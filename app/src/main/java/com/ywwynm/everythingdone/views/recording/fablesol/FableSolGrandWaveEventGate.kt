package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.max

/**
 * 严格因果、可重复的巨浪事件门控，对应 Python `core/grand_wave_gate.py`。
 *
 * 七境描述持续听感，巨浪则是一次性的短语重音，因此由独立门控鉴权。所有统计都只读取
 * trailing 2.5s；长 PEAK/CLIMAX 不设次数配额，但每次都必须重新满足声学到达条件与 14s
 * 可读间隔。调用方必须对每个候选调用 [resolve]，无论物理层最终是否接受。
 */
class FableSolGrandWaveEventGate {

    private val historyT = DoubleArray(HISTORY_CAPACITY)
    private val historyRising = DoubleArray(HISTORY_CAPACITY)
    private val historyPunch = DoubleArray(HISTORY_CAPACITY)
    private var historyHead = 0
    private var historySize = 0
    private val sharedRequest = FableSolGrandWaveRequest()

    private var lastT = Double.NaN
    private var audibleHistoryS = 0.0
    private var sectionIntensity = Double.NaN
    private var sectionWindowEnd = -100.0
    private var localConfirmS = 0.0
    private var denseConfirmS = 0.0
    private var relativeConfirmS = 0.0
    private var sectionPhraseConfirmS = 0.0
    private var repeatConfirmS = 0.0
    private var resurgentRepeatConfirmS = 0.0
    private var dropPending = false
    private var dropConfidence = 0.0
    private var peakBandLast = false
    private var episodeCount = 0
    private var episodeRepeats = 0
    private var localArmed = true
    private var localReleaseS = 0.0
    private var repeatArmed = false
    private var punchReleaseS = 0.0
    private var lastWaveT = -100.0

    fun reset() {
        clearHistory()
        lastT = Double.NaN
        audibleHistoryS = 0.0
        sectionIntensity = Double.NaN
        sectionWindowEnd = -100.0
        clearConfirmation()
        dropPending = false
        dropConfidence = 0.0
        peakBandLast = false
        episodeCount = 0
        episodeRepeats = 0
        localArmed = true
        localReleaseS = 0.0
        repeatArmed = false
        punchReleaseS = 0.0
        lastWaveT = -100.0
    }

    /** 只建立稳定段落基线，不制造边界重音。 */
    fun setSectionContext(intensity01: Double) {
        sectionIntensity = intensity01.coerceIn(0.0, 1.0)
        sectionWindowEnd = -100.0
    }

    fun clearSectionContext() {
        sectionIntensity = Double.NaN
        sectionWindowEnd = -100.0
    }

    /**
     * 实时 Foote 边界通常晚于声学中心约 3.7s，因此 Section 只为下一处当前短语开启短鉴权窗，
     * 绝不因事件本身直接制造巨浪。优先使用最后一帧音频时钟，避免暂停时间拉长窗口。
     */
    fun notifySection(
        intensity01: Double,
        surge: Boolean = true,
        now: Double = Double.NaN,
        sourceT: Double = 0.0
    ) {
        sectionIntensity = intensity01.coerceIn(0.0, 1.0)
        sectionWindowEnd = -100.0
        val arrivalT = when {
            !lastT.isNaN() -> lastT
            !now.isNaN() -> now
            else -> sourceT
        }
        if (surge) sectionWindowEnd = arrivalT + SECTION_WINDOW_S
    }

    fun notifyDrop(confidence01: Double = 1.0) {
        dropPending = true
        dropConfidence = confidence01.coerceIn(0.0, 1.0)
    }

    fun episodeCount(): Int = episodeCount

    fun episodeRepeatCount(): Int = episodeRepeats

    fun lastWaveTime(): Double = lastWaveT

    fun audibleHistorySeconds(): Double = audibleHistoryS

    fun isLocalArmed(): Boolean = localArmed

    fun isRepeatArmed(): Boolean = repeatArmed

    fun isSectionWindowActive(): Boolean = !lastT.isNaN() && lastT <= sectionWindowEnd

    /** 返回 true 时 [output] 是本帧候选；重复轮询同一音频帧严格返回 false。 */
    fun step(
        frame: FableSolPerceptualFrame,
        state: FableSolVisualState,
        output: FableSolGrandWaveRequest = sharedRequest
    ): Boolean = stepInternal(
        frame,
        if (frame.gradeDrive01.isNaN()) frame.intensityDrive01 else frame.gradeDrive01,
        state,
        output
    )

