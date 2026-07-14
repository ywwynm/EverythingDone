# 待办 · audio-visualization-fable-sol

- **D95～D116 九层材质存在度待 Android HDR 真机目测（2026-07-14）**：桌面固定帧、FP16
  数值链、跨仓曲线、动态出生和顶点预算均已通过；仍需确认第 3～7 层的闪点、局部反射、微法线/
  SSS、保色阴影与连续 HDR 尾部能否形成连续但严格递减的层次，第 7 层是否足够稀有，第 8 层是否
  干净退出，以及 `3.6×` 可用 headroom 下闪点核心、局部反射、连续银泽的亮度主次。若需修订，
  按单一效果族、单一逐层曲线调整，不改变 Python `320dp` 宽度、全局候选池、时间包络或自适应
  几何质量。

- **闪点出生首帧与锚点身份匹配仅在出现证据时升级（2026-07-14）**：D116 保留当前
  `candidate * 0.12` 出生初值与逐 track 最近锚点匹配，只修复 HDR 预补偿绕过共用时间包络。
  实现阶段先增加出生首帧、相邻锚点交叉和 HDR 退出曲线回归；若测试、录像或 HDR 真机仍能观察到
  出生跳变或身份交换，再把初始强度归零、顺序保持的全局一对一匹配作为独立单变量改动，不与本轮
  九层材质扩展一起预先实施。

- **D93/D94 待 Android HDR 真机目测（2026-07-14）**：确认背光薄峰是否出现小面积、保身份色的 `1.2～1.45×` 透射亮边，且不会抬高整层 APL；确认闪点总体仍稀疏、自然淡入淡出，但跨层分布能读成一条松散连续的太阳碎光路径，而不是逐层错位或常亮光柱。若需调整，优先只改路径宽度/外侧概率底或水面透射峰值，不改变 D70 生命周期、音频映射、闪点容量、SDR 基色与 Python 宽度。

- **D92 HDR 银泽与闪点退场待真 HDR 设备目测（2026-07-14）**：离屏 FP16 数值回归显示，
  银泽水体峰值约由 `1.17` 提至 `1.28` 倍 reference white，网格对角线二阶跳变相对内部由
  `1.54` 倍降至约 `1.14` 倍；仍需在 Android HDR 设备确认银泽亮度、矩形拼片感和闪点暂停/
  卡顿后的退场。若还需调亮，只调整 HDR 银泽响应，不改变 SDR 基色、Python 宽度或 glint 数量。

- **Android 局部保色阴影待真机目测（2026-07-14）**：D91 已把 Python 验证过的
  `macro_shadow_luma_cap=0.018` 同步到 Android GLES 与 Canvas 回退，两个 Android 路径及 Python
  ModernGL 现共用同一门控和 linear RGB 亮度封顶。真机重点检查近中层是否恢复适量坡面转折、阴影
  是否随波平滑移动，以及远层和宽缓坡是否继续干净。若仍偏平，优先只把亮度上限小幅提高至不超过
  `0.024`；若显脏，先降到 `0.012～0.015`，不得放宽覆盖范围或重新压暗远层。详见
  `research-2026-07-14-clean-form-shadow.md`。

- **建立 Android/Python 确定性画面对照夹具（2026-07-13）**：Python 首轮回同步已完成，下一步可让
  两端消费同一组固定特征帧、相同随机种子和时间点，按归一化横向坐标输出关键帧与像素/结构差异。
  Python 固定 320dp、Android 280dp，不要求墙面位置或可见波长数量逐像素重合；重点核验层序、材质、
  高光分布和运动时序。当前仅完成 Python 侧 shader 编译、离屏帧与横滚扫角验证。

- **桌面系统级 HDR 输出仅在确有需要时另立任务（2026-07-13）**：Python 已具备线性 `RGBA16F`、
  headroom、逐层峰值和 360ms 增益过渡，能够读取超白数值峰值；Qt/QImage 窗口仍是 SDR 预览。
  若要验收真正桌面 HDR，需要另行选择支持 scRGB/HDR swapchain 的窗口链并覆盖 Windows、显卡驱动和
  显示器能力，不能把当前数值诊断误报为系统级 HDR。

- **Debug 202607130749 三项表层光学基线待真机对照（2026-07-13；Debug `202607130907`）**：表面反射、
  薄峰透射和闪点外围光晕已精确恢复到 Debug `202607130749`，但保留当前
  `body_light_strength=0.36`。真机需确认三项观感与参照版本一致，并单独判断加回体光后
  水体是否更饱满；HDR 进入、分层峰值、Thing 色轴与远层阶梯不应变化。

