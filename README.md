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

# Build
cd esp32
cargo build --release

# Flash (replace /dev/ttyUSB0 with your port)
espflash flash target/xtensa-esp32-espidf/release/soundboard-esp32 \
  --port /dev/ttyUSB0 --monitor
```

The binary is `esp32/target/xtensa-esp32-espidf/release/soundboard-esp32`. The first
build takes a long while (ESP-IDF clone, tools, CMake build); later builds only rebuild
ESP-IDF when `sdkconfig.*` changes.

The ESP-IDF settings live in `esp32/sdkconfig.defaults`: BLE-only Bluedroid, one
connection, modem sleep, frequency scaling (80 MHz max, 40 MHz idle) and 1 ms FreeRTOS
ticks.

### With the 32.768 kHz crystal

Once the crystal is fitted (see the TODO below), build with the `sdkconfig.crystal`
overlay. It moves the RTC and the BLE sleep clock to the crystal, and the firmware then
enables automatic light sleep between connection events:

```bash
ESP_IDF_SDKCONFIG_DEFAULTS="sdkconfig.defaults;sdkconfig.crystal" cargo build --release
```

The boot log says `light sleep: on`. If the crystal does not start, ESP-IDF warns
`32.768kHz XTAL not detected` and falls back to the main crystal, so the board still
works, just without light sleep.

### Logging

Builds log only warnings and errors (`CONFIG_LOG_DEFAULT_LEVEL_WARN` in
`esp32/sdkconfig.defaults`). To see button presses and BLE events on the serial monitor,
switch it to `CONFIG_LOG_DEFAULT_LEVEL_INFO`.

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
- [ ] Check the load capacitors' value. They were fitted as "10 nF", but a
  32.768 kHz crystal needs about 10-22 pF. 10 nF (marked `103`) is 1000x too
  much and stops the crystal from oscillating. 10 pF is marked `100`.
- [ ] Solder the crystal between GPIO32 and GPIO33 (either way round). Keep the
  leads as short as possible and clean the flux off afterwards.
- [ ] Confirm row 1 moved from GPIO32 to GPIO14 (firmware 0.3.0 expects 14).
- [ ] Confirm the divider's top wire is on the power switch output, not on
  TP4056 `OUT+` before the switch. Before the switch it drains ~20 µA while off.

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
  (the crystal ones in `esp32/sdkconfig.crystal`).
- [x] Keep the current matrix pins, LED and the BLE UUIDs (button + battery),
  so the app works with either firmware.
- [x] Use ESP-IDF's ADC calibration for the battery reading.
- [ ] Flash 0.4.0 and check presses, battery and reconnects on the board.
- [ ] Flash the crystal build and check the boot log says `light sleep: on`
  without the `32.768kHz XTAL not detected` warning.
- [ ] Measure the battery drain before and after, to see what it gains.

## Versioning

Bump the version with every change you ship:

- **Android app:** in `android/app/build.gradle.kts`, raise `versionCode` by one and bump
  `versionName` (e.g. `1.0` → `1.1`). The build appends the git commit (`1.1+<hash>`, with
  `-dirty` for uncommitted changes) and the app shows it under the grid, so you can tell
  which build is installed on the phone.
- **Firmware:** bump `version` in `esp32/Cargo.toml`.

## Project structure

```
esp32/          Rust firmware (ESP-IDF std, esp-idf-svc + Bluedroid BLE)
android/        Android app (Kotlin, Jetpack Compose)
```
