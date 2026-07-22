package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 因果节拍跟踪（对应 features.py 的 _BeatTracker，BTrack/PLP 思路）。onset 包络自相关 → 梳状泛音
 * 叠加 × log-normal tempo 先验 → 抛物线精化 → 换挡迟滞；相位 = 近 8 拍衰减加权脉冲串对齐 + 锁相环
 * 纠偏。输出 (bpm, phase01, conf01)。
 */
class FableSolBeatTracker(private val frameRate: Double) {

    private val nWin = (WIN_S * frameRate).toInt()
    private val lagMin = (60.0 / 180.0 * frameRate).toInt()
    private val lagMax = (60.0 / 55.0 * frameRate).toInt()
    private val env = DoubleArray(nWin)
    private val lags = IntArray(lagMax - lagMin + 1) { lagMin + it }
    private val bpms = DoubleArray(lags.size) { 60.0 * frameRate / lags[it] }
    private val prior = DoubleArray(lags.size) { exp(-0.5 * (log2(bpms[it] / 118.0) / 0.9).pow(2)) }
    private val threeSec = (3.0 * frameRate).toInt()

    private var n = 0
    @JvmField var bpm = 0.0
    @JvmField var conf = 0.0
    private var tBeat = Double.NaN
    private var candBpm = 0.0
    private var candCnt = 0
    private val hist = ArrayDeque<Double>()

    fun reset() {
        java.util.Arrays.fill(env, 0.0)
        n = 0; bpm = 0.0; conf = 0.0; tBeat = Double.NaN
        candBpm = 0.0; candCnt = 0; hist.clear()
    }

    fun push(e: Double, t: Double) {
        System.arraycopy(env, 1, env, 0, nWin - 1)
        env[nWin - 1] = e
        n += 1
        if (n % STEP == 0 && n >= threeSec) update(t)
    }

    private fun update(t: Double) {
        // 活跃度不足：置信度衰减，拍链不动
        var s3 = 0.0
        for (i in nWin - threeSec until nWin) s3 += env[i]
        if (s3 < 1.0) { conf *= 0.8; return }
        var mean = 0.0
        for (x in env) mean += x
        mean /= nWin
        val lagHi = min(3 * lagMax, nWin / 2)
        val acLen = lagHi - lagMin + 1
        val ac = DoubleArray(acLen)
        for (li in 0 until acLen) {
            val lg = lagMin + li
            var s = 0.0
            for (i in 0 until nWin - lg) s += (env[i + lg] - mean) * (env[i] - mean)
            ac[li] = max(s, 0.0)
        }
        val scoreRaw = DoubleArray(lags.size)
        for (c in lags.indices) {
            val comb = atValue(lags[c], ac, lagHi) +
                    0.5 * atValue(lags[c] * 2, ac, lagHi) +
                    0.33 * atValue(lags[c] * 3, ac, lagHi)
            scoreRaw[c] = comb * prior[c]
        }
        val score = DoubleArray(lags.size)
        if (bpm > 0.0 && conf > 0.2) {
            val wIn = 0.45 * min(conf, 1.0)
            for (c in lags.indices) {
                val cont = exp(-0.5 * (log2(bpms[c] / bpm) / 0.15).pow(2))
                score[c] = scoreRaw[c] * ((1.0 - wIn) + wIn * cont)
            }
        } else System.arraycopy(scoreRaw, 0, score, 0, score.size)
        var b = argmax(score)
        val sMed = FableSolMath.percentile(score, 50.0)
        // 倍频消歧
        var bestTotal = -1.0; var bestB = b
        val mults = doubleArrayOf(1.0, 0.5, 2.0, 2.0 / 3.0, 1.5, 0.75, 4.0 / 3.0)
        for (mult in mults) {
            val bpmC = bpms[b] * mult
            if (bpmC < 55.0 || bpmC > 180.0) continue
            val bi = (FableSolMath.roundedFrameCount(60.0 * frameRate / bpmC) - lagMin)
                .coerceIn(0, scoreRaw.size - 1)
            val rc = phaseQuality(60.0 * frameRate / bpmC).second
            val total = scoreRaw[bi] * max(rc - 1.0, 0.02).pow(1.5)
            if (bestTotal < 0.0 || total > bestTotal) { bestTotal = total; bestB = bi }
        }
        b = bestB
        val confT = ((score[b] / (sMed + 1e-9) - 1.2) / 3.0).coerceIn(0.0, 1.0)
        var lag = lags[b].toDouble()
        if (b in 1 until score.size - 1) {
            val den = score[b - 1] - 2.0 * score[b] + score[b + 1]
            if (abs(den) > 1e-12) lag += 0.5 * (score[b - 1] - score[b + 1]) / den
        }
        val bpmNew = 60.0 * frameRate / max(lag, 1.0)
        if (bpm > 0.0 && abs(bpmNew - bpm) / bpm < 0.06) {
            bpm += (bpmNew - bpm) * 0.25; candCnt = 0
        } else {
            if (candBpm > 0.0 && abs(bpmNew - candBpm) / candBpm < 0.06) candCnt += 1
            else { candBpm = bpmNew; candCnt = 1 }
            if (candCnt >= SWITCH_CONFIRMATIONS || bpm <= 0.0) {
                bpm = bpmNew
                candCnt = 0
                tBeat = Double.NaN
            }
        }
        val perF = 60.0 * frameRate / bpm
        val pq = phaseQuality(perF)
        val ph = pq.first
        val confP = ((pq.second - 1.0) / 2.5).coerceIn(0.0, 1.0)
        val tMeas = t - ph / frameRate
        val perS = perF / frameRate
        if (tBeat.isNaN()) {
            tBeat = tMeas
        } else {
            val kB = FableSolMath.roundTiesToEven((tMeas - tBeat) / perS)
            val pred = tBeat + kB * perS
            tBeat = pred + 0.3 * (tMeas - pred)
        }
        while (tBeat + perS <= t) tBeat += perS
        if (hist.size >= 6) hist.removeFirst()
        hist.addLast(bpm)
        var stab = 1.0
        if (hist.size >= 4) {
            var hi = hist[0]; var lo = hist[0]
            for (x in hist) { if (x > hi) hi = x; if (x < lo) lo = x }
            stab = (1.0 - ((hi - lo) / max(lo, 1.0) - 0.05) / 0.10).coerceIn(0.0, 1.0)
        }
        val warm = ((n / frameRate - 4.0) / 4.5).coerceIn(0.0, 1.0)
        conf += (confT * (0.4 + 0.6 * confP) * stab * warm - conf) * 0.3
    }

