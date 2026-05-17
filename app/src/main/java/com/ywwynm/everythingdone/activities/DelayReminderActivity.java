package com.ywwynm.everythingdone.activities;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import androidx.core.util.Pair;
import androidx.appcompat.app.AppCompatActivity;
import android.view.View;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.fragments.ChooserDialogFragment;
import com.ywwynm.everythingdone.helpers.RemoteActionHelper;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.model.ThingBackground;
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
        return getOpenIntent(context, thingId, position, ThingBackground.pure(color));
    }

    /**
     * Phase 8: ThingBackground-aware open intent. Carries both KEY_COLOR (int)
     * and KEY_BACKGROUND (JSON), so the chooser-dialog accent can render the
     * gradient when the source thing has one.
     */
    public static Intent getOpenIntent(
            Context context, long thingId, int position, ThingBackground bg) {
        Intent intent = new Intent(context, DelayReminderActivity.class);
        intent.putExtra(Def.Communication.KEY_ID, thingId);
        intent.putExtra(Def.Communication.KEY_POSITION, position);
        if (bg != null) {
            intent.putExtra(Def.Communication.KEY_COLOR, bg.representativeColor());
            intent.putExtra(Def.Communication.KEY_BACKGROUND, bg.toJson());
        }
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
        // Phase 8: prefer the JSON ThingBackground when present so a GRADIENT
        // thing's delay-reminder dialog renders gradient text on its title /
        // confirm / picked row.
        String bgJson = intent.getStringExtra(Def.Communication.KEY_BACKGROUND);
        ThingBackground accent = ThingBackground.fromJson(bgJson);
        if (accent == null) accent = ThingBackground.pure(color);

        final ChooserDialogFragment cdf = new ChooserDialogFragment();
        cdf.setAccentBackground(accent);
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0);
                } else {
                    overridePendingTransition(0, 0);
                }
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
