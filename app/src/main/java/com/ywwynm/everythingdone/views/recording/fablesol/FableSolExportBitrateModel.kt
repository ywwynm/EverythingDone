package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.roundToInt

/**
 * 目标码率（VBR）模式的自动码率模型（fablesol-video-export D147）。
 *
 * 旧实现是两个写死的数：`120 fps → 24 Mbps`、`60 fps → 24 × 0.6 = 14.4 Mbps`。它们跟实际
 * 产物没有关系——FableSol 的画布宽度随时钟字形变化，编码器对齐后在 `1024×1472`～`1344×1472`
 * 之间，同一个 24 Mbps 在最窄与最宽画布上给出的每像素码字相差三成；换个编码器族或位深，
 * 差距更大。
 *
 * 因此改为按 D147 的乘积式推导：
 *
 * ```text
 * 自动目标码率 = 实际编码宽 × 实际编码高 × 实际帧率
 *              × 编码器族基础系数
 *              × 位深及 HDR/SDR 信号系数
 * ```
 *
 * 结果再夹进实际编码器的 `bitrateRange`。格式**不**因为携带 HDR10+ 或杜比动态元数据额外
 * 加系数：真正影响压缩负担的是像素率、编码器族、位深和信号特性，而元数据只有几百字节。
 */
internal object FableSolExportBitrateModel {

    /**
     * 系数版本。
     *
     * 改动任何一个系数都必须升级：产物码率看不出它是按哪一版算的，而
     * [FableSolExportBitrateModelTest] 的固定落点用例就是靠这个版本号说明"该改期望值了"。
     */
    const val COEFFICIENT_VERSION = 1

    /**
     * 基准：AVC、8-bit、SDR 的每像素码字（bit/pixel）。
     *
     * 两个锚点定出这个数，两者互相印证：
     *
     * - YouTube 对 1440p60 SDR 上传给出 24 Mbps，像素率 `2560×1440×60 ≈ 2.21e8`，
     *   即 `0.1086 bit/pixel`；
     * - 本项目此前的 120 fps 默认值 24 Mbps 落在最大画布 `1344×1472`（像素率 `2.374e8`）
     *   的 HEVC 10-bit HDR 档上，反解基准为 `0.1142 bit/pixel`。
     *
     * 取 `0.115` 使两者都落在合理位置，且默认档（HEVC 10-bit HDR、120 fps）的自动值与用户
     * 已经用了很久的 24 Mbps 基本持平，不构成体积回归。
     */
    const val BASE_BITS_PER_PIXEL = 0.115

    /**
     * 编码器族基础系数，相对 AVC。
     *
     * 取的是保守值：HEVC 相对 AVC 的公开码率节省常报为 30%～50%，AV1 相对 HEVC 再省
     * 20%～30%。系数往高了取只是多花些体积，往低了取会直接损失画质，因此都靠近区间的
     * 保守端。
     */
    const val FACTOR_AVC = 1.0
    const val FACTOR_HEVC = 0.70
    const val FACTOR_AV1 = 0.60

    /** 10-bit 要多带的精度。 */
    const val FACTOR_TEN_BIT = 1.10

    /**
     * HDR 信号的额外负担。
     *
     * PQ 与 HLG 都把更大的动态范围压进同样的码值域，高光与暗部的梯度都更陡；YouTube 同样
     * 对 HDR 上传给出高于 SDR 的推荐码率（1440p60：30 对 24 Mbps，即 1.25×）。这里取 1.15，
     * 因为其中一部分负担已经由 [FACTOR_TEN_BIT] 承担了。
     */
    const val FACTOR_HDR = 1.15

    /** 夹取下限；再低的目标码率在本项目的画布上没有可用画质。 */
    const val MIN_BITRATE_BPS = 1_000_000

    fun familyFactor(family: FableSolExportCodecFamily): Double = when (family) {
        FableSolExportCodecFamily.HEVC -> FACTOR_HEVC
        FableSolExportCodecFamily.AV1 -> FACTOR_AV1
        FableSolExportCodecFamily.AVC -> FACTOR_AVC
    }

    /** 位深与信号合成的一条系数（D147 把它们算作同一条轴）。 */
    fun signalFactor(tenBit: Boolean, hdr: Boolean): Double =
        (if (tenBit) FACTOR_TEN_BIT else 1.0) * (if (hdr) FACTOR_HDR else 1.0)

    /**
     * 按实际输出参数推导的自动目标码率（bps）。
     *
     * @param widthPx 已完成编码器对齐与 64px 分享兼容对齐的实际编码宽度。
     */
    fun autoBitrateBps(
        widthPx: Int,
        heightPx: Int,
        frameRate: Int,
        family: FableSolExportCodecFamily,
        tenBit: Boolean,
        hdr: Boolean
    ): Int {
        val pixelRate = widthPx.toDouble() * heightPx.toDouble() * frameRate.toDouble()
        val bits = pixelRate * BASE_BITS_PER_PIXEL *
            familyFactor(family) * signalFactor(tenBit, hdr)
        return bits.roundToInt().coerceAtLeast(MIN_BITRATE_BPS)
    }

    /** 用户拖过滑杆之后的绝对目标码率；不再随分辨率或帧率按比例缩放（D147）。 */
    fun customBitrateBps(mbps: Float): Int =
        (mbps * 1_000_000f).roundToInt().coerceAtLeast(MIN_BITRATE_BPS)
}
