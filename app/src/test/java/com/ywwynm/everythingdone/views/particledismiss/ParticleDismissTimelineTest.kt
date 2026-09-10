package com.ywwynm.everythingdone.views.particledismiss

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParticleDismissTimelineTest {

    @Test
    fun `消失逻辑与桌面对照必须严格使用一秒时钟`() {
        assertEquals(1.0f, ParticleDismissRenderer.TOTAL_DURATION, 0.0f)
        assertEquals(ParticleMicroflakeModel.DURATION, ParticleDismissRenderer.TOTAL_DURATION, 0f)
        assertEquals(1f / 240f, ParticleMicroflakeModel.STEP, 0f)
        val metadata = readProjectFile("shared/particle-dismiss/model.json").readText()
        assertTrue("打包资源与固定时步不匹配", "\"integration_hz\": 240" in metadata)
    }

    @Test
    fun `dim淡出必须由粒子首帧启动而不是dismiss请求启动`() {
        val source = readProjectFile(
            "app/src/main/java/com/ywwynm/everythingdone/fragments/BaseDialogFragment.kt"
        ).readText(Charsets.UTF_8)
        val particleBranch = source
            .substringAfter("val started = ParticleDismissController.start(")
            .substringBefore("dimLayer?.fadeOutAndDetach(DialogDimLayer.FADE_OUT_MS)")

        assertTrue(
            "粒子接管必须显式提供首帧回调来启动 dim",
            "onAnimationStarted =" in particleBranch
        )
        val afterStarted = particleBranch.substringAfter("if (started)")
        assertFalse(
            "不得在 start() 返回后立即启动 dim；此时材料与 GL 尚未准备",
            "dimLayer?.fadeOutAndDetach(" in afterStarted
        )
    }

    @Test
    fun `真实dialog必须同时等待overlay上屏和粒子首帧`() {
        var completed = 0
        val overlayFirst = ParticleDismissStartGate { completed++ }
        overlayFirst.markOverlayShown()
        assertEquals(0, completed)
        overlayFirst.markAnimationStarted()
        assertEquals(1, completed)
        overlayFirst.markAnimationStarted()
        overlayFirst.markOverlayShown()
        assertEquals(1, completed)

        val animationFirst = ParticleDismissStartGate { completed++ }
        animationFirst.markAnimationStarted()
        assertEquals(1, completed)
        animationFirst.markOverlayShown()
        assertEquals(2, completed)
    }

    private fun readProjectFile(relativePath: String): File {
        var directory = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(7) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate
            directory = directory.parentFile ?: return@repeat
        }
        error("找不到项目文件：$relativePath")
    }
}
