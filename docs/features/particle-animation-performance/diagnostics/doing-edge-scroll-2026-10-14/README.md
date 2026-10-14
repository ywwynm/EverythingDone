# Doing 底部回弹与急停证据

对应[诊断与最终结果](../../doing-edge-scroll-2026-10-14.md)。只操作已授权的三星、魅族、OPPO 手机；真实记事只读，测试计时使用隔离服务。

- `reports.zip`：37 份有效场景报告，包含旧版 OPPO 四类基线、最终三机五卡型边界 15 轮、四卡型 fling 12 轮及两种粒子画面六轮。按白名单保留报告、必要状态几何、时间线、输入时刻、阶段轨迹、像素变化摘要和 instrumentation 结果；无私人截图、录像或数据库。
- `manifest.json`：归档各文件的大小与 SHA-256。
- `summary.json`：同口径边界间隔、完整惯性时段 FPS、真实急停状态、回调契约、动画像素检查、单元测试、数据完整性与候选源码／APK 哈希。
- `local-videos.json`：六段本地录像的位置和哈希；录像仅作画面检查，不能拿录屏 FPS 代替实际呈现率。
- `publication.json`：发布完成后记录公开包、本地包、安装包哈希及相对已测候选的资源差异。

复测脚本位于 `../../checks/measure_doing_edges.py`、`analyze_doing_edges.py`、`measure_doing_real_checklist.py` 和 `analyze_doing_fling.py`。边界脚本要求输入驱动 JAR，支持显式输出路径；设备端 `HlsGestureInput.java` 同进程记录 UP、40ms 后 DOWN 与 CANCEL，保证急停确实发生在惯性期间。分析逐轮断言到底部、边缘效果活动及按下前仍在 SETTLING，详细探针和低开销 FPS 分开运行。

最终 30 次撞底、15 次急停均有效，无状态窗口缺失；96 次惯性滚动与六轮画面检查无失败／超时／中止事件，日志无溢出。1496 项 JVM 测试零失败、一项跳过。三星 374、魅族 181、OPPO 113 条真实记事与各自 2／2／52 条计时记录语义摘要完全相同。

中间失败候选和 ADB 传输中断保留在本地实验目录及诊断正文，不混入最终通过统计。最终像素／录像结果只代表三台 Android 手机，不替代卓易通的实际屏幕验收。
