# fablesol-video-export 会话记录

## 2026-07-27 — 倾斜可关；导出改用应用实际外观（深色卡片）

- 用户提两项：导出时可以不加入手机倾斜数据（设置里加开关，只对实时录音有意义）；
  深色模式下导出的 dialog 卡片也应当是深色，现在只有画框变黑、卡片恒白。
- 倾斜：新增 `export_tilt` 偏好（默认开启）与设置页第一行勾选框「保留录音过程中可能的画面
  倾斜」，`FableSolVideoExporter` 关掉时不读 `EDmo` chunk，直接走与历史录音相同的竖直渲染
  路径。控件形态先做成胶囊，用户裁定改为勾选框；该勾选框未选中态也用完整渐变描边
  （`applyCheckboxAccent(uncheckedGradient = true)`，默认关闭），连同行涟漪与圆形 checkbox
  涟漪一起跟随换色。用户复核后再定两条：描边**不降 alpha**（试过 160/255，发虚），选中态
  对号按填充色明暗自适应；并把这套外观推广到本 Dialog 的全部勾选行与 `SettingsActivity`
  的 13 个勾选框（见 `memory/decisions.md` 2026-07-27）。本 Dialog 的三种勾选行合并为
  `makeCheckRow` 一份实现。
- 卡片恒白的根因是 Context：Service 拿的是 Application Context，而 `<application>` 没有
  `android:theme`，其主题是平台默认的 `Theme.DeviceDefault.Light.DarkActionBar`，
  `android.R.attr.colorBackground` 恒为浅色，与夜间资源无关；同时它的 `uiMode` 也读不到
  AppCompat 对 Activity 的夜间覆写。新增 `FableSolExportAppearance.themedContext()`：按
  `AppearanceUtil.isDarkModeApplied()` 钉死夜间位后套 `EverythingDoneTheme.Dialog`，
  导出全程只用这一个 Context，画框、卡片、时钟 hostDark 三者不再各判各的。
- 顺带修正一处潜在不一致：应用固定浅色而系统深色时，原先画框会按系统判成深色。
- `:app:assembleDebug` 与 `:app:testDebugUnitTest`（64 个套件）通过；新增两条源码契约测试。
  未使用 adb，深浅两种模式的产物外观待真机确认。
- 已发布阿里云 debug 更新 `202607270608`；本地、暂存与远端 `latest.json` 的 APK SHA-256 均为
  `e5bfd8a3fb5be1b8da6aed2ab58f8f4f424722fd97ecc0ee8bda59b5690cefcc`，`releaseNotes` 字段存在。
- 胶囊改勾选框后重新发布 `202607270628`，三处 SHA-256 均为
  `023c8da7e96c955c2506dde9b2bc6e4db8bbbf1fb63d8843542b65b73cf15ef7`，远端 `releaseNotes` 517 字。
- 勾选框外观定案（描边不降 alpha、对号自适应、推广到设置页）后发布 `202607270646`，三处
  SHA-256 均为 `2dbf44295434414aa821a568cadb7a012840eef10fc8c525d13e3161f809fd32`。
- 文案去掉「可能的」后发布 `202607270654`，三处 SHA-256 均为
  `758d453adb8138806642c7db9afb290c793e89f50319c68f454f1b0582126826`。

## 2026-07-26 — 导出图标缩至 22dp 与编码设置术语统一

- 用户反馈 `video_frame_save` 内容较丰富，24dp 放进录音 FAB 和附件播放 Dialog 时略大；
  要求稍微缩小，并把“码率模式”“体积随画面复杂度变化”分别改为“编码模式”“视频大小随
  画面复杂度变化”，同步其它语言。
- 两处按钮分别把 padding 从 16→17dp、8→9dp，可见图标统一为 22dp；56/40dp touch ripple、
  布局位置和记事渐变着色不变。
- 13 套语言资源同步调整两个文案。按要求未运行测试或 adb；发布任务构建成功，阿里云
  debug 更新 `202607261510`。本地、元数据与远端 APK SHA-256 均为
  `499c59a302eba317885d288c68fa05b2d34462548fdeb59ffb0d0194bee5106e`。

## 2026-07-26 — Material 导出图标与 HDR 探测缓存

- 用户否定海浪图标并最终指定 Material Symbols `video_frame_save`，要求补全左边框和
  上边框；同时反馈打开设置有卡顿，不应每次真实编码，且不支持 HDR 时要复用顶部提示的
  文本颜色和“设备不支持”文案。
