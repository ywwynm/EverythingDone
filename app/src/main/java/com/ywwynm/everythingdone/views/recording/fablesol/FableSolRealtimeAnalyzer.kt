package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 实时特征链（对应 features.py 的 RealtimeAnalyzer）：A 加权响度、底噪追踪、自校准归一、
 * spectral flux onset、频段能量、频谱重心/平坦度、onset 密度→快慢、节拍、段落。
 * 采样率复用现有采集 44100Hz（D3），FRAME_RATE=SR/HOP 自适应。语义 MusiCNN 路径不移植。
 */
class FableSolRealtimeAnalyzer(private val sr: Int = 44100) {

    private val frameRate = sr.toDouble() / HOP
    private val halfN = N_FFT / 2
    private val anchorAlpha = 1.0 - exp(-1.0 / (frameRate * 1.2))

    private val window = FableSolMath.hanning(N_FFT)
    private val freqs = DoubleArray(halfN + 1) { it.toDouble() * sr / N_FFT }
    private val kwPow = FableSolAudioFrontEnd.kWeightPower(freqs, sr.toDouble())
    private val awPow = DoubleArray(halfN + 1) { Math.pow(10.0, aWeightDb(freqs[it]) / 10.0) }
    private val idx250 = FableSolMath.searchsorted(freqs, 250.0)
    private val idx2000 = FableSolMath.searchsorted(freqs, 2000.0)
    private val idx16000 = FableSolMath.searchsorted(freqs, 16000.0)
    private val bandIdx: IntArray
    private val dbRef: Double
    private val dbRefA: Double
    private val fluxFloor: Double
    private val whiteDecay = Math.pow(0.5, 1.0 / (WHITEN_HALFLIFE_S * frameRate))

    // 复用 scratch
    private val reArr = DoubleArray(N_FFT)
    private val imArr = DoubleArray(N_FFT)
    private val spec = DoubleArray(halfN + 1)
    private val wspec = DoubleArray(halfN + 1)

    private val novelty = FableSolNoveltyDetector(frameRate, 13, 24, 4.2)
    private val beat = FableSolBeatTracker(frameRate)
    private val cap = (30.0 * frameRate).toInt()
    private val rateCap = (20.0 * frameRate).toInt()
    private val rLoud = FableSolRingStat(cap, anchorAlpha)
    private val rLow = FableSolRingStat(cap, anchorAlpha)
    private val rMid = FableSolRingStat(cap, anchorAlpha)
    private val rHigh = FableSolRingStat(cap, anchorAlpha)
    private val rFlux = FableSolRingStat(cap, anchorAlpha)
    private val rRate = FableSolRingStat(rateCap, anchorAlpha)
    private val rF0 = FableSolRingStat((8.0 * frameRate).toInt(), anchorAlpha)
    private val rImpulseEnv = FableSolRingStat((1.2 * frameRate).toInt(), anchorAlpha)
    private val rMomentary = FableSolRunningMeanRing(Math.round(MOMENTARY_S * frameRate).toInt())
    private val rShortTerm = FableSolRunningMeanRing(Math.round(SHORTTERM_S * frameRate).toInt())
    private val envBandPass = FableSolAudioFrontEnd.bandPass(FLUCT_BP_HZ, FLUCT_BP_Q, frameRate)
    private val envHighPassR = FableSolAudioFrontEnd.highPassPole(ENV_HP_HZ, frameRate)
    private val rEnvAc = FableSolRunningMeanRing(Math.round(FLUCT_WIN_S * frameRate).toInt())
    private val rEnvBand = FableSolRunningMeanRing(Math.round(FLUCT_WIN_S * frameRate).toInt())
    private val rFluxCv = FableSolRunningMeanRing(Math.round(FLUX_CV_WIN_S * frameRate).toInt())

    @JvmField var agcWindowS = 24.0
    @JvmField var gateDb = 6.0
    @JvmField var expander = 0.32

    // 运行状态
    private var buf = DoubleArray(0)
    private var nConsumed = 0
    private var tBase = 0.0
    private var silent = false
    private var belowSince = Double.NaN
    private var prevLogbands: DoubleArray? = null
    private var whitePeak: DoubleArray? = null
    private val envHist = DoubleArray(3)
    private val strHist = DoubleArray(3)
    private var percEnv = 0.0
    private var speed01 = 0.0
    private var tempoSpeed01 = 0.5
    private var emaFast: DoubleArray? = null
    private var emaSlow: DoubleArray? = null
    private val onsetTimes = ArrayDeque<DoubleArray>()   // [t, strength]
    private var lastOnsetT = -10.0
    private var music01 = 0.0
    private var envHighPassX = 0.0
    private var envHighPassY = 0.0
    private var pitchBaseLog = Double.NaN
    private var pitchRelSm = 0.5
    private var syllableAbove = false
    private var syllablePeakDb = -120.0
    private var syllablePeakT = 0.0
    private val syllableTimes = ArrayDeque<Double>()
    private var lastProminenceT = -10.0
    private var hnrSm = 0.0
    private var arousalSm = 0.0
    private var loomSm = 0.0
    private var previousShortDb = Double.NaN
    private var floorDb = -60.0
    private val centers = HashMap<String, Double>()
    private var centerAge = 0.0
    // AudioRecord/MIC 在部分手机启动后的数秒会输出很强、随后衰减的低频暂态。
    // 预热门只拦截采集会话开头；稳定静音或可信中高频内容可提前结束预热。
    private var startupFirstT = Double.NaN
    private var startupTrustedSince = Double.NaN
    private var startupReady = false

