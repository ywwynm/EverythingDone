package com.ywwynm.everythingdone.views.particledismiss

/** 系统暗层归零后，还需等待原窗口提交；重复关闭及迟到回调不得再次移除窗口。 */
internal class DialogDimDismissGate(private val dismissWindow: () -> Unit) {
    var closing = false
        private set
    private var cleared = false
    private var finished = false

    fun begin(): Boolean {
        if (closing || finished) return false
        closing = true
        return true
    }

    fun dimCleared(): Boolean {
        if (!closing || finished || cleared) return false
        cleared = true
        return true
    }

    fun frameCommitted() {
        if (closing && cleared) finishImmediately()
    }

    fun finishImmediately() {
        if (finished) return
        finished = true
        closing = true
        dismissWindow()
    }

    /** 宿主已经摘除窗口时，旧动画不再操作它。 */
    fun detached() { finished = true; closing = true }
}
