package com.ywwynm.everythingdone.taptransfer.ui.particles

import com.ywwynm.everythingdone.taptransfer.ui.flight.TapParticleParams
import java.util.SplittableRandom
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * 接收状态的光尘带（第三版）：屏幕四周一圈细小的微片，与弹窗消散同一种颗粒（约 1 dp 的圆角方片、会翻转
 * 闪烁、没有拖尾），沿边缘缓慢流动。与桌面模拟器 `sim_dust.py` 逐项对应；逐片的随机初值由各自的随机数
 * 发生器给出，统计上一致、不逐片相同。
 *
 * 传输开始后，每一片按自己的节奏持续从边缘向内扩散：越往里越淡，扩散到最深处后重新从边缘出现，所以屏幕
 * 边缘始终有颗粒，向内的部分是一层逐渐淡去的尘，不形成一道「内边框」。扩散的深度随进度适度增加，数据到齐
 * 之后列表让位的那段时间里继续缓慢增加，直到颗粒流进卡片。
 *
 * 状态是时间的闭式函数（位置 = 初始位置 + 速度 × 时间 + 噪声在当时的取值），不逐帧积分：可以任意跳到
 * 某一时刻，与刷新率无关。坐标是舞台像素（左上角为原点）。本类不可变；求值用 [Evaluator]，每条线程各一个。
 */
