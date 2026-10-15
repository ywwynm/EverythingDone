package com.ywwynm.everythingdone.helpers

import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** UI 订阅可释放；释放只停止通知，不取消已经发起的后台生成。 */
internal class CoverGenerationSubscription : Closeable {
    private val closed = AtomicBoolean(false)
    private val cleanup = mutableListOf<() -> Unit>()

    fun dispatch(action: () -> Unit) {
        main.post { if (!closed.get()) action() }
    }

    // 只在主线程登记和移除 LiveData 观察者。
    fun onClose(action: () -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed.get()) action() else cleanup.add(action)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (Looper.myLooper() == Looper.getMainLooper()) release() else main.post { release() }
    }

    private fun release() {
        cleanup.forEach { it() }
        cleanup.clear()
    }

    companion object {
        private val main = Handler(Looper.getMainLooper())
    }
}
