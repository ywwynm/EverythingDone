package com.ywwynm.everythingdone.views.recording

/**
 * Opus 波浪可视化的一帧**语义驱动值**，由 [WaveAudioAnalyzerOpus] 把 [WaveAudioFrameOpus] 客观
 * 特征映射而来，[WaveVisualizerOpus] 只消费本帧。语义直接对应"声音→波浪群"（见
 * docs/features/audio-visualizer-opus/decisions.md D5/D8/D10）：声音塑造的是一群离散的浪的
 * **数量、大小、落在哪些层、传播速度**，而非一片水面纹理。
 *
 * 所有字段均为 0..1（除 pitchWavelength 亦 0..1），已在分析器内做过平滑/门控/自适应归一，
 * visualizer 仍需以连续包络继续追随，不得直接 set 到最终几何。
 */
data class WaveDriveFrameOpus(
    /** 整体响度 0..1。 */
    val loudness: Float,
    /** 声强对比 0..1（拉开正常/大声），驱动浪的高度与数量。 */
    val intensity: Float,
    /** 安静度 0..1（≈1−presence），门控静音态。 */
    val quietness: Float,
    /** 速度感 0..1（event density / tempo），驱动生成率与传播/流速。 */
    val pace: Float,

    /** 亮度 0..1（谱质心）：越亮→浪越偏小、偏快、偏前层。 */
    val brightness: Float,
    /** 低频权重 0..1（band ratio）：越大→大而慢的长浪、偏后/深层越多。 */
    val bassWeight: Float,
    /** 中频权重 0..1：主体人声浪。 */
    val midWeight: Float,
    /** 高频权重 0..1：小而快的表层碎浪。 */
    val trebleWeight: Float,

    /** 离散事件强度 0..1（onset/拍点）：>门槛即催生一道主浪。 */
    val onset: Float,
    /** 持续驱动 0..1（说话/音乐的持续存在）：按 pace 周期性催生新浪。 */
    val sustainDrive: Float,

    /** 主导波长归一 0..1（由可信音高派生，0.5=中性）。 */
    val pitchWavelength: Float,
    /** 音高置信度 0..1（低置信时忽略 pitchWavelength）。 */
    val pitchConfidence: Float,

    /** 水位目标 0..1（响度慢潮）：0=静息低位、1=强声高位。 */
    val waterLevel: Float,
    /** 噪声度 0..1（谱平坦度）：抑制稳态风噪对生成的驱动。 */
    val noiseLike: Float,

    /** 原始客观特征，供调试与扩展。 */
    val feature: WaveAudioFrameOpus
) {
    companion object {
        val SILENCE: WaveDriveFrameOpus = WaveDriveFrameOpus(
            loudness = 0f,
            intensity = 0f,
            quietness = 1f,
            pace = 0f,
            brightness = 0f,
            bassWeight = 0f,
            midWeight = 0f,
            trebleWeight = 0f,
            onset = 0f,
            sustainDrive = 0f,
            pitchWavelength = 0.5f,
            pitchConfidence = 0f,
            waterLevel = 0f,
            noiseLike = 1f,
            feature = WaveAudioFrameOpus.SILENCE
        )
    }
}
