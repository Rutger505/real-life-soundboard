//! Status LED: one short flash per press.
//!
//! [`StatusLed::flash`] turns the LED on and arms a one-shot esp_timer that
//! turns it off again, so the caller never waits for it.

use std::sync::{Arc, Mutex};
use std::time::Duration;

use esp_idf_svc::hal::gpio::{Output, PinDriver};
use esp_idf_svc::sys::EspError;
use esp_idf_svc::timer::{EspTaskTimerService, EspTimer};

/// How long the LED stays lit after a press.
const FLASH: Duration = Duration::from_millis(60);

pub struct StatusLed {
    pin: Arc<Mutex<PinDriver<'static, Output>>>,
    /// Wakes the chip from light sleep, so the LED never stays on longer.
    off: EspTimer<'static>,
}

impl StatusLed {
    pub fn new(
        mut pin: PinDriver<'static, Output>,
        timers: &EspTaskTimerService,
    ) -> Result<Self, EspError> {
        pin.set_low()?;
        let pin = Arc::new(Mutex::new(pin));

        let off_pin = pin.clone();
        let off = timers.timer(move || {
            let _ = off_pin.lock().unwrap().set_low();
        })?;

        Ok(Self { pin, off })
    }

    /// Light the LED for [`FLASH`]. A flash during a flash extends it.
    pub fn flash(&self) {
        let _ = self.pin.lock().unwrap().set_high();
        if let Err(e) = self.off.after(FLASH) {
            log::warn!("[led] timer: {e}");
        }
    }
}
