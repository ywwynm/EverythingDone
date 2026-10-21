package com.ywwynm.everythingdone.model

/**
 * 仅复用由完整 JSON 决定的不可变外观值，不持有 Thing、View 或数据库对象。
 * 数量和文本总长度均有上限；较大输入继续解析，但不挤出已有的常用值。
 */
internal class ParsedAppearanceCache<T : Any>(
    private val maxEntries: Int = 1024,
    private val maxChars: Int = 512 * 1024,
    private val maxEntryChars: Int = 64 * 1024,
    private val decode: (String) -> T?
) {
    private class Entry<T>(val value: T?)
    private val entries = LinkedHashMap<String, Entry<T>>(16, .75f, true)
    private var retainedChars = 0

    init {
        require(maxEntries > 0 && maxChars > 0 && maxEntryChars > 0)
    }

    fun parse(json: String?): T? {
        if (json.isNullOrEmpty()) return null
        if (json.length > maxEntryChars || json.length > maxChars) return decode(json)
        synchronized(entries) { entries[json]?.let { return it.value } }
        // JSON 解析不占用缓存锁，后台小组件读取不能阻塞主线程已命中的外观。
        val decoded = decode(json)
        synchronized(entries) {
            entries[json]?.let { return it.value }
            entries[json] = Entry(decoded)
            retainedChars += json.length
            val iterator = entries.entries.iterator()
            while (entries.size > maxEntries || retainedChars > maxChars) {
                retainedChars -= iterator.next().key.length
                iterator.remove()
            }
        }
        return decoded
    }
}
