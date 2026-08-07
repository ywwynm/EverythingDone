package com.ywwynm.everythingdone.spatial

import android.content.Context
import androidx.annotation.Keep
import com.google.crypto.tink.subtle.Ed25519Verify
import com.google.gson.Gson
import com.ywwynm.everythingdone.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Base64

@Keep
data class SpatialModelCatalog(
    val schemaVersion: Int,
    val channel: String,
    val catalogVersion: Long,
    val publishedAt: String,
    val runtimeVersion: Int,
    val models: List<SpatialModelCatalogEntry>,
    /**
     * schema 1 的可选扩展。旧 catalog/旧 App 均可忽略；当前 App 只接受内置固定 ABI。
     */
    val inpaintingModels: List<SpatialInpaintingCatalogEntry>?,
    /**
     * 旧版 schema 1 catalog 没有该字段；保持可空可以让已发布 catalog 和旧 App 双向兼容。
     */
    val runtimes: List<SpatialRuntimeCatalogEntry>?,
    /**
     * schema 1 的前向兼容扩展。旧 App 会忽略本字段，并继续只校验它认识的 MI-GAN；
     * 当前 App 把两组条目合并后再执行完整 ABI 校验。
     */
    val additionalInpaintingModels: List<SpatialInpaintingCatalogEntry>? = null,
    /** matting 边缘带细化模型（D54）；旧 App 忽略本字段。 */
    val mattingModels: List<SpatialMattingCatalogEntry>? = null,
    /** 可选实例分割 ownership provider；旧 App 忽略本字段。 */
    val segmentationModels: List<SpatialSegmentationCatalogEntry>? = null,
    /** 只细化既有实例轮廓的 promptable 模型包；旧 App 忽略本字段。 */
    val boundaryRefinementModels: List<SpatialBoundaryRefinementCatalogEntry>? = null
) {
    fun runtimeForCurrentDevice(): SpatialRuntimeCatalogEntry? {
        val abi = SpatialRuntimeStore.currentAbi() ?: return null
        return runtimes?.singleOrNull { it.abi == abi && it.isCompatible() }
    }

    fun allInpaintingModels(): List<SpatialInpaintingCatalogEntry> =
        inpaintingModels.orEmpty() + additionalInpaintingModels.orEmpty()
}

@Keep
data class SpatialModelCatalogEntry(
    val id: String,
    val version: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    val format: String,
    val precision: String,
    val license: String,
    val minDeviceRamMb: Int,
    val enabled: Boolean,
    val disabledReason: String?
) {
    fun builtInModel(): SpatialDepthModel? {
        val model = SpatialDepthModel.fromStableId(id) ?: return null
        return model.takeIf {
            version == it.version &&
                sizeBytes == it.sizeBytes &&
                sha256.equals(it.sha256, ignoreCase = true) &&
                minDeviceRamMb == it.minimumTotalRamMb &&
                format == "onnx" &&
                precision == "fp32"
        }
    }
}

@Keep
data class SpatialInpaintingCatalogEntry(
    val id: String,
    val version: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    val format: String,
    val precision: String,
    val license: String,
    val minDeviceRamMb: Int,
    val enabled: Boolean,
    val disabledReason: String?
) {
    fun builtInModel(): SpatialInpaintingModel? {
        val model = SpatialInpaintingModel.fromStableId(id) ?: return null
        return model.takeIf {
            version == it.version &&
                sizeBytes == it.sizeBytes &&
                sha256.equals(it.sha256, ignoreCase = true) &&
                minDeviceRamMb == it.minimumTotalRamMb &&
                format == "onnx" &&
                precision == it.inputContract.catalogPrecision &&
                license == it.licenseId
        }
    }
}

@Keep
data class SpatialMattingCatalogEntry(
    val id: String,
    val version: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    val format: String,
    val precision: String,
    val license: String,
    val minDeviceRamMb: Int,
    val enabled: Boolean,
    val disabledReason: String?
) {
    fun builtInModel(): SpatialMattingModel? {
        val model = SpatialMattingModel.fromStableId(id) ?: return null
        return model.takeIf {
            version == it.version &&
                sizeBytes == it.sizeBytes &&
                sha256.equals(it.sha256, ignoreCase = true) &&
                minDeviceRamMb == it.minimumTotalRamMb &&
                format == "onnx" &&
                precision == "fp32" &&
                license == it.licenseId
        }
    }
}

@Keep
data class SpatialSegmentationCatalogEntry(
    val id: String,
    val version: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    val format: String,
    val precision: String,
    val license: String,
    val minDeviceRamMb: Int,
    val enabled: Boolean,
    val disabledReason: String?
) {
    fun builtInModel(): SpatialSegmentationModel? {
        val model = SpatialSegmentationModel.fromStableId(id) ?: return null
        return model.takeIf {
            version == it.version &&
                sizeBytes == it.sizeBytes &&
                sha256.equals(it.sha256, ignoreCase = true) &&
                minDeviceRamMb == it.minimumTotalRamMb &&
                format == "onnx" &&
                precision == "fp32" &&
                license == it.licenseId
        }
    }
}

