# 卓易通弹窗黑角：合成层调查与直接图层复验

## 202610170332 实测：N 的固定 PQ 圆角与前景白圈通过

用户提供 `fablesol-composition-1792208311993.txt` 并反馈“N 应该有效”，随后确认 N 左侧原生白圈“完整显示，颜色正常”。原始文件 905,710 字节，SHA-256 `c421eb6ca9912a5af760d2286e2f852ccf17fe4abcd50e6b273b07b6af4f9802`。完整解析 607 条事件，0 解析错误、0 非 JSON 尾部；含七个历史会话，本轮只使用 11:37:32.349 开始的 `202610170332`／修订号 7 会话，共 93 条事件（包括两项生产日志附件）。解析证据保存于既有构建工作区 `tmp/fablesol-three-controls-feedback-20261017/`。

四项均为实际 BaseDialogFragment 结构、系统 dim=0.6、应用暗层覆盖 0、窗口 1140×2053、窗口轮廓半径 48；每项窗口身份不同，原裁剪和背景保持。观感都在条件与画面就绪后记录。

| 条件 | 实际输出／独立图层 | Decor elevation | 窗口四角 alpha | 日志观感 |
| --- | --- | --- | --- | --- |
| C | PQ／SurfaceView 数量 1 | 48 | 11、12、27、30 | black_corners |
| L | Canvas SDR／SurfaceView 数量 0 | 48 | 11、12、27、30 | corners_normal |
| M | PQ／SurfaceView 数量 1；去阴影请求已执行，各祖先 elevation／translationZ 为 0 | 0 | 0、0、0、0 | black_corners |
| N | PQ／直接 SurfaceControl；SurfaceView 数量 0 | 48 | 11、12、27、30 | corners_normal |

N 的 `direct_egl` 明确验证 `rgba8-bt2020-pq; alpha=8; white=203nit`，没有退回 SDR。C／M／N 的 GL 读回完全一致：PQ 白色 `[148,148,148,255]`、4 倍高光 `[186,186,186,255]`、内容圆角 `[0,0,0,0]`，GL error=0。因此本次圆角改善不是由降低高光或更换颜色编码造成。

N 窗口身份 `102045382`、Surface 身份 `28753270`，图层 1140×600，Surface 坐标 (96,194)，对应窗口内预览 (0,98) 加 Surface 内边距。11:38:22.501 的可见事务 2 已提交且 stillCurrent=true，随后 `direct_ready` 的 frameReady／transactionCommitted／windowHoleDrawn／visible 均为 true；反馈及导出时仍就绪。事务 1 是先前的隐藏状态，回调标记 stillCurrent=false，未被误当作可见首帧。日志中没有 N 失败或超时事件。

窗口 PixelCopy 在 N 的左白圈处为 `#FFFFFFFF`、右侧独立 Surface 白圈处为透明；独立 Surface 的拷贝及 GL 数据与 C／M 相同。它们分别采样窗口与子图层，不能单独证明最终屏幕的前后层级。用户后续确认左侧原生白圈完整且颜色正常，补足了该固定场景的前景叠加观感。

据此将**直接 SurfaceControl＋既有 PQ＋C 系统暗层**作为后续卓易通接入的优先方向。L／N 保留阴影仍正常，M 去掉阴影仍黑，不再把窗口阴影或 Canvas 圆角清除作为主要修复方向。证据支持问题与 SurfaceView 在当前兼容环境中的图层管理／合成路径有关，但尚不能指认其某个内部黑色背景层就是唯一根因。

验收边界：本次是同一台设备、一个固定图会话，尚未使用 N 运行真实动态海浪。导出发生在 N 仍显示时，没有 N 关闭／重建／释放序列；这不代表已发现泄漏，也不能认定释放已实测通过。下一轮实际接入须验证原生按钮叠加、连续更新、粒子快照与交接、HDR 0↔非 0、点外／返回关闭、快速重开及尺寸变化。现有三个正式音频弹窗仍未接入 N，本轮只记录证据，不修改应用代码或发布新包。

