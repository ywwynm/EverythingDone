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
        val page = readProjectFile(
            "tmp/particle-dismiss-tuning/cloth-motion-prototype/physical.html"
        ).readText(Charsets.UTF_8)

        assertTrue("桌面播放器缺少明确的一秒时钟", "PLAYBACK_DURATION_MS=1000" in page)
        assertTrue(
            "参考和模型必须共用同一归一化进度",
            "progress=startProgress+(now-start)/PLAYBACK_DURATION_MS" in page
        )
        assertFalse("不得再按参考原片的 4.7 秒播放模型", "/4700" in page)
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
            "不得在 start() 返回后立即启动 dim；此时 PBD 与 GL 尚未准备",
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
