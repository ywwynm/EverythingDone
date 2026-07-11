# 调研 · FableSol 视觉质量与音频相关性升级（2026-07-11 第一轮）

> 第二轮（听觉感知全景、水的美学本体、音画结合语法）见
> `research-2026-07-11-perception-aesthetics.md`，其中的设计论点对本文
> P2/P3/P4 与"白沫"待议项有修订。

目标：在保持美观、优雅、流畅的前提下，让动画更准确地反映声音的大小、高低、
节奏快慢、节拍与情感。本文是调研与提案，**尚未做任何决策或实现**；供讨论用。
调研方式：先通读当前 Kotlin 实现与功能文档，再由五个并行检索任务分别覆盖
实时音频特征、端侧 ML、Android 图形、设计参考、跨模态映射文献，最后综合。

---

## 1. 现状与差距

当前链路（已确认代码）：A 计权响度 + 自校准归一 → 三频段/质心/平坦度/倾斜 →
32 频带 spectral flux onset → 双时标事件率 flow01（D13）→ 自相关+梳状+先验节拍
→ Foote 段落；映射层慢包络驱动 swell/hero、onset 走 DynamicWave 物理注入（D12）、
beat 驱动流速脉冲；渲染为纯 Canvas（OKLab 渐变 + glint/菲涅尔/透光/轻纱）。

对照调研结论，差距集中在五处：

1. **听不到音高**。centroid 只反映频谱亮度；语音的语调轮廓（F0）完全没有进入
   可视化。而"音高↔垂直高度/亮度"是跨模态文献里证据最强、接近先天的映射
   （新生儿即有；Spence 2011，doi:10.3758/s13414-010-0073-7）。
2. **情感只有粗 mood**。性格档由段落事件驱动，输入只有 loud/centroid 的 EMA。
   文献结论：音频单模态下 arousal（激活度）可靠（RECOLA CCC≈0.70），valence
   不可靠（经典特征 CCC≈0.52，300M 参数大模型才 0.63~0.68）→ 应做 arousal、
   明确不做 valence。
3. **语音与音乐不分**。节拍视觉在语音下会锁到 2~4Hz 音节伪脉冲；需要
   speech/music 门控来抑制。
4. **响度计权非感知标准**。A 计权对 100~200Hz 男声基频衰减过强；广播/流媒体
   响度标准是 K 计权 BS.1770（momentary 400ms / short-term 3s，与主观响度
   相关 r=0.979）。
5. **渲染光学上限受 Canvas 限制**。逐像素的体积吸收、折射、caustics、抖动去
   banding 只能靠 AGSL（API 33+）；Canvas 回退天然存在（现渲染器即回退）。

另有一条**验证过的护栏**：D13 的"表层事件密度主导感知速度"与文献一致
（感知速度由 event density 主导而非 tempo），方向正确，继续保持。

---

## 2. 调研发现要点（按方向）

### 2.1 跨模态映射与硬约束（文献）

| 音频属性 → 视觉属性 | 证据强度 | 备注 |
|---|---|---|
| 音高 → 垂直高度 / 亮度 | 强（近先天，跨文化） | Dolscheid 2014; Spence 2011 |
| 响度 → 尺寸 / 亮度 / 逼近感 | 中强（婴儿期即有） | 400ms momentary 窗与感知一致 |
| 质心（音色亮度）→ 色彩明度/饱和 | 中 | 现有 colorBright 映射有依据 |
| 粗糙/不谐和 → 视觉锯齿 | 中强 | 但尖锐形状带负面情绪义，需克制（现 roughness 映射方向正确） |
| 事件密度 → 运动速度 | 中 | 印证 D13 |
| arousal → 运动能量 + 彩度 | 强 | valence↔色相为弱证据，不用 |
| valence → 平滑度/圆润度 | 中强 | 用运动质感表达，不主动估计 valence |

硬约束（数值）：
- **同步窗**：环境类动画的音→视延迟预算约 125~185ms；尖锐 transient 更敏感，
  迟到的水花显眼。预测式调度（对齐下一拍）可抵消管线延迟（人类 tapping 本身
  提前于拍点数十 ms）。
- **晕动带**：全场同相摆动避开约 0.15~0.5Hz（峰值 0.2Hz；Golding 2001）。现
  wander 周期 12~32s（0.03~0.08Hz）安全，但各层若同相大幅慢摆需自查。
