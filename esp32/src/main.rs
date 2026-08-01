use anyhow::Result;
use esp_idf_hal::{
    gpio::{Input, Output, PinDriver, Pull},
    peripherals::Peripherals,
};
use esp_idf_svc::bt::{
    BleEnabled, BtDriver,
    ble::gap::{AdvConfiguration, EspBleGap},
    ble::gatt::server::{
        ConnectionId, EspGatts, GattsEvent, TransferId,
    },
    BtUuid,
};
use log::{error, info};
use std::{
    sync::{Arc, Mutex},
    thread,
    time::Duration,
};

const SERVICE_UUID: &str = "12345678-1234-1234-1234-123456789012";
const CHAR_UUID: &str = "12345678-1234-1234-1234-123456789abc";

const DEVICE_NAME: &str = "Soundboard";

struct BleState {
    conn_id: Option<ConnectionId>,
    attr_handle: Option<u16>,
}

fn blink_led(led: &mut PinDriver<'_, impl esp_idf_hal::gpio::OutputPin, Output>, times: u8) {
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

    let mut led = PinDriver::output(peripherals.pins.gpio2)?;
    led.set_low()?;

    let mut buttons = (
        PinDriver::input(peripherals.pins.gpio4)?,
        PinDriver::input(peripherals.pins.gpio5)?,
        PinDriver::input(peripherals.pins.gpio12)?,
        PinDriver::input(peripherals.pins.gpio13)?,
        PinDriver::input(peripherals.pins.gpio14)?,
        PinDriver::input(peripherals.pins.gpio15)?,
        PinDriver::input(peripherals.pins.gpio16)?,
        PinDriver::input(peripherals.pins.gpio17)?,
        PinDriver::input(peripherals.pins.gpio18)?,
    );

    buttons.0.set_pull(Pull::Up)?;
    buttons.1.set_pull(Pull::Up)?;
    buttons.2.set_pull(Pull::Up)?;
    buttons.3.set_pull(Pull::Up)?;
    buttons.4.set_pull(Pull::Up)?;
    buttons.5.set_pull(Pull::Up)?;
    buttons.6.set_pull(Pull::Up)?;
    buttons.7.set_pull(Pull::Up)?;
    buttons.8.set_pull(Pull::Up)?;

    let ble_state: Arc<Mutex<BleState>> = Arc::new(Mutex::new(BleState {
        conn_id: None,
        attr_handle: None,
    }));

    let bt_driver = BtDriver::<BleEnabled>::new(peripherals.modem, None)?;

    let gap = EspBleGap::new(&bt_driver)?;

    gap.set_device_name(DEVICE_NAME)?;

    let adv_config = AdvConfiguration {
        include_name: true,
        include_txpower: false,
        min_interval: 0x20,
        max_interval: 0x40,
        service_uuid: Some(BtUuid::uuid128(
            0x12345678_1234_1234_1234_123456789012u128,
        )),
        ..Default::default()
    };

    gap.configure_adv_data(&adv_config)?;

    let ble_state_gatts = Arc::clone(&ble_state);

    let gatts = EspGatts::new(&bt_driver)?;

    let service_uuid = BtUuid::uuid128(0x12345678_1234_1234_1234_123456789012u128);
    let char_uuid = BtUuid::uuid128(0x12345678_1234_1234_1234_123456789abcu128);

    gatts.register_callback(move |event| {
        match event {
            GattsEvent::ServiceRegistered { status, service_id } => {
                info!("GATT service registered: {:?}", status);
                // Add characteristic will be done after service start
            }
            GattsEvent::ServiceStarted { status, service_handle } => {
                info!("GATT service started: {:?}", status);
            }
            GattsEvent::CharacteristicAdded {
                status,
                attr_handle,
                service_handle,
                char_uuid: _,
            } => {
                info!("Characteristic added, handle: {}", attr_handle);
                if let Ok(mut state) = ble_state_gatts.lock() {
                    state.attr_handle = Some(attr_handle);
                }
            }
            GattsEvent::Connect { conn_id, .. } => {
                info!("BLE client connected, conn_id: {}", conn_id);
                if let Ok(mut state) = ble_state_gatts.lock() {
                    state.conn_id = Some(conn_id);
                }
            }
            GattsEvent::Disconnect { conn_id, .. } => {
                info!("BLE client disconnected, conn_id: {}", conn_id);
                if let Ok(mut state) = ble_state_gatts.lock() {
                    if state.conn_id == Some(conn_id) {
                        state.conn_id = None;
                    }
                }
                // Restart advertising so phone can reconnect
                // gap.start_advertising() would go here; gap is captured above
            }
            _ => {}
        }
    })?;

    gatts.register_app(0)?;

    gap.start_advertising()?;

    info!("BLE advertising started as '{}'", DEVICE_NAME);

    // Track previous button states for edge detection
    let mut prev_states = [true; 9];
    // Debounce counters
    let mut debounce = [0u8; 9];

    loop {
        let raw_states = [
            buttons.0.is_low(),
            buttons.1.is_low(),
            buttons.2.is_low(),
            buttons.3.is_low(),
            buttons.4.is_low(),
            buttons.5.is_low(),
            buttons.6.is_low(),
            buttons.7.is_low(),
            buttons.8.is_low(),
        ];

        for i in 0..9 {
            if raw_states[i] {
                // Button pressed (active low)
                debounce[i] = debounce[i].saturating_add(1);
                if debounce[i] == 1 && prev_states[i] {
                    // Confirmed press on first detection (debounce via poll interval)
                    info!("Button {} pressed", i);

                    blink_led(&mut led, 2);

                    let state = ble_state.lock().unwrap();
                    if let (Some(conn_id), Some(attr_handle)) =
                        (state.conn_id, state.attr_handle)
                    {
                        let payload = [i as u8];
                        if let Err(e) =
                            gatts.send_indicate(conn_id, attr_handle, &payload, true)
                        {
                            error!("Failed to send notification: {:?}", e);
                        } else {
                            info!("Sent BLE notification: button {}", i);
                        }
                    } else {
                        info!("No BLE client connected, skipping notification");
                    }

                    prev_states[i] = false;
                }
            } else {
                debounce[i] = 0;
                prev_states[i] = true;
            }
        }

        thread::sleep(Duration::from_millis(20));
    }
}
