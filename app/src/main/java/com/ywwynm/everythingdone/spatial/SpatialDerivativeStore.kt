package com.ywwynm.everythingdone.spatial

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.StatFs
import android.util.AtomicFile
import androidx.annotation.Keep
import com.google.gson.Gson
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

class SpatialDerivativeStore(
    private val context: Context
) {
    @Keep
    data class Manifest(
        val schemaVersion: Int = LEGACY_SCHEMA_VERSION,
        val sourceFingerprint: String,
        val sourceSizeBytes: Long,
        val sourceModifiedAtMs: Long,
        val modelId: String,
        val modelVersion: String,
        val width: Int,
        val height: Int,
        val robustRange: Float,
        val strongEdgeRatio: Float,
        val strength: Float,
        val depthSha256: String,
        val renderMode: String? = null,
        val renderer: String? = null,
        val viewEnvelopeAmplitudes: List<Float>? = null,
        val maximumLocalStrain: Float? = null,
        val meshWidth: Int? = null,
        val meshHeight: Int? = null,
        val backgroundWidth: Int? = null,
        val backgroundHeight: Int? = null,
        val inpaintingModelId: String? = null,
        val inpaintingModelVersion: String? = null,
        val inpaintingQualityId: String? = null,
        val meshDepthSha256: String? = null,
        val backgroundDepthSha256: String? = null,
        val connectivitySha256: String? = null,
        val motionBasisSha256: String? = null,
        val backgroundSha256: String? = null,
        // matting 边缘带细化（D54）的可选扩展；旧版 App 与旧派生均忽略。
        val mattingModelId: String? = null,
        val mattingModelVersion: String? = null,
        val displayAlphaSha256: String? = null,
        val ownershipAlphaSha256: String? = null,
        val subjectMaskSha256: String? = null,
        // provider-neutral 互斥实例身份；旧 App 忽略并继续使用 subjectMask union。
        val segmentationModelId: String? = null,
        val segmentationModelVersion: String? = null,
        val ownershipLabelsSha256: String? = null,
        val boundaryRefinementModelId: String? = null,
        val boundaryRefinementModelVersion: String? = null
    )

    data class Derivative(
        val manifest: Manifest,
        val depth: SpatialDepthData,
        val ldiLite: SpatialLdiLiteData? = null
    )

    fun hasValid(sourcePath: String): Boolean = loadManifest(sourcePath) != null

    /**
     * 旧 renderer 仍可读取，以便保留回退与诊断能力；进入空间模式时则应重新生成已经
     * 改变运动场语义的派生。单层派生没有 renderer，不需要因此失效。
     */
    fun isCurrentGeneration(manifest: Manifest): Boolean =
        manifest.renderer == null ||
            manifest.renderer ==
            SpatialLdiRenderer.SURFACE_DEPTH_VNEXT11_ADAPTIVE_VISIBILITY_48PX.stableId

    /** 算法版本升级导致派生失效时，仍保留用户为同一源图选择的效果强度。 */
    fun retainedStrength(sourcePath: String): Float? {
        val source = File(sourcePath)
        if (!source.isFile) return null
        val manifestFile = File(derivativeDirectory(source), MANIFEST_FILE)
        if (!manifestFile.isFile) return null
        return runCatching {
            val manifest = gson.fromJson(
                manifestFile.readText(Charsets.UTF_8),
                Manifest::class.java
            )
            manifest.strength.takeIf {
                manifest.sourceSizeBytes == source.length() &&
                    manifest.sourceModifiedAtMs == source.lastModified() &&
                    manifest.sourceFingerprint == sourceFingerprint(source) &&
                    it.isFinite() && it in MIN_STRENGTH..MAX_STRENGTH
            }
        }.getOrNull()
    }

    fun loadManifest(sourcePath: String): Manifest? {
        val source = File(sourcePath)
        if (!source.isFile) return null
        val directory = derivativeDirectory(source)
        val manifestFile = File(directory, MANIFEST_FILE)
        if (!manifestFile.isFile || !File(directory, DEPTH_FILE).isFile) return null
        return runCatching {
            val manifest = gson.fromJson(manifestFile.readText(Charsets.UTF_8), Manifest::class.java)
            if (!manifestMatchesSource(manifest, source) ||
                (isLdiSchema(manifest.schemaVersion) &&
                    (
                        LDI_REQUIRED_FILES.any { !File(directory, it).isFile } ||
                            (manifest.displayAlphaSha256 != null &&
                                !File(directory, DISPLAY_ALPHA_FILE).isFile) ||
                            (manifest.ownershipAlphaSha256 != null &&
                                !File(directory, OWNERSHIP_ALPHA_FILE).isFile) ||
                            (manifest.subjectMaskSha256 != null &&
                                !File(directory, SUBJECT_MASK_FILE).isFile) ||
                            (manifest.ownershipLabelsSha256 != null &&
                                !File(directory, OWNERSHIP_LABELS_FILE).isFile) ||
                            (manifest.motionBasisSha256 != null &&
                                !File(directory, MOTION_BASIS_FILE).isFile)
                        ))
            ) {
                null
            } else {
                manifest
            }
        }.getOrNull()
    }

    fun load(sourcePath: String): Derivative? {
        val source = File(sourcePath)
        val manifest = loadManifest(sourcePath) ?: return null
        val depthFile = File(derivativeDirectory(source), DEPTH_FILE)
        return runCatching {
            if (sha256(depthFile) != manifest.depthSha256) return@runCatching null
            val values = readDepth(depthFile, manifest.width, manifest.height)
            val ldiLite = if (isLdiSchema(manifest.schemaVersion)) {
                loadLdiLite(derivativeDirectory(source), manifest)
            } else {
                null
            }
            Derivative(
                manifest = manifest,
                depth = SpatialDepthData(
                    width = manifest.width,
                    height = manifest.height,
                    values = values,
                    robustRange = manifest.robustRange,
                    strongEdgeRatio = manifest.strongEdgeRatio,
                    defaultStrength = manifest.strength
                ),
                ldiLite = ldiLite
            )
        }.getOrNull()
    }

    @Synchronized
    fun save(
        sourcePath: String,
        model: SpatialDepthModel,
        depth: SpatialDepthData,
        cancelled: AtomicBoolean? = null,
        ldiLite: SpatialLdiLiteData? = null
    ): Derivative {
        val source = File(sourcePath)
        check(source.isFile) { "源图片不存在" }
        val finalDirectory = derivativeDirectory(source)
        val parent = rootDirectory()
        check(parent.exists() || parent.mkdirs()) { "无法创建空间照片派生目录" }
        ensureSufficientSpace(parent, depth, ldiLite)
        val pending = File(parent, "${finalDirectory.name}.pending-${UUID.randomUUID()}")
        check(pending.mkdirs()) { "无法创建派生临时目录" }

        try {
            checkNotCancelled(cancelled)
            val depthFile = File(pending, DEPTH_FILE)
            writeDepth(depthFile, depth)
            checkNotCancelled(cancelled)
            val ldiHashes = if (ldiLite != null) {
                writeLdiLite(pending, ldiLite, cancelled)
            } else {
                null
            }
            val manifest = Manifest(
                schemaVersion = when (ldiLite?.renderer) {
                    null -> LEGACY_SCHEMA_VERSION
                    SpatialLdiRenderer.LEGACY_V19 -> LDI_LITE_SCHEMA_VERSION
                    SpatialLdiRenderer.SURFACE_CHARTS_VNEXT1 -> VNEXT1_SCHEMA_VERSION
                    SpatialLdiRenderer.SURFACE_CHARTS_VNEXT2_AFFINE_RESIDUAL ->
                        VNEXT2_SCHEMA_VERSION
                    SpatialLdiRenderer.SURFACE_CHARTS_VNEXT3_RIGID_CHARTS ->
                        VNEXT3_SCHEMA_VERSION
                    SpatialLdiRenderer.SURFACE_CHARTS_VNEXT4_RIGID_SUBJECTS ->
                        VNEXT4_SCHEMA_VERSION
                    SpatialLdiRenderer.SURFACE_CHARTS_VNEXT5_LOCAL_SIMILARITY ->
                        VNEXT5_SCHEMA_VERSION
                    SpatialLdiRenderer.SURFACE_CHARTS_VNEXT6_DIRECTIONAL_36PX ->
                        VNEXT6_SCHEMA_VERSION
                    SpatialLdiRenderer.SURFACE_CHARTS_VNEXT7_DIRECTIONAL_36PX_VOLUME_BALANCED ->
                        VNEXT7_SCHEMA_VERSION
                    SpatialLdiRenderer.SURFACE_DEPTH_VNEXT8_GLOBAL_CONTINUOUS_28PX ->
                        VNEXT8_SCHEMA_VERSION
                    SpatialLdiRenderer.SURFACE_DEPTH_VNEXT9_MULTISCALE_INVERSE_28PX ->
                        VNEXT9_SCHEMA_VERSION
                    SpatialLdiRenderer.SURFACE_DEPTH_VNEXT10_VISIBILITY_36PX ->
                        VNEXT10_SCHEMA_VERSION
                    SpatialLdiRenderer.SURFACE_DEPTH_VNEXT11_ADAPTIVE_VISIBILITY_48PX ->
                        VNEXT11_SCHEMA_VERSION
                },
                sourceFingerprint = sourceFingerprint(source),
                sourceSizeBytes = source.length(),
                sourceModifiedAtMs = source.lastModified(),
                modelId = model.stableId,
                modelVersion = model.version,
                width = depth.width,
                height = depth.height,
                robustRange = depth.robustRange,
                strongEdgeRatio = depth.strongEdgeRatio,
                strength = depth.defaultStrength,
                depthSha256 = sha256(depthFile),
                renderMode = if (ldiLite == null) {
                    SpatialRenderMode.SINGLE_LAYER.stableId
                } else {
                    SpatialRenderMode.LDI_LITE.stableId
                },
                renderer = ldiLite?.renderer?.stableId,
                viewEnvelopeAmplitudes = ldiLite?.viewEnvelope?.persistedAmplitudes(),
                maximumLocalStrain = ldiLite?.viewEnvelope?.maximumLocalStrain,
                meshWidth = ldiLite?.geometry?.width,
                meshHeight = ldiLite?.geometry?.height,
                backgroundWidth = ldiLite?.backgroundBitmap?.width,
                backgroundHeight = ldiLite?.backgroundBitmap?.height,
                inpaintingModelId = ldiLite?.inpaintingModelId,
                inpaintingModelVersion = ldiLite?.inpaintingModelVersion,
                inpaintingQualityId = ldiLite?.inpaintingQualityId,
                meshDepthSha256 = ldiHashes?.meshDepth,
                backgroundDepthSha256 = ldiHashes?.backgroundDepth,
                connectivitySha256 = ldiHashes?.connectivity,
                motionBasisSha256 = ldiHashes?.motionBasis,
                backgroundSha256 = ldiHashes?.background,
                mattingModelId = ldiLite?.takeIf {
                    it.displayAlpha != null || it.ownershipAlpha != null ||
                        it.subjectMask != null
                }?.mattingModelId,
                mattingModelVersion = ldiLite?.takeIf {
                    it.displayAlpha != null || it.ownershipAlpha != null ||
                        it.subjectMask != null
                }?.mattingModelVersion,
                displayAlphaSha256 = if (ldiLite?.displayAlpha != null) {
                    val alphaFile = File(pending, DISPLAY_ALPHA_FILE)
                    writeCompressedBytes(alphaFile, ldiLite.displayAlpha)
                    sha256(alphaFile)
                } else {
                    null
                },
                ownershipAlphaSha256 = if (ldiLite?.ownershipAlpha != null) {
                    val alphaFile = File(pending, OWNERSHIP_ALPHA_FILE)
                    writeCompressedBytes(alphaFile, ldiLite.ownershipAlpha)
                    sha256(alphaFile)
                } else {
                    null
                },
                subjectMaskSha256 = if (ldiLite?.subjectMask != null) {
                    val maskFile = File(pending, SUBJECT_MASK_FILE)
                    writeCompressedBytes(maskFile, ldiLite.subjectMask)
                    sha256(maskFile)
                } else {
                    null
                },
                // vNext 只持久化模型对 cut／补图条件产生的结果，不必把生成期实例标签
                // 带入渲染；模型 provenance 仍须保留，便于缓存契约与诊断。
                segmentationModelId = ldiLite?.segmentationModelId,
                segmentationModelVersion = ldiLite?.segmentationModelVersion,
                boundaryRefinementModelId = ldiLite?.boundaryRefinementModelId,
                boundaryRefinementModelVersion = ldiLite?.boundaryRefinementModelVersion,
                ownershipLabelsSha256 = if (ldiLite?.ownershipLabels != null) {
                    val labelsFile = File(pending, OWNERSHIP_LABELS_FILE)
                    writeCompressedBytes(labelsFile, ldiLite.ownershipLabels)
                    sha256(labelsFile)
                } else {
                    null
                }
            )
            val manifestFile = File(pending, MANIFEST_FILE)
            FileOutputStream(manifestFile).use { output ->
                output.write((gson.toJson(manifest) + "\n").toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            check(readDepth(depthFile, depth.width, depth.height).size == depth.values.size)
            if (ldiLite != null) {
                loadLdiLite(pending, manifest).backgroundBitmap.recycle()
            }
            checkNotCancelled(cancelled)

            val backup = File(parent, "${finalDirectory.name}.backup")
            if (backup.exists()) check(backup.deleteRecursively())
            if (finalDirectory.exists()) check(finalDirectory.renameTo(backup)) {
                "无法备份旧派生结果"
            }
            if (cancelled?.get() == true) {
                if (backup.exists()) backup.renameTo(finalDirectory)
                error("任务已取消")
            }
            if (!pending.renameTo(finalDirectory)) {
                if (backup.exists()) backup.renameTo(finalDirectory)
                error("无法发布空间照片派生结果")
            }
            if (cancelled?.get() == true) {
                finalDirectory.deleteRecursively()
                if (backup.exists()) backup.renameTo(finalDirectory)
                error("任务已取消")
            }
            if (backup.exists()) backup.deleteRecursively()
            return Derivative(manifest, depth, ldiLite)
        } catch (error: Throwable) {
            pending.deleteRecursively()
            throw error
        }
    }

    @Synchronized
    fun updateStrength(sourcePath: String, strength: Float): Boolean {
        val source = File(sourcePath)
        val directory = derivativeDirectory(source)
        val old = loadManifest(sourcePath) ?: return false
        val updated = old.copy(strength = strength.coerceIn(MIN_STRENGTH, MAX_STRENGTH))
        return replaceManifest(directory, updated)
    }

    @Synchronized
    fun updateRenderMode(sourcePath: String, mode: SpatialRenderMode): Boolean {
        val source = File(sourcePath)
        val directory = derivativeDirectory(source)
        val old = loadManifest(sourcePath) ?: return false
        val resolved = SpatialRenderMode.resolve(
            mode.stableId,
            isLdiSchema(old.schemaVersion)
        )
        return replaceManifest(
            directory = directory,
            manifest = old.copy(renderMode = resolved.stableId)
        )
    }

    @Synchronized
    fun remove(sourcePath: String): Boolean {
        val directory = derivativeDirectory(File(sourcePath))
        return !directory.exists() || directory.deleteRecursively()
    }

    @Synchronized
    fun clearAll(): Boolean {
        val root = rootDirectory()
        return !root.exists() || root.deleteRecursively()
    }

    fun totalBytes(): Long = directoryBytes(rootDirectory())

    private fun derivativeDirectory(source: File): File =
        File(rootDirectory(), sourceIdentity(source))

    private fun rootDirectory(): File =
        File(context.noBackupFilesDir, "spatial-photo/derivatives")

    private fun manifestMatchesSource(manifest: Manifest, source: File): Boolean =
        manifest.schemaVersion in SUPPORTED_SCHEMA_VERSIONS &&
            manifest.sourceSizeBytes == source.length() &&
            manifest.sourceModifiedAtMs == source.lastModified() &&
            manifest.sourceFingerprint == sourceFingerprint(source) &&
            manifest.width in 1..MAX_DEPTH_DIMENSION &&
            manifest.height in 1..MAX_DEPTH_DIMENSION &&
            manifest.width.toLong() * manifest.height <= MAX_DEPTH_PIXELS &&
            manifest.robustRange.isFinite() &&
            manifest.strongEdgeRatio.isFinite() &&
            manifest.strength.isFinite() &&
            manifest.strength in MIN_STRENGTH..MAX_STRENGTH &&
            validRenderMode(manifest) &&
            (
                manifest.schemaVersion == LEGACY_SCHEMA_VERSION ||
                    validLdiManifest(manifest)
                )

    private fun validRenderMode(manifest: Manifest): Boolean {
        if (manifest.renderMode == null) return true
        val mode = SpatialRenderMode.fromStableId(manifest.renderMode) ?: return false
        return mode != SpatialRenderMode.LDI_LITE ||
            isLdiSchema(manifest.schemaVersion)
    }

    private fun replaceManifest(directory: File, manifest: Manifest): Boolean {
        val atomicFile = AtomicFile(File(directory, MANIFEST_FILE))
        var output: FileOutputStream? = null
        return try {
            val stream = atomicFile.startWrite()
            output = stream
            stream.write((gson.toJson(manifest) + "\n").toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(stream)
            output = null
            true
        } catch (_: Exception) {
            output?.let(atomicFile::failWrite)
            false
        }
    }

    private fun sourceIdentity(source: File): String {
        val canonical = runCatching { source.canonicalPath }.getOrElse { source.absolutePath }
        return digestText("$canonical\u0000${source.length()}\u0000${source.lastModified()}")
    }

    private fun sourceFingerprint(source: File): String {
        val canonical = runCatching { source.canonicalPath }.getOrElse { source.absolutePath }
        return digestText("$canonical\u0000${source.length()}\u0000${source.lastModified()}")
    }

    private fun writeDepth(file: File, depth: SpatialDepthData) {
        writeDepthValues(file, depth.width, depth.height, depth.values)
    }

    private fun writeDepthValues(
        file: File,
        width: Int,
        height: Int,
        values: FloatArray
    ) {
        check(values.size == width * height)
        FileOutputStream(file).use { fileOutput ->
            val compressed = DeflaterOutputStream(fileOutput)
            val output = DataOutputStream(compressed)
            output.writeLong(DEPTH_MAGIC)
            output.writeInt(width)
            output.writeInt(height)
            for (value in values) {
                output.writeShort((value.coerceIn(0f, 1f) * 65535f).toInt())
            }
            output.flush()
            compressed.finish()
            compressed.flush()
            fileOutput.fd.sync()
        }
    }

    private fun writeLdiLite(
        directory: File,
        data: SpatialLdiLiteData,
        cancelled: AtomicBoolean?
    ): LdiHashes {
        val geometry = data.geometry
        val meshDepth = File(directory, MESH_DEPTH_FILE)
        writeDepthValues(
            meshDepth,
            geometry.width,
            geometry.height,
            geometry.surfaceDepth
        )
        checkNotCancelled(cancelled)
        val backgroundDepth = File(directory, BACKGROUND_DEPTH_FILE)
        writeDepthValues(
            backgroundDepth,
            geometry.width,
            geometry.height,
            geometry.backgroundDepth
        )
        checkNotCancelled(cancelled)
        val connectivity = File(directory, CONNECTIVITY_FILE)
        writeConnectivity(connectivity, geometry)
        checkNotCancelled(cancelled)
        val motionBasis = geometry.motionBasis?.let { basis ->
            File(directory, MOTION_BASIS_FILE).also { file ->
                writeMotionBasis(file, basis)
            }
        }
        checkNotCancelled(cancelled)
        val background = File(directory, BACKGROUND_FILE)
        FileOutputStream(background).use { output ->
            check(
                data.backgroundBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            ) { "无法编码隐藏背景图" }
            output.fd.sync()
        }
        checkNotCancelled(cancelled)
        return LdiHashes(
            meshDepth = sha256(meshDepth),
            backgroundDepth = sha256(backgroundDepth),
            connectivity = sha256(connectivity),
            motionBasis = motionBasis?.let(::sha256),
            background = sha256(background)
        )
    }

    private fun loadLdiLite(directory: File, manifest: Manifest): SpatialLdiLiteData {
        val width = checkNotNull(manifest.meshWidth)
        val height = checkNotNull(manifest.meshHeight)
        val meshDepthFile = File(directory, MESH_DEPTH_FILE)
        val backgroundDepthFile = File(directory, BACKGROUND_DEPTH_FILE)
        val connectivityFile = File(directory, CONNECTIVITY_FILE)
        val motionBasisFile = File(directory, MOTION_BASIS_FILE)
        val backgroundFile = File(directory, BACKGROUND_FILE)
        check(sha256(meshDepthFile).equals(manifest.meshDepthSha256, ignoreCase = true))
        check(
            sha256(backgroundDepthFile)
                .equals(manifest.backgroundDepthSha256, ignoreCase = true)
        )
        check(
            sha256(connectivityFile)
                .equals(manifest.connectivitySha256, ignoreCase = true)
        )
        check(sha256(backgroundFile).equals(manifest.backgroundSha256, ignoreCase = true))
        val motionBasis = manifest.motionBasisSha256?.let { expected ->
            check(sha256(motionBasisFile).equals(expected, ignoreCase = true)) {
                "屏幕空间位移基校验失败"
            }
            readMotionBasis(motionBasisFile, width, height)
        }

        val surfaceDepth = readDepth(meshDepthFile, width, height)
        val backgroundDepth = readDepth(backgroundDepthFile, width, height)
        val connectivity = readConnectivity(connectivityFile, width, height)
        val background = checkNotNull(BitmapFactory.decodeFile(backgroundFile.absolutePath)) {
            "无法解码隐藏背景图"
        }
        check(
            background.width == manifest.backgroundWidth &&
                background.height == manifest.backgroundHeight
        ) {
            background.recycle()
            "隐藏背景图尺寸不符"
        }
        val displayAlpha = manifest.displayAlphaSha256?.let { expected ->
            val alphaFile = File(directory, DISPLAY_ALPHA_FILE)
            check(sha256(alphaFile).equals(expected, ignoreCase = true)) {
                background.recycle()
                "显示 alpha 平面校验失败"
            }
            readCompressedBytes(alphaFile, background.width * background.height)
        }
        val subjectMask = manifest.subjectMaskSha256?.let { expected ->
            val maskFile = File(directory, SUBJECT_MASK_FILE)
            check(sha256(maskFile).equals(expected, ignoreCase = true)) {
                background.recycle()
                "主体几何 mask 校验失败"
            }
            readCompressedBytes(maskFile, width * height)
        }
        val ownershipAlpha = manifest.ownershipAlphaSha256?.let { expected ->
            val alphaFile = File(directory, OWNERSHIP_ALPHA_FILE)
            check(sha256(alphaFile).equals(expected, ignoreCase = true)) {
                background.recycle()
                "对象 ownership alpha 校验失败"
            }
            readCompressedBytes(alphaFile, background.width * background.height)
        }
        val ownershipLabels = manifest.ownershipLabelsSha256?.let { expected ->
            val labelsFile = File(directory, OWNERSHIP_LABELS_FILE)
            check(sha256(labelsFile).equals(expected, ignoreCase = true)) {
                background.recycle()
                "对象 ownership 标签校验失败"
            }
            readCompressedBytes(labelsFile, width * height)
        }
        val renderer = checkNotNull(SpatialLdiRenderer.fromStableId(manifest.renderer)) {
            "未知空间场景 renderer"
        }
        val viewEnvelope = when (renderer) {
            SpatialLdiRenderer.LEGACY_V19 -> null
            SpatialLdiRenderer.SURFACE_CHARTS_VNEXT1,
            SpatialLdiRenderer.SURFACE_CHARTS_VNEXT2_AFFINE_RESIDUAL,
            SpatialLdiRenderer.SURFACE_CHARTS_VNEXT3_RIGID_CHARTS,
            SpatialLdiRenderer.SURFACE_CHARTS_VNEXT4_RIGID_SUBJECTS,
            SpatialLdiRenderer.SURFACE_CHARTS_VNEXT5_LOCAL_SIMILARITY,
            SpatialLdiRenderer.SURFACE_CHARTS_VNEXT6_DIRECTIONAL_36PX,
            SpatialLdiRenderer.SURFACE_CHARTS_VNEXT7_DIRECTIONAL_36PX_VOLUME_BALANCED,
            SpatialLdiRenderer.SURFACE_DEPTH_VNEXT8_GLOBAL_CONTINUOUS_28PX,
            SpatialLdiRenderer.SURFACE_DEPTH_VNEXT9_MULTISCALE_INVERSE_28PX,
            SpatialLdiRenderer.SURFACE_DEPTH_VNEXT10_VISIBILITY_36PX,
            SpatialLdiRenderer.SURFACE_DEPTH_VNEXT11_ADAPTIVE_VISIBILITY_48PX -> checkNotNull(
                SpatialViewEnvelope.fromPersisted(
                    manifest.viewEnvelopeAmplitudes,
                    manifest.maximumLocalStrain
                )
            ) { "vNext 空间场景缺少有效视点包络" }
        }
        // vNext10 的隐藏背景运动基可以由持久化的表面运动、cut 与方向包络确定性重建。
        // 不再让补图层回退为另一套原始深度 warp，否则正确补全的纹理也会因前后层运动
        // 不同步而产生拖影。标签只在生成期决定哪些内部 cut 被删除，重建无需再次推理。
        val backgroundMotionBasis = if (
            renderer in setOf(
                SpatialLdiRenderer.SURFACE_DEPTH_VNEXT10_VISIBILITY_36PX,
                SpatialLdiRenderer.SURFACE_DEPTH_VNEXT11_ADAPTIVE_VISIBILITY_48PX
            ) &&
            motionBasis != null && viewEnvelope != null
        ) {
            SpatialVNextVisibilityBuilder.build(
                surfaceDepth = surfaceDepth,
                width = width,
                height = height,
                cutRight = connectivity.first,
                cutDown = connectivity.second,
                motionBasis = motionBasis,
                viewEnvelope = viewEnvelope
            ).backgroundMotionBasis
        } else {
            null
        }
        return SpatialLdiLiteData(
            geometry = SpatialLdiLiteGeometry(
                width = width,
                height = height,
                surfaceDepth = surfaceDepth,
                backgroundDepth = backgroundDepth,
                cutRight = connectivity.first,
                cutDown = connectivity.second,
                hiddenBackgroundMask = connectivity.third,
                motionBasis = motionBasis,
                backgroundMotionBasis = backgroundMotionBasis
            ),
            backgroundBitmap = background,
            inpaintingModelId = checkNotNull(manifest.inpaintingModelId),
            inpaintingModelVersion = checkNotNull(manifest.inpaintingModelVersion),
            renderer = renderer,
            viewEnvelope = viewEnvelope,
            inpaintingQualityId = manifest.inpaintingQualityId,
            displayAlpha = displayAlpha,
            ownershipAlpha = ownershipAlpha,
            ownershipLabels = ownershipLabels,
            subjectMask = subjectMask,
            mattingModelId = manifest.mattingModelId,
            mattingModelVersion = manifest.mattingModelVersion,
            segmentationModelId = manifest.segmentationModelId,
            segmentationModelVersion = manifest.segmentationModelVersion,
            boundaryRefinementModelId = manifest.boundaryRefinementModelId,
            boundaryRefinementModelVersion = manifest.boundaryRefinementModelVersion
        )
    }

    private fun writeCompressedBytes(file: File, data: ByteArray) {
        FileOutputStream(file).use { fileOutput ->
            val compressed = DeflaterOutputStream(fileOutput)
            compressed.write(data)
            compressed.finish()
            compressed.flush()
            fileOutput.fd.sync()
        }
    }

    private fun readCompressedBytes(file: File, expectedSize: Int): ByteArray {
        FileInputStream(file).use { fileInput ->
            InflaterInputStream(BufferedInputStream(fileInput)).use { input ->
                val data = input.readBytes()
                check(data.size == expectedSize) { "显示 alpha 平面尺寸不符" }
                return data
            }
        }
    }

    private fun writeMotionBasis(
        file: File,
        basis: SpatialScreenSpaceMotionBasis
    ) {
        FileOutputStream(file).use { fileOutput ->
            val compressed = DeflaterOutputStream(fileOutput)
            val output = DataOutputStream(compressed)
            output.writeLong(MOTION_BASIS_MAGIC)
            output.writeInt(basis.width)
            output.writeInt(basis.height)
            for (index in 0 until basis.width * basis.height) {
                output.writeFloat(basis.horizontalX[index])
                output.writeFloat(basis.horizontalY[index])
                output.writeFloat(basis.verticalX[index])
                output.writeFloat(basis.verticalY[index])
            }
            output.flush()
            compressed.finish()
            compressed.flush()
            fileOutput.fd.sync()
        }
    }

    private fun readMotionBasis(
        file: File,
        expectedWidth: Int,
        expectedHeight: Int
    ): SpatialScreenSpaceMotionBasis {
        FileInputStream(file).use { fileInput ->
            DataInputStream(InflaterInputStream(BufferedInputStream(fileInput))).use { input ->
                check(input.readLong() == MOTION_BASIS_MAGIC) {
                    "未知屏幕空间位移基格式"
                }
                val width = input.readInt()
                val height = input.readInt()
                check(width == expectedWidth && height == expectedHeight) {
                    "屏幕空间位移基尺寸不符"
                }
                val horizontalX = FloatArray(width * height)
                val horizontalY = FloatArray(width * height)
                val verticalX = FloatArray(width * height)
                val verticalY = FloatArray(width * height)
                for (index in horizontalX.indices) {
                    horizontalX[index] = input.readFloat()
                    horizontalY[index] = input.readFloat()
                    verticalX[index] = input.readFloat()
                    verticalY[index] = input.readFloat()
                }
                check(input.read() == -1) { "屏幕空间位移基包含尾随数据" }
                return SpatialScreenSpaceMotionBasis(
                    width = width,
                    height = height,
                    horizontalX = horizontalX,
                    horizontalY = horizontalY,
                    verticalX = verticalX,
                    verticalY = verticalY
                )
            }
        }
    }

    private fun writeConnectivity(file: File, geometry: SpatialLdiLiteGeometry) {
        FileOutputStream(file).use { fileOutput ->
            val compressed = DeflaterOutputStream(fileOutput)
            val output = DataOutputStream(compressed)
            output.writeLong(CONNECTIVITY_MAGIC)
            output.writeInt(geometry.width)
            output.writeInt(geometry.height)
            writeBooleanBits(output, geometry.cutRight)
            writeBooleanBits(output, geometry.cutDown)
            writeBooleanBits(output, geometry.hiddenBackgroundMask)
            output.flush()
            compressed.finish()
            compressed.flush()
            fileOutput.fd.sync()
        }
    }

    private fun readConnectivity(
        file: File,
        expectedWidth: Int,
        expectedHeight: Int
    ): Triple<BooleanArray, BooleanArray, BooleanArray> {
        FileInputStream(file).use { fileInput ->
            DataInputStream(InflaterInputStream(BufferedInputStream(fileInput))).use { input ->
                check(input.readLong() == CONNECTIVITY_MAGIC) {
                    "未知连接图文件格式"
                }
                val width = input.readInt()
                val height = input.readInt()
                check(width == expectedWidth && height == expectedHeight) {
                    "连接图尺寸不符"
                }
                val right = readBooleanBits(input, height * (width - 1))
                val down = readBooleanBits(input, (height - 1) * width)
                val hidden = readBooleanBits(input, width * height)
                check(input.read() == -1) { "连接图包含尾随数据" }
                return Triple(right, down, hidden)
            }
        }
    }

    private fun writeBooleanBits(output: DataOutputStream, values: BooleanArray) {
        output.writeInt(values.size)
        var byte = 0
        for (index in values.indices) {
            if (values[index]) byte = byte or (1 shl (index and 7))
            if ((index and 7) == 7 || index == values.lastIndex) {
                output.writeByte(byte)
                byte = 0
            }
        }
    }

    private fun readBooleanBits(
        input: DataInputStream,
        expectedSize: Int
    ): BooleanArray {
        check(input.readInt() == expectedSize) { "连接图元素数量不符" }
        val result = BooleanArray(expectedSize)
        var byte = 0
        for (index in result.indices) {
            if ((index and 7) == 0) byte = input.readUnsignedByte()
            result[index] = byte and (1 shl (index and 7)) != 0
        }
        return result
    }

    private fun readDepth(file: File, expectedWidth: Int, expectedHeight: Int): FloatArray {
        FileInputStream(file).use { fileInput ->
            DataInputStream(InflaterInputStream(BufferedInputStream(fileInput))).use { input ->
                check(input.readLong() == DEPTH_MAGIC) { "未知深度文件格式" }
                val width = input.readInt()
                val height = input.readInt()
                check(width == expectedWidth && height == expectedHeight) { "深度文件尺寸不符" }
                val values = FloatArray(width * height)
                for (index in values.indices) {
                    values[index] = input.readUnsignedShort() / 65535f
                }
                check(input.read() == -1) { "深度文件包含尾随数据" }
                return values
            }
        }
    }

    private fun digestText(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun sha256(file: File): String = SpatialModelStore.sha256(file)

    private fun directoryBytes(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf(::directoryBytes) ?: 0L
    }

    private fun ensureSufficientSpace(
        directory: File,
        depth: SpatialDepthData,
        ldiLite: SpatialLdiLiteData?
    ) {
        val uncompressedDepthBytes = depth.values.size.toLong() * 2L
        val ldiBytes = ldiLite?.let {
            it.geometry.surfaceDepth.size.toLong() * 4L +
                (it.geometry.motionBasis?.let { basis ->
                    basis.width.toLong() * basis.height * 4L * Float.SIZE_BYTES
                } ?: 0L) +
                it.geometry.cutRight.size / 8L +
                it.geometry.cutDown.size / 8L +
                (it.subjectMask?.size?.toLong() ?: 0L) +
                (it.ownershipLabels?.size?.toLong() ?: 0L) +
                (it.ownershipAlpha?.size?.toLong() ?: 0L) +
                it.backgroundBitmap.byteCount.toLong() * 2L
        } ?: 0L
        val required = uncompressedDepthBytes + ldiBytes + MIN_FREE_MARGIN_BYTES
        check(StatFs(directory.absolutePath).availableBytes >= required) {
            "存储空间不足，无法保存空间效果"
        }
    }

    private fun validLdiManifest(manifest: Manifest): Boolean =
        validRendererContract(manifest) &&
            manifest.meshWidth in 2..MAX_MESH_DIMENSION &&
            manifest.meshHeight in 2..MAX_MESH_DIMENSION &&
            manifest.meshWidth!!.toLong() * manifest.meshHeight!! <= MAX_MESH_PIXELS &&
            manifest.backgroundWidth in 2..MAX_BACKGROUND_DIMENSION &&
            manifest.backgroundHeight in 2..MAX_BACKGROUND_DIMENSION &&
            manifest.backgroundWidth!!.toLong() * manifest.backgroundHeight!! <=
            MAX_BACKGROUND_PIXELS &&
            SpatialInpaintingModel.fromStableId(manifest.inpaintingModelId) != null &&
            manifest.inpaintingModelVersion?.matches(MODEL_VERSION_REGEX) == true &&
            (
                manifest.inpaintingQualityId == null ||
                    SpatialInpaintingQuality.fromStableId(
                        manifest.inpaintingQualityId
                    ) != null
                ) &&
            manifest.meshDepthSha256?.matches(SHA256_REGEX) == true &&
            manifest.backgroundDepthSha256?.matches(SHA256_REGEX) == true &&
            manifest.connectivitySha256?.matches(SHA256_REGEX) == true &&
            (manifest.motionBasisSha256 == null ||
                manifest.motionBasisSha256.matches(SHA256_REGEX)) &&
            manifest.backgroundSha256?.matches(SHA256_REGEX) == true &&
            (manifest.displayAlphaSha256 == null ||
                manifest.displayAlphaSha256.matches(SHA256_REGEX)) &&
            (manifest.ownershipAlphaSha256 == null ||
                manifest.ownershipAlphaSha256.matches(SHA256_REGEX)) &&
            (manifest.subjectMaskSha256 == null ||
                manifest.subjectMaskSha256.matches(SHA256_REGEX)) &&
            (manifest.ownershipLabelsSha256 == null ||
                manifest.ownershipLabelsSha256.matches(SHA256_REGEX)) &&
            (
                manifest.segmentationModelId == null &&
                    manifest.segmentationModelVersion == null ||
                    (
                        SpatialSegmentationModel.fromStableId(
                            manifest.segmentationModelId
                        ) != null &&
                            manifest.segmentationModelVersion?.matches(
                                MODEL_VERSION_REGEX
                            ) == true
                        )
                ) &&
            (
                manifest.ownershipLabelsSha256 == null ||
                    SpatialSegmentationModel.fromStableId(
                        manifest.segmentationModelId
                    ) != null
                ) &&
            (
                manifest.boundaryRefinementModelId == null &&
                    manifest.boundaryRefinementModelVersion == null ||
                    (
                        SpatialBoundaryRefinementModel.fromStableId(
                            manifest.boundaryRefinementModelId
                        ) != null &&
                            manifest.boundaryRefinementModelVersion?.matches(
                                MODEL_VERSION_REGEX
                            ) == true &&
                            SpatialSegmentationModel.fromStableId(
                                manifest.segmentationModelId
                            ) != null
                        )
                )

    private fun validRendererContract(manifest: Manifest): Boolean = when (manifest.schemaVersion) {
        LDI_LITE_SCHEMA_VERSION ->
            manifest.renderer == SpatialLdiRenderer.LEGACY_V19.stableId &&
                manifest.viewEnvelopeAmplitudes == null &&
                manifest.maximumLocalStrain == null
        VNEXT1_SCHEMA_VERSION ->
            manifest.renderer == SpatialLdiRenderer.SURFACE_CHARTS_VNEXT1.stableId &&
                SpatialViewEnvelope.fromPersisted(
                    manifest.viewEnvelopeAmplitudes,
                    manifest.maximumLocalStrain
                ) != null
        VNEXT2_SCHEMA_VERSION ->
            manifest.renderer ==
                SpatialLdiRenderer.SURFACE_CHARTS_VNEXT2_AFFINE_RESIDUAL.stableId &&
                SpatialViewEnvelope.fromPersisted(
                    manifest.viewEnvelopeAmplitudes,
                    manifest.maximumLocalStrain
                ) != null
        VNEXT3_SCHEMA_VERSION ->
            manifest.renderer ==
                SpatialLdiRenderer.SURFACE_CHARTS_VNEXT3_RIGID_CHARTS.stableId &&
                SpatialViewEnvelope.fromPersisted(
                    manifest.viewEnvelopeAmplitudes,
                    manifest.maximumLocalStrain
                ) != null
        VNEXT4_SCHEMA_VERSION ->
            manifest.renderer ==
                SpatialLdiRenderer.SURFACE_CHARTS_VNEXT4_RIGID_SUBJECTS.stableId &&
                SpatialViewEnvelope.fromPersisted(
                    manifest.viewEnvelopeAmplitudes,
                    manifest.maximumLocalStrain
                ) != null
        VNEXT5_SCHEMA_VERSION ->
            manifest.renderer ==
                SpatialLdiRenderer.SURFACE_CHARTS_VNEXT5_LOCAL_SIMILARITY.stableId &&
                manifest.motionBasisSha256?.matches(SHA256_REGEX) == true &&
                SpatialViewEnvelope.fromPersisted(
                    manifest.viewEnvelopeAmplitudes,
                    manifest.maximumLocalStrain
                ) != null
        VNEXT6_SCHEMA_VERSION ->
            manifest.renderer ==
                SpatialLdiRenderer.SURFACE_CHARTS_VNEXT6_DIRECTIONAL_36PX.stableId &&
                manifest.motionBasisSha256?.matches(SHA256_REGEX) == true &&
                SpatialViewEnvelope.fromPersisted(
                    manifest.viewEnvelopeAmplitudes,
                    manifest.maximumLocalStrain
                ) != null
        VNEXT7_SCHEMA_VERSION ->
            manifest.renderer ==
                SpatialLdiRenderer.SURFACE_CHARTS_VNEXT7_DIRECTIONAL_36PX_VOLUME_BALANCED.stableId &&
                manifest.motionBasisSha256?.matches(SHA256_REGEX) == true &&
                SpatialViewEnvelope.fromPersisted(
                    manifest.viewEnvelopeAmplitudes,
                    manifest.maximumLocalStrain
                ) != null
        VNEXT8_SCHEMA_VERSION ->
            manifest.renderer ==
                SpatialLdiRenderer.SURFACE_DEPTH_VNEXT8_GLOBAL_CONTINUOUS_28PX.stableId &&
                manifest.motionBasisSha256?.matches(SHA256_REGEX) == true &&
                SpatialViewEnvelope.fromPersisted(
                    manifest.viewEnvelopeAmplitudes,
                    manifest.maximumLocalStrain
                ) != null
        VNEXT9_SCHEMA_VERSION ->
            manifest.renderer ==
                SpatialLdiRenderer.SURFACE_DEPTH_VNEXT9_MULTISCALE_INVERSE_28PX.stableId &&
                manifest.motionBasisSha256?.matches(SHA256_REGEX) == true &&
                SpatialViewEnvelope.fromPersisted(
                    manifest.viewEnvelopeAmplitudes,
                    manifest.maximumLocalStrain
                ) != null
        VNEXT10_SCHEMA_VERSION ->
            manifest.renderer ==
                SpatialLdiRenderer.SURFACE_DEPTH_VNEXT10_VISIBILITY_36PX.stableId &&
                manifest.motionBasisSha256?.matches(SHA256_REGEX) == true &&
                SpatialViewEnvelope.fromPersisted(
                    manifest.viewEnvelopeAmplitudes,
                    manifest.maximumLocalStrain
                ) != null
        VNEXT11_SCHEMA_VERSION ->
            manifest.renderer ==
                SpatialLdiRenderer.SURFACE_DEPTH_VNEXT11_ADAPTIVE_VISIBILITY_48PX.stableId &&
                manifest.motionBasisSha256?.matches(SHA256_REGEX) == true &&
                SpatialViewEnvelope.fromPersisted(
                    manifest.viewEnvelopeAmplitudes,
                    manifest.maximumLocalStrain
                ) != null
        else -> false
    }

    private fun checkNotCancelled(cancelled: AtomicBoolean?) {
        check(cancelled?.get() != true) { "任务已取消" }
    }

    companion object {
        const val MIN_STRENGTH = 0.15f
        const val MAX_STRENGTH = 1f
        private const val LEGACY_SCHEMA_VERSION = 1
        private const val LDI_LITE_SCHEMA_VERSION = 2
        private const val VNEXT1_SCHEMA_VERSION = 3
        private const val VNEXT2_SCHEMA_VERSION = 4
        private const val VNEXT3_SCHEMA_VERSION = 5
        private const val VNEXT4_SCHEMA_VERSION = 6
        private const val VNEXT5_SCHEMA_VERSION = 7
        private const val VNEXT6_SCHEMA_VERSION = 8
        private const val VNEXT7_SCHEMA_VERSION = 9
        private const val VNEXT8_SCHEMA_VERSION = 10
        private const val VNEXT9_SCHEMA_VERSION = 11
        private const val VNEXT10_SCHEMA_VERSION = 12
        private const val VNEXT11_SCHEMA_VERSION = 13
        private val SUPPORTED_SCHEMA_VERSIONS =
            setOf(
                LEGACY_SCHEMA_VERSION,
                LDI_LITE_SCHEMA_VERSION,
                VNEXT1_SCHEMA_VERSION,
                VNEXT2_SCHEMA_VERSION,
                VNEXT3_SCHEMA_VERSION,
                VNEXT4_SCHEMA_VERSION,
                VNEXT5_SCHEMA_VERSION,
                VNEXT6_SCHEMA_VERSION,
                VNEXT7_SCHEMA_VERSION,
                VNEXT8_SCHEMA_VERSION,
                VNEXT9_SCHEMA_VERSION,
                VNEXT10_SCHEMA_VERSION,
                VNEXT11_SCHEMA_VERSION
            )

        private fun isLdiSchema(schemaVersion: Int): Boolean =
            schemaVersion == LDI_LITE_SCHEMA_VERSION ||
                schemaVersion == VNEXT1_SCHEMA_VERSION ||
                schemaVersion == VNEXT2_SCHEMA_VERSION ||
                schemaVersion == VNEXT3_SCHEMA_VERSION ||
                schemaVersion == VNEXT4_SCHEMA_VERSION ||
                schemaVersion == VNEXT5_SCHEMA_VERSION ||
                schemaVersion == VNEXT6_SCHEMA_VERSION ||
                schemaVersion == VNEXT7_SCHEMA_VERSION ||
                schemaVersion == VNEXT8_SCHEMA_VERSION ||
                schemaVersion == VNEXT9_SCHEMA_VERSION ||
                schemaVersion == VNEXT10_SCHEMA_VERSION ||
                schemaVersion == VNEXT11_SCHEMA_VERSION
        private const val MANIFEST_FILE = "manifest.json"
        private const val DEPTH_FILE = "depth.u16z"
        private const val MESH_DEPTH_FILE = "mesh-depth.u16z"
        private const val BACKGROUND_DEPTH_FILE = "background-depth.u16z"
        private const val DISPLAY_ALPHA_FILE = "display-alpha.a8z"
        private const val OWNERSHIP_ALPHA_FILE = "ownership-alpha.a8z"
        private const val SUBJECT_MASK_FILE = "subject-mask.a8z"
        private const val OWNERSHIP_LABELS_FILE = "ownership-labels.u8z"
        private const val CONNECTIVITY_FILE = "connectivity.bits.z"
        private const val MOTION_BASIS_FILE = "motion-basis.f32z"
        private const val BACKGROUND_FILE = "background.png"
        private val LDI_REQUIRED_FILES = listOf(
            MESH_DEPTH_FILE,
            BACKGROUND_DEPTH_FILE,
            CONNECTIVITY_FILE,
            BACKGROUND_FILE
        )
        private const val DEPTH_MAGIC = 0x5350444550544831L // SPDEPTH1
        private const val CONNECTIVITY_MAGIC = 0x53504D4553483031L // SPMESH01
        private const val MOTION_BASIS_MAGIC = 0x5350424153495331L // SPBASIS1
        private const val MIN_FREE_MARGIN_BYTES = 1024L * 1024L
        private const val MAX_DEPTH_DIMENSION = 1024
        private const val MAX_DEPTH_PIXELS = 1024L * 1024L
        private const val MAX_MESH_DIMENSION = 1024
        private const val MAX_MESH_PIXELS = 1024L * 1024L
        private const val MAX_BACKGROUND_DIMENSION = 2048
        private const val MAX_BACKGROUND_PIXELS = 2048L * 2048L
        private val SHA256_REGEX = Regex("[0-9a-fA-F]{64}")
        private val MODEL_VERSION_REGEX = Regex("[A-Za-z0-9._-]{1,64}")
        private val gson = Gson()
    }

    private data class LdiHashes(
        val meshDepth: String,
        val backgroundDepth: String,
        val connectivity: String,
        val motionBasis: String?,
        val background: String
    )
}
