package com.ywwynm.everythingdone.spatial

import android.content.Context
import androidx.annotation.Keep
import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream

/**
 * QNN context binary（HTP 预编译图）的落盘与失效管理。
 *
 * 端上首次建 session 要做一次 HTP 图编译，实测 RF-DETR 312² 用 50.7 秒、
 * EdgeTAM 1024² 用 19.8 秒；复用 context binary 之后分别降到 0.40 秒和 0.25 秒
 * （D217/D219）。**没有这一层，NPU 路径在产品上不可用。**
 *
 * ORT 的产物是两个文件：`<stem>_ctx.onnx`（几 KB 的壳，引用外部数据）与
 * `<stem>_ctx_qnn.bin`（真正的 context binary，RF-DETR 为 69.2 MB）。两者必须成对。
 *
 * ## 失效判据
 *
 * context binary 是**对特定模型字节、特定 HTP 架构、特定运行组件**编译出来的产物，
 * 任何一维变化都可能让它加载失败或者更糟——静默算错。因此 [Key] 的每一项都进 marker，
 * 且回读时逐项比对，不匹配一律重编而不是"试试看"。
 */
object SpatialQnnContextStore {

    @Keep
    data class Key(
        /** 模型稳定 id，例如 `rf_detr_seg_nano`。 */
        val modelId: String,
        val modelVersion: String,
        /** 模型文件的 SHA-256：模型换了字节但 id/version 没动时也必须作废。 */
        val modelSha256: String,
        /** 形状档标识。动态形状模型钉档后每档一份产物。 */
        val shapeTag: String,
        /** HTP 架构，例如 `v73`。 */
        val dspArch: String,
        /** 运行组件包版本：ORT 与 QNN 库的版本一起变，编译产物不通用。 */
        val runtimePackageVersion: String
    )

    @Keep
    data class ReadyMarker(
        val schemaVersion: Int = MARKER_SCHEMA_VERSION,
        val key: Key,
        val contextModelName: String,
        val contextModelSizeBytes: Long,
        val binarySizeBytes: Long,
        val binaryName: String
    )

    private val gson = Gson()

    /**
     * 已就绪且校验通过的 context 模型文件；没有或不匹配返回 null（调用方应回落到重编）。
     */
    fun readyContextModel(context: Context, key: Key): File? {
        val directory = directoryFor(context, key)
        val marker = readMarker(directory) ?: return null
        if (marker.schemaVersion != MARKER_SCHEMA_VERSION) return null
        if (marker.key != key) return null
        val model = File(directory, marker.contextModelName)
        val binary = File(directory, marker.binaryName)
        // 只比长度，不比哈希：这两个文件在应用私有目录里，且每次生成都整目录重建；
        // 逐字节校验 69 MB 会把复用的 0.4 秒重新拖回秒级，得不偿失。
        if (!model.isFile || model.length() != marker.contextModelSizeBytes) return null
        if (!binary.isFile || binary.length() != marker.binarySizeBytes) return null
        return model
    }

    /**
     * 生成前调用：清空该 key 的目录并返回 ORT 的 `ep.context_file_path` 应该指向的路径。
     * 生成后必须调用 [commit]，否则下次仍视为未就绪。
     */
    fun prepareForCompile(context: Context, key: Key): File {
        val directory = directoryFor(context, key)
        if (directory.exists()) check(directory.deleteRecursively()) { "无法清理旧 QNN 编译产物" }
        check(directory.mkdirs()) { "无法创建 QNN 编译产物目录" }
        return File(directory, "${key.modelId}$CONTEXT_MODEL_SUFFIX")
    }

