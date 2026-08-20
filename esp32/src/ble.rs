//! BLE GATT soundboard server (trouble-host on the esp-radio controller).
//!
//! Layout:
//! - Service        `12345678-1234-1234-1234-123456789012`
//! - Characteristic `12345678-1234-1234-1234-123456789abc` (Notify + CCCD 0x2902)
//!
//! The phone subscribes to notifications on the characteristic and we notify it
//! with a single byte: the pressed button's index. The `#[gatt_service]` macro
//! adds the standard CCCD (0x2902) for the `notify` property automatically.
//!
//! Radio time dominates power once the CPU stops polling, so the link is tuned
//! for it. Advertising runs at a slow 100-200 ms interval and the controller
//! stops it automatically on connect; once connected we ask the phone to relax
//! the link (slow interval + slave latency) so the radio can skip most
//! connection events while we have nothing to send.

use bt_hci::cmd::le::{LeConnUpdate, LeReadLocalSupportedFeatures};
use bt_hci::controller::{ControllerCmdAsync, ControllerCmdSync, ExternalController};
use embassy_futures::join::join;
use embassy_futures::select::select3;
use embassy_time::{Duration, Timer};
use esp_hal::peripherals::BT;
use esp_radio::ble::controller::BleConnector;
use trouble_host::prelude::*;

const DEVICE_NAME: &str = "Soundboard";

// Service:        12345678-1234-1234-1234-123456789012
// Characteristic: 12345678-1234-1234-1234-123456789abc
// (The gatt macro needs UUID string literals, not consts, so they are inlined
// on the service/characteristic definitions below.)

/// Same service UUID as raw little-endian bytes, for the advertising payload
/// (BLE transmits UUIDs least-significant byte first).
const SERVICE_UUID_LE: [u8; 16] = [
    0x12, 0x90, 0x78, 0x56, 0x34, 0x12, 0x34, 0x12, 0x34, 0x12, 0x34, 0x12, 0x78, 0x56, 0x34, 0x12,
];

/// A wrist soundboard talks to one phone at a time.
const CONNECTIONS_MAX: usize = 1;
/// L2CAP channels: signalling + ATT.
const L2CAP_CHANNELS_MAX: usize = 2;

/// HCI command queue depth for the external controller.
const HCI_CMD_SLOTS: usize = 20;

// --- Requested low-power connection parameters -----------------------------
// A short interval keeps a press on the wire quickly; slave latency lets the
// radio skip that many events in a row whenever we have nothing to send (the
// normal case). Skipping is not a delay. A press is still transmitted at the
// next connection event.
const CONN_INTERVAL_MIN: Duration = Duration::from_millis(30);
const CONN_INTERVAL_MAX: Duration = Duration::from_millis(45);
const CONN_LATENCY: u16 = 20;
const CONN_TIMEOUT: Duration = Duration::from_millis(4000);

/// GATT server: a single soundboard service.
#[gatt_server]
struct Server {
    soundboard: SoundboardService,
}

/// The soundboard service and its one notify characteristic.
#[gatt_service(uuid = "12345678-1234-1234-1234-123456789012")]
struct SoundboardService {
    /// Pressed button index (0..=8). Notify-only from our side; the phone
    /// enables notifications via the auto-generated CCCD.
    #[characteristic(uuid = "12345678-1234-1234-1234-123456789abc", read, notify, value = 0u8)]
    button: u8,
}

/// Own the radio and run the BLE stack forever.
pub async fn run(bt: BT<'static>) {
    let connector = BleConnector::new(bt, Default::default()).unwrap();
    let controller: ExternalController<_, HCI_CMD_SLOTS> = ExternalController::new(connector);

    // A fixed random address is fine for a personal device.
    let address = Address::random([0xff, 0x8f, 0x1a, 0x05, 0xe4, 0xff]);

    let mut resources: HostResources<_, DefaultPacketPool, CONNECTIONS_MAX, L2CAP_CHANNELS_MAX> =
        HostResources::new();
    let stack = trouble_host::new(controller, &mut resources)
        .set_random_address(address)
        .build();
    let runner = stack.runner();
    let mut peripheral = stack.peripheral();

    let server = Server::new_with_config(GapConfig::Peripheral(PeripheralConfig {
        name: DEVICE_NAME,
        appearance: &appearance::power_device::GENERIC_POWER_DEVICE,
    }))
    .unwrap();

    // `ble_task` must run forever alongside everything else; it drives the HCI.
    let _ = join(ble_task(runner), async {
        loop {
            match advertise(&mut peripheral, &server).await {
                Ok(conn) => {
                    // Tasks that only make sense while connected. Any one
                    // returning (usually a disconnect) drops us back to
                    // advertising.
                    let gatt = gatt_events_task(&conn);
                    let notify = notify_task(&server, &conn);
                    let params = conn_params_task(&conn, &stack);
                    select3(gatt, notify, params).await;
                }
                Err(e) => {
                    log::error!("[ble] advertise error: {:?}", e);
                    Timer::after(Duration::from_millis(500)).await;
                }
            }
        }
    })
    .await;
}

