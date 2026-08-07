package com.ywwynm.everythingdone.spatial

import android.app.ActivityManager
import android.content.Context
import androidx.annotation.Keep
import com.google.gson.Gson
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object SpatialSegmentationModelStore {

    @Keep
    data class ReadyMarker(
        val schemaVersion: Int = 1,
        val modelId: String,
        val modelVersion: String,
        val sizeBytes: Long,
        val sha256: String
    )

    private val gson = Gson()

    fun modelDirectory(context: Context, model: SpatialSegmentationModel): File =
        File(rootDirectory(context), "${model.stableId}/${model.version}")

    fun modelFile(context: Context, model: SpatialSegmentationModel): File =
        File(modelDirectory(context, model), model.fileName)

    fun isInstalled(context: Context, model: SpatialSegmentationModel): Boolean {
        val modelFile = modelFile(context, model)
        val markerFile = File(modelDirectory(context, model), READY_MARKER)
        if (!modelFile.isFile || modelFile.length() != model.sizeBytes || !markerFile.isFile) {
            return false
        }
        return runCatching {
            val marker = gson.fromJson(
                markerFile.readText(Charsets.UTF_8),
                ReadyMarker::class.java
            )
            marker.schemaVersion == 1 &&
                marker.modelId == model.stableId &&
                marker.modelVersion == model.version &&
                marker.sizeBytes == model.sizeBytes &&
                marker.sha256.equals(model.sha256, ignoreCase = true)
        }.getOrDefault(false)
    }

    fun isDeviceEligible(context: Context, model: SpatialSegmentationModel): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(memory)
        return memory.totalMem / (1024L * 1024L) >= model.minimumTotalRamMb
    }

    fun hasSufficientAvailableMemory(
        context: Context,
        model: SpatialSegmentationModel
    ): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(memory)
        return !memory.lowMemory &&
            memory.availMem / (1024L * 1024L) >= model.minimumAvailableRamMb
    }

    fun installVerified(
        context: Context,
        model: SpatialSegmentationModel,
        source: File,
        markReady: Boolean = true
    ): File {
        check(source.isFile) { "实例分割模型临时文件不存在" }
        check(source.length() == model.sizeBytes) { "实例分割模型字节数不符" }
        check(SpatialModelStore.sha256(source).equals(model.sha256, ignoreCase = true)) {
            "实例分割模型 SHA-256 不符"
        }
        val directory = modelDirectory(context, model)
        check(directory.exists() || directory.mkdirs()) { "无法创建实例分割模型目录" }
        val target = modelFile(context, model)
        val pending = File(directory, "${model.fileName}.pending")
        FileInputStream(source).use { input ->
            FileOutputStream(pending).use { output ->
                input.copyTo(output, 1024 * 1024)
                output.fd.sync()
            }
        }
        check(
            pending.length() == model.sizeBytes &&
                SpatialModelStore.sha256(pending).equals(model.sha256, ignoreCase = true)
        )
        if (target.exists()) check(target.delete()) { "无法替换实例分割模型" }
        check(pending.renameTo(target)) { "无法原子安装实例分割模型" }
        if (markReady) writeReadyMarker(context, model)
        return target
    }

    fun writeReadyMarker(context: Context, model: SpatialSegmentationModel) {
        val target = modelFile(context, model)
        check(target.isFile && target.length() == model.sizeBytes)
        val directory = modelDirectory(context, model)
        val pending = File(directory, "$READY_MARKER.pending")
        val marker = ReadyMarker(
            modelId = model.stableId,
            modelVersion = model.version,
            sizeBytes = model.sizeBytes,
            sha256 = model.sha256
        )
        FileOutputStream(pending).use { output ->
            output.write((gson.toJson(marker) + "\n").toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        val targetMarker = File(directory, READY_MARKER)
        if (targetMarker.exists()) check(targetMarker.delete())
        check(pending.renameTo(targetMarker)) { "无法写入实例分割模型就绪标记" }
    }

    fun delete(context: Context, model: SpatialSegmentationModel): Boolean {
        val directory = modelDirectory(context, model)
        return !directory.exists() || directory.deleteRecursively()
    }

    fun totalBytes(context: Context): Long = directoryBytes(rootDirectory(context))

    private fun rootDirectory(context: Context): File =
        File(context.noBackupFilesDir, "spatial-photo/segmentation-models")

    private fun directoryBytes(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf(::directoryBytes) ?: 0L
    }

    private const val READY_MARKER = "ready.json"
}
