package com.ywwynm.everythingdone.model

import org.junit.Assert.*
import org.junit.Test

class DoingBackgroundCropsTest {
    @Test fun switchingDirectionsKeepsIndependentDrafts() {
        val portrait = DoingBackgroundCrops.Frame(0.25, 0.65, 2.0)
        val landscape = DoingBackgroundCrops.Frame(0.8, 0.4, 1.4)
        val saved = DoingBackgroundCrops().withFrame(false, portrait)
        val draft = saved.withFrame(true, landscape)
        assertEquals(portrait, draft.forSize(1080, 2400))
        assertEquals(landscape, draft.forSize(2400, 1080))
        assertEquals(DoingBackgroundCrops.Frame(), saved.landscape)
    }

    @Test fun naturallyLandscapeTabletUsesTheSameTwoRatiosAfterRotation() {
        for (landscape in listOf(false, true)) {
            assertEquals(DoingBackgroundCrops.aspectRatio(2560, 1600, landscape),
                DoingBackgroundCrops.aspectRatio(1600, 2560, landscape), 0.000001)
        }
        assertEquals(1.6, DoingBackgroundCrops.aspectRatio(2560, 1600, true), 0.000001)
    }

    @Test fun tallPhoneIsNotLimitedToDrawerHeaderRatios() {
        assertEquals(0.45, DoingBackgroundCrops.aspectRatio(1080, 2400, false), 0.000001)
        assertEquals(2400.0 / 1080, DoingBackgroundCrops.aspectRatio(1080, 2400, true), 0.000001)
    }

    @Test fun damagedValuesCannotEscapeEditorLimits() {
        assertEquals(DoingBackgroundCrops.Frame(), DoingBackgroundCrops.Frame(
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).normalized())
        assertEquals(DoingBackgroundCrops.Frame(0.0, 1.0, 3.0),
            DoingBackgroundCrops.Frame(-1.0, 2.0, 8.0).normalized())
        assertEquals(1.0, DoingBackgroundCrops.aspectRatio(0, 0, false), 0.0)
    }
}
