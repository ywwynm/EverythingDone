package com.ywwynm.everythingdone.views

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** 临界阻尼解析解：承接原速度，通过恢复力约束外移与收尾，不截断中途位置。 */
internal object MediaPreviewReturnMotion {
    const val MAX_DURATION_MS = 360L
    private const val BASE_FREQUENCY = 24.0

    class Spring(
        val from: Float,
        val to: Float,
        private val initialVelocity: Float,
        maxExcursion: Float,
        private val positionTolerance: Float,
        private val velocityTolerance: Float
    ) {
        private val delta = from.toDouble() - to
        private val speed = initialVelocity.toDouble()
        private val frequency: Double
        val durationMs: Long

        init {
            require(from.isFinite() && to.isFinite() && initialVelocity.isFinite())
            require(maxExcursion.isFinite() && maxExcursion >= 0f && (maxExcursion > 0f || initialVelocity == 0f))
            require(positionTolerance.isFinite() && positionTolerance > 0f && velocityTolerance.isFinite() && velocityTolerance > 0f)
            fun acceptable(w: Double): Boolean = excursion(w) <= maxExcursion && atRest(MAX_DURATION_MS.toDouble(), w)
            var low = BASE_FREQUENCY
            var high = low
            while (!acceptable(high)) high *= 2.0
            if (high != low) repeat(24) {
                val middle = (low + high) * .5
                if (acceptable(middle)) high = middle else low = middle
            }
            frequency = high
            var begin = 0.0
            var end = MAX_DURATION_MS.toDouble()
            if (atRest(0.0, frequency)) end = 0.0 else repeat(24) {
                val middle = (begin + end) * .5
                if (atRest(middle, frequency)) end = middle else begin = middle
            }
            durationMs = ceil(end).toLong()
        }

        private fun offset(seconds: Double, w: Double): Double =
            (delta + (speed + w * delta) * seconds) * exp(-w * seconds)

        private fun velocity(seconds: Double, w: Double): Double =
            (speed - w * (speed + w * delta) * seconds) * exp(-w * seconds)

        /** 极值在速度为零处；同时约束起点外侧和目标外侧，兼顾朝目标高速甩动。 */
        private fun excursion(w: Double): Double {
            val denominator = w * (speed + w * delta)
            if (denominator == 0.0) return 0.0
            val turn = speed / denominator
            if (turn <= 0.0) return 0.0
            val peak = offset(turn, w)
            return max(0.0, max(peak - max(delta, 0.0), min(delta, 0.0) - peak))
        }

        private fun atRest(elapsedMs: Double, w: Double): Boolean {
            val seconds = elapsedMs / 1000.0
            return abs(offset(seconds, w)) <= positionTolerance && abs(velocity(seconds, w)) <= velocityTolerance
        }

        fun position(elapsedMs: Float): Float = if (elapsedMs <= 0f) from
            else (to + offset(elapsedMs / 1000.0, frequency)).toFloat()

        fun velocity(elapsedMs: Float): Float = if (elapsedMs <= 0f) initialVelocity
            else velocity(elapsedMs / 1000.0, frequency).toFloat()
    }
}
