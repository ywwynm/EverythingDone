package com.ywwynm.everythingdone.helpers;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.support.v4.content.ContextCompat;
import android.widget.Toast;

import com.ywwynm.everythingdone.BuildConfig;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.database.HabitDAO;
import com.ywwynm.everythingdone.database.ReminderDAO;
import com.ywwynm.everythingdone.model.Habit;
import com.ywwynm.everythingdone.model.Reminder;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.utils.BitmapUtil;
import com.ywwynm.everythingdone.utils.DateTimeUtil;
import com.ywwynm.everythingdone.utils.DeviceUtil;
import com.ywwynm.everythingdone.utils.FileUtil;
import com.ywwynm.everythingdone.utils.LocaleUtil;
import com.ywwynm.everythingdone.utils.StringUtil;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;

import static com.ywwynm.everythingdone.R.string.days;

/**
 * Created by ywwynm on 2016/2/13.
 * send information to other apps, like sharing or sending feedback.
 */
public class SendInfoHelper {

    public static final String TAG = "SendInfoHelper";

    private SendInfoHelper() {}

    private static final String EXTRA_WX_SHARE_EXPLORE_CONTENT = "Kdescription";

    public static void shareApp(Context context) {
        String title = context.getString(R.string.act_share_everythingdone);
        String content = context.getString(R.string.app_share_info);

        Bitmap bm = ((BitmapDrawable) ContextCompat.getDrawable(
                context, R.drawable.ic_launcher_ori)).getBitmap();
        File file = BitmapUtil.saveBitmapToStorage(FileUtil.TEMP_PATH, "app.jpeg", bm);
        Uri uri = Uri.fromFile(file);
        ArrayList<Uri> list = new ArrayList<>();
        list.add(uri);

        startShare(context, title, content, list, true);
    }

    public static void shareThing(Context context, Thing thing) {
        if (thing == null) return;

        String title      = getShareThingTitle(context, thing);
        String content    = getThingShareInfo(context, thing);
        String attachment = thing.getAttachment();

        startShare(context, title, content,
                AttachmentHelper.toUriList(attachment), AttachmentHelper.isAllImage(attachment));
    }

    public static String getShareThingTitle(Context context, Thing thing) {
        if (thing == null) return null;

        boolean isChinese = LocaleUtil.isChinese(context);
        String title = context.getString(R.string.act_share);
        String thisStr = context.getString(R.string.this_gai);
        if (!isChinese) {
            title = title + " " + thisStr + " ";
        } else {
            title += thisStr;
        }
        return title + Thing.getTypeStr(thing.getType(), context);
    }

