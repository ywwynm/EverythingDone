package com.ywwynm.everythingdone.spatial

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全 arch 运行组件包（D267）的 catalog 校验。
 *
 * 这一档存在的理由是判定表查不到新骁龙时仍要能用，所以「`dspArch = all` 必须被接受」
 * 与「其它任意字符串必须被拒」是同一条规则的两面。
 */
class SpatialQnnRuntimeCatalogEntryTest {

    private fun hex(seed: Char) = seed.toString().repeat(64)

    private fun entry(
        dspArch: String,
        extraFiles: List<SpatialRuntimeExtraFile> = listOf(
            SpatialRuntimeExtraFile("libQnnHtp.so", 100L, hex('a')),
            SpatialRuntimeExtraFile("libQnnHtpV73Skel.so", 200L, hex('b')),
            SpatialRuntimeExtraFile("libQnnHtpV81Skel.so", 300L, hex('c'))
        )
    ) = SpatialQnnRuntimeCatalogEntry(
        id = SpatialRuntimeStore.RUNTIME_ID,
        packageVersion = SpatialRuntimeStore.QNN_PACKAGE_VERSION,
        ortVersion = SpatialRuntimeStore.ORT_VERSION,
        runtimeApiVersion = SpatialRuntimeStore.RUNTIME_API_VERSION,
        abi = SpatialQnnSupport.REQUIRED_ABI,
        dspArch = dspArch,
        url = "https://example.com/a.zip",
        sizeBytes = 1_000L,
        sha256 = hex('d'),
        unpackedSizeBytes = 10L + 5L + extraFiles.sumOf { it.sizeBytes },
        coreSizeBytes = 10L,
        coreSha256 = hex('e'),
        jniSizeBytes = 5L,
        jniSha256 = hex('f'),
        extraFiles = extraFiles,
        license = SpatialQnnRuntimeCatalogEntry.QNN_LICENSE_ID,
        enabled = true,
        disabledReason = null
    )

    @Test
    fun `单 arch 包照旧受支持`() {
        assertTrue(entry("v81").isCompatible())
    }

    @Test
    fun `全 arch 包受支持`() {
        assertTrue(entry(SpatialQnnSupport.ALL_ARCH).isCompatible())
    }

    @Test
    fun `dsp_arch 只能是真实架构或全 arch 标记`() {
        // 该字段会参与目录名与库名推导，放开了等于放开路径。
        assertFalse(entry("../../etc").isCompatible())
        assertFalse(entry("v73;rm -rf").isCompatible())
        assertFalse(entry("").isCompatible())
        assertFalse(entry("unsupported").isCompatible())
        assertFalse(entry("V81").isCompatible())
    }

    @Test
    fun `旧版 App 会拒绝全 arch 条目——所以它必须走独立字段`() {
        // 这条钉的是**为什么** qnnAllArchRuntimes 要独立存在：旧版 App 的 isCompatible()
        // 里 dspArch 必须过 isValidDspArch，而 validateCatalog 对 qnnRuntimes 是硬 check，
        // 混进去会让所有旧版整份拒绝 catalog。isValidDspArch 就是那道判据。
        assertFalse(SpatialQnnSupport.isValidDspArch(SpatialQnnSupport.ALL_ARCH))
    }

    @Test
    fun `全 arch 包的 extraFiles 上限与单 arch 同一套`() {
        // 四档 Skel+Stub 加三个共享库共 11 个，必须仍在 App 的 16 个上限内。
        val eleven = (1..11).map {
            SpatialRuntimeExtraFile("libQnnStub$it.so", it.toLong(), hex('a'))
        }
        assertTrue(entry(SpatialQnnSupport.ALL_ARCH, eleven).isCompatible())
        val seventeen = (1..17).map {
            SpatialRuntimeExtraFile("libQnnStub$it.so", it.toLong(), hex('a'))
        }
        assertFalse(entry(SpatialQnnSupport.ALL_ARCH, seventeen).isCompatible())
    }
}
