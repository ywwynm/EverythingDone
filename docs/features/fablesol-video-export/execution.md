# fablesol-video-export 执行记录

按 [plan.md](plan.md) 的六个批次推进。

---

## 2026-07-26 首轮实现（批次 1～6 全部落地）

编译通过（`:app:assembleDebug`），全量 `:app:testDebugUnitTest` 无失败。**尚未在真机验证**。

### 批次 1：HDR 三层解耦

结论比计划里写的**小得多**——耦合本来就不在渲染器里，而在调用方。
`FableSolGlRenderer.initialize(hdrOutput)` 早已是一个参数化的入口，屏上传
`session.isHdrOutput`，导出传自己的探测结果即可。因此本批只做了三件事：

- `initialize(linearScene: Boolean)`：改名 + 补文档，说明"场景是否线性"由调用方决定，
  与任何显示器无关。
- 新增 `setOfflineTimebase(enabled)`：`render()` 里的 `now` 改由 frameTimeNanos 推出。
  **这一条是必需的**——`drainAndApply` 用 `now - lastAudioElapsed > IDLE_SILENCE_MS` 判静默，
  而导出的挂钟推进速度与音频时间无关，沿用挂钟会在两个音频 hop 之间误判成静默。
- 新增 `primeHdrForExport(strength)` 与 `FableSolHdrTransition.snapTo()`：把 headroom 钉在
  用户强度档、增益从第一帧就是满的，不走 0.36s 淡入。

另新增 `ScenePresenter` 接口与 `setScenePresenter()`：`drawFrame` 末尾在有 presenter 时
交给它，否则走原来的 `presentScene`。屏上路径因此**一个分支都没多**。

### 批次 2：确定性时钟

`TimelyClockView` 新增两个公开入口：

- `showTimeAtElapsed(millis)`：形变进度由 millis 解析求出（每秒的形变锚定在整秒边界、
  持续 `ANIM_DURATION_MS = 300`），不启动任何 ValueAnimator，同一个 millis 恒得同一画面。
- `breathingAlphaAtElapsed(...)`：录音态时钟呼吸的解析形式。

屏上路径未改动。

### 批次 3：重力轨迹

- `FableSolGravityTrack`：`Collector`（采集侧，按 50Hz 栅格零阶保持重采样）+ `readFrom`
  （解析侧，任何异常都当作"没有轨迹"）。chunk id `EDmo`，置于 `data` 之后。
- `AudioRecorder`：`startRecording()` 起算、`stopListening()` 收尾、`saveToWaveFile()` 写
  chunk 并把 RIFF 长度字段加上 chunk 体积。
- `AudioRecordDialogFragment.dispatchGravityToVisualizer()` 顺手投递一份给 recorder。
  记的是**送给可视化的那三个分量**（屏幕旋转补偿已做完），回放时直接喂 `setContainerGravity`。

**核实到一处与设计评审时的说法不同**：播放对话框**也有**倾斜传感器
（`AudioPlayDialogFragment` 同样注册 `TYPE_GRAVITY` 并 `dispatchGravityToVisualizer`）。
这不改变任何决策——导出一律离线，倾斜一律来自轨迹——但意味着"没有轨迹的历史录音按竖直
渲染"这条降级同时适用于两个入口。

### 批次 4：离线渲染引擎

新增六个文件：

| 文件 | 职责 |
|---|---|
| `FableSolExportSpec` | 画面规格与 `FableSolExportPlan`（卡片/画布/时钟几何、画框参数） |
| `FableSolExportEgl` | 编码器 input surface 上的 EGL 会话 + 建链前的能力探测 |
| `FableSolExportClock` | 未附着的 TimelyClockView 自绘成位图 |
| `FableSolExportPresenter` | 导出专用 present program |
| `FableSolExportAudioSource` | 流式解码成单声道 PCM |
| `FableSolVideoExporter` | 驱动循环 |

外加 shader `shared/fablesol/glsl/export_present.frag`。

**偏离计划之处（值得记下）：**

1. **画框 + 时钟 + 传递函数合并成一个 pass。** 原计划是"线性画布 FBO → 叠时钟 → 编码"三遍，
   实际发现全程在线性浮点里算、最后一行才套 OETF，一个 fragment shader 就够，时钟的
   alpha 混合照样是物理正确的。省掉一个全画布 FBO 和两次全屏绘制。
2. **像素高度定档 1296**（= 144 × 9，16 对齐）。density 由 `1296 / 420dp` 反推，**不取设备
   density**——否则物理容器宽度会跟着导出分辨率漂移。
3. **`uCanvasPx` 被删。** 它声明了却没在 shader 里用到，GLSL 编译器会把它优化掉，
   `FableSolGlProgram.uniform()` 的 `check(location >= 0)` 会当场抛。这类"声明未用的 uniform"
   在这个封装下是运行时崩溃，不是警告。

### 批次 5：前台服务与落地

- `FableSolVideoExportService`：`mediaProcessing` 类型前台服务，通知带滚动更新的 ETA
  与取消动作，单线程队列（第二次点击排队而非并发）。
- `FableSolExportSink`：API 29+ 走 MediaStore + `IS_PENDING`，老系统写公共 Movies 目录再
  扫描。**两条路都不做整份文件的二次拷贝**——编码直接写进最终位置，失败就删条目。
- 剩余空间检查放在 `FableSolVideoExporter` 里（那里才知道时长与码率），按预估体积 × 1.2 判。
- Manifest：`FOREGROUND_SERVICE_MEDIA_PROCESSING` 权限 + service 声明。

### 批次 6：入口、设置与文案

- 录音对话框停止态：`[重录][对号][导出][取消]`，导出 FAB 48dp、accent 圆形底、淡入。
- 播放对话框：进度条右侧 40dp 图标按钮（滑杆右边距相应从 20dp 改到 56dp）。
- 共用图标 `act_fablesol_export_video`；当前实现采用补全左、上画框的 Material
  `video_frame_save`（见 D25）。
- GLES 不可用（`WaveVisualizerFableSolHost.isGlActive()` 为假）时两处入口都 `GONE`。
- 调参 Dialog 新增「导出」分组：帧率上限（120/60 开关）、恒定质量、质量档、目标码率、
  关键帧间隔，外加一行推导结果（MB/分钟 · 耗时倍率）作为无预览参数的反馈回路。
  纳入「恢复默认」。
- 13 种语言文案。

---

## 2026-07-26 第二轮：真机反馈修正

编译通过，全量单测无失败。仍未真机验收。

### 录音对话框按钮行

原来重录/取消只改 `alpha`、**始终占位**，加进导出 FAB 后主按钮被挤得偏心。改为
**visibility 驱动**：准备与录音态整行只有主按钮（严格居中），停止态三个副按钮才
`VISIBLE` 并淡入；回到准备态时淡出后 `withEndAction` 收回占位。导出 FAB 尺寸从 48dp
改到 **56dp / padding 16dp**，与对号完全一致，两者都大于 40dp 的重录与取消。

### 播放对话框对齐与配色

- 进度条轨道左缘对齐时钟左缘：`marginStart` 16dp + `paddingStart` 8dp = 24dp（时钟的边距）。
  留 8dp 内边距是给滑块，直接把 padding 归零会让滑块在两端被裁掉。
