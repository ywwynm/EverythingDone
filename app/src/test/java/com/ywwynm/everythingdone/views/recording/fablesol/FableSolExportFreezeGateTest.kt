package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出期间冻结实时水体的判据。
 *
 * 这里钉的不是算术，而是"某条生命周期路径把冻结悄悄撤销了"这一类失效：三个子系统各有自己
 * 的恢复入口，只发一次"停"必然漏。因此每条用例都走一段**事件序列**，断言序列末尾的目标
 * 状态，而不是单点取值。
 */
class FableSolExportFreezeGateTest {

    private fun gate(tiltAvailable: Boolean = true) = FableSolExportFreezeGate().apply {
        setTiltAvailable(tiltAvailable)
        setHostResumed(true)
    }

    @Test
    fun `没有导出对话框时三项都放行`() {
        val target = gate().target()
        assertFalse(target.frozen)
        assertTrue(target.tiltSensorRegistered)
        assertFalse(target.playbackSuppressed)
    }

    @Test
    fun `导出对话框出现即冻结画面_停传感器_压住播放`() {
        val target = gate().setExportDialogPresent(true)
        assertTrue(target.frozen)
        assertFalse(target.tiltSensorRegistered)
        assertTrue(target.playbackSuppressed)
    }

    @Test
    fun `冻结期间切后台再切回来不得恢复传感器`() {
        val gate = gate()
        gate.setExportDialogPresent(true)
        gate.setHostResumed(false)
        // onResume 的 startTiltSensor() 就是漏掉冻结的那条路径。
        val target = gate.setHostResumed(true)
        assertFalse(target.tiltSensorRegistered)
        assertTrue(target.frozen)
        assertTrue(target.playbackSuppressed)
    }

    @Test
    fun `冻结期间切后台再切回来不得解冻画面`() {
        val gate = gate()
        gate.setExportDialogPresent(true)
        gate.setHostResumed(false)
        assertTrue(gate.setHostResumed(true).frozen)
    }

    @Test
    fun `对话框消失后三项全部恢复`() {
        val gate = gate()
        gate.setExportDialogPresent(true)
        val target = gate.setExportDialogPresent(false)
        assertFalse(target.frozen)
        assertTrue(target.tiltSensorRegistered)
        assertFalse(target.playbackSuppressed)
    }

    @Test
    fun `解冻发生在后台时不注册传感器_回前台才注册`() {
        val gate = gate()
        gate.setExportDialogPresent(true)
        gate.setHostResumed(false)
        assertFalse(gate.setExportDialogPresent(false).tiltSensorRegistered)
        assertTrue(gate.setHostResumed(true).tiltSensorRegistered)
    }

    @Test
    fun `用户关掉实时倾斜时任何情形都不注册传感器`() {
        val gate = gate(tiltAvailable = false)
        assertFalse(gate.target().tiltSensorRegistered)
        assertFalse(gate.setExportDialogPresent(true).tiltSensorRegistered)
        assertFalse(gate.setExportDialogPresent(false).tiltSensorRegistered)
    }

    @Test
    fun `重复下发同一状态不改变目标`() {
        val gate = gate()
        val once = gate.setExportDialogPresent(true)
        val twice = gate.setExportDialogPresent(true)
        assert(once == twice)
    }

    /**
     * 配置变化重建时，两个对话框谁先走 onCreateView 没有保证。播放对话框先建的情形下，
     * 它注册回调时扫到的存在性就是唯一依据；之后进度对话框 attach 再来一次回调也必须收敛到
     * 同一结果。
     */
    @Test
    fun `重建后先扫描再收回调_结果一致`() {
        val scannedFirst = gate().apply { setExportDialogPresent(true) }.target()
        val calledBack = gate().apply {
            setExportDialogPresent(false)
            setExportDialogPresent(true)
        }.target()
        assert(scannedFirst == calledBack)
    }
}