/// Background task that drives the HCI transport. Must never stop.
async fn ble_task<C: Controller, P: PacketPool>(mut runner: Runner<'_, C, P>) {
    loop {
        if let Err(e) = runner.run().await {
            log::error!("[ble] runner error: {:?}", e);
            Timer::after(Duration::from_millis(500)).await;
        }
    }
}

/// Advertise until a central connects. Advertising stops automatically on
/// connect (single connection), and resumes when this is called again after a
/// disconnect.
async fn advertise<'a, C: Controller>(
    peripheral: &mut Peripheral<'a, C, DefaultPacketPool>,
    server: &'a Server<'_>,
) -> Result<GattConnection<'a, 'a, DefaultPacketPool>, BleHostError<C::Error>> {
    // Slow advertising interval: this device is usually already paired, so a
    // 100–200 ms interval still reconnects well inside a second while holding
    // far less radio than the 20–40 ms default.
    let params = AdvertisementParameters {
        interval_min: Duration::from_millis(100),
        interval_max: Duration::from_millis(200),
        ..Default::default()
    };

    // 128-bit UUID (18 bytes) + flags won't leave room for the name in the
    // 31-byte adv payload, so the name rides in the scan response.
    let mut adv_data = [0u8; 31];
    let adv_len = AdStructure::encode_slice(
        &[
            AdStructure::Flags(LE_GENERAL_DISCOVERABLE | BR_EDR_NOT_SUPPORTED),
            AdStructure::CompleteServiceUuids128(&[SERVICE_UUID_LE]),
        ],
        &mut adv_data[..],
    )?;

    let mut scan_data = [0u8; 31];
    let scan_len = AdStructure::encode_slice(
        &[AdStructure::CompleteLocalName(DEVICE_NAME.as_bytes())],
        &mut scan_data[..],
    )?;

    let advertiser = peripheral
        .advertise(
            &params,
            Advertisement::ConnectableScannableUndirected {
                adv_data: &adv_data[..adv_len],
                scan_data: &scan_data[..scan_len],
            },
        )
        .await?;
    log::info!("[ble] advertising as \"{DEVICE_NAME}\"");

    let conn = advertiser.accept().await?.with_attribute_server(server)?;
    log::info!("[ble] connected");
    Ok(conn)
}

/// Deliver button presses as one-byte notifications while connected.
async fn notify_task<P: PacketPool>(server: &Server<'_>, conn: &GattConnection<'_, '_, P>) {
    let button = server.soundboard.button;
    loop {
        let index = crate::BUTTON_EVENTS.receive().await;
        // `store = true` also updates the readable value. Fails only if the
        // link is gone, which the gatt task will notice too.
        if button.notify(conn, &index, true).await.is_err() {
            log::warn!("[ble] notify failed (link down?)");
            break;
        }
    }
}

/// Service GATT reads/writes until the connection closes. The characteristic is
/// notify-only, so there is nothing to act on here; we just keep the ATT server
/// responsive and detect disconnect.
async fn gatt_events_task<P: PacketPool>(conn: &GattConnection<'_, '_, P>) {
    loop {
        match conn.next().await {
            GattConnectionEvent::Disconnected { reason } => {
                log::info!("[ble] disconnected: {:?}", reason);
                break;
            }
            GattConnectionEvent::Gatt { event } => {
                // Accept everything; the sole characteristic carries no writable
                // state of ours (the CCCD is handled by the stack).
                match event.accept() {
                    Ok(reply) => reply.send().await,
                    Err(e) => log::warn!("[ble] gatt reply error: {:?}", e),
                }
            }
            _ => {}
        }
    }
}

/// Once connected, ask the phone to relax the link to the low-power parameters.
/// It is a request, so the central decides. We fire it once, shortly after
/// connect, then idle for the life of the connection.
async fn conn_params_task<C, P: PacketPool>(conn: &GattConnection<'_, '_, P>, stack: &Stack<'_, C, P>)
where
    C: Controller
        + ControllerCmdAsync<LeConnUpdate>
        + ControllerCmdSync<LeReadLocalSupportedFeatures>,
{
    // Give the phone a moment to finish service discovery / subscribing before
    // renegotiating the link.
    Timer::after(Duration::from_millis(1000)).await;

    let params = RequestedConnParams {
        min_connection_interval: CONN_INTERVAL_MIN,
        max_connection_interval: CONN_INTERVAL_MAX,
        max_latency: CONN_LATENCY,
        min_event_length: Duration::from_micros(0),
        max_event_length: Duration::from_micros(0),
        supervision_timeout: CONN_TIMEOUT,
    };

    match conn.raw().update_connection_params(stack, &params).await {
        Ok(()) => log::info!("[ble] low-power connection params requested"),
        Err(e) => log::warn!("[ble] conn param update failed: {:?}", e),
    }

    // TODO(power): esp-radio 0.18 exposes no BLE TX-power API, so we cannot drop
    // to 0 dBm from here (the ESP-IDF build did via esp_ble_tx_power_set). Left
    // at the controller default. Also, unlike the ESP-IDF build there is no
    // light-sleep during a connection here; that needs a 32.768 kHz crystal and
    // controller support that esp-radio does not currently surface. Idle-between
    // -events via slave latency (above) is the main lever we have.
    core::future::pending::<()>().await;
}