## 新实机证据

用户确认 debug `202610170304` 的 C、J、K 均仍有黑角；此前粒子动画已正常。此次优先调查，不继续叠加未经验证的透明遮罩。

原始日志：`fablesol-composition-1792206495230.txt`，651,952 字节，SHA-256 `01a54c66e670f8b235ba0fcd4d6285d6e1877bbd3a5f02bf9610317ef4a3e146`。共解析 513 条事件，0 解析错误，包含六个历史会话；本轮只用最后两个 `202610170304`／诊断修订号 6 的会话评价新候选。解析结果保存在已有构建工作区 `tmp/fablesol-corner-research-20261017/`。

最新会话为 11:07:57；固定模式 2（PQ）。C／J／K 共用同一 Window `158397228`、Surface `49309179`，EGL 会话数为 1。窗口 1140×2053，圆角半径 48；固定图从窗口内 y=98 开始，高 600，并未覆盖整个窗口。系统暗层均为 0.6，应用暗层覆盖为 0。

| 条件 | 窗口裁剪 | 显式清角 | 四个角采样 alpha | 用户反馈 |
| --- | --- | --- | --- | --- |
| C | 开 | 关 | 11、12、27、30 | 仍有黑角 |
| J | 关 | 关 | 11、12、27、30 | 仍有黑角 |
| K | 关 | 开 | 0、0、0、0 | 仍有黑角 |

采样均在 ready、stillCurrent=true 的状态下完成。前一个当前版本会话也重复得到同样 alpha 结果。生产日志中实际设置弹窗 `88091506`（960×1944）同样记录 `window_corner_buffer_alpha=0,0,0,0`，window／decor alpha 为 1。上述只证明四个采样点的窗口缓冲透明，不等于验证整个透明区域或最终屏幕。

生产日志还保留了 10:51 的旧原生圆角尝试，明确记录 `native_corner_api_unavailable`。这证明当时应用无法取得该非 SDK 接口，不能区分接口不存在与隐藏 API 过滤。当前 K 的 `decor_clip_disabled` 属于策略主动跳过，不是第二次 API 失败。

## 目前可以排除与仍需验证的部分

- K 的清角已经执行，且采样点变透明，但屏上黑角仍在。因此不能继续只用“Canvas 未清除／圆角路径未生效”解释。
- 海浪或固定图 Surface 不是整个窗口的大小，不能仅凭黑色形状就归因于海浪 shader。
- 仍需区分应用 SurfaceView 的下层背景、窗口／兼容宿主的矩形底层、窗口阴影。公开 Android 实现不能直接证明卓易通内部实现。
- 不改变已获用户确认的粒子交接；不将“全透明采样”记为视觉验收通过。

## 调查范围

读取应用与本地 Android 36 源码，检索 AOSP／Android 官方说明和公开工程材料。此次未操作设备、修改应用代码、发布 APK 或提交 Git。

## 公开资料与适用边界

### 1. 窗口缓冲透明，不等于最终合成透明

