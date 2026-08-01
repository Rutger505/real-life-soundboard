//! BLE GATT soundboard server.
//!
//! Layout:
//! - Service        `12345678-1234-1234-1234-123456789012`
//! - Characteristic `12345678-1234-1234-1234-123456789abc` (Notify + CCCD 0x2902)
//!
//! The phone subscribes to notifications on the characteristic and we notify it
//! with a single byte: the pressed button's index.
//!
//! Radio time is the dominant power cost once the CPU stops polling, so the
//! server is tuned for it: advertising runs at a slow interval and stops while
//! a phone is connected, and the link itself uses slave latency so the radio
//! can skip most connection events without hurting press latency.

use std::sync::{Arc, Mutex};

use enumset::enum_set;
use log::{error, info, warn};

use esp_idf_hal::modem::Modem;
use esp_idf_svc::bt::ble::gap::{AdvConfiguration, BleGapEvent, EspBleGap};
use esp_idf_svc::bt::ble::gatt::server::{EspGatts, GattsEvent};
use esp_idf_svc::bt::ble::gatt::{
    AutoResponse, GattCharacteristic, GattDescriptor, GattId, GattInterface, GattServiceId,
    GattStatus, Handle, Permission, Property,
};
use esp_idf_svc::bt::{BdAddr, Ble, BtDriver, BtUuid};
use esp_idf_svc::nvs::EspDefaultNvsPartition;
use esp_idf_svc::sys::{self, esp, EspError};

const DEVICE_NAME: &str = "Soundboard";
const SERVICE_UUID: u128 = 0x12345678_1234_1234_1234_123456789012;
const CHAR_UUID: u128 = 0x12345678_1234_1234_1234_123456789abc;
const CCCD_UUID: u16 = 0x2902;

const APP_ID: u16 = 0;

/// Upper bound on simultaneously tracked links. Matches
/// `CONFIG_BTDM_CTRL_BLE_MAX_CONN` in `sdkconfig.defaults`.
const MAX_CONNECTIONS: usize = 1;

/// Keep advertising after a phone has connected, so a second one can join.
///
/// Off: advertising is a transmission on three channels every
/// [`ADV_INT_MIN`]..[`ADV_INT_MAX`], forever, and a wrist soundboard talks to
/// one phone. Turning this on also means raising [`MAX_CONNECTIONS`] and
/// `CONFIG_BTDM_CTRL_BLE_MAX_CONN`.
const ADVERTISE_WHILE_CONNECTED: bool = false;

/// Advertising interval, in units of 0.625 ms.
///
/// `EspBleGap::start_advertising` hardcodes 20–40 ms, which is a lot of radio
/// to hold indefinitely while waiting for a phone that is usually already
/// paired. 100–200 ms still reconnects well inside a second.
const ADV_INT_MIN: u16 = 0x00a0; // 100 ms
const ADV_INT_MAX: u16 = 0x0140; // 200 ms

/// Connection parameters requested once the phone enables notifications.
///
/// A short interval keeps a press on the wire quickly, while slave latency lets
/// the radio skip [`CONN_LATENCY_EVENTS`] events in a row whenever we have
/// nothing to send — the normal case. Skipping is not a delay: a press is still
/// transmitted at the very next connection event.
const CONN_INTERVAL_MIN_MS: u32 = 30;
const CONN_INTERVAL_MAX_MS: u32 = 45;
const CONN_LATENCY_EVENTS: u32 = 20;
/// Must exceed `(1 + latency) * interval_max * 2` = 1890 ms.
const CONN_TIMEOUT_MS: u32 = 4000;

// Shorthand types for the 'static Bluedroid stack handles.
type SbBtDriver = BtDriver<'static, Ble>;
type SbGap = Arc<EspBleGap<'static, Ble, Arc<SbBtDriver>>>;
type SbGatts = Arc<EspGatts<'static, Ble, Arc<SbBtDriver>>>;

#[derive(Clone)]
struct Connection {
    conn_id: Handle,
    peer: BdAddr,
    subscribed: bool,
}

#[derive(Default)]
struct State {
    gatt_if: Option<GattInterface>,
    service_handle: Option<Handle>,
    char_handle: Option<Handle>,
    cccd_handle: Option<Handle>,
    connections: Vec<Connection>,
}

/// The BLE GATT soundboard server. Cloneable (all shared state behind `Arc`s)
/// so it can be moved into the GAP/GATTS event callbacks.
#[derive(Clone)]
pub struct Server {
    /// Kept alive for as long as the server is; the stack borrows it.
    _bt: Arc<SbBtDriver>,
    gap: SbGap,
    gatts: SbGatts,
    state: Arc<Mutex<State>>,
}

