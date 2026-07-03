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
