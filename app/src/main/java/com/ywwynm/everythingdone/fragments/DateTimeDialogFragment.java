package com.ywwynm.everythingdone.fragments;

import android.annotation.SuppressLint;
import android.content.DialogInterface;
import android.os.Bundle;
import android.support.annotation.StringRes;
import android.support.design.widget.TabLayout;
import android.support.v4.content.ContextCompat;
import android.support.v4.view.ViewPager;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.SimpleItemAnimator;
import android.text.InputFilter;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.activities.DetailActivity;
import com.ywwynm.everythingdone.adapters.DateTimePagerAdapter;
import com.ywwynm.everythingdone.adapters.RecurrencePickerAdapter;
import com.ywwynm.everythingdone.adapters.TimeOfDayRecAdapter;
import com.ywwynm.everythingdone.database.HabitDAO;
import com.ywwynm.everythingdone.database.ReminderDAO;
import com.ywwynm.everythingdone.model.Habit;
import com.ywwynm.everythingdone.model.Reminder;
import com.ywwynm.everythingdone.model.ReminderHabitParams;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.model.ThingAction;
import com.ywwynm.everythingdone.utils.DateTimeUtil;
import com.ywwynm.everythingdone.utils.DisplayUtil;
import com.ywwynm.everythingdone.utils.EdgeEffectUtil;
import com.ywwynm.everythingdone.utils.KeyboardUtil;
import com.ywwynm.everythingdone.utils.LocaleUtil;
import com.ywwynm.everythingdone.views.InputLayout;
import com.ywwynm.everythingdone.views.pickers.DateTimePicker;

import org.joda.time.DateTime;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;

/**
 * Created by ywwynm on 2015/8/14.
 * DialogFragment used to pick date/time/recurrence for a Reminder/Habit/Goal.
 */
@SuppressLint("SetTextI18n")
public class DateTimeDialogFragment extends BaseDialogFragment {

    public static final String TAG = "DateTimeDialogFragment";

    private static final String NO_PROBLEM = "no problem";

    private DetailActivity mActivity;

    private Thing mThing;
    private boolean[] mTabInitiated = new boolean[3];

    private int mAccentColor;
    private int black_54p;
    private int black_26p;

    private boolean confirmed;

    private int mPickedBefore;

    // tabs
    private TabLayout            mTabLayout;
    private ViewPager            mVpDateTime;
    private List<View>           mTabs;
    private List<Integer>        mTabHeights;
    private DateTimePagerAdapter mTabAdapter;

