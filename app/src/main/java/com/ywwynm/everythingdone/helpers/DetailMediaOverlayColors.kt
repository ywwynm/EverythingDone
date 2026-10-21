package com.ywwynm.everythingdone.helpers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.bumptech.glide.signature.ObjectKey
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.views.KnockoutTextBadge
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 每个附件持有一个实例。与显示请求并行取得最大边 256 的静态封面，解码与裁剪由 Glide 后台处理。
 * 小图和图形点集有界缓存；每个图形最多 256 个点，各元素等权共同选择该附件的统一颜色。
 * 播放期间没有定时任务。
 */
class DetailMediaOverlayColors(private val tile: ViewGroup, private val image: ImageView) {
    /** 只读静态取样；与 Glide 位图独立，顶栏可复用而无需再次解码。 */
    class Raster internal constructor(val width: Int, val height: Int, internal val pixels: IntArray) {
        val transparent = pixels.any { it ushr 24 != 255 }
        fun at(x: Float, y: Float): Int = pixels[
            (y * height).toInt().coerceIn(0, height - 1) * width +
                (x * width).toInt().coerceIn(0, width - 1)]
    }
    private data class Point(val x: Float, val y: Float, val inkX: Float = x,
                             val inkY: Float = y, val alpha: Int, val weight: Double)
    private data class Element(val id: Int, val maskKey: String, val width: Int, val height: Int,
        val pixels: IntArray?, val points: List<Point>?, val text: Boolean, val alpha: Float,
        val ink: InkSource?)
    private data class InkSource(val key: String, val width: Int, val height: Int,
        val density: Int, val pixels: IntArray)
    private var key: String? = null
    private var raster: Raster? = null
    val staticRaster: Raster? get() = raster.takeIf { mediaReady != 0L }
    private var target: CustomTarget<Bitmap>? = null
    private var crop: MediaCropBitmapRenderer.Crop? = null
    private var source = ""
    private var videoFrameMs: Long? = null
    private var video = false
    private var targetWidth = 0
    private var targetHeight = 0
    private var signature = ""
    private var revision = 0
    private var started = 0L
    private var mediaReady = 0L
    private var preDraw: ViewTreeObserver.OnPreDrawListener? = null
    private val views = intArrayOf(R.id.tv_badge_hdr, R.id.tv_badge_gif, R.id.iv_badge_live,
        R.id.iv_badge_spatial, R.id.iv_edit_image_attachment_appearance,
        R.id.iv_delete_image_attachment, R.id.iv_video_signal).map { tile.findViewById<View>(it) }
    private var job: Future<*>? = null
    private var captureJob: Future<*>? = null
    private var captureGeneration = 0
    private var generation = 0
    private val appliedInk = mutableMapOf<Int, Boolean>()
    private var sharedDark = false
    var pending = false; private set

    // 数值仅在 debug 采集，供真实详情页回归读取，不参与生产行为。
    var applyCount = 0; private set
    var staticLoadCount = 0; private set
    var cacheHitCount = 0; private set
    var lastApplyMicros = 0L; private set
    var lastCaptureMicros = 0L; private set
    var lastWorkerMicros = 0L; private set
    var readyLatencyMs = 0L; private set
    var afterMediaReadyMs = 0L; private set
    var appliedKey: String? = null; private set

