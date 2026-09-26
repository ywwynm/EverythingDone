package com.ywwynm.everythingdone.taptransfer.ui.particles

import android.view.animation.AnimationUtils

/**
 * 舞台视图的时钟：以动画时间（与 `ValueAnimator` 同源）计秒，并给出本帧与上一帧的间隔。
 *
 * 用动画时间而不是墙钟，是为了在同一帧里与属性动画对齐；间隔封顶，卡顿一帧之后粒子不会
 * 一下子跳出一大截。
 */
class StageClock {

    /** 时钟的起点（`AnimationUtils` 的毫秒，与 `SystemClock.uptimeMillis` 同一个时钟）。 */
    val originMs = AnimationUtils.currentAnimationTimeMillis()
    private var lastS = -1f

    /** 当前时刻（秒）。 */
    var now: Float = 0f
        private set

    /** 本帧与上一帧的间隔（秒）；第一帧按 60 Hz 算。 */
    var dt: Float = DEFAULT_DT
        private set

    fun tick() {
        now = (AnimationUtils.currentAnimationTimeMillis() - originMs) / 1000f
        dt = if (lastS < 0f) DEFAULT_DT else (now - lastS).coerceIn(0f, MAX_DT)
        lastS = now
    }

    /**
     * 拖尾长度的换算：拖尾按 [trailS] 秒的运动长度画，而一帧只走了 [dt] 秒。
     * 帧率越高，一帧的位移越短，乘的倍数就越大，拖尾在任何帧率下看起来一样长。
     */
    fun trailScale(trailS: Float): Float {
        return if (dt <= 1e-4f) 1f else (trailS / dt).coerceIn(1f, MAX_TRAIL_SCALE)
    }

    companion object {
        private const val DEFAULT_DT: Float = 1f / 60f
        private const val MAX_DT: Float = 1f / 20f
        private const val MAX_TRAIL_SCALE: Float = 6f
    }
}
