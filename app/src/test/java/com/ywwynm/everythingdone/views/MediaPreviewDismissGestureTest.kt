package com.ywwynm.everythingdone.views

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.github.chrisbanes.photoview.PhotoView
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.GraphicsMode

/** 用真实 PhotoView 分发事件，验证边缘接管及矩阵恢复，而不是仅测试手势公式。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class, qualifiers = "w400dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class MediaPreviewDismissGestureTest {
    private lateinit var owner: ActivityController<Activity>
    private lateinit var activity: Activity
    private lateinit var host: FrameLayout
    private lateinit var pager: FrameLayout
    private lateinit var page: FrameLayout
    private lateinit var photo: PhotoView
    private lateinit var chrome: View
    private lateinit var gesture: MediaPreviewDismissGesture
    private var enabled = true
    private var excluded = false
    private var commits = 0
    private var cancels = 0
    private var eventTime = 0L
    private var downTime = 0L
    private var touchX = 0f
    private var touchY = 0f
    private val actions = mutableListOf<Int>()
    private var videoBounds: RectF? = null
    private var backdrop: Bitmap? = null
    private var backgroundMask: RectF? = null
    private var committed: () -> Unit = {}

    @Before fun setup() {
        owner = Robolectric.buildActivity(Activity::class.java)
        activity = owner.get()
        activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        owner.setup().visible()
        host = FrameLayout(activity)
        pager = FrameLayout(activity)
        page = FrameLayout(activity)
        photo = PhotoView(activity)
        photo.setScaleLevels(1f, 3f, 6f)
        photo.setImageBitmap(Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) })
        chrome = View(activity).apply { alpha = .8f }
        page.addView(photo, FrameLayout.LayoutParams(-1, -1))
        pager.addView(page, FrameLayout.LayoutParams(-1, -1))
        host.addView(pager, FrameLayout.LayoutParams(-1, -1))
        activity.setContentView(host)
        layout()
        gesture = MediaPreviewDismissGesture(activity, pager, { photo }, { enabled }, { excluded },
            { videoBounds?.let(::RectF) ?: it.displayRect?.let(::RectF) }, { backdrop }, { listOf(chrome) },
            { event ->
                cancels++
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                content(cancel)
                cancel.recycle()
            }, { commits++; committed() }, { backgroundMask },
            { MediaPreviewDetailBackground.capture(host, activity.window.decorView) })
        eventTime = SystemClock.uptimeMillis()
    }

    @After fun cleanup() {
        MediaPreviewTransition.diagnosticInteractiveFrame = null
        gesture.close()
        owner.pause().stop().destroy()
    }

    private fun layout() {
        activity.window.decorView.apply {
            measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            layout(0, 0, 400, 800)
        }
    }

    private fun content(event: MotionEvent): Boolean {
        actions += event.actionMasked
        val bounds = ThingDetailTransition.bounds(photo, activity.window.decorView)
        val local = MotionEvent.obtain(event).apply { offsetLocation(-bounds.left, -bounds.top) }
        val result = photo.dispatchTouchEvent(local)
        local.recycle()
        return result
    }

    private fun down() {
        val bounds = ThingDetailTransition.bounds(photo, activity.window.decorView)
        touchX = bounds.centerX(); touchY = bounds.centerY()
        send(MotionEvent.ACTION_DOWN, touchX, touchY)
    }

    private fun send(action: Int, x: Float, y: Float, elapsed: Long = 100) {
        eventTime += elapsed
        if (action == MotionEvent.ACTION_DOWN) downTime = eventTime
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        gesture.dispatch(event, ::content)
        event.recycle()
    }

    private fun finishRebound() { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350)) }

    private fun secondFinger() {
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0 },
            MotionEvent.PointerProperties().apply { id = 1 })
        val coordinates = arrayOf(MotionEvent.PointerCoords().apply { x = touchX; y = touchY + 40f },
            MotionEvent.PointerCoords().apply { x = touchX + 50f; y = touchY + 40f })
        eventTime += 16
        val event = MotionEvent.obtain(downTime, eventTime,
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2, properties, coordinates, 0, 0, 1f, 1f, 0, 0, 0, 0)
        gesture.dispatch(event, ::content)
        event.recycle()
    }

    @Test fun `默认大小上下均接管且保持原 PhotoView 矩阵`() {
        for (direction in listOf(-1f, 1f)) {
            down()
            val before = RectF(photo.displayRect)
            send(MotionEvent.ACTION_MOVE, touchX, touchY + 40f * direction)
            assertTrue(gesture.isActive)
            assertTrue(page.scaleX < 1f)
            assertEquals(page.scaleX, page.scaleY, 0f)
            assertEquals(before, photo.displayRect)
            assertTrue(page.translationY * direction > 0)
            send(MotionEvent.ACTION_CANCEL, touchX, touchY + 40f * direction)
            finishRebound()
            assertFalse(gesture.isActive)
        }
        assertEquals(2, cancels)
        assertEquals(0, commits)
    }

    @Test fun `放大后先平移到边缘再接续返回且回弹保留倍率和边缘位置`() {
        photo.setScale(3f, false)
        down()
        val edgeDistance = -photo.displayRect.top
        assertTrue(edgeDistance > 40f)
        send(MotionEvent.ACTION_MOVE, touchX, touchY + edgeDistance / 2)
        assertFalse(gesture.isActive)
        send(MotionEvent.ACTION_MOVE, touchX, touchY + edgeDistance + 32f)
        assertTrue(gesture.isActive)
        val atEdge = RectF(photo.displayRect)
        assertEquals(0f, atEdge.top, 1f)
        assertEquals(3f, photo.scale, .001f)
        send(MotionEvent.ACTION_UP, touchX, touchY + edgeDistance + 32f, 500)
        finishRebound()
        assertFalse(gesture.isActive)
        assertEquals(0, commits)
        assertEquals(3f, photo.scale, .001f)
        assertEquals(atEdge, photo.displayRect)
        assertEquals(1f, page.scaleX, 0f)
        assertEquals(0f, page.translationY, 0f)
        assertNull(page.clipBounds)
        assertEquals(.8f, chrome.alpha, 0f)
        assertEquals(1, host.childCount)
    }

    @Test fun `横向手势始终交给原控件而不在后段变为返回`() {
        down()
        send(MotionEvent.ACTION_MOVE, touchX + 60f, touchY + 5f)
        send(MotionEvent.ACTION_MOVE, touchX + 65f, touchY + 180f)
        send(MotionEvent.ACTION_UP, touchX + 65f, touchY + 180f)
        assertFalse(gesture.isActive)
        assertEquals(0, cancels)
        assertEquals(0, commits)
        assertEquals(MotionEvent.ACTION_UP, actions.last())
    }

    @Test fun `禁用及顶部控件不会被垂直返回接管`() {
        for (blockByControl in listOf(false, true)) {
            enabled = blockByControl
            excluded = blockByControl
            down()
            send(MotionEvent.ACTION_MOVE, touchX, touchY + 200f)
            send(MotionEvent.ACTION_UP, touchX, touchY + 200f)
            assertFalse(gesture.isActive)
        }
        assertEquals(0, cancels)
        assertEquals(0, commits)
    }

    @Test fun `足够距离松手从当前缩小位置提交且不会回到全屏`() {
        down()
        send(MotionEvent.ACTION_MOVE, touchX, touchY + 40f)
        send(MotionEvent.ACTION_MOVE, touchX + 8f, touchY + 240f)
        val scale = page.scaleX
        val translation = page.translationY
        send(MotionEvent.ACTION_UP, touchX + 8f, touchY + 240f, 500)
        assertEquals(1, commits)
        assertTrue(gesture.isActive)
        assertEquals(scale, page.scaleX, 0f)
        assertEquals(translation, page.translationY, 0f)
        assertEquals(MotionEvent.ACTION_CANCEL, actions.last())
    }

    @Test fun `向回拖动恢复大小和背景且轻放后完整恢复`() {
        down()
        send(MotionEvent.ACTION_MOVE, touchX, touchY + 40f)
        send(MotionEvent.ACTION_MOVE, touchX, touchY + 160f)
        val smaller = page.scaleX
        send(MotionEvent.ACTION_MOVE, touchX, touchY + 30f)
        assertTrue(page.scaleX > smaller)
        send(MotionEvent.ACTION_UP, touchX, touchY + 30f, 500)
        finishRebound()
        assertEquals(0, commits)
        assertEquals(1f, page.scaleX, 0f)
        assertEquals(1, host.childCount)
    }

    @Test fun `实际媒体范围约束裁剪并随视频画幅变化更新`() {
        down()
        send(MotionEvent.ACTION_MOVE, touchX, touchY + 40f)
        val photoClip = RectF(page.clipBounds)
        videoBounds = RectF(0f, 260f, photo.width.toFloat(), 480f)
        activity.window.decorView.viewTreeObserver.dispatchOnPreDraw()
        assertEquals(RectF(videoBounds), RectF(page.clipBounds))
        assertNotEquals(photoClip, RectF(page.clipBounds))
        videoBounds = null
        activity.window.decorView.viewTreeObserver.dispatchOnPreDraw()
        assertEquals(photoClip, RectF(page.clipBounds))
    }

    @Test fun `返回几何包含父层缩放且保持纵横比`() {
        val initial = MediaPreviewTransition.fullPhotoRect(photo, activity.window.decorView)!!
        down()
        send(MotionEvent.ACTION_MOVE, touchX, touchY + 100f)
        val transformed = MediaPreviewTransition.fullPhotoRect(photo, activity.window.decorView)!!
        assertEquals(initial.width() * page.scaleX, transformed.width(), .01f)
        assertEquals(initial.height() * page.scaleY, transformed.height(), .01f)
        assertTrue(transformed.centerY() > initial.centerY())
    }

    @Test fun `资源替换会撤销返回而不覆盖新图片`() {
        down()
        send(MotionEvent.ACTION_MOVE, touchX, touchY + 100f)
        photo.setImageBitmap(Bitmap.createBitmap(120, 240, Bitmap.Config.ARGB_8888))
        val replacement = photo.drawable
        activity.window.decorView.viewTreeObserver.dispatchOnPreDraw()
        assertFalse(gesture.isActive)
        assertSame(replacement, photo.drawable)
        assertEquals(1f, page.scaleX, 0f)
        send(MotionEvent.ACTION_UP, touchX, touchY + 100f)
        assertEquals(0, commits)
    }

    @Test fun `销毁期间清除背景和外层姿态`() {
        down()
        send(MotionEvent.ACTION_MOVE, touchX, touchY + 100f)
        gesture.close()
        assertFalse(gesture.isActive)
        assertEquals(1f, page.scaleX, 0f)
        assertEquals(0f, page.translationY, 0f)
        assertEquals(1, host.childCount)
    }

    @Test fun `双指起手保持原缩放手势且单指留下后不接管`() {
        down()
        secondFinger()
        assertEquals(MotionEvent.ACTION_POINTER_DOWN, actions.last())
        send(MotionEvent.ACTION_MOVE, touchX, touchY + 160f)
        send(MotionEvent.ACTION_UP, touchX, touchY + 160f)
        assertFalse(gesture.isActive)
        assertEquals(0, cancels)
        assertEquals(0, commits)
    }

    @Test fun `拖动中加入第二指回弹且余下事件不交给已取消的控件`() {
        down()
        send(MotionEvent.ACTION_MOVE, touchX, touchY + 40f)
        assertTrue(gesture.isActive)
        secondFinger()
        val count = actions.size
        finishRebound()
        send(MotionEvent.ACTION_MOVE, touchX, touchY + 200f)
        send(MotionEvent.ACTION_UP, touchX, touchY + 200f)
        assertFalse(gesture.isActive)
        assertEquals(count, actions.size)
        assertEquals(0, commits)
        down()
        send(MotionEvent.ACTION_MOVE, touchX, touchY + 40f)
        assertTrue(gesture.isActive)
    }

    @Test fun `缺失旧手势尾事件的中断不吞掉下一次操作`() {
        down()
        send(MotionEvent.ACTION_MOVE, touchX, touchY + 80f)
        gesture.cancelImmediately()
        assertFalse(gesture.isActive)
        down()
        send(MotionEvent.ACTION_MOVE, touchX, touchY - 80f)
        assertTrue(gesture.isActive)
        assertTrue(page.translationY < 0f)
    }

    @Test fun `短距离快速甩动也能提交`() {
        down()
        send(MotionEvent.ACTION_MOVE, touchX, touchY + 12f, 16)
        send(MotionEvent.ACTION_MOVE, touchX, touchY + 40f, 16)
        send(MotionEvent.ACTION_MOVE, touchX, touchY + 70f, 16)
        send(MotionEvent.ACTION_UP, touchX, touchY + 90f, 16)
        assertEquals(1, commits)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `详情背景随拖动露出且向回拖时变暗`() {
        backdrop = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        down()
        send(MotionEvent.ACTION_MOVE, touchX, touchY + 40f)
        val backgroundView = host.getChildAt(0)
        backgroundView.layout(0, 0, host.width, host.height)
        fun green(): Int {
            val pixels = Bitmap.createBitmap(host.width, host.height, Bitmap.Config.ARGB_8888)
            backgroundView.draw(Canvas(pixels))
            return Color.green(pixels.getPixel(host.width / 2, host.height / 2))
        }
        val near = green()
        send(MotionEvent.ACTION_MOVE, touchX, touchY + 160f)
        val far = green()
        send(MotionEvent.ACTION_MOVE, touchX, touchY + 30f)
        val back = green()
        assertTrue(near > 0)
        assertTrue(far > near)
        assertTrue(back < near)
        send(MotionEvent.ACTION_UP, touchX, touchY + 30f, 500)
        finishRebound()
        assertEquals(1, host.childCount)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `拖动时原附件显示记事底色且完整快照保留用于最终交接`() {
        host.setBackgroundColor(Color.BLUE)
        backdrop = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        backgroundMask = RectF(40f, 100f, 160f, 200f)
        down()
        send(MotionEvent.ACTION_MOVE, touchX, touchY + 120f)
        val backgroundView = host.getChildAt(0)
        backgroundView.layout(0, 0, host.width, host.height)
        val pixels = Bitmap.createBitmap(host.width, host.height, Bitmap.Config.ARGB_8888)
        backgroundView.draw(Canvas(pixels))
        val offset = ThingDetailTransition.bounds(backgroundView, activity.window.decorView)
        val hole = pixels.getPixel((100 - offset.left).toInt(), (150 - offset.top).toInt())
        val outside = pixels.getPixel((220 - offset.left).toInt(), (150 - offset.top).toInt())
        assertTrue(Color.green(outside) > 0)
        assertEquals("空区应与其余详情使用相同显露程度的记事底色", Color.green(outside), Color.blue(hole))
        assertEquals(0, Color.red(hole) + Color.green(hole))
        assertEquals(Color.GREEN, backdrop!!.getPixel(100, 150))
        pixels.recycle()
        gesture.cancelImmediately()
        assertEquals(1, host.childCount)
    }

    @Test fun `确认返回后立即承接向外速度并在原窗口收回`() {
        val destination = RectF(20f, 40f, 220f, 240f)
        var finished = 0
        val frames = mutableListOf<Pair<Float, RectF>>()
        val elapsed = mutableListOf<Float>()
        MediaPreviewTransition.diagnosticInteractiveFrame = { p, time, _, _ ->
            frames += p to MediaPreviewTransition.fullPhotoRect(photo, activity.window.decorView)!!
            elapsed += time
        }
        committed = {
            assertTrue(gesture.animateReturn(destination, destination, targetMedia(destination), destination) { finished++ })
        }
        down()
        send(MotionEvent.ACTION_MOVE, touchX, touchY + 20f, 16)
        send(MotionEvent.ACTION_MOVE, touchX, touchY + 60f, 16)
        send(MotionEvent.ACTION_UP, touchX, touchY + 90f, 16)
        val released = MediaPreviewTransition.fullPhotoRect(photo, activity.window.decorView)!!
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
        // Robolectric 会在推进主循环时连续处理嵌套帧回调，因此断言逐帧记录而非 idleFor 返回时的终态。
        val continued = frames.first { it.first > 0f }.second
        assertTrue("$released -> $continued", continued.centerY() > released.centerY())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        val endpoint = MediaPreviewTransition.fullPhotoRect(photo, activity.window.decorView)!!
        assertEquals(destination.centerX(), endpoint.centerX(), .01f)
        assertEquals(destination.centerY(), endpoint.centerY(), .01f)
        assertEquals(destination.width(), endpoint.width(), .01f)
        assertEquals(1, finished)
        assertTrue(elapsed.last() <= 360f)
        assertEquals(0f, page.alpha, 0f)
        gesture.onPause()
        assertEquals(1, finished)
        assertEquals(0f, page.alpha, 0f)
        gesture.close()
        assertEquals(1f, page.alpha, 0f)
        assertEquals(1f, page.scaleX, 0f)
        assertEquals(1, host.childCount)
    }

    @Test fun `返回中切到后台只完成一次且端点保持遮盖`() {
        var finished = 0
        committed = {
            val end = RectF(20f, 40f, 220f, 240f)
            gesture.animateReturn(end, end, targetMedia(end), end) { finished++ }
        }
        down()
        send(MotionEvent.ACTION_MOVE, touchX, touchY + 200f)
        send(MotionEvent.ACTION_UP, touchX, touchY + 200f, 500)
        gesture.onPause()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals(1, finished)
        assertEquals(0f, page.alpha, 0f)
    }

    @Test fun `后台详情窗口仍能提供目标位置且屏外附件不生成目标`() {
        val root = activity.window.decorView
        val visible = MediaPreviewTransition.laidOutDetailRect(photo, root)!!
        root.visibility = View.INVISIBLE
        assertFalse(photo.isShown)
        assertEquals(visible, MediaPreviewTransition.laidOutDetailRect(photo, root))
        page.translationY = root.height.toFloat()
        assertNull(MediaPreviewTransition.laidOutDetailRect(photo, root))
        root.visibility = View.VISIBLE
    }

    private fun targetMedia(bounds: RectF) = ImageView(activity).apply {
        setImageBitmap(Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) })
        layout(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt())
    }

    @Test fun `高速缩回很小的目标时尺寸仍保持正值`() {
        val start = MediaPreviewTransition.fullPhotoRect(photo, activity.window.decorView)!!
        val target = RectF(120f, 200f, 124f, 204f)
        val widths = mutableListOf<Float>()
        MediaPreviewTransition.diagnosticInteractiveFrame = { _, _, image, _ -> widths += image.width() }
        val animation = MediaPreviewReturnAnimation(activity.window.decorView as FrameLayout,
            pager, page, RectF(photo.displayRect), start, start, target, target,
            targetMedia(target), target, 0f, 0f, -60000f, {}, {})
        animation.start()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertTrue("最小宽度 ${widths.min()}", widths.all { it >= target.width() * .94f - .001f })
        animation.close()
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `返回期间固定图标经历半透明帧且不随媒体移动`() {
        val start = MediaPreviewTransition.fullPhotoRect(photo, host)!!
        val end = RectF(start).apply { offset(0f, -100f) }
        val detail = FrameLayout(activity)
        val icon = View(activity).apply { setBackgroundColor(Color.BLACK) }
        detail.addView(icon)
        detail.layout(0, 0, host.width, host.height)
        val x = start.centerX().toInt()
        val y = (start.centerY() - 50).toInt()
        icon.layout(x - 10, y - 10, x + 10, y + 10)
        val chrome = MediaPreviewDetailChrome.capture(detail, listOf(icon))
        val red = mutableListOf<Int>()
        val clear = mutableListOf<Int>()
        val pixels = Bitmap.createBitmap(host.width, host.height, Bitmap.Config.ARGB_8888)
        MediaPreviewTransition.diagnosticInteractiveFrame = { _, _, _, _ ->
            for (i in 0 until host.childCount) if (host.getChildAt(i) !== pager)
                host.getChildAt(i).layout(0, 0, host.width, host.height)
            pixels.eraseColor(Color.WHITE)
            host.draw(Canvas(pixels))
            red += Color.red(pixels.getPixel(x, y))
            clear += Color.red(pixels.getPixel(x + 30, y))
        }
        val animation = MediaPreviewReturnAnimation(host, pager, page, RectF(photo.displayRect),
            start, start, end, end, targetMedia(end), end, 0f, 0f, 0f, {}, {}, chrome)
        try {
            animation.start()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            assertEquals(255, red.first())
            assertTrue("应有渐显中间帧：$red", red.any { it in 20..235 })
            assertEquals(0, red.last())
            assertTrue("图标之外始终是红色媒体", clear.all { it == 255 })
        } finally { animation.close(); pixels.recycle() }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `返回补图只绘制媒体而不携带详情上层控件`() {
        val end = RectF(20f, 40f, 220f, 240f)
        val media = targetMedia(end)
        val detail = FrameLayout(activity).apply {
            addView(media, FrameLayout.LayoutParams(200, 200))
            addView(View(activity).apply { setBackgroundColor(Color.MAGENTA) }, FrameLayout.LayoutParams(200, 40))
            measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY))
            layout(0, 0, 200, 200)
        }
        val sourcePixels = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        detail.draw(Canvas(sourcePixels))
        assertEquals(Color.MAGENTA, sourcePixels.getPixel(100, 10))
        committed = { gesture.animateReturn(end, end, media, end) {} }
        down()
        send(MotionEvent.ACTION_MOVE, touchX, touchY + 200f)
        send(MotionEvent.ACTION_UP, touchX, touchY + 200f, 500)
        gesture.onPause()
        val supplement = host.getChildAt(host.indexOfChild(pager) + 1)
        supplement.layout(0, 0, host.width, host.height)
        val actual = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888)
        supplement.draw(Canvas(actual))
        val hostOffset = ThingDetailTransition.bounds(host, activity.window.decorView)
        assertEquals(Color.RED, actual.getPixel((end.centerX() - hostOffset.left).toInt(),
            (end.top + 10 - hostOffset.top).toInt()))
    }
}