- **闪烁**：WCAG 2.3.1，≤3 次/秒、亮度对比阈值；饱和红色脉冲更严格。
- **无障碍**：多层视差与多速运动是 WCAG 2.3.3 点名的前庭触发类；需要
  reduced-motion 变体（安全替代通道 = 透明度/颜色变化）。
- **水的材质感知**：液体感来自"快速但空间平滑连贯的运动场"（低 Laplacian；
  Kawabe 2015）。逐层独立抖动会读成凝胶——层间需保留运动一致性。

### 2.2 实时音频特征（经典 DSP）

推荐纳入（按性价比排序，全部可因果、CPU 便宜）：
1. **F0 跟踪**：SwiftF0（2025，MIT，96k 参数，0.38MB，10dB SNR 下调和均值
   91.8%，显著优于 pYIN/CREPE）是抗噪最优；经典 YIN/FastYin/MPM 在近讲安静
   场景可达其九成价值且零依赖。YIN 的 CMNDF 极小值顺带给出 voicing/非周期度。
   pYIN 的 Viterbi 平滑非因果，不适用。
2. **音节率**（de Jong & Wempe 因果化）：强度包络峰 >2dB 于滚动中值 + ≥4dB 谷
   + voiced 门控；比 flux onset 密度更贴近语音"语速"（flux 把擦音噪声也计入）。
3. **arousal 复合指标**：2~3s 滑窗的 F0 IQR/斜率 + 响度动态 + 音节率
   （eGeMAPS 因果核心）；音频单模态 arousal CCC≈0.70，可靠。
4. **重音（prominence）事件**：voiced 音节的 energy×F0 位移×时长对滚动基线的
   z-score → 语音特有的"重读"事件，普通 onset 给不了。
5. **onset 前端两行升级**：自适应白化（Stowell & Plumbley，逐 bin 峰值归一，
   +10pp F-measure 量级）+ SuperFlux 频率向最大值滤波（颤音假阳性 −60%）。
6. **K 计权响度**：两个 biquad（shelf + RLB 高通）+ 400ms/3s 均方；44.1kHz 需
   重推系数（规范按 48kHz 给出）。保留现有自校准/可听度门架构，只换计权与窗。
7. **speech/music 门控（免费版）**：Scheirer–Slaney 三特征（4Hz 调制能量、flux
   方差、pulse metric）全部可从现有管线导出，1~2.4s 决策窗 + 迟滞。
8. **节拍器保留现状**，从 TISMIR 2024 实时 PLP 借三个思路：一拍前瞻抵消延迟、
   kernel 时长在稳定/灵敏间的权衡、输出 beat 稳定度置信。

明确不做：学习型节拍（BEAST/madmom/BeatNet：+5pp F1 不值 transformer 成本，
且许可差）、CREPE/SPICE（被 SwiftF0 全面压制）、实时 HPSS（因果版 100~200ms
滞后 + mask 泵动；用 32 频带的低/高子带 flux 拆分替代）、jitter/shimmer（手机
麦距离下不可靠）、chroma/key（语音无意义，音乐也需 10~30s 积分）、valence。

许可警示：Essentia 为 AGPL，aubio/TarsosDSP/BTrack 为 GPL——只可读作参考，
不可链接进闭源 APK；推荐集全部来自 MIT/规范文本，可自行实现。

### 2.3 端侧 ML

- **唯一值得引入的组件：YAMNet + 打包 LiteRT**。模型 3.94MB（Apache-2.0）+
  LiteRT arm64 压缩后约 1.74MB ≈ **5.7MB 下载增量**。输入 16kHz/0.975s 窗，
  Pixel 6 CPU 单次 12.3ms，中端机预计 25~60ms → 1Hz 推理占单核 3~6%，电量
  可忽略。产出：speech/music/singing/whisper/laughter/applause/silence 等
  521 类 + **6 类音乐情绪**（Happy/Sad/Tender/Exciting/Angry/Scary music）
  + 1024 维嵌入。用途：语义 mood 层（1Hz、3~5 窗 EMA，只驱动慢参数）。
