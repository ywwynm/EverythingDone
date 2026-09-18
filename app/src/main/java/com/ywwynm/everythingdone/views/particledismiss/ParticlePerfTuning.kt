package com.ywwynm.everythingdone.views.particledismiss

import com.ywwynm.everythingdone.BuildConfig
import org.json.JSONObject
import java.io.File

/**
 * 粒子动画性能专项第一批的逐项开关。默认全部开启；debug 包可以用应用私有目录下的
 * `files/particle-perf-tuning.json` 逐项关掉，用来做同一台设备上的 A/B 复测。
 * release 包读不到任何文件，取值恒为默认。
 *
 * GPU 侧各项（[jacobiLargeTile]、[historyInIntegration]、[skipBeforeFirstBirth]）都要求
 * GPU 输出逐位不变（G0），由 `ParticleTrajectoryCheck` 的 61 帧散列对照守住。
 */
internal object ParticlePerfTuning {

    const val CONFIG_NAME = "particle-perf-tuning.json"

    // 1.9 的反向时长换算（rippleDurationCompensation）已随第二批「时间基准统一」删除：
    // 现在全应用只有一条基准（AnimatorDurationScale），自绘涟漪与暗层交标称时长给
    // ValueAnimator，粒子自算时长乘同一个值，不存在两条口径。
    // 1.8 的节拍线程（ParticleClock）已成为唯一实现，不再留开关——挂 GL 线程自己的
    // Looper 实测明显掉帧，原因与三档 A/B 数据见 ParticleClock 的注释。

    /** 1.6：圆角遮罩挪到材料线程，在取像素之前做。 */
    @Volatile var maskOnMaterialThread = true

    /** 1.6：暗层与粒子层挂 DecorView 的 ViewGroupOverlay，不触发整窗重新布局。 */
    @Volatile var overlayInDecorOverlay = true

    /** 1.6：录音弹窗的传感器注册与通知渠道创建移出主线程 / 延后。 */
    @Volatile var deferRecordDialogInit = true

    /**
     * 1.7：材料线程与预计算在**已经有触摸反馈或别的粒子层在播**时让出到默认优先级，
     * 只有正在逐帧出画的那条 GL 线程保持 DISPLAY。无条件降级会拖慢左滑的按下准备。
     */
    @Volatile var defaultPriorityPreparation = true

    // 1.4 / 1.5 / 1.2（一窗一 Surface 的 SurfaceView、常驻 GL 线程与上下文、Surface 无缝
    // 帧率投票）在第二批完成，是结构性重构，没有留开关：两条路径并存要维护两套交接不变式。

    /**
     * 2.2：Adreno 上用 32×32 输出 tile 的批处理 Jacobi。
     *
     * **默认关闭：G0 成立但没有收益，第一批撤回。** 2026-09-14 两台真机实测的出现预计算墙钟
     * 中位数（每档 5 次热运行）：
     *
     * | 变体 | OPD2515 / Adreno 829 | SM-S9180 / Adreno 740 |
     * | --- | --- | --- |
     * | 现状 16×16 输出 + 8 边带（13 次 dispatch） | 181–184 ms | 274 ms |
     * | 32×32 输出 + 8 边带（13 次） | 183 ms | 272 ms |
     * | 32×32 输出 + 12 边带（9 次） | 196 ms | 328 ms |
     * | 32×32 输出 + 16 边带（7 次） | 213 ms | 311 ms |
     *
     * 61 帧散列在两台设备上对 8/12/16 三档都逐位一致，所以不是正确性问题：单纯是
     * 每工作组 26–32 KB 的 shared 把占用率压到极低，省下的 dispatch 次数换不回来。
     * 这也与阶段 0「dispatch 次数不是瓶颈」的结论一致。开关留着便于复现与后续复测。
     */
    val jacobiLargeTile: Boolean get() = !baselineGpu && jacobiLargeTileSetting

    @Volatile private var jacobiLargeTileSetting = false

    /** 2.2：大 tile 每批迭代数（8 / 12 / 16）；小 tile 路径固定 8。 */
    @Volatile var jacobiLargeBatch = 12

    /**
     * 2.1：历史转存并入采样最后一步的积分着色器。
     *
     * **默认关闭：G0 不成立，第一批撤回。** 2026-09-14 在 OPD2515（Adreno 829）实测：把
     * `step.comp` 的 `main` 改名为 `integrate`、再加一个先调用它、后写五分量历史的 `main`，
     * 即使在**根本不写历史**的消散路径上（history_frame 恒为 -1，追加的那段一次也不执行），
     * 61 帧散列也从第 11–16 帧起全部不一致；出现路径从第 7–10 帧起不一致。也就是说
     * 差异来自驱动对重组后代码的浮点代码生成，不是那次多余的写入。着色器数学一个字没改，
     * 但 GLSL ES 不带 `precise`，驱动可以自由重排与融合，逐位相同无法保证。
     * 开关留着只为复现这条结论。
     */
    val historyInIntegration: Boolean get() = !baselineGpu && historyInIntegrationSetting

    @Volatile private var historyInIntegrationSetting = false

