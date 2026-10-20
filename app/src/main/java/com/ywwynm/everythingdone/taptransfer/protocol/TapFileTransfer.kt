package com.ywwynm.everythingdone.taptransfer.protocol

import com.ywwynm.everythingdone.utils.AttachmentTempFiles
import java.io.*
import java.security.MessageDigest

/** TCP 身份校验后的附件协商。清单不包含本机路径；仅请求缺失内容，顺序不参与匹配。 */
object TapFileTransfer {
    private const val MAX_METADATA = 32 * 1024 * 1024
    private const val MAX_ATTACHMENTS = 100_000
    private const val RESERVE = 64L * 1024 * 1024

    data class Identity(val type: Int, val size: Long, val sha256: String)
    data class Descriptor(val key: String, val identity: Identity)
    data class LocalAttachment(val type: Int, val file: File)
    data class Source(val descriptor: Descriptor, val file: File, val modified: Long)
    data class Outgoing(val manifest: ByteArray, val sources: List<Source>) {
        val totalBytes: Long get() = manifest.size.toLong() + sources.sumOf { it.descriptor.identity.size }
        fun isUsable(): Boolean = sources.all { it.file.isFile &&
            it.file.length() == it.descriptor.identity.size && it.file.lastModified() == it.modified }
    }
    data class Received(val manifest: ByteArray, val descriptors: List<Descriptor>,
        val files: Map<String, File>, val borrowedKeys: Set<String>, val directory: File,
        val transferredBytes: Long) {
        /** 复用文件未转移所有权；读取来件时再次核实，清理只触及本次独占临时目录。 */
        fun verifyBorrowed() {
            val checked = HashSet<Pair<File, Identity>>()
            for (d in descriptors) if (d.key in borrowedKeys) {
                val file = files.getValue(d.key)
                if (checked.add(file to d.identity) && identify(d.identity.type, file) != d.identity)
                    throw IOException("复用附件已变化")
            }
        }
        fun discard() { directory.deleteRecursively() }
    }

    fun source(key: String, type: Int, file: File): Source {
        val modified = file.lastModified()
        val identity = identify(type, file)
        if (file.lastModified() != modified) throw IOException("附件在准备期间变化")
        return Source(Descriptor(key, identity), file, modified)
    }

