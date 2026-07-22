package com.ywwynm.everythingdone.views.recording.fablesol

import java.util.Arrays
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 分析器复用的输入槽，避免每个 hop 创建 CalibrationInput。 */
class FableSolCalibrationInput {
    @JvmField var t = 0.0
    @JvmField var silent = true
    @JvmField var loudMDb = -120.0
    @JvmField var loudSDb = -120.0
    @JvmField var speedAbs01 = 0.0
    @JvmField var rawRateHz = 0.0
    @JvmField var onsetEnv = 0.0
    @JvmField var flux = 0.0
    @JvmField var tempoBpm = 0.0
    @JvmField var tempoConf01 = 0.0
    @JvmField var centroid01 = 0.5
    @JvmField var bassRatio01 = 0.0
    @JvmField var percussiveMotion01 = 0.0
    @JvmField var vocalMotion01 = 0.0
    @JvmField var harmonicMotion01 = 0.0
    @JvmField var beatMotion01 = 0.0
    @JvmField var grooveMotion01 = 0.0
    @JvmField var punch01 = 0.0
    @JvmField var lowShare01 = 0.0
    @JvmField var domainGradeTrim01 = 0.0
    /**
     * A 计权安全通道给出的"本帧比底噪高多少 dB"。水位用它做贴地衰减；
     * 缺省 999 表示调用方未提供（合成帧/旧测试），此时不做任何折减。
     */
    @JvmField var aboveFloorDb = 999.0
}

/** 原地写入的实时感知输出。 */
class FableSolPerceptualCalibration {
    @JvmField var loudness01 = 0.0
    @JvmField var loudnessRaw01 = 0.0
    @JvmField var loudnessAbsolute01 = 0.0
    @JvmField var loudnessMomentary01 = 0.0
    @JvmField var loudnessTransientBoost01 = 0.0
    @JvmField var waterDrive01 = 0.0
    @JvmField var loudP10Db = 0.0
    @JvmField var loudP95Db = 0.0
    @JvmField var speed01 = 0.0
    @JvmField var speedAbs01 = 0.0
    @JvmField var speedRank01 = 0.0
    @JvmField var kineticDrive01 = 0.0
    @JvmField var kineticTarget01 = 0.0
    @JvmField var motionContextBoost01 = 0.0
    @JvmField var intensityDrive01 = 0.0
    @JvmField var targetDps = 0.0
    @JvmField var musicArousal01 = 0.0
    @JvmField var punchLu01 = 0.0
    @JvmField var energy01 = 0.0
    @JvmField var energyRising01 = 0.0
    @JvmField var buildUp01 = 0.0
    @JvmField var gradeDrive01 = 0.0
    @JvmField var liftScore01 = 0.0
    @JvmField var climaxScore01 = 0.0
    @JvmField var gradeAbsolute01 = 0.0
    @JvmField var gradeContext01 = 0.0
    @JvmField var vocalSoloPenalty01 = 0.0
    @JvmField var zLoud = 0.0
    @JvmField var zFlux = 0.0
    @JvmField var zOnsetRate = 0.0
    @JvmField var zBass = 0.0
    @JvmField var zCentroid = 0.0
    @JvmField var dropTriggered = false
    @JvmField var dropConfidence01 = 0.0
}

/**
 * Python `audio/calibration.py` 的实时 Kotlin 端口。
 *
 * W 使用固定 K 计权 dB 标尺，S 使用四类固定运动表面，K 只能在 S 上增加有界正证据；曲内分位
 * 只保留诊断用途。统计刷新限制为 4Hz，所有 ring/scratch 均预分配。
 */
class FableSolPerceptualCalibrator(frameRate: Double) {

    private val frameRate = max(frameRate, 1.0)
    private val dt = 1.0 / this.frameRate
    var relativeLoudnessMix = DEFAULT_RELATIVE_MIX
        private set

    private val loudRange = AdaptiveRange(this.frameRate, 96.0, 10.0, 95.0, 6.0)
    private val rateRange = AdaptiveRange(this.frameRate, 64.0, 10.0, 90.0, 0.5)
    private val loudMomentary = ScalarRing((6.0 * this.frameRate).toInt())
    private val onset = ScalarRing((2.0 * this.frameRate).toInt())
    private val centroid = ScalarRing((2.0 * this.frameRate).toInt())
    private val robust = Array(5) { ScalarRing((64.0 * this.frameRate).toInt()) }
    private val robustMedian = DoubleArray(5)
    private val robustScale = DoubleArray(5) { 1.0 }
    private val robustFloor = doubleArrayOf(0.50, 0.015, 0.05, 0.018, 0.018)
    private val robustValues = DoubleArray(5)
    private val robustZ = DoubleArray(5)
    private val robustRefreshN = max((0.25 * this.frameRate).toInt(), 1)
    private var robustUntil = 0

