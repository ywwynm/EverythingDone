package com.ywwynm.everythingdone.views.particledismiss

import android.graphics.RectF
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 消散的终点：颗粒不再四散淡出，而是汇成流、被吸进终点（碰一碰的 NFC 天线位置）。
 * 倒放（出现）时正好相反——颗粒从终点流出，拼回面板。
 *
 * 终点可以不止一处：给了 [framePx] 时，一部分微片（[frameShare]）的终点改在这个矩形离它最近的
 * 那条边上（沿边带一点错开），接收方用它表示「屏幕四周的颗粒也流进来」。每片去哪一处由它自己的
 * 随机数决定，CPU（改写寿命）与着色器（`sink_target`）按同一套算法各算一遍。
 *
 * 坐标与 [ParticleDismissSpec.originXPx] 同一坐标系（动画层像素）。只有碰一碰用它，
 * 其余动画的规格里恒为 null，着色器也不编进这一段（`#ifdef SINK`）。
 */
internal class ParticleSink(
    val xPx: Float,
    val yPx: Float,
    val framePx: RectF? = null,
    val frameShare: Float = 0f
) {

    /** 换到材料坐标之后的终点几何：[fromPixels][ParticleMicroflakeRenderer.Shared.fromPixels] 与着色器共用。 */
    class Geometry(
        val x: Float, val y: Float,
        val frameLeft: Float, val frameTop: Float, val frameRight: Float, val frameBottom: Float,
        /** 流向边框的片的占比；0 表示全部流向 ([x], [y])。 */
        val frameShare: Float,
        /** 沿边错开的全幅（材料单位）：终点在「最近点 ± 一半」之内均匀分布。 */
        val frameJitter: Float
    )

    /** 以快照左上角为原点、按 [scale]（像素 / 材料单位）换到材料坐标。 */
    fun toMaterial(originXPx: Float, originYPx: Float, scale: Float): Geometry {
        val frame = framePx
        if (frame == null || frameShare <= 0f) {
            val x = (xPx - originXPx) / scale
            val y = (yPx - originYPx) / scale
            return Geometry(x, y, x, y, x, y, 0f, 0f)
        }
        val left = (frame.left - originXPx) / scale
        val top = (frame.top - originYPx) / scale
        val right = (frame.right - originXPx) / scale
        val bottom = (frame.bottom - originYPx) / scale
        return Geometry(
            (xPx - originXPx) / scale, (yPx - originYPx) / scale,
            left, top, right, bottom,
            frameShare.coerceIn(0f, 1f), FRAME_JITTER * min(right - left, bottom - top)
        )
    }

    companion object {
        /** 最后一片最晚在整段时间轴（1 秒）的这一刻抵达终点。 */
        const val ARRIVAL_END = .96f

        /**
         * 释放整体提前的比例。原模型的释放铺满 0–0.8 秒，照搬的话最后释放的那批只剩
         * 0.16 秒飞到终点，会被硬拽进去；按比例压缩，它们也有足够的飞行时间。
         *
         * 压得太狠，剥离就不再是从一侧推过去，而是整张卡片几乎同时散开（0.62 时三星上整张卡片
         * 0.4 秒内全部释放，2026-09-24 实测）；0.75 让最晚的那批仍有约 0.36 秒飞行。
         */
        const val RELEASE_SCALE = .75f

        /** 飞行时长：基础值 + 每个短边距离加的时长（秒）。 */
        private const val FLIGHT_BASE = .30f
        private const val FLIGHT_PER_SPAN = .11f

        /** 飞行时长的随机幅度（±），同一处释放的颗粒不会齐步到达。 */
        private const val FLIGHT_JITTER = .18f

        /** 最短寿命：比原模型的下限（0.11 秒）略长，刚释放就被吸走的颗粒也看得见。 */
        private const val MIN_LIFE = .14f

        /**
         * 绕终点的切向分量（相对径向速度的比例）。流束因此弯成弧线汇入，而不是一束直线；
         * 着色器里它随飞行进度衰减，最后径直收进去。
         */
        const val SWIRL = .30f

        /** 沿边错开的全幅，占边框短边的比例：同一处的颗粒从一段边上散开流入，而不是垂直于边的一条线。 */
        private const val FRAME_JITTER = .36f

        /**
         * 按终点改写材料的释放时刻与寿命。
         *
         * 寿命改成「从出生到抵达终点」的时长之后，回放的历史范围、倒放积分的提前返回、
         * 绘制时的淡出都以寿命为准，自然与抵达对齐——这些地方一处都不用改。
         *
         * @param values 材料数组，每片 12 个浮点：0–1 出生位置、2 出生时刻、6 寿命、8–11 随机数。
         * @param span 面板短边（材料坐标）。
         */
        fun shape(values: FloatArray, sink: Geometry, span: Float) {
            require(values.size % 12 == 0)
            val unit = max(span, 1f)
            val target = FloatArray(2)
            var p = 0
            while (p < values.size) {
                target(values, p, sink, target)
                val born = values[p + 2] * RELEASE_SCALE
                val distance = hypot(target[0] - values[p], target[1] - values[p + 1]) / unit
                val jitter = 1f + FLIGHT_JITTER * (2f * values[p + 8] - 1f)
                val flight = (FLIGHT_BASE + FLIGHT_PER_SPAN * distance) * jitter
                // 各片的到达时刻错开一点，最后一刻不会整批同时熄灭
                val latest = ARRIVAL_END - .04f * values[p + 10]
                val arrival = min(born + flight, latest)
                values[p + 2] = born
                values[p + 6] = max(arrival - born, MIN_LIFE)
                p += 12
            }
        }

        /**
         * 第 [p] / 12 片的终点，写进 [out]。与 `step.comp` 的 `sink_target` 逐项对应：
         * 用哪个随机数、怎么比较、平局时先取哪条边，两边必须一致。
         */
        fun target(values: FloatArray, p: Int, sink: Geometry, out: FloatArray) {
            val pick = fract(values[p + 8] * 7.31f + values[p + 11] * 3.17f)
            if (pick >= sink.frameShare) {
                out[0] = sink.x
                out[1] = sink.y
                return
            }
            val x = values[p]
            val y = values[p + 1]
            val jitter = (fract(values[p + 9] * 5.13f + values[p + 10] * 1.91f) - .5f) * sink.frameJitter
            val left = x - sink.frameLeft
            val right = sink.frameRight - x
            val top = y - sink.frameTop
            val bottom = sink.frameBottom - y
            val nearest = min(min(left, right), min(top, bottom))
            when (nearest) {
                left -> { out[0] = sink.frameLeft; out[1] = (y + jitter).coerceIn(sink.frameTop, sink.frameBottom) }
                right -> { out[0] = sink.frameRight; out[1] = (y + jitter).coerceIn(sink.frameTop, sink.frameBottom) }
                top -> { out[0] = (x + jitter).coerceIn(sink.frameLeft, sink.frameRight); out[1] = sink.frameTop }
                else -> { out[0] = (x + jitter).coerceIn(sink.frameLeft, sink.frameRight); out[1] = sink.frameBottom }
            }
        }

        private fun fract(v: Float): Float = v - floor(v)
    }
}
