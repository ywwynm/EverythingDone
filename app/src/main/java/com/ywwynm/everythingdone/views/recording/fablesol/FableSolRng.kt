package com.ywwynm.everythingdone.views.recording.fablesol

/**
 * 随机源封装（对应 numpy default_rng 的用法）。原版用固定 seed 生成各层的相位/波长/抖动，
 * 保证层间差异与每次运行的可复现性。Kotlin 用 java.util.Random(seed)：底层序列与 PCG64
 * 不同，但每个种子仍产生互不相同、分布一致的参数——满足"物理/视觉行为一比一"（非逐值一致）。
 */
class FableSolRng(seed: Long) {
    private val r = java.util.Random(seed)

    fun uniform(lo: Double, hi: Double): Double = lo + (hi - lo) * r.nextDouble()

    fun uniform(lo: Double, hi: Double, n: Int): DoubleArray =
        DoubleArray(n) { lo + (hi - lo) * r.nextDouble() }

    fun gaussian(mean: Double, std: Double): Double = mean + std * r.nextGaussian()

    fun nextDouble(): Double = r.nextDouble()
}