    private val trendStride = max((0.25 * this.frameRate).toInt(), 1)
    private var trendUntil = 0
    private val trendCentroid = ScalarRing(96)
    private val trendRate = ScalarRing(96)
    private val trendBass = ScalarRing(96)
    private val trendScratchA = DoubleArray(96)
    private val trendScratchB = DoubleArray(96)
    private val trendIndex = IntArray(96)
    private val trendRanks = DoubleArray(96)
    private var buildTarget = 0.0

    private val motionContext = ScalarRing((10.0 * this.frameRate).toInt())
    private val loudContext = ScalarRing((10.0 * this.frameRate).toInt())
    private val centroidContext = ScalarRing((10.0 * this.frameRate).toInt())
    private val stateEvidence = FableSolCausalStateEvidence(this.frameRate)
    private val stateFrame = FableSolPerceptualFrame()
    private val output = FableSolPerceptualCalibration()

    private var audibleS = 0.0
    private var loudStatsReady = false
    private var loudness01 = 0.0
    private var speed01 = 0.0
    private var kinetic01 = 0.0
    private var positiveNovelty01 = 0.0
    private var contextMotionFast = Double.NaN
    private var contextLoudFast = Double.NaN
    private var contextCentroidFast = Double.NaN
    private var musicArousal01 = 0.0
    private var loudDynamic01 = 0.0
    private var dynamicUntil = 0
    private var energyPrevious = 0.0
    private var energyTrend = 0.0
    private var build01 = 0.0
    private var buildLatchedT = -100.0
    private var lastDropT = -100.0

    init {
        reset(true)
    }

    fun configure(relativeMix: Double) {
        relativeLoudnessMix = relativeMix.coerceIn(0.0, 0.6)
    }

    fun reset(full: Boolean) {
        audibleS = 0.0
        loudStatsReady = false
        loudness01 = 0.0
        speed01 = 0.0
        kinetic01 = 0.0
        positiveNovelty01 = 0.0
        contextMotionFast = Double.NaN
        contextLoudFast = Double.NaN
        contextCentroidFast = Double.NaN
        musicArousal01 = 0.0
        loudDynamic01 = 0.0
        dynamicUntil = 0
        energyPrevious = 0.0
        energyTrend = 0.0
        build01 = 0.0
        buildTarget = 0.0
        buildLatchedT = -100.0
        lastDropT = -100.0
        trendUntil = 0
        trendCentroid.clear(); trendRate.clear(); trendBass.clear()
        motionContext.clear(); loudContext.clear(); centroidContext.clear()
        stateEvidence.reset()
        if (full) {
            loudRange.clear(); rateRange.clear()
            loudMomentary.clear(); onset.clear(); centroid.clear()
            for (ring in robust) ring.clear()
            Arrays.fill(robustMedian, 0.0)
            Arrays.fill(robustScale, 1.0)
            robustUntil = 0
        }
        clearOutput()
    }

