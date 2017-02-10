package com.ywwynm.everythingdone.model;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Parcel;
import android.os.Parcelable;
import android.support.annotation.DrawableRes;
import android.support.annotation.IntDef;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.FrequentSettings;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.helpers.CheckListHelper;
import com.ywwynm.everythingdone.utils.DisplayUtil;
import com.ywwynm.everythingdone.utils.SystemNotificationUtil;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static com.ywwynm.everythingdone.Def.LimitForGettingThings.ALL_DELETED;
import static com.ywwynm.everythingdone.Def.LimitForGettingThings.ALL_FINISHED;
import static com.ywwynm.everythingdone.Def.LimitForGettingThings.ALL_UNDERWAY;
import static com.ywwynm.everythingdone.Def.LimitForGettingThings.GOAL_UNDERWAY;
import static com.ywwynm.everythingdone.Def.LimitForGettingThings.HABIT_UNDERWAY;
import static com.ywwynm.everythingdone.Def.LimitForGettingThings.NOTE_UNDERWAY;
import static com.ywwynm.everythingdone.Def.LimitForGettingThings.REMINDER_UNDERWAY;

/**
 * Created by ywwynm on 2015/5/21.
 * Model layer. Related to table things.
 */
public class Thing implements Parcelable {

    @IntDef({HEADER, NOTE, REMINDER, HABIT, GOAL,
            WELCOME_UNDERWAY, WELCOME_NOTE, WELCOME_REMINDER, WELCOME_HABIT, WELCOME_GOAL,
            NOTIFICATION_UNDERWAY, NOTIFICATION_NOTE, NOTIFICATION_REMINDER,
            NOTIFICATION_HABIT, NOTIFICATION_GOAL,
            NOTIFY_EMPTY_UNDERWAY, NOTIFY_EMPTY_NOTE, NOTIFY_EMPTY_REMINDER, NOTIFY_EMPTY_HABIT,
            NOTIFY_EMPTY_GOAL, NOTIFY_EMPTY_FINISHED, NOTIFY_EMPTY_DELETED, })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Type {}

    public static final int HEADER                = -1;
    public static final int NOTE                  = 0;
    public static final int REMINDER              = 1;
    public static final int HABIT                 = 2;
    public static final int GOAL                  = 3;
    public static final int WELCOME_UNDERWAY      = 4;
    public static final int WELCOME_NOTE          = 5;
    public static final int WELCOME_REMINDER      = 6;
    public static final int WELCOME_HABIT         = 7;
    public static final int WELCOME_GOAL          = 8;
    public static final int NOTIFICATION_UNDERWAY = 9;
    public static final int NOTIFICATION_NOTE     = 10;
    public static final int NOTIFICATION_REMINDER = 11;
    public static final int NOTIFICATION_HABIT    = 12;
    public static final int NOTIFICATION_GOAL     = 13;

    public static final int NOTIFY_EMPTY_UNDERWAY = 14;
    public static final int NOTIFY_EMPTY_NOTE     = 15;
    public static final int NOTIFY_EMPTY_REMINDER = 16;
    public static final int NOTIFY_EMPTY_HABIT    = 17;
    public static final int NOTIFY_EMPTY_GOAL     = 18;
    public static final int NOTIFY_EMPTY_FINISHED = 19;
    public static final int NOTIFY_EMPTY_DELETED  = 20;

    @IntDef({UNDERWAY, FINISHED, DELETED, DELETED_FOREVER})
    @Retention(RetentionPolicy.SOURCE)
    public @interface State {}

    public static final int UNDERWAY              = 0;
    public static final int FINISHED              = 1;
    public static final int DELETED               = 2;
    public static final int DELETED_FOREVER       = 3;

    public static final String PRIVATE_THING_PREFIX
            = App.getApp().getString(R.string.base_signal) + "L";

    public static final Parcelable.Creator<Thing> CREATOR = new Parcelable.Creator<Thing>() {

        @Override
        public Thing createFromParcel(Parcel source) {
            return new Thing(source);
        }

        @Override
        public Thing[] newArray(int size) {
            return new Thing[size];
        }
    };

    private long   id;
    private @Type  int type;
    private @State int state;
    private int    color;
    private String title;
    private String content;
    private String attachment;
    private long   location;
    private long   createTime;
    private long   updateTime;
    private long   finishTime;

