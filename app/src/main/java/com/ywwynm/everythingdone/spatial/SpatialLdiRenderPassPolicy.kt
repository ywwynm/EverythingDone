package com.ywwynm.everythingdone.spatial

/** LDI 各 pass 的深度缓冲契约，避免隐藏补图反过来遮挡已知源像素。 */
internal enum class SpatialLdiDepthFunction {
    LESS,
    LESS_OR_EQUAL
}

internal data class SpatialLdiPassState(
    val depthTest: Boolean,
    val depthWrite: Boolean,
    val depthFunction: SpatialLdiDepthFunction
)

internal object SpatialLdiRenderPassPolicy {
    /** 隐藏背景只是未覆盖像素的颜色兜底，不拥有可见层深度。 */
    val HIDDEN_BACKGROUND = SpatialLdiPassState(
        depthTest = false,
        depthWrite = false,
        depthFunction = SpatialLdiDepthFunction.LESS_OR_EQUAL
    )

    val CONNECTED_SURFACE = SpatialLdiPassState(
        depthTest = true,
        depthWrite = true,
        depthFunction = SpatialLdiDepthFunction.LESS_OR_EQUAL
    )

    /** 连通面同深度已覆盖时，不重复累积边界 splat 的半透明 alpha。 */
    val BOUNDARY_SPLAT = SpatialLdiPassState(
        depthTest = true,
        depthWrite = true,
        depthFunction = SpatialLdiDepthFunction.LESS
    )

}
