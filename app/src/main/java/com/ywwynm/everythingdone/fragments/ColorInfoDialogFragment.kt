@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.fragments

import android.graphics.Outline
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

import androidx.core.content.ContextCompat

import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.ColorNameMatcher

import kotlin.math.min

open class ColorInfoDialogFragment : BaseDialogFragment() {

    private var mBackground: ThingBackground? = null

    override fun getLayoutResource(): Int = R.layout.fragment_color_info

    override fun getDialogWindowWidthPx(): Int {
        return (320 * resources.displayMetrics.density).toInt()
    }

    open fun setThingBackground(background: ThingBackground?) {
        mBackground = background
        val args = ensureArguments()
        if (background != null) {
            args.putString(ARG_BACKGROUND, background.toJson())
        } else {
            args.remove(ARG_BACKGROUND)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoreArguments()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)

        val background = mBackground
        if (background == null) {
            dismiss()
            return mContentView
        }

        val title: TextView = f(R.id.tv_title_color_info)!!
        val confirm: TextView = f(R.id.tv_confirm_as_bt_color_info)!!
        val topSeparator: View = f(R.id.view_separator_1)!!
        val bottomSeparator: View = f(R.id.view_separator_2)!!
        val preview: View = f(R.id.v_color_info_preview)!!
        val scroll: ScrollView = f(R.id.sv_color_info)!!
        val list: LinearLayout = f(R.id.ll_color_info_items)!!

        BackgroundUtil.applyTextBackground(title, background)
        BackgroundUtil.applyTextBackground(confirm, background)
        confirm.setOnClickListener { dismiss() }

        installTopPreview(preview, background)
        bindSections(list, background)
        limitScrollHeight(
            scroll,
            topSeparator,
            bottomSeparator,
            background.mode == ThingBackground.Mode.PURE
        )

        return mContentView
    }

    private fun installTopPreview(preview: View, background: ThingBackground) {
        if (background.mode == ThingBackground.Mode.GRADIENT) {
            preview.visibility = View.GONE
            return
        }
        preview.visibility = View.VISIBLE
        installColorPreview(preview, background)
    }

