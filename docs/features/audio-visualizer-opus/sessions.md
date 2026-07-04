# 会话记录 — 录音波浪可视化（Opus 版）

## 2026-07-03 设计访谈（grill-with-docs）+ 首轮实现

- **调研**：七轮联网调研 + v1/v2 精读，汇总于 [research.md](research.md)。方向包括音频可视化设计
  范式（siriwave 多层正弦 + 中心钟形、lookas 弹簧-阻尼 + 非对称平滑）、水面/波浪模拟（弹簧质点
  水线、Gerstner、centripetal Catmull-Rom）、音频特征→视觉映射、法拉第波/cymatics/频谱→空间、
  "声音即水面"液化技法、Android 采集优化（UNPROCESSED + 关 AGC/NS）、稳健特征工程（PCEN/白化）。
- **v1/v2 总结**：v1 `VoiceVisualizer` = 多分量正弦合成 + 事后整形（原地放大、反复打补丁）；
  v2 `RecordingWaveVisualizer`（用户最爱）= 生成式浪包 + 196 点物理场 + 持久波三合一（方向对、
  但重且补丁多、有帧峰）。偏好排序 v2 > v1 > Fable。
- **访谈定案 D1–D13**（见 [decisions.md](decisions.md)）：核心=离散艺术化波浪群 + 轻弹簧底
  （收窄 D1）；共享场 + 前景层内扰动；全新 Opus 分析器；融合而非装饰；声音驱动浪的数量/大小/
  分层；采集改 UNPROCESSED + 关音效 + mono；层级=深度层 + 尺度倾向 + 音色分配；数量明显但克制；
  出生=离散事件 + 持续驱动 + 主副浪；每浪有方向整体滚动横移；主体水体纯本色不透明；Opus 直接生效。
  过程中用户两次重要澄清：① 主要通过"波浪"（离散、分层、可数）而非水面纹理反映声音，不过度模拟
  现实；② 主体水体明度/透明度皆为 1，避免完全激活时偏暗偏脏。均已落入 decisions/preferences。
- **实现 E1–E7**（见 [execution.md](execution.md)）：新增 5 个 Kotlin 类（`WaveAudioFrameOpus`、
  `WaveDriveFrameOpus`、`WaveFrameReceiverOpus`、`WaveAudioAnalyzerOpus`、`WaveVisualizerOpus`），
  改造 `AudioRecorder`（采集源/单声道/关音效/`linkOpus`）、`AudioRecordDialogFragment`、
  `fragment_record_audio.xml`。v1/v2/Fable 全部保留不删。`:app:assembleDebug` 通过，APK 20.8MB。
- **文档**：新建 `docs/features/audio-visualizer-opus/`（decisions/preferences/research/plan/
  execution/sessions）；更新 `CONTEXT.md` 的 **Voice Waveform** 词条（主体纯本色 + 上层明度/透明度
  阶梯，纠正 Fable 那轮改的"仅透明度"）；更新 `recording-wave-visualizer/preferences.md`（版本排序）。
- **待办**：E9 用户真机目视验证 + 可调参迭代（plan.md 第十节；execution.md 列出首轮简化点）。

## 2026-07-03 晚–07-04 真机调优批次（视觉层）

多轮真机反馈迭代，逐条落在 [debug-updates](debug-updates/)（含 debug code）与 decisions D17–D20：
层次均衡与"浅色后层窜高/深色前景遮死"的反复（D17 解耦"丰富度⟂主次"）→ 整体单向流动 + 浪包横穿
移出（D18 消驻波干涉）→ 六层角色分工"近层平静·偶爆发、远层细密·频繁"（D19）→ 大胆加大层间基线差
（相邻 9.6dp，平静时也能看清 6 层）→ 稳态空调底噪抑制（D20，音调性缩放 absoluteLevel）。

## 2026-07-04 响度"半绝对"重构（grill-me + 6 份调研 + 实现 + 发布）

- **触发**：用户提"把声音特征记录下来做相对比较"的想法，因为正常/小声说话跟大声放歌动画差别不大、
  甚至前者更显著。
