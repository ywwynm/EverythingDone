package com.ywwynm.everythingdone.spatial

/**
 * 空间照片实例 ownership 的可选模型。模型只提供“哪些像素属于同一对象”，不会替代深度、
 * matting 或补图。未被可靠识别的区域继续使用连续深度表面。
 */
enum class SpatialSegmentationModel(
    val stableId: String,
    val displayName: String,
    val version: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val inputSize: Int,
    val queryCount: Int,
    val classLogitCount: Int,
    /** COCO 预训练头保留 slot 0；真实稀疏 category ID 从 1 开始。 */
    val firstForegroundClassId: Int,
    /** no-object 没有独立 logit；slot 90 仍是有效的 toothbrush 类。 */
    val lastForegroundClassId: Int,
    val maskSize: Int,
    val confidenceThreshold: Float,
    val minimumTotalRamMb: Int,
    val minimumAvailableRamMb: Int,
    val licenseId: String
) {
    RF_DETR_SEG_NANO(
        stableId = "rf_detr_seg_nano",
        displayName = "RF-DETR Seg Nano",
        version = "1.0.0",
        fileName = "rfdetr_seg_nano_312.onnx",
        sizeBytes = 122_831_761L,
        sha256 = "e126db3d03364ddad43299cdb354e0e85a12719a695e1ded3f271012b0d4fa97",
        inputSize = 312,
        queryCount = 100,
        classLogitCount = 91,
        firstForegroundClassId = 1,
        lastForegroundClassId = 90,
        maskSize = 78,
        confidenceThreshold = 0.45f,
        minimumTotalRamMb = 6_144,
        minimumAvailableRamMb = 1_024,
        licenseId = "Apache-2.0"
    );

    companion object {
        fun fromStableId(value: String?): SpatialSegmentationModel? =
            entries.firstOrNull { it.stableId == value }
    }
}
