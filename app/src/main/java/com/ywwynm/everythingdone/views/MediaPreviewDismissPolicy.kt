package com.ywwynm.everythingdone.views

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

/** 与 Android 事件分发分离，距离均为像素，速度为像素／秒。 */
internal object MediaPreviewDismissPolicy {
    fun vertical(dx: Float, dy: Float, slop: Float): Boolean =
        abs(dy) > slop && abs(dy) > abs(dx) * 1.25f

    /** 先消耗图片可平移的距离，只把越过边缘的部分交给返回手势。 */
    fun beyondEdge(dy: Float, top: Float, bottom: Float, height: Float): Float {
        if (bottom - top <= height + 1f) return dy
        return if (dy > 0f) max(0f, dy - max(0f, -top))
        else -max(0f, -dy - max(0f, bottom - height))
    }

    fun progress(dy: Float, height: Float): Float =
        (abs(dy) / max(1f, height * .4f)).coerceIn(0f, 1f)

    fun scale(progress: Float): Float = 1f - .36f * progress.coerceIn(0f, 1f)

    fun shouldDismiss(dy: Float, vx: Float, vy: Float, height: Float, density: Float): Boolean {
        // 明显向回甩动表达取消，即使此前曾越过距离阈值。
        if (vy * sign(dy) < -600f * density) return false
        val distance = (height * .18f).coerceIn(96f * density, 180f * density)
        if (abs(dy) >= distance) return true
        return abs(dy) >= 18f * density && vy * sign(dy) >= 1200f * density &&
            abs(vy) > abs(vx) * 1.25f
    }
}
