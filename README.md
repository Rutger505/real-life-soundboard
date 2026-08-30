# Real Live Soundboard

A Bluetooth soundboard: press a physical button on an ESP32 and the paired Android phone plays the corresponding audio clip.

## What it is

9 physical buttons wired to an ESP32 send BLE GATT notifications to an Android app. Each button maps to an audio file you configure via the app. Press a button → hear a sound.

The Android app runs a **foreground service**, so the BLE connection and audio playback keep working while you use your phone normally, with the screen off, or with the app closed. An ongoing notification shows the connection status and the last button that was pressed.

## Hardware

- ESP32 dev board (any variant with 18+ GPIO pins)
- 9 momentary push buttons
- 1 LED (status indicator)
- Resistors: the firmware uses internal pull-downs, so no external resistors needed for buttons. Use a 220–330Ω resistor in series with the LED.

### Wiring table

| Button | GPIO | LED  | GPIO |
|--------|------|------|------|
| 1      | 4    | LED+ | 2    |
| 2      | 23   |      |      |
| 3      | 25   |      |      |
| 4      | 13   |      |      |
| 5      | 14   |      |      |
| 6      | 26   |      |      |
| 7      | 16   |      |      |
| 8      | 17   |      |      |
| 9      | 18   |      |      |

Connect each button between GPIO pin and 3.3V. The firmware configures internal pull-downs (press = high) so no external resistors are needed.

## Enclosure (3D print)

`case/real-live-soundboard.scad` is the OpenSCAD source for a flat three-part
enclosure, sized to carry in a shirt pocket. The body is 61.2 x 97.3 x 15.4 mm,
with nothing protruding from it.

Everything is driven by the measured component sizes at the top of the file, so
if your battery or dev board differs, edit those and the layout follows.

| Part  | `part=`   | Print height | Holds                                                                         |
|-------|-----------|--------------|-------------------------------------------------------------------------------|
| tray  | `"tray"`  | 13.8 mm      | battery, ESP32, charger, buck-boost and switch in corner retainers; four M2 bosses; four pillars |
| lid   | `"lid"`   | 5.1 mm       | nine button pockets underneath, finger dishes on top                          |
| plate | `"plate"` | 1.2 mm       | button carrier: takes the press force down into the tray pillars              |

Inside, three bands run front to back: charger (USB-C exits the front wall) with
the buck-boost and power switch, then the ESP32, then the LiPo. The 3x3 button
grid is centred on the case, directly above the ESP32. The status LED sits in
its own socket in the front right corner, directly above the power switch.

### Export

```bash
# all three parts laid out on one bed
openscad -o soundboard.stl -D 'part="print"' case/real-live-soundboard.scad

# or one at a time
openscad -o lid.stl -D 'part="lid"' case/real-live-soundboard.scad
```

`part="assembly"` renders everything in place with the components ghosted in,
which is the view to use when checking clearances.

### Printing

No supports. Every part comes out of the script already lying flat in its print
orientation, including the lid, which is exported upside down with the pockets
facing up. PLA or PETG, 0.2 mm layers, three or four perimeters. The four pillars
are 3 mm across and 7.9 mm tall, so leave part cooling on.

Fasteners: 4x M2x8 self-tapping, into the corner bosses.

### Assembly

1. A 6x6 tactile switch has four legs, but the pair on each side is internally
   shorted, so cut one leg from each side away. Trim the two that are left to
   about 2.5 mm and tin the tips. Check with a multimeter which pins pair up;
   not every part is wired the same.
2. Push the nine switches up into the lid pockets, and the 3 mm status LED
   into its socket in the front right corner. Fit its series resistor (220-330 ohm) on the
   leg, inside the case.
3. Drop the plate over them. The legs come through the slots, and two holes
   locate on the pegs on top of the tray pillars.
4. Solder now, not earlier. With the plate on, the lid assembly lies face down
   in front of you and the leg tips stand 1.3 mm proud of the plate, in the
   open. Lay a rigid 0.6 mm tinned copper wire flat against the underside of
   the plate as the common 3.3 V rail and solder all nine to it, then one
   30 AWG signal wire per button. That is 12 wires out of the lid once the LED
   is counted.
5. Fit the modules in the tray, route the loom down one of the ~10 mm free
   strips beside the plate rather than over the board, close the lid and drive
   the four screws.

### Clearances worth knowing

- **Button height has zero slack.** Each switch is clamped between the pocket
  ceiling and the plate with 0.00 mm to spare, so print tolerance on the 7.9 mm
  pillars decides whether a button binds or rattles. Drop `pil_top` by 0.1 mm if
  yours bind.
- **The rail is live.** It runs bare, 1.3 mm above the ESP32. Put a strip of
  Kapton on the board underneath it.
- **The charger stands 1.5 mm off the floor**, on four pads. The TP4056 is a
  linear charger and burns the difference between 5 V and the cell voltage as
  heat, so at 1 A it puts close to 2 W into a 28 x 18 mm board. Spread over the
  whole case that is only about 11 degrees, but the square centimetre of floor
  directly under the chip would carry it by conduction, and PLA gives up around
  55 C. The air gap takes that path away. Dropping the charge current does more
  than the gap does: swap the module's PROG resistor for 3k (400 mA) or 5k
  (240 mA) and the heat roughly halves again.
- **The battery has 4.1 mm of slack** in a cavity sized by the button stack. Pad
  it with foam or a printed spacer or the cell will slide around.
- **Button legs must be 3 mm or shorter** (`btn_leg`), or they bottom out.
- **The LED is fully captured.** Its light hole is 2.2 mm, narrower than the
  3 mm body, so the LED cannot fall out through the top. Push it in from
  underneath before the tray goes on, and check it points the right way first —
  once it is in, the only way back out is from below.


## BLE UUIDs

| Role           | UUID                                   |
|----------------|----------------------------------------|
| Service        | `12345678-1234-1234-1234-123456789012` |
| Characteristic | `12345678-1234-1234-1234-123456789abc` |

The characteristic is notify-only. When a button is pressed (pin driven high) the ESP32 sends a 1-byte notification with the button index (0–8).

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
case/           OpenSCAD source for the printed enclosure
```