- 导出按钮 icon 右缘对齐时钟右缘：`marginEnd` 16dp + `padding` 8dp = 24dp。**对齐的是
  icon 不是按钮区域**，所以边距要按 padding 折算。
- 图标着色与涟漪改为**跟随 App Chrome**（`app_chrome_control_unchecked` +
  `installAppChromeCircleRipple`），此前错误地跟随了记事 accent。这枚按钮属于 chrome，
  不属于记事——走带控件才跟记事颜色。

### 导出进度对话框

新增 `FableSolExportProgressDialogFragment` 与 `FableSolVideoExportBus`。

**关键语义**：导出**始终**在前台服务里跑、通知栏也始终有通知；对话框只是同一份状态的
另一个观察者。「在后台运行」只是关掉对话框，不改变任何执行路径——这一条纯粹是为了让
用户觉得直观。导出完成时对话框若还开着，就地换成完成态，给出「分享」与「添加为附件」。

「添加为附件」需要真实路径（本应用的附件模型是路径不是 URI），因此 `FableSolExportSink`
新增 `localPath()`：MediaStore 侧查 `MediaStore.Video.Media.DATA`，取不到就只提供分享。

通知标题改为「导出音频海浪动画视频」，完成后新增分享 action。

### 设置

- 入口文案「音频海浪动画参数调节」→「音频海浪动画设置」（13 语言）。
- 帧率与码率模式从 checkbox 改成**二选一的圆角标签**（60 fps / 120 fps，恒定质量 / 恒定码率）。
- 恒定质量档显示 **CQ 原值**（附设备区间），并隐藏目标码率；恒定码率档反之。
  设备完全不支持 CQ 时整个模式选择与质量行都不出现。
- 新增「导出 HDR 视频」开关，与 HDR 强度、设备能力构成三道门。
- 推导行加「约」字，并且**恒定质量档下不再给体积估算**。

**关于体积估算算错这件事**：用户实测一分多钟的视频只有二十几 MB，而面板按 24 Mbps 报
180 MB/分钟。原因不是把所有帧当成 I 帧算——公式 `bitrate × 60 / 8` 对真正的 24 Mbps 流是
对的——而是**默认走的恒定质量档里 `KEY_BIT_RATE` 只是提示**，实际码率由画面复杂度和
质量档决定，实测约 3 Mbps。所以正确的修法不是改公式，而是**只在恒定码率档给数字**，
恒定质量档改成「体积随画面复杂度变化」。

**关于「质量参数（CRF）/（QP）」的命名**：Android 的 `KEY_QUALITY` 既不是 x264 的 CRF、
也不是编码器内部的 QP，而是各厂商自行映射的一段区间（`getQualityRange()`），只保证
"越大越好"。因此界面上按**原值**显示并标注区间（如 `72  (0-100)`），标签写「质量参数（CQ）」
而不是 CRF 或 QP——后两者会让人以为可以照搬 x264 的经验值。

---

## 2026-07-26 第三轮：外部静态评审的十条，九条属实并修复

用户请 GPT 对导出链路做了一次静态评审。逐条核实后，**十条里九条是真缺陷**，一条（码率
范围）是注释与实现不符。全部已修，编译与全量单测通过，仍未真机验收。

| # | 问题 | 判定 | 修法 |
|---|---|---|---|
| 1 | `finish()` 一次 `drain()` 无产出就退出 | **属实** | 改为只受 30s 总时限约束，循环到两条轨都真的报 EOS；并 `check(muxing)` 防止无声无息产出畸形 MP4 |
| 2 | 离线时间轴用了未来音频；120fps 首帧步长错 | **属实** | 喂音频改到 `i/fps`（原为 `(i+1)/fps`，等于每帧前瞻一帧）；新增 `primeFrameTime()` 预置上一拍，首帧 dt 精确等于 1/fps |
| 3 | 离线分析器没套用户前端调参 | **属实** | 补 `FableSolFrontEndTuning` + `applyFrontEndStored` + `applyTo`，与录音、播放三处一致 |
| 4 | 能力探测后无真降级；`createEncoderByType` 未必是探到的那个编码器 | **属实** | `select()` 换成 `candidates()` 返回**带编码器名字**的有序候选，`createByCodecName` 创建；建编码器或建 EGL 失败就换下一档（含 120→60），全试完才失败，每次失败 `sink.discard()` 清残留 |
| 5 | 服务 `running` 竞态丢任务 | **属实** | 入队与"队列空即停工"放进同一把 `queueLock` |
| 6 | 锁屏后无 wake lock | **属实** | 加 `PARTIAL_WAKE_LOCK`（6 小时兜底超时），与 `mediaProcessing` 每日上限同量级 |
| 7 | 老系统 `Uri.fromFile` 外发会崩 | **属实** | 改走 `FileProvider.getUriForFile`，`external-path path="."` 已覆盖公共 Movies |
| 8 | "10-bit SDR" 实际经 8-bit 表面 | **属实** | EGL 位深改为跟随 `!eightBit` 而非 `hdr`；HEVC Main10 SDR 现在真拿 RGB10_A2 |
| 9 | "恒定码率"配成 VBR；码率范围注释不实 | **属实（一半）** | 支持 CBR 就用 CBR，否则退 VBR；码率下发前由 `tier.clampBitrate()` 夹到该编码器的 `bitrateRange`；注释改成"CQ 区间读设备、码率滑杆是通用范围+运行时夹取" |
| 10 | GLES 异步回退后入口不隐藏 | **属实** | 两处点击回调都再查一次 `isSupported`，不支持就地隐藏并返回 |

顺带自查发现并修掉的一条评审没提的：帧循环只喂到最后一帧的时间点，**音频尾巴（不足一帧
的那一段）从未进编码器**，音轨会比画面短一帧。收尾前补一次排空。

---

## 2026-07-26 第四轮：第二次外部评审，八条实质问题里七条属实

| # | 问题 | 判定 | 修法 |
|---|---|---|---|
| 1 | 120/60fps 物理子步不均匀 | **属实** | 帧时间戳是整数纳秒，`1e9/120` 截断后 dt 比 `PHYSICS_DT` 少约 0.33ns，累加器给出 `0,1,2` 序列——"120fps 每帧正好一步"从未成立。新增 `setOfflineFixedDt(1.0/fps)` 直接下发有理数步长 |
| 2 | 实时与离线初值不同，`max|Δ|=0` 判据不可达 | **属实** | 初值差异是有意设计（准备态就喂分析器、开始录音不重置），不改代码；**改判据**（见 followups.md）。代码侧只修掉预热门：离线调 `skipStartupGate()`——那道门拦的是麦克风冷启动，读文件没有这回事 |
| 3 | 队列竞态仍未彻底修复 | **属实** | 上一轮只堵了 poll/入队窗口，**收尾窗口没堵**：旧线程 finally 会释放新线程的 WakeLock、`quitSafely()` 新线程、`stopSelf()`。改为收尾前在锁内确认 `worker === thread && !running`；取消改为**按任务令牌**，不再 `queue.clear()` 误伤后来者 |
| 4 | 降级尝试中构造失败泄漏；`inputSurface` 未释放 | **属实** | 构造体移进 `configureCodecs()`，init 里 try/catch → `release()` 再抛；`release()` 补 `inputSurface.release()` |
| 5 | HDR 能力判定不完整；循环外失败不降级 | **属实** | API 34+ 加 `FEATURE_HlgEditing` 过滤；AV1 档限 API 34+（MediaMuxer 更早不支持 MP4 封 AV1）；`encoder.start()` 移进重试范围 |
| 6 | `commit()` 异常绕过失败处理 | **属实** | finally 里的 commit/discard 包 try/catch；服务侧 `runJob` 也包一层并向 Bus 报 Failed |
| 7 | Android 15+ 六小时 FGS 时限未处理 | **属实** | 覆写 `onTimeout(startId, fgsType)`：取消当前任务、报 Failed、收摊 |
| 8 | 重力轨迹开头用了未来采样 | **属实** | `Collector` 常驻记录最近读数，`start()` 把"此刻的姿态"落成 t=0 种子 |

