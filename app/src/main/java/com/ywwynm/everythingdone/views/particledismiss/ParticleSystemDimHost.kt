package com.ywwynm.everythingdone.views.particledismiss

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.helpers.DebugFileLogger

/** 系统暗层音频弹窗专用的粒子窗口；每次动画独占，避免新弹窗暗层压住复用的旧宿主。 */
internal class ParticleSystemDimHost private constructor(
    private val dialog: Dialog, val content: ViewGroup, private val activityDecor: View
) {
    private var closed = false
    private val attachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) { close() }
    }

    init {
        activityDecor.addOnAttachStateChangeListener(attachment)
        record("particle_host_created")
    }

    fun close() {
        if (closed) return
        closed = true
        activityDecor.removeOnAttachStateChangeListener(attachment)
        record("particle_host_closed")
        runCatching { dialog.dismiss() }
    }

    private fun record(event: String) {
        if (BuildConfig.DEBUG) DebugFileLogger.log("fablesol_dialog_dim.log",
            "event=$event host=${System.identityHashCode(dialog.window)} " +
                "attached=${content.isAttachedToWindow} flags=${dialog.window?.attributes?.flags}",
            "[DEBUG-fablesol-system-dim]")
    }

    companion object {
        fun create(activity: Activity): ParticleSystemDimHost? {
            if (activity.isFinishing || activity.isDestroyed || activity.isChangingConfigurations) return null
            val content = FrameLayout(activity).apply { setBackgroundColor(Color.TRANSPARENT) }
            val dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
            dialog.setContentView(content, ViewGroup.LayoutParams(-1, -1))
            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)
            val window = dialog.window ?: return null
            window.setFormat(PixelFormat.TRANSLUCENT)
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setWindowAnimations(0)
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            window.decorView.apply {
                setBackgroundColor(Color.TRANSPARENT)
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            window.setLayout(-1, -1)
            if (runCatching { dialog.show() }.isFailure) return null
            window.setLayout(-1, -1)
            return ParticleSystemDimHost(dialog, content, activity.window.decorView)
        }
    }
}
