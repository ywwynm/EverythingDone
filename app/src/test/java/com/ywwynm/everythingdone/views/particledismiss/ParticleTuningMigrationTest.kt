package com.ywwynm.everythingdone.views.particledismiss

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

/** 实际迁移函数读写 SharedPreferences 接口；Editor 在 apply 时才把整批改动写回。 */
class ParticleTuningMigrationTest {
    private fun preferences(values: MutableMap<String, Any>): SharedPreferences {
        fun editor(): SharedPreferences.Editor {
            val changes = linkedMapOf<String, Any?>()
            return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when (method.name) {
                    "putInt", "putBoolean" -> { changes[args[0] as String] = args[1]; proxy }
                    "remove" -> { changes[args[0] as String] = null; proxy }
                    "apply" -> {
                        changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                        null
                    }
                    else -> error("未预期的编辑操作：${method.name}")
                }
            } as SharedPreferences.Editor
        }
        return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "contains" -> values.containsKey(args[0])
                "getInt", "getBoolean" -> values[args[0]] ?: args[1]
                "edit" -> editor()
                else -> error("未预期的偏好操作：${method.name}")
            }
        } as SharedPreferences
    }

    private fun amount(prefs: SharedPreferences, style: ParticleStyle) =
        ParticleTuning.AMOUNT_LEVELS[ParticleTuning.amountIndex(prefs, style)]

    @Test fun `颜色精度继承流体旧值并与飘散独立保存`() {
        val values = mutableMapOf<String, Any>(ParticleTuning.KEY_COLOR_STANDARD to true)
        val prefs = preferences(values)
        ParticleTuning.migrate(prefs, 3.75f)
        assertTrue(ParticleTuning.isColorStandard(prefs, ParticleStyle.FLOW))
        assertFalse(ParticleTuning.isColorStandard(prefs, ParticleStyle.DRIFT))
        ParticleTuning.setColorStandard(prefs, ParticleStyle.DRIFT, true)
        ParticleTuning.setColorStandard(prefs, ParticleStyle.FLOW, false)
        val reopened = preferences(values)
        assertTrue(ParticleTuning.isColorStandard(reopened, ParticleStyle.DRIFT))
        assertFalse(ParticleTuning.isColorStandard(reopened, ParticleStyle.FLOW))
        ParticleTuning.setColorStandard(reopened, ParticleStyle.FLOW, true)
        ParticleTuning.setColorStandard(reopened, ParticleStyle.DRIFT, false)
        assertTrue(ParticleTuning.isColorStandard(prefs, ParticleStyle.FLOW))
        assertFalse(ParticleTuning.isColorStandard(prefs, ParticleStyle.DRIFT))
    }

    @Test fun `两种样式未设置颜色精度时都保持高精度`() {
        val prefs = preferences(mutableMapOf())
        for (style in ParticleStyle.values()) assertFalse(ParticleTuning.isColorStandard(prefs, style))
    }

    @Test fun `两种样式全部旧档位迁移后倍率独立且不变`() {
        for (drift in 0..14) for (flow in 0..14) {
            val values = mutableMapOf<String, Any>("drift_amount_index" to drift, "flow_amount_index" to flow,
                "drift_size_index" to 11, "particle_size_dp_index" to 7)
            val prefs = preferences(values)
            ParticleTuning.migrate(prefs, 3f)
            assertEquals((drift + 2) / 10f, amount(prefs, ParticleStyle.DRIFT), 1e-6f)
            assertEquals((flow + 2) / 10f, amount(prefs, ParticleStyle.FLOW), 1e-6f)
            assertFalse(values.containsKey("drift_amount_index"))
            assertFalse(values.containsKey("flow_amount_index"))
            assertEquals(160, values[ParticleTuning.KEY_DRIFT_SIZE])
            assertEquals(180, values[ParticleTuning.KEY_FLOW_SIZE])
            val after = values.toMap()
            ParticleTuning.migrate(prefs, 3f)
            assertEquals(after, values)
        }
    }

    @Test fun `更早的飘散数量迁移且不会覆盖较新的设置`() {
        for (legacy in 0..10) {
            val values = mutableMapOf<String, Any>("drift_particle_amount" to legacy)
            val prefs = preferences(values)
            ParticleTuning.migrate(prefs, 3f)
            assertEquals((legacy + 5) / 10f, amount(prefs, ParticleStyle.DRIFT), 1e-6f)
            assertFalse(values.containsKey("drift_particle_amount"))
        }
        val values = mutableMapOf<String, Any>("drift_particle_amount" to 10, "drift_amount_index" to 0,
            "flow_amount_index" to 14, ParticleTuning.KEY_FLOW_AMOUNT to 1)
        val prefs = preferences(values)
        ParticleTuning.migrate(prefs, 3f)
        assertEquals(.2f, amount(prefs, ParticleStyle.DRIFT), 0f)
        assertEquals(.1f, amount(prefs, ParticleStyle.FLOW), 0f)
        values[ParticleTuning.KEY_DRIFT_AMOUNT] = 16
        values["drift_amount_index"] = 0
        ParticleTuning.migrate(prefs, 3f)
        assertEquals(1.6f, amount(prefs, ParticleStyle.DRIFT), 0f)
    }

    @Test fun `未设置仍为一倍且新设置可以保存十分之一倍`() {
        val prefs = preferences(mutableMapOf())
        ParticleTuning.migrate(prefs, 3f)
        for (style in ParticleStyle.values()) assertEquals(1f, amount(prefs, style), 0f)
        val minimum = preferences(mutableMapOf(ParticleTuning.KEY_FLOW_AMOUNT to 1, ParticleTuning.KEY_DRIFT_AMOUNT to 1))
        for (style in ParticleStyle.values()) assertEquals(.1f, amount(minimum, style), 0f)
    }

    private fun size(prefs: SharedPreferences, style: ParticleStyle) =
        ParticleTuning.sizeScale(ParticleTuning.sizeIndex(prefs, style))

    private fun nearest(value: Float): Float = ParticleTuning.SIZE_LEVELS.minByOrNull {
        kotlin.math.abs(it - value)
    }!!

    @Test fun `两种大小默认一倍且全部倍率独立保存`() {
        val values = mutableMapOf<String, Any>()
        val prefs = preferences(values)
        for (density in listOf(1f, 1.5f, 3f, 4f)) {
            ParticleTuning.migrate(prefs, density)
            for (style in ParticleStyle.values()) assertEquals(1f, size(prefs, style), 0f)
        }
        for (hundredths in listOf(50, 54, 56, 100, 129, 240, 560, 600)) {
            values[ParticleTuning.KEY_DRIFT_SIZE] = hundredths
            values[ParticleTuning.KEY_FLOW_SIZE] = 100
            assertEquals(hundredths/100f, size(prefs, ParticleStyle.DRIFT), 0f)
            assertEquals(1f, size(prefs, ParticleStyle.FLOW), 0f)
        }
    }

    @Test fun `三十档旧dp按样式基准迁移并清理旧键`() {
        val old = floatArrayOf(.5f,.54f,.56f,.6f,.64f,.72f,.84f,.96f,1.08f,1.29f,1.5f,1.6f,
            1.8f,2f,2.1f,2.4f,2.56f,2.7f,3f,3.2f,3.6f,4f,4.2f,4.5f,4.8f,5f,5.1f,5.4f,5.6f,6f)
        for (density in listOf(1f,1.5f,2.625f,3f,3.5f,4f)) for (index in old.indices) {
            val values = mutableMapOf<String, Any>("flow_size_dp_index" to index, "drift_size_dp_index" to index,
                "drift_size_index" to 0, "particle_size_dp_index" to 0)
            val prefs = preferences(values)
            ParticleTuning.migrate(prefs, density)
            assertEquals(nearest(old[index]/.88f), size(prefs, ParticleStyle.FLOW), 1e-6f)
            assertEquals(nearest(old[index]*density/9.8f), size(prefs, ParticleStyle.DRIFT), 1e-6f)
            for (key in listOf("flow_size_dp_index","drift_size_dp_index","drift_size_index","particle_size_dp_index"))
                assertFalse(values.containsKey(key))
            val after = values.toMap()
            ParticleTuning.migrate(prefs, density)
            assertEquals(after, values)
        }
    }

    @Test fun `旧倍率与早期流体档位按各自取值迁移`() {
        val oldFlow = floatArrayOf(.64f,.72f,.84f,.96f,1.08f,1.29f,1.5f,1.6f,1.8f,2f,2.1f,2.4f,2.56f,2.7f,3f,3.2f)
        for (index in oldFlow.indices) {
            val values = mutableMapOf<String, Any>("particle_size_dp_index" to index,"drift_size_index" to index)
            val prefs = preferences(values)
            ParticleTuning.migrate(prefs, 4f)
            assertEquals(nearest(oldFlow[index]/.88f), size(prefs, ParticleStyle.FLOW), 1e-6f)
            assertEquals(nearest(.5f+index*.1f), size(prefs, ParticleStyle.DRIFT), 1e-6f)
        }
        val oldNine = floatArrayOf(.9f,1f,1.2f,1.6f,2f,2.4f,2.7f,3f,3.2f)
        for (index in oldNine.indices) {
            val values = mutableMapOf<String, Any>("microflake_cell_dp_index" to index)
            val prefs = preferences(values)
            ParticleTuning.migrate(prefs, 3f)
            assertEquals(if(index==0) 1f else nearest(oldNine[index]/.88f), size(prefs, ParticleStyle.FLOW), 1e-6f)
            assertFalse(values.containsKey("microflake_cell_dp_index"))
        }
        val values = mutableMapOf<String, Any>("flow_size_dp_index" to 0,"drift_size_dp_index" to 29,
            ParticleTuning.KEY_FLOW_SIZE to 240,ParticleTuning.KEY_DRIFT_SIZE to 54)
        val prefs = preferences(values)
        ParticleTuning.migrate(prefs, 3f)
        assertEquals(2.4f, size(prefs, ParticleStyle.FLOW), 0f)
        assertEquals(.54f, size(prefs, ParticleStyle.DRIFT), 0f)
    }
}
