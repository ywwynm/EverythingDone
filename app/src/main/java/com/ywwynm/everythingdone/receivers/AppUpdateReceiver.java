package com.ywwynm.everythingdone.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.ywwynm.everythingdone.helpers.AlarmHelper;
import com.ywwynm.everythingdone.utils.SystemNotificationUtil;

public class AppUpdateReceiver extends BroadcastReceiver {

    public static final String TAG = "AppUpdateReceiver";

    public AppUpdateReceiver() {
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            Log.v(TAG, "App updated!");

            SystemNotificationUtil.tryToCreateQuickCreateNotification(context);

            AlarmHelper.createAllAlarms(context, false);

            Log.v(TAG, "Alarms created!");
        }
    }
}
