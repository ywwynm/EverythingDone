@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.views

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

import androidx.core.content.ContextCompat

import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil

import java.util.Random

/**
 * Thing Background 编辑器：纯色/渐变两选项卡 +（纯色页：10 预置色 + 1 色区）/
 * （渐变页：8 方向 + 2 色区）。会话内维护「纯色工作态」与「渐变工作态」，切换选项卡
 * 实时预览、不重新随机。对外抛 [onBackgroundChanged]；间距尺寸全在 XML / dimens。
 *
 * 见 docs/features/thing-background-editor/decisions.md。
 */
class ThingBackgroundEditor @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** 当前 Thing Background 变更（用户操作产生）。 */
    var onBackgroundChanged: ((ThingBackground) -> Unit)? = null

    /** 请求从世界取色，slot 见 [SLOT_PURE]/[SLOT_START]/[SLOT_END]；宿主弹相机后调 [applyWorldColor]。 */
    var onRequestPickFromWorld: ((slot: Int) -> Unit)? = null

    private val tabPure: TextView
    private val tabGradient: TextView
    private var titleView: TextView? = null
    private var titleIcon: ImageView? = null
    private var titleIconSource: Drawable? = null
    private val tabHintColor: Int =
        ContextCompat.getColor(context, R.color.app_chrome_on_surface_hint)
    private val pagePure: LinearLayout
    private val pageGradient: LinearLayout
    private val gridPresets: GridLayout
    private val gridDirections: GridLayout
    private val areaPure: ColorAreaView
    private val areaStart: ColorAreaView
    private val areaEnd: ColorAreaView

    private val presetColors: IntArray
    private val presetCells = ArrayList<Cell>()
    private val directionCells = ArrayList<DirCell>()

    private var mode: ThingBackground.Mode = ThingBackground.Mode.PURE
    private var pureColor: Int = Color.GRAY
    private var gradStart: Int = Color.GRAY
    private var gradEnd: Int = Color.LTGRAY
    private var gradOrientation: ThingBackground.Orientation = ThingBackground.Orientation.L_R

    private var isBinding = false
    private val random = Random()
    private val cellMargin = resources.getDimensionPixelSize(R.dimen.tbe_swatch_gap)

    private class Cell(val root: FrameLayout, val bg: View, val check: ImageView, val color: Int)
    private class DirCell(
        val root: FrameLayout, val bg: View, val check: ImageView,
        val orientation: ThingBackground.Orientation
    )

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_thing_background_editor, this, true)

        tabPure = findViewById(R.id.tab_tbe_pure)
        tabGradient = findViewById(R.id.tab_tbe_gradient)
        pagePure = findViewById(R.id.page_tbe_pure)
        pageGradient = findViewById(R.id.page_tbe_gradient)
        gridPresets = findViewById(R.id.grid_tbe_presets)
        gridDirections = findViewById(R.id.grid_tbe_directions)
        areaPure = findViewById(R.id.area_tbe_pure)
        areaStart = findViewById(R.id.area_tbe_start)
        areaEnd = findViewById(R.id.area_tbe_end)

        presetColors = resources.getIntArray(R.array.thing)

        BackgroundUtil.installAppChromePillRipple(tabPure, context)
        BackgroundUtil.installAppChromePillRipple(tabGradient, context)
        tabPure.setOnClickListener {
            if (mode !== ThingBackground.Mode.PURE) {
                showMode(ThingBackground.Mode.PURE)
                emit()
            }
        }
        tabGradient.setOnClickListener {
            if (mode !== ThingBackground.Mode.GRADIENT) {
                showMode(ThingBackground.Mode.GRADIENT)
                emit()
            }
        }

        buildPresetGrid()
        buildDirectionGrid()

        areaPure.onColorChanged = { c -> pureColor = c; refreshPresetSelection(); refreshChrome(); if (!isBinding) emit() }
        areaStart.onColorChanged = { c -> gradStart = c; refreshDirections(); refreshChrome(); if (!isBinding) emit() }
        areaEnd.onColorChanged = { c -> gradEnd = c; refreshDirections(); refreshChrome(); if (!isBinding) emit() }

        areaPure.onRequestPickFromWorld = { onRequestPickFromWorld?.invoke(SLOT_PURE) }
        areaStart.onRequestPickFromWorld = { onRequestPickFromWorld?.invoke(SLOT_START) }
        areaEnd.onRequestPickFromWorld = { onRequestPickFromWorld?.invoke(SLOT_END) }
    }

    /** 用 Thing 当前背景初始化编辑器（不触发 onBackgroundChanged）。 */
    fun setBackground(background: ThingBackground?) {
        isBinding = true
        val bg = background ?: ThingBackground.pure(pureColor)
        if (bg.mode === ThingBackground.Mode.PURE) {
            mode = ThingBackground.Mode.PURE
            pureColor = bg.color
            gradStart = bg.color
            gradEnd = randomColor()
            gradOrientation = ThingBackground.Orientation.L_R
        } else {
            mode = ThingBackground.Mode.GRADIENT
            gradStart = bg.color
            gradEnd = bg.endColor
            gradOrientation = bg.orientation
            pureColor = bg.color
        }
        showMode(mode)
        isBinding = false
    }

    fun getThingBackground(): ThingBackground = currentBackground()

    /** 由宿主传入"调整颜色"标题，编辑器会让它实时跟随当前颜色着色。 */
    fun setTitleView(tv: TextView?) {
        titleView = tv
        refreshChrome()
    }

    /** 由宿主传入标题区图标（如返回箭头），编辑器会用当前颜色 tint 它。 */
    fun setTitleIcon(iv: ImageView?) {
        titleIcon = iv
        titleIconSource = iv?.drawable?.constantState?.newDrawable()
        refreshChrome()
    }

    /** 从世界取色回流：把取到的颜色应用到对应 slot。 */
    fun applyWorldColor(slot: Int, color: Int) {
        when (slot) {
            SLOT_PURE -> { pureColor = color; areaPure.setColor(color); refreshPresetSelection() }
            SLOT_START -> { gradStart = color; areaStart.setColor(color); refreshDirections() }
            SLOT_END -> { gradEnd = color; areaEnd.setColor(color); refreshDirections() }
        }
        refreshChrome()
        emit()
    }

    private fun currentBackground(): ThingBackground =
        if (mode === ThingBackground.Mode.PURE) ThingBackground.pure(pureColor)
        else ThingBackground.gradient(gradStart, gradEnd, gradOrientation)

    private fun emit() {
        onBackgroundChanged?.invoke(currentBackground())
    }

    private fun showMode(m: ThingBackground.Mode) {
        mode = m
        val pure = m === ThingBackground.Mode.PURE
        pagePure.visibility = if (pure) View.VISIBLE else View.GONE
        pageGradient.visibility = if (pure) View.GONE else View.VISIBLE
        if (pure) {
            areaPure.setColor(pureColor)
            refreshPresetSelection()
        } else {
            areaStart.setColor(gradStart)
            areaEnd.setColor(gradEnd)
            refreshDirections()
        }
        refreshChrome()
    }

    /** 选中的 tab 与标题文本用当前颜色着色，未选中 tab 用提示色。 */
    private fun refreshChrome() {
        val bg = currentBackground()
        styleTab(tabPure, mode === ThingBackground.Mode.PURE, bg)
        styleTab(tabGradient, mode === ThingBackground.Mode.GRADIENT, bg)
        titleView?.let { BackgroundUtil.applyTextBackground(it, bg) }
        titleIcon?.let { icon ->
            val base = titleIconSource
            if (base != null) {
                val fresh = base.constantState?.newDrawable()?.mutate() ?: base
                icon.setImageDrawable(BackgroundUtil.tintDrawable(resources, fresh, bg))
            }
        }
    }

    private fun styleTab(tab: TextView, selected: Boolean, bg: ThingBackground) {
        if (selected) {
            tab.setTypeface(Typeface.DEFAULT_BOLD)
            BackgroundUtil.applyTextBackground(tab, bg)
        } else {
            tab.setTypeface(Typeface.DEFAULT)
            BackgroundUtil.applyTextBackground(tab, ThingBackground.pure(tabHintColor))
        }
    }

    // ---- 预置色 ----

    private fun buildPresetGrid() {
        val inflater = LayoutInflater.from(context)
        for (i in presetColors.indices) {
            val color = presetColors[i] or -0x1000000
            val cellView = inflater.inflate(R.layout.color_picker_fab, gridPresets, false) as FrameLayout
            val glp = cellView.layoutParams as GridLayout.LayoutParams
            glp.rowSpec = GridLayout.spec(i / 5)
            glp.columnSpec = GridLayout.spec(i % 5)
            glp.setMargins(cellMargin, cellMargin, cellMargin, cellMargin)
            cellView.layoutParams = glp

            val bg: View = cellView.findViewById(R.id.v_color_cell_bg)
            val check: ImageView = cellView.findViewById(R.id.iv_color_cell_check)
            installCircle(cellView)
            bg.setBackgroundColor(color)
            cellView.setOnClickListener { selectPreset(color) }

            gridPresets.addView(cellView)
            presetCells.add(Cell(cellView, bg, check, color))
        }
    }

    private fun selectPreset(color: Int) {
        pureColor = color
        areaPure.setColor(color)
        refreshPresetSelection()
        refreshChrome()
        emit()
    }

    private fun refreshPresetSelection() {
        for (cell in presetCells) {
            if (cell.color == (pureColor or -0x1000000)) {
                cell.check.visibility = View.VISIBLE
                cell.check.setImageDrawable(tintedCheckmark(cell.color))
            } else {
                cell.check.visibility = View.GONE
                cell.check.setImageDrawable(null)
            }
        }
    }

    // ---- 渐变方向 ----

    private fun buildDirectionGrid() {
        val inflater = LayoutInflater.from(context)
        for (i in DIRECTION_ORDER.indices) {
            val o = DIRECTION_ORDER[i]
            val cellView = inflater.inflate(R.layout.color_picker_fab, gridDirections, false) as FrameLayout
            val glp = cellView.layoutParams as GridLayout.LayoutParams
            glp.rowSpec = GridLayout.spec(i / 4)
            glp.columnSpec = GridLayout.spec(i % 4)
            glp.setMargins(cellMargin, cellMargin, cellMargin, cellMargin)
            cellView.layoutParams = glp

            val bg: View = cellView.findViewById(R.id.v_color_cell_bg)
            val check: ImageView = cellView.findViewById(R.id.iv_color_cell_check)
            installCircle(cellView)
            cellView.setOnClickListener { selectDirection(o) }

            gridDirections.addView(cellView)
            directionCells.add(DirCell(cellView, bg, check, o))
        }
    }

    private fun selectDirection(o: ThingBackground.Orientation) {
        gradOrientation = o
        refreshDirections()
        refreshChrome()
        emit()
    }

    private fun refreshDirections() {
        val start = gradStart or -0x1000000
        val end = gradEnd or -0x1000000
        for (cell in directionCells) {
            val gd = GradientDrawable(toGdOrientation(cell.orientation), intArrayOf(start, end))
            gd.shape = GradientDrawable.RECTANGLE
            cell.bg.background = gd
            if (cell.orientation === gradOrientation) {
                cell.check.visibility = View.VISIBLE
                val rep = ThingBackground.gradient(start, end, cell.orientation).representativeColor()
                cell.check.setImageDrawable(tintedCheckmark(rep))
            } else {
                cell.check.visibility = View.GONE
                cell.check.setImageDrawable(null)
            }
        }
    }

    // ---- 公共绘制辅助 ----

    private fun installCircle(cell: FrameLayout) {
        cell.clipToOutline = true
        cell.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setOval(0, 0, v.width, v.height)
            }
        }
        cell.foreground = BackgroundUtil.circularRipple()
    }

    /** ic_color_picked 在浅色上 tint 黑、深色上 tint 白。refColor 为参考底色。 */
    private fun tintedCheckmark(refColor: Int): Drawable? {
        val raw = ContextCompat.getDrawable(context, R.drawable.ic_color_picked) ?: return null
        val d = raw.mutate()
        val tint = if (BackgroundUtil.isLight(refColor)) Color.BLACK else Color.WHITE
        d.setColorFilter(tint, PorterDuff.Mode.SRC_IN)
        return d
    }

    private fun randomColor(): Int =
        Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))

    companion object {
        const val SLOT_PURE = 0
        const val SLOT_START = 1
        const val SLOT_END = 2

        /** 行 1：4 个斜向（更好看，放第一排）；行 2：4 个正向（上下左右）。 */
        private val DIRECTION_ORDER = arrayOf(
            ThingBackground.Orientation.LT_RB,
            ThingBackground.Orientation.RT_LB,
            ThingBackground.Orientation.LB_RT,
            ThingBackground.Orientation.RB_LT,
            ThingBackground.Orientation.L_R,
            ThingBackground.Orientation.T_B,
            ThingBackground.Orientation.R_L,
            ThingBackground.Orientation.B_T
        )

        private fun toGdOrientation(o: ThingBackground.Orientation): GradientDrawable.Orientation =
            when (o) {
                ThingBackground.Orientation.L_R -> GradientDrawable.Orientation.LEFT_RIGHT
                ThingBackground.Orientation.T_B -> GradientDrawable.Orientation.TOP_BOTTOM
                ThingBackground.Orientation.LT_RB -> GradientDrawable.Orientation.TL_BR
                ThingBackground.Orientation.RT_LB -> GradientDrawable.Orientation.TR_BL
                ThingBackground.Orientation.LB_RT -> GradientDrawable.Orientation.BL_TR
                ThingBackground.Orientation.RB_LT -> GradientDrawable.Orientation.BR_TL
                ThingBackground.Orientation.R_L -> GradientDrawable.Orientation.RIGHT_LEFT
                ThingBackground.Orientation.B_T -> GradientDrawable.Orientation.BOTTOM_TOP
            }
    }
}
