package com.ywwynm.everythingdone.views.recording

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Fable 海浪可视化的音频特征分析器，运行在 [AudioRecorder] 的采集线程内。
 *
 * 提取四类特征：
 * - 响度：最近 ~23ms 的 RMS→dBFS，[-52,-14]dB 映射 0..1；
 * - 瞬态：最近 ~6ms 快 RMS 的上升沿；
 * - 基频：半采样（÷2）后的 YIN（CMND + 绝对阈值 + 抛物线细化），隔帧计算，
 *   搜索 60–500Hz；
 * - 语速：dB 包络上的音节核计数（峰 > 滑动中位数 +2dB、峰谷差 ≥4dB、需浊音），
 *   2.5s 窗折算音节/秒。
 *
 * 所有缓冲预分配；analyze 除返回的帧对象外无分配。
 */
class OceanWaveAudioAnalyzerFable(sampleRate: Int) {

    private val mDecimRate: Int = sampleRate / 2

    // YIN 搜索范围由半采样率导出：60–500Hz。
    private val mTauMin: Int = max(2, mDecimRate / PITCH_MAX_HZ)
    private val mTauMax: Int = mDecimRate / PITCH_MIN_HZ

    // 单声道环形缓冲（原采样率），供响度/瞬态。
    private val mMonoRing = FloatArray(MONO_RING_SIZE)
    private var mMonoWrite = 0
    private var mMonoCount = 0

    // 半采样环形缓冲，供 YIN。
    private val mYinRing = FloatArray(YIN_RING_SIZE)
    private var mYinWrite = 0
    private var mYinCount = 0
    private var mDecimCarry = 0f
    private var mDecimHasCarry = false

    // YIN 工作区（帧快照 + 差函数）。
    private val mYinFrame = FloatArray(YIN_WINDOW + mTauMax)
    private val mYinDiff = FloatArray(mTauMax + 1)
    private val mPitchHistory = FloatArray(3)
    private var mPitchHistoryIdx = 0
    private var mAnalyzeCounter = 0
    private var mLastPitch = 0f
    private var mLastConfidence = 0f

    // 瞬态。
    private var mPrevFastLoud = 0f

    // 音节率：dB 包络环 + 峰候选状态机 + 音节时间戳环。
    private val mEnvRing = FloatArray(ENV_RING_SIZE)
    private val mEnvSorted = FloatArray(ENV_RING_SIZE)
    private var mEnvWrite = 0
    private var mEnvCount = 0
    private var mEnvSmooth = SILENCE_DB
    private var mCandidatePeakDb = NO_CANDIDATE_DB
    private var mCandidateVoiced = false
    private val mSyllableTimesMs = LongArray(SYLLABLE_RING_SIZE)
    private var mSyllableWrite = 0
    private var mLastSyllableMs = -100000L
    private var mNowMs = 0L
    private var mRateSmooth = 0f

    /** 立体声 16-bit PCM 进料；与现有分析器共用同一读缓冲。 */
    fun ingest(buf: ByteArray, byteReadSize: Int) {
        var i = 0
        val usable = byteReadSize - (byteReadSize % BYTES_PER_STEREO_FRAME)
        while (i + BYTES_PER_STEREO_FRAME <= usable) {
            val left = readPcm16(buf, i)
            val right = readPcm16(buf, i + BYTES_PER_SAMPLE)
            var mono = ((left + right) * 0.5f) / PCM_16_MAX
            if (mono < -1f) mono = -1f else if (mono > 1f) mono = 1f

            mMonoRing[mMonoWrite] = mono
            mMonoWrite = (mMonoWrite + 1) % MONO_RING_SIZE
            if (mMonoCount < MONO_RING_SIZE) mMonoCount++

            // ÷2 抽取：相邻两点取均值，粗略低通足以支撑 ≤500Hz 的基频检测。
            if (mDecimHasCarry) {
                mYinRing[mYinWrite] = (mDecimCarry + mono) * 0.5f
                mYinWrite = (mYinWrite + 1) % YIN_RING_SIZE
                if (mYinCount < YIN_RING_SIZE) mYinCount++
                mDecimHasCarry = false
            } else {
                mDecimCarry = mono
                mDecimHasCarry = true
            }
            i += BYTES_PER_STEREO_FRAME
        }
    }