    /**
     * 编译完成后登记。找不到成对的 `.bin` 视为失败——ORT 在 embed_mode=0 下必然产出它，
     * 缺失说明这次编译没有真的落盘，此时**不能**留下一个会被后续误认为可用的 marker。
     */
    fun commit(context: Context, key: Key): ReadyMarker {
        val directory = directoryFor(context, key)
        val model = File(directory, "${key.modelId}$CONTEXT_MODEL_SUFFIX")
        check(model.isFile && model.length() > 0) { "QNN context 模型未生成" }
        val binary = directory.listFiles()
            ?.firstOrNull { it.name.endsWith(BINARY_SUFFIX) }
            ?: error("QNN context binary 未生成")
        val marker = ReadyMarker(
            key = key,
            contextModelName = model.name,
            contextModelSizeBytes = model.length(),
            binaryName = binary.name,
            binarySizeBytes = binary.length()
        )
        writeMarker(directory, marker)
        return marker
    }

    /** 模型更新或被删除时调用；也用于设置里的"清除 NPU 编译缓存"。 */
    fun invalidate(context: Context, modelId: String): Boolean {
        val root = rootDirectory(context)
        val targets = root.listFiles { file -> file.isDirectory && file.name.startsWith("$modelId-") }
            ?: return true
        return targets.all { it.deleteRecursively() }
    }

    fun deleteAll(context: Context): Boolean {
        val root = rootDirectory(context)
        return !root.exists() || root.deleteRecursively()
    }

    fun totalBytes(context: Context): Long = directoryBytes(rootDirectory(context))

    /**
     * 目录名把 key 的每一项都编进去。`modelId` 与 `shapeTag` 来自 App 内枚举，
     * `dspArch` 已由 [SpatialQnnSupport.isValidDspArch] 挡过白名单；其余字段仍统一
     * 做一次字符清洗，避免任何一处将来放宽后把路径分隔符带进来。
     */
    internal fun directoryName(key: Key): String = listOf(
        key.modelId,
        key.modelVersion,
        key.shapeTag,
        key.dspArch,
        key.runtimePackageVersion,
        key.modelSha256.take(SHA_PREFIX_LENGTH)
    ).joinToString("-") { sanitize(it) }

    private fun sanitize(value: String): String {
        check(value.isNotBlank()) { "QNN context key 含空字段" }
        // '.' 必须保留（modelVersion 是 1.0.0 这种），因此过滤挡不住 ".."，
        // 只能显式拒绝：这些字段都来自 App 内枚举，出现上跳序列意味着上游有缺陷，
        // 应当直接失败而不是清洗后继续。
        val cleaned = value.filter { it.isLetterOrDigit() || it == '.' || it == '_' }
        check(cleaned.isNotEmpty() && cleaned.any(Char::isLetterOrDigit)) {
            "QNN context key 字段非法：$value"
        }
        check(!cleaned.contains("..")) { "QNN context key 字段含上跳序列：$value" }
        return cleaned
    }

    private fun directoryFor(context: Context, key: Key): File =
        File(rootDirectory(context), directoryName(key))

    private fun rootDirectory(context: Context): File =
        File(context.noBackupFilesDir, "spatial-photo/qnn-context")

    private fun readMarker(directory: File): ReadyMarker? {
        val file = File(directory, READY_MARKER)
        if (!file.isFile || file.length() !in 1..MAX_MARKER_BYTES) return null
        return runCatching {
            gson.fromJson(file.readText(Charsets.UTF_8), ReadyMarker::class.java)
        }.getOrNull()
    }

    private fun writeMarker(directory: File, marker: ReadyMarker) {
        val file = File(directory, READY_MARKER)
        FileOutputStream(file).use { output ->
            output.write((gson.toJson(marker) + "\n").toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    private fun directoryBytes(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf(::directoryBytes) ?: 0L
    }

    private const val MARKER_SCHEMA_VERSION = 1
    private const val READY_MARKER = "ready.json"
    private const val CONTEXT_MODEL_SUFFIX = "_ctx.onnx"
    private const val BINARY_SUFFIX = ".bin"
    private const val MAX_MARKER_BYTES = 16L * 1024L
    private const val SHA_PREFIX_LENGTH = 16
}