    init {
        val edges = FableSolMath.geomspace(60.0, 12000.0, 33)
        bandIdx = IntArray(33) { FableSolMath.searchsorted(freqs, edges[it]) }
        // dB 标定：满幅 1kHz 正弦 → 0 dB（近似 dBFS）
        for (i in 0 until N_FFT) { reArr[i] = sin(2.0 * Math.PI * 1000.0 * i / sr) * window[i]; imArr[i] = 0.0 }
        FableSolFft.transform(reArr, imArr)
        var refK = 0.0
        var refA = 0.0
        var refUnweighted = 0.0
        for (i in 0 until idx16000) {
            val raw = reArr[i] * reArr[i] + imArr[i] * imArr[i]
            refK += raw * kwPow[i]
            refA += raw * awPow[i]
            refUnweighted += raw
        }
        dbRef = 10.0 * log10(refK)
        dbRefA = 10.0 * log10(refA)
        fluxFloor = refUnweighted * Math.pow(10.0, WHITEN_FLOOR_DBFS / 10.0)
        reset(true)
    }

    fun reset(full: Boolean) {
        buf = DoubleArray(0)
        nConsumed = 0
        tBase = 0.0
        // 从静音启动；通过相对门限和绝对可听度后才开放视觉驱动。
        silent = true
        belowSince = Double.NaN
        prevLogbands = null
        whitePeak = null
        rMomentary.reset(); rShortTerm.reset()
        envBandPass.reset(); envHighPassX = 0.0; envHighPassY = 0.0
        rEnvAc.reset(); rEnvBand.reset(); rFluxCv.reset()
        java.util.Arrays.fill(envHist, 0.0)
        java.util.Arrays.fill(strHist, 0.0)
        percEnv = 0.0
        speed01 = 0.0
        tempoSpeed01 = 0.5
        music01 = 0.0
        pitchBaseLog = Double.NaN
        pitchRelSm = 0.5
        syllableAbove = false; syllablePeakDb = -120.0; syllablePeakT = 0.0
        syllableTimes.clear(); lastProminenceT = -10.0
        hnrSm = 0.0; arousalSm = 0.0; loomSm = 0.0; previousShortDb = Double.NaN
        emaFast = null; emaSlow = null
        novelty.reset(full)
        beat.reset()
        onsetTimes.clear()
        lastOnsetT = -10.0
        startupFirstT = Double.NaN
        startupTrustedSince = Double.NaN
        startupReady = false
        if (full) {
            floorDb = -60.0
            centers.clear()
            centerAge = 0.0
            rLoud.reset(); rLow.reset(); rMid.reset(); rHigh.reset(); rFlux.reset(); rRate.reset()
            rF0.reset(); rImpulseEnv.reset()
        }
    }

    fun setTimeBase(t: Double) {
        tBase = t
        nConsumed = 0
        buf = DoubleArray(0)
        silent = true
        belowSince = Double.NaN
        prevLogbands = null
        whitePeak = null
        rMomentary.reset(); rShortTerm.reset()
        envBandPass.reset(); envHighPassX = 0.0; envHighPassY = 0.0
        rEnvAc.reset(); rEnvBand.reset(); rFluxCv.reset()
        java.util.Arrays.fill(envHist, 0.0)
        java.util.Arrays.fill(strHist, 0.0)
        percEnv = 0.0
        speed01 = 0.0
        tempoSpeed01 = 0.5
        syllableAbove = false; syllablePeakDb = -120.0; syllablePeakT = 0.0
        syllableTimes.clear(); lastProminenceT = -10.0
        onsetTimes.clear()
        lastOnsetT = t - 10.0
        startupFirstT = Double.NaN
        startupTrustedSince = Double.NaN
        startupReady = false
        emaFast = null; emaSlow = null
        novelty.reset(false)
        beat.reset()
    }

    /** 喂入任意长度 mono（float，[-1,1]）；返回该批产生的 frames 与 events。 */
    fun feed(mono: DoubleArray): Pair<List<FableSolFeatureFrame>, List<FableSolEvent>> {
        val frames = ArrayList<FableSolFeatureFrame>()
        val events = ArrayList<FableSolEvent>()
        buf = if (buf.isEmpty()) mono.copyOf() else concat(buf, mono)
        var off = 0
        while (buf.size - off >= N_FFT) {
            val t = tBase + (nConsumed + N_FFT / 2.0) / sr
            process(buf, off, t, frames, events)
            off += HOP
            nConsumed += HOP
        }
        if (off > 0) buf = buf.copyOfRange(off, buf.size)
        return Pair(frames, events)
    }

