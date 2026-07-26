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