    /** 2.4：最早出生时间之前跳过积分与压力更新。 */
    val skipBeforeFirstBirth: Boolean get() = !baselineGpu && skipBeforeFirstBirthSetting

    @Volatile private var skipBeforeFirstBirthSetting = true

    /**
     * 3.1（第三批）：识别左滑时历史不足就先画**已经备好的最高一档**，随后每拍按手指速度追上，
     * 绝不在出帧路径上阻塞补档。
     *
     * 第二批的回退就出在这里：甩动在 20–30 ms 就越过识别阈值，那时材料还没建好；等材料就绪
     * 时手指已经拖过 20%，首帧必须先同步补齐 `ceil(position)` 档历史（三星单档约 7 ms，
     * 补 12 档就是 80 ms 以上）。关掉这一项即恢复第二批的阻塞补档行为。
     */
    @Volatile var gestureCreepToFinger = true

    /**
     * 2b（第三批，外观）：创建页的出现动画以**新建按钮中心**为真实触点，取值决定用到哪几项：
     *
     * | 值 | 方向 | 触点距离 `touch_gap` | 触点强度 `touch_strength` |
     * | --- | --- | --- | --- |
     * | [TOUCH_OFF] = 0 | 固定 315° 虚拟远触点 | 默认 0.65 | 默认 0.5 |
     * | [TOUCH_DIRECTION] = 1 | 内容中心指向按钮 | 默认 0.65 | 默认 0.5 |
     * | [TOUCH_DIRECTION_GAP] = 2 | 同上 | 真实（按钮在内容内 ⇒ 0） | 默认 0.5 |
     * | [TOUCH_FULL] = 3 | 同上 | 真实 | 真实（⇒ 0） |
     *
     * 只改材料输入，不改模型与着色器。默认取 [TOUCH_DIRECTION_GAP]，由 3b 的归因轮选定：
     * **「从右下角那一点扇形汇入」的观感来自触点距离归零**（`step.comp` 的 `destination`
     * 落到内容边界上，每片按到那一点的方位角偏转）；再把触点强度也归零只是让分布更散，
     * 观感增益很小，而 OPD2515 上每采样预计算还要再贵 5 个百分点。
     */
    @Volatile var creationAppearanceTouch = TOUCH_DIRECTION_GAP

    const val TOUCH_OFF = 0
    const val TOUCH_DIRECTION = 1
    const val TOUCH_DIRECTION_GAP = 2
    const val TOUCH_FULL = 3

    /**
     * 2c（第三批，外观）：创建页的出现动画跳过倒放尾段几乎无粒子的部分——按材料的出生与寿命
     * 在 CPU 上算每个采样的存活片数，取「存活比例仍 ≥ [ParticleReversePlan.APPEARANCE_ALIVE_RATIO]」
     * 的最晚采样作为起点；播放时长按保留比例缩短（每帧速度不变），预计算也只算到那一个采样。
     * 保留段内的每一帧与不跳过时逐位相同。
     */
    @Volatile var creationAppearanceSkipEmptyTail = true

    /**
     * 第四批 ①：弹窗、页内面板与新卡片的出现也启用同一套「跳过尾段空白」。
     *
     * 机制、门槛（[appearanceAliveRatio]）与逐位一致性保证与创建页完全相同；分成两个开关
     * 只是为了能单独回退某一条路径。默认开。
     */
    @Volatile var dialogAppearanceSkipEmptyTail = true

    /**
     * 第九批第 2 条：含投影的页内面板（目前只有粒子动画设置页的示例对话框）在粒子起止两端
     * 走 [ParticleCardSurfaceHandoff] 的**窗口内原子交接**。
     *
     * 关掉它就退回第八批那条：含阴影的快照画在窗口内的叠加层上、交接那一次遍历里把真实
     * `elevation` 置 0。两条路径的差别只在交接的那一两帧——**半透明的投影**在「先显后撤」
     * 的重叠期会叠成两层，看上去就是起止各闪一下。留这个开关是为了能在**同一个包**上做
     * 受控对照（同一台设备、同一条动作，只切这一项），不必为了取修前数据重建旧版本。
     */
    @Volatile var panelShadowAtomicHandoff = true

    /**
     * 2c 的门槛：存活片占比低于它的采样算「几乎是空画面」，从倒放里去掉。
     * 提高它会跳掉更多帧——既让起始段更明显，也按比例省下预计算。
     * 第四批起创建页与弹窗共用这一个门槛（10%）。
     */
    @Volatile var appearanceAliveRatio = ParticleReversePlan.APPEARANCE_ALIVE_RATIO

    /**
     * 第四批 ②：首页空闲时为下一次新建预先建材并预算轨迹，点新建按钮后只上传真实贴图即开播。
     * 关掉它等于回到第三批的行为（每次新建都完整走建材 + 预计算）。
     */
    @Volatile var creationPrewarm = true

    /**
     * 第四批 ②：首页进入空闲多久之后开始预算（毫秒）。太短会与列表滚动惯性争 GPU。
     */
    @Volatile var creationPrewarmIdleMs = 500L

