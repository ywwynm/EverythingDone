# Current Debug Update Notes

## 2026-07-26 - dialog 从内部滑到外面松手会误关闭（第四十版）

framework 的 `Window.shouldCloseOnTouch` 只判断 `ACTION_UP`（及 `ACTION_OUTSIDE`）的
坐标是否出界，从不记录 `ACTION_DOWN` 的位置。手指按在 dialog 内部没有控件消费 touch
的位置（空白区、纯展示文本），滑到 dialog 外抬手，就被判成「点了外部」而 `cancel()`；
起点落在能消费 touch 的控件上时反而没事，因为后续事件都交给了那个控件，
`Dialog.onTouchEvent` 根本不参与。

`BaseDialogFragment.kt` 加了文件级 private 类 `GestureAnchoredDialog`，`onCreateDialog`
由 `super.onCreateDialog(...)`（等价于 `new Dialog(activity, theme)`）换成它。它在
`ACTION_DOWN` 时用与 framework `Window.isOutOfBounds` 等价的算法（decorView 宽高 ±
`scaledWindowTouchSlop`）记下起点，起点在内则整段手势直接返回 false、不进
`super.onTouchEvent`；`ACTION_OUTSIDE` 视为起点在外，保留非 modal window 的默认行为。

全部 27 个 dialog 都继承 `BaseDialogFragment`，项目内无 `AlertDialog.Builder`、无直接
`new Dialog`、无第二处 `onCreateDialog` 覆写、也没有 dialog 改过 window touch flag 或
覆写 touch 分发，所以一处改动即全量生效。`setCancelable` /
`setCanceledOnTouchOutside(false)` / back 键 / cancel-dismiss 回调均未触及。

发布号 202607251644（versionCode 43），APK 21108633 bytes，
SHA-256 66e544a5f34857c56fc439c48bdebdf5ed6342a3624bba7e2b76ca09119c8986。

## 2026-07-26 - 录音对话框停止后侧边键点不动（第三十九版）

`stopRecordingWithoutBlocking()` 先 `recordingToStopped()`（侧边两键置可点 + 淡入 360ms），
紧接着 `setRecorderTransitionInProgress(true)` 又把它们一并禁用，于是整个收尾窗口
（线程 join 上限 600ms + raw→wav 全量抄写）里按钮正在淡入却点不动。

那个禁用原本在挡真实并发：取消走 `dismiss()` → 另起线程 `release()`，会与收尾线程里的
`startListening()` 撞车（`startListening` 没有 @Synchronized，`release()` 会释放并置空
`mAudioRecord`）；取消时删 wav 也可能早于 `saveToWaveFile()` 打开输出流，删完又被建出来，
留下无主音频文件。**改法是消掉并发本身**：收尾/重启/释放全走同一条单线程队列
`mRecorderTasks`，取消时的删除也排进去、排在收尾之后；侧边两键只按状态可点，
主按钮仍在收尾期间禁用（保存要改名那份可能还在写的 wav）。

顺带：`onCreateView` 末尾补一次 `updateControlsEnabled()`——`setOnClickListener` 会把
alpha=0 的侧边键置为 clickable，准备态下点到取消键的位置本来会直接关掉对话框。

发布号 202607251626（versionCode 43），APK 21108633 bytes，
SHA-256 efb1360fed14b18896994830f7c60f9e2535b415d4c3fad251bb8339e3cef0b5。
releaseNotes 229 字符、单节。

## 2026-07-25 - 滑杆未播段再调淡（第三十八版）

极性不动，`INACTIVE_TRACK_ALPHA_SCALE` 0.75 → 0.6（亮色 26%→16%、暗色 40%→24%）。

发布号 202607251524（versionCode 43），APK 21108633 bytes，
SHA-256 b5427423e24cba359a7e8a3dc6f440df8c963c00aba2bc0d20ea79479ea84fc0。
releaseNotes 72 字符、单节。

## 2026-07-25 - 滑杆未播段极性修正（第三十七版）

上一版把播放对话框的滑杆未播段改成 `onColor(accentBg, 0.18f)`，用户实测"淡得根本看不到"。
根因不是 alpha 太低，是**极性取错了**：滑杆压在水面**上方的天空**上，而天空 =
主题的 `colorBackground` 与记事色 72%~84% 白化版混成（`FableSolGlRenderer.environmentBase`），
**亮色主题的天空恒浅、暗色主题恒深，与记事颜色深浅无关**。深色/渐变记事在亮色主题下被
`onColor` 判成"该用白"，白线画在浅色天空上直接消失。

结论：**FableSol 场景里，压在水体上的元素按记事色取黑白（图标、涟漪），压在天空上的元素
按主题取黑白。** 现在滑杆沿用 `app_chrome_on_surface_hint` 的极性、alpha 乘 0.75
（亮色 26%→20%、暗色 40%→30%）。

发布号 202607251511（versionCode 43），APK 21108633 bytes，
SHA-256 d5490428745ee18996f8ed2ba7ad7e69976c686565f6f0d60db88813739c3edc。
releaseNotes 194 字符、单节。

## 2026-07-25 - 音频播放对话框首轮修复（第三十六版）

真机反馈四条，全修。两个是播放态 bug：

**收尾死等播放头会永久卡住**。原实现等 `playbackHeadPosition >= ringWrite` 才算放完，
但末尾不足一个 HAL 缓冲的残帧未必被播出并计入播放头——在结尾附近暂停过一次尤其明显。
现象是播放永不结束、按钮停在「暂停」图标，反复点播放/暂停才被踢动几帧。改为**播放头
在非暂停状态下停滞 320ms 即判定放完**（暂停与 seek 都重置计时器，否则一次几秒的暂停会
让恢复那一刻直接误判），并把剩余样本一次性补喂分析器。同时补上不变式「非暂停 ⇒
AudioTrack 必须 PLAYING」，依据 `playState` 硬件态而非自己的 `mPlaying` 记账。

**拖到结尾再点播放只闪一下**：一起播就 EOS。改为位置 ≥ 时长−250ms 或已播完时，
按播放键从头重放当前这条；自动续播仍只由真正播完触发。

另两条是配色：涟漪改 `installCircleRipple` + `adaptiveRippleColor`（按记事明暗取黑/白，
不再用记事色本身）；滑杆未播段给 `DisplayUtil.setSeekBarBackground` 加可选第三参
`inactiveTrackColor`（默认 null = 原行为，**其余滑杆零影响**），本对话框传
`onColor(accentBg, 0.18f)`，比 App Chrome 的 hint 色淡。

发布号 202607251455（versionCode 43），APK 21108633 bytes，
SHA-256 4f82ba8eb6b681196c789242e06d8188e72d6a93fd897825f16a0db61f494dd2。
releaseNotes 289 字符、单节。

## 2026-07-25 - 音频附件播放对话框（第三十五版）

详情页点音频附件**卡片本身**改为进入新的 FableSol 播放对话框（文件名 + TimelyClockView
计时器 + 进度滑杆 + 上一曲/播放暂停/下一曲，播完按附件顺序续播）；右侧按钮的就地播放不变，
两者互斥出声。新增 `FableSolAudioFilePlayer`（MediaCodec → AudioTrack → 按
`playbackHeadPosition` 以 512 样本一批喂 `FableSolRealtimeAnalyzer`）与
`AudioPlayDialogFragment`。**没有引入任何离线分析**，Android 侧也不具备该储备。

两个坑：**喂分析器必须按已播出的采样位置**，按解码进度喂会让水面早于声音一整个 AudioTrack
缓冲（约 100~200ms）；**FableSol 的模拟容器高度是写死的 420dp**（`FableSolSpec.HEIGHT_DP`，
只有宽度运行期实测）且 `water.vert` 按 `uViewportPx * 0.5` 对齐视口中心，所以对话框加高到
450dp 后水线会凭空上抬 (450−420)/2 = 15dp，必须用 `setContentVerticalOffsetDp(+15f)` 按回去。
走带键按用户要求不用 FAB：裸 icon + `GradientRippleDrawable(shapeOval=true)` 记事色圆形涟漪，
图标色走 `BackgroundUtil.onColor`（按记事明暗取黑/白）。

**未真机验证**，验收点见 docs/features/audio-attachment-playback/followups.md。
发布号 202607251430（versionCode 43），APK 21108633 bytes，
SHA-256 b4bca2cbffb52fb19102c81fddfab578fe1bc1053660f12587a8ec3c1dd2245e。
releaseNotes 336 字符、单节。

## 2026-07-25 - 繁体中文全量改用台港惯用词（第三十四版）

两个繁体文件本是从简体轻度转换来的，底下压着大陆用词。全量扫描替换 236 行，完整对照表
（30+ 词条）记进 docs/features/localization/preferences.md 的「繁體用詞對照表」，**此后
新增繁体文案照表选词，不要从 zh-rCN 直译**。

三条必须记住的坑：**「文件夾」必须先于「文件」替换**，否则得到「檔案夾」；**「支持」
按义项分流**（技术义→支援，「支持開發者」保留）；**「通過」同理**（「未通過驗證」保留，
means-of→透過）。「應用」→「應用程式」时先全替换再修回 `應用程式程式`，专名
「小米應用商店」单独修回。

港台分歧词各随其习惯：TW 用硬體/網路/智慧，HK 保留硬件/網絡/智能。刻意未改：點擊、
通知欄、恢復（后者在本项目同时承担 resume 与 restore 两义）。顺带修错字：標凖→標準
（TW）、恢複→恢復（HK）。

提交 f9e67eec。发布号 202607251337（versionCode 43），APK 21103357 bytes，
SHA-256 7165fcb40d38c18b729d786b3e8413a80a3c88f3fcf3df8922be1f5293b3820f。
releaseNotes 356 字符、单节。


## 2026-07-25 - zh-rHK 也全量改用「介面」（第三十三版）

用户确认港台在软件 interface 这个义项上用词一致，遂把 `values-zh-rHK` 的 26 处「界面」
也全部替换为「介面」。两个繁体 locale 现在各 26 处「介面」、0 处「界面」；zh-rCN 维持
「界面」不动。约定与已知遗留（两个繁体文件仍留着「視頻」等大陆用词，本次未动）见
docs/features/localization/preferences.md 的「繁體用詞」一节。

发布号 202607251321（versionCode 43），APK 21105413 bytes，
SHA-256 53277e820460a4e7d8ca2e84832b578e4eea3ff7f5be3b78659d6be790bd9961。
releaseNotes 120 字符、单节。


## 2026-07-25 - zh-rTW 全量改用「介面」（第三十二版）

用户裁定 `values-zh-rTW` 一律用「介面」。该文件 26 处「界面」全部替换（设置分组标题、
说明文字、帮助正文、以及一处注释掉的 item）。用 Edit 工具的 replace_all 做，**不得**用
PowerShell 改这类含中文的资源文件。

**zh-rHK 未动**：它本来就是混用（21 处「界面」+ 5 处「介面」），属于用户未点名的另一个
locale，按项目规则（发现额外候选先报告、等确认）只报告不改。约定与后续动作记在
docs/features/localization/preferences.md 的「繁體用詞」一节。

发布号 202607251318（versionCode 43），APK 21106625 bytes，
SHA-256 43a04d47f6d83481afb3c4ccd23872c087e517bd62fb545420dcfa0adbf5c3c2。
releaseNotes 107 字符、单节。


## 2026-07-25 - 两条自动播放设置文案补齐动态照片（第三十一版）

`settings_autoplay_cover_dynamic` → "在记事列表中自动播放作为封面的动态内容（视频、动态
照片、GIF）"；`settings_autoplay_detail_dynamic` → "在记事详情界面中自动播放动态内容
（视频、动态照片、GIF）"。原封面文案漏掉动态照片，而它一直在 Cover Autoplay 管辖内。
十三个语种译文同步（术语用"动态照片"，遵循 CONTEXT.md 对 Motion Photo 不用 Live Photo
商标的约定；zh-rTW/HK 沿用项目既有的"界面"而非"介面"）。

顺带把 `rl_autoplay_cover_dynamic_as_bt` 的固定 56dp 改为 wrap_content + minHeight 56dp
+ 上下 10dp padding：文案变长后德语/俄语/葡语在 16sp 下会排三行（约 66dp），固定行高会
裁掉最后一行。详情那一行在第三十版已是两行竖排结构，不受影响。

发布号 202607251315（versionCode 43），APK 21105413 bytes，
SHA-256 a11934917b97ee75ea97fc95231a99c083ac4c184f3bd78b501dec5d60f8862b。
releaseNotes 249 字符、单节。


## 2026-07-25 - Detail Autoplay 首版真机反馈四处修正（第三十版）

D17–D20（docs/features/detail-animated-playback/decisions.md）。

1. **逐一播放队列卡死**（真 bug）：首版把全部可见项排进队列，静态图片排到队首后占住
   `sequentialPlaying` 且永不发 `onAnimationEnd`，队列就此停摆。`pumpQueue` 改为循环出队
   并用新增的 `ImageAttachmentAdapter.isPlayableNow` 筛选（**以派生 GIF 是否真的存在为准**，
   不能只看 `isMotionPhotoCandidate` 这种扩展名候选，否则普通 JPEG 也算数）；生成期间落选的
   由新增 `onDerivedPreviewReady` 重新入队。另加两道放行：加载失败、拿回非 GifDrawable。
   放行必须 `imageView.post` 出去——同步回调会在 Glide 回调内重新绑定同一 View，外层返回后
   Glide 再把旧资源塞回来顶掉画面。
2. **静/动切换闪白**：Glide 起新请求时 `onLoadCleared`/`onLoadStarted` 都会把 View 置空。
   `.into()` 改用 `KeepCurrentImageTarget`（继承 `DrawableImageViewTarget`，两个回调改为
   不动画面）。否决了"把当前 Drawable 当 placeholder"（旧资源会被释放回 BitmapPool）与
   "改用 CustomTarget"（丢掉视图尺寸解析与生命周期清理）。
3. 设置项档位改为标题下一行两行竖排（`RelativeLayout` → `LinearLayout`，id 改
   `ll_autoplay_detail_dynamic_as_bt`），避免长译文挤压。
4. 全屏视频页 `iv.isZoomable` 由 false 改 true——缩放跟随本就由 `trackMotionZoom` 实现，
   只是被这一行挡着；按住播放期间多指缩放不误停（只认 ACTION_UP/CANCEL）。

发布号 202607251257（versionCode 43），APK 21104557 bytes，
SHA-256 712a5b646d6c110d8ac8918f2890cc68c55e6f298b564a89a5cf166d15b6ed37。
releaseNotes 352 字符、单节。


## 2026-07-25 - 详情页动态内容四档自动播放 + 全屏视频自动播放（第二十九版）

新特性 Detail Autoplay（ADR-0017，docs/features/detail-animated-playback/）。详情附件
网格的 GIF / Motion Photo / 视频改由四档设置 `KEY_AUTOPLAY_DETAIL_DYNAMIC` 管控
（0 关 / 1 逐一 / 2 同时一次 / 3 同时循环，默认 3 = 与改动前无条件循环等价）；视频
首次在详情接入 Thing Card Video Preview（复用卡片那份 720px 产物）。全部档位按滚动
视口生效——详情 RecyclerView 全量布局、不回收，Glide 屏外暂停在那里失效，故新增
`DetailAttachmentPlaybackController` 用 `getGlobalVisibleRect` 算露出比例 + 滞回
（进 0.6 / 出 0.3）。长按手动播一遍走 RecyclerView 级 OnItemTouchListener（item 的
OnLongClickListener 会被 ItemTouchHelper 拖拽的 ACTION_CANCEL 掐掉），与拖拽排序刻意共存。
全屏预览新增普通视频的自动播放：翻页停稳 360ms → 关键帧起 3 秒真视频（postDelayed 定时停）
→ 回静帧；长按第一次从 0 播、松手记播放头、再长按续播。顺带补齐 Motion Photo 与视频
共用的音频焦点（TRANSIENT_MAY_DUCK），并把全屏视频的静帧改为 Thing Card Video Frame。

发布号 202607251240（versionCode 43），APK 21104557 bytes，
SHA-256 b0ad7e7870341e07c8f37591e4d1c3adb79f4a08b133e3b966e1b063b61182ff。
releaseNotes 884 字符、含全屏一节。同日 202607251239 为首次发布，因日志被拆成两个
`## ` 节而只上传了前半，已重发覆盖——发布任务只取第一个 `## ` 条目，日志必须单节。

尚未真机验证（验收 1–16 见 docs/features/detail-animated-playback/plan.md），
性能护栏（同时档并发上限）留待实测。


## 2026-07-25 - 银丝强度归零后星芒不再一起消失（第二十八版）

星芒的 CPU 星光源此前直接读两个银丝外观滑杆（`uplift_crest_rim` × 活跃度、
`uplift_rim_peak` − 1），任一到下限就把波顶辐亮度场压成恒等于 1、excess 全零，
星芒整体失去光源。改为恒用银丝标定档（强度 1.0 / 峰值 3.6），银丝滑杆不再进入
星芒路径；`glare_strength` 仍是唯一静音开关，`uplift_rim_slide` 继续耦合（节奏
同源且不会把场压成零）。D222，Python 与 Android 同构。默认档逐位不变。
Python 600 帧四档星表逐位一致 + 363 测试全绿；Android 272 项 fablesol 单测全绿，
新增 FableSolStarFieldTest。

发布号 202607251054（versionCode 43），APK 21098013 bytes，
SHA-256 095a1c091554506ef43e5a482fd6874973bd914bcbb7a44841a055ae50c818d4。
远端 latest.json 的 debugUpdateCode / sha256 / sizeBytes / releaseNotes（341 字符）
已逐项核对一致。


## 2026-07-25 - 性能面板开关 ripple 修正并重发（第二十七版）

用户指出开关行右侧 checkbox 的按压水波纹未跟随当前渐变（仍为系统默认半透明黑），
撤回提交修正：checkbox 在 addView 后套 `GradientRippleDrawable.applyCheckboxRipple`
（圆形渐变纹 + 父容器关裁剪），`applyUiAccent` 的 checkbox 循环补 `updateBackground`
跟色（无渐变背景的普通 checkbox 为空操作）。修正版先装 9018f404 经用户目验通过后
重发，取代 202607251018。

发布号 202607251030（versionCode 43），APK 21098013 bytes，
SHA-256 bbe7660ea9b5ca749c8ab150dae0a5a3f2b86b58db0584b7011100fe1d530d27。
远端 latest.json 元数据与说明（246 字符）已核对一致。


## 2026-07-25 - 性能面板改设置开关，默认关闭（第二十六版）

调参 Dialog 末尾新增 debug 专属"调试"组：「屏上性能面板」开关（SharedPreferences
`show_perf_hud`，默认 false，独立于恢复默认），`WaveVisualizerFableSolHost.attachPerfHud`
改读该设置并删除 `SHOW_PERF_HUD` 常量；关闭时 HUD 回调不注册、分位数格式化整段跳过。
13 语言新增 `fablesol_group_debug` / `fablesol_param_show_perf_hud`。无视觉与水体行为
改动。全量 :app:testDebugUnitTest 全绿。

发布号 202607251018（versionCode 43），APK 21098013 bytes，
SHA-256 921bd0df78bc5d783adc0052b31666bf1bc523c6ea6a1a97e38cc9bd737f0555。
远端 latest.json 的 debugUpdateCode / sha256 / sizeBytes / releaseNotes（243 字符）
已逐项核对一致。


## 2026-07-25 - 修复银丝/星芒掉帧：rim 距离场按带预计算（第二十五版）

真机 simpleperf 定因：`writeRimContourDistance` 每帧 18816 次 `kotlin.math.sqrt`
在 debuggable ART 下走 Generic JNI 慢速通道并触发 JIT 反复入队，GL 线程 37.5%、
行 worker 47% 的采样落在 `art::Mutex::ExclusiveLock`（四线程锁车队）——单段
11.25ms，即近几版 120→50fps 的主因。修复均位级等价：①切向/法向按（带, 列）预
计算（sqrt 18816→1568 次/帧），行循环零库调用，OPD2515 实测 11.25→0.49ms（21 倍）、
带 196×97 逐位对拍测试；②星芒扫描 apex 门提前剪枝，1.23→0.35ms。HUD 第五行新增
rim/star 字段，optics 改为纯光学实体构建。无视觉改动。全量 :app:testDebugUnitTest
通过（223+1 项）。

发布号 202607250808（versionCode 43），APK 21096141 bytes，
SHA-256 abc3789a9ea5ee534f4e6c660b92477171aed72ec88be4837e0d30a6ef3b7172。
远端 latest.json 的 debugUpdateCode / sha256 / sizeBytes / releaseNotes（524 字符）
已逐项核对一致。


## 2026-07-25 - 打开屏上性能 HUD（第二十四版）

`WaveVisualizerFableSolHost.SHOW_PERF_HUD` false→true，仅此一行。debug 版录音
对话框顶部恢复显示 GL 分段耗时面板（fps / gl 间隔 p50p95 / hz+rr / vsync+skip /
drain·phys·build·draw·swap / sample·vtx·sheen·color·optics / prep·field·fair·slope
/ 物理步数）。release 由 BuildConfig.DEBUG 常量折叠消除；HUD 的分位数与格式化都在
监控自己的 HandlerThread 上，不占 GL 线程。无视觉与功能改动。看完记得关回 false。

发布号 202607250541（versionCode 43），APK 21096141 bytes，
SHA-256 d69d737743a6141724857aca1b05648daa660aacdbbc181da498512b1d234e15。
latest.json releaseNotes 已核对（313 字符）。


## 2026-07-25 - 银丝改用真实法向距离场（D221，第二十三版）

用户在 D220 版上继续反馈"小矩形"，并猜测粗细变化 / Z 轴翻滚遮挡——**两个都对，
D220 日志"既不是粗细在变也不是翻滚遮挡"的判断作废**。根因是
`insideDistance/|∇depth01|` 这个换算：orbit_z 挤压相邻行使"每 depth01 对应多少
屏幕像素"同帧内从 24 变到 200+px（FWHM 在 1.5~9.1px 间跳），且换算要除雅可比
行列式，层带倒转（实测带高到过 −6.1px）时 det→0 被 Android 钳到 1e-9、梯度爆到
1e7、distancePx≈0 → 整列层带高度上银丝全按峰值渲染成竖直亮块 = "小矩形"。
Python 侧本轮先加了兜底钳制，所以两端表现不同、复现不出用户截图。

修复：顶点分量 8 语义从 |∇depth01| 改为**到上方最近锚行的法向像素距离**（CPU 直算，
只有减法 + 一次切向归一化，无行列式无除小量），并补上漏掉的横向位移分量（orbit_x
让同列相邻行也横向偏移）。锚行存"到上方那个锚行的距离"，water.vert 按 uStartLayer
把本次 draw 的上轮廓归零。FWHM 下限 1.50→2.38px，roughness 0.062→0.047(L1)。
算法提到 FableSolGlMeshLayout.writeRimContourDistance 以便单测 + 单次并行调度。

**未采纳**：九层基准高度真实音频下 95% 时刻反序（L3 89%、L8 68% 的列带高为负），
渲染侧保序可让 L3/L8 无丝列归零，但远层水位 p95 位移 14dp/最坏 33dp——用户裁决
不动水位，只保证"可见的那段是连续的线"。

附带：FableSolGlShaderParityTest 按 "\n}\n" 切函数体，与 core.autocrlf=true 的
CRLF 工作区冲突，已在 projectFile 入口规范化行尾。

发布号 202607250524（versionCode 43），APK 21096141 bytes，
SHA-256 3030d3572a3965e90135e0a9851311f13de172e50046352a9b59ac260fc9c9fb。
latest.json releaseNotes 已核对（877 字符）。


## 2026-07-25 - 银丝解析梯度 + 1px σ 下限（D220，第二十二版）

用户续报"银丝放大是一个个小矩形拼起来、不是一条线"，并猜粗细变化或 Z 向
翻滚遮挡——两者都不是。定位到两个叠加的采样问题（新指标：逐像素
roughness = 沿线亮度一阶差分 RMS / 序列 RMS）：
① 主因是有效 σ 只 0.63px < 一个采样间隔，银丝中心亚像素位置随波形漂移，
每列只点亮 1~2 像素且逐列跳行；σ 下限扫描（L0/L1）0.63→0.295/0.243、
0.72→0.059/0.114、1.00→0.040/0.062、1.30→0.036/0.058，1.0px 是拐点
（也正是一个采样间隔）。
② 次因是屏幕导数逐三角形恒定（网格列宽仅 1.67px/2.2px），亮芯位置与 σ
按网格四边形跳变，占约 40%。FFT 确认沿线无高频颗粒（≤6px 周期仅占 0~1%）。

修复：顶点新增分量 8 携带解析 |∇depth01|（J⁻ᵀ 公式，几何含义 = 相邻行沿
轮廓法向垂直间距的倒数；两端顶点 8→9 float，差分规则对齐 np.gradient），
footprint 取常量 1px；σ = max(sqrt(width²+footprint²/12), 1.0)，峰值仍按
width/σ 归一保能量。结果相位相关 −0.548→+0.042，
关滑动后 p95/p05 3.4×→1.9×，roughness 0.295/0.243→0.040/0.062
（口径=逐列可见峰值序列，固定整数行口径不可比）。samples=1 同样有效。代价：细档 FWHM
1.25→2.25px、峰值 −14%（能量守恒）；粗档基本不变。

发布号 202607250428（versionCode 43），APK 21465857 bytes，
SHA-256 d43975a1737b2506d73edc6e544ca270a01dbee8e5a2ff150e58471ffca0b3a5。
latest.json releaseNotes 已核对。


## 2026-07-25 - 银丝亮芯移出轮廓过渡区（D219，第二十一版）

实机反馈"银丝断断续续、上边缘有黑点、细档尤其怪"。Python 探针定死根因：
亮芯画在层轮廓那一行，而该行同时是 waterEdgeCoverage ±0.5px 软过渡中心与
层带几何边界（九层带不重叠 → MSAA 对这条内部边界无效），两者相乘削到约
1/4，且削多少取决于亚像素相位——|相位|与逐列峰值相关 −0.548(L0)/−0.312(L1)，
分档峰值 0.148↔0.363(L0)、0.359↔0.892(L1) = 2.5 倍。消融排除波背暗带/闪点/
星芒；关银丝后轮廓上方剖面严格单调，无独立暗带。

修复只动共享 water.frag 的 crestRimProfile：距离换算 fwidth→梯度模长（真实
法向垂直距离，fwidth 在斜坡高估 √2）、亮芯内移 0.5×footprint+min(1.5σ,1.5)
（封顶防 2dp 档离唇线 3dp）、外侧改对称高斯衰减、σ=sqrt(width²+footprint²/12)
的像素盒卷积按 width/σ 归一保能量。uniform/顶点格式/默认值全不变。
结果：相关 −0.154/−0.029，p95/p05 5.4×→3.5×(L0)、11.4×→6.8×(L1)，
沿线最亮处仅 +12%、中位 +35%。Python 361 全绿，assembleDebug 通过。

发布号 202607250338（versionCode 43），APK 21044037 bytes，
SHA-256 3628f2a0ce9500c44748e0d9c326ce1bfcfec17ebabb0313dbcc0a8c6426e8d2。
latest.json releaseNotes 已核对。


## 2026-07-25 - 近层星芒优先（D218，第二十版）

用户裁决 L0 特效应始终最多。两项修复（双端同步、不动 water.frag）：
层阈值再乘 1−0.6×weight⁴（近层起晕偏置，L0 有效阈值 2.8→1.72，
L1/L2 仍比之前宽松），起振/稳定/位置跟随时间常数 ×min(1, 150/波速)
（快层包络折算成恒定空间距离，L0 起振 0.09→0.0625s）。三段素材九桶
实测 8 桶 L0 星帧居首。Python 361 全绿，Android assembleDebug +
fablesol 测试包全绿。

发布号 202607250244（versionCode 43），APK 21044037 bytes，
SHA-256 198878f3b5f10e9f3774106e530ed2edfaa71e854e85f7bb71a63ecedab8c860。
latest.json releaseNotes 已核对。


## 2026-07-25 - 星芒亮度随 HDR 强度同步（D217，第十九版）

星场 CPU 复算此前只用 3.6 标定档 peakBoost，星振幅与用户 HDR 强度
（上限 9.6，D204）脱钩——也是 96→129dp 针长无差异的根因。修复：扫描/
阈值/出生/主从保持标定档，仅出射振幅乘 max(1, excessScale×hdrGain)，
与银丝 shader 端超白缩放一致。S=9.6 满增益最亮星核与银丝同顶 9.6，
可见针芒 ≈ 132dp（129dp 上限生效）；SDR 与 S=3.6 逐位不变。Android
assembleDebug + fablesol 测试包 267 全绿。

发布号 202607250205（versionCode 43），APK 21044037 bytes，
SHA-256 d6b7fd5cfce930cc1429ec665975479e8783cba19241632472bb38b520c383d5。
latest.json releaseNotes 已核对。


## 2026-07-25 - 针芒长度上限放宽到 129dp（第十八版）

调参对话框"星芒"组"针芒长度"上限 96→129dp（默认 48dp 不变，双端
Spec 同步），供真机继续探索长针芒观感。

发布号 202607250152（versionCode 43），APK 21044037 bytes，
SHA-256 a28b0ef793ea66e067ce9de136f0e8621d8bcd083725165f286c3f40ae900e46。
latest.json releaseNotes 已核对。


## 2026-07-25 - 移除体光带强度参数（D216，第十七版）

body_light_strength 整项移除：实测最大强度仅 1.5% 像素有 >1/255 变化、
HDR 资格门数学死路（volume≤0.192<0.24），职责由厚度透光承担。清理范围：
参数注册、调参对话框水体透光组条目、GL 构建分支（buildBodyLight + 单色
addContourBand 重载 + 逐列 hdrEligibility 数组）、旧 QPainter 路径体光块、
13 语言字符串；Python 端同步（params/gl_optics/canvas + 测试）。
Python pytest 361 全绿；Android assembleDebug + fablesol 测试包 267 全绿。
默认画面无变化。

发布号 202607250137（versionCode 43），APK 21044037 bytes，
SHA-256 7ae50c2eafe91db9c66f408df4a6f16e6678e069e161f4ab282cbf8729a73ffc。
latest.json releaseNotes 已核对。


## 2026-07-25 - 调参对话框按特效分组（D215，第十六版）

原"质感"大组拆成水体透光（含自外观移入的 body_light_strength）/ 银丝 /
星芒 / 闪点四个独立特效组；"眩光"命名统一改"星芒"、组内标签去重复前缀
（7 项 ×13 语言重译，fablesol_group_texture 字符串移除）。参数键与已存
调参值不变。Android assembleDebug + fablesol 测试包全绿。

发布号 202607241603（versionCode 43），APK 21045065 bytes，
SHA-256 9efaeb640fb5dee377f257e9538d4f64e795d5a2af2c8f32496427c7dbc951ca。
latest.json releaseNotes 已核对。


## 2026-07-24 - 星芒性能优化（D214，第十五版）

星系统耗时回归治理：太阳柱窗口裁剪（apex 门窗外精确零，扫描列近层省
~45%/远层 ~70%）、VBO 定容 + SubData（逐帧 glBufferData 是偶发卡顿源）、
逐星图案零分配推导、轨迹整理去 lambda/装箱、模糊 tap 按 σ 收敛（±4/±12）、
眩光 program 预编译移到表面初始化（消除首星帧编译大卡顿）。Python 端另做
九层合批（小数组 numpy 固定开销主导，合批 ÷9），实测星系统每帧
1.52→0.86ms、行为逐位不变；pytest 362 全绿。Android assembleDebug +
fablesol 测试包全绿。视觉无变化。

发布号 202607241357（versionCode 43），APK 21042657 bytes，
SHA-256 6cc6561db8f7c92aa23b57315c81fbade61918a3d3db6362d2eff313aa0ff604。
latest.json releaseNotes 已核对。


## 2026-07-24 - 星芒主刃偏水平 + 逐星差异（D213，第十四版）

针表整体旋转 90°（主刃偏水平起始）；针芒重构为逐星解析精灵：朝向偏移
（±26°）/转速与相位/参差/针长由出生种子决定、终生稳定、星星互不相同
（不同视场方向穿过泪膜/晶状体不同区域的物理依据），全分辨率解析求值。
新增 glare_halo.frag（光轮+宽晕合成）、glare_needle.frag 重写为精灵；
FableSolStarField 输出带 seed（FLOATS_PER_STAR=7）。双端同步；Android
assembleDebug + fablesol 测试包全绿。

发布号 202607241325（versionCode 43），APK 21042657 bytes，
SHA-256 fcc5d9504eb8f2f4e6ade6eeeceb3a4d5b484a3fc9b5b9bb8f0d3d8df16e981b。
latest.json releaseNotes 已核对。


## 2026-07-24 - 星芒长度-强度耦合 + 宽晕去格点（D212，第十三版）

参差立体化：芒长因子同时作振幅权重（长芒亮而长、短芒暗而短——可见长度
∝ √振幅 同一支配律），参差范围 0~2.4→0~4，高档呈"修长主刃 + 弱芒"的电影
感。宽晕"小方格/雪花"= 模糊 tap 栅格伪影（σ/2 间距超过源平滑尺度，二维
梳齿格点被 ~18× 增益放大）：模糊核 7→13 权重（±12 tap）、宽晕间距 σ/4，
覆盖仍 ±3σ。双端同步；Python pytest 眩光/审计包通过，Android
assembleDebug + fablesol 测试包全绿。

发布号 202607241304（versionCode 43），APK 21042575 bytes，
SHA-256 4a3fd2ee0f8b8d5c51640aa7ca99367eb58ef3b96b23e548b8ee07705c67a25d。
latest.json releaseNotes 已核对。


## 2026-07-24 - 星芒梦幻化 + 探索档位放宽（D210/D211，第十二版）

梦幻宽晕（σ≈7.4dp 二级模糊 + glare_halo 滑杆 0.27）、星芒慢旋（±16.6° 有界
摆动 9.6/23.4s 双周期，全画面同向）与芒长呼吸（0.29Hz 值噪声）、星裙水色
（向白 0.888→0.62）；针长/线数上限 96dp/16（黄金角表延展至 16 线，默认
48/9）、远层衰减默认 1.29、新滑杆眩光芒长参差（幂指数，默认 1.6）；针芒
gather 32→48 tap。眩光滑杆共 7 项；与 Python 模拟器同日定档一致（Python 端
pytest 全绿）。Android assembleDebug + fablesol 测试包（50 类 0 失败）全绿。

发布号 202607241241（versionCode 43），APK 21042575 bytes，
SHA-256 b799f47df191a5ba976b038bb700a525d812cb894b6fa95a4595b3af0b78bda3。
latest.json releaseNotes 已核对。


## 2026-07-24 - 波顶人眼眩光星芒（D206~D209 移植，第十一版）

从 Python 模拟器移植人眼眩光星芒：CPU 星光轨迹（FableSolStarField，银边辐
亮度场逐式同构复算 + apex 门 + 层权重相对阈值 + 簇内四次方主从 + 前层遮挡
+ 瞬时同步包络/0.36s 失锚淡出）+ present 前 PSF pass（FableSolGlarePass，
半分辨率星点注入/σ1.2dp 弥散/6 线×32tap 针芒 gather + 全分辨率显示 cap
resolve，FP16 失败回退 RGBA8）。新增 glare_*.vert/frag 五个 shader（共享
七文件未动）；调参 Dialog"质感"组新增五项（强度 0.9 / 触发 2.8×白 / 针长
24dp / 线数 6 / 远层衰减 0.5，13 语言字符串）；SDR 与录音态均可见，
strength=0 逐位回落。assembleDebug + fablesol 测试包（50 类）全绿。

发布号 202607241136（versionCode 43），APK 21040551 bytes，
SHA-256 6d88c7ba74216a93c5b23e88ce3532e2e5cff63016ea1b8976054e7730933e6a。
latest.json releaseNotes 已核对。


## 2026-07-24 - 出厂默认调整：HDR 强度=上限 9.6、银丝粗细 0.28dp（D204 修订三，第十版）

用户裁定两项出厂默认：HDR 强度默认 3.6→9.6（=上限；DEFAULT_STRENGTH=
MAX_STRENGTH，旧布尔 true 迁移目标随之），标定锚解耦为固定
CALIBRATION_STRENGTH=3.6（k 换算与基准峰值表不动，3.6 档观感可复现）；银丝
粗细默认 0.6→0.28dp（两端 params 同步，新栅格第 3 格）。已手动存过设置的
设备不受影响，点恢复默认才取新默认。测试改名 CALIBRATION 锚点并新增
"默认=上限、标定锚不动"断言；assembleDebug + fablesol 测试包全绿，Python
py_compile 通过。其余与 202607231655 一致。

