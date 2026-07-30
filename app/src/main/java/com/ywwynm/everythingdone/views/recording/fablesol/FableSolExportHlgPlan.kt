package com.ywwynm.everythingdone.views.recording.fablesol

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 一次 HLG（含杜比视界 8.4 基层）导出实际使用的信号范围与肩部参数
 * （fablesol-video-export D134～D136、D140、D164、D165）。
 *
 * 它把三件事绑在一起，避免像素、量化边界和完成信息各自表述：
 *
 * - **肩部参数表**：以归一化容量 `C_n` 为键的 `ξ` 表，导出开始时按当前固定的 HDR 强度生成
 *   一次，整段不变（D127）；
 * - **方向域 `W_device` 表**：回环验证通过时才有，否则全方向停在名义 100%；
 * - **P010 量化边界**：Y′/Cb/Cr 各自的实际码值区间。
 *
 * [extended] 为假即 D135 的名义范围路径——它仍是有效的 BT.2020/HLG/limited range HDR 视频，
 * 只是各方向不能再使用 100%～109% 的扩展余量，部分高饱和高光会更早进入肩部压缩。
 */
internal class FableSolExportHlgPlan private constructor(
    val strength: Double,
    val shoulder: FableSolExportHlgTransform.ShoulderTable,
    val deviceGrid: FableSolExportHlgDeviceRange.Grid?,
    val signalRange: FableSolExportP010Math.SignalRange
) {

    /** 本次产物是否真的使用了名义 100% 以上的信号。 */
    val extended: Boolean get() = deviceGrid != null

    /** 本次实际允许的最高非线性 HLG 信号；诊断与完成信息读这一份。 */
    val peakSignal: Double
        get() = deviceGrid?.peakCeiling ?: FableSolExportHlgTransform.SIGNAL_NOMINAL

    /** 本次产物**实际**的信号范围；完成态与诊断只读这一份（D136）。 */
    val range: FableSolExportHlgRange
        get() = if (extended) FableSolExportHlgRange.EXTENDED else FableSolExportHlgRange.NOMINAL

    /** 参考实现：与 shader 同一条路径，供 JVM 测试直接比对。 */
    fun mapDisplayLinear(r: Double, g: Double, b: Double): DoubleArray =
        FableSolExportHlgTransform.mapDisplayLinear(r, g, b, strength, shoulder) { u ->
            deviceGrid?.ceilingFor(u) ?: FableSolExportHlgTransform.SIGNAL_NOMINAL
        }

    companion object {

        /** 名义范围路径：不建方向表，量化边界回到 64～940 / 64～960。 */
        fun nominal(strength: Double): FableSolExportHlgPlan = FableSolExportHlgPlan(
            strength = strength,
            shoulder = FableSolExportHlgTransform.buildShoulderTable(strength),
            deviceGrid = null,
            signalRange = FableSolExportP010Math.SignalRange.NOMINAL
        )

        /**
         * 由回环验证得出的安全区间建立扩展路径。
         *
         * 验证只放宽实际证明过的边界；无法构成任何可用扩展时自动退回 [nominal]，不把
         * "验证跑过了"当成"扩展可用"。
         */
        fun of(strength: Double, safe: FableSolExportHlgDeviceRange.SafeCodes?): FableSolExportHlgPlan {
            val codes = safe ?: return nominal(strength)
            val grid = FableSolExportHlgDeviceRange.buildGrid(codes) ?: return nominal(strength)
            return FableSolExportHlgPlan(
                strength = strength,
                shoulder = FableSolExportHlgTransform.buildShoulderTable(strength),
                deviceGrid = grid,
                signalRange = codes.toSignalRange()
            )
        }
    }
}

/**
 * HLG 两张查表的 GL 资源。
 *
 * 采用 `R32F` + `NEAREST` 并在着色器里手写插值，而不是靠纹理过滤：ES 3.0 核心不保证 32 位
 * 浮点纹理可过滤，而 `R16F` 的 11 位尾数在 `ξ` 这种要参与指数运算的量上留不下足够余量。
 * 手写插值同时让 shader 与 [FableSolExportHlgTransform.ShoulderTable.xiAt] 逐行对应。
 *
 * **非 HLG 档位也必须绑一张完整纹理**：sampler 绑到不完整纹理是未定义行为，即便那条分支
 * 不会执行（批次 2 已经踩过一次）。因此没有 plan 时上传 1×1 占位。
 */
internal class FableSolExportHlgTextures private constructor(
    val shoulderTextureId: Int,
    val shoulderSize: Int,
    val deviceTextureId: Int,
    val deviceGridSize: Int,
    val deviceEnabled: Boolean
) {

    fun release() {
        val ids = intArrayOf(shoulderTextureId, deviceTextureId).filter { it != 0 }
        if (ids.isNotEmpty()) GLES30.glDeleteTextures(ids.size, ids.toIntArray(), 0)
    }

    companion object {

        fun upload(plan: FableSolExportHlgPlan?): FableSolExportHlgTextures {
            if (plan == null) {
                return FableSolExportHlgTextures(
                    shoulderTextureId = uploadFloats(floatArrayOf(0f), 1, 1),
                    shoulderSize = 1,
                    deviceTextureId = uploadFloats(
                        floatArrayOf(FableSolExportHlgTransform.SIGNAL_NOMINAL.toFloat()), 1, 1
                    ),
                    deviceGridSize = 1,
                    deviceEnabled = false
                )
            }
            val shoulder = plan.shoulder.values
            val grid = plan.deviceGrid
            return FableSolExportHlgTextures(
                shoulderTextureId = uploadFloats(shoulder, shoulder.size, 1),
                shoulderSize = shoulder.size,
                deviceTextureId = if (grid == null) {
                    uploadFloats(
                        floatArrayOf(FableSolExportHlgTransform.SIGNAL_NOMINAL.toFloat()), 1, 1
                    )
                } else {
                    uploadFloats(grid.values, grid.gridSize, grid.gridSize * 3)
                },
                deviceGridSize = grid?.gridSize ?: 1,
                deviceEnabled = grid != null
            )
        }

        private fun uploadFloats(values: FloatArray, widthPx: Int, heightPx: Int): Int {
            val textures = IntArray(1)
            GLES30.glGenTextures(1, textures, 0)
            val texture = textures[0]
            if (texture == 0) return 0
            val buffer = ByteBuffer
                .allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
            buffer.asFloatBuffer().put(values)
            buffer.rewind()
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_R32F, widthPx, heightPx)
            GLES30.glTexSubImage2D(
                GLES30.GL_TEXTURE_2D, 0, 0, 0, widthPx, heightPx,
                GLES30.GL_RED, GLES30.GL_FLOAT, buffer
            )
            // R32F 在 ES 3.0 核心里不可过滤：过滤模式必须是 NEAREST，否则纹理不完整。
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE
            )
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            return texture
        }
    }
}
