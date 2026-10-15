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

    @Test fun `滚动经过细碎黑白纹理时保持原色而明显变化立即切换`() {
        fun split(white: Int) = MediaOverlayContrast.choose(
            List(white) { MediaOverlayContrast.Sample(gray(255)) } +
                List(100 - white) { MediaOverlayContrast.Sample(gray(0)) })
        var current = false
        for (white in listOf(49, 51, 50, 52, 49, 51)) {
            current = split(white).withHysteresis(current)
            assertFalse(current)
        }
        current = split(60).withHysteresis(current)
        assertTrue(current)
        for (white in listOf(51, 49, 50, 48, 51)) {
            current = split(white).withHysteresis(current)
            assertTrue(current)
        }
        assertFalse(split(40).withHysteresis(current))
        assertTrue(split(51).withHysteresis(null))
    }

    @Test fun `均匀中灰临界区域双向稳定且大幅明暗变化无需等待`() {
        fun uniform(value: Int) = MediaOverlayContrast.choose(listOf(MediaOverlayContrast.Sample(gray(value))))
        assertFalse(uniform(114).withHysteresis(false))
        assertTrue(uniform(113).withHysteresis(true))
        assertTrue(uniform(160).withHysteresis(false))
        assertFalse(uniform(80).withHysteresis(true))
    }

    @Test fun `半透明媒体和操作栏覆盖后的颜色决定可辨认程度`() {
        val whiteMediaOnDarkNote = MediaOverlayContrast.composite(gray(255), gray(20), 196)
        assertTrue(MediaOverlayContrast.choose(listOf(MediaOverlayContrast.Sample(whiteMediaOnDarkNote))).dark)
        val opaqueDarkToolbar = MediaOverlayContrast.composite(gray(20), whiteMediaOnDarkNote, 255)
        assertFalse(MediaOverlayContrast.choose(listOf(MediaOverlayContrast.Sample(opaqueDarkToolbar))).dark)
        assertEquals(gray(20), MediaOverlayContrast.composite(gray(255), gray(20), 0))
    }

    @Test fun `按钮等权使大图形不能压过其余按钮`() {
        val result = MediaOverlayContrast.chooseEqualGroups(listOf(
            List(128) { MediaOverlayContrast.Sample(gray(255)) },
            listOf(MediaOverlayContrast.Sample(gray(0))),
            listOf(MediaOverlayContrast.Sample(gray(0)))))
        assertFalse(result.dark)
        assertEquals(2.0 / 3, result.whiteCoverage, 1e-12)
        assertEquals(1.0 / 3, result.blackCoverage, 1e-12)
    }

    @Test fun `增加一个按钮的采样密度不改变共同结果`() {
        val bright = listOf(MediaOverlayContrast.Sample(gray(220)),
            MediaOverlayContrast.Sample(gray(50), weight = .25))
        val dark = listOf(MediaOverlayContrast.Sample(gray(32)))
        val first = MediaOverlayContrast.chooseEqualGroups(listOf(bright, dark))
        val denser = MediaOverlayContrast.chooseEqualGroups(listOf(List(32) { bright }.flatten(), dark))
        assertEquals(first.dark, denser.dark)
        assertEquals(first.blackCoverage, denser.blackCoverage, 1e-12)
        assertEquals(first.whiteCoverage, denser.whiteCoverage, 1e-12)
        assertEquals(first.logContrastAdvantage, denser.logContrastAdvantage, 1e-12)
    }

    @Test fun `透明与空按钮不增加权重且共同结果保留切换余量`() {
        val white = listOf(MediaOverlayContrast.Sample(gray(255)))
        val black = listOf(MediaOverlayContrast.Sample(gray(0)))
        val invisible = List(128) { MediaOverlayContrast.Sample(gray(255), alpha = 0) }
        val balanced = MediaOverlayContrast.chooseEqualGroups(listOf(white, black, invisible, emptyList()))
        assertEquals(.5, balanced.blackCoverage, 1e-12)
        assertEquals(.5, balanced.whiteCoverage, 1e-12)
        assertTrue(balanced.withHysteresis(true))
        assertFalse(balanced.withHysteresis(false))
        assertTrue(MediaOverlayContrast.chooseEqualGroups(listOf(white, white, black)).withHysteresis(false))
        assertFalse(MediaOverlayContrast.chooseEqualGroups(listOf(white, black, black)).withHysteresis(true))
    }

    @Test fun `文字与图标共同评分时保留各自参考对比度`() {
        val result = MediaOverlayContrast.chooseGroups(listOf(
            MediaOverlayContrast.Group(listOf(MediaOverlayContrast.Sample(gray(128))), 4.5),
            MediaOverlayContrast.Group(listOf(MediaOverlayContrast.Sample(gray(255))), 3.0)))
        assertEquals(.5, result.blackCoverage, 1e-12)
        assertEquals(0.0, result.whiteCoverage, 1e-12)
        assertTrue(result.dark)
    }

    @Test fun `镂空标记等权参与统一配色且不被大图标覆盖`() {
        val badge = MediaOverlayContrast.Group(listOf(MediaOverlayContrast.Sample(gray(255), gray(128), 216)), 4.5)
        val icon = MediaOverlayContrast.Group(List(128) { MediaOverlayContrast.Sample(gray(0)) })
        val result = MediaOverlayContrast.chooseGroups(listOf(badge, badge, icon))
        assertTrue(result.dark)
        assertEquals(2.0 / 3, result.blackCoverage, 1e-12)
        assertEquals(1.0 / 3, result.whiteCoverage, 1e-12)
    }
}
