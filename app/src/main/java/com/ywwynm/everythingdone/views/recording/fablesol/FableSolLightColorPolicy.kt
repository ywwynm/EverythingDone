package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 宏观坡面受光策略：保留 D87 的同色正向提亮，并用局部、保色、线性亮度封顶的背坡阴影
 * 恢复少量体积感。阴影目标来自未混白身份色派生的 deepColor，不混黑、不读取微法线。
 */
object FableSolLightColorPolicy {

    const val MAX_RELATIVE_LONGITUDINAL_LIFT = 0.015
    const val LONGITUDINAL_LIGHT_RESPONSE = 0.12
    const val MACRO_SHADOW_NDL_START = 0.080
    const val MACRO_SHADOW_NDL_FULL = 0.180
    const val MACRO_SHADOW_FAR_START = 0.350
    const val MACRO_SHADOW_FAR_END = 0.700
    const val MACRO_SHADOW_CREST_START = 0.005
    const val MACRO_SHADOW_CREST_FULL = 0.080
    const val MACRO_SHADOW_LOCAL_FLOOR = 0.300
    const val MAX_MACRO_SHADOW_LUMA_CAP = 0.040

    fun resolveLongitudinal(
        base: Int,
        positiveNdl: Double,
        depth01: Double,
        negativeNdl: Double = 0.0,
        deep: Int = base,
        crestPinch: Double = 0.0,
        shadowLumaCap: Double = 0.0
    ): Int {
        val response = (positiveNdl.coerceAtLeast(0.0) * LONGITUDINAL_LIGHT_RESPONSE)
            .coerceAtMost(MAX_RELATIVE_LONGITUDINAL_LIFT)
        val lift = response * longitudinalDepthWeight(depth01)
        val scaledRed = (base ushr 16 and 0xff) * (1.0 + lift)
        val scaledGreen = (base ushr 8 and 0xff) * (1.0 + lift)
        val scaledBlue = (base and 0xff) * (1.0 + lift)
        val cap = shadowLumaCap.coerceIn(0.0, MAX_MACRO_SHADOW_LUMA_CAP)
        if (cap <= 0.0) return pack(base, scaledRed, scaledGreen, scaledBlue)

        val shadowMask = macroShadowMask(negativeNdl, depth01, crestPinch)
        if (shadowMask <= 0.0) return pack(base, scaledRed, scaledGreen, scaledBlue)

        val litRed = srgbToLinear(scaledRed / 255.0)
        val litGreen = srgbToLinear(scaledGreen / 255.0)
        val litBlue = srgbToLinear(scaledBlue / 255.0)
        val deepRed = srgbToLinear((deep ushr 16 and 0xff) / 255.0)
        val deepGreen = srgbToLinear((deep ushr 8 and 0xff) / 255.0)
        val deepBlue = srgbToLinear((deep and 0xff) / 255.0)
        val litLuma = luma(litRed, litGreen, litBlue)
        val availableLoss = (litLuma - luma(deepRed, deepGreen, deepBlue)).coerceAtLeast(0.0)
        if (availableLoss <= 1e-9) return pack(base, scaledRed, scaledGreen, scaledBlue)

        val requestedLoss = litLuma * cap * shadowMask
        val deepMix = (requestedLoss / availableLoss).coerceIn(0.0, 1.0)
        return pack(
            base,
            linearToSrgb(litRed + (deepRed - litRed) * deepMix) * 255.0,
            linearToSrgb(litGreen + (deepGreen - litGreen) * deepMix) * 255.0,
            linearToSrgb(litBlue + (deepBlue - litBlue) * deepMix) * 255.0
        )
    }

    fun macroShadowMask(negativeNdl: Double, depth01: Double, crestPinch: Double): Double {
        val backSlope = smoothstep(
            MACRO_SHADOW_NDL_START,
            MACRO_SHADOW_NDL_FULL,
            negativeNdl.coerceAtLeast(0.0)
        )
        val depthGate = 1.0 - smoothstep(
            MACRO_SHADOW_FAR_START,
            MACRO_SHADOW_FAR_END,
            depth01.coerceIn(0.0, 1.0)
        )
        val crestGate = MACRO_SHADOW_LOCAL_FLOOR +
            (1.0 - MACRO_SHADOW_LOCAL_FLOOR) * smoothstep(
                MACRO_SHADOW_CREST_START,
                MACRO_SHADOW_CREST_FULL,
                crestPinch
            )
        return (backSlope * depthGate * crestGate).coerceIn(0.0, 1.0)
    }

    private fun longitudinalDepthWeight(depth01: Double): Double {
        val t = ((depth01.coerceIn(0.0, 1.0) - 0.35) / 0.55).coerceIn(0.0, 1.0)
        val smooth = t * t * (3.0 - 2.0 * t)
        return 1.0 - 0.55 * smooth
    }

    private fun smoothstep(edge0: Double, edge1: Double, value: Double): Double {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    private fun srgbToLinear(value: Double): Double {
        val c = value.coerceIn(0.0, 1.0)
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun linearToSrgb(value: Double): Double {
        val c = value.coerceIn(0.0, 1.0)
        return if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1.0 / 2.4) - 0.055
    }

    private fun luma(red: Double, green: Double, blue: Double): Double =
        red * 0.2126 + green * 0.7152 + blue * 0.0722

    private fun pack(base: Int, red: Double, green: Double, blue: Double): Int {
        val alpha = base and -0x1000000
        return alpha or
            (red.roundToInt().coerceIn(0, 255) shl 16) or
            (green.roundToInt().coerceIn(0, 255) shl 8) or
            blue.roundToInt().coerceIn(0, 255)
    }
}