    /** 与 [step] 相同，但显式传入七境 grade，供不回写帧的集成路径使用。 */
    fun step(
        frame: FableSolPerceptualFrame,
        gradeDrive01: Double,
        state: FableSolVisualState,
        output: FableSolGrandWaveRequest = sharedRequest
    ): Boolean = stepInternal(frame, gradeDrive01.coerceIn(0.0, 1.0), state, output)

    private fun stepInternal(
        frame: FableSolPerceptualFrame,
        gradeDrive01: Double,
        state: FableSolVisualState,
        output: FableSolGrandWaveRequest
    ): Boolean {
        val t = frame.t
        val dt: Double
        if (lastT.isNaN()) {
            dt = 1.0 / 60.0
        } else {
            val elapsed = t - lastT
            if (elapsed == 0.0) return false
            if (elapsed < 0.0 || elapsed > 0.50) {
                clearHistory()
                clearConfirmation()
                dt = 1.0 / 60.0
            } else {
                dt = elapsed.coerceIn(1.0 / 240.0, 0.10)
            }
        }
        lastT = t

        if (!frame.silent) audibleHistoryS += dt

        appendHistory(t, frame.energyRising01, frame.punch01)
        pruneHistory(t - 2.5)
        val rising08 = historyMean(CHANNEL_RISING, t - 0.80, t)
        val punchFast = historyMean(CHANNEL_PUNCH, t - 0.60, t)
        val punchBase = historyMean(CHANNEL_PUNCH, t - 2.40, t - 0.60)
        val historyReady = historySize > 0 && t - historyT[historyHead] >= 1.0
        val punchDelta01 = if (historyReady) {
            ((punchFast - punchBase - 0.06) / 0.16).coerceIn(0.0, 1.0)
        } else {
            0.0
        }

        val water = frame.waterDrive01
        val kinetic = frame.kineticDrive01
        val intensity = frame.intensityDrive01
        val punch = frame.punch01
        val musicMotion = max(frame.percussiveMotion01, frame.harmonicMotion01)
        val vocalOnly = frame.vocalMotion01 > musicMotion + 0.16 && musicMotion < 0.58
        val context = frame.motionContextBoost01
        val physicalAttack = max(rising08, punchDelta01)
        val attack = max(context, physicalAttack)
        val arousal = frame.musicArousal01
        val novelty = frame.positiveNovelty01
        val gradeContext = frame.gradeContext01
        val loudRelative = (
            (frame.loudSDb - frame.loudP10Db) /
                max(frame.loudP95Db - frame.loudP10Db, 6.0)
            ).coerceIn(0.0, 1.5)

        val peakBand = state == FableSolVisualState.PEAK || state == FableSolVisualState.CLIMAX
        if (peakBand && !peakBandLast) {
            beginEpisode(t)
        } else if (!peakBand && peakBandLast) {
            clearConfirmation()
        }
        peakBandLast = peakBand

        localReleaseS = advance(localReleaseS, water < 0.76, dt)
        if (localReleaseS >= LOCAL_RELEASE_S) localArmed = true

        if (episodeCount >= 1) {
            punchReleaseS = advance(punchReleaseS, punch < 0.68, dt)
            if (punchReleaseS >= PUNCH_RELEASE_S) repeatArmed = true
        } else {
            punchReleaseS = 0.0
        }

        if (dropPending) {
            dropPending = false
            val dropOk = peakBand && t - lastWaveT >= REPEAT_MIN_GAP_S &&
                water >= 0.72 && kinetic >= 0.65 &&
                intensity >= 0.56 && musicMotion >= 0.54 && !vocalOnly
            if (dropOk) {
                fill(output, t, FableSolGrandWaveReason.DROP, dropConfidence)
                return true
            }
        }

        val sectionActive = t <= sectionWindowEnd
        val localCommon = peakBand && localArmed &&
            t - lastWaveT >= REPEAT_MIN_GAP_S && !vocalOnly
        val strictLocal = water >= LOCAL_MIN_WATER && kinetic >= LOCAL_MIN_KINETIC &&
            intensity >= 0.64 && musicMotion >= 0.64 &&
            physicalAttack >= LOCAL_MIN_ATTACK
        val strongNoveltyBridge = audibleHistoryS >= NOVELTY_HISTORY_S &&
            context >= NOVELTY_CONTEXT_MIN && gradeDrive01 >= NOVELTY_MIN_GRADE &&
            water >= 0.74 && water < BRIDGE_MAX_WATER && kinetic >= 0.85 &&
            intensity >= 0.62 && musicMotion >= 0.64
        val kineticDenseBridge = context >= 0.50 && gradeDrive01 >= 0.75 &&
            water >= 0.76 && water < BRIDGE_MAX_WATER && kinetic >= KINETIC_BRIDGE_MIN &&
            intensity >= 0.64 && musicMotion >= 0.76
        val massiveAttackArrival = water >= MASS_ARRIVAL_MIN_WATER &&
            kinetic >= MASS_ARRIVAL_MIN_KINETIC && intensity >= 0.68 &&
            gradeDrive01 >= 0.78 && musicMotion >= 0.60 &&
            context >= MASS_ARRIVAL_MIN_CONTEXT &&
            physicalAttack >= MASS_ARRIVAL_MIN_ATTACK
        val midWaterContextualArrival = water >= MID_WATER_MIN && water < MID_WATER_MAX &&
            kinetic >= MID_WATER_MIN_KINETIC && intensity >= MID_WATER_MIN_INTENSITY &&
            gradeDrive01 >= MID_WATER_MIN_GRADE && musicMotion >= MID_WATER_MIN_MUSIC &&
            context >= MID_WATER_MIN_CONTEXT && physicalAttack >= MID_WATER_MIN_ATTACK
        val sectionPhraseBridge = sectionActive && context >= 0.12 &&
            water >= 0.79 && kinetic >= 0.80 && intensity >= 0.65 && punch >= 0.80 &&
            musicMotion >= 0.64 && attack >= 0.20

        val localOk = localCommon &&
            (strictLocal || strongNoveltyBridge || massiveAttackArrival || midWaterContextualArrival)
        localConfirmS = advance(localConfirmS, localOk, dt)
        val denseOk = localCommon && kineticDenseBridge
        denseConfirmS = advance(denseConfirmS, denseOk, dt)
        sectionPhraseConfirmS = advance(
            sectionPhraseConfirmS,
            localCommon && sectionPhraseBridge,
            dt
        )

        val relativeHistoryReady = audibleHistoryS >= RELATIVE_MIN_HISTORY_S
        val relativeNoveltyOk = novelty >= RELATIVE_MIN_NOVELTY
        val relativeContextOk = gradeContext >= RELATIVE_MIN_GRADE_CONTEXT &&
            gradeContext <= RELATIVE_MAX_GRADE_CONTEXT
        val relativeLoudOk = loudRelative >= RELATIVE_MIN_LOUDNESS
        val relativePeak = localCommon && relativeHistoryReady && relativeNoveltyOk &&
            relativeLoudOk && relativeContextOk && water >= 0.70 && water < 0.80 &&
            kinetic >= 0.79 && intensity >= 0.64 && gradeDrive01 >= 0.70 &&
            musicMotion >= 0.60 && physicalAttack >= 0.19 && arousal >= 0.45
        val relativeLift = state == FableSolVisualState.LIFT && localArmed &&
            relativeHistoryReady && relativeNoveltyOk &&
            t - lastWaveT >= REPEAT_MIN_GAP_S && !vocalOnly &&
            relativeLoudOk && relativeContextOk && water >= 0.65 && water < 0.80 &&
            kinetic >= 0.80 && intensity >= 0.60 && gradeDrive01 >= 0.68 &&
            musicMotion >= 0.70 && physicalAttack >= 0.30 &&
            context >= 0.30 && arousal >= 0.35
        relativeConfirmS = advance(relativeConfirmS, relativePeak || relativeLift, dt)
        if (localConfirmS >= LOCAL_CONFIRM_S ||
            denseConfirmS >= DENSE_CONFIRM_S ||
            relativeConfirmS >= RELATIVE_CONFIRM_S ||
            sectionPhraseConfirmS >= SECTION_PHRASE_CONFIRM_S
        ) {
            fill(
                output,
                t,
                FableSolGrandWaveReason.CAUSAL_ARRIVAL,
                mean5(water, kinetic, intensity, musicMotion, attack)
            )
            return true
        }

        val repeatCommon = peakBand && episodeCount >= 1 && repeatArmed &&
            t - lastWaveT >= REPEAT_MIN_GAP_S && !vocalOnly
        val firstPunchRepeat = episodeRepeats == 0 &&
            t - lastWaveT <= FIRST_REPEAT_MAX_GAP_S &&
            water >= 0.79 && kinetic >= FIRST_REPEAT_MIN_KINETIC && intensity >= 0.62 &&
            punch >= 0.80 && musicMotion >= 0.58 &&
            physicalAttack >= FIRST_PUNCH_MIN_ATTACK
        val firstKineticRepeat = episodeRepeats == 0 &&
            t - lastWaveT <= FIRST_REPEAT_MAX_GAP_S &&
            water >= 0.79 && kinetic >= FIRST_REPEAT_MIN_KINETIC &&
            intensity >= 0.74 && punch >= 0.74 &&
            musicMotion >= 0.74 && physicalAttack >= 0.24
        val structuredLaterRepeat = episodeRepeats >= 1 && sectionActive &&
            water >= 0.79 && kinetic >= 0.80 && intensity >= 0.65 && punch >= 0.80 &&
            musicMotion >= 0.64 && attack >= 0.20 && context >= 0.12
        val resurgentLaterRepeat =
            (episodeRepeats >= 2 || (episodeRepeats >= 1 && sectionActive)) &&
                water >= 0.82 && kinetic >= 0.80 && intensity >= 0.60 && punch >= 0.72 &&
                musicMotion >= 0.64 && attack >= 0.30 && context >= 0.15
        val structuredMassRepeat = episodeRepeats >= 1 && sectionActive &&
            water >= STRUCTURED_MASS_MIN_WATER && kinetic >= 0.72 &&
            intensity >= 0.68 && gradeDrive01 >= 0.68 &&
            musicMotion >= 0.58 && context >= 0.12
        val attackedLaterRepeat = episodeRepeats >= 2 &&
            water >= ATTACKED_REPEAT_MIN_WATER && kinetic >= 0.80 &&
            intensity >= 0.68 && punch >= 0.78 && musicMotion >= 0.64 &&
            physicalAttack >= ATTACKED_REPEAT_MIN_ATTACK

        val repeatOk = repeatCommon &&
            (firstPunchRepeat || firstKineticRepeat || structuredLaterRepeat ||
                structuredMassRepeat || attackedLaterRepeat)
        repeatConfirmS = advance(repeatConfirmS, repeatOk, dt)
        resurgentRepeatConfirmS = advance(
            resurgentRepeatConfirmS,
            repeatCommon && resurgentLaterRepeat,
            dt
        )
        if (repeatConfirmS >= REPEAT_CONFIRM_S ||
            resurgentRepeatConfirmS >= RESURGENT_REPEAT_CONFIRM_S
        ) {
            fill(
                output,
                t,
                FableSolGrandWaveReason.PEAK_PHRASE_REPEAT,
                mean4(water, kinetic, intensity, punch)
            )
            return true
        }
        return false
    }

