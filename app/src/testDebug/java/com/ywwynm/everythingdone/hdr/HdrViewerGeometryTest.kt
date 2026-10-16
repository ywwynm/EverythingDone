package com.ywwynm.everythingdone.hdr

import org.junit.Assert.assertEquals
import org.junit.Test

class HdrViewerGeometryTest {
    private fun point(matrix: FloatArray, u: Float, v: Float): Pair<Float, Float> {
        val z = matrix[2] * u + matrix[5] * v + matrix[8]
        return (matrix[0] * u + matrix[3] * v + matrix[6]) / z to
            (matrix[1] * u + matrix[4] * v + matrix[7]) / z
    }

    @Test fun fitPreservesLetterboxAndTopLeftOrientation() {
        val m = HdrViewerGeometry.transform(floatArrayOf(2f, 0f, 0f, 0f, 2f, 100f, 0f, 0f, 1f), 200, 100, 400, 400)
        assertPoint(-1f, 0.5f, point(m, 0f, 0f))
        assertPoint(1f, -0.5f, point(m, 1f, 1f))
    }

    @Test fun zoomAndPanKeepOffscreenCoordinatesForClipping() {
        val m = HdrViewerGeometry.transform(floatArrayOf(6f, 0f, -400f, 0f, 6f, -100f, 0f, 0f, 1f), 200, 100, 400, 400)
        assertPoint(-3f, 1.5f, point(m, 0f, 0f))
        assertPoint(3f, -1.5f, point(m, 1f, 1f))
        assertPoint(0f, 0f, point(m, 0.5f, 0.5f))
    }

    @Test fun rotationFollowsActualDrawableMatrix() {
        val m = HdrViewerGeometry.transform(floatArrayOf(0f, -2f, 200f, 2f, 0f, 0f, 0f, 0f, 1f), 200, 100, 200, 400)
        assertPoint(1f, 1f, point(m, 0f, 0f))
        assertPoint(-1f, -1f, point(m, 1f, 1f))
    }

    @Test fun drawableDensityAndDecodeSizeDoNotChangeGeometry() {
        val first = HdrViewerGeometry.transform(floatArrayOf(2f, 0f, 0f, 0f, 2f, 100f, 0f, 0f, 1f), 200, 100, 400, 400)
        val second = HdrViewerGeometry.transform(floatArrayOf(1f, 0f, 0f, 0f, 1f, 100f, 0f, 0f, 1f), 400, 200, 400, 400)
        first.indices.forEach { assertEquals(first[it], second[it], 0.0001f) }
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsNonFiniteMatrix() {
        HdrViewerGeometry.transform(FloatArray(9) { Float.NaN }, 1, 1, 1, 1)
    }

    private fun assertPoint(x: Float, y: Float, actual: Pair<Float, Float>) {
        assertEquals(x, actual.first, 0.0001f)
        assertEquals(y, actual.second, 0.0001f)
    }
}
