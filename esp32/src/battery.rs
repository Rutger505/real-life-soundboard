//! Battery voltage through a 100k/100k divider on GPIO34.
//!
//! The ESP32 runs from the buck-boost's steady 3.3 V, so it cannot see the
//! cell directly. The divider sits after the power switch and halves the cell
//! voltage (4.2 V full -> 2.1 V on the pin). GPIO34 is on ADC1, which the
//! radio leaves alone (ADC2 is off-limits while BLE runs), and is input-only.
//!
//! The classic ESP32 has no ADC calibration in esp-hal, so we reproduce
//! ESP-IDF's `esp_adc_cal` line fit for ADC1 at 11 dB, scaled by the chip's
//! eFuse Vref when it has one. Expect +/-5-10 % on the percentage.

use esp_hal::analog::adc::{Adc, AdcConfig, AdcPin, Attenuation};
use esp_hal::peripherals::{ADC1, GPIO34};
use esp_hal::Blocking;

/// Readings averaged per measurement; the ESP32 SAR ADC is noisy.
const SAMPLES: u32 = 32;

/// Cell voltage / pin voltage (two equal resistors).
const DIVIDER: u32 = 2;

/// ESP-IDF's ADC1 line fit at 11 dB: `mV = raw * (vref * SCALE / 4096) / 65536 + OFFSET`.
const ATTEN_11DB_SCALE: u32 = 196_602;
const ATTEN_11DB_OFFSET_MV: u32 = 142;

/// Vref when the eFuse is not burned, and the eFuse step size.
const VREF_DEFAULT_MV: i32 = 1100;
const VREF_STEP_MV: i32 = 7;

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

pub struct Battery {
    adc: Adc<'static, ADC1<'static>, Blocking>,
    pin: AdcPin<GPIO34<'static>, ADC1<'static>>,
    /// Line-fit slope, scaled by 65536.
    coeff_a: u32,
}

impl Battery {
    pub fn new(adc1: ADC1<'static>, pin: GPIO34<'static>) -> Self {
        let mut config = AdcConfig::new();
        // 11 dB covers up to ~2.5 V accurately; the pin peaks at 2.1 V.
        let pin = config.enable_pin(pin, Attenuation::_11dB);
        let adc = Adc::new(adc1, config);
        let vref = efuse_vref_mv();
        log::info!("[battery] ADC vref {vref} mV");
        Self {
            adc,
            pin,
            coeff_a: vref * ATTEN_11DB_SCALE / 4096,
        }
    }

    /// Blocks for well under a millisecond.
    pub fn measure(&mut self) -> Reading {
        let sum: u64 = (0..SAMPLES).map(|_| self.read_raw() as u64).sum();
        let pin_mv = (self.coeff_a as u64 * sum / SAMPLES as u64 + 32_768) / 65_536
            + ATTEN_11DB_OFFSET_MV as u64;
        let millivolts = (pin_mv * DIVIDER as u64).min(u16::MAX as u64) as u16;
        let reading = Reading {
            millivolts,
            percent: percent(millivolts),
            charging: Charging::Unknown,
        };
        log::info!("[battery] {} mV, {} %", reading.millivolts, reading.percent);
        reading
    }

    fn read_raw(&mut self) -> u16 {
        loop {
            // The only error is WouldBlock while the conversion runs.
            if let Ok(raw) = self.adc.read_oneshot(&mut self.pin) {
                return raw;
            }
        }
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

/// Factory-measured Vref: 5 bits, sign-magnitude, 7 mV steps around 1100 mV.
/// Zero on chips that were never calibrated, which gives the default.
fn efuse_vref_mv() -> u32 {
    let bits: u8 = esp_hal::efuse::read_field_le(esp_hal::efuse::ADC_VREF);
    let magnitude = (bits & 0x0F) as i32;
    let steps = if bits & 0x10 != 0 { -magnitude } else { magnitude };
    (VREF_DEFAULT_MV + steps * VREF_STEP_MV) as u32
}
