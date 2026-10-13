package com.ywwynm.everythingdone.taptransfer.diagnostics

import java.io.IOException

/** 单次 TCP 建连的诊断限流，不参与连接时序。第一次保留堆栈，随后每五秒及结束时汇总。 */
internal class TapConnectRetryLog(
    private val startedMs: Long,
    private val enabled: Boolean = true,
    private val record: (detail: String, error: IOException?, checkpoint: Boolean) -> Unit
) {
    private var failures = 0
    private var lastReportMs = startedMs
    private var lastError = "无"
    private var finished = false

    /** 返回是否第一次失败，供调用方只触发一次环境查询。 */
    fun failed(nowMs: Long, error: IOException): Boolean {
        if (!enabled || finished) return false
        failures++
        var cause: Throwable = error
        repeat(3) { cause = cause.cause?.takeUnless { it === cause } ?: cause }
        // 外层异常含实际源地址，底层异常含 errno；汇总也需保留这两者，以观察重试中途的路由变化。
        lastError = "${error.javaClass.simpleName}: ${error.message.orEmpty().take(320)}" +
            if (cause === error) "" else "; ${cause.javaClass.simpleName}: ${cause.message.orEmpty().take(120)}"
        val first = failures == 1
        if (first || nowMs - lastReportMs >= 5000) {
            lastReportMs = nowMs
            record(summary(nowMs, "尚未连接"), if (first) error else null, first)
        }
        return first
    }

    fun finish(nowMs: Long, outcome: String) {
        if (!enabled || finished) return
        finished = true
        if (failures > 0) record(summary(nowMs, outcome), null, true)
    }

    private fun summary(nowMs: Long, outcome: String) =
        "result=$outcome, failures=$failures, elapsedMs=${(nowMs - startedMs).coerceAtLeast(0)}, lastError=$lastError"
}
