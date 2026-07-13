# FableSol 录音水面迁移 OpenGL ES 统一渲染器

录音对话框的 FableSol 水面此前用硬件 Canvas（drawVertices 三角网格 + CPU 逐顶点配色
+ 三项 API 33+ 的 AGSL 增强）渲染，UI 线程内完成全部物理、采样与配色，真机全程性
掉帧、倾斜时加重。2026-07-12 决定：渲染层迁移 OpenGL ES（TextureView + 自管 EGL
独立渲染线程），一比一复刻现有视觉验收后，在 GLSL 里做逐像素质感升级，并删除全部
Canvas 水体渲染路径。原因是三条约束同时成立时 GLES 是唯一解：新渲染路径必须在
minSdk 26 起严格像素级一致（排除 AGSL / API 34 Mesh）、含倾斜的稳定 60fps、以及
逐像素级的水体质感目标（微法线、逐像素光照、根治 banding）。

## Considered Options

- **纯 Canvas + worker 线程解耦**：能达成 60fps，但视觉上限停在 8bit 逐顶点插值，
  逐像素手法永久不可用；且 drawVertices 的硬件加速自 API 29 才开始，26–28 本就有洞。
- **AGSL 全通道逐像素（调研原推荐）**：性价比最高，但 API 33+ 专属，违反严格像素
  级一致的裁决；作为"核心一致 + 增强分级"的方案被用户明确否决。
- **android.graphics.Mesh（API 34+）**：架构最契合（顶点+片元着色器进 HWUI），但
  覆盖率约五成、生态过新、顶点缓冲不可就地更新，列为观察项。
- **GLSurfaceView**：对话框内 z-order / 圆角裁剪 / 出入场动画问题成套存在，弃用；
  TextureView 以一次额外合成拷贝换取完整 View 语义（Muzei GLTextureView 先例）。

## Consequences

- 现存 AGSL 三件套（抖动/软带/深度吸收）与九层 Canvas 回退在 GLES 验收一个发布
  周期后删除；EGL 初始化失败降级为静态记事色填充，不保留第二套水体实现。
- Python 模拟器新增 moderngl 渲染后端，与 Android 共享同一份 GLSL 文件——渲染层
  首次实现双端单一事实源；物理/分析/映射仍为 numpy/Kotlin 双端同构 + 差分测试。
- GLES 在新设备上经 ANGLE 转译为 Vulkan 是已接受的长期折旧风险；本视图规模
  （280×420dp）下转译损耗可忽略。
- 本决定推翻 plan-2026-07-11"明确不做 SurfaceView/GL 迁移"与 D20 的 AGSL 阶段 C
  路线；当时的判断基于连续 2.5D 水面迁移之前的低得多的 CPU 负载，且未含严格像素
  级一致这条新约束。

详细论证与量化依据见
`docs/features/audio-visualization-fable-sol/research-2026-07-12-render-architecture.md`；
决策链见同目录 decisions.md D38–D45。
