package com.ywwynm.everythingdone.thingfile

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ThingFileAttachmentStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private fun entry(key: String, name: String, bytes: String = key) =
        ThingFileAttachmentStore.Entry(key, 0,
            temp.newFile().apply { writeText(bytes) }, name)

    private fun move(root: File, vararg entries: ThingFileAttachmentStore.Entry) =
        ThingFileAttachmentStore.move(entries.toList(), root, "`启q琼")

    @Test fun keepsManifestNameInsteadOfTemporaryDownloadName() {
        val root = temp.newFolder()
        val incoming = entry("attachments/0/0_旅行照片.PNG", "旅行照片.PNG")
        val moved = move(root, incoming)
        assertEquals("旅行照片.PNG", moved.filesByKey.getValue(incoming.key).name)
        assertEquals(incoming.key, moved.newFiles.single().readText())
    }

    @Test fun onlyCollisionsAddNumberBeforeExtensionWithoutOverwriting() {
        val root = temp.newFolder()
        val images = File(root, "images").apply { mkdir() }
        File(images, "照片.jpg").writeText("existing")
        File(images, "照片-1.jpg").writeText("existing-1")
        val moved = move(root, entry("a", "照片.jpg"), entry("b", "照片.jpg"))
        assertEquals(listOf("照片-2.jpg", "照片-3.jpg"), moved.newFiles.map { it.name })
        assertEquals("existing", File(images, "照片.jpg").readText())
        assertEquals("existing-1", File(images, "照片-1.jpg").readText())
        assertEquals(listOf("a", "b"), moved.newFiles.map { it.readText() })
    }

    @Test fun keepsBorrowedFilePathAndModificationTime() {
        val root = temp.newFolder()
        val borrowed = temp.newFile("local.mp4").apply { writeText("video") }
        val modified = borrowed.lastModified()
        val incoming = ThingFileAttachmentStore.Entry("reused", 1, borrowed, "对方的视频.mp4", true)
        val moved = move(root, incoming)
        assertEquals(borrowed, moved.filesByKey.getValue("reused"))
        assertEquals(modified, borrowed.lastModified())
        assertTrue(moved.newFiles.isEmpty())
    }

    @Test fun missingManifestNameFallsBackToOriginalPartOfEntryKey() {
        assertEquals("12_录音.M4A", ThingFileAttachmentStore.originalName(null, "attachments/3/0_12_录音.M4A"))
        assertEquals("原图.GIF", ThingFileAttachmentStore.originalName("", "attachments/0/2_原图.GIF"))
        assertEquals("清单名称.PNG", ThingFileAttachmentStore.originalName("清单名称.PNG", "attachments/0/0_其它.png"))
    }

    @Test fun repeatedBatchesAllocateNamesAcrossThings() {
        val root = temp.newFolder()
        val first = move(root, entry("one", "封面.gif"))
        val second = move(root, entry("two", "封面.gif"))
        assertEquals("封面.gif", first.newFiles.single().name)
        assertEquals("封面-1.gif", second.newFiles.single().name)
        assertEquals("one", first.newFiles.single().readText())
        assertEquals("two", second.newFiles.single().readText())
    }

    @Test fun keepsNamesWithoutExtensionAndHiddenNames() {
        val root = temp.newFolder()
        val names = listOf("录音", "录音", ".cover", ".cover", "图像.v2.PNG", "图像.v2.PNG")
        val moved = move(root, *names.mapIndexed { i, name -> entry("$i", name) }.toTypedArray())
        assertEquals(listOf("录音", "录音-1", ".cover", ".cover-1", "图像.v2.PNG", "图像.v2-1.PNG"),
            moved.newFiles.map { it.name })
    }

    @Test fun collisionsAreLocalToEachAttachmentTypeDirectory() {
        val root = temp.newFolder()
        val items = (0..2).map { entry("$it", "同名").copy(type = it) }
        val moved = move(root, *items.toTypedArray())
        assertEquals(listOf("images", "videos", "audios"), moved.newFiles.map { it.parentFile.name })
        assertTrue(moved.newFiles.all { it.name == "同名" })
    }

    @Test fun sameNamedDirectoryAlsoReservesTheName() {
        val root = temp.newFolder()
        val existing = File(root, "images/photo.png").apply { mkdirs() }
        val moved = move(root, entry("new", "photo.png"))
        assertEquals("photo-1.png", moved.newFiles.single().name)
        assertTrue(existing.isDirectory)
    }

    @Test fun untrustedNamesStayInsideTypeDirectoryAndCannotBreakAttachmentEncoding() {
        val root = temp.newFolder()
        val names = listOf("../../相片.jpg", "C:\\folder\\录音.wav", "..", "", "a\u0000?*.png", "a`启q琼b.gif")
        val moved = move(root, *names.mapIndexed { i, name -> entry("$i", name) }.toTypedArray())
        assertEquals(listOf("相片.jpg", "录音.wav", "attachment.jpg", "attachment-1.jpg", "a___.png", "a_b.gif"),
            moved.newFiles.map { it.name })
        assertTrue(moved.newFiles.all { it.canonicalFile.parentFile == File(root, "images").canonicalFile })
    }

    @Test fun longUnicodeNamesKeepExtensionAndFitAfterAddingSuffix() {
        val root = temp.newFolder()
        val name = "图😀".repeat(36) + ".PNG"
        val moved = move(root, entry("a", name), entry("b", name))
        val files = moved.newFiles
        assertTrue(files[0].name.endsWith(".PNG"))
        assertTrue(files[1].name.endsWith("-1.PNG"))
        assertTrue(files.all { it.name.toByteArray(Charsets.UTF_8).size <= 255 })
        assertTrue(files.all { it.name == it.name.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) })
        assertEquals(listOf("a", "b"), files.map { it.readText() })
    }

    @Test fun failureRemovesNewFilesButPreservesCollisionsAndBorrowedFiles() {
        val root = temp.newFolder()
        val images = File(root, "images").apply { mkdir() }
        val existing = File(images, "photo.jpg").apply { writeText("existing") }
        val borrowed = File(images, "local.jpg").apply { writeText("borrowed") }
        val borrowedStamp = borrowed.lastModified()
        val missing = ThingFileAttachmentStore.Entry("missing", 0, File(temp.root, "missing.jpg"))
        assertThrows(IOException::class.java) {
            move(root, ThingFileAttachmentStore.Entry("borrowed", 0, borrowed, reused = true),
                entry("new", "photo.jpg"), missing)
        }
        assertEquals(setOf("photo.jpg", "local.jpg"), images.list()!!.toSet())
        assertEquals("existing", existing.readText())
        assertEquals("borrowed", borrowed.readText())
        assertEquals(borrowedStamp, borrowed.lastModified())
    }

    @Test fun missingBorrowedFileAlsoRollsBackOwnedFiles() {
        val root = temp.newFolder()
        val missing = ThingFileAttachmentStore.Entry("missing", 1, File(temp.root, "missing.mp4"), reused = true)
        assertThrows(IOException::class.java) { move(root, entry("new", "new.png"), missing) }
        assertTrue(File(root, "images").list()!!.isEmpty())
    }

    @Test fun concurrentImportsNeverOverwriteEachOther() {
        val root = temp.newFolder()
        val items = (0 until 12).map { entry("$it", "相片.png") }
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = items.map { item -> pool.submit<File> {
                start.await()
                move(root, item).newFiles.single()
            } }
            start.countDown()
            val files = results.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(12, files.toSet().size)
            assertEquals(items.map { it.key }, files.map { it.readText() })
            assertEquals((0 until 12).map { if (it == 0) "相片.png" else "相片-$it.png" }.toSet(),
                files.map { it.name }.toSet())
        } finally {
            pool.shutdownNow()
        }
    }
}
