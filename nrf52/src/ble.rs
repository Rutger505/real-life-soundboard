//! BLE: GATT server, advertising and everything sent to the phone.
//!
//! Same services and UUIDs as the ESP32 firmware:
//! - Soundboard service: Button (1 byte, read/notify) and Battery (raw)
//!   (3 bytes, read/notify).
//! - Battery Service 0x180F: Battery Level 0x2A19 (read/notify).
//!
//! The link is tuned for low radio time:
//! - Advertising is fast for [`FAST_ADV_FOR`] after boot or a disconnect,
//!   so the phone reconnects quickly, then slows down until it does.
//! - Once connected, the firmware asks for a short interval with a large
//!   peripheral latency. The radio only wakes every (latency + 1) intervals
//!   while idle, but a press still goes out at the very next interval.
//!
//! The battery is measured on every press, before the notification goes out,
//! because the cell sags while the radio transmits. Without presses it is
//! measured every [`BATTERY_PERIOD`], and once right after a phone connects
//! so its first read is fresh.

use defmt::{info, unwrap, warn};
use embassy_futures::join::join;
use embassy_futures::select::{Either, select};
use embassy_sync::blocking_mutex::raw::CriticalSectionRawMutex;
use embassy_sync::channel::Channel;
use embassy_time::{Duration, Instant, Timer};
use nrf_sdc::SoftdeviceController;
use trouble_host::prelude::*;

use crate::battery::{Battery, Reading};

const DEVICE_NAME: &str = "Soundboard";

const SERVICE_UUID: u128 = 0x12345678_1234_1234_1234_123456789012;

/// Advertising right after boot or a disconnect: the phone is usually close
/// and reconnects inside a second.
const FAST_ADV_MIN: Duration = Duration::from_millis(100);
const FAST_ADV_MAX: Duration = Duration::from_millis(200);
const FAST_ADV_FOR: Duration = Duration::from_secs(30);

/// Advertising once nobody connected for a while (phone away or off). Cuts
/// the advertising draw by ~7x; reconnecting then takes a few seconds.
const SLOW_ADV_MIN: Duration = Duration::from_millis(1000);
const SLOW_ADV_MAX: Duration = Duration::from_millis(1200);

/// Low-power connection params. The interval bounds how long a press waits
/// for the radio; the latency lets the radio skip that many events while
/// idle (~1.5 s between wakeups). The supervision timeout has to exceed
/// 2 x (latency + 1) x interval.
const CONN_INTERVAL_MIN: Duration = Duration::from_micros(30_000);
const CONN_INTERVAL_MAX: Duration = Duration::from_micros(45_000);
const CONN_LATENCY: u16 = 32;
const CONN_TIMEOUT: Duration = Duration::from_secs(6);

/// Let the phone finish discovery and subscribing before slowing the link
/// down: with latency on, everything the phone sends waits for a wakeup.
const CONN_PARAMS_DELAY: Duration = Duration::from_secs(3);

/// Battery check when nobody presses anything.
const BATTERY_PERIOD: Duration = Duration::from_secs(30 * 60);

/// Presses queued while the radio is busy. A full queue drops presses rather
/// than blocking the matrix scan; the LED still flashes.
const PRESS_QUEUE: usize = 16;

static PRESSES: Channel<CriticalSectionRawMutex, u8, PRESS_QUEUE> = Channel::new();

/// Queue a press for the phone. Called from the matrix scan.
pub fn press(index: u8) {
    if PRESSES.try_send(index).is_err() {
        warn!("[ble] press queue full, dropped {}", index);
    }
}

#[gatt_server]
struct Server {
    soundboard: SoundboardService,
    battery: BatteryService,
}

#[gatt_service(uuid = "12345678-1234-1234-1234-123456789012")]
struct SoundboardService {
    /// Index of the last pressed button, 0..=8.
    #[characteristic(uuid = "12345678-1234-1234-1234-123456789abc", read, notify)]
    button: u8,
    /// Cell mV (little-endian u16) + charging state.
    #[characteristic(uuid = "12345678-1234-1234-1234-123456789abd", read, notify)]
    battery_raw: [u8; 3],
}

