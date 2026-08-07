package com.ywwynm.everythingdone.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialSubjectLayerTest {

    @Test
    fun `主体mask保留成片前景并删除零散误检`() {
        val width = 10
        val height = 10
        val matte = FloatArray(width * height)
        for (y in 2..7) for (x in 2..7) matte[y * width + x] = 1f
        matte[0] = 1f

        val generated = SpatialSubjectLayer.buildMask(
            SpatialAlphaData(width, height, matte), width, height
        )
        assertNotNull(generated)
        val mask = checkNotNull(generated)

        assertTrue((mask[5 * width + 5].toInt() and 0xff) > 0)
        assertEquals(0, mask[0].toInt() and 0xff)
    }

}
