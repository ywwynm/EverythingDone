package com.ywwynm.everythingdone.spatial

/** 将面向用户的“稳定／立体”映射到实际渲染拓扑。 */
internal enum class SpatialRenderPath {
    SOURCE_WARP,
    LAYERED_SCENE;

    companion object {
        fun resolve(
            mode: SpatialRenderMode,
            renderer: SpatialLdiRenderer,
            hasLayeredScene: Boolean
        ): SpatialRenderPath {
            if (renderer.usesGlobalInverseWarp && mode != SpatialRenderMode.MPI) {
                return SOURCE_WARP
            }
            if (!hasLayeredScene) return SOURCE_WARP
            return when {
                mode == SpatialRenderMode.LDI_LITE || mode == SpatialRenderMode.MPI ->
                    LAYERED_SCENE
                mode == SpatialRenderMode.SINGLE_LAYER && renderer.isVNext ->
                    LAYERED_SCENE
                else -> SOURCE_WARP
            }
        }
    }
}
