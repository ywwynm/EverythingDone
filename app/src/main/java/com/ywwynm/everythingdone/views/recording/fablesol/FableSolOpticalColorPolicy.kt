package com.ywwynm.everythingdone.views.recording.fablesol

/** 反射与透射只沿 Thing 身份色到中性白改变，不额外旋转色相。 */
internal object FableSolOpticalColorPolicy {

    fun highlight(base: IntArray, whiteMix: Double): IntArray =
        towardNeutralWhite(base, whiteMix)

    fun thinTransmission(highlight: IntArray): IntArray =
        towardNeutralWhite(highlight, 0.10)

    fun crestVeil(highlight: IntArray): IntArray =
        towardNeutralWhite(highlight, 0.16)

    private fun towardNeutralWhite(base: IntArray, amount: Double): IntArray {
        val source = FableSolColor.rgbToOklab(base)
        val t = amount.coerceIn(0.0, 1.0)
        return FableSolColor.oklabToRgbGamutMapped(doubleArrayOf(
            source[0] * (1.0 - t) + t,
            source[1] * (1.0 - t),
            source[2] * (1.0 - t)
        ))
    }
}
