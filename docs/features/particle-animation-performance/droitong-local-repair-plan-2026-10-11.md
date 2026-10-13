# 卓易通：保留 SurfaceView 的局部修复与无 ADB 验证方案

日期：2026-10-11。本文保留提出时的方案；用户同日随后授权实施并发布，同包入口、候选与验证状态见 [实施记录](droitong-implementation-2026-10-11.md)。

## 目标与已知边界

用户确认先保留 SurfaceView，分别处理首页滑动粒子期间前景控件消失，以及 Dialog 打开时真实面板提前显示；本阶段只制定具体方案。

- 首页：汉堡菜单、ActivityHeader、FAB、actionbar 图标在动画期间消失，结束恢复。
- Dialog：单层和叠层均受影响，出现时完整面板盖在粒子上面，关闭正常。
- 用户反馈卓易通环境无法通过 ADB 连接。验证使用应用内入口、可导出报告和用户实看，不依赖 ADB、root、宿主文件系统或无线调试。
- 目标设备的 APK 版本、应用可见 Android API、卓易通版本及实际代码分支仍未知。不能根据鸿蒙版本直接推断 Android API，也不能仅凭接口存在认定其合成正确。
- 继续使用共享 SurfaceView、常驻 GL、栅栏和既有粒子模型。两个问题分别归因；不调整颗粒算法、改全屏 Dialog 架构或把 TextureView 作为本轮切换目标。

历史成本见 [TextureView／SurfaceView 核查](surface-texture-history-2026-10-11.md)；当前证据见 [首页分析](../swipe-complete-animation/analysis-2026-10-11-droitong-foreground.md) 和 [弹窗分析](../dialog-particle-dismiss/analysis-2026-10-11-droitong-window-alpha.md)。

## 一、首页前景层：优先区分内容与挂载问题

### 当前交接

`ParticleForegroundSurface` 为每个目标控件生成小位图，再经 HardwareRenderer／ImageReader 生成硬件缓冲。替代层当前以粒子 SurfaceView 的 SurfaceControl 为父节点。`enter()` 把原 View 的 `transitionAlpha` 设为 0，同时把替代缓冲加入窗口同步事务；`release()` 恢复原值。

已有同步不能简单解释为“先隐藏、过一会儿才显示”：正常实现把两者合并进窗口帧。真实缺口是取得 Image 或收到 committed 回调，并不能证明替代层最终有可见像素。整段消失、结束恢复更值得先查内容或合成路径，而不是增加固定延迟。

### 单变量诊断

| 编号 | 对照操作 | 能区分的原因 |
| --- | --- | --- |
| F1 | 虚构测试页预览原控件捕获位图，并在少量关键阶段读取 HardwareRenderer 输出；分别标注接管前、接管后 | CPU 位图已经空白：查隐藏状态、祖先透明度和离屏绘制；CPU 正常而硬件输出空白：查缓冲生成／同步。诊断读回需等待 acquire fence，失败标记不可读取，不能当成全透明。 |
| F2 | 使用内容已知的固定色块缓冲，在原父节点／原位置测试；原控件先不隐藏，色块放在不会与其混淆的位置 | 色块也不显示，降低业务控件绘制错误的可能；继续检查父子关系、坐标、alpha、裁剪和事务。色块能显示而真实内容不能显示，继续查真实缓冲与姿态。 |
| F3 | 复用同一缓冲，改为直接挂到窗口根节点，保持预期屏幕位置与其余条件可比 | 原挂载不显示、窗口根挂载显示，支持父子合成路径或其坐标映射异常；不能据此断言某个卓易通 API 有缺陷。 |
| F4 | 在确定可显示的路径中恢复真实前景，加入原 View 隐藏和同步交接 | 区分独立层本身不可见与进入／退出交接时序错误；检查首次、持续更新和恢复阶段。 |

这些是诊断页的受控实验。保留原控件可见只用于 F2／F3 定位，不直接作为正式修复，否则会产生粒子穿过 FAB、阴影变深等问题。

