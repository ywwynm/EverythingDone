# Doing 三机刷新率测量证据（2026-10-14）

结论、范围和排除项见 [测量报告](../../doing-refresh-rate-2026-10-14.md)。

- `text-analysis.json`：纯文本 6 轮。
- `complex-analysis.json`：图片、视频 GIF 封面、90 项三级清单，共 18 轮。
- `video-recheck-analysis.json`：魅族视频流体首次启动异常的定向复核 1 轮，单独保留。
- `reports.zip`：25 轮完整报告、手势几何和时间、探针完成输出及媒体状态；不包含用户数据库、系统窗口清单、截图或录像。
- `manifest.json`：报告与设备对应、事件数量、SHA-256，均为 `business_default` 整页绘制策略。
- `measurement-apk.json`：复杂样本使用的本地调试 APK 信息。报告中的 build `0` 是普通本地 `assembleDebug`，不是已发布版本；生产源码仍为 `9c9e51dd8`，只扩展两个 debug 探针。

实际 FPS 仅从驱动呈现时间戳计算，不能用提交回调、`later_frame_presented`、超时估计替代。全部原始日志溢出计数为 0。少量未真正呈现的提交保留在报告中，但不计入 FPS。

## 复现

1. 使用 [measure_doing_refresh_rate.py](../../checks/measure_doing_refresh_rate.py)；只针对本次三台明确授权设备。默认虚构长文本，`--fixtures image video checklist` 扩展复杂样本，`--devices` 可限定设备，`--styles` 可限定样式。
2. 复杂样本媒体放到应用外部 files 的 `doing-refresh-fixtures/image.png`、`video.mp4`。本轮合成图像由 ffmpeg 的 `testsrc2=size=2048x1536:rate=1` 输出一帧 PNG；视频由 `testsrc2=size=1920x1080:rate=30` 输出 6 秒 H.264（libx264、ultrafast、CRF 20、yuv420p）。视频 GIF 必须由实际应用生成，脚本会等 `derivedPreviewReady`、GIF 运行状态及帧号变化。
3. [analyze_doing_refresh_rate.py](../../checks/analyze_doing_refresh_rate.py) 接受 `--root` 和 `--expected-runs`。缺失、溢出、失败、不完整手势直接报错；未完成样本排除，不通过减少有效样本来伪造完整矩阵。

原始系统状态与未完成样本留在本地 `tmp/doing-refresh-rate-20261014/`、`tmp/doing-refresh-complex-20261014/`；复核在 `tmp/doing-refresh-video-recheck-20261014/`。
