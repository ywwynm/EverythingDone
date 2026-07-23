package com.ywwynm.everythingdone.views.recording.fablesol

import com.ywwynm.everythingdone.views.recording.fablesol.FableSolSpec.DEEP_LAYER_START
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolSpec.FLOW_DIR
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 特征帧/事件 → 动画驱动（对应 mapping.py 的 FeatureMapper，2026-07-11 A2/A4/A6 状态）：
 * 双 register 记忆、乐队分层、波群注入、远浪/段涌、境状态机、A6 表达修饰。
 *
 * 乐队分层（D16）：装饰层(0~1)拥有音头点击，旋律层(2~3)拥有中频轮廓（F0 到位前的骨架），
 * 织体层(4~6)拥有慢频段包络与波群，深两层(7~8)"无动于衷"——只随数十秒能量积分与段落慢变，
 * 为上层的"倾听"提供参照（Chion anempathetic）。
 *
 * 双 register（D17）：涌浪能量库以半衰期积累短语级能量，是"结束沉降"尾声的本体；chop/瞬态走
 * 注入与光学。响度经**波群**进入织体层：一组 2~4 个波包乘载一次能量，浪只在出生处被包络塑形，
 * 随后按物理传播（D12 延伸）。
 *
 * 反克隆（D18）：任何单一事件的注入 ≤3 层、逐层宽度/位置/时序独立，废除九层齐发的固定级联。
 *
 * 热路径纪律：applyFrame / applySilence 每帧调用——Python 的 dict 状态在此摊平为标量字段、
 * 全部中间向量使用预分配 scratch 数组，帧路径零分配。
 */
class FableSolFeatureMapper(private val p: FableSolParams) {

    private val rng = FableSolRng(7)
    private var lastIncomingT = -10.0

    // 时间层级拆分：能量慢、音色中速、瞬态由分析侧快速包络直接进入材质通道。
    //（对应 Python _slow dict，摊平为标量字段。）
    private var slowLoud = 0.0
    private var slowLow = 0.0
    private var slowMid = 0.0
    private var slowHigh = 0.0

    // A6 表达状态：渐强证据 / 冲击性 / 张力（相位试验）
    private var loom01 = 0.0
    private var impulse01 = 0.0
    private var tension01 = 0.0

    //（对应 Python _timbre dict，摊平为标量字段。）
    private var tCent = 0.5
    private var tTilt = 0.5
    private var tFlat = 0.2
    private var tPerc = 0.0
    private var tPunch = 0.0
    private var tWidth = 0.0
    private var tPan = 0.5
    private var tRelLow = 1.0 / 3
    private var tRelMid = 1.0 / 3
    private var tRelHigh = 1.0 / 3

    private var levelEnergy = 0.0    // 快 register（chop 的能量入口，秒级即忘）
    private var swellEnergy = 0.0    // 涌浪 register（短语记忆，半衰期释放）
    private var deepEnergy = 0.0     // 深层长积分（整段录音的能量史）
    private var deepFlow = 0.0
    private var smT = Double.NaN     // NaN 表示 None

    // 波群/装饰层/旋律层调度状态
    private var groupEndT = -10.0
    private var stGroup = 0.11
    private var groupTexturePtr = 0
    private var ornamentPtr = 0
    private var lastOrnamentT = -10.0
    private var melodyPtr = 0
    private var pitchSm = 0.5        // 旋律层音高高度（相对说话人基线）
    private var breathSm = 0.0       // 4Hz 音节调制 → 呼吸容积

    // 七境由持续等级 + LIFT/CLIMAX 阶段组成；所有对象和输出均在热路径复用。
    private val perceptualFrame = FableSolPerceptualFrame()
    private val gatePerceptualFrame = FableSolPerceptualFrame()
    private val stateEvidence = FableSolStateEvidence()
    private val gateStateEvidence = FableSolStateEvidence()
    private val stateMachine = FableSolSevenStateMachine()
    private val gateStateMachine = FableSolSevenStateMachine()
    private val stateDecision = FableSolStateDecision()
    private val gateStateDecision = FableSolStateDecision()
    private val continuousState = FableSolContinuousStateChannels()
    private val visualChannels = FableSolContinuousVisualChannels()
    private val grandWaveGate = FableSolGrandWaveEventGate()
    private val grandWaveRequest = FableSolGrandWaveRequest()
    private var deepLevelDp = 0.0
    private var levelT = Double.NaN

    // 旧短语悬停仅继续服务 shape register；水位只由 continuousState 写入。
    private var silenceRunS = 0.0
    private var suspended = false
    private var pitchRelLast = 0.5

    // 预分配 scratch（热路径零分配）
    private val bandsSlow = DoubleArray(3)
    private val rel = DoubleArray(3)
    private val contribution = DoubleArray(3)
    private val scratchBands = DoubleArray(3)   // onset/test 事件的频段向量

    // ---- 双 register 目标值 ----
    private fun swell01(): Double = min(max(swellEnergy, 0.0).pow(0.82) * 1.04, 1.0)

    private fun deep01(): Double = min(max(deepEnergy, 0.0).pow(0.82) * 1.04, 1.0)

    /** 深层只保留长积分 shape；基础水位由七境连续通道统一写入。 */
    private fun applyDeepTargets(sim: FableSolSimulation, waveScale: Double) {
        val d = deep01()
        // D203：深两层不吃 1.25 超驱（近中层逐帧上限仍 1.25×heroMax）——
        // 它们基准最高，超驱叠加满驱水位曾把浪尖顶过 TimelyClockView 中心。
        val stateGain = waveScale.coerceIn(0.0, 1.0)
        for (li in DEEP_LAYER_START until sim.layers.size) {
            val ls = sim.layers[li]
            val overall = p.lget("hero_max_dp", li) * p.get("hero_gain") *
                d.pow(0.8) * stateGain
            ls.heroTargetDp = overall
            ls.heroBandTargetDp[0] = overall * 0.55
            ls.heroBandTargetDp[1] = overall * 0.33
            ls.heroBandTargetDp[2] = overall * 0.12
            ls.roughnessTarget01 = 0.12 * d
        }
    }

