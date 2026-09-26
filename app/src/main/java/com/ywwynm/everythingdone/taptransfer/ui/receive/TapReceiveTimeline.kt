package com.ywwynm.everythingdone.taptransfer.ui.receive

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 接收一次的时间线（第三版，设计见 `docs/features/thing-transfer/design-2026-09-25-particle-motion.md`）：
 * 两层粒子共用的倒放时间轴，以及光尘带、天线处波纹跟着走的几个量。与桌面模拟器 `sim_timeline.ReceivePlan`
 * 逐项对应，差别只在「传输进度」：模拟器按时间先慢后快地假设，这里用界面上显示的真实进度。
 *
 * 倒放走到哪儿记作 tau（0 是正向时间轴的终点、颗粒全在天线与四周；1 是拼好）：
 * - 碰上之后随传输进度推进：`scale × 进度^gamma + floor × 秒数`（gamma < 1：起步快，一碰上就有颗粒冒出来）；
 * - 数据到齐之后按指数逼近上限 cap：一直在动、越来越慢，不会先拼出代理卡片；速度在到齐那一刻连续；
 * - 流入开始之后从当时的位置与速度出发，按三次 Hermite 平滑加速，流入时长结束时正好到 1，末速度是
 *   正常速度（1 / 流入时长）。
 *
 * 时间一律是秒（Double，`SystemClock.uptimeMillis` 与 Choreographer 帧时间同一个时钟）。在主线程调用；
 * [Inflow] 是不可变的，可以交给建材线程。
 */
