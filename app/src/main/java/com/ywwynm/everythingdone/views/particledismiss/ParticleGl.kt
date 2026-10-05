package com.ywwynm.everythingdone.views.particledismiss

import android.content.res.AssetManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import kotlin.math.min

/**
 * 进程内唯一的粒子渲染线程与 EGL 上下文（D4：全部常驻，不随动画释放）。
 *
 * - 上下文、着色器程序、累积纹理与帧缓冲、两张三维流场纹理、历史缓冲池都常驻；
 *   每个会话只建自己的材料/状态/色素/压缩 SSBO、前景纹理与压力网格，结束只删这些。
 * - 线程上始终有一个 1×1 的 pbuffer 作为空闲绘制面，所以**准备与预计算不需要窗口 Surface**，
 *   可以在 SurfaceView 的表面还没创建出来的时候就先跑起来。
 * - 一拍里依次处理各窗口的各会话，最后每个窗口只 `eglSwapBuffers` 一次。
 * - 不再每帧 `glFinish`：每次交换后放一个栅栏，下一拍开画之前等它，保证最多一帧在途。
 *
 * 逐帧成本专项第二批（F14）：线程上有**两个**同 share group 的上下文——出帧上下文
 * （现场积分、历史恢复、绘制、resolve、swap）与后台上下文（出现预计算、左滑预读、
 * 创建页预算）。两者用 `EGL_IMG_context_priority` 分档，**实际档位必须
 * `eglQueryContext` 读回**（规范原文说它只是 hint），读不到或两档没分开就退回单上下文。
 * 两个上下文在同一条线程上按需 `eglMakeCurrent` 切换：程序对象与 uniform、缓冲、纹理都
 * 共享，容器对象（VAO / FBO）只有出帧上下文碰。
 */
internal object ParticleGl {

    private const val MSG_PUMP = 1
    private const val MSG_POLL = 2

    /** 一次 pump 里连续做准备工作的墙钟预算；超过就让出，保证呈现回调与下一拍不被拖住。 */
    private const val WORK_BUDGET_NANOS = 8_000_000L

