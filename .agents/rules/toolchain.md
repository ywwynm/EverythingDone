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

## DevEco Studio 与 HarmonyOS 模拟器

- 2026-10-04 确认安装目录：`E:\software\DevEco Studio`；系统镜像根目录：`E:\harmonySDK`。
- HDC：`E:\software\DevEco Studio\sdk\default\openharmony\toolchains\hdc.exe`。先用 `list targets -v` 确认目标，再用 `-t <connectkey>`；不能把本次模拟器授权延伸至其它设备。
- 本次模拟器连接标识为 `127.0.0.1:5555`，镜像是 `system-image\HarmonyOS-7.0.0\phone_all_x86`，API 26／7.0.0.107。后续会话须重新核实目标。
- 默认 shell 的 `/system` 读取受限；安装目录中的 ext4 镜像可以用 `C:\Program Files\7-Zip\7z.exe` 只读列出并选择性提取文件，无需改挂载或改系统权限。

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

## 粒子动画离线 GPU 拟合

- 桌面渲染、缓存生成和验收仍使用 `everythingdone`。
- 需要 CUDA 的离线拟合可使用现有的
  `C:\Users\ywwynm\miniconda3\envs\spatialtuning\python.exe`；2026-09-28 实测
  PyTorch `2.11.0+cu128` 可识别 RTX 5090，包含 NumPy、SciPy、OpenCV、ModernGL。
- 此环境仅作为现成解释器使用，不修改其中的包，也不修改其它功能的代码或进程。
  `everythingdone` 当前为 CPU 版 PyTorch，不要为了本功能直接覆盖它的依赖。
