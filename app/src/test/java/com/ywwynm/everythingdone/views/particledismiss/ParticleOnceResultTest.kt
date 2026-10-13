package com.ywwynm.everythingdone.views.particledismiss

import org.junit.Assert.assertEquals
import org.junit.Test

class ParticleOnceResultTest {
    @Test fun `窗口提交晚于超时时不再执行成功交接`() {
        val results = mutableListOf<Boolean>()
        val completion = ParticleOnceResult { results += it }
        completion.finish(false)
        completion.finish(true)
        assertEquals(listOf(false), results)
    }

    @Test fun `成功提交后超时和重复提交不再次恢复控件`() {
        val results = mutableListOf<Boolean>()
        val completion = ParticleOnceResult { results += it }
        completion.finish(true)
        completion.finish(false)
        completion.finish(true)
        assertEquals(listOf(true), results)
    }

    @Test fun `回调重入不会再次交付`() {
        var calls = 0
        lateinit var completion: ParticleOnceResult
        completion = ParticleOnceResult { calls++; completion.finish(false) }
        completion.finish(true)
        assertEquals(1, calls)
    }
}
