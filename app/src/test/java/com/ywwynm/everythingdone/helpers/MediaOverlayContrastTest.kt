package com.ywwynm.everythingdone.helpers

import org.junit.Assert.*
import org.junit.Test

class MediaOverlayContrastTest {
    private fun gray(value: Int) = (255 shl 24) or (value shl 16) or (value shl 8) or value

    @Test fun `均匀背景包括中灰按半透明后的对比度选择`() {
        for ((gray, dark) in listOf(0 to false, 40 to false, 96 to false,
            128 to true, 160 to true, 240 to true, 255 to true)) {
            assertEquals("灰阶 $gray", dark,
                MediaOverlayContrast.choose(listOf(MediaOverlayContrast.Sample(gray(gray)))).dark)
        }
    }

    @Test fun `参考黑白对比度与透明合成值正确`() {
        assertEquals(21.0, MediaOverlayContrast.contrast(gray(0), gray(255)), 1e-12)
        assertEquals(1.0, MediaOverlayContrast.contrast(gray(128), gray(128)), 1e-12)
        assertEquals(gray(128), MediaOverlayContrast.composite(gray(255), gray(0), 128))
        assertEquals(gray(30), MediaOverlayContrast.composite(gray(0), gray(128), 196))
    }

    @Test fun `少量极亮背景不会压过多数暗区的清晰度`() {
        val samples = List(81) { MediaOverlayContrast.Sample(gray(40)) } +
            List(19) { MediaOverlayContrast.Sample(gray(255)) }
        val result = MediaOverlayContrast.choose(samples)
        assertFalse(result.dark)
        assertEquals(.81, result.whiteCoverage, 1e-12)
        assertEquals(.19, result.blackCoverage, 1e-12)
    }

    @Test fun `镂空文字比较露出的照片与相邻填充而非假定普通白字`() {
        val whiteHole = MediaOverlayContrast.Sample(gray(255), gray(128), 216)
        assertTrue(MediaOverlayContrast.choose(listOf(whiteHole), 4.5).dark)
        val blackHole = MediaOverlayContrast.Sample(gray(0), gray(128), 216)
        assertFalse(MediaOverlayContrast.choose(listOf(blackHole), 4.5).dark)
    }

    @Test fun `低权重边缘不会取代图形主体且空样本默认白`() {
        assertTrue(MediaOverlayContrast.choose(listOf(
            MediaOverlayContrast.Sample(gray(255), weight = 1.0),
            MediaOverlayContrast.Sample(gray(0), weight = .1))).dark)
        assertFalse(MediaOverlayContrast.choose(emptyList()).dark)
        assertFalse(MediaOverlayContrast.choose(listOf(MediaOverlayContrast.Sample(gray(255), alpha = 0))).dark)
    }

    @Test fun `未达参考值时仍能选出更清楚的颜色`() {
        val result = MediaOverlayContrast.choose(listOf(MediaOverlayContrast.Sample(gray(128), alpha = 96)), 4.5)
        assertEquals(0.0, result.blackCoverage, 0.0)
        assertEquals(0.0, result.whiteCoverage, 0.0)
        assertTrue(result.dark)
    }
}