- **SurfaceView 跨版本兼容矩阵（2026-07-13；当前真机首轮已通过）**：用户已确认 Debug
  `202607130639` 在当前设备上没有圆角、层级、淡入淡出或生命周期问题，容器迁移不再阻塞 HDR
  主线。后续仍需在 API 26～33、API 34、API 35+ 分别覆盖 16dp 圆角、控件覆盖、
  `0.16 ↔ 1.0` presentation alpha、弹窗出现/关闭及前后台 surface 重建；该兼容矩阵作为发布前
  扩展覆盖，不重复要求当前设备验收。

- **轻微折射仅在 HDR/材质主路径仍不足时独立试验（2026-07-13）**：先完成 D60 的环境/内部
  宽柔光隔离、统一反射与浪峰透射，以及 D56～D57 的 HDR 输出。若真机仍明确缺少透明介质感，
  再以恢复后的当前基线做单一、小幅、可归零的折射 A/B；不得恢复 A5.5 组合方案，也不得同时
  恢复已删除的焦散。

- **GL 迁移其余维度已亲自核对（2026-07-13 深挖，除颜色/光学外基本健康）**：
  - 物理/动感/响应一致：GL 与 Canvas 调同一个 `FableSolSimulation.update`（固定子步 PHYSICS_DT
    + acc 累积器，dt 只决定子步数），dt 语义两侧逐行相同（Choreographer 时间戳差、clamp 0.05、
    首帧 1/60、同一 `FableSolFramePacer` 锁 60Hz）。波速/流速/沉降两侧相同。
  - 事件分发集合一致：两侧都 `applyFrame`(仅最后帧)+`applySilence`+`Onset/Section/Prominence`，
    相同顺序（Canvas onAudioFrames L305-313 vs GL drainAndApply）。
  - 传感器同源：`AudioRecordDialogFragment` 唯一注册 `TYPE_GRAVITY?:ACCELEROMETER`，经 Host 同一
    `setContainerGravity(-screenX,screenY,gz)` 转发；`tilt=atan2(x,y)`、`pitch=atan2(z,hypot)`
    两侧公式完全一致（Canvas L223-228 vs GL applyLatestGravity）。
  - 几何/透视/depthScale：一致。帧节奏 `FableSolFramePacer` 逻辑正确（120Hz 稳定降 60，步长交给
    真实时间戳）。竞态 `FableSolGravityInbox` seqlock（奇偶序号+AtomicInteger 夹逼，字段非 volatile
    但对 3-float 容错传感器数据实践安全）。网格 `FableSolGlMeshLayout` Z 行三角带无裂缝（待确认
    `Z_ROWS=(N_LAYERS-1)*ROWS_PER_LAYER+1`）。生命周期 surface/attach/detachBlocking(750ms)/
    后台停渲染/dismiss 释放链路中曾遗漏 EBO 缓存失效；A1 已用 EGL 资源重建状态回归修复。
  - **唯一非观感隐患（低-中）**：`FableSolGlProgram.uniform()` 对 location<0 直接 check 抛→fatal→
    回退 Canvas 红天空；当前 shader 所有 uniform 都用到不触发，但将来改 shader 留下未用 uniform 会
    在某些驱动直接崩回退，应改为忽略（返回 -1 跳过 glUniform）。
  - 结论：物理/几何/线程/输入主体基本忠实；A1 生命周期故障与颜色/光学差异已在
    `research-2026-07-13-gles-parity-audit.md` 核验并修复，待真机验收。
  - 注：一次 Read 工具输出异常（错乱行号+重复 placeholder 行）经 `grep -c placeholder`=0 证伪，
    文件本身干净，非代码问题。

