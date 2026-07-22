package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.max

/**
 * 严格因果、可重复的巨浪事件门控，对应 Python `core/grand_wave_gate.py`。
 *
 * 七境描述持续听感；840dp 巨浪是一道短语重音，因此独立鉴权。历史仅保留 trailing 2.5s，
 * 使用固定 primitive ring；长 PEAK/CLIMAX 没有次数配额，每次真实短语释放并经过 14s 可读间隔后
 * 都可再次触发。调用方必须对每个 true 结果调用 [resolve]，无论物理层最终是否接受。
 */
class FableSolGrandWaveEventGate {

    private val historyT = DoubleArray(HISTORY_CAPACITY)
    private val historyRising = DoubleArray(HISTORY_CAPACITY)
    private val historyPunch = DoubleArray(HISTORY_CAPACITY)
    private var historyHead = 0
    private var historySize = 0
    private val sharedRequest = FableSolGrandWaveRequest()

    private var lastT = Double.NaN
    private var sectionIntensity = Double.NaN
    private var sectionWindowEnd = -100.0
    private var sectionConfirmS = 0.0
    private var localConfirmS = 0.0
    private var repeatConfirmS = 0.0
    private var dropPending = false
    private var dropConfidence = 0.0
    private var peakBandLast = false
    private var episodeCount = 0
    /** 本 episode 内已发出的重复短语巨浪次数，上限 [REPEAT_PER_EPISODE]。 */
    private var episodeRepeats = 0
    private var localArmed = true
    private var localReleaseS = 0.0
    private var repeatArmed = false
    private var punchReleaseS = 0.0
    private var lastWaveT = -100.0

    fun reset() {
        clearHistory()
        lastT = Double.NaN
        sectionIntensity = Double.NaN
        sectionWindowEnd = -100.0
        sectionConfirmS = 0.0
        localConfirmS = 0.0
        repeatConfirmS = 0.0
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
        sectionConfirmS = 0.0
    }

    fun clearSectionContext() {
        sectionIntensity = Double.NaN
        sectionWindowEnd = -100.0
        sectionConfirmS = 0.0
    }

    /**
     * 段落强度有序抬升时开启 4.5s 鉴权窗。优先使用最后一帧音频时钟，避免暂停时间拉长窗口。
     */
    fun notifySection(
        intensity01: Double,
        surge: Boolean = true,
        now: Double = Double.NaN,
        sourceT: Double = 0.0
    ) {
        val intensity = intensity01.coerceIn(0.0, 1.0)
        val previous = sectionIntensity
        sectionIntensity = intensity
        sectionWindowEnd = -100.0
        sectionConfirmS = 0.0
        if (previous.isNaN()) return
        val arrivalT = when {
            !lastT.isNaN() -> lastT
            !now.isNaN() -> now
            else -> sourceT
        }
        if (surge && intensity >= 0.65 && intensity - previous >= 0.15) {
            sectionWindowEnd = arrivalT + SECTION_WINDOW_S
        }
    }

    fun notifyDrop(confidence01: Double = 1.0) {
        dropPending = true
        dropConfidence = confidence01.coerceIn(0.0, 1.0)
    }

    fun episodeCount(): Int = episodeCount

    fun lastWaveTime(): Double = lastWaveT

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
        val attack = max(frame.motionContextBoost01, max(rising08, punchDelta01))
        val context = frame.motionContextBoost01

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
        val sectionGradeOk = peakBand ||
            (context >= 0.55 && gradeDrive01 >= 0.47 && !vocalOnly)
        val sectionOk = sectionGradeOk && sectionActive &&
            t - lastWaveT >= REPEAT_MIN_GAP_S &&
            water >= 0.76 && kinetic >= 0.72 && intensity >= 0.60 &&
            musicMotion >= 0.58 && attack >= 0.22
        sectionConfirmS = advance(sectionConfirmS, sectionOk, dt)
        if (sectionConfirmS >= SECTION_CONFIRM_S) {
            fill(
                output,
                t,
                FableSolGrandWaveReason.SECTION_LIFT,
                mean5(water, kinetic, intensity, musicMotion, attack)
            )
            return true
        }

        val localCommon = peakBand && localArmed && !sectionActive &&
            t - lastWaveT >= REPEAT_MIN_GAP_S && !vocalOnly
        val strictLocal = water >= 0.79 && kinetic >= 0.75 && intensity >= 0.64 &&
            musicMotion >= 0.64 && attack >= LOCAL_MIN_ATTACK
        // 强编曲新颖度可在水位慢包络尚未完全追上时桥接，但只能发生在 PEAK/CLIMAX，且仍受
        // 14s、音乐质量和 vocal-only 门约束；普通高能平台脉冲继续使用 strictLocal。
        val strongNoveltyBridge = context >= 0.55 && gradeDrive01 >= 0.70 &&
            water >= 0.74 && kinetic >= 0.85 && intensity >= 0.62 && musicMotion >= 0.70
        val localOk = localCommon && (strictLocal || strongNoveltyBridge)
        localConfirmS = advance(localConfirmS, localOk, dt)
        if (localConfirmS >= LOCAL_CONFIRM_S) {
            fill(
                output,
                t,
                FableSolGrandWaveReason.CAUSAL_ARRIVAL,
                mean5(water, kinetic, intensity, musicMotion, attack)
            )
            return true
        }

        val repeatOk = peakBand && episodeCount >= 1 &&
            episodeRepeats < REPEAT_PER_EPISODE && repeatArmed &&
            t - lastWaveT >= REPEAT_MIN_GAP_S &&
            water >= 0.79 && kinetic >= 0.75 && intensity >= 0.62 &&
            punch >= 0.80 && musicMotion >= 0.58 && !vocalOnly
        repeatConfirmS = advance(repeatConfirmS, repeatOk, dt)
        if (repeatConfirmS >= REPEAT_CONFIRM_S) {
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
            FableSolGrandWaveReason.SECTION_LIFT -> sectionWindowEnd = -100.0
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
        sectionConfirmS = 0.0
        localConfirmS = 0.0
        repeatConfirmS = 0.0
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
        const val SECTION_WINDOW_S = 4.5
        const val SECTION_CONFIRM_S = 0.20
        const val LOCAL_CONFIRM_S = 0.20
        const val REPEAT_CONFIRM_S = 0.30
        const val REPEAT_MIN_GAP_S = 14.0
        const val LOCAL_RELEASE_S = 1.0
        const val PUNCH_RELEASE_S = 0.50
        // 每个高潮 episode 最多两道巨浪：段落抬升/本地到达开局，重复短语补一道。
        // 2026-07-21 用户裁定"比之前稍微多一些、但别太多"——段落通道保持不限次
        // （结构证据本身足够稀有），只把无结构支撑的 repeat 通道压回每 episode
        // 一次。实测 Lose My Mind 母带：多出来的 98.5s 与 174.8s 正是同一
        // episode 里的第三、第四记重击。
        const val REPEAT_PER_EPISODE = 1
        // 本地到达（无段落、无重复短语支撑）必须有足够强的当下攻击证据。
        // 实测录音版：想要的 54.1s attack≥0.45，多出来的 39.1s 只有 0.34。
        const val LOCAL_MIN_ATTACK = 0.40

        private const val HISTORY_CAPACITY = 1024
        private const val CHANNEL_RISING = 0
        private const val CHANNEL_PUNCH = 1
    }
}
