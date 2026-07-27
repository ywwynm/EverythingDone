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

帧率上限、CQ 质量档、目标码率、关键帧间隔**全部**放进 FableSol 调参 Dialog，release 构建
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
| 目标码率 | 120fps → 24 Mbps；60fps → 14 Mbps（约 0.12 bit/像素/帧） |
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
- **导出期间播放照常**，两者不冲突（离线导出不碰 AudioTrack）。
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
High → Main → Baseline；设置 profile 时同时设置该编码器广告的 level。

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

## D32（2026-07-27）缓存签名必须含发布时间戳；杜比视界拆 8.1/8.4；HDR10+ 改按 SEI 判定

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

### 杜比视界拆成 8.1 与 8.4

8.4 的基层是 HLG，高光余量只有约 3.77 倍——正是我们嫌弃 HLG 的那个限制。**8.1 的基层是
PQ**，余量到 10000 尼特，且本身与 HDR10 兼容。二者**profile 常量相同**
（`DolbyVisionProfileDvheSt`），**唯一区别是传递函数**：PQ → 8.1，HLG → 8.4。Dolby 官方样例
只演示 8.4，但没有任何依据说 8.1 不成立，因此拆成两个格式各自真编一帧判定。

`AUTO_ORDER` 按规格从高到低重排（用户裁定"能支持多高规格就支持多高规格"，明确说不用考虑
收益）：**杜比视界 8.1 → HDR10+ → HDR10 → 杜比视界 8.4 → HLG**。前三为 PQ 基层（满余量），
后二为 HLG 基层；同基层内带动态元数据的在前。

`HdrFormatPreference` 的新增项一律续在末尾（`DOLBY_VISION_84` 占原 `DOLBY_VISION` 的位置，
`DOLBY_VISION_81` 续后），保持已持久化的序号语义不变。

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

### 杜比视界 profile 5：加，但要说清代价

用户裁定按 5 → 8.1 → 8.4 的顺序试。profile 5 是单层 PQ + IPT-PQ-c2，规格上最"纯"，但
**不向下兼容**：不支持杜比视界的播放端打开是一片绿紫，而 8.1 在同样场合会正常按 HDR10 播。
已加入并由真实编码判定，同时在界面说明里写明这个取舍。`AUTO_ORDER` 变为
5 → 8.1 → HDR10+ → HDR10 → 8.4 → HLG。

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
- 滑杆**只在选中 PQ 系格式时显示**（HDR10 / HDR10+ / 杜比视界 8.1）。HLG 与杜比视界 8.4
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
Dolby Vision 5 → Dolby Vision 8.1 → HDR10+ → HDR10 → Dolby Vision 8.4 → HLG
```

这套顺序优先保留 PQ 高光余量、动态元数据与更高 HDR 规格，并非播放器兼容性排序。界面文案
统一改为“按 HDR 规格与画质能力由高到低，选用本机实测可编码的首个格式”，同时保留当前实际
落入格式。仅修正文案，不改变已有 `AUTO_ORDER` 或编码降级行为。

---

## D48（2026-07-27）杜比视界 8.4 排在 HDR10 前，中文显示名统一本地化

用户进一步裁定自动顺序，并要求全面清理用户界面的英文 “Dolby Vision”：

```text
杜比视界 5 → 杜比视界 8.1 → HDR10+ → 杜比视界 8.4 → HDR10 → HLG
```

D47 关于“不是兼容性优先”的结论仍成立，但其记录的旧顺序由本决策覆盖。杜比视界 8.4 虽使用
HLG 基层，仍带设备生成的杜比动态元数据，因此在本产品的自动策略中排在静态元数据 HDR10
之前；HLG 继续作为最后兜底。

格式名称分成两层：

- **稳定标识**：固定英文，供探测缓存、格式恢复、日志关联和文件名等内部协议使用，不得本地化；
- **显示名称**：来自 Android 字符串资源。简体、繁体中文统一显示“杜比视界 5 / 8.1 / 8.4”，
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
3. **验证范围**：杜比视界各 Profile 独立验证。Profile 8.1 失败只说明 PQ 基层请求未通过，
   不将 Profile 8.4 描述为“还有机会”；改为说明 Profile 8.4 采用 HLG 基层并按独立候选验证。
4. **HDR10+ 路径**：独立报告“未提交动态元数据”和“提交 ST 2094-40 元数据”两种条件，
   不再使用“裸通路”“带元数据”等口语化标题；判据明确为输出码流是否检测到
   HDR10+ ST 2094-40 SEI。
5. **设备能力摘要**：统一使用“支持 / 不支持 / 未发现 / 未声明”等明确状态，区分显示设备
   亮度能力、EGL 色彩空间、10-bit 窗口配置和编码器声明能力。

能力缓存继续保存稳定英文格式标识和原始技术详情；展示时再按当前 locale 转换格式名称。
探测契约版本升至 5，使旧缓存中的口语化失败信息失效并重新执行验证。
