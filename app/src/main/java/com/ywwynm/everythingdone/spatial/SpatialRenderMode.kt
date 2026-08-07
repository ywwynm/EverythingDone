package com.ywwynm.everythingdone.spatial

/**
 * 空间照片的即时渲染模式。
 *
 * Legacy 单层模式只使用原图与深度图。vNext 派生下，“稳定”与“立体”都使用保形
 * chart + 隐藏背景：稳定关闭 matting alpha 显露，立体启用完整边缘显露；
 * MPI 是旧 debug 实验值，仅用于兼容已经写入 manifest 的派生记录；解析时会迁移到
 * source-locked LDI-lite，不再作为可选显示模式。模式只影响显示，不会重新运行深度或补图模型。
 */
enum class SpatialRenderMode(
    val stableId: String
) {
    SINGLE_LAYER("p0"),
    LDI_LITE("p1"),
    MPI("mpi");

    companion object {
        fun fromStableId(value: String?): SpatialRenderMode? =
            entries.firstOrNull { it.stableId == value }

        fun resolve(value: String?, hasLdiLite: Boolean): SpatialRenderMode {
            val stored = fromStableId(value)
            return when {
                stored == MPI && hasLdiLite -> LDI_LITE
                stored == LDI_LITE && !hasLdiLite -> SINGLE_LAYER
                stored == MPI && !hasLdiLite -> SINGLE_LAYER
                stored != null -> stored
                hasLdiLite -> LDI_LITE
                else -> SINGLE_LAYER
            }
        }
    }
}
