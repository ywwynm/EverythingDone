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
import com.ywwynm.everythingdone.fragments.ChooserDialogFragment;
import com.ywwynm.everythingdone.model.Habit;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.utils.DateTimeUtil;
import com.ywwynm.everythingdone.utils.DisplayUtil;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Created by ywwynm on 2016/10/27.
 * An Activity mainly used to select time will be spent to do something
 */
public class StartDoingActivity extends AppCompatActivity {

    public static final String TAG = "StartDoingActivity";

    public static Intent getOpenIntent(Context context, long thingId, int position, int color) {
        Intent intent = new Intent(context, StartDoingActivity.class);
        intent.putExtra(Def.Communication.KEY_ID, thingId);
        intent.putExtra(Def.Communication.KEY_POSITION, position);
        intent.putExtra(Def.Communication.KEY_COLOR, color);
        return intent;
    }

    private int[] mTypes = {
            Calendar.MINUTE,
            Calendar.MINUTE,
            Calendar.HOUR_OF_DAY,
            Calendar.MINUTE,
            Calendar.HOUR_OF_DAY,
            Calendar.HOUR_OF_DAY,
            Calendar.HOUR_OF_DAY
    };
    private int[] mTimes = {
            30,
            45,
            1,
            90,
            2,
            3,
            4
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Intent intent = getIntent();
        final long id = intent.getLongExtra(Def.Communication.KEY_ID, -1);
        int pos = intent.getIntExtra(Def.Communication.KEY_POSITION, -1);
        final Pair<Thing, Integer> pair = App.getThingAndPosition(getApplicationContext(), id, pos);
        final Thing thing = pair.first;
        if (thing == null) {
            finish();
            return;
        }

        int color = intent.getIntExtra(Def.Communication.KEY_COLOR, DisplayUtil.getRandomColor(this));

        final ChooserDialogFragment cdf = new ChooserDialogFragment();
        cdf.setAccentColor(color);
        cdf.setShouldShowMore(false);
        cdf.setTitle(getString(R.string.start_doing_estimated_time));
        cdf.setItems(getItems());
        cdf.setInitialIndex(0);
        cdf.setConfirmText(getString(R.string.start_doing_confirm));
        cdf.setConfirmListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                int index = cdf.getPickedIndex();
                boolean shouldGoToDoing = true;
                int type, time;
                if (index == 0) {
                    type = time = -1;
                } else {
                    index--;
                    long etc = DateTimeUtil.getActualTimeAfterSomeTime(
                            mTypes[index], mTimes[index]);
                    if (thing.getType() == Thing.HABIT) {
                        // TODO: 2016/11/1 > next T
                        Habit habit = HabitDAO.getInstance(getApplicationContext()).getHabitById(id);
                        if (habit != null) {
                            long nextTime = habit.getMinHabitReminderTime();
                            if (etc >= nextTime - 6 * 60 * 1000) {
                                Toast.makeText(getApplicationContext(),
                                        R.string.start_doing_time_too_long, Toast.LENGTH_LONG).show();
                                shouldGoToDoing = false;
                            }
                        }
                    }
                    type = mTypes[index];
                    time = mTimes[index];
                }
                if (shouldGoToDoing) {
                    cdf.dismiss();
                    Intent intent1 = DoingActivity.getOpenIntent(
                            StartDoingActivity.this, thing, time, type, System.currentTimeMillis());
                    startActivity(intent1);
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

    /**
     * Not sure
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
        for (int i = 0; i < mTypes.length; i++) {
            items.add(DateTimeUtil.getDateTimeStr(mTypes[i], mTimes[i], this));
        }
        return items;
    }
}
