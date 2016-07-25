package com.ywwynm.everythingdone.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.helpers.AlarmHelper;
import com.ywwynm.everythingdone.utils.SystemNotificationUtil;

/**
 * Created by ywwynm on 2016/2/6.
 * Used to set alarms for reminders/habits/goals after device reboots.
 */
public class BootReceiver extends BroadcastReceiver {

    public static final String TAG = "BootReceiver";

    public BootReceiver() {}

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            App.initQuickCreate();

            AlarmHelper.createAllAlarms(context, true);
        }
    }
}