    private ViewPager.SimpleOnPageChangeListener mPageChangeListener =
            new ViewPager.SimpleOnPageChangeListener() {
                @Override
                public void onPageSelected(int position) {
                    super.onPageSelected(position);
                    if (!mTabInitiated[position]) {
                        initAll(position);
                    }
                    KeyboardUtil.hideKeyboard(mVpDateTime);
                    improveComplex();
                }

                @Override
                public void onPageScrollStateChanged(int state) {
                    super.onPageScrollStateChanged(state);
                    if (state == ViewPager.SCROLL_STATE_IDLE) {
                        final ViewGroup.LayoutParams params = mVpDateTime.getLayoutParams();
                        params.height = mTabHeights.get(mVpDateTime.getCurrentItem());
                        mVpDateTime.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                mVpDateTime.setLayoutParams(params);
                            }
                        }, 96);
                    }
                }
            };

    private View.OnClickListener mTvTimeAsBtClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            improveComplex();
            if (v.equals(mTvTimeAsBtAfter)) {
                KeyboardUtil.hideKeyboard(mEtTimeAfter);
                mDtpAfter.show();
            } else {
                mDtpRec.show();
            }
        }
    };

    // at
    private int[] mTimeTypes = new int[] { Calendar.YEAR, Calendar.MONTH, Calendar.DATE,
            Calendar.HOUR_OF_DAY, Calendar.MINUTE };
    private TextView      mTvSummaryAt;
    private TextView[]    mTvsAt;
    private EditText[]    mEtsAt;
    private InputLayout[] mIlsAt;

    // after
    private EditText       mEtTimeAfter;
    private TextView       mTvTimeAsBtAfter;
    private DateTimePicker mDtpAfter;
    private TextView       mTvErrorAfter;

    // recurrence
    private TextView       mTvTimesLRec;
    private TextView       mTvTimesRRec;
    private TextView       mTvTimeAsBtRec;
    private TextView       mTvSummaryRec;
    private DateTimePicker mDtpRec;
    private ImageView      mIvPickAllAsBtRec;

    private RecyclerView            mRvTimeOfDay;
    private TimeOfDayRecAdapter     mAdapterTimeOfDay;
    private LinearLayoutManager     mLlmTimeOfDay;

    private RelativeLayout          mRlWmy; // wmy -> Week Month Year
    private FrameLayout             mFlDayYear;
    private InputLayout             mIlDayYear;
    private InputLayout             mIlHourWmy;
    private InputLayout             mIlMinuteWmy;
    private RecyclerView            mRvWmy;
    private GridLayoutManager       mGlmDayOfWeek;
    private GridLayoutManager       mGlmDayOfMonth;
    private GridLayoutManager       mGlmMonthOfYear;
    private RecurrencePickerAdapter mAdapterDayOfWeek;
    private RecurrencePickerAdapter mAdapterDayOfMonth;
    private RecurrencePickerAdapter mAdapterMonthOfYear;

    // footer
    private TextView mTvConfirmAsBt;
    private TextView mTvCancelAsBt;

    public static DateTimeDialogFragment newInstance(Thing thing, int limit) {
        DateTimeDialogFragment fragment = new DateTimeDialogFragment();
        Bundle args = new Bundle();
        args.putParcelable(Def.Communication.KEY_THING, thing);
        args.putInt(Def.Communication.KEY_LIMIT, limit);
        fragment.setArguments(args);
        return fragment;
    }

    public void setPickedBefore(int pickedBefore) {
        mPickedBefore = pickedBefore;
    }

    class InitiallyShowPageRunnable implements Runnable {

        int page;

        InitiallyShowPageRunnable(int page) {
            this.page = page;
        }

        @Override
        public void run() {
            mVpDateTime.setCurrentItem(page);
            updateViewPagerHeight();
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);

        initMembers();
        findViews();
        initUI();
        setEvents();

        Bundle args = getArguments();
        mThing = args.getParcelable(Def.Communication.KEY_THING);
        int limit = args.getInt(Def.Communication.KEY_LIMIT);
        if (mActivity.rhParams.getHabitDetail() != null ||
                limit == Def.LimitForGettingThings.HABIT_UNDERWAY) {
            mVpDateTime.post(new InitiallyShowPageRunnable(2));
            initAll(2);
        } else {
            int to = 0;
            if (limit == Def.LimitForGettingThings.GOAL_UNDERWAY
                    && mActivity.getType() == DetailActivity.CREATE) {
                to = 1;
            }
            mVpDateTime.post(new InitiallyShowPageRunnable(to));
            initAll(to);
        }

        return mContentView;
    }

    @Override
    protected int getLayoutResource() {
        return R.layout.fragment_date_time;
    }

    private void updateViewPagerHeight() {
        final ViewGroup.LayoutParams params = mVpDateTime.getLayoutParams();
        params.height = mTabHeights.get(mVpDateTime.getCurrentItem());
        mVpDateTime.requestLayout();
    }

    private void updateRvHeightRec(int index) {
        if (index == 0) {
            RelativeLayout.LayoutParams params =
                    (RelativeLayout.LayoutParams) mRvTimeOfDay.getLayoutParams();
            int count = mAdapterTimeOfDay.getItemCount();
            params.height = (int) (count * 48 * mActivity.screenDensity);
            mRvTimeOfDay.requestLayout();
        } else {
            float sd = mActivity.screenDensity;
            float[] heights = { sd * 122, sd * 240, sd * 184 };
            RelativeLayout.LayoutParams params =
                    (RelativeLayout.LayoutParams) mRvWmy.getLayoutParams();
            params.height = (int) heights[index - 1];
            mRvWmy.requestLayout();
        }
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        super.onDismiss(dialog);
        if (!confirmed) {
            mActivity.quickRemindPicker.pickPreviousForUI();
        }
        mTabInitiated = new boolean[3];
        KeyboardUtil.hideKeyboard(mActivity.getCurrentFocus());
    }

    private void initAll(int page) {
        if (page == 0) {
            findViewsAt();
            initUIAt();
            setEventsAt();
        } else if (page == 1) {
            findViewsAfter();
            initUIAfter();
            setEventsAfter();
        } else if (page == 2) {
            findViewsRec();
            initUIRec();
            setEventsRec();
            if (mThing.getType() == Thing.HABIT || mActivity.rhParams.getHabitDetail() != null) {
                int type;
                if (mActivity.rhParams.getHabitDetail() != null) {
                    type = mActivity.rhParams.getHabitType();
                } else {
                    Habit habit = HabitDAO.getInstance(mActivity).getHabitById(mThing.getId());
                    type = habit.getType();
                }
                if (type == Calendar.DATE) {
                    updateUIRecDay();
                } else if (type == Calendar.WEEK_OF_YEAR) {
                    updateUIRecWeek();
                } else if (type == Calendar.MONTH) {
                    updateUIRecMonth();
                } else if (type == Calendar.YEAR) {
                    updateUIRecYear();
                }
            } else {
                updateUIRecDay();
            }
        } else {
            initAll(0);
            return;
        }
        mTabInitiated[page] = true;
    }

    @SuppressLint("InflateParams")
    private void initMembers() {
        mActivity = (DetailActivity) getActivity();
        mAccentColor = mActivity.getAccentColor();
        black_54p = ContextCompat.getColor(mActivity, R.color.black_54p);
        black_26p = ContextCompat.getColor(mActivity, R.color.black_26p);
        confirmed = false;

        mTabs = new ArrayList<>();
        LayoutInflater inflater = LayoutInflater.from(mActivity);
        mTabs.add(inflater.inflate(R.layout.tab_date_time_at, null));
        mTabs.add(inflater.inflate(R.layout.tab_date_time_after, null));
        mTabs.add(inflater.inflate(R.layout.tab_date_time_recurrence, null));
        mTabAdapter = new DateTimePagerAdapter(mActivity, mTabs);

        mTabHeights = new ArrayList<>();
        mTabHeights.add((int) (192 * mActivity.screenDensity));
        mTabHeights.add((int) (96  * mActivity.screenDensity));
        mTabHeights.add((int) (192 * mActivity.screenDensity));

        mTvsAt = new TextView[5];
        mEtsAt = new EditText[5];
        mIlsAt = new InputLayout[5];
    }

    private void findViews() {
        mTabLayout     = f(R.id.tab_layout);
        mVpDateTime    = f(R.id.vp_date_time);
        mTvConfirmAsBt = f(R.id.tv_confirm_as_bt);
        mTvCancelAsBt  = f(R.id.tv_cancel_as_bt);
    }

    private void findViewsAt() {
        View tab0 = mTabs.get(0);
        mTvsAt[0] = f(tab0, R.id.tv_year_at);
        mTvsAt[1] = f(tab0, R.id.tv_month_at);
        mTvsAt[2] = f(tab0, R.id.tv_day_at);
        mTvsAt[3] = f(tab0, R.id.tv_hour_at);
        mTvsAt[4] = f(tab0, R.id.tv_minute_at);

        mEtsAt[0] = f(tab0, R.id.et_year_at);
        mEtsAt[1] = f(tab0, R.id.et_month_at);
        mEtsAt[2] = f(tab0, R.id.et_day_at);
        mEtsAt[3] = f(tab0, R.id.et_hour_at);
        mEtsAt[4] = f(tab0, R.id.et_minute_at);

        mTvSummaryAt = f(tab0, R.id.tv_summary_at);

        for (int i = 0; i < mIlsAt.length; i++) {
            mIlsAt[i] = new InputLayout(mActivity, mTvsAt[i], mEtsAt[i], mAccentColor);
        }
    }

    private void findViewsAfter() {
        View tab1        = mTabs.get(1);
        mEtTimeAfter     = f(tab1, R.id.et_time_after);
        mTvTimeAsBtAfter = f(tab1, R.id.tv_time_as_bt_after);
        mDtpAfter        = new DateTimePicker(mActivity, mContentView,
                Def.PickerType.TIME_TYPE_HAVE_HOUR_MINUTE, mAccentColor);
        mTvErrorAfter = f(tab1, R.id.tv_error_after);
    }

    private void findViewsRec() {
        View tab2         = mTabs.get(2);
        mTvTimesLRec      = f(tab2, R.id.tv_times_l_recurrence);
        mTvTimesRRec      = f(tab2, R.id.tv_times_r_recurrence);
        mTvTimeAsBtRec    = f(tab2, R.id.tv_time_as_bt_recurrence);
        mDtpRec           = new DateTimePicker(mActivity, mContentView,
                                Def.PickerType.TIME_TYPE_NO_HOUR_MINUTE, mAccentColor);
        mIvPickAllAsBtRec = f(tab2, R.id.iv_pick_all_as_bt_rec);
        mTvSummaryRec     = f(tab2, R.id.tv_summary_rec);

        // day
        mRvTimeOfDay      = f(tab2, R.id.rv_time_of_day);
        mAdapterTimeOfDay = new TimeOfDayRecAdapter(mActivity, mAccentColor);
        ArrayList<Integer> items = new ArrayList<>();
        items.add(-1);
        items.add(-1);
        mAdapterTimeOfDay.setItems(items);
        mLlmTimeOfDay = new LinearLayoutManager(mActivity);

        // wmy
        mRlWmy = f(tab2,R.id.rl_rec_wmy);
        mRvWmy = f(tab2, R.id.rv_rec_wmy);

        mFlDayYear = f(tab2, R.id.fl_day_rec_wmy);

        mIlDayYear   = new InputLayout(mActivity,
                (TextView) f(tab2, R.id.tv_day_rec_wmy),
                (EditText) f(tab2, R.id.et_day_rec_wmy), mAccentColor);
        mIlHourWmy   = new InputLayout(mActivity,
                (TextView) f(tab2, R.id.tv_hour_rec_wmy),
                (EditText) f(tab2, R.id.et_hour_rec_wmy), mAccentColor);
        mIlMinuteWmy = new InputLayout(mActivity,
                (TextView) f(tab2, R.id.tv_minute_rec_wmy),
                (EditText) f(tab2, R.id.et_minute_rec_wmy), mAccentColor);

        // week
        mGlmDayOfWeek     = new GridLayoutManager(mActivity, 4);
        mAdapterDayOfWeek = new RecurrencePickerAdapter(mActivity,
                Def.PickerType.DAY_OF_WEEK, mAccentColor);

        // month
        mGlmDayOfMonth     = new GridLayoutManager(mActivity, 6);
        mGlmDayOfMonth.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return position == 27 ? 3 : 1;
            }
        });
        mAdapterDayOfMonth = new RecurrencePickerAdapter(mActivity,
                Def.PickerType.DAY_OF_MONTH, mAccentColor);

        // year
        mGlmMonthOfYear = new GridLayoutManager(mActivity, 4);
        mAdapterMonthOfYear = new RecurrencePickerAdapter(mActivity,
                Def.PickerType.MONTH_OF_YEAR, mAccentColor);
    }

    private void initUI() {
        mTabLayout.setTabTextColors(black_26p, mAccentColor);
        mTabLayout.setSelectedTabIndicatorColor(mAccentColor);
        mTvConfirmAsBt.setTextColor(mAccentColor);

        EdgeEffectUtil.forViewPager(mVpDateTime, mAccentColor);

        mVpDateTime.setOffscreenPageLimit(2);
        mVpDateTime.setAdapter(mTabAdapter);
        mTabLayout.setupWithViewPager(mVpDateTime);
    }

    private void initUIAt() {
        DateTime dt = new DateTime();
        long reminderInMillis = mActivity.rhParams.getReminderInMillis();
        int[] reminderAfterTime = mActivity.rhParams.getReminderAfterTime();
        if (reminderInMillis != -1) {
            dt = dt.withMillis(reminderInMillis);
        } else if (reminderAfterTime != null) {
            dt = dt.withMillis(DateTimeUtil.getActualTimeAfterSomeTime(reminderAfterTime));
        } else if (Thing.isReminderType(mThing.getType())) {
            Reminder reminder = ReminderDAO.getInstance(mActivity).getReminderById(mThing.getId());
            dt = dt.withMillis(reminder.getNotifyTime());
        } else {
            dt = dt.plusMinutes(1);
        }
        int[] times = new int[5];
        for (int i = 0; i < times.length; i++) {
            times[i] = dt.get(DateTimeUtil.getJodaType(mTimeTypes[i]));
            mEtsAt[i].setText(times[i] + "");
            mIlsAt[i].raiseLabel(false);
        }
        formatMinuteAt();
        updateSummaryAt(times[0], times[1], times[2], times[3]);
    }

    private void initUIAfter() {
        DisplayUtil.tintView(mEtTimeAfter, black_26p);
        DisplayUtil.setSelectionHandlersColor(mEtTimeAfter, mAccentColor);
        mEtTimeAfter.setTextColor(black_54p);
        mDtpAfter.setAnchor(mTvTimeAsBtAfter);
        mDtpAfter.pickForUI(0);
        improveComplex();
    }

    private void initUIRec() {
        mDtpRec.setAnchor(mTvTimeAsBtRec);
        ((SimpleItemAnimator) mRvWmy.getItemAnimator())
                .setSupportsChangeAnimations(false);
    }

    private String getHabitDetail() {
        String habitDetail = mActivity.rhParams.getHabitDetail();
        if (habitDetail == null && mThing.getType() == Thing.HABIT) {
            habitDetail = HabitDAO.getInstance(mActivity).getHabitById(mThing.getId()).getDetail();
        }
        return habitDetail;
    }

    private int getHabitType() {
        int habitType = mActivity.rhParams.getHabitType();
        if (habitType == -1 && mThing.getType() == Thing.HABIT) {
            habitType = HabitDAO.getInstance(mActivity).getHabitById(mThing.getId()).getType();
        }
        return habitType;
    }

    private void updateUIRecDay() {
        mDtpRec.pickForUI(0);

        mRvTimeOfDay.setVisibility(View.VISIBLE);
        mRlWmy.setVisibility(View.GONE);
        mIvPickAllAsBtRec.setVisibility(View.GONE);

        if (getHabitType() == Calendar.DATE) {
            String habitDetail = getHabitDetail();
            if (habitDetail != null) {
                mAdapterTimeOfDay.setItems(Habit.getDayTimeListFromDetail(habitDetail));
            }
        } else {
            DateTime dt = new DateTime();
            List<Integer> items = new ArrayList<>();
            items.add(dt.getHourOfDay());
            items.add(dt.getMinuteOfHour());
            mAdapterTimeOfDay.setItems(items);
        }

        mRvTimeOfDay.setAdapter(mAdapterTimeOfDay);
        mRvTimeOfDay.setLayoutManager(mLlmTimeOfDay);
        updateHeightsTimeOfDay();

        updatePickedTimesRec();
        updateTimePeriodRec();
    }

    private void updateUIRecWeek() {
        mDtpRec.pickForUI(1);
        mTabHeights.set(2, (int) (mActivity.screenDensity * 280));
        updateViewPagerHeight();
        updateRvHeightRec(1);

        mRlWmy.setVisibility(View.VISIBLE);
        mRvTimeOfDay.setVisibility(View.GONE);
        mIvPickAllAsBtRec.setVisibility(View.VISIBLE);
        updatePickAllButton(mAdapterDayOfWeek);
        mFlDayYear.setVisibility(View.GONE);

        if (getHabitType() == Calendar.WEEK_OF_YEAR) {
            String habitDetail = getHabitDetail();
            if (habitDetail != null) {
                mAdapterDayOfWeek.pick(Habit.getDayOrMonthListFromDetail(habitDetail));
                String[] times = Habit.getTimeFromDetailWeekMonth(habitDetail);
                mIlHourWmy.setTextForEditText(times[0]);
                mIlMinuteWmy.setTextForEditText(times[1]);
            }
        } else {
            DateTime dt = new DateTime();
            int week = dt.getDayOfWeek();
            week = week == 7 ? 0 : week;
            mAdapterDayOfWeek.pick(week);
            mIlHourWmy.setTextForEditText("" + dt.getHourOfDay());
            String minute = "" + dt.getMinuteOfHour();
            minute = minute.length() == 1 ? "0" + minute : minute;
            mIlMinuteWmy.setTextForEditText(minute);
        }

        mRvWmy.setAdapter(mAdapterDayOfWeek);
        mRvWmy.setLayoutManager(mGlmDayOfWeek);

        updatePickedTimesRec();
        updateTimePeriodRec();
    }

    private void updateUIRecMonth() {
        mDtpRec.pickForUI(2);
        mTabHeights.set(2, (int) (mActivity.screenDensity * 392));
        updateViewPagerHeight();
        updateRvHeightRec(2);

        mRlWmy.setVisibility(View.VISIBLE);
        mRvTimeOfDay.setVisibility(View.GONE);
        mIvPickAllAsBtRec.setVisibility(View.VISIBLE);
        updatePickAllButton(mAdapterDayOfMonth);
        mFlDayYear.setVisibility(View.GONE);

        if (getHabitType() == Calendar.MONTH) {
            String habitDetail = getHabitDetail();
            if (habitDetail != null) {
                List<Integer> days = Habit.getDayOrMonthListFromDetail(habitDetail);
                mAdapterDayOfMonth.pick(days);
                if (days.get(days.size() - 1) == 27) {
                    mAdapterDayOfMonth.pick(27);
                    mAdapterDayOfMonth.pick(mAdapterDayOfMonth.getItemCount() - 1);
                }
                String[] times = Habit.getTimeFromDetailWeekMonth(habitDetail);
                mIlHourWmy.setTextForEditText(times[0]);
                mIlMinuteWmy.setTextForEditText(times[1]);
            }
        } else {
            DateTime dt = new DateTime();
            int day = dt.getDayOfMonth();
            day = day >= 28 ? 27 : day - 1;
            mAdapterDayOfMonth.pick(day);
            mIlHourWmy.setTextForEditText("" + dt.getHourOfDay());
            String minute = "" + dt.getMinuteOfHour();
            minute = minute.length() == 1 ? "0" + minute : minute;
            mIlMinuteWmy.setTextForEditText(minute);
        }

        mRvWmy.setAdapter(mAdapterDayOfMonth);
        mRvWmy.setLayoutManager(mGlmDayOfMonth);

        updatePickedTimesRec();
        updateTimePeriodRec();
    }

    private void updateUIRecYear() {
        mDtpRec.pickForUI(3);
        mTabHeights.set(2, (int) (mActivity.screenDensity * 340));
        updateViewPagerHeight();
        updateRvHeightRec(3);

        mRlWmy.setVisibility(View.VISIBLE);
        mRvTimeOfDay.setVisibility(View.GONE);
        mIvPickAllAsBtRec.setVisibility(View.VISIBLE);
        updatePickAllButton(mAdapterMonthOfYear);
        mFlDayYear.setVisibility(View.VISIBLE);

        if (getHabitType() == Calendar.YEAR) {
            String habitDetail = getHabitDetail();
            if (habitDetail != null) {
                List<Integer> months = Habit.getDayOrMonthListFromDetail(habitDetail);
                mAdapterMonthOfYear.pick(months);

                String[] dayTimes = Habit.getTimeFromDetailYear(habitDetail);
                if (dayTimes[0].equals("28")) {
                    EditText et = mIlDayYear.getEditText();
                    et.setInputType(EditorInfo.TYPE_CLASS_TEXT);
                    et.setFilters(new InputFilter[] { new InputFilter.LengthFilter(12) });
                    mIlDayYear.setTextForEditText(mActivity.getString(R.string.end_of_month));
                } else {
                    mIlDayYear.setTextForEditText(dayTimes[0]);
                }
                mIlHourWmy.setTextForEditText(dayTimes[1]);
                mIlMinuteWmy.setTextForEditText(dayTimes[2]);
            }
        } else {
            DateTime dt = new DateTime();
            int month = dt.getMonthOfYear() - 1;
            mAdapterMonthOfYear.pick(month);
            int day = dt.getDayOfMonth();
            if (day >= 28) {
                EditText et = mIlDayYear.getEditText();
                et.setInputType(EditorInfo.TYPE_CLASS_TEXT);
                et.setFilters(new InputFilter[] { new InputFilter.LengthFilter(12) });
                mIlDayYear.setTextForEditText(mActivity.getString(R.string.end_of_month));
            } else {
                mIlDayYear.setTextForEditText("" + day);
            }
            mIlHourWmy.setTextForEditText("" + dt.getHourOfDay());
            String minute = "" + dt.getMinuteOfHour();
            minute = minute.length() == 1 ? "0" + minute : minute;
            mIlMinuteWmy.setTextForEditText(minute);
        }

        mRvWmy.setAdapter(mAdapterMonthOfYear);
        mRvWmy.setLayoutManager(mGlmMonthOfYear);

        updatePickedTimesRec();
        updateTimePeriodRec();
    }

    private void updatePickAllButton(RecurrencePickerAdapter adapter) {
        if (adapter.getPickedCount() == adapter.getItemCount()) {
            mIvPickAllAsBtRec.setImageResource(R.drawable.act_deselect_all);
        } else {
            mIvPickAllAsBtRec.setImageResource(R.drawable.act_select_all);
        }
    }

    private void setEvents() {
        mContentView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    KeyboardUtil.hideKeyboard(v);
                    return true;
                }
                return false;
            }
        });
        setButtonEvents();
        setViewPagerEvents();
    }

    private void setButtonEvents() {
        mTvConfirmAsBt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                endSettingTime();
            }
        });
        mTvCancelAsBt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
    }

    private void setViewPagerEvents() {
        mVpDateTime.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                KeyboardUtil.hideKeyboard(v);
                return false;
            }
        });
        mVpDateTime.addOnPageChangeListener(mPageChangeListener);
    }

    private void setEventsAt() {
        mEtsAt[4].setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    endSettingTime();
                    return true;
                }
                return false;
            }
        });
        for (int i = 0; i < mIlsAt.length; i++) {
            final int index = i;
            mIlsAt[i].setOnFocusChangeListenerForEditText(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    int[] times = new int[5];
                    String temp;
                    for (int i1 = 0; i1 < times.length; i1++) {
                        temp = mEtsAt[i1].getText().toString();
                        if (temp.isEmpty()) {
                            times[i1] = -1;
                        } else {
                            try {
                                times[i1] = Integer.parseInt(temp);
                            } catch (NumberFormatException e) {
                                e.printStackTrace();
                                return;
                            }
                        }
                        if (times[i1] == 0 && i1 != 0 && i1 != 3 && i1 != 4) {
                            mEtsAt[i1].setText("1");
                            times[i1] = 1;
                        }
                        if (times[0] != -1 && times[1] != -1) {
                            int limit = DateTimeUtil.getTimeTypeLimit(times[0], times[1], i1);
                            if (times[i1] > limit) {
                                times[i1] = limit;
                                mEtsAt[i1].setText(limit + "");
                            }
                        }
                    }

                    if (!hasFocus) {
                        if (!mEtsAt[index].getText().toString().isEmpty()) {
                            formatMinuteAt();
                        }
                        updateSummaryAt(times[0], times[1], times[2], times[3]);
                    }
                }
            });
        }
    }

    private void setEventsAfter() {
        mTvTimeAsBtAfter.setOnClickListener(mTvTimeAsBtClickListener);
        mDtpAfter.setPickedListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                improveComplex();
            }
        });
        mEtTimeAfter.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    DisplayUtil.tintView(v, mAccentColor);
                    ((EditText) v).setTextColor(mAccentColor);
                    ((EditText) v).setHighlightColor(DisplayUtil.getLightColor(mAccentColor, mActivity));
                } else {
                    improveComplex();
                    DisplayUtil.tintView(v, black_26p);
                    ((EditText) v).setTextColor(black_54p);
                }
            }
        });
        mEtTimeAfter.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_NEXT) {
                    improveComplex();
                    KeyboardUtil.hideKeyboard(v);
                    mDtpAfter.show();
                    return true;
                }
                return false;
            }
        });
    }

    private void setEventsRec() {
        mTvTimeAsBtRec.setOnClickListener(mTvTimeAsBtClickListener);
        mDtpRec.setPickedListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int pickedIndex = mDtpRec.getPickedIndex();

                mIvPickAllAsBtRec.setVisibility(View.VISIBLE);
                mRvTimeOfDay.setVisibility(View.GONE);
                mRlWmy.setVisibility(View.GONE);

                mTvSummaryRec.setText("");

                if (pickedIndex == 0) {
                    updateUIRecDay();
                } else if (pickedIndex == 1) {
                    updateUIRecWeek();
                } else if (pickedIndex == 2) {
                    updateUIRecMonth();
                } else {
                    updateUIRecYear();
                }
            }
        });
        mIvPickAllAsBtRec.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickOrUnpickAll(mDtpRec.getPickedIndex());
            }
        });

        setEventsRecDay();
        setEventsRecWmy();
        setEventsRecWeek();
        setEventsRecMonth();
        setEventsRecYear();
    }

    private void updateHeightsTimeOfDay() {
        int count = mAdapterTimeOfDay.getItemCount();
        mTabHeights.set(2, (int) (mActivity.screenDensity * (count * 48 + 96)));
        updateViewPagerHeight();
        updateRvHeightRec(0);
    }

    private void pickOrUnpickAll(int index) {
        if (index == 1) {
            if (mAdapterDayOfWeek.getPickedCount() == mAdapterDayOfWeek.getItemCount()) {
                mAdapterDayOfWeek.unpickAll();
            } else {
                mAdapterDayOfWeek.pickAll();
            }
            mAdapterDayOfWeek.notifyDataSetChanged();
            mTvTimesLRec.setText("" + mAdapterDayOfWeek.getPickedCount());
            updatePickAllButton(mAdapterDayOfWeek);
        } else if (index == 2) {
            if (mAdapterDayOfMonth.getPickedCount() == mAdapterDayOfMonth.getItemCount()) {
                mAdapterDayOfMonth.unpickAll();
            } else {
                mAdapterDayOfMonth.pickAll();
            }
            mAdapterDayOfMonth.notifyDataSetChanged();
            mTvTimesLRec.setText("" + mAdapterDayOfMonth.getPickedCount());
            updatePickAllButton(mAdapterDayOfMonth);
        } else if (index == 3) {
            if (mAdapterMonthOfYear.getPickedCount() == mAdapterMonthOfYear.getItemCount()) {
                mAdapterMonthOfYear.unpickAll();
            } else {
                mAdapterMonthOfYear.pickAll();
            }
            mAdapterMonthOfYear.notifyDataSetChanged();
            mTvTimesLRec.setText("" + mAdapterMonthOfYear.getPickedCount());
            updatePickAllButton(mAdapterMonthOfYear);
        }
        improveComplex();
    }

    private void setEventsRecDay() {
        mAdapterTimeOfDay.setOnItemChangeCallback(new TimeOfDayRecAdapter.OnItemChangeCallback() {
            @Override
            public void onItemInserted() {
                updatePickedTimesRec();
                updateHeightsTimeOfDay();
            }

            @Override
            public void onItemRemoved() {
                updatePickedTimesRec();
                updateHeightsTimeOfDay();
            }
        });
    }

    private void setEventsRecWmy() {
        mIlHourWmy.setOnFocusChangeListenerForEditText(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) {
                    DateTimeUtil.limitHourForEditText((EditText) v);
                    String hourStr = mIlHourWmy.getTextFromEditText();
                    if (hourStr.isEmpty()) {
                        mTvSummaryRec.setText("");
                        return;
                    }
                    int hour = Integer.parseInt(hourStr);
                    if (hour >= 24) {
                        mIlHourWmy.setTextForEditText("23");
                    }
                    updateTimePeriodRec();
                }
            }
        });
        mIlMinuteWmy.setOnFocusChangeListenerForEditText(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) {
                    DateTimeUtil.formatLimitMinuteForEditText((EditText) v);
                }
            }
        });
    }

    private void updateTimePeriodRec() {
        if (mDtpRec.getPickedIndex() == 0) {
            mTvSummaryRec.setText("");
            return;
        }
        String hourStr = mIlHourWmy.getTextFromEditText();
        if (hourStr.isEmpty()) {
            mTvSummaryRec.setText("");
            return;
        }
        int hour = Integer.parseInt(hourStr);
        mTvSummaryRec.setTextColor(black_54p);
        mTvSummaryRec.setText(
                DateTimeUtil.getTimePeriodStr(hour, mActivity.getResources()));
    }

    class RecAdapterPickedListener implements View.OnClickListener {

        RecurrencePickerAdapter mAdapter;

        RecAdapterPickedListener(RecurrencePickerAdapter adapter) {
            mAdapter = adapter;
        }

        @Override
        public void onClick(View v) {
            mTvTimesLRec.setText("" + mAdapter.getPickedCount());
            improveComplex();
            updatePickAllButton(mAdapter);
        }
    }

    private void setEventsRecWeek() {
        mAdapterDayOfWeek.setOnPickListener(new RecAdapterPickedListener(mAdapterDayOfWeek));
    }

    private void setEventsRecMonth() {
        mAdapterDayOfMonth.setOnPickListener(new RecAdapterPickedListener(mAdapterDayOfMonth));
    }

    private void setEventsRecYear() {
        mIlDayYear.setOnFocusChangeListenerForEditText(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                EditText et = (EditText) v;
                if (!hasFocus) {
                    String dayStr = et.getText().toString();
                    if (dayStr.isEmpty()) return;
                    try {
                        int day = Integer.parseInt(dayStr);
                        if (day == 0) {
                            et.setText("1");
                        } else if (day >= 28) {
                            et.setInputType(EditorInfo.TYPE_CLASS_TEXT);
                            et.setFilters(new InputFilter[] { new InputFilter.LengthFilter(12) });
                            mIlDayYear.setTextForEditText(mActivity.getString(R.string.end_of_month));
                        }
                    } catch (NumberFormatException e) {
                        et.setInputType(EditorInfo.TYPE_CLASS_TEXT);
                        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2)});
                        e.printStackTrace();
                    }
                } else {
                    et.setInputType(EditorInfo.TYPE_CLASS_NUMBER);
                    et.setFilters(new InputFilter[] { new InputFilter.LengthFilter(2) });
                }
            }
        });
        mAdapterMonthOfYear.setOnPickListener(new RecAdapterPickedListener(mAdapterMonthOfYear));
    }

    private void updatePickedTimesRec() {
        int count;
        int type = mDtpRec.getPickedIndex();
        if (type == 0) {
            count = mAdapterTimeOfDay.getTimeCount();
        } else if (type == 1) {
            count = mAdapterDayOfWeek.getPickedCount();
        } else if (type == 2) {
            count = mAdapterDayOfMonth.getPickedCount();
        } else {
            count = mAdapterMonthOfYear.getPickedCount();
        }
        mTvTimesLRec.setText("" + count);
        improveComplex();
    }

    private void improveComplex() {
        if (LocaleUtil.isChinese(mActivity)) return;
        int page = mVpDateTime.getCurrentItem();
        if (page == 1) {
            String timeStr = mEtTimeAfter.getText().toString();
            if (timeStr.isEmpty()) return;

            String[] strs = mTvTimeAsBtAfter.getText().toString().split(" ");
            int time;
            try {
                time = Integer.parseInt(timeStr);
            } catch (NumberFormatException e) {
                e.printStackTrace();
                return;
            }
            int length = strs[0].length();
            if (time > 1 && strs[0].charAt(length - 1) != 's') {
                mTvTimeAsBtAfter.setText(strs[0] + "s " + strs[1]);
            } else if (time <= 1 && strs[0].charAt(length - 1) == 's') {
                mTvTimeAsBtAfter.setText(strs[0].substring(0, length - 1) + " " + strs[1]);
            }
        } else if (page == 2) {
            String timesStr = mTvTimesLRec.getText().toString();
            improveComplex(Integer.parseInt(timesStr), mTvTimesRRec);
        }
    }

    private void improveComplex(int num, TextView tv) {
        if (tv == null) {
            return;
        }
        String str = tv.getText().toString();
        final int length = str.length();
        if (num > 1 && str.charAt(length - 1) != 's') {
            tv.append("s");
        } else if (num <= 1 && str.charAt(length - 1) == 's') {
            tv.setText(str.substring(0, length - 1));
        }
    }

    private void endSettingTime() {
        mVpDateTime.requestFocus();
        KeyboardUtil.hideKeyboard(mVpDateTime);
        int page = mVpDateTime.getCurrentItem();
        if (page == 0) {
            endSettingTimeAt();
        } else if (page == 1) {
            endSettingTimeAfter();
        } else {
            endSettingTimeRec();
        }
    }

    private void endSettingTimeAt() {
        String yearStr = mEtsAt[0].getText().toString();
        try {
            int year = Integer.parseInt(yearStr);
            if (year > 4600000) {
                setErrorAt(R.string.error_too_late);
                return;
            }
        } catch (NumberFormatException e) {
            e.printStackTrace();
            setErrorAt(R.string.error_too_late);
            return;
        }

        int[] times = new int[5];
        String temp;
        boolean mayCanConfirm = true;
        for (int i = 0; i < times.length; i++) {
            temp = mEtsAt[i].getText().toString();
            if (temp.isEmpty()) {
                times[i] = -1;
                mayCanConfirm = false;
                break;
            } else {
                times[i] = Integer.parseInt(temp);
            }
        }
        if (mayCanConfirm) {
            DateTime dt  = new DateTime(times[0], times[1], times[2], times[3], times[4]);
            DateTime cur = new DateTime();
            if (dt.compareTo(cur) <= 0) {
                setErrorAt(R.string.error_later);
            } else {
                ReminderHabitParams before = new ReminderHabitParams(mActivity.rhParams);
                mActivity.rhParams.reset();
                mActivity.rhParams.setReminderInMillis(dt.getMillis());
                addActionForUndoRedo(before);
                updateActivityCbAndBackAndTd();
                mActivity.tvQuickRemind.setText(
                        DateTimeUtil.getDateTimeStrAt(dt, mActivity, false));
                confirmed = true;
                dismiss();
            }
        } else {
            setErrorAt(R.string.error_complete_time);
        }
    }

    private void setErrorAt(@StringRes int textRes) {
        mTvSummaryAt.setTextColor(ContextCompat.getColor(mActivity, R.color.error));
        mTvSummaryAt.setText(mActivity.getString(textRes));
    }

    private void endSettingTimeAfter() {
        String timeStr = mEtTimeAfter.getText().toString();
        if (timeStr.isEmpty()) {
            mTvErrorAfter.setText(R.string.error_complete_time);
        } else {
            int time;
            try {
                time = Integer.parseInt(timeStr);
            } catch (NumberFormatException e) {
                e.printStackTrace();
                mTvErrorAfter.setText(R.string.error_number_too_big);
                return;
            }
            if (time == 0) {
                mTvErrorAfter.setText(R.string.error_later);
            } else {
                int type = mDtpAfter.getPickedTimeType();
                if ((time > 4600000 && type == Calendar.YEAR) ||
                    (time > 4600000 * 12 && type == Calendar.MONTH) ||
                    (time > 4600000 * 53 && type == Calendar.WEEK_OF_YEAR) ||
                    (time > 4600000 * 365 && type == Calendar.DATE)) {
                    mTvErrorAfter.setText(R.string.error_too_late);
                    return;
                }

                ReminderHabitParams before = new ReminderHabitParams(mActivity.rhParams);
                mActivity.rhParams.reset();
                mActivity.rhParams.setReminderAfterTime(new int[] { type, time });
                addActionForUndoRedo(before);
                updateActivityCbAndBackAndTd();
                mActivity.tvQuickRemind.setText(DateTimeUtil.getDateTimeStrAfter(type, time, mActivity));
                confirmed = true;
                dismiss();
            }
        }
    }

    private void endSettingTimeRec() {
        String canConfirm = checkCanConfirmRec();
        if (NO_PROBLEM.equals(canConfirm)) {
            int type = mDtpRec.getPickedTimeType();
            String detail = "";
            if (type == Calendar.DATE) {
                detail = Habit.generateDetailTimeOfDay(mAdapterTimeOfDay.getFinalItems());
            } else {
                int hour = Integer.parseInt(mIlHourWmy.getTextFromEditText());
                int minute = Integer.parseInt(mIlMinuteWmy.getTextFromEditText());
                if (type == Calendar.WEEK_OF_YEAR) {
                    List<Integer> days = mAdapterDayOfWeek.getPickedIndexes();
                    detail = Habit.generateDetailDayOf(days, hour, minute);
                } else if (type == Calendar.MONTH) {
                    List<Integer> days = mAdapterDayOfMonth.getPickedIndexes();
                    detail = Habit.generateDetailDayOf(days, hour, minute);
                } else if (type == Calendar.YEAR) {
                    List<Integer> months = mAdapterMonthOfYear.getPickedIndexes();
                    int day;
                    try {
                        day = Integer.parseInt(mIlDayYear.getTextFromEditText());
                    } catch (NumberFormatException e) {
                        day = 28;
                    }
                    detail = Habit.generateDetailMonthOfYear(months, day, hour, minute);
                }
            }
            ReminderHabitParams before = mActivity.rhParams;
            mActivity.rhParams.reset();
            mActivity.rhParams.setHabitType(type);
            mActivity.rhParams.setHabitDetail(detail);
            addActionForUndoRedo(before);
            updateActivityCbAndBackAndTd();
            mActivity.tvQuickRemind.setText(DateTimeUtil.getDateTimeStrRec(mActivity, type, detail));
            confirmed = true;
            dismiss();
        } else {
            mTvSummaryRec.setTextColor(ContextCompat.getColor(mActivity, R.color.error));
            mTvSummaryRec.setText(canConfirm);
        }
    }

    private void formatMinuteAt() {
        String temp = mEtsAt[4].getText().toString();
        if (temp.length() == 1) {
            mEtsAt[4].setText(0 + temp);
        }
    }

    private void updateSummaryAt(int year, int month, int day, int hour) {
        if (year > 4600000) {
            setErrorAt(R.string.error_too_late);
            return;
        }
        mTvSummaryAt.setTextColor(black_54p);
        StringBuilder sb = new StringBuilder();
        if (year != -1 && month != -1 && day != -1) {
            DateTime dt = new DateTime().withYear(year).withMonthOfYear(month).withDayOfMonth(day);
            int dayOfWeek = dt.getDayOfWeek();
            dayOfWeek = dayOfWeek == 7 ? 1 : dayOfWeek + 1;
            sb.append(mActivity.getResources().getStringArray(R.array.day_of_week)[dayOfWeek - 1]);
            if (hour != -1) {
                sb.append(", ");
            }
        }
        if (hour != -1) {
            String period = DateTimeUtil.getTimePeriodStr(hour, mActivity.getResources());
            if ((year == -1 || month == -1 || day == -1) && !LocaleUtil.isChinese(mActivity)) {
                String temp = period.substring(0, 1).toUpperCase();
                period = temp + period.substring(1, period.length());
            }
            sb.append(period);
        }
        mTvSummaryAt.setText(sb.toString());
    }

    private void updateActivityCbAndBackAndTd() {
        if (mActivity.cbQuickRemind.isChecked()) {
            mActivity.updateDescriptions(mAccentColor);
            mActivity.updateBackButton();
        } else {
            final boolean temp = mActivity.shouldAddToActionList;
            mActivity.shouldAddToActionList = false;
            // onCheckedChange handles update cb, back and td.
            mActivity.cbQuickRemind.setChecked(true);
            mActivity.shouldAddToActionList = temp;
        }
    }

    private void addActionForUndoRedo(ReminderHabitParams before) {
        ReminderHabitParams after = new ReminderHabitParams(mActivity.rhParams);
        ThingAction action = new ThingAction(
                ThingAction.UPDATE_REMINDER_OR_HABIT, before, after);
        action.getExtras().putBoolean(
                ThingAction.KEY_CHECKBOX_STATE, mActivity.cbQuickRemind.isChecked());
        action.getExtras().putInt(ThingAction.KEY_PICKED_BEFORE, mPickedBefore);
        action.getExtras().putInt(ThingAction.KEY_PICKED_AFTER,  9);
        mActivity.getActionList().addAction(action);
    }

    private String checkCanConfirmRec() {
        int type = mDtpRec.getPickedIndex();
        if (type == 0) {
            return checkCanConfirmRecDay();
        } else if (type == 1) {
            return checkCanConfirmRecWeek();
        } else if (type == 2) {
            return checkCanConfirmRecMonth();
        } else {
            return checkCanConfirmRecYear();
        }
    }

    private String checkCanConfirmRecDay() {
        List<Integer> times = mAdapterTimeOfDay.getFinalItems();
        if (times.isEmpty()) {
            return mActivity.getString(R.string.error_complete_time);
        }
        if (times.contains(-1)) {
            return mActivity.getString(R.string.error_complete_time);
        } else {
            HashSet<String> set = new HashSet<>();
            for (int i = 0; i < times.size(); i += 2) {
                String time = times.get(i) + ":" + times.get(i + 1);
                if (!set.add(time)) {
                    return mActivity.getString(R.string.error_different);
                }
            }
        }
        return NO_PROBLEM;
    }

    private boolean isHourMinuteWmyOK() {
        return !mIlHourWmy.getTextFromEditText().isEmpty()
                && !mIlMinuteWmy.getTextFromEditText().isEmpty();
    }

    private String checkCanConfirmRecWeek() {
        if (!isHourMinuteWmyOK() || mAdapterDayOfWeek.getPickedCount() == 0) {
            return mActivity.getString(R.string.error_complete_time);
        }
        return NO_PROBLEM;
    }

    private String checkCanConfirmRecMonth() {
        if (!isHourMinuteWmyOK() || mAdapterDayOfMonth.getPickedCount() == 0) {
            return mActivity.getString(R.string.error_complete_time);
        }
        return NO_PROBLEM;
    }

    private String checkCanConfirmRecYear() {
        if (!isHourMinuteWmyOK()
                || mIlDayYear.getTextFromEditText().isEmpty()
                || mAdapterMonthOfYear.getPickedCount() == 0) {
            return mActivity.getString(R.string.error_complete_time);
        }
        return NO_PROBLEM;
    }
}