- **grill-me 收敛**：诊断出根因不是"缺历史"，而是**自适应归一化（floor+peak 都自适应）抹平大小声** +
  **MIC 保留 AGC 压动态**（D20 削 absoluteLevel 又加剧）。共识=**半绝对**：自适应零点 + 固定尺子。逐个
  钉定：目标（半绝对）、骨架（最小机制、不建历史）、零点（信号门控自适应底）、量程/死区（45/5dB）、
  曲线（S 曲线强区分）、录音（分步暂不动）。见 [decisions.md](decisions.md) D21。
- **调研**：6 份 web 调研（声学 dBFS/SPL、心理声学、自适应 metering/gate/VAD、Android MIC/AGC），存
  [research.md](research.md) 第 5 节，全面印证 45dB 量程、fast-down/slow-up 零点、AGC 是元凶、未校准
  拿不到绝对 SPL（半绝对是唯一正解）。
- **实现**：重构 `WaveAudioAnalyzerOpus` 响度链（新 `semiAbsLevel`、信号门控 `mFloorDb`、删 `mPeakDb`/
  `relativeLevel`/`absoluteLevel`/`absAssist`、`fastLevel→fastDbFs`、常量 `RANGE_DB=45`/`DEADZONE_DB=5`）；
  `AudioRecorder` MIC 也 `disablePreprocessing` + `getEnabled` 复核 + DEBUG log（修 `:141` 漏洞）。编译通过，
  发布 debug code **202607031730**。
- **提交**：首轮 Opus 功能整体提交 `85f1aa28`（31 文件；排除 Everything-Android 与临时日志）。
- **待验证**：真机看大声 vs 小声是否明显拉开、空调是否不激活、各机型 AGC 能否关（`logcat` 看 `preproc=`）；
  录音文件音量分步处理（暂不动，真机若变小再单独加保存前增益归一）。

## 2026-07-04 v5 之后第二轮调研优化（D23，五条落地）

- **触发**：用户在 v5（`78f9f9fe`）稳定后要求"分析当前实现 + 联网充分调研还能怎么优化"，先只出分析与
  建议（不改文件），再要求"改一下 1-5"。
- **调研**：新一轮 web 调研（避开前 7 轮已覆盖的弹簧水线/Gerstner/Catmull-Rom/cymatics/PCEN/半绝对响度），
  聚焦 AGSL/RuntimeShader、Gerstner 着色（fresnel/specular/深浅色）、SuperFlux（Böck DAFx-13）、LUFS/
  K 加权（BS.1770）、相量递推、Canvas drawPath 性能、domain warping（iq）。
- **落地五条**（见 [decisions.md](decisions.md) D23）：
  1. 建议1 竖直深度渐变着色（纯色记事，只提亮不压暗，守 D12；渐变记事保横向）。
  2. 建议2 K 加权响度（BS.1770 two-stage biquad，`ingest` 连续滤波到 `mKRing`，`dbFs`/`fastDbFs` 改用）。
  3. 建议3 浪包按层分桶 + 基础波场相量递推（性能，像素不变；drawVertices 不做）。
  4. 建议4 SuperFlux 频域最大值滤波（抑制颤音虚假 onset）。
  5. 建议5 分量权重缓慢时变起伏去机械感（与相量递推兼容、不碰流向，取代会冲突的空间 domain warping）。
- **未采纳**：建议6 AGSL（minSdk 26 vs API 33，单独立项）；建议7 tempo 锁（抖动回退风险）。
- **构建**：`WaveAudioAnalyzerOpus` + `WaveVisualizerOpus` 改动，`:app:assembleDebug` 两次分批通过。未发布
  debug（用户未要求）。
- **真机复校清单**（重点看 K 加权是否改变标定）：
  1. **大小声戏剧性**是否保持/更好（K 加权后 dbFs 刻度可能微移；若变弱调 `RANGE_DB`，若空调又激活调 `DEADZONE_DB`/floor 门控）；
  2. **纯色记事**是否有了竖直体积感、主体是否仍纯净不脏（`CREST_LIGHTEN_*` 可调；渐变记事应保持横向方向）；
  3. **唱歌/弦乐**的 onset 是否更干净、不再一串虚假浪（`SUPERFLUX_MAXFILTER_BINS` 可调）；
  4. **去机械感**是否自然、有没有引入不想要的形状抖动或"晃动"（`WOBBLE_AMP`/`WOBBLE_K_*` 可调）；
  5. 高能量场景**帧稳定性**（卡顿）是否改善（分桶 + 相量递推的目的）。
