package com.ywwynm.everythingdone.views.recording

/**
 * Opus 波浪可视化的一帧**客观音频特征**，由 [WaveAudioAnalyzerOpus] 在录音线程从 PCM 提取。
 * 这里只放"测量到的量"，不含视觉语义；语义映射见 [WaveDriveFrameOpus]。
 *
 * 特征选择遵循 docs/features/audio-visualizer-opus/research.md（手机单麦现实限制下最稳健的一组）。
 */
data class WaveAudioFrameOpus(
    /** 时域 RMS（线性，0..1 量级，未归一）。 */
    val rms: Float,
    /** dBFS（约 -96..0）。 */
    val dbFs: Float,
    /** 短窗快速电平（约 512 样本 RMS→0..1），用于低延迟瞬态。 */
    val fastLevel: Float,
    /** 相对电平 0..1：经近期分位自适应归一后的响度，用于区分"正常 vs 大声"。 */
    val relativeLevel: Float,

    // 5 个宏频段能量（PCEN/白化后 0..1）：bass / lowMid / mid / highMid / treble。
    val bass: Float,
    val lowMid: Float,
    val mid: Float,
    val highMid: Float,
    val treble: Float,

    /** 归一化谱质心 0..1（亮暗）。 */
    val centroid: Float,
    /** 谱平坦度 0..1（越高越像噪声/无调性）。 */
    val flatness: Float,
    /** 谱通量（正向差之和，onset novelty 原始量）。 */
    val flux: Float,
    /** 门控后的 onset 强度 0..1（离散事件）。 */
    val onsetStrength: Float,
    /** 事件密度 0..1（近 1–2s 的 onset 率，速度感）。 */
    val eventDensity: Float,

    /** 基频 Hz；清音/低置信/静音时为 0。 */
    val pitchHz: Float,
    /** 基频置信度 0..1。 */
    val pitchConfidence: Float,
    /** 音高在对数音区上的归一化位置 0..1（0.5=中性），用于映射主导波长。 */
    val pitchNormalized: Float
) {
    companion object {
        val SILENCE: WaveAudioFrameOpus = WaveAudioFrameOpus(
            rms = 0f,
            dbFs = -96f,
            fastLevel = 0f,
            relativeLevel = 0f,
            bass = 0f,
            lowMid = 0f,
            mid = 0f,
            highMid = 0f,
            treble = 0f,
            centroid = 0f,
            flatness = 1f,
            flux = 0f,
            onsetStrength = 0f,
            eventDensity = 0f,
            pitchHz = 0f,
            pitchConfidence = 0f,
            pitchNormalized = 0.5f
        )
    }
}
