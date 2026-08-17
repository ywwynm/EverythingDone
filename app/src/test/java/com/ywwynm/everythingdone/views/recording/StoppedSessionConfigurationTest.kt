package com.ywwynm.everythingdone.views.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoppedSessionConfigurationTest {

    @Test
    fun `microphone session after input death requires full reconfiguration`() {
        // ERROR_DEAD_OBJECT 等输入流死亡：AudioRecord 必须重建，复用检查（state 仍为
        // INITIALIZED）拦不住失效对象，只能在这里判为未配置。
        assertFalse(
            stoppedSessionRemainsConfigured(
                AudioInputMode.MICROPHONE,
                AudioRecordingNotice.CAPTURE_FAILED,
                inputFaulted = true
            )
        )
    }

    @Test
    fun `input fault flag alone forces reconfiguration when stop reason races ahead`() {
        // 手动停止/容量停止与 read 死亡并发：故障回调被 shouldRun 抑制或被 STOPPED 态
        // 忽略，停止原因仍是 NONE / SIZE_LIMIT_REACHED——引擎侧即刻记录的 inputFaulted
        // 必须独立否决复用。
        assertFalse(
            stoppedSessionRemainsConfigured(
                AudioInputMode.MICROPHONE,
                AudioRecordingNotice.NONE,
                inputFaulted = true
            )
        )
        assertFalse(
            stoppedSessionRemainsConfigured(
                AudioInputMode.MICROPHONE,
                AudioRecordingNotice.SIZE_LIMIT_REACHED,
                inputFaulted = true
            )
        )
    }

    @Test
    fun `microphone session after healthy stops keeps the fast restart path`() {
        assertTrue(
            stoppedSessionRemainsConfigured(
                AudioInputMode.MICROPHONE,
                AudioRecordingNotice.NONE,
                inputFaulted = false
            )
        )
        assertTrue(
            stoppedSessionRemainsConfigured(
                AudioInputMode.MICROPHONE,
                AudioRecordingNotice.SIZE_LIMIT_REACHED,
                inputFaulted = false
            )
        )
        // 文件写入中断是存储问题，采集对象健康，保持复用。
        assertTrue(
            stoppedSessionRemainsConfigured(
                AudioInputMode.MICROPHONE,
                AudioRecordingNotice.FILE_WRITE_INTERRUPTED,
                inputFaulted = false
            )
        )
    }

    @Test
    fun `system-audio sessions always reconfigure after stop`() {
        // 停止完成时投影已释放，无论停止原因如何都必须完整重建。
        for (notice in AudioRecordingNotice.entries) {
            assertFalse(
                stoppedSessionRemainsConfigured(AudioInputMode.SYSTEM, notice, inputFaulted = false)
            )
            assertFalse(
                stoppedSessionRemainsConfigured(
                    AudioInputMode.SYSTEM_AND_MICROPHONE,
                    notice,
                    inputFaulted = false
                )
            )
        }
    }
}
