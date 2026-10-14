package com.ywwynm.everythingdone.views.particledismiss

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Choreographer
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import com.ywwynm.everythingdone.views.InterceptTouchCardView
import com.ywwynm.everythingdone.views.DoingRecyclerView
import com.ywwynm.everythingdone.R
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 页面内容自首次显示起只进入共享 GL，真实树继续处理输入、布局及动画。
 * 壁纸仍在系统窗口中。卡片与粒子只在同一 GL 帧内替换，不改变 Surface 的可见性或位置。
 */
internal class ParticleDoingSceneContent(context: Context) : FrameLayout(context) {
    internal var scene: ParticleDoingGlScene? = null
    internal var drawNormally = false
    private var capturing = false
    init { setWillNotDraw(false) }
    override fun draw(canvas: Canvas) { if (capturing || drawNormally) super.draw(canvas) }
    override fun dispatchDraw(canvas: Canvas) { if (capturing || drawNormally) super.dispatchDraw(canvas) }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL) scene?.inputFeedbackChanged()
        return super.dispatchTouchEvent(event)
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        scene?.inputFeedbackChanged()
        return super.dispatchKeyEvent(event)
    }
    fun record(block: () -> Unit) {
        check(!capturing)
        capturing = true
        try { block() } finally { capturing = false }
    }
}

