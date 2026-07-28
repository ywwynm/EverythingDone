# fablesol-video-export 会话记录

## 2026-07-27 — 自动漫反射白按屏幕能力与 HDR 强度自适应

- 用户要求落实上一轮样片分析：不把 350 尼特写成固定默认值，改为按每台设备屏幕亮度能力、
  当前 HDR 峰值强度等参数自适应，并在 FableSol 设置 Dialog 下方的规格/编码推导文字中
  直接说明算法。
- D45 公式落地为
  `floor(min(可信面板峰值×1.75÷HDR强度, 可信最大帧平均亮度, 400)÷25)×25`，
  最后受现有 200 尼特 UI 下限约束。任一屏幕字段缺失时忽略该项；两者都不可用时回退
  400 尼特。默认 9.6 强度下，2000 尼特面板得到 350 尼特、母版峰值 3360 尼特；350 是
  输入参数算出的档位，不是常量。
- `FableSolExportDisplayLuminance` 同时读取并校验 `desiredMaxLuminance` 与
  `desiredMaxAverageLuminance`；`FableSolTuning` 区分自动/手动状态。HDR 强度滑杆拖动期间，
  仅自动档的漫反射白滑杆与峰值说明实时联动；手动值保持不变，恢复默认后重新进入自动档。
- PQ 格式的推导文字追加自动公式；手动档则说明恢复默认会重新计算。设备诊断同时列出峰值、
  最大帧平均亮度、当前强度和自动结果。13 套语言资源已同步。
- 新增 7 个公式边界测试与 1 个设置数据流源码契约；定向测试和 `:app:assembleDebug` 通过，
  APK 已生成。未使用 adb。完整源码契约测试类另受工作区既有 Dialog 按钮布局改动影响：
  旧断言要求恰有两个 `android:gravity="center"`，与本轮无关。
- 已发布阿里云 debug 更新 `202607270804`；本地与远端 APK、元数据 SHA-256 均为
  `ff8802fef0a1a07089ffe98479e130ae7102e4330c19d7b6ae5621a978eed06b`。

## 2026-07-27 — HDR10+ 800 尼特水体颜色偏浅诊断

- 用户反馈 HDR10+、漫反射白 800 尼特、高光起点 90% 的产物中，`#E80859→#671416`
  红色渐变水体比画面预览偏白、偏亮、偏浅；Dolby Vision 8.4、HDR10、HLG 较暗但颜色更准。
- 用 FFmpeg/ffprobe 检查 `phone_videos` 中 DV 8.4 与 HDR10+ 200/500/800 尼特四个样本；
  确认 PQ/HLG、BT.2020、有限范围、Main10、静态亮度元数据与逐帧 ST 2094-40 SEI 均可解出。
- 反解 YUV、PQ/HLG 并按白锚归一化后，三个 HDR10+ 档及 DV 8.4 HLG 基础层的红色通道比例
  中位差约 0.7%～1.2%，排除 P010 矩阵、范围、对齐或 UV 顺序造成显著色偏。
- 800 尼特产物的整帧平均亮度约 452～552 尼特、平均 maxRGB 约 526～603 尼特，峰值约
  7703 尼特；约 48% 帧含超过 2000 尼特目标屏幕的像素。红色水体通常低于 786～1118 尼特
  HDR10+ 膝点，抽查帧最多约 1.6% 红色像素入肩，故 90% 高光起点不是大面积水体变浅的直接原因。
- 结论：码流像素基本正确；主因是 800 尼特绝对白锚将普通画面整体推至高 APL，再叠加
  9.6 倍、约 7680 尼特峰值，触发手机的亮度/色容积映射并改变主观明度。动态统计仍有
  block mean、PQ 域均值和 `fraction_bright_pixels=0` 等质量问题，会增加播放器差异，但
  尚无证据表明它单独导致水体色偏。实时预览还会按当前 `Display.hdrSdrRatio` 限制
  headroom，而导出明确把场景基线 `1.0` 编成 800 尼特，两者本来就不是绝对亮度一比一。完整记录见
  `analysis-2026-07-27-hdr10plus-water-color.md`。未修改实现，未使用 adb。
- 后续评估当前“面板峰值 ÷ 4”自动白锚：它可作为追求明亮观感的粗略档位，但不适合作为
  无条件的保真默认值。约 2000 尼特面板会得到 500 尼特白锚；配合 9.6 倍强度后，母版峰值
  约为 4800 尼特，即面板峰值 2.4 倍，同时仍忽略最大帧平均亮度和饱和色可显示亮度。较稳妥
  的待裁定建议是自动档采用约 `min(面板峰值 ÷ 5～6, 400 尼特)`，并在厂商上报可信时再受
  `desiredMaxAverageLuminance` 约束；峰值 ÷ 4 保留为“明亮”档，800 尼特继续仅供手动高亮创作。

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
## 2026-07-27 - HDR 自动顺序、本地化显示名与能力信息专业化

