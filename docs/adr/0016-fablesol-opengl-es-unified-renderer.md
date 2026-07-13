# FableSol 录音水面迁移 OpenGL ES 统一渲染器

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