    private val thread: HandlerThread by lazy {
        HandlerThread("ParticleGl", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    }

    private val handler: Handler by lazy {
        Handler(thread.looper) { message ->
            if (message.what == MSG_PUMP || message.what == MSG_POLL) pump()
            true
        }
    }

    @Volatile private var beatTarget = 0L
    private var consumedTarget = 0L

    @Volatile private var clockRunning = false

    /** 只在 GL 线程访问。 */
    private val windows = ArrayList<ParticleWindowSurface>()

    var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private set
    var config: EGLConfig? = null
        private set
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var background: EGLContext = EGL14.EGL_NO_CONTEXT
    private var current: EGLContext = EGL14.EGL_NO_CONTEXT
    private var idleSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var bound: EGLSurface = EGL14.EGL_NO_SURFACE
    private var broken = false

    /** 后台上下文可用（已读回两档不同的优先级）。 */
    val hasBackgroundContext get() = background != EGL14.EGL_NO_CONTEXT

    /** 读回的优先级档位：[出帧, 后台]；单上下文时为 null。观察器与日志都取这一份。 */
    @Volatile var contextPriorities: IntArray? = null
        private set

    /** 双上下文的结论（含退回原因），写进日志与观察器产物。 */
    @Volatile var contextNote: String = "未建立"
        private set

    /** `eglMakeCurrent` 切换的累计次数与墙钟；决定「同线程切换」这条路值不值。 */
    @Volatile var switchCount = 0
        private set
    @Volatile var switchNanos = 0L
        private set

    var shared: ParticleMicroflakeRenderer.Shared? = null
        private set

    @Volatile private var clockAttached = false

    /**
     * 在 GL 线程上执行；默认先切回**出帧上下文**。
     *
     * 容器对象（VAO / FBO）与窗口表面只在出帧上下文里有效，而后台上下文是「用完不一定切回」
     * 的粘滞状态，所以每一段外部投进来的工作都必须有确定的起点。后台工作由调用方自己
     * [useBackground]（创建页预算就是这么做的）。
     */
    fun post(action: () -> Unit) {
        handler.post(guarded(action))
    }

    /** 等在途栅栏时用；别在 GL 线程上空转轮询。 */
    fun postDelayed(delayMs: Long, action: () -> Unit) {
        handler.postDelayed(guarded(action), delayMs)
    }

    private fun guarded(action: () -> Unit) = Runnable {
        useForeground()
        runCatching { action() }.onFailure {
            Log.e(ParticleMicroflakeRenderer.TAG, "粒子 GL 任务失败", it)
        }
    }

    /**
     * 请求尽快跑一次 pump。可从任意线程调用。
     *
     * 立即与延迟用两种消息：延迟的那一条**不能**覆盖已经排好的立即消息，否则节拍线程刚投进来的
     * 一拍会被 pump 结尾的轮询重排、白白晚 2 ms。
     */
    fun wake() {
        handler.removeMessages(MSG_POLL)
        if (!handler.hasMessages(MSG_PUMP)) handler.sendEmptyMessage(MSG_PUMP)
    }

    private fun wakeLater(delayMs: Long) {
        if (handler.hasMessages(MSG_PUMP) || handler.hasMessages(MSG_POLL)) return
        handler.sendEmptyMessageDelayed(MSG_POLL, delayMs)
    }

    fun register(window: ParticleWindowSurface) {
        attachClock()
        post {
            if (!windows.contains(window)) windows += window
            wake()
        }
    }

    fun unregister(window: ParticleWindowSurface) {
        post {
            windows -= window
            window.releaseGl()
            if (bound != EGL14.EGL_NO_SURFACE && bound == window.lastEglSurface) bindIdle()
            wake()
        }
    }

    /** 内存告警：释放可再生的大块（历史缓冲池）；上下文、程序与常驻纹理不动。 */
    fun trimMemory() {
        // 没用过粒子动画的进程不必为了这一下把线程起起来。
        if (shared == null && !clockAttached) return
        post { if (windows.none { it.busy }) shared?.trim() }
    }

    private fun attachClock() {
        if (clockAttached) return
        clockAttached = true
        ParticleClock.setListener { target ->
            beatTarget = target
            wake()
        }
    }

    private fun startClock() {
        if (clockRunning) return
        clockRunning = true
        ParticleClock.start()
    }

    private fun stopClock() {
        if (!clockRunning) return
        clockRunning = false
        ParticleClock.stop()
    }

    private fun pump() {
        if (windows.isEmpty()) { stopClock(); return }
        if (!ensureContext()) {
            windows.toList().forEach { it.failAll() }
            stopClock()
            return
        }
        val enteredAt = if (ParticleBeatTrace.enabled) System.nanoTime() else 0L
        val target = beatTarget
        val beat = target > consumedTarget
        if (beat) consumedTarget = target

        val live = windows.toList()
        if (beat) {
            useForeground()
            lastCompositeAt = System.nanoTime()
            for (window in live) window.composite(target)
        }
        for (window in live) window.resolvePresented()

        // 准备与预计算：只要还没到下一拍就一直推进，超出预算就让出一轮。
        // 截止时间只算一次——每轮重算等于给了一个永不到期的预算。
        val deadline = workDeadline(live)
        val workStartedAt = if (ParticleBeatTrace.enabled) System.nanoTime() else 0L
        var progressed = false
        var working = true
        while (working && beatTarget <= consumedTarget && System.nanoTime() < deadline) {
            working = false
            for (window in live) if (window.advanceOnce()) { working = true; progressed = true }
            // 首帧不等下一拍：一有内容就立刻停下来出帧（见 presentFirstFrames）。
            if (live.any { it.needsFirstFrame() }) break
        }
        // F15 / 研究第 10 节：粒子层是独立 SurfaceView，首帧准备好之后不必等下一次节拍才合成。
        if (!beat && presentFirstFrames(live)) for (window in live) window.resolvePresented()

        if (ParticleBeatTrace.enabled) ParticleBeatTrace.record(ParticleBeatTrace.PUMP,
            enteredAt, target, consumedTarget, if (beat) 1L else 0L,
            System.nanoTime() - workStartedAt, System.nanoTime())

        for (window in windows.toList()) if (window.detached) { windows -= window; window.releaseGl() }
        if (windows.isEmpty()) { stopClock(); return }

        if (windows.any { it.needsBeat() }) startClock() else stopClock()
        val pending = windows.any { it.needsWork() }
        if (beatTarget > consumedTarget) wake()
        else if (pending && progressed) wake()
        else if (pending || windows.any { it.awaitingPresentation }) wakeLater(POLL_INTERVAL_MS)
    }

    /** 准备阶段在等外部（材料线程、触摸反馈）时的轮询间隔。 */
    private const val POLL_INTERVAL_MS = 2L

    /**
     * F15：已经有会话在出帧时，预算与预读**不许吃掉出帧那一拍**。
     *
     * 阶段 0' 实测左滑的呈现间隔 p90 恰好是两个 vsync：一次 pump 里预读能连着跑满 8 ms 的
     * 预算，下一拍的合成就被推到再下一拍。这里把截止时间压到「下一拍之前还剩
     * [FRAME_RESERVE_NANOS]」，合成与呈现回调总有地方落脚。没有会话在出帧时（例如首页空闲
     * 预算、弹窗还在预计算）仍用完整的 [WORK_BUDGET_NANOS]。
     */
    private const val FRAME_RESERVE_NANOS = 6_000_000L

    private fun workDeadline(live: List<ParticleWindowSurface>): Long {
        val now = System.nanoTime()
        val full = now + WORK_BUDGET_NANOS
        if (!ParticlePerfTuning.yieldWorkBeforeBeat || live.none { it.presenting }) return full
        val beforeBeat = consumedTarget + ParticleClock.outputPeriodNanos - FRAME_RESERVE_NANOS
        return if (beforeBeat <= now) now else min(full, beforeBeat)
    }

    /**
     * 把「下一拍本来要画的那一帧」提前到现在。
     *
     * 只在**有会话一帧都还没提交过**时触发：呈现时刻写当前时刻（即「尽快」，见研究第 9 节：
     * 写到未来会加深流水线）。所有窗口一起合成，别的会话不会因为这次提前而少一帧。
     *
     * **必须把它要替掉的那一拍真的吃掉。** 提前出的这一帧会在 `now` 之后的第一个 vsync 上屏，
     * 而节拍的目标就锚在各个 vsync 上：只把 [consumedTarget] 推到 `now`，紧接着那一拍
     * （目标 ≈ 同一个 vsync）照样会再提交一帧，两张缓冲落在同一个显示周期里，
     * SurfaceFlinger 只 latch 新的那一张、把先提交的直接丢掉。被丢掉的那一帧
     * **永远等不到 `EGL_DISPLAY_PRESENT_TIME`**，它卡在待确认队列的头上，后面的帧要等到
     * 队列溢出才一起放行——2026-09-17 魅族实测就是这样：首帧提交早了 6–21 ms，首帧
     * **确认上屏**却从 58–68 ms 退到 120–131 ms（三个左滑场景一致）。
     * 把已消费的节拍推到 `now + 一拍`，正好吃掉那一个目标，下一拍照常。
     */
    private fun presentFirstFrames(live: List<ParticleWindowSurface>): Boolean {
        if (!ParticlePerfTuning.immediateFirstFrame) return false
        if (live.none { it.needsFirstFrame() }) return false
        val now = System.nanoTime()
        // 保险：会话可能「想出帧」却画不出内容（例如可见性刚变过、阶段还没到 PLAY）。
        // 那一次合成不会把它记成已提交，条件因此还成立；限一拍最多提前一次，别绕成连续交换。
        if (now - lastCompositeAt < ParticleClock.INTERVAL_NANOS / 2) return false
        useForeground()
        lastCompositeAt = now
        for (window in live) window.composite(now)
        consumedTarget = maxOf(consumedTarget, now + ParticleClock.INTERVAL_NANOS)
        return true
    }

    private var lastCompositeAt = 0L

    private fun ensureContext(): Boolean {
        if (broken) return false
        if (context != EGL14.EGL_NO_CONTEXT) return true
        return try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { eglFailure("eglGetDisplay") }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { eglFailure("eglInitialize") }
            // 常驻线程上始终要有一个可绑定的绘制面，准备与预计算才能在窗口 Surface 出现之前
            // 就跑起来。优先 WINDOW|PBUFFER 的配置；个别驱动没有这种组合时退回只要 WINDOW，
            // 空闲时用无表面绑定（EGL_KHR_surfaceless_context）。
            val chosen = chooseConfig(display, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT)
                ?: chooseConfig(display, EGL14.EGL_WINDOW_BIT)
            checkNotNull(chosen) { eglFailure("eglChooseConfig(RGBA8)") }
            config = chosen
            val priorityAvailable =
                (EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS) ?: "")
                    .contains("EGL_IMG_context_priority")
            context = createContext(chosen, EGL14.EGL_NO_CONTEXT,
                if (priorityAvailable) EGL_CONTEXT_PRIORITY_HIGH_IMG else 0)
            check(context != EGL14.EGL_NO_CONTEXT) { eglFailure("eglCreateContext") }
            idleSurface = EGL14.eglCreatePbufferSurface(display, chosen,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            if (idleSurface == EGL14.EGL_NO_SURFACE) {
                Log.w(ParticleMicroflakeRenderer.TAG,
                    "粒子 pbuffer 不可用，改用无表面绑定 EGL 0x${Integer.toHexString(EGL14.eglGetError())}")
            }
            check(EGL14.eglMakeCurrent(display, idleSurface, idleSurface, context)) {
                eglFailure("eglMakeCurrent")
            }
            bound = idleSurface
            foregroundSurface = idleSurface
            current = context
            ParticleMicroflakeRenderer.logCapabilities()
            ensureBackgroundContext(chosen, priorityAvailable)
            true
        } catch (error: Throwable) {
            Log.e(ParticleMicroflakeRenderer.TAG, "粒子 EGL 上下文建立失败", error)
            broken = true
            false
        }
    }