    /**
     * 3b：粒子 Surface 一就绪就先交换一张全透明帧。
     *
     * 一块新表面的**第一次**缓冲要等 SurfaceFlinger 给这一层做首次合成：实测 OPPO 上第一次
     * 出现动画的「提交 → 上屏」是 82–88 ms，同一次运行里之后每一次只有 14–17 ms。
     * 先交换一张空帧就把这一次等待挪到动画开始之前。
     */
    @Volatile var primeParticleSurface = true

    /**
     * 3b：会话在准备/预计算/等触摸反馈期间就让节拍跑起来。
     *
     * 否则预计算结束时节拍才启动，要重新登记 Choreographer 再等下一次 vsync，首帧白等一拍
     * （实测 OPPO 25 ms、三星 31 ms）。节拍空转的代价只是每 vsync 一次 wake，`composite`
     * 在没有内容时立即返回。
     */
    @Volatile var beatDuringPreparation = true

    /** 3b：本地材料库的并行道数上限（1 表示完全串行，只用于逐位对照）。 */
    @Volatile var materialLanes = 0

    /**
     * 第四批 ③：往 atrace / Perfetto 里打自定义分段（见 [ParticleTrace]）。**默认关闭**，
     * 只在带采集的那一轮打开——`Trace.beginSection` 本身有几微秒的开销，不该留在常态里。
     */
    @Volatile var atrace = false

    /**
     * 第四批 ③：触摸反馈（自绘涟漪）可见期间，预计算每 4 个积分步就冲一次并等上一批算完，
     * 把驱动队列里排着的 GPU 工作压到一个压力批次以内。
     *
     * 三星弹窗窗口涟漪期长帧的分段形态是「`DRAW` 0.2–0.4 ms，而 `COMMAND_ISSUE` p90 11.9 ms
     * + `GPU` p90 15.9 ms」——自己画得很快，却要排在别人后面。默认**关闭**：它必然让出现动画
     * 的首帧更晚，开不开由第四批那一轮采集的结果决定。
     */
    @Volatile var yieldPrecomputeDuringFeedback = false

    /**
     * 第四批 ③ 的正式修正：自绘涟漪期间给宿主 View 投 `REQUEST_FRAME_RATE_CATEGORY_HIGH`。
     *
     * 采集给出的根因是「脏区太小 → 可变刷新率把整窗压到 20–30 Hz」，不是任何一段画得慢
     * （见 [com.ywwynm.everythingdone.views.GradientRippleDrawable.voteFrameRate] 的注释与
     * batch4-results 第 4 节）。外观不变，只是同一段动画多出几倍的帧。API 35 以下无此机制。
     */
    @Volatile var rippleHighFrameRate = true

    // ---- 逐帧成本专项 第一批（2026-09-17）----
    //
    // 两项都只改「同一件事在哪一级算」，不改模型、不改参数。A1 属 G1（浮点结合律变了），
    // C10 属 G0（写的格子少了，值一个比特不动）。每一项都可以单独关掉回到改动前的路径，
    // 离屏 A/B（[ParticleSegmentBench.runPerFrame]）就是靠 [withPerFrame] 逐位切换。
    //
    // 同批评估过、**因为魅族上量不出收益而没有落地**的两项（数字见
    // `docs/features/particle-animation-performance/per-frame-batch1-2026-09-17.md`）：
    // A2「把只依赖方向的量提成 uniform」与 A3「流场与置信度并成一张 RGBA16F 纹理」，
    // 三条路径上都落在 ±2% 的本底噪声里，按本专项的纪律整段删掉，不留「理论上更快」的代码。

    /**
     * A1：`material.vert` 里只依赖片与时间的量改由 compute 每片每帧算一次，写进紧凑的
     * 「绘制记录」缓冲（位置流 + 外观流各一段），顶点着色器只做二维仿射展开。
     *
     * Adreno 把每个顶点着色器编成 bin-pass 与 render-pass 两份，而 `gl_Position` 的依赖链
     * 几乎吃掉全部昂贵数学——现状是每片每帧 4 顶点 × 2 遍 × 2 段 = **16 次**完整求值。
     */
    val drawRecords: Boolean get() = perFrameOn(PER_FRAME_DRAW_RECORDS) && drawRecordsSetting

    @Volatile private var drawRecordsSetting = true

    /**
     * C10：压力更新的清零、两遍模糊、tile 收集与投影都只跑**本帧粒子包围盒外扩一圈**那片
     * 区域，不再整网格 dispatch。包围盒由 splat 的原子 min/max 给出，区域取「上一轮处理过的
     * 区域 ∪ 本帧包围盒 + 5 格」，因此区域外的三个缓冲恒为零——**卡片内外都逐位相同**。
     */
    val pressureActiveRegion: Boolean get() =
        perFrameOn(PER_FRAME_ACTIVE_REGION) && pressureActiveRegionSetting

    @Volatile private var pressureActiveRegionSetting = true

    const val PER_FRAME_DRAW_RECORDS = 1
    const val PER_FRAME_ACTIVE_REGION = 8
    const val PER_FRAME_ALL = PER_FRAME_DRAW_RECORDS or PER_FRAME_ACTIVE_REGION

    // ---- 逐帧成本专项 第二批（2026-09-17）：全部是 G0，只改调度与时序 ----
    //
    // 四项各自可以单独关掉回到第一批的行为，真机的「修前」那一轮就是把它们一起关掉。
    // 没有任何一项碰着色器、模型、参数或采样内容。

