# Toolchain paths

## ADB
- `E:\AndroidSDK\platform-tools\adb.exe`
- Not on system PATH; must invoke by absolute path.
- Sample multi-device invocation: `& 'E:\AndroidSDK\platform-tools\adb.exe' -s emulator-5554 shell ...`
  — physical device (`BYZL...`) and emulator (`emulator-5554`) coexist; always pass `-s` to disambiguate.

## Android SDK root
- `E:\AndroidSDK\`