    /**
     * 建立/更新共享资源。尺寸变化时重建累积纹理，但只在没有会话开工时才允许——
     * 会话的 `frameWidth/frameHeight` 是按建时尺寸算的，中途换掉会让粒子错位。
     */
    fun ensureShared(assets: AssetManager, width: Int, height: Int,
                     allowResize: Boolean): ParticleMicroflakeRenderer.Shared? {
        if (!ensureContext()) return null
        val existing = shared ?: ParticleMicroflakeRenderer.Shared(assets).also { shared = it }
        if (existing.prepared && !allowResize) return existing
        // 累积纹理挂的是 FBO、全屏三角用的是 VAO——容器对象不跨上下文共享，只许出帧上下文建。
        useForeground()
        return runCatching { existing.ensure(width, height); existing }.getOrElse {
            Log.e(ParticleMicroflakeRenderer.TAG, "粒子共享资源建立失败", it)
            broken = true
            null
        }
    }

    /** 只要上下文（不建流体样式的共享资源）：飘散会话的准备阶段用。 */
    fun ensureContextReady(): Boolean {
        if (!ensureContext()) return false
        useForeground()
        return true
    }

    fun bind(surface: EGLSurface): Boolean {
        useForeground()
        if (surface == bound && current === context) return true
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return false
        bound = surface
        foregroundSurface = surface
        current = context
        return true
    }