    private fun fillPerceptualInput(fr: FableSolFeatureFrame) {
        fillRawPerceptualFrame(perceptualFrame, fr)
        fillRawPerceptualFrame(gatePerceptualFrame, fr)
        // 展示轨替换连续响度、七境证据、谱身份与可见动能（D194）；巨浪 gate 始终保留 raw。
        perceptualFrame.waterDrive01 = displayOrRaw(fr.displayWaterDrive01, fr.waterDrive01)
        perceptualFrame.lowShare01 = displayOrRaw(fr.displayRelLow, fr.relLow)
        perceptualFrame.centroid01 = displayOrRaw(fr.displayCentroid01, fr.centroid01)
        perceptualFrame.gradeDrive01 = displayOrRaw(fr.displayGradeDrive01, fr.gradeDrive01)
        perceptualFrame.kineticDrive01 = displayOrRaw(fr.displayKinetic01, fr.kineticDrive01)
        // 句间悬停（D197 修订）：display 侧短静默保持水位/档位；gate 帧保持 raw。
        if (fr.displayIsSilent01 >= 0.0) {
            perceptualFrame.silent = fr.displayIsSilent01 >= 0.5
        }

        stateEvidence.gradeDrive01 = displayOrRaw(fr.displayGradeDrive01, fr.gradeDrive01)
        stateEvidence.liftScore01 = displayOrRaw(fr.displayLiftScore01, fr.liftScore01)
        stateEvidence.climaxScore01 = displayOrRaw(fr.displayClimaxScore01, fr.climaxScore01)
        stateEvidence.gradeAbsolute01 = fr.gradeAbsolute01
        stateEvidence.gradeContext01 = fr.gradeContext01
        stateEvidence.vocalSoloPenalty01 = fr.vocalSoloPenalty01

        gateStateEvidence.gradeDrive01 = fr.gradeDrive01
        gateStateEvidence.liftScore01 = fr.liftScore01
        gateStateEvidence.climaxScore01 = fr.climaxScore01
        gateStateEvidence.gradeAbsolute01 = fr.gradeAbsolute01
        gateStateEvidence.gradeContext01 = fr.gradeContext01
        gateStateEvidence.vocalSoloPenalty01 = fr.vocalSoloPenalty01
    }

    private fun fillRawPerceptualFrame(
        target: FableSolPerceptualFrame,
        fr: FableSolFeatureFrame
    ) {
        target.t = fr.t
        target.silent = fr.isSilent
        target.waterDrive01 = fr.waterDrive01
        target.intensityDrive01 = fr.intensityDrive01
        target.kineticDrive01 = fr.kineticDrive01
        target.percussiveMotion01 = fr.percussiveMotion01
        target.vocalMotion01 = fr.vocalMotion01
        target.harmonicMotion01 = fr.harmonicMotion01
        target.grooveMotion01 = fr.grooveMotion01
        target.musicArousal01 = fr.musicArousal01
        target.energy01 = fr.energy01
        target.energyRising01 = fr.energyRising01
        target.buildUp01 = fr.buildUp01
        target.positiveNovelty01 = fr.novelty01
        target.punch01 = fr.punch01
        target.punchLu01 = fr.punchLu01
        target.lowShare01 = fr.relLow
        target.domainGradeTrim01 = 0.0
        target.gradeDrive01 = fr.gradeDrive01
        target.motionContextBoost01 = fr.motionContextBoost01
        target.centroid01 = fr.centroid01
        target.loudSDb = fr.loudSDb
        target.loudP10Db = fr.loudP10Db
        target.loudP95Db = fr.loudP95Db
        target.gradeContext01 = fr.gradeContext01
        // 说话域（plan-20260723）：gate 的说话资格输入，恒为 raw。
        target.voiceDominance01 = fr.voiceDominance01
        target.music01 = fr.music01
        target.speechEffort01 = fr.speechEffort01
        target.fluct4hz01 = fr.fluct4hz01
        target.sylRateHz = fr.sylRateHz
        target.captureDomain = fr.inputLoudnessTrimDb > 0.0
    }

    private fun fillSilenceInput(t: Double) {
        fillSilenceFrame(perceptualFrame, t)
        fillSilenceFrame(gatePerceptualFrame, t)
        stateEvidence.gradeDrive01 = 0.0
        stateEvidence.liftScore01 = 0.0
        stateEvidence.climaxScore01 = 0.0
        stateEvidence.gradeAbsolute01 = 0.0
        stateEvidence.gradeContext01 = 0.0
        stateEvidence.vocalSoloPenalty01 = 0.0
        gateStateEvidence.gradeDrive01 = 0.0
        gateStateEvidence.liftScore01 = 0.0
        gateStateEvidence.climaxScore01 = 0.0
        gateStateEvidence.gradeAbsolute01 = 0.0
        gateStateEvidence.gradeContext01 = 0.0
        gateStateEvidence.vocalSoloPenalty01 = 0.0
    }

