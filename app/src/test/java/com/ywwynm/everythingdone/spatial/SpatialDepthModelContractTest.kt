package com.ywwynm.everythingdone.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialDepthModelContractTest {

    @Test
    fun `DA3 Small只声明depth数值方向而不冒充metric型号`() {
        val model = SpatialDepthModel.DEPTH_ANYTHING_3_SMALL

        assertTrue(model.outputIsDepth)
        assertFalse(model.providesMetricScale)
        assertFalse(model.displayName.contains("Metric", ignoreCase = true))
    }

    /**
     * 原来这条断言的是"当前没有任何模型声明真实尺度"——那编码的是**当时的世界状态**，
     * 不是不变量；MoGe-2 上端后（D205）它必然失败。真正要守住的是：**只有能反解相机
     * 内参的那类输出契约才允许声明米制尺度**，相对深度模型一律不得冒充。
     */
    @Test
    fun `只有能反解内参的契约才允许声明真实尺度`() {
        for (model in SpatialDepthModel.entries) {
            if (model.providesMetricScale) {
                assertEquals(
                    "${model.stableId} 声明了米制尺度，但它的输出契约反解不出内参",
                    SpatialDepthOutputContract.MOGE_POINT_MAP,
                    model.outputContract
                )
            }
        }
    }

    @Test
    fun `相对深度模型一律不声明真实尺度`() {
        val relative = SpatialDepthModel.entries.filter {
            it.outputContract == SpatialDepthOutputContract.SINGLE_MAP
        }
        assertTrue("单图契约的模型应当不止一个", relative.size >= 3)
        assertTrue(
            "以下相对深度模型错误地声明了米制尺度：" +
                relative.filter { it.providesMetricScale }.map { it.stableId },
            relative.none { it.providesMetricScale }
        )
    }

    @Test
    fun `每个模型的ABI标识两两可区分`() {
        val models = SpatialDepthModel.entries
        assertEquals(models.size, models.map { it.stableId }.distinct().size)
        assertEquals(models.size, models.map { it.fileName }.distinct().size)
        assertEquals(models.size, models.map { it.sha256.lowercase() }.distinct().size)
    }
}