- 源码诊断确认旧探测虽在后台，但每次打开都会立刻创建视频/AAC codec、10-bit HLG EGL、
  最大画布 FP16 scene targets 和完整水体 renderer，与 Dialog 首帧及实时预览争用资源；
  CQ 范围也会重复同步枚举 codec。
- HDR 结果现按探测实现/App/Android/system build 签名持久化：成功长期复用，失败缓存
  24 小时；首次解析延后 800ms 并使用后台线程优先级。实际探测保留正式 codec、HLG EGL、
  AAC/MP4 与输出格式验证，移除整套水体 renderer 初始化；CQ 结果另做进程缓存。
- 不支持标签保持 enabled 文本色与 `0.5` alpha，追加现有本地化设备不支持文案。图标基于
  Google 官方 outlined `video_frame_save`，只补顶部中央与左侧中央缺口，原播放/保存几何
  不变。
- 遵照用户要求未运行任何测试任务或 adb；`publishDebugUpdate` 构建并发布阿里云 debug
  更新 `202607261457`。本地、元数据与远端 APK SHA-256 均为
  `4b5c2f9cbd16c97e2d53911f2f34fb211140cf07651a963b478d8788101f20f5`。

## 2026-07-26 — 双层导出图标、HDR 能力门控与结束态重播

- 用户要求导出图标改成两层错落水波：底层从画框左侧贯穿到右侧，上层从中间偏左开始；
  HDR 开关在设备无法实际编码时置灰；音频自然播放结束后拖动进度再点击播放必须恢复，并把
  主播放/暂停图标精确放大到 32dp、56dp ripple 不变。
- 按诊断闭环先建立失败回归。确认播放根因是自然结束后 `PlaybackThread` 已 return 并释放
  codec，而 `seekTo()/play()` 仍向死亡线程发请求；HDR 设置此前只保存偏好，且导出顺序会在
  60fps HDR 之前先返回 120fps SDR；56dp 容器减去 14dp padding 后图标实际只有 28dp。
- 新增结束态 seek 重启策略与暂停的新解码线程，初始 seek 在首个输出格式前带入并提前建立
  采样率时间基准；`onPrepared` 保留用户所选进度。主按钮 padding 改为 12dp，得到 32dp
  可见图标。
- 设置页后台使用最大导出画布执行一帧真实 HDR 编码：复用正式 tier、codec、AAC、muxer、
  10-bit BT.2020/HLG EGL、FP16 scene targets 与输出格式校验；失败时取消 HDR 偏好并置灰。
  正式导出的尝试顺序改为 HDR 120→HDR 60→SDR 120→SDR 60。
- Python 模拟器用真实录音离线渲染 4 秒帧，从 y=595～602px 与 y=579～586px 两层轮廓拟合
  新图标；24dp 预览确认两层分离，临时帧和预览已清理。
- 新回归先失败后通过；完整 `:app:testDebugUnitTest` 共 298 项、0 失败、1 跳过，
  `:app:assembleDebug` 成功。未使用 adb。已发布阿里云 debug 更新 `202607261422`，本地、
  远端元数据与重新下载 APK 的 SHA-256 均为
  `ed068f54312b407855805fb919f260a41684d833d8e2bf3689fe2228228188ce`。

## 2026-07-26 — 64px 分享画布与播放 Dialog 视觉修复

- 用户补充四项反馈：进度/完成按钮文字没有居中于 ripple；微信/朋友圈裁掉右侧背景且要求
  宽高均为 64px 倍数；图标要从 Python 真实离线水面提取；播放 Dialog 的导出图标要跟随
  记事渐变，主播放/暂停图标要真正更大。
- 先建立五项失败回归：动作 View 中心重力、codec+64px 尺寸、完整 crop rectangle、真实
  水面图标来源、播放 Dialog 渐变字形与主图标缩放。逐项修复后全部通过。
- 画布按 codec alignment 与 64px 的最小公倍数扩展，只对称重绘中性背景并重新居中卡片；
  任一单边 crop metadata 会使候选失败。280/310/383dp 三档复核分别得到
  `1024×1472`、`1152×1472`、`1344×1472`，左右画框严格等宽。
- Python 模拟器用真实录音生成 2/4/6 秒离线帧，从 4 秒帧提取 y=542～551px 的顶层水面，
  拟合为新图标的开放贝塞尔轮廓；临时帧和预览已清理，模拟器仓库无遗留修改。
- 播放 Dialog 的导出图标改用完整 `ThingBackground`（含渐变方向），主播放/暂停图标在原
  56dp ripple 内由 24dp 放大为 28dp。完整单测 292 项、0 失败、1 跳过，APK 构建成功；
  未使用 adb。
