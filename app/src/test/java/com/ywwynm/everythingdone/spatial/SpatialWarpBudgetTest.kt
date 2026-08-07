package com.ywwynm.everythingdone.spatial

import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialWarpBudgetTest {

    @Test
    fun `常量深度不削弱请求视差`() {
        val depth = depth(
            width = 32,
            height = 24,
            values = FloatArray(32 * 24) { 0.4f }
        )
        val requestedX = 0.057f
        val requestedY = -0.031f

        val limited = SpatialWarpBudget.analyze(depth)
            .limitMotion(requestedX, requestedY)

        assertEquals(requestedX, limited.x, 0.000001f)
        assertEquals(requestedY, limited.y, 0.000001f)
        assertEquals(1f, limited.scale, 0.000001f)
    }

    @Test
    fun `高风险阶跃在任意视点方向都受局部形变预算约束`() {
        val width = 384
        val source = depth(
            width = width,
            height = 257,
            values = FloatArray(width * 257) { index ->
                val x = index % width
                val y = index / width
                when {
                    x >= width / 2 && y >= 128 -> 1f
                    x >= width / 2 -> 0.8f
                    y >= 128 -> 0.2f
                    else -> 0f
                }
            }
        )
        val renderDepth = SpatialRenderDepthStabilizer.stabilize(source)
        val profile = SpatialWarpBudget.analyze(renderDepth)

        for ((x, y) in listOf(
            0.068f to 0f,
            -0.068f to 0f,
            0f to 0.068f,
            0f to -0.068f,
            0.048f to 0.048f,
            -0.048f to 0.048f
        )) {
            val limited = profile.limitMotion(x, y)
            val risk = hypot(limited.x, limited.y) * profile.gradientNorm
            assertTrue(
                "局部位移梯度 $risk 超过预算",
                risk <= SpatialWarpBudget.MAX_DISPLACEMENT_GRADIENT + 0.00001f
            )
            assertTrue(limited.scale in 0f..1f)
        }
    }

    @Test
    fun `平缓深度坡面保留完整交互幅度`() {
        val width = 384
        val height = 256
        val values = FloatArray(width * height) { index ->
            val x = index % width
            val y = index / width
            0.15f + 0.55f * x / (width - 1) + 0.2f * y / (height - 1)
        }
        val profile = SpatialWarpBudget.analyze(
            depth(width = width, height = height, values = values)
        )

        val requestedX = 0.05f
        val requestedY = 0.03f
        val limited = profile.limitMotion(requestedX, requestedY)

        assertEquals(requestedX, limited.x, 0.000001f)
        assertEquals(requestedY, limited.y, 0.000001f)
        assertEquals(1f, limited.scale, 0.000001f)
        assertTrue(profile.gradientNorm < 2f)
    }

    @Test
    fun `形变预算按 GPU 实际上传的八位深度计算`() {
        val width = 384
        // 奇偶列交替：几乎每条水平边都携带 0.02 的浮点差，高分位统计必然命中。
        val values = FloatArray(width) { x -> if (x % 2 == 0) 0.0038f else 0.0238f }

        val profile = SpatialWarpBudget.analyze(
            depth(width = width, height = 1, values = values)
        )

        // 浮点差为 0.02；转为 8-bit LUMINANCE 后实际相邻差为 6/255。
        assertTrue(profile.gradientNorm > 8.5f)
    }

    @Test
    fun `孤立坏点不再吞掉整图行程`() {
        val width = 384
        val height = 256
        val values = FloatArray(width * height) { 0.4f }
        // 单个异常像素制造 4 条硬边，远低于高分位阈值，应被预算忽略。
        values[128 * width + 192] = 1f

        val limited = SpatialWarpBudget.analyze(
            depth(width = width, height = height, values = values)
        ).limitMotion(0.09f, -0.05f)

        assertEquals(0.09f, limited.x, 0.000001f)
        assertEquals(-0.05f, limited.y, 0.000001f)
        assertEquals(1f, limited.scale, 0.000001f)
    }

    @Test
    fun `限幅保持视点方向不变`() {
        val width = 257
        val source = depth(
            width = width,
            height = 121,
            values = FloatArray(width * 121) { index ->
                if (index % width < width / 2) 0f else 1f
            }
        )
        val profile = SpatialWarpBudget.analyze(
            SpatialRenderDepthStabilizer.stabilize(source)
        )

        // 请求取新满幅对角（0.12, -0.072）：|m|×梯度 ≈ 0.564 > 0.36，确保触发钳制。
        val limited = profile.limitMotion(0.12f, -0.072f)

        assertTrue(limited.scale < 1f)
        assertEquals(
            0.12f / -0.072f,
            limited.x / limited.y,
            0.0001f
        )
        assertTrue(abs(limited.x) <= 0.12f)
        assertTrue(abs(limited.y) <= 0.072f)
    }

    @Test
    fun `双层断边不削弱由背景层承接的视差`() {
        val width = 20
        val height = 12
        val depth = FloatArray(width * height) { index ->
            if (index % width < width / 2) 0.9f else 0.1f
        }
        val cuts = BooleanArray(height * (width - 1))
        for (y in 0 until height) {
            cuts[y * (width - 1) + width / 2 - 1] = true
        }
        val geometry = SpatialLdiLiteGeometry(
            width = width,
            height = height,
            surfaceDepth = depth,
            backgroundDepth = depth.copyOf(),
            cutRight = cuts,
            cutDown = BooleanArray((height - 1) * width),
            hiddenBackgroundMask = BooleanArray(width * height)
        )

        val limited = SpatialWarpBudget.analyze(geometry)
            .limitMotion(0.068f, 0.041f)

        assertEquals(0.068f, limited.x, 0.000001f)
        assertEquals(0.041f, limited.y, 0.000001f)
        assertEquals(1f, limited.scale, 0.000001f)
    }

    @Test
    fun `双层连续表面仍受形变预算约束`() {
        val width = 20
        val height = 12
        val depth = FloatArray(width * height) { index ->
            if (index % width < width / 2) 0.9f else 0.1f
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

        val limited = SpatialWarpBudget.analyze(geometry)
            .limitMotion(0.068f, 0.041f)

        assertTrue(limited.scale < 1f)
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
