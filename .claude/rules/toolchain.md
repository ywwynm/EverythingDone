# Toolchain paths

## ADB
- `E:\AndroidSDK\platform-tools\adb.exe`
- Not on system `PATH`; must invoke by absolute path.
- A physical device (`BYZL...`) and an emulator (`emulator-5554`) coexist —
  always pass `-s <serial>` to disambiguate.
- Standard invocation pattern (PowerShell):
  ```powershell
  $adb = "E:\AndroidSDK\platform-tools\adb.exe"
  & $adb -s emulator-5554 shell ...
  ```

## Android SDK root
- `E:\AndroidSDK\`

## Gradle wrapper
- `E:\projects\EverythingDone\gradlew.bat`
- Not on `PATH`; must invoke by absolute path or after `cd` into the repo root.
- See [gradle.md](gradle.md) for invocation patterns.
