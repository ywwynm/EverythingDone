package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow

/**
 * ST 2094-40 的色调映射曲线：**膝点 + 贝塞尔肩部**。
 *
 * ### 为什么要有它
 *
 * 用户实测发现杜比视界 8.4 会"高光出现时压暗背景来衬托银丝星芒"，而我们的 HDR10+ 没有这个
 * 表现——因为此前发的元数据里 `tone_mapping_flag = 0`，只给了统计量、没给曲线。播放端拿不到
 * 曲线就只能按静态元数据那一套处理，自然没有逐场景的适配。
 *
 * ### 曲线的语义（照 libplacebo 的 ST2094-40 实现推出，无歧义）
 *
 * ```
 * x = 线性亮度 / 母版峰值        （x 的 1.0 = 我们声明的母版峰值，逐帧不变）
 * x ≤ Kx :  y = x · Ky/Kx                              线性段
 * x > Kx :  t = (x−Kx)/(1−Kx)
 *           B(t) = Σ C(N,p)·t^p·(1−t)^(N−p)·P[p]       P[0]=0，P[N]=1，中间是 anchors
 *           y = Ky + (1−Ky)·B(t)                       肩部
 * 输出亮度 = y · 目标显示峰值
 * ```
 *
 * 注意**两个轴的归一化基准不同**：x 按母版峰值，y 按目标显示峰值。所以"绝对亮度上不做
 * 改变"对应的斜率是 `母版峰值 / 目标峰值`，而不是 1。这一点写错，画面亮度会整体跑偏。
 *
 * ### 我们给的曲线是什么意思
 *
 * 「膝点以下原样保留，膝点以上压缩」——水体本身是主体，银丝与星芒是点缀；真要压，压点缀。
 * 膝点取**这一帧实测的高分位点**（水体主体的顶部），肩部按斜率连续接上去。全帧峰值本来就
 * 装得进目标显示时，膝点直接放到峰值，等于不压。
 *
 * ### 时间平滑是必须的
 *
 * 逐帧算出来的分位点会抖，膝点跟着抖就会让背景亮度一跳一跳（俗称"呼吸"）。所以膝点走
 * **快起慢落**的指数平滑：高光涌上来时迅速让出空间（不然会削顶），高光退去后缓慢回升
 * （不然背景会闪）。统计量本身仍是逐帧实测值，不平滑——平滑的是"我们想怎么映射"这个意图。
 */
