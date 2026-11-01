//! Battery voltage and charger state, from the XIAO's own circuit.
//!
//! The cell sits straight on the XIAO's BAT pads. Its divider runs from BAT
//! through 1 MΩ to P0.31 (AIN7) and through 510 kΩ to P0.14, so the pin sees
//! 510 / 1510 of the cell: 1.42 V at 4.2 V.
//!
//! P0.14 stays low all the time, which costs ~2.8 µA through the divider.
//! Driving it high or floating it would save that, but then P0.31 sits at or
//! above VDD + 0.3 V, the pin's absolute maximum; Seeed says to keep it low.
//!
//! The charger (BQ25101) pulls P0.17 low while it charges. The pull-up is
//! only on while reading it.

use embassy_nrf::Peri;
use embassy_nrf::gpio::{Input, Level, Output, OutputDrive, Pull};
use embassy_nrf::peripherals::{P0_14, P0_17, P0_31, SAADC};
use embassy_nrf::saadc::{self, ChannelConfig, Gain, Oversample, Saadc, Time};
use embassy_time::Timer;

use crate::Irqs;

/// Cell voltage / pin voltage, in kΩ: (1000 + 510) / 510.
const DIVIDER_TOP: u32 = 1000 + 510;
const DIVIDER_BOTTOM: u32 = 510;

/// Internal 0.6 V reference at gain 1/3: 1.8 V full scale over 12 bits.
const FULL_SCALE_MV: u32 = 1800;
const COUNTS: u32 = 4096;

/// Resting LiPo curve, cell mV -> percent, highest first.
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

/// Charging state as reported to the phone (third byte of Battery (raw)).
#[derive(Clone, Copy, PartialEq, Eq, defmt::Format)]
#[repr(u8)]
pub enum Charging {
    Charging = 1,
    NotCharging = 2,
}

#[derive(Clone, Copy, PartialEq, Eq, defmt::Format)]
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
    saadc: Peri<'static, SAADC>,
    vbat: Peri<'static, P0_31>,
    chg: Peri<'static, P0_17>,
    _divider: Output<'static>,
}

impl Battery {
    pub fn new(
        saadc: Peri<'static, SAADC>,
        vbat: Peri<'static, P0_31>,
        divider: Peri<'static, P0_14>,
        chg: Peri<'static, P0_17>,
    ) -> Self {
        Self {
            saadc,
            vbat,
            chg,
            _divider: Output::new(divider, Level::Low, OutputDrive::Standard),
        }
    }

    /// Takes about a millisecond. The SAADC is only enabled for the duration,
    /// it draws ~1 mA while on.
    pub async fn measure(&mut self) -> Reading {
        let pin_mv = self.pin_millivolts().await;
        let millivolts = (pin_mv * DIVIDER_TOP / DIVIDER_BOTTOM).min(u16::MAX as u32) as u16;
        let reading = Reading {
            millivolts,
            percent: percent(millivolts),
            charging: self.charging().await,
        };
        defmt::info!("[battery] {}", reading);
        reading
    }

    async fn pin_millivolts(&mut self) -> u32 {
        let mut config = saadc::Config::default();
        // Averages 16 conversions in hardware.
        config.oversample = Oversample::Over16x;
        let mut channel = ChannelConfig::single_ended(self.vbat.reborrow());
        channel.gain = Gain::Gain1_3;
        // The divider's source impedance is ~340 kΩ: needs 40 µs to settle.
        channel.time = Time::_40US;

        let mut adc = Saadc::new(self.saadc.reborrow(), Irqs, config, [channel]);
        adc.calibrate().await;
        let mut buf = [0i16; 1];
        adc.sample(&mut buf).await;
        drop(adc);

        buf[0].max(0) as u32 * FULL_SCALE_MV / COUNTS
    }

    async fn charging(&mut self) -> Charging {
        let chg = Input::new(self.chg.reborrow(), Pull::Up);
        // Lets the pull-up charge the pin.
        Timer::after_micros(50).await;
        if chg.is_low() {
            Charging::Charging
        } else {
            Charging::NotCharging
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
