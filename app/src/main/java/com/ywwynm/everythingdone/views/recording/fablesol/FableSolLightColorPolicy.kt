package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 宏观坡面受光策略：主体色与反射/透射分瓣不参与遮挡乘暗；背坡可见度只削减
 * 一个微弱、同色的直射光瓣。
 */
object FableSolLightColorPolicy {

    const val MAX_RELATIVE_LONGITUDINAL_LIFT = 0.015
    const val LONGITUDINAL_LIGHT_RESPONSE = 0.12
    const val MACRO_SHADOW_NDL_START = 0.080
    const val MACRO_SHADOW_NDL_FULL = 0.180
    const val MACRO_SHADOW_CREST_START = 0.005
    const val MACRO_SHADOW_CREST_FULL = 0.080
    const val MACRO_SHADOW_LOCAL_FLOOR = 0.300
    const val DIRECT_LIGHT_BASE_RESPONSE = 0.004
    const val MAX_DIRECT_LIGHT_LOBE = 0.019

    fun resolveLongitudinal(
        base: Int,
        positiveNdl: Double,
        depth01: Double,
        negativeNdl: Double = 0.0,
        directNdl: Double = 0.0,
        crestPinch: Double = 0.0
    ): Int {
        val relativeLift = (positiveNdl.coerceAtLeast(0.0) * LONGITUDINAL_LIGHT_RESPONSE)
            .coerceAtMost(MAX_RELATIVE_LONGITUDINAL_LIFT)
        val directLobe = min(
            directNdl.coerceAtLeast(0.0) * DIRECT_LIGHT_BASE_RESPONSE + relativeLift,
            MAX_DIRECT_LIGHT_LOBE
        ) * FableSolMaterialPolicy.macroLightWeight(depth01)
        val visibility = 1.0 - macroShadowMask(negativeNdl, depth01, crestPinch)
        val lift = directLobe * visibility.coerceIn(0.0, 1.0)
        val scaledRed = linearToSrgb(srgbToLinear(base ushr 16 and 0xff) * (1.0 + lift))
        val scaledGreen = linearToSrgb(srgbToLinear(base ushr 8 and 0xff) * (1.0 + lift))
        val scaledBlue = linearToSrgb(srgbToLinear(base and 0xff) * (1.0 + lift))
        return pack(base, scaledRed * 255.0, scaledGreen * 255.0, scaledBlue * 255.0)
    }

    fun macroShadowMask(negativeNdl: Double, depth01: Double, crestPinch: Double): Double {
        val backSlope = smoothstep(
            MACRO_SHADOW_NDL_START,
            MACRO_SHADOW_NDL_FULL,
            negativeNdl.coerceAtLeast(0.0)
        )
        val depthGate = FableSolMaterialPolicy.macroShadowWeight(depth01)
        val crestGate = MACRO_SHADOW_LOCAL_FLOOR +
            (1.0 - MACRO_SHADOW_LOCAL_FLOOR) * smoothstep(
                MACRO_SHADOW_CREST_START,
                MACRO_SHADOW_CREST_FULL,
                crestPinch
            )
        return (backSlope * depthGate * crestGate).coerceIn(0.0, 1.0)
    }

    private fun smoothstep(edge0: Double, edge1: Double, value: Double): Double {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    private fun srgbToLinear(channel: Int): Double {
        val value = channel.coerceIn(0, 255) / 255.0
        return if (value <= 0.04045) value / 12.92
        else ((value + 0.055) / 1.055).pow(2.4)
    }

    private fun linearToSrgb(linear: Double): Double {
        val value = linear.coerceAtLeast(0.0)
        return if (value <= 0.0031308) value * 12.92
        else 1.055 * value.pow(1.0 / 2.4) - 0.055
    }

    private fun pack(base: Int, red: Double, green: Double, blue: Double): Int {
        val alpha = base and -0x1000000
        return alpha or
            (red.roundToInt().coerceIn(0, 255) shl 16) or
            (green.roundToInt().coerceIn(0, 255) shl 8) or
            blue.roundToInt().coerceIn(0, 255)
    }
}
