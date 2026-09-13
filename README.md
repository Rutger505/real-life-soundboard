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

## Enclosure (3D print)

`case/real-live-soundboard.scad` is the OpenSCAD source for a flat printed
enclosure, sized to carry in a shirt pocket. The body is 56.0 x 100.1 x 25.0 mm,
with nothing protruding from it.

Everything is driven by the measured component sizes at the top of the file, so
if your battery or dev board differs, edit those and the layout follows.

| Part  | `part=`   | Print height | Holds                                                                         |
|-------|-----------|--------------|-------------------------------------------------------------------------------|
| tray  | `"tray"`  | 23.4 mm      | battery, ESP32, charger, buck-boost and switch in ribs and corner posts; four M2 bosses |
| lid   | `"lid"`   | 6.7 mm       | a block of nine button pockets underneath, a bore per key cap on top          |
| plate | `"plate"` | 5.2 mm       | button carrier: takes the press force out to the four ESP32 corner posts      |
| caps  | `"caps"`  | 2.1 mm       | nine key caps, one per button                                                 |

Inside, three bands run front to back: charger (USB-C exits the front wall) with
the buck-boost and power switch, then the ESP32, then the LiPo. The status LED
sits in its own socket in the front right corner, directly above the power
switch.

The ESP32 is held by four posts against the side walls, one at each corner of
the board. Both pin rows are open along their whole length, so wires soldered
straight onto the board can leave sideways. The same posts carry the button
plate 8 mm above the board, which leaves room for those wires to bend up. The
plate spans the full width between the walls, so it has a 4 mm rib under its
front and back edges to keep it from bowing when you press the middle button.

The battery pocket is 1 mm wider and 3 mm longer than the cell. At both front
corners, the ones facing the ESP32, the front rib and the side rib each stop
10 mm short. The leads have somewhere to go instead of being pinched between
the cell and a wall.

### Key caps

A bare tactile switch actuator is 3.3 mm across and sat below the lid surface,
which made it hard to find and hard to press. Each button now has a printed cap
with a 7 mm top that slides in a 7.4 mm bore in the lid:

- The top has a shallow dish for your fingertip and sits 0.3 mm below the lid
  surface, inside a chamfer that leads your finger in. A flat surface pressing
  on the case, like the inside of a pocket, does not reach it.
- An 8.1 mm square flange under the lid keeps the cap from falling out. The
  flange also stops it turning.
- The flat underside of the flange rests on the actuator. The actuator stands
  0.4 mm above the button's corner nubs, and a tactile switch travels about
  0.25 mm. If you push harder than that, the flange lands on the nubs instead
  of crushing the switch.
- The recess the flange moves in steps down to the button pocket at 45
  degrees, so the lid still prints upside down without supports.

### Export

```bash
# all parts laid out on one bed
openscad -o soundboard.stl -D 'part="print"' case/real-live-soundboard.scad

# or one at a time
openscad -o lid.stl -D 'part="lid"' case/real-live-soundboard.scad
```

`part="assembly"` renders everything in place with the components ghosted in,
which is the view to use when checking clearances.

### Printing

No supports. Every part comes out of the script already lying flat in its print
orientation, including the lid, which is exported upside down with the pockets
facing up, the plate, which lies flat face down with its ribs up, and the caps,
which stand on their flanges. PLA or PETG, 0.2 mm layers, three or four
perimeters. The caps come out better at 0.12 mm layers or finer, because the
dish on top is only 0.4 mm deep.

Fasteners: 4x M2x8 self-tapping, into the corner bosses.

### Assembly

1. A 6x6 tactile switch has four legs, but the pair on each side is internally
   shorted, so cut one leg from each side away. Keep diagonal legs, so one is
   on the left at the front and one is on the right at the back. Tin the tips.
   Check with a multimeter which pins pair up; not every part is wired the
   same.
2. Lay the lid face down and drop a cap into each pocket, dish first, so its
   top goes into the bore and its flange sits against the lid. Check that each
   one slides freely before going on.
3. Push the nine switches into the pockets on top of the caps, actuator first,
   and the 3 mm status LED into its socket in the front right corner. Fit its
   series resistor (220-330 ohm) on the leg, inside the case.
4. Drop the plate over them. The legs come through the slots, and the four
   corner holes locate on the pegs on top of the corner posts.
5. Solder now, not earlier. With the plate on, the lid assembly lies face down
   in front of you and the leg tips stand 1.8 mm proud of the plate. Turn it so
   the LED socket is in the far right corner. Left is then still left, but the
   rows are reversed: row 0 is now the one nearest you. Getting this wrong only
   changes which button sends which index, and the app can reassign sounds to
   match. It does not break anything. Run one
   bare wire along each row, soldered to the three left-hand legs, and one
   insulated wire along each column, stripped where it meets the three
   right-hand legs. The column wires cross the row wires, which is why they
   need the insulation. That is 6 wires to the ESP32, 8 with the LED (GPIO18 and GND).
6. Fit the modules in the tray, solder the matrix wires to GPIO25/26/27 and
   GPIO17/16/4, close the lid and drive the four screws.

### Clearances worth knowing

- **The key stack has zero slack.** Lid, cap flange, actuator, button and plate
  all touch with 0.00 mm to spare. Print tolerance on the 15.9 mm corner posts
  and the 0.8 mm flanges decides whether a key rattles or is held half pressed.
  If a switch clicks when you screw the lid down, sand that cap's flange
  underside. If the keys rattle, raise `pil_top` by 0.1 mm.
- **The row wires run bare**, 6.4 mm above the ESP32 at the plate ribs. Keep
  them tight against the plate so they cannot sag onto the board.
- **The charger stands 1.5 mm off the floor**, on four pads. The TP4056 is a
  linear charger and burns the difference between 5 V and the cell voltage as
  heat, so at 1 A it puts close to 2 W into a 28 x 18 mm board. Spread over the
  whole case that is only about 11 degrees, but the square centimetre of floor
  directly under the chip would carry it by conduction, and PLA gives up around
  55 C. The air gap takes that path away. Dropping the charge current does more
  than the gap does: swap the module's PROG resistor for 3k (400 mA) or 5k
  (240 mA) and the heat roughly halves again.
- **The battery has 13.7 mm of height slack** in a cavity sized by the ESP32 and
  button stack. Pad it with foam or a printed spacer or the cell will rattle.
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
