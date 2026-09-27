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

### Pins

The matrix needs 6 GPIOs for 9 buttons. The three rows sit next to each other
on one header and the three columns on the other, on both the 30-pin DevKit V1
and the 38-pin DevKitC. None of them is a strapping pin or a flash pin.

| Signal   | GPIO | Firmware setup                  |
|----------|------|---------------------------------|
| Row 0    | 25   | input, internal pull-up         |
| Row 1    | 26   | input, internal pull-up         |
| Row 2    | 27   | input, internal pull-up         |
| Column 0 | 17   | open-drain output               |
| Column 1 | 16   | open-drain output               |
| Column 2 | 4    | open-drain output               |
| LED+     | 18   | push-pull output, through the resistor |

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
| Row 1 (GPIO26) | button 4 (index 3) | button 5 (index 4) | button 6 (index 5) |
| Row 2 (GPIO27) | button 7 (index 6) | button 8 (index 7) | button 9 (index 8) |

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

| Role           | UUID                                   |
|----------------|----------------------------------------|
| Service        | `12345678-1234-1234-1234-123456789012` |
| Characteristic | `12345678-1234-1234-1234-123456789abc` |

The characteristic is notify-only. When a button is pressed the ESP32 sends a 1-byte notification with the button index (0–8).

## ESP32 firmware

Bare-metal (`no_std`) Xtensa, built on [esp-hal](https://github.com/esp-rs/esp-hal) with
[trouble](https://github.com/embassy-rs/trouble) as the BLE host and `esp-radio` as the
controller. **There is no ESP-IDF involved**, and no `ldproxy`: `esp-hal` ships the linker
script and `rust-toolchain.toml` pins the `esp` channel. The target is
`xtensa-esp32-none-elf`, and `core`/`alloc` are compiled from source via `build-std`.

The Xtensa toolchain still comes from `espup`, per the
[esp-rs book](https://esp-rs.github.io/book/):

```bash
# Install toolchain (once). Run this from $HOME, not from esp32/ --
# rust-toolchain.toml there pins the 'esp' channel before it exists.
cargo install espup      # or grab the prebuilt binary from the espup releases
espup install            # Xtensa Rust + Xtensa LLVM + GCC xtensa-esp-elf

# Load the environment into the shell (espup writes this file)
. $HOME/export-esp.sh

# Build
cd esp32
cargo build --release

# Flash (replace /dev/ttyUSB0 with your port)
espflash flash target/xtensa-esp32-none-elf/release/soundboard-esp32 \
  --port /dev/ttyUSB0 --monitor
```

The resulting binary is `esp32/target/xtensa-esp32-none-elf/release/soundboard-esp32`
(Xtensa ELF, ~5.4 MB unstripped with debug info). A cold release build takes roughly
7 minutes, since `build-std` recompiles `core` and `alloc` first.

> The `esp-*` and `trouble-host` versions in `esp32/Cargo.toml` are pinned to specific git
> revisions on purpose — the crates.io releases do not form a coherent set (`esp-radio`
> 0.18 speaks `bt-hci` 0.8 while `trouble-host` 0.7 needs 0.9). See the comment in that
> file before bumping anything.

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

## Project structure

```
esp32/          Rust firmware (no_std, esp-hal + esp-radio + trouble-host BLE)
android/        Android app (Kotlin, Jetpack Compose)
```
