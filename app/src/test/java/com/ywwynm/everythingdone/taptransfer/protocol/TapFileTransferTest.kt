package com.ywwynm.everythingdone.taptransfer.protocol

import com.ywwynm.everythingdone.thingfile.ThingFileAttachmentStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TapFileTransferTest {
    @get:Rule val temp = TemporaryFolder()
    private fun file(name: String, bytes: ByteArray) = File(temp.root, name).apply { writeBytes(bytes) }
    private fun source(index: Int, file: File, type: Int = 1) =
        TapFileTransfer.source("attachments/0/${index}_${file.name}", type, file)
    private fun payload(vararg sources: TapFileTransfer.Source) =
        TapFileTransfer.Outgoing("{\"test\":true}".toByteArray(), sources.toList())

    private fun exchange(payload: TapFileTransfer.Outgoing, local: List<TapFileTransfer.LocalAttachment> = emptyList()): TapFileTransfer.Received {
        val pool = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                val sending = pool.submit<Long> {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val bytes = TapFileTransfer.send(socket.getInputStream(), socket.getOutputStream(), payload)
                        TapStream.readAck(socket.getInputStream())
                        bytes
                    }
                }
                val received = Socket(InetAddress.getLoopbackAddress(), server.localPort).use { socket ->
                    socket.soTimeout = 5000
                    TapFileTransfer.receive(socket.getInputStream(), socket.getOutputStream(),
                        File(temp.root, "receive-${UUID.randomUUID()}"), local).also {
                        TapStream.writeAck(socket.getOutputStream())
                    }
                }
                assertEquals(received.transferredBytes, sending.get(5, TimeUnit.SECONDS))
                return received
            }
        } finally { pool.shutdownNow() }
    }

    @Test fun reorderedAttachmentsReuseLocalPathsWithoutWritingOrSendingFiles() {
        val originals = (0..2).map { file("local-$it.mp4", ByteArray(300 + it) { n -> (n + it).toByte() }) }
        val stamps = originals.map { it.lastModified() }
        val outgoing = listOf(2, 0, 1).mapIndexed { index, old ->
            source(index, file("renamed-$index.mp4", originals[old].readBytes()))
        }
        val received = exchange(payload(*outgoing.toTypedArray()), originals.map { TapFileTransfer.LocalAttachment(1, it) })
        assertEquals(0L, received.transferredBytes)
        assertEquals(listOf(originals[2], originals[0], originals[1]), received.files.values.toList())
        assertEquals(stamps, originals.map { it.lastModified() })
        assertEquals(3, received.borrowedKeys.size)
        received.verifyBorrowed()
        received.discard()
        assertTrue(originals.all { it.isFile })
    }

    @Test fun onlyMissingOrChangedContentIsSentAndSameSizeIsNotEnough() {
        val existing = file("old.mp4", byteArrayOf(1, 2, 3))
        val same = source(0, file("same.mp4", existing.readBytes()))
        val changed = source(1, file("changed.mp4", byteArrayOf(1, 2, 4)))
        val added = source(2, file("new.png", byteArrayOf(9, 8, 7, 6)), 0)
        val received = exchange(payload(same, changed, added), listOf(TapFileTransfer.LocalAttachment(1, existing)))
        assertEquals(7L, received.transferredBytes)
        assertEquals(existing, received.files[same.descriptor.key])
        assertArrayEquals(changed.file.readBytes(), received.files.getValue(changed.descriptor.key).readBytes())
        assertTrue(received.files.getValue(added.descriptor.key).name.endsWith(".png"))
    }

    @Test fun duplicateContentTransfersOnceAndKeepsIndependentFiles() {
        val a = source(0, file("a.gif", ByteArray(129) { it.toByte() }), 0)
        val b = source(1, file("b.gif", a.file.readBytes()), 0)
        val received = exchange(payload(a, b))
        assertEquals(129L, received.transferredBytes)
        assertEquals(2, received.files.values.toSet().size)
        received.files.values.forEach { assertArrayEquals(a.file.readBytes(), it.readBytes()) }
    }

    @Test fun negotiatedNewFilesKeepOriginalNamesAndBorrowedFilesKeepLocalPaths() {
        val root = temp.newFolder()
        val images = File(root, "images").apply { mkdir() }
        val existing = File(images, "相片.PNG").apply { writeText("existing") }
        val borrowed = File(root, "videos/local.mp4").apply { parentFile.mkdirs(); writeText("video") }
        val borrowedStamp = borrowed.lastModified()
        val reused = source(0, file("renamed.mp4", borrowed.readBytes()))
        val first = source(1, file("相片.PNG", byteArrayOf(2, 3)), 0)
        val duplicate = source(2, file("另一张.PNG", first.file.readBytes()), 0)
        val outgoing = payload(reused, first, duplicate)
        val received = exchange(outgoing, listOf(TapFileTransfer.LocalAttachment(1, borrowed)))
        assertEquals(2L, received.transferredBytes)
        val entries = outgoing.sources.map { source ->
            val key = source.descriptor.key
            ThingFileAttachmentStore.Entry(key, source.descriptor.identity.type,
                received.files.getValue(key), source.file.name, key in received.borrowedKeys)
        }
        val moved = ThingFileAttachmentStore.move(entries, root, "`启q琼")
        assertEquals(listOf("相片-1.PNG", "另一张.PNG"), moved.newFiles.map { it.name })
        assertTrue(moved.newFiles.all { it.readBytes().contentEquals(first.file.readBytes()) })
        assertEquals(borrowed, moved.filesByKey.getValue(reused.descriptor.key))
        received.discard()
        assertEquals("existing", existing.readText())
        assertEquals("video", borrowed.readText())
        assertEquals(borrowedStamp, borrowed.lastModified())
        assertTrue(moved.newFiles.all { it.isFile })
    }

    @Test fun maximumLengthOriginalNameSurvivesDownloadAndDuplicateStaging() {
        val name = "a".repeat(251) + ".png"
        val first = source(0, file(name, byteArrayOf(3, 6, 9)), 0)
        val duplicate = source(1, first.file, 0)
        val received = exchange(payload(first, duplicate))
        assertEquals(3L, received.transferredBytes)
        assertEquals(2, received.files.values.toSet().size)
        assertTrue(received.files.values.all { it.name.toByteArray(Charsets.UTF_8).size <= 255 })
        val moved = ThingFileAttachmentStore.move(listOf(first, duplicate).map {
            ThingFileAttachmentStore.Entry(it.descriptor.key, 0,
                received.files.getValue(it.descriptor.key), name)
        }, temp.newFolder(), "`启q琼")
        assertEquals(name, moved.newFiles[0].name)
        assertTrue(moved.newFiles[1].name.endsWith("-1.png"))
        assertTrue(moved.newFiles.all { it.readBytes().contentEquals(first.file.readBytes()) })
    }

    @Test fun duplicateContentCanUseOneLocalFileWithoutSendingOrMergingEntries() {
        val a = source(0, file("a.mp4", byteArrayOf(1, 3)))
        val b = source(1, file("b.mp4", a.file.readBytes()))
        val local = file("local.mp4", a.file.readBytes())
        val received = exchange(payload(a, b), listOf(TapFileTransfer.LocalAttachment(1, local)))
        assertEquals(0L, received.transferredBytes)
        assertEquals(1, received.borrowedKeys.size)
        assertEquals(2, received.files.values.toSet().size)
        received.discard()
        assertTrue(local.isFile)
    }

    @Test fun missingLocalFileIsRequested() {
        val a = source(0, file("a.mp4", byteArrayOf(1, 3)))
        val received = exchange(payload(a), listOf(TapFileTransfer.LocalAttachment(1, File(temp.root, "gone.mp4"))))
        assertEquals(2L, received.transferredBytes)
    }

    @Test fun emptyAttachmentListCompletes() {
        val received = exchange(payload())
        assertEquals(0L, received.transferredBytes)
        assertTrue(received.files.isEmpty())
    }

    @Test fun changedBorrowedFileFailsVerificationAndIsNeverDeleted() {
        val a = source(0, file("a.mp4", byteArrayOf(1, 3)))
        val local = file("local.mp4", a.file.readBytes())
        val received = exchange(payload(a), listOf(TapFileTransfer.LocalAttachment(1, local)))
        local.writeBytes(byteArrayOf(9, 8))
        assertThrows(IOException::class.java) { received.verifyBorrowed() }
        received.discard()
        assertArrayEquals(byteArrayOf(9, 8), local.readBytes())
    }

    @Test fun corruptOrTruncatedDataIsRejectedAndOwnedFilesAreRemoved() {
        val a = source(0, file("a.mp4", byteArrayOf(1, 3, 5)))
        val request = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).apply { writeInt(1); writeInt(0) } }.toByteArray()
        val wire = ByteArrayOutputStream()
        TapFileTransfer.send(ByteArrayInputStream(request), wire, payload(a))
        val corrupt = wire.toByteArray().apply { this[lastIndex] = 99 }
        for (data in listOf(corrupt, wire.toByteArray().dropLast(1).toByteArray())) {
            val directory = File(temp.root, "broken-${UUID.randomUUID()}")
            assertThrows(IOException::class.java) {
                TapFileTransfer.receive(ByteArrayInputStream(data), ByteArrayOutputStream(), directory)
            }
            assertFalse(directory.exists())
            assertTrue(a.file.isFile)
        }
    }

    @Test fun duplicateRequestsAreRejected() {
        val a = source(0, file("a.mp4", byteArrayOf(1, 3)))
        val b = source(1, file("b.mp4", byteArrayOf(2, 4)))
        val request = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(2); writeInt(0); writeInt(0) } }
        assertThrows(IOException::class.java) {
            TapFileTransfer.send(ByteArrayInputStream(request.toByteArray()), ByteArrayOutputStream(), payload(a, b))
        }
    }
}
