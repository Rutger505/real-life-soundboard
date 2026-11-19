//! Sense-woken buttons, one pin each.
//!
//! Every button sits between its own pin and GND, with the internal pull-up
//! holding the pin high. A press reads low.
//!
//! While idle the task waits for any pin to go low. That wait uses the GPIO
//! `SENSE` mechanism (the PORT event), which costs nothing while the chip
//! sleeps, unlike a GPIOTE IN channel. A press wakes the task. It then reads
//! every pin each [`SCAN_PERIOD`] until every button is released, and goes back
//! to waiting.
//!
//! Idle draw is zero: with no button down no current flows through a pull-up.

use embassy_futures::select::select_array;
use embassy_nrf::gpio::Input;
use embassy_time::{Duration, Timer};

use crate::{ble, led};

pub const BUTTONS: usize = 9;

/// Only runs while a button is down.
const SCAN_PERIOD: Duration = Duration::from_millis(5);

/// A button counts as released after this many clean scans (4 x 5 ms = 20 ms),
/// so contact bounce right after a press cannot register a second press.
const RELEASE_SCANS: u8 = 4;

#[embassy_executor::task]
pub async fn run(mut buttons: [Input<'static>; BUTTONS]) -> ! {
    let mut pressed = [false; BUTTONS];
    let mut clean_scans = [0u8; BUTTONS];

    loop {
        // Returns at once if a button is already held.
        select_array(buttons.each_mut().map(|button| button.wait_for_low())).await;

        // Pin state is the source of truth, so a spurious wakeup just scans
        // once and goes back to waiting.
        loop {
            for (index, button) in buttons.iter().enumerate() {
                if button.is_low() {
                    clean_scans[index] = 0;
                    if !pressed[index] {
                        pressed[index] = true;
                        on_press(index as u8);
                    }
                } else if pressed[index] {
                    clean_scans[index] += 1;
                    if clean_scans[index] >= RELEASE_SCANS {
                        pressed[index] = false;
                        clean_scans[index] = 0;
                    }
                }
            }

            if pressed.iter().all(|&p| !p) {
                break;
            }
            Timer::after(SCAN_PERIOD).await;
        }
    }
}

fn on_press(index: u8) {
    defmt::info!("[buttons] {} pressed", index);
    led::flash();
    ble::press(index);
}