    fun step(input: FableSolCalibrationInput): FableSolPerceptualCalibration {
        if (!input.silent) {
            audibleS += dt
            if (audibleS >= 3.0) loudStatsReady = true
            if (loudStatsReady) loudRange.push(input.loudSDb, dt)
            rateRange.push(input.rawRateHz, dt)
            loudMomentary.push(input.loudMDb)
            onset.push(input.onsetEnv)
            centroid.push(input.centroid01)
        }

        val loudAbsolute = fixedLoudness01(input.loudSDb)
        val loudMomentary01 = fixedLoudness01(input.loudMDb)
        val transientDelta = max(loudMomentary01 - loudAbsolute, 0.0)
        val transientBoost = min(0.90 * relativeLoudnessMix, 0.35) * transientDelta
        // 贴地衰减（2026-07-22）：只比底噪高一点点的内容不该撑满水位。采集档给
        // 低频加了 18dB 搁架、又整体 +10.5dB，房间轰鸣的 K 计权短时响度足以落进
        // 满刻度区；此前唯一的拦截是 A 计权静音门，门一开水位就直接冲到 ~1.0，
        // 中间没有过渡——真机上就是"水位几乎一直都是满的"。这条连续折减让水位
        // 随"高出底噪多少 dB"平滑起来，真正的说话/音乐（远高于 14dB）不受影响。
        val loudRaw = clip01(loudAbsolute + transientBoost) *
            smoothstep(0.0, NEAR_FLOOR_SPAN_DB, input.aboveFloorDb)
        val loudTarget = if (input.silent) 0.0 else loudRaw
        loudness01 = follow(loudness01, loudTarget, if (loudTarget > loudness01) 0.120 else 1.200)

        val rateRank = rateRange.score(input.rawRateHz)
        val speedTarget = if (input.silent) 0.0 else clip01(input.speedAbs01)
        speed01 = clip01(follow(speed01, speedTarget, if (speedTarget > speed01) 0.22 else 0.82))
        val positiveNovelty = positiveContext(input, loudRaw)

        dynamicUntil--
        if (dynamicUntil <= 0) {
            loudDynamic01 = if (loudMomentary.count >= frameRate.toInt()) {
                clip01((loudMomentary.percentile(90.0) - loudMomentary.percentile(10.0)) / 12.0)
            } else 0.0
            dynamicUntil = robustRefreshN
        }
        val fluxMean = clip01(onset.mean)
        val rate01 = clip01(input.rawRateHz / 6.0)
        val tempoComponent = clip01(FableSolSpeed.tempo01(input.tempoBpm) * input.tempoConf01)
        val centroidMean = clip01(centroid.mean)
        val arousalTarget = if (input.silent) 0.0 else 0.30 * loudDynamic01 +
            0.25 * fluxMean + 0.15 * rate01 + 0.15 * tempoComponent + 0.15 * centroidMean
        musicArousal01 = follow(
            musicArousal01,
            arousalTarget,
            if (arousalTarget > musicArousal01) 0.4 else 3.0
        )

        val punchLu = if (input.silent) 0.0 else
            clip01((input.loudMDb - input.loudSDb - 3.0) / 12.0)
        robustValues[0] = input.loudSDb
        robustValues[1] = input.flux
        robustValues[2] = input.rawRateHz
        robustValues[3] = input.bassRatio01
        robustValues[4] = input.centroid01
        if (!input.silent && loudStatsReady) updateRobustZ() else Arrays.fill(robustZ, 0.0)

        val energyLogit = 0.30 * robustZ[0] + 0.25 * robustZ[1] + 0.20 * robustZ[2] +
            0.15 * robustZ[3] + 0.10 * robustZ[4]
        val energy = if (input.silent) 0.0 else 1.0 / (1.0 + exp(-energyLogit))
        val derivative = (energy - energyPrevious) / max(dt, 1e-6)
        energyPrevious = energy
        energyTrend = follow(energyTrend, derivative, 0.8)
        val rising01 = smoothstep(0.015, 0.16, energyTrend)
        val currentBuild = buildUp(input)

        val bassConcentration = clip01(input.bassRatio01 / 0.65)
        val spectralBreadth = clip01(0.55 * input.centroid01 + 0.45 * (1.0 - bassConcentration))
        val intensity = 0.50 * loudRaw + 0.20 * spectralBreadth +
            0.15 * clip01(musicArousal01) + 0.15 * clip01(energy)
        val kineticTarget = if (input.silent) 0.0 else FableSolSpeed.kineticDriveTarget01(
            speed01, input.grooveMotion01, intensity, positiveNovelty)
        kinetic01 = follow(kinetic01, kineticTarget, if (kineticTarget > kinetic01) 0.18 else 0.78)
        kinetic01 = max(clip01(kinetic01), speed01)

        stateFrame.t = input.t
        stateFrame.silent = input.silent
        stateFrame.waterDrive01 = loudness01
        stateFrame.intensityDrive01 = intensity
        stateFrame.kineticDrive01 = kinetic01
        stateFrame.percussiveMotion01 = input.percussiveMotion01
        stateFrame.vocalMotion01 = input.vocalMotion01
        stateFrame.harmonicMotion01 = input.harmonicMotion01
        stateFrame.grooveMotion01 = input.grooveMotion01
        stateFrame.musicArousal01 = musicArousal01
        stateFrame.energy01 = energy
        stateFrame.energyRising01 = rising01
        stateFrame.buildUp01 = currentBuild
        stateFrame.positiveNovelty01 = positiveNovelty
        stateFrame.punch01 = input.punch01
        stateFrame.punchLu01 = punchLu
        stateFrame.lowShare01 = input.lowShare01
        stateFrame.domainGradeTrim01 = input.domainGradeTrim01
        stateFrame.motionContextBoost01 = positiveNovelty
        stateFrame.centroid01 = input.centroid01
        val evidence = stateEvidence.step(stateFrame)

        var dropTriggered = false
        var dropConfidence = 0.0
        if (!input.silent && input.t - buildLatchedT <= 3.0 &&
            robustZ[3] > 2.0 && robustZ[0] > 2.0 && input.t - lastDropT >= 10.0) {
            dropConfidence = clip01(0.55 + 0.12 * min(robustZ[3] - 2.0, 2.0) +
                0.12 * min(robustZ[0] - 2.0, 2.0) + 0.21 * currentBuild)
            dropTriggered = true
            lastDropT = input.t
            buildLatchedT = -100.0
        }

        output.loudness01 = clip01(loudness01)
        output.loudnessRaw01 = loudRaw
        output.loudnessAbsolute01 = loudAbsolute
        output.loudnessMomentary01 = loudMomentary01
        output.loudnessTransientBoost01 = clip01(transientBoost)
        output.waterDrive01 = clip01(loudness01)
        output.loudP10Db = loudRange.lo
        output.loudP95Db = loudRange.hi
        output.speed01 = clip01(speed01)
        output.speedAbs01 = clip01(input.speedAbs01)
        output.speedRank01 = clip01(rateRank)
        output.kineticDrive01 = clip01(kinetic01)
        output.kineticTarget01 = clip01(kineticTarget)
        output.motionContextBoost01 = positiveNovelty
        output.intensityDrive01 = clip01(intensity)
        output.targetDps = FableSolFlowPolicy.targetFlowDps(kinetic01)
        output.musicArousal01 = clip01(musicArousal01)
        output.punchLu01 = punchLu
        output.energy01 = clip01(energy)
        output.energyRising01 = rising01
        output.buildUp01 = currentBuild
        output.gradeDrive01 = evidence.gradeDrive01
        output.liftScore01 = evidence.liftScore01
        output.climaxScore01 = evidence.climaxScore01
        output.gradeAbsolute01 = evidence.gradeAbsolute01
        output.gradeContext01 = evidence.gradeContext01
        output.vocalSoloPenalty01 = evidence.vocalSoloPenalty01
        output.zLoud = robustZ[0]
        output.zFlux = robustZ[1]
        output.zOnsetRate = robustZ[2]
        output.zBass = robustZ[3]
        output.zCentroid = robustZ[4]
        output.dropTriggered = dropTriggered
        output.dropConfidence01 = dropConfidence
        return output
    }

