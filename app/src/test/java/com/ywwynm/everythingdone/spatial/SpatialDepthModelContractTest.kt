package com.ywwynm.everythingdone.spatial

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

    @Test
    fun `当前可下载深度模型均不声明真实尺度`() {
        assertTrue(SpatialDepthModel.entries.none { it.providesMetricScale })
    }
}
