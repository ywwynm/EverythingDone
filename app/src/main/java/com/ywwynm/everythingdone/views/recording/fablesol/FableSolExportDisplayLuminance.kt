package com.ywwynm.everythingdone.views.recording.fablesol

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 读取**本机显示设备**声明的 HDR 亮度能力。
 *
 * 这里读到的是"当前显示设备希望接收的内容峰值 / 最大帧平均亮度"，是**显示能力**，不是内容
 * 应采用的母版规范。按 D82，它只用于三件事：本机预览映射、设备诊断，以及"当前屏幕可能发生
 * 高光压缩"这类提示；不再参与默认导出信号与元数据的计算。
 *
 * 曾经这里还承担"由面板峰值、最大帧平均亮度与 HDR 强度推出漫反射白默认值"的职责
 * （D45）。那等于让"负责导出的是哪台设备"隐式改写内容的母版意图：同一份创作参数在两台设备
 * 上会得到不同的 PQ 像素与不同的静态元数据。D82 撤销了该默认语义，漫反射白改为与设备无关的
 * 标准 203 尼特（D83），相应的推导代码一并移除。
 *
 * 读的是面板能力，不是此刻亮度或 `hdrSdrRatio`，所以同一台设备不会因用户临时调暗屏幕而给出
 * 不同的诊断结论。
 */
internal object FableSolExportDisplayLuminance {

    /** 一次读数：两项都可能缺失，缺失即"该设备没有声明"，不伪造。 */
    data class DisplayLuminance(
        val peakNits: Float?,
        val maxAverageNits: Float?
    )

    /** 本机显示设备声明的 HDR 亮度能力；读不到或明显不可信的字段为 null。 */
    fun read(context: Context): DisplayLuminance = readDisplayLuminance(context)

    /** 屏幕声明的 HDR 内容峰值亮度（尼特）；读不到或明显不可信时返回 null。 */
    @SuppressLint("NewApi")
    fun panelPeakNits(context: Context): Float? = readDisplayLuminance(context).peakNits

    /** 屏幕声明的最大帧平均亮度（尼特）；读不到或明显不可信时返回 null。 */
    @SuppressLint("NewApi")
    fun panelMaxAverageNits(context: Context): Float? =
        readDisplayLuminance(context).maxAverageNits

    /**
     * 本机显示设备是否声明支持 HDR10+。
     *
     * D94 要求：本机不支持 HDR10+ 时明确提示"本机播放可能退回 HDR10，选择本机峰值不会让
     * HDR10+ 动态层在本机生效"。这是**显示**能力，与导出资格无关（D93）——编不编得出
     * HDR10+ 由编码器说了算，本机能不能看是另一回事。
     *
     * @return null 表示读不到该字段（API 34 以下没有这个常量），不伪造结论。
     */
    @SuppressLint("NewApi")
    fun panelSupportsHdr10Plus(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return try {
            val manager = context.applicationContext
                .getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = manager?.getDisplay(Display.DEFAULT_DISPLAY)
            val types = display?.hdrCapabilities?.supportedHdrTypes ?: return null
            types.any { it == Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS }
        } catch (ignored: Throwable) {
            null
        }
    }

    /**
     * 本机默认显示是否声明支持该 HDR 格式的播放（D93）。
     *
     * 只用于"当前屏幕无法准确预览"的提示，不参与导出资格——编不编得出由编码器说了算，
     * 本机能不能看是另一回事（D93）。HDR10+ 委托给 [panelSupportsHdr10Plus]，沿用其
     * API 门槛与语义。
     *
     * @return null 表示读不到能力；界面不提示、不伪造结论。
     */
    fun panelSupportsFormat(
        context: Context,
        format: FableSolExportHdrFormat
    ): Boolean? {
        if (format == FableSolExportHdrFormat.HDR10_PLUS) {
            return panelSupportsHdr10Plus(context)
        }
        return try {
            val manager = context.applicationContext
                .getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = manager?.getDisplay(Display.DEFAULT_DISPLAY)
            val types = display?.hdrCapabilities?.supportedHdrTypes ?: return null
            val wanted = when (format) {
                FableSolExportHdrFormat.HDR10 -> Display.HdrCapabilities.HDR_TYPE_HDR10
                FableSolExportHdrFormat.HLG -> Display.HdrCapabilities.HDR_TYPE_HLG
                FableSolExportHdrFormat.DOLBY_VISION_84 ->
                    Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION
                FableSolExportHdrFormat.HDR10_PLUS -> return panelSupportsHdr10Plus(context)
            }
            types.any { it == wanted }
        } catch (ignored: Throwable) {
            null
        }
    }

    /** 诊断与说明文字共用：整数不补 `.0`，非整数保留一位。 */
    internal fun formatDerivationNumber(value: Float): String {
        val integer = value.roundToInt()
        return if (abs(value - integer) < DISPLAY_INTEGER_EPSILON_NITS) {
            integer.toString()
        } else {
            String.format(Locale.US, "%.1f", value)
        }
    }

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

    internal fun plausiblePeak(value: Float?): Float? = value?.takeIf {
        it.isFinite() && it in MIN_PLAUSIBLE_PEAK_NITS..MAX_PLAUSIBLE_LUMINANCE_NITS
    }

    internal fun plausibleAverage(value: Float?): Float? = value?.takeIf {
        it.isFinite() && it in MIN_PLAUSIBLE_AVERAGE_NITS..MAX_PLAUSIBLE_LUMINANCE_NITS
    }

    // 低于这些值通常是占位符；高于一万尼特则超出 PQ 定义域。
    private const val MIN_PLAUSIBLE_PEAK_NITS = 300f
    private const val MIN_PLAUSIBLE_AVERAGE_NITS = 200f
    private const val MAX_PLAUSIBLE_LUMINANCE_NITS = 10000f
    private const val DISPLAY_INTEGER_EPSILON_NITS = 0.05f
}
