package com.ywwynm.everythingdone.hdr

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HdrDiagnosticJournalTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `重复导出只各合并一次当前生产日志 不扩大长期 journal`() {
        val journal = HdrDiagnosticJournal(temporary.newFolder(), 1024)
        journal.event("open", "当前版本")
        val before = journal.logFile.readBytes()
        repeat(12) { index ->
            val output = journal.snapshot(File(temporary.root, "export-$index.txt"), listOf("perf" to "unique-current-perf"))
            assertEquals(1, Regex("unique-current-perf").findAll(output.readText()).count())
            assertArrayEquals(before, journal.logFile.readBytes())
        }
    }

    @Test fun `有界事件保留完整 UTF8 与最新内容 超大事件明确标注`() {
        val journal = HdrDiagnosticJournal(temporary.newFolder(), 1024)
        repeat(20) { journal.event("step-$it", "中文诊断".repeat(30)) }
        assertTrue(journal.logFile.length() <= 1024)
        assertTrue(journal.logFile.readText().contains("step-19"))
        assertFalse(journal.logFile.readText().contains('\uFFFD'))
        journal.event("huge", "非常大".repeat(2000))
        assertTrue(journal.logFile.length() <= 1024)
        assertTrue(journal.logFile.readText().contains("journal_event_omitted"))
    }

    @Test fun `加载已有过大记录会清理 用户导出副本不受影响`() {
        val directory = temporary.newFolder()
        File(directory, "hdr-diagnostics.txt").writeText("旧数据".repeat(10000))
        val exported = File(temporary.root, "exported.txt").apply { writeText("已分享") }
        val journal = HdrDiagnosticJournal(directory, 1024)
        assertTrue(journal.logFile.length() <= 1024)
        assertTrue(journal.logFile.readText().contains("journal_reset"))
        journal.clear(); journal.event("new", "重新记录")
        assertFalse(journal.logFile.readText().contains("journal_reset"))
        assertEquals("已分享", exported.readText())
    }
}