发布号 202607231705（versionCode 43），APK 21035324 bytes，
SHA-256 389483c5ecf3e932d1df59723be62507defff807f77323fe02dc41852534af86。latest.json releaseNotes 已核对。


## 2026-07-24 - 银丝粗细下限 0.3→0.16dp（第九版）

用户确认 0.3dp 在低密度屏的亚像素疑虑后（银丝为半高斯 σ 语义 + shader 0.5px
采样地板，脚印始终 ≥ 约 1.2px 半高宽），裁定下限放宽到 0.16dp。两端目录同步
（FableSolTuning / Python params.py），步长 0.05→0.04 保证默认 0.60 与上限
2.00 精确在栅格（46 步、默认第 11 格）。0.16 档在常见密度下各层落 0.5px 地板
= 最细且不断裂。已存值不受影响。Android assembleDebug + fablesol 测试包全绿，
Python py_compile 通过。其余与 202607231637 一致。

发布号 202607231655（versionCode 43），APK 21035324 bytes，
SHA-256 775b02a7b11d6a75f017a85a6381434430a6f8c4e8b8a3151cba6b0853cffb2b。latest.json releaseNotes 已核对。


## 2026-07-24 - HDR 强度上限 7.5→9.6（D204 修订二，第八版）

用户裁定上限提到 9.6。MAX_STRENGTH 单点改动（读取端 coerce 自动收敛旧存储，
无需迁移），滑杆 130→172 步（步长 0.05、默认 3.6 精确落第 52 格），k 上限
2.5→8.6/2.6≈3.31，第 0 层闪点/银边顶格 9.6，迎光薄处透射预算上限约 +1.15
（7.5 档的 1.32 倍，面积泛白验收专项数字已同步）。测试同步后 assembleDebug
与 fablesol 测试包全绿。其余与 202607231628 一致。

发布号 202607231637（versionCode 43），APK 21035324 bytes，
SHA-256 f2754539c78747f546329572c26de1613f938a74b0d776ed7a855bc7f68eb157。latest.json releaseNotes 已核对。


## 2026-07-24 - 恢复默认按钮纳入 HDR 强度（D204 修订，第七版）

用户真机反馈：恢复默认按钮不恢复 HDR。系 D157 时代"重置保留 HDR 设置"约定的
延续，按用户裁定废止。FableSolTuning.clearHdrStrength 连旧布尔键一起清除回落
3.6；resetAllParams 清参数→清 HDR→实时下发→刷新滑杆行。其余与 202607231534
逐字节一致。assembleDebug 与 fablesol 测试包全绿。

发布号 202607231628（versionCode 43），APK 21035324 bytes，
SHA-256 bd26af1de7729f3a6e7c691947d485224e86d27957c47ced4b47d4ba55a242a4。latest.json releaseNotes 已核对。


## 2026-07-23 - HDR 强度用户可调 1.0～7.5（D204，第六版）

调参 Dialog 的 HDR 开关升级为强度滑杆：1.0=严格关闭、3.6=既有标定档（默认，
不动滑杆与上一版逐帧一致）、7.5=上限，步长 0.05。缩放对象是各效果超白增量
（k=(S−1)/2.6），闪点/银边/连续透射/mode8 同源受控，九层比例结构与 SDR 基线
任何档位不变；旧布尔 hdr_enabled 自动迁移（true→3.6、false→1.0）。
setDesiredHdrHeadroom 改按当前强度申请（同值去重），实际峰值仍被显示实时授予
钳制。fablesol 测试包全绿（FableSolHdrPolicyTest 重写为强度模型）。待真机验收：
高档位（S≥5）面积型透射是否泛白、三档屏幕亮度下授予收缩表现。

发布号 202607231534（versionCode 43），APK 21035324 bytes，
SHA-256 1b3d31081874f9cd7228f5c5ba1212ff52106e7d6124df363208b258129c35ed。latest.json releaseNotes 已核对。


## 2026-07-23 - 远层浪尖收顶三刀 + 深层积分 15s（D203，第五版）

用户真机反馈 D202 后远两层浪尖仍较易过 TimelyClockView 中心（解析上限：CLIMAX
层距放大 + 满驱 144 + 深层超驱 41.25 ≈ 371dp）。三刀：满驱 144→129（偏好数）、
深两层超驱上限 1.25→1.0、hero_max 深两层 34/33→27/24（偏好数）；另按用户指定
deep_integral_s 14→15s（两端）。近中层阶梯/浪形/远浪注入不动。六窗口逐 hop
实测（Lose 原曲两窗、银花、说话、Lose 全2 录音域两窗）：非巨浪帧 100% ≤328
（钟底-16 线），全场峰 319.5；巨浪峰 341~369 随语境、不到钟顶 384。双端全量绿
（Python 341+26 / JVM fablesol 全部 0 失败）。

发布号 202607231329（versionCode 43），APK 21034556 bytes，
SHA-256 140dd3c402704a4182776b7f697d9f629cf83489a5e63ad904f7b69e84de1809。latest.json releaseNotes 已核对。


## 2026-07-23 - 满驱水位 150→144dp 让位 TimelyClockView（D202，第四版）

用户报告高水位时 L8 的浪常越过 TimelyClockView 中心。布局实测钟底 344 / 钟心
364 / 钟顶 384dp（420dp 容器）。推导满驱 138dp，按用户偏好数取 144（偏好清单
2026-07-23 版已入 memory/preferences.md）；巨浪振幅曾试 150 后按用户裁定回 144
（不必强到钟顶，总高随音量；满驱 96+144+144=384 恰平钟顶）。层距/斜率不动，
五档 L0 涨落 22/54/81/109/137dp。逐 hop 实测四窗口：钟心穿越全消，Lose 最强段
非巨浪帧 98.1% 低于钟底，说话全窗峰 321.7dp。双端全量绿（Python 341+26 /
JVM fablesol 285）。备选回退：偏好数 129。

发布号 202607231311（versionCode 43），APK 21034556 bytes，
SHA-256 e6efe505cf31b88f7d693b36b689e2986e7b06420774642eddb1e3568d59b0dc。latest.json releaseNotes 已核对。


## 2026-07-23 - 采集预热上限 4.5s→1.5s（D201，第三版）

用户报告开始录音后动画静止约 5 秒。核实为 D11 预热门在 AGC 关不掉的机型上每次
熬满 4.5s 上限（静音时增益抬高、底噪低频主导，两个 0.3s 早退条件均不满足），
且分析器随 startListening 全冷启动。按用户裁决只缩上限（视觉解耦/分析器复用
记为后手未采纳）：两端 STARTUP_MAX_S 4.50→1.50，超时种底噪语义不变。双端全量
通过（Python 341+26 / JVM 全量），预热门专项 4 项通过。

发布号 202607231243（versionCode 43），APK 21034556 bytes，
SHA-256 80bbc3eea580bf82b9f7839b5821ccef17f3df3129520c8bc887f98f3b0c0bf4。latest.json releaseNotes 已核对。


## 2026-07-23 - 语速调制率与录音域到达门（D198~D200，第二版）

真机三新样本暴露：音节核检测对快速连说/重复单字失明（流速慢）、录音域巨浪门
绝对阈值与门在会话微扰下混沌翻转（全2 从 4 处变 1 处且在主歌）。修复：语速改
包络调制率（2~9Hz 归一化自相关，AGC 不变）+ failover 组合律；录音域巨浪改
"编曲到达重音"评分门（D200/X1 锚点级跨会话一致），母带门逐点不变；验收契约
升级跨会话一致性（12 轨 pass:true，含两版 Lose 全录音一致性检查）；Kotlin 全
同步并顺带修正 novelty01 两端差异。Python 341+26、JVM 全量通过。

发布号 202607231224（versionCode 43），APK 21034556 bytes，
SHA-256 94c46aefba87ec7d7878d56c1fb8ff34e9e3a98e972f98fe10fd7e4577d7a8be。
latest.json releaseNotes 已核对。真机验收由用户进行。


## 2026-07-23 - 说话/音乐双锚定：五档水位、巨浪转变触发、流速双驱动（D191~D197）

用户主诉真机录普通说话就出现高水位+波浪不断起伏的"激情音乐"效果。本轮落地
plan-20260723：说话按发声用力五档锚定展示水位（潮位包络防句间塌陷、上涨限速
60.9dp/s、满驱 120→150dp）；巨浪新增"安静→偏大声/大声"说话转变分支并按语境
分级 103/137dp（正常/快速连说/喊单字不触发，音节核与 4Hz 调制双指纹否决）；
可见流速语速为主响度为辅、静息 idle_flow_ratio 0.20；人声主导度路由 + raw 谱
用力证据（AGC 鲁棒）；七境执行表 D195；说话侧相位改渐强/骤升语义。巨浪门只读
raw 轨，11 轨官方验收（含 6 首 positive-master 与 rap 负样本）发布前最终一轮
pass:true；Python 341+26、Android JVM 全量、说话文件正负样本（43.68/55.17/
109.79 三处、33/64/80s 无）全部通过。

发布号 202607231005（versionCode 43 / versionName 2.0.0），APK 21,034,556 bytes，
SHA-256 cbe317d494e5979798754e2da56ec72982dae440f8c2fa7c02ef6692c9762229。
远端 latest.json 的 releaseNotes 已核对包含本轮条目。真机验收由用户进行中。


## 2026-07-22 - 全素材高潮巨浪、录音 display/raw 双轨与全曲一致性复核（D184～D185）

用户要求《Lose My Mind》母带只能触发 4 次，并纠正 `Back To December = 0` 不是负样本：
`assets` 中所有音乐都有足以触发巨浪的高潮。还要求用修改前录制的 Lose/《银花》两份全曲
WAV，在新代码下同时比较巨浪、水位、七境、普通浪、流速、颜色及逐层主浪；不得按歌曲名、
绝对时间或单曲参数拟合，Python 与 Android 都必须走实际实时产品路径。

本轮保持 512-sample feed 批次、源声道声明和事件观测批次语义。巨浪门新增：中高水位多证据
到达；12 秒因果响度范围成熟后达到滚动 q95、`novelty >= 0.20` 且有物理攻击/音乐运动/
arousal 的低响度高潮；结构窗内独立 50ms 确认的短 downbeat。各确认不能交替累计，14 秒
可读间隔不变。正式 Python 11 轨 validator 的 14 项检查全部通过：Lose 母带/全录音严格
4 次、短录音严格 2 次，《银花》母带/全录音各 1 次，其余 6 首母带全部至少 1 次。

录音链的固定补偿拆为 display/raw 双轨：raw frame、raw 七境和巨浪 gate 保持权威；display
响度展开与 `2.0 / 0.90 / 0.65` 频谱份额校正只驱动连续动画和展示七境。缺失 display 字段
以 `-1` 回退 raw；master 逐帧恒等。更强动态展开、kinetic 平滑与全局 band magnitude
补偿都会伤害至少一个 Lose 对照，已淘汰。Python 全量测试 333 项及 26 个 subtests 通过；
Android 巨浪门定向测试 34/34 通过，全量 JVM 测试 261 项中 260 项通过、0 失败、1 项条件
探针跳过。Android 全录音产品探针与 Python 得到相同巨浪时刻，逐帧 raw/display 关键字段
除 CSV 精度级差异外一致。未使用 adb。最终发布阿里云 Debug `202607221306`（versionCode
43 / 2.0.0），APK 21,029,948 bytes，SHA-256
`c7d6f00a497e1a994e2111f7bb62d28ce322c86dca697d847c6ac7323aa3e283`；远端元数据、大小与
完整发布说明均已复核。发布后再次回放两份全录音：Android/Python 对同一输入逐帧一致；
Lose 相对母带已无明显动画差异，《银花》仍有 level/state/hero 残差。继续测试的因果 p95
自适应收益很小且伤害 Lose；即使用母带水位做 oracle，《银花》state 也只有 80.18%、wave
MAE 0.1607，因此保持发布包不变，后续需校准音或多设备/多音量独立语料。详细记录见
`docs/features/audio-visualization-fable-sol/debug-updates/update-20260722205245.md`。

## 2026-07-22 - 巨浪纯实时链路收口：母带严格四次，Android 录音严格两次（D181～D183）

用户要求《Lose My Mind》母带只在约 52～53、69～70、139、158 秒出现巨浪，且整曲必须
**恰好四次**，任何第 5 次都算失败；开头约有 2 秒静音、只录到歌曲约 1:40 的 Android
录音应在自己的音频时钟约 54～55、71～73 秒对应触发两次。《银花玉鉴逐人来》约 2:23
也要触发。用户随后明确纠正了此前“Python 母带 0 次”的错误结论，要求必须走真实实时
分析与动画路径，不得按歌曲名或时间戳拟合。

本轮先废止错误探针口径：不再把立体声母带压成单声道，不再把离线 director 的 section
混入 realtime，也不再把后到事件按声学时间全局回插。Python 音频进程现在发送每次
`RealtimeAnalyzer.feed` 的完整 `analysis_batch`；GUI 按 generation 隔离 load/seek，逐批、
逐 hop 消费，同刻先 frame 后 event。巨浪门控改为通用的 physical attack、低水位
novelty/dense bridge、section-backed phrase 与 resurgence 分层鉴权；Section 本身不直接
造浪，也不使用每段固定次数配额。

正确的双声道纯实时回放结果为：《Lose My Mind》母带 **53.664 / 69.493 / 139.424 /
157.664 秒，严格 4 次**；Android 录音 **54.507 / 72.277 秒，严格 2 次**；《银花玉鉴
逐人来》**143.360 秒，1 次**。穷尽式检查会拒绝任何额外触发。非 WAV 控制曲中，
`Take Me Hand` 3 次且最小间隔 14.955 秒，`Back To December` 与《带我去找夜生活》均
为 0 次，没有出现密集机械触发。Python 全量测试为 306 项通过，另有 26 个 subtests。

Android 的 GLES 与 Canvas 也改为保留每次 512-sample `feed` 的观测批次，实时 Foote
fallback 阈值同步为 3.6 / 12s / 2.2，`state_sensitivity` 同时传给 mapper 与 analyzer；
采集启动抑制只属于 capture profile。录音首个目标最初仍漏触，最终定位为跨语言舍入差异：
Python `round()` 对 76.5 使用 ties-to-even，而 Kotlin `Math.round()` 使用 half-up，造成
约 53 秒的 110→72.94 BPM 假换挡。BeatTracker 三处离散化统一改为 `Math.rint` 后，同一
onset 序列的 BPM/置信度逐 hop 差异归零，Android 产品探针得到且仅得到 54.507、72.277
秒两次。随后对进入巨浪证据链的定长 ring 做同类审计，统一了 7 个曾相差 1 hop 的
1.2s/2s/10s 窗口容量；修后触发仍严格为上述两次。Android 不把立体声母带算术降混后
冒充单声道 `AudioRecord` 产品路径。

Android 全量 JVM 测试 246 项通过、0 失败，1 项条件探针跳过；未使用 ADB。已发布阿里云
Debug `202607221030`（versionCode 43 / 2.0.0），APK 21,029,948 bytes，SHA-256
`680e8c118702d312789e70e64aa6580188765c6d7f1c7c830b78afc57497aae3`；`latest.json`
已复核本地 APK 哈希一致，完整 `releaseNotes` 同时包含用户要求、实现与验证。

## 2026-07-22 - 静息流速回归 0.10：数字静音不再严格冻住（D179）

用户反馈「完全安静的环境里也该有基础流速，记得之前有这个设计」。**记忆属实**：
`idle_flow_ratio`（静息流速占比）两端 + 调参 UI 一直都在，只是默认值被调成 0。
`git log -G` 逐提交追（`-S` 抓不到纯数值改动）：0.24(基线) → 0.18 → **0.0**，
7-20 `389ac62`「重构因果音画实时耦合」**有意归零**，commit message 写明「对确认的
数字静音仍严格保持零流速」，同批把 flow_gain 1.8→1.0、flow_curve 1.29→1.0、
flow_smooth_s 0.48→0.36 一起推回中性值（标定搬进 flow_policy 固定曲线）。D173 次日
重申该原则。

**触发改动的实测**：用户 12.5s 录音 RMS **−84.0 dBFS**（历史 11 段是 −30.7~−52.8，
低 31dB，约 2 个 LSB），离线回放全程 IDLE、flow01 恒 0、流速 0。判定没错，但说明
「严格零流速」在真实安静环境就会触发，不只是理论边界。

改 `idle_flow_ratio` 0.0 → **0.10**。语义 `base×(idle+(1-idle)×drive)`——**只抬低端、
满速完全不变**：静音 0→18.0dp/s（L0，15.3s 穿屏；L4 12.7、L8 8.9），安静段
48.6→63.7，满速仍 200.0。用那段录音复验：flow01 与水位仍恒 0，只有物理流速抬起来。

选 0.10 而非 0.18/0.24：flow=0 时方向场本就按 ω=√(gk) 自由传播（最长模态 46.2dp/s），
静音画面并非冻住，需留余量；且 0.10 对有声段抬升最小，不推翻 D173 刚调好的低端。

Python 297 tests + Android 227 tests 全绿。

已发布阿里云 Debug `202607220649`（versionCode 43 / 2.0.0），APK `21029948` 字节，
SHA-256 `4355e525029cc86dbf0c478d688c0e760873e6cd8cbd62bb03cd782462096e38`；
`latest.json` 已确认含 `releaseNotes`（1490 字）。

**⚠ 两条待办**（见 followups）：① 那段录音 −84dBFS 的成因未查——正常手机底噪应在
−60~−70dBFS，且是 D175 改采样率协商后的第一段录音，需排除增益/遮挡/降噪；
② D173 已在 idle=0 前提下抬过一次低端（trim 20dp/s、锚点 24→42），现在两条抬升叠加，
若安静段过于活跃**优先回调 trim 或锚点，不要把 idle_flow_ratio 重新归零**。

**真机重点**：①完全安静时水是否在缓慢流（目标「看得出在走、不抢戏」，15s 穿屏；
太慢可调 0.18=8.5s，太活跃收到 0.05）；②安静但有声时是否过于活跃（本版唯一可能过头
处）；③大声时应与上一版**完全一致**（满速未动）。

## 2026-07-22 - 真机四修：DC 污染、冠部 mask 覆盖、流速惯性、水位软饱和（D178）

用户在 `202607220358` 上报四个问题。**前两个同源**：巨浪的影响被错误归类。

① **触发巨浪整幅画面瞬间平移**（真 bug，D177 放大了它）。`composeRow` 用整层 216 列
均值做 DC 去除，而巨浪虽出生在 560dp 画外，支撑 960dp 的侧翼盖住网格最右 84 列——触发
帧突然多出最高 112.8dp 的内容，`layerMean[0]` 跳变，整层平移。D177 前跳 6.97dp、
D177 后 **15.64dp**（画面 3.7%）。修复踩了两个坑：(a) bias 不只是巨浪自身隆起，还要
算冠部压掉背景那部分（只扣前者残余仍有 23dp）；(b) **这个均值有两个消费点**——compose
去 DC 与渲染的深度基准高度（`gl_scene.means` / `FableSolGlRenderer.layerMeans` + Canvas
回退），只改前者位移只从 29.4 降到 23.2dp，**基准高度那路才是大头**。两端各收敛出统一
取值口 `layer_dc_dp()` / `fillLayerDcDp()`。

② **巨浪平顶不平**（用户判断正确：不是巨浪自己的问题）。`background_keep` 只作用于
锚点 detail，**方向场与 Gerstner 轨道在锚层之后合成、从未被同一 mask 覆盖**，平顶上
浮着 9 个方向模态(~14dp) + 波包(2~8dp/个) + ±10dp 轨道 ≈ 画面高 5%。mask 现逐列发布给
surface，在软饱和**之前**乘进 eta/orbit。倾斜 lag 是整体斜面，不参与压制。
用户要的「已生成的浪完全不变」**未做**（会让水面退化成光滑曲面），记 followups。

③ **流速瞬间停滞**：`CriticalSpring` 半衰期对称 0.18s。改非对称——加速 0.18s 不变、
减速 **1.80s**。半衰期切换时位置与速度连续，只变加速度。

④ **高响度水位过高**。先纠正前提：**水位与七境档位无关**，`level_goal=160×water_drive`，
档位只拉层间距且 L0 不受影响；用户感到的「PEAK 水位高」实为响度高。满响度 L0 静水面
96+160=256dp + 巨浪 144dp = 400dp，顶满 420dp 容器。按用户裁定改为满响度落 **216dp**，
且**不走钳位**：`S·w/sqrt(1+(S·w/L)²)`，S=160（低端斜率不变）、F=120、L≈181.42。
实测 w=0.01 斜率 159.99、w=0.5 从 80→73.2、w=1.0 恰为 120。钳位会让高响度整段贴同一
高度、失去分辨。

Python 297 tests + Android 227 tests 全绿（两端各新增契约测试，Android 那条还断言
「未修正的原始均值确实跳了」，否则测试是空的）。

已发布阿里云 Debug `202607220531`（versionCode 43 / 2.0.0），APK `21029948` 字节，
SHA-256 `af0fc87a62eb1d5fc0522374715ea551f9612df4d53fb4496dbae201c4f32f56`；
`latest.json` 已确认含 `releaseNotes`（1680 字）。

**真机重点**：①触发/离场是否完全不再跳（本版最确定的修复）；②平顶是否明显更平；
③大声停下后流速是否滑停（1.80s 是估的，太拖或太急都好调）；④大声时水位是否还顶画面、
且**安静时水位不应被连累变低**（低端斜率没动）。

## 2026-07-22 - 巨浪轮廓拆成平顶 + 侧翼：顶宽与陡峭度解耦（D177）

用户反馈「巨浪有点尖、还需要更宽平」，并怀疑宽度定义与人眼感知不符。量化确认且更极端：
`WIDTH_DP=840` 是**支撑全宽**，90% 等高线只有 **207dp**、按斜率定义的顶部平坦区只有
193dp——**比 276dp 的 dialog 还窄四分之一**，浪峰经过时画面必然同时装下峰顶和两侧下坡。
全程 2.82s 里「宽平」观感只有 0.52s。

**换 Hann 无效且更糟**：统一到「相对支撑全宽」口径后 Hann 顶宽 0.205W < quintic
0.2466W，达到同样顶宽需支撑 1347dp、入场从 1.08s 涨到 1.72s。根因不是选错窗——任何
**单尺度**解析窗的顶宽都是 W 的固定倍数，「更宽的顶」必然等价于「整体放大」。因此改为
**平顶 + 两侧 quintic 侧翼**（Tukey 窗的 C² 变体），`PLATEAU_DP=120` / `FLANK_DP=420`；
`PLATEAU_DP=0` 逐点退回旧轮廓，是超集不是替换，两端各有测试钉死。

出生/离场判定改用 `visible_half_dp = P/2 + F×0.8433`（3% 等高线）而非支撑边界——侧翼
最外 66dp 高度不足 4.3dp，看不见却白花 0.17s。`MAX_HEIGHT_WIDTH_RATIO=0.24`（对支撑宽
取比，会随平顶增长而失效）换成 `MAX_FLANK_SLOPE=0.9`，`0.9×420/1.875 = 201.6dp` 与旧
`0.24×840` 数值相同。

效果：顶宽 193→**327dp**、入场 1.08→**0.91s**；侧翼陡峭度 32.7° 与振幅上限 201.6dp
逐位不变。Python 295 tests + Android 227 tests 全绿。**未跑** `grand_wave_audio_validation`
（需仓库外母带，`CAPTURE_WINDOWS` 已陈旧）。

**过程中的错误值得记**：首轮对比 Hann 与 quintic 时把 Hann 的「相对半宽」当成「相对
全宽」，得出「Hann 顶宽 41%」的反向结论并据此建议过换窗。跨函数比形状，第一步必须把
量纲归到同一分母。

已发布阿里云 Debug `202607220358`（versionCode 43 / 2.0.0），APK `21029948` 字节，
SHA-256 `48cd3957dc3674bb1bf73318e79ef8ba05aa2a8c0c57fbdc5af3a6ad1b25cf6a`；
`latest.json` 已确认含 `releaseNotes`（1592 字）。

**真机重点**：巨浪是否明显变宽平（本版唯一目的，入场那 0.17s 大概率感觉不出来）；
巨浪进画面时右缘那段 3.6dp 抬起**是否能看见**（能看见=裁多了）；平顶期间画面是否
**发死**（高位从 0.52s 涨到 0.83s，而峰顶下 L0 只剩 12%，已记 followups，目测前不动）。
其它浪与录音链路一律没碰，若有变化即回归。

## 2026-07-22 - 录音采集率改为运行期协商、优先 48kHz（D175）⚠改了录音文件格式

用户第三段录音（开 dialog 后立刻录，前导极短）两端仍不一致。三条路径把问题劈开：
Android 实时 3 道巨浪、Python **实时链** 3 道（与 Android 98.5% 一致）、Python
**离线路径**只有 1 道——**不是 Android vs Python，是实时链 vs 离线路径**。

① **离线路径两处缺陷（模拟器侧，已修）**：`analyze()` 把因果特征 previous-sample
hold 到 librosa 的 43.07Hz 网格，而实时 hop 是 93.75Hz，**hold 到更粗的网格 = 丢掉
一半 hop**，punch/energy_rising 的峰值被采样漏掉（代码注释却写着 "the exact realtime
hop stream"）；`OfflineDirector.drive` 每渲染帧只喂最后一个 hop，而 Android 一直是
逐个消费。修完后离线 ≡ 实时（同采样率下相关 1.000、巨浪 1 vs 1）。缓存版本 44→45。
**顺带发现 `tools/av_probe.py` 绕过 `drive()`、完全不回放 onset**——此前几轮离线测量
都偏低，别再用旧结论。

② **采样率：用户提出"为什么不是让 Android 改 48kHz"，采纳**。同一音频 44.1k 出 3 道
巨浪、48k 出 1 道。改设备而非改工具的理由：现代 Android HAL 原生 48kHz，请求 44100
会插一层重采样抹平瞬态（巨浪门恰恰靠瞬态判定）；且 D1~D174 全部标定都在 48kHz 上做，
改模拟器等于让工具偏离自己标定出的参数。实现为运行期协商
`RECORDING_SAMPLE_RATES = [48000, 44100]`——**不能直接改常量**：不支持时
`getMinBufferSize` 返回 ERROR_BAD_VALUE，旧代码 `if (bufSize <= 0) return` 是**静默
录不到音**。实际值贯穿三个分析器与 WAV 头。顺带修 `WaveAudioAnalyzerOpus` 按 44100
写死的 YIN tau 上下界（48kHz 下音高范围会从 75~600Hz 偏成 82~657Hz）。

**⚠ 录音文件格式变成 48kHz（约大 9%）**，老录音不受影响。真机首先要验的是**采集本身**：
能否正常录、存下来的音频有无变调变速（变调=协商率与 WAV 头对不上）、DEBUG 日志
`AudioRecord source=... rate=...` 实际协商到哪个、另两个可视化样式（原版/Opus）是否正常。
出问题直接回滚上一版 `202607220046`。本版**没有改任何动画参数**。

验证：48kHz 同源输入下双端 water/flow/level 相关 **1.000**、`level` mean|Δ| **0.021dp**、
巨浪 1 vs 1、PEAK/CLIMAX 一致 97.7%。Python 287 tests + 巨浪验证 8/8；
Android assembleDebug + 全量单测通过。

已发布阿里云 Debug `202607220210`（versionCode 43 / 2.0.0），APK `21029948` 字节，
SHA-256 `8e91e1c65cdfa045451bc4c5f6a5ea2739250bb0e7e8ad27a0ca3cda4e891e11`；
`latest.json` 已确认含 `releaseNotes`（1622 字）。

**待办**：`grade_drive01` 两端仍有 0.05 均值差（相关 0.971），怀疑 Python 侧可选人声
模型路径，已记 `followups.md`。

## 2026-07-22 - FableSol 水位贴地过渡与限速软饱和（D174）

用户新录音 `20260722001826.wav` 复现"Android 一上来就巨浪、水位几乎一直满、涨到
一半卡一下"，Python 打开同一文件却几乎没反应，并自问"是不是录制时读到的音频跟最终
wav 不一样"。**确实如此，而且是关键**：`AudioRecorder.RecordingThread` 从
`startListening()`（录音界面打开）就喂 FableSol 分析器，却只在 `mIsRecording`
（按下录音键）之后写 raw 文件——**分析器听到的比 wav 多一段前导**，底噪、归一化中心、
七境状态、巨浪 episode 全在那段没录进去的音频里建立。对照实验（同一音频只改前导）：
无前导最多 CLIMAX 0.9s；接 12s 安静前导变成 CLIMAX 3.3s、几乎全程满水位。**拿 wav
回放对比真机本身不成立。** 分析器先于录音运行是有意的，维持现状。

由此暴露并修复两个真实缺陷：
① **水位缺贴地过渡**。水位走固定绝对刻度，采集档给低频 +18dB 搁架、整体 +10.5dB，
房间轰鸣的短时响度足以落进满刻度区；此前唯一拦截是 A 计权静音门，门一开水位就冲到
~1.0。实测同一时刻两条时间线电平只差 0.3dB、底噪差 2.3dB，却一边静音一边水位
156.9dp。现按"高出 A 计权底噪多少 dB"做 smoothstep 折减（14dB 渐入）。
② **限速器在抖动**。`CriticalSpring` 的 33.6dp/s 限速是对输出硬钳位并改写 velocity，
下一帧弹簧又算出更大位移，逐帧在"贴限速/弹簧自由"之间跳：**33.2% 的帧贴限速、
|加速度| 反复 ±350dp/s²（峰值 434）**，正是"卡一下、抽搐一下"。改用已有的 `soft_limit`
（C∞）限制每步位移后**贴限速帧 33.2% → 0%**。

**回退记录**（勿重走）：试过把启动窗口底噪播种改成"取窗口最小值"（太乐观、门更松）、
给底噪加"学习期快时间常数"（门抬高、把该有的反应也门掉）。真正缺的是水位的连续过渡。

修复后双端同一 wav：PEAK/CLIMAX 档一致 **100%**，water/kinetic/flow 相关
0.999/1.000/1.000，`level` mean|Δ| **0.48dp**（上版 3.06dp）。

已发布阿里云 Debug `202607220046`（versionCode 43 / 2.0.0），APK `21029948` 字节，
SHA-256 `beaaf9108b0e79fd3f3ce3c992a224306a1c3585fb654bbf4e86cd5efdd201a4`；
`latest.json` 已确认含 `releaseNotes`（1484 字）。

**踩坑**：用 `(Get-Content x.py) | Set-Content -Encoding utf8 x.py` 删两行，把
`features.py` 一千六百行的中文注释全毁（PS 5.1 按 ANSI 读 UTF-8 再写回，无映射字节
变 `?`，**转码不可逆**）。改动全部未提交，只能 git checkout 后手工重放本轮八处编辑。
此后改文件一律走 Edit/Write，批量改写用 Python 脚本。

**待真机验证**：安静房间里水位是否还会自己撑满；说话时水位上涨是否平顺、有无"卡一下"；
正常说话的水位是否仍够高（贴地折减只削贴着底噪的声音）；上一版五项是否保持。

## 2026-07-22 - FableSol 五项观感修正（D173）+ Python/Android 实时链 parity

用户五条反馈，逐条用真实音频离屏回放定量确认后再改，Python 定稿、Android 同步。
① 安静段第 0 层实测只有 1.8dp/s（穿屏 176 秒）：听感微调 10 → 20dp/s、渐入区间
0.25 → 0.10，低端锚点 24 → 42dp/s，高端几乎不动，数字静音仍严格为 0。
② 巨浪恢复「每 episode 最多两道」，只压无结构支撑的 repeat 通道（段落通道不限次），
本地到达攻击门 0.28 → 0.40；评审曲目回到 4 次、录音版 1 次。
③ **确认静默的 2 秒里状态机原本直接 return**，档位被冻住（实测 PEAK 驻留 2.7s 而期间
平均响度仅 0.17）；改为照常解码只喂零证据，并要求 LIFT/CLIMAX 只能在「正在充能」时
点亮。深层长积分 30s → 14s（原本第 8 层比第 0 层慢 20.4 秒）。
④ 三个尺度组共用一个输运速度、而各自载波按 `c=sqrt(g/k)` 走，**包络从自己的波峰上
滑过去**（L0 安静时 142 vs 93dp/s），浪原地长高变矮；改为逐组按自己的相速度输运，
谱宽 4.4 → 2.9 倍，声像几何位移限速 9dp/s。
⑤ CLIMAX 波峰 height/width 的 p90 到 0.134（Stokes 破碎线 0.142），按每组最短模态
波长设振幅上限 0.085，p90 降到 0.071、可见起伏只掉 2~5%。

**parity 教训**：新增两端同口径离线回放探针（Android `FableSolAudioTimelineProbe`
JVM 单测 + Python `tools/rt_probe.py`），把同一个 WAV 各跑一遍逐帧比对。`loud_db`
两端逐帧完全相同 → FFT/计权/频段/采集调理都在 parity；唯一实质差异是 **Python 缺了
Android 的采集启动抑制与底噪播种**，−51dB 房间底噪被当成音乐几十秒（K01 0.83、
水流 130dp/s）。回移后状态一致率 73.0% → 92.9%、水位差 10.5 → 3.1dp、巨浪 2 vs 1
→ 1 vs 1。**耦合逻辑两端本就一致**，用户觉得「Python 正常」是输入域差异所致。

性能：`sim.update` 11.1 → 12.0µs（逐组包络输运把索引循环从 1 遍变 3 遍），占 120fps
预算 0.15%，其余阶段在噪声内。Python 287 passed，Android 223 项 0 失败。

已发布阿里云 Debug `202607211610`（versionCode 43 / 2.0.0），APK `21029948` 字节，
SHA-256 `3ac98b7775f0160ca9816e866b17d4a95b9602618b271191e82df9a4e576a8b3`；
`latest.json` 已确认含 `releaseNotes`（2112 字）。

**待真机验证**：安静时水面是否明显在流动；突发声之后档位是否 1 秒内离开 PEAK/CLIMAX；
高潮段的浪是否变圆变宽；已显示的浪是否还在原地长高变矮；整曲巨浪密度。
水位仍会因 3 秒响度窗撑住约 3 秒（ADR-0015 既定刻度，本轮未改）。

## 2026-07-21 - FableSol CPU 算法优化第一、二批（10 项，无视觉/物理改动）

按 `docs/features/audio-visualization-fable-sol/plan-2026-07-21-cpu-optimization-batches-1-2.md`
一次落地。第一批位级等价：顶点装载削减（含每顶点一次的 `hG/2` 除法与 19012 次/帧的
`require` 提到循环外）、派发合并（19 → 约 10 次/帧）、方向场循环互换（模态外层 → 列外层，
三数组约 12 趟 RMW → 1 趟写）、纵深均值互换、分配清理、微项。第二批确定性并行：行并行器
新增小任务入口（total=9 也能并行），光学几何 / 合成段 / 物理子步三处按层并行，输出
字节级或逐位相同，各留串行回退开关。

新增永久回归：方向场新旧路径逐位对拍、光学并行与串行逐元素 `floatToRawIntBits` 相等、
两路银泽共派发逐位相同、层任务体不得触碰层外可变状态的结构门禁。全量 223 项 0 失败。

**实测教训**：把 `ls.heroShiftedX` 人为改成 `layers[0].heroShiftedX`（真正的跨层共享写）
后逐位对拍**没有失败**，而同位置 1e-13 的确定性差异会被立刻抓住——harness 是灵的，只是
线程交错不保证发生（探针实测 worker 确实承担了 3/4 的层任务）。**竞态无法用单测证伪**，
层并行的安全依据是逐项共享写审计 + 结构门禁，不是对拍绿灯。

已发布阿里云 Debug `202607211215`（versionCode 43 / 2.0.0），APK `21029948` 字节，
SHA-256 `394fbe150b8556fdd0c010faf5fecd80a76fd638795600ca809c7e5c0dbfcd18`；
`latest.json` 已确认含 `releaseNotes`。

**待真机验证**：HUD 第三行 `work p50/p95` 与 `sample`/`vtx`/`sheen`/`optics`/`comp`/`phys`
分段，必须同派发速率对比。目标 work p50 5.3 → 约 3.5ms。`limit` 现恒为 `0.0/0.0`
（已并回方向场派发）属预期。

