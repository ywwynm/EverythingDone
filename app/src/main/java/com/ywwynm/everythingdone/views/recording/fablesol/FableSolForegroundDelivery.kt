package com.ywwynm.everythingdone.views.recording.fablesol

/** 主线程上的逐帧交付诊断；不控制捕获，也不归还仍被 GPU 使用的缓冲。 */
internal class FableSolForegroundDelivery {
    private var sequences = LongArray(3)
    var pendingCount = 0
        private set
    val pending: Boolean get() = pendingCount != 0
    val awaitingSequence: Long get() {
        var oldest = 0L
        for (sequence in sequences) if (sequence > 0L && (oldest == 0L || sequence < oldest)) oldest = sequence
        return oldest
    }

    fun submitted(sequence: Long) {
        check(sequence > 0L && sequence !in sequences)
        var index = sequences.indexOf(0L)
        if (index < 0) {
            // 尺寸变化时旧缓冲可能尚未返回；诊断容量不能反过来阻塞新的捕获。
            index = sequences.size
            sequences = sequences.copyOf(sequences.size * 2)
        }
        sequences[index] = sequence
        pendingCount++
    }

    /** 消费与归还可能先后通知同一帧，只记录最先一次；旧帧不影响其它帧。 */
    fun consumedOrReturned(sequence: Long): Boolean {
        if (sequence <= 0L) return false
        val index = sequences.indexOf(sequence)
        if (index < 0) return false
        sequences[index] = 0L
        pendingCount--
        return true
    }
}