    private fun positiveContext(input: FableSolCalibrationInput, loudRaw01: Double): Double {
        if (contextMotionFast.isNaN()) {
            contextMotionFast = input.speedAbs01
            contextLoudFast = loudRaw01
            contextCentroidFast = input.centroid01
        } else if (!input.silent) {
            val alpha = 1.0 - exp(-dt / 0.80)
            contextMotionFast += (input.speedAbs01 - contextMotionFast) * alpha
            contextLoudFast += (loudRaw01 - contextLoudFast) * alpha
            contextCentroidFast += (input.centroid01 - contextCentroidFast) * alpha
        }
        val ready = motionContext.count >= (2.0 * frameRate).toInt()
        val target = if (input.silent || !ready) 0.0 else max(
            smoothstep(0.04, 0.22, contextMotionFast - motionContext.mean),
            max(
                smoothstep(0.05, 0.25, contextLoudFast - loudContext.mean),
                smoothstep(0.04, 0.18, contextCentroidFast - centroidContext.mean)
            )
        )
        if (!input.silent) {
            motionContext.push(contextMotionFast)
            loudContext.push(contextLoudFast)
            centroidContext.push(contextCentroidFast)
        }
        positiveNovelty01 = follow(
            positiveNovelty01,
            target,
            if (target > positiveNovelty01) 0.16 else 0.80
        )
        return clip01(positiveNovelty01)
    }

