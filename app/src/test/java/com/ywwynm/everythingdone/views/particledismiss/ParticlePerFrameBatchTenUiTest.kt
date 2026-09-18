package com.ywwynm.everythingdone.views.particledismiss

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逐帧成本专项第十批（2026-09-18）的契约：粒子动画设置页的第六轮三条。
 *
 * 1. **状态栏与顶栏是一个整体**：主题色是渐变时，一条渐变横跨两块，交界处不再断开；
 * 2. **滑动条以滑块为准对齐**：滑块在 0 与最大值时的**外缘**落在标题左缘 / 读数右缘上，
 *    而不是让轨道对齐；
 * 3. **粒子大小读数用半角括号并留一个空格**，13 个语言目录一致；右侧一列（两处读数、
 *    颜色精度胶囊、勾选框）的**可见**右缘到屏幕右缘的距离一致。
 *
 * 逐项说明与真机数字见
 * `docs/features/particle-animation-performance/per-frame-batch10-ui-2026-09-18.md`。
 */
class ParticlePerFrameBatchTenUiTest {

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

    private val backgroundUtil get() = stripComments(
        text("app/src/main/java/com/ywwynm/everythingdone/utils/BackgroundUtil.kt"))

    private fun tagOf(id: String, source: String): String {
        val anchor = """android:id="@+id/$id""""
        assertTrue("布局里找不到 $id", anchor in source)
        return source.substringAfter(anchor).substringBefore(">")
    }

