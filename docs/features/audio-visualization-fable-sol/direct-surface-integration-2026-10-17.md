# N 直接图层接入实际音频弹窗

## 2026-10-17：用户确认同缓冲前景方案有效

- 用户安装上一轮 debug `202610170712` 后反馈“终于 OK 了”，确认本轮卓易通音频弹窗兼容问题已解决。此前最关键的互斥现象为圆角正常时原生前景消失、前景恢复时黑角复发；同缓冲方案获得当前测试设备的正向实测反馈。
- 最终实现保留 C 系统暗层、N 自管 PQ 图层及无 SurfaceView 的媒体子窗口；将海浪区域内的真实原生控件离屏绘制后与海浪在同一 GPU 输出缓冲中合成。输入与无障碍仍由原 View 处理，控件保持 SDR 参考白，完整画面统一处理 PQ 和圆角。
- 定位为使用公开图形 API 的针对性兼容方案，增加前景硬件缓冲、GPU 绘制／合成和生命周期维护成本。正常呈现无需逐帧 CPU 截图；实际额外耗时、功耗及其它卓易通设备覆盖尚未量化。本次结果支持规避跨图层合成路径有效，不能据此证明兼容器某个内部函数为唯一根因。
- 本轮仅记录实测结果并说明历史方案与取舍，不修改应用代码、不移除日志、不追加发布或 Git 提交。后续系统升级／更多机型仍应回归；用户的整体确认不扩写成逐项完成所有边界测试。

## 2026-10-17：发布同缓冲前景候选 202610170712

- 用户确认 202610170554 仍丢失前景、圆角正常。保留这一失败结论，本轮未收到新日志；采用同缓冲前景合成以规避兼容器跨图层遮挡，未声称已经确认系统根因。
- 仅三个卓易通生产弹窗改用 FableSolForegroundLayout。真实 View 的硬件绘制结果通过双缓冲 HardwareBufferRenderer、生产 fence 和 EGLImage 交给现有 GL 输出；控件输入与无障碍保留，参考白及半透明边缘正确转换，最终统一圆角。首帧等待两种内容，换色彩空间保留前景与旧画面；粒子仍分开捕获海浪及原控件一次。
- 关闭、后台或降级恢复原生绘制；待 GPU 释放后归还缓冲，借用的显示列表逐层失效。普通设备的 SurfaceView、系统暗层及共享粒子恢复代码未改；新前景合成默认关闭。
- 构建及 456 项相关 JVM 回归通过；新增真实 shader GPU 检查 18 项，覆盖禁用时各输出逐位一致、白色／预乘／方向／圆角。远端元数据、中文说明、APK 字节、原签名及新 DEX 类／标记核验通过；11 份源码／资源／测试与构建目录哈希一致。无设备操作、无 Git 提交。
- debug [202610170712](http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202610170712.apk) 已发布，30,760,777 字节，SHA-256 `58228fbb36fbf21ea59c3791d1fc38685112a26c1fad3ea218b26c92c5afed66`。说明为 `debug-updates/update-20261017151215.md`；证据位于构建目录 `tmp/fablesol-foreground-composite-20261017/`。
- 保留日志导出，新增前景实际 alpha 采样、缓冲捕获／导入序号和原窗口采样含义。仍需用户检查三个弹窗的控件持续显示／点击拖动、白色、圆角、粒子、HDR 开关和退出闪烁；离屏验证不等于卓易通屏幕验收。

2026-10-17 最新复验：用户确认 `202610170554` 仍在 HDR 出现后丢失计时、按钮、进度条，圆角正常。移除 CLEAR 的候选未通过；本轮没有新的稳定前景日志，不能把窗口清除视为已证实原因。后续候选将真实原生控件离屏绘制后与海浪合成到同一输出缓冲，避免依赖兼容器的跨图层前景顺序；继续保留 C 暗层、圆角和日志导出，普通设备路径不启用。

## 2026-10-17 新日志：交接成功，稳定前景采样缺失

收到 `fablesol-composition-1792215743505.txt`，SHA-256 为 `ddd20932d53b3d2cc18d165b1288e4b866ec8f0e3cb80e1197ae688f605615a6`。最新导出的生产直接图层日志含 85 条事件，均属 `202610170528`；尾部已截断，导出标记 `pendingWritesFlushed=false`。4 次交接成功，媒体窗口位置与原弹窗一致，没有 EGL／挂载失败。

