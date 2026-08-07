package com.ywwynm.everythingdone.spatial

/**
 * 空间渲染的取景契约（P1，design-2026-08-03）。
 *
 * 边距是**每档强度的常量**：按当前视差幅度的全视点最坏情况（对角满偏移）一次算足，
 * 倾斜过程中不再随视点半径变化。此前"参考视点严格零裁切、偏移时按半径动态放大"
 * 的方案会把倾斜幅度直接耦合成整帧缩放（满偏移 ~13% 的前后呼吸），回中还有
 * 直通/裁切两路径切换的缩放突跳；恒定边距用静止时的轻微裁切换取运动全程零呼吸。
 * 该计算不读取图片内容，跨场景一致。
 */
internal object SpatialSourceLock {

    fun coverMargin(parallaxAmplitude: Float): Margin {
        // 安全裁切必须等比例：逐轴裁切会在极限视角改变人物宽高比。
        // 视点被 SpatialPhotoView 限制在单位圆内，最坏情况半径为 1：
        // 视差项按幅度 × 半幅深度偏移，刚性平移与采样保护项按视点半径线性。
        val isotropic = (
            parallaxAmplitude * WORST_CASE_VIEWPOINT_RADIUS * MAX_CENTERED_DEPTH_OFFSET +
                WORST_CASE_VIEWPOINT_RADIUS * (
                    SpatialRenderDepthStabilizer.RIGID_PAN_AMPLITUDE +
                        EDGE_SAMPLING_GUARD
                    )
            ).coerceAtMost(SpatialRenderDepthStabilizer.MAX_COVER_MARGIN)
        return Margin(x = isotropic, y = isotropic)
    }

    internal data class Margin(
        val x: Float,
        val y: Float
    )

    private const val WORST_CASE_VIEWPOINT_RADIUS = 1f
    private const val MAX_CENTERED_DEPTH_OFFSET = 0.5f
    // GLES2 网格边缘、MSAA sample 与深度量化合计需要多于一个归一化像素的余量；
    // 0.001 在 1440 长边的对角视点仍会偶发露出清屏色。
    private const val EDGE_SAMPLING_GUARD = 0.005f
}
