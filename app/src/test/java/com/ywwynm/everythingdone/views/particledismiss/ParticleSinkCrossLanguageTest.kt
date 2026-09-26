package com.ywwynm.everythingdone.views.particledismiss

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终点整形（[ParticleSink.shape] / [ParticleSink.target]）与桌面模拟器
 * （`docs/features/thing-transfer/particle-sim/sim_model.py`）逐项一致。
 *
 * 对照数据由模拟器的 `make_sink_fixture.py` 生成。两边任何一边改了算法而另一边没跟上，这里就失败：
 * 先把两边改成同一套，再重新生成对照数据。
 */
class ParticleSinkCrossLanguageTest {

    private class Fixture(val geometry: ParticleSink.Geometry, val span: Float, val tuning: ParticleSink.Tuning,
                          val input: FloatArray, val output: FloatArray, val targets: FloatArray)

    private fun load(): Fixture {
        val file = generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .map { File(it, "docs/features/thing-transfer/particle-sim/fixtures/sink-shape.txt") }
            .first { it.isFile }
        var geometry: ParticleSink.Geometry? = null
        var span = 0f
        var tuning: ParticleSink.Tuning? = null
        val input = ArrayList<Float>()
        val output = ArrayList<Float>()
        val targets = ArrayList<Float>()
        for (line in file.readText(Charsets.UTF_8).replace("\r\n", "\n").lines()) {
            val words = line.trim().split(' ').filter { it.isNotEmpty() }
            when (words.firstOrNull()) {
                "geometry" -> {
                    val v = words.drop(1).map { it.toFloat() }
                    geometry = ParticleSink.Geometry(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7])
                }
                "span" -> span = words[1].toFloat()
                "tuning" -> {
                    val v = words.drop(1).map { it.toFloat() }
                    tuning = ParticleSink.Tuning(releaseScale = v[0], arrivalEnd = v[1], flightBase = v[2],
                        flightPerSpan = v[3], flightJitter = v[4], minLife = v[5])
                }
                "row" -> {
                    val parts = line.removePrefix("row").split('|').map { part ->
                        part.trim().split(' ').filter { it.isNotEmpty() }.map { it.toFloat() }
                    }
                    check(parts.size == 3 && parts[0].size == 12 && parts[1].size == 12 && parts[2].size == 2)
                    input += parts[0]; output += parts[1]; targets += parts[2]
                }
            }
        }
        return Fixture(checkNotNull(geometry), span, checkNotNull(tuning), input.toFloatArray(),
            output.toFloatArray(), targets.toFloatArray())
    }

    @Test fun `终点的选取与模拟器逐位一致`() {
        val f = load()
        val out = FloatArray(2)
        val count = f.input.size / 12
        assertTrue("对照数据太少", count >= 200)
        for (i in 0 until count) {
            ParticleSink.target(f.input, i * 12, f.geometry, out)
            assertEquals("第 $i 片终点 x", f.targets[i * 2], out[0], 0f)
            assertEquals("第 $i 片终点 y", f.targets[i * 2 + 1], out[1], 0f)
        }
    }

    // ---------------------------------------------------------------- 第三版（SINK_FIELD）

    private class FieldFixture(
        val geometry: ParticleSink.Geometry, val field: ParticleSink.Field, val tuning: ParticleSink.Tuning,
        val cardWidth: Float, val cardHeight: Float, val seed: Int,
        val input: FloatArray, val visible: BooleanArray, val output: FloatArray,
        val frameBound: BooleanArray, val absorbed: BooleanArray, val targets: FloatArray
    )

    private fun loadField(): FieldFixture {
        val file = generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .map { File(it, "docs/features/thing-transfer/particle-sim/fixtures/sink-field.txt") }
            .first { it.isFile }
        var head = FloatArray(0)
        var flow = FloatArray(4)
        var band = FloatArray(4)
        val groups = ArrayList<ParticleSink.Group>()
        var card = FloatArray(2)
        var seed = 0
        var blend = 0f
        var fieldValues = FloatArray(0)
        val input = ArrayList<Float>()
        val visible = ArrayList<Boolean>()
        val output = ArrayList<Float>()
        val frame = ArrayList<Boolean>()
        val absorbed = ArrayList<Boolean>()
        val targets = ArrayList<Float>()
        for (line in file.readText(Charsets.UTF_8).replace("\r\n", "\n").lines()) {
            val words = line.trim().split(' ').filter { it.isNotEmpty() }
            fun numbers() = words.drop(1).map { it.toFloat() }.toFloatArray()
            when (words.firstOrNull()) {
                "geometry" -> head = numbers()
                "flow" -> flow = numbers()
                "band" -> band = numbers()
                "group" -> numbers().let { v -> groups += ParticleSink.Group(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7]) }
                "card" -> card = numbers()
                "seed" -> seed = words[1].toInt()
                "blend" -> blend = words[1].toFloat()
                "field" -> fieldValues = numbers()
                "row" -> {
                    val parts = line.removePrefix("row").split('|').map { part ->
                        part.trim().split(' ').filter { it.isNotEmpty() }
                    }
                    check(parts.size == 4 && parts[0].size == 13 && parts[1].size == 12 && parts[2].size == 2 &&
                        parts[3].size == 2)
                    input += parts[0].take(12).map { it.toFloat() }
                    visible += parts[0][12] == "1"
                    output += parts[1].map { it.toFloat() }
                    frame += parts[2][0] == "1"
                    absorbed += parts[2][1] == "1"
                    targets += parts[3].map { it.toFloat() }
                }
            }
        }
        val geometry = ParticleSink.Geometry(head[0], head[1], head[2], head[3], head[4], head[5], head[6], head[7],
            field = true, flow = flow, band = band, groups = groups)
        val unit = ParticleMicroflakeModel.DP_UNITS.toFloat()
        // 整形只用到抵达时间窗、途中消散的寿命倍数与扰动；其余几项只进着色器
        val field = ParticleSink.Field(
            arrivalFirst = fieldValues[0], arrivalLast = fieldValues[1],
            glideStart = 0f, glideEnd = 1f, glideGain = 1f, spiral = 0f, absorbShare = flow[1], spreadDp = 0f,
            drift = 0f, freeLife = fieldValues[5], absorbInnerDp = 0f, absorbOuterDp = 1f, fadeS = .15f,
            nearScale = 1f, bandDp = 0f, bandSpiral = 0f, curl = 0f, vortexDp = 1f, groupBorn = 0f, groupArrive = 0f,
            groupSwirl = 0f, handoffGlide = .1f, handoffCurl = 1f, handoffScale = 1f, blendDp = blend / unit)
        val tuning = ParticleSink.Tuning(releaseScale = fieldValues[2], arrivalEnd = fieldValues[3],
            minLife = fieldValues[4])
        return FieldFixture(geometry, field, tuning, card[0], card[1], seed, input.toFloatArray(),
            visible.toBooleanArray(), output.toFloatArray(), frame.toBooleanArray(), absorbed.toBooleanArray(),
            targets.toFloatArray())
    }

    @Test fun `第三版：按远近分配来源后的随机向量与模拟器逐位一致`() {
        val f = loadField()
        val values = f.input.copyOf()
        ParticleSink.shapeForField(values, f.geometry, f.field, f.tuning, f.cardWidth, f.cardHeight, f.seed, f.visible)
        val count = values.size / 12
        assertTrue("对照数据太少", count >= 300)
        var moved = 0
        for (i in 0 until count) {
            for (k in 8..11) assertEquals("第 $i 片随机数 $k", f.output[i * 12 + k], values[i * 12 + k], 0f)
            if ((8..11).any { f.input[i * 12 + it] != values[i * 12 + it] }) moved++
        }
        assertTrue("来源分配没有换过任何随机向量", moved > count / 2)
    }

    @Test fun `第三版：流向边框与被吸入的判定与模拟器逐位一致`() {
        val f = loadField()
        val values = f.input.copyOf()
        ParticleSink.shapeForField(values, f.geometry, f.field, f.tuning, f.cardWidth, f.cardHeight, f.seed, f.visible)
        val count = values.size / 12
        var frameVisible = 0
        for (i in 0 until count) {
            assertEquals("第 $i 片是否流向边框", f.frameBound[i], ParticleSink.frameBound(values, i * 12, f.geometry))
            assertEquals("第 $i 片是否被吸入", f.absorbed[i], ParticleSink.absorbed(values, i * 12, f.geometry))
            if (f.visible[i] && f.frameBound[i]) frameVisible++
        }
        // 占比按看得见的颗粒算（0.25），看不见的只分到剩下的名额
        val shown = f.visible.count { it }
        assertEquals("看得见的颗粒里流向边框的占比", .25, frameVisible.toDouble() / shown, .05)
    }

    @Test fun `第三版：出生、寿命与落点与模拟器一致`() {
        val f = loadField()
        val values = f.input.copyOf()
        ParticleSink.shapeForField(values, f.geometry, f.field, f.tuning, f.cardWidth, f.cardHeight, f.seed, f.visible)
        val out = FloatArray(2)
        for (i in 0 until values.size / 12) {
            for (field in 0 until 12) {
                // 出生与寿命经过乘加（两边都是单精度），允许一两个 ulp；其余各项原样或整组搬动
                val tolerance = if (field == 2 || field == 6) 2e-6f else 0f
                assertEquals("第 $i 片第 $field 项", f.output[i * 12 + field], values[i * 12 + field], tolerance)
            }
            ParticleSink.fieldTarget(values, i * 12, f.geometry, f.cardWidth, f.cardHeight, out)
            // 落点经过 hypot / pow / log / 三角函数，两边的单精度实现可以差几个 ulp
            assertEquals("第 $i 片落点 x", f.targets[i * 2], out[0], 2e-3f)
            assertEquals("第 $i 片落点 y", f.targets[i * 2 + 1], out[1], 2e-3f)
        }
    }

    @Test fun `释放时刻与寿命的改写与模拟器一致`() {
        val f = load()
        val values = f.input.copyOf()
        ParticleSink.shape(values, f.geometry, f.span, f.tuning)
        for (i in values.indices) {
            val field = i % 12
            // 出生时刻与寿命经过 hypot（Kotlin 走双精度再取整，NumPy 在单精度里算），允许一两个 ulp
            val tolerance = if (field == 2 || field == 6) 2e-6f else 0f
            assertEquals("第 ${i / 12} 片第 $field 项", f.output[i], values[i], tolerance)
        }
    }
}
