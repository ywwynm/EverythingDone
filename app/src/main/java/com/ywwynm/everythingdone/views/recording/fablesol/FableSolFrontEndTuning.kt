package com.ywwynm.everythingdone.views.recording.fablesol

/**
 * 音频线程使用的 FableSol 声音分析调参快照。
 *
 * UI 线程只写四个 volatile 标量；录音线程在每批 PCM 进入 Analyzer 前调用 [applyTo]，
 * 避免跨线程直接改写 Analyzer 的其它运行状态。
 */
class FableSolFrontEndTuning {

    @Volatile
    var agcWindowS: Double = DEFAULT_AGC_WINDOW_S
        private set

    @Volatile
    var silenceGateDb: Double = DEFAULT_SILENCE_GATE_DB
        private set

    @Volatile
    var expanderAmount: Double = DEFAULT_EXPANDER_AMOUNT
        private set

    @Volatile
    var relativeLoudnessMix: Double = DEFAULT_RELATIVE_LOUDNESS_MIX
        private set

    /** 写入单项调参；未知 key 返回 false，合法 key 在这里统一钳位。 */
    fun set(key: String, value: Double): Boolean {
        when (key) {
            KEY_AGC_WINDOW_S -> agcWindowS = value.coerceIn(3.0, 30.0)
            KEY_SILENCE_GATE_DB -> silenceGateDb = value.coerceIn(0.0, 18.0)
            KEY_EXPANDER_AMOUNT -> expanderAmount = value.coerceIn(0.0, 1.0)
            KEY_RELATIVE_LOUDNESS_MIX -> relativeLoudnessMix = value.coerceIn(0.0, 0.6)
            else -> return false
        }
        return true
    }

    /** 录音线程调用：把同一时刻读到的快照套到下一批音频分析。 */
    fun applyTo(analyzer: FableSolRealtimeAnalyzer) {
        analyzer.agcWindowS = agcWindowS
        analyzer.gateDb = silenceGateDb
        analyzer.expander = expanderAmount
        analyzer.relativeLoudnessMix = relativeLoudnessMix
    }

    companion object {
        const val KEY_AGC_WINDOW_S = "agc_window_s"
        const val KEY_SILENCE_GATE_DB = "silence_gate_db"
        const val KEY_EXPANDER_AMOUNT = "expander_amount"
        const val KEY_RELATIVE_LOUDNESS_MIX = "relative_loudness_mix"

        const val DEFAULT_AGC_WINDOW_S = 24.0
        const val DEFAULT_SILENCE_GATE_DB = 6.0
        const val DEFAULT_EXPANDER_AMOUNT = 0.32
        const val DEFAULT_RELATIVE_LOUDNESS_MIX = FableSolPerceptualCalibrator.DEFAULT_RELATIVE_MIX

        fun defaultValue(key: String): Double = when (key) {
            KEY_AGC_WINDOW_S -> DEFAULT_AGC_WINDOW_S
            KEY_SILENCE_GATE_DB -> DEFAULT_SILENCE_GATE_DB
            KEY_EXPANDER_AMOUNT -> DEFAULT_EXPANDER_AMOUNT
            KEY_RELATIVE_LOUDNESS_MIX -> DEFAULT_RELATIVE_LOUDNESS_MIX
            else -> throw IllegalArgumentException("Unknown FableSol front-end key: $key")
        }
    }
}
