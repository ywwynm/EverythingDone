package com.ywwynm.everythingdone.taptransfer.protocol

import java.nio.charset.StandardCharsets

/**
 * 碰一碰在 NFC 上交换的命令（ISO 7816-4 的 APDU 形式）。
 *
 * 接收方开读取模式，发送方由卡模拟服务应答。一次碰触依次是：
 *
 * ```
 * SELECT  00 A4 04 00 Lc AID 00         → version u8 | 握手字节数 u16 | 9000
 * GET     80 20 offHi offLo Le          → 握手的 [off, off+Le) 段 | 9000
 * HELLO   80 10 00 00 Lc id(16) name 00 → 9000：发送方就此认定碰上了这一台
 * ```
 *
 * GET 按段读，每段不超过 [MAX_CHUNK]，不依赖扩展长度 APDU——验证程序量到的两台都支持
 * 65279 字节，但不能假定别的设备也支持。HELLO 放在读完握手之后：接收方先确认这不是
 * 已经收过的那一次发送，再让发送方开始播放流出动画。
 */
object TapApdu {

    /**
     * F0（专有 RID 前缀）+ ASCII "EDTAP1"。验证程序用的是 F0 + "EDTRNS"，两者同时装着也不会
     * 抢同一个 AID。
     */
    @JvmField
    val AID: ByteArray = byteArrayOf(
        0xF0.toByte(), 0x45, 0x44, 0x54, 0x41, 0x50, 0x31
    )

    const val CLA_ISO: Int = 0x00
    const val CLA_PROPRIETARY: Int = 0x80
    const val INS_SELECT: Int = 0xA4
    const val INS_HELLO: Int = 0x10
    const val INS_GET: Int = 0x20

    /** 单段 GET 的最大长度，留在短 APDU 的 256 字节以内。 */
    const val MAX_CHUNK: Int = 240

    /** 接收方在 HELLO 里带的标识长度：每次进入接收状态随机生成一次。 */
    const val RECEIVER_ID_BYTES: Int = 16

    const val SW_OK: Int = 0x9000

    /** 6985 conditions of use not satisfied：发送方没打开分享界面，或还没准备好。 */
    const val SW_NOT_READY: Int = 0x6985

    /** 6A89 file already exists：这一台在这次发送里已经收过了。 */
    const val SW_ALREADY_RECEIVED: Int = 0x6A89

    /** 6A86 incorrect P1-P2：GET 的偏移越界。 */
    const val SW_WRONG_OFFSET: Int = 0x6A86

    /** 6700 wrong length。 */
    const val SW_WRONG_LENGTH: Int = 0x6700

    /** 6D00 instruction not supported。 */
    const val SW_UNKNOWN_INS: Int = 0x6D00

    /** 6A82 file not found：SELECT 的不是我们的 AID。 */
    const val SW_WRONG_AID: Int = 0x6A82

    /** 发送方正在给另一台发送。沿用 6985 之外的 6986（command not allowed）。 */
    const val SW_BUSY: Int = 0x6986

    @JvmStatic
    fun select(): ByteArray {
        val out = ByteArray(5 + AID.size + 1)
        out[0] = CLA_ISO.toByte()
        out[1] = INS_SELECT.toByte()
        out[2] = 0x04
        out[3] = 0x00
        out[4] = AID.size.toByte()
        System.arraycopy(AID, 0, out, 5, AID.size)
        out[out.size - 1] = 0x00
        return out
    }

    @JvmStatic
    fun get(offset: Int, length: Int): ByteArray {
        require(offset in 0..0xFFFF) { "偏移越界：$offset" }
        require(length in 1..MAX_CHUNK) { "长度越界：$length" }
        return byteArrayOf(
            CLA_PROPRIETARY.toByte(), INS_GET.toByte(),
            (offset shr 8).toByte(), offset.toByte(),
            length.toByte()
        )
    }