    /**
     * F14：常驻 GL 线程上再开一个**同 share group、低优先级**的上下文，出现预计算、左滑预读
     * 与创建页预算都跑在它上面，出帧那一条命令流因此不再排在几十次 compute 之后。
     *
     * 优先级用 `EGL_IMG_context_priority`，**实际档位必须 `eglQueryContext` 读回**
     * （规范原文说请求值只是 hint）；读不到、驱动没这个扩展、或者两档没有分开，就退回单
     * 上下文，行为与第一批完全一致。结论写在 [ParticleGl.contextNote] 里。
     */
    @Volatile var backgroundContext = true

    /**
     * F15：预计算与预读改成「提交一个采样就放栅栏、不等」，由栅栏轮询把采样转成「可以画」。
     *
     * 第一批之前每个采样都要 `glClientWaitSync` 等上一批算完（左滑 4 ms、弹窗 6–7 ms），
     * 一次 pump 的 8 ms 预算里只塞得下两个采样，而这段阻塞正好压在节拍上——阶段 0' 量到的
     * 「左滑每两个 vsync 才出一帧」就是这么来的。关掉它回到阻塞口径。
     */
    @Volatile var asyncPrecompute = true

    /** 在途采样数上限；0 = 取默认的两批在途（与第一批的 `PreparationQueue` 同深度）。 */
    @Volatile var reverseInFlight = 0

    /**
     * F15：已经有会话在出帧时，一次 pump 里的预算与预读不许跨过下一拍。
     * 关掉它回到「固定 8 ms 预算」。
     */
    @Volatile var yieldWorkBeforeBeat = true

    /**
     * 研究第 10 节：首帧不等节拍。粒子层是独立 SurfaceView，首帧一备好就直接合成并交换，
     * 呈现时刻写「尽快」；它要替掉的那一拍随后被吃掉，等价于把一拍提前而不是多出一帧。
     *
     * **默认关闭：第二批量不出收益，按本专项的纪律不留「理论上更快」的改动。** 它的上限
     * 本来就只有一拍（16.7 ms），而第三批的 [beatDuringPreparation] 已经让节拍在准备期间
     * 就跑着，实际能省的更少；魅族三个左滑场景 + 两个弹窗场景的「请求 → 首帧呈现」在开与
     * 关之间全落在单轮噪声里（±20–40 ms）。实现与那条「被替掉的一拍必须真的吃掉」的教训
     * 留在代码里（见 `ParticleGl.presentFirstFrames`），换设备或换路径时可以直接开起来复测。
     */
    @Volatile var immediateFirstFrame = false

    /**
     * 第三批：节拍按 **vsync 计数**出拍，目标呈现时刻直接取系统给的预期呈现时刻。
     *
     * 此前是「起点 + k × 16 666 666 ns」的自由计时。魅族 20 Infinity 触摸期面板跑 120.8 Hz、
     * 一个 vsync 只有 **8.28 ms**，两个是 16.56 ms，而我们每拍加 16.67 ms——每拍多 0.107 ms，
     * 目标相对真实 vsync 持续漂移；再加上目标写的是**过去**的时刻（= 尽快呈现），
     * 一帧早到就挤到上一帧后面 8.28 ms 处，晚到就退一个 vsync。第二批实测魅族左滑的呈现间隔
     * 里有 15% 是 8.28 ms、11% 是 33 ms，均值看着还行、看起来却一直在抖。
     *
     * 关掉它回到自由计时（本批矩阵的「修前」列）。
     */
    @Volatile var beatPhaseLock = true

    /**
     * E12 的调参口（**V 级，默认 0 = 现状**）：把微片尺寸按 dp 定死，而不是按像素分格、
     * 顶到 `max_cells` 为止。
     *
     * 现状是 `cell = max(rules.cell, sqrt(面积 × budget / max_cells))`——高 dpi 手机上微片的
     * 物理尺寸比平板小、数量顶到上限。这里给出「不小于 [microflakeCellDp] 个 dp」这一条下限，
     * `max_cells` 那条上限仍然生效。只供第三批的受控对照用；改它会改画面，必须用户看图定夺。
     */
    @Volatile var microflakeCellDp = 0f

    /**
     * C7 的调参口（**V 级，默认 100 = 现状**）：压力投影的 Jacobi 迭代数，也就是设置页里的
     * 「流动精细度」。**0 表示整段压力更新都不跑**（见 `ParticleMicroflakeRenderer` 的
     * `skipPressure`），修正场保持构造时清空的零场。
     */
    @Volatile var jacobiIterations = 100