    private fun fillSilenceFrame(target: FableSolPerceptualFrame, t: Double) {
        target.t = t
        target.silent = true
        target.waterDrive01 = 0.0
        target.intensityDrive01 = 0.0
        target.kineticDrive01 = 0.0
        target.percussiveMotion01 = 0.0
        target.vocalMotion01 = 0.0
        target.harmonicMotion01 = 0.0
        target.grooveMotion01 = 0.0
        target.musicArousal01 = 0.0
        target.energy01 = 0.0
        target.energyRising01 = 0.0
        target.buildUp01 = 0.0
        target.positiveNovelty01 = 0.0
        target.punch01 = 0.0
        target.punchLu01 = 0.0
        target.lowShare01 = 0.0
        target.domainGradeTrim01 = 0.0
        target.gradeDrive01 = 0.0
        target.motionContextBoost01 = 0.0
        target.centroid01 = 0.5
        target.loudSDb = -120.0
        target.loudP10Db = 0.0
        target.loudP95Db = 0.0
        target.gradeContext01 = 0.0
    }

    private fun displayOrRaw(display: Double, raw: Double): Double =
        if (display >= 0.0) display else raw

    /** 七境、连续执行通道、巨浪鉴权和基础水位只有这一处生产写入口。 */
    private fun advanceState(sim: FableSolSimulation): FableSolContinuousVisualChannels {
        val expressionGain = p.get("expression_gain")
        val transitionSpeed = p.get("transition_speed")
        stateMachine.step(
            perceptualFrame,
            stateEvidence,
            stateSensitivity = p.get("state_sensitivity"),
            transitionSpeed = transitionSpeed,
            output = stateDecision
        )
        gateStateMachine.step(
            gatePerceptualFrame,
            gateStateEvidence,
            stateSensitivity = p.get("state_sensitivity"),
            transitionSpeed = transitionSpeed,
            output = gateStateDecision
        )
        continuousState.step(
            t = perceptualFrame.t,
            state = stateDecision.state,
            silent = perceptualFrame.silent,
            waterDrive01 = perceptualFrame.waterDrive01,
            kineticDrive01 = perceptualFrame.kineticDrive01,
            musicArousal01 = perceptualFrame.musicArousal01,
            punchLu01 = perceptualFrame.punchLu01,
            punch01 = perceptualFrame.punch01,
            centroid01 = perceptualFrame.centroid01,
            expressionGain = expressionGain,
            transitionSpeed = transitionSpeed,
            output = visualChannels
        )

        sim.flow01 = visualChannels.flow01
        sim.layerSpread = visualChannels.spread
        sim.visualState = stateDecision.state.name
        sim.visualStateLabel = stateDecision.state.label
        sim.visualWaterDrive01 = visualChannels.waterDrive01
        sim.visualLevelTargetDp = visualChannels.levelGoalDp
        sim.visualLevelDp = visualChannels.levelDp
        sim.visualWaveScale = visualChannels.waveScale
        sim.visualTargetDps = visualChannels.targetDps
        sim.sparkle01 = visualChannels.rim01
        sim.glintCapacity01 = visualChannels.cap01
        sim.calm01 = when (stateDecision.state) {
            FableSolVisualState.IDLE, FableSolVisualState.SILENCE -> 1.0
            FableSolVisualState.CALM -> 0.55
            else -> 0.0
        }
        sim.resonance01 = when (stateDecision.state) {
            FableSolVisualState.PEAK, FableSolVisualState.CLIMAX -> 1.0
            FableSolVisualState.GROOVE -> 0.55
            else -> 0.0
        }

        if (grandWaveGate.step(
                gatePerceptualFrame,
                gateStateEvidence.gradeDrive01,
                gateStateDecision.state,
                grandWaveRequest
            )
        ) {
            val accepted = sim.triggerGrandWave(
                expressionGain, amplitude01 = grandWaveRequest.amplitude01)
            grandWaveGate.resolve(grandWaveRequest, accepted)
        }
        applyLevelTargets(sim, visualChannels, perceptualFrame.t, transitionSpeed)
        return visualChannels
    }

    private fun applyLevelTargets(
        sim: FableSolSimulation,
        channels: FableSolContinuousVisualChannels,
        frameT: Double,
        transitionSpeed: Double
    ) {
        val dt = if (levelT.isNaN() || frameT <= levelT) {
            1.0 / 60.0
        } else {
            (frameT - levelT).coerceIn(0.0, 0.10)
        }
        levelT = frameT
        val deepTau = 2.25 * 2.0.pow(-0.65 * transitionSpeed.coerceIn(-1.0, 1.0))
        deepLevelDp += (channels.levelDp - deepLevelDp) *
            (1.0 - exp(-dt / max(deepTau, 0.10)))
        for (ls in sim.layers) {
            ls.swellTargetDp = max(
                if (ls.i >= DEEP_LAYER_START) deepLevelDp else channels.levelDp,
                0.0
            )
        }
    }

    internal fun currentVisualState(): FableSolVisualState = stateDecision.state

    internal fun currentGateState(): FableSolVisualState = gateStateDecision.state

    internal fun currentWaveScale(): Double = visualChannels.waveScale

