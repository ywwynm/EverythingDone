# FableSol 录音水面迁移 OpenGL ES 统一渲染器

## 2026-10-17 最终采用：仅卓易通的同缓冲前景合成

用户已确认 C 系统暗层＋N 自管 PQ 输出＋HWUI 原生前景同缓冲合成解决当前设备的颜色、圆角和前景缺失，并选择保留完成性能调整的最新版。保留原 View 的输入与无障碍，普通设备继续原渲染路径；缓冲使用权以 GPU fence 约束，最新前景合并、补捕获与导入缓存只用于兼容分支。90 Hz 请求不代表系统持续采用，日志仍观察到无触摸后降为 60 Hz；不修改共用海浪计算。此次关闭用户可见调试入口及 HUD，诊断代码保留。下列候选过程作为历史依据，最终状态以本段和功能性能记录为准。

## 2026-10-17 补充：卓易通原生前景合入海浪输出

直接 PQ 图层与原生窗口控件在目标设备上未能稳定同时显示。背景裁出候选仍失败后，三个音频弹窗仅在卓易通分支把原生控件通过 HardwareBufferRenderer 绘制到透明 sRGB 硬件缓冲，再与海浪在最终呈现 pass 合成。保留原 View 输入和无障碍；普通设备不创建离屏前景。前景以 SDR 参考白合成，PQ／SDR 切换保留旧帧与前景，粒子快照继续分别获取海浪和原控件。窗口系统暗层、直接图层承载及圆角不变。

这一候选绕开对兼容器跨图层排序的依赖；离屏 shader 已验证，但仍需用户确认 HWUI、EGLImage 和最终窗口显示，不能视为已经确定并修复系统内部根因。

录音对话框的 FableSol 水面此前用硬件 Canvas（drawVertices 三角网格 + CPU 逐顶点配色
+ 三项 API 33+ 的 AGSL 增强）渲染，UI 线程内完成全部物理、采样与配色，真机持续性
掉帧、倾斜时加重。2026-07-12 决定将渲染层迁移 OpenGL ES；2026-07-13 又根据 HDR
调研把容器从 TextureView 修订为统一的 SurfaceView + 自管 EGL 独立渲染线程。它会先
一比一复刻现有视觉，验收后在 GLSL 中做逐像素质感与 HDR 升级，并删除全部 Canvas 水体
渲染路径。原因是三条约束同时成立时 GLES 是唯一解：新渲染路径必须在
minSdk 26 起严格像素级一致（排除 AGSL / API 34 Mesh）、含倾斜的稳定 60fps、以及
逐像素级的水体质感目标（微法线、逐像素光照、根治 banding）；而 Android 官方能力表
又将 SurfaceView 标为完整 HDR 支持，将 TextureView 标为 Android T+ 的有限 HDR 支持。

## Considered Options

- **纯 Canvas + worker 线程解耦**：能达成 60fps，但视觉上限停在 8bit 逐顶点插值，
  逐像素手法永久不可用；且 drawVertices 的硬件加速自 API 29 才开始，26–28 本就有洞。
- **AGSL 全通道逐像素（调研原推荐）**：性价比最高，但 API 33+ 专属，违反严格像素
  级一致的裁决；作为"核心一致 + 增强分级"的方案被用户明确否决。
- **android.graphics.Mesh（API 34+）**：架构最契合（顶点+片元着色器进 HWUI），但
  覆盖率约五成、生态过新、顶点缓冲不可就地更新，列为观察项。
- **TextureView + 自管 EGL**：普通 View alpha、旋转与复杂裁切语义完整，但 HDR 支持有限，
  还有一次额外合成；在 HDR 成为明确目标后不再作为主容器，也不保留为旧系统分支。
- **GLSurfaceView**：和 SurfaceView 共享独立 surface 的合成约束，同时限制当前自管 EGL、
  HDR colorspace 与渲染生命周期设计的控制面，因此不采用便利封装。
- **SurfaceView + 自管 EGL**：完整 HDR 支持、直接合成且能保持现有独立渲染线程；项目
  minSdk 26 已高于 SurfaceView 平移/缩放与 View 同步的 API 24 边界，因此成为统一容器。

## Consequences

### 2026-10-17：卓易通音频弹窗采用直接 SurfaceControl

`202610170528` 实测无黑角但前景仍丢失。新日志只捕获到粒子隐藏期的零像素，窗口后续已正常恢复透明度，不能证明最终遮挡原因。本轮保持媒体子窗口，移除 Direct View 对原窗口的 CLEAR，改在祖先背景 Drawable 绘制时留出海浪区域，原生前景仍由原窗口绘制。稳定像素采样不再依赖兼容环境的呈现 fence；该候选仍须联合验收前景、圆角与粒子交接，不能把公开 API 的标准行为当作卓易通实测结果。

生产 N 出现前景消失后，透明区域提示候选未通过。透明 SDR SurfaceView 容器版 `202610170436` 已恢复前景，但完整弹窗在 SDR／HDR 下都重新出现黑角，粒子图像正常。最新候选移除生产卓易通路径的 SurfaceView，改用公开 `TYPE_APPLICATION_MEDIA` 子窗口，以原 Dialog 的 windowToken 附着，系统保证其位于父窗口后方；自有 PQ 图层使用子窗口的公开根接口。原 Dialog 继续绘制控件并处理输入、C 系统暗层；子窗口没有第二套控件、不接收输入、不另加暗层。原圆角和粒子生产者合同保持，实际圆角、前景及闪烁待用户实测。普通设备不受此例外影响，不调用隐藏 `setRelativeLayer` 或读取隐藏窗口根层。

