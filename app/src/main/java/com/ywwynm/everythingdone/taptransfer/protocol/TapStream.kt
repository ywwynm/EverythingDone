package com.ywwynm.everythingdone.taptransfer.protocol

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Wi-Fi Direct 组内那条 TCP 连接上的帧格式。默认接收方主动连接发送方。
 * 接收方建组时，发送方主动连接并先写 EDTR 身份前导，随后两端沿用下列逻辑方向：
 *
 * ```
 * 接收方 → 发送方   "EDTS" | version u8 | token 16 字节 | receiverId 16 字节
 * 发送方 → 接收方   status u8 | length u64 | 元数据 length 字节
 * 接收方 → 发送方   所需附件编号（见 TapFileTransfer）
 * 发送方 → 接收方   请求的附件内容
 * 接收方 → 发送方   ACK（0x06），收完并校验文件之后才回
 * ```
 *
 * 发送方放行一条连接要两项都对上：令牌证明它碰过这台手机（组密码同一次分享里谁都一样）；
 * 接收方标识必须是发送方此刻正在等的那一台——碰上时在 HELLO 里报过的那个，一次 HELLO
 * 只放行一条连接。只读了握手、没发 HELLO 的读卡设备因此连不上来，超时之后才连上来的
 * 也会被拒绝（[STATUS_NOT_EXPECTED]）。
 *
 * 发送方要等到 ACK 才算发完——写进套接字只代表进了本机的发送缓冲区（验证程序正是因此
 * 量出过 500 MB/s 的假速率）。
 */
object TapStream {

    const val VERSION: Int = 2

    const val STATUS_OK: Int = 0
    const val STATUS_BAD_TOKEN: Int = 1

    /** 发送方此刻没有在等这一台：没发过 HELLO、已经超时，或者这次 HELLO 已经连上过一条。 */
    const val STATUS_NOT_EXPECTED: Int = 2

    /** 发送方的记事文件没写出来。 */
    const val STATUS_NO_PAYLOAD: Int = 3

    const val ACK: Int = 0x06

    /** 长度字段的合理上限。附件不设体积上限，这里只挡住明显损坏的长度。 */
    const val MAX_PAYLOAD_BYTES: Long = 64L * 1024 * 1024 * 1024

    private const val BUFFER: Int = 64 * 1024

    private val MAGIC = byteArrayOf('E'.code.toByte(), 'D'.code.toByte(), 'T'.code.toByte(), 'S'.code.toByte())
    private val SENDER_MAGIC = byteArrayOf('E'.code.toByte(), 'D'.code.toByte(), 'T'.code.toByte(), 'R'.code.toByte())

    /** 接收方连上来时报的身份：碰上时读到的令牌，与 HELLO 里报过的接收方标识。 */
    class ClientHello(val token: ByteArray, val receiverId: ByteArray)

    @JvmStatic
    fun writeClientHello(out: OutputStream, token: ByteArray, receiverId: ByteArray) {
        writeHello(out, token, receiverId, MAGIC)
    }

    /** 反向组网先由发送方证明自己持有本次 NFC 令牌，再沿用原文件协议。 */
    fun writeSenderHello(out: OutputStream, token: ByteArray, receiverId: ByteArray) {
        writeHello(out, token, receiverId, SENDER_MAGIC)
    }

    private fun writeHello(out: OutputStream, token: ByteArray, receiverId: ByteArray, magic: ByteArray) {
        require(token.size == TapHandshake.TOKEN_BYTES)
        require(receiverId.size == TapApdu.RECEIVER_ID_BYTES)
        val data = DataOutputStream(out)
        data.write(magic)
        data.writeByte(VERSION)
        data.write(token)
        data.write(receiverId)
        data.flush()
    }

    @JvmStatic
    fun readClientHello(input: InputStream): ClientHello {
        return readHello(input, MAGIC)
    }

    fun readSenderHello(input: InputStream): ClientHello = readHello(input, SENDER_MAGIC)

    private fun readHello(input: InputStream, expectedMagic: ByteArray): ClientHello {
        val data = DataInputStream(input)
        val magic = ByteArray(MAGIC.size)
        data.readFully(magic)
        if (!magic.contentEquals(expectedMagic)) {
            throw TapProtocolException("不是碰一碰连接")
        }
        val version = data.readUnsignedByte()
        if (version != VERSION) {
            throw TapProtocolException("连接版本不支持：$version")
        }
        val token = ByteArray(TapHandshake.TOKEN_BYTES)
        data.readFully(token)
        val receiverId = ByteArray(TapApdu.RECEIVER_ID_BYTES)
        data.readFully(receiverId)
        return ClientHello(token, receiverId)
    }

    @JvmStatic
    fun writeHeader(out: OutputStream, status: Int, length: Long) {
        val data = DataOutputStream(out)
        data.writeByte(status)
        data.writeLong(length)
        data.flush()
    }

    /** 返回将要收到的字节数；状态不是 [STATUS_OK] 时抛出。 */
    @JvmStatic
    fun readHeader(input: InputStream): Long {
        val data = DataInputStream(input)
        val status = data.readUnsignedByte()
        val length = data.readLong()
        if (status != STATUS_OK) {
            throw TapProtocolException("发送方拒绝：$status")
        }
        if (length <= 0 || length > MAX_PAYLOAD_BYTES) {
            throw TapProtocolException("长度不合理：$length")
        }
        return length
    }

    /**
     * 恰好复制 [length] 字节。对方提前断开时抛 [EOFException]——传了一半的内容一律作废
     * （决策 2026-09-24「传输失败时两边都回到等待」）。
     *
     * @param onProgress 每复制一段回调一次已复制的字节数，调用方自己决定多久刷新一次界面。
     */
    @JvmStatic
    fun copyExactly(input: InputStream, output: OutputStream, length: Long, onProgress: (Long) -> Unit) {
        val buffer = ByteArray(BUFFER)
        var done = 0L
        while (done < length) {
            val want = minOf(buffer.size.toLong(), length - done).toInt()
            val n = input.read(buffer, 0, want)
            if (n < 0) {
                throw EOFException("还差 ${length - done} 字节")
            }
            output.write(buffer, 0, n)
            done += n
            onProgress(done)
        }
        output.flush()
    }

    @JvmStatic
    fun writeAck(out: OutputStream) {
        out.write(ACK)
        out.flush()
    }

    @JvmStatic
    fun readAck(input: InputStream) {
        val b = input.read()
        if (b != ACK) {
            throw IOException(if (b < 0) "对方没有确认就断开了" else "确认字节不对：$b")
        }
    }

    /** 已回 ACK 的接收方保留网络，直到发送方关闭 TCP；调用方必须设置有界读超时。 */
    fun awaitSenderClose(input: InputStream) {
        if (input.read() != -1) throw TapProtocolException("文件完成后出现额外数据")
    }
}
