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
| LED+     | 2    | push-pull output, through the resistor |

GPIO2 is a strapping pin. It has to be low or floating to flash the board, and
an LED to ground leaves it that way.

### Button matrix

Every button has one leg on a row wire and the other on a column wire. The
button in row `r`, column `c` sends index `r * 3 + c` to the phone. Looking at
the top of the case with the USB-C port towards you, row 0 is the one furthest
away and column 0 is on the left:

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
case/           OpenSCAD source for the printed enclosure
```
