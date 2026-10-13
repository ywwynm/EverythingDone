package com.ywwynm.everythingdone.taptransfer.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.concurrent.thread

class TapDiagnosticBufferTest {
    @Test fun connectionRefusalBurstKeepsHandshakeGroupAndFirstErrorAfterRetry() {
        val buffer = TapDiagnosticBuffer("发送会话")
        buffer.hide("DIRECT-private", "secret-password")
        buffer.add(7300, "NFC 已接受接收方", "receiverCreatesGroup=true", checkpoint = true)
        buffer.add(9100, "发送方已加入接收组", "host=192.168.49.1, port=42689", checkpoint = true)
        repeat(140) { i ->
            val error = IOException("ECONNREFUSED attempt=$i DIRECT-private secret-password").apply {
                stackTrace = Array(6) { StackTraceElement("SocketInput", "connect", "SocketInput.java", 101 + it) }
            }
            buffer.add(9200L + i * 250, "TCP 未连接", "", error, checkpoint = i == 0)
        }
        buffer.add(44200, "连接超时", "limitMs=35000", failure = true)
        repeat(30) { buffer.add(45000L + it, "恢复发送组", "step=$it") }
        val report = buffer.snapshot()
        assertTrue(report.contains("receiverCreatesGroup=true"))
        assertTrue(report.contains("host=192.168.49.1, port=42689"))
        assertTrue(report.contains("ECONNREFUSED attempt=0 "))
        assertTrue(report.contains("limitMs=35000"))
        assertTrue(report.contains("step=29"))
        assertTrue(report.contains("较早事件已省略"))
        assertFalse(report.contains("DIRECT-private"))
        assertFalse(report.contains("secret-password"))
    }

    @Test fun retainedCheckpointsRemainBoundedAcrossManyTransfers() {
        val buffer = TapDiagnosticBuffer("发送会话")
        repeat(1000) { buffer.add(it.toLong(), "关键阶段", "x".repeat(2000), checkpoint = true) }
        buffer.add(1001, "最新一次入组", "port=42689", checkpoint = true)
        val report = buffer.snapshot()
        assertTrue(report.length < 22_000)
        assertTrue(report.contains("最新一次入组"))
    }

    @Test fun failureSurvivesProgressAndRetryEviction() {
        val buffer = TapDiagnosticBuffer("接收会话", maxEvents = 3, maxChars = 500)
        buffer.add(0, "设备", "model=测试设备, API=36", environmentInfo = true)
        buffer.add(21, "connect 失败", "reason=2 (BUSY)", failure = true)
        buffer.add(22, "接收失败提示", "JOIN", failure = true)
        repeat(100) { buffer.add(100L + it, "重试进度", "$it/100") }
        val report = buffer.snapshot()
        assertTrue(report.contains("reason=2 (BUSY)"))
        assertTrue(report.contains("model=测试设备, API=36"))
        assertTrue(report.contains("99/100"))
        assertTrue(report.contains("较早事件已省略"))
        assertFalse(report.contains("0/100"))
    }

    @Test fun credentialsAndPathsAreRemovedFromNestedExceptions() {
        val buffer = TapDiagnosticBuffer("接收会话")
        buffer.hide("DIRECT-xy-测试", "test-secret-123", "0123456789abcdef")
        val cause = IOException("password=test-secret-123 path=/storage/emulated/0/个人文件.edthing")
        val error = IllegalArgumentException("DIRECT-xy-测试 token=0123456789abcdef mac=ab:cd:ef:12:34:56", cause)
        buffer.add(60, "入组失败", "", error, failure = true)
        val report = buffer.snapshot()
        listOf("DIRECT-xy-测试", "test-secret-123", "0123456789abcdef", "个人文件", "ab:cd:ef:12:34:56")
            .forEach { assertFalse(report.contains(it)) }
        assertTrue(report.contains("java.lang.IllegalArgumentException"))
        assertTrue(report.contains("java.io.IOException"))
        assertTrue(report.contains("at "))
    }

    @Test fun concurrentCallbacksStayInTheirOriginalSession() {
        val old = TapDiagnosticBuffer("旧会话", maxEvents = 256)
        val new = TapDiagnosticBuffer("新会话")
        val writers = (1..4).map { worker -> thread {
            repeat(32) { old.add(it.toLong(), "worker=$worker", "event=$it") }
        } }
        new.add(0, "等待碰触", "")
        writers.forEach { it.join() }
        assertFalse(new.snapshot().contains("worker="))
        (1..4).forEach { assertTrue(old.snapshot().contains("worker=$it | event=31")) }
    }

    @Test fun hugeMessagesAreBoundedAndRecentEventsRemainReadable() {
        val buffer = TapDiagnosticBuffer("会话", maxEvents = 8, maxChars = 4000)
        repeat(160) { buffer.add(it.toLong(), "异常", "x".repeat(10000), failure = true) }
        buffer.add(200, "准备重试", "")
        val report = buffer.snapshot()
        assertTrue(report.length < 12_000)
        assertTrue(report.contains("准备重试"))
        assertTrue(report.contains("较早事件已省略"))
    }
}