    fun bindIdle() {
        if (context == EGL14.EGL_NO_CONTEXT) return
        foregroundSurface = idleSurface
        if (EGL14.eglMakeCurrent(display, idleSurface, idleSurface, context)) {
            bound = idleSurface
            current = context
        }
    }

    /** 出帧上下文上一次绑的绘制面；切回来时原样恢复，省掉一次多余的 `eglMakeCurrent`。 */
    private var foregroundSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    // ---- F14：两个上下文 ----

    private const val EGL_CONTEXT_PRIORITY_LEVEL_IMG = 0x3100
    private const val EGL_CONTEXT_PRIORITY_HIGH_IMG = 0x3101
    private const val EGL_CONTEXT_PRIORITY_MEDIUM_IMG = 0x3102
    private const val EGL_CONTEXT_PRIORITY_LOW_IMG = 0x3103

    private fun createContext(config: EGLConfig, share: EGLContext, priority: Int): EGLContext {
        val attributes = if (priority != 0) intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL_CONTEXT_PRIORITY_LEVEL_IMG, priority, EGL14.EGL_NONE)
        else intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        val created = EGL14.eglCreateContext(display, config, share, attributes, 0)
        if (created != EGL14.EGL_NO_CONTEXT || priority == 0) return created
        // 带优先级属性建不出来就退回不带：这一档本来就是 hint，不能因此让粒子整个失效。
        return EGL14.eglCreateContext(display, config, share,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
    }

