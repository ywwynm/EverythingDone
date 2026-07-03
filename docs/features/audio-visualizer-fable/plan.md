# 录音海浪可视化（Fable 方案）· 实现计划

日期：2026-07-03。决策来源见 [decisions.md](decisions.md)（D1–D7）。
调研支撑：深水色散、波包叠加、卷浪造型、YIN 基频、Canvas 高帧率实践
（两份联网调研报告的结论已内化到本计划的公式与参数中）。

## 1. 需求映射

| # | 需求 | 落实机制 |
|---|------|----------|
| 1 | ≥6 层、记事颜色、透明度分远近 | 7 层实心水体；PURE 原色 / GRADIENT 横向整条复用；alpha 阶梯 0.16→0.92（D3） |
| 2 | 符合物理规律 | 深水色散 ω=√(g·k)（长浪快短浪慢）；包络按群速=相速/2 行进（波峰从浪群尾部冒出、前端消失）；陡度约束 Σ Q·k·A ≤ 1 防自交 |
| 3 | 每层多波峰波谷 | 每层 2 个环境正弦分量（波长互异）+ 波包叠加，横轴多峰谷 |
| 4 | 初始水位盖过录音按钮 | 静息水位 100dp > 按钮顶缘 76dp（D5） |
| 5 | 反映音量/快慢/声调 | 响度（RMS→dB）、语速（音节核起音率）、基频（YIN）三特征全量提取（D1、D6） |
| 6 | 音量→水位 | 响度→目标水位，攻 0.12s 释 0.9s，下限 84dp 恒盖按钮，上限 200dp（D5） |
| 7 | 新浪右侧注入、与已有浪融合 | 波包 Hann 包络从右缘外入画；载波用全局相位场 k·x−ω·t，与既有浪线性叠加、同波数波峰自动对齐，不改写已播放的浪（D6） |
| 8 | 及时响应、语速影响生成速度 | 水位攻击 0.12s + 瞬态微波包即时注入；音节率→波包生成间隔 0.15–1.2s（D6） |
| 9 | 波谷不低于水位很多 | 波峰锐化指数 p∈[1.5,4]（峰尖谷平）+ 波谷软钳制 η<0 时 η=−Lt·u/(1+u)（D5） |
| 10 | 巨浪倒卷、重力落水、自然融合 | SWELL→CURL（对数螺线卷头）→FALL（尖端抛物线）→SPLASH（水滴粒子+泡沫+落点次级波包）（D7） |
| 11 | 层各有灵动感 | 每层随机波长/相位/陡度、色散速度天然不同、独立慢幅度调制（波群）+ 缓慢换代重播种 |
| 12 | 无锯齿/截断/阶梯 | 64 段中点二次贝塞尔（C1 连续）、抗锯齿、闭合填充；不用 clipPath |
| 13 | 设备最高帧率 | Choreographer + frameTimeNanos dt（钳制 64ms）；onDraw 零分配、Path.rewind、对象池；小面积对话框填充率低 |

## 2. 架构与数据流

```
AudioRecorder.RecordingThread（已有，44.1kHz 立体声 PCM）
  ├─ RecordingAudioAnalyzer → RecordingWaveFrameReceiver（现有链，原样保留，
  │                            仅当有旧接收器注册时才运转）
  └─ OceanWaveAudioAnalyzerFable（新增旁路，仅当有 Fable 接收器注册时运转）
        └─ OceanWaveAudioFrameFable ──每约 20ms──▶ OceanWaveFrameReceiverFable
                                                        │（音频线程写入原子快照）
                                                        ▼
              OceanWaveVisualizerFable（Canvas View，Choreographer 帧循环消费）
```

新增文件（`views/recording/`，Kotlin）：
- `OceanWaveAudioFrameFable.kt` —— 帧数据类 + SILENCE 常量
- `OceanWaveFrameReceiverFable.kt` —— 接收器接口
- `OceanWaveAudioAnalyzerFable.kt` —— PCM→特征
- `OceanWaveVisualizerFable.kt` —— 海浪 View

宿主修改（3 处，现有方案类全部保留）：
- `AudioRecorder.kt`：新增 `linkFable()` 与 Fable 分析器旁路；新旧分析器均按
  "有接收器才运转"门控（旧链无人注册时不再空跑 FFT，行为不变、只省 CPU）