- **GLES 迁移三阶段执行中（2026-07-12，主线）**：见
  plan-2026-07-12-gles-migration.md 与 ADR-0016。Stage 0 首轮已完成帧仪表、锁 60Hz、
  光学 DoubleArray 零分配和传感器后台 latest-value 合并。`202607121348` 的首轮日志已证明
  首次 attach 时因尺寸为 0 导致帧循环未启动，现已在 `onSizeChanged()` 补启动，待新版真机
  确认恢复动画并回传包含 `onDraw[...]` 的 `debug_logs/fablesol_frame_perf.log`。第二份
  真机日志已测得 onDraw P50 约 32.8ms，而 GPU P50 约 4ms，确认 CPU/UI 线程瓶颈；
  Stage 0 平滑核重复计算回归已修，待新版日志形成 Canvas 最终基线。AGSL 位图 atlas
  不再阻塞主线。Stage 1 首个 TextureView+EGL 纵向切片已接入录音 Dialog并完成阶段验证；
  该容器现已由 D64 修订为 SurfaceView+EGL，待迁移后重新验证
  EGL/GLSL 存活、透明合成/圆角/动画、基础连续水面、音频与倾斜响应，以及
  `glFrame` 分段耗时；随后迁移闪点/珍珠/流光/猫爪、表面软带、薄峰透光、波背阴影与
  羽化，完成一比一视觉复刻（验收
  后一个发布周期删除全部 Canvas 水体渲染与 AGSL 三件套，D42）→ Stage 2 十一项
  逐像素视觉升级（D45）。模拟器需同步新增 moderngl 后端共享 GLSL（D43）。
  A6 在整个 GL 计划完成后解冻（D44）。

  真机 `202607121451` 已确认 GL 正常且持续倾斜不卡，基础 GLES 性能验收通过。珍珠与猫爪已按
  用户裁决整体删除；镜面闪点、顺流流光、表面软带、薄峰透光、波背阴影与远层羽化已进入 GLES。
  表面软带与远层羽化已恢复 Canvas 原始参数；A1 与 B1～B9 审计确认项已修复，体积光带和波冠
  轻纱也已迁移。下一版需真机确认：后台返回不再红屏、白带在相同参数下恢复正常、渐变记事配色、
  天空 banding、整体明暗和光学软边 alpha 均接近 Canvas，并继续确认性能。Stage 1 剩余工作主要为
  Stage 1 真机观感已于 `202607130124` 通过。Stage 2-1 色相保持高光压缩因真机高光变暗、观感
  变差而被整项删除，回退版已确认正常。Stage 2-2 双色深度散射按用户反馈微调为 0.21；当前只
  加入 Stage 2-3 解析镜面抗锯齿，待真机确认倾斜/运动时闪点是否更稳定、亮度与数量是否仍自然、
  双色散射和帧率是否保持；通过后再进入风耦合。

- **Stage 1 历史失败回退验证（2026-07-12；由 D64 取代容器）**：`202607121429` 因 XML 向 TextureView 设置
  transparent background 在构造阶段崩溃，已改 Host 管理 GL/Canvas。新版需确认正常 GL
  不显示红色；若 EGL/GLSL/绘制失败，应自动出现可运动的 Canvas 水面且天空为纯红色，日志
  同时含 `[DEBUG-FABLESOL-GL] fatal`。

- **核实 API 26–28 上 drawVertices 的实际行为（2026-07-12）**：调研确认硬件
  Canvas 的 drawVertices 支持自 API 29 始；26–28 设备上当前连续水面可能静默
  丢失或整 View 软件光栅化。GLES 落地即根治；在此之前如有旧设备反馈异常，
  按此归因。

- **低屏幕内重力投影下的滚转稳定性待真机确认（2026-07-12）**：Android Z 轴滚转链没有
  角度限幅或临界回退，`±180°` 已做连续展开；但手机接近平放时 `hypot(gx,gy)` 趋近于零，
  重力传感器无法观测绕屏幕法线的角度，当前也没有低投影门控。若真机的“回退”只在这一姿态
  出现，应记录发生时的持机姿态，再考虑按投影置信度冻结滚转、恢复后沿最近等价角继续，而不是
  修改现有 360° 展开逻辑。

- **连续 2.5D 水面首轮修复待真机复测（2026-07-12）**：Z 轴翻滚远处两侧空白已由
  投影安全采样窗修复；24 次 Path/Gradient 已改为 8 次分层三角网格，二维场纯数学采样
  基准约快 4 倍。用户进一步确认常态播放流畅，主要卡顿发生在倾斜手机期间；已把倾斜时
  最高 190 列的动态渲染固定为最多 120 个等距插值列，并把边界剖面从约 114Hz 限为 30Hz。
  修复后仍需真机确认两侧无空白、共享边无裂缝、倾斜流畅度、帧率和发热。