    fun analyze(elapsedMs: Long): OceanWaveAudioFrameFable {
        if (mMonoCount <= 0) {
            return OceanWaveAudioFrameFable.SILENCE
        }
        mNowMs += elapsedMs
        mAnalyzeCounter++

        val rms = recentRms(LOUDNESS_WINDOW)
        val db = toDbfs(rms)
        val loudness = clamp01((db - LOUD_MIN_DB) / (LOUD_MAX_DB - LOUD_MIN_DB))

        val fastLoud = clamp01((toDbfs(recentRms(FAST_WINDOW)) - LOUD_MIN_DB) / (LOUD_MAX_DB - LOUD_MIN_DB))
        val transient = clamp01((fastLoud - mPrevFastLoud) * TRANSIENT_GAIN)
        mPrevFastLoud = fastLoud

        // 基频隔帧计算（~40ms 一次足够），其余帧沿用上次结果。
        if (mAnalyzeCounter and 1 == 0) {
            detectPitch()
        }
        val pitch = medianPitch()
        val voiced = pitch > 0f && mLastConfidence > VOICED_CONFIDENCE && loudness > VOICED_LOUDNESS

        val rate = updateSyllableRate(db, voiced, elapsedMs)

        return OceanWaveAudioFrameFable(
            loudness = loudness,
            transient = transient,
            pitchHz = pitch,
            pitchConfidence = if (pitch > 0f) mLastConfidence else 0f,
            voiced = voiced,
            syllableRate = rate
        )
    }

    // ---------------------------------------------------------------- 响度

    private fun recentRms(windowSize: Int): Float {
        val available = min(mMonoCount, windowSize)
        if (available <= 0) return 0f
        val start = (mMonoWrite - available + MONO_RING_SIZE) % MONO_RING_SIZE
        var sumSq = 0.0
        for (i in 0 until available) {
            val s = mMonoRing[(start + i) % MONO_RING_SIZE]
            sumSq += s.toDouble() * s.toDouble()
        }
        return sqrt(sumSq / available).toFloat()
    }

    private fun toDbfs(rms: Float): Float {
        if (rms <= 1.0e-6f) return SILENCE_DB
        return (20.0 * log10(rms.toDouble())).toFloat()
    }

    // ---------------------------------------------------------------- 基频

    private fun detectPitch() {
        val needed = YIN_WINDOW + mTauMax
        if (mYinCount < needed) {
            mLastPitch = 0f
            mLastConfidence = 0f
            pushPitch(0f)
            return
        }
        val start = (mYinWrite - needed + YIN_RING_SIZE) % YIN_RING_SIZE
        for (i in 0 until needed) {
            mYinFrame[i] = mYinRing[(start + i) % YIN_RING_SIZE]
        }

        // 差函数 d(tau) 与累积均值归一化 d'(tau)。
        mYinDiff[0] = 1f
        var runningSum = 0f
        var bestTau = -1
        var bestValue = Float.MAX_VALUE
        var thresholdTau = -1
        for (tau in 1..mTauMax) {
            var d = 0f
            for (i in 0 until YIN_WINDOW) {
                val diff = mYinFrame[i] - mYinFrame[i + tau]
                d += diff * diff
            }
            runningSum += d
            val cmnd = if (runningSum <= 0f) 1f else d * tau / runningSum
            mYinDiff[tau] = cmnd
            if (tau >= mTauMin) {
                if (cmnd < bestValue) {
                    bestValue = cmnd
                    bestTau = tau
                }
                // 绝对阈值：取第一次下穿阈值后的局部极小。
                if (thresholdTau < 0 && cmnd < YIN_THRESHOLD) {
                    thresholdTau = tau
                }
                if (thresholdTau in 1 until tau && mYinDiff[tau] > mYinDiff[tau - 1]) {
                    bestTau = tau - 1
                    bestValue = mYinDiff[tau - 1]
                    break
                }
            }
        }
        if (bestTau <= 0 || bestValue > YIN_REJECT) {
            mLastPitch = 0f
            mLastConfidence = 0f
            pushPitch(0f)
            return
        }

        // 抛物线细化。
        var refined = bestTau.toFloat()
        if (bestTau in 2 until mTauMax) {
            val a = mYinDiff[bestTau - 1]
            val b = mYinDiff[bestTau]
            val c = mYinDiff[bestTau + 1]
            val denom = a + c - 2f * b
            if (denom > 1.0e-9f) {
                refined += 0.5f * (a - c) / denom
            }
        }
        val pitch = mDecimRate / refined
        if (pitch < PITCH_MIN_HZ.toFloat() || pitch > PITCH_MAX_HZ.toFloat()) {
            mLastPitch = 0f
            mLastConfidence = 0f
            pushPitch(0f)
            return
        }
        mLastPitch = pitch
        mLastConfidence = clamp01(1f - bestValue)
        pushPitch(pitch)
    }

    private fun pushPitch(pitch: Float) {
        mPitchHistory[mPitchHistoryIdx] = pitch
        mPitchHistoryIdx = (mPitchHistoryIdx + 1) % mPitchHistory.size
    }

