package com.ywwynm.everythingdone.views.recording.fablesol

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FableSol 导出文案的 13 套语言覆盖（fablesol-video-export 全程护栏）。
 *
 * 漏翻一条不会让任何东西崩：Android 会静默回退到 `values` 的英文，于是中文界面里冒出一句
 * 英文，而这件事只有真正切到那个语言、并且恰好走到那条分支时才看得见。批次 3～7 一共新增了
 * 五十多条导出文案，逐个人工核对不现实，因此把规则钉在这里。
 *
 * 同时守住另一条护栏：**能力缓存只存结构化原始数据**。把已经本地化的整句写进缓存，等于让
 * 用户切一次系统语言就看到上一种语言的结论。
 */
class FableSolExportLocalizationTest {

    /** 项目支持的全部语言资源目录；`values` 是默认（英文）。 */
    private val locales = listOf(
        "values",
        "values-zh-rCN", "values-zh-rHK", "values-zh-rTW",
        "values-ja", "values-ko",
        "values-de", "values-es", "values-fr", "values-it", "values-pt", "values-ru",
        "values-hi"
    )

    /**
     * 刻意不逐语言翻译的专有名词。
     *
     * `HDR10`、`HDR10+`、`HLG`、`Dolby Vision 8.4` 在绝大多数语言里就写作拉丁品牌名，
     * 回退到默认值才是正确结果；只有中文按用户偏好覆盖为「杜比视界」。把它们塞进每套语言
     * 只会造出十来条与默认值一模一样的条目，日后改品牌名要改十三处。
     */
    private val properNouns = setOf(
        "fablesol_export_hdr_brand_dolby_vision",
        "fablesol_export_hdr_format_name_hdr10",
        "fablesol_export_hdr_format_name_hdr10_plus",
        "fablesol_export_hdr_format_name_hlg",
        "fablesol_export_hdr_format_name_dolby_vision_84"
    )

    @Test
    fun everyExportStringExistsInAllThirteenLocales() {
        val base = stringsOf("values")
            .filterKeys { it.startsWith("fablesol_") && it !in properNouns }
        assertTrue("默认语言里应当有大量 fablesol_ 文案", base.size > 80)

        val missing = mutableListOf<String>()
        for (locale in locales.drop(1)) {
            val translated = stringsOf(locale)
            for (key in base.keys) {
                if (!translated.containsKey(key)) missing += "$locale/$key"
            }
        }
        assertEquals("以下文案未覆盖全部语言：$missing", emptyList<String>(), missing)
    }

    @Test
    fun formatArgumentsMatchAcrossLocales() {
        // 参数个数或序号对不上会在运行时抛 IllegalFormatException——而且只在那条分支被走到
        // 时抛。位置参数（%1$s）必须逐个对齐，不能只数总数。
        val base = stringsOf("values").filterKeys { it.startsWith("fablesol_") }
        val mismatched = mutableListOf<String>()
        for (locale in locales.drop(1)) {
            val translated = stringsOf(locale)
            for ((key, english) in base) {
                val other = translated[key] ?: continue
                val expected = positionalArguments(english)
                val actual = positionalArguments(other)
                if (expected != actual) mismatched += "$locale/$key $expected != $actual"
            }
        }
        assertEquals("以下文案的格式参数不一致：$mismatched", emptyList<String>(), mismatched)
    }

    @Test
    fun exactSpecificationMessagesIncludeTheEncodingMode() {
        val chinese = stringsOf("values-zh-rCN")
        assertTrue(
            chinese.getValue("fablesol_export_no_exact_specification").contains("编码模式")
        )
        assertTrue(
            chinese.getValue("fablesol_export_public_specification").contains("%3\$s")
        )
    }