    /** 消费候选；只有物理层接受后才记入次数与 14s 可读间隔。 */
    fun resolve(request: FableSolGrandWaveRequest, accepted: Boolean) {
        clearConfirmation()
        when (request.reason) {
            FableSolGrandWaveReason.CAUSAL_ARRIVAL -> if (!accepted) {
                localArmed = false
                localReleaseS = 0.0
            }
            FableSolGrandWaveReason.PEAK_PHRASE_REPEAT -> if (!accepted) {
                repeatArmed = false
                punchReleaseS = 0.0
            }
            else -> Unit
        }
        if (!accepted) return
        episodeCount += 1
        if (request.reason == FableSolGrandWaveReason.PEAK_PHRASE_REPEAT) {
            episodeRepeats += 1
        }
        lastWaveT = request.audioT
        sectionWindowEnd = -100.0
        localArmed = false
        localReleaseS = 0.0
        repeatArmed = false
        punchReleaseS = 0.0
    }

    private fun beginEpisode(t: Double) {
        val recentPrePeakAccent = t - lastWaveT < REPEAT_MIN_GAP_S
        if (!recentPrePeakAccent) {
            episodeCount = 0
            episodeRepeats = 0
            localArmed = true
        }
        repeatArmed = false
        punchReleaseS = 0.0
        clearConfirmation()
    }

