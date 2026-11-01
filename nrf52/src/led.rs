//! Status LED: one short flash per press.
//!
//! [`flash`] only raises a signal, so the matrix scan never waits for the
//! LED. A flash during a flash extends it.

use embassy_futures::select::{Either, select};
use embassy_nrf::gpio::Output;
use embassy_sync::blocking_mutex::raw::CriticalSectionRawMutex;
use embassy_sync::signal::Signal;
use embassy_time::{Duration, Timer};

/// How long the LED stays lit after a press.
const FLASH: Duration = Duration::from_millis(60);

static TRIGGER: Signal<CriticalSectionRawMutex, ()> = Signal::new();

/// Light the LED for [`FLASH`].
pub fn flash() {
    TRIGGER.signal(());
}

#[embassy_executor::task]
pub async fn run(mut led: Output<'static>) -> ! {
    loop {
        TRIGGER.wait().await;
        led.set_high();
        while let Either::First(()) = select(TRIGGER.wait(), Timer::after(FLASH)).await {}
        led.set_low();
    }
}
