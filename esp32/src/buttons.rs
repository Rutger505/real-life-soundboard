//! Interrupt-driven button input.
//!
//! The obvious implementation — read all nine pins every 20 ms — keeps the CPU
//! out of every low-power state permanently, for nine register reads per tick.
//! Instead each pin raises an interrupt that unblocks the main task, so while
//! nobody is touching the board the main task is descheduled and the chip is
//! free to scale down (and, on hardware that supports it, light-sleep).
//!
//! Two hardware facts shape the design:
//!
//! - GPIO wakeup from light sleep is **level**-triggered only, and
//!   `gpio_wakeup_enable` writes the pin's interrupt type, so these pins
//!   interrupt on "low level" rather than on a falling edge.
//! - The ESP-IDF HAL masks a pin's interrupt from inside the ISR (otherwise a
//!   level-triggered pin would re-enter forever and trip the watchdog).
//!
//! So a press wakes us once, and we only re-arm the interrupts once every
//! button reads released again. That is what stops a held button from
//! re-triggering, and it is why the scanner polls at [`ACTIVE_TICK`] for the
//! short while a button is actually down.

use std::num::NonZeroU32;
use std::time::{Duration, Instant};

use esp_idf_hal::delay::TickType;
use esp_idf_hal::gpio::{Input, InterruptType, Level, PinDriver};
use esp_idf_hal::sleep;
use esp_idf_hal::task::notification::Notification;
use esp_idf_svc::sys::EspError;

/// Number of buttons. Kept below 16 so a press set fits in the [`u16`] mask
/// returned by [`ButtonScanner::wait`].
pub const COUNT: usize = 9;

/// A second press of the same button within this window is contact bounce.
const DEBOUNCE: Duration = Duration::from_millis(40);

/// Poll interval used *only* while a button is held down or another deadline is
/// pending. Idle costs nothing, so this can be short without affecting battery
/// life.
const ACTIVE_TICK: Duration = Duration::from_millis(20);

/// Bitmask of buttons, one bit per index.
pub type PressMask = u16;

pub struct ButtonScanner<'d> {
    pins: [PinDriver<'d, Input>; COUNT],
    notification: Notification,
    /// Debounced state from the previous scan, for edge detection.
    down: [bool; COUNT],
    /// When each button was last accepted as pressed.
    last_press: [Option<Instant>; COUNT],
}

impl<'d> ButtonScanner<'d> {
    /// Wire up interrupts and light-sleep wakeup for the given pins, which are
    /// expected to be inputs with a pull-up (pressed = low).
    pub fn new(mut pins: [PinDriver<'d, Input>; COUNT]) -> Result<Self, EspError> {
        // Bound to the calling task, so the scanner must be used from the task
        // that constructs it.
        let notification = Notification::new();

        for (index, pin) in pins.iter_mut().enumerate() {
            pin.set_interrupt_type(InterruptType::LowLevel)?;
            // Also lets the press wake the chip out of light sleep. Sets the
            // interrupt type again to the same value.
            sleep::gpio::configure_light(pin.pin(), Level::Low)?;

            let notifier = notification.notifier();
            let bit = NonZeroU32::new(1 << index).expect("index < COUNT < 32");

            // SAFETY: the closure only calls `notify_and_yield`, which is
            // ISR-safe, and the notified task is `main`, which outlives every
            // interrupt that can reach this notifier.
            unsafe {
                pin.subscribe(move || {
                    notifier.notify_and_yield(bit);
                })?;
            }
        }

        Ok(Self {
            pins,
            notification,
            down: [false; COUNT],
            last_press: [None; COUNT],
        })
    }

    /// Block until there is something to do, then report the buttons that were
    /// newly pressed.
    ///
    /// `busy_until` is the main loop's next unrelated deadline (the status LED).
    /// With no deadline pending and every button released, this blocks
    /// indefinitely — the state the firmware is in almost all of the time.
    pub fn wait(&mut self, busy_until: Option<Instant>) -> Result<PressMask, EspError> {
        if busy_until.is_none() && self.all_released() {
            self.arm()?;
            self.notification.wait_any();
        } else {
            let now = Instant::now();
            let timeout = busy_until
                .map(|deadline| deadline.saturating_duration_since(now))
                .unwrap_or(ACTIVE_TICK)
                .min(ACTIVE_TICK);

            self.notification.wait(TickType::from(timeout).ticks());
        }

        Ok(self.scan())
    }

    /// (Re-)enable the pin interrupts. Only ever called with every button
    /// released, so no interrupt can be pending against a held button.
    fn arm(&mut self) -> Result<(), EspError> {
        for pin in &mut self.pins {
            pin.enable_interrupt()?;
        }

        Ok(())
    }

    fn all_released(&self) -> bool {
        self.pins.iter().all(|pin| pin.is_high())
    }

    /// Read the pins and return the newly pressed, debounced buttons.
    ///
    /// The notification payload is deliberately ignored: pin state is the
    /// authoritative source, which makes a spurious or stale wakeup harmless.
    fn scan(&mut self) -> PressMask {
        let now = Instant::now();
        let mut pressed = 0;

        for index in 0..COUNT {
            let down = self.pins[index].is_low();
            let was_down = std::mem::replace(&mut self.down[index], down);

            if !down || was_down {
                continue;
            }

            let bounced = self.last_press[index]
                .is_some_and(|last| now.saturating_duration_since(last) < DEBOUNCE);
            if !bounced {
                self.last_press[index] = Some(now);
                pressed |= 1 << index;
            }
        }

        pressed
    }
}

/// Iterate the button indices set in `mask`.
pub fn indices(mask: PressMask) -> impl Iterator<Item = usize> {
    (0..COUNT).filter(move |index| mask & (1 << index) != 0)
}