零散项一并处理：GLES 回退新增 `onGlFallback` 回调，两处入口**立刻**隐藏而不是等下次点击；
候选编码器按用户所选的 CQ/CBR 模式排序，避免首个不支持就静默换模式；导出时钟补上录音开始
那 360ms 的淡入（0.36→1.0）再进呼吸，与屏上相位一致。

未处理并记为遗留：Bus 是全局单例，第二个请求会把第一个的状态重置为 Idle——当前一次只跑一个
导出、第二个排队，影响有限。

---

## 2026-07-26 第五轮：第三次外部评审，八条全部属实

| # | 问题 | 修法 |
|---|---|---|
| 1 | **P1** API 26–28 写不进公共 Movies | `WRITE_EXTERNAL_STORAGE` 在 Manifest 里带 `maxSdkVersion="28"`，但**运行时从未申请**（`PermissionUtil` 只申请读）。改为运行时查权限：没有就写应用自己的外部 Movies 目录（免权限、API 29 前同样可被扫描进相册），有就仍走公共目录 |
| 2 | **P1** Android 14 上 HDR 候选被全部误杀 | `FEATURE_HlgEditing` 是 **API 35** 才加入的能力位，我却从 34 起就拿它过滤——API 34 上一律 false，HDR 全军覆没并静默降 SDR。门槛改到 35，33–34 交给 configure/EGL 重试兜底 |
| 3 | **P1** 旧 worker 仍可能停掉新任务 | 上一轮把判决放进锁里，**执行仍在锁外**：判决后新 START 起了新线程，旧线程照样释放它的 WakeLock 并 `stopSelf()`。改为①WakeLock 由每个 worker 各自持有各自释放；②收尾用 `stopSelfResult(latestStartId)`——判决之后又来 START 的话 startId 已变，停止请求会被系统驳回 |
| 4 | **P2** 排队任务共用全局状态 | Bus 的每个 State 加 `jobId`，`Launcher` 铸 id 并同时下发给服务与对话框，对话框只消费自己那一个；新增 `Queued` 状态，排队中的对话框显示"准备中"而不是别人的进度 |
| 5 | **P2** MediaStore 提交失败仍报成功 | `commit()` 改为返回 `Boolean` 并检查 `resolver.update()` 的行数；调用点从 `finally` 移进**成功路径**，提交失败返回 `Failure` 并清理 |
| 6 | **P2** 录音/播放/导出的分析器起始状态不一致 | 统一**文件输入这条契约**：播放与导出都调 `skipStartupGate()`。"复现录音的预热状态"做不到（那段音频根本没被录下来），已在 followups.md 把 D15 ① 判据改写 |
| 7 | **P2** 降级尝试泄漏 EGL | `attemptEgl` 原是 try 内局部变量，`start()` 失败时 catch 够不着 → 上下文与 surface 随每档失败泄漏。提到 try 外并在 catch 里先于 encoder 释放 |
| 8 | **P2** 系统超时最终报成"用户取消" | `onTimeout` 先立 `timedOut` 旗再取消；结果映射与通知都把 `Cancelled` 翻译成超时失败 |

新增 JVM 门禁 `FableSolExportFixedDtTest`：钉住「有理数步长下 120fps 恒 1 子步、60fps 恒 2
子步」，并把当年那个整数纳秒截断写法的失败形态作为反例记录下来。

**仍未覆盖的测试**（GPT 列的清单里剩下的）：文件播放与导出的事件一致性、两个任务排队不串、
旧 worker 收尾不影响新 worker、commit/timeout/降级的失败路径、Android 14 的 HDR 候选。
这些都需要 MediaCodec / Service / Robolectric，项目目前没有这套环境；记在 followups.md。

**已知边界（未修，非缺陷）**：一次候选尝试只验证到 `encoder.start()`；`eglSwapBuffers`、
首次 `dequeueOutputBuffer`、`muxer.addTrack` 失败仍会终止整个导出而不降级——把它们纳入
重试意味着渲染到一半推倒重来。AV1-in-MP4 这个已知触发点已通过 API 34+ 门槛消除。

---

## 2026-07-26 第六轮：接手修复第四次评审发现的完整链路问题

本轮不再做局部补丁，把“候选编码档”和“前台服务任务”分别收成完整事务：

- `FableSolExportEncoder.finish()` 在返回前完成并检查 `MediaMuxer.stop()/release()`；
  `FableSolExportSink.commit()` 只能在其后调用，MP4 尚未写完时不可能解除 pending 或扫描。
- `FableSolVideoExporter` 每个候选从音频、muxer、codec、EGL、renderer 全新开始，任何渲染、
  首帧交换、输出格式、`addTrack()`、编码或封装错误都清理后尝试下一档。发布失败不重复渲染。
- 输出格式检查实际 profile、尺寸、完整 crop rectangle 和色彩标记；10-bit/HDR 静默降 profile 或 FP16 scene target
  回退 RGBA8 都不再报 HDR 成功。缺失但未冲突的色彩键会写入交给 muxer 的 track format。
- 候选尺寸按具体编码器要求与 64px 分享边界共同对齐，中性画框对称吸收补齐像素；补 H.264 Main/Baseline 和 profile+level。
- 编码器与音频源构造过程改为先登记资源所有权再 configure/start，构造异常同样能释放已创建
  的 codec、Surface、MediaExtractor 与 muxer。
- API 26–28 发起前请求写权限，旧版 sink 只写公共 Movies；文件原子占位、冲突自动改名，
  MediaScanner 回调确认后才算提交。文件名加入毫秒、jobId，并按 UTF-8 字节预留后缀空间。
- Service 改成主线程状态机 + 单工作线程：按任务取消，旧任务没有机会在锁外撤掉新任务的
  foreground；超时立即写失败终态。Bus 使用 `Map<jobId, State>`，终态拒绝旧回调覆盖。
- CQ 改为 64MB 实际空间保底并在编码中滚动复查；CBR 估算纳入 AAC 192kbps。
- 完成 Dialog 只显示可执行动作；排队态的取消与后台运行按钮也有明确监听器。

