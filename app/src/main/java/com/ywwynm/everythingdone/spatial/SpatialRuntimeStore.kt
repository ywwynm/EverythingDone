package com.ywwynm.everythingdone.spatial

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import androidx.annotation.Keep
import com.google.gson.Gson
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream

/**
 * 空间深度推理共用的按需 ONNX Runtime。
 *
 * 该类不能引用任何 ai.onnxruntime 类型；必须先完成绝对路径加载，再允许 ORT Java 类初始化。
 */
object SpatialRuntimeStore {

    const val RUNTIME_ID = "onnxruntime"
    const val ORT_VERSION = "1.28.0"
    /**
     * r2 只扩大裁剪算子集合，不改变 Java/JNI ABI。保持 API 1 可让旧版 App 安全使用这个超集包；
     * 当前版再用精确 packageVersion 强制把缺少 AOT-GAN 算子的 r1 升级掉。
     */
    const val RUNTIME_API_VERSION = 1
    // r7 = 十二个模型的算子并集（r6 的十个 + Big-LaMa + MoGe-2），**并编入 XNNPACK EP**。
    // MoGe-2 是 opset 14，r6 缺 (Pow, 14) 这类三元组——注意缺的不是算子名，Pow 早就在
    // opset 17/18 两节里了（D206）。XNNPACK 是给 Big-LaMa 的卷积用的，占其内核时间
    // 58.6%（D203）；EP 按会话选用，不改变既有模型的行为。
    // 提升必须与 r7 catalog 同步，否则下载链路会被 catalog 校验阻断。
    const val REQUIRED_PACKAGE_VERSION = "1.28.0-r7"
    const val CORE_LIBRARY = "libonnxruntime.so"
    const val JNI_LIBRARY = "libonnxruntime4j_jni.so"
    val SUPPORTED_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

    private val gson = Gson()

    @Volatile
    private var loaded = false

    @Volatile
    private var loadedMarker: ReadyMarker? = null

    @Keep
    data class ReadyMarker(
        val schemaVersion: Int = MARKER_SCHEMA_VERSION,
        val id: String,
        val packageVersion: String,
        val ortVersion: String,
        val runtimeApiVersion: Int,
        val abi: String,
        val coreSizeBytes: Long,
        val coreSha256: String,
        val jniSizeBytes: Long,
        val jniSha256: String
    )

    fun currentAbi(): String? =
        Build.SUPPORTED_ABIS.firstOrNull { it in SUPPORTED_ABIS }

    fun isInstalled(context: Context): Boolean {
        val marker = readCurrentMarker(context) ?: return false
        return installedFiles(context, marker)?.let { (core, jni) ->
            core.length() == marker.coreSizeBytes &&
                jni.length() == marker.jniSizeBytes
        } == true
    }

    fun isLoaded(): Boolean = loaded

