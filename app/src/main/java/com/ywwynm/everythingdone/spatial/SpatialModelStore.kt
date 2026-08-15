package com.ywwynm.everythingdone.spatial

import android.app.ActivityManager
import android.content.Context
import androidx.annotation.Keep
import com.google.gson.Gson
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

object SpatialModelStore {

    private val gson = Gson()

    @Keep
    data class ReadyMarker(
        val schemaVersion: Int = 1,
        val modelId: String,
        val modelVersion: String,
        val sizeBytes: Long,
        val sha256: String
    )

    fun modelDirectory(context: Context, model: SpatialDepthModel): File =
        File(rootDirectory(context), "${model.stableId}/${model.version}")

    fun modelFile(context: Context, model: SpatialDepthModel): File =
        File(modelDirectory(context, model), model.fileName)

    fun isInstalled(context: Context, model: SpatialDepthModel): Boolean {
        val modelFile = modelFile(context, model)
        val markerFile = File(modelDirectory(context, model), READY_MARKER)
        if (!modelFile.isFile || modelFile.length() != model.sizeBytes || !markerFile.isFile) {
            return false
        }
        return runCatching {
            val marker = gson.fromJson(markerFile.readText(Charsets.UTF_8), ReadyMarker::class.java)
            marker.schemaVersion == 1 &&
                marker.modelId == model.stableId &&
                marker.modelVersion == model.version &&
                marker.sizeBytes == model.sizeBytes &&
                marker.sha256.equals(model.sha256, ignoreCase = true)
        }.getOrDefault(false)
    }

    fun isDeviceEligible(context: Context, model: SpatialDepthModel): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memory)
        val totalMb = memory.totalMem / (1024L * 1024L)
        return totalMb >= model.minimumTotalRamMb
    }

    fun hasSufficientAvailableMemory(context: Context, model: SpatialDepthModel): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memory)
        val availableMb = memory.availMem / (1024L * 1024L)
        return !memory.lowMemory && availableMb >= model.minimumAvailableRamMb
    }

    /**
     * 校验临时模型并完成原子安装。调用方必须先完成 catalog 验签。
     */
    fun installVerified(
        context: Context,
        model: SpatialDepthModel,
        source: File,
        markReady: Boolean = true
    ): File {
        check(source.isFile) { "模型临时文件不存在" }
        check(source.length() == model.sizeBytes) {
            "模型字节数不符：${source.length()} != ${model.sizeBytes}"
        }
        val actualHash = sha256(source)
        check(actualHash.equals(model.sha256, ignoreCase = true)) {
            "模型 SHA-256 不符"
        }

        val targetDirectory = modelDirectory(context, model)
        check(targetDirectory.exists() || targetDirectory.mkdirs()) {
            "无法创建模型目录：${targetDirectory.absolutePath}"
        }
        val target = modelFile(context, model)
        val pending = File(targetDirectory, "${model.fileName}.pending")
        copyAndSync(source, pending)
        check(pending.length() == model.sizeBytes && sha256(pending) == model.sha256)

        if (target.exists()) check(target.delete()) { "无法替换旧模型文件" }
        check(pending.renameTo(target)) { "无法原子安装模型文件" }

        if (markReady) writeReadyMarker(context, model)
        return target
    }

    fun writeReadyMarker(context: Context, model: SpatialDepthModel) {
        val target = modelFile(context, model)
        check(target.isFile && target.length() == model.sizeBytes) { "模型候选文件不完整" }
        val targetDirectory = modelDirectory(context, model)
        val marker = ReadyMarker(
            modelId = model.stableId,
            modelVersion = model.version,
            sizeBytes = model.sizeBytes,
            sha256 = model.sha256
        )
        val markerPending = File(targetDirectory, "$READY_MARKER.pending")
        FileOutputStream(markerPending).use { output ->
            output.write((gson.toJson(marker) + "\n").toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        val markerFile = File(targetDirectory, READY_MARKER)
        if (markerFile.exists()) check(markerFile.delete())
        check(markerPending.renameTo(markerFile)) { "无法写入模型就绪标记" }
    }

    fun delete(context: Context, model: SpatialDepthModel): Boolean {
        val directory = modelDirectory(context, model)
        val ok = !directory.exists() || directory.deleteRecursively()
        // modelDirectory 是 <stableId>/<version> 两层，只删 version 层会把空的
        // stableId 目录留成壳（2026-08-15 在 OPD2515 上实测残留），一并清掉。
        directory.parentFile?.takeIf { it.list()?.isEmpty() == true }?.delete()
        return ok
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun rootDirectory(context: Context): File =
        File(context.noBackupFilesDir, "spatial-photo/models")

    private fun copyAndSync(source: File, target: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output, 1024 * 1024)
                output.fd.sync()
            }
        }
    }

    private const val READY_MARKER = "ready.json"
}
