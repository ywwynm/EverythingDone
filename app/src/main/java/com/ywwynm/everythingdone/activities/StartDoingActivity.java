package com.ywwynm.everythingdone.activities;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.core.util.Pair;
import androidx.appcompat.app.AppCompatActivity;
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
import com.ywwynm.everythingdone.model.ThingBackground;
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
        return getOpenIntent(context, thingId, position,
                ThingBackground.pure(color), startType, hrTime);
    }

    /**
     * Phase 8: full {@link ThingBackground}-aware open intent. Carries both
     * KEY_COLOR (representative int) and KEY_BACKGROUND (JSON) so callers that
     * still read only the int continue to work, while this activity prefers
     * the JSON when present — letting it render gradients on its dialogs and,
     * critically, reflect any pending colour pick the caller made in
     * DetailActivity instead of falling back to the stale saved DB value.
     */
    public static Intent getOpenIntent(
            Context context, long thingId, int position, ThingBackground bg,
            @DoingService.StartType int startType, long hrTime) {
        Intent intent = new Intent(context, StartDoingActivity.class);
        intent.putExtra(Def.Communication.KEY_ID, thingId);
        intent.putExtra(Def.Communication.KEY_POSITION, position);
        if (bg != null) {
            intent.putExtra(Def.Communication.KEY_COLOR, bg.representativeColor());
            intent.putExtra(Def.Communication.KEY_BACKGROUND, bg.toJson());
        }
        intent.putExtra(DoingService.KEY_START_TYPE, startType);
        intent.putExtra(Def.Communication.KEY_TIME, hrTime);
        return intent;
    }

    private Thing mThing;
    private @DoingService.StartType int mStartType;
    /** Phase 8: accent decoded from the intent, prioritised over mThing.getColor()
     *  on this screen's dialogs so DetailActivity's pending pick is honoured. */
    private ThingBackground mAccentBackground;

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
        // Phase 8: prefer the JSON-encoded ThingBackground when present so the
        // chooser title / confirm render gradient when applicable, and so the
        // value reflects the caller's pending pick rather than the stale
        // mThing.getColor() / KEY_COLOR int.
        String bgJson = intent.getStringExtra(Def.Communication.KEY_BACKGROUND);
        mAccentBackground = ThingBackground.fromJson(bgJson);
        if (mAccentBackground == null) mAccentBackground = ThingBackground.pure(color);

        final ChooserDialogFragment cdf = new ChooserDialogFragment();
        cdf.setAccentBackground(mAccentBackground);
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0);
                } else {
                    overridePendingTransition(0, 0);
                }
            }
        });
        cdf.show(getFragmentManager(), ChooserDialogFragment.TAG);
    }

    private void tryToStopAnotherDoingAndStartThis(final ChooserDialogFragment cdf) {
        AlertDialogFragment adf = new AlertDialogFragment();
        // Phase 8: use the caller-supplied accent so a GRADIENT or pending
        // pick renders on the title / confirm. mAccentBackground falls back
        // to ThingBackground.pure(mThing.color) in onCreate when absent.
        adf.setTitleBackground(mAccentBackground);
        adf.setConfirmBackground(mAccentBackground);
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
