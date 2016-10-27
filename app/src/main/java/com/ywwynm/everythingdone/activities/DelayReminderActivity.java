package com.ywwynm.everythingdone.activities;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.v4.util.Pair;
import android.support.v7.app.AppCompatActivity;
import android.view.View;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.fragments.ChooserDialogFragment;
import com.ywwynm.everythingdone.helpers.RemoteActionHelper;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.utils.DateTimeUtil;
import com.ywwynm.everythingdone.utils.DisplayUtil;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**=
 * Created by ywwynm on 2016/10/21.
 * An Activity used to select time to delay an alarm for Reminder
 */
public class DelayReminderActivity extends AppCompatActivity {

    public static final String TAG = "DelayReminderActivity";

    public static Intent getOpenIntent(Context context, long thingId, int position, int color) {
        Intent intent = new Intent(context, DelayReminderActivity.class);
        intent.putExtra(Def.Communication.KEY_ID, thingId);
        intent.putExtra(Def.Communication.KEY_POSITION, position);
        intent.putExtra(Def.Communication.KEY_COLOR, color);
        return intent;
    }

    private int[] mTypes = {
            Calendar.MINUTE,
            Calendar.MINUTE,
            Calendar.MINUTE,
            Calendar.MINUTE,
            Calendar.MINUTE,
            Calendar.HOUR_OF_DAY,
            Calendar.HOUR_OF_DAY,
            Calendar.HOUR_OF_DAY,
            Calendar.DATE
    };
    private int[] mTimes = {
            5,
            10,
            15,
            30,
            45,
            1,
            2,
            6,
            1
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        long id = intent.getLongExtra(Def.Communication.KEY_ID, -1);
        int pos = intent.getIntExtra(Def.Communication.KEY_POSITION, -1);
        final Pair<Thing, Integer> pair = App.getThingAndPosition(getApplicationContext(), id, pos);
        if (pair.first == null) {
            finish();
            return;
        }

        int color = intent.getIntExtra(Def.Communication.KEY_COLOR, DisplayUtil.getRandomColor(this));

        final ChooserDialogFragment cdf = new ChooserDialogFragment();
        cdf.setAccentColor(color);
        cdf.setShouldShowMore(false);
        cdf.setTitle(getString(R.string.delay_reminder));
        cdf.setItems(getItems());
        cdf.setInitialIndex(0);
        cdf.setConfirmListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                int index = cdf.getPickedIndex();
                RemoteActionHelper.delay(getApplicationContext(), pair.first, pair.second,
                        mTypes[index], mTimes[index]);
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
     * 5  minutes
     * 10 minutes
     * 15 minutes
     * 30 minutes
     * 45 minutes
     * 1  hour
     * 2  hours
     * 6  hours
     * 1  day
     */
    private List<String> getItems() {
        List<String> items = new ArrayList<>();
        for (int i = 0; i < mTypes.length; i++) {
            items.add(DateTimeUtil.getDateTimeStr(mTypes[i], mTimes[i], this));
        }
        return items;
    }
}
