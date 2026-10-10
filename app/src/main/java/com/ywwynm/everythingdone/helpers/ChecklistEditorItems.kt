package com.ywwynm.everythingdone.helpers

/** 详情编辑状态独立于 ViewHolder；相同文字的两行也有各自的身份。持有的是数据，不是 View。 */
internal class ChecklistEditorItems(items: List<String?>) : AbstractMutableList<String?>() {
    class Entry(var value: String?) {
        var selectionStart = -1
        var selectionEnd = -1
        var composingStart = -1
        var composingEnd = -1
        fun finishComposition() { composingStart = -1; composingEnd = -1 }
    }

    private val entries = items.mapTo(ArrayList()) { Entry(it) }
    private var finishedHeaderIndex: Int? = null
    override val size get() = entries.size
    override fun get(index: Int) = entries[index].value
    override fun indexOf(element: String?): Int {
        if (element != "4") return entries.indexOfFirst { it.value == element }
        // ConcatAdapter 和网格布局会频繁读取折叠区的 itemCount；滚动期间无需反复扫描清单。
        return finishedHeaderIndex ?: entries.indexOfFirst { it.value == "4" }.also { finishedHeaderIndex = it }
    }
    override fun set(index: Int, element: String?): String? {
        val entry = entries[index]
        val previous = entry.value
        entry.value = element
        if (previous == "4" || element == "4") finishedHeaderIndex = null
        if (CheckListHelper.textOf(previous) != CheckListHelper.textOf(element)) entry.finishComposition()
        return previous
    }
    override fun add(index: Int, element: String?) { entries.add(index, Entry(element)); finishedHeaderIndex = null }
    override fun removeAt(index: Int): String? {
        val removed = entries.removeAt(index).value
        finishedHeaderIndex = null
        return removed
    }
    fun entry(index: Int) = entries.getOrNull(index)
    fun move(from: Int, to: Int) { entries.add(to, entries.removeAt(from)); finishedHeaderIndex = null }
}
