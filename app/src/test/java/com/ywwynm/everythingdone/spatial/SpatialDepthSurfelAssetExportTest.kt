package com.ywwynm.everythingdone.spatial

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import javax.imageio.ImageIO
import org.junit.Test

/**
 * 仅在设置 `SPATIAL_SURFEL_EXPORT_DIR` 时导出 Android 实际构建的连续微表面标量，
 * 供与 GLES2 着色器一致的最终成图离屏复核；常规单元测试不产生文件。
 */
class SpatialDepthSurfelAssetExportTest {

    @Test
    fun `导出Android等价连续微表面最终渲染输入`() {
        val outputRoot = System.getenv("SPATIAL_SURFEL_EXPORT_DIR")
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?: return
        val projectRoot = findProjectRoot()
        val assetsRoot = File(projectRoot, "tmp/spatial-desktop-tuning/assets")
        require(assetsRoot.isDirectory)
        check(outputRoot.exists() || outputRoot.mkdirs())
        val selectedScenes = System.getenv("SPATIAL_SURFEL_EXPORT_SCENES")
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
        val depthImage = checkNotNull(
            ImageIO.read(File(scene, "diagnostic-depth/disparity.png"))
        )
        val sourceValues = FloatArray(depthImage.width * depthImage.height)
        for (y in 0 until depthImage.height) {
            for (x in 0 until depthImage.width) {
                sourceValues[y * depthImage.width + x] =
                    depthImage.raster.getSampleFloat(x, y, 0) / 255f
            }
        }
        val result = SpatialDepthSurfelBuilder.build(
            sourceDepth = SpatialDepthData(
                width = depthImage.width,
                height = depthImage.height,
                values = sourceValues,
                robustRange = 1f,
                strongEdgeRatio = 0f,
                defaultStrength = 1f,
                sharpEdges = true
            ),
            width = guideWidth,
            height = guideHeight
        )
        check(output.exists() || output.mkdirs())
        DataOutputStream(
            BufferedOutputStream(FileOutputStream(File(output, EXPORT_FILE)))
        ).use { data ->
            data.writeLong(EXPORT_MAGIC)
            data.writeInt(result.surfels.width)
            data.writeInt(result.surfels.height)
            data.writeFloat(result.surfels.guardFraction)
            data.writeFloat(result.surfels.backgroundScalar)
            data.writeFloat(result.surfels.requestedMaximumParallax)
            data.writeInt(result.surfels.motionScalars.size)
            result.surfels.motionScalars.forEach(data::writeFloat)
        }
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
        const val EXPORT_MAGIC = 0x5350534651413031L // SPSFQA01
        const val EXPORT_FILE = "depth-surfels.bin"
    }
}
