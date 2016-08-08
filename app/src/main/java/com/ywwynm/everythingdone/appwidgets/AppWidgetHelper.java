package com.ywwynm.everythingdone.appwidgets;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.AppWidgetTarget;
import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.activities.DetailActivity;
import com.ywwynm.everythingdone.appwidgets.single.BaseThingWidget;
import com.ywwynm.everythingdone.appwidgets.single.ThingWidgetLarge;
import com.ywwynm.everythingdone.appwidgets.single.ThingWidgetMiddle;
import com.ywwynm.everythingdone.appwidgets.single.ThingWidgetSmall;
import com.ywwynm.everythingdone.appwidgets.single.ThingWidgetTiny;
import com.ywwynm.everythingdone.database.AppWidgetDAO;
import com.ywwynm.everythingdone.database.HabitDAO;
import com.ywwynm.everythingdone.database.ReminderDAO;
import com.ywwynm.everythingdone.helpers.AttachmentHelper;
import com.ywwynm.everythingdone.helpers.CheckListHelper;
import com.ywwynm.everythingdone.model.Habit;
import com.ywwynm.everythingdone.model.Reminder;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.model.ThingWidgetInfo;
import com.ywwynm.everythingdone.services.ChecklistWidgetService;
import com.ywwynm.everythingdone.services.ThingsListWidgetService;
import com.ywwynm.everythingdone.utils.DateTimeUtil;
import com.ywwynm.everythingdone.utils.DisplayUtil;

import java.util.List;

/**
 * Created by ywwynm on 2016/7/27.
 * helper class for app widgets, especially for showing thing
 */
public class AppWidgetHelper {

    public static final String TAG = "AppWidgetHelper";

    private static final float screenDensity = DisplayUtil.getScreenDensity(App.getApp());

    private static final int dp12 = (int) (screenDensity * 12);

    private static final int LL_WIDGET_THING          = R.id.rl_widget_thing;

    private static final int IV_IMAGE_ATTACHMENT      = R.id.iv_thing_image;
    private static final int TV_IMAGE_COUNT           = R.id.tv_thing_image_attachment_count;

    private static final int TV_TITLE                 = R.id.tv_thing_title;
    private static final int IV_PRIVATE_THING         = R.id.iv_private_thing;

    private static final int TV_CONTENT               = R.id.tv_thing_content;
    private static final int LV_CHECKLIST             = R.id.lv_thing_check_list;

    private static final int LL_AUDIO_ATTACHMENT      = R.id.ll_thing_audio_attachment;
    private static final int TV_AUDIO_COUNT           = R.id.tv_thing_audio_attachment_count;

    private static final int RL_REMINDER              = R.id.rl_thing_reminder;
    private static final int V_REMINDER_SEPARATOR     = R.id.view_reminder_separator;
    private static final int IV_REMINDER              = R.id.iv_thing_reminder;
    private static final int TV_REMINDER_TIME         = R.id.tv_thing_reminder_time;

    private static final int RL_HABIT                 = R.id.rl_thing_habit;
    private static final int V_HABIT_SEPARATOR_1      = R.id.view_habit_separator_1;
    private static final int TV_HABIT_SUMMARY         = R.id.tv_thing_habit_summary;
    private static final int TV_HABIT_NEXT_REMINDER   = R.id.tv_thing_habit_next_reminder;
    private static final int V_HABIT_SEPARATOR_2      = R.id.view_habit_separator_2;
    private static final int LL_HABIT_RECORD          = R.id.ll_thing_habit_record;
    private static final int TV_HABIT_LAST_FIVE       = R.id.tv_thing_habit_last_five_record;
    private static final int TV_HABIT_FINISHED_THIS_T = R.id.tv_thing_habit_finished_this_t;

    private static final int LL_THING_STATE           = R.id.ll_thing_state;
    private static final int V_STATE_SEPARATOR        = R.id.view_state_separator;
    private static final int TV_THING_STATE           = R.id.tv_thing_state;

    private static final int V_PADDING_BOTTOM         = R.id.view_thing_padding_bottom;

    private AppWidgetHelper() {}

