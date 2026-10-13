package com.ywwynm.everythingdone.views.particledismiss

/** 高频帧数据保留开头与末尾；不能挤掉隐藏、恢复和失败等生命周期证据。调用方负责同步。 */
internal class PresentationEventBuffer<T>(private val frameCapacity: Int = 2048,
                                           private val lifecycleCapacity: Int = 1024) {
    internal data class Entry<T>(val order: Long, val value: T)
    class Snapshot<T> internal constructor(private val entries: List<Entry<T>>,
        val droppedFrames: Int, val droppedLifecycle: Int) {
        fun values(): List<T> = entries.sortedBy { it.order }.map { it.value }
    }
    private var order = 0L
    private val initialFrames = ArrayList<Entry<T>>()
    private val latestFrames = ArrayDeque<Entry<T>>()
    private val lifecycle = ArrayDeque<Entry<T>>()
    var droppedFrames = 0
        private set
    var droppedLifecycle = 0
        private set

    init { require(frameCapacity >= 2 && lifecycleCapacity > 0) }

    fun add(value: T, frame: Boolean) {
        val entry = Entry(order++, value)
        if (!frame) {
            if (lifecycle.size == lifecycleCapacity) { lifecycle.removeFirst(); droppedLifecycle++ }
            lifecycle.addLast(entry)
        } else if (initialFrames.size < frameCapacity / 2) initialFrames += entry
        else {
            if (latestFrames.size == frameCapacity - frameCapacity / 2) { latestFrames.removeFirst(); droppedFrames++ }
            latestFrames.addLast(entry)
        }
    }

    /** 锁内只复制引用，排序和 JSON 组装由调用方在锁外完成。 */
    fun capture(): Snapshot<T> = Snapshot(initialFrames + latestFrames + lifecycle, droppedFrames, droppedLifecycle)
    fun snapshot(): List<T> = capture().values()
}