## 2026-07-21 - FableSol 达成 120fps 并转攻 p95（第六～九批）

关键突破是 **ARR 省电平衡投票**：Android 15 的自适应刷新率默认给窗口投「省电平衡」票，
把对话框里的普通 View 压到 NORMAL（约 60Hz）。`window.isFrameRatePowerSavingsBalanced
= false` 一行让真机达到 **119.8fps**（`vs 8.3/8.3`、`grid 8.3`、`skip 0%`）。

此前依次修掉三个棘轮，全部是「读当前状态再据此请求」形成的自锁环——这个坑本会话踩了
三次，代码里已写明：
1. `postFrameCallback()` 放在 8ms 渲染之后 → SF 给「请求时刻之后的第一个唤醒点」，
   系统性错过 → 帧间隔被 vsync 栅格量化成恒定两周期；
2. 节拍器目标取自 `Display.getRefreshRate()`（含 override/renderFrameRate）→ 被压到 60 后
   只按 60 提交 → 继续喂给系统。改取 `Display.Mode.getRefreshRate()`；
3. `Surface.setFrameRate` 的投票值取自**当前**模式刷新率 → 面板在 60 时就投 60。
   改取 `Display.getSupportedModes()` 里同分辨率下的最高刷新率。

另确认 SurfaceView 子图层从未投过帧率票：窗口 `preferredRefreshRate` 管不到它；
`View.setRequestedFrameRate` 不向子 View 传播，且窗口图层被 ViewRootImpl 以
`FRAME_RATE_SELECTION_STRATEGY_SELF` 禁止下传。

**剩余问题是 p95**：`work` p50 5.6ms、p95 10.3ms，预算 8.33ms。各段 p95/p50 比值均约
1.8~2×，**尖峰是乘性的**（CPU 降频/调度抖动），不是 GC 这类加性事件——因此砍 p50 能
等比例砍 p95。第四个棘轮闭合在 SF 的内容检测里：越线漏帧 → SF 判定 60fps 内容 → 降节拍
→ steps 变 2 → 出不来。

本轮落地：音频分析缓冲改定长复用（消 5.3MB/s 垃圾）、`sample()` 只对渲染窗口求值
（`rs` 恒为 0 证明单调修复从不触发，故裁窗零画质代价；桌面 172→97.9µs，−43%）。

已发布阿里云 Debug `202607210835`，SHA-256
`7330c33dcb7194aba1517f949856a114c3510e5c69d9fdf173db7caf1a86cff2`。

**待办**：`vtx`（约 1.1ms）+ `sheen`（约 0.2ms）的 GPU 化——节点纹理 + 顶点着色器内
Hermite 重建（shader 内无超越函数，误差约 1e-4 px）。用户已授权同时修改 Android 与
Python 蓝本。注意：**在顶点着色器里解析求值方向场不可行**，相位达 44~58 rad 而 GLSL ES
只保证 `[−π,π]` 内精度，会违反 D40。

## 2026-07-21 - FableSol 第六批：锁死 60fps 的三个成因（主因是自引入的顺序错误）

第五批后真机 59.8fps / 16.7ms，`hz 120.0`、分段合计仅 8.0ms（预算 8.33ms）。
35 个 agent 的排查（29 条候选、复核后成立 8 条）定位三个叠加成因：

1. **主因（第五批自己引入）**：`Choreographer.postFrameCallback` 被放在约 8ms 的渲染
   **之后**。在回调体内 post 时 `doFrame` 已清 `mFrameScheduled`，会当场向 SF 发一次性
   vsync 请求，SF 给「请求时刻之后的第一个唤醒点」。申请时刻 `vsync+8.2~8.5ms` 越过
   `vsync+8.33ms`，只能拿再下一拍 → 间隔被 vsync 栅格量化成恒定两周期。这解释了
   "work 8.0ms 却恰好 16.7ms 且零抖动"，也解释了棘轮效应（掉下去只需 +0.03ms 噪声，
   爬回来要 −0.3ms，因为子步 1→2 会再加 0.3ms）。**修复：把申请提到渲染之前。**
2. `Display.getRefreshRate()` 的取值顺序是 `refreshRateOverride → renderFrameRate →
   mode.refreshRate`，前两者是"系统认为本应用需要多少帧"。拿它当节拍器目标 = 让系统的
   降频决策反过来喂给自己，形成闭环。改取 `Display.Mode.getRefreshRate()`（物理模式速率）。
3. SurfaceView 的子 SurfaceControl 图层从未投过帧率票。窗口 `preferredRefreshRate`
   管不到它；`View.setRequestedFrameRate` 不向子 View 传播，且窗口图层被 ViewRootImpl
   以 `FRAME_RATE_SELECTION_STRATEGY_SELF` 禁止下传。改为直接对
   `SurfaceHolder.getSurface().setFrameRate(...)`（API 30+）。

已排除：`eglPresentationTimeANDROID` 传过去时刻（过期时刻即刻 latch，不会 PRESENT_LATER）、
温控（work 未越预算且当时无 `th` 实测值）、`PHYSICS_DT` 双稳态（定步长累加器保证长期守恒，
真正随子步翻倍的只有 0.3ms，是棘轮的增量项而非独立成因）。

HUD 新增一行 `vs / arm / skip` 与 `hz A/B`（系统下发速率 / 物理模式速率），可一次性区分
"系统没按 120Hz 派发""回调到得太晚""自己跳了帧"。

已发布阿里云 Debug `202607210549`（versionCode 43 / 2.0.0），APK `20939764` 字节，
SHA-256 `ec90b8f4f2b2515a0c3d661a75d2924c9358a6fb0387dc10d2eda79a09324444`；
`latest.json` 的 `releaseNotes` 字段已回读确认存在。

## 2026-07-21 - FableSol 第五批：GPU 迁移设计审查 + 单调归约折叠

第四批后真机 59.3fps（16.9ms/帧）：`phys 6.1 → 1.4ms`（主浪/环境波相位递推奏效），
GL 线程 CPU 合计 8.2ms，`draw 0.4 + swap 0.3` 说明 GPU 几乎全闲。用户提出"当初迁
OpenGL ES 时以为会把运算搬进 shader"，并裁定**两端（Android + Python 蓝本）都可以改**、
设备面板为 **120Hz**。

49 个 agent 的双端设计审查（42 条候选、复核后成立 9 条）关键结论：

1. **在顶点着色器里解析求值方向场不可行**：相位达 44~58 rad，GLSL ES 只保证
   `sin`/`cos` 在 `[−π, π]` 内精度，几何会变成厂商相关，正面违反 D40。
   正确设计是**节点纹理 + 顶点着色器内 Hermite 重建**（着色器内无超越函数，
   误差约 1e-4 px，远低于 D139 已接受的 0.035px）。
2. **整体搬迁不成立**，卡在闭环：银泽滤波（整网格 7 趟）需要 CPU 全部 97×196 坡度
   → 坡度是 worldEta 的 Hermite 导数 → CPU 必须重建 → 坐标顺带就有了。打断闭环
   必须把滤波也做成独立离屏 pass。
3. **共享 GLSL 不是障碍**：`gl_renderer.py:61-67` 把 `#version 300 es` 改写成
   `#version 330 core` 并剥离精度限定符，两端共用一份源。但新增 uniform 有 D164 风险
   （GLES 链接器裁掉未使用 uniform → `check(location >= 0)` 抛出 → 静默切 Canvas）。
4. **8.33ms 预算尚未证实**：`gl 16.9ms` 与分段合计 8.2ms 之间有 8.7ms 无归属，
   "面板被压回 60Hz"与"120Hz 但略越预算导致每两次 vsync 一帧"都自洽。

本批只落地纯 CPU、逐位一致的部分：两处整行单调归约折叠（利用
`min_j bound(step_j) ≡ bound(min_j step_j)`，因 bound 对 rawStep 单调不减，IEEE-754 下
逐位成立）、顶点循环的重复元素读/死存储/逐顶点除法。另加 `hz`（面板实际刷新率）与
`rs`（单调修复触发行数）读数以判定上述第 4 点。

**未采纳审计的 S1-e（波包包络窗口化）**：它漏了 `repairOrbitRowMonotone` 扫描整行
216 点，窗口外的陈旧值会改变行缩放比例进而影响画面，不是逐位一致。

已发布阿里云 Debug `202607210424`（versionCode 43 / 2.0.0），APK `20939764` 字节，
SHA-256 `944bb2fac135654469aa0a153b1e47a9f181622315c03d35ea9a6463c3f49974`；
`latest.json` 的 `releaseNotes` 字段已回读确认存在。

## 2026-07-21 - FableSol 第四批：主浪/环境波相位递推（帧率与录音相关的成因）

第三批后真机 60.1fps（29.6 → 16.6ms/帧）。分段读数解开两件事：

1. `field 22.7 → 0.6ms`、`limit 0.7ms`——上一版那 22.7ms 几乎全是软饱和，**即便
   已把 `Math.cbrt` 换成纯算术**。说明 `Double.doubleToRawLongBits` /
   `longBitsToDouble` 在 debuggable ART 下同样没有内联，每次立方根仍要付两次慢调用；
   二项展开快速路绕开它们后塌到 0.7ms。**结论：debuggable ART 下任何 `java.lang`
   的位操作/数学静态方法都不能假定是内联的。**
2. 瓶颈转为 `phys 6.1ms`，其中 `wave 0.30`、`surf ≈ 0`，约 5.8ms 落在 `perFrame`。
   用户观察到的"帧率跟录音相关"由此解释：主浪空间采样有"能量过低即早退"，静音
   不花钱、有声音满负荷。

落地审计 1B-1/1B-2：主浪六模态（每帧 9×216×6×2 = 23328 次 libm）与环境波四模态
（9×216×4 = 7776 次，且无早退）在等距网格上改相位旋转递推，libm 降到常数级；
累加顺序与乘法结合序保持不变。附带免疫 `phase` 无界累加进入 libm Payne-Hanek 慢路径。
安全网是全量等距校验——第一版只做"均值 + 中点"抽查，被"整体等距、只有第 40 点被
推移"的测试网格击穿，改为全量扫描（216 次比较，相对省下的三万余次 libm 可忽略）。

已发布阿里云 Debug `202607210306`（versionCode 43 / 2.0.0），APK `20939764` 字节，
SHA-256 `223ae7586c43cac813616d8d0766ce311b073da354d473c766ab0880e7128e6a`；
`latest.json` 的 `releaseNotes` 字段已回读确认存在。

## 2026-07-21 - FableSol 第三批：软饱和快速路 + `field` 分解仪表

第二批后真机 33.8fps（56.6 → 29.6ms/帧），瓶颈收敛到 `sample` 23.0ms 中的
`field` 22.7ms（方向场累加）；`optics` 5.0 → 0.7ms，`prep/fair/slope` 各 ≤0.1ms，
`draw 0.1 / swap 0.1` 说明 GPU 仍全空闲。

一个对不上的数字：`fair` 每次内层迭代约 1.2ns、`field` 约 68ns，而两者循环体复杂度
只差一倍。`fair`/`sheen` 用同一套派发且都很快 → 派发机制没问题，是 `field` 内部另有
原因。本批**先把它拆成可读数字，不猜**：`sample()` 分段细化为
`prep / field / limit / fair / slope`（把 41904 次软饱和 + 单调修复单独派发），
另加 `pkt`（参与累加的波包数，自然上限 7 但事件注入可叠到 24）与 `cpu W/N`
（实际计算线程数 / 可见核数）两个读数。

同时落地审计 1A-2 软饱和快速路：`ratio⁶ ≤ 1e-3` 时用四项二项展开代替开方与三次
Halley 除法，截断相对误差 < 5.6e-14。阈值刻意不放宽到 1e-2——`FableSolCurveFairnessTest`
以 1e-12 容差探测 `value = ±3`，恰好落在快速路内。

已发布阿里云 Debug `202607210251`（versionCode 43 / 2.0.0），APK `20939764` 字节，
SHA-256 `6655a11c3311d5cef467687ac3320549b3c033d44917707c2b1f3ead38398e62`；
`latest.json` 的 `releaseNotes` 字段已回读确认存在。

## 2026-07-21 - FableSol 帧率回归真凶定位：`Math.cbrt`（第二批）

上一版的屏幕仪表在真机给出决定性读数：整帧 56.6ms 中 `sample` 独占 **44.0ms**，
`draw 0.2 / swap 0.1` 证明 GPU 完全空闲。真凶是本轮迁移新引入的六阶软饱和
`value / sqrt(cbrt(1 + ratio⁶))`——97 行 × 216 点 × X/Z 两路 = 每帧 **41904 次
`Math.cbrt`**。Android 的 `Math.cbrt` 是 libcore 的纯 Java FDLIBM 实现，没有
`sin`/`cos` 那样的快速路径，debuggable 构建下每次约 1µs（44ms ÷ 41904 ≈ 1.05µs）。
桌面 HotSpot 把它当内联函数，代价近零——**桌面 JVM 探针对这类 ART 特有开销结构性
失明**（同一函数桌面 0.26ms vs 真机 44ms，相差 170 倍）。

改为纯算术实现（位级初值 + 三次 Halley 迭代，误差约 1 ulp），并新增 `[1,1e18)` 上
与 `Math.cbrt` 的逐点比对测试。同时按 87 个 agent 的审计结论落地三项：闪点死算链
短路（`glint_capacity_gain` 默认 0 时容量恒为 0、一个顶点都不发，却仍跑 31360 次
sin/cos，逐位等价地跳过，optics 282µs → 56µs）、C2 fairing 的 125712 次除法换成
每行两次倒数、性能仪表自伤（GL 线程上的同步 binder `getThermalHeadroom` 改 1s 缓存、
分位数一次排序出三个值、HUD 8Hz → 2Hz）。

仪表增强：`sample()` 内部分 `prep / field / fair / slope` 四段，物理段分
`bc / wave / surf`，等待真机读数决定下一批。

已发布阿里云 Debug `202607210239`（versionCode 43 / 2.0.0），APK `20939764` 字节，
SHA-256 `a98ff937a3d8cec1466a0ff9372d9927c91f4955d09361dcafe9b844557d47af`；
`latest.json` 的 `releaseNotes` 字段已回读确认存在。

## 2026-07-21 - FableSol 帧率回归修复第一批 + 屏幕内性能仪表

用户反馈 FableSol 迁移后动画发卡，并明确**新 debug 包比旧 debug 包更卡**，
因此排除 D163 的 ART 运行时税，判定为本轮代码回归。用户裁定：可接受"难以察觉
的差异"；当时无可用设备，先纯静态优化，再发布远程测试。

无设备条件下新增 JVM 探针 `FableSolCpuFrameCostProbe`（默认跳过，需
`-Dfablesol.perf=1`）取相对基线，定位到本轮迁移新增的 `FableSolRowParallel`
每次派发要付 22.8µs 的挂起/唤醒往返，而一帧派发五次。四项**逐位等价**优化：
行并行改短自旋+挂起（空任务 22.8µs → 1.0µs）、`sample()` 派发四次合并为两次、
银泽坡度滤波按行并行（136µs → 44µs）、几何光学顶点色归一化外提。连续水面
采样 407µs → 258µs（−37%），每帧屏障总开销约 114µs → 约 5µs。

同时新增 GL 线程 build 段分解计时（采样/顶点/银泽/配色/光学）与每帧 hop 计数，
接到 Debug 构建的屏幕内 HUD，便于远程读数。新增两条逐位一致性回归测试，开发中
实际拦下一处求和顺序错误（`(b + 2a) + a` 误合并为 `b + 3a`，浮点加法不结合）。

尚未处理：`optics.build`（桌面约 274µs，当前最大单项、完全未并行）、19012 顶点
填充与上传、GPU 侧四 pass + 4×MSAA + FP16。

已发布阿里云 Debug `202607210220`（versionCode 43 / 2.0.0），APK `20939764` 字节，
SHA-256 `2522f9f37baaf20cc1b566ff5d93e50a2a6a68b807a9cbd5aad5cb02b9b857d4`；
`latest.json` 的 `releaseNotes` 字段已回读确认存在。

## 2026-07-18 - 接通 FableSol“声音分析与灵敏度”双端调参（D171）

用户先询问 Python“感知前端”面板中的选项是否生效，以及 Android 为何没有同组
控件。诊断确认 `agc_window_s`、`silence_gate_db`、`expander_amount` 在 Python
GUI 实时分析中均有完整热更新链并真实影响输出；Android 也运行同构算法和相同
默认值 `24s / 6dB / 0.32`，但三个同名 Params 注册无法到达 `AudioRecorder`
独立持有的 Analyzer。Python 离线模式与 `--sim-audio` 当时也只用固定默认值。
用户确认按建议全部接通，并进一步指出“感知前端”难以理解，因此现统一命名为
“声音分析与灵敏度”。

Android 在 `FableSolTuning` 中增加 `AUDIO_FRONT_END` 目标与三项参数组，补齐
13 种语言资源；新增 `FableSolFrontEndTuning` volatile 快照，Dialog 继续复用
既有持久化并将预览值送到 `AudioRecorder`，录音线程在每批 PCM feed 前应用到
`FableSolRealtimeAnalyzer`，当前会话下一批即可生效。Python 将离线 worker、
缓存键和 `--sim-audio` 接入同一参数快照；非默认值通过正式因果 Analyzer 的
“自定义−默认”差分修正离线响度、三频段、静音与 onset 驱动，默认值保持旧
离线输出；GUI 以 350ms 防抖触发整曲重分析。

验证：Android 完整 `:app:testDebugUnitTest` 通过；Python 164 项 `unittest`
全部通过；新增动态测试确认 Android 静音门真实改变 Analyzer 输出。20 秒确定性
音频离线对照确认非默认参数会改变响度、三频段、flow 和 105 个静音帧，并产生
独立缓存。发布日志：docs/features/audio-visualization-fable-sol/debug-updates/
update-20260718210721.md。已发布阿里云 Debug `202607181308`（versionCode 43 /
2.0.0）；发布任务成功，生成的 APK 大小为 `20934848` 字节，SHA-256 为
`b371f9f977e4dac0c9527dabdea9eff4be873eba6180d27804ed1a87735edaa5`。

## 2026-07-18 - 收敛 FableSol“主浪”调参目录（D170）

用户在对比 Android 与 Python 模拟器后追问 `hero_punch` 是否就是
“主浪冲击（旧）”，并提出删除不生效项、把 `beat_gain` 放回正确组别，
同时让 Python 参数面板展示真正生效的参数。代码与 Git 历史审计确认：
`hero_punch` 正是该控件；它和 `hero_punch_decay_s` 只读取从未被生产路径
写入的零状态，Python 已于 2026-07-16 删除，Android 遗留到次日调参
Dialog 后才变得可见；`beat_gain` 实际只加速环境波相位，不作用于主浪。

本次从 Android 的 `FableSolTuning`、`FableSolParams`、
`FableSolSimulation` 及 13 种语言资源删除两个 Punch 参数和旧状态；
Android/Python 均将 `beat_gain` 移入“环境与流动”；Python
`ControlPanel` 增加“主浪”组，只显示 `hero_gain`、`hero_len_dp`、
`hero_attack_s`、`hero_release_s`、`hero_breath` 5 个有效全局参数。
新增双端目录合同测试，Android 完整 `:app:testDebugUnitTest` 与 Python
159 项 `unittest` 全部通过；默认参数值及生产画面行为不变。
已发布阿里云 Debug `202607181229`（versionCode 43 / 2.0.0），APK 大小
`20930620` 字节，SHA-256 为
`ee4f8a15c7dc15cb723ef86e6b166759451cb9b419d5ffe2fd049740f262a73a`；
本地 APK、远端文件与 `latest.json` 已核对一致。
日志文件：docs/features/audio-visualization-fable-sol/debug-updates/
update-20260718202834.md。

## 2026-07-18 - 恢复波背自阴影（D169，back_shade_gain 默认 0.80）

用户裁定 D164 删除的波背自阴影需要保留，两侧从删除提交父版本一比一找回：
参数注册（Python"外观"/Android"外观与光学"，0~1.2 默认 0.80）、权重表、
GL mode 9 暗带（宏观曲率随 prepareContour 恢复）、Canvas 回退与 OKLab
降明度阴影色（Android 恢复 FableSolShadowColorPolicy，macroShade 不恢复）。
aerial_contrast 维持已删、空气透视因子按 0 固化为 1；共享 optical.frag
零改动（mode 9 复用 >7.5 半正弦分支，HDR <8.5 上界天然排除），仅补注释。
13 语言文案恢复（意语 \' 转义）。守护测试反转："唯一默认几何 = mode 9、
0..6 层有、7..8 层无、gain=0 归零"；HDR 源码序列断言恢复 backShade 环节。
Python 158 + Android JVM 全绿；截图 A/B 默认对归零最大差 18/255（注意：
--params 文件必须 {"values": {...}} 结构，裸键被静默忽略）。
已发布阿里云 Debug `202607181139`（versionCode 43 / 2.0.0），APK 大小
`20932604` 字节，SHA-256 为
`39d125a2bff9a7ff55d1386850ce6dcc179d746c62ef89e441c4cc0279c88992`；
`latest.json` releaseNotes 已核对。日志文件
docs/features/audio-visualization-fable-sol/debug-updates/update-20260718193844.md。

## 2026-07-18 - 恢复闪点出生场（"闪点数量"转正式可调项，默认 0）

D164 删除 crest_glint_strength 后闪点出生场只剩菲涅尔细节项（恒低于 0.08
门槛），glint_capacity_gain 成死参数。用户喜欢闪点，裁决恢复：镜面反射项
以固化强度 0.90（原默认，参数本身不恢复）写回两侧出生场（reflection ×
facet 碎裂），OpticalWaveSet 恢复毛细曲率输出（sample/sampleInto 双返回）。
默认 0 时画面与 202607180942 逐位一致；调参 Dialog"质感提升"组拉起即出。
params 标签"闪点数量（试验期归零）"→"闪点数量"（Android 文案本就无试验期
字样）。两侧新增出生守护测试（默认 0 无出生 / 拉 1 必须出生 + mode3 几何），
Python 158 + Android JVM 全绿；模拟器截图确认闪点可见。
已发布阿里云 Debug `202607181001`（versionCode 43 / 2.0.0），APK 大小
`20931572` 字节，SHA-256 为
`8fe29d50ef91a890155e1543c147f5b534caa53916ddf030b45e444986099055`；
`latest.json` releaseNotes 已核对。日志文件
docs/features/audio-visualization-fable-sol/debug-updates/update-20260718175500.md。

## 2026-07-18 - 撤销波浪曲线单纯化（D167 撤销），回到注入固化完成态

用户对照 8 图网格（真实录音驱动、近层裁剪、4 时刻 × 前后）后裁定：轮廓
叠加/转折在参数精简之前即已存在，属既定观感，非本轮回归——D167 三处
数值全部还原（高频模态 0.92/0.66/0.42、Gerstner 0.58/0.46 与 0.62/0.48、
spread 尾部 0.94/1.06/1.18），两侧同步，注释清除。波形几何回到与
202607180817 一致；D164~D166 参数精简与 GL 修复保留。两侧测试全绿。
已发布阿里云 Debug `202607180942`（versionCode 43 / 2.0.0），APK 大小
`20931572` 字节，SHA-256 为
`2e15b53f4c8e753343366715a7700be1531dc952984c88832339707373a8759c`；
`latest.json` releaseNotes 已核对。日志文件
docs/features/audio-visualization-fable-sol/debug-updates/update-20260718172000.md。

## 2026-07-18 - 波浪曲线单纯化（轮廓叠加小波与生硬转折收敛）

用户真机目测：表面光学移除后轮廓复杂度暴露——"完整 sin 中间叠加小曲线、
生硬转折"，确认与银丝无关、静止轮廓即有。定因：沿 X 的轮廓 100% 来自连续
水面方向谱（9 模态，最短 58dp）+ 波包 + Gerstner 峰聚拢（q 实算全模态顶满
0.58 上限）；此前被背阴影体积塑形/流光/轻纱/微法线纹理掩盖。三处收敛
（Python 模拟器 --screenshot 前后对照定档）：高频三模态振幅 0.92/0.66/0.42
→ 0.46/0.33/0.21；Gerstner q 0.58/0.46 → 0.32/0.26（波包 0.62/0.48 →
0.34/0.27）；spread_scale 尾部 0.94/1.06/1.18 → 0.82/0.88/0.94。两侧同步，
测试全绿。compose_layer_field 确认为无调用死代码（顺带记录，未删）。
已发布阿里云 Debug `202607180912`（versionCode 43 / 2.0.0），APK 大小
`20831652` 字节，SHA-256 为
`9b82340eb8d2356faebaea8d2905b73c66387963bb2a191ad86861ab9ebb4c85`；
`latest.json` releaseNotes 已核对。日志文件
docs/features/audio-visualization-fable-sol/debug-updates/update-20260718164500.md。

## 2026-07-18 - "注入"组 12 参数固化进实现（机制保留、行为零变化）

考古：10 项来自 git 化前基线快照、rhythm_wave 两项来自同日 3b43d3f。定性：
连续水面主路径（build_gl_frame）只消费 sim.heights 的逐层均值（means），
DynamicWave 行波空间形态被均值化 → 调参无可感变化；且 demo_mode、面板测试
按钮、多份测试依赖 inject_layer 机制 → 不能连根删，选固化。两侧消费点写死
默认值（增益 1.0/幅 36/宽 96~216/渐入 120ms/节奏 0.85·0.25/偏置 0.75/级联
0.054s/远浪 0.75·0.50·3.2s），远浪 2.5D 波包（inject_depth_packet）照常。
顺带删 Python 面板残留的空白"段落"组标题。测试改造：wave_shape_continuity
的关断对照改 mock sim.inject_layer；registry_audit 断言两组退出面板。
Python 157 + Android JVM 全绿。
已发布阿里云 Debug `202607180817`（versionCode 43 / 2.0.0），APK 大小
`20831652` 字节，SHA-256 为
`2bed10b03fd851a20fcda62ceb1c8bf43dbddb7911449e9d69b51f61f3624500`；
`latest.json` releaseNotes 已核对。日志文件
docs/features/audio-visualization-fable-sol/debug-updates/update-20260718161800.md。

## 2026-07-18 - 移除"段落"组 8 参数（段涌连根删、mood 两项固化）

段落组 8 项全部来自 git 化之前的基线快照（2026-07-10），用户目测调整无感
后裁决整组移除。段涌（surge_gain 基线即默认 0、从未启用）连同
apply_section 注入分支与 surge_lift 状态机两侧连根删；section_delay_s
（固化 1.0s 进 app.py/main_window.py 离线对时）与 lookahead_s（前瞻蓄势
随段涌整段删，sections.py）只服务模拟器离线播放路径、Android 本就未消费；
mood_transition_s/mood_spread_dp 固化 1.5s/12dp（性格档切换行为不变）。
调参 Dialog "段落"组整组消失（13 语言各删 7 行文案）。Python 157 测试全绿、
Android JVM 测试全绿。
已发布阿里云 Debug `202607180757`（versionCode 43 / 2.0.0），APK 大小
`20844396` 字节，SHA-256 为
`430c71d8337f29b3d8a9a432dc4de0db73cdc1f6940fe8503e5c6dad88e75a76`；
`latest.json` releaseNotes 已核对。日志文件
docs/features/audio-visualization-fable-sol/debug-updates/update-20260718155900.md。

## 2026-07-18 - 修复上一包 GL 崩溃回退（卡顿 + 观感异常）

用户真机反馈 202607180728 动画效果大变且非常卡顿。定因：删除风梳微法线后
water.frag 的 uTimeSeconds/uSurfaceHeadingRad 只剩声明、无使用者，GLES 链接器
裁出 active 列表（location=-1）；FableSolGlProgram.uniform() 的
check(location>=0) 每帧抛异常 → onGlFailure 静默回退 Canvas 软件绘制（画面
特性不同 + 性能差）。Python 端无恙因其 _uniform 有缺失防护。修复：删除两个
死 uniform 的声明与两侧上传；新增 JVM 静态守护测试
everyQueriedUniformIsActuallyUsedInItsShaderProgram（渲染器查询名必须出现在
shader 正文）。Python 157 + Android fablesol 127 测试全绿。
已发布阿里云 Debug `202607180743`（versionCode 43 / 2.0.0），APK 大小
`20850832` 字节，SHA-256 为
`3128e698b67e2a752457cd2eabce0b498436649ff313240dae8dbe44030a16c8`；
`latest.json` releaseNotes 已核对。日志文件
docs/features/audio-visualization-fable-sol/debug-updates/update-20260718161500.md。

## 2026-07-18 - 移除 17 项归零无感的表面光学参数与对应功能

用户在 Python 模拟器逐项归零 A/B 目测：镜面高光/高光提亮/波峰透光（含
纵深）/波冠轻纱/毛细闪光/薄峰透光/表面流光/轨道摆幅/波背自阴影/空气
透视/1/f 慢调制/微法线带限/全局 1/f 呼吸/风梳微法线/朝阳次表面散射
（含收束）共 17 参数全 0 或最小后画面无可感变化，裁决整体移除。Python
（params/simulation/mapping/ambient/gl_optics/gl_renderer/canvas，删
pink_breath.py 与 specular_policy.py）与 Android（Params/Tuning/GlOptics/
Simulation/FeatureMapper/WaveSets/GlRenderer/WaveVisualizer，删
PinkBreathPolicy/SpecularAaPolicy/OpticalColorPolicy/ShadowColorPolicy）
及共享 GLSL 同步瘦身；capillary 驱动链、crest_veil 场、streak/credit 状态机
一并删除。Python 157 测试与 Android 全部 JVM 测试全绿。
已发布阿里云 Debug `202607180728`（versionCode 43 / 2.0.0），APK 大小
`20850832` 字节，SHA-256 为
`5dd10e61426d897a99f0af07ae8a0a3bb2ef4b0509f1b9f8f65f038459c23eef`；
`latest.json` releaseNotes 已核对。日志文件
docs/features/audio-visualization-fable-sol/debug-updates/update-20260718152815.md。

## 2026-07-17 - 性能优化合集重发（用户手机验收用）

内容与 202607171143 相同（无新代码），发布日志合并 D162+D163 两轮：
120fps 解锁 + 渲染调度修复 + 零视觉变化说明，供用户手机端更新对照。
已发布阿里云 Debug `202607171147`（versionCode 43 / 2.0.0），APK 大小
`20867852` 字节，SHA-256 为
`39bae6580c232e12d2f4cfe5e303d7f2cca13521b21696639d72bb8af36ad04b`；
`latest.json` releaseNotes 已核对。日志文件
docs/features/audio-visualization-fable-sol/debug-updates/update-20260717195800.md。

## 2026-07-17 - 性能优化（二）：GL 线程 DISPLAY 优先级 + 工作窃取（D163）

平板前台复测定因：debuggable ART 运行时税（Mutex/CAS/JNI/JIT ≈ 半数周期）
是水面 30fps 主因且早于本轮存在；release 同场景 atrace 实测满帧 120.4fps。
本次落地 GL 线程 DISPLAY 优先级（compose 7.8→2.75ms）与行并行 8 行小块
工作窃取（消除异构核尾延迟），画面内容不变。143 项 JVM 测试全绿。
已发布阿里云 Debug `202607171143`（versionCode 43 / 2.0.0），APK 大小
`20867852` 字节，SHA-256 为
`7e97ca3eba02a2c2159f6426...`（完整值见 latest.json，releaseNotes 已核对）。
日志文件 docs/features/audio-visualization-fable-sol/debug-updates/update-20260717194500.md。
非 debuggable 发布通道已登记 followups（D163）。

## 2026-07-17 - 零视觉损失性能优化：120fps 解锁 + 逐帧成本下调（D162）

Android pacer 动态跟随显示刷新率（上限 120fps）、两 Dialog 窗口刷新率
请求 60→120；sample()/顶点填充按行并行 + 预备量折叠 + 波向量缓存；
三 VAO 一次捕获布局 + 光学帧常量 uniform 去重。确定性对照下优化前后
实机 RGBA 输出逐字节一致（max|Δ|=0/255）；Python 侧同轮批量化后
帧 p50 10.23→8.63ms 且对照帧逐位一致。152 项 JVM 测试全绿。
已发布阿里云 Debug `202607171056`（versionCode 43 / 2.0.0），APK 大小
`20867852` 字节，SHA-256 为
`bdd10bc5be5d20489e8e8adabaf23f6bb13b08a17782e04e9180a20ae550a2b1`；
`latest.json` releaseNotes 已核对。日志文件
docs/features/audio-visualization-fable-sol/debug-updates/update-20260717185900.md。
前台 120Hz 端到端 FrameMetrics 与真机目测待平板解锁后补做（followups 已登记）。

## 2026-07-17 - 调参目录多语言化（87 条 × 13 语言）

FableSolTuning.Spec 的 label 从中文字符串改为 @StringRes（labelRes/
titleRes），8 组名 + 79 参数名以 fablesol_group_* / fablesol_param_*
命名进入 13 个 strings.xml（脚本一次性插入 1131 条；教训：英/德组名
里的 & 必须 &amp; 转义）。中文文案与 Python GUI 一致。143 项全绿。
已发布阿里云 Debug `202607170801`（versionCode 43 / 2.0.0），APK 大小
`20867852` 字节，SHA-256 为
`80785a96615411b496f9ffd2949eaccb898c191cea3d5fa686d92d066c36c35d`；
`latest.json` releaseNotes 已核对。日志文件
docs/features/audio-visualization-fable-sol/debug-updates/update-20260717160136.md。

## 2026-07-17 - 暂停语义重做 + 按钮行 divided 间距（D161）

暂停从"停帧循环"改为"冻结模拟与音频泵、渲染照跑"（对齐 Python
canvas.py freeze_probe 语义）：simulationPaused 门跳过 sim.update/
重力，drainAndApply 丢弃冻结期音频、静默计时锚随帧冻结；调参热更/
HDR 过渡/换色揭示照常逐帧——冻结画面上渲染类参数实时可见，模拟
推进类（环境流动/主浪/涨落/注入/段落）需恢复播放（与 Python 一致）。
API 更名 setSimulationPaused，换色不再强制恢复播放。按钮行上边距换
divided_action_row_margin_top（参照选语言 Dialog）。143 项全绿。
已发布阿里云 Debug `202607170749`（versionCode 43 / 2.0.0），APK 大小
`20783596` 字节，SHA-256 为
`75e7aec198aa4de0d9b8666b2563b3ad1250803a80e9e26b5eadd7c3c061637c`；
`latest.json` releaseNotes 已核对。日志文件
docs/features/audio-visualization-fable-sol/debug-updates/update-20260717154853.md。

## 2026-07-17 - 调参 Dialog 四轮反馈 + 表面亮带整项移除（D160）

HDR 勾选框自身涟漪补装 GradientRippleDrawable.applyCheckboxRipple 并
随换色 updateBackground；按钮行 marginTop 恢复标准 dimen。表面亮带
（surface_strip_gain / buildSurfaceBand / drawSurfaceStrip /
SURFACE_BAND_* 权重 / FableSolSurfaceColorPolicy）随 Python 端
D147 先例整项移除，参数不再注册、目录不再收录；canvas 端流光与
亮带解耦保留（自算迎光/摆幅、底色取层色中间调，与 GL buildStreaks
对齐），mode 4 顶点合同改为"不复存在"。测试 149→143（删 band 专测
×2 + SurfaceColorPolicyTest ×2 + MaterialPolicy band ×1，排序合同
去 surface 项）全绿。
已发布阿里云 Debug `202607170734`（versionCode 43 / 2.0.0），APK 大小
`20783596` 字节，SHA-256 为
`4ce4083c2143881288678083259c73ab1b3845daf0caf00b328281cbf1c467a4`；
`latest.json` releaseNotes 已核对。日志文件
docs/features/audio-visualization-fable-sol/debug-updates/update-20260717153336.md。

## 2026-07-17 - 调参 Dialog 三轮反馈（D159）

