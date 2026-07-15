package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FableSolGlRenderTargetSourceTest {

    @Test
    fun `折射背景与合成场景使用两个独立离屏目标`() {
        val source = rendererSource()
        val drawFrame = functionBody(source, "private fun drawFrame()", "private fun drawEnvironmentTo")

        assertTrue(source.contains("private var preWaterFramebufferId = 0"))
        assertTrue(source.contains("private var preWaterTextureId = 0"))
        assertTrue(drawFrame.contains("drawEnvironmentTo(preWaterFramebufferId)"))
        assertTrue(drawFrame.contains("drawEnvironmentTo(sceneFramebufferId)"))
        assertTrue(drawFrame.contains("bindPreWaterScene()"))
        assertTrue(source.contains("waterProgram.uniform(\"uPreWaterScene\"), 1"))
        assertTrue(source.contains("GLES30.GL_TEXTURE1"))
    }

    @Test
    fun `两个目标同格式创建且折射背景使用线性过滤`() {
        val source = rendererSource()
        val create = functionBody(
            source,
            "private fun createSceneTargets",
            "private fun configureSceneTexture"
        )

        assertTrue(create.contains("GLES30.glGenTextures(2"))
        assertTrue(create.contains("GLES30.glGenFramebuffers(2"))
        assertTrue(create.contains("configureSceneTexture(sceneTextureId"))
        assertTrue(create.contains("GLES30.GL_NEAREST"))
        assertTrue(create.contains("configureSceneTexture(preWaterTextureId"))
        assertTrue(create.contains("GLES30.GL_LINEAR"))
        assertTrue(create.contains("if (hdrTargets) GLES30.GL_RGBA16F else GLES30.GL_RGBA8"))
    }

    @Test
    fun `FP16任一目标失败时成对回退且不改写输出颜色空间`() {
        val source = rendererSource()
        val ensure = functionBody(
            source,
            "private fun ensureSceneTargets",
            "private fun createSceneTargets"
        )

        assertTrue(ensure.contains("releaseSceneTargets()"))
        assertTrue(ensure.contains("hdrContentEnabled = false"))
        assertTrue(ensure.contains("createSceneTargets(width, height, hdrTargets = false)"))
        assertFalse(ensure.contains("sceneLinear = false"))
    }

    private fun functionBody(source: String, startToken: String, endToken: String): String {
        val start = source.indexOf(startToken)
        val end = source.indexOf(endToken, start + startToken.length)
        check(start >= 0 && end > start) { "找不到待核验的 Renderer 函数区段" }
        return source.substring(start, end)
    }

    private fun rendererSource(): String {
        var directory = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(5) {
            val candidate = File(
                directory,
                "app/src/main/java/com/ywwynm/everythingdone/views/recording/" +
                    "fablesol/FableSolGlRenderer.kt"
            )
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            directory = directory.parentFile ?: return@repeat
        }
        error("找不到 FableSolGlRenderer.kt")
    }
}
