package com.ywwynm.everythingdone.views.particledismiss

import java.nio.ByteBuffer

/** 只加速材料准备；所有规则仍由共同模型传入。JVM 单测保留原实现作为独立基准。 */
internal object ParticleMaterialNative {
    val available = runCatching { System.loadLibrary("particle_material") }.isSuccess
    private val referenceOnly = ThreadLocal<Boolean>()
    val enabled get() = available && referenceOnly.get() != true

    fun <T> reference(block: () -> T): T {
        val previous = referenceOnly.get()
        referenceOnly.set(true)
        return try { block() } finally { referenceOnly.set(previous) }
    }

    /**
     * 3b：设置本地库按行并行的道数（含调用线程）。1 表示完全串行。
     *
     * 每一道只写自己那些下标、只读**别的**缓冲（分离的高斯两趟、梯度读模糊结果、填充逐片独立），
     * 所以道数不影响任何一个元素的计算顺序，输出必然逐位相同——设备端的 `nativeCheck`
     * 用「1 道 vs 默认道数」逐位对照守住这一条。只在 debug 的逐位对照里调用。
     */
    fun setLanes(lanes: Int) {
        if (!available) return
        // **0 表示"恢复按核数自动决定"**，不能被 coerceIn 夹成 1。
        // 3b 的写法是 `coerceIn(1, 16)`，于是 `setLanes(0)` 实际把道数钉死在 1：设备端的
        // 「1 道 vs 默认道数」对照因此比的是两次串行运行（恒等式），而且整个进程此后一直串行。
        // 2026-09-15 第四批新增的优先级读回对照把它暴露出来（工作线程一道都没派发）。
        runCatching { nativeSetLanes(if (lanes <= 0) 0 else lanes.coerceIn(1, 16)) }
    }

    private external fun nativeSetLanes(lanes: Int)

    /** 本地库实际使用的道数（含调用线程）；不可用时为 0。 */
    fun lanes(): Int = if (!available) 0 else runCatching { nativeLanes() }.getOrDefault(0)

    private external fun nativeLanes(): Int

    /**
     * 第四批 ④：工作线程最近一次派发时采用的 nice。
     *
     * 本地库的按行并行改成「工作线程跟随调用线程的优先级」——1.7 的「有反馈或播放时让出」
     * 由调用线程自己决定（[ParticlePerfTuning.defaultPriorityPreparation]），工作线程照抄，
     * 所以那条语义不需要在本地库里再判一次。优先级不改变任何数值，逐位对照证明不了它生效，
     * 只能把两个 nice 读回来比。
     */
    fun lanePriority(): Int = if (!available) 0 else runCatching { nativeLanePriority() }.getOrDefault(0)

    /** 当前线程自己的 nice（Linux 上 `PRIO_PROCESS` 作用于 tid，与 `Process.getThreadPriority` 同义）。 */
    fun threadPriority(): Int = if (!available) 0 else runCatching { nativeThreadPriority() }.getOrDefault(0)

    /**
     * 设备端对照专用：真跑一次并行段再读回工作线程采用的 nice。
     *
     * 不能靠一次真实建材来触发——小尺寸下 `parallel` 走串行分支，一道也不派发，
     * 读回来的永远是初值（2026-09-15 第一次跑对照时就是这样失败的）。
     */
    fun probeLanePriority(): Int =
        if (!available) 0 else runCatching { nativeProbeLanePriority() }.getOrDefault(0)

    private external fun nativeLanePriority(): Int

    private external fun nativeThreadPriority(): Int

    private external fun nativeProbeLanePriority(): Int

    external fun release(nx: Int, ny: Int, width: Float, height: Float,
        gridWidth: Int, gridHeight: Int, grid: FloatArray, fieldMin: Double, fieldSize: Double,
        geometry: DoubleArray, anchors: DoubleArray, variation: FloatArray, inverse: DoubleArray,
        patches: DoubleArray, windX: Double, windY: Double, locality: Double, offsets: FloatArray?): FloatArray

    external fun gaussian(input: FloatArray, width: Int, height: Int, weights: DoubleArray): FloatArray

    external fun finishField(field: FloatArray, nx: Int, ny: Int, width: Float, height: Float,
        windX: Double, windY: Double, panelWeight: Float, weights1: DoubleArray,
        weights3: DoubleArray, weights7: DoubleArray): Array<FloatArray>

    external fun packUploads(pixels: IntArray, material: FloatArray, count: Int,
        rgba: ByteBuffer, state: ByteBuffer)

    external fun packColors(pixels: IntArray, rgba: ByteBuffer)

    /** [packColors] 的逆（也是同一个 R↔B 对调）：直接从上传字节还原 Android ARGB。 */
    external fun unpackColors(rgba: ByteBuffer, pixels: IntArray)

    external fun packState(material: FloatArray, count: Int, state: ByteBuffer)

    external fun populate(nx: Int, ny: Int, width: Float, height: Float, pixels: IntArray,
        pixelWidth: Int, pixelHeight: Int, seed: Int, release: FloatArray, offsets: FloatArray,
        normalX: FloatArray, normalY: FloatArray, compression: FloatArray,
        panel: FloatArray, rules: FloatArray, copyLimit: Int, maxCount: Int): Array<FloatArray>
}
