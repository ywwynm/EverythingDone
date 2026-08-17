package com.ywwynm.everythingdone.views.recording

/** 生成 PCM16 little-endian RIFF/WAVE 头；支持 data 后附加自定义 chunk。 */
object PcmWaveHeader {
    fun create(
        audioLength: Long,
        sampleRate: Int,
        channels: Int,
        trailingChunkBytes: Int = 0
    ): ByteArray {
        require(audioLength >= 0L)
        require(sampleRate > 0)
        require(channels > 0)
        require(trailingChunkBytes >= 0)
        val byteRate = sampleRate.toLong() * channels * BYTES_PER_SAMPLE
        val blockAlign = channels * BYTES_PER_SAMPLE
        val riffLength = audioLength + 36L + trailingChunkBytes
        // 32 位字段装不下时显式失败（上层按封装失败处理），不静默截断产出坏文件；
        // 正常到不了这里——采集侧在 AudioRecordingLimits 上限处已自动停止。
        require(riffLength <= 0xFFFF_FFFFL) {
            "RIFF length $riffLength exceeds the 32-bit WAV limit"
        }
        return ByteArray(44).also { header ->
            writeAscii(header, 0, "RIFF")
            writeUInt32(header, 4, riffLength)
            writeAscii(header, 8, "WAVE")
            writeAscii(header, 12, "fmt ")
            writeUInt32(header, 16, 16L)
            writeUInt16(header, 20, 1)
            writeUInt16(header, 22, channels)
            writeUInt32(header, 24, sampleRate.toLong())
            writeUInt32(header, 28, byteRate)
            writeUInt16(header, 32, blockAlign)
            writeUInt16(header, 34, 16)
            writeAscii(header, 36, "data")
            writeUInt32(header, 40, audioLength)
        }
    }

    private fun writeAscii(target: ByteArray, offset: Int, value: String) {
        for (index in value.indices) target[offset + index] = value[index].code.toByte()
    }

    private fun writeUInt16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xff).toByte()
        target[offset + 1] = ((value ushr 8) and 0xff).toByte()
    }

    private fun writeUInt32(target: ByteArray, offset: Int, value: Long) {
        target[offset] = (value and 0xff).toByte()
        target[offset + 1] = ((value ushr 8) and 0xff).toByte()
        target[offset + 2] = ((value ushr 16) and 0xff).toByte()
        target[offset + 3] = ((value ushr 24) and 0xff).toByte()
    }

    private const val BYTES_PER_SAMPLE = 2
}
