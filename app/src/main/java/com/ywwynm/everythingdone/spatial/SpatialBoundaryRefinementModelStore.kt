package com.ywwynm.everythingdone.spatial

import android.app.ActivityManager
import android.content.Context
import androidx.annotation.Keep
import com.google.gson.Gson
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream

object SpatialBoundaryRefinementModelStore {

    @Keep
    data class ReadyMarker(
        val schemaVersion: Int = 1,
        val modelId: String,
        val modelVersion: String,
        val archiveSizeBytes: Long,
        val archiveSha256: String,
        val components: List<SpatialBoundaryRefinementComponent>
    )

    private val gson = Gson()

    fun modelDirectory(context: Context, model: SpatialBoundaryRefinementModel): File =
        File(rootDirectory(context), "${model.stableId}/${model.version}")

    fun componentFile(
        context: Context,
        model: SpatialBoundaryRefinementModel,
        component: SpatialBoundaryRefinementComponent
    ): File = File(modelDirectory(context, model), component.fileName)

    fun isInstalled(context: Context, model: SpatialBoundaryRefinementModel): Boolean {
        val directory = modelDirectory(context, model)
        val markerFile = File(directory, READY_MARKER)
        if (!markerFile.isFile || markerFile.length() !in 1..MAX_MARKER_BYTES) return false
        if (model.components.any { component ->
                val file = File(directory, component.fileName)
                !file.isFile || file.length() != component.sizeBytes
            }
        ) {
            return false
        }
        return runCatching {
            val marker = gson.fromJson(
                markerFile.readText(Charsets.UTF_8),
                ReadyMarker::class.java
            )
            marker == ReadyMarker(
                modelId = model.stableId,
                modelVersion = model.version,
                archiveSizeBytes = model.archiveSizeBytes,
                archiveSha256 = model.archiveSha256,
                components = model.components
            )
        }.getOrDefault(false)
    }

    fun isDeviceEligible(context: Context, model: SpatialBoundaryRefinementModel): Boolean {
        val memory = memoryInfo(context)
        return memory.totalMem / BYTES_PER_MIB >= model.minimumTotalRamMb
    }

    fun hasSufficientAvailableMemory(
        context: Context,
        model: SpatialBoundaryRefinementModel
    ): Boolean {
        val memory = memoryInfo(context)
        return !memory.lowMemory && memory.availMem / BYTES_PER_MIB >= model.minimumAvailableRamMb
    }

    @Synchronized
    fun installVerified(
        context: Context,
        model: SpatialBoundaryRefinementModel,
        archive: File,
        markReady: Boolean = true
    ) {
        check(archive.isFile && archive.length() == model.archiveSizeBytes) {
            "边界细化模型下载字节数不符"
        }
        check(
            SpatialModelStore.sha256(archive).equals(model.archiveSha256, ignoreCase = true)
        ) { "边界细化模型 SHA-256 不符" }
        val root = rootDirectory(context)
        check(root.exists() || root.mkdirs()) { "无法创建边界细化模型目录" }
        val pending = File(root, ".pending-${model.stableId}-${model.version}-${System.nanoTime()}")
        check(pending.mkdirs()) { "无法创建边界细化模型临时目录" }
        try {
            extractComponents(archive, pending, model)
            if (markReady) writeReadyMarker(File(pending, READY_MARKER), model)
            val target = modelDirectory(context, model)
            check(target.parentFile?.exists() == true || target.parentFile?.mkdirs() == true)
            val backup = File(target.parentFile, ".backup-${target.name}-${System.nanoTime()}")
            if (target.exists()) moveAtomically(target, backup)
            try {
                moveAtomically(pending, target)
                if (backup.exists()) check(backup.deleteRecursively())
            } catch (error: Throwable) {
                if (target.exists()) target.deleteRecursively()
                if (backup.exists()) moveAtomically(backup, target)
                throw error
            }
            // 字节已换成这一份，旧的执行期失败结论作废；设置页读取侧兜不住模型维
            //（见 [SpatialQnnExecutionBlocklist]）。上 QNN 的只有 encoder，清它那一个 id。
            SpatialQnnExecutionBlocklist.clear(context, model.qnnEncoderModelId)
        } catch (error: Throwable) {
            pending.deleteRecursively()
            throw error
        }
    }

    @Synchronized
    fun writeReadyMarker(context: Context, model: SpatialBoundaryRefinementModel) {
        check(model.components.all { component ->
            val file = componentFile(context, model, component)
            file.isFile && file.length() == component.sizeBytes
        }) { "边界细化模型组件不完整" }
        val directory = modelDirectory(context, model)
        val pending = File(directory, "$READY_MARKER.pending")
        writeReadyMarker(pending, model)
        moveAtomically(pending, File(directory, READY_MARKER))
    }

    @Synchronized
    fun delete(context: Context, model: SpatialBoundaryRefinementModel): Boolean {
        val directory = modelDirectory(context, model)
        val ok = !directory.exists() || directory.deleteRecursively()
        // modelDirectory 是 <stableId>/<version> 两层，只删 version 层会把空的
        // stableId 目录留成壳（2026-08-15 在 OPD2515 上实测残留），一并清掉。
        directory.parentFile?.takeIf { it.list()?.isEmpty() == true }?.delete()
        // 产物没了，执行期结论也不该留着（见 [SpatialQnnExecutionBlocklist] 的作废条件）。
        SpatialQnnExecutionBlocklist.clear(context, model.qnnEncoderModelId)
        return ok
    }

    fun totalBytes(context: Context): Long = directoryBytes(rootDirectory(context))

    private fun extractComponents(
        archive: File,
        pending: File,
        model: SpatialBoundaryRefinementModel
    ) {
        val expected = model.components.associateBy(SpatialBoundaryRefinementComponent::fileName)
        val extracted = mutableSetOf<String>()
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val component = expected[entry.name]
                check(!entry.isDirectory && component != null) {
                    "边界细化模型压缩包包含未知入口"
                }
                check(extracted.add(entry.name)) { "边界细化模型压缩包包含重复入口" }
                val target = File(pending, component.fileName)
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var written = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        written += read
                        check(written <= component.sizeBytes) {
                            "边界细化模型解压体积超过签名值"
                        }
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
                check(target.length() == component.sizeBytes) {
                    "边界细化模型组件字节数不符"
                }
                check(
                    SpatialModelStore.sha256(target).equals(component.sha256, ignoreCase = true)
                ) { "边界细化模型组件 SHA-256 不符" }
                input.closeEntry()
            }
        }
        check(extracted == expected.keys) { "边界细化模型压缩包缺少必要组件" }
    }

    private fun writeReadyMarker(file: File, model: SpatialBoundaryRefinementModel) {
        val marker = ReadyMarker(
            modelId = model.stableId,
            modelVersion = model.version,
            archiveSizeBytes = model.archiveSizeBytes,
            archiveSha256 = model.archiveSha256,
            components = model.components
        )
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

    private fun memoryInfo(context: Context): ActivityManager.MemoryInfo {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
    }

    private fun rootDirectory(context: Context): File =
        File(context.noBackupFilesDir, "spatial-photo/boundary-refinement-models")

    private fun directoryBytes(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf(::directoryBytes) ?: 0L
    }

    private const val READY_MARKER = "ready.json"
    private const val MAX_MARKER_BYTES = 32L * 1024L
    private const val BYTES_PER_MIB = 1024L * 1024L
}
