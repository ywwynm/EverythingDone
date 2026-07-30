package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.abs
import kotlin.math.exp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `SDR（保留高光层次）` 曲线的数学门禁（fablesol-video-export D68～D76）。
 *
 * 曲线没有画面预览，用户只能靠信息栏的一句话理解它；因此每一条结构性质都必须由测试钉住，
 * 而不是靠肉眼看导出结果"差不多对"。
 */
class FableSolExportSdrToneMapTest {

    private val toneMap = FableSolExportSdrToneMap
    private val maxStrength = FableSolHdrPolicy.MAX_STRENGTH.toDouble()

    private fun stable(strength: Double) = toneMap.curveFor(strength, strength)

    // ---- 标定 ----

    /**
     * D72 的两个端点：强度 `1.0×` 严格恒等，`9.6×` 用满 BT.2446 Method B 的标定量——
     * HDR 参考白落到 SDR **信号** 90%。中间连续，不在开关 HDR 的边界跳变。
     */
    @Test
    fun calibrationPinsBothEndsOfTheStrengthRange() {
        assertEquals(1.0, toneMap.calibrationWhite(1.0), 1e-12)
        assertTrue(stable(1.0).identity)

        val full = toneMap.calibrationWhite(maxStrength)
        // 反过来套 BT.709 OETF 应当正好回到 0.90 信号。
        val signal = 1.099 * Math.pow(full, 0.45) - 0.099
        assertEquals(toneMap.FULL_CALIBRATION_SIGNAL, signal, 1e-9)

        // 顶部预留随强度单调增加且连续。
        var previous = 0.0
        var strength = 1.0
        while (strength <= maxStrength + 1e-9) {
            val reserved = 1.0 - toneMap.calibrationWhite(strength)
            assertTrue("reserved headroom must grow at $strength", reserved >= previous - 1e-12)
            assertTrue(reserved - previous < 0.02)
            previous = reserved
            strength += 0.05
        }
    }

    /** 膝点由 W 反解，使基础段同时满足 `F(1) = W` 与 `F'(1) = 1/e`。 */
    @Test
    fun kneeSolvesForBothConstraintsAtReferenceWhite() {
        for (strength in listOf(1.5, 3.6, 6.0, maxStrength)) {
            val curve = stable(strength)
            assertEquals(curve.white, curve.scalar(1.0), 1e-12)
            val h = 1e-6
            val slope = (curve.scalar(1.0) - curve.scalar(1.0 - h)) / h
            assertEquals(1.0 / Math.E, slope, 1e-4)
            assertTrue("knee must stay inside the range", curve.knee > 0.0 && curve.knee < 1.0)
        }
    }

    // ---- 结构 ----

    /** 单调、连续、只压缩不提亮，且顶端从不越过 1.0。 */
    @Test
    fun curveIsMonotoneContinuousAndCompressOnly() {
        for (strength in listOf(1.2, 2.0, 3.6, 6.4, maxStrength)) {
            for (peak in listOf(1.0, 1.2, 1.6, 2.5, strength)) {
                val curve = toneMap.curveFor(strength, peak)
                var previous = curve.scalar(0.0)
                assertEquals(0.0, previous, 1e-12)
                var m = 0.0
                while (m <= strength + 1e-9) {
                    val value = curve.scalar(m)
                    assertTrue("monotone at $m (strength=$strength peak=$peak)", value >= previous - 1e-12)
                    assertTrue("must not brighten at $m", value <= m + 1e-9)
                    assertTrue("must stay in SDR at $m", value <= 1.0 + 1e-9)
                    // 连续：相邻采样的落差不超过步长本身（斜率处处 ≤ 1）。
                    assertTrue("continuous at $m", value - previous <= 0.001 + 1e-9)
                    previous = value
                    m += 0.001
                }
            }
        }
    }

    /** 膝点以下严格恒等：暗部与中间调一个码值都不动（D68）。 */
    @Test
    fun shadowsAndMidTonesAreUntouched() {
        val curve = stable(maxStrength)
        for (m in listOf(0.0, 0.01, 0.18, 0.3, curve.knee)) {
            assertEquals(m, curve.scalar(m), 1e-12)
        }
        assertTrue("compression must start below reference white", curve.knee < 1.0)
        // 18% 中灰必须落在恒等段里，否则"中间调保持不变"就名不副实。
        assertTrue(curve.knee > 0.18)
    }