7 次 `window_foreground` 采样都在粒子持有期间：decorAlpha=0、控件有效 alpha=0，因此零像素不能证明稳定状态下控件已被清除。随后 4 次窗口可见性均恢复为 alpha=1、particleHolds=0，但 `particle_recovery presented=false` 导致现有代码跳过恢复后的采样。这是诊断盲点，不能把无效呈现回调直接当作 UI 消失根因；历史关闭粒子仍复现也要求继续检查最终合成。

本轮依次验证可见性恢复、窗口 CLEAR／前景重绘、最终图层遮挡；补充不依赖呈现 fence 成功的稳定窗口采样。保留用户实测与导出日志闭环，不连接设备，不将 JVM 状态回归当作卓易通合成验收。

关联 `production_dialog_dim_log` 后确认，4 次粒子恢复均将 windowAlpha／decorAlpha 恢复为 1，直接图层也恢复 alpha=1。可见性没有恢复的假设减弱；原文件缺少稳定后的像素，仍无法确认窗口清除与最终遮挡中的哪一种成立。

当前候选保持 528 的媒体子窗口和 C 暗层，仅替换原窗口中透明区域的形成方式：撤销 Direct View 的全矩形 CLEAR，在祖先背景 Drawable 绘制时用 `clipOutRect` 留出海浪区域，保存／恢复原背景并跟随深浅色替换。原生控件仍由原窗口绘制，不复制控件、不改变焦点／点击或另加前景窗口；首帧交接时才启用背景裁出，HDR 切换保持同一裁出，关闭／降级时还原。这样移除会作用于目标缓冲的清除操作，但最终合成是否正确仍需要用户复验。