    private fun process(src: DoubleArray, srcOff: Int, t: Double,
                        frames: ArrayList<FableSolFeatureFrame>, events: ArrayList<FableSolEvent>) {
        val k = (agcWindowS * frameRate).toInt()
        for (i in 0 until N_FFT) { reArr[i] = src[srcOff + i] * window[i]; imArr[i] = 0.0 }
        FableSolFft.transform(reArr, imArr)
        var pTotal = 0.0
        var pTotalA = 0.0
        for (i in 0..halfN) {
            val s = reArr[i] * reArr[i] + imArr[i] * imArr[i]
            spec[i] = s
            // 近 Nyquist 手机电子干扰不属于可视化的听觉频段。
            val ws = if (i < idx16000) s * kwPow[i] else 0.0
            wspec[i] = ws
            pTotal += ws
            if (i < idx16000) pTotalA += s * awPow[i]
        }
        val db = 10.0 * log10(pTotalA + 1e-12) - dbRefA
        var pLow = 0.0; for (i in 0 until idx250) pLow += wspec[i]
        var pMid = 0.0; for (i in idx250 until idx2000) pMid += wspec[i]
        var pHigh = 0.0; for (i in idx2000 until idx16000) pHigh += wspec[i]
        if (suppressCaptureStartup(t, db, pLow, pMid, pHigh)) {
            frames.add(startupSilentFrame(t, db))
            return
        }
        rMomentary.push(pTotal)
        rShortTerm.push(pTotal)
        val dbM = 10.0 * log10(rMomentary.mean() + 1e-12) - dbRef
        val dbS = 10.0 * log10(rShortTerm.mean() + 1e-12) - dbRef
        val envLinear = sqrt(max(pTotal, 0.0))
        val highPassed = envHighPassR * (envHighPassY + envLinear - envHighPassX)
        envHighPassX = envLinear
        envHighPassY = highPassed
        val envBand = envBandPass.process(highPassed)
        rEnvAc.push(highPassed * highPassed)
        rEnvBand.push(envBand * envBand)
        // 底噪追踪
        if (db < floorDb) floorDb = db
        else {
            val prox = max(0.0, 1.0 - (db - floorDb) / 18.0)
            floorDb += (db - floorDb) * (1.0 / frameRate) / FLOOR_RELAX_S * prox
        }
        floorDb = max(floorDb, db - 60.0)
        // 静音门（迟滞 + 进入需持续 150ms）
        val tEnter = max(floorDb + gateDb - 2.0, -80.0)
        val tExit = max(floorDb + gateDb + 2.0, -76.0)
        if (silent) {
            if (db > tExit) silent = false
        } else if (db < tEnter) {
            if (belowSince.isNaN()) belowSince = t
            else if (t - belowSince >= 0.15) { silent = true; belowSince = Double.NaN }
        } else belowSince = Double.NaN
        val audibility = smoothstep(AUDIBILITY_ZERO_DB, AUDIBILITY_FULL_DB, db)
        // 相对门适配环境；绝对可听度只拦截极低电平电子噪声/AGC 泵动。
        val sil = silent || audibility <= AUDIBILITY_SILENT_CUTOFF
        // 响度混合归一
        if (!sil) rLoud.push(dbM)
        val rank = rLoud.span01(dbM, k, 5.0, 95.0, MIN_SPAN_DB)
        val confLoud = !sil && audibility >= 0.15 && db > floorDb + gateDb + 6.0
        if (confLoud) centerAge += 1.0 / frameRate
        val center = trackCenter("loud", dbM, confLoud)
        val trust = 0.25 + 0.75 * min(centerAge / 12.0, 1.0)
        val dbAbs = (0.5 + (dbM - center) / ABS_SPAN_DB * trust).coerceIn(0.0, 1.0)
        val wLoud = 0.3 * min(rLoud.count.toDouble() / k, 1.0)
        var loud01 = wLoud * rank + (1.0 - wLoud) * dbAbs
        loud01 = (0.5 + (loud01 - 0.5) * (1.0 + expander * 1.2)).coerceIn(0.0, 1.0)
        loud01 *= audibility
        // 频段
        val low01 = band01(pLow, rLow, "low", confLoud, trust, k, sil) * audibility
        val mid01 = band01(pMid, rMid, "mid", confLoud, trust, k, sil) * audibility
        val high01 = band01(pHigh, rHigh, "high", confLoud, trust, k, sil) * audibility
        // 谱形比例（长期身份）
        val rLowRoot = sqrt(max(pLow, 1e-20)); val rMidRoot = sqrt(max(pMid, 1e-20)); val rHighRoot = sqrt(max(pHigh, 1e-20))
        val rootSum = max(rLowRoot + rMidRoot + rHighRoot, 1e-12)
        val relLow = rLowRoot / rootSum; val relMid = rMidRoot / rootSum; val relHigh = rHighRoot / rootSum
        val tiltDb = 10.0 * log10((pLow + 1e-12) / (pHigh + 1e-12))
        val spectralTilt01 = (0.5 + tiltDb / 36.0).coerceIn(0.0, 1.0)
        // 质心
        var cAcc = 0.0
        for (i in 0..halfN) cAcc += freqs[i] * wspec[i]
        val cHz = cAcc / (pTotal + 1e-12)
        val centroid01 = (ln(max(cHz, 200.0) / 200.0) / ln(8000.0 / 200.0)).coerceIn(0.0, 1.0)
        // 谱平坦度（固定 dB 映射）
        var logSum = 0.0; var linSum = 0.0
        for (i in 1 until idx16000) { logSum += log10(spec[i] + 1e-12); linSum += spec[i] }
        val nSp = idx16000 - 1
        val flatDb = 10.0 * (logSum / nSp - log10(linSum / nSp + 1e-12))
        val flat01 = ((flatDb + 45.0) / 30.0).coerceIn(0.0, 1.0)
        // 立体声（mono → 中性）
        val stereoWidth01 = 0.0; val pan01 = 0.5
        // 自适应白化 + SuperFlux；未白化 logb 继续供 Foote 段落检测。
        val bandpow = FableSolMath.sumAdjacentSegments(spec, bandIdx)
        val logb = DoubleArray(32) { log10(bandpow[it] + 1e-10) }
        var peaks = whitePeak
        if (peaks == null) {
            peaks = DoubleArray(32) { max(bandpow[it], fluxFloor) }
            whitePeak = peaks
        } else {
            for (i in 0 until 32) peaks[i] = max(max(bandpow[i], fluxFloor), peaks[i] * whiteDecay)
        }
        val logw = DoubleArray(32) { log10(bandpow[it] / peaks[it] + 1e-6) }
        var flux = 0.0
        val prev = prevLogbands
        if (prev != null) {
            for (i in 0 until 32) {
                var ref = prev[i]
                if (i > 0) ref = max(ref, prev[i - 1])
                if (i + 1 < 32) ref = max(ref, prev[i + 1])
                val d = logw[i] - ref
                if (d > 0.0) flux += d
            }
        }
        prevLogbands = logw
        // 所有帧都为 flux 提供基线；audibility 会阻止极低电平变化被相对归一放大。
        rFlux.push(flux)
        val rf = rFlux.recent(k)
        var onsetEnv = 0.0; var strength = 0.0
        if (rf.size >= 8) {
            val pcs = FableSolMath.percentiles(rf, doubleArrayOf(50.0, 95.0, 98.0))
            onsetEnv = ((flux - pcs[0]) / max(pcs[1] - pcs[0], 1e-3)).coerceIn(0.0, 1.5) / 1.5
            strength = ((flux - pcs[0]) / max(pcs[2] - pcs[0], 1e-3)).coerceIn(0.0, 1.0)
        }
        onsetEnv *= audibility
        strength *= audibility
        // onset 事件（因果峰选，延迟 1 帧）
        envHist[0] = envHist[1]; envHist[1] = envHist[2]; envHist[2] = onsetEnv
        strHist[0] = strHist[1]; strHist[1] = strHist[2]; strHist[2] = strength
        val e0 = envHist[0]; val e1 = envHist[1]; val e2 = envHist[2]
        if (!sil && e1 > 0.27 && e1 >= e2 && e1 > e0 && t - lastOnsetT > ONSET_MIN_GAP_S) {
            lastOnsetT = t
            onsetTimes.addLast(doubleArrayOf(t, strHist[1]))
            events.add(FableSolEvent.Onset(t, strHist[1], centroid01, low01, mid01, high01, flat01, pan01, stereoWidth01))
        }
        while (onsetTimes.isNotEmpty() && onsetTimes.first()[0] < t - FableSolSpeed.RATE_WINDOW_S) onsetTimes.removeFirst()
        val rawRate = onsetTimes.size / FableSolSpeed.RATE_WINDOW_S
        var salientSum = 0.0
        var fastRawCount = 0
        var fastSalientSum = 0.0
        for (o in onsetTimes) {
            val weight = FableSolSpeed.onsetSalienceWeight(o[1])
            salientSum += weight
            if (o[0] > t - FableSolSpeed.FAST_RATE_WINDOW_S) {
                fastRawCount++
                fastSalientSum += weight
            }
        }
        val salientRate = salientSum / FableSolSpeed.RATE_WINDOW_S
        val fastRawRate = fastRawCount / FableSolSpeed.FAST_RATE_WINDOW_S
        val fastSalientRate = fastSalientSum / FableSolSpeed.FAST_RATE_WINDOW_S
        rRate.push(rawRate)
        val relRate = rRate.span01(rawRate, rateCap, 10.0, 90.0, 0.5)
        val activityDensity01 = FableSolSpeed.onsetDensity01(rawRate)
        val warm = min(rRate.count.toDouble() / rateCap, 1.0)
        val wr = 0.65 * warm
        val activity01 = (wr * relRate + (1.0 - wr) * activityDensity01).coerceIn(0.0, 1.0)

        // A3：YIN 音高、浊音度、说话人相对音高、因果音节核与重音事件。
        val (f0Hz, cmndf) = if (sil) 0.0 to 1.0 else yinPitch(src, srcOff)
        val voiced01 = if (sil) 0.0 else
            (1.0 - smoothstep(0.10, YIN_VOICED_CMNDF, cmndf)) * audibility
        val pitch01 = (ln(max(f0Hz, YIN_MIN_HZ) / YIN_MIN_HZ) /
                ln(YIN_MAX_HZ / YIN_MIN_HZ)).coerceIn(0.0, 1.0)
        if (voiced01 > 0.55 && f0Hz > 0.0) {
            val logF0 = ln(f0Hz)
            pitchBaseLog = if (pitchBaseLog.isNaN()) logF0 else
                pitchBaseLog + (logF0 - pitchBaseLog) * (1.0 - exp(-1.0 / (frameRate * 15.0)))
        }
        val pitchRel01 = if (pitchBaseLog.isNaN() || f0Hz <= 0.0) 0.5 else
            (0.5 + (ln(f0Hz) - pitchBaseLog) / ln(2.0) * 0.5).coerceIn(0.0, 1.0)
        val pitchGain = 1.0 - exp(-1.0 / (frameRate * 0.24))
        pitchRelSm += ((if (voiced01 > 0.3) pitchRel01 else 0.5) - pitchRelSm) * pitchGain
        val dbK = 10.0 * log10(pTotal + 1e-12) - dbRef
        val recentLoud = rLoud.recent((3.0 * frameRate).toInt())
        val syllableMedian = if (rLoud.count >= 8) FableSolMath.percentile(recentLoud, 50.0) else dbK
        val syllableRateHz = if (sil) 0.0 else
            syllableStep(t, dbK, syllableMedian, voiced01, pitchRel01, events)

        // 节拍证据先经 speech/music 慢门；语音音节率不经过该门。
        beat.push(if (sil) 0.0 else onsetEnv, t)
        val (bpm, beatPh, beatConf) = beat.state(t)
        rFluxCv.push(flux)
        val fluctRatio = rEnvBand.mean() / (rEnvAc.mean() + 1e-9)
        val fluxCv = rFluxCv.std() / (rFluxCv.mean() + 1e-6)
        if (!sil) {
            val evidence = FableSolSpeed.tempoConfidence01(beatConf)
            val tau = if (evidence > music01) MUSIC_RISE_S else MUSIC_FALL_S
            music01 += (evidence - music01) * (1.0 - exp(-1.0 / (frameRate * tau)))
        }
        val musicGate = smoothstep(MUSIC_GATE_LO, MUSIC_GATE_HI, music01)
        val gatedBeatConf = beatConf * musicGate
        val fluct4hz01 = fluctRatio.coerceIn(0.0, 1.0) * audibility
        val slowRate = FableSolSpeed.effectiveEventRate(rawRate, salientRate, gatedBeatConf)
        val fastRate = FableSolSpeed.effectiveEventRate(fastRawRate, fastSalientRate, gatedBeatConf)
        var rate = FableSolSpeed.surfaceEventRate(fastRate, slowRate)
        rate = max(rate, syllableRateHz * (1.0 - musicGate))
        tempoSpeed01 = FableSolSpeed.tempoEvidenceStep(
            tempoSpeed01, FableSolSpeed.tempo01(bpm), gatedBeatConf, frameRate)
        val speedTarget = FableSolSpeed.fusePerceivedSpeed01(rate, tempoSpeed01, gatedBeatConf)
        speed01 = FableSolSpeed.smoothStep(speed01, speedTarget, frameRate)
        val flow01 = speed01
        // 打击性质感
        val percTarget = (onsetEnv * (0.75 + 0.25 * flat01)).coerceIn(0.0, 1.0)
        val percTau = if (percTarget > percEnv) 0.035 else 0.28
        percEnv += (percTarget - percEnv) * (1.0 - exp(-1.0 / (frameRate * percTau)))
        val percussive01 = percEnv
        // punch（近 2s onset 平均强度）
        var punchSum = 0.0; var punchCnt = 0
        for (o in onsetTimes) if (o[0] > t - 2.0) { punchSum += o[1]; punchCnt++ }
        val punch01 = if (punchCnt > 0) min(punchSum / punchCnt * 1.2, 1.0) else 0.0

        // A6：清澈度、激活度、渐强逼近与冲击性。
        val hnrTarget = (1.0 - cmndf) * voiced01
        hnrSm += (hnrTarget - hnrSm) * (1.0 - exp(-1.0 / (frameRate * 2.5)))
        val hnr01 = ((hnrSm - 0.15) / 0.65).coerceIn(0.0, 1.0)
        if (voiced01 > 0.45) rF0.push(pitch01)
        val f0History = rF0.recent((8.0 * frameRate).toInt())
        val f0Iqr01 = if (f0History.size >= 20) {
            val q = FableSolMath.percentiles(f0History, doubleArrayOf(25.0, 75.0))
            ((q[1] - q[0]) / 0.18).coerceIn(0.0, 1.0)
        } else 0.0
        val loudHistory = rLoud.recent((6.0 * frameRate).toInt())
        val loudDynamic01 = if (rLoud.count >= 30) {
            val q = FableSolMath.percentiles(loudHistory, doubleArrayOf(10.0, 90.0))
            ((q[1] - q[0]) / 18.0).coerceIn(0.0, 1.0)
        } else 0.0
        val syllable01 = (syllableRateHz / 5.0).coerceIn(0.0, 1.0)
        val arousalTarget = if (sil) 0.0 else
            (0.40 * loudDynamic01 + 0.30 * f0Iqr01 + 0.30 * syllable01).coerceIn(0.0, 1.0)
        arousalSm += (arousalTarget - arousalSm) * (1.0 - exp(-1.0 / (frameRate * 4.0)))
        val shortSlope = if (previousShortDb.isNaN()) 0.0 else (dbS - previousShortDb) * frameRate
        previousShortDb = dbS
        val loomTarget = if (sil) 0.0 else (shortSlope / 4.0).coerceIn(0.0, 1.0) * audibility
        val loomTau = if (loomTarget > loomSm) 0.5 else 1.1
        loomSm += (loomTarget - loomSm) * (1.0 - exp(-1.0 / (frameRate * loomTau)))
        val loom01 = loomSm.coerceIn(0.0, 1.0)
        rImpulseEnv.push(onsetEnv)
        val impulseHistory = rImpulseEnv.recent((1.2 * frameRate).toInt())
        var impulse01 = 0.0
        if (impulseHistory.size >= 24) {
            val mean = FableSolMath.mean(impulseHistory)
            var variance = 0.0
            for (value in impulseHistory) {
                val d = value - mean
                variance += d * d
            }
            val std = sqrt(variance / impulseHistory.size)
            if (std > 1e-6) {
                var fourth = 0.0
                for (value in impulseHistory) {
                    val z = (value - mean) / std
                    fourth += z * z * z * z
                }
                impulse01 = (fourth / impulseHistory.size - 3.0).div(9.0).coerceIn(0.0, 1.0)
            }
        }
        for (event in events) if (event is FableSolEvent.Onset) {
            event.impulse01 = impulse01
            event.loom01 = loom01
        }
        // 性格档取值用的快/慢 EMA
        val vec = doubleArrayOf(loud01, low01, mid01, high01, centroid01, flow01, flat01, punch01)
        var ef = emaFast; var es = emaSlow
        if (ef == null) { ef = vec.copyOf(); es = vec.copyOf(); emaFast = ef; emaSlow = es }
        val kf = 1.0 - exp(-1.0 / (frameRate * 2.5))
        val ks = 1.0 - exp(-1.0 / (frameRate * 10.0))
        for (i in vec.indices) { ef[i] += (vec[i] - ef[i]) * kf; es!![i] += (vec[i] - es[i]) * ks }
        // 段落边界（倒谱路径；学习期不发段落）
        val sec = novelty.push(logb, !sil, t)
        if (sec != null && centerAge > 6.0) {
            events.add(FableSolEvent.Section(sec.t, sec.magnitude01, ef[0], ef[4]))
        }
        frames.add(FableSolFeatureFrame(
            t = t,
            loudness01 = if (sil) 0.0 else loud01,
            bandLow = if (sil) 0.0 else low01,
            bandMid = if (sil) 0.0 else mid01,
            bandHigh = if (sil) 0.0 else high01,
            relLow = relLow, relMid = relMid, relHigh = relHigh,
            centroid01 = centroid01, spectralTilt01 = spectralTilt01,
            flatness01 = flat01, percussive01 = if (sil) 0.0 else percussive01,
            punch01 = if (sil) 0.0 else punch01,
            stereoWidth01 = stereoWidth01, pan01 = pan01,
            onsetEnv = onsetEnv, flow01 = flow01, activity01 = activity01,
            loudDb = db, floorDb = floorDb, isSilent = sil,
            tempoBpm = bpm, beatPhase01 = beatPh,
            beatConf01 = if (sil) 0.0 else gatedBeatConf,
            loudMDb = dbM, loudSDb = dbS,
            music01 = music01,
            fluct4hz01 = if (sil) 0.0 else fluct4hz01,
            fluxCv = fluxCv,
            f0Hz = f0Hz, pitch01 = pitch01, pitchRel01 = pitchRel01,
            voiced01 = voiced01, sylRateHz = syllableRateHz,
            hnr01 = hnr01, arousal01 = arousalSm,
            loom01 = loom01, impulse01 = impulse01
        ))
    }