- **画外出生硬保证已双端同构完成（2026-07-11）**：Android injectLayer 与
  Python sim.inject_layer 同修（画外全支撑、共鸣档塌缩移除、中心网格上限防
  静默丢包）；Python 新增 4 项回归（test_offscreen_birth.py），双端全绿。
  Android 补丁发布 `202607111443`。注：8dp 出生间隙下波前 ~50ms 合法入画，
  保证语义=渐入期无宏观突现（锁步测试阈值 0.05dp/0.04s 即此语义）。
- **焦散已整体移除**（两轮修形仍"不好看"）；深度吸收（absorption_gain 0.35）保留。

- **C 阶段返工中（2026-07-11 真机红色天空确诊）**：AGSL 不支持 uniform 数组动态
  索引（GLSL ES 1.0 fragment 限制），C2 band 与 C3 layerFill 一直静默回退（C1 抖动
  无数组应存活）。返工方案：轮廓 top/th 归一化后写入 RGBA_F16 216×1 Bitmap 池
  （每帧每带独立位图防记录别名），shader 以 `uniform shader data` 在纹素中心
  eval 采样 + 手动线性插值。诊断天空色与 C3 夸张档保留至真机复验通过后移除。

- **阶段 C 进度（2026-07-11）**：C1 抖动 + C2 软带逐像素已真机确认无问题；
  C3 深度吸收（`absorption_gain` 0.35）+ 焦散（`caustic_gain` 0.5）已实装并发布
  （Debug `202607111334`），待真机验收（介质深度感/光脉柔和跟流/两参数 A/B/
  帧率发热）。**折射视差暂缓**：第 0 层不透明，本架构中真折射无"透过水看背后"
  语义，只能做假的填充扰动——用户看过 C3 效果后再定去留。渲染保真度验收以
  Android 真机为准。

- **传播式 Hero 包络待真机复测（2026-07-11）**：Python/Android 已同构修复并通过自动回归；
  需用连续“你好你好你好”、强弱交替和升降调人声确认不再出现既有浪突然上抬，同时观察新的
  上游传播是否显得过迟。若仍有突变，下一诊断对象依次为全局 `shapeRoughness01` 慢聚峰与
  `pan01` 相位平移；不得回退为全局 Hero 振幅标量。

- **Android 表达/材质批已迁移，待真机目测（2026-07-11）**：A1～A6、B1 与视觉批次 2
  已接入 Kotlin；需重点确认持久闪点/珍珠斑是否稳定、表面带与薄峰透光的立体感、流光和
  轨道微摆是否自然、波背自阴影/空气透视/冷暖/1/f 是否克制，以及 HNR 清澈度、looming
  生长和“张力=相位”是否值得保留。张力试验仍可整体移除（搜索 `tension01`）。

## 2026-07-11 表达力升级计划产生（见 plan-2026-07-11-expression-upgrade.md）

- **A1~A5 已完成主体实现（Python 侧，2026-07-11）**：A5 最新为持久光斑实体、表面带与
  接触阴影，等待用户动态目测；A6 尚未开始。本轮 Kotlin 移植仍随阶段 B 统一进行，移植增量已
  记录在模拟器 `docs/porting-notes.md`。回归样本 `20260710235706.wav`（长录音）不在 assets
  目录，Android 验证时需用户重新提供或以现有样本替代。
- **A5.5 材质纵深方案已否决并回退**：三段光学合成、组内层降权、大面积浪顶平面、深度吸收与
  折射视差组合观感不如此前 A5，相关 Python 代码、参数、测试、素材和移植说明均已移除。后续视觉
  试验必须以恢复后的 A5 为基线，一次只改一个手法并直接 A/B。其调研文档
  `research-2026-07-11-material-depth-direction.md` 为 GPT 所作，用户要求降权参考。
- **翻滚感三手法已实装待目测**（`research-2026-07-11-orbital-roll-and-light.md` 的①②③，
  2026-07-11）：`thin_glow_gain` / `flow_streak_gain` / `orbital_sway_dp` 三滑杆在
  外观组，归零即回 A5 基线。④低幅 Gerstner 渲染位移仍为尾序试验未做。用户 GUI
  反馈后逐项定去留。
- **接触阴影已移除（2026-07-11 用户裁决）**：偏灰、每层常驻、难看。层间厚度感的
  替代候选=波背自阴影（`research-2026-07-11-aesthetic-extensions.md` 第 1 项，层内
  明暗转折，色相保持），待用户圈选。不得原样恢复灰色接触阴影。
- **渲染性能基线（2026-07-11）**：离屏 640×840 逐帧 grab，音乐场景 mean 9.88ms /
  p99 11.6ms（优化前 27.66ms）。新增视觉手法后应重跑 `scratch/paint_bench.py`
  确认不破 60fps 预算。
