package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.exp
import kotlin.math.pow

/**
 * `SDR（保留高光层次）` 的高亮压缩曲线（fablesol-video-export D68～D76）。
 *
 * 输入是 FP16 扩展显示线性 Rec.709 里的 `max(R, G, B)`（D76），输出是同一色空间内 `0～1` 的
 * SDR 显示线性值；调用方按 `F(m) / m` 的**共同比例**缩放三个通道，不逐通道求值、不去饱和
 * （D69）。曲线只压缩、不提亮：`F(m) ≤ m` 处处成立。
 *
 * ## 三段
 *
 * ```text
 * m ≤ K            F(m) = m                                    暗部与中间调原样保留（D68）
 * K < m ≤ 1        F(m) = 1 - (1-K)·exp(-(m-K)/(1-K))          固定基础压缩（D71）
 * 1 < m ≤ P        F(m) = T - (T-W)·(1 - (m-1)/(P-1))^p        超白高光（动态映射只动这一段）
 * m > P            F(m) = T                                    平滑落后时的同比例收缩（D69）
 * ```
 *
 * 三段在 `m = K` 与 `m = 1` 处都是 C¹ 连续：第二段在 `K` 处斜率为 1、在 `1` 处斜率恒为
 * `1/e`，第三段按构造在 `1` 处也取 `1/e`（两个分支都是）。因此曲线上没有随帧移动的折点。
 *
 * ## 两个参数
 *
 * - **W = F(1)**，HDR 参考白落到的 SDR 显示线性值。它由 HDR 强度定，全片固定，两种映射方式
 *   共用（D71）。强度 `1.0×` 时 `W = 1`，曲线严格恒等；强度 `9.6×` 时用满 BT.2446 Method B
 *   的标定量——参考白落到 SDR **信号** 90%（D72），即显示线性 `0.8090`。中间按强度线性插值：
 *   D72 只钉了两个端点与"顶部预留连续增加"，插值方式属标定选择，由回归测试固定。
 *   膝点 `K = 1 - e·(1-W)` 由 W 反解，不是独立参数——这样第二段的形状只有一个自由度，
 *   `F(1) = W` 与 `F'(1) = 1/e` 同时成立。
 * - **P = 控制峰值**。`稳定映射` 取 HDR 强度本身（全片固定，不预扫描，D67）；`动态映射` 取
 *   FableSol 超白内容的真实逐帧峰值经 [PeakTracker] 平滑后的值（D73～D75）。
 *
 * `P - 1 ≥ 1 - K` 时 `p > 1`，峰值恰好落到 `T = 1`，用满 SDR 顶部范围；`P` 太靠近 `1` 时
 * 强行拉到 1.0 需要 `p < 1`，那是**凸**曲线，等于在高光段放大对比度——与 D68"只压缩"相悖。
 * 此时改取 `p = 1`，峰值落在 `T = W + (P-1)/e < 1`。两个分支在边界上给出同一条直线，
 * `T` 与整条曲线都随 `P` 连续变化，不会在阈值附近产生亮度跳变。
 */
internal object FableSolExportSdrToneMap {

    /** BT.2446 Method B 的完整标定量：HDR 参考白落到 SDR **信号** 90%（D72）。 */
    const val FULL_CALIBRATION_SIGNAL = 0.90

    /** 上一行换算成显示线性：≈ 0.8090。 */
    val FULL_CALIBRATION_WHITE: Double = bt709InverseOetf(FULL_CALIBRATION_SIGNAL)

    /** 高光峰值上升时压缩控制量的跟进时间常数（D74）。 */
    const val ATTACK_SECONDS = 0.08

    /** 高光峰值下降时的释放时间常数（D74）。 */
    const val RELEASE_SECONDS = 0.80

    /** 强度低于此值时视为没有额外高光，曲线恒等（D64、D72）。 */
    private const val IDENTITY_EPSILON = 1e-4

    private const val E = Math.E