新增三组门禁：`FableSolExportStateRegistryTest`、`FableSolExportGeometryTest` 和
`FableSolExportPipelineSourceTest`。连同原有定步长测试，全量 `:app:testDebugUnitTest`
与 `:app:assembleDebug` 通过。全量 Lint 仍被项目原有基线挡住（488 errors / 1033 warnings，
首项是 `AutoNotifyReceiver.kt` 的旧 `MissingPermission`）；本功能筛选结果只剩既有
`FableSolExportClock` 的 KTX 风格提示。未使用 adb。已发布阿里云 debug 更新
`202607261246`，本地与远端元数据一致，APK SHA-256 为
`47c17e73260f24ae33c4388f8c373032692dc7920f1d5ed29afa5a9be811df85`。

---

## 2026-07-26 第七轮：双层水波、真实 HDR 能力门与播放结束态恢复

- `act_fablesol_export_video.xml` 继续保留圆角画框，内部改为两条从 Python 真实离线帧提取
  的开放贝塞尔轮廓：底层触及左右内框，上层从 x=9.2dp 开始；两者的峰谷位置、振幅不同。
- `FableSolHdrExportCapability` 在后台按 383dp 最大卡片画布实际编码一帧：依次验证 codec
  profile/尺寸/帧率、AAC+MP4 封装、RGB10_A2 HLG EGL surface、FP16 scene targets 和最终
  输出格式。探测期间或失败后 HDR 开关置灰，失败还会清除无效的 HDR 偏好。
- `FableSolExportAttemptPlan` 将 HDR 请求的尝试顺序固定为 HDR 120、HDR 60、SDR 120、
  SDR 60；设置页探测与正式导出复用 `candidatesForMode()`，不再发生界面与导出分叉。
- `FableSolAudioFilePlayer` 标记自然结束的线程；结束后 seek 会从原路径创建暂停的新线程并
  带入初始 seek，首个输出格式前先读取输入采样率建立进度基准。播放 Dialog 的
  `onPrepared` 不再把用户选定位置清零。
- 主播放/暂停按钮仍为 56dp touch ripple，padding 从 14dp 改为 12dp，可见图标由 28dp
  精确增加到 32dp。
- 新增 `FableSolExportAttemptPlanTest`、`FableSolPlaybackRestartPolicyTest` 并扩展
  `FableSolExportPipelineSourceTest`。完整 JVM 测试 298 项、0 失败、1 跳过；debug APK
  构建成功，未使用 adb。已发布阿里云 debug 更新 `202607261422`；本地与远端重新下载 APK
  的 SHA-256 均为
`ed068f54312b407855805fb919f260a41684d833d8e2bf3689fe2228228188ce`。目标三星设备上的
codec/EGL 结论与播放时序保留为真机验收项。

---

## 2026-07-26 第八轮：Material 图标、HDR 缓存与设置首帧减负

- `act_fablesol_export_video.xml` 改为 Google Material Symbols Outlined
  `video_frame_save`；在官方 path 后补顶部中央、左侧中央两段 80/960 viewport 宽的边框，
  播放三角、保存箭头和右侧 1dp 可见边缘补偿保持不变。
- `FableSolHdrExportCapability` 新增进程与 SharedPreferences 两级缓存。签名包含探测 contract、
  App 版本、Android API 和 `Build.FINGERPRINT`；成功结果长期有效，失败结果 24 小时过期。
- 设置页先尝试无 I/O 的进程缓存；未命中时延后 800ms，以
  `THREAD_PRIORITY_BACKGROUND` 读取持久化缓存或真实编码。探测固定使用默认 CBR，避免结论
  随用户 CQ 偏好漂移。
- 一帧探测仍覆盖实际 HDR codec、RGB10_A2 BT.2020/HLG EGL、AAC/MP4、EOS 与输出格式；
  移除与“编码能力”无关且最重的完整 `FableSolGlRenderer` shader/FBO 初始化。正式导出路径
  的 FP16 scene targets 验证未改。
- `settingsQualityRange()` 对 nullable 结果做进程缓存；不支持 CQ 的设备也不会重复枚举。
  HDR 不支持标签使用与顶部一致的 enabled 文本色 + `0.5` alpha，并追加相同本地化提示。
- 用户明确要求不跑测试，本轮未执行测试任务或 adb；发布任务构建成功。阿里云 debug 更新
  `202607261457`，本地、元数据与远端 APK SHA-256 均为
  `4b5c2f9cbd16c97e2d53911f2f34fb211140cf07651a963b478d8788101f20f5`。

---

## 2026-07-26 第九轮：22dp 导出图标与编码术语

- 录音 FAB / 附件播放导出按钮的容器仍为 56dp / 40dp，只把 padding 改为 17dp / 9dp，
  `video_frame_save` 可见尺寸统一为 22dp。
- `fablesol_param_export_bitrate_mode` 的展示语义由 Bitrate mode 改为 Encoding mode；
  `fablesol_export_estimate_quality` 明确加入 Video size。资源 key 保持不变以控制改动面，
  13 套语言展示文本同步更新。
- 未运行测试或 adb；发布任务构建成功。阿里云 debug 更新 `202607261510`，本地、元数据
  与远端 APK SHA-256 均为
  `499c59a302eba317885d288c68fa05b2d34462548fdeb59ffb0d0194bee5106e`。

---

## 2026-07-26/27 第十轮：PQ 通路、能力诊断与杜比视界检测（三次连续发布）

用户提出两台设备的疑问后要求 A/B/C 三项全做（见 D27）。

- `202607261547`——新增 `FableSolExportTransfer`（SDR / HLG / PQ）与
  `FableSolExportAttemptPlan.ordered(hdrTransfers, requestedFrameRate)`；
  `FableSolExportEgl` 支持 `EGL_EXT_gl_colorspace_bt2020_pq`；`export_present.frag` 增加
  PQ 分支与 `uSdrWhiteNits`；HDR10 写入 `KEY_HDR_STATIC_INFO`；设置新增「HDR 格式」。
- `202607261557`——诊断行永远显示“尚未探测”：文字在后台探测之前就已生成。改为
  `diagnostics()` 先调 `probe()`，并列出逐档失败原因。
- `202607261604`——仍然问不出原因：缓存条目只存布尔结论，命中缓存时 `probeInternal`
  不执行，`lastFailureReason` 恒为空，而否定结果 TTL 24 小时。改为诊断细节随
  `CachedResult` 一并持久化，`PROBE_CONTRACT_VERSION` 1→2 作废旧条目。SHA-256
  `fcbd2c2ca22d3d2f8faae609544889b15575522e0dab30adb8f2d3c59ae413d0`。

三份日志文件各写了多个 `## ` 小节，而发布任务只提取第一个，应用内更新说明因此被截断。

## 2026-07-27 第十一轮：色彩范围校验误杀整机 HDR

- 三星 S23 Ultra 的逐档失败原因全部是同一句
  `IllegalStateException: Encoder changed color-range from 2 to 1`——本项目自己的
  `preserveOrInstallColorKey` 抛的，与设备无关。按 D28 拆分权威归属，新增
  `FableSolExportColorRange.resolveForMuxer`：编码器报了就采纳，没报才补 limited。
- HDR 阶梯的档位名去掉写死的 `HLG`（改为 `HEVC Main10` / `AV1 Main10`），此前与独立的
  传递函数轴叠加后产生“HDR10 120fps HEVC Main10 HLG”这种自相矛盾的诊断行。
