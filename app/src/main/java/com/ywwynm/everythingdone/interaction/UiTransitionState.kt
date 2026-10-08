package com.ywwynm.everythingdone.interaction

/** 每个 Activity 独立管理；所有调用均在主线程，受理必须先于 Fragment 事务或导航。 */
internal class UiTransitionState {
    val root = Any()
    private enum class Phase { OPENING, READY, CLOSING }
    private data class Layer(val token: Any, val key: String, var phase: Phase)
    private val layers = mutableListOf<Layer>()
    private var departure: Any? = null
    private var departed = false

    val current: Any get() = layers.lastOrNull()?.token ?: root

    fun open(key: String, source: Any = current): Any? {
        if (!canLeave(source)) return null
        if (layers.any { it.key == key && it.phase != Phase.CLOSING }) return null
        return restore(key)
    }

    fun restore(key: String): Any = Any().also { layers += Layer(it, key, Phase.OPENING) }

    fun ready(token: Any) {
        layers.find { it.token === token && it.phase == Phase.OPENING }?.phase = Phase.READY
    }

    fun closing(token: Any) { layers.find { it.token === token }?.phase = Phase.CLOSING }
    fun release(token: Any) { layers.removeAll { it.token === token } }

    fun canInteract(source: Any): Boolean = departure == null && source === current &&
        (layers.lastOrNull()?.phase?.let { it == Phase.READY } ?: true)

    // 关闭中的来源仍可同步交接给后继弹窗；其窗口已经不能接收新的点击。
    private fun canLeave(source: Any): Boolean = departure == null && source === current &&
        layers.lastOrNull()?.phase != Phase.OPENING

    fun leave(source: Any = current): Any? {
        if (!canLeave(source)) return null
        return Any().also { departure = it; departed = false }
    }

    fun cancelDeparture(token: Any) {
        if (departure === token) { departure = null; departed = false }
    }

    fun paused() { if (departure != null) departed = true }
    fun resumed() {
        if (departed) { departure = null; departed = false }
    }
}