    @Test
    fun stringsWithLiteralPercentDeclareThemselvesUnformatted() {
        // 同一条字符串里出现多个"裸 %"时，aapt2 会拒绝编译；而只有一个时它能过，
        // 却会在 String.format 那条路上炸。规则统一成：带裸 % 的必须 formatted="false"。
        for (locale in locales) {
            val raw = fileOf(locale).readText(Charsets.UTF_8)
            val offenders = Regex("<string name=\"(fablesol_[^\"]+)\"([^>]*)>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
                .findAll(raw)
                .filter { match ->
                    val attributes = match.groupValues[2]
                    val body = match.groupValues[3]
                    // `%%` 是**正确**的转义，先整体去掉再找裸 %；否则每一条
                    // "%1$d%%" 都会被误判（本项目有三条进度与高光起点文案属此类）。
                    val bare = Regex("%(?![sd\\d])")
                        .containsMatchIn(body.replace("%%", ""))
                    bare && !attributes.contains("formatted=\"false\"")
                }
                .map { it.groupValues[1] }
                .toList()
            assertEquals(
                "$locale 里这些文案含裸 % 却没有 formatted=\"false\"",
                emptyList<String>(),
                offenders
            )
        }
    }

    @Test
    fun capabilityCacheKeepsStructuredDataOnly() {
        // 能力缓存里只允许出现稳定标识、编码器名与厂商原文，不得出现已本地化的整句：
        // 用户切一次系统语言就会看到上一种语言的结论（D50 的同一条原则）。
        val matrix = source("FableSolExportCapabilityMatrix.kt")
        assertFalse(matrix.contains("context.getString"))
        assertFalse(matrix.contains("R.string."))
        val failure = source("FableSolExportFailure.kt")
        assertFalse(failure.contains("context.getString"))
        assertFalse(failure.contains("R.string."))
        // 缓存里存的是稳定标识，展示时才翻。
        assertTrue(matrix.contains("stableId"))

        // 回环结论同理：存的是码值与稳定的原因标识，不是句子。
        val verification = source("FableSolExportHlgVerification.kt")
        assertFalse(verification.contains("R.string."))
        assertTrue(verification.contains("REASON_OK"))
    }

    @Test
    fun newlyAddedBatchStringsAreAllPresent() {
        // 批次 6、7 新增的两组文案逐个点名：上面的通用规则只保证"默认语言里有的都翻了"，
        // 万一某一条连默认语言都漏加，通用规则是发现不了的。
        val required = listOf(
            // 批次 6：HLG 信号范围（D136、D137、D144）
            "fablesol_param_export_hlg_signal_range",
            "fablesol_param_export_dolby_base_signal_range",
            "fablesol_export_hlg_signal_range_auto",
            "fablesol_export_hlg_signal_range_nominal",
            "fablesol_export_hlg_result_extended",
            "fablesol_export_hlg_result_nominal",
            "fablesol_export_detail_hlg_range",
            "fablesol_export_preparing_hlg_range",
            // 设置页信号范围的预计落点与 DV 基层澄清（D135、D144，2026-07-30）
            "fablesol_export_hlg_range_predict_extended",
            "fablesol_export_hlg_range_predict_nominal",
            "fablesol_export_dolby_base_range_note",
            // HDR10+ SEI 部分覆盖的如实说明（D91 第 4 条修订，2026-07-30）
            "fablesol_export_detail_hdr10plus_sei_partial",
            "fablesol_export_hlg_range_desc",
            "fablesol_export_hlg_range_verify",
            "fablesol_export_hlg_range_auto_format",
            // 批次 7：编码策略（D145～D151）
            "fablesol_param_export_qp_guard",
            "fablesol_param_export_high_complexity",
            "fablesol_param_export_b_frames",
            "fablesol_export_bitrate_auto",
            "fablesol_export_desc_cq",
            "fablesol_export_desc_vbr_auto",
            "fablesol_export_desc_vbr_custom",
            "fablesol_export_desc_qp_guard",
            "fablesol_export_desc_high_complexity",
            "fablesol_export_desc_b_frames",
            // B 帧不适用的三种具体原因（D148，2026-07-30 取代通用的 _unavailable）
            "fablesol_export_desc_b_frames_api",
            "fablesol_export_desc_b_frames_av1",
            "fablesol_export_desc_b_frames_baseline",
            "fablesol_export_detail_encoding",
            "fablesol_export_rate_control_cq",
            "fablesol_export_rate_control_vbr",
            "fablesol_export_rate_control_cbr",
            "fablesol_export_tool_high_complexity",
            "fablesol_export_tool_qp_guard",
            "fablesol_export_tool_b_frames",
            "fablesol_export_tool_no_b_frames",
            "fablesol_export_tool_separator",
            // HDR10+ 完整场景统计、场景级稳定曲线与结果说明（D177）
            "fablesol_export_desc_highlight_start",
            "fablesol_export_detail_hdr10plus_identity",
            "fablesol_export_reference_peak_infeasible",
            "fablesol_export_hdr_desc_hdr10_plus",
            // D179：公开规格失败、确认与重试状态
            "fablesol_export_public_specification",
            "fablesol_export_retry_reason_encoder_initialization",
            "fablesol_export_retry_reason_encoding_interrupted",
            "fablesol_export_retry_reason_hdr_scene_analysis",
            "fablesol_export_retry_reason_hdr_render_path",
            "fablesol_export_retry_reason_encoding_path",
            "fablesol_export_retry_confirmation",
            "fablesol_export_retry_summary_same_spec",
            "fablesol_export_retry_summary_changed_spec",
            "fablesol_export_retry_terminal_failure",
            "fablesol_export_invalid_request",
            "fablesol_export_no_exact_specification",
            "fablesol_export_internal_error",
            "fablesol_export_service_interrupted",
            "fablesol_export_confirmation_title",
            "fablesol_export_confirmation_notification_summary",
            "fablesol_export_use_suggested_spec",
            "fablesol_export_use_suggested_spec_retry",
            "fablesol_export_end_export"
        )
        val missing = mutableListOf<String>()
        for (locale in locales) {
            val strings = stringsOf(locale)
            for (key in required) {
                if (!strings.containsKey(key)) missing += "$locale/$key"
            }
        }
        assertEquals("以下本轮新增文案缺失：$missing", emptyList<String>(), missing)
    }

    @Test
    fun hdr10PlusSceneStringsUseFormalChinese() {
        val strings = stringsOf("values-zh-rCN")
        val keys = listOf(
            "fablesol_export_desc_highlight_start",
            "fablesol_export_detail_hdr10plus_identity",
            "fablesol_export_reference_peak_infeasible",
            "fablesol_export_hdr_desc_hdr10_plus"
        )
        val colloquialFragments = listOf(
            "装得进",
            "多带了一层",
            "想让曲线",
            "真的产生映射",
            "这套组合",
            "生成不了",
            "把参考显示峰值"
        )
        val offenders = keys.flatMap { key ->
            colloquialFragments
                .filter { fragment -> strings.getValue(key).contains(fragment) }
                .map { fragment -> "$key/$fragment" }
        }
        assertEquals("HDR10+ 场景文案仍含口语化表述：$offenders", emptyList<String>(), offenders)
    }

    @Test
    fun retryStringsUseFormalChineseAndNameTheCompleteSpecification() {
        val strings = stringsOf("values-zh-rCN")
        val reasonKeys = listOf(
            "fablesol_export_retry_reason_encoder_initialization",
            "fablesol_export_retry_reason_encoding_interrupted",
            "fablesol_export_retry_reason_hdr_scene_analysis",
            "fablesol_export_retry_reason_hdr_render_path",
            "fablesol_export_retry_reason_encoding_path"
        )
        for (key in reasonKeys) {
            assertTrue("$key 必须嵌入完整失败规格", strings.getValue(key).contains("%1\$s"))
        }
        val retryKeys = reasonKeys + listOf(
            "fablesol_export_retry_confirmation",
            "fablesol_export_retry_summary_same_spec",
            "fablesol_export_retry_summary_changed_spec",
            "fablesol_export_retry_terminal_failure",
            "fablesol_export_confirmation_notification_summary"
        )
        val colloquialFragments = listOf(
            "该规格",
            "这套规格",
            "换一个",
            "搞不定",
            "跑不动",
            "再试试",
            "装得进",
            "真的"
        )
        val offenders = retryKeys.flatMap { key ->
            colloquialFragments
                .filter { fragment -> strings.getValue(key).contains(fragment) }
                .map { fragment -> "$key/$fragment" }
        }
        assertEquals("规格重试文案仍含口语化表述：$offenders", emptyList<String>(), offenders)
    }

    @Test
    fun bitrateModeNamesDescribeTheActualVbrTarget() {
        val expected = mapOf(
            "values" to "Target bitrate",
            "values-zh-rCN" to "目标码率",
            "values-zh-rHK" to "目標位元率",
            "values-zh-rTW" to "目標位元率",
            "values-ja" to "目標ビットレート",
            "values-ko" to "목표 비트레이트",
            "values-de" to "Zielbitrate",
            "values-es" to "Tasa de bits objetivo",
            "values-fr" to "Débit cible",
            "values-it" to "Bitrate di destinazione",
            "values-pt" to "Taxa de bits alvo",
            "values-ru" to "Целевой битрейт",
            "values-hi" to "लक्षित बिटरेट"
        )
        for ((locale, value) in expected) {
            assertEquals(
                "$locale 的编码模式必须表述实际使用的 VBR 目标码率",
                value,
                stringsOf(locale).getValue("fablesol_export_mode_bitrate")
            )
        }
    }

    @Test
    fun hdr10PlusInfeasibleSceneUsesLocalizedResource() {
        val exporter = source("FableSolVideoExporter.kt")
        assertTrue(
            exporter.contains("R.string.fablesol_export_reference_peak_infeasible")
        )
        assertTrue(exporter.contains("context.getString"))
    }

    @Test
    fun retiredStringsAreGoneEverywhere() {
        // 随设计撤销而作废的文案要一起删掉，否则下一个人会以为它们还在用。
        val retired = listOf(
            "fablesol_export_hdr_format_off",
            "fablesol_export_hdr_desc_off",
            "fablesol_param_export_hdr_format",
            "fablesol_export_estimate_white_auto_formula",
            "fablesol_export_estimate_white_auto_fallback",
            "fablesol_export_estimate_white_manual",
            // D141 把杜比视界收敛为 8.4：Profile 5 与 8.1 的名字已无任何代码引用，
            // 留着只会让下一个人以为那两档还是产品能力。
            "fablesol_export_hdr_format_name_dolby_vision_5",
            "fablesol_export_hdr_format_name_dolby_vision_81",
            // D177 改由完整场景预分析得出确定结论。旧文案描述“部分高亮帧可能……”并以
            // 逐帧独立判定为前提，已不符合当前的场景级统计与曲线生成路径。
            "fablesol_export_reference_peak_risk"
        )
        val leftovers = mutableListOf<String>()
        for (locale in locales) {
            val strings = stringsOf(locale)
            for (key in retired) {
                if (strings.containsKey(key)) leftovers += "$locale/$key"
            }
        }
        assertEquals("以下已作废文案仍在：$leftovers", emptyList<String>(), leftovers)
    }

    // ---- 辅助 ----

    /** `%1$s` 之类的位置参数，按出现顺序。非位置的 `%s` 也一并收集，两边必须完全一致。 */
    private fun positionalArguments(value: String): List<String> =
        Regex("%(\\d+\\\$)?[sd]").findAll(value).map { it.value }.toList()

    private fun stringsOf(locale: String): Map<String, String> {
        val raw = fileOf(locale).readText(Charsets.UTF_8)
        return Regex(
            "<string name=\"([^\"]+)\"[^>]*>(.*?)</string>",
            RegexOption.DOT_MATCHES_ALL
        ).findAll(raw).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun fileOf(locale: String): File =
        projectFile("app/src/main/res/$locale/strings.xml")

    private fun source(name: String): String = projectFile(
        "app/src/main/java/com/ywwynm/everythingdone/views/recording/fablesol/$name"
    ).readText(Charsets.UTF_8)

    private fun projectFile(relativePath: String): File {
        var directory = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate
            directory = directory.parentFile ?: return@repeat
        }
        throw AssertionError("找不到 $relativePath")
    }
}
