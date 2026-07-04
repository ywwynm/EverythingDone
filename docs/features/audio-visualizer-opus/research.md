# 调研汇总 — 录音波浪可视化（Opus 版）

七轮联网调研 + v1/v2 精读的可落地结论，供实现参考。链接为关键来源。

## 1. 视觉核心：弹簧水线 + Gerstner + 连续路径

- **弹簧质点水线（耦合振子）**：水面离散成 N 点，每点阻尼弹簧 `a=-k·x-d·v` + 向左右邻居扩散
  （多趟双缓冲）。脉冲注入=浪起、扩散=传播、阻尼=消亡，多次注入自动干涉。经验值
  tension/damping≈0.025、spread 0.15–0.25、扩散 2–8 趟。O(N)、60fps 无压力、无条件连续。
  ([Game2DWaterKit](https://haydeludos.github.io/Game2DWaterKit-Documentation/water-system/water-simulation/) ·
  [Envato 2D Water](https://code.tutsplus.com/make-a-splash-with-dynamic-2d-water-effects--gamedev-236t) ·
  [John Wigg Dynamic Water](https://john-wigg.dev/DynamicWaterDemo/))
- **Gerstner/摆线波**：横向位移 `x'=x+Q·A·cos(k·x-ωt)`、高度 `y=A·sin(...)`，得峰尖谷平且 C1。
  硬约束 `Σ(Q·k·A)≤1` 防自交；细纹层 Q 小（毛细波更圆）、大涌浪 Q 大（峰更尖）。
  ([GPU Gems 1 Ch.1](https://developer.nvidia.com/gpugems/gpugems/part-i-natural-effects/chapter-1-effective-water-simulation-physical-models) ·
  [80.lv Gerstner](https://80.lv/articles/tutorial-ocean-shader-with-gerstner-waves))
- **路径**：采样点按 x' 排序后 **centripetal Catmull-Rom（α=0.5）**，从根上消除折角/过冲/自交
  （优于均匀参数化）。([Catmull-Rom](https://en.wikipedia.org/wiki/Catmull%E2%80%93Rom_spline))
- FFT 海面/Tessendorf 过重、为写实开阔海面设计，不用；纯 sum-of-sines 会"原地漂移"缺传播。

## 2. 声音→水面的融合思路（落在"浪"上，非水面纹理）

- **色散定波长↔速度**：`ω=√(g·k+(σ/ρ)·k³)`（g、σ/ρ 当观感常数）。低音浪长且慢、高音浪短且快，
  一眼物理成立。([Dispersion](https://en.wikipedia.org/wiki/Dispersion_(water_waves)))
- **法拉第波/cymatics**：振动驱动流体表面驻波，频率越高空间波长越短；subharmonic(f/2) 响应带来
  "缓半拍、更柔"；有振幅阈值（弱信号只微澜）。启示：**声音的频谱决定浪的尺度分布**（低音→大浪、
  高音→细浪），而不是给平面上纹理。([Nigel Stanford Cymatics](https://nigelstanford.com/cymatics/))
- **液化频谱、别做成频谱柱**：核心是"用数据当力去激励有惯性的介质，而不是拿数据直接画线"；靠
  平滑、惯性、传播、色散错峰把离散频谱化成流体。分量作为行波横跨全宽平移，不绑定 x 区间。
- **多通道正交映射**：响度→浪的数量+高度+水位；音色亮暗→浪的尺度分布+落层；音高→主导波长；
  onset/节奏→催生新浪；语速→生成率+流速。叠加后仍是一片自然的水，每维变化清晰可读。
- **体积/光泽只靠形与色**：按高度着色（峰亮谷深）、按斜率提亮（迎光侧）、多层半透明视差 + 明度/
  透明度阶梯——不加独立高光/泡沫/光晕图层。([jaynakum Gerstner shading](https://jaynakum.github.io/blog/5/GerstnerWaves.html) ·
  [siriwave](https://github.com/kopiro/siriwave))

## 3. 录音采集优化（关键，解决"抓不到精细特征"）

- 根因：`AudioSource.MIC` 走厂商默认预处理链（多数机型 AGC+NS），AGC 压平动态、NS 抹频谱细节。
- **推荐**：`UNPROCESSED`（`PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED` 检测，回退
  `VOICE_RECOGNITION`）+ 显式关 `AutomaticGainControl`/`NoiseSuppressor`/`AcousticEchoCanceler`
  （按 `audioSessionId`，`isAvailable()` 后 `setEnabled(false)`）+ **单声道** + 44.1kHz + 1024 帧。
- **单路采集，PCM 分叉**给分析与 WAV：单 App 不能同时开两个 AudioRecord 拿真实数据（麦克风独占）。
- **权衡**：关 AGC/NS 后保存的备忘更自然、动态更真，但安静环境更"生"、偏小声、噪声更明显。
- **现实边界**：单麦远场音乐能可靠拿到响度动态/onset 节奏/粗略 bass·mid·treble/亮度；拿不到精细
  乐器分离与复调 pitch。映射建立在"跟随能量/节奏/亮度"上。
  ([AudioSource](https://developer.android.com/reference/android/media/MediaRecorder.AudioSource) ·
  [AOSP 预处理](https://source.android.com/docs/core/audio/implement-pre-processing) ·
  [并发采集](https://source.android.com/docs/core/audio/concurrent))

## 4. 特征工程配方（喂给全新 Opus 分析器）

- **特征集**：相对 RMS（短/长时比）+ spectral flux→onset/event-density + 20–32 个对数/Mel 频段
  （PCEN 或 预加重+自适应白化）+ 归一化谱质心（亮暗）+ 谱平坦度（门控）+ 近场 YIN pitch（带置信度）。
- **预加重** `y[n]=x[n]−0.97·x[n−1]` 补麦克风高频滚降。**PCEN**（α≈0.98、δ=2、r=0.5、s≈0.025、
  ε=1e-6）一步做 AGC+压缩，压稳态背景、突出瞬态前景，最适合空调噪声场景。
- **相对/自适应为王**：band ratios（如 treble/(bass+mid)）表音色抗麦克风差异；滑动分位(p5–p95,
  5–20s)归一恢复"正常 vs 大声"；event-density 表"慢 vs 快"；自适应噪声底（最小值统计）吸收风噪；
  平坦度门控滤稳态噪声。
- **平滑量级**：响度 τ≈80–150ms（attack 30ms/release 200–400ms）、质心 τ≈200–300ms、
  自适应窗 5–20s、onset 最小间隔≈50ms。
  ([Meyda](https://meyda.js.org/audio-features.html) · [TarsosDSP](https://github.com/JorenSix/TarsosDSP) ·
  [PCEN](https://par.nsf.gov/servlets/purl/10313542) ·
  [自适应白化](https://www.researchgate.net/publication/250824858_Adaptive_whitening_for_improved_real-time_audio_onset_detection))

## 5. 响度"半绝对"方案的 web 调研（2026-07-04，6 份，支撑 [D21](decisions.md)）

针对"正常/小声说话跟大声放歌动画差别不大"，为"半绝对响度"（自适应零点 + 固定 dB 尺子）做的校准调研。

- **声学 dBFS 参考**：正常说话 RMS≈−20dBFS、峰值 −6~0、安静室内底噪 −50~−40、crest factor~11dB；实时
  电平表事实显示窗 −60~0dBFS；映射在 dB 域做再叠曲线（纯线性振幅"看着死"）。
  ([audiointerfacing](https://audiointerfacing.com/dbfs-in-audio/) ·
  [DPA 语音](https://www.dpamicrophones.com/mic-university/background-knowledge/facts-about-speech-intelligibility/) ·
  [OBS 电平表](https://obsproject.com/kb/audio-mixer-technical-details))
- **dB SPL 场景值**（@1m，DPA）：正常对话 58、提高嗓门 64、大声 70、喊叫 76；耳语 30/安静室内 40/很响
  音乐 100+。正常→喊叫差~20dB SPL，单人声自身动态~40dB。注意是 SPL 非 dBFS，只作场景相对参考。
  ([Phonak](https://audiologyblog.phonakpro.com/revisiting-expectations-for-average-and-soft-speech-levels/) ·
  [Nureva](https://support.nureva.com/docs/about-background-noise-levels))
- **心理声学**：+10dB≈感知响度翻倍（Stevens 幂律 loudness∝intensity^0.3）；dB 域映射是对的一阶近似；
  别用原始振幅/RMS 线性（夸大瞬态）。用户要的"强区分"是视觉审美，非"感知响度线性/sones"（后者会
  压缩大声，与诉求相反）。
  ([HyperPhysics](https://hyperphysics.gsu.edu/hbase/Sound/loud.html) · [Sone](https://en.wikipedia.org/wiki/Sone))
- **自适应 metering/gate/VAD**：噪声底用 **fast-down/slow-up** 双时间常数积分器（信号突发不抬底）；
  死区/迟滞 **5–6dB** 比 3dB 更稳；VU ~300ms 平滑 + hold 20–200ms 防闪。**45dB 量程被坐实**（正常说话
  ~44%、提高嗓门~67%、喊叫封顶，≈单人声动态）。
  ([VOCAL VAD](https://vocal.com/voice-quality-enhancement/standard-methods-of-voice-activity-detection-vad/) ·
  [噪声门](https://en.wikipedia.org/wiki/Noise_gate))
- **dBFS↔SPL**：两者差一个设备相关常数 K=94−sensitivity_dBFS（典型~120dB）；**未校准手机拿不到绝对
  SPL**（缺 mic 灵敏度+preamp+AGC+ADC 增益链，OS 不暴露），跨机误差 5–10dB + 距离项 6dB/翻倍 → 完全
  绝对不可行，半绝对是唯一正解。AGC 主动抬小声压大声，摧毁 dBFS↔SPL 关系（元凶实锤）。
  ([dBFS](https://en.wikipedia.org/wiki/DBFS) ·
  [AD 麦克风灵敏度](https://www.analog.com/en/resources/analog-dialogue/articles/understanding-microphone-sensitivity.html) ·
  [NIOSH 手机测声](https://www.cdc.gov/niosh/bulletin/2014/sound-app.html))
- **Android MIC/AGC**（深化第 3 节）：CDD 对 MIC **无**"禁 AGC"强制条款——多数原生机 MIC 不太压、动态
  基本线性（此时"大小声差不多"主因是**软件自适应归一化**，非硬件）；三星/小米等定制 ROM 可能 HAL 层
  压、`setEnabled(false)` 返回成功却**无效关不掉**。姿势：create→`getEnabled`→`setEnabled(false)`→复核+log。
  NS 建议一并关（削小声/高频），AEC 顺手；可选 `PCM_FLOAT`（免归一、小声精度高，但不增加动态）。
  ([CDD 5.4](https://android.googlesource.com/platform/compatibility/cdd/+/refs/tags/platform-tools-31.0.0/5_multimedia/5_4_audio-recording.md) ·
  [AutomaticGainControl](https://developer.android.com/reference/android/media/audiofx/AutomaticGainControl))
