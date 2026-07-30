package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

/**
 * 静态 HDR 元数据的回读核对（fablesol-video-export D91、D166）。
 *
 * **淘汰性核对（[verify]）只在短探测产物（单帧小文件）上执行**。正式产物封装成功之后，
 * 附加解析（[inspect]）一律只进诊断与完成信息，不删除、不重编码、不改报失败（D142）——
 * 把数十分钟的完整渲染因为一次事后解析推倒重来，正是那条决定否定的行为。
 *
 * 核对接受两种有效承载：容器里的 mdcv/clli box，或编码器按 configure 注入生成的码流 SEI。
 * 容器里**什么都没有**不算失败：`MediaMuxer` 把 `KEY_HDR_STATIC_INFO` 落盘成容器级 box 是
 * 较新 Android 才有的行为，旧系统上实际承载的就是 SEI，而 SEI 在这一层读不到。只有容器
 * 明确给出了**冲突**的值才淘汰候选。
 */
internal object FableSolExportStaticMetadataCheck {

    /**
     * 容器写入器对最低母版亮度的已知换算。
     *
     * 平台结构里这个字段的单位是 0.0001 尼特，而 AOSP 的 MP4 写入器把它当成尼特再乘 10000
     * 落进 mdcv box——OPPO PLZ110（Android 16）上注入 `1`（= 0.0001 尼特）读回来是 `10000`
     * （= 1 尼特），同一台机器的码流 SEI 里仍是 `1`（2026-07-29 实测）。这是写入器的换算，
     * 不是我们写错了，因此核对必须同时接受两种读数。
     */
    const val CONTAINER_MIN_LUMINANCE_SCALE = 10000

    /** 25 字节 CTA-861.3 Static Metadata Descriptor ID 0。 */
    const val DESCRIPTOR_BYTES = 25

    /** 一次核对的结论。 */
    internal sealed class Outcome {
        /** 容器给出的描述符与应用生成的一致。 */
        object Match : Outcome()

        /** 容器没有携带描述符；按 D166，码流 SEI 是这条路上的有效承载，不判失败。 */
        object AbsentFromContainer : Outcome()

        /** 容器给出了冲突的值：该候选在短探测阶段被淘汰。 */
        data class Conflict(val detail: String) : Outcome()
    }

    /**
     * 逐字段比较两个描述符。
     *
     * 纯函数，JVM 可测——这段核对写错的后果是"每台设备都被判失败"或"每台设备都被判通过"，
     * 两者都不该只靠真机偶然发现。
     */
    fun compare(expected: ByteArray, actual: ByteArray): Outcome {
        if (actual.size < DESCRIPTOR_BYTES || expected.size < DESCRIPTOR_BYTES) {
            return Outcome.Conflict(
                "descriptor length ${actual.size}, expected $DESCRIPTOR_BYTES"
            )
        }
        val mismatches = ArrayList<String>(4)
        // 8 个色度分量 + 白点：字节 1..16，逐个 16 位小端。
        for (index in 0 until 8) {
            val offset = 1 + index * 2
            val want = readShort(expected, offset)
            val got = readShort(actual, offset)
            if (want != got) mismatches += "primary[$index] $got != $want"
        }
        compareField(expected, actual, 17, "maxDisplayLuminance", mismatches)
        compareField(expected, actual, 21, "maxCll", mismatches)
        compareField(expected, actual, 23, "maxFall", mismatches)
        // 最低母版亮度：接受原值，也接受容器写入器的 ×10000 换算。
        val wantMin = readShort(expected, 19)
        val gotMin = readShort(actual, 19)
        if (gotMin != wantMin &&
            gotMin != (wantMin * CONTAINER_MIN_LUMINANCE_SCALE and 0xFFFF)
        ) {
            mismatches += "minDisplayLuminance $gotMin != $wantMin"
        }
        return if (mismatches.isEmpty()) {
            Outcome.Match
        } else {
            Outcome.Conflict(mismatches.joinToString("; "))
        }
    }

    /**
     * 打开短探测产物，核对它的视频轨静态元数据。
     *
     * @return 冲突时返回可读的原因；一致或容器未携带时返回 null。
     */
    fun verify(file: File, expected: ByteBuffer): String? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            when (val outcome = readContainerOutcome(extractor, expected)) {
                is Outcome.Conflict -> outcome.detail
                else -> null
            }
        } catch (ignored: Throwable) {
            // 解析不出来不等于产物有问题（D166）：这一层读不到 SEI，而 SEI 在旧系统上正是
            // 实际承载。保守地判为"未携带"，不淘汰候选。
            null
        } finally {
            try {
                extractor.release()
            } catch (ignored: Throwable) {
            }
        }
    }

    /**
     * 打开**正式产物**，解析它的视频轨静态元数据（D166 第三条）。
     *
     * 纯诊断入口：调用方只把结论写进运行诊断与完成信息的补充说明，绝不据此删除产物、
     * 重新编码或改报失败（D142）。数据源用 content URI 而不是文件路径——API 29+ 的
     * MediaStore 产物取 `DATA` 列不可靠。解析只读容器头，GB 级产物上也在毫秒量级。
     *
     * @return 三种结论之一；解析本身失败（打不开、无视频轨等）时返回 null。
     */
    fun inspect(context: Context, uri: Uri, expected: ByteBuffer): Outcome? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            readContainerOutcome(extractor, expected)
        } catch (ignored: Throwable) {
            null
        } finally {
            try {
                extractor.release()
            } catch (ignored: Throwable) {
            }
        }
    }

    /** 两个入口共用的解析体：找第一条视频轨，取容器描述符并与预期比较。 */
    private fun readContainerOutcome(extractor: MediaExtractor, expected: ByteBuffer): Outcome {
        val expectedBytes = ByteArray(expected.remaining())
        expected.duplicate().get(expectedBytes)
        var actual: ByteArray? = null
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (!mime.startsWith("video/")) continue
            val buffer = try {
                format.getByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO)
            } catch (ignored: Throwable) {
                null
            } ?: continue
            actual = ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
            break
        }
        return compare(expectedBytes, actual ?: return Outcome.AbsentFromContainer)
    }

    private fun compareField(
        expected: ByteArray,
        actual: ByteArray,
        offset: Int,
        name: String,
        mismatches: MutableList<String>
    ) {
        val want = readShort(expected, offset)
        val got = readShort(actual, offset)
        if (want != got) mismatches += "$name $got != $want"
    }

    private fun readShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
}
