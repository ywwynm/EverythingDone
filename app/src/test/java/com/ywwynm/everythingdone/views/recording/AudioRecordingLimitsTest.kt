package com.ywwynm.everythingdone.views.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRecordingLimitsTest {

    @Test
    fun `上限低于 32 位字段并留出收尾余量`() {
        assertTrue(AudioRecordingLimits.MAX_RECORDING_DATA_BYTES < 0xFFFF_FFFFL)
        assertEquals(
            64L * 1024L * 1024L,
            0xFFFF_FFFFL - AudioRecordingLimits.MAX_RECORDING_DATA_BYTES
        )
    }

    @Test
    fun `未达上限不触发`() {
        assertFalse(AudioRecordingLimits.reached(0L))
        assertFalse(AudioRecordingLimits.reached(AudioRecordingLimits.MAX_RECORDING_DATA_BYTES - 1L))
    }

    @Test
    fun `达到与超过上限均触发`() {
        assertTrue(AudioRecordingLimits.reached(AudioRecordingLimits.MAX_RECORDING_DATA_BYTES))
        assertTrue(AudioRecordingLimits.reached(0xFFFF_FFFFL))
        assertTrue(AudioRecordingLimits.reached(Long.MAX_VALUE))
    }

    @Test
    fun `上限处的 WAV 头仍可生成而字段溢出被显式拒绝`() {
        PcmWaveHeader.create(
            AudioRecordingLimits.MAX_RECORDING_DATA_BYTES,
            48_000,
            2,
            trailingChunkBytes = 1024
        )
        var thrown = false
        try {
            PcmWaveHeader.create(0xFFFF_FFFFL, 48_000, 2)
        } catch (_: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }
}
