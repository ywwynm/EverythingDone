package com.ywwynm.everythingdone.views.particledismiss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.round

class ParticleDriftCanvasTest {
    private fun layout(width: Int, height: Int, sw: Int, sh: Int, x: Float, y: Float): ParticleDriftCanvas.Layout {
        val canvas = ParticleDriftCanvas.layout(width, height, sw, sh, x, y)
        // 离屏 GLSL 检查直接读取正式几何函数的结果，不另写一份画布尺寸公式。
        println("CANVAS|" + listOf(width, height, sw, sh, x, y, canvas.simulationWidth,
            canvas.simulationHeight, canvas.simulationLeft, canvas.simulationTop,
            canvas.workLeft, canvas.workTop, canvas.workWidth, canvas.workHeight).joinToString("|"))
        return canvas
    }

    @Test fun `高颜色面板的上方仍属于可绘制区域`() {
        val canvas = layout(1440, 3120, 1344, 2600, 48f, 472f)
        assertTrue("粒子越过面板上缘后仍在窗口内，不应在 y=${canvas.workTop} 被裁掉", canvas.workTop <= 0f)
        assertTrue(canvas.workLeft <= 0f && canvas.workRight >= 1440f && canvas.workBottom >= 3120f)
    }

    @Test fun `弹窗面板卡片创建页及横屏窗口均不留内部裁剪边界`() {
        val windows = listOf(1080 to 2400, 1440 to 3120, 3120 to 1440, 2560 to 1600)
        val clipped = mutableListOf<String>()
        for ((width, height) in windows) {
            for ((sw, sh) in listOf(width / 2 to height / 4, width - 48 to height - 96,
                width - 48 to height / 8, width to height, width + 64 to height + 64)) {
                for ((x, y) in listOf(24f to 48f, (width-sw) / 2f to (height-sh) / 2f,
                    (width-sw-24f) to (height-sh-48f))) {
                    val canvas = layout(width, height, sw, sh, x, y)
                    if (canvas.workLeft > 0f || canvas.workTop > 0f ||
                        canvas.workRight < width || canvas.workBottom < height)
                        clipped += "$width × $height, $sw × $sh, ($x, $y): $canvas"
                }
            }
        }
        assertTrue(clipped.joinToString("\n"), clipped.isEmpty())
    }

    @Test fun `扩大绘制区域保留原有模拟基准及发射位置`() {
        val tall = ParticleDriftCanvas.layout(1440, 3120, 1344, 2600, 48f, 472f)
        assertEquals(1440, tall.simulationWidth)
        assertEquals(2600, tall.simulationHeight)
        assertEquals(0f, tall.simulationLeft, 0f)
        assertEquals(472f, tall.simulationTop, 0f)
        val landscape = ParticleDriftCanvas.layout(3120, 1440, 3072, 180, 24f, 48f)
        assertEquals(3072, landscape.simulationWidth)
        assertEquals(1224, landscape.simulationHeight)
        assertEquals(24f, landscape.simulationLeft, 0f)
        assertEquals(-474f, landscape.simulationTop, 0f)
    }

    @Test fun `小数原点和奇数尺寸保留像素网格且工作面不随面板偏移膨胀`() {
        val origins = listOf(-128.75f, -1f, 0f, .5f, 24.25f, 1700.25f)
        for (x in origins) for (y in origins) {
            val canvas = ParticleDriftCanvas.layout(1081, 2401, 713, 1225, x, y)
            assertTrue(canvas.workLeft <= 0f && canvas.workLeft > -2f)
            assertTrue(canvas.workTop <= 0f && canvas.workTop > -2f)
            assertTrue(canvas.workRight >= 1081f && canvas.workRight < 1083f)
            assertTrue(canvas.workBottom >= 2401f && canvas.workBottom < 2403f)
            assertEquals(round(canvas.particleOffsetX), canvas.particleOffsetX, 0f)
            assertEquals(round(canvas.particleOffsetY), canvas.particleOffsetY, 0f)
            // 同一模拟粒子经新工作面平移后，最终仍落在原来的设备坐标。
            val px = 183.25f
            val py = -97.5f
            assertEquals(canvas.simulationLeft + px * 2f,
                canvas.workLeft + (px + canvas.particleOffsetX) * 2f, 0f)
            assertEquals(canvas.simulationTop + py * 2f,
                canvas.workTop + (py + canvas.particleOffsetY) * 2f, 0f)
        }
    }

    @Test fun `参考自定义模拟尺寸不再形成绘制边界`() {
        val canvas = ParticleDriftCanvas.layout(1440, 3120, 427, 481, 13.25f, -8.5f,
            floatArrayOf(901.5f, 1231.75f))
        assertEquals(901, canvas.simulationWidth)
        assertEquals(1231, canvas.simulationHeight)
        assertEquals(-223.75f, canvas.simulationLeft, 0f)
        assertEquals(-383.5f, canvas.simulationTop, 0f)
        assertTrue(canvas.workLeft <= 0f && canvas.workTop <= 0f)
        assertTrue(canvas.workRight >= 1440f && canvas.workBottom >= 3120f)
    }
}