    private boolean selected;

    public Thing(long id, @Type int type, int color, long location) {
        this(id, type, UNDERWAY, color, "", "", "", location,
                System.currentTimeMillis(), System.currentTimeMillis(), 0);
    }

    public Thing(long id, @Type int type, @State int state, int color, String title, String content,
                 String attachment, long location, long createTime, long updateTime, long finishTime) {
        this.id         = id;
        this.type       = type;
        this.state      = state;
        this.color      = color;
        this.title      = title;
        this.content    = content;
        this.attachment = attachment;
        this.location   = location;
        this.createTime = createTime;
        this.updateTime = updateTime;
        this.finishTime = finishTime;

        selected = false;
    }

    public Thing(Thing thing) {
        id         = thing.id;
        type       = thing.type;
        state      = thing.state;
        color      = thing.color;
        title      = thing.title;
        content    = thing.content;
        attachment = thing.attachment;
        location   = thing.location;
        createTime = thing.createTime;
        updateTime = thing.updateTime;
        finishTime = thing.finishTime;
        selected   = thing.selected;
    }

    public Thing(Parcel in) {
        id         = in.readLong();
        type       = in.readInt();
        state      = in.readInt();
        color      = in.readInt();
        title      = in.readString();
        content    = in.readString();
        attachment = in.readString();
        location   = in.readLong();
        createTime = in.readLong();
        updateTime = in.readLong();
        finishTime = in.readLong();
    }

