package com.ywwynm.everythingdone.views.particledismiss

import kotlin.random.Random

/** 无真实定向输入的消散，每次等概率选择左上或正上；一次动画只取一次。 */
internal object ParticleDismissDirection {
    /** 模型角度：向右为 0°，向上为 90°。 */
    fun randomDegrees(): Float = if (Random.nextBoolean()) 135f else 90f

    /** Android 屏幕坐标的 y 轴向下。 */
    fun randomRadians(): Float = Math.toRadians(-randomDegrees().toDouble()).toFloat()
}
