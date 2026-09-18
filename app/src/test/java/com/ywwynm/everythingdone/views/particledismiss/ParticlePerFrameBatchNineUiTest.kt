package com.ywwynm.everythingdone.views.particledismiss

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逐帧成本专项第九批（2026-09-18）的契约：粒子动画设置页的第五轮三条。
 *
 * 1. 字号、行高、间距一律以**「空间照片效果」设置页**为参照，性能自动调节那一行与那页的
 *    「通过倾斜设备控制视点」逐项同值；
 * 2. 示例对话框的阴影在粒子起止两端走**原子交接**，不再有两层半透明投影叠加的那一两帧；
 * 3. 切换方向时对话框不再凭空消失，直接按新方向播一轮「消散 → 出现」。
 *
 * 逐项说明见
 * `docs/features/particle-animation-performance/per-frame-batch9-ui-2026-09-18.md`。
 *
 * **参照值不写死**：下面的断言都先从 `activity_spatial_photo_settings.xml` 里读出那一页
 * 的取值，再要求本页取同一份。参照页改了，这里跟着改，不会悄悄对不上。
 */
class ParticlePerFrameBatchNineUiTest {

    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "shared/particle-dismiss/rules.properties").isFile }

    private fun text(path: String) = File(root, path).readText(Charsets.UTF_8)

    private fun stripComments(source: String): String = source
        .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        .replace(Regex("""(?m)//.*$"""), " ")
        .replace(Regex("""<!--[\s\S]*?-->"""), " ")

    private val pageCode get() = stripComments(
        text("app/src/main/java/com/ywwynm/everythingdone/activities/ParticleTuningActivity.kt"))

    private val layout get() = stripComments(
        text("app/src/main/res/layout/activity_particle_tuning.xml"))

    /** 参照物：「空间照片效果」的二级设置页。 */
    private val reference get() = stripComments(
        text("app/src/main/res/layout/activity_spatial_photo_settings.xml"))

    private val controller get() = stripComments(
        text("app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/" +
            "ParticleDismissController.kt"))

    private val overlay get() = stripComments(
        text("app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/" +
            "ParticleDismissOverlay.kt"))

    private val panelAnimator get() = stripComments(
        text("app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/" +
            "ParticlePanelAnimator.kt"))

    private val handoff get() = stripComments(
        text("app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/" +
            "ParticleCardSurfaceHandoff.kt"))

    // ---- 布局取值的小工具 ----

    /** 某个带 id 的元素的**开始标签**（容器与自闭合标签通用：都截到第一个 `>`）。 */
    private fun tagOf(id: String, source: String): String {
        val anchor = """android:id="@+id/$id""""
        assertTrue("布局里找不到 $id", anchor in source)
        return source.substringAfter(anchor).substringBefore(">")
    }

    /** 引用了 [marker] 的那个元素的开始标签（用于参照页里没有 id 的说明文本）。 */
    private fun tagContaining(marker: String, source: String): String {
        val at = source.indexOf(marker)
        assertTrue("布局里找不到 $marker", at >= 0)
        val start = source.lastIndexOf('<', at)
        return source.substring(start, source.indexOf('>', at))
    }

    private fun attr(tag: String, name: String): String? =
        Regex("""android:$name="([^"]*)"""").find(tag)?.groupValues?.get(1)

    private fun require(tag: String, name: String, label: String): String =
        attr(tag, name) ?: throw AssertionError("$label 缺 android:$name")

    // ---- 第 1 条：字号、行高、间距与参照页逐项同值 ----

    /** 参照页所有行的左右内边距。 */
    private val referenceInset: String
        get() = require(tagOf("rl_spatial_tilt_as_bt", reference), "paddingLeft", "参照页倾斜行")

    @Test fun `参数区所有行的左右内边距取参照页的那一份`() {
        assertEquals("参照页是 16dp", "16dp", referenceInset)
        listOf("rl_particle_tuning_cell_row", "rl_particle_tuning_flow_row",
            "ll_particle_tuning_color_row", "rl_particle_tuning_auto_row",
            "tv_particle_tuning_cell_detail", "tv_particle_tuning_flow_detail",
            "tv_particle_tuning_color_detail",
            "tv_particle_tuning_hint", "tv_particle_tuning_stats").forEach { id ->
            val tag = tagOf(id, layout)
            assertEquals("$id 的左内边距", referenceInset, attr(tag, "paddingLeft"))
            assertEquals("$id 的右内边距", referenceInset, attr(tag, "paddingRight"))
        }
        // 只看参数区（滚动容器往下那一段）：上方两个选项卡的 20dp 是胶囊自己的内边距。
        val params = layout.substringAfter("sv_particle_tuning_params")
        assertFalse("参数区不许再出现 20dp 的左右内边距",
            """android:paddingLeft="20dp"""" in params ||
                """android:paddingRight="20dp"""" in params)
    }

    @Test fun `带读数的三行取参照页那一档行高与字号`() {
        // 参照页的 `row_depth_detail`：56dp 的行，左边标题 16sp，右边读数 14sp。
        val referenceRow = tagOf("row_depth_detail", reference)
        val referenceHeight = require(referenceRow, "layout_height", "参照页读数行")
        val referenceTitle = require(
            tagContaining("@string/spatial_depth_detail\"", reference), "textSize", "参照页行标题")
        val referenceValue = require(
            tagOf("tv_depth_detail_value", reference), "textSize", "参照页读数")
        assertEquals("参照页的读数行是 56dp", "56dp", referenceHeight)
        assertEquals("参照页的行标题是 16sp", "16sp", referenceTitle)
        assertEquals("参照页的读数是 14sp", "14sp", referenceValue)

        listOf("rl_particle_tuning_cell_row", "rl_particle_tuning_flow_row",
            "ll_particle_tuning_color_row").forEach {
            assertEquals("$it 的行高", referenceHeight, attr(tagOf(it, layout), "layout_height"))
        }
        listOf("tv_particle_tuning_cell_label", "tv_particle_tuning_flow_label",
            "tv_particle_tuning_color_label", "tv_particle_tuning_auto_label").forEach {
            assertEquals("$it 的字号", referenceTitle, attr(tagOf(it, layout), "textSize"))
        }
        listOf("tv_particle_tuning_cell_value", "tv_particle_tuning_flow_value").forEach {
            assertEquals("$it 的字号", referenceValue, attr(tagOf(it, layout), "textSize"))
        }
        assertFalse("标题不许再用 strong 那一档（参照页用的是主题的 textColorPrimary）",
            "app_chrome_on_surface_strong" in layout)
    }

    @Test fun `说明文本取参照页的字号、颜色与收尾内边距`() {
        val referenceNote = tagContaining("@string/spatial_depth_detail_description", reference)
        val size = require(referenceNote, "textSize", "参照页说明")
        val color = require(referenceNote, "textColor", "参照页说明")
        val bottom = require(referenceNote, "paddingBottom", "参照页说明")
        assertEquals("12sp", size)
        assertEquals("@color/app_chrome_on_surface_hint", color)
        assertEquals("10dp", bottom)
        assertEquals("参照页的说明紧跟上一个控件，没有上内边距", null, attr(referenceNote, "paddingTop"))
        listOf("tv_particle_tuning_cell_detail", "tv_particle_tuning_flow_detail",
            "tv_particle_tuning_color_detail").forEach { id ->
            val tag = tagOf(id, layout)
            assertEquals("$id 的字号", size, attr(tag, "textSize"))
            assertEquals("$id 的颜色", color, attr(tag, "textColor"))
            assertEquals("$id 的收尾内边距", bottom, attr(tag, "paddingBottom"))
            assertEquals("$id 不许再有上边距", null, attr(tag, "layout_marginTop"))
        }
    }

    /**
     * 第 10 批第 2 条把「对齐什么」从轨道改成了**滑块**：滑动条仍然走全宽、不带自己的
     * 外边距，但左右内边距由 `alignSliderThumbToTextEdges` 按「行内边距 + 滑块半径」下发。
     * 那一条的判据在 `ParticlePerFrameBatchTenUiTest`。
     */
    @Test fun `滑动条走全宽、不另加外边距`() {
        listOf("sb_particle_tuning_cell", "sb_particle_tuning_flow").forEach { id ->
            val tag = tagOf(id, layout)
            assertEquals("$id 要占满整行", "match_parent", attr(tag, "layout_width"))
            assertEquals("$id 不许再另加上边距", null, attr(tag, "layout_marginTop"))
            assertEquals("$id 不许再另加左边距", null, attr(tag, "layout_marginLeft"))
        }
    }

    @Test fun `性能自动调节整行与参照页的「通过倾斜设备控制视点」逐项同值`() {
        val referenceRow = tagOf("rl_spatial_tilt_as_bt", reference)
        val row = tagOf("rl_particle_tuning_auto_row", layout)
        listOf("minHeight", "paddingLeft", "paddingRight", "paddingTop", "paddingBottom")
            .forEach { name ->
                assertEquals("自动调节行的 $name", require(referenceRow, name, "参照页倾斜行"),
                    attr(row, name))
            }
        assertEquals("参照页那一行是 72dp", "72dp", attr(referenceRow, "minHeight"))
        assertTrue("整行可点", """android:clickable="true"""" in row)

        // 勾选框：贴右缘、垂直居中、自己不可点（按下态由整行下发）。
        val referenceBox = tagOf("sw_spatial_tilt", reference)
        val box = tagOf("cb_particle_tuning_auto", layout)
        listOf("layout_alignParentEnd", "layout_centerVertical").forEach { name ->
            assertEquals("勾选框的 $name", require(referenceBox, name, "参照页勾选框"),
                attr(box, name))
        }
        assertTrue("勾选框自己不可点", """android:clickable="false"""" in box)

        // 文字块：占满剩余宽度、垂直居中、与勾选框留同样的间距。
        val referenceBlock = reference.substringAfter(referenceBox).substringAfter("<LinearLayout")
            .substringBefore(">")
        val block = layout.substringAfter(box).substringAfter("<LinearLayout").substringBefore(">")
        assertEquals("文字块与勾选框之间的间距",
            require(referenceBlock, "layout_marginEnd", "参照页文字块"),
            attr(block, "layout_marginEnd"))
        assertEquals("12dp", attr(block, "layout_marginEnd"))
        assertTrue("文字块要垂直居中", """android:layout_centerVertical="true"""" in block)
        assertTrue("文字块排在勾选框左边",
            """android:layout_toStartOf="@id/cb_particle_tuning_auto"""" in block)

        // 说明在**行内**，上边距与字号取参照页那一份。
        val referenceNote = tagContaining("@string/spatial_device_tilt_description", reference)
        val note = tagOf("tv_particle_tuning_auto_detail", layout)
        assertEquals("说明的上边距", require(referenceNote, "layout_marginTop", "参照页说明"),
            attr(note, "layout_marginTop"))
        assertEquals("2dp", attr(note, "layout_marginTop"))
        assertEquals("说明的字号", require(referenceNote, "textSize", "参照页说明"),
            attr(note, "textSize"))
        assertEquals("说明的颜色", require(referenceNote, "textColor", "参照页说明"),
            attr(note, "textColor"))
        assertEquals("行内说明不再自己带左右内边距（由整行给）", null, attr(note, "paddingLeft"))
    }

    @Test fun `恢复默认的右缘仍与上方各行对齐`() {
        val reset = tagOf("tv_particle_tuning_reset", layout)
        val margin = require(reset, "layout_marginEnd", "恢复默认").removeSuffix("dp").toInt()
        val padding = require(reset, "paddingRight", "恢复默认").removeSuffix("dp").toInt()
        assertEquals("外边距 + 内边距要等于各行的左右内边距",
            referenceInset, "${margin + padding}dp")
    }

    // ---- 第 2 条：阴影在粒子起止两端原子交接 ----

    @Test fun `含投影的面板在系统支持时走窗口内原子交接`() {
        val judge = panelAnimator.substringAfter("fun atomicHandoff(").substringBefore("\n}")
        assertTrue("有投影才谈得上交接", "shadowElevationPx != null" in judge)
        assertTrue("低于 API 34 时自动退回第 8 批那条",
            "ParticleCardSurfaceHandoff.supported()" in judge)
        assertTrue("留一个逐项开关，好在同一个包上取修前数据",
            "ParticlePerfTuning.panelShadowAtomicHandoff" in judge)
        assertTrue("出现那一侧把 cardShadow 打开",
            "cardShadow = atomicHandoff(shadowElevationPx)" in panelAnimator)
        val start = controller.substringAfter("fun startContent(").substringBefore("\n    private fun")
        assertTrue("消散那一侧也判一次，三个条件一个不少",
            "val atomicHandoff = cardSnapshot != null && ParticlePerfTuning.panelShadowAtomicHandoff"
                in start && "ParticleCardSurfaceHandoff.supported()" in start)
        val tuning = stripComments(text("app/src/main/java/com/ywwynm/everythingdone/" +
            "views/particledismiss/ParticlePerfTuning.kt"))
        assertTrue("开关默认是开的（= 发布配置）",
            "var panelShadowAtomicHandoff = true" in tuning)
        assertTrue("debug 包可以由配置文件覆盖",
            """config.optBoolean("panelShadowAtomicHandoff", panelShadowAtomicHandoff)""" in tuning)
    }

    @Test fun `消散那一条把交接的三件事一起打开`() {
        val attach = controller.substringAfter("private fun attachDismissOverlay")
            .substringBefore("\n    private fun runAfterShown")
        assertTrue("粒子会话按卡片那条登记到窗口内", "inWindow = atomicHandoff" in attach)
        assertTrue("交接的源就是被消散的那棵树",
            "cardSource = if (atomicHandoff) decor else null" in attach)
        assertTrue("消散收尾不许把交接快照再显示出来",
            "restoreSourceOnRelease = false" in attach)
        assertTrue("原子交接那一条不再改真实 elevation（alpha 为 0 本来就不投影）",
            "if (cardSnapshot != null && !atomicHandoff && decor.elevation > 0f)" in attach)
    }

    @Test fun `交接期只有一份投影：窗口内的快照层与自有图层不许同时画`() {
        assertTrue("走原子交接时不再建窗口内的承接快照",
            "if (isCondense || gesture != null || synchronizedCard) null" in overlay)
        assertTrue("收尾按调用方的意思决定要不要把源显示回来",
            "releaseToSource(showCard = restoreSourceOnRelease)" in overlay)
        assertTrue("消散那一条默认值仍是真（卡片滑动取消要把卡片放回去）",
            "restoreSourceOnRelease: Boolean = true" in overlay)
    }

    @Test fun `交接的两步各自绑在一帧上，这是不闪的全部依据`() {
        val enter = handoff.substringAfter("fun enter(").substringBefore("\n    }")
        assertTrue("「快照显示 + 源置零透明度」绑在同一次窗口绘制上",
            "onWindowFrame(transaction)" in enter && "source.alpha = 0f" in enter)
        val onWindowFrame = handoff.substringAfter("private fun onWindowFrame(")
            .substringBefore("\n    }")
        assertTrue("窗口那一侧用 applyTransactionOnDraw",
            "root.applyTransactionOnDraw(it)" in onWindowFrame)
        val startFrame = handoff.substringAfter("fun leave(").substringBefore("\n    }")
        assertTrue("「快照显示 + 粒子末帧撤下」绑在同一次 Surface 提交上",
            "window.endCardFrame(session, transaction)" in startFrame)
        val surface = stripComments(text("app/src/main/java/com/ywwynm/everythingdone/" +
            "views/particledismiss/ParticleWindowSurface.kt"))
        assertTrue("Surface 那一侧用 applyTransactionToFrame",
            "view.applyTransactionToFrame(transaction)" in surface)
    }

    // ---- 第 3 条：切换方向不再让对话框凭空消失 ----

    @Test fun `对话框可见且静止时，换方向直接播一轮`() {
        val apply = pageCode.substringAfter("private fun applyDirection(degrees").substringBefore("\n    }")
        assertTrue("同一个方向不重播", "if (dialogDirection == degrees) return" in apply)
        assertTrue("一轮就是「消散 → 出现」两步", "dialogStepsLeft = ROUND_STEPS" in apply)
        assertTrue("立刻走，不等下一次排程", "stepDialog()" in apply)
        assertEquals("一轮两步", "2",
            Regex("""ROUND_STEPS = (\d+)""").find(pageCode)!!.groupValues[1])
        // 只有「此刻根本不可见」才走另一条入口——第 11 批起那一条也不会让画面空着：
        // 它先把静止的对话框显示出来，再播同样的一轮。
        assertTrue("不可见时先显示静止的对话框再播",
            "if (!dialogShowing) { beginDialogPreview(); return }" in apply)
    }

    @Test fun `正在播的时候换方向，等这一轮走完再补`() {
        val apply = pageCode.substringAfter("private fun applyDirection(degrees").substringBefore("\n    }")
        assertTrue("排程里还有步骤、或这一步没落地，都算正在播",
            "if (dialogStepsLeft > 0 || dialogBusy) { pendingRound = true; return }"
                in apply)
        val finish = pageCode.substringAfter("private fun finishDialogStep")
            .substringBefore("\n    }")
        assertTrue("这一步落地才解锁", "dialogBusy = false" in finish)
        assertTrue("整轮走完之前不补",
            "if (dialogStepsLeft > 0) { main.postDelayed(dialogStep, STEP_GAP_MS); return }"
                in finish && "if (!pendingRound) return" in finish)
        assertTrue("补的也是一轮两步", "dialogStepsLeft = ROUND_STEPS" in finish)
    }

    @Test fun `每一步都要拿到「播完了」的回调，另有看门狗兜底`() {
        val step = pageCode.substringAfter("private fun stepDialog").substringBefore("\n    }")
        assertTrue("消散那一侧传收尾回调",
            "panelAnimator.dismiss(target, dialogDirection, elevation, settled" in step)
        assertTrue("出现那一侧也传",
            "panelAnimator.appear(target, dialogDirection, elevation, settled)" in step)
        assertTrue("起不来时立刻解锁，锁不能一直留着",
            "finishDialogStep()" in step)
        assertTrue("起步前先把透明度还原：原子交接期间对话框的 alpha 是 0，" +
            "出现那一条会把当前值记下来、播完再还原回去", "target.alpha = 1f" in step)
        assertTrue("下一步等这一步落地再排，不按固定间隔",
            "main.postDelayed(dialogStep, STEP_GAP_MS)" in
                pageCode.substringAfter("private fun finishDialogStep").substringBefore("\n    }"))
        val begin = pageCode.substringAfter("private fun beginDialogStep").substringBefore("\n    }")
        assertTrue("看门狗按设备的动画缩放放大",
            "AnimatorDurationScale.scale(this).coerceAtLeast(1f)" in begin)
        assertTrue("动画层那一侧真的会回调",
            "onDone = settle" in panelAnimator && "onAppearanceFinished = settle" in panelAnimator)
    }

    @Test fun `切回卡片那一格时把正在播的对话框粒子收掉`() {
        val switch = pageCode.substringAfter("private fun switchPreview").substringBefore("\n    }")
        assertTrue("取消正在播的出现层", "panelAnimator.cancel(sampleDialog)" in switch)
        assertTrue("待补的那一轮也作废", "pendingRound = false" in switch)
        assertTrue("锁一并清掉", "dialogBusy = false" in switch)
    }

    @Test fun `换色只逐步插值，不动两块预览的可见性`() {
        // 第 9 批要一并检查的「有没有别的瞬间消失/出现」：换色走的是 ArgbEvaluator 逐步插值，
        // 全程不碰 visibility，也不重开动画，所以原样保留。
        val transition = pageCode.substringAfter("private fun startColorTransition")
            .substringBefore("\n    }")
        assertFalse("换色不许改可见性", "visibility" in transition)
        assertFalse("换色不许重开一轮", "beginDialogPreview" in transition)
        val apply = pageCode.substringAfter("private fun applyThemeColor").substringBefore("\n    }")
        assertFalse("套主题色也不许改可见性", "visibility" in apply)
    }
}
