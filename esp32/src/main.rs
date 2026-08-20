//! Bare-metal ESP32 firmware for a wrist soundboard.
//!
//! 9 buttons + a status LED. A press flashes the LED and sends the button
//! index (0..=8) as a one-byte BLE notification to the paired phone.
//!
//! Runs on esp-hal with the Embassy executor (esp-rtos), the esp-radio BLE
//! controller and the trouble-host GATT stack. Each button is its own task
//! blocked on a GPIO edge, so the core idles whenever nobody is pressing.

#![no_std]
#![no_main]

mod ble;
mod buttons;
mod led;

use embassy_executor::Spawner;
use embassy_sync::blocking_mutex::raw::CriticalSectionRawMutex;
use embassy_sync::channel::Channel;
use embassy_sync::signal::Signal;

use esp_hal::clock::CpuClock;
use esp_hal::gpio::{Input, InputConfig, Level, Output, OutputConfig, Pull};
use esp_hal::interrupt::software::SoftwareInterruptControl;
use esp_hal::timer::timg::TimerGroup;

use esp_backtrace as _;

// The ESP-IDF bootloader needs this to recognise the app image.
esp_bootloader_esp_idf::esp_app_desc!();

/// Kept below 16 so an index fits in a byte and matches the task pool size.
pub const BUTTON_COUNT: usize = 9;

/// Debounced press indices, from the button tasks to the BLE notify task.
/// Lossy on purpose: with no phone draining it, presses drop instead of
/// blocking a button task.
pub static BUTTON_EVENTS: Channel<CriticalSectionRawMutex, u8, 16> = Channel::new();

/// Poked on every accepted press so the LED task flashes without the button
/// tasks touching the GPIO. Re-arming during a flash is fine.
pub static LED_SIGNAL: Signal<CriticalSectionRawMutex, ()> = Signal::new();

#[esp_rtos::main]
async fn main(spawner: Spawner) {
    esp_println::logger::init_logger_from_env();

    // 80 MHz is plenty for one byte per press, and current scales with clock.
    let peripherals = esp_hal::init(esp_hal::Config::default().with_cpu_clock(CpuClock::_80MHz));

    // The BLE host keeps its ATT tables and packet pool here.
    esp_alloc::heap_allocator!(size: 72 * 1024);

    // Start the Embassy executor (timer tick + software interrupt).
    let timg0 = TimerGroup::new(peripherals.TIMG0);
    let sw_int = SoftwareInterruptControl::new(peripherals.SW_INTERRUPT);
    esp_rtos::start(timg0.timer0, sw_int.software_interrupt0);

    // Status LED: GPIO2, active-high, off.
    let led = Output::new(peripherals.GPIO2, Level::Low, OutputConfig::default());
    spawner.spawn(led::led_task(led).unwrap());

    // Buttons: internal pull-down, so wire each between its GPIO and 3.3V.
    // Index order is the value notified to the phone.
    let cfg = InputConfig::default().with_pull(Pull::Down);
    spawner.spawn(buttons::button_task(Input::new(peripherals.GPIO4, cfg), 0).unwrap());
    spawner.spawn(buttons::button_task(Input::new(peripherals.GPIO23, cfg), 1).unwrap());
    spawner.spawn(buttons::button_task(Input::new(peripherals.GPIO25, cfg), 2).unwrap());
    spawner.spawn(buttons::button_task(Input::new(peripherals.GPIO13, cfg), 3).unwrap());
    spawner.spawn(buttons::button_task(Input::new(peripherals.GPIO14, cfg), 4).unwrap());
    spawner.spawn(buttons::button_task(Input::new(peripherals.GPIO26, cfg), 5).unwrap());
    spawner.spawn(buttons::button_task(Input::new(peripherals.GPIO16, cfg), 6).unwrap());
    spawner.spawn(buttons::button_task(Input::new(peripherals.GPIO17, cfg), 7).unwrap());
    spawner.spawn(buttons::button_task(Input::new(peripherals.GPIO18, cfg), 8).unwrap());

    // Owns the radio and never returns.
    ble::run(peripherals.BT).await;
}
