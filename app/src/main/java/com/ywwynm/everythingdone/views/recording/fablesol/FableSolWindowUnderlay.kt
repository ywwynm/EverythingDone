package com.ywwynm.everythingdone.views.recording.fablesol

import android.annotation.TargetApi
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.os.IBinder
import android.view.AttachedSurfaceControl
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.helpers.DebugFileLogger
import com.ywwynm.everythingdone.views.particledismiss.ParticleTransparentSurfaceContainer
import org.json.JSONObject

/**
 * 仅卓易通使用的窗口下层承载。媒体子窗口由系统保证在原 Dialog 后方，
 * 普通透明 View 只提供根 Surface；不创建 SurfaceView、背景层或第二份控件。
 * 输入与 C 系统暗层仍由原 Dialog 管理，窗口随原宿主附着／释放。
 */
@TargetApi(34)
internal class FableSolWindowUnderlay(context: Context) : View(context),
    ParticleTransparentSurfaceContainer {
    var onContainerChanged: (() -> Unit)? = null
    var generation = 0L
        private set
    var failure: String? = null
        private set
    private val manager = context.getSystemService(WindowManager::class.java)
    private var media: MediaRoot? = null
    private var params: WindowManager.LayoutParams? = null
    private var parentToken: IBinder? = null
    private var closed = false
    private var pendingRemoval = false
    private var removalIssue: String? = null

    val control: AttachedSurfaceControl?
        get() = media?.takeIf { it.isAttachedToWindow && it.drawn }?.rootSurfaceControl

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isClickable = false
        isFocusable = false
        // 占位 View 不绘制、不挖孔，也不产生粒子图像。
        setWillNotDraw(true)
    }

    private inner class MediaRoot : View(context) {
        var drawn = false
        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            isFocusable = false
            isClickable = false
        }
        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            if (!drawn) {
                drawn = true
                post {
                    if (media === this) {
                        record("media_window_ready")
                        onContainerChanged?.invoke()
                    }
                }
            }
        }
        override fun onDetachedFromWindow() {
            drawn = false
            super.onDetachedFromWindow()
            if (media === this) {
                media = null
                params = null
                parentToken = null
                generation++
                record("media_window_detached_by_system")
                this@FableSolWindowUnderlay.post {
                    onContainerChanged?.invoke()
                    synchronizeWindow()
                }
            }
        }
    }

    /** 原窗口 preDraw 中调用；窗口尺寸只跟随 Dialog，不随海浪逐帧重建。 */
    fun synchronizeWindow() {
        if (closed || pendingRemoval || failure != null || !isAttachedToWindow ||
            visibility != VISIBLE || windowVisibility != VISIBLE ||
            rootView.width <= 0 || rootView.height <= 0) return
        val token = windowToken ?: return
        val old = params
        if (media != null && parentToken !== token) {
            removeWindow()
            return
        }
        try {
            if (media == null) {
                val attributes = WindowManager.LayoutParams(
                    rootView.width, rootView.height,
                    WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    this.token = token
                    gravity = Gravity.TOP or Gravity.LEFT
                    x = 0; y = 0
                    setFitInsetsTypes(0)
                    softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                    windowAnimations = 0
                    dimAmount = 0f
                    title = "FableSolMediaUnderlay"
                }
                val root = MediaRoot()
                media = root
                params = attributes
                parentToken = token
                generation++
                manager.addView(root, attributes)
                record("media_window_added")
                postDelayed({
                    if (media === root && !closed && isAttachedToWindow &&
                        windowVisibility == VISIBLE && control == null) {
                        failure = "media_window_first_draw_timeout"
                        record("media_window_failed")
                        onContainerChanged?.invoke()
                    }
                }, 6000L)
            } else if (old != null && (old.width != rootView.width || old.height != rootView.height)) {
                old.width = rootView.width
                old.height = rootView.height
                manager.updateViewLayout(media, old)
                record("media_window_resized")
            }
        } catch (error: Exception) {
            failure = error.toString()
            record("media_window_failed")
            removeWindow()
            post { onContainerChanged?.invoke() }
        }
    }

    /** 媒体窗口可能有独立的 Surface 内边距，不能直接复用原窗口坐标。 */
    fun positionOf(source: View): IntArray {
        val root = media ?: return intArrayOf(0, 0)
        val sourceScreen = IntArray(2).also(source::getLocationOnScreen)
        val rootScreen = IntArray(2).also(root::getLocationOnScreen)
        val rootSurface = IntArray(2).also(root::getLocationInSurface)
        return intArrayOf(sourceScreen[0] - rootScreen[0] + rootSurface[0],
            sourceScreen[1] - rootScreen[1] + rootSurface[1])
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        closed = false
        post { synchronizeWindow() }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (!isAttachedToWindow) return
        if (visibility != VISIBLE) removeWindow() else post { synchronizeWindow() }
    }

    override fun onDetachedFromWindow() {
        close()
        super.onDetachedFromWindow()
    }

    fun close() {
        closed = true
        removeWindow()
    }

    private fun removeWindow() {
        val root = media ?: return
        media = null
        params = null
        parentToken = null
        generation++
        pendingRemoval = true
        record("media_window_remove_requested")
        // 避免在原窗口绘制／分离回调中同步销毁另一个 ViewRoot。
        handler?.post {
            try { manager.removeViewImmediate(root) }
            catch (error: Exception) {
                // 系统可能已随父窗口销毁子窗口；记录结果，但不阻止下一次附着。
                removalIssue = error.toString()
            } finally {
                pendingRemoval = false
                record("media_window_removed")
                onContainerChanged?.invoke()
                if (!closed) synchronizeWindow()
            }
        } ?: run {
            try { manager.removeViewImmediate(root) }
            catch (error: Exception) { removalIssue = error.toString() }
            pendingRemoval = false
            record("media_window_removed")
        }
    }

    fun diagnostics(): JSONObject = JSONObject()
        .put("type", "TYPE_APPLICATION_MEDIA").put("surfaceViewCreated", false)
        .put("generation", generation).put("failure", failure)
        .put("attached", media?.isAttachedToWindow == true).put("drawn", media?.drawn == true)
        .put("parentToken", parentToken?.let { System.identityHashCode(it) })
        .put("windowSize", params?.let { "${it.width}x${it.height}" })
        .put("rootSize", media?.let { "${it.width}x${it.height}" })
        .put("rootScreen", media?.let { IntArray(2).also(it::getLocationOnScreen).joinToString(",") })
        .put("parentScreen", IntArray(2).also(rootView::getLocationOnScreen).joinToString(","))
        .put("flags", params?.flags).put("dimAmount", params?.dimAmount)
        .put("closed", closed).put("removing", pendingRemoval)
        .put("removalIssue", removalIssue)

    private fun record(event: String) {
        if (!BuildConfig.DEBUG) return
        DebugFileLogger.log("fablesol_direct.log", diagnostics().put("event", event)
            .put("debugUpdateCode", context.getString(com.ywwynm.everythingdone.R.string.debug_update_code))
            .put("underlay", System.identityHashCode(this)).put("window", System.identityHashCode(rootView))
            .toString(), "[DEBUG-FABLESOL-DIRECT]")
    }
}
