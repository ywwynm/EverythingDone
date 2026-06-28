@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.ywwynm.everythingdone.fragments

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.os.Bundle
import androidx.annotation.StringRes
import com.google.android.material.tabs.TabLayout
import androidx.core.content.ContextCompat
import androidx.viewpager.widget.ViewPager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import android.text.InputFilter
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView

import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.adapters.DateTimePagerAdapter
import com.ywwynm.everythingdone.adapters.RecurrencePickerAdapter
import com.ywwynm.everythingdone.adapters.TimeOfDayRecAdapter
import com.ywwynm.everythingdone.database.HabitDAO
import com.ywwynm.everythingdone.database.ReminderDAO
import com.ywwynm.everythingdone.model.Habit
import com.ywwynm.everythingdone.model.Reminder
import com.ywwynm.everythingdone.model.ReminderHabitParams
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingAction
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.DateTimeUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.EdgeEffectUtil
import com.ywwynm.everythingdone.utils.KeyboardUtil
import com.ywwynm.everythingdone.utils.LocaleUtil
import com.ywwynm.everythingdone.views.GradientRippleDrawable
import com.ywwynm.everythingdone.views.InputLayout
import com.ywwynm.everythingdone.views.pickers.DateTimePicker

import java.time.ZonedDateTime
import java.time.Instant
import java.time.ZoneId

import java.util.ArrayList
import java.util.Calendar
import java.util.HashSet

/**
 * Created by ywwynm on 2015/8/14.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * DialogFragment used to pick date/time/recurrence for a Reminder/Habit/Goal.
 */
@SuppressLint("SetTextI18n")
open class DateTimeDialogFragment : BaseDialogFragment() {

    private var mActivity: DetailActivity? = null

    private var mThing: Thing? = null
    private var mTabInitiated: BooleanArray = BooleanArray(3)

    private var mAccentColor: Int = 0
    /** Phase 8: full ThingBackground for any UI element that can render gradient. */
    private var mAccentBackground: ThingBackground? = null
    private var black_54p: Int = 0
    private var black_26p: Int = 0

    private var confirmed: Boolean = false

    private var mPickedBefore: Int = 0

    // tabs
    private var mTabLayout: TabLayout? = null
    private var mVpDateTime: ViewPager? = null
    private var mTabs: MutableList<View>? = null
    private var mTabHeights: MutableList<Int>? = null
    private var mTabAdapter: DateTimePagerAdapter? = null

