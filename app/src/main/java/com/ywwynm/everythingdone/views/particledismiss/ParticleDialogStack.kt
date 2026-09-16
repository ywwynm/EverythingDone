package com.ywwynm.everythingdone.views.particledismiss

import android.app.Activity
import android.app.Dialog

/**
 * 当前在屏上的弹窗栈，用来判断一个弹窗的粒子动画要不要抬到别的弹窗之上。
 *
 * 粒子画在 [ParticleWindowSurface] 上，而那张表面挂在 Activity 的 DecorView 里、
 * `setZOrderOnTop(true)`——它在 Activity 窗口之内最上，**但仍在 Dialog 这种独立窗口之下**。
 * 详情页的「改变颜色」面板本身就是一个 `BaseDialogFragment`，所以在它之上打开的「从世界取色」
 * 的粒子会被它盖住（2026-09-16 实测：粒子只出现在面板矩形之外）。首页的「调整外观」是页内
 * 面板而不是窗口，所以那边层级是对的。
 *
 * 判据只有一条：**这个弹窗下面还压着另一个会留下来的弹窗**。正在消散的弹窗不算——A 关闭紧接着
 * B 打开是最常见的连续操作，现有设计把并发会话合成进同一张表面、交接靠同一份帧时间戳，
 * 把 B 抬到另一个窗口就成了跨窗口交接（本项目记过「跨窗口交接必闪」）。
 */
internal object ParticleDialogStack {

    /** 在屏的弹窗，按 `onStart` 的先后排；[leaving] 标记消散已经开始、马上就不在了。 */
    private class Entry(val dialog: Dialog, val activity: Activity) {
        var leaving = false
    }

    private val entries = ArrayList<Entry>()

    /** `BaseDialogFragment.onStart` 里登记。 */
    fun onShown(dialog: Dialog, activity: Activity) {
        prune()
        if (entries.any { it.dialog === dialog }) return
        entries += Entry(dialog, activity)
    }

    /** 粒子消散已经接管：这个弹窗马上就不在了，不能再让别人把自己抬到它之上。 */
    fun onLeaving(dialog: Dialog) {
        entries.firstOrNull { it.dialog === dialog }?.leaving = true
    }

    /** 真实 dismiss 之后注销。 */
    fun onDismissed(dialog: Dialog) {
        entries.removeAll { it.dialog === dialog }
        prune()
    }

    /**
     * [dialog] 的粒子动画是否需要抬到别的弹窗之上——即同一个 Activity 里还有别的弹窗在屏上、
     * 且不是正在离场的。
     */
    fun needsElevation(activity: Activity, dialog: Dialog?): Boolean {
        prune()
        return entries.any { it.activity === activity && it.dialog !== dialog &&
            !it.leaving && it.dialog.isShowing }
    }

    /** 这个 Activity 里在屏的弹窗数——承载窗口能不能撤下来的依据。 */
    fun stackedCount(activity: Activity): Int {
        prune()
        return entries.count { it.activity === activity && it.dialog.isShowing }
    }

    private fun prune() {
        entries.removeAll { !it.dialog.isShowing || it.activity.isDestroyed }
    }
}
