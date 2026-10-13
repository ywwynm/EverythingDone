package com.ywwynm.everythingdone.taptransfer.protocol

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** 对方发来的内容不符合协议：版本不对、长度越界、字段缺失。 */
class TapProtocolException(message: String) : IOException(message)

/**
 * 碰一碰时经 NFC 交给接收方的握手内容。
 *
 * 接收方据它按 SSID 与密码直接加入发送方的 Wi-Fi Direct 组、连上 [port]，再用 [token] 证明
 * 自己就是碰过的那一台。[colors] 让接收方在数据到达之前就把屏幕边框换成这几条记事的颜色；
 * [aspects] 与 [fullSpan] 让它在数据到达之前就能预计这几张卡片会落在列表的哪里、多大（粒子从天线处
 * 朝那里飘）。
 *
 * 二进制布局，整数一律大端：
 *
 * ```
 * "EDTP"          4 字节标识
 * version         u8
 * token           16 字节
 * port            u16
 * ssid            str
 * passphrase      str
 * senderName      str
 * thingCount      u16
 * colorCount      u8，最多 MAX_COLORS
 * colors          i32 × colorCount（ARGB）
 * —— 以下可选（2026-09-25 加，旧版发送方没有；读到末尾即当作没有，协议版本不变）——
 * cardCount       u8，最多 MAX_COLORS
 * cards           (aspect u16，高宽比 × 1000；fullSpan u8，1 = 通栏) × cardCount
 * —— 以下可选（调试兼容接收；旧读取器读完 cards 后忽略尾部）——
 * "WFIP"          4 字节扩展标识
 * groupOwnerIpv4  str，发送组的实际本地 IPv4；没有 cards 时先写 cardCount=0
 * —— 以下可选（2026-10-13 系统连接确认等待；旧读取器忽略尾部）——
 * "WFWT"          4 字节扩展标识
 * wifiWaitMs      i32，发送端从 HELLO 起保留连接的毫秒数；缺省 25000
 *
 * str = u16 字节数 + UTF-8 字节
 * ```
 */
