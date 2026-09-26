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
        // 第三版：两处共用抵达时间窗；接收的天线那一份没有内半径（倒放里从天线处浮现）
        val receive = params.receiveField(emptyList())
        assertEquals(params.sendField.arrivalLast, receive.arrivalLast, 0f)
        assertEquals(0f, receive.absorbInnerDp, 0f)
        assertTrue(receive.bandDp > params.dustBandDp)
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

    @Test fun `倒放时间轴的上限不小于系数时拒绝`() {
        val broken = source.replace(Regex("(?m)^receive_emerge_pace=.*$"), "receive_emerge_pace=0.4,0.02,0.34,0.5")
        assertThrows(IllegalArgumentException::class.java) { read(broken) }
    }
}