    /**
     * 把设置页里选的四项推进来。[ParticleTuning] 在进程启动与每次改动之后各调一次。
     *
     * debug 的 A/B 配置文件写过同名键时**以文件为准**：那个文件是用来在同一台设备上复测
     * 某一档的，被用户设置盖掉就白跑了。[attach] 先于 `Application.onCreate` 执行
     * （它由 debug 专用的 ContentProvider 调用），所以这里只需要看一眼标记。
     */
    fun applyUserTuning(cellDp: Float, iterations: Int, colorStandard: Boolean, autoTune: Boolean) {
        if (!cellDpFromFile) microflakeCellDp = cellDp.coerceIn(0f, 12f)
        if (!jacobiFromFile) jacobiIterations = iterations.coerceIn(0, 200)
        if (!colorPrecisionFromFile) {
            phase2Defaults = if (colorStandard) phase2Defaults or P2_SRGB8_ACCUMULATION
                else phase2Defaults and P2_SRGB8_ACCUMULATION.inv()
        }
        if (!autoDowngradeFromFile) autoDowngrade = autoTune
    }

    @Volatile private var cellDpFromFile = false

    @Volatile private var jacobiFromFile = false

    @Volatile private var colorPrecisionFromFile = false

    @Volatile private var autoDowngradeFromFile = false

    /**
     * 受控对照临时切这两个值时用；**只作用于调用它的那条线程**。
     *
     * 不能改进程级的那两个字段：同一时刻常驻 GL 线程上的创建页提前预算也在建材、也在跑压力
     * 更新，还会把配方写进缓存键——第五批的 `withBaselineGpu` 正是栽在这一条上。读点因此一律
     * 走 [microflakeCellDpNow] / [jacobiIterationsNow]：先看本线程有没有标记，没有才取设置值。
     */
    fun <T> withOptions(cellDp: Float, iterations: Int, action: () -> T): T {
        val previousCell = cellOverride.get()
        val previousIterations = iterationsOverride.get()
        cellOverride.set(cellDp)
        iterationsOverride.set(iterations)
        check(microflakeCellDpNow == cellDp && jacobiIterationsNow == iterations) { "调参口没切过去" }
        return try {
            action()
        } finally {
            cellOverride.set(previousCell)
            iterationsOverride.set(previousIterations)
            check(microflakeCellDpNow == (previousCell ?: microflakeCellDp) &&
                jacobiIterationsNow == (previousIterations ?: jacobiIterations)) { "调参口没恢复" }
        }
    }

    private val cellOverride = ThreadLocal<Float>()
    private val iterationsOverride = ThreadLocal<Int>()

    /** 建材那一侧的读点。 */
    val microflakeCellDpNow: Float get() = cellOverride.get() ?: microflakeCellDp

    /** 压力投影那一侧的读点。 */
    val jacobiIterationsNow: Int get() = iterationsOverride.get() ?: jacobiIterations

    // 同批评估过的「把上一帧的 GPU 栅栏挪到两拍之间轮询」已删除：节拍级取证里
    // `awaitFence` 的实测等待是 **0.02 ms**（魅族三个左滑场景的 p50/p90/max 全在 0.07 以内），
    // 「在途只留一帧把提交串成一条链」这条假设不成立，改了也没有可测收益。

    /**
     * 离屏 A/B 与 G0/G1 对照用：在**当前线程**上只让 [enabled] 里的那几项生效，其余按回
     * 改动前的路径。与 [withBaselineGpu] 分开是因为后者会连第一、二批那几项一起关掉
     * （比如 2.4 的首生前跳过），量出来的差值就不是本批的了。
     *
     * 传 0 即整批关闭，传 [PER_FRAME_ALL] 即整批打开（也是默认行为）。
     */
    fun <T> withPerFrame(enabled: Int, action: () -> T): T {
        val previous = perFrame.get()
        perFrame.set(PER_FRAME_ALL and enabled.inv())
        return try { action() } finally { perFrame.set(previous) }
    }

    /** 整批关闭。 */
    fun <T> withPerFrameBaseline(action: () -> T): T = withPerFrame(0, action)

    private val perFrame = ThreadLocal<Int>()

    private fun perFrameOn(item: Int): Boolean {
        if (baselineGpu) return false
        return (perFrame.get() ?: 0) and item == 0
    }

    // 2.5（四步积分合并进一次 dispatch）只做了一次性诊断变体，测完已删除：
    // 61 帧散列在两台设备上都不一致（消散从第 6–7 帧起、出现从第 4–5 帧起），
    // 预计算收益只有 OPD2515 约 5–7 ms、SM-S9180 约 8 ms，两条门槛都不满足。

    // ---- 逐帧成本专项 第二阶段（2026-09-18）：三家 GPU 与交互延迟 ----
    //
    // 每一项都是一位，默认值在 [phase2Defaults] 里。与第一批那套 [withPerFrame] 的区别是
    // 这里要能**两个方向**临时切换：批 D 的几项默认关，离屏对照要把它们临时打开；批 A 的
    // 几项默认开，对照要把它们临时关掉。所以用「强制开」与「强制关」两张线程局部掩码。
    //
    // 着色器侧的项都通过 `program(defines=)` 走独立变体，两套变体在 `shared.compiled` 里共存。

    /** U1：compute→compute 的屏障只发 `GL_SHADER_STORAGE_BARRIER_BIT`。 */
    const val P2_NARROW_BARRIERS = 1 shl 0

    /** U6：`pressure-batched.comp` 的散度与占据度移出 shared（16 → 8 KiB）。 */
    const val P2_SHARED_HALF = 1 shl 1

