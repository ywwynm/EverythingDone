# Android 平台迁移后续事项

## 2026-10-15 — 核查当前 APK 的 16 KB 原生库对齐

- 在 OPPO PLZ110（`3B1629006YC00000`）验证详情顶部按钮时，系统弹出 Android 应用兼容性提示，报告 APK 不符合 16 KB 对齐要求；可见条目包括 `lib/arm64-v8a/libandroidndkgif.so` 的 LOAD 区段未对齐，以及 `libsurface_util_jni.so` 检查错误。
- 这是当前设备提示的事实，尚未逐库检查 ELF 与 APK 对齐，不能把“未知错误”直接解释成确定的 ELF 根因。旧迁移计划中的“没有原生 .so，16 KB 风险低”结论不再能描述当前构建。
- 后续单独核查所有打包原生库的来源、ELF LOAD 对齐和 APK 打包对齐，确认后再调整 NDK/依赖或链接参数。本轮顶部按钮改动不涉及这些原生库，也未修复此项。
- 证据位置：`tmp/detail-toolbar/toolbar-first/3B1629006YC00000/` 的首轮截图。关闭本次提示以继续视觉验证，不修改“不再显示”设置。
