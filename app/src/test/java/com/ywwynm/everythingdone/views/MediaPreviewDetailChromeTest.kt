package com.ywwynm.everythingdone.views

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.LayerDrawable
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import com.ywwynm.everythingdone.model.ThingBackground
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaPreviewDetailChromeTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test fun `后台图标保持详情坐标和父层透明度且只补被媒体遮住的部分`() {
        val root = FrameLayout(context)
        val parent = FrameLayout(context).apply { alpha = .5f }
        val icon = View(context).apply { setBackgroundColor(Color.BLACK) }
        val hidden = View(context).apply { setBackgroundColor(Color.RED); visibility = View.INVISIBLE }
        root.addView(parent); parent.addView(icon); parent.addView(hidden)
        root.layout(0, 0, 100, 100); parent.layout(10, 20, 90, 60)
        icon.layout(50, 0, 110, 20); hidden.layout(0, 0, 40, 20)
        // 来源窗口隐藏仍可准备图标，但内容自身隐藏的控件不能进入快照。
        root.visibility = View.INVISIBLE
        val chrome = MediaPreviewDetailChrome.capture(root, listOf(icon, hidden))
        val pixels = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        chrome.draw(Canvas(pixels), RectF(70f, 0f, 100f, 100f))
        assertTrue(Color.red(pixels.getPixel(80, 25)) in 126..129)
        assertEquals(Color.WHITE, pixels.getPixel(65, 25)) // 未被媒体覆盖，留给背景显示。
        assertEquals(Color.WHITE, pixels.getPixel(95, 25)) // 被父容器裁剪。
        assertEquals(Color.WHITE, pixels.getPixel(25, 25)) // 隐藏控件不绘制。
        pixels.eraseColor(Color.WHITE)
        chrome.draw(Canvas(pixels), RectF(70f, 0f, 100f, 100f), RectF(60f, 0f, 80f, 100f))
        assertTrue(Color.red(pixels.getPixel(65, 25)) in 126..129) // 挖空处的图标也能渐显。
        assertTrue(Color.red(pixels.getPixel(75, 25)) in 126..129) // 相交部分只画一次。
        pixels.recycle()
    }

    @Test fun `后台清理结束嵌套波纹且不影响后续新的点击反馈`() {
        val root = FrameLayout(context)
        val view = View(context).apply { isClickable = true }
        val ripple = GradientRippleDrawable(ThingBackground.pure(Color.BLACK), shapeOval = false)
        view.foreground = LayerDrawable(arrayOf(ripple))
        root.addView(view)
        root.layout(0, 0, 100, 100); view.layout(0, 0, 100, 100)
        view.isPressed = true
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(64))
        assertTrue(ripple.feedbackAnimating)
        MediaPreviewDetailChrome.clearTouchFeedback(root)
        assertFalse(view.isPressed)
        assertFalse(ripple.hasVisibleFeedback)
        assertFalse(ripple.feedbackAnimating)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertFalse(ripple.hasVisibleFeedback)
        view.isPressed = true
        assertTrue(ripple.feedbackAnimating)
        MediaPreviewDetailChrome.clearTouchFeedback(root)
    }
}
