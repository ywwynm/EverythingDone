package com.ywwynm.everythingdone.spatial

enum class SpatialInpaintingInputContract(
    val catalogPrecision: String
) {
    UINT8_PIPELINE("uint8-pipeline"),
    FLOAT32_AOTGAN_RGB_MASK("float32-aotgan-rgb-mask"),

    /**
     * Big-LaMa：`image` float32 [1,3,512,512] RGB **0..1**、`mask` float32 [1,1,512,512]
     * **1=洞**，输出 float32 [1,3,512,512] RGB **0..255**。空间维在导出时写死 512，
     * 因此只能按原生像素分块推理（见 [SpatialInpaintingTiling]），不能像 AOT-GAN 那样
     * 缩到工作分辨率——缩图会直接吃掉 4–17px 宽的显露带（D160/D188）。
     * 桌面实测：洞外逐位还原（误差 0.0000 级），所以块间羽化在已知区域不引入偏差。
     */
    FLOAT32_LAMA_RGB_MASK_512("float32-lama-rgb-mask-512")
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
    ),

    /**
     * Big-LaMa（LaMa，WACV 2022，Apache-2.0）。带真值台上梯度相关 0.604 对 AOT-GAN 的
     * 0.088（D160），用户 2026-08-12 逐档目检后裁定它主观优于 MI-GAN，遂移植上端。
     *
     * 198 MiB 是三个补全模型里最大的，且**只能按 512 原生分块推理**（空间维写死），
     * 因此内存门槛显著高于另外两个：权重常驻约 200 MB，再加 512² 的激活。
     * 下面两个阈值是按体积比例外推的**估计值，尚未在真机上实测**，见 followups。
     */
    BIG_LAMA_PLACES2_512(
        stableId = "big_lama_places2_512",
        displayName = "Big-LaMa",
        version = "1.0.0",
        fileName = "big_lama_places2_512_fp32.onnx",
        sizeBytes = 208_044_816L,
        sha256 = "1faef5301d78db7dda502fe59966957ec4b79dd64e16f03ed96913c7a4eb68d6",
        minimumTotalRamMb = 8_192,
        minimumAvailableRamMb = 2_560,
        inputContract = SpatialInpaintingInputContract.FLOAT32_LAMA_RGB_MASK_512,
        licenseId = "Apache-2.0"
    );

    companion object {
        fun fromStableId(value: String?): SpatialInpaintingModel? =
            entries.firstOrNull { it.stableId == value }
    }
}