角标按钮去圆形衬底 + 图标 imageAlpha=176（亮色不实黑）；
surface_strip_gain 默认 1.0→0.0（Python 端该特效已整项移除，视觉
对齐；四个亮带合同测试显式 setForTest 开启）；删"系统"组
（demo_mode 不再暴露）；换色卡顿修复——根因是每档全量重设 82 条
滑杆的 requestLayout 风暴，改为档中只更新视口内可见滑杆、动画结束
全量补齐；参数列表 overScrollMode=never（12+ stretch RenderEffect
拖垮预览帧率），到边提示走上下滚动指示分隔线。149 项全绿。
已发布阿里云 Debug `202607170713`（versionCode 43 / 2.0.0），APK 大小
`20783596` 字节，SHA-256 为
`7729de22488cbe6da8d1eac3d08d1339a3fb18a5bc2471f181a4daee95c11b5c`；
`latest.json` releaseNotes 已核对。日志文件
docs/features/audio-visualization-fable-sol/debug-updates/update-20260717151251.md。

## 2026-07-17 - 调参 Dialog 二轮打磨（D158）

用户九项裁决落地：预览贴顶满宽（present.frag 底角半径增量切直下两角，
顶角与 Dialog 轮廓同 dimen）；取景上移 36dp（旋转前 R^{-1} 补偿，第 0
层波谷不贴边）；右上角暂停/换色按钮——换色 = water.frag 揭示门 +
渲染端双遍（主遍 OKLab 插值、第二遍目标色 SRC_ALPHA 混合），新色
波浪从右缘 1600ms 涌入，UI 强调色元素 12 档同步渐变，颜色池 = accent
渐变 + 内置 10 色 + 记事背景去重；HDR 行渐变勾选框 + GradientRipple、
确定按钮 accent 化、底部滚动指示分隔线；"质感提升（试验）"更名
"质感"、行距增大。滑动卡顿根因 = 窗口 preferredRefreshRate=60 锁
刷新率，按用户指示留待下轮。两端输出行合同断言同步更新
（colorRevealAlpha）；Android 149 + Python 177 全绿。
已发布阿里云 Debug `202607170655`（versionCode 43 / 2.0.0），APK 大小
`20783596` 字节，SHA-256 为
`f45a9202f08ebf50b7e693caf58bbf59df8fa1fed253e409c8f47ca4df485d12`；
`latest.json` releaseNotes 已核对。日志文件
docs/features/audio-visualization-fable-sol/debug-updates/update-20260717145502.md。

## 2026-07-17 - 设置内新增"音频海浪动画参数调节"（D157）

设置 → 用户界面新增入口（RECORD_AUDIO 权限门控）打开调参 Dialog：
顶部固定 240dp 与录音界面同源的 GLES 实时预览（录音驱动、重力倾斜、
HDR、App 默认强调色），下方滚动区列出 82 个实际生效标量参数（9 组，
标签/范围/步长与 Python params.py 同源）+ HDR 高光开关（默认开、
设备不支持置灰）+ 恢复默认。调节经渲染线程 drain 实时生效并失效
静态材质色缓存，松手持久化到独立 prefs（只存偏离项），各渲染器
构造时套用；录音 Dialog 的 HDR 激活改读开关。顺带补注册漏移植的
swell_halflife_s / deep_integral_s（按实效值 0.5/1.0 保观感，与
Python 3.0/30.0 差异入 followups）。161 项单元测试全绿。
已发布阿里云 Debug `202607170600`（versionCode 43 / 2.0.0），APK 大小
`20781780` 字节，SHA-256 为
`aab5d1c25960ccd544b5f8220107c51db291fdef505bb0a4b1c5d6a34bdad033`；
`latest.json` releaseNotes 已核对。日志文件
docs/features/audio-visualization-fable-sol/debug-updates/update-20260717135950.md。

## 2026-07-17 - FableSol 银丝亮度过渡全面平滑化（v18）

用户真机截图裁决"高亮边界生硬、第 1 层 4 处高亮"。四根因移除：
①dFdx 逐三角形阶跃 → 顶点/覆盖判据改"坡度近零 × 波峰显著度
crestRimProminence"全平滑场；②太阳柱半宽收窄 0.11/0.055；③亮结
λ=360dp（相位取模同步 360——取模必须等于波长否则回绕跳变）+ 过渡带
0.24~0.78 + 深度 0.60；④覆盖门 wings 坡度窗删除（陡坡空间压缩成
硬边）、方向倾斜 0.80+0.20、顶点 SDR 增益 0.9。沿丝追踪 P90 过渡
落差 30→22/255。Kotlin 同步三常量（取模 360/尺度 1/360/深度 0.60），
按用户指示跳过 Android 测试（Python 177 项全绿），APK 核对含
crestRimProminence、无 dFdx。已发布阿里云 Debug `202607170459`
（versionCode 43 / 2.0.0），APK 大小 `20776849` 字节，SHA-256 为
`443007de5a87fd698430eaa53fae70303aa0de23ee6a35c71f41c8df98b89f2a`；
`latest.json` releaseNotes 已核对。日志文件
docs/features/audio-visualization-fable-sol/debug-updates/update-20260717125905.md。

## 2026-07-17 - FableSol 银丝高亮收敛太阳柱（v17）

用户真机反馈"每个高波峰都有高亮区 → 断断续续，每层最好 1~2 处"。
物理定性：顶点高亮是光滑波唇的宏观镜面点（每凸段至多一个、需太阳柱内
坡度可达、无 Cox–Munk 微面片兜底），比闪点更严格集中于柱内。实现：
apex01 × 太阳柱包络（柱心同构 sun_glitter_policy.path_center01、柱半宽
更窄 0.15→0.07），新 uniform uCrestRimSpanX0Px/uCrestRimSpanPx（row 0
可见跨度换算 x01；Kotlin 在 buildFrame 取 vertexData row 0 首末列 x）。
银丝本体（天空宽光源掠射反射）沿峰连续不受柱限制。Python 177 项 +
Android 149 项（--rerun）全绿，APK 核对含 crestRimSunColumn。
已发布阿里云 Debug `202607170358`（versionCode 43 / 2.0.0），APK 大小
`20776849` 字节，SHA-256 为
`3ee3b64439dabd88bbaefb4056b8655aad02e4ede22cb340e9c878b51c4354e6`；
`latest.json` releaseNotes 已核对。日志文件
docs/features/audio-visualization-fable-sol/debug-updates/update-20260717115820.md。
真机验收要点：同层高亮区收敛 1~2 处、柱外银丝连续无断续感、滑动亮结
入柱冲顶节奏、60fps。

## 2026-07-17 - FableSol 银丝顶点纯亮度渐变（v16 终形）

用户裁决 v15 顶点线宽膨大 + 晕铺展像细线"打结"的光斑 → v16：剖面
粗细全程恒定，顶点强调只走亮度（SDR ×1~2.1、HDR +2.2·apex01，顶端
最亮向两翼平滑衰减，亮结滑过顶点自然到达最亮）。纯共享 shader 改动、
Kotlin 零改动；按用户指示本次跳过 Android 测试（Python 177 项全绿），
assembleDebug 通过、APK 核对 v16 标记。已发布阿里云 Debug
`202607170339`（versionCode 43 / 2.0.0），APK 大小 `20776849` 字节，
SHA-256 为
`b99f85a07aee0cd6e18b812d117d9af16b4d263164a2758d838d3b86812c4838`；
`latest.json` releaseNotes 已核对。日志文件
docs/features/audio-visualization-fable-sol/debug-updates/update-20260717113848.md。

## 2026-07-17 - FableSol 银丝波峰全覆盖与顶点连续增亮（v13~v15）

三轮用户裁决落地：①方向门降级为 ±28% 倾斜，主门改局部凸性
（−dFdx(sheen 坡度)，"高出均值"对宽缓涌包失效；首渲 +dFdx 符号错误把
辉光整场点在波谷，用户目测抓出）；②顶点须显著最亮——v14 独立亮球被
否（"两个东西"），v15 改同一剖面随顶点度连续塑形（线宽 ×1~1.45、晕幅
×1~3.2、晕铺展 3.2→4.6 倍、SDR 能量 ×1~2.1、HDR 太阳项 +2.2·apex，
超驱后被 headroom 钳 3.6 = 闪点核心档）；亮结滑到顶点的冲顶由乘积自然
涌现。v13~v15 全为共享 shader 改动、Android 零 Kotlin 改动（重打包即
同步）。Python 177 项全绿；Android 149 项 --rerun 强制重跑全绿（教训：
parity 测试运行时读共享 shader，Gradle 不追踪其为输入，shader 改动后
必须 --rerun）。APK 核对含 crestRimApexMask/连续塑形/凸性负号。
已发布阿里云 Debug `202607170325`（versionCode 43 / 2.0.0），APK 大小
`20776849` 字节，SHA-256 为
`a1cc6e373d6bbc60e68d830d7648081eeb2d776ff73259794ed311a60012275f`；
`latest.json` releaseNotes 已核对。日志文件
docs/features/audio-visualization-fable-sol/debug-updates/update-20260717112520.md。
真机验收要点：银丝覆盖波峰全域（含顶点）、顶点连续增亮增晕（HDR 屏
顶点 3.6 刺眼）、波谷干净、滑动冲顶节奏、60fps。

## 2026-07-17 - FableSol 银丝滑动提速 64dp/s

用户定档：滑动速度 55→64dp/s（Python gl_renderer.rim_slide_phase_px 与
Kotlin FableSolGlRenderer 上传处两端同改，其余不变）。Android 149 项
全绿、assembleDebug 通过。已发布阿里云 Debug `202607170223`
（versionCode 43 / 2.0.0），APK 大小 `20776849` 字节，SHA-256 为
`89acbbe02f4796eeca56ea14710001a6c7b5c6e2e2320a148dc42532689c4f89`；
`latest.json` releaseNotes 已核对。日志文件
docs/features/audio-visualization-fable-sol/debug-updates/update-20260717102309.md。

## 2026-07-17 - FableSol 银丝细化与逆流滑动（D156 v9~v12）

发布 202607170010 后的四轮续调：v9/v10 亮芯 1.0→0.6dp、中远层权重按
用户定值 (1/0.90/0.72/0.42/0.27/0.16/0.10/0.05/0.0129)、空气透视变细
加陡（0.45+0.55×w）；银丝四控制项参数化（uplift_rim_width 0.6dp /
uplift_rim_halo 0.16 / uplift_rim_peak 3.6 / uplift_rim_slide 1.0，
Python GUI 同名）；v11/v12 逆流滑动视差——正弦亮结（GPU sin 大参数
精度使值噪声整行偏平的修复）+ 恒速 55dp/s 的 sim.t 纯函数相位
（λ=240dp 取模；跨帧积分在单帧渲染路径恒 0 的修复），亮度 vs glint
定量结论：SDR 双方核心纯白、HDR 银丝峰值≥glint。Python 177 项与
Android 149 项全绿，assembleDebug 通过，APK 核对含 slide uniform 与
正弦亮结；未使用 adb。已发布阿里云 Debug `202607170158`
（versionCode 43 / 2.0.0），APK 大小 `20776849` 字节，SHA-256 为
`e461a14dd73f60f7f3f61d48eab06ae3aa1eec76aad909cf11ce4fd7f05b6107`；
`latest.json` releaseNotes 已核对。日志文件
docs/features/audio-visualization-fable-sol/debug-updates/update-20260717095741.md。
真机验收要点：银丝亮段以 55dp/s 右滑 vs 波浪左滚的视差、0.6dp 细丝
观感、层级递减、HDR 太阳段、60fps。

## 2026-07-17 - FableSol 波峰银边"山舞银蛇"（D156）

用户圈选 GPT 效果图的波唇白线，物理定性为剪影掠射镜面线（掠射菲涅耳→1
全反射 + 高光沿最小曲率成线 + 半角对准），经 v1~v8 八轮目测迭代定稿：
剖面 = 1.0dp 高斯亮芯 + 0.16 幅指数晕（平滑窗归零，无内部截止边界）、
方向门 smoothstep(0.40,0.82)（银蛇 = 受光坡段，段长约半波长）、峰锐度门
删除（实测 pinch 沿轮廓 P50=0——乘性门会把银蛇饿死）、场强 × 音频活跃度
（0.30+0.70×sparkle01，D67 合规）、权重近层重远层近无 + 线宽空气透视
变细、HDR 峰值 = 1+2.6×weight（第 0 层 3.6 = 闪点核心同档，录音门控；
front fill 获得银边专属 HDR 通道——parity 测试的 bodyBlock 断言范围
相应调整）。闪点评审期经 glint_capacity_gain=0 默认归零（容量表不动，
Python/Kotlin 同构门，相关测试显式开 1）。Python 177 项与 Android
`:app:testDebugUnitTest` 149 项全绿，`:app:assembleDebug` 通过，APK 内
water.frag 已含 crestRimShape 与 0.16 晕幅；未使用 adb。已发布阿里云
Debug `202607170010`（versionCode 43 / 2.0.0），APK 大小 `20776849`
字节，SHA-256 为
`3bd6c6062fd6c5789b8e7616953ef78a46c0c999ebd3effb1c6a6422cbd8c8f4`；
本地 `latest.json` 的 `releaseNotes` 已核对。日志文件
docs/features/audio-visualization-fable-sol/debug-updates/update-20260717080949.md。
真机验收要点：银蛇的长度/亮度/游动感、闪点缺席后的观感、HDR 屏上
太阳段超白、60fps（银边约 +15 ALU/px，无采样）。

## 2026-07-16 - FableSol 厚度透光层级重分布与第 0 层透光（D154/D155）

厚度透光独立权重表（4~8 层上提为 0.56/0.49/0.42/0.36/0.27，经
`uThicknessGlowWeights[9]` 上传，shader 未上传时回退 SDR_SSS 保旧接线）；
第 0 层按用户裁决适当放宽 D6：front fill 参与厚度透光，入射量 = 水面处
波峰门（fill 本列水面 y 借闲置 aSlope.y 传入），衰减 = 近表亮环
0.35×范围 + Beer–Lambert（λ=2.5×范围），目标色对第 0 层补偿 ×1.35；
HDR 超白与峰值逐位不变。Python 177 项 unittest、Android
`:app:testDebugUnitTest` 161 项与 `:app:assembleDebug` 全部通过；
未使用 adb。已发布阿里云 Debug `202607161438`（versionCode 43 / 2.0.0），
APK 大小 `20776849` 字节，SHA-256 为
`bfd52b9544b92380f64ab62296bbf27a41e9244c2607c43cd70d61a196ed3343`；
本地 `latest.json` 的 `releaseNotes` 已核对含完整日志。日志文件
docs/features/audio-visualization-fable-sol/debug-updates/update-20260716223659.md。

## 2026-07-16 - FableSol 厚度透光上线（D151~D153）

质感提升批唯一存活项定档 uplift_thick_glow=1.29 / uplift_glow_boost=1.6，
薄峰透光 deprecated 归零，砍除掠射光泽/日出光锚/闪点风力；已发布阿里云
Debug `202607161231`，APK SHA-256
`ec156ea3fe39ff4d05fb64dc325b5eb331646698cf451599b0467ddad7e548c2`；
日志文件 docs/features/audio-visualization-fable-sol/debug-updates/update-20260716202919.md；
同号 202607161229 因 releaseNotes 带 BOM 被该次覆盖。
（本条由 2026-07-16 晚补位：原回填误插入下方 2026-06-28 条目的列表中，已迁出。）

## 2026-07-15 - FableSol 光学实体形状 RGSS 超采样

D140 的 MSAA 只抗几何覆盖。glint/streak/reflection/halo/transmission 的形状是 `optical.frag` 里
`smoothstep`/`sin` 逐像素程序化算出来的，MSAA 不重算片元着色故完全不抗其形状边缘锯齿。共享
`optical.frag` 因此对光学 pass 单独做 4x 旋转网格超采样（RGSS）：覆盖抽成 `opticalCoverage(vec2 uv)`，
单样本预乘输出抽成 `shadeOpticalSample(vec2 uv)`，`main()` 用 `dFdx/dFdy(vLocalUv)` 抖动四个 x/y 子
位置再平均。大实体导数极小、四点几乎重合，观感与逐像素一次着色一致，不改 glint 尺寸/剖面/峰值；只用
GLES 3.0 核心片元导数，光学占少量像素、4x 开销可忽略。两端共享 shader 一次覆盖。

另确认最亮 glint 核心的 SDR 台阶**不是缺抗锯齿**，而是薄亮 HDR 超白（峰值约 `2.3×`）clip 到 SDR 的
固有现象（clip 在着色下游、亚像素宽，任何采样抗不掉），真机 HDR 屏更柔和。用户裁决保持 glint 锐利、
不柔化，维持 D103～D118 合同。Python 167 项 unittest、Android 全量测试与 `:app:assembleDebug` 通过，
两仓 `git diff --check` 通过；未使用 ADB。已发布阿里云 Debug `202607150755`（versionCode 43 / 2.0.0），
APK 大小 `20776849` 字节，SHA-256 为
`c0c250d8c0df69a767f24e84ca9afae33ff17952d1da3403eab3205a3d26fa80`；远端 `latest.json` 的
`releaseNotes` 已回读，远端 SHA-256 与本地 APK 一致。

## 2026-07-15 - FableSol 波浪边缘 4x MSAA 抗锯齿

放大真机截图后九层弯曲界线、水天轮廓和光学闪点边缘仍有阶梯锯齿与颗粒。根因是 Android 与
Python 的整条离屏链都渲染到单采样 FBO、无任何多重采样；D139 的 `waterEdgeCoverage` 只是方向盲的
约 1px depth01 平滑，且其原生 DPI 修复只对 Python 生效（Android `uRasterScale` 恒为 1，早已按
surface 实体分辨率渲染）。Python 离屏对照证明是纯采样不足：2x2 超采样与 4x MSAA 都能消除台阶。

两端场景离屏改用 4x MSAA：几何画进多重采样 renderbuffer，再 resolve 进单采样 `sceneTexture`；
`pre-water` 折射背景保持单采样。采样数按格式查询取 `min(4, 支持值)`，与场景同格式（RGBA8/RGBA16F），
不支持时原子回退单采样并保留 D134 的 FP16→RGBA8 目标回退与输出颜色空间契约。Android 用可移植的
`glRenderbufferStorageMultisample` + `glBlitFramebuffer` resolve（GLES3.0 保证），未依赖无 Java 绑定
的 EXT。MSAA 只对几何覆盖多采样，材质/颜色仍逐像素一次计算；九层界线是几何边故逐像素 MSAA 足够。
`waterEdgeCoverage`、196 列 C1、glint 数量与逐层 HDR 峰值等既定合同不变。

18 色 FP16 回归的远/近响应比、相邻主体色差与超白覆盖同无 MSAA 一致；新增 Python MSE 测试确认 MSAA
更接近超采样真值。桌面 960×1260 FP16 完成时间 `10.94→11.10ms`（约 +1.4%）。Python 167 项 unittest、
Android 全量 `:app:testDebugUnitTest` 与 `:app:assembleDebug` 通过，两仓 `git diff --check` 通过；
未使用 ADB。已发布阿里云 Debug `202607150701`（versionCode 43 / 2.0.0），APK 大小 `20776849` 字节，
SHA-256 为 `a4887087e5a3017e65838309ae2f01314d2804683231dafaf313ff02252013bf`；远端 `latest.json` 的
`releaseNotes` 已回读，远端 SHA-256 与本地 APK 一致。Android 真机放大观感与帧率由用户验收。

## 2026-07-15 - FableSol 删除层内斜短光点与 continuous sheen

用户复看上一轮动态图片后确认：每层水体内部新增的斜短光点不是边缘 glint，观感仍然不好，要求
直接去掉。`full/no_glint/water_only` 消融与 GPU 回归证明，它们是为替代旧扩缩光带而加入的稳定
微面片 continuous sheen；关闭 glint 或全部 optical 后仍存在，因此没有继续调整大小、密度或
阴影，而是删除整条可见连续高光路径，避免再变成点、线、环或斑块。

共享 `water.frag`/`water.vert` 已删除固定微面片、连续 GGX/Smith 太阳反射、SDR 有界反射、
continuous HDR sheen excess 及配套暗面；Android 与 Python 同步删除对应 uniform 上传、逐层
coverage/峰值策略和两个已失效面板参数。边缘 glint 的数量与峰值、surface reflection/streak、
折射、Beer–Lambert、SSS 和 HDR transmission 保持独立。预滤波坡度仍服务折射与透射；HDR
transmission 沿用不随微法线分块的 `V·H` Fresnel，避免以另一种噪点重新出现。

129 BPM、适中水位的动态三联画确认 `full/no_glint/water_only` 均无层内斜点、扩缩光带或环状
替代物。18 色 FP16 回归中，完整效果峰值仍为 `2.629/2.934/3.314× reference white`
（最小/中位/最大），`water_only` 不超过 `1.000×`；九层主体相邻色差继续通过既有验收线。
Python 160 项 unittest、Android 140 项 Debug JVM 测试全部通过；删除后 GPU 基准略有改善。
未使用 ADB，最终 Android HDR 动态观感仍由用户真机验收。已发布阿里云 Debug
`202607150348`（versionCode 43 / 2.0.0），APK 大小 `20776849` 字节，SHA-256 为
`d64026f0da0d91d043dd51d7cdc1b9f3923c03ec37e41df37940fca8c6c6ad32`；远端元数据、完整说明、
重新下载 APK 及包内 shader 均已回读核对。

## 2026-07-15 - FableSol 恢复逐层特效并重做晶莹光影

保留静态 `lighten_far` 主体色板、零界面肩以及录音/HDR 不改写主体色，恢复已经确认的九层
闪点数量、出现率、长度、强度和五组 HDR 峰值：第 0～2 层为主视觉，第 3～7 层保留逐层稀疏的
水面事件，第 8 层退出离散闪点。闪点改为贴住真实水面、出生后尺寸固定、向水内单调衰减的实心
短光迹；删除解析光晕、周期扩缩和大块低频椭圆银斑。

连续水面高光与暗面现在来自同一法线和太阳方向。暗面只在当前水色上保色降低曝光，不混黑、灰
或整体乘暗 HDR；SDR 身份色肩部与小面积银白 HDR 核心分开。折射与 Beer–Lambert 从未包含当前
层的背景颜色开始合成，当前层透明度只应用一次；体光 HDR 资格、第 8 层无配对暗面、反射身份色
和预乘 HDR excess 也已修正。Python 与 Android GL/Canvas 已同构同步。

18 色普通 PNG 明确只作为 8-bit SDR 颜色/形状对照；HDR 直接验收 FP16 线性 scRGB 原值和
`1.0/1.08/1.16/1.29/1.6/2.0× reference white` 分档图。完整效果峰值中位 `2.934×`，去闪点
后仍为 `1.898×`；完整效果 `>1/>1.29/>2×` pooled 覆盖率为 `0.636%/0.346%/0.091%`。
Python 158 项测试和约 63fps 的 FP16 读回基准通过；Android 139 项 JVM 测试与 Debug 构建通过，
未使用 ADB。动态 HDR 与系统 tone mapping 由真机最终验收。

已发布阿里云 Debug `202607150216`（versionCode 43 / 2.0.0），APK 大小 `20776849` 字节，
SHA-256 为 `108df976f5cc8c63a69e38a4e36237c5bfe14e5acfc511b874524edff251e682`；远端
元数据、完整中文说明和重新下载 APK 的大小与哈希均已核对一致。

## 2026-07-15 - FableSol 恢复 lighten_far 并清理中远层圆环光斑

根据真机复测，撤销固定 hue 的亮向色域阶梯，Android/Python 主体统一恢复静态
`depth × lighten_far` 的 OKLab 混白；第 0 层保持记事色、第 8 层最多混白 `86.4%`，录音、HDR、
mood 与 color breath 不再改变主体。界面肩归零，第二、第三层不再使用逐色保色或特殊压暗。

离散闪点只保留最近三层，容量为 `3/2/1`；删除周期尺寸呼吸、强度尺寸耦合与解析外晕，中远层
连续太阳反射同步关闭。折射/Beer 背景混合上限收为 `.016`，最近三层连续反射权重为
`.72/.49/.21`。18 色离屏回归确认 648 个停靠点逐码符合静态混白公式，第 4～9 层不再出现离散
光斑；第二、第三层跨色中位色度保留率约 `98.2%/96.0%`。

Python 149 项 unittest、Android 全量 Debug JVM 测试和 `:app:assembleDebug` 均通过，未使用
ADB。已发布阿里云 Debug `202607150057`（versionCode 43 / 2.0.0），APK 大小 `20776849`
字节，SHA-256 为 `271573bebdac51fcbe211ba92c34c05c4f8a5bc143b407684e3fe8f2b7185945`；远端
元数据、完整中文说明和重新下载 APK 均已核对一致。

## 2026-07-15 - FableSol 第二轮晶莹水体材质重构

用户复测上一版后指出：九层边界变弱且偶发记事色粗边，跨层移动光影出现直线马赛克，最近三层
以外缺少闪点和材质变化，现实海面光斑、波光粼粼与局部 HDR 超白响应不足。用户提供 10 个内置
色和 8 组真实纯色/渐变，要求浅色不显脏、远层不超过 86.4% 等效混白；先在 Python HDR 离屏链
调试并解决卡顿，再同步 Android，Android 实机测试由用户完成。

### 实现与诊断

九层主体改为固定 hue 的亮向 OKLCH 色域贴边阶梯，以八条相邻 ΔE 分配层距；第 0 层保持 Thing
身份色，录音、mood 和 color breath 不再整体提亮主体，界面肩只补 8-bit 量化损失。共享
`water.frag` 改为 GGX/Smith/Schlick 连续微表面与导数 specular AA；SDR 反射、SSS、同色阴影和
HDR 局部 excess 分责限能。连续网格从 25 行增至 97 行；九层均有闪点容量，闪点核心与解析光晕
合并为一次 `6～32` 段曲面绘制。

Android `FableSolGlRenderer` 与 Python `FableSolGlRenderer` 均增加独立 `pre-water`、`scene`
离屏目标。水体从不可变背景纹理执行 Snell 屏幕空间折射，并只对透射分瓣应用 Beer–Lambert；
相对彩度和感知明度保护浅色。FP16 任一目标失败时两张纹理原子回退 RGBA8，不改写 EGL 输出
颜色空间。Python 对精确 8-bit RGB 色变换做色板作用域稀疏缓存，高活动稳态中位 15.52ms
（约 64.4fps），P95 18.86ms，保留 23 个闪点和 6 条流光。

### 验证

18 色 FP16 回归的最弱相邻主体 ΔE 为 0.01365、中位 0.01973；全部夹具界面肩宽度为零；去掉
离散闪点后仍有中位 0.442% 的局部超白覆盖。Python 151 项 unittest、Android 149 项 JVM 测试
及 `:app:assembleDebug` 全部通过，共享 GLSL 由 ModernGL 实际编译。未使用 ADB。真机需重点
观察快速大浪的折射边缘、准备态/录音态第 1/2 层腹部亮度、浅色阴影洁净度、九层动态分界和
中远层碎光分布。

已发布阿里云 Debug `202607141929`（versionCode 43 / 2.0.0），APK 大小 `20776849` 字节，
SHA-256 为 `327b4fd21c5555b723649386a93cadcb05241d8215210a0de6236e22627536c5`；远端
`latest.json`、完整说明和重新下载 APK 均已核对一致。

## 2026-07-14 - FableSol Step D/E：HDR 背光透射与连续太阳碎光

用户先要求找回 Claude 制定的 HDR 增强计划并确认阶段 D/E，随后要求实现。阶段 D 的目标是让真实
水面上的朝阳 SSS 进入 HDR，而不是继续只依赖独立透射带；阶段 E 的目标是把既有镜面闪点按固定
太阳路径跨整个连续水面组织，而不是各层独立拼接。此前已经解决的银泽网格分片、闪点突消、宽域
脏阴影和 `lighten_far=0.864` 均作为不可回退基线，Python 宽度继续固定为 320dp/640px。

共享 `water.frag` 复用 `sunriseSubsurfaceMask()`，只在 scene-linear HDR 分支新增
`(1-Fresnel)` 身份色透射差量；近层目标峰值 `1.45× reference white`，随深度归回 SDR，并受
实时 headroom 与录音态 HDR 增益封顶。SDR SSS 公式不变，独立 mode 8 峰值收为
`1.08/1.06/1.04/1.02/1.0…` 的弱肩部，避免重复记账。

新增 `FableSolSunGlitterPolicy`；`FableSolGlOptics` 把所有层未匹配锚点汇入一个候选池，由一个
总出生额度跨层选择。出生分数按近宽远窄、随深度连续偏移的太阳路径加权，路径外保留 `0.12`
概率底；闪点沿真实相邻深度行方向轻微展开，近层最大 `2.6dp`、远层最大 `1.3dp`。数量、单点
亮度、D70 attack/release、软退场、HDR 峰值与音频映射均未改变。Python ModernGL/QPainter
已同构同步。

数值回归确认透射只产生正向、小面积 HDR 差量，峰值 `1.0/1.45` 两档的 SDR 输出逐字节一致；
全局出生池可跨至少两层组织闪点且明显偏向太阳路径。Android 强制重跑 118 项单元测试全部通过，
`:app:assembleDebug` 成功；Python `compileall` 和 111 项 unittest 通过，共享 shader 已由 ModernGL
实际编译并完成离屏渲染。未使用 ADB。

已发布阿里云 Debug `202607140529`（versionCode 43 / 2.0.0），APK 大小 `20776849` 字节，SHA-256
为 `4092970efd1134750aac3a9d2ac0909a4a32b11884685090a30adac5afa10317`。远端 `latest.json`
已回读完整说明、大小与哈希；重新下载 APK 与本地构建完全一致，包内共享 `water.frag` 已确认包含
Step D 的函数、uniform 和 Fresnel 互补预算。

## 2026-07-14 - FableSol 同步局部保色坡面阴影

用户在干净平色与 D87 正向坡面光基础上仍觉得水体偏平，先要求上网调研更真实、但不显脏的阴影，
随后确认 Python 试验效果方向并要求同步 Android 发布。本轮 Android 新增
`macro_shadow_luma_cap=0.018`：保留 D87 正向同色提亮，只对宏观背坡使用 `0.08～0.18` 的
负向相对 `N·L` 门、`0.35～0.70` 深度退出和 crest 局部性。颜色只朝未混白 Thing 身份色派生的
`deepColor` 移动，并按最终 linear RGB 亮度损失封顶，不混黑、不使用微法线暗纹、不压暗远层。

GLES 规则位于共享 `water.vert`；Canvas 回退同步 deep 目标、crest 收敛度和封顶算法，并保持逐顶点
零临时数组分配。Python ModernGL 改为直接复用共享阴影函数，只保留自身关闭 `depthScattering` 的
覆盖，避免双重阴影。`lighten_far=0.864`、Python 320dp、深度散射默认关闭、微法线、HDR、SSS 和
其它局部光学均未改变。Android FableSol 全量测试和 Python 104 项测试通过，ModernGL 已实际编译
渲染共享 shader；未使用 ADB。真机请重点观察近中层坡面是否恢复适量转折，同时远层和宽缓坡是否
保持干净。

已发布阿里云 Debug `202607140331`（versionCode 43 / 2.0.0），APK SHA-256 为
`41abcc87643b6685c3ca432df8b77edcac8eac8260e0fb91cf42e6db5fd08da4`，大小 `20776849` 字节。
首次远端回读发现发布日志的多级 `##` 只嵌入首节，已改用 `###` 子节并在同一更新码覆盖；最终
`latest.json` 已包含实现、验证和真机观察全文，本地 APK、远端 APK 与元数据的哈希和大小一致。

## 2026-07-14 - FableSol 将 lighten_far 收到 0.864

用户在 `0.96` 对照版后要求改为 `0.864`。Android 与 Python 默认值已同步，静态层间混白步进
由约 `12%` 收到 `10.8%`，其它颜色、坡面光、alpha、环境与局部光学均不变，Python 宽度仍为
320dp。Android FableSol 105 项、Python 99 项测试及 `:app:assembleDebug` 通过。

已发布阿里云 Debug `202607140235`，APK SHA-256
`eea03b4b477196413ca411fc8be418e522ca9b7727b225b179a118385308500f`，远端 `latest.json`
与完整 `releaseNotes` 已回读一致。

## 2026-07-14 - FableSol 将 lighten_far 提到 0.96

用户希望九层水体更加分明。本轮只把 Android 与 Python 默认 `lighten_far` 从 `0.60` 提到
`0.96`，静态层间混白步进由约 `7.5%` 增至约 `12%`；第 0 层原色、D87 坡面光、alpha、
环境色和局部光学均不变，Python 宽度仍为 320dp。最远层叠加 mood/色彩呼吸后可能更早钳到
纯白，留给真机重点观察。

Android FableSol 105 项、Python 99 项测试及 `:app:assembleDebug` 通过。已发布阿里云 Debug
`202607140224`，APK SHA-256 `b9150d62e264f60d19a8aad7c818ac6ad44910bb28003452cfdd3a0be6ef6db2`，
远端 `latest.json` 与完整 `releaseNotes` 已回读一致。

## 2026-07-14 - FableSol 恢复干净的坡面立体感

D86 清理宽域阴影后，用户确认水体基色退化为逐层平色、整体太平。本轮采用 D87：纵向长波只取
`max(fullNdl-referenceNdl, 0)`，按原 RGB 同比例抬亮；响应 `0.12`、近层封顶 `1.5%`，远层
权重降至 `0.45`。背光坡面严格返回原色，不混天空色、不计算 Fresnel 候选色、不使用
`blackMix`。Android GL/Canvas 与 Python ModernGL/QPainter 已同步，Python 宽度仍为 320dp；
深度散射默认关闭和表面反射局部门控继续保留。

固定帧第 1/2/4/5/7 层 61px 横向跨度约为 `1.49/1.50/1.54/1.76/1.02`，所有层最小差为 0。
Android FableSol 105 项、Python 99 项测试及 `:app:assembleDebug` 全部通过。已发布阿里云 Debug
`202607140217`，APK SHA-256 `6be0ad89a80ae54342c333f5b7d15c4fdc80e2baac0165e3c1c83c114b2d4863`，
远端 `latest.json` 与完整 `releaseNotes` 已回读一致。

## 2026-07-14 - FableSol 清理近中层大范围阴影

用户在 Android FableSol 中持续观察到单层水体内部存在较大范围灰暗阴影；此前分别归零
`skyReflect`、`depth_scattering_strength`、`micro_normal_strength` 和波背阴影都只能缓解。先在
Python 同步 Android 效果并做固定恒色帧逐项消融：第一轮只禁止纵向负向受光、限制正向抬亮后，
用户继续指出近中层仍有。进一步 X 方向取样确认三个宽域来源：深度散射会让第 1/2/4/5 层整体
降低约 12/15/11/7 个亮度单位；纵向长波受光会形成最高约 4 的宽域抬亮；旧 `surface_strip`
即使无波峰也保留 `1.2dp` 基础宽度，中层可形成约 11 的宽域抬亮，使相邻基色被感知为阴影。

本轮同步已验证的 Python 干净填充策略：`FableSolParams.kt` 将默认
`depth_scattering_strength` 设为 0；`water.vert` 的 `relativeLongitudinalLight()` 严格返回基色，
Canvas 回退的 `FableSolLightColorPolicy` 同样恒等；`FableSolMaterialPolicy` 与
`FableSolGlOptics` 把表面反射改为迎光+波峰双门控，无波峰/非迎光位置宽度为 0，近层最大约
`3dp`，HDR eligibility 同步局部化。`water.frag` 的 HDR 掠射青灰银泽明确不属于本次阴影，保持
不变。此前诊断实验遗留的 `micro_normal_strength` 与 `lighten_far` 断言也收口到当前正式值。

验证：Android FableSol 105 项单元测试通过；Python 共享 shader/ModernGL 6 项实际编译渲染测试
通过；`:app:assembleDebug` 通过。待阿里云发布后由真机重点检查近中层宽域阴影、局部波峰反射与
HDR 银泽是否按预期分离。已发布阿里云 Debug `202607140110`，APK SHA-256
`ee2fcd44559f3f54a1e968cfc8767a0193e821fbbb8620fa5452ec3d0f37dbe5`，远端 `latest.json` 与完整
`releaseNotes` 已回读核对。

## 2026-07-13 - FableSol 去灰蒙蒙：水层改不透明 + 直接混白（关键突破）

