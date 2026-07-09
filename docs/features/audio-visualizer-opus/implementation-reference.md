# 实现全景与参数字典 — 录音波浪可视化（Opus 版）

> ⚠️ **时效提示（2026-07-06）**：§3 的"重力/倾斜容器"描述的是**旧口径**（每帧解平衡 + 能量池晃动）。倾斜物理已升级为 C-可见方案——**Phase 1** 自由液面速度场（1D 交错网格浅水，晃动/回荡/爬墙/反射 + 浪包平流 + 静止门控，取代能量池）+ **Phase 2** 弹道水团（空中坠落/Worthington 回弹/空中水量守恒）。最新设计见 decisions.md **D48/D49**、迭代与参数见 sessions.md（202607060726 起）与 debug-updates。本参考文档 §3 待手感定稿后统一重写。

> 面向后续讨论/优化的实现参考：讲清"是什么、怎么工作、每个参数如何发挥作用"。
> 决策依据见 [decisions.md](decisions.md)（D1–D47），审美硬约束见 [preferences.md](preferences.md)，
> 调研见 [research.md](research.md)。本文只描述代码现状，不重复决策论证。
>
> 相关源文件（均在 `app/src/main/java/com/ywwynm/everythingdone/views/recording/`）：
> `WaveVisualizerOpus.kt`（渲染 View + 帧循环）、`WaveAudioAnalyzerOpus.kt`（PCM→驱动帧）、
> `WaveDriveFrameOpus.kt` / `WaveAudioFrameOpus.kt` / `WaveFrameReceiverOpus.kt`（数据结构/接口）、
> `AudioRecorder.kt`（采集线程）、`fragments/AudioRecordDialogFragment.kt`（传感器接入）。

---

## 0. 数据流总览

```
AudioRecord(PCM16 mono 44.1k)
 → AudioRecorder 采集线程：ingest(PCM) 累积；每 mSamplingInterval=20ms 调一次 analyze(elapsed)
   → WaveAudioAnalyzerOpus：PCM → 客观特征 → 语义驱动帧 WaveDriveFrameOpus（全 0..1）
     → receive(frame)  [在录音线程调用；只写 @Volatile mIncoming + synchronized onset 累加]
 ── 解耦 ──
 WaveVisualizerOpus 自己的 vsync 帧循环（postInvalidateOnAnimation）：
   onDraw → update(dt)[平滑目标值 + 生成/推进浪包 + 重力] → drawWater(canvas)
```

- 音频线程只更新"目标值"，绘制线程用连续包络追随。两者帧率不同、互不阻塞。
- 传感器（TYPE_GRAVITY）回调在主线程，写 `@Volatile mInputGravityX/Y/Z`；`updateContainerGravity` 在 vsync 线程读。线程安全。

**两层语义分离（关键设计）**：
- `WaveAudioFrameOpus` = 客观测量（rms/dbFs/5 频段/质心/平坦度/flux/onset/eventDensity/pitch）。
- `WaveDriveFrameOpus` = 视觉语义（loudness/intensity/quietness/pace/brightness/bass·mid·treble 权重/onset/sustainDrive/pitchWavelength/pitchConfidence/waterLevel/noiseLike + 原始 feature）。

**设计哲学（D5/D8/D10）**：声音塑造的是一群**离散、分层、可数的"浪"**的数量/大小/落层/传播速度，不是一片水面纹理。"现在有几道浪、多大、在哪些层、跑多快" = "现在多响、什么音色、什么音高、什么节奏"的直接读数。

---

## 1. 音频分析器 `WaveAudioAnalyzerOpus`

构造 `WaveAudioAnalyzerOpus(sampleRate=44100)`。FFT_SIZE=2048，FAST_WINDOW=512，BAND_COUNT=5，mono PCM16。

### 1.1 ingest(buf, byteReadSize)（录音线程逐样本）
- `readPcm16` 小端 → [-1,1] 写环形缓冲 `mRing`（raw 信号）。
- 同时对每样本连续跑 **K 加权两级 biquad**（transposed DF-II，连续状态、无每帧 warm-up），写 `mKRing`。
  - K 加权（BS.1770-4，D23 建议2）：stage1 high-shelf（fc≈1682Hz +4dB Q0.707 抬 2–5k）+ stage2 RLB high-pass（fc≈38Hz Q0.5 压低频）。系数 `designKWeighting()` 按 sampleRate 由 RBJ cookbook 生成。
  - **分工**：响度（dbFs/fastDbFs）用 K 加权 `mKRing`；FFT/flux/频段/pitch 用 raw 预加重 `mRing`。目的：从测量源头削低频空调隆隆对响度的贡献。

