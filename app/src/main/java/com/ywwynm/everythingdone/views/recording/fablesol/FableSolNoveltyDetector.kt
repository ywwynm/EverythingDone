package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/** 段落命中（对应 push 返回的 {t, magnitude01}）。 */
class FableSolNoveltyHit(@JvmField val t: Double, @JvmField val magnitude01: Double)

/**
 * 因果 Foote 新奇度段落检测（对应 features.py 的 _NoveltyDetector 倒谱路径）：32 对数频带的
 * 1~12 阶倒谱系数 → 0.5s 块平均 → 逐维滚动 z 分数 → L2 归一 → 自相似矩阵对角线滑动高斯棋盘核。
 * 语义 MusiCNN 路径不移植，固定走倒谱回退（use_z=True, fire_z=4.2）。
 */
class FableSolNoveltyDetector(
    private val frameRate: Double,
    private val kWin: Int = 13,
    private val warmupBlocks: Int = 24,
    private val fireZ: Double = 4.2,
    private val minGapS: Double = 9.0
) {
    private val blockN = (0.5 * frameRate).toInt()
    private val dct = Array(12) { k ->
        DoubleArray(32) { j -> cos(Math.PI / 32.0 * (j + 0.5) * (k + 1)) }
    }
    private val kernel: Array<DoubleArray>

    private val acc = DoubleArray(32)
    private var accFrames = 0
    private var accVoiced = 0
    private val blocks = ArrayDeque<BlockEntry>()
    private val nov = ArrayDeque<DoubleArray>()   // [tCenter, novValue]
    private var lastFire = -100.0
    private var mean: DoubleArray? = null
    private var variance: DoubleArray? = null
    private var statN = 0

    private class BlockEntry(@JvmField val t: Double, @JvmField val vec: DoubleArray?)

    init {
        val half = kWin / 2
        val kv = DoubleArray(kWin) {
            val d = (it - half).toDouble()
            exp(-0.5 * (d / (half * 0.5)) * (d / (half * 0.5))) * Math.signum(d)
        }
        var absSum = 0.0
        for (a in 0 until kWin) for (b in 0 until kWin) absSum += abs(kv[a] * kv[b])
        kernel = Array(kWin) { a -> DoubleArray(kWin) { b -> kv[a] * kv[b] / absSum } }
        reset(true)
    }

    fun reset(full: Boolean) {
        java.util.Arrays.fill(acc, 0.0)
        accFrames = 0; accVoiced = 0
        blocks.clear(); nov.clear()
        lastFire = -100.0
        if (full) { mean = null; variance = null; statN = 0 }
    }

    /** 倒谱回退路径：32 频带 log 能量逐帧累积成 0.5s 块 → 1~12 阶 DCT。 */
    fun push(logb: DoubleArray, voiced: Boolean, t: Double): FableSolNoveltyHit? {
        if (voiced) { for (j in 0 until 32) acc[j] += logb[j]; accVoiced++ }
        accFrames++
        if (accFrames < blockN) return null
        var hit: FableSolNoveltyHit? = null
        if (accVoiced >= blockN / 3) {
            val x = DoubleArray(32) { acc[it] / accVoiced }
            val vec = DoubleArray(12) { k ->
                var s = 0.0
                val row = dct[k]
                for (j in 0 until 32) s += row[j] * x[j]
                s
            }
            hit = pushBlock(vec, t - 0.25)
        } else {
            blocks.addLast(BlockEntry(t - 0.25, null)); capBlocks()
        }
        java.util.Arrays.fill(acc, 0.0); accFrames = 0; accVoiced = 0
        return hit
    }

    private fun pushBlock(vecIn: DoubleArray, tCenter: Double): FableSolNoveltyHit? {
        statN++
        var vec = vecIn
        var mn = mean; var vr = variance
        if (mn == null) { mn = DoubleArray(vec.size); vr = DoubleArray(vec.size) { 1.0 }; mean = mn; variance = vr }
        val a = max(1.0 / statN, 1.0 / 60.0)
        val z = DoubleArray(vec.size)
        for (i in vec.indices) {
            mn[i] += (vec[i] - mn[i]) * a
            vr!![i] += ((vec[i] - mn[i]) * (vec[i] - mn[i]) - vr[i]) * a
            z[i] = (vec[i] - mn[i]) / sqrt(vr[i] + 1e-6)
        }
        vec = z
        var norm = 0.0
        for (v in vec) norm += v * v
        norm = sqrt(norm)
        val unit = if (norm > 1e-6) DoubleArray(vec.size) { vec[it] / norm } else null
        blocks.addLast(BlockEntry(tCenter, unit)); capBlocks()
        return detect()
    }

    private fun detect(): FableSolNoveltyHit? {
        if (blocks.size < kWin || statN < warmupBlocks) return null
        val start = blocks.size - kWin
        val cT = blocks[start + kWin / 2].t
        val vecs = arrayOfNulls<DoubleArray>(kWin)
        for (i in 0 until kWin) {
            val v = blocks[start + i].vec ?: return null
            vecs[i] = v
        }
        var novVal = 0.0
        for (a in 0 until kWin) {
            val va = vecs[a]!!
            for (b in 0 until kWin) {
                val vb = vecs[b]!!
                var dot = 0.0
                for (d in va.indices) dot += va[d] * vb[d]
                novVal += kernel[a][b] * dot
            }
        }
        nov.addLast(doubleArrayOf(cT, novVal)); capNov()
        if (nov.size < 3) return null
        val last = nov.size - 1
        val t1 = nov[last - 1][0]; val n1 = nov[last - 1][1]
        val n0 = nov[last - 2][1]; val n2 = nov[last][1]
        if (!(n1 >= n0 && n1 > n2 && t1 - lastFire > minGapS)) return null
        if (nov.size < 8) return null
        val histArr = DoubleArray(nov.size) { nov[it][1] }
        val med = FableSolMath.percentile(histArr, 50.0)
        val absDev = DoubleArray(histArr.size) { abs(histArr[it] - med) }
        val mad = FableSolMath.percentile(absDev, 50.0) + 1e-4
        val zScore = (n1 - med) / mad
        if (zScore <= fireZ) return null
        val mag = (zScore / (3.0 * fireZ)).coerceIn(0.0, 1.0)
        lastFire = t1
        return FableSolNoveltyHit(t1, mag)
    }

    private fun capBlocks() { while (blocks.size > 64) blocks.removeFirst() }
    private fun capNov() { while (nov.size > 80) nov.removeFirst() }
}
