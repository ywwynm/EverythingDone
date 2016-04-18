package com.ywwynm.everythingdone.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.ywwynm.everythingdone.helpers.AlarmHelper;

public class AppUpdateReceiver extends BroadcastReceiver {

    public static final String TAG = "AppUpdateReceiver";

    public AppUpdateReceiver() {
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.v(TAG, "App updated!");

        AlarmHelper.createAllAlarms(context, false);

        Log.v(TAG, "Alarms created!");
    }
}
