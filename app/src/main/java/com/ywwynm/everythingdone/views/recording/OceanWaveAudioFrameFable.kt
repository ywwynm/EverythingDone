package com.ywwynm.everythingdone.views.recording

/**
 * Fable 海浪可视化的一帧音频特征，由 [OceanWaveAudioAnalyzerFable] 在录音线程
 * 从 PCM 中提取。数值语义与映射规则见 docs/features/audio-visualizer-fable/plan.md。
 */
data class OceanWaveAudioFrameFable(
    /** 响度 0..1：RMS→dBFS 后按 [-52, -14]dB 线性映射。 */
    val loudness: Float,
    /** 短窗响度上升沿 0..1，驱动即时微波包与巨浪瞬态触发。 */
    val transient: Float,
    /** 基频 Hz；清音/静音/低置信时为 0。 */
    val pitchHz: Float,
    /** 基频置信度 0..1。 */
    val pitchConfidence: Float,
    /** 该帧是否浊音（置信度与响度门控后的结论）。 */
    val voiced: Boolean,
    /** 语速：音节/秒（约 2.5s 窗、EMA 平滑）。 */
    val syllableRate: Float
) {
    companion object {
        val SILENCE: OceanWaveAudioFrameFable =
            OceanWaveAudioFrameFable(0f, 0f, 0f, 0f, false, 0f)
    }
}
