package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.view.View
import com.github.adnansm.timelytextview.TimelyClockView
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.AppearanceUtil

/**
 * 导出画面里的计时时钟。
 *
 * 用一个**未附着**的 [TimelyClockView] 自绘：它只被导出线程碰，与屏上那只互不影响，
 * 也不占用主线程。形变不走 ValueAnimator，而是 [TimelyClockView.showTimeAtElapsed]
 * 按导出时间解析求值（fablesol-video-export D1），因此同一个时间戳恒得同一画面。
 */
internal class FableSolExportClock(
    context: Context,
    private val plan: FableSolExportPlan,
    accent: ThingBackground
) {

    private val view = TimelyClockView(context)
    private val bitmap: Bitmap = Bitmap.createBitmap(
        plan.clockWidthPx.coerceAtLeast(1),
        plan.clockHeightPx.coerceAtLeast(1),
        Bitmap.Config.ARGB_8888
    )
    private val canvas = Canvas(bitmap)
    private var renderedSecond = -1L
    private var renderedFrozen = false

    init {
        val preferences = context.getSharedPreferences(Def.Meta.PREFERENCES_NAME, 0)
        val digitStyle = preferences.getString(Def.Meta.KEY_DOING_DIGIT_STYLE, DEFAULT_STYLE)
            ?: DEFAULT_STYLE
        val digitFill = (preferences.getString(Def.Meta.KEY_DOING_DIGIT_RENDER, DEFAULT_RENDER)
            ?: DEFAULT_RENDER) == DEFAULT_RENDER
        view.setStyleName(digitStyle)
        view.setRenderMode(digitFill)
        view.setClockMode(TimelyClockView.MODE_FULL)
        view.setColonWidthFactor(COLON_WIDTH_FACTOR)
        // 画框与 chrome 一起跟随 Appearance Mode（D4），时钟的 hostDark 照旧同步。
        view.setHostDark(AppearanceUtil.isDarkMode(context))
        if (accent.mode == ThingBackground.Mode.GRADIENT) {
            view.setInkGradient(accent.color, accent.endColor, timelyOrientation(accent.orientation))
        } else {
            view.setInkColor(accent.color)
        }
        view.measure(
            View.MeasureSpec.makeMeasureSpec(bitmap.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(bitmap.height, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, bitmap.width, bitmap.height)
    }

    /**
     * 取 [elapsedMs] 处的时钟位图。形变结束、且秒数未变时直接复用上一帧——录音每秒只有
     * 300ms 在形变，这一条省掉约七成的 Canvas 重绘。
     */
    fun bitmapAt(elapsedMs: Long): Bitmap {
        val second = elapsedMs / 1000L
        val frozen = elapsedMs - second * 1000L >= MORPH_DURATION_MS
        if (second == renderedSecond && frozen && renderedFrozen) return bitmap
        view.showTimeAtElapsed(elapsedMs)
        canvas.drawColor(0, PorterDuff.Mode.CLEAR)
        view.draw(canvas)
        renderedSecond = second
        renderedFrozen = frozen
        return bitmap
    }

    /**
     * 时钟透明度：先复现录音开始时那 360ms 的淡入（准备态 0.36 → 1.0），之后才进入呼吸。
     * 直接从 t=0 就呼吸的话，开头的透明度和后续相位都跟屏上对不上。
     */
    fun alphaAt(elapsedMs: Long): Float {
        val intro = FableSolExportSpec.CLOCK_INTRO_MS
        if (elapsedMs < intro) {
            val progress = elapsedMs.toFloat() / intro
            val eased = progress * progress * (3f - 2f * progress)
            return FableSolExportSpec.CLOCK_ALPHA_PREPARED +
                (FableSolExportSpec.CLOCK_ALPHA_HIGH -
                    FableSolExportSpec.CLOCK_ALPHA_PREPARED) * eased
        }
        return TimelyClockView.breathingAlphaAtElapsed(
            elapsedMs - intro,
            FableSolExportSpec.CLOCK_ALPHA_LOW,
            FableSolExportSpec.CLOCK_ALPHA_HIGH,
            FableSolExportSpec.CLOCK_BREATH_LEG_MS
        )
    }

    fun release() {
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    private fun timelyOrientation(orientation: ThingBackground.Orientation): Int = when (orientation) {
        ThingBackground.Orientation.L_R -> TimelyClockView.ORIENTATION_L_R
        ThingBackground.Orientation.R_L -> TimelyClockView.ORIENTATION_R_L
        ThingBackground.Orientation.T_B -> TimelyClockView.ORIENTATION_T_B
        ThingBackground.Orientation.B_T -> TimelyClockView.ORIENTATION_B_T
        ThingBackground.Orientation.LT_RB -> TimelyClockView.ORIENTATION_LT_RB
        ThingBackground.Orientation.RB_LT -> TimelyClockView.ORIENTATION_RB_LT
        ThingBackground.Orientation.RT_LB -> TimelyClockView.ORIENTATION_RT_LB
        ThingBackground.Orientation.LB_RT -> TimelyClockView.ORIENTATION_LB_RT
    }

    private companion object {
        const val DEFAULT_STYLE = "poppins"
        const val DEFAULT_RENDER = "fill"
        const val COLON_WIDTH_FACTOR = 0.42f
        /** 与 TimelyClockView.ANIM_DURATION_MS 一致。 */
        const val MORPH_DURATION_MS = 300L
    }
}
