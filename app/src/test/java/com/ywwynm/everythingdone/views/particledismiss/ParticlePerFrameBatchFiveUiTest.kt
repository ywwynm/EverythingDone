package com.ywwynm.everythingdone.views.particledismiss

import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逐帧成本专项第五批（2026-09-18）的契约：把粒子动画设置页做成产品品质的那八条改动。
 *
 * 最要紧的一条是**默认值必须与改动前逐位相同**：粒子大小的梯度换了一整套，而「未设置」时
 * 一律不往模型里写 dp 下限，沿用 `rules.properties` 里自带的 `cell=1.65`。
 *
 * 逐项说明见
 * `docs/features/particle-animation-performance/per-frame-batch5-ui-2026-09-18.md`。
 */
class ParticlePerFrameBatchFiveUiTest {

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

    /** 全部带 strings.xml 的语言目录。 */
    private val localeDirs: List<String>
        get() = (File(root, "app/src/main/res").listFiles() ?: emptyArray())
            .filter { it.isDirectory && it.name.startsWith("values") }
            .filter { File(it, "strings.xml").isFile }
            .map { it.name }
            .sorted()

    // ---- 第 7 条：档位与「默认逐位一致」 ----

    @Test fun `粒子大小的档位逐位等于用户裁定的那一组`() {
        val expected = floatArrayOf(
            .64f, .72f, .84f, .96f, 1.08f, 1.29f, 1.5f, 1.6f,
            1.8f, 2f, 2.1f, 2.4f, 2.56f, 2.7f, 3f, 3.2f
        )
        assertEquals("档位数", expected.size, ParticleTuning.CELL_DP_LEVELS.size)
        for (i in expected.indices) {
            assertEquals("第 $i 档", expected[i], ParticleTuning.CELL_DP_LEVELS[i], 1e-6f)
        }
    }

    @Test fun `未设置时不写 dp 下限，默认值因此与加入本设置之前逐位相同`() {
        for (index in ParticleTuning.CELL_DP_LEVELS.indices) {
            assertEquals("未设置时任何档位都不许往模型里写下限",
                0f, ParticleTuning.cellDpFloor(index, explicit = false), 0f)
        }
        // 模型取的是「规则里的下限」与「设置里的下限」的较大者，所以写 0 等于沿用规则。
        val model = stripComments(
            text("app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/" +
                "ParticleMicroflakeModel.kt"))
        assertTrue("模型要按 max(规则下限, 设置下限) 取值",
            Regex("""max\(\s*max\(rules\.decimal\("cell"\),\s*requested""").containsMatchIn(model))
        val rules = text("shared/particle-dismiss/rules.properties")
        assertTrue("规则里的下限还是 1.65，整条等价关系建立在它之上",
            rules.lineSequence().any { it.trim() == "cell=1.65" })
    }

    @Test fun `滑杆停在最接近规则下限的那一档`() {
        // 归一空间里一个 dp 是 1.875 个单位，规则里的 1.65 个单位 = 0.88 dp。
        val rulesDp = (ParticleTuning.RULES_CELL_FLOOR_UNITS / ParticleMicroflakeModel.DP_UNITS)
            .toFloat()
        assertEquals(.88f, rulesDp, 1e-3f)
        val nearest = ParticleTuning.CELL_DP_LEVELS.indices
            .minByOrNull { abs(ParticleTuning.CELL_DP_LEVELS[it] - rulesDp) }
        assertEquals("默认停的那一档要是梯度上最接近 0.88 dp 的",
            nearest, ParticleTuning.DEFAULT_CELL_INDEX)
        assertEquals(.84f, ParticleTuning.cellDpValue(ParticleTuning.DEFAULT_CELL_INDEX), 1e-6f)
    }

    @Test fun `低于规则下限的几档被规则吃掉，显式选中它们同样不改画面`() {
        val absorbed = ParticleTuning.CELL_DP_LEVELS.indices.filter {
            ParticleTuning.CELL_DP_LEVELS[it] * ParticleMicroflakeModel.DP_UNITS <=
                ParticleTuning.RULES_CELL_FLOOR_UNITS
        }
        assertEquals("0.64 / 0.72 / 0.84 三档都在规则下限之内", listOf(0, 1, 2), absorbed)
        assertTrue("再往上必须真的抬高下限",
            (3 until ParticleTuning.CELL_DP_LEVELS.size).all {
                ParticleTuning.CELL_DP_LEVELS[it] * ParticleMicroflakeModel.DP_UNITS >
                    ParticleTuning.RULES_CELL_FLOOR_UNITS
            })
    }

