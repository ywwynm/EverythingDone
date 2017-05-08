package com.ywwynm.everythingdone.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.activities.DetailActivity;

/**
 * Created by ywwynm on 2017/4/23.
 * A broadcast receiver that will open DetailActivity and create a reminder for user to write
 * their TODOs everyday.
 */
public class DailyCreateTodoReceiver extends BroadcastReceiver {

    public static final String TAG = "DailyCreateTodoReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Intent openIntent = DetailActivity.getOpenIntentForCreate(context, TAG, App.newThingColor);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        context.startActivity(openIntent);
    }

}