    fun applySilence(sim: FableSolSimulation) {
        smT = Double.NaN
        slowLoud *= 0.94; slowLow *= 0.94; slowMid *= 0.94; slowHigh *= 0.94
        tFlat *= 0.94; tPerc *= 0.94; tPunch *= 0.94; tWidth *= 0.94
        tCent += (0.5 - tCent) * 0.05
        tPan += (0.5 - tPan) * 0.05
        val dt = 1.0 / 60.0
        levelEnergy *= 0.96
        // 尾声：chop 立即死（上面的 0.96），涌浪按半衰期沉降；深层长积分只在有声期间生效——
        // 静默时以 tau~2s 与全场一同归静（用户 2026-07-11 否决深层余韵：停止后第 8/9 层
        // 拖几十秒不落是缺陷，不是韵味）。
        swellEnergy *= 0.5.pow(dt / max(p.get("swell_halflife_s"), 0.5))
        val kDeep = 1.0 - exp(-dt / 2.0)
        deepEnergy *= 1.0 - kDeep
        deepFlow *= 1.0 - kDeep
        sim.flow01Deep = deepFlow
        sim.setBeat(0.0, 0.0, 0.0)
        sim.setColorDrive(0.5, 0.0)
        sim.setMaterialDrive(0.0, 0.5)
        sim.setSpatialDrive(0.0, 0.5)
        tension01 *= 0.94   // 静息回失谐（A6 张力试验）
        sim.setTension01(tension01)
        fillSilenceInput(sim.t)
        val channels = advanceState(sim)
        for (li in 0 until DEEP_LAYER_START) {
            val ls = sim.layers[li]
            // 基础水位已由 advanceState 写入；这里只释放旧 shape 能量。
            ls.heroTargetDp = 0.0
            ls.heroBandTargetDp[0] = 0.0; ls.heroBandTargetDp[1] = 0.0; ls.heroBandTargetDp[2] = 0.0
            ls.roughnessTarget01 = 0.0
        }
        applyDeepTargets(sim, channels.waveScale)
    }

    fun applyFrame(sim: FableSolSimulation, fr: FableSolFeatureFrame) {
        val silent = fr.isSilent
        val t = fr.t
        val dt = if (smT.isNaN()) 1.0 / 60.0 else (t - smT).coerceIn(0.0, 0.1)
        smT = t
        val displayWater = displayOrRaw(fr.displayWaterDrive01, fr.waterDrive01)
        val displayRelLow = displayOrRaw(fr.displayRelLow, fr.relLow)
        val displayRelMid = displayOrRaw(fr.displayRelMid, fr.relMid)
        val displayRelHigh = displayOrRaw(fr.displayRelHigh, fr.relHigh)
        val displayCentroid = displayOrRaw(fr.displayCentroid01, fr.centroid01)
        // 浪形能量保持灵敏；基础水位另有更慢、更稳定的独立状态。
        val ke = if (dt > 0) 1.0 - exp(-dt / 0.28) else 0.0
        val kt = if (dt > 0) 1.0 - exp(-dt / 0.24) else 0.0
        val kr = if (dt > 0) 1.0 - exp(-dt / 0.85) else 0.0
        // loudness01 is kept only for legacy diagnostics.  The animation contract is
        // the fixed-domain perceptual water drive, so capture/master parity cannot leak
        // back through the old shape register.
        slowLoud += ((if (silent) 0.0 else displayWater) - slowLoud) * ke
        slowLow += ((if (silent) 0.0 else fr.bandLow) - slowLow) * ke
        slowMid += ((if (silent) 0.0 else fr.bandMid) - slowMid) * ke
        slowHigh += ((if (silent) 0.0 else fr.bandHigh) - slowHigh) * ke
        tCent += ((if (silent) 0.5 else displayCentroid) - tCent) * kt
        tTilt += (fr.spectralTilt01 - tTilt) * kt
        tFlat += ((if (silent) 0.0 else fr.flatness01) - tFlat) * kt
        tPerc += ((if (silent) 0.0 else fr.percussive01) - tPerc) * kt
        tPunch += ((if (silent) 0.0 else fr.punch01) - tPunch) * kt
        tWidth += ((if (silent) 0.0 else fr.stereoWidth01) - tWidth) * kt
        tPan += (fr.pan01 - tPan) * kt
        tRelLow += (displayRelLow - tRelLow) * kr
        tRelMid += (displayRelMid - tRelMid) * kr
        tRelHigh += (displayRelHigh - tRelHigh) * kr
        sim.setBeat(fr.tempoBpm, fr.beatPhase01, if (silent) 0.0 else fr.beatConf01)
        bandsSlow[0] = slowLow; bandsSlow[1] = slowMid; bandsSlow[2] = slowHigh
        val levelIn = if (silent) 0.0 else displayWater
        val levelTau = if (levelIn > levelEnergy) p.get("swell_presmooth_s")
        else p.get("swell_presmooth_release_s")
        if (dt > 0.0) {
            levelEnergy += (levelIn - levelEnergy) * (1.0 - exp(-dt / max(levelTau, 0.05)))
            // 双 register（D17）：涌浪快升慢放（半衰期）——"记住刚才那句话"；
            // 深层长积分承载整段录音的能量史与流速史（"长曝光"）。
            if (levelEnergy > swellEnergy) {
                swellEnergy += (levelEnergy - swellEnergy) * (1.0 - exp(-dt / 0.9))
            } else if (!suspended) {
                // 句中悬停（<0.6s 且语调未落）：涌浪衰减暂停——屏住的呼吸
                swellEnergy *= 0.5.pow(dt / max(p.get("swell_halflife_s"), 0.5))
            }
            val kDeep = 1.0 - exp(-dt / max(p.get("deep_integral_s"), 1.0))
            deepEnergy += (levelEnergy - deepEnergy) * kDeep
            deepFlow += (displayOrRaw(fr.displayKinetic01, fr.kineticDrive01) - deepFlow) * kDeep
        }
        sim.flow01Deep = deepFlow
        val relSum = max(tRelLow + tRelMid + tRelHigh, 1e-6)
        rel[0] = tRelLow / relSum; rel[1] = tRelMid / relSum; rel[2] = tRelHigh / relSum
        val eMix = 0.45 * slowLoud + 0.55 * (bandsSlow[0] + bandsSlow[1] + bandsSlow[2]) / 3.0
        val colorEnergy = min(eMix.pow(0.7) * 1.15, 1.0)
        sim.setColorDrive(tCent, colorEnergy)
        var rough = (0.62 * tFlat + 0.23 * tPerc + 0.15 * (1.0 - tTilt)).coerceIn(0.0, 1.0)
        // A6：HNR 清澈度——谐波干净的声音让水更清。清澈是减法，不新增元素。
        val clar = if (silent) 0.0 else fr.hnr01
        rough *= 1.0 - 0.42 * clar
        sim.setMaterialDrive(rough, tTilt)
        sim.setSpatialDrive(tWidth, tPan)
        // 呼吸容积（A3）：4Hz 音节调制经 1.2s 平滑后调制环境波振幅——
        // 水面随说话的音节脉搏"呼吸"（心理声学波动强度峰值恰在 4Hz）。
        if (dt > 0.0) {
            val kb = 1.0 - exp(-dt / 1.2)
            val f4 = if (silent) 0.0 else fr.fluct4hz01
            breathSm += (f4 - breathSm) * kb
            val kp = 1.0 - exp(-dt / 0.35)
            pitchSm += (((fr.pitchRel01 - 0.5) * fr.voiced01 + 0.5) - pitchSm) * kp
        }
        val musicGate = fr.music01
        // A6：事件记忆（注入时取用）与张力推进。张力=相位试验（D18/D20 标签，
        // 目测不喜欢即砍）：持续渐强证据 × 门控（浊音或音乐）触发，升 τ1s 降 τ2.2s。
        loom01 = if (silent) 0.0 else fr.loom01
        impulse01 = if (silent) 0.0 else fr.impulse01
        if (dt > 0.0) {
            val gate = max(musicGate, fr.voiced01)
            // 证据下限 0.35：普通语音的短语级起伏（loom 中位≈0.3）不该充能张力，
            // 只有持续、显著的渐强（真正的 crescendo）才拉动相位相干。
            val tensT = max(loom01 - 0.35, 0.0) / 0.65 * gate
            val tauT = if (tensT > tension01) 1.0 else 2.2
            tension01 += (tensT - tension01) * (1.0 - exp(-dt / tauT))
        }
        sim.setTension01(tension01)
        silenceTrack(fr, silent, dt)
        fillPerceptualInput(fr)
        val channels = advanceState(sim)
        stGroup = channels.waveScale
        sim.breath01 = (breathSm * 2.2).coerceIn(0.0, 1.0)
        for (li in 0 until DEEP_LAYER_START) {
            val ls = sim.layers[li]
            val role = bandWeights(ls.depth01)
            val drive = dot3(role, bandsSlow)
            val identity = dot3(role, rel)
            val identityGain = (0.78 + 1.25 * (identity - 1.0 / 3)).coerceIn(0.58, 1.22)
            var shapeMix = (0.45 * slowLoud + 0.55 * drive) * identityGain
            shapeMix = min(shapeMix.pow(0.7) * 1.15, 1.0)
            var overall = p.lget("hero_max_dp", ls.i) * p.get("hero_gain") * shapeMix.pow(0.8)
            overall *= channels.waveScale
            // 旋律层（A3）：主浪高度随音高相对说话人基线抬落（音高↔高度强映射）。
            if (ls.i == MELODY_LAYERS[0] || ls.i == MELODY_LAYERS[1]) {
                overall *= 0.74 + 0.62 * pitchSm
            }
            ls.heroTargetDp = overall
            var cSum = 0.0
            for (j in 0 until 3) {
                contribution[j] = role[j] * max(bandsSlow[j], 0.03) * (0.55 + 1.35 * rel[j])
                cSum += contribution[j]
            }
            cSum = max(cSum, 1e-6)
            for (j in 0 until 3) ls.heroBandTargetDp[j] = overall * contribution[j] / cSum
            ls.roughnessTarget01 = rough * (0.82 + 0.18 * role[2])
        }
        applyDeepTargets(sim, channels.waveScale)
    }

