package com.ywwynm.everythingdone.spatial

import kotlin.math.roundToInt

/** 把四通道有符号位移基量化为 GLES2 可线性采样的 RGBA8 纹理。 */
internal object SpatialMotionBasisTexture {

    fun encode(basis: SpatialScreenSpaceMotionBasis): ByteArray {
        val size = basis.width * basis.height
        return ByteArray(size * CHANNEL_COUNT).also { encoded ->
            for (index in 0 until size) {
                val offset = index * CHANNEL_COUNT
                encoded[offset] = encodeComponent(basis.horizontalX[index]).toByte()
                encoded[offset + 1] = encodeComponent(basis.horizontalY[index]).toByte()
                encoded[offset + 2] = encodeComponent(basis.verticalX[index]).toByte()
                encoded[offset + 3] = encodeComponent(basis.verticalY[index]).toByte()
            }
        }
    }

    internal fun encodeComponent(value: Float): Int =
        (((value.coerceIn(MIN_VALUE, MAX_VALUE) - MIN_VALUE) / VALUE_RANGE) * 255f)
            .roundToInt()
            .coerceIn(0, 255)

    internal fun decodeComponent(value: Int): Float =
        (value.coerceIn(0, 255) / 255f) * VALUE_RANGE + MIN_VALUE

    private const val CHANNEL_COUNT = 4
    private const val MIN_VALUE = -1f
    private const val MAX_VALUE = 1f
    private const val VALUE_RANGE = MAX_VALUE - MIN_VALUE
}
