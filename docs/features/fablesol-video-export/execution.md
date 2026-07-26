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