#[gatt_service(uuid = service::BATTERY)]
struct BatteryService {
    #[characteristic(uuid = characteristic::BATTERY_LEVEL, read, notify)]
    level: u8,
}

/// Static random address from the chip's factory-programmed one, so it stays
/// the same across reboots and reflashes.
pub fn address() -> Address {
    let ficr = embassy_nrf::pac::FICR;
    let lo = ficr.deviceaddr(0).read();
    // The two top bits mark a static random address.
    let hi = ficr.deviceaddr(1).read() as u16 | 0xC000;
    let mut addr = [0u8; 6];
    addr[..4].copy_from_slice(&lo.to_le_bytes());
    addr[4..].copy_from_slice(&hi.to_le_bytes());
    Address::random(addr)
}

/// Run the BLE host and the app logic forever.
pub async fn run(controller: SoftdeviceController<'_>, address: Address, mut battery: Battery) -> ! {
    info!("[ble] address {}", address);
    let mut resources: HostResources<DefaultPacketPool, 1, 2> = HostResources::new();
    let stack = trouble_host::new(controller, &mut resources)
        .set_random_address(address)
        .build();
    let mut peripheral = stack.peripheral();
    let mut runner = stack.runner();

    let server = unwrap!(Server::new_with_config(GapConfig::Peripheral(PeripheralConfig {
        name: DEVICE_NAME,
        appearance: &appearance::UNKNOWN,
    })));

    let host = async {
        loop {
            if let Err(e) = runner.run().await {
                defmt::panic!("[ble] host: {}", defmt::Debug2Format(&e));
            }
        }
    };
    let app = async {
        loop {
            let conn = advertise(&mut peripheral, &server).await;
            // Presses made while nobody listened are stale now.
            PRESSES.clear();
            select(gatt_events(&conn), connected(&server, &conn, &stack, &mut battery)).await;
        }
    };
    match join(host, app).await {}
}

/// Advertise until a phone connects: fast first, then slow.
async fn advertise<'v, 's, C: Controller>(
    peripheral: &mut Peripheral<'v, C, DefaultPacketPool>,
    server: &'s Server<'v>,
) -> GattConnection<'v, 's, DefaultPacketPool> {
    let mut adv_data = [0; 31];
    let adv_len = unwrap!(AdStructure::encode_slice(
        &[
            AdStructure::Flags(LE_GENERAL_DISCOVERABLE | BR_EDR_NOT_SUPPORTED),
            AdStructure::CompleteServiceUuids128(&[SERVICE_UUID.to_le_bytes()]),
        ],
        &mut adv_data[..],
    ));
    // Flags + the 128-bit UUID fill the advertisement; the name goes in the
    // scan response.
    let mut scan_data = [0; 31];
    let scan_len = unwrap!(AdStructure::encode_slice(
        &[AdStructure::CompleteLocalName(DEVICE_NAME.as_bytes())],
        &mut scan_data[..],
    ));
    let adv = Advertisement::ConnectableScannableUndirected {
        adv_data: &adv_data[..adv_len],
        scan_data: &scan_data[..scan_len],
    };

    let fast = AdvertisementParameters {
        interval_min: FAST_ADV_MIN,
        interval_max: FAST_ADV_MAX,
        ..Default::default()
    };
    let slow = AdvertisementParameters {
        interval_min: SLOW_ADV_MIN,
        interval_max: SLOW_ADV_MAX,
        ..Default::default()
    };

    info!("[ble] advertising (fast)");
    let advertiser = unwrap!(peripheral.advertise(&fast, adv).await);
    let conn = match select(advertiser.accept(), Timer::after(FAST_ADV_FOR)).await {
        Either::First(conn) => unwrap!(conn),
        // Dropping the advertiser stops it.
        Either::Second(()) => {
            info!("[ble] advertising (slow)");
            let advertiser = unwrap!(peripheral.advertise(&slow, adv).await);
            unwrap!(advertiser.accept().await)
        }
    };
    info!("[ble] connected");
    unwrap!(conn.with_attribute_server(server))
}