用户在纯白 dialog 下、纯色记事 AE6060=(174,96,96) 实测最下层水体 A55659=(165,86,89)，整片低饱和灰蒙蒙。
用真实颜色数学（FableSolColor.mixOklab）逐 band 复现，定位根因：**不是掉饱和，是透明度**。半透明水层让
环境色（纯白 dialog 背景）从底下透上来，把远层从饱满色拖成灰——实测远层 S 从 ~35 塌到 ~27，L 推到 86 近白。
近/中层层数多盖住环境所以干净；远层层数少透得最厉害。用户提出且认同的方案：去掉透明度、每层颜色纯由混白确定。
本轮 `alpha` 全 = 1.0（不透明），`lighten_far` 0.6→0.75（补回去掉透明度损失的远处白，L86→L88）。不透明下
band = 纯混白层色、无环境透入：近层严格 = 记事色、S 不塌、远层干净偏白。环境/天空层未动。
MaterialPolicyTest lighten_far 断言 0.60→0.75；删临时诊断测试。assembleDebug + FableSol 全测通过。
待真机确认；近层 (165,86,89) 偏暗此前判断是回退的旧构建残留，若本版仍在再查（HDR 显示路径/采样点）。
已发布阿里云 Debug `202607131603`，APK SHA-256 `b57b74318490d34f42d7955f0ade8e2fa41dec9e73ff1374b269cde28f5d9370`。

## 2026-07-13 - FableSol 近处去脏：纵向受光改封顶高光、背光坡不压暗

远处灰浊已解决（远层 alpha 抬升），用户明确近脏在近处。定位：`relativeLongitudinalLight` 的**背光坡压暗**支
（`base*(1-blackMix)`），其 `depthScale=(1-depth01)²` + `nearShadingWeight` 双重把压暗**堆在近层**，最该晶莹处最暗。
已试过（不再重复）：[339] 关背光压暗但提亮还开→红框还在（被提亮污染）；[349] 关提亮留压暗→削减很多近处仍脏。
用户选"封顶高光、不要阴影"。本轮改：背光坡不压暗；受光坡只取 `lift=min(max(candidate-base,0),base*0.15)`，
`return base + lift*nearShadingWeight`（保色不冲白，冲白正是衬脏根）。景深交给水层阶梯+lighten_far。顶部 candidate/
skyReflect=0/linearSky 逐字不变，uniform 存活不变。ParityTest 原钉 GL/Canvas blackMix 一致→GL 有意分歧，改守封顶
高光结构 + 断言 `sqrt(darkness)` 已删，重命名；104/0。合并 asset 核对 lift 在、旧压暗无。Canvas 回退
（FableSolLightColorPolicy/WaveVisualizerFableSol）暂不动，记为后续对齐项。封顶幅度 0.15 可调。
已发布阿里云 Debug `202607131436`，APK SHA-256 `af716d5a2d6fa0df146ac255fe010db246991d0f2dd888798c50e247af166631`。

## 2026-07-13 - FableSol 基础层去灰浊：抬高远层 alpha

关受光提亮后"脏削减很多、基础层仍稍脏"。定位根因：水体合成起点是 `environmentAt`（84% 深色 app 背景），远层被
`lighten_far` 混白成乳白、alpha 又低到 0.357 → 64% 深背景透过乳白远层 = 灰浊；只发生在远端 band（近/中被 layer0
alpha=1.0 覆盖）。本轮 `alpha` 阶梯 1.0→0.357 抬到 1.0→0.68（深背景透出 64%→32%），外科式只清远端、不动近中。
受光提亮仍关（保持基础层信号干净）。若远方变干净=确诊，下一版给提亮封顶恢复再逐个加回 depth_scattering/
micro_normal/back_shade；若仍发灰=转查 lighten_far 乳白量或身份色派生。assembleDebug + FableSol 全测通过。
已发布阿里云 Debug `202607131412`，APK SHA-256 `3c15e33cf47a3ba021d8f3ad6494809b759607d58a779c1280ac06402110bd67`。

## 2026-07-13 - FableSol 诊断：关闭受光坡提亮（红框暗斑）

关背光压暗后红框暗斑仍在→排除。水体内部只剩 `relativeLongitudinalLight` 受光坡提亮。疑 Step A 陡法线让受光
提亮冲白、把旁边水色衬暗衬脏。本轮受光支 `return base`（关提亮），背光压暗恢复（两支不同避免 uniform 被优化）。
若红框净=提亮冲白衬脏，下一版给提亮封顶（不冲白）再干净加回诊断关掉项；若仍在=基础层色/合成本身，钻 buildColors。
parity 11/0。已发布阿里云 Debug `202607131349`，APK SHA-256 `3bf4c8ad408139ce4bb4641361f3d6008af5031e4ea7dee6009efda192ecfa0a`。

## 2026-07-13 - FableSol 诊断：关闭背光坡压暗（红框暗斑）

用户发截图圈红框：暗斑都在波形背光肩部。此时 depth_scattering/back_shade/micro_normal/skyReflect 全关，
水体内部只剩 `relativeLongitudinalLight`（Step A 光贴波走）在打明暗→暗斑=它的背光坡压暗（Step A 陡法线放大，
故"之前没这个问题"）。本轮只关背光压暗支：`return mix(base, base*(1-blackMix), 0.0)`，受光提亮保留。若红框净=
背光压暗，下一版重做成干净的深身份色阴影并逐步加回诊断关掉的项；若仍在=受光提亮太狠衬脏，转收提亮。parity 11/0。
已发布阿里云 Debug `202607131339`，APK SHA-256 `860881e1e149366e2ee98706eec2a612ffa43bf57ba531a7a4b016ea83ca2776`。

## 2026-07-13 - FableSol 诊断：关闭微法线（隔离暗部混杂灰黑颗粒）

depth_scattering=0 后暗部仍脏 → 不是保色加深类。转查微法线：它叠 ±18% 细密明暗噪点，暗部表现为细碎黑点/
脏纹理，最符合"混杂灰黑"。本轮 `micro_normal_strength 0.16→0`（诊断），其它保持（skyReflect=0/back_shade=0/
depth_scattering=0）。若暗部脏消失=微法线噪点，下一版重做其强度/暗部足迹/带限；若仍脏=基础层色/合成本身，
届时请用户发截图看。已清源确认：灰青光斑=SDR 天空反射。更新 PinkBreathPolicyTest；107 项 0 失败。已发布阿里云
Debug `202607131326`，APK SHA-256 `b00116484a21b7627881d1b0739f38f1d6f200f5756162d2d7048910623c96ac`。

## 2026-07-13 - FableSol 诊断：关闭 depth_scattering（隔离暗部脏）

灰青光斑确认=SDR 天空反射，`skyReflect=0` 已清。暗部仍脏，继续隔离：`depth_scattering_strength 0.45→0`
（诊断），其它不动。`derive()` 深色本身 `L×0.70 C×1.15` gamut-map、保色不发脏；嫌疑是 0.45 强度把波谷压得
又深又闷。若关掉暗部变干净=它，下一版用更浅深色+更小强度重做；若仍脏=转查 relativeLongitudinalLight
漫反射压暗/微法线。更新 DepthScatteringPolicyTest；107 项 0 失败。已发布阿里云 Debug `202607131319`，
APK SHA-256 `5631c4eb04cf64b6ff9c27b12978b17e091b54994dac950e4e23711fdd5ac7b1`。

## 2026-07-13 - FableSol：关闭 SDR 天空反射（清灰青光斑 + 暗部洗脏）

诊断确认 back_shade 不是暗部脏主因（dl=0 只好一点）；用户坚称灰青光斑是真 bug。定位共同强嫌疑=
`relativeLongitudinalLight` 的 SDR 天空反射（`skyReflect×Fresnel×近白偏冷天空`）：反射近白偏冷天空→灰青光斑，
叠水体又洗脏暗部。关键：Step C 后水面"好反射"已由 HDR 银泽接管，这道 SDR 老反射冗余且脏。本轮 `skyReflect
0.2→0`（保留 N·L 漫反射）。back_shade 继续 dl=0。若暗部仍残脏，下一刀关 depth_scattering 深色下拉或 blackMix。
非 HDR 屏此刻无镜面高光，待"SDR 钳位版光泽"补。parity 11/0。已发布阿里云 Debug `202607131311`，APK SHA-256
`861e19fa741dafc0c4b3e3d78b9196e8997ce6cb731c702aefc658406aacf41a`。

## 2026-07-13 - FableSol 诊断：关闭波背阴影（back_shade dl=0）

用户反馈上一版暗部仍脏、青灰又显。应其要求发一个纯诊断版隔离波背阴影：`BACK_DARKEN_L 0.10→0`，其它不动。
若暗部变干净=波背阴影是主因；若仍脏=来自 depthScattering 深色下拉 / relativeLongitudinalLight 的 blackMix /
skyReflect 青灰天空反射，下一版逐个关闭定位。青灰是独立的 `skyReflect=0.2` SDR 天空反射，近两版未动、非新回归，
待单独处理。107 项 0 失败。已发布阿里云 Debug `202607131306`，APK SHA-256
`0bb79b63082520213692334d98b227f7779b3b4ccbcadc43e6d54fa290bc62b6`。

## 2026-07-13 - FableSol：加深改为"更深的记事色"（保彩度不发黑）

用户反馈灰黑色让水体脏、近层黑尤其明显。定位两处加深在**掉彩度**：① `depthScattering` 的 deep 从已混白
层色派生（压暗乳白=灰）；② `back_shade` 向黑混（同时降 L 和 C=发黑）。修复：① `buildColors` 的
deep/subsurface 改从**未混白** `base` 身份渐变派生；② `backShade` 由 `mixOklab(base,black,0.18)` 改
`darkenOklab(base,0.10)`（只降 L 保彩度）。暗部变"更深的记事色"，近/中/远层都不再发灰黑。光泽本轮保持 1.6
不提亮（避免加大对比让黑更扎眼，先单独验收"黑→深色"）。更新 `ShadowColorPolicyTest`；107 项 0 失败，未用 adb。
详见 `docs/features/audio-visualization-fable-sol/debug-updates/update-20260713205714.md`。已发布阿里云 Debug
`202607131257`，APK SHA-256 `8cc2c57892d24722594ce2e945c1f1b6e38f6a558926849d5d2e3451f9eb7834`。

## 2026-07-13 - FableSol Step C 修复：银泽门控改加成（让 HDR 光泽真正出现）

Step C v1 真机完全看不到 HDR 光泽。几何诊断：平缓水面反射方向多指向观察者、太阳在画面深处高处，
`sunCos≈0.03`，`sunLobe=pow(sunCos,2~6)≈0` 把 sheen 整片掐灭。修复：`grazingSheenExcess` 把 sunLobe(门控)
改为 `sunBoost=1+1.4*pow(sunCos,3)`(全域 1、朝太阳最高 ~2.4)，`grazing=clamp(fresnel*sunBoost,0,1)`，
峰值近层提到 1.6×。掠射受光面广域出银泽、朝太阳更强；仍只 HDR+录音态、SDR 逐字节不变。present pass
不钳超白(round-trip 对 >1 恒等)。107 项 0 失败；解包 APK 确认含 `sunBoost`，未使用 adb。详见
`docs/features/audio-visualization-fable-sol/debug-updates/update-20260713203030.md`。已发布阿里云 Debug
`202607131231`，APK SHA-256 `335dc0d4908fbfbd546f2aa947b37cdc435ecbe128b9effea331baee4d4e4133`。

## 2026-07-13 - FableSol Step C：掠射 Fresnel 反射进 HDR（银泽）

水面首次进 HDR。`water.frag` 新增 `grazingSheenExcess()`：掠射 Fresnel(`f0=0.020373`,`pow(1-NdV,5)`) ×
朝太阳反射瓣(`reflect(-viewDir,normal)·lightDir`, 瓣指数 `mix(6,2,depth01)` 近紧远宽) × 深度衰减峰值
(`mix(1.40,1.0,smoothstep(0,0.62,depth01))`, 对齐 litCrestPeaks/D74, 服从 `uHdrHeadroom`)。仅
`uSceneLinear && vFrontFill==0 && uHdrGain>0 && uHdrHeadroom>1` 叠加超白差量 `max(peak-1,0)`，SDR 逐字节
不变；近中性白+一丝身份色(D69)；不接音频。`renderer` 把 `hdrGain`/`hdrHeadroom` 也喂给水面。Crest 报告
印证方向(`lerp(body,sky,R)` 统一太阳模型、Fresnel/SSS 公式已对齐，见 plan 的 Crest 节)。v1 只做宏观法线
大面积柔光泽，未接微法线/闪点瓣。新增守卫测试；107 项 0 失败；解包 APK 确认含新 shader，未使用 adb。
详见 `docs/features/audio-visualization-fable-sol/debug-updates/update-20260713202008.md`。已发布阿里云
Debug `202607131220`，APK SHA-256 `4124c7950f7d232b1d4ec62bd7ba85d1959a04ed12d4647b0956711aaf1eb3ed`。

## 2026-07-13 - FableSol Step B 补丁：远处灰黑修复

Step B 真机反馈中远处波背灰黑更重。根因确认：deep/subsurface 从**已被 `lighten_far` 混白的层色**派生
（`renderer` 先 `mixOklab(base,WHITE,lighten)` 再 `derive`），乳白远层被加深只会变灰；Step B 的
`depth_scattering 0.45` 加重了它。修复：`water.vert` 新增 `nearShadingWeight(depth01)=
mix(1,0.15,smoothstep(0.15,0.70,depth01))`，`depthScattering` 强度与 `relativeLongitudinalLight` 输出都乘/混它
——加深与打光随水层混白而衰减，近层保留对比与"光贴波走"、远层交给景深阶梯保持干净。parity 关键串
保留、强制重跑通过；106 项 0 失败；解包 APK 确认含新 shader，未使用 adb。详见
`docs/features/audio-visualization-fable-sol/debug-updates/update-20260713200742.md`。已发布阿里云 Debug
`202607131208`，APK SHA-256 `3e8295b80ec17df263e6744124d21eaea996fe9e882350f37e5c4411ec83b058`。

## 2026-07-13 - FableSol Step B：止脏 + 拉对比

Step A（打光法线迁到 `worldEta`）真机反馈"更真实但脏"——灰青带 + 偏灰黑。诊断为真法线放大了
`water.vert relativeLongitudinalLight` 的掠射 Fresnel 天空反射，它反射近白 `uHorizonColor` 且 SDR 钳位，
身份色被糊成中性灰（挨着品红读成灰青）。Step B 原则"打光待在身份色轴上"：① 掠射天空反射项乘
`skyReflect=0.2` 压弱（灭灰青），保留全强度 N·L 漫反射与 `0.14*sqrt(darkness)` 保色暗化；②
`body_light_strength 0.36→0`（无感的平铺中间调抬升）；③ `depth_scattering_strength 0.21→0.45`（几何
deep/subsurface 顶替体光，波谷压深、浪峰提亮=对比与保色阴影主来源）。灰青那块反射留给后续 HDR 变亮银。
同步更新 3 个测试。106 项单测 0 失败、`assembleDebug` 通过，未使用 adb。详见
`docs/features/audio-visualization-fable-sol/debug-updates/update-20260713194258.md`。
已发布阿里云 Debug `202607131143`，APK SHA-256 `2ce575de752bed8bcb90cca3e42256186bee0706e1feec42fa730c33b242a917`。

## 2026-07-13 - FableSol Step A：打光法线统一到真连续水面

`/grill-with-docs` 收敛出"光照相干化与 HDR 存在感"计划（`plan-2026-07-13-light-coherence-hdr-presence.md`）
后实现第一步。诊断出打光法线 `aSlope` 原只来自二维方向场 `eta`、不含各层波形轮廓，光与看得见的
波形错位；光学高光又是逐层第三套基准。改动全在 `FableSolContinuousSurface.kt`：`sample()` 先合成
真渲染面 `worldEta`（各层轮廓 + 方向场）再从它求 `slopeX/slopeZ`；`composeLayerField` 跨层由线性改
Catmull-Rom 防层锚点坡度接缝，锚点行仍精确穿过各层轮廓；行间权重按固定 `z01[r]` 在 init 预计算
保持零分配。shader/renderer/optical 未改。106 项单测 0 失败、`assembleDebug` 通过，未使用 adb。
详见 `docs/features/audio-visualization-fable-sol/debug-updates/update-20260713191842.md`。
已发布阿里云 Debug `202607131120`，APK SHA-256 `1d3e355f75b5cd3bb2cda7d4faacab0bcf33b00e9a3af3c70f67fa85fb3754ab`。

## 2026-07-13 - FableSol Stage 2-3 解析镜面抗锯齿

用户要求把双色深度散射轻量档从 0.22 微调为 0.21，并继续下一项。审计确认当前 GLES 镜面闪点
由 `FableSolGlOptics.buildGlints()` 的坡度高斯选峰驱动，而非 shader 中不存在的传统高光指数。
本轮在 `FableSolOpticalWaveSet` 按真实列采样足迹解析带限短毛细波：每波少于 2 个样本的分量
转为坡度方差，4 个样本以上完整保留，中间平滑过渡；方差按高斯卷积关系展宽闪点选取 lobe，
并做积分能量归一；过滤掉的曲率以统计 RMS 补回，避免闪点数量随带限一起消失。只有闪点使用
过滤坡度，体积光等仍使用原始毛细坡度。新增独立
`specular_aa_strength`，设为 0 可逐值恢复旧路径。未修改闪点实体上限、跟踪、颜色、曲面内侧
几何、双色散射其他规则或水面运动。84 项单测与 Debug 构建通过，未使用 adb。详见
`docs/features/audio-visualization-fable-sol/debug-updates/update-20260713114624.md`。
已发布阿里云 Debug `202607130347`。

## 2026-07-13 - FableSol Stage 2-2 双色深度散射

用户确认 PBR Neutral 高光压缩回退版正常并要求继续下一项。本轮只加入双色深度散射：在
`FableSolDepthScatteringPolicy` 中从当前记事色派生 deep/subsurface 色板，色相偏移分别限制在
8°/4°，并用保持色相的 sRGB 色域压缩避免逐通道硬裁切。连续水面顶点新增 Gerstner 横向收拢量，
共享 `water.vert` 仅按远近视角、浪峰收拢和固定光向混合两色；独立强度参数默认 0.22。未改变
现有高光、光学实体、波形、运动或音频映射，也未新增离屏 pass。79 项单测与 Debug 构建通过，
APK 已确认包含更新后的共享 shader，未使用 adb。
详见 `docs/features/audio-visualization-fable-sol/debug-updates/update-20260713113655.md`。
已发布阿里云 Debug `202607130337`。

## 2026-07-13 - 整项回退 FableSol Stage 2-1 高光压缩

用户复测认为 PBR Neutral 处理使高光不够亮、观感不如 Stage 1。已删除色调映射 shader、RGBA8
离屏 framebuffer/纹理、最终 fullscreen pass、独立参数和专项测试，恢复 Stage 1 默认
framebuffer 直接输出；Stage 1 迁移一致性修复保持不变。本轮不加入下一项质感优化，未使用 adb。
73 项单测与 Debug 构建通过，APK 已确认不再包含 `tone_map.frag`。详见
`docs/features/audio-visualization-fable-sol/debug-updates/update-20260713112427.md`。
已发布阿里云 Debug `202607130324`。

## 2026-07-13 - FableSol Stage 2-1 色相保持高光压缩

用户确认 GLES 与 Canvas 观感一致并要求继续质感优化。本轮只加入 Khronos PBR Neutral 启发的
最终高光 shoulder：场景先进入 RGBA8 离屏目标，再经 fullscreen pass 在 sRGB↔linear 之间处理；
线性最大通道≤0.76 时严格恒等，更亮时补回 F90 并按官方 Ks/Kd 曲线压缩。新增独立开关
`pbr_neutral_strength`，未修改散射、微法线、光学实体或运动。数学、shader 契约和 EGL 重建
回归通过；79 项单测与 Debug 构建通过，APK 包含 `tone_map.frag`，未使用 adb。详见
`docs/features/audio-visualization-fable-sol/debug-updates/update-20260713094457.md`。
已发布阿里云 Debug `202607130145`。

## 2026-07-13 - 修复 FableSol GLES 迁移差异并恢复 Canvas 光带参数

用户提供 A1/B1～B9 清单并要求独立核验。撤销此前 GLES 感知补偿：表面软带宽度/alpha、远层
羽化 alpha 均恢复 Canvas 100%。A1、B1/B2/B3/B5/B6/B7/B8 核心判断准确并已修复；B4 的
天空漏抖和双倍噪声准确，alpha 后果不适用于当前不透明目标；B9 恢复增益、阈值、空气透视与
双色彩晕，但按既有裁决保留水内弯曲几何。新增 EGL 重建、shader 契约、体积光带、波冠轻纱、
光学容量等回归；73 项单测与 Debug 构建通过，未使用 adb。详见
`docs/features/audio-visualization-fable-sol/research-2026-07-13-gles-parity-audit.md`。
已发布阿里云 Debug `202607130124`。

## 2026-07-13 - 继续降低最远两层羽化亮度

用户要求继续降低远层羽化亮度。仅将 GLES 羽化 alpha 从原 Canvas 参数的 30% 降至 15%，宽度、
颜色和其他光效不变。实际顶点 alpha 回归先失败后转绿；完整单测与 Debug 构建通过，未使用 adb。
详见 `docs/features/audio-visualization-fable-sol/debug-updates/update-20260713073536.md`。已发布阿里云
Debug `202607122336`。

## 2026-07-13 - 分离校准表面软带与最远两层羽化

用户要求 `202607122313` 的表面软带宽度回调但更加透明，并指出最远两层白带几乎没变化。确认
前七层表面软带与最远两层 `edge feather` 是独立通道：前者调整为 Canvas 宽度的 62%、峰值
透明度的 34%；后者保持柔化宽度，alpha 降到原值的 30%。两条真实网格回归均先失败后转绿；
完整单测与 Debug 构建通过，未使用 adb。详见
`docs/features/audio-visualization-fable-sol/debug-updates/update-20260713073038.md`。已发布阿里云 Debug
`202607122331`。

## 2026-07-13 - 第二次收窄并淡化 GLES 表面软带

用户确认 `202607121602` 的表面软带仍需更窄、更透明。仅将 GLES 表面软带相对 Canvas 的峰值
透明度从 68% 降至 48%、宽度从 72% 降至 52%；颜色、薄峰透光、波背阴影、远层羽化均不改。
真实网格回归按新目标先失败、修复后通过；完整单测与 Debug 构建通过，未使用 adb。详见
`docs/features/audio-visualization-fable-sol/debug-updates/update-20260713071313.md`。已发布阿里云 Debug
`202607122313`。

## 2026-07-13 - GLES 表面软带真机感知补偿

用户确认 `202607121554` 虽已恢复 Canvas 的半正弦剖面，但白边仍更厚、更白。进一步逐项对照
确认带宽、颜色、基础 alpha 和剖面数学均一致，故只对 GLES 表面软带增加独立感知补偿：峰值
透明度缩放到 Canvas 的 68%，带宽缩放到 72%；薄峰透光、波背阴影、远层羽化均不改。新增
真实网格回归，修复前失败、修复后通过；完整单测与 Debug 构建通过，未使用 adb。详见
`docs/features/audio-visualization-fable-sol/debug-updates/update-20260713000202.md`。已发布阿里云 Debug
`202607121602`。

## 2026-07-12 - 修复 GLES 表面边带比 Canvas 过厚过白

用户截图指出 `202607121547` 的多层白边比旧 Canvas 明显更厚或更白。对照确认几何宽度和颜色
公式均相同，根因是透明度剖面迁移错误：Canvas/AGSL 为峰值 0.66 的半正弦，GLES 使用中央接近
1.0 的宽平台，导致同宽几何同时显得更白、更厚。新增峰值和 10000 点积分光量回归，旧模型稳定
失败；现将表面软带、薄峰透光、波背阴影和远层羽化统一恢复为
`0.66×sin(π·relativeDepth)`。未改宽度或颜色。完整 65 项单测与 Debug 构建通过；未使用 adb。
详见 `docs/features/audio-visualization-fable-sol/debug-updates/update-20260712235416.md`。
已发布阿里云 Debug `202607121554`。

## 2026-07-12 - 移除珍珠/猫爪并迁移四类 GLES 表面效果

用户要求移除珍珠斑、猫爪暗纹，并继续表面软带、薄峰透光、波背阴影和远层羽化。珍珠跟踪已从
GLES/Canvas 删除；猫爪绘制、`FableSolFeatureMapper` 生成入口、`FableSolSimulation` 阵风数组/
生命周期推进、阴影颜色策略与测试也一并删除。GLES 新增四类沿轮廓的分层曲面带：表面软带仅
0～6 层，薄峰透光仅 0～4 层并保留 4～14dp 海拔门，波背阴影仅 0～5 层且保持记事色混黑，
环境色羽化仅 7～8 层。固定光学顶点容量提升到 20000，无稳态扩容。新增层范围回归，完整 64 项
单测与 Debug 构建通过，APK 含六份共享 GLSL；未使用 adb。详见
`docs/features/audio-visualization-fable-sol/debug-updates/update-20260712234619.md`。
已发布阿里云 Debug `202607121547`。

## 2026-07-12 - 纠正实体识别并修复镜面闪点高光越出曲面

用户用第二张截图确认 `202607121525` 后亮白长斜高光仍越出波面，并质疑是否在说同一个对象。
重新按代码通道核对后承认前两轮误认：被修的是低透明度、持续顺流移动的 `streak`；截图实际是
最高 alpha 约 0.92、跟随受光峰的 `glint`。新增 glint 独立顶点范围与真实生成路径回归，旧完整
直椭圆稳定出现负法向及曲面外顶点。现保留 glint 的峰值检测、身份跟踪和呼吸，只把几何改成
10 段贴合轮廓、仅向水内展开的弯曲软带，并收窄锐利亮芯。完整 64 项单测与 Debug 构建通过；
未使用 adb。详见
`docs/features/audio-visualization-fable-sol/debug-updates/update-20260712233225.md`。
已发布阿里云 Debug `202607121533`。

## 2026-07-12 - 根据截图把顺流流光改为贴合波峰的弯曲软带

用户提供截图，明确指向右下方跨过波峰的长斜锐利高光。由此纠正上一轮诊断：`202607121517`
只解决了流光短轴跨出中心切线的问题，但长轴仍是一条中心点切线上的直椭圆；波峰是曲线，流光
两端仍会穿到空气侧。新增实际生成路径的逐顶点曲面约束，要求每个流光顶点均位于其横坐标对应
的水面轮廓以内，旧半椭圆稳定失败。现将每条流光分成 10 段，逐段采样波峰高度形成弯曲带，并
分别软化长度两端、轮廓入口和水内下缘。完整 63 项单测与 Debug 构建通过；未使用 adb。详见
`docs/features/audio-visualization-fable-sol/debug-updates/update-20260712232510.md`。
已发布阿里云 Debug `202607121525`。

## 2026-07-12 - 修复 GLES 顺流流光滑出波峰

用户安装 `202607121505` 后指出，沿波面切线倾斜并顺流移动的高光有时会有一部分滑到曲面外。
诊断确认 GLES 流光使用完整对称椭圆，切线法向两侧各占一半；中心向水内偏移小于部分流光半厚度，
所以宽流光或陡坡会露出空气侧。新增实际网格回归，修复前稳定发现负法向顶点；现将流光改为从
轮廓开始、只沿水体内法向延伸的半椭圆，并在轮廓后的 0～14% 厚度内透明软入，避免硬裁切亮边。
闪点和珍珠形态不变。完整 63 项单测与 Debug 构建通过；未使用 adb。详细见
`docs/features/audio-visualization-fable-sol/debug-updates/update-20260712231659.md`。
已发布阿里云 Debug `202607121517`。

## 2026-07-12 - FableSol Stage 1 GLES 光学实体首个切片

用户确认 `202607121451` 倾斜已经不卡并要求继续下一步。按 GLES 迁移计划继续 Stage 1 视觉复刻，
新增 `FableSolGlOptics`：闪点、珍珠与流光保留跨帧实体身份、受光峰匹配、攻击/释放、顺流移动和
寿命；猫爪消费 Simulation 已有阵风。CPU 每帧只生成固定上限的椭圆三角形，新
`optical.vert/optical.frag` 在 GPU 上做旋转、径向软边和 alpha 混合。光学实体穿插在 8→0 层
水体绘制之间，避免远层装饰错误浮到近层之上；固定容量最多 64 个椭圆。新增两项回归，完整
62 项单测与 Debug 构建通过，APK 已确认包含六份共享 GLSL；未使用 adb。详细见
`docs/features/audio-visualization-fable-sol/debug-updates/update-20260712230410.md`。
已发布阿里云 Debug `202607121505`。

## 2026-07-12 - 修复 GLES 倾斜物理尖峰并细分性能日志

用户确认 `202607121437` 的 GLES 路径正常启用，但倾斜手机时仍感觉卡顿。真机日志显示 GL draw、
swap 与 GPU 均很轻，问题集中在 `FableSolSimulation.update()`：physics P50 会从约 5.8ms 上升到
12.5ms、P95 约 16ms。根因是倾斜持续改变边界参数后，九层 216 点剖面会集中在同一帧执行大量
`exp`。现将重建改为显示帧级预算，首次初始化后每帧最多 5 层，且利用左右对称只计算 108 点再
镜像写入；单个倾斜帧的边界点工作上限约下降 72%，传感器与 120Hz 物理不降频。性能日志新增
`steps/bcLayers/bc/waves/surface/compose`，便于下一次真机复测直接定位剩余开销。新增两项回归，
完整 60 项单测与 Debug 构建通过；未使用 adb。详细见
`docs/features/audio-visualization-fable-sol/debug-updates/update-20260712224913.md`。
已发布阿里云 Debug `202607121451`。

## 2026-07-12 - 修复 GLES TextureView 充气崩溃与红天空 Canvas 回退

用户安装 `202607121429` 后打开录音 Dialog 立即闪退，并要求 GL 未正常启用时回退 Canvas、
天空显示红色。崩溃栈确认 XML 给 TextureView 设置 transparent background，OPPO Android 16
在构造阶段抛 `TextureView doesn't support displaying a background drawable`；GL View 内的
`setBackgroundColor()` 也有同样风险。现改 FrameLayout Host 管理 GL/Canvas，彻底移除
TextureView background API；正常只运行 GL，任何 EGL/GLSL/draw/swap fatal 时切 Canvas，
Canvas 天空强制纯红并保留错误日志。完整单测与 Debug 构建通过；未使用 adb。详细见
`docs/features/audio-visualization-fable-sol/debug-updates/update-20260712223617.md`。
已发布阿里云 Debug `202607121437`。

## 2026-07-12 - FableSol Stage 1 首个 OpenGL ES 纵向切片

用户确认进入 GLES Stage。新增仓库级共享 GLSL、透明 TextureView、EGL ES 3.0 会话、
独立 `FableSolGles` 线程与 latest-frame 合并；Simulation、音频/重力消费、连续网格构建和
GL 绘制全部移出 UI 线程。录音 Dialog 已切换到 GL 路径，首批覆盖环境、连续 2.5D 网格、
九层颜色合成、Thing 纯色/八向渐变、纵向受光、Fresnel、抖动与近层填充；光学实体待后续
迁移。新增 `glFrame` 分段日志和 EGL/GLSL 静态色失败降级。完整单测与 Debug 构建通过，
APK 已确认打包四份 GLSL；未使用 adb。详细见
`docs/features/audio-visualization-fable-sol/debug-updates/update-20260712222824.md`。
已发布阿里云 Debug `202607121429`。

## 2026-07-12 - 修复 FableSol Stage 0 Hann 平滑性能回归

用户反馈恢复动画后比以前更卡，并回传第二份性能日志。稳定段 onDraw P50 约 32.8ms：
physics 6.5ms、color 11.8ms、submit_optics 13.6ms；GPU P50 仅约 4ms，确认 CPU/UI
线程瓶颈并进一步证明 Stage 1 GLES 必要。另定位 Stage 0 自身回归：池化版
`smoothSignal()` 把 Hann 权重 `cos()` 放进采样点×核点内循环。现改为初始化时缓存半径
3~6 的归一化核，帧内只做乘加，继续保持零分配。新增数值回归，完整单测与 Debug
构建通过；未使用 adb。详细见
`docs/features/audio-visualization-fable-sol/debug-updates/update-20260712220236.md`。
已发布阿里云 Debug `202607121403`。

## 2026-07-12 - 修复 FableSol Stage 0 水面完全静止

用户安装 `202607121348` 后反馈录音继续但水面、波浪和倾斜响应全部静止，并回传
`fablesol_frame_perf.log`。日志持续产生 Window FrameMetrics，却完全没有水面每 120 次
`onDraw` 才输出的六段汇总，确认帧循环从未启动。根因是 `onAttachedToWindow()` 早于首次
layout，彼时宽高为 0、`ensureAnimating()` 拒绝启动；Stage 0 又移除了音频/传感器回调的
逐次 invalidate，而 `onSizeChanged()` 没有补启动。现已在获得有效尺寸并更新物理容器宽度后
调用 `ensureAnimating()`。完整单测和 Debug 构建通过；未使用 adb。详细见
`docs/features/audio-visualization-fable-sol/debug-updates/update-20260712215624.md`。
已发布阿里云 Debug `202607121357`。

## 2026-07-12 - FableSol Stage 0 首轮性能修复与真机帧诊断

用户确认开始执行 GLES 迁移计划。本次先完成 Stage 0 首轮：以 Choreographer
frameTimeNanos 将水面固定为 60Hz，Dialog Window 请求 60Hz；重力传感器移到独立
HandlerThread，通过无分配 latest-value 信箱在渲染帧消费；`drawHighlights`、表面带、
猫爪、闪点、珍珠等光学路径的帧内 DoubleArray 改为 scratch 池，数学与 optical sample
增加 caller-buffer 入口。新增临时 FrameMetrics + onDraw 六段耗时日志，写入
`debug_logs/fablesol_frame_perf.log`，标记 `[DEBUG-FABLESOL-PERF]`。位图 atlas 等真机
SYNC/COMMAND_ISSUE 数据后再裁决，因为 Stage 1 会删除 AGSL 上传路径。新增节拍、重力信箱、
分位数和缓冲数学测试；完整单测与 Debug 构建通过；未使用 adb。详细见
`docs/features/audio-visualization-fable-sol/debug-updates/update-20260712214507.md`。
最终阿里云 Debug 更新码 `202607121348`；`202607121346` 因嵌入说明不完整、
`202607121347` 因重力信箱读端继续收紧为单次非阻塞读取，均已被替代。

## 2026-07-11 - 根治浪包突然隆起/鼓包 + 移除焦散

长期顽疾定位：注入 Hann 包本应画外出生，但 ①主因——injectLayer 的 uLimit 向内
钳位在共鸣档塌缩（melodic/loud → resonance01→1 → wallBlend≥0.35 → 画外余量
140→12dp），每次注入的半个包体（48~140dp）被直接压进可见区，120ms 内隆起几十 dp；
②次因——jitter/pan/frac 随机尾部越界，A6 宽度增长放大。物理核验共鸣档墙外
cScale=0.65、画外包可穿墙进入，向内钳位非必需。修复：画外全支撑硬保证
（need=可见半宽+半包宽+8dp，只向外推不向内拉；超网格先收窄包宽，仍放不下则丢弃）。
另按用户裁决整体移除焦散（两轮修形仍不好看，宁少勿烂）。全部单测（含浪形连续性）
与构建绿；未使用 adb。详细见
`docs/features/audio-visualization-fable-sol/debug-updates/update-20260711222645.md`。

## 2026-07-11 - 修复焦散悬浮感与偶发颜色闪烁

用户反馈焦散像"悬浮在浪前的羽毛"、水色偶尔闪烁。①焦散噪声纵坐标由屏幕 y 改为
水深锚定（光纹随浪升降），亮度受上方浪峰曲率聚焦调制（数据纹理 b 通道，峰下亮
谷下淡），阈值再稀疏；②闪烁根因=轮廓数据位图池跨帧复用与 RenderThread 在飞显示
列表竞争（位图不做快照），改三帧轮换池。构建单测绿；未使用 adb。仍差则按宁少勿烂
砍焦散。详细见
`docs/features/audio-visualization-fable-sol/debug-updates/update-20260711220450.md`。

## 2026-07-11 - C3 定稿优雅档 + 移除诊断 + 修复应用内更新日志缺失

真机确认纹理采样返工后三个 AGSL shader 全部存活。C3 回优雅档（absorption 0.35 /
caustic 0.5）、移除天空诊断色。修复应用内更新日志缺失：publishDebugUpdate 的
releaseNotes 依赖 `-PdebugUpdateNotesFile` 属性，此前几次发布未传，latest.json 无
日志字段；本次起恒传（规范已入 .claude/rules/gradle.md）。阶段 C 定稿：C1 抖动 +
C2 软带逐像素 + C3 吸收/焦散全部存活；折射暂缓待裁决。构建单测绿；未使用 adb。
详细见 `docs/features/audio-visualization-fable-sol/debug-updates/update-20260711215258.md`。

