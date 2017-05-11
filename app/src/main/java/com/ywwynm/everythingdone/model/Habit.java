package com.ywwynm.everythingdone.model;

import android.content.Context;
import android.database.Cursor;

import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.database.HabitDAO;
import com.ywwynm.everythingdone.utils.DateTimeUtil;
import com.ywwynm.everythingdone.utils.LocaleUtil;

import org.joda.time.DateTime;
import org.joda.time.DateTimeFieldType;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Created by ywwynm on 2016/1/29.
 * model layer. related to table "habits".
 */
public class Habit {

    private long id;
    private int type;
    private int remindedTimes;

    /**
     * time of day:   6:30,9:25,16:40
     * day of week:   2,4,6 15:0
     * day of month:  6,16,26,31 12:30
     * month of year: 3,6,9 16 23:45
     */
    private String detail;

    private String record;
    private String intervalInfo;

    private long createTime;
    private long firstTime;

    private List<HabitReminder> mHabitReminders;
    private List<HabitRecord> mHabitRecords;

    public Habit(long id, int type, int remindedTimes, String detail, String record, String intervalInfo,
                 long createTime, long firstTime) {
        this.id = id;
        this.type = type;
        this.remindedTimes = remindedTimes;
        this.detail = detail;
        this.record = record;
        this.intervalInfo = intervalInfo;
        this.createTime = createTime;
        this.firstTime = firstTime;
    }

    public Habit(Cursor c) {
        this(c.getLong(0), c.getInt(1), c.getInt(2), c.getString(3), c.getString(4), c.getString(5),
                c.getLong(6), c.getLong(7));
    }

