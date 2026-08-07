package com.ywwynm.everythingdone.spatial

enum class SpatialInpaintingInputContract(
    val catalogPrecision: String
) {
    UINT8_PIPELINE("uint8-pipeline"),
    FLOAT32_AOTGAN_RGB_MASK("float32-aotgan-rgb-mask")
}

/**
 * 隐藏背景窄带补全的固定推理 ABI。它与用户可选择的两种深度模型职责不同，不参与深度模型选择。
 */
enum class SpatialInpaintingModel(
    val stableId: String,
    val displayName: String,
    val version: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val minimumTotalRamMb: Int,
    val minimumAvailableRamMb: Int,
    val inputContract: SpatialInpaintingInputContract,
    val licenseId: String
) {
    MIGAN_PLACES2_512_PIPELINE(
        stableId = "migan_places2_512_pipeline",
        displayName = "MI-GAN",
        version = "2.0.0",
        fileName = "migan_pipeline_v2.onnx",
        sizeBytes = 28_079_181L,
        sha256 = "6f1f3530a1a2324b19752018ce756088b07973cda8d7d890034ace5c8a48c40b",
        minimumTotalRamMb = 4_096,
        minimumAvailableRamMb = 768,
        inputContract = SpatialInpaintingInputContract.UINT8_PIPELINE,
        licenseId = "MIT"
    ),
    AOTGAN_PLACES2_512(
        stableId = "aotgan_places2_512",
        displayName = "AOT-GAN",
        version = "1.0.0",
        fileName = "aotgan_places2_512.onnx",
        sizeBytes = 60_989_366L,
        sha256 = "6b255797029da17f60ef1e8860c6a6ccad13a0de4f97ab877a69f937946388e4",
        minimumTotalRamMb = 6_144,
        minimumAvailableRamMb = 1_536,
        inputContract = SpatialInpaintingInputContract.FLOAT32_AOTGAN_RGB_MASK,
        licenseId = "Apache-2.0"
    );

    companion object {
        fun fromStableId(value: String?): SpatialInpaintingModel? =
            entries.firstOrNull { it.stableId == value }
    }
}
