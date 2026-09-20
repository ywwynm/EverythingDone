package com.ywwynm.everythingdone.activities

import android.view.View
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

import androidx.appcompat.widget.Toolbar

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.helpers.ThingCardContentLimitsHelper
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.EdgeEffectUtil

import kotlin.math.roundToInt

/**
 * 记事卡片外观设置：内容文本最大显示行数、清单项最大显示个数，各按卡片宽度分「正常 /
 * 宽」两档，共四个全局默认值。单条记事可在首页 contextual 面板里覆盖这里的取值。
 *
 * 这四个值只作用于首页列表与单一记事 widget 配置页；DoingActivity、提醒全屏页、文件夹
 * 预览小卡片、桌面小组件都不经过它们。见
 * docs/features/thing-card-content-limits/plan.md。
 */
class ThingCardAppearanceSettingsActivity : EverythingDoneBaseActivity() {

    private var toolbar: Toolbar? = null

    private var tvContentLinesNormalValue: TextView? = null
    private var tvContentLinesFullValue: TextView? = null
    private var tvChecklistItemsNormalValue: TextView? = null
    private var tvChecklistItemsFullValue: TextView? = null

    private var sbContentLinesNormal: SeekBar? = null
    private var sbContentLinesFull: SeekBar? = null
    private var sbChecklistItemsNormal: SeekBar? = null
    private var sbChecklistItemsFull: SeekBar? = null

    override fun getLayoutResource(): Int = R.layout.activity_thing_card_appearance_settings

    override fun initMembers() = Unit

    override fun findViews() {
        toolbar = f(R.id.actionbar)
        tvContentLinesNormalValue = f(R.id.tv_content_lines_normal_value)
        tvContentLinesFullValue = f(R.id.tv_content_lines_full_value)
        tvChecklistItemsNormalValue = f(R.id.tv_checklist_items_normal_value)
        tvChecklistItemsFullValue = f(R.id.tv_checklist_items_full_value)
        sbContentLinesNormal = f(R.id.sb_content_lines_normal)
        sbContentLinesFull = f(R.id.sb_content_lines_full)
        sbChecklistItemsNormal = f(R.id.sb_checklist_items_normal)
        sbChecklistItemsFull = f(R.id.sb_checklist_items_full)
    }

    override fun initUI() {
        DisplayUtil.expandLayoutToStatusBarAboveLollipop(this)
        DisplayUtil.expandStatusBarViewAboveKitkat(f(R.id.view_status_bar))
        DisplayUtil.darkStatusBar(this)

        val accent = App.defaultAccentBackground
        BackgroundUtil.applyBackground(f<View>(R.id.view_status_bar), accent)
        BackgroundUtil.applyBackground(toolbar, accent)

        val scrollView: ScrollView = f(R.id.sv_thing_card_appearance_settings)!!
        EdgeEffectUtil.forScrollView(scrollView, accent.color)
        DisplayUtil.applyBottomInsetAsScrollPadding(scrollView)

        listOf(
            f<TextView>(R.id.tv_title_group_content_lines),
            f<TextView>(R.id.tv_title_group_checklist_items)
        ).forEach { BackgroundUtil.applyTextBackground(it, accent) }

        bindLimitRow(
            sbContentLinesNormal, tvContentLinesNormalValue,
            ThingCardContentLimitsHelper.globalContentMaxLines(false),
            R.string.thing_card_appearance_settings_span_normal
        ) { ThingCardContentLimitsHelper.setGlobalContentMaxLines(this, false, it) }

        bindLimitRow(
            sbContentLinesFull, tvContentLinesFullValue,
            ThingCardContentLimitsHelper.globalContentMaxLines(true),
            R.string.thing_card_appearance_settings_span_full
        ) { ThingCardContentLimitsHelper.setGlobalContentMaxLines(this, true, it) }

        bindLimitRow(
            sbChecklistItemsNormal, tvChecklistItemsNormalValue,
            ThingCardContentLimitsHelper.globalChecklistMaxItems(false),
            R.string.thing_card_appearance_settings_span_normal
        ) { ThingCardContentLimitsHelper.setGlobalChecklistMaxItems(this, false, it) }

        bindLimitRow(
            sbChecklistItemsFull, tvChecklistItemsFullValue,
            ThingCardContentLimitsHelper.globalChecklistMaxItems(true),
            R.string.thing_card_appearance_settings_span_full
        ) { ThingCardContentLimitsHelper.setGlobalChecklistMaxItems(this, true, it) }
    }

