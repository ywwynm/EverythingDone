package com.ywwynm.everythingdone.views.reveal

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 边框路径的几何断言。Path/PathMeasure 是框架实现，JVM 单测里不可用，
 * 因此让生产代码把边角写进 [ShiningBorder.BorderPathSink]，这里用同一组调用
 * 还原折线并按弧长离散，验证起点、走向与闭合，不重写一遍边角计算。
 */
class ShiningBorderPathTest {

    private class Sample(val x: Float, val y: Float)

    /** 与 Path 的 `arcTo(..., forceMoveTo = false)` 等价：圆弧起点不连续时自动补直线。 */
    private class PolylineSink : ShiningBorder.BorderPathSink {
        val points = ArrayList<Sample>()

        override fun moveTo(x: Float, y: Float) {
            points.add(Sample(x, y))
        }

        override fun lineTo(x: Float, y: Float) {
            points.add(Sample(x, y))
        }

        override fun arcTo(left: Float, top: Float, right: Float, bottom: Float,
                           startAngle: Float, sweepAngle: Float) {
            val rx = (right - left) / 2.0
            val ry = (bottom - top) / 2.0
            val ox = left + rx
            val oy = top + ry
            for (step in 0..ARC_STEPS) {
                val radians = Math.toRadians(startAngle + sweepAngle * step / ARC_STEPS.toDouble())
                points.add(Sample((ox + rx * cos(radians)).toFloat(), (oy + ry * sin(radians)).toFloat()))
            }
        }
    }

    private class Polyline(points: List<Sample>) {
        private val points = points
        private val cumulative = DoubleArray(points.size)

        init {
            for (i in 1 until points.size) {
                cumulative[i] = cumulative[i - 1] + hypot(
                    (points[i].x - points[i - 1].x).toDouble(),
                    (points[i].y - points[i - 1].y).toDouble()
                )
            }
        }

        val length: Double get() = cumulative.last()

        /** 与 ShiningBorder.PathFrame 一致：沿弧长等距取点，间距约 1 px。 */
        fun discretize(): List<Sample> {
            val count = length.toInt() + 1
            return (0 until count).map { at(length * it / (count - 1)) }
        }

        private fun at(distance: Double): Sample {
            if (distance <= 0.0) return points.first()
            if (distance >= length) return points.last()
            var i = 1
            while (i < cumulative.size && cumulative[i] < distance) i++
            val span = cumulative[i] - cumulative[i - 1]
            val t = if (span <= 0.0) 0.0 else (distance - cumulative[i - 1]) / span
            val a = points[i - 1]
            val b = points[i]
            return Sample((a.x + (b.x - a.x) * t).toFloat(), (a.y + (b.y - a.y) * t).toFloat())
        }
    }

    private fun bottomRight(radius: Float): Polyline = PolylineSink().also {
        ShiningBorder.addRoundRectCWFromBottomRight(it, LEFT, TOP, RIGHT, BOTTOM, radius)
    }.let { Polyline(it.points) }

    private fun assertStartsAlongBottomEdgeToTheLeft(radius: Float) {
        val frame = bottomRight(radius).discretize()
        val first = frame.first()
        assertEquals("起点在底边上", BOTTOM, first.y, TOLERANCE)
        assertEquals("起点在右下角的圆角切点", RIGHT - radius, first.x, TOLERANCE)
        assertTrue("起点必须落在右下角附近", hypot((RIGHT - first.x).toDouble(), (BOTTOM - first.y).toDouble()) <= radius + TOLERANCE)

        for (i in 1..EDGE_SAMPLES) {
            assertEquals("沿底边前进时 y 不变（第 $i 点）", BOTTOM, frame[i].y, TOLERANCE)
            assertTrue("沿底边向左，x 必须递减（第 $i 点：${frame[i].x} 应小于 ${frame[i - 1].x}）",
                frame[i].x < frame[i - 1].x)
        }
        assertTrue("首段应明显向左推进", frame[0].x - frame[EDGE_SAMPLES].x > EDGE_SAMPLES * 0.5f)

        val last = frame.last()
        assertEquals("终点回到起点 x", first.x, last.x, CLOSE_TOLERANCE)
        assertEquals("终点回到起点 y", first.y, last.y, CLOSE_TOLERANCE)
    }

