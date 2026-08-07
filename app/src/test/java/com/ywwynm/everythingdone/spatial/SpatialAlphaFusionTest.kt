package com.ywwynm.everythingdone.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpatialAlphaFusionTest {

    /** 8×8 网格：左半近景（0.9）右半远景（0.1），中缝竖直断边。 */
    private fun geometryWithVerticalCut(): SpatialLdiLiteGeometry {
        val width = 8
        val height = 8
        val depth = FloatArray(width * height) { index ->
            if (index % width < 4) 0.9f else 0.1f
        }
        val cutRight = BooleanArray(height * (width - 1))
        for (y in 0 until height) cutRight[y * (width - 1) + 3] = true
        return SpatialLdiLiteGeometry(
            width = width,
            height = height,
            surfaceDepth = depth,
            backgroundDepth = depth.copyOf(),
            cutRight = cutRight,
            cutDown = BooleanArray((height - 1) * width),
            hiddenBackgroundMask = BooleanArray(width * height)
        )
    }

    @Test
    fun buildDisplayAlpha_appliesMatteOnlyInActiveBandCells() {
        val geometry = geometryWithVerticalCut()
        // matte 与网格同构：左半前景高 alpha（活性），右半 0。
        val matte = SpatialAlphaData(
            width = 16,
            height = 16,
            values = FloatArray(16 * 16) { index ->
                if (index % 16 < 8) 0.75f else 0f
            }
        )

        val plane = SpatialAlphaFusion.buildDisplayAlpha(
            geometry = geometry,
            matte = matte,
            targetWidth = 32,
            targetHeight = 32
        )

        checkNotNull(plane)
        // 近侧带内（左半、活性格）：alpha ≈ 0.75×255。
        val inside = plane[16 * 32 + 12].toInt() and 0xff
        assertEquals(191.0, inside.toDouble(), 6.0)
        // 远侧带内格 matte 峰值 0 → 非活性 → 不透明。
        val farSide = plane[16 * 32 + 28].toInt() and 0xff
        assertEquals(255, farSide)
    }

    @Test
    fun buildDisplayAlpha_collapsedMatteYieldsNull() {
        val geometry = geometryWithVerticalCut()
        // 全图 matte 塌零（MODNet 暗衣失效形态）：无活性格 → 返回 null。
        val matte = SpatialAlphaData(16, 16, FloatArray(16 * 16))
        assertNull(
            SpatialAlphaFusion.buildDisplayAlpha(geometry, matte, 32, 32)
        )
    }

    @Test
    fun bandRadiusCells_preservesApproximatePixelWidthAcrossMeshDensities() {
        assertEquals(4, SpatialAlphaFusion.bandRadiusCells(600, 450))
        assertEquals(2, SpatialAlphaFusion.bandRadiusCells(256, 192))
    }
}
