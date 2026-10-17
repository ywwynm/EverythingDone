package com.ywwynm.everythingdone.views.recording.fablesol

/** 只由前景 GL 队列访问。归还必须发生在最后使用 fence 完成后，不负责 GPU 同步本身。 */
internal class FableSolForegroundImportCache<K : Any, V : Any>(
    private val create: (K) -> V,
    private val rebind: (V) -> Boolean,
    private val destroy: (V) -> Unit,
    private val valid: (K) -> Boolean,
    private val capacity: Int = 3
) {
    class Entry<K, V> internal constructor(val key: K, val value: V) {
        internal var inUse = true
    }
    private val entries = mutableListOf<Entry<K, V>>()
    val size: Int get() = entries.size
    val activeCount: Int get() = entries.count { it.inUse }
    var peakSize = 0
        private set
    var reuseEnabled = true
        private set

    init { require(capacity > 0) }

    fun acquire(key: K): Entry<K, V> {
        check(valid(key)) { "foreground_buffer_closed" }
        trim()
        val cached = entries.firstOrNull { it.key === key }
        if (cached != null) {
            check(!cached.inUse) { "foreground_buffer_still_in_use" }
            if (rebind(cached.value)) {
                cached.inUse = true
                entries.remove(cached); entries += cached
                return cached
            }
            // 只处理已明确报告的重绑失败；本上下文退回逐次导入，不重复触发同类驱动错误。
            reuseEnabled = false
            trim()
        }
        val entry = Entry(key, create(key))
        entries += entry
        peakSize = maxOf(peakSize, entries.size)
        trim()
        return entry
    }

    fun release(entry: Entry<K, V>) {
        check(entry in entries && entry.inUse) { "foreground_import_not_owned" }
        entry.inUse = false
        // EGLImage 保留自己的缓冲引用；只归还本帧使用权，下次 HWUI 写完后可重新绑定。
        trim()
    }

    private fun trim() {
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!entry.inUse && (!reuseEnabled || !valid(entry.key))) {
                iterator.remove(); destroy(entry.value)
            }
        }
        // 尺寸切换时旧 GPU 资源暂时超过目标容量，只能移除空闲项。
        while (entries.size > capacity) {
            val unused = entries.firstOrNull { !it.inUse } ?: break
            entries.remove(unused); destroy(unused.value)
        }
    }

    /** 调用者先完成 GPU 并归还所有当前／退休项，再销毁 EGL 上下文。 */
    fun clearAfterFinish() {
        check(activeCount == 0) { "foreground_imports_still_in_use" }
        entries.forEach { destroy(it.value) }
        entries.clear()
        reuseEnabled = true
    }
}
