//! nRF52840 firmware for the soundboard, on a Seeed XIAO nRF52840.
//!
//! A 3x3 button matrix + a status LED. A press flashes the LED and sends the
//! button index (0..=8) as a one-byte BLE notification to the paired phone.
//! The battery level is measured through the XIAO's own divider and served
//! over BLE too. GATT layout and UUIDs match the ESP32 firmware, so the
//! Android app works with either.
//!
//! Stack: Embassy (async executor + HAL), Nordic's SoftDevice Controller
//! (`nrf-sdc`, the BLE link layer) and TrouBLE (`trouble-host`, the host).
//!
//! Tasks:
//! - [`buttons::run`] sleeps on GPIO sense and only scans while a key is down.
//! - [`led::run`] times the LED flash.
//! - `mpsl_task` services Nordic's radio scheduler.
//! - `main` runs the BLE host and the app logic ([`ble::run`]).
//!
//! Power: the executor sleeps (WFE, System ON idle) whenever nothing is
//! ready. See [`init_power`] for the board-level settings.

#![no_std]
#![no_main]

mod battery;
mod ble;
mod buttons;
mod flash;
mod led;

use core::panic::PanicInfo;

use cortex_m::peripheral::SCB;
use cortex_m_rt::{ExceptionFrame, exception};
use defmt::{error, info, unwrap};
use defmt_rtt as _;
use embassy_executor::Spawner;
use embassy_nrf::config::{Config, LfclkSource};
use embassy_nrf::gpio::{Input, Level, Output, OutputDrive, Pull};
use embassy_nrf::mode::Async;
use embassy_nrf::peripherals::{QSPI, RNG};
use embassy_nrf::{bind_interrupts, qspi, rng, saadc};
use nrf_sdc::mpsl::MultiprotocolServiceLayer;
use nrf_sdc::{self as sdc, mpsl};
use static_cell::StaticCell;
use trouble_host::PacketPool;
use trouble_host::prelude::DefaultPacketPool;

bind_interrupts!(pub struct Irqs {
    RNG => rng::InterruptHandler<RNG>;
    SAADC => saadc::InterruptHandler;
    QSPI => qspi::InterruptHandler<QSPI>;
    EGU0_SWI0 => mpsl::LowPrioInterruptHandler;
    CLOCK_POWER => mpsl::ClockInterruptHandler;
    RADIO => mpsl::HighPrioInterruptHandler;
    TIMER0 => mpsl::HighPrioInterruptHandler;
    RTC0 => mpsl::HighPrioInterruptHandler;
});

/// Accuracy of the XIAO's 32.768 kHz crystal, as told to the radio
/// scheduler. Tolerance plus temperature drift sits well inside this; the
/// margin only widens the receive window a little.
const LFXO_PPM: u16 = 50;

/// RAM handed to the SoftDevice Controller. Too little makes the build fail
/// at boot with the size it needs in the log.
const SDC_MEM: usize = 4720;

/// Outgoing / incoming L2CAP buffers for the one link.
const L2CAP_TXQ: u8 = 3;
const L2CAP_RXQ: u8 = 3;

