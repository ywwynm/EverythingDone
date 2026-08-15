package com.ywwynm.everythingdone.spatial

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import androidx.annotation.Keep
import com.google.gson.Gson
import com.ywwynm.everythingdone.BuildConfig
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
    /**
     * 骁龙 NPU 版运行组件。**与 CPU 版互斥**：QNN EP 编译在 `libonnxruntime.so` 里，
     * 一个进程只能加载一份，所以开 QNN 是换整包，不是往既有包里补库。
     */
    const val QNN_PACKAGE_VERSION = "1.28.0-qnn-r2"

    /**
     * [QNN_PACKAGE_VERSION] 里打包的 QAIRT 版本。**预编译 context binary 与它强绑定**：
     * 版本不一致时 ORT 会在建 session 时报
     * `LoadCachedQnnContextFromBuffer … Error code: 5000`（D252）。
     * 换运行组件包必须同步改这里，否则 catalog 里的预编译产物会被静默判为不兼容。
     */
    const val QNN_QAIRT_VERSION = "2.48"

    /** 当前该装哪一个包。总开关决定，装反了 [isCompatibleMarker] 会判不兼容并要求重下。 */
    fun requiredPackageVersion(context: Context): String =
        if (SpatialPreferences.qnnEnabled(context)) QNN_PACKAGE_VERSION
        else REQUIRED_PACKAGE_VERSION

    /** 某个变体是否已装好。UI 要同时显示两行，所以不能只问"当前变体"。 */
    fun isVariantInstalled(context: Context, qnn: Boolean): Boolean {
        if (qnn && SpatialQnnSupport.resolveDspArch(context) == null) return false
        val marker = readMarker(context, qnn) ?: return false
        return installedFiles(context, marker)?.let { (core, jni) ->
            core.length() == marker.coreSizeBytes && jni.length() == marker.jniSizeBytes
        } == true
    }

    fun variantTotalBytes(context: Context, qnn: Boolean): Long {
        val marker = readMarker(context, qnn) ?: return 0L
        return directoryBytes(runtimeDirectory(context, marker))
    }

    /** 只删某一个变体，另一个不动。 */
    @Synchronized
    fun deleteVariant(context: Context, qnn: Boolean): Boolean {
        val marker = readMarker(context, qnn) ?: return true
        val dir = runtimeDirectory(context, marker)
        val ok = !dir.exists() || dir.deleteRecursively()
        File(rootDirectory(context), markerName(qnn)).delete()
        return ok
    }

    private fun markerName(qnn: Boolean): String =
        if (qnn) CURRENT_MARKER_QNN else CURRENT_MARKER
    const val CORE_LIBRARY = "libonnxruntime.so"
    const val JNI_LIBRARY = "libonnxruntime4j_jni.so"
    val SUPPORTED_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

    private val gson = Gson()

    @Volatile
    private var loaded = false

    @Volatile
    private var loadedMarker: ReadyMarker? = null

    /** 走 debug 覆盖目录加载时记下它，用来挡住"同进程换库"。 */
    @Volatile
    private var loadedOverride: String? = null

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
        val jniSha256: String,
        /** QNN 包才有：本包对应的 HTP 架构。CPU 包为 null。 */
        val dspArch: String? = null,
        /** QNN 包才有：QAIRT 那几个库。CPU 包为 null。 */
        val extraFiles: List<SpatialRuntimeExtraFile>? = null
    )

    fun currentAbi(): String? =
        Build.SUPPORTED_ABIS.firstOrNull { it in SUPPORTED_ABIS }

    fun isInstalled(context: Context): Boolean {
        if (debugOverrideDirectory(context) != null) return true
        val marker = readCurrentMarker(context) ?: return false
        return installedFiles(context, marker)?.let { (core, jni) ->
            core.length() == marker.coreSizeBytes &&
                jni.length() == marker.jniSizeBytes
        } == true
    }

    fun isLoaded(): Boolean = loaded

    /**
     * 已安装运行组件的包版本。QNN 的编译产物要与它绑定——ORT 或 QNN 库一变，
     * 之前编出来的 context binary 就不能再用（见 [SpatialQnnContextStore.Key]）。
     */
    fun installedPackageVersion(context: Context): String? {
        debugOverrideDirectory(context)?.let { return "$OVERRIDE_PACKAGE_PREFIX${it.name}" }
        return readCurrentMarker(context)?.packageVersion
    }

    fun nativeLibraryDirectory(context: Context): File {
        debugOverrideDirectory(context)?.let { return it }
        val marker = readCurrentMarker(context)
            ?: error("空间计算组件尚未安装")
        val (core, _) = installedFiles(context, marker)
            ?: error("空间计算组件文件不完整")
        return checkNotNull(core.parentFile)
    }

    /**
     * Debug 专用运行组件覆盖目录。
     *
     * QNN 版 `libonnxruntime.so` 与现役裁剪版是两份不同的库，而同一进程只能加载一份；
     * 在 catalog 尚未上架 QNN 运行组件之前，真机验证只能靠这条旁路：由 debug 探针把
     * adb push 上来的库复制进这个目录，产品路径检测到就改用它。
     *
     * 旁路的只是"必须先上架 catalog"这一步——目录里必须两个核心库齐全才认，
     * 且 release 构建下这个方法恒为 null。
     */
    private fun debugOverrideDirectory(context: Context): File? {
        if (!BuildConfig.DEBUG) return null
        val directory = File(context.noBackupFilesDir, OVERRIDE_DIRECTORY)
        if (!directory.isDirectory) return null
        if (!File(directory, CORE_LIBRARY).isFile) return null
        if (!File(directory, JNI_LIBRARY).isFile) return null
        return directory
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    @Synchronized
    fun ensureLoaded(context: Context) {
        debugOverrideDirectory(context)?.let { directory ->
            if (loaded) {
                check(loadedOverride == directory.absolutePath) {
                    "空间计算组件已在当前进程中更新，请重新启动 App"
                }
                return
            }
            // 覆盖包由 adb 放入，不经 catalog，因此没有可比对的签名摘要；
            // 这条路只在 debug 构建存在。
            System.load(File(directory, CORE_LIBRARY).absolutePath)
            System.load(File(directory, JNI_LIBRARY).absolutePath)
            loadedOverride = directory.absolutePath
            loaded = true
            return
        }
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
            pruneObsoletePackages(root, target, qnnVariant = false)
            return marker
        } catch (error: Throwable) {
            pending.deleteRecursively()
            throw error
        }
    }

    /**
     * QNN 版运行组件的安装。与 CPU 版走同一套落盘、标记与裁剪逻辑，唯一的差别是
     * zip 里除两个 onnxruntime 库外还有 QAIRT 的几个 `.so`，逐个按签名 catalog 校验。
     */
    @Synchronized
    fun installVerifiedQnn(
        context: Context,
        entry: SpatialQnnRuntimeCatalogEntry,
        archive: File
    ): ReadyMarker {
        check(entry.isCompatible()) { "QNN 运行组件与当前 App 不兼容" }
        check(entry.abi == currentAbi()) { "QNN 运行组件 ABI 与设备不匹配" }
        // 全 arch 包对任何机器都成立（由 QNN 自己挑 Skel）；单 arch 包必须与本机判定一致。
        check(
            entry.dspArch == SpatialQnnSupport.ALL_ARCH ||
                entry.dspArch == SpatialQnnSupport.resolveDspArch(context)
        ) {
            "QNN 运行组件的 HTP 架构与设备不匹配"
        }
        check(archive.isFile && archive.length() == entry.sizeBytes) {
            "QNN 运行组件下载字节数不符"
        }
        check(SpatialModelStore.sha256(archive).equals(entry.sha256, ignoreCase = true)) {
            "QNN 运行组件 SHA-256 不符"
        }

        val root = rootDirectory(context)
        check(root.exists() || root.mkdirs()) { "无法创建运行组件目录" }
        val pending = File(
            root,
            ".pending-${entry.packageVersion}-${entry.abi}-${entry.dspArch}-${System.nanoTime()}"
        )
        check(pending.mkdirs()) { "无法创建运行组件临时目录" }
        try {
            extractQnnLibraries(archive, pending, entry)
            val marker = ReadyMarker(
                id = entry.id,
                packageVersion = entry.packageVersion,
                ortVersion = entry.ortVersion,
                runtimeApiVersion = entry.runtimeApiVersion,
                abi = entry.abi,
                coreSizeBytes = entry.coreSizeBytes,
                coreSha256 = entry.coreSha256.lowercase(),
                jniSizeBytes = entry.jniSizeBytes,
                jniSha256 = entry.jniSha256.lowercase(),
                dspArch = entry.dspArch,
                extraFiles = entry.extraFiles.map {
                    it.copy(sha256 = it.sha256.lowercase())
                }
            )
            writeMarker(File(pending, READY_MARKER), marker)

            val target = runtimeDirectory(context, marker)
            check(target.parentFile?.exists() == true || target.parentFile?.mkdirs() == true)
            if (target.exists()) check(target.deleteRecursively()) {
                "无法替换旧运行组件目录"
            }
            moveAtomically(pending, target)

            val currentPending = File(root, "$CURRENT_MARKER_QNN.pending")
            writeMarker(currentPending, marker)
            moveAtomically(currentPending, File(root, CURRENT_MARKER_QNN))
            pruneObsoletePackages(root, target, qnnVariant = true)
            return marker
        } catch (error: Throwable) {
            pending.deleteRecursively()
            throw error
        }
    }

    fun partialFileQnn(context: Context, entry: SpatialQnnRuntimeCatalogEntry): File =
        File(
            context.noBackupFilesDir,
            "spatial-photo/downloads/qnn-runtime-${entry.packageVersion}-" +
                "${entry.abi}-${entry.dspArch}.zip.part"
        )

    private fun extractQnnLibraries(
        archive: File,
        pending: File,
        entry: SpatialQnnRuntimeCatalogEntry
    ) {
        val expected = buildMap {
            put(CORE_LIBRARY, entry.coreSizeBytes to entry.coreSha256)
            put(JNI_LIBRARY, entry.jniSizeBytes to entry.jniSha256)
            entry.extraFiles.forEach { put(it.name, it.sizeBytes to it.sha256) }
        }
        val extracted = mutableSetOf<String>()
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { input ->
            while (true) {
                val zipEntry = input.nextEntry ?: break
                check(!zipEntry.isDirectory && zipEntry.name in expected) {
                    "QNN 运行组件压缩包包含未知入口：${zipEntry.name}"
                }
                check(extracted.add(zipEntry.name)) { "QNN 运行组件压缩包包含重复入口" }
                val (expectedSize, expectedHash) = expected.getValue(zipEntry.name)
                val target = File(pending, zipEntry.name)
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var written = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        written += read
                        check(written <= expectedSize) { "QNN 运行组件解压体积超过签名值" }
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
                check(target.length() == expectedSize) { "QNN 运行组件解压字节数不符" }
                check(SpatialModelStore.sha256(target).equals(expectedHash, ignoreCase = true)) {
                    "QNN 运行组件解压哈希不符：${zipEntry.name}"
                }
                makeReadOnly(target)
                input.closeEntry()
            }
        }
        check(extracted == expected.keys) { "QNN 运行组件压缩包缺少必要文件" }
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

    private fun readCurrentMarker(context: Context): ReadyMarker? =
        readMarker(context, SpatialPreferences.qnnEnabled(context))

    private fun readMarker0(root: File, name: String): ReadyMarker? {
        val file = File(root, name)
        if (!file.isFile || file.length() !in 1..MAX_MARKER_BYTES) return null
        return runCatching {
            gson.fromJson(file.readText(Charsets.UTF_8), ReadyMarker::class.java)
        }.getOrNull()
    }

    private fun readMarker(context: Context, qnn: Boolean): ReadyMarker? {
        val file = File(rootDirectory(context), markerName(qnn))
        if (!file.isFile || file.length() !in 1..MAX_MARKER_BYTES) return null
        val required = if (qnn) QNN_PACKAGE_VERSION else REQUIRED_PACKAGE_VERSION
        return runCatching {
            val marker = gson.fromJson(file.readText(Charsets.UTF_8), ReadyMarker::class.java)
            marker.takeIf { isCompatibleMarker(context, it, required) }
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

    private fun isCompatibleMarker(
        context: Context,
        marker: ReadyMarker,
        required: String
    ): Boolean =
        marker.schemaVersion == MARKER_SCHEMA_VERSION &&
            marker.id == RUNTIME_ID &&
            marker.ortVersion == ORT_VERSION &&
            marker.runtimeApiVersion == RUNTIME_API_VERSION &&
            marker.packageVersion == required &&
            // QNN 包必须是本机架构的那一份：Skel/Stub 按架构分，装错了 QNN 会在
            // device 创建阶段失败，并留下一堆指向别处的日志（D219）。
            (marker.packageVersion != QNN_PACKAGE_VERSION ||
                (marker.dspArch != null &&
                    (marker.dspArch == SpatialQnnSupport.ALL_ARCH ||
                        marker.dspArch == SpatialQnnSupport.resolveDspArch(context)))) &&
            marker.abi == currentAbi() &&
            marker.coreSizeBytes in 1..MAX_CORE_BYTES &&
            marker.jniSizeBytes in 1..MAX_JNI_BYTES &&
            marker.coreSha256.matches(SHA256_REGEX) &&
            marker.jniSha256.matches(SHA256_REGEX)

    private fun runtimeDirectory(context: Context, marker: ReadyMarker): File =
        File(
            rootDirectory(context),
            "objects/${marker.packageVersion}/${marker.abi}" +
                (marker.dspArch?.let { "/$it" } ?: "")
        )

    /**
     * 只清理**同一变体**里的过期包。此前不分变体地清，导致装 QNN 版会顺手删掉 CPU 版，
     * 开关一来回切就要重下（2026-08-14 反馈）。
     */
    private fun pruneObsoletePackages(root: File, current: File, qnnVariant: Boolean) {
        val keepOther = readMarker0(root, if (qnnVariant) CURRENT_MARKER else CURRENT_MARKER_QNN)
            ?.let { "objects/${it.packageVersion}" }
        val objects = File(root, "objects")
        // **不能按绝对路径精确相等判断**：CPU 包是 `<版本>/<abi>` 两层，QNN 包多一层
        // dspArch（`<版本>/<abi>/<arch>`）。用相等判断时，QNN 装完这里会认为
        // `<abi>` 不是当前目录而把它整个递归删掉，刚装好的包当场消失，症状是
        // "安装后校验失败"（2026-08-13 实际发生）。改成按"是不是当前目录的祖先或本身"判。
        val currentPath = current.absolutePath
        fun keeps(candidate: File): Boolean {
            val path = candidate.absolutePath
            return currentPath == path || currentPath.startsWith(path + File.separator)
        }
        objects.listFiles()?.forEach { packageDirectory ->
            // 另一个变体的包整包跳过，不属于本次裁剪的范围
            if (keepOther != null && packageDirectory.name == keepOther.substringAfterLast('/')) {
                return@forEach
            }
            packageDirectory.listFiles()?.forEach { abiDirectory ->
                if (!keeps(abiDirectory)) {
                    abiDirectory.deleteRecursively()
                } else {
                    // 同一 ABI 下的其它架构目录（换机或换包时留下的）照样要清
                    abiDirectory.listFiles()?.forEach { archDirectory ->
                        if (archDirectory.isDirectory && !keeps(archDirectory)) {
                            archDirectory.deleteRecursively()
                        }
                    }
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
    private const val OVERRIDE_DIRECTORY = "spatial-photo/runtime-override"
    private const val OVERRIDE_PACKAGE_PREFIX = "debug-override-"
    private const val MARKER_SCHEMA_VERSION = 1
    /**
     * CPU 版与 NPU 版**各自一份**当前标记，磁盘上共存。
     *
     * 约束只在"加载"这一层：QNN EP 编译在 `libonnxruntime.so` 里，一个进程只能加载一份。
     * 但没有任何理由不让两份同时**存在**——此前从"不能同时用"推到"不能同时存"，
     * 结果是开关一切换就得重下一百多 MB，而且下载进度显示在另一行上，
     * 用户完全看不懂发生了什么（2026-08-14 反馈）。多占的只是 CPU 版那 13 MB。
     */
    private const val CURRENT_MARKER = "current.json"
    private const val CURRENT_MARKER_QNN = "current-qnn.json"
    private const val READY_MARKER = "ready.json"
    private const val MAX_MARKER_BYTES = 16L * 1024L
    private const val MAX_CORE_BYTES = 64L * 1024L * 1024L
    private const val MAX_JNI_BYTES = 2L * 1024L * 1024L
}
