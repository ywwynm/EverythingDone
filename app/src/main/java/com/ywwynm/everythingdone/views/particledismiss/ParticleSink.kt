package com.ywwynm.everythingdone.views.particledismiss

import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 消散的终点：颗粒不再四散淡出，而是汇成流、被吸进终点（碰一碰的 NFC 天线位置）。
 * 倒放（出现）时正好相反——颗粒从终点流出，拼回面板。
 *
 * 终点可以不止一处：给了 [framePx] 时，一部分微片（[frameShare]）的终点改在这个矩形上，接收方用它
 * 表示「屏幕四周的颗粒也流进来」。每片去哪一处由它自己的随机数决定，CPU（改写寿命）与着色器按同一套
 * 算法各算一遍。
 *
 * 两种模型：
 * - [field] 为空：2026-09-24 版的终点牵引（`step.comp` 的 `#elif defined(SINK)`）——每片在寿命末端
 *   收进同一点；
 * - [field] 非空：第三版的柔性终点（`SINK_FIELD`，设计见
 *   `docs/features/thing-transfer/design-2026-09-25-particle-motion.md`）——被牵引的颗粒在同一个时间窗里
 *   整团滑向各自的落点（天线周围按瑞利分布散开、或屏幕边缘的一条带），其余按弹窗的方式途中消散。
 *   CPU 部分与桌面模拟器 `sim_model.py` 逐行对应，`ParticleSinkCrossLanguageTest` 用模拟器生成的
 *   对照数据核对。
 *
 * 坐标与 [ParticleDismissSpec.originXPx] 同一坐标系（动画层像素）。只有碰一碰用它，
 * 其余动画的规格里恒为 null，着色器也不编进这一段（`#ifdef SINK`）。
 */