- `fragment_record_audio.xml`：`RecordingWaveVisualizer` → `OceanWaveVisualizerFable`（id 不变）
- `AudioRecordDialogFragment.kt`：字段类型与 `link→linkFable`

宿主契约维持：View 铺满 280×360dp、绘制在控件之下；宿主
`animate().alpha(0.16/1.0)` 由 View 重写 `onSetAlpha` 吸收进画笔透明度
（避免离屏合成）；`setThingBackground(ThingBackground)` 同名方法。

## 3. 分析器规格（OceanWaveAudioAnalyzerFable）

运行在录音线程内，每次 `ingest(bytes, size)` 立体声混单声道入环形缓冲
（4096 @44.1k），同时抽取一半采样率（22050Hz）入 YIN 缓冲（2048）。
每约 20ms `analyze(elapsedMs)` 产出一帧：

| 特征 | 算法 | 参数 |
|------|------|------|
| loudness | 最近 1024 样本 RMS→dBFS，线性映射 | [-52,-14]dB → 0..1 |
| transient | 最近 256 样本快 RMS 的上升沿 | rise×3 钳制 0..1 |
| pitchHz | YIN（CMND + 绝对阈值 + 抛物线细化），22050Hz、窗 1024、隔帧计算（40ms） | τ 搜索 44..368（60–500Hz）、阈值 0.14、3 点中值滤波 |
| voiced | 置信度与响度门控 | conf>0.5 且 loudness>0.08 |
| syllableRate | De Jong & Wempe 简化版：dB 包络峰计数（峰>2.56s 滑动中位数+2dB、峰间谷深≥4dB、需 voiced），2.5s 窗折算音节/秒，EMA τ0.8s | 正常语速约 3–6 |

静音/停止时宿主分发 `OceanWaveAudioFrameFable.SILENCE`。

## 4. 波场模型（View 内）

坐标：y 向下；水位 level 自底向上计。全局像素重力 `g = 360dp/s²`
（λ=150dp 波周期约 1.6s）；所有分量 ω=√(g·k)，一律向左传播
（θ = k·x + ω·t + φ）。

7 层参数（i=0 最远 → 6 最近；dp）：

| i | alpha | 基线抬升 | 主波长 λ1 | 陡度 k·A | 波包权重 |
|---|-------|---------|----------|----------|----------|
| 0 | 0.16 | +24 | 320 | 0.055 | 0.30 |
| 1 | 0.22 | +20 | 278 | 0.075 | 0.38 |
| 2 | 0.30 | +16 | 242 | 0.095 | 0.48 |
| 3 | 0.40 | +12 | 210 | 0.115 | 0.58 |
| 4 | 0.52 | +8  | 183 | 0.140 | 0.70 |
| 5 | 0.68 | +4  | 159 | 0.165 | 0.84 |
| 6 | 0.92 | 0   | 138 | 0.190 | 1.00 |

- 每层 2 分量：主分量 (λ1, A=k·A×λ/2π×随机 0.85–1.15)；副分量 λ2=1.9λ1、
  幅 0.5 倍、独立相位。波长/相位初始化随机抖动，各层互不成整数比。
- 波形函数：锐化正弦 `s(θ)=2·((sinθ+1)/2)^p − mean(p)`（mean 数值预计算保
  均值为零），p 随层从 1.5（远）→ 3.0（近）；近两层（i=5,6）另加 Gerstner
  横向位移 X = x − Q·A·cos(θ)，Q·k·A≈0.35（波峰前倾）。
- 波群呼吸：每层幅度 ×(0.72+0.28·sin(Ω_i·t+φ_i))，Ω_i 随机 0.12–0.30 rad/s。
- 换代重播种：每层每 25–40s 把副分量淡出→换新 (λ,φ)→淡入，海面长期不重样。
- 波谷软钳制：η<0 时 η=−Lt·u/(1+u)，u=−η/Lt，Lt=10+0.7i dp。
- 水位：`level += (target−level)·(1−exp(−dt/τ))`，target=84+loudness^1.1×116，
  τ 攻 0.12s / 释 0.9s。层基线 y = H − level − 抬升_i。

## 5. 波包（声音注入）

- 结构：Hann 包络 × 全局相位载波；`η_p(x)=A·E((x−xc)/W)·sin(k·x+ω·t+φ_g)`，
  E(u)=0.5(1+cos πu)（|u|≤1），W=1.25λ（全宽 2.5λ）。