    /** 读回实际拿到的档位；规范明文说请求值只是 hint，不读回就不能按「设了就生效」归因。 */
    private fun queryPriority(target: EGLContext): Int? {
        val value = IntArray(1)
        if (!EGL14.eglQueryContext(display, target, EGL_CONTEXT_PRIORITY_LEVEL_IMG, value, 0)) return null
        return value[0]
    }

    private fun priorityName(level: Int?) = when (level) {
        null -> "读不到"
        EGL_CONTEXT_PRIORITY_HIGH_IMG -> "HIGH"
        EGL_CONTEXT_PRIORITY_MEDIUM_IMG -> "MEDIUM"
        EGL_CONTEXT_PRIORITY_LOW_IMG -> "LOW"
        else -> "0x${Integer.toHexString(level)}"
    }

    private fun ensureBackgroundContext(config: EGLConfig, priorityAvailable: Boolean) {
        val note: (String) -> Unit = {
            contextNote = it
            Log.i(ParticleMicroflakeRenderer.TAG, "粒子上下文 $it")
        }
        if (!ParticlePerfTuning.backgroundContext) { note("单上下文（开关关闭）"); return }
        if (!priorityAvailable) { note("单上下文：驱动没有 EGL_IMG_context_priority"); return }
        val created = createContext(config, context, EGL_CONTEXT_PRIORITY_LOW_IMG)
        if (created == EGL14.EGL_NO_CONTEXT) {
            note("单上下文：后台上下文创建失败 EGL 0x${Integer.toHexString(EGL14.eglGetError())}")
            return
        }
        val high = queryPriority(context)
        val low = queryPriority(created)
        // 档位常量按 HIGH < MEDIUM < LOW 递增，数值小的优先级高。
        if (high == null || low == null || high >= low) {
            EGL14.eglDestroyContext(display, created)
            note("单上下文：读回 出帧=${priorityName(high)} 后台=${priorityName(low)}，两档没有分开")
            return
        }
        background = created
        contextPriorities = intArrayOf(high, low)
        note("双上下文：出帧=${priorityName(high)} 后台=${priorityName(low)}")
        measureSwitchCost()
    }

    /** 同线程切换的一次性标定；「后台放独立线程还是同线程切」这条取舍要有数。 */
    private fun measureSwitchCost() {
        val started = System.nanoTime()
        repeat(16) { useBackground(); useForeground() }
        Log.i(ParticleMicroflakeRenderer.TAG,
            "粒子上下文切换 32 次 用时Ms=${(System.nanoTime() - started) / 1e6}")
        switchCount = 0
        switchNanos = 0L
    }