    private fun clearConfirmation() {
        localConfirmS = 0.0
        denseConfirmS = 0.0
        relativeConfirmS = 0.0
        sectionPhraseConfirmS = 0.0
        repeatConfirmS = 0.0
        resurgentRepeatConfirmS = 0.0
    }

    private fun appendHistory(t: Double, rising: Double, punch: Double) {
        val index: Int
        if (historySize < HISTORY_CAPACITY) {
            index = (historyHead + historySize) % HISTORY_CAPACITY
            historySize += 1
        } else {
            index = historyHead
            historyHead = (historyHead + 1) % HISTORY_CAPACITY
        }
        historyT[index] = t
        historyRising[index] = rising
        historyPunch[index] = punch
    }

    private fun pruneHistory(cutoff: Double) {
        while (historySize > 0 && historyT[historyHead] <= cutoff) {
            historyHead = (historyHead + 1) % HISTORY_CAPACITY
            historySize -= 1
        }
    }

    private fun clearHistory() {
        historyHead = 0
        historySize = 0
    }

    private fun historyMean(channel: Int, startT: Double, endT: Double): Double {
        var sum = 0.0
        var count = 0
        for (offset in 0 until historySize) {
            val index = (historyHead + offset) % HISTORY_CAPACITY
            val time = historyT[index]
            if (time > startT && time <= endT) {
                sum += if (channel == CHANNEL_RISING) historyRising[index] else historyPunch[index]
                count += 1
            }
        }
        return if (count > 0) sum / count else 0.0
    }

