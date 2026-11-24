# real-life-soundboard: agent notes

9 physical buttons → BLE GATT notify → Android foreground service plays the mapped clip. Hardware, pins, BLE, battery, build/flash steps: see README.

- `nrf52/`: **current** firmware, Seeed XIAO nRF52840, Rust no_std (Embassy, embassy-nrf 0.11, nrf-sdc 0.4 SoftDevice Controller, trouble-host 0.8), target `thumbv7em-none-eabihf`, stable toolchain.
- `esp32/`: ESP32 firmware (ESP-IDF std), **retired**: don't build or change unless Rutger revives it.
- `android/`: Kotlin/Compose app `com.rutger.soundboard`, minSdk 26, target/compile 35, Gradle 9.5, JDK 17.
- `*/case/real-live-soundboard.scad` ("live", not "life"): OpenSCAD case per board.

## Rules

- **Main only**: commit + push to main, no PRs.
- **Bump versions with every change**:
  - Android: `versionCode` +1 and `versionName` (e.g. `1.10` → `1.11`) in `android/app/build.gradle.kts`. The build appends the git commit; without a bump, builds are hard to tell apart on the phone.
  - Firmware: `version` in `nrf52/Cargo.toml` (or `esp32/Cargo.toml`).
- **BLE contract stable**: service `12345678-1234-1234-1234-123456789012`, button `…9abc` (read/notify, 1 byte index), battery raw `…9abd` (3 bytes), Battery Service `0x180F` / Level `0x2A19`. Both firmwares share it; the app works with either.
- Button index = `n - 1` (nRF52, one pin per button) / `r * 3 + c` (ESP32 matrix), same physical layout.
- **README is authoritative for pins**: check it (and `nrf52/src/main.rs`) before changing any.

## nRF52 pitfalls

- Build needs `arm-none-eabi-newlib` (bindgen sysroot `/usr/arm-none-eabi`, `nrf52/.cargo/config.toml`).
- Flash: `cargo run --release` (runner `probe-rs run --chip nRF52840_xxAA`, defmt over RTT) via SWD probe (Pico debugprobe). Linked at 0x0, no UF2 bootloader; first flash needs `probe-rs erase --chip nRF52840_xxAA --allow-erase-all`.
- **P0.14 (battery divider enable) must stay low**: high puts P0.31 over its max voltage.
- Measure idle current with the probe unplugged (it keeps debug logic powered).

## Case

- Parametric `[x,y,z]` vectors at top, then derived values + `assert`s. `part`: assembly|open|tray|lid|plate|print. Style follows the motorized-blinds remote case.
- **Verify every change by rendering** (echoes dims, trips asserts); lid takes minutes:
  `for p in tray lid plate; do openscad -o /tmp/$p.stl -D "part=\"$p\"" real-live-soundboard.scad; done`
  Quick dims/assert check: `-D 'part="none"'` (no geometry). Expected body: esp32 56 x 87.4 x 23.2 mm, nrf52 47.6 x 56 x 18.1 mm.
- Interference check: `-D 'part="none"'`, `intersection()` of `tray()`/`plate()`/`lid()` with solid mocks; empty object = no clash.
- Add an `assert()` for each layout invariant you rely on. Headless PNG preview doesn't work.
- `esp32/case/real-live-soundboard.sc` (SolidCraft) mirrors the ESP32 `.scad` 1:1: keep in step when that case changes.
