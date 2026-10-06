package com.ywwynm.everythingdone.views.particledismiss

import android.app.Activity
import android.content.Context
import android.content.res.AssetManager
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Build
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.SurfaceControl
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import com.ywwynm.everythingdone.BuildConfig
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 粒子叠加层专用的 SurfaceView；快照合成等遍历要能一眼把它认出来并跳过。 */
internal class ParticleOverlaySurfaceView(context: Context) : SurfaceView(context)

/**
 * 普通粒子会话共享宿主窗口的一个 Surface；卡片通过 [inWindow] 使用各自的 TextureView。
 *
 * `setZOrderOnTop(true)` 下 SurfaceView 不打洞（`isAboveParent()` 恒真），表面被 parent 在
 * Activity 窗口的 layer 内、仍在 Dialog 等独立窗口之下——这正是现有「先就位、后揭开」顺序
 * 需要的。同窗口内两个都 on-top 的 SurfaceView 相对顺序未定义，所以**只能有一个**：窗口内
 * 普通并发会话（上一个弹窗消散、下一个弹窗出现）按创建顺序叠画进同一个 Surface，
 * 一拍一次交换。卡片的 TextureView 属于 Activity 窗口内容，不受这项顺序限制。
 *
 * 共享 Surface 首次需要时创建并留在窗口上，直到 Activity 销毁或配置变化，以复用首次合成。
 * 没有会话时不提交新帧，最后一帧保持全透明；卡片的 TextureView 随各自动画层释放。
 */