    public static void updateAppWidget(Context context, long thingId) {
        // TODO: 2016/8/2 when there is things list widget, we should also update it, too.
        AppWidgetDAO appWidgetDAO = AppWidgetDAO.getInstance(context);
        List<ThingWidgetInfo> thingWidgetInfos = appWidgetDAO.getThingWidgetInfosByThingId(thingId);
        for (ThingWidgetInfo thingWidgetInfo : thingWidgetInfos) {
            Intent intent = new Intent(context, getProviderClassBySize(thingWidgetInfo.getSize()));
            intent.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS,
                    new int[] { thingWidgetInfo.getId() });
            context.sendBroadcast(intent);
        }
    }

    public static Class getProviderClassBySize(@ThingWidgetInfo.Size int size) {
        if (size == ThingWidgetInfo.SIZE_TINY) {
            return ThingWidgetTiny.class;
        } else if (size == ThingWidgetInfo.SIZE_SMALL) {
            return ThingWidgetSmall.class;
        } else if (size == ThingWidgetInfo.SIZE_MIDDLE) {
            return ThingWidgetMiddle.class;
        } else if (size == ThingWidgetInfo.SIZE_LARGE) {
            return ThingWidgetLarge.class;
        }
        return BaseThingWidget.class;
    }

    public static @ThingWidgetInfo.Size int getSizeByProviderClass(Class clazz) {
        if (clazz.equals(ThingWidgetTiny.class)) {
            return ThingWidgetInfo.SIZE_TINY;
        } else if (clazz.equals(ThingWidgetSmall.class)) {
            return ThingWidgetInfo.SIZE_SMALL;
        } else if (clazz.equals(ThingWidgetMiddle.class)) {
            return ThingWidgetInfo.SIZE_MIDDLE;
        } else if (clazz.equals(ThingWidgetLarge.class)) {
            return ThingWidgetInfo.SIZE_LARGE;
        }
        return ThingWidgetInfo.SIZE_MIDDLE;
    }

    public static RemoteViews createRemoteViewsForSingleThing(
            Context context, Thing thing, int position, int appWidgetId, Class clazz) {
        RemoteViews remoteViews = new RemoteViews(context.getPackageName(), R.layout.app_widget_thing);
        setAppearance(context, remoteViews, thing, appWidgetId, clazz);
        final Intent contentIntent = DetailActivity.getOpenIntentForUpdate(
                context, TAG, thing.getId(), position);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, (int) thing.getId(), contentIntent, 0);
        remoteViews.setOnClickPendingIntent(R.id.rl_widget_thing, pendingIntent);
        return remoteViews;
    }

    public static RemoteViews createRemoteViewsForThingsList(Context context, int limit, int appWidgetId) {
        RemoteViews remoteViews = new RemoteViews(context.getPackageName(), R.layout.app_widget_things_list);
        Intent intent = new Intent(context, ThingsListWidgetService.class);
        intent.putExtra(Def.Communication.KEY_LIMIT, limit);
        intent.putExtra(Def.Communication.KEY_WIDGET_ID, appWidgetId);
        remoteViews.setRemoteAdapter(R.id.lv_things_list, intent);

        intent = new Intent(context, DetailActivity.class);
        intent.putExtra(Def.Communication.KEY_SENDER_NAME, TAG);
        intent.putExtra(Def.Communication.KEY_DETAIL_ACTIVITY_TYPE,
                DetailActivity.UPDATE);
        intent.setData(Uri.parse(intent.toUri(Intent.URI_INTENT_SCHEME)));
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
        remoteViews.setPendingIntentTemplate(R.id.lv_things_list, pendingIntent);

        return remoteViews;
    }

    private static void setAppearance(
            Context context, RemoteViews remoteViews, Thing thing, int appWidgetId, Class clazz) {
        remoteViews.setInt(LL_WIDGET_THING, "setBackgroundColor", thing.getColor());

        setImageAttachment(context, remoteViews, thing, appWidgetId);
        setTitleAndPrivate(remoteViews, thing);
        setContent(context, remoteViews, thing, appWidgetId, clazz);
        setAudioAttachment(context, remoteViews, thing);
        setState(context, remoteViews, thing);

        setReminder(context, remoteViews, thing);
        setHabit(context, remoteViews, thing);
    }

    private static void setSeparatorVisibilities(
            RemoteViews remoteViews, int visibility) {
        remoteViews.setViewVisibility(V_STATE_SEPARATOR,    visibility);
        remoteViews.setViewVisibility(V_REMINDER_SEPARATOR, visibility);
        remoteViews.setViewVisibility(V_HABIT_SEPARATOR_1,  visibility);
    }

    private static void setImageAttachment(
            Context context, final RemoteViews remoteViews, Thing thing, int appWidgetId) {
        if (thing.isPrivate()) {
            remoteViews.setViewVisibility(IV_IMAGE_ATTACHMENT,  View.GONE);
            remoteViews.setViewVisibility(TV_IMAGE_COUNT,       View.GONE);
            remoteViews.setViewVisibility(V_PADDING_BOTTOM,     View.VISIBLE);
            return;
        }

        String attachment = thing.getAttachment();
        String firstImageTypePathName = AttachmentHelper.getFirstImageTypePathName(attachment);
        if (firstImageTypePathName == null) {
            remoteViews.setViewVisibility(IV_IMAGE_ATTACHMENT,  View.GONE);
            remoteViews.setViewVisibility(TV_IMAGE_COUNT,       View.GONE);
            remoteViews.setViewVisibility(V_PADDING_BOTTOM, View.VISIBLE);
            return;
        }

        remoteViews.setViewVisibility(IV_IMAGE_ATTACHMENT,  View.VISIBLE);
        remoteViews.setViewVisibility(TV_IMAGE_COUNT,       View.VISIBLE);

        final String pathName = firstImageTypePathName.substring(1, firstImageTypePathName.length());
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(pathName, options);

//        Glide.with(context)
//                .load(pathName)
//                .asBitmap()
//                .override(options.outWidth, options.outWidth * 3 / 4)
//                .centerCrop()
//                .into(new SimpleTarget<Bitmap>() {
//                    @Override
//                    public void onResourceReady(Bitmap resource, GlideAnimation<? super Bitmap> glideAnimation) {
//                        remoteViews.setImageViewBitmap(IV_IMAGE_ATTACHMENT, resource);
//                    }
//                });
//        Glide.with(context)
//                .load(pathName)
//                .asBitmap()
//                .override(options.outWidth, options.outWidth * 3 / 4)
//                .centerCrop()
//                .into(new AppWidgetTarget(
//                        context, remoteViews, IV_IMAGE_ATTACHMENT, new int[] { appWidgetId }));

        remoteViews.setTextViewText(TV_IMAGE_COUNT,
                AttachmentHelper.getImageAttachmentCountStr(attachment, context));

        remoteViews.setViewVisibility(V_PADDING_BOTTOM, View.GONE);
        setSeparatorVisibilities(remoteViews, View.GONE);
    }

    private static void setTitleAndPrivate(RemoteViews remoteViews, Thing thing) {
        String title = thing.getTitleToDisplay();
        if (!title.isEmpty()) {
            remoteViews.setViewVisibility(TV_TITLE, View.VISIBLE);
            remoteViews.setTextViewText(TV_TITLE, title);
            remoteViews.setViewPadding(TV_TITLE, dp12, dp12, dp12, 0);
            remoteViews.setViewVisibility(V_PADDING_BOTTOM, View.VISIBLE);
            setSeparatorVisibilities(remoteViews, View.VISIBLE);
        } else {
            remoteViews.setViewVisibility(TV_TITLE, View.GONE);
        }

        if (thing.isPrivate()) {
            remoteViews.setViewVisibility(IV_PRIVATE_THING, View.VISIBLE);
        } else {
            remoteViews.setViewVisibility(IV_PRIVATE_THING, View.GONE);
        }
    }

    private static void setContent(
            Context context, RemoteViews remoteViews, Thing thing, int appWidgetId, Class clazz) {
        String content = thing.getContent();
        if (content.isEmpty() || thing.isPrivate()) {
            remoteViews.setViewVisibility(LV_CHECKLIST, View.GONE);
            remoteViews.setViewVisibility(TV_CONTENT,   View.GONE);
            return;
        }

        if (!CheckListHelper.isCheckListStr(content)) {
            remoteViews.setViewVisibility(LV_CHECKLIST, View.GONE);
            remoteViews.setViewVisibility(TV_CONTENT,   View.VISIBLE);
            remoteViews.setViewPadding(TV_CONTENT, dp12, dp12, dp12, 0);
            remoteViews.setTextViewText(TV_CONTENT, content);
            int length = content.length();
            if (length <= 60) {
                remoteViews.setTextViewTextSize(TV_CONTENT, TypedValue.COMPLEX_UNIT_SP,
                        -0.14f * length + 24.14f);
            } else {
                remoteViews.setTextViewTextSize(TV_CONTENT, TypedValue.COMPLEX_UNIT_SP, 16);
            }
        } else {
            remoteViews.setViewVisibility(LV_CHECKLIST, View.VISIBLE);
            remoteViews.setViewVisibility(TV_CONTENT,   View.GONE);

            Intent intent = new Intent(context, ChecklistWidgetService.class);
            intent.putExtra(Def.Communication.KEY_WIDGET_ID, appWidgetId);
            intent.putExtra(Def.Communication.KEY_ID, thing.getId());
            intent.setData(Uri.parse(intent.toUri(Intent.URI_INTENT_SCHEME)));
            remoteViews.setRemoteAdapter(LV_CHECKLIST, intent);

            /**
             * see {@link ChecklistWidgetService.ChecklistViewFactory#setupEvents(RemoteViews, int)}
             */
            intent = new Intent(context, clazz);
            intent.setAction(Def.Communication.BROADCAST_ACTION_UPDATE_CHECKLIST);
            intent.setData(Uri.parse(intent.toUri(Intent.URI_INTENT_SCHEME)));
            PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT);
            remoteViews.setPendingIntentTemplate(R.id.lv_thing_check_list, pendingIntent);

            remoteViews.setViewPadding(LV_CHECKLIST, dp12, dp12, dp12, 0);
        }

        remoteViews.setViewVisibility(V_PADDING_BOTTOM, View.VISIBLE);
        setSeparatorVisibilities(remoteViews, View.VISIBLE);
    }

    private static void setAudioAttachment(Context context, RemoteViews remoteViews, Thing thing) {
        String attachment = thing.getAttachment();
        String str = AttachmentHelper.getAudioAttachmentCountStr(attachment, context);
        if (str == null) {
            remoteViews.setViewVisibility(LL_AUDIO_ATTACHMENT, View.GONE);
            return;
        }

        remoteViews.setViewVisibility(LL_AUDIO_ATTACHMENT, View.VISIBLE);
        remoteViews.setViewPadding(LL_AUDIO_ATTACHMENT, dp12, (int) (screenDensity * 9), dp12, 0);
        remoteViews.setTextViewText(TV_AUDIO_COUNT, str);

        remoteViews.setViewVisibility(V_PADDING_BOTTOM, View.VISIBLE);
        setSeparatorVisibilities(remoteViews, View.VISIBLE);
    }

    private static void setState(Context context, RemoteViews remoteViews, Thing thing) {
        @Thing.State int state = thing.getState();
        if (state != Thing.UNDERWAY) {
            remoteViews.setViewVisibility(LL_THING_STATE, View.VISIBLE);
            remoteViews.setTextViewText(TV_THING_STATE, Thing.getStateStr(state, context));
            remoteViews.setViewVisibility(V_PADDING_BOTTOM, View.VISIBLE);
        } else {
            remoteViews.setViewVisibility(LL_THING_STATE, View.GONE);
        }
    }

    private static void setReminder(Context context, RemoteViews remoteViews, Thing thing) {
        int thingType = thing.getType();
        int thingState = thing.getState();
        Reminder reminder = ReminderDAO.getInstance(context).getReminderById(thing.getId());
        if (reminder == null) {
            remoteViews.setViewVisibility(RL_REMINDER, View.GONE);
            return;
        }

        int state = reminder.getState();
        long notifyTime = reminder.getNotifyTime();

        remoteViews.setViewVisibility(RL_REMINDER, View.VISIBLE);
        remoteViews.setViewPadding(RL_REMINDER, dp12, dp12, dp12, 0);

        if (thingType == Thing.REMINDER) {
            remoteViews.setViewPadding(IV_REMINDER, 0, (int) (screenDensity * 2), 0, 0);
            remoteViews.setImageViewResource(IV_REMINDER, R.drawable.card_reminder);
            remoteViews.setTextViewTextSize(TV_REMINDER_TIME, TypedValue.COMPLEX_UNIT_SP, 12);

            String timeStr = DateTimeUtil.getDateTimeStrAt(notifyTime, context, false);
            if (timeStr.startsWith("on ")) {
                timeStr = timeStr.substring(3, timeStr.length());
            }
            String textToSet = timeStr;
            if (thingState != Thing.UNDERWAY || state != Reminder.UNDERWAY) {
                textToSet += ", " + Reminder.getStateDescription(thingState, state, context);
            }
            remoteViews.setTextViewText(TV_REMINDER_TIME, textToSet);
        } else {
            remoteViews.setViewPadding(IV_REMINDER, 0, (int) (screenDensity * 1.6), 0, 0);
            remoteViews.setImageViewResource(IV_REMINDER, R.drawable.card_goal);
            remoteViews.setTextViewTextSize(TV_REMINDER_TIME, TypedValue.COMPLEX_UNIT_SP, 16);

            if (thingState == Reminder.UNDERWAY && state == Reminder.UNDERWAY) {
                remoteViews.setTextViewText(TV_REMINDER_TIME,
                        DateTimeUtil.getDateTimeStrGoal(notifyTime, context));
            } else {
                remoteViews.setTextViewText(TV_REMINDER_TIME,
                        Reminder.getStateDescription(thingState, state, context));
            }
        }

        remoteViews.setViewVisibility(LL_THING_STATE, View.GONE);
        remoteViews.setViewVisibility(V_PADDING_BOTTOM, View.VISIBLE);
    }

    private static void setHabit(Context context, RemoteViews remoteViews, Thing thing) {
        Habit habit = HabitDAO.getInstance(context).getHabitById(thing.getId());
        if (habit == null)  {
            remoteViews.setViewVisibility(RL_HABIT, View.GONE);
            return;
        }

        remoteViews.setViewVisibility(RL_HABIT, View.VISIBLE);
        remoteViews.setViewPadding(RL_HABIT, dp12, dp12, dp12, 0);

        remoteViews.setTextViewText(TV_HABIT_SUMMARY, habit.getSummary(context));

        if (thing.getState() == Thing.UNDERWAY) {
            remoteViews.setViewVisibility(TV_HABIT_NEXT_REMINDER,   View.VISIBLE);
            remoteViews.setViewVisibility(V_HABIT_SEPARATOR_2,      View.VISIBLE);
            remoteViews.setViewVisibility(TV_HABIT_LAST_FIVE,       View.VISIBLE);
            remoteViews.setViewVisibility(LL_HABIT_RECORD,          View.VISIBLE);
            remoteViews.setViewVisibility(TV_HABIT_FINISHED_THIS_T, View.VISIBLE);

            String next = context.getString(R.string.habit_next_reminder);
            remoteViews.setTextViewText(TV_HABIT_NEXT_REMINDER,
                    next + " " + habit.getNextReminderDescription(context));

            String record = habit.getRecord();
            StringBuilder lastFive;
            int len = record.length();
            if (len >= 5) {
                lastFive = new StringBuilder(record.substring(len - 5, len));
            } else {
                lastFive = new StringBuilder(record);
                for (int i = 0; i < 5 - len; i++) {
                    lastFive.append("?");
                }
            }
            setHabitLastFive(remoteViews, lastFive.toString());

            remoteViews.setTextViewText(TV_HABIT_FINISHED_THIS_T,
                    habit.getFinishedTimesThisTStr(context));
        } else {
            remoteViews.setViewVisibility(TV_HABIT_NEXT_REMINDER,   View.GONE);
            remoteViews.setViewVisibility(V_HABIT_SEPARATOR_2,      View.GONE);
            remoteViews.setViewVisibility(TV_HABIT_LAST_FIVE,       View.GONE);
            remoteViews.setViewVisibility(LL_HABIT_RECORD,          View.GONE);
            remoteViews.setViewVisibility(TV_HABIT_FINISHED_THIS_T, View.GONE);
        }

        remoteViews.setViewVisibility(LL_THING_STATE, View.GONE);
        remoteViews.setViewVisibility(V_PADDING_BOTTOM, View.VISIBLE);
    }

    private static void setHabitLastFive(RemoteViews remoteViews, String lastFive) {
        int[] ids = {
                R.id.iv_thing_habit_record_1,
                R.id.iv_thing_habit_record_2,
                R.id.iv_thing_habit_record_3,
                R.id.iv_thing_habit_record_4,
                R.id.iv_thing_habit_record_5
        };
        char[] states = lastFive.toCharArray();
        for (int i = 0; i < states.length; i++) {
            if (states[i] == '0') {
                remoteViews.setImageViewResource(ids[i], R.drawable.card_habit_unfinished);
            } else if (states[i] == '1') {
                remoteViews.setImageViewResource(ids[i], R.drawable.card_habit_finished);
            } else {
                remoteViews.setImageViewResource(ids[i], R.drawable.card_habit_unknown);
            }
        }
    }

}