    /** A3 YIN：4:1 抽取后的 ACF 近似差分与 CMNDF。 */
    private fun yinPitch(src: DoubleArray, srcOff: Int): Pair<Double, Double> {
        val n = N_FFT / YIN_DECIM
        val down = DoubleArray(n)
        var mean = 0.0
        for (i in 0 until n) {
            var sum = 0.0
            val base = srcOff + i * YIN_DECIM
            for (j in 0 until YIN_DECIM) sum += src[base + j]
            down[i] = sum / YIN_DECIM
            mean += down[i]
        }
        mean /= n
        var r0 = 0.0
        for (i in down.indices) {
            down[i] -= mean
            r0 += down[i] * down[i]
        }
        val sampleRate4 = sr.toDouble() / YIN_DECIM
        val tauMin = max((sampleRate4 / YIN_MAX_HZ).toInt(), 2)
        val tauMax = min((sampleRate4 / YIN_MIN_HZ).toInt(), n - 2)
        if (tauMax <= tauMin + 2 || r0 < 1e-10) return 0.0 to 1.0
        val count = tauMax - tauMin
        val cmndf = DoubleArray(count)
        var cumulative = 1e-12
        for (index in 0 until count) {
            val tau = tauMin + index
            var autocorrelation = 0.0
            for (i in 0 until n - tau) autocorrelation += down[i] * down[i + tau]
            val difference = 2.0 * (r0 - autocorrelation)
            cumulative += max(difference, 0.0)
            cmndf[index] = difference * (index + 1) / cumulative
        }
        var best = -1
        for (i in cmndf.indices) if (cmndf[i] < YIN_THRESHOLD) { best = i; break }
        if (best < 0) {
            best = 0
            for (i in 1 until cmndf.size) if (cmndf[i] < cmndf[best]) best = i
        }
        var offset = 0.0
        if (best > 0 && best + 1 < cmndf.size) {
            val denominator = cmndf[best - 1] - 2.0 * cmndf[best] + cmndf[best + 1]
            if (abs(denominator) > 1e-12) {
                offset = (0.5 * (cmndf[best - 1] - cmndf[best + 1]) / denominator)
                    .coerceIn(-1.0, 1.0)
            }
        }
        val tau = tauMin + best + offset
        return sampleRate4 / max(tau, 1e-6) to cmndf[best].coerceIn(0.0, 1.0)
    }