    @Test fun `档位键换过名字，旧下标不会被读成新梯度上的另一档`() {
        assertEquals("particle_size_dp_index", ParticleTuning.KEY_CELL_INDEX)
        val tuning = stripComments(
            text("app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/" +
                "ParticleTuning.kt"))
        assertTrue("恢复默认要把粒子大小退回「未设置」",
            "remove(KEY_CELL_INDEX)" in tuning)
        assertTrue("「未设置」由键在不在决定", "contains(KEY_CELL_INDEX)" in tuning)
        // 旧键只在迁移里出现：用户在上一版显式选过的那一档要搬到新梯度上最接近的一档，
        // 而上一版的最低一档本来就等于「未设置」，因此只删键、不写值。
        assertTrue("旧键要有迁移", "LEGACY_KEY_CELL_INDEX = \"microflake_cell_dp_index\"" in tuning)
        val migrate = tuning.substringAfter("private fun migrateLegacyCellIndex")
            .substringBefore("\n    }")
        assertTrue("迁移之后要把旧键删掉", "remove(LEGACY_KEY_CELL_INDEX)" in migrate)
        assertTrue("上一版的最低一档等于未设置，不许写成新梯度的第 0 档",
            "legacy in 1 until LEGACY_CELL_DP_LEVELS.size" in migrate)
        assertTrue("按 dp 取值找最接近的一档", "minByOrNull" in migrate)
    }

    @Test fun `档位文案按定义时的小数位显示`() {
        for (index in ParticleTuning.CELL_DP_LEVELS.indices) {
            val label = ParticleTuning.cellDpLabel(index)
            val value = ParticleTuning.CELL_DP_LEVELS[index]
            assertEquals("第 $index 档的文案要能还原成取值",
                value, label.replace(',', '.').toFloat(), 1e-6f)
            val decimals = label.length - label.indexOfFirst { it == '.' || it == ',' } - 1
            val expected = if ((value * 100f).roundToInt() % 10 == 0) 1 else 2
            assertEquals("第 $index 档的小数位", expected, decimals)
        }
    }

    // ---- 第 5 条：统计行四项，且不出现开发口吻 ----

    @Test fun `统计行是四项，渲染间隔与帧率互为倒数`() {
        ParticleFrameStats.begin()
        var at = 2_000_000_000L
        repeat(31) { ParticleFrameStats.onPresented(at); at += 16_666_667L }
        val summary = ParticleFrameStats.end()!!
        assertEquals(31, summary.frames)
        assertEquals(16.67f, summary.intervalMs, .05f)
        assertEquals(60f, summary.fps, .2f)
        assertEquals("帧率就是平均间隔的倒数", 1000f / summary.intervalMs, summary.fps, 1e-3f)

        for (locale in listOf("values", "values-zh-rCN")) {
            val stats = Regex("""<string name="particle_tuning_stats">(.*?)</string>""")
                .find(text("app/src/main/res/$locale/strings.xml"))!!.groupValues[1]
            for (slot in listOf("%1\$s", "%2\$d", "%3\$d", "%4\$s", "%5\$d")) {
                assertTrue("$locale 的统计行缺少 $slot", slot in stats)
            }
            assertFalse("$locale 的统计行不该出现 p90", "p90" in stats)
        }
    }

    @Test fun `用户可见文案不带开发口吻`() {
        val banned = mapOf(
            "values" to listOf("microflake", "Microflake", "Jacobi", "p90", "iteration"),
            "values-zh-rCN" to listOf("微片", "Jacobi", "p90", "迭代", "调参")
        )
        for ((locale, words) in banned) {
            val strings = text("app/src/main/res/$locale/strings.xml")
            val block = strings.lineSequence()
                .filter { "\"particle_tuning" in it || "\"settings_particle_tuning\"" in it }
                .joinToString("\n")
            assertTrue("$locale 的文案块没取到", block.isNotEmpty())
            for (word in words) {
                assertFalse("$locale 的文案里还留着「$word」：$block", word in block)
            }
        }
    }

    // ---- 第 8 条：全部语言目录都有 ----

    @Test fun `新增文案进了每一个语言目录`() {
        val names = listOf(
            "settings_particle_tuning", "particle_tuning_preview_card",
            "particle_tuning_preview_dialog", "particle_tuning_hint_card",
            "particle_tuning_hint_dialog", "cd_particle_tuning_switch_color",
            "particle_tuning_stats", "particle_tuning_stats_waiting", "particle_tuning_stats_idle",
            "particle_tuning_cell_size", "particle_tuning_cell_size_value",
            "particle_tuning_value_default", "particle_tuning_cell_size_detail",
            "particle_tuning_flow_detail", "particle_tuning_flow_detail_explanation",
            "particle_tuning_reset", "particle_tuning_card_title",
            "particle_tuning_card_content", "particle_tuning_dialog_title",
            "particle_tuning_dialog_content"
        )
        assertTrue("语言目录至少应有十个", localeDirs.size >= 10)
        for (locale in localeDirs) {
            val strings = text("app/src/main/res/$locale/strings.xml")
            for (name in names) {
                assertTrue("$locale 缺少 $name", "name=\"$name\"" in strings)
            }
        }
    }

