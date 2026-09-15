package com.ywwynm.everythingdone.views.particledismiss

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 背景暗层在「弹窗由新 Activity 承载」这条路上的回归。
 *
 * 开始做事、更突出的通知、延迟提醒、认证、列表小组件配置都是先起一个透明 Activity，
 * 在 `onCreate` / `onPostCreate` 里就提交 DialogFragment：暗层因此挂在宿主 DecorView
 * **第一次布局之前**。这三条契约各自对应一种「背景不变暗」的失效方式，JVM 层没有 View
 * 可以驱动（模块只有 JUnit），所以定在源码上。
 */
class DialogDimCoverageTest {

    @Test fun `overlay 里的层必须跟住宿主尺寸`() {
        val source = stripComments(read("app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/ParticleOverlayHost.kt"))
        val overlayBranch = source.substringAfter("decor.overlay.add(view)").substringBefore("fun detach(")
        assertTrue("attach 之后必须立刻按当前尺寸布局一次", "layoutFullScreen(decor, view)" in overlayBranch)
        assertTrue(
            "宿主首帧之前挂上来时 decor 还是 0×0，必须监听宿主布局把尺寸补上，" +
                "否则层以 0×0 留在 overlay 里、一个像素都画不出来",
            "decor.addOnLayoutChangeListener(" in overlayBranch
        )
        // 这个提前返回正是必须监听的原因：尺寸未知时它什么也不做。
        assertTrue("if (width <= 0 || height <= 0) return" in source)
        assertTrue("尺寸同步回调最终仍走同一个布局入口", source.split("layoutFullScreen(decor, view)").size >= 3)
    }

    @Test fun `暗层淡入必须等到拿到尺寸之后再起步`() {
        val source = stripComments(read("app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/DialogDimLayer.kt"))
        val fadeIn = source.substringAfter("private fun fadeIn()").substringBefore("fun fadeOutAndDetach(")
        assertTrue("淡入必须挂在 doOnLayout 上", "view.doOnLayout {" in fadeIn)
        assertTrue("view.animate().alpha(DIM_AMOUNT)" in fadeIn)
        val attach = source.substringAfter("fun attach(activity: Activity")
        assertFalse(
            "attach 内不得直接起淡入：宿主首帧之前这一层既没有尺寸、也从未进入绘制树",
            "view.animate()" in attach
        )
        assertTrue("if (animateIn) it.fadeIn()" in attach)
    }

    @Test fun `出现动画早于暗层结束时必须补到最终浓度`() {
        val source = stripComments(read("app/src/main/java/com/ywwynm/everythingdone/fragments/BaseDialogFragment.kt"))
        val deferredAttach = source
            .substringAfter("host.window.decorView.doOnAttach {")
            .substringBefore("if (animateAppearance) {")
        assertTrue("DialogDimLayer.attach(host, animateIn = !animateAppearance)" in deferredAttach)
        assertTrue(
            "宿主未附着时进度回调会全部落在 null 上，暗层会停在 alpha 0",
            "setAppearanceProgress(1f)" in deferredAttach
        )
        assertTrue("appearanceSettled" in deferredAttach)
    }

    private fun stripComments(source: String): String = source
        .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        .replace(Regex("""(?m)//.*$"""), " ")

    private fun read(relativePath: String): String {
        var directory = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(7) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            directory = directory.parentFile ?: return@repeat
        }
        error("找不到项目文件：$relativePath")
    }
}
