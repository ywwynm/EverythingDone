package com.ywwynm.everythingdone.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialCatalogVerifierTest {

    @Test
    fun legacyModelOnlyStableCatalog_stillVerifiesAndMatchesBothBuiltInModels() {
        val bytes = checkNotNull(
            javaClass.classLoader?.getResourceAsStream("spatial/stable-catalog.json")
        ).use { it.readBytes() }

        val catalog = SpatialCatalogVerifier.verify(bytes)

        assertEquals("stable", catalog.channel)
        assertEquals(2, catalog.models.size)
        // 旧 catalog 只含头两个模型；新增内置模型（如 DA3）不得破坏旧 catalog 的验证。
        assertEquals(
            setOf(
                SpatialDepthModel.ZIPDEPTH,
                SpatialDepthModel.DEPTH_ANYTHING_V2_SMALL
            ),
            catalog.models.mapNotNull { it.builtInModel() }.toSet()
        )
        assertTrue(catalog.runtimes.isNullOrEmpty())
    }

    @Test(expected = java.security.GeneralSecurityException::class)
    fun tamperedPayload_isRejected() {
        val original = checkNotNull(
            javaClass.classLoader?.getResourceAsStream("spatial/stable-catalog.json")
        ).bufferedReader().use { it.readText() }
        val tampered = original.replace(
            "ew0KICAgICJzY2hlbWFWZXJzaW9u",
            "e30KICAgICJzY2hlbWFWZXJzaW9u"
        )

        SpatialCatalogVerifier.verify(tampered.toByteArray(Charsets.UTF_8))
    }
}