    /**
     * 滑块进度与取值差一个 MIN_LIMIT 的偏移。值改变时立刻落盘，并置
     * [App.setJustNotifyAll]——首页的 onResume 会消费它并重绑整张列表，否则改完返回
     * 首页要等卡片被回收重绑才看得到。
     */
    private fun bindLimitRow(
        seekBar: SeekBar?,
        valueView: TextView?,
        currentValue: Int,
        labelRes: Int,
        onValueChanged: (Int) -> Unit
    ) {
        if (seekBar == null || valueView == null) return
        val min = ThingCardContentLimitsHelper.MIN_LIMIT
        seekBar.max = ThingCardContentLimitsHelper.MAX_LIMIT - min
        seekBar.progress = currentValue - min
        seekBar.contentDescription = getString(labelRes)
        valueView.text = currentValue.toString()
        DisplayUtil.setSeekBarBackground(seekBar, App.defaultAccentBackground)
        alignSliderToTextEdges(seekBar)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = min + progress
                valueView.text = value.toString()
                if (!fromUser) return
                onValueChanged(value)
                App.setJustNotifyAll(true)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit

            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })
    }

    /**
     * 让滑动条两端与同一张卡片里其它文本的左右缘对齐。与 `ParticleTuningActivity`
     * 的 `alignSliderToTextEdges` 是同一套：
     *
     * ```
     * 轨道内缩 = 内边距 - 行内边距          滑块外探 = 滑块半径 - 轨道内缩
     * ```
     *
     * 两者之和恒等于滑块半径，只能分配。只留行内边距（16dp）时轨道严丝合缝、滑块整个
     * 半径探出去；把半径整个补进内边距则反过来。滑块只在两端才顶到边、轨道却一直在，
     * 所以取中点，各让 [SLIDER_EDGE_BALANCE] 份。
     */
    private fun alignSliderToTextEdges(bar: SeekBar) {
        val radius = (bar.thumb?.intrinsicWidth ?: 0) / 2
        if (radius <= 0) return
        val inset = (radius * SLIDER_EDGE_BALANCE).roundToInt()
        val side = (ROW_SIDE_PADDING_DP * resources.displayMetrics.density).roundToInt() + inset
        if (bar.paddingStart == side && bar.paddingEnd == side) return
        bar.setPaddingRelative(side, bar.paddingTop, side, bar.paddingBottom)
    }

    // 四个滑块的监听在 initUI 的 bindLimitRow 里一并装好，这里没有别的事件。
    override fun setEvents() = Unit

    override fun setActionbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_thing_card_appearance)
        toolbar?.setNavigationOnClickListener { finish() }
        // 顶栏背景为 accent 渐变，标题与返回图标改为偏白，与 SettingsActivity 一致。
        val fg = BackgroundUtil.onColor(App.defaultAccentBackground, BackgroundUtil.ON_ALPHA_PRIMARY)
        toolbar?.setTitleTextColor(fg)
        toolbar?.navigationIcon?.let {
            toolbar?.navigationIcon = DisplayUtil.opaqueTintDrawable(this, it, fg)
        }
    }

    companion object {
        /** 行左右内边距，与本页所有 56dp 行的 `paddingLeft/Right` 一致。 */
        private const val ROW_SIDE_PADDING_DP = 16f

        /**
         * 滑动条两端「轨道内缩」占滑块半径的比例，余下的那一份是「滑块外探」。取值与
         * `ParticleTuningActivity.SLIDER_EDGE_BALANCE` 保持一致。
         */
        private const val SLIDER_EDGE_BALANCE = 0.5f
    }
}
