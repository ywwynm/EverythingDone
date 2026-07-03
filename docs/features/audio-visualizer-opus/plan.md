# 实施方案 — 录音波浪可视化（Opus 版）

汇编 D1–D13 为可执行方案。目标：最高视觉质量，靠**声音对波浪群的深度塑造**实现"自然/美观/
丰富/流畅"，不加装饰元素。现有 v1/v2/Fable 代码全部保留不删。类/目录以 `Opus` 结尾。

## 一、文件与类（`views/recording/`，Opus 后缀）

| 文件 | 职责 |
|---|---|
| `WaveAudioFrameOpus.kt` | 客观特征帧（data class） |
| `WaveDriveFrameOpus.kt` | 语义驱动帧（visualizer 消费），内含 raw feature |
| `WaveFrameReceiverOpus.kt` | 接口 `fun receive(frame: WaveDriveFrameOpus)` |
| `WaveAudioAnalyzerOpus.kt` | DSP：PCM→特征→驱动帧 |
| `WaveVisualizerOpus.kt` | 自定义 View：波浪群 + 轻弹簧底 + Gerstner + Catmull-Rom 渲染 |

改动：`AudioRecorder.kt`（采集 + `linkOpus`）、`AudioRecordDialogFragment.kt` + `fragment_record_audio.xml`（换用 Opus）。

## 二、录音采集（D6）

- `AudioSource`：`UNPROCESSED`（`AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED=="true"`
  才用，否则回退 `VOICE_RECOGNITION`）。
- 构造 `AudioRecord` 后按 `audioSessionId` 关 `AutomaticGainControl`/`NoiseSuppressor`/
  `AcousticEchoCanceler`（`isAvailable()` → `create(id).enabled=false`，持引用防 GC）。
- **单声道** `CHANNEL_IN_MONO` + 44.1kHz + PCM_16BIT；读块约 1024 帧。WAV 头 `channels=1`、
  `byteRate` 相应改。单路采集，PCM 分叉喂分析与写盘。
- 旧分析器（v2/Fable）保留但不 link、不运行，不受 mono 影响。
- 可选（后评估，不入首版）：写盘那份做响度归一化缓解偏小声。

## 三、音频分析器 `WaveAudioAnalyzerOpus`（D3/D7）

- 环形缓冲；一阶预加重 `y[n]=x[n]-0.97 x[n-1]`；Hann 窗 FFT 2048、hop 512。
- **频段**：20–32 个对数/Mel bin，聚合成 5–6 个宏频段（bass/lowMid/mid/highMid/treble）。
- **PCEN**（α≈0.98、δ=2、r=0.5、s≈0.025、ε=1e-6）压稳态背景、突出瞬态；或预加重+逐频段自适应白化。
- 特征：相对 RMS（短/长时比）、spectral flux→onset、event-density→pace、归一化谱质心（亮暗）、
  谱平坦度（门控）、近场 YIN pitch + 置信度、band ratios（treble/(bass+mid) 等表音色）。
- 稳健化：自适应噪声底（最小值统计）吸风噪；平坦度/浊音组合门 + 迟滞；滑动分位(p5–p95, 5–20s)
  归一恢复"正常 vs 大声"。平滑：响度 τ≈80–150ms（attack≈30 / release≈200–400ms）、质心 τ≈200–300ms。
- 产出 `WaveDriveFrameOpus`（语义）：`loudness/intensity`、`quietness`、`pace`、`brightness`、
  `bassWeight/midWeight/trebleWeight`（band ratios）、`onset`（离散事件强度）、`sustainDrive`
  （持续驱动强度）、`pitchWavelengthNorm`+`pitchConfidence`、`waterLevelTarget`、`noiseLike`。

## 四、视觉模型（D1/D2/D5/D8/D9/D10/D11）

**主角：离散波浪群。** 水面轮廓 = 当前所有活着的浪的连续叠加 + 轻弹簧底，采样 N≈64–96 点，
按 x' 排序后 **centripetal Catmull-Rom（α=0.5）** 连成 Path 填充。

- **一道浪 `WavePacket`（内部结构）**：`layer`、`originX`、`dir(±1)`、`wavelength k`、`speed`
  （由色散 `ω=√(g·k+σ/ρ·k³)` 定，长浪慢/短纹快，再乘 pace）、`amp`、`age/lifetime`、rise/fall
  包络、`gerstnerQ`（细纹小、大涌大）、crest/trailing 权重。对 H 的贡献 = 高斯窗 × Gerstner 峰形，
  中心 = `originX + dir·speed·age`（横向传播）。全局约束 `Σ(Q·k·amp)≤1` 防自交。
