# real-life-soundboard

## Versions

Bump the version with every change:

- Android: `versionCode` +1 and `versionName` (e.g. `1.0` → `1.1`) in `android/app/build.gradle.kts`.
  The app shows `versionName` plus the git commit; without a bump, builds are hard to tell apart on the phone.
- Firmware: `version` in `esp32/Cargo.toml`.
