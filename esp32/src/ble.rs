//! BLE GATT soundboard server (Bluedroid, through esp-idf-svc).
//!
//! Same GATT layout and UUIDs as the bare-metal firmware, so the app works
//! with either:
//!
//! - Soundboard service `12345678-1234-1234-1234-123456789012`
//!   - Button `...9abc` (read, notify): 1 byte, the pressed button index.
//!   - Battery (raw) `...9abd` (read, notify): cell mV as LE u16, then the
//!     charging state.
//! - Battery Service `0x180F`
//!   - Battery Level `0x2A19` (read, notify): 0-100 %.
//!
//! Both services are attribute tables with Bluedroid answering reads and CCCD
//! writes itself (auto response). Values are kept in the stack with
//! `set_attr`, and we only watch CCCD writes to know who subscribed.
//!
//! The link is tuned for low radio time: slow advertising that stops while a
//! phone is connected, and a request for a slow interval with slave latency
//! so the radio can skip events while we have nothing to send.

use std::sync::mpsc::SyncSender;
use std::sync::{Arc, Mutex};

use esp_idf_svc::bt::ble::gap::{BleGapEvent, EspBleGap};
use esp_idf_svc::bt::ble::gatt::server::{EspGatts, GattsEvent};
use esp_idf_svc::bt::ble::gatt::{GattInterface, GattStatus, Handle};
use esp_idf_svc::bt::{BdAddr, Ble, BtDriver, BtStatus, BtUuid};
use esp_idf_svc::hal::modem::Modem;
use esp_idf_svc::nvs::EspDefaultNvsPartition;
use esp_idf_svc::sys::{self, esp, EspError};

use crate::battery::Reading;
use crate::Event;

const DEVICE_NAME: &str = "Soundboard";
const APP_ID: u16 = 0;

const SERVICE_UUID: u128 = 0x12345678_1234_1234_1234_123456789012;
const BUTTON_UUID: u128 = 0x12345678_1234_1234_1234_123456789abc;
const BATTERY_RAW_UUID: u128 = 0x12345678_1234_1234_1234_123456789abd;
const BATTERY_SERVICE_UUID: u16 = 0x180F;
const BATTERY_LEVEL_UUID: u16 = 0x2A19;

/// Advertising interval, in 0.625 ms units. Usually the phone is already
/// paired, so this still reconnects inside a second while using far less
/// radio than the 20-40 ms default.
const ADV_INT_MIN: u16 = 160; // 100 ms
const ADV_INT_MAX: u16 = 320; // 200 ms

/// Low-power connection params. Latency lets the radio skip that many events
/// when idle; a press still goes out at the next event, so it is no delay.
const CONN_INTERVAL_MIN: u16 = 24; // 30 ms, in 1.25 ms units
const CONN_INTERVAL_MAX: u16 = 36; // 45 ms
const CONN_LATENCY: u16 = 20; // events
const CONN_TIMEOUT: u16 = 400; // 4 s, in 10 ms units

// --- GATT attribute tables -------------------------------------------------
//
// Bluedroid reads the UUIDs and initial values behind these pointers later,
// on its own task, so they are statics.

static PRIMARY_SERVICE: [u8; 2] = 0x2800u16.to_le_bytes();
static CHARACTERISTIC: [u8; 2] = 0x2803u16.to_le_bytes();
static CCCD: [u8; 2] = 0x2902u16.to_le_bytes();

static SERVICE: [u8; 16] = SERVICE_UUID.to_le_bytes();
static BUTTON: [u8; 16] = BUTTON_UUID.to_le_bytes();
static BATTERY_RAW: [u8; 16] = BATTERY_RAW_UUID.to_le_bytes();
static BATTERY_SERVICE: [u8; 2] = BATTERY_SERVICE_UUID.to_le_bytes();
static BATTERY_LEVEL: [u8; 2] = BATTERY_LEVEL_UUID.to_le_bytes();

/// Characteristic declaration value: properties read | notify.
static READ_NOTIFY: [u8; 1] =
    [(sys::ESP_GATT_CHAR_PROP_BIT_READ | sys::ESP_GATT_CHAR_PROP_BIT_NOTIFY) as u8];
static ZEROES: [u8; 3] = [0; 3];

/// Indices into the soundboard table (and its handles).
mod sb {
    pub const BUTTON: usize = 2;
    pub const BUTTON_CCCD: usize = 3;
    pub const RAW: usize = 5;
    pub const RAW_CCCD: usize = 6;
    pub const LEN: usize = 7;
}

/// Indices into the battery service table.
mod bas {
    pub const LEVEL: usize = 2;
    pub const LEVEL_CCCD: usize = 3;
    pub const LEN: usize = 4;
}

