package com.ywwynm.everythingdone.views.particledismiss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParticleDisplayModesTest {
    private fun mode(id: Int, rate: Float, width: Int = 1368, height: Int = 3192) =
        ParticleDisplayModes.Mode(id, width, height, rate)

    @Test fun `魅族模式列表选择同分辨率的 120 Hz 而非低分辨率模式`() {
        val modes = listOf(mode(1, 120.00001f), mode(2, 60.000004f), mode(3, 120f, 912, 2128))
        assertEquals(1, ParticleDisplayModes.choose(modes, 1368, 3192, 0, 120f))
        assertEquals(3, ParticleDisplayModes.choose(modes, 912, 2128, 0, 120f))
        assertNull(ParticleDisplayModes.choose(modes, 1920, 1080, 0, 120f))
    }

    @Test fun `兼容 60 和 90 和 144 Hz 设备且不随意选最高档`() {
        assertNull(ParticleDisplayModes.choose(listOf(mode(1, 60f)), 1368, 3192, 0, 120f))
        assertEquals(2, ParticleDisplayModes.choose(listOf(mode(1, 60f), mode(2, 90f)), 1368, 3192, 0, 120f))
        assertEquals(3, ParticleDisplayModes.choose(listOf(mode(1, 60f), mode(3, 144f)), 1368, 3192, 0, 120f))
        val modes = listOf(mode(1, 90f), mode(2, 120f), mode(3, 144f))
        assertEquals(2, ParticleDisplayModes.choose(modes, 1368, 3192, 0, 120f))
        assertEquals(3, ParticleDisplayModes.choose(modes, 1368, 3192, 3, 120f))
    }

    @Test fun `重叠 Surface 和重复请求只在最后退出时恢复`() {
        val votes = ParticleDisplayModeVotes<Any>()
        val window = Any(); val first = Any(); val second = Any()
        assertEquals(1, votes.update(window, first, 0, 1))
        assertNull(votes.update(window, first, 1, 1))
        assertNull(votes.update(window, second, 1, 1))
        assertNull(votes.update(window, first, 1, null))
        assertNull(votes.update(window, first, 1, null))
        assertEquals(0, votes.update(window, second, 1, null))
        assertNull(votes.update(window, second, 0, null))
    }

    @Test fun `恢复原有明确模式且不覆盖动画期间其他功能的修改`() {
        val votes = ParticleDisplayModeVotes<Any>()
        val window = Any(); val owner = Any()
        assertEquals(1, votes.update(window, owner, 2, 1))
        assertEquals(2, votes.update(window, owner, 1, null))
        assertEquals(1, votes.update(window, owner, 2, 1))
        assertNull(votes.update(window, owner, 3, null))
        assertEquals(1, votes.update(window, owner, 3, 1))
        assertEquals(3, votes.update(window, owner, 1, null))
    }

    @Test fun `不同窗口互不干扰且已是目标模式无需写回`() {
        val votes = ParticleDisplayModeVotes<Any>()
        val a = Any(); val b = Any(); val owner = Any()
        assertEquals(1, votes.update(a, owner, 0, 1))
        assertNull(votes.update(b, owner, 1, 1))
        assertEquals(0, votes.update(a, owner, 1, null))
        assertNull(votes.update(b, owner, 1, null))
    }
}