## 2026-07-11 - C 阶段返工：轮廓数据改纹理采样（修复 AGSL 红屏回退）

真机红屏确诊：AGSL 不允许 uniform 数组动态索引（GLSL ES 1.0 fragment 限制），
C3 层填充与 C2 软带 shader 一直静默回退。返工：top/th 归一化编码进 RGBA_F16
216×1 位图（精度≈0.3px），shader 以 input shader 纹素中心采样+手动插值；位图取自
帧内递增池（防显示列表别名，稳态零分配）。诊断天空色与 C3 夸张档保留供复验。
构建单测绿；未使用 adb。详细见
`docs/features/audio-visualization-fable-sol/debug-updates/update-20260711214716.md`。

## 2026-07-11 - 临时诊断版：AGSL 回退可视化 + C3 夸张档

用户真机感觉 C3 无变化，需区分 shader 静默回退与效果过于克制。任一 AGSL shader
编译失败时天空变纯色（红=C3 层填充、橙=C2 软带、紫=C1 抖动失败；正常天空=全部
存活）；C3 临时夸张档 absorption 0.80 / caustic 1.0，确认存活后回优雅档
（0.35/0.5）并移除诊断。构建绿；未使用 adb。详细见
`docs/features/audio-visualization-fable-sol/debug-updates/update-20260711214037.md`。

## 2026-07-11 - 阶段 C3：层填充逐像素光学（深度吸收 + 焦散）

用户真机确认 C1+C2 后圈定 C3。`FableSolAgsl` 新增 `layerFill` shader 链在已抖动渐变
之上：逐像素以该层轮廓（≤216 列 uniform 数组）求水深——①深度吸收（Beer–Lambert
近似，乘性衰减保色相、下限 0.72，全九层，`absorption_gain` 默认 0.35）；②焦散
（表面下 1.5~36dp 包络内的横向拉伸双倍频值噪声亮脉，阈值稀疏化，相位随层流累积
漂移，近三层限定，`caustic_gain` 默认 0.5，焦散色从本层色派生无新色相）。两参数
各自归零即关（A5.5 教训）。折射视差暂缓：第 0 层不透明，真折射在本架构无语义，
待用户看过本轮再定。构建与单测绿；未使用 adb。真机验收：水体是否读作有深度的
介质、光脉是否柔和跟流、两参数 A/B、帧率发热。详细记录见
`docs/features/audio-visualization-fable-sol/debug-updates/update-20260711213355.md`。
已发布阿里云 Debug `202607111334`，APK SHA-256 为
`2227c465c01b9c3b2ea125ac570bec4e7eb0f3c083e1846ff8f046fa07044db7`。

## 2026-07-11 - 阶段 C：AGSL 逐像素渲染增强（C1 抖动 + C2 软带）

阶段 A/B 收敛后按 D20 启动阶段 C，用户确认真机 Android 16 并圈定 C1+C2。新增
`FableSolAgsl`（RuntimeShader 运行时编译，API<33 或编译失败自动回退既有 Canvas 路径）。
C1：环境天空与九层水体填充渐变叠加三角分布抖动（±1/255），消除 OLED 平缓渐变的
色阶条纹——该问题按 D20 暂存至今，视觉设计零变化。C2：fade 软带优先走逐像素
shader——轮廓上沿与厚度（≤216 列）作 uniform 数组传入，逐像素求连续钟形剖面
（平台值 0.14/0.48/0.72 与 CPU 三子带一致、smoothstep 连续过渡），表面带/薄峰透光/
波背自阴影/体光/珍珠斑/猫爪/羽化全部受益，路径光栅移到 GPU；uniform 缓冲复用无
逐帧分配。C3（吸收/折射/焦散）另行立项。`:app:testDebugUnitTest` 与
`:app:assembleDebug` 通过；未使用 adb。真机验收：banding 是否消失、软带是否更
连续柔和、帧率发热。详细记录见
`docs/features/audio-visualization-fable-sol/debug-updates/update-20260711212101.md`。
已发布阿里云 Debug `202607111322`，APK SHA-256 为
`a35d2464692b2e1c502b88b30554887aba9fe0173994eaf5324a6ffeebdf621d`。

## 2026-07-11 - 根治连续“你好”时既有 Hero 浪突然上抬

用户反馈连续说“你好你好你好”时，偶尔有一条已经可见的浪突然向上变形，并要求先确认修复方案
是否与 Python/Android 既有设计冲突。确定性差分排除 `Prominence`、张力相干与注入渐入后，确认
根因是 `FeatureMapper.applyFrame()` 的响度、频段、音高和境状态持续改写全局 Hero 振幅；即使有
0.85 秒攻击，也会重新缩放整段已可见解析波。修复前两套无事件 Simulation 在 0.2 秒内出现最高
约 `0.541dp RMS` 的可见轮廓分叉。

对照 `CONTEXT.md`、ADR-0006/0009/0011 与 Android D12/D17/D19 后，保留六模态 Hero、慢声音
背景、A3 音高/境映射和 `Prominence` 几何事件，只淘汰全局幅度标量。Python 与 Android 每层新增
低/中/高三条空间能量包络：原攻击/释放只平滑上游画外源，能量按
`FLOW_DIR × (1.5|flow| + 0.45×wave_speed)` 传播进入可见区；`HeroWave.sample()` 逐点读取包络，
不再因下一帧声音整体重塑现有峰谷。回归同时要求传播到达前不分叉、到达后产生可测声音差异，
避免用关闭 Hero 响应伪修复。

Python 全量 55 项、Android 完整 `:app:testDebugUnitTest` 已通过；未使用 adb。详细记录见
`docs/features/audio-visualization-fable-sol/debug-updates/update-20260711200915.md`。已发布阿里云
Debug `202607111211`，APK SHA-256 为
`1120b8db1177bf3148585e9027b8ce4d95d7cf1195fe544fdbe1659fc397977e`。

## 2026-07-11 - 将 FableSol 最新表达与材质升级迁移到 Android

用户要求继续把 `audioVisualizerSimulatorFable` 的最新更新迁移到 Android，并明确范围只包括
`FableSol` 实现链。对照源端移植说明和两边工作区后，确认 Android 已有映射、物理和渲染的大部分
改动，但音频帧、事件和 Analyzer 尚未接上 A1/A3/A6，当前代码因此无法编译。

本次补齐 K/A 双计权、400ms/3s 响度窗、白化 SuperFlux、music gate、4Hz 波动强度、YIN、
相对音高、音节率/重音、HNR、arousal、looming 与 impulse；扩展 `FableSolFeatureFrame` 和
`FableSolEvent`，完成 `Prominence` 分发与随机整数接口。已有部分迁移继续覆盖双 register、
乐队分层、反克隆注入、境状态、持久闪点/珍珠斑、猫爪、表面带、薄峰透光、流光条纹、轨道
微摆、波背自阴影、空气透视、冷暖微偏、1/f 和 A6 张力试验；接触阴影与 A5.5 保持移除。

新增 Kotlin 表达升级回归；源模拟器 42 项相关测试、Android 完整单测和 Debug 构建通过。
未使用 adb、未安装设备、未创建 Git commit。详细记录见
`docs/features/audio-visualization-fable-sol/debug-updates/update-20260711193253.md`。已发布阿里云
Debug `202607111133`，APK SHA-256 为
`d7b29809505c0de0029e54ac21c4b507f51f131fbe02e97208a86e1c85c4b15e`。

## 2026-07-11 - 让 FableSol 流速对齐快速发声和高事件密度

用户先后提供 Android 录音 `20260710234846.wav` 与 `20260710235706.wav`，反馈快速“啦啦啦”和另一段高事件密度声音对应的水流都偏慢，并要求结合公开研究调整人的感知速度映射。诊断确认两段录音都未触及 213.6dp/s 的第 0 层物理上限：旧实现固定以 3 秒为分母，导致新出现的密集声音需要约 3 秒才能建立速度；节拍置信度越高，弱 subdivision 被删除得越多；tempo 与 density 的凸组合还会让约 110 BPM 向下拉低每秒 6~8 个表层事件。

本次同步修改 Python 蓝本与 Android/Kotlin：新增 1 秒快速事件率并与 3 秒保持事件率融合；通过听觉门的原始 subdivision 固定保留 75%，不再受 beat confidence 降权；tempo 仅在剩余行程内提供最高 12% 正向佐证，不再压低表层速度；分析侧攻击由 0.65 秒缩短到 0.35 秒，Simulation 流速平滑由 0.72 秒缩短到 0.48 秒。onset strength 仍主要控制波高、注入能量和材质，没有修改物理速度上限。

真实 WAV 回放中，快速“啦啦啦”的 `flow01` 中位数由约 0.49 提高到 0.65，第二个 onset 后约 0.92 秒越过 0.5；长录音中位数由约 0.51 提高到 0.83。此前近静音 Android 噪声样本仍只有 3 个 onset、97% 帧静音、平均响度约 0.007，没有复发噪声高速流动。新增 Python/Kotlin 速度纯函数与 Analyzer 整链回归；Python 10 项相关测试、完整 Android 单测通过，未使用 adb。详细说明见 `docs/features/audio-visualization-fable-sol/debug-updates/update-20260711001812.md`。已发布阿里云 Debug `202607101619`，APK SHA-256 为 `a49ece45531e35ae9dc64e4146fa7dc7e0ca192d551c34d2a1c3431b3851e4e1`。

## 2026-07-10 - 抑制 FableSol 采集启动低频暂态并保持既有浪形连续

用户提供 Android 录音 `20260710231609.wav`，反馈打开录音 Dialog 后即使环境安静，水位也会先升后降；
约第 7 秒的拖鞋拍地声还会让几层已经形成的浪生硬改形。Python 分析、回放和测试按用户要求使用 Conda
环境 `everythingdone`。

诊断确认录音前 0~3 秒的 PCM 本身约为 −20~−23dBFS，约 70%~90% 的 A 加权能量位于 250Hz 以下，
约 4.5 秒后才降到 −55dBFS 以下；当前应用显式关闭 AGC/NS/AEC，因此不能简单归因于应用层先录噪声、
再开始降噪。Android Analyzer 增加采集会话启动预热门：稳定静音或可信非低频内容持续 0.3 秒可提前
开放，最长 4.5 秒；低频暂态不进入底噪、中心、flux 或视觉输出。该门只影响水面，不裁剪保存的 WAV。

拍击 onset 位于约 6.30、6.76、7.15、7.26 秒。旧实现即使关闭物理注入，仍会通过 HeroWave punch
在全屏直接重塑多层既有轮廓，高频模态攻击最低约 61ms。Python 与 Kotlin 同步取消这条直接改形路径；
快速事件只进入 DynamicWave 物理波包，HeroWave 只按约 0.72 秒以上的慢包络改变，几何粗糙度与快速
光学材质分离。无物理注入的 onset 轮廓变化由最高约 0.56dp RMS 降为严格 0，极端频段目标单帧变化由
约 3.97 降至约 0.39dp RMS。

新增双端回归测试；Python 5 项测试、FableSol JVM 测试、完整 `:app:testDebugUnitTest` 和
`:app:assembleDebug` 均通过，未使用 adb。详细日志见
`docs/features/audio-visualization-fable-sol/debug-updates/update-20260710234151.md`。已发布阿里云 Debug
`202607101544`，APK SHA-256 为 `a6e0997c1f672cc02c65b3d88022081159df83092c63fa6060e19ec37f832309`。

## 2026-07-10 - 修复完全倒置时 FableSol 水体未转到录音 Dialog 顶部

用户真机发现完全倒置手机时，FableSol 水面和波浪仍停在侧边，没有像迁移前的 Opus 一样转到录音
Dialog 顶部；同时要求确认打开录音 Dialog 后是否禁止宿主 Activity 自动旋转。

诊断以“180° 重力输入必须保留 ±180° 渲染角”为复现信号。新增 `FableSolContainerGeometryTest`
用例后稳定得到 90°，确认根因是 `FableSolSimulation.setTilt()` 直接沿用了 Python 桌面滑块的
`[-90°, 90°]` 限制。修复后 Simulation 接受完整圆周角，并在 179°↔−179° 之间选择最近等价角，
避免水体跨边界时反向旋转 358°；墙面过渡改按水面偏离水平面的角度计算，使 0° 和 180° 都保持
水平水面语义。现有填充闭合距离在 180° 时已经越过顶部约 80dp，不需要修改水量或绘制范围。

迁移前后 diff 证明 `AudioRecordDialogFragment` 的行为没有变化：`onCreateView()` 保存当前
`requestedOrientation` 与屏幕 rotation 后设置 `SCREEN_ORIENTATION_LOCKED`；`onDestroyView()` 和
`onDismiss()` 恢复原策略；重力传感器到屏幕坐标的转换也与 Opus 相同。新增测试修复前 2 项失败、
修复后通过；完整 `:app:testDebugUnitTest`、`:app:assembleDebug` 均通过，未使用 adb。详细日志见
`docs/features/audio-visualization-fable-sol/debug-updates/update-20260710231140.md`。已发布阿里云 Debug
`202607101512`，APK SHA-256 为 `a070d497adc004cbe9b7da4455948c3083d03f7293360bc3bd0972cb26306199`。

## 2026-07-10 - 修复 FableSol 近静音录音的假响度与假 onset

用户提供 Android 近静音录音 `20260710215433.wav`：旧 FableSol 前端前 10 秒输出约 0.45~0.60 响度，
并持续产生每秒 7~8 个假 onset。复核确认录音存在 18.3kHz 强电子干扰、约 100Hz 嗡声与非平稳
AGC/噪声泵动；同时确认原分析中“`reduceat` 最后一带越过 12kHz”是代码 Bug，但单独修正它不会让
前 5 秒的 37 个假 onset 降低，直接撑开静音门的是 A 加权总能量仍累计到 Nyquist。

本次同步修复 Python 蓝本与 Android/Kotlin：听觉总能量、底噪、质心和平坦度限制在 16kHz 以下；
32 个 flux 带严格止于 12kHz；Analyzer 从静音启动；以 −66~−54dBFS smoothstep 绝对可听度置信
缩放响度、频段和 onset；flux 环接收所有帧作为基线，保留稀疏真实脉冲的快速检测。

真实 WAV 在 Python 路径由 90 个假 onset、平均响度 0.272 降至 3 个 onset、约 0.004；Kotlin 原生
44.1kHz 路径为 4 个 onset、约 0.008。新增 Python/Kotlin 回归测试覆盖频带边界、超声调制近静音与
明确可听稀疏脉冲；`:app:testDebugUnitTest`、`:app:assembleDebug` 均通过，未使用 adb。详细日志见
`docs/features/audio-visualization-fable-sol/debug-updates/update-20260710225539.md`。已发布阿里云 Debug
`202607101456`，APK SHA-256 为 `7201f01aec23479a5ad182aa52d0057618c4e048b521c7d2501f81d693886ad3`。

Latest published debug update: `202607101544`.

## 2026-07-10 - FableSol 物理容器改用 Dialog 最终实测宽度

用户在 Python → Android 迁移审查后确认：PREPARED/STOPPED 持续监听并驱动水面属于既定录音预览设计，
不需要按 Python 播放器状态机重置或门控 Analyzer。随后用户要求消除固定 320dp 物理宽度，并特别明确
“真实宽度”是 Dialog 完成全部布局测量后 `WaveVisualizerFableSol` 获得的最终宽度，不是 XML 的
`280dp`，也不是直接读取 TimelyClockView 的声明宽度。

诊断确认固定宽度影响 `FableSolSimulation` 的容器跨度、沿重力方向尺寸、体积守恒倾斜水位、墙面、
渲染范围和屏幕坐标注入中心；`FableSolFeatureMapper` 的段落 surge 也固定使用 `320×0.75`。旧实现以
280dp View 为例，水平跨度仍为 320dp，30° 倾斜跨度为 487.128129dp，而正确值应为 452.487113dp。

本次由 `WaveVisualizerFableSol.onSizeChanged(w, ...)` 把最终实测 `w / density` 传给 Simulation；上述物理
行为及段落 surge 宽度全部改用运行时 `containerWidthDp`。原 320dp 常量改名为
`REFERENCE_WIDTH_DP`，仅保留为 `DX_DP` 网格采样和测量前回退；波长、浪高、速度及固定 dp 注入宽度
不缩放。新增 `FableSolContainerGeometryTest`，修复后水平/30° 倾斜结果与真实宽度公式完全一致。

验证：`:app:testDebugUnitTest` 与 `:app:assembleDebug` 均为 `BUILD SUCCESSFUL`，未使用 adb。详细发布日志见
`docs/features/audio-visualization-fable-sol/debug-updates/update-20260710222131.md`。最终已发布阿里云 Debug
`202607101423`，APK SHA-256 为 `5f283f05b85d45a6c7512e1b07a0eba221a83be736a19cb999497a16edc142a0`；
首次上传的 `202607101422` 因更新说明不完整，已由最终版本替代。

Latest published debug update: `202607101423`.

## 2026-07-10 - 修复 FableSol 第 0 层水面颜色偏浅

用户反馈：正常录音态下，距离屏幕最近的第 0 层水面比 Thing 本身明显更浅、更灰；进一步确认纯色和
渐变 Thing 都应让第 0 层直接保持记事颜色。

诊断对照了 `WaveVisualizerFableSol`、原始 `audioVisualizerSimulatorFable/canvas.py` 和旧
`WaveVisualizerOpus`。问题不是 Paint/View alpha 或 OKLab 移植，而是原模拟器的 palette 规则被
套到了 Thing 身份色：纯色会生成向白混合 45% 的第二端色；纯色和渐变又都会在第 0 层叠加
`color_breath` / `moodBright` 混白。

本次新增 `FableSolLayerColorPolicy`：纯色基础色两端都使用 `background.color`；渐变保留原始
`color`、`endColor` 与 `orientation`；`lighten_far`、`moodBright`、`color_breath` 的合成混白量
统一乘以 `depth01`，保证第 0 层混白量恒为 0，远层仍保留空气透视和声音明度变化。高光、环境天空、
水体物理和逐层透明度未改动。

新增 4 项 `FableSolLayerColorPolicyTest`，先在旧规则下复现 3 项失败，再确认修复后全部通过；完整
`:app:testDebugUnitTest` 与 `:app:assembleDebug` 均为 `BUILD SUCCESSFUL`。未使用 adb。详细发布日志见
`docs/features/audio-visualization-fable-sol/debug-updates/update-20260710214017.md`。已发布阿里云 Debug
`202607101341`，APK SHA-256 为 `701d41938d0f4735bfe72f83816b65be789bcc1060b00f58383d18b0c05292f0`。

Latest published debug update: `202607101341`.

## 2026-06-28 - 修复习惯详情对话框宽度过窄

用户反馈：在 `DetailActivity` 中查看习惯（Habit）详情的对话框宽度特别窄。

诊断确认：`HabitDetailDialogFragment` 没有覆盖 `BaseDialogFragment` 的 `getDialogWindowWidthPx()`，父类默认返回 `WRAP_CONTENT`；同时布局根 `LinearLayout` 又在 `fragment_habit_detail.xml` 中写死了 `280dp`。`280dp` 只是 Material Dialog 的最小标准宽度，在大屏 / 高密度设备上对话框会显得明显偏窄。而项目中其它同类纯文本对话框（如 `DebugUpdateDialogFragment`、`ThingFolderNameDialogFragment`）都通过覆盖 `getDialogWindowWidthPx()` 返回 `320 * density` 对应的像素宽度。

本次按项目既有约定修复：

- `HabitDetailDialogFragment.kt` 新增 `import com.ywwynm.everythingdone.utils.DisplayUtil`，并覆盖 `getDialogWindowWidthPx()` 返回 `(DisplayUtil.getScreenDensity(activity) * 320).toInt()`，即 320dp 对应的实际像素宽度。
- `fragment_habit_detail.xml` 根 `LinearLayout` 的 `android:layout_width` 由 `280dp` 改为 `match_parent`，让对话框宽度以窗口宽度（fragment 返回的 320dp）为准，与 `fragment_debug_update.xml` 的写法保持一致。

验证：`:app:assembleDebug` BUILD SUCCESSFUL，`git diff --check` 通过，未使用 adb。

## 2026-06-27 - 搜索排除私密与 checklist 存储标记

详细发布日志见 `docs/features/thing-folders/debug-updates/update-20260627163603.md`。用户反馈：首页搜索时，私密记事标题前缀和 checklist 的各种内容前缀可能被纳入搜索范围；这些标记不是用户真正输入的记事内容，不应影响搜索结果。

诊断确认搜索链路存在规则分叉：`ThingManager.searchThings(...)` 和 DAO raw `title/content like` 会先按存储字段匹配，旧的内存剥离逻辑只在关键词本身包含特殊 signal 时才触发；文件夹递归计数、缩略图候选和编辑返回后的“是否仍匹配搜索”也没有共享同一套净化规则。本次新增 `ThingSearchHelper` 统一搜索匹配：私密标题移除真实私密前缀；checklist 正文通过 `CheckListHelper.toContentStr(..., "", "")` 转为用户可见纯文本。`ThingDAO`、`ThingFolderDAO`、`ThingManager` 和 `Thing.matchSearchRequirement(...)` 都改走 helper，搜索范围操作和文件夹搜索结果的判断保持一致。验证：`:app:assembleDebug` 通过，`git diff --check` 通过且仅有仓库既有 LF/CRLF 提示，未使用 adb。已发布 debug update `202606270837`，远端 APK 为 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606270837.apk`，SHA-256 为 `7ac3a73ee8ab096a2e4018d687c84e6d1de067275f3189fbba12040be3618848`。

Previous published debug update: `202606270816`.

## 2026-06-27 - 清理搜索态批量操作中的冗余刷新

详细发布日志见 `docs/features/thing-folders/debug-updates/update-20260627161550.md`。用户继续转述两个 review nitpick：状态类批量/范围操作中 manager 先 `loadThings()`，Activity 又按搜索条件 `searchThings()`；批量置顶记事逐项 `rebuildThingListEntries()` 后又统一刷新；`enterSelectionMode()` 仍有两次 `setListEntrySelected(...)`。评估后确认都可以安全清理，前提是最终仍由 Activity 的统一刷新路径负责恢复搜索投影。

本次让状态类 manager 方法增加默认 `reload` 参数，Activity 中马上调用 `refreshHomeAfterScopeStateChange()` 的路径传 `reload=false`；`restoreThingsToPreTrashState(...)` 与 `trashThingsPreservingState(...)` 内部多组状态变更后最多 reload 一次；批量置顶记事改为逐项只更新 location，最后统一 `rebuildCurrentThingListEntries()`；`enterSelectionMode(...)` 删除模式切换前的重复选中，保留 `toSelectingMode(...)` 之后的选中。普通调用默认 `reload=true`，搜索态最终仍按当前文本和颜色刷新一次，选择范围、操作对象、弹窗文案不变。验证：`:app:assembleDebug` 通过，`git diff --check` 通过，未使用 adb。已发布 debug update `202606270816`，远端 APK 为 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606270816.apk`，SHA-256 为 `1eccfead32d427f39f23fb2cc9105d8d0ed58676e366266989e00e8d27b51ee2`。

Previous published debug update: `202606270752`.

## 2026-06-27 - 修复搜索态批量操作后跳出搜索结果

详细发布日志见 `docs/features/thing-folders/debug-updates/update-20260627155027.md`。用户转述 Claude code review，要求核对后再决定是否修改。逐项检查后确认 review 的核心刷新问题成立：搜索态含文件夹的状态操作、纯记事恢复、批量移动到文件夹、批量置顶/私密等路径，部分会经由 `ThingManager.loadThings()` 清空 `mEntryFilterKeyword` 和 `mEntryFilterColor`，让搜索框仍显示原筛选但列表跳回当前文件夹全量内容；搜索结果被操作清空时也可能走首页空状态而不是搜索 no-result。

本次修复让 `refreshHomeAfterScopeStateChange()` 先按当前搜索态重建列表，再刷新 UI 与空状态；给 `moveSelectedThingsIntoFolder(...)`、`toggleFolderSticky(...)`、`updateFolderPrivate(...)` 增加默认 `reload` 参数，批量/搜索感知入口传 `reload=false`，由 Activity 统一调用 `loadThingsForCurrentSearchState()` 恢复搜索投影；移动到文件夹视觉刷新路径在计算新列表形状前先重建搜索投影。当前文件夹/单文件夹范围内容操作也会在搜索态下透传当前搜索文本和颜色筛选，并在确认弹窗中合并搜索范围提醒。未改 `enterSelectionMode()` 的幂等重复选中设置，也未改 DAO SQL 与内存搜索剥离 signal 的理论维护风险。验证：`:app:assembleDebug` 通过，`git diff --check` 通过，未使用 adb。已发布 debug update `202606270752`，远端 APK 为 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606270752.apk`，SHA-256 为 `ddc0ac12c627cf366610a4c1e47796fbed330a55d26f244cd12930c7bbc25152`。

Previous published debug update: `202606270654`.

## 2026-06-27 - 修复刚打开搜索模式时底层数据为空

详细发布日志见 `docs/features/thing-folders/debug-updates/update-20260627145144.md`。用户补充复现条件：问题只出现在点击搜索按钮后、尚未输入搜索文本且颜色仍为默认全部颜色的初始搜索态；此时拖拽无法创建/加入文件夹，原地释放进入选择模式后记事卡片视觉上变化但 toolbar 计数和 contextual menu 不更新；进入文件夹再返回会恢复正常。

诊断确认 `ThingsActivity.toggleSearching(false)` 进入搜索态时清空了 `mThings`，但 Adapter 优先使用的 `mThingListEntries` 仍保留原列表，因此 UI 可见卡片与底层搜索集合不一致。选择计数、菜单和拖拽投放源记事查找依赖 `mThings`，所以初始搜索态下记事相关操作失效。修复后进入搜索模式不再清空 `mThings`，而是立刻按当前搜索框文本和颜色执行 `ThingManager.searchThings(...)`；空文本 + 全部颜色会建立真实的当前范围全部结果搜索投影，并在搜索态开启后调用 `handleSearchResults()` 同步 no-result 状态。验证：`:app:assembleDebug` 通过，`git diff --check` 通过，未使用 adb。已发布 debug update `202606270654`，远端 APK 为 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606270654.apk`，SHA-256 为 `ff48cdd6c1d9f50cebed3d2ec80a54be9b7fd1d954d1365dc0a7d321b9243260`。

Previous published debug update before this entry: `202606270220`.

## 2026-06-27 - 短列表颜色面板下选中卡片顶部对齐

详细发布日志见 `docs/features/thing-background-editor/debug-updates/update-20260627101952.md`。用户反馈：主页记事/文件夹混合列表 item 不多、不需要滑动时，选择一个 item 打开外观 panel，切换到调整颜色并点击 RGB/Hex `EditText` 后，键盘弹出使 panel 中间区域变为可滑动，但混合列表没有把选中 item 滚动到 contextual actionbar 下方 16dp 处。

诊断发现，`ensureThingCardAppearanceSelectedCardVisible()` 原先只保证选中 holder 完整可见；短列表里 holder 已经绑定且仍处于可见区时，不会继续走“贴近顶部”的定位路径。短列表还可能因为内容高度不够，仅靠 `panelHeight + spacing` 的底部 padding 不能产生足够滚动余量。

实现上，`ThingsActivity` 将选中卡片目标位置统一为 `RecyclerView.paddingTop + getThingCardListItemSpacingPx()`；panel 打开期间的 RecyclerView 底部 padding 会按选中项当前顶边到目标顶边的距离、以及已布局内容底部，额外补足短列表需要的滚动空间。随后可见性检查不再只做“完整可见”，而是让选中卡片顶边对齐到 contextual actionbar 下方 16dp。验证：`git diff --check` 通过，仅有仓库既有 LF/CRLF 提示；`:app:assembleDebug --console=plain --no-configuration-cache` BUILD SUCCESSFUL；已发布 `202606270220` 到阿里云 debug update channel。

## 2026-06-26 - 颜色面板切换收起键盘与底部动画

详细发布日志见 `docs/features/thing-background-editor/debug-updates/update-20260626230720.md`。根据用户反馈，主页颜色编辑器在键盘打开后切换纯色/渐变 page、外观页/颜色页或切换另一个记事/文件夹外观 panel 时，应主动收起键盘；同时主页“调整记事/文件夹外观”panel 和详情页“调整颜色”panel 的出现/消失需要从屏幕底部升起、降落到底部。本次保留用户已调整的 `thing_card_appearance_panel_card_peek_height=36dp`。

实现上，`ThingBackgroundEditor` 在纯色/渐变 tab 切换前调用 `KeyboardUtil.hideKeyboard`；`ThingsActivity` 在主页 panel 打开/切换、外观页与颜色页互切、隐藏时统一收起键盘。主页 panel 新增基于 `translationY` 的底部滑入/滑出动画，并用 `mThingCardAppearancePanelVisibilityToken` 防止快速切换时旧动画回调把新 panel 误设为 `GONE`；隐藏时等滑出结束后再还原 RecyclerView 底部 padding。详情页 `ThingBackgroundEditorBottomSheet` 新增 `EverythingDoneAnimationBottomPanel` window animation，复用 `bottom_panel_slide_in.xml` / `bottom_panel_slide_out.xml`。

验证：`git diff --check` 通过，仅有仓库既有 LF/CRLF 提示；`:app:assembleDebug --console=plain --no-configuration-cache` BUILD SUCCESSFUL；已发布 `202606261510` 到阿里云 debug update channel。

## 2026-06-26 - 首页颜色面板改为轻量卡片露出预留

详细发布日志见 `docs/features/thing-background-editor/debug-updates/update-20260626225200.md`。
根据用户反馈，不再追求完整露出当前卡片，避免把颜色面板自身压缩得过小。首页颜色面板仍保留在 `fl_things` 内，并继续通过 `ScrollAwareColumn.maxMeasuredHeightPx` 控制最大高度；卡片预留区改为固定轻量 peek：`thing_card_appearance_panel_card_peek_height`（88dp）+ 卡片间距。删除未发布的“按当前 holder 实际高度 / 可用高度 45% 上限”预留逻辑。验证 `:app:assembleDebug` BUILD SUCCESSFUL，已发布 `202606261452`。

## 2026-06-26 - 撤回顶层面板层级改法，改为限制首页颜色面板高度

详细发布日志见 `docs/features/thing-background-editor/debug-updates/update-20260626224412.md`。
根据用户反馈，撤回上一版把 `panel_thing_card_appearance` 移到 contextual toolbar 之上的做法，让 panel 回到 `fl_things` 内。新的修复方向是在选择模式、渐变页、键盘弹出并触发可滚动时降低中间滑动区域高度，从而降低整个 panel 高度。`ScrollAwareColumn` 新增运行时 `maxMeasuredHeightPx`；`ThingsActivity` 按 `fl_things` 高度减去 actionbar/contextual toolbar 区域、底部 margin 和列表卡片间距来设置 panel 最大高度。这样现有 RecyclerView 底部 padding 与选中卡片可见性检查会使用更小的 panel 高度，让正在调整颜色的卡片更容易保持可见。验证 `:app:assembleDebug` BUILD SUCCESSFUL，已发布 `202606261444`。

## 2026-06-26 - 首页颜色面板层级高于选择模式工具栏

详细发布日志见 `docs/features/thing-background-editor/debug-updates/update-20260626223524.md`。
修复首页选择模式打开外观面板后，颜色编辑器渐变页在键盘弹出并触发滚动时，面板标题可能被 contextual actionbar 挡住的问题。原因是 `panel_thing_card_appearance` 原本位于 `DrawerLayout` 内部，而 contextual toolbar 是 `DrawerLayout` 后面的顶层 sibling；面板自身 `elevation` 不能跨父级压过 toolbar。本次将面板 include 移到 `activity_things.xml` 顶层，并排在 contextual toolbar 之后，让它作为顶层浮层显示在选择模式工具栏上方。验证 `:app:assembleDebug` BUILD SUCCESSFUL，已发布 `202606261435`。

## 2026-06-26 - 颜色面板滚动区裁剪与分割线间距修正

详细发布日志见 `docs/features/thing-background-editor/debug-updates/update-20260626222956.md`。
根据用户继续反馈，对照 `LicenseDialogFragment` 和用于选择应用语言的 `ChooserDialogFragment`，把颜色面板标题下方分割线间距统一为 12dp，把底部 action row 到下分割线的间距统一为 `app_chrome_dialog_divided_action_row_margin_top`。同时在 `ScrollAwareColumn.drawChild()` 中对 `NestedScrollView` 子项强制裁剪，确保中间滚动内容只能显示在中间区域，不能越界覆盖标题、上下分割线或取消/确定按钮。验证 `:app:assembleDebug` BUILD SUCCESSFUL，已发布 `202606261430`。

## 2026-06-26 - 颜色面板键盘弹出时固定标题与按钮

详细发布日志见 `docs/features/thing-background-editor/debug-updates/update-20260626221227.md`。
修复首页外观面板颜色页和详情页颜色面板在 RGB/Hex 输入框弹出键盘后，标题区、取消/确定按钮区可能被挤压或与中间滚动内容重叠的问题。`ScrollAwareColumn` 现在先测真实自然高度，再固定标题/分割线/底部按钮，只把剩余高度分配给中间 `NestedScrollView`；两处颜色编辑器滚动区也显式裁剪子内容，避免预置色或渐变方向按钮越界显示。验证 `:app:assembleDebug` BUILD SUCCESSFUL，已发布 `202606261413`。

## 2026-06-26 - 渐变方向顺序：斜向放第一排

详细发布日志见 `docs/features/thing-background-editor/debug-updates/update-20260626001553.md`。
渐变选项卡 8 个方向把 4 个斜向放第一排（更好看）、4 个正向放第二排。
验证 `:app:assembleDebug` BUILD SUCCESSFUL，已发布 `202606251616`。此改动尚未 git 提交。

## 2026-06-25 - 从世界取色图标改为纯相机（去掉滴管）

详细发布日志见 `docs/features/thing-background-editor/debug-updates/update-20260625232833.md`。
按用户要求去掉滴管，改为纯相机：Material camera_alt 机身轮廓 + 镜头圆环（描边）。
验证 `:app:assembleDebug` BUILD SUCCESSFUL，已发布 `202606251528`。

## 2026-06-25 - 从世界取色图标退回相机版

详细发布日志见 `docs/features/thing-background-editor/debug-updates/update-20260625231813.md`。
按用户要求，图标退回相机版：Material camera_alt 机身轮廓 + 中间滴管。
验证 `:app:assembleDebug` BUILD SUCCESSFUL，已发布 `202606251518`。

## 2026-06-25 - 从世界取色图标：相框做大 + 滴管移右下角破框

详细发布日志见 `docs/features/thing-background-editor/debug-updates/update-20260625231323.md`。
照片相框做大让太阳/山峰更舒展，滴管从居中改到右下角并探出相框（破框效果）。
验证 `:app:assembleDebug` BUILD SUCCESSFUL，已发布 `202606251513`。

## 2026-06-25 - 从世界取色图标改「照片+取色」+ 详情页面板不再压暗背后

详细发布日志见 `docs/features/thing-background-editor/debug-updates/update-20260625225555.md`。
从世界取色图标改为「照片(相框+太阳+山峰) + 右上滴管」；详情页颜色面板清除对话框 dim，
背后记事颜色不再变暗、显示准确。验证 `:app:assembleDebug` BUILD SUCCESSFUL，已发布 `202606251456`。

## 2026-06-25 - 从世界取色相机图标换更清晰轮廓

详细发布日志见 `docs/features/thing-background-editor/debug-updates/update-20260625224526.md`。
「相机 + 中间滴管」的相机轮廓改用 Material camera_alt 标准机身（顶部居中梯形凸起），中间仍为缩放居中的滴管。
验证 `:app:assembleDebug` BUILD SUCCESSFUL，已发布 `202606251445`。

## 2026-06-25 - 从世界取色图标改为「相机+中间滴管」+ 滚动分割线铺满全宽

详细发布日志见 `docs/features/thing-background-editor/debug-updates/update-20260625223539.md`。
从世界取色图标改为「相机外框 + 中间滴管(Material colorize 缩放居中)」；滚动提示分割线改为铺满整个
面板/对话框宽度（负横向边距抵消内边距 + clipToPadding=false），详情对话框与首页颜色页都已修正。
验证 `:app:assembleDebug` BUILD SUCCESSFUL，已发布 `202606251436`。