    /** M1：非 Adreno 上一维核用 64 线程、二维核用 8×8。 */
    const val P2_MALI_LOCAL_SIZE = 1 shl 2

    /** U2：流场与置信度合成一张 RGBA16F 三维纹理，`field_cell` 取样 2 → 1。 */
    const val P2_MERGED_FIELD = 1 shl 3

    /** U2b：三维采样器声明成 `mediump`。 */
    const val P2_MEDIUMP_SAMPLER = 1 shl 4

    /** U3：`peel_field` RGBA32F → RG16F，`sample_peel` 4 点 → 一次硬件双线性。 */
    const val P2_PEEL_FIELD_HALF = 1 shl 5

    /** U5：`material.vert` / `material.frag` 的 varying 除 `uv` 外改 `mediump`。 */
    const val P2_MEDIUMP_VARYING = 1 shl 6

    /** U4：片元里的逐片常量下沉到 `draw-record.comp`。 */
    const val P2_FLAKE_CONSTANTS = 1 shl 7

    /** U10a：绘制只用一个实例化顶点缓冲（取消 location 4/5 那一路）。 */
    const val P2_SINGLE_INSTANCE_BUFFER = 1 shl 8

    /** A1（本阶段编号）：`curl()` 与 `peel-blur` 的 `vec4` 改 `mediump`。 */
    const val P2_MEDIUMP_ALU = 1 shl 9

    /** M2：活动区域接到逐次 Jacobi 路径。 */
    const val P2_REGION_PLAIN_JACOBI = 1 shl 10

    /** M3：非 Adreno 上压力求解改用逐次 Jacobi（无 shared）。 */
    const val P2_PLAIN_JACOBI_ON_MALI = 1 shl 11

    /** M4：`peel-splat.comp` 的整网格清零改成只清活动区域。 */
    const val P2_CLEAR_ACTIVE_REGION = 1 shl 12

    /**
     * U7 / 归因 B1：累积目标 RGBA16F → SRGB8_ALPHA8，也就是设置页里「颜色精度」的「标准」档。
     * **默认关**（= 高精度的 16 位浮点，与加入本设置之前逐位相同）。
     */
    const val P2_SRGB8_ACCUMULATION = 1 shl 13

    /** U8：splat 双线性 → 最近格。**V 级，默认关。** */
    const val P2_NEAREST_SPLAT = 1 shl 14

    /** U10：`sample_field` 改硬件三线性。**V 级，默认关。** */
    const val P2_HARDWARE_TRILINEAR = 1 shl 15

    /** 归因专用：`sample_field` 只取一个格子、不插值（画面必然错，只量墙钟）。 */
    const val P2_PROBE_SINGLE_CELL = 1 shl 16

    /**
     * 本阶段的默认配置 = 发布配置。
     *
     * 采纳的五项都是两台真机各自跑过逐项 A/B、**两台都不劣化**才收的
     * （数字见 `per-frame-huawei-attrib-2026-09-18.md` 第 5 节）：
     *
     * | 项 | 华为 Maleoon 910 | 魅族 Adreno 740 | G |
     * | --- | --- | --- | --- |
     * | [P2_NARROW_BARRIERS] 屏障位收窄 | 消散 0.901 | 消散 0.970 | G0（两台逐位相同） |
     * | [P2_SHARED_HALF] shared 16 → 8 KiB | 0.994（中性） | 消散 0.865、预读 0.793 | G0 |
     * | [P2_MALI_LOCAL_SIZE] 非 Adreno 的工作组变体 | 消散 0.885 | 不走这条变体 | G0 |
     * | [P2_CLEAR_ACTIVE_REGION] 只清活动区域 | 消散 0.929 | 1.004（中性） | G0 |
     * | [P2_FLAKE_CONSTANTS] 片元逐片量下沉 | 出现 0.814、消散 0.953 | 0.966 / 0.978 | G1（两台实测逐位相同） |
     *
     * 否决的：U2 / U2b / U3 / U10（单格取样探针证明三维取样不是瓶颈）、U5 与 U2b
     * （驱动忽略 `mediump`，画面逐位相同）、A1（`mediump` 被接受但改画面、只省 2–3%）、
     * U10a（第五段记录比省下的属性流更贵）、M2 / M3（逐次 Jacobi 在 Maleoon 上慢 3 倍）。
     * V 级三项（U7 / U8 / U10）与归因探针默认关，等用户看图裁定。
     */
    @Volatile var phase2Defaults = P2_NARROW_BARRIERS or P2_SHARED_HALF or P2_MALI_LOCAL_SIZE or
        P2_CLEAR_ACTIVE_REGION or P2_FLAKE_CONSTANTS

