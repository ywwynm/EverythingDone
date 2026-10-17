package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FableSolDiagnosticLogFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `首次升级清除本功能当前及轮转日志 保留其它文件`() {
        val directory = temporary.newFolder()
        for (name in FableSolDiagnosticLogFiles.FILES) for (suffix in listOf("", ".old"))
            File(directory, name + suffix).writeText("旧记录")
        val other = File(directory, "notification.log").apply { writeText("其它功能") }
        val exported = File(directory, "fablesol-composition-user-export.txt").apply { writeText("用户已导出") }
        assertTrue(FableSolDiagnosticLogFiles(directory).ensureVersion("849"))
        for (name in FableSolDiagnosticLogFiles.FILES) for (suffix in listOf("", ".old"))
            assertFalse(File(directory, name + suffix).exists())
        assertEquals("其它功能", other.readText())
        assertEquals("用户已导出", exported.readText())
    }

    @Test fun `同版本重启和再次打开不会清空刚完成的测试`() {
        val directory = temporary.newFolder()
        val store = FableSolDiagnosticLogFiles(directory)
        store.ensureVersion("849")
        val file = File(directory, FableSolDiagnosticLogFiles.FILES.last()).apply { writeText("新测试") }
        assertFalse(FableSolDiagnosticLogFiles(directory).ensureVersion("849"))
        assertEquals("新测试", file.readText())
        assertTrue(store.ensureVersion("850"))
        assertFalse(file.exists())
    }

    @Test fun `用户主动清空同版本后可以继续写新日志`() {
        val directory = temporary.newFolder()
        val store = FableSolDiagnosticLogFiles(directory)
        store.ensureVersion("849")
        val file = File(directory, FableSolDiagnosticLogFiles.FILES.last()).apply { writeText("旧测试") }
        store.clear("849")
        assertFalse(file.exists())
        file.writeText("重新测试")
        assertFalse(store.ensureVersion("849"))
        assertEquals("重新测试", file.readText())
    }
}
