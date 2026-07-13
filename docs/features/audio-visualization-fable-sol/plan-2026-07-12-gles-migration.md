# 计划 · FableSol 性能修复与 OpenGL ES 迁移（2026-07-12 grill 定稿）

依据：D38~D45、ADR-0016、三份调研
（research-2026-07-12-render-architecture / perf-jank-diagnosis / water-visual-quality）。

**目标**：① 稳定 60fps（View 锁 60Hz，静置/说话/倾斜全程，D38）；② API 26+ 严格
像素级一致（D40）；③ 逐像素质感升级（D45 十一项）。物理/分析/映射层不动，仍为
Python(numpy)/Kotlin 双端同构。

## Stage 0 · 通用性能修复（任何架构下都需要，全部可保留）

1. **临时帧仪表（D39）**：FrameMetrics 挂对话框自己的 window（不是 Activity 的），
   收集 DRAW/SYNC/COMMAND_ISSUE/SWAP/GPU_DURATION(31+)/TOTAL 滚动 P50/P95/P99；
   onDraw 六段计时（drain/物理/采样/配色/装配/提交）写入应用私有日志文件；
   随帧记录 refreshRate、thermalHeadroom。性能验收后整体移除。
2. **锁 60Hz**：Choreographer frameTimeNanos 自节流（全版本、确定生效）+ 对话框
   Window 设 preferredRefreshRate=60 作窗口级提示；模拟步长继续与
   frameTimeNanos 解耦（已有 120Hz 固定子步）。
3. **稳态零分配**：drawHighlights 等光学路径的每层每帧 DoubleArray 全部提为成员/
   池（消除 GC 尖刺——偶发顿挫的头号嫌疑）。
4. **传感器合并**：重力监听移到后台 HandlerThread，回调只写 volatile 最新值，
   帧循环每帧读一次；SENSOR_DELAY_GAME 保持。
5. **位图上传治理（过渡期）**：AGSL 软带的 216×1 位图合并为单张 atlas（30 次
   微上传 → 1 次）；GLES 落地后此路径整体消失，此项只在 Stage 1 拖长时才值得做，
   顺序可与 Stage 1 合并裁决。
6. **核实 API 26–28 drawVertices 行为**（调研发现硬件加速自 29 始）：确认现状
   是静默丢失还是软件回退，记录到 followups；GLES 落地即根治。

Stage 0 验收：真机仪表数据显示锁 60Hz 后 P95 帧时长 < 16.6ms 的程度、GC 尖刺
是否消失；用户主观"全程不顺滑"是否已缓解——结果决定 Stage 1 的紧迫度，不改变
其必要性（严格一致 + 逐像素目标仍只有 GLES 满足）。

## Stage 1 · GLES 渲染器一比一复刻

- **形态**：TextureView + 自管 EGL（Muzei GLTextureView 模式）+ 独立渲染线程；
  渲染线程按 60Hz 节拍跑"模拟推进 + 采样 + 绘制"，音频线程照旧投喂
  frames/events 队列，UI 线程只剩生命周期与 TextureView 集成。
  透明度：TextureView setOpaque(false) + 带 alpha 的 EGLConfig，保持对话框
  圆角/暗化/出入场动画全部正常。
- **GLES 版本**：以 ES 3.0 编写（API 26+ 时代硬件实际全支持）；着色器避免
  3.1+ 特性。理论上的 ES2-only 设备走 D42 的静态色填充降级，不做第二套水体。
- **管线**：高度场/轮廓行 + 九层调色板经小纹理（216 宽，每帧一次上传，三缓冲）
  进入片元着色器；层合成、渐变、纵向受光、Fresnel、深度吸收、软带剖面、抖动
  全部逐像素；闪点/珍珠/流光/猫爪等实体继续 CPU 跟踪（几何小、逻辑复杂），
  以实例化小面片或参数化 uniform 交给着色器绘制。
- **复刻语义**：观感等同现状（允许因逐像素插值而优于现状的差异，如更平滑的
  渐变与无阶差软带）；九层颜色语义、D1/D6 配色策略、D33~D36 阴影颜色策略、
  D37 俯仰策略全部按现有 Kotlin 实现为准移植。
- **双端**：模拟器新增 moderngl 后端（D43），与 Android 共享同一份 GLSL；
  物理/映射差分测试照旧。
- **验收**：用户真机目测（D22）+ 仪表确认含倾斜稳定 60fps；通过后一个发布
  周期删除全部 Canvas 水体渲染与 AGSL 三件套（D42）。

## Stage 2 · 逐像素视觉升级（D45 十一项）

首波按序：①色相保持高光压缩（Khronos PBR Neutral，置于抖动之前的最后色阶段）
→ ②双色深度散射（OKLab 派生 deep/subsurface，浪峰收拢掩码混合，色相偏移 ≤10°）
→ ③解析镜面抗锯齿（亚网格波分量坡度方差 → 高光指数收窄）→ ④风耦合（猫爪
粗糙度场同时驱动天空带局部压暗与闪点密度/亮度，闪点 Cox-Munk 纵向拉长 ≤2:1）
→ ⑤1/f 呼吸（pink01 接全局波幅/阵风节奏/闪点出生率）。

二波：微法线风梳纹理（IQ 解析导数值噪声 2~3 倍频程，按行带限，依赖③）、
朝阳 SSS（Crest 脚手架，高衰减指数 4~10，掩码=浪峰收拢，禁接瞬态音频）、
解析光晕（闪点周围数学 falloff，置于①之前的线性域）、Display P3 广色域窗口、
近岸弯月面细线、线条笔意（3~7 条顺流细亮线，原型标签，最后做，做不好即砍）。

纪律（每项适用）：独立开关、一次只改一项、模拟器 moderngl 目测认可后才移植、
宁少勿烂整项砍；A5.5 教训——不得同批改变层次、颜色与表面结构；红线全套继续
有效（不尖窄、水位慢变、浪形连续、阴影保持记事色、远层黑度很淡）。

## 之后

GL 计划整体完成后解冻 A6（D44）。Mesh API（34+）为 2027+ 观察项（ADR-0016）。