- 不依赖 Play services / Play AI packs（国内设备普遍无 GMS），LiteRT 直接打包；
  NNAPI 已在 Android 15 弃用，小模型走 CPU/XNNPACK 即可。
- **音乐 valence/arousal 小模型 2026 年不存在商用可行选项**：Essentia/MTG 模型
  CC BY-NC-SA，DEAM 数据集本身 NC → 自训练也被污染。YAMNet 6 类音乐情绪是
  免费替代。
- SER（语音情感分类）：真实环境 8 类 macro-F1≈0.36，唯一小模型 Wav2Small
  （120KB）是 NC 许可 → 不做。
- Silero VAD（MIT，2.2MB ONNX）质量好，但需要 ONNX Runtime（arm64 压缩
  +6.3MB）→ 作为第二运行时被否决；现有噪声门 + 可听度门已够，必要时 WebRTC
  VAD（158KB）兜底。
- SwiftF0 官方是 ONNX；若采用需自转 TFLite（复用打包的 LiteRT），否则先用
  经典 YIN。

### 2.4 Android 图形

- **主路线：单 pass AGSL 水体 shader（API 33+，RuntimeShader 作 Paint shader
  照常 drawPath）**。九层高度场逐帧打包成 216×9 Bitmap，经
  `RuntimeShader.setInputBuffer()` 传入（javadoc 明示 heightmap 用途，跳过色彩
  空间转换与预乘；双线性采样免费插值 216 点）。**不可用 uniform float 数组**：
  SkSL 禁止非常量下标索引。shader 内自上而下合成九层，把 9 层半透明叠加
  （~9x 局部 overdraw）折叠为每像素一次求值；大学基准显示 RuntimeShader 在
  波纹/粒子类负载上优于等效 Canvas。
- 一旦进入逐像素域，以下效果接近免费：**Beer–Lambert 深度吸收**（体积感）、
  **背景折射**（环境渐变作 child shader，UV 按坡度偏移）、**程序化 caustics**
  （Worley 噪声 pow 锐化，深度衰减；AGSL 无噪声内建，需手写 hash）、
  **Cox–Munk 太阳闪点统计**（坡度高斯分布 → 闪点密度随"风"=毛细度变化）、
  **波峰背光透射**（现 crest glow 的逐像素版）、**IGN/蓝噪声抖动**（根治大面积
  渐变在 OLED 上的 banding）。
- 限制与守则：AGSL 无 dFdx/discard/噪声内建，循环编译期展开（保持短）；
  RuntimeShader 只创建一次（编译可达数百 ms，放首帧外）；half=mediump，高度
  用 float。**不要**：给整 View 挂 RenderEffect 做水体（比自绘贵）、每帧重建
  shader、BlurMaskFilter（硬件 Canvas 不支持）、每层 saveLayer（fill-rate 翻倍）、
  迁移 SurfaceView/GL（此尺寸无必要）、快照 View 做折射（GPU→CPU 回读）。
- **Bloom**：高光单独录进 RenderNode，1/2~1/4 分辨率
  `RenderEffect.createBlurEffect` 后加法混回（ARM SIGGRAPH 2015：降采样
  dual-filter 是移动端唯一便宜的 blur 家族）。API 31+，可选项。
- **Canvas 回退性能卫生**（无论走不走 AGSL 都做）：onDraw 零分配（复用
  FloatArray/`path.rewind()`/`setLocalMatrix` 复用渐变）；中端机单帧个体 draw
  调用参考预算 ~400；overdraw 全屏预算 ~2.5x（本 View 九层半透明约 9x 但只占
  屏幕约 1/3，属临界）；Profile GPU Rendering + FrameMetrics 验证。
- 帧节奏：120Hz 面板上用 `setRequestedFrameRate(60f)`（API 35）投票降频；
  降级阶梯：bloom → caustics/glitter 八度 → foam → 回退渲染器。
- 粒子（若做）：单次 `drawPoints`+ROUND cap 批量画，预算 100~300 粒轻松；
  API 33+ 可在 shader 内 hash 生成，零 CPU。

### 2.5 设计参考（获奖美学）

