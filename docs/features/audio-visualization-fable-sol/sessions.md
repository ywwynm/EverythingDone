# 会话记录 · audio-visualization-fable-sol

## 2026-07-10 物理容器改用 View 最终实测宽度

用户确认两项产品语义：PREPARED/STOPPED 持续监听并驱动水面属于设计；物理容器宽度应使用 Dialog
布局完成后 `WaveVisualizerFableSol` 的最终实测宽度，而不是 XML 的 280dp，也不是固定 320dp。

新增 `FableSolContainerGeometryTest`，先稳定复现水平跨度错误（320dp，而实测宽度为 280dp）和 30° 倾斜
跨度错误（487.128129dp，而实测宽度应为 452.487113dp），再实现修复。View 通过 `onSizeChanged(w, ...)`
传入 `w / density`；Simulation 的容器跨度、沿重力方向尺寸、体积守恒水位、墙面和注入中心改用运行时宽度；
FeatureMapper 的段落 surge 宽度改为运行时宽度的 75%。320dp 仅保留为网格采样间距和测量前回退。

修复后原始几何复现信号与新增测试均通过；完整 `:app:testDebugUnitTest`、`:app:assembleDebug` 通过，未使用 adb。
最终发布阿里云 Debug `202607101423`；首次上传的 `202607101422` 因更新说明小节不完整，已由最终版本替代。

## 2026-07-10 Python → Android 迁移审查

逐项对照实时音频特征、速度/节拍/Foote 事件、FeatureMapper、九层物理、Ambient/Hero/Optical 与 Canvas
渲染，未发现高严重度公式移植错误。使用同一段 44.1kHz、20 秒合成音频做 Python/Kotlin 差分烟测，双方均输出
1719 帧与 34 个 onset，时间戳一致；聚合 loudness/flow 的小差异来自 D3 的动态 frame rate 适配。

审查当时发现四项差异：Android 录音开始/停止不采用 Python 的 analyzer reset/time-base/gating 语义；不可见期间
feature frame 队列与动画/麦克风生命周期可能造成内存、GC 或后台占用；首版 View 实测宽度与 320dp 物理容器
不一致；核心迁移缺少 Python golden fixture 回归测试。其后录音状态被确认为设计，容器宽度已由 D8 修复。完整结论见
`migration-review-2026-07-10.md`。`./gradlew :app:testDebugUnitTest` 通过，未使用 adb。

## 2026-07-10 首次移植并接入

把桌面模拟器 `audioVisualizerSimulatorFable`（PySide6 + numpy，约 2500 行纯数学核心 + 渲染）
的**实时分析 → 九层水体物理 → 渲染**一比一移植为 Kotlin，替换录音对话框可视化（Opus 保留）。

- **新建包** `app/src/main/java/com/ywwynm/everythingdone/views/recording/fablesol/`：
  Spec/Params/FeatureFrame/Event/Color/Math/Rng/Speed/Fft、DynamicWave/WaveSets/Simulation/FeatureMapper、
  RingStat/BeatTracker/NoveltyDetector/RealtimeAnalyzer、FrameReceiver、WaveVisualizerFableSol。
- **接入**：`AudioRecorder.linkFableSol`（采集线程 PCM→float→feed→分发 frames/events）；
  `AudioRecordDialogFragment` + `fragment_record_audio.xml` 切换到 `WaveVisualizerFableSol`，
  对话框高度 360→420dp。
- **首版决策**：配色接 `ThingBackground`（纯色+渐变+8 向，见 D1）；物理最初固定 320×420dp 逻辑坐标、
  只改高度（D2，宽度部分后来由 D8 修订）；采样率复用 44100Hz（D3）；移植实时 Foote 段落检测（D4）；音频线程产帧、
  UI 线程消费的无锁线程模型（D5）。
- **编译**：`:app:assembleDebug` 通过（修掉 `FableSolPending` 可见性、补 `WaveVisualizerFableSol.onMeasure`
  固有尺寸避免撑大对话框）。
- **发布**：阿里云 debug `202607101136`，待真机验证视觉/物理/性能/重力符号/配色。

关键说明：RNG 用 `java.util.Random`（非 numpy PCG64），逐值不同但分布/层间差异一致，满足"行为
一比一"而非"逐帧像素一致"。

## 2026-07-10 修复水体透明 + 透明度闪烁

真机反馈水体一直透明且逐帧闪烁。根因：渲染复用单个 `Paint`，高光带 `setColor` 把 Paint.alpha 改小
并泄漏到下一层水面渐变填充（Android 会用 Paint.alpha 调制 shader），近层不透明主体被压成半透明、
透出浅天空 → 显透明；泄漏 alpha 随高光每帧变 → 闪烁。修复：拆 `fillPaint`（渐变填充，alpha 恒 255）
/ `bandPaint`（纯色带）。附带 View alpha 0.16→1.0 对齐 Python 不透明。发布 debug `202607101241`。
详见 `debug-updates/update-20260710201205.md`。

## 2026-07-10 诊断最近层水面比 Thing 本色浅灰

真机反馈：正常录音态下，距离屏幕最近的第 0 层水面明显比当前 Thing 背景色更浅、更灰。
通过对照 `WaveVisualizerFableSol`、原始 `audioVisualizerSimulatorFable/canvas.py` 与旧
`WaveVisualizerOpus` 的颜色链路确认：这不是 Paint alpha、View 录音态透明度或 OKLab 移植错误，
而是移植时沿用了原模拟器的 palette 语义——纯色背景会先生成一个向白混合 45% 的 `c2Base`，
第 0 层再把 `c1Base → c2Base` 画成覆盖整个水面以下区域的竖直渐变；同时 `color_breath` 与
`moodBright` 对第 0 层也继续向白混色。第 0 层自身 alpha 为 1.0，因此最终可见的主体正是这层
已经被提亮的渐变。

旧 Opus 的产品语义则是最近层直接使用 Thing 的纯色或原始渐变，只让远层提亮。建议 FableSol
恢复这一语义：纯色背景的两个基础端点都使用 `background.color`，并让空气透视/声音明度变化
随 `depth01` 生效，使第 0 层混白量恒为 0；远层、高光与物理逻辑继续保留。本轮只完成诊断，
尚未修改渲染代码。

## 2026-07-10 修复第 0 层纯色与渐变偏浅

用户确认纯色和渐变 Thing 都应让第 0 层直接保持记事颜色。新增可独立单测的
`FableSolLayerColorPolicy`：纯色基础色的 start/end 都复制 `background.color`，不再生成向白混合
45% 的第二端色；渐变继续保留原始 `color`、`endColor` 与方向。`lighten_far`、`moodBright`、
`color_breath` 的合成混白量统一乘以 `depth01`，保证第 0 层恒为 0，远层仍保留空气透视和声音明度变化。

回归测试先在旧规则下稳定出现 3 项失败，再应用修复；随后新增的 4 项颜色策略测试与完整
`:app:testDebugUnitTest` 均通过，`:app:assembleDebug` 通过。未使用 adb；视觉效果待用户通过阿里云
Debug 版本真机确认。已发布阿里云 Debug `202607101341`。
