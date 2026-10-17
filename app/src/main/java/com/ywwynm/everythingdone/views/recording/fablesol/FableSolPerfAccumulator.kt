package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.ceil

/** 取证专用时间项，统一纳秒输入；不把 CPU 提交耗时解释为 GPU 执行时间。 */
internal enum class FableSolPerfMetric {
    MAIN_QUEUE, INPUT_DELIVERY, INPUT_DISPATCH, INPUT_TO_CAPTURED_SWAP,
    WINDOW_UNKNOWN, WINDOW_INPUT, WINDOW_ANIMATION, WINDOW_LAYOUT, WINDOW_DRAW,
    WINDOW_SYNC, WINDOW_COMMAND, WINDOW_SWAP, WINDOW_TOTAL, WINDOW_GPU, WINDOW_DEADLINE,
    WINDOW_VSYNC_INTERVAL, WINDOW_CALLBACK_DELAY,
    PRE_DRAW_INTERVAL, CAPTURE_INTERVAL, CAPTURE_ALLOCATE, CAPTURE_RECORD, CAPTURE_SUBMIT,
    HWUI_CALLBACK, CAPTURE_FENCE, ALPHA_PROBE, CAPTURE_MAIN_QUEUE, CAPTURE_READY,
    CAPTURE_DEFERRED_WAIT, CAPTURE_RETRY_QUEUE,
    CAPTURE_CONSUMER_WAIT, CAPTURE_UNCONSUMED_RETURN_WAIT, FOREGROUND_CONSUMED_CALLBACK_QUEUE,
    BUFFER_HOLD, BUFFER_RELEASE_QUEUE, FOREGROUND_GL_QUEUE, FOREGROUND_REPLACE,
    FOREGROUND_FINISH, FOREGROUND_DELETE, FOREGROUND_IMPORT,
    FOREGROUND_ACQUIRE, FOREGROUND_REBIND,
    FOREGROUND_RETIRE_CREATE, FOREGROUND_RETIRE_POLL, FOREGROUND_RETIRE_LATENCY,
    FOREGROUND_USE_FENCE,
    FOREGROUND_PRESENT, FOREGROUND_PRESENT_SWAP, OUTPUT_SWAP_INTERVAL,
    CLOCK_MORPH_INTERVAL, CLOCK_DRAW_CPU, CLOCK_MORPH_TO_DRAW, CLOCK_DRAW_INTERVAL,
    CLOCK_MORPH_TO_SWAP, CLOCK_SWAP_INTERVAL,
    CLOCK_CAPTURE_INTERVAL, CLOCK_MORPH_TO_CAPTURE,
    GL_CALLBACK_INTERVAL, GL_CALLBACK_LATE, GL_CALLBACK_WORK, GL_CALLBACK_IDLE,
    GL_TIMELINE_GRID, GL_TIMELINE_LEAD, GL_TIMELINE_DEADLINE,
    GL_FRAME, GL_SWAP_INTERVAL, GL_SWAP, GL_DRAIN, GL_PHYSICS, GL_BUILD, GL_DRAW_CPU,
    GL_SAMPLE, GL_VERTEX, GL_OPTICS, GL_RIM, GL_STAR,
    NEW_FOREGROUND_INTERVAL, CAPTURE_TO_FIRST_SWAP, FOREGROUND_AGE_AT_SWAP,
    FOREGROUND_AGE_AFTER_INPUT, FIRST_BUFFERS_FINISH, PARTICLE_SNAPSHOT
}