    /**
     * 音头 → 装饰层点击 + 波群候选 + 稀有远浪；深层无动于衷。
     *
     * 已经形成的浪不得被瞬态重塑（D12）：全部快速能量走 DynamicWave 注入。
     */
    fun applyOnset(sim: FableSolSimulation, ev: FableSolEvent.Onset) {
        val s = ev.strength01
        scratchBands[0] = ev.low; scratchBands[1] = ev.mid; scratchBands[2] = ev.high
        // 通道互斥（D18 单事件 ≤3 层）：远浪触发时它就是本次事件的全部几何表达
        // （已含旋律主浪+装饰前奏+织体余韵三层），跳过点击与波群；
        // 否则点击（1 层，即时）+ 波群（首包即时，后续包按组间隔在未来出生）。
        // 远浪触发固化：冷却 3.2s、强度门 0.75、概率 0.50（原 incoming_* 参数）。
        val cd = 3.2 * (1.5 - 0.9 * sim.flow01.coerceIn(0.0, 1.0))
        if (s >= 0.75 && sim.t - lastIncomingT >= cd
            && rng.nextDouble() < 0.50) {
            lastIncomingT = sim.t
            val amp = s * 36.0 * visualChannels.waveScale.coerceIn(0.2, 2.0)
            // 重音装弹、拍点发射：锁拍时注入时刻吸附到预测的下一拍
            var delay0 = 0.0
            if (sim.beat01 > 0.45) {
                delay0 = sim.timeToNextBeat()
                val per = 60.0 / max(sim.currentBeatBpm(), 1.0)
                if (delay0 < 0.08) delay0 += per
            }
            // 对应 Python ev.get("impulse01", self._impulse01)：NaN=事件未附带 → 回退帧记忆
            val evImpulse = if (ev.impulse01.isNaN()) impulse01 else ev.impulse01
            inject(sim, amp, ev.centroid01, scratchBands, incoming = true, cascade = true,
                punch = s, delay0 = delay0, pan01 = ev.pan01, impulse01 = evImpulse)
            return
        }
        // 波群成组的 onset 由组首包充当即时响应（跳过点击）；组间歇里的音节
        // 才落装饰层点击——两通道互补，单事件层数恒 ≤3（D18）。
        if (!scheduleWaveGroup(sim, ev, scratchBands)) injectOrnamentDab(sim, ev)
    }