    fun identify(type: Int, file: File): Identity {
        if (!file.isFile) throw IOException("附件不存在")
        val size = file.length()
        val modified = file.lastModified()
        val digest = MessageDigest.getInstance("SHA-256")
        var read = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                read += n
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
            }
        }
        if (read != size || file.length() != size || file.lastModified() != modified)
            throw IOException("附件在校验期间变化")
        return Identity(type, size, hex(digest.digest()))
    }

    /** 调用方仍负责最终 ACK 和 socket 生命周期。返回实际传输的附件字节数。 */
    fun send(input: InputStream, output: OutputStream, payload: Outgoing,
        progress: (Long, Long) -> Unit = { _, _ -> }): Long {
        if (!payload.isUsable()) throw IOException("待发送附件已变化")
        val metadata = encode(payload)
        TapStream.writeHeader(output, TapStream.STATUS_OK, metadata.size.toLong())
        output.write(metadata)
        output.flush()
        val request = DataInputStream(input)
        val count = request.readInt()
        if (count !in 0..payload.sources.size) throw IOException("附件请求数量不合法")
        val indices = List(count) { request.readInt() }
        if (indices.distinct().size != count || indices.any { it !in payload.sources.indices })
            throw IOException("附件请求编号不合法")
        val bytes = indices.sumOf { payload.sources[it].descriptor.identity.size }
        val total = metadata.size.toLong() + bytes
        var done = metadata.size.toLong()
        progress(done, total)
        for (index in indices) {
            val source = payload.sources[index]
            source.file.inputStream().use { from ->
                copyVerified(from, output, source.descriptor.identity) { n -> progress(done + n, total) }
            }
            done += source.descriptor.identity.size
        }
        // 即使本次文件全部复用，也不能把已知过期的发送清单当作成功。
        if (!payload.isUsable()) throw IOException("发送期间附件已变化")
        output.flush()
        progress(total, total)
        return bytes
    }

    fun receive(input: InputStream, output: OutputStream, directory: File,
        existing: List<LocalAttachment> = emptyList(),
        connected: (Long) -> Unit = {}, progress: (Long, Long) -> Unit = { _, _ -> }): Received {
        // 必须是本次独占目录，任何失败都只清理本次创建的文件。
        if (!directory.mkdir()) throw IOException("无法创建接收目录")
        try {
            val length = TapStream.readHeader(input)
            if (length > MAX_METADATA) throw IOException("附件清单过大")
            val metadata = ByteArray(length.toInt())
            DataInputStream(input).readFully(metadata)
            val (manifest, descriptors) = decode(metadata)
            val wantedSizes = descriptors.map { it.identity.type to it.identity.size }.toSet()
            val candidates = LinkedHashMap<Identity, MutableList<File>>()
            for (local in existing.distinctBy { it.file.absolutePath }) {
                if (!local.file.isFile || local.type to local.file.length() !in wantedSizes) continue
                // 已丢失或不可读的本地候选不阻止正常接收，向发送方请求该文件即可。
                val identity = try { identify(local.type, local.file) }
                    catch (e: InterruptedIOException) { throw e }
                    catch (_: IOException) { continue }
                    catch (_: SecurityException) { continue }
                candidates.getOrPut(identity) { ArrayList() }.add(local.file)
            }
            val requested = LinkedHashMap<Identity, Int>()
            descriptors.forEachIndexed { index, d ->
                if (candidates[d.identity].isNullOrEmpty()) requested.putIfAbsent(d.identity, index)
            }
            val request = DataOutputStream(output)
            request.writeInt(requested.size)
            requested.values.forEach { request.writeInt(it) }
            request.flush()
            val bytes = requested.keys.sumOf { it.size }
            val total = length + bytes
            connected(total)
            progress(length, total)
            val downloads = HashMap<Identity, File>()
            var done = length
            for ((identity, index) in requested) {
                checkSpace(directory)
                val file = AttachmentTempFiles.create(directory, descriptors[index].key)
                file.outputStream().use { to ->
                    copyVerified(input, to, identity, directory) { n -> progress(done + n, total) }
                }
                downloads[identity] = file
                done += identity.size
            }
            val used = HashSet<String>()
            val files = LinkedHashMap<String, File>()
            val borrowed = HashSet<String>()
            for (d in descriptors) {
                val local = candidates[d.identity].orEmpty()
                val available = local.firstOrNull { it.absolutePath !in used }
                val downloaded = downloads[d.identity]?.takeIf { it.absolutePath !in used }
                val file = available ?: downloaded ?: run {
                    // 多个同内容条目需要独立路径，否则按路径存储的裁剪、封面设置会互相覆盖。
                    val from = local.firstOrNull() ?: downloads.getValue(d.identity)
                    checkSpace(directory)
                    AttachmentTempFiles.create(directory, d.key).also { to ->
                        from.inputStream().use { src -> to.outputStream().use { dst ->
                            copyVerified(src, dst, d.identity, directory) {}
                        } }
                    }
                }
                used.add(file.absolutePath)
                files[d.key] = file
                if (available != null) borrowed.add(d.key)
            }
            progress(total, total)
            return Received(manifest, descriptors, files, borrowed, directory, bytes)
        } catch (e: Exception) {
            directory.deleteRecursively()
            throw e
        }
    }

    private fun encode(payload: Outgoing): ByteArray {
        validate(payload.manifest, payload.sources.map { it.descriptor })
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(payload.manifest.size)
            out.write(payload.manifest)
            out.writeInt(payload.sources.size)
            for (source in payload.sources) {
                val d = source.descriptor
                out.writeUTF(d.key)
                out.writeByte(d.identity.type)
                out.writeLong(d.identity.size)
                out.writeUTF(d.identity.sha256)
                if (bytes.size() > MAX_METADATA) throw IOException("附件清单过大")
            }
        }
        return bytes.toByteArray().also { if (it.size > MAX_METADATA) throw IOException("附件清单过大") }
    }

    private fun decode(metadata: ByteArray): Pair<ByteArray, List<Descriptor>> {
        val input = DataInputStream(ByteArrayInputStream(metadata))
        val length = input.readInt()
        if (length !in 1..MAX_METADATA || length > input.available()) throw IOException("记事清单长度不合法")
        val manifest = ByteArray(length).also { input.readFully(it) }
        val count = input.readInt()
        if (count !in 0..MAX_ATTACHMENTS || count > input.available() / 12) throw IOException("附件数量不合法")
        val descriptors = List(count) {
            Descriptor(input.readUTF(), Identity(input.readUnsignedByte(), input.readLong(), input.readUTF()))
        }
        if (input.available() != 0) throw IOException("附件清单包含额外数据")
        validate(manifest, descriptors)
        return manifest to descriptors
    }

    private fun validate(manifest: ByteArray, descriptors: List<Descriptor>) {
        if (manifest.isEmpty() || manifest.size > MAX_METADATA || descriptors.size > MAX_ATTACHMENTS)
            throw IOException("记事清单不合法")
        val keys = HashSet<String>()
        var total = 0L
        for (d in descriptors) {
            if (!keys.add(d.key) || !d.key.matches(Regex("attachments/[0-9]+/[0-9]+_[^/\\\\]+")) ||
                d.identity.type !in 0..2 || d.identity.size !in 0..TapStream.MAX_PAYLOAD_BYTES ||
                !d.identity.sha256.matches(Regex("[a-f0-9]{64}"))) throw IOException("附件描述不合法")
            total += d.identity.size
            if (total > TapStream.MAX_PAYLOAD_BYTES) throw IOException("附件总大小不合法")
        }
    }

    private fun copyVerified(input: InputStream, output: OutputStream, identity: Identity,
        directory: File? = null, progress: (Long) -> Unit) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var done = 0L
        var spaceChecked = 0L
        while (done < identity.size) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), identity.size - done).toInt())
            if (n < 0) throw EOFException("附件不完整")
            digest.update(buffer, 0, n)
            output.write(buffer, 0, n)
            done += n
            if (directory != null && done - spaceChecked >= 4L * 1024 * 1024) {
                checkSpace(directory)
                spaceChecked = done
            }
            progress(done)
        }
        if (hex(digest.digest()) != identity.sha256) throw IOException("附件内容校验失败")
    }

    private fun checkSpace(directory: File) {
        if (directory.usableSpace < RESERVE) throw IOException("no space left for attachments")
    }
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
