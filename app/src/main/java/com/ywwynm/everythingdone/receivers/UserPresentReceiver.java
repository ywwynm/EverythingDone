package com.ywwynm.everythingdone.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.ywwynm.everythingdone.helpers.AlarmHelper;
import com.ywwynm.everythingdone.utils.SystemNotificationUtil;

/**
 * Created by ywwynm on 2016/9/15.
 * Receiver that receives {@link Intent#ACTION_USER_PRESENT} broadcast.
 * This is used to create alarms so that they can still ring and not be destroyed, especially
 * on some third-party roms such as EMUI, MIUI and etc.
 * This isn't very elegant solution, but I think it's at least better than keeping app always alive.
 * However, I don't know if this is useful, just a try.
 * Besides, I don't know if this will cost much battery or memory, either.
 */
public class UserPresentReceiver extends BroadcastReceiver {

    public static final String TAG = "UserPresentReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
            Log.i(TAG, "Screen is on, EverythingDone is responding...");

            final Context appContext = context.getApplicationContext();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    AlarmHelper.createAllAlarms(appContext, false);
                    Log.i(TAG, "Alarms set.");

                    SystemNotificationUtil.tryToCreateQuickCreateNotification(appContext);
                    Log.i(TAG, "Quick Create Notification created.");

                    Log.i(TAG, "Everything Done after screen was on.");
                }
            }).start();

        }
    }
}
