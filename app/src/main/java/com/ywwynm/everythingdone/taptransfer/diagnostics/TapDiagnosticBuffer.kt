package com.ywwynm.everythingdone.taptransfer.diagnostics

import java.util.ArrayDeque

/** 每个会话独立持有，迟到回调仍写入原会话；重试不会清掉失败依据。 */
internal class TapDiagnosticBuffer(
    private val header: String,
    private val maxEvents: Int = 160,
    private val maxChars: Int = 12_000
) {
    private val events = ArrayDeque<String>()
    private val secrets = mutableSetOf<String>()
    private var chars = 0
    private var omitted = 0
    private val failures = ArrayDeque<String>()
    private val environment = linkedMapOf<String, String>()
    private val checkpoints = ArrayDeque<String>()
    private var checkpointChars = 0

    @Synchronized
    fun hide(vararg values: String) {
        secrets.addAll(values.filter { it.isNotEmpty() })
    }

    @Synchronized
    fun add(elapsedMs: Long, stage: String, detail: String, error: Throwable? = null,
            failure: Boolean = false, environmentInfo: Boolean = false, checkpoint: Boolean = false) {
        val raw = buildString {
            append("[+").append(elapsedMs.coerceAtLeast(0)).append(" ms] ").append(stage)
            if (detail.isNotBlank()) append(" | ").append(detail)
            var cause = error
            // 异常链及应用/框架调用位置足以区分平台拒绝、超时和协议失败；不用原始对象的 toString。
            repeat(4) {
                val current = cause ?: return@repeat
                append("\n  ").append(current.javaClass.name).append(": ").append(current.message.orEmpty())
                current.stackTrace.take(6).forEach { append("\n    at ").append(it) }
                cause = current.cause?.takeUnless { it === current }
            }
        }
        var safe = raw
        secrets.sortedByDescending { it.length }.forEach { safe = safe.replace(it, "<已隐藏>") }
        safe = safe.replace(Regex("(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}"), "<MAC 已隐藏>")
            .replace(Regex("/(?:storage|data|sdcard)/[^\\s,;]+"), "<路径已隐藏>")
            .take(1800)
        if (failure) {
            failures.addLast(safe)
            while (failures.size > 4) failures.removeFirst()
        }
        if (environmentInfo) environment[stage] = safe
        if (checkpoint) {
            checkpoints.addLast(safe)
            checkpointChars += safe.length
            while (checkpoints.size > 20 || checkpointChars > 8000) {
                checkpointChars -= checkpoints.removeFirst().length
            }
        }
        events.addLast(safe)
        chars += safe.length
        while (events.size > maxEvents || chars > maxChars) {
            chars -= events.removeFirst().length
            omitted++
        }
    }

    @Synchronized
    fun snapshot(): String = buildString {
        append(header).append('\n')
        environment.values.forEach { append(it).append('\n') }
        if (failures.isNotEmpty()) {
            append("最近失败（重试后保留）：\n")
            failures.forEach { append(it).append('\n') }
        }
        if (omitted > 0 && checkpoints.isNotEmpty()) {
            append("关键阶段（滚动日志截断后保留）：\n")
            checkpoints.forEach { append(it).append('\n') }
        }
        if (omitted > 0) append("较早事件已省略：").append(omitted).append(" 条\n")
        events.forEach { append(it).append('\n') }
    }
}
