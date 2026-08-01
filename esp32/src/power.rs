//! Power management.
//!
//! The firmware spends essentially all of its time blocked in
//! [`crate::buttons::ButtonScanner::wait`], so battery life is decided almost
//! entirely by what the chip does while nothing is happening. Three knobs
//! matter, in descending order of impact:
//!
//! 1. **BLE modem sleep** — the radio powers down between connection events.
//!    Enabled in `sdkconfig.defaults` (`CONFIG_BTDM_CTRL_MODEM_SLEEP`).
//! 2. **Dynamic frequency scaling** — the CPU drops to [`CPU_MIN_MHZ`] whenever
//!    no driver holds a power lock, which is the normal state here.
//! 3. **Automatic light sleep** — the CPU is powered down between events. See
//!    [`LIGHT_SLEEP`] for why this is off by default.

use esp_idf_svc::sys::{self, esp, EspError};

/// Whether to enter light sleep when nothing holds a power lock.
///
/// This only pays off on boards that carry a 32.768 kHz crystal on 32K_XP /
/// 32K_XN. The Bluetooth controller has to keep time from a low-power clock
/// while it sleeps, and the main crystal (the only alternative on a bare
/// DevKit) stops in light sleep — so with `CONFIG_BTDM_CTRL_LPCLK_SEL_MAIN_XTAL`
/// the controller holds an `ESP_PM_NO_LIGHT_SLEEP` lock for as long as BLE is
/// up and enabling this buys nothing.
///
/// To use it: fit a 32.768 kHz crystal, switch the marked block in
/// `sdkconfig.defaults` over to the external crystal, and set this to `true`.
pub const LIGHT_SLEEP: bool = false;

/// CPU ceiling. A GATT server sending one byte per button press has no use for
/// 240 MHz, and active current scales roughly with frequency.
const CPU_MAX_MHZ: i32 = 80;

/// Frequency used whenever no power lock is held. Must be the crystal frequency
/// or an integer division of it; 40 MHz on every ESP32 module in circulation.
const CPU_MIN_MHZ: i32 = 40;

/// BLE transmit power. 0 dBm reaches a phone in the same room with margin and
/// costs meaningfully less per packet than the +3 dBm default.
const TX_POWER: sys::esp_power_level_t = sys::esp_power_level_t_ESP_PWR_LVL_N0;

/// Enable dynamic frequency scaling (and light sleep, if [`LIGHT_SLEEP`]).
///
/// Requires `CONFIG_PM_ENABLE` and `CONFIG_FREERTOS_USE_TICKLESS_IDLE`.
pub fn configure() -> Result<(), EspError> {
    let config = sys::esp_pm_config_t {
        max_freq_mhz: CPU_MAX_MHZ,
        min_freq_mhz: CPU_MIN_MHZ,
        light_sleep_enable: LIGHT_SLEEP,
    };

    esp!(unsafe { sys::esp_pm_configure(&config as *const _ as *const core::ffi::c_void) })
}

/// Lower the radio's transmit power.
///
/// Must run after the controller is up, i.e. after `BtDriver::new`.
pub fn set_ble_tx_power() -> Result<(), EspError> {
    esp!(unsafe {
        sys::esp_ble_tx_power_set(sys::esp_ble_power_type_t_ESP_BLE_PWR_TYPE_DEFAULT, TX_POWER)
    })
}