- 已发布阿里云 debug 更新 `202607261333`；本地 APK 与远端元数据记录的 SHA-256 均为
  `b46e7416668c20b7763845db99af6b1793b95ad89fae8448fbf7cc1af6378f83`。

## 2026-07-26 — 完成通知结果同源与导出图标浅水波

- 用户要求完成通知与完成 Dialog 一样展示 HDR/SDR、实际帧率、实际视频大小和保存位置；
  同时要求导出视频图标保留画框，但水体不能再像山峰。
- Service 改为先构造唯一的 `FableSolVideoExportBus.State.Done`，Bus、完成通知、通知查看与
  分享入口都消费该状态。通知复用完成 Dialog 的四行文案，不再单独查询文件或拼接缺字段摘要。
- 图标保留圆角视频画框，内部从高振幅实心波带改成 1.8dp 圆头描边的低振幅开放贝塞尔水波。
  新增两项源码契约测试，完整 debug 单测共 289 项、0 失败、1 跳过，APK 构建通过。
- 未使用 adb。已发布阿里云 debug 更新 `202607261307`；本地 APK 与远端元数据记录的
  SHA-256 均为 `00c4996ed83661033312f49067a7fbe81a606b8bdbbab1420171c7f1030633bc`。

## 2026-07-26 — 接手完成导出链路事务化修复

- 用户在 Claude 多轮修复后要求直接接手。逐项落地第四次静态评审的 11 类问题：MP4 必须先
  完成 `muxer.stop()/release()` 才发布；旧系统公共相册权限、原子唯一文件与扫描确认；
  Service 主线程状态机、按任务取消、任务状态表和即时超时终态；codec/decoder 构造清理；
  profile+level、尺寸对齐、H.264 完整回退、实际 HDR 输出验证；CQ 动态空间门禁；完成态动作
  可用性。
- 编码候选升级为完整事务，连首帧/EGL/输出格式/`addTrack()`/后续编码失败都能清理后降级；
  HDR scene target 静默回退 RGBA8 会明确放弃 HDR 档。
- 新增状态隔离、几何对齐和管线源码契约三组 JVM 测试。全量单测和 debug APK 构建通过；
  全量 Lint 的本功能筛选无 error，项目全局仍有 488 个既有 error。未使用 adb，尚待真机
  验证实际 MediaCodec/HDR 与 API 26–28 相册行为。
- 已发布阿里云 debug 更新 `202607261246`；本地/远端元数据和 APK SHA-256
  `47c17e73260f24ae33c4388f8c373032692dc7920f1d5ed29afa5a9be811df85` 一致。

## 2026-07-26 — 完成态补充产物信息并恢复双按钮

- 用户明确视频本来就会默认保存到相册，因此撤销上一版三行操作，完成态只保留「分享」
  「添加到附件」两个横向按钮；二者文字与涟漪都使用当前记事的完整 `ThingBackground`，
  不再区分强调色主次。
- `FableSolExportSink` 在成功提交后新增读取实际文件大小与用户可识别保存位置的能力：
  MediaStore 路径优先查询 `SIZE`，失败时读取文件描述符长度；位置优先真实路径，分区存储
  不提供路径时回落到 `Movies/EverythingDone/文件名`。这些结果随
  `FableSolVideoExportBus.State.Done` 传递，不使用码率估算。
- 完成信息改为四行：导出完成、HDR/SDR 与实际帧率、格式化文件大小、最终位置；13 套语言
  资源已同步。上一版为 `ThreeActionsAlertDialogFragment` 增加的自定义第三操作已完全撤销。
- 源码反馈回路、`:app:testDebugUnitTest` 与 `:app:assembleDebug` 已通过；未使用 adb。
  已发布阿里云 debug 更新 `202607260941`，本地/远端元数据与 APK SHA-256
  `378bf18ce36d59d37ddc483e0b1d422bd371a52c429380d8ba7cbde50bb517b4`
  一致；待真机视觉确认。

## 2026-07-26 — 2dp 光学校正、进度 Dialog 统一与三行完成操作

- 用户复核 handle 几何对齐后要求 handle 与轨道共同再向左一点，形成视觉对齐；现保留
  Timely 字体感知的稳定着墨包络计算，仅把 SeekBar 整体固定向左越过 2dp，导出图标右缘
  继续独立对齐数字右缘。
