package com.ywwynm.everythingdone.views.particledismiss

import kotlin.math.*

/** 仅从快照识别大面积面板色，不接受场景名称或界面类型。 */
internal object ParticleMicroflakeContent {
    data class Panel(val color: FloatArray, val weight: Float, val fraction: Float)

    fun panel(pixels: IntArray, width: Int, height: Int, rules: ParticleMicroflakeRules): Panel {
        val stride = ceil(sqrt(pixels.size / 65536.0)).toInt().coerceAtLeast(1)
        val counts = IntArray(4096)
        val first = IntArray(4096) { Int.MAX_VALUE }
        val sums = Array(3) { LongArray(4096) }
        var opaque = 0
        for (y in 0 until height step stride) for (x in 0 until width step stride) {
            val c = pixels[y * width + x]
            if (c ushr 24 != 255) continue
            val r = c shr 16 and 255; val g = c shr 8 and 255; val b = c and 255
            val key = (r / 16) * 256 + (g / 16) * 16 + b / 16
            counts[key]++
            first[key] = min(first[key], y * width + x)
            sums[0][key] += r.toLong(); sums[1][key] += g.toLong(); sums[2][key] += b.toLong()
            opaque++
        }
        if (opaque == 0) return Panel(FloatArray(3), 0f, 0f)
        var best = 0
        for (key in counts.indices) {
            if (counts[key] > counts[best] || counts[key] == counts[best] && first[key] < first[best]) best = key
        }
        val fraction = counts[best].toFloat() / opaque
        val confidence = smooth((fraction - rules.number("panel_min_fraction")) /
            (rules.number("panel_full_fraction") - rules.number("panel_min_fraction")))
        return Panel(FloatArray(3) { (sums[it][best].toDouble() / counts[best] / 255.0).toFloat() }, confidence, fraction)
    }

    fun weight(color: Int, panel: Panel, distanceStart: Float, distanceSpan: Float): Float {
        if (color ushr 24 == 0) return 0f
        val r = (color shr 16 and 255) / 255f; val g = (color shr 8 and 255) / 255f; val b = (color and 255) / 255f
        val chroma = smooth((max(r, max(g, b)) - min(r, min(g, b)) - .30f) / .42f)
        val difference = max(abs(r - panel.color[0]), max(abs(g - panel.color[1]), abs(b - panel.color[2])))
        val contrast = smooth((difference - distanceStart) / distanceSpan)
        // 极低覆盖像素的直通 RGB 在预乘往返后很不稳定；其内容份额应随覆盖降低。
        return (chroma + (contrast - chroma) * panel.weight) * ((color ushr 24) / 255f)
    }

    private fun smooth(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