- **轻弹簧水线（底）**：N 点阻尼弹簧 + 邻居扩散（tension/damping≈0.025、spread≈0.2、2–4 趟），
  很低幅、活动度门控；提供浪间细微涟漪与"活水"（静默不死平）。不搞重物理仿真。
- **6 深度层视差（D2/D8）**：后→前，后层倾向大/慢/长浪、前层小/快/短浪（倾向非硬轨道）。每层
  渲染 = 弹簧底×该层视差 + 分配到该层的浪包。前景 1–2 层叠极轻独立细纹扰动，活动度门控。
- **数量（D9）**：并发浪数随能量涨落，安静 1–3、正常 4–7、高潮 ~12–16，并发上限 ~16，超限回收
  最旧/最淡者。
- **流动（D11）**：每浪按自身 dir 滚动横移，深度层错峰（后左前右之类）+ 很慢全局漂移；pace→流速。

## 五、声音 → 波浪群（融合，D5/D8/D10）

每帧驱动帧决定生成：
- **离散事件**：`onset` 过门 → 催生主浪：`amp ∝ onset×intensity`；波长/尺度由事件时频谱平衡定
  （bass 重→长浪→后层；bright→短浪→前层）；层由尺度/音色定；dir 交替；speed 由色散×pace。
  强事件额外催一道错峰**副浪**（D10）。
- **持续驱动**：`sustainDrive` 按 pace 周期性催生新浪，尺寸分布随当前 band ratios（bass 多→后层
  大浪多、treble 多→前层碎浪多），实现"音色→浪的尺度与分层分布"。
- **响度**：→ 总浪数 + 全局振幅 + 水位慢潮。**音高**（可信）→ 偏置初生波长。**quietness**→抑制生成，
  只留 1–3 道低缓长浪 + 弹簧呼吸。
- 每道浪出生后按自身 rise→travel→decay 演化，**不被后续音频改写**（连续叠加从结构上保证不后浪
  压前浪、不原地长成山）。

## 六、颜色（D12）

- **主体水体（最前/最近层）= 记事 Thing Background 本色，明度=1、透明度=1**（纯色原样 / 渐变整条
  按 `orientation`），颜色身份锚点，绝不压暗/叠透，静息就盖住 56dp 录音按钮。
- **上层/更远波浪层**：明度 + 透明度阶梯表现景深，倾向**更亮更透**（越远越亮越透），不变脏。
- **体积/光泽**：按高度/斜率着色——**提亮波峰为主**、压暗波谷为辅且很克制；不加独立高光/泡沫/光晕图层。

## 七、水位 / 状态 / 性能（D13）

- **水位**：沿用 v2 D68 口径，静息盖住按钮（最前景静态水面约 271dp），响度→慢潮位（攻快释慢），
  上限护住顶部 ~60dp 计时。
- **静音态**：低缓长浪 + 弹簧呼吸，无事件、无表面细节，活动度门控唤醒。
- **帧循环**：`postInvalidateOnAnimation` 跟随 vsync；显式处理 `onWindowVisibilityChanged`/
  `onWindowFocusChanged`/聚合可见性，锁屏返回自动恢复。
- **onMeasure**：非 EXACTLY 只上报 ≤280×360dp 固有尺寸，绝不撑大对话框。
- **状态编排**：沿用现有 PREPARED/RECORDING/STOPPED 的 alpha（0.16/1.0/0.16）与位移淡入。
- **性能**：每帧 O(N×活跃浪)（N≈80、浪≤16）+ O(N) 弹簧，远轻于 v2；无 196 点场、无三机制叠加。

## 八、接入（D13）

`fragment_record_audio.xml` 与 `AudioRecordDialogFragment` 换用 `WaveVisualizerOpus`；`AudioRecorder`
加 `linkOpus` 并在有 Opus 接收器时运行 `WaveAudioAnalyzerOpus`。v1/v2/Fable 全保留不删、不接线。

## 九、构建 / 验证

`:app:assembleDebug` 出 APK 侧载真机目视验证；按下述可调参迭代。

## 十、待真机校准的可调参

弹簧 tension/damping/spread；浪包 amp/width/speed/lifetime 区间与并发上限；生成门槛与生成率；
色散常数 g、σ/ρ；水位范围与攻/释；层 tone/alpha 阶梯与提亮波峰强度；频段划分；PCEN 参数；
归一窗口与各平滑 τ。目标：安静平缓轻柔、强声/快歌够野够快、音色/音高/节奏清晰可读、全程连续无
阶梯无帧峰。