internal class ParticleWindowSurface private constructor(
    private val activity: Activity,
    private val decor: ViewGroup,
    private val onTextureReady: (() -> Unit)? = null
) : SurfaceHolder.Callback {

    private val inWindow get() = onTextureReady != null
    @Volatile private var textureSubmitted = false
    @Volatile private var texturePresentedAt = 0L
    private var textureRevealed = false
    val view: View = (if (onTextureReady != null) TextureView(activity).apply {
        isOpaque = false
        // 保持参与绘制，收到首个粒子缓冲后与真实卡片在同一窗口帧中交接。
        alpha = 1f / 65536f
    } else ParticleOverlaySurfaceView(activity).apply {
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
    }).apply {
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    @Volatile private var surface: Surface? = null
    @Volatile private var surfaceWidth = 0
    @Volatile private var surfaceHeight = 0
    @Volatile var detached = false
        private set

    // ---- 只在 GL 线程访问 ----
    private val sessions = ArrayList<ParticleLayerSession>()
    private val incoming = ConcurrentLinkedQueue<ParticleLayerSession>()
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var boundSurface: Surface? = null
    private var timestamps = false
    private var fence = 0L
    private var surfaceDirty = false
    private var recomposite = false
    private val pending = ArrayDeque<Frame>()
    private val firstCardFrames = java.util.IdentityHashMap<ParticleLayerSession, SurfaceControl.Transaction>()
    private val frameTransactions = ArrayDeque<SurfaceControl.Transaction>()
    private val assets: AssetManager =
        activity.applicationContext.assets

    var lastEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        private set

    private class Frame(val frameId: Long, val target: Long, val submittedAt: Long,
                        val drawn: List<ParticleLayerSession>)

    private val attachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) { dispose() }
    }

    init {
        if (view is SurfaceView) view.holder.addCallback(this)
        else (view as TextureView).surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                surface = Surface(texture)
                surfaceWidth = width
                surfaceHeight = height
                ParticleGl.wake()
            }
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                surfaceWidth = width
                surfaceHeight = height
                ParticleGl.wake()
            }
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                if (textureSubmitted && !textureRevealed) {
                    textureRevealed = true
                    view.alpha = 1f
                    onTextureReady?.invoke()
                }
                if (Build.VERSION.SDK_INT >= 29 && view.viewTreeObserver.isAlive) {
                    view.viewTreeObserver.registerFrameCommitCallback {
                        texturePresentedAt = runCatching { texture.timestamp }.getOrDefault(texturePresentedAt)
                        ParticleGl.wake()
                    }
                }
            }
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                val previous = surface
                surface = null
                // 框架立即释放消费者以唤醒可能等缓冲的 eglSwapBuffers；GL 随后释放生产端。
                ParticleGl.post {
                    releaseEglSurface()
                    previous?.release()
                }
                return true
            }
        }
        view.addOnAttachStateChangeListener(attachment)
        decor.addView(view, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        ParticleGl.register(this)
    }

    val busy get() = sessions.isNotEmpty()

    /** 本窗口在出帧的会话全都可以按高帧率出（目前只有飘散）。 */
    val prefersHighFrameRate get() = sessions.isNotEmpty() && sessions.all { it.prefersHighFrameRate }

    /** 当前投给 Surface 的刷新率（0 = 不投）；GL 线程改，Surface 重建时主线程重置为 0。 */
    @Volatile private var votedRate = 0f

    /**
     * GL 线程：按本窗口的状态给 Surface 投票——没有会话不投（0），有流体会话投 [VOTED_FRAME_RATE]，
     * 只有飘散会话投 [HIGH_FRAME_RATE]。只在变化时发一次。
     *
     * **空闲时必须不投**：SurfaceFlinger 按应用所有图层的投票给整个 uid 设帧率覆盖，粒子 Surface 常驻投 60 Hz 时
     * 整个应用（首页滚动、左滑时的手指位置）都被压在 60 fps，面板却在 120 Hz（`dumpsys display` 的
     * `mFrameRateOverrides`，2026-09-26 首页滚动分析与 2026-10-06 左滑实测）。
     */
    fun voteFrameRate(rate: Float) {
        if (rate == votedRate) return
        val target = surface ?: return
        votedRate = rate
        applyFrameRate(target, rate)
    }

    /** 本窗口最近一次真的合成出帧的节拍目标；只在 GL 线程读写。补帧（[ParticleGl] 的 lateFrames）靠它判断这一拍画过没有。 */
    var compositedTarget = 0L
        private set

    fun add(session: ParticleLayerSession) {
        incoming += session
        ParticleGl.wake()
    }

    /** 与该会话第一次实际画入共享 Surface 的缓冲一起撤下交接快照。 */
    fun startCardFrame(session: ParticleLayerSession, transaction: SurfaceControl.Transaction) {
        ParticleGl.post {
            if (session.cancelled || detached) { transaction.close(); return@post }
            firstCardFrames.put(session, transaction)?.close()
            session.setVisible(true)
        }
    }

    /** 下一张缓冲不再包含该卡片，同时将自有快照图层显示出来。 */
    fun endCardFrame(session: ParticleLayerSession, transaction: SurfaceControl.Transaction) {
        ParticleGl.post {
            firstCardFrames.remove(session)?.close()
            session.setVisible(false)
            if (surface == null || detached) {
                transaction.apply(); transaction.close()
            } else frameTransactions.addLast(transaction)
            ParticleGl.wake()
        }
    }

    // ---- SurfaceHolder 回调（主线程） ----

    override fun surfaceCreated(holder: SurfaceHolder) {
        surface = holder.surface
        applyFrameRate(holder.surface, 0f)
        votedRate = 0f
        ParticleGl.post { prime() }
        ParticleGl.wake()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surface = holder.surface
        surfaceWidth = width
        surfaceHeight = height
        applyFrameRate(holder.surface, 0f)
        votedRate = 0f
        ParticleGl.post { prime() }
        ParticleGl.wake()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surface = null
        // 回调返回之后这个 Surface 就不再有效，必须先等 GL 线程放手。
        val latch = CountDownLatch(1)
        ParticleGl.post {
            releaseEglSurface()
            latch.countDown()
        }
        ParticleGl.wake()
        runCatching { latch.await(500, TimeUnit.MILLISECONDS) }
    }

    /**
     * 1.2：不改任何窗口属性，只在自己的 Surface 上投无缝的刷新率；每帧的 `eglPresentationTimeANDROID` 负责钉住
     * 呈现时刻。投什么由 [voteFrameRate] 按会话决定，[rate] 为 0 表示撤掉投票（不表达偏好）。
     */
    private fun applyFrameRate(target: Surface, rate: Float) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                target.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                    Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                target.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            }
        }
    }

    // ---- GL 线程 ----

    private fun intake() {
        while (true) {
            val next = incoming.poll() ?: break
            sessions += next
        }
    }

    private fun targetWidth() = if (surfaceWidth > 0) surfaceWidth else decor.width
    private fun targetHeight() = if (surfaceHeight > 0) surfaceHeight else decor.height

    /**
     * 3b：先交换一张全透明帧，把「这一层的第一次合成」挪到动画开始之前。
     *
     * 一块新表面的第一次缓冲要等 SurfaceFlinger 给它分配并做首次合成：OPD2515 上实测，
     * 同一次运行里**第一次**出现动画的「提交 → 上屏」是 82–88 ms，之后每一次只有 14–17 ms。
     * 空帧不含任何内容，屏幕上看不出来，只是让这一次等待发生在真正需要出画之前。
     */
    fun prime() {
        if (inWindow || primed || !ParticlePerfTuning.primeParticleSurface) return
        if (surface == null || !ensureEglSurface()) return
        if (!ParticleGl.bind(eglSurface)) return
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, targetWidth().coerceAtLeast(1), targetHeight().coerceAtLeast(1))
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        primed = EGL14.eglSwapBuffers(ParticleGl.display, eglSurface)
        surfaceDirty = false
        if (primed) primedAt = System.nanoTime()
    }

    private var primed = false

    /** debug 观察器读它来判断首帧是不是踩在「表面第一次合成」上。 */
    @Volatile var primedAt = 0L
        private set

    /** 推进一步准备/预计算；返回是否有进展。 */
    fun advanceOnce(): Boolean {
        intake()
        purge()
        // 表面创建回调可能早于上下文就绪；准备阶段再补一次，别把这一次等待留给首帧。
        prime()
        if (sessions.isEmpty()) return false
        val width = targetWidth()
        val height = targetHeight()
        if (width <= 0 || height <= 0) return false
        // 流体样式的共享资源（全屏累积缓冲、流场、程序）只在有流体会话时才建；飘散会话自带所需的一切。
        val shared = if (sessions.any { it.needsMicroflakeShared })
            ParticleGl.ensureShared(assets, width, height,
                allowResize = sessions.none { it.started && it.needsMicroflakeShared }) ?: run { failAll(); return false }
        else if (ParticleGl.ensureContextReady()) null else run { failAll(); return false }
        var progressed = false
        for (session in sessions.toList()) if (session.advanceOnce(shared)) progressed = true
        return progressed
    }

    /** 节拍要不要继续跑；与「这一拍出不出帧」分开，否则手势停顿一下节拍就断（见 needsBeat）。 */
    fun needsBeat(): Boolean {
        if (surface == null) return false
        return sessions.any { it.needsBeat() } || needsFrame()
    }

    fun needsFrame(): Boolean {
        if (surface == null) return false
        if (frameTransactions.isNotEmpty()) return true
        if (sessions.any { it.wantsFrame() }) return true
        // 有会话退出或改变了可见性时要再交换一次，否则它的最后一帧会留在 Surface 上；
        // 全部退完之后那一次把 Surface 抹成全透明。
        return surfaceDirty && (recomposite || sessions.none { it.visible && !it.cancelled })
    }

    fun needsWork(): Boolean = incoming.isNotEmpty() || sessions.any { it.needsWork() }

    /** 有手势会话要补画这一拍（见 [ParticleLayerSession.wantsLateFrame]）。 */
    fun wantsLateFrame(): Boolean = surface != null && sessions.any { it.wantsLateFrame() }

    /** 有会话的首帧已经备好、还没提交过：这一帧不必等下一次节拍。 */
    fun needsFirstFrame(): Boolean {
        if (surface == null) return false
        return sessions.any { it.wantsFirstFrame() }
    }

    /** 本窗口已经有会话在逐帧出画；预算与预读要给它让出每一拍（见 ParticleGl.workDeadline）。 */
    val presenting get() = surface != null && sessions.any { it.presenting }

    val awaitingPresentation get() = pending.isNotEmpty()

    fun composite(target: Long) = ParticleTrace.section("particle-composite") { compositeOnce(target) }

    private fun compositeOnce(target: Long) {
        intake()
        purge()
        if (!needsFrame()) {
            if (ParticleBeatTrace.enabled) sessions.forEach { it.traceSkip(target) }
            return
        }
        if (!ensureEglSurface()) return
        if (!ParticleGl.bind(eglSurface)) { failAll(); return }
        compositedTarget = target
        // L3：ADPF 要每拍报一次真实耗时，所以这个时刻在取证关掉时也要取。
        val enteredAt = System.nanoTime()
        awaitFence()
        val fenceDoneAt = if (ParticleBeatTrace.enabled) System.nanoTime() else 0L
        val width = targetWidth()
        val height = targetHeight()
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val drawn = ArrayList<ParticleLayerSession>(sessions.size)
        // 创建顺序即叠放顺序：先创建的在下。
        for (session in sessions.toList()) if (session.drawInto(target)) drawn += session
        if (Build.VERSION.SDK_INT >= 34 && view is SurfaceView &&
            (frameTransactions.isNotEmpty() || drawn.any { firstCardFrames.containsKey(it) })) {
            val transaction = SurfaceControl.Transaction()
            for (session in drawn) firstCardFrames.remove(session)?.let {
                transaction.merge(it); it.close()
            }
            while (frameTransactions.isNotEmpty()) {
                val next = frameTransactions.removeFirst()
                transaction.merge(next); next.close()
            }
            try { view.applyTransactionToFrame(transaction) }
            catch (_: IllegalStateException) {
                // 主线程刚销毁了表面，此时已经没有可同步显示的粒子帧。
                transaction.apply(); transaction.close()
            }
        }
        val drawnAt = if (ParticleBeatTrace.enabled) System.nanoTime() else 0L
        EGLExt.eglPresentationTimeANDROID(ParticleGl.display, eglSurface, target)
        val frameId = if (timestamps) ParticleFrameTimestamps.nextFrameId(ParticleGl.display, eglSurface) else -1L
        // 不再每帧 glFinish：放一个栅栏，下一拍开画前等它，最多一帧在途。
        fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        if (!EGL14.eglSwapBuffers(ParticleGl.display, eglSurface)) {
            Log.w(ParticleMicroflakeRenderer.TAG,
                "粒子交换失败 EGL 0x${Integer.toHexString(EGL14.eglGetError())}")
            failAll()
            return
        }
        if (ParticleBeatTrace.enabled) ParticleBeatTrace.record(ParticleBeatTrace.COMPOSITE,
            target, enteredAt, fenceDoneAt - enteredAt, drawnAt, System.nanoTime(),
            frameId, drawn.size.toLong())
        // L3：本拍真正做了多少工作。目标是一拍；报得比目标大即为升频请求。
        if (drawn.isNotEmpty()) ParticlePerformanceHint.frame(activity, System.nanoTime() - enteredAt)
        surfaceDirty = drawn.isNotEmpty()
        if (inWindow && drawn.isNotEmpty()) textureSubmitted = true
        recomposite = false
        drawn.forEach { it.frameSubmitted(target) }
        pending += Frame(frameId, target, System.nanoTime(), drawn)
        // 呈现判定要有上限：一直 PENDING 的帧不能把交接卡死。
        while (pending.size > MAX_PENDING_FRAMES) releaseFrame(pending.removeFirst())
    }

    fun resolvePresented() {
        if (pending.isEmpty()) return
        val now = System.nanoTime()
        while (pending.isNotEmpty()) {
            val head = pending.first()
            if (inWindow) {
                if (head.target > texturePresentedAt && now - head.submittedAt <= PRESENT_TIMEOUT_NANOS) return
                releaseFrame(pending.removeFirst())
                continue
            }
            val presentTime = if (head.frameId >= 0 && now - head.submittedAt <= PRESENT_TIMEOUT_NANOS)
                ParticleFrameTimestamps.presentTime(ParticleGl.display, eglSurface, head.frameId) else null
            if (!shouldRelease(now - head.submittedAt, presentTime)) {
                // 合成器丢掉的帧一直报 PENDING。它后面已经有帧真的上屏，说明它不会再上屏了，直接放行；
                // 否则它会把后面已上屏的帧一起压到超时（飘散的弹窗出现因此晚 200 多毫秒才交接给真实面板）。
                if (!laterFramePresented(now)) return
                releaseFrame(pending.removeFirst())
                continue
            }
            if (ParticleBeatTrace.enabled) ParticleBeatTrace.record(ParticleBeatTrace.PRESENT,
                head.target, head.frameId, presentTime ?: 0L, now, now - head.submittedAt)
            releaseFrame(pending.removeFirst(), presentTime)
        }
    }

    /** 队首之后有没有帧已经报了真实呈现时刻（缓冲按顺序上屏，排在后面的帧上屏了，前面的就不会再上屏）。 */
    private fun laterFramePresented(now: Long): Boolean {
        for (k in 1 until pending.size) {
            val frame = pending[k]
            if (frame.frameId < 0 || now - frame.submittedAt > PRESENT_TIMEOUT_NANOS) continue
            val time = ParticleFrameTimestamps.presentTime(ParticleGl.display, eglSurface, frame.frameId)
            if (time > 0) return true
        }
        return false
    }

    /**
     * [presentTime] 是驱动报的真实呈现时刻（PENDING / INVALID / 无时间戳时为 null）。
     * 它与「我们什么时候问出来」是两件事，3b 的空档归因必须把两者分开看。
     */
    private fun releaseFrame(frame: Frame, presentTime: Long? = null) {
        val displayed = presentTime?.takeIf { it > 0 && it != ParticleFrameTimestamps.PENDING }
        // 调参页开着的时候才记；拿不到驱动的呈现时刻（TextureView 那条路）就退回本拍的
        // 目标时刻，此时读数是「我们按节拍出了几帧」而不是「上屏了几帧」。
        if (frame.drawn.isNotEmpty()) ParticleFrameStats.onPresented(displayed ?: frame.target)
        frame.drawn.forEach { runCatching { it.framePresented(frame.target, displayed) } }
    }

    private fun purge() {
        val finished = sessions.filter { it.cancelled }
        if (finished.isEmpty()) return
        sessions.removeAll(finished.toSet())
        finished.forEach { session ->
            firstCardFrames.remove(session)?.close()
            runCatching { session.dispose() }
        }
        // 退出的会话画在 Surface 上的最后一帧必须被下一次合成抹掉。
        recomposite = true
        // 已经退出的会话不再接收呈现回调。
        val remaining = ArrayList<Frame>(pending.size)
        while (pending.isNotEmpty()) {
            val frame = pending.removeFirst()
            val alive = frame.drawn.filter { !finished.contains(it) }
            remaining += Frame(frame.frameId, frame.target, frame.submittedAt, alive)
        }
        remaining.forEach { pending.addLast(it) }
    }

    private fun ensureEglSurface(): Boolean {
        val target = surface ?: return false
        if (eglSurface != EGL14.EGL_NO_SURFACE && boundSurface === target && target.isValid) return true
        releaseEglSurface()
        if (!target.isValid) return false
        val config = ParticleGl.config ?: return false
        val created = EGL14.eglCreateWindowSurface(ParticleGl.display, config, target,
            intArrayOf(EGL14.EGL_NONE), 0)
        if (created == EGL14.EGL_NO_SURFACE) {
            Log.w(ParticleMicroflakeRenderer.TAG,
                "粒子窗口表面创建失败 EGL 0x${Integer.toHexString(EGL14.eglGetError())}")
            return false
        }
        eglSurface = created
        lastEglSurface = created
        boundSurface = target
        surfaceDirty = false
        timestamps = !inWindow && ParticleFrameTimestamps.enable(ParticleGl.display, created)
        if (BuildConfig.DEBUG) Log.i(
            ParticleMicroflakeRenderer.TAG, "粒子 Surface 就绪 帧时间戳=$timestamps")
        return true
    }

    private fun awaitFence() {
        if (fence == 0L) return
        GLES30.glClientWaitSync(fence, GLES30.GL_SYNC_FLUSH_COMMANDS_BIT, FENCE_TIMEOUT_NANOS)
        GLES30.glDeleteSync(fence)
        fence = 0L
    }

    // 同批试过「把呈现时刻写成本拍 vsync + 一个输出周期减余量」，想用它挡住「早到一个显示
    // 周期」的那一档，让呈现间隔齐整。**魅族 20 Infinity 上这个下限根本不生效**：下限写在
    // vsync + 12.6 ms，实测仍有 28/69 帧在 vsync + 8.3 ms 就上屏了（一个显示周期）。也就是说
    // 这台的 SurfaceFlinger 不按 `desiredPresentTime` 推迟我们的缓冲。既然量不出效果、又可能
    // 在别的机型上白白多等一个周期，整段撤掉，`eglPresentationTimeANDROID` 仍写本拍目标
    // （= 尽快呈现）。结论写进 per-frame-batch3 第 3 节。

    private fun releaseEglSurface() {
        // 表面没了就问不出呈现时刻了。把在途帧按「已上屏」放行，否则等着它的会话会一直
        // 停在末帧、只能靠看门狗收尾。
        while (pending.isNotEmpty()) releaseFrame(pending.removeFirst())
        if (fence != 0L) { GLES30.glDeleteSync(fence); fence = 0L }
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            ParticleGl.unbindIfCurrent(eglSurface)
            EGL14.eglDestroySurface(ParticleGl.display, eglSurface)
        }
        eglSurface = EGL14.EGL_NO_SURFACE
        lastEglSurface = EGL14.EGL_NO_SURFACE
        boundSurface = null
        timestamps = false
        surfaceDirty = false
        primed = false
        primedAt = 0L
    }

    /** GL 线程：窗口整体退出。 */
    fun releaseGl() {
        firstCardFrames.values.forEach { it.close() }
        firstCardFrames.clear()
        while (frameTransactions.isNotEmpty()) frameTransactions.removeFirst().let {
            it.apply(); it.close()
        }
        sessions.toList().forEach { runCatching { it.dispose() } }
        sessions.clear()
        incoming.clear()
        releaseEglSurface()
    }

    fun failAll() {
        sessions.toList().forEach { runCatching { it.dispose() } }
        sessions.clear()
        pending.clear()
        recomposite = true
    }

    /** 主线程：Activity 销毁或配置变化。 */
    private fun dispose() {
        if (detached) return
        detached = true
        synchronized(hosts) { if (hosts[decor]?.get() === this) hosts.remove(decor) }
        view.removeOnAttachStateChangeListener(attachment)
        ParticleGl.unregister(this)
    }

    companion object {
        /**
         * 粒子层投给 SurfaceFlinger 的帧率，也是**采样计划唯一该用的那个值**。
         *
         * 粒子层自己按这个节拍出帧（[applyFrameRate]），与面板此刻在跑 60 还是 120 Hz 无关：
         * 120 Hz 面板上 SurfaceFlinger 留在 120，我们每隔一拍提交一次。用面板的瞬时刷新率去
         * 算采样数，只会让「预算时」与「播放时」读到两个值——阶段 0' 实测的创建页预算必然
         * 未命中就是这么来的（缓存键里放了瞬时读数）。
         */
        const val VOTED_FRAME_RATE = 60f

        /** 飘散高帧率期间投给粒子 Surface 的刷新率（[ParticleGl] 切换，见 [ParticlePerfTuning.driftHighFrameRate]）。 */
        const val HIGH_FRAME_RATE = 120f

        /** 卡片专用，生命周期跟随动画层；每张卡片可以独立与原 View 同帧交接。 */
        fun inWindow(activity: Activity, parent: ViewGroup, onReady: () -> Unit): ParticleWindowSurface =
            ParticleWindowSurface(activity, parent, onReady)

        /**
         * 键是「承载这张表面的那个窗口的 decor」，不是 Activity：弹窗叠弹窗时粒子要改挂到
         * [ParticleElevatedHost] 的全屏窗口上（见那里的说明），同一个 Activity 因此可能同时
         * 有两张表面。
         *
         * 值必须是弱引用：本对象强引用 Activity 与 decor，直接放进 WeakHashMap 会让「值经由
         * 静态根强引用键」，条目永远回收不掉，等于泄漏整个 Activity。
         */
        private val hosts = WeakHashMap<ViewGroup, WeakReference<ParticleWindowSurface>>()

        /** 每个承载窗口一个；首次需要时创建。必须在主线程调用。 */
        fun of(activity: Activity): ParticleWindowSurface? =
            of(activity, activity.window?.decorView as? ViewGroup)

        fun of(activity: Activity, decor: ViewGroup?): ParticleWindowSurface? {
            if (decor == null || !decor.isAttachedToWindow || activity.isDestroyed) return null
            synchronized(hosts) {
                hosts[decor]?.get()?.let { if (!it.detached) return it }
                hosts.remove(decor)
                return runCatching { ParticleWindowSurface(activity, decor) }.getOrNull()
                    ?.also { hosts[decor] = WeakReference(it) }
            }
        }

        /**
         * 最多保留的未确认帧；再多说明呈现时间戳一直没回来，按提交顺序强制放行。飘散高帧率（每 8.3 ms 一帧）时首帧上屏
         * 延迟 40 ms 上下就有 5 帧在途，所以留到 10。
         */
        private const val MAX_PENDING_FRAMES = 10

        /** 单帧的呈现判定上限：超过就按已显示处理，交接不能卡死。 */
        internal const val PRESENT_TIMEOUT_NANOS = 250_000_000L

        private const val FENCE_TIMEOUT_NANOS = 200_000_000L


        /**
         * 这一帧能不能按「已上屏」处理。
         *
         * [presentTime] 非空表示用的是 `EGL_ANDROID_get_frame_timestamps`：
         * PENDING 要继续等（并且必须保序，否则暗层会先于粒子首帧启动）；
         * INVALID / 调用失败按已上屏放行。为空表示没有帧时间戳，走保守判定：
         * **缓冲已入队 + 下一次 vsync**。两条之上还有一个硬上限，交接不能卡死。
         */
        internal fun shouldRelease(elapsedNanos: Long, presentTime: Long?): Boolean {
            if (elapsedNanos > PRESENT_TIMEOUT_NANOS) return true
            if (presentTime != null) return presentTime != ParticleFrameTimestamps.PENDING
            return elapsedNanos >= ParticleClock.INTERVAL_NANOS
        }
    }
}