    init {
        val layout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> schedule() }
        tile.addOnLayoutChangeListener(layout)
        views.forEach { it.addOnLayoutChangeListener(layout) }
    }

    fun bind(identity: String, path: String, isVideo: Boolean, frameMs: Long?, width: Int,
             height: Int, crop: MediaCropBitmapRenderer.Crop?) {
        if (key == identity) {
            if (raster == null && target == null) requestStatic()
            return
        }
        cancelTarget()
        cancelJob()
        key = identity
        source = path
        video = isVideo
        videoFrameMs = frameMs
        targetWidth = width
        targetHeight = height
        this.crop = crop
        started = SystemClock.uptimeMillis()
        mediaReady = 0L
        signature = ""
        appliedKey = null
        raster = cache.get(identity)
        revision++
        sharedDark = false
        // 复用 holder 时不得暂时显示上一个附件的黑色。
        views.filter { appliedInk[it.id] == true }.forEach { setDark(it, false) }
        if (raster != null) {
            if (BuildConfig.DEBUG) cacheHitCount++
            schedule()
        } else requestStatic()
    }

    fun onMediaReady(identity: String, bitmap: Bitmap? = null, completeImage: Boolean = false) {
        if (key != identity) return
        if (mediaReady == 0L) mediaReady = SystemClock.uptimeMillis()
        image.postInvalidateOnAnimation()
        if (raster != null || captureJob != null || bitmap == null || bitmap.isRecycled ||
            bitmap.config == Bitmap.Config.HARDWARE) return
        // 显示缓存先就绪时直接取得独立小副本，避免 HDR 原图或视频再次解码。
        // 这里只做原生缩放及一次复制；逐像素检查留在后台，不持有 Glide 原位图。
        cancelTarget()
        val time = if (BuildConfig.DEBUG) System.nanoTime() else 0L
        val (w, h) = sampleSize()
        val small = if (completeImage) {
            MediaCropBitmapRenderer.renderCrop(bitmap, w, h, MediaCropBitmapRenderer.Crop(.5, .5, 1.0)) ?: return
        } else Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawBitmap(bitmap, null, Rect(0, 0, w, h), samplePaint)
        }
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        small.recycle()
        if (BuildConfig.DEBUG) lastCaptureMicros = (System.nanoTime() - time) / 1000
        prepareRaster(identity, w, h, pixels)
    }

    private fun sampleSize(): Pair<Int, Int> {
        val scale = minOf(1f, SAMPLE_EDGE.toFloat() / max(targetWidth, targetHeight).coerceAtLeast(1))
        return max(1, (targetWidth * scale).roundToInt()) to max(1, (targetHeight * scale).roundToInt())
    }

    private fun requestStatic() {
        val identity = key ?: return
        if (target != null || targetWidth <= 0 || targetHeight <= 0) return
        val (w, h) = sampleSize()
        val previewSource = if (!video && MediaPreviewThumbnail.isStaticImage(source)) MediaPreviewThumbnail.source(source) else null
        val requestTarget = object : CustomTarget<Bitmap>(w, h) {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                if (target !== this || key != identity) return
                val time = if (BuildConfig.DEBUG) System.nanoTime() else 0L
                // 动态照片播放派生动画时，取色请求仍取得静态主图；复用该次解码，不新增预加载。
                val sampled = if (previewSource != null) {
                    MediaPreviewThumbnail.remember(previewSource, resource)
                    MediaCropBitmapRenderer.renderCrop(resource, w, h, crop ?: MediaCropBitmapRenderer.Crop(.5, .5, 1.0)) ?: return
                } else resource
                val width = sampled.width
                val height = sampled.height
                val pixels = IntArray(width * height)
                // 单次复制至多 256×256 像素；后台只持有副本，不依赖 Glide 位图的回收时机。
                sampled.getPixels(pixels, 0, width, 0, 0, width, height)
                if (sampled !== resource) sampled.recycle()
                if (BuildConfig.DEBUG) lastCaptureMicros = (System.nanoTime() - time) / 1000
                prepareRaster(identity, width, height, pixels)
            }
            override fun onLoadCleared(placeholder: Drawable?) = Unit
            override fun onLoadFailed(errorDrawable: Drawable?) {
                if (target === this) tile.post { if (target === this) cancelTarget() }
            }
        }
        target = requestTarget
        if (BuildConfig.DEBUG) staticLoadCount++
        var request = Glide.with(image.context).asBitmap().load(source)
            .disallowHardwareConfig().dontAnimate().override(w, h)
            .priority(Priority.HIGH)
            .signature(ObjectKey("detail-overlay-static-v1:$identity"))
        if (video && videoFrameMs != null) request = request.apply(RequestOptions.frameOf(videoFrameMs!! * 1000L))
        request = if (previewSource != null) request.dontTransform()
            else crop?.let { request.transform(MediaCropTransformation(w, h, it)) } ?: request.centerCrop()
        request.into(requestTarget)
    }

    private fun prepareRaster(identity: String, width: Int, height: Int, pixels: IntArray) {
        val token = captureGeneration
        captureJob = executor.submit {
            val sampled = Raster(width, height, pixels)
            cache.put(identity, sampled)
            tile.post {
                if (key == identity && captureGeneration == token) {
                    raster = sampled
                    revision++
                    cancelTarget()
                    schedule()
                    image.postInvalidateOnAnimation()
                }
            }
        }
    }

    fun schedule() {
        if (raster == null || key == null) return
        if (tile.width > 0 && tile.height > 0 && !tile.isLayoutRequested && views.none { it.isLayoutRequested }) {
            apply()
            return
        }
        if (preDraw != null) return
        val listener = ViewTreeObserver.OnPreDrawListener {
            removePreDraw()
            apply()
            true
        }
        preDraw = listener
        tile.viewTreeObserver.addOnPreDrawListener(listener)
        tile.invalidate()
    }

    private fun removePreDraw() {
        preDraw?.let { if (tile.viewTreeObserver.isAlive) tile.viewTreeObserver.removeOnPreDrawListener(it) }
        preDraw = null
    }

    private fun apply() {
        val grid = raster ?: return
        val identity = key ?: return
        if (tile.width <= 0 || tile.height <= 0) return
        val visible = views.filter { it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 }
        // 异步检测后新出现的标识先沿用附件当前颜色，等待评分时也不与其他元素混色。
        visible.filter { sharedDark || appliedInk[it.id] != null }.forEach { setDark(it, sharedDark) }
        val bounds = visible.map { view ->
            Rect(0, 0, view.width, view.height).also { tile.offsetDescendantRectToMyCoords(view, it) }
        }
        val geometry = "${tile.width}:${tile.height}:" + visible.indices.joinToString(";") {
            "${visible[it].id}:${bounds[it]}:${visible[it].alpha}"
        }
        if (signature == "$revision:$geometry") return
        cancelJob()
        signature = "$revision:$geometry"
        val requestSignature = signature
        val token = generation
        val time = if (BuildConfig.DEBUG) System.nanoTime() else 0L
        val backdrop = if (grid.transparent) backdrop(grid.width, grid.height) else null
        val resultKey = "$identity:$geometry:${backdrop?.pixels?.contentHashCode() ?: 0}"
        fun publish(dark: Boolean, prepareMicros: Long, workerMicros: Long) {
            if (key != identity || generation != token || signature != requestSignature) return
            val applyTime = if (BuildConfig.DEBUG) System.nanoTime() else 0L
            sharedDark = dark
            visible.forEach { setDark(it, dark) }
            pending = false
            job = null
            if (BuildConfig.DEBUG) {
                applyCount++
                lastApplyMicros = prepareMicros + (System.nanoTime() - applyTime) / 1000
                lastWorkerMicros = workerMicros
                if (appliedKey == null) {
                    readyLatencyMs = SystemClock.uptimeMillis() - started
                    afterMediaReadyMs = if (mediaReady == 0L) 0L else SystemClock.uptimeMillis() - mediaReady
                }
            }
            appliedKey = identity
        }
        results.get(resultKey)?.let {
            publish(it, if (BuildConfig.DEBUG) (System.nanoTime() - time) / 1000 else 0L, 0L)
            return
        }
        // View 和 Drawable 只在主线程读取/绘制；点集整理及评分只处理不可变的小数组。
        val elements = visible.map(::captureElement)
        val tileWidth = tile.width
        val tileHeight = tile.height
        val prepareMicros = if (BuildConfig.DEBUG) (System.nanoTime() - time) / 1000 else 0L
        pending = true
        job = executor.submit {
            val workerTime = if (BuildConfig.DEBUG) System.nanoTime() else 0L
            fun background(x: Float, y: Float): Int {
                val color = grid.at(x, y)
                return if (color ushr 24 == 255) color else MediaOverlayContrast.composite(
                    color, backdrop?.at(x, y) ?: Color.BLACK, color ushr 24)
            }
            val groups = elements.mapIndexed { index, element ->
                element.ink?.let(::prepareInk)
                val rect = bounds[index]
                fun x(value: Float) = (rect.left + value * rect.width()) / tileWidth
                fun y(value: Float) = (rect.top + value * rect.height()) / tileHeight
                val samples = resolvePoints(element).map { p -> MediaOverlayContrast.Sample(
                    background(x(p.x), y(p.y)), background(x(p.inkX), y(p.inkY)),
                    (p.alpha * element.alpha).roundToInt(), p.weight)
                }
                MediaOverlayContrast.Group(samples, if (element.text) 4.5 else 3.0)
            }
            val dark = MediaOverlayContrast.chooseGroups(groups).dark
            results.put(resultKey, dark)
            val workerMicros = if (BuildConfig.DEBUG) (System.nanoTime() - workerTime) / 1000 else 0L
            tile.post { publish(dark, prepareMicros, workerMicros) }
        }
    }

    private fun backdrop(width: Int, height: Int): Raster {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        canvas.scale(width.toFloat() / tile.width, height.toFloat() / tile.height)
        val location = IntArray(2).also(tile::getLocationInWindow)
        val parents = mutableListOf<View>()
        var parent: View? = tile
        while (parent != null) { parents += parent; parent = parent.parent as? View }
        for (view in parents.asReversed()) {
            val drawable = view.background ?: continue
            val origin = IntArray(2).also(view::getLocationInWindow)
            val saved = canvas.save()
            canvas.translate((origin[0] - location[0]).toFloat(), (origin[1] - location[1]).toFloat())
            drawable.draw(canvas)
            canvas.restoreToCount(saved)
        }
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()
        return Raster(width, height, pixels)
    }

    private fun captureElement(view: View): Element {
        val maskKey = "${view.id}:${view.width}:${view.height}:${view.resources.configuration.fontScale}"
        val ink = if (view is ImageView && inks.get(inkKey(view)) == null) captureInk(view) else null
        val cached = masks.get(maskKey)
        if (cached != null) return Element(view.id, maskKey, 0, 0, null, cached, view is KnockoutTextBadge, view.alpha, ink)
        val ratio = MASK_EDGE.toFloat() / max(view.width, view.height)
        val w = max(1, (view.width * ratio).roundToInt())
        val h = max(1, (view.height * ratio).roundToInt())
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(w.toFloat() / view.width, h.toFloat() / view.height)
        if (view is KnockoutTextBadge) view.drawContrastGlyphs(canvas)
        else if (view is ImageView) {
            val drawable = view.drawable.constantState?.newDrawable(view.resources)?.mutate() ?: view.drawable
            val oldBounds = drawable.copyBounds()
            drawable.bounds = view.drawable.bounds
            canvas.translate(view.paddingLeft.toFloat(), view.paddingTop.toFloat())
            canvas.concat(view.imageMatrix)
            drawable.draw(canvas)
            drawable.bounds = oldBounds
        }
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        bitmap.recycle()
        return Element(view.id, maskKey, w, h, pixels, null, view is KnockoutTextBadge, view.alpha, ink)
    }

    private fun resolvePoints(element: Element): List<Point> {
        element.points?.let { return it }
        val w = element.width
        val h = element.height
        val pixels = element.pixels!!
        val points = ArrayList<Point>()
        for (y in 0 until h) for (x in 0 until w) {
            val alpha = pixels[y * w + x] ushr 24
            if (alpha < 64) continue
            if (element.text) {
                if (alpha < 160) continue
                // 镂空文字与近邻填充成对比较，文字区域不会被大面积底色淹没。
                var neighbor = -1
                for (i in NEIGHBOR_X.indices) {
                    val nx = x + NEIGHBOR_X[i]
                    val ny = y + NEIGHBOR_Y[i]
                    if (nx in 1 until w - 1 && ny in 1 until h - 1 && pixels[ny * w + nx] ushr 24 < 32) {
                        neighbor = ny * w + nx
                        break
                    }
                }
                if (neighbor < 0) continue
                points += Point((x + .5f) / w, (y + .5f) / h,
                    (neighbor % w + .5f) / w, (neighbor / w + .5f) / h,
                    216, alpha / 255.0)
            } else points += Point((x + .5f) / w, (y + .5f) / h,
                alpha = alpha, weight = alpha / 255.0)
        }
        val bounded = if (points.size <= MAX_POINTS) points else List(MAX_POINTS) {
            points[it * points.size / MAX_POINTS]
        }
        masks.put(element.maskKey, bounded)
        return bounded
    }

    private fun setDark(view: View, dark: Boolean) {
        if (appliedInk[view.id] == dark) return
        if (view is KnockoutTextBadge) view.setDarkInk(dark)
        else if (view is ImageView) {
            // 正常路径在后台评分时准备；只有图标缓存已淘汰而最终颜色仍命中时才补建。
            val pair = inks.get(inkKey(view)) ?: prepareInk(captureInk(view))
            // Flyme 强制深色会改写颜色滤镜；将 RGB 写入位图，保留全部原始 alpha。
            view.setImageDrawable(BitmapDrawable(view.resources, if (dark) pair.second else pair.first))
        }
        appliedInk[view.id] = dark
    }

    private fun inkKey(view: ImageView): String =
        "${view.id}:${view.drawable.intrinsicWidth}:${view.drawable.intrinsicHeight}:${view.resources.displayMetrics.densityDpi}"

    private fun captureInk(view: ImageView): InkSource {
        val original = view.drawable
        val w = original.intrinsicWidth.coerceAtLeast(1)
        val h = original.intrinsicHeight.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val drawable = original.constantState?.newDrawable(view.resources)?.mutate() ?: original
        val oldBounds = drawable.copyBounds()
        drawable.setBounds(0, 0, w, h)
        drawable.draw(Canvas(bitmap))
        drawable.bounds = oldBounds
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        bitmap.recycle()
        return InkSource(inkKey(view), w, h, view.resources.displayMetrics.densityDpi, pixels)
    }

    private fun prepareInk(source: InkSource): Pair<Bitmap, Bitmap> {
        inks.get(source.key)?.let { return it }
        val white = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val black = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        white.density = source.density
        black.density = source.density
        val pixels = source.pixels
        for (i in pixels.indices) pixels[i] = pixels[i] or 0x00ffffff
        white.setPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        for (i in pixels.indices) pixels[i] = pixels[i] and 0xff000000.toInt()
        black.setPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        return Pair(white, black).also { inks.put(source.key, it) }
    }

    private fun cancelTarget() {
        captureGeneration++
        captureJob?.let { it.cancel(false); if (it is Runnable) executor.remove(it) }
        captureJob = null
        val old = target ?: return
        target = null
        Glide.with(image.context).clear(old)
    }

    private fun cancelJob() {
        generation++
        val old = job
        old?.cancel(false)
        if (old is Runnable) executor.remove(old)
        job = null
        pending = false
    }

    fun detach() { removePreDraw(); cancelTarget(); cancelJob(); signature = "" }

    fun clear() {
        detach()
        key = null
        raster = null
        signature = ""
        appliedKey = null
    }

    companion object {
        private const val SAMPLE_EDGE = 256
        private const val MASK_EDGE = 64
        private const val MAX_POINTS = 256
        private val NEIGHBOR_X = intArrayOf(-1, 1, 0, 0, -2, 2, 0, 0)
        private val NEIGHBOR_Y = intArrayOf(0, 0, -1, 1, 0, 0, -2, 2)
        private val samplePaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val cache = object : LruCache<String, Raster>(4 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Raster) = value.pixels.size * 4
        }
        private val masks = LruCache<String, List<Point>>(64)
        private val inks = LruCache<String, Pair<Bitmap, Bitmap>>(16)
        private val results = LruCache<String, Boolean>(256)
        private val executor = ThreadPoolExecutor(1, 1, 15L, TimeUnit.SECONDS,
            LinkedBlockingQueue(), { work -> Thread(work, "DetailOverlayContrast").apply { priority = Thread.NORM_PRIORITY - 1 } }
        ).apply { allowCoreThreadTimeOut(true) }
    }
}
