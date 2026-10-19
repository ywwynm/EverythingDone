package com.ywwynm.everythingdone.views.particledismiss

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逐帧成本专项第八批（2026-09-18）的契约：粒子动画设置页的第四轮八条改动。
 *
 * 逐项说明见
 * `docs/features/particle-animation-performance/per-frame-batch8-ui-2026-09-18.md`。
 */
class ParticlePerFrameBatchEightUiTest {

    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "shared/particle-dismiss/rules.properties").isFile }

    private fun text(path: String) = File(root, path).readText(Charsets.UTF_8)

    private fun stripComments(source: String): String = source
        .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        .replace(Regex("""(?m)//.*$"""), " ")
        .replace(Regex("""<!--[\s\S]*?-->"""), " ")

    private val pageCode get() = stripComments(
        text("app/src/main/java/com/ywwynm/everythingdone/activities/ParticleTuningActivity.kt"))

    private val layoutCode get() = stripComments(
        text("app/src/main/res/layout/activity_particle_tuning.xml"))

    private val cardLayoutCode get() = stripComments(
        text("app/src/main/res/layout/card_particle_tuning_preview.xml"))

    private val dialogLayoutCode get() = stripComments(
        text("app/src/main/res/layout/dialog_particle_tuning_preview.xml"))

    private val popupLayoutCode get() = stripComments(
        text("app/src/main/res/layout/popup_particle_tuning_direction.xml"))

    private val controller get() = stripComments(
        text("app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/" +
            "ParticleDismissController.kt"))

    private val panelAnimator get() = stripComments(
        text("app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/" +
            "ParticlePanelAnimator.kt"))

    private val swipeReturn get() = stripComments(
        text("app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/" +
            "ThingSwipeReturn.kt"))

    /** 取布局里某个控件的那一段属性文本（到自闭合标签为止）。 */
    private fun attributesOf(id: String, source: String = layoutCode): String =
        source.substringAfter("""android:id="@+id/$id"""").substringBefore("/>")

    private fun bodyOf(signature: String): String =
        pageCode.substringAfter(signature).substringBefore("\n    }")

    private val localeDirs: List<String>
        get() = (File(root, "app/src/main/res").listFiles() ?: emptyArray())
            .filter { it.isDirectory && it.name.startsWith("values") }
            .filter { File(it, "strings.xml").isFile }
            .filter { """name="particle_tuning_hint_card"""" in
                File(it, "strings.xml").readText(Charsets.UTF_8) }
            .map { it.name }
            .sorted()

    private fun value(locale: String, name: String) =
        Regex("""<string name="$name">([\s\S]*?)</string>""")
            .find(text("app/src/main/res/$locale/strings.xml"))!!.groupValues[1]

    // ---- 第 1 条：两个图标按钮在亮色下不许是实黑 ----

    @Test fun `预览下方的图标按钮取 on-surface 次级色，不再是主级`() {
        // 图标矢量本身是纯黑：主级（#DE000000）叠上去在浅色底上量到 (0,0,0)，也就是用户说的
        // 「纯黑」。次级是 #8A000000 / #A8FFFFFF，与「音频海浪动画设置」页的同类按钮同一份。
        listOf("iv_particle_tuning_palette", "iv_particle_tuning_direction").forEach { id ->
            val button = attributesOf(id)
            assertTrue("$id 的图标色要取 on-surface 次级色",
                """android:tint="@color/app_chrome_on_surface_secondary"""" in button)
        }
        val chrome = pageCode.substringAfter("listOf(paletteButton, directionButton).forEach {")
            .substringBefore("\n        }")
        assertTrue("再压一层不透明度，与海浪页同一个取值",
            "imageAlpha = PREVIEW_BUTTON_ICON_ALPHA" in chrome)
        assertEquals("与 FableSolTuningDialogFragment 的 PREVIEW_BUTTON_ICON_ALPHA 一致",
            "176", Regex("""PREVIEW_BUTTON_ICON_ALPHA = (\d+)""")
                .find(pageCode)!!.groupValues[1])
        assertFalse("不许再把主级色刷到图标上",
            "app_chrome_on_surface_primary" in pageCode)
    }

    // ---- 第 2 条：颜色精度的胶囊恢复到第六批的尺寸 ----

    @Test fun `颜色精度胶囊的高度与字号回到第六批`() {
        listOf("tv_particle_tuning_color_standard", "tv_particle_tuning_color_high").forEach { id ->
            val chip = attributesOf(id)
            assertTrue("$id 的高度回到 36dp", """android:layout_height="36dp"""" in chip)
            assertTrue("$id 的字号回到 14sp", """android:textSize="14sp"""" in chip)
            assertFalse("不许再用第七批那一档 12sp", """android:textSize="12sp"""" in chip)
            assertFalse("不许再用第七批那一档 5dp 纵向内边距",
                """android:paddingTop="5dp"""" in chip)
        }
        assertEquals("圆角取高度的一半，仍是胶囊形", "18f",
            Regex("""CHIP_CORNER_DP = ([\d.]+f)""").find(pageCode)!!.groupValues[1])
    }

    // 第 2、3 条里那两条尺寸断言已被**第 9 批**取代：字号与行高改以「空间照片效果」设置页
    // 为准（标题 16sp、读数 14sp、勾选行 72dp），判据见 ParticlePerFrameBatchNineUiTest。
    // 本批仍成立的部分只剩「说明紧跟勾选行，只留 2dp」。

    // ---- 第 3 条：自动调节行与说明的间距 ----

    @Test fun `自动调节的说明紧跟那一行，只留 2dp`() {
        val detail = attributesOf("tv_particle_tuning_auto_detail")
        assertTrue("上边距 2dp", """android:layout_marginTop="2dp"""" in detail)
    }

    // ---- 第 4 条：按钮点击先把涟漪播完 ----

    @Test fun `点对话框按钮之后先等触摸反馈结算再消散`() {
        val replay = bodyOf("private fun replayDialogRound")
        assertTrue("按被点的那个按钮起一次反馈等待",
            "ParticleTouchFeedback.begin(this, source)" in replay)
        assertTrue("结算之后才走下一步", "afterSettled" in replay)
        assertTrue("没有反馈可等时不额外拖一帧", "if (feedback == null) stepDialog()" in replay)
        val setUp = bodyOf("private fun setUpSampleDialog")
        assertTrue("点击把按钮本身传下去",
            "button.setOnClickListener { replayDialogRound(button) }" in setUp)
    }

    // ---- 第 5 条：示例卡片的文字颜色随底色自适应 ----

    @Test fun `示例卡片的标题与正文按底色取对比色`() {
        val apply = bodyOf("private fun applyCardColor")
        assertTrue("标题走 onColor 的主级档",
            "BackgroundUtil.onColor(background, BackgroundUtil.ON_ALPHA_PRIMARY)" in apply)
        assertTrue("正文走 onColor 的次级档",
            "BackgroundUtil.onColor(background, BackgroundUtil.ON_ALPHA_SECONDARY)" in apply)
        assertFalse("布局里不许再写死白字", "white_86p" in cardLayoutCode)
        assertFalse("布局里不许再写死白字", "white_76p" in cardLayoutCode)
    }

    // ---- 第 6 条：对话框的阴影随粒子一起出现与消失 ----

    @Test fun `示例对话框的投影拍进快照，代码与布局取同一个 dimen`() {
        assertTrue("布局用 dimen",
            """app:cardElevation="@dimen/particle_tuning_dialog_elevation"""" in dialogLayoutCode)
        val step = bodyOf("private fun stepDialog")
        assertTrue("代码读同一个 dimen",
            "R.dimen.particle_tuning_dialog_elevation" in step)
        assertTrue("消散把投影高度传下去", "panelAnimator.dismiss(target, dialogDirection, elevation"
            in step)
        assertTrue("出现也传", "panelAnimator.appear(target, dialogDirection, elevation" in step)
        assertTrue("出现那一侧必须同时关掉圆角遮罩，否则外扩的四角阴影会被削掉",
            "roundSnapshot = shadowElevationPx == null" in panelAnimator)
    }

    @Test fun `含阴影的消散：外扩取景、原点偏移、粒径补回、交接期不留真实投影`() {
        assertTrue("消散那条路有这个入口", "shadowElevationPx: Float? = null" in controller)
        assertTrue("按传进来的 elevation 算取景范围，不用卡片那一档默认值",
            "ParticleCardSnapshot.of(decor, shadowElevationPx)" in controller)
        assertTrue("含阴影时不做圆角遮罩",
            "val radius = if (cardSnapshot != null) 0f else dialogCornerRadius(activity)"
                in controller)
        assertTrue("原点按外扩量往左上挪",
            "dialogLocation[0] - hostLocation[0] + inflateX" in controller)
        assertTrue("方向仍按本体中心算，不受外扩影响", "bodyCenterX" in controller)
        assertTrue("粒径按面积比补回来",
            "materialAreaScale = cardSnapshot?.materialAreaScale ?: 1f" in controller)
        assertTrue("含阴影时不再退回窗口 PixelCopy（尺寸与原点对不上）",
            "cardSnapshot == null && ParticleCaptureRoute.shouldRetryOtherRoute" in controller)
        val attach = controller.substringAfter("ParticleOverlayHost.attach(hostDecor, overlay)")
            .substringBefore("\n        var waiting")
        assertTrue("挂上叠加层的同一次遍历里把真实投影放掉", "decor.elevation = 0f" in attach)
        assertTrue("收尾再还回去", "decor.elevation = kept" in attach)
    }

    @Test fun `含阴影的快照与卡片那条各有各的入口`() {
        val appear = controller.substringAfter("fun appearContent(").substringBefore("\n    }")
        assertTrue("卡片那条仍由 cardShadow 决定", "inWindow = cardShadow" in appear)
        assertTrue("阴影快照是另一个入口",
            "shadowElevationPx != null -> ParticleCardSnapshot.of(decor, shadowElevationPx)"
                in appear)
        // 本批原来还断言「面板那条不许打开原子交接」。**第 9 批推翻了它**：交接期两层半透明
        // 投影叠加正是收尾与起步各闪一下的根因，面板因此也要走 ParticleCardSurfaceHandoff。
        // 新判据见 ParticlePerFrameBatchNineUiTest。
    }

    // ---- 第 7 条：方向按钮与八方向弹层 ----

    @Test fun `手动选择的八个方向齐全且互不相同`() {
        val cells = Regex("""R\.id\.iv_particle_direction_(\w+) to ([\d.]+f)""")
            .findAll(pageCode).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertEquals("八个方向", 8, cells.size)
        assertEquals("八个角度互不相同", 8, cells.map { it.second }.toSet().size)
        assertEquals("八格的名字齐全",
            setOf("lt", "t", "rt", "l", "r", "lb", "b", "rb"), cells.map { it.first }.toSet())
        // 约定：0 = 右、90 = 上、180 = 左、270 = 下（见 ParticleMicroflakeRenderer 的 atan2(-dy, dx)）。
        assertEquals("上是 90 度", "90f", cells.first { it.first == "t" }.second)
        assertEquals("右是 0 度", "0f", cells.first { it.first == "r" }.second)
        assertEquals("左是 180 度", "180f", cells.first { it.first == "l" }.second)
        assertEquals("下是 270 度", "270f", cells.first { it.first == "b" }.second)
        cells.forEach { (name, _) ->
            assertTrue("弹层布局里要有 $name 那一格",
                "iv_particle_direction_$name" in popupLayoutCode)
        }
    }

    @Test fun `按钮上的箭头就是当前方向，弹层里的格子同一套旋转`() {
        val update = bodyOf("private fun updateDirectionButton")
        assertTrue("旋转量是 90 减方向角", "90f - dialogDirection" in update)
        val popup = bodyOf("private fun showDirectionPopup")
        assertTrue("每一格按自己的角度转", "90f - degrees" in popup)
        assertTrue("选中的那一格用主题色", "themeBackground.representativeColor()" in popup)
        // 选完之后怎么衔接由**第 9 批**重定：不再 `startDialogSequence()`（那会先让对话框
        // 凭空消失），改成 `applyDirection`，判据见 ParticlePerFrameBatchNineUiTest。
        assertTrue("选完把新方向交给统一的衔接入口", "applyDirection(degrees)" in popup)
    }

    @Test fun `方向只作用于预览，不进用户设置`() {
        val tuning = stripComments(text("app/src/main/java/com/ywwynm/everythingdone/" +
            "views/particledismiss/ParticleTuning.kt"))
        assertFalse("ParticleTuning 里不许出现方向这一项", "direction" in tuning.lowercase())
        val reset = bodyOf("resetButton?.setOnClickListener {")
        assertFalse("「恢复默认」也不该碰它", "dialogDirection" in reset)
    }

    @Test fun `切换选项卡时两个按钮的布局是动画过渡`() {
        val animate = bodyOf("private fun animateDirectionButton")
        assertTrue("宽度做动画而不是直接改可见性", "ValueAnimator.ofInt(from, target)" in animate)
        assertTrue("时长有常量", "duration = DIRECTION_SWITCH_MS" in animate)
        assertFalse("不许用 visibility 跳变", "View.GONE" in animate)
        val switch = bodyOf("private fun switchPreview")
        assertTrue("切选项卡时调它", "animateDirectionButton(show = !card)" in switch)
        val row = attributesOf("ll_particle_tuning_preview_buttons")
        assertTrue("那一排自己居中，收起方向按钮之后换色按钮才会平滑回到正中",
            """android:layout_gravity="center_horizontal"""" in row)
    }

    // ---- 第 8 条：示例卡片允许右滑 ----

    @Test fun `示例卡片两个方向都能滑，右滑是左滑的镜像`() {
        assertTrue("动画器放开右滑",
            "ParticleSwipeAnimator(this, allowRightSwipe = true)" in pageCode)
        assertTrue("两个方向都受理",
            "ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.START or ItemTouchHelper.END)"
                in pageCode)
        val draw = pageCode.substringAfter("override fun onChildDraw").substringBefore("\n        }")
        assertFalse("不许再把右滑的会话每帧清掉",
            "if (dX > 0f) swipeAnimator.reset" in draw)
        assertTrue("松手之后两个方向都上锁", "if (!isCurrentlyActive && dX != 0f) beginRelease()"
            in draw)
        val back = bodyOf("private fun bringCardBack")
        assertTrue("回位从飞出去的那一侧聚回来",
            "fromRight = direction == ItemTouchHelper.END" in back)
        assertTrue("右滑的镜像角度是 0 度（正右方）",
            "APPEAR_FROM_RIGHT_DEGREES = 0f" in swipeReturn)
        assertTrue("左滑仍是 180 度", "APPEAR_FROM_LEFT_DEGREES = 180f" in swipeReturn)
        assertTrue("滑回来那一条也按方向取符号",
            "cos(Math.toRadians(-appearFrom.toDouble()))" in swipeReturn)
    }

    @Test fun `记事卡片那一格的提示改成「滑动」，每个语言目录都不再说「左滑」`() {
        assertEquals("滑动以预览动画效果", value("values-zh-rCN", "particle_tuning_hint_card"))
        assertEquals("Swipe to preview the animation",
            value("values", "particle_tuning_hint_card"))
        assertTrue("语言目录不止一个", localeDirs.size >= 13)
        localeDirs.forEach {
            val hint = value(it, "particle_tuning_hint_card")
            assertTrue("$it 的提示不许为空", hint.isNotBlank())
            assertFalse("$it 还写着「左滑」：$hint", "左滑" in hint || "左滑" in hint)
            assertFalse("$it 还写着 left：$hint", "left" in hint.lowercase())
        }
    }

    @Test fun `方向按钮与八个方向的文案进了每一个语言目录`() {
        val names = listOf("cd_particle_tuning_direction",
            "particle_tuning_direction_t", "particle_tuning_direction_b",
            "particle_tuning_direction_l", "particle_tuning_direction_r",
            "particle_tuning_direction_lt", "particle_tuning_direction_rt",
            "particle_tuning_direction_lb", "particle_tuning_direction_rb")
        localeDirs.forEach { locale ->
            names.forEach { name ->
                assertTrue("$locale 缺 $name",
                    """<string name="$name">""" in text("app/src/main/res/$locale/strings.xml"))
            }
        }
    }
}