@Keep
data class SpatialBoundaryRefinementCatalogEntry(
    val id: String,
    val version: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    val format: String,
    val precision: String,
    val license: String,
    val minDeviceRamMb: Int,
    val enabled: Boolean,
    val disabledReason: String?
) {
    fun builtInModel(): SpatialBoundaryRefinementModel? {
        val model = SpatialBoundaryRefinementModel.fromStableId(id) ?: return null
        return model.takeIf {
            version == it.version &&
                sizeBytes == it.archiveSizeBytes &&
                sha256.equals(it.archiveSha256, ignoreCase = true) &&
                minDeviceRamMb == it.minimumTotalRamMb &&
                format == "zip-onnx-bundle" &&
                precision == "fp32" &&
                license == it.licenseId
        }
    }
}

@Keep
data class SpatialRuntimeCatalogEntry(
    val id: String,
    val packageVersion: String,
    val ortVersion: String,
    val runtimeApiVersion: Int,
    val abi: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    val unpackedSizeBytes: Long,
    val coreSizeBytes: Long,
    val coreSha256: String,
    val jniSizeBytes: Long,
    val jniSha256: String,
    val license: String,
    val enabled: Boolean,
    val disabledReason: String?
) {
    fun isCompatible(): Boolean =
        id == SpatialRuntimeStore.RUNTIME_ID &&
            ortVersion == SpatialRuntimeStore.ORT_VERSION &&
            runtimeApiVersion == SpatialRuntimeStore.RUNTIME_API_VERSION &&
            abi in SpatialRuntimeStore.SUPPORTED_ABIS &&
            packageVersion == SpatialRuntimeStore.REQUIRED_PACKAGE_VERSION &&
            sizeBytes in 1..MAX_ARCHIVE_BYTES &&
            coreSizeBytes in 1..MAX_CORE_BYTES &&
            jniSizeBytes in 1..MAX_JNI_BYTES &&
            unpackedSizeBytes == coreSizeBytes + jniSizeBytes &&
            sha256.matches(SHA256_REGEX) &&
            coreSha256.matches(SHA256_REGEX) &&
            jniSha256.matches(SHA256_REGEX) &&
            license == "MIT"

    companion object {
        private val SHA256_REGEX = Regex("[0-9a-fA-F]{64}")
        private const val MAX_ARCHIVE_BYTES = 64L * 1024L * 1024L
        private const val MAX_CORE_BYTES = 64L * 1024L * 1024L
        private const val MAX_JNI_BYTES = 2L * 1024L * 1024L
    }
}

@Keep
data class SpatialSignedCatalogEnvelope(
    val schemaVersion: Int,
    val keyId: String,
    val payloadBase64: String,
    val signatureBase64: String
)

/**
 * 远端 catalog 的字节级验签与 fail-closed 校验。
 *
 * 签名覆盖 payload 的原始 UTF-8 字节，不重新序列化 JSON，避免两端 canonicalization 差异。
 */
object SpatialCatalogVerifier {

    private val gson = Gson()

    fun verify(envelopeJson: ByteArray): SpatialModelCatalog {
        check(envelopeJson.size <= MAX_ENVELOPE_BYTES) { "catalog 体积异常" }
        val envelope = gson.fromJson(
            envelopeJson.toString(Charsets.UTF_8),
            SpatialSignedCatalogEnvelope::class.java
        )
        check(envelope.schemaVersion == ENVELOPE_SCHEMA_VERSION) { "未知 catalog 封装版本" }
        check(envelope.keyId == expectedKeyId()) { "catalog keyId 不受信任" }

        val payload = Base64.getDecoder().decode(envelope.payloadBase64)
        val signature = Base64.getDecoder().decode(envelope.signatureBase64)
        val publicKey = Base64.getDecoder().decode(
            BuildConfig.SPATIAL_MODEL_CATALOG_PUBLIC_KEY
        )
        check(payload.isNotEmpty() && payload.size <= MAX_PAYLOAD_BYTES) { "catalog payload 体积异常" }
        check(signature.size == 64) { "catalog 签名长度错误" }
        check(publicKey.size == 32) { "内置 catalog 公钥错误" }
        Ed25519Verify(publicKey).verify(signature, payload)

        val catalog = gson.fromJson(
            payload.toString(Charsets.UTF_8),
            SpatialModelCatalog::class.java
        )
        validateCatalog(catalog)
        return catalog
    }

