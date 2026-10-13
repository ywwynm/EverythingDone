# Doing 整页性能与回归摘要

本目录只保留测量与状态摘要，不包含用户记事正文、图片、数据库或录像。

- `perf-fused-variant.json`：三机两样式、同输入三次滑动的原路径／整页配对。帧率来自有效驱动呈现时间；首回调时长不是光学触摸延迟。魅族 FLOW 的异常低值未删除，最终复测数值见 [分析记录](../../doing-scene-performance-2026-10-12.md)。
- `final-audit.json`：每机 17 项常规回归，保留魅族一次普通卡片看门狗。
- `closing-audit.json`：该异常的原路径一次、新路径两次复测，以及动态封面像素变化。不同加载状态不能直接比较动态帧数。
- `visual-regression.json`：三星、魅族 12 段关键录像的固定色块检查；不能据此证明所有可能像素完全一致。
- `completion-checks.json`、`publish-verification.json`：最终全量测试、默认入口及数据保护核对；公开 APK 下载验证。
- 各设备目录：实际默认入口的三项验证；原始颜色 JNI、GPU 合成与计时路径像素对照结果；恢复安装包与数据库逻辑比对。

API 34 以下沿用既有路径。OPPO 无系统录屏能力，只有截图、状态与驱动时间戳证据。完整现场数据保存在本地 `tmp/particle-doing-optimization-20261012/`，不随 APK 发布。
