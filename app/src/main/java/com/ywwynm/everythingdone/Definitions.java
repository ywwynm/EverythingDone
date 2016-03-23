package com.ywwynm.everythingdone;

/**
 * Created by ywwynm on 2015/5/21.
 * Definitions for EverythingDone
 */
public final class Definitions {

    public Definitions() {
        // Create an empty constructor to prevent instantiation of the class.
    }

    public abstract static class MetaData {

        public static final String META_DATA_NAME          = "EverythingDone_metadata";
        public static final String PREFERENCES_NAME        = "EverythingDone_preferences";
        public static final String THINGS_COUNTS_FILE_NAME = "things_counts.dat";
        public static final String CREATE_ALARMS_FILE_NAME = "create_alarms.dat";

        public static final String APP_VERSION = "1.0";
        public static final int DATABASE_VERSION = 1;

        public static final String FEEDBACK_EMAIL = "everythingdone@gmail.com";

        public static final String KEY_FIRST_TIME_USED      = "first_time_used";
        public static final String KEY_RINGTONE_REMINDER    = "ringtone_reminder";
        public static final String KEY_RINGTONE_HABIT       = "ringtone_habit";
        public static final String KEY_RINGTONE_GOAL        = "ringtone_goal";
        public static final String KEY_RINGTONE_AUTO_NOTIFY = "ringtone_auto_notify";
        public static final String KEY_AUTO_NOTIFY          = "auto_notify";

    }

    public abstract static class LimitForGettingThings {

        public static final int ALL_UNDERWAY      = 0;
        public static final int NOTE_UNDERWAY     = 1;
        public static final int REMINDER_UNDERWAY = 2;
        public static final int HABIT_UNDERWAY    = 3;
        public static final int GOAL_UNDERWAY     = 4;
        public static final int ALL_FINISHED      = 5;
        public static final int ALL_DELETED       = 6;

    }

    public abstract static class Database {

        public static final String TABLE_THINGS              = "things";
        public static final String COLUMN_ID_THINGS          = "id";
        public static final String COLUMN_TYPE_THINGS        = "type";
        public static final String COLUMN_STATE_THINGS       = "state";
        public static final String COLUMN_COLOR_THINGS       = "color";
        public static final String COLUMN_TITLE_THINGS       = "title";
        public static final String COLUMN_CONTENT_THINGS     = "content";
        public static final String COLUMN_ATTACHMENT_THINGS  = "attachment";
        public static final String COLUMN_LOCATION_THINGS    = "location";
        public static final String COLUMN_CREATE_TIME_THINGS = "create_time";
        public static final String COLUMN_UPDATE_TIME_THINGS = "update_time";
        public static final String COLUMN_FINISH_TIME_THINGS = "finish_time";

        public static final String TABLE_REMINDERS              = "reminders";
        public static final String COLUMN_ID_REMINDERS          = "id";
        public static final String COLUMN_NOTIFY_TIME_REMINDERS = "notify_time";
        public static final String COLUMN_STATE_REMINDERS       = "state";
        public static final String COLUMN_GOAL_DAYS_REMINDERS   = "goal_days";
        public static final String COLUMN_CREATE_TIME_REMINDERS = "create_time";
        public static final String COLUMN_UPDATE_TIME_REMINDERS = "update_time";

        public static final String TABLE_HABITS                 = "habits";
        public static final String COLUMN_ID_HABITS             = "id";
        public static final String COLUMN_TYPE_HABITS           = "type";
        public static final String COLUMN_REMINDED_TIMES_HABITS = "reminded_times";
        public static final String COLUMN_DETAIL_HABITS         = "detail";
        public static final String COLUMN_RECORD_HABITS         = "record";
        public static final String COLUMN_INTERVAL_INFO_HABITS  = "interval_info";
        public static final String COLUMN_CREATE_TIME_HABITS    = "create_time";
        public static final String COLUMN_FIRST_TIME_HABITS     = "first_time";