    /** 两段之间在膝点处 C¹ 连续：斜率从 1 平滑降下来，不出现折角。 */
    @Test
    fun kneeJoinIsSmooth() {
        val curve = stable(maxStrength)
        val h = 1e-6
        val below = (curve.scalar(curve.knee) - curve.scalar(curve.knee - h)) / h
        val above = (curve.scalar(curve.knee + h) - curve.scalar(curve.knee)) / h
        assertEquals(1.0, below, 1e-5)
        assertEquals(1.0, above, 1e-4)
    }

    /** 参考白处同样 C¹ 连续，且**任何**控制峰值下斜率都恒为 `1/e`。 */
    @Test
    fun referenceWhiteJoinIsSmoothForEveryControlPeak() {
        val h = 1e-6
        for (peak in listOf(1.05, 1.3, 1.5631, 2.0, 5.0, maxStrength)) {
            val curve = toneMap.curveFor(maxStrength, peak)
            val below = (curve.scalar(1.0) - curve.scalar(1.0 - h)) / h
            val above = (curve.scalar(1.0 + h) - curve.scalar(1.0)) / h
            assertEquals("below at peak=$peak", 1.0 / Math.E, below, 1e-4)
            assertEquals("above at peak=$peak", 1.0 / Math.E, above, 1e-4)
        }
    }

    // ---- 基础段全片固定（D71） ----

    /** 动态映射只动 `>1.0`：`0～1.0` 的映射在任何控制峰值下逐点相同。 */
    @Test
    fun theBaseRangeNeverMovesWithTheControlPeak() {
        val reference = toneMap.curveFor(maxStrength, maxStrength)
        for (peak in listOf(1.0, 1.1, 1.4, 2.2, 4.0, 8.0)) {
            val curve = toneMap.curveFor(maxStrength, peak)
            var m = 0.0
            while (m <= 1.0) {
                assertEquals("base range moved at $m (peak=$peak)", reference.scalar(m), curve.scalar(m), 1e-12)
                m += 0.002
            }
        }
    }

    /** 反过来：超白段确实随控制峰值变化，否则动态映射等于没做。 */
    @Test
    fun theHighlightRangeDoesRespondToTheControlPeak() {
        val wide = toneMap.curveFor(maxStrength, maxStrength)
        val narrow = toneMap.curveFor(maxStrength, 2.0)
        assertTrue(narrow.scalar(1.5) > wide.scalar(1.5) + 0.01)
        // 控制峰值本身正好落到顶端。
        assertEquals(1.0, narrow.scalar(2.0), 1e-9)
        assertEquals(1.0, wide.scalar(maxStrength), 1e-9)
    }

    /**
     * 控制峰值太靠近 1.0 时够不到 1.0：强行拉满需要凸曲线，那等于在高光段放大对比度，
     * 与 D68"只压缩"相悖。此时改走 `p = 1` 的直线，落点连续、不跳变。
     */
    @Test
    fun aPeakTooCloseToWhiteFallsShortInsteadOfExpandingContrast() {
        val curve = toneMap.curveFor(maxStrength, 1.2)
        assertTrue("exponent must never drop below 1", curve.exponent >= 1.0)
        assertTrue("target must fall short of 1.0", curve.target < 1.0)
        assertEquals(curve.target, curve.scalar(1.2), 1e-12)

        // 跨越 p = 1 的边界（peak - 1 = 1 - knee）时 target 与曲线都连续。
        val boundary = 1.0 + (1.0 - curve.knee)
        val before = toneMap.curveFor(maxStrength, boundary - 1e-4)
        val after = toneMap.curveFor(maxStrength, boundary + 1e-4)
        assertEquals(1.0, after.target, 1e-6)
        assertTrue(abs(before.target - after.target) < 1e-3)
        assertTrue(abs(before.scalar(1.3) - after.scalar(1.3)) < 1e-3)
    }

