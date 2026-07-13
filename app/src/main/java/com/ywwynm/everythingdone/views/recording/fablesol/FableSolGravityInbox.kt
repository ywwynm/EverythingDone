package com.ywwynm.everythingdone.views.recording.fablesol

import java.util.concurrent.atomic.AtomicInteger

/** 单生产者传感器线程到单消费者渲染线程的无分配 latest-value 信箱。 */
internal class FableSolGravityInbox {

    private val sequence = AtomicInteger(0)
    private var x = 0f
    private var y = 1f
    private var z = 0f

    fun offer(x: Float, y: Float, z: Float) {
        sequence.incrementAndGet() // 奇数表示写入中。
        this.x = x
        this.y = y
        this.z = z
        sequence.incrementAndGet() // 偶数表示一组完整样本。
    }

    fun hasUpdateAfter(consumedSequence: Int): Boolean = sequence.get() != consumedSequence

    /** 返回本次读取到的序号；没有更新时返回 [consumedSequence]。 */
    fun drainLatest(consumedSequence: Int, output: FloatArray): Int {
        val before = sequence.get()
        if (before == consumedSequence || (before and 1) != 0) return consumedSequence
        val localX = x
        val localY = y
        val localZ = z
        val after = sequence.get()
        if (before != after || (after and 1) != 0) return consumedSequence
        output[0] = localX
        output[1] = localY
        output[2] = localZ
        return after
    }
}
