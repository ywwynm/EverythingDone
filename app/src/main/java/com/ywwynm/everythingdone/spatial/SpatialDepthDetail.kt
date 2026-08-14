package com.ywwynm.everythingdone.spatial

/**
 * MoGe 系模型的几何细节档位，对应 ONNX 的 `num_tokens` 输入。
 *
 * **这是 MoGe 唯一有效的细节旋钮。** 实测把输入长边从 518 抬到 1440，深度场的有效带宽
 * 完全持平（0.00238 / 0.00229 / 0.00237 / 0.00229）——喂更大的图只是把同一份低频结果
 * 插值上去。真正决定内在分辨率的是 `num_tokens`：它在 ViT patch-14 下换算成模型内部
 * 实际推理的像素数。
 *
 * 耗时随 tokens **超线性**增长（attention 是 O(n²)）：ViT-B 在 8 Gen 2 上实测
 * 1200/1800/2700/3600 分别是 5.2 / 8.6 / 14.4 / 25.4 秒——3600 是 1800 的两倍 tokens，
 * 却要近三倍时间，而有效带宽只 +17%。因此 3600 不作为档位提供。
 *
 * [STANDARD] 是唯一经过完整画质验收的档：1800 tokens 约合 588×602 px 内在分辨率，
 * 与渲染网格的 720 长边精确匹配。另两档的画质影响**尚未量化**，见
 * `docs/features/spatial-photo-effect/followups.md`。
 */
enum class SpatialDepthDetail(
    val stableId: String,
    val numTokens: Int,
    val minimumAvailableRamMb: Int
) {
    /** 内在分辨率低于渲染网格，属欠采样；用时间换等待，画质代价未量化。 */
    FAST("fast_1200", 1200, 1_280),

    /** 默认档：与 720 长边渲染网格精确匹配，既有全部画质结论都建立在这一档上。 */
    STANDARD("standard_1800", 1800, 1_536),

    /** 内在分辨率高于渲染网格，增益未量化；耗时约为标准档的 1.7 倍。 */
    FINE("fine_2700", 2700, 2_048);

    companion object {
        val DEFAULT = STANDARD

        fun fromStableId(value: String?): SpatialDepthDetail? =
            entries.firstOrNull { it.stableId == value }
    }
}
