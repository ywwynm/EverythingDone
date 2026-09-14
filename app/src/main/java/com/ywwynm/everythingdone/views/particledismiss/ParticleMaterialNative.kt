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
    external fun packState(material: FloatArray, count: Int, state: java.nio.ByteBuffer)

    external fun populate(nx: Int, ny: Int, width: Float, height: Float, pixels: IntArray,
        pixelWidth: Int, pixelHeight: Int, seed: Int, release: FloatArray, offsets: FloatArray,
        normalX: FloatArray, normalY: FloatArray, compression: FloatArray,
        panel: FloatArray, rules: FloatArray, copyLimit: Int, maxCount: Int): Array<FloatArray>
}
