package com.ywwynm.everythingdone.spatial

import android.content.Context
import androidx.annotation.Keep
import com.google.gson.Gson
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * AI Hub 预编译的 NPU context 产物（`--target_runtime precompiled_qnn_onnx`）。
 *
 * 与 [SpatialQnnContextStore] 的分工：那个存的是**端上现编**的结果，键里带
 * `runtimePackageVersion` 与模型 sha，换任一个都作废重编；这个存的是**下发下来**的，
 * 由 catalog 的签名保证内容，键只有 (modelId, modelVersion, dspArch)。
 *
 * 有预编译产物就不必在设备上编译——Big-LaMa 的图在端上编译会被 LMK 杀掉（D250 之前
 * 实测），AI Hub 上即使降到 optimization level 1 也要几十分钟，端上没有可能。
 *
 * **QAIRT 版本必须与运行组件一致**，否则建 session 时报 `Error code: 5000`（D252）。
 * 该校验在 [SpatialQnnPrecompiledCatalogEntry.isCompatible] 里，这里只负责落盘与取用。
 */
object SpatialQnnPrecompiledStore {

    private val gson = Gson()

    @Keep
    data class ReadyMarker(
        val schemaVersion: Int = MARKER_SCHEMA_VERSION,
        val modelId: String,
        val modelVersion: String,
        val dspArch: String,
        val qairtVersion: String,
        val contextModelName: String,
        val contextModelSizeBytes: Long,
        val contextModelSha256: String,
        val contextBinaryName: String,
        val contextBinarySizeBytes: Long,
        val contextBinarySha256: String
    )

    /**
     * 可直接建 session 的 EPContext 模型；没有装好就返回 null，调用方照常走现编或 CPU。
     *
     * `model.onnx` 里的 `ep_cache_context` 是**相对路径**（`./model.bin`），所以两个文件
     * 必须留在同一个目录里，不能只把 onnx 拷出去。
     */
    fun contextModel(
        context: Context,
        modelId: String,
        modelVersion: String,
        dspArch: String
    ): File? {
        val directory = directoryFor(context, modelId, modelVersion, dspArch)
        val marker = readMarker(directory) ?: return null
        if (marker.modelId != modelId ||
            marker.modelVersion != modelVersion ||
            marker.dspArch != dspArch ||
            marker.qairtVersion != SpatialRuntimeStore.QNN_QAIRT_VERSION
        ) {
            return null
        }
        val model = File(directory, marker.contextModelName)
        val binary = File(directory, marker.contextBinaryName)
        if (!model.isFile || model.length() != marker.contextModelSizeBytes) return null
        if (!binary.isFile || binary.length() != marker.contextBinarySizeBytes) return null
        return model
    }

    fun isInstalled(
        context: Context,
        modelId: String,
        modelVersion: String,
        dspArch: String
    ): Boolean = contextModel(context, modelId, modelVersion, dspArch) != null

    /**
     * 解包并校验。zip 里只允许 catalog 明确描述的那两个入口，多一个都拒。
     */
    @Synchronized
    fun installVerified(
        context: Context,
        entry: SpatialQnnPrecompiledCatalogEntry,
        archive: File
    ) {
        check(entry.isCompatible()) { "NPU 预编译产物与当前 App 不兼容" }
        check(entry.dspArch == SpatialQnnSupport.resolveDspArch()) {
            "NPU 预编译产物的 HTP 架构与设备不匹配"
        }
        check(archive.isFile && archive.length() == entry.sizeBytes) {
            "NPU 预编译产物下载字节数不符"
        }
        check(SpatialModelStore.sha256(archive).equals(entry.sha256, ignoreCase = true)) {
            "NPU 预编译产物 SHA-256 不符"
        }

        val target = directoryFor(context, entry.modelId, entry.modelVersion, entry.dspArch)
        val root = checkNotNull(target.parentFile)
        check(root.exists() || root.mkdirs()) { "无法创建 NPU 预编译目录" }
        val pending = File(root, ".pending-${entry.dspArch}-${System.nanoTime()}")
        check(pending.mkdirs()) { "无法创建 NPU 预编译临时目录" }
        try {
            extract(archive, pending, entry)
            writeMarker(
                File(pending, READY_MARKER),
                ReadyMarker(
                    modelId = entry.modelId,
                    modelVersion = entry.modelVersion,
                    dspArch = entry.dspArch,
                    qairtVersion = entry.qairtVersion,
                    contextModelName = entry.contextModelName,
                    contextModelSizeBytes = entry.contextModelSizeBytes,
                    contextModelSha256 = entry.contextModelSha256.lowercase(),
                    contextBinaryName = entry.contextBinaryName,
                    contextBinarySizeBytes = entry.contextBinarySizeBytes,
                    contextBinarySha256 = entry.contextBinarySha256.lowercase()
                )
            )
            if (target.exists()) check(target.deleteRecursively()) { "无法替换旧的 NPU 预编译目录" }
            check(pending.renameTo(target)) { "无法提交 NPU 预编译目录" }
        } catch (error: Throwable) {
            pending.deleteRecursively()
            throw error
        }
    }

    fun partialFile(context: Context, entry: SpatialQnnPrecompiledCatalogEntry): File =
        File(
            context.noBackupFilesDir,
            "spatial-photo/downloads/qnn-precompiled-${entry.modelId}-" +
                "${entry.modelVersion}-${entry.dspArch}.zip.part"
        )

