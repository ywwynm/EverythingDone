package com.ywwynm.everythingdone.views.particledismiss

import android.animation.LayoutTransition
import android.graphics.Bitmap
import android.os.Build
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import com.ywwynm.everythingdone.BuildConfig

/** 独立渲染内容的临时冻结不覆盖录音、播放、导出各自的暂停状态。 */
internal interface ParticleSnapshotParticipant {
    fun holdParticleSnapshot(): () -> Unit
}

/**
 * 仅 debug：记下最近一次抓图走的是哪条路径、终止在哪一步，供「消散未开播：快照为空」
 * 那一行日志取用。抓图一律由主线程发起、回调也回到主线程，同一时刻至多一次，所以
 * 单份记录足够；release 包里既不写也不读，抓图本身的行为一个字节都不变。
 */
internal object ParticleCaptureDiagnostics {

    @Volatile var route: String = "未开始"
    @Volatile var failure: String? = null

    /** 进入某条路径：记下路径并清掉上一次的失败原因。 */
    fun begin(value: String) {
        if (!BuildConfig.DEBUG) return
        route = value
        failure = null
        Log.i(ParticleMicroflakeRenderer.TAG, "抓图路径：$value")
    }

    fun note(reason: String) {
        if (BuildConfig.DEBUG) failure = reason
    }

    /** 外层只在内层没有记下更精确的原因时才补一条。 */
    fun noteIfAbsent(reason: String) {
        if (BuildConfig.DEBUG && failure == null) failure = reason
    }

    fun describe(): String = "路径=$route 原因=${failure ?: "未记录"}"
}

/**
 * 按弹窗圆角裁过的快照遮盖层。用 `clipPath` 而不是往位图上画遮罩：那张位图同时给
 * 粒子建材用，不能就地改；复制一份整屏 ARGB_8888 又是十几 MB。
 */
