package com.ywwynm.everythingdone.views.particledismiss

import android.app.Activity
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.annotation.RequiresApi

/** Doing 内容树的唯一绘制所有者；随 Activity 存活，不依赖全局调试工厂。 */
@RequiresApi(34)
internal class ParticleDoingPage(private val activity: Activity) : AutoCloseable {
    private val content = ParticleDoingSceneContent(activity)
    private val diagnostic = ParticlePresentationDiagnostics.of(activity)
    private var shared: ParticleWindowSurface? = null
    private var bootstrap: ViewTreeObserver.OnPreDrawListener? = null
    private var closed = false

    init {
        val host = activity.findViewById<ViewGroup>(android.R.id.content)
        while (host.childCount > 0) {
            val child = host.getChildAt(0)
            host.removeViewAt(0)
            content.addView(child)
        }
        host.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT))
        ParticleFrameTimestamps.preload()
        var started = 0L
        val listener = ViewTreeObserver.OnPreDrawListener {
            if (!closed && !activity.isFinishing) {
                // SurfaceView 到首次遍历才被请求；复杂内容的初始化耗时不能占用其等待期限。
                if (started == 0L) {
                    started = android.os.SystemClock.uptimeMillis()
                    diagnostic?.event("doing_scene_bootstrap_started")
                }
                val surfaceWindow = shared ?: ParticleWindowSurface.of(activity)?.also {
                    shared = it
                    it.pageContentActive = true
                }
                if (surfaceWindow != null && content.width > 0 && content.height > 0 &&
                    (surfaceWindow.view as? SurfaceView)?.holder?.surface?.isValid == true) {
                    removeBootstrap()
                    runCatching {
                        content.scene = ParticleDoingGlScene(activity, content, surfaceWindow, diagnostic, ::restoreNative)
                        content.scene?.start()
                        surfaceWindow.view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                            override fun onViewAttachedToWindow(v: View) = Unit
                            override fun onViewDetachedFromWindow(v: View) {
                                diagnostic?.event("doing_scene_window_detached")
                                v.removeOnAttachStateChangeListener(this)
                            }
                        })
                    }.onFailure {
                        diagnostic?.event("doing_scene_failed", mapOf("reason" to it.toString()))
                        restoreNative()
                    }
                } else if (android.os.SystemClock.uptimeMillis() - started > 2000L) {
                    diagnostic?.event("doing_scene_failed", mapOf("reason" to "bootstrap_surface_unavailable"))
                    restoreNative()
                } else content.postInvalidateOnAnimation()
            }
            true
        }
        bootstrap = listener
        content.viewTreeObserver.addOnPreDrawListener(listener)
    }

    private fun removeBootstrap() {
        bootstrap?.let { if (content.viewTreeObserver.isAlive) content.viewTreeObserver.removeOnPreDrawListener(it) }
        bootstrap = null
    }

    /** 失败恢复原树的绘制与输入，不以关闭正在计时的页面作为兜底。 */
    private fun restoreNative() {
        removeBootstrap()
        shared?.pageContentActive = false
        content.scene?.close()
        content.scene = null
        content.drawNormally = true
        content.invalidate()
    }

    fun beginExit() { removeBootstrap(); shared?.pageContentActive = false; content.scene?.beginExit() }
    fun setWindowVisible(visible: Boolean) { content.scene?.setWindowVisible(visible) }

    override fun close() {
        if (closed) return
        closed = true
        removeBootstrap()
        shared?.pageContentActive = false
        content.scene?.close()
        content.scene = null
    }

    companion object {
        fun contentOf(activity: Activity): ViewGroup? =
            (activity.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0) as? ParticleDoingSceneContent)
                ?.takeUnless { it.drawNormally }

        fun of(source: View): ParticleDoingGlScene? {
            var parent: View? = source
            while (parent != null) {
                if (parent is ParticleDoingSceneContent) return parent.scene
                parent = parent.parent as? View
            }
            return null
        }
    }
}
