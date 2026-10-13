package com.ywwynm.everythingdone.taptransfer.diagnostics

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class TapConnectRetryLogTest {
    @Test fun fullConnectionDeadlineKeepsSetupAndFirstRefusalWithBoundedReports() {
        val buffer = TapDiagnosticBuffer("发送会话")
        buffer.add(0, "入组完成", "host=192.168.49.1, port=42689", checkpoint = true)
        var records = 0
        var stacks = 0
        var firstFailures = 0
        var clock = 0L
        val log = TapConnectRetryLog(clock) { detail, error, checkpoint ->
            records++
            if (error != null) stacks++
            buffer.add(clock, "TCP 建连", detail, error, checkpoint = checkpoint)
        }
        repeat(140) { index ->
            clock = index * 250L
            if (log.failed(clock, IOException("ECONNREFUSED localPort=${40000 + index}"))) firstFailures++
        }
        clock = 35000
        log.finish(clock, "期限结束")
        log.finish(clock, "迟到清理")
        assertFalse(log.failed(clock, IOException("迟到连接异常")))
        assertEquals(1, firstFailures)
        assertEquals(1, stacks)
        assertEquals(8, records)
        val report = buffer.snapshot()
        assertTrue(report.contains("入组完成"))
        assertTrue(report.contains("ECONNREFUSED localPort=40000"))
        assertTrue(report.contains("failures=140"))
        assertTrue(report.contains("result=期限结束"))
        assertFalse(report.contains("迟到清理"))
        assertFalse(report.contains("迟到连接异常"))
        assertFalse(report.contains("较早事件已省略"))
    }

    @Test fun eventualSuccessKeepsTheFirstFailureAndLatestDifferentCause() {
        val details = mutableListOf<String>()
        val log = TapConnectRetryLog(100) { detail, _, _ -> details.add(detail) }
        log.failed(110, IOException("ECONNREFUSED"))
        log.failed(400, IOException("connect failed from 192.168.100.166", IOException("ETIMEDOUT")))
        log.finish(800, "已连接")
        assertEquals(2, details.size)
        assertTrue(details.first().contains("ECONNREFUSED"))
        assertTrue(details.last().contains("ETIMEDOUT"))
        assertTrue(details.last().contains("from 192.168.100.166"))
        assertTrue(details.last().contains("result=已连接, failures=2, elapsedMs=700"))
    }
}