### 1.2 analyze(elapsedMs)（约 43–50Hz）
`dt = clamp(elapsedMs, 8..80ms)/1000`。
- **组帧**：最近 ≤2048 样本前补零；逐点 raw 一阶预加重 `pre = raw − 0.97·prev` 加 Hann 窗 → FFT 输入；K 加权样本累加平方 → 时域 RMS。`dbFs = 20log10(rms)`（K 加权后）。
- **FFT** 迭代基-2；`mMag[bin] = |X|/half`。
- **自适应白化 + SuperFlux**（onset novelty）：每 bin `mWhitenPeak`（遇大即涨、否则 ×WHITEN_DECAY 衰减，floor `WHITEN_FLOOR`），白化 `w=m/peak`；参考帧=上一帧白化谱 `mPrevWhitened` 沿频率轴 ±SUPERFLUX_MAXFILTER_BINS(2) 取 max，正向差累加 → flux（抑颤音虚假 onset）；帧末 `mCurWhitened`→`mPrevWhitened`。
- **谱质心/平坦度**：`centroid = normalizeLogFreq(Σm·f/Σm, 120, 6000)`；`flatness` = 几何均值/算术均值（越高越像噪声）。
- **5 宏频段自适应归一**（bass/lowMid/mid/highMid/treble，边界 `BAND_HZ` = 40/160/500/1500/4000/11000）：各段 `mBandFloorDb`（快降 FLOOR_FALL0.30/慢升 FLOOR_RISE0.02）、`mBandPeakDb`（快升 PEAK_RISE0.35/慢降 PEAK_FALL0.03），峰≥底+BAND_MIN_RANGE_DB(26)，归一后 attack0.5/release0.16 → `mBandNorm[b]`。
- **半绝对响度（D21/D59，核心）**：`semiAbsLevel = (levelDbFs − mFloorDb − DEADZONE_DB) / RANGE_DB` clamp01。
  - 零点 `mFloorDb` = 信号门控自适应底：seeding 开场锚定；如果开场样本已达到内容电平，seed 余量从 3dB 平滑扩到 24dB，避免真实内容被 5dB 死区吃掉。
  - `signalGate = max(tonalNow, pitchConfidence, fluxActive, riseActive, contentActive)`。前四证据分别是低平坦=乐音 / 有音高 / flux 起伏 / dB 快升；`contentActive` 由 `dbClamped-mFloorDb` 的 headroom（14→24dB）和 K 加权绝对电平（-62→-52dB）取 max。
  - `floorK = FLOOR_FALL` 用于变静快降；声音变大时，`signalGate >= FLOOR_FREEZE_GATE0.55` 则冻结 floor 上升，否则用 `FLOOR_RISE·(1−signalGate)` 慢升。低电平空调稳态仍会被吸收；稳定但足够明显的真实声音不会被慢慢吞成底噪。
  - `levelDbFs = max(dbFs, fastDbFs())`（快窗 512 取 max，瞬时冲击更快）。RANGE_DB=45 尺子 + DEADZONE_DB=5 死区。
- **视觉内容分数（D60）**：`visualContentScore = VISUAL_CONTENT_BASE0.12 + 0.88·max(tonalNow, pitchConfidence, onsetScore, eventDensity)`。`semiAbsLevel` 只表示高出环境底的能量，`visualLevel = semiAbsLevel · visualContentScore` 才进入语义映射。缺少音调/音高/onset/节奏结构的宽频环境声只给低水位和轻响应。
- **onset**：`threshold = mFluxMean + mFluxDev·FLUX_DEV_BIAS`；`onsetScore = clamp01((flux−threshold)/(mFluxDev·FLUX_GAIN+FLUX_MIN_RANGE))·tonalGate`；`isOnset` 需 ≥ONSET_TRIGGER(0.40) 且距上次 ≥MIN_ONSET_SPACING_SEC(0.05) 且 semiAbsLevel≥ONSET_LEVEL_GATE(0.06)。`eventDensity` = 近 96 帧每秒事件数 / EVENT_DENSITY_FULL(7)。
- **YIN pitch**（隔 PITCH_EVERY=3 帧）：门控 level<PITCH_LEVEL_GATE(0.08) 或 flatness>PITCH_FLATNESS_GATE(0.55) 时置信衰减；差分函数+CMND，YIN_THRESHOLD0.16 首个局部极小，抛物线精化；75..600Hz，`pitchNormalized` 对数归一。

