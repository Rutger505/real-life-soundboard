//! Battery voltage through a 100k/100k divider on GPIO34.
//!
//! The ESP32 runs from the buck-boost's steady 3.3 V, so it cannot see the
//! cell directly. The divider sits after the power switch and halves the cell
//! voltage (4.2 V full -> 2.1 V on the pin). GPIO34 is on ADC1, which the
//! radio leaves alone (ADC2 is off-limits while BLE runs), and is input-only.
//!
//! Raw readings go through ESP-IDF's ADC calibration (line fitting, from the
//! chip's eFuse Vref or Two Point values when it has them).

use esp_idf_svc::hal::adc::attenuation::DB_12;
use esp_idf_svc::hal::adc::oneshot::config::{AdcChannelConfig, Calibration};
use esp_idf_svc::hal::adc::oneshot::{AdcChannelDriver, AdcDriver};
use esp_idf_svc::hal::adc::ADC1;
use esp_idf_svc::hal::gpio::{ADCPin, Gpio34};
use esp_idf_svc::sys::EspError;

/// Readings averaged per measurement; the ESP32 SAR ADC is noisy.
const SAMPLES: u32 = 32;

/// Cell voltage / pin voltage (two equal resistors).
const DIVIDER: u32 = 2;

/// Resting LiPo curve, cell mV -> percent, highest first. The buck-boost keeps
/// running far below 3.3 V, but the cell has nothing useful left there.
const LIPO_CURVE: [(u16, u8); 11] = [
    (4200, 100),
    (4100, 90),
    (4000, 80),
    (3900, 68),
    (3800, 55),
    (3750, 45),
    (3700, 35),
    (3650, 25),
    (3600, 15),
    (3500, 6),
    (3300, 0),
];

/// Charging state as reported to the phone. TP4056 `CHRG` is not wired, so
/// the firmware cannot tell yet.
#[derive(Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum Charging {
    Unknown = 0,
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub struct Reading {
    pub millivolts: u16,
    pub percent: u8,
    pub charging: Charging,
}

impl Reading {
    /// Wire format of the custom battery characteristic: cell mV as a
    /// little-endian u16, then the charging state.
    pub fn to_bytes(self) -> [u8; 3] {
        let [lo, hi] = self.millivolts.to_le_bytes();
        [lo, hi, self.charging as u8]
    }
}

type Channel = AdcChannelDriver<
    'static,
    <Gpio34<'static> as ADCPin>::AdcChannel,
    AdcDriver<'static, <ADC1<'static> as esp_idf_svc::hal::adc::Adc>::AdcUnit>,
>;

pub struct Battery {
    channel: Channel,
}

impl Battery {
    pub fn new(adc1: ADC1<'static>, pin: Gpio34<'static>) -> Result<Self, EspError> {
        let adc = AdcDriver::new(adc1)?;
        let config = AdcChannelConfig {
            // 12 dB (formerly "11 dB") covers up to ~2.5 V accurately; the
            // pin peaks at 2.1 V.
            attenuation: DB_12,
            calibration: Calibration::Line,
            ..Default::default()
        };
        let channel = AdcChannelDriver::new(adc, pin, &config)?;
        Ok(Self { channel })
    }

    /// Blocks for well under a millisecond. A failed conversion reads as 0 V,
    /// which the app shows as empty rather than hiding the battery.
    pub fn measure(&mut self) -> Reading {
        let pin_mv = self.pin_millivolts().unwrap_or_else(|e| {
            log::error!("[battery] ADC read failed: {e}");
            0
        });
        let millivolts = (pin_mv as u32 * DIVIDER).min(u16::MAX as u32) as u16;
        let reading = Reading {
            millivolts,
            percent: percent(millivolts),
            charging: Charging::Unknown,
        };
        log::info!("[battery] {} mV, {} %", reading.millivolts, reading.percent);
        reading
    }

    /// Averages raw counts, then calibrates once. The line fit is linear, so
    /// that equals averaging calibrated readings.
    fn pin_millivolts(&mut self) -> Result<u16, EspError> {
        let mut sum = 0u32;
        for _ in 0..SAMPLES {
            sum += self.channel.read_raw()? as u32;
        }
        let raw = ((sum + SAMPLES / 2) / SAMPLES) as u16;
        self.channel.raw_to_mv(raw)
    }
}

/// Piecewise-linear lookup in [`LIPO_CURVE`].
fn percent(mv: u16) -> u8 {
    let (top_mv, top_pct) = LIPO_CURVE[0];
    if mv >= top_mv {
        return top_pct;
    }
    for pair in LIPO_CURVE.windows(2) {
        let ((hi_mv, hi_pct), (lo_mv, lo_pct)) = (pair[0], pair[1]);
        if mv >= lo_mv {
            let span = (hi_pct - lo_pct) as u32;
            let pos = (mv - lo_mv) as u32;
            return lo_pct + (span * pos / (hi_mv - lo_mv) as u32) as u8;
        }
    }
    0
}