internal class FableSolExportHdr10PlusCurve(
    /** 我们声明的母版峰值（尼特）= HDR 强度 × 漫反射白。 */
    private val masteringPeakNits: Double,
    /**
     * 曲线针对的目标显示峰值。**不能写死**——见
     * [FableSolExportHdr10PlusCurve.Companion.targetNitsFor]。
     */
    private val targetNits: Double = DEFAULT_TARGET_NITS,
    /**
     * 「高光起点」：画面亮度分布的第几个百分位算作高光的开始。
     *
     * 它以下是主体、原样保留，以上才压缩。调高 = 更多画面受保护、压缩更晚开始；
     * 调低 = 更早开始压缩，高光之间的层次留得更多。用户可调（`FableSolExportOptions`）。
     */
    private val highlightStartPercent: Int = DEFAULT_HIGHLIGHT_START_PERCENT
) {

    private var smoothedKneeNits = Double.NaN

    class Shape(
        /** 归一化膝点，写进码流时乘 4095。 */
        val kneeX: Double,
        val kneeY: Double,
        /** 贝塞尔中间控制点，写进码流时乘 1023。 */
        val anchors: DoubleArray,
        val targetNits: Double
    )

    /**
     * @param dtSeconds 本帧对应的时间步长（1 / 帧率），用来把平滑时间常数换算成系数。
     */
    fun next(stats: FableSolHdr10PlusStats, dtSeconds: Double): Shape {
        val target = kneeTargetNits(stats)
        smoothedKneeNits = if (smoothedKneeNits.isNaN()) {
            target
        } else {
            // 膝点下降 = 高光涌上来 = 要迅速让出空间；上升 = 高光退去 = 慢慢回来。
            val tau = if (target < smoothedKneeNits) ATTACK_SECONDS else RELEASE_SECONDS
            val coefficient = 1.0 - exp(-dtSeconds.coerceAtLeast(0.0) / tau)
            smoothedKneeNits + (target - smoothedKneeNits) * coefficient
        }
        return shapeFor(smoothedKneeNits)
    }

    private fun kneeTargetNits(stats: FableSolHdr10PlusStats): Double {
        val peak = stats.maxsclNits.maxOrNull() ?: FableSolExportTransfer.SDR_WHITE_NITS
        // 整帧都装得进目标显示时不需要压缩，膝点直接放到峰值。
        if (peak <= targetNits) return peak.coerceAtLeast(FableSolExportTransfer.SDR_WHITE_NITS)
        val diffuseTop = stats.nitsAtPercent(
            highlightStartPercent.coerceIn(MIN_HIGHLIGHT_START_PERCENT, MAX_HIGHLIGHT_START_PERCENT)
        )
        // 上限留出目标范围顶端的一成给点缀，否则肩部无处安放。
        val ceiling = maxOf(
            minOf(targetNits * DIFFUSE_HEADROOM_FRACTION, kneeCeilingNits()),
            FableSolExportTransfer.SDR_WHITE_NITS
        )
        return diffuseTop.coerceIn(FableSolExportTransfer.SDR_WHITE_NITS, ceiling)
    }

    /**
     * 膝点还能放多高，才不至于让肩部退化成一道断崖。
     *
     * 斜率连续解出的第一个控制点是 `P[1] = (M − k) / (N(T − k))`（M 母版峰值、T 目标峰值、
     * k 膝点、N 阶数）。它必须 ≤ 1，否则只能夹到 1——那样所有控制点都变成 1，肩部从
     * `(Kx,Ky)` 几乎垂直冲到顶，膝点以上的一切被压成同一个亮度。
     *
     * 解 `P[1] ≤ 1` 得 `k ≤ (N·T − M)/(N − 1)`。这一条**必须**参与膝点上限，否则漫反射白
     * 一调高（母版峰值随之抬到目标峰值的好几倍）曲线就会悄悄退化成硬钳——而硬钳会让各通道
     * 在不同亮度处撞顶，播放端逐通道处理时就会出现明显的偏色。
     */
    private fun kneeCeilingNits(): Double {
        val degree = ANCHOR_COUNT + 1
        return (degree * targetNits - masteringPeakNits) / (degree - 1)
    }

    private fun shapeFor(kneeNits: Double): Shape {
        val kneeX = (kneeNits / masteringPeakNits).coerceIn(MIN_KNEE, MAX_KNEE)
        val kneeY = (kneeNits / targetNits).coerceIn(MIN_KNEE, MAX_KNEE)
        val degree = ANCHOR_COUNT + 1
        // 斜率连续：线性段斜率 Ky/Kx 必须等于肩部在 t=0 处的导数
        // (1−Ky)/(1−Kx)·N·P[1]，解出 P[1]。接不上就会在膝点看到一道折痕。
        val first = (kneeY * (1.0 - kneeX) / (kneeX * (1.0 - kneeY) * degree))
            .coerceIn(0.0, 1.0)
        val anchors = DoubleArray(ANCHOR_COUNT)
        anchors[0] = first
        for (index in 1 until ANCHOR_COUNT) {
            // 从 P[1] 单调缓入到 1；二次缓动足够平滑，也保证不会回头。
            val position = (degree - (index + 1)).toDouble() / (degree - 1)
            anchors[index] = 1.0 - (1.0 - first) * position.pow(2.0)
        }
        return Shape(kneeX, kneeY, anchors, targetNits)
    }

    companion object {

        /** 读不到屏幕峰值时的目标显示峰值：1000 尼特是 HDR10 的常规母版目标。 */
        const val DEFAULT_TARGET_NITS = 1000.0

        /** 目标峰值至少要是漫反射白的这个倍数，否则高光根本没有落脚的地方。 */
        const val MIN_TARGET_HEADROOM = 2.0

        /**
         * 曲线该针对多亮的显示设备。
         *
         * 写死 1000 是错的：漫反射白调到 800 时，母版峰值会去到 7680，而目标只有 1000——
         * 膝点以上要把 800→7680 压进 800→1000，斜率解出来的第一个控制点远大于 1，只能夹死，
         * 肩部就退化成一道断崖。取屏幕自己声明的峰值，并保证至少是漫反射白的两倍。
         *
         * 上限压在母版峰值：目标比母版还高等于告诉播放端"请把画面提亮"，方向就反了。
         */
        fun targetNitsFor(
            masteringPeakNits: Double,
            whiteNits: Double,
            panelPeakNits: Float?
        ): Double {
            val panel = panelPeakNits?.toDouble() ?: DEFAULT_TARGET_NITS
            val lower = minOf(whiteNits * MIN_TARGET_HEADROOM, masteringPeakNits)
            return panel.coerceIn(lower, masteringPeakNits)
        }

        /** 高光涌上来时让出空间的时间常数；慢了会削顶。 */
        const val ATTACK_SECONDS = 0.08

        /** 高光退去后回升的时间常数；快了背景会闪。 */
        const val RELEASE_SECONDS = 0.80

        /** 默认把第 90 百分位当作"水体主体的顶部"，其上算高光。 */
        const val DEFAULT_HIGHLIGHT_START_PERCENT = 90
        const val MIN_HIGHLIGHT_START_PERCENT = 50
        const val MAX_HIGHLIGHT_START_PERCENT = 99

        /** 膝点最高只放到目标范围的九成，顶端一成留给点缀。 */
        const val DIFFUSE_HEADROOM_FRACTION = 0.9

        /** 贝塞尔中间控制点个数；`num_bezier_curve_anchors` 是 4 位，最多 15。 */
        const val ANCHOR_COUNT = 9

        // 膝点不能贴到 0 或 1：Kx=0 会让线性段斜率发散，Ky=1 会让肩部整段退化成平顶。
        private const val MIN_KNEE = 0.02
        private const val MAX_KNEE = 0.95
    }
}