- 导出进度 Dialog 移除根节点 12dp 底部 padding，改用标准取消/确定 Dialog 的动作行 8dp
  底边距；标题直接复用通知的 `fablesol_export_title`，中文统一为「导出音频海浪动画视频」。
- 导出完成态改用 `ThreeActionsAlertDialogFragment` 的纵向三行结构，依次提供「分享」
  「添加到附件」「保存到相册」。三项共用当前记事的完整 `ThingBackground`，文字与涟漪
  均支持渐变；自定义第三项不会被返回键或点外部误触发。
- 导出器本来就会在成功时提交 `MediaStore`，所以保存操作不再复制视频：现代系统确认已提交，
  旧系统对原文件补媒体扫描。源码反馈回路、`:app:testDebugUnitTest` 与
  `:app:assembleDebug` 已通过。已发布阿里云 debug 更新 `202607260920`，本地/远端元数据与
  APK SHA-256 `8162db3198cd417830915242b3ef1502c80b7a65786ab266f32944099106fe27`
  一致；未使用 adb，待真机视觉确认。

## 2026-07-26 — handle 外缘对齐与导出选项完整渐变换色

- 用户复核上一版后指出：左侧对齐的是进度轨道，不是滑杆 handle；并要求「帧率上限」
  「码率模式」的未选中胶囊轮廓、选中胶囊填充在海浪换色时正确跟色且支持完整渐变，
  不得使用 `representativeColor()`。
- 按 AOSP `AbsSeekBar` 实际坐标公式复现：自定义 20dp thumb 的
  `thumbOffset=10dp`，水平 padding 被清零时最小进度的 handle 左缘为 −10dp。现在先安装
  自定义 thumb，再把 SeekBar 左右 padding 设为实际 `thumbOffset`，使 handle 外缘精确
  落在 Timely 稳定着墨包络左缘。
- 胶囊原实现虽进入 `mAccentChipPainters`，但选中填充与未选中描边都只读取
  `mAppliedBackground.color`，直接丢掉 `endColor` 和 `orientation`。现改为
  `BackgroundUtil.fillDrawable()` 完整填充与 `GradientStrokeDrawable` 完整渐变描边；
  描边以统一 alpha 淡化，换色动画每一档继续重绘。
- 源码反馈回路从「handle −10dp、渐变丢失」变为「handle 0dp、完整
  ThingBackground」；`:app:testDebugUnitTest` 与 `:app:assembleDebug` 通过。已发布阿里云
  debug 更新 `202607260708`，本地/远端元数据与 APK SHA-256 一致；未使用 adb，待真机
  视觉确认。

## 2026-07-26 — 播放对话框按 Timely 字形着墨包络对齐

- 用户确认上一版把时钟改为 `wrap_content` 后，进度条左缘与导出图标右缘仍未对齐可见数字。
- 逐行复核 `TimelyClockView.onMeasure()` / `onDraw()` 并扫描 33 套字形资产，确认根因是
  `advance` 字槽宽度与按 0.8 字高绘制的真实轮廓边界不同，字体侧边留白可达约 10dp。
- `contentLeftPx()/contentRightPx()` 改为返回字体、渲染模式和 Stencil kerning 感知的稳定
  着墨包络；播放对话框按该包络对齐，并补偿导出矢量图标 viewport 的 1dp 右侧留白。
- 资产几何检查覆盖 33 套字体的实心/描边 66 组组合；`:app:testDebugUnitTest` 与
  `:app:assembleDebug` 通过。已发布阿里云 debug 更新 `202607260649` 并核对远端元数据；
  未使用 adb，待真机视觉确认。

## 2026-07-27 — HDR 多格式导出静态代码审查

- 针对尚未提交的 Dolby Vision 5 / 8.1 / 8.4、HDR10+、HDR10、HLG 导出改动，梳理了设置页
  能力探测、编码候选、EGL surface/离屏 P010 两条链路、位级元数据、封装与发布门禁。
- 对照 Android `MediaCodec` / `MediaFormat` 与 FFmpeg 的 SMPTE ST 2094-40 字段定义，确认
  HDR10+ 目标显示峰值单位、正式 SEI 验收、P010 色彩范围、EGL 误门控、分位点统计口径、
  `slice-height=0`、探测旧状态和 Dialog 延后探测等问题；待修复项已写入 `followups.md`。
- `:app:testDebugUnitTest :app:assembleDebug` 通过；现有 HDR10+ payload 单测只覆盖固定头与
  总长度，无法发现字段语义错误。本轮未修改实现、未使用 adb，也未进行真机或产物播放验收。
