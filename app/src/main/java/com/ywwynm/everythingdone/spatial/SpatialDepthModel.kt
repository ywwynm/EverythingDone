package com.ywwynm.everythingdone.spatial

/**
 * 空间照片 v1 的推理契约。
 *
 * 模型文件由签名 catalog 分发，但输入、输出和预处理属于 App 与模型之间的固定 ABI，不能由远端
 * catalog 任意改写。新 ABI 必须随 App 版本发布。
 */
enum class SpatialDepthModel(
    val stableId: String,
    val displayName: String,
    val version: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val inputSize: Int,
    val imageNetNormalization: Boolean,
    val outputHasChannelDimension: Boolean,
    /** 模型输出为 depth（近小远大）时为 true；只描述数值方向，不代表具有真实尺度。 */
    val outputIsDepth: Boolean = false,
    /** 只有经官方定义与相机内参校准、输出具备真实尺度的型号才能为 true。 */
    val providesMetricScale: Boolean = false,
    /**
     * 深度边缘过渡仅 1–2 px 的锐边模型为 true：归一化后对逆深度做小半径灰度闭运算，
     * 把发丝间隙这类亚网格结构并入前景团块。渲染网格（600 长边）无法表达条缕级几何，
     * 不闭合会在断边判定处形成密集交替断边与成串剥边碎块（2026-08-01 真机 A/B）。
     */
    val sharpDepthEdges: Boolean = false,
    /**
     * 视差对比度（绕 0.5 的线性缩放，1 = 不缩）。原始 depth 经 1/depth 归一化后主体-背景
     * 间隔可达 0.9（DAV2 类输出约 0.6），层间位移、显露带宽、边界撕裂与面内弯折随之
     * 放大（2026-08-01 单人像真机 A/B：主体位移过大、近物拉伸、耳坠被吞）。线性缩放
     * 保持单调且梯度均匀衰减，无分位数重映射的值域病理。
     */
    val disparityContrast: Float = 1f,
    val minimumTotalRamMb: Int,
    val minimumAvailableRamMb: Int
) {
    ZIPDEPTH(
        stableId = "zipdepth",
        displayName = "ZipDepth",
        version = "1.0.0",
        fileName = "zipdepth_base_npu_384.onnx",
        sizeBytes = 24_591_999L,
        sha256 = "200e7bc60787c14b1fd01a5d26d19cf91fa83d2d41810cb857c7fa27a052988e",
        inputSize = 384,
        imageNetNormalization = false,
        outputHasChannelDimension = true,
        minimumTotalRamMb = 3_072,
        minimumAvailableRamMb = 512
    ),
    DEPTH_ANYTHING_V2_SMALL(
        stableId = "depth_anything_v2_small",
        displayName = "Depth Anything V2 Small",
        version = "1.0.0",
        fileName = "depth_anything_v2_vits_518.onnx",
        sizeBytes = 98_941_040L,
        sha256 = "eb022aabc0ef2da038223719207b69053c6996b10833336fdd0b82dbe7bb290e",
        inputSize = 518,
        imageNetNormalization = true,
        outputHasChannelDimension = false,
        minimumTotalRamMb = 6_144,
        minimumAvailableRamMb = 1_024
    ),

    /**
     * 深度 PoC 两轮胜出的第三模型（发丝级边缘，halo 中位数 9px vs DAV2-S 27px /
     * ZipDepth 98px）。单目分支导出，输出为深度；运行期 native 峰值比 DAV2-S 高约
     * 300 MiB，可用内存门槛相应上调。需要 Runtime r3 的算子并集。
     */
    DEPTH_ANYTHING_3_SMALL(
        stableId = "depth_anything_3_small",
        displayName = "Depth Anything 3 Small",
        version = "1.0.0",
        fileName = "da3_small_mono_518.onnx",
        sizeBytes = 100_479_065L,
        sha256 = "36277e27cabd0c37bc71ac302e885ff48fce4a895943360d75c20d8f033ec420",
        inputSize = 518,
        imageNetNormalization = true,
        outputHasChannelDimension = false,
        outputIsDepth = true,
        sharpDepthEdges = true,
        // D55（0.72 压缩）退役于 2026-08-04：其三个动因——位移过大（现由 regularize
        // 应变上界与预算钳制约束）、内部撕裂（P2 归组禁断）、边界 halo（P3 组引导
        // 修正）——均已结构性解决，还原模型完整层次。字段保留供后续模型使用。
        disparityContrast = 1f,
        minimumTotalRamMb = 6_144,
        minimumAvailableRamMb = 1_536
    );

    val inputShape: LongArray
        get() = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())

    val outputShape: LongArray
        get() = if (outputHasChannelDimension) {
            longArrayOf(1, 1, inputSize.toLong(), inputSize.toLong())
        } else {
            longArrayOf(1, inputSize.toLong(), inputSize.toLong())
        }

    companion object {
        fun fromStableId(value: String?): SpatialDepthModel? =
            entries.firstOrNull { it.stableId == value }
    }
}