    /** 因果音节核；重音只在音节峰完成后产生。 */
    private fun syllableStep(t: Double, db: Double, median: Double, voiced01: Double,
                             pitchRel01: Double, events: ArrayList<FableSolEvent>): Double {
        if (!syllableAbove) {
            if (db > median + SYLLABLE_RISE_DB && voiced01 > 0.4) {
                syllableAbove = true
                syllablePeakDb = db
                syllablePeakT = t
            }
        } else {
            if (db > syllablePeakDb) {
                syllablePeakDb = db
                syllablePeakT = t
            }
            if (db < syllablePeakDb - SYLLABLE_DIP_DB) {
                syllableAbove = false
                if (syllableTimes.isEmpty() || syllablePeakT - syllableTimes.last() > SYLLABLE_MIN_GAP_S) {
                    syllableTimes.addLast(syllablePeakT)
                    val excursion = abs(pitchRel01 - 0.5) * 2.0
                    val strength = ((syllablePeakDb - median - 4.0) / 12.0)
                        .coerceIn(0.0, 1.0) * (0.62 + 0.38 * excursion)
                    if (strength >= PROMINENCE_MIN_STRENGTH &&
                        syllablePeakT - lastProminenceT > PROMINENCE_MIN_GAP_S) {
                        lastProminenceT = syllablePeakT
                        events.add(FableSolEvent.Prominence(syllablePeakT, strength, pitchRel01))
                    }
                }
            }
        }
        while (syllableTimes.isNotEmpty() && syllableTimes.first() < t - SYLLABLE_RATE_WINDOW_S) {
            syllableTimes.removeFirst()
        }
        return syllableTimes.size / SYLLABLE_RATE_WINDOW_S
    }

