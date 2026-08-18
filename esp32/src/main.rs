//! Real Live Soundboard — ESP32 firmware (bare-metal `no_std`).
//!
//! 9 push buttons and a status LED on the wrist. A press flashes the LED and
//! sends the button index (0..=8) as a one-byte BLE GATT notification to the
//! paired phone, which plays the configured clip.
//!
//! This is a true bare-metal build: no ESP-IDF, no RTOS-on-top-of-an-RTOS.
//! It runs directly on `esp-hal` with the Embassy async executor (`esp-rtos`),
//! the `esp-radio` BLE controller and the `trouble-host` GATT stack.
//!
//! The whole firmware is arranged around doing nothing cheaply. Every button is
//! its own async task blocked on a GPIO edge interrupt ([`buttons`]); when
//! nobody is touching the board every task is parked and the executor idles the
//! CPU on a `waiti`. The LED flash is a short non-blocking pulse ([`led`]) and
//! the radio ([`ble`]) sleeps between connection events. See each module.

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

// Required by the ESP-IDF second-stage bootloader to recognise the app image.
esp_bootloader_esp_idf::esp_app_desc!();

/// Number of buttons. Kept below 16 so an index fits comfortably in a byte and
/// matches the fixed-size embassy task pool below.
pub const BUTTON_COUNT: usize = 9;

/// Newly pressed, debounced button indices, produced by the per-button tasks
/// and consumed by the BLE notify task. Bounded and lossy on purpose: if no
/// phone is draining it (disconnected, or mid-reconnect) presses are dropped
/// rather than blocking a button task — a soundboard press is only interesting
/// live.
pub static BUTTON_EVENTS: Channel<CriticalSectionRawMutex, u8, 16> = Channel::new();

/// Poked on every accepted press so the LED task can flash without the button
/// tasks ever touching the GPIO. Coalescing (a second press during a flash just
/// re-arms it) is fine.
pub static LED_SIGNAL: Signal<CriticalSectionRawMutex, ()> = Signal::new();

#[esp_rtos::main]
async fn main(spawner: Spawner) {
    esp_println::logger::init_logger_from_env();

    // 80 MHz is plenty for a GATT server that sends one byte per press, and
    // active current scales roughly with clock. (Bare-metal has no dynamic
    // frequency scaling / light sleep the way ESP-IDF did; the executor still
    // idles the core between events, which is where the real savings are.)
    let peripherals = esp_hal::init(esp_hal::Config::default().with_cpu_clock(CpuClock::_80MHz));

    // The BLE host keeps its ATT tables and packet pool on the heap.
    esp_alloc::heap_allocator!(size: 72 * 1024);

    // Bring up the Embassy executor + scheduler (timer tick + a software
    // interrupt for context switches). Everything past here is async tasks.
    let timg0 = TimerGroup::new(peripherals.TIMG0);
    let sw_int = SoftwareInterruptControl::new(peripherals.SW_INTERRUPT);
    esp_rtos::start(timg0.timer0, sw_int.software_interrupt0);

    // --- Status LED: GPIO2, active-high, starts off. ---
    let led = Output::new(peripherals.GPIO2, Level::Low, OutputConfig::default());
    spawner.spawn(led::led_task(led).unwrap());

    // --- Buttons: internal pull-DOWN, so a press pulls the pin HIGH. Wire each
    // button between its GPIO and 3.3V — no external resistors needed. Each pin
    // gets its own task blocked on the rising (press) edge. ---
    let cfg = InputConfig::default().with_pull(Pull::Down);
    // index -> GPIO. Order matters; it is the value notified to the phone.
    spawner.spawn(buttons::button_task(Input::new(peripherals.GPIO4, cfg), 0).unwrap());
    spawner.spawn(buttons::button_task(Input::new(peripherals.GPIO23, cfg), 1).unwrap());
    spawner.spawn(buttons::button_task(Input::new(peripherals.GPIO25, cfg), 2).unwrap());
    spawner.spawn(buttons::button_task(Input::new(peripherals.GPIO13, cfg), 3).unwrap());
    spawner.spawn(buttons::button_task(Input::new(peripherals.GPIO14, cfg), 4).unwrap());
    spawner.spawn(buttons::button_task(Input::new(peripherals.GPIO26, cfg), 5).unwrap());
    spawner.spawn(buttons::button_task(Input::new(peripherals.GPIO16, cfg), 6).unwrap());
    spawner.spawn(buttons::button_task(Input::new(peripherals.GPIO17, cfg), 7).unwrap());
    spawner.spawn(buttons::button_task(Input::new(peripherals.GPIO18, cfg), 8).unwrap());

    // --- BLE: owns the radio for the rest of time. Never returns. ---
    ble::run(peripherals.BT).await;
}
