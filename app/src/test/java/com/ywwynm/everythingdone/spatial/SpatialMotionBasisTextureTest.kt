package com.ywwynm.everythingdone.spatial

import org.junit.Assert.assertEquals
import org.junit.Test

class SpatialMotionBasisTextureTest {

    @Test
    fun `RGBA8量化可在一个通道量化误差内还原位移基`() {
        val values = floatArrayOf(-1f, -0.5f, 0f, 0.5f, 1f)
        val basis = SpatialScreenSpaceMotionBasis(
            width = values.size,
            height = 2,
            horizontalX = values + values,
            horizontalY = values.reversedArray() + values.reversedArray(),
            verticalX = FloatArray(values.size * 2),
            verticalY = values + values
        )

        val encoded = SpatialMotionBasisTexture.encode(basis)

        for (index in basis.horizontalX.indices) {
            val decoded = SpatialMotionBasisTexture.decodeComponent(
                encoded[index * 4].toInt() and 0xff
            )
            assertEquals(basis.horizontalX[index], decoded, 2f / 255f)
        }
    }

    @Test
    fun `超出纹理契约的值会被安全钳制`() {
        assertEquals(0, SpatialMotionBasisTexture.encodeComponent(-2f))
        assertEquals(255, SpatialMotionBasisTexture.encodeComponent(2f))
    }
}
