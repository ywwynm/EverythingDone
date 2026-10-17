# 大视频与后台恢复复测

这里保存 2026-10-17 实机验证使用的脚本。只在已明确获准操作对应设备时执行；脚本限定隔离包 `com.ywwynm.everythingdone.covervalidation`，不操作正式包数据。三台设备的序列号、ADB 和 FFmpeg 路径记录在脚本中，复测前按本次授权范围核对。

将本目录脚本复制到仓库 `tmp/cover-recovery/`，从仓库根目录运行。大视频、APK、原始事件、GIF、日志和截图均留在这个临时目录；精简验收材料位于功能文档的 `checks/background-2026-10-17/`。

## 构建夹具

使用 FFmpeg 创建 `tmp/cover-recovery/complex-4k.mp4`：H.264、3840×2160、30 fps、24 秒、高运动复杂度和细噪声，约 109 MB。复现命令如下，文件内容不涉及真实用户媒体。

```powershell
& C:\ffmpeg\bin\ffmpeg.exe -f lavfi -i 'testsrc2=size=3840x2160:rate=30:duration=24' -vf 'noise=alls=6:allf=t' -c:v libx264 -preset fast -b:v 36M -maxrate 42M -bufsize 72M -g 120 -pix_fmt yuv420p -an -y tmp/cover-recovery/complex-4k.mp4
.\tmp\cover-recovery\prepare.ps1
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug -I tmp/cover-recovery/clone.init.gradle --console=plain
Copy-Item app/build/outputs/apk/debug/app-debug.apk tmp/cover-recovery/wm2105-isolated.apk
```

隔离清单仅在临时目录生成，调试入口只对隔离 APK 导出。先安装该 APK，并将夹具推送到每台授权设备的 `/sdcard/Android/data/com.ywwynm.everythingdone.covervalidation/files/complex-4k.mp4`。`pipeline_v2.py` 会重复安装同一隔离 APK，但不自动创建最初的媒体夹具。

## 执行与判定

```powershell
& C:\Users\ywwynm\miniconda3\envs\everythingdone\python.exe tmp/cover-recovery/pipeline_v2.py
& C:\Users\ywwynm\miniconda3\envs\everythingdone\python.exe tmp/cover-recovery/summarize_v2.py
```

- 每次改变隔离夹具的修改时间，确保对同一个 12 秒起始帧重新生成；不是命中旧 GIF 缓存。
- 每机依次执行：生成时回收自身进程并后台停留 180 秒；生成时与系统设置往返 12 次再后台停留 180 秒；进入原生集中编码后取消、立即显式重试并后台停留 60 秒；原有 72 项卡片与生成回归。
- 后台只打开系统设置，每 10 秒移动一次焦点，避免自动熄屏，不激活设置项、不改系统配置、不主动锁屏。兼容性提示只点“确定”，不更改“不再显示”。
- `CoverRecoveryProbeActivity` 使用真实卡片、真实 WorkManager 和真实编码器，记录运行 PID、任务轮次、状态、数量行、GIF 是否正在播放。取消操作仅针对隔离包的视频封面任务。
- 除 READY 外，还检查新发生的应用崩溃、各轮进度、实际 GIF 的 720×405、75 帧和 3 秒，以及最终数量行和播放状态。`pipeline_v2.py` 任一场景异常即返回非零；最后执行汇总脚本才说明所有预期证据都齐全。
- 完成后卸载三台隔离包，清除隔离夹具及服务；不要删除或重置正式包。

文件夹前缀 `wm2105` 标识修复 WorkManager 2.9.1 超时崩溃后重新执行的最终轮次。此前 `death-green` 虽然最后成功，但有中途崩溃，不属于通过证据。
