package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.Display
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.helpers.DebugFileLogger
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.views.particledismiss.ParticleSnapshotParticipant
import com.ywwynm.everythingdone.views.particledismiss.ParticleSurfaceRedrawParticipant
import com.ywwynm.everythingdone.views.particledismiss.ParticleSurfaceSnapshotSource
import kotlin.math.max
import kotlin.math.min

/** SurfaceView 宿主：UI 线程只投递 60Hz 时间戳，全部水体工作在 FableSolGles 线程。 */
internal class WaveVisualizerFableSolGl @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback, FableSolFrameReceiver,
    ParticleSnapshotParticipant, ParticleSurfaceRedrawParticipant, ParticleSurfaceSnapshotSource, FableSolRenderView {

    override val view: View get() = this

    private val density = resources.displayMetrics.density.toDouble()
    private val pqCompatibility = FableSolDisplayPolicy.usesPqCompatibility(
        Build.VERSION.SDK_INT, Build.MANUFACTURER, Build.DISPLAY)
    private val windowClip = if (pqCompatibility) FableSolWindowSurfaceClip(this) else null
    private var pqUnavailable = false
    override val compatibilityHdrSupported: Boolean?
        get() = if (pqCompatibility) !pqUnavailable else null
    override var onHdrAvailabilityChanged: (() -> Unit)? = null
    private val renderThread = FableSolGlRenderThread(
        context,
        density,
        onHdrStatus = { active, _, attemptedPq, sessionId ->
            post {
                if (!surfaceReady) return@post
                if (pqCompatibility && sessionId != sessionGeneration) return@post
                hdrContentAvailable = surfaceReady && active && (!pqCompatibility || hdrRequestedByUser())
                if (FableSolDisplayPolicy.isPqFailure(attemptedPq, active) && !pqUnavailable) {
                    pqUnavailable = true
                    // 实际 PQ 或浮点场景失败才回退；本 View 生命周期内不反复探测／换格式。
                    holder.setFormat(PixelFormat.RGBA_8888)
                    updateSurfaceSession(PixelFormat.RGBA_8888, width, height)
                    onHdrAvailabilityChanged?.invoke()
                }
                updateDesiredHdrHeadroom()
            }
        },
        onFatalError = { message ->
            post {
                contentDescription = "FableSol GLES unavailable: $message"
                onGlFailure?.invoke(message)
            }
        }
    )
    override var onGlFailure: ((String) -> Unit)? = null
    private var animating = false
    /**
     * 完全冻结。它是一道**门**而不是一次性命令：`ensureAnimating()` 有五个调用点
     * （attach、surfaceCreated、surfaceChanged、sizeChanged、可见性变化），任何一个都会在
     * 冻结期间把帧循环拉回来——切后台再切回来就足以撤销冻结。因此判据必须进
     * [shouldAnimate]。
     */
    private var frozen = false
    private var hostFrozen = false
    private var particleHolds = 0
    private var surfaceReady = false
    /** 当前 EGL 会话是按哪个缓冲格式建的；与 [desiredSurfaceFormat] 不一致就必须重建会话。 */
    private var attachedFormat = PixelFormat.UNKNOWN
    private var attachedOutput = FableSolScreenOutput.SDR
    private var sessionGeneration = 0L
    private var formatPresentedAction: (() -> Unit)? = null
    private var votedFrameRate = 0f
    private var demotedPollStreak = 0
    private var lastRevoteUptimeMs = 0L
    private var revotePending = false
    private val revoteRunnable = Runnable {
        revotePending = false
        if (surfaceReady && shouldAnimate()) {
            applySurfaceFrameRate(desiredRefreshRate(display), preferAtLeast = true)
        }
    }
    private var performanceMonitor: FableSolPerformanceMonitor? = null
    private var recordingHdrRequested = false
    private var hdrContentAvailable = false
    private var desiredHdrHeadroomRaised = false
    // 用户 HDR 强度（D204）：构造时读持久化值，调参 Dialog 拖动时实时覆盖。
    private var hdrStrength = FableSolTuning.hdrStrength(context)
    // setDesiredHdrHeadroom 去重：拖动强度滑杆时避免每 tick 向系统重发同值请求。
    private var appliedDesiredHdrHeadroom = 1f
    private val releaseHdrHeadroom = Runnable {
        if (!recordingHdrRequested) {
            desiredHdrHeadroomRaised = false
            applyDesiredHdrHeadroom(1f)
        }
    }
    /**
     * 显示状态轮询。帧节拍已经交给 GL 线程自己的 Choreographer，UI 线程只负责
     * 低频查询 HDR headroom 与显示刷新率——这两项都要通过 View 拿 Display，
     * 必须在 UI 线程做。250ms 一次，与原先逐帧回调里的节流周期一致。
     */
    private val displayPoll = object : Runnable {
        override fun run() {
            if (!shouldAnimate()) {
                stopFrameLoop()
                return
            }
            pollHdrHeadroom()
            pollDisplayRefreshRate()
            postDelayed(this, DISPLAY_POLL_INTERVAL_MS)
        }
    }

    init {
        setZOrderOnTop(false)
        holder.setFormat(desiredSurfaceFormat())
        holder.addCallback(this)
    }

    /**
     * 卓易通 PQ 改用完整 8 位 alpha 的 RGBA8，避免 RGB10_A2 的兼容合成穿透。
     * 关闭档仍要重建 SDR 色彩空间；普通设备保持原来的 FP16 scRGB。
     */
    private fun desiredSurfaceFormat(strength: Float = hdrStrength): Int =
        if (desiredOutput(strength) == FableSolScreenOutput.SCRGB) PixelFormat.RGBA_F16
        else PixelFormat.RGBA_8888

    private fun desiredOutput(strength: Float = hdrStrength) = FableSolDisplayPolicy.requestedOutput(
        Build.VERSION.SDK_INT, pqCompatibility, pqUnavailable, resources.configuration.isScreenHdr, strength)

    /** 关闭档同时控制内容增益与窗口色彩空间。 */
    private fun hdrRequestedByUser(): Boolean = hdrStrength > FableSolHdrPolicy.STRENGTH_OFF

    override fun setThingBackground(background: ThingBackground) {
        renderThread.setThingBackground(background)
    }

    override fun setContainerGravity(x: Float, y: Float, z: Float) {
        renderThread.setGravity(x, y, z)
    }

    override fun setPerformanceMonitor(monitor: FableSolPerformanceMonitor?) {
        performanceMonitor = monitor
        renderThread.setPerformanceMonitor(monitor)
    }

    override fun setPresentationAlpha(alpha: Float) {
        renderThread.setPresentationAlpha(alpha)
    }

    override fun setRecordingHdrActive(active: Boolean) {
        recordingHdrRequested = active
        renderThread.setHdrRecordingRequested(active)
        updateDesiredHdrHeadroom()
    }

    /**
     * 这次强度变化会不会换缓冲格式。换格式意味着旧缓冲当场作废、新 EGL 会话交出第一帧
     * 之前合成器手上没有内容——调用方（[WaveVisualizerFableSolHost]）要先盖一张静止图。
     */
    override fun willChangeSurfaceFormat(strength: Float): Boolean {
        if (!surfaceReady) return false
        val value = strength.coerceIn(
            FableSolHdrPolicy.STRENGTH_OFF, FableSolHdrPolicy.MAX_STRENGTH
        )
        if (value == hdrStrength) return false
        val wanted = desiredSurfaceFormat(value)
        return wanted != attachedFormat || (pqCompatibility && desiredOutput(value) != attachedOutput)
    }

    /** 当前这一帧的静止拷贝，供换格式期间遮盖用；拿不到就回调 null（调用方照常切）。 */
    override fun copyCurrentFrame(done: (Bitmap?) -> Unit) {
        if (copySdrSnapshot(done)) return
        if (!surfaceReady || !holder.surface.isValid || width <= 0 || height <= 0) {
            done(null); return
        }
        val bitmap = runCatching {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: run { done(null); return }
        runCatching {
            PixelCopy.request(this, bitmap, { status ->
                if (status == PixelCopy.SUCCESS) done(bitmap)
                else { bitmap.recycle(); done(null) }
            }, handler)
        }.onFailure { bitmap.recycle(); done(null) }
    }

    /** 新会话在这条 Surface 上真正交出第一帧之后回调一次（主线程）。 */
    override fun onNextPresented(action: () -> Unit) {
        if (!pqCompatibility) {
            renderThread.notifyOnNextPresented(action)
            return
        }
        // setFormat 到 surfaceChanged 之间旧会话还可能提交帧；不能用旧帧撤盖。
        if (desiredSurfaceFormat() != attachedFormat || desiredOutput() != attachedOutput) formatPresentedAction = action
        else {
            formatPresentedAction = null
            renderThread.notifyOnNextPresented(action)
            // 冻结预览可能已交出新会话唯一一帧；注册回调后再补一次，避免等到遮盖超时。
            if (frozen) renderThread.renderSingleFrame()
        }
    }

    override fun copySdrSnapshot(done: (Bitmap?) -> Unit): Boolean {
        if (!pqCompatibility) return false
        if (!surfaceReady) done(null) else renderThread.copySdrSnapshot(done)
        return true
    }

    /**
     * 用户 HDR 强度（1.0=关，9.6 封顶）：立即生效，已抬升的期望 headroom 同步改请求值。
     *
     * 跨过关闭档同时换缓冲格式：HDR 使用原生 scRGB 或兼容 PQ，SDR 使用 sRGB 8 位。
     * 光改着色器输出换不掉合成器对这块缓冲的解释。换格式会触发 surfaceChanged，
     * 那里按 [attachedFormat] 判出格式变了、整条重建 EGL 会话。**换格式会丢掉旧缓冲**，
     * 所以这条路要由 [WaveVisualizerFableSolHost] 先盖一张静止图再调（[willChangeSurfaceFormat]）。
     */
    override fun setHdrStrength(strength: Float) {
        val value = strength.coerceIn(
            FableSolHdrPolicy.STRENGTH_OFF,
            FableSolHdrPolicy.MAX_STRENGTH
        )
        if (value == hdrStrength) return
        val previousFormat = desiredSurfaceFormat()
        hdrStrength = value
        val format = desiredSurfaceFormat()
        if (format != attachedFormat || (pqCompatibility && format != previousFormat)) holder.setFormat(format)
        renderThread.setHdrStrength(value)
        // PQ/SDR 的像素格式现在相同，setFormat 不会通知 surfaceChanged；显式重建颜色会话。
        // 宿主已在窗口 commit 后才调用本方法，重建与首帧交接仍处在快照遮盖下。
        if (pqCompatibility && surfaceReady && desiredOutput() != attachedOutput) {
            updateSurfaceSession(format, width, height)
        }
        updateDesiredHdrHeadroom()
    }

    override fun setTuningValue(key: String, value: Double) {
        renderThread.setTuningValue(key, value)
    }

    /**
     * 暂停冻结（与 Python 模拟器同语义）：模拟与音频泵停住、画面静止，但渲染
     * 循环照跑——冻结画面上调参、换色、HDR 切换仍逐帧实时生效。
     */
    override fun setSimulationPaused(paused: Boolean) {
        renderThread.setSimulationPaused(paused)
    }

    /**
     * 完全冻结：停帧循环、冻住模拟、撤掉帧率投票。与 [setSimulationPaused]（只冻模拟、
     * 渲染照跑）是两回事——这里连 buildFrame / drawFrame 都不再发生，SurfaceView 停在
     * 最后一帧。
     *
     * 撤帧率投票是必须的：`stopFrameLoop()` 本身不动 `setFrameRate`，不撤的话面板会为一张
     * 静止画面继续被顶在 120Hz。
     */
    override fun setFrozen(value: Boolean) {
        hostFrozen = value
        applyFrozenState()
    }

    override fun holdParticleSnapshot(): () -> Unit {
        particleHolds++
        applyFrozenState()
        var released = false
        return {
            if (!released) {
                released = true
                particleHolds--
                applyFrozenState()
            }
        }
    }

    override fun redrawForParticleRecovery(beforeSubmit: () -> Unit, submitted: (Boolean) -> Unit) {
        renderThread.renderParticleRecoveryFrame(beforeSubmit, submitted)
    }

    override fun acceptsParticleRecoveryBuffer(bitmap: Bitmap): Boolean =
        surfaceReady && bitmap.width > 0 && bitmap.height > 0

    private fun applyFrozenState() {
        val target = hostFrozen || particleHolds > 0
        if (frozen == target) return
        frozen = target
        renderThread.setFrozen(target)
        if (target) {
            stopFrameLoop()
            clearSurfaceFrameRate()
        } else {
            ensureAnimating()
        }
    }

    override fun beginBackgroundTransition(background: ThingBackground) {
        renderThread.beginBackgroundTransition(background)
    }

    override fun setContentVerticalOffsetDp(offsetDp: Float) {
        renderThread.setContentVerticalOffsetDp(offsetDp)
    }

    override fun setBottomCornerRadiusPx(radiusPx: Float) {
        renderThread.setBottomCornerRadiusPx(radiusPx)
    }

    override fun onAudioFrames(frames: List<FableSolFeatureFrame>, events: List<FableSolEvent>) {
        renderThread.onAudioFrames(frames, events)
    }

    override fun clearPendingAudio() {
        renderThread.clearPendingAudio()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (BuildConfig.DEBUG) {
            Log.i(SURF_PROBE, "surfaceCreated valid=${holder.surface.isValid}")
        }
        if (!holder.surface.isValid) return
        surfaceReady = true
        votedFrameRate = 0f   // Surface 已换新，旧票不再有效
        val frame = holder.surfaceFrame
        val preferHdr = canBuildHdrSurface()
        val hdrSdrRatio = currentHdrSdrRatio()
        attachedFormat = desiredSurfaceFormat()
        attachedOutput = desiredOutput()
        sessionGeneration++
        renderThread.attach(
            holder.surface,
            max(width, frame.width()).coerceAtLeast(1),
            max(height, frame.height()).coerceAtLeast(1),
            preferHdr,
            hdrSdrRatio,
            preferPq = pqCompatibility && !pqUnavailable,
            sessionId = sessionGeneration
        )
        renderThread.setHdrStrength(hdrStrength)
        renderThread.setHdrRecordingRequested(recordingHdrRequested)
        ensureAnimating()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (BuildConfig.DEBUG) {
            Log.i(
                SURF_PROBE,
                "surfaceChanged fmt=$format ${width}x$height ready=$surfaceReady " +
                    "valid=${holder.surface.isValid} attachedFmt=$attachedFormat"
            )
        }
        updateSurfaceSession(format, width, height)
        windowClip?.request()
    }

    private fun updateSurfaceSession(format: Int, width: Int, height: Int) {
        // 快速往返关闭档时，忽略已经被后一个 setFormat 替代的通知。
        if (pqCompatibility && format != desiredSurfaceFormat()) return
        // 缓冲格式变了（用户在调参 Dialog 里把 HDR 强度拖过「关闭」那一档）只会走到
        // surfaceChanged——SurfaceView 不会为此重建 Surface，于是 surfaceReady 仍为 true。
        // EGL 会话是按旧格式与旧色彩空间建的，只 resize 换不掉它，必须整条重建。
        val formatChanged = surfaceReady && holder.surface.isValid &&
            (format != attachedFormat || (pqCompatibility && desiredOutput() != attachedOutput))
        if ((!surfaceReady && holder.surface.isValid) || formatChanged) {
            // attach 会停止 GL 线程的帧循环，同步重置 UI 侧状态，确保新会话重新开始提交。
            if (pqCompatibility) stopFrameLoop()
            surfaceReady = true
            votedFrameRate = 0f   // Surface 已换新，旧票不再有效
            attachedFormat = format
            attachedOutput = desiredOutput()
            sessionGeneration++
            renderThread.attach(
                holder.surface,
                width.coerceAtLeast(1),
                height.coerceAtLeast(1),
                canBuildHdrSurface(),
                currentHdrSdrRatio(),
                preferPq = pqCompatibility && !pqUnavailable,
                sessionId = sessionGeneration
            )
            renderThread.setHdrStrength(hdrStrength)
            renderThread.setHdrRecordingRequested(recordingHdrRequested)
            formatPresentedAction?.let {
                formatPresentedAction = null
                renderThread.notifyOnNextPresented(it)
            }
        } else {
            renderThread.resize(width, height)
        }
        ensureAnimating()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        sessionGeneration++
        if (BuildConfig.DEBUG) Log.i(SURF_PROBE, "surfaceDestroyed ready=$surfaceReady")
        stopFrameLoop()
        clearSurfaceFrameRate()
        resetHdrSurfaceState()
        if (surfaceReady) {
            surfaceReady = false
            renderThread.detachBlocking()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        windowClip?.attach()
        ensureAnimating()
    }

    override fun onDetachedFromWindow() {
        windowClip?.detach()
        sessionGeneration++
        formatPresentedAction = null
        stopFrameLoop()
        resetHdrSurfaceState()
        if (surfaceReady) {
            surfaceReady = false
            renderThread.detachBlocking()
        }
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (BuildConfig.DEBUG) Log.i(SURF_PROBE, "windowVisibility=$visibility")
        if (visibility == View.VISIBLE) ensureAnimating() else stopFrameLoop()
        if (visibility == View.VISIBLE) windowClip?.request()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (BuildConfig.DEBUG) Log.i(SURF_PROBE, "visibilityAggregated=$isVisible")
        if (isVisible) ensureAnimating() else stopFrameLoop()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (surfaceReady && width > 0 && height > 0) renderThread.resize(width, height)
        ensureAnimating()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveIntrinsic(widthMeasureSpec, INTRINSIC_W_DP),
            resolveIntrinsic(heightMeasureSpec, INTRINSIC_H_DP)
        )
    }

    private fun ensureAnimating() {
        if (frozen) {
            // 冻结态不开循环，但刚建出来或刚改过尺寸的 surface 必须有内容：补一帧就停。
            if (surfaceReady && width > 0 && height > 0) renderThread.renderSingleFrame()
            return
        }
        if (!shouldAnimate()) return
        if (!animating) {
            animating = true
            renderThread.setAnimating(true)
        }
        removeCallbacks(displayPoll)
        displayPoll.run()
    }

    private fun stopFrameLoop() {
        removeCallbacks(displayPoll)
        removeCallbacks(revoteRunnable)
        revotePending = false
        demotedPollStreak = 0
        animating = false
        renderThread.setAnimating(false)
    }

    /**
     * 这一次要不要把窗口 Surface 建成 FP16 linear scRGB。
     *
     * 三个条件缺一不可：API 34+、显示器报告 HDR 且实时 `hdrSdrRatio` 可用（D80）、
     * **用户没有把 HDR 强度关掉**。最后这一条是 2026-09-15 补上的：此前强度只缩放着色器
     * 的超白增量（[FableSolHdrPolicy.excessScale]），表面的色彩空间与像素格式不跟着退回，
     * 于是「HDR 强度=关闭」之后这一层在合成器里仍然是 scRGB linear 的 FP16 层。魅族 20
     * Infinity（Flyme / Android 15）实测：`hdr_strength=1.0` 时 SurfaceFlinger 里仍是
     * `format RGBA16161616F_UBWC dataspace 0x18410000 composition Device/Device`，屏幕上
     * 颜色明显不对，而系统截屏（走 GPU 合成，色彩管理正确）是对的。强度为关时内容本就
     * 等价 SDR，所以这一档退回 sRGB 8 位不改变任何应当看到的像素，只是不再让合成器按
     * 扩展线性去解释这块缓冲。
     */
    private fun canBuildHdrSurface(): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        if (!hdrRequestedByUser()) return false
        if (pqCompatibility) return !pqUnavailable
        val currentDisplay = display ?: return false
        return currentDisplay.isHdr && currentDisplay.isHdrSdrRatioAvailable
    }

    private fun currentHdrSdrRatio(): Float {
        if (Build.VERSION.SDK_INT < 34) return 1f
        if (pqCompatibility) {
            val currentDisplay = display
            return if (currentDisplay?.isHdrSdrRatioAvailable == true) currentDisplay.hdrSdrRatio else Float.NaN
        }
        val currentDisplay = display ?: return 1f
        if (!currentDisplay.isHdr || !currentDisplay.isHdrSdrRatioAvailable) return 1f
        return currentDisplay.hdrSdrRatio
    }

    /**
     * 让渲染频率跟随当前显示模式：60Hz 面板维持既有 60fps，高刷面板放开到
     * 120fps 上限（模拟以真实时间戳推进，水体速度与两端视觉合同不变）。
     * 刷新率查询按 250ms 节流；显示模式切换（如系统省电降到 60Hz）时 pacer
     * 自动收回节奏。
     */
    /**
     * 显示相关轮询。
     *
     * **投票与节拍都只看「期望」，绝不看「当前」**——这是本文件踩过两次的坑：
     * 上一版用当前模式刷新率既当节拍目标又当投票值，面板一旦掉到 60，我们就投 60、
     * 按 60 提交，把它牢牢钉死在 60（真机实测 `hz 60.0/60.0`）。任何"读当前状态
     * 再据此请求"的写法都会构成自锁环。
     */
    private fun pollDisplayRefreshRate() {
        val currentDisplay = display
        // 期望速率 = 同分辨率下支持的最高刷新率，上限 120。与面板此刻处于哪个模式无关。
        val desired = desiredRefreshRate(currentDisplay)
        applySurfaceFrameRate(desired)
        maybeRecoverFrameRate(desired)
        performanceMonitor?.setDisplayModeRefreshRate(
            (currentDisplay?.mode?.refreshRate ?: 0f).toDouble()
        )
    }

    /**
     * 降档看门狗。真机复现：面板保持 120Hz 模式，系统却把本应用的 vsync 派发降到
     * 60（HUD `vs 16.6/16.6`、`grid 16.6` 而 `hz 120/120`），且该状态不自行恢复——
     * [applySurfaceFrameRate] 的 votedFrameRate 去重意味着首投之后不再有任何投票
     * 动作；降档后应用又只能按 16.6ms 栅格提交，系统内容检测永远观察不到高于 60
     * 的呈现率，两个方向都没有恢复通道。
     *
     * 这里在观测派发间隔持续高于期望 1.5 倍时撤票再重投：同值重发对 SurfaceFlinger
     * 是空操作，必须先清零制造状态变化；撤票与重投间隔 [REVOTE_CLEAR_TO_APPLY_DELAY_MS]，
     * 期间照常提交的若干缓冲保证两笔状态先后到达 SF，而不是被合并成无变化。
     */
    private fun maybeRecoverFrameRate(desiredFps: Double) {
        if (Build.VERSION.SDK_INT < 30 || !surfaceReady) return
        val observedNs = renderThread.observedVsyncIntervalNs()
        if (observedNs <= 0L) {
            demotedPollStreak = 0
            return
        }
        val desiredIntervalNs = (1_000_000_000.0 / desiredFps).toLong()
        if (observedNs < desiredIntervalNs * 3 / 2) {
            demotedPollStreak = 0
            return
        }
        demotedPollStreak++
        if (demotedPollStreak < DEMOTED_POLLS_BEFORE_REVOTE || revotePending) return
        val now = SystemClock.uptimeMillis()
        if (now - lastRevoteUptimeMs < REVOTE_MIN_INTERVAL_MS) return
        lastRevoteUptimeMs = now
        logDemotionRevote(observedNs, desiredFps)
        clearSurfaceFrameRate()
        revotePending = true
        postDelayed(revoteRunnable, REVOTE_CLEAR_TO_APPLY_DELAY_MS)
    }

    /** 降档/重投证据落盘（仅 debug）：完整 DisplayInfo 含 renderFrameRate 地面真值。 */
    private fun logDemotionRevote(observedNs: Long, desiredFps: Double) {
        if (!BuildConfig.DEBUG) return
        DebugFileLogger.log(
            PERF_LOG_FILE,
            buildString {
                append("revote observed=")
                append(observedNs / 1_000_000)
                append("ms desired=")
                append(desiredFps)
                append("fps\n")
                append(display?.toString() ?: "display=null")
            },
            DEBUG_PREFIX
        )
    }

    /**
     * 同分辨率下支持的最高刷新率（上限 [MAX_RENDER_FPS]）。
     *
     * 只在相同物理分辨率的模式里挑，避免帧率投票顺带触发分辨率切换。
     * `Display.getSupportedModes()` 自 API 23 起可用，minSdk 26 无需守卫。
     */
    private fun desiredRefreshRate(currentDisplay: Display?): Double {
        if (currentDisplay == null) return MAX_RENDER_FPS
        val currentMode = currentDisplay.mode ?: return MAX_RENDER_FPS
        var best = 0f
        for (mode in currentDisplay.supportedModes) {
            if (mode.physicalWidth != currentMode.physicalWidth) continue
            if (mode.physicalHeight != currentMode.physicalHeight) continue
            if (mode.refreshRate > best) best = mode.refreshRate
        }
        if (best < MIN_VALID_REFRESH_RATE) return MAX_RENDER_FPS
        return min(best.toDouble(), MAX_RENDER_FPS)
    }

    /**
     * 给 SurfaceView 自己的 Surface 投帧率票。
     *
     * 这是 API 30+ 上唯一能作用到 GL 图层的通路：Dialog 窗口上的
     * `preferredRefreshRate` 只作用于窗口图层；`View.setRequestedFrameRate` 既不向
     * 子 View 传播，其汇总的窗口 SurfaceControl 又被 ViewRootImpl 以
     * `FRAME_RATE_SELECTION_STRATEGY_SELF` 明令禁止下传给 SurfaceView 子图层。
     *
     * API 31+ 用 `CHANGE_FRAME_RATE_ALWAYS`：默认的 ONLY_IF_SEAMLESS 在这台设备上
     * 无法把面板从 60 拉回 120（无缝切换不被允许时投票会被忽略）。
     */
    private fun applySurfaceFrameRate(desiredFps: Double, preferAtLeast: Boolean = false) {
        if (Build.VERSION.SDK_INT < 30) return
        if (!surfaceReady) return
        val value = desiredFps.toFloat()
        if (value == votedFrameRate) return
        val surface = holder.surface
        if (!surface.isValid) return
        try {
            // 必须是 DEFAULT，不能用 FIXED_SOURCE。2026-07-21 实测：改成 FIXED_SOURCE 后
            // 进入 120Hz 的概率反而下降，且一旦掉到 60 就再也回不来。FIXED_SOURCE 的语义是
            // 「内容帧率固定，系统可自行做 pull-down 匹配」——SurfaceFlinger 因此可以判定
            // 「120 的固定源用 60Hz + 2:1 pulldown 也满足」并稳稳停在 60。DEFAULT 表示
            // 「按这个速率跑，但可以适配」，是 202607210718 首次跑到 119.8fps 时的配置。
            // 降档重投（preferAtLeast）在 API 36 上改用 AT_LEAST：语义是「至少这个速率」，
            // 60 无法满足它，给仲裁一个比 DEFAULT 更硬的下界；首投保持已验证的 DEFAULT。
            if (Build.VERSION.SDK_INT >= 31) {
                val compatibility = if (preferAtLeast && Build.VERSION.SDK_INT >= 36) {
                    Surface.FRAME_RATE_COMPATIBILITY_AT_LEAST
                } else {
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
                }
                surface.setFrameRate(value, compatibility, Surface.CHANGE_FRAME_RATE_ALWAYS)
            } else {
                surface.setFrameRate(value, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            }
            votedFrameRate = value
        } catch (ignored: IllegalStateException) {
            // Surface 已在其它线程被释放；下一轮轮询会重投。
            votedFrameRate = 0f
        }
    }

    /** Surface 销毁前撤票，避免残留投票影响系统的刷新率决策。 */
    private fun clearSurfaceFrameRate() {
        votedFrameRate = 0f
        if (Build.VERSION.SDK_INT < 30) return
        val surface = holder.surface
        if (!surface.isValid) return
        try {
            surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        } catch (ignored: IllegalStateException) {
            // 已释放，无需撤票。
        }
    }

    private fun pollHdrHeadroom() {
        if (!hdrContentAvailable || Build.VERSION.SDK_INT < 34) return
        renderThread.setDisplayHdrSdrRatio(currentHdrSdrRatio())
    }

    private fun updateDesiredHdrHeadroom() {
        removeCallbacks(releaseHdrHeadroom)
        if (Build.VERSION.SDK_INT < 35) return
        if (pqCompatibility && !hdrRequestedByUser()) {
            desiredHdrHeadroomRaised = false
            applyDesiredHdrHeadroom(1f)
            return
        }
        if (pqCompatibility && hdrContentAvailable) {
            // HDR 开启时沿用图片的自动映射，关闭档在上方明确退回 1。
            desiredHdrHeadroomRaised = false
            applyDesiredHdrHeadroom(0f)
            return
        }
        if (recordingHdrRequested && hdrContentAvailable) {
            desiredHdrHeadroomRaised = true
            // 只向系统申请内容实际会用到的余量：期望值 = 当前用户强度。
            applyDesiredHdrHeadroom(hdrStrength)
        } else if (hdrContentAvailable && desiredHdrHeadroomRaised) {
            postDelayed(releaseHdrHeadroom, HDR_RELEASE_DELAY_MS)
        } else {
            desiredHdrHeadroomRaised = false
            applyDesiredHdrHeadroom(1f)
        }
    }

    private fun applyDesiredHdrHeadroom(value: Float) {
        if (Build.VERSION.SDK_INT >= 35) {
            if (value == appliedDesiredHdrHeadroom) return
            appliedDesiredHdrHeadroom = value
            setDesiredHdrHeadroom(value)
            windowClip?.request()
        }
    }

    private fun resetHdrSurfaceState() {
        removeCallbacks(releaseHdrHeadroom)
        hdrContentAvailable = false
        desiredHdrHeadroomRaised = false
        applyDesiredHdrHeadroom(1f)
        renderThread.setDisplayHdrSdrRatio(1f)
    }

    private fun shouldAnimate(): Boolean = !frozen && surfaceReady && isAttachedToWindow &&
        width > 0 && height > 0 && windowVisibility == View.VISIBLE && isShown

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
        private const val SURF_PROBE = "FableSolSurfProbe"
        // 显示模式未知时的保底节奏；实际目标随显示刷新率在 [60, 120] 内跟随。
        const val TARGET_FPS = 60.0
        const val MAX_RENDER_FPS = 120.0
        private const val MIN_VALID_REFRESH_RATE = 10f
        private const val DISPLAY_POLL_INTERVAL_MS = 250L
        // 降档看门狗：连续 4 次轮询（约 1s）观测派发间隔越限才重投，避免瞬时抖动误触发；
        // 重投至少间隔 4s；撤票到重投留 96ms（约 6 个 60Hz 帧），保证两笔状态先后落到 SF。
        private const val DEMOTED_POLLS_BEFORE_REVOTE = 4
        private const val REVOTE_MIN_INTERVAL_MS = 4000L
        private const val REVOTE_CLEAR_TO_APPLY_DELAY_MS = 96L
        private const val PERF_LOG_FILE = "fablesol_frame_perf.log"
        private const val DEBUG_PREFIX = "[DEBUG-FABLESOL-GL]"
        private const val HDR_RELEASE_DELAY_MS = 360L
        private const val INTRINSIC_W_DP = 280f
        private const val INTRINSIC_H_DP = 420f
    }
}
