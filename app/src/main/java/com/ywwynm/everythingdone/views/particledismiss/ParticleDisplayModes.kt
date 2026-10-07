package com.ywwynm.everythingdone.views.particledismiss

import java.util.WeakHashMap

/** 显示模式选择不依赖 Android，分辨率和其他功能已有的更高刷新率偏好都保留。 */
internal object ParticleDisplayModes {
    data class Mode(val id: Int, val width: Int, val height: Int, val rate: Float)

    fun choose(modes: List<Mode>, width: Int, height: Int, original: Int, target: Float): Int? {
        val candidates = modes.filter { it.width == width && it.height == height && it.rate > 61f }
        candidates.firstOrNull { it.id == original && it.rate >= target - .5f }?.let { return it.id }
        // 120.00001 等面板标称误差不应落到 60 Hz；没有 120 档时选最低的足够高的档位。
        return (candidates.filter { it.rate >= target - .5f }.minByOrNull { it.rate }
            ?: candidates.maxByOrNull { it.rate })?.id
    }
}

/** 一个窗口可同时有多个粒子 Surface；只有最后一个请求退出才恢复原偏好。 */
internal class ParticleDisplayModeVotes<K : Any> {
    private class Request(val original: Int, val applied: Int, owner: Any) {
        val owners = hashSetOf(owner)
    }
    private val requests = WeakHashMap<K, Request>()

    /** 返回需要写入窗口的值；null 表示无需修改。owner 必须是独立 token，不能持有窗口。 */
    fun update(window: K, owner: Any, current: Int, desired: Int?): Int? {
        val previous = requests[window]
        if (desired != null) {
            if (previous != null) {
                previous.owners.add(owner)
                return null
            }
            requests[window] = Request(current, desired, owner)
            return desired.takeIf { it != current }
        }
        if (previous == null || !previous.owners.remove(owner) || previous.owners.isNotEmpty()) return null
        requests.remove(window)
        // 其他功能在动画期间改了偏好时，不覆盖它的新值。
        return previous.original.takeIf { current == previous.applied && current != it }
    }
}
