package com.ywwynm.everythingdone.views

import org.junit.Assert.*
import org.junit.Test

class MediaPreviewDismissPolicyTest {
    @Test fun `横向与小抖动不抢走翻页和点击`() {
        assertFalse(MediaPreviewDismissPolicy.vertical(2f, 5f, 8f))
        assertFalse(MediaPreviewDismissPolicy.vertical(100f, 80f, 8f))
        assertFalse(MediaPreviewDismissPolicy.vertical(100f, 120f, 8f))
        assertTrue(MediaPreviewDismissPolicy.vertical(40f, -100f, 8f))
    }

    @Test fun `先消耗放大图片的平移距离再交接`() {
        assertEquals(0f, MediaPreviewDismissPolicy.beyondEdge(80f, -100f, 1100f, 800f), .001f)
        assertEquals(20f, MediaPreviewDismissPolicy.beyondEdge(120f, -100f, 1100f, 800f), .001f)
        assertEquals(0f, MediaPreviewDismissPolicy.beyondEdge(-80f, -300f, 900f, 800f), .001f)
        assertEquals(-20f, MediaPreviewDismissPolicy.beyondEdge(-120f, -300f, 900f, 800f), .001f)
    }

    @Test fun `在边缘往图片内部拖动仍用于平移`() {
        assertEquals(0f, MediaPreviewDismissPolicy.beyondEdge(-100f, 0f, 1600f, 800f), .001f)
        assertEquals(0f, MediaPreviewDismissPolicy.beyondEdge(100f, -800f, 800f, 800f), .001f)
    }

    @Test fun `高度适屏或不足一屏时上下均可接续返回`() {
        for (dy in listOf(-100f, 100f)) {
            assertEquals(dy, MediaPreviewDismissPolicy.beyondEdge(dy, 200f, 600f, 800f), .001f)
            assertEquals(dy, MediaPreviewDismissPolicy.beyondEdge(dy, 0f, 800f, 800f), .001f)
        }
    }

    @Test fun `上下缩放对称且回拖精确恢复`() {
        for (distance in listOf(0f, 16f, 96f, 300f, 800f, 2000f)) {
            val up = MediaPreviewDismissPolicy.progress(-distance, 800f)
            val down = MediaPreviewDismissPolicy.progress(distance, 800f)
            assertEquals(up, down, 0f)
            assertTrue(MediaPreviewDismissPolicy.scale(up) in .64f..1f)
        }
        assertEquals(1f, MediaPreviewDismissPolicy.scale(MediaPreviewDismissPolicy.progress(0f, 800f)), 0f)
    }

    @Test fun `慢拖到足够距离才返回`() {
        for (direction in listOf(-1f, 1f)) {
            assertFalse(MediaPreviewDismissPolicy.shouldDismiss(30f * direction, 0f, 0f, 800f, 1f))
            assertTrue(MediaPreviewDismissPolicy.shouldDismiss(160f * direction, 0f, 0f, 800f, 1f))
        }
    }

    @Test fun `短距离快速甩动要求有足够位移且方向向外`() {
        for (direction in listOf(-1f, 1f)) {
            assertTrue(MediaPreviewDismissPolicy.shouldDismiss(40f * direction, 20f, 1600f * direction, 800f, 1f))
            assertFalse(MediaPreviewDismissPolicy.shouldDismiss(5f * direction, 0f, 3000f * direction, 800f, 1f))
            assertFalse(MediaPreviewDismissPolicy.shouldDismiss(40f * direction, 0f, -1600f * direction, 800f, 1f))
            assertFalse(MediaPreviewDismissPolicy.shouldDismiss(40f * direction, 2000f, 1600f * direction, 800f, 1f))
        }
    }

    @Test fun `超过阈值后明显回甩仍然取消`() {
        assertFalse(MediaPreviewDismissPolicy.shouldDismiss(200f, 0f, -1000f, 800f, 1f))
        assertFalse(MediaPreviewDismissPolicy.shouldDismiss(-200f, 0f, 1000f, 800f, 1f))
    }

    @Test fun `相同物理手势在不同密度屏幕判定一致`() {
        for (density in listOf(1f, 2f, 3.5f)) {
            assertTrue(MediaPreviewDismissPolicy.shouldDismiss(40f * density, 0f, 1600f * density, 800f * density, density))
            assertFalse(MediaPreviewDismissPolicy.shouldDismiss(40f * density, 0f, 400f * density, 800f * density, density))
        }
    }
}
