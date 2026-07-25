package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

/**
 * D222：星芒的触发场恒用银丝标定档，不跟随"银丝"组的外观滑杆。
 *
 * 银丝强度归零只让银丝自身隐去，星芒的存在与否由"星芒强度"独立掌管。
 */
class FableSolStarFieldTest {

    @Test
    fun starburstsSurviveWhenSilverThreadSlidersAreZeroed() {
        val baseline = starsWith { }
        assertTrue("标定档本身要出星，否则本测试无判别力", baseline.count > 0)

        val rimOff = starsWith { it.set("uplift_crest_rim", 0.0) }
        assertEquals(baseline.count, rimOff.count)
        assertArrayEquals(baseline.data, rimOff.data, 0f)

        val peakFloor = starsWith { it.set("uplift_rim_peak", 1.0) }
        assertEquals(baseline.count, peakFloor.count)
        assertArrayEquals(baseline.data, peakFloor.data, 0f)

        val bothOff = starsWith {
            it.set("uplift_crest_rim", 0.0)
            it.set("uplift_rim_peak", 1.0)
        }
        assertEquals(baseline.count, bothOff.count)
        assertArrayEquals(baseline.data, bothOff.data, 0f)
    }

    @Test
    fun starburstStrengthRemainsTheOnlySwitchThatSilencesTheField() {
        assertEquals(0, starsWith { it.set("glare_strength", 0.0) }.count)
    }

    private class Stars(val count: Int, val data: FloatArray)

    /** 单帧扫描一个合成水面：第 0 层在太阳柱中心带一个平顶高峰。 */
    private fun starsWith(configure: (FableSolParams) -> Unit): Stars {
        val params = FableSolParams()
        // 亮结滑动会按 valueNoise 随位置调制场强，本测试只关心滑杆解耦，
        // 关掉它让 knotMix 恒为 1，断言不依赖噪声取值。
        params.set("uplift_rim_slide", 0.0)
        configure(params)
        val sim = FableSolSimulation(params)
        val water = syntheticWater()
        val meanY = FloatArray(FableSolSpec.N_LAYERS) { layer ->
            (layer * LAYER_PITCH_PX).toFloat()
        }
        val subsurface = FloatArray(FableSolSpec.N_LAYERS * 3) { 0.6f }
        val field = FableSolStarField(DENSITY)
        val components = FableSolGlMeshLayout.COMPONENTS_PER_VERTEX
        field.update(
            sim,
            params,
            COLUMNS,
            water,
            meanY,
            22.0 * DENSITY,
            water[0].toDouble(),
            (water[(COLUMNS - 1) * components] - water[0]).toDouble(),
            crestRimActivity = 1.0,
            layerSubsurfaceStart = subsurface
        )
        return Stars(field.starCount, field.starData.copyOf())
    }

    /**
     * 每层锚行都是"平坦水面 + 太阳柱中心一个高斯波峰"：峰顶坡度为零
     * （flatTop=1）、显著度足够（lifted=1），apex 门内必有唯一局部峰。
     */
    private fun syntheticWater(): FloatArray {
        val components = FableSolGlMeshLayout.COMPONENTS_PER_VERTEX
        val values = FloatArray(
            FableSolContinuousSurface.Z_ROWS * COLUMNS * components
        )
        val crestColumn = (COLUMNS - 1) * 0.4287
        val sigma = COLUMNS * 0.05
        for (row in 0 until FableSolContinuousSurface.Z_ROWS) {
            val layer = row / FableSolContinuousSurface.ROWS_PER_LAYER
            for (column in 0 until COLUMNS) {
                val offset = (row * COLUMNS + column) * components
                val delta = (column - crestColumn) / sigma
                val bump = exp(-0.5 * delta * delta)
                // 屏幕 y 向下：减去 bump 即抬高波峰。
                values[offset] = (column * SPACING_PX).toFloat()
                values[offset + 1] =
                    (layer * LAYER_PITCH_PX - CREST_HEIGHT_PX * bump).toFloat()
                // 坡度 = 高斯的导数（峰顶恰为 0，两翼陡到关掉 flatTop）。
                values[offset + FableSolGlMeshLayout.SHEEN_SLOPE_X_OFFSET] =
                    (CREST_HEIGHT_PX * bump * delta / sigma).toFloat()
                values[offset + FableSolGlMeshLayout.SHEEN_SLOPE_Z_OFFSET] = 0f
            }
        }
        return values
    }

    private companion object {
        const val COLUMNS = 120
        const val DENSITY = 2.5
        const val SPACING_PX = 3.0
        const val LAYER_PITCH_PX = 4.0
        const val CREST_HEIGHT_PX = 36.0
    }
}
