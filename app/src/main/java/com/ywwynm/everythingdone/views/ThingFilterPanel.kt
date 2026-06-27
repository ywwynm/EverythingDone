@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.views

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.model.ThingWidgetInfo
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.DisplayUtil

/**
 * Reusable filter region used both by the home Drawer and by the Things-list
 * AppWidget configuration. It stacks the two orthogonal content filters:
 *
 * - Top row: 记事状态 (status) as a single segmented capsule (see
 *   [ThingStatusSegmentedView]): 正在进行 / 已完成 / 回收站.
 * - Bottom row: 记事类型 (type), five circular icon buttons: 全部类型 / 记录 /
 *   提醒 / 习惯 / 目标. 全部类型 is exclusive; the four concrete types are
 *   multi-select and the mask returns to 全部类型 automatically when none stay
 *   selected.
 *
 * Selected segments/circles use a fill derived from the current Thing Scope — the
 * accent → accent2 gradient at the "全部记事" root, or the folder's own colour /
 * gradient inside a folder — with icon and text colours adapting to the fill.
 * Every control shows a ripple on touch.
 */
class ThingFilterPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** Invoked when the user taps a status segment. */
    var onStatusChange: ((Int) -> Unit)? = null

    /** Invoked when the user changes the type filter mask. */
    var onTypeFilterChange: ((Int) -> Unit)? = null

    private var scopeBackground: ThingBackground = defaultScopeBackground()
    private var scopeIsRoot: Boolean = true

    private var currentMask: Int = ThingWidgetInfo.TYPE_FILTER_ALL

    private val statusView = ThingStatusSegmentedView(context)

    private class TypeButton(
        val button: ImageView,
        @param:DrawableRes val iconRes: Int
    )

    private val typeButtons = LinkedHashMap<Int, TypeButton>()

    init {
        orientation = VERTICAL
        setPadding(dp(16f), dp(12f), dp(16f), dp(12f))

        statusView.onStatusChange = { status -> onStatusChange?.invoke(status) }
        addView(statusView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(
            Space(context),
            LayoutParams(LayoutParams.MATCH_PARENT, dp(ROW_GAP_DP))
        )

        // Type row uses space-between so the first/last circles sit flush against
        // the panel content edges, lining their outer edges up with the status
        // capsule edges above.
        val typeRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val typeSpecs = listOf(
            ThingWidgetInfo.TYPE_FILTER_ALL to R.drawable.drawer_all,
            ThingWidgetInfo.TYPE_FILTER_NOTE to R.drawable.drawer_note,
            ThingWidgetInfo.TYPE_FILTER_REMINDER to R.drawable.drawer_reminder,
            ThingWidgetInfo.TYPE_FILTER_HABIT to R.drawable.drawer_habit,
            ThingWidgetInfo.TYPE_FILTER_GOAL to R.drawable.drawer_goal
        )
        typeSpecs.forEachIndexed { index, (maskKey, iconRes) ->
            if (index > 0) {
                typeRow.addView(Space(context), LayoutParams(0, 1, 1f))
            }
            typeRow.addView(
                createTypeButton(maskKey, iconRes),
                LayoutParams(dp(CIRCLE_TOUCH_DP), dp(CIRCLE_TOUCH_DP))
            )
        }
        addView(typeRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        refreshTypeVisuals()
    }

    /** Sets the Scope-derived fill used by selected segments and circles. */
    fun setScopeBackground(background: ThingBackground?) {
        scopeIsRoot = background == null
        scopeBackground = background ?: defaultScopeBackground()
        statusView.setScopeBackground(background)
        refreshTypeVisuals()
    }

    /** Updates the displayed selection without firing change callbacks. */
    fun setSelection(status: Int, typeFilterMask: Int) {
        statusView.setStatus(status)
        currentMask = ThingWidgetInfo.normalizedTypeFilterMask(typeFilterMask)
        refreshTypeVisuals()
    }

    fun getStatus(): Int = statusView.getStatus()

    fun getTypeFilterMask(): Int = currentMask

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        for ((_, typeButton) in typeButtons) {
            val bg = typeButton.button.background
            if (bg is GradientRippleDrawable) bg.stopAnimations()
        }
    }

    private fun onTypeTapped(maskKey: Int) {
        currentMask = if (maskKey == ThingWidgetInfo.TYPE_FILTER_ALL) {
            ThingWidgetInfo.TYPE_FILTER_ALL
        } else if (ThingWidgetInfo.isAllTypeFilter(currentMask)) {
            maskKey
        } else {
            ThingWidgetInfo.normalizedTypeFilterMask(currentMask xor maskKey)
        }
        refreshTypeVisuals()
        onTypeFilterChange?.invoke(currentMask)
    }

    private fun refreshTypeVisuals() {
        val allSelected = ThingWidgetInfo.isAllTypeFilter(currentMask)
        for ((maskKey, typeButton) in typeButtons) {
            val selected = if (maskKey == ThingWidgetInfo.TYPE_FILTER_ALL) {
                allSelected
            } else {
                !allSelected && currentMask and maskKey != 0
            }
            applyTypeButtonVisual(typeButton, selected)
        }
    }

    private fun createTypeButton(maskKey: Int, @DrawableRes iconRes: Int): View {
        val button = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER
            val padding = dp(CIRCLE_PADDING_DP)
            setPadding(padding, padding, padding, padding)
            isClickable = true
            isFocusable = true
            setOnClickListener { onTypeTapped(maskKey) }
        }
        typeButtons[maskKey] = TypeButton(button, iconRes)
        return button
    }

    private fun applyTypeButtonVisual(typeButton: TypeButton, selected: Boolean) {
        val foreground = typeForeground(selected)
        typeButton.button.background = circleBackground(selected)
        val drawable = AppCompatResources.getDrawable(context, typeButton.iconRes)
        if (drawable != null) {
            typeButton.button.setImageDrawable(
                DisplayUtil.opaqueTintDrawable(context, drawable, foreground)
            )
        }
    }

    private fun typeForeground(selected: Boolean): Int {
        if (!selected) return unselectedForeground()
        if (scopeIsRoot) return SELECTED_FG_LIGHT
        return if (BackgroundUtil.isLight(scopeBackground)) {
            SELECTED_FG_DARK
        } else {
            SELECTED_FG_LIGHT
        }
    }

    private fun circleBackground(selected: Boolean): Drawable {
        if (!selected) {
            // Unselected: a gradient ripple that only surfaces on touch (transparent at rest),
            // tinted by the current Scope (root = accent gradient, folder = folder colour).
            return GradientRippleDrawable(scopeBackground, shapeOval = true)
        }
        val content = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (scopeBackground.mode == ThingBackground.Mode.GRADIENT) {
                orientation = mapGradientOrientation(scopeBackground.orientation)
                colors = intArrayOf(scopeBackground.color, scopeBackground.endColor)
            } else {
                setColor(scopeBackground.color)
            }
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
        return RippleDrawable(
            ColorStateList.valueOf(ContextCompat.getColor(context, R.color.app_chrome_ripple)),
            content,
            mask
        )
    }

    private fun unselectedForeground(): Int {
        return ContextCompat.getColor(context, R.color.app_chrome_drawer_item_foreground)
    }

    private fun defaultScopeBackground(): ThingBackground {
        return ThingBackground.gradient(
            ContextCompat.getColor(context, R.color.app_accent),
            ContextCompat.getColor(context, R.color.app_accent2),
            ThingBackground.Orientation.L_R
        )
    }

    private fun mapGradientOrientation(
        orientation: ThingBackground.Orientation
    ): GradientDrawable.Orientation {
        return when (orientation) {
            ThingBackground.Orientation.L_R -> GradientDrawable.Orientation.LEFT_RIGHT
            ThingBackground.Orientation.R_L -> GradientDrawable.Orientation.RIGHT_LEFT
            ThingBackground.Orientation.T_B -> GradientDrawable.Orientation.TOP_BOTTOM
            ThingBackground.Orientation.B_T -> GradientDrawable.Orientation.BOTTOM_TOP
            ThingBackground.Orientation.LT_RB -> GradientDrawable.Orientation.TL_BR
            ThingBackground.Orientation.RB_LT -> GradientDrawable.Orientation.BR_TL
            ThingBackground.Orientation.RT_LB -> GradientDrawable.Orientation.TR_BL
            ThingBackground.Orientation.LB_RT -> GradientDrawable.Orientation.BL_TR
        }
    }

    private fun dp(value: Float): Int {
        return (resources.displayMetrics.density * value).toInt()
    }

    companion object {
        private const val ROW_GAP_DP = 14.0f
        private const val CIRCLE_TOUCH_DP = 42.0f
        private const val CIRCLE_PADDING_DP = 10.0f

        private const val SELECTED_FG_DARK = 0xDE000000.toInt()
        private const val SELECTED_FG_LIGHT = 0xF2FFFFFF.toInt()
    }
}