    private fun buildUp(input: FableSolCalibrationInput): Double {
        trendUntil--
        if (trendUntil <= 0 && !input.silent) {
            trendCentroid.push(input.centroid01)
            trendRate.push(input.rawRateHz)
            trendBass.push(input.bassRatio01)
            trendUntil = trendStride
            val n16 = (16.0 / 0.25).toInt()
            buildTarget = if (trendCentroid.count < n16) 0.0 else {
                val n = trendCentroid.copyChronological(trendScratchA)
                trendRate.copyChronological(trendScratchB)
                val rho = min(spearmanRising(trendScratchA, n), spearmanRising(trendScratchB, n))
                trendBass.copyChronological(trendScratchA)
                var bassRecent = 0.0
                for (i in max(0, n - 8) until n) bassRecent += trendScratchA[i]
                bassRecent /= min(8, n).coerceAtLeast(1)
                val p30 = percentileInPlace(trendScratchA, n, 30.0)
                trendBass.copyChronological(trendScratchA)
                val p60 = percentileInPlace(trendScratchA, n, 60.0)
                val relativeLow = if (p60 - p30 > 1e-4) 1.0 - smoothstep(p30, p60, bassRecent) else 0.0
                val absoluteLow = 1.0 - smoothstep(0.22, 0.38, bassRecent)
                smoothstep(0.60, 0.88, rho) * max(relativeLow, absoluteLow)
            }
        }
        build01 = follow(build01, buildTarget, if (buildTarget > build01) 0.45 else 1.8)
        if (build01 > 0.58) buildLatchedT = input.t
        return clip01(build01)
    }

    private fun spearmanRising(values: DoubleArray, n: Int): Double {
        if (n < 4) return 0.0
        var lo = values[0]; var hi = values[0]
        for (i in 1 until n) { lo = min(lo, values[i]); hi = max(hi, values[i]) }
        if (hi - lo < 1e-8) return 0.0
        for (i in 0 until n) trendIndex[i] = i
        for (i in 1 until n) {
            val current = trendIndex[i]
            var j = i - 1
            while (j >= 0 && values[trendIndex[j]] > values[current]) {
                trendIndex[j + 1] = trendIndex[j]
                j--
            }
            trendIndex[j + 1] = current
        }
        for (rank in 0 until n) trendRanks[trendIndex[rank]] = rank.toDouble()
        val center = (n - 1) * 0.5
        var numerator = 0.0; var x2 = 0.0; var r2 = 0.0
        for (i in 0 until n) {
            val x = i - center
            val r = trendRanks[i] - center
            numerator += x * r; x2 += x * x; r2 += r * r
        }
        val denominator = sqrt(x2 * r2)
        return if (denominator > 1e-12) numerator / denominator else 0.0
    }

    private fun updateRobustZ() {
        var ready = true
        val minimum = (4.0 * frameRate).toInt()
        for (ring in robust) if (ring.count < minimum) ready = false
        if (ready) {
            for (i in 0 until 5) robustZ[i] =
                ((robustValues[i] - robustMedian[i]) / robustScale[i]).coerceIn(-4.0, 4.0)
        } else Arrays.fill(robustZ, 0.0)
        for (i in 0 until 5) robust[i].push(robustValues[i])
        robustUntil--
        if (robustUntil <= 0 && robust[0].count >= 16) {
            for (i in 0 until 5) {
                val median = robust[i].percentile(50.0)
                robustMedian[i] = median
                robustScale[i] = max(robust[i].medianAbsoluteDeviation(median) * 1.4826, robustFloor[i])
            }
            robustUntil = robustRefreshN
        }
    }

    private fun clearOutput() {
        val blank = FableSolPerceptualCalibration()
        // reset 很低频；逐字段写清楚可避免把输出对象替换后破坏外部引用。
        output.loudness01 = blank.loudness01
        output.waterDrive01 = 0.0
        output.speed01 = 0.0
        output.kineticDrive01 = 0.0
        output.targetDps = 0.0
        output.dropTriggered = false
        output.dropConfidence01 = 0.0
    }

    private fun follow(value: Double, target: Double, tau: Double): Double =
        value + (target - value) * (1.0 - exp(-dt / max(tau, 1e-4)))