    public Habit(Habit habit) {
        id = habit.id;
        type = habit.type;
        remindedTimes = habit.remindedTimes;
        detail = habit.detail;
        record = habit.record;
        intervalInfo = habit.intervalInfo;
        createTime = habit.createTime;
        firstTime = habit.firstTime;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public int getRemindedTimes() {
        return remindedTimes;
    }

    public void setRemindedTimes(int remindedTimes) {
        this.remindedTimes = remindedTimes;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public String getRecord() {
        return record;
    }

    public void setRecord(String record) {
        this.record = record;
    }

    public String getIntervalInfo() {
        return intervalInfo;
    }

    public void setIntervalInfo(String intervalInfo) {
        this.intervalInfo = intervalInfo;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public long getFirstTime() {
        return firstTime;
    }

    public void setFirstTime(long firstTime) {
        this.firstTime = firstTime;
    }

    public void setHabitReminders(List<HabitReminder> habitReminders) {
        mHabitReminders = habitReminders;
    }

    public List<HabitReminder> getHabitReminders() {
        return mHabitReminders;
    }

    public List<HabitRecord> getHabitRecords() {
        return mHabitRecords;
    }

    public void setHabitRecords(List<HabitRecord> habitRecords) {
        mHabitRecords = habitRecords;
    }

    // added on 2017/3/2, version should be 1.3.6(38)
    public boolean isPaused() {
        return intervalInfo.endsWith(",");
    }

    public long getMinHabitReminderTime() {
        return getClosestHabitReminder().getNotifyTime();
    }

    public int getFinishedTimes() {
        int times = 0;
        for (int i = 0; i < record.length(); i++) {
            if (record.charAt(i) == '1') times++;
        }
        return times;
    }

    public void initHabitReminders() {
        mHabitReminders = new ArrayList<>();
        if (type == Calendar.DATE) {
            initHabitRemindersTimeOfDay();
        } else if (type == Calendar.WEEK_OF_YEAR) {
            initHabitRemindersDayOfWeek();
        } else if (type == Calendar.MONTH) {
            initHabitRemindersDayOfMonth();
        } else if (type == Calendar.YEAR) {
            initHabitRemindersMonthOfYear();
        }
        firstTime = getMinHabitReminderTime();
    }

    public HabitReminder getClosestHabitReminder() {
        long minTime = Long.MAX_VALUE;
        HabitReminder ret = null;
        for (HabitReminder habitReminder : mHabitReminders) {
            long time = habitReminder.getNotifyTime();
            if (time < minTime) {
                minTime = time;
                ret = habitReminder;
            }
        }
        return ret;
    }
    
    public HabitReminder getFinalHabitReminder() {
        long maxTime = Long.MIN_VALUE;
        HabitReminder ret = null;
        for (HabitReminder habitReminder : mHabitReminders) {
            long time = habitReminder.getNotifyTime();
            if (time > maxTime) {
                maxTime = time;
                ret = habitReminder;
            }
        }
        return ret;
    }

    public List<HabitRecord> getHabitRecordsThisT() {
        List<HabitRecord> ret = new ArrayList<>();
        for (HabitRecord habitRecord : mHabitRecords) {
            long time = habitRecord.getRecordTime();
            if (DateTimeUtil.calculateTimeGap(time, System.currentTimeMillis(), type) == 0) {
                ret.add(habitRecord);
            }
        }
        return ret;
    }

    public String getCompletionRate() {
        int fTimes = getFinishedTimes();
        int total = record.length();
        if (total == 0) {
            return "0 %";
        }
        NumberFormat nf = NumberFormat.getPercentInstance();
        nf.setMaximumFractionDigits(2);
        String str = nf.format((float) fTimes / total);
        return str.substring(0, str.length() - 1) + " %";
    }

    public int getTotalT() {
        int total = DateTimeUtil.calculateTimeGap(createTime, System.currentTimeMillis(), type);
        return total - getTotalIntervalT();
    }

    public int getPersistInT() {
        final int size = mHabitRecords.size();
        if (size == 0) {
            return 0;
        }

        long time0 = mHabitRecords.get(0).getRecordTime();
        int piT = 1;
        for (int i = 1; i < size; i++) {
            long time = mHabitRecords.get(i).getRecordTime();
            if (DateTimeUtil.calculateTimeGap(time0, time, type) != 0) {
                time0 = time;
                piT++;
            }
        }

        piT -= getTotalIntervalT();
        return piT;
    }

    public int getTotalIntervalT() {
        int total = 0;
        if (intervalInfo.isEmpty()) return total;
        String[] intervals = intervalInfo.split(";");
        for (String interval : intervals) {
            String[] times = interval.split(",");
            long s = Long.parseLong(times[0]);
            long e = System.currentTimeMillis();
            if (times.length == 2) {
                e = Long.parseLong(times[1]);
            }
            total += DateTimeUtil.calculateTimeGap(s, e, type);
        }
        return total;
    }

    public boolean allowFinish() {
        if (isPaused()) return false;
        DateTime dt = new DateTime();
        DateTimeFieldType jodaType = DateTimeUtil.getJodaType(type);
        int ct = dt.get(jodaType), t;
        long nextTime = 0;
        for (HabitReminder habitReminder : mHabitReminders) {
            long time = habitReminder.getNotifyTime();
            t = dt.withMillis(time).get(jodaType);
            if (ct == t) {
                return true;
            } else {
                nextTime = time;
            }
        }

        DateTime ndt = new DateTime(DateTimeUtil.getHabitReminderTime(type, nextTime, -1));
        t = ndt.get(jodaType);
        // All notifications are set to next T but we are still in this T.
        ct = dt.withMillis(System.currentTimeMillis()).get(jodaType);
        return ct == t && record.length() == remindedTimes - 1;
    }

    /**
     * Check if user can finish Habit once now for a Habit reminder which was reminded at given time.
     *
     * This method may be called to check if user can finish Habit once when he presses "finished
     * this time" action button when corresponding notification comes. Without this check, user can
     * leave that notification forever and can finish the Habit once at any time. As a result, we
     * should check if the Habit reminder's notifyTime and current time is in same Habit cycle.
     *
     * Considering user can create a Habit that will remind him 4 times a day, this method cannot
     * work if user can ignore the notification until next notification comes. However, we will not
     * allow user to see two notifications for one Habit, as a result, he cannot finish the Habit
     * once at wrong time for a certain Habit reminder.
     *
     * @param notifyTime the Habit reminder's alarm time
     * @return {@code true} if user can finish Habit once now. {@code false} otherwise.
     */
    public boolean allowFinish(long notifyTime) {
        if (isPaused()) return false;
        DateTime dt = new DateTime();
        DateTimeFieldType jodaType = DateTimeUtil.getJodaType(type);
        return dt.get(jodaType) == dt.withMillis(notifyTime).get(jodaType);
    }

    public String getFinishedTimesThisTStr(Context context) {
        String timeTypeThisT = DateTimeUtil.getThisTStr(type, context);
        String finished = context.getString(R.string.finished);
        int timesThisT = HabitDAO.getInstance(context).getFinishedTimesThisT(this);
        if (LocaleUtil.isChinese(context)) {
            String timesStr = context.getString(R.string.times);
            return timeTypeThisT + finished + " " + timesThisT + " " + timesStr;
        } else {
            return finished + " " + LocaleUtil.getTimesStr(context, timesThisT) + " " + timeTypeThisT;
        }
    }

    public String getNextReminderDescription(Context context) {
        long nextTime = getClosestHabitReminder().getNotifyTime();
        return DateTimeUtil.getDateTimeStrAt(nextTime, context, true);
    }

    public String getSummary(Context context) {
        String timeTypeEveryT = DateTimeUtil.getTimeTypeStr(type, context);
        int timesEveryT = mHabitReminders.size();
        if (LocaleUtil.isChinese(context)) {
            String every = context.getString(R.string.every);
            String timesStr = context.getString(R.string.times);
            return every + timeTypeEveryT + " " + timesEveryT + " " + timesStr;
        } else {
            return LocaleUtil.getTimesStr(context, timesEveryT) + " a " + timeTypeEveryT;
        }
    }

    /**
     * Precondition: Habit should be in underway.
     */
    public String getStateDescription(Context context) {
        if (isPaused()) {
            return context.getString(R.string.habit_paused);
        } else return "";
    }

    public String getCelebrationText(Context context) {
        StringBuilder sb = new StringBuilder();
        String part1 = context.getString(R.string.celebration_habit_part_1);
        int piT = getPersistInT();
        sb.append(part1).append(" ").append(piT < 1 ? "<1" : String.valueOf(piT)).append(" ")
                .append(DateTimeUtil.getTimeTypeStr(type, context));
        if (piT > 1 && !LocaleUtil.isChinese(context)) {
            sb.append("s");
        }
        sb.append(context.getString(R.string.celebration_habit_part_2))
                .append(" ")
                .append(LocaleUtil.getTimesStr(context, getFinishedTimes()))
                .append(LocaleUtil.isChinese(context) ? "" : " ")
                .append(context.getString(R.string.celebration_habit_part_3))
                .append("\n")
                .append(context.getString(R.string.celebration_habit_part_4));
        return sb.toString();
    }

    public long getDoingEndLimitTime() {
        int recordedTimes = record.length();
        if (remindedTimes > recordedTimes) {
            return getMinHabitReminderTime();
        } else {
            if (mHabitReminders.size() < 2) {
                // Once a month, you finish it in advance and now still want to do that thing?
                // Ok, you can do it until end of next month.
                long end = Long.MAX_VALUE;
                DateTime dt = new DateTime();
                dt = dt.withHourOfDay(23).withMinuteOfHour(59).withSecondOfMinute(59).withMillisOfSecond(999);
                if (type == Calendar.DATE) {
                    end = dt.plusDays(1).getMillis();
                } else if (type == Calendar.WEEK_OF_YEAR) {
                    end = dt.plusWeeks(1).withDayOfWeek(7).getMillis();
                } else if (type == Calendar.MONTH) {
                    end = dt.plusMonths(1).withDayOfMonth(31).getMillis();
                } else if (type == Calendar.YEAR) {
                    end = dt.plusYears(1).withMonthOfYear(12).withDayOfMonth(31).getMillis();
                }
                return end;
            }

            List<HabitReminder> newHrs = new ArrayList<>(mHabitReminders);
            Collections.sort(newHrs, new Comparator<HabitReminder>() {
                @Override
                public int compare(HabitReminder hr1, HabitReminder hr2) {
                    long hrTime1 = hr1.getNotifyTime();
                    long hrTime2 = hr2.getNotifyTime();
                    if (hrTime1 == hrTime2) return 0;
                    else if (hrTime1 < hrTime2) return -1;
                    else return 1;
                }
            });
            return newHrs.get(1).getNotifyTime();
        }
    }

    public static boolean noUpdate(Habit habit, int type, String detail) {
        return habit.getType() == type && habit.getDetail().equals(detail);
    }

    public static boolean noTypeUpdate(Habit habit, int type) {
        return habit.getType() == type;
    }

    public static String generateDetailTimeOfDay(List<Integer> times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times.size(); i += 2) {
            String minute = String.valueOf(times.get(i + 1));
            sb.append(times.get(i)).append(":")
                    .append(minute.length() == 1 ? "0" + minute : minute).append(",");
        }
        return sb.substring(0, sb.length() - 1);
    }

    public static List<Integer> getDayTimeListFromDetail(String detail) {
        List<Integer> timeList = new ArrayList<>();
        String[] times = detail.split(",");
        for (String time : times) {
            String[] hm = time.split(":");
            timeList.add(Integer.parseInt(hm[0]));
            timeList.add(Integer.parseInt(hm[1]));
        }
        return timeList;
    }

    public static String generateDetailDayOf(List<Integer> days, int hour, int minute) {
        StringBuilder sb = new StringBuilder();
        for (Integer day : days) {
            sb.append(day).append(",");
        }
        sb.deleteCharAt(sb.length() - 1);
        sb.append(" ").append(hour).append(":").append(minute < 10 ? "0" + minute : minute);
        return sb.toString();
    }

    public static List<Integer> getDayOrMonthListFromDetail(String detail) {
        List<Integer> domList = new ArrayList<>();
        String[] dayTimes = detail.split(" ");
        String[] doms = dayTimes[0].split(",");
        for (String dom : doms) {
            domList.add(Integer.parseInt(dom));
        }
        return domList;
    }

    public static String[] getTimeFromDetailWeekMonth(String detail) {
        String[] dayTimes = detail.split(" ");
        String[] times = dayTimes[1].split(":");
        if (times[1].length() == 1) {
            times[1] = "0" + times[1];
        }
        return times;
    }

    public static String generateDetailMonthOfYear(List<Integer> months, int day, int hour, int minute) {
        StringBuilder sb = new StringBuilder();
        for (Integer month : months) {
            sb.append(month).append(",");
        }
        sb.deleteCharAt(sb.length() - 1);
        sb.append(" ").append(day).append(" ").append(hour).append(":")
                .append(minute < 10 ? "0" + minute : minute);
        return sb.toString();
    }

    public static String[] getTimeFromDetailYear(String detail) {
        String[] dayTimes = new String[3];
        String[] monthDayTimes = detail.split(" ");
        dayTimes[0] = monthDayTimes[1];
        String[] times = monthDayTimes[2].split(":");
        dayTimes[1] = times[0];
        dayTimes[2] = times[1].length() == 1 ? "0" + times[1] : times[1];
        return dayTimes;
    }

    private void initHabitRemindersTimeOfDay() {
        String[] times = detail.split(",");
        DateTime dt = new DateTime();
        for (String time : times) {
            String[] t = time.split(":");
            int hour   = Integer.parseInt(t[0]);
            int minute = Integer.parseInt(t[1]);
            dt = dt.withHourOfDay(hour).withMinuteOfHour(minute).withSecondOfMinute(0);
            long remMillis = dt.getMillis();
            while (System.currentTimeMillis() >= remMillis) {
                remMillis += 86400000;
            }
            mHabitReminders.add(new HabitReminder(0, id, remMillis));
            dt = dt.withMillis(System.currentTimeMillis());
        }
    }

    private void initHabitRemindersDayOfWeek() {
        String[] dateTimes = detail.split(" ");
        String[] times = dateTimes[1].split(":");
        int hour = Integer.parseInt(times[0]);
        int minute = Integer.parseInt(times[1]);
        DateTime dt = new DateTime().withHourOfDay(hour)
                .withMinuteOfHour(minute).withSecondOfMinute(0);
        String[] days = dateTimes[0].split(",");
        for (String dayStr : days) {
            int day = Integer.parseInt(dayStr);
            day = day == 0 ? 7 : day;
            dt = dt.withDayOfWeek(day);
            long remMillis = dt.getMillis();
            while (System.currentTimeMillis() >= remMillis) {
                remMillis += 604800000;
            }
            mHabitReminders.add(new HabitReminder(0, id, remMillis));
            dt = dt.withMillis(System.currentTimeMillis()).withHourOfDay(hour)
                    .withMinuteOfHour(minute).withSecondOfMinute(0);
        }
    }

    private void initHabitRemindersDayOfMonth() {
        String[] dateTimes = detail.split(" ");
        String[] times = dateTimes[1].split(":");
        int hour = Integer.parseInt(times[0]);
        int minute = Integer.parseInt(times[1]);
        DateTime dt = new DateTime().withHourOfDay(hour)
                .withMinuteOfHour(minute).withSecondOfMinute(0);
        String[] days = dateTimes[0].split(",");
        for (String dayStr : days) {
            int day = Integer.parseInt(dayStr);
            long remMillis;
            if (day == 27) {
                int year = dt.getYear();
                int month = dt.getMonthOfYear();
                dt = dt.withDayOfMonth(DateTimeUtil.getDaysOfMonth(year, month));
                remMillis = dt.getMillis();
                while (System.currentTimeMillis() >= remMillis) {
                    dt = dt.plusMonths(1);
                    year = dt.getYear();
                    month = dt.getMonthOfYear();
                    dt = dt.withDayOfMonth(DateTimeUtil.getDaysOfMonth(year, month));
                    remMillis = dt.getMillis();
                }
            } else {
                dt = dt.withDayOfMonth(day + 1);
                remMillis = dt.getMillis();
                while (System.currentTimeMillis() >= remMillis) {
                    dt = dt.plusMonths(1);
                    remMillis = dt.getMillis();
                }
            }
            mHabitReminders.add(new HabitReminder(0, id, remMillis));
            dt = dt.withMillis(System.currentTimeMillis()).withHourOfDay(hour)
                    .withMinuteOfHour(minute).withSecondOfMinute(0);
        }
    }

    private void initHabitRemindersMonthOfYear() {
        String[] dateTimes = detail.split(" ");
        int day = Integer.parseInt(dateTimes[1]);
        String[] times = dateTimes[2].split(":");
        int hour = Integer.parseInt(times[0]);
        int minute = Integer.parseInt(times[1]);
        DateTime dt = new DateTime().withHourOfDay(hour)
                .withMinuteOfHour(minute).withSecondOfMinute(0);
        String[] months = dateTimes[0].split(",");
        for (String monthStr : months) {
            int month = Integer.parseInt(monthStr) + 1;
            dt = dt.withMonthOfYear(month);
            long remMillis;
            if (day == 28) {
                int year = dt.getYear();
                dt = dt.withDayOfMonth(DateTimeUtil.getDaysOfMonth(year, month));
                remMillis = dt.getMillis();
                while (System.currentTimeMillis() >= remMillis) {
                    dt = dt.plusYears(1).withDayOfMonth(
                            DateTimeUtil.getDaysOfMonth(year + 1, month));
                    remMillis = dt.getMillis();
                }
            } else {
                dt = dt.withDayOfMonth(day);
                remMillis = dt.getMillis();
                while (System.currentTimeMillis() >= remMillis) {
                    remMillis = dt.plusYears(1).getMillis();
                }
            }
            mHabitReminders.add(new HabitReminder(0, id, remMillis));
            dt = dt.withMillis(System.currentTimeMillis()).withHourOfDay(hour)
                    .withMinuteOfHour(minute).withSecondOfMinute(0);
        }
    }
}