fn attr(
    uuid: &'static [u8],
    perm: u32,
    max_len: usize,
    value: &'static [u8],
) -> sys::esp_gatts_attr_db_t {
    sys::esp_gatts_attr_db_t {
        attr_control: sys::esp_attr_control_t {
            auto_rsp: sys::ESP_GATT_AUTO_RSP as _,
        },
        att_desc: sys::esp_attr_desc_t {
            uuid_length: uuid.len() as _,
            uuid_p: uuid.as_ptr() as *mut _,
            perm: perm as _,
            max_length: max_len as _,
            length: value.len() as _,
            value: value.as_ptr() as *mut _,
        },
    }
}

/// Characteristic declaration + value + CCCD.
fn notify_characteristic(uuid: &'static [u8], len: usize) -> [sys::esp_gatts_attr_db_t; 3] {
    let read = sys::ESP_GATT_PERM_READ;
    [
        attr(&CHARACTERISTIC, read, 1, &READ_NOTIFY),
        attr(uuid, read, len, &ZEROES[..len]),
        attr(&CCCD, read | sys::ESP_GATT_PERM_WRITE, 2, &ZEROES[..2]),
    ]
}

fn soundboard_table() -> Vec<sys::esp_gatts_attr_db_t> {
    let mut table = vec![attr(
        &PRIMARY_SERVICE,
        sys::ESP_GATT_PERM_READ,
        16,
        &SERVICE,
    )];
    table.extend(notify_characteristic(&BUTTON, 1));
    table.extend(notify_characteristic(&BATTERY_RAW, 3));
    debug_assert_eq!(table.len(), sb::LEN);
    table
}

fn battery_table() -> Vec<sys::esp_gatts_attr_db_t> {
    let mut table = vec![attr(
        &PRIMARY_SERVICE,
        sys::ESP_GATT_PERM_READ,
        2,
        &BATTERY_SERVICE,
    )];
    table.extend(notify_characteristic(&BATTERY_LEVEL, 1));
    debug_assert_eq!(table.len(), bas::LEN);
    table
}

// --- Server ----------------------------------------------------------------

/// Characteristics we notify.
#[derive(Clone, Copy, Debug)]
enum Char {
    Button,
    BatteryRaw,
    BatteryLevel,
}

const CHARS: [Char; 3] = [Char::Button, Char::BatteryRaw, Char::BatteryLevel];

#[derive(Clone, Copy)]
struct Connection {
    conn_id: u16,
    peer: BdAddr,
    /// Notifications enabled, per [`Char`].
    subscribed: [bool; 3],
}

#[derive(Default)]
struct State {
    gatt_if: Option<GattInterface>,
    soundboard: Option<[Handle; sb::LEN]>,
    battery: Option<[Handle; bas::LEN]>,
    /// Raw advertising and scan response data applied.
    adv_data_set: bool,
    scan_rsp_set: bool,
    /// First advertising started. Later starts come from disconnects.
    advertising: bool,
    /// One phone at a time (`CONFIG_BTDM_CTRL_BLE_MAX_CONN=1`).
    conn: Option<Connection>,
}

impl State {
    /// Advertise for the first time once the payloads are set and both
    /// services exist, so a phone never connects to a half-built server.
    /// Returns true exactly once.
    fn take_first_advertising(&mut self) -> bool {
        let ready = self.adv_data_set
            && self.scan_rsp_set
            && self.soundboard.is_some()
            && self.battery.is_some();
        if ready && !self.advertising {
            self.advertising = true;
            return true;
        }
        false
    }

    /// (value handle, CCCD handle) of a characteristic, once registered.
    fn handles(&self, ch: Char) -> Option<(Handle, Handle)> {
        match ch {
            Char::Button => self.soundboard.map(|h| (h[sb::BUTTON], h[sb::BUTTON_CCCD])),
            Char::BatteryRaw => self.soundboard.map(|h| (h[sb::RAW], h[sb::RAW_CCCD])),
            Char::BatteryLevel => self.battery.map(|h| (h[bas::LEVEL], h[bas::LEVEL_CCCD])),
        }
    }
}

type Driver = BtDriver<'static, Ble>;
type Gap = EspBleGap<'static, Ble, Arc<Driver>>;
type Gatts = EspGatts<'static, Ble, Arc<Driver>>;

/// The GATT server. Cheap to clone; the clones in the Bluedroid callbacks and
/// the one in the `app` thread share everything.
#[derive(Clone)]
pub struct Server {
    gap: Arc<Gap>,
    gatts: Arc<Gatts>,
    state: Arc<Mutex<State>>,
    events: SyncSender<Event>,
}