- 「DV 封装」在毫无 DV 编码器的三星上同样答“接受”，说明 `MediaMuxer.addTrack` 只认
  MIME、不构成证据；改为仅在存在 DV 编码器时显示，措辞改为“接受该轨类型”。
- `PROBE_CONTRACT_VERSION` 2→3 使被旧标准误判的机器立即重新探测。
- 新增 `FableSolExportColorRangeTest`（2 例）钉住回归。`:app:assembleDebug` 与
  `:app:testDebugUnitTest` 全绿（FableSolExport* 六个测试类共 25 例，0 失败）。未使用
  adb。阿里云 debug 更新 `202607261618`，APK SHA-256
  `2cefedc1b8c8b664d1d58f2a92b3abf3f82d4c9a6e10e5186526a5c5050d89a3`。

## 2026-07-27 第十二轮：四种 HDR 格式开放选择，杜比视界从"不做"翻案

两台机器都通了、默认都落 HDR10 之后，用户要求把 HDR 格式做成可选并在界面上说明区别，
且**必须实测能编才允许出现在界面上**；同时要求重新调研杜比视界与 HDR10+（见 D29）。

- 调研结论：Dolby 官方第三方样例 `DolbyLaboratories/dolby-vision-editor` 用标准
  `MediaCodec` + surface 输入编 profile 8.4，应用**不提供 RPU**——D27 里"没有公开接口所以
  做不了"的判断是没查证就下的，已在该条上标注推翻。反过来，HDR10+ 的动态元数据我们
  **确实提供不了**：`PARAMETER_KEY_HDR10_PLUS_INFO` 文档明确它不适用于 surface 输入模式。
- 新增 `FableSolExportHdrFormat`（HDR10 / HDR10+ / HLG / 杜比视界）与 `FableSolExportCodecEntry`；
  编码阶梯由格式自己持有，`FableSolExportTier` 增加 `hdrFormat` 字段，
  `candidatesForMode(format, …)` 取代 `candidatesForMode(transfer, …)`，
  `FableSolExportModeAttempt` 改为携带 `format`（null = SDR）。
- 杜比视界：MIME `video/dolby-vision` + `DolbyVisionProfileDvheSt` + HLG + BT.2020 +
  按像素率现算的 level（`dolbyVisionLevel`，阶梯照抄官方样例），且 level 在 64px 分享对齐
  **之后**才算。`FEATURE_HlgEditing` 的门从"transfer == HLG"收窄到"format == HLG"，
  否则 API 35+ 上 DV 会被这道门筛光。
- 静态母版元数据的条件从 `transfer == PQ` 改为 `hdrFormat.writesStaticMetadata`；
  HDR10+ 与杜比视界要求输出 profile 原样回报（`requiresExactProfile`），
  防止静默降级成 HDR10 却挂着别的名字。
- 能力探测改为**逐格式各走一遍真实编码 + 封装**，缓存改存通过的格式列表；每种失败格式
  只留第一条原因（同格式下各编码器报错通常一样，全列会把别的格式挤出可见范围）。
  `PROBE_CONTRACT_VERSION` 3→4。
- 删掉上一轮加的「DV 封装」探测：三星连 DV 编码器都没有却同样答"接受"，说明
  `MediaMuxer.addTrack` 只认 MIME，不构成证据；现在由真实编码探测取代。
- 设置页：`HDR 格式` 改为按实测结果动态生成的胶囊（贪心换行，320dp 里排不下一行），
  下方一段随选择变化的说明；选「自动」时直接写出本机会落到哪一种。存着的格式若本机
  编不出来，自动退回「自动」并同步改掉偏好。档位名带上格式，完成提示里也能看出用了哪种。
- 13 套语言新增 7 条文案。新增 `FableSolExportHdrFormatTest`（6 例）钉住自动档顺序、
  profile 严格性、DV 基层是 HLG、level 阶梯与竖幅小画布在 120fps 下必须解出非零 level。
- `:app:assembleDebug` 与 `:app:testDebugUnitTest` 全绿。未使用 adb。阿里云 debug 更新
  `202607261657`，APK SHA-256
  `44826f5eeac6ce24d06bee1cb5a1639ef21c288a4d4ec4927e2e867008bb073c`。

## 2026-07-27 第十三轮：三台设备各一个"被自己人挡住"的问题（见 D30）

- **三星 HLG 缺失**：删掉 `FEATURE_HlgEditing` 那道筛（高通编码器一个都不广告它，API 35 上
  把 HLG 候选整批筛光）。`FableSolExportPipelineSourceTest` 相应改为
  `assertFalse(encoder.contains("isFeatureSupported("))`，钉住它不许回来；同时保留
  AV1-in-MP4 的 `SDK_INT < 34` 门。
- **华为平板整机 HDR 不可用**：`eglChooseConfig` 改为四级阶梯
  （`RGB10_A2+recordable` → `RGB10+recordable` → `RGB10_A2` → `RGB10`）。
  `FableSolExportEgl.Capability` 增 `tenBitWindowConfig`，诊断行新增「10-bit 表面」一项——
  广告了 PQ 扩展不等于建得起 10-bit 表面，此前两者被混为一谈。
- **HDR10+**：确认做不到，`Encoder changed profile 8192 to 2` 是编码器在说它只产 HDR10。
  动态元数据只能应用逐帧提供（surface 输入下被 Android 明确排除）或编码器自行生成
  （该机不做）。明确不伪造元数据。新增 `FableSolExportHdrFormat.downgradeHint`，把这类
  失败翻成人话而不是甩原始异常串；`formatFailures` 为空时的措辞也从"没有编码器广告支持
  这个 profile"改为"没有编码器通过候选筛选（profile / 尺寸 / 帧率）"，后者才是真的原因面。
- **HDR Vivid**：Android 官方支持格式页的 HDR 视频格式只有 HLG10 / HDR10 / HDR10+ /
  Dolby Vision 8.4 四种，通篇没有 HDR Vivid，无法做。我们已实现的四种正好就是这四种。
- **导出通知图标**：`FableSolVideoExportService` 的两处 `setSmallIcon` 用的是
  `act_create_white`——那是"新建"的加号，通知栏里显示出来就是个加号。改为
  `act_fablesol_export_video`。
- `:app:assembleDebug` 与 `:app:testDebugUnitTest` 全绿（309 例）。未使用 adb。
  阿里云 debug 更新 `202607262154`，APK SHA-256
  `dafd6f10d8de1ba8ab3255994e82927cf11a7deb7e14fa910e9edb48816f1f5f`。

## 2026-07-27 第十四轮：HDR10+ 字节缓冲通路的决定性探测（见 D31）

用户追问"surface 模式编不了 HDR10+，其它模式行不行"。查证结果：行——字节缓冲输入是
`PARAMETER_KEY_HDR10_PLUS_INFO` 唯一被允许的模式，也是 AOSP CTS `HDREncoderTestBase`
采用的模式。D30 里"HDR10+ 做不到"的范围写错了，只对当前 surface 链路成立。

- 新增 `FableSolHdr10PlusProbe`：与导出管线**完全隔离**（不建 EGL、不碰渲染器、不写文件），
  按 `COLOR_FormatYUVP010` 配置编码器、喂一帧平场 P010、读输出格式回报的 profile。
  分「裸通路」与「带元数据」两问，以区分通路不通与载荷写错。
