package com.ywwynm.everythingdone.spatial

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import javax.imageio.ImageIO
import org.junit.Test

/**
 * 仅在设置 `SPATIAL_CHART_EXPORT_DIR` 时导出九场景的 Android 等价 chart 数据，供最终
 * shader 语义离屏复核；常规单元测试不产生文件。
 */
class SpatialSurfaceChartAssetExportTest {

    @Test
    fun `导出Android等价全表面chart最终渲染输入`() {
        val outputRoot = System.getenv("SPATIAL_CHART_EXPORT_DIR")
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?: return
        val projectRoot = findProjectRoot()
        val assetsRoot = File(projectRoot, "tmp/spatial-desktop-tuning/assets")
        require(assetsRoot.isDirectory)
        check(outputRoot.exists() || outputRoot.mkdirs())
        val selectedScenes = System.getenv("SPATIAL_CHART_EXPORT_SCENES")
            .orEmpty()
            .split(',')
            .filter(String::isNotBlank)
            .toSet()

        assetsRoot.listFiles()
            .orEmpty()
            .filter(File::isDirectory)
            .filter { selectedScenes.isEmpty() || it.name in selectedScenes }
            .sortedBy(File::getName)
            .forEach { scene -> exportScene(scene, File(outputRoot, scene.name)) }
    }

    private fun exportScene(scene: File, output: File) {
        val meta = File(scene, "meta.json").readText(Charsets.UTF_8)
        val guideWidth = integerField(meta, "guideWidth")
        val guideHeight = integerField(meta, "guideHeight")
        val center = checkNotNull(ImageIO.read(File(scene, "center.jpg")))
        val guideColor = resize(center, guideWidth, guideHeight, BufferedImage.TYPE_INT_ARGB)
        val colors = IntArray(guideWidth * guideHeight)
        guideColor.getRGB(0, 0, guideWidth, guideHeight, colors, 0, guideWidth)

        val depthImage = checkNotNull(
            ImageIO.read(File(scene, "diagnostic-depth/disparity.png"))
        )
        val sourceDepth = FloatArray(depthImage.width * depthImage.height)
        for (y in 0 until depthImage.height) {
            for (x in 0 until depthImage.width) {
                sourceDepth[y * depthImage.width + x] =
                    depthImage.raster.getSampleFloat(x, y, 0) / 255f
            }
        }
        val depth = SpatialDepthData(
            width = depthImage.width,
            height = depthImage.height,
            values = sourceDepth,
            robustRange = 1f,
            strongEdgeRatio = 0f,
            defaultStrength = 1f,
            sharpEdges = true
        )
        val geometry = SpatialVNextGeometryBuilder.build(
            width = guideWidth,
            height = guideHeight,
            sourceDepth = depth
        ).geometry
        val charts = SpatialSurfaceChartBuilder.build(
            colorPixels = colors,
            width = guideWidth,
            height = guideHeight,
            surfaceDepth = geometry.surfaceDepth,
            depthResidualGain = System.getenv("SPATIAL_CHART_DEPTH_RESIDUAL_GAIN")
                ?.toFloatOrNull()
                ?: 6f
        )
        val renderData = SpatialSurfaceChartRenderDataBuilder.build(
            charts = charts.charts,
            motionBasis = charts.motionBasis,
            maximumAtlasSize = 4096,
            overlapSigmaPxAt720 = System.getenv("SPATIAL_CHART_OVERLAP_SIGMA")
                ?.toFloatOrNull()
                ?: SpatialSurfaceChartBuilder.OVERLAP_SIGMA_PX_AT_720
        )
        check(output.exists() || output.mkdirs())
        writeBinary(
            file = File(output, "chart-render.bin"),
            charts = charts,
            renderData = renderData
        )
        writeLabels(File(output, "charts.png"), charts.charts)
    }

    private fun writeBinary(
        file: File,
        charts: SpatialSurfaceChartBuilder.Result,
        renderData: SpatialSurfaceChartRenderData
    ) {
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { output ->
            output.writeLong(EXPORT_MAGIC)
            output.writeInt(charts.charts.width)
            output.writeInt(charts.charts.height)
            output.writeInt(renderData.atlasWidth)
            output.writeInt(renderData.atlasHeight)
            output.writeInt(charts.charts.chartCount)
            output.writeFloat(charts.charts.guardFraction)
            output.writeFloat(charts.backgroundMotionBasis.horizontalX[0])
            output.writeFloat(charts.backgroundMotionBasis.horizontalY[0])
            output.writeFloat(charts.backgroundMotionBasis.verticalX[0])
            output.writeFloat(charts.backgroundMotionBasis.verticalY[0])
            output.writeInt(renderData.atlasWeights.size)
            renderData.atlasWeights.forEach(output::writeFloat)
            output.writeInt(renderData.quads.size)
            renderData.quads.forEach { quad ->
                output.writeInt(quad.chartIndex)
                output.writeFloat(quad.sourceLeft)
                output.writeFloat(quad.sourceTop)
                output.writeFloat(quad.sourceRight)
                output.writeFloat(quad.sourceBottom)
                output.writeFloat(quad.atlasLeft)
                output.writeFloat(quad.atlasTop)
                output.writeFloat(quad.atlasRight)
                output.writeFloat(quad.atlasBottom)
                output.writeFloat(quad.horizontalX)
                output.writeFloat(quad.horizontalY)
                output.writeFloat(quad.verticalX)
                output.writeFloat(quad.verticalY)
                output.writeFloat(quad.zWeight)
            }
            output.writeInt(charts.charts.labels.size)
            charts.charts.labels.forEach(output::writeInt)
            output.writeInt(charts.chartScalars.size)
            charts.chartScalars.forEach(output::writeFloat)
        }
    }

    private fun writeLabels(file: File, charts: SpatialSurfaceChartData) {
        val image = BufferedImage(charts.width, charts.height, BufferedImage.TYPE_INT_RGB)
        for (index in charts.labels.indices) {
            val label = charts.labels[index]
            val color = (
                ((67 * label + 53) % 255 shl 16) or
                    ((131 * label + 97) % 255 shl 8) or
                    ((193 * label + 29) % 255)
                )
            image.setRGB(index % charts.width, index / charts.width, color)
        }
        ImageIO.write(image, "png", file)
    }

    private fun resize(
        source: BufferedImage,
        width: Int,
        height: Int,
        type: Int
    ): BufferedImage {
        if (source.width == width && source.height == height && source.type == type) return source
        val result = BufferedImage(width, height, type)
        val graphics = result.createGraphics()
        try {
            graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR
            )
            graphics.drawImage(source, 0, 0, width, height, null)
        } finally {
            graphics.dispose()
        }
        return result
    }

    private fun integerField(json: String, name: String): Int =
        checkNotNull(
            Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*(\\d+)")
                .find(json)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
        ) { "meta.json 缺少 $name" }

    private fun findProjectRoot(): File {
        var directory = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(7) {
            if (File(directory, "settings.gradle").isFile) return directory
            directory = directory.parentFile ?: return@repeat
        }
        error("找不到项目根目录")
    }

    private companion object {
        const val EXPORT_MAGIC = 0x5350434851413031L // SPCHQA01
    }
}
