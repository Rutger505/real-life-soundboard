//! Status LED.
//!
//! A press needs visible feedback, but every millisecond the LED is lit is a
//! millisecond the main task cannot go back to sleep — so this is one short
//! flash driven from the main loop's deadline, never a blocking blink sequence.

use std::time::{Duration, Instant};

use esp_idf_hal::gpio::{Output, PinDriver};
use esp_idf_svc::sys::EspError;

/// How long the LED stays lit after a press.
const FLASH: Duration = Duration::from_millis(60);

pub struct StatusLed<'d> {
    pin: PinDriver<'d, Output>,
    off_at: Option<Instant>,
}

impl<'d> StatusLed<'d> {
    pub fn new(mut pin: PinDriver<'d, Output>) -> Result<Self, EspError> {
        pin.set_low()?;

        Ok(Self { pin, off_at: None })
    }

    /// Light the LED; [`Self::update`] turns it off again.
    pub fn flash(&mut self, now: Instant) {
        self.pin.set_high().ok();
        self.off_at = Some(now + FLASH);
    }

    /// When the LED next needs attention, or `None` if it is already off.
    /// The main loop uses this to decide how long it may sleep.
    pub fn deadline(&self) -> Option<Instant> {
        self.off_at
    }

    pub fn update(&mut self, now: Instant) {
        if self.off_at.is_some_and(|off_at| now >= off_at) {
            self.pin.set_low().ok();
            self.off_at = None;
        }
    }
}