internal class ParticleSink(
    val xPx: Float,
    val yPx: Float,
    val framePx: RectF? = null,
    val frameShare: Float = 0f,
    val tuning: Tuning = Tuning(),
    /** 第三版的柔性终点；为空时是旧版的终点牵引。 */
    val field: Field? = null
) {

    /**
     * 可调的几项。默认值即碰一碰当前的取值；碰一碰实际从 `shared/tap-transfer/particles.properties`
     * 读（`TapParticleParams`），桌面模拟器读同一份文件。
     */
    class Tuning(
        /** 释放时刻整体乘的比例：给最晚释放的那批留出飞行时间。 */
        val releaseScale: Float = RELEASE_SCALE,
        /** 最后一片最晚在整段时间轴（1 秒）的这一刻抵达终点。 */
        val arrivalEnd: Float = ARRIVAL_END,
        /** 飞行时长（秒）的基础值与每个短边距离加的时长。 */
        val flightBase: Float = FLIGHT_BASE,
        val flightPerSpan: Float = FLIGHT_PER_SPAN,
        /** 飞行时长的随机幅度（±）。 */
        val flightJitter: Float = FLIGHT_JITTER,
        /** 最短寿命（秒）。 */
        val minLife: Float = MIN_LIFE,
        /** 绕终点的切向分量（相对径向速度的比例），随飞行进度衰减。 */
        val swirl: Float = SWIRL,
        /** 转向终点的过渡区间（飞行进度）：`smoothstep(homingStart, homingEnd, journey)`。 */
        val homingStart: Float = HOMING_START,
        val homingEnd: Float = HOMING_END,
        /** 流向边框的颗粒沿边错开的全幅，占边框短边的比例。 */
        val frameJitter: Float = FRAME_JITTER
    )

    /**
     * 第三版的柔性终点（`sink_*` 与接收的几项，含义见 `particles.properties` 的注释）。长度一律是 dp，
     * [toMaterial] 换成材料单位；[cards] 是几张卡片各自的矩形（动画层像素），两张以上时按卡片分组。
     */
    class Field(
        /** 被牵引的颗粒抵达各自落点的时间窗（粒子时间轴 0–1）。 */
        val arrivalFirst: Float,
        val arrivalLast: Float,
        /** 整团滑行：牵引按绝对时间在 [glideStart, glideEnd] 里渐入，接近增益。 */
        val glideStart: Float,
        val glideEnd: Float,
        val glideGain: Float,
        /** 贴近落点（涡旋核内）的切向比；0 = 末段不旋转。 */
        val spiral: Float,
        /** 流向天线的颗粒里被吸入的比例（其余途中消散）。 */
        val absorbShare: Float,
        /** 天线周围落点散开的尺度（dp，瑞利分布的 1 / 0.55）。 */
        val spreadDp: Float,
        /** 途中消散的颗粒朝天线漂移（每秒若干短边）。 */
        val drift: Float,
        /** 途中消散的颗粒的寿命倍数。 */
        val freeLife: Float,
        /** 贴近天线时隐去的内、外半径（dp），寿命末端的淡出（秒），贴近时缩到的比例。 */
        val absorbInnerDp: Float,
        val absorbOuterDp: Float,
        val fadeS: Float,
        val nearScale: Float,
        /** 流向边框的颗粒落在边框向里这么宽的带里（dp）；带里的螺旋与途中卷流。 */
        val bandDp: Float,
        val bandSpiral: Float,
        val curl: Float,
        /** 涡旋核半径（dp）。 */
        val vortexDp: Float,
        /** 按卡片错开：第一张出生平移 / 最后一张抵达提前，切向倍数沿卡片顺序增加的量。 */
        val groupBorn: Float,
        val groupArrive: Float,
        val groupSwirl: Float,
        /** 光尘接手的颗粒：牵引按年龄渐入的时长、卷流倍数与取样尺度（`SINK_TARGETS`）。 */
        val handoffGlide: Float,
        val handoffCurl: Float,
        val handoffScale: Float,
        /** 来自四周与来自天线的两部分之间的扰动（dp），见 [assignSources]。 */
        val blendDp: Float,
        val cards: List<RectF> = emptyList()
    )

    /** 按卡片分组的一组：矩形（材料坐标）与四个参数（牵引时间窗平移、切向倍数、流向边框的占比、出生平移）。 */
    class Group(
        val left: Float, val top: Float, val right: Float, val bottom: Float,
        val shift: Float, val swirl: Float, val share: Float, val born: Float
    )

    /** 换到材料坐标之后的终点几何：[fromPixels][ParticleMicroflakeRenderer.Shared.fromPixels] 与着色器共用。 */
    class Geometry(
        val x: Float, val y: Float,
        val frameLeft: Float, val frameTop: Float, val frameRight: Float, val frameBottom: Float,
        /** 流向边框的片的占比；0 表示全部流向 ([x], [y])。 */
        val frameShare: Float,
        /** 沿边错开的全幅（材料单位）：终点在「最近点 ± 一半」之内均匀分布。 */
        val frameJitter: Float,
        /** 着色器的两项：切向分量与转向区间，见 [Tuning]。 */
        val swirl: Float = SWIRL,
        val homingStart: Float = HOMING_START,
        val homingEnd: Float = HOMING_END,
        /** 第三版（`SINK_FIELD`）；为 false 时以下各项不用。 */
        val field: Boolean = false,
        /** `sink_flow`：贴近落点的切向比、吸入份额、落点散布（材料单位）、自由漂移。 */
        val flow: FloatArray = FloatArray(4),
        /** `sink_band`：边框带宽（材料单位）、边框螺线、卷流、（空）。 */
        val band: FloatArray = FloatArray(4),
        /** `sink_absorb`：隐去内、外半径（材料单位）、寿命末端淡出、贴近时的缩放。 */
        val absorb: FloatArray = floatArrayOf(0f, 1f, .075f, 1f),
        /** `sink_vortex`：涡旋核半径（材料单位）。 */
        val vortex: Float = 1f,
        /** `sink_glide`：牵引渐入的时间窗与接近增益。 */
        val glide: FloatArray = floatArrayOf(0f, 0f, 1f),
        /** `sink_handoff`：被接手的颗粒的牵引渐入、卷流倍数与取样尺度。 */
        val handoff: FloatArray = floatArrayOf(.14f, 1f, 1.37f, 0f),
        val groups: List<Group> = emptyList()
    )

    /** 以快照左上角为原点、按 [scale]（像素 / 材料单位）换到材料坐标。 */
    fun toMaterial(originXPx: Float, originYPx: Float, scale: Float): Geometry {
        val f = field
        val frame = framePx
        if (f != null) return fieldGeometry(f, frame, originXPx, originYPx, scale)
        if (frame == null || frameShare <= 0f) {
            val x = (xPx - originXPx) / scale
            val y = (yPx - originYPx) / scale
            return Geometry(x, y, x, y, x, y, 0f, 0f, tuning.swirl, tuning.homingStart, tuning.homingEnd)
        }
        val left = (frame.left - originXPx) / scale
        val top = (frame.top - originYPx) / scale
        val right = (frame.right - originXPx) / scale
        val bottom = (frame.bottom - originYPx) / scale
        return Geometry(
            (xPx - originXPx) / scale, (yPx - originYPx) / scale,
            left, top, right, bottom,
            frameShare.coerceIn(0f, 1f), tuning.frameJitter * min(right - left, bottom - top),
            tuning.swirl, tuning.homingStart, tuning.homingEnd
        )
    }

    /** `sim_model.sink_geometry` 的第三版分支。 */
    private fun fieldGeometry(f: Field, frame: RectF?, originXPx: Float, originYPx: Float, scale: Float): Geometry {
        val unit = ParticleMicroflakeModel.DP_UNITS.toFloat()
        val x = (xPx - originXPx) / scale
        val y = (yPx - originYPx) / scale
        val share = if (frame != null) frameShare.coerceIn(0f, 1f) else 0f
        val flow = floatArrayOf(f.spiral, f.absorbShare, f.spreadDp * unit, f.drift)
        val band = floatArrayOf(f.bandDp * unit, f.bandSpiral, f.curl, 0f)
        val absorb = floatArrayOf(f.absorbInnerDp * unit, f.absorbOuterDp * unit, f.fadeS, f.nearScale)
        val glide = floatArrayOf(f.glideStart, f.glideEnd, f.glideGain)
        val handoff = floatArrayOf(f.handoffGlide, f.handoffCurl, f.handoffScale, 0f)
        val groups = cardGroups(f, share, originXPx, originYPx, scale)
        if (frame == null || share <= 0f) {
            return Geometry(x, y, x, y, x, y, 0f, 0f, tuning.swirl, tuning.homingStart, tuning.homingEnd,
                true, flow, band, absorb, f.vortexDp * unit, glide, handoff, groups)
        }
        val left = (frame.left - originXPx) / scale
        val top = (frame.top - originYPx) / scale
        val right = (frame.right - originXPx) / scale
        val bottom = (frame.bottom - originYPx) / scale
        return Geometry(x, y, left, top, right, bottom, share, tuning.frameJitter * min(right - left, bottom - top),
            tuning.swirl, tuning.homingStart, tuning.homingEnd,
            true, flow, band, absorb, f.vortexDp * unit, glide, handoff, groups)
    }

    /**
     * `sim_model.card_groups`：几张卡片同时拼出时，第一张出生最晚（倒放时最先拼好）、后面的依次更早离开
     * 光尘带；切向倍数沿卡片顺序递变。流向边框的占比各组相同（哪一片来自四周按远近分配，见 [assignSources]）。
     * 一张卡片时不分组；最多四组（着色器里是长度 4 的数组）。
     */
    private fun cardGroups(f: Field, share: Float, originXPx: Float, originYPx: Float, scale: Float): List<Group> {
        if (f.cards.size < 2) return emptyList()
        val n = min(f.cards.size, 4)
        return (0 until n).map { g ->
            val t = g.toFloat() / (n - 1)
            val r = f.cards[g]
            Group((r.left - originXPx) / scale, (r.top - originYPx) / scale,
                (r.right - originXPx) / scale, (r.bottom - originYPx) / scale,
                -f.groupArrive * t, 1f + f.groupSwirl * t, share.coerceIn(0f, 1f), f.groupBorn * (1f - t))
        }
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
        const val FLIGHT_BASE = .30f
        const val FLIGHT_PER_SPAN = .11f

        /** 飞行时长的随机幅度（±），同一处释放的颗粒不会齐步到达。 */
        const val FLIGHT_JITTER = .18f

        /** 最短寿命：比原模型的下限（0.11 秒）略长，刚释放就被吸走的颗粒也看得见。 */
        const val MIN_LIFE = .14f

        /**
         * 绕终点的切向分量（相对径向速度的比例）。流束因此弯成弧线汇入，而不是一束直线；
         * 着色器里它随飞行进度衰减，最后径直收进去。
         */
        const val SWIRL = .30f

        /** 转向终点的过渡区间（飞行进度）：前段仍按剥离与流场走，此后逐渐转向终点。 */
        const val HOMING_START = .08f
        const val HOMING_END = .50f

        /** 沿边错开的全幅，占边框短边的比例：同一处的颗粒从一段边上散开流入，而不是垂直于边的一条线。 */
        const val FRAME_JITTER = .36f

        /** 来源扰动的散列盐：与随机向量（共同模型 `random_values` 的盐是种子本身）错开。 */
        const val SOURCE_SALT = 0x5EED5EED

        /**
         * 按终点改写材料的释放时刻与寿命。
         *
         * 寿命改成「从出生到抵达终点」的时长之后，回放的历史范围、倒放积分的提前返回、
         * 绘制时的淡出都以寿命为准，自然与抵达对齐——这些地方一处都不用改。
         *
         * @param values 材料数组，每片 12 个浮点：0–1 出生位置、2 出生时刻、6 寿命、8–11 随机数。
         * @param span 面板短边（材料坐标）。
         */
        fun shape(values: FloatArray, sink: Geometry, span: Float, tuning: Tuning = Tuning()) {
            require(values.size % 12 == 0)
            val unit = max(span, 1f)
            val target = FloatArray(2)
            var p = 0
            while (p < values.size) {
                target(values, p, sink, target)
                val born = values[p + 2] * tuning.releaseScale
                val distance = hypot(target[0] - values[p], target[1] - values[p + 1]) / unit
                val jitter = 1f + tuning.flightJitter * (2f * values[p + 8] - 1f)
                val flight = (tuning.flightBase + tuning.flightPerSpan * distance) * jitter
                // 各片的到达时刻错开一点，最后一刻不会整批同时熄灭
                val latest = tuning.arrivalEnd - .04f * values[p + 10]
                val arrival = min(born + flight, latest)
                values[p + 2] = born
                values[p + 6] = max(arrival - born, tuning.minLife)
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

        // ---------------------------------------------------------------- 第三版（SINK_FIELD）

        /** 第 [p] / 12 片所在的组：出生位置落进的第一个矩形，都不在时为 -1（用缺省值）。与 `sink_group` 相同。 */
        fun groupOf(values: FloatArray, p: Int, sink: Geometry): Int {
            val x = values[p]
            val y = values[p + 1]
            sink.groups.forEachIndexed { g, r ->
                if (x >= r.left && y >= r.top && x < r.right && y < r.bottom) return g
            }
            return -1
        }

        private fun groupShare(values: FloatArray, p: Int, sink: Geometry): Float {
            val g = groupOf(values, p, sink)
            return if (g >= 0) sink.groups[g].share else sink.frameShare
        }

        /** `sink_frame_bound`：这一片是否流向边框。 */
        fun frameBound(values: FloatArray, p: Int, sink: Geometry): Boolean =
            fract(values[p + 8] * 7.31f + values[p + 11] * 3.17f) < groupShare(values, p, sink)

        /** `sink_absorbed`：流向天线的这一片是否被吸入（否则途中消散）。 */
        fun absorbed(values: FloatArray, p: Int, sink: Geometry): Boolean =
            fract(values[p + 10] * 4.73f + values[p + 9] * 8.21f) < sink.flow[1]

        /**
         * `sink_field_target`：第 [p] / 12 片的落点写进 [out]（材料坐标）。流向边框的：从快照中心沿
         * 「中心 → 这一片」射到边框上，沿边错开、在带宽内向里散开；流向天线的：天线周围按瑞利分布散开。
         * [cardWidth] / [cardHeight] 是快照（材料单位）。
         */
        fun fieldTarget(values: FloatArray, p: Int, sink: Geometry, cardWidth: Float, cardHeight: Float,
                        out: FloatArray) {
            val r0 = values[p + 8]; val r1 = values[p + 9]; val r2 = values[p + 10]; val r3 = values[p + 11]
            if (frameBound(values, p, sink)) {
                val cx = cardWidth * .5f
                val cy = cardHeight * .5f
                var ux = values[p] - cx
                var uy = values[p + 1] - cy
                val length = hypot(ux, uy)
                if (length > .0001f) {
                    ux /= max(length, 1e-9f); uy /= max(length, 1e-9f)
                } else {
                    ux = 0f; uy = 1f
                }
                val tx = if (ux > .0001f) (sink.frameRight - cx) / ux else if (ux < -.0001f) (sink.frameLeft - cx) / ux else 1e9f
                val ty = if (uy > .0001f) (sink.frameBottom - cy) / uy else if (uy < -.0001f) (sink.frameTop - cy) / uy else 1e9f
                val side = tx < ty
                val reach = min(tx, ty)
                var hx = cx + ux * reach
                var hy = cy + uy * reach
                val jitter = (fract(r1 * 5.13f + r2 * 1.91f) - .5f) * sink.frameJitter
                hx = (hx + if (side) 0f else jitter).coerceIn(sink.frameLeft, sink.frameRight)
                hy = (hy + if (side) jitter else 0f).coerceIn(sink.frameTop, sink.frameBottom)
                val depth = sink.band[0] * fract(r1 * 3.93f + r0 * 6.11f).pow(1.6f)
                val ix = if (side) (if (ux > 0f) -1f else 1f) else 0f
                val iy = if (side) 0f else (if (uy > 0f) -1f else 1f)
                out[0] = hx + ix * depth
                out[1] = hy + iy * depth
                return
            }
            val angle = fract(r1 * 3.71f + r0 * 5.27f) * 6.28318f
            // 瑞利分布（二维高斯的半径）：向外逐渐变稀，没有一圈整齐的边
            val u = fract(r2 * 9.13f + r3 * 2.39f)
            val radius = min(.55f * sqrt(-2f * ln(max(1f - u, .0001f))), 2.2f) * sink.flow[2]
            out[0] = sink.x + cos(angle) * radius
            out[1] = sink.y + sin(angle) * radius
        }

        /**
         * `sim_model.frame_hit`：流向边框的颗粒各自的落点在边框上的位置（射线与边框的交点，不含沿边错开
         * 与向里的深度），双精度。写进 [out]。
         */
        fun frameHit(x: Double, y: Double, sink: Geometry, cardWidth: Float, cardHeight: Float, out: DoubleArray) {
            val left = sink.frameLeft.toDouble(); val top = sink.frameTop.toDouble()
            val right = sink.frameRight.toDouble(); val bottom = sink.frameBottom.toDouble()
            val cx = cardWidth.toDouble() * .5
            val cy = cardHeight.toDouble() * .5
            var ux = x - cx
            var uy = y - cy
            // 平方和开方（正确舍入），与模拟器逐位一致；hypot 两边的实现不保证相同
            val length = sqrt(ux * ux + uy * uy)
            if (length > 1e-4) {
                ux /= max(length, 1e-9); uy /= max(length, 1e-9)
            } else {
                ux = 0.0; uy = 1.0
            }
            val tx = if (ux > 1e-4) (right - cx) / ux else if (ux < -1e-4) (left - cx) / ux else 1e9
            val ty = if (uy > 1e-4) (bottom - cy) / uy else if (uy < -1e-4) (top - cy) / uy else 1e9
            val reach = min(tx, ty)
            out[0] = (cx + ux * reach).coerceIn(left, right)
            out[1] = (cy + uy * reach).coerceIn(top, bottom)
        }

        /**
         * 与共同模型 `random_values`、模拟器 `hash_units` 同一个 32 位整数散列：[index] 与 [salt] 按 32 位
         * 无符号整数相加，给出 [0, 1) 的均匀数。
         */
        fun hashUnit(index: Int, salt: Int): Double {
            var x = index + salt
            x = (x xor (x ushr 16)) * 0x7FEB352D
            x = (x xor (x ushr 15)) * 0x846CA68B.toInt()
            x = x xor (x ushr 16)
            return (x ushr 8).toDouble() / 16777216.0
        }

        /**
         * 接收：按远近分配每一片的来源（`sim_model.assign_sources`，逐位对应）。离屏幕边缘近的那部分由
         * 屏幕四周的颗粒拼出，离天线近的由天线处的颗粒拼出。
         *
         * 一片是否流向边框由它的随机向量决定（[frameBound]：散列 < 占比）。这里不改着色器，只在各片之间
         * 重排随机向量：散列 < 占比的那些向量（个数不变）交给「到天线的距离 − 到自己的边框落点的距离」最大
         * 的那几片。[blend]（材料单位）是加在这个差值上的扰动的标准差（四个散列均匀数之和，近似正态），
         * 两部分之间是一段混在一起的过渡。只在 [visible]（出生在快照不透明处）的颗粒里排，占比按它们算。
         * 原地修改各片的 8–11 项。
         */
        fun assignSources(values: FloatArray, sink: Geometry, cardWidth: Float, cardHeight: Float, blend: Float,
                          seed: Int, visible: BooleanArray?) {
            val n = values.size / 12
            if (n == 0 || sink.frameShare <= 0f) return
            val frameVector = BooleanArray(n) { frameBound(values, it * 12, sink) }
            val k = frameVector.count { it }
            if (k == 0 || k == n) return
            val gx = sink.x.toDouble()
            val gy = sink.y.toDouble()
            val hit = DoubleArray(2)
            val score = DoubleArray(n)
            val salt = seed xor SOURCE_SALT
            for (i in 0 until n) {
                val x = values[i * 12].toDouble()
                val y = values[i * 12 + 1].toDouble()
                frameHit(x, y, sink, cardWidth, cardHeight, hit)
                var s = sqrt((x - gx) * (x - gx) + (y - gy) * (y - gy)) -
                    sqrt((x - hit[0]) * (x - hit[0]) + (y - hit[1]) * (y - hit[1]))
                if (blend > 0f) {
                    val i4 = i * 4
                    val noise = (hashUnit(i4, salt) + hashUnit(i4 + 1, salt) + hashUnit(i4 + 2, salt) +
                        hashUnit(i4 + 3, salt) - 2.0) * sqrt(3.0)
                    s += blend.toDouble() * noise
                }
                score[i] = s
            }
            val shown = (0 until n).filter { visible == null || visible[it] }
            val hidden = (0 until n).filter { visible != null && !visible[it] }
            val kShown = min(k, floor(shown.size.toDouble() * k / n + .5).toInt())
            // 差值从大到小、下标从小到大
            val ranked = shown.sortedWith { a, b ->
                val c = score[b].compareTo(score[a])
                if (c != 0) c else a.compareTo(b)
            }
            val toFrame = BooleanArray(n)
            for (j in 0 until kShown) toFrame[ranked[j]] = true
            for (j in 0 until k - kShown) toFrame[hidden[j]] = true
            val original = values.copyOf()
            // 向量按原来的先后依次交给按下标排好的接收方
            var nextFrame = 0
            var nextOther = 0
            for (i in 0 until n) {
                val source: Int
                if (toFrame[i]) {
                    while (!frameVector[nextFrame]) nextFrame++
                    source = nextFrame++
                } else {
                    while (frameVector[nextOther]) nextOther++
                    source = nextOther++
                }
                System.arraycopy(original, source * 12 + 8, values, i * 12 + 8, 4)
            }
        }

        /**
         * `sim_model.shape_for_field`：被牵引的颗粒（流向边框的、被吸入天线的）在同一个时间窗里抵达各自的
         * 落点（与释放先后无关，所以是整团一起到）；其余颗粒保留弹窗的寿命（乘 [Field.freeLife]），途中消散。
         * 释放时刻同样整体压缩。按卡片分组时再平移出生与抵达。原地改写各片的出生时刻（2）与寿命（6）。
         */
        fun shapeField(values: FloatArray, sink: Geometry, field: Field, tuning: Tuning, cardWidth: Float,
                       cardHeight: Float) {
            require(values.size % 12 == 0)
            var p = 0
            while (p < values.size) {
                val homing = frameBound(values, p, sink) || absorbed(values, p, sink)
                val g = groupOf(values, p, sink)
                val shift = if (g >= 0) sink.groups[g].shift else 0f
                val bornShift = if (g >= 0) sink.groups[g].born else 0f
                val born = values[p + 2] * tuning.releaseScale + bornShift
                val arrival = field.arrivalFirst + (field.arrivalLast - field.arrivalFirst) * values[p + 10] + shift
                val latest = tuning.arrivalEnd - .04f * values[p + 10]
                val homeLife = max(arrival - born, tuning.minLife)
                val freeLife = max(min(values[p + 6] * field.freeLife, latest - born), tuning.minLife)
                values[p + 2] = born
                values[p + 6] = if (homing) homeLife else freeLife
                p += 12
            }
        }

        /**
         * 第三版的整形入口：先按远近分配来源（只在有边框时），再改写出生与寿命。[visible] 是各片出生处的
         * 快照是否不透明。
         */
        fun shapeForField(values: FloatArray, sink: Geometry, field: Field, tuning: Tuning, cardWidth: Float,
                          cardHeight: Float, seed: Int, visible: BooleanArray?) {
            if (sink.frameShare > 0f) {
                val blend = field.blendDp * ParticleMicroflakeModel.DP_UNITS.toFloat()
                assignSources(values, sink, cardWidth, cardHeight, blend, seed, visible)
            }
            shapeField(values, sink, field, tuning, cardWidth, cardHeight)
        }

        private fun fract(v: Float): Float = v - floor(v)
    }
}
