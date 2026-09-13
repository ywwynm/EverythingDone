package com.ywwynm.everythingdone.views.particledismiss

/** 出现从下方扇区进入：倒播的原消散方向介于左下和右下之间。 */
internal object ParticleAppearanceDirection {
    fun degrees(unit: Double): Float = (225.0 + 90.0 * unit.coerceIn(0.0, 1.0)).toFloat()
}
