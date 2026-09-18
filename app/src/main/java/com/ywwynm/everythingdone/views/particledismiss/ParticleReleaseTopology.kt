package com.ywwynm.everythingdone.views.particledismiss

import kotlin.math.*

/** 共同释放场与局部起点连续组合；所有尺寸、触点和素材使用同一规则。 */
internal object ParticleReleaseTopology {
    private data class Patch(val x: Double, val y: Double, val delay: Double,
        val c: Double, val s: Double, val scale: Double)

    private fun patches(width: Float, height: Float, seed: Long, origin: ParticleReleaseOrigin?): Array<Patch> {
        val span = min(width, height).toDouble()
        val salt = (seed xor (seed ushr 32)).toInt() xor 0x243f6a88
        val count = max(2, ceil(max(width, height) / span * 1.4).toInt()) +
            if (ParticleMicroflakeModel.randomValue(0, salt) > .70) 1 else 0
        val random = DoubleArray((count + 1) * 4) { ParticleMicroflakeModel.randomValue(it, salt).toDouble() }
        val perimeter = 2.0 * (width + height)
        return Array(count) { i ->
            val j = 4 + i * 4
            val fraction = random[0] + (i + .85 * (random[j] - .5)) / count
            val position = (fraction - floor(fraction)) * perimeter
            val (x, y) = when {
                position < width -> position to 0.0
                position < width + height -> width.toDouble() to position - width
                position < 2 * width + height -> 2 * width + height - position to height.toDouble()
                else -> 0.0 to perimeter - position
            }
            val inflate = 1.015 + .025 * random[j + 1]
            val angle = 2 * PI * random[j + 3]
            val patch = Patch(width * .5 + (x - width * .5) * inflate, height * .5 + (y - height * .5) * inflate,
                .02 + .25 * random[j + 2] + .06 * i,
                cos(angle), sin(angle), .65 + .50 * random[j])
            // 固定其中一个起点，数量与其余起点的随机参数均不变。零延迟让触点属于首批消逝区域。
            if (i == 0 && origin != null)
                patch.copy(x = origin.x.toDouble() * width, y = origin.y.toDouble() * height, delay = 0.0)
            else patch
        }
    }

    internal fun nativePatches(width: Float, height: Float, seed: Long,
                               origin: ParticleReleaseOrigin? = null): DoubleArray =
        patches(width, height, seed, origin).flatMap { listOf(it.x, it.y, it.delay, it.c, it.s, it.scale) }.toDoubleArray()

    fun release(nx: Int, ny: Int, width: Float, height: Float, direction: Float,
        seed: Long, detail: DoubleArray, origin: ParticleReleaseOrigin? = null): DoubleArray {
        val span = min(width, height).toDouble()
        val patches = patches(width, height, seed, origin)
        val angle = Math.toRadians(direction.toDouble()); val wx = cos(angle); val wy = -sin(angle)
        var low = Double.POSITIVE_INFINITY; var high = Double.NEGATIVE_INFINITY
        val result = DoubleArray(nx * ny)
        for (y in 0 until ny) for (x in 0 until nx) {
            val px = (x + .5) / nx * width; val py = (y + .5) / ny * height
            var arrival = 10.0
            for (patch in patches) {
                val dx = (px - patch.x) / span; val dy = (py - patch.y) / span
                val u = (patch.c * dx + patch.s * dy) / patch.scale
                val v = (-patch.s * dx + patch.c * dy) / (patch.scale * .80)
                val distance = sqrt(u * u + v * v + .0004)
                val local = patch.delay + .62 * distance + .025 * (dx * wx + dy * wy)
                val h = max(.055 - abs(arrival - local), 0.0) / .055
                arrival = min(arrival, local) - h * h * .055 * .25
            }
            arrival += .035 * tanh((detail[y * nx + x].coerceIn(.008, .78) - .38) / .18)
            result[y * nx + x] = arrival
            low = min(low, arrival); high = max(high, arrival)
        }
        val range = max(high - low, 1e-6)
        val weight = locality(width, height, direction, seed, origin)
        for (i in result.indices) {
            val base = detail[i].coerceIn(.008, .78)
            val local = .024 + .72 * ((result[i] - low) / range).pow(1.45)
            result[i] = base + weight * (local - base)
        }
        return result
    }

    internal fun locality(width: Float, height: Float, direction: Float, seed: Long,
                          origin: ParticleReleaseOrigin? = null): Double {
        val angle = Math.toRadians(direction.toDouble())
        val ux = abs(cos(angle)) / width; val uy = abs(sin(angle)) / height
        val corner = 2 * min(ux, uy) / max(ux + uy, 1e-8)
        val salt = (seed xor (seed ushr 32)).toInt() xor 0x510e527f
        val geometry = (1 - corner).pow(1.5) * (.42 + .14 * ParticleMicroflakeModel.randomValue(0, salt))
        val t = ((ParticleMicroflakeModel.randomValue(3, salt) - .30) / .55).coerceIn(0.0, 1.0)
        val randomLocality = .72 * t * t * (3 - 2 * t)
        val weight = geometry + (1 - geometry) * randomLocality
        // 有明确触点时以局部扩散为主，保留细节扰动；否则随机细节场可能把触点推迟到中段。
        return if (origin == null) weight else max(weight, .90)
    }
}
