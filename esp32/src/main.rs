//! Real Live Soundboard — ESP32 firmware.
//!
//! 9 push buttons + 1 status LED. On a button press the LED blinks and the
//! button index (0..=8) is sent as a 1-byte BLE GATT **notification** to the
//! connected phone, which plays the configured audio clip.
//!
//! BLE layout:
//! - Service       12345678-1234-1234-1234-123456789012
//! - Characteristic 12345678-1234-1234-1234-123456789abc  (Notify + CCCD 0x2902)
//!
//! The phone subscribes to notifications on the characteristic; we notify every
//! subscribed connection with `[index]`. Advertising restarts on (dis)connect
//! so the phone can always (re)connect.

use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

use anyhow::Result;
use enumset::enum_set;

use esp_idf_hal::gpio::{Input, Output, PinDriver, Pull};
use esp_idf_hal::peripherals::Peripherals;

use esp_idf_svc::bt::ble::gap::{AdvConfiguration, BleGapEvent, EspBleGap};
use esp_idf_svc::bt::ble::gatt::server::{EspGatts, GattsEvent};
use esp_idf_svc::bt::ble::gatt::{
    AutoResponse, GattCharacteristic, GattDescriptor, GattId, GattInterface, GattServiceId,
    GattStatus, Handle, Permission, Property,
};
use esp_idf_svc::bt::{BdAddr, Ble, BtDriver, BtUuid};
use esp_idf_svc::nvs::EspDefaultNvsPartition;

use log::{error, info};

const DEVICE_NAME: &str = "Soundboard";
const SERVICE_UUID: u128 = 0x12345678_1234_1234_1234_123456789012;
const CHAR_UUID: u128 = 0x12345678_1234_1234_1234_123456789abc;
const CCCD_UUID: u16 = 0x2902;

const APP_ID: u16 = 0;
/// GPIOs wired to the 9 buttons (index 0..=8). All use internal pull-ups, so
/// a press pulls the pin low. LED is on GPIO2.
const BUTTON_PINS: [u8; 9] = [4, 5, 12, 13, 14, 15, 16, 17, 18];

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
struct Server {
    gap: SbGap,
    gatts: SbGatts,
    state: Arc<Mutex<State>>,
}

impl Server {
    fn new(gap: SbGap, gatts: SbGatts) -> Self {
        Self {
            gap,
            gatts,
            state: Arc::new(Mutex::new(State::default())),
        }
    }

    /// Advertising configuration (also (re)starts advertising once applied).
    fn set_adv_conf(&self) -> Result<()> {
        self.gap.set_adv_conf(&AdvConfiguration {
            include_name: true,
            include_txpower: false,
            service_uuid: Some(BtUuid::uuid128(SERVICE_UUID)),
            ..Default::default()
        })?;
        Ok(())
    }

    fn on_gap_event(&self, event: BleGapEvent) {
        if let BleGapEvent::AdvertisingConfigured(_) = event {
            if let Err(e) = self.gap.start_advertising() {
                error!("start_advertising failed: {e:?}");
            }
        }
    }

    fn on_gatts_event(&self, gatt_if: GattInterface, event: GattsEvent) {
        if let Err(e) = self.handle_gatts_event(gatt_if, event) {
            error!("GATTS event handling failed: {e:?}");
        }
    }

