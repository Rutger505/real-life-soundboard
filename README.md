# Real Live Soundboard

A Bluetooth soundboard: press a physical button on an ESP32 and the paired Android phone plays the corresponding audio clip.

## What it is

9 physical buttons wired to an ESP32 send BLE GATT notifications to an Android app. Each button maps to an audio file you configure via the app. Press a button → hear a sound.

The Android app runs a **foreground service**, so the BLE connection and audio playback keep working while you use your phone normally, with the screen off, or with the app closed. An ongoing notification shows the connection status and the last button that was pressed.

## Hardware

- ESP32 dev board (any variant with 18+ GPIO pins)
- 9 momentary push buttons
- 1 LED (status indicator)
- Resistors: the firmware uses internal pull-ups, so no external resistors needed for buttons. Use a 220–330Ω resistor in series with the LED.

### Wiring table

| Button | GPIO | LED  | GPIO |
|--------|------|------|------|
| 1      | 4    | LED+ | 2    |
| 2      | 23   |      |      |
| 3      | 12   |      |      |
| 4      | 13   |      |      |
| 5      | 14   |      |      |
| 6      | 15   |      |      |
| 7      | 16   |      |      |
| 8      | 17   |      |      |
| 9      | 18   |      |      |

Connect each button between GPIO pin and GND. The firmware configures internal pull-ups so no external resistors are needed.

## BLE UUIDs

| Role           | UUID                                   |
|----------------|----------------------------------------|
| Service        | `12345678-1234-1234-1234-123456789012` |
| Characteristic | `12345678-1234-1234-1234-123456789abc` |

The characteristic is notify-only. When a button is pressed the ESP32 sends a 1-byte notification with the button index (0–8).

## ESP32 firmware

Requires the Rust ESP-IDF toolchain. Follow the [esp-rs book](https://esp-rs.github.io/book/) to install `espup` and the Xtensa toolchain.

```bash
# Install toolchain (once)
cargo install espup
espup install

# Source the environment
. $HOME/export-esp.sh

# Build
cd esp32
cargo build --release

# Flash (replace /dev/ttyUSB0 with your port)
espflash flash target/xtensa-esp32-espidf/release/soundboard-esp32 --port /dev/ttyUSB0 --monitor
```

## Android app

Requires Android Studio (Hedgehog or newer) and Android SDK 35.

```bash
cd android
./gradlew assembleDebug
# Or open in Android Studio and run
```

**Setup:**
1. Install the APK on your Android device (API 26+).
2. Grant Bluetooth and storage permissions when prompted.
3. The app automatically scans for the ESP32 by service UUID.
4. Tap the folder icon on each button slot to assign an audio file.
5. Press a physical button — the LED blinks and the audio plays on the phone.

## Project structure

```
esp32/          Rust firmware (std, esp-idf-hal, esp-idf-svc)
android/        Android app (Kotlin, Jetpack Compose)
```
