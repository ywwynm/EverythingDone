package com.ywwynm.everythingdone.helpers;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import com.ywwynm.everythingdone.Definitions;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.model.Habit;
import com.ywwynm.everythingdone.model.Reminder;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.database.HabitDAO;
import com.ywwynm.everythingdone.database.ReminderDAO;
import com.ywwynm.everythingdone.utils.DateTimeUtil;
import com.ywwynm.everythingdone.utils.LocaleUtil;

import java.util.Calendar;

/**
 * Created by ywwynm on 2016/2/13.
 * send information to other apps, like sharing or sending feedback.
 */
public class SendInfoHelper {

    public static final String TAG = "EverythingDone$SendInfoHelper";

    public static final String NO_PROBLEM = "no problem";

    public static void shareApp(Context context) {
        String shareStr = context.getString(R.string.act_share_everythingdone);
        String content = context.getString(R.string.app_share_info);
        startShare(context, shareStr, content);
    }

    public static String shareThing(Context context, Thing thing) {
        String description = getThingShareInfo(context, thing);
        if (description == null) {
            return context.getString(R.string.error_share_empty_title_content);
        }

        boolean isChinese = LocaleUtil.isChinese(context);
        String shareStr = context.getString(R.string.act_share);
        String thisStr = context.getString(R.string.this_gai);
        if (!isChinese) {
            shareStr = shareStr + " " + thisStr + " ";
        } else {
            shareStr += thisStr;
        }
        shareStr = shareStr + Thing.getTypeStr(thing.getType(), context);

        startShare(context, shareStr, description);

        return NO_PROBLEM;
    }