## 2026-06-25 - 从世界取色图标改为「地球+滴管」+ 详情页按钮间距再调小

详细发布日志见 `docs/features/thing-background-editor/debug-updates/update-20260625222711.md`。
从世界取色图标改为「地球(世界) + 滴管(取色，复用 Material colorize，缩放叠加于地球右上、笔尖指向地球)」；
详情页进一步收紧取消/确定按钮与上方内容的间距。验证 `:app:assembleDebug` BUILD SUCCESSFUL，已发布 `202606251427`。

## 2026-06-25 - 从世界取色图标再调整 + 收紧标题与内容间距

详细发布日志见 `docs/features/thing-background-editor/debug-updates/update-20260625220739.md`。
从世界取色图标改为"眼睛 + 取色瞳孔"（眼前所见的世界 + 取色，瞳孔被当前色 tint）；去掉编辑器内部多余
顶部内边距、调小标题下分割线上边距，收紧"调整颜色"标题与下方内容间距（详情页与首页一致）。
验证 `:app:assembleDebug` BUILD SUCCESSFUL，已发布 `202606251407`。

## 2026-06-25 - 颜色面板展开/滚动行为修正 + 从世界取色图标重设计

详细发布日志见 `docs/features/thing-background-editor/debug-updates/update-20260625215250.md`。
要点：从世界取色图标改为"取景框 + 中心取色点"；详情页颜色面板从可拖拽 BottomSheet 改为底部固定对话框
（打开即全展开、切 tab 不缩回）；渐变 tab 默认全展开、仅键盘弹出时中间编辑器可滚动（标题/取消确定固定）；
详情页补取消/确定（取消放弃回到打开时颜色、确定提交）；首页颜色页返回箭头跟随当前色；详情与首页都加
标题下/操作上滚动感知分割线（仿语言/许可证 dialog）。验证 `:app:assembleDebug` BUILD SUCCESSFUL，已发布 `202606251353`。

## 2026-06-25 - 颜色编辑器测试反馈修正（tab/title/图标/颜色条/滚动/边距）

详细发布日志见 `docs/features/thing-background-editor/debug-updates/update-20260625212527.md`。
要点：tab 改自定义胶囊 ripple、无下划线、选中文本随当前色着色；"调整颜色"标题随当前色着色；
随机=骰子、从世界取色=地球图标；修复颜色条 handle 右侧裁切；详情 BottomSheet 与首页颜色页
用限高滚动容器（渐变页/键盘弹出可滚动）；详情 BottomSheet 加左右边距与最大宽度居中。
验证 `:app:assembleDebug` BUILD SUCCESSFUL，已发布 `202606251325`。

## 2026-06-25 - 重构改变记事颜色的编辑器（Thing Background 编辑器）

详细发布日志见 `docs/features/thing-background-editor/debug-updates/update-20260625205701.md`。
要点：详情页与首页卡片外观面板改变记事颜色的 ColorPicker 重构为带「纯色/渐变」选项卡 +
黑→彩虹→白颜色条 + RGB/Hex 一行 + 随机/从世界取色的新编辑器；详情页用淡遮罩 BottomSheet，
首页面板就地切换颜色页；并入渐变方向、删除独立的 GradientOrientationDialogFragment。
搜索界面的色相筛选不变。

验证：`:app:assembleDebug` BUILD SUCCESSFUL；已用 `:app:publishDebugUpdate` 发布
`202606251258` 到阿里云 debug update channel。

## 2026-06-21 - 筛选下隐藏没有匹配记事的文件夹

这次 debug update 修正首页 Folder Card 在 `status + typeFilterMask` 筛选下的显示规则：
文件夹本身仍然允许为空并保留在数据模型里，但当前筛选条件下，如果某个 Folder subtree
里没有任何匹配的真实记事，这个 Folder Card 就不会出现在记事列表中。

- **用户反馈**：在 state 和 type 筛选条件下，文件夹里如果没有相关记事，就不要出现在记事列表里。
- **实现方式**：收紧 `ThingFolderDAO.getFolderEntriesForTypeFilterProjection()` 和缩略图子
  Folder 判断路径，让主列表使用的 type-filter projection 必须满足
  `countDescendantThingsForTypeFilterProjection(...) > 0` 才显示 Folder Card。
- **保留语义**：Structurally Empty Thing Folder 仍然是有效用户内容，不恢复自动删除；这次只影响
  首页列表 projection 的可见性。配置/浏览类入口使用的 `getFolderEntriesForProjection()` 保持不变。
- **文档同步**：更新 `docs/features/thing-folders/` 和 `docs/features/home-empty-state/`
  中关于 Empty Folder 与筛选投影的决策和 session 记录。

验证状态：

- `.\gradlew.bat :app:assembleDebug` 已通过，结果为 `BUILD SUCCESSFUL`。
- `git diff --check` 已通过，仅有仓库既有的 LF/CRLF 提示。

## 2026-06-21 - 将 WELCOME/NOTIFY_EMPTY 占位记事迁移为空状态 UI

这次 debug update 根据前面的充分讨论，完成首页空状态的大改动：应用不再把
`WELCOME_*` 和 `NOTIFY_EMPTY_*` 作为真实或临时记事呈现，而是在首页列表为空时
显示居中的 `ImageView` + `TextView` 空状态 UI。

- **首次启动和首次使用状态**：新数据库不再自动插入 `WELCOME_*` 记事；旧数据库会在初始化
  Home Empty State 历史后删除 legacy placeholder 行。首次使用提示改为读取原有
  `welcome_*` 字符串，并区分全局 first-use 与具体 `NOTE` / `REMINDER` / `HABIT` /
  `GOAL` 类型 first-use。
- **操作后变空状态**：完成、删除、恢复、永久删除、改变类型、移动记事、移动/删除/恢复/
  永久删除/解散文件夹等当前 Activity 内的用户操作，如果让当前 projection 变空，会显示旧
  `empty_*` 文案对应的瞬时操作结果提示；切换 status/type/filter/folder、搜索、颜色筛选、
  重启或重新打开后不再保留这个瞬时状态。
- **普通空状态**：操作结束后或用户已经创建过对应内容后，空列表使用新的 `home_empty_*`
  字符串，不再复用 `NOTIFY_EMPTY_*` 语义。
- **空文件夹状态**：Structurally Empty Thing Folder 不再被自动删除，允许作为用户内容保留；
  父列表可以显示空 Folder Card，打开空文件夹时显示文件夹专属空状态。显式创建空文件夹的入口
  仍按讨论结果 deferred。
- **数据和兼容清理**：新增 `HomeEmptyStateHistory`，从现有真实记事、现有 Thing Folder
  以及旧 `ThingsCounts.ALL` 初始化 first-use 历史；`ThingDAO`、`ThingManager`、
  `ThingsCounts`、Detail 返回路径、单记事 widget 配置和列表 widget 均移除对 legacy
  placeholder 的正常业务依赖。
- **复核修正**：发布前按 16 个确认点重新核对，补删了已经不用的
  `DBHelper.generateInsertInitialSQL()` 旧初始化 helper，并修正了 `ThingBackground.fromRandom()`
  里指向该旧函数的注释。

验证状态：

- `.\gradlew.bat :app:assembleDebug` 已通过，结果为 `BUILD SUCCESSFUL`。
- `git diff --check` 已通过，仅有仓库既有的 LF/CRLF 提示。

## 2026-06-21 - 修复从详情页返回后 Activity Header 标题布局状态不一致

这次 debug update 修复打开一个记事详情页再返回首页后，Activity Header 标题行和继续滑动时状态不一致的问题。用户反馈的表现包括标题最大行数、宽度、位置等可能变化，导致显示异常。

- **原因**：`ThingsActivity.onResume()` 会调用 `refreshActivitySurfaceAndHeader()`，该路径会刷新 Header 文本；而 `ActivityHeader.updateText()` 会重建标题的 `maxLines`、`maxWidth` 等布局约束。打开 Detail 返回时，RecyclerView 仍停留在原来的滚动位置，但 `onPause()` 已经把 `mScrollCausedByFinger` 置为 `false`，普通 `onScrolled` 不会立即再次同步 Header 折叠状态，导致 Header 暂时按展开态约束显示。
- **修复**：`refreshActivitySurfaceAndHeader()` 在 `mActivityHeader?.updateText()` 后注册一次 pre-draw 同步，使用当前 RecyclerView 的首个可见位置调用 `ActivityHeader.updateAll(...)`。这样下一帧绘制前会重新应用当前滚动位置对应的 `maxLines`、`maxWidth`、scale 和 translation。
- **影响范围**：修复放在统一 Header 刷新入口，不只覆盖 Detail 返回，也覆盖其它“刷新 Header 文本但列表滚动位置没有变化”的路径。

验证状态：

- `E:\projects\EverythingDone\gradlew.bat :app:assembleDebug` 已通过，结果为 `BUILD SUCCESSFUL`。

## 2026-06-21 - 修复 Drawer 多类型筛选空状态，并让 widget 配置页文件夹区域动态高度

这次 debug update 修复两个筛选和配置界面的细节：

- **Drawer type filter 空状态语义**：用户反馈，在 Drawer 中同时选择记录/提醒/习惯/目标时，结果和记事列表小组件配置界面选择这些类型不一致，Drawer 会把 generic `NOTIFY_EMPTY_UNDERWAY` 带出来。进一步确认后，正确语义不是“多类型一律不显示空状态”，而是每个被选中的具体 type 都要独立判断：如果当前投影里没有该 type 的真实记事，也没有任何可见子文件夹递归包含该 type 的记事，就显示对应的 `NOTIFY_EMPTY_*` 占位卡片。
- **实现方式**：`ThingDAO.getThingsCursorForDisplay()` 现在只在 all-types 投影中从数据库查询 `NOTIFY_EMPTY` 行；自定义 type filter 的空状态由 `ThingManager.rebuildThingListEntries()` 在内存中按 type 动态补位。UNDERWAY 下会分别补 `NOTIFY_EMPTY_NOTE` / `NOTIFY_EMPTY_REMINDER` / `NOTIFY_EMPTY_HABIT` / `NOTIFY_EMPTY_GOAL`；FINISHED/DELETED 下只有整个自定义筛选结果为空时才补对应 status 的通用空状态。临时占位不会写入数据库。
- **ActivityHeader 统计**：`ActivityHeader` 继续通过 `getVisibleChildCountsForActivityHeader()` 统计真实 Thing 和匹配的 Folder descendants；`NOTIFY_EMPTY` 卡片仍被排除，因此占位卡片不会被算作某个 type 的真实记事。
- **记事列表小组件配置页 Folder scope 高度**：上方文件夹列表不再使用固定 `176dp` 初始高度。`activity_things_list_widget_configuration.xml` 改为 `wrap_content`，运行时按可见行数动态设置高度：每行 `44dp`，最多 4 行，也就是最多 `176dp`；没有任何文件夹时只显示根行高度。展开或收缩文件夹后会重新计算高度，只有可见行数超过 4 行时才保持原来的滚动高度。

验证状态：

- `E:\projects\EverythingDone\gradlew.bat :app:assembleDebug` 已通过，结果为 `BUILD SUCCESSFUL`。
- `git diff --check` 已通过，仅有仓库既有的 LF/CRLF 提示。

## 2026-06-21 - 彻底移除 limit 投影协议并修复剩余 status/type filter 语义

这次 debug update 继续收尾 Drawer 类型筛选重构中 `limit -> status + typeFilterMask` 的迁移。根据用户确认，本次不处理“类型筛选跨 status 保留”“ActivityHeader 标题是否显示类型筛选”“widget status 是否完整接入”这三项，只解决剩余的语义问题并彻底移除全局 `limit` 概念。

- **彻底移除 active limit 协议**：删除 `Def.LimitForGettingThings`、`Def.Communication.KEY_LIMIT`、`App.getLimit()`、`App.setLimit()` 和 Detail/DateTime 之间已经无用的 legacy projection 参数链。跨 Activity / widget / authenticated Folder 打开路径现在统一使用 `KEY_STATUS`。
- **修复旧值混用风险**：`ThingsActivity`、`AuthenticationActivity`、`AppWidgetHelper` 等入口不再把旧 `limit` 数值当作 `status` 传递；`App.setStatus()` 和 `ThingManager.setStatus()` 会先通过 `ThingListProjection.normalizeStatus()` 归一化状态。
- **修复回收站文件夹语义**：`ThingFolderDAO` 的 type-filter projection 在 `DELETED` status 下重新允许 effectively deleted Folder，并在这种 Folder 内按 type mask 查询用户事项，不再错误要求子事项本身必须是 `state=DELETED`。
- **修复 Header 计数不一致**：`ThingsCounts.getThingsCountForStatus()` 的 FINISHED/DELETED all-types 范围与 `ThingDAO.getThingsCursorForDisplay()` 对齐，避免 Header count 比实际列表少。
- **文档同步**：更新 `docs/features/drawer-type-filter/execution.md`、`sessions.md`，新增 `followups.md` 记录已完成项和用户明确暂缓项。

验证状态：

- `.\gradlew.bat :app:assembleDebug` 已通过，结果为 `BUILD SUCCESSFUL`。
- `rg` 确认 app 代码中已无 `LimitForGettingThings`、`KEY_LIMIT`、`getLimit()`、`setLimit()`、`mLimit` 或 `changeToLimit`。
- `git diff --check` 已通过，仅有仓库既有的 LF/CRLF 提示。

## 2026-06-21 - 全局清理 LimitForGettingThings 引用，彻底移除 limit 概念

业务逻辑文件中 `Def.LimitForGettingThings` 和 `App.getLimit()` 的全部剩余引用已迁移到 `Def.ThingStatus` 和 `App.getStatus()`/`setStatus()`，涉及 15 个文件。`menu_drawer.xml` 中四个类型 item 已移除。全局引用从 50+ 处缩减至 12 处，全部仅在 bridge/compat 层。

## 2026-06-21 - Widget 配置页类型 icon 去透明度 + 摘要文本格式对齐 Drawer

这次 debug update 对齐记事列表 widget 配置页与 Drawer 的类型筛选 UI：

- 类型 icon 选中/未选中均移除 alpha 透明度变化，始终保持完全不透明。
- 摘要文本格式从"记事类型：全部"/"记事类型：提醒/习惯"改为"全部类型"/"提醒/习惯"（与 Drawer 一致），去掉 `widget_type_filter_summary` 前缀。

## 2026-06-21 - 类型提示文本颜色对齐 widget 配置页和卡片外观 panel

Drawer 类型筛选的摘要文本颜色从 `app_chrome_on_surface_secondary` 改为 `app_chrome_on_surface_hint`，与记事列表 widget 配置页的类型提示文本（`tv_widget_type_filter_summary`）和调整记事卡片外观 panel 里的标签文本使用同一颜色。

## 2026-06-21 - Drawer Header 统计文案统一 + 国际化补全

这次 debug update 简化 Drawer Header 的完成率标签并补全所有 locale 的国际化：

- Drawer Header 的完成率标签从按类型区分（所有记事/记录/提醒/习惯/目标完成率）统一为"记事完成率"（`completion_rate_things`），不再根据当前类型筛选变化。
- 新增 `completion_rate_things` 字符串到全部 13 个 locale：EN "Things completion rate"、ZH-CN "记事完成率"、ZH-TW/HK "記事完成率"、JA "完了率"、KO "완료율"、RU "Уровень завершения"、PT "Taxa de conclusão"、IT "Tasso di completamento"、HI "पूर्णता दर"、FR "Taux d'achèvement"、ES "Tasa de finalización"、DE "Abschlussrate"。
- 补全 `all_types` 字符串的 ZH-TW/HK 翻译："全部類型"。
- `DrawerHeader.updateTexts()` 简化为直接设置 `completion_rate_things`，移除所有 type/status 分支判断。

## 2026-06-21 - Drawer 选中背景颜色独立为 drawer_selected_bg

Drawer 的选中背景统一改为新颜色 `drawer_selected_bg`，不再复用 `app_chrome_divider`：

- 新增 `drawer_selected_bg` 颜色资源：浅色 `#1A000000`，深色 `#24FFFFFF`（与 `app_chrome_ripple` 一致）。
- 普通 drawer 导航 item（正在进行/已完成/回收站/设置/帮助/关于）选中时，`DrawerItemHolder.createItemBackground` 使用 `drawer_selected_bg` 作为选中态背景。
- 类型筛选 icon 选中时，圆形背景同样使用 `drawer_selected_bg`。

## 2026-06-21 - Activity Header 标题自适应对所有模式生效

这次 debug update 让 Activity Header 的标题 maxLines、maxWidth 和 RecyclerView header spacer 自适应逻辑在非文件夹视图下也同样生效：

- **标题最大行数**: 展开态最多 4 行，折叠态最多 2 行；移除 `mInFolderProjection` 限制，使类型筛选产生的长标题（如"记录/提醒/习惯/目标"）在非文件夹视图下也能正常换行。
- **标题最大宽度**: 折叠进度驱动 maxWidth 线性缩放，折叠态标题不会遮挡 actionbar 右侧 icon；移除文件夹专用判断。
- **RecyclerView header spacer**: `updateTitleLayoutForProgress(0f)` + `requestExpandedHeaderSpacerRefresh()` 已在非文件夹路径调用，不再被 `mInFolderProjection` 跳过。
- **折叠标题视觉高度**: `getCollapsedTitleVisualHeight` 和 `getCollapsedTitleLineCount` 统一使用行数感知计算，不再为非文件夹模式走高度硬编码分支。
- **紧凑折叠标题判断**: `shouldUseCompactCollapsedFolderTitle` 移除文件夹前置条件，长标题在任意视图下折叠后均使用 `COMPACT_TWO_LINE_FOLDER_TITLE_SCALE`。

验证状态：

- `.\\gradlew.bat :app:compileDebugKotlin` 已通过，结果为 `BUILD SUCCESSFUL`。
- 本次发布使用 `:app:publishDebugUpdate` 发布到阿里云 debug update channel。

这次 debug update 修复类型筛选后 Activity Header 和数据统计不正确的问题：

- **Activity Header 统计**: `rebuildThingListEntries()` 中 `getFolderEntriesForProjection` 改为调用 `getFolderEntriesForTypeFilterProjection`，同时传入 `mStatus` 和 `mTypeFilterMask`，让列表中的文件夹卡片统计（recursiveThingCount）和直接记事数量都反映当前类型筛选结果。
- **大文件夹缩略图**: `getFolderThumbnailPreviewEntries` 改为统一调用 `getThumbnailEntriesForTypeFilterPreview(folder, mStatus, mTypeFilterMask)`，确保大文件夹内部缩略图只显示符合当前类型筛选的记事。
- **ThingFolderDAO 全链路**: 所有 `ForTypeFilter*` 方法新增 `status` 参数（`getFolderEntriesForTypeFilterProjection`、`getThumbnailEntriesForTypeFilterPreview`、`countDescendantThingsForTypeFilterProjection`、`getThumbnailEntriesForTypeFilterProjection`、`getThumbnailFolderEntriesForTypeFilterProjection`、`countDirectChildFoldersForTypeFilterProjection`、`shouldIncludeFolderForTypeFilterProjection`），内部将 `thingSelectionForStatusAndTypeFilter(Def.ThingStatus.UNDERWAY, ...)` 替换为 `thingSelectionForStatusAndTypeFilter(status, ...)`，让类型筛选对所有 status 值生效。
- `getFolderEntriesForTypeFilterProjection` 中 deleted 文件夹的过滤条件从 `continue`（无条件跳过）恢复为 `status != Def.ThingStatus.DELETED` 条件判断。

验证状态：

- `.\\gradlew.bat :app:compileDebugKotlin` 已通过，结果为 `BUILD SUCCESSFUL`。
- 本次发布使用 `:app:publishDebugUpdate` 发布到阿里云 debug update channel。

这次 debug update 修复上一版 Drawer 类型筛选的崩溃和视觉问题：

- **修复闪退**：特定类型组合下 SQL 括号数错误导致 `SQLiteException: near ")"`。根因是 `getThingsCursorForDisplay` 特定类型分支多了一层 `((`，现已去掉多余的左括号。
- **类型指示性文本颜色**：从 `black_54p` 改为 `app_chrome_on_surface_secondary`（#8A000000 浅色 / #A8FFFFFF 深色）。
- **文本与 icon 间距**：summary 文本增加 `2dp` 底部 margin。
- **Icon 行居中**：用 `Gravity.CENTER_HORIZONTAL` 容器包裹 icon row。
- **Icon tint 颜色**：未选中改为 `app_chrome_drawer_item_foreground`（和已完成/回收站 icon 一致）；选中改为 `app_accent` + 圆形背景使用 `app_chrome_divider`（和正在进行/已完成/回收站选中态背景一致）。

验证状态：

- `.\\gradlew.bat :app:assembleDebug` 已通过，结果为 `BUILD SUCCESSFUL`。
- 本次发布使用 `:app:publishDebugUpdate` 发布到阿里云 debug update channel。

## 2026-06-21 - Drawer 类型筛选重构：导航项改为多选 icon 行，limit 拆为 status + typeFilterMask

这次 debug update 重构了 Drawer 的类型筛选系统和底层数据结构：

- **Drawer UI**: 将 记录/提醒/习惯/目标 四个独立导航目标替换为类似 widget 配置页的 5 个多选 icon（全部/记录/提醒/习惯/目标）。"全部"与具体类型互斥，除全部外可多选。icon 行上方有摘要文本（"全部类型"/"记录"/"记录/提醒" 等），上下有分割线与文件夹区域、已完成/回收站分隔。类型 icon 点击不关闭 Drawer，可连续多选。
- **已完成/回收站**: 保持不变，仍为互斥导航目标，与正在进行、文件夹树共同组成筛选条件。
- **类型筛选持久化**: 不持久化，每次启动重置为"全部类型"。
- **ActivityHeader 标题**: 显示正在进行/已完成/回收站/文件夹名称，不包含类型筛选文本。
- **DrawerHeader 位置文本**: 文件夹内显示文件夹名，否则显示状态名；不包含类型筛选。
- **后端重构**: 废弃并拆分 `Def.LimitForGettingThings`（0-6）为 `Def.ThingStatus`（UNDERWAY/FINISHED/DELETED）+ `typeFilterMask` bitmask。`ThingListProjection`、`ThingManager`、`ThingDAO`、`ThingFolderDAO` 全部改为接收 status + typeFilterMask 双参数；`Thing.getLimits()` 移除，`ThingWidgetInfo` 新增 status 字段。
- **App 兼容层**: `App.getLimit()`/`setLimit()` 保留为 deprecated 兼容包装，内部使用 `mStatus`；DrawerHeader、ActivityHeader、ModeManager 等消费者逐步迁移。
- `menu_drawer.xml` 中 `drawer_note`/`drawer_reminder`/`drawer_habit`/`drawer_goal` 四个 ID 已移除。

验证状态：

- `.\gradlew.bat :app:assembleDebug` 已通过，结果为 `BUILD SUCCESSFUL`。
- 本次发布使用 `:app:publishDebugUpdate` 发布到阿里云 debug update channel。

这次 debug update 根据用户进一步反馈微调上一版重复卡片修复：

- 上一版为了避免 Folder-scoped 记事列表 AppWidget 创建返回后出现两个新记事卡片，把这条 create-return projection rebind 的 whole-list appearing animation 一起关闭了。
- 用户确认这种情况下可以播放 things appearing animation。重新分析后确认，重复卡片的根因不是 appearing animation，而是 projection 已经 `notifyDataSetChanged()` 后又继续执行普通新建路径里的 `notifyItemInserted(newListPosition)`。
- 现在 `updateMainUiForShortcutFolderCreateDone()` 仍然会在普通新建插入逻辑前 `return`，不再触发 `armNewItemAnimation()`、`notifyItemInserted()` 或 `notifyItemChanged(1)`；但调用外部 Folder projection rebind 时重新传入 `shouldThingsAnimWhenAppearing = true`，让目标文件夹内容按普通 things appearing animation 出现。

验证状态：

- 静态检查确认该特殊路径仍会在普通创建通知前 `return`，且 handler 内不包含 `notifyItemInserted` 或 `armNewItemAnimation`。
- `.\gradlew.bat :app:assembleDebug` 已通过，结果为 `BUILD SUCCESSFUL`。
- `git diff --check` 已通过，仅有仓库既有的 LF/CRLF 提示。
- 本次发布使用 `:app:publishDebugUpdate` 发布到阿里云 debug update channel，并在发布后回读远端 `latest.json` 确认版本、APK URL、SHA-256 和 release notes。

## 2026-06-20 - 修正 Folder-scoped widget 创建返回后的重复卡片

这次 debug update 修正上一版 Folder-scoped 记事列表 AppWidget 创建返回后的列表重复问题：

- 用户反馈：从 Folder-scoped 记事列表 AppWidget 创建记事完成后，首页确实会回到对应文件夹，但列表里可能同时出现两个刚创建的记事卡片。
- 诊断确认根因在 `ThingsActivity.updateMainUiForCreateDone()`：命中 widget 创建返回后，`openExternalProjectionFromIntent()` 会先打开目标 Folder、重新加载 projection 并 `notifyDataSetChanged()`；随后同一个创建结果继续落入普通“同一列表新建记事”的路径，执行 `armNewItemAnimation()` 和 `notifyItemInserted(newListPosition)`。此时数据源里已经有新记事，再发插入通知会让 RecyclerView 短时间进入“已有数据 + 又插入一次”的错配状态，表现为重复卡片。
- 现在新增 `updateMainUiForShortcutFolderCreateDone()` 专门处理这条路径：Folder-scoped widget 创建返回只走一次外部 Folder projection rebind，命中后立即 `return`，不再继续执行普通新建卡片插入动画、`notifyItemInserted()` 或 `notifyItemChanged(1)`。
- 这条 create-return rebind 同时关闭本次 whole-list appearing animation，避免它和创建动画、数据刷新互相叠加。普通 widget/header 打开 Folder projection 仍保留原来的 external projection 行为和普通 appearing treatment。

验证状态：

- 静态检查确认 `updateMainUiForShortcutFolderCreateDone()` 不包含 `notifyItemInserted`、`armNewItemAnimation` 或直接 `notifyDataSetChanged`，且 `updateMainUiForCreateDone()` 命中该分支后会在普通创建通知前 `return`。
- `.\gradlew.bat :app:assembleDebug` 已通过，结果为 `BUILD SUCCESSFUL`。
- `git diff --check` 已通过，仅有仓库既有的 LF/CRLF 提示。
- 本次发布使用 `:app:publishDebugUpdate` 发布到阿里云 debug update channel，并在发布后回读远端 `latest.json` 确认版本、APK URL、SHA-256 和 release notes。

## 2026-06-20 - 修正记事列表 widget 文件夹选择与创建返回

这次 debug update 继续修正 Folder-aware AppWidget 的三个细节：

- 记事列表 AppWidget 配置界面的 Folder picker 现在复用 Drawer 的 `DrawerNavigationView.FolderIconDrawable` 来绘制文件夹图标。私密文件夹会显示和 Drawer 一样的“文件夹内带锁”图标；因为私密祖先而需要认证的子文件夹仍然走认证逻辑，但图标语义保持和 Drawer 一致。
- Folder picker 行样式做了微调：文件夹 icon 和文件夹名称之间额外增加 2dp 间距；右侧展开/收缩按钮从矩形 row ripple 改为 App Chrome 的圆形 ripple，并只在该文件夹确实有子文件夹时可点击。
- 从 Folder-scoped 的记事列表 AppWidget 点击创建记事并完成后，返回首页时会保留这次 widget 的目标文件夹。`DetailActivity` 会在 `ShortcutActivity` 发起的新建结果里带回 `KEY_FOLDER_ID`，`ThingsActivity` 收到后复用现有的外部打开 Folder projection 逻辑，让首页显示对应文件夹而不是根目录。创建流程仍然不使用 widget 的类型过滤强制新记事类型。

验证状态：

- `.\gradlew.bat :app:assembleDebug` 已通过，结果为 `BUILD SUCCESSFUL`。
- `git diff --check` 已通过，仅有仓库既有的 LF/CRLF 提示。
- 本次发布使用 `:app:publishDebugUpdate` 发布到阿里云 debug update channel，并在发布后回读远端 `latest.json` 确认版本、APK URL、SHA-256 和 release notes。

## 2026-06-20 - 修正 widget 分割线、单记事完成区与文件夹卡片前景色

这次 debug update 修正用户继续反馈的三个 AppWidget 细节：

- 记事列表 AppWidget 配置页的文件夹 scope picker 底部分割线改为固定显示。顶部 divider 仍只在列表已经向下滚动后显示；底部 divider 作为文件夹列表区域和下面类型/显示设置之间的稳定边界，不再因为滚动到底部而消失。
- 单个记事 AppWidget 不再显示旧的底部完成按钮，按钮上方那条与记事内容分隔的虚线也随 `ll_thing_action` 一起隐藏。提醒、习惯、状态等记事内容自身的分割线不受影响。
- 记事列表 AppWidget 里的 Folder summary card 前景色改为对齐首页 summary Folder Card：浅色文件夹卡片上 icon 和标题使用 `black_86p` 主前景，数量文本使用 `black_66p`，私密锁使用 `black_76p`；深色文件夹卡片对应使用白色侧的同级前景色。

验证状态：

- `.\gradlew.bat :app:assembleDebug` 已通过，结果为 `BUILD SUCCESSFUL`。
- `git diff --check` 已通过，仅有仓库既有的 LF/CRLF 提示。
- 已使用 `:app:publishDebugUpdate` 发布到阿里云 debug update channel，并在发布后回读远端 `latest.json` 确认版本、APK URL、SHA-256 和 release notes。

## 2026-06-20 - 调整记事列表 widget 配置页滚动分割线与间距

这次 debug update 继续修正用户反馈的记事列表 AppWidget 配置界面细节：

- 文件夹 scope picker 上方补齐和选择应用语言 dialog 一致的滚动分割线行为：标题下方的 divider 默认保留空间但不可见，只有当列表已经向下滚动、还能向上滚回时才显示；底部 divider 也改为只在下方还有可滚内容时显示。
- 五个“记事类型”图标继续使用圆形触摸 ripple，但触摸/选中圆从 48dp 收紧到 40dp，图标内容仍保持 24dp；相邻图标之间新增 2dp 间距，并把这两个数值收进 `dimens.xml`。
- 确认按钮与上方内容区域之间的间距改用 `app_chrome_dialog_divided_action_row_margin_top`，和带分割内容的 app chrome dialog action row 保持一致，避免底部空隙过大。
- 展开、收起、选择或认证文件夹后，会重新计算 scope picker 的上下 divider 状态，避免列表内容高度变化后 divider 显示滞后。

验证状态：

- `.\gradlew.bat :app:assembleDebug` 已通过，结果为 `BUILD SUCCESSFUL`。
- `git diff --check` 已通过，仅有仓库既有的 LF/CRLF 提示。
- 已使用 `:app:publishDebugUpdate` 发布到阿里云 debug update channel，并在发布后回读远端 `latest.json` 确认版本、APK URL、SHA-256 和 release notes。

## 2026-06-20 - 修正 widget 媒体透明度、图标/分割线颜色与列表配置页

这次 debug update 继续修正用户在上一版 widget 文件夹支持中发现的回归和细节问题：

- 单个记事 AppWidget 预览里的图片/视频透明度不再依赖 RemoteViews 的 `ImageView.setImageAlpha`。实测该调用在预览和 launcher RemoteViews 中不能可靠地产生实时效果，因此 `AppWidgetHelper` 改为在写入 RemoteViews 前按 widget alpha 生成已经合成透明度的媒体 bitmap，覆盖媒体背景、上/下/左/右封面图和视频帧缩略图。
- 单个记事 AppWidget 预览底部的确认按钮从系统 `Button` 改为普通 `TextView`，只安装 foreground pill ripple，并继续使用当前 Thing 的纯色或渐变背景来适配文字颜色。这样按钮常态下不再显示系统默认 background，只在触摸时显示 pill ripple。
- 记事列表 AppWidget 的 Folder summary card 现在和 Thing card 一样给 root 安装透明圆角 background、`clipToOutline` 和 API 31+ 的 outline radius，解决文件夹卡片没有圆角的问题。
- 全面排查了 RemoteViews Thing card 的小图标和分割线颜色适配：音频附件图标、清单勾选图标、提醒/习惯/状态/私密/记录图标都会按卡片前景明暗进行黑/白 tint；所有 widget 卡片里的 dashed separator 会根据卡片背景切换白色或黑色 drawable。颜色判断也改为使用 Thing background 的 representative color，而不是只读旧的 `thing.getColor()`。
- 修正从记事列表 AppWidget 打开正在做的记事时的入口差异：`AuthenticationActivity` 识别到当前 Doing Thing 后，会用主 app task flags 打开 `DoingActivity`，避免列表 widget 经过 authentication task 后影响 Doing 卡片宽度。
- 记事列表 AppWidget 配置界面更新：5 个类型 icon 保持圆形触摸 ripple，上方新增实时文本“记事类型：全部 / 提醒/习惯”等；列表/网格的显示模式改成类似自定义记事卡片外观 panel 的“左侧提示文本 + 右侧两个文本选项”，带 pill ripple 和选中态，不再使用 RadioButton。

验证状态：

- `.\gradlew.bat :app:assembleDebug` 已通过，结果为 `BUILD SUCCESSFUL`。
- `git diff --check` 已通过，仅有仓库既有的 LF/CRLF 提示。
- 发布流程使用 `:app:publishDebugUpdate` 发布到阿里云 debug update channel，并在发布任务后回读远端 `latest.json` 确认版本、APK URL、SHA-256 和 release notes。

## 2026-06-20 - 修正单个记事 widget 配置页卡片与 RemoteViews 预览

这次 debug update 修正上一版发布后用户继续指出的单个记事 AppWidget 配置页问题：

- 单个记事 AppWidget 配置页的 Thing/Folder 卡片必须继续和首页记事列表保持一致。重新阅读 `BaseThingsAdapter`、`ThingsAdapter` 和 `BaseThingWidgetConfiguration` 后确认，配置页虽然已经改成复用 `ThingsAdapter`，但它是通过混合 Thing/Folder adapter 手动委托绑定的，delegate adapter 自己没有真正 attach 到 `RecyclerView`，因此首页卡片依赖宿主 `RecyclerView` 宽度的媒体高度、背景媒体延迟布局和裁剪重放逻辑仍可能拿到默认宽度。现在 `BaseThingsAdapter` 增加了显式的 delegated host RecyclerView 绑定入口，单个记事配置页在初始化、span count 变化、布局完成后和每次 bind 前都会同步宿主 RecyclerView，让普通 Thing 卡片、大文件夹预览里的私密 Thing、图片/视频封面高度与裁剪都走首页同一套尺寸路径。
- 单个记事 AppWidget 配置页的大文件夹预览现在也接入配置页的点击语义：点击大文件夹里的 Thing 会直接选中该 Thing 并进入预览；点击大文件夹里的 Folder 会通过和顶层 Folder 行一致的逻辑打开该 Folder，私密 Folder 仍先走认证。
- 单个记事 AppWidget 预览继续使用 `RemoteViews`，不切换为 `card_thing`。针对圆角问题，新增 `bg_app_widget_card_clip.xml` 作为透明圆角 root background，`app_widget_thing.xml` 与 `app_widget_item_thing.xml` 的 root 都设置 `clipToOutline=true`，`AppWidgetHelper` 每次构建 RemoteViews 时也会恢复 root 的圆角 background、调用 `setClipToOutline(true)`，并在 API 31+ 使用 `RemoteViews.setViewOutlinePreferredRadiusDimen(...)` 设置圆角 outline，避免旧的 `setBackgroundColor(Color.TRANSPARENT)` 把 root 的圆角 outline 覆盖掉。
- 单个记事 AppWidget 预览的透明度滑杆仍然通过 RemoteViews 实时重建预览。图片/视频媒体不再依赖预先把 bitmap 画成半透明，而是把 opaque bitmap 写入 RemoteViews 后对对应的 `ImageView` 设置 `setImageAlpha`；这样前景封面、左右/上下媒体 panel 和背景媒体都能在预览和真实 widget 里使用同一个 alpha 路径。纯色/渐变背景仍保留原有的透明 background bitmap 路径，避免透明度叠加两次。
- 确认按钮保持无 background：布局里去掉默认 Button background，运行时仍只安装 foreground pill ripple 与适配当前 Thing 背景的文字颜色，pill 只在触摸反馈时出现。