### 最小候选修复

**候选 F-A：修正捕获或参数。** 如果 F1／姿态日志已证明内容、alpha、位置或旋转错误，只修正该环节。捕获与原控件的屏幕隐藏状态分开处理，保留现有软件位图隔离 RippleDrawable 的方式；不恢复历史上会使第二个 HardwareRenderer 自主出帧的直接波纹录制。

**候选 F-B：改变自有前景层的父节点。** 如果 F3 有效，把这些自有层由“粒子 SurfaceView 的子层”改为“窗口根节点下的层”。这不是新建另一个 SurfaceView；粒子仍在原共享 Surface 上渲染。

- 复用 `ParticleCardSurfaceHandoff.prepare()` 已使用的公开 `rootSurfaceControl.buildReparentTransaction()` 模式。只操作应用自有层，不修改框架管理的粒子 SurfaceControl。
- 重新核对坐标原点、窗口偏移、旋转和裁剪，明确自有卡片交接快照与前景的顺序：前景必须在粒子和卡片交接快照之上；前景之间仍保持既有顺序。
- 需要统一调整自有层的 layer 区间。当前卡片快照为 `Int.MAX_VALUE - 1`，不能简单给每个前景复制同一个大数，也不能溢出或让不同层处于同一 Z 值。不依赖隐藏的相对层级接口。
- 原控件隐藏与替代层显示继续合入 `applyTransactionOnDraw()`；退出按相反顺序交接。保留并发动画的引用计数，最后一位使用者退出后才恢复与销毁。
- 已知准备失败、无效层、窗口脱离或超时须收尾并恢复原控件；迟到回调不得再次隐藏。不能把“图层有效、回调成功”当作能够自动发现屏幕不可见的判据。

F-B 涉及前景和卡片交接两处小范围代码，不改变粒子计算、绘制后端或逐帧纹理合成方式。仍须实测，不能预先承诺卓易通的根节点合成一定正常。

若两种挂载均失败，先依据 F1／F2 继续定位。把前景改画进共享 GL 表面的方案留为后续评估：它还要协调卡片交接快照、阴影、颜色和动态更新，不属于本轮可直接承诺的小改动。

## 二、Dialog 打开：分开验证隐藏方式与建立时机

### 优先对照

| 编号 | 唯一主要变化 | 结果解释 |
| --- | --- | --- |
| D0 | 保留当前 show 后设置 Window alpha 的路径 | 同 APK 基准，核对是否从第一帧就出现完整 Dialog，还是仅末段提前恢复。 |
| D1 | 隐藏仍用 Window alpha，但在本次 Dialog 第一个可见帧前建立状态 | 只有 D1 正常，支持首帧／属性更新时机问题；不必同时加入内容隐藏。 |
| D2 | 保持与基准相同的启动时机，额外隐藏普通 Decor 绘制，Window alpha 请求照旧 | D1 无效而 D2 有效，支持绕过窗口透明合成能够改善；仍需检查背景、阴影与末帧交接。 |

必要时在固定普通 Dialog 上先做短暂的“隐藏后恢复”测试，不启动粒子，用实看确认窗口 alpha 与内容 alpha 各自是否真正隐藏画面。D1／D2 在受控入口独立切换，不在第一次测试中同时更改窗口动画和完成时钟。

### 最小候选修复

