package com.ywwynm.everythingdone.views.recording.fablesol

import android.annotation.TargetApi
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Region
import android.os.Build
import android.os.SystemClock
import android.view.AttachedSurfaceControl
import android.view.Surface
import android.view.SurfaceControl
import android.view.View
import android.view.ViewTreeObserver
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.helpers.DebugFileLogger
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.views.particledismiss.ParticleDirectSurfaceSource
import com.ywwynm.everythingdone.views.particledismiss.ParticleSnapshotParticipant
import org.json.JSONObject
import java.time.Duration
import java.util.concurrent.Executors

/** 卓易通 N 路径：自有图层保留最后缓冲；三个音频弹窗在同一缓冲中合成海浪与控件。 */
@TargetApi(34)
internal class WaveVisualizerFableSolDirect(context: Context,
    private val windowUnderlay: FableSolWindowUnderlay) : View(context), FableSolRenderView,
    ParticleSnapshotParticipant, ParticleDirectSurfaceSource {
    init { if (BuildConfig.DEBUG) FableSolCompositionPerformance.prepareDiagnosticLogs(context) }
    override val view: View get() = this
    override val retainsFrameOnOutputChange = true
    override val compatibilityHdrSupported: Boolean get() = !pqUnavailable && !failed
    override var onHdrAvailabilityChanged: (() -> Unit)? = null
    override var onGlFailure: ((String) -> Unit)? = null
    private val backgroundCutout = FableSolWindowBackgroundCutout(this)
    private val foregroundProbe = FableSolForegroundProbeSchedule()
    private val foregroundLayout: FableSolForegroundLayout?
        get() = (parent as? View)?.parent as? FableSolForegroundLayout
    private var foregroundSequence = 0L
    private val transition = FableSolLayerTransition()
    private class Layer(val ticket: FableSolLayerTransition.Ticket, val control: SurfaceControl,
                        val surface: Surface) {
        var frameReady = false
        var attached = false
        var released = false
        var windowDrawRecorded = false
        var pose: Pose? = null
    }
    private data class Pose(val x: Int, val y: Int, val clip: Rect, val visible: Boolean, val alpha: Float,
                            val reference: AttachedSurfaceControl?, val referenceGeneration: Long)
    private val layers = mutableListOf<Layer>()
    private var shown: Layer? = null
    private var rendering: Layer? = null
    private var rendererStarted = false
    private var releasing = false
    private var failed = false
    private var pqUnavailable = false
    private var strength = FableSolTuning.hdrStrength(context)
    private var offAfter = 0L
    private var frozen = false
    private var particleHolds = 0
    private var animating = false
    private val frameRate = FableSolDirectFrameRate()
    private var desiredFrameRate = 60f
    private var monitor: FableSolPerformanceMonitor? = null
    private var performance: FableSolCompositionPerformance? = null
    private var performanceStateAt = 0L
    private var nextPresented: (() -> Unit)? = null
    private var lifecycle = 0L
    private var particleGeneration = 0L
    private var transactionSequence = 0L
    private val preDraw = ViewTreeObserver.OnPreDrawListener { reconcile(); true }
    private val poll = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || releasing || failed) return
            refreshFrameRateTarget()
            render.setDisplayHdrSdrRatio(ratio())
            monitor?.setDisplayModeRefreshRate((display?.mode?.refreshRate ?: 0f).toDouble())
            reconcile()
            samplePerformanceState()
            postDelayed(this, 250)
        }
    }
    private val render = FableSolGlRenderThread(context, resources.displayMetrics.density.toDouble(),
        onHdrStatus = { active, diagnostic, attemptedPq, id ->
            post {
                if (releasing || rendering?.ticket?.id != id || !isAttachedToWindow) return@post
                record("egl", "target=$id active=$active $diagnostic")
                if (attemptedPq && !active && !pqUnavailable) {
                    pqUnavailable = true
                    onHdrAvailabilityChanged?.invoke()
                    updateRequest()
                }
            }
        },
        onFatalError = { message -> post { if (!releasing && isAttachedToWindow) fail(message) } })

    private fun output() = if (!pqUnavailable && strength > FableSolHdrPolicy.STRENGTH_OFF)
        FableSolScreenOutput.PQ else FableSolScreenOutput.SDR

    private fun updateRequest() {
        if (width > 0 && height > 0) transition.request(FableSolLayerTransition.Spec(output(), width, height))
    }

    internal fun startCompositionPerformance(window: android.view.Window, scenario: String) {
        if (!BuildConfig.DEBUG || performance != null) return
        val probe = FableSolCompositionPerformance(window, this, scenario)
        performance = probe
        foregroundLayout?.performance = probe
        render.setCompositionPerformance(probe)
        samplePerformanceState(force = true)
    }

    private fun stopCompositionPerformance() {
        foregroundLayout?.performance = null
        render.setCompositionPerformance(null)
        performance?.stop()
        performance = null
    }

    private fun samplePerformanceState(force: Boolean = false) {
        val perf = performance ?: return
        val now = SystemClock.uptimeMillis()
        if (!force && now - performanceStateAt < 2000L) return
        performanceStateAt = now
        val currentDisplay = display
        perf.state(JSONObject().put("sampledAtUptimeMs", now).put("actualOutput", shown?.ticket?.spec?.output?.name)
            .put("requestedOutput", output().name).put("preparing", transition.preparing?.id)
            .put("animating", animating).put("frozen", frozen).put("particleHolds", particleHolds)
            .put("visible", isShown && windowVisibility == VISIBLE).put("hdrStrength", strength)
            .put("waveWidth", width).put("waveHeight", height)
            .put("foregroundWidth", foregroundLayout?.width).put("foregroundHeight", foregroundLayout?.height)
            .put("foregroundBufferLimit", FableSolForegroundCapture.BUFFER_LIMIT).put("foregroundFormat", "RGBA_8888")
            .put("foregroundRetirement", "gpu_fence_async")
            .put("foregroundRetirementFence", "after_last_foreground_sample")
            .put("foregroundScheduling", "latest_before_present_cached_scene")
            .put("votedFps", votedFps).put("refreshHz", currentDisplay?.refreshRate)
            .put("frameRatePolicy", "direct_90_if_available_minimum_seamless")
            .put("desiredFps", desiredFrameRate)
            .put("frameRateMinimum", frameRate.applied?.minimum)
            .put("frameRateMinimumRejected", frameRate.minimumRejected)
            .put("frameRateVoteFailures", frameRate.failures)
            .put("modeHz", currentDisplay?.mode?.refreshRate).put("modeId", currentDisplay?.mode?.modeId)
            .put("hdrSdrRatio", ratio().takeIf { it.isFinite() })
            .put("foregroundSeq", foregroundSequence).put("lifecycle", lifecycle)
            .put("perfHudEnabled", FableSolTuning.isPerfHudEnabled(context)))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshFrameRateTarget()
        lifecycle++
        windowUnderlay.onContainerChanged = {
            particleGeneration++
            record("order_reference_changed", "generation=${windowUnderlay.generation} valid=${windowUnderlay.control != null} failure=${windowUnderlay.failure}")
            // 子窗口回调中不重入窗口配置；本帧 preDraw 也会再次核对引用。
            post { reconcile() }
        }
        viewTreeObserver.addOnPreDrawListener(preDraw)
        parent?.requestTransparentRegion(this)
        record("attached")
        removeCallbacks(poll); post(poll)
        post { reconcile() }
        val token = lifecycle
        postDelayed({
            if (token == lifecycle && !releasing && !failed && isAttachedToWindow && isShown &&
                windowVisibility == VISIBLE && !rendererStarted)
                fail("window_order_reference_timeout:${windowUnderlay.failure}")
        }, 6000L)
    }

    private fun reconcile() {
        if (failed || releasing || !isAttachedToWindow || width < 1 || height < 1) return
        updateRequest()
        try {
            windowUnderlay.synchronizeWindow()
            windowUnderlay.failure?.let { fail("media_window:$it"); return }
            backgroundCutout.synchronize(shown?.frameReady == true && isShown && windowVisibility == VISIBLE)
            shown?.let { updatePose(it) }
            if (transition.preparing == null && (particleHolds == 0 || !rendererStarted) &&
                SystemClock.uptimeMillis() >= offAfter && isShown && windowVisibility == VISIBLE &&
                rootSurfaceControl != null && windowUnderlay.control != null) {
                // 若快速反向丢弃过候选，GPU 已不再连接旧屏幕缓冲，需准备同空间的新图层。
                transition.begin(force = rendererStarted && rendering !== shown)?.let(::prepare)
            }
            updateAnimating()
            if (BuildConfig.DEBUG) {
                val layer = shown
                val visible = layer?.pose?.let { it.visible && it.alpha >= .99f } == true &&
                    particleHolds == 0 && transition.preparing == null
                if (foregroundProbe.shouldSample(layer?.ticket?.id, visible, SystemClock.uptimeMillis()))
                    layer?.let { sampleWindowForeground("stable_visible", it) }
            }
        } catch (error: Exception) { fail(error.toString()) }
    }

    private fun prepare(ticket: FableSolLayerTransition.Ticket) {
        val control = SurfaceControl.Builder().setName("FableSolDirect-${System.identityHashCode(this)}-${ticket.id}")
            .setBufferSize(ticket.spec.width, ticket.spec.height).setFormat(PixelFormat.RGBA_8888)
            .setOpaque(false).setHidden(true).build()
        val layer = try { Layer(ticket, control, Surface(control)) }
            catch (error: Exception) { control.release(); throw error }
        // 先以隐藏状态附着到下层媒体窗口，候选 PQ 缓冲不会提前露出。
        val root = rootSurfaceControl
        val attachment = buildAttachment(control)
        if (root == null || attachment == null) {
            attachment?.close()
            transition.discard(ticket)
            layer.surface.release(); control.release()
            postDelayed({ reconcile() }, 32)
            return
        }
        layers += layer
        rendering = layer
        animating = false
        render.setHdrStrength(strength)
        attachment.use { transaction ->
            applyPose(transaction, layer, pose().copy(visible = false))
            check(root.applyTransactionOnDraw(transaction)) { "direct_hidden_attach_rejected" }
        }
        layer.attached = true
        invalidate()
        record("prepare", "target=${ticket.id} output=${ticket.spec.output} size=${ticket.spec.width}x${ticket.spec.height}")
        sampleWindowForeground("preparing", layer)
        val token = lifecycle
        val ready: (FableSolScreenOutput) -> Unit = ready@{ actual ->
            if (token != lifecycle || releasing || !transition.isCurrent(ticket)) return@ready
            layer.frameReady = true
            // 此时旧 EGL 已释放，未显示的过期候选可安全回收。
            layers.filter { it !== layer && it !== shown }.toList().forEach(::releaseLayer)
            record("buffers_ready", "target=${ticket.id} actual=$actual")
            updateRequest()
            if (actual != ticket.spec.output || !transition.canPublish(ticket)) {
                transition.discard(ticket)
                record("candidate_superseded", "target=${ticket.id}")
                reconcile()
            } else publish(layer)
        }
        if (!rendererStarted) {
            rendererStarted = true
            render.attach(layer.surface, ticket.spec.width, ticket.spec.height,
                ticket.spec.output == FableSolScreenOutput.PQ, ratio(), preferPq = true,
                sessionId = ticket.id, directReady = ready, requireForeground = foregroundLayout != null)
        } else {
            render.replaceDirectSurface(layer.surface, ticket.spec.width, ticket.spec.height,
                ticket.spec.output == FableSolScreenOutput.PQ, ratio(), ticket.id, ready)
        }
        foregroundLayout?.startForegroundCapture({ frame ->
            if (releasing || failed || !isAttachedToWindow) frame.close()
            else render.submitForeground(frame) {
                if (!releasing && isAttachedToWindow) {
                    foregroundSequence = frame.sequence
                    if (frame.sequence <= 3 || (frame.sequence and (frame.sequence - 1)) == 0L)
                        record("foreground_imported", "sequence=${frame.sequence} size=${width}x$height")
                }
            }
        }, ::fail)
        // 捕获／切换异常不留下永久旧画面。失败明确降级，不能伪报新图层就绪。
        postDelayed({
            if (token == lifecycle && !releasing && transition.isCurrent(ticket)) fail("target_ready_timeout:${ticket.id}")
        }, 6000)
    }

    private fun pose(): Pose {
        val xy = windowUnderlay.positionOf(this)
        val clip = Rect()
        val reference = windowUnderlay.control
        val visible = isShown && windowVisibility == VISIBLE && getLocalVisibleRect(clip) && reference != null
        var opacity = 1f
        var ancestor: View? = this
        while (ancestor != null) {
            opacity *= ancestor.alpha
            ancestor = ancestor.parent as? View
        }
        return Pose(xy[0], xy[1], clip, visible, opacity.coerceIn(0f, 1f),
            reference, windowUnderlay.generation)
    }

    private fun applyPose(transaction: SurfaceControl.Transaction, layer: Layer, next: Pose) {
        // 系统保证媒体窗口低于原 Dialog；此处只定位媒体窗口内自有的海浪图层。
        transaction.setPosition(layer.control, next.x.toFloat(), next.y.toFloat())
            .setCrop(layer.control, next.clip).setLayer(layer.control, 1)
            .setOpaque(layer.control, false).setAlpha(layer.control, next.alpha)
            .setVisibility(layer.control, next.visible)
        layer.pose = next
    }

    private fun buildAttachment(control: SurfaceControl): SurfaceControl.Transaction? =
        windowUnderlay.control?.buildReparentTransaction(control)

    private fun publish(layer: Layer) {
        updateRequest()
        if (!transition.canPublish(layer.ticket)) {
            transition.discard(layer.ticket)
            reconcile()
            return
        }
        val root = rootSurfaceControl
        val transaction = buildAttachment(layer.control)
        if (root == null || transaction == null || windowUnderlay.control == null) {
            transaction?.close()
            val token = lifecycle
            postDelayed({
                if (token == lifecycle && !releasing && transition.isCurrent(layer.ticket)) publish(layer)
            }, 32)
            return
        }
        val previous = shown
        val token = lifecycle
        val id = ++transactionSequence
        layer.attached = true
        // 与图层交接同一帧停止绘制海浪区域的背景；不清除窗口中的前景像素。
        shown = layer
        particleGeneration++
        frameRate.reset()
        try {
            backgroundCutout.synchronize(true)
            foregroundLayout?.showCompositedForeground()
            applyPose(transaction, layer, pose())
            previous?.let { transaction.setVisibility(it.control, false) }
            transaction.addTransactionCommittedListener(context.mainExecutor) {
                if (token == lifecycle && !releasing && transition.committed(layer.ticket)) {
                    record("handoff_committed", "transaction=$id target=${layer.ticket.id} previous=${previous?.ticket?.id}")
                    previous?.let(::releaseLayer)
                    nextPresented?.let { nextPresented = null; it() }
                    reconcile()
                }
            }
            check(root.applyTransactionOnDraw(transaction)) { "direct_handoff_rejected" }
            record("handoff_scheduled", "transaction=$id target=${layer.ticket.id}")
            invalidate()
        } finally { transaction.close() }
    }

    private fun updatePose(layer: Layer, force: Boolean = false, presented: ((Boolean) -> Unit)? = null) {
        if (!layer.attached || layer.released) { presented?.invoke(false); return }
        val next = pose()
        if (!force && layer.pose == next) return
        val root = rootSurfaceControl ?: run { presented?.invoke(false); return }
        val token = lifecycle
        val id = ++transactionSequence
        val attachment = buildAttachment(layer.control) ?: run {
            // 媒体窗口正在重建，旧子层已不可显示；等新窗口就绪后重新附着。
            presented?.invoke(false); return
        }
        attachment.use { transaction ->
            val previous = layer.pose
            applyPose(transaction, layer, next)
            if (previous?.visible != next.visible || (previous?.alpha == 0f) != (next.alpha == 0f))
                record("visibility", "target=${layer.ticket.id} visible=${next.visible} alpha=${next.alpha}")
            if (presented != null) {
                if (Build.VERSION.SDK_INT >= 35) {
                    transaction.addTransactionCompletedListener(presentationWorker) { stats ->
                        val valid = runCatching { stats.presentFence.use { it.isValid && it.await(Duration.ofMillis(900)) } }.getOrDefault(false)
                        post { presented(valid && token == lifecycle && layer === shown && !releasing) }
                    }
                } else transaction.addTransactionCommittedListener(context.mainExecutor) {
                    presented(token == lifecycle && layer === shown && !releasing)
                }
            }
            check(root.applyTransactionOnDraw(transaction)) { "direct_pose_rejected:$id" }
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val layer = shown ?: return
        if (!layer.frameReady) return
        if (BuildConfig.DEBUG && !layer.windowDrawRecorded) {
            layer.windowDrawRecorded = true
            val hardware = canvas.isHardwareAccelerated
            post { record("window_background_cutout", "target=${layer.ticket.id} hardware=$hardware stillShown=${layer === shown}") }
        }
    }

    override fun gatherTransparentRegion(region: Region?): Boolean {
        // 窗口像素中还有计时、按钮和滑杆，不能将海浪矩形声明为可以跳过的透明区。
        // 固定 N 的全尺寸白圈 View 会扣除整块提示；实际控件只扣除局部，二者并不等价。
        // 按普通 View 保守保留本区域的窗口像素；透明部分只在背景 Drawable 中裁出。
        super.gatherTransparentRegion(region)
        return false
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isAttachedToWindow) post { reconcile() }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (isAttachedToWindow) {
            if (visibility != VISIBLE) releaseAll()
            else { removeCallbacks(poll); post(poll) }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (isAttachedToWindow) post { reconcile() }
    }

    private fun updateAnimating() {
        val wanted = rendererStarted && !releasing && transition.preparing == null && rendering === shown &&
            !frozen && particleHolds == 0 && isShown && windowVisibility == VISIBLE
        if (wanted != animating) { animating = wanted; render.setAnimating(wanted) }
        shown?.surface?.takeIf { it.isValid }?.let { surface ->
            val change = frameRate.update(surface, desiredFrameRate, wanted, Build.VERSION.SDK_INT,
                SystemClock.uptimeMillis()) { vote ->
                val compatibility = if (vote.minimum) Surface.FRAME_RATE_COMPATIBILITY_AT_LEAST
                    else Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
                // 不为升频触发有视觉中断的模式切换，也不通过反复撤票重投刺激刷新率。
                surface.setFrameRate(vote.hz, compatibility, Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)
            }
            if (change != null) record("frame_rate_vote",
                "requested=${change.requested} applied=${change.applied} fallback=${change.fallback} failure=${change.failure}")
        }
    }
    private val votedFps: Float get() = frameRate.applied?.hz ?: -1f

    /** 模式查询复用既有 250 ms 轮询，不在逐帧 preDraw 里反复查询和分配列表。 */
    private fun refreshFrameRateTarget() {
        val current = display ?: return
        val mode = current.mode
        desiredFrameRate = FableSolDirectFrameRate.desiredRate(current.supportedModes.map {
            FableSolDirectFrameRate.Mode(it.physicalWidth, it.physicalHeight, it.refreshRate)
        }, mode.physicalWidth, mode.physicalHeight)
    }
    private fun ratio(): Float = display?.takeIf { it.isHdrSdrRatioAvailable }?.hdrSdrRatio ?: Float.NaN

    override fun setHdrStrength(strength: Float) {
        val next = strength.coerceIn(FableSolHdrPolicy.STRENGTH_OFF, FableSolHdrPolicy.MAX_STRENGTH)
        if (next == this.strength) return
        this.strength = next
        render.setHdrStrength(next)
        offAfter = if (output() == FableSolScreenOutput.SDR && shown?.ticket?.spec?.output == FableSolScreenOutput.PQ)
            SystemClock.uptimeMillis() + 210L else 0L
        record("strength_request", "strength=$next output=${output()} holds=$particleHolds")
        reconcile()
        if (offAfter > 0L) postDelayed({ reconcile() }, 210L)
    }

    override fun willChangeSurfaceFormat(strength: Float) = false // 由本容器保留旧缓冲并事务替换。
    override fun onNextPresented(action: () -> Unit) { nextPresented = action }
    override fun copyCurrentFrame(done: (Bitmap?) -> Unit) { copySdrSnapshot(done) }
    override fun copySdrSnapshot(done: (Bitmap?) -> Unit): Boolean {
        val token = lifecycle
        fun copy(attempt: Int) {
            if (token != lifecycle || !isAttachedToWindow || failed || releasing) { done(null); return }
            if (transition.preparing != null || rendering?.frameReady != true) {
                if (attempt < 40) postDelayed({ copy(attempt + 1) }, 16) else done(null)
                return
            }
            render.copySdrSnapshot { bitmap ->
                if (token != lifecycle || releasing) { bitmap?.recycle(); done(null) } else done(bitmap)
            }
        }
        copy(0)
        return true
    }

    override val particleSurfaceValid: Boolean get() = shown?.let {
        it.frameReady && !it.released && it.surface.isValid && windowUnderlay.control != null &&
            it.pose?.referenceGeneration == windowUnderlay.generation
    } == true
    override val particleSurfaceGeneration: Long get() = if (shown == null) -1 else particleGeneration
    override fun presentForParticleRecovery(done: (Boolean) -> Unit) {
        val layer = shown ?: run { done(false); return }
        val token = lifecycle
        render.renderParticleRecoveryFrame({}) { submitted ->
            if (!submitted || token != lifecycle || layer !== shown || releasing) done(false)
            else try {
                updatePose(layer, force = true) {
                    record("particle_recovery", "target=${layer.ticket.id} presented=$it")
                    // 即使兼容器不给有效 fence，reconcile 仍会在稳定可见后采样。
                    done(it)
                }
            } catch (error: Exception) { record("particle_recovery_failed", error.toString()); done(false) }
        }
    }

    override fun holdParticleSnapshot(): () -> Unit {
        particleHolds++
        render.setFrozen(true)
        updateAnimating()
        record("particle_hold", "count=$particleHolds")
        var released = false
        return {
            if (!released) {
                released = true
                particleHolds--
                render.setFrozen(frozen || particleHolds > 0)
                record("particle_resume", "count=$particleHolds")
                reconcile()
            }
        }
    }

    override fun setFrozen(value: Boolean) { frozen = value; render.setFrozen(value || particleHolds > 0); updateAnimating() }
    override fun setThingBackground(background: ThingBackground) = render.setThingBackground(background)
    override fun setContainerGravity(x: Float, y: Float, z: Float) = render.setGravity(x, y, z)
    override fun setPerformanceMonitor(monitor: FableSolPerformanceMonitor?) { this.monitor = monitor; render.setPerformanceMonitor(monitor) }
    override fun setPresentationAlpha(alpha: Float) = render.setPresentationAlpha(alpha)
    override fun setRecordingHdrActive(active: Boolean) = render.setHdrRecordingRequested(active)
    override fun setTuningValue(key: String, value: Double) = render.setTuningValue(key, value)
    override fun setSimulationPaused(paused: Boolean) = render.setSimulationPaused(paused)
    override fun beginBackgroundTransition(background: ThingBackground) = render.beginBackgroundTransition(background)
    override fun setContentVerticalOffsetDp(offsetDp: Float) = render.setContentVerticalOffsetDp(offsetDp)
    override fun setBottomCornerRadiusPx(radiusPx: Float) = render.setBottomCornerRadiusPx(radiusPx)
    override fun onAudioFrames(frames: List<FableSolFeatureFrame>, events: List<FableSolEvent>) = render.onAudioFrames(frames, events)
    override fun clearPendingAudio() = render.clearPendingAudio()

    private fun fail(message: String) {
        if (failed) return
        failed = true
        record("failed", message)
        releaseAll()
        stopCompositionPerformance()
        windowUnderlay.close()
        onHdrAvailabilityChanged?.invoke()
        onGlFailure?.invoke(message)
    }

    private fun releaseLayer(layer: Layer) {
        if (layer.released) return
        layer.released = true
        runCatching { SurfaceControl.Transaction().use { it.setVisibility(layer.control, false).reparent(layer.control, null).apply() } }
        layer.surface.release()
        layer.control.release()
        layers.remove(layer)
        record("released", "target=${layer.ticket.id}")
    }

    private fun releaseAll() {
        lifecycle++
        frameRate.reset()
        removeCallbacks(poll)
        if (releasing) return
        releasing = true
        transition.reset()
        foregroundProbe.reset()
        foregroundLayout?.stopForegroundCapture()
        foregroundSequence = 0L
        backgroundCutout.restore()
        nextPresented = null
        layers.forEach { layer ->
            runCatching { SurfaceControl.Transaction().use { it.setVisibility(layer.control, false).reparent(layer.control, null).apply() } }
        }
        shown = null
        rendering = null
        animating = false
        record("release_requested")
        render.detachDirect {
            layers.toList().forEach(::releaseLayer)
            rendererStarted = false
            releasing = false
            record("release_complete")
            if (isAttachedToWindow && windowVisibility == VISIBLE && !failed) { removeCallbacks(poll); post(poll) }
        }
    }

    override fun onDetachedFromWindow() {
        windowUnderlay.onContainerChanged = null
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(preDraw)
        releaseAll()
        stopCompositionPerformance()
        super.onDetachedFromWindow()
    }

    private fun record(event: String, detail: String = "") {
        if (!BuildConfig.DEBUG) return
        performance?.event(event, detail)
        DebugFileLogger.log("fablesol_direct.log", JSONObject().put("event", event)
            .put("debugUpdateCode", context.getString(com.ywwynm.everythingdone.R.string.debug_update_code))
            .put("view", System.identityHashCode(this)).put("window", System.identityHashCode(rootView))
            .put("lifecycle", lifecycle).put("shown", shown?.ticket?.id)
            .put("preparing", transition.preparing?.id).put("output", shown?.ticket?.spec?.output?.name)
            .put("requestedOutput", output().name).put("surface", shown?.let { System.identityHashCode(it.surface) })
            .put("position", shown?.pose?.let { "${it.x},${it.y}" })
            .put("crop", shown?.pose?.clip?.toShortString()).put("alpha", shown?.pose?.alpha)
            .put("transparentHintPolicy", "preserve_window_pixels")
            .put("zOrderPolicy", "application_media_window_below_dialog")
            .put("orderReference", windowUnderlay.control?.let { System.identityHashCode(it) })
            .put("orderReferenceGeneration", windowUnderlay.generation)
            .put("orderReferenceFailure", windowUnderlay.failure)
            .put("underlay", System.identityHashCode(windowUnderlay))
            .put("underlaySize", "${windowUnderlay.width}x${windowUnderlay.height}")
            .put("underlayViewAlpha", windowUnderlay.alpha)
            .put("mediaWindow", windowUnderlay.diagnostics())
            .put("windowBackground", backgroundCutout.diagnostics())
            .put("foregroundComposite", foregroundLayout?.foregroundDiagnostics())
            .put("foregroundSequence", foregroundSequence)
            .put("performanceSession", performance?.session)
            .put("windowForegroundExpected", foregroundLayout?.foregroundDiagnostics()?.optBoolean("composited") != true)
            .put("particleHolds", particleHolds).put("detail", detail).toString(), "[DEBUG-FABLESOL-DIRECT]")
    }

    private fun sampleWindowForeground(stage: String, layer: Layer, delay: Long = 0L) {
        if (!BuildConfig.DEBUG) return
        val token = lifecycle
        val current = { token == lifecycle && !releasing && !layer.released &&
            isAttachedToWindow && (layer === shown || layer === rendering) }
        postDelayed({
            if (current()) FableSolWindowForegroundDiagnostics.sample(this, stage, current) {
                record("window_foreground", it.put("target", layer.ticket.id).toString())
            }
        }, delay)
    }

    private companion object {
        val presentationWorker = Executors.newSingleThreadExecutor { Thread(it, "FableSolDirectPresent") }
    }
}
