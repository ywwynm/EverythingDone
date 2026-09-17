package com.ywwynm.everythingdone.views.particledismiss

import android.graphics.HardwareRenderer
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.RenderNode
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
import androidx.annotation.RequiresApi
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.BuildConfig
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * 卡片使用独立 Surface 时，临时将真实 FAB 的绘制放在其上方。
 * 按钮仍留在原 View 树处理输入、布局与动画；只转移绘制，不复制按钮或修改业务状态。
 * 按钮内容记录到可复用的小位图，阴影仍由 HWUI 绘制；静止复用同一帧，不读回 GPU 像素。
 */
@RequiresApi(34)
internal class ParticleFabSurface private constructor(private val source: FloatingActionButton,
                                                     parentSurface: SurfaceControl) {
    private val root = checkNotNull(source.rootSurfaceControl)
    private val originalTransitionAlpha = source.transitionAlpha
    private val lighting = checkNotNull(ParticleCardSnapshot.of(source, source.z))
    private val maxZ = max(source.z, source.compatElevation + source.compatPressedTranslationZ)
    private val ratio = maxZ / max(lighting.lightZ - maxZ, maxZ.coerceAtLeast(1f))
    private val lightDistance = max(max(abs(lighting.lightX), abs(lighting.lightY)),
        max(abs(source.width - lighting.lightX), abs(source.height - lighting.lightY)))
    private val padding = ceil(max(maxZ * 1.5f, (lightDistance + lighting.lightRadius) * ratio) + 2f).toInt()
    private val width = source.width * 2 + padding * 2
    private val height = source.height * 2 + padding * 2
    private val rotation = lighting.rotation
    private val renderWidth = if (rotation % 2 == 0) width else height
    private val renderHeight = if (rotation % 2 == 0) height else width
    private lateinit var reader: ImageReader
    private lateinit var renderer: HardwareRenderer
    private val node = RenderNode("particleFabFrame")
    private val button = RenderNode("particleFabView")
    private var content: Bitmap? = null
    private lateinit var layer: SurfaceControl
    private var leases = 0
    private var active = false
    private var closing = false
    private var disposed = false
    private var drawing = false
    private var rendering = false
    private var acquiredImages = 0
    private var previousPose: List<Float>? = null
    private var pendingFrame: Frame? = null
    private var firstFrame: Frame? = null
    private val readyCallbacks = mutableListOf<(Result<ParticleFabSurface?>) -> Unit>()
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
        layer = SurfaceControl.Builder().setName("ParticleFabForeground").setParent(parentSurface)
            .setFormat(PixelFormat.RGBA_8888).setOpaque(false).setBufferSize(renderWidth, renderHeight).build()
        SurfaceControl.Transaction().use {
            it.setLayer(layer, Int.MAX_VALUE).setVisibility(layer, false).apply()
        }
        renderer.setName("particleFab")
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
            if (!active) {
                firstFrame?.image?.let { closeUnused(it) }
                firstFrame = frame
                val callbacks = readyCallbacks.toList()
                readyCallbacks.clear()
                callbacks.forEach { it(Result.success(this)) }
            } else runCatching {
                // 接管后只有该层在画 FAB；持续更新不依赖窗口再次提交可见内容。
                SurfaceControl.Transaction().use { putFrame(it, frame); it.apply() }
            }.onFailure { closeNow(failed = true) }
        }, main)
        source.viewTreeObserver.addOnPreDrawListener(preDraw)
        source.addOnAttachStateChangeListener(attachment)
    }

    /** 与卡片的第一步窗口交接合并，不让 FAB 的半透明阴影出现两份。 */
    fun enter(transaction: SurfaceControl.Transaction): Boolean {
        if (disposed || closing) return false
        if (active) return true
        val frame = firstFrame ?: return false
        firstFrame = null
        active = true
        main.removeCallbacks(timeout)
        source.transitionAlpha = 0f
        putFrame(transaction, frame)
        return true
    }

    fun release(onFailure: () -> Unit) {
        failureCallbacks.remove(onFailure)
        if (disposed || --leases > 0) return
        closing = true
        main.removeCallbacks(timeout)
        source.transitionAlpha = originalTransitionAlpha
        if (active && source.isAttachedToWindow) {
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
        if (disposed || closing || rendering || drawing || acquiredImages >= reader.maxImages) return
        if (!source.isAttachedToWindow) { closeNow(failed = true); return }
        val parent = source.parent as? View ?: return
        val parentAt = IntArray(2).also { parent.getLocationOnScreen(it) }
        val decorAt = IntArray(2).also { source.rootView.getLocationOnScreen(it) }
        val layoutX = parentAt[0] + source.left - parent.scrollX
        val layoutY = parentAt[1] + source.top - parent.scrollY
        var alpha = if (source.isShown) source.alpha * originalTransitionAlpha else 0f
        var ancestor: View? = parent
        while (ancestor != null) { alpha *= ancestor.alpha; ancestor = ancestor.parent as? View }
        val pose = listOf(layoutX.toFloat(), layoutY.toFloat(), source.translationX, source.translationY,
            source.scaleX, source.scaleY, source.rotation, source.pivotX, source.pivotY, source.z, alpha,
            source.width.toFloat(), source.height.toFloat())
        if (pose == previousPose && !source.isDirty) return
        previousPose = pose
        val centre = floatArrayOf(source.width / 2f, source.height / 2f)
        source.matrix.mapPoints(centre)
        val x = layoutX + centre[0] - width / 2f
        val y = layoutY + centre[1] - height / 2f
        pendingFrame = Frame(x - decorAt[0], y - decorAt[1], alpha)
        button.setPosition(0, 0, source.width, source.height)
        button.translationX = layoutX - x + source.translationX
        button.translationY = layoutY - y + source.translationY
        button.pivotX = source.pivotX; button.pivotY = source.pivotY
        button.scaleX = source.scaleX; button.scaleY = source.scaleY; button.rotationZ = source.rotation
        button.elevation = source.z
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
        canvas.enableZ(); canvas.drawRenderNode(button); canvas.disableZ()
        node.endRecording()
        val lx = lighting.lightX + layoutX - x
        val ly = lighting.lightY + layoutY - y
        val lightX = when (rotation) { 1 -> height - ly; 2 -> width - lx; 3 -> ly; else -> lx }
        val lightY = when (rotation) { 1 -> lx; 2 -> height - ly; 3 -> width - lx; else -> ly }
        renderer.setLightSourceGeometry(lightX, lightY, lighting.lightZ, lighting.lightRadius)
        renderer.setLightSourceAlpha(lighting.ambientAlpha, lighting.spotAlpha)
        rendering = true
        main.postDelayed(timeout, 1200)
        if (BuildConfig.DEBUG) renderedFramesForTests++
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
        callbacks.forEach { it(Result.failure(IllegalStateException("FAB 硬件层不可用"))) }
        val failures = failureCallbacks.toList(); failureCallbacks.clear()
        if (failed) failures.forEach { main.post(it) }
        worker.post {
            if (::renderer.isInitialized) runCatching { renderer.destroy() }
            if (::reader.isInitialized) runCatching { reader.close() }
            node.discardDisplayList(); button.discardDisplayList()
            content?.recycle(); content = null
        }
    }

    companion object {
        private val main = Handler(Looper.getMainLooper())
        private val worker by lazy { Handler(HandlerThread("ParticleFabRelease").apply { start() }.looper) }
        private val surfaces = WeakHashMap<View, ParticleFabSurface>()
        var renderedFramesForTests = 0

        fun prepare(source: View, particleView: SurfaceView, onFailure: () -> Unit,
                    done: (Result<ParticleFabSurface?>) -> Unit, retries: Int = 60) {
            val fab = source.rootView.findViewById<View>(R.id.fab_create) as? FloatingActionButton
            if (fab == null) { done(Result.success(null)); return }
            if (!fab.isAttachedToWindow || fab.width <= 0 || fab.height <= 0 || !source.isAttachedToWindow) {
                done(Result.failure(IllegalStateException("FAB 尚未布局或已离开窗口"))); return
            }
            if (surfaces[fab]?.closing == true) {
                if (retries > 0) fab.postOnAnimation { prepare(source, particleView, onFailure, done, retries - 1) }
                else done(Result.failure(IllegalStateException("FAB 交接尚未结束")))
                return
            }
            val parentSurface = particleView.surfaceControl
            if (parentSurface == null || !parentSurface.isValid) {
                if (retries > 0 && particleView.isAttachedToWindow)
                    particleView.postOnAnimation { prepare(source, particleView, onFailure, done, retries - 1) }
                else done(Result.failure(IllegalStateException("粒子 Surface 不可用")))
                return
            }
            val existing = surfaces[fab]?.takeUnless { it.closing || it.disposed }
            val target = existing ?: runCatching { ParticleFabSurface(fab, parentSurface) }
                .getOrNull()?.also { surfaces[fab] = it }
            if (target == null) { done(Result.failure(IllegalStateException("FAB 硬件层创建失败"))); return }
            target.leases++
            target.failureCallbacks += onFailure
            if (target.active || target.firstFrame != null) done(Result.success(target))
            else { target.readyCallbacks += done; target.refreshSafely() }
        }

        /** 离屏捕获包含 FAB 的祖先时，同步记录原控件；恢复发生在同一调用栈内。 */
        fun withNativeDrawing(root: View, draw: () -> Unit) {
            if (Build.VERSION.SDK_INT < 34) { draw(); return }
            val affected = surfaces.values.filter { surface ->
                var parent: View? = surface.source
                while (parent != null && parent !== root) parent = parent.parent as? View
                parent === root && surface.active && !surface.drawing
            }
            affected.forEach { it.source.transitionAlpha = it.originalTransitionAlpha }
            try { draw() } finally { affected.forEach { if (!it.disposed && !it.closing) it.source.transitionAlpha = 0f } }
        }
    }
}
