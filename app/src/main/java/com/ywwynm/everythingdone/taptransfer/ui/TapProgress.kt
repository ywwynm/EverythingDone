package com.ywwynm.everythingdone.taptransfer.ui

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * 把一次传输的真实状态折算成「应该显示到哪」的目标进度，发送方与接收方共用。
 *
 * 实测一次碰一碰的时间几乎全花在接收方入组上（0.6–3 秒，期间没有任何数据在传），之后传数据
 * 很快（普通记事不到 1 秒）。如果只按字节算，前几秒进度一动不动，然后瞬间走完，动画就不成立。
 * 所以把全程拆成两段：
 *
 * - **入组段**：没有真实读数可用，按时间向 [JOIN_SHARE] 渐近，永远到不了；
 * - **数据段**：从连上那一刻的目标值出发，按字节比例走到 [DATA_END]；
 * - 对方确认收到（发送方）或文件写好（接收方）之后才到 1。
 */
class TapProgressModel {

    private var touchedAtMs: Long = -1L
    private var dataBase: Float = 0f
    private var dataFraction: Float = 0f
    private var connected: Boolean = false
    private var finished: Boolean = false

    fun touched(nowMs: Long) {
        touchedAtMs = nowMs
        dataBase = 0f
        dataFraction = 0f
        connected = false
        finished = false
    }

    /** 连上之后数据段从这一刻的目标值起步，显示进度不会因为换段而跳变。 */
    fun connected(nowMs: Long) {
        if (connected) {
            return
        }
        dataBase = joinTarget(nowMs)
        connected = true
    }

    fun progress(done: Long, total: Long) {
        if (total > 0L) {
            dataFraction = (done.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
        }
    }

    fun finished() {
        finished = true
    }

    fun target(nowMs: Long): Float {
        if (finished) {
            return 1f
        }
        if (touchedAtMs < 0L) {
            return 0f
        }
        if (!connected) {
            return joinTarget(nowMs)
        }
        return dataBase + (DATA_END - dataBase) * dataFraction
    }

    private fun joinTarget(nowMs: Long): Float {
        val seconds = (nowMs - touchedAtMs).coerceAtLeast(0L) / 1000f
        return JOIN_SHARE * (1f - exp(-seconds / JOIN_TIME_CONSTANT_S))
    }

    companion object {
        /** 入组段最多占到的份额。 */
        const val JOIN_SHARE: Float = 0.34f

        /** 入组段的时间常数：1.4 秒到 63%，实测多数入组在这之内完成。 */
        const val JOIN_TIME_CONSTANT_S: Float = 1.4f

        /** 数据收齐但还没确认时停在这里，留一小段给「确认」。 */
        const val DATA_END: Float = 0.96f
    }
}

/**
 * 把目标进度变成显示进度。
 *
 * 用户的要求是「跟着真实进度走，但不能匀速」（决策 2026-09-24）：
 *
 * - 显示值用临界阻尼的弹簧追目标：从静止起步（缓入），接近目标时减速（缓出），目标跳变时平滑
 *   追上，不会越过目标；
 * - 再用一条最短时长的缓入缓出曲线封顶：目标一下子到 1（很小的记事、或者模拟），也至少按
 *   [minDurationS] 播完，不会一闪而过；
 * - 显示值只增不减。
 *
 * 弹簧用解析解逐帧推进，帧间隔再大也稳定。
 */
class ProgressSmoother(
    private val minDurationS: Float = DEFAULT_MIN_DURATION_S,
    private val omega: Float = DEFAULT_OMEGA
) {

    var value: Float = 0f
        private set

    private var velocity: Float = 0f
    private var elapsedS: Float = 0f

    val isFinished: Boolean
        get() = value >= 1f

    fun reset() {
        value = 0f
        velocity = 0f
        elapsedS = 0f
    }

    /**
     * @param target 目标进度，[0, 1]。
     * @param dtS 距上一帧的秒数。
     * @return 新的显示进度。
     */
    fun step(target: Float, dtS: Float): Float {
        if (value >= 1f) {
            return 1f
        }
        val dt = dtS.coerceIn(0f, MAX_STEP_S)
        elapsedS += dt
        val goal = target.coerceIn(0f, 1f)

        // 临界阻尼弹簧 x'' = -ω²x - 2ωx' 的解析解，x 为到目标的偏差
        val x0 = value - goal
        val v0 = velocity
        val decay = exp(-omega * dt)
        val c = v0 + omega * x0
        var next = goal + (x0 + c * dt) * decay
        var nextVelocity = (v0 - omega * c * dt) * decay

        // 最短时长封顶：显示值不能快过这条缓入缓出曲线
        val capT = elapsedS / minDurationS
        val cap = easeInOutCubic(capT)
        if (next > cap) {
            next = cap
            nextVelocity = min(nextVelocity, easeInOutCubicSlope(capT) / minDurationS)
        }
        if (next < value) {
            // 只增不减：目标不会后退，这里只是挡住数值误差
            next = value
            nextVelocity = max(0f, nextVelocity)
        }
        if (goal >= 1f && next >= FINISH_EPSILON) {
            next = 1f
            nextVelocity = 0f
        }
        value = next
        velocity = nextVelocity
        return value
    }

    companion object {
        const val DEFAULT_MIN_DURATION_S: Float = 1.1f
        const val DEFAULT_OMEGA: Float = 8f

        /** 单帧最多推进这么多秒：卡顿一帧之后不会跳一大截。 */
        private const val MAX_STEP_S: Float = 0.05f

        /**
         * 目标到 1 之后，显示值到这里就直接收尾：临界阻尼的最后一截逼近得很慢，
         * 剩下 1% 的格子一次溶解完看不出跳变，却能省掉约 0.2 秒的拖尾。
         */
        private const val FINISH_EPSILON: Float = 0.99f

        @JvmStatic
        fun easeInOutCubic(t: Float): Float {
            val x = t.coerceIn(0f, 1f)
            return if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).let { it * it * it } / 2f
        }

        /** [easeInOutCubic] 对 t 的导数。 */
        @JvmStatic
        fun easeInOutCubicSlope(t: Float): Float {
            if (t <= 0f || t >= 1f) {
                return 0f
            }
            return if (t < 0.5f) 12f * t * t else 3f * (-2f * t + 2f).let { it * it }
        }
    }
}
