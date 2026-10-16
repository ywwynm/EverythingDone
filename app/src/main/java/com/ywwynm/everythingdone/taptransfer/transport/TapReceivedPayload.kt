package com.ywwynm.everythingdone.taptransfer.transport

import android.content.Context
import com.ywwynm.everythingdone.taptransfer.protocol.TapFileTransfer
import com.ywwynm.everythingdone.thingfile.ThingFileReader
import java.io.File

/** 来件所有权随回调交付。复用附件始终属于原记事，discard 仅清理本次独占文件。 */
sealed class TapReceivedPayload {
    abstract fun read(context: Context): ThingFileReader.Result
    abstract fun discard()
    abstract val transferredBytes: Long

    class Archive(private val file: File) : TapReceivedPayload() {
        override val transferredBytes get() = file.length()
        override fun read(context: Context) = file.inputStream().use { ThingFileReader.read(context, it) }
        override fun discard() { file.delete() }
    }

    class Files(val received: TapFileTransfer.Received) : TapReceivedPayload() {
        override val transferredBytes get() = received.transferredBytes
        override fun read(context: Context) = ThingFileReader.readTransfer(context, received)
        override fun discard() = received.discard()
    }
}