    private fun atValue(lag: Int, ac: DoubleArray, lagHi: Int): Double {
        if (lag > lagHi) return 0.0
        val idx = (lag - lagMin).coerceIn(0, ac.size - 1)
        return ac[idx]
    }

    private fun phaseQuality(perF: Double): Pair<Int, Double> {
        val nK = min(8.0, (nWin - 1) / perF).toInt()
        val offsLen = max(perF.toInt(), 1)
        val s = DoubleArray(offsLen)
        val kMax = max(nK, 1)
        for (k2 in 0 until kMax) {
            val shift = FableSolMath.roundedFrameCount(k2 * perF)
            val w = 0.8.pow(k2)
            for (off in 0 until offsLen) {
                val idx = nWin - 1 - off - shift
                if (idx >= 0) { val e = env[idx]; s[off] += w * e * e }
            }
        }
        var ph = 0; var mx = s[0]
        for (i in 1 until offsLen) if (s[i] > mx) { mx = s[i]; ph = i }
        var sm = 0.0
        for (x in s) sm += x
        sm /= offsLen
        return Pair(ph, s[ph] / (sm + 1e-9))
    }

    fun state(t: Double): Triple<Double, Double, Double> {
        if (bpm <= 0.0 || tBeat.isNaN()) return Triple(0.0, 0.0, 0.0)
        val ph = ((t - tBeat) / (60.0 / bpm)).mod(1.0)
        return Triple(bpm, ph, conf)
    }

    private fun argmax(a: DoubleArray): Int {
        var b = 0; var mx = a[0]
        for (i in 1 until a.size) if (a[i] > mx) { mx = a[i]; b = i }
        return b
    }

    private fun log2(x: Double): Double = ln(x) / LN2

    companion object {
        private const val WIN_S = 9.6
        private const val STEP = 16
        private const val SWITCH_CONFIRMATIONS = 3
        private val LN2 = ln(2.0)

    }
}