private class RoundedBitmapCover(
    private val bitmap: Bitmap, private val radiusPx: Float
) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        if (bitmap.isRecycled) return
        val b = bounds
        rect.set(b)
        path.reset()
        path.addRoundRect(rect, radiusPx, radiusPx, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(path)
        canvas.drawBitmap(bitmap, null, b, paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Drawable 抽象成员，按平台约定返回半透明。")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** 窗口的普通控件与窗口下方的独立 Surface 分别捕获，再按原合成顺序拼合。 */
internal object ParticleSnapshot {
    private val handler = Handler(Looper.getMainLooper())

    fun hasVisibleSurface(root: View): Boolean {
        var found = false
        visit(root) { if (ParticleSurfaceCopy.isSource(it) && it.width > 0 && it.height > 0 && it.getGlobalVisibleRect(Rect())) found = true }
        return found
    }

    /**
     * Activity 的快照位于 Dialog 窗口下方，无法遮住该窗口移除 Surface 时留下的洞。
     * 在原窗口也保留同一张合成图，直到窗口退出；不能只等下方的粒子层上屏。
     * 使用 ViewOverlay，不参与测量，也不隐藏或重建仍在显示的 Surface。
     *
     * [cornerRadiusPx] 是弹窗的圆角半径，只有在圆角遮罩还没画进 [snapshot] 时才需要传
     * （`ParticlePerfTuning.maskOnMaterialThread` 为真时就是这样：遮罩在材料线程上、
     * 对着粒子那一份做，盖在真实弹窗上的这一份是没裁角的）。不裁的话，只要合成图四角
     * 不是恰好透明，真实弹窗就会在交接期变成直角。
     */
    fun coverSurfaces(root: View, snapshot: Bitmap, cornerRadiusPx: Float = 0f): (() -> Unit)? {
        if (!hasVisibleSurface(root)) return null
        val cover = if (cornerRadiusPx > 0f) {
            RoundedBitmapCover(snapshot, cornerRadiusPx).apply { setBounds(0, 0, root.width, root.height) }
        } else {
            BitmapDrawable(root.resources, snapshot).apply {
                setBounds(0, 0, root.width, root.height)
                isFilterBitmap = false
            }
        }
        root.overlay.add(cover)
        var released = false
        return {
            if (!released) {
                released = true
                root.overlay.remove(cover)
            }
        }
    }

    class Hold(private val releases: List<() -> Unit>,
               private val transitions: List<Pair<ViewGroup, LayoutTransition>>) : () -> Unit {
        private var released = false
        fun settleLayout(): Boolean {
            var changed = false
            // 移除 LayoutTransition 不会清除它尚未收到布局结果的监听器。
            // 首次布局后再取消这些新启动的旧过渡，下一次布局才能固定最终边界。
            for ((view, transition) in transitions) if (transition.isRunning && view.layoutTransition == null) {
                view.layoutTransition = transition
                view.layoutTransition = null
                view.requestLayout()
                changed = true
            }
            return !changed
        }
        override fun invoke() {
            if (released) return
            released = true
            releases.asReversed().forEach { it() }
        }
    }

    fun hold(root: View): Hold {
        val releases = mutableListOf<() -> Unit>()
        val transitions = mutableListOf<Pair<ViewGroup, LayoutTransition>>()
        // LayoutTransition 会继续绘制已 GONE 的旧页，且给新页留下临时 alpha。
        // 先结束页内过渡再捕获最终内容；交接后恢复正常的页内布局动画。
        visit(root) { view ->
            if (view is ViewGroup) view.layoutTransition?.let { transition ->
                transitions += view to transition
                view.layoutTransition = null
                view.requestLayout()
                releases += { if (view.layoutTransition == null) view.layoutTransition = transition }
            }
        }
        visit(root) { if (it is ParticleSnapshotParticipant) releases += it.holdParticleSnapshot() }
        return Hold(releases, transitions)
    }

    private fun visit(view: View, action: (View) -> Unit) {
        if (view.visibility != View.VISIBLE) return
        // 粒子自己那一层 SurfaceView 常驻在宿主 DecorView 上（一窗一 Surface）。它既不参与
        // 快照内容，也不能被当成「窗口下方的独立 Surface」去 PixelCopy——那会在它还没提交
        // 过缓冲时反复 ERROR_SOURCE_NO_DATA，把整次捕获拖到超时。
        if (view is ParticleOverlaySurfaceView) return
        action(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i), action)
    }

    /**
     * [dismissal] 表示这是消散：一律先离屏（见 [ParticleCaptureRoute.preferOffscreen]）。
     * [forceOffscreen] 由「窗口 PixelCopy 已经失败过一次」的回退传 true。
     */
    fun capture(window: Window, root: View, offscreen: (() -> Bitmap?)? = null,
                asyncOffscreen: (((Bitmap?) -> Unit) -> Unit)? = null,
                dismissal: Boolean = false, forceOffscreen: Boolean = false,
                done: (Bitmap?) -> Unit) {
        // 统一硬件捕获已经按控件原层级合成独立生产者，不能再把同一缓冲叠一次。
        if (Build.VERSION.SDK_INT >= 29 && ParticlePresentationPolicy.of(root).unified && asyncOffscreen != null) {
            ParticleCaptureDiagnostics.begin("硬件层级捕获与独立表面")
            asyncOffscreen(done)
            return
        }
        val surfaces = mutableListOf<View>()
        visit(root) { if (ParticleSurfaceCopy.isSource(it) && it.width > 0 && it.height > 0 && it.getGlobalVisibleRect(Rect())) surfaces += it }
        // 同属窗口下方的 Surface 按前到后合成，避免后画的底层盖住高层。
        surfaces.reverse()
        val started = SystemClock.uptimeMillis()
        var finished = false
        // 只记录终止分支，重试分支不记——否则最后留下的是「还能再试一次」的中间状态。
        fun failed(reason: String) {
            if (!finished) ParticleCaptureDiagnostics.note(reason)
        }
        val timeout = Runnable {
            if (!finished) { failed("整体超时 900ms"); finished = true; done(null) }
        }
        val complete: (Bitmap?) -> Unit = { bitmap ->
            if (finished) bitmap?.recycle() else {
                finished = true; handler.removeCallbacks(timeout); done(bitmap)
            }
        }
        fun copySurfaces(base: Bitmap, index: Int) {
            if (finished) { base.recycle(); return }
            if (index == surfaces.size) { complete(base); return }
            val surface = surfaces[index]
            val buffer = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
            fun request() {
                if (finished) { buffer.recycle(); base.recycle(); return }
                if (!root.isAttachedToWindow || !surface.isAttachedToWindow) {
                    failed("独立 Surface 抓取前已 detach")
                    buffer.recycle(); base.recycle(); complete(null); return
                }
                try {
                    ParticleSurfaceCopy.request(surface, buffer, { status ->
                        if (finished) { buffer.recycle(); base.recycle() }
                        else if (status == PixelCopy.SUCCESS) {
                            val rootAt = IntArray(2); root.getLocationOnScreen(rootAt)
                            val at = IntArray(2); surface.getLocationOnScreen(at)
                            val clip = Rect()
                            if (surface.getGlobalVisibleRect(clip)) {
                                // getGlobalVisibleRect 的 global 是 ViewRoot 坐标，仍需补窗口在屏幕上的偏移。
                                val windowAt = IntArray(2); surface.rootView.getLocationOnScreen(windowAt)
                                clip.offset(windowAt[0] - rootAt[0], windowAt[1] - rootAt[1])
                                val canvas = Canvas(base)
                                canvas.save(); canvas.clipRect(clip)
                                // SurfaceView 在窗口缓冲中留下透明洞；文字、按钮及半透明遮罩仍在其上。
                                canvas.drawBitmap(buffer, (at[0] - rootAt[0]).toFloat(), (at[1] - rootAt[1]).toFloat(),
                                    Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OVER) })
                                canvas.restore()
                            }
                            buffer.recycle(); copySurfaces(base, index + 1)
                        } else if (status == PixelCopy.ERROR_SOURCE_NO_DATA && SystemClock.uptimeMillis() - started < 700) {
                            handler.postDelayed({ request() }, 16)
                        } else {
                            failed("独立 Surface PixelCopy 状态=$status 等待=${SystemClock.uptimeMillis() - started}ms")
                            buffer.recycle(); base.recycle(); complete(null)
                        }
                    }, handler)
                } catch (error: Exception) {
                    if (SystemClock.uptimeMillis() - started < 700 && root.isAttachedToWindow) handler.postDelayed({ request() }, 16)
                    else {
                        failed("独立 Surface PixelCopy 异常=${error.javaClass.simpleName}")
                        buffer.recycle(); base.recycle(); complete(null)
                    }
                }
            }
            request()
        }
        // 回调缺席也要恢复真实内容。正在写入的缓冲仅由迟到回调回收。
        handler.postDelayed(timeout, 900)
        val preferOffscreen = ParticleCaptureRoute.preferOffscreen(
            surfaces.isNotEmpty(), dismissal = dismissal, forced = forceOffscreen)
        if (asyncOffscreen != null && preferOffscreen) {
            ParticleCaptureDiagnostics.begin(
                if (surfaces.isEmpty()) "离屏异步" else "离屏异步+独立Surface×${surfaces.size}")
            asyncOffscreen { base ->
                if (base == null) {
                    // 离屏那一层已经记下更精确的原因（syncAndDraw / fence / 超时），不要盖掉。
                    ParticleCaptureDiagnostics.noteIfAbsent("离屏异步返回空")
                    complete(null)
                } else copySurfaces(base, 0)
            }
            return
        }
        // 普通 View 保留原有离屏捕获；独立 Surface 需要窗口里的真实透明洞才能正确合成。
        if (offscreen != null && preferOffscreen) {
            ParticleCaptureDiagnostics.begin(
                if (surfaces.isEmpty()) "离屏同步" else "离屏同步+独立Surface×${surfaces.size}")
            val base = offscreen()
            if (base == null) {
                ParticleCaptureDiagnostics.noteIfAbsent("离屏同步返回空")
                complete(null)
            } else copySurfaces(base, 0)
            return
        }
        ParticleCaptureDiagnostics.begin("窗口PixelCopy" + if (surfaces.isEmpty()) "" else "+独立Surface×${surfaces.size}")
        val base = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val at = IntArray(2); root.getLocationInWindow(at)
        fun requestWindow() {
            if (finished) { base.recycle(); return }
            try {
                PixelCopy.request(window, Rect(at[0], at[1], at[0] + root.width, at[1] + root.height), base, { status ->
                    if (finished) base.recycle()
                    else if (status == PixelCopy.SUCCESS) copySurfaces(base, 0)
                    else if (status == PixelCopy.ERROR_SOURCE_NO_DATA && root.isAttachedToWindow &&
                        SystemClock.uptimeMillis() - started < WINDOW_RETRY_MS) handler.postDelayed({ requestWindow() }, 16)
                    else {
                        failed("窗口 PixelCopy 状态=$status 附着=${root.isAttachedToWindow} " +
                            "等待=${SystemClock.uptimeMillis() - started}ms")
                        base.recycle(); complete(null)
                    }
                }, handler)
            } catch (error: Exception) {
                failed("窗口 PixelCopy 异常=${error.javaClass.simpleName}")
                base.recycle(); complete(null)
            }
        }
        requestWindow()
    }

    /** 窗口 `PixelCopy` 收到 `ERROR_SOURCE_NO_DATA` 之后还肯重试多久。 */
    private const val WINDOW_RETRY_MS = 700L
}