    private val mPageChangeListener: ViewPager.SimpleOnPageChangeListener =
        object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                if (!mTabInitiated[position]) {
                    initAll(position)
                }
                KeyboardUtil.hideKeyboard(mVpDateTime)
                improveComplex()
            }

            override fun onPageScrollStateChanged(state: Int) {
                super.onPageScrollStateChanged(state)
                if (state == ViewPager.SCROLL_STATE_IDLE) {
                    val params: ViewGroup.LayoutParams = mVpDateTime!!.layoutParams
                    params.height = mTabHeights!![mVpDateTime!!.currentItem]
                    mVpDateTime!!.postDelayed({
                        mVpDateTime!!.layoutParams = params
                    }, 96)
                }
            }
        }

    private val mTvTimeAsBtClickListener: View.OnClickListener = View.OnClickListener { v ->
        improveComplex()
        if (v.equals(mTvTimeAsBtAfter)) {
            KeyboardUtil.hideKeyboard(mEtTimeAfter)
            mDtpAfter!!.show()
        } else {
            mDtpRec!!.show()
        }
    }

    // at
    private val mTimeTypes: IntArray = intArrayOf(
        Calendar.YEAR, Calendar.MONTH, Calendar.DATE,
        Calendar.HOUR_OF_DAY, Calendar.MINUTE
    )
    private var mTvSummaryAt: TextView? = null
    private var mTvsAt: Array<TextView?>? = null
    private var mEtsAt: Array<EditText?>? = null
    private var mIlsAt: Array<InputLayout?>? = null

    // after
    private var mEtTimeAfter: EditText? = null
    private var mTvTimeAsBtAfter: TextView? = null
    private var mDtpAfter: DateTimePicker? = null
    private var mTvErrorAfter: TextView? = null

    // recurrence
    private var mTvTimesLRec: TextView? = null
    private var mTvTimesRRec: TextView? = null
    private var mTvTimeAsBtRec: TextView? = null
    private var mTvSummaryRec: TextView? = null
    private var mDtpRec: DateTimePicker? = null
    private var mIvPickAllAsBtRec: ImageView? = null

    private var mRvTimeOfDay: RecyclerView? = null
    private var mAdapterTimeOfDay: TimeOfDayRecAdapter? = null
    private var mLlmTimeOfDay: LinearLayoutManager? = null

    private var mRlWmy: RelativeLayout? = null // wmy -> Week Month Year
    private var mFlDayYear: FrameLayout? = null
    private var mIlDayYear: InputLayout? = null
    private var mIlHourWmy: InputLayout? = null
    private var mIlMinuteWmy: InputLayout? = null
    private var mRvWmy: RecyclerView? = null
    private var mGlmDayOfWeek: GridLayoutManager? = null
    private var mGlmDayOfMonth: GridLayoutManager? = null
    private var mGlmMonthOfYear: GridLayoutManager? = null
    private var mAdapterDayOfWeek: RecurrencePickerAdapter? = null
    private var mAdapterDayOfMonth: RecurrencePickerAdapter? = null
    private var mAdapterMonthOfYear: RecurrencePickerAdapter? = null

    // footer
    private var mTvConfirmAsBt: TextView? = null
    private var mTvCancelAsBt: TextView? = null

    open fun setPickedBefore(pickedBefore: Int) {
        mPickedBefore = pickedBefore
    }

    internal inner class InitiallyShowPageRunnable(var page: Int) : Runnable {
        override fun run() {
            mVpDateTime!!.currentItem = page
            updateViewPagerHeight()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)

        initMembers()
        findViews()
        initUI()
        setEvents()

        val args: Bundle = arguments!!
        mThing = args.getParcelable(Def.Communication.KEY_THING)
        val thingType = mThing?.type ?: Thing.NOTE
        if (mActivity!!.rhParams.habitDetail != null ||
            Thing.isTypeHabit(thingType)
        ) {
            mVpDateTime!!.post(InitiallyShowPageRunnable(2))
            initAll(2)
        } else {
            var to = 0
            if (Thing.isTypeGoal(thingType)
                && mActivity!!.type == DetailActivity.CREATE
            ) {
                to = 1
            }
            mVpDateTime!!.post(InitiallyShowPageRunnable(to))
            initAll(to)
        }

        return mContentView
    }

    override fun getLayoutResource(): Int = R.layout.fragment_date_time

    override fun getDialogWindowWidthPx(): Int =
        resources.getDimensionPixelSize(R.dimen.dialog_width_date_time)

    private fun updateViewPagerHeight() {
        val params: ViewGroup.LayoutParams = mVpDateTime!!.layoutParams
        params.height = mTabHeights!![mVpDateTime!!.currentItem]
        mVpDateTime!!.requestLayout()
    }

    private fun updateRvHeightRec(index: Int) {
        if (index == 0) {
            val params = mRvTimeOfDay!!.layoutParams as RelativeLayout.LayoutParams
            val count = mAdapterTimeOfDay!!.itemCount
            params.height = (count * 48 * mActivity!!.screenDensity).toInt()
            mRvTimeOfDay!!.requestLayout()
        } else {
            val sd: Float = mActivity!!.screenDensity
            val heights: FloatArray = floatArrayOf(sd * 122, sd * 240, sd * 184)
            val params = mRvWmy!!.layoutParams as RelativeLayout.LayoutParams
            params.height = heights[index - 1].toInt()
            mRvWmy!!.requestLayout()
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (!confirmed) {
            mActivity!!.quickRemindPicker!!.pickPreviousForUI()
        }
        mTabInitiated = BooleanArray(3)
        KeyboardUtil.hideKeyboard(mActivity!!.currentFocus)
    }

    private fun initAll(page: Int) {
        if (page == 0) {
            findViewsAt()
            initUIAt()
            setEventsAt()
        } else if (page == 1) {
            findViewsAfter()
            initUIAfter()
            setEventsAfter()
        } else if (page == 2) {
            findViewsRec()
            initUIRec()
            setEventsRec()
            if (mThing!!.type == Thing.HABIT || mActivity!!.rhParams.habitDetail != null) {
                val type: Int
                if (mActivity!!.rhParams.habitDetail != null) {
                    type = mActivity!!.rhParams.habitType
                } else {
                    val habit: Habit = HabitDAO.getInstance(mActivity)!!.getHabitById(mThing!!.id)!!
                    type = habit.type
                }
                when (type) {
                    Calendar.DATE -> updateUIRecDay()
                    Calendar.WEEK_OF_YEAR -> updateUIRecWeek()
                    Calendar.MONTH -> updateUIRecMonth()
                    Calendar.YEAR -> updateUIRecYear()
                }
            } else {
                updateUIRecDay()
            }
        } else {
            initAll(0)
            return
        }
        mTabInitiated[page] = true
    }

    @SuppressLint("InflateParams")
    private fun initMembers() {
        mActivity = activity as DetailActivity
        mAccentBackground = mActivity!!.getAccentBackground()
        mAccentColor = if (mAccentBackground != null)
            mAccentBackground!!.color
        else mActivity!!.getAccentColor()
        black_54p = ContextCompat.getColor(
            mActivity!!, R.color.app_chrome_on_surface_secondary
        )
        black_26p = ContextCompat.getColor(
            mActivity!!, R.color.app_chrome_on_surface_hint
        )
        confirmed = false

        mTabs = ArrayList()
        val inflater = LayoutInflater.from(mActivity)
        mTabs!!.add(inflater.inflate(R.layout.tab_date_time_at, null))
        mTabs!!.add(inflater.inflate(R.layout.tab_date_time_after, null))
        mTabs!!.add(inflater.inflate(R.layout.tab_date_time_recurrence, null))
        mTabAdapter = DateTimePagerAdapter(mActivity, mTabs as List<View?>)

        mTabHeights = ArrayList()
        mTabHeights!!.add((192 * mActivity!!.screenDensity).toInt())
        mTabHeights!!.add((96  * mActivity!!.screenDensity).toInt())
        mTabHeights!!.add((192 * mActivity!!.screenDensity).toInt())

        mTvsAt = arrayOfNulls(5)
        mEtsAt = arrayOfNulls(5)
        mIlsAt = arrayOfNulls(5)
    }

    private fun findViews() {
        mTabLayout     = f(R.id.tab_layout)
        mVpDateTime    = f(R.id.vp_date_time)
        mTvConfirmAsBt = f(R.id.tv_confirm_as_bt)
        mTvCancelAsBt  = f(R.id.tv_cancel_as_bt)
    }

    private fun findViewsAt() {
        val tab0: View = mTabs!![0]
        mTvsAt!![0] = f(tab0, R.id.tv_year_at)
        mTvsAt!![1] = f(tab0, R.id.tv_month_at)
        mTvsAt!![2] = f(tab0, R.id.tv_day_at)
        mTvsAt!![3] = f(tab0, R.id.tv_hour_at)
        mTvsAt!![4] = f(tab0, R.id.tv_minute_at)

        mEtsAt!![0] = f(tab0, R.id.et_year_at)
        mEtsAt!![1] = f(tab0, R.id.et_month_at)
        mEtsAt!![2] = f(tab0, R.id.et_day_at)
        mEtsAt!![3] = f(tab0, R.id.et_hour_at)
        mEtsAt!![4] = f(tab0, R.id.et_minute_at)

        mTvSummaryAt = f(tab0, R.id.tv_summary_at)

        for (i in mIlsAt!!.indices) {
            mIlsAt!![i] = InputLayout(mActivity!!, mTvsAt!![i]!!, mEtsAt!![i]!!, mAccentColor)
            mIlsAt!![i]!!.setAccentBackground(mAccentBackground)
        }
    }

    private fun findViewsAfter() {
        val tab1: View = mTabs!![1]
        mEtTimeAfter     = f(tab1, R.id.et_time_after)
        mTvTimeAsBtAfter = f(tab1, R.id.tv_time_as_bt_after)
        mDtpAfter        = DateTimePicker(
            mActivity!!, mContentView!!,
            Def.PickerType.TIME_TYPE_HAVE_HOUR_MINUTE, mAccentColor
        )
        if (mAccentBackground != null) {
            mDtpAfter!!.setAccentBackground(mAccentBackground)
        }
        mTvErrorAfter = f(tab1, R.id.tv_error_after)
    }

    private fun findViewsRec() {
        val tab2: View = mTabs!![2]
        mTvTimesLRec      = f(tab2, R.id.tv_times_l_recurrence)
        mTvTimesRRec      = f(tab2, R.id.tv_times_r_recurrence)
        mTvTimeAsBtRec    = f(tab2, R.id.tv_time_as_bt_recurrence)
        mDtpRec           = DateTimePicker(
            mActivity!!, mContentView!!,
            Def.PickerType.TIME_TYPE_NO_HOUR_MINUTE, mAccentColor
        )
        if (mAccentBackground != null) {
            mDtpRec!!.setAccentBackground(mAccentBackground)
        }
        mIvPickAllAsBtRec = f(tab2, R.id.iv_pick_all_as_bt_rec)
        mTvSummaryRec     = f(tab2, R.id.tv_summary_rec)

        // day
        mRvTimeOfDay      = f(tab2, R.id.rv_time_of_day)
        mAdapterTimeOfDay = TimeOfDayRecAdapter(mActivity, mAccentColor)
        mAdapterTimeOfDay!!.setAccentBackground(mAccentBackground)
        val items: MutableList<Int?> = ArrayList()
        items.add(-1)
        items.add(-1)
        mAdapterTimeOfDay!!.setItems(items)
        mLlmTimeOfDay = LinearLayoutManager(mActivity)

        // wmy
        mRlWmy = f(tab2, R.id.rl_rec_wmy)
        mRvWmy = f(tab2, R.id.rv_rec_wmy)

        mFlDayYear = f(tab2, R.id.fl_day_rec_wmy)

        mIlDayYear   = InputLayout(
            mActivity!!,
            f(tab2, R.id.tv_day_rec_wmy),
            f(tab2, R.id.et_day_rec_wmy), mAccentColor
        )
        mIlDayYear!!.setAccentBackground(mAccentBackground)
        mIlHourWmy   = InputLayout(
            mActivity!!,
            f(tab2, R.id.tv_hour_rec_wmy),
            f(tab2, R.id.et_hour_rec_wmy), mAccentColor
        )
        mIlHourWmy!!.setAccentBackground(mAccentBackground)
        mIlMinuteWmy = InputLayout(
            mActivity!!,
            f(tab2, R.id.tv_minute_rec_wmy),
            f(tab2, R.id.et_minute_rec_wmy), mAccentColor
        )
        mIlMinuteWmy!!.setAccentBackground(mAccentBackground)

        // week
        mGlmDayOfWeek     = GridLayoutManager(mActivity, 4)
        mAdapterDayOfWeek = RecurrencePickerAdapter(
            mActivity, Def.PickerType.DAY_OF_WEEK, mAccentColor
        )
        mAdapterDayOfWeek!!.setAccentBackground(mAccentBackground)

        // month
        mGlmDayOfMonth     = GridLayoutManager(mActivity, 6)
        mGlmDayOfMonth!!.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (position == 27) 3 else 1
            }
        }
        mAdapterDayOfMonth = RecurrencePickerAdapter(
            mActivity, Def.PickerType.DAY_OF_MONTH, mAccentColor
        )
        mAdapterDayOfMonth!!.setAccentBackground(mAccentBackground)

        // year
        mGlmMonthOfYear = GridLayoutManager(mActivity, 4)
        mAdapterMonthOfYear = RecurrencePickerAdapter(
            mActivity, Def.PickerType.MONTH_OF_YEAR, mAccentColor
        )
        mAdapterMonthOfYear!!.setAccentBackground(mAccentBackground)
    }

    private fun initUI() {
        mTabLayout!!.setTabTextColors(black_26p, mAccentColor)
        if (mAccentBackground != null) {
            BackgroundUtil.applyTabIndicator(mTabLayout, mAccentBackground)
        } else {
            mTabLayout!!.setSelectedTabIndicatorColor(mAccentColor)
        }
        if (mAccentBackground != null) {
            BackgroundUtil.applyTextBackground(mTvConfirmAsBt, mAccentBackground)
        } else {
            mTvConfirmAsBt!!.setTextColor(mAccentColor)
        }
        mTvConfirmAsBt?.let {
            GradientRippleDrawable.applyAccentRipple(it, mAccentBackground, mAccentColor)
        }

        EdgeEffectUtil.forViewPager(mVpDateTime, mAccentColor)

        mVpDateTime!!.clipChildren = true
        mVpDateTime!!.clipToPadding = true
        mVpDateTime!!.setPadding(0, 0, 0, 0)
        mVpDateTime!!.pageMargin = 0
        mVpDateTime!!.offscreenPageLimit = 2
        mVpDateTime!!.adapter = mTabAdapter
        mTabLayout!!.setupWithViewPager(mVpDateTime)
        installTabRippleShapes()

        if (mAccentBackground != null
            && mAccentBackground!!.mode === ThingBackground.Mode.GRADIENT
        ) {
            applyShaderToSelectedTab()
            mTabLayout!!.addOnTabSelectedListener(
                object : TabLayout.OnTabSelectedListener {
                    override fun onTabSelected(tab: TabLayout.Tab) {
                        applyShaderToSelectedTab()
                    }
                    override fun onTabUnselected(tab: TabLayout.Tab) {
                        clearShaderOnTab(tab)
                    }
                    override fun onTabReselected(tab: TabLayout.Tab) { }
                })
        }
    }

    private fun installTabRippleShapes() {
        val tabLayout = mTabLayout ?: return
        // 三个 tab 的触摸 ripple 改为当前记事颜色（胶囊形）。
        val bg = mAccentBackground ?: ThingBackground.pure(mAccentColor)
        for (i in 0 until tabLayout.tabCount) {
            val tab = tabLayout.getTabAt(i) ?: continue
            tab.view.background = GradientRippleDrawable(bg, shapeOval = false, cornerRadiusPx = -1f)
        }
    }

    /** Install the gradient shader on the currently-selected tab's label TextView. */
    private fun applyShaderToSelectedTab() {
        if (mAccentBackground == null) return
        val sel: TabLayout.Tab = mTabLayout!!.getTabAt(
            mTabLayout!!.selectedTabPosition
        ) ?: return
        val tv: TextView? = findTabLabelTextView(sel)
        if (tv != null) {
            BackgroundUtil.applyTextBackground(tv, mAccentBackground)
        }
    }

    /** Remove the gradient shader so the unselected tab uses TabLayout's int colour. */
    private fun clearShaderOnTab(tab: TabLayout.Tab?) {
        if (tab == null) return
        val tv: TextView? = findTabLabelTextView(tab)
        if (tv != null && tv.paint.shader != null) {
            tv.paint.setShader(null)
            tv.invalidate()
        }
    }

    /** Walk a Material TabView for its label TextView (no public API exposes it). */
    private fun findTabLabelTextView(tab: TabLayout.Tab): TextView? {
        val tabView: View = tab.view
        if (tabView is ViewGroup) {
            val vg: ViewGroup = tabView
            for (i in 0 until vg.childCount) {
                val child: View = vg.getChildAt(i)
                if (child is TextView) return child
            }
        }
        return null
    }

    private fun initUIAt() {
        var dt: ZonedDateTime = ZonedDateTime.now()
        val reminderInMillis = mActivity!!.rhParams.reminderInMillis
        val reminderAfterTime: IntArray? = mActivity!!.rhParams.reminderAfterTime
        if (reminderInMillis != -1L) {
            dt = Instant.ofEpochMilli(reminderInMillis).atZone(ZoneId.systemDefault())
        } else if (reminderAfterTime != null) {
            dt = Instant.ofEpochMilli(
                DateTimeUtil.getActualTimeAfterSomeTime(reminderAfterTime)
            ).atZone(ZoneId.systemDefault())
        } else if (Thing.isReminderType(mThing!!.type)) {
            val reminder: Reminder = ReminderDAO.getInstance(mActivity)!!.getReminderById(mThing!!.id)!!
            dt = Instant.ofEpochMilli(reminder.notifyTime).atZone(ZoneId.systemDefault())
        } else {
            dt = dt.plusMinutes(1)
        }
        val times = IntArray(5)
        for (i in times.indices) {
            times[i] = dt.get(DateTimeUtil.getTemporalFieldFor(mTimeTypes[i]))
            mEtsAt!![i]!!.setText(times[i].toString() + "")
            mIlsAt!![i]!!.raiseLabel(false)
        }
        formatMinuteAt()
        updateSummaryAt(times[0], times[1], times[2], times[3])
    }

    private fun initUIAfter() {
        DisplayUtil.tintView(mEtTimeAfter, black_26p)
        DisplayUtil.setSelectionHandlersColor(mEtTimeAfter, mAccentColor)
        mEtTimeAfter!!.setTextColor(black_54p)
        applyDropdownIcon(mTvTimeAsBtAfter)
        mTvTimeAsBtAfter!!.background = GradientRippleDrawable(
            mAccentBackground ?: ThingBackground.pure(mAccentColor),
            shapeOval = false, cornerRadiusPx = -1f
        )
        mDtpAfter!!.setAnchor(mTvTimeAsBtAfter!!)
        mDtpAfter!!.pickForUI(0)
        improveComplex()
    }

    private fun initUIRec() {
        applyDropdownIcon(mTvTimeAsBtRec)
        val recBg = mAccentBackground ?: ThingBackground.pure(mAccentColor)
        mTvTimeAsBtRec!!.background = GradientRippleDrawable(recBg, shapeOval = false, cornerRadiusPx = -1f)
        mIvPickAllAsBtRec!!.background = GradientRippleDrawable(recBg, shapeOval = true)
        mDtpRec!!.setAnchor(mTvTimeAsBtRec!!)
        (mRvWmy!!.itemAnimator as SimpleItemAnimator).supportsChangeAnimations = false
    }

    private fun applyDropdownIcon(textView: TextView?) {
        val tint = ContextCompat.getColor(mActivity!!, R.color.app_chrome_control_unchecked)
        textView!!.setCompoundDrawablesRelativeWithIntrinsicBounds(
            null,
            null,
            DisplayUtil.opaqueTintDrawable(
                mActivity!!,
                ContextCompat.getDrawable(mActivity!!, R.drawable.ic_dropdown),
                tint
            ),
            null
        )
    }

    private fun getHabitDetail(): String? {
        var habitDetail: String? = mActivity!!.rhParams.habitDetail
        if (habitDetail == null && mThing!!.type == Thing.HABIT) {
            habitDetail = HabitDAO.getInstance(mActivity)!!.getHabitById(mThing!!.id)!!.detail
        }
        // habitDetail may still be null — caller paths null-check before use
        return habitDetail
    }

    private fun getHabitType(): Int {
        var habitType: Int = mActivity!!.rhParams.habitType
        if (habitType == -1 && mThing!!.type == Thing.HABIT) {
            habitType = HabitDAO.getInstance(mActivity)!!.getHabitById(mThing!!.id)!!.type
        }
        return habitType
    }

    private fun updateUIRecDay() {
        mDtpRec!!.pickForUI(0)

        mRvTimeOfDay!!.visibility = View.VISIBLE
        mRlWmy!!.visibility = View.GONE
        mIvPickAllAsBtRec!!.visibility = View.GONE

        if (getHabitType() == Calendar.DATE) {
            val habitDetail: String? = getHabitDetail()
            if (habitDetail != null) {
                mAdapterTimeOfDay!!.setItems(Habit.getDayTimeListFromDetail(habitDetail) as MutableList<Int?>?)
            }
        } else {
            val dt: ZonedDateTime = ZonedDateTime.now()
            val items: MutableList<Int?> = ArrayList()
            items.add(dt.hour)
            items.add(dt.minute)
            mAdapterTimeOfDay!!.setItems(items)
        }

        mRvTimeOfDay!!.adapter = mAdapterTimeOfDay
        mRvTimeOfDay!!.layoutManager = mLlmTimeOfDay
        updateHeightsTimeOfDay()

        updatePickedTimesRec()
        updateTimePeriodRec()
    }

    private fun updateUIRecWeek() {
        mDtpRec!!.pickForUI(1)
        mTabHeights!![2] = (mActivity!!.screenDensity * 280).toInt()
        updateViewPagerHeight()
        updateRvHeightRec(1)

        mRlWmy!!.visibility = View.VISIBLE
        mRvTimeOfDay!!.visibility = View.GONE
        mIvPickAllAsBtRec!!.visibility = View.VISIBLE
        updatePickAllButton(mAdapterDayOfWeek!!)
        mFlDayYear!!.visibility = View.GONE

        if (getHabitType() == Calendar.WEEK_OF_YEAR) {
            val habitDetail: String? = getHabitDetail()
            if (habitDetail != null) {
                mAdapterDayOfWeek!!.pick(Habit.getDayOrMonthListFromDetail(habitDetail))
                val times: Array<String?> = Habit.getTimeFromDetailWeekMonth(habitDetail)
                mIlHourWmy!!.setTextForEditText(times[0]!!)
                mIlMinuteWmy!!.setTextForEditText(times[1]!!)
            }
        } else {
            val dt: ZonedDateTime = ZonedDateTime.now()
            var week = dt.dayOfWeek.value
            week = if (week == 7) 0 else week
            mAdapterDayOfWeek!!.pick(week)
            mIlHourWmy!!.setTextForEditText("" + dt.hour)
            var minute = "" + dt.minute
            minute = if (minute.length == 1) "0$minute" else minute
            mIlMinuteWmy!!.setTextForEditText(minute)
        }

        mRvWmy!!.adapter = mAdapterDayOfWeek
        mRvWmy!!.layoutManager = mGlmDayOfWeek

        updatePickedTimesRec()
        updateTimePeriodRec()
    }

    private fun updateUIRecMonth() {
        mDtpRec!!.pickForUI(2)
        mTabHeights!![2] = (mActivity!!.screenDensity * 392).toInt()
        updateViewPagerHeight()
        updateRvHeightRec(2)

        mRlWmy!!.visibility = View.VISIBLE
        mRvTimeOfDay!!.visibility = View.GONE
        mIvPickAllAsBtRec!!.visibility = View.VISIBLE
        updatePickAllButton(mAdapterDayOfMonth!!)
        mFlDayYear!!.visibility = View.GONE

        if (getHabitType() == Calendar.MONTH) {
            val habitDetail: String? = getHabitDetail()
            if (habitDetail != null) {
                val days: List<Int?> = Habit.getDayOrMonthListFromDetail(habitDetail)
                mAdapterDayOfMonth!!.pick(days)
                if (days[days.size - 1] == 27) {
                    mAdapterDayOfMonth!!.pick(27)
                    mAdapterDayOfMonth!!.pick(mAdapterDayOfMonth!!.itemCount - 1)
                }
                val times: Array<String?> = Habit.getTimeFromDetailWeekMonth(habitDetail)
                mIlHourWmy!!.setTextForEditText(times[0]!!)
                mIlMinuteWmy!!.setTextForEditText(times[1]!!)
            }
        } else {
            val dt: ZonedDateTime = ZonedDateTime.now()
            var day = dt.dayOfMonth
            day = if (day >= 28) 27 else day - 1
            mAdapterDayOfMonth!!.pick(day)
            mIlHourWmy!!.setTextForEditText("" + dt.hour)
            var minute = "" + dt.minute
            minute = if (minute.length == 1) "0$minute" else minute
            mIlMinuteWmy!!.setTextForEditText(minute)
        }

        mRvWmy!!.adapter = mAdapterDayOfMonth
        mRvWmy!!.layoutManager = mGlmDayOfMonth

        updatePickedTimesRec()
        updateTimePeriodRec()
    }

    private fun updateUIRecYear() {
        mDtpRec!!.pickForUI(3)
        mTabHeights!![2] = (mActivity!!.screenDensity * 340).toInt()
        updateViewPagerHeight()
        updateRvHeightRec(3)

        mRlWmy!!.visibility = View.VISIBLE
        mRvTimeOfDay!!.visibility = View.GONE
        mIvPickAllAsBtRec!!.visibility = View.VISIBLE
        updatePickAllButton(mAdapterMonthOfYear!!)
        mFlDayYear!!.visibility = View.VISIBLE

        if (getHabitType() == Calendar.YEAR) {
            val habitDetail: String? = getHabitDetail()
            if (habitDetail != null) {
                val months: List<Int?> = Habit.getDayOrMonthListFromDetail(habitDetail)
                mAdapterMonthOfYear!!.pick(months)

                val dayTimes: Array<String?> = Habit.getTimeFromDetailYear(habitDetail)
                if ("28" == dayTimes[0]) {
                    val et: EditText = mIlDayYear!!.getEditText()
                    et.inputType = EditorInfo.TYPE_CLASS_TEXT
                    et.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(12))
                    mIlDayYear!!.setTextForEditText(mActivity!!.getString(R.string.end_of_month))
                } else {
                    mIlDayYear!!.setTextForEditText(dayTimes[0]!!)
                }
                mIlHourWmy!!.setTextForEditText(dayTimes[1]!!)
                mIlMinuteWmy!!.setTextForEditText(dayTimes[2]!!)
            }
        } else {
            val dt: ZonedDateTime = ZonedDateTime.now()
            val month = dt.monthValue - 1
            mAdapterMonthOfYear!!.pick(month)
            val day = dt.dayOfMonth
            if (day >= 28) {
                val et: EditText = mIlDayYear!!.getEditText()
                et.inputType = EditorInfo.TYPE_CLASS_TEXT
                et.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(12))
                mIlDayYear!!.setTextForEditText(mActivity!!.getString(R.string.end_of_month))
            } else {
                mIlDayYear!!.setTextForEditText("" + day)
            }
            mIlHourWmy!!.setTextForEditText("" + dt.hour)
            var minute = "" + dt.minute
            minute = if (minute.length == 1) "0$minute" else minute
            mIlMinuteWmy!!.setTextForEditText(minute)
        }

        mRvWmy!!.adapter = mAdapterMonthOfYear
        mRvWmy!!.layoutManager = mGlmMonthOfYear

        updatePickedTimesRec()
        updateTimePeriodRec()
    }

    private fun updatePickAllButton(adapter: RecurrencePickerAdapter) {
        val iconRes = if (adapter.getPickedCount() == adapter.itemCount) {
            R.drawable.act_deselect_all
        } else {
            R.drawable.act_select_all
        }
        mIvPickAllAsBtRec!!.setImageDrawable(
            DisplayUtil.opaqueTintDrawable(
                mActivity!!,
                ContextCompat.getDrawable(mActivity!!, iconRes),
                ContextCompat.getColor(mActivity!!, R.color.app_chrome_control_unchecked)
            )
        )
    }

    private fun setEvents() {
        mContentView!!.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                KeyboardUtil.hideKeyboard(v)
                return@setOnTouchListener true
            }
            false
        }
        setButtonEvents()
        setViewPagerEvents()
    }

    private fun setButtonEvents() {
        mTvConfirmAsBt!!.setOnClickListener { endSettingTime() }
        mTvCancelAsBt!!.setOnClickListener { dismiss() }
    }

    private fun setViewPagerEvents() {
        mVpDateTime!!.setOnTouchListener { v, _ ->
            KeyboardUtil.hideKeyboard(v)
            false
        }
        mVpDateTime!!.addOnPageChangeListener(mPageChangeListener)
    }

    private fun setEventsAt() {
        mEtsAt!![4]!!.setOnEditorActionListener(TextView.OnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                endSettingTime()
                return@OnEditorActionListener true
            }
            false
        })
        for (i in mIlsAt!!.indices) {
            val index = i
            mIlsAt!![i]!!.setOnFocusChangeListenerForEditText(View.OnFocusChangeListener { _, hasFocus ->
                val times = IntArray(5)
                var temp: String
                for (i1 in times.indices) {
                    temp = mEtsAt!![i1]!!.text.toString()
                    if (temp.isEmpty()) {
                        times[i1] = -1
                    } else {
                        try {
                            times[i1] = Integer.parseInt(temp)
                        } catch (e: NumberFormatException) {
                            e.printStackTrace()
                            return@OnFocusChangeListener
                        }
                    }
                    if (times[i1] == 0 && i1 != 0 && i1 != 3 && i1 != 4) {
                        mEtsAt!![i1]!!.setText("1")
                        times[i1] = 1
                    }
                    if (times[0] != -1 && times[1] != -1) {
                        val limit = DateTimeUtil.getTimeTypeLimit(times[0], times[1], i1)
                        if (times[i1] > limit) {
                            times[i1] = limit
                            mEtsAt!![i1]!!.setText(limit.toString() + "")
                        }
                    }
                }

                if (!hasFocus) {
                    if (!mEtsAt!![index]!!.text.toString().isEmpty()) {
                        formatMinuteAt()
                    }
                    updateSummaryAt(times[0], times[1], times[2], times[3])
                }
            })
        }
    }

    private fun setEventsAfter() {
        mTvTimeAsBtAfter!!.setOnClickListener(mTvTimeAsBtClickListener)
        mDtpAfter!!.setPickedListener { improveComplex() }
        mEtTimeAfter!!.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            val et = v as EditText
            if (hasFocus) {
                val useGradientLine = mAccentBackground != null
                        && mAccentBackground!!.mode === ThingBackground.Mode.GRADIENT
                if (useGradientLine) {
                    BackgroundUtil.applyTextBackground(et, mAccentBackground)
                    BackgroundUtil.applyEditTextUnderline(et, mAccentBackground)
                    // Hide native underline so only the gradient strip shows.
                    DisplayUtil.tintView(v, android.graphics.Color.TRANSPARENT)
                } else {
                    if (et.paint.shader != null) {
                        et.paint.setShader(null)
                        et.invalidate()
                    }
                    et.setTextColor(mAccentColor)
                    BackgroundUtil.clearEditTextUnderline(et)
                    DisplayUtil.tintView(v, mAccentColor)
                }
                et.highlightColor = DisplayUtil.getLightColor(mAccentColor, mActivity)
            } else {
                improveComplex()
                DisplayUtil.tintView(v, black_26p)
                if (et.paint.shader != null) {
                    et.paint.setShader(null)
                    et.invalidate()
                }
                et.setTextColor(black_54p)
                BackgroundUtil.clearEditTextUnderline(et)
            }
        }
        mEtTimeAfter!!.setOnEditorActionListener(TextView.OnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                improveComplex()
                KeyboardUtil.hideKeyboard(v)
                mDtpAfter!!.show()
                return@OnEditorActionListener true
            }
            false
        })
    }

    private fun setEventsRec() {
        mTvTimeAsBtRec!!.setOnClickListener(mTvTimeAsBtClickListener)
        mDtpRec!!.setPickedListener {
            val pickedIndex = mDtpRec!!.getPickedIndex()

            mIvPickAllAsBtRec!!.visibility = View.VISIBLE
            mRvTimeOfDay!!.visibility = View.GONE
            mRlWmy!!.visibility = View.GONE

            mTvSummaryRec!!.text = ""

            when (pickedIndex) {
                0 -> updateUIRecDay()
                1 -> updateUIRecWeek()
                2 -> updateUIRecMonth()
                else -> updateUIRecYear()
            }
        }
        mIvPickAllAsBtRec!!.setOnClickListener {
            pickOrUnpickAll(mDtpRec!!.getPickedIndex())
        }

        setEventsRecDay()
        setEventsRecWmy()
        setEventsRecWeek()
        setEventsRecMonth()
        setEventsRecYear()
    }

    private fun updateHeightsTimeOfDay() {
        val count = mAdapterTimeOfDay!!.itemCount
        mTabHeights!![2] = (mActivity!!.screenDensity * (count * 48 + 96)).toInt()
        updateViewPagerHeight()
        updateRvHeightRec(0)
    }

    private fun pickOrUnpickAll(index: Int) {
        when (index) {
            1 -> {
                if (mAdapterDayOfWeek!!.getPickedCount() == mAdapterDayOfWeek!!.itemCount) {
                    mAdapterDayOfWeek!!.unpickAll()
                } else {
                    mAdapterDayOfWeek!!.pickAll()
                }
                mAdapterDayOfWeek!!.notifyDataSetChanged()
                mTvTimesLRec!!.text = "" + mAdapterDayOfWeek!!.getPickedCount()
                updatePickAllButton(mAdapterDayOfWeek!!)
            }
            2 -> {
                if (mAdapterDayOfMonth!!.getPickedCount() == mAdapterDayOfMonth!!.itemCount) {
                    mAdapterDayOfMonth!!.unpickAll()
                } else {
                    mAdapterDayOfMonth!!.pickAll()
                }
                mAdapterDayOfMonth!!.notifyDataSetChanged()
                mTvTimesLRec!!.text = "" + mAdapterDayOfMonth!!.getPickedCount()
                updatePickAllButton(mAdapterDayOfMonth!!)
            }
            3 -> {
                if (mAdapterMonthOfYear!!.getPickedCount() == mAdapterMonthOfYear!!.itemCount) {
                    mAdapterMonthOfYear!!.unpickAll()
                } else {
                    mAdapterMonthOfYear!!.pickAll()
                }
                mAdapterMonthOfYear!!.notifyDataSetChanged()
                mTvTimesLRec!!.text = "" + mAdapterMonthOfYear!!.getPickedCount()
                updatePickAllButton(mAdapterMonthOfYear!!)
            }
        }
        improveComplex()
    }

    private fun setEventsRecDay() {
        mAdapterTimeOfDay!!.setOnItemChangeCallback(object : TimeOfDayRecAdapter.OnItemChangeCallback {
            override fun onItemInserted() {
                updatePickedTimesRec()
                updateHeightsTimeOfDay()
            }

            override fun onItemRemoved() {
                updatePickedTimesRec()
                updateHeightsTimeOfDay()
            }
        })
    }

    private fun setEventsRecWmy() {
        mIlHourWmy!!.setOnFocusChangeListenerForEditText(View.OnFocusChangeListener { v, hasFocus ->
            if (!hasFocus) {
                DateTimeUtil.limitHourForEditText(v as EditText)
                val hourStr: String = mIlHourWmy!!.getTextFromEditText()
                if (hourStr.isEmpty()) {
                    mTvSummaryRec!!.text = ""
                    return@OnFocusChangeListener
                }
                val hour = Integer.parseInt(hourStr)
                if (hour >= 24) {
                    mIlHourWmy!!.setTextForEditText("23")
                }
                updateTimePeriodRec()
            }
        })
        mIlMinuteWmy!!.setOnFocusChangeListenerForEditText { v, hasFocus ->
            if (!hasFocus) {
                DateTimeUtil.formatLimitMinuteForEditText(v as EditText)
            }
        }
    }

    private fun updateTimePeriodRec() {
        if (mDtpRec!!.getPickedIndex() == 0) {
            mTvSummaryRec!!.text = ""
            return
        }
        val hourStr: String = mIlHourWmy!!.getTextFromEditText()
        if (hourStr.isEmpty()) {
            mTvSummaryRec!!.text = ""
            return
        }
        val hour = Integer.parseInt(hourStr)
        mTvSummaryRec!!.setTextColor(black_54p)
        mTvSummaryRec!!.text =
            DateTimeUtil.getTimePeriodStr(hour, mActivity!!.resources)
    }

    internal inner class RecAdapterPickedListener(var mAdapter: RecurrencePickerAdapter) : View.OnClickListener {

        override fun onClick(v: View) {
            mTvTimesLRec!!.text = "" + mAdapter.getPickedCount()
            improveComplex()
            updatePickAllButton(mAdapter)
        }
    }

    private fun setEventsRecWeek() {
        mAdapterDayOfWeek!!.setOnPickListener(RecAdapterPickedListener(mAdapterDayOfWeek!!))
    }

    private fun setEventsRecMonth() {
        mAdapterDayOfMonth!!.setOnPickListener(RecAdapterPickedListener(mAdapterDayOfMonth!!))
    }

    private fun setEventsRecYear() {
        mIlDayYear!!.setOnFocusChangeListenerForEditText(View.OnFocusChangeListener { v, hasFocus ->
            val et = v as EditText
            if (!hasFocus) {
                val dayStr: String = et.text.toString()
                if (dayStr.isEmpty()) return@OnFocusChangeListener
                try {
                    val day = Integer.parseInt(dayStr)
                    if (day == 0) {
                        et.setText("1")
                    } else if (day >= 28) {
                        et.inputType = EditorInfo.TYPE_CLASS_TEXT
                        et.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(12))
                        mIlDayYear!!.setTextForEditText(mActivity!!.getString(R.string.end_of_month))
                    }
                } catch (e: NumberFormatException) {
                    et.inputType = EditorInfo.TYPE_CLASS_TEXT
                    et.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(2))
                    e.printStackTrace()
                }
            } else {
                et.inputType = EditorInfo.TYPE_CLASS_NUMBER
                et.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(2))
            }
        })
        mAdapterMonthOfYear!!.setOnPickListener(RecAdapterPickedListener(mAdapterMonthOfYear!!))
    }

    private fun updatePickedTimesRec() {
        val type = mDtpRec!!.getPickedIndex()
        val count = when (type) {
            0 -> mAdapterTimeOfDay!!.getTimeCount()
            1 -> mAdapterDayOfWeek!!.getPickedCount()
            2 -> mAdapterDayOfMonth!!.getPickedCount()
            else -> mAdapterMonthOfYear!!.getPickedCount()
        }
        mTvTimesLRec!!.text = "" + count
        improveComplex()
    }

    private fun improveComplex() {
        if (LocaleUtil.isChinese(mActivity)) return
        val page = mVpDateTime!!.currentItem
        if (page < 0 || page >= mTabInitiated.size || !mTabInitiated[page]) return
        if (page == 1) {
            val timeStr: String = mEtTimeAfter!!.text.toString()
            if (timeStr.isEmpty()) return

            val strs: Array<String> = mTvTimeAsBtAfter!!.text.toString().split(" ".toRegex()).toTypedArray()
            val time: Int
            try {
                time = Integer.parseInt(timeStr)
            } catch (e: NumberFormatException) {
                e.printStackTrace()
                return
            }
            val length = strs[0].length
            if (time > 1 && strs[0][length - 1] != 's') {
                mTvTimeAsBtAfter!!.text = strs[0] + "s " + strs[1]
            } else if (time <= 1 && strs[0][length - 1] == 's') {
                mTvTimeAsBtAfter!!.text = strs[0].substring(0, length - 1) + " " + strs[1]
            }
        } else if (page == 2) {
            val timesStr: String = mTvTimesLRec!!.text.toString()
            improveComplex(Integer.parseInt(timesStr), mTvTimesRRec)
        }
    }

    private fun improveComplex(num: Int, tv: TextView?) {
        if (tv == null) {
            return
        }
        val str: String = tv.text.toString()
        val length: Int = str.length
        if (num > 1 && str[length - 1] != 's') {
            tv.append("s")
        } else if (num <= 1 && str[length - 1] == 's') {
            tv.text = str.substring(0, length - 1)
        }
    }

    private fun endSettingTime() {
        mVpDateTime!!.requestFocus()
        KeyboardUtil.hideKeyboard(mVpDateTime)
        val page = mVpDateTime!!.currentItem
        when (page) {
            0 -> endSettingTimeAt()
            1 -> endSettingTimeAfter()
            else -> endSettingTimeRec()
        }
    }

    private fun endSettingTimeAt() {
        val yearStr: String = mEtsAt!![0]!!.text.toString()
        try {
            val year = Integer.parseInt(yearStr)
            if (year > 4600000) {
                setErrorAt(R.string.error_too_late)
                return
            }
        } catch (e: NumberFormatException) {
            e.printStackTrace()
            setErrorAt(R.string.error_too_late)
            return
        }

        val times = IntArray(5)
        var temp: String
        var mayCanConfirm = true
        for (i in times.indices) {
            temp = mEtsAt!![i]!!.text.toString()
            if (temp.isEmpty()) {
                times[i] = -1
                mayCanConfirm = false
                break
            } else {
                times[i] = Integer.parseInt(temp)
            }
        }
        if (mayCanConfirm) {
            val dt: ZonedDateTime  = ZonedDateTime.of(
                times[0], times[1], times[2], times[3], times[4], 0, 0,
                ZoneId.systemDefault()
            )
            val cur: ZonedDateTime = ZonedDateTime.now()
            if (dt <= cur) {
                setErrorAt(R.string.error_later)
            } else {
                val before = ReminderHabitParams(mActivity!!.rhParams)
                mActivity!!.rhParams.reset()
                mActivity!!.rhParams.reminderInMillis = dt.toInstant().toEpochMilli()
                addActionForUndoRedo(before)
                updateActivityCbAndBackAndTd()
                mActivity!!.tvQuickRemind!!.text =
                    DateTimeUtil.getDateTimeStrAt(dt, mActivity, false)
                confirmed = true
                dismiss()
            }
        } else {
            setErrorAt(R.string.error_complete_time)
        }
    }

    private fun setErrorAt(@StringRes textRes: Int) {
        mTvSummaryAt!!.setTextColor(ContextCompat.getColor(mActivity!!, R.color.error))
        mTvSummaryAt!!.text = mActivity!!.getString(textRes)
    }

    private fun endSettingTimeAfter() {
        val timeStr: String = mEtTimeAfter!!.text.toString()
        if (timeStr.isEmpty()) {
            mTvErrorAfter!!.setText(R.string.error_complete_time)
        } else {
            val time: Int
            try {
                time = Integer.parseInt(timeStr)
            } catch (e: NumberFormatException) {
                e.printStackTrace()
                mTvErrorAfter!!.setText(R.string.error_number_too_big)
                return
            }
            if (time == 0) {
                mTvErrorAfter!!.setText(R.string.error_later)
            } else {
                val type = mDtpAfter!!.getPickedTimeType()
                if ((time > 4600000 && type == Calendar.YEAR) ||
                    (time > 4600000 * 12 && type == Calendar.MONTH) ||
                    (time > 4600000 * 53 && type == Calendar.WEEK_OF_YEAR) ||
                    (time > 4600000 * 365 && type == Calendar.DATE)
                ) {
                    mTvErrorAfter!!.setText(R.string.error_too_late)
                    return
                }

                val before = ReminderHabitParams(mActivity!!.rhParams)
                mActivity!!.rhParams.reset()
                mActivity!!.rhParams.reminderAfterTime = intArrayOf(type, time)
                addActionForUndoRedo(before)
                updateActivityCbAndBackAndTd()
                mActivity!!.tvQuickRemind!!.text = DateTimeUtil.getDateTimeStrAfter(type, time, mActivity)
                confirmed = true
                dismiss()
            }
        }
    }

    private fun endSettingTimeRec() {
        val canConfirm: String = checkCanConfirmRec()
        if (NO_PROBLEM == canConfirm) {
            val type = mDtpRec!!.getPickedTimeType()
            var detail: String? = ""
            if (type == Calendar.DATE) {
                detail = Habit.generateDetailTimeOfDay(mAdapterTimeOfDay!!.getFinalItems())
            } else {
                val hour = Integer.parseInt(mIlHourWmy!!.getTextFromEditText())
                val minute = Integer.parseInt(mIlMinuteWmy!!.getTextFromEditText())
                when (type) {
                    Calendar.WEEK_OF_YEAR -> {
                        val days: List<Int?>? = mAdapterDayOfWeek!!.getPickedIndexes()
                        detail = Habit.generateDetailDayOf(days, hour, minute)
                    }
                    Calendar.MONTH -> {
                        val days: List<Int?>? = mAdapterDayOfMonth!!.getPickedIndexes()
                        detail = Habit.generateDetailDayOf(days, hour, minute)
                    }
                    Calendar.YEAR -> {
                        val months: List<Int?>? = mAdapterMonthOfYear!!.getPickedIndexes()
                        val day: Int
                        try {
                            day = Integer.parseInt(mIlDayYear!!.getTextFromEditText())
                        } catch (_: NumberFormatException) {
                            @Suppress("UNUSED_VARIABLE")
                            val ignored: Int = 28.also { /* defaultDay */ }
                            detail = Habit.generateDetailMonthOfYear(months, 28, hour, minute)
                            applyConfirm(type, detail)
                            return
                        }
                        detail = Habit.generateDetailMonthOfYear(months, day, hour, minute)
                    }
                }
            }
            applyConfirm(type, detail)
        } else {
            mTvSummaryRec!!.setTextColor(ContextCompat.getColor(mActivity!!, R.color.error))
            mTvSummaryRec!!.text = canConfirm
        }
    }

    private fun applyConfirm(type: Int, detail: String?) {
        val before: ReminderHabitParams = mActivity!!.rhParams
        mActivity!!.rhParams.reset()
        mActivity!!.rhParams.habitType = type
        mActivity!!.rhParams.habitDetail = detail
        addActionForUndoRedo(before)
        updateActivityCbAndBackAndTd()
        mActivity!!.tvQuickRemind!!.text = DateTimeUtil.getDateTimeStrRec(mActivity, type, detail)
        confirmed = true
        dismiss()
    }

    private fun formatMinuteAt() {
        val temp: String = mEtsAt!![4]!!.text.toString()
        if (temp.length == 1) {
            mEtsAt!![4]!!.setText("0$temp")
        }
    }

    private fun updateSummaryAt(year: Int, month: Int, day: Int, hour: Int) {
        if (year > 4600000) {
            setErrorAt(R.string.error_too_late)
            return
        }
        mTvSummaryAt!!.setTextColor(black_54p)
        val sb = StringBuilder()
        if (year != -1 && month != -1 && day != -1) {
            val dt: ZonedDateTime = ZonedDateTime.now().withYear(year).withMonth(month).withDayOfMonth(day)
            var dayOfWeek = dt.dayOfWeek.value
            dayOfWeek = if (dayOfWeek == 7) 1 else dayOfWeek + 1
            sb.append(mActivity!!.resources.getStringArray(R.array.day_of_week)[dayOfWeek - 1])
            if (hour != -1) {
                sb.append(", ")
            }
        }
        if (hour != -1) {
            var period: String = DateTimeUtil.getTimePeriodStr(hour, mActivity!!.resources)!!
            if ((year == -1 || month == -1 || day == -1) && !LocaleUtil.isChinese(mActivity)) {
                val temp = period.substring(0, 1).uppercase()
                period = temp + period.substring(1, period.length)
            }
            sb.append(period)
        }
        mTvSummaryAt!!.text = sb.toString()
    }

    private fun updateActivityCbAndBackAndTd() {
        if (mActivity!!.cbQuickRemind!!.isChecked) {
            mActivity!!.updateDescriptions(mAccentColor)
            mActivity!!.updateBackButton()
        } else {
            val temp: Boolean = mActivity!!.shouldAddToActionList
            mActivity!!.shouldAddToActionList = false
            mActivity!!.cbQuickRemind!!.isChecked = true
            mActivity!!.shouldAddToActionList = temp
        }
    }

    private fun addActionForUndoRedo(before: ReminderHabitParams) {
        val after = ReminderHabitParams(mActivity!!.rhParams)
        val action = ThingAction(
            ThingAction.UPDATE_REMINDER_OR_HABIT, before, after
        )
        action.getExtras()!!.putBoolean(
            ThingAction.KEY_CHECKBOX_STATE, mActivity!!.cbQuickRemind!!.isChecked
        )
        action.getExtras()!!.putInt(ThingAction.KEY_PICKED_BEFORE, mPickedBefore)
        action.getExtras()!!.putInt(ThingAction.KEY_PICKED_AFTER, 9)
        mActivity!!.getActionList().addAction(action)
    }

    private fun checkCanConfirmRec(): String {
        val type = mDtpRec!!.getPickedIndex()
        return when (type) {
            0 -> checkCanConfirmRecDay()
            1 -> checkCanConfirmRecWeek()
            2 -> checkCanConfirmRecMonth()
            else -> checkCanConfirmRecYear()
        }
    }

    private fun checkCanConfirmRecDay(): String {
        val times: List<Int?> = mAdapterTimeOfDay!!.getFinalItems()!!
        if (times.isEmpty()) {
            return mActivity!!.getString(R.string.error_complete_time)
        }
        if (times.contains(-1)) {
            return mActivity!!.getString(R.string.error_complete_time)
        } else {
            val set = HashSet<String>()
            var i = 0
            while (i < times.size) {
                val time = times[i].toString() + ":" + times[i + 1]
                if (!set.add(time)) {
                    return mActivity!!.getString(R.string.error_different)
                }
                i += 2
            }
        }
        return NO_PROBLEM
    }

    private fun isHourMinuteWmyOK(): Boolean {
        return !mIlHourWmy!!.getTextFromEditText().isEmpty()
                && !mIlMinuteWmy!!.getTextFromEditText().isEmpty()
    }

    private fun checkCanConfirmRecWeek(): String {
        if (!isHourMinuteWmyOK() || mAdapterDayOfWeek!!.getPickedCount() == 0) {
            return mActivity!!.getString(R.string.error_complete_time)
        }
        return NO_PROBLEM
    }

    private fun checkCanConfirmRecMonth(): String {
        if (!isHourMinuteWmyOK() || mAdapterDayOfMonth!!.getPickedCount() == 0) {
            return mActivity!!.getString(R.string.error_complete_time)
        }
        return NO_PROBLEM
    }

    private fun checkCanConfirmRecYear(): String {
        if (!isHourMinuteWmyOK()
            || mIlDayYear!!.getTextFromEditText().isEmpty()
            || mAdapterMonthOfYear!!.getPickedCount() == 0
        ) {
            return mActivity!!.getString(R.string.error_complete_time)
        }
        return NO_PROBLEM
    }

    companion object {
        const val TAG: String = "DateTimeDialogFragment"

        private const val NO_PROBLEM = "no problem"

        @JvmStatic
        fun newInstance(thing: Thing?): DateTimeDialogFragment {
            val fragment = DateTimeDialogFragment()
            val args = Bundle()
            args.putParcelable(Def.Communication.KEY_THING, thing)
            fragment.arguments = args
            return fragment
        }
    }
}