internal class TapReceiveTimeline(
    private val scale: Float,
    private val floor: Float,
    private val cap: Float,
    private val gamma: Float,
    /** 流入时长（秒）：流入开始到拼好。 */
    val appearS: Float
) {

    var touchedS = Double.NaN
        private set
    var completeS = Double.NaN
        private set

    /** 流入开始之后才有：从那一刻的位置与速度出发、到拼好的那一段。 */
    var inflow: Inflow? = null
        private set

    /** 传输期间最近一次推进到的 tau 与速度（每秒）。 */
    private var tau = 0f
    private var rate = 0f
    private var lastS = Double.NaN

    /** 数据到齐那一刻的 tau 与速度。 */
    private var tauC = 0f
    private var rateC = 0f

    val isTouched: Boolean get() = !touchedS.isNaN()
    val isComplete: Boolean get() = !completeS.isNaN()

    fun touched(nowS: Double) {
        touchedS = nowS
        completeS = Double.NaN
        inflow = null
        tau = 0f
        rate = 0f
        lastS = nowS
    }

    /**
     * 传输期间每帧调用：[progress] 是显示出来的传输进度（0–1）。tau 只增不减；速度按最近几帧的变化平滑，
     * 数据到齐时用它接上指数逼近，两段之间没有折角。
     */
    fun advance(nowS: Double, progress: Float) {
        if (!isTouched || isComplete) return
        val elapsed = (nowS - touchedS).toFloat().coerceAtLeast(0f)
        val next = max(tau, softCap(scale * progress.coerceIn(0f, 1f).pow(gamma) + floor * elapsed))
        val dt = (nowS - lastS).toFloat()
        if (dt > 1e-4f) {
            val instant = (next - tau) / dt
            // 约 0.1 秒的指数平滑：单帧的跳动（进度按块到达）不会被当成速度
            val k = min(1f, dt / RATE_SMOOTHING_S)
            rate += (instant - rate) * k
            lastS = nowS
        }
        tau = next
    }

    /** 数据到齐：从此刻起按指数逼近上限。 */
    fun complete(nowS: Double) {
        if (!isTouched || isComplete) return
        completeS = nowS
        tauC = tau
        // 速度至少是最低速度：传输很快时平滑后的速度可能还没跟上
        rateC = max(rate, floor)
    }

    /** 流入开始（拼卡那一层第一帧上屏）：定下到拼好的那一段。 */
    fun startInflow(nowS: Double): Inflow {
        if (!isComplete) complete(nowS)
        val plan = inflowAt(nowS)
        inflow = plan
        return plan
    }

    /** 假设流入在 [startS] 开始，到拼好的那一段（光尘接手要在建材时预先算好交接的时刻）。 */
    fun inflowAt(startS: Double): Inflow {
        val tauH = waiting(startS)
        val rateH = waitingRate(startS)
        return Inflow(startS, tauH, rateH, appearS)
    }

    /** 此刻的 tau；碰上之前为 0。 */
    fun tauAt(nowS: Double): Float {
        if (!isTouched) return 0f
        inflow?.let { if (nowS >= it.startS) return it.tauAt(nowS) }
        if (!isComplete) return tau
        return waiting(nowS)
    }

    /**
     * 传输很慢时（十几秒的大图）「最低速度 × 秒数」会一直涨，越过上限就等于数据还没到先拼出了代理卡片。
     * 低于上限的 [KNEE] 倍时原样（模拟器里的传输都只有一两秒，落在这一段），之上按指数逼近上限，拐点处
     * 斜率连续。
     */
    private fun softCap(raw: Float): Float {
        val knee = cap * KNEE
        if (raw <= knee) return raw
        val room = cap - knee
        return knee + room * (1f - exp(-(raw - knee) / room))
    }

    /** 数据到齐之后、流入之前：指数逼近上限。 */
    private fun waiting(nowS: Double): Float {
        if (!isComplete) return tau
        // 到齐时已经贴着上限（很慢的传输）：停在那里
        val room = cap - tauC
        if (room <= 1e-6f) return tauC
        val elapsed = (nowS - completeS).toFloat().coerceAtLeast(0f)
        return tauC + room * (1f - exp(-rateC * elapsed / room))
    }

    private fun waitingRate(nowS: Double): Float {
        if (!isComplete) return rate
        val room = cap - tauC
        if (room <= 1e-6f) return 0f
        val elapsed = (nowS - completeS).toFloat().coerceAtLeast(0f)
        return rateC * exp(-rateC * elapsed / room)
    }

    /**
     * 光尘带的扩散程度：碰上之后随传输进度增加到 1；数据到齐之后（列表让位的那段时间）继续缓慢增加
     * （每秒 0.25，最多再加 0.5）。与 `ReceivePlan.spread` 相同；显示的进度在数据到齐时可能还没追到 1，
     * 照样按它加，不跳。
     */
    fun spread(nowS: Double, progress: Float): Float {
        if (!isTouched) return 0f
        var s = progress.coerceIn(0f, 1f)
        if (isComplete) s += min((nowS - completeS).toFloat().coerceAtLeast(0f) * .25f, .5f)
        return s
    }

    /** 开始扩散以来的秒数（碰上起算）。 */
    fun diffuseTime(nowS: Double): Float = if (!isTouched) 0f else (nowS - touchedS).toFloat().coerceAtLeast(0f)

    /**
     * 流入开始到拼好的那一段：三次 Hermite，起点 ([tauH], [rateH])，终点 (1, 1 / [durationS])。
     * 不可变，建材线程上也可以用。
     */
    class Inflow(val startS: Double, val tauH: Float, val rateH: Float, val durationS: Float) {

        val endS: Double get() = startS + durationS

        fun tauAt(nowS: Double): Float {
            val u = ((nowS - startS) / durationS).toFloat()
            if (u <= 0f) return tauH
            if (u >= 1f) return 1f
            return hermite(u)
        }

        private fun hermite(u: Float): Float {
            val u2 = u * u
            val u3 = u2 * u
            val h00 = 2f * u3 - 3f * u2 + 1f
            val h10 = u3 - 2f * u2 + u
            val h01 = -2f * u3 + 3f * u2
            val h11 = u3 - u2
            return h00 * tauH + h10 * durationS * rateH + h01 + h11
        }

        /** [tauAt] 的反函数：倒放走到 [target] 的时刻（二分，[target] 落在这一段之外时取两端）。 */
        fun timeOfTau(target: Float): Double {
            if (target <= tauH) return startS
            if (target >= 1f) return endS
            var lo = 0f
            var hi = 1f
            repeat(40) {
                val mid = (lo + hi) / 2f
                if (hermite(mid) < target) lo = mid else hi = mid
            }
            return startS + durationS * ((lo + hi) / 2f)
        }
    }

    companion object {
        private const val RATE_SMOOTHING_S = .1f

        /** 传输期间 tau 从上限的这个倍数起软着陆（见 [softCap]）。 */
        private const val KNEE = .8f
    }
}