    /** 装饰层点击（Laban dab）：单层、限速、宽度红线内的小水花。 */
    private fun injectOrnamentDab(sim: FableSolSimulation, ev: FableSolEvent.Onset) {
        val s = ev.strength01
        if (s < ORNAMENT_MIN_STRENGTH) return
        if (sim.t - lastOrnamentT < ORNAMENT_MIN_GAP_S) return
        lastOrnamentT = sim.t
        val li = ORNAMENT_LAYERS[ornamentPtr % ORNAMENT_LAYERS.size]
        ornamentPtr += 1
        val centroid = ev.centroid01.coerceIn(0.0, 1.0)
        val low = ev.low
        val width = max(ORNAMENT_MIN_WIDTH_DP, 136.0 - 44.0 * centroid) * rng.uniform(0.95, 1.18)
        var amp = (2.2 + 6.0 * (s - ORNAMENT_MIN_STRENGTH) / (1.0 - ORNAMENT_MIN_STRENGTH)) *
            (0.62 + 0.38 * low)
        amp *= visualChannels.waveScale.coerceIn(0.2, 2.0)
        amp = min(amp, AMP_WIDTH_SLOPE * width)   // 高宽联动：不尖窄
        val span = sim.geometrySpan()
        val side = if (FLOW_DIR < 0) 1.0 else -1.0
        val u = side * (span / 2.0 + width * 0.22) + rng.gaussian(0.0, 0.30 * width)
        val travel = FLOW_DIR * (0.55 + 0.25 * sim.flow01.coerceIn(0.0, 1.0))
        val peak = 0.85 + 0.55 * centroid   // 锐度上限收紧（红线）
        sim.injectLayer(li, 0.0, width, amp, travel, 0.0, uDp = u, peak = peak)
    }

    /**
     * 波群（D17/D18）：一组 2~4 个波包乘载一次能量，逐包落在轮转的织体层。
     *
     * 包络只作用于出生幅度，浪成形后按物理传播；组间保持间歇（set/lull）。
     * 取代旧的九层齐发 rhythm wave——那是"复制粘贴感"的来源。
     */
    private fun scheduleWaveGroup(sim: FableSolSimulation, ev: FableSolEvent.Onset,
                                  bandVec: DoubleArray): Boolean {
        val strength = ev.strength01
        // 节奏波包门槛固化 0.25（原 rhythm_wave_min_strength）。
        val threshold = if (stateDecision.state == FableSolVisualState.LIFT) 0.50 else 0.25
        if (strength < threshold) return false
        if (swellEnergy < GROUP_MIN_SWELL) return false
        val span = sim.geometrySpan()
        val probe = sim.layers[TEXTURE_LAYERS[1]]
        val transport = abs(probe.flowDps) + 0.55 * p.lget("wave_speed_dps", TEXTURE_LAYERS[1])
        val interval = (span / max(transport * 1.55, 1.0)).coerceIn(0.72, 2.8)
        if (sim.t < groupEndT + GROUP_LULL_FACTOR * interval) return false
        val sw = swell01()
        // Python round 为银行家舍入、Math.round 为半数进位；输入是连续量，恰逢 .5 的
        // 差异测度为零，行为等价。
        val n = (2 + Math.round(2.0 * sw * strength + 0.9 * loom01).toInt())
            .coerceIn(2, GROUP_ENVELOPE.size)
        val flow = sim.flow01.coerceIn(0.0, 1.0)
        val centroid = ev.centroid01
        // 节奏波包强度固化 0.85（原 rhythm_wave_gain）。
        val ampBase = 0.85 * (3.0 + 6.0 * strength) *
            (0.72 + 0.28 * flow) * (0.70 + 0.50 * sw) *
            (0.55 + 0.45 * stGroup) *
            (1.0 + 0.35 * loom01)   // A6：渐强下波群生长更旺
        val side = if (FLOW_DIR < 0) 1.0 else -1.0
        val peak = min((0.85 + 0.55 * centroid) * (1.0 + 0.10 * loom01), 1.60)
        val bandMax = max(max(bandVec[0], max(bandVec[1], bandVec[2])), 1e-6)
        for (k in 0 until n) {
            val li = TEXTURE_LAYERS[groupTexturePtr % TEXTURE_LAYERS.size]
            groupTexturePtr += 1
            val ls = sim.layers[li]
            val role = bandWeights(ls.depth01)
            val response = 0.62 + 0.38 * dot3(role, bandVec) / bandMax
            val width = (108.0 + (1.0 - centroid) * 84.0) * rng.uniform(0.85, 1.25)
            var amp = ampBase * GROUP_ENVELOPE[k] * response * rng.uniform(0.85, 1.15)
            amp = min(amp, AMP_WIDTH_SLOPE * width)
            val jitter = rng.gaussian(0.0, 0.32 * width)
            val u = side * (span / 2.0 + width * 0.25 + 8.0 + ls.depth01 * 12.0) + jitter
            val delay = k * interval * rng.uniform(0.90, 1.15)
            sim.injectLayer(li, 0.0, width, amp, FLOW_DIR * 0.86, delay, uDp = u, peak = peak)
        }
        groupEndT = sim.t + (n - 1) * interval
        return true
    }

