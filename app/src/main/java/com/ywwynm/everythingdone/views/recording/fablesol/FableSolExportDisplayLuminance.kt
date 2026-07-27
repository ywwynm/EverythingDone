package com.ywwynm.everythingdone.views.recording.fablesol

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 从屏幕自身的能力与当前 HDR 强度推一个漫反射白默认值。
 *
 * PQ 是绝对亮度；默认白锚既不能写死，也不能只盯着屏幕小面积峰值。这里同时使用：
 *
 * - `desiredMaxLuminance`：约束最终 HDR 峰值；
 * - `desiredMaxAverageLuminance`：约束大面积普通画面，避免只凭小面积峰值把水体整体抬得过亮；
 * - HDR 强度：同一白锚乘 3.6 与乘 9.6 得到的母版峰值完全不同，必须一起算。
 *
 * 自动档允许母版峰值达到面板峰值的 [CONTENT_PEAK_ALLOWANCE] 倍，给 HDR10+ 留下映射空间；
 * 但不再像旧公式“峰值 ÷ 4”那样，在默认 9.6 强度下固定产出面板峰值 2.4 倍的内容。
 *
 * 读的是面板能力，不是此刻亮度或 `hdrSdrRatio`；所以同一台设备不会因用户临时调暗屏幕而
 * 改写产物。用户一旦手动拖动漫反射白，调用方会保存手动值，不再走本策略。
 */
internal object FableSolExportDisplayLuminance {

    data class Recommendation(
        val whiteNits: Float,
        val panelPeakNits: Float?,
        val panelMaxAverageNits: Float?,
        val hdrStrength: Float,
        /** 由 `面板峰值 × 1.75 ÷ HDR 强度` 得到；面板未声明时为 null。 */
        val peakConstraintWhiteNits: Float?,
        /** 所有可用约束取最小值后的原始结果，尚未应用自动范围与 25 尼特档位量化。 */
        val rawConstraintWhiteNits: Float,
        val fallbackUsed: Boolean
    ) {
        val authoredPeakNits: Float
            get() = whiteNits * hdrStrength
    }

    private data class DisplayLuminance(
        val peakNits: Float?,
        val maxAverageNits: Float?
    )

    /**
     * @return 推荐的漫反射白（尼特）。读不到或读到不可信的值时返回
     *   [FableSolExportOptions.DEFAULT_PQ_WHITE_NITS]。
     */
    fun autoWhiteNits(context: Context, hdrStrength: Float): Float =
        autoWhiteRecommendation(context, hdrStrength).whiteNits

    fun autoWhiteRecommendation(
        context: Context,
        hdrStrength: Float
    ): Recommendation {
        val display = readDisplayLuminance(context)
        return recommend(
            panelPeakNits = display.peakNits,
            panelMaxAverageNits = display.maxAverageNits,
            hdrStrength = hdrStrength
        )
    }

    /**
     * 纯数学入口，供 JVM 回归覆盖所有厂商上报与强度组合。
     *
     * 只把**可用**约束放进最小值：峰值或最大帧平均亮度缺失时忽略该项；两者都缺失时，
     * [AUTO_WHITE_MAX_NITS] 与现有安全默认值相同，形成明确回退。
     */
    internal fun recommend(
        panelPeakNits: Float?,
        panelMaxAverageNits: Float?,
        hdrStrength: Float
    ): Recommendation {
        val peak = plausiblePeak(panelPeakNits)
        val average = plausibleAverage(panelMaxAverageNits)
        val strength = hdrStrength.takeIf { it.isFinite() }
            ?.coerceIn(FableSolHdrPolicy.STRENGTH_OFF, FableSolHdrPolicy.MAX_STRENGTH)
            ?: FableSolHdrPolicy.DEFAULT_STRENGTH
        val peakConstraint = peak?.let {
            it * CONTENT_PEAK_ALLOWANCE / strength
        }
        val raw = listOfNotNull(
            peakConstraint,
            average,
            AUTO_WHITE_MAX_NITS
        ).minOrNull() ?: FableSolExportOptions.DEFAULT_PQ_WHITE_NITS
        val bounded = raw.coerceIn(
            FableSolExportOptions.MIN_PQ_WHITE_NITS,
            AUTO_WHITE_MAX_NITS
        )
        val quantized = (
            floor((bounded + QUANTIZE_EPSILON_NITS) / AUTO_WHITE_STEP_NITS) *
                AUTO_WHITE_STEP_NITS
            ).toFloat().coerceIn(
                FableSolExportOptions.MIN_PQ_WHITE_NITS,
                AUTO_WHITE_MAX_NITS
            )
        return Recommendation(
            whiteNits = quantized,
            panelPeakNits = peak,
            panelMaxAverageNits = average,
            hdrStrength = strength,
            peakConstraintWhiteNits = peakConstraint,
            rawConstraintWhiteNits = raw,
            fallbackUsed = peak == null && average == null
        )
    }

