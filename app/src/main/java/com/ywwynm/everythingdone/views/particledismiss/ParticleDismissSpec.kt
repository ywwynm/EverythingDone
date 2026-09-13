package com.ywwynm.everythingdone.views.particledismiss

import android.graphics.Bitmap

/** 出现与消散共用的材料、几何和播放输入。 */
internal class ParticleDismissSpec(
    val snapshot: Bitmap,
    /** 快照左上角在动画层（Activity DecorView）内的像素位置。 */
    val originXPx: Float,
    val originYPx: Float,
    /** 按中心至该点求消散方向；出现沿同一轨迹倒序归入面板。 */
    val virtualTouchXPx: Float,
    val virtualTouchYPx: Float,
    val durationScale: Float,
    val hashSeed: Int,
    /** 为 true 时完整倒放当前共同消散模型。 */
    val reverse: Boolean = false,
    /** 当前出现与消散均使用一秒完整过程，另外应用系统动画时长缩放。 */
    val playbackDurationS: Float = ParticleMicroflakeModel.DURATION,
    val requestedAtNanos: Long = System.nanoTime(),
    /** 真实触点到控件边缘的距离，以短边为单位。 */
    val touchGap: Float? = null,
    /** 真实触点在可触摸背景中的相对远近；无触点使用中等强度。 */
    val touchStrength: Float? = null
)