@RequiresApi(34)
internal class ParticleDoingGlScene(private val activity: Activity, private val content: ParticleDoingSceneContent,
    private val window: ParticleWindowSurface, private val report: ParticlePresentationDiagnostics.Run?,
    private val failed: () -> Unit) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    // 图像已完成后的交接不依赖原窗口遍历，不能被下一次遍历的同步屏障多挡一拍。
    private val frameCallbacks = Handler.createAsync(Looper.getMainLooper())
    private val scrollingContent = content.findViewById<DoingRecyclerView>(R.id.rv_thing_doing)
    private val worker = Handler(HandlerThread("ParticleDoingSceneCopy").apply { start() }.looper)
    private val node = RenderNode("particleDoingScene")
    private val cache = ParticleViewCaptureTree.Cache()
    private val renderer = HardwareRenderer()
    // 当前显示一张、正在交接一张，另留 acquireLatestImage 合并过期帧需要的两个空位。
    private fun createReader() = ImageReader.newInstance(content.width, content.height, PixelFormat.RGBA_8888, 4,
        HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
    private var reader = createReader()
    private var windowVisible = true
    private val hardwareImages = ParticleGlHardwareImages()
    private val layers = (0..1).map { ParticleGlBitmapLayer("doing-scene-$it", -2, false, underlay = true).apply {
        hardwareImages = this@ParticleDoingGlScene.hardwareImages
    } }
    private var front = 0
    private var first = true
    private var busy = false
    private var closed = false
    @Volatile private var exiting = false
    private var captureRequested = true
    private var captureAfterTraversal = false
    private var captureAfterAnimation = false
    private var animationFrameView: View? = null
    private val animationFrameDispatch = Runnable {
        val source = animationFrameView
        animationFrameView = null
        if (source != null && source.isAttachedToWindow && !closed && !exiting && windowVisible) {
            source.invalidate()
            // 尺寸或层级变化仍先完成原生布局；边缘动画只更新已经布局的内容。
            if (content.isLayoutRequested) content.postInvalidateOnAnimation() else requestCapture()
        }
    }
    private val animationFrame = Choreographer.FrameCallback { frameCallbacks.post(animationFrameDispatch) }
    private var inputFeedbackPending = true
    private var captureStart = 0L
    private var frames = 0
    private val frameGate = ParticlePageFrameGate()
    private val retained get() = frameGate.retained
    private val readerLeases = java.util.IdentityHashMap<ImageReader, Int>()
    private val retiredReaders = mutableSetOf<ImageReader>()
    private var readerStopped = false
    @Volatile private var capturePhase = "idle"
    // Ripple 可由 RenderThread 自行续帧；消费队列不能依赖主线程及时运行。
    // 当前显示帧之外最多保留一个新图像，其余帧在消费者线程立即归还。
    private val imageInFlight = AtomicBoolean(false)
    private val producerNeedsRefresh = AtomicBoolean(false)
    private var nativeFeedbackRefreshes = 0
    private var feedbackDrainFrames = 0
    private var feedbackCommitPending = false
    @Volatile private var skippedProducerFrames = 0
    private var activeSwitch: Switch? = null
    private val switches = ArrayDeque<Switch>()
    private val timeout = Runnable {
        report?.event("doing_scene_timeout_state", mapOf("phase" to capturePhase, "retained" to retained,
            "imageInFlight" to imageInFlight.get(), "skippedProducerFrames" to skippedProducerFrames,
            "glThread" to Thread.getAllStackTraces().entries.firstOrNull { it.key.name == "ParticleGl" }
                ?.value?.joinToString("\n")))
        fail("capture_timeout")
    }
    private val preDraw = ViewTreeObserver.OnPreDrawListener { requestCapture(); true }
    private data class Switch(val stage: String, val nativeChange: () -> Unit,
                              val glChange: () -> Unit, val done: (Boolean) -> Unit)

    init {
        window.pageContentActive = true
        node.setPosition(0, 0, content.width, content.height)
        renderer.setName("ParticleDoingScene")
        renderer.isOpaque = false
        renderer.setSurface(reader.surface)
        renderer.setContentRoot(node)
        ParticleWindowLighting.apply(renderer, content)
        attachReader()
        renderer.start()
        content.viewTreeObserver.addOnPreDrawListener(preDraw)
        report?.event("doing_scene_created", mapOf("nativeDrawing" to false, "systemWallpaper" to true,
            "width" to content.width, "height" to content.height, "singlePageOwner" to true))
    }

    // 先由页面所有者登记 scene，同步捕获失败时才能完整解除绘制所有权。
    fun start() = requestCapture()

    /** 原生波纹的进入／退出由输入派发触发；普通动态帧继续走原提交节拍。 */
    fun inputFeedbackChanged() { inputFeedbackPending = true }

    private fun attachReader() {
        reader.setOnImageAvailableListener({ images ->
            val availableAt = if (ParticleBeatTrace.enabled) System.nanoTime() else 0L
            capturePhase = "image_available"
            val image = runCatching { images.acquireLatestImage() }.onFailure { error ->
                main.post { if (!closed && !exiting) fail("image_acquire_${error.javaClass.simpleName}") }
            }.getOrNull()
            // acquireLatest 会合并通知；后续通知没有新图像是正常情况。
            if (image == null) return@setOnImageAvailableListener
            val acquiredAt = if (ParticleBeatTrace.enabled) System.nanoTime() else 0L
            if (exiting || !imageInFlight.compareAndSet(false, true)) {
                skippedProducerFrames++
                if (!exiting) producerNeedsRefresh.set(true)
                image.close(); return@setOnImageAvailableListener
            }
            var fenceCompletedAt = 0L
            val buffer = runCatching {
                capturePhase = "image_fence"
                image.fence.use { check(!it.isValid || it.await(Duration.ofMillis(700))) }
                if (ParticleBeatTrace.enabled) fenceCompletedAt = System.nanoTime()
                image.hardwareBuffer
            }.getOrNull()
            val fencedAt = if (ParticleBeatTrace.enabled) System.nanoTime() else 0L
            if (ParticleBeatTrace.enabled) ParticleBeatTrace.record(ParticleBeatTrace.PAGE_IMAGE_ACQUIRE,
                availableAt, acquiredAt, fenceCompletedAt, fencedAt)
            frameCallbacks.post {
                val receivedAt = if (ParticleBeatTrace.enabled) System.nanoTime() else 0L
                capturePhase = "upload_queued"
                if (buffer == null || closed || exiting || !busy) {
                    buffer?.close(); image.close()
                    imageInFlight.set(false)
                    if (buffer == null && !closed && !exiting) fail("image_fence_or_buffer")
                    else if (!closed && !exiting && windowVisible && !busy) {
                        // 原生 Ripple 的 RenderThread 动画可能没有 UI invalidate。
                        // 自动续帧只通知刷新，不把它当作卡片交接所需的新布局帧。
                        // 先归还 Image，再在下一次遍历受控捕获；退出仍保持有界消费。
                        captureRequested = true
                        captureAfterTraversal = false
                        nativeFeedbackRefreshes++
                        feedbackDrainFrames = 2
                        ParticleTouchFeedback.invalidateNativeRipples(content)
                        content.postInvalidateOnAnimation()
                    }
                    return@post
                }
                frameGate.retain()
                readerLeases[images] = (readerLeases[images] ?: 0) + 1
                val back = 1 - front
                val where = IntArray(2).also(content::getLocationInWindow)
                val switching = activeSwitch
                val previous = front
                val change = {
                    if (!closed && !exiting) {
                        window.retireGlImage(layers[previous])
                        layers[back].visible = true
                        switching?.glChange?.invoke()
                    }
                }
                // 普通动态更新在上传任务内一起换层，避免 GL→主线程→GL 再排一轮。
                // 首帧及卡片交接仍使用与该帧绑定的提交确认。
                val live = !first && switching == null
                window.uploadGlHardwareBuffer(layers[back], buffer, where[0].toFloat(), where[1].toFloat(), 1f, {
                    image.close()
                    frameCallbacks.post {
                        val leases = checkNotNull(readerLeases[images]) - 1
                        if (leases == 0) {
                            readerLeases.remove(images)
                            if (retiredReaders.remove(images)) {
                                images.close()
                                ParticleGl.post { hardwareImages.trimUnused() }
                            }
                        } else readerLeases[images] = leases
                        frameGate.release()
                        finishReaderClose()
                    }
                }, onUploaded = if (live) change else null) { ready ->
                    val uploadedAt = if (ParticleBeatTrace.enabled) System.nanoTime() else 0L
                    capturePhase = "upload_ready"
                    if (closed || exiting) return@uploadGlHardwareBuffer
                    if (!ready) { fail("upload_failed"); return@uploadGlHardwareBuffer }
                    val done: (Boolean) -> Unit = {
                        frameGate.submitted {
                            if (ParticleBeatTrace.enabled) ParticleBeatTrace.record(ParticleBeatTrace.PAGE_IMAGE,
                                captureStart, availableAt, fencedAt, receivedAt, uploadedAt, System.nanoTime())
                            if (!closed && !exiting) {
                                imageInFlight.set(false)
                                main.removeCallbacks(timeout)
                                front = back; busy = false; frames++
                                // 最后一张原生动画帧也可能在 GL 使用上一张时到达。
                                // 归还多余图像后保留一次刷新请求，不能让淡出停在倒数帧。
                                if (producerNeedsRefresh.getAndSet(false)) {
                                    captureRequested = true
                                    captureAfterTraversal = false
                                    nativeFeedbackRefreshes++
                                    feedbackDrainFrames = 2
                                    ParticleTouchFeedback.invalidateNativeRipples(content)
                                }
                                if (feedbackDrainFrames > 0) {
                                    // RenderThread 的末帧可早于 UI 线程的动画结束清理。
                                    // 再经过 UI 遍历和受控提交，把清理后的按钮列表送入 GL。
                                    // 由真实续帧触发且有界，不使用时长猜测或常驻轮询。
                                    feedbackDrainFrames--
                                    captureRequested = true
                                    captureAfterTraversal = false
                                    ParticleTouchFeedback.invalidateNativeRipples(content)
                                    if (feedbackDrainFrames == 0 && !feedbackCommitPending) {
                                        feedbackCommitPending = true
                                        // 原窗口提交会把 RenderThread 的结束通知送回 UI 线程；
                                        // 在该提交之后再记录一次，避免保留仍引用旧波纹属性的显示列表。
                                        ParticleDismissController.runAfterShown(content, 100) {
                                            feedbackCommitPending = false
                                            if (!closed && !exiting && windowVisible) {
                                                report?.event("doing_scene_native_feedback_commit")
                                                ParticleTouchFeedback.invalidateNativeRipples(content)
                                                captureRequested = true
                                                captureAfterTraversal = false
                                                content.postInvalidateOnAnimation()
                                            }
                                        }
                                    }
                                }
                                capturePhase = "idle"
                                activeSwitch = null
                                if (first || switching != null || frames % 60 == 0) report?.event("doing_scene_frame", mapOf(
                                    "frames" to frames, "stage" to (switching?.stage ?: "live"),
                                    "nativeFeedbackRefreshes" to nativeFeedbackRefreshes,
                                    "captureToCallbackMs" to (System.nanoTime() - captureStart) / 1e6,
                                    "contentAlpha" to content.alpha, "contentTransitionAlpha" to content.transitionAlpha,
                                    "surfaceAlpha" to window.view.alpha, "surfaceTranslationX" to window.view.translationX,
                                    "meaning" to "single_gl_buffer_not_final_screen"))
                                if (first) { first = false; report?.event("doing_scene_ready") }
                                switching?.done?.invoke(it)
                                // 忙碌期间的 preDraw 已完成输入／动画／布局。现在消费该请求，
                                // 不再等一次遍历；否则捕获稍超一拍就会固定丢掉下一拍。
                                if (switches.isNotEmpty() || (captureRequested && captureAfterTraversal)) capture()
                                else if (captureRequested) content.postInvalidateOnAnimation()
                            }
                        }
                        // 当前显示图像继续保留；旧图像读完且本次交换结束，才允许下一张。
                    }
                    // 交接要保留具体缓冲及层清单。普通更新仅按显示节拍取最新状态。
                    if (live) done(true)
                    else if (windowVisible && (first || switching != null))
                        window.changeGlLayersCommitted("gl-doing-scene-${switching?.stage ?: "initial"}", done, change)
                    else window.changeGlLayers {
                        change()
                        // 连续场景本身没有真实 View 交接；下一次更新只需排在本次 GL 状态更新之后。
                        // 等驱动呈现回调会把背景刷新节拍限制为一轮回调延迟。
                        main.post { done(true) }
                    }
                }
            }
        }, worker)
    }

    private fun requestCapture() {
        if (exiting) return
        if (ParticleBeatTrace.enabled) ParticleBeatTrace.record(ParticleBeatTrace.PAGE_REQUEST,
            System.nanoTime(), if (busy) 1L else 0L)
        captureRequested = true
        captureAfterTraversal = true
        if (!busy) capture()
    }

    /** 仅接续控件已经请求的动画帧；没有下一次请求时立即停止，不常驻捕获。 */
    fun requestAnimationFrame(source: View) {
        if (closed || exiting || !windowVisible || animationFrameView != null) return
        animationFrameView = source
        ParticleClock.postFrameCallback(animationFrame)
    }

    private fun cancelAnimationFrame() {
        animationFrameView = null
        ParticleClock.removeFrameCallback(animationFrame)
        frameCallbacks.removeCallbacks(animationFrameDispatch)
    }

    /** 背景明暗判定读取实际承载页面的 Surface，避免取到已停止绘制的空窗口。 */
    fun copyFrame(rect: android.graphics.Rect, bitmap: Bitmap, done: (Int) -> Unit): Boolean {
        val surfaceView = window.view as? android.view.SurfaceView ?: return false
        if (closed || first || !surfaceView.holder.surface.isValid) return false
        android.view.PixelCopy.request(surfaceView, rect, bitmap, done, main)
        return true
    }

    /** 先取得改动后的整页纹理，再将其与粒子／静态卡片变化放入同一 GL 提交。 */
    fun switch(stage: String, nativeChange: () -> Unit, glChange: () -> Unit, done: (Boolean) -> Unit) {
        if (closed || exiting) { done(false); return }
        switches += Switch(stage, nativeChange, glChange, done)
        if (!busy) capture()
    }

    private fun capture() {
        if (closed || exiting || busy || !windowVisible) return
        // 预测遍历不仅影响 RenderRequest，也会让 EdgeEffect.draw 读取未来时间。
        // 在同一 UI 队列中等动画阶段结束后再记录，保留已经完成的布局和属性更新。
        if (scrollingContent?.hasActiveEdgeEffect == true &&
            android.view.animation.AnimationUtils.currentAnimationTimeMillis() -
            android.os.SystemClock.uptimeMillis() > 1L) {
            if (!captureAfterAnimation) {
                captureAfterAnimation = true
                frameCallbacks.post {
                    captureAfterAnimation = false
                    if (!closed && !exiting) {
                        // 预测窗口可能已经缓存了子 View；让边缘效果在实际时间重新记录。
                        scrollingContent?.invalidate()
                        capture()
                    }
                }
            }
            return
        }
        if (!content.isAttachedToWindow) return
        if (content.width <= 0 || content.height <= 0) return
        if (content.width != reader.width || content.height != reader.height) {
            // 当前显示的图像继续来自旧队列；新尺寸首帧替换它并完成 GPU 读取后再关闭旧队列。
            val previous = reader
            reader = createReader()
            attachReader()
            renderer.setSurface(reader.surface)
            if ((readerLeases[previous] ?: 0) == 0) previous.close() else retiredReaders += previous
            node.setPosition(0, 0, content.width, content.height)
            report?.event("doing_scene_resized", mapOf("width" to content.width, "height" to content.height))
        }
        if (!captureRequested && switches.isEmpty() && !first) return
        busy = true; captureRequested = false
        captureAfterTraversal = false
        capturePhase = "surface_capture"
        activeSwitch = switches.removeFirstOrNull()
        captureStart = System.nanoTime()
        main.postDelayed(timeout, 2000)
        if (first && report != null) main.postDelayed({
            if (first && busy && !closed && !exiting) report.event("doing_scene_slow_initial_capture", mapOf(
                "phase" to capturePhase, "glThread" to Thread.getAllStackTraces().entries
                    .firstOrNull { it.key.name == "ParticleGl" }?.value?.joinToString("\n")))
        }, 500)
        try { activeSwitch?.nativeChange?.invoke() }
        catch (error: Exception) { fail(error.toString()); return }
        ParticleSurfaceFrames.capture(content) { pixels ->
            if (closed || exiting) { pixels?.values?.forEach(Bitmap::recycle); return@capture }
            if (pixels == null) { fail("surface_copy_failed"); return@capture }
            try {
                capturePhase = "recording"
                val recordingStarted = if (ParticleBeatTrace.enabled) System.nanoTime() else 0L
                // 容器只停止原窗口的 draw，不改变控件的 alpha、可访问性和触摸树。
                // 子控件的真实 alpha、阴影、裁剪及动画仍按原始树绘制。
                val canvas = node.beginRecording(content.width, content.height)
                // 原窗口不遍历这里的子树；根的 isDirty 不能代表全部后代已更新。
                // 每次捕获走真实 draw 更新失效的后代显示列表，静态子 View 仍由 HWUI 缓存。
                try { content.record { ParticleViewCaptureTree.record(canvas, content, pixels, cache = cache,
                    forceRootRecording = true) } }
                finally { node.endRecording() }
                capturePhase = "hardware_sync"
                val recordingFinished = if (ParticleBeatTrace.enabled) System.nanoTime() else 0L
                // 输入刚改变反馈状态时，先让原窗口遍历初始化 RenderThread 动画属性。
                // 普通动态帧不额外排队，保留流体与持续背景的原提交节拍。
                val afterWindowTraversal = inputFeedbackPending
                inputFeedbackPending = false
                fun submit() {
                    try {
                        if (closed || exiting) return
                        // 部分系统在 fling 中把 AnimationUtils 时钟预测到未来；默认请求会继承它，
                        // 让 HWUI 把后续真实帧连续判作 AlreadyDrawn。离屏提交使用同一 MONOTONIC
                        // 时基的实际时间，控件与粒子的动画进度仍由各自原有动画器决定。
                        val frameTime = System.nanoTime()
                        if (ParticleBeatTrace.enabled) ParticleBeatTrace.record(ParticleBeatTrace.PAGE_FRAME_TIME,
                            captureStart, android.view.animation.AnimationUtils.currentAnimationTimeMillis() * 1_000_000L,
                            frameTime)
                        val status = renderer.createRenderRequest().setVsyncTime(frameTime)
                            .setWaitForPresent(false).syncAndDraw()
                        if (ParticleBeatTrace.enabled) ParticleBeatTrace.record(ParticleBeatTrace.PAGE_CAPTURE, captureStart,
                            recordingStarted, recordingFinished, System.nanoTime(), status.toLong())
                        capturePhase = "hardware_submitted_$status"
                        if (first) report?.event("doing_scene_initial_sync", mapOf("status" to status,
                            "surfaceValid" to reader.surface.isValid, "drawingEnabled" to HardwareRenderer.isDrawingEnabled(),
                            "durationMs" to (System.nanoTime() - captureStart) / 1e6))
                        // 状态是位标志。FRAME_DROPPED 表示本次 vsync 已绘制或消费者尚在读取，
                        // 渲染器会在下次 vsync 自行补帧；保留整页和待处理交接，继续等图像回调。
                        if (status and (HardwareRenderer.SYNC_LOST_SURFACE_REWARD_IF_FOUND or
                                    HardwareRenderer.SYNC_CONTEXT_IS_STOPPED) != 0)
                            fail("hardware_draw_$status")
                        else if (status and HardwareRenderer.SYNC_FRAME_DROPPED != 0)
                            report?.event("doing_scene_frame_deferred", mapOf("status" to status,
                                "retained" to retained, "imageInFlight" to imageInFlight.get()))
                    } catch (error: Exception) { fail(error.toString()) }
                    finally { pixels.values.forEach(Bitmap::recycle) }
                }
                if (afterWindowTraversal) main.post { submit() } else submit()
            } catch (error: Exception) { pixels.values.forEach(Bitmap::recycle); fail(error.toString()) }
        }
    }

    fun prepareCard(source: View, spec: ParticleDismissSpec, sources: List<View>,
                    onFailure: () -> Unit, done: (ParticleCardHandoff?) -> Unit) {
        val card = ParticleGlBitmapLayer("doing-card-${System.nanoTime()}", 0, false)
        worker.post {
            val bitmap = runCatching { spec.snapshot.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
            main.post {
                if (closed || bitmap == null) { bitmap?.recycle(); done(null); return@post }
                window.uploadGlBitmap(card, bitmap, spec.originXPx, spec.originYPx, spec.sourceAlpha) { ready ->
                    if (!ready || closed) { window.closeGlLayer(card); done(null) }
                    else done(Handoff(source, sources, card, onFailure))
                }
            }
        }
    }

    private inner class Handoff(private val source: View, private val sources: List<View>,
        private val card: ParticleGlBitmapLayer, private val onFailure: () -> Unit) : ParticleCardHandoff {
        private val state = ParticleGlHandoffState()
        private var session: ParticleLayerSession? = null
        private var restore: (() -> Unit)? = null
        private var complete: (() -> Unit)? = null
        private var entered = false

        override fun start(window: ParticleWindowSurface, session: ParticleLayerSession, reverse: Boolean) {
            if (!state.beginStart()) return
            this.session = session
            switch("enter", {
                if (state.phase == ParticleGlHandoffState.Phase.STAGING) {
                    sources.forEach {
                        (it as? InterceptTouchCardView)?.setParticleAlphaLock(0f)
                        it.alpha = 0f
                    }
                    entered = true
                }
            }, {
                if (entered && state.phase != ParticleGlHandoffState.Phase.CLOSED) {
                    card.visible = !reverse
                    // UI 改动已进入本次捕获后，快速松手也不能取消配套的 GL 静态卡片。
                    // 返回快照尚未备好时仍显示完整卡片，下一次整页提交再一起撤除。
                    val play = state.phase != ParticleGlHandoffState.Phase.RETURNING
                    card.replacedBy = if (play) session else null
                    session.setVisible(play)
                }
            }) { success ->
                if (state.phase == ParticleGlHandoffState.Phase.STAGING) {
                    if (success) state.acceptStartFrame() else onFailure()
                }
            }
        }

        override fun leave(window: ParticleWindowSurface, session: ParticleLayerSession, showCard: Boolean,
                           restore: () -> Unit, done: () -> Unit) {
            if (!state.beginReturn()) {
                if (state.phase == ParticleGlHandoffState.Phase.CLOSED) { restore(); done() }
                return
            }
            this.restore = restore; complete = done
            switch("return", {
                this.restore?.invoke(); this.restore = null
            }, {
                session.setVisible(false); card.visible = false; card.replacedBy = null
            }) { success ->
                report?.event("doing_scene_handoff_returned", mapOf("success" to success, "entered" to entered,
                    "showCard" to showCard, "sourceAlpha" to source.alpha,
                    "sourceParentAlpha" to (source.parent as? View)?.alpha))
                dispose()
            }
        }

        override fun dispose() {
            if (!state.close()) return
            restore?.invoke(); restore = null
            window.changeGlLayers { session?.setVisible(false); card.visible = false }
            window.closeGlLayer(card)
            complete?.let { complete = null; main.post(it) }
        }
    }

    private fun fail(reason: String) {
        if (closed || exiting) return
        report?.event("doing_scene_failed", mapOf("reason" to reason))
        close()
        failed()
    }

    fun setWindowVisible(visible: Boolean) {
        windowVisible = visible
        window.pageContentActive = visible && !closed && !exiting
        if (!visible) {
            main.removeCallbacks(timeout)
            cancelAnimationFrame()
        }
        else if (!closed && !exiting) {
            captureRequested = true
            if (busy) main.postDelayed(timeout, 2000) else capture()
            content.postInvalidateOnAnimation()
        }
    }

    /** 停止生产新帧，保留窗口中已显示的整页；窗口转场负责它的退场。 */
    fun beginExit() {
        if (closed || exiting) return
        exiting = true
        cancelAnimationFrame()
        window.pageContentActive = false
        main.removeCallbacks(timeout)
        if (content.viewTreeObserver.isAlive) content.viewTreeObserver.removeOnPreDrawListener(preDraw)
        report?.event("doing_scene_exit_started", mapOf("frames" to frames, "keepWindowFrame" to true))
        renderer.stop()
        report?.event("doing_scene_producer_stopped")
    }

    override fun close() {
        if (closed) return
        beginExit()
        closed = true
        frameGate.close()
        ParticleGl.post { hardwareImages.close() }
        main.removeCallbacks(timeout)
        if (content.viewTreeObserver.isAlive) content.viewTreeObserver.removeOnPreDrawListener(preDraw)
        activeSwitch?.done?.invoke(false); activeSwitch = null
        switches.toList().forEach { it.done(false) }; switches.clear()
        // Activity.onDestroy 可能早于退出窗口从屏幕移除；此时不提交透明 GL 帧。
        // 整页纹理由 ParticleWindowSurface 在 decor 分离时与整个窗口一起释放。
        if ((!activity.isFinishing && !activity.isChangingConfigurations) || window.detached)
            layers.forEach(window::closeGlLayer)
        report?.event("doing_scene_closed", mapOf("frames" to frames,
            "nativeFeedbackRefreshes" to nativeFeedbackRefreshes,
            "skippedProducerFrames" to skippedProducerFrames,
            "keepWindowFrameUntilDetach" to ((activity.isFinishing || activity.isChangingConfigurations) && !window.detached)))
        // 共享 View 节点的记录、销毁及原窗口失效通知必须在同一线程完成。
        val retireBegan = System.nanoTime()
        renderer.destroy(); node.discardDisplayList(); cache.close()
        report?.event("doing_scene_retired", mapOf("elapsedMs" to (System.nanoTime() - retireBegan) / 1e6))
        worker.post {
            main.post { readerStopped = true; finishReaderClose() }
        }
    }

    private fun finishReaderClose() {
        if (closed && readerStopped && retained == 0) {
            readerStopped = false
            reader.close(); worker.looper.quitSafely()
            report?.event("doing_scene_capture_resources_released")
        }
    }
}
