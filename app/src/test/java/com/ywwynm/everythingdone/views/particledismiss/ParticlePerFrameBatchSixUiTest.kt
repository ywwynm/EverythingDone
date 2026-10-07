package com.ywwynm.everythingdone.views.particledismiss

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逐帧成本专项第六批（2026-09-18）的契约：粒子动画设置页的第二轮八条改动。
 *
 * 逐项说明见
 * `docs/features/particle-animation-performance/per-frame-batch6-ui-2026-09-18.md`。
 *
 * 被第七批取代的三条（「永远判不成完成」、倒放期间禁用滑动、示例记事文案）已从本类移除，
 * 现在的契约在 [ParticlePerFrameBatchSevenUiTest]。
 */
class ParticlePerFrameBatchSixUiTest {

    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "shared/particle-dismiss/rules.properties").isFile }

    private fun text(path: String) = File(root, path).readText(Charsets.UTF_8)

    private fun stripComments(source: String): String = source
        .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        .replace(Regex("""(?m)//.*$"""), " ")
        .replace(Regex("""<!--[\s\S]*?-->"""), " ")

    private val page get() =
        text("app/src/main/java/com/ywwynm/everythingdone/activities/ParticleTuningActivity.kt")

    private val pageCode get() = stripComments(page)

    private val layout get() = text("app/src/main/res/layout/activity_particle_tuning.xml")

    private val layoutCode get() = stripComments(layout)

    private val tuning get() = stripComments(
        text("app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/ParticleTuning.kt"))

    private val localeDirs: List<String>
        get() = (File(root, "app/src/main/res").listFiles() ?: emptyArray())
            .filter { it.isDirectory && it.name.startsWith("values") }
            .filter { File(it, "strings.xml").isFile }
            .map { it.name }
            .sorted()

    // ---- 第 1 条：新增的两项设置 ----

    @Test fun `颜色精度默认是高精度，选标准才切到 8 位累积`() {
        val cell = ParticlePerfTuning.microflakeCellDp
        val iterations = ParticlePerfTuning.jacobiIterations
        try {
            ParticlePerfTuning.applyUserTuning(0f, 100, colorStandard = false, autoTune = false)
            assertFalse("默认必须是 16 位浮点，也就是与加入本设置之前逐位相同",
                ParticlePerfTuning.phase2(ParticlePerfTuning.P2_SRGB8_ACCUMULATION))
            ParticlePerfTuning.applyUserTuning(0f, 100, colorStandard = true, autoTune = false)
            assertTrue("选「标准」要真的切到 SRGB8_ALPHA8 累积",
                ParticlePerfTuning.phase2(ParticlePerfTuning.P2_SRGB8_ACCUMULATION))
        } finally {
            // 这是进程级开关，切过去之后必须读回来确认恢复（第四批 setLanes(0) 的教训）。
            ParticlePerfTuning.applyUserTuning(cell, iterations,
                colorStandard = false, autoTune = false)
            assertFalse(ParticlePerfTuning.phase2(ParticlePerfTuning.P2_SRGB8_ACCUMULATION))
            assertFalse(ParticlePerfTuning.autoDowngrade)
        }
    }

    @Test fun `性能自动调节默认关，打开才让规则动手`() {
        val cell = ParticlePerfTuning.microflakeCellDp
        val iterations = ParticlePerfTuning.jacobiIterations
        try {
            ParticlePerfTuning.applyUserTuning(0f, 100, colorStandard = false, autoTune = false)
            assertFalse(ParticlePerfTuning.autoDowngrade)
            ParticlePerfTuning.applyUserTuning(0f, 100, colorStandard = false, autoTune = true)
            assertTrue(ParticlePerfTuning.autoDowngrade)
        } finally {
            ParticlePerfTuning.applyUserTuning(cell, iterations,
                colorStandard = false, autoTune = false)
            assertFalse(ParticlePerfTuning.autoDowngrade)
        }
    }

    @Test fun `两项新设置的键与恢复默认`() {
        assertEquals("color_precision_standard", ParticleTuning.KEY_COLOR_STANDARD)
        assertEquals("auto_performance_tuning", ParticleTuning.KEY_AUTO_TUNE)
        val restore = tuning.substringAfter("fun restoreDefaults").substringBefore("\n    }")
        for (key in listOf("KEY_FLOW_SIZE", "KEY_FLOW_DETAIL",
            "KEY_COLOR_STANDARD", "KEY_AUTO_TUNE")) {
            assertTrue("恢复默认要把 $key 退回未设置", "remove($key)" in restore)
        }
    }

    // ---- 第 2 条：普通滑动条 ----

    @Test fun `滑动条换成与海浪设置页同款的普通滑动条`() {
        assertTrue("布局用系统 SeekBar", Regex("""<SeekBar""").containsMatchIn(layoutCode))
        assertFalse("不再有自研的档位滑条", "LevelSlider" in layoutCode || "LevelSlider" in pageCode)
        assertFalse("刻度控件已随之删除",
            File(root, "app/src/main/java/com/ywwynm/everythingdone/views/LevelTicksView.kt").exists())
        assertFalse("刻度文案不该再出现在页面代码里", "setLabels(" in pageCode)
        assertTrue("轨道与滑块仍走应用自己的渐变着色",
            "DisplayUtil.setSeekBarBackground(it, background)" in pageCode)
        assertTrue("粒子大小的使用统一倍率档位（滑动天然吸附）",
            "cellSlider?.max = ParticleTuning.FLOW_SIZE_LEVELS.lastIndex" in pageCode)
    }

    // ---- 第 3 条：流动精细度 0–100 ----

    @Test fun `流动精细度是 0 到 100 的连续整数，默认 100`() {
        assertEquals(0, ParticleTuning.FLOW_MIN)
        assertEquals(100, ParticleTuning.FLOW_MAX)
        assertEquals(100, ParticleTuning.DEFAULT_FLOW)
        assertTrue("滑动条量程就是取值本身",
            "flowSlider?.max = ParticleTuning.FLOW_MAX" in pageCode)
    }

    @Test fun `取 0 时整段压力更新都不跑`() {
        val renderer = stripComments(
            text("app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/" +
                "ParticleMicroflakeRenderer.kt"))
        val skip = renderer.substringAfter("val skipPressure =").substringBefore("\n")
            .plus(renderer.substringAfter("val skipPressure =").substringAfter("\n")
                .substringBefore("\n"))
        assertTrue("0 要让整段压力更新早退：$skip",
            "ParticlePerfTuning.jacobiIterationsNow <= 0" in skip)
        val perf = stripComments(
            text("app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/" +
                "ParticlePerfTuning.kt"))
        assertTrue("推进来的取值不许被夹成 1", "iterations.coerceIn(0, 200)" in perf)
    }

    @Test fun `旧的六档下标按取值迁移过来，最高一档等于未设置`() {
        assertTrue("旧键只出现在迁移里",
            """LEGACY_KEY_FLOW_INDEX = "jacobi_iterations_index"""" in tuning)
        val migrate = tuning.substringAfter("private fun migrateLegacyFlow").substringBefore("\n    }")
        assertTrue("迁移之后要把旧键删掉", "remove(LEGACY_KEY_FLOW_INDEX)" in migrate)
        assertTrue("按取值搬，不按下标搬", "LEGACY_FLOW_LEVELS[legacy]" in migrate)
        assertTrue("最高一档本来就是默认值，只删键不写值",
            "legacy != LEGACY_FLOW_LEVELS.lastIndex" in migrate)
        assertEquals("flow_detail", ParticleTuning.KEY_FLOW_DETAIL)
    }

    // ---- 第 4 条：换色之后整页跟色 ----

    @Test fun `换色时整页元素一起跟过去`() {
        val apply = pageCode.substringAfter("private fun applyThemeColor").substringBefore("\n    }")
        // 第 10 批起状态栏占位与顶栏由 `ll_particle_tuning_header` 整块上色（一条渐变横跨
        // 两者），不再各自 applyBackground 一次。
        for (target in listOf("ll_particle_tuning_header", "applyToolbarColor", "paintCapsule",
            "updateColorTabs", "cellSlider", "flowSlider", "cellValue", "flowValue",
            "resetButton", "autoCheckBox", "paletteButton", "applySampleDialogColor",
            "applyCardColor")) {
            assertTrue("换色要带上 $target", target in apply)
        }
        val transition = pageCode.substringAfter("private fun startColorTransition")
            .substringBefore("\n    }")
        assertTrue("与海浪设置页一样逐步插值", "lerpBackground(from, target, fraction)" in transition)
        assertTrue("收尾再整页刷一次", "applyThemeColor(target, full = true)" in transition)
    }

    // ---- 第 5 条：松手之后的回位（第七批改成「过阈值就完成，再倒放回来」）----

    @Test fun `松手之后仍由粒子沿原轨迹回位`() {
        val callback = pageCode.substringAfter("private inner class SwipeCallback")
        assertTrue("回位期间把相位喂给动画器，粒子据此倒放",
            "particles = swipeAnimator.draw(viewHolder.itemView, dX)" in callback)
        assertTrue("回位结束时交回真实卡片", "swipeAnimator.reset(viewHolder.itemView)" in callback)
    }

    // ---- 第 6 条：默认主题色是应用的强调渐变 ----

    @Test fun `进页面用强调渐变，按了换色按钮才取记事颜色`() {
        assertTrue("初值是应用的强调渐变",
            "private var themeBackground: ThingBackground = App.defaultAccentBackground" in pageCode)
        val pool = pageCode.substringAfter("private fun loadColorPool").substringBefore("\n    }")
        assertFalse("颜色池建好之后不许自己换色", "switchThemeColor()" in pool)
        assertTrue("换色由按钮触发",
            "paletteButton?.setOnClickListener { switchThemeColor() }" in pageCode)
    }

    // ---- 第 7 条：换色按钮在预览下方正中 ----

    @Test fun `换色按钮在预览下方居中`() {
        // 第 8 批在换色按钮右侧加了方向按钮，居中改由外层那一排容器负责。
        val preview = layoutCode.substringAfter("""android:id="@+id/fl_particle_tuning_preview"""")
        val row = preview.substringAfter(
            """android:id="@+id/ll_particle_tuning_preview_buttons"""").substringBefore(">")
        assertTrue("按钮那一排要在预览区之后", row.isNotEmpty())
        assertTrue("水平居中", """android:layout_gravity="center_horizontal"""" in row)
        assertTrue("换色按钮就在这一排里", "iv_particle_tuning_palette" in preview)
        val previewBlock = layoutCode.substringAfter(
            """android:id="@+id/fl_particle_tuning_preview"""").substringBefore("</FrameLayout>")
        assertFalse("按钮不在预览区里面", "iv_particle_tuning_palette" in previewBlock)
    }

    // ---- 第 8 条：示例文案（示例记事那两条第七批已换成「远山」，契约移到那一批）----

    @Test fun `本批新增的文案进了每一个语言目录`() {
        val names = listOf(
            "particle_tuning_dialog_confirm", "particle_tuning_dialog_cancel",
            "particle_tuning_color_precision", "particle_tuning_color_precision_standard",
            "particle_tuning_color_precision_high", "particle_tuning_color_precision_detail",
            "particle_tuning_auto_tune", "particle_tuning_auto_tune_detail")
        assertTrue("语言目录至少应有十个", localeDirs.size >= 10)
        for (locale in localeDirs) {
            val strings = text("app/src/main/res/$locale/strings.xml")
            for (name in names) {
                assertTrue("$locale 缺少 $name", "name=\"$name\"" in strings)
            }
        }
    }

    @Test fun `新增文案同样不带开发口吻`() {
        val banned = mapOf(
            "values" to listOf("Jacobi", "p90", "iteration", "SRGB8", "RGBA16F"),
            "values-zh-rCN" to listOf("微片", "Jacobi", "p90", "迭代", "调参"))
        for ((locale, words) in banned) {
            val block = text("app/src/main/res/$locale/strings.xml").lineSequence()
                .filter { "\"particle_tuning" in it }
                .joinToString("\n")
            assertTrue("$locale 的文案块没取到", block.isNotEmpty())
            for (word in words) {
                assertFalse("$locale 的文案里还留着「$word」", word in block)
            }
        }
    }

    @Test fun `页面代码里一条写死的界面文本都没有`() {
        val literals = Regex(""""[^"\n]{4,}"""").findAll(pageCode)
            .map { it.value.trim('"') }
            .filter { candidate -> candidate.any { it.code > 0x7F } }
            .toList()
        assertTrue("界面文案必须走 strings.xml：$literals", literals.isEmpty())
    }
}
