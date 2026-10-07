package com.ywwynm.everythingdone.views.particledismiss

import android.view.Window

/**
 * 飘散高刷期间的宿主窗口请求，所有调用均在主线程。
 *
 * 魅族 Android 15 实测：仅 Surface 的 120 Hz 提示、窗口数值刷新率提示都会被降到 60 Hz；
 * 同分辨率的明确模式请求可恢复详情页大弹窗的高刷。系统仍保留最终模式选择权。
 *
 * 多个粒子 Surface 可以属于同一个 Activity。最后一个高刷请求退出后才恢复窗口原值，
 * 所有者只传独立 token，避免弱键表的值反向持有 Activity／Window。
 */
internal object ParticleWindowFrameRate {
    private val requests = ParticleDisplayModeVotes<Window>()

    fun vote(window: Window, owner: Any, rate: Float) {
        val attributes = window.attributes
        val preferred = if (rate > ParticleWindowSurface.VOTED_FRAME_RATE) {
            val display = window.decorView.display ?: return
            val current = display.mode
            ParticleDisplayModes.choose(display.supportedModes.map {
                ParticleDisplayModes.Mode(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate)
            }, current.physicalWidth, current.physicalHeight, attributes.preferredDisplayModeId, rate)
        } else null
        val next = requests.update(window, owner, attributes.preferredDisplayModeId, preferred) ?: return
        attributes.preferredDisplayModeId = next
        window.attributes = attributes
    }
}