[AOSP SurfaceView 说明](https://source.android.com/docs/core/graphics/arch-sv-glsv) 将 SurfaceView 描述为独立合成层，默认位于应用 UI 层下方。窗口 PixelCopy 的四点 alpha 不能覆盖这些下层及兼容宿主的最终输出。

[AOSP SurfaceView 源码](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/view/SurfaceView.java) 与本地 Android 36 源码都包含默认黑色的 `mBackgroundControl`。标准实现仅在下置且标为不透明、未禁用背景时显示该层；本项目使用 RGBA_8888，按标准路径应隐藏它。因此这只是值得排查的图层，尚不能据此判定它在卓易通上出错。该背景层的父节点仍为 SurfaceView 容器；不能声称标准 AOSP 会自动让它覆盖整个 Dialog。

`setResizeBackgroundColor()` 的 alpha 按源码约定会被忽略，而且属于非 SDK 接口。传入 `Color.TRANSPARENT` 并不能可靠关闭这层背景。改变为明显的测试色仅适合作为接口可用时的定位手段，不能将反射失败、颜色未变等同于排除背景层。

### 2. 公开文档确实记录过相似的黑角机制，但不是卓易通根因证明

荣耀的 [CN119620886A《显示卡片的方法、电子设备及可读存储介质》](https://patents.google.com/patent/CN119620886A/zh) 在 S823／[0162]～[0163] 描述：嵌入式卡片通过宿主 SurfaceView 承载时，下方背景可能从透明圆角处显露；处理点位于宿主背景层。这里的宿主／厂商接口不是普通应用可直接复用的标准方案。原始专利 HTML 已通过 HTTPS 读取，保存在本轮临时证据目录；不采用检索站的 AI 摘要作为技术证据。

荣耀的 [CN118444805B《显示悬浮窗的方法及相关装置》](https://patentimages.storage.googleapis.com/03/cc/26/283a719d9265bf/CN118444805B.pdf) 在 [0066]～[0076] 也讨论了 View 圆角不能覆盖独立 Surface 图层的问题，并提出将图层移离圆角区域的几何方案。它针对系统悬浮窗，不能直接证明调整本 App padding 就能解决。目前固定图已从 y=98 开始而窗口半径为 48，顶角仍黑，单纯内缩海浪并不充分。

上述材料均来自荣耀，与华为／卓易通不是同一实现。尚未找到可核实的卓易通官方图层结构说明或针对这一问题的公开应用接入修复。

### 3. 可用公开 API 构造新的对照路径

Android 的 [SurfaceControl](https://developer.android.com/reference/android/view/SurfaceControl) 允许应用创建图层并从它构造 Surface；[AttachedSurfaceControl](https://developer.android.com/reference/android/view/AttachedSurfaceControl) 可将应用创建的图层附着到窗口，并让事务随 View 绘制提交。[Transaction.setOpaque](https://developer.android.com/reference/android/view/SurfaceControl.Transaction#setOpaque(android.view.SurfaceControl,boolean)) 明确控制是否忽略缓冲中的 alpha，API 33 起公开。本项目卓易通分支已要求 API 34+。

因此可以在诊断页新增直接创建 SurfaceControl 的路径，以 RGBA_8888／`setOpaque(false)` 构建独立图层，接现有 `FableSolEglSession(Surface)` 和 PQ 输出，避开 SurfaceView 自动创建的背景层与挖孔管理。这是结构不同且可验证的候选，并非已验证的修复。窗口中部的透明区域、前景按钮、事务同步、快照与资源生命周期需要显式管理；先做固定测试图，不直接改三个正式弹窗。

这与已失败的非 SDK `setCornerRadius` 探测不同；新对照不依赖该接口，也不声称公开 API 可以控制卓易通外层宿主。

### 4. 不直接换成 TextureView

[Android HDR 播放文档](https://developer.android.com/media/grow/hdr-playback) 指出 SurfaceView 和 TextureView 的 HDR 能力不同，后者在该视频播放路径可能转为 SDR。该说明不能直接证明所有自定义 GL TextureView 都无法输出 HDR，但也不足以支持将本项目 PQ Surface 等价替换为 TextureView。维持 ADR 0016 的既有设备路径，不为黑角整体更换渲染容器。

## 下一轮最小验证建议

保留同样的窗口尺寸、圆角背景、C 系统暗层 0.6、前景控件和亮度条件；每项新建窗口并记录身份与销毁，避免在需要销毁 Surface 的对照中误称“同一 EGL 会话”。

| 对照 | 唯一主要变化 | 有区分力的结果 |
| --- | --- | --- |
| 无独立 Surface | 真正移除 SurfaceView，以普通 Canvas 画静态内容；不能只是关闭 HDR 或停止海浪 | 若仍黑，应用 SurfaceView 不是必要条件；若正常，再查独立图层加入过程 |
| 去窗口阴影 | 在原 PQ 条件上将窗口／Decor 及面板相关 elevation 归零，记录实际值 | 若黑角随之消失或变化，先修阴影路径；保持暗层不变 |
| 直接 SurfaceControl | 无 SurfaceView，固定图仍用同样 RGBA8 PQ 与 shader；显式非 opaque | 若普通 Canvas 和此项正常、旧 SurfaceView 黑，支持后者管理层相关假设；若此项也黑，继续查窗口／兼容宿主 |

直接 SurfaceControl 成功后，才评估仅卓易通下接入真实海浪并回归粒子、关闭和 HDR 切换；现有非卓易通设备不变。若不含独立 Surface 的普通弹窗仍黑，优先测试透明窗口外壳与内嵌圆角内容，或宿主提供的适配方式；扩大窗口可能扩大黑底，不能直接当成修复发布。

应用侧临时背景截图补角会在动态背景、暗层过渡和窗口移动时产生接缝，不作为首选。增加更多同类 CLEAR／clipPath 遮罩、反复改位深也不是当前高价值对照。

## 验证限制

本轮通过日志解析、源码与官方 API 核对建立下一轮方向，没有可本地自动重现卓易通最终合成的测试环境。没有添加只验证属性设置成功的单测来替代视觉验收；黑角问题仍未解决。

## 用户授权后的诊断实现（2026-10-17，修订号 7）

- 测试页新增 L／M／N，默认回到 C 系统暗层／固定 PQ 基准。进入或离开 L／M／N 均新建同样尺寸的窗口，并记录原窗口、原图层释放及新实例；三项固定画面，避免把海浪状态变化混入结构对照。A～K 与海浪模式仍保留。
- L 使用普通 Canvas 生成 SDR 静态图，明确标注没有 HDR／EGL；当前窗口的 SurfaceView 数量必须为 0 才接受观感。N 也检查数量为 0，保证独立图层来自新路径。
- M 调用公开 `Window.setElevation(0)`，并清零 Decor／面板祖先 View 的 elevation 与 translationZ。该调用会改变窗口 Surface／阴影内边距，所以仅 M 每窗口设置一次；离开时用新窗口恢复。`Window.getElevation` 是非 SDK 接口，日志分别记录设置请求与公开 View 的实际数值，不伪报读取了窗口内部状态。
- N 使用公开 `SurfaceControl.Builder`、`Surface(SurfaceControl)`、`AttachedSurfaceControl` 及事务，显式 RGBA8、非 opaque、alpha=1、目标层级 -1；位置取 `getLocationInSurface`，包含窗口 Surface 内边距，避免与 `getLocationInWindow` 混用产生偏移。图层附着随窗口绘制提交，首帧 PQ、事务提交和窗口透明区域绘制均满足后才接受反馈。
- N 的创建、实际 EGL 输出、GL 像素、事务、透明区域、位置、失败和资源释放均写入原日志；若 PQ 回退或 6 秒未就绪则明确报失败，不将其当作有效的 PQ 对照。退出时先隐藏并解除图层父节点，再在原渲染线程释放资源；迟到回调按实例与代数丢弃。
- C／M／N 共用提取后的固定 GL 绘制类；逐段归一化核对绘制主体与上一版相同。正式海浪 shader、EGL 和三个音频弹窗代码均不变。
- N 的目标层级只是请求：公开附着接口的中间父容器及卓易通合成可能使其覆盖左侧原生白圈。界面提示用户观察并备注；不能从 `setLayer(-1)` 成功推定与 SurfaceView 的前景叠加完全等价，也不据此直接接入生产。
- 导出继续支持保存到下载／分享文本文件，附带现有生产日志；不采集音频、记事内容或截图文件。正式屏幕黑角、颜色与前景层级仍由本轮用户复验。