### 1.3 mapToDrive（特征→语义，含 attack/release 平滑）
平滑 `approach(dt,tau)=1−exp(−dt/tau)`。
| 输出 | 计算 | attack/release |
|---|---|---|
| presence | =visualLevel | 0.06 / 0.28 |
| quietness | 1−smoothStep(presence: QUIET_START0.03→QUIET_FULL0.13) | — |
| loudness | =visualLevel（较线性，水位用） | 0.05 / 0.30 |
| intensity | =contrast(visualLevel)（S 曲线强区分，浪高用） | 0.05 / 0.30 |
| pace | eventDensity·smoothStep(presence:0.05→0.30) | 0.10 / 0.55 |
| brightness | centroid·presence | 0.22 |
| bass/mid/trebleW | band ratios（bassE=bass+0.6lowMid 等）各 /sum | 0.20 |
| sustainDrive | (0.7presence+0.5loudness)·(1−0.3quietness) | 0.12 / 0.22 |
| waterLevel | WATER_REST+（WATER_MAX−REST）·contrast((0.4loud+0.6intensity)·WATER_DRAMA1.2) | 0.26 / 0.64 |
| noiseLike | =flatness | 0.30 |
| pitchWavelength | confidence>PITCH_MIN_CONFIDENCE(0.30)? pitchNormalized : 0.5 | — |
| onset | isOnset? max(score,0.5) : score·ONSET_CONTINUOUS_SCALE(0.22)·presence | — |

`contrast(x) = 0.5·smoothstep(x) + 0.5·x²`。

---

## 2. 渲染 `WaveVisualizerOpus`

`class WaveVisualizerOpus : View, WaveFrameReceiverOpus`。LAYER_COUNT=6，BASE_COMPS=3，RENDER_N=216，MAX_PACKETS=26，MAX_DT=0.05。

### 2.1 水面模型（D15）= 基础波场 + 离散事件浪包
**① 基础波场**（始终起伏、有峰有谷）：每层 3 分量行波。init 里各层随机 `mBaseK`（波数=cycles·2π，cycles 按层 CYCLES_FAR_SCALE1.35→NEAR0.72 缩放）、`mBaseDrift`（相速，温和色散，全场同向 mFlowDir=+1，LAYER_REVERSE_PROB0.18 少数层反向）、`mBasePhase`、`mBaseWeight`（归一化→场值∈[-1,1]）、`mBaseAmpScale`（0.84..1.29）。相量递推预计算 `mCosDx/mSinDx`（消逐点 sin，D23 建议3.2）；`mBaseWobbleK/Phase` 缓慢 LFO（WOBBLE_AMP0.18）乘权重去机械感（D23 建议5，不碰流向）。
- 振幅 `baseAmpLayer = (BASE_FLOOR_DP3.6 + BASE_GAIN_DP26·mLayerDrive[layer])·density·mBaseAmpScale·mLayerAmp[layer]`。

**② 事件浪包 `WavePacket`**（正高斯行进波峰）：`center = origin + dir·speed·age`；不对称宽度（迎/背 skew）；贡献 `amp·env·exp(−(u/wSide)²)`。生命周期 `lifecycleEnv = smoothStep(0,riseFrac,t)·(1−smoothStep(fallStartFrac,1,t))`。

### 2.2 6 层角色分工（D17/D19，主次≠振幅）
- **丰富度** = 各层独立波形 + 独立时间响应 `mLayerDriveTau`（前景 0.13 灵敏→后层 0.42 迟缓）。
- **主次** = **波峰净空阶梯 `mLayerCeilFrac`**（远 0.5→近 1.0），tanh 压进净空，后层最高点结构性低于前景，与振幅无关。
- 角色梯度（layer0=最远/最浅，5=最近/前景/最深）：

