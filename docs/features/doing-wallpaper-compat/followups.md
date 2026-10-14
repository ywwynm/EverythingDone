# 后续事项

## 2026-10-14

- 华为 `PCE-W30`／API 31 的启动崩溃已按用户后续授权修正：`setWallpaperTouchEventsEnabled()` 仅在 API 34 及以上调用。仍待问题设备确认启动结果，详见 `analysis-2026-10-14-huawei-launch-crash.md`。
- 如继续排查，增加可在卓易通内操作、导出的最小壁纸窗口诊断，对比原生 Activity 与 DoingActivity，区分宿主映射缺失、窗口配置差异及壁纸类型限制。本轮仅调研，尚未实施。
- 自选背景、双方向裁切及 DoingActivity 横竖屏切换已实现，见 `implementation-2026-10-14.md`。卓易通设备上的宿主壁纸自动映射诊断继续保留为独立后续事项；本轮三台验收设备均为原生 Android 环境。
