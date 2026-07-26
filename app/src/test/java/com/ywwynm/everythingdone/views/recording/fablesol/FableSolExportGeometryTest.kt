package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Test

class FableSolExportGeometryTest {

    @Test
    fun canvasRoundsUpToCodecAnd64PixelShareAlignment() {
        assertEquals(1472, FableSolExportTier.alignForEncoder(1444, 16))
        assertEquals(1216, FableSolExportTier.alignForEncoder(1160, 16))
        assertEquals(1216, FableSolExportTier.alignForEncoder(1160, 8))
        assertEquals(1536, FableSolExportTier.alignForEncoder(1450, 96))
    }

    @Test
    fun alignedCanvasKeepsCardGeometryAndRecentresOnlyBackdrop() {
        val base = FableSolExportPlan(
            cardWidthPx = 1012,
            cardHeightPx = 1296,
            paddingPx = 74,
            canvasWidthPx = 1160,
            canvasHeightPx = 1444,
            cardOriginXPx = 74,
            cardOriginYPx = 74,
            density = 1296.0 / 420.0,
            cornerRadiusPx = 1f,
            shadowOffsetPx = 1f,
            shadowRadiusPx = 1f,
            shadowAlpha = 1f,
            rimWidthPx = 1f,
            rimColor = 0,
            rimAlpha = 1f,
            backdropColor = 0,
            clockWidthPx = 1,
            clockHeightPx = 1,
            clockLeftPx = 1,
            clockTopPx = 1
        )

        val aligned = base.withCanvasSize(1216, 1472)

        assertEquals(base.cardWidthPx, aligned.cardWidthPx)
        assertEquals(base.cardHeightPx, aligned.cardHeightPx)
        assertEquals(base.density, aligned.density, 0.0)
        assertEquals(102, aligned.cardOriginXPx)
        assertEquals(88, aligned.cardOriginYPx)
        assertEquals(0, aligned.canvasWidthPx % 64)
        assertEquals(0, aligned.canvasHeightPx % 64)
        assertEquals(
            aligned.canvasWidthPx,
            aligned.cardOriginXPx * 2 + aligned.cardWidthPx
        )
        assertEquals(
            aligned.canvasHeightPx,
            aligned.cardOriginYPx * 2 + aligned.cardHeightPx
        )
    }
}