- 若 D1 有效，在 `BaseDialogFragment`／`GestureAnchoredDialog` 的显示前准备路径建立隐藏状态，`startCondense()` 复用该状态。只能对本次确定使用出现粒子的实例生效；系统动画关闭、用户选择仅消失、重建恢复及启动拒绝必须立即恢复。
- 若 D2 有效，增加 Dialog 出现专用的显示状态管理：保存并隐藏 Decor 普通绘制及必要的背景／阴影，完成、取消、失败、detach 均恢复原值。不要逐个把所有子 View 改为 GONE／INVISIBLE，以免改变布局或销毁独立 Surface。
- 普通 Dialog 当前主要使用离屏快照；继续从原内容捕获，屏幕隐藏不能污染快照。直接对根调用 `draw()` 的现行实现不依赖窗口 alpha；新增隐藏方式仍必须用不透明内容比例和预览确认捕获有效，不能只判断 Bitmap 非空。
- **带独立 Surface 的 Dialog 不能照搬。** `ParticleCaptureRoute` 对出现动画的该场景优先窗口 PixelCopy。若 Decor 本身变透明，窗口缓冲可能已是空图，需要配套改成离屏普通内容加独立 Surface 捕获，并单独验证 Surface 的隐藏／恢复；父 Decor 的 alpha 不作为充分保证。
- 首轮先在普通确认框、添加附件和叠层普通 Dialog 上定方向，再验录音等实际包含独立 Surface 的弹窗。若它们仍提前露出，局部方案尚未覆盖该类窗口，不能推广或宣称全部修复。是否需要扩展参与者的显示控制，取决于这一步证据。
- 不把 `windowAnimations = 0` 列为首轮修复：当前保留它有历史原因，子类 onStart 会设置底部滑入，禁用也可能影响“仅出现时”模式的退出。只有证据表明入场动画覆盖状态，才单独处理入场控制并保留退出行为。
- 若基准实测属于“末段提前露出”，优先核对实际呈现信息与超时放行，修正恢复条件；不通过固定多等几十毫秒处理所有弹窗。
- 保留应用自有 dim、现有叠层宿主、键盘和取消语义。恢复真实面板后，等待窗口帧交接再撤粒子／快照；完成和失败回调只生效一次。

## 三、无 ADB 测试包

### 应用内入口

后续实施时在 debug 包的粒子设置页增加“显示兼容性检查”入口，打开普通 Activity，避免测试入口自身依赖故障中的 Dialog。当前入口尚不存在。

- 复用 `ParticleSwipeShadowProbeActivity` 的虚构列表、真实滑动／回位管线和 `ParticleChromeProbe`，补齐实际首页前景范围；不读写真实记事。
- Dialog 必须用真正的 `BaseDialogFragment` 打开普通、底部和叠层窗口。粒子设置页内的嵌入式弹窗预览不是独立 Window，不能替代该项测试。
- 提供“原实现”“只验证前景候选”“只验证 Dialog 候选”，两项分别有效后才组合。更细的 F1–F4／D1–D2 由诊断页按顺序引导，不要求用户理解 API 名称或手工组合大量开关。
- 同一 APK、固定虚构内容和随机种子，显示测试编号与当前实际分支。异常不能悄悄退到 TextureView 后仍报告 Surface 候选通过。
- 实验选项仅本次诊断会话有效，退出恢复，重启默认关闭；以后若支持真实首页临时复验，也须明确显示生效状态并自动还原。

### 报告内容

报告在应用内生成，不依赖读取系统 logcat。既有 `ParticleCaptureDiagnostics` 等日志需接入本次会话的结构化记录，而非让用户设法读取私有目录。

- APK 构建标识、测试编号、粒子样式、应用可见 Android API／Build／GL 信息、实际 Surface 分支、根节点／子层模式。容器报告的 Build 信息只标为“应用可见”，卓易通版本允许用户手填，不能冒充宿主真实型号。
- 前景：捕获前后可读样本、目标 bounds、坐标原点、alpha／transitionAlpha、层级、Surface 有效性、buffer／fence 状态、隐藏／恢复／事务回调时间、最后引用释放状态。
- Dialog：Window 与 Decor alpha、背景／阴影控制状态、windowAnimations、单层／叠层宿主、独立 Surface 数量、快照来源、首末帧、恢复原因、超时或取消。
- 分开标注“生成缓冲”“提交”“committed”“呈现回调／驱动时间戳”“超时推定”，缺失信息写 unavailable。API 35+ 的 completed 信息可辅助诊断，不当作 API 34 的必要条件，也不作为肉眼可见的充分证据。
- 每项由用户选择“正常／前景消失／面板提前出现／闪烁或残影”，与同一个 run ID 对应。

