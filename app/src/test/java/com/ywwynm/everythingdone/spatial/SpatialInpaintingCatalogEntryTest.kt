package com.ywwynm.everythingdone.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpatialInpaintingCatalogEntryTest {

    @Test
    fun exactPublishedAbiResolvesBuiltInModel() {
        val model = SpatialInpaintingModel.MIGAN_PLACES2_512_PIPELINE
        val entry = entry(model)

        assertEquals(model, entry.builtInModel())
    }

    @Test
    fun aotGanPublishedAbiResolvesBuiltInModel() {
        val model = SpatialInpaintingModel.AOTGAN_PLACES2_512

        assertEquals(model, entry(model).builtInModel())
    }

    @Test
    fun changedPipelinePrecisionIsRejected() {
        val model = SpatialInpaintingModel.MIGAN_PLACES2_512_PIPELINE
        val entry = entry(model).copy(precision = "fp32")

        assertNull(entry.builtInModel())
    }

    @Test
    fun changedHashIsRejected() {
        val model = SpatialInpaintingModel.MIGAN_PLACES2_512_PIPELINE
        val entry = entry(model).copy(sha256 = "0".repeat(64))

        assertNull(entry.builtInModel())
    }

    @Test
    fun legacyAndAdditionalCatalogFieldsAreMerged() {
        val miGan = entry(SpatialInpaintingModel.MIGAN_PLACES2_512_PIPELINE)
        val aotGan = entry(SpatialInpaintingModel.AOTGAN_PLACES2_512)
        val catalog = SpatialModelCatalog(
            schemaVersion = 1,
            channel = "stable",
            catalogVersion = 1,
            publishedAt = "2026-07-31T00:00:00Z",
            runtimeVersion = 1,
            models = emptyList(),
            inpaintingModels = listOf(miGan),
            runtimes = null,
            additionalInpaintingModels = listOf(aotGan)
        )

        assertEquals(listOf(miGan, aotGan), catalog.allInpaintingModels())
    }

    private fun entry(
        model: SpatialInpaintingModel
    ): SpatialInpaintingCatalogEntry = SpatialInpaintingCatalogEntry(
        id = model.stableId,
        version = model.version,
        url = "https://example.invalid/${model.fileName}",
        sizeBytes = model.sizeBytes,
        sha256 = model.sha256,
        format = "onnx",
        precision = model.inputContract.catalogPrecision,
        license = model.licenseId,
        minDeviceRamMb = model.minimumTotalRamMb,
        enabled = true,
        disabledReason = null
    )
}
