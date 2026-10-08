package com.ywwynm.everythingdone.views.particledismiss

import org.junit.Assert.assertEquals
import org.junit.Test

class ParticleFastOptionsTest {
    @Test fun `对照变体在嵌套和异常后恢复且不影响其他线程`() {
        val original = ParticleFastOptions.current
        ParticleFastOptions.withOptions(ParticleFastOptions.BASELINE) {
            assertEquals(ParticleFastOptions.BASELINE, ParticleFastOptions.current)
            var other: ParticleFastOptions? = null
            Thread { other = ParticleFastOptions.current }.apply { start(); join() }
            assertEquals(original, other)
            runCatching {
                ParticleFastOptions.withOptions(original) {
                    assertEquals(original, ParticleFastOptions.current)
                    error("测试异常")
                }
            }
            assertEquals(ParticleFastOptions.BASELINE, ParticleFastOptions.current)
        }
        assertEquals(original, ParticleFastOptions.current)
    }
}