    @Test fun `代码里不写死任何界面文案`() {
        val literals = Regex(""""[^"\n]{4,}"""").findAll(pageCode)
            .map { it.value.trim('"') }
            .filter { candidate -> candidate.any { it.code > 0x7F } }
            .toList()
        assertTrue("界面文案必须走 strings.xml：$literals", literals.isEmpty())
    }

    // ---- 第 1 至 4、6 条：页面本身 ----

    @Test fun `顶栏下方有投影，与其它二级页面一致`() {
        assertTrue("缺少顶栏投影", "@drawable/actionbar_shadow" in layout)
        val shadow = layout.substringAfter("""android:id="@+id/actionbar_shadow"""")
            .substringBefore("/>")
        // 第 10 批把状态栏占位与顶栏套进了 `ll_particle_tuning_header`（整块铺一条渐变），
        // 投影因此挂在那个容器下方——位置没变，仍然贴着顶栏的下沿。
        assertTrue("投影要挂在顶栏那一块的下方",
            """android:layout_below="@id/ll_particle_tuning_header"""" in shadow)
        assertTrue("投影要压在内容之上，也就是放在根布局的最后",
            layout.indexOf("@+id/actionbar_shadow") > layout.indexOf("sv_particle_tuning_params"))
    }

    @Test fun `示例卡最宽 640 dp 并居中`() {
        assertTrue("最大宽度要写成 640 dp", "CARD_MAX_WIDTH_DP = 640" in pageCode)
        val apply = pageCode.substringAfter("private fun applyCardWidth").substringBefore("\n    }")
        assertTrue("居中靠左右边距，不靠 gravity（RecyclerView 的条目没有这一项）",
            "leftMargin" in apply && "rightMargin" in apply)
        assertTrue("要按可用宽度封顶", "min(" in apply)
    }

    @Test fun `示例对话框用常规 surface 底色，只有标题与确定走强调色`() {
        val dialog = text("app/src/main/res/layout/dialog_particle_tuning_preview.xml")
        assertTrue("底色要取 surface", "@color/app_chrome_surface_elevated" in dialog)
        assertTrue("圆角与真实对话框同源",
            "@dimen/app_chrome_dialog_popup_corner_radius" in dialog)
        assertTrue("按钮排布与真实对话框同源",
            "@dimen/app_chrome_dialog_action_button_height" in dialog)
        val apply = pageCode.substringAfter("private fun applySampleDialogColor")
            .substringBefore("\n    }")
        assertTrue("标题与确定按钮走记事颜色",
            "applyTextBackground(title" in apply && "applyTextBackground(confirm" in apply)
        assertFalse("对话框整块底色不许套强调渐变",
            "ll_particle_tuning_dialog_body" in pageCode)
    }

    @Test fun `选项卡与恢复默认是胶囊形并带渐变涟漪`() {
        val capsule = pageCode.substringAfter("private fun makeCapsule").substringBefore("\n    }")
        assertTrue("胶囊的涟漪要用 GradientRippleDrawable", "GradientRippleDrawable(" in capsule)
        assertTrue("胶囊形：圆角取 -1f（控件高度的一半）", "cornerRadiusPx = -1f" in capsule)
        assertTrue("恢复默认按钮走胶囊形的强调涟漪",
            "GradientRippleDrawable.applyAccentRipple(it, background" in pageCode)
    }

    // 滑动条那一条契约在第六批整体换过（自研档位滑条 → 与海浪设置页同款的普通滑动条），
    // 见 ParticlePerFrameBatchSixUiTest。

    @Test fun `当前值旁边标出本机像素`() {
        val value = Regex("""<string name="particle_tuning_cell_size_value">(.*?)</string>""")
            .find(text("app/src/main/res/values/strings.xml"))!!.groupValues[1]
        assertTrue("文案要同时给出 dp 与 px", "dp" in value && "px" in value)
        val pixel = pageCode.substringAfter("private fun pixelLabel").substringBefore("\n    }")
        assertTrue("像素按本机密度换算", "displayMetrics.density" in pixel)
        assertTrue("未显式选过时要标注默认",
            "particle_tuning_value_default" in pageCode)
    }

    @Test fun `预览的记事颜色取自用户自己的记事，无记事时退回默认记事色`() {
        val pool = pageCode.substringAfter("private fun loadColorPool").substringBefore("\n    }")
        assertTrue("颜色池从记事表读", "ThingDAO.getInstance" in pool)
        assertTrue("读的是记事自己的背景", "Thing(cursor).getBackground()" in pool)
        assertTrue("一条记事都没有时退回默认记事色", "R.array.thing" in pool)
        assertTrue("在后台线程读，不挡主线程", "Thread(" in pool)
        assertTrue("页面上要有换色按钮", "iv_particle_tuning_palette" in layout)
        assertTrue("换色是随机取一条与当前不同的",
            "candidates[random.nextInt(candidates.size)]" in pageCode)
    }
}
