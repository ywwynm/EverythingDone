package com.ywwynm.everythingdone.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpatialBoundaryRefinementCatalogEntryTest {

    @Test
    fun `签名条目必须精确匹配内置多文件推理 ABI`() {
        val model = SpatialBoundaryRefinementModel.EDGETAM
        val entry = SpatialBoundaryRefinementCatalogEntry(
            id = model.stableId,
            version = model.version,
            url = "https://example.invalid/${model.archiveFileName}",
            sizeBytes = model.archiveSizeBytes,
            sha256 = model.archiveSha256,
            format = "zip-onnx-bundle",
            precision = "fp32",
            license = model.licenseId,
            minDeviceRamMb = model.minimumTotalRamMb,
            enabled = true,
            disabledReason = null
        )

        assertEquals(model, entry.builtInModel())
        assertNull(entry.copy(sizeBytes = model.archiveSizeBytes + 1).builtInModel())
        assertNull(entry.copy(format = "onnx").builtInModel())
        assertNull(entry.copy(license = "unknown").builtInModel())
    }
}
