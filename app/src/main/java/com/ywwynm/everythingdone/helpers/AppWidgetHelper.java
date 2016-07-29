package com.ywwynm.everythingdone.helpers;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.animation.GlideAnimation;
import com.bumptech.glide.request.target.SimpleTarget;
import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.activities.DetailActivity;
import com.ywwynm.everythingdone.database.HabitDAO;
import com.ywwynm.everythingdone.database.ReminderDAO;
import com.ywwynm.everythingdone.model.Habit;
import com.ywwynm.everythingdone.model.Reminder;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.utils.DateTimeUtil;
import com.ywwynm.everythingdone.utils.DisplayUtil;

/**
 * Created by ywwynm on 2016/7/27.
 * helper class for app widgets, especially for showing thing
 */
public class AppWidgetHelper {

    public static final String TAG = "AppWidgetHelper";

    private static final float screenDensity = DisplayUtil.getScreenDensity(App.getApp());

    private static final int LL_WIDGET_THING          = R.id.ll_widget_thing;
    private static final int V_PADDING_BOTTOM         = R.id.view_thing_padding_bottom;

    private static final int FL_IMAGE_ATTACHMENT      = R.id.fl_image_attachment;
    private static final int IV_IMAGE_ATTACHMENT      = R.id.iv_image_attachment;
    private static final int TV_IMAGE_COUNT           = R.id.tv_thing_image_attachment_count;

    private static final int TV_TITLE                 = R.id.tv_thing_title;
    private static final int IV_PRIVATE_THING         = R.id.iv_private_thing;

    private static final int TV_CONTENT               = R.id.tv_thing_content;
    private static final int LV_CHECKLIST             = R.id.lv_check_list;

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
    private static final int LL_HABIT_RECORD          = R.id.ll_habit_record;
    private static final int TV_HABIT_LAST_FIVE       = R.id.tv_thing_habit_last_five_record;
    private static final int TV_HABIT_FINISHED_THIS_T = R.id.tv_thing_habit_finished_this_t;

    private AppWidgetHelper() {}

