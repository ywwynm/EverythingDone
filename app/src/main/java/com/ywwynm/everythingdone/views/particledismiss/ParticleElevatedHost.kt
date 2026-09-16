package com.ywwynm.everythingdone.views.particledismiss

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * 需要盖住别的弹窗时，粒子改挂到这个承载窗口上。
 *
 * ## 为什么必须是一个独立窗口
 *
 * 粒子表面是挂在 Activity DecorView 里的 SurfaceView（`setZOrderOnTop(true)`），它在 Activity
 * 窗口之内最上，但**仍在 Dialog 这种独立窗口之下**。而本项目的弹窗主题是 `windowIsFloating`、
 * 按内容尺寸布局，所以「把粒子挂进下层弹窗自己的窗口」会被那个弹窗的矩形裁掉——粒子本来要飞出
 * 卡片一个身位。只有另起一个**全屏**、且在那些弹窗**之后**加入的窗口才同时满足「在上面」和
 * 「不裁剪」。
 *
 * ## 这个窗口不参与交互
 *
 * 不可聚焦、不可触摸、不占焦点、不带窗口动画、没有系统 dim、背景全透明：它只是一块画布。
 * 焦点仍留在真正的弹窗上，返回键与点击照旧穿过去。
 *
 * ## 生命周期
 *
 * 按需显示，空闲即撤。Activity 那张常驻表面是「建起来就留着」的（省掉每次动画等首帧），
 * 这一张不能照搬：它一直挂着就会永远压在所有弹窗之上。代价是这条路每次要等一次表面创建，
 * 只发生在弹窗叠弹窗这种少数场合。
 */
internal object ParticleElevatedHost {

    private val main = Handler(Looper.getMainLooper())
    private val hosts = java.util.WeakHashMap<Activity, Holder>()

    private class Holder(val dialog: Dialog, val content: ViewGroup) {
        /** 还在用这块画布的会话数；归零且弹窗栈空了才撤窗口。 */
        var users = 0
        var idleCheck: Runnable? = null
    }

    /**
     * 取一块「在所有弹窗之上」的全屏画布。调用方用完必须 [release]。
     * 窗口起不来时返回 null，调用方退回 Activity 的常驻表面（层级仍是旧的，但动画照播）。
     */
    fun acquire(activity: Activity): ViewGroup? {
        if (activity.isFinishing || activity.isDestroyed) return null
        val holder = hosts[activity]?.takeIf { it.dialog.isShowing } ?: create(activity) ?: return null
        holder.users++
        holder.idleCheck?.let { main.removeCallbacks(it); holder.idleCheck = null }
        return holder.content
    }

    /** 与 [acquire] 一一对应。最后一个用完之后延后一拍再判断是否撤窗口。 */
    fun release(activity: Activity) {
        val holder = hosts[activity] ?: return
        holder.users = (holder.users - 1).coerceAtLeast(0)
        if (holder.users > 0) return
        val check = Runnable {
            val current = hosts[activity] ?: return@Runnable
            // 连续两个弹窗接力时中间会短暂归零，别把窗口撤了又建。
            if (current.users > 0) return@Runnable
            // 叠加状态还在（上面那个弹窗迟早要关）就留着：窗口一撤，下一次 show 之后
            // decor 要等下一次遍历才附着，而消散是同步判定的，来不及。
            if (ParticleDialogStack.stackedCount(activity) >= 2) return@Runnable
            dismiss(activity, current)
        }
        holder.idleCheck = check
        main.postDelayed(check, IDLE_MS)
    }

    /** Activity 退到后台、配置变化等场合直接收掉。 */
    fun discard(activity: Activity) {
        val holder = hosts[activity] ?: return
        dismiss(activity, holder)
    }

    private fun dismiss(activity: Activity, holder: Holder) {
        holder.idleCheck?.let { main.removeCallbacks(it) }
        hosts.remove(activity)
        runCatching { holder.dialog.dismiss() }
    }

    private fun create(activity: Activity): Holder? {
        val content = FrameLayout(activity).apply { setBackgroundColor(Color.TRANSPARENT) }
        val dialog = object : Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar) {}
        val window = dialog.window ?: return null
        dialog.setContentView(content, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        // **必须显式给窗口格式**：主题里的 `windowIsTranslucent` 不会替 Dialog 设置它，
        // 窗口默认是 OPAQUE，整块全屏画布就会涂成黑的把下面全盖住（2026-09-16 实测黑屏，
        // `dumpsys window` 里那条 `mAttrs` 没有 `fmt=` 即为 OPAQUE）。
        window.setFormat(PixelFormat.TRANSLUCENT)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        window.setWindowAnimations(0)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        window.decorView.apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val shown = runCatching { dialog.show() }.isSuccess
        if (!shown) return null
        // Activity 的 decor 从窗口上摘下来（销毁、配置变化）时把这个窗口一并收掉，
        // 否则会留下「has leaked window」。
        (activity.window?.decorView)?.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) {
                    v.removeOnAttachStateChangeListener(this)
                    discard(activity)
                }
            })
        // show 之后再投一次：部分 ROM 会在 show 时按主题重置尺寸。
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        return Holder(dialog, content).also { hosts[activity] = it }
    }

    /** 空闲多久之后撤窗口：连续两个弹窗接力时中间的空档远小于这个值。 */
    private const val IDLE_MS = 400L
}