- **扩展清单第一批+第三批+1/f 已实装待目测**（2026-07-11 用户圈选）：
  `back_shade_gain` / `aerial_contrast` / `hue_temp_deg` / `pink_mod` 四滑杆，
  归零即关。**通过后解冻 A6（声音驱动表达批）**。
- **glitter path 光柱：用户未圈选，暂缓**；双谱 swell+chop（动 simulation 波合成，
  侵入性高）暂缓；备选梯队（lost-and-found 边缘、英雄高光层级、Ponyo 波浪手势、
  SoT 双体色）继续搁置，见 `research-2026-07-11-aesthetic-extensions.md`。
- **声音驱动层暂缓**：用户当前优先提升纯视觉材质；A6 中 HNR/arousal/looming/冲击性映射、
  “张力=相位”及其他声音驱动扩展暂不继续，待视觉效果收敛后再评估。

- **暗色天空 bug（D21 修复层）**：录音前后天空色彩翻转，成因已定位（View alpha 0.16→1.0
  × 天空基色硬编码白）；用户指示后置处理，时机待定。
- **音乐中人声存在检测（2026-07-11，旧模型验证完成待校准头）**：旧模拟器的 14.9MB YAMNet
  权重有效；已确认 top-1、错误类别集合、31.25Hz 过度推理和 `music*0.24→singing` 融合是现有
  识别失败主因。下一步以 Jamendo/自有许可数据训练 `vocal_present` 校准头，优先完整 521 scores
  小头，不足再用 1024 维 embedding + causal GRU/TCN。Android 以 2Hz standalone LiteRT 独立线程
  接入，输出 `vocalPresence01`；Essentia voice/instrumental 仅作研究基线（CC BY-NC-SA，不直接
  发布），Silero/WebRTC VAD 与实时源分离均不作为主路线。详见
  `research-2026-07-11-android-vocal-presence.md`。
- ~~**AGSL 立项条件（D20）**~~：已被 D40/D41 取代（2026-07-12）——严格像素级
  一致排除 AGSL 新特性，逐像素路线改走 GLES（ADR-0016）；OLED banding 由 GLES
  着色器内抖动根治，全设备一致。
- **张力=相位与驻波呼吸带试验标签**：Python 目测不喜欢即砍（D18/D20）。
- **离线音乐文件可视化模式（D14 范围事实）**：未来功能，复用境 4~6 与段落导演路径。

## 2026-07-10 迁移审查新增

- **后台与队列**：把 `pendingFrames` 改成单槽 latest frame，离散事件使用有上限队列；动画条件加入窗口可见性，
  并确认 Fragment 进入后台时是否继续占用麦克风。
- **感知流速真机校准**：双时间尺度表层事件率、75% subdivision 保底和 tempo 正向佐证已经在 Python/Android 同步实现。等待用户用阿里云 Debug 对 `20260710234846.wav`、`20260710235706.wav` 对应场景做真机主观确认；若长录音约 0.83 的中位 `flow01` 仍过快或偏慢，只调整表层密度标定/保底比例，不提高物理上限或全局 `flow_gain`。
- **差分回归测试**：把 Python 版输出固化为 fixture，覆盖 Analyzer、onset/beat、FeatureMapper 与 Simulation；
  当前已有第 0 层颜色策略、运行时容器几何、超声泵动近静音/稀疏可听脉冲、采集启动低频预热，
  以及 onset/模态目标不得直接重塑既有浪形的测试。

首版已可运行并发布，以下项待真机反馈后处理：

- ~~**性能：高光 DoubleArray 分配**~~：Stage 0 已把光学路径改为按锚层复用的 scratch
  buffer；第二份真机日志显示主要剩余成本是 physics、逐顶点 color 与 optics CPU 计算，
  已转由 Stage 1 GLES 迁移解决，不再通过降低 N_POINTS/PHYSICS_HZ 牺牲视觉或物理质量。
- **配色/环境天空观感**：环境天空以白为 base、记事色做极浅染色（`environment_tint`）。若与对话框
  观感冲突（例如白底过重），再讨论是否弱化天空或改 base。
- **静止/停止态**：无音频帧超过 200ms 调 `applySilence` 衰减；真机确认停止录音后水面收敛是否自然。
- **参数固化**：原版可调参数已按默认值硬编码，暂无调参入口；若需现场微调视觉，再考虑最小暴露方式。
