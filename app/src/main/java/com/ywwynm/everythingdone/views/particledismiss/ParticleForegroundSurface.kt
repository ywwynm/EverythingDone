package com.ywwynm.everythingdone.views.particledismiss

import android.graphics.HardwareRenderer
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.graphics.Matrix
import android.hardware.DataSpace
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.SurfaceControl
import android.view.View
import android.view.ViewTreeObserver
import android.view.SurfaceView
import android.view.animation.AnimationUtils
import android.view.animation.Transformation
import androidx.annotation.RequiresApi
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.ywwynm.everythingdone.BuildConfig
import java.util.WeakHashMap
import java.time.Duration
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * 卡片使用独立 Surface 时，同步接管首页前景（顶栏、模拟阴影、标题及 FAB）的绘制。
 * 原控件仍处理输入、布局与动画；保留半透明像素，静止复用缓冲，不读回 GPU 像素。
 * 每个控件只分配自身范围；FAB 另留缩放和原生阴影所需的余量。
 */
@RequiresApi(34)
internal class ParticleForegroundSurface private constructor(private val source: View,
                                                             parentSurface: SurfaceControl,
                                                             private val layerOrder: Int,
                                                             private val glWindow: ParticleWindowSurface? = null) {
    private val root = checkNotNull(source.rootSurfaceControl)
    private val diagnostic = ParticlePresentationDiagnostics.of(source)
    private val rootForeground = diagnostic?.options?.rootForeground == true
    private val diagnosticName = if (diagnostic == null) "" else "foreground-${runCatching {
        source.resources.getResourceEntryName(source.id)
    }.getOrDefault(source.id.toString())}-${System.identityHashCode(source)}"
    private var submittedDiagnosticFrames = 0
    internal val glLayer = glWindow?.let { ParticleGlBitmapLayer(diagnosticName, layerOrder, foreground = true).apply {
        copyHardwareToOwned = ParticlePresentationPolicy.of(source).unified
    } }
    private var copyingGlImage = false
    private var copiedGlFrames = 0
    private val originalTransitionAlpha = source.transitionAlpha
    private var nativeHidden = false
    private val fab = source as? FloatingActionButton
    private val lighting = if (fab != null) checkNotNull(ParticleCardSnapshot.of(source, source.z)) else null
    private val padding = if (fab != null && lighting != null) {
        val maxZ = max(source.z, fab.compatElevation + fab.compatPressedTranslationZ)
        val ratio = maxZ / max(lighting.lightZ - maxZ, maxZ.coerceAtLeast(1f))
        val distance = max(max(abs(lighting.lightX), abs(lighting.lightY)),
            max(abs(source.width - lighting.lightX), abs(source.height - lighting.lightY)))
        ceil(max(maxZ * 1.5f, (distance + lighting.lightRadius) * ratio) + 2f).toInt()
    } else 0
    private val width = source.width * (if (fab != null) 2 else 1) + padding * 2
    private val height = source.height * (if (fab != null) 2 else 1) + padding * 2
    private val rotation = ParticleWindowLighting.rotation(source)
    private val renderWidth = if (rotation % 2 == 0) width else height
    private val renderHeight = if (rotation % 2 == 0) height else width
    private lateinit var reader: ImageReader
    private lateinit var renderer: HardwareRenderer
    private val node = RenderNode("particleForegroundFrame")
    private val button = RenderNode("particleForegroundView")
    private var content: Bitmap? = null
    private lateinit var layer: SurfaceControl
    private var leases = 0
    private var active = false
    private var closing = false
    private var disposed = false
    private var drawing = false
    private var rendering = false
    private var acquiredImages = 0
    private val glRetainedImages = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var readerClosing = false
    private var previousPose: List<Float>? = null
    private var pendingFrame: Frame? = null
    private var firstFrame: Frame? = null
    private val readyCallbacks = mutableListOf<(Result<ParticleForegroundSurface>) -> Unit>()
    private val failureCallbacks = mutableListOf<() -> Unit>()
    private val preDraw = ViewTreeObserver.OnPreDrawListener { refreshSafely(); true }
    private val timeout = Runnable { closeNow(failed = true) }
    private val attachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) { closeNow(failed = true) }
    }

    private data class Frame(val x: Float, val y: Float, val alpha: Float, var image: Image? = null)

    init {
        try {
            initialise(parentSurface)
        } catch (error: Throwable) {
            closeNow()
            throw error
        }
    }

    private fun initialise(parentSurface: SurfaceControl) {
        reader = ImageReader.newInstance(renderWidth, renderHeight, PixelFormat.RGBA_8888, 3,
            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                HardwareBuffer.USAGE_COMPOSER_OVERLAY)
        renderer = HardwareRenderer()
        // SurfaceView 公开允许挂接自有子层；不修改框架管理的 Surface 本身。
        if (glWindow == null) {
            val builder = SurfaceControl.Builder().setName(if (fab != null) "ParticleFabForeground" else "ParticleHomeForeground-${source.id}")
                .setFormat(PixelFormat.RGBA_8888).setOpaque(false).setBufferSize(renderWidth, renderHeight)
            if (!rootForeground) builder.setParent(parentSurface)
            layer = builder.build()
            (if (rootForeground) checkNotNull(root.buildReparentTransaction(layer))
                else SurfaceControl.Transaction()).use {
                it.setLayer(layer, if (rootForeground) ParticlePresentationDiagnostics.rootForegroundLayer(layerOrder) else layerOrder)
                    .setVisibility(layer, false).apply()
            }
        }
        diagnostic?.event("foreground_created", mapOf("view" to diagnosticName,
            "parent" to if (glWindow != null) "shared_gl" else if (rootForeground) "window_root" else "particle_surface",
            "width" to renderWidth, "height" to renderHeight, "rotation" to rotation,
            "parentValid" to parentSurface.isValid, "originalTransitionAlpha" to originalTransitionAlpha,
            "layer" to if (rootForeground) ParticlePresentationDiagnostics.rootForegroundLayer(layerOrder) else layerOrder))
        renderer.setName("particleForeground")
        renderer.isOpaque = false
        renderer.setSurface(reader.surface)
        renderer.setContentRoot(node)
        renderer.start()
        reader.setOnImageAvailableListener({ images ->
            val image = runCatching { images.acquireNextImage() }.getOrElse {
                closeNow(failed = true); null
            } ?: return@setOnImageAvailableListener
            acquiredImages++
            rendering = false
            main.removeCallbacks(timeout)
            val frame = pendingFrame
            pendingFrame = null
            if (disposed || closing || frame == null) { closeUnused(image); return@setOnImageAvailableListener }
            frame.image = image
            if (glWindow != null) {
                copyToGl(frame, image)
                return@setOnImageAvailableListener
            }
            sampleHardware(image)
            if (!active) {
                firstFrame?.image?.let { closeUnused(it) }
                firstFrame = frame
                val callbacks = readyCallbacks.toList()
                readyCallbacks.clear()
                callbacks.forEach { it(Result.success(this)) }
            } else runCatching {
                // 接管后只有该层在画前景控件；持续更新不依赖窗口再次提交可见内容。
                SurfaceControl.Transaction().use { putFrame(it, frame); it.apply() }
            }.onFailure { closeNow(failed = true) }
        }, main)
        source.viewTreeObserver.addOnPreDrawListener(preDraw)
        source.addOnAttachStateChangeListener(attachment)
    }

    /** 与卡片的第一步窗口交接合并，不让半透明前景阴影出现两份。 */
    fun enter(transaction: SurfaceControl.Transaction): Boolean {
        if (disposed || closing) return false
        if (active) return true
        val frame = firstFrame ?: return false
        firstFrame = null
        active = true
        main.removeCallbacks(timeout)
        nativeHidden = true
        source.transitionAlpha = 0f
        diagnostic?.event("foreground_hidden", mapOf("view" to diagnosticName,
            "alpha" to source.alpha, "transitionAlpha" to source.transitionAlpha, "leases" to leases))
        putFrame(transaction, frame)
        return true
    }

    /** GL 纹理已在窗口中提交之后才隐藏原 View；首轮 prepared 图像不再经 SurfaceControl 提交。 */
    fun enterGl(hideNative: Boolean = true): Boolean {
        if (disposed || closing || glLayer?.closed != false) return false
        if (active) return true
        if (firstFrame == null) return false
        firstFrame = null; active = true
        nativeHidden = hideNative
        if (hideNative) source.transitionAlpha = 0f
        diagnostic?.event("foreground_hidden", mapOf("view" to diagnosticName,
            "alpha" to source.alpha, "transitionAlpha" to source.transitionAlpha, "leases" to leases,
            "backend" to "shared_gl", "nativeHidden" to hideNative))
        return true
    }

    /** 完整底图候选捕获时排除此 View；只在主线程同步记录期间使用。 */
    fun nativeViewForBackdrop(): View = source

    /** 完整 GL 画面已显示后隐藏真实前景；此时半透明像素不会重复叠加。 */
    fun hideNativeForGl() {
        if (disposed || closing || glWindow == null) return
        nativeHidden = true
        source.transitionAlpha = 0f
    }

    /** 首尾交接中的完整 GL 画面仍在，先恢复宿主，再撤下整组 GL。 */
    fun restoreNativeForGl() {
        if (glWindow == null) return
        nativeHidden = false
        source.transitionAlpha = originalTransitionAlpha
    }

    private fun copyToGl(frame: Frame, image: Image) {
        copyingGlImage = true
        val phase = if (active) "active" else "prepared"
        // Image 必须在复制结束前保持持有；同一 worker 随后才销毁 renderer / reader。
        worker.post {
            val result = runCatching {
                image.fence.use { check(!it.isValid || it.await(Duration.ofMillis(1000))) { "前景缓冲未就绪" } }
                val buffer = checkNotNull(image.hardwareBuffer)
                if (copiedGlFrames++ < 4) diagnostic?.event("foreground_buffer_usage", mapOf("view" to diagnosticName, "usage" to buffer.usage))
                val hardware = try { checkNotNull(Bitmap.wrapHardwareBuffer(buffer, null)) } finally { buffer.close() }
                if (ParticlePresentationPolicy.of(source).fastOpaqueHandoff == true && rotation == 0 && ParticleHardwareTexture.available) {
                    // 保持 Image 租用直到 GL 不再读取，避免生产者改写仍显示的缓冲。
                    return@runCatching hardware
                }
                val pixels = try { checkNotNull(hardware.copy(Bitmap.Config.ARGB_8888, false)) } finally { hardware.recycle() }
                ParticlePresentationDiagnostics.sample(diagnostic, "$diagnosticName-hardware-$phase", pixels)
                if (rotation == 0) pixels else try {
                    Bitmap.createBitmap(pixels, 0, 0, pixels.width, pixels.height,
                        Matrix().apply { setRotate(-90f * rotation) }, false)
                } finally { pixels.recycle() }
            }
            val retained = result.getOrNull()?.config == Bitmap.Config.HARDWARE
            if (retained) glRetainedImages.incrementAndGet() else image.close()
            frame.image = null
            main.post {
                if (!retained) acquiredImages--
                copyingGlImage = false
                val release = {
                    if (retained) {
                        image.close()
                        if (glRetainedImages.decrementAndGet() == 0 && readerClosing) worker.post { reader.close() }
                        main.post { acquiredImages-- }
                    }
                    Unit
                }
                val pixels = result.getOrNull()
                if (disposed || closing) { pixels?.recycle(); release(); return@post }
                if (pixels == null) {
                    diagnostic?.event("gl_foreground_copy_failed", mapOf("view" to diagnosticName,
                        "error" to result.exceptionOrNull().toString()))
                    closeNow(failed = true); return@post
                }
                val ready: (Boolean) -> Unit = ready@{ valid ->
                    if (disposed || closing) return@ready
                    if (!valid) { closeNow(failed = true); return@ready }
                    if (!active) {
                        firstFrame = frame
                        val callbacks = readyCallbacks.toList(); readyCallbacks.clear()
                        callbacks.forEach { it(Result.success(this)) }
                    }
                }
                if (retained) checkNotNull(glWindow).uploadGlImage(checkNotNull(glLayer), pixels,
                    frame.x, frame.y, frame.alpha, release, done = ready)
                else checkNotNull(glWindow).uploadGlBitmap(checkNotNull(glLayer), pixels,
                    frame.x, frame.y, frame.alpha, ready)
            }
        }
    }

    fun release(onFailure: () -> Unit, synchronizedGlRestore: Boolean = false) {
        failureCallbacks.remove(onFailure)
        if (disposed || --leases > 0) return
        closing = true
        main.removeCallbacks(timeout)
        source.transitionAlpha = originalTransitionAlpha
        diagnostic?.event("foreground_restored", mapOf("view" to diagnosticName,
            "transitionAlpha" to source.transitionAlpha, "leases" to leases))
        if (glWindow != null) {
            if (active && !synchronizedGlRestore) ParticleHostFrame.after(source, diagnostic, "foreground_restore") { closeNow() }
            else closeNow()
        } else if (active && source.isAttachedToWindow) {
            val transaction = SurfaceControl.Transaction().setBuffer(layer, null)
                .setVisibility(layer, false).reparent(layer, null)
                .addTransactionCommittedListener({ main.post(it) }) { closeNow() }
            applyWithWindow(transaction)
            main.postDelayed(timeout, 1000)
        } else closeNow()
    }

    private fun refreshSafely() {
        runCatching { refresh() }.onFailure { closeNow(failed = true) }
    }

    private fun refresh() {
        if (disposed || closing || rendering || drawing || copyingGlImage || acquiredImages >= reader.maxImages) return
        if (!source.isAttachedToWindow) { closeNow(failed = true); return }
        val parent = source.parent as? View ?: return
        val parentAt = IntArray(2).also { parent.getLocationOnScreen(it) }
        val decorAt = IntArray(2).also { source.rootView.getLocationOnScreen(it) }
        val layoutX = parentAt[0] + source.left - parent.scrollX
        val layoutY = parentAt[1] + source.top - parent.scrollY
        val animation = source.animation?.takeUnless { it.hasEnded() }
        val legacy = Transformation()
        if (animation != null) {
            if (!animation.isInitialized) animation.initialize(source.width, source.height, parent.width, parent.height)
            animation.getTransformation(AnimationUtils.currentAnimationTimeMillis(), legacy)
            source.postInvalidateOnAnimation()
        }
        // contextual toolbar 先设 INVISIBLE 再播旧式平移退场；绘制期仍须跟随该变换。
        var alpha = if (source.isShown || (animation != null && parent.isShown))
            source.alpha * originalTransitionAlpha * legacy.alpha else 0f
        var ancestor: View? = parent
        while (ancestor != null) { alpha *= ancestor.alpha; ancestor = ancestor.parent as? View }
        val matrixValues = FloatArray(9).also { legacy.matrix.getValues(it) }
        val pose = listOf(layoutX.toFloat(), layoutY.toFloat(), source.translationX, source.translationY,
            source.scaleX, source.scaleY, source.rotation, source.pivotX, source.pivotY, source.z, alpha,
            source.width.toFloat(), source.height.toFloat()) + matrixValues.toList()
        if (pose == previousPose && !source.isDirty) return
        previousPose = pose
        val centre = floatArrayOf(source.width / 2f, source.height / 2f)
        source.matrix.mapPoints(centre)
        legacy.matrix.mapPoints(centre)
        val x = layoutX + centre[0] - width / 2f
        val y = layoutY + centre[1] - height / 2f
        pendingFrame = Frame(x - decorAt[0], y - decorAt[1], alpha)
        button.setPosition(0, 0, source.width, source.height)
        if (fab != null) {
            button.translationX = layoutX - x + source.translationX
            button.translationY = layoutY - y + source.translationY
            button.pivotX = source.pivotX; button.pivotY = source.pivotY
            button.scaleX = source.scaleX; button.scaleY = source.scaleY; button.rotationZ = source.rotation
            button.elevation = source.z
        }
        button.setAmbientShadowColor(source.outlineAmbientShadowColor)
        button.setSpotShadowColor(source.outlineSpotShadowColor)
        val outline = Outline().apply { this.alpha = 1f }
        source.outlineProvider?.getOutline(source, outline)
        button.setOutline(outline); button.setClipToOutline(source.clipToOutline)
        drawing = true
        try {
            var pixels = content
            if (pixels == null || pixels.width != source.width || pixels.height != source.height) {
                pixels?.recycle()
                pixels = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
                content = pixels
            }
            // RippleDrawable 在第二个硬件 renderer 上会自行出帧，可能耗尽 ImageReader 并阻塞
            // 共用 RenderThread。软件 Canvas 只记录这个小按钮，波纹留在 UI 时钟驱动；阴影不栅格化。
            pixels.eraseColor(Color.TRANSPARENT)
            source.draw(Canvas(pixels))
            ParticlePresentationDiagnostics.sample(diagnostic,
                "$diagnosticName-cpu-${if (active) "active" else "prepared"}", pixels)
            val canvas = button.beginRecording(source.width, source.height)
            try { canvas.drawBitmap(pixels, 0f, 0f, null) } finally { button.endRecording() }
        } finally { drawing = false }
        node.setPosition(0, 0, renderWidth, renderHeight)
        val canvas = node.beginRecording(renderWidth, renderHeight)
        when (rotation) {
            1 -> canvas.translate(height.toFloat(), 0f)
            2 -> canvas.translate(width.toFloat(), height.toFloat())
            3 -> canvas.translate(0f, width.toFloat())
        }
        canvas.rotate(90f * rotation)
        if (fab == null) {
            canvas.translate(layoutX - x, layoutY - y)
            canvas.concat(legacy.matrix)
            canvas.concat(source.matrix)
        }
        canvas.enableZ(); canvas.drawRenderNode(button); canvas.disableZ()
        node.endRecording()
        lighting?.let {
            val lx = it.lightX + layoutX - x
            val ly = it.lightY + layoutY - y
            val lightX = when (rotation) { 1 -> height - ly; 2 -> width - lx; 3 -> ly; else -> lx }
            val lightY = when (rotation) { 1 -> lx; 2 -> height - ly; 3 -> width - lx; else -> ly }
            renderer.setLightSourceGeometry(lightX, lightY, it.lightZ, it.lightRadius)
            renderer.setLightSourceAlpha(it.ambientAlpha, it.spotAlpha)
        }
        rendering = true
        main.postDelayed(timeout, 1200)
        if (BuildConfig.DEBUG && fab != null) renderedFramesForTests++
        val result = renderer.createRenderRequest().setWaitForPresent(false).syncAndDraw()
        if (result and (HardwareRenderer.SYNC_LOST_SURFACE_REWARD_IF_FOUND or
                HardwareRenderer.SYNC_CONTEXT_IS_STOPPED) != 0) closeNow(failed = true)
        else if (result and HardwareRenderer.SYNC_FRAME_DROPPED != 0) {
            rendering = false; pendingFrame = null; previousPose = null
            main.removeCallbacks(timeout)
            source.postOnAnimation { refreshSafely() }
        }
    }

    private fun putFrame(transaction: SurfaceControl.Transaction, frame: Frame) {
        if (submittedDiagnosticFrames++ < 4) diagnostic?.event("foreground_buffer_submitted", mapOf(
            "view" to diagnosticName, "x" to frame.x, "y" to frame.y, "alpha" to frame.alpha,
            "valid" to layer.isValid, "active" to active, "meaning" to "submitted_not_screen_verified"))
        val image = checkNotNull(frame.image)
        image.hardwareBuffer.use { buffer ->
            image.fence.use { acquireFence ->
                transaction.setBuffer(layer, buffer, acquireFence) { releaseFence ->
                    worker.post {
                        // 只在工作线程等待合成器用完再归还，不阻塞主线程、不读回像素。
                        runCatching {
                            releaseFence?.use { if (it.isValid) it.awaitForever() }
                            image.close()
                        }.onSuccess {
                            main.post {
                                acquiredImages--
                                // 消费者较慢时跳过中间姿态，缓冲释放后继续绘制最新状态。
                                if (!disposed && !closing) source.postOnAnimation { refreshSafely() }
                            }
                        }.onFailure {
                            main.post { closeNow(failed = true) }
                        }
                    }
                }
            }
        }
        val transform = when (rotation) {
            1 -> SurfaceControl.BUFFER_TRANSFORM_ROTATE_270
            2 -> SurfaceControl.BUFFER_TRANSFORM_ROTATE_180
            3 -> SurfaceControl.BUFFER_TRANSFORM_ROTATE_90
            else -> SurfaceControl.BUFFER_TRANSFORM_IDENTITY
        }
        transaction.setBufferTransform(layer, transform).setDataSpace(layer, DataSpace.DATASPACE_SRGB)
            .setPosition(layer, frame.x, frame.y).setAlpha(layer, frame.alpha).setVisibility(layer, true)
    }

    private fun applyWithWindow(transaction: SurfaceControl.Transaction) {
        transaction.use {
            if (source.isAttachedToWindow && root.applyTransactionOnDraw(it)) source.rootView.invalidate()
            else it.apply()
        }
    }

    /** 关键图像模式才读回；只读已完成的帧，并在 Image 仍归本方持有时完成拷贝。 */
    private fun sampleHardware(image: Image) {
        val run = diagnostic ?: return
        val name = "$diagnosticName-hardware-${if (active) "active" else "prepared"}"
        if (!run.options.images || !run.claimImage(name)) return
        try {
            // 不等待尚未完成的生产帧；读回有额外成本，只用于图像取证，PNG 在后台编码。
            val ready = image.fence.use { !it.isValid || it.await(Duration.ZERO) }
            if (!ready) {
                run.event("image_unavailable", mapOf("image" to name, "reason" to "acquire_fence_pending"))
                return
            }
            val buffer = image.hardwareBuffer
            if (buffer == null) { run.event("image_unavailable", mapOf("image" to name)); return }
            val bitmap = try { Bitmap.wrapHardwareBuffer(buffer, null) } finally { buffer.close() }
            val copy = try { bitmap?.copy(Bitmap.Config.ARGB_8888, false) } finally { bitmap?.recycle() }
            run.image(name) { copy }
        } catch (failure: Exception) {
            run.event("image_failed", mapOf("image" to name, "error" to failure.toString()))
        }
    }

    private fun closeUnused(image: Image) {
        image.close()
        acquiredImages--
    }

    private fun closeNow(failed: Boolean = false) {
        if (disposed) return
        disposed = true
        main.removeCallbacks(timeout)
        if (surfaces[source] === this) surfaces.remove(source)
        if (source.viewTreeObserver.isAlive) source.viewTreeObserver.removeOnPreDrawListener(preDraw)
        source.removeOnAttachStateChangeListener(attachment)
        source.transitionAlpha = originalTransitionAlpha
        diagnostic?.event("foreground_closed", mapOf("view" to diagnosticName,
            "failed" to failed, "transitionAlpha" to source.transitionAlpha, "leases" to leases))
        glLayer?.let { glWindow?.closeGlLayer(it) }
        if (::layer.isInitialized) {
            runCatching {
                SurfaceControl.Transaction().use {
                    it.setBuffer(layer, null).setVisibility(layer, false).reparent(layer, null).apply()
                }
            }
            layer.release()
        }
        firstFrame?.image?.let { closeUnused(it) }; firstFrame = null
        val callbacks = readyCallbacks.toList(); readyCallbacks.clear()
        callbacks.forEach { it(Result.failure(IllegalStateException("前景硬件层不可用"))) }
        val failures = failureCallbacks.toList(); failureCallbacks.clear()
        if (failed) failures.forEach { main.post(it) }
        worker.post {
            // 此树只记录私有的软件位图，不借用真实 View 的 RenderNode。
            if (::renderer.isInitialized) runCatching { renderer.destroy() }
            readerClosing = true
            if (::reader.isInitialized && glRetainedImages.get() == 0) runCatching { reader.close() }
            node.discardDisplayList(); button.discardDisplayList()
            content?.recycle(); content = null
        }
    }

    companion object {
        private val main = Handler(Looper.getMainLooper())
        private val worker by lazy { Handler(HandlerThread("ParticleForegroundRelease").apply { start() }.looper) }
        private val surfaces = WeakHashMap<View, ParticleForegroundSurface>()
        var renderedFramesForTests = 0

        fun prepare(source: View, particleView: SurfaceView, onFailure: () -> Unit,
                    done: (Result<List<ParticleForegroundSurface>>) -> Unit,
                    glWindow: ParticleWindowSurface? = null) {
            val targets = ParticleHomeForeground.views(source)
            if (targets.isEmpty()) { done(Result.success(emptyList())); return }
            val prepared = mutableListOf<ParticleForegroundSurface>()
            var remaining = targets.size
            var error: Throwable? = null
            targets.forEachIndexed { index, view ->
                prepareView(view, index + 1, particleView, onFailure, { result ->
                    result.onSuccess { prepared += it }.onFailure { error = it }
                    if (--remaining == 0) {
                        val failure = error
                        if (failure != null) {
                            prepared.forEach { it.release(onFailure) }
                            done(Result.failure(failure))
                        } else done(Result.success(prepared))
                    }
                }, glWindow = glWindow)
            }
        }

        private fun prepareView(view: View, layerOrder: Int, particleView: SurfaceView, onFailure: () -> Unit,
                                done: (Result<ParticleForegroundSurface>) -> Unit, retries: Int = 60,
                                glWindow: ParticleWindowSurface? = null) {
            if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) {
                done(Result.failure(IllegalStateException("前景尚未布局或已离开窗口"))); return
            }
            val previous = surfaces[view]
            val exclusiveGroup = glWindow != null &&
                ParticlePresentationPolicy.of(view).opaqueGlHandoff == true
            // 回聚可能在上一组的撤除回调之前预热。完整画面不能共享同一个前景成员，
            // 否则上一组退出会撤掉新组的 FAB；只对该诊断候选等待旧租约归还。
            if (previous?.closing == true || (exclusiveGroup && previous != null && previous.leases > 0)) {
                if (retries > 0) view.postOnAnimation { prepareView(view, layerOrder, particleView, onFailure, done, retries - 1, glWindow) }
                else done(Result.failure(IllegalStateException("前景交接尚未结束")))
                return
            }
            val parentSurface = particleView.surfaceControl
            if (parentSurface == null || !parentSurface.isValid) {
                if (retries > 0 && particleView.isAttachedToWindow)
                    particleView.postOnAnimation { prepareView(view, layerOrder, particleView, onFailure, done, retries - 1, glWindow) }
                else done(Result.failure(IllegalStateException("粒子 Surface 不可用")))
                return
            }
            val existing = surfaces[view]?.takeUnless { it.closing || it.disposed }
            val target = existing ?: runCatching { ParticleForegroundSurface(view, parentSurface, layerOrder, glWindow) }
                .getOrNull()?.also { surfaces[view] = it }
            if (target == null) { done(Result.failure(IllegalStateException("前景硬件层创建失败"))); return }
            target.leases++
            target.failureCallbacks += onFailure
            if (target.active || target.firstFrame != null) done(Result.success(target))
            else { target.readyCallbacks += done; target.refreshSafely() }
        }

        /** 离屏捕获包含前景的祖先时，同步记录原控件；恢复发生在同一调用栈内。 */
        fun withNativeDrawing(root: View, draw: () -> Unit) {
            if (Build.VERSION.SDK_INT < 34) { draw(); return }
            val affected = surfaces.values.filter { surface ->
                var parent: View? = surface.source
                while (parent != null && parent !== root) parent = parent.parent as? View
                parent === root && surface.active && surface.nativeHidden && !surface.drawing
            }
            affected.forEach { it.source.transitionAlpha = it.originalTransitionAlpha }
            try { draw() } finally { affected.forEach { if (!it.disposed && !it.closing) it.source.transitionAlpha = 0f } }
        }
    }
}
