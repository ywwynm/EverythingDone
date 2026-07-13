# Current Debug Update Notes

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
