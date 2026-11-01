# Real Live Soundboard

A Bluetooth soundboard: press a physical button on an ESP32 and the paired Android phone plays the corresponding audio clip.

## What it is

9 physical buttons in a 3x3 matrix, wired to an ESP32, send BLE GATT notifications to an Android app. Each button maps to an audio file you configure via the app. Press a button → hear a sound.

The Android app runs a **foreground service**, so the BLE connection and audio playback keep working while you use your phone normally, with the screen off, or with the app closed. An ongoing notification shows the connection status and the last button that was pressed.

## Hardware

- ESP32 dev board (ESP32-WROOM; a WROVER uses GPIO16/17 for PSRAM)
- 9 momentary push buttons, wired as a 3x3 matrix
- 1 LED (status indicator) and a 220-330 ohm resistor in series with it
- Optional: 9x 1N4148 diodes, one per button (see below)
- 2x 100k resistors for the battery voltage divider (see [Battery level](#battery-level))

### Pins

The matrix needs 6 GPIOs for 9 buttons. The three rows are on one header and
the three columns on the other, on both the 30-pin DevKit V1 and the 38-pin
DevKitC. None of them is a strapping pin or a flash pin.

GPIO26 and GPIO27 are skipped: the current board has a fault between them.
GPIO32 and GPIO33 are kept free for an optional 32.768 kHz crystal.

| Signal   | GPIO | Firmware setup                  |
|----------|------|---------------------------------|
| Row 0    | 25   | input, internal pull-up         |
| Row 1    | 14   | input, internal pull-up         |
| Row 2    | 13   | input, internal pull-up         |
| Column 0 | 17   | open-drain output               |
| Column 1 | 16   | open-drain output               |
| Column 2 | 4    | open-drain output               |
| LED+     | 18   | push-pull output, through the resistor |
| Battery  | 34   | ADC1, 11 dB, divider midpoint   |

The LED is not on GPIO2 because most dev boards already have their own LED on
that pin. GPIO18 is on the same header as the columns, two pins past GPIO17
on both boards, and it is not a strapping pin.

"Open-drain" is a firmware setting, not something you wire. A normal output
drives its pin to either 3.3 V or ground. An open-drain output can only pull
its pin to ground, or let go of it. When a column lets go, the row's pull-up
brings the line back to 3.3 V.

### Button matrix

A row is three buttons side by side, left to right. A column is three buttons
one behind the other, front to back. Every button has one leg on a row wire and
the other on a column wire. The button in row `r`, column `c` sends index
`r * 3 + c` to the phone. Looking at the top of the case with the USB-C port
towards you, row 0 is the one furthest away and column 0 is on the left:

|            | Column 0 (GPIO17) | Column 1 (GPIO16) | Column 2 (GPIO4) |
|------------|-------------------|-------------------|------------------|
| Row 0 (GPIO25) | button 1 (index 0) | button 2 (index 1) | button 3 (index 2) |
| Row 1 (GPIO14) | button 4 (index 3) | button 5 (index 4) | button 6 (index 5) |
| Row 2 (GPIO13) | button 7 (index 6) | button 8 (index 7) | button 9 (index 8) |

No resistors are needed. While nobody presses anything, all columns are pulled
low and the firmware sleeps until a row goes low. It then scans one column at a
time every 5 ms until every button is released. The columns are open-drain, so
they can only pull low. Two buttons held in the same row can never short two
driven outputs together.

Without diodes, holding three buttons that form an L makes the fourth corner of
that rectangle read as pressed too. One or two buttons at a time always read
correctly. If you want any combination to work, put a 1N4148 in series with
each button, anode on the row side and cathode (the band) on the column side.

## BLE UUIDs

| Role                  | UUID                                   |
|-----------------------|----------------------------------------|
| Soundboard service    | `12345678-1234-1234-1234-123456789012` |
| Button                | `12345678-1234-1234-1234-123456789abc` |
| Battery (raw)         | `12345678-1234-1234-1234-123456789abd` |
| Battery Service       | `0x180F` (standard)                    |
| Battery Level         | `0x2A19` (standard)                    |

- **Button** (read, notify): when a button is pressed the ESP32 sends a 1-byte
  notification with the button index (0–8).
- **Battery Level** (read, notify): 1 byte, 0–100 %.
- **Battery (raw)** (read, notify): 3 bytes. Cell voltage in mV as a
  little-endian `u16`, then the charging state (0 = unknown; `CHRG` is not
  wired yet).

## Battery level

The ESP32 runs from the buck-boost's steady 3.3 V, so it never sees the battery
voltage. A voltage divider makes it visible.

**Hardware**

- Voltage divider, 2x 100k, from the power switch's output (so it draws
  nothing when off) to GND. The midpoint goes to **GPIO34**: an ADC1 pin, so it
  doesn't clash with the radio the way ADC2 does, and input-only. At 4.2 V the
  pin sees 2.1 V. The divider draws about 20 µA.
- Optional: TP4056 `CHRG` (open-drain, active-low while charging) to a free GPIO
  with an internal pull-up. While charging, the voltage reads high, so the app
  could show "charging" instead of a percentage. Not used by the firmware yet.

**Firmware** (`esp32/src/battery.rs`)

- Measured on every button press, before the press is sent, because the
  voltage sags while the radio transmits. Also every 30 min, and once right
  after a phone connects.
- 32 ADC samples are averaged, then converted with ESP-IDF's ADC calibration
  (line fitting at 12 dB, from the chip's eFuse values). Expect ±5-10 %.
- Voltage to percent through a resting LiPo curve (4.2 V = 100 %, 3.8 V = 55 %,
  3.7 V = 35 %, 3.3 V = 0 %).
- Both battery characteristics only notify when the percentage changes. Reads
  always return the latest measurement.

**App** (`Battery.kt`, `BleManager.kt`)

- After service discovery the app subscribes to the button characteristic
  first, then to Battery Level and Battery (raw), and reads both once (the
  firmware only notifies on a change). Android runs one GATT operation at a
  time, so these are queued, each started from the previous one's callback.
- The battery characteristics are optional: on older firmware the battery UI
  stays hidden and presses work as before.
- The percentage shows under the button grid and in the foreground
  notification, with a warning below 15 %. The info icon opens nerd stats:
  voltage, percentage, charging state, capacity, mAh used, average draw, time
  remaining and when it runs empty, time since the last charge, number of
  samples, last measurement and button presses.
- Every reading becomes a timestamped sample (time, %, mV), stored in
  `battery_history.json` in the app's files dir, so it survives restarts.
  The firmware can't report charging, so a jump up of 10 points or more counts
  as a charge and starts a new set of samples.
- Drain (%/h) is a least-squares fit over all samples since the last charge.
  Average current = drain × capacity / 100, time remaining = level / drain.
  It shows "not enough data" until the samples span an hour and 3 %. The
  battery capacity (mAh) is a setting in the stats dialog; only the mAh
  numbers need it.

## ESP32 firmware

Rust on ESP-IDF (`std`), through [esp-idf-svc](https://github.com/esp-rs/esp-idf-svc)
and its Bluedroid GATT server. The target is `xtensa-esp32-espidf`; `embuild` downloads
and builds ESP-IDF v5.5.5 on the first build, and `ldproxy` links. ESP-IDF and its tools
go to `~/.espressif` (`ESP_IDF_TOOLS_INSTALL_DIR = "global"`), so `cargo clean` does not
download them again.

The Xtensa Rust toolchain comes from `espup`, per the
[esp-rs book](https://docs.esp-rs.org/book/):

```bash
# Install toolchain (once). Run this from $HOME, not from esp32/ --
# rust-toolchain.toml there pins the 'esp' channel before it exists.
cargo install espup ldproxy espflash
espup install            # Xtensa Rust + Xtensa LLVM + GCC xtensa-esp-elf

# Load the environment into the shell (espup writes this file)
. $HOME/export-esp.sh

# Build, flash and open the serial monitor (espflash is the cargo runner)
cd esp32
cargo run --release
# Pick the port: cargo run --release -- --port /dev/ttyUSB0
```

The binary is `esp32/target/xtensa-esp32-espidf/release/soundboard-esp32`. The first
build takes a long while (ESP-IDF clone, tools, CMake build); later builds only rebuild
ESP-IDF when `sdkconfig.*` changes.

The ESP-IDF settings live in `esp32/sdkconfig.defaults`: BLE-only Bluedroid, one
connection, modem sleep, frequency scaling (80 MHz max, 40 MHz idle), 1 ms FreeRTOS
ticks, and the 32.768 kHz crystal on GPIO32/33 as RTC and BLE sleep clock, so the chip
light-sleeps between connection events. The resolved settings end up in
`target/xtensa-esp32-espidf/release/build/esp-idf-sys-*/out/sdkconfig`.

The boot log says `light sleep: on`. If the crystal does not start, ESP-IDF warns
`32.768kHz XTAL not detected` and falls back to the main crystal, so the board still
works, just without light sleep.

### Logging

Builds log only warnings and errors (`CONFIG_LOG_DEFAULT_LEVEL_WARN` in
`esp32/sdkconfig.defaults`). To see button presses and BLE events on the serial monitor,
switch it to `CONFIG_LOG_DEFAULT_LEVEL_INFO`.

## nRF52 firmware (XIAO nRF52840)

A port of the ESP32 firmware to a Seeed XIAO nRF52840, for a much lower standby
draw. Same buttons, LED, GATT services and UUIDs, so the app works with either
board. Rust on [Embassy](https://embassy.dev), with Nordic's SoftDevice Controller
([nrf-sdc](https://github.com/alexmoon/nrf-sdc)) as BLE link layer and
[TrouBLE](https://github.com/embassy-rs/trouble) as host.

**Hardware**

| Signal   | XIAO pin | nRF pin | Firmware setup                     |
|----------|----------|---------|------------------------------------|
| Row 0    | D0       | P0.02   | input, internal pull-up            |
| Row 1    | D1       | P0.03   | input, internal pull-up            |
| Row 2    | D2       | P0.28   | input, internal pull-up            |
| Column 0 | D3       | P0.29   | open-drain output                  |
| Column 1 | D4       | P0.04   | open-drain output                  |
| Column 2 | D5       | P0.05   | open-drain output                  |
| LED+     | D6       | P1.11   | push-pull output, through the resistor |

- The LiPo goes straight to the **BAT+ / BAT-** pads on the back, with the power
  switch in BAT+. No buck-boost, no TP4056 and no external divider: the XIAO has
  its own regulator, charger (BQ25101, charges from USB-C) and battery divider.
  With the switch off, USB-C does not reach the cell, so switch on to charge.
- The 32.768 kHz crystal is on the board.

**Power**

- Idle draw is the chip sleeping with RAM kept and the RTC running, plus the
  radio waking for the link. Expect roughly 5-10 µA connected and idle.
- 32.768 kHz crystal as low-frequency clock, DC/DC regulator on, QSPI flash in
  deep power-down (`nrf52/src/main.rs`, `flash.rs`).
- Buttons wake the chip through GPIO sense, which costs nothing while idle.
- The firmware asks the phone for a 30-45 ms interval with peripheral latency 32:
  presses still go out at the next interval, but the idle radio only wakes about
  every 1.5 s. The boot log shows what the phone granted.
- Advertising is fast (100-200 ms) for 30 s after boot or a disconnect, then slow
  (1-1.2 s) until a phone connects.
- The XIAO's battery divider stays enabled (P0.14 low), ~2.8 µA. Disabling it
  would put P0.31 at its absolute maximum voltage; Seeed says to leave it low.
- A connected probe keeps the debug logic powered (~mA). Measure with the probe
  unplugged.

**Battery** (`nrf52/src/battery.rs`)

- SAADC on P0.31 through the 1M / 510k divider, internal reference, 16x
  hardware oversampling, calibrated on every reading. Same LiPo curve and same
  measuring moments as the ESP32 firmware.
- Charging state from the charger's status pin (P0.17): Battery (raw) reports
  1 = charging, 2 = not charging.

**Debug probe**

Flashing and logs go over SWD with [probe-rs](https://probe.rs). A Raspberry Pi
Pico (H) with the [debugprobe](https://github.com/raspberrypi/debugprobe)
firmware works: hold BOOTSEL, plug it in, copy `debugprobe_on_pico.uf2` onto it.

| Pico      | XIAO (pads on the back) |
|-----------|-------------------------|
| GP2       | SWCLK                   |
| GP3       | SWDIO                   |
| GND       | GND                     |

Power the XIAO from its own USB-C or the battery, not from the Pico.

**Build and flash**

```bash
# Once: target and probe-rs
rustup target add thumbv7em-none-eabihf
curl -LsSf https://github.com/probe-rs/probe-rs/releases/latest/download/probe-rs-tools-installer.sh | sh

cd nrf52
# The XIAO ships with the UF2 bootloader and the chip locked for debugging.
# The first time, erase it all (this removes the UF2 bootloader):
probe-rs erase --chip nRF52840_xxAA --allow-erase-all

# Build, flash and stream the log (probe-rs is the cargo runner)
cargo run --release
```

The firmware is linked at address 0 (`nrf52/memory.x`) and keeps debugging
enabled, so later flashes need no erase. To go back to UF2, flash Seeed's
bootloader hex with `probe-rs download --binary-format hex`.

**Logging**

[defmt](https://defmt.ferrous-systems.com) over RTT, shown by `cargo run`. The
level is `DEFMT_LOG` in `nrf52/.cargo/config.toml` (`info`). A panic or hard
fault logs and resets the chip.

**TODO**

- [ ] Wire the matrix, LED and battery to the XIAO.
- [ ] Flash and check presses, battery, charging state and reconnects.
- [ ] Check the granted connection parameters in the log.
- [ ] Measure the idle current (PPK2 on the BAT pads, probe unplugged).

## Case

One parametric OpenSCAD file per board, for PLA and printed without supports:

- `esp32/case/real-live-soundboard.scad`: ESP32, TP4056, buck-boost and LiPo, 56 x 87.4 x 23.2 mm.
- `nrf52/case/real-live-soundboard.scad`: XIAO nRF52840 and LiPo, 47.6 x 65.3 x 20.9 mm.

Both start with the measured parts, then derived values and `assert`s for every
clearance they rely on. The `part` dropdown in the OpenSCAD customizer picks what to
show: `assembly` and `open` (with the parts as ghosts), `tray`, `lid`, `plate` or
`print` (all three laid out on the bed). Closed with four M2 self-tapping screws.

**nRF52 case**

- Front band: the XIAO lies on two rails under its short ends, so the wires from the
  BAT and SWD pads on its underside leave underneath. USB-C goes out the left wall.
  The slide switch is in the right wall, the status LED hangs off the front wall
  between them. A dab of hot glue on the rails keeps the board down.
- Back band: the LiPo, with the side walls, a rear beam and a front rib around it.
  The leads leave at the front corners.
- The 3x3 button plate sits over the battery, pegged onto two side posts and the
  rear beam.
- Tolerances are half the ESP32 case's, like the motorized-blinds remote (those
  printed too loose). The XIAO sizes are datasheet values: measure the board first.

Render to check the asserts and the size:

```bash
cd nrf52/case    # or esp32/case
for p in tray lid plate; do openscad -o ~/Temporary/$p.stl -D "part=\"$p\"" real-live-soundboard.scad; done
```

## Android app

Requires JDK 17 and Android SDK 35 (`platforms;android-35`, `build-tools;35.0.0`).
Android Studio is optional — the Gradle wrapper (Gradle 9.5) is enough on a headless
machine, it just needs `local.properties` pointing at the SDK:

```bash
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug
# Or open in Android Studio and run
```

Output: `android/app/build/outputs/apk/debug/app-debug.apk` (~8.7 MB).

**Setup:**
1. Install the APK on your Android device (API 26+).
2. Grant Bluetooth and storage permissions when prompted.
3. The app automatically scans for the ESP32 by service UUID.
4. Tap the folder icon on each button slot to assign an audio file.
5. Press a physical button — the LED blinks and the audio plays on the phone.

## TODO

### 32.768 kHz crystal and ESP-IDF light sleep

A 32.768 kHz crystal on GPIO32/33 lets the BLE controller keep time while the
chip light-sleeps between connection events. That needs ESP-IDF, which is why the
firmware moved back from bare-metal (esp-radio keeps BLE sleep disabled) to
Rust + ESP-IDF.

**Hardware: current state**

- [x] Battery divider: `OUT+ ─100k─┬─100k─ GND`, midpoint to GPIO34.
- [x] Load capacitors, one from GPIO32 to GND and one from GPIO33 to GND.
- [x] Load capacitors are 10 pF (a 32.768 kHz crystal needs about 10-22 pF).
- [x] Salvaged crystal from a drone board fitted. Its rating is unknown and it does
  not start: the boot log warns `32.768kHz XTAL not detected`.
- [ ] Replace it with a known 32.768 kHz crystal, CL about 7-9 pF to suit the 10 pF
  capacitors, between GPIO32 and GPIO33.
- [x] Flux cleaned off around GPIO32/33 and the crystal.
- [x] Row 1 moved from GPIO32 to GPIO14.
- [x] Divider's top wire is on the power switch output, so it draws nothing while off.

Target wiring:

```
GPIO32 ──┬──[32.768 kHz]──┬── GPIO33
         │                │
      [10-22 pF]       [10-22 pF]
         │                │
        GND              GND
```

Keep GPIO26/27 unused (board fault between them).

**Firmware**

- [x] Port the firmware back to Rust + ESP-IDF (esp-idf-svc), firmware 0.4.0.
- [x] Enable `CONFIG_RTC_CLK_SRC_EXT_CRYS`, `CONFIG_BTDM_CTRL_LPCLK_SEL_EXT_32K_XTAL`,
  `CONFIG_BTDM_CTRL_MODEM_SLEEP`, `CONFIG_PM_ENABLE` and
  `CONFIG_FREERTOS_USE_TICKLESS_IDLE`, and light sleep in `esp_pm_configure`
  (all in `esp32/sdkconfig.defaults`).
- [x] Keep the current matrix pins, LED and the BLE UUIDs (button + battery),
  so the app works with either firmware.
- [x] Use ESP-IDF's ADC calibration for the battery reading.
- [ ] Flash 0.5.0 and check presses, battery and reconnects on the board.
- [ ] Check the boot log says `light sleep: on`
  without the `32.768kHz XTAL not detected` warning.
- [ ] Measure the battery drain before and after, to see what it gains.

## Versioning

Bump the version with every change you ship:

- **Android app:** in `android/app/build.gradle.kts`, raise `versionCode` by one and bump
  `versionName` (e.g. `1.0` → `1.1`). The build appends the git commit (`1.1+<hash>`, with
  `-dirty` for uncommitted changes) and the app shows it under the grid, so you can tell
  which build is installed on the phone.
- **Firmware:** bump `version` in `esp32/Cargo.toml` or `nrf52/Cargo.toml`.

## Project structure

```
esp32/          Rust firmware (ESP-IDF std, esp-idf-svc + Bluedroid BLE)
esp32/case/     OpenSCAD case for the ESP32 build
nrf52/          Rust firmware for the XIAO nRF52840 (Embassy, nrf-sdc + TrouBLE)
nrf52/case/     OpenSCAD case for the nRF52 build
android/        Android app (Kotlin, Jetpack Compose)
```