    @JvmStatic
    fun hello(receiverId: ByteArray, receiverName: String): ByteArray {
        require(receiverId.size == RECEIVER_ID_BYTES) { "接收方标识必须是 $RECEIVER_ID_BYTES 字节" }
        val name = TapHandshake.truncateUtf8(receiverName, TapHandshake.MAX_NAME_BYTES)
            .toByteArray(StandardCharsets.UTF_8)
        val lc = receiverId.size + name.size
        val out = ByteArray(5 + lc + 1)
        out[0] = CLA_PROPRIETARY.toByte()
        out[1] = INS_HELLO.toByte()
        out[2] = 0x00
        out[3] = 0x00
        out[4] = lc.toByte()
        System.arraycopy(receiverId, 0, out, 5, receiverId.size)
        System.arraycopy(name, 0, out, 5 + receiverId.size, name.size)
        out[out.size - 1] = 0x00
        return out
    }

    @JvmStatic
    fun response(data: ByteArray?, sw: Int): ByteArray {
        val n = data?.size ?: 0
        val out = ByteArray(n + 2)
        if (data != null) {
            System.arraycopy(data, 0, out, 0, n)
        }
        out[n] = (sw shr 8).toByte()
        out[n + 1] = sw.toByte()
        return out
    }

    @JvmStatic
    fun statusWord(response: ByteArray?): Int {
        if (response == null || response.size < 2) {
            return -1
        }
        val n = response.size
        return ((response[n - 2].toInt() and 0xFF) shl 8) or (response[n - 1].toInt() and 0xFF)
    }

    @JvmStatic
    fun data(response: ByteArray): ByteArray = response.copyOfRange(0, (response.size - 2).coerceAtLeast(0))

    /** 卡模拟端解析出来的一条命令。 */
    sealed class Command {
        /** [ours] 为 false 表示对方选的不是我们的 AID。 */
        class Select(val ours: Boolean) : Command()
        class Get(val offset: Int, val length: Int) : Command()
        class Hello(val receiverId: ByteArray, val receiverName: String) : Command()
        object Unknown : Command()
    }

    @JvmStatic
    fun parse(apdu: ByteArray?): Command {
        if (apdu == null || apdu.size < 4) {
            return Command.Unknown
        }
        val cla = apdu[0].toInt() and 0xFF
        val ins = apdu[1].toInt() and 0xFF
        if (cla == CLA_ISO && ins == INS_SELECT) {
            if (apdu.size < 5) {
                return Command.Select(false)
            }
            val lc = apdu[4].toInt() and 0xFF
            if (apdu.size < 5 + lc) {
                return Command.Select(false)
            }
            val aid = apdu.copyOfRange(5, 5 + lc)
            return Command.Select(aid.contentEquals(AID))
        }
        if (cla != CLA_PROPRIETARY) {
            return Command.Unknown
        }
        return when (ins) {
            INS_GET -> {
                if (apdu.size < 5) {
                    return Command.Unknown
                }
                val offset = ((apdu[2].toInt() and 0xFF) shl 8) or (apdu[3].toInt() and 0xFF)
                // 短 APDU 里 Le = 0 表示 256
                val le = (apdu[4].toInt() and 0xFF).let { if (it == 0) 256 else it }
                Command.Get(offset, le)
            }
            INS_HELLO -> {
                if (apdu.size < 5) {
                    return Command.Unknown
                }
                val lc = apdu[4].toInt() and 0xFF
                if (lc < RECEIVER_ID_BYTES || apdu.size < 5 + lc) {
                    return Command.Unknown
                }
                val id = apdu.copyOfRange(5, 5 + RECEIVER_ID_BYTES)
                val name = String(apdu, 5 + RECEIVER_ID_BYTES, lc - RECEIVER_ID_BYTES, StandardCharsets.UTF_8)
                Command.Hello(id, name)
            }
            else -> Command.Unknown
        }
    }
}
