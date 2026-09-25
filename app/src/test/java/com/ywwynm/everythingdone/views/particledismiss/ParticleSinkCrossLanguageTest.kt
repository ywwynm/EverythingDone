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
