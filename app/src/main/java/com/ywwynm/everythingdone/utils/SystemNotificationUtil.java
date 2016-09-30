package com.ywwynm.everythingdone.utils;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.drawable.BitmapDrawable;
import android.media.RingtoneManager;
import android.net.Uri;
import android.provider.Settings;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.FileProvider;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.activities.AuthenticationActivity;
import com.ywwynm.everythingdone.activities.DetailActivity;
import com.ywwynm.everythingdone.activities.SettingsActivity;
import com.ywwynm.everythingdone.database.HabitDAO;
import com.ywwynm.everythingdone.helpers.AttachmentHelper;
import com.ywwynm.everythingdone.helpers.CheckListHelper;
import com.ywwynm.everythingdone.model.Habit;
import com.ywwynm.everythingdone.model.HabitReminder;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.permission.PermissionUtil;

import java.io.File;
import java.util.List;

/**
 * Created by ywwynm on 2016/2/1.
 * notification utils
 */
public class SystemNotificationUtil {

    public static final String TAG = "SystemNotificationUtil";

    private SystemNotificationUtil() {}

    /**
     * Create a {@link NotificationCompat.Builder} for a giving thing which shows its text(title,
     * content, type description, checklist and so on), color, attachment and defines basic content
     * pendingIntent.
     * Please notice that a private thing can also be notified.
     *
     * @param senderName caller's tag that tells DetailActivity who opened it
     * @param id the notification's id.
     * @param position the position of {@param thing} inside
     *                 {@link com.ywwynm.everythingdone.managers.ThingManager#mThings}
     * @param thing the {@link Thing} to be notified.
     * @param autoNotify {@code true} means this notification is used to show automatic reminder.
     * @return the builder object that contains enough information for a notification's UI.
     */
    public static NotificationCompat.Builder newGeneralNotificationBuilder(
            Context context, String senderName, long id, int position, Thing thing, boolean autoNotify) {

        Intent contentIntent = AuthenticationActivity.getOpenIntent(
                context, senderName, id, position,
                Def.Communication.AUTHENTICATE_ACTION_VIEW,
                context.getString(R.string.check_private_thing));
        int type  = thing.getType();
        int color = thing.getColor();
        PendingIntent contentPendingIntent = PendingIntent.getActivity(context,
                (int) id, contentIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        int defaults = Notification.DEFAULT_LIGHTS;
        if (!DeviceUtil.isScreenOn(context) || !DeviceUtil.hasLollipopApi()) {
            defaults |= Notification.DEFAULT_VIBRATE;
        }
        /*
            After executed code above, on my Nexus 6P with Android Nougat, there will be short and
            weak vibration(twice) if screen is on and longer/stronger vibration(twice) if screen
            is off.
         */
        // TODO: 2016/9/30 Maybe we should provide different vibrations for different thing type. And it seems default vibration isn't very elegant on all devices.

        Uri soundUri = getSoundUri(type, context, autoNotify);
        if (DeviceUtil.hasNougatApi()) {
            // Without this line, the sound won't play.
            // But this is stupid... I think it should be fixed by Google
            context.grantUriPermission("com.android.systemui", soundUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context)
                .setColor(color)
                .setDefaults(defaults)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setContentIntent(contentPendingIntent)
                .setSound(soundUri)
                .setSmallIcon(Thing.getTypeIconWhiteLarge(type))
                .setAutoCancel(true);

        String title      = thing.getTitleToDisplay();
        String content    = thing.getContent();
        String attachment = thing.getAttachment();

        if (thing.isPrivate()) {
            content    = context.getString(R.string.notification_private_thing_content);
            attachment = "";
        }

        String contentTitle = title, contentText = content;
        int style = 0;

        if (CheckListHelper.isCheckListStr(content)) {
            contentText = CheckListHelper.toContentStr(content, "X  ", "√  ");
        }

        if (title.isEmpty() && content.isEmpty()) {
            contentTitle = Thing.getTypeStr(type, context);
            if (AttachmentHelper.isValidForm(attachment)) {
                contentText = context.getString(R.string.notification_has_attachment);
            } else {
                contentText = context.getString(R.string.empty);
            }
        } else {
            if (title.isEmpty()) {
                contentTitle = Thing.getTypeStr(type, context);
                style = 1;
            } else if (content.isEmpty()) {
                contentTitle = Thing.getTypeStr(type, context);
                contentText = title;
            } else { // no empty here
                style = 1;
            }
        }

        builder.setContentTitle(contentTitle).setContentText(contentText);
        if (style == 1) {
            builder.setStyle(new NotificationCompat.BigTextStyle().bigText(contentText));
        }

        String firstImageUri = AttachmentHelper.getFirstImageTypePathName(attachment);
        if (firstImageUri != null && PermissionUtil.hasStoragePermission(context)) {
            String pathName = firstImageUri.substring(1, firstImageUri.length());
            Bitmap bigPicture;
            Point display = DisplayUtil.getDisplaySize(context);
            int width = Math.min(display.x, display.y);
            int height = width / 2;
            if (firstImageUri.charAt(0) == '0') {
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

        return builder;
    }

    public static void tryToCreateQuickCreateNotification(Context context) {
        SharedPreferences sp = context.getSharedPreferences(
                Def.Meta.PREFERENCES_NAME, Context.MODE_PRIVATE);
        if (sp.getBoolean(Def.Meta.KEY_QUICK_CREATE, true)) {
            createQuickCreateNotification(context);
        }
    }

    public static void createQuickCreateNotification(Context context) {
        NotificationManagerCompat nmc = NotificationManagerCompat.from(context);
        nmc.cancel(Def.Meta.ONGOING_NOTIFICATION_ID);

        int color = DisplayUtil.getRandomColor(context);
        while (color == App.newThingColor) {
            color = DisplayUtil.getRandomColor(context);
        }
        App.newThingColor = color;

        Intent contentIntent = DetailActivity.getOpenIntentForCreate(
                context, App.class.getName(), color);
        PendingIntent contentPendingIntent = PendingIntent.getActivity(context,
                Def.Meta.ONGOING_NOTIFICATION_ID, contentIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN) /* don't show icon in status bar */
                .setColor(color)
                .setContentTitle(context.getString(R.string.everythingdone))
                .setContentText(context.getString(R.string.title_create_thing))
                .setContentIntent(contentPendingIntent)
                .setSmallIcon(R.drawable.act_create_white);
        nmc.notify(Def.Meta.ONGOING_NOTIFICATION_ID, builder.build());
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

    private static Uri getSoundUri(int type, Context context, boolean autoNotify) {
        SharedPreferences preferences = context.getSharedPreferences(
                Def.Meta.PREFERENCES_NAME, Context.MODE_PRIVATE);
        String key;
        String fs = SettingsActivity.FOLLOW_SYSTEM;
        if (autoNotify) {
            key = Def.Meta.KEY_RINGTONE_AUTO_NOTIFY;
        } else {
            if (type == Thing.REMINDER) {
                key = Def.Meta.KEY_RINGTONE_REMINDER;
            } else if (type == Thing.HABIT) {
                key = Def.Meta.KEY_RINGTONE_HABIT;
            } else { // type == Thing.GOAL
                key = Def.Meta.KEY_RINGTONE_GOAL;
            }
        }
        String uriStr = preferences.getString(key, fs);
        if (uriStr.equals(fs)) {
            return Settings.System.DEFAULT_NOTIFICATION_URI;
        } else {
            Uri uri = Uri.parse(Uri.decode(uriStr));
            RingtoneManager rm = new RingtoneManager(context);
            rm.setType(RingtoneManager.TYPE_NOTIFICATION);
            if (uri != Settings.System.DEFAULT_NOTIFICATION_URI
                    && rm.getRingtonePosition(uri) == -1) { // user's ringtone
                String pathName = UriPathConverter.getLocalPathName(context, uri);
                if (pathName == null || !new File(pathName).exists()) {
                    preferences.edit().putString(key, fs).apply();
                    return Settings.System.DEFAULT_NOTIFICATION_URI;
                } else if (DeviceUtil.hasNougatApi()) {
                    uri = FileProvider.getUriForFile(
                            context, Def.Meta.APP_AUTHORITY, new File(pathName));
                }
            }
            return uri;
        }
    }

}