    fn handle_gatts_event(&self, gatt_if: GattInterface, event: GattsEvent) -> Result<()> {
        match event {
            GattsEvent::ServiceRegistered { status, app_id } => {
                if matches!(status, GattStatus::Ok) && app_id == APP_ID {
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
            }
            GattsEvent::ServiceCreated {
                status,
                service_handle,
                ..
            } => {
                if matches!(status, GattStatus::Ok) {
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
            }
            GattsEvent::CharacteristicAdded {
                status,
                attr_handle,
                service_handle,
                char_uuid,
            } => {
                if matches!(status, GattStatus::Ok)
                    && char_uuid == BtUuid::uuid128(CHAR_UUID)
                {
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
            }
            GattsEvent::DescriptorAdded {
                status,
                attr_handle,
                descr_uuid,
                ..
            } => {
                if matches!(status, GattStatus::Ok) && descr_uuid == BtUuid::uuid16(CCCD_UUID) {
                    self.state.lock().unwrap().cccd_handle = Some(attr_handle);
                }
            }
            GattsEvent::PeerConnected { conn_id, addr, .. } => {
                info!("Peer connected: {addr} (conn_id {conn_id})");
                {
                    let mut state = self.state.lock().unwrap();
                    state.connections.push(Connection {
                        conn_id,
                        peer: addr,
                        subscribed: false,
                    });
                }
                // Keep advertising so additional phones can connect too.
                self.set_adv_conf()?;
            }
            GattsEvent::PeerDisconnected { addr, .. } => {
                info!("Peer disconnected: {addr}");
                {
                    let mut state = self.state.lock().unwrap();
                    state.connections.retain(|c| c.peer != addr);
                }
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
                if Some(handle) == cccd_handle && offset == 0 && value.len() >= 2 {
                    let enabled = value[0] & 0x01 != 0;
                    let mut state = self.state.lock().unwrap();
                    if let Some(conn) = state
                        .connections
                        .iter_mut()
                        .find(|c| c.conn_id == conn_id)
                    {
                        conn.subscribed = enabled;
                        info!("conn {conn_id} notifications: {enabled}");
                    }
                }

                if need_rsp {
                    self.gatts
                        .send_response(gatt_if, conn_id, trans_id, GattStatus::Ok, None)?;
                }
            }
            _ => {}
        }

        Ok(())
    }

    /// Notify every subscribed connection with the pressed button index.
    fn notify_button(&self, index: u8) {
        let state = self.state.lock().unwrap();
        let (Some(gatt_if), Some(char_handle)) = (state.gatt_if, state.char_handle) else {
            info!("Button {index}: BLE not ready, skipping notify");
            return;
        };
        let payload = [index];
        for conn in state.connections.iter().filter(|c| c.subscribed) {
            if let Err(e) = self.gatts.notify(gatt_if, conn.conn_id, char_handle, &payload) {
                error!("notify to {} failed: {e:?}", conn.peer);
            } else {
                info!("Notified {} of button {index}", conn.peer);
            }
        }
    }
}

fn blink_led(led: &mut PinDriver<'_, Output>, times: u8) {
    for _ in 0..times {
        led.set_high().ok();
        thread::sleep(Duration::from_millis(80));
        led.set_low().ok();
        thread::sleep(Duration::from_millis(80));
    }
}

fn main() -> Result<()> {
    esp_idf_svc::sys::link_patches();
    esp_idf_svc::log::EspLogger::initialize_default();

    let peripherals = Peripherals::take()?;
    let nvs = EspDefaultNvsPartition::take()?;

    // Take the peripheral fields we need up front (distinct fields => partial moves).
    let pins = peripherals.pins;
    let modem = peripherals.modem;

    let mut led = PinDriver::output(pins.gpio2)?;
    led.set_low()?;

    // Pins are type-erased in esp-idf-hal 0.46, so all 9 inputs share one type.
    let mut buttons: [PinDriver<'_, Input>; 9] = [
        PinDriver::input(pins.gpio4, Pull::Up)?,
        PinDriver::input(pins.gpio5, Pull::Up)?,
        PinDriver::input(pins.gpio12, Pull::Up)?,
        PinDriver::input(pins.gpio13, Pull::Up)?,
        PinDriver::input(pins.gpio14, Pull::Up)?,
        PinDriver::input(pins.gpio15, Pull::Up)?,
        PinDriver::input(pins.gpio16, Pull::Up)?,
        PinDriver::input(pins.gpio17, Pull::Up)?,
        PinDriver::input(pins.gpio18, Pull::Up)?,
    ];
    let _ = &BUTTON_PINS; // documented wiring; pins are taken explicitly above

    // Bring up the BLE stack.
    let bt: Arc<SbBtDriver> = Arc::new(BtDriver::new(modem, Some(nvs))?);
    let server = Server::new(
        Arc::new(EspBleGap::new(bt.clone())?),
        Arc::new(EspGatts::new(bt.clone())?),
    );

    let gap_server = server.clone();
    server.gap.subscribe(move |event| gap_server.on_gap_event(event))?;

    let gatts_server = server.clone();
    server
        .gatts
        .subscribe(move |(gatt_if, event)| gatts_server.on_gatts_event(gatt_if, event))?;

    server.gatts.register_app(APP_ID)?;
    info!("BLE soundboard server started as '{DEVICE_NAME}'");

    // Edge-detected, poll-debounced button scanning.
    let mut prev_pressed = [false; 9];
    loop {
        for i in 0..9 {
            let pressed = buttons[i].is_low();
            if pressed && !prev_pressed[i] {
                info!("Button {i} pressed");
                blink_led(&mut led, 2);
                server.notify_button(i as u8);
            }
            prev_pressed[i] = pressed;
        }
        thread::sleep(Duration::from_millis(20));
    }
}
