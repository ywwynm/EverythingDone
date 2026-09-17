package com.ywwynm.everythingdone.views.particledismiss

import com.ywwynm.everythingdone.BuildConfig

/**
 * 仅 debug：逐帧成本归因用的「临时跳过某一段」掩码。
 *
 * 用途只有一个——测同一条路径在少了某一段之后的墙钟，差值即那一段的份额。跳过之后画面
 * 当然是错的，所以**只允许在离屏基准里用**（[ParticleSegmentBench]），绝不能落到出帧路径上。
 *
 * 掩码是**线程局部**的。第五批的教训（见 [ParticlePerfTuning.withBaselineGpu] 的注释）：
 * 诊断改进程级开关时，常驻 GL 线程上的提前预算会同时读到，等于把生产路径也改了。
 * release 包里 [bits] 恒为 0，所有读点都是常量假分支。
 */
internal object ParticleAblation {

    const val INTEGRATE = 1 shl 0
    const val SPLAT = 1 shl 1
    const val BLUR = 1 shl 2
    const val JACOBI = 1 shl 3
    const val PROJECT = 1 shl 4
    const val HISTORY = 1 shl 5
    const val PASS1 = 1 shl 6
    const val RESOLVE = 1 shl 7
    const val CLEAR = 1 shl 8
    const val PASS0 = 1 shl 9
    const val PRESSURE = SPLAT or BLUR or JACOBI or PROJECT

    private val local = ThreadLocal<Int>()

    val bits: Int get() = if (!BuildConfig.DEBUG) 0 else local.get() ?: 0

    fun skipping(mask: Int) = bits and mask != 0

    /** 只影响当前线程，退出时恢复上一层的掩码。 */
    fun <T> with(mask: Int, action: () -> T): T {
        if (!BuildConfig.DEBUG) return action()
        val previous = local.get()
        local.set(mask)
        return try { action() } finally { local.set(previous) }
    }
}