    /** 超出控制峰值的输入按 `T` 同比例收缩，不逐通道硬钳（D69）。 */
    @Test
    fun inputAboveTheControlPeakShrinksProportionally() {
        val curve = toneMap.curveFor(maxStrength, 2.0)
        assertEquals(curve.target, curve.scalar(2.0), 1e-12)
        assertEquals(curve.target, curve.scalar(5.0), 1e-12)
        val rgb = doubleArrayOf(5.0, 2.5, 1.0)
        curve.apply(rgb)
        // 通道比例原样保留。
        assertEquals(2.0, rgb[0] / rgb[1], 1e-9)
        assertEquals(5.0, rgb[0] / rgb[2], 1e-9)
        assertTrue(rgb[0] <= 1.0 + 1e-9)
    }

    // ---- 共同增益（D69、D76） ----

    /** 三个通道乘同一个数：中性白仍是中性白，带色高光保持色相与饱和度。 */
    @Test
    fun rgbGetsOneCommonGain() {
        val curve = stable(maxStrength)
        val neutral = doubleArrayOf(4.0, 4.0, 4.0)
        curve.apply(neutral)
        assertEquals(neutral[0], neutral[1], 1e-12)
        assertEquals(neutral[1], neutral[2], 1e-12)

        val tinted = doubleArrayOf(3.0, 1.5, 0.6)
        val before = tinted.copyOf()
        curve.apply(tinted)
        val gain = tinted[0] / before[0]
        assertEquals(gain, tinted[1] / before[1], 1e-12)
        assertEquals(gain, tinted[2] / before[2], 1e-12)
        // 亮度尺度是 maxRGB，不是加权亮度：最亮通道决定压缩量（D76）。
        assertEquals(curve.scalar(3.0), tinted[0], 1e-12)
    }

    /** 恒等曲线一动不动，连黑点都不碰。 */
    @Test
    fun identityCurveLeavesEverythingAlone() {
        val curve = stable(1.0)
        val rgb = doubleArrayOf(0.0, 0.42, 1.0)
        curve.apply(rgb)
        assertEquals(0.0, rgb[0], 1e-12)
        assertEquals(0.42, rgb[1], 1e-12)
        assertEquals(1.0, rgb[2], 1e-12)
    }

    // ---- 时间响应（D74） ----

    /** 第一帧直接按实测值初始化，不从占位值渐变上来。 */
    @Test
    fun theFirstFrameInitialisesDirectly() {
        val tracker = FableSolExportSdrToneMap.PeakTracker()
        assertEquals(1.0, tracker.current, 1e-12)
        assertEquals(4.2, tracker.next(4.2, 1.0 / 120.0), 1e-12)
    }

    /** 快压慢放：上升用 `0.08s`，下降用 `0.80s`，两者相差整整一个数量级。 */
    @Test
    fun attackIsTenTimesFasterThanRelease() {
        val dt = 1.0 / 120.0
        val rising = FableSolExportSdrToneMap.PeakTracker()
        rising.next(1.0, dt)
        val afterRise = rising.next(5.0, dt)
        val falling = FableSolExportSdrToneMap.PeakTracker()
        falling.next(5.0, dt)
        val afterFall = falling.next(1.0, dt)

        val riseProgress = (afterRise - 1.0) / 4.0
        val fallProgress = (5.0 - afterFall) / 4.0
        assertEquals(1.0 - exp(-dt / FableSolExportSdrToneMap.ATTACK_SECONDS), riseProgress, 1e-9)
        assertEquals(1.0 - exp(-dt / FableSolExportSdrToneMap.RELEASE_SECONDS), fallProgress, 1e-9)
        assertTrue(riseProgress > fallProgress * 9.0)
    }

