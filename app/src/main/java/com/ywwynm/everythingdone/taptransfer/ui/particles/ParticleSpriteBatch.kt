package com.ywwynm.everythingdone.taptransfer.ui.particles

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import kotlin.math.sqrt

/**
 * 把成千上万个粒子合成一次 `Canvas.drawVertices` 画出来。
 *
 * 每个粒子是一个沿运动方向拉长的四边形，贴一张柔边圆点的精灵图：静止时是圆点，运动时被拉成
 * 头亮尾淡的拖尾——拖尾长度取自粒子这一帧走过的距离，所以越快拖得越长，这是流动感的主要来源。
 * 颜色逐顶点给出（头部原色、尾部压低透明度），与精灵图相乘。
 *
 * 一次调用画完所有粒子，而不是每个粒子一次 `drawLine`：几千次绘制调用会把 RenderThread 拖慢，
 * 而且不同颜色的画笔无法合批。`drawVertices` 从 API 29 起支持硬件加速，碰一碰本身也只在
 * API 29 以上开放（决策 2026-09-24「不支持碰一碰的设备直接隐藏两个入口」）。
 */
class ParticleSpriteBatch(private val capacity: Int = DEFAULT_CAPACITY) {

    init {
        // 下标用 short 存，每个粒子 4 个顶点
        require(capacity in 1..MAX_CAPACITY) { "容量越界：$capacity" }
    }

    private val verts = FloatArray(capacity * 8)
    private val texs = FloatArray(capacity * 8)
    private val colors = IntArray(capacity * 4)
    private val indices = ShortArray(capacity * 6)
    private var count = 0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        isDither = false
        shader = BitmapShader(SPRITE, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }

    init {
        val s = SPRITE_SIZE.toFloat()
        for (i in 0 until capacity) {
            val t = i * 8
            texs[t] = 0f; texs[t + 1] = 0f
            texs[t + 2] = s; texs[t + 3] = 0f
            texs[t + 4] = s; texs[t + 5] = s
            texs[t + 6] = 0f; texs[t + 7] = s
            val v = (i * 4).toShort()
            val k = i * 6
            indices[k] = v
            indices[k + 1] = (v + 1).toShort()
            indices[k + 2] = (v + 2).toShort()
            indices[k + 3] = v
            indices[k + 4] = (v + 2).toShort()
            indices[k + 5] = (v + 3).toShort()
        }
    }

    private var canvas: Canvas? = null

    fun begin(canvas: Canvas) {
        this.canvas = canvas
        count = 0
    }

    /**
     * 加一个粒子：头在 (headX, headY)，尾在 (tailX, tailY)，[radius] 是圆点实心部分的半径（像素），
     * [color] 是 ARGB。尾部的透明度乘以 [tailAlpha]。
     */
    fun add(
        headX: Float, headY: Float,
        tailX: Float, tailY: Float,
        radius: Float,
        color: Int,
        tailAlpha: Float = DEFAULT_TAIL_ALPHA
    ) {
        if ((color ushr 24) == 0 || radius <= 0f) {
            return
        }
        if (count == capacity) {
            flush()
        }
        // 四边形的半宽要比实心半径大：精灵图外圈是柔边光晕
        val half = radius / SPRITE_CORE_FRACTION
        var ax = headX - tailX
        var ay = headY - tailY
        val len = sqrt(ax * ax + ay * ay)
        if (len > 1e-3f) {
            ax /= len
            ay /= len
        } else {
            ax = 1f
            ay = 0f
        }
        val nx = -ay * half
        val ny = ax * half
        val ex = ax * half
        val ey = ay * half

        val v = count * 8
        // 顶点顺序与贴图坐标一致：尾左、头左、头右、尾右
        verts[v] = tailX - ex + nx; verts[v + 1] = tailY - ey + ny
        verts[v + 2] = headX + ex + nx; verts[v + 3] = headY + ey + ny
        verts[v + 4] = headX + ex - nx; verts[v + 5] = headY + ey - ny
        verts[v + 6] = tailX - ex - nx; verts[v + 7] = tailY - ey - ny

        val tail = scaleAlpha(color, tailAlpha)
        val c = count * 4
        colors[c] = tail
        colors[c + 1] = color
        colors[c + 2] = color
        colors[c + 3] = tail
        count++
    }

    fun end() {
        flush()
        canvas = null
    }

    private fun flush() {
        val c = canvas ?: return
        if (count == 0) {
            return
        }
        c.drawVertices(
            Canvas.VertexMode.TRIANGLES,
            count * 8, verts, 0,
            texs, 0,
            colors, 0,
            indices, 0, count * 6,
            paint
        )
        count = 0
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 4096
        private const val MAX_CAPACITY: Int = 8191

        private const val DEFAULT_TAIL_ALPHA: Float = 0.12f

        private const val SPRITE_SIZE: Int = 64

        /** 精灵图里实心圆点占半边长的比例，其余是光晕。 */
        private const val SPRITE_CORE_FRACTION: Float = 0.42f

        private val SPRITE: Bitmap by lazy { createSprite() }

        /** 白色圆点：实心核 + 一圈淡光晕。颜色由顶点色相乘得到。 */
        private fun createSprite(): Bitmap {
            val size = SPRITE_SIZE
            val pixels = IntArray(size * size)
            val center = (size - 1) / 2f
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val dx = (x - center) / center
                    val dy = (y - center) / center
                    val r = sqrt(dx * dx + dy * dy)
                    // 实心核的边缘只留一点抗锯齿的过渡，光晕很淡：粒子是一颗颗清楚的点，不是一团虚光
                    val core = 1f - smoothstep(SPRITE_CORE_FRACTION - 0.05f, SPRITE_CORE_FRACTION + 0.04f, r)
                    val halo = 0.16f * (1f - smoothstep(0.3f, 0.95f, r))
                    val a = (maxOf(core, halo) * 255f + 0.5f).toInt().coerceIn(0, 255)
                    pixels[y * size + x] = (a shl 24) or 0xFFFFFF
                }
            }
            return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        }

        private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
            val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        @JvmStatic
        fun scaleAlpha(color: Int, factor: Float): Int {
            val a = ((color ushr 24) * factor.coerceIn(0f, 1f) + 0.5f).toInt()
            return (a shl 24) or (color and 0x00FFFFFF)
        }
    }
}
