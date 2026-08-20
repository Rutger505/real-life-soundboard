//! Status LED: one short non-blocking flash per press.
//!
//! The task sleeps on [`crate::LED_SIGNAL`] and, when poked, lights the LED
//! for [`FLASH`] then turns it off. It never blocks any other task.

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