    private fun band01(power: Double, ring: FableSolRingStat, fkey: String,
                       confLoud: Boolean, trust: Double, k: Int, sil: Boolean): Double {
        val bdb = 10.0 * log10(power + 1e-12) - dbRef
        val c = trackCenter(fkey, bdb, confLoud)
        if (!sil) ring.push(bdb)
        val rk = ring.span01(bdb, k, 5.0, 95.0, MIN_SPAN_DB)
        val rel = (0.5 + (bdb - c) / ABS_SPAN_DB * trust).coerceIn(0.0, 1.0)
        val wb = 0.3 * min(ring.count.toDouble() / k, 1.0)
        return wb * rk + (1.0 - wb) * rel
    }

    private fun trackCenter(key: String, v: Double, conf: Boolean): Double {
        if (conf) {
            val c0 = centers[key]
            val c = if (c0 == null) v else {
                val tau = if (centerAge < 12.0) 6.0 else 300.0
                var kc = (1.0 / frameRate) / tau
                if (abs(v - c0) > 12.0) kc *= 8.0
                c0 + (v - c0) * kc
            }
            centers[key] = c
        }
        return centers[key] ?: v
    }

    /**
     * 采集会话启动保护：连续稳定静音或可信的非低频内容达到短窗口后立即开放；否则最多等待
     * 4.5 秒，并把仍存在的低频背景种成噪声底。这样不会把 MIC/HAL 启动暂态学习成“有效声音”，
     * 也不会让用户开口说话时固定等待完整超时。
     */
    private fun suppressCaptureStartup(t: Double, db: Double,
                                       pLow: Double, pMid: Double, pHigh: Double): Boolean {
        if (startupReady) return false
        if (startupFirstT.isNaN()) startupFirstT = t
        val total = max(pLow + pMid + pHigh, 1e-20)
        val lowShare = pLow / total
        val quiet = db <= STARTUP_QUIET_DB
        val trustedContent = db > STARTUP_QUIET_DB && lowShare <= STARTUP_MAX_LOW_SHARE
        if (quiet || trustedContent) {
            if (startupTrustedSince.isNaN()) startupTrustedSince = t
        } else {
            startupTrustedSince = Double.NaN
        }
        val stable = !startupTrustedSince.isNaN() &&
                t - startupTrustedSince >= STARTUP_TRUST_S
        val timedOut = t - startupFirstT >= STARTUP_MAX_S
        if (!stable && !timedOut) return true

        startupReady = true
        // 若是稳定静音，或超时后仍只有低频暂态，把当前电平作为环境底噪起点。
        if (quiet || (timedOut && !trustedContent)) floorDb = db
        silent = true
        belowSince = Double.NaN
        return false
    }

