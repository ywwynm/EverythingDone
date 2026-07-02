# 待办 — 录音波形可视化改造

## 数值微调（等用户真机反馈）

当前可测版本 code 202607020636（D23：在 D22 基础上保留自由多分量、个性化错峰和声音驱动，同时限制极端正峰尖化，让过窄过高的波峰更圆润）。

**companion 常量**（全局固定，直接改）：
- `LAYERS / COMPONENTS`：层数 / 每层波分量数。
- `INPUT_HISTORY_FRAMES / LAYER_SURGE_MAX_DELAY`：个性化错峰响应的历史帧长度与每层浪涌最大延迟。
- `mResponseDelayFrames / mResponseAttack / mResponseRelease`：每层 / 每分量的延迟、上升和回落惯性。
- `mLayerSurgeDelay / mLayerSurgeAmp / mLayerSurgeCrest / mLayerSurgeTau`：每层局部浪涌的延迟、强度、尖峰加成和衰减。
- `MIN_DB / MAX_DB`：音量→归一化区间（灵敏度）。
- `CURVE_GAMMA`：音量非线性幂曲线（>1 压低中小音量、突出大声；越大对比越极端）。
- `BAND_GAMMA / GLOBAL_LOUDNESS_MIX / BAND_MIX`：频段能量曲线，以及整体响度与频段驱动的混合比例。
- `AMP_JITTER`：每个波分量的慢速随机包络深度。
- `TAU_LEVEL`：水位缓动快慢。
- `TAU_SURGE / SURGE_AMP_BOOST / SURGE_CREST_BOOST`：瞬态浪涌的衰减、浪幅加成和波峰尖化加成。
- `IDLE_AMP`：静音微动幅度。
- `REST_FRAC / MAX_FRAC`：静息 / 最高水位（二者接近＝水位近固定；一起抬高＝整体水位更高）。
- `MAX_AMP_DP`：波浪最大振幅。
- `TOP_LIMIT_DP / SOFT_DP`：顶部软限的上界与软化尺度（浪尖最高约 `TOP_LIMIT_DP+SOFT_DP*ln2`，防糊住计时）。
- `TROUGH_FACTOR / TROUGH_SOFT`：波谷不对称压浅（越小谷越浅；1=对称正弦）。
- `CREST_FACTOR / CREST_SOFT`：波峰尖化强度与过渡尺度。
- `WANDER_DP`：基线微漂幅度。

**`AudioRecorder.VoiceAudioAnalyzer` 常量**（调音频响应）：
- `FFT_SIZE`：当前 2048；越大频率分辨率越高、响应越慢。
- `VISUAL_MIN_DB / VISUAL_MAX_DB`：RMS/分贝到 `loudness` 的归一化范围。
- `BAND_MIN_DB / BAND_MAX_DB`：FFT 频段能量到 0..1 的归一化范围。
- `BAND_ATTACK / BAND_RELEASE`：频段能量平滑的上升 / 回落速度。
- `BAND_RANGES`：`low`、`lowMid`、`mid`、`high`、`air` 的频段边界。

**构造函数 `init` 里的生成区间公式**（改这些区间可调"野度"，每实例随机生成）：
- `mAmpFactor / mLighten / mAlpha`：各层振幅尺度 / 颜色变亮量 / 透明度（线性、无随机）。
- `mK`：各分量空间频率区间（波峰疏密与参差）。
- `mTau`：各分量缓动时间常数（高频快 / 低频慢的错落）。
- `mDrift`：各分量漂移速度（横向流动快慢、正负）。
- `mPhi / mWanderPhase`：随机相位。
- `mCompWeight`：低频大浪 vs 高频细纹的占比分布。
- `mWanderSpeed`：基线微漂速度。
- `SIDE_CONTROL_SCRIM_ALPHA`（AudioRecordDialogFragment）：侧边图标衬底浓淡。

## 可能的后续

- 若用户确认效果，再考虑是否 git commit。
- 词汇表 **Voice Waveform** 若将来扩展到"已保存音频的播放波形"，需再区分范围。

### D20 回退与灵动感重做（已实现，待真机确认）

用户反馈：D20 版本野性不如上一版，整体太平稳，每个波浪都差不多；希望回到上一版，再重新优化，核心是保留随机、灵动、每个波浪像有自己的个性，并且不要所有波浪在同一时刻做出同样的上升、下降、变化。

新的调研结论：

- D20 的“主形/细纹分离 + 质量门控 + 空间平滑”解决了形状收束，但也压掉了 D19 的个性差异和不确定性。
- 更适合本需求的方向不是统一谱形，而是“群体运动里的错峰响应”：每层 / 每分量有独立 envelope follower、attack/release、延迟、overshoot 与噪声调制。
- 动画设计里的 stagger / offset / overlap 原理可以直接映射到波浪：同一个声音事件不应让所有层同时响应，而应按每层个性产生随机先后、惯性和余波。
- 有机随机不应是每帧跳变，也不应被过度质量门控；应使用连续 noise / LFO / envelope，让随机性在时间上连贯。

已按推荐方向实现：

