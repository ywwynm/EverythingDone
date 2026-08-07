package com.ywwynm.everythingdone.spatial

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialRuntimeCatalogEntryTest {

    @Test
    fun compatibleEntry_acceptsSignedPackageContract() {
        assertTrue(validEntry().isCompatible())
    }

    @Test
    fun compatibleEntry_rejectsUnpackedSizeMismatch() {
        val entry = validEntry()
        assertFalse(entry.copy(unpackedSizeBytes = entry.unpackedSizeBytes + 1).isCompatible())
    }

    @Test
    fun compatibleEntry_rejectsUnknownAbiOrRuntimeVersion() {
        assertFalse(validEntry().copy(abi = "mips").isCompatible())
        assertFalse(validEntry().copy(ortVersion = "1.29.0").isCompatible())
    }

    @Test
    fun compatibleEntry_rejectsRuntimeWithoutAotOperators() {
        assertFalse(validEntry().copy(packageVersion = "1.28.0-r1").isCompatible())
    }

    private fun validEntry(): SpatialRuntimeCatalogEntry =
        SpatialRuntimeCatalogEntry(
            id = SpatialRuntimeStore.RUNTIME_ID,
            packageVersion = SpatialRuntimeStore.REQUIRED_PACKAGE_VERSION,
            ortVersion = SpatialRuntimeStore.ORT_VERSION,
            runtimeApiVersion = SpatialRuntimeStore.RUNTIME_API_VERSION,
            abi = "arm64-v8a",
            url = "https://example.invalid/onnxruntime-arm64-v8a.zip",
            sizeBytes = 12_000_000,
            sha256 = "1".repeat(64),
            unpackedSizeBytes = 20_500_000,
            coreSizeBytes = 20_000_000,
            coreSha256 = "2".repeat(64),
            jniSizeBytes = 500_000,
            jniSha256 = "3".repeat(64),
            license = "MIT",
            enabled = true,
            disabledReason = null
        )
}
