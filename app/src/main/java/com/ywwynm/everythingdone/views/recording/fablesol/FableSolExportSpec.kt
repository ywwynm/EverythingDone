package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.Context
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.utils.AppearanceUtil
import kotlin.math.roundToInt

/**
 * FableSol 可视化视频的画面规格。
 *
 * 构图**与触发入口无关**（fablesol-video-export D2）：恒用录音对话框那套紧凑构图——
 * 卡片高 420dp、时钟贴顶 36dp、不做取景平移。播放对话框那 30dp 的滑杆空间与取景平移
 * 都不进产物。
 *
 * dp 几何照抄当前对话框（宽度随用户数字字形在 280～383dp 间变化），像素高度提到固定档，
 * density 由此反推——这是真正的高分辨率重新渲染，不是放大。
 */
internal object FableSolExportSpec {

    /** 卡片高度的逻辑坐标，与 `fragment_record_audio.xml` 及 Fable 蓝本一致。 */
    const val CARD_HEIGHT_DP = 420.0

    /** Timely 字形决定的已知最大卡片宽度；设置页能力探测按最坏画布验证。 */
    const val MAX_CARD_WIDTH_DP = 383.0

    /** 固定像素高度档：1296 = 144 × 9，且 16 对齐。 */
    const val CARD_HEIGHT_PX = 1296

    const val PADDING_DP = 24.0
    const val CLOCK_TOP_DP = 36.0
    const val CLOCK_HEIGHT_DP = 40.0
    const val CLOCK_SIDE_DP = 24.0

    private const val SHADOW_OFFSET_DP = 6.0
    private const val SHADOW_RADIUS_DP = 24.0
    private const val RIM_WIDTH_DP = 1.0

    /** 画框底色跟随 Appearance Mode（D4）；偏白/偏黑但不与卡片抢注意力。 */
    private const val BACKDROP_DARK = 0xFF121212.toInt()
    private const val BACKDROP_LIGHT = 0xFFF4F4F4.toInt()
    private const val SHADOW_ALPHA_DARK = 0.28f
    private const val SHADOW_ALPHA_LIGHT = 0.40f
    /** 深色底上用亮描边，浅色底上用极淡的暗描边——极性必须跟着底色翻。 */
    private const val RIM_COLOR_DARK = 0xFFFFFFFF.toInt()
    private const val RIM_COLOR_LIGHT = 0xFF000000.toInt()
    private const val RIM_ALPHA_DARK = 0.10f
    private const val RIM_ALPHA_LIGHT = 0.06f

    /** 录音态时钟呼吸（与 AudioRecordDialogFragment 的常量一致）。 */
    const val CLOCK_ALPHA_LOW = 0.84f
    const val CLOCK_ALPHA_HIGH = 1.0f
    const val CLOCK_BREATH_LEG_MS = 1996L
    /** 录音开始时时钟先从准备态透明度淡到满，之后才开始呼吸（与对话框的 ANIM_DURATION 一致）。 */
    const val CLOCK_INTRO_MS = 360L
    const val CLOCK_ALPHA_PREPARED = 0.36f

    /**
     * @param cardWidthDp 当前对话框实测宽度（dp）。低于 280 时按 280 兜底。
     */
    fun plan(context: Context, cardWidthDp: Double): FableSolExportPlan {
        val widthDp = cardWidthDp.coerceAtLeast(280.0)
        val scale = CARD_HEIGHT_PX / CARD_HEIGHT_DP
        val cardWidthPx = even((widthDp * scale).roundToInt())
        val paddingPx = even((PADDING_DP * scale).roundToInt())
        val canvasWidthPx = cardWidthPx + 2 * paddingPx
        val canvasHeightPx = CARD_HEIGHT_PX + 2 * paddingPx

        val deviceDensity = context.resources.displayMetrics.density.toDouble()
        val cornerRadiusDp = context.resources.getDimension(
            R.dimen.app_chrome_dialog_popup_corner_radius
        ) / deviceDensity

        val dark = AppearanceUtil.isDarkMode(context)
        return FableSolExportPlan(
            cardWidthPx = cardWidthPx,
            cardHeightPx = CARD_HEIGHT_PX,
            paddingPx = paddingPx,
            canvasWidthPx = canvasWidthPx,
            canvasHeightPx = canvasHeightPx,
            cardOriginXPx = paddingPx,
            cardOriginYPx = paddingPx,
            // 渲染器按「像素尺寸 / density」推容器 dp 宽，所以这里必须交回反推出的 scale，
            // 而不是设备 density——否则物理容器宽度会跟着导出分辨率漂移。
            density = scale,
            cornerRadiusPx = (cornerRadiusDp * scale).toFloat(),
            shadowOffsetPx = (SHADOW_OFFSET_DP * scale).toFloat(),
            shadowRadiusPx = (SHADOW_RADIUS_DP * scale).toFloat(),
            shadowAlpha = if (dark) SHADOW_ALPHA_DARK else SHADOW_ALPHA_LIGHT,
            rimWidthPx = (RIM_WIDTH_DP * scale).toFloat().coerceAtLeast(1f),
            rimColor = if (dark) RIM_COLOR_DARK else RIM_COLOR_LIGHT,
            rimAlpha = if (dark) RIM_ALPHA_DARK else RIM_ALPHA_LIGHT,
            backdropColor = if (dark) BACKDROP_DARK else BACKDROP_LIGHT,
            clockWidthPx = (cardWidthPx - 2 * (CLOCK_SIDE_DP * scale)).roundToInt()
                .coerceAtLeast(1),
            clockHeightPx = (CLOCK_HEIGHT_DP * scale).roundToInt().coerceAtLeast(1),
            clockLeftPx = (CLOCK_SIDE_DP * scale).roundToInt(),
            clockTopPx = (CLOCK_TOP_DP * scale).roundToInt()
        )
    }

    private fun even(value: Int): Int = if (value % 2 == 0) value else value + 1
}

/**
 * 一次导出的全部几何与画框参数，像素单位。卡片相关坐标以**卡片左上角**为原点，
 * 交给 shader 前再换算成 GL 的 y 向上坐标。
 */
internal data class FableSolExportPlan(
    val cardWidthPx: Int,
    val cardHeightPx: Int,
    val paddingPx: Int,
    val canvasWidthPx: Int,
    val canvasHeightPx: Int,
    val cardOriginXPx: Int,
    val cardOriginYPx: Int,
    val density: Double,
    val cornerRadiusPx: Float,
    val shadowOffsetPx: Float,
    val shadowRadiusPx: Float,
    val shadowAlpha: Float,
    val rimWidthPx: Float,
    val rimColor: Int,
    val rimAlpha: Float,
    val backdropColor: Int,
    val clockWidthPx: Int,
    val clockHeightPx: Int,
    val clockLeftPx: Int,
    val clockTopPx: Int
) {
    /**
     * 编码器与分享链路的宽高对齐要求只扩大中性画框，不改变卡片像素尺寸与反推 density，
     * 因此水体物理容器仍严格对应用户看到的 dp 几何。
     */
    fun withCanvasSize(widthPx: Int, heightPx: Int): FableSolExportPlan {
        require(widthPx >= canvasWidthPx && heightPx >= canvasHeightPx)
        return copy(
            canvasWidthPx = widthPx,
            canvasHeightPx = heightPx,
            cardOriginXPx = (widthPx - cardWidthPx) / 2,
            cardOriginYPx = (heightPx - cardHeightPx) / 2
        )
    }
}
