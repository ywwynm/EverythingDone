package com.ywwynm.everythingdone.taptransfer.ui.particles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 光尘带的几何与取色：与桌面模拟器 `sim_dust.py` 相同。期望值由模拟器算出。 */
class TapDustBandTest {

    @Test fun `圆角矩形上按周长比例取点与模拟器相同`() {
        // (周长比例, 内缩) → 模拟器 inset_round_rect 的结果；屏幕 1080 × 2340、圆角 90、越往里角越圆 0.6
        val cases = listOf(
            floatArrayOf(0f, 9f, 95.4f, 9f),
            floatArrayOf(.1f, 9f, 757.36672f, 9f),
            floatArrayOf(.26f, 30f, 1050f, 802.66088f),
            floatArrayOf(.37f, 50f, 1030f, 1508.37876f),
            floatArrayOf(.5f, 9f, 984.6f, 2331f),
            floatArrayOf(.74f, 120f, 120f, 1575.56389f),
            floatArrayOf(.99f, 20f, 43.32176f, 44.72118f),
            floatArrayOf(1.3f, 9f, 1071f, 1056.38336f)
        )
        val out = FloatArray(2)
        for (c in cases) {
            TapDustBand.insetRoundRect(c[0], 1080f, 2340f, c[1], 90f, TapDustBand.ROUNDING, out)
            assertEquals("frac=${c[0]} inset=${c[1]} 的 x", c[2], out[0], .05f)
            assertEquals("frac=${c[0]} inset=${c[1]} 的 y", c[3], out[1], .05f)
        }
    }

    @Test fun `调色板每片只取相邻两色之一`() {
        val palette = intArrayOf(0xFF0000, 0x00FF00, 0x0000FF)
        // 位置 0.5 落在第 1、2 色之间（f = 1.5）：挑哪一色看随机数与 0.5 的比较
        assertEquals(0x00FF00, TapDustBand.palettePick(palette, .5f, .9f))
        assertEquals(0x0000FF, TapDustBand.palettePick(palette, .5f, .1f))
        // 首尾相接：位置 0.9 在最后一色与第一色之间
        val wrapped = TapDustBand.palettePick(palette, .9f, 0f)
        assertTrue(wrapped == 0x0000FF || wrapped == 0xFF0000)
        assertEquals(0xABCDEF, TapDustBand.palettePick(intArrayOf(0xABCDEF), .37f, .5f))
    }
}
