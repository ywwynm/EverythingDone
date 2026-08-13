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
    ),
    SURFACE_CHARTS_VNEXT12_ALL_SURFACE_NORMALIZED_36PX(
        "surface-charts-vnext12-all-surface-normalized-36px",
        true
    ),
    SURFACE_DEPTH_VNEXT13_ADAPTIVE_SURFELS_36PX(
        "surface-depth-vnext13-adaptive-surfels-36px",
        true
    ),

    /**
     * 与 vNext13 同一套点元渲染，**只把运动基换成由米制深度与内参直接算出的真透视视差**
     * （[SpatialTrueParallaxMotion]），不再走局部刚性拟合。用户 2026-08-12 反馈端上
     * "不像空间照片、像直接对图片做 warp"，根因即在此（D204）。
     * 需要深度模型给出 `metricDepth` + `intrinsics`，目前只有 MoGe-2 满足。
     */
    SURFACE_DEPTH_VNEXT14_TRUE_PERSPECTIVE(
        "surface-depth-vnext14-true-perspective",
        true
    ),

    /**
     * 真透视视差 + **断边三角网格**，与网页端同一套表示。
     *
     * vNext14 沿用了 vNext13 的点元 splat：每个采样点画成一个 `GL_POINTS`，点大小按
     * **未形变**的网格间距算。真透视把视差放大到物理量级后，深度断崖处相邻点元被拉开的
     * 距离远超点大小，合成遍又只补"被对向邻点夹住的一像素裂缝"，于是剪影上出现棋盘状
     * 缺口——底板从主体身上透出来，就是用户反复看到的"透明条带"（D211）。
     *
     * 三角网格没有这个问题：三角形天然铺满顶点之间，只有**显式断边**才留洞，而那正是
     * 真显露区、由第二层承接。这一档同时拿回两样点元路径没有的东西：
     * **逐轴独立的运动基系数**（点元每点只有一个标量，两轴只能取几何平均，见 D210）
     * 与**前景软 α**（点元片元着色器把 alpha 写死 1.0，根本不采样 `displayAlpha`）。
     */
    SURFACE_DEPTH_VNEXT15_TRUE_PERSPECTIVE_MESH(
        "surface-depth-vnext15-true-perspective-mesh",
        true
    );

    /**
     * 该派生保存的是全局连续二维位移场。普通空间模式应使用 backward/inverse warp
     * 消费它，不能再把深度断边转成前向网格裂口。
     */
    val usesGlobalInverseWarp: Boolean
        get() = this == SURFACE_DEPTH_VNEXT8_GLOBAL_CONTINUOUS_28PX ||
            this == SURFACE_DEPTH_VNEXT9_MULTISCALE_INVERSE_28PX

    /** 需要目标位置浮点分子／分母／coverage 累加的全表面 chart 路径。 */
    val usesNormalizedSurfaceCharts: Boolean
        get() = this == SURFACE_CHARTS_VNEXT12_ALL_SURFACE_NORMALIZED_36PX

    /** 使用连续 guide 深度点元、目标空间深度测试和补景合成，不走三角形 warp。 */
    val usesDepthSurfels: Boolean
        get() = this == SURFACE_DEPTH_VNEXT13_ADAPTIVE_SURFELS_36PX ||
            this == SURFACE_DEPTH_VNEXT14_TRUE_PERSPECTIVE

    /**
     * 运动基是真透视视差（非拟合）。这一档**不得再套保形预算**——
     * `SpatialWarpBudget` 抑制的局部拉伸，在这里正是视差本身（D204）。
     */
    val usesTruePerspective: Boolean
        get() = this == SURFACE_DEPTH_VNEXT14_TRUE_PERSPECTIVE ||
            this == SURFACE_DEPTH_VNEXT15_TRUE_PERSPECTIVE_MESH

    /**
     * 取景边距**由生成期按真实位移场算好并落盘**，运行时不得再按幅度现推。
     * `SpatialSourceLock.coverMargin(amplitude)` 假定幅度是归一化位移，而真透视档的
     * 幅度单位是**米**，代进去得到的是没有意义的数。
     */
    val usesPersistedCoverMargin: Boolean
        get() = this == SURFACE_DEPTH_VNEXT15_TRUE_PERSPECTIVE_MESH

    /**
     * 前景表面先画进**超采样离屏目标**，再在分辨遍里做盒式降采样与窄缝闭合，最后叠到
     * 已画好底板的屏幕上。
     *
     * 剪影上那条残留细带是采样粒度问题：网格 532×714 铺到 1440 宽的渲染上是 2.7 px
     * 一个采样，而发丝是亚采样结构，断边只能落在采样格上，于是剪影阶梯化。网页端在这
     * 一层比端上多的正是**超采样**与**窄缝闭合**（D212）。
     */
    val usesSupersampledMesh: Boolean
        get() = this == SURFACE_DEPTH_VNEXT15_TRUE_PERSPECTIVE_MESH

    companion object {
        fun fromStableId(value: String?): SpatialLdiRenderer? =
            entries.firstOrNull { it.stableId == value }
    }
}