    /**
     * 三种静默：句中悬停（短停顿+非终结语调，冻结涌浪衰减）/句末沉降/
     * 结束尾声（applySilence 路径）。悬停最长 [SUSPEND_MAX_S]。
     */
    private fun silenceTrack(fr: FableSolFeatureFrame, silent: Boolean, dt: Double) {
        if (silent) {
            silenceRunS += dt
            suspended = silenceRunS < SUSPEND_MAX_S && pitchRelLast >= SUSPEND_PITCH_FLOOR
        } else {
            silenceRunS = 0.0
            suspended = false
            if (fr.voiced01 > 0.3) pitchRelLast = fr.pitchRel01
        }
    }

    /**
     * 重音峰击（Laban Press→Punch）：旋律层单层宽涌，音高越高峰越高。
     *
     * 单事件单层（D18 内），宽度大、锐度软（不尖窄红线）。
     */
    fun applyProminence(sim: FableSolSimulation, ev: FableSolEvent.Prominence) {
        val s = ev.strength01
        val pr = ev.pitchRel01.coerceIn(0.0, 1.0)
        val li = MELODY_LAYERS[melodyPtr % MELODY_LAYERS.size]
        melodyPtr += 1
        val span = sim.geometrySpan()
        val side = if (FLOW_DIR < 0) 1.0 else -1.0
        var width = (176.0 + 60.0 * (1.0 - pr)) * rng.uniform(0.92, 1.15)
        var amp = (5.0 + 9.0 * s) * (0.72 + 0.56 * pr)
        amp *= visualChannels.waveScale.coerceIn(0.2, 2.0)
        width *= 1.0 + 0.16 * loom01   // A6：渐强下重音浪随之生长（宽随幅长）
        amp *= 1.0 + 0.30 * loom01
        amp = min(amp, AMP_WIDTH_SLOPE * width)
        val u = side * (span / 2.0 + width * 0.30) + rng.gaussian(0.0, 0.26 * width)
        val travel = FLOW_DIR * (0.80 + 0.12 * sim.flow01.coerceIn(0.0, 1.0))
        val peak = 0.88 + 0.34 * pr
        sim.injectLayer(li, 0.0, width, amp, travel, 0.0, uDp = u, peak = peak)
    }

    /** 测试注入（对应 Python 面板按钮路径）：走与真实 onset 完全相同的注入管线。 */
    fun injectTest(sim: FableSolSimulation, incoming: Boolean) {
        val s = rng.uniform(0.65, 0.95)
        val amp = s.pow(1.5) * 36.0
        scratchBands[0] = rng.uniform(0.55, 0.95)
        scratchBands[1] = rng.uniform(0.35, 0.75)
        scratchBands[2] = rng.uniform(0.25, 0.65)
        inject(sim, max(amp, 6.0), rng.uniform(0.15, 0.55), scratchBands,
            incoming = incoming, cascade = true, punch = s, pan01 = rng.uniform(0.2, 0.8))
    }

    /**
     * 远浪/测试注入。反克隆（D18）：旋律层主浪 + 装饰层前奏 + 织体层余韵，
     * 共 3 层，逐层宽度/位置/时序独立——不再九层齐发。
     *
     * cascade/punch 为与 Python 同步保留的旧签名兼容参数，当前不参与计算；
     * 参数 impulse01 遮蔽同名字段（与 Python 的 impulse01 形参 / self._impulse01 对应）。
     */
    @Suppress("UNUSED_PARAMETER")
    private fun inject(sim: FableSolSimulation, ampIn: Double, centroid01: Double,
                       bands: DoubleArray, incoming: Boolean, cascade: Boolean,
                       punch: Double = 0.7, delay0: Double = 0.0, pan01: Double = 0.5,
                       impulse01: Double = 0.0) {
        if (incoming) {
            // 稀有远浪同时向连续二维水面注入有限相干波包；原前景即时注入保留。
            sim.injectDepthPacket(punch.coerceIn(0.0, 1.0), pan01, zDominant = true)
        }
        var amp = ampIn
        // 浪宽范围固化 96~216dp（原 inject_width_min/max_dp）。
        val wMin = 96.0
        val wMax = 216.0
        var width = wMax + (wMin - wMax) * centroid01  // 低沉→宽，清脆→窄
        // A6：looming→新浪生长与前倾；冲击性→更宽的底座 + 更立的峰。
        // 守红线：宽度与幅度同向增长（高浪必须宽），峰锐度有硬上限。
        width *= 1.0 + 0.18 * loom01 + 0.22 * impulse01
        amp *= 1.0 + 0.32 * loom01
        var peak = (0.85 + 0.75 * centroid01.coerceIn(0.0, 1.0)) *
            (1.0 + 0.12 * loom01 + 0.16 * impulse01)
        peak = min(peak, 1.70)
        val span = sim.geometrySpan()
        val side = if (FLOW_DIR < 0) 1.0 else -1.0
        var uBase: Double
        val travel: Double
        if (incoming) {
            uBase = side * (span / 2.0 + width / 2.0 + 16.0)
            travel = FLOW_DIR * 0.95
            amp *= 1.15
        } else {
            val frac = rng.uniform(0.30, 0.65)
            uBase = side * (span / 2.0 + width * frac)
            // 顺流偏置上限固化 0.75（原 travel_bias_max）。
            var tf = 0.75 * (0.6 + 0.4 * sim.flow01)
            tf += (0.95 - tf) * frac
            travel = FLOW_DIR * tf
        }
        uBase += (pan01.coerceIn(0.0, 1.0) - 0.5) * span * 0.16

        val melody = MELODY_LAYERS[rng.integers(0, MELODY_LAYERS.size)]
        val ornament = ORNAMENT_LAYERS[rng.integers(0, ORNAMENT_LAYERS.size)]
        val texture = TEXTURE_LAYERS[groupTexturePtr % TEXTURE_LAYERS.size]
        groupTexturePtr += 1
        val bandMax = max(max(bands[0], max(bands[1], bands[2])), 1e-6)
        // 三层反克隆计划 (层, 幅度系数, 额外延迟)：旋律主浪 / 装饰前奏 / 织体余韵。
        injectPlanned(sim, melody, 1.00, 0.0, width, amp, travel, delay0, uBase, peak, side, bands, bandMax)
        injectPlanned(sim, ornament, 0.52, 0.085, width, amp, travel, delay0, uBase, peak, side, bands, bandMax)
        injectPlanned(sim, texture, 0.34, 0.17, width, amp, travel, delay0, uBase, peak, side, bands, bandMax)
    }

