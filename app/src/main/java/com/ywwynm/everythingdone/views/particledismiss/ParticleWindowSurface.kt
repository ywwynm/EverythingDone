package com.ywwynm.everythingdone.views.particledismiss

import android.app.Activity
import android.content.Context
import android.content.res.AssetManager
import android.graphics.PixelFormat
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Build
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
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
 * 一个宿主窗口只有一个粒子 Surface（1.4）。
 *
 * `setZOrderOnTop(true)` 下 SurfaceView 不打洞（`isAboveParent()` 恒真），表面被 parent 在
 * Activity 窗口的 layer 内、仍在 Dialog 等独立窗口之下——这正是现有「先就位、后揭开」顺序
 * 需要的。同窗口内两个都 on-top 的 SurfaceView 相对顺序未定义，所以**只能有一个**：窗口内
 * 所有并发会话（上一个弹窗消散 + 下一个弹窗出现 + 左滑卡片）按创建顺序叠画进同一个 Surface，
 * 一拍一次交换。
 *
 * 表面首次需要时创建并留在窗口上，直到 Activity 销毁或配置变化——每次动画重建表面要等
 * 首帧，那正是要省掉的开销。没有会话时不提交新帧，最后一帧保持全透明。
 */
internal class ParticleWindowSurface private constructor(
    private val activity: Activity,
    private val decor: ViewGroup
) : SurfaceHolder.Callback {

    val view = ParticleOverlaySurfaceView(activity).apply {
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
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
    private val sessions = ArrayList<ParticleSession>()
    private val incoming = ConcurrentLinkedQueue<ParticleSession>()
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var boundSurface: Surface? = null
    private var timestamps = false
    private var fence = 0L
    private var surfaceDirty = false
    private var recomposite = false
    private val pending = ArrayDeque<Frame>()
    private val assets: AssetManager =
        activity.applicationContext.assets

    var lastEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        private set

    private class Frame(val frameId: Long, val target: Long, val submittedAt: Long,
                        val drawn: List<ParticleSession>)

    private val attachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) { dispose() }
    }

    init {
        view.holder.addCallback(this)
        view.addOnAttachStateChangeListener(attachment)
        decor.addView(view, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        ParticleGl.register(this)
    }

    val busy get() = sessions.isNotEmpty()

    fun add(session: ParticleSession) {
        incoming += session
        ParticleGl.wake()
    }

    // ---- SurfaceHolder 回调（主线程） ----

    override fun surfaceCreated(holder: SurfaceHolder) {
        surface = holder.surface
        applyFrameRate(holder.surface)
        ParticleGl.post { prime() }
        ParticleGl.wake()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surface = holder.surface
        surfaceWidth = width
        surfaceHeight = height
        applyFrameRate(holder.surface)
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
     * 1.2：不再改任何窗口属性，只在自己的 Surface 上投一次无缝 60 Hz。
     * 120 Hz 面板上 SurfaceFlinger 会留在 120（60 的倍数），节拍仍每隔一拍提交一次，
     * 每帧的 `eglPresentationTimeANDROID` 负责钉住呈现时刻。
     */
    private fun applyFrameRate(target: Surface) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                target.setFrameRate(60f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                    Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                target.setFrameRate(60f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
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
        if (primed || !ParticlePerfTuning.primeParticleSurface) return
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
        val shared = ParticleGl.ensureShared(assets, width, height,
            allowResize = sessions.none { it.started }) ?: run { failAll(); return false }
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
        if (sessions.any { it.wantsFrame() }) return true
        // 有会话退出或改变了可见性时要再交换一次，否则它的最后一帧会留在 Surface 上；
        // 全部退完之后那一次把 Surface 抹成全透明。
        return surfaceDirty && (recomposite || sessions.none { it.visible && !it.cancelled })
    }

    fun needsWork(): Boolean = incoming.isNotEmpty() || sessions.any { it.needsWork() }

    val awaitingPresentation get() = pending.isNotEmpty()

    fun composite(target: Long) = ParticleTrace.section("particle-composite") { compositeOnce(target) }

    private fun compositeOnce(target: Long) {
        intake()
        purge()
        if (!needsFrame()) return
        if (!ensureEglSurface()) return
        if (!ParticleGl.bind(eglSurface)) { failAll(); return }
        awaitFence()
        val width = targetWidth()
        val height = targetHeight()
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val drawn = ArrayList<ParticleSession>(sessions.size)
        // 创建顺序即叠放顺序：先创建的在下。
        for (session in sessions.toList()) if (session.drawInto(target)) drawn += session
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
        surfaceDirty = drawn.isNotEmpty()
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
            val presentTime = if (head.frameId >= 0 && now - head.submittedAt <= PRESENT_TIMEOUT_NANOS)
                ParticleFrameTimestamps.presentTime(ParticleGl.display, eglSurface, head.frameId) else null
            if (!shouldRelease(now - head.submittedAt, presentTime)) return
            releaseFrame(pending.removeFirst(), presentTime)
        }
    }

    /**
     * [presentTime] 是驱动报的真实呈现时刻（PENDING / INVALID / 无时间戳时为 null）。
     * 它与「我们什么时候问出来」是两件事，3b 的空档归因必须把两者分开看。
     */
    private fun releaseFrame(frame: Frame, presentTime: Long? = null) {
        val displayed = presentTime?.takeIf { it > 0 && it != ParticleFrameTimestamps.PENDING }
        frame.drawn.forEach { runCatching { it.framePresented(frame.target, displayed) } }
    }

    private fun purge() {
        val finished = sessions.filter { it.cancelled }
        if (finished.isEmpty()) return
        sessions.removeAll(finished.toSet())
        finished.forEach { session -> runCatching { session.dispose() } }
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
        timestamps = ParticleFrameTimestamps.enable(ParticleGl.display, created)
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
        synchronized(hosts) { if (hosts[activity]?.get() === this) hosts.remove(activity) }
        view.removeOnAttachStateChangeListener(attachment)
        ParticleGl.unregister(this)
    }

    companion object {
        /**
         * 值必须是弱引用：本对象强引用 Activity，直接放进 WeakHashMap 会让「值经由静态根
         * 强引用键」，条目永远回收不掉，等于泄漏整个 Activity。
         */
        private val hosts = WeakHashMap<Activity, WeakReference<ParticleWindowSurface>>()

        /** 每个宿主窗口一个；首次需要时创建。必须在主线程调用。 */
        fun of(activity: Activity): ParticleWindowSurface? {
            val decor = activity.window?.decorView as? ViewGroup ?: return null
            if (!decor.isAttachedToWindow || activity.isDestroyed) return null
            synchronized(hosts) {
                hosts[activity]?.get()?.let { if (!it.detached) return it }
                hosts.remove(activity)
                return runCatching { ParticleWindowSurface(activity, decor) }.getOrNull()
                    ?.also { hosts[activity] = WeakReference(it) }
            }
        }

        /** 最多保留的未确认帧；再多说明呈现时间戳一直没回来，按提交顺序强制放行。 */
        private const val MAX_PENDING_FRAMES = 6

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
