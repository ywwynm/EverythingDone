package com.ywwynm.everythingdone.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.ywwynm.everythingdone.appwidgets.AppWidgetHelper;
import com.ywwynm.everythingdone.helpers.AlarmHelper;
import com.ywwynm.everythingdone.utils.SystemNotificationUtil;

public class AppUpdateReceiver extends BroadcastReceiver {

    public static final String TAG = "AppUpdateReceiver";

    public AppUpdateReceiver() {
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            Log.i(TAG, "EverythingDone updated.");

            final Context appContext = context.getApplicationContext();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    AlarmHelper.createAllAlarms(appContext, false);
                    Log.i(TAG, "Alarms set.");

                    SystemNotificationUtil.tryToCreateQuickCreateNotification(appContext);
                    Log.i(TAG, "Quick Create Notification created.");

                    SystemNotificationUtil.tryToCreateThingOngoingNotification(appContext);

                    AppWidgetHelper.updateAllAppWidgets(appContext);
                    Log.i(TAG, "App widgets updated.");

                    Log.i(TAG, "Everything Done after app updated.");
                }
            }).start();

        }
    }
}