    /** 反克隆计划中的单层注入：宽度/抖动/延迟逐层独立（RNG 调用次序与 Python 逐行一致）。 */
    private fun injectPlanned(sim: FableSolSimulation, li: Int, kAmp: Double, extra: Double,
                              width: Double, amp: Double, travel: Double, delay0: Double,
                              uBase: Double, peak: Double, side: Double,
                              bands: DoubleArray, bandMax: Double) {
        val ls = sim.layers[li]
        val role = bandWeights(ls.depth01)
        val affinity = 0.72 + 0.28 * dot3(role, bands) / bandMax
        val wi = width * rng.uniform(0.95, 1.35)
        val ampI = min(amp * kAmp * affinity, AMP_WIDTH_SLOPE * wi)
        val jit = rng.gaussian(0.0, 0.30 * width)
        val deep = side * ls.depth01 * 36.0
        val delay = delay0 + extra + rng.uniform(0.0, 0.05)
        sim.injectLayer(li, 0.0, wi, ampI, travel, delay, uDp = uBase + jit + deep, peak = peak)
    }

    /** 段落边界保留 mood，并只为独立巨浪门控开启短鉴权窗；七境本身不读取 section。 */
    fun applySection(sim: FableSolSimulation, ev: FableSolEvent.Section) {
        stateMachine.notifySection()
        gateStateMachine.notifySection()
        grandWaveGate.notifySection(
            intensity01 = ev.energy01,
            surge = ev.surge,
            now = sim.t,
            sourceT = ev.t
        )
        sim.setMood(ev.energy01, ev.brightness01)
    }

    @Suppress("UNUSED_PARAMETER")
    fun applyDrop(sim: FableSolSimulation, ev: FableSolEvent.Drop) {
        // sim 参数保留在签名中，使所有结构事件入口一致；门控只使用下一 authoritative audio frame。
        stateMachine.notifyDrop(ev.confidence01)
        gateStateMachine.notifyDrop(ev.confidence01)
        grandWaveGate.notifyDrop(ev.confidence01)
    }

    fun applyStructuralEvent(sim: FableSolSimulation, ev: FableSolEvent) {
        when (ev) {
            is FableSolEvent.Section -> applySection(sim, ev)
            is FableSolEvent.Drop -> applyDrop(sim, ev)
            else -> Unit
        }
    }

    /** 返回最近的水体角色权重行（共享只读表行，不得修改；depth01 入参保持旧接口兼容）。 */
    private fun bandWeights(depth01: Double): DoubleArray {
        val i = Math.round(depth01 * (LAYER_ROLE_WEIGHTS.size - 1)).toInt()
            .coerceIn(0, LAYER_ROLE_WEIGHTS.size - 1)
        return LAYER_ROLE_WEIGHTS[i]
    }

    private fun dot3(a: DoubleArray, b: DoubleArray): Double =
        a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    companion object {
        // 乐队分层（D16）：装饰/旋律/织体；深两层从 FableSolSpec.DEEP_LAYER_START 起。
        private val ORNAMENT_LAYERS = intArrayOf(0, 1)
        private val MELODY_LAYERS = intArrayOf(2, 3)
        private val TEXTURE_LAYERS = intArrayOf(4, 5, 6)

        // 逐层频段权重（深两层置零：它们不听逐帧频段，走长积分路径）。
        // 近=重量/低频、旋律层=中频主唱、织体=中高微光（高频光感由近层光学承担后，
        // 织体只需中等高频份额，不再让远景替齿擦音抖动）。
        private val LAYER_ROLE_WEIGHTS = arrayOf(
            doubleArrayOf(0.70, 0.22, 0.08), doubleArrayOf(0.62, 0.28, 0.10),
            doubleArrayOf(0.22, 0.62, 0.16), doubleArrayOf(0.18, 0.62, 0.20),
            doubleArrayOf(0.14, 0.48, 0.38), doubleArrayOf(0.12, 0.42, 0.46), doubleArrayOf(0.10, 0.36, 0.54),
            doubleArrayOf(0.00, 0.00, 0.00), doubleArrayOf(0.00, 0.00, 0.00)
        )

        // 波群调度（D17）：组内包络"次强-最强-回落-收尾"；组间间歇 ≥ 系数×包间隔。
        private val GROUP_ENVELOPE = doubleArrayOf(0.62, 1.00, 0.78, 0.55)
        private const val GROUP_MIN_SWELL = 0.12
        private const val GROUP_LULL_FACTOR = 1.35
        // 装饰层点击（Laban dab）：限速限幅，宽度红线（不尖窄）。
        private const val ORNAMENT_MIN_STRENGTH = 0.30
        private const val ORNAMENT_MIN_GAP_S = 0.18
        private const val ORNAMENT_MIN_WIDTH_DP = 96.0
        // 波高/宽度联动红线：注入幅度 ≤ 系数×宽度（高浪必须宽）。
        private const val AMP_WIDTH_SLOPE = 0.09

        // 三种静默：句中悬停阈值（非终结语调 + 短停顿 → 冻结涌浪衰减）
        private const val SUSPEND_MAX_S = 0.6
        private const val SUSPEND_PITCH_FLOOR = 0.47
    }
}
