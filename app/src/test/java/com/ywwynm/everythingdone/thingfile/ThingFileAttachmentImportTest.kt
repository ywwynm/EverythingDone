package com.ywwynm.everythingdone.thingfile

import android.content.res.Resources
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.DetailAttachmentMediaAppearance
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingCardAppearance
import com.ywwynm.everythingdone.taptransfer.protocol.TapFileTransfer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 执行真实清单读取、更新准备和外观映射，只省略应用启动及数据库写入。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = ThingFileAttachmentImportTest.TestApp::class)
class ThingFileAttachmentImportTest {
    private val context get() = RuntimeEnvironment.getApplication() as TestApp
    private val bytes = byteArrayOf(3, 6, 9)

    class TestApp : App() {
        override fun onCreate() { ReflectionHelpers.setStaticField(App::class.java, "app", this) }
        override fun getResources(): Resources = TestResources(super.getResources())
    }

    @Suppress("DEPRECATION")
    class TestResources(base: Resources) : Resources(base.assets, base.displayMetrics, base.configuration) {
        override fun getString(id: Int): String = if (id == R.string.base_signal) "`启q琼" else "resource-$id"
        override fun getColor(id: Int, theme: Theme?): Int = 0xff123456.toInt()
    }

    private fun manifest(entry: String, name: String): JSONObject {
        val thing = JSONObject().put(ThingFile.KEY_TYPE, Thing.NOTE)
            .put(ThingFile.KEY_TITLE, "收到的记事")
            .put(ThingFile.KEY_ATTACHMENTS, JSONArray().put(JSONObject()
                .put(ThingFile.KEY_ATTACHMENT_ENTRY, entry)
                .put(ThingFile.KEY_ATTACHMENT_NAME, name)
                .put(ThingFile.KEY_ATTACHMENT_TYPE, 0)))
            .put(ThingFile.KEY_CARD_APPEARANCE, JSONObject(ThingCardAppearance(mediaSourceKey = entry).toJson()))
            .put(ThingFile.KEY_DETAIL_APPEARANCE, JSONObject(DetailAttachmentMediaAppearance()
                .withSource(entry, DetailAttachmentMediaAppearance.SourceAppearance(fullSpanEnabled = true)).toJson()))
        return JSONObject().put(ThingFile.KEY_FORMAT, ThingFile.FORMAT)
            .put(ThingFile.KEY_VERSION, ThingFile.VERSION_THINGS_ONLY)
            .put(ThingFile.KEY_THINGS, JSONArray().put(thing))
    }

    private fun readArchive(entry: String, name: String): ThingFileReader.Result.Ok {
        val archive = ByteArrayOutputStream()
        ZipOutputStream(archive).use { zip ->
            zip.putNextEntry(ZipEntry(ThingFile.ENTRY_MANIFEST))
            zip.write(manifest(entry, name).toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(entry))
            zip.write(bytes)
            zip.closeEntry()
        }
        val result = ThingFileReader.read(context, ByteArrayInputStream(archive.toByteArray()))
        assertTrue("ZIP 附件应可读取，实际为 ${result.javaClass.simpleName}", result is ThingFileReader.Result.Ok)
        return result as ThingFileReader.Result.Ok
    }

    @Test fun archiveNameReachesUpdatePreparationAndBothAppearanceMappings() {
        val result = readArchive("attachments/0/0_pack.png", "原图.PNG")
        val pending = result.things.single()
        assertEquals(listOf("原图.PNG"), pending.attachmentNames)
        val prepared = ThingFileUpdate.prepare(pending)
        val file = prepared.files.single()
        assertEquals("原图.PNG", file.name)
        assertArrayEquals(bytes, file.readBytes())
        val key = "0${file.absolutePath}"
        assertEquals(key, prepared.content.thingCardAppearance.mediaSourceKey)
        assertTrue(prepared.content.detailAttachmentMediaAppearance.sources.getValue(key).fullSpanEnabled)
        ThingFileReader.cleanUp(result.tempDir)
        prepared.ensureFilesAvailable()
        prepared.discard()
        assertFalse(file.exists())
    }

    @Test fun archiveCanStageMaximumLengthOriginalNameBeforeImport() {
        val name = "b".repeat(251) + ".png"
        val result = readArchive("attachments/0/0_$name", name)
        val prepared = ThingFileUpdate.prepare(result.things.single())
        assertEquals(name, prepared.files.single().name)
        assertArrayEquals(bytes, prepared.files.single().readBytes())
        ThingFileReader.cleanUp(result.tempDir)
        prepared.discard()
    }

    @Test fun negotiatedBorrowedFileSurvivesPreparationAndCancellation() {
        val root = File(Def.getAppFileDir(context)!!).apply { mkdirs() }
        val local = File(root, "local.png").apply { writeBytes(bytes) }
        val modified = local.lastModified()
        val entry = "attachments/0/0_sender.png"
        val directory = File(context.cacheDir, "received").apply { mkdirs() }
        val received = TapFileTransfer.Received(manifest(entry, "对方名称.png").toString().toByteArray(),
            listOf(TapFileTransfer.Descriptor(entry, TapFileTransfer.identify(0, local))),
            mapOf(entry to local), setOf(entry), directory, 0)
        val parsed = ThingFileReader.readTransfer(context, received) as ThingFileReader.Result.Ok
        assertEquals(listOf("对方名称.png"), parsed.things.single().attachmentNames)
        val prepared = ThingFileUpdate.prepare(parsed.things.single())
        assertTrue(prepared.files.isEmpty())
        assertEquals("0${local.absolutePath}", prepared.content.thingCardAppearance.mediaSourceKey)
        prepared.discard()
        received.discard()
        assertArrayEquals(bytes, local.readBytes())
        assertEquals(modified, local.lastModified())
    }
}