    private fun recordSwitch(startedAt: Long) {
        switchCount++
        switchNanos += System.nanoTime() - startedAt
    }

    /**
     * 切到后台（低优先级）上下文；不可用时返回 false，调用方原样在出帧上下文里跑。
     *
     * 出帧侧刚上传过的材料、色素与前景纹理要对后台可见，所以切之前先 `glFenceSync` + `glFlush`，
     * 切过去再 `glWaitSync`（服务端等待，不阻塞 CPU）。这条顺带把「后台的 GPU 工作排在本帧
     * 之后」这件事变成显式的——本来就是我们要的次序。
     */
    fun useBackground(): Boolean {
        if (background == EGL14.EGL_NO_CONTEXT || broken) return false
        if (current === background) return true
        val handoff = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        GLES30.glFlush()
        val startedAt = System.nanoTime()
        if (!EGL14.eglMakeCurrent(display, idleSurface, idleSurface, background)) {
            if (handoff != 0L) GLES30.glDeleteSync(handoff)
            Log.w(ParticleMicroflakeRenderer.TAG,
                "粒子后台上下文绑定失败 EGL 0x${Integer.toHexString(EGL14.eglGetError())}")
            return false
        }
        current = background
        bound = idleSurface
        recordSwitch(startedAt)
        if (handoff != 0L) {
            GLES30.glWaitSync(handoff, 0, GLES30.GL_TIMEOUT_IGNORED)
            GLES30.glDeleteSync(handoff)
        }
        return true
    }

    /**
     * 切回出帧上下文。
     *
     * **这里不插 `glWaitSync`**：那会让高优先级的一帧去等低优先级上下文里全部在途的工作
     * （典型的优先级反转）。后台写、出帧读的交接由数据那一侧保证——历史采样的
     * `preparedSamples` 只计已经**确认完成**的采样（[ParticleMicroflakeRenderer.pollReverse]），
     * 整段预计算的交接在 `endReverse` 处一次性排空。
     */
    fun useForeground() {
        if (context == EGL14.EGL_NO_CONTEXT || current === context) return
        GLES30.glFlush()
        val startedAt = System.nanoTime()
        val restore = if (foregroundSurface != EGL14.EGL_NO_SURFACE) foregroundSurface else idleSurface
        if (!EGL14.eglMakeCurrent(display, restore, restore, context)) {
            Log.w(ParticleMicroflakeRenderer.TAG,
                "粒子出帧上下文绑定失败 EGL 0x${Integer.toHexString(EGL14.eglGetError())}")
            // 绘制面可能刚被主线程销毁；退回空闲面，别让整条线程停在没有上下文的状态。
            foregroundSurface = idleSurface
            if (!EGL14.eglMakeCurrent(display, idleSurface, idleSurface, context)) return
            bound = idleSurface
            current = context
            recordSwitch(startedAt)
            return
        }
        current = context
        bound = restore
        recordSwitch(startedAt)
    }

    /**
     * 该 Surface 正被绑定时先切回空闲面，调用方随后才能销毁它。
     *
     * 也要看 [foregroundSurface]：此刻可能正停在后台上下文上，销毁的是**出帧上下文记着的**
     * 那一块，切回来时再去绑就是个已经无效的面。
     */
    fun unbindIfCurrent(surface: EGLSurface) {
        if (surface == bound || surface == foregroundSurface) bindIdle()
    }

    private fun chooseConfig(display: EGLDisplay, surfaceType: Int): EGLConfig? {
        val attributes = intArrayOf(
            EGL14.EGL_SURFACE_TYPE, surfaceType,
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, configs.size, count, 0) ||
            count[0] <= 0) return null
        return configs[0]
    }

    private fun eglFailure(operation: String): String =
        "$operation failed: EGL 0x${Integer.toHexString(EGL14.eglGetError())}"

    private const val EGL_OPENGL_ES3_BIT_KHR = 0x0040
}