    public Thing(Cursor c) {
        this(c.getLong(0),
             c.getInt(1),
             c.getInt(2),
             c.getInt(3),
             c.getString(4),
             c.getString(5),
             c.getString(6),
             c.getLong(7),
             c.getLong(8),
             c.getLong(9),
             c.getLong(10));
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public @Type int getType() {
        return type;
    }

    public void setType(@Type int type) {
        this.type = type;
    }

    public @State int getState() {
        return state;
    }

    public void setState(@State int state) {
        this.state = state;
    }

    public int getColor() {
        return color;
    }

    public void setColor(int color) {
        this.color = color;
    }

    public String getTitle() {
        return title;
    }

    public String getTitleToDisplay() {
        if (isPrivate()) {
            return title.substring(PRIVATE_THING_PREFIX.length());
        }
        return title;
    }

    public boolean isPrivate() {
        return title.startsWith(PRIVATE_THING_PREFIX);
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setAttachment(String attachment) {
        this.attachment = attachment;
    }

    public String getAttachment() {
        return attachment;
    }

    public void setLocation(long location) {
        this.location = location;
    }

    public long getLocation() {
        return location;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public long getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(long updateTime) {
        this.updateTime = updateTime;
    }

    public long getFinishTime() {
        return finishTime;
    }

    public void setFinishTime(long finishTime) {
        this.finishTime = finishTime;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public static String getTypeStr(@Type int type, Context context) {
        if (type == NOTE) {
            return context.getString(R.string.note);
        } else if (type == REMINDER) {
            return context.getString(R.string.reminder);
        } else if (type == HABIT) {
            return context.getString(R.string.habit);
        } else if (type == GOAL) {
            return context.getString(R.string.goal);
        } else return context.getString(R.string.thing);
    }

    public static @DrawableRes int getTypeIconWhiteLarge(@Type int type) {
        if (type == Thing.REMINDER) {
            return R.drawable.ic_reminder_white_large;
        } else if (type == Thing.HABIT) {
            return R.drawable.ic_habit_white_large;
        } else if (type == Thing.GOAL) {
            return R.drawable.ic_goal_white_large;
        } else {
            return R.drawable.ic_note_white_large;
        }
    }

    public static String getStateStr(@State int state, Context context) {
        if (state == UNDERWAY) {
            return context.getString(R.string.underway);
        } else if (state == FINISHED) {
            return context.getString(R.string.finished);
        } else if (state == DELETED) {
            return context.getString(R.string.deleted);
        } else {
            return context.getString(R.string.underway);
        }
    }

    public static int[] getLimits(@Type int type, @State int state) {
        int[] limits;
        if (state == FINISHED || type == NOTIFY_EMPTY_FINISHED) {
            limits = new int[] { Def.LimitForGettingThings.ALL_FINISHED };
        } else if (state == DELETED || type == NOTIFY_EMPTY_DELETED) {
            limits = new int[] { Def.LimitForGettingThings.ALL_DELETED };
        } else {
            if (type == WELCOME_UNDERWAY || type == NOTIFICATION_UNDERWAY
                    || type == NOTIFY_EMPTY_UNDERWAY) {
                return new int[] { Def.LimitForGettingThings.ALL_UNDERWAY };
            } else if (type == WELCOME_NOTE || type == NOTIFICATION_NOTE
                    || type == NOTIFY_EMPTY_NOTE) {
                return new int[] { Def.LimitForGettingThings.NOTE_UNDERWAY };
            } else if (type == WELCOME_REMINDER || type == NOTIFICATION_REMINDER
                    || type == NOTIFY_EMPTY_REMINDER) {
                return new int[] { Def.LimitForGettingThings.REMINDER_UNDERWAY };
            } else if (type == WELCOME_HABIT || type == NOTIFICATION_HABIT
                    || type == NOTIFY_EMPTY_HABIT) {
                return new int[] { Def.LimitForGettingThings.HABIT_UNDERWAY };
            } else if (type == WELCOME_GOAL || type == NOTIFICATION_GOAL
                    || type == NOTIFY_EMPTY_GOAL) {
                return new int[] { Def.LimitForGettingThings.GOAL_UNDERWAY };
            } else {
                limits = new int[2];
                limits[0] = Def.LimitForGettingThings.ALL_UNDERWAY;
                if (type == REMINDER) {
                    limits[1] = Def.LimitForGettingThings.REMINDER_UNDERWAY;
                } else if (type == HABIT) {
                    limits[1] = Def.LimitForGettingThings.HABIT_UNDERWAY;
                } else if (type == GOAL) {
                    limits[1] = Def.LimitForGettingThings.GOAL_UNDERWAY;
                } else {
                    limits[1] = Def.LimitForGettingThings.NOTE_UNDERWAY;
                }
            }
        }
        return limits;
    }

    public static boolean isTypeStateMatchLimit(@Type int type, @State int state, int limit) {
        if (state == Thing.DELETED_FOREVER) {
            return false;
        }
        int[] limits = getLimits(type, state);
        for (int lim : limits) {
            if (limit == lim) {
                return true;
            }
        }
        return false;
    }

    public static int getNotifyEmptyType(int limit) {
        switch (limit) {
            case ALL_UNDERWAY:
                return Thing.NOTIFY_EMPTY_UNDERWAY;
            case NOTE_UNDERWAY:
                return Thing.NOTIFY_EMPTY_NOTE;
            case REMINDER_UNDERWAY:
                return Thing.NOTIFY_EMPTY_REMINDER;
            case HABIT_UNDERWAY:
                return Thing.NOTIFY_EMPTY_HABIT;
            case GOAL_UNDERWAY:
                return Thing.NOTIFY_EMPTY_GOAL;
            case ALL_FINISHED:
                return Thing.NOTIFY_EMPTY_FINISHED;
            case ALL_DELETED:
                return Thing.NOTIFY_EMPTY_DELETED;
            default:
                return Thing.NOTIFY_EMPTY_UNDERWAY;
        }
    }

    public static Thing generateNotifyEmpty(int limit, long headerId, Context context) {
        Thing thing = new Thing(headerId, getNotifyEmptyType(limit),
                DisplayUtil.getRandomColor(context), headerId);
        switch (limit) {
            case ALL_UNDERWAY:
                thing.setTitle(context.getString(R.string.congratulations));
                thing.setContent(context.getString(R.string.empty_underway));
                break;
            case NOTE_UNDERWAY:
                thing.setContent(context.getString(R.string.empty_note));
                break;
            case REMINDER_UNDERWAY:
                thing.setContent(context.getString(R.string.empty_reminder));
                break;
            case HABIT_UNDERWAY:
                thing.setTitle(context.getString(R.string.congratulations));
                thing.setContent(context.getString(R.string.empty_habit));
                break;
            case GOAL_UNDERWAY:
                thing.setContent(context.getString(R.string.empty_goal));
                break;
            case ALL_FINISHED:
                thing.setContent(context.getString(R.string.empty_finished));
                break;
            case ALL_DELETED:
                thing.setContent(context.getString(R.string.empty_deleted));
                break;
            default:return null;
        }
        return thing;
    }

    public static Thing getSameCheckStateThing(Thing thing, @State int stateBefore, @State int stateAfter) {
        Thing result = thing;
        if (stateBefore == UNDERWAY && stateAfter == FINISHED) {
            String content = thing.getContent();
            if (content.contains(CheckListHelper.SIGNAL + 0)) {
                result = new Thing(thing);
                result.setContent(content.replaceAll(
                        CheckListHelper.SIGNAL + 0, CheckListHelper.SIGNAL + 1));
            }
        } else if (stateBefore == FINISHED && stateAfter == UNDERWAY) {
            String content = thing.getContent();
            if (content.contains(CheckListHelper.SIGNAL + 1)) {
                result = new Thing(thing);
                result.setContent(content.replaceAll(
                        CheckListHelper.SIGNAL + 1, CheckListHelper.SIGNAL + 0));
            }
        }
        return result;
    }

    public static boolean noUpdate(Thing thing, String title, String content, String attachment,
                                   @Type int type, int color) {
        return thing.title.equals(title)
            && thing.content.equals(content)
            && thing.attachment.equals(attachment)
            && thing.type == type
            && thing.color == color;
    }

    public static boolean isImportantType(@Type int type) {
        return type == HABIT || type == GOAL;
    }

    public static boolean isReminderType(@Type int type) {
        return type == REMINDER || type == GOAL;
    }

    public static boolean isTypeReminder(@Type int type) {
        return type == REMINDER || type == WELCOME_REMINDER
                || type == NOTIFICATION_REMINDER || type == NOTIFY_EMPTY_REMINDER;
    }

    public static boolean isTypeHabit(@Type int type) {
        return type == HABIT || type == WELCOME_HABIT
                || type == NOTIFICATION_HABIT || type == NOTIFY_EMPTY_HABIT;
    }

    public static boolean isTypeGoal(@Type int type) {
        return type == GOAL || type == WELCOME_GOAL
                || type == NOTIFICATION_GOAL || type == NOTIFY_EMPTY_GOAL;
    }

    public static boolean sameType(@Type int type1, @Type int type2) {
        if (type1 == type2) return true;
        if (type1 == WELCOME_UNDERWAY) return true;
        if (type1 == WELCOME_REMINDER && type2 == REMINDER) {
            return true;
        }
        if (type1 == WELCOME_HABIT && type2 == HABIT) {
            return true;
        }
        if (type1 == WELCOME_GOAL && type2 == GOAL) {
            return true;
        }
        return false;
    }

    public static void tryToCancelOngoing(Context context, long thingId) {
        final String K = Def.Meta.KEY_ONGOING_THING_ID;
        long curOngoingId = FrequentSettings.getLong(K);
        if (curOngoingId == thingId) {
            SystemNotificationUtil.cancelThingOngoingNotification(context, thingId);
            context.getSharedPreferences(Def.Meta.PREFERENCES_NAME, Context.MODE_PRIVATE)
                    .edit().putLong(K, -1L).apply();
            FrequentSettings.put(K, -1L);
        }
    }

    public boolean matchSearchRequirement(String keyword, int color) {
        if (this.color != color && color != -1979711488 && color != 0) {
            return false;
        }

        String curContent = content;
        if (CheckListHelper.isSignalContainsStrIgnoreCase(keyword)) {
            StringBuilder sbRex = new StringBuilder();
            for (int i = 0; i < CheckListHelper.CHECK_STATE_NUM; i++) {
                sbRex.append(CheckListHelper.SIGNAL).append(i).append("|");
            }
            sbRex.deleteCharAt(sbRex.length() - 1);
            curContent = curContent.replaceAll(sbRex.toString(), "");
        }
        return curContent.contains(keyword);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o != null && getClass() == o.getClass() && id == ((Thing) o).id;
    }

    @Override
    public int hashCode() {
        return (int) (id ^ (id >>> 32));
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(id);
        dest.writeInt(type);
        dest.writeInt(state);
        dest.writeInt(color);
        dest.writeString(title);
        dest.writeString(content);
        dest.writeString(attachment);
        dest.writeLong(location);
        dest.writeLong(createTime);
        dest.writeLong(updateTime);
        dest.writeLong(finishTime);
    }
}