    fun delete(context: Context, modelId: String): Boolean {
        val root = File(rootDirectory(context), sanitize(modelId))
        return !root.exists() || root.deleteRecursively()
    }

    fun totalBytes(context: Context): Long = directoryBytes(rootDirectory(context))

    private fun extract(
        archive: File,
        pending: File,
        entry: SpatialQnnPrecompiledCatalogEntry
    ) {
        val expected = mapOf(
            entry.contextModelName to (entry.contextModelSizeBytes to entry.contextModelSha256),
            entry.contextBinaryName to (entry.contextBinarySizeBytes to entry.contextBinarySha256)
        )
        val extracted = mutableSetOf<String>()
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { input ->
            while (true) {
                val zipEntry = input.nextEntry ?: break
                if (zipEntry.isDirectory) {
                    input.closeEntry()
                    continue
                }
                // AI Hub 的 zip 把两个文件放在一层目录里，取 basename 后再比对白名单——
                // basename 本身已由 catalog 的正则限制过，不含分隔符与 `..`。
                val name = zipEntry.name.substringAfterLast('/')
                check(name in expected) { "NPU 预编译包包含未知入口：${zipEntry.name}" }
                check(extracted.add(name)) { "NPU 预编译包包含重复入口" }
                val (size, hash) = expected.getValue(name)
                val file = File(pending, name)
                FileOutputStream(file).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var written = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        written += read
                        check(written <= size) { "NPU 预编译产物解压体积超过签名值" }
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
                check(file.length() == size) { "NPU 预编译产物解压字节数不符：$name" }
                check(SpatialModelStore.sha256(file).equals(hash, ignoreCase = true)) {
                    "NPU 预编译产物解压哈希不符：$name"
                }
                input.closeEntry()
            }
        }
        check(extracted == expected.keys) { "NPU 预编译包缺少必要文件" }
    }

    /**
     * 本地已装的这一份，是否就是 catalog 现在下发的那一份。
     *
     * 键只有 (modelId, modelVersion, dspArch)，**同一个键下的产物是会被换掉的**——
     * 2026-08-14 把 Big-LaMa 的傅里叶分支改写成 GEMM 后重编了 context binary（D262），
     * 模型本身没升版，键完全一样。只按键判断"装过了"的话，老用户会永远停在旧产物上，
     * 修好的东西发出去等于没发。
     */
    fun matchesCatalog(context: Context, entry: SpatialQnnPrecompiledCatalogEntry): Boolean {
        val installed = contextModel(
            context, entry.modelId, entry.modelVersion, entry.dspArch
        ) ?: return false
        val marker = readMarker(installed.parentFile ?: return false) ?: return false
        return marker.contextBinarySha256.equals(entry.contextBinarySha256, ignoreCase = true) &&
            marker.contextModelSha256.equals(entry.contextModelSha256, ignoreCase = true)
    }

    /**
     * 装着的是旧产物就整个删掉，让它退回"未下载"。
     *
     * 删而不是加一档"可更新"状态：设置页所有行的状态机都只有装没装两种，多一档要多一条
     * 文案、12 个语言都得跟，而这里退回未下载既准确又不用改状态机——那一份确实不该再用了。
     *
     * @return 是否真的删了
     */
    fun purgeIfStale(context: Context, entry: SpatialQnnPrecompiledCatalogEntry): Boolean {
        if (!isInstalled(context, entry.modelId, entry.modelVersion, entry.dspArch)) return false
        if (matchesCatalog(context, entry)) return false
        val directory = directoryFor(context, entry.modelId, entry.modelVersion, entry.dspArch)
        return directory.deleteRecursively()
    }

    private fun readMarker(directory: File): ReadyMarker? {
        val file = File(directory, READY_MARKER)
        if (!file.isFile || file.length() !in 1..MAX_MARKER_BYTES) return null
        return runCatching {
            gson.fromJson(file.readText(Charsets.UTF_8), ReadyMarker::class.java)
                ?.takeIf { it.schemaVersion == MARKER_SCHEMA_VERSION }
        }.getOrNull()
    }

    private fun writeMarker(file: File, marker: ReadyMarker) {
        FileOutputStream(file).use { output ->
            output.write((gson.toJson(marker) + "\n").toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    private fun directoryFor(
        context: Context,
        modelId: String,
        modelVersion: String,
        dspArch: String
    ): File = File(
        rootDirectory(context),
        "${sanitize(modelId)}/${sanitize(modelVersion)}/${sanitize(dspArch)}"
    )

    private fun rootDirectory(context: Context): File =
        File(context.noBackupFilesDir, "spatial-photo/qnn-precompiled")

    /** 目录名全部来自 catalog，必须白名单化，绝不能让 `..` 或分隔符进路径。 */
    private fun sanitize(value: String): String {
        check(value.isNotEmpty() && value.length <= 64) { "非法标识：$value" }
        check(value.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }) {
            "非法标识：$value"
        }
        check(!value.contains("..")) { "非法标识：$value" }
        return value
    }

    private fun directoryBytes(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf { directoryBytes(it) } ?: 0L
    }

    private const val READY_MARKER = "ready.json"
    private const val MARKER_SCHEMA_VERSION = 1
    private const val MAX_MARKER_BYTES = 8L * 1024L
}
