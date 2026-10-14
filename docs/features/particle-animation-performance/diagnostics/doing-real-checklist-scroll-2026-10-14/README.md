# 指定清单与惯性滚动证据

测量版本为 debug `202610140919`，三星 SM-S9180 与魅族 20 Inf 各 14 轮，共 28 轮。OPPO 手机 PMA110 未连接；本目录没有其设备结果。完整结论及限制见[测量报告](../../doing-real-checklist-scroll-2026-10-14.md)。

## 文件

- `summary.json`：按设备、输入方案和阶段合并的结果；`presentedFramesPerActiveSecond` 为完整滚动时段实际呈现计数，`fps` 为相邻呈现间隔换算，不能混用。
- `baseline-analysis.json`：6 轮指定真实记事的常规反复滚动。
- `contact-compare-analysis.json`：4 轮连续按住往返与反复抬手的对照。
- `fling-analysis.json`：6 轮真实短卡，每机 72 次有效惯性。
- `fling-checklist-analysis.json`、`fling-video-analysis.json`：各 4 轮虚构长卡，每机每类 24 次有效惯性。
- `trace-analysis.json`、`fling-trace-analysis.json`：各 2 轮分段取证，含详细记录开销，不并入前述 FPS。
- `validation.json`：全部轮次的构建号、媒体播放状态、计时次数、温度采样及日志核验。
- `database-integrity.json`：真实记事及计时记录的前后语义摘要，排除内部 HEADER，没有数据库正文。
- `reports.zip`：28 份原始报告及相应的手势轨迹、设备端 UP 时间、几何、详细取证和本次工具副本；`manifest.json` 列出证据条目的 SHA-256。

不归档真实记事正文、数据库副本、UI 文本树、截图、屏幕录像或系统窗口全文。原始 UI 与数据库副本只在本地临时目录。

## 复算

解压 `reports.zip` 到独立目录，以当前功能目录下 `checks/` 的脚本分析：

```text
analyze_doing_real_checklist.py --root <解压目录>/baseline --expected-runs 6
analyze_doing_real_checklist.py --root <解压目录>/contact-compare --expected-runs 4
analyze_doing_fling.py --root <解压目录>/fling --expected-runs 6
analyze_doing_fling.py --root <解压目录>/fling-checklist --expected-runs 4 --min-gestures 12
analyze_doing_fling.py --root <解压目录>/fling-video --expected-runs 4 --min-gestures 12
analyze_doing_real_checklist_trace.py --root <解压目录>/trace
analyze_doing_real_checklist_trace.py --root <解压目录>/fling-trace
```

分析脚本仅读文件，不触碰设备。重新采集需要对具体设备的授权，并核对真实记事 ID；设备映射与本次输入参数见采集脚本及报告。`--fling` 使用归档的 `HlsGestureInput` 系统输入工具；可用 `--input-jar` 指定其已编译路径。长卡参数为 `--fixture checklist` 或 `video --fling-distance .5 --flings-per-block 6 --fling-wait 2.5`，每类两轮；详细取证改为每组 4 次、等待 2 秒并加 `--beat-trace`，单独保存。