    @SuppressLint("SimpleDateFormat")
    public static void sendFeedback(Context context, boolean attachLogFile) {
        Intent intent = new Intent();
        intent.putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.act_feedback) + "-"
                + new SimpleDateFormat("yyyyMMddHHmmss").format(new Date())
                + "-" + BuildConfig.VERSION_NAME + "-" + BuildConfig.VERSION_CODE);
        intent.putExtra(Intent.EXTRA_TEXT, DeviceUtil.getDeviceInfo() + "\n");

        String email = Def.Meta.FEEDBACK_EMAIL;
        if (!attachLogFile) {
            intent.setAction(Intent.ACTION_SENDTO);
            intent.setData(Uri.parse("mailto:" + email));
        } else {
            intent.setAction(Intent.ACTION_SEND);
            intent.setType("message/rfc822");
            intent.putExtra(Intent.EXTRA_EMAIL, new String[] { email });
            intent.putExtra(Intent.EXTRA_STREAM, getLatestLogUri());
        }

        try {
            context.startActivity(Intent.createChooser(intent,
                    context.getString(R.string.send_feedback_to_developer)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(context, context.getString(R.string.error_activity_not_found),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private static Uri getLatestLogUri() {
        String dirPath = Def.Meta.APP_FILE_DIR + "/log";
        File dir = new File(dirPath);
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files == null) return null;
            File max = null;
            String maxName = "";
            for (File file : files) {
                String name = file.getName();
                if (name.endsWith(".log") && name.compareTo(maxName) > 0) {
                    maxName = name;
                    max = file;
                }
            }
            return Uri.fromFile(max);
        }
        return null;
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
        String title = thing.getTitleToDisplay();
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
        }

        long id = thing.getId();
        @Thing.Type  int type  = thing.getType();
        @Thing.State int state = thing.getState();
        if (type == Thing.REMINDER) {
            Reminder reminder = ReminderDAO.getInstance(context).getReminderById(id);
            if (reminder != null) {
                String reminderMe = context.getString(R.string.remind_me);
                String reminderInfoStr = DateTimeUtil.getDateTimeStrReminder(
                        context, thing, reminder, true);
                sb.append(StringUtil.upperFirst(reminderMe)).append(reminderInfoStr).append("\n\n");
            }
        } else if (type == Thing.HABIT) {
            Habit habit = HabitDAO.getInstance(context).getHabitById(id);
            if (habit != null) {
                sb.append(StringUtil.upperFirst(getHabitShareInfo(context, id, state))).append("\n\n");
            }
        } else if (type == Thing.GOAL) {
            if (state != Thing.FINISHED) {
                Reminder goal = ReminderDAO.getInstance(context).getReminderById(id);
                if (goal != null) {
                    String goalInfoStr = DateTimeUtil.getDateTimeStrGoal(context, thing, goal);
                    sb.append(StringUtil.upperFirst(goalInfoStr)).append("\n\n");
                }
            }
        }

        if (state == Thing.FINISHED && type != Thing.HABIT) {
            sb.append(StringUtil.upperFirst(getFinishedThingInfo(context, thing))).append("\n\n");
        }

        sb.append(context.getString(R.string.from_everything_done));
        return sb.toString();
    }

    public static String getHabitShareInfo(Context context, long id, int thingState) {
        Habit habit = HabitDAO.getInstance(context).getHabitById(id);
        if (habit == null) {
            return "";
        }

        int type = habit.getType();
        int piT = habit.getPersistInT();
        boolean isChinese = LocaleUtil.isChinese(context);
        StringBuilder sb = new StringBuilder();

        String remindMe = context.getString(R.string.remind_me);
        String habitStr = DateTimeUtil.getDateTimeStrRec(context, habit.getType(), habit.getDetail());
        if (habitStr == null) {
            habitStr = habit.getSummary(context);
        } else if (habitStr.startsWith("at ")) {
            habitStr = habitStr.substring(3, habitStr.length());
        }
        if (habit.isPaused()) {
            habitStr += ", " + habit.getStateDescription(context);
        }
        String GAP;
        if (isChinese) {
            GAP = "";
        } else GAP = " ";
        sb.append(remindMe).append(habitStr).append("\n")
                .append(context.getString(R.string.share_i_persist_in_for)).append(GAP)
                .append(piT < 1 ? "<1" : String.valueOf(piT)).append(GAP)
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
        sb.append(GAP).append(LocaleUtil.getTimesStr(context, finishedTimes, false));

        if (thingState == Thing.FINISHED) {
            sb.append(", ").append(context.getString(R.string.share_habit_developed));
        }

        return sb.toString();
    }

    private static String getFinishedThingInfo(Context context, Thing thing) {
        boolean isChinese = LocaleUtil.isChinese(context);
        StringBuilder sb = new StringBuilder();
        @Thing.Type int type = thing.getType();

        if (type == Thing.GOAL) {
            Reminder goal = ReminderDAO.getInstance(context).getReminderById(thing.getId());
            int gap = DateTimeUtil.calculateTimeGap(
                    goal.getUpdateTime(), thing.getFinishTime(), Calendar.DATE);
            String gapStr;
            if (gap == 0) {
                gapStr = "<1";
            } else {
                gapStr = String.valueOf(gap);
            }

            String STRGAP;
            if (isChinese) {
                STRGAP = "";
            } else STRGAP = " ";
            sb.append(context.getString(R.string.share_i_work_hard_for)).append(STRGAP)
                    .append(gapStr).append(STRGAP).append(context.getString(days));
            if (!isChinese && gap > 1) {
                sb.append("s");
            }

            String achieve = context.getString(R.string.share_goal_finished);
            if (isChinese) {
                sb.append(", ").append(achieve);
            } else {
                sb.append(" ").append(achieve);
            }
            return sb.toString();
        }

        // else : type isn't GOAL
        sb.append(String.format(context.getString(R.string.finish_at_normal),
                DateTimeUtil.getDateTimeStrAt(thing.getFinishTime(), context, true)));

        return sb.toString();
    }

    private static void startShare(
            Context context, String title, String content, ArrayList<Uri> attachments, boolean allImage) {
        Intent intent = new Intent();
        if (attachments == null) {
            intent.setAction(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TEXT, content);
        } else {
            intent.setAction(Intent.ACTION_SEND_MULTIPLE);
            if (allImage) {
                intent.setType("image/*");
                intent.putExtra(EXTRA_WX_SHARE_EXPLORE_CONTENT, content);
            } else {
                intent.setType("*/*");
            }
            if (content != null) {
                intent.putExtra(Intent.EXTRA_TEXT, content);
            }
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, attachments);
        }
        context.startActivity(Intent.createChooser(intent, title));
    }

}
