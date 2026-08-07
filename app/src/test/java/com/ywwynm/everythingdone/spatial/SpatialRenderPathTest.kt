package com.ywwynm.everythingdone.spatial

import org.junit.Assert.assertEquals
import org.junit.Test

class SpatialRenderPathTest {

    @Test
    fun `全局连续场在普通空间模式下必须走inverse warp而不是前向网格`() {
        for (
            renderer in SpatialLdiRenderer.entries.filter {
                it.usesGlobalInverseWarp
            }
        ) {
            for (mode in listOf(SpatialRenderMode.SINGLE_LAYER, SpatialRenderMode.LDI_LITE)) {
                assertEquals(
                    SpatialRenderPath.SOURCE_WARP,
                    SpatialRenderPath.resolve(
                        mode = mode,
                        renderer = renderer,
                        hasLayeredScene = true
                    )
                )
            }
        }
    }

    @Test
    fun `vNext稳定模式必须使用保形分层几何而不是旧单纹理warp`() {
        assertEquals(
            SpatialRenderPath.LAYERED_SCENE,
            SpatialRenderPath.resolve(
                mode = SpatialRenderMode.SINGLE_LAYER,
                renderer = SpatialLdiRenderer.SURFACE_CHARTS_VNEXT1,
                hasLayeredScene = true
            )
        )
        assertEquals(
            SpatialRenderPath.LAYERED_SCENE,
            SpatialRenderPath.resolve(
                mode = SpatialRenderMode.SINGLE_LAYER,
                renderer =
                    SpatialLdiRenderer.SURFACE_DEPTH_VNEXT11_ADAPTIVE_VISIBILITY_48PX,
                hasLayeredScene = true
            )
        )
    }

    @Test
    fun `旧v19稳定模式与无分层派生仍保持兼容路径`() {
        assertEquals(
            SpatialRenderPath.SOURCE_WARP,
            SpatialRenderPath.resolve(
                mode = SpatialRenderMode.SINGLE_LAYER,
                renderer = SpatialLdiRenderer.LEGACY_V19,
                hasLayeredScene = true
            )
        )
        assertEquals(
            SpatialRenderPath.SOURCE_WARP,
            SpatialRenderPath.resolve(
                mode = SpatialRenderMode.LDI_LITE,
                renderer = SpatialLdiRenderer.SURFACE_CHARTS_VNEXT1,
                hasLayeredScene = false
            )
        )
    }

    @Test
    fun `立体模式在新旧renderer下都使用分层场景`() {
        for (
            renderer in SpatialLdiRenderer.entries.filterNot {
                it.usesGlobalInverseWarp
            }
        ) {
            assertEquals(
                SpatialRenderPath.LAYERED_SCENE,
                SpatialRenderPath.resolve(
                    mode = SpatialRenderMode.LDI_LITE,
                    renderer = renderer,
                    hasLayeredScene = true
                )
            )
        }
    }
}
