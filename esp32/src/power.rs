//! Power management.
//!
//! The firmware spends nearly all of its time blocked, so battery life comes
//! down to what the chip does while nothing happens:
//!
//! 1. **BLE modem sleep**: the radio powers down between connection events
//!    (`CONFIG_BTDM_CTRL_MODEM_SLEEP` in `sdkconfig.defaults`).
//! 2. **Dynamic frequency scaling**: the CPU drops to [`CPU_MIN_MHZ`] whenever
//!    no driver holds a power lock.
//! 3. **Automatic light sleep**: the CPU powers down between events. Only with
//!    a 32.768 kHz crystal, see [`LIGHT_SLEEP`].

use esp_idf_svc::sys::{self, esp, EspError};

/// Light sleep between events, on when the firmware is built with
/// `sdkconfig.crystal` (see the README).
///
/// The BLE controller has to keep time from a low-power clock while the chip
/// sleeps. The main crystal stops in light sleep, so with
/// `CONFIG_BTDM_CTRL_LPCLK_SEL_MAIN_XTAL` the controller holds a no-light-sleep
/// lock while BLE is up and this would buy nothing. A 32.768 kHz crystal on
/// GPIO32/33 keeps running. If that crystal does not start, ESP-IDF falls back
/// to the main crystal (with a warning in the log), and the chip just stays
/// awake as without it.
pub const LIGHT_SLEEP: bool = cfg!(esp_idf_btdm_ctrl_lpclk_sel_ext_32k_xtal);

/// CPU ceiling. One byte per press has no use for 240 MHz, and active current
/// scales roughly with frequency. BLE needs at least 80 MHz.
const CPU_MAX_MHZ: i32 = 80;

/// Used whenever no power lock is held: the 40 MHz main crystal.
const CPU_MIN_MHZ: i32 = 40;

/// BLE transmit power. 0 dBm reaches a phone in the same room with margin and
/// costs less per packet than the +3 dBm default.
const TX_POWER: sys::esp_power_level_t = sys::esp_power_level_t_ESP_PWR_LVL_N0;

/// Enable frequency scaling, and light sleep if [`LIGHT_SLEEP`].
/// Needs `CONFIG_PM_ENABLE` and `CONFIG_FREERTOS_USE_TICKLESS_IDLE`.
pub fn configure() -> Result<(), EspError> {
    let config = sys::esp_pm_config_t {
        max_freq_mhz: CPU_MAX_MHZ,
        min_freq_mhz: CPU_MIN_MHZ,
        light_sleep_enable: LIGHT_SLEEP,
    };
    esp!(unsafe { sys::esp_pm_configure(&config as *const _ as *const core::ffi::c_void) })
}

/// Lower the radio's transmit power. Must run after the controller is up.
pub fn set_ble_tx_power() -> Result<(), EspError> {
    for kind in [
        sys::esp_ble_power_type_t_ESP_BLE_PWR_TYPE_DEFAULT,
        sys::esp_ble_power_type_t_ESP_BLE_PWR_TYPE_ADV,
        sys::esp_ble_power_type_t_ESP_BLE_PWR_TYPE_SCAN,
    ] {
        esp!(unsafe { sys::esp_ble_tx_power_set(kind, TX_POWER) })?;
    }
    Ok(())
}
