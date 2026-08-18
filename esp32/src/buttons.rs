//! Interrupt-driven button input.
//!
//! The obvious implementation — read all nine pins every 20 ms — keeps the CPU
//! out of every idle state permanently. Instead each button is a task blocked
//! on its pin's rising-edge interrupt: while nobody is touching the board every
//! task is parked and the executor idles the core. A press wakes exactly one
//! task, which is the state the firmware is in essentially all of the time.
//!
//! Pins use an internal pull-down, so a press pulls the line HIGH and the press
//! edge is the rising edge. Contact bounce is handled per button by ignoring a
//! second edge inside [`DEBOUNCE`].

use embassy_time::{Duration, Instant, Timer};
use esp_hal::gpio::Input;

/// A second edge on the same button within this window is contact bounce.
const DEBOUNCE: Duration = Duration::from_millis(40);

/// One task per physical button. `index` is the byte notified to the phone.
///
/// The pool size must be at least [`crate::BUTTON_COUNT`]; each spawned button
/// occupies one slot.
#[embassy_executor::task(pool_size = crate::BUTTON_COUNT)]
pub async fn button_task(mut pin: Input<'static>, index: u8) {
    let mut last_press: Option<Instant> = None;

    loop {
        // Parks the task (and lets the core idle) until the button is pressed.
        pin.wait_for_rising_edge().await;

        let now = Instant::now();
        let bounced = last_press.is_some_and(|last| now - last < DEBOUNCE);
        if bounced {
            continue;
        }
        last_press = Some(now);

        log::info!("button {index} pressed");
        // Lossy on purpose: drop the press if nothing is draining the channel
        // (no phone connected) rather than stalling this task. The LED still
        // flashes so the wearer gets feedback regardless.
        let _ = crate::BUTTON_EVENTS.try_send(index);
        crate::LED_SIGNAL.signal(());

        // A physical press is held for tens of ms; swallow the bounce burst
        // before re-arming so a single tap can't post twice.
        Timer::after(DEBOUNCE).await;
    }
}
