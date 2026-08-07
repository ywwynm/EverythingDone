package com.ywwynm.everythingdone.spatial

/**
 * 持久化空间场景所使用的几何/渲染契约。
 *
 * v19 只用于读取已有派生；新生成结果使用 vNext，二者必须拥有不同 renderer ID，
 * 避免算法变化后复用语义不一致的缓存。
 */
enum class SpatialLdiRenderer(
    val stableId: String,
    val isVNext: Boolean
) {
    LEGACY_V19("ldi-lite-v19-segmentation-prior", false),
    SURFACE_CHARTS_VNEXT1("surface-charts-vnext1", true),
    SURFACE_CHARTS_VNEXT2_AFFINE_RESIDUAL(
        "surface-charts-vnext2-affine-residual",
        true
    ),
    SURFACE_CHARTS_VNEXT3_RIGID_CHARTS(
        "surface-charts-vnext3-rigid-charts",
        true
    ),
    SURFACE_CHARTS_VNEXT4_RIGID_SUBJECTS(
        "surface-charts-vnext4-rigid-subjects",
        true
    ),
    SURFACE_CHARTS_VNEXT5_LOCAL_SIMILARITY(
        "surface-charts-vnext5-local-similarity",
        true
    ),
    SURFACE_CHARTS_VNEXT6_DIRECTIONAL_36PX(
        "surface-charts-vnext6-directional-36px",
        true
    ),
    SURFACE_CHARTS_VNEXT7_DIRECTIONAL_36PX_VOLUME_BALANCED(
        "surface-charts-vnext7-directional-36px-volume-balanced",
        true
    ),
    SURFACE_DEPTH_VNEXT8_GLOBAL_CONTINUOUS_28PX(
        "surface-depth-vnext8-global-continuous-28px",
        true
    ),
    SURFACE_DEPTH_VNEXT9_MULTISCALE_INVERSE_28PX(
        "surface-depth-vnext9-multiscale-inverse-28px",
        true
    ),
    SURFACE_DEPTH_VNEXT10_VISIBILITY_36PX(
        "surface-depth-vnext10-visibility-36px",
        true
    ),
    SURFACE_DEPTH_VNEXT11_ADAPTIVE_VISIBILITY_48PX(
        "surface-depth-vnext11-adaptive-visibility-48px",
        true
    );

    /**
     * 该派生保存的是全局连续二维位移场。普通空间模式应使用 backward/inverse warp
     * 消费它，不能再把深度断边转成前向网格裂口。
     */
    val usesGlobalInverseWarp: Boolean
        get() = this == SURFACE_DEPTH_VNEXT8_GLOBAL_CONTINUOUS_28PX ||
            this == SURFACE_DEPTH_VNEXT9_MULTISCALE_INVERSE_28PX

    companion object {
        fun fromStableId(value: String?): SpatialLdiRenderer? =
            entries.firstOrNull { it.stableId == value }
    }
}