验证状态：

- `.\gradlew.bat :app:assembleDebug` 已通过，结果为 `BUILD SUCCESSFUL`。
- `git diff --check` 已通过，仅有仓库既有的 LF/CRLF 提示。
- 已使用 `:app:publishDebugUpdate` 发布到阿里云 debug update channel；发布后会回读远端 `latest.json` 确认版本、APK URL、SHA-256 和 release notes。

## 2026-06-20 - 修正小组件文件夹支持的发布后反馈

这次 debug update 修正上一版 Folder-aware AppWidget 发布后的四类反馈：

- 单个记事 AppWidget 配置页里的 Thing Card 不应该只是“看起来像首页”，而是要复用首页记事列表的真实卡片绑定。现在配置页的 Thing 代理改为基于 `ThingsAdapter`，仅覆盖数据源、点击行为和 Folder 认证状态；私密 Folder 认证后显示的私密记事、包含图片/视频的记事、上下/左右/背景媒体、视频封面和保存的裁剪几何都走首页同一套绑定路径。
- 单个记事 AppWidget 选择记事后的预览界面做了 UI 修正：预览容器和 RemoteViews root 都安装圆角 outline，减少“只有上半部分有圆角”的情况；透明度滑杆改为使用当前 Thing 的纯色或渐变背景；右侧确认按钮改成无 background 的文本按钮，触摸反馈为 pill ripple，文字颜色同样适配当前 Thing 背景。
- 记事列表 AppWidget 的 Grid 行点击修正为每个可见 slot 绑定自己的 fill-in intent。行本身只负责 RemoteViews 打包，不再让第二/第三个 slot 误打开第一项；4-cell 宽度的记事列表 widget 在 Grid 模式下使用 2 列。
- 记事/记事列表 AppWidget 的透明度现在会应用到媒体 bitmap：前景缩略图、左右媒体 panel 和媒体背景都会先按 widget alpha 合成后再写入 RemoteViews，避免包含图片/视频的记事仍然保持不透明。

验证状态：

- `.\gradlew.bat :app:assembleDebug --console=plain` 已通过，结果为 `BUILD SUCCESSFUL`。
- 已添加静态检查，确认配置页 Thing 代理继承 `ThingsAdapter`、grid slot 独立绑定 fill-in intent、4-cell widget 返回 2 列、媒体背景和前景媒体都经过 alpha 合成。
- `git diff --check` 已通过，仅有仓库既有的 LF/CRLF 提示。
- 已使用 `:app:publishDebugUpdate` 发布到阿里云 debug update channel；发布后会回读远端 `latest.json`，确认版本、APK URL、SHA-256 和 releaseNotes 指向本次反馈修复。

## 2026-06-20 - 小组件支持文件夹、列表/网格展示与多类型筛选

这次 debug update 发布完整的 Folder-aware AppWidget 更新，来自用户关于“小组件也支持显示文件夹”的一轮设计确认和补充：

- Things-list AppWidget 不再新增单独的“文件夹”小组件，而是合并成通用记事列表小组件：配置页可以选择根目录或某个 Folder scope、选择 List/Grid 展示模式，并通过横排 All/Note/Reminder/Habit/Goal icon 进行单选或多选类型筛选。
- 配置页的 Folder picker 改成接近 Drawer / 移动到文件夹 dialog 的树形界面：正在进行行可选且默认展开，下面显示直接子 Folder；Folder 树支持展开、收起、滑动，选择私密 Folder 或展开其子树前会先走认证。
- Things-list AppWidget 渲染混合的 direct child Thing + direct child Folder summary card，不递归展开子孙；Folder 卡片点击后通过认证并打开 app 进入对应 Folder；Thing 卡片保持原有 RemoteViews 支持范围内的高信息量 Thing Card。
- Grid 模式按 widget 宽度自动派生列数，使用 row-oriented RemoteViews 打包来保留混合列表顺序并支持 full-span Thing / Folder card；List 模式仍保持全宽行，Thing 的 span 设置只影响 Grid。
- Header 标题和颜色现在反映 scope 与类型筛选：根目录 + 多类型显示 `提醒/习惯` 这类 `/` 拼接标题，Folder + 多类型显示 `文件夹名 · 提醒/习惯`；根目录用 app accent，Folder scope 使用对应 Folder 纯色或渐变，前景色自适应。
- 创建按钮在 Folder-scoped widget 中会把新 Thing 放进该 Folder，但不会由类型筛选强制创建类型；Reminder/Habit/Goal 仍由用户在创建流程里设置的提醒时间、重复等字段决定。
- 单个记事 AppWidget 配置页支持 Folder 导航：在选择记事的界面里 Folder 卡片与首页保持一致，点击 Folder 进入其内部，标题切换为 Folder 名称，返回按钮和左上角返回 icon 在 Folder 内部用于返回上一级；最终仍只允许选择 Thing。
- 底层迁移了 Things-list widget 配置存储，新增 target Folder、type filter mask、display mode，并把 legacy negative `thing_id` limit 映射到新字段，保留透明度、header 透明、simple view 等既有设置。
- 本轮最后补齐了三个 review gap：打开 app 时保留多类型筛选 mask；配置页对私密 Folder 选择/展开做认证；单个记事 widget 配置页复用首页 Folder Card 绑定而不是本地摘要卡片。

验证状态：

- `git diff --check` 已通过，仅有仓库既有的 LF/CRLF 提示。
- `.\gradlew.bat :app:assembleDebug` 已通过，结果为 `BUILD SUCCESSFUL`。
- 已使用 `:app:publishDebugUpdate` 发布 debug update `202606201023` 到阿里云 debug update channel；发布后回读远端 `latest.json`，确认 APK 为 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606201023.apk`，SHA-256 为 `090e6e54206558b9f9270eb59b4bd57e05f49c8d5e2ad969d1a8f6a11a587a88`，releaseNotes 指向本次 Folder-aware AppWidget 更新。

## 2026-06-20 - 进一步收紧正在做间距、微调创建图标、关闭文件夹返回出现动画

这次 debug update 继续处理上一个版本的三个跟进反馈：

- “正在做”火箭+喷剂图标和文字之间仍然偏远，因此把 `card_thing.xml`、`app_widget_item_thing.xml` 和 `app_widget_thing.xml` 里的 compound drawable padding 从 `8dp` 继续收紧到 `4dp`。
- `vec_ic_create_thing` 上一版描边略粗，因此把 vector path 的 stroke width 从 `28` 调整到 `18`。这样比完全不描边时更有分量，但不会像上一版那样显得厚。
- 根目录创建记事 FAB 的 icon tint 恢复为 `black_54p`，匹配最开始加号图标在黄色 FAB 上的颜色强度；文件夹内部 FAB 仍然根据文件夹颜色自适应前景色。
- 文件夹返回上层时的问题不是 smooth scroll，而是 `ThingsAdapter` 的 ordinary things appearing animation。现在从子文件夹返回父层、或通过 Activity Header 路径返回祖先层时，会关闭这次 rebind 的 appearing animation，让列表直接出现在保存的滑动位置；打开新的子文件夹仍保留从顶部出现的动画。

验证状态：

- `git diff --check` 已通过，仅有仓库既有的 LF/CRLF 提示。
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache` 已通过，结果为 `BUILD SUCCESSFUL`。
- 已使用 `:app:publishDebugUpdate` 发布 debug update `202606200558`，远端 `latest.json` 已确认 APK 为 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606200558.apk`，SHA-256 为 `f1775e6462875b3bc17a40a6eaa8de155696b09ffd2b9ffcdf99c0f6c1de4936`。

## 2026-06-20 - 加粗创建图标、收紧正在做间距、修正文件夹返回位置恢复

这次 debug update 继续处理用户对图标细节和文件夹返回体验的反馈：

- 创建记事 FAB 里的 `vec_ic_create_thing` 仍然显得偏细，因此在 vector 路径级别增加了白色描边，让图形更厚实，同时不改 FAB 本身的 padding 和布局。
- “正在做”覆盖层里的火箭+喷剂图标和“正在做”文字距离偏远，因此把 `card_thing.xml`、`app_widget_item_thing.xml` 和 `app_widget_thing.xml` 中的 compound drawable padding 从 `12dp` 收紧到 `8dp`。
- 从子文件夹返回上层时，不再把保存的 RecyclerView 位置恢复 `post` 到下一轮，也不使用 smooth scroll。现在会在父目录数据重新绑定后同步恢复 `LayoutManager` state，并在下一次绘制前用最终 first visible adapter position 无动画更新 Activity Header。
- Activity Header 的无动画更新现在会先取消残留的 translation、title scale、subtitle alpha 和 shadow 动画，避免返回父目录时旧动画继续推动 Header，造成位置闪烁或跳动。

验证状态：

- `git diff --check` 已通过，仅有仓库既有的 LF/CRLF 提示。
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache` 已通过，结果为 `BUILD SUCCESSFUL`。
- 已使用 `:app:publishDebugUpdate` 发布 debug update `202606200550`，远端 `latest.json` 已确认 APK 为 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606200550.apk`，SHA-256 为 `2573db59a6ab83257e4f174fc1f473d5c63e003423faf81485e5bb0742bc7879`。

## 2026-06-20 - 放大正在做图标并同步小组件
这次 debug update 修正上一版 `vec_ic_doing_thing` 的后续反馈：

- 用户反馈更新后的“正在做”图标图像偏小。重新量了旧 `ic_doing_thing.png` 的可见区域，旧 mdpi 资源是完整 `44dp × 48dp` 可见画布；上一版新 vector 虽然保留了同样的 intrinsic size，但火箭主体实际占用宽度明显更小。现在放大了 `vec_ic_doing_thing` 内部的火箭和喷射形状，同时仍保留 `44dp × 48dp` 的画布，避免影响 cover 文本排版。
- 用户要求确认所有位置是否都更新为新 icon。源码搜索确认还有 `app_widget_item_thing.xml` 和 `app_widget_thing.xml` 两个小组件布局仍在引用旧 `@drawable/ic_doing_thing`；现在它们也已切换到 `@drawable/vec_ic_doing_thing`，和 `card_thing.xml` 使用同一个 vector。
- 旧的密度 PNG 资源文件暂时保留，避免已存在的 launcher RemoteViews 或其他缓存状态在刷新前找不到旧资源；但布局源码中已经没有 `@drawable/ic_doing_thing` 引用。

验证状态：

- 源码搜索确认 `app/src/main` 下已经没有 `@drawable/ic_doing_thing` 引用，当前 `@drawable/vec_ic_doing_thing` 只出现在 `card_thing.xml`、`app_widget_item_thing.xml` 和 `app_widget_thing.xml` 的 doing cover 中。
- `git diff --check` 已通过，仅有仓库既有的 LF/CRLF 提示。
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache` 已通过，结果为 `BUILD SUCCESSFUL`。
- 已使用 `:app:publishDebugUpdate` 发布 debug update `202606200536`，远端 `latest.json` 已确认 APK 为 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606200536.apk`，SHA-256 为 `a2db97bbf3a6a7147ad0f8c115064294b9beefa000bc32822be5c073d74722d5`。

## 2026-06-20 - 优化创建/正在做图标、文件夹返回滚动位置和渐变 icon tint
这次 debug update 继续处理用户对文件夹投影界面和记事卡片动效/图标的反馈：

- 用户反馈 `vec_ic_create_thing` 放在创建记事 FAB 中时视觉上略微偏左上，而且线条显得偏细。现在在 vector 内部把图形略微放大，并向右下微调，让 FAB 中的视觉重心更接近居中，同时不改动 FAB 本身布局。
- 用户反馈记事卡片右滑时出现的“正在做”图标也应跟随新的 `vec_ic_start_thing` 风格。现在新增 `vec_ic_doing_thing`，保留旧 `ic_doing_thing` 在卡片 cover 上的 `44dp × 48dp` 显示尺寸，上半部分复用新的火箭轮廓，下半部分用同风格的 vector 喷射形状；`card_thing.xml` 的右滑 cover 已切换到新 vector，widget 暂不顺手改动。
- 用户反馈首页或文件夹滚动到某个位置后，打开子文件夹再返回会跳回上一层顶部。现在 `ThingsActivity` 会在进入子文件夹或切换路径前保存当前 `ThingListProjection.key()` 对应的 `RecyclerView.LayoutManager` 状态；从子文件夹返回父目录或点击路径返回上层时，会在列表重新绑定后恢复父目录此前的滚动位置。新打开的子文件夹仍然默认从顶部开始。
- 用户反馈文件夹内部 actionbar icon 在渐变色文件夹中看起来比 Activity Header 文本淡。诊断后确认纯色 tint 已经通过 `DisplayUtil.opaqueTintDrawable(...)` 把旧半透明图标的 alpha 归一化，但渐变 tint 路径此前直接使用原始 alpha mask。现在 `BackgroundUtil.tintDrawable(...)` 的渐变分支会把 icon 实际像素区域的最大 alpha 归一到不透明，再用 Folder 渐变填充这个 mask，避免 tint 到整个触摸区域，也避免老图标资源显得发灰。

验证状态：

- `git diff --check` 已通过，仅有仓库既有的 LF/CRLF 提示。
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache` 已通过，结果为 `BUILD SUCCESSFUL`。
- 已使用 `:app:publishDebugUpdate` 发布 debug update `202606200526`，远端 `latest.json` 已确认 APK 为 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606200526.apk`，SHA-256 为 `d0087e48b0b409e85c24304ebc07315bc82a7ccdbf7af5b6d302321553d22df9`。

## 2026-06-20 - 修正 Activity Header 递归记事数并更新创建/开始图标
这次 debug update 修正文件夹相关数量显示和两个常用操作图标：

- Activity Header 里的记事数量现在会统计当前层直接记事 + 所有直接子文件夹里的递归记事数量，不再只统计当前列表直接可见的记事卡片。
- 文件夹数量仍然表示当前层直接可见的子文件夹数量；如果文件夹数或记事数为 0，仍会省略对应段落。
- 从 Everything-Android 复制了 `vec_ic_create_thing`，并把 ThingsActivity 创建记事 FAB 的 icon 换成这个 vector；同时根据 FAB 背景亮度显式 tint，保证默认黄色 FAB 和文件夹颜色 FAB 上都清楚。
- 从 Everything-Android 复制了 `vec_ic_start_thing`，并把 Detail 底栏、设置页、明显通知页和系统通知 action 里的开始做事 icon 全部换成新 vector，原有控件尺寸不变。

验证状态：

- `git diff --check` 已通过，仅有仓库既有的 LF/CRLF 提示。
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache` 已通过，结果为 `BUILD SUCCESSFUL`。
- 已使用 `:app:publishDebugUpdate` 发布 debug update `202606200438`，远端 `latest.json` 已确认 APK 为 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606200438.apk`，SHA-256 为 `a2a1c2c0bbc2c27d513a921efca20a0f4c259197f317cfc8e8764dabc90e7d12`。

## 2026-06-20 - 优化文件夹内部颜色氛围与数量提示
这次 debug update 继续调整文件夹内部 ThingsActivity 的颜色和 header 细节：

- 文件夹内部列表和大文件夹卡片使用的 muted folder surface 进一步靠近 `bg_activity_things`，只保留更轻的一层文件夹色系提示，浅色和暗色模式都会更克制。
- 在文件夹内部，创建记事的 FAB 会使用当前文件夹的纯色或渐变色；离开文件夹后恢复普通 `app_accent`。
- 在文件夹内部，普通 actionbar 的菜单图标和 overflow 会使用当前文件夹的纯色或渐变色 tint；回到非文件夹界面会恢复 app chrome tint，避免残留上一层文件夹颜色。
- 在文件夹内部进入选择模式时，contextual actionbar 和 statusbar 占位 view 会使用当前文件夹的纯色或渐变色；里面的关闭按钮、菜单图标和标题文字会根据文件夹颜色自动选择偏黑或偏白的前景色。
- 非文件夹内部的 Activity Header counts 也会显示直接子文件夹数量，并和记事数量一样省略为 0 的段落，例如只显示 `X件记事` 或 `X个文件夹`。

验证状态：

- `git diff --check` 已通过，仅有仓库既有的 LF/CRLF 提示。
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache` 已通过，结果为 `BUILD SUCCESSFUL`。

## 2026-06-20 - 优化文件夹内列表背景和 Activity Header
这次 debug update 继续调整文件夹界面与大文件夹背景：

- 大文件夹和文件夹内列表使用的 muted folder surface 更偏向 `bg_activity_things`，文件夹本身色系只保留更轻的一层提示。
- 打开文件夹后，ThingsActivity 主列表背景和状态栏占位背景会切换为当前文件夹对应的 muted surface；返回根目录或切换到非文件夹界面时恢复为 `bg_activity_things`。
- Activity Header 中当前文件夹名称会使用文件夹自身的纯色或渐变文字效果；根目录标题会恢复原来的 app chrome 颜色。
- Activity Header 的文件夹内数量提示会省略为 0 的类型，例如没有子文件夹时显示 `X件记事`，而不是 `0个文件夹，X件记事`。

验证状态：

- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache` 已通过，结果为 `BUILD SUCCESSFUL`。
- 已使用 `:app:publishDebugUpdate` 发布 debug update `202606200402`，远端 `latest.json` 已确认 APK 为 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606200402.apk`，SHA-256 为 `d348ba6bc1aff772c2faeee0a56c650106b5ae8cc4be43b3550a95463ee7338b`。

## 2026-06-20 - 优化大文件夹卡片内部背景色
这次 debug update 优化大文件夹 thumbnail 模式下的卡片内部空白区域和拖拽 overlay 背景：

- 大文件夹卡片内部不再固定填充 `bg_activity_things`，而是根据文件夹自身的纯色或渐变色生成一个非常接近当前列表背景的 muted surface。
- 浅色模式下只混入少量文件夹色，避免卡片变成明显的大色块；暗色模式下混入比例略高一点，让 `#121212` 背景上仍然能看出文件夹本身的色系。
- 渐变文件夹会保留原始渐变方向，并分别把起止色混向当前列表背景；纯色文件夹则生成对应的单色 muted surface。
- 拖拽 overlay 使用同一套 muted surface 先遮住内部原生 elevation 阴影，再绘制截图，避免松手前后大文件夹内部背景色不一致。

验证状态：

- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache` 已通过，结果为 `BUILD SUCCESSFUL`。
- 已使用 `:app:publishDebugUpdate` 发布 debug update `202606200345`，远端 `latest.json` 已确认 APK 为 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606200345.apk`，SHA-256 为 `abdf38d71fc930223b37fc0b29e4a814dc5be23202951c354cd19bedf7848996`。

## 2026-06-20 - 回退大文件夹真透明阴影方案以恢复流畅度
这次 debug update 回退上一版大文件夹 thumbnail 真透明外阴影方案，优先恢复列表滚动和 overlay 拖拽流畅度：

- 用户反馈：上一版使用 outside-only `MaterialShapeDrawable` 阴影后太卡，希望再确认有没有更优实现；如果没有，就回退到内部填充列表背景、不再追求真透明。
- 重新查看 Android 和 Material 的阴影模型后，确认真透明 + 无内部阴影 + 原生 elevation 三者无法同时用轻量公开 API 实现。上一版的 RecyclerView decoration + outside-only `clipPath` + compat shadow 方案虽然能保留 alpha 透明，但滚动和拖拽期间开销过高。
- 保留 `ThingListOverlayDragController.kt` 从 `activities` 移动到 `managers` 的结构调整；删除 `ThumbnailFolderCardShadowDecoration` 和 `OutsideOnlyRoundedShadow`，移除 `ThingsActivity` 中对应的 item decoration。
- `ThingsAdapter` 恢复 thumbnail 模式文件夹卡片的轻量实现：`CardView` 内部填充 `bg_activity_things`，`cardElevation/maxCardElevation` 恢复为普通记事卡片一致的 normal/dragging elevation，touch 和 Moving-mode elevation 动画也恢复走普通 `CardView` 路径。
- `DragOverlayImageView` 恢复为原生 View elevation：大文件夹 overlay 使用扩大的 overlay bounds 和 inset `Outline`，在内容区域先绘制 `bg_activity_things` 遮住内部 elevation 阴影，再绘制捕获的 bitmap。这样不再是真 alpha 透明，但避免了逐帧自绘阴影带来的卡顿。
- 更新 `docs/features/thing-folders/` 下的 preferences、decisions 和 sessions，记录当前取舍：thumbnail 大文件夹优先使用原生 elevation 和流畅度，内部空白区域用列表背景填充。

验证状态：

- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache` 已通过，结果为 `BUILD SUCCESSFUL`。
- `git diff --check` 已通过，仅有仓库既有的 LF/CRLF 提示。
- 已使用 `:app:publishDebugUpdate` 发布 debug update `202606200334`，远端 `latest.json` 已确认 APK 为 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606200334.apk`，SHA-256 为 `f4055cab9ef9fa86e8def43a93978d6e5809556f38f9888aee8de4e4beb11e28`。

## 2026-06-20 - 修复大文件夹卡片透明区域和 overlay 外阴影
这次 debug update 继续修正记事/文件夹 overlay 拖拽与大文件夹卡片外观：

- 用户反馈：`ThingListOverlayDragController.kt` 不应该继续放在 `activities` 目录里；现在已经移动到 `app/src/main/java/com/ywwynm/everythingdone/managers/ThingListOverlayDragController.kt`，`ThingsActivity` 通过显式 import 使用它，Host contract 不变。
- 用户反馈：处于大文件夹/thumbnail 模式的文件夹卡片内部空白区域不是真正透明，正常列表态和拖拽 overlay 都是用 `bg_activity_things` 之类的背景色盖住内部阴影。这会让空白区域只是“看起来像背景”，不是实际 alpha 透明。
- 重新确认 Android 的阴影模型后，这次不再让透明的大文件夹 `CardView` 自己承担原生 `cardElevation`：原生 View/CardView elevation 基于 `Outline`，`clipToOutline` 裁剪的是内容，不是阴影；`View.draw(Canvas)` 截图也不会把实时阴影和 outline clipping 捕获进 bitmap。单个透明且有原生 elevation 的 View 很容易让阴影从透明像素里透出来。
- 新增 `ThumbnailFolderCardShadowDecoration` 和共享的 `OutsideOnlyRoundedShadow`：大文件夹列表卡片本体保持透明，原生 `cardElevation/maxCardElevation` 设为 `0f`；外侧阴影由 RecyclerView decoration 使用 `MaterialShapeDrawable` compat elevation 绘制，并用 even-odd path 只保留卡片圆角轮廓外侧，内部透明区域不再被填色或阴影污染。
- 更新 `ThingsAdapter` 和 `ModeManager`：thumbnail 模式文件夹卡片在 normal、touch、moving、selecting、退出模式等路径都保持原生 elevation 为 `0f`，避免按压或长按时又出现内部阴影。
- 更新 `DragOverlayImageView`：thumbnail 文件夹 overlay 不再使用系统 View elevation，也不再在 content rect 里绘制列表背景；它先在扩大的 overlay bounds 内绘制同一套 outside-only 圆角阴影，再裁剪并绘制带透明像素的 bitmap 内容。普通记事卡片和 summary 文件夹 overlay 仍然走原生 elevation 路径。
- 更新了 `docs/features/thing-folders/` 下的 preferences、decisions 和 sessions，记录“真实透明 + 外侧阴影层”的新决策，替代此前“用列表背景盖住内部阴影”的旧策略。

验证状态：

- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache` 已通过，结果为 `BUILD SUCCESSFUL`。
- `git diff --check` 已通过，仅有仓库既有的 LF/CRLF 提示。
- 已使用 `:app:publishDebugUpdate` 发布 debug update `202606200322`，远端 `latest.json` 已确认 APK 为 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606200322.apk`，SHA-256 为 `3fb04b67aa40780c09751d1d5d7e03d2e0f87a8ce07d6409fb781b492fb5d210`。

## 2026-06-20 - 修复 activity header 边界闪烁和两行文件夹标题缩放
这次 debug update 修复正在进行界面和文件夹界面滚动到 action bar 附近时的 header 闪烁：

- 用户反馈：记事列表向上滑动到第一个可见卡片接近 action bar 时，activity header 的标题可能突然消失，action bar 阴影也可能突然消失；再滑动一点又会闪烁回来。文件夹界面同样会发生，并且“X个文件夹，Y件记事”的副标题可能突然出现在列表卡片下面，被列表挡住。
- 根因是 `ActivityHeader.updateAll(...)` 还沿用旧的 102dp header 高度假设：当 `scrollY >= 102dp` 时会把 `scrollY` 重置为 0。RecyclerView 在边界处可能仍然认为第 0 个不可见 header spacer 可见，但它的 top 已经超过旧的 102dp 阈值，于是 header 状态会从“折叠”突然跳回“展开”，导致标题、阴影和副标题 alpha 闪烁。
- 现在这段逻辑改为把 `scrollY` clamp 到当前真实 header spacer 高度，不再把超出值重置为 0。这样标题、subtitle alpha 和 action bar 阴影会连续变化，不会在 header spacer 边界跳变。
- `ThingsActivity` 里所有传给 `ActivityHeader` 的 first visible position 也改为取 staggered grid 所有 span 的最小可见 adapter position，而不是只取 `positions[0]`，避免多列边界时误判第一个可见项。
- 如果文件夹名称折叠到 action bar 时需要显示两行，最终标题 scale 会比普通单行折叠标题更小一点，并且这个额外缩小会跟随同一个滚动折叠进度连续完成。两行标题的竖直居中计算也会立即使用两行视觉高度，避免抵达 action bar 时位置跳一下。
- 相关实现主要涉及 `ActivityHeader.kt` 和 `ThingsActivity.kt`。

验证状态：

- 源码检查确认旧的 `scrollY >= 102dp -> 0` 重置路径已经移除，`positions[0]` 传给 ActivityHeader 的路径也已经移除。
- `git diff --check` 已通过，仅有仓库既有的 LF/CRLF 提示。
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache` 已通过，结果为 `BUILD SUCCESSFUL`。
- 已使用 `:app:publishDebugUpdate` 发布 debug update `202606200248`，并回读远端 `latest.json` 确认 APK 为 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606200248.apk`，SHA-256 为 `8c967fa38d199131fec7b5129e0248c0101cc2c0039abc1c0cedb5ac7d4ebcaf`。

## 2026-06-20 - 修复文件夹内 header 滚动闪退和折叠居中
这次 debug update 针对文件夹内 header 滚动造成的闪退做根因修复：

- 用户提供的崩溃日志显示：`RecyclerView` 在触摸滚动过程中进入 `StaggeredGridLayoutManager.fill(...)`，随后抛出 `IllegalArgumentException: Called attach on a child which is not detached`。崩溃对象是 `rv_things` 的第 0 个 header spacer 对应的 holder。
- 根因是上一版为了支持长文件夹名，把 header 的真实测量高度同步给 RecyclerView 的不可见 spacer；但 `ActivityHeader` 在滚动过程中会改变标题宽度和折叠行数，触发 header 重新测量，继而在 `RecyclerView` 正在布局/attach 子 View 时执行 `notifyItemChanged(0)`，导致重复 attach 崩溃。
- 现在 spacer 不再跟随滚动过程中的 header 布局变化更新。它只在明确的展开态刷新点更新，例如进入/切换文件夹后的 `updateText()`，或者 header reset 之后。
- `ThingsActivity` 侧新增了一层保护：spacer 高度请求只保留最后一次，并且会等到 `RecyclerView` 不在 `isComputingLayout`、滚动状态也已经回到 `SCROLL_STATE_IDLE` 后，再应用到 adapter，避免以后类似路径再次在布局中途通知第 0 项。
- 文件夹名称折叠到 action bar 后的竖直居中也一起修正：折叠平移会根据当前标题可见布局重新计算，包含文件夹名最多两行时的实际视觉高度，而不是用展开态 header 块高度硬算。
- 相关实现主要涉及 `ActivityHeader.kt` 和 `ThingsActivity.kt`。

验证状态：

- 已把崩溃日志对应到 `RecyclerView` attach 路径，并确认 `updateHeaderSpacerHeight()` 不再从滚动更新路径触发。
- `git diff --check` 已通过，仅有仓库既有的 LF/CRLF 提示。
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache` 已通过，结果为 `BUILD SUCCESSFUL`。
- 当前工作区只检测到一台物理设备，没有默认 emulator；为避免擅自操作真机，本次没有执行 ADB 安装冒烟测试。
- 已使用 `:app:publishDebugUpdate` 发布 debug update `202606200220`，并回读远端 `latest.json` 确认 APK 为 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606200220.apk`，SHA-256 为 `47d0066c617659570aa5a86e1dcaa0951c20240098850a71d575b713d2dacc9f`。

## 2026-06-20 - 修正文件夹移动禁用态和文件夹内 header 显示
这次 debug update 继续修正文件夹移动 dialog 和进入文件夹后的 activity header 细节：

- 用户反馈：移动文件夹时，dialog 里源文件夹和它的子树已经不可选，但 icon 和文字仍然看起来像可选状态。现在这些行仍然可以展开查看层级，但文件夹 icon 和标题文字会使用 App Chrome 的 disabled 前景色，明确表达“不可选”；右侧展开/收缩按钮保持正常可点。
- 用户反馈：进入文件夹后，header 不需要显示完整路径，也不应该变成蓝色下划线链接。现在文件夹内 header 只显示当前文件夹名称，颜色和样式保持跟首页“正在进行”一致。
- 文件夹内 header 的副标题从“X项内容”改为直接显示“X个文件夹，Y件记事”，由 `ThingManager.getVisibleChildCountsForActivityHeader()` 按当前列表中的直接子文件夹和直接记事分别统计。
- 对较长文件夹名做了布局处理：展开状态下标题右侧会比右侧卡片更靠左；折叠到 action bar 区域时，标题最多显示两行，并且宽度会限制在搜索等 toolbar action 左侧；标题宽度会随现有 header 折叠进度变化。
- `ActivityHeader` 会根据真实测量高度刷新 RecyclerView 顶部的不可见 header spacer。文件夹名换成多行时，列表第一个可见卡片会跟着下移，避免文件夹信息和列表内容互相覆盖。
- 相关实现主要涉及 `MoveToThingFolderDialogFragment.kt`、`ActivityHeader.kt`、`ThingManager.kt`、`ThingsAdapter.kt`、`ThingsAdapterWrapper.kt` 和 `ThingsActivity.kt`。

验证状态：

- `git diff --check` 已通过，仅有仓库既有的 LF/CRLF 提示。
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache` 已通过，结果为 `BUILD SUCCESSFUL`。
- 已使用 `:app:publishDebugUpdate` 发布 debug update `202606200208`，并回读远端 `latest.json` 确认 APK 为 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606200208.apk`，SHA-256 为 `ab98531bd6fb30a47c054d67916d73fafccac2d4f604729ec9f9c3e9f689f503`。

## 2026-06-20 - 修复文件夹移动、私密展开、暗色 overflow 和媒体计数颜色

这次 debug update 继续修正文件夹树、私密文件夹和卡片外观相关的细节：

- 移动文件夹时，“移动到文件夹”dialog 现在会显示源文件夹和它的整棵子树；这些行可以展开查看层级，但会以不可选状态显示，不能作为移动目标。
- 移动文件夹 dialog 的文件夹列表改为稳定预留上下分割线和底部按钮间距。展开后从不可滚动变为可滚动时，不再因为动态切换 margin 或 `GONE` 分割线导致 dialog 内容抽动、闪烁。
- 移动文件夹/记事 dialog 里展开私密文件夹使用本次 dialog 内的临时验证状态；dialog dismiss 后再次展开私密文件夹需要重新验证。当前已经处于已验证的私密文件夹路径内时，不会重复验证。
- Drawer 中有子文件夹的私密文件夹会继续显示展开/收缩按钮。点击展开按钮时需要密码或指纹验证；关闭 Drawer 后，这次展开验证会失效，并折叠不在当前私密路径内的私密子树。
- 暗色模式下，进入文件夹后右上角的 overflow icon 会使用 app accent 黄色。返回“正在进行”根目录时会刷新 options menu，让当前文件夹专用的 overflow 入口消失。
- 图片/视频位于记事卡片上方、下方、左侧或右侧时，覆盖在黑色背景条上的“X张图片,Y个视频”提示文本和图标保持偏白色。调整记事卡片外观并改变颜色时，不再把这组覆盖层计数实时改成跟随记事颜色的自适应前景色；只有内联媒体计数继续跟随卡片前景色。

验证状态：

- `git diff --check` 已通过，仅有仓库既有的 LF/CRLF 提示。
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache` 已通过，结果为 `BUILD SUCCESSFUL`。
- 已使用 `:app:publishDebugUpdate` 发布 debug update `202606191645` 到 debug update channel，并已回读远端 `latest.json`，确认当前 APK 为 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606191645.apk`，SHA-256 为 `dadbcec9b7b43faf4465f9c5e4bf9a973a68fc6f3955637bd32e6793e8ced849`。
## 2026-07-02 - 录音波浪果冻感与重新开始失效修复

详见 `docs/features/recording-wave-visualizer/debug-updates/update-20260702162127.md`。本次修复录音波浪两个问题：一是 20ms 音频特征更新下的小幅输入变化会持续驱动视觉目标，造成果冻感；二是录完音后多次点击重新开始，旧监听线程没有被明确 stop/join，可能让可视化只剩水流动而失去波峰波谷响应。

实现上，`VoiceVisualizer.kt` 在 `receive(VoiceAudioFrame)` 入口加入 `stableInput()` 死区，过滤分量、水位、rhythm energy 和 pulse 的微小变化；`AudioRecorder.kt` 新增 `restartListening()`、当前 `RecordingThread` 跟踪、线程私有 stop 标记、旧线程 stop/join 和 `AudioRecord` 初始化兜底；`RecordingThread` 捕获启动时的 raw 文件和 `AudioRecord`，防止旧线程在下一次 start 后继续读取；raw 写入和 wav 转存都按真实读取长度写入；`AudioRecordDialogFragment.kt` 的重新开始按钮改为调用统一重启入口并清空旧保存文件。

验证：`:app:assembleDebug` BUILD SUCCESSFUL；`:app:publishDebugUpdate` 已发布到阿里云 debug 通道，code `202607020823`；未使用 adb。
## 2026-07-02 - 录音停止和重新开始按钮卡顿修复

详见 `docs/features/recording-wave-visualizer/debug-updates/update-20260702163121.md`。用户反馈 D27 后按下停止按钮、重新开始按钮都会出现 UI 卡死约一秒。诊断确认原因是 D27 为了彻底修复重启后动画失效，把 `AudioRecord.stop()`、`RecordingThread.join(600ms)` 和 raw -> wav 转存放进了 UI 点击链路。

本次保留 D27 的线程安全收束，但把阻塞工作移出主线程：停止按钮点击后立即切到 STOPPED UI，后台执行 `stopListening(true)`、wav 转存和重新开始监听；重新开始按钮点击后立即切回 PREPARED UI，后台删除旧 wav 并执行 `restartListening()`；后台完成前保存、重新开始、取消等相关按钮临时不可点。dialog 关闭时的 recorder release 和 `audio_raw` 清理也改为后台执行。

验证：`:app:assembleDebug` BUILD SUCCESSFUL；`:app:publishDebugUpdate` 已发布到阿里云 debug 通道，code `202607020832`；未使用 adb。
# 2026-07-13 - FableSol 解析光晕强度微调

按用户要求将 `analytic_halo_strength` 从 0.22 调为 0.21，其余三项质感参数和光晕算法不变。
相关参数回归通过；详见
`docs/features/audio-visualization-fable-sol/debug-updates/update-20260713124317.md`。未使用 adb。
已发布阿里云 Debug `202607130443`，远端 `latest.json` 已回读确认。

# 2026-07-13 - FableSol Stage 2-4 四项持续质感优化

同批加入四项可独立关闭的持续质感：1/f 慢呼吸进入环境波幅、稀有波包节奏和仅新生闪点的频率；
水体片元增加按远近行足迹带限的三倍频风梳解析微法线；近层浪峰增加固定日照、6 次方向瓣且不接
瞬态音频的朝阳 SSS；镜面闪点亮芯之前增加严格位于水体内侧的数学衰减光晕。87 项 FableSol
单测与 Debug 构建通过，APK 已确认包含新版 GLSL；未使用 adb。详见
`docs/features/audio-visualization-fable-sol/debug-updates/update-20260713120538.md`。
已发布阿里云 Debug `202607130406`，远端 `latest.json` 已回读确认。
