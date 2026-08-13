package com.ywwynm.everythingdone.spatial

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import javax.imageio.ImageIO
import org.junit.Test

/**
 * 把 HEAD 的 vNext11 几何导出，供桌面查看器作**基线**渲染。
 *
 * 背景（2026-08-10 D142/D143）：桌面 `viewer-layered` 与提交版 vNext11 是两种完全不同的
 * 渲染——前者是 backward warp 两层合成，后者是前向网格 + 硬件深度缓冲 + 断边处真正断开的
 * 三角形。D125–D141 的桌面调优一直在优化前者，天花板本来就低于提交版。要继续用桌面链路
 * 迭代，第一步必须让查看器能渲染 vNext11 本身，之后任何候选都在同屏对比里裁定。
 *
 * 只在设置 `SPATIAL_VNEXT11_EXPORT_DIR` 时产出文件，常规单元测试不写盘。
 * 深度输入取 `diagnostic-depth/disparity.png`（由 `rebuild_diagnostic_depth.py` 从已发布的
 * disp_front/back 精确重建，无需重跑生成器）。
 *
 * 导出的是**逐样本数组**而不是 Kotlin 侧的分块顶点缓冲：网页端按同一条三角形规则
 * （跨 cutRight/cutDown 的三角形不生成）自行建网格即可，不必复刻 GLES2 的 16 位索引分块。
 */
class SpatialVNext11AssetExportTest {

    @Test
    fun `导出vNext11几何供桌面基线渲染`() {
        val outputRoot = System.getenv("SPATIAL_VNEXT11_EXPORT_DIR")
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?: return
        val projectRoot = findProjectRoot()
        val assetsRoot = File(projectRoot, "tmp/spatial-desktop-tuning/assets")
        require(assetsRoot.isDirectory) { "找不到桌面资产目录" }
        check(outputRoot.exists() || outputRoot.mkdirs())
        val selected = System.getenv("SPATIAL_VNEXT11_EXPORT_SCENES")
            .orEmpty()
            .split(',')
            .filter(String::isNotBlank)
            .toSet()

        assetsRoot.listFiles()
            .orEmpty()
            .filter(File::isDirectory)
            .filter { File(it, "diagnostic-depth/disparity.png").isFile }
            .filter { selected.isEmpty() || it.name in selected }
            .sortedBy(File::getName)
            .forEach { scene -> exportScene(scene, File(outputRoot, scene.name)) }
    }