    /** 每一位对应的 JSON 键名；[attach] 与调试脚本共用。 */
    val PHASE2_KEYS: List<Pair<String, Int>> = listOf(
        "narrowBarriers" to P2_NARROW_BARRIERS,
        "sharedHalf" to P2_SHARED_HALF,
        "maliLocalSize" to P2_MALI_LOCAL_SIZE,
        "mergedField" to P2_MERGED_FIELD,
        "mediumpSampler" to P2_MEDIUMP_SAMPLER,
        "peelFieldHalf" to P2_PEEL_FIELD_HALF,
        "mediumpVarying" to P2_MEDIUMP_VARYING,
        "flakeConstants" to P2_FLAKE_CONSTANTS,
        "singleInstanceBuffer" to P2_SINGLE_INSTANCE_BUFFER,
        "mediumpAlu" to P2_MEDIUMP_ALU,
        "regionPlainJacobi" to P2_REGION_PLAIN_JACOBI,
        "plainJacobiOnMali" to P2_PLAIN_JACOBI_ON_MALI,
        "clearActiveRegion" to P2_CLEAR_ACTIVE_REGION,
        "srgb8Accumulation" to P2_SRGB8_ACCUMULATION,
        "nearestSplat" to P2_NEAREST_SPLAT,
        "hardwareTrilinear" to P2_HARDWARE_TRILINEAR,
        "probeSingleCell" to P2_PROBE_SINGLE_CELL)

    /** 取值点：先看本线程的强制开 / 强制关，都没有才取默认。 */
    fun phase2(item: Int): Boolean {
        if (baselineGpu) return false
        if ((phase2Force.get() ?: 0) and item != 0) return true
        if ((phase2Suppress.get() ?: 0) and item != 0) return false
        return phase2Defaults and item != 0
    }

    /** 把当前线程看到的整张掩码取一份快照；渲染器在准备管线时读一次，之后不再变。 */
    fun phase2Snapshot(): Int {
        var mask = 0
        for ((_, bit) in PHASE2_KEYS) if (phase2(bit)) mask = mask or bit
        return mask
    }

    /**
     * 离屏对照：**只作用于调用它的那条线程**（`dont-touch-process-wide-state`，
     * 第五批 `withBaselineGpu` 的教训）。切过去与恢复都要读回来断言
     * （`ab-restore-must-be-verified`，第四批 `setLanes(0)` 的教训）。
     */
    fun <T> withPhase2(on: Int = 0, off: Int = 0, action: () -> T): T {
        val previousOn = phase2Force.get()
        val previousOff = phase2Suppress.get()
        phase2Force.set(on)
        phase2Suppress.set(off)
        for ((_, bit) in PHASE2_KEYS) {
            if (on and bit != 0) check(phase2(bit)) { "第二阶段开关没切开：$bit" }
            if (off and bit != 0) check(!phase2(bit)) { "第二阶段开关没切关：$bit" }
        }
        return try {
            action()
        } finally {
            phase2Force.set(previousOn)
            phase2Suppress.set(previousOff)
            for ((_, bit) in PHASE2_KEYS) {
                val expected = ((previousOn ?: 0) and bit != 0) ||
                    ((previousOff ?: 0) and bit == 0 && phase2Defaults and bit != 0)
                check(phase2(bit) == expected) { "第二阶段开关没恢复：$bit" }
            }
        }
    }

    private val phase2Force = ThreadLocal<Int>()
    private val phase2Suppress = ThreadLocal<Int>()

    /**
     * L2：左滑的建材在 `ACTION_DOWN` 就派出去，不等 `ItemTouchHelper` 判出左滑。
     * 判定没来就丢弃（代价是「按下但没左滑」时白建一次材料）。
     */
    @Volatile var swipeMaterialsOnDown = true

    /** L3：常驻 GL 线程与材料工作线程放进一个 ADPF hint session。 */
    @Volatile var performanceHint = true

    /** L4：离屏抓图按内容包围盒而不是整窗口尺寸。 */
    @Volatile var snapshotContentBounds = true

    /**
     * 按实测帧率自动降档（设置页里的「性能自动调节」）。**默认关**，由
     * [ParticleTuning] 从设置里推进来。规则本身是纯逻辑，见 [ParticleAutoDowngrade]，
     * JVM 单测覆盖。
     */
    @Volatile var autoDowngrade = false

    @Volatile private var loaded = false