| 数组 | layer0(远)→5(近) | 作用 |
|---|---|---|
| mLayerAmp | 1.29→0.5 | 基础波场平均振幅（**反向**：前景平静不挡后层） |
| mLayerCeilFrac | 0.5→1.0 | 波峰极值/净空（前景偶尔冲很高） |
| mLayerPacketAmp | 0.5→1.29 | 事件浪幅（**反向**：前景偶尔大浪） |
| CYCLES_*_SCALE | ×1.35→×0.72 | 波峰密度（远细密、近大疏） |
| mLayerDriveTau | 0.42→0.13 | 驱动响应快慢 |
| mLayerOffset | 60→0dp | 基线偏移（×offsetScale） |
| mLayerTone / mLayerBaseAlpha | 0.56→0 / 120→255 | 视差提亮/透明 |
| LAYER_SPAWN_W_BACK/FRONT | — | 落层频率（弱声偏远、强击偏近） |

"越近极值越高但平均越低，越远极值越低但频率越高"→ 任意时刻可见 6 层。

### 2.3 update(dt)
- 平滑各语义（intensity 涨0.072/落0.264、pace、brightness、quietness、sustain、waterLevel、bass/treble/noise）。
- `flowDrive = max(intensity, sustain·0.8)`；`mFlowTime += dt·(FLOW_BASE0.5 + FLOW_PACE2.8·pace + FLOW_DRIVE0.5·flowDrive)`。
- `updateContainerGravity(dt)`（见 §3）。各层 `mLayerDrive` 用各自 tau 追 flowDrive。
- **生成**：onset≥ONSET_SPAWN_GATE(0.12)&quietness<QUIET_SPAWN_BLOCK(0.96) → pace≥0.5&onset≥0.45 走 `spawnWaveTrain`（浪列）否则 `spawnWave`+可选副浪；sustain 周期（interval 随 pace 从 0.30→0.129 收紧）→ `spawnWave`；slosh 能量超阈 → `spawnSloshWave`。老化移除。

### 2.4 spawnWave / spawnWaveTrain（声音→浪的映射）
- 容量满只回收将逝浪（lifecycleEnv<RECYCLE_ENV_MAX0.129），否则不生成（不硬删可见浪）。
- 落层 `pickLayer(bias)`：LAYER_SPAWN_W_BACK→FRONT 插值采样，percussive 偏前层。
- 波长：远短近长·(1−0.3brightness)·bass 拉长·treble 缩短；pitch 可信时掺 pitchWavelength。`wavelengthPx = lerp(w/8, w·1.29, wl)`，w=`effectiveWaveSpan()`。
- 速度（色散）：`DISPERSION_BASE36·√wavelengthPx·(0.36+1.6pace)·bass 慢·treble 快`。长浪快短纹慢。
- 幅度：`PACKET_AMP_DP36·density·(0.45+0.64·max(strength,intensity))·mLayerPacketAmp·(1−0.45·noisy)`。
- 音色偏置常量：BASS_WL/WIDTH/SPEED/LIFETIME、TREBLE_WL/SPEED（第1条，偏置非硬轨道）。

### 2.5 颜色（D12/D29）
`rebuildPaints`：主体层（layer5）=记事本色不透明；上层按 mLayerTone 提亮 + mLayerBaseAlpha 递增透明（大气透视，更亮更透）。渐变记事沿用 8 向 orientation。**D29 已撤销所有竖直明暗**；D26 高光已 D27 撤回。

---

## 3. 重力/倾斜容器（D30–D46）

### 3.1 传感器接入（AudioRecordDialogFragment）
- `prepareTiltSensor`：TYPE_GRAVITY 主，缺失回退 TYPE_ACCELEROMETER；SENSOR_DELAY_GAME。
- 生命周期跟随 dialog 可见（onResume/onPause/onDestroyView/onDismiss，D33/D45）；onDestroyView 复位 `setContainerGravity(0,1,0)`。
- **临时锁 Activity 方向**（D41）：lockHostOrientation 存原 requestedOrientation → SCREEN_ORIENTATION_LOCKED；退出恢复。
- `dispatchGravityToVisualizer`：按 `mLockedRotation` 归一化到固定 UI 坐标（0/90/180/270），`setContainerGravity(-screenX, screenY, gz)`。**竖屏（ROTATION_0）左右/上下反转都已修并经真机验证**（sessions 202607060311/0314）；90/180/270 因方向锁定可能未真机验证（followups 待办）。

