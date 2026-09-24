package com.ywwynm.everythingdone.taptransfer.ui.particles

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * 碰一碰粒子用的流场：二维梯度噪声（带解析导数）与由它导出的旋度场。
 *
 * 粒子沿旋度场漂移时不会聚成团、也不会凭空散开（旋度场散度为零），看上去是在流，而不是在抖。
 * 这是「更强的流动感」在运动上的来源（preferences.md）；另一半来自拖尾绘制，见 [ParticleSpriteBatch]。
 *
 * 纯 Kotlin，不依赖 Android，便于在 JVM 上核对导数与散度。
 */
class FlowNoise(seed: Int = 1) {

    private val perm = IntArray(512)
    private val gradX = FloatArray(GRADIENTS)
    private val gradY = FloatArray(GRADIENTS)

    init {
        val p = IntArray(256) { it }
        // 线性同余打乱排列表，种子相同则流场相同，截图与录屏可以复现
        var s = seed.toLong() * 6364136223846793005L + 1442695040888963407L
        for (i in 255 downTo 1) {
            s = s * 6364136223846793005L + 1442695040888963407L
            val j = ((s ushr 33) % (i + 1)).toInt()
            val t = p[i]
            p[i] = p[j]
            p[j] = t
        }
        for (i in 0 until 512) {
            perm[i] = p[i and 255]
        }
        for (i in 0 until GRADIENTS) {
            val a = (i + 0.5) * 2.0 * Math.PI / GRADIENTS
            gradX[i] = cos(a).toFloat()
            gradY[i] = sin(a).toFloat()
        }
    }

    /**
     * 在 (x, y) 处求噪声值，并把两个偏导数写进 [out]：`out[0] = ∂n/∂x`，`out[1] = ∂n/∂y`。
     * 返回值大致落在 [-0.7, 0.7]。
     */
    fun sample(x: Float, y: Float, out: FloatArray): Float {
        val fx0 = floor(x)
        val fy0 = floor(y)
        val ix = fx0.toInt() and 255
        val iy = fy0.toInt() and 255
        val fx = x - fx0
        val fy = y - fy0

        val g00 = perm[perm[ix] + iy] % GRADIENTS
        val g10 = perm[perm[ix + 1] + iy] % GRADIENTS
        val g01 = perm[perm[ix] + iy + 1] % GRADIENTS
        val g11 = perm[perm[ix + 1] + iy + 1] % GRADIENTS

        val a = gradX[g00] * fx + gradY[g00] * fy
        val b = gradX[g10] * (fx - 1f) + gradY[g10] * fy
        val c = gradX[g01] * fx + gradY[g01] * (fy - 1f)
        val d = gradX[g11] * (fx - 1f) + gradY[g11] * (fy - 1f)

        val u = fade(fx)
        val v = fade(fy)
        val du = fadeSlope(fx)
        val dv = fadeSlope(fy)

        val k = a - b - c + d
        val value = a + u * (b - a) + v * (c - a) + u * v * k

        val kx = gradX[g00] - gradX[g10] - gradX[g01] + gradX[g11]
        val ky = gradY[g00] - gradY[g10] - gradY[g01] + gradY[g11]
        out[0] = gradX[g00] + u * (gradX[g10] - gradX[g00]) + v * (gradX[g01] - gradX[g00]) +
            u * v * kx + du * ((b - a) + v * k)
        out[1] = gradY[g00] + u * (gradY[g10] - gradY[g00]) + v * (gradY[g01] - gradY[g00]) +
            u * v * ky + dv * ((c - a) + u * k)
        return value
    }

    /**
     * 旋度场：以两层噪声的和为流函数 ψ，速度取 (∂ψ/∂y, -∂ψ/∂x)。两层以不同方向随 [time]
     * 平移，叠起来的流场会慢慢变形，而不是整体平移。结果写进 [out]，量级约为 1。
     */
    fun curl(x: Float, y: Float, time: Float, out: FloatArray) {
        sample(x + time * 0.31f, y - time * 0.23f, scratch)
        var dx = scratch[0]
        var dy = scratch[1]
        sample(x * 2.03f - time * 0.19f + 17.1f, y * 2.03f + time * 0.27f + 3.7f, scratch)
        dx += scratch[0] * 0.5f * 2.03f
        dy += scratch[1] * 0.5f * 2.03f
        out[0] = dy
        out[1] = -dx
    }

    /** 只要值、不要导数时用，例如给溶解边缘加起伏。 */
    fun value(x: Float, y: Float): Float = sample(x, y, scratch)

    private val scratch = FloatArray(2)

    companion object {
        private const val GRADIENTS: Int = 16

        private fun fade(t: Float): Float = t * t * t * (t * (t * 6f - 15f) + 10f)

        private fun fadeSlope(t: Float): Float = 30f * t * t * (t * (t - 2f) + 1f)
    }
}
