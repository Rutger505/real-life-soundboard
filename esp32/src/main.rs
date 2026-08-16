//! Real Live Soundboard — ESP32 firmware.
//!
//! 9 push buttons and a status LED on the wrist. A press flashes the LED and
//! sends the button index (0..=8) as a one-byte BLE GATT notification to the
//! paired phone, which plays the configured clip.
//!
//! The device runs off a small LiPo, so the whole firmware is arranged around
//! doing nothing cheaply: [`buttons`] blocks on a GPIO interrupt instead of
//! polling, [`led`] never sleeps in the main loop, [`ble`] keeps the radio off
//! between connection events, and [`power`] scales the CPU down in between. See
//! each module for the details.

mod ble;
mod buttons;
mod led;
mod power;

use std::time::Instant;

use anyhow::Result;
use log::info;

use esp_idf_hal::gpio::{Input, PinDriver, Pull};
use esp_idf_hal::peripherals::Peripherals;
use esp_idf_svc::nvs::EspDefaultNvsPartition;

use buttons::ButtonScanner;
use led::StatusLed;

fn main() -> Result<()> {
    esp_idf_svc::sys::link_patches();
    esp_idf_svc::log::EspLogger::initialize_default();

    let peripherals = Peripherals::take()?;
    let nvs = EspDefaultNvsPartition::take()?;

    // Distinct fields, so these are partial moves rather than a whole-struct one.
    let pins = peripherals.pins;
    let modem = peripherals.modem;

    let mut led = StatusLed::new(PinDriver::output(pins.gpio2)?)?;

    // All use internal pull-downs, so a press pulls the pin high. Wire each
    // button between its GPIO and 3.3V — no external resistors needed.
    //
    // Strapping pins are avoided: GPIO5 (index 1 → GPIO23) as before, and with
    // pull-downs GPIO12/GPIO15 are also unsafe at boot, so those two buttons
    // sit on GPIO25/GPIO26 instead. Input-only pins (34/35/36/39) are never
    // used here because they lack internal pull-downs entirely.
    let button_pins: [PinDriver<'_, Input>; buttons::COUNT] = [
        PinDriver::input(pins.gpio4, Pull::Down)?,
        PinDriver::input(pins.gpio23, Pull::Down)?,
        PinDriver::input(pins.gpio25, Pull::Down)?,
        PinDriver::input(pins.gpio13, Pull::Down)?,
        PinDriver::input(pins.gpio14, Pull::Down)?,
        PinDriver::input(pins.gpio26, Pull::Down)?,
        PinDriver::input(pins.gpio16, Pull::Down)?,
        PinDriver::input(pins.gpio17, Pull::Down)?,
        PinDriver::input(pins.gpio18, Pull::Down)?,
    ];
    let mut scanner = ButtonScanner::new(button_pins)?;

    let server = ble::Server::new(modem, nvs)?;
    power::set_ble_tx_power()?;

    // Last, so the stack is fully up before the CPU is allowed to scale down.
    power::configure()?;
    info!(
        "soundboard up (light sleep: {})",
        if power::LIGHT_SLEEP { "on" } else { "off" }
    );

    loop {
        // Blocks indefinitely whenever the LED is off and no button is held,
        // which is the overwhelming majority of the time.
        let pressed = scanner.wait(led.deadline())?;
        let now = Instant::now();

        for index in buttons::indices(pressed) {
            info!("button {index} pressed");
            server.notify_button(index as u8);
            led.flash(now);
        }

        led.update(now);
    }
}