- 回退 D20 的质量门控、严格谱式权重、主形/细纹强分离、空间平滑和 headroom 前置限幅，恢复更自由的多分量基底。
- 保留 D19 的小颤动修复：即时 `AMP_JITTER` 仍保持为连续慢速包络，`transient` 门限保留。
- 新增 `incoming → delayed target → current` 响应链；每层 / 每分量按自己的 delay / attack / release 追随音频目标。
- 不同层生成“个性参数”：响应延迟、attack/release 时间、局部浪涌强度、尖峰加成、衰减速度、漂移速度和 envelope 相位。
- 瞬态改为 layer-local surge，让某些层先起、某些层后涌，形成随机先后顺序。
- D19 的高频细纹和多分量干涉回归；只保留顶部 soft limit 和波谷压浅等局部保护，不做统一质量门控。

### 波形美感优化（已实现，待真机确认）

用户反馈：偶尔感觉波浪形状不够好看。进一步调研后，初步判断当前方案的问题不在“是否使用 Gerstner 式不对称”，而在多分量生成还偏自由随机：

- 真实/图形学水面通常围绕一个主峰频率和能量谱分布组织波，而不是让各分量频率、权重、尖峰和高频细纹独立随机。
- `mK` 当前跨度较宽，`mCompWeight` 递减较缓，多个高频分量叠加后有时会形成过密、破碎或不协调的局部轮廓。
- `CREST_FACTOR`、`MAX_AMP_DP`、顶部 soft limit 和多层 alpha 叠加会共同改变轮廓观感：浪尖过多或过尖时，水面容易显得“乱”而不是“自然”。

已按优先路线实现：

- 低频 3 个分量决定主轮廓，高频 / air 分量只按小幅 `DETAIL_AMP_SCALE` 叠加为表面细纹。
- 分量权重改为更严格的谱式递减，主形分量占绝大部分能量。
- 实例随机参数加入质量门控：过密、过碎、细纹占比过高或曲率过高会重抽。
- 波峰尖化加入 `CREST_MIN_INPUT / CREST_FULL_INPUT` 和 `STEEPNESS_CAP`，小碎峰不再都被尖化。
- 最终水面采样点做轻量空间平滑，并根据顶部 headroom 预先限制振幅。

### 小颤动问题（已修，待真机确认）

用户反馈：波浪已经上升后，随着音量和频段参数变化，浪体内部偶尔出现小颤动，观感奇怪。

初步判断不是帧循环卡顿，而是音频目标值与随机项共同造成的视觉抖动：

- `VoiceVisualizer.receive(VoiceAudioFrame)` 每 100ms 为每个波分量重新乘一次 `AMP_JITTER=0.7`，即同一声音能量下目标振幅仍可跳动 ±70%。
- `AudioRecorder.VoiceAudioAnalyzer` 的 FFT 窗口为 2048 样本（约 46ms），每 100ms 取最近一窗；高频 / air 频段和 `transient` 容易受短时噪声影响。
- `BAND_ATTACK=0.62`、高频分量 `mTau` 较短，使细纹对这些跳变反应偏快；波浪幅度已经较大时，细小目标变化会被 `MAX_AMP_DP=60` 放大。

已按优先路线修复：

- 随机抖动从“每次采样即时换目标”改成“每分量连续慢速随机包络”。
- `transient` 加 0.20 门限和 180ms 冷却，避免微小频谱 flux 反复触发浪涌。
- 高频 / air 频段权重略降，`BAND_MIX` 0.78→0.70。
- `AMP_JITTER` 0.70→0.32，把“野度”更多交给相位/频率干涉，而不是实时振幅随机。

### FFT 频段驱动（已实现，待真机调参）

`AudioRecorder` 已对 PCM 帧做轻量 FFT，并输出 5 个频段；`VoiceVisualizer` 已把低频映射到大浪、
中频映射到主体浪、高频映射到细纹。后续重点是真机判断频段归一化是否过敏或过钝。

- 若细纹过吵：降低 `air` 频段增益、提高 `BAND_GAMMA` 或降低 `BAND_MIX`。
- 若声音内容不明显：降低 `BAND_GAMMA`、提高 `BAND_MIX` 或调整 `BAND_MIN_DB/BAND_MAX_DB`。
- 若人声低频大浪太弱：提高 `low` / `lowMid` 在 `bandDrive` 中的权重。

### 物理感深化（已实现，待真机调参）

已在 `VoiceVisualizer` 中增加：

- 波峰尖化（`CREST_FACTOR / CREST_SOFT`）；
- 分量漂移速度与空间频率的近似频散关系；
- 谱式递减权重 + 少量随机；
- 瞬态浪涌时的短时额外浪幅和尖峰。

### 完整音频特征帧（已实现）

`VoiceAudioFrame` 已新增：

- `loudness`：RMS/分贝，继续控制整体浪幅和很小的水位变化；
- `low` / `lowMid` / `mid` / `high` / `air`：FFT 分频段能量，分别映射大浪、中浪、细纹与表层扰动。
- `transient`：由响度快速上升 + 频谱正向变化合成，触发短时额外浪涌。
- 兼容策略：`receive(Int)` 仍保留，会包装成只有 `loudness` 的全频段特征帧。
