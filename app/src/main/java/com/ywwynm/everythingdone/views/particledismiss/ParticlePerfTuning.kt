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
    @Volatile var jacobiLargeTile = false

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
    @Volatile var historyInIntegration = false

    /** 2.4：最早出生时间之前跳过积分与压力更新。 */
    @Volatile var skipBeforeFirstBirth = true

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
     * 保留段内的每一帧与不跳过时逐位相同。只作用于创建页，弹窗出现暂不启用。
     */
    @Volatile var creationAppearanceSkipEmptyTail = true

    /**
     * 2c 的门槛：存活片占比低于它的采样算「几乎是空画面」，从倒放里去掉。
     * 提高它会跳掉更多帧——既让起始段更明显，也按比例省下预计算。
     */
    @Volatile var creationAppearanceAliveRatio = ParticleReversePlan.APPEARANCE_ALIVE_RATIO

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

    // 2.5（四步积分合并进一次 dispatch）只做了一次性诊断变体，测完已删除：
    // 61 帧散列在两台设备上都不一致（消散从第 6–7 帧起、出现从第 4–5 帧起），
    // 预计算收益只有 OPD2515 约 5–7 ms、SM-S9180 约 8 ms，两条门槛都不满足。

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
        jacobiLargeTile = config.optBoolean("jacobiLargeTile", jacobiLargeTile)
        jacobiLargeBatch = config.optInt("jacobiLargeBatch", jacobiLargeBatch).coerceIn(1, 16)
        historyInIntegration = config.optBoolean("historyInIntegration", historyInIntegration)
        skipBeforeFirstBirth = config.optBoolean("skipBeforeFirstBirth", skipBeforeFirstBirth)
        gestureCreepToFinger = config.optBoolean("gestureCreepToFinger", gestureCreepToFinger)
        creationAppearanceTouch =
            config.optInt("creationAppearanceTouch", creationAppearanceTouch).coerceIn(TOUCH_OFF, TOUCH_FULL)
        creationAppearanceSkipEmptyTail =
            config.optBoolean("creationAppearanceSkipEmptyTail", creationAppearanceSkipEmptyTail)
        creationAppearanceAliveRatio =
            config.optDouble("creationAppearanceAliveRatio", creationAppearanceAliveRatio.toDouble())
                .toFloat().coerceIn(0f, .9f)
        primeParticleSurface = config.optBoolean("primeParticleSurface", primeParticleSurface)
        beatDuringPreparation = config.optBoolean("beatDuringPreparation", beatDuringPreparation)
        materialLanes = config.optInt("materialLanes", materialLanes).coerceIn(0, 16)
        if (materialLanes > 0) ParticleMaterialNative.setLanes(materialLanes)
    }

    /** 离屏对照用：临时把 GPU 侧各项恢复成改动前的行为。 */
    fun <T> withBaselineGpu(action: () -> T): T {
        val tile = jacobiLargeTile
        val history = historyInIntegration
        val skip = skipBeforeFirstBirth
        jacobiLargeTile = false
        historyInIntegration = false
        skipBeforeFirstBirth = false
        return try { action() } finally {
            jacobiLargeTile = tile
            historyInIntegration = history
            skipBeforeFirstBirth = skip
        }
    }
}
