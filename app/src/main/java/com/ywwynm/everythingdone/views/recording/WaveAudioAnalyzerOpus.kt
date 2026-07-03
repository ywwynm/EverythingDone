package com.ywwynm.everythingdone.views.recording

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Opus 波浪可视化的音频分析器（全新构建，不复用 v2）。在录音线程消费单声道 PCM，产出
 * [WaveDriveFrameOpus] 语义驱动帧。设计目标：在手机单麦、可能有空调风噪/远场音乐的现实限制下，
 * 把能可靠拿到的特征"榨干"——响度动态、onset/节奏、粗略 bass/mid/treble 音色、亮度、近场音高。
 *
 * 配方见 docs/features/audio-visualizer-opus/research.md / decisions.md D7：
 * 预加重 → Hann/FFT2048 → 5 宏频段（各自 dB 自适应 floor/peak 归一）→ 逐 bin 自适应白化做
 * spectral flux/onset → 质心/平坦度 → 事件密度→pace → YIN pitch → 语义映射（含自适应对比与门控）。
 * 所有输出均带死区/门控/平滑，避免稳态噪声驱动视觉。
 */
class WaveAudioAnalyzerOpus(private val sampleRate: Int) {

    private val mRing = FloatArray(FFT_SIZE)
    private var mWriteIndex = 0
    private var mSampleCount = 0

    private val mWindow = FloatArray(FFT_SIZE) { i ->
        (0.5 - 0.5 * cos(2.0 * Math.PI * i / (FFT_SIZE - 1))).toFloat()
    }
    private val mReal = DoubleArray(FFT_SIZE)
    private val mImag = DoubleArray(FFT_SIZE)
    private val mMag = FloatArray(FFT_SIZE / 2)
    private val mWhitenPeak = FloatArray(FFT_SIZE / 2) { WHITEN_FLOOR }
    private val mPrevWhitened = FloatArray(FFT_SIZE / 2)

    private val mBandStartBin = IntArray(BAND_COUNT)
    private val mBandEndBin = IntArray(BAND_COUNT)
    private val mBandNorm = FloatArray(BAND_COUNT)        // 各频段自归一后的平滑值 0..1
    private val mBandFloorDb = FloatArray(BAND_COUNT) { BAND_START_DB }
    private val mBandPeakDb = FloatArray(BAND_COUNT) { BAND_START_DB + BAND_MIN_RANGE_DB }

    private var mFloorDb = ADAPTIVE_FLOOR_START_DB       // 整体响度自适应底
    private var mPeakDb = ADAPTIVE_FLOOR_START_DB + ADAPTIVE_MIN_RANGE_DB
    private var mSeeded = false                          // 是否已用开场实际环境电平锚定自适应底/峰

    private var mFluxMean = 0f
    private var mFluxDev = 0.05f
    private var mFrameCounter = 0
    private var mLastOnsetFrame = -100
    private val mOnsetHistory = FloatArray(EVENT_HISTORY)  // 最近若干帧是否有 onset（1/0）
    private var mOnsetHistoryIndex = 0

    // 语义输出的连续状态（attack/release 平滑）
    private var mLoudness = 0f
    private var mIntensity = 0f
    private var mPresence = 0f
    private var mPace = 0f
    private var mBrightness = 0f
    private var mBassW = 0f
    private var mMidW = 0f
    private var mTrebleW = 0f
    private var mSustain = 0f
    private var mWaterLevel = 0f
    private var mNoiseLike = 1f

    // YIN pitch（隔帧计算以省算力）
    private var mPitchHz = 0f
    private var mPitchConfidence = 0f
    private var mPitchNormalized = 0.5f
    private val mYinBuffer = FloatArray(YIN_MAX_TAU + 1)

