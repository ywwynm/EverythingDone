package com.ywwynm.everythingdone.taptransfer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TapProgressTest {

    @Test
    fun joinPhaseCreepsButNeverReachesItsShare() {
        val model = TapProgressModel()
        model.touched(0L)
        val early = model.target(300L)
        // 入组最慢实测 3 秒；8 秒时仍在渐近，没有到顶
        val late = model.target(8_000L)
        assertTrue(early > 0f)
        assertTrue(late > early)
        assertTrue(late < TapProgressModel.JOIN_SHARE)
        assertTrue(late > TapProgressModel.JOIN_SHARE * 0.99f)
    }

    @Test
    fun dataPhaseStartsWhereJoinPhaseWasAndEndsBeforeOne() {
        val model = TapProgressModel()
        model.touched(0L)
        val atConnect = model.target(1200L)
        model.connected(1200L)
        assertEquals(atConnect, model.target(1200L), 1e-6f)
        model.progress(50L, 100L)
        val half = model.target(1300L)
        assertTrue(half > atConnect && half < TapProgressModel.DATA_END)
        model.progress(100L, 100L)
        assertEquals(TapProgressModel.DATA_END, model.target(1400L), 1e-6f)
        model.finished()
        assertEquals(1f, model.target(1500L), 0f)
    }

    @Test
    fun smootherStartsSlowlyEvenIfTargetJumps() {
        val smoother = ProgressSmoother()
        val first = smoother.step(1f, 1f / 60f)
        // 起步缓入：第一帧只走很小的一步
        assertTrue("first=$first", first < 0.01f)
    }

    @Test
    fun smootherRespectsMinimumDuration() {
        val smoother = ProgressSmoother(minDurationS = 1.1f)
        var t = 0f
        while (!smoother.isFinished && t < 5f) {
            smoother.step(1f, 1f / 60f)
            t += 1f / 60f
        }
        assertTrue("t=$t", t >= 1.1f - 1f / 30f)
        assertTrue("t=$t", t < 1.3f)
    }

    @Test
    fun smootherIsMonotonicAndNeverOvershootsTarget() {
        val smoother = ProgressSmoother()
        var previous = 0f
        val targets = floatArrayOf(0.1f, 0.3f, 0.3f, 0.34f, 0.6f, 0.96f, 0.96f, 1f)
        for (target in targets) {
            repeat(20) {
                val v = smoother.step(target, 1f / 60f)
                assertTrue(v >= previous)
                assertTrue(v <= target + 1e-5f)
                previous = v
            }
        }
    }

    @Test
    fun smootherSurvivesLongFrames() {
        val smoother = ProgressSmoother()
        repeat(200) { smoother.step(1f, 0.5f) }
        assertEquals(1f, smoother.value, 0f)
    }

    @Test
    fun easingCurveEndpointsAndSlope() {
        assertEquals(0f, ProgressSmoother.easeInOutCubic(0f), 0f)
        assertEquals(1f, ProgressSmoother.easeInOutCubic(1f), 0f)
        assertEquals(0.5f, ProgressSmoother.easeInOutCubic(0.5f), 1e-6f)
        val h = 1e-3f
        for (t in floatArrayOf(0.2f, 0.45f, 0.7f)) {
            val numeric = (ProgressSmoother.easeInOutCubic(t + h) - ProgressSmoother.easeInOutCubic(t - h)) / (2 * h)
            assertEquals(numeric, ProgressSmoother.easeInOutCubicSlope(t), 1e-2f)
        }
    }
}
