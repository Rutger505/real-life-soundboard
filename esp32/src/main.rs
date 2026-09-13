//! Bare-metal ESP32 firmware for a wrist soundboard.
//!
//! A 3x3 button matrix + a status LED. A press flashes the LED and sends the
//! button index (0..=8) as a one-byte BLE notification to the paired phone.
//!
//! Runs on esp-hal with the Embassy executor (esp-rtos), the esp-radio BLE
//! controller and the trouble-host GATT stack. The matrix task sleeps on the
//! row pins and only scans while a key is down, so the core idles whenever
//! nobody is pressing.

#![no_std]
#![no_main]

mod ble;
mod buttons;
mod led;

use embassy_executor::Spawner;
use embassy_sync::blocking_mutex::raw::CriticalSectionRawMutex;
use embassy_sync::channel::Channel;
use embassy_sync::signal::Signal;

use esp_hal::clock::CpuClock;
use esp_hal::gpio::{DriveMode, Input, InputConfig, Level, Output, OutputConfig, Pull};
use esp_hal::interrupt::software::SoftwareInterruptControl;
use esp_hal::timer::timg::TimerGroup;

use esp_backtrace as _;

// The ESP-IDF bootloader needs this to recognise the app image.
esp_bootloader_esp_idf::esp_app_desc!();

/// Debounced press indices, from the matrix task to the BLE notify task.
/// Lossy on purpose: with no phone draining it, presses drop instead of
/// blocking the scan.
pub static BUTTON_EVENTS: Channel<CriticalSectionRawMutex, u8, 16> = Channel::new();

/// Poked on every accepted press so the LED task flashes without the matrix
/// task touching the GPIO. Re-arming during a flash is fine.
pub static LED_SIGNAL: Signal<CriticalSectionRawMutex, ()> = Signal::new();

#[esp_rtos::main]
async fn main(spawner: Spawner) {
    esp_println::logger::init_logger_from_env();

    // 80 MHz is plenty for one byte per press, and current scales with clock.
    let peripherals = esp_hal::init(esp_hal::Config::default().with_cpu_clock(CpuClock::_80MHz));

    // The BLE host keeps its ATT tables and packet pool here.
    esp_alloc::heap_allocator!(size: 72 * 1024);

    // Start the Embassy executor (timer tick + software interrupt).
    let timg0 = TimerGroup::new(peripherals.TIMG0);
    let sw_int = SoftwareInterruptControl::new(peripherals.SW_INTERRUPT);
    esp_rtos::start(timg0.timer0, sw_int.software_interrupt0);

    // Status LED: GPIO18, active-high, off. Not GPIO2: that one also drives
    // the dev board's own LED.
    let led = Output::new(peripherals.GPIO18, Level::Low, OutputConfig::default());
    spawner.spawn(led::led_task(led).unwrap());

    // Button matrix, 6 wires for 9 keys. The index notified to the phone is
    // row * 3 + column. Rows sit side by side on one header of a 30- or
    // 38-pin dev board and columns on the other, and none of the six is a
    // strapping or flash pin.
    let row_cfg = InputConfig::default().with_pull(Pull::Up);
    let rows = [
        Input::new(peripherals.GPIO25, row_cfg),
        Input::new(peripherals.GPIO26, row_cfg),
        Input::new(peripherals.GPIO27, row_cfg),
    ];
    let col_cfg = OutputConfig::default().with_drive_mode(DriveMode::OpenDrain);
    let cols = [
        Output::new(peripherals.GPIO17, Level::High, col_cfg),
        Output::new(peripherals.GPIO16, Level::High, col_cfg),
        Output::new(peripherals.GPIO4, Level::High, col_cfg),
    ];
    spawner.spawn(buttons::matrix_task(rows, cols).unwrap());

    // Owns the radio and never returns.
    ble::run(peripherals.BT).await;
}
