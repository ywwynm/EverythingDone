package com.ywwynm.everythingdone.views.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmWaveHeaderTest {

    @Test
    fun `stereo header uses correct channels byte rate and block align`() {
        val header = PcmWaveHeader.create(
            audioLength = 48_000L * 4L,
            sampleRate = 48_000,
            channels = 2
        )

        assertEquals(2, uint16(header, 22))
        assertEquals(192_000L, uint32(header, 28))
        assertEquals(4, uint16(header, 32))
        assertEquals(48_000L * 4L, uint32(header, 40))
    }

    @Test
    fun `riff length includes trailing gravity chunk`() {
        val header = PcmWaveHeader.create(
            audioLength = 9_600L,
            sampleRate = 48_000,
            channels = 1,
            trailingChunkBytes = 144
        )

        assertEquals(9_600L + 36L + 144L, uint32(header, 4))
        assertTrue(String(header, 0, 4, Charsets.US_ASCII) == "RIFF")
        assertTrue(String(header, 36, 4, Charsets.US_ASCII) == "data")
    }

    private fun uint16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun uint32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xffL) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xffL) shl 24))
}
