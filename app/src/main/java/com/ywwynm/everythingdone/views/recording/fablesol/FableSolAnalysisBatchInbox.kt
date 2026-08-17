package com.ywwynm.everythingdone.views.recording.fablesol

/**
 * 一次 [FableSolRealtimeAnalyzer.feed] 的完整观测结果。
 *
 * [FableSolEvent.t] 是声学边界时刻，不是检测器实际观察到事件的时刻；因此 batch 边界是
 * 因果语义的一部分，不能在渲染线程 drain 时把多批 frames/events 分别合并。
 */
internal class FableSolAnalysisBatch(
    val frames: List<FableSolFeatureFrame>,
    val events: List<FableSolEvent>
)

/** 采集线程写、渲染线程整批 drain 的并发 inbox。 */
internal class FableSolAnalysisBatchInbox {
    private val lock = Any()
    private var pending = ArrayList<FableSolAnalysisBatch>()

    fun offer(frames: List<FableSolFeatureFrame>, events: List<FableSolEvent>) {
        if (frames.isEmpty() && events.isEmpty()) return
        val batch = FableSolAnalysisBatch(
            ArrayList(frames),
            events.sortedBy { it.t }
        )
        synchronized(lock) { pending.add(batch) }
    }

    fun drain(): ArrayList<FableSolAnalysisBatch> = synchronized(lock) {
        pending.also { pending = ArrayList() }
    }

    /** View 不可见时丢弃尚未消费的批次，返回前台后只响应新的实时声音。 */
    fun clear() {
        synchronized(lock) { pending = ArrayList() }
    }
}

internal class FableSolAnalysisBatchCounts(
    val frames: Int,
    val events: Int
)

/** GLES 与 Canvas 共用的 authoritative hop / event 交织规则。 */
internal object FableSolAnalysisBatchConsumer {
    fun consume(
        batches: List<FableSolAnalysisBatch>,
        onFrame: (FableSolFeatureFrame) -> Unit,
        onEvent: (FableSolEvent) -> Unit
    ): FableSolAnalysisBatchCounts {
        var frameCount = 0
        var eventCount = 0
        for (batch in batches) {
            var eventIndex = 0
            for (frame in batch.frames) {
                while (eventIndex < batch.events.size && batch.events[eventIndex].t < frame.t) {
                    onEvent(batch.events[eventIndex++])
                    eventCount++
                }
                onFrame(frame)
                frameCount++
                while (eventIndex < batch.events.size && batch.events[eventIndex].t <= frame.t) {
                    onEvent(batch.events[eventIndex++])
                    eventCount++
                }
            }
            while (eventIndex < batch.events.size) {
                onEvent(batch.events[eventIndex++])
                eventCount++
            }
        }
        return FableSolAnalysisBatchCounts(frameCount, eventCount)
    }
}