评审词证据：ADA 获奖作品被引用的品质是"与声音完美同步的流体动画"（Odio 2022）、
"配色随天空时刻变化"（Tide Guide 2026）、"对光照变化的反应"（Metaballs 2026）、
"对最小细节的极致打磨"（Alto）。评审奖励的工艺信号：单一连贯光照模型、
声画同步感、环境响应（时间/重力/主题）、无可见重复、克制、无障碍、零掉帧、
**一句话讲得清的概念**。

高性价比设计手法（12 条中最相关的）：
1. **按浪高做双色深度着色**（Sea of Thieves 散射近似）：层内像素在"深水色↔
   次表面色"间按高度混合，波峰透亮、波谷深沉；两色均由主题色派生（深=压暗
   去饱和，次表面=提亮微移向青）。逐像素版需 AGSL；Canvas 可用现有 crest
   glow 带近似。
2. **三类高光的纪律**（Journey 的 rim/ocean/glitter 分类）：顶边 1px rim 光随
   深度衰减；镜面光柱只在中景层；闪点只在最近层且响度门控（≈300ms 衰减）。
   现有 glint/菲涅尔/透光已接近，但未按此纪律分层。
3. **无理数层速比 + fBM 微八度**：杜绝相位重合与可见循环（Apple Music 播放
   背景的 4 层轨道手法；Seascape 的 fBM）。现有各层速度独立 + jitter，需核查
   是否存在可感的相位锁定。
4. **声音→力，物理→运动**（Magnetosphere 架构）：响度提升 choppiness（波峰
   锐度）多于波高。与 D12 完全一致，方向已验证正确。
5. **Material 3 弹簧语义**：空间量（水位/倾斜）允许轻微过冲（液体惯性），
   效果量（颜色/亮度）严禁过冲；默认 standard 阻尼，expressive 只留给
   录音开始/结束两个仪式时刻。
6. **层间级联延迟 30~60ms**（Disney overlapping action）——现有
   `cascade_step_s=0.054` 已实现，保持。
7. **时段底色**（Tide Guide 手法）：主题色之下按本地时间混入极浅冷暖底调
   （夜冷/黄昏暖）。与记事身份色关系需讨论。
8. **永不静止的 idle** + 响度"记忆"（近期高能段留下缓慢衰减的余韵）——现
   levelEnergy 慢释放已部分实现。
9. **reduced-motion 变体**（必做）：跟随系统"移除动画"，静态分层渐变 +
   缓慢透明度呼吸 + 常规电平计。
10. **色彩 script 测试**：10 种主题色截图排墙检查，每帧可作海报（Monument
    Valley 验收法）；同时检查暗色主题下是否出现无意的阴郁感。

品味护栏（原文强调）：不做多色相"AI 渐变"；不做振幅→几何的线性映射；
不做全屏脉冲/频闪；**慎加名词性装饰（泡沫粒子、水花、气泡）——每加一个名词
都稀释单一概念**（Journey 靠删除阴影换来光照打磨）；水不得索取注视（周边视觉
可读即可）；物理契约不可破（水位跳变/倾斜瞬移毁掉全部可信度）；永不掉帧。

一句话概念（供打磨）：**"你的声音是掠过一小片真实水面的风——它服从重力、
短暂记住你的能量、在你说完时归于平静。"** 每个设计手法要么服务这句话，
要么删掉。

---

## 3. 提案（分四个阶段，均含 Python 蓝本同构 + 差分测试成本）

> 项目既有原则延续：分析/映射侧任何变更，Python 蓝本与 Kotlin 同构实现、
> fixture 差分回归（D13 先例）；浪形连续性 D12 是硬约束。

### Phase 1 · 速赢（纯分析侧 + 渲染卫生，无新依赖）

- **P1a** onset 前端：自适应白化 + SuperFlux 最大值滤波（几行代码级别）。
- **P1b** K 计权 momentary/short-term 响度替换 A 计权入口（保留自校准体系）。
- **P1c** speech/music 门控（Scheirer–Slaney 免费版）→ 语音下抑制 beat 视觉
  （beatConf 清零或压低），音乐下正常。
- **P1d** 渲染零分配改造（followups 已记录的 GC 问题）+ FrameMetrics 降级阶梯。
- **P1e** 延迟测量：onset 发生 → 首个可见响应的端到端毫秒数（对照 125~185ms
  预算；capillary attack 0.06s 是现有最快通道，inject ramp 120ms 是最慢环节）。