impl Server {
    /// Bring up the controller and register the GATT application. Services
    /// and advertising follow from the callbacks.
    pub fn new(
        modem: Modem<'static>,
        nvs: EspDefaultNvsPartition,
        events: SyncSender<Event>,
    ) -> Result<Self, EspError> {
        let bt = Arc::new(Driver::new(modem, Some(nvs))?);
        let server = Self {
            gap: Arc::new(EspBleGap::new(bt.clone())?),
            gatts: Arc::new(EspGatts::new(bt)?),
            state: Arc::new(Mutex::new(State::default())),
            events,
        };

        let gap_server = server.clone();
        server
            .gap
            .subscribe(move |event| gap_server.on_gap_event(event))?;
        let gatts_server = server.clone();
        server.gatts.subscribe(move |(gatt_if, event)| {
            if let Err(e) = gatts_server.on_gatts_event(gatt_if, event) {
                log::error!("[ble] GATTS event handling failed: {e:?}");
            }
        })?;

        server.gatts.register_app(APP_ID)?;
        Ok(server)
    }

    /// Store the pressed button index and notify it.
    pub fn notify_button(&self, index: u8) {
        self.update(Char::Button, &[index], true);
    }

    /// Store a battery reading; notify it too when `notify` is set. Returns
    /// whether a subscribed phone was notified, so the caller retries the
    /// notification with the next reading otherwise.
    pub fn publish_battery(&self, reading: Reading, notify: bool) -> bool {
        let level = self.update(Char::BatteryLevel, &[reading.percent], notify);
        let raw = self.update(Char::BatteryRaw, &reading.to_bytes(), notify);
        level && raw
    }

    /// Ask the phone to relax the link. It is a request; the central decides.
    pub fn request_low_power_conn_params(&self) {
        let Some(conn) = self.state.lock().unwrap().conn else {
            return;
        };
        let mut params = sys::esp_ble_conn_update_params_t {
            bda: conn.peer.addr(),
            min_int: CONN_INTERVAL_MIN,
            max_int: CONN_INTERVAL_MAX,
            latency: CONN_LATENCY,
            timeout: CONN_TIMEOUT,
        };
        match esp!(unsafe { sys::esp_ble_gap_update_conn_params(&mut params) }) {
            Ok(()) => log::info!("[ble] low-power connection params requested"),
            Err(e) => log::warn!("[ble] conn param update failed: {e}"),
        }
    }

    /// Set the stored value (what a read returns), and notify the phone if
    /// asked and it subscribed. Returns whether a notification went out.
    fn update(&self, ch: Char, value: &[u8], notify: bool) -> bool {
        // Snapshot under the lock; the ESP-IDF calls run without it so the
        // Bluedroid callbacks, which need the same lock, never wait on us.
        let (handles, target) = {
            let state = self.state.lock().unwrap();
            let target = match (state.gatt_if, state.conn) {
                (Some(gatt_if), Some(conn)) if conn.subscribed[ch as usize] => {
                    Some((gatt_if, conn.conn_id))
                }
                _ => None,
            };
            (state.handles(ch), target)
        };
        let Some((value_handle, _)) = handles else {
            return false;
        };

        if let Err(e) = self.gatts.set_attr(value_handle, value) {
            log::warn!("[ble] set {ch:?} failed: {e}");
        }
        match target {
            Some((gatt_if, conn_id)) if notify => {
                match self.gatts.notify(gatt_if, conn_id, value_handle, value) {
                    Ok(()) => true,
                    Err(e) => {
                        log::warn!("[ble] notify {ch:?} failed: {e}");
                        false
                    }
                }
            }
            _ => false,
        }
    }

    fn start_advertising(&self) {
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
        if let Err(e) = esp!(unsafe { sys::esp_ble_gap_start_advertising(&mut params) }) {
            log::error!("[ble] start advertising failed: {e}");
        }
    }

    /// Flags + the 128-bit service UUID fill the 31-byte advertisement, so
    /// the name goes in the scan response.
    fn configure_advertising(&self) -> Result<(), EspError> {
        let mut adv = vec![2, 0x01, 0x06, 17, 0x07];
        adv.extend_from_slice(&SERVICE);
        self.gap.set_raw_adv_conf(&adv)?;

        let mut scan_rsp = vec![DEVICE_NAME.len() as u8 + 1, 0x09];
        scan_rsp.extend_from_slice(DEVICE_NAME.as_bytes());
        self.gap.set_raw_scan_rsp_conf(&scan_rsp)
    }