    private fun installColorPreview(preview: View, background: ThingBackground) {
        preview.clipToOutline = true
        preview.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, v.height / 2f)
            }
        }
        BackgroundUtil.applyBackground(preview, background)
    }

    private fun limitScrollHeight(
        scroll: ScrollView,
        topSeparator: View,
        bottomSeparator: View,
        hasTopPreview: Boolean
    ) {
        scroll.post {
            val density = resources.displayMetrics.density
            val maxByReferenceDialog = (400 * density).toInt()
            val maxByScreen = (resources.displayMetrics.heightPixels * 0.52f).toInt()
            val maxHeight = min(maxByReferenceDialog, maxByScreen)
            if (scroll.height > maxHeight) {
                val lp = scroll.layoutParams
                lp.height = maxHeight
                scroll.layoutParams = lp
                scroll.post {
                    installScrollSeparators(scroll, topSeparator, bottomSeparator, hasTopPreview)
                }
            } else {
                installScrollSeparators(scroll, topSeparator, bottomSeparator, hasTopPreview)
            }
        }
    }

    private fun installScrollSeparators(
        scroll: ScrollView,
        topSeparator: View,
        bottomSeparator: View,
        hasTopPreview: Boolean
    ) {
        val canScroll = scroll.canScrollVertically(-1) || scroll.canScrollVertically(1)
        setScrollTopMargin(scroll, if (canScroll && !hasTopPreview) 0 else 12)
        if (!canScroll) {
            topSeparator.visibility = View.GONE
            bottomSeparator.visibility = View.GONE
            return
        }
        scroll.viewTreeObserver.addOnScrollChangedListener {
            updateScrollSeparators(scroll, topSeparator, bottomSeparator)
        }
        updateScrollSeparators(scroll, topSeparator, bottomSeparator)
    }

    private fun setScrollTopMargin(scroll: ScrollView, marginDp: Int) {
        val lp = scroll.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val topMargin = (marginDp * resources.displayMetrics.density).toInt()
        if (lp.topMargin == topMargin) return
        lp.topMargin = topMargin
        scroll.layoutParams = lp
    }

    private fun updateScrollSeparators(
        scroll: ScrollView,
        topSeparator: View,
        bottomSeparator: View
    ) {
        if (!scroll.canScrollVertically(-1)) {
            topSeparator.visibility = View.INVISIBLE
            bottomSeparator.visibility = View.VISIBLE
        } else if (!scroll.canScrollVertically(1)) {
            topSeparator.visibility = View.VISIBLE
            bottomSeparator.visibility = View.INVISIBLE
        } else {
            topSeparator.visibility = View.VISIBLE
            bottomSeparator.visibility = View.VISIBLE
        }
    }

    private fun bindSections(list: LinearLayout, background: ThingBackground) {
        if (background.mode == ThingBackground.Mode.PURE) {
            addSection(
                list,
                null,
                null,
                ColorNameMatcher.describeColor(activity!!, background.color)
            )
        } else {
            addSection(
                list,
                getString(R.string.color_info_gradient_start),
                ThingBackground.pure(background.color),
                ColorNameMatcher.describeColor(activity!!, background.color)
            )
            addSection(
                list,
                getString(R.string.color_info_gradient_end),
                ThingBackground.pure(background.endColor),
                ColorNameMatcher.describeColor(activity!!, background.endColor)
            )
            addSection(
                list,
                getString(R.string.color_info_representative),
                ThingBackground.pure(background.representativeColor()),
                ColorNameMatcher.describeColor(activity!!, background.representativeColor())
            )
        }

    }

    private fun addSection(
        list: LinearLayout,
        title: String?,
        previewBackground: ThingBackground?,
        description: ColorNameMatcher.ColorDescription
    ) {
        if (previewBackground != null) {
            addSectionPreview(list, previewBackground)
        }
        if (title != null) {
            addSectionTitle(list, title)
        }
        val match = description.match
        addRow(list, getString(R.string.color_info_name), match.localizedName)
        if (match.localizedName != match.englishName) {
            addRow(list, getString(R.string.color_info_english_name), match.englishName)
        }
        addRow(list, getString(R.string.color_info_rgb), description.rgb)
        addRow(list, getString(R.string.color_info_hex), description.hex)
        addRow(list, getString(R.string.color_info_hsl), description.hsl)
    }

    private fun addSectionPreview(list: LinearLayout, background: ThingBackground) {
        val preview = View(activity)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (36 * resources.displayMetrics.density).toInt()
        )
        lp.topMargin = if (list.childCount == 0) 0 else (16 * resources.displayMetrics.density).toInt()
        preview.layoutParams = lp
        list.addView(preview)
        installColorPreview(preview, background)
    }

    private fun addSectionTitle(list: LinearLayout, text: String) {
        val tv = TextView(activity)
        tv.text = text
        tv.textSize = 15f
        tv.typeface = android.graphics.Typeface.DEFAULT_BOLD
        tv.setTextColor(ContextCompat.getColor(activity!!, R.color.app_chrome_on_surface_primary))
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = (12 * resources.displayMetrics.density).toInt()
        tv.layoutParams = lp
        list.addView(tv)
    }

    private fun addRow(list: LinearLayout, title: String, content: String) {
        val titleView = TextView(activity)
        titleView.text = title
        titleView.textSize = 12f
        titleView.setTextColor(ContextCompat.getColor(activity!!, R.color.app_chrome_on_surface_secondary))
        val titleLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        titleLp.topMargin = (6 * resources.displayMetrics.density).toInt()
        titleView.layoutParams = titleLp
        list.addView(titleView)

        val contentView = TextView(activity)
        contentView.text = content
        contentView.textSize = 14f
        contentView.setTextColor(ContextCompat.getColor(activity!!, R.color.app_chrome_on_surface_primary))
        list.addView(contentView)
    }

    private fun ensureArguments(): Bundle {
        var args = arguments
        if (args == null) {
            args = Bundle()
            arguments = args
        }
        return args
    }

    private fun restoreArguments() {
        val args = arguments ?: return
        mBackground = ThingBackground.fromJson(args.getString(ARG_BACKGROUND))
    }

    companion object {
        const val TAG: String = "ColorInfoDialogFragment"
        private const val ARG_BACKGROUND = "background"
    }
}