- 行进：xc 以群速 c_g=ω/(2k) 向左；载波峰以相速穿过包络。
- 生成：门控 loudness>0.06；间隔 = clamp(0.9/max(音节率,0.9), 0.15, 1.2)s；
  波长 λ=240×(pitch/80)^−0.55 dp 钳制 [78,250]（无声调用 150±25 抖动）；
  振幅 A=(8+22·loudness^1.3)dp×抖动，起振 ease-in 0.3s，入画 4s 后指数衰减
  τ2.5s；出左缘或幅值<2% 时回收。上限 12 个，满员复用最老者。
- 瞬态（transient>0.4）额外注入短波长小微波包（λ≈90dp、A∝transient），
  保障"及时响应"。
- 逐层呈现：包场对 64 点采样网格每帧算一次（共享数组），各层按波包权重
  叠加，越近越显著。

## 6. 巨浪（Breaker）

- 触发（D7）：瞬态通道 transient>0.55 且 loudness>0.5 即触发；持续通道
  loudness>0.62 维持 2s 后，每 6–10s 触发一次；冷却 4–8s 随机；同屏 1 个，
  宿主层为最近层。
- 序列（总时长≈1.2–1.8s）：
  1. SWELL（0.4s）：专属大波包（λ≈170dp，A=30–44dp ∝ loudness）从右入画，
     局部陡度推向 0.9；
  2. CURL（0.4s）：波峰处生成对数螺线卷头 r=r0·e^(−0.22θ)，θ 扫 0→1.75π，
     r0=0.5×峰高，唇厚 0.22×峰高，随波峰以相速前行；
  3. FALL（约 0.3s）：卷头尖端脱离，vx=−1.15c_p、vy 受 g=1000dp/s² 抛物线
     下落，射流拉长；
  4. SPLASH：触水生成 14–20 个水滴粒子（上扇形初速、同重力回落，落水即
     回收并转泡沫）+ 落点注入次级波包（λ≈60dp、A≈10dp）与水面融合；
     泡沫沿唇口与溅落区，alpha 指数衰减 τ1.5s，随表层漂移。
- 造型画法：卷头为闭合 Path（外螺线去、内螺线回），用最近层画笔绘制；
  水滴/泡沫为小圆，泡沫色 = 记事代表色向白混 70%。

## 7. 渲染与性能

- 帧循环：`postOnAnimation` 自续；dt=frameTimeNanos 差值，钳制 ≤64ms；
  attach+visible 才运转。
- 采样网格 64 段（65 点，dx≈4.4dp）；曲线 = 中点二次贝塞尔（quadTo），
  抗锯齿开；每层闭合填充到底边。
- 零分配：Path×7 rewind 复用、FloatArray 网格/包场复用、波包/水滴/泡沫
  对象池；音频帧经 volatile 快照传递；onDraw 无 new。
- 不使用 saveLayer / clipPath / xfermode；宿主 View alpha 经 onSetAlpha
  吸收进画笔。
- 预算：7 层×64 段路径每帧 CPU 微秒级；280×360dp 填充率远低于全屏；
  120Hz 有充分余量。留降级阶梯：网格 64→48→32，层 7→6。

## 8. 颜色

- PURE：各层同一 ARGB，按层 alpha。
- GRADIENT：每层 LinearGradient(0..w 横向, color→endColor)，orientation 含
  右→左语义（R_L、RT_LB、RB_LT）时交换端点；shader 仅在尺寸/颜色变化时
  重建；层 alpha 经 paint.alpha 叠乘。
- 泡沫/水滴：代表色向白混 70%，只出现在巨浪期间。

## 9. 验证

1. `:app:assembleDebug` 编译通过（产物 app-debug.apk）。
2. 目视清单（用户侧真机验证）：静音水面轻涌且盖住按钮；说话水位随音量
   起落；语速快时新浪更密；低音出长涌浪、高音出细碎浪；喊叫触发倒卷
   巨浪并溅落融合；各层节奏不同步；曲线平滑无锯齿；高刷流畅。
3. 按项目约定不主动连接设备/模拟器；如需发布 debug 更新走
   `publishDebugUpdate` 流程并写 debug-updates 日志。