    fn on_gap_event(&self, event: BleGapEvent) {
        let first = {
            let mut state = self.state.lock().unwrap();
            match event {
                BleGapEvent::RawAdvertisingConfigured(status) => {
                    log_status("advertising data", status);
                    state.adv_data_set = true;
                }
                BleGapEvent::RawScanResponseConfigured(status) => {
                    log_status("scan response", status);
                    state.scan_rsp_set = true;
                }
                BleGapEvent::AdvertisingStarted(status) => {
                    log_status("advertising", status);
                    log::info!("[ble] advertising as \"{DEVICE_NAME}\"");
                    return;
                }
                BleGapEvent::ConnectionParamsConfigured {
                    status, conn_int, ..
                } => {
                    log_status("connection params", status);
                    log::info!("[ble] connection interval {} us", conn_int as u32 * 1250);
                    return;
                }
                _ => return,
            }
            state.take_first_advertising()
        };
        if first {
            self.start_advertising();
        }
    }

    fn on_gatts_event(&self, gatt_if: GattInterface, event: GattsEvent) -> Result<(), EspError> {
        match event {
            GattsEvent::ServiceRegistered { status, app_id } if app_id == APP_ID => {
                if status != GattStatus::Ok {
                    log::error!("[ble] app registration failed: {status:?}");
                    return Ok(());
                }
                self.state.lock().unwrap().gatt_if = Some(gatt_if);
                self.gap.set_device_name(DEVICE_NAME)?;
                self.configure_advertising()?;
                create_attr_table(gatt_if, soundboard_table(), 0)?;
                create_attr_table(gatt_if, battery_table(), 1)?;
            }
            GattsEvent::AttributeTableCreated {
                status,
                svc_uuid,
                handles,
                ..
            } => {
                if status != GattStatus::Ok {
                    log::error!("[ble] attribute table {svc_uuid:?} failed: {status:?}");
                    return Ok(());
                }
                if let Some(&service) = handles.first() {
                    self.gatts.start_service(service)?;
                }
                let first = {
                    let mut state = self.state.lock().unwrap();
                    if svc_uuid == BtUuid::uuid128(SERVICE_UUID) {
                        state.soundboard = handles.try_into().ok();
                    } else if svc_uuid == BtUuid::uuid16(BATTERY_SERVICE_UUID) {
                        state.battery = handles.try_into().ok();
                    }
                    state.take_first_advertising()
                };
                if first {
                    self.start_advertising();
                }
            }
            GattsEvent::PeerConnected { conn_id, addr, .. } => {
                log::info!("[ble] connected: {addr}");
                self.state.lock().unwrap().conn = Some(Connection {
                    conn_id,
                    peer: addr,
                    subscribed: [false; 3],
                });
                // The controller stopped advertising by itself.
                let _ = self.events.try_send(Event::Connected);
            }
            GattsEvent::PeerDisconnected { addr, reason, .. } => {
                log::info!("[ble] disconnected: {addr} ({reason:?})");
                let cccds: Vec<Handle> = {
                    let mut state = self.state.lock().unwrap();
                    state.conn = None;
                    CHARS
                        .iter()
                        .filter_map(|&ch| state.handles(ch).map(|(_, cccd)| cccd))
                        .collect()
                };
                // The CCCD values live in the shared table; the next phone
                // starts unsubscribed.
                for cccd in cccds {
                    self.gatts.set_attr(cccd, &[0, 0])?;
                }
                self.start_advertising();
            }
            GattsEvent::Write {
                conn_id,
                trans_id,
                handle,
                offset,
                need_rsp,
                is_prep,
                value,
                ..
            } => {
                if !is_prep && offset == 0 && value.len() == 2 {
                    let mut state = self.state.lock().unwrap();
                    let ch = CHARS
                        .iter()
                        .copied()
                        .find(|&ch| state.handles(ch).is_some_and(|(_, cccd)| cccd == handle));
                    if let (Some(ch), Some(conn)) = (ch, state.conn.as_mut()) {
                        if conn.conn_id == conn_id {
                            let enabled = value[0] & 0x01 != 0;
                            conn.subscribed[ch as usize] = enabled;
                            log::info!("[ble] {ch:?} notifications: {enabled}");
                        }
                    }
                }
                // Auto-response attributes are answered by the stack.
                if need_rsp {
                    self.gatts
                        .send_response(gatt_if, conn_id, trans_id, GattStatus::Ok, None)?;
                }
            }
            _ => {}
        }
        Ok(())
    }
}

/// Bluedroid copies the table itself, but not what its entries point to:
/// those are the statics above.
fn create_attr_table(
    gatt_if: GattInterface,
    table: Vec<sys::esp_gatts_attr_db_t>,
    instance: u8,
) -> Result<(), EspError> {
    esp!(unsafe {
        sys::esp_ble_gatts_create_attr_tab(table.as_ptr(), gatt_if, table.len() as _, instance)
    })
}

fn log_status(what: &str, status: BtStatus) {
    if status != BtStatus::Success {
        log::error!("[ble] {what} failed: {status:?}");
    }
}
