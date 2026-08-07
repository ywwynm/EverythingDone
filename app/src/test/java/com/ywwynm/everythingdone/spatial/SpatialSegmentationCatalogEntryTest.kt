package com.ywwynm.everythingdone.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpatialSegmentationCatalogEntryTest {

    @Test
    fun signedEntryMustExactlyMatchBuiltInInferenceAbi() {
        val model = SpatialSegmentationModel.RF_DETR_SEG_NANO
        val entry = SpatialSegmentationCatalogEntry(
            id = model.stableId,
            version = model.version,
            url = "https://example.invalid/${model.fileName}",
            sizeBytes = model.sizeBytes,
            sha256 = model.sha256,
            format = "onnx",
            precision = "fp32",
            license = model.licenseId,
            minDeviceRamMb = model.minimumTotalRamMb,
            enabled = true,
            disabledReason = null
        )

        assertEquals(model, entry.builtInModel())
        assertNull(entry.copy(sizeBytes = model.sizeBytes + 1).builtInModel())
        assertNull(entry.copy(license = "unknown").builtInModel())
    }
}
