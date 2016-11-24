package com.ywwynm.everythingdone.activities;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.util.Pair;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.Toast;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.BuildConfig;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.database.HabitDAO;
import com.ywwynm.everythingdone.fragments.ChooserDialogFragment;
import com.ywwynm.everythingdone.helpers.ThingDoingHelper;
import com.ywwynm.everythingdone.model.Habit;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.services.DoingService;
import com.ywwynm.everythingdone.utils.DateTimeUtil;
import com.ywwynm.everythingdone.utils.DisplayUtil;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;

/**
 * Created by ywwynm on 2016/10/27.
 * An Activity mainly used to select time will be spent to do something
 */
public class StartDoingActivity extends AppCompatActivity {

    public static final String TAG = "StartDoingActivity";

    public static Intent getOpenIntent(
            Context context, long thingId, int position, int color,
            @DoingService.StartType int startType) {
        Intent intent = new Intent(context, StartDoingActivity.class);
        intent.putExtra(Def.Communication.KEY_ID, thingId);
        intent.putExtra(Def.Communication.KEY_POSITION, position);
        intent.putExtra(Def.Communication.KEY_COLOR, color);
        intent.putExtra(DoingService.KEY_START_TYPE, startType);
        return intent;
    }

    private Thing mThing;
    private @DoingService.StartType int mStartType;

    private List<Integer> mTypes;
    private List<Integer> mTimes;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Intent intent = getIntent();
        final long id = intent.getLongExtra(Def.Communication.KEY_ID, -1);
        final int pos = intent.getIntExtra(Def.Communication.KEY_POSITION, -1);
        final Pair<Thing, Integer> pair = App.getThingAndPosition(getApplicationContext(), id, pos);
        mThing = pair.first;
        if (mThing == null) {
            finish();
            return;
        }
        mStartType = intent.getIntExtra(DoingService.KEY_START_TYPE, DoingService.START_TYPE_ALARM);

        initTimeMember();

        int color = intent.getIntExtra(Def.Communication.KEY_COLOR, DisplayUtil.getRandomColor(this));
        final ChooserDialogFragment cdf = new ChooserDialogFragment();
        cdf.setAccentColor(color);
        cdf.setShouldShowMore(false);
        cdf.setTitle(getString(R.string.start_doing_estimated_time));
        cdf.setItems(getItems());
        cdf.setInitialIndex(0);
        cdf.setShouldDismissAfterConfirm(false);
        cdf.setConfirmText(getString(R.string.start_doing_confirm));
        cdf.setConfirmListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                tryToStartDoingAlarmUser(cdf);
            }
        });
        cdf.setOnDismissListener(new ChooserDialogFragment.OnDismissListener() {
            @Override
            public void onDismiss() {
                finish();
                overridePendingTransition(0, 0);
            }
        });
        cdf.show(getFragmentManager(), ChooserDialogFragment.TAG);
    }

    private void tryToStartDoingAlarmUser(final ChooserDialogFragment cdf) {
        int index = cdf.getPickedIndex();
        boolean canStartDoing = true;
        long timeInMillis;
        if (index == 0) {
            timeInMillis = -1;
        } else {
            index--;
            long etc = DateTimeUtil.getActualTimeAfterSomeTime(
                    mTypes.get(index), mTimes.get(index));
            if (mThing.getType() == Thing.HABIT) {
                Habit habit = HabitDAO.getInstance(this).getHabitById(mThing.getId());
                if (habit != null) {
                    GregorianCalendar calendar = new GregorianCalendar();
                    int ct = calendar.get(habit.getType()); // current t
                    calendar.setTimeInMillis(etc);
                    if (calendar.get(habit.getType()) != ct) {
                        Toast.makeText(this,
                                R.string.start_doing_time_long_t, Toast.LENGTH_LONG).show();
                        canStartDoing = false;
                    } else {
                        long nextTime = habit.getMinHabitReminderTime();
                        if (etc >= nextTime - ThingDoingHelper.TIME_BEFORE_NEXT_HABIT_REMINDER) {
                            Toast.makeText(this,
                                    R.string.start_doing_time_long_alarm, Toast.LENGTH_LONG).show();
                            canStartDoing = false;
                        }
                    }
                }
            }
            timeInMillis = DateTimeUtil.getActualTimeAfterSomeTime(
                    0, mTypes.get(index), mTimes.get(index));
        }
        if (canStartDoing) {
            cdf.dismiss();
            ThingDoingHelper helper = new ThingDoingHelper(this, mThing);
            if (mStartType == DoingService.START_TYPE_ALARM) {
                helper.startDoingAlarm(timeInMillis);
            } else {
                helper.startDoingUser(timeInMillis);
            }
        }
    }

    private void initTimeMember() {
        mTypes = new ArrayList<>();
        mTimes = new ArrayList<>();

        if (BuildConfig.DEBUG) {
            mTypes.add(Calendar.SECOND);
            mTimes.add(6);
        }

        mTypes.add(Calendar.MINUTE);
        mTypes.add(Calendar.MINUTE);
        mTypes.add(Calendar.MINUTE);
        mTypes.add(Calendar.HOUR_OF_DAY);
        mTypes.add(Calendar.MINUTE);
        mTypes.add(Calendar.HOUR_OF_DAY);
        mTypes.add(Calendar.HOUR_OF_DAY);
        mTypes.add(Calendar.HOUR_OF_DAY);

        mTimes.add(15);
        mTimes.add(30);
        mTimes.add(45);
        mTimes.add(1);
        mTimes.add(90);
        mTimes.add(2);
        mTimes.add(3);
        mTimes.add(4);
    }

    /**
     * Not sure
     * 15 minutes
     * 30 minutes
     * 45 minutes
     * 1  hour
     * 90 minutes
     * 2  hours
     * 3  hours
     * 4  hours
     */
    private List<String> getItems() {
        List<String> items = new ArrayList<>();
        items.add(getString(R.string.start_doing_time_not_sure));
        final int size = mTypes.size();
        for (int i = 0; i < size; i++) {
            items.add(DateTimeUtil.getDateTimeStr(mTypes.get(i), mTimes.get(i), this));
        }
        return items;
    }
}