    private fun validateCatalog(catalog: SpatialModelCatalog) {
        check(catalog.schemaVersion == CATALOG_SCHEMA_VERSION) { "未知 catalog schema" }
        check(catalog.channel == BuildConfig.SPATIAL_MODEL_CATALOG_CHANNEL) {
            "catalog 渠道与构建信任根不匹配"
        }
        check(catalog.catalogVersion > 0) { "catalog 版本无效" }
        check(catalog.runtimeVersion == RUNTIME_VERSION) { "catalog runtime ABI 不兼容" }
        check(catalog.models.size in 1..MAX_MODELS) { "catalog 模型数量异常" }
        check(catalog.models.map { it.id }.distinct().size == catalog.models.size) {
            "catalog 包含重复模型"
        }
        val inpaintingModels = catalog.allInpaintingModels()
        check(inpaintingModels.size <= MAX_INPAINTING_MODELS) {
            "catalog 补全模型数量异常"
        }
        check(inpaintingModels.map { it.id }.distinct().size == inpaintingModels.size) {
            "catalog 包含重复补全模型"
        }
        val runtimes = catalog.runtimes.orEmpty()
        check(runtimes.size <= SpatialRuntimeStore.SUPPORTED_ABIS.size) {
            "catalog 运行组件数量异常"
        }
        check(runtimes.map { it.abi }.distinct().size == runtimes.size) {
            "catalog 包含重复 ABI 运行组件"
        }

        val catalogHost = URI(BuildConfig.SPATIAL_MODEL_CATALOG_URL).host
        check(!catalogHost.isNullOrBlank()) { "内置 catalog URL 无效" }
        for (entry in catalog.models) {
            check(entry.builtInModel() != null) { "catalog 模型 ABI 不受当前 App 支持：${entry.id}" }
            val modelUri = URI(entry.url)
            check(modelUri.scheme == "https" || modelUri.scheme == "http") {
                "模型 URL 协议不受支持"
            }
            check(modelUri.host == catalogHost) { "模型 URL 不属于受信 catalog 主机" }
            check(modelUri.query.isNullOrEmpty() && modelUri.fragment.isNullOrEmpty()) {
                "模型对象必须使用无查询参数的不可变 URL"
            }
            check(entry.license.isNotBlank()) { "模型许可字段为空" }
            check(entry.disabledReason == null || !entry.enabled) {
                "可用模型不能带禁用原因"
            }
        }
        for (entry in inpaintingModels) {
            check(entry.builtInModel() != null) {
                "catalog 补全模型 ABI 不受当前 App 支持：${entry.id}"
            }
            validateImmutableObjectUrl(entry.url, catalogHost, "补全模型")
            check(entry.license == entry.builtInModel()?.licenseId) {
                "补全模型许可不受支持"
            }
            check(entry.disabledReason == null || !entry.enabled) {
                "可用补全模型不能带禁用原因"
            }
        }
        for (entry in runtimes) {
            check(entry.isCompatible()) {
                "catalog 运行组件 ABI 不受当前 App 支持：${entry.abi}"
            }
            validateImmutableObjectUrl(entry.url, catalogHost, "运行组件")
            check(entry.disabledReason == null || !entry.enabled) {
                "可用运行组件不能带禁用原因"
            }
        }
        val mattingModels = catalog.mattingModels.orEmpty()
        check(mattingModels.size <= MAX_MATTING_MODELS) { "catalog matting 模型数量异常" }
        check(mattingModels.map { it.id }.distinct().size == mattingModels.size) {
            "catalog 包含重复 matting 模型"
        }
        for (entry in mattingModels) {
            check(entry.builtInModel() != null) {
                "catalog matting 模型 ABI 不受当前 App 支持：${entry.id}"
            }
            validateImmutableObjectUrl(entry.url, catalogHost, "matting 模型")
            check(entry.disabledReason == null || !entry.enabled) {
                "可用 matting 模型不能带禁用原因"
            }
        }
        val segmentationModels = catalog.segmentationModels.orEmpty()
        check(segmentationModels.size <= MAX_SEGMENTATION_MODELS) {
            "catalog 实例分割模型数量异常"
        }
        check(segmentationModels.map { it.id }.distinct().size == segmentationModels.size) {
            "catalog 包含重复实例分割模型"
        }
        for (entry in segmentationModels) {
            check(entry.builtInModel() != null) {
                "catalog 实例分割模型 ABI 不受当前 App 支持：${entry.id}"
            }
            validateImmutableObjectUrl(entry.url, catalogHost, "实例分割模型")
            check(entry.disabledReason == null || !entry.enabled) {
                "可用实例分割模型不能带禁用原因"
            }
        }
        val boundaryRefinementModels = catalog.boundaryRefinementModels.orEmpty()
        check(boundaryRefinementModels.size <= MAX_BOUNDARY_REFINEMENT_MODELS) {
            "catalog 边界细化模型数量异常"
        }
        check(
            boundaryRefinementModels.map { it.id }.distinct().size ==
                boundaryRefinementModels.size
        ) { "catalog 包含重复边界细化模型" }
        for (entry in boundaryRefinementModels) {
            check(entry.builtInModel() != null) {
                "catalog 边界细化模型 ABI 不受当前 App 支持：${entry.id}"
            }
            validateImmutableObjectUrl(entry.url, catalogHost, "边界细化模型")
            check(entry.disabledReason == null || !entry.enabled) {
                "可用边界细化模型不能带禁用原因"
            }
        }
    }