    /** 由 debug 专用 ContentProvider 在进程启动时调用；release 包没有调用点。 */
    fun attach(filesDir: File) {
        if (!BuildConfig.DEBUG || loaded) return
        loaded = true
        val file = File(filesDir, CONFIG_NAME)
        if (!file.isFile) return
        val config = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        maskOnMaterialThread = config.optBoolean("maskOnMaterialThread", maskOnMaterialThread)
        overlayInDecorOverlay = config.optBoolean("overlayInDecorOverlay", overlayInDecorOverlay)
        deferRecordDialogInit = config.optBoolean("deferRecordDialogInit", deferRecordDialogInit)
        defaultPriorityPreparation = config.optBoolean("defaultPriorityPreparation", defaultPriorityPreparation)
        jacobiLargeTileSetting = config.optBoolean("jacobiLargeTile", jacobiLargeTileSetting)
        jacobiLargeBatch = config.optInt("jacobiLargeBatch", jacobiLargeBatch).coerceIn(1, 16)
        historyInIntegrationSetting = config.optBoolean("historyInIntegration", historyInIntegrationSetting)
        skipBeforeFirstBirthSetting = config.optBoolean("skipBeforeFirstBirth", skipBeforeFirstBirthSetting)
        gestureCreepToFinger = config.optBoolean("gestureCreepToFinger", gestureCreepToFinger)
        creationAppearanceTouch =
            config.optInt("creationAppearanceTouch", creationAppearanceTouch).coerceIn(TOUCH_OFF, TOUCH_FULL)
        creationAppearanceSkipEmptyTail =
            config.optBoolean("creationAppearanceSkipEmptyTail", creationAppearanceSkipEmptyTail)
        dialogAppearanceSkipEmptyTail =
            config.optBoolean("dialogAppearanceSkipEmptyTail", dialogAppearanceSkipEmptyTail)
        panelShadowAtomicHandoff =
            config.optBoolean("panelShadowAtomicHandoff", panelShadowAtomicHandoff)
        // 旧键保留：第三批的 A/B 配置文件里写的是 creationAppearanceAliveRatio。
        appearanceAliveRatio =
            config.optDouble("appearanceAliveRatio",
                config.optDouble("creationAppearanceAliveRatio", appearanceAliveRatio.toDouble()))
                .toFloat().coerceIn(0f, .9f)
        creationPrewarm = config.optBoolean("creationPrewarm", creationPrewarm)
        creationPrewarmIdleMs =
            config.optLong("creationPrewarmIdleMs", creationPrewarmIdleMs).coerceIn(0L, 10_000L)
        primeParticleSurface = config.optBoolean("primeParticleSurface", primeParticleSurface)
        beatDuringPreparation = config.optBoolean("beatDuringPreparation", beatDuringPreparation)
        materialLanes = config.optInt("materialLanes", materialLanes).coerceIn(0, 16)
        atrace = config.optBoolean("atrace", atrace)
        yieldPrecomputeDuringFeedback =
            config.optBoolean("yieldPrecomputeDuringFeedback", yieldPrecomputeDuringFeedback)
        rippleHighFrameRate = config.optBoolean("rippleHighFrameRate", rippleHighFrameRate)
        drawRecordsSetting = config.optBoolean("drawRecords", drawRecordsSetting)
        pressureActiveRegionSetting =
            config.optBoolean("pressureActiveRegion", pressureActiveRegionSetting)
        backgroundContext = config.optBoolean("backgroundContext", backgroundContext)
        asyncPrecompute = config.optBoolean("asyncPrecompute", asyncPrecompute)
        reverseInFlight = config.optInt("reverseInFlight", reverseInFlight).coerceIn(0, 16)
        yieldWorkBeforeBeat = config.optBoolean("yieldWorkBeforeBeat", yieldWorkBeforeBeat)
        immediateFirstFrame = config.optBoolean("immediateFirstFrame", immediateFirstFrame)
        beatPhaseLock = config.optBoolean("beatPhaseLock", beatPhaseLock)
        cellDpFromFile = config.has("microflakeCellDp")
        jacobiFromFile = config.has("jacobiIterations")
        colorPrecisionFromFile = config.has("srgb8Accumulation")
        autoDowngradeFromFile = config.has("autoDowngrade")
        microflakeCellDp = config.optDouble("microflakeCellDp", microflakeCellDp.toDouble())
            .toFloat().coerceIn(0f, 12f)
        jacobiIterations = config.optInt("jacobiIterations", jacobiIterations).coerceIn(0, 200)
        var mask = phase2Defaults
        for ((key, bit) in PHASE2_KEYS) {
            if (!config.has(key)) continue
            mask = if (config.optBoolean(key)) mask or bit else mask and bit.inv()
        }
        phase2Defaults = mask
        swipeMaterialsOnDown = config.optBoolean("swipeMaterialsOnDown", swipeMaterialsOnDown)
        performanceHint = config.optBoolean("performanceHint", performanceHint)
        snapshotContentBounds = config.optBoolean("snapshotContentBounds", snapshotContentBounds)
        autoDowngrade = config.optBoolean("autoDowngrade", autoDowngrade)
        if (materialLanes > 0) ParticleMaterialNative.setLanes(materialLanes)
    }

    /**
     * 离屏对照用：临时把 GPU 侧各项恢复成改动前的行为。
     *
     * **只作用于调用它的那条线程。** 第五批之前它改的是进程级开关，而 G0 对照跑在自己的
     * 线程上、与常驻 GL 线程上的**提前预算**同时进行：预算那一侧每次 `advance()` 都读
     * [skipBeforeFirstBirth]、`prepareReversePipeline()` 读 [historyInIntegration] 与
     * [jacobiLargeTile]、`ParticleCreationPrewarm.recipe()` 把这三项写进缓存键——对照一进入
     * 基线段，生产路径读到的就是另一套配方。默认配置下三项的基线值与生产值只有
     * [skipBeforeFirstBirth] 不同（而 2.4 本身是逐位恒等式），所以没有画面后果；但缓存键会
     * 因此对不上而白白未命中，A/B 配置下预算还可能编译成基线变体。改成线程局部之后，
     * 对照怎么切都碰不到别的线程。
     */
    fun <T> withBaselineGpu(action: () -> T): T {
        val previous = baseline.get()
        baseline.set(true)
        return try { action() } finally { baseline.set(previous) }
    }

    private val baseline = ThreadLocal<Boolean>()

    private val baselineGpu get() = baseline.get() == true
}
