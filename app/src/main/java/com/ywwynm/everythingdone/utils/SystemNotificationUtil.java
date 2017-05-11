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
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.FileProvider;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.FrequentSettings;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.activities.AuthenticationActivity;
import com.ywwynm.everythingdone.activities.DetailActivity;
import com.ywwynm.everythingdone.activities.DoingActivity;
import com.ywwynm.everythingdone.activities.SettingsActivity;
import com.ywwynm.everythingdone.database.HabitDAO;
import com.ywwynm.everythingdone.helpers.AttachmentHelper;
import com.ywwynm.everythingdone.helpers.CheckListHelper;
import com.ywwynm.everythingdone.model.Habit;
import com.ywwynm.everythingdone.model.HabitReminder;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.permission.PermissionUtil;
import com.ywwynm.everythingdone.receivers.DoingNotificationActionReceiver;
import com.ywwynm.everythingdone.receivers.HabitNotificationActionReceiver;
import com.ywwynm.everythingdone.receivers.ReminderNotificationActionReceiver;
import com.ywwynm.everythingdone.services.DoingService;

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

    public static void addActionsForReminderNotification(
            NotificationCompat.Builder builder, Context context, long id, int position,
            @Thing.Type int type) {
        Intent finishIntent = new Intent(context, ReminderNotificationActionReceiver.class);
        finishIntent.setAction(Def.Communication.NOTIFICATION_ACTION_FINISH);
        finishIntent.putExtra(Def.Communication.KEY_ID, id);
        finishIntent.putExtra(Def.Communication.KEY_POSITION, position);
        builder.addAction(R.drawable.act_finish, context.getString(R.string.act_finish),
                PendingIntent.getBroadcast(context,
                        (int) id, finishIntent, PendingIntent.FLAG_UPDATE_CURRENT));

        if (type == Thing.REMINDER) {
            Intent startIntent = new Intent(context, ReminderNotificationActionReceiver.class);
            startIntent.setAction(Def.Communication.NOTIFICATION_ACTION_START_DOING);
            startIntent.putExtra(Def.Communication.KEY_ID, id);
            startIntent.putExtra(Def.Communication.KEY_POSITION, position);
            builder.addAction(R.drawable.act_start_doing,
                    context.getString(R.string.act_start_doing),
                    PendingIntent.getBroadcast(context,
                            (int) id, startIntent, PendingIntent.FLAG_UPDATE_CURRENT));

            Intent delayIntent = new Intent(context, ReminderNotificationActionReceiver.class);
            delayIntent.setAction(Def.Communication.NOTIFICATION_ACTION_DELAY);
            delayIntent.putExtra(Def.Communication.KEY_ID, id);
            delayIntent.putExtra(Def.Communication.KEY_POSITION, position);
            builder.addAction(R.drawable.act_delay,
                    context.getString(R.string.act_delay),
                    PendingIntent.getBroadcast(context,
                            (int) id, delayIntent, PendingIntent.FLAG_UPDATE_CURRENT));
        }
    }

    public static void addActionsForHabitNotification(
            Context context, NotificationCompat.Builder builder, long hrId, int position, long hrTime) {
        Intent finishIntent = new Intent(context, HabitNotificationActionReceiver.class);
        finishIntent.setAction(Def.Communication.NOTIFICATION_ACTION_FINISH);
        finishIntent.putExtra(Def.Communication.KEY_ID, hrId);
        finishIntent.putExtra(Def.Communication.KEY_POSITION, position);
        finishIntent.putExtra(Def.Communication.KEY_TIME, hrTime);
        builder.addAction(R.drawable.act_finish, context.getString(R.string.act_finish_this_time_habit),
                PendingIntent.getBroadcast(context,
                        (int) hrId, finishIntent, PendingIntent.FLAG_UPDATE_CURRENT));

        Intent startIntent = new Intent(context, HabitNotificationActionReceiver.class);
        startIntent.setAction(Def.Communication.NOTIFICATION_ACTION_START_DOING);
        startIntent.putExtra(Def.Communication.KEY_ID, hrId);
        startIntent.putExtra(Def.Communication.KEY_POSITION, position);
        finishIntent.putExtra(Def.Communication.KEY_TIME, hrTime);
        builder.addAction(R.drawable.act_start_doing,
                context.getString(R.string.act_start_doing),
                PendingIntent.getBroadcast(context,
                        (int) hrId, startIntent, PendingIntent.FLAG_UPDATE_CURRENT));

//            Intent getItIntent = new Intent(context, HabitNotificationActionReceiver.class);
//            getItIntent.setAction(Def.Communication.NOTIFICATION_ACTION_GET_IT);
//            getItIntent.putExtra(Def.Communication.KEY_ID, hrId);
//            getItIntent.putExtra(Def.Communication.KEY_POSITION, position);
//            builder.addAction(R.drawable.act_get_it,
//                    context.getString(R.string.act_get_it),
//                    PendingIntent.getBroadcast(context,
//                            (int) hrId, getItIntent, PendingIntent.FLAG_UPDATE_CURRENT));

        Intent deleteIntent = new Intent(context, HabitNotificationActionReceiver.class);
        deleteIntent.setAction(Def.Communication.NOTIFICATION_ACTION_CANCEL);
        deleteIntent.putExtra(Def.Communication.KEY_ID, hrId);
        builder.setDeleteIntent(PendingIntent.getBroadcast(
                context, (int) hrId, deleteIntent, PendingIntent.FLAG_UPDATE_CURRENT));
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

        Intent contentIntent = DetailActivity.getOpenIntentForCreate(
                context, App.class.getName(), App.newThingColor);
        PendingIntent contentPendingIntent = PendingIntent.getActivity(context,
                Def.Meta.ONGOING_NOTIFICATION_ID, contentIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN) /* don't show icon in status bar */
                .setColor(App.newThingColor)
                .setContentTitle(context.getString(R.string.everythingdone))
                .setContentText(context.getString(R.string.title_create_thing))
                .setContentIntent(contentPendingIntent)
                .setSmallIcon(R.drawable.act_create_white);
        nmc.notify(Def.Meta.ONGOING_NOTIFICATION_ID, builder.build());
    }

    public static Notification createDoingNotification(
            Context context, Thing thing, @DoingService.State int doingState,
            String leftTimeStr, long hrTime, int highlightStrategy) {
        @Thing.Type int thingType = thing.getType();
        final String contentText = getDoingNotificationContent(context, doingState, leftTimeStr);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context)
                .setColor(thing.getColor())
                .setSmallIcon(Thing.getTypeIconWhiteLarge(thingType))
                .setContentTitle(getDoingNotificationTitle(context, thing, doingState))
                .setContentText(contentText);
        if (highlightStrategy != 0) {
            if (highlightStrategy >= 1) {
                builder.setDefaults(Notification.DEFAULT_ALL);
            }
            if (highlightStrategy >= 2) {
                builder.setPriority(Notification.PRIORITY_MAX);
            }
        }

        long thingId = thing.getId();
        if (doingState == DoingService.STATE_DOING) {
            Intent contentIntent = DoingActivity.getOpenIntent(context, true);
            builder.setContentIntent(PendingIntent.getActivity(
                    context, (int) thingId, contentIntent, PendingIntent.FLAG_UPDATE_CURRENT));

            Intent finishIntent = new Intent(context, DoingNotificationActionReceiver.class);
            finishIntent.setAction(DoingNotificationActionReceiver.ACTION_FINISH);
            finishIntent.putExtra(Def.Communication.KEY_ID, thingId);
            finishIntent.putExtra(Def.Communication.KEY_TIME, hrTime);
            builder.addAction(R.drawable.act_finish, context.getString(R.string.act_finish),
                    PendingIntent.getBroadcast(
                            context, (int) thingId, finishIntent, PendingIntent.FLAG_UPDATE_CURRENT));

            Intent cancelIntent = new Intent(context, DoingNotificationActionReceiver.class);
            cancelIntent.setAction(DoingNotificationActionReceiver.ACTION_USER_CANCEL);
            cancelIntent.putExtra(Def.Communication.KEY_ID, thingId);
            builder.addAction(R.drawable.act_cancel_white, context.getString(R.string.cancel),
                    PendingIntent.getBroadcast(
                            context, (int) thingId, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            builder.setStyle(new NotificationCompat.BigTextStyle().bigText(contentText));
            Intent contentIntent = new Intent(context, DoingNotificationActionReceiver.class);
            if (doingState == DoingService.STATE_FAILED_CARELESS) {
                contentIntent.setAction(DoingNotificationActionReceiver.ACTION_STOP_SERVICE);
            } else if (doingState == DoingService.STATE_FAILED_NEXT_ALARM) {
                builder.setAutoCancel(true);
            }
            builder.setContentIntent(PendingIntent.getBroadcast(
                    context, (int) thingId, contentIntent, PendingIntent.FLAG_UPDATE_CURRENT));
        }
        return builder.build();
    }

    private static String getDoingNotificationTitle(
            Context context, Thing thing, @DoingService.State int doingState) {
        StringBuilder nTitle = new StringBuilder();
        if (doingState == DoingService.STATE_DOING) {
            nTitle.append(context.getString(R.string.doing_currently_doing)).append(" ");
        }
        String thingTitle = thing.getTitleToDisplay();
        if (!thingTitle.isEmpty()) {
            nTitle.append(thingTitle);
        } else {
            String thingContent = thing.getContent();
            if (!thingContent.isEmpty()) {
                if (CheckListHelper.isCheckListStr(thingContent)) {
                    thingContent = CheckListHelper.toContentStr(thingContent, "X ", "√ ");
                    thingContent = thingContent.replaceAll("\n", "\n  ");
                }
                nTitle.append(thingContent);
            } else {
                // there should be attachment
                String attachment = thing.getAttachment();
                if (!attachment.isEmpty() && !"to QQ".equals(attachment)) {
                    String imgStr = AttachmentHelper.getImageAttachmentCountStr(attachment, context);
                    if (imgStr != null) {
                        nTitle.append(imgStr).append(", ");
                    }
                    String audStr = AttachmentHelper.getAudioAttachmentCountStr(attachment, context);
                    if (audStr != null) {
                        nTitle.append(audStr);
                    }
                }
            }
        }
        return nTitle.toString();
    }

    private static String getDoingNotificationContent(
            Context context, @DoingService.State int doingState, String leftTimeStr) {
        if (doingState == DoingService.STATE_DOING) {
            return context.getString(R.string.doing_left_time) + " " + leftTimeStr;
        } else {
            String between = LocaleUtil.isChinese(context) ? ", " : ". ";
            String part1 = "";
            if (doingState == DoingService.STATE_FAILED_CARELESS) {
                part1 = context.getString(R.string.doing_failed_careless);
            } else if (doingState == DoingService.STATE_FAILED_NEXT_ALARM) {
                part1 = context.getString(R.string.doing_failed_next_alarm);
            }
            return part1 + between + context.getString(R.string.doing_click_to_dismiss);
        }
    }

    public static void tryToCreateThingOngoingNotification(Context context) {
        long curOngoingId = FrequentSettings.getLong(Def.Meta.KEY_ONGOING_THING_ID);
        if (curOngoingId != -1) {
            Thing thing = App.getThingAndPosition(context, curOngoingId, -1).first;
            if (thing != null) {
                createThingOngoingNotification(context, thing);
            }
        }
    }

    public static void createThingOngoingNotification(Context context, Thing thing) {
        long id = thing.getId();
        NotificationCompat.Builder builder = newGeneralNotificationBuilder(
                context, App.class.getName(), id, -1, thing, false);
        boolean showOnLockscreen = FrequentSettings.getBoolean(Def.Meta.KEY_ONGOING_LOCKSCREEN);
        builder.setPriority(showOnLockscreen ? Notification.PRIORITY_DEFAULT : Notification.PRIORITY_MIN)
                .setSound(null)
                .setDefaults(0)
                .setOngoing(true)
                .setAutoCancel(false);

        @Thing.Type int thingType = thing.getType();
        if (Thing.isReminderType(thingType) || thingType == Thing.NOTE) {
            Intent finishIntent = new Intent(context, ReminderNotificationActionReceiver.class);
            finishIntent.setAction(Def.Communication.NOTIFICATION_ACTION_FINISH);
            finishIntent.putExtra(Def.Communication.KEY_ID, id);
            finishIntent.putExtra(Def.Communication.KEY_POSITION, -1);
            builder.addAction(R.drawable.act_finish, context.getString(R.string.act_finish),
                    PendingIntent.getBroadcast(context,
                            (int) id, finishIntent, PendingIntent.FLAG_UPDATE_CURRENT));

            Intent startIntent = new Intent(context, ReminderNotificationActionReceiver.class);
            startIntent.setAction(Def.Communication.NOTIFICATION_ACTION_START_DOING);
            startIntent.putExtra(Def.Communication.KEY_ID, id);
            startIntent.putExtra(Def.Communication.KEY_POSITION, -1);
            builder.addAction(R.drawable.act_start_doing,
                    context.getString(R.string.act_start_doing),
                    PendingIntent.getBroadcast(context,
                            (int) id, startIntent, PendingIntent.FLAG_UPDATE_CURRENT));
        } else if (thingType == Thing.HABIT) {
            Intent finishIntent = new Intent(context, HabitNotificationActionReceiver.class);
            finishIntent.setAction(Def.Communication.NOTIFICATION_ACTION_FINISH);
            finishIntent.putExtra(Def.Communication.KEY_ID, -1); // hrId -> -1
            finishIntent.putExtra(Def.Communication.KEY_POSITION, -1);
            finishIntent.putExtra(Def.Communication.KEY_TIME, -1); // hrTime -> -1
            builder.addAction(R.drawable.act_finish, context.getString(R.string.act_finish_once_habit),
                    PendingIntent.getBroadcast(context,
                            (int) id, finishIntent, PendingIntent.FLAG_UPDATE_CURRENT));

            Intent startIntent = new Intent(context, HabitNotificationActionReceiver.class);
            startIntent.setAction(Def.Communication.NOTIFICATION_ACTION_START_DOING);
            startIntent.putExtra(Def.Communication.KEY_ID, -1);
            startIntent.putExtra(Def.Communication.KEY_POSITION, -1);
            finishIntent.putExtra(Def.Communication.KEY_TIME, -1);
            builder.addAction(R.drawable.act_start_doing,
                    context.getString(R.string.act_start_doing),
                    PendingIntent.getBroadcast(context,
                            (int) id, startIntent, PendingIntent.FLAG_UPDATE_CURRENT));
        }

        int idToNotify = (int) id;
        idToNotify *= -1;
        NotificationManagerCompat.from(context).notify(idToNotify, builder.build());
    }

    public static void cancelThingOngoingNotification(Context context, long id) {
        int idToCancel = (int) id;
        idToCancel *= -1;
        NotificationManagerCompat.from(context).cancel(idToCancel);
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
        RingtoneManager rm = new RingtoneManager(context);
        rm.setType(RingtoneManager.TYPE_NOTIFICATION);
        Uri defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        if (defaultUri == null) {
            defaultUri = rm.getRingtoneUri(0);
        }
        if (uriStr.equals(fs)) {
            return defaultUri;
        } else {
            Uri uri = Uri.parse(Uri.decode(uriStr));
            if (uri != defaultUri && rm.getRingtonePosition(uri) == -1) { // user's ringtone
                String pathName = UriPathConverter.getLocalPathName(context, uri);
                if (pathName == null || !new File(pathName).exists()) {
                    preferences.edit().putString(key, fs).apply();
                    return defaultUri;
                } else if (DeviceUtil.hasNougatApi()) {
                    uri = FileProvider.getUriForFile(
                            context, Def.Meta.APP_AUTHORITY, new File(pathName));
                }
            }
            return uri;
        }
    }

}
