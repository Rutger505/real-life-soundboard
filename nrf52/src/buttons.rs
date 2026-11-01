//! Sense-woken 3x3 button matrix.
//!
//! Rows are inputs with the internal pull-up; columns are open-drain outputs
//! (`Standard0Disconnect1`). A column can only pull its switches low, never
//! drive a line high, so two keys held in one row can never short two columns
//! against each other.
//!
//! While idle every column sits low and the task waits for any row to go low.
//! That wait uses the GPIO `SENSE` mechanism (the PORT event), which costs
//! nothing while the chip sleeps, unlike a GPIOTE IN channel. A press drags
//! its row low and wakes the task. It then scans one column at a time every
//! [`SCAN_PERIOD`] until every key is released, and goes back to waiting.
//!
//! Idle draw is zero: with no key down no current flows through a pull-up.
//!
//! Without a diode per switch, three keys held in an L shape make the fourth
//! corner of that rectangle read as pressed too. One or two keys never do.

use embassy_futures::select::select3;
use embassy_nrf::gpio::{Input, Output};
use embassy_time::{Duration, Timer};

use crate::{ble, led};

pub const ROWS: usize = 3;
pub const COLS: usize = 3;

/// Only runs while a key is down.
const SCAN_PERIOD: Duration = Duration::from_millis(5);

/// A key counts as released after this many clean scans (4 x 5 ms = 20 ms),
/// so contact bounce right after a press cannot register a second press.
const RELEASE_SCANS: u8 = 4;

/// Time for a row to recover through its pull-up once a column lets go. The
/// real RC time is microseconds; this rounds up to two RTC ticks.
const SETTLE: Duration = Duration::from_micros(50);

#[embassy_executor::task]
pub async fn run(mut rows: [Input<'static>; ROWS], mut cols: [Output<'static>; COLS]) -> ! {
    let mut pressed = [[false; COLS]; ROWS];
    let mut clean_scans = [[0u8; COLS]; ROWS];

    loop {
        for col in &mut cols {
            col.set_low();
        }
        // Returns at once if a key is already held.
        {
            let [r0, r1, r2] = &mut rows;
            select3(r0.wait_for_low(), r1.wait_for_low(), r2.wait_for_low()).await;
        }
        for col in &mut cols {
            col.set_high();
        }

        // Pin state is the source of truth, so a spurious wakeup just scans
        // once and goes back to waiting.
        loop {
            for (c, col) in cols.iter_mut().enumerate() {
                col.set_low();
                Timer::after(SETTLE).await;
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
                col.set_high();
            }

            if pressed.iter().flatten().all(|&p| !p) {
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
