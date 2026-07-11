# 偏好 · audio-visualization-fable-sol

## 发布

本功能的迭代改动：改完代码、`:app:assembleDebug` 编译通过后**直接发布阿里云 debug**
（建 `debug-updates/update-*.md` 日志 + `:app:publishDebugUpdate` 传入），不再单独询问是否发布。
与全局 preferences 的发布规则一致（2026-07-10 用户就本功能再次确认）。

## 重力与屏幕方向

FableSol 的手机重力行为应与迁移前的 Opus 保持一致：完全倒置时水体来到录音 Dialog 顶部；
录音 Dialog 存续期间禁止宿主 Activity 自动旋转，关闭后恢复打开前的方向策略（2026-07-10）。

## Python 运行环境

分析、测试和运行 `audioVisualizerSimulatorFable` 时使用 Conda 环境 `everythingdone`（2026-07-10）。

## 浪形连续性

已经塑形完成的浪不得因音头或快速音频参数在短时间内生硬改变自身形状；只能依据物理传播、重力、
阻尼、边界，或显式进入的新能量/浪涌自然变化。该要求同时适用于 Python 蓝本和 Android 移植
（2026-07-10）。