    private fun startupSilentFrame(t: Double, db: Double): FableSolFeatureFrame {
        return FableSolFeatureFrame(
            t = t,
            loudness01 = 0.0,
            bandLow = 0.0,
            bandMid = 0.0,
            bandHigh = 0.0,
            relLow = 1.0 / 3.0,
            relMid = 1.0 / 3.0,
            relHigh = 1.0 / 3.0,
            centroid01 = 0.5,
            spectralTilt01 = 0.5,
            flatness01 = 0.0,
            percussive01 = 0.0,
            punch01 = 0.0,
            stereoWidth01 = 0.0,
            pan01 = 0.5,
            onsetEnv = 0.0,
            flow01 = 0.0,
            activity01 = 0.0,
            loudDb = db,
            floorDb = floorDb,
            isSilent = true,
            tempoBpm = 0.0,
            beatPhase01 = 0.0,
            beatConf01 = 0.0
        )
    }

    private fun smoothstep(lo: Double, hi: Double, value: Double): Double {
        val q = ((value - lo) / max(hi - lo, 1e-6)).coerceIn(0.0, 1.0)
        return q * q * (3.0 - 2.0 * q)
    }

    private fun aWeightDb(f: Double): Double {
        val fm = max(f, 1.0)
        val f2 = fm * fm
        val ra = (12194.0 * 12194.0 * f2 * f2) /
                ((f2 + 20.6 * 20.6) * sqrt((f2 + 107.7 * 107.7) * (f2 + 737.9 * 737.9)) * (f2 + 12194.0 * 12194.0))
        return 20.0 * log10(ra) + 2.0
    }

