package com.ywwynm.everythingdone.views.particledismiss

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逐帧成本专项第十一批（2026-09-18）的契约：粒子动画设置页切到「示例对话框」那一格时，
 * **直接显示静止的对话框**，随后只播一轮「消散 → 出现」。
 *
 * 此前是「空白 → 出现 → 消散 → 出现」：静止态只被当成**终点**，而从不可见起步、以可见收尾，
 * 出现就必然比消散多一次（奇偶性）。把静止的可见态同时当成**起点**之后，一轮自己就闭合了，
 * 多出来的那一次「出现」与开头那一帧空白一起消失。
 *
 * 逐项说明与真机逐帧证据见
 * `docs/features/particle-animation-performance/per-frame-batch11-ui-2026-09-18.md`。
 */
class ParticlePerFrameBatchElevenUiTest {

    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "shared/particle-dismiss/rules.properties").isFile }

    private fun text(path: String) = File(root, path).readText(Charsets.UTF_8)

    private fun stripComments(source: String): String = source
        .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        .replace(Regex("""(?m)//.*$"""), " ")
        .replace(Regex("""<!--[\s\S]*?-->"""), " ")

    private val pageCode get() = stripComments(
        text("app/src/main/java/com/ywwynm/everythingdone/activities/ParticleTuningActivity.kt"))

    private val controller get() = stripComments(
        text("app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/" +
            "ParticleDismissController.kt"))

    /** 切出一个函数的声明体：只断言这一段里的契约，不去全文搜函数名。 */
    private fun body(name: String): String {
        val at = Regex("""(?m)^    (?:private|override) fun $name\b""").find(pageCode)
        assertTrue("源码里找不到 $name", at != null)
        return pageCode.substring(at!!.range.last).substringBefore("\n    }")
    }

    // ---- 三步开场序列整段删掉 ----

    @Test fun `一轮就是两步，源码里不再有三步开场序列`() {
        assertEquals("一轮两步：消散 → 出现", "2",
            Regex("""ROUND_STEPS = (\d+)""").find(pageCode)!!.groupValues[1])
        assertFalse("三步那个常量整段删掉", "OPENING_STEPS" in pageCode)
        assertFalse("开场序列那个入口整段删掉", "startDialogSequence" in pageCode)
        // 排步数只有两种写法：排一轮，或清零。再没有第三种步数。
        val assigned = Regex("""dialogStepsLeft = (\w+)""").findAll(pageCode)
            .map { it.groupValues[1] }.toSet()
        assertEquals("要么排一轮、要么清零", setOf("ROUND_STEPS", "0"), assigned)
    }

    // ---- 切过去的第一帧就有静止的对话框 ----

    @Test fun `切到对话框那一格直接显示静止的对话框`() {
        val switch = body("switchPreview")
        assertTrue("切过去走的是「先显示再播一轮」那一条", "beginDialogPreview()" in switch)
        // 「藏起来」这一句只允许留在**切回卡片**的那一支里：切过去还藏，第一帧就是空的。
        val toDialog = switch.substringAfter("} else {")
        assertFalse("切到对话框那一支不许把它藏起来",
            "sampleDialog?.visibility = View.INVISIBLE" in toDialog)
        val toCard = switch.substringAfter("if (card) {").substringBefore("} else {")
        assertTrue("切回卡片那一支才藏", "sampleDialog?.visibility = View.INVISIBLE" in toCard)
        val begin = body("beginDialogPreview")
        val showAt = begin.indexOf("showDialogIdle()")
        val roundAt = begin.indexOf("awaitDialogIdleFrame()")
        assertTrue("先显示，再去排那一轮", showAt in 1 until roundAt)
        assertTrue("进来时不欠步数", "dialogStepsLeft = 0" in begin)
        assertTrue("上一轮的层要收掉", "panelAnimator.cancel(sampleDialog)" in begin)
    }

    @Test fun `静止态与出现动画的末帧是同一个画面`() {
        val idle = body("showDialogIdle")
        // 位置、透明度一项不缺地写全；只写 visibility 的话，上一轮原子交接留下的 alpha = 0
        // 会让「显示出来」等于什么也看不见。
        assertTrue("透明度还原成不透明", "target.alpha = 1f" in idle)
        assertTrue("位置还原", "target.translationX = 0f" in idle && "target.translationY = 0f" in idle)
        assertTrue("可见", "target.visibility = View.VISIBLE" in idle)
        assertTrue("状态跟着走", "dialogShowing = true" in idle)
        // 阴影由布局的 cardElevation 给，静止态一个字都不许碰它——碰了就与出现末帧不是同一个
        // 画面（原子交接那一条自始至终没改过真实 elevation，见第 9 批）。
        assertFalse("不许动 elevation", "elevation" in idle)
    }

    // ---- 粒子层没就绪时保持静止显示，不空白 ----

    @Test fun `那一轮等对话框真的画出来之后才开始`() {
        val await = body("awaitDialogIdleFrame")
        assertTrue("等的是「这一帧真的提交了」，不是「这一次遍历排完了」",
            "ParticleDismissController.runAfterShown(target, IDLE_FRAME_TIMEOUT_MS" in await)
        assertTrue("切走或又重进过一次，这一轮就作废",
            "token != dialogPreviewToken || previewingCard || isFinishing" in await)
        val shown = controller.substringAfter("fun runAfterShown").substringBefore("\n    }")
        assertTrue("提交回调才算数", "registerFrameCommitCallback" in shown)
        assertTrue("拿不到提交回调也要有尽头", "postDelayed(fire, timeoutMs)" in shown)
    }

    @Test fun `还没就绪就继续等，期间保持静止显示`() {
        val round = body("startDialogRound")
        assertTrue("没就绪就重排自己",
            "main.postDelayed(dialogRoundStart, ROUND_READY_POLL_MS)" in round)
        assertTrue("等待有尽头", "dialogRoundRetries++ < ROUND_READY_RETRIES" in round)
        // 等待期间只准原地等：这一段里不许出现任何把对话框藏起来的写法。
        assertFalse("等的时候不许藏", "INVISIBLE" in round)
        assertTrue("就绪之后先把静止态写一遍再起步", "showDialogIdle()" in round)
        val ready = pageCode.substringAfter("private fun dialogReadyForRound")
            .substringBefore("\n\n")
        listOf("isAttachedToWindow", "isShown", "isLaidOut",
            "width > 0", "height > 0", "!target.isLayoutRequested").forEach {
            assertTrue("消散那一条对宿主的要求：$it", it in ready)
        }
    }

    @Test fun `消散起不来时保持静止显示，不留一帧空白`() {
        val step = body("stepDialog")
        val failed = step.substringAfter("if (!panelAnimator.dismiss(target")
            .substringAfter("})) {").substringBefore("\n            }")
        assertTrue("整轮作废", "dialogStepsLeft = 0" in failed)
        assertTrue("排下的那一步要撤掉", "main.removeCallbacks(dialogStep)" in failed)
        assertTrue("回到静止显示", "showDialogIdle()" in failed)
        assertFalse("绝不把对话框藏起来", "INVISIBLE" in failed)
    }

    // ---- 其余入口一律走同一轮 ----

    @Test fun `重进、换方向、改参数走的都是同一轮`() {
        assertTrue("回到页面时先显示再播一轮",
            "if (!previewingCard) beginDialogPreview()" in body("onResume"))
        assertTrue("换方向时不可见，也先显示再播",
            "if (!dialogShowing) { beginDialogPreview(); return }" in body("applyDirection"))
        assertTrue("补那一轮时不可见，同样先显示再播",
            "if (!dialogShowing) { beginDialogPreview(); return }" in body("finishDialogStep"))
        val restart = body("restartPreview")
        assertTrue("改参数时正播着就不打断，等这一轮走完再补",
            "dialogStepsLeft > 0 || dialogBusy" in restart && "pendingRound = true" in restart)
        assertTrue("静止着就直接用新参数播一轮", "beginDialogPreview()" in restart)
    }

    @Test fun `离开页面要把排好的那一轮撤掉`() {
        assertTrue("退到后台撤掉待起的那一轮",
            "main.removeCallbacks(dialogRoundStart)" in body("onPause"))
        assertTrue("切走也撤", "main.removeCallbacks(dialogRoundStart)" in body("switchPreview"))
    }
}
