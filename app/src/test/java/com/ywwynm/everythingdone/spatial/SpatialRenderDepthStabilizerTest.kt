package com.ywwynm.everythingdone.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SpatialRenderDepthStabilizerTest {

    @Test
    fun `明暗 relief 只作为轻量提示而不主导空间形变感知`() {
        assertTrue(SpatialRenderDepthStabilizer.RELIEF_GAIN <= 0.2f)
        assertTrue(SpatialRenderDepthStabilizer.MAX_RELIEF <= 0.02f)
    }

    @Test
    fun `刚性取景移动与最大视差仍由常量取景边界覆盖`() {
        val maximumHorizontalSampleShift =
            SpatialRenderDepthStabilizer.RIGID_PAN_AMPLITUDE +
                SpatialRenderDepthStabilizer.MAX_PARALLAX_AMPLITUDE * 0.5f
        val margin = SpatialSourceLock.coverMargin(
            SpatialRenderDepthStabilizer.MAX_PARALLAX_AMPLITUDE
        )

        assertTrue(maximumHorizontalSampleShift < margin.x)
        assertTrue(margin.x <= SpatialRenderDepthStabilizer.MAX_COVER_MARGIN)
    }

    @Test
    fun `常量深度保持不变`() {
        val source = depth(
            width = 9,
            height = 7,
            values = FloatArray(9 * 7) { 0.37f }
        )

        val result = SpatialRenderDepthStabilizer.stabilize(source)

        result.values.forEach { assertEquals(0.37f, it, 0.000001f) }
    }

    @Test
    fun `阶跃深度被转换为有界连续坡度且远离边界的平面保持不变`() {
        val width = 121
        val source = depth(
            width = width,
            height = 5,
            values = FloatArray(width * 5) { index ->
                if (index % width < width / 2) 0f else 1f
            }
        )

        val result = SpatialRenderDepthStabilizer.stabilize(source)

        val deltaX = SpatialRenderDepthStabilizer.MAX_RENDER_SLOPE / width
        val deltaY = SpatialRenderDepthStabilizer.MAX_RENDER_SLOPE / result.height
        assertEquals(0f, result.values.first(), 0.000001f)
        assertEquals(1f, result.values[width - 1], 0.000001f)
        for (y in 0 until result.height) {
            for (x in 0 until result.width) {
                val index = y * result.width + x
                assertTrue(result.values[index] in 0f..1f)
                if (x > 0) {
                    assertTrue(
                        abs(result.values[index] - result.values[index - 1]) <=
                            deltaX + 0.000001f
                    )
                }
                if (y > 0) {
                    assertTrue(
                        abs(result.values[index] - result.values[index - result.width]) <=
                            deltaY + 0.000001f
                    )
                }
            }
        }
    }

    @Test
    fun `陡而未断的连通边升格为断边且不改动深度`() {
        val width = 40
        val height = 10
        // 左半 0.9、右半 0.1：中缝声明为断边；另在 x=30 处留一条未断的硬跳变。
        val depth = FloatArray(width * height) { index ->
            val x = index % width
            when {
                x < width / 2 -> 0.9f
                x < 30 -> 0.1f
                else -> 0.7f
            }
        }
        val cutRight = BooleanArray(height * (width - 1))
        for (y in 0 until height) {
            cutRight[y * (width - 1) + width / 2 - 1] = true
        }
        val geometry = SpatialLdiLiteGeometry(
            width = width,
            height = height,
            surfaceDepth = depth,
            backgroundDepth = depth.copyOf(),
            cutRight = cutRight,
            cutDown = BooleanArray((height - 1) * width),
            hiddenBackgroundMask = BooleanArray(width * height)
        )

        val result = SpatialRenderDepthStabilizer.promoteSteepEdgesToCuts(geometry)

        for (y in 0 until height) {
            // 声明断边保留；x=29→30 的硬跳变（斜率 0.6×40=24）被升格为断边。
            assertTrue(result.cutRight[y * (width - 1) + width / 2 - 1])
            assertTrue(result.cutRight[y * (width - 1) + 29])
            // 平坦区域的连通边不受影响。
            for (x in 0 until width - 1) {
                if (x == width / 2 - 1 || x == 29) continue
                assertFalse(result.cutRight[y * (width - 1) + x])
            }
        }
        // 深度原样保留，不做任何包络磨平。
        for (index in depth.indices) {
            assertEquals(depth[index], result.surfaceDepth[index], 0f)
        }
        // 升格后剩余连通边平坦，满幅请求不再被限幅。
        val limited = SpatialWarpBudget.analyze(result).limitMotion(0.09f, 0f)
        assertEquals(1f, limited.scale, 0.000001f)
    }

    @Test
    fun `低于升格阈值的连通坡面不被升格`() {
        val width = 100
        val height = 4
        // 单调坡面：每格 Δ=0.01，归一化斜率约 1，远低于约 3.56 的升格阈值。
        val depth = FloatArray(width * height) { index ->
            val x = index % width
            (0.01f * x).coerceIn(0f, 1f)
        }
        val geometry = SpatialLdiLiteGeometry(
            width = width,
            height = height,
            surfaceDepth = depth,
            backgroundDepth = depth.copyOf(),
            cutRight = BooleanArray(height * (width - 1)),
            cutDown = BooleanArray((height - 1) * width),
            hiddenBackgroundMask = BooleanArray(width * height)
        )

        val result = SpatialRenderDepthStabilizer.promoteSteepEdgesToCuts(geometry)

        // 连续坡面低于阈值：一条也不升格。
        assertFalse(result.cutRight.any { it })
        assertFalse(result.cutDown.any { it })
    }



    @Test
    fun `最大水平视差下阶跃深度不再产生逆向映射折返`() {
        val width = 384
        val source = depth(
            width = width,
            height = 3,
            values = FloatArray(width * 3) { index ->
                if (index % width < width / 2) 0f else 1f
            }
        )

        assertTrue(hasHorizontalFold(source))
        assertFalse(hasHorizontalFold(SpatialRenderDepthStabilizer.stabilize(source)))
    }

    private fun hasHorizontalFold(depth: SpatialDepthData): Boolean {
        val outputWidth = 1200
        for (direction in floatArrayOf(-1f, 1f)) {
            var previous = Float.NEGATIVE_INFINITY
            for (x in 0 until outputWidth) {
                val coverMargin = SpatialSourceLock.coverMargin(
                    SpatialRenderDepthStabilizer.MAX_PARALLAX_AMPLITUDE
                ).x
                val textureX = coverMargin +
                    x.toFloat() / (outputWidth - 1) *
                    (1f - 2f * coverMargin)
                val sample = bilinearHorizontal(depth, textureX, row = 1)
                val sourceX = textureX +
                    direction * SpatialRenderDepthStabilizer.MAX_PARALLAX_AMPLITUDE *
                    (sample - 0.5f)
                if (sourceX + 0.0000001f < previous) return true
                previous = sourceX
            }
        }
        return false
    }

    private fun bilinearHorizontal(depth: SpatialDepthData, u: Float, row: Int): Float {
        val x = u.coerceIn(0f, 1f) * (depth.width - 1)
        val left = x.toInt().coerceIn(0, depth.width - 1)
        val right = (left + 1).coerceAtMost(depth.width - 1)
        val fraction = x - left
        val offset = row * depth.width
        return depth.values[offset + left] * (1f - fraction) +
            depth.values[offset + right] * fraction
    }

    private fun depth(width: Int, height: Int, values: FloatArray) = SpatialDepthData(
        width = width,
        height = height,
        values = values,
        robustRange = 1f,
        strongEdgeRatio = 0f,
        defaultStrength = 0.72f
    )
}
