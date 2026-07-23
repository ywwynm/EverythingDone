package com.ywwynm.everythingdone.views.recording.fablesol

/**
 * 七境使用的纯 DSP 标量帧。调用方应复用同一个实例，避免在约 94Hz 的分析热路径分配对象。
 *
 * [vocalMotion01] 是常规 DSP 运动特征，不依赖、也不包含可选的人声神经网络。
 */
class FableSolPerceptualFrame {
    @JvmField var t = 0.0
    @JvmField var silent = true
    @JvmField var waterDrive01 = 0.0
    @JvmField var intensityDrive01 = 0.0
    @JvmField var kineticDrive01 = 0.0
    @JvmField var percussiveMotion01 = 0.0
    @JvmField var vocalMotion01 = 0.0
    @JvmField var harmonicMotion01 = 0.0
    @JvmField var grooveMotion01 = 0.0
    @JvmField var musicArousal01 = 0.0
    @JvmField var energy01 = 0.0
    @JvmField var energyRising01 = 0.0
    @JvmField var buildUp01 = 0.0
    @JvmField var positiveNovelty01 = 0.0
    @JvmField var punch01 = 0.0
    @JvmField var punchLu01 = 0.0
    @JvmField var lowShare01 = 0.0
    @JvmField var domainGradeTrim01 = 0.0
    /** [FableSolCausalStateEvidence] 的当前输出；未接线时保持 NaN 并由门控回退 intensity。 */
    @JvmField var gradeDrive01 = Double.NaN
    @JvmField var motionContextBoost01 = 0.0
    @JvmField var centroid01 = 0.5
    // 巨浪 gate 的原始响度/上下文诊断；展示轨不得覆盖这些字段。
    @JvmField var loudSDb = -120.0
    @JvmField var loudP10Db = 0.0
    @JvmField var loudP95Db = 0.0
    @JvmField var gradeContext01 = 0.0
    // 说话域（plan-20260723）：巨浪 gate 的说话资格与转变分支输入；恒为 raw。
    @JvmField var voiceDominance01 = 0.0
    @JvmField var music01 = 0.0
    @JvmField var speechEffort01 = 0.0
    @JvmField var fluct4hz01 = 0.0
    @JvmField var sylRateHz = 0.0
    /** D200：capture 域标志（input_loudness_trim_db>0）；录音域走到达评分门。 */
    @JvmField var captureDomain = false
}

/** 七境的持续等级。使用 Int 而不是 enum 进入解码器内环，避免装箱。 */
object FableSolSustainedGrade {
    const val CALM = 0
    const val GROOVE = 1
    const val PEAK = 2
    const val COUNT = 3
}

/** 七种可见境；LIFT/CLIMAX 是叠加在持续等级上的阶段。 */
enum class FableSolVisualState(@JvmField val label: String) {
    IDLE("镜塘"),
    SILENCE("寒塘清浅"),
    CALM("洞庭风细"),
    GROOVE("湖光潋滟"),
    LIFT("云生沧海"),
    PEAK("层波叠浪"),
    CLIMAX("云舒浪卷")
}

/** 原地写入的证据结果；实例由分析链持有并复用。 */
class FableSolStateEvidence {
    @JvmField var gradeDrive01 = 0.0
    @JvmField var liftScore01 = 0.0
    @JvmField var climaxScore01 = 0.0
    @JvmField var gradeAbsolute01 = 0.0
    @JvmField var gradeContext01 = 0.0
    @JvmField var vocalSoloPenalty01 = 0.0
}

/** 原地写入的持续等级解码结果。 */
class FableSolGradeDecision {
    @JvmField var grade = FableSolSustainedGrade.CALM
    @JvmField var calmProbability = 1.0
    @JvmField var grooveProbability = 0.0
    @JvmField var peakProbability = 0.0
    @JvmField var transitionProgress01 = 0.0
}

enum class FableSolStateReason {
    NONE,
    HEARD,
    SILENCE,
    GRADE_SOFT_UP,
    GRADE_SOFT_DOWN,
    PHASE_RELEASE,
    CAUSAL_LIFT,
    CAUSAL_NEW_HIGH,
    DROP
}

/** 七境决策的原地输出；视觉弹簧和渲染参数由 Mapper/Simulation 负责。 */
class FableSolStateDecision {
    @JvmField var state = FableSolVisualState.IDLE
    @JvmField var baseState = FableSolVisualState.CALM
    @JvmField var changed = false
    @JvmField var reason = FableSolStateReason.NONE
    @JvmField var gradeDrive01 = 0.0
    @JvmField var liftScore01 = 0.0
    @JvmField var climaxScore01 = 0.0
    @JvmField var calmProbability = 1.0
    @JvmField var grooveProbability = 0.0
    @JvmField var peakProbability = 0.0
    @JvmField var evidenceProgress01 = 0.0
}

enum class FableSolGrandWaveReason {
    DROP,
    CAUSAL_ARRIVAL,
    PEAK_PHRASE_REPEAT,
    SPEECH_TRANSITION,
    CAPTURE_ARRIVAL
}

/** 巨浪候选请求。只有 [FableSolGrandWaveEventGate.step] 返回 true 时字段才有效。 */
class FableSolGrandWaveRequest {
    @JvmField var audioT = 0.0
    @JvmField var reason = FableSolGrandWaveReason.CAUSAL_ARRIVAL
    @JvmField var score01 = 0.0
    // D193：巨浪分级。音乐分支恒 1.0（144dp 现状不变）；说话转变分支 0.556~1.0。
    @JvmField var amplitude01 = 1.0
}