internal enum class FableSolPerfCount {
    PRE_DRAW, CAPTURE_PENDING, CAPTURE_NO_BUFFER, CAPTURE_SUBMITTED, CAPTURE_READY,
    CAPTURE_RETRY_ATTEMPT, CAPTURE_RETRY_SUBMITTED, CAPTURE_RETRY_PENDING, CAPTURE_RETRY_NO_BUFFER,
    CAPTURE_CONSUMER_BUSY, CAPTURE_RETRY_CONSUMER_BUSY, CAPTURE_CONSUMED, CAPTURE_RETURNED_BEFORE_CONSUMED,
    CAPTURE_FAILED, BUFFER_CREATED, BUFFER_RELEASED, FOREGROUND_IMPORTED, FOREGROUND_REJECTED,
    FOREGROUND_CACHE_CREATED, FOREGROUND_CACHE_HIT, FOREGROUND_CACHE_DESTROYED, FOREGROUND_CACHE_REBIND_FAILED,
    FOREGROUND_RETIRED, FOREGROUND_RETIRE_SIGNALED, FOREGROUND_RETIRE_PENDING,
    FOREGROUND_RETIRE_FALLBACK, FOREGROUND_CONTEXT_FINISH,
    FOREGROUND_USE_FENCE, FOREGROUND_RETIRE_LAST_USE,
    FOREGROUND_MAILBOX_DISCARDED, FOREGROUND_TAKEN_BEFORE_PRESENT, FOREGROUND_ONLY_SWAP, WAVE_SWAP_OK,
    CLOCK_ANIMATOR_UPDATE, CLOCK_MORPH_FRAME, CLOCK_DRAW, CLOCK_NEW_DRAW, CLOCK_NEW_SWAP,
    CLOCK_DRAW_IN_CAPTURE, CLOCK_DRAW_OUTSIDE_CAPTURE, CLOCK_NEW_CAPTURE,
    CLOCK_DRAW_OUTSIDE_PENDING, CLOCK_DRAW_OUTSIDE_READY, CLOCK_CAPTURE_FROM_RETRY,
    GL_CALLBACK, GL_PACER_SKIP, GL_SWAP_OK, GL_SWAP_FAILED, NEW_FOREGROUND, REUSED_FOREGROUND,
    TOUCH_DOWN, TOUCH_MOVE, TOUCH_UP, TOUCH_CANCEL, WINDOW_FRAME, WINDOW_FIRST_FRAME,
    WINDOW_OVER_DEADLINE, METRICS_CALLBACKS_LOST, AUDIO_HOPS, AUDIO_EVENTS, AUDIO_PACKETS,
    TRACE_OVERFLOW
}

/** 两秒窗口内的有界样本。计数／均值／最大值覆盖全部样本，分位数最多保留最近 512 项。 */
internal class FableSolPerfAccumulator(private val capacity: Int = 512) {
    init { require(capacity > 0) }
    private class Series(capacity: Int) {
        val values = LongArray(capacity)
        var count = 0L
        var sum = 0.0
        var max = 0L
        var above16 = 0L
        var above33 = 0L
    }
    data class Sample(val count: Long, val sumNs: Double, val maxNs: Long,
                      val above16Ms: Long, val above33Ms: Long, val values: LongArray) {
        private val sorted by lazy(LazyThreadSafetyMode.NONE) { values.sortedArray() }
        fun percentile(fraction: Double): Long {
            require(fraction in 0.0..1.0)
            if (values.isEmpty()) return 0
            return sorted[(ceil(fraction * sorted.size).toInt() - 1).coerceIn(sorted.indices)]
        }
    }
    data class Snapshot(val metrics: Map<FableSolPerfMetric, Sample>, val counts: LongArray)
    private val series = Array(FableSolPerfMetric.values().size) { Series(capacity) }
    private val counts = LongArray(FableSolPerfCount.values().size)

    @Synchronized fun sample(metric: FableSolPerfMetric, nanos: Long) {
        if (nanos < 0L) return // 系统以 -1 表示不可用，不能算作 0 ms。
        val item = series[metric.ordinal]
        item.values[(item.count % capacity).toInt()] = nanos
        item.count++
        item.sum += nanos.toDouble()
        item.max = maxOf(item.max, nanos)
        if (nanos > 16_666_667L) item.above16++
        if (nanos > 33_333_333L) item.above33++
    }

    @Synchronized fun increment(counter: FableSolPerfCount, amount: Long = 1) {
        if (amount > 0) counts[counter.ordinal] += amount
    }

    /** 锁内仅复制数值并复位；排序、JSON 和文件写入全部在锁外、工作线程执行。 */
    @Synchronized fun drain(): Snapshot {
        val result = LinkedHashMap<FableSolPerfMetric, Sample>()
        for (metric in FableSolPerfMetric.values()) {
            val item = series[metric.ordinal]
            if (item.count > 0) result[metric] = Sample(item.count, item.sum, item.max,
                item.above16, item.above33, item.values.copyOf(minOf(item.count, capacity.toLong()).toInt()))
            item.count = 0; item.sum = 0.0; item.max = 0; item.above16 = 0; item.above33 = 0
        }
        val resultCounts = counts.copyOf()
        counts.fill(0)
        return Snapshot(result, resultCounts)
    }
}

internal data class FableSolPerfInput(val sequence: Long, val receivedNs: Long)