    fun nativeLibraryDirectory(context: Context): File {
        val marker = readCurrentMarker(context)
            ?: error("空间计算组件尚未安装")
        val (core, _) = installedFiles(context, marker)
            ?: error("空间计算组件文件不完整")
        return checkNotNull(core.parentFile)
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    @Synchronized
    fun ensureLoaded(context: Context) {
        val marker = readCurrentMarker(context)
            ?: error("空间计算组件尚未安装")
        val (core, jni) = installedFiles(context, marker)
            ?: error("空间计算组件文件不完整")
        if (loaded) {
            check(marker == loadedMarker) {
                "空间计算组件已在当前进程中更新，请重新启动 App"
            }
            return
        }
        check(SpatialModelStore.sha256(core).equals(marker.coreSha256, ignoreCase = true)) {
            "空间计算核心校验失败"
        }
        check(SpatialModelStore.sha256(jni).equals(marker.jniSha256, ignoreCase = true)) {
            "空间计算 JNI 校验失败"
        }
        makeReadOnly(core)
        makeReadOnly(jni)

        // JNI 库的 DT_NEEDED 包含 libonnxruntime.so，必须先把核心库加入当前 linker namespace。
        System.load(core.absolutePath)
        System.load(jni.absolutePath)
        loadedMarker = marker
        loaded = true
    }

    /**
     * zip 只允许包含两个签名 catalog 明确描述的库，拒绝额外入口、路径穿越和解压膨胀。
     */
    @Synchronized
    fun installVerified(
        context: Context,
        entry: SpatialRuntimeCatalogEntry,
        archive: File
    ): ReadyMarker {
        check(entry.isCompatible()) { "运行组件与当前 App 不兼容" }
        check(entry.abi == currentAbi()) { "运行组件 ABI 与设备不匹配" }
        check(archive.isFile && archive.length() == entry.sizeBytes) {
            "运行组件下载字节数不符"
        }
        check(SpatialModelStore.sha256(archive).equals(entry.sha256, ignoreCase = true)) {
            "运行组件 SHA-256 不符"
        }

        val root = rootDirectory(context)
        check(root.exists() || root.mkdirs()) { "无法创建运行组件目录" }
        val pending = File(root, ".pending-${entry.packageVersion}-${entry.abi}-${System.nanoTime()}")
        check(pending.mkdirs()) { "无法创建运行组件临时目录" }
        try {
            extractLibraries(archive, pending, entry)
            val marker = ReadyMarker(
                id = entry.id,
                packageVersion = entry.packageVersion,
                ortVersion = entry.ortVersion,
                runtimeApiVersion = entry.runtimeApiVersion,
                abi = entry.abi,
                coreSizeBytes = entry.coreSizeBytes,
                coreSha256 = entry.coreSha256.lowercase(),
                jniSizeBytes = entry.jniSizeBytes,
                jniSha256 = entry.jniSha256.lowercase()
            )
            writeMarker(File(pending, READY_MARKER), marker)

            val target = runtimeDirectory(context, marker)
            check(target.parentFile?.exists() == true || target.parentFile?.mkdirs() == true)
            if (target.exists()) check(target.deleteRecursively()) {
                "无法替换旧运行组件目录"
            }
            moveAtomically(pending, target)

            val currentPending = File(root, "$CURRENT_MARKER.pending")
            writeMarker(currentPending, marker)
            moveAtomically(currentPending, File(root, CURRENT_MARKER))
            pruneObsoletePackages(root, target)
            return marker
        } catch (error: Throwable) {
            pending.deleteRecursively()
            throw error
        }
    }

    @Synchronized
    fun delete(context: Context): Boolean {
        val root = rootDirectory(context)
        return !root.exists() || root.deleteRecursively()
    }

    fun totalBytes(context: Context): Long = directoryBytes(rootDirectory(context))

    fun partialFile(context: Context, entry: SpatialRuntimeCatalogEntry): File =
        File(
            context.noBackupFilesDir,
            "spatial-photo/downloads/runtime-${entry.packageVersion}-${entry.abi}.zip.part"
        )

    private fun readCurrentMarker(context: Context): ReadyMarker? {
        val file = File(rootDirectory(context), CURRENT_MARKER)
        if (!file.isFile || file.length() !in 1..MAX_MARKER_BYTES) return null
        return runCatching {
            val marker = gson.fromJson(file.readText(Charsets.UTF_8), ReadyMarker::class.java)
            marker.takeIf(::isCompatibleMarker)
        }.getOrNull()
    }

    private fun installedFiles(context: Context, marker: ReadyMarker): Pair<File, File>? {
        val directory = runtimeDirectory(context, marker)
        val ready = File(directory, READY_MARKER)
        val core = File(directory, CORE_LIBRARY)
        val jni = File(directory, JNI_LIBRARY)
        if (!ready.isFile || !core.isFile || !jni.isFile) return null
        val directoryMarker = runCatching {
            gson.fromJson(ready.readText(Charsets.UTF_8), ReadyMarker::class.java)
        }.getOrNull() ?: return null
        if (directoryMarker != marker) return null
        return core to jni
    }

    private fun isCompatibleMarker(marker: ReadyMarker): Boolean =
        marker.schemaVersion == MARKER_SCHEMA_VERSION &&
            marker.id == RUNTIME_ID &&
            marker.ortVersion == ORT_VERSION &&
            marker.runtimeApiVersion == RUNTIME_API_VERSION &&
            marker.packageVersion == REQUIRED_PACKAGE_VERSION &&
            marker.abi == currentAbi() &&
            marker.coreSizeBytes in 1..MAX_CORE_BYTES &&
            marker.jniSizeBytes in 1..MAX_JNI_BYTES &&
            marker.coreSha256.matches(SHA256_REGEX) &&
            marker.jniSha256.matches(SHA256_REGEX)

    private fun runtimeDirectory(context: Context, marker: ReadyMarker): File =
        File(
            rootDirectory(context),
            "objects/${marker.packageVersion}/${marker.abi}"
        )

    private fun pruneObsoletePackages(root: File, current: File) {
        val objects = File(root, "objects")
        objects.listFiles()?.forEach { packageDirectory ->
            packageDirectory.listFiles()?.forEach { abiDirectory ->
                if (abiDirectory.absolutePath != current.absolutePath) {
                    abiDirectory.deleteRecursively()
                }
            }
            if (packageDirectory.listFiles().isNullOrEmpty()) {
                packageDirectory.delete()
            }
        }
    }

    private fun rootDirectory(context: Context): File =
        File(context.noBackupFilesDir, "spatial-photo/runtime")

    private fun extractLibraries(
        archive: File,
        pending: File,
        entry: SpatialRuntimeCatalogEntry
    ) {
        val expected = mapOf(
            CORE_LIBRARY to (entry.coreSizeBytes to entry.coreSha256),
            JNI_LIBRARY to (entry.jniSizeBytes to entry.jniSha256)
        )
        val extracted = mutableSetOf<String>()
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { input ->
            while (true) {
                val zipEntry = input.nextEntry ?: break
                check(!zipEntry.isDirectory && zipEntry.name in expected) {
                    "运行组件压缩包包含未知入口"
                }
                check(extracted.add(zipEntry.name)) { "运行组件压缩包包含重复入口" }
                val (expectedSize, expectedHash) = expected.getValue(zipEntry.name)
                val target = File(pending, zipEntry.name)
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var written = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        written += read
                        check(written <= expectedSize) { "运行组件解压体积超过签名值" }
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
                check(target.length() == expectedSize) { "运行组件解压字节数不符" }
                check(SpatialModelStore.sha256(target).equals(expectedHash, ignoreCase = true)) {
                    "运行组件解压哈希不符"
                }
                makeReadOnly(target)
                input.closeEntry()
            }
        }
        check(extracted == expected.keys) { "运行组件压缩包缺少必要文件" }
    }

    private fun writeMarker(file: File, marker: ReadyMarker) {
        FileOutputStream(file).use { output ->
            output.write((gson.toJson(marker) + "\n").toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    private fun moveAtomically(source: File, target: File) {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
    }

    private fun makeReadOnly(file: File) {
        if (file.canWrite()) {
            check(file.setReadOnly()) { "无法把运行组件设为只读" }
        }
        check(!file.canWrite()) { "运行组件在加载前仍可写" }
    }

    private fun directoryBytes(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf(::directoryBytes) ?: 0L
    }

    private val SHA256_REGEX = Regex("[0-9a-fA-F]{64}")
    private const val MARKER_SCHEMA_VERSION = 1
    private const val CURRENT_MARKER = "current.json"
    private const val READY_MARKER = "ready.json"
    private const val MAX_MARKER_BYTES = 16L * 1024L
    private const val MAX_CORE_BYTES = 64L * 1024L * 1024L
    private const val MAX_JNI_BYTES = 2L * 1024L * 1024L
}