    /**
     * 生成可直接核对的标准数学表达式。缺失的设备约束不进入参数列表，函数名、括号与分隔符
     * 固定使用 ASCII 语法，数值小数点固定为 `.`。
     */
    internal fun constraintFormula(recommendation: Recommendation): String {
        val terms = mutableListOf<String>()
        recommendation.panelPeakNits?.let { peak ->
            terms += "${formatDerivationNumber(peak)} × " +
                "${String.format(Locale.US, "%.2f", CONTENT_PEAK_ALLOWANCE)} ÷ " +
                String.format(Locale.US, "%.2f", recommendation.hdrStrength)
        }
        recommendation.panelMaxAverageNits?.let { average ->
            terms += formatDerivationNumber(average)
        }
        terms += formatDerivationNumber(AUTO_WHITE_MAX_NITS)
        return "min(${terms.joinToString(", ")})"
    }

    /** 公式与设备能力说明共用：整数不补 `.0`，非整数保留一位。 */
    internal fun formatDerivationNumber(value: Float): String {
        val integer = value.roundToInt()
        return if (abs(value - integer) < DISPLAY_INTEGER_EPSILON_NITS) {
            integer.toString()
        } else {
            String.format(Locale.US, "%.1f", value)
        }
    }

    /**
     * 屏幕声明的 HDR 内容峰值亮度（尼特）；读不到或明显不可信时返回 null。
     */
    @SuppressLint("NewApi")
    fun panelPeakNits(context: Context): Float? = readDisplayLuminance(context).peakNits

    /** 屏幕声明的最大帧平均亮度（尼特）；读不到或明显不可信时返回 null。 */
    @SuppressLint("NewApi")
    fun panelMaxAverageNits(context: Context): Float? =
        readDisplayLuminance(context).maxAverageNits

    @SuppressLint("NewApi")
    private fun readDisplayLuminance(context: Context): DisplayLuminance {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return DisplayLuminance(null, null)
        }
        return try {
            val manager = context.applicationContext
                .getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = manager?.getDisplay(Display.DEFAULT_DISPLAY)
            val capabilities = display?.hdrCapabilities
            DisplayLuminance(
                peakNits = plausiblePeak(capabilities?.desiredMaxLuminance),
                maxAverageNits = plausibleAverage(capabilities?.desiredMaxAverageLuminance)
            )
        } catch (ignored: Throwable) {
            DisplayLuminance(null, null)
        }
    }

    private fun plausiblePeak(value: Float?): Float? = value?.takeIf {
        it.isFinite() && it in MIN_PLAUSIBLE_PEAK_NITS..MAX_PLAUSIBLE_LUMINANCE_NITS
    }

    private fun plausibleAverage(value: Float?): Float? = value?.takeIf {
        it.isFinite() && it in MIN_PLAUSIBLE_AVERAGE_NITS..MAX_PLAUSIBLE_LUMINANCE_NITS
    }

    /** 允许母版峰值达到面板峰值 1.75 倍，保留映射空间但不让自动档失控。 */
    const val CONTENT_PEAK_ALLOWANCE = 1.75f
    /** 自动档不越过 400 尼特；更亮的 500～800 尼特必须由用户明确手动选择。 */
    const val AUTO_WHITE_MAX_NITS = 400f
    /** 与设置滑杆一致，自动值只落在 25 尼特档位，并始终向下取整以不突破约束。 */
    const val AUTO_WHITE_STEP_NITS = 25f

    // 低于这些值通常是占位符；高于一万尼特则超出 PQ 定义域。
    private const val MIN_PLAUSIBLE_PEAK_NITS = 300f
    private const val MIN_PLAUSIBLE_AVERAGE_NITS = 200f
    private const val MAX_PLAUSIBLE_LUMINANCE_NITS = 10000f
    private const val QUANTIZE_EPSILON_NITS = 1e-3f
    private const val DISPLAY_INTEGER_EPSILON_NITS = 0.05f
}
