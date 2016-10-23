package com.ywwynm.everythingdone.utils;

import android.support.v4.util.LongSparseArray;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.database.HabitDAO;
import com.ywwynm.everythingdone.database.ReminderDAO;
import com.ywwynm.everythingdone.model.Habit;
import com.ywwynm.everythingdone.model.Reminder;
import com.ywwynm.everythingdone.model.Thing;

import java.util.Comparator;

/**
 * Created by ywwynm on 2016/10/23.
 * utils for sorting list
 */
public class ThingsSorter {

    public static final String TAG = "ThingsSorter";

    private ThingsSorter() {}

    /**
     * Get a {@link Comparator} used to sort a list of {@link Thing}s by their alarm time.
     * @param ignoreSticky if {@code true}, then the first thing in the result list sorted by this
     *                     comparator will be the thing that are closest to ring an alarm, even if
     *                     it's not sticky and there are other sticky things.
     *                     if {@code false}, then the first thing will be sticky even if there are
     *                     other things that have closer alarm to ring. Of course, if there are no
     *                     sticky things, then the sorted list will be same as that sorted with
     *                     {@param ignoreSticky} is false.
     */
    public static Comparator<Thing> getThingComparatorByAlarmTime(final boolean ignoreSticky) {
        final ReminderDAO rDao = ReminderDAO.getInstance(App.getApp());
        final HabitDAO hDao = HabitDAO.getInstance(App.getApp());
        return new Comparator<Thing>() {

            private LongSparseArray<Boolean> shouldCompareMap = new LongSparseArray<>();
            private LongSparseArray<Long> timeMap = new LongSparseArray<>();

            @Override
            public int compare(Thing thing1, Thing thing2) {
                // header is on top
                if (thing1.getType() == Thing.HEADER) return -1;
                if (thing2.getType() == Thing.HEADER) return 1;

                if (ignoreSticky) {
                    return compareByAlarmTime(thing1, thing2);
                } else {
                    // sort by location at first(which means considering sticky), then by alarm time
                    long loc1 = thing1.getLocation();
                    long loc2 = thing2.getLocation();
                    if (loc1 < 0 && loc2 >= 0) {
                        return -1;
                    } else if (loc1 >= 0 && loc2 < 0) {
                        return 1;
                    } else {
                        return compareByAlarmTime(thing1, thing2);
                    }
                }
            }

            private int compareByAlarmTime(Thing thing1, Thing thing2) {
                @Thing.Type int type1 = thing1.getType();
                @Thing.Type int type2 = thing2.getType();
                long id1 = thing1.getId();
                Boolean shouldCompare1 = shouldCompare(id1, type1);
                long id2 = thing2.getId();
                Boolean shouldCompare2 = shouldCompare(id2, type2);

                if (!shouldCompare1 && !shouldCompare2) {
                    return compareByLocationAndSticky(thing1.getLocation(), thing2.getLocation());
                } else if (shouldCompare1 && !shouldCompare2) {
                    return -1;
                } else if (!shouldCompare1 && shouldCompare2) {
                    return 1;
                } else {
                    long time1 = getAlarmTime(thing1.getId(), type1);
                    long time2 = getAlarmTime(thing2.getId(), type2);
                    if (time1 < time2) {
                        return -1;
                    } else if (time1 == time2) {
                        return 0;
                    } else return 1;
                }
            }

            private boolean shouldCompare(long id, @Thing.Type int type) {
                Boolean shouldCompare = shouldCompareMap.get(id);
                if (shouldCompare == null) {
                    if (Thing.isReminderType(type)) {
                        Reminder reminder = rDao.getReminderById(id);
                        shouldCompare = reminder != null && reminder.getState() == Reminder.UNDERWAY;
                    } else if (type == Thing.HABIT) {
                        Habit habit = hDao.getHabitById(id);
                        shouldCompare = habit != null;
                    } else {
                        shouldCompare = false;
                    }
                    shouldCompareMap.put(id, shouldCompare);
                }
                return shouldCompare;
            }

            private long getAlarmTime(long id, @Thing.Type int type) {
                Long time = timeMap.get(id);
                if (time == null) {
                    time = Long.MAX_VALUE;
                    if (Thing.isReminderType(type)) {
                        Reminder r1 = rDao.getReminderById(id);
                        if (r1 != null && r1.getState() == Reminder.UNDERWAY) {
                            time = r1.getNotifyTime();
                        }
                    } else {
                        Habit h1 = hDao.getHabitById(id);
                        if (h1 != null) {
                            time = h1.getMinHabitReminderTime();
                        }
                    }
                    timeMap.put(id, time);
                }
                return time;
            }
        };
    }

    public static int compareByLocationAndSticky(long loc1, long loc2) {
        if (loc1 < 0 && loc2 >= 0) {
            return -1;
        } else if (loc1 >= 0 && loc2 < 0) {
            return 1;
        } else if (loc1 >= 0 && loc2 >= 0) {
            if (loc1 > loc2)       return -1;
            else if (loc1 == loc2) return 0;
            else                   return 1;
        } else { // both are <0, both are sticky on top
            // locations are -3 and -2, the -3 one will be in front of the -2 one.
            if (loc1 < loc2)       return -1;
            else if (loc1 == loc2) return 0;
            else                   return 1;
        }
    }

}
