package com.ywwynm.everythingdone.views.recording.fablesol

import android.media.MediaCodec
import java.util.ArrayDeque

/**
 * 当前进程内最近一次导出链的技术记录。
 *
 * 主界面只显示最近一次公开规格失败；具体组件、输入通路、底层异常与连续尝试顺序保留在设备
 * 诊断中。记录不包含输入文件路径或其它用户数据，并采用有界队列，避免长期运行时无限增长。
 */
internal object FableSolExportRuntimeDiagnostics {

    private val entries = ArrayDeque<String>(MAX_ENTRIES)

    @Synchronized
    fun beginExport() {
        entries.clear()
        entries.addLast("开始新的导出尝试链。")
    }

    @Synchronized
    fun record(message: String) {
        val normalized = message
            .replace('\r', ' ')
            .replace('\n', ' ')
            .take(MAX_ENTRY_LENGTH)
        while (entries.size >= MAX_ENTRIES) entries.removeFirst()
        entries.addLast(normalized)
    }

    @Synchronized
    fun lines(): List<String> = entries.toList()

    fun specification(spec: FableSolExportPublicSpec): String =
        "${spec.format?.stableLabel ?: FableSolExportHdrFormat.SDR_LABEL} · " +
            "${spec.family.stableLabel} · ${if (spec.tenBit) "10-bit" else "8-bit"} · " +
            "${if (spec.softwareOnly) "软件编码" else "硬件编码"} · ${spec.frameRate} fps · " +
            spec.rateControl.stableId

    fun failure(error: Throwable): String = buildString {
        append(error.javaClass.simpleName)
        error.message?.takeIf { it.isNotBlank() }?.let {
            append("：").append(it)
        }
        val codec = error as? MediaCodec.CodecException ?: return@buildString
        append("；diagnosticInfo=").append(codec.diagnosticInfo)
        append("；errorCode=").append(codec.errorCode)
        append("；transient=").append(codec.isTransient)
        append("；recoverable=").append(codec.isRecoverable)
    }

    private const val MAX_ENTRIES = 64
    private const val MAX_ENTRY_LENGTH = 1000
}
