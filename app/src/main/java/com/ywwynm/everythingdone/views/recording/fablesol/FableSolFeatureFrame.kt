package com.ywwynm.everythingdone.views.recording.fablesol

/**
 * 一帧实时音频特征（对应 features.py 的 frame dict）。所有 01 字段均为 0..1。
 * 由 [FableSolRealtimeAnalyzer] 在音频线程产出，[FableSolFeatureMapper] 消费。
 */
class FableSolFeatureFrame(
    @JvmField val t: Double,
    @JvmField val loudness01: Double,
    @JvmField val bandLow: Double,
    @JvmField val bandMid: Double,
    @JvmField val bandHigh: Double,
    @JvmField val relLow: Double,
    @JvmField val relMid: Double,
    @JvmField val relHigh: Double,
    @JvmField val centroid01: Double,
    @JvmField val spectralTilt01: Double,
    @JvmField val flatness01: Double,
    @JvmField val percussive01: Double,
    @JvmField val punch01: Double,
    @JvmField val stereoWidth01: Double,
    @JvmField val pan01: Double,
    @JvmField val onsetEnv: Double,
    @JvmField val flow01: Double,
    @JvmField val activity01: Double,
    @JvmField val loudDb: Double,
    @JvmField val floorDb: Double,
    @JvmField val isSilent: Boolean,
    @JvmField val tempoBpm: Double,
    @JvmField val beatPhase01: Double,
    @JvmField val beatConf01: Double
)

/** 离散事件（对应 features.py 产出的 onset / section event dict）。 */
sealed class FableSolEvent {
    abstract val t: Double

    /** 音头事件。 */
    class Onset(
        override val t: Double,
        @JvmField val strength01: Double,
        @JvmField val centroid01: Double,
        @JvmField val low: Double,
        @JvmField val mid: Double,
        @JvmField val high: Double,
        @JvmField val flatness01: Double,
        @JvmField val pan01: Double,
        @JvmField val stereoWidth01: Double
    ) : FableSolEvent()

    /** 段落边界事件（Foote 新奇度）。 */
    class Section(
        override val t: Double,
        @JvmField val magnitude01: Double,
        @JvmField val energy01: Double,
        @JvmField val brightness01: Double,
        @JvmField val surge: Boolean = true
    ) : FableSolEvent()
}
