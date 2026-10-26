//! Interrupt-woken 3x3 button matrix.
//!
//! Rows are inputs with the internal pull-up; columns are open-drain outputs.
//! A column can only pull its switches low, never drive a line high, so two
//! keys held in one row can never short two columns against each other.
//!
//! While idle every column sits low and the thread blocks on a low-level
//! interrupt on the rows: a press drags its row low and wakes it (also out of
//! light sleep, see [`run`]). It then scans one column at a time every
//! [`SCAN_PERIOD`] until every key is released, and goes back to sleep.
//!
//! Level-triggered because GPIO wakeup from light sleep only supports levels.
//! The HAL masks a pin's interrupt inside the ISR, so a held key cannot
//! re-trigger it; the interrupts are only re-armed once every key is up.
//!
//! Without a diode per switch, three keys held in an L shape make the fourth
//! corner of that rectangle read as pressed too. One or two keys never do.

use std::num::NonZeroU32;
use std::thread;
use std::time::Duration;

use anyhow::Result;

use esp_idf_svc::hal::delay::Ets;
use esp_idf_svc::hal::gpio::{Input, InterruptType, Level, Output, PinDriver};
use esp_idf_svc::hal::sleep;
use esp_idf_svc::hal::task::notification::Notification;

pub const ROWS: usize = 3;
pub const COLS: usize = 3;

/// Only runs while a key is down; the thread blocks the rest of the time.
const SCAN_PERIOD: Duration = Duration::from_millis(5);

/// A key counts as released after this many clean scans (4 x 5 ms = 20 ms),
/// so contact bounce right after a press cannot register a second press.
const RELEASE_SCANS: u8 = 4;

/// Time for a row to recover through its pull-up once a column lets go.
/// The real RC time is microseconds; this is margin. Busy-waits.
const SETTLE_US: u32 = 200;

/// Scan the matrix forever, calling `on_press` with `row * COLS + col` for
/// every new press. Must stay on the thread that calls it: the wakeup is a
/// task notification to this thread.
pub fn run(
    mut rows: [PinDriver<'static, Input>; ROWS],
    mut cols: [PinDriver<'static, Output>; COLS],
    mut on_press: impl FnMut(u8),
) -> Result<()> {
    let notification = Notification::new();
    let bit = NonZeroU32::new(1).unwrap();

    for row in &mut rows {
        row.set_interrupt_type(InterruptType::LowLevel)?;
        // Lets a press wake the chip from light sleep too. A no-op cost when
        // light sleep is off.
        sleep::gpio::configure_light(row.pin(), Level::Low)?;

        let notifier = notification.notifier();
        // SAFETY: the ISR only calls `notify_and_yield`, which is ISR-safe,
        // and the notified thread runs this function forever.
        unsafe {
            row.subscribe(move || {
                notifier.notify_and_yield(bit);
            })?;
        }
    }

    let mut pressed = [[false; COLS]; ROWS];
    let mut clean_scans = [[0u8; COLS]; ROWS];

    loop {
        for col in &mut cols {
            col.set_low()?;
        }
        // A key that is already held fires the level interrupt at once.
        for row in &mut rows {
            row.enable_interrupt()?;
        }
        notification.wait_any();
        // The ISR only masked the row that fired.
        for row in &mut rows {
            row.disable_interrupt()?;
        }
        for col in &mut cols {
            col.set_high()?;
        }

        // Pin state is the source of truth, so a stale or spurious wakeup
        // just scans once and goes back to sleep.
        loop {
            for (c, col) in cols.iter_mut().enumerate() {
                col.set_low()?;
                Ets::delay_us(SETTLE_US);
                for (r, row) in rows.iter().enumerate() {
                    if row.is_low() {
                        clean_scans[r][c] = 0;
                        if !pressed[r][c] {
                            pressed[r][c] = true;
                            on_press((r * COLS + c) as u8);
                        }
                    } else if pressed[r][c] {
                        clean_scans[r][c] += 1;
                        if clean_scans[r][c] >= RELEASE_SCANS {
                            pressed[r][c] = false;
                            clean_scans[r][c] = 0;
                        }
                    }
                }
                col.set_high()?;
            }

            if pressed.iter().flatten().all(|&p| !p) {
                break;
            }
            thread::sleep(SCAN_PERIOD);
        }
    }
}
