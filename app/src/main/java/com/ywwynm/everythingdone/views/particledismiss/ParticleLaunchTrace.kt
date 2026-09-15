package com.ywwynm.everythingdone.views.particledismiss

import com.ywwynm.everythingdone.BuildConfig

/**
 * 「点新建按钮 → 出现动画首帧」这条链路的分段时刻（2a）。
 *
 * 只在 debug 包记录，release 包每个 [mark] 都在第一行返回、不分配任何对象。用来回答
 * 用户那个问题：点下去之后到底是**真的在等**，还是动画已经在播、只是起始段看不出来。
 * 读取方是 debug 的只读观察器；它把这份时间线连同会话自己的分段一起写进 result.json。
 */
internal object ParticleLaunchTrace {

    /** 一个分段点：标签 + 墙钟（`System.nanoTime`，与观察器其余时间戳同一时钟）。 */
    internal class Mark(val label: String, val at: Long)

    private const val CAPACITY = 64

    private val marks = ArrayList<Mark>(CAPACITY)

    fun mark(label: String) {
        if (!BuildConfig.DEBUG) return
        val entry = Mark(label, System.nanoTime())
        synchronized(marks) {
            // 一次新建只会打十来个点；上限只防止长时间运行后无限增长。
            if (marks.size >= CAPACITY) marks.removeAt(0)
            marks += entry
        }
    }

    fun snapshot(): List<Mark> = synchronized(marks) { marks.toList() }

    fun clear() = synchronized(marks) { marks.clear() }
}