    public static void sendFeedback(Context context) {
        Intent intent = new Intent(Intent.ACTION_SENDTO);
        intent.setType("message/rfc822");
        intent.setData(Uri.parse("mailto:" + Definitions.MetaData.FEEDBACK_EMAIL));
        intent.putExtra(Intent.EXTRA_SUBJECT,
                context.getString(R.string.act_feedback) + "-" + System.currentTimeMillis()
                        + "-" + Definitions.MetaData.APP_VERSION);
        try {
            context.startActivity(Intent.createChooser(intent,
                    context.getString(R.string.send_feedback_to_developer)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(context, context.getString(R.string.error_activity_not_found),
                    Toast.LENGTH_SHORT).show();
        }
    }

    public static void rateApp(Context context) {
        Uri uri = Uri.parse("market://details?id=" + context.getPackageName());
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        try {
            context.startActivity(Intent.createChooser(
                    intent, context.getString(R.string.support_select_market)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(context, context.getString(R.string.error_activity_not_found),
                    Toast.LENGTH_SHORT).show();
        }
    }

    public static String getThingShareInfo(Context context, Thing thing) {
        String title = thing.getTitle();
        String content = thing.getContent();
        if (title.isEmpty() && content.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (!title.isEmpty()) {
            sb.append(title).append("\n\n");
        }
        if (!content.isEmpty()) {
            if (CheckListHelper.isCheckListStr(content)) {
                sb.append(CheckListHelper.toContentStr(content, "X  ", "√  "));
            } else {
                sb.append(content);
            }
            sb.append("\n\n");
        } else {
            sb.append("\n");
        }

        long id = thing.getId();
        int state = thing.getState();
        int type = thing.getType();
        if (Thing.isReminderType(type)) {
            sb.append(getReminderShareInfo(context, id, state, type == Thing.GOAL));
        } else if (type == Thing.HABIT) {
            sb.append(getHabitShareInfo(context, id, state));
        }

        if (state == Thing.FINISHED && type != Thing.HABIT) {
            sb.append("\n").append(getFinishedThingInfo(context, thing));
        }

        sb.append("\n\n").append(context.getString(R.string.from_everything_done));
        return sb.toString();
    }

    public static String getReminderShareInfo(Context context, long id, int thingState, boolean isGoal) {
        boolean isChinese = LocaleUtil.isChinese(context);
        String at = context.getString(R.string.at);
        StringBuilder sb = new StringBuilder();
        String reminderStr = context.getString(R.string.reminder);
        if (!isChinese) {
            reminderStr = "Reminder ";
        }
        String was = "was ";

        Reminder reminder = ReminderDAO.getInstance(context).getReminderById(id);
        int reminderState = reminder.getState();
        if (reminderState == Reminder.REMINDED) {
            if (isChinese) {
                sb.append(context.getString(R.string.reminder_reminded)).append(at);
            } else sb.append("Reminded ");
            sb.append(DateTimeUtil.getDateTimeStrAt(reminder.getNotifyTime(), context, true));
        } else if (reminderState == Reminder.EXPIRED) {
            sb.append(reminderStr);
            if (!isChinese) {
                sb.append(was);
            }
            sb.append(context.getString(R.string.reminder_expired));
        } else {
            if (thingState == Thing.UNDERWAY) {
                sb.append(context.getString(R.string.will_remind));
                if (!isChinese) sb.append(" ");
                if (isGoal) {
                    sb.append(DateTimeUtil.getDateTimeStrGoal(reminder.getNotifyTime(), context));
                } else {
                    sb.append(DateTimeUtil.getDateTimeStrAt(
                            reminder.getNotifyTime(), context, true));
                }
            } else if (thingState == Thing.FINISHED) {
                String notNeeded = context.getString(R.string.reminder_needless);
                if (isChinese) {
                    sb.append(notNeeded);
                } else sb.append(reminderStr).append(was).append(notNeeded);
            }
        }
        return sb.toString();
    }

    public static String getHabitShareInfo(Context context, long id, int thingState) {
        boolean isChinese = LocaleUtil.isChinese(context);
        StringBuilder sb = new StringBuilder();

        Habit habit = HabitDAO.getInstance(context).getHabitById(id);
        int type = habit.getType();
        int piT = habit.getPersistInT();

        sb.append(habit.getSummary(context)).append(", ")
                .append(context.getString(R.string.i_persist_in_for)).append(" ")
                .append(piT < 0 ? 0 : piT).append(" ")
                .append(DateTimeUtil.getTimeTypeStr(type, context));
        if (!isChinese && piT > 1) {
            sb.append("s");
        }
        sb.append(", ")
                .append(context.getString(R.string.act_finish));
        if (isChinese) {
            sb.append("了");
        }
        int finishedTimes = habit.getFinishedTimes();
        sb.append(" ").append(LocaleUtil.getTimesStr(context, finishedTimes));

        if (thingState == Thing.FINISHED) {
            sb.append(", ").append(context.getString(R.string.habit_developed));
        }

        return sb.toString();
    }

    private static String getFinishedThingInfo(Context context, Thing thing) {
        boolean isChinese = LocaleUtil.isChinese(context);
        StringBuilder sb = new StringBuilder();
        int type = thing.getType();

        if (type == Thing.GOAL) {
            Reminder goal = ReminderDAO.getInstance(context).getReminderById(thing.getId());
            int gap = DateTimeUtil.calculateTimeGap(
                    goal.getCreateTime(), thing.getFinishTime(), Calendar.DATE);
            sb.append(context.getString(R.string.i_work_hard_for)).append(" ")
                    .append(gap).append(" ").append(context.getString(R.string.days));
            if (!isChinese && gap > 1) {
                sb.append("s");
            }

            String achieve = context.getString(R.string.finished_goal);
            if (isChinese) {
                sb.append(", ").append(achieve);
            } else {
                sb.append(" ").append(achieve);
            }
            return sb.toString();
        }

        if (isChinese) {
            sb.append(context.getString(R.string.act_finish)).append(context.getString(R.string.at));
        } else {
            sb.append("Finished ");
        }

        sb.append(DateTimeUtil.getDateTimeStrAt(thing.getFinishTime(), context, true));
        return sb.toString();
    }

    private static void startShare(Context context, String title, String content) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.putExtra(Intent.EXTRA_TEXT, content);
        intent.setType("text/plain");
        context.startActivity(Intent.createChooser(intent, title));
    }

}