    /**
     * 一条已经解算好的曲线。字段与 `export_present.frag` 的 uniform 一一对应。
     *
     * @param knee `K`，其下完全恒等。
     * @param white `W = F(1)`，HDR 参考白的落点。
     * @param peak `P`，本次的控制峰值。
     * @param exponent `p`，超白段的指数，`≥ 1`。
     * @param target `T = F(P)`，控制峰值的落点，`≤ 1`。
     * @param identity 整条曲线是否恒等；为真时调用方直接跳过色调映射。
     */
    internal data class Curve(
        val knee: Double,
        val white: Double,
        val peak: Double,
        val exponent: Double,
        val target: Double,
        val identity: Boolean
    ) {

        /** 亮度尺度上的曲线求值；调用方以 `scalar(m) / m` 缩放 RGB（D69、D76）。 */
        fun scalar(m: Double): Double {
            if (identity || m <= knee) return m
            val span = 1.0 - knee
            if (m <= 1.0) return 1.0 - span * exp(-(m - knee) / span)
            if (peak <= 1.0) return white
            val u = ((m - 1.0) / (peak - 1.0)).coerceIn(0.0, 1.0)
            return target - (target - white) * (1.0 - u).pow(exponent)
        }

        /**
         * 对一个显示线性 RGB 三元组施加共同增益。
         *
         * 亮度尺度取 `max(R, G, B)`：任何带色高光都不会因为某个通道权重较低而逃过压缩，
         * 而三个通道乘同一个数，色相、饱和度与既有通道比例原样保留（D69、D76）。
         */
        fun apply(rgb: DoubleArray) {
            if (identity) return
            val m = maxOf(rgb[0], maxOf(rgb[1], rgb[2]))
            if (m <= 0.0) return
            val gain = scalar(m) / m
            rgb[0] *= gain
            rgb[1] *= gain
            rgb[2] *= gain
        }
    }

    /**
     * 强度为 `1.0×` 时曲线严格恒等，之后顶部预留随强度线性增加，到 `9.6×` 用满标定量。
     *
     * @return `W = F(1)`。
     */
    fun calibrationWhite(strength: Double): Double {
        val clamped = strength.coerceIn(
            FableSolHdrPolicy.STRENGTH_OFF.toDouble(),
            FableSolHdrPolicy.MAX_STRENGTH.toDouble()
        )
        val span = FableSolHdrPolicy.MAX_STRENGTH - FableSolHdrPolicy.STRENGTH_OFF
        val t = (clamped - FableSolHdrPolicy.STRENGTH_OFF) / span
        return 1.0 - t * (1.0 - FULL_CALIBRATION_WHITE)
    }

    /** 膝点由 W 反解，使第二段同时满足 `F(1) = W` 与 `F'(1) = 1/e`。 */
    fun kneeFor(white: Double): Double = 1.0 - E * (1.0 - white)

    /**
     * @param strength 当前 HDR 高光强度，决定固定的基础压缩量。
     * @param controlPeak 控制峰值：稳定映射传 [strength]，动态映射传平滑后的实测超白峰值。
     */
    fun curveFor(strength: Double, controlPeak: Double): Curve {
        val white = calibrationWhite(strength)
        if (1.0 - white <= IDENTITY_EPSILON) {
            return Curve(1.0, 1.0, 1.0, 1.0, 1.0, identity = true)
        }
        val knee = kneeFor(white)
        val span = 1.0 - knee
        val peak = controlPeak.coerceIn(1.0, strength.coerceAtLeast(1.0))
        val above = peak - 1.0
        // p < 1 会让超白段变成凸曲线，等于放大高光对比度——D68 只允许压缩。够不到 1.0 时
        // 就不够，取 p = 1 的直线，峰值落在 T < 1。
        val exponent = if (above > span) above / span else 1.0
        val target = (white + above / (E * exponent)).coerceAtMost(1.0)
        return Curve(knee, white, peak, exponent, target, identity = false)
    }

    /**
     * 动态映射的控制峰值：快压慢放，第一帧直接按实测值初始化（D74）。
     *
     * 平滑只作用在这一个标量上，不做跨帧画面混合，因此不会产生拖影。
     */
    internal class PeakTracker {

        private var value = Double.NaN

        /** 当前控制峰值；尚未喂过帧时为 `1.0`。 */
        val current: Double get() = if (value.isNaN()) 1.0 else value

        /**
         * @param measuredPeak 本帧 FableSol 内容的真实超白 maxRGB 峰值，已夹在 `1.0～强度`。
         * @param dtSeconds 帧间隔。
         */
        fun next(measuredPeak: Double, dtSeconds: Double): Double {
            if (value.isNaN()) {
                value = measuredPeak
                return value
            }
            val tau = if (measuredPeak > value) ATTACK_SECONDS else RELEASE_SECONDS
            val coefficient = 1.0 - exp(-dtSeconds.coerceAtLeast(0.0) / tau)
            value += (measuredPeak - value) * coefficient
            return value
        }
    }

    /** BT.709 OETF 的反函数；只用来把 D72 的"信号 90%"换算成显示线性。 */
    fun bt709InverseOetf(signal: Double): Double =
        if (signal < 4.5 * 0.018) signal / 4.5 else ((signal + 0.099) / 1.099).pow(1.0 / 0.45)
}
