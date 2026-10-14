# Doing 三机惯性滚动修复证据

- `summary.json`：12 轮复杂卡 fling、2 轮真实卡 fling、24 轮粒子矩阵、3 轮内容位移与退出、6 轮画面、2 轮 OPPO 基线及 6 轮归因探针的解析结果；包含 JVM／设备检查与真实数据摘要。
- `reports.zip`、`manifest.json`：上述 55 份有效报告和对应输入、媒体、温度及详细时序，清单提供逐文件大小和 SHA-256。失败／未开始的测量不混入。
- `perfetto-slice-example.json`、`perfetto-config.pbtxt`：魅族重复帧长间隔的局部切片和采集配置。完整 trace 的本地位置与哈希见 `local-large-artifacts.json`。
- `publication.json`：公开版本、APK 哈希、三机安装校验与资源差异。
- `local-large-artifacts.json`：画面录像、完整 Perfetto 的本地位置与哈希；图像含用户背景，仅保留本地，不把录像帧数作为性能证据。

`summary.json` 中 `fling.presentedFramesPerActiveSecond` 是主结果：完整 SETTLING 窗口内唯一真实驱动呈现帧数除以整个窗口时长，保留开头、结尾和停顿。`fps` 是相邻呈现间隔换算，不能在存在长停顿的旧版本中替代主结果。实际 ACTION_UP 与状态切换一一配对，纵向位移必须有效。详细时序和画面录像不混入低开销性能表。

将 reports.zip 解压到独立目录后复算（脚本位于本功能的 `checks/`）：

```text
python analyze_doing_fling.py --root <解压目录>/final-fling-checklist --expected-runs 3 --min-gestures 12
python analyze_doing_fling.py --root <解压目录>/final-real-fling --expected-runs 2 --min-gestures 12
python analyze_doing_refresh_rate.py --root <解压目录>/final-animation-matrix --expected-runs 24
python analyze_doing_content_motion.py --root <解压目录>/final-motion-trace
python analyze_doing_real_checklist_trace.py --root <解压目录>/async-trace --expected-runs 1
```

其余三类复杂卡把第一条路径中的 checklist 换成 video／image／text。细分链路分析兼容旧／新路径，历史字段 `wrapBitmapMs` 在新路径表示获取 HardwareBuffer 句柄，`recycleMs` 表示关闭 Java 句柄。内容偏移取松手后 100–600ms，避开尾端不足一像素的自然降速；捕获率不是物理屏幕帧率。结果与限制见[完整报告](../../doing-fling-fix-2026-10-14.md)。
