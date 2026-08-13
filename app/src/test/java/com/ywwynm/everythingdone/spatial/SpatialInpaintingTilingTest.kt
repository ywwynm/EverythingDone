package com.ywwynm.everythingdone.spatial

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 分块几何必须与桌面 `inpaint_onnx_tiled` 逐条一致，否则端上产出的第二层与网页端验收
 * 过的那一版不是同一个东西。下面的期望值是从桌面实现按同一公式手算出来的，
 * 不是从本实现反推的——反推会把两边一起写错。
 */
class SpatialInpaintingTilingTest {

    @Test
    fun 成品尺寸下的分块与桌面一致() {
        // 540×720：桌面 pad_r = ceil((540-512)/384)*384 + 512 - 540 = 384+512-540 = 356
        // → padded 896；同理 padded 高 = 896。两个方向各 2 个块（0 与 384）。
        val plan = SpatialInpaintingTiling.plan(540, 720)
        assertEquals(896, plan.paddedWidth)
        assertEquals(896, plan.paddedHeight)
        assertArrayEquals(intArrayOf(0, 384), plan.originsX)
        assertArrayEquals(intArrayOf(0, 384), plan.originsY)
        assertEquals(4, plan.tileCount)
    }

    @Test
    fun 小于一块时补到一块且只有一个块() {
        val plan = SpatialInpaintingTiling.plan(300, 200)
        assertEquals(512, plan.paddedWidth)
        assertEquals(512, plan.paddedHeight)
        assertArrayEquals(intArrayOf(0), plan.originsX)
        assertArrayEquals(intArrayOf(0), plan.originsY)
    }

    @Test
    fun 恰好一块时不额外补边() {
        val plan = SpatialInpaintingTiling.plan(512, 512)
        assertEquals(512, plan.paddedWidth)
        assertEquals(512, plan.paddedHeight)
        assertEquals(1, plan.tileCount)
    }

    @Test
    fun 每个块都落在填充后的范围内且最后一块贴边() {
        for (extent in intArrayOf(513, 700, 896, 897, 1080, 1920)) {
            val plan = SpatialInpaintingTiling.plan(extent, extent)
            val last = plan.originsX.last()
            assertTrue("块起点越界 extent=$extent", last + plan.tile <= plan.paddedWidth)
            assertEquals(
                "最后一块必须贴到填充边 extent=$extent",
                plan.paddedWidth - plan.tile,
                last
            )
        }
    }

    @Test
    fun 反射下标不重复边缘像素() {
        // numpy mode="reflect"：…3 2 1 0 | 0 1 2 3 4 | 3 2 1 0…（边缘不重复）
        assertEquals(0, SpatialInpaintingTiling.reflectIndex(0, 5))
        assertEquals(4, SpatialInpaintingTiling.reflectIndex(4, 5))
        assertEquals(3, SpatialInpaintingTiling.reflectIndex(5, 5))
        assertEquals(2, SpatialInpaintingTiling.reflectIndex(6, 5))
        assertEquals(0, SpatialInpaintingTiling.reflectIndex(8, 5))
        assertEquals(1, SpatialInpaintingTiling.reflectIndex(9, 5))
        // 退化尺寸不得越界
        assertEquals(0, SpatialInpaintingTiling.reflectIndex(7, 1))
    }

    @Test
    fun 窗函数与numpy的hanning一致() {
        val window = SpatialInpaintingTiling.window(512, 128)
        // 第 256 行的行权重恰为 1，因此该行就是一维窗本身
        val line = FloatArray(512) { window[256 * 512 + it] }
        // 中间一整段权重为 1
        assertEquals(1f, window[256 * 512 + 256], 1e-6f)
        // np.hanning(256)[0] == 0，因此块的最外圈权重恰为 0
        assertEquals(0f, window[0], 1e-6f)
        assertEquals(0f, window[511], 1e-6f)
        assertEquals(0f, window[511 * 512 + 511], 1e-6f)
        // np.hanning(256)[64] = 0.5 - 0.5*cos(2*pi*64/255) = 0.50307997（实测对表）
        assertEquals(0.50307997f, line[64], 1e-5f)
        // np.hanning(256)[127] = 0.99996206
        assertEquals(0.99996206f, line[127], 1e-5f)
        // 左右对称
        for (i in 0 until 512) {
            assertTrue("窗函数不对称 i=$i", abs(line[i] - line[511 - i]) < 1e-5f)
        }
    }

    @Test
    fun 窗函数非负且不超过一() {
        val window = SpatialInpaintingTiling.window()
        for (value in window) {
            assertTrue("窗值越界 $value", value >= -1e-6f && value <= 1f + 1e-6f)
        }
    }

    @Test
    fun 成品尺寸下每个真实像素都被至少一个块以正权重覆盖() {
        // 权重恒为 0 的像素会退回原图；带落在那里就永远补不上，因此必须逐像素核一遍。
        val width = 540
        val height = 720
        val plan = SpatialInpaintingTiling.plan(width, height)
        val window = SpatialInpaintingTiling.window(plan.tile, SpatialInpaintingTiling.OVERLAP)
        val weights = FloatArray(width * height)
        for (originY in plan.originsY) {
            for (originX in plan.originsX) {
                val endY = minOf(originY + plan.tile, height)
                val endX = minOf(originX + plan.tile, width)
                for (y in originY until endY) {
                    for (x in originX until endX) {
                        val local = (y - originY) * plan.tile + (x - originX)
                        weights[y * width + x] += window[local]
                    }
                }
            }
        }
        var uncovered = 0
        for (value in weights) if (value <= 1e-6f) uncovered++
        // 只有画面最外圈的一圈像素（窗值恰为 0 且只被一个块覆盖）允许没有权重
        assertTrue("无权重像素过多：$uncovered", uncovered <= 2 * (width + height))
    }
}