### 3.2 updateContainerGravity(dt)（每帧，双级平滑压噪声）
- `len = √(rawX²+rawY²)`（重力屏平面投影）；`confidence = smoothStep(GRAVITY_PROJECTION_LOW1.0, HIGH3.2, len)`。
- **平放退化（D42）**：confidence≤0.08 时不用微小投影，`mStableGravity` 以 FLAT_RETURN_TAU1.45 慢回 (0,1)。
- 否则 `mStableGravity` 追 unit，tau=STABLE_GRAVITY_TAU0.08（conf>0.6）或 WEAK0.24；`mGravity` 再追 target（GRAVITY_FOLLOW_TAU0.16）。
- **slosh 能量**：`targetDelta`（方向变化量）、`zDelta=|rawZ−prevZ|/9.8`（前后倾/推动，D32）；`mSloshEnergy = ·exp(−dt/SLOSH_DECAY_TAU0.55) + (targetDelta·SLOSH_TURN_GAIN2.2 + zDelta·SLOSH_Z_GAIN0.95)·(0.35+0.65conf)`，clamp[0,2.4]。静止时衰减、不误注入。

### 3.3 drawWater 重力坐标系（D37/D43/D46）
- gx,gy=mGravity（单位重力，指向水底填充侧，默认 (0,1)=屏幕下）；tx,ty=(gy,−gx)=自由液面切向。
- **局部容器坐标 (u=切向, v=重力向)**：矩形四角 `mRectU=dx·tx+dy·ty`、`mRectV=dx·gx+dy·gy`。
  - `uStart=min(mRectU)`、`uSpan=max−min`（切向跨度）→ `mWaveSpanPx`，供 `effectiveWaveSpan()`（浪包尺度）。
  - `vSpan=maxV−minV`（**沿重力轴的容器实际深度**，D47）、`topLimitV=minV+TOP_LIMIT_FRAC·vSpan`（计时保护线）。
- **面积守恒填充（D38）**：`fillRatio=(1−BASE_TOP_FRAC0.75 + waterLevel·WATER_RANGE_FRAC0.24)` clamp[0.10,0.88]=0.25+waterLevel·0.24。`baseSurfaceV=solveBaseV(w·h·fillRatio)`（二分 22 次，`clippedWaterArea` Sutherland-Hodgman 半平面裁剪+鞋带面积）。
- 每层：`layerBaseV=baseSurfaceV−mLayerOffset·density·offsetScale`；
  - **`hCeil=(layerBaseV−topLimitV)·mLayerCeilFrac`**（波峰净空，D47 复原口径）。
  - **`troughMaxPx=TROUGH_MAX_FRAC·vSpan`**（波谷限深，D47 复原口径）。
  - 逐点 s=Σ基础波场(相量递推)·baseAmpLayer + ΣpacketContribution → shapeHeight（Gerstner 峰尖谷平）→ 峰 tanh 压到 hCeil、谷 tanh 限到 troughMaxPx → `mSurfaceS[n]`，累加 mean。
  - **去均值**：`surfaceV=layerBaseV−(mSurfaceS[n]−mean)`（零均值，守恒需要）。
  - 屏幕：`mSurfaceX=cx+tx·u+gx·surfaceV`、`mSurfaceY=cy+ty·u+gy·surfaceV`。
- `buildGravitySurfacePath`：Catmull-Rom 过采样点，收尾沿重力延伸 `far=diag·2` 封底。外层 `clipRect(0,0,w,h)`。

### 3.4 spawnSloshWave
`mSloshEnergy≥SLOSH_SPAWN_GATE0.42`&cooldown 触发。count1-2，layer=5/4（前景），dir=mSloshDir，边缘进，amp=SLOSH_AMP_DP54·…（比普通浪大），短寿 SLOSH_LIFETIME1.55。→ 撞壁反弹（D35，隐形壁）。

---

## 4. 采集驱动 `AudioRecorder`
- RecordingThread：`audioRecord.read()` 阻塞读 VISUAL_READ_FRAMES=512 帧（≈11.6ms@44.1k mono）；每次读到 `ingest`。
- `elapsed≥mSamplingInterval(20ms)` 才 `analyze(elapsed)`（≈43–50Hz），elapsed 作 dt。`receive()` **在录音线程**。
- 每条链（v2/Opus）只在有 receiver 时跑；当前 Opus 生效（D13）。RECORDING_SAMPLE_RATE=44100 mono 16bit。DEBUG 每 400ms 打 opus drive log。WAV 保存：mIsRecording 时同一 audioBytes 写盘（单路采集 PCM 分叉，D6/D16 采集默认 MIC）。

---