    private fun fill(
        output: FableSolGrandWaveRequest,
        audioT: Double,
        reason: FableSolGrandWaveReason,
        score01: Double
    ) {
        output.audioT = audioT
        output.reason = reason
        output.score01 = score01.coerceIn(0.0, 1.0)
    }

    private fun advance(runS: Double, condition: Boolean, dt: Double): Double =
        if (condition) runS + dt else 0.0

    private fun mean4(a: Double, b: Double, c: Double, d: Double): Double =
        ((a + b + c + d) * 0.25).coerceIn(0.0, 1.0)

    private fun mean5(a: Double, b: Double, c: Double, d: Double, e: Double): Double =
        ((a + b + c + d + e) * 0.20).coerceIn(0.0, 1.0)

    companion object {
        const val SECTION_WINDOW_S = 5.5
        const val LOCAL_CONFIRM_S = 0.18
        const val DENSE_CONFIRM_S = 0.05
        const val RELATIVE_CONFIRM_S = 0.05
        const val SECTION_PHRASE_CONFIRM_S = 0.05
        const val REPEAT_CONFIRM_S = 0.20
        const val RESURGENT_REPEAT_CONFIRM_S = 0.20
        const val REPEAT_MIN_GAP_S = 14.0
        const val LOCAL_RELEASE_S = 1.0
        const val PUNCH_RELEASE_S = 0.50
        const val LOCAL_MIN_ATTACK = 0.40
        const val LOCAL_MIN_WATER = 0.89
        const val LOCAL_MIN_KINETIC = 0.875
        const val BRIDGE_MAX_WATER = 0.84
        const val NOVELTY_CONTEXT_MIN = 0.90
        const val NOVELTY_MIN_GRADE = 0.70
        const val NOVELTY_HISTORY_S = 12.0
        const val KINETIC_BRIDGE_MIN = 0.92
        const val MASS_ARRIVAL_MIN_WATER = 0.91
        const val MASS_ARRIVAL_MIN_KINETIC = 0.80
        const val MASS_ARRIVAL_MIN_ATTACK = 0.30
        const val MASS_ARRIVAL_MIN_CONTEXT = 0.50
        const val MID_WATER_MIN = 0.84
        const val MID_WATER_MAX = 0.89
        const val MID_WATER_MIN_KINETIC = 0.86
        const val MID_WATER_MIN_INTENSITY = 0.70
        const val MID_WATER_MIN_GRADE = 0.82
        const val MID_WATER_MIN_MUSIC = 0.70
        const val MID_WATER_MIN_CONTEXT = 0.75
        const val MID_WATER_MIN_ATTACK = 0.28
        const val RELATIVE_MIN_HISTORY_S = NOVELTY_HISTORY_S
        const val RELATIVE_MIN_LOUDNESS = 1.0
        const val RELATIVE_MIN_NOVELTY = 0.20
        const val RELATIVE_MIN_GRADE_CONTEXT = 0.02
        const val RELATIVE_MAX_GRADE_CONTEXT = 0.11
        const val FIRST_PUNCH_MIN_ATTACK = 0.30
        const val FIRST_REPEAT_MIN_KINETIC = 0.84
        const val FIRST_REPEAT_MAX_GAP_S = 28.0
        const val STRUCTURED_MASS_MIN_WATER = 0.90
        const val ATTACKED_REPEAT_MIN_WATER = 0.88
        const val ATTACKED_REPEAT_MIN_ATTACK = 0.38

        private const val HISTORY_CAPACITY = 1024
        private const val CHANNEL_RISING = 0
        private const val CHANNEL_PUNCH = 1
    }
}
