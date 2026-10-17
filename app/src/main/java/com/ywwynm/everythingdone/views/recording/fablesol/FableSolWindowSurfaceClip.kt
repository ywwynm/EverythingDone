package com.ywwynm.everythingdone.views.recording.fablesol

import android.graphics.Outline
import android.graphics.Rect
import android.view.SurfaceControl
import android.view.SurfaceView
import android.view.View
import androidx.annotation.RequiresApi
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.helpers.DebugFileLogger

/**
 * 仅卓易通音频窗口使用：shader 的透明角不一定能裁掉合成器下层的黑色矩形。
 * 尝试把整个 Dialog 的圆角同时施加给独立 Surface 容器；设置预览只占窗口上部，
 * 因此 crop 仍取整个窗口，不能误把预览底部也裁成圆角。
 * setCornerRadius 不是公开 SDK；不豁免系统限制，拒绝时记录并保留原有 shader／View 裁剪。
 */
@RequiresApi(34)
internal class FableSolWindowSurfaceClip(private val view: SurfaceView) {
    private var root: View? = null
    private var unavailable = false
    private var queued = false
    private var lastSkip: String? = null
    private val layout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> request() }
    private val apply = Runnable { queued = false; applyClip() }

    fun attach() {
        root = view.rootView.also { it.addOnLayoutChangeListener(layout) }
        request()
    }

    fun detach() {
        root?.removeOnLayoutChangeListener(layout)
        root = null
        view.removeCallbacks(apply)
        queued = false
    }

    fun request() {
        if (queued || unavailable || !view.isAttachedToWindow) return
        queued = true
        view.post(apply)
    }

    private fun applyClip() {
        val decor = root ?: return skipped("no_root")
        if (!decor.isAttachedToWindow) return skipped("root_detached")
        // 显式窗口清角不再依赖原生 SurfaceControl 圆角；记录跳过，不能误称请求被拒绝。
        if (!decor.clipToOutline) return skipped("decor_clip_disabled")
        if (!view.holder.surface.isValid) return skipped("surface_invalid")
        val outline = Outline().apply { decor.outlineProvider?.getOutline(decor, this) }
        val bounds = Rect()
        if (!outline.getRect(bounds) || outline.radius <= 0f) return skipped("no_round_outline")
        val windowAt = IntArray(2).also(decor::getLocationOnScreen)
        val surfaceAt = IntArray(2).also(view::getLocationOnScreen)
        val frame = view.holder.surfaceFrame
        val clip = FableSolWindowClipGeometry.of(
            windowAt[0] + bounds.left, windowAt[1] + bounds.top,
            windowAt[0] + bounds.right, windowAt[1] + bounds.bottom,
            surfaceAt[0], surfaceAt[1], view.width, view.height, frame.width(), frame.height(),
            outline.radius) ?: return skipped("invalid_geometry")
        val control = view.surfaceControl
        if (!control.isValid) return skipped("control_invalid")
        lastSkip = null
        try {
            // 查找一次公开签名，不调用 setAccessible 或隐藏 API 豁免。
            val corner = setCornerRadius ?: error("native_corner_api_unavailable")
            SurfaceControl.Transaction().use { transaction ->
                corner.invoke(transaction, control, clip.radius)
                transaction.setCrop(control, Rect(clip.left, clip.top, clip.right, clip.bottom))
                val queuedOnDraw = decor.rootSurfaceControl?.applyTransactionOnDraw(transaction) == true
                if (!queuedOnDraw) transaction.apply()
                decor.invalidate()
                record("requested", "clip=$clip queuedOnDraw=$queuedOnDraw")
            }
        } catch (failure: Exception) {
            unavailable = true
            record("unavailable", "reason=${failure.javaClass.simpleName}:${failure.message}")
        }
    }

    private fun skipped(reason: String) {
        if (reason == lastSkip) return
        lastSkip = reason
        record("skipped", "reason=$reason")
    }

    private fun record(event: String, detail: String) {
        if (BuildConfig.DEBUG) DebugFileLogger.log("fablesol_dialog_dim.log",
            "event=native_window_corner_$event surface=${System.identityHashCode(view)} $detail",
            "[DEBUG-fablesol-system-dim]")
    }

    companion object {
        private val setCornerRadius by lazy {
            runCatching { SurfaceControl.Transaction::class.java.getMethod("setCornerRadius",
                SurfaceControl::class.java, Float::class.javaPrimitiveType) }.getOrNull()
        }
    }
}
