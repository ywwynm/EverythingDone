package com.ywwynm.everythingdone.views.recording.fablesol

import android.graphics.Color
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 颜色工具（对应 canvas.py 的颜色函数）：sRGB↔线性、OKLab 感知混色、HLS 偏色。
 * rgb 统一用 IntArray(3)（r,g,b，0..255）表示，避免频繁装箱。
 */
object FableSolColor {

    fun rgb(r: Int, g: Int, b: Int): IntArray = intArrayOf(r, g, b)

    fun fromColor(c: Int): IntArray = intArrayOf(Color.red(c), Color.green(c), Color.blue(c))

    fun toColor(rgb: IntArray, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), rgb[0], rgb[1], rgb[2])

    /** 线性 RGB 插值（_mix / _lerp_rgb）。 */
    fun mix(c: IntArray, other: IntArray, f: Double): IntArray = intArrayOf(
        (c[0] + (other[0] - c[0]) * f).roundToInt(),
        (c[1] + (other[1] - c[1]) * f).roundToInt(),
        (c[2] + (other[2] - c[2]) * f).roundToInt()
    )

    private fun linearChannel(v: Double): Double {
        val x = v / 255.0
        return if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
    }

    private fun srgbChannel(v: Double): Int {
        val x = v.coerceIn(0.0, 1.0)
        val y = if (x <= 0.0031308) 12.92 * x else 1.055 * x.pow(1.0 / 2.4) - 0.055
        return (y.coerceIn(0.0, 1.0) * 255.0).roundToInt()
    }

    fun rgbToOklab(c: IntArray): DoubleArray {
        val r = linearChannel(c[0].toDouble())
        val g = linearChannel(c[1].toDouble())
        val b = linearChannel(c[2].toDouble())
        val l = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b
        val m = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b
        val s = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b
        val l_ = cbrt(l); val m_ = cbrt(m); val s_ = cbrt(s)
        return doubleArrayOf(
            0.2104542553 * l_ + 0.7936177850 * m_ - 0.0040720468 * s_,
            1.9779984951 * l_ - 2.4285922050 * m_ + 0.4505937099 * s_,
            0.0259040371 * l_ + 0.7827717662 * m_ - 0.8086757660 * s_
        )
    }

    fun oklabToRgb(lab: DoubleArray): IntArray {
        val linear = oklabToLinearRgb(lab)
        return intArrayOf(srgbChannel(linear[0]), srgbChannel(linear[1]), srgbChannel(linear[2]))
    }

    /** 保持 OKLab 明度与色相，把超出 sRGB 的彩度沿同一色相压回色域。 */
    fun gamutMappedOklab(lab: DoubleArray): DoubleArray {
        val source = lab.copyOf()
        source[0] = source[0].coerceIn(0.0, 1.0)
        if (isOklabInSrgbGamut(source[0], source[1], source[2])) return source
        val chroma = hypot(source[1], source[2])
        if (chroma <= ACHROMATIC_EPSILON) {
            return doubleArrayOf(source[0], 0.0, 0.0)
        }
        var low = 0.0
        var high = 1.0
        repeat(GAMUT_SEARCH_ITERATIONS) {
            val scale = (low + high) * 0.5
            if (isOklabInSrgbGamut(
                    source[0],
                    source[1] * scale,
                    source[2] * scale
                )
            ) low = scale else high = scale
        }
        return doubleArrayOf(source[0], source[1] * low, source[2] * low)
    }

    fun oklabToRgbGamutMapped(lab: DoubleArray): IntArray =
        oklabToRgb(gamutMappedOklab(lab))

    /** 判断给定 OKLab 坐标是否无需裁切即可落在 sRGB 色域内。 */
    fun isOklabInSrgbGamut(lab: DoubleArray): Boolean =
        isOklabInSrgbGamut(lab[0], lab[1], lab[2])

    /** 固定 L/h 时，从中性色轴向外二分求 sRGB 可容纳的最大 OKLCH 彩度。 */
    fun maximumSrgbChroma(lightness: Double, hueRadians: Double, maximum: Double): Double {
        var low = 0.0
        var high = maximum.coerceAtLeast(0.0)
        if (high <= ACHROMATIC_EPSILON) return 0.0
        val hueCos = cos(hueRadians)
        val hueSin = sin(hueRadians)
        repeat(GAMUT_SEARCH_ITERATIONS) {
            val chroma = (low + high) * 0.5
            if (isOklabInSrgbGamut(
                    lightness,
                    chroma * hueCos,
                    chroma * hueSin
                )
            ) low = chroma else high = chroma
        }
        return low
    }

    private fun isOklabInSrgbGamut(lightness: Double, a: Double, b: Double): Boolean {
        val l_ = lightness + 0.3963377774 * a + 0.2158037573 * b
        val m_ = lightness - 0.1055613458 * a - 0.0638541728 * b
        val s_ = lightness - 0.0894841775 * a - 1.2914855480 * b
        val l = l_ * l_ * l_; val m = m_ * m_ * m_; val s = s_ * s_ * s_
        val red = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s
        val green = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s
        val blue = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
        return red >= -GAMUT_EPSILON && red <= 1.0 + GAMUT_EPSILON &&
            green >= -GAMUT_EPSILON && green <= 1.0 + GAMUT_EPSILON &&
            blue >= -GAMUT_EPSILON && blue <= 1.0 + GAMUT_EPSILON
    }

    private fun oklabToLinearRgb(lab: DoubleArray): DoubleArray {
        val bigL = lab[0]; val a = lab[1]; val b = lab[2]
        val l_ = bigL + 0.3963377774 * a + 0.2158037573 * b
        val m_ = bigL - 0.1055613458 * a - 0.0638541728 * b
        val s_ = bigL - 0.0894841775 * a - 1.2914855480 * b
        val l = l_ * l_ * l_; val m = m_ * m_ * m_; val s = s_ * s_ * s_
        val r = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s
        val g = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s
        val bb = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
        return doubleArrayOf(r, g, bb)
    }

    /** OKLab 空间线性插值（_mix_oklab），避免透明叠层发灰。 */
    fun mixOklab(c1: IntArray, c2: IntArray, f: Double): IntArray {
        val t = f.coerceIn(0.0, 1.0)
        val a = rgbToOklab(c1)
        val b = rgbToOklab(c2)
        return oklabToRgb(doubleArrayOf(
            a[0] * (1.0 - t) + b[0] * t,
            a[1] * (1.0 - t) + b[1] * t,
            a[2] * (1.0 - t) + b[2] * t
        ))
    }

    /** 色相偏移（_shift_hue），单位度。经 HLS 空间旋转 hue。 */
    fun shiftHue(c: IntArray, degrees: Double): IntArray {
        val hls = rgbToHls(c[0] / 255.0, c[1] / 255.0, c[2] / 255.0)
        val h = (hls[0] + degrees / 360.0).let { it - Math.floor(it) }  // % 1.0
        val out = hlsToRgb(h, hls[1], hls[2])
        return intArrayOf(
            (out[0] * 255).roundToInt(), (out[1] * 255).roundToInt(), (out[2] * 255).roundToInt()
        )
    }

    /** 色相向 target 有界旋转（_hue_toward）：透射色偏移从本色出发、幅度设上限。 */
    fun hueToward(c: IntArray, targetDeg: Double, maxDeg: Double): IntArray {
        val hls = rgbToHls(c[0] / 255.0, c[1] / 255.0, c[2] / 255.0)
        if (hls[2] < 1e-4) return c  // 无彩色没有色相可言
        var delta = (targetDeg - hls[0] * 360.0 + 180.0) % 360.0
        if (delta < 0) delta += 360.0
        delta -= 180.0
        return shiftHue(c, delta.coerceIn(-maxDeg, maxDeg))
    }

    /** 固定 OKLab 色相、优先保持绝对彩度，在 sRGB 边界才压缩彩度。 */
    fun withOklabLightness(c: IntArray, lightness: Double): IntArray {
        val lab = rgbToOklab(c)
        lab[0] = lightness.coerceIn(0.0, 1.0)
        return oklabToRgbGamutMapped(lab)
    }

    /** OKLab 提亮，不向白点混色，也不会自动反向压暗。 */
    fun lightenOklab(c: IntArray, dl: Double): IntArray {
        if (dl <= 0.0) return c.copyOf()
        val lab = rgbToOklab(c)
        lab[0] = (lab[0] + dl).coerceIn(0.0, 1.0)
        return oklabToRgbGamutMapped(lab)
    }

    /** OKLab 降明度、固定色相并优先保持绝对彩度；近黑时安全收敛到黑。 */
    fun darkenOklab(c: IntArray, dl: Double): IntArray {
        if (dl <= 0.0) return c.copyOf()
        val lab = rgbToOklab(c)
        lab[0] = (lab[0] - dl).coerceIn(0.0, 1.0)
        return oklabToRgbGamutMapped(lab)
    }

    private const val GAMUT_SEARCH_ITERATIONS = 22
    private const val GAMUT_EPSILON = 1e-9
    private const val ACHROMATIC_EPSILON = 1e-9

    // ---- colorsys 端口（0..1） ----
    private fun rgbToHls(r: Double, g: Double, b: Double): DoubleArray {
        val maxc = maxOf(r, g, b)
        val minc = minOf(r, g, b)
        val sumc = maxc + minc
        val rangec = maxc - minc
        val l = sumc / 2.0
        if (minc == maxc) return doubleArrayOf(0.0, l, 0.0)
        val s = if (l <= 0.5) rangec / sumc else rangec / (2.0 - maxc - minc)
        val rc = (maxc - r) / rangec
        val gc = (maxc - g) / rangec
        val bc = (maxc - b) / rangec
        var h = when (maxc) {
            r -> bc - gc
            g -> 2.0 + rc - bc
            else -> 4.0 + gc - rc
        }
        h = (h / 6.0).let { it - Math.floor(it) }  // % 1.0
        return doubleArrayOf(h, l, s)
    }

    private fun hlsToRgb(h: Double, l: Double, s: Double): DoubleArray {
        if (s == 0.0) return doubleArrayOf(l, l, l)
        val m2 = if (l <= 0.5) l * (1.0 + s) else l + s - l * s
        val m1 = 2.0 * l - m2
        return doubleArrayOf(hlsV(m1, m2, h + 1.0 / 3.0), hlsV(m1, m2, h), hlsV(m1, m2, h - 1.0 / 3.0))
    }

    private fun hlsV(m1: Double, m2: Double, hueIn: Double): Double {
        val hue = hueIn.let { it - Math.floor(it) }  // % 1.0
        return when {
            hue < 1.0 / 6.0 -> m1 + (m2 - m1) * hue * 6.0
            hue < 0.5 -> m2
            hue < 2.0 / 3.0 -> m1 + (m2 - m1) * (2.0 / 3.0 - hue) * 6.0
            else -> m1
        }
    }
}