impl Server {
    /// Bring up the controller, register the GATT application and start
    /// advertising. Returns once registration is under way; the rest happens in
    /// the event callbacks.
    pub fn new(modem: Modem<'static>, nvs: EspDefaultNvsPartition) -> Result<Self, EspError> {
        let bt: Arc<SbBtDriver> = Arc::new(BtDriver::new(modem, Some(nvs))?);

        let server = Self {
            _bt: bt.clone(),
            gap: Arc::new(EspBleGap::new(bt.clone())?),
            gatts: Arc::new(EspGatts::new(bt)?),
            state: Arc::new(Mutex::new(State::default())),
        };

        let gap_server = server.clone();
        server
            .gap
            .subscribe(move |event| gap_server.on_gap_event(event))?;

        let gatts_server = server.clone();
        server
            .gatts
            .subscribe(move |(gatt_if, event)| gatts_server.on_gatts_event(gatt_if, event))?;

        server.gatts.register_app(APP_ID)?;

        Ok(server)
    }

    /// Notify every subscribed connection with the pressed button index.
    pub fn notify_button(&self, index: u8) {
        // Snapshot the targets so the ESP-IDF calls below run without the lock
        // held — the GATTS callback needs the same lock to make progress.
        let mut targets = [0 as Handle; MAX_CONNECTIONS];
        let mut count = 0;

        let (gatt_if, char_handle) = {
            let state = self.state.lock().unwrap();

            let (Some(gatt_if), Some(char_handle)) = (state.gatt_if, state.char_handle) else {
                warn!("button {index}: BLE not ready, dropping");
                return;
            };

            for conn in state
                .connections
                .iter()
                .filter(|conn| conn.subscribed)
                .take(MAX_CONNECTIONS)
            {
                targets[count] = conn.conn_id;
                count += 1;
            }

            (gatt_if, char_handle)
        };

        for &conn_id in &targets[..count] {
            if let Err(e) = self.gatts.notify(gatt_if, conn_id, char_handle, &[index]) {
                error!("notify to conn {conn_id} failed: {e:?}");
            }
        }
    }

    /// Apply the advertising payload. Advertising itself (re)starts from the
    /// resulting `AdvertisingConfigured` event.
    fn set_adv_conf(&self) -> Result<(), EspError> {
        self.gap.set_adv_conf(&AdvConfiguration {
            include_name: true,
            include_txpower: false,
            service_uuid: Some(BtUuid::uuid128(SERVICE_UUID)),
            ..Default::default()
        })
    }

    /// Start advertising at [`ADV_INT_MIN`]..[`ADV_INT_MAX`].
    ///
    /// Bypasses `EspBleGap::start_advertising`, which offers no way to widen
    /// its hardcoded 20–40 ms interval.
    fn start_advertising(&self) -> Result<(), EspError> {
        let mut params = sys::esp_ble_adv_params_t {
            adv_int_min: ADV_INT_MIN,
            adv_int_max: ADV_INT_MAX,
            adv_type: sys::esp_ble_adv_type_t_ADV_TYPE_IND,
            own_addr_type: sys::esp_ble_addr_type_t_BLE_ADDR_TYPE_PUBLIC,
            peer_addr: [0; 6],
            peer_addr_type: sys::esp_ble_addr_type_t_BLE_ADDR_TYPE_PUBLIC,
            channel_map: sys::esp_ble_adv_channel_t_ADV_CHNL_ALL,
            adv_filter_policy: sys::esp_ble_adv_filter_t_ADV_FILTER_ALLOW_SCAN_ANY_CON_ANY,
        };

        esp!(unsafe { sys::esp_ble_gap_start_advertising(&mut params) })
    }

    /// Ask the phone to relax the link now that it is only carrying the odd
    /// button press. It is a request — the central decides.
    fn request_low_power_conn_params(&self, peer: BdAddr) -> Result<(), EspError> {
        self.gap.set_conn_params_conf(
            peer,
            CONN_INTERVAL_MIN_MS,
            CONN_INTERVAL_MAX_MS,
            // The helper takes latency in ms and divides by 10 to get events.
            CONN_LATENCY_EVENTS * 10,
            CONN_TIMEOUT_MS,
        )
    }

    fn on_gap_event(&self, event: BleGapEvent) {
        if let BleGapEvent::AdvertisingConfigured(_) = event {
            if let Err(e) = self.start_advertising() {
                error!("start_advertising failed: {e:?}");
            }
        }
    }