- 用户将自动格式顺序明确为
  `杜比视界 5 → 杜比视界 8.1 → HDR10+ → 杜比视界 8.4 → HDR10 → HLG`，
  并要求中文 locale 的全部用户可见位置统一使用“杜比视界”。
- `FableSolExportHdrFormat` 将固定英文 `stableLabel` 与 Android 字符串资源
  `displayNameRes` 分离。能力缓存和编码档位继续使用稳定标识；设置胶囊、自动格式说明、
  能力诊断、导出完成 Dialog、通知和失败信息统一使用本地化显示名。
- HDR 能力信息改为正式的结构化结论：列出通过验证的格式与自动选择；传递函数或 Profile
  不一致时显示请求值、实际值和“目标格式验证未通过”；HDR10+ 独立报告未提交和提交
  ST 2094-40 元数据两种验证条件。
- 重写 HDR10、HDR10+、HLG、杜比视界 8.1 / 8.4 的中文格式说明，移除“无需压缩”
  “最高的一档”等不够严谨或与实际顺序冲突的表述；自动漫反射白说明同步改为“可用峰值 /
  可用最大帧平均亮度 / 向下量化”的正式口径。
- 探测契约版本升至 5，使旧缓存中的口语化诊断失效。新增顺序、稳定标识、本地化链路和
  禁用口语化措辞的回归测试；定向单元测试与 `:app:assembleDebug` 均通过，未使用 adb。
- 已发布阿里云 debug 更新 `202607270851`；本地 APK、本地与远端元数据、远端 APK 的
  SHA-256 均为
  `5e0a67df1bbce6bee5ca0fb3f0ce92a3364390ede9d41bb1f307bdc0d566ff8e`，
  文件大小与远端发布说明校验通过。


## 2026-07-27 - 编码器成为可选轴；修复 HDR 偏好粘死与探测参数不一致

用户在三星 Z Fold4 上报告两个现象：设备具备 HDR 编码能力，设置页默认却选中「关闭」；
选择 120fps 上限，产物却是 60fps 且使用 AV1 编码器，而设备存在多个 HEVC 编码器。

**诊断**

- 默认「关闭」是偏好被能力结论覆盖所致，且该覆盖不可逆（D50）。
- 产物为 AV1 60fps 的成因链：AV1 位于 HDR 阶梯中 HEVC 之后；该机唯一的 AV1 Main10 实现
  `c2.android.av1.encoder` 是软件编码器，而候选收集不区分软硬件；降级顺序为格式优先、
  帧率其次，AV1 在 120fps 不成立而 60fps 成立。
- 高通四个 HEVC Main10 候选在两档帧率下全部失败，具体原因**当时无法查证**：探测端一旦
  某格式成功即跳出循环，不记录帧率与编码器；导出端只保留最后一条失败原因，且仅在全部
  失败时才展示。探测固定恒定码率而导出默认恒定质量，两者验证的并非同一份 `MediaFormat`
  （D51）。

**改动**

- 能力探测改为逐 (格式 × 编码器族 × 帧率) 记录可行组合并整表缓存，SDR 一并纳入；
  探测契约版本升至 6，缓存签名加入编码模式。
- 新增编码器族选择（自动 / HEVC / AV1 / H.264），按可行组合表置灰，并实现冲突迁移。
  SDR 阶梯补入 AV1 Main10 与 Main8。
- 自动档不再使用软件编码器；软件实现在诊断、设置摘要与完成信息中标注。
- 完成对话框、通知与设置摘要写明实际使用的编码器。
- 能力结论不再回写用户偏好，写偏好一律要求 `fromUser`。

**验证**

新增 `FableSolExportCapabilityMatrixTest`（8 项）覆盖格式轴上 SDR 与通配的区分、软件编码器
不参与自动档、帧率回退、缓存往返与分隔符注入；`FableSolExportPipelineSourceTest` 新增四项
源码契约，并将此前断言"设备编不出 HDR 时必须写 false"的那一条改为断言其反面。
`:app:assembleDebug` 与 `:app:testDebugUnitTest`（339 项）均通过，全程未使用 adb。
## 2026-07-28 - 三台设备的逐轮定位：验证判据、三轴联动与 EGL config