    init {
        val nyquist = FFT_SIZE / 2
        for (b in 0 until BAND_COUNT) {
            val startHz = BAND_HZ[b * 2]
            val endHz = BAND_HZ[b * 2 + 1]
            mBandStartBin[b] = (startHz * FFT_SIZE / sampleRate).coerceIn(1, nyquist - 1)
            mBandEndBin[b] = (endHz * FFT_SIZE / sampleRate).coerceIn(mBandStartBin[b] + 1, nyquist)
        }
    }

    /** 单声道 PCM16（小端）写入环形缓冲。 */
    fun ingest(buf: ByteArray, byteReadSize: Int) {
        var i = 0
        val usable = byteReadSize - (byteReadSize % BYTES_PER_MONO_FRAME)
        while (i + BYTES_PER_MONO_FRAME <= usable) {
            val sample = readPcm16(buf, i) / PCM_16_MAX
            mRing[mWriteIndex] = sample.coerceIn(-1f, 1f)
            mWriteIndex = (mWriteIndex + 1) % FFT_SIZE
            if (mSampleCount < FFT_SIZE) mSampleCount++
            i += BYTES_PER_MONO_FRAME
        }
    }

    fun analyze(elapsedMs: Long): WaveDriveFrameOpus {
        if (mSampleCount <= 0) return WaveDriveFrameOpus.SILENCE
        val dt = (elapsedMs.coerceIn(MIN_FRAME_MS, MAX_FRAME_MS)).toFloat() / 1000f

        // ---- 组帧：预加重 + Hann 窗，同时算时域 RMS ----
        val available = mSampleCount.coerceAtMost(FFT_SIZE)
        val leadingZeros = FFT_SIZE - available
        val start = (mWriteIndex - available + FFT_SIZE) % FFT_SIZE
        var sumSq = 0.0
        var prevSample = 0f
        for (i in 0 until FFT_SIZE) {
            val raw = if (i < leadingZeros) 0f else mRing[(start + i - leadingZeros) % FFT_SIZE]
            if (i >= leadingZeros) sumSq += raw.toDouble() * raw.toDouble()
            // 一阶预加重补麦克风高频滚降
            val pre = raw - PRE_EMPHASIS * prevSample
            prevSample = raw
            mReal[i] = (pre * mWindow[i]).toDouble()
            mImag[i] = 0.0
        }
        val rms = sqrt(sumSq / available.coerceAtLeast(1)).toFloat()
        val dbFs = if (rms <= SILENCE_RMS) -96f else (20.0 * log10(rms.toDouble())).toFloat()

        fft(mReal, mImag)
        val half = FFT_SIZE / 2
        for (bin in 0 until half) {
            val re = mReal[bin]; val im = mImag[bin]
            mMag[bin] = (sqrt(re * re + im * im) / half).toFloat()
        }

        // ---- 逐 bin 自适应白化 → spectral flux（onset novelty）----
        var flux = 0f
        for (bin in 1 until half) {
            val m = mMag[bin]
            if (m > mWhitenPeak[bin]) mWhitenPeak[bin] = m
            else mWhitenPeak[bin] = max(WHITEN_FLOOR, mWhitenPeak[bin] * WHITEN_DECAY)
            val w = m / mWhitenPeak[bin]
            val d = w - mPrevWhitened[bin]
            if (d > 0f) flux += d
            mPrevWhitened[bin] = w
        }
        flux /= half.toFloat()

        // ---- 谱质心 / 平坦度 ----
        var magSum = 0.0; var freqWeighted = 0.0; var logSum = 0.0; var linSum = 0.0; var cnt = 0
        for (bin in 1 until half) {
            val m = mMag[bin].toDouble()
            val freq = bin.toDouble() * sampleRate / FFT_SIZE
            magSum += m
            freqWeighted += m * freq
            logSum += ln(m + 1e-9)
            linSum += m
            cnt++
        }
        val centroidHz = if (magSum > 1e-9) (freqWeighted / magSum) else 0.0
        val centroid = normalizeLogFreq(centroidHz.toFloat(), CENTROID_MIN_HZ, CENTROID_MAX_HZ)
        val flatness = if (linSum > 1e-9 && cnt > 0) {
            (exp(logSum / cnt) / (linSum / cnt)).toFloat().coerceIn(0f, 1f)
        } else 1f

        // ---- 5 宏频段：各自 dB 自适应 floor/peak 归一 ----
        for (b in 0 until BAND_COUNT) {
            var energy = 0.0; var bins = 0
            for (bin in mBandStartBin[b] until mBandEndBin[b]) {
                val m = mMag[bin]; energy += (m * m).toDouble(); bins++
            }
            val bandRms = sqrt(energy / bins.coerceAtLeast(1)).toFloat()
            val db = if (bandRms <= SILENCE_RMS) BAND_START_DB else (20.0 * log10(bandRms.toDouble())).toFloat()
            // 自适应 floor（快降慢升，吸收稳态背景）/ peak（快升慢降）
            mBandFloorDb[b] += (db - mBandFloorDb[b]) * (if (db < mBandFloorDb[b]) FLOOR_FALL else FLOOR_RISE)
            mBandPeakDb[b] += (db - mBandPeakDb[b]) * (if (db > mBandPeakDb[b]) PEAK_RISE else PEAK_FALL)
            if (mBandPeakDb[b] < mBandFloorDb[b] + BAND_MIN_RANGE_DB) mBandPeakDb[b] = mBandFloorDb[b] + BAND_MIN_RANGE_DB
            val norm = ((db - mBandFloorDb[b]) / (mBandPeakDb[b] - mBandFloorDb[b])).coerceIn(0f, 1f)
            val k = if (norm > mBandNorm[b]) BAND_ATTACK else BAND_RELEASE
            mBandNorm[b] += (norm - mBandNorm[b]) * k
        }

        // ---- 整体响度自适应对比 → relativeLevel ----
        val dbClamped = dbFs.coerceIn(ADAPTIVE_FLOOR_MIN_DB, ADAPTIVE_PEAK_MAX_DB)
        if (!mSeeded && rms > SILENCE_RMS) {
            // 用开场实际环境电平锚定底/峰，消除"打开后先涨后落再稳到另一状态"的初始收敛瞬变（问题3）
            mSeeded = true
            mFloorDb = dbClamped - ADAPTIVE_SEED_FLOOR_MARGIN
            mPeakDb = dbClamped + ADAPTIVE_MIN_RANGE_DB
        }
        mFloorDb += (dbClamped - mFloorDb) * (if (dbClamped < mFloorDb) FLOOR_FALL else FLOOR_RISE)
        mPeakDb += (dbClamped - mPeakDb) * (if (dbClamped > mPeakDb) PEAK_RISE else PEAK_FALL)
        if (mPeakDb < mFloorDb + ADAPTIVE_MIN_RANGE_DB) mPeakDb = mFloorDb + ADAPTIVE_MIN_RANGE_DB
        val relativeLevel = ((dbClamped - mFloorDb - ADAPTIVE_OFFSET_DB) / (mPeakDb - mFloorDb)).coerceIn(0f, 1f)
        val absoluteLevel = ((dbFs - VISUAL_MIN_DB) / (VISUAL_MAX_DB - VISUAL_MIN_DB)).coerceIn(0f, 1f)
        val fastLevel = fastLevel()

        // ---- onset：flux 自适应门限 + 最小间隔 ----
        val meanDelta = flux - mFluxMean
        mFluxMean += meanDelta * FLUX_MEAN_ALPHA
        mFluxDev += (abs(meanDelta) - mFluxDev) * FLUX_DEV_ALPHA
        val threshold = mFluxMean + mFluxDev * FLUX_DEV_BIAS
        // 稳态噪声门：谱平坦度高（噪声）时压制 onset
        val tonalGate = smoothStep(FLATNESS_TONAL_HI, FLATNESS_TONAL_LO, flatness)
        val onsetScore = (((flux - threshold) / (mFluxDev * FLUX_GAIN + FLUX_MIN_RANGE)).coerceIn(0f, 1f)) * tonalGate
        val minSpacing = max(1, (MIN_ONSET_SPACING_SEC / dt).toInt())
        val isOnset = onsetScore >= ONSET_TRIGGER &&
                mFrameCounter - mLastOnsetFrame >= minSpacing &&
                relativeLevel >= ONSET_LEVEL_GATE
        if (isOnset) mLastOnsetFrame = mFrameCounter
        pushOnset(if (isOnset) 1f else 0f)
        val eventDensity = eventDensity(dt)

        // ---- YIN pitch（隔帧）----
        if (mFrameCounter % PITCH_EVERY == 0) updatePitch(relativeLevel, flatness)

        mFrameCounter++

        // ---- 客观特征帧 ----
        val feature = WaveAudioFrameOpus(
            rms = rms, dbFs = dbFs, fastLevel = fastLevel, relativeLevel = relativeLevel,
            bass = mBandNorm[0], lowMid = mBandNorm[1], mid = mBandNorm[2],
            highMid = mBandNorm[3], treble = mBandNorm[4],
            centroid = centroid, flatness = flatness, flux = flux,
            onsetStrength = onsetScore, eventDensity = eventDensity,
            pitchHz = mPitchHz, pitchConfidence = mPitchConfidence, pitchNormalized = mPitchNormalized
        )

        return mapToDrive(feature, absoluteLevel, relativeLevel, fastLevel, onsetScore, isOnset,
            eventDensity, centroid, flatness, dt)
    }