    fn on_gatts_event(&self, gatt_if: GattInterface, event: GattsEvent) {
        if let Err(e) = self.handle_gatts_event(gatt_if, event) {
            error!("GATTS event handling failed: {e:?}");
        }
    }

    fn handle_gatts_event(
        &self,
        gatt_if: GattInterface,
        event: GattsEvent,
    ) -> Result<(), EspError> {
        match event {
            GattsEvent::ServiceRegistered {
                status: GattStatus::Ok,
                app_id,
            } if app_id == APP_ID => {
                self.state.lock().unwrap().gatt_if = Some(gatt_if);
                self.gap.set_device_name(DEVICE_NAME)?;
                self.set_adv_conf()?;
                self.gatts.create_service(
                    gatt_if,
                    &GattServiceId {
                        id: GattId {
                            uuid: BtUuid::uuid128(SERVICE_UUID),
                            inst_id: 0,
                        },
                        is_primary: true,
                    },
                    8,
                )?;
            }
            GattsEvent::ServiceCreated {
                status: GattStatus::Ok,
                service_handle,
                ..
            } => {
                self.state.lock().unwrap().service_handle = Some(service_handle);
                self.gatts.start_service(service_handle)?;
                // Notify characteristic that carries the button index.
                self.gatts.add_characteristic(
                    service_handle,
                    &GattCharacteristic {
                        uuid: BtUuid::uuid128(CHAR_UUID),
                        permissions: enum_set!(Permission::Read),
                        properties: enum_set!(Property::Notify),
                        max_len: 1,
                        auto_rsp: AutoResponse::ByApp,
                    },
                    &[],
                )?;
            }
            GattsEvent::CharacteristicAdded {
                status: GattStatus::Ok,
                attr_handle,
                service_handle,
                char_uuid,
            } if char_uuid == BtUuid::uuid128(CHAR_UUID) => {
                self.state.lock().unwrap().char_handle = Some(attr_handle);
                // CCCD so the phone can enable notifications.
                self.gatts.add_descriptor(
                    service_handle,
                    &GattDescriptor {
                        uuid: BtUuid::uuid16(CCCD_UUID),
                        permissions: enum_set!(Permission::Read | Permission::Write),
                    },
                )?;
            }
            GattsEvent::DescriptorAdded {
                status: GattStatus::Ok,
                attr_handle,
                descr_uuid,
                ..
            } if descr_uuid == BtUuid::uuid16(CCCD_UUID) => {
                self.state.lock().unwrap().cccd_handle = Some(attr_handle);
            }
            GattsEvent::PeerConnected { conn_id, addr, .. } => {
                info!("peer connected: {addr} (conn_id {conn_id})");
                self.state.lock().unwrap().connections.push(Connection {
                    conn_id,
                    peer: addr,
                    subscribed: false,
                });

                // The controller stops advertising by itself on connect; only
                // resume it if we want to collect more phones.
                if ADVERTISE_WHILE_CONNECTED {
                    self.set_adv_conf()?;
                }
            }
            GattsEvent::PeerDisconnected { addr, .. } => {
                info!("peer disconnected: {addr}");
                self.state
                    .lock()
                    .unwrap()
                    .connections
                    .retain(|conn| conn.peer != addr);
                self.set_adv_conf()?;
            }
            GattsEvent::Write {
                conn_id,
                trans_id,
                handle,
                offset,
                need_rsp,
                value,
                ..
            } => {
                // The only writable attribute is the CCCD: 0x0001 = subscribe
                // to notifications, 0x0000 = unsubscribe.
                let cccd_handle = self.state.lock().unwrap().cccd_handle;
                let mut subscribed_peer = None;

                if Some(handle) == cccd_handle && offset == 0 && value.len() >= 2 {
                    let enabled = value[0] & 0x01 != 0;
                    let mut state = self.state.lock().unwrap();

                    if let Some(conn) = state
                        .connections
                        .iter_mut()
                        .find(|conn| conn.conn_id == conn_id)
                    {
                        conn.subscribed = enabled;
                        info!("conn {conn_id} notifications: {enabled}");

                        if enabled {
                            subscribed_peer = Some(conn.peer);
                        }
                    }
                }

                if need_rsp {
                    self.gatts
                        .send_response(gatt_if, conn_id, trans_id, GattStatus::Ok, None)?;
                }

                // Only after the response: the phone is done setting up, so now
                // is the moment to slow the link down.
                if let Some(peer) = subscribed_peer {
                    self.request_low_power_conn_params(peer)?;
                }
            }
            _ => {}
        }

        Ok(())
    }
}
