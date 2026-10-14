# Doing 滚动与首次流体优化证据

- `final-matrix-analysis.json`：最终候选 24 轮，三机 × 四卡型 × 两样式，每轮三次横拖和两次纵向滚动。
- `paired-*-analysis.json`：魅族原版、候选各 4 轮，图片／视频两样式的包间配对。
- `reports.zip` 与 `manifest.json`：上述 32 轮原始报告、手势时序、显示模式、温度及媒体状态；报告 SHA-256 对应清单。
- `stage-traces.zip` 与 `stage-trace-manifest.json`：诊断、单变量调度、程序共享、材料前移和 JNI 分段实验。它们含详细取证开销，不混入最终矩阵；归档带 `trace_metrics.py` 辅助查看。失败的方法采样不作证据。
- `device-tests.json`、`unit-tests.json`：三机数值／像素探针和 JVM 测试结果。
- `visual-summary.json`：六场画面检查、GIF 帧号及静止计时区域差异。OPPO 是约 0.5 FPS 截图序列，不能用于动画帧率判断；其它录屏也不计入性能统计。用户背景截图与影片仅留本地临时目录。
- `database-integrity.json`：真实记事及计时记录的语义摘要前后一致，排除应用正常重建的内部 HEADER 行；不包含数据库私有内容。
- `candidate.json`、`publication.json`：完整矩阵 APK 与发布版本／三机安装哈希；发布包相对候选只有更新编号资源改变。

性能复算使用 `checks/analyze_doing_refresh_rate.py`，解压到独立目录后分别指定 `--root` 与 `--expected-runs 24` 或 `4`。采集使用 `checks/measure_doing_refresh_rate.py`，需要先取得对具体设备的 ADB 授权；完整矩阵不加 `--beat-trace` 或 `--method-trace`，阶段诊断才启用详细取证。

只对驱动确认的唯一呈现时间戳计算 FPS；`firstVisibleLatencyMs` 是激活到可见回调，不是光学延迟。统计结果、样本排除、未改善场景和剩余限制见 [实施报告](../../doing-scroll-flow-optimization-2026-10-14.md)。
