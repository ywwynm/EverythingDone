package com.ywwynm.everythingdone.spatial

/**
 * 旧人像 matting 的固定推理 ABI（可选兼容组件）。MODNet 只提供人像 ownership 的软
 * 轮廓，不参与深度模型选择，也不代表通用对象 matting 的当前质量前沿。
 *
 * 输入协议为 MODNet 官方口径：双边等比缩放到长边 [referenceSize] 并各自对齐 32
 * 的动态尺寸，(x-0.5)/0.5 归一化，**不做方形补边**——补边的黑条会改变全局上下文
 * （PoC 实测与官方口径相关性仅 0.948）。输出 [1,1,H,W] alpha。
 */
enum class SpatialMattingModel(
    val stableId: String,
    val displayName: String,
    val version: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val referenceSize: Int,
    val minimumTotalRamMb: Int,
    val minimumAvailableRamMb: Int,
    val licenseId: String
) {
    MODNET_PHOTOGRAPHIC(
        stableId = "modnet_photographic",
        displayName = "MODNet",
        version = "1.0.0",
        fileName = "modnet_photographic.onnx",
        sizeBytes = 25_888_640L,
        sha256 = "07c308cf0fc7e6e8b2065a12ed7fc07e1de8febb7dc7839d7b7f15dd66584df9",
        referenceSize = 512,
        minimumTotalRamMb = 4_096,
        minimumAvailableRamMb = 512,
        licenseId = "Apache-2.0"
    );

    companion object {
        fun fromStableId(value: String?): SpatialMattingModel? =
            entries.firstOrNull { it.stableId == value }
    }
}
