# 录音海浪可视化（Fable 方案）· 会话记录

## 2026-07-03 设计访谈 + 首轮实现

- grill-with-docs 访谈逐题定案 D1–D7（见 [decisions.md](decisions.md)）；
  按用户第 0 条约束，全程未读取现有方案（recording-wave-visualizer）的
  代码与文档，仅考察录音宿主（AudioRecorder 采集管线、对话框接入点）。
- 同步修订 CONTEXT.md 中 Voice Waveform 的三处取色描述：原"同色系深浅
  阶梯"规则改为"每层本色 + 透明度阶梯（越近越不透明）"。
- 完成两份联网调研（2D 海浪物理模拟与卷浪造型；Android 实时音频特征提
  取与高帧率 Canvas 渲染），结论内化于 [plan.md](plan.md) 的公式与参数。
- 新增 4 个 Kotlin 类（`views/recording/`）：`OceanWaveAudioFrameFable`、
  `OceanWaveFrameReceiverFable`、`OceanWaveAudioAnalyzerFable`、
  `OceanWaveVisualizerFable`。
- 宿主接线：`AudioRecorder` 增加 `linkFable` 旁路，新旧分析链均改为
  "有接收器才运转"的门控（旧链无人注册时不再空跑 FFT，行为不变）；
  `fragment_record_audio.xml` 与 `AudioRecordDialogFragment` 换用新 View；
  现有方案类全部保留未删。
- `:app:assembleDebug` 编译通过。落地备注：静音水位下限取 86dp（决策
  "约 84dp"的落地值，配合波谷软钳制保证任何时刻不露出 56dp 主按钮）；
  XML `android:alpha` 会在 View 父类构造期间触发 `onSetAlpha`，已在
  `applyPaintAlphas` 内防护字段未初始化的时序问题。
- 用户指出历史上自定义 View 接入该对话框时反复出现的坑：Dialog 的
  WRAP_CONTENT/AT_MOST 测量链里，默认 View（getDefaultSize）会吃满可用
  空间、把根 FrameLayout 撑高。核实本实现确有此问题后，为
  `OceanWaveVisualizerFable` 重写 `onMeasure`：非 EXACTLY 模式只上报固有
  最小尺寸，对话框尺寸交由根布局 minWidth/minHeight（280×360dp）决定，
  FrameLayout 再以 EXACTLY 对 match_parent 子项二次测量铺满。
- 已发布 debug 更新 202607030814 供真机验证（日志
  debug-updates/update-20260703161322.md）。
- 待办：用户真机目视验证（execution.md E12）。