    private class AdaptiveRange(
        private val frameRate: Double,
        seconds: Double,
        private val qLo: Double,
        private val qHi: Double,
        private val minSpan: Double
    ) {
        private val ring = ScalarRing((seconds * frameRate).toInt())
        private val refreshN = max((0.25 * frameRate).toInt(), 1)
        private var untilRefresh = 0
        private var rawLo = Double.NaN
        private var rawHi = Double.NaN
        var lo = 0.0
            private set
        var hi = 0.0
            private set

        fun clear() {
            ring.clear(); untilRefresh = 0
            rawLo = Double.NaN; rawHi = Double.NaN; lo = 0.0; hi = 0.0
        }

        fun push(value: Double, dt: Double) {
            ring.push(value)
            untilRefresh--
            if (untilRefresh <= 0) {
                val targetLo = ring.percentile(qLo)
                val targetHi = ring.percentile(qHi)
                if (rawLo.isNaN()) {
                    rawLo = targetLo; rawHi = targetHi; lo = targetLo; hi = targetHi
                } else {
                    val slow = 1.0 - exp(-max(dt * refreshN, 0.0) / 24.0)
                    rawLo = if (targetLo < rawLo) targetLo else rawLo + (targetLo - rawLo) * slow
                    rawHi = if (targetHi > rawHi) targetHi else rawHi + (targetHi - rawHi) * slow
                }
                untilRefresh = refreshN
            }
            if (!rawLo.isNaN()) {
                val anchor = 1.0 - exp(-max(dt, 0.0) / 1.2)
                lo += (rawLo - lo) * anchor
                hi += (rawHi - hi) * anchor
            }
        }

        fun score(value: Double): Double = if (rawLo.isNaN()) 0.0 else
            clip01((value - lo) / max(hi - lo, minSpan))
    }

    private class ScalarRing(capacity: Int) {
        private val data = DoubleArray(max(capacity, 1))
        private val scratch = DoubleArray(data.size)
        private val deviationScratch = DoubleArray(data.size)
        private var index = 0
        var count = 0
            private set
        private var sum = 0.0
        val mean: Double get() = if (count > 0) sum / count else 0.0

        fun clear() { index = 0; count = 0; sum = 0.0 }

        fun push(value: Double) {
            if (count == data.size) sum -= data[index] else count++
            data[index] = value
            sum += value
            index = (index + 1) % data.size
        }

        fun copyChronological(output: DoubleArray): Int {
            val n = min(count, output.size)
            val start = (index - n + data.size) % data.size
            for (i in 0 until n) output[i] = data[(start + i) % data.size]
            return n
        }

        fun percentile(q: Double): Double {
            val n = copyChronological(scratch)
            return percentileInPlace(scratch, n, q)
        }

        fun medianAbsoluteDeviation(median: Double): Double {
            val n = copyChronological(scratch)
            for (i in 0 until n) deviationScratch[i] = kotlin.math.abs(scratch[i] - median)
            return percentileInPlace(deviationScratch, n, 50.0)
        }
    }

    companion object {
        const val DEFAULT_RELATIVE_MIX = 0.20
        private const val LOUDNESS_FLOOR_DB = -30.0
        private const val LOUDNESS_CEILING_DB = -2.0
        /** 贴地余量：内容高出 A 计权底噪不足这么多 dB 时，水位按 smoothstep 连续折减。 */
        const val NEAR_FLOOR_SPAN_DB = 14.0

        fun fixedLoudness01(valueDb: Double): Double = clip01(
            (valueDb - LOUDNESS_FLOOR_DB) / (LOUDNESS_CEILING_DB - LOUDNESS_FLOOR_DB))

        private fun clip01(value: Double): Double = value.coerceIn(0.0, 1.0)

        private fun smoothstep(lo: Double, hi: Double, value: Double): Double {
            if (hi <= lo) return if (value >= hi) 1.0 else 0.0
            val q = clip01((value - lo) / (hi - lo))
            return q * q * (3.0 - 2.0 * q)
        }

        private fun percentileInPlace(values: DoubleArray, n: Int, q: Double): Double {
            if (n <= 0) return 0.0
            Arrays.sort(values, 0, n)
            val position = q.coerceIn(0.0, 100.0) / 100.0 * (n - 1)
            val lower = position.toInt()
            val upper = min(lower + 1, n - 1)
            val fraction = position - lower
            return values[lower] + (values[upper] - values[lower]) * fraction
        }
    }
}
