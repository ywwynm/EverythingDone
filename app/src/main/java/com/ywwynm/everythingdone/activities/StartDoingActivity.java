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
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.database.HabitDAO;
import com.ywwynm.everythingdone.fragments.AlertDialogFragment;
import com.ywwynm.everythingdone.fragments.ChooserDialogFragment;
import com.ywwynm.everythingdone.helpers.ThingDoingHelper;
import com.ywwynm.everythingdone.model.DoingRecord;
import com.ywwynm.everythingdone.model.Habit;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.services.DoingService;
import com.ywwynm.everythingdone.utils.DateTimeUtil;
import com.ywwynm.everythingdone.utils.DisplayUtil;

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
            @DoingService.StartType int startType, long hrTime) {
        Intent intent = new Intent(context, StartDoingActivity.class);
        intent.putExtra(Def.Communication.KEY_ID, thingId);
        intent.putExtra(Def.Communication.KEY_POSITION, position);
        intent.putExtra(Def.Communication.KEY_COLOR, color);
        intent.putExtra(DoingService.KEY_START_TYPE, startType);
        intent.putExtra(Def.Communication.KEY_TIME, hrTime);
        return intent;
    }

    private Thing mThing;
    private @DoingService.StartType int mStartType;

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

        int color = intent.getIntExtra(Def.Communication.KEY_COLOR, DisplayUtil.getRandomColor(this));
        final ChooserDialogFragment cdf = new ChooserDialogFragment();
        cdf.setAccentColor(color);
        cdf.setShouldShowMore(false);
        cdf.setTitle(getString(R.string.start_doing_estimated_time));
        cdf.setItems(ThingDoingHelper.getStartDoingTimeItems(this));
        cdf.setInitialIndex(0);
        cdf.setShouldDismissAfterConfirm(false);
        cdf.setConfirmText(getString(R.string.start_doing_confirm));
        cdf.setConfirmListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                long doingId = App.getDoingThingId();
                if (doingId == -1) {
                    tryToStartDoingAlarmUser(cdf);
                } else if (doingId != mThing.getId()) {
                    // doing another thing
                    tryToStopAnotherDoingAndStartThis(cdf);
                } else {
                    // TODO: 2016/11/27 is doing this thing impossible here?
                }
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

    private void tryToStopAnotherDoingAndStartThis(final ChooserDialogFragment cdf) {
        AlertDialogFragment adf = new AlertDialogFragment();
        adf.setTitleColor(mThing.getColor());
        adf.setConfirmColor(mThing.getColor());
        adf.setTitle(getString(R.string.start_doing_stop_another_title));
        adf.setContent(getString(R.string.start_doing_stop_another_content));
        adf.setConfirmText(getString(R.string.yes));
        adf.setCancelText(getString(R.string.no));
        adf.setConfirmListener(new AlertDialogFragment.ConfirmListener() {
            @Override
            public void onConfirm() {
                tryToStartDoingAlarmUser(cdf);
            }
        });
        adf.show(getFragmentManager(), AlertDialogFragment.TAG);
    }

    private void tryToStartDoingAlarmUser(final ChooserDialogFragment cdf) {
        int index = cdf.getPickedIndex();
        boolean canStartDoing = true;
        long timeInMillis;
        if (index == 0) {
            timeInMillis = -1;
        } else {
            Pair<List<Integer>, List<Integer>> typeTimes =
                    ThingDoingHelper.getStartDoingTypeTimes(false);
            long etc = DateTimeUtil.getActualTimeAfterSomeTime(
                    typeTimes.first.get(index), typeTimes.second.get(index));
            if (mThing.getType() == Thing.HABIT) {
                Habit habit = HabitDAO.getInstance(this).getHabitById(mThing.getId());
                if (habit != null) {
                    GregorianCalendar calendar = new GregorianCalendar();
                    int ct = calendar.get(habit.getType()); // current t
                    calendar.setTimeInMillis(etc + ThingDoingHelper.TIME_BEFORE_NEXT_T);
                    if (calendar.get(habit.getType()) != ct) {
                        Toast.makeText(this,
                                R.string.start_doing_time_long_t, Toast.LENGTH_LONG).show();
                        canStartDoing = false;
                    } else {
                        long nextTime = habit.getDoingEndLimitTime();
                        if (etc >= nextTime - ThingDoingHelper.TIME_BEFORE_NEXT_HABIT_REMINDER) {
                            Toast.makeText(this,
                                    R.string.start_doing_time_long_alarm, Toast.LENGTH_LONG).show();
                            canStartDoing = false;
                        }
                    }
                }
            }
            timeInMillis = DateTimeUtil.getActualTimeAfterSomeTime(
                    0, typeTimes.first.get(index), typeTimes.second.get(index));
        }
        if (canStartDoing) {
            cdf.dismiss();
            long doingId = App.getDoingThingId();
            if (doingId != -1 && doingId != mThing.getId()) {
                DoingService.sResetDoingIdInOnDestroy = false;
                ThingDoingHelper.stopDoing(this, DoingRecord.STOP_REASON_CANCEL_USER);
            }

            ThingDoingHelper helper = new ThingDoingHelper(this, mThing);
            long hrTime = getIntent().getLongExtra(Def.Communication.KEY_TIME, -1L);
            if (mStartType == DoingService.START_TYPE_ALARM) {
                helper.startDoingAlarm(timeInMillis, hrTime);
            } else {
                helper.startDoingUser(timeInMillis, hrTime);
            }
        }
    }
}