        public static final String TABLE_HABIT_REMINDERS              = "habit_reminders";
        public static final String COLUMN_ID_HABIT_REMINDERS          = "id";
        public static final String COLUMN_HABIT_ID_HABIT_REMINDERS    = "habit_id";
        public static final String COLUMN_NOTIFY_TIME_HABIT_REMINDERS = "notify_time";

        public static final String TABLE_HABIT_RECORDS               = "habit_records";
        public static final String COLUMN_ID_HABIT_RECORDS           = "id";
        public static final String COLUMN_HABIT_ID_HABIT_RECORDS     = "habit_id";
        public static final String COLUMN_HR_ID_HABIT_RECORDS        = "habit_reminder_id";
        public static final String COLUMN_RECORD_TIME_HABIT_RECORDS  = "record_time";
        public static final String COLUMN_RECORD_YEAR_HABIT_RECORDS  = "record_year";
        public static final String COLUMN_RECORD_MONTH_HABIT_RECORDS = "record_month";
        public static final String COLUMN_RECORD_WEEK_HABIT_RECORDS  = "record_week";
        public static final String COLUMN_RECORD_DAY_HABIT_RECORDS   = "record_day";

    }

    public abstract static class Communication {

        public static final int REQUEST_ACTIVITY_THINGS             = 0;
        public static final int REQUEST_ACTIVITY_DETAIL             = 1;
        public static final int REQUEST_ACTIVITY_IMAGE_VIEWER       = 2;
        public static final int REQUEST_TAKE_PHOTO                  = 3;
        public static final int REQUEST_CAPTURE_VIDEO               = 4;
        public static final int REQUEST_CHOOSE_MEDIA_FILE           = 5;
        public static final int REQUEST_BROADCAST_RECEIVER_REMINDER = 6;

        public static final String KEY_SENDER_NAME          = "sender_name";
        public static final String KEY_DETAIL_ACTIVITY_TYPE = "detail_activity_type";

        public static final String KEY_THING       = "thing";
        public static final String KEY_ID          = "id";
        public static final String KEY_COLOR       = "color";
        public static final String KEY_POSITION    = "position";
        public static final String KEY_TYPE_BEFORE = "type_before";
        public static final String KEY_STATE_AFTER = "state_after";

        public static final String KEY_RESULT_CODE = "result_code";

        public static final String KEY_CALL_CHANGE = "call_change";

        public static final String KEY_EDITABLE       = "editable";
        public static final String KEY_TYPE_PATH_NAME = "type_path_name";

        public static final String KEY_TIME = "time";

        public static final int RESULT_NO_UPDATE                        = 0;
        public static final int RESULT_CREATE_THING_DONE                = 1;
        public static final int RESULT_CREATE_BLANK_THING               = 2;
        public static final int RESULT_UPDATE_THING_DONE_TYPE_SAME      = 3;
        public static final int RESULT_UPDATE_THING_DONE_TYPE_DIFFERENT = 4;
        public static final int RESULT_UPDATE_THING_STATE_DIFFERENT     = 5;

        public static final int RESULT_UPDATE_IMAGE_DONE = 1;

        public static final String NOTIFICATION_ACTION_FINISH =
                "com.ywwynm.everythingdone.notification_action_finish";
        public static final String NOTIFICATION_ACTION_DELAY  =
                "com.ywwynm.everythingdone.notification_action_delay";
        public static final String NOTIFICATION_ACTION_GET_IT =
                "com.ywwynm.everythingdone.notification_action_get_it";

        public static final String BROADCAST_ACTION_UPDATE_MAIN_UI =
                "com.ywwynm.everythingdone.broadcast_action_update_main_ui";

    }

    public abstract static class PickerType {

        public static final int COLOR_HAVE_ALL             = 0;
        public static final int COLOR_NO_ALL               = 1;
        public static final int AFTER_TIME                 = 2;
        public static final int TIME_TYPE_NO_HOUR_MINUTE   = 3;
        public static final int TIME_TYPE_HAVE_HOUR_MINUTE = 4;
        public static final int DAY_OF_WEEK                = 5;
        public static final int DAY_OF_MONTH               = 6;
        public static final int MONTH_OF_YEAR              = 7;

    }
}
