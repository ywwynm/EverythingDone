package com.ywwynm.everythingdone.taptransfer.ui.receive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** 接收的倒放时间轴：与模拟器 `ReceivePlan.reverse_tau` 的三段一致，且处处连续、单调。 */
class TapReceiveTimelineTest {

    private val scale = .22f
    private val floor = .02f
    private val cap = .34f
    private val gamma = .5f
    private val appear = 1.3f

    private fun timeline() = TapReceiveTimeline(scale, floor, cap, gamma, appear)

    /** 按 60 帧推进：碰上在 100 秒，进度按「时间的 1.6 次方」走（模拟器的假设），[span] 秒到齐。 */
    private fun transferred(span: Double): TapReceiveTimeline {
        val t = timeline()
        t.touched(100.0)
        var now = 100.0
        while (now < 100.0 + span) {
            now += 1.0 / 60.0
            val u = ((now - 100.0) / span).coerceAtMost(1.0)
            t.advance(now, u.pow(1.6).toFloat())
        }
        t.complete(now)
        return t
    }

    @Test fun `碰上之前为零，传输期间按进度推进`() {
        val t = timeline()
        assertEquals(0f, t.tauAt(50.0), 0f)
        t.touched(100.0)
        t.advance(101.0, .25f)
        assertEquals(scale * .25f.pow(gamma) + floor * 1f, t.tauAt(101.0), 1e-6f)
        // 进度不会倒退：tau 只增不减
        t.advance(101.1, .2f)
        assertTrue(t.tauAt(101.1) >= scale * .25f.pow(gamma) + floor * 1f)
    }

    @Test fun `到齐之后连续地逼近上限，越来越慢但不停`() {
        val t = transferred(2.0)
        val tc = t.completeS
        val atComplete = t.tauAt(tc)
        assertEquals(scale + floor * 2f, atComplete, .01f)
        var previous = atComplete
        var previousStep = Float.MAX_VALUE
        for (k in 1..40) {
            val now = tc + k * .25
            val tau = t.tauAt(now)
            val step = tau - previous
            assertTrue("第 $k 步没有前进", step > 0f)
            assertTrue("第 $k 步没有变慢", step <= previousStep + 1e-6f)
            assertTrue(tau < cap)
            previous = tau
            previousStep = step
        }
    }

    @Test fun `传输很慢时不会先拼出代理卡片`() {
        // 20 秒的传输：最低速度一项单独就有 0.4，越过了上限
        val t = transferred(20.0)
        val atComplete = t.tauAt(t.completeS)
        assertTrue("到齐时 $atComplete", atComplete < cap)
        // 逼近上限但不越过（单精度下足够久之后正好等于上限）
        assertTrue(t.tauAt(t.completeS + 30.0) <= cap)
        assertTrue(t.tauAt(t.completeS + .01) > atComplete)
    }

    @Test fun `流入从当时的位置出发，时长走完正好拼好`() {
        val t = transferred(1.5)
        val start = t.completeS + .8
        val before = t.tauAt(start)
        val inflow = t.startInflow(start)
        assertEquals(before, inflow.tauH, 1e-5f)
        assertEquals(before, t.tauAt(start), 1e-5f)
        assertEquals(1f, t.tauAt(start + appear), 0f)
        var previous = before
        for (k in 1..100) {
            val tau = t.tauAt(start + appear * k / 100.0)
            assertTrue("流入第 $k 步倒退", tau >= previous - 1e-6f)
            previous = tau
        }
    }

    @Test fun `交接时刻的反函数与正向一致`() {
        val t = transferred(1.5)
        val inflow = t.inflowAt(t.completeS + .5)
        for (target in listOf(.4f, .5f, .7f, .95f)) {
            val at = inflow.timeOfTau(target)
            assertEquals(target, inflow.tauAt(at), 1e-4f)
        }
        assertEquals(inflow.startS, inflow.timeOfTau(0f), 0.0)
        assertEquals(inflow.endS, inflow.timeOfTau(1f), 0.0)
    }

    @Test fun `光尘带的扩散随进度增加，到齐之后再缓慢增加至多一半`() {
        val t = timeline()
        assertEquals(0f, t.spread(10.0, .5f), 0f)
        t.touched(100.0)
        assertEquals(.5f, t.spread(100.5, .5f), 0f)
        t.complete(102.0)
        assertEquals(1.25f, t.spread(103.0, 1f), 1e-6f)
        assertEquals(1.5f, t.spread(110.0, 1f), 1e-6f)
    }
}
