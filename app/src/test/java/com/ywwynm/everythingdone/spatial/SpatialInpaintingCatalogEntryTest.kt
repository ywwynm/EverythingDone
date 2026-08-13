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
    fun bigLamaPublishedAbiResolvesBuiltInModel() {
        val model = SpatialInpaintingModel.BIG_LAMA_PLACES2_512

        assertEquals(model, entry(model).builtInModel())
    }

    /**
     * 三个模型的 ABI 必须两两可区分：precision 是 catalog 与 App 之间唯一的契约标识，
     * 撞车会让下载下来的权重按错误的输入契约推理（AOT-GAN 的 0..1 归一化口径与
     * Big-LaMa 的 0..255 输出口径不兼容），而且不会报错、只会出一张烂图。
     */
    @Test
    fun everyModelHasDistinctAbiIdentity() {
        val models = SpatialInpaintingModel.entries
        assertEquals(models.size, models.map { it.stableId }.distinct().size)
        assertEquals(models.size, models.map { it.fileName }.distinct().size)
        assertEquals(models.size, models.map { it.sha256.lowercase() }.distinct().size)
        assertEquals(
            models.size,
            models.map { it.inputContract.catalogPrecision }.distinct().size
        )
    }

    /** 目录条目串到别的模型上必须被拒——builtInModel() 是靠 id 找、再逐项核对的。 */
    @Test
    fun crossModelEntryIsRejected() {
        val bigLama = SpatialInpaintingModel.BIG_LAMA_PLACES2_512
        val crossed = entry(bigLama).copy(
            sizeBytes = SpatialInpaintingModel.MIGAN_PLACES2_512_PIPELINE.sizeBytes
        )

        assertNull(crossed.builtInModel())
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