    @Test fun `直角时从右下角起步沿底边向左并闭合`() {
        assertStartsAlongBottomEdgeToTheLeft(0f)
    }

    @Test fun `圆角时从右下角起步沿底边向左并闭合`() {
        assertStartsAlongBottomEdgeToTheLeft(CORNER_RADIUS)
    }

    @Test fun `右下起点路径绕行一整圈且四条边依次经过`() {
        for (radius in floatArrayOf(0f, CORNER_RADIUS)) {
            val polyline = bottomRight(radius)
            val width = RIGHT - LEFT
            val height = BOTTOM - TOP
            val expected = 2.0 * (width - 2 * radius) + 2.0 * (height - 2 * radius) + 2.0 * PI * radius
            assertEquals("周长应为圆角矩形周长（radius=$radius）", expected, polyline.length, 1.0)

            // 四分之一处依次落在左下、左上、右上，顺序即 底→左→顶→右。
            val frame = polyline.discretize()
            val quarter = frame[frame.size / 4]
            val half = frame[frame.size / 2]
            val threeQuarters = frame[frame.size * 3 / 4]
            assertTrue("四分之一处应已走到左边（x=${quarter.x}）", quarter.x < LEFT + width * 0.25f)
            assertTrue("四分之一处应高于起点（y=${quarter.y}）", quarter.y < BOTTOM - height * 0.1f)
            assertTrue("一半处应到顶边附近（y=${half.y}）", half.y < TOP + height * 0.25f)
            assertTrue("四分之三处应走到右边（x=${threeQuarters.x}）", threeQuarters.x > RIGHT - width * 0.25f)
        }
    }

    @Test fun `默认左下起点仍沿左边向上，卡片边框走向不变`() {
        for (radius in floatArrayOf(0f, CORNER_RADIUS)) {
            val frame = PolylineSink().also {
                ShiningBorder.addRoundRectCW(it, LEFT, TOP, RIGHT, BOTTOM, radius)
            }.let { Polyline(it.points) }.discretize()
            val first = frame.first()
            assertEquals("起点在左边上（radius=$radius）", LEFT, first.x, TOLERANCE)
            assertEquals("起点在左下角的圆角切点（radius=$radius）", BOTTOM - radius, first.y, TOLERANCE)
            for (i in 1..EDGE_SAMPLES) {
                assertEquals("沿左边前进时 x 不变（radius=$radius，第 $i 点）", LEFT, frame[i].x, TOLERANCE)
                assertTrue("沿左边向上，y 必须递减（radius=$radius，第 $i 点）", frame[i].y < frame[i - 1].y)
            }
            assertEquals("终点回到起点 x", first.x, frame.last().x, CLOSE_TOLERANCE)
            assertEquals("终点回到起点 y", first.y, frame.last().y, CLOSE_TOLERANCE)
        }
    }

    @Test fun `两种起点角覆盖同一条闭合路径`() {
        for (radius in floatArrayOf(0f, CORNER_RADIUS)) {
            val cw = PolylineSink().also {
                ShiningBorder.addRoundRectCW(it, LEFT, TOP, RIGHT, BOTTOM, radius)
            }.let { Polyline(it.points) }
            val fromBottomRight = bottomRight(radius)
            assertTrue("两种起点角的路径长度必须一致（radius=$radius）",
                abs(cw.length - fromBottomRight.length) < 1.0)
        }
    }

    private companion object {
        const val LEFT = 0f
        const val TOP = 0f
        const val RIGHT = 1080f
        const val BOTTOM = 2400f
        const val CORNER_RADIUS = 48f
        const val ARC_STEPS = 180
        const val EDGE_SAMPLES = 24
        const val TOLERANCE = 0.01f
        const val CLOSE_TOLERANCE = 1.5f
    }
}
