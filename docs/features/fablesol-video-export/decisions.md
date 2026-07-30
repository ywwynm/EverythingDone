# fablesol-video-export 决策

全部决策产生于 2026-07-26 的一次设计评审。编号 D1 起，与评审顺序一致。

---

## D1（2026-07-26）画面内容 = 水体 + 时钟计时动画 + 圆角

产物画面包含 FableSol 水体、`TimelyClockView` 的计时动画和卡片圆角；**不含**按钮、
文件名、进度条。

**理由**：时钟的墨色取自记事 accent（`AudioRecordDialogFragment.configureClockView()`
按 `accentBg` 设 `setInkColor` / `setInkGradient`），属于 Thing-owned 内容，把它放进产物
不违反 **Hybrid Chrome Surface** 那条"壳跟 Appearance Mode、水体跟 Thing Background"
的切分。按钮与文件名是 chrome 控件，放进产物只有在"演示这个 App"时才有意义。

**连带约束**：`TimelyClockView` 的形变由 `ValueAnimator` 驱动、跟随挂钟，离线渲染用不了。
必须给 vendored 的 `timelytextview` 加一个**按形变进度直接求值**的入口（走现成的
`TimelyEvaluator`），让时钟画面成为 `f(导出时间戳)` 的纯函数。这样它既能在渲染线程上
自绘、完全不碰主线程，也能在非实时驱动下逐帧精确复现。

---

## D2（2026-07-26）画布几何跟随对话框实测 dp，不固定画幅

**对话框宽度不是 280dp。** `BaseDialogFragment.getDialogWindowWidthPx()` 默认
`WRAP_CONTENT`，两个音频对话框都没有覆写；布局根节点的 `layout_width="280dp"` 因为以
null parent 充气被丢弃，生效的只有 `minWidth="280dp"`。宽度由最宽的子 View 决定，而
`TimelyClockView.onMeasure()` 的固有宽是 `advance × 6.84 × 40dp`，`advance` 来自用户选的
数字字形：

| 字形 | advance | 对话框宽 |
|---|---|---|
| bigshouldersstencil | 0.682 | 280dp（minWidth 兜底） |
| jetbrainsmono | 0.846 | 280dp（压线） |
| **poppins（默认）** | **0.959** | **310dp** |
| **orbitron（最宽）** | **1.224** | **383dp** |

`WaveVisualizerFableSolGl.onSizeChanged()` 把实测宽送进 `sim.setContainerWidthDp(w / density)`，
所以**物理容器宽度真的随字形变**。

**决策**：画布几何完全跟随当前实测 dp 几何，接受宽高比在 0.667～0.912 之间浮动。

**否掉固定画幅**（如恒按 280×420dp 渲染）的理由：改画幅就是改物理——波长、体积守恒、
可见跨度、倾斜水位全跟着变，产物里的水就不是用户刚才看到的那片水了。9:16 竖屏留边的
方案不违反这条（水体卡片仍是原比例），但它引入留边尺寸、卡片大小、居中方式一整串与
水体无关的新决定，留作以后的分享形态。

---

## D3（2026-07-26）像素尺寸：dp 几何照抄，像素高度提到固定档

导出时**不用设备 density**，而是按"dp 几何 + 目标像素高度"反推 density。

**理由**：FableSol 的几何全部按 dp 定义，提高像素密度是**真正的高分辨率重新渲染**，
不是放大——银丝与星芒会更实而不是更糊。若改用重采样（例如把屏上 1260px 缩放到 1296px），
2.9% 的重采样会破坏亚像素级的银丝轮廓连续性，而项目为此专门做过栅格相位相关判据
（见 `audio-visualization-fable-sol/rim-edge-testing.md`）。

---

## D4（2026-07-26）导出画框：24dp padding + 跟随 Appearance Mode 的底色 + 投影 + 描边

视频没有 alpha 通道，圆角外必然是某个实色。产物改为**画布整体放大一圈**：卡片四周
24dp padding，填一个偏白或偏黑的中性底色，卡片带投影与发丝描边凸显轮廓。

- **底色跟随 Appearance Mode**（深色模式偏黑、浅色模式偏白）。
- **padding 必须 ≥ 投影模糊半径**，否则阴影被画布边缘切掉。
- **描边随底色翻极性**：深色底用亮描边，浅色底用暗描边或省略（投影在白底上已足够）。

**理由**：画框是 chrome 的延伸，而界面本来就跟随系统主题，所以产物跟随主题是自洽的。
由此确立一条贯穿本功能的原则——**产物是"此刻这个界面的忠实记录"，不是一件归记事所有的
作品**。评审中曾主张固定偏黑底以最大化 HDR 高光的相对对比、并保证跨设备一致，被否。

**连带修正**：按同一条原则，时钟的 `setHostDark(AppearanceUtil.isDarkMode(...))` **照旧
跟随系统**，不固定。整个 chrome 层要么一起跟随、要么一起不跟随。

**实现要点**：投影几乎不要钱——`present.frag` 的 `roundedRectCoverage()` 已经在算圆角
矩形的有符号距离场，投影和描边都是同一个 SDF 上再做一次 `smoothstep`。但要**单独一个
导出用的 present program**，不要在现有 shader 里加分支：屏上那条是逐帧关键路径，不该
为导出功能背任何分支。

---

## D5（2026-07-26）导出 HDR 亮度上限取用户强度档，与显示余量无关

屏上是 `headroom = min(display.hdrSdrRatio, 用户HDR强度)`，每 250ms 跟着屏幕亮度实时变。
**导出固定取用户 HDR 强度档，完全不读 `display.hdrSdrRatio`。**

**理由**：

1. 按屏上余量导出的实际后果很糟且用户看不懂——室内中低亮度下 `hdrSdrRatio` 经常就是
   1.0，用户拿到一个纯 SDR 视频而没有任何提示。
2. 文件的 headroom 是文件自己的属性，播放端按自身能力还原。把导出那一刻的屏幕亮度烙进
   文件，等于把观看条件写死进内容。
3. 它是"离线导出没有窗口 surface、压根无 `hdrSdrRatio` 可读"这一事实的必然结论。

**代价**：屏上所见比文件里暗。这是正确的方向——文件里存的是作者意图的亮度。

**必须一起做的重构（改的是渲染器初始化契约）**：目前 `FableSolGlRenderThread.attach()`
里 `renderer.initialize(session.isHdrOutput)` 把 `hdrContentEnabled` / `sceneLinear` 与
EGL 窗口是否 HDR 绑死。要支持"屏幕 SDR 但导出 HDR"，必须解耦成三层：

| 层 | 现在 | 改后 |
|---|---|---|
| 场景 FBO | `hdrOutput` 为真才是 RGBA16F | 能建就恒为 RGBA16F |
| 窗口 present | 按 `hdrHeadroom` clamp | 不变 |
| 导出 present | — | 按导出 headroom 独立编码 |

GL 侧的前提是 `GL_EXT_color_buffer_half_float` / `GL_EXT_color_buffer_float`，ES 3.0 上
近乎必备，与显示器无关；真正的门槛在编码器与 `EGL_EXT_gl_colorspace_bt2020_hlg`。

---

## D6（2026-07-26）SDR 是另一条着色分支，不是 HDR 的降级

设备编不了 HDR 时，用 FableSol 自己的 SDR 分支**重新渲染**一份 SDR 产物，
**绝不让播放端把 HDR 结果 tone map 回来**。

**理由**：FableSol 的 SDR 不是 HDR 截断出来的。`water.frag:515/590` 与 `optical.frag:89`
的超白逻辑整段包在 `if (uSceneLinear && uHdrGain > 0.0001 && uHdrHeadroom > 1.001)` 里，
条件不成立时高光走 SDR 写法；ADR-0016 也明写"不对 SDR 添加 tone mapping"。两条分支在
同一份 shader 里，切换只是几个 uniform，把降级做成"换分支重渲染"是几乎零成本的正确做法。

**判定复用已有设置，不发明新开关**：

```
导出 HDR  ⟺  FableSolTuning.hdrStrength() > 1.0  且  设备能编 10-bit HDR 信号
否则      →  原生 SDR 分支渲染
```

`FableSolHdrPolicy.STRENGTH_OFF = 1f` 本来就是"不开启 HDR"。用户想要 SDR 产物就把调参
Dialog 里的 HDR 强度拉到底，全局一致。

**已知代价**：微信、QQ 一律转码，HDR 元数据几乎必然丢失。若日后实测分享是主要用途，
再补一个导出时的 HDR/SDR 选择。

---

## D7（2026-07-26）非实时驱动，且它不等于整曲前瞻分析

`CONTEXT.md` 原有不变式写"不得改用整曲离线分析"。评审确认这个词盖住了两个不同的东西：

| | 含义 | 是否被禁止 |
|---|---|---|
| **整曲前瞻分析** | 先扫全曲取段落/节拍结构，用**未来信息**驱动画面 | **是**，破坏因果性 |
| **非实时驱动** | 同一条因果实时链逐样本喂、逐步长推进，只是不按挂钟走 | **否**，每帧信息量与实时完全相同 |

提交 `56b77b4c` 的信息已经划过这条界："the only thing offline would buy here is section
and beat structure"——被拒绝的是那个前瞻结构。

**代码支持**：`fablesol/` 下所有 `SystemClock` / `System.nanoTime` 全部是性能探针；唯一
有功能作用的是 `FableSolGlRenderer.kt:396` 的 `SystemClock.elapsedRealtime()`，只喂给
`drainAndApply()`（调参下发）与 `advanceColorTransition()`（换色涌入），都不碰物理。
`sim.update(dt)` 是纯 dt，分析器是纯采样驱动。

**播放路径一行不改**：导出走一条新的"解码 → 按音频时间直接喂分析器"的路径，
`FableSolAudioFilePlayer` 的 AudioTrack + `playbackHeadPosition` 喂入原封不动。

**术语已在 `CONTEXT.md` 中精确化**，不变式改为"不得改用整曲前瞻分析"。

---

## D8（2026-07-26）导出跑在前台服务里，不设时长上限

前台服务（`mediaProcessing` 类型）+ 通知栏进度 + 可取消。用户可以退出对话框、切后台、
锁屏，导出照跑完。

**否掉 WorkManager**：它是为可延迟任务设计的，而用户点了导出就想现在跑。
**否掉留在对话框里**：`onVisibilityAggregated(false)` 会停掉渲染循环，且长录音无法接受。

**明确不设时长阈值**，由此四件事从可选变成必须：

1. 通知必须在启动后几秒内给出**可信 ETA**并滚动更新，不能只转圈。离线渲染帧成本稳定，
   跑几十帧即可外推。
2. 取消必须真能中断，并清掉半截文件与 muxer 状态。
3. 必须诚实面对热降频——20 分钟持续满载会降频，帧成本可能涨 1.5～2 倍，标称 20 分钟的
   任务实际可能跑 30～40 分钟。**`FableSolAdpf` 不用于导出**：ADPF 是为在 vsync 截止时间
   内完成一帧服务的，导出没有截止时间，上报只会误导调度器。
4. 失败即失败：删半成品、通知里如实报原因，**不做断点续传**。

---

## D9（2026-07-26）帧率 120 优先，编码阶梯逐级降级

`FableSolSpec.PHYSICS_DT = 1.0 / 120.0`，而 `FableSolSimulation.update()` 是定步长累加器
（`while (acc >= PHYSICS_DT)`）。所以 **120fps 渲染与物理正好 1:1，是这套模拟的原生速率**，
两者之间没有时间混叠。`FableSolSpec.FPS = 60` 只是蓝本参考值。

**成本**：物理总量恒为每秒音频 120 子步、不随帧率变；但 `buildFrame`（`surface2d.sample`
416.6µs + sheen 69.2µs，占 CPU 路径 87%）、GL 绘制与编码都是逐渲染帧的。所以
120fps ≈ 60fps 的 1.9 倍：

| | 每秒音频耗时 | 1 分钟录音 | 20 分钟录音 |
|---|---|---|---|
| 60fps | 0.5～0.66s | 30～40s | 10～13 分钟 |
| **120fps** | **0.95～1.25s** | **60～75s** | **20～25 分钟** |

**编码降级阶梯**（Android CDD 强制的只有 H.264 编码器；HEVC 编码是 SHOULD，
HEVC **Main10** 编码更是少数——很多中低端 SoC 能*解* Main10 只能*编* 8-bit Main）：

```
HDR 分支（hdrStrength > 1.0）
  1. HEVC Main10 + BT.2020/HLG
  2. AV1 Main10  + BT.2020/HLG
  3. → 落回 SDR 分支，并明确告知"本机无法导出 HDR"

SDR 分支
  1. HEVC Main10 + BT.709（10-bit）
  2. HEVC Main   + BT.709（8-bit）+ 抖动
  3. H.264 High  + BT.709（8-bit）+ 抖动
```

两条连带约束：

- **抖动由编码位深决定，不由场景是否线性决定。** 现在 `present.frag` 是
  `if (uSceneLinear) {...} else { 加抖动 }`；导出路径改成"只要落到 8-bit 档就加抖动"。
  依据是项目已知 8-bit 装不下 FableSol 的平滑渐变（`present.frag:57` 的 `triangularDither`
  就是为此存在），而视频编码会让它更糟两次：量化产生色带，DCT 又把掩盖色带的抖动当高频
  细节处理。
- **探测发生在渲染开始之前**：档位同时决定 shader 分支与编码器 input surface 的 EGL
  colorspace。顺序是探测 → 定档 → 建编码器 → 建 EGLSurface → 开渲。因此档位在用户点确认
  时已知，应连同预估体积一起显示。

---

## D10（2026-07-26）导出参数进设置，release 可见，用户可调

帧率上限、CQ 质量档、VBR 目标码率、关键帧间隔**全部**放进 FableSol 调参 Dialog，release 构建
可见（该入口 `SettingsActivity.kt:1036` 本就没有 `BuildConfig.DEBUG` 门，只有「调试」
分组是 debug 专属）。

评审中曾主张只暴露帧率、把码率与 QP 归为"标定量"放进调试分组（类比 D204/D217
「标定不随用户滑杆漂移」），理由是这几项没有预览、用户无法判断自己调对了没有。
**用户明确否决：说了让调整就要能调整。**

由此这三件事成为必须：

- **范围从设备读，不能硬编**：`EncoderCapabilities.getQualityRange()` 给 CQ 合法档位
  （各厂商不同，不是固定 0–51），`getBitrateRange()` 给码率上下限；
  `BITRATE_MODE_CQ` 不支持时 CQ 选项直接不出现。写死范围会在某些机器上让 `configure()` 抛异常。
- **面板里实时显示推导结果**代替看不见的预览：当前设置下的 **MB/分钟** 与 **导出耗时倍率**，
  给用户一条反馈回路。
- **这几项不走 `setTuningValue()`**：现有 param spec 都会实时推给 GL 线程，导出参数是导出时
  才读的。需要在 `FableSolTuning.GROUPS` 里加一类新 `Target`（`EXPORT`），只落
  SharedPreferences；同时纳入「恢复默认」（`restoreDefaults` 现在只 remove `param_` 前缀与
  两个 HDR 键），以及 13 种语言的文案。

**待标定的默认值**（需真机上拿最容易暴露色带的场景——大面积缓变的深色渐变记事 + 静水——实测）：

| 项 | 暂定 |
|---|---|
| VBR 目标码率 | 120fps → 24 Mbps；60fps → 14 Mbps（约 0.12 bit/像素/帧） |
| 关键帧间隔 | 2 秒 |

按 24 Mbps 算体积为 **3 MB/秒 → 180 MB/分钟 → 20 分钟 3.6 GB**。

---

## D11（2026-07-26）音轨并行编 AAC-LC 192 kbps

离线渲染本来就要解码整个文件拿 PCM，顺手编 AAC。`MediaMuxer` 要求 `start()` 之前把所有
track 加完，因此音视频两个编码器一起建、一起喂。

**不能直接 remux 原音轨**：本项目录出来的是 WAV（`AudioRecorder.saveToWaveFile()` 手写
RIFF 头），而 `MediaMuxer` 的 MP4 容器不支持 PCM 音轨（只收 AAC / AMR）。若源本身是 AAC
且容器兼容，可以走零转码 remux。

192 kbps：采集是单声道，128 kbps 已透明，192 是给"源可能是导入的立体声音乐"留的余量。
20 分钟音轨约 29 MB，相对视频的 GB 级可忽略。

---

## D12（2026-07-26）产物落 `Movies/EverythingDone/`，不自动挂成附件

导出完成后提供「分享」与「添加为本记事的附件」两个动作，但**不自动挂**。

**理由**：

1. GB 级文件不该进应用私有目录——用户在系统的"应用存储"里只看到 EverythingDone 占了
   几个 G，无法单独删除某一个视频。
2. 记事里已经有那份 WAV，再自动挂一个包含同一段音频的 MP4 是重复，详情页会同时出现
   音频卡与视频卡，卡片封面还会被视频抢走。
3. 不落盘直接进分享 sheet 不成立：`ACTION_SEND` 需要 FileProvider URI，必然先落盘；
   且十几分钟才产出的东西，用户很可能只是想存着。

**私密记事不做特殊处理**（用户裁定）：视频必须落盘。已知代价是一条私密记事的可视化会
出现在公共图库里，绕过隐私约束（ADR-0011）。若日后要收紧，此处是唯一改动点。

**WAV 与视频是两个独立产物**，视频不取代 WAV——否则功能入口本身就不存在了。

---

## D13（2026-07-26）不做实时旁路；倾斜靠重力轨迹还原

评审中一度因为"倾斜只在录音对话框有"而倾向实时旁路，最终否决。

**倾斜不需要实时录制就能保住**：`AudioRecordDialogFragment.kt:294` 以
`SENSOR_DELAY_GAME`（50Hz）注册 `TYPE_GRAVITY`，**且已经跑在自己的 `FableSolTiltSensor`
线程上**，与渲染路径无关。记成轨迹只要 50Hz × 3 float × 4 字节 = **600 B/秒 =
36 KB/分钟**，20 分钟 720 KB——相对视频的 3 MB/**秒**等于不存在。

**而实时旁路有硬冲突**：HDR headroom 是在**着色阶段**写进场景 FBO 的
（`optical.frag:96/101/106` 的 `min(uHdrCorePeak, uHdrHeadroom)`、`water.frag:519/605` 的
`min(outLinear, vec3(uHdrHeadroom))`），**一次场景渲染只能对应一个 headroom**，而 D5 已定
导出取用户强度、屏幕取实时余量，两者几乎总是不同。三条出路都要付代价，详见 ADR-0018。

**离线重建保真度**（逐项核对过）：

| 输入 | 来源 | 状态 |
|---|---|---|
| PCM | WAV | ✓ |
| 倾斜 | 伴生重力轨迹 | ✓（本功能之后的录音） |
| 随机相位/波长/抖动 | `FableSolRng(seed: Long)` → `java.util.Random(seed)`，固定种子 | ✓ 逐位可复现 |
| 挂钟依赖 | 全链只有性能探针用 `SystemClock` | ✓ |
| 记事配色 / 调参值 | 导出时的当前值 | 会变；视为特性 |
| 录音态 | 导出时恒按 RECORDING | ✓ |

**待实测确认一项**：`FableSolRealtimeAnalyzer` 的预热门（`:157` "预热门只拦截采集会话
开头"）是否纯采样驱动。若掺了挂钟，须改成采样驱动。

**重力轨迹存进 WAV 自身的自定义 RIFF chunk**（chunk id `EDmo`，放在 `data` chunk 之后）：

- 一个文件自动解决全部生命周期——同步、改名、复制、分享、备份、删除，附件走哪条路轨迹
  就跟到哪，**不需要任何额外管理代码**。伴生文件方案要在四条路径上各补一遍且总会漏。
- `AudioRecorder` 本就手写 RIFF 头，加一个 chunk 是该函数的自然延伸。
- RIFF 规范要求读者跳过不认识的 chunk，合规读者（MediaExtractor、ffmpeg、浏览器、DAW）
  照常播放。放在 `data` **之后**是为了保护"只认 `data` 长度"这一更普遍的实现——不解析
  `data` 长度连音频时长都算不出来，比"假设头恒为 44 字节"普遍得多。
- 否掉派生缓存目录：缓存的前提是"丢了可以重算"，而重力轨迹是**源数据**。
  否掉数据库：WAV 被拷出去再拷回来就断链。

格式尽量朴素：chunk id 四字节 + 小版本号 + 采样率 + `float32 x/y/z` 定长数组，不压缩。

**2026-07-27 补记**：`EDmo` 从"本功能之后的录音一定有"降级为"可能没有"。设置里新增的
「画面响应设备倾斜」（audio-visualization-fable-sol D227）关掉之后，录音对话框不再注册
传感器，`AudioRecorder.startRecording()` 也不再启动 Collector，写出的 WAV 整段没有这个
chunk——既然当时的画面本就不倾斜，记下来的姿态没有可复现的对象。读取端不需要改：
没有 chunk 与「保留录音过程中的画面倾斜」关掉走的是同一条竖直渲染路径。

---

## D14（2026-07-26）入口、图标与确认流程

**两个入口，一个引擎**：

- 录音对话框停止态：对号 FAB 右侧加「保存并导出视频」FAB，按钮行变为
  `[重录][对号][导出][取消]`。宽度够——40+56+40+40 加三处间距约 224dp，最窄的对话框也有 280dp。
- 播放对话框：进度条右侧加「导出视频」图标按钮。

**图标里不放对号**：播放对话框那个按钮没有确认语义。共用图标为**取景框 + 一道波峰**
（视频画幅 + 水体）；录音对话框里的确认语义由它作为 FAB 紧挨对号 FAB 这个形态承担。

**GLES 不可用则入口不出现**，而不是点了失败。判定点是
`WaveVisualizerFableSolHost.fallbackActive`，但它是运行时才知道的（GL 失败才
`activateCanvasFallback()`），所以按钮可见性必须响应该状态，不能在 `onCreateView` 里定死；
更稳妥的做法是导出前单独探一次能否建离线 EGL 上下文。

**点击后直接开始，不弹确认**：把「档位（HDR/SDR）· 预估体积 · 预估剩余时间」放进通知，
随实测滚动更新，随时可取消。

**理由**：取消的成本极低而确认的成本每次都付。任务随时可中断、完成前不落最终文件，用户
在通知里看到"3.6 GB / 剩余 24 分钟"后一秒就能取消，损失只有几秒 GPU 时间。为这个几乎无
代价的可逆性让每次导出都多一次弹窗点击不划算。**前提**：通知必须在启动后几秒内给出可信
数字，而不是先转十几秒圈。

---

## D15（2026-07-26）验收判据分三层，逐位层是硬门禁

**关键简化**：离线路径与实时路径共用 `FableSolGlRenderer` 的全部场景渲染代码，差异只有
三处——谁调用 `render()`、dt 从哪来、present 到哪个 surface。所以送进 GL 之前的一切必然
等价，**不需要逐像素验收**。

**① 逐位层（硬门禁，可进 CI）**

两条路径的差异**唯一来源是 dt 序列**：`update()` 是定步长累加器，物理只在 `PHYSICS_DT`
边界推进，真正分岔的是"音频帧在第几个子步边界被应用"。因此**给实时渲染器喂一串等间隔
1/120 的合成时间戳，它就应该与离线路径逐位相同**。

同一个 WAV、同一批 `FableSolFeatureFrame`、同一串合成 dt，比对每帧送去渲染的状态向量
（水位、九层高度场、mapper 输出、七境连续通道、`hdrGain`），要求 **max|Δ| = 0**。纯 Kotlin，
JVM 单测即可（先例：`FableSolCpuFrameCostProbe`；项目一贯做法："Python 对照帧逐位一致
max|Δ|=0"、"新旧树三配置帧级全等"）。

**② 事件层（真机，人工触发）**

真实录一段（带 vsync 抖动、可能掉档），再离线导出同一份 WAV，比对**因果事件序列**：
onset、七境状态转移、巨浪触发时刻、段落切换。要求序列完全一致、时刻差 ≤ 一个 hop（约 10.7ms）。
这层测的正是不变式真正关心的"因果链没变"。`sessions.md` 里记的"OfflineDirector 60fps
驱动路径的巨浪结果与实时路径不一致"，若当时有这层判据会当场暴露。

**③ 观感层（真机，人眼）**

录一段、导一段并排看。无法自动化，但只有它能发现构图、配色、时钟位置、圆角、投影、
padding 这些 ①② 完全覆盖不到的错误——而这些恰是本功能新增代码最多的部分。

①是合入门禁，②③是发布前的人工检查项。

---

## D16（2026-07-26）若干配套规则

- **导出前检查剩余存储空间**，按预估体积 × 1.2 判断，不够直接不启动并说明。
- **同时只跑一个导出**，第二次点击排队而非并发（两个 MediaCodec 实例 + 两套 GL 上下文
  会互相拖垮）。
- ~~**导出期间播放照常**，两者不冲突（离线导出不碰 AudioTrack）。~~ **D187 修订**：
  离线导出确实不碰 AudioTrack，但两者同进程抢 CPU。进度对话框在前台期间播放暂停、
  实时水体完全冻结；对话框关掉即恢复（播放不自动续播）。
- **产物文件名** = 音频附件名 + 时间戳。
- **老录音无重力轨迹** → 按竖直渲染，不提示（提示了用户也无法补救）。

---

## D17（2026-07-26）播放对话框按 Timely 字形着墨包络对齐，不按 advance 字槽对齐

播放对话框下方的进度条左缘与导出图标右缘，要对齐上方 `TimelyClockView` 的**可见数字**
边缘，而不是 View 边缘或 `onMeasure()` 上报的固有宽边缘。

`TimelyClockView` 的固有宽度是 `advance × 位数/冒号 × drawH`；`advance` 来自当前字体，
但真正字形横向只按 `0.8 × drawH` 绘制，且轮廓本身带字体各自的侧边留白。对 33 套现有
字形做资产级几何复现后，40dp 时左右留白随字体分别落在约 0.3～11.2dp、3.0～12.9dp；
因此 `wrap_content` 只能保留字体间宽度差异，不能让 View 边缘等于数字边缘。

决策：`TimelyClockView.contentLeftPx()/contentRightPx()` 按当前字体轮廓、实心/描边 stroke、
绘制缩放与 Stencil 秒钟 kerning 计算**稳定着墨包络**。包络覆盖该字体的全部数字组合，
不跟随末位数字逐秒变化，避免进度条宽度与按钮位置抖动。播放对话框在布局稳定后把进度条和
导出图标对齐该包络；图标另补偿矢量素材 viewport 内固定的 1dp 右侧留白。

---

## D18（2026-07-26）播放进度按 handle 外缘对齐；导出选项胶囊保留完整渐变

播放进度条左侧对齐 Timely 数字时，对齐对象是滑杆 **handle 的可见外缘**，不是轨道起点。
AOSP `AbsSeekBar` 在最小进度处的实际绘制坐标为
`paddingLeft - thumbOffset`；自定义 20dp thumb 经 `setThumb()` 自动得到 10dp
`thumbOffset`。因此进度条 View 继续落在数字着墨左缘，同时将左右 padding 设为实际
`thumbOffset`：handle 外缘正好与 View 边缘重合，轨道自然从 handle 中心开始。

音频海浪动画设置中的「帧率上限」「码率模式」胶囊，常驻颜色必须直接消费完整
`ThingBackground`：

- 选中态用完整渐变填充，保留 `color`、`endColor` 与 `orientation`。
- 未选中态用完整渐变描边，统一降低整条 shader 的 alpha，不把渐变降成单个颜色。
- 两种状态都继续进入 `applyUiAccent()` 的分档换色回调，因此换色过渡的每一档都会更新。

胶囊填充与描边不得以 `representativeColor()` 或单独的 `color` 代替完整渐变。

---

## D19（2026-07-26）进度条采用 2dp 光学校正；导出完成态改为三行同强调色操作

播放进度条在 D18 的 handle 外缘几何对齐基础上，整体再向左越过 Timely 稳定着墨左缘
**2dp**。这是固定的光学校正：handle 与轨道作为一组移动，右侧导出图标仍按数字右缘独立
对齐，不改变 Timely 字体感知的测量逻辑。

导出进度 Dialog 与标准取消/确定 Dialog 使用同一底部口径：移除根节点 12dp
`paddingBottom`，改为动作行 8dp `layout_marginBottom`。标题不再维护独立的
`fablesol_export_dialog_title` 展示值，直接复用通知所用的 `fablesol_export_title`，中文均为
「导出音频海浪动画视频」。

导出成功后不再把进度布局就地改成横向双按钮，而是切换到现有
`ThreeActionsAlertDialogFragment` 的三行纵向结构：

1. 分享；
2. 添加到附件；
3. 保存到相册。

三项文字与涟漪都使用当前记事的完整强调背景，不区分主次。第三项是自定义操作而不是取消；
按返回键或点击外部只关闭 Dialog，不得误触发“保存到相册”。

`FableSolExportSink` 在编码成功时已把产物提交到 `Movies/EverythingDone/` 和
`MediaStore`（D12）。因此“保存到相册”执行幂等的可见性确认：现代系统直接确认已提交的
MediaStore 条目，旧系统再触发一次路径扫描；不得复制出第二份同名视频。

---

## D20（2026-07-26）完成态恢复双按钮，并展示产物规格、大小与位置

D19 的三行完成操作被本决策取代。由于 `FableSolExportSink` 在成功时已经默认把视频提交到
公共相册，“保存到相册”既不是待执行动作，也不应作为冗余确认入口。

导出完成后继续使用导出进度 Dialog 自身的横向双按钮完成态，只提供：

1. 分享；
2. 添加到附件。

两个按钮不区分主次，文字与触摸涟漪都使用当前记事的完整 `ThingBackground`，包含渐变终点
与方向。完成信息按多行结构展示：

- 导出完成；
- 规格：HDR/SDR、实际帧率；
- 视频大小：成功提交后的实际文件大小；
- 位置：用户可识别的公共相册相对路径与文件名。

大小和位置属于导出结果数据，由 `FableSolExportSink` 在 `commit()` 后提供并随
`FableSolVideoExportBus.State.Done` 传递；Dialog 不通过猜测码率或重新扫描目录推算。

---

## D21（2026-07-26）成功边界以后封装完成为准；候选档、任务状态和旧版落地均事务化

第四次外部静态评审确认，之前虽然已经检查 `commit()`，但“成功”边界仍然放早了：
`MediaMuxer.stop()` 只在 `release()` 中执行且异常被吞掉，MediaStore 可能先解除
`IS_PENDING`，再去写 MP4 的索引。现在一次成功必须依次满足：

1. 音视频两轨都收到 EOS；
2. `MediaMuxer.stop()` 与 `release()` 成功；
3. 输出 `MediaFormat` 证实 10-bit/HDR profile，并保留或补齐对应的色彩标记；
4. MediaStore 更新成功，或旧系统 MediaScanner 回调确认已经入库。

上述任一步失败，产物都不发布。编码候选从“只重试 configure/start”扩大为完整事务：每档
重新打开音频、创建 codec/muxer/EGL、初始化渲染器、编码并封装；首帧交换、输出格式、
`addTrack()` 或后续编码失败都清理后从零尝试下一档。HDR 档还必须在 `resize()` 后确认
FP16 scene target 没有静默回退到 RGBA8。

编码尺寸按具体 `VideoCapabilities.widthAlignment/heightAlignment` 向上对齐，只扩大中性
画框并重新居中卡片，不改变卡片像素尺寸、density 或水体物理容器。H.264 阶梯补齐
High → Main → Baseline；设置 profile 时同时设置按 D152 计算的最低充分 level，不再直接取
该编码器广告的最高 level。

API 26–28 不再用 `getExternalFilesDir()` 伪装公共相册：发起导出前申请
`WRITE_EXTERNAL_STORAGE`，只写公共 `Movies/EverythingDone/`。目标文件用
`createNewFile()` 原子占位并在冲突时改名，`discard()` 只删除本 sink 本次真正拥有的文件。

Service 的队列切换、前台状态与 `stopSelfResult()` 全部串行到主线程，工作线程只执行一次
完整导出；取消按 `jobId` 精确作用于当前或排队任务。Bus 改为每任务状态表，终态不可被已经
排队的旧进度覆盖；系统超时在回调中立即给当前与所有排队任务写入失败终态。

恒定质量模式不再拿提示码率做硬性体积门禁：开始前保留 64MB 实际可用空间，编码途中每
30 帧复查；恒定码率模式仍按视频码率 + AAC 192kbps 估算并留 20% 余量。

---

## D22（2026-07-26）完成通知复用已发布结果；导出图标采用浅水波

导出成功后先构造唯一的 `FableSolVideoExportBus.State.Done`，完成 Dialog 与系统通知都
从该状态读取 HDR/SDR、实际帧率、实际文件大小、保存位置和分享 URI。通知复用
`fablesol_export_dialog_done` 的四行文案，不再单独拼接一份缺少大小和位置的摘要，也不再
二次查询输出文件，避免两处展示结果分叉。

导出视频图标继续使用原有圆角视频画框；内部水体从高振幅实心波带改为圆头描边的开放
贝塞尔曲线。波形保持低矮、连续和圆润，避免在 24dp 图标中被识别成山峰。

---

## D23（2026-07-26）分享画布按 64px 对齐；交互图形按真实边界与记事身份绘制

微信等分享链路会再次解码或转码视频。只满足厂商 codec 宣称的 2/4/8/16px 最小对齐时，
默认字形可能生成 `1106×1444` 一类尺寸，外部链路若按更大的编码块处理，余数会只从右侧
或底部丢弃。现在每个候选尺寸都按“codec 对齐与 64px 的最小公倍数”向上扩展；新增像素
只重绘中性画框，并重新居中原卡片，卡片像素、density、水体物理容器和字形决定的宽高比
均不改变。编码器输出若携带 crop keys，则必须明确覆盖 `[0, width-1] × [0, height-1]`
完整画幅，否则该候选失败并进入下一档。

导出进度/完成 Dialog 的动作文字使用 `gravity=center`，以整个 36dp touch ripple View
为边界做水平、垂直居中，不再依赖字体默认顶部基线与 padding 恰好抵消。

导出视频图标的水面不再人工拼正弦波。用 Python 模拟器对真实录音离线渲染，在 4 秒帧中
逐列提取顶层水面（原始范围 y=542～551px），再拟合成 24dp 图标内的低振幅开放贝塞尔
轮廓；圆角视频画框不变。音频附件播放 Dialog 中，该图标字形直接使用完整
`ThingBackground`，包括渐变端点和方向，触摸 ripple 仍保持中性的 App Chrome 圆形反馈。

播放/暂停按钮维持 56dp touch ripple，但主图标从 `centerInside` 改为 `fitCenter`，在现有
14dp padding 内由 24dp 放大到 28dp；上一曲和下一曲仍是 48dp ripple 与 24dp 图标。

---

## D24（2026-07-26）HDR 开关以一帧真实编码为门；HDR 帧率降级先于 SDR

设备广告 Main10 profile 只代表候选存在，不能证明整条 HDR 导出链可用。设置 Dialog 在后台
对正式导出的最大画布执行一次短探测：复用实际 `FableSolExportTier`、编码参数、
`FableSolExportEncoder`、10-bit BT.2020/HLG `FableSolExportEgl` 和输出格式校验，交换一帧
后完整收尾。任一环节失败都视为不支持，清理临时 MP4，并将“导出 HDR 视频”取消选中且置灰。
探测期间同样保持禁用，避免能力未知时写入无效偏好。

帧率设置是上限。用户请求 HDR 且选择 120fps 时，降级顺序固定为：

1. HDR 120fps；
2. HDR 60fps；
3. SDR 120fps；
4. SDR 60fps。

这保证 120fps HDR 不可用、60fps HDR 可用时仍然得到 HDR，而不是在同一帧率内先静默落到
SDR。设置开关的能力探测使用同一顺序中的 HDR 部分，避免界面判定与正式导出分叉。

音频自然播放结束时，原解码线程已经退出且释放资源，不能再接受 seek。结束后第一次 seek
必须用原路径创建一条暂停的新线程，并把初始 seek 请求带入新解码器；下一次播放点击再唤醒
这条新线程。主播放/暂停图标通过 56dp 容器内 12dp padding 得到精确 32dp 可见尺寸，ripple
外框不变。

---

## D25（2026-07-26）导出图标改用 Material `video_frame_save`；HDR 探测延后并缓存

导出图标不再表达水体本身，改用 Google Material Symbols Outlined 的
`video_frame_save`。保留官方播放三角、保存箭头和整体比例，只补齐原取景角标在顶部中央
与左侧中央的两段缺口，使左边框、上边框连续完整。两处入口继续共用同一资源，播放 Dialog
仍以完整 `ThingBackground`（含渐变）着色。

HDR 实编码结论是设备能力，不是每次打开设置都应重新执行的动态状态。探测签名由探测实现
版本、App `VERSION_CODE`、Android API 和 `Build.FINGERPRINT` 组成：成功结果在签名不变时
长期复用；失败结果缓存 24 小时，之后允许恢复一次真实探测，避免临时 codec 占用造成永久
误判。进程内结果可立即恢复；首次进程启动后的持久化读取和必要的实际编码均在 Dialog 首帧
之后以后台低优先级执行。

实际探测继续创建正式 `FableSolExportEncoder`、RGB10_A2 BT.2020/HLG EGL surface，交换并
封装一帧，验证最终 profile、画幅和色彩元数据；不再创建整套 `FableSolGlRenderer`、编译
水体 shader 或分配完整场景缓冲。FP16 扩展仍由 `FableSolExportEgl.probe()` 门控，正式导出
仍验证实际 scene targets。这样能力判定仍覆盖真实 HDR 编码链，同时不把水体渲染初始化成本
叠到设置 Dialog 上。CQ 能力枚举的结果（包括“不支持”）另做进程缓存，避免重复打开时再次
同步枚举 codec。

不可用的“导出 HDR 视频”标签追加与顶部 HDR 高光增强相同的“设备不支持”本地化文案；
标签保持 enabled 文本色，仅使用相同的 `0.5` alpha，不再叠加系统 disabled 色和整行
`0.38` alpha。

---

## D26（2026-07-26）复杂导出图标使用 22dp；设置术语改为“编码模式”

`video_frame_save` 同时包含画框、播放三角、保存箭头，24dp 放进两个入口时视觉密度偏高。
录音 Dialog 的 FAB 保持 56dp、padding 从 16dp 增至 17dp；音频附件 Dialog 的按钮保持
40dp、padding 从 8dp 增至 9dp。两处可见图标统一为 22dp，touch ripple 与按钮位置不变。

设置标签使用更上位且与两个选项都相符的“编码模式”，不再称“码率模式”；恒定质量说明从
“体积随画面复杂度变化”明确为“视频大小随画面复杂度变化”。13 套语言资源同步表达
“Encoding mode”和“Video size varies with scene complexity”的对应语义。

---

## D27（2026-07-27）传递函数是与编码档位并列的独立一轴，HDR 默认优先 PQ

此前整条 HDR 通路只认 HLG 一种 EGL 色彩空间。这带来两个问题：只提供 `bt2020_pq` 扩展的
设备会被整体判成不支持 HDR 导出；而 HLG 按 BT.2408 把漫反射白定在信号 0.75，其上只剩约
3.77 倍余量，用户 HDR 强度上限却是 9.6，超出部分只能靠软肩压缩——强度调到 4 以上几乎
看不出区别，等于上限形同虚设。

因此把传递函数（SDR / HLG / PQ）从编码档位里拆出来，成为 `FableSolExportTransfer` 这条
独立的轴：同一个 HEVC Main10 编码器既可以出 HLG 也可以出 PQ。降级顺序是**先把一种传递
函数的帧率阶梯走完再换下一种**，SDR 永远最后；否则 120fps PQ 失败会抢先落到 120fps HLG，
而余量更大的 60fps PQ 本来可用。

默认优先 PQ：它是绝对亮度、上限 10000 nits，9.6 倍 SDR 白约合 1949 nits，完全放得下，
一点都不用压。设置提供「自动 / HDR10 / HLG」；选 HLG 的唯一理由是产物主要在不支持 HDR
的设备上回看时，被压成普通画面更温和。HDR10 产物写入 `KEY_HDR_STATIC_INFO`——我们的峰值
可由 HDR 强度 × 203 nits 精确算出，填的是准确值而非实拍那样的估计。

`FEATURE_HlgEditing` 只在 `transfer == HLG` 且 API ≥ 35 时作为过滤条件：它是 API 35 才
加入的能力位，拿它过滤 PQ 档是张冠李戴，在 API 34 上查它更会把所有 HDR 候选静默筛光。

~~杜比视界不做：它需要授权，还需要逐帧 RPU 动态映射元数据，而后者没有公开接口。~~
**这一条在 2026-07-27 被证伪，见 D29。** 当时没有查证就下了结论：Dolby 官方给第三方开发者
的样例（`DolbyLaboratories/dolby-vision-editor`，BSD-3）用的就是标准 `MediaCodec` + surface
输入，应用**不需要**自己产 RPU，元数据层由编码器生成。

---

## D28（2026-07-27）色彩范围以编码器回报为准，色域与传递函数以我们为准

编码器输出格式与申请不符时，此前一律判该档不可用。三星 S23 Ultra 上这条规则造成整机
HDR 失效：高通编码器一律把色彩范围回报为 full（1），而我们申请 limited（2），四档 HDR
候选全部抛 `IllegalStateException: Encoder changed color-range from 2 to 1`——色彩空间与
Main10 编码器一样不缺，纯粹被自己的校验挡死。

区分标准是**谁才是权威**：

- **色域（BT.2020）与传递函数（PQ / HLG）**是我们绘制的像素自身的属性，由 EGL 表面的
  色彩空间和导出 shader 钉死，编码器改变不了它们的含义。回报不符说明这一档确实做不到
  我们要的事，仍判不可用。
- **色彩范围**描述的是编码器自己执行的那一步 RGB→YUV 转换选了哪一档。它做了什么只有
  它自己知道，所以采纳它回报的值并写进 muxer 轨道格式，让容器标记与码流一致；只有在
  编码器根本不回显该键时，才补上我们请求的 limited，保证容器一定带标记。

推论：能力探测结果的缓存签名里必须含探测契约版本，且**每次改动接受标准都要推进它**，
否则被旧标准误判为不支持的机器会继续沿用错误结论（否定结果缓存 24 小时）。

---

## D29（2026-07-27）四种 HDR 输出格式全部开放给用户，但必须实测编出一帧才出现在界面上

用户要求把 HDR 格式做成可选，并在界面上说明各自的区别。调研后确定可并列的是**四种**，
不是五种——PQ 与 HDR10 是同一件事的两个说法（HDR10 = PQ 曲线 + BT.2020 + 静态母版元数据），
并列会让人以为是两个选项。

引入 `FableSolExportHdrFormat`（HDR10 / HDR10+ / HLG / 杜比视界）作为**第三条轴**：
传递函数决定我们怎么画（EGL 色彩空间 + 导出 shader），格式还额外决定用哪个编码器、
写什么元数据。HDR10 与 HDR10+ 共用 PQ，杜比视界与 HLG 共用 HLG。

### 杜比视界可以做（推翻 D27）

依据是 Dolby 官方给第三方开发者的样例 `DolbyLaboratories/dolby-vision-editor`
（BSD-3，`editor/.../VideoEncoder.java`）。它编 profile 8.4 的做法是：

```java
format.setString(KEY_MIME, MIMETYPE_VIDEO_DOLBY_VISION);
format.setInteger(KEY_PROFILE, DolbyVisionProfileDvheSt);
format.setInteger(KEY_COLOR_TRANSFER, COLOR_TRANSFER_HLG);
format.setInteger(KEY_COLOR_STANDARD, COLOR_STANDARD_BT2020);
format.setInteger(KEY_COLOR_RANGE, COLOR_RANGE_LIMITED);
format.setInteger(KEY_LEVEL, getDolbyVisionLevel(fps, resolution));
format.setInteger(KEY_COLOR_FORMAT, COLOR_FormatSurface);
codec.configure(format, null, null, CONFIGURE_FLAG_ENCODE);
setInputSurface(codec.createInputSurface());
```

三个关键点：**用的是 surface 输入**（与我们整条链路一致）；**应用不提供任何 RPU**，
元数据层由编码器生成；产物直接 `muxer.addTrack(encoderOutputFormat)` 封进 MP4。
OPPO Find X9 Ultra 上枚举到的 `c2.qti.dv.encoder` profile 值 256 正是 `DolbyVisionProfileDvheSt`。

要注意 8.4 的基层是 **HLG**，所以它的高光余量与 HLG 相同（约 3.77 倍），并不比 HDR10 大。
它的价值在动态元数据带来的更好色调映射，不在余量。

level 不能取编码器广告的最高档，要按像素率阶梯现算再取"刚好够用"的一档，且必须在 64px
分享对齐**之后**算——对齐会改变像素率。

### HDR10+ 只能做到"设备愿意生成就有"

`MediaCodec.PARAMETER_KEY_HDR10_PLUS_INFO` 的文档明确写着：编码器在 **surface 输入模式下
不应设置该参数**。我们整条链路正是 surface 输入，所以逐场景动态元数据（ST 2094-40）**我们
提供不了**。选它意味着元数据完全由设备编码器自行生成，内容既不能干预也无从校验。

因此自动档的顺序是 HDR10 → HDR10+ → HLG → 杜比视界：HDR10 排第一而不是 HDR10+，因为我们
的峰值本来就能精确写进静态元数据，拿一个验证不了的东西替换一个已经准确的东西不是升级。

### 只有实测编出一帧的格式才出现在界面上

`MediaCodecList` 广告支持而 `configure()` 时静默降级是真实存在的。因此能力探测改为**逐格式
各走一遍完整的真实编码 + 封装**，设置页只摆通过的那几个。相应地，HDR10+ 与杜比视界不接受
profile 的"等价替换"（HDR10 申请 Main10 而回报 Main10HDR10 是良性的具体化，HDR10+ 被降成
Main10 则是名不副实）。

代价是探测从"一档成功即收工"变成最多四轮，但它在后台跑且带缓存；换来的是界面上没有一个
选了却不生效的选项。

---

## D30（2026-07-27）广告能力位一律不作为门禁；EGL config 必须是阶梯

三台设备各暴露一个"被自己人挡住"的问题，根子是同一个：**拿设备广告的某一位去提前否决，
而不是让真实编码来判定**。

### 能力位不再作为门禁

三星 S23 Ultra 上 HLG 的诊断是"没有编码器广告支持这个 profile"，但这台机器 HEVC Main10
编码器一整排、EGL 的 HLG 色彩空间也有。原因是 `FEATURE_HlgEditing` 那道筛：它的高通编码器
一个都不广告这个能力位，于是 API 35 上 HLG 候选被整批筛光。

这个能力位问的本来就不是我们要的事——它描述的是「HLG 编辑」那套转码用例，而我们只是把一张
已经编好的 HLG 画面交给 Main10 编码器。既然 D29 已经确立"每一档都要真编一帧才算数"，
再留一个语义不对的广告位提前否决就只有害处。**已删除，并由源码契约测试钉住不许回来。**

### `eglChooseConfig` 必须沿阶梯降级

华为平板（Kirin，`OMX.hisi.video.encoder.hevc`）整机 HDR 不可用，每一档都失败在
`eglChooseConfig failed (tenBit=true)`。原先只试一种组合：10/10/10 + alpha 2 +
`EGL_RECORDABLE_ANDROID`。失败的多半不是"没有 10-bit"，而是没有**同时**满足 alpha≥2 与
recordable 的那一个 config——不少厂商驱动不给 10-bit config 打 recordable 标记。

改为四级阶梯：`RGB10_A2+recordable` → `RGB10+recordable` → `RGB10_A2` → `RGB10`。
放弃 recordable 是安全的：输入表面本来就来自 `MediaCodec.createInputSurface()`，
这个属性只是给驱动的提示。同时把"这台机器能建起哪一档 10-bit 表面"单独探出来进诊断——
广告了 PQ 扩展**不等于**建得起 10-bit 表面，这两件事此前被混为一谈。

### HDR10+ 在我们这条链路上做不到（有实证）

三星上 HDR10+ 的失败是 `Encoder changed profile 8192 to 2`（8192 =
`HEVCProfileMain10HDR10Plus`，2 = `HEVCProfileMain10`）：编码器收下了配置，却明确表示
它只会产出 HDR10。

这不是配置写错。HDR10+ 的动态元数据（ST 2094-40）是**随码流内嵌的 SEI**，来源只有两个：
应用逐帧提供，或编码器自行分析生成。前者被 Android 明确排除——`PARAMETER_KEY_HDR10_PLUS_INFO`
的文档写着"编码器在 surface 输入模式下不应设置该参数"，而我们整条链路正是 surface 输入；
后者这台设备不做。所以没有第三条路。

**明确不去伪造元数据**：技术上可以在配置时塞一份自造的 ST 2094-40 blob 赌编码器认账，
但那份 blob 里的百分位分布是我们没有测过的数，等于把一个验证不了的东西写进用户的文件。
与 D29 里"不拿验证不了的东西替换已经准确的东西"是同一条原则。

失败原因改为翻成人话显示，而不是把原始异常串甩给用户。

### HDR Vivid 不做

华为平板的 HDR 格式是 HDR Vivid（菁彩 HDR，GY/T 358-2022）。**Android 平台不支持它**：
官方支持格式页列出的 HDR 视频格式只有 HLG10、HDR10、HDR10+、Dolby Vision 8.4 四种，
通篇没有 HDR Vivid。它的元数据同样是 T.35 SEI，处境与 HDR10+ 完全一样，而且连 AOSP 的
profile 常量都没有；华为自己的编码路径要么走 HarmonyOS 的接口，要么由相机管线提供带
Vivid 元数据的 buffer，两条都不是第三方应用能从 GL 画面走的。

值得注意的是，我们实现的四种格式**正好就是 Android 官方列的那四种**——这条线已经走到头了。
对华为平板而言可达成的结果是 HDR10，它的屏幕照样按 HDR 呈现。

---

## D31（2026-07-27）"HDR10+ 做不到"只对当前链路成立；字节缓冲通路先探后建

D30 写的是"HDR10+ 做不到"。这个陈述**范围写错了**：做不到的是**我们当前这条 surface 输入
链路**，不是这台设备。两者是两个命题。

Android 对 HDR10+ 编码的官方路径是**字节缓冲输入**：`PARAMETER_KEY_HDR10_PLUS_INFO` 唯一
被允许使用的就是这种模式，而 AOSP CTS 的 HDR10+ 编码用例（`HDREncoderTestBase`）走的正是
它——逐帧 `setParameters` 送元数据。这意味着凡是广告 `HEVCProfileMain10HDR10Plus` 的设备，
在这条路上都必须真能编，否则过不了 CTS。

因此先建一个**与导出管线完全隔离**的探测 `FableSolHdr10PlusProbe`：按 `COLOR_FormatYUVP010`
配置编码器、喂一帧平场、读输出格式回报的 profile。分两问——「裸通路」（只切模式）与
「带元数据」（同时送 ST 2094-40 载荷）——才分得清失败是通路不通还是载荷写错。

### 更正 D30 里"不伪造元数据"的适用范围

那句话在 surface 模式下成立：画面已经交给编码器，任何统计量都只能猜。但在字节缓冲模式下
**画面在我们手里**，ST 2094-40 要求的 maxscl、average_maxrgb、各百分位分布全都是**测得出来
的实数**。所以这条路上元数据不是诚信问题，是工作量问题。

### 真要建，代价是两件编码器原本代劳的事

1. **RGB→P010**：改渲到离屏 FBO，再做 BT.2020 非恒定亮度、limited range 的 10-bit YUV 转换
   （宜用 GPU 两趟出 Y 与 CbCr 平面再读回，避免 CPU 逐像素）。错了会直接表现为颜色不对——
   好在错得显眼。
2. **逐帧统计**：GPU 归约出 maxscl / average_maxrgb，再由一张降采样的 maxRGB 图在 CPU 上算
   百分位。`tone_mapping_flag` 取 0：我们没有任何艺术调整要表达，不写曲线比编一条出来诚实。

收益需要如实评估：HDR10+ 相对 HDR10 多出来的是**动态**色调映射，而我们的产物是一整段连续
场景、统计量缓变，"动态"能买到的比电影那种多镜头内容少得多；而 HDR10 的静态元数据我们本来
就写的是精确值。所以**先探后建，不见到设备认账不动管线**。

ST 2094-40 载荷是逐位打包的（单窗口 + 9 个百分位 + 不带曲线 = 387 位 = 49 字节），
写错一位后面全部错位且极难反查，因此固定头与总长由 `FableSolHdr10PlusPayloadTest` 钉住。

---

## D32（2026-07-27）缓存签名必须含发布时间戳；杜比视界仅保留 8.4；HDR10+ 改按 SEI 判定

### 缓存签名里的"版本号"是假的

`cacheSignature()` 用 `BuildConfig.VERSION_CODE` 做区分量，而它在 `app/build.gradle` 里是
**写死的 43**，两次 debug 发布之间不变。后果：D30 删掉 `FEATURE_HlgEditing` 之后，设置页
仍从缓存读出改动前的结论——用户截图里"HLG：没有编码器广告支持这个 profile"连**措辞**都还是
D29 时期的，而 D30 已经把它改成了另一句。**三行失败原因全是旧数据。**

改用 `R.string.debug_update_code`：发布任务 `generateDebugUpdateValues` 生成的时间戳，
每发一版必变（本地未发布构建为 "0"）。`PROBE_CONTRACT_VERSION` 保留，供本地反复构建时手动
作废。源码契约测试钉住签名里必须出现这个资源。

这是 D28 那条推论的第二次踩坑——当时写的是"每次改动接受标准都要推进契约版本"，但真正的
问题是**不该依赖一个需要人记得去改的量**。现在它自动了。

### 杜比视界仅保留 8.4

杜比视界 8.4 使用 HLG 兼容基层，公开的 Dolby Android 第三方编辑样例明确演示并声明支持
编码/转码到 8.4。杜比视界 8.1 虽同属 Profile 8，但要求 PQ/HDR10 兼容基层；现有真机在明确
申请 PQ 时把传递函数改回 HLG，不能据此生成 8.1。Profile 5 则要求单层 IPT-PQ-c2 表示及相应
杜比元数据，Android/Dolby 面向普通第三方应用的公开编码路径未提供该创作能力。

因此产品格式集合不再把 Profile 5 或 8.1 作为可探测、可选择、可自动尝试的候选，只保留
杜比视界 8.4。Profile 常量存在或解码器能够播放某个 Profile，不等于第三方应用具备对应的编码与
颜色转换能力。有效自动顺序由 D48 与 D141 统一为
`HDR10+ → 杜比视界 8.4 → HDR10 → HLG`。

### HDR10+ 不能用输出 profile 判定

D31 的探测按输出格式回报的 `KEY_PROFILE` 判断，得出"降回 profile 2 = 不产 HDR10+"。
**这个判据是错的**：HEVC 层面没有"HDR10+ profile"——码流的 `general_profile_idc` 本来就是
Main10（2），HDR10+ 只是额外一段 T.35 SEI；`HEVCProfileMain10HDR10Plus`（8192）是 Android
框架层的合成常量。编码器回报 2 完全可能只是在如实陈述码流 profile。

改为**直接在输出字节里匹配 SEI 签名** `B5 00 3C 00 01`（T.35 国家码 + terminal_provider_code
+ oriented_code 高位）。这五个字节里没有连续两个 `0x00`，不会被防竞争字节 `0x03` 打断，
可按字节直接匹配。找到即为实证，与回报的 profile 无关。

---

## D33（2026-07-27）HDR10+ 走字节缓冲输入；杜比视界的天花板由设备定

### 实证结论（三星 S23 Ultra / OPPO Find X9 Ultra）

| 问题 | 实测结果 |
|---|---|
| HDR10+ 换字节缓冲能不能编 | **能**。裸通路无 SEI，带元数据后「码流带 HDR10+ SEI」 |
| 杜比视界 8.1（PQ 基层） | **不能**。`Encoder changed color-transfer from 6 to 7`——编码器把 PQ 改回 HLG |
| 杜比视界 8.4（HLG 基层） | **能**，已进实测通过列表 |
| 杜比视界 profile 5 | OPPO 只广告 profile 8，无从谈起 |

### HDR10+ 的判据不是 profile，是 SEI

D31 用输出 `KEY_PROFILE` 判定是错的，D32 已在探测里改正，这里落到正式通路：
`FableSolExportHdrFormat.requiresExactProfile` 去掉 HDR10+，改由
`FableSolExportEncoder.hdr10PlusSeiSeen` 在写样本时扫描码流签名 `B5 00 3C 00 01` 来确认。
探测与正式导出都以"码流里真有那段 SEI"为准；没有就不给这个选项。

### 字节缓冲通路

`FableSolExportP010Bridge` 承担编码器原本代劳的两件事，三趟 GPU：

1. 呈现——`FableSolExportPresenter.targetFramebufferId` 指向离屏 RGB10_A2；
2. 转换——`p010_luma.frag` / `p010_chroma.frag` 产出 P010 的两个平面；
3. 统计——`p010_stats.frag` 归约到 32×32，供 ST 2094-40 的 maxscl / 均值 / 分位点。

两个实现约束值得记住：

- **输出目标必须是 RGBA8，不能用 16 位整数纹理**。ES 3.0 只保证
  `GL_RGBA` + `GL_UNSIGNED_BYTE` 这一组 glReadPixels 组合可用，整数纹理的回读格式是实现
  自定的。一个 RGBA8 texel 装两个 16 位样本，回读字节序直接就是 P010。
- **统计必须在 GPU 上做**。CPU 逐像素扫全帧在 Kotlin 里每帧几十毫秒，乘上 120fps 完全不能
  接受；归约到 1024 个块之后 CPU 侧只剩一千来次迭代。块内取 **max** 而不是取平均，才不会漏掉
  水面上那些很小的高光点。

`FableSolExportEgl` 增加离屏（pbuffer）模式：字节缓冲下没有 input surface，但仍需要 GL
上下文；pbuffer 不打色彩空间属性，传递函数由导出 shader 自己编码。

### 更正 D30/D32 里"不伪造元数据"的适用范围（第二次）

那句话只在 surface 输入下成立。字节缓冲模式下画面在我们手里，maxscl / average_maxrgb /
分位点全是**量出来的**。GPU 归约的 8 位量化在峰值附近有百分之几的误差——那是**降低精度的
测量**，不是编造，且对"告诉播放端按多高的峰值还原"完全够用。`tone_mapping_flag` 仍取 0：
我们没有艺术调整要表达，不写贝塞尔曲线比编一条出来诚实。

### 杜比视界 Profile 5 不进入产品候选

Profile 5 是单层 IPT-PQ-c2 表示，不包含 HDR10 或 HLG 兼容基层。当前共用 BT.2020/PQ 绘制路径
没有公开、可验证的 IPT-PQ-c2 与 RPU 创作接口；仅让编码器接受一个 Dolby Vision MIME/Profile
不能证明颜色表示正确。AOSP 还明确把 `DolbyVisionProfileDvheDtr` 标为 `dvhe.04`，真正的
`dvhe.05` 是 `DolbyVisionProfileDvheStn`；此前所谓 Profile 5 候选申请的常量本身也不正确。
该候选由 D141 直接移除，不再向用户说明为可用但兼容性较差的格式。

---

## D34（2026-07-27）Main10 必须在 HDR10+ 的可接受回报值里——D33 只修了一半

D32/D33 认定"HDR10+ 不能拿 profile 当判据"并把它移出 `requiresExactProfile`，但
`acceptsTenBitProfile` 的白名单**没有同步加上 `HEVCProfileMain10`**。于是：

```
申请 HEVCProfileMain10HDR10Plus (8192) → 编码器回报 HEVCProfileMain10 (2)
  requiresExactProfile = false           → 不再直接拒
  白名单 = {Main10HDR10, Main10HDR10Plus} → 2 不在其中 → 仍然判失败
```

结果 HDR10+ 永远进不了「实测通过」列表，设置页的胶囊与自动档文本一直显示 HDR10——用户
正是这样发现的。**修一半等于没修**：解除了一道门禁，却没检查它下游那道门认不认新值。

白名单加入 `HEVCProfileMain10` / `AV1ProfileMain10`。这不会放松 HDR10 / HLG 的判定——
它们申请的就是 Main10，命中的是前面那行相等判断，走不到白名单。HDR10+ 的真正判据仍是
`FableSolExportEncoder.hdr10PlusSeiSeen`（码流里有没有那段 SEI），profile 编号说明不了任何事。

由 `FableSolExportHdrFormatTest.hdr10PlusAcceptsMain10AsTheReportedProfile` 钉住，
同时钉住 8-bit Main 仍要拒（那才是真降档）、以及杜比视界仍只认原样回报。

顺带两处：交给编码器的帧长度改用规范的 `stride × sliceHeight × 3 / 2`（部分实现按此校验）；
HDR10+ 的档位名去掉重复的格式词（曾出现「HDR10+ HEVC Main10 HDR10+」）。

---

## D35（2026-07-27）杜比视界 8.1 在 Android 上不成立；厂商参数改为向设备查询

### OPPO 同样能编 HDR10+

用户那张 OPPO 截图里 HDR10+ 的失败是 D34 修掉的那个 bug（白名单不认 Main10），截图版本
早于修复。同一张截图的独立探测行——`带元数据 码流带 HDR10+ SEI`——不经过那道校验，说的是
设备真话：**两台设备都能编 HDR10+**。

### 杜比视界 8.1：三条证据，结论是做不到

1. OPPO 只广告 profile 8；
2. 明确要求 PQ，编码器明确改回 HLG（`changed color-transfer from 6 to 7`）；
3. **Dolby 官方第三方样例的发行说明只声称"encoding and transcoding to Dolby Vision 8.4"**，
   8.1 与 profile 5 一字未提，并注明"平台 SOC 必须支持 P010 编码才能产出 8.4"。

即这不只是这台机器的限制——Android 上第三方能走到的杜比视界就是 8.4。

**不放松传递函数校验。** 与 D28 的色彩范围不同：色彩范围是编码器自己那步转换的属性，编码器
是权威；而传递函数是**我们画出来的像素的属性**。编码器说 HLG 而我们画的是 PQ，放行只会产出
一个标着 HLG、内容却是 PQ 的文件，肉眼可见地错。所以只能判这一档不可用。

### 与其猜厂商键名，不如问设备

Qualcomm 的 MediaCodec 扩展文档站是 JS 渲染的，抓不到内容。改用 API 31 的
`MediaCodec.getSupportedVendorParameters()` **直接向编码器查询它自己的私有参数**，按
dv / dolby / hdr / profile / color / transfer 过滤后进诊断行。有相关旋钮就去试，一个都没有
就是真的没有别的办法——这比照着文档猜键名可靠得多，也符合本功能一贯的"实测才算"。

同时把 `changed color-transfer` 这类失败翻成人话，不再甩原始异常。

---

## D36（2026-07-27）HDR10+ 的元数据是动态的；本功能优先 HDR10+ 而非杜比视界 8.4

### 官方支持格式页那一格不可信

`developer.android.com/media/platform/supported-formats` 的 HDR 表把 HDR10+ 的「元数据」
一栏填成 **静态**。这一格要么是笔误，要么只填了它**同时也带**的那一半。反证三条，都来自
同一家的文档与我们自己的实测：

- `MediaCodec.PARAMETER_KEY_HDR10_PLUS_INFO`：「设置**下一个入队输入帧**的 HDR10+ 元数据」，
  格式为 **ST 2094-40**——ST 2094 系列就是动态元数据标准；
- `MediaFormat.KEY_HDR10_PLUS_INFO`：该数据「**每一帧输出都可能不同**」；
- 本项目实测：不逐帧提供，码流里就没有那段 SEI。

准确表述是**两份都有**：HDR10+ = 静态母版元数据（峰值 / MaxCLL / MaxFALL）+ 逐场景 ST 2094-40。
我们的实现也正是两份都写。

### 对本功能，HDR10+ 优于杜比视界 8.4

决定性的是**基层曲线**，不是元数据：

| | HDR10+ | 杜比视界 8.4 |
|---|---|---|
| 基层 | PQ，绝对亮度 | HLG，相对亮度 |
| 余量 | 9.6 × 203 ≈ 1949 尼特，不用压 | 普通白之上约 3.77 倍，强度过 4 即压 |
| 动态元数据来源 | **我们逐帧实测**，内容可核 | 设备编码器生成，不可见不可控 |
| 回落 | 按 HDR10 播 | 按 HLG 播 |

FableSol 的视觉身份恰恰是水面上远高于漫反射白的细亮高光与星芒——那正是 HLG 压掉的一段，
所以这个差距对本功能不是可有可无的。此外杜比视界的 RPU 描述的是一个**顶端已被压掉**的信号，
再好的显示端适配也换不回丢失的余量。

`AUTO_ORDER` 已经是这个顺序（HDR10+ 在 8.4 之前），无需改动。**唯一的例外**：用户长期把 HDR
强度放在 3 以下时余量差距用不上，两者接近，届时取决于播放设备。

### 连带修正：HDR10+ 的界面说明已过时

旧文案写「动态元数据只能由设备自行生成，我们既设不了也验不了」——那是 surface 输入时的实情，
D33 换成字节缓冲输入之后正好相反。13 套语言全部改为「由我们逐帧从画面实测得出」。
**界面上留一句与实现相反的话，比没有这句话更糟。**

---

## D37（2026-07-27）HDR 开关并入格式选择；产物与提示带上格式与真实码率

### 用户实测：杜比视界 8.4 的动态色调映射确实在工作

观察是"一开始白色卡片背景更亮，银丝星芒出现后背景反而暗下去衬托高光"——这正是逐场景动态
元数据的作用：显示设备按每段画面的亮度分布重新分配它有限的亮度预算。

**这暴露了 D36 结论的一个盲区。** D36 判定 HDR10+ 优于 8.4，依据是余量（PQ 满量程 vs HLG
约 3.77 倍），那部分成立；但我们发出的 ST 2094-40 里 `tone_mapping_flag = 0`——**没有色调
映射曲线**，所以 HDR10+ 目前拿不到上面那种适配效果。当时的理由是"没有艺术调整要表达，编
一条曲线不诚实"，而用户的观察说明那条曲线正是效果的来源。

修正后的认识：**它是能诚实算出来的**——我们每帧都实测了 maxscl / 均值 / 分位点，从这些测量
推一条曲线属于"从测量导出的意图"，不是编造。列为待办，未做前不改默认顺序；这件事会改变产物
在各类屏幕上的观感，属于"调子"，需用户确认。

### 设置只留一处 HDR 入口

删除单独的"导出 HDR 视频"开关，并入「导出 HDR 视频格式」的胶囊列表，第一项为「关闭」。
理由：开关与格式选择表达的是同一件事，两处并存会产生"关掉开关但选了 HDR10 会怎样"这种无解
的组合。设备一种 HDR 格式都编不出来时列表只剩「关闭」，也就不需要再解释开关为何置灰。
`makeExportSwitchRow` / `ExportSwitchControl` / `probeHdrExportCapability` 等 106 行随之删除，
源码契约测试改为 `assertFalse(tuning.contains("makeExportSwitchRow("))`。

指示性文字末尾追加最终格式；编码器清单移到该行之后（细节不该挡在结论前面）。

### 产物与完成提示

- 文件名带格式后缀（`fileTag`：HDR10 / HDR10Plus / DV5 / DV81 / DV84 / SDR）。名字要等定档
  之后才算得出来，而两个 sink 实现都在 `createMuxer()` 那一刻才真正落名，所以 `displayName`
  改为推导属性 + `tagFormat()`，不必推迟 sink 的创建。
- 完成对话框与通知加上格式与**实际码率**。恒定质量档下事前给不出码率（`KEY_BIT_RATE` 只是
  提示），但产物落盘后 `文件大小 × 8 ÷ 时长` 就是真实值——这才是用户想知道的数。
  由 `State.Done.bitrateBps` 统一计算，两处共用 `FableSolExportBitrateText`。

---

## D38（2026-07-27）HDR10+ 写入从实测统计导出的色调映射曲线

D37 把"补曲线"列为待办并请用户裁定，用户授权做。

### 曲线语义（照 libplacebo 的 ST2094-40 实现推出，无歧义）

```
x = 线性亮度 / 母版峰值        x 的 1.0 = 声明的母版峰值，逐帧不变
x ≤ Kx :  y = x · Ky/Kx
x > Kx :  t = (x−Kx)/(1−Kx)
          B(t) = Σ C(N,p)·t^p·(1−t)^(N−p)·P[p]     P[0]=0，P[N]=1，中间是 anchors
          y = Ky + (1−Ky)·B(t)
输出亮度 = y · 目标显示峰值
```

**两个轴的归一化基准不同**：x 按母版峰值，y 按 `targeted_system_display_maximum_luminance`。
所以"绝对亮度不变"对应的斜率是 `母版峰值 / 目标峰值` 而不是 1——写错画面亮度会整体跑偏。
膝点 12 位（/4095），锚点 10 位（/1023），`num_bezier_curve_anchors` 4 位（取 9）。

### 目标峰值取 1000 尼特而不是母版峰值

曲线的用处正是"显示设备够不到母版峰值时怎么压"；把目标设成母版峰值等于说"不用压"，
那条曲线就没有任何信息量。1000 尼特是 HDR10 的常规母版目标，比它亮或暗的屏幕由播放端
自行外推——ST 2094-40 本来就是这么用的。

### 曲线形状：膝点以下原样，膝点以上压缩

水体是主体、银丝星芒是点缀，真要压就压点缀。膝点取该帧实测的第 90 百分位（水体主体顶部），
上限留出目标范围顶端一成；整帧峰值本来就装得进目标显示时，膝点直接放到峰值，等于不压。
第一个锚点由**斜率连续**解出（`P[1] = Ky(1−Kx) / (Kx(1−Ky)N)`），接不上会在水体与高光的
交界处留下一道折痕；其余锚点二次缓入到 1，保证单调。

### 膝点必须时间平滑，且快起慢落

逐帧分位点会抖，膝点跟着抖会让背景亮度"呼吸"。指数平滑：下降（高光涌上）τ = 0.08s，
上升（高光退去）τ = 0.80s。慢了会削顶，快了背景会闪。

**平滑的是意图，不是测量**：maxscl / 均值 / 分位点仍是逐帧实测值原样写入，只有膝点做平滑。

### 连带

`FableSolHdr10PlusProbe` 自带的那份 ST 2094-40 写入器删除，改用正式通路同一份——两份实现
迟早漂移，而这东西错一位后面全部错位。`FableSolExportHdr10PlusCurveTest` 钉住单调性、
端点、膝点斜率连续与快起慢落四条；载荷长度从 49 字节变为 64 字节（+118 位）。

---

## D39（2026-07-27）PQ 的漫反射白锚点改为可调；"没有动态感"的根因不是曲线

### 用户的观察与真正的原因

观察：杜比视界 8.4 开头背景亮、水体动起来有星芒后背景变暗衬托高光；而 HDR10 / HLG /
带曲线的 HDR10+ **从一开始背景就是暗的**。

根因**不在曲线**，在漫反射白锚点：PQ 是绝对亮度，我们把水体与卡片钉在 BT.2408 的 203 尼特，
而手机在高亮度下显示 SDR 白在 500–800 尼特——产物因此比同一台手机的普通白还暗。这就是常说的
"HDR 看起来比 SDR 更暗"，从第一帧就定死，曲线管不着（它只动膝点以上）。

**同一个原因解释了"没有动态感"**：背景坐在 203 尼特时，1000 尼特的屏幕还剩五倍余量，
从不需要在背景与高光之间取舍，所以什么都不动。**杜比视界的动态感恰恰来自它的背景坐得够高，
高到屏幕不得不取舍。** 我们的画面是静的，是因为整体被压得太低，而不是曲线不对。

杜比视界 8.4 躲开这一点是因为基层是 HLG（相对亮度，跟随显示端自己的参考白），并且走杜比
自己的显示管线。

### 决定：做成可调，且只在 PQ 系格式下出现

用户裁定"做成可调的吧，只有用户选择了相关的选项才出现这个滑杆"。

- 新增 `FableSolExportOptions.pqWhiteNits`，范围 200–800 尼特、每档 25，**默认 400**
  （约等于手机 HDR 拍摄内容的常用漫反射白）。高光峰值 = 本值 × HDR 强度；水体与高光的
  **相对**关系不变，整体一起上移。
- 滑杆**只在选中 PQ 系格式时显示**（HDR10 / HDR10+）。HLG 与杜比视界 8.4
  是相对亮度，没有绝对锚点可调，摆出来会让人以为它有用。「自动」按解析出的实际格式判断。
- 锚点同时驱动三处：导出 shader 的 `uSdrWhiteNits`、静态元数据的峰值
  （`峰值 = 强度 × 锚点`）、以及 HDR10+ 曲线的母版峰值。三者必须同源，否则元数据描述的
  和画出来的不是同一件事。

按默认 400 算，满强度峰值 3840 尼特，1000 尼特的屏幕装不下，**必须**取舍——D38 那条曲线
到这时才真正开始起作用。

---

## D40（2026-07-27）漫反射白默认值由屏幕能力推出；ADPF tid 快照必须原子

### 崩溃：`toIntArray()` 是两步操作

OPPO PMA110 实测崩溃 `ArrayIndexOutOfBoundsException: length=0; index=0`，栈顶
`FableSolRowParallel.workerThreadIds` → `CollectionsKt.toIntArray`。**与本功能无关**，是实时
渲染路径上一直存在的竞态：Kotlin 的 `Collection<Int>.toIntArray()` 先读 `size` 建数组、
再迭代填充，而被读的 `CopyOnWriteArrayList` 正被 worker 线程在启动时并发写入；两步之间完成
一次注册就会拿 length=0 的数组写 index=0。

改为先 `ArrayList(collection)`（走 `CopyOnWriteArrayList.toArray()`，对当前数组的一次原子
拷贝），再从快照转数组，长度与内容必然自洽。`FableSolRowParallelSnapshotTest` 用同样的并发
形态钉住。

**一般化的教训**：并发容器上任何"先问长度再遍历"的两步写法都是错的，哪怕容器本身线程安全。

### 漫反射白：读屏幕能力，取峰值的四分之一

用户提出"现在手机屏幕 HDR 峰值都蛮高，能不能从设备读取自动算默认值"。

`Display.HdrCapabilities.getDesiredMaxLuminance()` 给出屏幕声明的 HDR 内容峰值；
默认漫反射白 = 峰值 ÷ 4，夹到 200–800。1600 尼特屏得 400，2600 尼特屏得 650。
该值仅作滑杆初值，用户拖过即以其为准，「恢复默认」清回自动。

**为什么固定取四分之一而不是按 HDR 强度反推**：两件事必须分开——漫反射白对标"这块屏幕能多亮"，
HDR 强度是作者意图，不该被屏幕反过来改写。按"让高光正好落在屏幕峰值"反推（峰值 ÷ 9.6），
2600 尼特的屏幕只得 271 尼特，屏幕越好画面越暗，方向就反了。四分之一意味着背景舒服地亮着，
高光越界的部分交给 D38 的曲线去压——那正是动态感的来源。

**读能力不读当前状态**：`getDesiredMaxLuminance` 是屏幕的能力，而 `getHdrSdrRatio` 随亮度
滑杆变。用后者会让同一段音频在不同亮度下导出成不同亮度的文件——D5 已经为此拒绝过读
`hdrSdrRatio`，这里沿用同一条原则。

该 API 在 API 34 被标记过时（厂商填的值不可靠），因此自己夹一道 300–10000 尼特的合理区间，
超出即视为占位值并退回固定的 400。诊断新增「屏幕 HDR 峰值 · 自动漫反射白」一行以便核对。

---

## D41（2026-07-27）曲线目标峰值不得写死；膝点上限由"控制点不许夹死"解出

### 现象

用户把漫反射白拉到 800 后，HDR10+ 产物在星芒出现时背景**间歇性发青蓝**。400 时不出现。

### 根因：肩部退化成断崖

肩部第一个控制点由斜率连续解出：`P[1] = (M − k) / (N(T − k))`（M 母版峰值、T 目标峰值、
k 膝点、N 阶数）。D38 把 T **写死成 1000**。漫反射白 800 时 M = 800 × 9.6 = 7680，
要把 800→7680 压进 800→1000，`P[1]` 解出来远大于 1，只能夹到 1——**所有控制点随之全为 1**，
肩部从膝点几乎垂直冲顶，膝点以上的一切被压成同一亮度。

三个通道于是在各自不同的亮度处先后撞上这道崖。星芒最亮处通常偏暖（R 先撞顶），R 被压掉后
剩下 G/B 就显出青蓝；而"有时有有时没有"是因为要画面里真的出现足够亮的高光才撞得到。

### 两处修正

1. **目标峰值随屏幕定**：`targetNitsFor(mastering, white, panelPeak)` 取屏幕声明的峰值
   （D40 已经在读），下限保证 ≥ 漫反射白 × 2，上限压在母版峰值（目标高过母版等于让播放端
   提亮画面，方向反了）。800 白点 + 2000 目标 ⇒ `P[1] = 0.57`，稳在合法范围。
2. **膝点上限由不等式解出**：`P[1] ≤ 1 ⟺ k ≤ (N·T − M)/(N − 1)`，直接参与膝点的 coerce。
   即便目标峰值再低，膝点也会自动退到曲线不退化的位置。

`FableSolExportHdr10PlusCurveTest` 新增覆盖 200–800 全滑杆 × 四档强度的用例，任何组合下
第一个控制点都不得 ≥ 0.999、肩部不得退化成平顶。

**一般化的教训**：凡是"解出来再 clamp"的参数，clamp 触发就意味着约束不成立——必须回头调整
输入让解落在合法域内，而不是夹死了事。夹死通常不会报错，只会让产物悄悄变形。

---

## D42（2026-07-27）外部评审九条：八条属实并修复，一条需要区分用途

用户请 GPT 对 HDR10+ / P010 通路做静态评审。逐条核实后**质量很高**——四条 P1 全部成立，
三条中等问题也成立；只有分位点那条需要补充区分。

### P1-1 `targeted_system_display_maximum_luminance` 单位写错一万倍

该字段单位是 **0.0001 尼特**，实现按 1 尼特写入，播放端会把 1000 尼特读成 0.1 尼特。
**位宽本身就是证据**：上限 10000 尼特 ÷ 0.0001 = 1e8，恰好需要 27 位；按尼特存 14 位就够，
根本用不着 27 位。已改为 ×10000 并夹到 10000 尼特。

同一份载荷里 maxscl / average_maxrgb / distribution 是**归一化 [0,1]、步长 0.00001** 的量，
原实现的 `nits × 10` 恰好等价（归一化基准是 PQ 的 10000 尼特），那几个没写错。
**两种单位混在一份数据里是这个格式本身的坑**，注释已写明。

新增 `FableSolHdr10PlusPayloadDecodeTest` 逐字段解回来核对。原有测试只查固定头与总长——
位宽、总长、固定头全对，这个一万倍的错误照样通过。**只校验结构不校验数值的测试挡不住单位错误。**

### P1-2 正式导出没把 SEI 当发布门禁

探测检查 `hdr10PlusSeiSeen`，正式导出在 `finish()` 后直接 commit。编码器运行中丢弃元数据时，
会发布一个文件名与完成提示均标为 HDR10+、实为普通 Main10 的文件。已在 commit 前加
`check(!byteBuffer || encoder.hdr10PlusSeiSeen)`，失败即让降级阶梯换 HDR10 重来。

### P1-3 字节缓冲通路的色彩范围权威归属

D28 确立"色彩范围以编码器回报为准"，但那条**只适用于 surface 模式**——那里 RGB→YUV 由编码器
执行。字节缓冲模式下转换是我们做的，样本固定有限范围，编码器只是透传；采纳它的回报会让容器
标记与样本冲突（发灰、黑位抬高）。原则收敛为一句：**谁做的转换谁是权威。**

### P1-4 `slice-height` 回报 0 未处理

Android 明确允许厂商回报 0，而 0 会让色度平面起始偏移与入队长度一起变成 0——画面全废且不报错。
`stride` 同理。两者改为 `takeIf { it > 0 }` 后退回按画面尺寸计算。

### P2 HDR10+ 被无关的 EGL 能力拦截

该通路在自有离屏 RGB10_A2 framebuffer 内完成 PQ 编码，1×1 pbuffer 不打色彩空间属性，却仍被
`anyHdrColorSpace`、传递函数列表与 10-bit pbuffer config 三处门控。这会把**有 P010 HDR10+
编码能力、但缺 EGL 窗口扩展**的设备错误降到 SDR。新增
`FableSolExportHdrFormat.requiresEglColorSpace`，三处门禁一并放开；离屏 EGL 不再要求 10-bit config。

### 其余三条

- 探测每轮开头未重置 `lastSupportedFormats` 等，早退或异常会让旧清单存活并被写进缓存 → 已重置。
- 设置页删除 HDR 开关时把 D24 的"首帧后再探测"约束一起弄丢 → 已恢复 800ms 延后且随 Dialog 失效。
- MaxFALL 写死 203，而漫反射白已可调到 800 → 改为跟随漫反射白。

### 唯一需要区分的一条：分位点统计口径

评审说得对：当前先归约成 32×32 的块**平均**再取分位点，小面积高光会被抹掉，与标准要求的
逐像素 linearized maxRGB 分位点不是一回事。

但要区分它的两个用途：**给我们自己定膝点**时，恰恰不希望几个像素的星芒把膝点顶上去，块平均
在这个用途上是合适的；**写进元数据交给播放端**时才是真的失真。所以问题不是"算错了"，而是
**一个数被当成两个用途**。正确的修法是逐像素直方图（当前图形接口下需另加一趟渲染），列入
遗留项，不仓促改。

---

## D43（2026-07-27）掉饱和的成因是峰值超标；「膝点」改名并开放为参数

### 进度条改用与滑杆同源的渐变

导出进度对话框用 `progressTintList = ColorStateList.valueOf(accent.color)` ——那只是渐变的
**起点单色**，换色时看不出方向，也与同一 Dialog 内其它强调色元素对不上。新增
`DisplayUtil.setProgressBarBackground()` 复用 `SeekBarTrackDrawable`，两处不再各有一套着色。
不定长档仍是单色：平台的不定长动画是另一个 drawable，只接受 tint。

### 深红→粉红：逐通道压缩，而压缩量由峰值超标决定

用户观察：漫反射白调高后水体偏白，深红变成正红甚至粉红。

**渲染没错**——我们输出的 R:G:B 比例是准确的，PQ 编码也保比例。掉饱和来自**下游的逐通道
压缩**：深红的 R 最大、压得最狠，G/B 几乎不动，三者比例被拉近 ⇒ 朝白色走。

而压得多狠取决于 `峰值 = 漫反射白 × HDR 强度` 超出屏幕能力多少。203→800（3.9×）而强度仍
9.6 时，峰值从 1949 涨到 7680，对 2000 尼特的屏幕要硬压近四倍。

**因此把峰值写进指示行**。两根滑杆各调各的，乘积很容易在不知不觉间失控，而那个乘积才是决定
画面发不发白的量。这不是能"修掉"的 bug——想背景亮又不掉饱和，只能把强度收回来，两者是乘法
关系。

### 「膝点」→「高光起点」，并开放为参数

术语确实是黑话。改名为**「高光起点」**：以下是主体、原样保留，以上算高光、才被压缩。
范围 50–99%，默认 90。调高 ⇒ 更多画面受保护、饱和色更不易被压掉，代价是高光层次空间变窄。

- 只在 HDR10+ 下出现——只有它带色调映射曲线，别的格式调它毫无作用，摆出来是误导。
- 码流只带 9 个标准分位点，而滑杆连续，故 `FableSolHdr10PlusStats.nitsAtPercent()` 在分位点
  之间按百分位轴线性插值；否则滑杆推到某些位置膝点原地不动，手感发涩。
- 指示行完整写出：格式 · 漫反射白 · 峰值 · 高光起点。

**滑杆的显示条件要按"这个参数真的起作用吗"来定，而不是按"它属于哪一组"**：漫反射白对所有
PQ 系格式有效，高光起点只对 HDR10+ 有效，两者的可见条件因此不同。

---

## D44（2026-07-27）倾斜可关；外观按应用**实际生效**的夜间模式，不按 Context 碰巧带的那份

### 倾斜做成开关，关掉即复用"没有轨迹"那条路

D13 把倾斜存进 WAV 的 `EDmo` chunk，导出时一律重放。现在加一个设置项（默认保留），关掉时
**连读都不读**轨迹——直接落到 `gravityTrack == null` 那条既有分支，与本功能之前的历史录音
走同一条竖直渲染路径。不为"用户关掉了"另造一种表达：两者要的结果完全一样。

它**只对本应用录制的音频有效**，因为只有那些 WAV 带得动轨迹；导入的音频本来就没有倾斜
可言。设置项因此写作「保留录音过程中可能的画面倾斜」，不写成泛指的"导出时是否倾斜"。

位置排在导出组**第一行**：倾斜是画面内容，其余各项是编码参数，两类不该混在一起。

**控件形态是勾选框，不是档位胶囊**（用户裁定）。胶囊适合"若干并列的档位"，而这一项是一个
是非开关，摆两个胶囊等于把二值伪装成多选。随之要求**未选中的方框也用完整强调背景描边**，
而不是全应用 checkbox 通用的中性色——即 D18 给胶囊定的"未选中不把渐变压成单色"延伸到勾选框。

描边**不降 alpha**（第一版试过 160/255，用户否决）：checkbox 的描边只有 2dp 宽，淡一点就比
旁边的控件明显发虚。选中态的对号也从固定白色改为按填充色明暗自适应。

这两条随后被用户推广成全局规则，见 `memory/decisions.md` 2026-07-27 那条：本 Dialog 的
全部勾选行与 `SettingsActivity` 的 13 个勾选框统一采用，因此本目录不再单独维护它的细节。

### 深色模式下卡片恒白，根因是 Context 而不是取色逻辑

现象：深色模式下画框（`FableSolExportSpec` 的 backdrop）已经是黑的，卡片却仍是白的。

`FableSolGlRenderer` 的 `environmentBase` 取 `context.theme` 的
`android.R.attr.colorBackground`——屏上那份来自对话框 Context，主题
`EverythingDoneTheme.Dialog` 把它指向 `@color/app_chrome_surface_elevated`
（#FFFFFF / 夜间 #1E1E1E）。而导出跑在 Service 里，拿到的是 Application Context，
它两样都不对：

| | 屏上（对话框 Context） | 导出（Application Context） |
|---|---|---|
| 主题 | `EverythingDoneTheme.Dialog` | **平台默认**的 `Theme.DeviceDefault.Light.DarkActionBar`——`<application>` 根本没写 `android:theme` |
| `colorBackground` | `app_chrome_surface_elevated`，随 `-night` 翻 | 平台自带的浅色值，**与夜间资源无关** |
| `uiMode` | AppCompat 按默认夜间模式覆写过 | 只跟系统走，读不到应用自己的设置 |

所以这不是"取色写错了"，是**问错了对象**。新增 `FableSolExportAppearance.themedContext()`：
先按 `AppearanceUtil.isDarkModeApplied()` 把配置里的夜间位钉死，再套上
`EverythingDoneTheme.Dialog`，导出全程只用这一个 Context。画框、卡片、时钟 `hostDark`
三处从此不可能各判各的——它们本来就该是同一个判定。

### `isDarkMode()` 对后台工作不成立，因此另立一条

`AppearanceUtil.isDarkMode()` 读的是调用者 Context 的配置，只有 Activity 上准确。
新增的 `isDarkModeApplied()` 与 `getDefaultNightMode()` 同源：跟随系统时读系统配置，
否则直接看强制深色开关。它顺带修掉一处原有的不一致——**应用固定浅色而系统深色**时，
旧逻辑会把画框判成深色，与界面相反。

**沿用 D4 的原则**：产物是"此刻这个界面的忠实记录"，所以这里不提供浅色/深色的导出选择，
只保证跟随应用当前外观。要不要让用户挑，是另一件事。

---

## D45（2026-07-27）自动漫反射白同时受面板峰值、帧平均能力与 HDR 强度约束

用户在 HDR10+ 800 尼特样片的逐像素分析后裁定：默认漫反射白不能直接写成 350 尼特固定值，
也不能继续只取面板峰值的四分之一；要按每台机器的亮度能力与当前 HDR 峰值强度自适应，并在
FableSol 设置 Dialog 下方的规格/编码推导文字里说明算法。

### 公式

设：

- `P`：Android `desiredMaxLuminance` 上报且通过合理性校验的面板 HDR 峰值；
- `A`：Android `desiredMaxAverageLuminance` 上报且通过合理性校验的最大帧平均亮度；
- `S`：当前 HDR 峰值强度，夹在 1.0～9.6；
- `M = 1.75`：允许母版峰值达到面板峰值的倍数，保留 HDR10+ 映射空间但不再放任到旧算法的
  `9.6 ÷ 4 = 2.4` 倍；
- `C = 400` 尼特：自动档上限；500～800 尼特继续只作为用户明确选择的高亮创作档。

自动白锚为：

```text
raw = min(P × M ÷ S, A, C)  // 仅列入可用约束
autoWhite = floor(clamp(raw, 200, 400) ÷ 25) × 25
```

`A` 直接作为漫反射白上限是有意的保守约束：实测 FableSol 画面的帧平均亮度约为白锚的
0.56～0.69，因此这样仍给大面积画面保留约三成余量。`P` 或 `A` 未声明/不可信时只忽略对应项；
两者都不可用才退回现有安全默认值。以 `P=2000`、`S=9.6` 为例，峰值约束为
`2000×1.75÷9.6≈365`，按 25 尼特向下对齐得到 350；350 是该设备与强度组合的结果，不是常量。

### 状态与界面

- 用户从未拖动漫反射白，或点“恢复默认”后：保持自动状态；HDR 强度变化时白锚滑杆和推导文字
  同步重算，正式导出时再次按最新能力与强度求值。
- 用户拖动漫反射白后：保存手动值，不再随 HDR 强度改写；推导文字明确写“手动设置”。
- PQ 格式的推导文字在现有“漫反射白、峰值、高光起点”之后补公式：
  `min(可用的屏幕峰值×1.75÷HDR强度, 可用的最大帧平均亮度, 400)`，再按 25 尼特向下取整；
  非 PQ 格式不显示无效说明。
- 设备诊断同时列出峰值、最大帧平均亮度、当前 HDR 强度和算出的自动白锚，便于核对厂商上报值。

---

## D46（2026-07-27）自动白锚推导文字展示实际设备参数与完整计算过程

用户复核 D45 首版文案后指出：既然使用 `min` 函数，参数列表就必须采用标准公式语法，
不能写成 `min（……、……）`。设置 Dialog 的规格/编码推导文字还必须直接显示本机实际可用的
屏幕 HDR 峰值与最大帧平均亮度，并整体采用更专业、可核对的表达。

界面按以下结构展示：

```text
设备亮度能力：HDR 峰值 2000 尼特；最大帧平均亮度 600 尼特。
计算：min(2000 × 1.75 ÷ 9.60, 600, 400) = 364.6 尼特；
限制在 200–400 尼特自动范围内，并按 25 尼特档位向下取整，最终为 350 尼特。
```

- `min(...)` 一律使用 ASCII 半角括号和英文逗号；公式数值使用 `.` 作为小数点。
- 峰值或最大帧平均亮度未声明/未通过合理性校验时，能力行标为“未声明（不参与计算）”，
  公式中直接省略该参数，不得把缺失值伪装成 0。
- 两项设备能力都不可用时，明确说明采用安全回退值；手动档明确说明当前不执行自动公式，
  “恢复默认”后才重新按设备能力计算。
- `min(...)` 的原始约束结果与应用自动范围、档位量化后的最终值分开显示，避免把
  `min(...) = 350` 写成数学上不成立的等式。

---

## D47（2026-07-27）“自动”HDR 格式说明与规格优先顺序保持一致

用户指出设置 Dialog 把自动格式描述为“按兼容性优先”，但实际 `AUTO_ORDER` 已明确按规格从高
到低排列：

```text
HDR10+ → Dolby Vision 8.4 → HDR10 → HLG
```

这套顺序优先保留 PQ 高光余量、动态元数据与更高 HDR 规格，并非播放器兼容性排序。界面文案
统一改为“按 HDR 规格与画质能力由高到低，选用本机实测可编码的首个格式”，同时保留当前实际
落入格式。仅修正文案，不改变已有 `AUTO_ORDER` 或编码降级行为。

---

## D48（2026-07-27）杜比视界 8.4 排在 HDR10 前，中文显示名统一本地化

用户进一步裁定自动顺序，并要求全面清理用户界面的英文 “Dolby Vision”：

```text
HDR10+ → 杜比视界 8.4 → HDR10 → HLG
```

D47 关于“不是兼容性优先”的结论仍成立，但其记录的旧顺序由本决策覆盖。杜比视界 8.4 虽使用
HLG 基层，仍带设备生成的杜比动态元数据，因此在本产品的自动策略中排在静态元数据 HDR10
之前；HLG 继续作为最后兜底。

格式名称分成两层：

- **稳定标识**：固定英文，供探测缓存、格式恢复、日志关联和文件名等内部协议使用，不得本地化；
- **显示名称**：来自 Android 字符串资源。简体、繁体中文统一显示“杜比视界 8.4”，
  非中文 locale 使用基础英文资源。

所有用户可见入口必须调用同一个显示名称映射，包括设置胶囊、自动格式说明、规格推导行、设备
诊断、导出完成 Dialog、通知和可能呈现给用户的编码失败信息。不得在各调用点自行拼接
`Dolby Vision`，也不得把本地化名称写回缓存或偏好。

---

## D49（2026-07-27）HDR 能力信息采用结构化验证结论和正式技术措辞

用户指出杜比视界失败判定中“编码器把基层的亮度曲线改回去了”“那一档还有机会”等表达过于
口语化，要求全面检查 HDR 相关信息并统一为专业、严谨、正式的描述。

用户可见的 HDR 能力信息按以下结构表达：

1. **能力结论**：明确“可用 / 不可用”，列出通过完整单帧编码与封装验证的格式，以及自动
   选择结果。
2. **请求值与实际值**：编码器改写传递函数或 Profile 时，直接列出请求值、实际值和验证
   结论。例如 `请求 PQ / ST 2084 (6)，实际 HLG (7)`，不得使用“改回去了”等拟人化措辞。
3. **验证范围**：产品只验证杜比视界 8.4。诊断可以列出编码器原始广告的其它 Profile，但不得
   把它们转换为产品候选或描述成可用导出格式；Profile 8.4 采用 HLG 基层并单独完成实际编码验证。
4. **HDR10+ 路径**：独立报告“未提交动态元数据”和“提交 ST 2094-40 元数据”两种条件，
   不再使用“裸通路”“带元数据”等口语化标题；判据明确为输出码流是否检测到
   HDR10+ ST 2094-40 SEI。
5. **设备能力摘要**：统一使用“支持 / 不支持 / 未发现 / 未声明”等明确状态，区分显示设备
   亮度能力、EGL 色彩空间、10-bit 窗口配置和编码器声明能力。

能力缓存继续保存稳定英文格式标识和原始技术详情；展示时再按当前 locale 转换格式名称。
探测契约版本升至 5，使旧缓存中的口语化失败信息失效并重新执行验证。


## D50（2026-07-27）能力结论不得回写用户偏好

三星 Z Fold4 上设备能编 HDR10 与 HLG，设置页的默认选择却是「关闭」。原因在
`populateHdrFormatChips`：探测结果为空时执行 `setExportHdrEnabled(appContext, false)`，
把「这台设备编不出 HDR」这个**能力事实**写进了**用户偏好**。该写入不可逆——判定是否开启
的表达式是 `exportHdrEnabled(...) && formats.isNotEmpty()`，偏好一旦为 false，之后即便
探测重新通过，界面仍以偏好为准落在「关闭」上，随后又写一次 false。

该机上曾使探测结果为空的路径至少有四条：旧版本中色彩范围校验否决全部高通 HDR 候选；
`FEATURE_HlgEditing` 门禁在 API 35 上筛光 HLG 候选；探测线程抛出异常时调用方回退到空
列表；否定结果 24 小时缓存有效期内打开设置页。任何一次都足以永久关闭 HDR。

**定为通则：能力探测只允许影响当次显示，不得写入任何用户偏好。** 写偏好的入口一律要求
`fromUser` 为真。冲突迁移是例外，见 D52。

## D51（2026-07-27）能力探测按用户实际使用的编码模式验证

此前探测固定使用恒定码率，理由是"能力结论不能随用户偏好漂移"。但设置中「编码模式」默认
为恒定质量，该档下发的是 `KEY_BITRATE_MODE = CQ` 加 `KEY_QUALITY`，候选排序也随之改变
（`.cq` 变体优先）。于是设置页验证过的 `MediaFormat` 与正式导出实际使用的并不是同一份，
「设置页显示可用、正式导出全档失败」由此产生。

探测改为读取用户当前的编码模式，并将该模式纳入能力缓存签名。码率与关键帧间隔仍取默认值：
它们会被夹到编码器的合法区间，不改变可行性，纳入签名只会让滑杆每动一次就作废整份缓存。

## D52（2026-07-27）可行组合表：编码器族成为用户可选的第三条轴

用户要求可以选择 H.264 / HEVC / AV1，并按组合是否成立决定选项是否可用。

三条轴（HDR 格式 × 编码器族 × 帧率）的可行性数据本来就在探测过程中产生，此前每种格式
一旦编成一帧就跳出循环，只保留"该格式可用"一个布尔量，既说不出它落在哪个编码器与帧率
上，也无从判断其余组合是否成立。现改为逐组合记录并整表缓存
（`FableSolExportCapabilityMatrix`），探测契约版本升至 6。

- **杜比视界归入 HEVC 族**：它更换的是 MIME，基层与编码器仍是 HEVC。
- **SDR 一并纳入探测**，并在 SDR 阶梯中补上 AV1：此前 AV1 只出现在 HDR 阶梯，关闭 HDR
  之后无从选择该编码器。
- **置灰规则**：格式与编码器的可用性不受当前帧率约束（帧率是上限语义，导出会自动降级）；
  120fps 在当前组合下不成立时置灰，即"选择 120fps 却输出 60fps"的那种情形。
- **冲突迁移**：刚变更的那条轴优先。变更编码器则迁移格式，变更格式则迁移编码器；迁移结果
  落盘，避免界面显示与实际导出组合不一致。初始化时只迁移编码器，格式是明确的用户意图，
  不因一次探测结论被抹除。

## D53（2026-07-27；2026-07-28 修订）自动档将软件编码器作为同规格的最后回退

三星 Z Fold4 上一次 120fps 的 HDR 导出实际落在 `c2.android.av1.encoder` 的 60fps 上。
该实现是 AOSP 的软件编码器，而候选收集此前完全不区分软硬件
（`MediaCodecInfo.isSoftwareOnly()` 在整条导出通路中从未被调用）。导出画布接近两百万像素，
软件编码与硬件编码的耗时可能相差一到两个数量级。该设备事实要求产品明确告知代价，但不能
直接推导成自动档排除软件编码器；原先的完全排除规则现予撤销。

- 自动档允许软件编码器参与候选。对 D161 已经固定的同一组输出规格，先尝试全部硬件候选，
  再按相同的编码器族顺序尝试软件候选；只有同规格的硬件和软件候选均不可用时，才进入既有
  规格降级阶梯。不得为了避免软件编码而改变帧率、HDR 格式、分辨率或位深。
- 同一实现类型内继续按 D161 使用 `HEVC → AV1 → AVC／H.264`；因此完整的同规格顺序为
  `硬件 HEVC → 硬件 AV1 → 硬件 AVC → 软件 HEVC → 软件 AV1 → 软件 AVC`，不存在的
  Profile／位深组合自然不生成候选。
- 用户显式选择编码器族时，只在该族内部按硬件优先、软件后备；不得绕开显式选择改用其它族。
- 当设置页根据当前全部约束解析出的实际候选是软件实现时，信息栏明确说明导出预计更慢，并可能
  增加耗电和发热；不增加额外确认 Dialog。能力诊断、设置摘要、导出进度及完成信息均继续明确
  标注实际使用的是硬件编码还是软件编码。
- 软件编码器只承担其能力探测和真实编码共同证明可以完成的规格。某台设备上的软件 AV1 最高
  只能达到 60fps，不得被泛化为 AV1 或软件编码器的全局帧率上限。

## D54（2026-07-27）导出完成信息写明实际使用的编码器

降级阶梯在格式、帧率与编码器三条轴上依次退让，退让结果此前只有格式与帧率可见。完成对话框、
通知与设置页推导文字统一改为「输出格式 · 编码器」，软件实现附加标注。能力诊断的首行同时
写出每种通过验证的格式实际落在哪个编码器与帧率上。

## D55（2026-07-27）派生结论必须在当前选择的**全部**约束下解析

OPPO 上把编码器钉成 AV1 后，格式胶囊已正确地只保留 HDR10 与 HLG，说明文字却仍显示
「当前为 HDR10+」。原因是该文字使用探测得出的全局 autoFormat，那是编码器同样取自动时的
答案，不含用户当前的编码器约束。

**通则：任何面向用户的派生结论，都必须在当前选择的全部约束下解析，不得复用某一条轴取默认
值时的结果。** 本轮据此修正三处：

1. **「自动」格式**改为 `matrix.autoFormat(编码器族, 是否允许软件实现)`，顺序仍与导出
   阶梯一致。格式说明、设置摘要与滑杆显隐（漫反射白、高光起点）全部改用该结果。
2. **有效帧率**与编码器在同一次遍历中解出。面板上的帧率是上限，当前组合达不到时导出会自行
   降级；体积估算与提示语此前仍按上限计算，会与产物给出两个互相矛盾的数。
3. **能力报告**列出每种格式的全部可用编码器及各自能达到的最高帧率。此前只报第一个落点，
   OPPO 上 HLG 的 HEVC 与 AV1 两条路都成立，报告却只写了 HEVC。

同时修正冲突迁移的判据：此前取第一个成立的选项，与当前不同就迁移，会在用户钉定 AV1、
随后切换到 AV1 同样支持的格式时把编码器甩回「自动」，取消用户明确做过的选择。改为仅在当前
选项**确实不成立**时才迁移。
## D56（2026-07-28）10 位编码器输入表面必须带 EGL_RECORDABLE_ANDROID

三星 Z Fold4 上全部 HDR 档位与 SDR 的 10 位档位一律失败，报错为编码器状态异常
（`Invalid to call at Released state`、`Pending dequeue output buffer request cancelled`、
`signalEndOfInputStream() is valid only at Executing states`）；同机 8 位档位全部通过。
关键证据有两条：SDR 通路通过验证的是 `HEVC Main SDR` 而非 `HEVC Main10 SDR`；
「10-bit 窗口配置」停在阶梯的第三档，即不带 `EGL_RECORDABLE_ANDROID` 的那一档。

`EGL_RECORDABLE_ANDROID` 决定 EGL 分配出来的缓冲带不带视频编码器用途位。缺少该属性时，
交给 `MediaCodec.createInputSurface()` 的缓冲编码器无法消费，组件随之进入不可用状态。
此前 [FableSolExportEgl] 的注释将其描述为「给驱动的一个提示，拿不到就退一档」，该判断有误，
已一并更正并由源码契约测试钉住。

**定为通则：编码器输入表面的 config 必须带 `EGL_RECORDABLE_ANDROID`；取不到时不能默认降档
了事，要先确认是真的没有。** 该属性属于厂商扩展，部分驱动不将其纳入 `eglChooseConfig` 的匹配
条件，却可通过 `eglGetConfigAttrib` 正常读取。因此匹配落空后改为枚举全部 config 逐项核对，
放宽的两档仅作为枚举同样找不到时的最后退路，其成立与否交由真实编码探测判定。

能力报告新增 10 位窗口 config 的清点（总数与带 recordable 的个数），使该判断可核对。
探测契约版本升至 9。

## D57（2026-07-28）设备能力报告支持整段复制

设备能力报告是排查的原始材料，此前只能截图传出，一屏放不下需截多张且无法检索。设置页的
推导文字与设备能力报告改为长按复制两段全文，下方给出提示。复制内容不含提示本身。
## D58（2026-07-28）三星 Z Fold4 无可用的硬件 10 位输入通路，属设备限制

结论已由实测确定，不再作为待查项：

- EGL 侧：该机共 4 个 10 位窗口 config，带 `EGL_RECORDABLE_ANDROID` 的为 0 个。枚举全部
  config 逐项核对后结果不变，因此 `eglChooseConfig` 的匹配结果本就是准确的。
- 编码器侧：`c2.qti.hevc.encoder` 不支持 P010 字节缓冲输入。

两条 10 位输入通路都不可用，因此该机的硬件 HEVC 编码器虽然广告 Main10 与 Main10HDR10Plus，
在公开接口上无法承担任何 HDR 编码。8 位通路一切正常（HEVC Main SDR 与 H.264 High SDR 均在
120 fps 通过）。HDR 因此只能由软件 AV1 承担，且仅到 60 fps。按照修订后的 D53，软件 AV1
可以作为自动档的最后候选；自动档不得再仅仅因为该路径是软件实现就落到 SDR。用户显式选择
HDR10、HLG 或 AV1 时，仍须服从相应的格式与编码器族约束。

**明确不做的替代方案**：用 8 位表面配 Main10 加 PQ 标记，让编码器上采样。PQ 在 8 位下的
色带正落在水体所处的中低亮度区间，产物会明显劣于 SDR，而文件名与完成提示仍标着 HDR10。
这与本功能一贯的判据冲突：广告与实测不符的产物，比不提供该选项更糟。
## D59（2026-07-28）已成功的导出不得死在收尾通知上

华为平板（EMUI，Android 12）上视频写完并入库之后，进程在发送完成通知时崩溃。通知栏的分享
按钮由 `Intent.createChooser` 携带媒体 URI 构建，而 `PendingIntent.getActivity` 会在**构建
当时**执行一次 URI 授权（`Intent.migrateExtraStreamToClipData`），该设备拒绝授权并抛出
`SecurityException`。

授权被拒的原因位于厂商实现，应用侧无法改变。但产物完好、终态也已经通过 Bus 发给界面，为了
一个通知按钮让整次导出以崩溃收场是不成比例的。

**定为通则：收尾阶段的一切装饰性动作都必须可失败。** 打开产物的跳转、分享按钮、以及
`NotificationManager.notify` 各自兜底，任一失败只减少通知的能力，不影响导出结论。对话框内的
分享走 Activity 上下文，不在此列。

## D60（2026-07-28）三条轴的说明都要写出当前实际落点

HDR 格式的说明一直写着「自动」当前会落到哪一种，编码器那一侧却只有一段固定文字，于是选
「自动」时看不出这台机器上究竟挑中了哪个编码器，也看不出是硬件还是软件编码。

编码器说明改为三段式：该档位的特点（HEVC、AV1、H.264 各自独立描述，与 HDR 格式说明形式
一致）、当前实际使用的编码器并标明硬件或软件、灰色选项的含义。该结论随输出格式与帧率变化，
因此由 `notifyResolved` 统一写入，而不是在选中编码器时写一次。

推导文字同时调整版式：结论行加粗并与推导过程空一行，推导过程与设备能力报告之间不再留额外
间距，使三段连成一份可整体阅读、可整体复制的报告。
## D61（2026-07-28）编码验证必须以真的产出样本为准

华为平板（Kirin，`OMX.hisi.video.encoder.hevc`）上每一档 HEVC 都导出成 0 字节文件，SDR 与
HDR、60 与 120 fps 皆然，只有 H.264 有数据。

判据不完整是根本原因：`INFO_OUTPUT_FORMAT_CHANGED` 一到达即可 `addTrack` 并启动封装，此后
即便一个实际样本都不产出，`finish()` 依然会走到流结束并成功返回。能力探测与正式导出都以
「拿到输出格式并走到 EOS」为准，于是一个不报错、也不产出的编码器被判为通过。

该机的具体成因是带 `EGL_RECORDABLE_ANDROID` 的 10 位窗口 config 为 0 个（见 D56）：缓冲缺少
视频编码器用途位，编码器无法消费。SDR 通路的首选档位 HEVC Main10 同样使用 10 位表面，因此
关闭 HDR 也无法回避，而 H.264 属于 8 位档位，走另一条 config。

**定为通则：编码能力的判据是产物里真的有东西，不是流程走完了。** 与 HDR10+ 以「码流里确实
带上 ST 2094-40 SEI」为判据是同一条原则的两种表现。实现上以写入容器的视频样本数为硬门禁，
能力探测与正式导出两处都验，并在发布前再核对一次落盘大小。任一不通过即判该候选失败，交由
降级阶梯换下一档。

## D62（2026-07-28）SDR 导出区分原生重渲染与 HDR 高光降映射

用户明确选择 SDR 时，存在两种不同的创作意图，不能再由一个“关闭 HDR”选项统一代替：

1. `SDR（原生渲染）`：关闭 HDR 额外高光后重新渲染，保持当前行为并作为默认值。
2. `SDR（保留高光层次）`：保留原有 HDR 强度进行渲染，再通过 HDR→SDR 色调映射压入 SDR
   输出；该模式保留的是高光层次和相对观感，不宣称保留 SDR 无法承载的绝对 HDR 亮度。

现有关闭 HDR 的偏好迁移到 `SDR（原生渲染）`，不得改变既有用户的输出语义。

设置页继续只使用一个互斥选择器，避免 HDR 开关与格式选择表达出相互冲突的状态。标题由
“导出 HDR 格式”改为“导出色彩模式”，选项按两种 SDR、`HDR（自动）`、具体 HDR 格式排列。
两种 SDR 共同使用 SDR 编码能力矩阵；`SDR（保留高光层次）` 还要求 FP16 扩展显示线性渲染
能力，但不依赖 HDR 编码能力。PQ 漫反射白、高光起点等格式专属参数只在适用格式下显示；以后
新增的 SDR
色调映射参数只在 `SDR（保留高光层次）` 下显示。

## D63（2026-07-28）保留高光 SDR 复用动画当前的 HDR 高光强度

`SDR（保留高光层次）` 的色调映射输入直接使用动画当前的 HDR 高光强度，不新增一套 SDR
专属的源强度。由此，同一动画导出的 HDR 与保留高光 SDR 版本来自同一创作状态。

后续如需提供 SDR 色调映射控制，只允许调整映射风格、高光压缩等输出侧行为，不再叠加第二套
输入强度，避免两个含义相近的参数共同改变 HDR 源信号。

## D64（2026-07-28）高光强度关闭时，保留高光 SDR 自然退化并明示

当前 HDR 高光强度为 `1.0×` 时，`SDR（保留高光层次）` 仍可选择，不置灰、不自动提高强度，
也不视为错误。由于源场景没有额外 HDR 高光，该模式自然退化为与 `SDR（原生渲染）` 相同的
输出。

设置页下方的信息栏必须明确提示当前退化状态及原因，例如“当前 HDR 高光强度为 1.0×，本次
导出将与 SDR（原生渲染）一致”，避免用户误以为色调映射仍能凭空恢复高光。高光强度重新高于
`1.0×` 后，该选择自动恢复其正常语义。

## D65（2026-07-28）保留高光 SDR 提供稳定与动态两种映射方式

选择 `SDR（保留高光层次）` 后，显示二级选项“色调映射方式”，只提供以下两个明确档位，
不增加含义不确定的“自动”：

1. `稳定映射`：整段视频使用同一条色调映射曲线，使跨帧亮度与色彩关系保持稳定。
2. `动态映射`：根据逐帧实测的亮度分布调整曲线，并做时间平滑；它能更充分地利用每一帧的
   SDR 亮度范围，但画面的亮度观感以及高光色域压缩结果可能随内容产生轻微变化。

设置页下方的信息栏必须随选择解释二者的差异，至少写清“跨帧稳定”与“逐帧保留更多高光、
可能轻微变化”的取舍，不能只显示档位名称。

## D66（2026-07-28）稳定映射是保留高光 SDR 的默认方式

“色调映射方式”没有既有偏好时默认选择 `稳定映射`，避免首次使用就引入预期外的跨帧亮度
变化。用户主动改为 `动态映射` 后持久保存该选择，后续继续尊重用户偏好。

## D67（2026-07-28）稳定映射由 HDR 强度直接定曲线，不预扫描全片

`稳定映射` 根据动画当前的 HDR 高光强度直接确定一条全片固定曲线，在正式导出的单次渲染中
应用；不得为了计算该曲线而预先完整运行一遍音频驱动、模拟与亮度统计。

该选择优先保证跨帧一致性、结果可复现和导出耗时。某些全程较暗的内容可能因此没有用满 SDR
顶部范围；希望按每帧亮度分布更充分利用范围的用户应选择 `动态映射`。

## D68（2026-07-28）仅保留高光 SDR 为超白内容压缩高亮端

高亮端压缩只适用于 `SDR（保留高光层次）`。该模式按当前 HDR 强度生成 `>1.0` 的场景高光，
因此必须在 SDR 顶部范围内为其腾出空间：暗部与中间调保持不变，在尚待标定的高亮起点之后
平滑压缩接近 SDR 白及以上的内容，不增加 toe、全局曝光或整体压暗。

`SDR（原生渲染）` 不受该策略影响：它继续关闭 HDR 额外高光重新渲染，不做色调映射，也不
增加高亮肩部。不得为了实现保留高光模式而改变原生 SDR 的既有亮度或色彩。

## D69（2026-07-28）保留高光 SDR 只重分配亮度，不额外改变颜色

FableSol 的 HDR 源画面已经为不同高光设计了各自的颜色：银丝与星芒核心接近白色，逆光透射、
外围光效和色散仍有意保留身份色。SDR 色调映射不得再统一执行顶部去饱和，也不得覆盖这些既有
创作关系。

色调映射对一个统一的亮度尺度求值，再以同一比例缩放 RGB；禁止分别对 R、G、B 应用不同曲线。
由此，中性白继续保持中性白，带色高光保持原有通道比例、色相和饱和度。输出边界处理也应采用
同比例收缩，不以逐通道硬钳制造新的偏色。

## D70（2026-07-28）首版不提供 SDR 压缩强度控制

`SDR（保留高光层次）` 首版不再增加“肩部起点”“高光保留量”之类的滑杆或预设。HDR 高光
强度负责源信号，稳定/动态映射负责时间行为；在没有导出画面预览的设置页继续增加曲线参数，
用户难以仅凭数值判断结果，也会扩大无效或冲突的参数组合。

高亮端曲线由当前 HDR 强度与选定映射方式自动确定，并通过固定样本和回归测试标定。信息栏用
用户可理解的语言说明普通高亮会略微压缩、为超白高光保留空间，不显示内部曲线数值。

## D71（2026-07-28）动态映射只允许调整 HDR 额外高光

`稳定映射` 与 `动态映射` 对源场景 `0～1.0` 的 SDR 基础范围使用同一条固定曲线。暗部、
中间调以及普通白的映射不得随帧变化；D68 已确认的高亮端基础压缩也必须保持全片一致。

`动态映射` 的逐帧测量与时间平滑只允许调整 `>1.0` 的 HDR 额外高光如何分配到剩余 SDR
顶部范围。由此，星芒和银丝可以按当前画面利用更多范围，但背景、水体主体及其既有颜色关系不
得随高光出现而产生亮度“呼吸”。本决定将 D65 所述“可能轻微变化”收窄为仅限超白高光区域。

## D72（2026-07-28）SDR 高亮压缩以 BT.2446 Method B 为标定依据

`SDR（保留高光层次）` 的基础曲线参考 ITU-R BT.2446 Method B 的制作意图与约束：暗部和
中间调保持稳定，高亮端采用连续的平滑压缩，HDR 参考白在完整标定量下约落到 SDR 信号的
90%，为压缩后的高光保留顶部空间；色调映射只作用于亮度。

FableSol 的输入已经是 FP16 扩展显示线性 Rec.709，不是已编码的 BT.2100 PQ/HLG、BT.2020 视频。
因此实现只采用 Method B 的标定原则与曲线约束，不额外执行 HLG/PQ 往返转换，也不宣称完整
符合该方法的全部广播转换流程。D69 已决定不采用可选的高光消色处理。

当 HDR 强度为 `1.0×` 时曲线严格恒等；随强度提高，顶部预留连续增加，到 `9.6×` 使用完整
标定量，不得在关闭与开启 HDR 的边界发生亮度跳变。

参考：
<https://www.itu.int/dms_pub/itu-r/opb/rep/R-REP-BT.2446-1-2021-PDF-E.pdf>

## D73（2026-07-28）动态 SDR 映射只由 FableSol 超白内容驱动

`动态映射` 在 FableSol 场景完成水体、银丝、星芒与眩光合成之后、执行 SDR 色调映射之前，
从 FP16 扩展显示线性工作空间中测量 `>1.0` 的 HDR 额外高光。

外侧 padding、画框背景、投影、描边和时钟均属于 SDR 图形元素，不纳入动态统计，也不得与
水体竞争高光预算。动态曲线只能响应 FableSol 自己实际生成的超白内容。

## D74（2026-07-28）动态 SDR 曲线采用快压慢放的时间响应

`动态映射` 复用现有 HDR10+ 曲线已经验证的时间响应：

- 高光峰值上升时，压缩控制量以 `0.08s` 时间常数快速跟进，避免突然削顶。
- 高光峰值下降时，以 `0.80s` 时间常数缓慢释放，避免剩余高光突然跳亮。
- 第一帧直接按该帧实测值初始化，不从占位值渐变。

时间平滑只作用于色调映射曲线的控制量，不做跨帧画面混合，不得引入拖影。

## D75（2026-07-28）动态 SDR 曲线由真实超白峰值驱动

`动态映射` 使用当前帧 FableSol 超白区域的真实峰值，并将控制量约束在 `1.0～当前 HDR
强度`；不得用 P99 等高分位数替代真实峰值。

银丝和星芒是刻意设计的稀疏高光，高分位统计可能完全漏掉它们。真实峰值判据必须保证最亮核心
也进入曲线且不被剪平；由于 D71 已将动态变化限制在 `>1.0`，极小高光占用顶部范围也不得改变
背景和水体主体的映射。

## D76（2026-07-28）SDR 色调映射统一使用 maxRGB 亮度尺度

保留高光 SDR 的逐像素曲线自变量与动态模式的逐帧峰值均定义为 `max(R,G,B)`，不使用
Rec.709 加权亮度 `Y`。

曲线先把输入 `maxRGB` 映射为目标峰值，再以“目标峰值 ÷ 输入峰值”的同一比例缩放三个通道。
这样任何带色高光都不会因某个通道权重较低而逃过压缩，输出 RGB 可共同进入 SDR 范围，同时
保持原有通道比例。BT.2446 Method B 继续只作为曲线标定与高亮压缩原则的参考，不照搬其
`Y` 通道实现。

## D77（2026-07-28）动态统计不可用时退到稳定映射并明示

动态峰值归约与读取应纳入设备能力探测。探测已知不可用时，`动态映射` 选项置灰并在信息栏说明
原因；不得因此改写用户保存的映射偏好。

若正式导出期间动态统计通路失败，则丢弃该次尝试并从第 1 帧改用 `稳定映射` 重新导出。不得
进一步退到原生 SDR，因为 FP16 扩展显示线性画面与固定色调映射仍可保留高光层次。设置页、
导出完成信息与相关日志必须显示“动态统计不可用，实际采用稳定映射”，不得把降级结果仍标成
动态映射。

## D78（2026-07-28）FP16 扩展显示线性渲染不可用时退到原生 SDR 并明示

FP16 扩展显示线性渲染能力是 `SDR（保留高光层次）` 的硬前提。探测已知不支持时，该选项继续
显示但置灰，信息栏说明设备缺少 FP16 扩展线性渲染能力；这与 D64 中 HDR 强度为 `1.0×` 时仍
允许选择不是同一种情况。

已保存该偏好的用户换到不支持的设备，或正式导出时 FP16 渲染目标分配失败，则丢弃当前尝试并
从第 1 帧改用 `SDR（原生渲染）`。不得改写用户偏好；完成信息与日志必须显示实际采用了原生
SDR，不能继续标注“保留高光层次”。

## D79（2026-07-28）继续使用 FP16 扩展显示线性 Rec.709 共用工作空间

FableSol 的身份色、背景、描边与时钟等创作颜色来自 sRGB/Rec.709，shader 将其解码到显示线性
光，并以 FP16 保存 `>1.0` 的 HDR 亮度。该空间明确为已经包含 FableSol 创作与渲染意图的
display-referred 工作空间，不是摄像机曝光意义上的 scene-referred 辐亮度；中性
`R = G = B = 1.0` 表示 HDR Reference White。

本轮不把内部工作色域迁移到线性 BT.2020：现有源颜色没有 Rec.709 之外的色度信息，扩大工作
色域不会凭空增加真实颜色，反而可能改变已经验收的混合与身份色关系。SDR 在最终输出阶段编码
为 BT.709；HDR 在最终输出阶段才转换到 BT.2020 并编码为相应传递函数。色域与动态范围必须
分开处理，不得因 FP16 能表示 HDR 亮度就把工作空间误称为 BT.2020 广色域，也不得因为 HLG
OETF 接受场景线性输入，就把这份已经具有显示意图的共用工作空间重新解释成场景光。

## D80（2026-07-28）两种 SDR 模式共用标准 BT.709 输出定义

`SDR（原生渲染）` 与 `SDR（保留高光层次）` 均在共用显示线性工作空间完成各自处理后应用
BT.709 OETF，并以 BT.709 color standard 与 SDR video transfer 编码；二者只在 HDR 高光
是否生成及是否执行色调映射上不同，不得使用两套颜色解释。

本轮不增加 Display P3 SDR 或 BT.2020 SDR。FableSol 的源颜色仍位于 Rec.709 内，扩大 SDR
容器色域不会新增真实颜色，却会降低播放器与分享平台兼容性。Surface 输入的 RGB→YUV 色彩范围
以编码器实际回报为准并写入容器；应用自行生成 YUV 的通路则由应用声明与样本一致的范围。

## D81（2026-07-28）SDR 文件名区分原生、稳定与动态映射

两种 SDR 模式在标准视频元数据中都会被标记为 BT.709 SDR，文件离开应用后无法仅凭
color standard 与 transfer 判断其生成方式。文件名因此使用稳定的短标签区分：

- `SDR`：`SDR（原生渲染）`，沿用既有标签，保持旧文件命名兼容。
- `SDR-TM`：`SDR（保留高光层次）·稳定映射`。
- `SDR-DTM`：`SDR（保留高光层次）·动态映射`。

导出完成 Dialog、完成通知和相关日志不得只显示短标签，必须显示完整的本地化模式名称；若发生
D77 或 D78 定义的运行时降级，还必须显示实际采用的稳定映射或原生 SDR。文件名标签同样以最终
实际产物为准，不得保留失败尝试的 `SDR-DTM` 或 `SDR-TM` 标签。

## D82（2026-07-28）默认 HDR 母版亮度意图与导出设备无关

同一份 FableSol 动画在创作参数相同的情况下，默认应具有相同的 HDR 亮度意图；不得因为负责
导出的手机、平板或外接屏幕不同而自动改写 PQ 像素、漫反射白、母版峰值、MaxCLL、MaxFALL 或
HDR10+ 动态元数据。

Android `Display.HdrCapabilities` 提供的是当前显示设备希望接收的内容峰值、最大帧平均亮度等
显示能力，不是该内容应采用的母版规范。它们后续只用于本机预览映射、设备诊断以及“当前屏幕
可能发生高光压缩”的提示，不再参与默认导出信号和元数据的计算。

本决定取代 D45 中“自动漫反射白随导出设备的面板峰值与最大帧平均亮度变化”的默认语义；
D45 作为既有实现与问题演变记录保留。用户明确选择的创作亮度参数仍可改变母版，本决定只禁止
以导出设备身份隐式改变内容。

## D83（2026-07-28）PQ 默认漫反射白采用 203 尼特

HDR10 与 HDR10+ 等 PQ 输出在用户没有明确进行亮度创作调整时，以
`203 cd/m²` 作为设备无关的漫反射白及图形白基准；“恢复默认”同样回到 203 尼特。该值采用
ITU-R BT.2408 的名义 HDR Reference White，不再由导出设备反推。

漫反射白只定义普通白的绝对亮度，不是 HDR 峰值上限。当前最大 `9.6×` HDR 强度对应的解析峰值
约为 `203 × 9.6 = 1949 cd/m²`，仍保留完整 HDR 高光。相较原先 400 尼特默认值对应的
3840 尼特峰值，这一默认组合更少依赖播放端强压缩，也更有利于保留稀疏有色高光的层次与色容积。

200～800 尼特的手动创作范围继续保留；用户明确选择的手动值仍同时驱动 PQ 像素绝对亮度与相关
元数据。HLG 与杜比视界 8.4 为相对亮度路径，不使用该参数。

## D84（2026-07-28）PQ 漫反射白使用“标准/自定义”界面语义

D82 取消按导出设备自动计算母版亮度后，漫反射白控件不再显示“自动/手动”，避免把固定的创作
基准误解为设备自适应结果：

- 未经用户调整或执行“恢复默认”后，显示 `标准（203 尼特）`。
- 用户拖动后，显示 `自定义（N 尼特）`，继续使用 D83 保留的 200～800 尼特创作范围。
- 不为首版增加“明亮”等额外预设；直接调整现有参数即可表达该意图。

设置页下方的信息栏可以显示本机 HDR 峰值与最大帧平均亮度，作为当前设备播放能力的参考。若
解析出的母版峰值超出本机能力，应提示播放时可能发生高光映射，但不得自动修改漫反射白、
HDR 强度或任何输出元数据。

## D85（2026-07-28）HDR10 与 HDR10+ 编码前预分析全片静态亮度

当前 `MaxCLL = 漫反射白 × HDR 强度` 只是渲染管线的理论上限，
`MaxFALL = 漫反射白` 则是保守估计；二者都没有描述最终动画在整段时间内实际出现的内容亮度。
HDR10 静态内容亮度元数据应分别表示全片最高内容亮度和全片最高帧平均亮度，继续使用参数代替
测量可能诱发播放设备不必要或不准确的静态色调映射。

HDR10 与 HDR10+ 在正式配置编码器之前默认增加一次全片亮度预分析，取得实际 `MaxCLL` 与
`MaxFALL` 后再开始正式编码。预分析允许多运行一次音频驱动、动画模拟和画面渲染，但不重复执行
视频与音频编码；首版不提供关闭开关，不以不准确的静态元数据换取较短耗时。

杜比视界 8.4 采用 HLG 兼容基层，不复用 PQ 静态元数据路径；其编码器生成的 RPU 与兼容基层如何
进行产物复核，由第六大点后续决定。

## D86（2026-07-28）MaxCLL 与 MaxFALL 按最终 BT.2020 线性 maxRGB 全分辨率统计

HDR10/HDR10+ 的全片预分析使用最终实际导出尺寸，并覆盖最终可见合成画面：FableSol 场景、
画框背景、卡片、投影、描边与时钟都属于作品内容，必须纳入。只有真正位于可见裁切区域之外的
编码填充才可排除；不得套用 D73 为动态 SDR 创作映射而排除外围图形的统计口径。

统计位置位于线性 Rec.709 转换成线性 BT.2020 之后、PQ 编码与量化之前。对每个像素定义
`Emax = max(R, G, B)`：

- `MaxCLL` 为全片全部像素 `Emax` 的最大值。
- `MaxFALL` 为每帧全部可见像素 `Emax` 平均值的全片最大值。

该口径遵循 ITU-T H.274 §8.10 对 Content Light Level Information 的定义，不改用 CIE Y 或
Rec.709/BT.2020 加权亮度，也不用缩略图、块平均或分位点近似。写入 16-bit 整数尼特字段时向上
取整，使结果仍是实际信号的上界；不得使用会向下舍入的普通四舍五入。

## D87（2026-07-28）HDR 虚拟母版白点采用 D65

FableSol 的 HDR 虚拟母版使用 CIE 1931 `x=0.3127, y=0.3290` 的 D65 白点，与 sRGB、
BT.709、BT.2020/BT.2100 的现有中性白定义保持一致。D65 只规定中性灰与白的色度，不规定其
亮度；PQ 漫反射白的 203 尼特继续由 D83 独立定义。

当前 HDR 静态元数据已经写入该 D65 坐标，后续实现不得因重构虚拟母版或内容亮度统计而改变，
也不增加 D50、DCI 白点等与现有源颜色不一致的选项。

## D88（2026-07-28）BT.2020 编码容器使用 P3-D65 虚拟母版 primaries

HDR10 与 HDR10+ 的视频信号继续以 BT.2020 primaries 编码；ST 2086 Mastering Display
Colour Volume 中的虚拟母版显示器 primaries 则采用 Display P3，并使用 D87 的 D65 白点。

编码色域与母版显示器色域表达的是两件事：BT.2020 决定码值如何解释，P3-D65 描述承载创作意图
的母版显示色容积。ITU-T H.Sup19 将 P3-D65 1000/4000 尼特列为常见 HDR 母版环境，并明确
允许将限制在 P3 内的 HDR 内容装入 BT.2020 编码容器。

FableSol 现有身份色仍是 Rec.709 子集，完整位于 P3 之内；本决定只修正 ST 2086 元数据，不做
Rec.709→P3 的创作扩色，不改变 shader 输出像素，也不把内容描述成实际使用了 P3 全色域。

## D89（2026-07-28）虚拟母版亮度范围描述渲染能力，不代替内容实测

HDR10/HDR10+ 的 ST 2086 虚拟母版最低亮度固定为 `0.0001 cd/m²`；最高亮度取
`ceil(漫反射白 × HDR 强度)`，并限制在 PQ 的 `10000 cd/m²` 信号上限内。若 D86 的最终合成
预分析因数值边界测得略高的 `MaxCLL`，虚拟母版最高亮度至少覆盖该实测值。

该最高亮度不按 1000/2000/4000 尼特监视器档位取整，也不读取导出设备能力。FableSol 没有实际
调色监视器，虚拟母版的用途是准确描述当前程序化渲染允许的数学色容积；任意固定的更高档位都
可能使只采纳 MDCV、忽略 CLLI 的播放端进行不必要的强压缩。

字段职责保持分离：

- ST 2086 的最高/最低亮度与 P3-D65 primaries 描述虚拟母版显示色容积。
- `MaxCLL` 描述该视频全片实际出现的最高 `maxRGB`。
- `MaxFALL` 描述该视频实际出现的最高帧平均 `maxRGB`。

这些元数据不改变 PQ 像素本身，只为目标显示设备的静态色调映射提供边界与内容信息。

## D90（2026-07-28）静态亮度预分析失败时使用理论 MaxCLL 与未知 MaxFALL

漫反射白是 PQ 绝对亮度的缩放锚点，不等同于内容亮度统计。预分析成功时仍以 D86 的全片实测
结果为准；只有统计归约或读回失败、但画面渲染本身仍然有效时才采用以下回退：

- `MaxCLL = ceil(漫反射白 × HDR 强度)`。shader 已将场景限制在该理论峰值内，BT.2020 线性
  转换也不扩大中性最大分量，因此它是有效但偏保守的内容亮度上界。
- `MaxFALL = 0`。漫反射白不能保证是每一帧平均 `maxRGB` 的上界；把它直接写成 MaxFALL 可能
  低报。把理论峰值同时写成 MaxFALL 虽然安全，却会暗示可能出现接近峰值的全屏亮画面并诱发
  过度保守的映射。按 H.274，零明确表示未提供该上界，不表示零尼特画面。

设置页、完成信息与诊断必须区分“全片实测”和“理论上界/未知”，不得把回退值显示成实测结果。
若失败源于画面渲染本身，则不得套用本回退并发布；应按正常候选失败处理。

HDR10+ 若只有全片汇总失败、逐帧动态统计仍正常，可保留 HDR10+ 并使用上述静态回退；若逐帧
动态统计也失败，则该尝试不得继续标为 HDR10+，应从第 1 帧按既有阶梯改用 HDR10。

## D91（2026-07-28）HDR10 静态元数据是带用户提示的发布硬门禁

应用生成的 ST 2086 MDCV、`MaxCLL` 与 `MaxFALL` 是 HDR10/HDR10+ 内容定义的一部分，不能只
在 `MediaCodec.configure()` 时提交后便假定厂商编码器和封装器一定保留。

应用作为静态元数据权威执行以下门禁：

1. 编码器输出格式未回报 `KEY_HDR_STATIC_INFO` 时，将应用生成的预期 25 字节描述符补入交给
   `MediaMuxer` 的视频轨格式；编码器回报了不同内容时不得静默采纳。
2. （经 D166 修订）逐字段回读核对定位在**短探测产物**上执行：核对 P3-D65 MDCV、虚拟母版
   亮度范围、`MaxCLL`、`MaxFALL`、BT.2020 standard 与 PQ transfer；容器静态元数据缺失、
   不一致，或码流携带冲突的 MDCV/CLLI SEI 时，在短探测阶段淘汰该候选。MP4 容器中的正确
   静态元数据构成有效承载，不强制要求编码器另行重复生成同值 SEI。
3. （经 D166 修订）正式导出以元数据注入、有效视频样本与编码/封装真实错误为候选成败条件；
   完整编码并成功封装后，对正式产物的附加解析只记录诊断，不删除、不重编码、不改报失败
   （D142）。
4. （2026-07-30 修订措辞）HDR10+ 的发布前硬门禁为**码流确实携带 ST 2094-40 SEI**。本条
   引入时指认的"现有门禁"即任一样本证据（2026-07-27 提交 `55a2452f`，早于本决策一天）；
   原文的"逐帧"描述该 SEI 的逐帧性质与输入侧的逐帧注入，不是逐帧核验要求。实现按逐样本
   计数：覆盖率始终进设备诊断，部分覆盖（0 < N < 总数）在完成信息如实说明、不改报失败
   ——末端逐帧硬门禁会把数十分钟的完整渲染因假设性设备行为判死（D142 反对的方向）。
   若真机日后出现部分覆盖的实测证据，凭数据再裁定是否升级门禁。

门禁结果必须面向用户可见：

- 设置页能力信息列出静态 HDR 元数据是否通过实际封装回读验证；被拒绝的候选保留具体原因。
- 某候选失败但后续候选成功时，完成信息显示最终实际格式与编码器；详细诊断可说明曾因静态
  元数据缺失或冲突而换档。
- 全部候选失败时明确提示“静态 HDR 元数据缺失或不一致，产物未发布”，并提供可复制的期望值、
  实际值和失败阶段；不得只显示笼统的“导出失败”。
- 成功产物显示最终写入的虚拟母版范围、实测 `MaxCLL/MaxFALL`；若采用 D90 回退，则明确标注
  `MaxCLL` 为理论上界、`MaxFALL` 为未知。

## D92（2026-07-28）成功的 HDR 全片亮度预分析按完整渲染指纹持久缓存

全片预分析结果在输入与渲染条件相同时是确定的。为避免重复导出 HDR10/HDR10+ 时再次运行完整
动画，持久缓存完整成功的归一化 `MaxCLL`、`MaxFALL` 及其统计状态。

缓存键必须覆盖所有能改变预编码画面的条件：

- 音频文件完整内容摘要，包括本应用 WAV 中的倾斜轨迹等自定义 chunk；不得只依赖 URI、文件名、
  大小或修改时间。
- 全部 FableSol 视觉调节值、是否保留倾斜、实际应用明暗外观及最终画框/背景参数。
- HDR 强度、实际导出分辨率、实际帧率以及会影响采样、抗锯齿或最终合成的规格。
- 独立的亮度统计/渲染契约版本；相关 shader、颜色转换、合成范围或统计定义变化时必须升级版本。

HDR10 与 HDR10+ 的基础 PQ 像素相同时共用缓存；HDR10+ 候选失败后重试 HDR10 也直接复用。
编码器、码率、CQ/CBR 和关键帧间隔不改变预编码像素，不进入缓存键。

缓存保存相对于漫反射白的归一化统计，因此只调整 PQ 漫反射白时可重新缩放到尼特，无需重新
渲染；会改变场景本身的 HDR 强度仍进入键。只有完整成功的预分析可写入缓存，取消、部分结果、
异常以及 D90 的回退状态不得缓存。缓存随应用缓存清理，并在契约版本变化时自然失效。

## D93（2026-07-28）HDR 显示能力只决定预览可信度，编码能力才决定导出资格

本机屏幕是否支持 HDR、支持哪一种 HDR 格式以及自身峰值，不得作为 HDR 导出选项的可用性门禁。
只要当前设备的 10-bit 输入、编码器 Profile、EGL/P010、动态/静态元数据与 MP4 封装通路通过
真实验证，即使本机屏幕不能播放该格式，也允许生成用于其它设备或平台的合规文件。

显示能力只用于信息与预览边界：

- 本机不支持所选 HDR 格式时，信息栏提示当前屏幕无法准确预览，最终效果需在兼容设备查看。
- 本机支持该格式但声明峰值低于虚拟母版峰值时，提示本机播放预计会进行高光映射。
- 当前预览未使用与目标产物等价的 HDR surface/transfer 时，必须明确标为 SDR 代理预览，
  不得让用户误以为屏上效果就是最终 HDR。

屏幕支持类型、亮度设置、折叠屏内外屏或外接屏变化不得重新排序 `HDR（自动）` 候选，不得改写
漫反射白、HDR 强度、HDR10+ 元数据或输出像素。编码能力失败仍按既有候选阶梯处理；显示能力
不足不得伪装成编码失败。

## D94（2026-07-28）HDR10+ 参考显示峰值使用滑杆并允许显式采用本机声明值

HDR10+ 的 `targeted_system_display_maximum_luminance` 不再由导出设备在后台静默决定，而是
作为仅在 HDR10+ 下显示的“参考显示峰值”创作参数：

- 滑杆范围为 300～10000 尼特；300～1000 尼特每档 25 尼特，1000～4000 尼特每档
  100 尼特，4000～10000 尼特每档 500 尼特。
- 默认值和“恢复默认”均为 `标准（1000 尼特）`。
- 滑杆下方提供可直接选取的 400、600、1000、2000、4000 尼特参考值。
- 另提供 `采用本机值（N 尼特）`。它读取当前设置界面所在显示设备通过 Android
  `Display.HdrCapabilities.getDesiredMaxLuminance()` 声明的期望 HDR 内容峰值，并将该值填入
  滑杆；这是一次性取值并保存数值，不建立持续跟随关系。折叠屏内外屏、外接屏、显示模式或
  后续设备变化不得自行改写已经选择的值。

Android 返回的是显示设备声明的“期望内容最大亮度”，不是仪器实测面板峰值，也不等于当前
亮度。信息栏必须说明省电、温度、窗口面积和面板功率限制仍可能使实际播放亮度低于该值；
读取不到有效值时不伪造本机值。若当前显示设备不支持 HDR10+，还需明确提示本机播放可能退回
HDR10，选择本机峰值不会让 HDR10+ 动态层在本机生效。

参考显示峰值只改变 HDR10+ 动态元数据中的引导曲线及对应目标字段，不改变 PQ 基础像素、
ST 2086 虚拟母版范围、`MaxCLL` 或 `MaxFALL`。D82/D93 的设备无关默认原则继续成立：
只有用户显式执行“采用本机值”才允许把本机声明值转化为持久的创作参数。

## D95（2026-07-28）HDR10+ 曲线横轴按元数据时间区间的实际内容峰值归一化

现有 `FableSolExportHdr10PlusCurve` 使用
`Kx = kneeNits / masteringPeakNits`，把 FableSol 的虚拟母版峰值当成 HDR10+ 曲线横轴
`s = 1` 的含义；但当前载荷使用 ApplicationVersion 1，且不携带
`MasteringDisplayActualPeakLuminance`。播放器没有任何字段可据此得知这条横轴采用了虚拟母版
峰值，因而该算法与 ST 2094-40 的归一化语义不一致。

改为：

- 每个 HDR10+ 元数据时间区间以非零
  `max(MaxSCL.R, MaxSCL.G, MaxSCL.B)` 作为输入归一化峰值。当前元数据逐帧生成，因此该值就是
  本帧实测内容峰值。
- `MaxSCL` 无效或全零时，回退到该时间区间最后一个
  `DistributionMaxRGBPercentiles`；ApplicationVersion 1 的 `J8 = 99` 必须按规范实际计算
  99.98% 分位值。
- 横向膝点按 `Kx = kneeNits / sourceNormalizationPeakNits` 计算；纵向膝点仍按
  `Ky = kneeNits / targetedSystemDisplayMaximumLuminance` 计算。
- 时间平滑继续作用于绝对膝点亮度；每个时间区间再使用当期实际内容峰值把平滑后的绝对膝点
  转成 `Kx`。
- 虚拟母版峰值继续限定 FableSol HDR 渲染范围并写入 ST 2086 静态元数据，但不再参与 HDR10+
  曲线横轴归一化。

现有基于错误横轴推导的 `kneeCeilingNits()`、`MIN_TARGET_HEADROOM` 以及“参考显示峰值至少为
漫反射白两倍”的强制限制不得保留；曲线的连续性和单调性应基于正确的实际内容峰值重新求解。
“内容峰值不超过参考显示峰值时是否仍携带曲线”留待下一项单独决定。

## D96（2026-07-28）内容峰值不超过参考显示峰值时不写 HDR10+ 创作曲线

当某个 HDR10+ 元数据时间区间的实际输入归一化峰值不高于用户选择的
`targeted_system_display_maximum_luminance` 时，该区间设置 `tone_mapping_flag = 0`，不携带
KneePoint 与 BezierCurveAnchors；参考显示峰值、MaxSCL、AverageMaxRGB、DistributionMaxRGB 和
FractionBrightPixels 仍按各自契约完整写入。

不得用所谓“单位曲线”代替省略曲线。HDR10+ 贝塞尔曲线的归一化终点固定为 `(1, 1)`，而输入
横轴和输出纵轴分别以实际内容峰值及参考显示峰值归一化；两者不相等时，归一化单位曲线并不保持
绝对 PQ 亮度。例如 600 尼特内容配 1000 尼特参考显示会表达把最高内容扩张到 1000 尼特，属于
主动改变亮度和对比度，而非中性映射。

省略创作曲线不等于删除 HDR10+ 元数据，也不影响 HDR10 PQ 基础层。兼容接收端仍可利用强制
存在的内容统计针对自己的实际显示能力生成映射。曲线启停在参考峰值附近的时间稳定策略留待
下一项决定。

## D97（2026-07-28）HDR10+ 曲线启停采用只位于参考峰值上方的迟滞

HDR10+ 曲线是否存在使用有状态的双阈值判定，不直接以
`sourceNormalizationPeakNits > targetNits` 逐帧硬切：

- 曲线关闭时，实际内容峰值超过较高的开启阈值才开启。
- 曲线开启后，实际内容峰值下降到较低的关闭阈值才关闭。
- 两个阈值都必须位于参考显示峰值之上；内容峰值一旦不高于参考显示峰值，立即执行 D96，
  不写曲线。不得通过延迟关闭让曲线继续作用于无需压缩的内容。
- 曲线关闭期间仍连续更新绝对膝点及其时间平滑状态，不得清空或重新初始化；随后再次开启时
  从连续状态恢复。

该迟滞只稳定 `tone_mapping_flag` 和引导曲线状态，不改变 PQ 基础像素、逐帧亮度统计或用户
选择的参考显示峰值。具体双阈值留待下一项决定。

## D98（2026-07-28）HDR10+ 曲线迟滞采用 1% 开启与 0.25% 关闭阈值

设用户选择的参考显示峰值为 `T`，使用写入元数据前、尚未量化到 0.1 尼特码值的高精度实际
内容峰值 `P` 判定：

- 当前曲线关闭时，`P >= 1.01T` 才开启。
- 当前曲线开启时，`P <= 1.0025T` 即关闭。
- `P <= T` 时始终立即关闭，继续满足 D96。
- 两个阈值之间保持已有状态。

采用相对比例而非固定尼特差，使同一策略适用于 300～10000 尼特的完整参考峰值范围。例如
300、1000、4000 尼特对应的开启阈值分别为 303、1010、4040 尼特，关闭阈值分别为
300.75、1002.5、4010 尼特。判定不得先把峰值四舍五入成载荷中的 MaxSCL 码值，避免量化边界
反过来驱动曲线启停。

## D99（2026-07-28）达到开启条件的一帧真实高光也立即启用 HDR10+ 曲线

曲线启用不增加“连续超标若干帧”或“持续若干毫秒”的时间门槛。只要当前帧的有效高光统计达到
D98 的开启条件，该帧立即携带曲线；短暂的星芒、银丝反光等不得因为在 120 fps 下只维持一两帧
而错过动态映射。

`tone_mapping_flag` 的可用性立即响应，但绝对膝点仍沿用其时间平滑状态，因此并不等于把曲线
形状无过渡地重置到本帧目标。峰值回落后严格按 D96～D98 关闭，不另设按帧数或毫秒计算的保持
时间。时间上只出现一帧是否有效与空间上只有极少数像素是否应驱动整帧曲线是两个独立问题；
后者留待下一项决定。

## D100（2026-07-28）HDR10+ 曲线启停由逐像素 99.98% 分位值驱动

D98 中用于曲线启停迟滞的高精度峰值 `P` 改为当前元数据时间区间内，全分辨率、线性
BT.2020 `max(R,G,B)` 累积分布的真实 99.98% 分位值，不使用 MaxSCL，也不使用当前 32×32
归约纹理中的块平均分布。

由此：

- 只有约 0.02% 或更多像素构成的超目标高光才会开启全局创作曲线；少数孤立异常值不得仅凭
  MaxSCL 改变整帧映射。
- 一旦曲线开启，D95 不变：横轴 `s = 1` 仍使用非零 `max(MaxSCL)` 归一化，真实极值仍被曲线
  范围和元数据完整覆盖。
- 若 99.98% 分位值未达到 D98 的开启阈值、但 MaxSCL 超过参考峰值，则不写创作曲线，同时仍
  如实写入 MaxSCL 和 DistributionMaxRGB，允许接收端自行处理稀疏极值。
- D99 的立即时间响应继续成立：只持续一帧但空间覆盖达到上述条件的高光，该帧立即启用曲线。

99.98% 不是另造的私有百分位。ST 2094-40 对 ApplicationVersion 1 的
`J8 = 99` 明确要求以 99.98% 计算 `V8`；同一份合规逐像素统计同时作为码流描述和曲线启停依据，
但不得改变 MaxSCL 作为横轴归一化终点的独立职责。

## D101（2026-07-28）修正 HDR10+ ApplicationVersion 1 的标准分布向量

继续使用生态兼容性最好的标准九项
`J = [1, 5, 10, 25, 50, 75, 90, 95, 99]`，但按 ST 2094-40
ApplicationVersion 1 的特殊约束写入对应 V 向量：

- `V1` 固定为 `0.00000`。
- `V2` 固定为 `0.00255`，即以 10000 尼特为归一化上限时的 25.5 尼特。
- `V1/V2` 是该标准 J 布局的保留标记，不是实际 5% 与 10% CFD 分位值。
- `V8` 按 D100 写入真实 99.98% 分位值，不得继续按字面 99% 计算。
- 其余 `V0/V3～V7` 按各自 J 值从逐像素、线性 maxRGB CFD 中计算。

内部算法若需要真实 5% 或 10% 分位值，可以在独立统计结构中保留，但不得写进 V1/V2。载荷
编码、回读解析、发布门禁和逐字段测试都必须识别上述特殊语义，不能只检查九项数量与 J 向量。

**2026-07-28 原文验证**：已对照 SMPTE ST 2094-40:2020 正文 §8.5.4 核实，本条数值与原文
完全一致，可直接作为实现与测试依据。原文措辞："The length of the vectors shall equal
9"；"Whenever J8 equal to 99 is present, the percentage value 99.98% shall be used in
the calculation of V8"；"If and only if J1=5 and J2=10, the vector elements for V1 and
V2 are not part of the CFD, V1 shall be 0.00000, V2 shall be 0.00255 and other values
for V1 and V2 are reserved"。来源：
<https://pub.smpte.org/pub/st2094-40/st2094-40-2020.pdf>

## D102（2026-07-28）AverageMaxRGB 按最终线性 BT.2020 逐像素精确平均

每个 HDR10+ 元数据时间区间的 `AverageMaxRGB` 必须在最终可见合成画面已经转换到线性
BT.2020、尚未进行 PQ 编码与量化的位置计算。对每个像素先取
`Emax = max(linearR, linearG, linearB)`，再对区间内全部像素等权求和并除以真实像素总数；
只在写入载荷时量化到 ST 2094-40 的 `0.00001` 步长。

现有“在 PQ 码值域求块平均，再对块平均值求平均，最后只做一次 EOTF”的算法必须删除，因为
PQ 非线性使 `EOTF(mean(PQ))` 不等于 `mean(EOTF(PQ))`。若实现采用分块 GPU 归约，每块必须
保留线性总和与实际像素数，CPU 端按像素数加权；不得把边缘不足整块的区域与完整块等权，也不得
继续用 RGBA8 PQ 块平均值充当 AverageMaxRGB。

统计范围继续覆盖 D86 定义的最终完整画面，不得只统计水体或忽略画框、时钟、背景等合成内容。

## D103（2026-07-28）MaxSCL 按最终线性 BT.2020 全像素高精度通道峰值计算

每个 HDR10+ 元数据时间区间的 MaxSCL 分别取最终线性 BT.2020 完整画面全部像素的
`max(R)`、`max(G)`、`max(B)`。统计应与 D102 共享同一高精度、PQ 编码前的数据来源，中间
归约不得再把通道峰值存入 RGBA8 PQ。

当前 RGBA8 PQ 归约在约 300、600、1000、2000、4000 尼特附近的单码级亮度间隔已分别约为
11、22、36、72、145 尼特，既大于 D98 的迟滞尺度，也远大于 MaxSCL 字段本身的 0.1 尼特
量化步长。后续实现可以继续分块归约，但块峰值必须保持源线性缓冲的有效精度；只允许在最终写入
17 位 MaxSCL 字段时统一量化到 0.1 尼特。

该高精度 MaxSCL 同时作为 D95 的曲线横轴归一化终点和接收端内容极值描述，不得从码流量化值
反向驱动导出期间的曲线计算。

## D104（2026-07-28）HDR10+ 精确统计采用两级后端，失败后回归全局 HDR 候选顺序

HDR10+ 逐像素动态统计按以下顺序尝试：

1. GLES 3.1 compute shader、SSBO 与原子计数实现的 GPU 高速逐像素直方图。
2. 高速路径不可用或已知图验证失败时，使用 GLES 3.0 精确兼容路径：片元 shader 为每个像素
   计算线性 BT.2020 maxRGB 并高精度打包进全分辨率 RGBA8 统计纹理，通过项目已有保证路径的
   `glReadPixels(GL_RGBA, GL_UNSIGNED_BYTE)` 读回；CPU 只做解包、直方图计数与求和，不执行
   P010 到 RGB 的逐像素重建。界面提示该兼容路径可能增加导出时间，但统计定义与画质不降级。

当前 32×32 块平均不得成为第三个 HDR10+ 发布后端。它可以保留作诊断或不进入码流的内部启发
统计，但不得冒充 ST 2094-40 的逐像素 CFD。

两条精确路径都失败时，只判当前 HDR10+ 候选失败，并回到 D48 的全局 HDR 规格顺序。若导出
过程已经尝试到 HDR10+，后续依次为：

`HDR10+ → 杜比视界 8.4 → HDR10 → HLG`

不得从 HDR10+ 直接跳过杜比视界 8.4 落到 HDR10。候选仍须服从 D55 的当前编码器族等全部
用户约束；完成信息与诊断显示最终实际采用的格式及 HDR10+ 两条统计路径的失败原因。

## D105（2026-07-28）显式选择 HDR10+ 时失败即失败，不发布任何替代格式

跨 HDR 格式的 D48/D104 候选顺序只适用于“自动”。用户明确选择 HDR10+ 时，该选择是严格的
格式请求：

- 仍应穷尽当前编码器族、帧率上限等用户约束下全部合法的 HDR10+ 编码候选，并按 D104 依次
  尝试 GPU 高速统计与 GLES 3.0 精确兼容统计。
- 全部 HDR10+ 候选失败后立即结束导出；不得继续尝试杜比视界、HDR10、HLG，也不得退化成
  原生 SDR。
- 不发布任何半成品或替代产物，清理本次临时文件。
- 失败信息明确写出用户请求的 HDR10+、失败阶段、两条统计路径及编码候选的具体原因，并提示
  用户返回 FableSol 设置自行选择“自动”或其它格式；不得只显示笼统的“导出失败”。

因此“自动”表示尽量取得按规格排序的最佳可用 HDR，“HDR10+”则表示只接受真正通过全部动态
元数据与发布门禁的 HDR10+ 文件。

## D106（2026-07-28）所有显式 HDR 格式都是严格请求，失败提醒只属于真实导出任务

D105 的严格格式语义推广到杜比视界 8.4、HDR10 与 HLG。用户显式
选择任一 HDR 格式时，只穷尽该格式内部、且满足当前编码器族与帧率等约束的候选；全部失败后
结束任务，不切换其它 HDR 格式，也不发布 SDR。只有“自动”才跨格式按 D48/D104 逐级尝试，
并在全部 HDR 候选失败后执行既有的原生 SDR 回退；“关闭”始终直接请求 SDR。

上述“失败”只在用户确实发起了一次视频导出后成立：

- 设置界面的 HDR/编码器能力探测只更新行内的可用性、实测结论和原因，不弹失败 Dialog、不发
  失败通知，也不把探测完成事件描述成一次导出失败。
- 能力探测结论不得自行启动导出、改写显式格式偏好或发布任何替代产物。
- 真正的导出任务仍以运行期候选尝试和产物门禁为权威；只有该任务已经实际执行，并穷尽用户所选
  格式的合法候选后，才清理临时文件并显示 D105 定义的具体失败原因。
- 前台与后台只能在该真实任务终止后分别通过适当的失败界面或通知告知用户；不得提前根据设置页
  的缓存结论模拟失败。

## D107（2026-07-28）真实导出失败后提供直达设置操作，但不自动改档或重试

显式 HDR 格式的真实导出任务按 D106 失败后：

- 前台失败 Dialog 提供“调整导出设置”操作。
- 任务在后台终止时，失败通知提供同名操作。
- 点击后打开 FableSol 设置并定位到“导出 HDR 视频格式”区域，保留刚才失败的显式格式及其它
  用户参数。
- 应用不得自动切换“自动”、其它 HDR 格式或 SDR，也不得因为打开设置便自动重新发起导出。
  用户自行修改选择并再次执行导出。

失败界面仍须直接展示请求格式、失败阶段和可核对的具体原因；“调整导出设置”是导航捷径，
不能取代错误说明。

## D108（2026-07-28）HDR10+ 默认实际计算 ApplicationVersion 1 FractionBrightPixels

不再始终把 `FractionBrightPixels` 写成表示“未计算”的 0。每个 HDR10+ 元数据时间区间按
ST 2094-40 ApplicationVersion 1 的方法计算：

- 从最终线性 BT.2020 完整画面生成横纵各缩小 5 倍的代理帧，缩放属于规范要求的平滑过程，
  不得用简单抽取每第五个像素代替。
- 代理像素亮度使用 D65 BT.2020 公式
  `Y = 0.2627R + 0.6780G + 0.0593B`。
- 当前元数据时间区间为单帧，因此该帧就是区间内最亮帧；若后续改变时间区间定义，必须按规范
  重新选择平均亮度最高、并列时帧序号最高的代理帧。
- 以最亮代理像素亮度 `Ymax` 为上界，对每个代理像素按亮度差 `ε = Ymax − Y` 施加规范权重
  `f(ε)`：`ε < 1/255` 时权重恒为 1，`1/255 ≤ ε < 5/255` 时按 `(5/255 − ε) / (4/255)`
  线性衰减到 0，`ε ≥ 5/255` 时为 0；权重求和后除以代理帧真实像素数。
  （2026-07-28 按原文式 7/11 校正：原表述"在其下方 1/255～5/255 的范围内统计"漏写了
  `ε < 1/255` 的全权重区，照字面实现会把最亮 1/255 带内的像素算错。）
- 写入时量化到 0.001；已完成计算且结果大于 0 时至少写 0.001，不能因量化向下变成表示
  “未计算”的 0。

该值只描述高亮面积，不参与 D98/D100 的曲线启停，也不得用全分辨率 99.98% CFD 近似代替。

**2026-07-28 原文验证**：已对照 SMPTE ST 2094-40:2020 §10.4 核实：5:1 代理缩放
（"a 'proxy frame' that is a 5:1 image reduction resizing operation"）、BT.2020/D65
亮度式（式 6）、最亮帧取平均亮度最高且并列取最高帧号（式 9）、亮度带
`[Ymax − 5/255, Ymax]`、权重函数式 7（μ1 = 1/255、μ2 = 5/255）与按总像素数归一化的
式 11 全部一致；上文权重区表述已按原文校正。来源：
<https://pub.smpte.org/pub/st2094-40/st2094-40-2020.pdf>

## D109（2026-07-28）FractionBrightPixels 单独失败时使用规范零值并继续 HDR10+

若某帧只有 ApplicationVersion 1 `FractionBrightPixels` 的代理缩放或计算失败，而同帧线性源
画面、MaxSCL、AverageMaxRGB、DistributionMaxRGB、曲线及其它发布门禁均独立有效，则该帧
写 `FractionBrightPixels = 0` 并继续 HDR10+。该零值严格解释为“未计算”，不解释成画面中没有
亮像素。

不得用本回退掩盖共享统计源或核心逐像素通路故障；线性画面、CFD 或其它核心统计异常时仍按
D104～D106 处理候选失败。能力详情记录 FBP 已知图计算是否通过；真实导出采用零值时，完成
信息说明“高亮像素比例未计算，已使用规范零值；其余 HDR10+ 动态元数据有效”，但不把成功任务
标成失败。

## D110（2026-07-28）高光起点直接查询内部逐像素 CFD 的真实百分位

HDR10+“高光起点”不再调用码流九项 DistributionMaxRGB V 向量进行插值。内部高精度逐像素
直方图保留独立 CFD 查询能力，用户选择 50%～99% 中任意百分位时，直接返回该百分位的真实
线性 BT.2020 maxRGB 值：

- “高光起点 90%”表示最低亮度的 90% 像素位于起点以下，较亮的约 10% 像素进入高光映射
  范围。
- D100 的曲线启停继续独立查询真实 99.98% 分位。
- D101 的码流 V 向量只用于标准载荷：`V1/V2` 保留特殊固定值，`V8` 表示 99.98%；不得再
  反向充当任意百分位查询表。
- 32×32 块平均不再决定膝点。孤立亮点是否有资格启用全局曲线已经由 D100 的空间门槛处理。

由此删除现有 `nitsAtPercent()` 对标准九项 V 值的线性插值职责；时间平滑仍作用于查询所得的
绝对膝点亮度。

## D111（2026-07-28）HDR10+ 全片固定为 Profile B，低峰值帧使用 Case 3 中性曲线

一次 HDR10+ 导出的所有帧统一保持可被常用生态工具识别的 Profile B 结构，不再根据逐帧亮度
切换 Profile 或省略曲线：

- `targeted_system_display_maximum_luminance` 始终写入用户选择的非零参考显示峰值。
- `tone_mapping_flag` 始终为 1，每帧始终携带 KneePoint 和 9 个
  BezierCurveAnchors；中性 Case 3 中虽然 anchors 不参与函数计算，仍写入合法、单调的占位值，
  以保持全片载荷结构和 Profile 判定稳定。
- 设 D95 定义的源归一化峰值为 `S`，参考显示峰值为 `T`。当 `S <= T` 时写
  `Kx = 1`、`Ky = S/T`。按 ST 2094-40 Case 3，`F(s) = Ky × s`，恢复到绝对亮度后有
  `T × F(s) = S × s`，因此不会把低峰值帧拉亮。
- 当 `S > T` 时仍写入 Profile B 压缩曲线；具体曲线生成、极端峰值可表示范围及时间稳定策略
  继续单独决策。

本决策取代 D96～D99 的“无曲线帧”和 `tone_mapping_flag` 启停迟滞。D100 的全分辨率真实
99.98% 分位统计仍保留，但不再负责开关 `tone_mapping_flag`；它在压缩曲线生成中的具体职责
重新开放，后续单独决定。

## D112（2026-07-28）所有 HDR10+ 百分位统一采用 ST 2094-40 nearest-rank 定义

HDR10+ 码流 DistributionMaxRGB、D100 的 99.98% 查询以及 D110 的内部任意百分位查询，共用
同一套累计频率分布语义：

- 使用元数据时间区间内全部选中像素的线性 BT.2020 `max(R,G,B)`，保留重复值并按升序理解。
- 设像素总数为 `n`、查询百分比为 `p`，使用从 1 开始的序号
  `r = max(1, ceil(n × p / 100))`，返回升序序列中的第 `r` 个值。
- 不在相邻像素、直方图桶或码流九项 V 向量之间插值。
- ApplicationVersion 1 的 `J8 = 99` 查询必须把 `p` 解释为 99.98，而不是字面 99。
- 先按上述定义选择源像素值，只在写入 DistributionMaxRGB 载荷时量化到规范要求的
  `0.00001` 步长；统计后端的内部精度按 D169 固定为同一载荷量化网格（0.1 尼特桶），
  内部曲线查询使用该精度。

CPU 与 GPU 统计后端、发布门禁、诊断回读和测试向量必须使用相同的百分位定义；不得因实现
方式不同而分别采用线性插值、`floor((n-1)p)` 或其它 percentile 约定。

## D113（2026-07-28）HDR10+ 曲线横轴优先按 V8 归一化，MaxSCL 仅作回退

修订 D95 的源归一化峰值优先级。设 HDR10+ 曲线横轴 `s = 1` 对应的源峰值为 `S`：

1. 优先使用按 D112 从完整逐像素 CFD 查询得到的有效
   `DistributionMaxRGBPercentiles[8]`，即 ApplicationVersion 1 的真实 99.98% 分位 V8。
2. V8 无效或为零时，回退到非零 `max(MaxSCL.R, MaxSCL.G, MaxSCL.B)`。
3. 两者均为零时按全黑或统计异常分别处理；具体零值分支在曲线边界条件决策中定义，不得用
   虚拟母版峰值静默替代。

选择 V8 优先是 FableSol 的互操作与画面稳定策略，而不是声称 ST 2094-40 只允许这一种
归一化。标准正文允许最后一个 Distribution 分位值或非零 `max(MaxSCL)`；Annex B 的信息性
参考方法采用 MaxSCL 优先，但 ATSC A/341 接收端参考算法及公开的 Samsung SDK 衍生实现优先
采用 V8。FableSol 选择与公开接收端行为一致，避免最亮约 0.02% 的孤立像素拉伸整帧曲线。

超过 V8 的像素仍完整参与 MaxSCL、AverageMaxRGB、DistributionMaxRGB 和静态亮度元数据计算，
MaxSCL 必须继续按 D103 写入真实值，不得裁成 V8。参考映射可能把高于 V8 的最亮约 0.02%
像素共同限制到输出峰值，由此可能损失这些极少数像素之间的亮度层次；这是本策略为互操作和
曲线稳定性接受的明确取舍。

本决策取代 D95 中“非零 MaxSCL 优先、最后一个 Distribution 分位值回退”的顺序；D95 的绝对
膝点换算、虚拟母版峰值不参与 HDR10+ 曲线横轴等其余结论继续有效。D111 中的 `S` 此后按本
决策解释。

## D114（2026-07-28）HDR10+ 曲线必须在载荷量化后通过单调与膝点斜率连续门禁

FableSol 生成的 HDR10+ Profile B 基础 OOTF 不仅满足可编码的字段范围，还必须满足以下画质
约束：

- 整条映射函数在定义域内单调不下降。
- 线性段与 Bezier 段在膝点处亮度连续。
- 两段在膝点处的一阶斜率连续；考虑 12-bit KneePoint 和 10-bit anchors 的离散步长后，允许
  使用明确、可测试的量化误差范围，但不得出现肉眼可感知的尖锐转折。
- 上述检查必须针对最终准备写入载荷的量化值重新执行，不能只验证量化前的浮点参数。

现有曲线代码把斜率连续公式求得的第一个 anchor 直接
`coerceIn(0.0, 1.0)`。当理论值大于 1 时，这种截断虽然可能保留字段范围和单调性，却会破坏
膝点斜率连续，因而不得作为成功路径。其余 anchors 的生成也必须接受同一套量化后曲线验证，
不能仅凭控制点数值递增就认定最终函数满足全部门禁。

若当前源归一化峰值、参考显示峰值和膝点组合不存在通过门禁的九 anchor 解，必须进入显式的
边界处理；不得发布截断后的曲线。边界处理采用调整目标、调整膝点还是终止当前 HDR10+ 候选，
留待下一项决策。

## D115（2026-07-28）HDR10+ 曲线无解时先降低膝点，动态范围超过十倍时拒绝候选

设 D113 的源归一化峰值为 `S`、用户选择的参考显示峰值为 `T`、绝对膝点亮度为 `k`。在
ApplicationVersion 1 使用 9 个 anchors、膝点以下保持绝对亮度且满足 D114 斜率连续时，第一个
anchor 为：

`P1 = (S - k) / (10 × (T - k))`

因此合法解必须满足 `k <= (10T - S) / 9`，并由此采用以下边界处理：

- 当 `T < S <= 10T` 且用户百分位查询所得膝点高于可行上限时，保持参考显示峰值 `T` 不变，
  只把实际膝点向下移动到最接近用户意图、且量化后能够通过 D114 门禁的最高可行值。完成信息和
  诊断记录用户请求值与实际采用值，不把调整后的值伪装成原始百分位结果。
- 当 `S > 10T` 时，不存在从非负膝点开始、同时保持膝点以下绝对亮度和一阶连续的十阶曲线。
  当前 HDR10+ 候选必须失败；不得静默抬高用户选择的 `T`、压暗膝点以下主体、改变 PQ 基础
  像素或发布截断 `P1` 的曲线。
- 连续域存在解但载荷量化后仍找不到满足 D114 误差门禁的参数时，同样判当前 HDR10+ 候选失败。

候选失败沿用 D104～D107：`HDR（自动）`继续尝试杜比视界 8.4、HDR10、HLG；显式 HDR10+
终止导出并给出本帧 `S/T`、最低可行参考峰值以及可操作建议，包括提高参考显示峰值、降低 HDR
强度或降低漫反射白。不得仅显示“曲线生成失败”。

## D116（2026-07-28）保留 300 尼特参考峰值下限，并对低目标与理论无解风险分层提示

HDR10+“参考显示峰值”继续采用 D94 的 300～10000 尼特范围。300 尼特是 ST 2094 合法且可用于
低峰值 HDR 显示设备的目标，不因 FableSol 高光较强而提高滑杆下限；默认值仍为 1000 尼特，
快捷参考值仍从 400 尼特开始，因此 300 尼特只通过拖动滑杆或采用被范围约束后的本机声明值
得到。

信息栏增加两层互不混淆的提示：

- 参考峰值位于 300～400 尼特时，说明这是低峰值 HDR 目标，普通亮部可能更早进入压缩，画面
  与 600/1000 尼特目标相比会有更明显的亮度取舍。
- 当理论渲染峰值 `漫反射白 × HDR强度 > 10 × 参考显示峰值` 时，追加说明“当前组合在部分
  高亮帧可能无法生成满足平滑门禁的 HDR10+ 曲线；最终以逐帧 V8 实测为准”。该提示是保守
  风险预告，不得提前禁用 HDR10+、自动抬高参考峰值或把设置页状态标为导出失败。

D115 的真实失败条件仍只使用逐帧实测 `S = V8`。理论峰值超过十倍只代表存在风险；若最亮高光
不足以把 99.98% 分位推过 `10T`，导出仍正常继续。采用 D83 的默认漫反射白 203 尼特和默认
HDR 强度 9.6 时，理论峰值约 1949 尼特，因此即使参考峰值为 300 尼特也不会触发 `S > 10T`
的完全无解分支，但仍可能因可行膝点降低而产生较强压缩。

## D117（2026-07-28）HDR10+ 肩部 anchors 由完整 CFD 驱动，不再使用固定二次缓动

现有 `FableSolExportHdr10PlusCurve` 只用源峰值、目标峰值和膝点求出 `P1`，随后以固定平方
函数生成 `P2～P9`。该形状没有标准依据，也没有区分膝点以上像素集中在中高亮还是极高亮的
不同画面，因此不得继续作为正式 HDR10+ 曲线生成策略。

新的 Profile B 基础 OOTF 按以下职责分离：

- `P1` 继续由 D114 的膝点一阶斜率连续条件确定。
- `P2～P9` 根据 D112 的内部完整逐像素 linear BT.2020 maxRGB CFD 求解，不只使用写入码流的
  九项 DistributionMaxRGB V 向量。
- 高光像素密度较大的亮度区间应获得更多输出斜率和层次；像素稀疏的区间承担更多动态范围
  压缩，以符合 ATSC A/341 对 content-dependent basis OOTF 的公开指导。
- 求解过程同时约束控制点和最终曲线单调、曲率平滑，并在 12-bit KneePoint、10-bit anchors
  量化后通过 D114 门禁。

HDR10+ 标准与公开 ATSC 参考资料没有规定唯一的发送端基础 OOTF 生成公式，正式工具的自动分析
算法也未公开。因此本决策把曲线定位为 FableSol 的自动创作算法，不声称复制某个未公开的
“标准曲线”。具体拟合目标、密度权重、时间稳定和质量验证继续逐项决定。

## D118（2026-07-28）HDR10+ 标准统计保持线性域，内部曲线拟合目标使用 PQ 感知域

HDR10+ 统计、载荷语义与内部画质优化使用不同但明确衔接的坐标：

- MaxSCL、AverageMaxRGB、DistributionMaxRGB、FractionBrightPixels 及 D112 的内部 CFD
  继续严格按 ST 2094-40 在线性 BT.2020 中计算。
- KneePoint 和 BezierCurveAnchors 最终描述的函数仍以线性归一化 maxRGB 为输入与输出，不得
  改成 PQ 码值曲线。
- 仅在 D117 的自动曲线求解器内部，把源绝对亮度和候选映射后的绝对亮度转换到 ST 2084 PQ
  坐标，在该坐标中计算拟合误差、局部对比度分配与平滑代价；求得的目标采样点再转换回线性
  亮度，拟合为 ST 2094-40 Bezier 参数。

选择 PQ 域作为内部质量坐标，是因为线性尼特差并不近似人眼感知差异；在线性域直接最小二乘会
让数千尼特区间的数值误差支配目标函数，可能牺牲漫反射白附近与中高亮主体的可见层次。PQ
坐标用于评价画质而非改变载荷语法，CPU/GPU 统计值也不得为了拟合方便而提前转成 PQ 域。

## D119（2026-07-28）HDR10+ 基础 OOTF 只压缩，不提高绝对亮度或放大局部对比度

当 D113 的源峰值 `S` 高于参考显示峰值 `T` 时，FableSol 的 Profile B 基础 OOTF 在绝对亮度
坐标中满足：

- 膝点以下严格保持 `Lout = Lin`。
- 膝点以上始终满足 `0 <= Lout <= Lin`，不得把任何输入亮度映射得比原始绝对亮度更高。
- 全曲线局部斜率满足 `0 <= dLout/dLin <= 1`。像素密集区间最多保持原有局部对比度，稀疏
  区间承担更多压缩；不得为了利用输出范围而主动放大某段局部对比度。
- 源归一化峰值映射到参考显示峰值，仍需使用完整可用输出范围。

上述限制是 FableSol 的创作保真策略，不是 ST 2094-40 的强制语法约束。它与 D111 的低峰值帧
不提亮语义一致，并加入 D114 的浮点与载荷量化后门禁。若量化使任一采样区间越过 identity
映射或局部斜率上限，求解器必须重新约束求解或判候选失败，不得仅对个别输出点事后裁切而制造
新的折点。

## D120（2026-07-28）HDR10+ 在 PQ 域按内容密度与均匀先验共同分配肩部范围

在 D117～D119 的约束下，膝点至源 V8 之间的目标肩部使用带均匀先验的内容密度分配：

1. 将膝点至 V8 的绝对亮度范围转换到 PQ 感知坐标，并划分足够精细的内部区间。
2. 从 D112 的完整逐像素 CFD 取得各区间的实际像素质量，形成归一化内容密度。
3. 使用
   `w(q) = (1 - α) × contentDensity(q) + α × uniformDensity(q)`
   构造正则化密度，其中 `α` 的取值策略另行决定。
4. 按 `w(q)` 的累计质量分配膝点至参考显示峰值的输出 PQ 范围：内容密集区获得更多可见范围，
   稀疏区承担更多压缩。
5. 将目标映射交给受约束求解器拟合 9 个 anchors。若某处触发 D114/D119 的单调、连续、
   identity 或局部斜率上限，必须在可行域内重新分配剩余范围，不能对目标点或 anchors 作逐点
   裁切。

均匀先验用于防止空直方图区间变成长平台、稀疏银丝和星芒全部落到同一亮度，并降低少量像素
跨桶引起的逐帧曲线跳变。纯原始 CDF 均衡不得作为发布算法；固定二次肩部也不得作为统计失败
时的静默替代。精确离散方式、`α`、时间稳定和统计失败行为继续分别决定。

## D121（2026-07-28）HDR10+ CFD 均匀先验固定为 0.5，不逐帧自适应或开放用户参数

D120 的混合系数固定为 `α = 0.5`：

`w(q) = 0.5 × contentDensity(q) + 0.5 × uniformDensity(q)`

该值在所有帧、分辨率、帧率、参考显示峰值和高光起点下保持一致。不得根据逐帧直方图熵、
峰数量、亮像素比例或压缩比再次动态改变 `α`，避免 CFD 本身变化之外再叠加“算法信任程度”
变化并放大亮度呼吸。

`α` 不作为设置项暴露。用户继续只通过参考显示峰值与高光起点表达创作意图；均匀先验属于内部
稳定性正则。0.5 是首版偏保守取值，使稀疏银丝和星芒至少保留显著的均匀范围，同时仍让一半
分配受真实内容密度驱动。D114/D119 的约束求解仍决定最终可行曲线，不能把 0.5 解释成最终
曲线两种形状的简单线性各半。

后续若固定参考动画、软件解码参考映射和 HDR10+ 实机验证一致证明其它常量更好，才允许修改；
修改时必须升级曲线算法及相关统计缓存契约版本并重新执行完整质量验证，不能在运行时自行漂移。

## D122（2026-07-28）HDR10+ 时间稳定作用于 PQ 映射采样，再按当前帧约束重新拟合

新曲线不得只平滑 KneePoint，也不得逐项直接平滑编码后的 anchors。每帧按以下顺序处理：

1. 使用当前帧真实 CFD、`S/T/k` 和 D114～D121 生成未经时间平滑的瞬时目标曲线。
2. 在固定的绝对 PQ 亮度采样网格上计算该曲线的输出 PQ 值。
3. 对每个映射采样值执行快起慢落的因果指数平滑：目标输出下降、表示需要更强压缩时使用
   `τ = 0.08s`；目标输出上升、表示放松压缩时使用 `τ = 0.80s`。
4. 使用当前帧的源 V8、参考显示峰值和实际可行膝点，把平滑后的 PQ 目标重新投影到当前帧
   可行域，并求解 KneePoint 与 9 个 anchors。
5. 当前帧端点及 D114/D115/D119 门禁必须立即满足。时间状态不得使源峰值越过目标、造成削顶，
   也不得成为发布旧约束曲线的理由。

由此平滑的是接收端实际看到的亮度映射，而不是跨帧语义可能变化的载荷参数。MaxSCL、
AverageMaxRGB、DistributionMaxRGB 与 FractionBrightPixels 仍逐帧写入未经平滑的真实统计。
0.08/0.80 秒是 FableSol 的首版经验参数而非 HDR10+ 标准值；后续须通过固定闪现、渐亮、渐暗
和重复脉冲序列验证，修改时遵循 D121 的算法版本管理。

## D123（2026-07-28）HDR10+ 首版采用轻量画质回归，罕见边界不要求完整片源或逐项实机验证

HDR10+ 新统计与曲线算法的发布验证分为硬门禁、常规画质检查和增强验证，不把难以制作的
罕见边界片源设为首版发布前提：

- 硬门禁包括位流语法正确、量化后的曲线通过 D114/D115/D119 约束、导出后元数据存在性检查，
  以及候选失败时严格执行已经确定的自动回退或显式失败行为。
- 常规主观画质检查使用现有 HDR10+ 样片和少量具有代表性的 FableSol 动画，对新版与旧版输出
  进行 A/B 比较；要求默认参数和普通场景没有肉眼明显退化。旧版结果是软性的视觉回归基线，
  不是要求逐像素或逐亮度一致的金标准。
- 罕见边界通过直接构造 `S/T`、CFD、稀疏高光和突变帧序列等统计输入，或使用少量程序化合成帧，
  验证算法不会生成非法曲线、崩溃或错误导出，并能给出正确的回退或失败结果；不要求为每种边界
  制作完整视频，也不要求逐项在真实 HDR10+ 设备上证明主观画质改善。
- 真实 HDR10+ 设备只需对常规代表样片进行抽查。软件参考映射、多设备交叉验证和罕见边界实机
  对比属于有条件时执行的增强验证，不阻塞首版发布。

现有固定二次肩部曲线仅保留为离线 A/B 对照，不得因新版候选在边界失败而作为运行时静默回退；
运行时仍遵循 D104～D107 及 D115 已确定的格式回退与显式失败规则。

## D124（2026-07-28）GLES 3.0 精确统计先采用直接 RGBA8 回读，紧凑打包由实际性能决定

D104 的 GLES 3.0 精确兼容路径首版继续采用全分辨率 RGBA8 统计纹理与回读，不预先改成把多个
源像素压入单个 RGB10_A2 统计像素的紧凑方案。

性能评估必须使用 FableSol 的实际导出画布，而不是假设 4K 视频：当前设计的卡片宽度为
280～383dp、卡片高度固定为 1296px，基础画布约为 `1012～1330 × 1444`；经过通常的 64px
分享兼容对齐后约为 `1024～1344 × 1472`，即约 151 万～198 万像素。编码器自身对齐要求可能
继续扩大画布，因此实现和诊断使用当前 `FableSolExportPlan` 的实际尺寸计算统计资源、单帧
回读量和耗时，不把上述范围硬编码为运行时上限。

在通常对齐尺寸下，全分辨率 RGBA8 回读约为 5.75～7.55MiB/帧；按 120fps 的名义处理速率约为
0.67～0.88GiB/s。该负担需要实测，但不足以在实现前认定兼容路径不可行。首版优先选择结构
简单、易于用已知像素图验证且能够保留输入 10-bit PQ RGB 码值的实现，并记录统计阶段耗时与
总导出速度。

只有真机实测证明统计回读是显著瓶颈时，才实施诸如三像素 RGB10_A2 紧凑打包、异步 PBO 或
其它不会降低 D101～D103、D108、D110、D112 精度的优化。优化前后必须得到相同的量化统计结果；
不得为了达到实时处理速度而恢复 32×32 块近似值，也不得使用未经实际画布测量的理论带宽数字
作为取消 GLES 3.0 兼容后端的理由。

## D125（2026-07-28）HDR10+ 不自动检测场景切换，只在明确的时间边界重置平滑状态

当前 FableSol 导出是一段连续生成的动画，没有剪辑时间线或多个镜头的结构。D122 的 HDR10+
曲线平滑不增加基于 CFD、直方图距离、AverageMaxRGB 或峰值突变的自动场景切换检测，避免把
星芒突现、水体骤亮、音频脉冲等正常动画内容误判为切镜，并因强制重置曲线造成可见亮度跳变。

平滑状态只在具有明确语义的非连续边界重置：

- 每个新的格式与编码候选从第 1 帧开始时；
- 导出失败后从头重试、显式重新开始，或检测到帧时间戳不连续时；
- 将来若支持多片段导出，由上游明确传入的片段边界触发。

连续时间轴内的突然变亮或变暗仍按 D122 的 `0.08s/0.80s` 快压慢放处理，不因为统计量变化幅度
较大而绕过时间稳定。候选之间不得复用上一候选的平滑状态、CFD 历史或首帧结果。

## D126（2026-07-28）HLG 固定以 75% 信号表示参考白，不暴露绝对亮度参数

FableSol 扩展显示线性值 `1.0` 在 HLG 输出中固定映射到 75% HLG 信号。令
`E_ref = 0.26497`、参考 HLG system gamma `γ = 1.2`，先把线性 BT.2020 显示光按
`D_ref = E_ref^γ ≈ 0.203159` 归一到标称峰值 1000cd/m² 的参考显示器，再执行 BT.2100
逆 OOTF；中性参考白由此回到场景线性值 `E_ref`，经标准 HLG OETF 得到 75% 信号，并在参考
显示器上还原为约 203cd/m²。`E_ref`、`D_ref` 与 `γ` 均为整段固定的规范转换常量，不因导出
设备、本机显示峰值或逐帧内容改变。

HLG 是相对亮度系统，不复用 PQ 的“漫反射白”和 HDR10+“参考显示峰值”参数，也不新增 HLG
白点或目标峰值滑杆。不得为了把 FableSol 理论最高 9.6 倍高光完整线性装入 HLG 而整体降低
参考白、压暗主体；超出 HLG 规范信号色容积的部分由 D128～D131 的稳定高光肩部处理。

`1 / E_ref ≈ 3.77` 只是 HLG 场景线性域从参考白到 OETF 输入上限的比值，不能作为 FableSol
显示线性工作空间的高光阈值。中性色轴在上述参考显示器上的显示线性容量约为
`(1 / E_ref)^γ ≈ 4.92` 倍参考白；有色信号还受 HLG 信号色容积约束，并可按 D134 在标准
窄范围 super-white 内按需使用最高 109% 信号，不能仅用中性色数值判定是否放得下。界面与完成
信息不展示这些内部阈值，只说明 HLG 会按显示设备相对适配，并会平滑收纳超出编码余量的高光，
不承诺固定的绝对显示峰值。

## D127（2026-07-28）HLG 不提供逐帧自适应曲线，整段导出使用稳定的输出映射

普通 HLG 没有 HDR10+ ST 2094-40 一类逐帧动态内容元数据。虽然应用可以在编码前根据每帧峰值
改变 HLG 高光曲线并把结果直接烘焙进基础像素，但这种行为属于 FableSol 的输出侧逐帧改画面，
不是 HLG 动态元数据：接收端不知道原始映射，不能撤销，也不能根据自身显示峰值重新求解。

FableSol 的 HLG 导出不实现上述逐帧自适应，也不增加“稳定/动态”选项。一次导出内，同一个
FP16 扩展显示线性 RGB 输入必须得到同一个 HLG 输出；映射只由 D126 的固定参考白、用户在导出
开始前已经确定的 HDR 强度，以及 D128～D131 的固定逆 OOTF 与肩部参数共同决定，不读取逐帧
MaxRGB、直方图或场景峰值来改变曲线。

该取舍允许低峰值帧不一定单独占满全部 HLG 高光范围，以换取跨帧亮度稳定、保留星芒强弱差异，
并避免烘焙曲线与播放端 HLG system gamma 或厂商自有适配叠加出额外亮度呼吸。HLG 接收显示器
根据自身能力执行相对显示适配，不得把该显示端行为描述成片源携带的动态元数据。

稳定肩部仅属于 `export_present.frag` 的 HLG 输出转换，不修改 FableSol 物理模拟、材质、FP16
扩展显示线性工作空间或 PQ/SDR 导出路径。

## D128（2026-07-28）HLG 先执行标准逆 OOTF，再按场景线性 BT.2020 maxRGB 施加共同增益

当前代码把 D79 的显示线性工作空间直接乘 `0.26497` 后应用 HLG OETF，等于把已经包含显示意图
的图形渲染重新解释为摄像机场景光。参考 HLG 显示器随后再施加 system gamma，会使低于参考白
的中间调额外变暗、高于参考白的内容额外变亮。HLG 输出改为以下固定流水线：

1. 把扩展显示线性 Rec.709 转成非负显示线性 BT.2020，并按 D126 的 `D_ref` 归一为参考 HLG
   显示光 `D`。
2. 按 BT.2100 在亮度分量上执行逆 OOTF。令
   `Y_D = dot(D, vec3(0.2627, 0.6780, 0.0593))`；当 `Y_D > 0` 时，
   `E_S = D * Y_D^((1 - γ) / γ)`，黑色保持零。
3. 在场景线性 BT.2020 的 `E_S` 中计算 `m = max(R_S, G_S, B_S)`，由 D129～D131 的稳定
   肩部得到 `m'`，再对三个通道共同乘以 `gain = m' / m`；`m = 0` 时保持黑色。
4. 对缩放后的三个场景线性通道分别应用标准 HLG OETF。

逆 OOTF 与肩部都只对同一像素施加三通道共同增益，因此场景线性 BT.2020 RGB 比例不会被
FableSol 的 HLG 转换额外改变，并确保最大场景分量进入 D134 允许的窄范围 HLG 信号色容积。
原本
R≈G≈B 的白色星芒仍保持白色；彩色水体高光保留原有色度方向，不再以“很亮所以应当主动漂白”
为理由改变颜色。

上述保证只覆盖应用自身肩部前后的线性 RGB 比例，不宣称最终显示的感知饱和度绝对不变；
HLG OETF、接收端 OOTF、显示器色容积与厂商处理仍可能影响观感。

## D129（2026-07-28）HLG 按每个像素的颜色方向计算可用信号色容积

不再用 Rec.709 蓝色轴的最不利容量为所有像素生成一条全局肩部。对每个非黑像素，设逆 OOTF
之前的扩展显示线性 Rec.709 为 `P_D`，定义其显示线性尺度与颜色方向：

```text
s = max(P_D.r, P_D.g, P_D.b)
u = P_D / s
```

其中 `max(u) = 1`。把单位颜色方向 `u` 按 D128 执行 Rec.709→BT.2020、乘 `D_ref` 并执行
逆 OOTF，定义：

```text
q(u) = max(inverseOotf(D_ref * rec709ToBt2020(u)))
```

BT.2100 逆 OOTF 对正比例缩放具有 `1 / γ` 次齐次性，因此实际像素在场景线性 HLG 域中的
maxRGB，以及同一颜色方向在用户所选 HDR 高光上限 `H_D = uHdrHeadroom` 处的端点分别为：

```text
m      = q(u) * s^(1 / γ)
H_S(u) = q(u) * H_D^(1 / γ)
```

- D134 根据颜色方向和可验证的窄范围 HLG 信号上限定义 `C_S(u)`；中性色方向为 `1.0`，
  需要扩展色容积的方向可高于 `1.0`，但对应的 HLG OETF 输出不得超过 109%。
- 当 `H_S(u) <= C_S(u)` 时，该颜色方向在完整 `0～H_D` 范围内均能直接装入可用 HLG
  信号色容积，对该像素保持恒等映射。
- 只有 `H_S(u) > C_S(u)` 时，才对该颜色方向启用 D130～D131 的肩部，并把端点映射到
  `C_S(u)`。

由此，中性色方向可使用约 `4.92×` 参考白的显示线性余量；经验证能够保留 super-white 时，
当前 Rec.709 蓝色方向可使用约 `5.50×` 的源显示线性尺度后才需要压缩，而不是在名义 100%
信号限制下约 `3.32×` 就开始压缩。任何颜色的边界都不得让其它仍放得下的颜色提前压缩。
`q(u)` 与 `C_S(u)` 只由当前像素颜色方向、固定色彩变换和已经选定的信号范围能力决定，不读取
逐帧统计、周围像素或目标显示能力；相同输入在整段导出中仍得到相同输出。

## D130（2026-07-28）HLG 各颜色方向共用显示线性参考白 2 倍的肩部起点

肩部起点继续定义在 FableSol 扩展显示线性源空间的
`K_D = 2.0 × HDR Reference White`，但转换到 HLG 场景线性 maxRGB 域后，按 D129 的颜色方向
得到各自的膝点：

```text
K_S(u) = q(u) * K_D^(1 / γ)
```

因此所有颜色在源显示线性尺度 `s <= 2.0` 时都严格保持原样，不会因为蓝色等高饱和方向更早
占用 HLG 通道余量，就在低于共同创作膝点时提前压缩。中性色方向的
`K_S ≈ 0.47212`，在 D126 的参考 HLG 显示器上对应约 `2 × 203 = 406cd/m²`；其它颜色的
`K_S(u)` 数值不同，但都表示同一个源显示线性 `2.0×` 起点。

`K_D = 2.0` 是 FableSol 的稳定创作取值，不是 BT.2100 或 BT.2408 强制规定的 HLG 参数。
HLG 不新增“高光起点”滑杆，也不复用 HDR10+ 按 CFD 百分位定义的同名参数；界面不得显示
内部的 `q(u)` 或 `K_S(u)`。

## D131（2026-07-28）HLG 为每个颜色方向拟合端点一致的指数肩部

当 D129 判定当前颜色方向需要肩部时，设 D130 的方向相关起点为 `K = K_S(u)`、该方向在整段
固定 HDR 上限处的端点为 `H = H_S(u)`、D134 的方向相关 HLG 场景线性上限为 `C = C_S(u)`。
D128 在逆 OOTF 后得到的 maxRGB 标量 `m` 使用以下映射：

```text
F(m) = m                                             m <= K
F(m) = K + A * (1 - exp(-(m - K) / A))              K < m <= H
```

参数表中每个需要肩部的采样点都在导出开始时求得正数 `A`，使：

```text
A * (1 - exp(-(H - K) / A)) = C - K
```

在 `H > C > K` 时该方程存在唯一正解。`A` 不再是整段唯一 uniform，但方向相关性可以完全
归一化（本段表述经 D164 修正）：把肩部除以 `q(u)` 后，起点 `K_n = K_D^(1/γ)` 与端点
`H_n = H_D^(1/γ)` 都是整段常数，唯一随方向变化的形状量是归一化容量
`C_n(u) = C_S(u) / q(u)`，归一化尺度 `A_n = A / q(u)` 只是 `C_n` 的一元函数。实现使用
导出开始时按当前固定 `H_D` 生成的一维参数表，以 `C_n(u)` 查表并在 shader 中做确定性
线性插值；`C_n(u)` 逐像素闭式求得。不得以 `q(u)` 作查表键（同一 `q` 可对应不同容量的
颜色方向，见 D164 的反例），不得在每个像素中迭代求根，也不得用最近点查表造成颜色渐变中
的阶梯。

该函数满足 `F(K) = K`、`F'(K) = 1`、`F(H) = C`，且在有效输入范围内单调、局部斜率位于
0～1。由此每个颜色方向的膝点都没有一阶折角、不放大局部对比度；用户设置的最高源高光沿该
颜色方向恰好使用其可用 HLG 通道上限，而仍有余量的颜色不被其它颜色连带压缩。当 `H` 只略
高于 `C` 时，函数自然趋近恒等映射；当 `H` 远高于 `C` 时，自然趋近渐近式肩部的形状。

不再把指数尺度固定写成 `C - K`。旧公式在 `H` 只略高于 `C` 时也会把最高高光显著压低，
不能满足 D129“该颜色确实放不下才按实际溢出量处理”的语义。肩部前后都只按 `m' / m` 对
RGB 施加共同增益，颜色方向保持不变；最终仅保留用于浮点舍入安全的 `C` 上限钳位，不以逐通道
截断替代端点求解。

## D132（2026-07-28）HLG 按显示参照源执行逆 OOTF，相关既有决定已直接修正

代码与标准复核确认，FableSol 共用 FP16 内容是扩展显示线性图形渲染，不是摄像机场景线性信号：
基础颜色来自 sRGB/Rec.709，解码到线性光后已经形成显示创作意图；PQ 路径也把同一线性值直接
按 HDR Reference White 换算为绝对显示亮度。若 HLG 路径继续直接乘 `0.26497` 后应用 OETF，
参考 HLG 显示器会再次施加约 1.2 的 OOTF gamma，使 `0.5` 倍参考白约从应有的 101.5cd/m²
变成 88.4cd/m²，并使 `2.0` 倍参考白约从应有的 406cd/m² 变成 466cd/m²。

因此 HLG 路径固定采用 BT.2100 的显示参照转换：显示线性 BT.2020 → 参考显示光归一化 →
逆 HLG OOTF → FableSol 稳定色容积肩部 → HLG OETF。1000cd/m²、`γ = 1.2` 是规范转换所用的
参考 HLG 显示条件，不是用户目标设备参数；其它 HLG 显示器仍按自身峰值与观看环境执行接收端
适配。

本决定已经直接改写 D79、D126～D131 中的源语义、处理顺序、容量判断、膝点亮度和曲线坐标域，
不保留“待修订”状态。D126 的 75% HLG 参考白、D127 的整段稳定映射，以及 D128 的线性 RGB
共同增益原则继续成立。

标准依据：

- ITU-R BT.2100-3 Table 5、Note 5i、Table 10 与 Annex 1：
  <https://www.itu.int/dms_pubrec/itu-r/rec/bt/R-REC-BT.2100-3-202502-I%21%21PDF-E.pdf>
- ITU-R BT.2390 的 OETF/EOTF/OOTF 系统关系及 HLG 参考 OOTF：
  <https://www.itu.int/pub/R-REP-BT.2390>

## D133（2026-07-28）HLG 逐像素按颜色方向利用余量，不采用最不利颜色的全局肩部

用户选择让每个像素按自身颜色方向使用 HLG 信号色容积，而不是以容量最小的 Rec.709 蓝色方向
提前压缩整幅画面的所有高光。D129～D131 已直接改写为方向相关的 `q(u)`、`K_S(u)`、
`H_S(u)` 与指数肩部；刚提出但尚未实现的“完整源色立方体共用一条最不利肩部”不再是发布设计，
也不得作为运行时回退。

该策略优先保留各颜色自身仍然可用的亮度范围：例如 HDR 强度为 `4.0×` 时，中性色方向仍位于
约 `4.92×` 的容量内，因此保持原有约 812cd/m² 的参考显示亮度；D134 的 super-white 高画质
路径中，当前 Rec.709 蓝色方向也能容纳到约 `5.50×`，所以同样不提前压缩。方向相关映射不使用
逐帧内容统计，参数随输入 RGB 连续变化，并始终以三通道共同增益保持线性 RGB 比例，因此不是
逐帧动态 HLG，也不得引入逐通道截断或离散颜色档位。

实现必须以连续插值和量化后误差验证保证相邻颜色方向不会产生可见亮度台阶。若方向参数表无法
创建、读取或通过数值门禁，该 HLG 候选应按既定格式候选失败规则处理；不得静默退回全局最不利
肩部并仍把产物标为同一算法版本。

## D134（2026-07-28）HLG 使用经验证的窄范围 super-white 扩展逐颜色方向的色容积

FableSol 的 HLG 高画质输出支持 BT.2100 窄范围信号在名义峰值以上的 super-white 区间：
10-bit 亮度名义峰值为码值 940，允许的视频数据范围延伸到 1019，对应非线性 HLG 信号最高
约 `W_MAX = 1.09`。该能力只用于补足高饱和颜色在 HLG OOTF 下较小的色容积，不用于把中性白
提升到参考显示器 1000cd/m² 以上，也不把视频标记为 full range。

对 D129 的单位 Rec.709 颜色方向 `u`，先定义显示线性 BT.2020 方向
`v = rec709ToBt2020(u)`。令该方向最强 BT.2020 显示分量恰好到达参考 HLG 显示器名义峰值的
源显示线性尺度为：

```text
s_peak(u) = 1 / (D_ref * max(v.r, v.g, v.b))
```

再把这一显示参照端点转换到 HLG 场景线性与非线性信号域：

```text
C_match(u)  = q(u) * s_peak(u)^(1 / γ)
W_match(u)  = hlgOetf(C_match(u))
W(u)        = min(W_match(u), W_MAX)
C_S(u)      = hlgInverseOetf(W(u))
```

由此，中性色方向的 `W(u) = 1.0`，不会使用 super-white；高饱和方向只使用匹配同一 1000cd/m²
参考显示色容积所需的扩展量。按当前 Rec.709→BT.2020 矩阵，纯 Rec.709 红、绿、蓝方向约分别
需要 `103.30%`、`100.77%`、`107.65%`，均低于 109% 标准上限。若未来工作色域或矩阵改变，
任何方向仍必须由 `W_MAX` 硬门禁约束，不得把 super-white 当作不受限的额外亮度。

super-white 只能在应用能够控制有限范围 10-bit 码值、并通过正式能力探测确认编码器保留名义
范围以外、视频数据范围以内样本的路径启用。普通 RGB Surface 输入即使成功编码，也不能据此
证明 `>1.0` HLG 信号未被 EGL、Surface 或编码器 RGB→YUV 阶段钳到名义峰值。首选实现为应用
自行生成 limited-range P010：亮度按 `64 + 876 × Y'` 量化，允许落入 941～1019；色度也允许
按 BT.2100 定义越过名义 64～960 范围。最终亮度和色度样本均限制在 10-bit 视频数据范围
4～1019，并保持 BT.2020、HLG、limited range 容器与码流标记一致。

能力验证必须使用包含中性 100%、有色 100%～109% 阶梯和平场块的实际编码样本，并在编码后
复核重建码值与色彩标记；只检查 `MediaCodec.configure()`、输出 profile 或文件存在不构成
super-white 支持证据。未通过验证的路径不得显示、记录或完成报告为“HLG 扩展信号范围”。

标准依据：

- ITU-R BT.2100-3 Table 9 与 Note 9a：
  <https://www.itu.int/dms_pubrec/itu-r/rec/bt/R-REC-BT.2100-3-202502-I%21%21PDF-E.pdf>
- 现行 ITU-R BT.2408-9 关于窄范围信号、super-white，以及使用扩展信号范围增加 HLG 色容积的说明；
  D134 的红、绿、蓝匹配值由 BT.2100-3 的 OOTF 与色彩矩阵按同一定义计算：
  <https://www.itu.int/dms_pub/itu-r/opb/rep/R-REP-BT.2408-9-2026-PDF-E.pdf>

## D135（2026-07-28）HLG super-white 不可验证时回退到名义范围 HLG，不回退 SDR

当设备能够稳定完成标准 HLG 编码，但 D134 的实际编码验证无法证明名义范围以外、视频数据范围以内的
10-bit 亮度或色度码值能够被保留时，本次导出仍继续使用 HLG，不把 super-white 作为 HLG 格式本身的
必备条件，也不因此回退为 SDR。

该兼容路径把所有颜色方向的非线性信号上限收回到名义 100%：

```text
W(u)   = 1.0
C_S(u) = hlgInverseOetf(1.0) = 1.0
```

D129～D131 的逐像素颜色方向、共同 RGB 增益和稳定指数肩部仍然保留；区别仅是各方向不能再使用
100%～109% 的扩展信号余量，因此部分高饱和高光会比 D134 的高画质路径更早进入肩部压缩。该产物仍是
BT.2020、HLG、limited range 的有效 HDR 视频，不得描述成 super-white 或“HLG 扩展信号范围”。

无论用户明确选择 HLG，还是“自动”最终选择 HLG，HLG 内部候选顺序均为：

1. 已通过实际编码验证的 super-white limited-range P010 路径；
2. 名义范围 HLG 路径。

super-white 验证未通过不构成一次导出失败，也不在设置页能力探测结束时弹出失败提醒。设置页下方信息栏
应说明设备预计使用“HLG 扩展信号范围”或“HLG 名义范围”；导出完成 Dialog、完成通知和设备诊断应记录
本次实际使用的信号范围。若导出期间高画质路径的实际验证或建立失败，但名义范围 HLG 仍可正常开始，则
可以切换到名义范围路径继续导出，并在完成信息中如实说明；只有 HLG 编码本身也失败时，才执行既有的
格式候选失败规则。任何情况下都不得仅因 super-white 不可用而把 HLG 产物静默改成 SDR。

## D136（2026-07-28）HLG 提供自动增强与名义范围两种信号范围选项

HLG 导出增加“信号范围”设置，提供以下两个互斥选项：

- **自动增强（默认）**：优先使用 D134 的 super-white 高画质路径；设备实际编码验证通过时使用
  最高 109% 的方向相关扩展信号范围，验证未通过或本次建立失败时按 D135 自动使用名义范围 HLG。
- **名义范围**：始终把非线性 HLG 信号限制在 100%，按 D135 的 `C_S(u) = 1.0` 平滑收纳高光，
  不为本次导出尝试或要求 super-white 路径，适用于更重视播放器、电视或分享平台兼容性的场景。

两者都输出 BT.2020、HLG、limited range HDR 视频，不得把“名义范围”描述成 SDR、低位深或另一种
HDR 格式。“自动增强”只在实际验证成立时才可对外报告为“HLG 扩展信号范围”，不得因为用户选择了该项
就预先承诺实际产物一定使用 100%～109% 信号。

设置页下方信息栏必须解释画面与兼容性差异：“自动增强”可减少高饱和彩色高光的提前压缩，但下游若不按
标准保留扩展信号，可能发生彩色高光裁切；“名义范围”会更早、但平滑地压缩这部分高光，以换取更可预测的
下游兼容性。导出完成 Dialog、完成通知和设备诊断继续以实际产物为准，显示“HLG 扩展信号范围”或
“HLG 名义范围”，而不是只显示用户申请的选项。

## D137（2026-07-28）HLG 信号范围只在显式 HLG 系格式下可编辑，自动格式不读取隐藏值

信号范围是显式 HLG 系格式的专属设置。用户把 HDR 格式明确选择为 HLG 时，设置页以“HLG 信号范围”
显示并允许编辑 D136 的“自动增强/名义范围”选项；按 D143～D144 显式选择杜比视界 8.4 时，同一语义
以“HLG 基层信号范围”显示。切换到“自动”、PQ 系格式或 SDR 时隐藏该项，但保留此前显式选择，以便
以后切回相应 HLG 系格式时恢复。

当 HDR 格式为“自动”且候选顺序最终落到 HLG 或杜比视界 8.4 时，固定采用“自动增强”语义：
验证通过则使用 super-white，验证未通过则使用相应格式的名义范围 HLG 基层。自动档不得读取或继承
当前不可见的“名义范围”历史值，避免隐藏设置使自动档静默放弃可用的 HLG 色容积。用户若明确要求
兼容性优先，应显式选择相应 HLG 系格式及“名义范围”，而不是依赖自动格式的隐藏状态。

“自动”格式的信息栏应在说明候选规则时补充：若最终使用 HLG 或杜比视界 8.4，将自动尝试扩展信号
范围并在不可用时使用名义范围。最终完成信息仍只报告实际产物，不额外显示已经隐藏且未参与本次求值的
历史选项。

## D138（2026-07-28）自动增强在能力未知时先完成一次验证，再开始正式渲染

当本次实际 HLG 策略为“自动增强”，但当前编码器、输入路径与运行环境尚无可复用的 D134
super-white 验证结论时，不得因为后台探测尚未完成就直接把第一次导出固定为名义范围。正式动画渲染
开始前先执行一次短时验证，并在导出准备阶段显示“正在验证 HLG 扩展信号范围”：

- 验证通过：本次使用 super-white 扩展信号范围；
- 验证未通过、发生受控异常或超时：本次继续使用名义范围 HLG，不弹出导出失败提醒；
- 用户明确选择“名义范围”：不等待、也不为本次导出触发该验证。

验证必须在创建完整动画渲染任务和发布目标文件之前完成，以免切换信号范围时重做已经渲染的帧，或在相册中
留下探测产物。该阶段属于 HLG 候选的准备过程，而不是一段独立的用户导出；只有后续 HLG 编码本身失败时，
才按 D106～D107 显示真实导出失败。

验证结论按至少以下完整签名缓存：设备系统构建、应用探测契约版本、实际编码器名称、编码器 Profile、
编码模式、分辨率与帧率能力档、输入方式、实际解码器名称与 CPU 可读 P010 输出方式，以及
P010/Surface 路径实现版本。签名变化或缓存失效时重新验证。
设置页后台探测已经得到相同签名的结论时，正式导出直接复用；正式导出完成的首次验证也回写同一缓存。
不得缓存为一个与编码器和输入路径无关的全局“设备支持 super-white”布尔值。

验证结果只决定本次采用“HLG 扩展信号范围”还是“HLG 名义范围”。准备阶段的信息可以更新为实际落点，
最终完成 Dialog、通知和诊断继续报告实际产物；不得把一次验证未通过描述成设备不支持 HLG。

## D139（2026-07-28）HLG super-white 必须通过真实编码—解码 P010 回环验证

D134 的 super-white 通过条件不是编码器接受 P010、产出非空样本或回报 HLG 格式，而是扩展信号经过
有损压缩与解码重建后仍可辨认。验证使用与正式候选相同的编码器名称、MIME、Profile、编码模式、
输入 P010 排布和色彩标记，执行以下完整链路：

```text
已知 limited-range P010 测试图
→ 正式编码器配置
→ 临时压缩码流与容器
→ 支持该 MIME/Profile 的实际解码器
→ CPU 可读 P010 重建样本
```

不得使用 Surface 解码后的截图、8-bit YUV 输出或 RGB 显示读回作为通过证据，因为这些中间转换自身可能
裁切、量化或色调映射 100% 以上信号。解码器必须明确支持 `COLOR_FormatYUVP010`，实际输出也必须确认为
10-bit P010；读取时遵循解码输出报告的 crop、stride、slice height、plane row stride 与 pixel stride，
不得假定紧密排布。

测试图使用足够大的平坦中性及红、绿、蓝阶梯色块，并只统计远离色块边缘的内部区域，以避开 4:2:0
色度抽样、运动估计和环路滤波对边界的污染。通过判定使用各区域的重建中位值及允许有损编码误差的区间，
不得要求逐像素或逐码值完全相等；但必须证明名义 100% 以上的阶梯没有全部塌缩到同一个 100% 端点，
且重建方向与申请的扩展颜色方向一致。具体测试档位与数值门禁由后续决定固定。

同时复核临时产物及解码输出的 BT.2020 primaries、HLG transfer、BT.2020 matrix 和 limited range
标记。像素回环与标记检查必须同时通过；仅有其中一项不能启用“HLG 扩展信号范围”。临时文件只位于应用
缓存目录，验证结束后删除，不进入媒体库。

若系统没有可用于 CPU 读取的 P010 解码器、实际输出退化为 8-bit、解码失败或样本不可可靠解析，则结论为
“扩展信号范围无法验证”，按 D135 使用名义范围 HLG；这不否定编码器的普通 HLG 能力，也不触发导出失败。
现有 `probeCandidate()` 的非空视频样本门禁继续用于判断编码候选是否成立，但不能代替本回环验证。

标准与平台依据：

- 现行 ITU-R BT.2408-9 对窄范围、名义峰值以上 headroom 与 super-white 的定义：
  <https://www.itu.int/dms_pub/itu-r/opb/rep/R-REP-BT.2408-9-2026-PDF-E.pdf>
- Android Compatibility Definition 对声明 `COLOR_FormatYUVP010` 的解码器须提供 CPU 可读 P010 的要求：
  <https://source.android.com/docs/compatibility/16/android-16-cdd#512_hdr_video>
- Android `MediaCodec` 与 `COLOR_FormatYUVP010` 的原始视频缓冲定义：
  <https://developer.android.com/reference/android/media/MediaCodecInfo.CodecCapabilities#COLOR_FormatYUVP010>

## D140（2026-07-28）HLG 按设备实测的 Y′CbCr 分量余量连续限制各颜色方向

HLG super-white 能力不是全局布尔值。若编码—解码回环只证明部分 limited-range P010 分量的扩展
码值能够保留，不得因此让所有颜色方向一起退回名义范围。探测分别得到 Y′、Cb、Cr 在当前编码与解码路径
中可可靠保留的连续安全区间，并由该区间推导每个非线性 RGB 颜色方向的设备上限 `W_device(u)`：

```text
W_actual(u) = min(W_standard(u), W_device(u))
C_S(u)      = hlgInverseOetf(W_actual(u))
```

其中 `W_standard(u)` 是 D134 按 BT.2100/BT.2408 得到且最高不超过 109% 的标准上限。
`W_device(u)` 必须由 Y′CbCr 的连续约束求得；不得把红、绿、蓝测试结果直接切成离散颜色档位，也不得在
正式像素路径中逐通道硬截断。具体求解算法由 D165 确定为方向域查表加二分。D128～D131 仍对 RGB 使用共同增益，因此某一方向的设备余量较小只会使该
方向更早进入平滑肩部，不会改变其线性 RGB 色度方向。

只要至少一个 FableSol 实际可达的颜色方向能够可靠使用 100% 以上信号，本次产物就可报告为
“HLG 扩展信号范围”；各方向只使用自身已经验证的余量。只有所有可达方向的
`W_device(u) <= 1.0`，或无法建立连续、安全的分量区间时，才整体采用 D135 的“HLG 名义范围”。
由此产生的设备间彩色高光容量差异是受控结果：每台设备只使用其实际编码路径能够保留的范围。

测试图档位、统计样本数和有损编码容差属于实现门禁参数，在计划与测试阶段选用保守默认值并以回归测试固定；
不再把每个数值拆成独立产品决策。它们不得放宽 D139 的真实回环要求，也不得以追求精确测量为由阻塞普通
HLG 导出；不能可靠求得扩展区间时直接使用名义范围即可。

## D141（2026-07-28）杜比视界导出仅支持 Profile 8.4

FableSol 的杜比视界产品能力收敛为 **Profile 8.4**。Profile 5 与 Profile 8.1 不进入设置页选项、
自动候选、产品能力矩阵、正式导出尝试或成功产物名称；设备诊断可以展示编码器原始广告的其它 Profile，
但必须标为设备声明，不能表述为 FableSol 可导出的格式。

理由如下：

- Dolby 官方 Android 第三方编辑样例只声明并演示编码或转码到 Profile 8.4，没有提供 Profile 5 或
  Profile 8.1 的第三方创作路径。
- Profile 8.1 需要 PQ/HDR10 兼容基层，而现有真机在申请 PQ 时把传递函数改为 HLG；该产物不能按
  Profile 8.1 验收。
- Profile 5 使用单层 IPT-PQ-c2 表示。公开 Android 接口没有提供可验证的 Profile 5 输入表示、RPU
  创作及封装契约，不能把普通 BT.2020/PQ 输入加一个 Profile 常量当成 Profile 5。
- 当前代码所谓 Profile 5 候选使用 `DolbyVisionProfileDvheDtr`，AOSP 将该常量映射为 `dvhe.04`；
  真正的 `dvhe.05` 常量是 `DolbyVisionProfileDvheStn`。但即使改用正确常量，也不能补齐上述
  Profile 5 创作链路，因此后续实现应删除该候选，而不是只替换常量。

有效的 HDR 自动格式顺序固定为：

```text
HDR10+ → 杜比视界 8.4 → HDR10 → HLG
```

若旧版持久化设置中存在显式 Profile 5 或 Profile 8.1，升级时迁移为显式“杜比视界 8.4”，以保留用户
明确选择杜比视界的意图；即使当前设备不支持 8.4，也不得进一步静默改成“自动”或其它 HDR 格式，而应按
D50 与 D106 显示该格式当前不可用，并在用户实际尝试导出时给出失败原因。

依据：

- Dolby 官方 Android 第三方编辑器：
  <https://github.com/DolbyLaboratories/dolby-vision-editor>
- AOSP `MediaProfiles.cpp` 对 Dolby Vision Profile 常量与 `dvhe` 编号的映射：
  <https://android.googlesource.com/platform/frameworks/av/+/master/media/libmedia/MediaProfiles.cpp>

## D142（2026-07-28）产物结构检查不得在完整编码后推翻一次成功导出

HDR 格式的产品目标是让用户得到能够被实际播放链路正确识别和显示的视频，不是把应用做成专业码流
认证工具。用户已经在 OPPO 手机上实际导出杜比视界 8.4，并确认系统相册播放时显示杜比视界标识；
该结果构成当前设备上从编码、封装到播放识别的有效端到端证据。

因此，后续若增加 RPU、SEI、Dolby Vision configuration record 或其它结构检查，必须区分其发生阶段：

- **编码前能力探测**可以用短样本排除无法配置、无法启动、没有视频样本或明显输出成其它格式的候选，
  以避免进入一次注定失败的完整渲染。
- **编码过程中的廉价观察**可以顺手记录实际码流特征，用于能力缓存、开发诊断和兼容性分析，但不得把
  应用内自制解析器的完整性当成比设备编码器和真实播放结果更高的权威。
- **完整编码并成功封装后**，不得仅因事后结构检查失败、结果不完整或应用无法解析，就删除已经生成的
  视频、要求用户重新导出、自动换格式重编码，或者把已完成结果改报为导出失败。

自动格式只在候选实际无法建立或编码、封装流程发生真实错误时继续尝试下一候选；不得在一份完整视频已经
成功生成后，仅为满足附加验收而重新渲染整段动画。显式格式同理：真实编码或封装错误仍按 D106 报错，
诊断检查的不确定性则不改变成功结果。

由此撤销上一轮讨论中“Profile 8.4 必须通过严格的最终文件 RPU 与封装双重硬门禁才允许发布”的建议。
正确的优先级是实际产物与播放行为优先，深入码流检查只作为低优先级质量保障和故障定位手段。

## D143（2026-07-28）杜比视界 8.4 复用 HLG super-white 自动增强

杜比视界 Profile 8.4 的兼容基层本身就是 10-bit BT.2020 HLG。ITU-R BT.2408 允许窄范围 HLG 使用
名义 100% 以上、视频数据范围以内的 super-white 扩展色容积；Dolby 官方 Android 转码指南也明确使用
`COLOR_FormatYUVP010` 向 Profile 8.4 编码器输入 BT.2020、limited-range、HLG 的 YUV 数据。因此，
Profile 8.4 不需要因为携带杜比动态元数据就放弃 D134～D140 已确定的 HLG 高画质映射。

显式选择“杜比视界 8.4”，以及“自动”最终落到该格式时，默认采用“自动增强”语义：

1. 优先以 `video/dolby-vision`、`DolbyVisionProfileDvheSt`、P010、BT.2020、HLG、limited range
   建立编码路径，并复用普通 HLG 的逐颜色方向 super-white 映射。
2. 当前杜比编码路径经 D139～D140 的编码—解码回环确认能够保留扩展码值时，正式导出使用该路径；
   杜比编码器仍负责根据实际增强后的 HLG 基层逐帧生成动态元数据。
3. 若当前杜比编码器不接受 P010、无法可靠确认扩展码值被保留，或高画质路径在正式渲染前无法建立，
   则继续导出名义范围的杜比视界 8.4；可以优先使用 P010 名义范围，并保留当前已实测可用的 Surface
   名义范围路径作为后备。
4. super-white 不可用不构成杜比视界格式失败，不切换为普通 HLG、HDR10 或 SDR，也不弹出导出失败；
   导出完成信息与设备诊断只如实说明本次杜比视界产物的 HLG 基层使用了“扩展信号范围”还是
   “名义范围”。

这里的回环验证只在正式动画渲染前决定输入路径，不在完整视频编码后推翻成功结果。D142 继续适用：
完整编码并成功封装的杜比视界视频不会因为事后附加检查而被删除、重编码或改报失败。

依据：

- Dolby 官方 Android Profile 8.4 转码指南，明确给出 P010、BT.2020、limited-range HLG 输入配置：
  <https://professionalsupport.dolby.com/s/article/Transcoding-Dolby-Vision-profile-8-4-to-Dolby-Vision-profile-8-4-on-Android>
- Dolby 对 Profile 8.4 的 HLG 兼容基层说明：
  <https://professionalsupport.dolby.com/s/article/Dolby-Vision-for-video-sharing-services-FAQs>
- 现行 ITU-R BT.2408-9 对 HLG super-white 与扩展色容积的说明：
  <https://www.itu.int/dms_pub/itu-r/opb/rep/R-REP-BT.2408-9-2026-PDF-E.pdf>

## D144（2026-07-28）杜比视界 8.4 显示 HLG 基层信号范围选项

用户显式选择“杜比视界 8.4”时，设置页显示“HLG 基层信号范围”，提供与普通 HLG 相同的两个选项：

- **自动增强（默认）**：按 D143 优先使用经验证的 P010 super-white；不可用时仍导出名义范围的
  杜比视界 8.4。
- **名义范围**：不尝试为本次导出建立 super-white 路径，始终把杜比视界 HLG 基层限制在名义 100%。

信息栏必须说明该设置改变的是杜比视界 8.4 的 HLG 兼容基层及其高饱和彩色高光容量，不是开关杜比
动态元数据；两种选项都仍是杜比视界 8.4，设备编码器都继续生成动态元数据。“自动”HDR 格式最终落到
杜比视界 8.4 时固定使用“自动增强”，不得读取当前隐藏的“名义范围”历史值。切换到 PQ 系格式或 SDR
时隐藏该设置，最终完成信息只报告本次实际采用的基层信号范围。

## D145（2026-07-28）离线导出使用 CQ 或 VBR，CBR 仅作设备后备

FableSol 视频是离线文件导出，没有实时传输链路要求固定瞬时带宽。用户可见的编码模式调整为：

- **恒定质量（CQ，默认）**：设备与当前格式、编码器、尺寸、帧率组合实际支持时，默认只下发
  `BITRATE_MODE_CQ` 与 `KEY_QUALITY`，不再像当前代码一样无条件同时下发 `KEY_BIT_RATE`；
  Android 官方明确说明同时设置编码质量与编码码率时行为未定义，所谓“部分厂商把码率当上限”
  的假设不能继续覆盖正式 API 契约。个别编码器要求 configure 必须携带码率键时，按 D167 的
  同模式兼容阶梯处理，不因此丢失 CQ 档。
- **目标码率（VBR）**：取代原来的用户可见“恒定码率”，下发 `BITRATE_MODE_VBR` 与
  `KEY_BIT_RATE`。码率滑杆继续表示目标平均码率，由编码器在复杂水体、高光和高速运动帧中动态分配
  更多码字，在静态或低复杂度帧中减少占用。

CBR 不再作为用户主动选择的正常模式。只有当前可用编码器不支持 VBR、但确实支持 CBR 时，才允许把
CBR 作为内部设备兼容后备；设置页信息栏、能力诊断和导出完成信息必须显示本次实际落在 CBR，不能仍写
成 VBR。若当前组合同时支持 VBR 与 CBR，始终优先 VBR。

旧版持久化的“恒定码率”选择迁移为“目标码率（VBR）”，并保留用户原有目标码率数值。该迁移保留的是
“控制文件体积和平均码率”的用户意图，而不是继续保留不适合离线导出的 CBR 算法。CQ、VBR 与必要的
CBR 后备都必须按 D51 使用正式导出相同的 MediaFormat 做短样本能力验证，能力缓存签名也必须区分实际
码控模式。

依据：

- Android 对 `BITRATE_MODE_CQ`、`BITRATE_MODE_VBR` 与实现相关质量区间的定义：
  <https://developer.android.com/reference/android/media/MediaCodecInfo.EncoderCapabilities>
- Android 对同时设置视频质量与视频码率时行为未定义的说明：
  <https://developer.android.com/reference/android/media/MediaRecorder#setVideoEncodingQuality(int)>
- Android 12 起对 VBR 编码实施最低画质保护、但不对 CBR 实施该保护：
  <https://developer.android.com/reference/android/media/MediaCodec>

## D146（2026-07-28）CQ 默认使用实际编码器公开的最高质量

FableSol 导出的默认取向是画质优先。当前代码把 CQ 默认值放在编码器 `qualityRange` 从下限到上限的
80% 位置，但 Android 没有赋予该比例任何跨编码器的质量含义；现有真机记录中，该默认值曾产生约
3 Mbps 的实际视频码率，也不能据此证明已经充分利用编码器质量能力。

当实际格式、编码器、Profile、输入方式、尺寸与帧率组合支持 CQ 时：

- 默认值和“恢复默认”均解析为该实际编码器 `qualityRange.upper`，即编码器通过公开 API 声明的最高
  CQ 质量值；不再保留 `DEFAULT_QUALITY_FRACTION = 0.8` 作为默认策略。
- 质量滑杆继续开放，用户可以主动降低质量以换取更小文件。界面显示编码器原始质量值及其实际区间，
  不把它伪装成跨厂商可比较的 CRF、QP 或客观百分比。
- 用户自定义原值按实际编码器路径分别保存；切换到另一编码器、MIME/Profile 或实质不同的输入路径时，
  不得把前一编码器的原始值直接套用。新路径没有自己的历史值时使用该路径的最高质量。
- CQ 不做额外的短片自动标定，也不以事前码率估算限制最高质量。编码过程中继续执行剩余空间保护；
  导出完成后显示实际文件大小与实际平均码率。

自动格式或自动编码器在解析出实际候选之后，才读取该候选对应的 CQ 值。能力探测与正式导出必须使用
同一个解析结果，不能由设置页某个“代表性编码器”的质量区间代替实际候选。

依据：

- Android `getQualityRange()` 对实现相关质量值及“更高值通常获得更好画质、较低压缩率”的定义：
  <https://developer.android.com/reference/android/media/MediaCodecInfo.EncoderCapabilities#getQualityRange()>

## D147（2026-07-28）VBR 默认码率按实际输出自动推导，不增加新控件

VBR 不再使用全局固定的 `120 fps → 24 Mbps、60 fps → 14.4 Mbps` 默认值。FableSol 的实际画布
宽度会随时钟字形变化，编码器对齐后约覆盖 `1024×1472`～`1344×1472`；最大画布在 120 fps 下约为
2.37 亿像素/秒，与 `2560×1440 @ 60 fps` 的像素率接近。固定 24 Mbps 会让不同宽度、帧率、编码器
族和信号位深获得不一致的每像素质量。

VBR 默认目标码率由以下已解析的实际输出参数计算：

```text
自动目标码率 =
    实际编码宽度 × 实际编码高度 × 实际帧率
    × 编码器族基础系数
    × 位深及 HDR/SDR 信号系数
```

结果夹入当前实际编码器的 `bitrateRange`。格式仅因携带 HDR10+ 或杜比动态元数据不重复增加系数；
真正影响压缩负担的是编码器族、像素率、位深和信号特性。各编码器族的保守基础系数在计划阶段统一选定，
并用大面积深色渐变、静水及高速高光水体回归，不再把每个边界数值拆成独立产品决策。

该模型不增加“自动/自定义”按钮、标签组或开关，继续只使用现有码率滑杆：

- 从未手动调整或执行现有“恢复默认”后，滑杆位于当前组合的自动推导值；组合变化时位置随新的自动值
  更新，信息栏显示“自动（N Mbps）”及推导依据。
- 用户拖动现有滑杆后，该值成为绝对 Mbps 的自定义目标；切换分辨率、帧率、格式或编码器时不再按
  `0.6` 或其它比例自动缩放，仅在超出实际编码器合法范围时夹取并说明申请值与实际值。
- 已明确保存旧版码率键的用户迁移为自定义 Mbps；没有保存过该键的用户进入新的自动默认。

不为自动目标码率额外预扫描整段动画。VBR 编码器本身负责在复杂帧与简单帧之间分配码率；选择 VBR 的
用户仍可依靠目标平均码率控制大致文件体积。设置页的体积估算使用解析后的目标码率，导出完成信息继续
显示实际平均码率，因为 VBR 目标值不保证等于最终文件的精确平均值。

外部参照：

- YouTube 对约 1440p 高帧率 HDR 上传给出的 30 Mbps 参考值，以及按分辨率、帧率和 HDR/SDR
  区分码率的做法：
  <https://support.google.com/youtube/answer/1722171>
- Android 编码器公开合法码率区间的接口：
  <https://developer.android.com/reference/android/media/MediaCodecInfo.VideoCapabilities#getBitrateRange()>

## D148（2026-07-28）B 帧作为独立导出选项，默认关闭

视频导出设置增加用户可见的 B 帧选项，默认不启用。该选项不并入“自动码率”、CQ/VBR 或
“高复杂度编码”，也不由画质优先默认值自动打开；它表示用户明确接受使用帧重排换取更高压缩效率。

未启用时，所有新安装、升级用户以及执行“恢复默认”后的配置均明确禁止 B 帧。Android 10 及以上
请求 `KEY_MAX_B_FRAMES = 0`；Android 8～9 使用 `MediaMuxer`，按 Android 官方分享编码建议同时
采用可用的低延迟约束以避免编码器自行产生 B 帧。旧版本没有该设置，不推断用户意图，迁移值固定为关闭。

B 帧只影响压缩参考结构、编码效率、耗时及帧重排，不改变 FableSol 的渲染、色彩空间、传递函数、
HDR 格式或动态元数据内容。

用户开启 B 帧后，若当前系统版本、编码器、Profile、输入方式或封装路径不支持，仍以 `0` 个 B 帧
完成原格式导出；不得因此切换 SDR/HDR 格式、切换 HDR 元数据格式或把整次导出判为失败。设置页下方
信息栏应在能力解析后说明“当前组合不支持 B 帧，将按无 B 帧编码”，导出完成信息记录本次实际采用
无 B 帧。该降级属于压缩工具不可用，不改变用户选择的目标视频格式。选项开启后的格式适用范围及请求
方式按下文执行。

选项开启且当前路径可用时，固定请求 `KEY_MAX_B_FRAMES = 1`，不再增加 B 帧数量滑杆或二级选项。
这里的 `1` 表示任意两个 I/P 参考帧之间最多允许出现 **1 个连续 B 帧**，不是整个 GOP 最多只有
1 个 B 帧；例如编码器可以在同一 GOP 内自行采用 `I B P B P B P`，也可以少用或完全不用 B 帧。
Android API 将该值定义为上限而不是强制数量，因此设置为 `1` 后不得仅凭配置值宣称码流一定含有
B 帧。该保守上限遵循 Android 离线分享编码的正式建议，并降低额外的重排深度和厂商兼容风险。

B 帧选项的格式适用范围固定如下：

- H.264/AVC High 与 Main Profile 可以请求最多 1 个 B 帧；Baseline Profile 不适用。
- 所有基于 HEVC 的可用导出路径可以请求最多 1 个 B 帧，包括 HEVC SDR、HDR10、HDR10+、HLG
  以及杜比视界 Profile 8.4。HDR10+ 的逐帧元数据继续绑定输入帧，由编码器按时间戳处理重排；
  杜比视界 RPU 仍由接受该配置的设备编码器生成。
- AV1 不套用 H.26x 的 B 帧概念或 `KEY_MAX_B_FRAMES`；其双向/复合预测与参考帧结构继续由 AV1
  编码器自行决定。

当前实际组合为 H.264 Baseline、AV1、API 29 以下或其它无法建立 B 帧路径的情况时，控件显示为
不适用，信息栏说明具体原因。开启 B 帧不得改变自动格式或自动编码器的候选优先级，也不得为了满足
该压缩偏好把 AV1、HDR10+、杜比视界或其它既定输出切换成 HEVC/H.264 的另一候选；始终先解析目标
格式与编码器，再判断该选项能否应用。

补充（2026-07-30 裁定）：能力探测的合成基线固定 B 帧关（矩阵行的键不含 B 帧轴，纳入用户
开关会让结论随偏好漂移，或迫使矩阵升为第七维）。用户开启后若正式导出的编码器初始化因该
请求被拒，按运行时退让（见候选循环）回到探测已证明的无 B 帧形态，完成信息如实显示未申请。

依据：

- Android `KEY_MAX_B_FRAMES` 的默认值、上限语义及 API 29 起可用的定义：
  <https://developer.android.com/reference/android/media/MediaFormat#KEY_MAX_B_FRAMES>
- Android 对 API 26～28 使用 `MediaMuxer` 时禁用 B 帧、API 29 起才建议主动启用的说明：
  <https://developer.android.com/media/optimize/sharing#b-frames>

## D149（2026-07-28）高复杂度编码作为独立选项，默认开启

视频导出设置增加独立的“高复杂度编码”开关，默认开启。它与 B 帧、编码模式（CQ/VBR）及视频格式
彼此独立，不增加厂商原始复杂度数值滑杆。Android 的复杂度值是编码器及 MIME 相关的实现细节，
不能跨设备或跨编码器解释成统一等级或百分比。

- 开启时，在实际格式、编码器、Profile、输入方式、分辨率和帧率均已解析后，读取该实际编码器的
  `EncoderCapabilities.getComplexityRange()`，请求 `MediaFormat.KEY_COMPLEXITY =
  complexityRange.upper`，让编码器使用其公开的最高复杂度。更高复杂度允许使用更多编码工具，
  目标是在相同码率下改善画质或在相同质量下提高压缩率，代价是更长导出时间、更高功耗和发热。
- 关闭时不强制 `complexityRange.lower`，而是完全省略 `KEY_COMPLEXITY`，保留厂商对当前组合的
  默认复杂度。关闭表示“不额外要求最高复杂度”，不是主动要求最低画质或最快编码。
- 若实际编码器只报告单一复杂度、范围不可用，或正式导出前的同配置短探测不接受最高值，则省略
  `KEY_COMPLEXITY` 并使用厂商默认值继续原格式导出。不得因此切换编码模式、编码器族、SDR/HDR
  格式或动态元数据格式，也不得把整次导出判为失败。

新安装、旧版本升级以及执行“恢复默认”后均默认开启。设置页信息栏必须说明高复杂度可能改善压缩
画质但会增加耗时、功耗和发热；能力解析后显示当前实际使用“最高复杂度”还是“厂商默认”。导出完成
信息同样记录本次实际申请的策略，但不得仅凭申请成功宣称厂商一定启用了某一种内部编码工具。

依据：

- Android `KEY_COMPLEXITY` 的设备及编码器相关语义：
  <https://developer.android.com/reference/android/media/MediaFormat#KEY_COMPLEXITY>
- Android 对复杂度范围及“更高值使用更多编码工具、更低值节省功耗或时间”的定义：
  <https://developer.android.com/reference/android/media/MediaCodecInfo.EncoderCapabilities#getComplexityRange()>

## D150（2026-07-28）所有视频导出声明为非实时编码，不请求 operating rate

FableSol 视频导出是离线文件生成，不要求编码器以最终播放帧率实时完成。所有 SDR 与 HDR 格式、
所有编码模式以及“高复杂度编码”开关的两种状态，均在 `MediaFormat` 中请求
`KEY_PRIORITY = 1`，把任务声明为非实时、尽力完成的编码；不增加用户设置。

不设置 `KEY_OPERATING_RATE`。`KEY_FRAME_RATE` 仍表示码率控制、GOP 计算和产物播放所需的目标帧率，
每帧 PTS 仍按用户选择的 60/120 fps 等时间线生成；不设置 operating rate 只是不要求硬件在现实时间
内以该吞吐量处理输入，不会降低产物帧率或改变播放速度。这样可以允许最高复杂度编码在需要时花费更长
时间，也避免把离线任务错误纳入实时资源保证。

若设备忽略非实时优先级提示，仍按当前格式完成导出；不得据此报错、切换编码器族、切换 SDR/HDR 格式
或关闭用户选择的其它画质工具。设置页信息栏在“高复杂度编码”说明中提示导出可能慢于实时，但不把
`KEY_PRIORITY` 暴露为独立选项。

依据：

- Android 对 `KEY_PRIORITY = 1` 为 non-realtime、best effort 资源规划提示的定义：
  <https://developer.android.com/reference/android/media/MediaFormat#KEY_PRIORITY>
- Android 对 `KEY_OPERATING_RATE` 用于编码器资源规划及所需处理速率的定义：
  <https://developer.android.com/reference/android/media/MediaFormat#KEY_OPERATING_RATE>

## D151（2026-07-28）VBR 增加默认开启的复杂帧质量保护

视频导出设置增加独立的“复杂帧质量保护”开关，默认开启。该设置只作用于目标码率（VBR）模式，
不作用于恒定质量（CQ）或仅作设备后备的 CBR：CQ 已由编码器质量值直接表达目标，CBR 则不应再用
质量下限破坏其固定码率约束。

开启且当前系统为 Android 12（API 31）及以上、实际编码器声明
`CodecCapabilities.FEATURE_QpBounds`、同配置短探测接受该设置时，下发
`MediaFormat.KEY_VIDEO_QP_MAX = 40`。QP 越高表示量化越重；该上限用于阻止复杂水体、高速高光和
渐变帧在码率压力下被编码器压到更差的 QP。统一键的值直接作为 I 帧上限，P/B 帧由 Android
按当前 MIME 的规则推导，不再暴露 I/P/B 原始 QP 数值或额外滑杆。

关闭时完全省略所有 QP 上下限。开启但系统、实际编码器、Profile、输入方式或短探测不支持时，同样
省略 QP 上限并以原格式、原编码器继续导出；不得切换 CQ/VBR、编码器族、SDR/HDR 格式或动态元数据
格式，也不得把导出判为失败。

该保护允许 VBR 为守住复杂帧最低质量而超过目标平均码率，因此设置页信息栏必须明确提示“复杂画面
更稳定，但实际码率和文件大小可能高于目标”；体积估算继续以 VBR 目标值为基准并标明可能上浮。
完整视频成功编码后，不得仅因实际码率或文件大小上浮而删除、重编或放宽 QP 上限。新安装、旧版本升级
以及执行“恢复默认”后均默认开启。

依据：

- Android 对 API 31 起标准 QP 上限、推荐最大 QP 40 及其码率上浮代价的说明：
  <https://developer.android.com/media/optimize/sharing#quantization-parameter-qp>
- Android `KEY_VIDEO_QP_MAX` 对 I/P/B 帧以及按 MIME 推导的定义：
  <https://developer.android.com/reference/android/media/MediaFormat#KEY_VIDEO_QP_MAX>
- 编码器是否公开支持 QP bounds 的标准能力标志：
  <https://developer.android.com/reference/android/media/MediaCodecInfo.CodecCapabilities#FEATURE_QpBounds>

## D152（2026-07-28）Profile 保持画质优先，Level/Tier 选择最低充分值

撤销普通 AVC、HEVC 与 AV1 路径直接使用
`advertised.maxByOrNull { it.level }` 的现状，并修订 D21 中笼统使用编码器广告 level 的旧规则。
Profile 与 Level 必须分别解释：

- Profile 决定编码工具、位深和格式能力，继续按既定阶梯选择，例如 H.264 High、HEVC Main10
  以及相应 HDR Profile。
- Level/Tier 描述解码所需的画面尺寸、像素/块吞吐量、码率、解码图像缓存和参考结构上限，
  不是画质档位。申请更高 Level 不会使同一 Profile 的画面更清晰，反而可能让只支持较低 Level
  但本可正常播放该视频的设备拒绝产物。

编码尺寸完成编码器及 64px 分享兼容对齐后，根据实际宽高、播放帧率、当前解析后的码率（仅
VBR 与 CBR 后备；CQ 按 D168 不以码率定档）、位深、Profile 及 B 帧/参考结构，按 AVC、
HEVC、AV1 各自标准约束计算最低需求，再从实际编码器对该 Profile 广告的 Level 中选择足够且
最低的一档。VBR 的质量保护可能引起码率上浮，计划阶段为
Level 的码率约束选择保守余量并以边界回归固定，不把每个余量数值拆成产品选项。

HEVC 与 AV1 的 High Tier 仅在解析后的码率需求无法由同 Level 的 Main Tier 容纳、且实际编码器
广告支持时使用；不得把 High Tier 当作画质开关。杜比视界已经按对齐后的像素率选择最低充分 Level，
继续沿用并纳入同一套公共解析语义。

`KEY_LEVEL` 只是配置提示。短探测和正式导出使用同一个已解析 Level；若编码器输出格式报告了更高但
合法且足够的实际 Level，则接受、封装并在诊断中记录，不因此重编、切换格式或判为失败。不得把完整
视频编码后的 Level 检查升级为推翻成功产物的严格门禁。本策略不增加用户设置。

依据：

- Android 对 `KEY_LEVEL` 必须与配置兼容、编码器最终应使用足以解码的 Level，但不受申请值硬性
  上限约束的说明：
  <https://developer.android.com/reference/android/media/MediaFormat#KEY_LEVEL>

## D153（2026-07-28）应用自有 P010 路径优先使用 FP16 呈现中间面

当前 HDR10+ 的应用自有 P010 桥接路径先把 FableSol 的 FP16 场景渲染为 PQ 编码的
`GL_RGB10_A2` 呈现纹理，再由亮度与色度着色器量化、打包为 10-bit limited-range P010。
其中色度着色器已经对每个 4:2:0 色度样本覆盖的 2×2 非线性 R′G′B′ 像素求平均，并非简单
抽取单个像素；本轮需要修正的主要问题是 `FP16 → RGB10_A2 → P010` 带来的两次 10-bit
量化，而不是补做色度平均。

应用能够自行生成 P010 时，正式质量路径调整为：

1. 保持 FableSol 现有 FP16 线性场景渲染；
2. 把 PQ 或 HLG 呈现结果写入可渲染的 `RGBA16F` 中间面；
3. 亮度、色度和逐帧统计均读取这一个高精度呈现中间面；
4. 只在生成最终 P010 码值时进行一次目标 10-bit limited-range 量化与打包。

该路径不要求把浮点纹理读回 CPU。现有用于字节打包与 `glReadPixels` 的 RGBA8 输出目标继续
保留，它们承载的是已经量化完成的 P010 字节，不构成额外颜色量化。HDR10+ 的逐帧统计也必须
读取同一实际送入 P010 转换的 FP16 呈现结果，避免元数据统计与编码输入来自不同精度的画面。

不能仅凭 GLES 版本或扩展字符串假定半浮点颜色附件可用。建立正式渲染前，必须同时检查所需
浮点颜色缓冲能力，并实际创建目标、验证 framebuffer completeness；成功才使用 `RGBA16F`。
若当前 GPU、驱动或该具体格式组合无法建立完整的 FP16 framebuffer，则无提示交互地退回现有
`RGB10_A2` 中间面，继续导出用户选择的原 HDR 格式。该兼容后备不得导致格式切换、导出失败或
完整视频重编码；设备诊断和导出完成信息可以如实记录本次使用“高精度中间面”还是“10-bit
兼容中间面”。

这是内部画质实现，不增加用户选项。它适用于当前 HDR10+ 应用自有 P010 路径，也适用于后续
按既定决策实现的 HLG super-white、杜比视界 Profile 8.4 等应用自有 P010 路径；由厂商
Surface 编码器自行完成 RGB→YUV 的路径不在本决策中强行替换。实现阶段使用暗部平滑渐变、
高饱和边缘、明亮水面等固定素材，对比 FP16 直达 P010 与 `RGB10_A2` 后备路径，重点检查色带、
码值误差、颜色误差和逐帧统计一致性。

依据：

- Khronos `EXT_color_buffer_half_float` 对 `RGBA16F` 等半浮点纹理可作为颜色渲染目标的定义：
  <https://registry.khronos.org/webgl/extensions/EXT_color_buffer_half_float/>
- Khronos OpenGL ES 扩展注册表，用于核对目标 Android GLES 实现对应的浮点颜色缓冲扩展：
  <https://registry.khronos.org/OpenGL/index_es.php>

## D154（2026-07-28）应用自有 P010 的色度降采样与码流色度位置保持一致

当前 P010 色度着色器把每个 2×2 非线性 R′G′B′ 像素直接平均，再计算一组 Cb/Cr。该结果的
色度中心位于相对左上亮度样本的 `(0.5, 0.5)`，等价于 H.273 的
`Chroma420SampleLocType = 1`。但 BT.2020/BT.2100 的 4:2:0 Y′CbCr 规定色度样本与偶数
水平、偶数垂直位置的亮度样本共点，即 Type 2、偏移 `(0, 0)`；H.274 也建议 HEVC 的 VUI
显式标记 Type 2。

应用自有 P010 路径不再使用当前无位置语义的 2×2 box average，改用有明确相位的二维可分离
低通滤波后再进行 2:1 水平和垂直抽取：

- BT.2020/BT.2100 PQ 与 HLG 的首选路径采用 Type 2，以偶数位置像素为滤波中心；
- 基础滤波采用 ITU HDR 转换指南使用的短抽头 `f0 = [1/8, 6/8, 1/8]`，分别作用于水平与
  垂直方向；实现阶段可以在固定回归素材上与抗混叠更强的 `f1 = [1/4, 2/4, 1/4]` 比较，
  但不得无依据换成锐化、振铃明显或简单点采样的滤波器；
- 边界按保持相位的一致延拓方式处理，不得因为纹理边缘把首末色度样本移动到另一采样位置。

Android 公开的 `MediaFormat` 没有供应用可靠指定 HEVC VUI 色度采样位置的标准键，因此同配置
短探测需要读取实际编码器产生的 SPS/VUI：

1. 若编码器显式输出 Type 2，则正式导出使用首选 Type 2 滤波；
2. 若编码器明确输出 Type 0～5 中的其它合法位置，则使用相同低通质量、但将滤波相位调整到
   编码器实际声明的位置；
3. 若编码器未携带色度位置信息，则按 HEVC 生态的 nominal/default Type 0 解码语义选择相位，
   并在诊断中记为“码流未显式声明、按 Type 0 匹配”，而不是继续生成 Type 1 数据。

该策略的首要目标是让 P010 中的实际色度样本与播放器依据码流得到的空间位置一致；在编码器能够
正确声明时使用 BT.2100 Type 2，在不能声明时也不能强行生成 Type 2 后让解码器按 Type 0
解释。色度位置不匹配主要影响高饱和细边缘、钟表文字、星光和水面高光附近的色边与半像素相位，
不会作为整体亮度或全局色彩风格选项暴露给用户。

色度位置探测或首选 Type 2 不可用不得导致导出失败、HDR/SDR 格式切换、编码器家族切换、完整
视频重编码或事后删除。正式导出必须直接采用探测后已解析的相位；设备诊断和导出完成信息可以
记录“Type 2 共点”或实际兼容相位。本决策适用于 HDR10+ 以及后续应用自行生成 P010 的 HLG、
杜比视界 Profile 8.4 路径；厂商 Surface 输入路径继续由图形栈与编码器完成色度转换。
AV1 编码器的对应声明与解析按 D170 以同一原则执行。

依据：

- ITU-T H.273 对 Type 0～5 相对位置的定义，以及 BT.2020/BT.2100 4:2:0 对应 Type 2 的说明：
  <https://www.itu.int/rec/dologin_pub.asp?id=T-REC-H.273-202407-I%21%21PDF-E&lang=e&type=items>
- ITU-T H.274 对 BT.2020/BT.2100 HEVC VUI 应显式使用 Type 2 的说明：
  <https://www.itu.int/epublications/publication/itu-t-h-274-v3-2023-09-versatile-supplemental-enhancement-information-messages-for-coded-video-bitstreams>
- ITU-T H.Sup15 对 HDR/WCG 4:2:0 共点采样和 `f0`、`f1` 短抽头滤波器的参考：
  <https://www.itu.int/rec/dologin_pub.asp?id=T-REC-H.Sup15-201701-I%21%21PDF-E&lang=e&type=items>
- AOSP Codec2 对 H.273 Type 0～5 色度偏移的内部定义；该能力并未形成供普通 Android 应用
  通过 `MediaFormat` 可靠配置的公开键：
  <https://android.googlesource.com/platform/frameworks/av/+/73f67d3e66b8cbbfe0484c4d9bf0713fce9eb0fe/media/codec2/core/include/C2Config.h>
- FFmpeg HEVC 解码器在 VUI 未携带色度位置时按 left/Type 0 解释的业界实现：
  <https://www.ffmpeg.org/doxygen/8.0/libavcodec_2hevc_2hevcdec_8c_source.html>

## D155（2026-07-28）PQ 应用自有 P010 默认执行闭环亮度修正

BT.2020 NCL Y′CbCr 把全分辨率 Y′ 与 4:2:0 Cb/Cr 分开编码。色度降采样、10-bit 量化并在
播放器中重新上采样后，每个像素实际结合的 Cb/Cr 已经不同于原始 4:4:4 色度；若仍原样保留
原始 Y′，经 PQ EOTF 重建的线性 RGB 与亮度可能在高饱和边缘发生明显偏差，即 HDR/WCG
转换文献所称的 chroma leakage。它可能表现为钟表文字、星光、水面反射和高饱和细边缘附近的
错误明暗轮廓或色边，不是编码器再增加码率就能完全恢复的信息。

PQ 的应用自有 P010 路径默认采用闭环亮度修正：

1. 从 D153 的高精度 PQ R′G′B′ 呈现中间面计算全分辨率 4:4:4 Y′CbCr；
2. 按 D154 的实际色度位置和低通滤波器生成 4:2:0 Cb/Cr，并执行正式输出所用的 10-bit
   limited-range 量化；
3. 对量化后的 Cb/Cr 反量化，并使用与实际色度位置匹配的参考上采样恢复到全分辨率；
4. 逐像素求得修正后的 Y′，使其与重建 Cb/Cr 组合并通过 PQ EOTF 后的线性光亮度尽量接近
   原始 FP16 呈现画面；
5. 最终 P010 Y 平面写入修正并量化后的 Y′，Cb/Cr 平面仍写入第 2 步得到的正式色度值。

正式导出使用 ITU-T H.Sup15 给出的单步闭式思路，不在每个像素上执行最多十次的二分迭代。
测试代码可以实现或复用高精度迭代解作为 oracle，验证闭式解确实降低重建后的线性亮度误差，
并确认其结果与迭代参考在固定容差内一致；测试 oracle 不进入正式逐帧导出关键路径。

亮度修正必须同时受以下约束：

- 最终 Y′ 保持在目标 limited-range 的合法 10-bit 范围内；
- 与上采样色度组合后的 R′G′B′ 和线性 RGB 不得因为修正产生新的非法值、额外硬裁剪或明显
  色相反转；
- 仅在修正后的线性亮度误差小于原始 Y′ 时采用修正值；不改善或解不稳定时保留原始合法 Y′；
- 最大允许改变量通过固定回归素材确定保守边界，不开放原始数值设置，也不把该过程当成锐化、
  局部对比度或 tone mapping 工具。

该计算完全由当前像素、同一帧已经确定的色度样本和固定滤波器决定，不读取前后帧统计，不调整
整帧曝光、参考白、HDR 强度、MaxCLL/MaxFALL 或 HDR10+ 曲线，因此不会引入逐帧全局亮度漂移，
也不改变用户选定的画面风格。HDR10+ 的动态元数据统计继续描述 D153 的原始高精度呈现目标；
闭环修正的目的正是让解码重建结果更接近该目标。

这是 PQ 应用自有 P010 的内部默认质量处理，不增加用户选项，也不并入“高复杂度编码”开关。
若当前 GPU 无法建立所需临时目标、实现自检未通过或运行时资源分配失败，则保留普通逐像素 Y′
转换并继续原格式导出；不得因此切换 HDR 格式、报整次导出失败、事后删除或重编码完整视频。
当前直接覆盖 HDR10+ 字节缓冲路径，未来其它由应用生成 PQ P010 的路径同样适用；Surface 输入
继续由厂商颜色转换链负责。

依据：

- ITU-T H.Sup15 对 PQ NCL Y′CbCr 4:2:0 色度泄漏、迭代与闭式亮度修正方法的说明：
  <https://www.itu.int/rec/dologin_pub.asp?id=T-REC-H.Sup15-201701-I%21%21PDF-E&lang=e&type=items>
- HDRTools 参考实现的变更记录，包括迭代闭环 luma micrograding、其它亮度修正和自适应色度
  降采样方法：
  <https://gitlab.com/standards/HDRTools/-/blob/04f37a931e6b5f6dce38b0265a63971563f093f0/CHANGES.txt>

## D156（2026-07-28）HLG 与杜比视界 8.4 使用场景线性闭环亮度修正

HLG 采用 BT.2020 NCL Y′CbCr 时同样可能因 4:2:0 色度降采样和重建产生 chroma leakage。
但 HLG 是场景参考系统，播放设备会根据自身显示峰值、环境和 system gamma 形成 OOTF；不能把
D155 的 PQ 绝对显示亮度目标或某一固定参考显示峰值直接套到 HLG。

普通 HLG 以及杜比视界 Profile 8.4 的应用自有 P010 基层默认采用 HLG 专用闭环修正：

1. 先按 D154 及本次实际采用的名义范围或 super-white 安全范围，生成、量化并参考上采样
   4:2:0 Cb/Cr；
2. 对原始 HLG R′G′B′ 和结合上采样色度得到的重建 R′G′B′ 分别应用 HLG inverse OETF，
   回到相对场景线性 RGB；
3. 以 BT.2020 线性亮度权重最小化场景线性亮度误差，求取每像素修正后的 Y′；
4. 使用 HLG 分段 inverse OETF 自身的函数值与导数完成单步闭式／局部线性化修正，不复用 PQ
   EOTF 的近似系数；高精度迭代解只作为测试 oracle。

选择场景线性目标意味着算法不假定 400、1000、2000 尼特或本机显示峰值。离线回归仍须把修正
前后结果分别通过多组合法 HLG OOTF/system gamma，确认不同目标显示峰值下的显示线性亮度误差
总体降低，并且任何测试档都没有出现新的明显色相、亮度轮廓或高光断层；该多显示器回归不是运行时
逐帧分析，也不会让产物绑定到某一台显示器。

修正后的样本必须服从本次已经解析的实际信号范围：

- “名义范围”始终限制在名义 HLG 码值和色容积内，不得借亮度修正静默进入 super-white；
- “自动增强”实际落到扩展路径时，只可使用 D139～D140 对当前编码—解码链分别验证出的 Y′、
  Cb、Cr 连续安全区间，不得把统一 4～1019 全范围当成无条件可用；
- 杜比视界 8.4 使用其 HLG 基层本次实际落点的相同约束，修正不改变 RPU 生成职责，也不开关
  杜比动态元数据；
- 修正不降低场景线性误差、越过安全区间、产生新的 RGB 非法值或数值解不稳定时，保留普通合法
  Y′。

该算法逐像素且不读取前后帧统计，不调整 HLG 颜色方向高光肩部、参考白、信号范围选项、整体曝光
或画面风格，因而不是逐帧动态 HLG。它是普通 HLG 与杜比视界 8.4 应用自有 P010 路径的内部
默认质量处理，不增加设置，也不并入编码器复杂度开关。Surface 输入路径继续由厂商转换；临时目标
或自检不可用时使用普通 Y′ 并继续原格式导出，不报错、不换格式、不事后重编码。

依据：

- ITU-R BT.2390 对 PQ/HLG NCL Y′CbCr 的色度误差可传播到亮度，以及 HLG 为场景参考系统、
  OOTF 随目标显示条件变化的说明：
  <https://www.itu.int/dms_pub/itu-r/opb/rep/R-REP-BT.2390-9-2021-PDF-E.pdf>
- ITU-T H.Sup18 对 HLG 从场景线性 RGB 经 OETF、BT.2020 Y′CbCr、10-bit 量化和 4:2:0
  色度降采样的正式预编码链路：
  <https://www.itu.int/rec/dologin_pub.asp?id=T-REC-H.Sup18-201710-I%21%21PDF-E&lang=e&type=items>
- 公开的高效亮度修正方案对 PQ、BT.1886 与 HLG 等传递函数采用同类局部线性化的说明：
  <https://patents.justia.com/patent/20190238866>

## D157（2026-07-28）应用自有 P010 在最终码值量化时使用静态蓝噪声无偏舍入

当前导出呈现只在 8-bit 编码档位启用 `export_present.frag` 中幅度按 `/255` 计算的 RGB
抖动；应用自有 P010 路径则先把呈现结果写入高精度或 `RGB10_A2` 中间面，再在
`p010_luma.frag` 与 `p010_chroma.frag` 中直接把 Y′、Cb、Cr 四舍五入为 10-bit 码值。
FableSol 含有大面积平滑暗部、背景渐变和水面缓变，即使 10-bit 已显著减轻色带，最终整数
量化仍有必要使用与目标量化器匹配的低幅度抖动。

应用自有 P010 路径采用以下内部默认策略：

1. 不复用现有呈现阶段 `/255` 的 RGB 抖动。该幅度针对 8-bit RGB 输出设计，放到 P010
   前不仅相对 10-bit 码值过大，还会先在 RGB 域制造不与最终 Y′CbCr 量化边界对齐的彩色
   扰动。
2. 在最终 Y′、Cb、Cr **码值域**分别执行蓝噪声阈值舍入：使用确定性的 64×64 蓝噪声阈值
   表，把原来的固定四舍五入改为局部无偏的上下码值选择；每个样本的量化误差保持在不足
   一个目标 10-bit 码值内，不叠加多码值强度的噪声。
3. 蓝噪声图案默认固定在导出画布坐标中，不随帧旋转、镜像、平移或重新随机化。Y′、Cb、
   Cr 使用确定且互不相同的相位偏移，避免三个量化器形成规则相关性；但不启用逐帧时域
   抖动，以免在视频中引入闪烁、显示器时域混叠和不必要的编码压力。
4. 处理顺序必须与 D155～D156 的闭环亮度修正一致：先用蓝噪声阈值获得本帧实际写出的
   量化 Cb/Cr，以这些实际色度码值完成参考上采样和闭环 Y′ 修正，再对修正后的 Y′执行最终
   蓝噪声阈值量化。闭环不能假设色度仍由普通四舍五入产生。
5. HDR10+ 的 maxscl、平均亮度、分布百分位等统计继续读取 D153 的抖动前高精度呈现目标；
   不把人为量化噪声计入内容分析，也不让噪声改变逐帧动态元数据。
6. 舍入后的所有样本必须服从本次实际信号范围：名义范围使用名义边界；HLG／杜比视界 8.4
   super-white 自动增强使用 D139～D140 已验证的逐分量连续安全区间。准确落在黑位、中性
   色度、白位或安全边界整数码值上的样本不得被抖动推离该码值，最后仍执行合法范围钳制。

该处理不改变平均曝光、参考白、HDR 强度、色彩方向或画面风格，不增加用户选项，也不并入
“高复杂度编码”开关。蓝噪声阈值资源创建、自检或取样路径不可用时，退回当前普通四舍五入
并继续按用户选定的原格式导出；不得据此切换 HDR 格式、报整次导出失败、事后重编完整视频
或删除已经完成的视频。固定回归素材需要同时检查平滑渐变的色带改善、静止画面的纹理可见性、
运动画面的闪烁、平均码值偏差和编码体积变化。

依据：

- libplacebo 对低位深整数输出默认启用抖动、以 64×64 蓝噪声作为默认高质量方法，并默认
  关闭时域抖动的实现策略：
  <https://libplacebo.org/options/>
- FFmpeg `libplacebo` 滤镜对整数输出抖动、伪蓝噪声默认值及默认关闭时域抖动的说明：
  <https://www.ffmpeg.org/ffmpeg-filters.html>
- libplacebo 的公开着色器实现：在目标位深码值域以蓝噪声阈值执行舍入，并仅在显式启用
  temporal 参数时按帧旋转或镜像阈值图案：
  <https://raw.githubusercontent.com/haasn/libplacebo/master/src/shaders/dithering.c>

## D158（2026-07-28）应用自有 P010 成为所有 10-bit 导出的首选输入路径

当前 HDR10+ 因逐帧动态元数据接口的限制必须使用字节缓冲 P010，而 HDR10、HLG、杜比视界
Profile 8.4 与 10-bit SDR 主要仍通过编码器 input Surface 输入 RGB，由厂商图形栈和编码器
完成 RGB→Y′CbCr。若继续维持这种分工，D153～D157 已确定的 FP16 呈现中间面、准确色度位置、
闭环亮度修正和目标码值蓝噪声量化只会覆盖 HDR10+，同一应用的其它 10-bit 格式则可能因设备
实现不同而产生不同的矩阵精度、色度相位、量化和边界行为。

因此，所有 10-bit SDR／HDR 导出统一采用“应用自有 P010 优先、同格式 Surface 后备”的质量
策略：

1. 对本次实际编码器、MIME、Profile、Level/Tier、分辨率、帧率、码控、B 帧及色彩标记组合
   查询其公开色彩格式；编码器列出 `COLOR_FormatYUVP010` 或等价的标准 P010 值，并且同配置
   短探测能够接收、编码和解码一帧合法 P010 时，正式导出优先使用应用自有 P010。
2. 不以 Android API 版本单独作硬门禁。`COLOR_FormatYUVP010` 作为编码器公开常量从 API 33
   才正式加入，但较早系统若实际列出相同标准格式并通过完整短探测，也允许使用；反之，即使
   API 较新或能力位声称支持 HDR 编辑，只要实际 P010 路径未通过就不得强行使用。
3. P010 的颜色转换必须跟随实际输出定义，而不是全部套用 HDR10+ 着色器：
   - 10-bit SDR 使用 BT.709 primaries、BT.709 传递函数与 BT.709 NCL 矩阵；
   - HDR10／HDR10+ 使用 BT.2020 primaries、PQ 与 BT.2020 NCL 矩阵；
   - HLG／杜比视界 Profile 8.4 基层使用 BT.2020 primaries、HLG 与 BT.2020 NCL 矩阵。
   三类路径均使用本次真实信号范围、色度位置和与其传递函数匹配的闭环／量化处理。
4. HDR10+ 继续属于“P010 必需”格式：其动态元数据只能在字节缓冲输入下逐帧提交，不能用
   Surface 冒充同格式后备；显式 HDR10+ 的失败行为仍遵守 D104～D107。
5. HDR10、HLG、杜比视界 Profile 8.4 与 10-bit SDR 若 P010 探测失败，或正式尝试在形成一次
   成功产物以前因 P010 输入、资源分配或编码异常失败，则退回**同一输出格式、同一 Profile、
   同一编码器族**的 10-bit Surface 输入继续尝试。该后备不是 HDR 格式降级，也不得直接改成
   SDR；后续才按既定候选规则处理真正的同格式编码失败。
6. 一旦 Surface 或 P010 任一路径已经完成编码、封装和发布边界，不再为了比较转换精度而重编
   完整视频；附加诊断同样不得推翻成功结果。设备能力诊断可以如实记录本次采用“应用 P010
   转换”还是“编码器 Surface 转换”，但不新增普通用户设置或导出完成态的必读技术字段。

应用自有 P010 需要额外的离屏渲染、GPU 回读和字节缓冲拷贝，离线导出可能更慢并使用更多内存
带宽；按照已确认的质量优先原则，只要本机真实路径稳定可用，性能差异不构成改回 Surface 首选
的理由。实现仍须使用有界缓冲和可取消的逐帧流程，不能因追求该路径造成无界内存增长。8-bit
输出继续使用现有 Surface 路径及 D9 的 8-bit 抖动，不在本决策中改成应用自有 YUV。

依据：

- Android `MediaCodec` 对 ByteBuffer 原始视频输入、编码器实际列出的色彩格式以及 10-bit／HDR
  输入必须配置适配 Profile 的说明：
  <https://developer.android.com/reference/android/media/MediaCodec>
- Android `COLOR_FormatYUVP010` 的 10-bit 4:2:0 半平面布局与公开 API 定义：
  <https://developer.android.com/reference/android/media/MediaCodecInfo.CodecCapabilities>
- Android CDD 对声明 HDR editing 的编码器应支持 P010、写入对应 HDR 元数据的要求：
  <https://source.android.com/docs/compatibility/16/android-16-cdd>

## D159（2026-07-28）10-bit SDR 闭环亮度修正使用 BT.1886 参考显示目标

D158 让 10-bit SDR 也优先使用应用自有 P010，因此它同样需要解决 BT.709 NCL Y′CbCr
在 4:2:0 色度降采样、量化和播放端重建后产生的 chroma leakage。SDR 码流标记
`COLOR_TRANSFER_SDR_VIDEO`／BT.709 传递特性，但标准观看链以 BT.1886 参考 EOTF 把
解码后的 R′G′B′转换为显示亮度；FableSol 当前 SDR 呈现着色器中 BT.709 OETF 与参考
BT.1886 EOTF 共同形成的明暗关系属于既有输出意图，不应被闭环算法误判为需要消除的误差。

10-bit SDR 应用自有 P010 默认采用以下闭环亮度修正：

1. 先取得本次所有 SDR 色调映射和最终 BT.709 OETF 已经完成后的全分辨率 BT.709
   R′G′B′；原生 SDR、稳定 HDR→SDR 与动态 HDR→SDR 都从各自已经确定的最终 SDR
   R′G′B′开始，不回到 HDR 中间值重新决定映射。
2. 按 D154 的实际色度位置和滤波器生成 BT.709 NCL 4:2:0 Cb/Cr，按 D157 完成正式
   10-bit 码值舍入，再以同一色度位置和参考上采样器重建全分辨率 Cb/Cr。
3. 原始 BT.709 R′G′B′与“候选 Y′ + 重建 Cb/Cr”得到的 R′G′B′分别通过 BT.1886
   参考 EOTF；逐像素调整 Y′，使重建后的显示线性 BT.709 亮度尽量接近原始信号经过同一
   BT.1886 后的亮度。
4. 正式导出采用与 D155～D156 相同类别的单步闭式／局部线性化求解；高精度迭代解只作为
   测试 oracle。修正只有在显示线性亮度误差确实降低、重建 RGB 合法且没有引入新轮廓时
   才能采用，之后再执行 D157 的最终 Y′量化。

闭环参考固定使用 BT.1886 的理想标准参数：显示黑位为 0、白位只作归一化尺度、幂指数为
2.4。100 尼特或其它整体白亮度尺度在相对误差求解中约去，不读取本机屏幕黑位、峰值、环境光
或显示模式，也不让导出文件绑定到生成它的设备。该修正不改变 SDR tone mapping 曲线、高光
强度、参考白、饱和度策略或跨帧状态，因此不会把稳定映射变成动态映射，也不会改变原生 SDR
模式的渲染风格。

这是所有 10-bit SDR 应用自有 P010 路径的内部默认质量处理，不增加用户选项。临时目标、
数值自检或修正条件不可用时，保留普通合法 Y′并继续同一 SDR 格式导出；不得切换模式、报整次
导出失败或事后重编视频。8-bit Surface 路径继续由厂商完成 RGB→YUV，不在本决策中接管。

依据：

- ITU-R BT.1886 对 HDTV 制作参考显示 EOTF 的定义：
  <https://www.itu.int/dms_pubrec/itu-r/rec/bt/R-REC-BT.1886-0-201103-I%21%21PDF-E.pdf>
- ITU-R BT.2390 对 SDR 系统 OOTF 由 BT.709 OETF 与 BT.1886 EOTF 共同组成的说明：
  <https://www.itu.int/dms_pub/itu-r/opb/rep/R-REP-BT.2390-11-2023-PDF-E.pdf>
- ITU-T H.Sup15 对 4:2:0 NCL 色度泄漏、量化／反量化／参考上采样闭环亮度修正，以及 SDR
  参考显示采用 BT.1886 的说明：
  <https://www.itu.int/rec/dologin_pub.asp?id=T-REC-H.Sup15-201701-I%21%21PDF-E&lang=e&type=items>

## D160（2026-07-28）明确选择 SDR 时提供自动、10-bit 与 8-bit 位深

SDR 的 10-bit 输出能更准确保留 FableSol 大面积暗部、水面和背景缓变，但 HEVC Main10、
AV1 Main10 或少见的 AVC High10 在旧播放器、系统分享目标和部分平台转码链上的兼容性不如
常见 8-bit 4:2:0。Android 也只保证默认视频编码 Profile 支持 8-bit YUV 4:2:0；10-bit
必须另行选择适配 Profile、输入格式并由具体设备实际支持。因此，位深不能继续只作为一个无法
表达用户意图的内部静默降级结果。

当用户明确选择原生 SDR、稳定 HDR→SDR 或动态 HDR→SDR 时，设置中显示“视频位深”：

- **自动（推荐）**：默认值。按质量优先顺序先尝试 10-bit；当前格式、编码器族或输入路径
  无法完成 10-bit 时，再尝试带 D9 抖动的 8-bit。该行为延续现有自动策略。
- **10-bit 画质优先**：严格请求 10-bit，只生成 10-bit 候选；不能完成时本次真实导出失败，
  告知当前编码器／Profile／输入路径的原因并提供直达设置操作，不静默改成 8-bit。
- **8-bit 兼容优先**：严格只生成 8-bit 候选，并始终启用与最终 8-bit 量化匹配的抖动；
  不在后台先试 10-bit，也不因设备能够编码 Main10 而改回 10-bit。

“视频位深”是独立于 SDR 映射方式、编码器族、码控模式、B 帧和高复杂度编码的用户意图轴。
若所选编码器族与严格位深没有任何可用组合，例如用户同时严格选择某个不提供 10-bit Profile
的编码器族，则按显式请求失败处理，不得绕开用户选择换编码器族。自动位深仍可在当前允许的
编码器候选内降到 8-bit。

HDR10、HDR10+、HLG 与杜比视界 Profile 8.4 均要求 10-bit；选择任一 HDR 格式时不显示、
不读取隐藏的 SDR 位深偏好，也不提供 8-bit HDR。HDR 格式切回明确 SDR 后恢复上次 SDR
位深选择。信息栏必须说明：

- 10-bit 更适合水体、阴影和背景的平滑渐变，但对编码 Profile 与下游分享链要求更高；
- 8-bit 使用抖动减轻色带，兼容范围通常更广，但不能等同于原生 10-bit 精度；
- 自动会优先 10-bit，并在本机无法完成时使用 8-bit。

导出完成 Dialog、通知和设备诊断继续显示**实际**位深；严格请求失败只在用户真正开始一次导出
并失败后提醒，不能在设置页能力探测完成时弹出失败。位深能力结果仍可在设置页信息栏内静态说明。

依据：

- Android `MediaCodec` 对默认编码 Profile 只保证 8-bit YUV 4:2:0，以及 10+ bit／HDR
  输入必须配置适配 Profile 的说明：
  <https://developer.android.com/reference/android/media/MediaCodec>
- Android 对 P010 10-bit 4:2:0 输入布局的定义：
  <https://developer.android.com/reference/android/media/MediaCodecInfo.CodecCapabilities#COLOR_FormatYUVP010>

## D161（2026-07-28）自动候选先保持输出规格，再以 HEVC 优先打破编码器族平局

自动导出不得为了采用某个编码器族而改变用户请求或上层候选已经确定的输出规格。候选排序先按
既有规则确定 HDR／SDR 格式、SDR 映射方式、分辨率、帧率、位深及其它可见规格；只有这些规格
完全相同、区别仅在编码器实现时，才先按 D53 尝试硬件实现、再尝试软件实现；每种实现类型
内部采用以下族顺序：

1. HEVC；
2. AV1；
3. AVC／H.264（仅用于其实际支持的规格）。

因此，在同一帧率和其它规格相同的情况下，每个硬件／软件候选组内的 10-bit 顺序为
`HEVC 10-bit → AV1 10-bit`，8-bit 顺序为
`HEVC 8-bit → AV1 8-bit → AVC 8-bit`；完整的实现类型交叉顺序由 D53 定义。D160 的
自动位深仍先穷尽同规格可用的 10-bit 候选，再进入带抖动的 8-bit 候选。若更高帧率或所选
HDR 格式只在 AV1 上可用，不得仅为了 HEVC 优先而改成较低帧率、其它 HDR 格式或 SDR。

“用户当前设备上的 AV1 编码器均为软件实现”只作为设备观察事实记录。本决策不排除软件 AV1，
也不把该设备现状泛化为所有移动设备的 AV1 能力；硬件／软件实现是否参与自动候选属于独立的
候选资格规则，不能从 HEVC 族优先顺序推导。

## D162（2026-07-28）8-bit SDR 导出使用静态蓝噪声阈值抖动

当前 8-bit Surface 导出在 `export_present.frag` 中把两次 `sin` 哈希的结果相加为三角分布，
再以 `/255` 的幅度直接加到 BT.709 编码 RGB。该处理能减轻部分色带，但它不是以 8-bit
相邻码值为上下候选的无偏阈值舍入；哈希频谱也没有蓝噪声对低频成分的约束。FableSol 的暗部、
水面和背景包含大面积缓变，D160 又允许用户严格选择 8-bit，因此应把这条路径升级为与最终
8-bit 量化明确匹配的抖动。

所有 8-bit SDR 导出统一采用以下内部策略：

1. 复用 D157 所用的确定性 64×64 蓝噪声阈值资源；以导出画布像素坐标周期采样，不在每帧
   旋转、镜像、平移或重新随机化。静态图案可避免逐帧噪声造成的闪烁、显示端时域混叠和额外
   视频编码压力。
2. 抖动位于全部 SDR 映射、色域处理和 BT.709 OETF 之后，并紧邻 8-bit RGB Surface 的最终
   写出。在目标 8-bit 编码值域中，以蓝噪声阈值在相邻上下码值之间执行局部无偏选择，而不是
   继续叠加当前三角分布噪声。
3. R′、G′、B′共用同一个阈值。这样不会为了消除亮度渐变色带而主动制造逐通道彩色噪点，
   中性色仍保持中性，也更利于后续厂商 RGB→Y′CbCr 与视频压缩。该 Surface 路径无法控制厂商
   最终的 4:2:0 色度量化，因此不虚假宣称能够对 Y′、Cb、Cr 分别完成码值级抖动。
4. 精确的 0 与 1 保持在真黑和真白码值，所有结果继续钳制在合法 RGB 编码范围内；抖动不改变
   平均亮度、色调映射、高光强度、色彩方向或用户选择的 SDR 模式。
5. 蓝噪声纹理创建、自检或采样路径不可用时，退回当前确定性的三角哈希抖动并继续同一
   8-bit SDR 格式导出；不得因此切换位深、编码器族、帧率或 SDR 模式，也不得报整次导出失败。

该策略同时适用于“自动”最终回退到 8-bit 和“8-bit 兼容优先”，不新增设置项，也不影响
10-bit SDR／HDR 的 D157 P010 码值域抖动。屏幕实时预览是否同步改用蓝噪声不属于视频导出
成片质量的必要条件，本决策不将其与导出实现绑定。

依据：

- libplacebo 将抖动视为降低输出位深时避免色带所必需的处理，并以 64×64 蓝噪声 LUT 作为
  默认高质量方法：
  <https://libplacebo.org/options/#dithering>
- libplacebo 的公开实现使用一个蓝噪声 `bias` 对颜色分量执行相邻码值阈值选择，并仅在显式
  启用 temporal 时才逐帧旋转／镜像图案：
  <https://raw.githubusercontent.com/haasn/libplacebo/master/src/shaders/dithering.c>

## D163（2026-07-28）关键帧间隔保留 0.5～10 秒滑杆并默认 2 秒

当前设置页已经提供关键帧间隔滑杆，范围为 0.5～10 秒、步长 0.5 秒、默认 2 秒，并通过
`MediaFormat.KEY_I_FRAME_INTERVAL` 传给编码器。该参数表达的是请求的**最长关键帧时间间隔**：
编码器通常依据声明帧率换算成非关键帧数量，也可以为了编码效率提前生成关键帧；它不是严格固定
GOP，不表示每个 GOP 的 B 帧数量，也不保证实际间隔精确等于滑杆值。

保留现有控件和默认值：

- 默认 2 秒，在压缩效率、播放器拖动定位、缩略图提取和常见发布链兼容性之间保持稳妥平衡；
- 用户仍可在 0.5～10 秒内显式调整，不随 HDR／SDR、帧率、编码器族、软硬件实现、码控模式或
  B 帧设置自动改变；
- 较短间隔通常会增加帧内编码开销与文件体积，但改善随机访问；较长间隔通常能提高连续动画的
  压缩效率，但拖动后可能需要从更早的关键帧开始解码。信息栏应直接说明该取舍，不把更长间隔
  描述成必然提高单帧画质；
- D161 比较 HEVC、AV1、AVC 或软硬件候选时，关键帧间隔也属于必须保持完全相同的编码规格。
  任何自动候选切换都不得静默改写用户选择；
- 继续以第一帧可独立解码为发布要求，不为连续、无镜头切换的 FableSol 动画额外请求逐场景
  关键帧，也不启用全帧内编码作为所谓高画质后备。

能力探测应使用与正式导出相同的解析后关键帧间隔，所有系统一律按 Float 下发。
（2026-07-28 实现前审查删除原"旧系统整数秒兼容"分支：`KEY_I_FRAME_INTERVAL` 自 API 25
起接受 Float，本项目 minSdk 26，该分支不可达；且其字面规则在 0.5 秒时只能得到含义为
全帧内编码的 0，与本条"不启用全帧内编码"的要求矛盾。）

依据：

- Android 对 `KEY_I_FRAME_INTERVAL` 的秒数语义、负值／零值含义及编码器通常按帧率换算的说明：
  <https://developer.android.com/reference/android/media/MediaFormat#KEY_I_FRAME_INTERVAL>
- Apple HLS 交付规范建议约每两秒提供一个 IDR，说明 2 秒是具有广泛发布兼容性的默认上限：
  <https://developer.apple.com/documentation/http-live-streaming/hls-authoring-specification-for-apple-devices/>

## D164（2026-07-28）HLG 肩部参数表以归一化容量 C_S(u)/q(u) 为查表键

实现前审查（[plan-review-2026-07-28.md](plan-review-2026-07-28.md)）发现 D131 原文
"一维参数表以 `q(u)` 查表"在数学上不成立，本决定予以修正，并已直接改写 D131 的相应
段落。

把 D131 的肩部方程按 `q(u)` 归一化（`m_n = m / q(u)`）后：起点
`K_n = K_D^(1/γ) = 2^(1/1.2) ≈ 1.7818` 与端点 `H_n = H_D^(1/γ)` 都是整段常数（`H_D`
为用户 HDR 强度），唯一随颜色方向变化的形状量是归一化容量：

```text
C_n(u) = C_S(u) / q(u)
```

归一化肩部尺度 `A_n = A / q(u)` 满足
`A_n × (1 − exp(−(H_n − K_n) / A_n)) = C_n − K_n`，即 `A_n` 只是 `C_n` 的一元函数。
因此一维参数表必须以 `C_n(u)` 为键，表项存储 `A_n`。

`q(u)` 不能作键，因为它与 `C_n` 不是一一对应。在标准信号范围内
`C_S(u) = C_match(u) = (max(v) / Y_v)^(1/6)`（`v = rec709ToBt2020(u)`，`Y_v` 为
BT.2020 亮度加权），而 `q(u) = E_ref × max(v)^(5/6) × C_match(u)`：给定 `q` 后
`max(v)` 与 `C_match` 仍可互相置换。数值反例（γ = 1.2、标准 Rec.709→BT.2020 矩阵）：

- 纯红方向 `q ≈ 0.215` 低于中性白 `q ≈ 0.265`，但 `C_n(红) ≈ 5.57` 高于
  `C_n(白) ≈ 3.77`——`q` 与容量甚至不同向。
- 红→白路径在 `q ≈ 0.234` 处 `C_n ≈ 4.48`；红→品红路径在同一 `q` 处 `C_n ≈ 5.04`。
  同一 `q` 需要两个不同的肩部尺度，一维 `q` 表必然给错其中一个：要么仍有余量的颜色被
  提前压缩，要么容量不足的颜色越界后被末端钳位，两者都违反 D129/D133 的按方向利用
  余量语义。

实现要点：

- `C_match(u)` 与 `q(u)` 在 shader 中均为便宜的闭式量，`C_n(u)` 逐像素直接求得后查表；
  求解 `A_n` 仍按 D131 在导出开始时完成，shader 只做确定性线性插值。
- `W_MAX = 1.09` 与 D140 的设备上限通过
  `C_S(u) = hlgInverseOetf(min(W_standard(u), W_device(u)))` 自然并入 `C_n(u)`，
  查表结构不变。按当前 Rec.709 源色域，`W_MAX` 上限不会实际触发（最高约 107.65%），
  仅作防御性钳制。
- D129～D134 的其余结论（共同 RGB 增益、方向相关端点、`2.0×` 共同起点、名义范围回退）
  不变；D133 的连续性与量化后台阶门禁改为覆盖 `C_n` 键的插值。

## D165（2026-07-28）W_device(u) 由方向域查表加二分求得

D140 要求把回环验证得到的 Y′、Cb、Cr 连续安全区间换算成每个颜色方向的设备上限
`W_device(u)`，但未给出算法。该换算要经过 HLG OETF、BT.2020 NCL 矩阵与量化，没有闭式
反解，现确定为方向域查表：

- 颜色方向域是 `max(u) = 1` 的三个立方体面。导出开始时在每个面上取固定网格（首版
  32×32；网格密度属实现门禁参数，按回归测试固定），网格点即单位方向 `u`。
- 对每个网格点以二分求最大可用 `W`，二分区间为 `[1.0, W_MAX]`。候选 `W` 的可行性判定
  覆盖整段 super-white 区间，不假定端点是最不利点：沿该方向按固定 `W` 步长（首版
  0.005）取 `1.0` 至候选 `W` 的全部采样，逐点经 HLG OETF、BT.2020 NCL 矩阵与 D157 的
  量化得到 Y′CbCr 码值，三个分量全部落在已验证安全区间内才算可行。固定步长使较大 `W`
  的检查点集合包含较小 `W` 的集合，可行性对 `W` 单调，二分因此成立；名义范围以内的
  信号按矩阵归一化天然落在名义码值内，无需检查。
- 全部网格求解在 CPU 上一次完成（几千次二分，毫秒级），不进入逐帧路径，也不逐像素
  迭代。
- shader 按面选择与双线性插值读取 `W_device(u)`，与 `W_standard(u)` 逐方向取 min 后按
  D164 并入 `C_n(u)` 查表键。面与面共享边上的网格点由同一方向求得同一值，插值天然
  连续；量化后的相邻方向台阶验证沿用 D133 门禁。
- 回环结论为三分量完整扩展区间均可用时，`W_device(u)` 恒为 `W_MAX`，查表退化为常数，
  不引入额外误差；无法建立任何安全区间时按 D135 使用名义范围，不构建该表。

## D166（2026-07-28）静态元数据回读核对定位在短探测阶段

D91 第 2、3 条原文要求在完整编码封装后、提交前重新打开正式产物核对静态元数据，核对失败
即丢弃候选换档重编；这与更晚的 D142（完整编码并成功封装后不得因附加结构检查推翻结果）
按字面互斥。现按 D142 收敛，并已改写 D91 的相应条目：

- 逐字段回读核对在**短探测产物**上执行。短探测与正式导出使用同一编码器与同一
  `MediaFormat` 解析结果，元数据行为几乎不会只在完整编码时才改变，而该核对在单帧文件上
  接近零成本；一次正式导出的核对失败则意味着把数十分钟的完整渲染推倒重来，正是 D142
  否定的行为。
- 正式导出的门禁保持为：configure 阶段注入、交给 muxer 的轨格式补齐、有效视频样本、
  编码与封装真实错误。
- 正式产物封装成功后仍解析一遍，结果只进诊断与完成信息，不删除、不重编码、不改报失败。
- 承载事实：`MediaMuxer` 把 `KEY_HDR_STATIC_INFO` 落盘为容器级 mdcv/clli box 是较新
  Android 才具备的行为，实现时以 AOSP `MPEG4Writer` 为准核对支持起点；旧系统上轨格式
  补齐可能不产生容器标记，实际承载是编码器按 configure 注入生成的码流 SEI。因此
  configure 阶段的 `KEY_HDR_STATIC_INFO` 注入必须始终保留，短探测核对接受"容器 box"
  与"码流 SEI"任一有效承载。

## D167（2026-07-28）CQ 下发采用同模式兼容阶梯

D145 的"不得同时下发 `KEY_QUALITY` 与 `KEY_BIT_RATE`"若绝对执行存在设备回归：OMX 路径
（ACodec）的视频编码器在 configure 阶段要求必须携带码率键，缺失直接失败——现有真机中
华为平板的 `OMX.hisi.video.encoder.hevc` 即属此类。若纯 CQ 配置失败即判该编码器不支持
CQ，这类设备会从"今天 CQ 可用"退化为静默落 VBR，方向与 D145 的画质意图相反。

CQ 的 `MediaFormat` 下发因此按同模式阶梯执行：

1. 短探测先试纯 CQ（`BITRATE_MODE_CQ` + `KEY_QUALITY`，不带 `KEY_BIT_RATE`）。
2. configure 或短探测失败时，重试 CQ + 码率提示：仍为 `BITRATE_MODE_CQ` +
   `KEY_QUALITY`，另附 `KEY_BIT_RATE`，取值为 D147 对当前解析候选的自动推导码率。这是
   **同一编码模式**的兼容形态，不是换成 VBR。
3. 两种形态都失败，才判该编码器不支持 CQ，交由既有候选与模式规则处理。

配套要求：

- 正式导出必须使用与短探测通过的同一形态，不得探测用一种、正式用另一种。
- 能力缓存签名区分两种形态；能力诊断与设备报告记录实际形态（纯 CQ 或 CQ+码率提示）。
- 用户可见语义不变：两种形态都是恒定质量，设置摘要与完成信息不因兼容形态改变措辞，
  形态细节只进诊断。

## D168（2026-07-28）CQ 模式的 Level 只按尺寸与像素率定档

D152 的最低充分 Level 需要码率分量，而 CQ 模式没有解析码率，实际码率事先未知且可能超过
按像素率选出档位的标准码率上限。两个方向的取舍：为未知码率抬 Level 或 Tier 会显著膨胀
声明的解码要求（例如把约 1400p 的文件声明成 8K 级 Level），缩小可播放设备面，违背 D152
的兼容初衷；不抬档则声明的码率上限可能被实际码流名义超出，而移动生态对 Level 码率合规的
执行很松（现状代码长期声明编码器广告的最高 Level 也未见播放问题），硬件解码的真实门槛是
像素率。

因此确定：

- CQ 模式下，Level 只按对齐后尺寸与像素率（连同 Profile、位深、B 帧/参考结构的既有
  约束）取最低充分档，Tier 保持 Main，不为未知码率抬 Level 或改用 High Tier。
- 接受实际码率可能名义超出该档标准码率上限；编码器回报更高但合法的实际 Level 时，仍按
  D152 采纳、封装并记录诊断。
- VBR 与 CBR 后备继续按 D152 使用解析后的码率参与定档。
- 剩余风险——个别编码器在 CQ 下可能为遵守声明 Level 而自限质量——由批次 7 的画质 A/B
  回归观察；确认发生时按具体编码器在诊断中记录，并允许对该编码器单独抬档，不作全局规则。

## D169（2026-07-28）HDR10+ 直方图桶对齐载荷量化网格

D112 的 nearest-rank 定义要落在直方图实现上，桶粒度必须显式固定，否则 D104 两个统计
后端的一致性测试没有判定标准：

- 逐像素 CFD 直方图在线性归一化域取均匀 100001 个桶，桶宽 `0.00001`（以 PQ 10000 尼特
  为归一化上限，即 0.1 尼特），与 DistributionMaxRGB 载荷量化网格完全对齐。计数器约
  400 KB，GLES 3.1 SSBO 与 GLES 3.0 CPU 直方图均可直接容纳。
- nearest-rank（`r = max(1, ceil(n × p / 100))`）在桶上求值：按升序桶累计计数找到第
  `r` 个样本所在桶，返回该桶对应的网格值。由此全部百分位（含 V8 的 99.98% 与 D110 的
  高光起点查询）在载荷精度上精确。
- `MaxSCL` 不经过桶：两个后端都对原始线性值单独求真实最大值（GPU 侧按位 atomic max），
  按 D103 只在写入载荷时量化。`AverageMaxRGB` 同样按 D102 由线性总和与真实像素数计算，
  不从桶重建。
- GLES 3.1 与 GLES 3.0 后端使用同一桶定义与同一求值规则；一致性测试要求两者的直方图
  计数、九项分位、99.98% 分位与高光起点查询逐项完全相等，不允许容差。
- D112 中"内部曲线查询继续保留统计后端能够提供的高精度值"按本决定落实为：内部精度即
  该载荷量化网格（0.1 尼特），对膝点与曲线拟合远超所需。

## D170（2026-07-28）AV1 应用 P010 的色度位置按序列头声明匹配

D154 的色度位置探测与相位匹配只描述了 HEVC 的 SPS/VUI。10-bit AV1 的应用 P010 路径
现实存在——HDR10+ 的 AV1 候选必须字节缓冲输入，D158 之后 10-bit SDR/HDR10 的 AV1 也
优先 P010——因此同一原则推广到 AV1：

- AV1 的色度位置声明是序列头 OBU 的 `chroma_sample_position` 字段，与 H.273 的对应为
  `CSP_COLOCATED` = Type 2（水平垂直共点）、`CSP_VERTICAL` = Type 0（水平共点、垂直
  居中）、`CSP_UNKNOWN` = 未声明。
- 短探测从实际编码器输出解析该字段（csd-0 的 av1C 携带序列头；AV1 OBU 无防竞争字节，
  解析比 HEVC SPS 简单）：声明 `CSP_COLOCATED` 时用 Type 2 相位；声明 `CSP_VERTICAL`
  时用 Type 0 相位；`CSP_UNKNOWN` 或无法解析时按 Type 0 兼容语义选相位，诊断记为
  "码流未显式声明、按 Type 0 匹配"。
- 滤波质量、边界延拓、探测或首选相位不可用时不改档不失败等约束与 D154 完全一致；
  探测结论进入能力缓存签名的方式也与 HEVC 相同。

## D171（2026-07-29）保留高光 SDR 曲线族与标定插值

D68～D72 定下了保留高光 SDR 的**约束**：暗部与中间调恒等、高亮端平滑压缩、只压缩不提亮、
强度 `1.0×` 严格恒等、强度 `9.6×` 时 HDR 参考白落到 SDR 信号 90%。它们没有给出唯一的曲线，
实现时必须补齐，因此把选定的曲线族与标定插值定案如下（`FableSolExportSdrToneMap`）。

**曲线族**（`m` 为显示线性 maxRGB，`W = F(1)`，`K` 为膝点，`P` 为控制峰值）：

```text
m ≤ K        F(m) = m
K < m ≤ 1    F(m) = 1 - (1-K)·exp(-(m-K)/(1-K))
1 < m ≤ P    F(m) = T - (T-W)·(1 - (m-1)/(P-1))^p
m > P        F(m) = T
```

- **膝点由 W 反解**：`K = 1 - e·(1-W)`。这样第二段只有一个自由度，却同时满足 `F(1) = W` 与
  `F'(1) = 1/e`；`K` 不是独立参数，也就不存在"膝点与参考白落点互相打架"的组合。
- **超白段的指数** `p = max(1, (P-1)/(1-K))`，**峰值落点** `T = W + (P-1)/(e·p)`。两个分支
  在 `1` 处斜率都恰为 `1/e`，与第二段 C¹ 相接；`p ≥ 1` 保证曲线在超白段是凹的，也就保证了
  D68 的"只压缩"。
- `P - 1 ≥ 1 - K` 时 `T = 1`，控制峰值正好用满 SDR 顶部；`P` 太靠近 `1` 时强行拉到 1.0 需要
  `p < 1`（凸曲线，等于放大高光对比度），因此改取 `p = 1` 的直线，落点 `T < 1`。`T` 与整条
  曲线都随 `P` 连续，阈值附近不产生亮度跳变。

**标定插值**：`W(强度)` 在 `1.0×` 的 `W = 1` 与 `9.6×` 的 `W = 0.8090`（BT.709 OETF 的
90% 信号反解）之间按强度**线性**插值。D72 只钉了两个端点与"顶部预留连续增加"，插值方式属
标定选择；线性是满足这两条的最简形式，其结果由 `FableSolExportSdrToneMapTest` 的固定素材
用例锁住，日后若要改成感知域插值，必须同时更新那组期望值并说明理由。

**两种映射方式共用第一、二段**：`稳定映射` 取 `P = HDR 强度`，`动态映射` 取平滑后的实测超白
峰值。因此 D71 的"`0～1.0` 基础范围全片固定"是曲线族的结构性质，不是实现时的额外约束。

**共同增益**：`F(m)/m` 同乘 RGB 三通道（D69、D76）。因为每个通道都不超过 `m`，而 `F(m) ≤ 1`，
所以任何通道都不会越界，不需要逐通道钳制，输出边界处理天然是同比例收缩。

## D172（2026-07-29）HLG 回环测试图按分量走阶梯，判定以"是否与名义端点拉开"为准

D139 把测试图描述为"平坦中性及红、绿、蓝阶梯色块"，D140 则要求由回环**分别**得出 Y′、Cb、
Cr 三条连续安全区间。两者在实现上不能同时照字面满足：一块高饱和色块失败只说明三个分量里
至少有一个越界，说不出是哪一个，而 `W_device(u)` 的推导（D165）需要三条独立的区间。

D140 已把"测试图档位、统计样本数和有损编码容差"划为实现门禁参数并授权在实现与回归测试阶段
固定，本决定据此定案（`FableSolExportHlgLoopback`）：

- **按分量走阶梯**：亮度阶梯把色度钉在中性 512、色度阶梯把亮度钉在中位 512 且另一路色度钉在
  512。每条轴从各自的名义端点（940 / 960 / 64）起，按 8 个码值向外，末级恒为视频数据范围
  端点（1019 / 4）。末级必须单列——`1019 - 940 = 79` 不是步长的整数倍，漏掉它就永远验不出
  `W_MAX` 能否用满，而那正是这条轴最有价值的一级。
- **判定不能只看误差**："重建值与申请值相差不超过容差"单独不成立：编码器把 945 钳到 940 时
  误差只有 5，落在任何合理容差内。真正的判据是 D139 写明的"名义 100% 以上的阶梯没有全部
  塌缩到同一个端点"，因此每一级还要与**名义端点的重建值**拉开与其名义差成比例的距离
  （系数 0.5）。
- **容差必须小于阶梯步长**（当前 6 < 8）。否则编码器把某一级钳住时，紧邻的下一级只差一个
  步长、误差仍落在容差内而蒙混过关，安全上限会比真实上限高出整整一级——写出去的码值随后被
  编码器钳掉，而肩部还以为那段余量可用。
- **任一条轴的名义端点自身重建不正确时整份测量作废**，按 D135 使用名义范围：其余读数没有
  可信的比较基准。
- **色彩标记缺失与冲突同样判为未通过**。D139 要求像素回环与标记检查同时通过；容器没有声明
  BT.2020/HLG/limited range 时，"扩展码值被保留"这件事没有可解释的载体。这与 D166 允许
  静态元数据"容器完全没有携带描述符也不判失败"不同：那一条的实际承载者是码流 SEI，而信号
  范围没有第二个承载处。

2026-07-29 实测：OPPO PLZ110 的 `c2.qti.hevc.encoder` / `c2.qti.hevc.decoder` 通路上三个分量
的完整视频数据范围（`1019,4,1019,4,1019`）全部保留；同机 `c2.qti.dv.encoder` 通路的阶梯塌缩，
按本决定判为无法验证，杜比视界 8.4 因此导出名义范围基层——正是 D143 第 3 条的预期行为。

**2026-07-30 修订（评审 P14）**：末级的容差改为 `min(6, 与前一级档距 − 1)`——色度高端 2、
低端 3、亮度 6（不变）。原文的"容差必须小于阶梯步长"只在整数倍档位成立；末级单列后与前
一级只差 3～4 个码值（1019−1016、8−4），沿用整体容差 6 会让"编码器恰在倒数第二级钳制"的
情况把末级也蒙混过关，安全上限高报 3～4 个色度码值，随后写出的码值被编码器钳掉。收紧后
误拒的代价只是区间止步于前一级（保守收缩）；回环统计取平场内部区域的中位值，2 码值容差
的误拒概率极低。`FableSolExportHlgLoopback.CONTRACT_VERSION` 随判定门禁变化升级到 2，
既有回环缓存整体失效并按新规则重测一次。

## D173（2026-07-29）导出色彩模式的胶囊顺序 HDR 在前，B 帧开关用动词短语

用户看过真机设置截图后提出的两处，均已改并定案。

**一、色彩模式的胶囊顺序改为 HDR 在前。**

此前是 `SDR（原生渲染） → SDR（保留高光层次） → 自动 → HDR10+ → 杜比视界 8.4 → HDR10 → HLG`。
默认值是「自动」（HDR），把两个 SDR 摆在最前面等于让用户先读完两个降级选项才看到默认值，
也与 2026-07-27 定下的"能支持多高规格就支持多高规格"取向相反。新顺序：

```text
自动 → HDR10+ → 杜比视界 8.4 → HDR10 → HLG → SDR（原生渲染） → SDR（保留高光层次）
```

其中具体 HDR 格式按 `AUTO_ORDER` 排列，与自动档的候选顺序一致；本机编不出来的格式本来就
不进这一组，因此顺序不受设备影响。持久化用稳定标识（批次 1 已改），改顺序不影响已保存的
选择；但**选中项一律按值查下标，不得写死数字**——原先"没有可用 HDR 格式时回退到下标 0"
在新顺序下会指向另一个模式。

**二、B 帧开关的标签改为动词短语。**

原标签是「B 帧」——一个裸名词，读起来像分组标题而不是开关。同组的「高复杂度编码」「复杂帧
质量保护」在勾选框语境下都读得通，唯独这一条不通。改为「启用 B 帧编码」，13 套语言同步。

**连带修正一处与 D126 冲突的文案。** 截图核对时发现 HLG 的格式说明写着"参考白以上的标称
高光余量约为 3.77 倍，因此 HDR 强度超过约 4 后可见层次增量有限"——而 D126 明确写了
`1/E_ref ≈ 3.77` 是 HLG **场景线性**域里的比值，**不能**当作显示线性工作空间的高光阈值。
按 D126、D129、D133、D134 改为：中性高光在参考显示条件下可用到约 `4.92` 倍参考白，验证通过
扩展信号范围后高饱和颜色可用到约 `5.50` 倍，超出部分由整段固定的肩部平滑收纳而非裁切。
这条文案是批次 6 之前写的，当时那套数还没有定下来。

## D174（2026-07-29）每个选项的说明贴在它自己下面，信息栏只留推导结论

用户看过设置截图后指出：HLG 信号范围、B 帧、复杂帧质量保护、高复杂度编码这些选项，下面
没有任何说明；解释统一堆在最底部那一段信息栏里。改的是这一行、要读的却在屏幕外几屏之下，
等于没写。

**新的信息架构**：

- **每一个选项都有一行紧跟其下的说明**（11sp、62% 透明度，与「导出色彩模式」原有的那段说明
  同款式）。已覆盖：编码模式/目标码率、复杂帧质量保护、高复杂度编码、启用 B 帧编码、
  关键帧间隔、色调映射方式、视频位深、HLG（基层）信号范围、漫反射白、参考显示峰值。
- **底部信息栏只留这一组设置的推导结论**：体积估算、实际帧率、实际格式与编码器、漫反射白
  与峰值、参考显示峰值、高光起点，随后是推导过程与设备能力报告。它回答"我得到了什么"，
  不再兼职回答"每个开关是什么意思"。
- **说明常显，不随开关显隐。** 先前的写法是"打开才显示"，那恰好在用户想弄清"要不要打开"
  的那一刻把话收走。B 帧的说明另按实际落点追加一句适用性（AV1 与 H.264 Baseline 不适用）。
- 「色调映射方式」与「视频位深」的说明从「导出色彩模式」那段里搬出来，挂到它们各自那一行。
  它们原本拼在色彩模式说明的末尾，而那段在屏幕上位于这两行**上方**。
- 唯一留在色彩模式说明里的例外是 D137 的那一句"自动档最终落到 HLG 系时会尝试扩展信号
  范围"：它讲的是**自动档的行为**，而自动档下信号范围那一行根本不显示，没有别处可挂。

**补上一条一直缺失的说明**：关键帧间隔此前在任何地方都没有解释（plan.md 批次 7 把它列进了
"信息栏要解释"的清单，但字符串从来没写过），滑杆上只有一个数字。新增
`fablesol_export_desc_keyframe`，13 套语言齐备。

## D175（2026-07-29）参考值胶囊写明它测的是什么，高光起点补上说明

用户看过设置截图后提出的两处，均已改。

**一、「本机（2000）」改为「本机屏幕峰值（2000 尼特）」。**

原文案只有一个裸数字，既没有单位、也没说这个数测的是什么。它的来源是
`Display.HdrCapabilities.desiredMaxLuminance`，即**本机显示设备声明的峰值内容亮度**；
同一行的其它胶囊（400/600/1000/2000/4000）是不带单位的参考值，唯独这一条来自设备，
不写清楚就无从判断它与旁边那些数是不是同一回事。该行本来就会换行（D94 补记），加长不挤。

语义不变：仍是 D94 的**一次性取值**，点一下把当前声明值填进滑杆并保存为数字，不建立持续
跟随关系；面板声明值不等于实际播放亮度这一点由下方说明行承担。

**二、高光起点补上说明行。**

这个滑杆此前在任何地方都没有解释，只显示一个百分数。新增
`fablesol_export_desc_highlight_start`（13 套语言）：它是逐帧实测亮度分布上的一个分位，
低于它的内容保持线性映射、高于它的进入受控滚降；调低让更多画面参与压缩、最亮处层次更细，
调高保留更多线性区但其上的高光压缩更陡；**只改变 HDR10+ 动态元数据，不改变 PQ 像素本身**。
该说明只在 HDR10+ 下出现——只有它带逐帧色调映射曲线（D43）。

## D176（2026-07-29）HDR10+ 曲线横轴退回母版峰值恒定，逐帧统计不再充当归一化基准

用户报告本轮改动后 HDR10+ 产物亮度频繁往复变化甚至突变。逐帧解析样片 01_HDR10Plus 的 853
帧 ST 2094-40 元数据后定位：像素层完全稳定（与同设置的 02_HDR10 同帧 YAVG 差 ≤0.08/1023），
亮度变化全部来自动态元数据。本决策修订 D113，并相应修订 D111 的 Case 3 公式与 D115 的
可行性判据；D110/D112 的统计定义、D114 的量化后门禁、D117～D121 的肩部拟合机制与 D122 的
时间稳定机制全部保留。

### 根因：中性曲线在恒定横轴的接收端上是逐帧全局增益

参考显示峰值设为 2000 尼特，高于全片 V8 峰值 1928 尼特，853 帧全部落在 D111 的
`Kx = 1、Ky = V8/T`。V8（99.98% 分位）随星芒在 201～1928 尼特之间变化，`Ky` 随之在
0.100～0.964 之间移动，单帧最大步进 0.043，0.5 秒内可从 0.11 升到 0.59。

`F(s) = Ky·s` 只有在接收端**按本帧 V8 归一化横轴**时才是恒等映射——那正是 D113 选择 V8
优先时所设的前提。用户的播放链路按恒定基准（母版峰值）归一化，也就是 D38 的原始语义、
libplacebo 一系的行为；在那种接收端上 `Ky` 就是整幅画面的乘数，背景约 150 尼特被在约
15～148 尼特之间来回改写。压缩路径同样带病：`fit()` 的 `kneeX = knee/V8` 以逐帧量作分母，
恒定横轴接收端上膝下斜率变成随帧变化的 `S/M`；本样片全片中性没触发，但缺陷是同一个。

**一般化的教训：凡是接收端可能解释为全局增益的量，都不允许跟着逐帧统计走。** 载荷里的
`Kx`、`Ky` 描述的是一个函数，而函数的自变量基准由接收端决定；发送端换基准而接收端不知道，
产物就整体跑偏，且这种偏差在导出端完全看不出来——单测覆盖不了"接收端如何解释载荷"。

### 决定：横轴按声明母版峰值归一化，全片常量

设 `M` = 写进 ST 2086 MDCV 的声明母版峰值，`T` = 用户选择的参考显示峰值，两者都是全片常量。
曲线横轴 `s = 线性亮度 / M`，纵轴仍按 `T`；`s = 1` 逐帧不变。

`M` 必须与 MDCV 写入值**同源**，为此从 `FableSolExportTransfer.hdr10StaticInfo` 里抽出
`masteringPeakNits(peakNits, diffuseWhiteNits, luminance)`，元数据与曲线共用。两处各算各的，
就等于"元数据声明的母版"与"曲线假设的母版"不是同一件事。

- **`M ≤ T`（配置级判定，不逐帧）**：全片写中性曲线 `Kx = 1、Ky = M/T`。此时
  `F(s)·T = s·M` 是精确恒等，且 `Kx`、`Ky` 与 anchors 全片逐位恒定——接收端无论按哪种基准
  归一化都读不出随帧变化的全局增益。anchors 仍写单调占位值，`tone_mapping_flag` 恒为 1
  （D111 不变）。
- **`M > T`**：逐帧压缩曲线。膝点以下恒等要求 `Ky/Kx = M/T`，即 `kneeX = k/M、kneeY = k/T`；
  斜率连续的第一控制点因此是 `P1 = (M−k)/(10(T−k))`，`P1 ≤ 1` 给出膝点可行上限
  `k ≤ (10T−M)/9`。上限只由两个全片常量决定，不再逐帧解一次。
- 膝点的钳制作用在**绝对膝点**上，不是分别夹 `Kx` 与 `Ky`：后者会让两个分量落到不同比例上，
  `Ky/Kx = M/T` 当场断掉，膝点以下不再是 identity。

### D115 的十倍判据改为配置级

`M > 10T` 时不存在非负膝点，判据里只有 `M` 与 `T` 两个全片常量，因此这是**配置级**结论：
在配置编码器之前就判掉，不再逐帧 throw。`FableSolExportHdr10PlusCurve.unsupportedReason`
是唯一判据，构造函数与设置页信息栏共用它。D116 的提示随之从风险预告改为确定结论，并给出
母版峰值与最低可行参考峰值两个数（`fablesol_export_reference_peak_infeasible`，13 套语言）；
仍不提前禁用 HDR10+、不自动抬高参考峰值、不把设置页状态标为导出失败。

判据比"十倍"稍严一点点：膝点还必须落在 12 位 KneePoint 的**第一个非零档**上，即
`(10T−M)/9 ≥ M/4095`，解出 `T ≥ M(1 + 9/4095)/10`（比朴素的 `M/10` 高约 0.22%）。
`M = 10T` 恰好落在被这一档挡掉的位置：`k = 0` 在连续域里算解，但它没有线性段，`P1` 的解析式
退化成 `0/0`，整条曲线变成 NaN——而 **NaN 通不过任何比较，于是量化后门禁的每一条判定都会
"通过"**，最后写出去的是一条把全部亮度映射到零的曲线，画面全黑。`fit()` 因此另加一道
`isFinite` 拦截。这类"用比较做的门禁遇上 NaN 会全部放行"的缺陷值得单独记一笔。

D115 的其余结论不变：`T < M ≤ 10T` 且用户分位所得膝点高于可行上限时，保持 `T` 不变、只把
膝点下移到最高可行值，并如实记录请求值与采用值。

### 帧统计的职责收窄

V8、高光起点分位与 CFD 直方图**不再充当横轴归一化基准**，只剩两件事：

1. 选出本帧膝点。帧峰值本来就装得进 `T` 时膝点放到帧峰值，这一帧的内容因此完全不压——这是
   D38 起就有的规则，本决策把它从"整条曲线中性"缩回"这一帧不压"。
2. 给肩部拟合提供内容密度。拟合中 `s` 的分母从逐帧峰值换成 `M`，样本仍只取到帧峰值对应的
   PQ 位置。

D113 的"V8 优先、MaxSCL 回退"在这两处继续有效，只是它决定的不再是横轴。

### 时间平滑不允许有旁路

D122 的固定 PQ 网格平滑机制保留，但**任何写入载荷的逐帧量都必须经过它**，中性/不压帧作为
identity 目标参与平滑，不得 reset；平滑状态只在 D125 规定的时间边界重置。旧实现在 Case 3
分支逐帧 `resetSmoothingTo(null)`，0.08/0.80s 的快压慢放从未生效。据此补上三处：

- **膝点**逐帧在 PQ 域快压慢放。它决定"identity 到哪里为止"，一帧之内从高光起点分位跳到
  帧峰值（该帧突然装得进 `T`）会让这个边界附近的亮度突变。下移是更强压缩，走 `0.08s`。
- **肩部拟合的覆盖上界**同样平滑。"样本取到哪里"本身能改变 anchors：帧峰值从 200 跳到 1900
  尼特时，肩部从"几乎没有样本"一步变成"整段被内容拟合"，实测单个 anchor 一帧跳 197/1023。
  上行代表高光涌上来，走 `0.08s`；下行走 `0.80s`。
- **去掉按样本数量切换的硬开关。** 旧写法"样本少于 10 个就整体退回解析形状"是一道台阶，
  样本数跨过阈值的那一帧 anchors 一步跳 184/1023。正则（`RIDGE`）本就承担这件事：它让法
  方程恒为正定，零样本时解精确等于解析形状，样本渐多时连续过渡到拟合结果。

### 完成信息如实说明恒等

`M ≤ T` 时 HDR10+ 与同参数 HDR10 在画面上没有任何差别（真恒等）。完成信息因此新增一行说明
这一点，并**不显示高光起点**——那个数在恒等配置下不产生任何压缩，写出来等于给了一个不影响
产物的参数。判据读的是本次真的用了的那条曲线（`identityMapping`），不是照 `M/T` 再推一遍。
沿用批次 5/6 的教训：不得把"通路跑通了"写成画质提升。

### 回归测试

单测无法覆盖"接收端如何解释载荷"，但可以**把接收端建模进测试**。
`FableSolExportHdr10PlusTest` 新增两条，按 `out(L) = F(L/M)·T` 对恒定横轴接收端求值，输入是
背景恒定 150 尼特、V8 在 200 ↔ 1900 尼特之间反复跳变的合成序列：

- **恒定横轴接收端**：`M ≤ T` 时 `out(150)` 全片逐位相同且等于 150 尼特；`M > T` 时膝点以下
  严格 identity，膝点以上单帧相对变化 ≤ 12%（`τ = 0.08s` 在 120fps 下单帧走 9.9%，加量化
  台阶），且平滑收敛之后整段摆幅 ≤ 1.05（实测 1.008）。
- **载荷稳定性**：`M ≤ T` 时每帧量化后的 `(Kx, Ky, anchors)` 逐位相同，且
  `Kx = 4095、Ky = round(M/T × 4095)`；`M > T` 时相邻帧膝点步进 ≤ 32/4095（回归时 `Ky`
  单帧最大步进 176），单个 anchor 步进 ≤ 112/1023。

开头那一段允许有较大的单向变化——首批帧里根本没有高光，肩部本就该跟着内容收紧，那是动态
元数据的用途，不是回归。判据因此落在**收敛之后的摆幅**上，而不是整段的最大最小之比。

## D177（2026-07-29）HDR10+ 连续动画按一个场景统计，曲线横轴恢复场景 V8 语义

用户在已包含 D176 的 OPPO 样片上仍观察到：星芒出现时水体被整体压暗，星芒消失后水体又变亮；
203 尼特和 350 尼特漫反射白都存在可感知的亮度不稳定。

逐帧解析 203 尼特样片后得到：

- 853 帧的显式曲线完全相同：`Kx = 1`、`Ky = 1949/2000`，9 个 anchors 也逐位相同。因此
  这份样片的呼吸不可能再由 D176 所修的曲线横轴跳变造成。
- 描述性统计仍剧烈变化：`FractionBrightPixels` 在约 `0.001～0.828` 间跳变，单帧最大下降
  约 `0.479`；V8 在约 `201～1929` 尼特之间移动。星芒越亮，FBP 越小，两者相关性约 `-0.898`。
- ST 2094-40 允许接收端单独或组合使用 MaxSCL、AverageMaxRGB、DistributionMaxRGB 和 FBP，
  并明确允许据此整体变暗或变亮。曲线恒定并不能阻止 OPPO 播放端使用仍在逐帧跳变的场景统计。

根因是**时间区间定义错误**。ST 2094-40 §4.8 把 scene 定义为一个时间区间内所有图像的选中
像素；§8.3～§8.6 的 MaxSCL、AverageMaxRGB、CFD 与 FBP 都是场景量。现实现却把 120 fps 的
每一帧都定义成新场景。FableSol 离线导出没有镜头切换，水体、银丝和星芒是同一连续动画中的
状态变化，不应触发新的 HDR10+ 场景。

据此采用以下发送端契约：

1. **整段连续动画是一个场景。** 正式编码前跑一次确定性离线预分析，逐帧读取最终线性
   BT.2020 合成，但只把它们作为场景累计的原始样本。
2. **场景统计按规范跨全部帧累计。**
   - MaxSCL 取场景内各通道最大值；
   - AverageMaxRGB 对场景内全部像素、全部帧求平均；
   - DistributionMaxRGB 从场景内全部 maxRGB 样本的 CFD 求出；
   - FBP 从平均亮度最高的 5:1 代理帧计算，平均并列时取帧号更大的帧；任一代理帧缺失时
     无法证明选中了场景最亮帧，整场景按规范写 0（未计算），不从剩余帧猜测；
   - 静态 MaxCLL 取场景最大 maxRGB，MaxFALL 仍取逐帧平均 maxRGB 的最大值。
3. **场景计数使用 64 位。** 单帧 GPU 直方图仍可用 32 位计数；跨帧累计必须转入
   `LongArray`，否则约十秒以上的高频桶就可能超过 `Int.MAX_VALUE`。
4. **曲线只求解一次。** KneePoint、anchors 和完整 HDR10+ 载荷在编码前生成；正式编码循环
   每帧重复同一份载荷，不再调用逐帧统计或曲线求解。高光压缩可以存在，但不能随星芒出现与
   消失带动水体亮度。

### D176 的修订

D176 把横轴改成 ST 2086 MDCV 母版峰值，是在错误的逐帧场景模型上抑制跳变的补偿，并非
ST 2094-40 的正确坐标定义。§8.7.4 明确规定归一化输入 `s = 1` 可以对应场景最后一个
DistributionMaxRGB 分位，或非零 `max(MaxSCL)`；MDCV 母版峰值不在这两个选项中。

本决策因此**取代 D176 的母版峰值横轴**，恢复 D113：

- `S` 优先取整个场景的 V8（99.98%），失效时回退场景 `max(MaxSCL)`；
- `Kx = k/S`、`Ky = k/T`，膝点以下满足 `Ky/Kx = S/T`；
- `S ≤ T` 时全场景使用 Case 3 中性曲线 `Kx = 1、Ky = S/T`；
- `S > T` 时按场景 CFD 生成一次压缩曲线；
- D115 的十倍可行性判据继续成立，但输入是预分析后的场景 `S`，不是设置页可见的理论母版峰值。

设置页在预分析前不知道场景 V8，因此不得再把“漫反射白 × HDR 强度超过参考峰值十倍”显示为
确定失败。该理论值只是场景峰值上界；实际场景 V8 可能远低于它。当前删除这条确定性提示，
真实无解只在场景预分析完成后按实际 `S/T` 判定。

D122、D125 的逐帧平滑状态不再进入当前发送端。它们是为错误的逐帧场景模型设计的补偿；未来
若引入真正的镜头切换或多场景时间线，应按场景边界重新决定过渡策略，不能直接恢复逐帧平滑。

## D178（2026-07-29）HDR10+ 场景文案采用正式书面语，运行时失败必须本地化

D177 新增或改写的用户文案统一遵循以下契约：

1. 参数说明、格式说明、完成结果和失败提示均使用客观、简练的正式书面语，不使用口语化比喻、
   第二人称祈使句或营销式引导。
2. 恒等映射完成信息只说明三个事实：场景源峰值未超过参考显示峰值、显示结果与同参数 HDR10
   一致、产物仍包含 ST 2094-40 动态元数据；是否执行高光压缩使用条件式表述。
3. `S/T` 不可行只能在完整场景预分析后判定。曲线层保留英文诊断信息供测试与开发排查，
   导出器必须在进入曲线构建前将该结论转换为当前语言的资源字符串，内部英文不得直接进入失败
   对话框。
4. 上述四类文案在全部 13 套语言中保持相同的信息层级、参数占位符和技术含义。

## D179（2026-07-29）设置页帧率是严格输出规格

用户裁定：设置页的 60 fps／120 fps 表示必须交付的输出帧率，不再表示“最高帧率”。

- 格式、编码器族与帧率组成同一个精确可行组合；三条轴的选中态、置灰状态和派生结论必须基于
  同一组合，不得从其它帧率借用能力结论。
- 选择 120 fps 后，“自动”格式与“自动”编码器只能解析 120 fps 下成立的落点；仅在 60 fps
  成立的 HDR10、AV1 10-bit 等结果不得出现在当前结论中。
- 120 fps 仅在当前格式与编码器约束下存在至少一个精确 120 fps 组合时可选；每个格式与编码器
  选项也仅在当前帧率下存在完整组合时可选。三条轴必须对称联动。
- “自动”的派生规格必须从上述同一组精确组合中选择。若 120 fps 下只有 SDR 可行，格式自动
  必须解析为 SDR，全部 HDR 选项置灰。
- 当前约束下没有完整组合时，禁用导出并显示正式原因，不得自动改用 60 fps。

### 运行时帧率同样严格

用户进一步裁定：正式导出不得在 120 fps 候选失败后自动降为 60 fps。

- 导出器应先穷尽仍满足所选帧率及当前已解析公开规格的内部候选。自动模式只负责导出开始前的
  初始解析；正式导出开始后，不得因为当前编码器族失败而静默切换到另一编码器族。
- 提议从 AV1 改为 HEVC 等编码器族变化时，必须先在 Dialog 中说明失败规格、正式分类的失败
  原因及拟采用的新规格，并等待用户明确确认；确认前不得开始新规格的导出。
- 该确认规则覆盖输出格式、编码器族、位深及硬件／软件编码类型：其中任一项变化均不得自动
  重试，只能显示建议规格并等待用户确认。
- 帧率不进入建议降级流程。120 fps 下全部合法候选失败时，本次导出直接失败，不得建议或自动
  改为 60 fps；用户如需改变帧率，应返回设置页重新选择。
- 建议规格只能改变用户原先设为“自动”的轴。显式选择的输出格式、编码器族或位深均为严格
  约束，运行时失败后不得提出改变这些轴的建议；本次导出应在符合显式约束的候选耗尽后失败。
- 当部分轴为显式选择、部分轴为自动时，建议规格必须保留全部显式选择，只能调整自动轴。例如
  显式 HDR10 配合自动编码器时，可以建议更换编码器族；显式 AV1 则不得建议改为 HEVC。
- 所选帧率下全部候选失败时，本次导出失败并说明原因，不得发布帧率与设置不一致的产物。
- 提议切换到其它合法规格重新尝试时，导出 Dialog 必须说明上一规格失败的原因及拟采用的新
  规格。该说明使用专业、简练、正式的书面语，不得使用口语化表述。

### Dialog 公开规格与内部实现边界

用户进一步裁定，Dialog 中可核对的公开规格由以下五项组成：

1. 输出格式；
2. 编码器族；
3. 位深；
4. 硬件或软件编码类型；
5. 帧率。

上述任一项发生变化均属于规格变化。具体 `codecName` 以及应用 P010／Surface 输入通路属于
内部实现，不作为新的输出规格。内部候选切换不得掩盖公开规格变化；尤其从硬件编码切换到软件
编码时，必须按规格变化处理，不得仅作为同族编码器的内部替换。

五项公开规格完全一致时，允许自动尝试其它具体 `codecName` 或 P010／Surface 输入通路；
该权限不扩展到任何公开规格变化。

同规格内部重试按用户是否已经感知到有效处理进度区分：

- 进度仍为 0、尚未产生可见处理进度时，可静默尝试下一内部候选；
- 已产生可见进度并需要重新处理时，可自动继续，但进度 Dialog 必须显示正在以相同输出规格
  重试，并列出完整公开规格及正式分类的失败原因；
- 由于公开规格未变，上述两类内部重试均不要求用户确认。

重试原因采用稳定、可国际化的用户级分类，不直接显示底层异常、厂商错误码或内部实现名称。
原因说明不得使用缺少指代对象的“该规格”等笼统表述；必须同时给出失败尝试的完整公开规格，
再说明编码器初始化、编码中断、场景统计或 HDR 渲染通路等具体失败阶段。无法进一步分类时，
仍须列明完整失败规格，并使用统一的通用编码路径失败说明。

公开规格变化时，进度 Dialog 转入等待确认状态，不开始建议规格。正文采用固定三段结构：

1. 失败规格：完整列出五项公开规格；
2. 失败原因：使用正式分类原因；
3. 建议规格：完整列出拟采用的五项公开规格。

该状态仅提供“使用建议规格重试”和“结束导出”两个操作，不再显示“在后台运行”。用户选择
前者后才开始建议规格；选择后者则终止当前导出任务。

等待规格确认时，点击 Dialog 外部区域不得关闭；系统返回键等同于“结束导出”。不得通过关闭
Dialog 将未决确认任务留在后台无限等待。

若用户此前已选择“在后台运行”，公开规格变化发生时不得依赖系统从后台弹出 Dialog。前台服务
必须直接把现有导出通知更新为可操作的规格确认通知，使用户能够在通知中确认建议规格或结束
导出。

点击规格确认通知正文直接视为采用建议规格，由前台服务继续当前任务；不得为了确认而恢复原
导出界面或构造 Activity 导航链。通知正文与“使用建议规格”操作必须进入同一个按任务标识幂等
处理的确认命令，重复点击不得启动多个重试。应用仍在前台且 Dialog 可见时，Dialog 与通知读取
同一份等待确认状态，不得分别维护结论。

通知中继续保留“使用建议规格”操作，使确认入口明确可见；点击通知正文作为执行同一命令的
快捷方式。“结束导出”操作同时保留。

后台等待规格确认不设置自动超时。等待期间必须释放编码器、EGL、临时输出、唤醒锁及其它导出
资源，仅保留任务输入、建议规格和通知状态；通知保持为不可滑除的待确认通知，直到用户选择。
不得因超时自动采用建议规格或静默结束。若进程或服务被系统终止，本次任务按失败结束，不自动
恢复或修改规格。

用户确认建议规格后若该规格再次失败，后续每一次公开规格变化均须重新请求确认；首次确认不
构成对整个降级链的授权。Dialog 与通知只显示最近一次失败规格、对应原因和下一建议规格，并
以“已尝试 N 个输出规格”等简短计数说明进度。完整规格尝试顺序、内部候选和底层异常进入设备
诊断，不在主界面累积长列表。

用户确认建议规格后，最近一次失败说明不得在新尝试开始时立即清除：

- 重试进行期间，Dialog 与通知持续显示最近一次失败规格、失败原因和当前规格；
- 成功完成时，完成 Dialog 显示实际产物规格、已尝试规格数量及最近一次规格调整原因；
- 最终失败时，显示最后失败规格及对应原因；
- 完整历史仍只进入设备诊断。

多个建议规格同时可用时，沿用确定性的画质优先顺序：

1. 帧率固定，全部显式轴保持不变；
2. 优先保持输出格式和位深，先考虑其它可用硬件编码器族；
3. 同格式硬件候选耗尽后，再建议同格式软件编码；
4. 同格式候选全部耗尽后，才进入自动格式的下一格式；
5. SDR 位深为自动时，10-bit 候选耗尽后才建议 8-bit。

设置页初始解析、能力矩阵与运行时建议必须共用该顺序，不得分别维护不同优先级。

## D180（2026-07-30）HDR10+ 保持完整场景统计，不恢复母版峰值横轴

在 OPPO PLZ110 上使用同一段 7 秒 FableSol 动画，分别以 203 尼特和 350 尼特漫反射白重新
导出并逐帧验证后，D177 的场景级实现达到预期：

- 旧样片 853 帧包含 844 份不同的 HDR10+ 载荷；D177 的两份新样片均为 853 帧、1 份唯一
  载荷，完整场景的 MaxSCL、AverageMaxRGB、CFD、FBP 和曲线不再随星芒逐帧变化。
- 203 尼特样片的水体核心亮度帧间变化第 95 分位为 0.31%，最大值为 1.09%；350 尼特样片
  最大值为 1.03%。高光活跃帧的水体核心亮度分别比低高光帧高 2.56% 和 2.91%，没有出现
  “星芒出现时压暗水体”的反向关系。
- 203 尼特与 350 尼特样片的平均线性亮度比例为 1.722，接近漫反射白比例 350/203；水体色相
  与饱和度连续，高光未发生裁切。
- 同帧手机截图未显示旧样片与 D177 样片的 PQ 基础像素差异，这与“动态元数据不修改基础像素”
  的设计一致。截图只能证明应用像素与播放器解码结果，不能单独证明面板内部 HDR10+ 映射行为；
  因此同时以码流元数据唯一性和逐帧亮度统计作为验收依据。

据此确认 D176 的母版峰值横轴修复没有保留必要。当前继续采用 D177 的规范路径：曲线横轴优先
使用完整场景 V8，失效时回退完整场景 MaxSCL；整段动画只生成并重复一份场景载荷。不得恢复
逐帧场景统计、逐帧曲线或 MDCV 母版峰值横轴。

## D181（2026-07-30）AOSP 软件 AV1 的 HDR 最高恒定质量档增加量化上限

OPPO PLZ110 的 `c2.android.av1.encoder` 在 1152×1472、60 fps、10-bit HDR10 恒定质量
最高档下可完成编码，但约 580 kbps 的产物在橙色水体渐变中出现可见块状量化。该问题不是
HDR 映射或帧间亮度不稳定：

- 产物为 AV1 Main 10、BT.2020/PQ、limited range，427 帧均可解码；
- 水体亮度时序稳定，高光活跃帧没有压暗；
- 改用目标码率 VBR 后，码率约 991 kbps，但整体 PSNR/SSIM 略低，局部量化问题也未稳定
  消失。因此不得通过改变默认编码模式或静默改用 HEVC 解决。

AOSP 把公开质量上限 100 映射为 libaom CQ 15。该编码器同时声明支持 QP bounds，因此新增
以下严格限定的质量保护：

1. 仅匹配组件名 `c2.android.av1.encoder`；
2. 仅匹配软件 AV1、HDR、恒定质量、用户采用编码器公开质量上限的情况；
3. 编码器必须声明支持 QP bounds；
4. 额外下发 `KEY_VIDEO_QP_MAX = 8`；其它编码器、SDR、非最高质量档和目标码率模式均不受
   影响。

实机结果中编码器报告的 I/P/B 最大 QP 为 8/11/14，视频码率为 1.151 Mbps；同一分析脚本
生成的同帧接触表中，原样片 3 秒附近的橙色水体块状量化已消失。相对同一 HEVC 参考，整体
PSNR 从 45.3877 dB 提升至 45.4090 dB，SSIM 从 0.995019 提升至 0.995076；修复后仍为
AV1 Main 10、60 fps、427 帧，完整解码，且水体帧间最大变化从 1.31% 降至 1.09%。

该保护不改变用户公开规格，也不进入运行时规格重试流程。若未来同名编码器不再声明 QP bounds，
或用户选择非最高质量档，应保持原编码行为。

## D182（2026-07-30）设置页码率模式统一表述为“目标码率”

设置页第二种编码模式实际请求 VBR，并按解析后的画布、帧率、编码器族、位深和 HDR/SDR 信号
计算目标码率；只有具体编码器不支持 VBR 时才在内部使用 CBR，并在完成信息中明确标注设备后备。

因此设置页不得将该模式显示为“恒定码率”。默认英文统一为 `Target bitrate`，简体中文统一为
“目标码率”，其余 11 套语言使用等义的正式术语。`fablesol_export_rate_control_cbr` 仍保留
“恒定码率（设备后备）”，仅描述实际发生的内部 CBR 后备结果。

## D183（2026-07-30）编码模式纳入完整规格能力矩阵并保持严格

用户选择“恒定质量”后，导出完成信息显示 VBR。完成信息没有标错；实际编码确实使用了 VBR。
根因由两条相互叠加的错误构成：

1. 设置页通过 `settingsQualityRange()` 独立扫描设备上的 HEVC、AV1 与 H.264 编码器，只以
   本项目画布和 60 fps 判断是否存在任意 CQ 路径，没有携带当前 HDR 格式、编码器族、位深及
   用户选择的 120 fps。因此 AV1 60 fps 的 CQ 能力可以错误地启用 HDR10+／HEVC／120 fps
   组合下的“恒定质量”。
2. 候选层把“支持用户所选编码模式”仅作为排序条件。精确档位没有 CQ 质量区间时，
   `FableSolExportRateControlForm.resolve()` 会继续把该档位解析为 VBR，造成用户意图与实际
   码流不一致。

修正后的约束如下：

- 能力矩阵由“格式 × 编码器族 × 帧率 × 位深”扩展为“格式 × 编码器族 × 帧率 × 位深 ×
  编码模式”。CQ 与目标码率分别使用正式导出相同的 `MediaFormat` 完成短编码验证，并同时
  缓存在一份矩阵中；切换编码模式不复用另一模式的结论，也不重新建立整份设备缓存。
- CQ 候选必须由同一精确档位公开有效的 `qualityRange`；没有该区间的档位直接排除。目标码率
  候选必须支持 VBR 或 CBR。形态解析只接受已满足该前置条件的档位，违反时立即报告内部契约
  错误，不再执行 CQ→VBR 静默转换。
- 设置页始终显示“恒定质量／目标码率”，但按当前完整组合分别置灰不可用选项。质量滑杆的
  最小值、最大值和持久化签名取自当前精确组合实际探测通过的具体编码器，不再使用代表性
  编码器区间。
- 导出前检查、设置摘要、按钮可用性、正式候选和完成信息读取同一五维结论。当前组合不满足
  所选编码模式时，以正式本地化文案阻止导出；不得显示另一模式的估算或开始编码。
- 编码模式成为第六项公开规格。CQ 与目标码率之间的变化与格式、编码器族、位深、软硬件类型、
  帧率一样，不允许作为同规格内部重试自动发生；CBR 仍只属于“目标码率”用户意图内的设备
  后备，并在完成信息中如实标注。

## D184（2026-07-30）选项说明随选项本身显隐；编码模式在冲突让步中排最末

用户提出的两处，均已改并定案。

**一、没有这个选项，就不显示它的说明。**

恒定质量下「复杂帧质量保护」那一行是 `GONE` 的（D151：该保护只作用于 VBR），但它下面那行
说明仍然写入，界面上留着一段没有归属的文字。根因是 D174“说明常显”被扩大解释了：那条讲的是
**说明不随开关的开／关显隐**——用户正在判断“要不要打开”的时候把话收走是错的；它不包含
“选项本身都不在了还留着说明”。

约束改为：**每一行说明的显隐判据必须与它那一行的显隐判据同源。** `qpGuardNote` 与
`refreshModeRows` 里的 `qpGuardRow` 共用 `prefersConstantQuality`。

全面核对了这一组的全部十一行说明，只有上面这一处有问题：

- `mappingNote`、`bitDepthNote`、`signalRangeNote`、`whiteNote`、`referencePeakNote`、
  `highlightNote` 早就按条件写空串收起（`setNote("")` 会把整行收掉）；
- 高复杂度、启用 B 帧、关键帧间隔三行始终显示，说明随之常显；
- 编码模式那一行按 D183 始终显示（不可用的一侧置灰而非隐藏），因此 `rateControlNote` 也
  常显，只是文案在两种模式与“无精确规格”之间切换。

完成信息一侧本来就是对的：`qpGuardRequested` 要求解析出的形态确实是 `VARIABLE_BITRATE`，
恒定质量下不会声称启用了这项保护。

**二、冲突让步顺序：编码模式 → 编码器族 → 输出格式 → 帧率。**

设置页的 `reconcile` 此前只枚举格式、编码器、帧率三轴，编码模式被 `feasible` 当成固定前提。
于是“120 fps 上没有恒定质量通路”表现为**恢复默认后掉到 60 fps 的恒定质量**——拿一项真实的
规格损失，换了一项本可无损替代的偏好。用户裁定：**恒定质量与目标码率不进入优先级比较**，
因为恒定质量编不出来时，把目标码率调高同样能提升画质。

修正后：

- 编码模式进入 `reconcile` 的枚举，并且**保护权重最低**，即冲突时第一个让步；
- **帧率保护权重最高**，最后才让步。这与 D179 的画质优先顺序（帧率固定 → 保持格式与位深 →
  换编码器族）一致；D179 已经要求设置页初始解析、能力矩阵与运行时建议共用同一份顺序，而设置
  页这一处的权重此前与它相反（格式 4 > 编码器 2 > 帧率 1）。现为帧率 8 > 格式 4 >
  编码器 2 > 编码模式 1，权重的**相对大小**就是让步顺序本身。
- 编码模式胶囊的点击必须与帧率一样接进同一套联动（`applyMode`），不能只写偏好：`resolve` 的
  默认参数读的是界面下标 `modeIndex`，只写偏好会让这条轴的显示与求解分家。
- D183 关于“编码模式是第六项公开规格、不得作为同规格内部重试自动发生”的约束不变：本条只
  管**设置页在组合不成立时如何收敛**，不放松正式导出期间的严格性。

## D185（2026-07-30）能力矩阵低帧率行只承袭同一实现的高帧率结论

修订 D179 实施中"高帧率通过即蕴含低帧率通过"的无条件承袭：120 fps 产生赢家后，60 fps 行
此前直接复用该 tier 与全部绑定结论（码控形态、色度位置、复杂度落点），60 fps 从未实测。

承袭的前提只对**同一实现**成立。候选生成先按 `areSizeAndRateSupported(w, h, 120)` 筛编码
器，在"硬件编码器只支持到 60 fps、120 fps 由软件实现扛下"的设备上，120 的赢家是软件实现；
60 fps 若承袭它，设置页会把该行错标成软件，行内按 codecName 绑定的形态/复杂度结论也指向
错误的实现——正式导出按实际帧率重新排序后首选硬件，那些结论等于缺失。

现改为**条件承袭**：

- 承袭前先对本帧率纯枚举候选（`candidatesForMode`，不编码）；首位候选与高帧率赢家为同一
  `codecName` 才承袭实测结论。常见设备（硬件扛得下 120 fps）保持零额外编码。
- 分歧时本帧率单独实测，并清空族赢家状态：本行的结论只能来自本帧率的实测，高帧率赢家不得
  经行尾兜底漏回本行；它通常也在本帧率的候选列表里，会以普通候选身份被重新实测。
- 其余字段的承袭方向保持接受：形态（CQ+提示）与复杂度（省略）即便低帧率本可更优也只往
  保守方向偏，色度位置与质量区间与帧率无关。

## D186（2026-07-30）正式导出侧对"无探测结论回退的纯 CQ"开放一级运行时形态阶梯

D167 的形态阶梯此前只存在于短探测：正式导出读探测结论，缓存取不到时回退纯 CQ 一次性下发，
configure 失败即淘汰候选。该设计在三种情形叠加时产生真实回归窗口：候选阶梯救不了
configure 必须携带码率键的设备（每个 CQ 候选都以纯 CQ 失败，整批淘汰后建议换族/换格式，
而 CQ+码率提示本可成功——正是 D167 立项要避免的方向）；预检对"矩阵无该组合行"按设计放行；
`PROBE_CONTRACT_VERSION` 升级会让全部设备的矩阵失效直至下次打开设置页。

现补一级运行时阶梯，边界如下：

- **触发条件**（三者同时）：本次下发的纯 CQ 来自"无探测结论"的回退（缓存命中时探测结论
  仍是唯一权威，矩阵过期的正路是重探）；失败发生在编码器初始化阶段；本候选尚未做过形态
  退让。
- **动作**：同一候选改以 CQ+码率提示（D147 自动推导码率）重试一次。两种形态的用户可见
  语义相同（D167），不构成公开规格变化；属于零进度同规格内部重试（D179 允许）。完成信息
  经实际落点如实显示形态，诊断记录退让原因。
- **不回写矩阵**：矩阵只归探测写；下次进设置页重探归位。
- HLG super-white 回环验证与正式编码继续共用同一份形态（D139）：重试形态贯通
  `resolveHlgPlan`。

---

## D187（2026-07-30）导出进度对话框在前台期间，实时水体完全冻结

导出跑在与实时视图**同一个进程**里，而实时 GL 线程是 `THREAD_PRIORITY_DISPLAY`
（`FableSolGlRenderThread.attach()`）、导出工作线程只有 `THREAD_PRIORITY_DEFAULT`
（`FableSolVideoExportService.ensureWorker()`）。不让路的话，实时视图在 CPU 调度上一直
压着导出。

### 冻结的构成

`frozen = setSimulationPaused(true) + 停帧循环 + 撤帧率投票 + 按需单帧`

**只冻模拟是不够的。** 调参 Dialog 那个 `setSimulationPaused` 只跳过 `sim.update` 与重力
应用，`buildFrame` + `drawFrame` 每帧照跑，而按 D9 的实测 `buildFrame` 占 CPU 帧路径 87%。
字面意义的"暂停对声音和倾斜的响应"几乎省不出资源，只是让画面站住。因此新增一个与它并列
的独立开关：两者语义不同，也不能合并成一个字段——那样解除其中一个会把另一个一起解除。

**撤帧率投票是两笔。** SurfaceView 自己的 `setFrameRate` 由 `clearSurfaceFrameRate()` 撤
（`stopFrameLoop()` 原本不撤），播放对话框窗口的 `preferredRefreshRate = 120` 由
`applyWindowRefreshRateVote(false)` 撤。少撤任何一笔，面板都会为一张静止画面继续跑在
120Hz。

**否掉"冻结改为 1fps 限速"**：`FableSolFramePacer` 下限就是 1.0，现成可用，但限速只跳过
build+draw，`postFrameCallback()` 在 `shouldRender` 之前无条件执行——每秒仍有 120 次 GL
线程唤醒。

### 判据是"门"，不是一次性命令

三个子系统各有自己的恢复入口，只发一次"停"必然漏：

| 子系统 | 会撤销冻结的路径 | 处理 |
|---|---|---|
| 帧循环 | `onResume` → `onVisibilityAggregated(true)` → `ensureAnimating()` | frozen 进 `shouldAnimate()` |
| 倾斜传感器 | `onResume` → `startTiltSensor()` | 改由 `FableSolExportFreezeGate` 下发目标状态 |
| surface | SurfaceView 的 surface 在窗口不可见时被销毁，重建出来的一帧未画 = 空白 | 冻结态的 `ensureAnimating()` 投一次"渲染一帧就停" |

判据抽成不依赖 Android 的 `FableSolExportFreezeGate`，由 `FableSolExportFreezeGateTest`
的 9 条事件序列用例钉住；View 与 Fragment 只负责幂等执行。

### 触发条件：只看对话框在不在

**进度对话框存在期间冻结，对话框消失即解冻**，不看导出跑到哪一步。完成态、等待确认态
继续冻着是有意为之：水体被对话框盖住，而后台确认不设超时，冻着反而省电。

否掉"还要看导出状态"（多一块总线状态机，且完成态下水面突然复活而声音仍暂停）；否掉
"只要导出在跑就冻"（用户点「在后台运行」回到播放界面就是一潭死水，一旦允许手动解冻
就退化回本规则）。

**重试链路上判据是连续的**：自动重试与用户确认的重试都复用同一个 `ActiveJob`、jobId 不变
（`acceptSuggestedSpec` 直接 post `Running`），对话框原地重渲染，不会消失重建。进度对话框
全项目只有 `FableSolVideoExportLauncher.launchGranted` 一处 show，通知栏也没有把它拉回来的
PendingIntent——因此"对话框在前台"只可能发生在播放对话框之上。

### 播放：暂停，不自动续播

与"对话框不可见就暂停"同一约定，何时接着听由用户按播放键决定。三条硬理由：

1. `FableSolAnalysisBatchInbox` 无界且**只被渲染循环 drain**，停了帧循环而播放继续，分析帧
   会一路堆到解冻。
2. `onCompleted()` 会自动跳下一条附件并 `autoPlay = true`。导出一条 3 分钟录音可能跑十几
   分钟，期间播放对话框会把剩下的音频附件一条条放完，还每次把 HDR headroom 顶上去。
   （另在 `onCompleted` 里加了压制期不跳转的保险。）
3. 播放线程是普通优先级的 `Thread("FableSolAudioPlayback")`，解码 + AudioTrack + 实时 FFT
   全在上面，与导出工作线程同优先级正面抢 CPU。

### 可见表现：不改不透明度，直接冻

水体 280dp×450dp 铺满播放对话框，进度对话框只有 320dp 宽、高度 wrap_content，上下各露出
一大截——冻结是用户看得见的。设计当日先定的是"淡到 0.16 再冻"（与录音停止后退居背景同一
个值），实机看过之后由用户改判：**播放对话框里水体是内容本身，退到背景档会被读成"内容
没了"**；退居背景那套语言属于录音对话框的空转态，不适用在这里。

因此冻结不碰不透明度、立即生效。附带收益是省掉那 360ms 淡出的渲染——那同样是从编码
那里抢来的。`animatePresentationAlpha` 上为此加的 `onEnd` 回调随之撤回。

仍需注意：冻结态下渲染循环停着，`openTrack` 的淡入动画中间值虽然画不出来，却可能被
surface 重建时的按需单帧原样定住，所以冻结态改为直接给终值。

### 方向锁不动

`mLockedRotation` 是对话框打开时按当时 rotation 定死的。冻结期间解锁让设备转过去，恢复后
重力到屏幕坐标的换算就是错的。传感器注销、方向锁保持。

### 信号通路：监听宿主 FragmentManager

`FragmentManager.FragmentLifecycleCallbacks` 监听进度对话框的 attach/detach，注册时再自己
扫一遍 `fragments` 做初始同步（重建时两个对话框谁先走 `onCreateView` 没有保证）。

**否掉"启动导出时把回调交给进度对话框"**：配置变化会重建两个对话框、回调随之丢失，水面
就再也解不了冻——三星 Z Fold4 折叠展开、暗色模式切换、关掉实时倾斜后的旋转都会触发重建。
失效模式是"永久冻结的一潭死水"，不能赌它不发生。

### 不在范围内

录音对话框的 PREPARED / STOPPED 空转态（水面 alpha 只有 0.16、无音频输入却每帧
build+draw）用同一个原语就能省下来，但那是产品行为变更而非纯优化，另记 followup。录音
对话框与导出无关：点「保存并导出视频」先 `saveFileAndLeave()` 关闭对话框，导出在
`onDismiss` 里才发起，那时它的 visualizer 已经 `onDetachedFromWindow` → `stopFrameLoop()`。

---

## D188（2026-07-30）进程被杀后恢复出的进度对话框判为"导出已中断"，不再回落成排队态

`FableSolVideoExportBus.newJobId()` 在铸号那一刻就把排队态登记进 registry，所以只要任务是
本进程发起的，`currentFor` 必然有值——**取不到只有一种可能：进程被杀过**。此时
FragmentManager 会把对话框从 savedInstanceState 恢复出来，而服务、总线、任务全都不在了，
再没有任何状态会送达。

原先回落成 `Queued` 会转一个永远不停的圈。D187 之后这条路径的代价升级了：判据只看对话框
在不在，僵尸对话框意味着水体永久冻结、播放永久暂停。因此直接判为"导出已中断"终态
（复用 `fablesol_export_service_interrupted`），给出确认按钮。

`currentFor` 返回 null 还有第二种成因——终态被 registry 限长淘汰（>64 个任务）。用
`isKnownJobId`（号 < 下一个待发号 ⟺ 本进程铸过）区分：那种情形任务确实跑完过、结果也显示
过，直接关掉即可，不报中断。dismiss 走 `view.post`，不在 `onCreateView` 里同步拆自己。

---

## D189（2026-07-30）D187 的两处生命周期修正；EOS 收尾暂停一项判定不成立

外部评审对 D187/D188 提了四项，三项成立、一项不成立。记录在案是为了防止后续按错误结论
改回去。

### 修正一：帧率省电平衡要跟着冻结一起切

`isFrameRatePowerSavingsBalanced = false` 的含义是让窗口**退出**系统按需下调刷新率的省电
调节。动画期间需要它（水面是连续动画），冻结期间恰恰相反——静止画面应该把刷新率控制权
还给系统。原先它在 `onStart()` 里无条件置 false，撤了两笔显式帧率票却仍占着这一项。

现与 `preferredRefreshRate` 合并进 `applyWindowFrameRatePolicy(animating)`，两笔一起切。

### 修正二：帧时间锚在 `setAnimating(true)` 里复位，不在解冻点补

评审指出后台解冻时 `setFrozen(false)` 的 `handler?.post` 会被丢掉——`detachBlocking()`
末尾 `handler = null`。属实，但根因更早：**`lastFrameTimeNanos` 本来就没有任何复位点**，
每一次切后台再回来的首帧都以后台前的时间戳算 dt，被 `MAX_DT_SECONDS` 夹住之后仍是常规
步长的 6 倍。这是 D187 之前就存在的问题，不是冻结引入的。

因此不去补"让解冻那次 post 活下来"，改为在 `FableSolGlRenderThread.setAnimating(true)`
里无条件复位：循环停过就必然有间隔，锚必然过期。一处覆盖解冻、后台返回、surface 重建
三条路径，也不再依赖 handler 在某个时刻是否还活着。

### 判定不成立：EOS 收尾期间暂停不生效

评审称 `FableSolAudioFilePlayer.drainToEnd()` 不调 `waitWhilePaused()`，因此在末尾缓冲阶段
发起导出时声音会继续跑到尾部。**不成立。** `decodeLoop` 的收尾分支是

```
if (sawOutputEos) {
    if (drainToEnd()) { if (shouldRun) complete(); return }
    continue
}
```

`continue` 回到 `while (shouldRun)` 顶部，顶部第二句就是 `if (!waitWhilePaused()) break`。
暂停在下一次迭代生效，`waitWhilePaused()` 里调 `audioTrack?.pause()`、发出 playing=false
并阻塞在 `lock.wait()`，恢复时还调 `resetDrainStall()` 清掉收尾停滞计时——后者的存在本身
就说明"收尾期间会被暂停"是设计内的情形。

真实偏差只有一次迭代：`drainToEnd()` 在 `paused` 为真时跳过自己那次
`lock.wait(BUFFER_FULL_WAIT_MS)` 并多跑一遍 `pumpAnalyzer()`。为此改动 EOS 收尾循环不划算，
反而会与 `resetDrainStall` 的时序纠缠。

### 顺带：领域文档的旧合同

`CONTEXT.md` 原写"显示实时 Voice Waveform 的界面不受是否正在生成视频影响"，D16 原写
"导出期间播放照常"。两句在 D187 之后都不成立，已分别改写与加删除线标注。产物内容确实
仍与实时水体无关——被改变的只是资源让路。

---

## D190（2026-07-30）双机导出验收采用能力约束下的风险覆盖矩阵

“各种参数组合”不解释为把不可行组合与全部连续滑杆值做笛卡尔积。验收先读取每台设备的真实
能力矩阵，再采用以下边界：

- 每种可用 HDR 格式、编码器族、帧率、码控模式、SDR 位深与 SDR 映射至少有一项真实产物；
- B 帧、高复杂度、复杂帧质量保护、关键帧间隔、目标码率、漫反射白、参考显示峰值、高光起点
  与 HLG 信号范围采用两两覆盖，并把容易暴露时域或量化问题的边界值放入代表项；
- 能力矩阵已经证明不可达的组合不伪造导出，保留失败层级与设备原因作为验收结果；
- 两台设备必须先对齐到同一 APK SHA-256，使用各自真实记事中的音频附件；每项保存精确请求
  偏好和完成态规格，测试结束恢复用户原偏好；
- 电脑端对每个产物执行音视频完整解码、流规格和时间戳检查；动态 HDR 另核对逐帧元数据覆盖。
  画面检查以线性化后的水体主体亮度步进、三帧脉冲、交替变化和高频能量为主，同时保留整帧与
  水体细节联系表作视觉复核。

该方法验证公开可行面和高风险交互，不把设备不支持、用户主动选择极低码率或没有 HDR Vivid
显示终端等外部边界误判成实现缺陷。

---

## D191（2026-07-30）AOSP 软件 AV1 的 HDR VBR 不列为有效能力

双机矩阵中的 OPPO `O09`、三星 `S02`／`S04` 都由
`c2.android.av1.encoder` 生成 10-bit HDR VBR。三项音视频结构、时间戳和色彩标记合法，
但平滑海浪渐变出现肉眼可见的矩形块；同机 HDR10／HLG CQ 对照没有该问题。请求
12／24 Mbps 并不能成为质量下限，异常 VBR 样本实际只有约 0.9～1.2 Mbps。

已按可证伪顺序排除以下替代解释：

- 通用复杂帧保护 `QP_MAX=40` 只能减轻，不能消除三星 HDR10 的块效应；
- 把上限收紧至 8 后，三星与 OPPO HDR10 干净，但 OPPO 约 7 秒 HLG 样本在约 5 秒处仍有
  局部矩形块；
- 按 Android `MediaFormat` 的 I/P/B 类型键分别写 8，与通用键 8 产生逐字节相同的视频
  基本流；
- 再把上限收紧至 4 和 1 仍不能消除 OPPO HLG 的矩形块；QP=1 还让 12 Mbps 请求实际升至
  约 19.43 Mbps。

公开实现与实机结果一致。[Android `MediaFormat`](https://developer.android.com/reference/android/media/MediaFormat)
说明通用 QP 键会按 MIME 转成逐类型边界；AOSP
[`C2SoftAomEnc`](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/media/codec2/components/aom/C2SoftAomEnc.cpp)
只接收 I/P 两类上限，setter 取两者较小值，最终向 libaom 下发一个
`rc_max_quantizer`，而 VBR 路径固定启用 cyclic-refresh AQ（`AV1E_SET_AQ_MODE=3`）。
因此问题不是键形态或 QP 不够严格，而是该实现的 HDR VBR／cyclic-refresh 组合不可靠。

决定如下：

- 仅当编码器名字精确为 `c2.android.av1.encoder`，且候选同时是软件 AV1 与 HDR 时，把
  广告的 VBR 从**有效能力**中剔除；与“复杂帧质量保护”开关无关；
- 目标码率候选若同时广告 CBR，则沿既有形态解析落到 CBR，完成信息明确显示“恒定码率
  （设备后备）”；若没有 CBR，则不提供该目标码率候选；
- CQ 不是同一条码控路径：最高质量仍保留 D181 已在 OPPO 渐变上验证的 `QP_MAX=8`；
- 厂商 AV1、硬件 AV1、SDR 以及其它编码器族没有同一份证据，能力不变。

最终 APK 在两台设备上复跑 HDR10／HLG：四组 CBR 与同机 CQ 基线的 SSIM 为
0.999119～0.999511、PSNR 为 55.93～59.01 dB，完整解码、帧数、PTS、GOP、CICP 与 `nclx`
均正确，水面细节不再有 VBR 的严重块效应。按原设置关闭 QP 保护复跑的 `O09`、`S02`、
`S04`，与各自开启保护的 CBR 回归项生成逐字节相同的视频基本流，证明该开关不会旁路决定。
能力探测缓存契约版本由 15 升至 16，使旧 VBR 可行结论失效。
