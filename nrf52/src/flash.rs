//! The XIAO's 2 MB QSPI flash (P25Q16H), sent to deep power-down.
//!
//! The firmware never uses it, but a NOR flash in standby draws several µA,
//! as much as the rest of the idle board. The QSPI peripheral sends the
//! deep power-down opcode itself when the driver is dropped with
//! `deep_power_down` configured, then turns itself off. Chip select stays
//! driven high afterwards, so the flash cannot wake up by accident.

use embassy_nrf::Peri;
use embassy_nrf::gpio::Pin;
use embassy_nrf::peripherals::QSPI;
use embassy_nrf::qspi::{self, DeepPowerDownConfig, Qspi};

use crate::Irqs;

/// 2 MB, from the board's device tree.
const CAPACITY: u32 = 2 * 1024 * 1024;

/// tDP (3 µs) and tRES1 (8 µs) of the P25Q16H, in the QSPI's 16 µs units.
const DPD_TIME: u16 = 1;

pub fn power_down(
    qspi: Peri<'static, QSPI>,
    sck: Peri<'static, impl Pin>,
    csn: Peri<'static, impl Pin>,
    io0: Peri<'static, impl Pin>,
    io1: Peri<'static, impl Pin>,
    io2: Peri<'static, impl Pin>,
    io3: Peri<'static, impl Pin>,
) {
    let mut config = qspi::Config::default();
    config.capacity = CAPACITY;
    config.deep_power_down = Some(DeepPowerDownConfig {
        enter_time: DPD_TIME,
        exit_time: DPD_TIME,
    });
    let flash = Qspi::new(qspi, Irqs, sck, csn, io0, io1, io2, io3, config);
    drop(flash);
    defmt::info!("[flash] deep power-down");
}
