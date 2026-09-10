package com.ywwynm.everythingdone.views.particledismiss

import java.util.Random
import kotlin.math.*

/** r33 材料输入。384 dp 对应 720 逻辑坐标，纹理保留设备原始分辨率。 */
internal object ParticleMicroflakeModel {
    const val STEP = 1f / 240f
    const val DURATION = 1f
    const val CELL = 2.35f
    const val MAX_CELLS = 160_000

    data class Materials(
        val columns: Int,
        val rows: Int,
        val cellX: Float,
        val cellY: Float,
        val bodyWeight: Float,
        val values: FloatArray,
        val pigment: FloatArray,
        val statistics: Map<String, Double> = emptyMap()
    ) {
        val count get() = pigment.size
    }

    fun build(
        width: Float,
        height: Float,
        pixels: IntArray,
        pixelWidth: Int,
        pixelHeight: Int,
        directionDegrees: Float,
        seed: Long
    ): Materials {
        val started = System.nanoTime()
        require(width > 0 && height > 0 && pixels.size == pixelWidth * pixelHeight)
        val cell = max(CELL, sqrt(width * height / (MAX_CELLS - 1000)))
        val nx = ceil(width / cell).toInt().coerceAtLeast(2)
        val ny = ceil(height / cell).toInt().coerceAtLeast(2)
        val count = nx * ny
        val cellX = width / nx
        val cellY = height / ny
        var opaque = 0
        var white = 0
        val sampleStep = ceil(sqrt(pixels.size / 65536.0)).toInt().coerceAtLeast(1)
        for (y in 0 until pixelHeight step sampleStep) for (x in 0 until pixelWidth step sampleStep) {
            val c = pixels[y * pixelWidth + x]
            if (c ushr 24 > 242) {
                opaque++
                if (min(c shr 16 and 255, min(c shr 8 and 255, c and 255)) > 229) white++
            }
        }
        val body = smooth(((if (opaque == 0) 0f else white.toFloat() / opaque) - .30f) / .35f)
        val classified = System.nanoTime()
        val metrics = linkedMapOf("classificationMs" to (classified-started)/1e6)
        val release = releaseField(nx, ny, directionDegrees) { name, value -> metrics[name] = value }
        val released = System.nanoTime()
        val blurred = gaussian(release, nx, ny, 3)
        val gx = FloatArray(count)
        val gy = FloatArray(count)
        for (y in 0 until ny) for (x in 0 until nx) {
            val i = y * nx + x
            val dx = (blurred[y * nx + min(x + 1, nx - 1)] - blurred[y * nx + max(x - 1, 0)]) /
                (cellX * if (x == 0 || x == nx - 1) 1 else 2)
            val dy = (blurred[min(y + 1, ny - 1) * nx + x] - blurred[max(y - 1, 0) * nx + x]) /
                (cellY * if (y == 0 || y == ny - 1) 1 else 2)
            val length = max(hypot(dx, dy), 1e-6f)
            gx[i] = dx / length
            gy[i] = dy / length
        }
        val normalsX = gaussian(gx, nx, ny, 7)
        val normalsY = gaussian(gy, nx, ny, 7)
        val normalised = System.nanoTime()
        val values = FloatArray(count * 12)
        val pigment = FloatArray(count)
        val order = LongArray(count)
        val random = Random(seed)
        for (i in 0 until count) {
            val x = (i % nx + .5f) * cellX
            val y = (i / nx + .5f) * cellY
            val rx = random.nextFloat()
            val ry = random.nextFloat()
            val rz = random.nextFloat()
            val rw = random.nextFloat()
            val born = max(release[i] + (.060f + .080f * body) * (rw - .5f), .001f)
            val px = (x / width * pixelWidth).toInt().coerceIn(0, pixelWidth - 1)
            val py = (y / height * pixelHeight).toInt().coerceIn(0, pixelHeight - 1)
            val c = pixels[py * pixelWidth + px]
            val red = (c shr 16 and 255) / 255f
            val green = (c shr 8 and 255) / 255f
            val blue = (c and 255) / 255f
            val content = smooth((max(red, max(green, blue)) - min(red, min(green, blue)) - .30f) / .42f)
            val cap = .865f + .115f * rz - born
            var life = (.10f + .22f * (-ln(max(rx, .004f))).pow(.85f) + .20f * born) * .70f
            life = max(min(life, cap), .11f)
            life = max(min(life * (1f + .30f * content), cap), .11f)
            val p = i * 12
            values[p] = x; values[p + 1] = y; values[p + 2] = born; values[p + 3] = i.toFloat()
            values[p + 4] = normalsX[i]; values[p + 5] = normalsY[i]
            values[p + 6] = life; values[p + 7] = .018f + .036f * rz
            values[p + 8] = rx; values[p + 9] = ry; values[p + 10] = rz; values[p + 11] = rw
            pigment[i] = content
            val depth = sin(x * .014f + y * .021f) * .6f + (ry - .5f) * .15f
            order[i] = ((depth + 1f).times(1_000_000).toLong() shl 32) or i.toLong()
        }
        val populated = System.nanoTime()
        // 原始索引破除等深度排序的歧义，不依赖对象排序或逐帧重排。
        order.sort()
        val sorted = FloatArray(values.size)
        val sortedPigment = FloatArray(count)
        for (i in order.indices) {
            val original = order[i].toInt()
            values.copyInto(sorted, i * 12, original * 12, original * 12 + 12)
            sortedPigment[i] = pigment[original]
        }
        return Materials(nx, ny, cellX, cellY, body, sorted, sortedPigment, metrics + mapOf(
            "releaseMs" to (released-started)/1e6, "normalsMs" to (normalised-released)/1e6,
            "populationMs" to (populated-normalised)/1e6, "sortMs" to (System.nanoTime()-populated)/1e6))
    }

