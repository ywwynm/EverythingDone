package com.ywwynm.everythingdone.views.particledismiss

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.Window

/** 独立渲染内容的临时冻结不覆盖录音、播放、导出各自的暂停状态。 */
internal interface ParticleSnapshotParticipant {
    fun holdParticleSnapshot(): () -> Unit
}

/** 窗口的普通控件与窗口下方的独立 Surface 分别捕获，再按原合成顺序拼合。 */
internal object ParticleSnapshot {
    private val handler = Handler(Looper.getMainLooper())

    fun hasVisibleSurface(root: View): Boolean {
        var found = false
        visit(root) { if (it is SurfaceView && it.width > 0 && it.height > 0 && it.getGlobalVisibleRect(Rect())) found = true }
        return found
    }

    /**
     * Activity 的快照位于 Dialog 窗口下方，无法遮住该窗口移除 Surface 时留下的洞。
     * 在原窗口也保留同一张合成图，直到窗口退出；不能只等下方的粒子层上屏。
     * 使用 ViewOverlay，不参与测量，也不隐藏或重建仍在显示的 Surface。
     */
    fun coverSurfaces(root: View, snapshot: Bitmap): (() -> Unit)? {
        if (!hasVisibleSurface(root)) return null
        val cover = android.graphics.drawable.BitmapDrawable(root.resources, snapshot).apply {
            setBounds(0, 0, root.width, root.height)
            isFilterBitmap = false
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
               private val transitions: List<Pair<ViewGroup, android.animation.LayoutTransition>>) : () -> Unit {
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
        val transitions = mutableListOf<Pair<ViewGroup, android.animation.LayoutTransition>>()
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
        action(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i), action)
    }

    fun capture(window: Window, root: View, offscreen: (() -> Bitmap?)? = null,
                asyncOffscreen: (((Bitmap?) -> Unit) -> Unit)? = null, done: (Bitmap?) -> Unit) {
        val surfaces = mutableListOf<SurfaceView>()
        visit(root) { if (it is SurfaceView && it.width > 0 && it.height > 0 && it.getGlobalVisibleRect(Rect())) surfaces += it }
        // 同属窗口下方的 Surface 按前到后合成，避免后画的底层盖住高层。
        surfaces.reverse()
        val started = SystemClock.uptimeMillis()
        var finished = false
        val timeout = Runnable { if (!finished) { finished = true; done(null) } }
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
                    buffer.recycle(); base.recycle(); complete(null); return
                }
                try {
                    PixelCopy.request(surface, buffer, { status ->
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
                        } else { buffer.recycle(); base.recycle(); complete(null) }
                    }, handler)
                } catch (_: Exception) {
                    if (SystemClock.uptimeMillis() - started < 700 && root.isAttachedToWindow) handler.postDelayed({ request() }, 16)
                    else { buffer.recycle(); base.recycle(); complete(null) }
                }
            }
            request()
        }
        // 回调缺席也要恢复真实内容。正在写入的缓冲仅由迟到回调回收。
        handler.postDelayed(timeout, 900)
        if (asyncOffscreen != null && surfaces.isEmpty()) {
            asyncOffscreen { base -> if (base == null) complete(null) else copySurfaces(base, 0) }
            return
        }
        // 普通 View 保留原有离屏捕获；独立 Surface 需要窗口里的真实透明洞才能正确合成。
        if (offscreen != null && surfaces.isEmpty()) {
            val base = offscreen()
            if (base == null) complete(null) else copySurfaces(base, 0)
            return
        }
        val base = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val at = IntArray(2); root.getLocationInWindow(at)
        fun requestWindow() {
            if (finished) { base.recycle(); return }
            try {
                PixelCopy.request(window, Rect(at[0], at[1], at[0] + root.width, at[1] + root.height), base, { status ->
                    if (finished) base.recycle()
                    else if (status == PixelCopy.SUCCESS) copySurfaces(base, 0)
                    else if (status == PixelCopy.ERROR_SOURCE_NO_DATA && root.isAttachedToWindow &&
                        SystemClock.uptimeMillis() - started < 700) handler.postDelayed({ requestWindow() }, 16)
                    else { base.recycle(); complete(null) }
                }, handler)
            } catch (_: Exception) { base.recycle(); complete(null) }
        }
        requestWindow()
    }
}
