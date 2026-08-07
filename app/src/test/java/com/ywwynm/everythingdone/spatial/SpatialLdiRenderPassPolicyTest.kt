package com.ywwynm.everythingdone.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialLdiRenderPassPolicyTest {

    @Test
    fun `隐藏背景只能填充颜色不能占用深度`() {
        val state = SpatialLdiRenderPassPolicy.HIDDEN_BACKGROUND

        assertFalse(state.depthTest)
        assertFalse(state.depthWrite)
        assertEquals(SpatialLdiDepthFunction.LESS_OR_EQUAL, state.depthFunction)
    }

}