    /** 3 点中值去抖：至少两帧检出才输出，孤立跳变被滤掉。 */
    private fun medianPitch(): Float {
        val a = mPitchHistory[0]
        val b = mPitchHistory[1]
        val c = mPitchHistory[2]
        var zeros = 0
        if (a <= 0f) zeros++
        if (b <= 0f) zeros++
        if (c <= 0f) zeros++
        if (zeros >= 2) return 0f
        val maxV = max(a, max(b, c))
        val minV = min(a, min(b, c))
        val median = a + b + c - maxV - minV
        return if (median > 0f) median else maxV
    }

    // ---------------------------------------------------------------- 语速

    private fun updateSyllableRate(db: Float, voiced: Boolean, elapsedMs: Long): Float {
        mEnvSmooth += (db - mEnvSmooth) * ENV_ALPHA
        mEnvRing[mEnvWrite] = mEnvSmooth
        mEnvWrite = (mEnvWrite + 1) % ENV_RING_SIZE
        if (mEnvCount < ENV_RING_SIZE) mEnvCount++

        val median = envMedian()

        if (mEnvSmooth > mCandidatePeakDb) {
            mCandidatePeakDb = mEnvSmooth
            if (voiced) mCandidateVoiced = true
        } else if (mCandidatePeakDb - mEnvSmooth >= SYLLABLE_VALLEY_DB) {
            // 从候选峰跌落 ≥4dB：若峰合格则记一个音节核，然后重置候选。
            if (mCandidatePeakDb >= median + SYLLABLE_PEAK_ABOVE_MEDIAN_DB &&
                mCandidateVoiced &&
                mNowMs - mLastSyllableMs >= SYLLABLE_MIN_SPACING_MS
            ) {
                mSyllableTimesMs[mSyllableWrite] = mNowMs
                mSyllableWrite = (mSyllableWrite + 1) % SYLLABLE_RING_SIZE
                mLastSyllableMs = mNowMs
            }
            mCandidatePeakDb = mEnvSmooth
            mCandidateVoiced = voiced
        }

        var count = 0
        for (t in mSyllableTimesMs) {
            if (t > 0 && mNowMs - t <= RATE_WINDOW_MS) count++
        }
        val instRate = count * 1000f / RATE_WINDOW_MS
        val alpha = 1f - exp(-(elapsedMs.coerceIn(1L, 200L) / 1000f) / RATE_SMOOTH_TAU)
        mRateSmooth += (instRate - mRateSmooth) * alpha
        return mRateSmooth
    }

    private fun envMedian(): Float {
        if (mEnvCount <= 0) return SILENCE_DB
        System.arraycopy(mEnvRing, 0, mEnvSorted, 0, mEnvCount)
        java.util.Arrays.sort(mEnvSorted, 0, mEnvCount)
        return mEnvSorted[mEnvCount / 2]
    }

    // ---------------------------------------------------------------- 工具

    private fun readPcm16(buf: ByteArray, index: Int): Int {
        return ((buf[index].toInt() and 0xff) or (buf[index + 1].toInt() shl 8)).toShort().toInt()
    }

    private fun clamp01(v: Float): Float {
        if (v < 0f) return 0f
        if (v > 1f) return 1f
        return v
    }

    companion object {
        private const val BYTES_PER_SAMPLE = 2
        private const val BYTES_PER_STEREO_FRAME = 4
        private const val PCM_16_MAX = 32768f

        private const val MONO_RING_SIZE = 4096
        private const val YIN_RING_SIZE = 2048
        private const val YIN_WINDOW = 1024
        private const val PITCH_MIN_HZ = 60
        private const val PITCH_MAX_HZ = 500
        private const val YIN_THRESHOLD = 0.14f
        private const val YIN_REJECT = 0.55f

        private const val LOUDNESS_WINDOW = 1024
        private const val FAST_WINDOW = 256
        private const val LOUD_MIN_DB = -52f
        private const val LOUD_MAX_DB = -14f
        private const val SILENCE_DB = -100f
        private const val TRANSIENT_GAIN = 3f

        private const val VOICED_CONFIDENCE = 0.5f
        private const val VOICED_LOUDNESS = 0.08f

        private const val ENV_RING_SIZE = 128
        private const val ENV_ALPHA = 0.45f
        private const val NO_CANDIDATE_DB = -160f
        private const val SYLLABLE_VALLEY_DB = 4f
        private const val SYLLABLE_PEAK_ABOVE_MEDIAN_DB = 2f
        private const val SYLLABLE_MIN_SPACING_MS = 120L
        private const val SYLLABLE_RING_SIZE = 16
        private const val RATE_WINDOW_MS = 2500L
        private const val RATE_SMOOTH_TAU = 0.8f
    }
}
