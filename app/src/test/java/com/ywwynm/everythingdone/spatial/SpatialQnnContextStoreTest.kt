package com.ywwynm.everythingdone.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialQnnContextStoreTest {

    private fun key(
        modelId: String = "rf_detr_seg_nano",
        modelVersion: String = "1.0.0",
        modelSha256: String = "a".repeat(64),
        shapeTag: String = "312x312",
        dspArch: String = "v73",
        runtimePackageVersion: String = "1.28.0-q1"
    ) = SpatialQnnContextStore.Key(
        modelId = modelId,
        modelVersion = modelVersion,
        modelSha256 = modelSha256,
        shapeTag = shapeTag,
        dspArch = dspArch,
        runtimePackageVersion = runtimePackageVersion
    )

    @Test
    fun `key 的每一维都进目录名`() {
        val base = SpatialQnnContextStore.directoryName(key())
        // 换任一维都必须落到不同目录，否则会拿旧产物去跑新组合。
        assertNotEquals(base, SpatialQnnContextStore.directoryName(key(modelId = "edgetam")))
        assertNotEquals(base, SpatialQnnContextStore.directoryName(key(modelVersion = "1.0.1")))
        assertNotEquals(base, SpatialQnnContextStore.directoryName(key(shapeTag = "512x512")))
        assertNotEquals(base, SpatialQnnContextStore.directoryName(key(dspArch = "v75")))
        assertNotEquals(
            base,
            SpatialQnnContextStore.directoryName(key(runtimePackageVersion = "1.28.0-q2"))
        )
        assertNotEquals(
            base,
            SpatialQnnContextStore.directoryName(key(modelSha256 = "b".repeat(64)))
        )
    }

    @Test
    fun `同一 key 的目录名稳定`() {
        assertEquals(
            SpatialQnnContextStore.directoryName(key()),
            SpatialQnnContextStore.directoryName(key())
        )
    }

    @Test
    fun `路径分隔符被清除`() {
        val name = SpatialQnnContextStore.directoryName(key(modelId = "rf/detr"))
        assertFalse(name.contains('/'))
        assertFalse(name.contains('\\'))
        assertTrue(name.startsWith("rfdetr-"))
    }

    @Test(expected = IllegalStateException::class)
    fun `上跳序列被拒绝而不是清洗后继续`() {
        // '.' 必须保留给 1.0.0 这种版本号，所以过滤挡不住 ".."，只能显式失败。
        SpatialQnnContextStore.directoryName(key(shapeTag = ".."))
    }

    @Test(expected = IllegalStateException::class)
    fun `清洗后只剩点号的字段被拒绝`() {
        SpatialQnnContextStore.directoryName(key(modelId = "/./"))
    }

    @Test(expected = IllegalStateException::class)
    fun `key 字段全为非法字符时拒绝生成目录名`() {
        SpatialQnnContextStore.directoryName(key(shapeTag = "///"))
    }

    @Test(expected = IllegalStateException::class)
    fun `key 字段为空时拒绝生成目录名`() {
        SpatialQnnContextStore.directoryName(key(modelVersion = "  "))
    }

    @Test
    fun `模型字节变化会换目录即使 id 与 version 不变`() {
        // 模型重新发布但版本号忘了升的情形：context binary 必须跟着作废。
        val a = SpatialQnnContextStore.directoryName(key(modelSha256 = "1".repeat(64)))
        val b = SpatialQnnContextStore.directoryName(key(modelSha256 = "2".repeat(64)))
        assertNotEquals(a, b)
    }

    @Test
    fun `不同 dsp_arch 不共用产物`() {
        // context binary 按 dsp_arch 绑定（D215），v73 的产物在 v75 上不可用。
        val arches = listOf("v68", "v69", "v73", "v75", "v79", "v81")
        val names = arches.map { SpatialQnnContextStore.directoryName(key(dspArch = it)) }
        assertEquals(names.size, names.distinct().size)
    }

    @Test
    fun `目录名只含文件系统安全字符`() {
        val name = SpatialQnnContextStore.directoryName(key())
        assertTrue(name.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' })
    }
}
