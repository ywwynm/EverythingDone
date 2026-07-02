package com.ywwynm.everythingdone.views.recording

/**
 * 录音波形可视化的一帧轻量音频特征。
 *
 * 所有值都归一化到 0..1：AudioRecorder 负责从 PCM / FFT 中提取音频含义，
 * VoiceVisualizer 只负责把这些含义映射成水波运动。
 */
internal data class VoiceAudioFrame(
    val loudness: Float,
    val low: Float,
    val lowMid: Float,
    val mid: Float,
    val high: Float,
    val air: Float,
    val transient: Float
) {

    fun bandAt(index: Int): Float = when (index) {
        0 -> low
        1 -> lowMid
        2 -> mid
        3 -> high
        else -> air
    }

    companion object {
        val SILENCE: VoiceAudioFrame = VoiceAudioFrame(
            loudness = 0f,
            low = 0f,
            lowMid = 0f,
            mid = 0f,
            high = 0f,
            air = 0f,
            transient = 0f
        )
    }
}