class TapHandshake(
    val token: ByteArray,
    val port: Int,
    val ssid: String,
    val passphrase: String,
    val senderName: String,
    val thingCount: Int,
    val colors: IntArray,
    /** 前几条记事的卡片高宽比（高 / 宽，按发送方列表里的样子量），最多 [MAX_COLORS] 条；没有时为空。 */
    val aspects: FloatArray = FloatArray(0),
    /** 与 [aspects] 同序：这条记事的卡片是否通栏。 */
    val fullSpan: BooleanArray = BooleanArray(0),
    /** 普通 Wi-Fi 兼容接收使用；旧握手没有此字段，不能据此猜测组地址。 */
    val groupOwnerIpv4: String? = null,
    /** 只在普通 Wi-Fi 兼容连接时延长接收等待；发送方必须兑现所声明的期限。 */
    val wifiConnectionTimeoutMs: Int = DEFAULT_CONNECTION_WAIT_MS
) {

    init {
        require(token.size == TOKEN_BYTES) { "token 必须是 $TOKEN_BYTES 字节" }
        require(port in 1..0xFFFF) { "端口越界：$port" }
        require(thingCount in 1..0xFFFF) { "条数越界：$thingCount" }
        require(colors.size <= MAX_COLORS) { "颜色最多 $MAX_COLORS 个" }
        require(aspects.size <= MAX_COLORS && fullSpan.size == aspects.size) { "卡片形状最多 $MAX_COLORS 条，两项须同样多" }
        require(aspects.all { it.isFinite() && it > 0f }) { "高宽比须为正" }
        require(groupOwnerIpv4 == null || isLocalIpv4(groupOwnerIpv4)) { "组地址须为本地 IPv4 地址" }
        require(wifiConnectionTimeoutMs in DEFAULT_CONNECTION_WAIT_MS..MAX_CONNECTION_WAIT_MS) { "连接等待期限越界" }
        require(groupOwnerIpv4 != null || wifiConnectionTimeoutMs == DEFAULT_CONNECTION_WAIT_MS) { "扩展等待需要兼容组地址" }
        // SSID 与密码截断之后就入不了组，不合规只能是调用方的错误，不能静默截掉
        credentialProblem(ssid, passphrase)?.let { throw IllegalArgumentException(it) }
    }

    fun encode(): ByteArray {
        val bytes = ByteArrayOutputStream(128)
        DataOutputStream(bytes).use { out ->
            out.write(MAGIC)
            out.writeByte(VERSION)
            out.write(token)
            out.writeShort(port)
            writeString(out, ssid, MAX_SSID_BYTES)
            writeString(out, passphrase, MAX_PASSPHRASE_BYTES)
            // 只有设备名允许截断：它只用来显示
            writeString(out, senderName, MAX_NAME_BYTES)
            out.writeShort(thingCount)
            out.writeByte(colors.size)
            for (color in colors) {
                out.writeInt(color)
            }
            if (aspects.isNotEmpty() || groupOwnerIpv4 != null) {
                out.writeByte(aspects.size)
                for (k in aspects.indices) {
                    out.writeShort((aspects[k] * ASPECT_SCALE + .5f).toInt().coerceIn(1, 0xFFFF))
                    out.writeByte(if (fullSpan[k]) 1 else 0)
                }
            }
            if (groupOwnerIpv4 != null) {
                out.writeInt(WIFI_ENDPOINT_MAGIC)
                writeString(out, groupOwnerIpv4, MAX_IPV4_BYTES)
                if (wifiConnectionTimeoutMs != DEFAULT_CONNECTION_WAIT_MS) {
                    out.writeInt(WIFI_WAIT_MAGIC)
                    out.writeInt(wifiConnectionTimeoutMs)
                }
            }
        }
        return bytes.toByteArray()
    }

    companion object {

        const val VERSION: Int = 1

        const val TOKEN_BYTES: Int = 16
        const val DEFAULT_CONNECTION_WAIT_MS = 25_000
        const val MAX_CONNECTION_WAIT_MS = 90_000

        /** 最多带这么多条记事的颜色（接收方给边框换色用）；更多的条数只影响计数。 */
        const val MAX_COLORS: Int = 12

        /** 高宽比按千分之一存成 u16：最大 65.535，足够任何一张卡片。 */
        private const val ASPECT_SCALE: Float = 1000f
        private const val WIFI_ENDPOINT_MAGIC: Int = 0x57464950 // WFIP
        private const val WIFI_WAIT_MAGIC: Int = 0x57465754 // WFWT
        private const val MAX_IPV4_BYTES: Int = 15

        /** Wi-Fi Direct 的 SSID 最长 32 字节，密码 8 到 63 个字符（WPA2 口令的范围）。 */
        internal const val MAX_SSID_BYTES: Int = 32
        private const val MIN_PASSPHRASE_CHARS: Int = 8
        internal const val MAX_PASSPHRASE_BYTES: Int = 63

        /** 与 `WifiP2pConfig.Builder.setNetworkName` 的校验一致：必须以 `DIRECT-xy` 开头。 */
        private val GROUP_NAME = Regex("^DIRECT-[a-zA-Z0-9]{2}.*")

        /** 设备名只用来显示「正在发送给某设备」，过长的截掉。 */
        const val MAX_NAME_BYTES: Int = 96

        private val MAGIC = byteArrayOf('E'.code.toByte(), 'D'.code.toByte(), 'T'.code.toByte(), 'P'.code.toByte())

        @JvmStatic
        fun decode(bytes: ByteArray): TapHandshake {
            val buffer = ByteBuffer.wrap(bytes)
            try {
                val magic = ByteArray(MAGIC.size)
                buffer.get(magic)
                if (!magic.contentEquals(MAGIC)) {
                    throw TapProtocolException("不是碰一碰握手")
                }
                val version = buffer.get().toInt() and 0xFF
                if (version != VERSION) {
                    throw TapProtocolException("握手版本不支持：$version")
                }
                val token = ByteArray(TOKEN_BYTES)
                buffer.get(token)
                val port = buffer.short.toInt() and 0xFFFF
                val ssid = readString(buffer, MAX_SSID_BYTES)
                val passphrase = readString(buffer, MAX_PASSPHRASE_BYTES)
                val senderName = readString(buffer, MAX_NAME_BYTES)
                val thingCount = buffer.short.toInt() and 0xFFFF
                val colorCount = buffer.get().toInt() and 0xFF
                if (colorCount > MAX_COLORS) {
                    throw TapProtocolException("颜色数越界：$colorCount")
                }
                val colors = IntArray(colorCount) { buffer.int }
                var aspects = FloatArray(0)
                var fullSpan = BooleanArray(0)
                if (buffer.hasRemaining()) {
                    val cardCount = buffer.get().toInt() and 0xFF
                    if (cardCount > MAX_COLORS) {
                        throw TapProtocolException("卡片形状数越界：$cardCount")
                    }
                    aspects = FloatArray(cardCount)
                    fullSpan = BooleanArray(cardCount)
                    for (k in 0 until cardCount) {
                        val aspect = buffer.short.toInt() and 0xFFFF
                        if (aspect == 0) {
                            throw TapProtocolException("高宽比为 0")
                        }
                        aspects[k] = aspect / ASPECT_SCALE
                        fullSpan[k] = (buffer.get().toInt() and 0xFF) == 1
                    }
                }
                if (port == 0 || thingCount == 0) {
                    throw TapProtocolException("握手字段缺失")
                }
                // 这两项原样交给 WifiP2pConfig.Builder，不合规的值在那里会抛异常：在这里就拒绝
                credentialProblem(ssid, passphrase)?.let { throw TapProtocolException(it) }
                var groupOwnerIpv4: String? = null
                var wifiWaitMs = DEFAULT_CONNECTION_WAIT_MS
                if (buffer.remaining() >= 4 && buffer.int == WIFI_ENDPOINT_MAGIC) {
                    groupOwnerIpv4 = readString(buffer, MAX_IPV4_BYTES)
                    if (!isLocalIpv4(groupOwnerIpv4)) throw TapProtocolException("组地址不是本地 IPv4 地址")
                    if (buffer.remaining() >= 4 && buffer.int == WIFI_WAIT_MAGIC) {
                        wifiWaitMs = buffer.int
                        if (wifiWaitMs !in DEFAULT_CONNECTION_WAIT_MS..MAX_CONNECTION_WAIT_MS) {
                            throw TapProtocolException("连接等待期限越界")
                        }
                    }
                }
                return TapHandshake(token, port, ssid, passphrase, senderName, thingCount, colors,
                    aspects, fullSpan, groupOwnerIpv4, wifiWaitMs)
            } catch (e: BufferUnderflowException) {
                throw TapProtocolException("握手不完整")
            }
        }

        /** 只允许字面形式的本地单播地址，不做 DNS 解析，也不接受环回／公网／多播目标。 */
        fun isLocalIpv4(address: String): Boolean {
            val parts = address.split('.')
            if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 ||
                    (it.length > 1 && it[0] == '0') || it.any { c -> c !in '0'..'9' } }) return false
            val octets = parts.map { it.toInt() }
            if (octets.any { it !in 0..255 }) return false
            return octets[0] == 10 ||
                (octets[0] == 172 && octets[1] in 16..31) ||
                (octets[0] == 192 && octets[1] == 168) ||
                (octets[0] == 169 && octets[1] == 254)
        }

        /**
         * 组名与密码能不能直接交给 `WifiP2pConfig.Builder`（它对不合规的值抛异常）。
         * 返回不合规的原因；合规时返回 null。
         */
        @JvmStatic
        fun credentialProblem(ssid: String, passphrase: String): String? = when {
            ssid.toByteArray(StandardCharsets.UTF_8).size > MAX_SSID_BYTES -> "SSID 过长"
            !GROUP_NAME.matches(ssid) -> "SSID 不是 Wi-Fi Direct 组名"
            passphrase.length < MIN_PASSPHRASE_CHARS -> "密码过短"
            passphrase.toByteArray(StandardCharsets.UTF_8).size > MAX_PASSPHRASE_BYTES -> "密码过长"
            else -> null
        }

        /**
         * 按 UTF-8 字节数截断，不切开一个码位：截在代理对中间会留下半个字符，
         * 对方解码出来是替换符。
         */
        @JvmStatic
        fun truncateUtf8(s: String, maxBytes: Int): String {
            if (s.toByteArray(StandardCharsets.UTF_8).size <= maxBytes) {
                return s
            }
            val sb = StringBuilder()
            var used = 0
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                val n = String(Character.toChars(cp)).toByteArray(StandardCharsets.UTF_8).size
                if (used + n > maxBytes) {
                    break
                }
                sb.appendCodePoint(cp)
                used += n
                i += Character.charCount(cp)
            }
            return sb.toString()
        }

        private fun writeString(out: DataOutputStream, s: String, maxBytes: Int) {
            val bytes = truncateUtf8(s, maxBytes).toByteArray(StandardCharsets.UTF_8)
            out.writeShort(bytes.size)
            out.write(bytes)
        }

        /**
         * 严格按 UTF-8 解码：宽松解码会把非法字节换成三字节的替换符，字符串变长之后可能越过
         * 构造时的长度检查。握手来自碰上来的任何设备，不能假定它是完事儿写的。
         */
        private fun readString(buffer: ByteBuffer, maxBytes: Int): String {
            val length = buffer.short.toInt() and 0xFFFF
            if (length > maxBytes) {
                throw TapProtocolException("字符串过长：$length")
            }
            val bytes = ByteArray(length)
            buffer.get(bytes)
            return try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (e: CharacterCodingException) {
                throw TapProtocolException("字符串不是合法的 UTF-8")
            }
        }
    }
}
