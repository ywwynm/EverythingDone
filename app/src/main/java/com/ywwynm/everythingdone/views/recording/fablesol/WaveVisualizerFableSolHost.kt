package com.ywwynm.everythingdone.views.recording.fablesol

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.views.particledismiss.ParticleSnapshotParticipant
import kotlin.math.min

/**
 * Stage 1 运行时宿主：正常只运行 GLES；任何 GL 失败时切换旧 Canvas，并把天空强制为纯红色。
 */
class WaveVisualizerFableSolHost private constructor(
    context: Context,
    attrs: AttributeSet?,
    useDirectSurface: Boolean
) : FrameLayout(context, attrs), FableSolFrameReceiver,
    ParticleSnapshotParticipant {

    @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : this(context, attrs, true)

    // 正常 GLES 路径不创建第二份模拟、网格和采样数组；失败时再补齐回退实例与当前状态。
    private var canvasFallback: WaveVisualizerFableSol? = null
    private val windowUnderlay = if (useDirectSurface && FableSolDisplayPolicy.usesPqCompatibility(
        android.os.Build.VERSION.SDK_INT, android.os.Build.MANUFACTURER, android.os.Build.DISPLAY))
        FableSolWindowUnderlay(context) else null
    private val glView: FableSolRenderView = (if (windowUnderlay != null)
        WaveVisualizerFableSolDirect(context, windowUnderlay) else WaveVisualizerFableSolGl(context)).apply {
        onGlFailure = { activateCanvasFallback() }
        onHdrAvailabilityChanged = { this@WaveVisualizerFableSolHost.onHdrAvailabilityChanged?.invoke() }
    }
    private var fallbackActive = false
    private var currentBackground: ThingBackground? = null
    private var gravityX = 0f
    private var gravityY = 1f
    private var gravityZ = 0f
    private var performanceMonitor: FableSolPerformanceMonitor? = null
    private var perfHud: TextView? = null
    /** 换缓冲格式期间的静止遮盖层；平时不创建，撤掉之后位图立即回收。 */
    private var formatCover: ImageView? = null
    private var coverShownAtNanos = 0L
    private var hdrStrengthRequestId = 0L
    private var requestedHdrStrength = FableSolTuning.hdrStrength(context)
    private var hdrCaptureInFlight = false
    private var hdrLifecycleId = 0L
    private var presentationAlpha = PREPARED_PRESENTATION_ALPHA
    private var presentationAnimator: ValueAnimator? = null
    private var particleHolds = 0
    private var simulationPaused = false
    private var hostFrozen = false
    private val tuningOverrides = mutableMapOf<String, Double>()

    init {
        val match = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        windowUnderlay?.let { addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
        addView(glView.view, match)
        setPresentationAlpha(PREPARED_PRESENTATION_ALPHA)
    }

    fun setThingBackground(background: ThingBackground) {
        currentBackground = background
        glView.setThingBackground(background)
        canvasFallback?.setThingBackground(background)
    }

    fun setContainerGravity(x: Float, y: Float, z: Float) {
        gravityX = x
        gravityY = y
        gravityZ = z
        if (fallbackActive) canvasFallback?.setContainerGravity(x, y, z)
        else glView.setContainerGravity(x, y, z)
    }

    internal fun setPerformanceMonitor(monitor: FableSolPerformanceMonitor?) {
        performanceMonitor?.onHudUpdate = null
        performanceMonitor = monitor
        glView.setPerformanceMonitor(if (fallbackActive) null else monitor)
        canvasFallback?.setPerformanceMonitor(if (fallbackActive) monitor else null)
        attachPerfHud(monitor)
    }

    internal fun startCompositionPerformance(window: android.view.Window, scenario: String) {
        if (BuildConfig.DEBUG && !fallbackActive)
            (glView as? WaveVisualizerFableSolDirect)?.startCompositionPerformance(window, scenario)
    }

    /**
     * Debug 构建的屏幕内帧率仪表：直接把 GL 线程分阶段耗时叠在水面上，
     * 免去为了读一次帧率而拉设备日志。release 构建里 [BuildConfig.DEBUG] 为常量 false，
     * 整段会被 R8 消除。
     *
     * 是否显示由设置里波浪调参 Dialog 末尾的开关决定（[FableSolTuning.isPerfHudEnabled]，
     * 默认关闭）；关闭时 HUD 回调不注册，监控的分位数格式化也随之整段跳过，
     * perf 文件日志照常工作。
     */
    private fun attachPerfHud(monitor: FableSolPerformanceMonitor?) {
        if (!BuildConfig.DEBUG) return
        if (monitor == null || !FableSolTuning.isPerfHudEnabled(context)) {
            perfHud?.let(::removeView)
            perfHud = null
            return
        }
        val hud = perfHud ?: TextView(context).apply {
            setBackgroundColor(HUD_BACKGROUND)
            setTextColor(HUD_FOREGROUND)
            textSize = HUD_TEXT_SP
            typeface = Typeface.MONOSPACE
            includeFontPadding = false
            val padding = (resources.displayMetrics.density * 4f).toInt()
            setPadding(padding, padding, padding, padding)
            addView(
                this,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.TOP
                }
            )
            perfHud = this
        }
        monitor.onHudUpdate = { text -> hud.post { hud.text = text } }
    }

    fun setPresentationAlpha(alpha: Float) {
        val value = alpha.coerceIn(0f, 1f)
        presentationAlpha = value
        glView.setPresentationAlpha(value)
        canvasFallback?.alpha = value
    }

    fun animatePresentationAlpha(targetAlpha: Float, durationMs: Long) {
        presentationAnimator?.cancel()
        val target = targetAlpha.coerceIn(0f, 1f)
        if (durationMs <= 0L || presentationAlpha == target) {
            setPresentationAlpha(target)
            return
        }
        presentationAnimator = ValueAnimator.ofFloat(presentationAlpha, target).apply {
            duration = durationMs
            addUpdateListener { setPresentationAlpha(it.animatedValue as Float) }
            start()
            if (particleHolds > 0) pause()
        }
    }

    /** 海浪模拟由子视图冻结；宿主的呈现透明度也必须在同一帧交接后继续。 */
    override fun holdParticleSnapshot(): () -> Unit {
        particleHolds++
        canvasFallback?.setFrozen(true)
        presentationAnimator?.pause()
        var released = false
        return {
            if (!released) {
                released = true
                particleHolds--
                canvasFallback?.setFrozen(hostFrozen || particleHolds > 0)
                if (particleHolds == 0) presentationAnimator?.resume()
            }
        }
    }

    fun setRecordingHdrActive(active: Boolean) {
        glView.setRecordingHdrActive(active && !fallbackActive)
    }

    /**
     * GLES 是否仍在跑（未落到 Canvas 回退）。视频导出同样依赖 GLES，回退设备上入口
     * 直接不出现（fablesol-video-export D14）。
     */
    fun isGlActive(): Boolean = !fallbackActive

    /** GLES 异步失败、切到 Canvas 回退时回调一次；导出入口据此立刻隐藏，而不是等下次点击。 */
    var onGlFallback: (() -> Unit)? = null

    /** null 继续使用普通 Android 能力判断；兼容输出失败只改变可用状态，不覆盖用户偏好。 */
    internal fun compatibilityHdrSupported(): Boolean? = glView.compatibilityHdrSupported?.let {
        it && !fallbackActive
    }

    internal var onHdrAvailabilityChanged: (() -> Unit)? = null

    /**
     * 用户 HDR 强度（1.0=关，9.6 封顶）；仅 GL 路径，Canvas 回退无 HDR。
     *
     * 跨过「关闭」那一档要换缓冲格式、整条重建 EGL 会话——**换格式的那一刻旧
     * 缓冲就作废了**，新会话交出第一帧之前合成器手上没有内容，OPD2515 上看到的就是这一两帧
     * 空白（表现为预览瞬间闪一下）。
     *
     * 做法是「先显后撤」：先把当前这一帧 `PixelCopy` 下来盖在预览上，再换格式；等新会话
     * **在这条 Surface 上**真正交出第一帧（[WaveVisualizerFableSolGl.onNextPresented]，
     * 不是宿主窗口画了一帧）才撤掉遮盖层。附 [COVER_TIMEOUT_MS] 兜底，抓图失败也照常切，
     * 只是那一次没有遮盖——绝不因为遮盖失败就不改设置。
     */
    fun setHdrStrength(strength: Float) {
        if (glView.retainsFrameOnOutputChange) {
            glView.setHdrStrength(strength)
            return
        }
        if (glView.compatibilityHdrSupported == null) {
            setNativeHdrStrength(strength)
            return
        }
        hdrStrengthRequestId++
        requestedHdrStrength = strength
        // 截图期间只保留最后一次请求，防止拖动滑杆排队做多次 GPU 读回。
        if (hdrCaptureInFlight) return
        if (fallbackActive || !glView.willChangeSurfaceFormat(strength) || formatCover?.visibility == VISIBLE) {
            applyRequestedHdrStrength()
            return
        }
        hdrCaptureInFlight = true
        val lifecycle = hdrLifecycleId
        glView.copyCurrentFrame { frame ->
            if (lifecycle != hdrLifecycleId || !isAttachedToWindow) {
                frame?.recycle()
                return@copyCurrentFrame
            }
            if (glView.willChangeSurfaceFormat(requestedHdrStrength)) {
                showFormatCover(frame)
                coverShownAtNanos = System.nanoTime()
                if (BuildConfig.DEBUG) {
                    Log.i(COVER_TAG, "换格式遮盖：已上 抓图=${frame != null} 强度=$requestedHdrStrength")
                }
                if (frame != null && glView.compatibilityHdrSupported != null && isHardwareAccelerated) {
                    // 卓易通先等遮盖进入窗口缓冲，再使旧 PQ 缓冲失效。
                    // 准备期间继续合并滑杆请求，不能在 commit 前由下一次拖动抢先换格式。
                    applyAfterCoverCommit(lifecycle)
                    return@copyCurrentFrame
                }
            } else {
                frame?.recycle()
            }
            hdrCaptureInFlight = false
            applyRequestedHdrStrength()
        }
    }

    /** 普通 Android 保留原有 PixelCopy 遮盖与切换顺序。 */
    private fun setNativeHdrStrength(strength: Float) {
        if (fallbackActive || !glView.willChangeSurfaceFormat(strength)) {
            glView.setHdrStrength(strength)
            return
        }
        glView.copyCurrentFrame { frame ->
            showFormatCover(frame)
            coverShownAtNanos = System.nanoTime()
            if (BuildConfig.DEBUG) Log.i(COVER_TAG, "换格式遮盖：已上 抓图=${frame != null} 强度=$strength")
            glView.setHdrStrength(strength)
            glView.onNextPresented { hideFormatCover("新会话首帧") }
            removeCallbacks(hideFormatCoverRunnable)
            postDelayed(hideFormatCoverRunnable, COVER_TIMEOUT_MS)
        }
    }

    private fun applyAfterCoverCommit(lifecycle: Long) {
        var completed = false
        lateinit var timeout: Runnable
        val proceed = {
            if (!completed) {
                completed = true
                removeCallbacks(timeout)
                if (lifecycle == hdrLifecycleId && isAttachedToWindow) {
                    hdrCaptureInFlight = false
                    applyRequestedHdrStrength()
                }
            }
        }
        timeout = Runnable { proceed() }
        val observer = viewTreeObserver
        if (observer.isAlive) observer.registerFrameCommitCallback { post { proceed() } }
        invalidate()
        postDelayed(timeout, 160L)
    }

    private fun applyRequestedHdrStrength() {
        val requestId = hdrStrengthRequestId
        glView.setHdrStrength(requestedHdrStrength)
        if (formatCover?.visibility == VISIBLE) {
            glView.onNextPresented {
                if (requestId == hdrStrengthRequestId) hideFormatCover("新会话首帧")
            }
            removeCallbacks(hideFormatCoverRunnable)
            postDelayed(hideFormatCoverRunnable, COVER_TIMEOUT_MS)
        }
    }

    private fun showFormatCover(frame: Bitmap?) {
        if (frame == null) return
        val view = formatCover ?: ImageView(context).also {
            it.scaleType = ImageView.ScaleType.FIT_XY
            formatCover = it
            addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        // 遮盖层压在水面之上；HUD 随后再提到最前，保持它一直可见。
        view.bringToFront()
        perfHud?.bringToFront()
        (view.drawable as? BitmapDrawable)?.bitmap?.takeIf { it !== frame }?.recycle()
        view.setImageBitmap(frame)
        view.visibility = VISIBLE
    }

    private val hideFormatCoverRunnable = Runnable { hideFormatCover("兜底超时") }

    private fun hideFormatCover(reason: String = "收尾") {
        removeCallbacks(hideFormatCoverRunnable)
        val view = formatCover ?: return
        if (view.visibility != VISIBLE) return
        if (BuildConfig.DEBUG) {
            Log.i(COVER_TAG, "换格式遮盖：撤下 原因=$reason 停留=%.1fms".format(
                (System.nanoTime() - coverShownAtNanos) / 1e6))
        }
        view.visibility = GONE
        val drawable = view.drawable as? BitmapDrawable
        view.setImageDrawable(null)
        drawable?.bitmap?.recycle()
    }

    /** 运行时调参（调参 Dialog 实时预览）：直达当前活动的渲染后端。 */
    fun setTuningValue(key: String, value: Double) {
        tuningOverrides[key] = value
        glView.setTuningValue(key, value)
        canvasFallback?.setTuningValue(key, value)
    }

    /** 暂停冻结（调参 Dialog）：画面静止但渲染照跑，调参实时可见。 */
    fun setSimulationPaused(paused: Boolean) {
        simulationPaused = paused
        glView.setSimulationPaused(paused)
        canvasFallback?.setSimulationPaused(paused)
    }

    /**
     * 完全冻结（导出进度对话框在前台）：帧循环停掉，连 buildFrame / drawFrame 都不再发生，
     * 画面停在最后一帧，帧率投票一并撤掉。与 [setSimulationPaused] 是两个独立开关。
     *
     * 谁来调它由调用方的生命周期决定，见 [FableSolExportFreezeGate]。
     */
    fun setFrozen(frozen: Boolean) {
        hostFrozen = frozen
        glView.setFrozen(frozen)
        canvasFallback?.setFrozen(frozen || particleHolds > 0)
    }

    /**
     * 渐变切换配色（调参 Dialog 换色）：GL 端新颜色的波浪从右缘涌入；
     * Canvas 回退无过渡动画、直切。
     */
    fun animateThingBackground(background: ThingBackground) {
        currentBackground = background
        glView.beginBackgroundTransition(background)
        canvasFallback?.setThingBackground(background)
    }

    /** 预览取景：内容整体沿屏幕 y 平移（dp，负 = 上移）；仅 GL 路径。 */
    fun setContentVerticalOffsetDp(offsetDp: Float) {
        glView.setContentVerticalOffsetDp(offsetDp)
    }

    /** 底部两角半径覆盖（px；<0 恢复与顶部一致）；仅 GL 路径。 */
    fun setBottomCornerRadiusPx(radiusPx: Float) {
        glView.setBottomCornerRadiusPx(radiusPx)
    }

    override fun onAudioFrames(frames: List<FableSolFeatureFrame>, events: List<FableSolEvent>) {
        if (fallbackActive) canvasFallback?.onAudioFrames(frames, events)
        else glView.onAudioFrames(frames, events)
    }

    /** 离开/返回录音 Dialog 时清掉边界处未消费批次，避免快速回放后台历史。 */
    fun clearPendingAudio() {
        glView.clearPendingAudio()
        canvasFallback?.clearPendingAudio()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(resolveIntrinsic(widthMeasureSpec, INTRINSIC_W_DP), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(resolveIntrinsic(heightMeasureSpec, INTRINSIC_H_DP), MeasureSpec.EXACTLY)
        )
    }

    override fun onDetachedFromWindow() {
        hdrStrengthRequestId++
        hdrLifecycleId++
        hdrCaptureInFlight = false
        presentationAnimator?.cancel()
        presentationAnimator = null
        hideFormatCover()
        super.onDetachedFromWindow()
    }

    private fun activateCanvasFallback() {
        if (fallbackActive) return
        fallbackActive = true
        glView.setPerformanceMonitor(null)
        glView.setRecordingHdrActive(false)
        glView.view.visibility = View.GONE
        windowUnderlay?.visibility = View.GONE
        val fallback = WaveVisualizerFableSol(context).also { canvasFallback = it }
        fallback.setGlFallbackDiagnostic(true)
        currentBackground?.let(fallback::setThingBackground)
        fallback.setContainerGravity(gravityX, gravityY, gravityZ)
        fallback.setPerformanceMonitor(performanceMonitor)
        fallback.setSimulationPaused(simulationPaused)
        fallback.setFrozen(hostFrozen || particleHolds > 0)
        tuningOverrides.forEach { (key, value) -> fallback.setTuningValue(key, value) }
        fallback.alpha = presentationAlpha
        addView(fallback, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        onGlFallback?.invoke()
        if (glView.compatibilityHdrSupported != null) onHdrAvailabilityChanged?.invoke()
    }

    private fun resolveIntrinsic(spec: Int, dp: Float): Int {
        val mode = MeasureSpec.getMode(spec)
        val size = MeasureSpec.getSize(spec)
        val intrinsic = (dp * resources.displayMetrics.density).toInt()
        return when (mode) {
            MeasureSpec.EXACTLY -> size
            MeasureSpec.AT_MOST -> min(size, intrinsic)
            else -> intrinsic
        }
    }

    companion object {
        /** C／M 等旧 SurfaceView 对照不随生产容器切成 N；仅 debug 诊断页调用。 */
        internal fun forSurfaceViewComparison(context: Context): WaveVisualizerFableSolHost {
            check(BuildConfig.DEBUG)
            return WaveVisualizerFableSolHost(context, null, false)
        }

        private const val INTRINSIC_W_DP = 280f
        private const val INTRINSIC_H_DP = 420f
        private const val PREPARED_PRESENTATION_ALPHA = 0.16f
        private const val HUD_TEXT_SP = 8f
        /** 遮盖层的兜底时限：新会话万一交不出帧，也绝不把静止图永久留在屏幕上。 */
        private const val COVER_TIMEOUT_MS = 900L
        private const val COVER_TAG = "FableSolSurfProbe"
        private val HUD_BACKGROUND = Color.argb(150, 0, 0, 0)
        private val HUD_FOREGROUND = Color.argb(255, 120, 255, 160)
    }
}