    /**
     * 一次闪现的完整时序：压下去要快到不削顶，放开要慢到看不出跳亮。
     *
     * 120fps 下十帧正好是 `0.0833s`，约一个 attack 时间常数、却只有八分之一个 release
     * 时间常数——同一段时间里跟进走了近三分之二，释放只走了一成。星芒因此不会被削平，
     * 它消失之后剩余高光也不会突然变亮。
     */
    @Test
    fun aFlashIsFollowedQuicklyAndReleasedSlowly() {
        val dt = 1.0 / 120.0
        val tenFrames = 10.0 * dt
        val tracker = FableSolExportSdrToneMap.PeakTracker()
        tracker.next(1.0, dt)
        repeat(10) { tracker.next(6.0, dt) }
        val attacked = (tracker.current - 1.0) / 5.0
        assertEquals(
            1.0 - exp(-tenFrames / FableSolExportSdrToneMap.ATTACK_SECONDS), attacked, 1e-9
        )
        assertTrue("attack must clear two thirds within 10 frames", attacked > 0.64)

        // 跟到九成需要约 2.3 个时间常数，即 0.18s；这仍远快于一次可见的高光变化。
        repeat(15) { tracker.next(6.0, dt) }
        assertTrue("attack must clear 90% within 25 frames", (tracker.current - 1.0) / 5.0 > 0.9)

        val peak = tracker.current
        repeat(10) { tracker.next(1.0, dt) }
        val remaining = (tracker.current - 1.0) / (peak - 1.0)
        assertEquals(exp(-tenFrames / FableSolExportSdrToneMap.RELEASE_SECONDS), remaining, 1e-9)
        assertTrue("release must still hold 90% after 10 frames", remaining > 0.9)
    }

    /** 控制量始终夹在 `1.0～当前 HDR 强度`（D75），曲线不会被喂进越界值。 */
    @Test
    fun theControlPeakStaysInsideItsLegalRange() {
        val curve = toneMap.curveFor(maxStrength, 99.0)
        assertEquals(maxStrength, curve.peak, 1e-12)
        val floored = toneMap.curveFor(maxStrength, 0.2)
        assertEquals(1.0, floored.peak, 1e-12)
        // 峰值退到 1.0 时超白段整段退化成常数，等于"这一帧没有额外高光可分配"。
        assertEquals(floored.white, floored.scalar(1.0), 1e-12)
        assertEquals(floored.white, floored.scalar(3.0), 1e-12)
    }

    // ---- 归约编解码（D73） ----

    /** 峰值打包与解包是同一套定义；32×32 网格里取的是全画面最大值。 */
    @Test
    fun peakEncodingRoundTripsThroughTheReductionGrid() {
        val grid = FableSolExportScenePeak.GRID
        val bytes = ByteArray(grid * grid * 4)
        val (lowSmall, highSmall) = FableSolExportScenePeak.encodePeak(1.5, maxStrength)
        for (index in bytes.indices step 4) {
            bytes[index] = lowSmall.toByte()
            bytes[index + 1] = highSmall.toByte()
        }
        assertEquals(1.5, FableSolExportScenePeak.decodePeak(bytes, maxStrength), 2e-4)

        // 一个块里出现更亮的内容就要被取出来——银丝与星芒正是这种稀疏高光（D75）。
        val (lowBig, highBig) = FableSolExportScenePeak.encodePeak(7.25, maxStrength)
        bytes[17 * 4] = lowBig.toByte()
        bytes[17 * 4 + 1] = highBig.toByte()
        assertEquals(7.25, FableSolExportScenePeak.decodePeak(bytes, maxStrength), 2e-4)
    }

    // ---- 固定素材回归 ----

    /**
     * 三段固定素材的落点。数值本身是标定结果，改动曲线族时必须重新解释、而不是顺手改数。
     *
     * - 暗水体（线性 0.12）与中灰（0.18）在恒等段，一个码值都不许动；
     * - 普通白（1.0）落到 SDR 信号 90%；
     * - 星芒核心（强度峰值）落到满幅。
     */
    @Test
    fun fixedMaterialLandsWhereTheCalibrationSays() {
        val curve = stable(maxStrength)
        assertEquals(0.12, curve.scalar(0.12), 1e-12)
        assertEquals(0.18, curve.scalar(0.18), 1e-12)
        assertEquals(0.8089627, curve.scalar(1.0), 1e-6)
        assertEquals(1.0, curve.scalar(maxStrength), 1e-9)
        // 银丝（2.0×）与星芒（6.0×）之间仍分得开，没有被压成同一片死白。
        assertNotEquals(curve.scalar(2.0), curve.scalar(6.0), 0.02)
    }
}
