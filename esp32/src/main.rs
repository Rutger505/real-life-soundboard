//! ESP32 firmware for a wrist soundboard, on Rust + ESP-IDF (std).
//!
//! A 3x3 button matrix + a status LED. A press flashes the LED and sends the
//! button index (0..=8) as a one-byte BLE notification to the paired phone.
//! The battery level is measured on GPIO34 and served over BLE too.
//!
//! Threads:
//! - `main` runs the button matrix ([`buttons`]). It sleeps on a GPIO
//!   interrupt and only scans while a key is down.
//! - `app` ([`app`]) measures the battery and talks to the BLE server
//!   ([`ble`]), so a slow radio call never holds up the matrix.
//! - Bluedroid's own tasks run the GATT server callbacks.
//!
//! Power: modem sleep, dynamic frequency scaling and, with a 32.768 kHz
//! crystal, automatic light sleep. See [`power`].

mod app;
mod battery;
mod ble;
mod buttons;
mod led;
mod power;

use std::sync::mpsc;

use anyhow::Result;

use esp_idf_svc::hal::gpio::{PinDriver, Pull};
use esp_idf_svc::hal::peripherals::Peripherals;
use esp_idf_svc::nvs::EspDefaultNvsPartition;
use esp_idf_svc::timer::EspTaskTimerService;

/// Events for the `app` thread.
pub enum Event {
    /// Debounced press, button index `row * 3 + column`.
    Press(u8),
    /// A phone connected.
    Connected,
}

/// Presses queued while the app thread is busy. A full queue drops presses
/// rather than blocking the matrix scan.
const EVENT_QUEUE: usize = 16;

fn main() -> Result<()> {
    esp_idf_svc::sys::link_patches();
    esp_idf_svc::log::EspLogger::initialize_default();

    let peripherals = Peripherals::take()?;
    let pins = peripherals.pins;
    let nvs = EspDefaultNvsPartition::take()?;
    let timers = EspTaskTimerService::new()?;

    // Status LED: GPIO18, active-high. Not GPIO2: that one also drives the
    // dev board's own LED.
    let led = led::StatusLed::new(PinDriver::output(pins.gpio18)?, &timers)?;

    // Button matrix, 6 wires for 9 keys. Rows sit on one header of a 30- or
    // 38-pin dev board and columns on the other, and none of the six is a
    // strapping or flash pin. GPIO26/27 are skipped: the board has a fault
    // between them. GPIO32/33 are left for the 32.768 kHz crystal.
    let rows = [
        PinDriver::input(pins.gpio25, Pull::Up)?,
        PinDriver::input(pins.gpio14, Pull::Up)?,
        PinDriver::input(pins.gpio13, Pull::Up)?,
    ];
    let cols = [
        PinDriver::output_od(pins.gpio17)?,
        PinDriver::output_od(pins.gpio16)?,
        PinDriver::output_od(pins.gpio4)?,
    ];

    // Cell voltage through a 100k/100k divider (after the power switch).
    let battery = battery::Battery::new(peripherals.adc1, pins.gpio34)?;

    let (events, inbox) = mpsc::sync_channel(EVENT_QUEUE);
    let server = ble::Server::new(peripherals.modem, nvs, events.clone())?;
    power::set_ble_tx_power()?;

    std::thread::Builder::new()
        .name("app".into())
        .stack_size(6 * 1024)
        .spawn(move || app::run(inbox, server, battery))?;

    // Last, so the stack is fully up before the CPU may scale down or sleep.
    power::configure()?;
    log::warn!(
        "soundboard {} up (light sleep: {})",
        env!("CARGO_PKG_VERSION"),
        if power::LIGHT_SLEEP { "on" } else { "off" }
    );

    buttons::run(rows, cols, |index| {
        log::info!("button {index} pressed");
        led.flash();
        // Dropped when the queue is full; the LED still gives feedback.
        let _ = events.try_send(Event::Press(index));
    })
}