    /** 三个起点、连续旋转、低频扭曲，与 fields.py 的通用弹窗场同式。 */
    fun releaseField(nx: Int, ny: Int, directionDegrees: Float, stage: ((String, Double) -> Unit)? = null): FloatArray {
        val started = System.nanoTime()
        val angle = Math.toRadians((directionDegrees - 65f).toDouble())
        val c = cos(angle); val s = sin(angle)
        val result = FloatArray(nx * ny)
        for (y in 0 until ny) for (x in 0 until nx) {
            val qx = (x + .5) / nx - .5
            val qy = (y + .5) / ny - .5
            val rx = .5 + c * qx - s * qy
            val ry = .5 + s * qx + c * qy
            val wx = rx + .035 * fieldSin(5.7 * ry + 1.2) + .019 * fieldSin(11 * rx + 7.3 * ry + 2.4)
            val wy = ry + .027 * fieldSin(6.3 * rx - 1.8) + .017 * fieldSin(7.6 * rx - 10.2 * ry + .6)
            fun part(ox: Double, oy: Double, delay: Double): Double {
                val dx = (wx - ox) * .66; val dy = (wy - oy) * .67
                // sqrt(d)^.88 与 d^.44 等价，避免每格六次通用平方与三次开方。
                return delay + fieldPower(dx * dx + dy * dy + 1e-8)
            }
            result[y * nx + x] = smoothMin(smoothMin(part(.60, 1.02, 0.0), part(0.0, .06, .15)), part(1.04, .20, .28)).toFloat()
        }
        val computed = System.nanoTime()
        val sorted = result.sortedArray()
        val ordered = System.nanoTime()
        val q = (sorted.size - 1) * .997
        val lower = q.toInt()
        val upper = min(lower + 1, sorted.lastIndex)
        val top = sorted[lower] * (1 - (q - lower)) + sorted[upper] * (q - lower)
        val minimum = sorted.first()
        val range = max(top - minimum, 1e-8)
        val gain = .59 / range
        for (i in result.indices) result[i] = (((result[i] - minimum) * gain + .018).toFloat()).coerceIn(0f, .78f)
        stage?.invoke("fieldMs", (computed-started)/1e6)
        stage?.invoke("quantileMs", (ordered-computed)/1e6)
        stage?.invoke("normalizationMs", (System.nanoTime()-ordered)/1e6)
        return result
    }

    private fun smoothMin(a: Double, b: Double): Double {
        val h = (.5 + .5 * (b - a) / .047).coerceIn(0.0, 1.0)
        return b * (1 - h) + a * h - .047 * h * (1 - h)
    }

    // 释放场没有每帧变化。小型数值表替代逐格 libm 调用；八方向桌面基准把
    // 归一化释放时间误差限制在 2 微秒内。幂表按浮点指数拆分，原点附近也保持精度。
    private val sinTable = DoubleArray(8193) { sin(it * (2.0 * PI / 8192)) }
    private val powerMantissa = DoubleArray(1025) { (1.0 + it / 1024.0).pow(.44) }
    private val powerExponent = DoubleArray(129) { 2.0.pow((it - 64) * .44) }

    private fun fieldSin(value: Double): Double {
        val position = (value / (2.0 * PI) - floor(value / (2.0 * PI))) * 8192
        val i = position.toInt().coerceAtMost(8191)
        return sinTable[i] + (sinTable[i + 1] - sinTable[i]) * (position - i)
    }

    private fun fieldPower(value: Double): Double {
        val bits = value.toRawBits()
        val exponent = ((bits ushr 52) and 2047).toInt() - 1023
        if (exponent !in -64..64) return value.pow(.44)
        val index = ((bits ushr 42) and 1023).toInt()
        val fraction = (bits and 4398046511103L) / 4398046511104.0
        return (powerMantissa[index] + (powerMantissa[index + 1] - powerMantissa[index]) * fraction) * powerExponent[exponent + 64]
    }

    private fun smooth(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** scipy.ndimage 的 reflect 边界和截断半径，两个方向分开卷积。 */
    private fun gaussian(input: FloatArray, width: Int, height: Int, sigma: Int): FloatArray {
        val radius = sigma * 4
        val weights = DoubleArray(radius * 2 + 1) { exp(-.5 * ((it - radius).toDouble() / sigma).pow(2)) }
        val sum = weights.sum()
        for (i in weights.indices) weights[i] /= sum
        fun reflect(index: Int, size: Int): Int {
            var i = index
            while (i < 0 || i >= size) i = if (i < 0) -i - 1 else 2 * size - i - 1
            return i
        }
        // 把 reflect 的边界处理移出最内层，并利用高斯核的对称性减半乘法。
        val xIndex = Array(width) { x -> IntArray(radius * 2 + 1) { reflect(x + it - radius, width) } }
        val yIndex = Array(height) { y -> IntArray(radius * 2 + 1) { reflect(y + it - radius, height) * width } }
        val vertical = FloatArray(input.size)
        val output = FloatArray(input.size)
        for (y in 0 until height) for (x in 0 until width) {
            val indices = yIndex[y]
            var value = input[y * width + x] * weights[radius]
            for (k in 1..radius) value += (input[indices[radius - k] + x].toDouble() + input[indices[radius + k] + x]) * weights[radius + k]
            vertical[y * width + x] = value.toFloat()
        }
        for (y in 0 until height) for (x in 0 until width) {
            val indices = xIndex[x]
            val row = y * width
            var value = vertical[row + x] * weights[radius]
            for (k in 1..radius) value += (vertical[row + indices[radius - k]].toDouble() + vertical[row + indices[radius + k]]) * weights[radius + k]
            output[y * width + x] = value.toFloat()
        }
        return output
    }
}
