package com.ywwynm.everythingdone.fragments

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

import androidx.core.content.ContextCompat
import com.github.adnansm.timelytextview.TimelyView
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.views.GradientRippleDrawable
import kotlin.math.roundToInt

/**
 * Countdown digit style chooser: one row per style previewing "01:29:36" (so the
 * hour/minute/second weight ladder shows), plus a Fill/Outline toggle. Persists
 * the choice to the app settings preferences; DoingActivity reads it on open.
 */
class DoingDigitStyleDialogFragment : BaseDialogFragment() {

    private var fill = true
    private var selected = "poppins"
    private var onChosen: (() -> Unit)? = null

    fun setOnChosen(cb: () -> Unit) {
        onChosen = cb
    }

    override fun getLayoutResource(): Int = R.layout.dialog_doing_digit_style

    override fun getDialogWindowWidthPx(): Int =
        dp(280f)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val v = super.onCreateView(inflater, container, savedInstanceState)
        val ctx = v!!.context
        val sp = ctx.getSharedPreferences(Def.Meta.PREFERENCES_NAME, Context.MODE_PRIVATE)
        selected = sp.getString(Def.Meta.KEY_DOING_DIGIT_STYLE, "poppins") ?: "poppins"
        fill = (sp.getString(Def.Meta.KEY_DOING_DIGIT_RENDER, "fill") ?: "fill") == "fill"

        val tvFill = f<TextView>(R.id.tv_ddc_fill)
        val tvOutline = f<TextView>(R.id.tv_ddc_outline)
        val tvTitle = f<TextView>(R.id.tv_ddc_title)
        val scroll = f<ScrollView>(R.id.sv_ddc_rows)
        val rows = f<LinearLayout>(R.id.ll_ddc_rows)
        val scrollIndicator = f<View>(R.id.view_ddc_scroll_indicator)
        val accentBackground = App.defaultAccentBackground
        val tabHintColor = ContextCompat.getColor(ctx, R.color.app_chrome_on_surface_hint)
        val selectedLabelColor = BackgroundUtil.onColor(
            accentBackground, BackgroundUtil.ON_ALPHA_TERTIARY
        )
        val rowRadius = dp(8f).toFloat()
        BackgroundUtil.applyTextBackground(tvTitle, accentBackground)

        fun updateScrollIndicator() {
            scrollIndicator.visibility =
                if (scroll.canScrollVertically(-1)) View.VISIBLE else View.INVISIBLE
        }

        fun scrollToSelectedRow() {
            scroll.post {
                val index = STYLES.indexOfFirst { it.first == selected }
                if (index >= 0) {
                    val child = rows.getChildAt(index)
                    if (child != null) {
                        scroll.scrollTo(0, (child.top - dp(8f)).coerceAtLeast(0))
                    }
                }
                updateScrollIndicator()
            }
        }

        fun buildRows() {
            rows.removeAllViews()
            val wPx = (getDialogWindowWidthPx() - dp(64f)).coerceAtLeast(dp(220f))
            val hPx = dp(52f)
            for ((id, label) in STYLES) {
                val isSelected = id == selected
                val row = LinearLayout(ctx)
                row.orientation = LinearLayout.VERTICAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(92f)
                ).apply {
                    setMargins(dp(12f), dp(4f), dp(12f), dp(4f))
                }
                row.setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
                row.background = if (isSelected) selectedRowBackground(accentBackground, rowRadius) else null
                row.foreground = if (isSelected) null else GradientRippleDrawable(
                    accentBackground, shapeOval = false, cornerRadiusPx = rowRadius
                )
                row.isClickable = true
                row.isFocusable = true

                val tv = TextView(ctx)
                tv.text = label
                tv.textSize = 13f
                tv.includeFontPadding = false
                tv.gravity = Gravity.CENTER_VERTICAL
                tv.maxLines = 1
                tv.ellipsize = TextUtils.TruncateAt.END
                tv.setTextColor(if (isSelected) selectedLabelColor else tabHintColor)
                tv.setTypeface(Typeface.DEFAULT, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                tv.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(20f)
                )
                row.addView(tv)

                val iv = ImageView(ctx)
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52f))
                lp.setMargins(0, dp(4f), 0, 0)
                iv.layoutParams = lp
                iv.scaleType = ImageView.ScaleType.FIT_CENTER
                iv.setImageBitmap(
                    if (isSelected) {
                        TimelyView.renderClock(ctx, id, fill, Color.WHITE, wPx, hPx)
                    } else {
                        TimelyView.renderClock(
                            ctx, id, fill,
                            accentBackground.color, accentBackground.endColor, wPx, hPx
                        )
                    }
                )
                row.addView(iv)

                row.setOnClickListener {
                    sp.edit()
                        .putString(Def.Meta.KEY_DOING_DIGIT_STYLE, id)
                        .putString(Def.Meta.KEY_DOING_DIGIT_RENDER, if (fill) "fill" else "outline")
                        .apply()
                    onChosen?.invoke()
                    dismiss()
                }
                rows.addView(row)
            }
            scrollToSelectedRow()
        }

        fun updateToggle() {
            styleTab(tvFill, fill, accentBackground, tabHintColor)
            styleTab(tvOutline, !fill, accentBackground, tabHintColor)
        }

        tvFill.setOnClickListener {
            if (!fill) { fill = true; updateToggle(); buildRows() }
        }
        tvOutline.setOnClickListener {
            if (fill) { fill = false; updateToggle(); buildRows() }
        }

        updateToggle()
        scroll.viewTreeObserver.addOnScrollChangedListener { updateScrollIndicator() }
        buildRows()
        return v
    }

    private fun styleTab(
        tab: TextView,
        selected: Boolean,
        accentBackground: ThingBackground,
        hintColor: Int
    ) {
        tab.foreground = GradientRippleDrawable(
            accentBackground, shapeOval = false, cornerRadiusPx = -1f
        )
        if (selected) {
            tab.setTypeface(Typeface.DEFAULT_BOLD)
            BackgroundUtil.applyTextBackground(tab, accentBackground)
        } else {
            tab.setTypeface(Typeface.DEFAULT)
            BackgroundUtil.applyTextBackground(tab, ThingBackground.pure(hintColor))
        }
    }

    private fun selectedRowBackground(bg: ThingBackground, radiusPx: Float): GradientDrawable {
        return BackgroundUtil.makeTranslucentGradient(bg, 255).apply {
            cornerRadius = radiusPx
        }
    }

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density).roundToInt()

    companion object {
        const val TAG = "DoingDigitStyleDialogFragment"

        /** id (asset name) -> display label. */
        val STYLES = listOf(
            "poppins" to "Poppins",
            "comfortaa" to "Comfortaa",
            "orbitron" to "Orbitron",
            "playfairdisplay" to "Playfair Display",
            "abrilfatface" to "Abril Fatface",
            "zillaslab" to "Zilla Slab",
            "lora" to "Lora",
            "dmserifdisplay" to "DM Serif Display",
            "jetbrainsmono" to "JetBrains Mono",
            "pacifico" to "Pacifico",
            "dancingscript" to "Dancing Script"
        )
    }
}
