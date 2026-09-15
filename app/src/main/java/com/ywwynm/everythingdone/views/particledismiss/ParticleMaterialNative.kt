package com.ywwynm.everythingdone.views.particledismiss

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
        runCatching { nativeSetLanes(lanes.coerceIn(1, 16)) }
    }

    private external fun nativeSetLanes(lanes: Int)

    /** 本地库实际使用的道数（含调用线程）；不可用时为 0。 */
    fun lanes(): Int = if (!available) 0 else runCatching { nativeLanes() }.getOrDefault(0)

    private external fun nativeLanes(): Int

    external fun release(nx: Int, ny: Int, width: Float, height: Float,
        gridWidth: Int, gridHeight: Int, grid: FloatArray, fieldMin: Double, fieldSize: Double,
        geometry: DoubleArray, anchors: DoubleArray, variation: FloatArray, inverse: DoubleArray,
        patches: DoubleArray, windX: Double, windY: Double, locality: Double, offsets: FloatArray?): FloatArray

    external fun gaussian(input: FloatArray, width: Int, height: Int, weights: DoubleArray): FloatArray

    external fun finishField(field: FloatArray, nx: Int, ny: Int, width: Float, height: Float,
        windX: Double, windY: Double, panelWeight: Float, weights1: DoubleArray,
        weights3: DoubleArray, weights7: DoubleArray): Array<FloatArray>

    external fun packUploads(pixels: IntArray, material: FloatArray, count: Int,
        rgba: java.nio.ByteBuffer, state: java.nio.ByteBuffer)

    external fun packColors(pixels: IntArray, rgba: java.nio.ByteBuffer)

    /** [packColors] 的逆（也是同一个 R↔B 对调）：直接从上传字节还原 Android ARGB。 */
    external fun unpackColors(rgba: java.nio.ByteBuffer, pixels: IntArray)

    external fun packState(material: FloatArray, count: Int, state: java.nio.ByteBuffer)

    external fun populate(nx: Int, ny: Int, width: Float, height: Float, pixels: IntArray,
        pixelWidth: Int, pixelHeight: Int, seed: Int, release: FloatArray, offsets: FloatArray,
        normalX: FloatArray, normalY: FloatArray, compression: FloatArray,
        panel: FloatArray, rules: FloatArray, copyLimit: Int, maxCount: Int): Array<FloatArray>
}
