package com.ywwynm.everythingdone.helpers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.RecyclerView
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.adapters.ImageAttachmentAdapter
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.views.GradientRippleDrawable
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * 顶部按钮按其图形下方的静态媒体取样，各按钮等权，共同使用同一种黑白配色。
 * 绘制前只检查几何与背景是否改变；
 * 不读播放帧，不重新解码图片，图形与静态小图复用，评分交给单个工作线程。
 */
class DetailToolbarContrast(
    private val root: ViewGroup,
    private val toolbar: Toolbar,
    private val list: RecyclerView,
    private val cover: View,
    private val accent: () -> ThingBackground?
) {
    private data class Point(val x: Float, val y: Float, val alpha: Int)
    private class Button(val view: View, val icon: Drawable, val points: List<Point>) {
        var dark: Boolean? = null
        var ripple: GradientRippleDrawable? = null
    }
    private data class Glyph(val button: Button, val bounds: RectF, val alpha: Float)
    private data class Media(val raster: DetailMediaOverlayColors.Raster?, val bounds: RectF,
                             val clip: Rect, val alpha: Float)
    private data class Frame(val glyphs: List<Glyph>, val media: List<Media>, val bounds: Rect,
                             val background: ThingBackground?, val layers: List<List<Int>>, val revision: Int)
    private class Layer(val width: Int, val height: Int, val pixels: IntArray) {
        fun at(x: Float, y: Float): Int = pixels[(y * height).toInt().coerceIn(0, height - 1) * width +
            (x * width).toInt().coerceIn(0, width - 1)]
    }

    private val buttons = mutableMapOf<View, Button>()
    private var toolbarDark: Boolean? = null
    private var lastFrame: Frame? = null
    private var revision = 0
    private var closed = false
    private var work: Future<*>? = null
    private val executor = ThreadPoolExecutor(1, 1, 15L, TimeUnit.SECONDS, LinkedBlockingQueue(),
        { task -> Thread(task, "DetailToolbarContrast").apply { priority = Thread.NORM_PRIORITY - 1 } }
    ).apply { allowCoreThreadTimeOut(true) }
    private val preDraw = ViewTreeObserver.OnPreDrawListener { update(); true }

    // 仅 debug 记录，供真实滚动回归读取，不改变调度或选择规则。
    var computeCount = 0; private set
    var refreshCount = 0; private set
    var applyCount = 0; private set
    var lastMainMicros = 0L; private set
    var lastWorkerMicros = 0L; private set
    var pending = false; private set

    init { root.viewTreeObserver.addOnPreDrawListener(preDraw) }

    fun refresh() { revision++; toolbar.invalidate() }

    fun dispose() {
        closed = true
        if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(preDraw)
        cancelWork()
        executor.shutdownNow()
        buttons.values.forEach { it.ripple?.stopAnimations() }
        buttons.clear()
        lastFrame = null
    }

    private fun update() {
        if (closed || !toolbar.isShown || toolbar.width == 0 || toolbar.height == 0) return
        val started = if (BuildConfig.DEBUG) System.nanoTime() else 0L
        val toolbarBounds = screenBounds(toolbar)
        val glyphs = controls().mapNotNull(::glyph)
        val active = glyphs.mapTo(mutableSetOf()) { it.button.view }
        buttons.keys.filter { it !in active }.forEach { buttons.remove(it)?.ripple?.stopAnimations() }
        val media = ArrayList<Media>()
        for (i in 0 until list.childCount) {
            val holder = list.getChildViewHolder(list.getChildAt(i)) as? ImageAttachmentAdapter.ImageViewHolder ?: continue
            val image = holder.ivImage ?: continue
            val clip = Rect()
            if (!image.isShown || !image.getGlobalVisibleRect(clip) || !Rect.intersects(clip, toolbarBounds)) continue
            media += Media(holder.overlayColors.staticRaster, RectF(screenBounds(image)), clip,
                image.alpha * holder.itemView.alpha)
        }
        val frame = Frame(glyphs, media, toolbarBounds, accent(),
            listOf(layerSignature(root), layerSignature(cover), layerSignature(toolbar)), revision)
        if (lastFrame == frame) return
        lastFrame = frame
        if (BuildConfig.DEBUG) refreshCount++
        cancelWork()
        val fallback = frame.background?.let(BackgroundUtil::isLight) ?: false
        val hasMedia = glyphs.any { glyph -> media.any { it.raster != null &&
            RectF.intersects(glyph.bounds, RectF(it.clip)) } }
        if (!hasMedia) {
            applyTogether(glyphs, fallback)
            if (BuildConfig.DEBUG) lastMainMicros = (System.nanoTime() - started) / 1000
            return
        }
        val previous = toolbarDark
        // 新菜单按钮先继承整排现有颜色，异步计算期间也保持一致。
        applyTogether(glyphs, previous ?: fallback)
        // 小尺寸背景只包含底色与覆盖层，排除图标、附件标记和正在扩散的波纹。
        val base = captureLayer(root, toolbarBounds, Color.BLACK)
        val shade = captureLayer(cover, toolbarBounds)
        val overlay = captureLayer(toolbar, toolbarBounds)
        val prepareMicros = if (BuildConfig.DEBUG) (System.nanoTime() - started) / 1000 else 0L
        pending = true
        if (BuildConfig.DEBUG) computeCount++
        work = executor.submit {
            val workerStart = if (BuildConfig.DEBUG) System.nanoTime() else 0L
            var visibleMedia = false
            val groups = glyphs.map { glyph ->
                glyph.button.points.map { point ->
                    val sx = glyph.bounds.left + point.x * glyph.bounds.width()
                    val sy = glyph.bounds.top + point.y * glyph.bounds.height()
                    val x = (sx - toolbarBounds.left) / toolbarBounds.width()
                    val y = (sy - toolbarBounds.top) / toolbarBounds.height()
                    var color = base.at(x, y)
                    val shadePixel = shade.at(x, y)
                    val overlayPixel = overlay.at(x, y)
                    // 附件通常互不重叠；按当前子视图顺序合成半透明像素与底色。
                    for (item in media) {
                        val raster = item.raster ?: continue
                        if (!item.clip.contains(sx.toInt(), sy.toInt())) continue
                        val pixel = raster.at((sx - item.bounds.left) / item.bounds.width(),
                            (sy - item.bounds.top) / item.bounds.height())
                        val alpha = ((pixel ushr 24) * item.alpha).roundToInt()
                        if (alpha > 0 && glyph.alpha > 0f && shadePixel ushr 24 < 255 &&
                            overlayPixel ushr 24 < 255) visibleMedia = true
                        color = MediaOverlayContrast.composite(pixel, color, alpha)
                    }
                    color = over(shadePixel, color)
                    color = over(overlayPixel, color)
                    val alpha = (point.alpha * glyph.alpha).roundToInt()
                    MediaOverlayContrast.Sample(color, alpha = alpha,
                        weight = point.alpha.toDouble() * point.alpha / (255.0 * 255.0))
                }
            }
            val dark = if (visibleMedia) MediaOverlayContrast.chooseEqualGroups(groups).withHysteresis(previous) else fallback
            val workerMicros = if (BuildConfig.DEBUG) (System.nanoTime() - workerStart) / 1000 else 0L
            root.post {
                if (closed || lastFrame != frame) return@post
                val applyStart = if (BuildConfig.DEBUG) System.nanoTime() else 0L
                applyTogether(glyphs, dark)
                pending = false
                work = null
                if (BuildConfig.DEBUG) {
                    applyCount++
                    lastMainMicros = prepareMicros + (System.nanoTime() - applyStart) / 1000
                    lastWorkerMicros = workerMicros
                }
            }
        }
    }

    private fun cancelWork() {
        work?.let { it.cancel(false); if (it is Runnable) executor.remove(it) }
        work = null
        pending = false
    }

    private fun controls(): List<View> = buildList {
        for (i in 0 until toolbar.childCount) {
            when (val child = toolbar.getChildAt(i)) {
                is ActionMenuView -> for (j in 0 until child.childCount) add(child.getChildAt(j))
                is ImageView -> add(child)
            }
        }
    }

    private fun glyph(view: View): Glyph? {
        if (!view.isShown || view.width == 0 || view.height == 0) return null
        val icon: Drawable
        val bounds: RectF
        when (view) {
            is ImageView -> {
                icon = view.drawable ?: return null
                bounds = RectF(icon.bounds)
                view.imageMatrix.mapRect(bounds)
                bounds.offset((view.paddingLeft - view.scrollX).toFloat(), (view.paddingTop - view.scrollY).toFloat())
            }
            is TextView -> {
                val drawables = view.compoundDrawables
                icon = drawables[0] ?: drawables[2] ?: return null
                bounds = RectF(icon.bounds)
                val left = if (drawables[0] != null) view.paddingLeft.toFloat()
                    else view.width - view.paddingRight - bounds.width()
                val top = view.compoundPaddingTop +
                    (view.height - view.compoundPaddingTop - view.compoundPaddingBottom - bounds.height()) / 2f
                bounds.offsetTo(left, top)
            }
            else -> return null
        }
        if (bounds.isEmpty) return null
        val button = buttons[view]?.takeIf { it.icon === icon } ?: run {
            buttons.remove(view)?.ripple?.stopAnimations()
            Button(view, icon, capturePoints(icon)).also { buttons[view] = it }
        }
        val location = IntArray(2).also(view::getLocationOnScreen)
        bounds.offset(location[0].toFloat(), location[1].toFloat())
        return Glyph(button, bounds, view.alpha)
    }

    private fun capturePoints(icon: Drawable): List<Point> {
        val size = icon.bounds
        val scale = minOf(1f, 48f / maxOf(size.width(), size.height()).coerceAtLeast(1))
        val width = (size.width() * scale).roundToInt().coerceAtLeast(1)
        val height = (size.height() * scale).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(width.toFloat() / size.width(), height.toFloat() / size.height())
        canvas.translate(-size.left.toFloat(), -size.top.toFloat())
        icon.draw(canvas)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()
        val visible = pixels.indices.filter { pixels[it] ushr 24 >= 24 }
        val count = minOf(128, visible.size)
        return List(count) { index ->
            val pixel = visible[index * visible.size / count]
            Point((pixel % width + .5f) / width, (pixel / width + .5f) / height, pixels[pixel] ushr 24)
        }
    }

    private fun apply(button: Button, dark: Boolean) {
        if (button.dark == dark && button.view.foreground === button.ripple) return
        button.icon.mutate().setTint(if (dark) Color.BLACK else Color.WHITE)
        button.view.invalidate()
        val rippleColor = if (dark) 0x29000000 else 0x5cffffff
        val ripple = button.ripple ?: GradientRippleDrawable(
            ThingBackground.pure(rippleColor or 0xff000000.toInt()), shapeOval = false,
            fixedRadiusPx = toolbar.resources.displayMetrics.density * BackgroundUtil.TOOLBAR_ICON_RIPPLE_RADIUS_DP,
            centered = true, peakAlphaOverride = (rippleColor ushr 24) / 255f
        ).also { button.ripple = it }
        if (button.dark != dark) ripple.updateAdaptiveColor(rippleColor)
        button.view.background = null
        if (button.view.foreground !== ripple) button.view.foreground = ripple
        button.dark = dark
    }

    private fun applyTogether(glyphs: List<Glyph>, dark: Boolean) {
        glyphs.forEach { apply(it.button, dark) }
        toolbarDark = dark
    }

    private fun screenBounds(view: View): Rect {
        val location = IntArray(2).also(view::getLocationOnScreen)
        return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    private fun layerSignature(view: View): List<Int> {
        val drawable = view.background
        val bounds = screenBounds(view)
        return buildList {
            add(view.visibility); add(view.alpha.toBits()); add(bounds.left); add(bounds.top)
            add(bounds.right); add(bounds.bottom); add(drawable?.alpha ?: 0)
            when (drawable) {
                is ColorDrawable -> add(drawable.color)
                is GradientDrawable -> {
                    add(drawable.color?.defaultColor ?: 0)
                    drawable.colors?.forEach(::add)
                    add(drawable.orientation.ordinal)
                }
                else -> add(System.identityHashCode(drawable))
            }
        }
    }

    private fun captureLayer(view: View, region: Rect, initial: Int = Color.TRANSPARENT): Layer {
        val width = 128
        val height = (region.height().toFloat() / region.width() * width).roundToInt().coerceAtLeast(16)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(initial)
        val checkpoint = canvas.save()
        if (view.visibility == View.VISIBLE && view.alpha > 0f) {
            val bounds = screenBounds(view)
            canvas.scale(width.toFloat() / region.width(), height.toFloat() / region.height())
            canvas.translate((bounds.left - region.left).toFloat(), (bounds.top - region.top).toFloat())
            canvas.clipRect(0, 0, view.width, view.height)
            if (view.alpha < 1f) canvas.saveLayerAlpha(0f, 0f, view.width.toFloat(), view.height.toFloat(),
                (view.alpha * 255).roundToInt())
            view.background?.draw(canvas)
        }
        canvas.restoreToCount(checkpoint)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()
        return Layer(width, height, pixels)
    }

    private fun over(pixel: Int, background: Int) =
        MediaOverlayContrast.composite(pixel, background, pixel ushr 24)
}