/// Serve GATT requests until the phone disconnects. Reads come straight from
/// the attribute table; CCCD writes are stored by the server.
async fn gatt_events(conn: &GattConnection<'_, '_, DefaultPacketPool>) {
    loop {
        match conn.next().await {
            GattConnectionEvent::Disconnected { reason } => {
                info!("[ble] disconnected: {}", reason);
                return;
            }
            GattConnectionEvent::Gatt { event } => match event.accept() {
                Ok(reply) => reply.send().await,
                Err(e) => warn!("[ble] GATT reply failed: {}", e),
            },
            GattConnectionEvent::ConnectionParamsUpdated {
                conn_interval,
                peripheral_latency,
                supervision_timeout,
            } => info!(
                "[ble] interval {} us, latency {}, timeout {} ms",
                conn_interval.as_micros(),
                peripheral_latency,
                supervision_timeout.as_millis()
            ),
            _ => {}
        }
    }
}

/// App logic while a phone is connected. Returns when a notification fails,
/// which means the link is gone.
async fn connected(
    server: &Server<'_>,
    conn: &GattConnection<'_, '_, DefaultPacketPool>,
    stack: &Stack<'_, SoftdeviceController<'_>, DefaultPacketPool>,
    battery: &mut Battery,
) {
    // The last percentage notified. Both battery characteristics only notify
    // when it changes; reads always see the latest value. A new phone has not
    // seen any value yet.
    let mut last_percent = None;
    if publish(server, conn, battery.measure().await, &mut last_percent).await.is_err() {
        return;
    }
    let mut next_sample = Instant::now() + BATTERY_PERIOD;
    let mut conn_params_at = Some(Instant::now() + CONN_PARAMS_DELAY);

    loop {
        let deadline = conn_params_at.map_or(next_sample, |at| at.min(next_sample));
        match select(PRESSES.receive(), Timer::at(deadline)).await {
            Either::First(index) => {
                let reading = battery.measure().await;
                if let Err(e) = server.soundboard.button.notify(conn, &index, true).await {
                    warn!("[ble] notify button: {}", e);
                    return;
                }
                if publish(server, conn, reading, &mut last_percent).await.is_err() {
                    return;
                }
                next_sample = Instant::now() + BATTERY_PERIOD;
            }
            Either::Second(()) => {
                let now = Instant::now();
                if conn_params_at.is_some_and(|at| now >= at) {
                    conn_params_at = None;
                    request_low_power(conn, stack).await;
                }
                if now >= next_sample {
                    if publish(server, conn, battery.measure().await, &mut last_percent).await.is_err() {
                        return;
                    }
                    next_sample = now + BATTERY_PERIOD;
                }
            }
        }
    }
}

/// Store a battery reading, and notify it when the percentage changed. A
/// phone that has not subscribed yet keeps `last_percent` unset, so the next
/// reading notifies again.
async fn publish(
    server: &Server<'_>,
    conn: &GattConnection<'_, '_, DefaultPacketPool>,
    reading: Reading,
    last_percent: &mut Option<u8>,
) -> Result<(), Error> {
    let level = &server.battery.level;
    let raw = &server.soundboard.battery_raw;
    let bytes = reading.to_bytes();

    if *last_percent == Some(reading.percent) {
        server.set(level, &reading.percent)?;
        server.set(raw, &bytes)?;
        return Ok(());
    }
    let subscribed = level.should_notify(conn) && raw.should_notify(conn);
    level.notify(conn, &reading.percent, true).await?;
    raw.notify(conn, &bytes, true).await?;
    if subscribed {
        *last_percent = Some(reading.percent);
    }
    Ok(())
}

/// Ask the phone to relax the link. It is a request; the central decides.
async fn request_low_power(
    conn: &GattConnection<'_, '_, DefaultPacketPool>,
    stack: &Stack<'_, SoftdeviceController<'_>, DefaultPacketPool>,
) {
    let params = RequestedConnParams {
        min_connection_interval: CONN_INTERVAL_MIN,
        max_connection_interval: CONN_INTERVAL_MAX,
        max_latency: CONN_LATENCY,
        min_event_length: Duration::from_micros(0),
        max_event_length: Duration::from_micros(0),
        supervision_timeout: CONN_TIMEOUT,
    };
    match conn.raw().update_connection_params(stack, &params).await {
        Ok(()) => info!("[ble] low-power connection params requested"),
        Err(e) => warn!("[ble] conn param update failed: {}", defmt::Debug2Format(&e)),
    }
}
