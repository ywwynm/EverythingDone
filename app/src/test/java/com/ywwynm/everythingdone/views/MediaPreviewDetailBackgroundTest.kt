package com.ywwynm.everythingdone.views

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.View
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
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaPreviewDetailBackgroundTest {
    @Test fun `八个方向的渐变保持根容器坐标且捕获不修改原背景`() {
        val context = RuntimeEnvironment.getApplication()
        val root = FrameLayout(context)
        val note = View(context)
        root.addView(note)
        root.layout(0, 0, 240, 480); note.layout(20, 40, 220, 440)
        val hole = RectF(40f, 120f, 180f, 260f)
        for (orientation in GradientDrawable.Orientation.values()) {
            val original = GradientDrawable(orientation, intArrayOf(Color.RED, Color.BLUE))
            original.setBounds(0, 0, note.width, note.height)
            note.background = original
            val before = Rect(original.bounds)
            val captured = MediaPreviewDetailBackground.capture(note, root)!!
            val expected = Bitmap.createBitmap(240, 480, Bitmap.Config.ARGB_8888)
            Canvas(expected).apply { translate(20f, 40f); original.draw(this) }
            val actual = Bitmap.createBitmap(240, 480, Bitmap.Config.ARGB_8888)
            // 后台窗口隐藏时仍保留原布局；绘制范围不能改变渐变端点。
            root.visibility = View.INVISIBLE
            captured.draw(Canvas(actual), hole)
            for (y in 120 until 260 step 16) for (x in 40 until 180 step 16)
                assertEquals("$orientation ($x,$y)", expected.getPixel(x, y), actual.getPixel(x, y))
            assertEquals(Color.TRANSPARENT, actual.getPixel(25, 60))
            captured.draw(Canvas(actual), hole, .4f)
            assertEquals(before, original.bounds)
            assertEquals(255, original.alpha)
            expected.recycle(); actual.recycle()
        }
    }

    @Test fun `返回键渐显不会修改缓存底色且渐变副本与来源独立`() {
        val context = RuntimeEnvironment.getApplication()
        val note = View(context).apply { layout(0, 0, 100, 200) }
        val original = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.RED, Color.BLUE))
        note.background = original
        val captured = MediaPreviewDetailBackground.capture(note, note)!!
        original.colors = intArrayOf(Color.GREEN, Color.GREEN)
        val pixels = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(pixels)
        val area = RectF(0f, 0f, 100f, 200f)
        captured.draw(canvas, area, .5f)
        assertEquals(128, Color.alpha(pixels.getPixel(50, 100)))
        assertEquals(0, Color.green(pixels.getPixel(50, 100)))
        pixels.eraseColor(Color.TRANSPARENT)
        captured.draw(canvas, area)
        assertEquals(255, Color.alpha(pixels.getPixel(50, 100)))
        assertEquals(0, Color.green(pixels.getPixel(50, 100)))
        pixels.recycle()
    }
}
