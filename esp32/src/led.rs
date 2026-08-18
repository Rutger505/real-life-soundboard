//! Status LED.
//!
//! A press needs visible feedback, but every millisecond the LED is lit is a
//! millisecond of nothing useful — so this is one short, non-blocking flash.
//! The task sleeps on [`crate::LED_SIGNAL`] and, when poked, lights the LED for
//! [`FLASH`] and turns it off again. It never blocks any other task: button
//! input and BLE run on their own tasks the whole time.

use embassy_time::{Duration, Timer};
use esp_hal::gpio::Output;

/// How long the LED stays lit after a press.
const FLASH: Duration = Duration::from_millis(60);

/// Drive the status LED (GPIO2, active-high). Owns the pin for the whole run.
#[embassy_executor::task]
pub async fn led_task(mut led: Output<'static>) {
    led.set_low();

    loop {
        // Parks until a press is registered.
        crate::LED_SIGNAL.wait().await;

        led.set_high();
        Timer::after(FLASH).await;
        led.set_low();
    }
}
