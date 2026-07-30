package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * ST 2094-40 Profile B 的基础 OOTF：**膝点 + 10 阶贝塞尔肩部**
 * （fablesol-video-export D111、D113～D122、D177）。
 *
 * ### 载荷语义
 *
 * ```
 * s = 线性归一化亮度 / S        （S = 场景 V8，失效时回退场景 MaxSCL）
 * s ≤ Kx :  F(s) = s · Ky/Kx                                   线性段
 * s > Kx :  t = (s−Kx)/(1−Kx)
 *           B(t) = Σ C(10,i)·t^i·(1−t)^(10−i)·P[i]，P[0]=0、P[10]=1、P[1..9]=anchors
 *           F(s) = Ky + (1−Ky)·B(t)                            肩部
 * 输出绝对亮度 = F(s) · T       （T = 参考显示峰值）
 * ```
 *
 * 两个轴的归一化基准不同：横轴按场景源峰值 `S`，纵轴按参考显示峰值 `T`。所以"绝对亮度
 * 不变"对应的归一化斜率是 `S/T` 而不是 1；膝点处 `Ky/Kx = S/T` 恒成立，因此膝点以下
 * 严格是 identity（D119）。
 *
 * ST 2094-40 §8.7.4 明确规定 `s = 1` 可以对应场景最后一个 DistributionMaxRGB 分位或非零
 * MaxSCL；MDCV 母版峰值不在这两个选项里。D176 把横轴改成母版峰值，是在“每帧误当一个场景”
 * 前提下压住跳变的补丁，D177 已将其取代：整段连续动画只生成一个场景，`S` 因而天然全片恒定，
 * 无需偏离标准坐标语义。
 *
 * ### 全片固定 Profile B（D111）
 *
 * `tone_mapping_flag` 恒为 1，每帧都写 KneePoint 与 9 个 anchors。`S ≤ T` 是**场景级**判定：
 * 场景源峰值未超过参考显示峰值时，全片写入同一条 Case 3 中性曲线（`Kx = 1`、`Ky = S/T`）。此时
 * `F(s) = Ky·s`，还原到绝对亮度正好 `T·F(s) = S·s`。anchors 仍写
 * 合法单调的占位值，以保持全片载荷结构与 Profile 判定稳定。
 *
 * ### 场景统计的职责（D177）
 *
 * V8 决定横轴归一化基准；高光起点分位决定膝点；完整场景 CFD 为肩部拟合提供内容密度。三者
 * 都从同一个连续场景的全部像素与全部帧计算一次，不在编码循环里逐帧变化。
 *
 * ### 肩部形状来自完整 CFD（D117、D120、D121）
 *
 * 不再使用没有标准依据的固定二次缓动。膝点到场景源峰值之间的目标映射在 **PQ 感知域**分配
 * （D118：线性尼特差不近似人眼感知差异），密度为
 * `w = 0.5 × 内容密度 + 0.5 × 均匀先验`（α 固定 0.5，D121）；内容密集的区间获得更多输出
 * 范围，稀疏区间承担更多压缩。
 *
 * ### 时间稳定（D177）
 *
 * 当前 FableSol 导出没有镜头切换，整段动画是一个场景；统计、膝点、anchors 与 FBP 均只求解
 * 一次并在每帧重复同一份载荷。D122 为错误的逐帧场景模型设计的时间平滑不再进入发送端；
 * 将来若出现真正的多场景时间线，应按场景边界重新定义过渡策略，不能复用逐帧呼吸补丁。
 */
