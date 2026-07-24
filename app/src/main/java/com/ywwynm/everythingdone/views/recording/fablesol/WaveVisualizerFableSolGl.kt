package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Display
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.helpers.DebugFileLogger
import com.ywwynm.everythingdone.model.ThingBackground
import kotlin.math.max
import kotlin.math.min

/** SurfaceView 宿主：UI 线程只投递 60Hz 时间戳，全部水体工作在 FableSolGles 线程。 */
class WaveVisualizerFableSolGl @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback, FableSolFrameReceiver {

    private val density = resources.displayMetrics.density.toDouble()
    private val renderThread = FableSolGlRenderThread(
        context,
        density,
        onHdrStatus = { active, _ ->
            post {
                hdrContentAvailable = surfaceReady && active
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
    internal var onGlFailure: ((String) -> Unit)? = null
    private var animating = false
    private var surfaceReady = false
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
        holder.setFormat(
            if (Build.VERSION.SDK_INT >= 34 && resources.configuration.isScreenHdr) {
                PixelFormat.RGBA_F16
            } else {
                PixelFormat.RGBA_8888
            }
        )
        holder.addCallback(this)
    }

    fun setThingBackground(background: ThingBackground) {
        renderThread.setThingBackground(background)
    }

    fun setContainerGravity(x: Float, y: Float, z: Float) {
        renderThread.setGravity(x, y, z)
    }

    internal fun setPerformanceMonitor(monitor: FableSolPerformanceMonitor?) {
        performanceMonitor = monitor
        renderThread.setPerformanceMonitor(monitor)
    }

    internal fun setPresentationAlpha(alpha: Float) {
        renderThread.setPresentationAlpha(alpha)
    }

    internal fun setRecordingHdrActive(active: Boolean) {
        recordingHdrRequested = active
        renderThread.setHdrRecordingRequested(active)
        updateDesiredHdrHeadroom()
    }

    /** 用户 HDR 强度（1.0=关，9.6 封顶）：立即生效，已抬升的期望 headroom 同步改请求值。 */
    internal fun setHdrStrength(strength: Float) {
        val value = strength.coerceIn(
            FableSolHdrPolicy.STRENGTH_OFF,
            FableSolHdrPolicy.MAX_STRENGTH
        )
        if (value == hdrStrength) return
        hdrStrength = value
        renderThread.setHdrStrength(value)
        updateDesiredHdrHeadroom()
    }

    internal fun setTuningValue(key: String, value: Double) {
        renderThread.setTuningValue(key, value)
    }

    /**
     * 暂停冻结（与 Python 模拟器同语义）：模拟与音频泵停住、画面静止，但渲染
     * 循环照跑——冻结画面上调参、换色、HDR 切换仍逐帧实时生效。
     */
    internal fun setSimulationPaused(paused: Boolean) {
        renderThread.setSimulationPaused(paused)
    }

    internal fun beginBackgroundTransition(background: ThingBackground) {
        renderThread.beginBackgroundTransition(background)
    }

    internal fun setContentVerticalOffsetDp(offsetDp: Float) {
        renderThread.setContentVerticalOffsetDp(offsetDp)
    }

    internal fun setBottomCornerRadiusPx(radiusPx: Float) {
        renderThread.setBottomCornerRadiusPx(radiusPx)
    }

    override fun onAudioFrames(frames: List<FableSolFeatureFrame>, events: List<FableSolEvent>) {
        renderThread.onAudioFrames(frames, events)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (!holder.surface.isValid) return
        surfaceReady = true
        votedFrameRate = 0f   // Surface 已换新，旧票不再有效
        val frame = holder.surfaceFrame
        val preferHdr = canBuildHdrSurface()
        val hdrSdrRatio = currentHdrSdrRatio()
        renderThread.attach(
            holder.surface,
            max(width, frame.width()).coerceAtLeast(1),
            max(height, frame.height()).coerceAtLeast(1),
            preferHdr,
            hdrSdrRatio
        )
        renderThread.setHdrStrength(hdrStrength)
        renderThread.setHdrRecordingRequested(recordingHdrRequested)
        ensureAnimating()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (!surfaceReady && holder.surface.isValid) {
            surfaceReady = true
            votedFrameRate = 0f   // Surface 已换新，旧票不再有效
            renderThread.attach(
                holder.surface,
                width.coerceAtLeast(1),
                height.coerceAtLeast(1),
                canBuildHdrSurface(),
                currentHdrSdrRatio()
            )
            renderThread.setHdrStrength(hdrStrength)
            renderThread.setHdrRecordingRequested(recordingHdrRequested)
        } else {
            renderThread.resize(width, height)
        }
        ensureAnimating()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
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
        ensureAnimating()
    }

    override fun onDetachedFromWindow() {
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
        if (visibility == View.VISIBLE) ensureAnimating() else stopFrameLoop()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
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

    private fun canBuildHdrSurface(): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        val currentDisplay = display ?: return false
        return currentDisplay.isHdr && currentDisplay.isHdrSdrRatioAvailable
    }

    private fun currentHdrSdrRatio(): Float {
        if (Build.VERSION.SDK_INT < 34) return 1f
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
        }
    }

    private fun resetHdrSurfaceState() {
        removeCallbacks(releaseHdrHeadroom)
        hdrContentAvailable = false
        desiredHdrHeadroomRaised = false
        applyDesiredHdrHeadroom(1f)
        renderThread.setDisplayHdrSdrRatio(1f)
    }

    private fun shouldAnimate(): Boolean = surfaceReady && isAttachedToWindow &&
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