## 5. 竖直静止 vs 加倾斜特性前（问题1 结论，D47 后现状）

竖直静止（gx=0,gy=1,tx=1,ty=0）时：
- `uSpan=w`、`mSurfaceX=[0,w]`=旧 x；`fillRatio` 刻意构造使基准水面 `mSurfaceY(平)=h(0.75−waterLevel·0.24)`=**旧 baseSurfaceY 完全一致**；层偏移后=旧 layerBaseY。→ **基准水面位置/水位响应/层偏移完全一致**。
- 波峰净空 `hCeil`、波谷限深 `troughMaxPx` 经 **D47** 改回以 `vSpan`(=h) 为参照 → **竖直时精确等于旧口径**（前景大浪冲到距顶约 72dp、波谷基线下约 18dp）。
- **仅剩一处有意保留的差异：去均值**。旧代码事件浪包 ≥0 正峰、无去均值→浪多时整片水面被顶高；新代码零均值→浪多不整体抬高（面积守恒 D38；"响度→水位"仍由 waterLevel 单独驱动）。**不复原**：一旦倾斜非零均值会破坏守恒，条件分支会在起始倾斜瞬间跳变。
- 传感器噪声：竖直静止 confidence=1，双级平滑（0.08→0.16）压制，仅极轻微倾斜漂移；slosh 稳态噪声能量 ≈0.16<0.42 不误触发（ACCELEROMETER 兜底噪声更大，边缘可能偶发）。

---

## 6. 参数速查（去哪调什么）
- **静止波形形状** → drawWater 去均值（守恒，勿动）；`hCeil`/`troughMaxPx`（已 D47 复原，调 `TOP_LIMIT_FRAC0.20`/`TROUGH_MAX_FRAC0.05`）。
- **倾斜跟随快慢** → `GRAVITY_FOLLOW_TAU0.16`、`STABLE_GRAVITY_TAU0.08`/`WEAK0.24`。
- **平放退化** → `GRAVITY_PROJECTION_LOW1.0`/`HIGH3.2`、`FLAT_RETURN_TAU1.45`。
- **晃水强度/频率** → `SLOSH_SPAWN_GATE0.42`、`SLOSH_TURN_GAIN2.2`、`SLOSH_Z_GAIN0.95`、`SLOSH_DECAY_TAU0.55`、`SLOSH_AMP_DP54`、`SLOSH_COOLDOWN_*`。
- **水位对响度** → `WATER_RANGE_FRAC0.24`、`BASE_TOP_FRAC0.75`（analyzer `WATER_DRAMA1.2`）。
- **浪高对响度** → intensity(contrast S 曲线)、`PACKET_AMP_DP36`、`BASE_GAIN_DP26`。
- **落层/尺度** → `mLayerCeilFrac`/`mLayerAmp`/`mLayerPacketAmp`/`LAYER_SPAWN_W_*`、`CYCLES_*_SCALE`。
- **音色偏置** → `BASS_WL/WIDTH/SPEED/LIFETIME`、`TREBLE_WL/SPEED`。
- **onset 灵敏** → analyzer `FLUX_DEV_BIAS0.30`、`ONSET_TRIGGER0.40`、`SUPERFLUX_MAXFILTER_BINS2`。
- **响度/内容标定** → analyzer `RANGE_DB45`、`DEADZONE_DB5`、`FLOOR_*`、`FLOOR_CONTENT_*`、`FLOOR_FREEZE_GATE0.55`、`VISUAL_CONTENT_BASE0.12`、seeding。

## 7. 已知待办/优化候选（未下结论，待讨论/真机）
- 倾斜容器手感（惯性回摆、爬墙、反弹夸张度）、90/180/270 轴映射、平放稳定性均**未真机验证**（[followups.md](followups.md)）。
- ACCELEROMETER 兜底：静止噪声比 GRAVITY 大、走路录音时运动加速度污染方向判断。老设备降级。
- `solveBaseV` 每帧 22 次二分；竖直有解析解 `baseV=h/2−h·fillRatio`，可加 gx≈0 fast-path（收益小）。
- `effectiveWaveSpan` 一帧延迟（spawn 在 update 用上一帧 `mWaveSpanPx`）：方向剧变那帧浪包位置略偏，可接受。
- 基础波场倾斜时波长随 uSpan 拉伸（cycles 铺满整个跨度）：竖直无影响，倾斜时波变"胖"，可接受。
