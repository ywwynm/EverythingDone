package com.ywwynm.everythingdone.fragments

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

import com.github.adnansm.timelytextview.TimelyView
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R

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
        (resources.displayMetrics.widthPixels * 0.92f).toInt()

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
        val rows = f<LinearLayout>(R.id.ll_ddc_rows)
        val density = resources.displayMetrics.density
        val dark = Color.parseColor("#20242B")
        val accent = 0x33448AFF

        fun buildRows() {
            rows.removeAllViews()
            val wPx = (resources.displayMetrics.widthPixels * 0.60f).toInt()
            val hPx = (44 * density).toInt()
            for ((id, label) in STYLES) {
                val row = LinearLayout(ctx)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (52 * density).toInt()
                )
                row.setPadding(
                    (12 * density).toInt(), (4 * density).toInt(),
                    (12 * density).toInt(), (4 * density).toInt()
                )
                if (id == selected) row.setBackgroundColor(accent)
                row.isClickable = true

                val tv = TextView(ctx)
                tv.text = label
                tv.textSize = 13f
                tv.gravity = Gravity.CENTER_VERTICAL
                tv.layoutParams = LinearLayout.LayoutParams(
                    (96 * density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT
                )
                row.addView(tv)

                val iv = ImageView(ctx)
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                lp.setMargins(0, (4 * density).toInt(), 0, (4 * density).toInt())
                iv.layoutParams = lp
                iv.setBackgroundColor(dark)
                iv.scaleType = ImageView.ScaleType.FIT_CENTER
                iv.setImageBitmap(TimelyView.renderClock(ctx, id, fill, Color.WHITE, wPx, hPx))
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
        }

        fun updateToggle() {
            tvFill.setBackgroundColor(if (fill) accent else Color.TRANSPARENT)
            tvOutline.setBackgroundColor(if (!fill) accent else Color.TRANSPARENT)
        }

        tvFill.setOnClickListener {
            if (!fill) { fill = true; updateToggle(); buildRows() }
        }
        tvOutline.setOnClickListener {
            if (fill) { fill = false; updateToggle(); buildRows() }
        }

        updateToggle()
        buildRows()
        return v
    }

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