提供“复制简报”和“分享／保存诊断包”。包内可包含 JSON 时间线与虚构场景关键快照，不打包真实记事；若容器的文件分享不便，页面仍可显示和复制核心结果。测试包经现有 APK 更新／下载方式安装到卓易通内，更新能力以该设备实际情况为准，不要求卸载应用或清除数据。

### 最终画面与性能

- 首轮以正常速度实看为准；有需要再做一次系统录屏，画面里带测试编号。若录屏与肉眼不同，记录差异并用另一台设备拍摄，不能用录屏未复现否定用户观察。
- PixelCopy 只能证明指定源缓冲的内容，不能证明跨窗口最终 Z 顺序正确；已提交／已呈现的层也可能透明、被裁剪或被遮挡。
- 先做原实现／候选的小范围配对：首页慢拖回拉、松手回位、滑动完成；普通与叠层 Dialog 各测试首次及连续打开。找到有效候选后再连续 12 次验证，补底部面板、独立 Surface、返回／取消、中途切后台、两种粒子样式和必要的横屏／主题回归。
- 图像采样与性能测量分开。轻量性能模式关闭额外 GPU 读回／持续截图，比较相同场景的准备时间、首个粒子提交、可获得的呈现延迟、帧间隔分布及收尾停顿；窗口 FrameMetrics 不代表独立粒子 Surface 的全部耗时。
- 合格条件是原问题稳定消失，同时无双份阴影、粒子穿过前景、凝聚末帧空档、关闭退化、交互异常或反复操作资源增长；不能以日志成功或桌面单测替代设备画面。

## 四、实施顺序与代码范围

1. 先补可直接打开的诊断页、少量关键证据与导出；将能够在本机静态核对的候选一并放入同一测试包，避免每换一个开关就重新安装。
2. 前景优先 F1–F3，按证据只采用 F-A 或 F-B；D0–D2 独立定位。第一包不包含全面 GL 前景重绘或渲染后端重写。
3. 局部修改预计集中在 `ParticleForegroundSurface`、`ParticleCardSurfaceHandoff`、`BaseDialogFragment` 和 `ParticleDismissController`。若隐藏内容方案覆盖独立 Surface，再涉及 `ParticleSnapshot`／`ParticleCaptureRoute` 及相应参与者。
4. 对实际新增状态管理验证取消、重复恢复、并发引用、detach、迟到回调，不编造模拟合成器测试来声称解决驱动问题。设备阶段先用户手动安装并测试卓易通，正常 Android 环境的回归同样不把 ADB 作为前提。
5. 只有成功候选再进入真实首页及真实 Dialog 的合并验证；记录效果与性能后决定是否通用采用或保留可选兼容配置。不能只按“华为／鸿蒙”品牌字符串自动启用。

本轮只完成方案与记录，不创建这些代码、入口或测试包。

## 平台依据

- [AttachedSurfaceControl](https://developer.android.com/reference/android/view/AttachedSurfaceControl)：API 31 起公开根节点 reparent 与随 View 绘制原子提交的接口；后者不自动安排绘制，需保持 invalidate。
- [SurfaceView.getSurfaceControl](https://developer.android.com/reference/android/view/SurfaceView#getSurfaceControl())：当前用其作为自有层父节点符合公开用途；不能修改框架管理属性来强行调层。
- [SurfaceControl.Transaction](https://developer.android.com/reference/android/view/SurfaceControl.Transaction)：committed 与 presented 语义不同；completed 回调自 API 35 起提供。setLayer 相同 Z 值的兄弟层次序未定义。
- [PixelCopy](https://developer.android.com/reference/android/view/PixelCopy)：复制指定 Window／Surface 最近入队缓冲，不能据此替代最终屏幕合成结果。
- [WindowManager.LayoutParams.alpha](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#alpha)：整窗透明度的标准约定；当前只有症状与源码推断，尚无卓易通实现缺陷的直接证据。