internal class TapDustBand(
    val width: Float,
    val height: Float,
    val density: Float,
    val corner: Float,
    private val params: TapParticleParams,
    private val seed: Int = SEED
) {

    val count: Int

    private val frac0: FloatArray
    private val depth0: FloatArray
    private val speed: FloatArray
    private val inward: FloatArray
    val size: FloatArray
    private val angle0: FloatArray
    private val spin: FloatArray
    private val flipRate: FloatArray
    private val flip0: FloatArray
    val baseAlpha: FloatArray
    private val rank: FloatArray
    private val wanderU: FloatArray
    private val wanderV: FloatArray
    private val shade: FloatArray
    private val pick: FloatArray

    private val insetPx = params.borderInsetDp * density
    private val perimeter = max(2f * (width + height - 4f * insetPx), 1f)

    // GPU 端（[TapDustLayer]）用的几项：与 [Evaluator] 同一套取值
    val perimeterPx: Float get() = perimeter
    val bandPx: Float get() = params.dustBandDp * density
    val spreadPx: Float get() = params.dustSpreadDp * density
    val rest: Float get() = params.dustRest
    val speedPx: Float get() = params.dustSpeedDp * density
    val wanderPx: Float get() = params.dustWanderDp * density

    /** 流场噪声的种子：GPU 端按它建同一张排列表。 */
    val noiseSeed: Int get() = seed

    init {
        val random = SplittableRandom(seed.toLong())
        val perimeterDp = 2f * (width + height) / density
        val n = (perimeterDp * params.dustPerDp).toInt().coerceIn(0, MAX_COUNT)
        count = n
        fun next() = random.nextDouble().toFloat()
        frac0 = FloatArray(n) { next() }
        depth0 = FloatArray(n) { next() }
        speed = FloatArray(n) { .55f + .9f * next() }
        val (inLo, inHi) = params.dustInwardHz.let { it[0] to it[1] }
        inward = FloatArray(n) { inLo + (inHi - inLo) * next() }
        val (sizeLo, sizeHi) = params.dustSizeDp.let { it[0] to it[1] }
        // 细粒为主，少量稍大的片（与弹窗微片的对数正态尺度相近）
        size = FloatArray(n) { (sizeLo + (sizeHi - sizeLo) * next().pow(2.2f)) * density }
        angle0 = FloatArray(n) { next() * TAU }
        spin = FloatArray(n) { (next() - .5f) * 2.4f }
        val (flipLo, flipHi) = params.dustFlipHz.let { it[0] to it[1] }
        flipRate = FloatArray(n) { (flipLo + (flipHi - flipLo) * next()) * TAU }
        flip0 = FloatArray(n) { next() * TAU }
        baseAlpha = FloatArray(n) { .55f + .45f * next() }
        // 扩散时按它先后出场，密度随进度增加
        rank = FloatArray(n) { next() }
        wanderU = FloatArray(n) { next() * 64f }
        wanderV = FloatArray(n) { next() * 64f }
        shade = FloatArray(n) { .85f + .3f * next() }
        pick = FloatArray(n) { next() }
    }

    /**
     * 逐片常数，每片 12 个 float（[TapDustLayer] 的顶点属性 0–2）：周长初相、跨带初相、沿边速度倍数、向内频率；
     * 尺寸（像素）、初始转角、转速、翻转角速度；翻转初相、自身不透明度、出场次序、取色。
     */
    fun packStatic(): FloatArray {
        val out = FloatArray(count * 12)
        for (i in 0 until count) {
            val k = i * 12
            out[k] = frac0[i]; out[k + 1] = depth0[i]; out[k + 2] = speed[i]; out[k + 3] = inward[i]
            out[k + 4] = size[i]; out[k + 5] = angle0[i]; out[k + 6] = spin[i]; out[k + 7] = flipRate[i]
            out[k + 8] = flip0[i]; out[k + 9] = baseAlpha[i]; out[k + 10] = rank[i]; out[k + 11] = pick[i]
        }
        return out
    }

    /**
     * 会变的那一份，每片 4 个 float（顶点属性 3）：漂移种子 u、v、明暗、隐去时的倒放位置（[hideTau] 为 null 或
     * 这一片没被接手时是 [NOT_HANDED]）。
     */
    fun packDynamic(hideTau: FloatArray?): FloatArray {
        val out = FloatArray(count * 4)
        for (i in 0 until count) {
            val k = i * 4
            out[k] = wanderU[i]; out[k + 1] = wanderV[i]; out[k + 2] = shade[i]
            val hide = hideTau?.getOrNull(i) ?: Float.POSITIVE_INFINITY
            out[k + 3] = if (hide.isFinite()) hide else NOT_HANDED
        }
        return out
    }

    /** 颗粒最深扩散到的像素：等待时是带宽，扩散时加上 spread × 扩散距离。 */
    fun reach(spread: Float): Float = (params.dustBandDp + params.dustSpreadDp * spread) * density

    /** 带外沿离屏幕边缘的像素。 */
    val inset: Float get() = insetPx

    /** 越往里越淡：一轮扩散的后半段逐渐隐去，回到边缘时淡入，不在循环处闪动。 */
    fun innerFade(q: Float): Float = lookup(INNER_FADE, q)

    /** 求值器：带一份自己的噪声（噪声求值要用临时量，不能跨线程共用）。 */
    inner class Evaluator {
        private val noise = FlowNoise(seed)
        private val curl = FloatArray(2)

        /** 上一次 [evaluate] / [phase] 的跨带相位与出场比例。 */
        var q = 0f
            private set
        var edgeFade = 0f
            private set

        /**
         * 只求第 [i] 片的跨带相位 [q] 与出场比例 [edgeFade]（不求位置）：不透明度只取决于这两项，逐帧出图时
         * 先用它挑掉看不见的片，省下噪声与取点。返回这一片自身的不透明度（未乘整条带的）。
         */
        fun phase(i: Int, spread: Float, diffuseT: Float): Float {
            val present = params.dustRest + (1f - params.dustRest) * min(spread, 1f)
            edgeFade = ((present - rank[i]) / .08f).coerceIn(0f, 1f)
            if (edgeFade <= 0f) {
                q = 0f
                return 0f
            }
            // 跨带位置：等待时停在初相，扩散后按各自的频率向内走一轮再回到边缘（0 → 1 循环）
            q = fract(depth0[i] + inward[i] * max(diffuseT, 0f))
            return baseAlpha[i] * innerFade(q) * edgeFade
        }

        /**
         * 第 [i] 片在舞台时钟 [t]（秒）、扩散程度 [spread]、扩散时间 [diffuseT] 下的位置，写进 [out]（x、y）。
         * [retreat] 是整体向外退去的像素（交接之后没被接手的光尘）。
         */
        fun evaluate(i: Int, t: Float, spread: Float, diffuseT: Float, retreat: Float, out: FloatArray) {
            phase(i, spread, diffuseT)
            // 跨带相位在出场比例为 0 时没有求：位置照样要按它算
            q = fract(depth0[i] + inward[i] * max(diffuseT, 0f))
            position(i, t, spread, retreat, out)
        }

        /** 在 [phase] 之后求第 [i] 片的位置（用它求出的 [q]）。 */
        fun position(i: Int, t: Float, spread: Float, retreat: Float, out: FloatArray) {
            val d = density
            val reach = (params.dustBandDp + params.dustSpreadDp * spread) * d
            val speedPx = params.dustSpeedDp * d
            var frac = frac0[i] + speed[i] * speedPx * t / perimeter
            // 噪声漂移：沿边与跨带两个方向，低频、随时间缓慢变化
            val wander = params.dustWanderDp * d
            val scale = 1f / (90f * d)
            val u = frac * perimeter * scale + wanderU[i]
            val v = q * 3f + wanderV[i]
            noise.curl(u, v, t * .45f, curl)
            // 边缘密、往里疏（q^1.6）
            val depth = insetPx + reach * lookup(DEPTH_CURVE, q) + curl[1] * wander - retreat
            frac += curl[0] * wander * .6f / perimeter
            insetRoundRect(frac, width, height, depth, corner, ROUNDING, out)
        }

        /**
         * 第 [i] 片在 ([x], [y]) 处的颜色（ARGB，不透明）：按绕屏幕中心的方位取调色板（随时间转过 [turn] 圈），
         * 每片只取相邻两色之一，逐片略有明暗。
         */
        fun color(i: Int, x: Float, y: Float, palette: IntArray, turn: Float): Int {
            val angle = atan2(y - height / 2f, x - width / 2f) / TAU
            val c = palettePick(palette, angle - turn, pick[i])
            val s = shade[i]
            val r = (((c shr 16) and 0xFF) * s).toInt().coerceAtMost(255)
            val g = (((c shr 8) and 0xFF) * s).toInt().coerceAtMost(255)
            val b = ((c and 0xFF) * s).toInt().coerceAtMost(255)
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        /** 第 [i] 片此刻的转角（弧度）。 */
        fun angle(i: Int, t: Float): Float = angle0[i] + spin[i] * t

        /** 第 [i] 片此刻正对的程度 |cos(翻转角)|：宽度按它压缩（下限 0.35），正对时出现闪光。 */
        fun facing(i: Int, t: Float): Float = abs(cos(flip0[i] + flipRate[i] * t))
    }

    companion object {
        private const val SEED = 20260925
        private const val TAU = (2.0 * PI).toFloat()

        /** 越往里角越圆：圆角半径每往里 1 像素增加这么多（光尘与柔光共用）。 */
        const val ROUNDING: Float = .6f

        /** 没被接手的光尘在「隐去时的倒放位置」一项填的值（着色器按「小于 1e8」判定被接手）。 */
        const val NOT_HANDED: Float = 1e9f

        /** 平板横屏一圈约四千 dp；上限只防参数写错。 */
        private const val MAX_COUNT = 60000

        private fun fract(x: Float): Float = x - floor(x)

        /** 查表的分辨率：跨带相位按 1/1024 取样、线性插值，与直接求值的差别远小于一个像素。 */
        private const val TABLE = 1024

        /** q^1.6：边缘密、往里疏。 */
        private val DEPTH_CURVE = FloatArray(TABLE + 1) { (it.toFloat() / TABLE).pow(1.6f) }

        private val INNER_FADE = FloatArray(TABLE + 1) {
            val q = it.toFloat() / TABLE
            val back = ((q - .3f) / .7f).coerceIn(0f, 1f).pow(1.3f)
            (1f - back) * (q / .04f).coerceIn(0f, 1f).pow(.5f)
        }

        private fun lookup(table: FloatArray, q: Float): Float {
            val f = q.coerceIn(0f, 1f) * TABLE
            val k = f.toInt().coerceAtMost(TABLE - 1)
            val w = f - k
            return table[k] + (table[k + 1] - table[k]) * w
        }

        /**
         * 按方位取调色板，但每片只取相邻两色之一（按到两色的远近随机挑），不做插值：相隔很远的两种颜色
         * （比如碰上后的青与橙）之间不会混出灰褐色，交界处是两色颗粒交错。
         */
        fun palettePick(palette: IntArray, position: Float, pick: Float): Int {
            val n = palette.size
            if (n == 1) return palette[0]
            val f = fract(position) * n
            val k = floor(f).toInt().coerceIn(0, n - 1)
            return palette[if (pick < f - k) (k + 1) % n else k]
        }

        /**
         * 宽 [width]、高 [height] 的舞台内缩 [inset] 的圆角矩形上，按周长比例 [frac]（从上边左端起、顺时针）
         * 取点，写进 [out]（x、y）。圆角半径 = max([corner] − [inset], 0) + [rounding] × [inset]：越往里角越圆；
         * 按周长比例取点，任意深度都不会在角上交叉。
         */
        fun insetRoundRect(frac: Float, width: Float, height: Float, inset: Float, corner: Float, rounding: Float,
                           out: FloatArray) {
            val f = fract(frac)
            val l = inset
            val t = inset
            val r = width - inset
            val b = height - inset
            var rad = max(corner - inset, 0f) + rounding * inset
            rad = min(rad, min(r - l, b - t) / 2f).coerceAtLeast(0f)
            val sw = max(r - l - 2f * rad, 0f)
            val sh = max(b - t - 2f * rad, 0f)
            val arc = (PI / 2.0).toFloat() * rad
            val total = 2f * sw + 2f * sh + 4f * arc
            var d = f * total
            // 上边（向右）→ 右上角 → 右边（向下）→ 右下角 → 下边（向左）→ 左下角 → 左边（向上）→ 左上角
            if (d <= sw) { out[0] = l + rad + d; out[1] = t; return }
            d -= sw
            if (d <= arc) { cornerPoint(r - rad, t + rad, -PI_2, d, rad, out); return }
            d -= arc
            if (d <= sh) { out[0] = r; out[1] = t + rad + d; return }
            d -= sh
            if (d <= arc) { cornerPoint(r - rad, b - rad, 0f, d, rad, out); return }
            d -= arc
            if (d <= sw) { out[0] = r - rad - d; out[1] = b; return }
            d -= sw
            if (d <= arc) { cornerPoint(l + rad, b - rad, PI_2, d, rad, out); return }
            d -= arc
            if (d <= sh) { out[0] = l; out[1] = b - rad - d; return }
            d -= sh
            cornerPoint(l + rad, t + rad, PI_F, d, rad, out)
        }

        private const val PI_F = PI.toFloat()
        private const val PI_2 = (PI / 2.0).toFloat()

        private fun cornerPoint(cx: Float, cy: Float, start: Float, along: Float, rad: Float, out: FloatArray) {
            val a = start + if (rad > 0f) along / rad else 0f
            out[0] = cx + cos(a) * rad
            out[1] = cy + sin(a) * rad
        }
    }
}
