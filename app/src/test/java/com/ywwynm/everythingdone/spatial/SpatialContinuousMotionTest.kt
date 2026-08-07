package com.ywwynm.everythingdone.spatial

import kotlin.math.abs
import kotlin.math.exp
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialContinuousMotionTest {

    @Test
    fun `硬深度台阶在主运动场中必须连续过渡而不能形成纸片边界`() {
        val width = 128
        val height = 80
        val depth = FloatArray(width * height) { index ->
            if (index % width < width / 2) 0.9f else 0.1f
        }
        val basis = SpatialContinuousMotionBuilder.build(width, height, depth)
        val scalar = basis.horizontalX
        var maximumStep = 0f
        for (y in 0 until height) {
            for (x in 0 until width - 1) {
                maximumStep = maxOf(
                    maximumStep,
                    abs(scalar[y * width + x + 1] - scalar[y * width + x])
                )
            }
        }
        val left = (0 until height).map { scalar[it * width + width / 8] }
            .average().toFloat()
        val right = (0 until height).map { scalar[it * width + width * 7 / 8] }
            .average().toFloat()

        assertTrue("运动场仍像分层纸片一样跳变：$maximumStep", maximumStep < 0.035f)
        assertTrue("过度平滑吞掉了全局前后关系：${left - right}", left - right > 0.30f)
    }

    @Test
    fun `双尺度场抑制像素噪声同时保留物体内部体积`() {
        val width = 96
        val height = 64
        val centerX = (width - 1) * 0.5f
        val centerY = (height - 1) * 0.5f
        val depth = FloatArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val dx = (x - centerX) / width
            val dy = (y - centerY) / height
            val volume = 0.34f * exp((-(dx * dx + dy * dy) / 0.055f).toDouble()).toFloat()
            val noise = if ((x + y) % 2 == 0) 0.10f else -0.10f
            (0.25f + volume + noise).coerceIn(0f, 1f)
        }
        val scalar = SpatialContinuousMotionBuilder.build(width, height, depth).horizontalX
        val center = scalar[(height / 2) * width + width / 2]
        val corner = scalar[width + 1]
        var checkerResidual = 0f
        for (y in 8 until height - 8) {
            for (x in 8 until width - 9) {
                checkerResidual = maxOf(
                    checkerResidual,
                    abs(scalar[y * width + x + 1] - scalar[y * width + x])
                )
            }
        }

        assertTrue("中尺度体积被压成刚性平移：${center - corner}", center - corner > 0.08f)
        assertTrue("像素级深度噪声仍会直接扭曲画面：$checkerResidual", checkerResidual < 0.02f)
    }
}