    private fun concat(a: DoubleArray, b: DoubleArray): DoubleArray {
        val out = DoubleArray(a.size + b.size)
        System.arraycopy(a, 0, out, 0, a.size)
        System.arraycopy(b, 0, out, a.size, b.size)
        return out
    }

    companion object {
        const val N_FFT = 2048
        const val HOP = 512
        private const val ONSET_MIN_GAP_S = 0.09
        private const val FLOOR_RELAX_S = 20.0
        private const val MIN_SPAN_DB = 12.0
        private const val ABS_SPAN_DB = 24.0
        private const val AUDIBILITY_ZERO_DB = -66.0
        private const val AUDIBILITY_FULL_DB = -54.0
        private const val AUDIBILITY_SILENT_CUTOFF = 0.02
        private const val MOMENTARY_S = 0.4
        private const val SHORTTERM_S = 3.0
        private const val WHITEN_HALFLIFE_S = 3.0
        private const val WHITEN_FLOOR_DBFS = -72.0
        private const val ENV_HP_HZ = 0.4
        private const val FLUCT_BP_HZ = 4.0
        private const val FLUCT_BP_Q = 0.8
        private const val FLUCT_WIN_S = 2.0
        private const val FLUX_CV_WIN_S = 2.4
        private const val MUSIC_RISE_S = 4.0
        private const val MUSIC_FALL_S = 6.0
        private const val MUSIC_GATE_LO = 0.45
        private const val MUSIC_GATE_HI = 0.75
        private const val YIN_DECIM = 4
        private const val YIN_MIN_HZ = 60.0
        private const val YIN_MAX_HZ = 480.0
        private const val YIN_THRESHOLD = 0.14
        private const val YIN_VOICED_CMNDF = 0.30
        private const val SYLLABLE_RISE_DB = 2.0
        private const val SYLLABLE_DIP_DB = 4.0
        private const val SYLLABLE_MIN_GAP_S = 0.12
        private const val SYLLABLE_RATE_WINDOW_S = 2.5
        private const val PROMINENCE_MIN_STRENGTH = 0.45
        private const val PROMINENCE_MIN_GAP_S = 0.35
        private const val STARTUP_QUIET_DB = -58.0
        private const val STARTUP_MAX_LOW_SHARE = 0.55
        private const val STARTUP_TRUST_S = 0.30
        private const val STARTUP_MAX_S = 4.50
    }
}
