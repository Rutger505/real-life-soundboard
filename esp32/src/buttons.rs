//! Interrupt-driven button input.
//!
//! Each button is a task blocked on its pin's rising edge, so the core idles
//! while nobody is pressing. Internal pull-down means a press pulls the line
//! high. A second edge within [`DEBOUNCE`] is bounce and gets ignored.

use embassy_time::{Duration, Instant, Timer};
use esp_hal::gpio::Input;

/// A second edge on the same button inside this window is contact bounce.
const DEBOUNCE: Duration = Duration::from_millis(40);

/// One task per button. `index` is the byte notified to the phone.
/// Pool size must be at least [`crate::BUTTON_COUNT`].
#[embassy_executor::task(pool_size = crate::BUTTON_COUNT)]
pub async fn button_task(mut pin: Input<'static>, index: u8) {
    let mut last_press: Option<Instant> = None;

    loop {
        // Parks the task until the button is pressed.
        pin.wait_for_rising_edge().await;

        let now = Instant::now();
        let bounced = last_press.is_some_and(|last| now - last < DEBOUNCE);
        if bounced {
            continue;
        }
        last_press = Some(now);

        log::info!("button {index} pressed");
        // Drop the press if nothing is draining the channel. The LED still
        // flashes, so the wearer gets feedback either way.
        let _ = crate::BUTTON_EVENTS.try_send(index);
        crate::LED_SIGNAL.signal(());

        // Swallow the bounce burst before re-arming so one tap can't post twice.
        Timer::after(DEBOUNCE).await;
    }
}
