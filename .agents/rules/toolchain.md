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

## FableSol Python 模拟器

- 仓库：`E:\projects\audioVisualizerSimulatorFable`。
- 所有运行、测试和离屏渲染统一使用 Conda 环境 `everythingdone`；不要使用系统 `python` 或
  Codex bundled Python。
- Windows 下优先直接调用该环境解释器，避免 `conda run` 在中文输出上触发 GBK 编码错误：
  ```powershell
  & "C:\Users\ywwynm\miniconda3\envs\everythingdone\python.exe" <脚本或参数>
  ```
- `conda run -n everythingdone python ...` 只在子进程输出不含异常编码字符时作为后备。
