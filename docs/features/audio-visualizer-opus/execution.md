# 执行清单 — 录音波浪可视化（Opus 版）

依据 [plan.md](plan.md)。状态：`[ ]` 待做 / `[x]` 完成 / `[~]` 进行中。自底向上实现。

## 2026-07-03 首轮实现

- [x] E1 帧与接口：`WaveAudioFrameOpus.kt`（客观特征）、`WaveDriveFrameOpus.kt`
      （语义驱动，内含 raw feature）、`WaveFrameReceiverOpus.kt`（`fun interface`）
- [x] E2 `WaveAudioAnalyzerOpus.kt`：预加重 + FFT2048 + 5 宏频段自适应 floor/peak 归一
      + 逐 bin 自适应白化 flux→onset + 归一化质心 + 平坦度门控 + event-density→pace
      + YIN pitch（隔帧）+ 语义映射（自适应对比/门控/attack-release）→ `WaveDriveFrameOpus`
- [x] E3 `AudioRecorder.kt`：采集改 UNPROCESSED（回退 VOICE_RECOGNITION）+ 关 AGC/NS/AEC
      + 单声道 + WAV 头改 mono；加 `linkOpus` + Opus 分析器旁路（有接收器才运行）；
      构造函数接受 Context（检测 UNPROCESSED 支持）
- [x] E4 `WaveVisualizerOpus.kt`：离散波浪群（高斯非对称波峰、色散传播、rise/travel/decay
      生命周期）+ 轻弹簧底（活水 + 浪间涟漪）+ 6 深度层视差 + centripetal Catmull-Rom 路径
      + 颜色（主体纯本色不透明、上层更亮更透）+ 水位 + 生成门控 + onMeasure + 自驱帧循环
      （可见性/焦点恢复）。view alpha 交给框架离屏合成，未拦截 setAlpha（避免 Fable 的初始化时序坑）
- [x] E5 `fragment_record_audio.xml`：View 类换成 `WaveVisualizerOpus`（id 不变）
- [x] E6 `AudioRecordDialogFragment.kt`：字段类型改 Opus、`linkOpus`、`AudioRecorder(mActivity)`
- [x] E7 编译 `:app:assembleDebug` 通过（app-debug.apk 20.8MB 已产出，无 Opus 警告）
- [x] E8 文档收尾：decisions/preferences/research/plan/execution + CONTEXT.md 词条
- [ ] E9 用户真机目视验证 + 可调参迭代（plan.md 第十节）

## 2026-07-04 第二轮调研优化（D23，五条）

- [x] E10 建议2 K 加权响度：`WaveAudioAnalyzerOpus` 加两级 BS.1770 biquad（`designKWeighting` 按
      sampleRate 由 RBJ cookbook 生成），`ingest` 连续滤波到 `mKRing`，组帧 `sumSq` 与 `fastDbFs` 改用 K 加权
- [x] E11 建议4 SuperFlux：flux 参考帧沿频率轴 ±2 bins 最大值 + 双缓冲 `mCurWhitened` 帧末拷回
- [x] E12 建议1 竖直深度渐变：`rebuildPaints` 纯色记事各层竖直提亮渐变（只提亮不压暗，守 D12），
      渐变记事保持横向 orientation
- [x] E13 建议3 性能：`drawWater` 浪包按层分桶预筛选（`mLayerPacketScratch`）+ 基础波场相量递推
      （预计算 `mCosDx/mSinDx`，删 `baseFieldNorm`）；drawVertices 不做（后续实验）
- [x] E14 建议5 去机械感：分量权重缓慢时变起伏（`WOBBLE_*`，兼容相量递推、不碰流向）
- [x] E15 编译 `:app:assembleDebug` 通过（分两批：音频层、视觉层）
- [ ] E16 用户真机复校（见 sessions.md 清单；重点 K 加权是否需重调 `RANGE_DB`/`DEADZONE_DB`）
- 未采纳：建议6 AGSL（minSdk 26 vs API 33）、建议7 tempo 锁（抖动风险）

## 首轮已知待调/简化点（真机后处理）

- YIN 的 CMND 只在 [tau_min, tau_max] 区间累加分母，pitch 精度有折扣；若音高映射不稳再补全或换法。
- 体积/光泽暂只做"主体纯本色 + 上层更亮更透"，未做按高度/斜率提亮波峰（D12 的次要项），
  真机看层次是否足够再决定是否加（仍不加独立图层）。
- 弹簧邻居扩散用了简化双缓冲，仅作轻量活水，未严格守恒；如活水观感不佳再规整。
- 大量可调参（弹簧手感、波包区间、生成门槛、色散常数、水位、层阶梯、PCEN/归一窗）待真机校准。