    private fun exportScene(scene: File, output: File) {
        val depthImage = checkNotNull(
            ImageIO.read(File(scene, "diagnostic-depth/disparity.png"))
        ) { "无法读取 ${scene.name} 的视差图" }
        val sourceValues = FloatArray(depthImage.width * depthImage.height)
        for (y in 0 until depthImage.height) {
            for (x in 0 until depthImage.width) {
                sourceValues[y * depthImage.width + x] =
                    depthImage.raster.getSampleFloat(x, y, 0) / 255f
            }
        }
        // 网格尺寸沿用 app 的 MESH_LONG_EDGE=512；视差图本身已是 guide 分辨率，
        // 长边通常就是 512，这里仍按同一规则算一遍，保证与端侧一致。
        val (meshWidth, meshHeight) = scaledSize(
            depthImage.width,
            depthImage.height,
            MESH_LONG_EDGE
        )
        val result = SpatialVNextGeometryBuilder.build(
            width = meshWidth,
            height = meshHeight,
            sourceDepth = SpatialDepthData(
                width = depthImage.width,
                height = depthImage.height,
                values = sourceValues,
                robustRange = 1f,
                strongEdgeRatio = 0f,
                defaultStrength = 1f,
                sharpEdges = true
            )
        )
        val geometry = result.geometry
        val motion = checkNotNull(geometry.motionBasis) { "${scene.name} 缺少运动基" }
        check(output.exists() || output.mkdirs())

        DataOutputStream(
            BufferedOutputStream(FileOutputStream(File(output, EXPORT_FILE)))
        ).use { data ->
            data.writeLong(EXPORT_MAGIC)
            data.writeInt(EXPORT_VERSION)
            data.writeInt(geometry.width)
            data.writeInt(geometry.height)
            geometry.surfaceDepth.forEach(data::writeFloat)
            geometry.backgroundDepth.forEach(data::writeFloat)
            geometry.cutRight.forEach { data.writeByte(if (it) 1 else 0) }
            geometry.cutDown.forEach { data.writeByte(if (it) 1 else 0) }
            geometry.hiddenBackgroundMask.forEach { data.writeByte(if (it) 1 else 0) }
            motion.horizontalX.forEach(data::writeFloat)
            motion.horizontalY.forEach(data::writeFloat)
            motion.verticalX.forEach(data::writeFloat)
            motion.verticalY.forEach(data::writeFloat)
            val background = geometry.backgroundMotionBasis
            data.writeByte(if (background != null) 1 else 0)
            background?.let {
                it.horizontalX.forEach(data::writeFloat)
                it.horizontalY.forEach(data::writeFloat)
                it.verticalX.forEach(data::writeFloat)
                it.verticalY.forEach(data::writeFloat)
            }
        }

        // 运行时标量：网页端不复刻 Kotlin 的包络插值与取景边距公式，直接查表，
        // 避免两端各写一份公式后悄悄分叉。
        val envelope = result.viewEnvelope
        val steps = (0..STRENGTH_STEPS).map { index ->
            val strength = index.toFloat() / STRENGTH_STEPS
            val amplitude = envelope.maximumMotionAmplitude(strength)
            val margin = SpatialSourceLock.coverMargin(amplitude)
            """    { "strength": $strength, "maxAmplitude": $amplitude, """ +
                """"coverMarginX": ${margin.x}, "coverMarginY": ${margin.y} }"""
        }
        File(output, RUNTIME_FILE).writeText(
            buildString {
                appendLine("{")
                appendLine("""  "renderer": "${SpatialLdiRenderer
                    .SURFACE_DEPTH_VNEXT11_ADAPTIVE_VISIBILITY_48PX.stableId}",""")
                appendLine("""  "meshWidth": ${geometry.width},""")
                appendLine("""  "meshHeight": ${geometry.height},""")
                appendLine("""  "motionCandidateId": "${result.motionCandidateId}",""")
                appendLine("""  "mediumResidualWeight": ${result.mediumResidualWeight},""")
                appendLine("""  "reliefGain": ${SpatialRenderDepthStabilizer.RELIEF_GAIN},""")
                appendLine("""  "maxRelief": ${SpatialRenderDepthStabilizer.MAX_RELIEF},""")
                appendLine("""  "rigidPan": ${SpatialRenderDepthStabilizer.RIGID_PAN_AMPLITUDE},""")
                appendLine("""  "envelopeAmplitudes": ${envelope.persistedAmplitudes()},""")
                appendLine("""  "maximumLocalStrain": ${envelope.maximumLocalStrain},""")
                appendLine("""  "strengthTable": [""")
                appendLine(steps.joinToString(",\n"))
                appendLine("  ]")
                appendLine("}")
            },
            Charsets.UTF_8
        )
    }

    private fun scaledSize(width: Int, height: Int, maximumLongEdge: Int): Pair<Int, Int> {
        val longEdge = maxOf(width, height)
        if (longEdge <= maximumLongEdge) return width to height
        val scale = maximumLongEdge.toFloat() / longEdge
        return maxOf(2, Math.round(width * scale)) to maxOf(2, Math.round(height * scale))
    }

    private fun findProjectRoot(): File {
        var directory = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(7) {
            if (File(directory, "settings.gradle").isFile) return directory
            directory = directory.parentFile ?: return@repeat
        }
        error("找不到项目根目录")
    }

    private companion object {
        const val MESH_LONG_EDGE = 512
        const val STRENGTH_STEPS = 20
        const val EXPORT_FILE = "vnext11-geometry.bin"
        const val RUNTIME_FILE = "vnext11-runtime.json"
        const val EXPORT_VERSION = 1
        const val EXPORT_MAGIC = 0x56_4E_58_31_31_47_45_4FL // "VNX11GEO"
    }
}