#[embassy_executor::main]
async fn main(spawner: Spawner) {
    let p = embassy_nrf::init(init_power());
    info!("soundboard {} up", env!("CARGO_PKG_VERSION"));

    // The 2 MB QSPI flash idles at several µA in standby; send it to deep
    // power-down. The firmware never uses it.
    flash::power_down(p.QSPI, p.P0_21, p.P0_25, p.P0_20, p.P0_24, p.P0_22, p.P0_23);

    // Button matrix, 6 wires for 9 keys, all on the XIAO's left header:
    // rows D0-D2, columns D3-D5. Rows are pulled up; columns are open-drain.
    let rows = [
        Input::new(p.P0_02, Pull::Up), // D0
        Input::new(p.P0_03, Pull::Up), // D1
        Input::new(p.P0_28, Pull::Up), // D2
    ];
    let cols = [
        Output::new(p.P0_29, Level::Low, OutputDrive::Standard0Disconnect1), // D3
        Output::new(p.P0_04, Level::Low, OutputDrive::Standard0Disconnect1), // D4
        Output::new(p.P0_05, Level::Low, OutputDrive::Standard0Disconnect1), // D5
    ];
    // External status LED on D6, active-high through its resistor.
    let led = Output::new(p.P1_11, Level::Low, OutputDrive::Standard);

    // Cell voltage on P0.31 through the XIAO's divider (enabled by P0.14),
    // charger status on P0.17.
    let battery = battery::Battery::new(p.SAADC, p.P0_31, p.P0_14, p.P0_17);

    // Radio scheduler: RTC0, TIMER0, TEMP and PPI channels belong to it.
    let mpsl_p = mpsl::Peripherals::new(p.RTC0, p.TIMER0, p.TEMP, p.PPI_CH19, p.PPI_CH30, p.PPI_CH31);
    let lfclk_cfg = mpsl::raw::mpsl_clock_lfclk_cfg_t {
        source: mpsl::raw::MPSL_CLOCK_LF_SRC_XTAL as u8,
        rc_ctiv: 0,
        rc_temp_ctiv: 0,
        accuracy_ppm: LFXO_PPM,
        skip_wait_lfclk_started: false,
    };
    static MPSL: StaticCell<MultiprotocolServiceLayer> = StaticCell::new();
    let mpsl = MPSL.init(unwrap!(MultiprotocolServiceLayer::new(mpsl_p, Irqs, lfclk_cfg)));
    spawner.spawn(unwrap!(mpsl_task(mpsl)));

    spawner.spawn(unwrap!(buttons::run(rows, cols)));
    spawner.spawn(unwrap!(led::run(led)));

    let sdc_p = sdc::Peripherals::new(
        p.PPI_CH17, p.PPI_CH18, p.PPI_CH20, p.PPI_CH21, p.PPI_CH22, p.PPI_CH23, p.PPI_CH24, p.PPI_CH25, p.PPI_CH26,
        p.PPI_CH27, p.PPI_CH28, p.PPI_CH29,
    );
    let mut rng = rng::Rng::new(p.RNG, Irqs);
    let mut sdc_mem = sdc::Mem::<SDC_MEM>::new();
    let sdc = unwrap!(build_sdc(sdc_p, &mut rng, mpsl, &mut sdc_mem));

    ble::run(sdc, ble::address(), battery).await
}

/// Board-level power settings.
///
/// - LFCLK from the XIAO's 32.768 kHz crystal: the RTCs and the radio
///   scheduler keep time from it while the chip sleeps, and a crystal needs a
///   narrower receive window than the RC oscillator.
/// - DC/DC for REG1: the XIAO has the inductor fitted, and it roughly halves
///   the radio's current against the LDO. REG0 is unused: the board feeds VDD
///   directly from its own 3.3 V regulator.
/// - Debug stays allowed (embassy-nrf's default), so the probe can always
///   reflash the chip.
fn init_power() -> Config {
    let mut config = Config::default();
    config.lfclk_source = LfclkSource::ExternalXtal;
    config.dcdc.reg0 = false;
    config.dcdc.reg0_voltage = None;
    config.dcdc.reg1 = true;
    config
}

fn build_sdc<'d, const N: usize>(
    p: sdc::Peripherals<'d>,
    rng: &'d mut rng::Rng<Async>,
    mpsl: &'d MultiprotocolServiceLayer,
    mem: &'d mut sdc::Mem<N>,
) -> Result<sdc::SoftdeviceController<'d>, sdc::Error> {
    sdc::Builder::new()?
        .support_adv()
        .support_peripheral()
        .peripheral_count(1)?
        .buffer_cfg(
            DefaultPacketPool::MTU as u16,
            DefaultPacketPool::MTU as u16,
            L2CAP_TXQ,
            L2CAP_RXQ,
        )?
        .build(p, rng, mpsl, mem)
}

#[embassy_executor::task]
async fn mpsl_task(mpsl: &'static MultiprotocolServiceLayer<'static>) -> ! {
    mpsl.run().await
}

/// Log and reset. Nobody watches the board, so a reboot beats a dead
/// soundboard; with the probe attached the message shows up first.
#[panic_handler]
fn panic(info: &PanicInfo) -> ! {
    error!("panic: {}", defmt::Display2Format(info));
    SCB::sys_reset()
}

#[exception]
unsafe fn HardFault(frame: &ExceptionFrame) -> ! {
    error!("hard fault at pc {=u32:#010x}", frame.pc());
    SCB::sys_reset()
}