    // ------------------------------------------------------------------
    // 特征 → 语义驱动映射（含自适应对比、门控、attack/release 平滑）
    // ------------------------------------------------------------------
    private fun mapToDrive(
        feature: WaveAudioFrameOpus,
        absoluteLevel: Float, relativeLevel: Float, fastLevel: Float,
        onsetScore: Float, isOnset: Boolean, eventDensity: Float,
        centroid: Float, flatness: Float, dt: Float
    ): WaveDriveFrameOpus {
        // presence：存在感主要由"相对电平（超出近期噪声底的部分）"驱动——稳态风噪已被自适应底吸收，
        // 音色/浊音只做温和加权（下限 0.6，绝不把有声压成零）。放开灵敏度：宁可略敏感也不要毫无反应。
        val tonal = smoothStep(FLATNESS_TONAL_HI, FLATNESS_TONAL_LO, flatness)
        val voiced = mPitchConfidence
        // 稳态宽频噪声（空调风噪等）：relativeLevel 已被自适应底吸收→≈0，但 absoluteLevel 无自适应底、会把持续
        // 底噪抬起来（这是"底噪比音乐还激烈"的元凶）。故 relativeLevel 主导、absoluteLevel 仅作辅助，且辅助力度
        // 随"音调性"缩放：越像纯噪声（tonal/voiced 越低）辅助越弱 → 稳态底噪几乎不激活，真实语音/音乐不受影响。
        val absAssist = ABS_ASSIST * (NOISE_FLOOR_ASSIST + (1f - NOISE_FLOOR_ASSIST) * max(tonal, voiced))
        val level = max(relativeLevel, absAssist * max(absoluteLevel, fastLevel))
        val gate = 0.6f + 0.4f * max(tonal, voiced)
        val presenceTarget = (level * gate).coerceIn(0f, 1f)
        mPresence += (presenceTarget - mPresence) *
                (if (presenceTarget > mPresence) approach(dt, PRESENCE_ATTACK) else approach(dt, PRESENCE_RELEASE))
        val quietness = (1f - smoothStep(QUIET_START, QUIET_FULL, mPresence)).coerceIn(0f, 1f)

        // loudness / intensity（不再乘 presence 压低——presence 已由相对电平驱动，避免双重压制）
        val loudTarget = max(relativeLevel, absAssist * absoluteLevel)
        mLoudness += (loudTarget - mLoudness) *
                (if (loudTarget > mLoudness) approach(dt, LOUD_ATTACK) else approach(dt, LOUD_RELEASE))
        // 声强对比：拉开正常/大声（S 曲线）
        val intensityTarget = contrast(0.72f * relativeLevel + absAssist * (0.18f * absoluteLevel + 0.1f * fastLevel))
        mIntensity += (intensityTarget - mIntensity) *
                (if (intensityTarget > mIntensity) approach(dt, LOUD_ATTACK) else approach(dt, LOUD_RELEASE))

        // pace：事件密度为主，绝对量避免锁 BPM
        val paceTarget = (eventDensity * 0.8f + smoothStep(0.1f, 0.6f, mPresence) * 0.2f) * mPresence
        mPace += (paceTarget - mPace) *
                (if (paceTarget > mPace) approach(dt, PACE_ATTACK) else approach(dt, PACE_RELEASE))

        // brightness：质心
        val brightTarget = centroid * mPresence
        mBrightness += (brightTarget - mBrightness) * approach(dt, BRIGHT_TAU)

        // 频段权重（band ratios）：归一化分布，抗整体音量差异
        val bassE = feature.bass + feature.lowMid * 0.6f
        val midE = feature.lowMid * 0.4f + feature.mid + feature.highMid * 0.4f
        val trebE = feature.highMid * 0.6f + feature.treble
        val sum = bassE + midE + trebE + 1e-4f
        mBassW += (bassE / sum - mBassW) * approach(dt, BAND_W_TAU)
        mMidW += (midE / sum - mMidW) * approach(dt, BAND_W_TAU)
        mTrebleW += (trebE / sum - mTrebleW) * approach(dt, BAND_W_TAU)

        // sustain：持续有声驱动（说话/音乐），非瞬态。更慷慨，保证连续说话时持续有新浪涌出。
        val sustainTarget = (0.7f * mPresence + 0.5f * mLoudness).coerceAtMost(1f) * (1f - 0.3f * quietness)
        mSustain += (sustainTarget - mSustain) *
                (if (sustainTarget > mSustain) approach(dt, SUSTAIN_ATTACK) else approach(dt, SUSTAIN_RELEASE))

        // 水位：慢潮（攻快释慢）
        val waterTarget = (WATER_REST + (WATER_MAX - WATER_REST) *
                contrast(0.6f * mLoudness + 0.4f * mIntensity)).coerceIn(0f, 1f)
        mWaterLevel += (waterTarget - mWaterLevel) *
                (if (waterTarget > mWaterLevel) approach(dt, WATER_ATTACK) else approach(dt, WATER_RELEASE))

        mNoiseLike += (flatness - mNoiseLike) * approach(dt, NOISE_TAU)

        // 主导波长：音高可信时映射（0=长波长/低音，1=短波长/高音）
        val pitchWavelength = if (mPitchConfidence > PITCH_MIN_CONFIDENCE) mPitchNormalized else 0.5f

        // onset 输出：事件触发给全强度，否则给连续弱量并被 presence 门控
        val onsetOut = if (isOnset) max(onsetScore, 0.5f) else onsetScore * ONSET_CONTINUOUS_SCALE * mPresence

        return WaveDriveFrameOpus(
            loudness = mLoudness.coerceIn(0f, 1f),
            intensity = mIntensity.coerceIn(0f, 1f),
            quietness = quietness,
            pace = mPace.coerceIn(0f, 1f),
            brightness = mBrightness.coerceIn(0f, 1f),
            bassWeight = mBassW.coerceIn(0f, 1f),
            midWeight = mMidW.coerceIn(0f, 1f),
            trebleWeight = mTrebleW.coerceIn(0f, 1f),
            onset = onsetOut.coerceIn(0f, 1f),
            sustainDrive = mSustain.coerceIn(0f, 1f),
            pitchWavelength = pitchWavelength,
            pitchConfidence = mPitchConfidence,
            waterLevel = mWaterLevel.coerceIn(0f, 1f),
            noiseLike = mNoiseLike.coerceIn(0f, 1f),
            feature = feature
        )
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------
    private fun fastLevel(): Float {
        val n = mSampleCount.coerceAtMost(FAST_WINDOW)
        if (n <= 0) return 0f
        val s = (mWriteIndex - n + FFT_SIZE) % FFT_SIZE
        var sumSq = 0.0
        for (i in 0 until n) {
            val v = mRing[(s + i) % FFT_SIZE]; sumSq += v.toDouble() * v.toDouble()
        }
        val rms = sqrt(sumSq / n)
        val db = if (rms <= SILENCE_RMS) -96.0 else 20.0 * log10(rms)
        return (((db - VISUAL_MIN_DB) / (VISUAL_MAX_DB - VISUAL_MIN_DB)).toFloat()).coerceIn(0f, 1f)
    }

    private fun pushOnset(v: Float) {
        mOnsetHistory[mOnsetHistoryIndex] = v
        mOnsetHistoryIndex = (mOnsetHistoryIndex + 1) % EVENT_HISTORY
    }

    private fun eventDensity(dt: Float): Float {
        var sum = 0f
        for (v in mOnsetHistory) sum += v
        // 每秒事件数 → 0..1（EVENT_DENSITY_FULL 事件/秒 = 满）
        val windowSec = EVENT_HISTORY * dt
        val perSec = if (windowSec > 1e-3f) sum / windowSec else 0f
        return (perSec / EVENT_DENSITY_FULL).coerceIn(0f, 1f)
    }

    private fun updatePitch(level: Float, flatness: Float) {
        if (level < PITCH_LEVEL_GATE || flatness > PITCH_FLATNESS_GATE) {
            mPitchConfidence *= PITCH_CONF_DECAY
            if (mPitchConfidence < 0.02f) { mPitchConfidence = 0f; mPitchHz = 0f }
            return
        }
        val available = mSampleCount.coerceAtMost(FFT_SIZE)
        if (available < YIN_MAX_TAU * 2) return
        val start = (mWriteIndex - available + FFT_SIZE) % FFT_SIZE
        val w = available
        // 差分函数
        mYinBuffer[0] = 1f
        var runningSum = 0f
        var bestTau = -1
        for (tau in YIN_MIN_TAU..YIN_MAX_TAU) {
            var d = 0f
            var j = 0
            val lim = w - tau
            while (j < lim) {
                val a = mRing[(start + j) % FFT_SIZE]
                val b = mRing[(start + j + tau) % FFT_SIZE]
                val diff = a - b
                d += diff * diff
                j++
            }
            runningSum += d
            mYinBuffer[tau] = if (runningSum > 1e-9f) d * tau / runningSum else 1f
            if (mYinBuffer[tau] < YIN_THRESHOLD &&
                (tau == YIN_MIN_TAU || mYinBuffer[tau] <= mYinBuffer[tau - 1])) {
                // 继续到局部极小
                if (tau + 1 <= YIN_MAX_TAU) {
                    // 简化：找到首个低于阈值即接受
                }
                bestTau = tau
                break
            }
        }
        if (bestTau < 0) {
            // 取全局最小作为兜底（低置信）
            var minVal = Float.MAX_VALUE; var minTau = -1
            for (tau in YIN_MIN_TAU..YIN_MAX_TAU) {
                if (mYinBuffer[tau] < minVal) { minVal = mYinBuffer[tau]; minTau = tau }
            }
            if (minTau < 0 || minVal > YIN_FALLBACK_MAX) { mPitchConfidence *= PITCH_CONF_DECAY; return }
            bestTau = minTau
        }
        // 抛物线插值精化
        val betterTau = parabolicTau(bestTau)
        val hz = sampleRate / betterTau
        val conf = (1f - mYinBuffer[bestTau]).coerceIn(0f, 1f)
        mPitchHz = hz
        mPitchConfidence = conf
        mPitchNormalized = normalizeLogFreq(hz, PITCH_MIN_HZ, PITCH_MAX_HZ)
    }

    private fun parabolicTau(tau: Int): Float {
        if (tau <= YIN_MIN_TAU || tau >= YIN_MAX_TAU) return tau.toFloat()
        val s0 = mYinBuffer[tau - 1]; val s1 = mYinBuffer[tau]; val s2 = mYinBuffer[tau + 1]
        val denom = 2f * (2f * s1 - s0 - s2)
        if (abs(denom) < 1e-9f) return tau.toFloat()
        return tau + (s2 - s0) / denom
    }

    private fun normalizeLogFreq(hz: Float, minHz: Float, maxHz: Float): Float {
        if (hz <= 0f) return 0f
        val v = (ln(hz.coerceIn(minHz, maxHz)) - ln(minHz)) / (ln(maxHz) - ln(minHz))
        return v.coerceIn(0f, 1f)
    }

    /** S 曲线拉开对比（正常 vs 大声）。 */
    private fun contrast(v: Float): Float {
        val x = v.coerceIn(0f, 1f)
        return (x * x * (3f - 2f * x)).let { smoothed ->
            // 再叠一点幂次强化高段
            (0.5f * smoothed + 0.5f * x * x).coerceIn(0f, 1f)
        }
    }

    private fun approach(dt: Float, tau: Float): Float = 1f - exp(-dt / tau)

    private fun smoothStep(start: Float, end: Float, v: Float): Float {
        if (end == start) return if (v >= end) 1f else 0f
        val t = ((v - start) / (end - start)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun readPcm16(buf: ByteArray, index: Int): Int {
        return ((buf[index].toInt() and 0xff) or (buf[index + 1].toInt() shl 8)).toShort().toInt()
    }

    private fun fft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = real[i]; real[i] = real[j]; real[j] = t
                t = imag[i]; imag[i] = imag[j]; imag[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2.0 * Math.PI / len
            val wr = cos(angle); val wi = sin(angle)
            var i = 0
            while (i < n) {
                var curR = 1.0; var curI = 0.0
                val halfLen = len / 2
                for (k in 0 until halfLen) {
                    val even = i + k; val odd = even + halfLen
                    val orr = real[odd] * curR - imag[odd] * curI
                    val ori = real[odd] * curI + imag[odd] * curR
                    real[odd] = real[even] - orr; imag[odd] = imag[even] - ori
                    real[even] += orr; imag[even] += ori
                    val nr = curR * wr - curI * wi
                    curI = curR * wi + curI * wr; curR = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    companion object {
        private const val FFT_SIZE = 2048
        private const val FAST_WINDOW = 512
        private const val BAND_COUNT = 5
        private const val BYTES_PER_MONO_FRAME = 2
        private const val PCM_16_MAX = 32768f
        private const val SILENCE_RMS = 1.0e-7f
        private const val PRE_EMPHASIS = 0.97f

        private const val MIN_FRAME_MS = 8L
        private const val MAX_FRAME_MS = 80L

        // bass / lowMid / mid / highMid / treble（treble 上限保守 11k，避免麦克风噪声主导）
        private val BAND_HZ = intArrayOf(40, 160, 160, 500, 500, 1500, 1500, 4000, 4000, 11000)

        private const val BAND_START_DB = -80f
        private const val BAND_MIN_RANGE_DB = 26f
        private const val BAND_ATTACK = 0.5f
        private const val BAND_RELEASE = 0.16f

        private const val FLOOR_FALL = 0.30f
        private const val FLOOR_RISE = 0.02f
        private const val PEAK_RISE = 0.35f
        private const val PEAK_FALL = 0.03f

        // 去 AGC 后麦克风电平明显偏低，窗口整体下移；peak 起点贴近 floor 让相对电平即时敏感。
        private const val ADAPTIVE_FLOOR_START_DB = -70f
        private const val ADAPTIVE_FLOOR_MIN_DB = -92f
        private const val ADAPTIVE_PEAK_MAX_DB = -4f
        private const val ADAPTIVE_MIN_RANGE_DB = 12f
        private const val ADAPTIVE_OFFSET_DB = 2f
        private const val VISUAL_MIN_DB = -72f
        private const val VISUAL_MAX_DB = -22f
        // absoluteLevel（无自适应底、绝对 dB 归一）仅作辅助，relativeLevel（有自适应底、吸收稳态底噪）主导：
        private const val ABS_ASSIST = 0.5f             // absoluteLevel 辅助权重上限
        private const val NOISE_FLOOR_ASSIST = 0.28f     // 纯噪声时辅助的最低占比（越小越压制稳态空调底噪）

        private const val WHITEN_FLOOR = 1.0e-5f
        private const val WHITEN_DECAY = 0.9970f

        private const val CENTROID_MIN_HZ = 120f
        private const val CENTROID_MAX_HZ = 6000f
        private const val FLATNESS_TONAL_HI = 0.55f  // 高于此更像噪声
        private const val FLATNESS_TONAL_LO = 0.20f  // 低于此更像乐音/语音

        private const val FLUX_MEAN_ALPHA = 0.04f
        private const val FLUX_DEV_ALPHA = 0.06f
        private const val FLUX_DEV_BIAS = 0.30f
        private const val FLUX_GAIN = 2.2f
        private const val FLUX_MIN_RANGE = 0.004f
        private const val ONSET_TRIGGER = 0.40f
        private const val ONSET_LEVEL_GATE = 0.06f
        private const val ONSET_CONTINUOUS_SCALE = 0.22f
        private const val MIN_ONSET_SPACING_SEC = 0.05f

        private const val EVENT_HISTORY = 96
        private const val EVENT_DENSITY_FULL = 7f   // 7 事件/秒 ≈ 满速度感

        // YIN
        private const val PITCH_EVERY = 3
        private const val PITCH_MIN_HZ = 75f
        private const val PITCH_MAX_HZ = 600f
        private const val YIN_MIN_TAU = 73    // 44100/600
        private const val YIN_MAX_TAU = 588   // 44100/75
        private const val YIN_THRESHOLD = 0.16f
        private const val YIN_FALLBACK_MAX = 0.55f
        private const val PITCH_LEVEL_GATE = 0.08f
        private const val PITCH_FLATNESS_GATE = 0.55f
        private const val PITCH_CONF_DECAY = 0.82f
        private const val PITCH_MIN_CONFIDENCE = 0.30f

        // 语义平滑时间常数（秒）
        private const val PRESENCE_ATTACK = 0.06f
        private const val PRESENCE_RELEASE = 0.28f
        private const val LOUD_ATTACK = 0.05f
        private const val LOUD_RELEASE = 0.30f
        private const val PACE_ATTACK = 0.10f
        private const val PACE_RELEASE = 0.55f
        private const val BRIGHT_TAU = 0.22f
        private const val BAND_W_TAU = 0.20f
        private const val SUSTAIN_ATTACK = 0.12f
        private const val SUSTAIN_RELEASE = 0.22f   // 更快释放：说完词后尽快停止持续生成（问题1）
        private const val ADAPTIVE_SEED_FLOOR_MARGIN = 3f
        private const val WATER_ATTACK = 0.32f
        private const val WATER_RELEASE = 0.64f
        private const val NOISE_TAU = 0.30f
        private const val QUIET_START = 0.03f
        private const val QUIET_FULL = 0.13f
        private const val WATER_REST = 0.0f
        private const val WATER_MAX = 1.0f
    }
}
