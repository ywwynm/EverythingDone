package com.ywwynm.everythingdone.views.particledismiss

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逐帧成本专项第七批（2026-09-18）的契约：粒子动画设置页的第三轮八条改动。
 *
 * 逐项说明见
 * `docs/features/particle-animation-performance/per-frame-batch7-ui-2026-09-18.md`。
 */
class ParticlePerFrameBatchSevenUiTest {

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

    private val swipeReturn get() = stripComments(
        text("app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/" +
            "ThingSwipeReturn.kt"))

    private val localeDirs: List<String>
        get() = (File(root, "app/src/main/res").listFiles() ?: emptyArray())
            .filter { it.isDirectory && it.name.startsWith("values") }
            .filter { File(it, "strings.xml").isFile }
            .map { it.name }
            .sorted()

    private fun value(locale: String, name: String) =
        Regex("""<string name="$name">([\s\S]*?)</string>""")
            .find(text("app/src/main/res/$locale/strings.xml"))!!.groupValues[1]

    /** 取布局里某个控件的那一段属性文本（到自闭合标签为止）。 */
    private fun attributesOf(id: String): String =
        layoutCode.substringAfter("""android:id="@+id/$id"""").substringBefore("/>")

    // ---- 第 1 条：选项卡的选中态只留涟漪 ----

    @Test fun `预览选项卡没有底色，只有胶囊形涟漪`() {
        val make = pageCode.substringAfter("private fun makeCapsule").substringBefore("\n    }")
        assertTrue("选项卡不许再有底色 drawable", "tab.background = null" in make)
        assertTrue("涟漪仍是胶囊形（半径 −1 = 高度的一半）", "cornerRadiusPx = -1f" in make)
        assertFalse("胶囊底色那条常量已经不该存在", "CAPSULE_TINT_ALPHA" in pageCode)
    }

    @Test fun `选中的选项卡靠文字颜色与字重区分`() {
        val paint = pageCode.substringAfter("private fun paintCapsule").substringBefore("\n    }")
        assertTrue("选中加粗", "Typeface.BOLD" in paint && "Typeface.NORMAL" in paint)
        assertTrue("选中用主题色、未选中用页面次级文字色",
            "if (selected) background else idle" in paint)
        assertFalse("不许再往底色 drawable 上刷主题色", "paintShape(" in paint)
    }

    // ---- 第 2 条：换色按钮去圆底 ----

    @Test fun `换色按钮只有图标与涟漪，图标取 on-surface 色`() {
        // 第 8 批起换色与方向两个按钮一起装 chrome，图标色也从 on-surface 主级换成次级
        // （浅色下主级叠在纯黑矢量上仍是实黑）；那一条契约在第 8 批的用例里。
        val button = pageCode.substringAfter("listOf(paletteButton, directionButton).forEach {")
            .substringBefore("\n        }")
        assertTrue("去掉圆形底", "it.background = null" in button)
        assertTrue("涟漪是圆形的", "shapeOval = true" in button)
        val apply = pageCode.substringAfter("private fun applyThemeColor").substringBefore("\n    }")
        assertFalse("换色不再改图标本身的颜色", "setColorFilter" in apply)
        assertTrue("跟着换色的只有那一圈涟漪",
            "(paletteButton?.foreground as? GradientRippleDrawable)?.updateBackground(background)"
                in apply)
    }

    // ---- 第 3 条：颜色精度两个选项与标题同一行 ----

    @Test fun `颜色精度的两个选项与标题排在同一行`() {
        val label = attributesOf("tv_particle_tuning_color_label")
        assertTrue("标题占满剩余宽度，把选项挤到右边", """android:layout_weight="1"""" in label)
        val row = layoutCode
            .substringAfter("""android:id="@+id/tv_particle_tuning_color_label"""")
            .substringBefore("""android:id="@+id/tv_particle_tuning_color_detail"""")
        assertTrue("标准在同一行里", "tv_particle_tuning_color_standard" in row)
        assertTrue("高也在同一行里", "tv_particle_tuning_color_high" in row)
        assertFalse("两个选项之间不该再隔一层竖向容器",
            """android:orientation="vertical"""" in row)
    }

    @Test fun `颜色精度的选项样式与「导出色彩设置」同一种`() {
        val paint = pageCode.substringAfter("private fun paintChoiceChip").substringBefore("\n    }")
        assertTrue("选中填满完整渐变", "BackgroundUtil.fillDrawable(background)" in paint)
        assertTrue("未选中自绘一圈渐变描边",
            "BackgroundUtil.GradientStrokeDrawable(" in paint)
        assertTrue("选中态的文字走纯色分支才能自适应黑白",
            "ThingBackground.pure(" in paint)
        assertTrue("涟漪走 foreground", "chip.foreground as? GradientRippleDrawable" in paint)
        assertTrue("两个选项都按这一种画", "paintChoiceChip(colorStandardTab" in pageCode &&
            "paintChoiceChip(colorHighTab" in pageCode)
    }

    @Test fun `颜色精度的选项文本与说明按协调方拟定的改`() {
        assertEquals("标准", value("values-zh-rCN", "particle_tuning_color_precision_standard"))
        assertEquals("高", value("values-zh-rCN", "particle_tuning_color_precision_high"))
        // 「标准 = 8 位整数、高 = 16 位浮点数」是实现的真实口径：标准档是 SRGB8_ALPHA8（定点），
        // 只有高精度档的 RGBA16F 才是浮点。初稿把两档都写成浮点，用户裁定后已更正。
        assertEquals("标准精度使用 8 位整数表示颜色，计算压力更轻；高精度使用 16 位浮点数，过渡更细腻。",
            value("values-zh-rCN", "particle_tuning_color_precision_detail"))
        val english = value("values", "particle_tuning_color_precision_detail")
        assertTrue("英文要给出同样的信息量：8 位整数 / 计算更轻",
            "8-bit integer" in english && "lighter to compute" in english)
        assertTrue("英文要给出同样的信息量：16 位浮点 / 过渡更细腻",
            "16-bit floating point" in english && "finer" in english)
    }

    @Test fun `每个语言目录都说清了「标准是整数、高是浮点」`() {
        // 判据只看「8 与 16 两个位宽各出现一次」与「浮点只跟 16 位走」：各语言的措辞不同，
        // 但不得再把标准档说成浮点。
        val floating = mapOf(
            "values" to "floating point", "values-zh-rCN" to "浮点", "values-zh-rHK" to "浮點",
            "values-zh-rTW" to "浮點", "values-ja" to "浮動小数点", "values-ko" to "부동소수점",
            "values-de" to "Gleitkomma", "values-es" to "coma flotante",
            "values-fr" to "virgule flottante", "values-it" to "virgola mobile",
            "values-pt" to "ponto flutuante", "values-ru" to "плавающей запятой",
            "values-hi" to "फ़्लोटिंग पॉइंट")
        for ((locale, word) in floating) {
            val text = value(locale, "particle_tuning_color_precision_detail")
            val at = text.indexOf(word)
            assertTrue("$locale 里应出现「$word」：$text", at > 0)
            val eight = text.indexOf("8")
            val sixteen = text.indexOf("16")
            assertTrue("$locale 里 8 与 16 都要出现：$text", eight > 0 && sixteen > 0)
            assertTrue("$locale 把浮点写在了 8 位那一半上：$text", at > eight)
        }
    }

    // ---- 第 4 条：性能自动调节整行可点 ----

    @Test fun `性能自动调节整行可点，勾选框自己不接管点击`() {
        // 行本身的尺寸与内边距在**第 9 批**改为以「空间照片效果」设置页的倾斜行为准
        // （72dp / 16dp / 上下 10dp，容器也从 LinearLayout 换成 RelativeLayout），
        // 判据搬到 ParticlePerFrameBatchNineUiTest；这里只留「整行可点」这一条。
        val row = layoutCode.substringAfter("""android:id="@+id/rl_particle_tuning_auto_row"""")
            .substringBefore(">")
        assertTrue("整行可点", """android:clickable="true"""" in row)
        val box = attributesOf("cb_particle_tuning_auto")
        assertTrue("勾选框自己不可点", """android:clickable="false"""" in box)
        assertTrue("整行的点击去翻勾选框",
            "it.setOnClickListener { autoCheckBox?.toggle() }" in pageCode)
        assertTrue("整行自己带一层涟漪",
            "it.background = GradientRippleDrawable(" in pageCode)
    }

    @Test fun `标题与说明之间留出间距`() {
        val detail = attributesOf("tv_particle_tuning_auto_detail")
        // 第 8 批按「音频海浪动画设置」页的同类说明行改成 2dp（那一页是 padding(20, 2, 20, 10)）。
        assertTrue("说明与上方的标题之间要有间距",
            """android:layout_marginTop="2dp"""" in detail)
        // 第 9 批把说明搬进了整行内部（与参照页一致），左右内边距由整行给，它自己不再带。
    }

    @Test fun `性能自动调节的说明改成「减轻计算压力」`() {
        val zh = value("values-zh-rCN", "particle_tuning_auto_tune_detail")
        assertTrue("要出现「减轻计算压力」：$zh", "减轻计算压力" in zh)
        assertFalse("不再写「并提示，可随时改回」：$zh", "可随时改回" in zh)
        val english = value("values", "particle_tuning_auto_tune_detail")
        assertTrue("英文同样只说减轻计算压力", "ease the computing load" in english)
    }

    // ---- 第 5 条：示例卡片按首页规则完成，再倒放回来 ----

    @Test fun `示例卡片的阈值与回位规则与首页一致`() {
        val callback = pageCode.substringAfter("private inner class SwipeCallback")
        assertFalse("不再把阈值顶到不可能达到的值", "getSwipeThreshold" in callback)
        assertFalse("甩动速度同样回到默认", "getSwipeEscapeVelocity" in callback)
        assertFalse("回位时长也回到默认", "getAnimationDuration" in callback)
        assertTrue("过阈值之后先把剩下那一段播完",
            "swipeAnimator.finishSwipe(viewHolder.itemView, list.width, done)" in callback)
    }

    @Test fun `卡片彻底消失之后换一张新卡片再以粒子聚回来`() {
        val back = pageCode.substringAfter("private fun bringCardBack").substringBefore("\n    }")
        assertTrue("先换上一张新 View", "adapter.notifyItemChanged(0)" in back)
        assertTrue("再交给与首页同一条的回位",
            "cardReturn.start(0, particles = true" in back)
        assertTrue("这一页不看首页的风格设置，永远用粒子",
            "ThingSwipeReturn(this, cardList!!, alwaysParticles = true)" in pageCode)
        assertTrue("回位一律沿左侧的反向轨迹聚回来",
            "APPEAR_FROM_LEFT_DEGREES = 180f" in swipeReturn)
        assertTrue("开关只放宽「要不要看风格设置」这一项",
            "alwaysParticles ||" in swipeReturn)
    }

    @Test fun `收尾期间禁用滑动，回位真的结束了才解锁`() {
        assertTrue("松手就上锁", "private fun beginRelease" in pageCode)
        assertTrue("上锁之后不接受新的滑动方向",
            "if (returning) 0 else super.getSwipeDirs" in pageCode)
        assertTrue("整段手势都吞掉", "if (returning) return true" in pageCode)
        val clear = pageCode.substringAfter("override fun clearView").substringBefore("\n        }")
        assertTrue("换下旧 View 触发的 clearView 不许提前解锁", "if (!condensing)" in clear)
        assertTrue("回位收尾时才真的解锁", "condensing = false" in
            pageCode.substringAfter("cardReturn.start(0, particles = true")
                .substringBefore("\n    }"))
        assertTrue("另留一道看门狗", "private fun armUnlockWatchdog" in pageCode)
        val listenerAt = pageCode.indexOf("addOnItemTouchListener")
        val helperAt = pageCode.indexOf("ItemTouchHelper(SwipeCallback()).attachToRecyclerView")
        assertTrue("拦截器必须先于 ItemTouchHelper 注册", listenerAt in 1 until helperAt)
    }

    // ---- 第 6 条：示例对话框播一轮就停，点按钮重播 ----

    @Test fun `对话框进页面自动播一轮之后静止，不再循环`() {
        val step = pageCode.substringAfter("private fun stepDialog").substringBefore("\n    }")
        assertTrue("按剩余步数推进", "dialogStepsLeft--" in step)
        // 第 9 批起下一步不再按固定间隔排，而是等这一步真的落地（动画时长会被设备的动画
        // 缩放放大，按固定间隔会压在上一步的收尾上），判据改在 finishDialogStep 上。
        val finish = pageCode.substringAfter("private fun finishDialogStep")
            .substringBefore("\n    }")
        assertTrue("欠步数才排下一步",
            "if (dialogStepsLeft > 0) { main.postDelayed(dialogStep, STEP_GAP_MS); return }"
                in finish)
        // 第 11 批把三步开场序列整段删掉：切过去**直接显示静止的对话框**，再播一轮两步。
        // 「一进来自动播、播完就停、不循环」这条契约没变，判据见 ParticlePerFrameBatchElevenUiTest。
        assertTrue("自动播放排的就是一轮", "dialogStepsLeft = ROUND_STEPS" in pageCode)
    }

    @Test fun `点「稍后」或「继续」重播一轮`() {
        assertTrue("一轮是消散再出现", "ROUND_STEPS = 2" in pageCode)
        val replay = pageCode.substringAfter("private fun replayDialogRound")
            .substringBefore("\n    }")
        assertTrue("只在静止且可见时受理",
            "dialogStepsLeft > 0 || dialogBusy || !dialogShowing" in replay)
        assertTrue("重播排两步", "dialogStepsLeft = ROUND_STEPS" in replay)
        // 第 8 批起点击把按钮本身传下去，先等它的涟漪播完再消散。
        assertTrue("两个按钮各自绑点击",
            "button.setOnClickListener { replayDialogRound(button) }" in pageCode)
        assertFalse("预览区本身不再接管点击",
            "dialogHost?.setOnClickListener" in pageCode)
    }

    @Test fun `两个按钮各自可点，按下态不会同时落到两边`() {
        val setUp = pageCode.substringAfter("private fun setUpSampleDialog").substringBefore("\n    }")
        assertTrue("必须各自可点：不可点的子 View 会跟着父容器一起进按下态",
            "button.isClickable = true" in setUp)
        assertTrue("两个按钮一起处理", "listOf(cancel, confirm)" in setUp)
    }

    // ---- 第 7 条：恢复默认与上方元素右对齐 ----

    @Test fun `恢复默认的文本右缘与上方各行对齐`() {
        val reset = attributesOf("tv_particle_tuning_reset")
        assertTrue("靠右", """android:layout_gravity="end"""" in reset)
        assertTrue("内边距 8dp，涟漪只比文本略宽",
            """android:paddingRight="8dp"""" in reset && """android:paddingLeft="8dp"""" in reset)
        // 第 9 批把各行的左右内边距从 20dp 改成参照页的 16dp，外边距随之 12 → 8
        //（8 + 8 = 16，仍然与上方各行的右缘对齐）。等式判据在 ParticlePerFrameBatchNineUiTest。
        assertTrue("外边距 8dp", """android:layout_marginEnd="8dp"""" in reset)
    }

    // ---- 第 8 条：示例记事换成协调方拟的诗 ----

    @Test fun `示例记事是「远山」这首诗，四行且保留换行`() {
        assertEquals("远山", value("values-zh-rCN", "particle_tuning_card_title"))
        assertEquals("Distant Mountains", value("values", "particle_tuning_card_title"))
        assertEquals("远山不来，\\n我也不去。\\n风把云吹过山脊，\\n两不相欠，各自完整。",
            value("values-zh-rCN", "particle_tuning_card_content"))
        assertEquals(
            "The mountain does not come to me,\\nnor I to it.\\n" +
                "The wind carries clouds across the ridge;\\nnothing is owed, and each is whole.",
            value("values", "particle_tuning_card_content"))
        assertTrue("繁体也按同一首意译", "遠山" in value("values-zh-rTW", "particle_tuning_card_title"))
    }

    @Test fun `每个语言目录的示例记事都是四行`() {
        assertTrue("语言目录至少应有十个", localeDirs.size >= 10)
        for (locale in localeDirs) {
            val content = value(locale, "particle_tuning_card_content")
            assertEquals("$locale 的示例正文必须是四行：$content", 3,
                Regex("""\\n""").findAll(content).count())
        }
    }

    @Test fun `示例对话框的文案一个字没动`() {
        assertEquals("留在此刻", value("values-zh-rCN", "particle_tuning_dialog_title"))
        assertEquals("Stay in This Moment", value("values", "particle_tuning_dialog_title"))
        assertEquals("继续", value("values-zh-rCN", "particle_tuning_dialog_confirm"))
        assertEquals("稍后", value("values-zh-rCN", "particle_tuning_dialog_cancel"))
        assertTrue(value("values-zh-rCN", "particle_tuning_dialog_content")
            .endsWith("此刻是唯一还能改动的一页。要继续吗？"))
    }

    @Test fun `示例卡片容得下四行正文`() {
        assertTrue("正文行数上限要放开到四行以上",
            Regex("""android:maxLines="([4-9]|\d{2,})"""").containsMatchIn(cardLayoutCode))
    }

    @Test fun `本批改过的文案进了每一个语言目录，也没有开发口吻`() {
        val names = listOf("particle_tuning_card_title", "particle_tuning_card_content",
            "particle_tuning_color_precision_detail", "particle_tuning_auto_tune_detail",
            "particle_tuning_hint_dialog")
        for (locale in localeDirs) {
            val strings = text("app/src/main/res/$locale/strings.xml")
            for (name in names) assertTrue("$locale 缺少 $name", "name=\"$name\"" in strings)
        }
        val zh = text("app/src/main/res/values-zh-rCN/strings.xml").lineSequence()
            .filter { "\"particle_tuning" in it }.joinToString("\n")
        for (word in listOf("微片", "Jacobi", "p90", "迭代", "调参")) {
            assertFalse("中文文案里还留着「$word」", word in zh)
        }
    }
}