- ST 2094-40 载荷按 `user_data_registered_itu_t_t35()` 逐位打包：单窗口、9 个百分位、
  `tone_mapping_flag = 0`，共 387 位 = 49 字节。`FableSolHdr10PlusPayloadTest`（2 例）
  钉住固定头 `B5 00 3C 00 01 04 01` 与总长。
- 诊断新增「HDR10+ 字节缓冲」一行，仅在 HDR10+ 当前不可用时才探（可用就没有这个问题）。
- `:app:assembleDebug` 与 `:app:testDebugUnitTest` 全绿。未使用 adb。阿里云 debug 更新
  `202607262206`，APK SHA-256
  `6405abbd9721082296e589b1d79371bb688fd864037bec01646592c48995940e`。

## 2026-07-27 第十五轮：缓存签名、杜比视界 8.1、HDR10+ 判据（见 D32）

用户截图暴露了一个更根本的问题：**上一版看到的失败原因全是旧缓存**。

- `cacheSignature()` 改用 `R.string.debug_update_code`（发布任务生成的时间戳，每发一版必变）
  取代写死为 43 的 `BuildConfig.VERSION_CODE`；`peekCachedResult()` 相应改为需要 Context。
  源码契约测试新增 `assertTrue(capability.contains("R.string.debug_update_code"))`。
- `FableSolExportHdrFormat` 拆出 `DOLBY_VISION_81`（PQ 基层）与 `DOLBY_VISION_84`（HLG 基层），
  profile 常量相同、只差传递函数。`AUTO_ORDER` 按规格从高到低重排为
  8.1 → HDR10+ → HDR10 → 8.4 → HLG。`HdrFormatPreference` 新增项续在末尾以保持存储序号。
  新增 13 套语言的 8.1 说明文案。
- `FableSolHdr10PlusProbe` 的判据从"输出格式回报的 profile"改为**在输出字节里匹配 SEI 签名**
  `B5 00 3C 00 01`——HEVC 没有 HDR10+ 这个 profile，回报 2 只是在陈述码流真实 profile。
- `FableSolExportHdrFormatTest` 增至 8 例（自动档顺序、8.1/8.4 只差传递函数）。
- `:app:assembleDebug` 与 `:app:testDebugUnitTest` 全绿（312 例）。未使用 adb。阿里云 debug
  更新 `202607262220`，APK SHA-256
  `de094905d0bd9e907dadbf786164713dd3e1905c4d99b38214b8938b4bf44c10`。

## 2026-07-27 第十六轮：HDR10+ 字节缓冲通路落地；杜比视界补 profile 5（见 D33）

两台设备的实测把两个问题都定死了：三星「带元数据 码流带 HDR10+ SEI」= 通路可行；
OPPO 的 8.1 失败于 `Encoder changed color-transfer from 6 to 7`（PQ→HLG）= 该编码器只出 8.4。

- 新增 `FableSolExportP010Bridge` + `p010_luma.frag` / `p010_chroma.frag` / `p010_stats.frag`：
  离屏 RGB10_A2 呈现 → 两趟转出 P010 双平面 → 一趟归约成 32×32 亮度统计。输出目标一律
  RGBA8（ES 3.0 只保证这一组 glReadPixels 组合），一个 texel 装两个 16 位样本。
- 新增 `FableSolExportHdr10PlusMetadata`：ST 2094-40 载荷构造 + 从归约结果测统计量 +
  `containsSei()` 码流签名匹配。`FableSolHdr10PlusPayloadTest` 迁移到它上面。
- `FableSolExportEncoder` 增字节缓冲输入：`COLOR_FormatYUVP010`、`queueVideoFrame()`
  （`setParameters` 必须先于 `queueInputBuffer`）、`queueVideoEndOfStream()`、
  `hdr10PlusSeiSeen`（写样本时扫描确认）。`FableSolExportEgl` 增离屏 pbuffer 模式。
- `requiresExactProfile` 去掉 HDR10+，改以 SEI 为判据；探测与正式导出都按此。
- 新增 `DOLBY_VISION_5`（单层 PQ + IPT-PQ-c2，不向下兼容，界面说明写明取舍）。
  `AUTO_ORDER` 变为 5 → 8.1 → HDR10+ → HDR10 → 8.4 → HLG。13 套语言新增说明文案。
- `:app:assembleDebug` 与 `:app:testDebugUnitTest` 全绿（313 例）。未使用 adb。阿里云 debug
  更新 `202607262244`，APK SHA-256
  `459c5784361c7911da5fea2c77ec358afc997272cf75deadd52b16c9852b649f`。

## 2026-07-27 第十七轮：HDR10+ 仍显示为 HDR10——D33 只修了一半（见 D34）

用户反馈设置里的胶囊与自动档文本仍是 HDR10。根因是 `acceptsTenBitProfile` 的白名单没有
同步加上 `HEVCProfileMain10`：HDR10+ 申请 8192、编码器回报 2，`requiresExactProfile` 已放行
但白名单不认 2，照样判失败，于是永远进不了「实测通过」列表。

- 白名单加入 `HEVCProfileMain10` / `AV1ProfileMain10`；不影响 HDR10 / HLG（它们走相等判断）。
- `FableSolExportP010Bridge.writeInto` 返回规范帧长 `stride × sliceHeight × 3 / 2`。
- HDR10+ 的 codec entry 去掉重复格式词（曾出现「HDR10+ HEVC Main10 HDR10+」）。
- `FableSolExportHdrFormatTest` 增至 11 例，新增 profile 接受性两例。
- `:app:assembleDebug` 与 `:app:testDebugUnitTest` 全绿。未使用 adb。阿里云 debug 更新
  `202607270101`，APK SHA-256
  `41bd4ba0a8fa3801cd42779f8dfb470b4147380be5c9fd1292dd36932f1f1478`。

## 2026-07-27 第十八轮：OPPO 的 HDR10+ 与杜比视界 8.1 的定论（见 D35）

用户的 OPPO 截图来自 D34 修复**之前**的版本，其 HDR10+ 失败正是那个 bug；但同截图的独立
探测行 `带元数据 码流带 HDR10+ SEI` 不经过该校验，证明**两台设备都能编 HDR10+**。

- 杜比视界 8.1 定论为做不到：设备只广告 profile 8、明确把 PQ 改回 HLG，且 Dolby 官方样例
  发行说明只声称支持编码到 8.4。**不放松传递函数校验**——传递函数是我们画出来的像素的属性，
  放行会产出标着 HLG 而内容是 PQ 的文件。
- 新增 `vendorParameters()`：用 API 31 的 `getSupportedVendorParameters()` 直接向编码器查询
  私有参数（Qualcomm 文档站 JS 渲染抓不到，猜键名不可靠），按 dv/dolby/hdr/profile/color/
  transfer 过滤后进诊断行，作为 8.1 是否还有指望的唯一线索。
- `changed color-transfer` 类失败改为人话措辞，新增 `TRANSFER_DOWNGRADE_MARKER`。
- `:app:assembleDebug` 与 `:app:testDebugUnitTest` 全绿。未使用 adb。阿里云 debug 更新
  `202607270113`，APK SHA-256
  `d49d97c463dfb7d8429407809ab8691feb7199ca2212b3c6acb932349348cc2b`。

