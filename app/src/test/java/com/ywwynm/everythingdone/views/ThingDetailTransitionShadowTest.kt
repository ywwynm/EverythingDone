package com.ywwynm.everythingdone.views

import android.app.Application
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class ThingDetailTransitionShadowTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun card(): Pair<FrameLayout, View> {
        val host = FrameLayout(context).apply { layout(0, 0, 400, 800) }
        val card = View(context).apply {
            elevation = 8f
            translationZ = 2f
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 16f)
                    outline.alpha = .6f
                }
            }
        }
        host.addView(card)
        card.layout(40, 80, 240, 280)
        return host to card
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `捕获实际投影高度颜色且不改动卡片`() {
        val (host, card) = card()
        card.outlineAmbientShadowColor = Color.RED
        card.outlineSpotShadowColor = Color.BLUE
        val style = requireNotNull(ThingDetailTransitionShadow.Style.capture(card, host))
        assertEquals(10f, style.elevation)
        assertEquals(.6f, style.alpha)
        assertEquals(Color.RED, style.ambient)
        assertEquals(Color.BLUE, style.spot)
        assertEquals(8f, card.elevation)
        assertEquals(2f, card.translationZ)
        assertEquals(RectF(0f, 0f, 400f, 800f), style.clip)
    }

    @Test fun `阴影可超出卡片但保留父级裁剪`() {
        val (host, card) = card()
        host.setPadding(20, 60, 30, 50)
        val style = requireNotNull(ThingDetailTransitionShadow.Style.capture(card, host))
        assertEquals(RectF(20f, 60f, 370f, 750f), style.clip)
        assertTrue(style.clip.left < card.left)
        assertTrue(style.clip.bottom > card.bottom)
    }

    @Test fun `无高度或透明轮廓不凭空生成阴影`() {
        val (host, card) = card()
        card.elevation = 0f
        card.translationZ = 0f
        assertNull(ThingDetailTransitionShadow.Style.capture(card, host))
        card.elevation = 8f
        card.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) { outline.setEmpty() }
        }
        assertNull(ThingDetailTransitionShadow.Style.capture(card, host))
    }

    @Test fun `投影轮廓跟随当前几何并允许回到零高度`() {
        val (host, card) = card()
        val style = requireNotNull(ThingDetailTransitionShadow.Style.capture(card, host))
        val shadow = ThingDetailTransitionShadow(context)
        val rect = RectF(20f, 40f, 320f, 540f)
        shadow.update(rect, 12f, style, 5f)
        val outline = Outline()
        shadow.outlineProvider.getOutline(shadow, outline)
        val actual = Rect()
        assertTrue(outline.getRect(actual))
        assertEquals(Rect(0, 0, 300, 500), actual)
        assertEquals(20, shadow.left)
        assertEquals(40, shadow.top)
        assertEquals(12f, outline.radius)
        assertEquals(.6f, outline.alpha)
        assertEquals(5f, shadow.elevation)
        shadow.update(RectF(0f, 0f, 400f, 800f), 0f, style, 0f)
        assertEquals(0f, shadow.elevation)
    }

    @Test @Config(sdk = [26]) fun `旧系统沿用黑色原生投影`() {
        val (host, card) = card()
        val style = requireNotNull(ThingDetailTransitionShadow.Style.capture(card, host))
        assertEquals(Color.BLACK, style.ambient)
        assertEquals(Color.BLACK, style.spot)
        ThingDetailTransitionShadow(context).update(RectF(40f, 80f, 240f, 280f), 16f, style, 10f)
    }
}
