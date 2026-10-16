package com.ywwynm.everythingdone.hdr

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HdrDiagnosticsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun knownChartHasOneTwoAndFourTimesLinearLuminance() {
        val weight = HdrProbeMath.weight(4f, 1f, 4f)
        assertEquals(1f, HdrProbeMath.apply(1f, 0f, 1f, 4f, 1f, 0f, 0f, weight), 0.00001f)
        assertEquals(2f, HdrProbeMath.apply(1f, 0.5f, 1f, 4f, 1f, 0f, 0f, weight), 0.00001f)
        assertEquals(4f, HdrProbeMath.apply(1f, 1f, 1f, 4f, 1f, 0f, 0f, weight), 0.00001f)
    }

    @Test fun limitedHeadroomReducesGainAndClampsAtImageLimit() {
        assertEquals(0f, HdrProbeMath.weight(1f, 1f, 4f), 0f)
        assertEquals(0.5f, HdrProbeMath.weight(2f, 1f, 4f), 0.00001f)
        assertEquals(1f, HdrProbeMath.weight(8f, 1f, 4f), 0f)
        assertEquals(2f, HdrProbeMath.apply(1f, 1f, 1f, 4f, 1f, 0f, 0f,
            HdrProbeMath.weight(2f, 1f, 4f)), 0.00001f)
    }

    @Test fun equalTransitionBoundsDoNotProduceNan() {
        assertEquals(0f, HdrProbeMath.weight(1f, 1f, 1f), 0f)
        assertEquals(1f, HdrProbeMath.weight(2f, 1f, 1f), 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unmeasuredHeadroomCannotBeUsedAsZeroInGainFormula() { HdrProbeMath.weight(0f, 1f, 4f) }

    @Test fun journalSurvivesReopenAndSnapshotContainsOnlyEvents() {
        val folder = temporary.newFolder("session")
        val journal = HdrDiagnosticJournal(folder)
        journal.event("状态", "中文观感：没有变化")
        File(folder, "source-123.jpg").writeText("PRIVATE_IMAGE_BYTES")
        val reopened = HdrDiagnosticJournal(folder)
        reopened.event("错误", "EGL_BAD_MATCH")
        val snapshot = reopened.snapshot(File(temporary.root, "export/report.txt"))
        val text = snapshot.readText(Charsets.UTF_8)
        assertTrue(text.contains("中文观感：没有变化"))
        assertTrue(text.contains("EGL_BAD_MATCH"))
        assertFalse(text.contains("PRIVATE_IMAGE_BYTES"))
        reopened.event("以后追加", "不应改变之前导出的文件")
        assertEquals(text, snapshot.readText(Charsets.UTF_8))
    }

    @Test fun concurrentRendererAndLifecycleEventsRemainWhole() {
        val journal = HdrDiagnosticJournal(temporary.newFolder("concurrent"))
        val reopened = HdrDiagnosticJournal(journal.directory)
        val worker = Executors.newFixedThreadPool(4)
        repeat(80) { n -> worker.execute { (if (n % 2 == 0) journal else reopened).event("EVENT_$n", "DETAIL_$n") } }
        worker.shutdown()
        assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS))
        val snapshot = journal.snapshot(File(temporary.root, "concurrent.txt")).readText()
        repeat(80) { n -> assertTrue(snapshot.contains("EVENT_$n\nDETAIL_$n\n\n")) }
        assertEquals(80, snapshot.split("EVENT_").size - 1)
    }

    @Test fun persistedSessionIdentifiersCannotEscapePrivateFolder() {
        assertTrue(HdrDiagnosticJournal.validId(HdrDiagnosticJournal.newId()))
        assertFalse(HdrDiagnosticJournal.validId("../../outside"))
        assertFalse(HdrDiagnosticJournal.validId(null))
        assertFalse(HdrDiagnosticJournal.validId("hdr-123-12345678/../outside"))
    }
}