固定图对照确认 SurfaceView 的 C／去阴影 M 仍黑角，普通 Canvas L 与直接 PQ 图层 N 圆角正常；N 左侧原生白圈也获用户确认完整且颜色正常。用户授权三个实际音频弹窗采用直接 SurfaceControl，保留 C 系统暗层与原轮廓。仅既有卓易通判据启用此例外，普通设备继续 SurfaceView，自管 EGL、音频和物理渲染器保持共用。

直接图层的颜色空间切换保留旧缓冲，候选隐藏渲染就绪后同步交接；粒子通过生产者合同取得 sRGB 快照并等待恢复。它替代此前兼容路径的 SDR 遮盖及销毁当前 Surface 的切换方法，关闭档仍退出 PQ。实际动态海浪与闪烁需用户实机验收，详见 `docs/features/audio-visualization-fable-sol/direct-surface-integration-2026-10-17.md`。

### 2026-10-16：卓易通 PQ 兼容例外

普通 Android 继续遵循下述 scRGB 与实时比例策略。仅 API 34+、命中既有卓易通环境判据时，
允许缺失能力声明仍直接尝试 BT.2020 PQ，并检查实际色彩空间、RGBA 位深和浮点 FBO。
首版 FP16 PQ 在用户设备上出现局部偏色，之后的 RGB10_A2 PQ 在 HDR 开启时穿透。当前
候选使用完整 8 位 alpha 的 RGBA8 PQ，圆角量化后预乘，透明区域不得残留 RGB；降低颜色
编码精度的影响由 PQ 域共通道抖动抑制，实际观感待用户验证。内部场景仍为 FP16，最终呈现时
才转成 PQ；失败回退 SDR。缺失的亮度比例
与已测得的比例分开，兼容内容预算不等于面板实测峰值。

卓易通在录音／播放状态变化时保留 PQ Surface；用户设置的关闭档必须按 D220 真正退回 SDR。
PQ／SDR 即使像素格式相同也要重建颜色会话；先提交 sRGB 遮盖，等目标会话首帧后撤盖。
粒子快照也由渲染器显式输出 sRGB，避免
依赖兼容环境的 PQ PixelCopy 转换。普通设备保留原路径。实现与真机验证边界见
`docs/features/audio-visualization-fable-sol/pq-dialog-colors-2026-10-16.md`。

- 现存 AGSL 三件套（抖动/软带/深度吸收）与九层 Canvas 回退在 GLES 验收一个发布
  周期后删除；EGL 初始化失败降级为静态记事色填充，不保留第二套水体实现。
- API 26～33 与非 HDR 设备仍在同一个 SurfaceView 上输出 SDR；API 34+ 只有在显示器、
  实时 headroom、FP16/scRGB EGL 链路均通过能力探测时才输出 HDR。API 35+ 优先使用
  SurfaceView 自身的 desired HDR headroom，不把整个窗口无条件改成高亮内容。
- HDR 使用 float component EGL config + linear scRGB window surface，场景先在
  `GL_RGBA16F` 线性 framebuffer 合成。`Window.setColorMode()` 不影响 SurfaceView，因此
  Dialog Window 保持原颜色模式。任一 EGL/GL 扩展、FP16 framebuffer 或实时
  `hdrSdrRatio` 条件不成立时，当前 surface 自动取消超白增量并回退 SDR。
- 超白亮度是录音态的局部光学属性，不是窗口曝光。峰值仅分配给近中层的
  镜面核心、窄受光浪峰和少量透射；远层、环境、光晕和顺流流光保持 SDR。
  PREPARED/STOPPED 只平滑收回 HDR 增益，不重建 surface；不对 SDR 添加 tone mapping。
- SurfaceView 保持在窗口下方，使文字和控件正常叠加，禁止 `setZOrderOnTop(true)`。
  API 34 以前的中间 View alpha 不可靠，录音准备/停止态淡入淡出必须由渲染器内的
  presentation alpha 完成。复杂 outline 裁切也不作为前提；圆角由带 alpha 的 GL 输出明确
  形成，并在 API 26、34、35+ 真机上验证无漏边、遮挡、动画错位或 surface 重建闪烁。
- Python 模拟器新增 moderngl 渲染后端，与 Android 共享同一份 GLSL 文件——渲染层
  首次实现双端单一事实源；物理/分析/映射仍为 numpy/Kotlin 双端同构 + 差分测试。
- GLES 在新设备上经 ANGLE 转译为 Vulkan 是已接受的长期折旧风险；本视图规模
  （280×420dp）下转译损耗可忽略。
- 本决定推翻 plan-2026-07-11"明确不做 SurfaceView/GL 迁移"与 D20 的 AGSL 阶段 C
  路线；当时的判断基于连续 2.5D 水面迁移之前的低得多的 CPU 负载，且未含严格像素
  级一致这条新约束。

详细论证与量化依据见
`docs/features/audio-visualization-fable-sol/research-2026-07-12-render-architecture.md`；
决策链见同目录 decisions.md D38–D45、D56 与 D64。
