package com.ywwynm.everythingdone.views.recording.fablesol

import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.max
import kotlin.math.min

/**
 * 录音期间的设备重力方向序列，随 PCM 一同写进 WAV 自身的一个自定义 RIFF chunk。
 *
 * 它是**源数据**：丢了就再也拿不回当时的水体倾斜，因此不放派生缓存、也不放数据库，而是
 * 跟着音频走同一个文件——同步、改名、复制、分享、备份、删除都不需要额外的管理代码。
 *
 * chunk 置于 `data` chunk **之后**：RIFF 规范要求读者跳过不认识的 chunk，而"只认 `data`
 * 长度"这一实现远比"假设头恒为 44 字节"普遍（不解析 `data` 长度连时长都算不出来）。
 *
 * 载荷布局（全部小端）：
 * ```
 * 'E','D','m','o'   chunk id
 * uint32            chunk 载荷字节数
 * uint16            版本（当前 1）
 * uint16            采样率 Hz（当前 50）
 * uint32            采样点数
 * float32 x,y,z     × 采样点数
 * ```
 * 载荷恒为 `8 + 12n` 字节，天然偶数，无需 RIFF 补白。
 */
internal class FableSolGravityTrack private constructor(
    private val rateHz: Int,
    /** 逐点 x/y/z 连续存放，长度 = 3 × 采样点数。 */
    private val samples: FloatArray
) {

    val sampleCount: Int get() = samples.size / 3

    val durationSeconds: Double
        get() = if (rateHz <= 0) 0.0 else sampleCount.toDouble() / rateHz

    /**
     * 取音频时间 [seconds] 处的重力方向写入 [out]（零阶保持，与录制时的传感器语义一致）。
     * 轨迹为空时写入竖直向下 `(0, 1, 0)`——与 [AudioRecordDialogFragment] 停止倾斜时一致。
     */
    fun sampleAt(seconds: Double, out: FloatArray) {
        val count = sampleCount
        if (count <= 0 || rateHz <= 0) {
            out[0] = 0f
            out[1] = 1f
            out[2] = 0f
            return
        }
        val index = min(max((seconds * rateHz).toInt(), 0), count - 1)
        val offset = index * 3
        out[0] = samples[offset]
        out[1] = samples[offset + 1]
        out[2] = samples[offset + 2]
    }

    companion object {

        const val RATE_HZ = 50
        private const val VERSION = 1
        private const val HEADER_BYTES = 8
        private val CHUNK_ID = byteArrayOf(
            'E'.code.toByte(), 'D'.code.toByte(), 'm'.code.toByte(), 'o'.code.toByte()
        )
        /** 只扫这么多个 chunk，避免损坏文件把解析拖成死循环。 */
        private const val MAX_CHUNKS = 64

        /**
         * 从一个 WAV 里读出重力轨迹；没有该 chunk（本功能之前的历史录音）时返回 null，
         * 调用方按竖直渲染。任何解析异常都当作"没有轨迹"，绝不让它影响导出本身。
         */
        fun readFrom(file: File): FableSolGravityTrack? {
            if (!file.isFile || file.length() < 44L) return null
            var access: RandomAccessFile? = null
            try {
                access = RandomAccessFile(file, "r")
                val riff = ByteArray(12)
                if (access.read(riff) != 12) return null
                if (riff[0] != 'R'.code.toByte() || riff[1] != 'I'.code.toByte() ||
                    riff[2] != 'F'.code.toByte() || riff[3] != 'F'.code.toByte() ||
                    riff[8] != 'W'.code.toByte() || riff[9] != 'A'.code.toByte() ||
                    riff[10] != 'V'.code.toByte() || riff[11] != 'E'.code.toByte()
                ) return null

                val fileLength = file.length()
                var position = 12L
                val header = ByteArray(8)
                var scanned = 0
                while (position + 8 <= fileLength && scanned < MAX_CHUNKS) {
                    scanned++
                    access.seek(position)
                    if (access.read(header) != 8) return null
                    val size = readUInt32(header, 4)
                    if (size < 0L || position + 8 + size > fileLength) return null
                    if (header[0] == CHUNK_ID[0] && header[1] == CHUNK_ID[1] &&
                        header[2] == CHUNK_ID[2] && header[3] == CHUNK_ID[3]
                    ) {
                        return parsePayload(access, position + 8, size)
                    }
                    // RIFF chunk 按偶数字节对齐。
                    position += 8 + size + (size and 1L)
                }
            } catch (ignored: Throwable) {
                // 解析失败等同于没有轨迹。
            } finally {
                try {
                    access?.close()
                } catch (ignored: Throwable) {
                }
            }
            return null
        }

        private fun parsePayload(
            access: RandomAccessFile,
            offset: Long,
            size: Long
        ): FableSolGravityTrack? {
            if (size < HEADER_BYTES) return null
            access.seek(offset)
            val head = ByteArray(HEADER_BYTES)
            if (access.read(head) != HEADER_BYTES) return null
            val version = readUInt16(head, 0)
            if (version != VERSION) return null
            val rate = readUInt16(head, 2)
            if (rate <= 0) return null
            val count = readUInt32(head, 4)
            if (count <= 0L || count > Int.MAX_VALUE / 3L) return null
            val bytes = count * 12L
            if (HEADER_BYTES + bytes > size) return null
            val payload = ByteArray(bytes.toInt())
            var read = 0
            while (read < payload.size) {
                val n = access.read(payload, read, payload.size - read)
                if (n <= 0) return null
                read += n
            }
            val values = FloatArray(count.toInt() * 3)
            var cursor = 0
            for (index in values.indices) {
                values[index] = Float.fromBits(readInt32(payload, cursor))
                cursor += 4
            }
            return FableSolGravityTrack(rate, values)
        }

        private fun readUInt16(source: ByteArray, offset: Int): Int =
            (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

        private fun readInt32(source: ByteArray, offset: Int): Int =
            (source[offset].toInt() and 0xff) or
                ((source[offset + 1].toInt() and 0xff) shl 8) or
                ((source[offset + 2].toInt() and 0xff) shl 16) or
                ((source[offset + 3].toInt() and 0xff) shl 24)

        private fun readUInt32(source: ByteArray, offset: Int): Long =
            readInt32(source, offset).toLong() and 0xffffffffL
    }

    /**
     * 采集侧收集器。传感器回调本来就跑在 `FableSolTiltSensor` 独立线程上，这里只做一次
     * 数组追加，与渲染路径无关。
     */
    internal class Collector {

        private val lock = Any()
        private var timestamps = LongArray(INITIAL_CAPACITY)
        private var values = FloatArray(INITIAL_CAPACITY * 3)
        private var count = 0
        private var baseElapsedMs = 0L
        private var collecting = false
        // 最近一次读数（无论是否在录）。传感器早在准备态就已注册，因此录音开始时手上一定
        // 有一个当前值；没有它的话 t=0 会被零阶保持反填成第一个**未来**采样。
        private var lastX = 0f
        private var lastY = 1f
        private var lastZ = 0f
        private var hasLast = false

        /** 录音真正开始的那一刻调用；重录与取消都从这里重新起算。 */
        fun start() {
            synchronized(lock) {
                count = 0
                baseElapsedMs = SystemClock.elapsedRealtime()
                collecting = true
                // 先把"此刻的姿态"落成 t=0 的种子。
                timestamps[0] = 0L
                values[0] = lastX
                values[1] = lastY
                values[2] = lastZ
                count = 1
            }
        }

        fun stop() {
            synchronized(lock) { collecting = false }
        }

        fun offer(x: Float, y: Float, z: Float) {
            synchronized(lock) {
                lastX = x
                lastY = y
                lastZ = z
                hasLast = true
                if (!collecting) return
                if (count == timestamps.size) {
                    timestamps = timestamps.copyOf(timestamps.size * 2)
                    values = values.copyOf(values.size * 2)
                }
                timestamps[count] = SystemClock.elapsedRealtime() - baseElapsedMs
                val offset = count * 3
                values[offset] = x
                values[offset + 1] = y
                values[offset + 2] = z
                count++
            }
        }

        /**
         * 按 [RATE_HZ] 的定频栅格零阶保持重采样，输出完整 chunk（含 id 与长度头）。
         * 没有采样点时返回 null，调用方不写 chunk。
         */
        fun buildChunk(durationSeconds: Double): ByteArray? {
            val snapshotTimestamps: LongArray
            val snapshotValues: FloatArray
            val snapshotCount: Int
            synchronized(lock) {
                if (count <= 0) return null
                snapshotCount = count
                snapshotTimestamps = timestamps.copyOf(count)
                snapshotValues = values.copyOf(count * 3)
            }
            val gridCount = max(1, Math.round(durationSeconds * RATE_HZ).toInt())
            val payloadBytes = HEADER_BYTES + gridCount * 12
            val chunk = ByteArray(8 + payloadBytes)
            System.arraycopy(CHUNK_ID, 0, chunk, 0, 4)
            writeUInt32(chunk, 4, payloadBytes.toLong())
            writeUInt16(chunk, 8, VERSION)
            writeUInt16(chunk, 10, RATE_HZ)
            writeUInt32(chunk, 12, gridCount.toLong())

            var source = 0
            var cursor = 16
            for (grid in 0 until gridCount) {
                val atMs = grid * 1000L / RATE_HZ
                while (source + 1 < snapshotCount && snapshotTimestamps[source + 1] <= atMs) {
                    source++
                }
                val offset = source * 3
                writeInt32(chunk, cursor, snapshotValues[offset].toRawBits())
                writeInt32(chunk, cursor + 4, snapshotValues[offset + 1].toRawBits())
                writeInt32(chunk, cursor + 8, snapshotValues[offset + 2].toRawBits())
                cursor += 12
            }
            return chunk
        }

        private fun writeUInt16(target: ByteArray, offset: Int, value: Int) {
            target[offset] = (value and 0xff).toByte()
            target[offset + 1] = ((value shr 8) and 0xff).toByte()
        }

        private fun writeInt32(target: ByteArray, offset: Int, value: Int) {
            target[offset] = (value and 0xff).toByte()
            target[offset + 1] = ((value shr 8) and 0xff).toByte()
            target[offset + 2] = ((value shr 16) and 0xff).toByte()
            target[offset + 3] = ((value shr 24) and 0xff).toByte()
        }

        private fun writeUInt32(target: ByteArray, offset: Int, value: Long) {
            writeInt32(target, offset, value.toInt())
        }

        private companion object {
            const val INITIAL_CAPACITY = 1024
        }
    }
}