### Phase 2 · 语音表达力（最大的音频相关性升级）

- **P2a** F0 跟踪：先经典 YIN/MPM（零依赖、可差分测试；近讲场景够用），
  SwiftF0-转-TFLite 作为后续抗噪升级选项。voicing 顺带产出。
- **P2b** F0 → **旋律浪**：voiced 期间以低幅连续注入表达语调轮廓（音高↔高度
  强映射），只影响近景 0~2 层；作为新注入通道进 DynamicWave，遵守 D12。
  语调上扬/下降在浪形上可读。
- **P2c** 音节率并入 flow01（语音模式下替代/融合 flux onset 密度），语速感知
  更准（D13 的自然延伸）。
- **P2d** 重音事件 → 现有 injectRhythmWave/incoming 通道的语音版触发器。
- **P2e** HNR/气声度 → 水体"清澈度"：谐波清亮=光滑镜面高光，气声/嘶哑=毛细
  纹+轻纱增多（映射到现有 capillary/veil 参数，零渲染改动）。
- **P2f** arousal 复合指标连续驱动 mood 管道（现 setMood 只被段落事件触发）。

### Phase 3 · 语义与编排（可选 ML）

- **P3a** YAMNet 语义层（+5.7MB、1Hz、Apache-2.0）：speech/music/singing/
  laughter/applause → 性格档语义化；音乐时启用 6 类音乐情绪驱动 mood 色彩
  能量。若 APK 预算否决，则 P1c 免费版是永久替代。
- **P3b** 节拍视觉强化（仅音乐模式）：下拍 bloom/亮度呼吸（置信度门控、
  ≤3 次/秒、效果量无过冲）；一拍前瞻抵消管线延迟。
- **P3c** 录音开始/结束仪式：开始=一次轻柔全层涟漪（expressive 弹簧），
  结束=水面收敛定格（现 applySilence 衰减的编排化）。

### Phase 4 · 渲染上限（AGSL 双轨）

- **P4a** AGSL 单 pass 水体（API 33+ 门控，现 Canvas 为回退）：高度场
  216×9 Bitmap `setInputBuffer`；逐像素深度吸收 + 折射 + dither。首版对齐
  现有视觉，再叠加新效果，保证回退一致性可对比。
- **P4b** caustics + Cox-Munk glitter + 波峰透射逐像素版（capillary01/
  roughness01 已是现成驱动参数）。
- **P4c** bloom（RenderNode 降采样 blur，API 31+，可降级）。
- **P4d** （待讨论）破碎白沫：坡度超限 → 白沫量场（扩展 crestVeil 语义，
  表面材质而非独立粒子系统，规避"名词性装饰"护栏）。
- **P4e** 设计系统落地：Journey 三高光纪律、双色深度着色、时段底色、
  reduced-motion 变体、色彩 script 验收。

### 各阶段不依赖关系

P1 全部独立可先行；P2 依赖 P1e 的延迟基线（校准注入时序）；P3a 独立；
P4 与 P2/P3 正交（分析侧与渲染侧解耦是现架构优点）。

---

## 4. 明确不做（附依据）

| 项 | 原因 |
|---|---|
| valence（音频单模态愉悦度）估计 | 科学上不可靠（CCC≈0.5），唯一小模型 NC 许可 |
| SER 语音情感分类标签 | 真实环境 F1≈0.36，产品上是负资产 |
| 学习型节拍跟踪（madmom/BeatNet/BEAST） | +5pp F1 不值 transformer 成本与许可风险 |
| CREPE/SPICE/pYIN | 被 SwiftF0/YIN 组合全面压制；pYIN 非因果 |
| chroma/key 检测 | 语音无意义；音乐需 10~30s 积分，驱动不了实时视觉 |
| 实时 HPSS | 因果版 100~200ms 滞后；子带 flux 拆分可替代 |
| jitter/shimmer | 手机麦距离/噪声下不可靠（仅 F0 跨设备稳健） |
| ONNX Runtime 第二运行时 | arm64 +6.3MB 压缩，超预算；一切 ML 走 LiteRT |
| Play services / Play AI packs 依赖 | 国内设备普遍无 GMS |
| Essentia/aubio/TarsosDSP/BTrack 链接 | AGPL/GPL 不可入闭源 APK（可读作参考） |
| SurfaceView/GL/Vulkan 迁移 | 280×420dp 元素 HWUI 完全够用 |
| uniform float 数组传高度场 | SkSL 禁止动态索引，必须用 Bitmap 通道 |
| 每层 saveLayer / BlurMaskFilter / 每帧重建 shader | 硬件 Canvas 性能反模式 |
| 全屏脉冲、频闪、多色相 AI 渐变 | 设计护栏 + WCAG 2.3.1 |

