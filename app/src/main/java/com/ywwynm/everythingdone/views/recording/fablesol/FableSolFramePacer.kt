package com.ywwynm.everythingdone.views.recording.fablesol

/**
 * 用显示 vsync 时间戳把任意刷新率的回调约束到固定渲染频率。
 *
 * 它只决定本次是否进行模拟和绘制；模拟步长仍由实际渲染时间戳推进，因此在 90/120Hz
 * 面板上跳过显示回调不会改变水体速度。
 */
internal class FableSolFramePacer(targetFps: Double) {

    private val intervalNs = (1_000_000_000.0 / targetFps).toLong()
    private var nextFrameNs = Long.MIN_VALUE

    fun shouldRender(frameTimeNs: Long): Boolean {
        if (nextFrameNs == Long.MIN_VALUE || frameTimeNs < nextFrameNs - intervalNs) {
            nextFrameNs = frameTimeNs + intervalNs
            return true
        }
        if (frameTimeNs + DEADLINE_TOLERANCE_NS < nextFrameNs) return false
        do {
            nextFrameNs += intervalNs
        } while (nextFrameNs <= frameTimeNs + DEADLINE_TOLERANCE_NS)
        return true
    }

    fun reset() {
        nextFrameNs = Long.MIN_VALUE
    }

    private companion object {
        // 120Hz 的整数纳秒周期每两帧会比 60Hz 周期少 1ns；同时容忍少量系统时间戳抖动。
        const val DEADLINE_TOLERANCE_NS = 500_000L
    }
}