internal class FableSolExportHdr10PlusCurve(
    /** 场景源归一化峰值（尼特）：优先 V8，失效时回退 max(MaxSCL)（D113、D177）。 */
    private val sourcePeakNits: Double,
    /** 参考显示峰值（尼特），用户可调（D94）。 */
    private val targetNits: Double = DEFAULT_TARGET_NITS,
    /**
     * 「高光起点」：内部 CFD 的第几个百分位开始算高光。以下原样保留，以上才压缩。
     * 直接查完整逐像素 CFD 的真实百分位，不再对码流九项 V 向量插值（D110）。
     */
    private val highlightStartPercent: Int = DEFAULT_HIGHLIGHT_START_PERCENT
) {

    init {
        // 完整场景统计已经在构造前得出；`S > 10T` 因而是本场景的确定结论（D115、D177）。
        unsupportedReason(sourcePeakNits, targetNits)?.let { throw Unsolvable(it) }
    }

    /**
     * 一帧的曲线参数。
     *
     * @param kneeX 归一化膝点横坐标，写进码流时乘 4095。
     * @param kneeY 归一化膝点纵坐标。
     * @param anchors 9 个贝塞尔中间控制点，写进码流时乘 1023。
     * @param neutral 是否为 Case 3 中性曲线（场景源峰值未超过参考显示峰值，全片一致）。
     * @param requestedKneeNits 用户百分位查询所得的膝点；与 [kneeNits] 不同即发生了下移。
     * @param kneeNits 实际采用的膝点绝对亮度（尼特）。
     */
    class Shape(
        val kneeX: Double,
        val kneeY: Double,
        val anchors: DoubleArray,
        val neutral: Boolean,
        val requestedKneeNits: Double,
        val kneeNits: Double
    ) {

        /** 载荷量化之后的曲线求值；门禁必须针对这一份，而不是量化前的浮点参数（D114）。 */
        fun quantized(): Shape {
            val kx = quantize(kneeX, KNEE_SCALE)
            val ky = quantize(kneeY, KNEE_SCALE)
            return Shape(
                kneeX = kx,
                kneeY = ky,
                anchors = DoubleArray(anchors.size) { quantize(anchors[it], ANCHOR_SCALE) },
                neutral = neutral,
                requestedKneeNits = requestedKneeNits,
                kneeNits = kneeNits
            )
        }

        /** 归一化域求值：`s ∈ [0,1]` → `F(s) ∈ [0,1]`。 */
        fun evaluate(s: Double): Double {
            val x = s.coerceIn(0.0, 1.0)
            if (kneeX <= 0.0) return kneeY
            if (x <= kneeX) return x * kneeY / kneeX
            if (kneeX >= 1.0) return x * kneeY
            val t = ((x - kneeX) / (1.0 - kneeX)).coerceIn(0.0, 1.0)
            return kneeY + (1.0 - kneeY) * bezier(t, anchors)
        }
    }

    /** 曲线无解：当前 HDR10+ 候选失败，不发布截断曲线（D114、D115）。 */
    internal class Unsolvable(val detail: String) : RuntimeException(detail)

    /** `S`，归一化到 PQ 的 10000 尼特上限；当前连续场景内恒定。 */
    private val source =
        (sourcePeakNits / FableSolExportTransfer.PQ_MAX_NITS).coerceIn(1e-6, 1.0)

    /** `T`，同样归一化；全片常量。 */
    private val target = (targetNits / FableSolExportTransfer.PQ_MAX_NITS).coerceIn(1e-6, 1.0)

    /**
     * 膝点的可行上限：`P1 = (S−k)/(10(T−k)) ≤ 1 ⟺ k ≤ (10T−S)/9`（D115）。
     *
     * 两个输入都是场景常量，所以这条上限也是常量。
     */
    private val kneeCeiling = minOf(
        (10.0 * target - source) / 9.0,
        target * MAX_KNEE,
        source * MAX_KNEE
    )

    /** 膝点下限；上限比它还低时以上限为准，避免 `coerceIn` 反过来抛参数错。 */
    private val kneeFloor =
        minOf(maxOf(MIN_KNEE_FRACTION * target, source * MIN_KNEE), kneeCeiling)

    /**
     * 这次导出的曲线是不是全片恒等（`S ≤ T`，D111、D177）。
     *
     * 恒等表示 HDR10+ 动态层不改变画面：产物与同参数 HDR10 的显示结果一致，同时包含合规的
     * 动态元数据。完成信息应客观说明该结果，不得将格式通路可用表述为画质提升。
     */
    val identityMapping: Boolean get() = source <= target

    /**
     * @param stats 当前场景统计；与构造时的 `S` 同源，并决定膝点与肩部内容密度。
     * @throws Unsolvable 量化后仍找不到满足门禁的参数。
     */
    fun shapeForScene(stats: FableSolHdr10PlusStats): Shape {
        // S ≤ T 是场景级判定：全片同一条中性曲线，逐位恒定。
        if (source <= target) return neutralShape()

        val scenePeak = source
        val requestedKnee = stats.percentile(
            highlightStartPercent.coerceIn(
                MIN_HIGHLIGHT_START_PERCENT, MAX_HIGHLIGHT_START_PERCENT
            ).toDouble()
        )
        val kneeWish = requestedKnee
        // 保持参考显示峰值不变，只把膝点下移到最接近用户意图、且可行的最高值（D115）。
        val knee = kneeWish.coerceIn(kneeFloor, kneeCeiling)

        val mapping = targetMapping(stats, knee)
        // 门禁必须针对**最终准备写入载荷的量化值**重新执行，不能只验证量化前的浮点参数
        // （D114）。内容密度拟合出来的肩部偶尔会在膝点之后略微超过 identity 斜率；此时按
        // 固定比例整体向解析缓入形状收敛并重新求值——那是**在可行域内重新分配**，不是对
        // 目标点或 anchors 作逐点裁切（D120 第 5 条）。解析形状本身已知满足全部门禁。
        var reason: String? = null
        for (weight in BLEND_STEPS) {
            val shape = fit(knee, requestedKnee, scenePeak, mapping, weight)
            reason = gateFailure(shape.quantized())
            if (reason == null) return shape
        }
        throw Unsolvable(
            (reason ?: "No feasible nine-anchor curve") +
                " (S=%.5f T=%.5f k=%.5f)".format(source, target, knee)
        )
    }

    /**
     * 全片恒定的 Case 3 中性曲线：`Kx = 1`、`Ky = S/T`。
     *
     * `F(s) = Ky·s`，还原成绝对亮度是 `T·F(s) = S·s = 线性亮度`——真恒等，与 HDR10 的画面
     * 完全一致。Kx、Ky 与 anchors 全部由场景常量决定，量化后逐帧逐位相同。
     */
    private fun neutralShape(): Shape {
        val ky = (source / target).coerceIn(0.0, 1.0)
        val kneeNits = minOf(sourcePeakNits, targetNits)
        return Shape(
            kneeX = 1.0,
            kneeY = ky,
            anchors = DoubleArray(ANCHOR_COUNT) { (it + 1).toDouble() / (ANCHOR_COUNT + 1) },
            neutral = true,
            requestedKneeNits = kneeNits,
            kneeNits = kneeNits
        )
    }

    /**
     * 在固定 PQ 采样网格上求出场景目标映射（D120、D177）。
     *
     * 分配区间是**膝点到场景源峰值**，终点固定映射到参考显示峰值——那正是载荷结构强制的
     * 端点（`F(1) = 1`）。
     *
     * @return 每个网格点对应的目标**输出** PQ 值。
     */
    private fun targetMapping(stats: FableSolHdr10PlusStats, knee: Double): DoubleArray {
        val kneePq = FableSolExportHdr10PlusMetadata.linearToPq(knee)
        val sourcePq = FableSolExportHdr10PlusMetadata.linearToPq(source)
        val targetPq = FableSolExportHdr10PlusMetadata.linearToPq(target)
        val histogram = stats.histogram

        // 每个网格段的输入 PQ 跨度；膝点以下是 identity，不参与分配。
        val inputPq = DoubleArray(GRID_POINTS) { gridInputPq(it) }
        val firstAbove = inputPq.indexOfFirst { it > kneePq }.takeIf { it > 0 }
            ?: return inputPq.copyOf()
        val segments = GRID_POINTS - firstAbove
        if (segments <= 0) return inputPq.copyOf()

        val inputSpan = DoubleArray(segments)
        val desired = DoubleArray(segments)
        var weightTotal = 0.0
        for (index in 0 until segments) {
            val lowPq = if (index == 0) kneePq else inputPq[firstAbove + index - 1]
            val highPq = min(inputPq[firstAbove + index], sourcePq)
            inputSpan[index] = max(highPq - lowPq, 0.0)
            // 内容密度：该段覆盖的真实像素质量（D117、D120）。
            val mass = histogram?.massBetween(
                FableSolExportHdr10PlusMetadata.pqToLinear(lowPq),
                FableSolExportHdr10PlusMetadata.pqToLinear(highPq)
            )?.toDouble() ?: 1.0
            desired[index] = mass
            weightTotal += mass
        }
        // w = 0.5 × 内容密度 + 0.5 × 均匀先验（D121）。均匀先验防止空直方图区间变成长平台、
        // 稀疏银丝与星芒全部落到同一亮度，也避免极少数像素主导整段场景的肩部形状。
        for (index in 0 until segments) {
            val content = if (weightTotal > 0.0) desired[index] / weightTotal else 0.0
            desired[index] = (1.0 - UNIFORM_PRIOR) * content + UNIFORM_PRIOR / segments
        }

        // 按 w 的累计质量分配膝点到参考显示峰值的输出范围，再**带上限**重新分配：
        // 任何一段的输出跨度都不得超过它的输入跨度，否则该段的局部对比度被放大，
        // 与 D119"最多保持原有局部对比度"直接冲突。溢出的部分按比例分给尚未触顶的段——
        // 这是在可行域内重新分配剩余范围，不是对目标点或 anchors 逐点裁切（D120 第 5 条）。
        val outputSpan = DoubleArray(segments)
        var remaining = max(targetPq - kneePq, 0.0)
        var freeWeight = 1.0
        val saturated = BooleanArray(segments)
        repeat(REDISTRIBUTION_PASSES) {
            var overflow = 0.0
            var stillFree = 0.0
            for (index in 0 until segments) {
                if (saturated[index]) continue
                val share = if (freeWeight > 0.0) remaining * desired[index] / freeWeight else 0.0
                if (share > inputSpan[index]) {
                    outputSpan[index] = inputSpan[index]
                    saturated[index] = true
                    overflow += share - inputSpan[index]
                } else {
                    outputSpan[index] = share
                    stillFree += desired[index]
                }
            }
            if (overflow <= 1e-12 || stillFree <= 0.0) return@repeat
            remaining = outputSpan.indices.sumOf {
                if (saturated[it]) 0.0 else outputSpan[it]
            } + overflow
            freeWeight = stillFree
        }

        val mapping = DoubleArray(GRID_POINTS)
        for (index in 0 until firstAbove) mapping[index] = inputPq[index]
        var cumulative = kneePq
        for (index in 0 until segments) {
            cumulative += outputSpan[index]
            // 场景源峰值以上的网格点不在曲线定义域；钉在目标峰值上，使整条采样序列
            // 仍然单调，也与载荷强制的 `F(1) = 1` 端点一致。
            mapping[firstAbove + index] = if (inputPq[firstAbove + index] > sourcePq) {
                targetPq
            } else {
                cumulative
            }
        }
        return mapping
    }

    /**
     * 把场景级 PQ 目标投影回可行域，拟合 KneePoint 与 9 个 anchors。
     *
     * `P1` 由膝点一阶斜率连续条件确定（D114），横轴按场景源峰值归一化后它正好是
     * `(S−k) / (10(T−k))`；`P2～P9` 对目标映射做带约束的最小二乘。单调性用保序回归（PAVA）
     * 强制——控制点单调即可保证贝塞尔单调，这比逐点裁切既稳妥又不制造新的折点。
     */
    private fun fit(
        knee: Double,
        requestedKnee: Double,
        /**
         * 拟合样本的上界：场景源峰值。
         */
        coverage: Double,
        outputPq: DoubleArray,
        /** 向解析缓入形状收敛的比例；0 = 纯内容密度拟合，1 = 纯解析形状。 */
        analyticWeight: Double
    ): Shape {
        // 膝点已经被钳在可行域里，`Ky/Kx = S/T` 因此精确成立——两个分量各自再 coerce 一次
        // 反而会破坏这条恒等关系，膝点以下就不再是 identity 了。
        val kneeX = knee / source
        val kneeY = knee / target
        val degree = ANCHOR_COUNT + 1
        val slopeContinuousP1 = kneeY * (1.0 - kneeX) / (kneeX * (1.0 - kneeY) * degree)
        // 非有限值必须在这里拦住。NaN 通不过任何比较，于是 `gateFailure` 的每一条判定都会
        // "通过"，量化再把 NaN 变成 0——发布出去的是一条把全部亮度映射到零的曲线。
        // 越界判定必须在截断**之前**执行：先 coerceAtMost 再比较会让这道防线永假，膝点
        // 钳制一旦失守，理论值大于 1 的 P1 就被静默截断，膝点斜率连续随之被破坏（D114
        // 明文"截断 P1 不得作为成功路径"）。
        if (!slopeContinuousP1.isFinite() || slopeContinuousP1 > 1.0 + P1_EPSILON) {
            throw Unsolvable(
                "Slope-continuous P1 = $slopeContinuousP1 is outside the field range"
            )
        }
        val firstAnchor = slopeContinuousP1.coerceAtMost(1.0)

        val coveragePq = FableSolExportHdr10PlusMetadata.linearToPq(coverage)
        val samples = ArrayList<DoubleArray>(GRID_POINTS)
        for (index in outputPq.indices) {
            val gridPq = gridInputPq(index)
            if (gridPq > coveragePq) continue
            val inputLinear = FableSolExportHdr10PlusMetadata.pqToLinear(gridPq)
            val s = (inputLinear / source).coerceIn(0.0, 1.0)
            if (s <= kneeX) continue
            val outLinear = FableSolExportHdr10PlusMetadata.pqToLinear(outputPq[index])
            // 只压缩：输出绝对亮度不得高于输入（D119）。
            val y = (min(outLinear, inputLinear) / target).coerceIn(0.0, 1.0)
            val t = ((s - kneeX) / (1.0 - kneeX)).coerceIn(0.0, 1.0)
            val b = ((y - kneeY) / (1.0 - kneeY)).coerceIn(0.0, 1.0)
            samples.add(doubleArrayOf(t, b))
        }

        val anchors = solveAnchors(firstAnchor, samples)
        if (analyticWeight > 0.0) {
            val analytic = analyticAnchors(firstAnchor)
            for (index in anchors.indices) {
                anchors[index] = anchors[index] * (1.0 - analyticWeight) +
                    analytic[index] * analyticWeight
            }
            enforceMonotone(anchors, fromIndex = 0)
        }
        return Shape(
            kneeX = kneeX,
            kneeY = kneeY,
            anchors = anchors,
            neutral = false,
            requestedKneeNits = requestedKnee * FableSolExportTransfer.PQ_MAX_NITS,
            kneeNits = knee * FableSolExportTransfer.PQ_MAX_NITS
        )
    }

    /**
     * 对目标贝塞尔值做最小二乘，`P1` 固定、`P0 = 0`、`P10 = 1`，再用保序回归压成单调。
     *
     * 法方程奇异时退回解析缓入形状：它已知满足全部门禁，只是不带内容密度信息。这是**整体**
     * 换一条可行解，不是对个别点裁切（D120 第 5 条）。
     *
     * **不按样本数量设阈值切换。** 曾经写成"样本少于 10 个就整体退回解析形状"，那是一道硬
     * 开关：样本数跨过阈值时，anchors 会从解析形状一步跳到拟合结果。正则本身承担连续过渡——
     * [RIDGE] 让法方程恒为正定，零样本时
     * 解精确等于解析形状，样本渐多时连续地过渡到拟合结果，中间没有台阶。
     */
    private fun solveAnchors(firstAnchor: Double, samples: List<DoubleArray>): DoubleArray {
        val free = ANCHOR_COUNT - 1
        val fallback = analyticAnchors(firstAnchor)

        val normal = Array(free) { DoubleArray(free) }
        val rhs = DoubleArray(free)
        for (sample in samples) {
            val t = sample[0]
            val residual = sample[1] -
                basis(1, t) * firstAnchor -
                basis(ANCHOR_COUNT + 1, t)
            for (row in 0 until free) {
                val bi = basis(row + 2, t)
                rhs[row] += bi * residual
                for (column in 0 until free) {
                    normal[row][column] += bi * basis(column + 2, t)
                }
            }
        }
        // Tikhonov 正则：法方程在高阶伯恩斯坦基下条件数很差，少量正则既稳住解，也把没有样本
        // 支撑的区间自然拉回解析形状。
        for (row in 0 until free) {
            normal[row][row] += RIDGE
            rhs[row] += RIDGE * fallback[row + 1]
        }
        val solved = solve(normal, rhs) ?: return fallback

        val anchors = DoubleArray(ANCHOR_COUNT)
        anchors[0] = firstAnchor
        for (index in 0 until free) {
            anchors[index + 1] = solved[index].coerceIn(0.0, 1.0)
        }
        // 把解投影到"差分非增"的可行集里：这一族的 `B'` 单调不增，膝点处的绝对斜率恰为 1，
        // 因此整条肩部自动满足 D119 的 `0 ≤ dLout/dLin ≤ 1`。只做保序回归是不够的——单调只
        // 保证曲线不回头，不阻止它在膝点之后加速。P1 固定不动：它是斜率连续的唯一解（D114）。
        enforceSlopeBound(anchors)
        return anchors
    }

    /**
     * 已知可行的肩部形状：控制点**差分非增**。
     *
     * 贝塞尔的导数本身是差分序列的贝塞尔，因此差分非增 ⟹ `B'` 单调不增 ⟹ 肩部处处不比膝点
     * 更陡。膝点处的绝对斜率恰为 1（identity），于是整条曲线自动满足 D119 的
     * `0 ≤ dLout/dLin ≤ 1`。
     *
     * 旧实现用的是"从 `P1` 二次缓入到 1"。它在强压缩下碰巧可行，在**弱压缩**下则不然：
     * `S/T = 1.2` 时 `P1 ≈ 0.123`，而二次缓动的第二个差分是 0.184——比第一个还大，肩部一出
     * 膝点就加速，绝对斜率冲到 1.07。没有门禁时这种曲线会被直接发布。
     *
     * 构造：`Δ_i = max(P1 − i·b, 0)`，二分 `b` 使 `ΣΔ = 1`。`S > T` 时恒有 `P1 > 0.1`，
     * 因此 `10·P1 > 1 ≥ P1`，解必然存在。
     */
    private fun analyticAnchors(firstAnchor: Double): DoubleArray {
        val degree = ANCHOR_COUNT + 1
        val first = firstAnchor.coerceIn(1.0 / degree, 1.0)
        var low = 0.0
        var high = first
        repeat(64) {
            val mid = (low + high) * 0.5
            var total = 0.0
            for (index in 0 until degree) total += max(first - index * mid, 0.0)
            if (total > 1.0) low = mid else high = mid
        }
        val step = (low + high) * 0.5
        val anchors = DoubleArray(ANCHOR_COUNT)
        var cumulative = 0.0
        for (index in 0 until ANCHOR_COUNT) {
            cumulative += max(first - index * step, 0.0)
            anchors[index] = cumulative.coerceIn(0.0, 1.0)
        }
        return anchors
    }

    /**
     * 量化之后重新验证（D114）：单调不下降、膝点连续、绝对斜率落在 `[0, 1]`（D119）。
     */
    private fun gateFailure(shape: Shape): String? {
        if (shape.neutral) return null
        // 归一化斜率换算成绝对斜率的系数：横轴按 S、纵轴按 T，identity 对应 dy/ds = S/T。
        val identitySlope = source / target
        var previousY = shape.evaluate(0.0)
        for (index in 1..GATE_SAMPLES) {
            val s = index.toDouble() / GATE_SAMPLES
            val y = shape.evaluate(s)
            if (y < previousY - MONOTONIC_TOLERANCE) {
                return "Curve decreases at s=%.4f".format(s)
            }
            // 归一化斜率换算成绝对斜率：dLout/dLin = (dy/ds) × T/S。
            val absolute = (y - previousY) * GATE_SAMPLES / identitySlope
            if (absolute > 1.0 + SLOPE_TOLERANCE) {
                return "Local slope %.3f exceeds identity at s=%.4f".format(absolute, s)
            }
            previousY = y
        }
        // 膝点两侧的斜率连续（D114）：两侧都用量化后载荷值的**闭式**斜率——线性段斜率就是
        // Ky/Kx，贝塞尔初始斜率是 B'(0)·(1−Ky)/(1−Kx) = 10·P1·(1−Ky)/(1−Kx)，不需要采样。
        // 曾经用固定步长 1/256 做有限差分：膝点被可行上限压到 kneeX < 1/256 时（场景峰值
        // 接近参考峰值十倍），"膝下"样本区间跨过原点，把斜率连续的合法曲线误判成不连续，
        // 连已知可行的解析形状也被杀掉——可行性预检说可行，正式导出必然失败。
        val kx = shape.kneeX
        val ky = shape.kneeY
        if (kx > 0.0 && kx < 1.0 && ky > 0.0 && ky < 1.0) {
            val below = ky / kx
            val above = (ANCHOR_COUNT + 1) * shape.anchors[0] * (1.0 - ky) / (1.0 - kx)
            // 容差 = 基础感知容差 + 量化传播上界：Kx、Ky 各偏至多半个 1/4095 档、P1 偏至多
            // 半个 1/1023 档时，两侧斜率相对偏移的一阶和。极小膝点下上界自然放宽——那里的
            // 折点位于亚尼特亮度，不构成可感知的尖锐转折（D114 允许"明确、可测试的量化误差
            // 范围"）。P1 的分母下界取 1/10（S > T 时 P1 > 0.1 恒成立），P1 被错误清零时
            // 不会靠放宽容差蒙混过关。
            val quantizationSlack = 0.5 / KNEE_SCALE *
                (1.0 / kx + 1.0 / ky + 1.0 / (1.0 - kx) + 1.0 / (1.0 - ky)) +
                0.5 / ANCHOR_SCALE /
                max(shape.anchors[0], 1.0 / MAX_DYNAMIC_RANGE)
            val relative = kotlin.math.abs(above - below) / max(below, 1e-9)
            if (relative > KNEE_SLOPE_TOLERANCE + quantizationSlack) {
                return "Knee slope discontinuity %.3f (below %.3f, above %.3f)".format(
                    relative, below, above
                )
            }
        }
        return null
    }

    companion object {

        /** 参考显示峰值的默认值（D94）：1000 尼特是 HDR10 的常规母版目标。 */
        const val DEFAULT_TARGET_NITS = 1000.0

        /**
         * 这套场景 `S / T` 组合能不能生成 Profile B 曲线；能则返回 null（D115、D177）。
         *
         * 九 anchor 的斜率连续条件要求 `P1 = (S−k)/(10(T−k)) ≤ 1`，即 `k ≤ (10T−S)/9`。
         * `S > 10T` 时连非负膝点都不存在。这里的 S 必须来自完整场景统计，不能传 MDCV
         * 母版峰值代替。
         *
         * 判据比"十倍"稍严一点点：膝点还必须落在 12 位 KneePoint 的**第一个非零档**上。
         * `k = 0` 在连续域里算解，但它没有线性段，`P1` 的解析式退化成 `0/0`，整条曲线随之
         * 变成 NaN——量化后 anchors 全为 0，画面直接全黑。`S = 10T` 恰好落在这个陷阱里。
         *
         * 返回值仅用于内部诊断；用户界面应通过本地化资源说明最低可行参考峰值及相关参数。
         */
        fun unsupportedReason(sourcePeakNits: Double, targetNits: Double): String? {
            val kneeCeilingNits = (MAX_DYNAMIC_RANGE * targetNits - sourcePeakNits) /
                (MAX_DYNAMIC_RANGE - 1.0)
            if (kneeCeilingNits >= sourcePeakNits * MIN_KNEE) return null
            return "HDR10+ needs a reference display peak of at least " +
                "${minimumTargetNits(sourcePeakNits)} nits: " +
                "the scene source peak %.0f nits is about %d times ".format(
                    sourcePeakNits, MAX_DYNAMIC_RANGE.toInt()
                ) +
                "the selected %.0f nits, and no nine-anchor Profile B curve can keep ".format(
                    targetNits
                ) +
                "luminance below the highlight threshold unchanged. Raise the reference " +
                "display peak, or lower the diffuse white or the HDR strength."
        }

        /**
         * 给定场景源峰值时，能生成曲线的最低参考显示峰值（尼特，向上取整）。
         *
         * 由 `(10T−S)/9 ≥ S/4095` 解出 `T ≥ S(1 + 9/4095)/10`；比朴素的 `S/10` 高约 0.22%，
         * 而那 0.22% 正是"膝点必须能被 12 位表示"的那一档。
         */
        fun minimumTargetNits(sourcePeakNits: Double): Int = ceil(
            sourcePeakNits * (1.0 + (MAX_DYNAMIC_RANGE - 1.0) * MIN_KNEE) / MAX_DYNAMIC_RANGE
        ).toInt()

        /** ST 2094-40 §8.7.4 的横轴基准：优先场景 V8，失效时回退非零 max(MaxSCL)。 */
        fun sourcePeakNits(stats: FableSolHdr10PlusStats): Double {
            val normalized = stats.percentile9998.takeIf { it > 0.0 }
                ?: stats.maxScl.maxOrNull()?.takeIf { it > 0.0 }
                ?: 0.0
            return normalized * FableSolExportTransfer.PQ_MAX_NITS
        }

        /**
         * 九 anchor Profile B 曲线能承担的最大动态范围倍数（D115）。
         *
         * 十阶贝塞尔在膝点处的绝对斜率恰为 1，`P1 ≤ 1` 就把 `S/T` 卡在十倍以内。
         */
        const val MAX_DYNAMIC_RANGE = 10.0

        /** 默认把第 90 百分位当作"水体主体的顶部"，其上算高光。 */
        const val DEFAULT_HIGHLIGHT_START_PERCENT = 90
        const val MIN_HIGHLIGHT_START_PERCENT = 50
        const val MAX_HIGHLIGHT_START_PERCENT = 99

        /** 贝塞尔中间控制点个数；`num_bezier_curve_anchors` 是 4 位，最多 15。 */
        const val ANCHOR_COUNT = 9

        /**
         * 场景曲线拟合使用的固定 PQ 采样网格点数。
         *
         * 网格铺满整个 `[0, 1]` PQ 域，而肩部只占其中一段——1949 尼特场景源峰值、195 尼特膝点时，
         * 落在膝点与场景源峰值之间的只有约四分之一。点数太少会让拟合因样本不足直接退回解析
         * 形状，内容密度就白算了。128 点在该区间留下约 30 个样本，足够定 8 个自由控制点。
         */
        const val GRID_POINTS = 128

        /** 均匀先验固定 0.5，不按帧自适应、不开放为设置项（D121、D177）。 */
        const val UNIFORM_PRIOR = 0.5

        private const val KNEE_SCALE = 4095
        private const val ANCHOR_SCALE = 1023
        private const val GATE_SAMPLES = 256
        /**
         * 正则强度。
         *
         * 十阶伯恩斯坦基的法方程条件数很差：只有 8 个自由控制点、约 30 个样本时，无正则的
         * 最小二乘会给出**振荡**的控制多边形（差分在上限与近零之间来回跳）。曲线本身仍然
         * 合法，但肩部会带上没有内容依据的起伏。正则把解拉向解析形状——它同时是
         * D120/D121 那条均匀先验在控制点空间里的对应物。
         *
         * 量级要与法方程相称：矩阵元约为 `样本数 × 基函数积` ≈ 0.3，1e-3 等于没加。
         */
        private const val RIDGE = 0.05

        /** 膝点正好落在可行上限时，`P1` 的解析解在浮点下会溢出 1 一个 ulp。 */
        private const val P1_EPSILON = 1e-6

        /** 输出范围触顶后重新分配的轮数；有界且足够收敛。 */
        private const val REDISTRIBUTION_PASSES = 6

        /** 斜率上限投影的轮数。 */
        private const val SLOPE_BOUND_PASSES = 12

        /** 门禁不过时向解析形状收敛的比例阶梯；有界、确定，最后一档必定可行。 */
        private val BLEND_STEPS = doubleArrayOf(0.0, 0.25, 0.5, 0.75, 1.0)

        /**
         * 膝点的钳制范围只受**量化步长**限制，不再是一个凭感觉定的 0.02/0.95。
         *
         * `Kx = k/S` 在高动态范围下本来就该很小（S/T = 9 时可行膝点只有源峰值的 1.2%）。
         * 用 0.02 去托住它会直接破坏 `Ky/Kx = S/T` 这条恒等关系，膝点以下不再是 identity，
         * 斜率连续门禁随之必然失败——量化后 12 位膝点能表示的最小非零值就是 1/4095。
         *
         * 钳制作用在**绝对膝点**上（[kneeFloor]/[kneeCeiling]），不是分别夹 Kx 与 Ky：后者
         * 会让两个分量各自落到不同的比例上，恒等关系当场断掉。
         */
        private const val MIN_KNEE = 1.0 / KNEE_SCALE
        private const val MAX_KNEE = (KNEE_SCALE - 1.0) / KNEE_SCALE

        /** 膝点至少要落在参考显示峰值的这个比例之上，否则主体被压得没有意义。 */
        private const val MIN_KNEE_FRACTION = 0.05

        // 量化步长带来的允许误差；12 位膝点与 10 位 anchors 的台阶都在这个量级之内。
        private const val MONOTONIC_TOLERANCE = 1e-9
        private const val SLOPE_TOLERANCE = 0.06
        private const val KNEE_SLOPE_TOLERANCE = 0.12

        /** 伯恩斯坦基函数 `C(10, i) · t^i · (1−t)^(10−i)`。 */
        fun basis(index: Int, t: Double): Double {
            val degree = ANCHOR_COUNT + 1
            return binomial(degree, index) *
                Math.pow(t, index.toDouble()) *
                Math.pow(1.0 - t, (degree - index).toDouble())
        }

        /** 贝塞尔求值：`P0 = 0`、`P10 = 1`，中间是 anchors。 */
        fun bezier(t: Double, anchors: DoubleArray): Double {
            var value = basis(ANCHOR_COUNT + 1, t)
            for (index in anchors.indices) {
                value += basis(index + 1, t) * anchors[index]
            }
            return value
        }

        fun quantize(value: Double, scale: Int): Double =
            Math.round(value.coerceIn(0.0, 1.0) * scale).toDouble() / scale

        private fun binomial(n: Int, k: Int): Double {
            if (k < 0 || k > n) return 0.0
            var result = 1.0
            for (index in 0 until k) {
                result = result * (n - index) / (index + 1)
            }
            return result
        }

        /**
         * 保序回归（PAVA）：控制点单调即保证贝塞尔单调，不必逐点裁切。
         *
         * @param fromIndex 从这一项开始压平。`P1` 由膝点斜率连续条件唯一确定（D114），
         *   一旦被 PAVA 合并进相邻区段就不再连续——那正是"膝点处出现折痕"的来源。
         */
        fun enforceMonotone(anchors: DoubleArray, fromIndex: Int = 0) {
            for (index in (fromIndex + 1).coerceAtLeast(1) until anchors.size) {
                if (anchors[index] < anchors[index - 1]) {
                    var start = index
                    var sum = anchors[index]
                    var count = 1
                    while (start > fromIndex + 1 && anchors[start - 1] > sum / count) {
                        start--
                        sum += anchors[start]
                        count++
                    }
                    val mean = sum / count
                    for (position in start..index) anchors[position] = mean
                }
            }
            for (index in anchors.indices) {
                anchors[index] = anchors[index].coerceIn(0.0, 1.0)
            }
            // 第一个自由控制点不得低于 P1，否则整条肩部在起点就往回走。
            for (index in (fromIndex + 1).coerceAtLeast(1) until anchors.size) {
                if (anchors[index] < anchors[index - 1]) anchors[index] = anchors[index - 1]
            }
        }

        /**
         * 投影到「绝对斜率不超过 1」的可行集，`P1` 保持不变。
         *
         * 门禁要的是 `0 ≤ dLout/dLin ≤ 1`。膝点处的绝对斜率恰为 1，对应 `B'(0) = 10·P1`；
         * 而 `B'` 是差分序列的贝塞尔，即差分的凸组合。因此**只要每个差分都不超过第一个
         * 差分**，`B'` 就处处不超过 `B'(0)`，绝对斜率也就处处不超过 1。
         *
         * 这比"差分非增"弱得多——后者会把解直接压成唯一的解析形状，内容密度就白算了。
         * 约束只有三条：`Δ_i ≥ 0`（单调）、`Δ_i ≤ Δ_0`（斜率上限）、`ΣΔ = 1`（端点）。
         * `S > T` 时恒有 `P1 > 0.1`，所以 `10·P1 > 1`，可行集非空。
         */
        fun enforceSlopeBound(anchors: DoubleArray) {
            val count = anchors.size
            val deltas = DoubleArray(count + 1)
            deltas[0] = anchors[0]
            for (index in 1 until count) deltas[index] = anchors[index] - anchors[index - 1]
            deltas[count] = 1.0 - anchors[count - 1]
            val cap = deltas[0]
            val target = 1.0 - cap
            repeat(SLOPE_BOUND_PASSES) {
                var sum = 0.0
                for (index in 1..count) {
                    deltas[index] = deltas[index].coerceIn(0.0, cap)
                    sum += deltas[index]
                }
                val deficit = target - sum
                if (kotlin.math.abs(deficit) < 1e-12) return@repeat
                // 差额按各段**剩余可调空间**分摊，而不是等分：已经贴住上下界的段不再动，
                // 内容密度定下来的相对形状因此尽量保住。
                var room = 0.0
                for (index in 1..count) {
                    room += if (deficit > 0.0) cap - deltas[index] else deltas[index]
                }
                if (room <= 1e-12) return@repeat
                for (index in 1..count) {
                    val share = if (deficit > 0.0) {
                        (cap - deltas[index]) / room
                    } else {
                        deltas[index] / room
                    }
                    deltas[index] += deficit * share
                }
            }
            for (index in 1..count) deltas[index] = deltas[index].coerceIn(0.0, cap)
            var cumulative = 0.0
            for (index in 0 until count) {
                cumulative += deltas[index]
                anchors[index] = cumulative.coerceIn(0.0, 1.0)
            }
        }

        /** 小规模高斯消元；奇异时返回 null，调用方退回解析形状。 */
        fun solve(matrix: Array<DoubleArray>, rhs: DoubleArray): DoubleArray? {
            val size = rhs.size
            val a = Array(size) { row -> matrix[row].copyOf() }
            val b = rhs.copyOf()
            for (column in 0 until size) {
                var pivot = column
                for (row in column + 1 until size) {
                    if (kotlin.math.abs(a[row][column]) > kotlin.math.abs(a[pivot][column])) {
                        pivot = row
                    }
                }
                if (kotlin.math.abs(a[pivot][column]) < 1e-12) return null
                val tempRow = a[column]; a[column] = a[pivot]; a[pivot] = tempRow
                val tempValue = b[column]; b[column] = b[pivot]; b[pivot] = tempValue
                for (row in column + 1 until size) {
                    val factor = a[row][column] / a[column][column]
                    if (factor == 0.0) continue
                    for (inner in column until size) a[row][inner] -= factor * a[column][inner]
                    b[row] -= factor * b[column]
                }
            }
            val result = DoubleArray(size)
            for (row in size - 1 downTo 0) {
                var accumulator = b[row]
                for (column in row + 1 until size) accumulator -= a[row][column] * result[column]
                result[row] = accumulator / a[row][row]
            }
            return result
        }

        /** 固定的**绝对** PQ 输入采样网格；整段场景只在这套坐标上拟合一次。 */
        fun gridInputPq(index: Int): Double = index / (GRID_POINTS - 1).toDouble()
    }
}