    public static RemoteViews createRemoteViewsForSingleThing(
            Context context, Thing thing, int position) {
        RemoteViews remoteViews = new RemoteViews(context.getPackageName(), R.layout.app_widget_thing);
        setAppearance(context, remoteViews, thing);
        final Intent contentIntent = DetailActivity.getOpenIntentForUpdate(
                context, TAG, thing.getId(), position);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, (int) thing.getId(), contentIntent, 0);
        remoteViews.setOnClickPendingIntent(R.id.ll_widget_thing, pendingIntent);
        return remoteViews;
    }

    public static void setAppearance(Context context, RemoteViews remoteViews, Thing thing) {
        remoteViews.setInt(LL_WIDGET_THING, "setBackgroundColor", thing.getColor());

        setTitleAndPrivate(remoteViews, thing);
        setContent(remoteViews, thing);
        setReminder(context, remoteViews, thing);
        setHabit(context, remoteViews, thing);
        setAudioAttachment(context, remoteViews, thing);
        setImageAttachment(context, remoteViews, thing);
    }

    private static void setTitleAndPrivate(RemoteViews remoteViews, Thing thing) {
        String title = thing.getTitleToDisplay();
        if (!title.isEmpty()) {
            int p = (int) (screenDensity * 16);
            remoteViews.setViewVisibility(TV_TITLE, View.VISIBLE);
            remoteViews.setViewPadding(TV_TITLE, p, p, p, 0);
            remoteViews.setTextViewText(TV_TITLE, title);
        } else {
            remoteViews.setViewVisibility(TV_TITLE, View.GONE);
        }

        if (thing.isPrivate()) {
            remoteViews.setViewVisibility(IV_PRIVATE_THING,    View.VISIBLE);
            remoteViews.setViewVisibility(IV_IMAGE_ATTACHMENT, View.GONE);
            remoteViews.setViewVisibility(TV_CONTENT,          View.GONE);
            remoteViews.setViewVisibility(LV_CHECKLIST,        View.GONE);
            remoteViews.setViewVisibility(LL_AUDIO_ATTACHMENT, View.GONE);
            remoteViews.setViewVisibility(RL_REMINDER,         View.GONE);
            remoteViews.setViewVisibility(RL_HABIT,            View.GONE);
        } else {
            remoteViews.setViewVisibility(IV_PRIVATE_THING,    View.GONE);
        }
    }

    private static void setContent(RemoteViews remoteViews, Thing thing) {
        int p = (int) (screenDensity * 16);
        String content = thing.getContent();
        if (!content.isEmpty()) {
            if (!CheckListHelper.isCheckListStr(content)) {
                remoteViews.setViewVisibility(LV_CHECKLIST, View.GONE);
                remoteViews.setViewVisibility(TV_CONTENT,   View.VISIBLE);
                // TODO: 2016/7/28 always 16sp
                remoteViews.setViewPadding(TV_CONTENT, p, p, p, 0);
                remoteViews.setTextViewText(TV_CONTENT, content);
            } else {
                remoteViews.setViewVisibility(LV_CHECKLIST, View.VISIBLE);
                remoteViews.setViewVisibility(TV_CONTENT,   View.GONE);

//                long id = thing.getId();
//                List<String> items = CheckListHelper.toCheckListItems(content, false);
//                CheckListAdapter adapter = mCheckListAdapters.get(id);
//                if (adapter == null) {
//                    adapter = new CheckListAdapter(mApp,
//                            CheckListAdapter.TEXTVIEW, items);
//                    mCheckListAdapters.put(id, adapter);
//                } else {
//                    adapter.setItems(items);
//                }
//                holder.rvChecklist.setAdapter(adapter);
//                holder.rvChecklist.setLayoutManager(new LinearLayoutManager());

                int rp = (int) (screenDensity * 6);
                remoteViews.setViewPadding(LV_CHECKLIST, rp, p, p, 0);
            }
        } else {
            remoteViews.setViewVisibility(LV_CHECKLIST, View.GONE);
            remoteViews.setViewVisibility(TV_CONTENT,   View.GONE);
        }
    }

    private static void setReminder(Context context, RemoteViews remoteViews, Thing thing) {
        int thingType = thing.getType();
        int thingState = thing.getState();
        Reminder reminder = ReminderDAO.getInstance(context).getReminderById(thing.getId());
        if (reminder != null) {
            int state = reminder.getState();
            long notifyTime = reminder.getNotifyTime();

            int p = (int) (screenDensity * 16);
            remoteViews.setViewVisibility(RL_REMINDER, View.VISIBLE);
            remoteViews.setViewPadding(RL_REMINDER, p, p, p, 0);

            if (thingType == Thing.REMINDER) {
//                params.setMargins(0, (int) (mScreenDensity * 2), 0, 0);
                remoteViews.setImageViewResource(IV_REMINDER, R.drawable.card_reminder);
                remoteViews.setTextViewTextSize(TV_REMINDER_TIME, TypedValue.COMPLEX_UNIT_SP, 12);

                String timeStr = DateTimeUtil.getDateTimeStrAt(notifyTime, context, false);
                if (timeStr.startsWith("on ")) {
                    timeStr = timeStr.substring(3, timeStr.length());
                }
                String textToSet = timeStr;
                if (thingState != Thing.UNDERWAY || state != Reminder.UNDERWAY) {
                    textToSet += Reminder.getStateDescription(thingState, state, context);
                }
                remoteViews.setTextViewText(TV_REMINDER_TIME, textToSet);
            } else {
//                params.setMargins(0, (int) (mScreenDensity * 1.6), 0, 0);
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
        } else {
            remoteViews.setViewVisibility(RL_REMINDER, View.GONE);
        }
    }

    private static void setHabit(Context context, RemoteViews remoteViews, Thing thing) {
        Habit habit = HabitDAO.getInstance(context).getHabitById(thing.getId());
        if (habit != null) {
            int p = (int) (screenDensity * 16);
            remoteViews.setViewVisibility(RL_HABIT, View.VISIBLE);
            remoteViews.setViewPadding(RL_HABIT, p, p, p, 0);

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
                // TODO: 2016/7/28 last five appearance
//                holder.habitRecordPresenter.setRecord(lastFive.toString());

                remoteViews.setTextViewText(TV_HABIT_FINISHED_THIS_T,
                        habit.getFinishedTimesThisTStr(context));
            } else {
                remoteViews.setViewVisibility(TV_HABIT_NEXT_REMINDER,   View.GONE);
                remoteViews.setViewVisibility(V_HABIT_SEPARATOR_2,      View.GONE);
                remoteViews.setViewVisibility(TV_HABIT_LAST_FIVE,       View.GONE);
                remoteViews.setViewVisibility(LL_HABIT_RECORD,          View.GONE);
                remoteViews.setViewVisibility(TV_HABIT_FINISHED_THIS_T, View.GONE);
            }
        } else {
            remoteViews.setViewVisibility(RL_HABIT, View.GONE);
        }
    }

    private static void setAudioAttachment(Context context, RemoteViews remoteViews, Thing thing) {
        String attachment = thing.getAttachment();
        String str = AttachmentHelper.getAudioAttachmentCountStr(attachment, context);
        if (str == null) {
            remoteViews.setViewVisibility(LL_AUDIO_ATTACHMENT, View.GONE);
        } else {
            remoteViews.setViewVisibility(LL_AUDIO_ATTACHMENT, View.VISIBLE);
            int p = (int) (screenDensity * 16);
            remoteViews.setViewPadding(LL_AUDIO_ATTACHMENT, p, p / 4 * 3, p, 0);

            remoteViews.setTextViewText(TV_AUDIO_COUNT, str);
        }
    }

    private static void setImageAttachment(Context context, final RemoteViews remoteViews, Thing thing) {
        String attachment = thing.getAttachment();
        String firstImageTypePathName = AttachmentHelper.getFirstImageTypePathName(attachment);
        if (firstImageTypePathName != null) {
            remoteViews.setViewVisibility(FL_IMAGE_ATTACHMENT, View.VISIBLE);

            String pathName = firstImageTypePathName.substring(1, firstImageTypePathName.length());
            Glide.with(context)
                    .load(pathName)
                    .asBitmap()
                    .centerCrop()
                    .into(new SimpleTarget<Bitmap>() {
                        @Override
                        public void onResourceReady(
                                Bitmap resource, GlideAnimation<? super Bitmap> glideAnimation) {
                            remoteViews.setImageViewBitmap(IV_IMAGE_ATTACHMENT, resource);
                        }
                    });

            // if thing has only an image/video, there should be no margins for ImageView
//            if (holder.tvTitle.getVisibility() == View.GONE
//                    && holder.tvContent.getVisibility() == View.GONE
//                    && holder.rvChecklist.getVisibility() == View.GONE
//                    && holder.llAudioAttachment.getVisibility() == View.GONE
//                    && holder.rlReminder.getVisibility() == View.GONE) {
//                holder.vPaddingBottom.setVisibility(View.GONE);
//            } else {
//                holder.vPaddingBottom.setVisibility(View.VISIBLE);
//            }

            remoteViews.setTextViewText(TV_IMAGE_COUNT,
                    AttachmentHelper.getImageAttachmentCountStr(attachment, context));
        } else {
            remoteViews.setViewVisibility(V_PADDING_BOTTOM, View.VISIBLE);
            remoteViews.setViewVisibility(FL_IMAGE_ATTACHMENT, View.GONE);
        }
    }

}