## 2026-07-27 第十九轮：HDR10+ 元数据性质与格式取舍（见 D36）

用户发现官方支持格式页把 HDR10+ 的元数据栏填成「静态」，追问到底是静态还是动态，以及对
本功能 HDR10+ 与杜比视界 8.4 哪个更好。

- 结论：**HDR10+ 是动态的**（ST 2094-40），官方那一格不可信；准确说法是静态母版 + 逐场景
  动态两份都有，我们的实现也是两份都写。
- 结论：**对本功能 HDR10+ 更好**，决定性因素是基层曲线（PQ 满余量 vs HLG 约 3.77 倍），
  而 FableSol 的高光正长在那一段。`AUTO_ORDER` 已是该顺序，无需改动。
- 修正 13 套语言里 HDR10+ 的说明：旧文案「动态元数据只能由设备自行生成，我们既设不了也
  验不了」在 D33 之后已与实现相反，改为「由我们逐帧从画面实测得出」。
- `:app:assembleDebug` 与 `:app:testDebugUnitTest` 全绿。未使用 adb。阿里云 debug 更新
  `202607270120`，APK SHA-256
  `895f3b6b031a7d85ad0aff401715a38cf2f0c4303b684e96c0d45ea7ff9a3a1d`。

## 2026-07-27 第二十轮：HDR 开关并入格式选择；产物带格式与真实码率（见 D37）

用户实测观察到杜比视界 8.4 有"高光出现时压暗背景"的动态适配，而 HDR10+ 没有——根因是我们
发的 ST 2094-40 里 `tone_mapping_flag = 0`。补曲线列为待办（可从已有实测统计诚实推出）。

- 删除单独的"导出 HDR 视频"开关（连带 `makeExportSwitchRow` / `ExportSwitchControl` /
  `probeHdrExportCapability` 等 106 行），并入「导出 HDR 视频格式」胶囊，首项为「关闭」。
- 指示性文字末尾追加最终格式；编码器诊断清单移到该行之后。
- `FableSolExportSink.displayName` 改为推导属性 + `tagFormat()`，文件名带格式后缀。
- `Result.Success` / `State.Done` 增 `formatLabel` 与 `frames`；新增 `State.Done.bitrateBps`
  （文件大小 ÷ 时长，CQ 档同样算得出）与共用的 `FableSolExportBitrateText`。
- 完成对话框与通知文案改为 5 参（格式 / fps / 体积 / 码率 / 位置），13 套语言同步；
  新增「关闭」选项与其说明文案。
- 源码契约测试改为钉住"只有一处 HDR 入口"。`:app:assembleDebug` 与 `:app:testDebugUnitTest`
  全绿（315 例）。未使用 adb。阿里云 debug 更新 `202607270150`，APK SHA-256
  `0baa96ccfbd6c9059b0daec6a7a01a77a9c9fcdb805c1c3774fcac66436eb4f5`。

## 2026-07-27 第二十一轮：HDR10+ 的色调映射曲线（见 D38）

用户授权做 D37 里列出的曲线。

- 新增 `FableSolExportHdr10PlusCurve`：膝点取该帧实测第 90 百分位，第一个锚点由斜率连续
  解出，其余二次缓入到 1；膝点做**快起慢落**的指数平滑（τ 0.08s / 0.80s），平滑的是意图，
  统计量仍是逐帧实测原值。目标峰值取 1000 尼特（取母版峰值等于说"不用压"，曲线就没信息量）。
- `FableSolExportHdr10PlusMetadata.payload` 改签名收曲线，写 `tone_mapping_flag = 1` 与
  膝点（/4095）、锚点个数、锚点（/1023）；载荷 49 → 64 字节。
- `FableSolHdr10PlusProbe` 自带的 ST 2094-40 写入器删除，统一用正式通路那一份。
- 新增 `FableSolExportHdr10PlusCurveTest`（4 例）钉住单调性、端点、膝点斜率连续、快起慢落；
  载荷测试增加"不带曲线仍为 49 字节"一例。13 套语言的 HDR10+ 说明同步更新。
- `:app:assembleDebug` 与 `:app:testDebugUnitTest` 全绿。未使用 adb。阿里云 debug 更新
  `202607270203`，APK SHA-256
  `6848727c15d6e6d771c370655abdebdec34f81cc2ab3b242ac0a4a2b85550012`。

## 2026-07-27 第二十二轮：漫反射白锚点改为可调（见 D39）

用户澄清观察后定位到真因：不是曲线，是 PQ 的漫反射白钉在 203 尼特——比手机自己的 SDR 白还暗，
而且低到屏幕从不需要在背景与高光间取舍，所以画面才是静的。

- `FableSolExportOptions.pqWhiteNits`（200–800 尼特，每档 25，默认 400）+
  `FableSolTuning.exportPqWhiteNits` 读写与恢复默认。
- `FableSolExportPresenter` 增 `whiteNits` 构造参数驱动 `uSdrWhiteNits`；
  `FableSolVideoExporter` 按 `tier.transfer == PQ` 选锚点，并用它算 `peakNits` 与
  HDR10+ 曲线的母版峰值——三处必须同源。
- 设置新增滑杆，**仅在选中 PQ 系格式（HDR10 / HDR10+ / DV 8.1）时显示**；
  `addHdrFormatBlock` 的回调改为携带解析出的格式。
- 13 套语言新增标签。`:app:assembleDebug` 与 `:app:testDebugUnitTest` 全绿。未使用 adb。
  阿里云 debug 更新 `202607270224`，APK SHA-256
  `4b3695a1fee2487486ff9458bed113b8e23910f577119bcfa44827bf38f8bc2a`。

## 2026-07-27 第二十三轮：修实时渲染的 ADPF 竞态崩溃；漫反射白默认值由屏幕推出（见 D40）

- **崩溃**（OPPO PMA110，`ArrayIndexOutOfBoundsException: length=0; index=0`）：
  `FableSolRowParallel.workerThreadIds()` 用的 `toIntArray()` 是"先读 size 再迭代"的两步操作，
  而列表正被 worker 并发写入。改为 `ArrayList(collection)` 原子快照后再转数组。
  **与本功能无关**，是实时渲染路径上一直存在的竞态。新增
  `FableSolRowParallelSnapshotTest` 用同样的并发形态钉住。
- **漫反射白自动默认值**：新增 `FableSolExportDisplayLuminance`，读
  `Display.HdrCapabilities.getDesiredMaxLuminance()`（自夹 300–10000 合理区间），
  默认漫反射白 = 峰值 ÷ 4，夹到 200–800；`FableSolTuning.exportPqWhiteNits` 在偏好未设置时
  返回该值。诊断新增「屏幕 HDR 峰值 · 自动漫反射白」一行。
- `:app:assembleDebug` 与 `:app:testDebugUnitTest` 全绿。未使用 adb。阿里云 debug 更新
  `202607270232`，APK SHA-256
  `4a84963ee294e98db0f1c88d348ff6f5d9176a48f122dc1426538ba65def7e94`。

## 2026-07-27 第二十四轮：高白点下曲线退化导致偏色（见 D41）

用户把漫反射白拉到 800 后，HDR10+ 产物在星芒出现时背景间歇性发青蓝；400 时不出现。

