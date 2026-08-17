package com.ywwynm.everythingdone.views.recording

/**
 * 录音容量边界。RIFF/WAVE 的长度字段只有 32 位，data 超过约 4GiB 后头部溢出、文件失效；
 * 逼近上限时由采集线程触发"停止并保留"，把已录内容封装成合法 WAV。
 */
object AudioRecordingLimits {

    /**
     * data chunk 的最大字节数：UInt32 上限减去头部与重力 chunk 的份额，再留 64MiB 余量，
     * 保证触发停止到实际收尾之间继续写入也不会越过 RIFF 边界。
     * 48kHz 16-bit 立体声约合 6 小时 4 分钟，单声道翻倍。
     */
    const val MAX_RECORDING_DATA_BYTES: Long = 0xFFFF_FFFFL - 64L * 1024L * 1024L

    fun reached(dataBytesWritten: Long): Boolean = dataBytesWritten >= MAX_RECORDING_DATA_BYTES
}