    private fun attr(tag: String, name: String): String? =
        Regex("""android:$name="([^"]*)"""").find(tag)?.groupValues?.get(1)

    /** 13 个语言目录：`values/` 加 12 个 `values-<语言>/`。 */
    private val stringFiles: List<File>
        get() = File(root, "app/src/main/res").listFiles()!!
            .filter { it.isDirectory && (it.name == "values" || it.name.startsWith("values-")) }
            .mapNotNull { File(it, "strings.xml").takeIf(File::isFile) }
            .sortedBy { it.parentFile.name }

    // ---- 第 1 条：状态栏 + 顶栏是一个整体 ----

    @Test fun `状态栏占位与顶栏套在同一个容器里`() {
        val header = tagOf("ll_particle_tuning_header", layout)
        assertEquals("容器要占满宽度", "match_parent", attr(header, "layout_width"))
        assertEquals("容器高度由两个子 View 决定", "wrap_content", attr(header, "layout_height"))
        assertEquals("竖着排：状态栏在上、顶栏在下", "vertical", attr(header, "orientation"))
        val inside = layout.substringAfter("@+id/ll_particle_tuning_header")
            .substringBefore("</LinearLayout>")
        assertTrue("状态栏占位要在容器里", "@+id/view_status_bar" in inside)
        assertTrue("顶栏要在容器里", "@+id/actionbar" in inside)
        // 两个子 View 自己保持透明，否则会把容器那条渐变盖掉。
        assertEquals("状态栏占位自己不上色",
            "@android:color/transparent", attr(tagOf("view_status_bar", layout), "background"))
        assertEquals("顶栏自己不上色",
            "@android:color/transparent", attr(tagOf("actionbar", layout), "background"))
        assertFalse("顶栏不再靠 layout_below 排在状态栏下面（已经在同一个竖排容器里）",
            """android:layout_below="@id/view_status_bar"""" in layout)
    }

    @Test fun `主题色只往那个容器上铺一次`() {
        val apply = pageCode.substringAfter("private fun applyThemeColor").substringBefore("\n    }")
        assertTrue("上色的对象是整块顶部",
            "BackgroundUtil.applyBackground(f<View>(R.id.ll_particle_tuning_header), background)"
                in apply)
        assertFalse("不许再单独给状态栏占位铺一条渐变",
            "applyBackground(f<View>(R.id.view_status_bar)" in apply)
        assertFalse("也不许再单独给顶栏铺一条", "applyBackground(toolbar" in apply)
        // `applyBackground` 的渐变是按 View 自己的 bounds 铺的，这正是分开上色会断开的原因。
        val painter = backgroundUtil.substringAfter("fun applyBackground(view: View?")
            .substringBefore("\n    }")
        assertTrue("渐变仍然是 GradientDrawable（按 bounds 铺）", "obtainGradient(view)" in painter)
    }

    // ---- 第 2 条：滑动条以滑块为准对齐 ----

    @Test fun `滑动条的左右内边距是「行内边距 + 半个滑块半径」`() {
        val align = pageCode.substringAfter("private fun alignSliderToTextEdges")
            .substringBefore("\n    }")
        assertTrue("半径要从滑块自己身上取，不写死",
            "bar.thumb?.intrinsicWidth ?: 0) / 2" in align)
        assertTrue("行内边距用的是同一个常量",
            "ROW_SIDE_PADDING_DP * resources.displayMetrics.density" in align)
        assertTrue("两侧各补一份按比例分下来的半径",
            "radius * SLIDER_EDGE_BALANCE" in align && "+ inset" in align)
        assertTrue("按需要才改，别每次换色都触发一次重新布局",
            "if (bar.paddingStart == side && bar.paddingEnd == side) return" in align)
        assertTrue("用相对内边距（RTL 下同样成立）", "bar.setPaddingRelative(" in align)
        assertEquals("行内边距与参数区各行同值", "16f",
            Regex("""ROW_SIDE_PADDING_DP = ([\d.]+f)""").find(pageCode)!!.groupValues[1])
        // 「轨道内缩」与「滑块外探」之和恒等于滑块半径，只能分配。取中点：滑块只在两端才
        // 顶到边，轨道却一直在。
        assertEquals("各让一半", "0.5f",
            Regex("""SLIDER_EDGE_BALANCE = ([\d.]+f)""").find(pageCode)!!.groupValues[1])
    }

    @Test fun `两根滑杆换色之后都要重新对齐一次`() {
        val apply = pageCode.substringAfter("private fun applyThemeColor").substringBefore("\n    }")
        // `setSeekBarBackground` 会换一个新的滑块 drawable，`mThumbOffset` 随之重算，
        // 所以每次上色之后都要跟一次对齐。
        val cell = apply.substringAfter("cellSlider?.let {").substringBefore("}")
        val flow = apply.substringAfter("flowSlider?.let {").substringBefore("}")
        listOf("粒子大小" to cell, "流动精细度" to flow).forEach { (name, block) ->
            assertTrue("$name 的滑杆先上色", "DisplayUtil.setSeekBarBackground(it, background)" in block)
            assertTrue("$name 的滑杆再对齐", "alignSliderToTextEdges(it)" in block)
        }
    }

    // ---- 第 3 条：半角括号与右缘对齐 ----

    @Test fun `粒子大小读数在 13 个语言目录里都用半角括号并留一个空格`() {
        val files = stringFiles.filter { "particle_tuning_cell_size_value" in it.readText(Charsets.UTF_8) }
        assertEquals("13 个语言目录都要有这一条", 13, files.size)
        files.forEach { file ->
            val value = Regex(
                """<string name="particle_tuning_cell_size_value">([^<]*)</string>"""
            ).find(file.readText(Charsets.UTF_8))!!.groupValues[1]
            assertEquals("${file.parentFile.name} 的写法", "%1\$s dp (%2\$s px)", value)
        }
    }

    @Test fun `设置页的文案里不许再出现全角括号`() {
        stringFiles.forEach { file ->
            for (match in Regex("""<string name="(particle_tuning[^"]*)">([^<]*)</string>""")
                .findAll(file.readText(Charsets.UTF_8))) {
                val name = match.groupValues[1]
                val value = match.groupValues[2]
                assertFalse("${file.parentFile.name}/$name 里还有全角括号：$value",
                    '（' in value || '）' in value)
            }
        }
    }

    @Test fun `勾选框的可见右缘与同一列的读数、胶囊对齐`() {
        // 勾选框画出来的方框比它 24dp 的占位向内缩 `CHECKBOX_VISUAL_INSET_DP`，
        // 不补这一段的话可见右缘会比读数与胶囊再往里 2 dp（魅族实测 18.0 对 16.0）。
        val inset = Regex("""CHECKBOX_VISUAL_INSET_DP: Float = ([\d.]+)f""")
            .find(backgroundUtil)!!.groupValues[1]
        assertEquals("2", inset)
        val box = tagOf("cb_particle_tuning_auto", layout)
        assertEquals("负外边距要正好补掉那一段", "-${inset}dp", attr(box, "layout_marginEnd"))
        assertEquals("左右写法都要给（兼容不走 RTL 解析的取值）",
            attr(box, "layout_marginEnd"), attr(box, "layout_marginRight"))
        assertTrue("仍然贴着行的右内边距", """android:layout_alignParentEnd="true"""" in box)
    }

    @Test fun `右侧一列的控件都靠行的右内边距定位，没有别的外边距`() {
        // 读数与胶囊的墨迹右缘就是它们的 bounds 右缘，所以只要各自贴住行的右内边距即可。
        listOf("tv_particle_tuning_cell_value", "tv_particle_tuning_flow_value").forEach { id ->
            val tag = tagOf(id, layout)
            assertTrue("$id 要贴右缘", """android:layout_alignParentEnd="true"""" in tag)
            assertEquals("$id 不许再另加右外边距", null, attr(tag, "layout_marginEnd"))
        }
        val high = tagOf("tv_particle_tuning_color_high", layout)
        assertEquals("颜色精度的第二枚胶囊不许另加右外边距", null, attr(high, "layout_marginEnd"))
        val colorRow = tagOf("ll_particle_tuning_color_row", layout)
        assertEquals("胶囊那一行的右内边距与其它行同值", "16dp", attr(colorRow, "paddingRight"))
    }
}