- 根因：肩部第一个控制点 `P[1] = (M − k)/(N(T − k))` 在 T 写死 1000、M = 7680 时远大于 1，
  被夹到 1 后所有控制点全为 1，肩部退化成断崖；三通道先后撞顶即偏色。
- `FableSolExportHdr10PlusCurve` 增 `targetNits` 构造参数与 `targetNitsFor()`：取屏幕声明峰值，
  下限 ≥ 漫反射白 × 2、上限 ≤ 母版峰值。
- 膝点上限追加 `(N·T − M)/(N − 1)`，由 `P[1] ≤ 1` 直接解出。
- `FableSolExportHdr10PlusCurveTest` 增两例：全滑杆 × 四档强度下肩部不得退化；
  目标峰值必须落在两倍漫反射白与母版峰值之间。
- `:app:assembleDebug` 与 `:app:testDebugUnitTest` 全绿。未使用 adb。阿里云 debug 更新
  `202607270248`，APK SHA-256
  `5ebbcaf901f5262c24de2673b0bc6e8d6b6d88e114b3a1a76f7d0a24ef109ec4`。

## 2026-07-27 第二十五轮：外部评审九条，八条修复（见 D42）

- `targeted_system_display_maximum_luminance` 改为 ×10000（单位 0.0001 尼特；27 位宽度即证据），
  新增 `FableSolHdr10PlusPayloadDecodeTest` 逐字段解码核对。
- 正式导出 commit 前加 `check(!byteBuffer || encoder.hdr10PlusSeiSeen)`。
- 字节缓冲模式强制 `COLOR_RANGE_LIMITED`（谁做的转换谁是权威；D28 只适用于 surface 模式）。
- `KEY_STRIDE` / `KEY_SLICE_HEIGHT` 回报 0 一律视为未知并退回画面尺寸。
- 新增 `requiresEglColorSpace`，HDR10+ 不再被 `anyHdrColorSpace` / 传递函数列表 / 10-bit
  pbuffer config 三处门禁拦截；离屏 EGL 不再要求 10-bit config。
- `probeInternal` 每轮开头重置 `lastSupportedFormats` / `lastCandidateFailures` /
  `lastFailureReason`。
- 恢复设置页 800ms 延后探测（D24），并随 Dialog 失效取消。
- `hdr10StaticInfo` 增 `frameAverageNits`，MaxFALL 跟随漫反射白而非写死 203。
- 分位点口径问题属实但需区分用途（定膝点 vs 写进元数据），列入遗留项未改。
- `:app:assembleDebug` 与 `:app:testDebugUnitTest` 全绿。未使用 adb。阿里云 debug 更新
  `202607270327`，APK SHA-256
  `c6da788dbeb55dd9a5b7234ee43abb9106b8eb537a2addfdf0984a56db1b2c08`。

## 2026-07-27 第二十六轮：进度条渐变、峰值可见、「高光起点」可调（见 D43）

- 新增 `DisplayUtil.setProgressBarBackground()`，导出进度条改用与调参滑杆同源的
  `SeekBarTrackDrawable` 渐变轨道，替换掉只取起点单色的 `progressTintList`。
- 指示行追加**峰值**（= 漫反射白 × HDR 强度）：掉饱和的根因是这个乘积超出屏幕能力，
  而两根滑杆各调各的，乘积不写出来用户看不见。
- 「膝点」改名「高光起点」并开放为参数（50–99%，默认 90），**仅在 HDR10+ 下显示**；
  `FableSolHdr10PlusStats.nitsAtPercent()` 在 9 个标准分位点之间线性插值。
- 13 套语言新增 3 条文案。`FableSolExportHdr10PlusCurveTest` 增一例覆盖插值与
  "起点调高则膝点上移"。
- `:app:assembleDebug` 与 `:app:testDebugUnitTest` 全绿（325 例）。未使用 adb。阿里云 debug
  更新 `202607270344`，APK SHA-256
  `e62440dcec364a7743667e80db136d9531e1e903e02361c0af828786d9273201`。

## 2026-07-27 第二十七轮：完成对话框与通知补上色彩规格

- 新增 `FableSolExportSpecText`：完成对话框与通知**共用同一处生成逻辑**，两边不会漂移成
  两个说法。
- `Result.Success` 与 `State.Done` 增 `pqWhiteNits` / `peakNits` / `highlightStartPercent`，
  由导出器按档位填——**只在真正生效时才带出去**：HLG 系没有绝对锚点，非 HDR10+ 没有曲线。
- 完成文案增第 6 个参数（色彩规格行，不适用时为空串），13 套语言同步；新增
  `fablesol_export_detail_hdr` / `fablesol_export_detail_highlight`。
- `:app:assembleDebug` 与 `:app:testDebugUnitTest` 全绿。未使用 adb。阿里云 debug 更新
  `202607270437`，APK SHA-256
  `b4f4c55d15890101f45d77f53a07fad902ca5fadc9b54447fc0f7e8ed1279d0f`。

## 2026-07-27 第二十八轮：重写设置中的 HDR 文案（13 套语言）

用户要求去掉口语与不专业表述（第一人称"我们"、破折号、"不用压""代价是"等）。重写 10 条
文案时连带发现并修正两处过期内容与一处真实缺陷：

- **过期**：HDR10 说明写死"203 尼特"，而漫反射白已是可调参数；HDR10+ 说明仍称「膝点」，
  该术语已改名「高光起点」。
- **缺陷**：Profile 5 说明里有 Markdown 粗体 `**不向下兼容**`——Android 字符串资源不解析
  Markdown，星号会原样显示给用户。
- 13 套语言全部重写；法语、意大利语撇号按资源规则转义；简繁分别用各自惯用术语。
- 顺带发现三条**死字符串**（`fablesol_export_started` 仍写着已废弃的"水体视频"、
  `fablesol_export_estimate`、`fablesol_param_export_hdr`），本轮未动，仅记录。
- `:app:assembleDebug` 与 `:app:testDebugUnitTest` 全绿。未使用 adb。阿里云 debug 更新
  `202607270448`，APK SHA-256
  `81e4558f66e6ab68e87edd31b6739a412f5af5aef6fd7c48d8f78840cbfc7aff`。

---

## 待办（首轮未做）

1. **D15 ① 的逐位门禁尚未建立。** 需要一个 JVM 单测：同一批 `FableSolFeatureFrame` +
   等间隔 1/120 合成时间戳，比对实时驱动与离线驱动逐帧的状态向量，要求 max|Δ| = 0。
   这是合入门禁，但它本身是一批独立工作。
2. **D15 ②③ 真机验收未做**（事件序列比对、观感并排比对）。
3. **码率与 CQ 默认值未标定**，当前用的是 decisions.md 里的推测值（120fps 24 Mbps）。
4. **`FableSolRealtimeAnalyzer` 预热门是否纯采样驱动尚未确认**（D13 遗留项）。
5. **HLG 上限的取舍需要真机复核**：HLG 在 SDR 参考白之上只有约 3.77 倍余量，而用户强度
   上限是 9.6。当前用线性域软肩（knee = 2.0，之上指数渐近）承接超出部分，而不是硬钳。
   这是实现时才浮现的约束，decisions.md 未记；观感需真机确认，若不可接受则改走 PQ。
