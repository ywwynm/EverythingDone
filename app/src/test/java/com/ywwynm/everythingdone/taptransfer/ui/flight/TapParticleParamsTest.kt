package com.ywwynm.everythingdone.taptransfer.ui.flight

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 碰一碰的粒子参数文件：Android 与桌面模拟器共读的那一份。 */
class TapParticleParamsTest {

    private val source: String by lazy {
        val file = generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .map { File(it, "shared/tap-transfer/particles.properties") }
            .first { it.isFile }
        file.readText(Charsets.UTF_8)
    }

    private fun read(text: String) = TapParticleParams.read(text.byteInputStream(Charsets.UTF_8))

    @Test fun `共享参数文件能读出全部取值`() {
        val params = read(source)
        assertTrue(params.outflowMs > 0 && params.reformMs > 0 && params.appearS > 0f)
        assertTrue(params.sink.homingStart < params.sink.homingEnd)
        assertEquals(4, params.outflowEase.size)
        assertEquals(4, params.reformEase.size)
        assertEquals(2, params.glowRadiusDp.size)
    }

    @Test fun `缺项时报出键名`() {
        val lines = source.lines().filterNot { it.startsWith("sink_swirl=") }.joinToString("\n")
        val error = assertThrows(IllegalStateException::class.java) { read(lines) }
        assertTrue(error.message!!.contains("sink_swirl"))
    }

    @Test fun `列表项数不对时拒绝`() {
        val broken = source.replace(Regex("(?m)^send_outflow_ease=.*$"), "send_outflow_ease=0.3,0,0.6")
        assertThrows(IllegalArgumentException::class.java) { read(broken) }
    }

    @Test fun `转向区间的起点不小于终点时拒绝`() {
        val broken = source.replace(Regex("(?m)^sink_homing=.*$"), "sink_homing=0.5,0.08")
        assertThrows(IllegalArgumentException::class.java) { read(broken) }
    }

    @Test fun `天线的光在流出两端为零、中段最亮`() {
        val params = read(source)
        assertEquals(0f, params.glowAt(0f), 1e-6f)
        assertEquals(0f, params.glowAt(1f), 1e-6f)
        val peak = (1..99).maxOf { params.glowAt(it / 100f) }
        assertTrue(peak > .9f)
    }
}