    private fun validateImmutableObjectUrl(url: String, catalogHost: String, label: String) {
        val uri = URI(url)
        check(uri.scheme == "https" || uri.scheme == "http") {
            "$label URL 协议不受支持"
        }
        check(uri.host == catalogHost) { "$label URL 不属于受信 catalog 主机" }
        check(uri.query.isNullOrEmpty() && uri.fragment.isNullOrEmpty()) {
            "${label}对象必须使用无查询参数的不可变 URL"
        }
    }

    private fun expectedKeyId(): String =
        "${BuildConfig.SPATIAL_MODEL_CATALOG_CHANNEL}-2026-01"

    private const val ENVELOPE_SCHEMA_VERSION = 1
    private const val CATALOG_SCHEMA_VERSION = 1
    private const val RUNTIME_VERSION = 1
    private const val MAX_MODELS = 8
    private const val MAX_INPAINTING_MODELS = 8
    private const val MAX_MATTING_MODELS = 8
    private const val MAX_SEGMENTATION_MODELS = 8
    private const val MAX_BOUNDARY_REFINEMENT_MODELS = 8
    private const val MAX_ENVELOPE_BYTES = 256 * 1024
    private const val MAX_PAYLOAD_BYTES = 192 * 1024
}

class SpatialCatalogClient(
    private val context: Context
) {
    data class Result(
        val catalog: SpatialModelCatalog,
        val fromCache: Boolean
    )

    fun fetchOrCached(): Result {
        val networkError = runCatching {
            val bytes = download(BuildConfig.SPATIAL_MODEL_CATALOG_URL)
            val catalog = SpatialCatalogVerifier.verify(bytes)
            rejectRollback(catalog)
            saveCache(bytes, catalog.catalogVersion)
            return Result(catalog, fromCache = false)
        }.exceptionOrNull()

        val cache = cacheFile()
        if (cache.isFile) {
            val bytes = cache.readBytes()
            val catalog = SpatialCatalogVerifier.verify(bytes)
            return Result(catalog, fromCache = true)
        }
        throw IllegalStateException("无法取得可信模型目录", networkError)
    }

    private fun rejectRollback(catalog: SpatialModelCatalog) {
        val previous = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getLong(KEY_HIGHEST_CATALOG_VERSION, 0L)
        check(catalog.catalogVersion >= previous) { "拒绝 catalog 回滚" }
    }

    private fun saveCache(bytes: ByteArray, catalogVersion: Long) {
        val target = cacheFile()
        check(target.parentFile?.exists() == true || target.parentFile?.mkdirs() == true)
        val pending = File(target.parentFile, "${target.name}.pending")
        FileOutputStream(pending).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        // 验签通过后再替换最后一次有效 catalog。
        if (target.exists()) check(target.delete())
        check(pending.renameTo(target))
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_HIGHEST_CATALOG_VERSION, catalogVersion)
            .apply()
    }

    private fun download(url: String): ByteArray {
        check(url.isNotBlank()) { "当前构建未配置模型 catalog URL" }
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Accept-Encoding", "identity")
        try {
            val status = connection.responseCode
            check(status == HttpURLConnection.HTTP_OK) { "catalog HTTP $status" }
            val declared = connection.contentLengthLong
            check(declared in -1L..MAX_DOWNLOAD_BYTES.toLong()) { "catalog Content-Length 异常" }
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    check(output.size() + read <= MAX_DOWNLOAD_BYTES) { "catalog 超出体积上限" }
                    output.write(buffer, 0, read)
                }
                return output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun cacheFile(): File =
        File(context.noBackupFilesDir, "spatial-photo/catalog/${BuildConfig.SPATIAL_MODEL_CATALOG_CHANNEL}.json")

    companion object {
        private const val CONNECT_TIMEOUT_MS = 12_000
        private const val READ_TIMEOUT_MS = 20_000
        private const val MAX_DOWNLOAD_BYTES = 256 * 1024
        private const val PREFERENCES = "spatial_photo_catalog"
        private const val KEY_HIGHEST_CATALOG_VERSION = "highest_catalog_version"
    }
}
