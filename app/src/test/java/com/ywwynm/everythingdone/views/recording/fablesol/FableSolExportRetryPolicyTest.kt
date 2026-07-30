package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FableSolExportRetryPolicyTest {

    private val av1Hardware = FableSolExportPublicSpec(
        format = FableSolExportHdrFormat.HDR10,
        family = FableSolExportCodecFamily.AV1,
        tenBit = true,
        softwareOnly = false,
        frameRate = 120
    )

    @Test
    fun publicSpecChangesOnlyWhenOneOfTheSixPublishedFieldsChanges() {
        // 具体 codecName 与输入通路不属于本模型，因此同一公开规格只产生一个分组。
        assertEquals(
            listOf(av1Hardware),
            FableSolExportRetryPolicy.orderedSpecs(
                listOf(av1Hardware, av1Hardware.copy(), av1Hardware.copy())
            )
        )
        assertNotEquals(
            av1Hardware,
            av1Hardware.copy(family = FableSolExportCodecFamily.HEVC)
        )
        assertNotEquals(av1Hardware, av1Hardware.copy(softwareOnly = true))
        assertNotEquals(av1Hardware, av1Hardware.copy(frameRate = 60))
        assertNotEquals(
            av1Hardware,
            av1Hardware.copy(rateControl = FableSolExportRateControl.TARGET_BITRATE)
        )
    }

    @Test
    fun suggestionNeverChangesTheStrictFrameRateOrRetriesAFailedSpecs() {
        val hevcHardware = av1Hardware.copy(family = FableSolExportCodecFamily.HEVC)
        val hevcSixty = hevcHardware.copy(frameRate = 60)
        val hevcSoftware = hevcHardware.copy(softwareOnly = true)

        assertEquals(
            hevcSoftware,
            FableSolExportRetryPolicy.nextSuggestion(
                ordered = listOf(av1Hardware, hevcSixty, hevcHardware, hevcSoftware),
                failed = setOf(av1Hardware, hevcHardware),
                strictFrameRate = 120
            )
        )
        assertNull(
            FableSolExportRetryPolicy.nextSuggestion(
                ordered = listOf(av1Hardware, hevcSixty),
                failed = setOf(av1Hardware),
                strictFrameRate = 120
            )
        )
    }
}