依据为 Android 官方 [Canvas.clipOutRect](https://developer.android.com/reference/android/graphics/Canvas#clipOutRect(android.graphics.Rect)) 和 [DrawableWrapper](https://developer.android.com/reference/android/graphics/drawable/DrawableWrapper)，并核对本地 AOSP 36 的 DecorView 背景包装与 Drawable 回调。稳定采样改由生产轮询观察连续可见 600ms 后触发，与粒子呈现 fence 分离；每次新图层／重新可见仅采样一次。导出在 DebugFileLogger 队列排空后读取，每份生产日志保留最近 512KiB，原入口保持。针对真实的诊断缺口新增调度回归；当前 JVM 无法重现卓易通最终合成，不以调度测试证明前景已修复。

候选 debug `202610170554` 已于北京时间 13:54 发布阿里云：[APK](http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202610170554.apk)。构建和 456 项相关回归通过，55 个套件、0 失败／错误／跳过；其中 5 项新增稳定采样调度回归。远端元数据／中文说明／APK 字节和原签名核验通过；30,760,777 字节，SHA-256 `70eab70c7a2754fe3588643a4ddf611ae06927b04d99161365f10eb24632a903`。包内新背景裁出类、`stable_visible` 与诊断标记已核对；6 个变更／新增 Kotlin 文件在主目录和构建目录一致。构建目录仅额外回移主目录已有的日志队列等待方法，未改主目录公共帮助类或引入其它任务改动。

说明归档 `debug-updates/update-20261017135402.md` 并传入 Gradle，汇总历史原字节保留；证据 `tmp/fablesol-foreground-evidence-20261017/`。无设备操作、无 Git 提交。待复验真实三个弹窗的前景、圆角、粒子开关、HDR 切换、点外／返回和后台恢复；如仍异常，稳定显示约 2 秒后再进入诊断页导出，以取得 `stable_visible` 样本。此版发布不代表兼容器最终合成已验收。

## 2026-10-17 复验：媒体子窗口无黑角，但前景仍消失

用户确认 `202610170528` 没有黑角，但 UI 元素再次消失。媒体子窗口候选仅通过圆角项，未通过前景项；不能用 Android 标准窗口顺序推断该卓易通版本的最终合成已正确。

当前形成互补结果：`202610170436` 的 SurfaceView 父容器前景正常但 SDR／HDR 均黑角；`202610170528` 的媒体子窗口无黑角但前景消失。最新反馈没有附带日志，尚未读取任何当前版本的 `window_foreground` 采样，不能直接认定控件被 PQ 层遮挡，也不能排除窗口实际像素被清除或透明提示影响。

下一步先取当前版导出，按 `debugUpdateCode` 和实际窗口身份对应 `preparing`、`after_handoff` 的控件 `shown/alpha/nonTransparentPixels/opaquePixels`，同时检查 `mediaWindow` 绑定、就绪及位置。若窗口仍有控件像素，优先调查最终图层顺序／透明混合；若像素在交接后消失，优先检查 CLEAR 绘制和窗口重绘；若控件自身隐藏或 alpha 为 0，再查可见性恢复。需要这份已有诊断来决定下一次改动，不再把两条单项通过的路径来回替换。保留用户复验／文件日志闭环，无设备操作。

## 2026-10-17 复验：前景已恢复，透明 SDR 容器仍触发黑角

用户确认 `202610170436` 的计时、按钮、进度条已正常，但完整弹窗的圆角重新出现黑色区域；HDR 开启和关闭都存在，粒子动画图像没有黑角。这是当前有效复现条件。窗口下层排序候选通过前景项，但没有通过圆角项，不能将该版记为整体修复完成。

本轮优先区分：① SurfaceView 管理路径使兼容宿主增加矩形背景，预测即使不输出 PQ 仍会出现黑角；② 容器自身的透明缓冲或框架背景层处理错误，预测去掉该缓冲可以保留前景顺序并消除黑角；③ 窗口绘制或海浪圆角错误，预测窗口／海浪像素采样也有不透明角。历史 K 清角、M 去阴影均失败，而无 SurfaceView 的 N 通过圆角，因此不重复单独清角或去阴影。只使用公开 API，保留 C 暗层、已正常的前景与粒子交接；本轮继续由用户实测及日志闭环，不连接设备。

本地 AOSP 36 核对：SurfaceView 的同步重绘需要等待自身缓冲事务，单纯停止提交透明 Canvas 可能阻塞窗口首帧，不能作为安全的去缓冲方案；公开 View 裁剪也不能等价替代原生圆角事务。本轮选择移除生产兼容路径的 SurfaceView，改用 `TYPE_APPLICATION_MEDIA` 附属窗口承载自有 PQ 图层。该公开窗口类型明确位于所附着窗口后方；使用原弹窗的 windowToken，不获取悬浮窗权限，不接收输入，不另加暗层。原生控件仍在原弹窗，C 系统 dim=0.6 保持。媒体窗口只提供透明根层，不复制控件，不承担粒子截图；旧／新 PQ 缓冲仍在既有交接状态中切换。

依据：[WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_MEDIA)、本地 Android 36 `SurfaceView.handleSyncBufferCallback`／`ViewRootImpl.updateAndGetBoundsLayer`。此候选同时满足明确的窗口顺序与无 SurfaceView 两个条件，但尚未证明卓易通正确实现媒体子窗口，必须复验前景、圆角、C 暗层、触摸、关闭及粒子交接。日志需要记录媒体窗口绑定 token、实际位置、尺寸、附着代次及关闭释放，区分屏幕问题与挂载失败。

已实现媒体子窗口候选，沿用原窗口绘制事务同步交接 PQ 图层与透明区域。子窗口为整个 Dialog 的尺寸，以海浪实际屏幕位置与子窗口 Surface 内边距换算子层位置；不把设置预览的底边错误裁圆。原生产 shader、GL 渲染线程、C 暗层及粒子恢复代码未改。AOSP [WindowState.assignChildLayers](https://android.googlesource.com/platform/frameworks/base/+/master/services/core/java/com/android/server/wm/WindowState.java) 明确为媒体子窗口相对主窗口缓冲安排下层顺序；这比同一 Bounds 容器中的负层号更明确，但仍不代替卓易通验收。无设备操作，使用现有用户复验与文件日志闭环；当前 JVM 环境不能复现最终窗口合成，未添加只验证调用语句的测试。

最终构建及 451 项相关 JVM 回归通过，54 个测试套件，0 失败／错误／跳过。仅两个生产 Kotlin 文件变化；普通设备 SurfaceView、GL 线程、C 暗层及粒子恢复实现与初次 N 发布的源码哈希一致。补充了子窗口首帧超时、意外分离重建和后台／关闭释放；子窗口创建失败会明确日志并走既有降级。

debug `202610170528` 已于北京时间 13:28 发布阿里云：[APK](http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202610170528.apk)。30,760,777 字节，SHA-256 `a8f2a98908774811dc6eba1da2a6ec13b1af2442a2ba8671f2408f846f9311ab`；远端元数据、中文说明、APK 字节和原签名核验通过。直接解析包内 DEX 确认 `FableSolWindowUnderlay` 与 `MediaRoot` 的父类均为普通 View，媒体窗口挂载及释放标记齐全，主目录／构建目录两个源码文件哈希一致。说明 `debug-updates/update-20261017132845.md` 已传给 Gradle，历史说明原字节保留；证据 `tmp/fablesol-underlay-corners-20261017/`。无设备操作、无 Git 提交；屏幕圆角、前景、暗层与交接仍待复验。

## 2026-10-17 复验：透明区域提示修正无效

用户再次确认：`202610170418` 仍在 HDR 出现后丢失计时、按钮和进度条，圆角正常，关闭粒子也一样。因此保守扣除透明提示没有解决前景消失，不能将此前透明提示推测写成根因。本轮仍采用用户设备反馈与导出日志闭环，不连接设备。

继续检查实际窗口层级和窗口绘制：AOSP 36 的 `buildReparentTransaction` 将子层挂在中间 Bounds 容器下，`setLayer(-1)` 只相对该父层排序；`SurfaceView.updateRelativeZ` 则明确相对根窗口设置层级。这两种顺序保证不等价。固定 N 白圈通过与生产失败仍需解释，尚未获得本轮 `window_foreground` 采样，不能直接认定是控件像素被清除或最终合成遮挡。

核对 SDK 后确认 `setRelativeLayer` 是隐藏接口，不能直接作为应用方案。本轮候选使用与海浪同尺寸的透明 SDR SurfaceView 作父容器，通过公开 `getSurfaceControl`／`reparent` 把自有 PQ 层挂到窗口下层。容器与 Direct View 同位置、同尺寸，子层坐标为 0，不重复加窗口位移；SurfaceView 不输出 PQ、不创建 PQ EGL，不修改其框架层属性。容器 View alpha 为 0，由 Direct 在 PQ 就绪后才清窗口，避免透明 SDR 首帧提前形成空白；容器按代次重新挂载，不参与粒子捕获／恢复。共享 shader、C 暗层、窗口圆角及 PQ／SDR 缓冲交接保持。

公开接口依据：[SurfaceControl.Transaction.setLayer／reparent](https://developer.android.com/reference/android/view/SurfaceControl.Transaction)、[SurfaceView 及其 SurfaceControl 生命周期](https://developer.android.com/reference/android/view/SurfaceView)；实现核对使用本地 Android 36 `ViewRootImpl.updateAndGetBoundsLayer`／`buildReparentTransaction` 与 `SurfaceView.updateRelativeZ`。透明 SDR 容器方案仍需卓易通实测，特别关注前景、圆角、粒子恢复、快速 HDR 反向及切后台返回；不因与原 PQ SurfaceView 使用不同缓冲就预先认定黑角不会复发。

候选构建及 451 项相关 JVM 回归通过，0 失败／错误／跳过；覆盖 HDR 策略、缓冲交接、暗层、粒子与 shader 一致性。当前测试环境不能复现卓易通合成，不新增只检查调用语句的伪回归。窗口采样继续导出，新增容器代次、排序策略、首轮透明绘制和逐条 debug 版本号；父容器重建会使粒子生产者代次变化，防止旧恢复回调误认同一图层。

已于北京时间 12:36 发布 debug `202610170436`：[APK](http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202610170436.apk)。30,760,777 字节，SHA-256 `19a945111de09214048341f0bf8a9ab6dbea6509e649ebb5f226a574c34108ab`。远端元数据、说明及 APK 字节与本地一致，旧签名验证通过；包内新容器、粒子排除标记和挂载策略均已核对，5 个变更 Kotlin 文件主目录／构建目录哈希一致。普通 SurfaceView、渲染线程、C 暗层和粒子恢复实现与 `202610170404` 源码哈希相同。发布说明 `debug-updates/update-20261017123700.md`，证据 `tmp/fablesol-window-order-20261017/`。未连接设备、未提交 Git；实际前景、圆角及闪烁仍待用户复验。

## 2026-10-17 实际弹窗反馈：圆角正常，原生前景在交接后消失

用户验证 `202610170404`：圆角已正常，但计时、图标按钮、进度条等原生控件不显示，只剩海浪。关闭粒子动画仍可复现；无粒子时更清楚地看到先显示完整弹窗，再在海浪进入 HDR 后丢失前景。因此不能归因于粒子动画结束时未恢复，也不能沿用固定白圈通过的结论认定实际窗口已通过。

本轮反馈闭环仍采用用户设备实测与可导出日志；没有设备操作授权，不执行 ADB／HDC。候选按优先级为：① 根窗口透明区域提示错误或被兼容合成器误处理；② 直接图层层级未可靠处于窗口像素之下；③ 粒子／可见性恢复状态异常。第三项已被关闭粒子仍复现的结果显著削弱。需要区分窗口缓冲里是否还存在控件，以及它们最终是否显示。

AOSP `View.gatherTransparentRegion` 会从透明区域中扣除普通绘制 View 的整个矩形，`ViewGroup` 按绘制层级遍历；旧 N 白圈是铺满预览的普通 View，实际只在小范围画圆，却会扣除整块预览提示。真实弹窗的按钮／文本只扣除局部矩形，不能当作等价对照。先保持 N 图层／C 暗层／圆角不动，改为保守保留海浪区域内的窗口像素，由实际缓冲 alpha 进行混合。兼容器内部是否误处理复杂提示尚未证实，最终结果需实测。

依据：[AOSP ViewGroup](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/view/ViewGroup.java)、本地 Android 36 `View.java`／`ViewRootImpl.java`，以及 [AttachedSurfaceControl 官方文档](https://developer.android.com/reference/android/view/AttachedSurfaceControl)。

候选已实现：直接容器改为调用普通 View 的透明区域扣除，不改变实际 CLEAR 绘制或图层层级。debug 增加 `window_foreground`，在准备、交接后、粒子恢复后异步采样窗口缓冲的控件 alpha 和显示状态，最大边 512；仅数值入日志，不保存图像或读取文本。旧对照模式 5／6 显式创建 SurfaceView 宿主，避免生产 N 接入后遗留的强转异常。

本轮构建与 451 项相关 JVM 回归通过；现有测试没有真实窗口合成的测试环境，未另加只断言实现语句的测试。仍须用户实测前景恢复、圆角、粒子和 HDR 切换；没有将透明区域候选写成已证实的兼容器根因。

候选 debug `202610170418` 已于北京时间 12:18 发布：[APK](http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202610170418.apk)。30,760,777 字节，SHA-256 `7dd14716fd07efc99083b96fb1a2bc8721d86cc2375dcd48bb43317de64c47ab`；远端元数据、说明、APK 字节、原签名和新增 DEX 标记核验通过。发布说明 `debug-updates/update-20261017121729.md`，证据 `tmp/fablesol-foreground-20261017/`，改前基线使用 `before-scope/`。先在关闭粒子时验证原生前景，再覆盖粒子、HDR 切换和关闭；如仍失败，按窗口像素证据再判断层级。

## 范围与证据

用户授权 N 接入录音、音频附件和海浪设置，保留 C 系统暗层，仅卓易通启用，保留文件导出并发布 debug。现有方案偶尔仍闪烁，需同时检查交接。固定图 N 已通过圆角和原生白圈实测；真实动态海浪与切换仍由用户复验。

## 本轮可证伪假设

1. SurfaceView 管理路径导致黑角：相同 PQ 内容、系统暗层及原轮廓下，直接 SurfaceControl 的动态海浪应消除黑角。
2. EGL 换色彩空间让当前缓冲失效，而 SDR 遮盖／提交回调不能完全保证最终合成连续：保留旧独立图层到新图层首帧就绪，再同步替换，应减少空帧及 SDR 快照引起的亮度跳变。
3. 粒子只识别 SurfaceView，恢复时窗口与生产者分别就绪：显式让新生产者参与 sRGB 快照、冻结、恢复及隐藏，应保留完整海浪和前景并减少二次出现。

## 实施约束

共用现有物理、音频分析、GL 渲染器和 PQ 编码；非卓易通仍创建原 SurfaceView。只有三个布局使用生产宿主。新图层显式 RGBA8／非 opaque，不使用隐藏圆角 API；C 系统 dim=0.6 不变，撤掉生产路径已失败的 K 清角覆盖。

颜色空间切换保持单一模拟状态和原图层最后缓冲，候选图层完成渲染后再事务交接；请求合并，过期回调不得恢复旧图层。关闭档退出 PQ，粒子持有期间不切换颜色空间。日志记录图层身份、输出、首帧、事务、隐藏及释放，纳入既有导出。

## 验证边界

用户日志与分组实测构成本轮合成反馈闭环；本机没有获准连接可复现卓易通的设备。JVM 只验证实际使用的交接状态和回调顺序，不将编译或状态测试冒充屏幕验收。保留用户要求的诊断日志，不执行 diagnose 的通用删日志建议。

## 当前实现与本地核对

- 宿主仅三个生产 XML 使用，新接口不改变非卓易通的 SurfaceView 方法体。N 没有创建 SurfaceView，也不使用非 SDK 圆角或全窗口 HDR 模式。
- 图层首先隐藏附着；初次准备允许在粒子冻结期间完成，避免出现动画等首帧时相互等待。HDR 请求先写入生产渲染器，不能遗漏持久化的非默认强度。
- 首轮构建及 55 项相关状态／策略测试通过，后续审查补充快速反向恢复、密集请求及 detach 测试，再做最终回归。没有实机画面证据，不能提前断言偶发闪烁已完全消失。

## 最终本地验证

- 最终 assembleDebug 与 451 项相关 JVM 回归通过，0 失败／错误／跳过；包含 7 项真实使用的交接状态测试，以及粒子、暗层、HDR 策略与 shader 一致性回归。
- 原 SurfaceView 源码去除接口声明／可见性签名变化后与本轮开始完全一致；普通设备分支不调用新图层迁移接口。未修改共享 shader、音频分析或模拟参数。
- 审查补充：隐藏窗口的直接图层不执行可能返回无效呈现 fence 的 prime，待窗口恢复后再核验；后台隐藏释放与窗口重新附着分别处理，避免短暂无原生根 Surface 时降级；最后缓冲持有到目标事务提交，GPU 资源释放后才释放 Surface。
- 导出新增 `production_direct_log`，保留旧对照入口。实机需检查录音准备／录制／停止、附件播放／暂停、设置冻结／变色、HDR 0↔非 0、两种粒子、点外／返回、快速连续弹窗及切后台返回。没有授权设备操作，本轮未执行 ADB／HDC。

## 发布与交付

- debug `202610170404` 已于北京时间 2026-10-17 12:04 发布阿里云；[APK 下载](http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202610170404.apk)。
- 远端 APK 与本地构建／暂存包逐字节一致，30,760,777 字节；SHA-256 `9cb5caed1bc170d31547d4cc3f459f76d5f50dd24895ab9d0c3e7cfdfb0613fa`。签名验证通过，证书 SHA-256 保持 `06b552f17449b489e168f368716adbc3c8bbc371b1355a1413f53afeeda836ed`。
- 远端元数据及中文说明与本地一致，包内直接图层／交接状态／粒子生产者类和新导出标记齐全；构建工作区与主目录的 17 个变更 Kotlin 文件哈希保持一致。
- 证据保存在构建工作区 `tmp/fablesol-direct-integration-20261017/`，包含最终测试、发布输出、远端回读、签名及 `release-state.json`。发布说明归档 `debug-updates/update-20261017120121.md`，汇总历史保留；无设备操作、无 Git 提交。
- 屏幕结果仍待用户回报；如出现异常，在复现后长按海浪设置的“HDR 高光增强”进入对照页，导出文件，其中 `production_direct_log` 可关联实际弹窗的首帧、切换、粒子恢复及关闭释放。
