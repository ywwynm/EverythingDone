package com.ywwynm.everythingdone.views.particledismiss

import android.os.Build
import android.view.Window
import java.util.WeakHashMap

/** 多个粒子层共享同一个窗口，最后一层退出后恢复窗口原有的刷新率策略。 */
internal object ParticleWindowFrameRate {
    private data class Vote(var count: Int, val rate: Float, val heldRate: Float, val balanced: Boolean)
    private val votes = WeakHashMap<Window, Vote>()

    fun hold(window: Window): () -> Unit {
        if (Build.VERSION.SDK_INT < 35) return {}
        val vote = votes[window] ?: Vote(0, window.attributes.preferredRefreshRate,
            maxOf(60f, window.attributes.preferredRefreshRate),
            window.isFrameRatePowerSavingsBalanced).also {
            votes[window] = it
            window.isFrameRatePowerSavingsBalanced = false
            window.attributes = window.attributes.apply { preferredRefreshRate = it.heldRate }
        }
        vote.count++
        var released = false
        return {
            if (!released) {
                released = true
                if (--vote.count == 0) {
                    votes.remove(window)
                    window.isFrameRatePowerSavingsBalanced = vote.balanced
                    // 期间若业务主动改变了目标帧率，不覆盖业务的新值。
                    if (window.attributes.preferredRefreshRate == vote.heldRate) {
                        window.attributes = window.attributes.apply { preferredRefreshRate = vote.rate }
                    }
                }
            }
        }
    }

    /** 在准备阶段统一源窗口和承载窗口，不能等焦点交接时才切换显示模式。 */
    fun holdTransition(host: Window, source: Window): () -> Unit {
        val releaseHost = hold(host)
        val releaseSource = if (source === host) ({}) else hold(source)
        return { releaseSource(); releaseHost() }
    }
}
