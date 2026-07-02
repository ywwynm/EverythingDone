# 执行清单 — 录音波形可视化改造

按 [plan.md](plan.md) 执行。完成一项勾一项。

## 编码

- [x] 1. 重写 `VoiceVisualizer.kt`：帧循环 + 缓动目标 + 4 层水体波浪绘制；
      `receive` 只更新 `volatile` 目标；新增 `setThingBackground(bg)`；数值集中为顶部常量。
- [x] 2. 改 `fragment_record_audio.xml`：单一根 FrameLayout，`VoiceVisualizer` 铺满下部；
      计时/文件名/按钮叠加其上；删除 `view_voice_visualizer_base`。
- [x] 3. 改 `AudioRecordDialogFragment.kt`：移除 `mBase` 相关；改用 `setThingBackground`；
      给两个侧边图标加圆形半透明衬底（背景）+ 涟漪保持前景；收敛状态 alpha 动画到 `mVisualizer`。
- [x] 4. `attrs.xml`：无需改动（声明保留，XML 已移除 `numColumns/renderRange` 用法）。

## 验证

- [x] 5. `:app:assembleDebug` 编译通过，产出 `app-debug.apk`。
- [x] 6. 无新增编译 error / warning（`BUILD SUCCESSFUL`）。

## 发布

- [x] 7. 新建 `debug-updates/update-20260702012413.md`（首个 `## ` 段为发布说明，中文）。
- [x] 8. `:app:publishDebugUpdate` 发布到阿里云 debug 通道，code `202607011725`。
- [x] 9. 记录到 [sessions.md](sessions.md)；等用户真机反馈后微调数值（见 [followups.md](followups.md)）。

## 注意

- 未 `git commit`（等用户测试确认后再按需提交）。
- 未自动安装到物理设备。
- 数值按用户真机反馈迭代，集中在 `VoiceVisualizer` 顶部常量。
