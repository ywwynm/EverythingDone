package com.ywwynm.everythingdone.taptransfer.ui.particles

import android.graphics.PointF
import java.util.Random
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sin

/**
 * 沿圆角矩形周长流动的一圈粒子：接收方等待时的屏幕粒子边框、凝聚出来的卡片轮廓都是它。
 *
 * 每个粒子有自己的弧长位置、速度与「车道」（离边线的距离），在车道上再叠一点旋度场的横向
 * 摆动，整圈看上去是一条有宽度、会起伏的流带，而不是一串等距的点。颜色沿周长取自调色板，
 * 调色板随时间缓慢旋转，颜色本身也在流。
 */
class PerimeterParticles(
    private val capacity: Int,
    private val noise: FlowNoise,
    val shape: RoundRectPerimeter = RoundRectPerimeter()
) {

    /** 调色板：沿周长均匀分布的几个颜色，相邻颜色之间线性插值。 */
    var palette: IntArray = intArrayOf(0xFF4FC3F7.toInt())

    /** 调色板旋转一圈的秒数。 */
    var paletteCycleS: Float = 24f

    /** 整体透明度，用于淡入淡出。 */
    var alpha: Float = 1f

    /** 不为 null 时，调色板按粒子绕这一点的角度取色，见 [colorOf]。 */
    var angularPaletteCenter: PointF? = null

    private val arc = FloatArray(capacity)
    private val speed = FloatArray(capacity)
    private val lane = FloatArray(capacity)
    private val radius = FloatArray(capacity)
    private val twinkle = FloatArray(capacity)
    private val baseAlpha = FloatArray(capacity)
    private val colorOverride = IntArray(capacity)
    private val prevX = FloatArray(capacity)
    private val prevY = FloatArray(capacity)
    private val curX = FloatArray(capacity)
    private val curY = FloatArray(capacity)
    private val placed = BooleanArray(capacity)
    private val bornS = FloatArray(capacity)

    var count: Int = 0
        private set

    private val point = FloatArray(4)
    private val curl = FloatArray(2)

    /**
     * 按周长均匀撒满 [n] 个粒子。
     *
     * @param laneWidth 流带宽度（像素），车道在 [0, laneWidth] 内随机，向内偏离边线。
     * @param speedPx 平均速度（像素/秒），正数为顺时针。
     */
    fun populate(
        n: Int, laneWidth: Float, speedPx: Float, minRadius: Float, maxRadius: Float,
        random: Random, nowS: Float
    ) {
        count = 0
        val total = n.coerceAtMost(capacity)
        for (k in 0 until total) {
            val s = shape.length * (k + random.nextFloat()) / total
            add(s, laneWidth * random.nextFloat().let { it * it }, speedPx * (0.7f + 0.6f * random.nextFloat()),
                minRadius + (maxRadius - minRadius) * random.nextFloat().let { it * it },
                0.45f + 0.55f * random.nextFloat(), 0, random, nowS)
        }
    }

    /**
     * 加一个粒子。[argb] 为 0 时颜色取自调色板，否则固定用它（例如从别处流过来的粒子保留
     * 原来的颜色）。
     */
    fun add(
        arcPosition: Float, laneOffset: Float, speedPx: Float, particleRadius: Float,
        opacity: Float, argb: Int, random: Random, nowS: Float
    ): Boolean {
        if (count == capacity) {
            return false
        }
        val i = count++
        arc[i] = arcPosition
        speed[i] = speedPx
        lane[i] = laneOffset
        radius[i] = particleRadius
        twinkle[i] = random.nextFloat() * 6.2832f
        baseAlpha[i] = opacity
        colorOverride[i] = argb
        placed[i] = false
        bornS[i] = nowS
        return true
    }

    /** 最近的弧长位置：流过来的粒子落到轮廓上时，从这里接着走。 */
    fun nearestArc(x: Float, y: Float): Float {
        // 周长不长，按 1/96 周长的步子粗找即可
        var best = 0f
        var bestD = Float.MAX_VALUE
        val steps = 96
        for (k in 0 until steps) {
            val s = shape.length * k / steps
            shape.pointAt(s, point)
            val dx = point[0] - x
            val dy = point[1] - y
            val d = dx * dx + dy * dy
            if (d < bestD) {
                bestD = d
                best = s
            }
        }
        return best
    }

    fun update(dtS: Float, nowS: Float, wobblePx: Float, noiseScale: Float) {
        val length = shape.length
        if (length <= 0f) {
            return
        }
        for (i in 0 until count) {
            // 速度随位置起伏：同一段流带时快时慢，粒子之间会追上、拉开
            noise.curl(arc[i] * noiseScale, lane[i] * 0.05f, nowS * 0.35f, curl)
            val surge = 1f + 0.35f * curl[0]
            arc[i] = (arc[i] + speed[i] * surge * dtS) % length
            if (arc[i] < 0f) {
                arc[i] += length
            }
            shape.pointAt(arc[i], point)
            val nx = -point[3]
            val ny = point[2]
            val offset = lane[i] + curl[1] * wobblePx
            val x = point[0] + nx * offset
            val y = point[1] + ny * offset
            if (placed[i]) {
                prevX[i] = curX[i]
                prevY[i] = curY[i]
            } else {
                prevX[i] = x
                prevY[i] = y
                placed[i] = true
            }
            curX[i] = x
            curY[i] = y
        }
    }

    fun draw(batch: ParticleSpriteBatch, nowS: Float, trailScale: Float, fadeInS: Float = 0.5f) {
        if (alpha <= 0.001f) {
            return
        }
        val length = shape.length
        for (i in 0 until count) {
            if (!placed[i]) {
                continue
            }
            val flicker = 0.78f + 0.22f * sin(nowS * 2.4f + twinkle[i])
            val born = ((nowS - bornS[i]) / fadeInS).coerceIn(0f, 1f)
            val a = alpha * baseAlpha[i] * flicker * born
            val base = colorOf(i, nowS, length)
            val hx = curX[i]
            val hy = curY[i]
            // 边框上粒子速度不高，拖尾额外拉长一些，流带才连得起来
            val tx = hx + (prevX[i] - hx) * trailScale
            val ty = hy + (prevY[i] - hy) * trailScale
            batch.add(hx, hy, tx, ty, radius[i], ParticleSpriteBatch.scaleAlpha(base, a))
        }
    }

    /** 当前位置与颜色，供把边框上的粒子交给涌入动画时读取。 */
    fun snapshot(index: Int, nowS: Float, out: FloatArray): Int {
        out[0] = curX[index]
        out[1] = curY[index]
        return colorOf(index, nowS, shape.length)
    }

    /**
     * 粒子的颜色：固定色优先；否则取调色板。设了 [angularPaletteCenter] 时按粒子绕这一点的角度取
     * （与 `SweepGradient` 同一口径，粒子与底下那层光的颜色处处对得上），否则按周长位置取。
     * 两种取法都随时间转动，颜色本身也在流。
     */
    private fun colorOf(i: Int, nowS: Float, length: Float): Int {
        if (colorOverride[i] != 0) {
            return colorOverride[i]
        }
        val center = angularPaletteCenter
        if (center != null) {
            val angle = atan2(curY[i] - center.y, curX[i] - center.x)
            val turn = (angle / (2f * PI.toFloat())).let { if (it < 0f) it + 1f else it }
            return paletteColor(turn - nowS / paletteCycleS)
        }
        return paletteColor(arc[i] / length + nowS / paletteCycleS)
    }

    fun radiusOf(index: Int): Float = radius[index]

    fun clear() {
        count = 0
    }

    /** 调色板在 [position]（按圈计，取小数部分）处的颜色。 */
    fun paletteColor(position: Float): Int {
        val colors = palette
        if (colors.size == 1) {
            return colors[0]
        }
        var p = position % 1f
        if (p < 0f) {
            p += 1f
        }
        val f = p * colors.size
        val k = f.toInt().coerceIn(0, colors.size - 1)
        val t = f - k
        return lerpColor(colors[k], colors[(k + 1) % colors.size], t)
    }

    companion object {
        @JvmStatic
        fun lerpColor(a: Int, b: Int, t: Float): Int {
            val u = t.coerceIn(0f, 1f)
            val aa = a ushr 24
            val ar = (a shr 16) and 0xFF
            val ag = (a shr 8) and 0xFF
            val ab = a and 0xFF
            val ba = b ushr 24
            val br = (b shr 16) and 0xFF
            val bg = (b shr 8) and 0xFF
            val bb = b and 0xFF
            val oa = (aa + (ba - aa) * u + 0.5f).toInt()
            val or = (ar + (br - ar) * u + 0.5f).toInt()
            val og = (ag + (bg - ag) * u + 0.5f).toInt()
            val ob = (ab + (bb - ab) * u + 0.5f).toInt()
            return (oa shl 24) or (or shl 16) or (og shl 8) or ob
        }
    }
}
