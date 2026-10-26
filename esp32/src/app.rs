//! The `app` thread: battery measurements and everything sent to the phone.
//!
//! The battery is measured on every press, before the notification goes out,
//! because the cell sags while the radio transmits. Without presses it is
//! measured every [`BATTERY_PERIOD`], and once right after a phone connects so
//! its first read is fresh.

use std::sync::mpsc::{Receiver, RecvTimeoutError};
use std::time::{Duration, Instant};

use crate::battery::{Battery, Reading};
use crate::ble::Server;
use crate::Event;

/// Battery check when nobody presses anything.
const BATTERY_PERIOD: Duration = Duration::from_secs(30 * 60);

/// Let the phone finish discovery and subscribing before asking it to slow
/// the link down.
const CONN_PARAMS_DELAY: Duration = Duration::from_secs(1);

pub fn run(inbox: Receiver<Event>, server: Server, mut battery: Battery) {
    // The last percentage sent to the phone. Both battery characteristics
    // only notify when it changes; reads always see the latest value.
    let mut last_percent = None;
    let mut next_sample = Instant::now();
    let mut conn_params_at = None;

    loop {
        let deadline = conn_params_at.map_or(next_sample, |at: Instant| at.min(next_sample));
        let timeout = deadline.saturating_duration_since(Instant::now());

        match inbox.recv_timeout(timeout) {
            Ok(Event::Press(index)) => {
                let reading = battery.measure();
                server.notify_button(index);
                publish(&server, reading, &mut last_percent);
                next_sample = Instant::now() + BATTERY_PERIOD;
            }
            Ok(Event::Connected) => {
                // A new phone has not seen any value yet.
                last_percent = None;
                publish(&server, battery.measure(), &mut last_percent);
                next_sample = Instant::now() + BATTERY_PERIOD;
                conn_params_at = Some(Instant::now() + CONN_PARAMS_DELAY);
            }
            Err(RecvTimeoutError::Timeout) => {
                let now = Instant::now();
                if conn_params_at.is_some_and(|at| now >= at) {
                    conn_params_at = None;
                    server.request_low_power_conn_params();
                }
                if now >= next_sample {
                    publish(&server, battery.measure(), &mut last_percent);
                    next_sample = now + BATTERY_PERIOD;
                }
            }
            // Every sender is gone, which never happens: main owns one forever.
            Err(RecvTimeoutError::Disconnected) => return,
        }
    }
}

fn publish(server: &Server, reading: Reading, last_percent: &mut Option<u8>) {
    let changed = *last_percent != Some(reading.percent);
    if server.publish_battery(reading, changed) {
        *last_percent = Some(reading.percent);
    }
}