上一节加入编码器选择之后，OPPO、三星 Z Fold4 与华为平板各暴露出一批问题。本节按设备逐轮
定位，共发布十一个 debug 版本，最终得到的结论几乎都是**验证判据本身不完整**，而不是设备
不支持。

### OPPO：派生结论没有带上全部约束

把编码器钉成 AV1 之后，格式胶囊已经正确地只留下 HDR10 与 HLG，说明文字却仍写着「当前为
HDR10+」——那段文字用的是探测得出的全局 `autoFormat`，即编码器也取自动时的答案。同类问题
还有两处：体积估算按帧率上限计算而非实际可达帧率；能力报告只列第一个落点，因而 HLG 漏掉了
同样成立的 AV1。三处统一改为在当前选择的全部约束下解析（D55）。

### 三星 Z Fold4：从「HDR 落到软件 AV1」查到 EGL config

初始现象是选了 120fps 却输出 60fps 的软件 AV1。逐轮排除：

1. 补齐诊断——此前一种格式只要有任一编码器通过就丢弃其余失败原因，SDR 更是完全没有报告。
2. 修正探测自身：8 位档此前被拿 10 位输入表面验证，与正式导出不是同一条链路。修好后 H.264
   与 HEVC 的 SDR 通路立刻恢复为硬件编码。
3. `CodecException` 的消息常为空，补出厂商诊断串、错误码与瞬时标志。
4. 最终定位到 `EGL_RECORDABLE_ANDROID`：该机共 4 个 10 位窗口 config，带该属性的为 0 个。
   缺少它，缓冲就不带视频编码器用途位，编码器无法消费。此前代码把它当作「给驱动的提示」
   直接降档，判断有误（D56）。改为在 `eglChooseConfig` 落空后自行枚举全部 config 逐项核对；
   该机枚举后结果不变，因此其硬件 HDR 在公开接口上确实不可达（D58）。

### 华为平板：两处判据把可用与不可用同时判反了

- HDR10 被自身校验否决，报错是 `Encoder changed profile 2 to null`。编码器未在输出格式里
  回报 `KEY_PROFILE` 不等于它改掉了 profile，OMX 系尤其常见。改为与色彩键一致处理。
- 全部 HEVC 档位导出成 0 字节文件，只有 H.264 有数据。根因是验证判据只到「拿到输出格式并
  走到流结束」，而 `INFO_OUTPUT_FORMAT_CHANGED` 一到达即可登记轨道并启动封装，此后一个实际
  样本都不产出，收尾依然成功返回。改为以实际写入的视频样本数为硬门禁，并在发布前核对落盘
  大小（D61）。该机的成因同样是 10 位表面缺少 recordable，而 SDR 阶梯的首选档也是
  HEVC Main10，因此关闭 HDR 也无法回避；补上样本门之后会自动落到 HEVC 8 位并保持 120 fps。
- 导出完成时闪退，原因是通知栏分享按钮由 `Intent.createChooser` 携带媒体 URI 构建，
  `PendingIntent.getActivity` 在构建当时执行 URI 授权并被该设备拒绝。收尾阶段的装饰性动作
  一律改为可失败（D59）。

### 交互与呈现

- 帧率、导出 HDR 格式、编码器改为三向互锁，帧率不再是「上限，不成立时自行降级」。冲突时按
  「保住刚变更的那一条、其余改动最少」枚举出成立的组合。
- 编码器胶囊说明改为三段式：档位特点、当前实际使用的编码器并标明硬件或软件、灰色选项含义。
- 恒定质量档要求该编码器能处理本次画布：Z Fold4 上声明支持恒定质量的编码器尺寸上限仅
  512×512，此前设置里摆着该档位、导出时却静默换成恒定码率。
- 位深进入结论并显示，写成「HEVC 10-bit（硬件编码）」这种形式。
- 「帧率上限」改称「帧率」；推导文字结论行加粗并与推导过程分隔；推导文字与设备能力报告支持
  长按整段复制。

### 验证

新增 `FableSolExportCapabilityMatrixTest`（12 项）与 8 项源码契约，覆盖三轴联动、样本门、
未回报 profile、EGL config 枚举、位深回退与复制。`:app:assembleDebug` 与
`:app:testDebugUnitTest`（370 项）均通过，全程未使用 adb。

**未完成**：设备能力诊断文本仍为中文硬编码。抽字符串不足以解决——能力缓存存的是已拼好的
整句，换语言会读出上一种语言的文本，需要先让缓存只存原始数据。已记入 followups。
