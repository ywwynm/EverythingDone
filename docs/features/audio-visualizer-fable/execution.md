# 录音海浪可视化（Fable 方案）· 执行清单

依据 [plan.md](plan.md)。状态：`[ ]` 待做 / `[x]` 完成 / `[~]` 进行中。

## 2026-07-03 首轮实现

- [x] E1 `OceanWaveAudioFrameFable.kt`：帧数据类（loudness / transient /
      pitchHz / pitchConfidence / voiced / syllableRate）+ SILENCE
- [x] E2 `OceanWaveFrameReceiverFable.kt`：接收器接口
- [x] E3 `OceanWaveAudioAnalyzerFable.kt`：环形缓冲 + 半采样 YIN（隔帧）
      + RMS 响度 + 瞬态 + 音节率
- [x] E4 `OceanWaveVisualizerFable.kt`：7 层波场 + 波包 + 巨浪 + 粒子 +
      帧循环 + 颜色（PURE/GRADIENT）+ onSetAlpha 吸收
- [x] E5 `AudioRecorder.kt`：linkFable + 旁路分析器 + 新旧链按接收器门控
- [x] E6 `fragment_record_audio.xml`：View 类替换（id 不变）
- [x] E7 `AudioRecordDialogFragment.kt`：字段类型 + linkFable
- [x] E8 编译 `:app:assembleDebug` 通过（app-debug.apk 已产出）
- [x] E9 文档收尾：decisions / sessions / CONTEXT.md 均已更新
- [x] E10 修复对话框测量问题：View 不重写 onMeasure 时默认吃满 Dialog 的
      WRAP_CONTENT/AT_MOST 可用空间、撑大根 FrameLayout；现改为非 EXACTLY
      模式只上报固有最小尺寸，对话框保持 280×360dp（由根布局
      minWidth/minHeight 决定，FrameLayout 二次测量铺满本 View）
- [x] E11 发布 debug 更新 202607030814（日志
      debug-updates/update-20260703161322.md）
- [ ] E12 用户真机目视验证（依据 plan.md 第 9 节清单 + 对话框尺寸）
