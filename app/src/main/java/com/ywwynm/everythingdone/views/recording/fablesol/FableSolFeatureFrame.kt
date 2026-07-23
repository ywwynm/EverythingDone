package com.ywwynm.everythingdone.views.recording.fablesol

/**
 * 一帧实时音频特征（对应 features.py 的 frame dict）。所有 01 字段均为 0..1。
 * [loudness01] 保留旧 Android 诊断刻度；视觉水位与七境必须读取 [waterDrive01]。
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
    @JvmField val beatConf01: Double,
    @JvmField val loudMDb: Double = loudDb,
    @JvmField val loudSDb: Double = loudDb,
    @JvmField val music01: Double = 0.0,
    @JvmField val fluct4hz01: Double = 0.0,
    @JvmField val fluxCv: Double = 0.0,
    @JvmField val f0Hz: Double = 0.0,
    @JvmField val pitch01: Double = 0.0,
    @JvmField val pitchRel01: Double = 0.5,
    @JvmField val voiced01: Double = 0.0,
    @JvmField val sylRateHz: Double = 0.0,
    @JvmField val hnr01: Double = 0.0,
    @JvmField val arousal01: Double = 0.0,
    @JvmField val loom01: Double = 0.0,
    @JvmField val impulse01: Double = 0.0,
    // 感知标定 v2。旧字段继续保留，便于 Canvas/GL 在迁移期间共享同一帧类型。
    @JvmField val loudnessRaw01: Double = loudness01,
    @JvmField val loudnessAbsolute01: Double = loudness01,
    @JvmField val loudnessMomentary01: Double = loudness01,
    @JvmField val loudnessTransientBoost01: Double = 0.0,
    @JvmField val waterDrive01: Double = loudness01,
    @JvmField val legacyLoudness01: Double = loudness01,
    @JvmField val loudP10Db: Double = 0.0,
    @JvmField val loudP95Db: Double = 0.0,
    @JvmField val loudMUntrimmedDb: Double = loudMDb,
    @JvmField val loudSUntrimmedDb: Double = loudSDb,
    @JvmField val inputLoudnessTrimDb: Double = 0.0,
    @JvmField val speed01: Double = flow01,
    @JvmField val speedAbs01: Double = flow01,
    @JvmField val speedRank01: Double = activity01,
    @JvmField val kineticDrive01: Double = flow01,
    @JvmField val kineticTarget01: Double = flow01,
    @JvmField val motionContextBoost01: Double = 0.0,
    @JvmField val percussiveMotion01: Double = 0.0,
    @JvmField val vocalMotion01: Double = 0.0,
    @JvmField val harmonicMotion01: Double = 0.0,
    @JvmField val beatMotion01: Double = 0.0,
    @JvmField val grooveMotion01: Double = 0.0,
    @JvmField val intensityDrive01: Double = loudness01,
    @JvmField val targetDps: Double = 0.0,
    @JvmField val musicArousal01: Double = 0.0,
    @JvmField val punchLu01: Double = 0.0,
    @JvmField val energy01: Double = 0.0,
    @JvmField val energyRising01: Double = 0.0,
    @JvmField val buildUp01: Double = 0.0,
    @JvmField val gradeDrive01: Double = 0.0,
    @JvmField val liftScore01: Double = 0.0,
    @JvmField val climaxScore01: Double = 0.0,
    @JvmField val gradeAbsolute01: Double = 0.0,
    @JvmField val gradeContext01: Double = 0.0,
    @JvmField val vocalSoloPenalty01: Double = 0.0,
    @JvmField val zLoud: Double = 0.0,
    @JvmField val zFlux: Double = 0.0,
    @JvmField val zOnsetRate: Double = 0.0,
    @JvmField val zBass: Double = 0.0,
    @JvmField val zCentroid: Double = 0.0,
    @JvmField val novelty01: Double = 0.0,
    // 说话锚定（plan-20260723）：raw 用力原料 + 主导度/用力档/展示动能。
    @JvmField val effortSpectral01: Double = 0.0,
    @JvmField val rawLowShare01: Double = 0.0,
    @JvmField val rawPresenceShare01: Double = 0.0,
    @JvmField val voiceDominance01: Double = 0.0,
    @JvmField val speechEffort01: Double = 0.0,
    @JvmField val speechWater01: Double = 0.0,
    @JvmField val displayKinetic01: Double = -1.0,
    /** 句间悬停后的 display 侧静默标志；-1 表示旧调用方（回退 raw isSilent）。 */
    @JvmField val displayIsSilent01: Double = -1.0,
    // 可选的展示轨。合成帧默认 -1 以保持旧调用方 identity；产品分析帧始终显式写值。
    @JvmField val displayWaterDrive01: Double = -1.0,
    @JvmField val displayGradeDrive01: Double = -1.0,
    @JvmField val displayLiftScore01: Double = -1.0,
    @JvmField val displayClimaxScore01: Double = -1.0,
    @JvmField val displayRelLow: Double = -1.0,
    @JvmField val displayRelMid: Double = -1.0,
    @JvmField val displayRelHigh: Double = -1.0,
    @JvmField val displayCentroid01: Double = -1.0
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
        @JvmField val stereoWidth01: Double,
        @JvmField var impulse01: Double = Double.NaN,
        @JvmField var loom01: Double = Double.NaN
    ) : FableSolEvent()

    /** 音节重音事件：在旋律角色层出生一条宽浪。 */
    class Prominence(
        override val t: Double,
        @JvmField val strength01: Double,
        @JvmField val pitchRel01: Double
    ) : FableSolEvent()

    /** 段落边界事件（Foote 新奇度）。 */
    class Section(
        override val t: Double,
        @JvmField val magnitude01: Double,
        @JvmField val energy01: Double,
        @JvmField val brightness01: Double,
        @JvmField val confidence01: Double = 0.0,
        @JvmField val surge: Boolean = true
    ) : FableSolEvent()

    /** 未达到主段落阈值的可诊断新奇度峰；与 Python 一样不驱动动画。 */
    class NoveltyMinor(
        override val t: Double,
        @JvmField val magnitude01: Double,
        @JvmField val confidence01: Double
    ) : FableSolEvent()

    /** 因果 build-up 后的低频/响度共同到达，不依赖离线标签或神经网络。 */
    class Drop(
        override val t: Double,
        @JvmField val confidence01: Double
    ) : FableSolEvent()
}