---

## 5. 待讨论决策点

1. **APK 预算**：YAMNet+LiteRT ≈ +5.7MB 下载增量，换 1Hz 语义层（含音乐
   情绪 6 类）。值不值？（P3a 取舍；不采用则 P1c 免费门控为最终方案）
2. **渲染路线**：AGSL 单 pass 是视觉上限所在，但工程量大、只覆盖 API 33+
   设备（存量 Android 12- 走回退）。双轨维护成本 vs 视觉收益，优先级怎么排？
   （建议：P1/P2 先行，P4 独立立项）
3. **白沫/破碎**：设计护栏反对"名词性装饰"；但"波峰白沫作为表面材质状态"
   （crestVeil 的自然延伸，非粒子）或可兼容概念。要不要做？
4. **旋律浪的表达强度**：作为 FableSol 的新视觉签名（明显可读的语调轮廓）
   还是低调融入（只在近层微弱起伏）？
5. **时段底色**：环境天空按本地时间引入冷暖底调，会在记事身份色之外增加
   一个颜色维度。与"配色 = 记事身份"的 D1/D6 原则如何相处？
6. **F0 实现选型确认**：先经典 YIN（零依赖、双端同构容易）后视真机噪声表现
   决定是否升级 SwiftF0-TFLite——是否同意此顺序？
7. **验收方式**：延迟测量脚本、10 主题色 script 墙、reduced-motion 变体是否
   纳入本轮范围。

## 6. 来源索引（关键条目）

- 跨模态：Spence 2011 (doi:10.3758/s13414-010-0073-7)；Dolscheid 2014；
  Ćwiek 2022 (rstb.2020.0390)；Kawabe 2015 (PMID 25102388)；van Assen &
  Fleming (PMC5807092)；Golding 2001 (PMID 11277284)；WCAG 2.3.1/2.3.3；
  ITU-R BT.1359（AV 同步阈值）。
- 音频特征：SwiftF0 (arXiv:2508.18440, MIT)；PESTO (arXiv:2508.01488)；
  YIN (de Cheveigné 2002)；de Jong & Wempe 2009 (BRM 41:385)；SuperFlux
  (DAFx-13)；自适应白化 (Stowell & Plumbley ICMC 2007)；ITU-R BS.1770-4 /
  EBU R128；Scheirer & Slaney 1997；TISMIR 2024 实时 PLP (10.5334/tismir.189)；
  RECOLA/eGeMAPS arousal 证据 (arXiv:2103.09154)。
- 端侧 ML：YAMNet (tensorflow/models, Apache-2.0, 3.94MB 实测)；LiteRT
  (developers.googleblog.com 2026-01)；NNAPI 弃用迁移指南；Silero VAD v6
  (MIT, 2.22MB)；Wav2Small (arXiv:2408.13920, NC)；MSP-Podcast Odyssey 2024
  (arXiv:2405.20064)；Essentia models 许可页。
- 图形：AGSL 官方文档（using-agsl / quick-reference / agsl-vs-glsl）；
  RuntimeShader.java (AOSP, setInputBuffer)；skia-discuss（SkSL 数组索引限制）；
  ARM SIGGRAPH 2015 blur 带宽；RenderNode blur (Android Developers Medium)；
  Android 16 ARR/getGpuHeadroom；Uppsala diva2:1806968（RuntimeShader vs
  Canvas 基准）。
- 设计：ADA 2022 Odio / 2026 Tide Guide、Metaballs 评审词；Sea of Thieves
  SIGGRAPH 2018；Journey GDC 2013（Zucconi 五篇解析）；teamLab Black Waves；
  Material 3 Expressive motion；WWDC18 Designing Fluid Interfaces；
  Spotify Canvas guidelines；calm technology (Case)；Apple Music 播放背景
  逆向 (aadishv.dev)。
