package com.ywwynm.everythingdone.utils;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.provider.Settings;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.content.ContextCompat;

import com.ywwynm.everythingdone.Definitions;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.activities.DetailActivity;
import com.ywwynm.everythingdone.activities.SettingsActivity;
import com.ywwynm.everythingdone.database.HabitDAO;
import com.ywwynm.everythingdone.helpers.AttachmentHelper;
import com.ywwynm.everythingdone.helpers.CheckListHelper;
import com.ywwynm.everythingdone.model.Habit;
import com.ywwynm.everythingdone.model.HabitReminder;
import com.ywwynm.everythingdone.model.Thing;

import java.io.File;
import java.util.List;

/**
 * Created by ywwynm on 2016/2/1.
 * notification utils
 */
public class SystemNotificationUtil {

    public static final String TAG = "EverythingDone$SystemNotificationUtil";

    public static NotificationCompat.Builder newGeneralNotificationBuilder(
            Context context, String senderName, long id, int position, Thing thing, boolean autoNotify) {
        Intent contentIntent = new Intent(context, DetailActivity.class);
        contentIntent.putExtra(Definitions.Communication.KEY_SENDER_NAME, senderName);
        contentIntent.putExtra(Definitions.Communication.KEY_DETAIL_ACTIVITY_TYPE,
                DetailActivity.UPDATE);
        contentIntent.putExtra(Definitions.Communication.KEY_ID, id);
        contentIntent.putExtra(Definitions.Communication.KEY_POSITION, position);

        int type = thing.getType();
        int color = thing.getColor();
        PendingIntent contentPendingIntent = PendingIntent.getActivity(context,
                (int) id, contentIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context)
                .setColor(color)
                .setDefaults(Notification.DEFAULT_LIGHTS)
                .setPriority(Notification.PRIORITY_MAX)
                .setContentIntent(contentPendingIntent)
                .setSound(getRingtoneUri(type, context, autoNotify))
                .setSmallIcon(getIconRes(type))
                .setAutoCancel(true);

        String title = thing.getTitle();
        String content = thing.getContent();

        String contentTitle = title, contentText = content, bigText = content;
        int style = 0;

        if (CheckListHelper.isCheckListStr(content)) {
            contentText = CheckListHelper.toContentStr(content, "", "");
            bigText = CheckListHelper.toContentStr(content, "X  ", "√  ");
        }

        if (title.isEmpty() && content.isEmpty()) {
            contentTitle = Thing.getTypeStr(type, context);
            contentText = context.getString(R.string.empty);
        } else {
            if (title.isEmpty()) {
                contentTitle = Thing.getTypeStr(type, context);
                style = 1;
            } else if (content.isEmpty()) {
                contentTitle = Thing.getTypeStr(type, context);
                contentText = title;
            } else {
                style = 1;
            }
        }

        builder.setContentTitle(contentTitle).setContentText(contentText);
        if (style == 1) {
            builder.setStyle(new NotificationCompat.BigTextStyle().bigText(bigText));
        }

        String firstImageUri = AttachmentHelper.getFirstImageTypePathName(thing.getAttachment());
        if (firstImageUri != null) {
            String pathName = firstImageUri.substring(1, firstImageUri.length());
            if (new File(pathName).exists()) {
                Bitmap bigPicture;
                Point display = DisplayUtil.getDisplaySize(context);
                int width = Math.min(display.x, display.y);
                int height = width / 2;
                char tc = firstImageUri.charAt(0);
                if (tc == '0') {
                    bigPicture = BitmapUtil.decodeFileWithRequiredSize(pathName, width, height);
                } else {
                    bigPicture = BitmapUtil.createCroppedBitmap(
                            AttachmentHelper.getImageFromVideo(pathName), width, height);
                }
                builder.setStyle(new NotificationCompat.BigPictureStyle()
                        .bigPicture(bigPicture)
                        .setSummaryText(contentText));
            } else {
                extendWearable(builder, type, color, autoNotify, context);
            }
        } else {
            extendWearable(builder, type, color, autoNotify, context);
        }

        return builder;
    }

    public static void cancelNotification(long thingId, int type, Context context) {
        NotificationManagerCompat nmc = NotificationManagerCompat.from(context);
        if (Thing.isReminderType(type)) {
            nmc.cancel((int) thingId);
        } else if (type == Thing.HABIT) {
            Habit habit = HabitDAO.getInstance(context).getHabitById(thingId);
            List<HabitReminder> habitReminders = habit.getHabitReminders();
            for (HabitReminder habitReminder : habitReminders) {
                nmc.cancel((int) habitReminder.getId());
            }
        }
    }

    private static void extendWearable(NotificationCompat.Builder builder, int type, int color,
                                       boolean autoNotify, Context context) {
        int bmdRes = R.drawable.wear_reminder;
        if (autoNotify) {
            bmdRes = R.drawable.wear_auto_notify;
        } else {
            if (type == Thing.HABIT) {
                bmdRes = R.drawable.wear_habit;
            } else if (type == Thing.GOAL) {
                bmdRes = R.drawable.wear_goal;
            }
        }
        BitmapDrawable bmd = (BitmapDrawable) ContextCompat.getDrawable(context, bmdRes);
        Bitmap bm = BitmapUtil.createLayeredBitmap(bmd, color);
        builder.extend(new NotificationCompat.WearableExtender().setBackground(bm));
    }

    private static Uri getRingtoneUri(int type, Context context, boolean autoNotify) {
        SharedPreferences preferences = context.getSharedPreferences(
                Definitions.MetaData.PREFERENCES_NAME, Context.MODE_PRIVATE);
        String fs = SettingsActivity.FOLLOW_SYSTEM;
        String uriStr = fs;
        if (autoNotify) {
            uriStr = preferences.getString(Definitions.MetaData.KEY_RINGTONE_AUTO_NOTIFY, fs);
        } else {
            if (type == Thing.REMINDER) {
                uriStr = preferences.getString(Definitions.MetaData.KEY_RINGTONE_REMINDER, fs);
            } else if (type == Thing.HABIT) {
                uriStr = preferences.getString(Definitions.MetaData.KEY_RINGTONE_HABIT, fs);
            } else if (type == Thing.GOAL) {
                uriStr = preferences.getString(Definitions.MetaData.KEY_RINGTONE_GOAL, fs);
            }
        }

        if (uriStr.equals(fs)) {
            return Settings.System.DEFAULT_NOTIFICATION_URI;
        } else {
            return Uri.parse(uriStr);
        }
    }

    private static int getIconRes(int type) {
        if (type == Thing.REMINDER) {
            return R.mipmap.notification_reminder;
        } else if (type == Thing.HABIT) {
            return R.mipmap.notification_habit;
        } else if (type == Thing.GOAL) {
            return R.mipmap.notification_goal;
        } else {
            return R.mipmap.notification_note;
        }
    }

}
