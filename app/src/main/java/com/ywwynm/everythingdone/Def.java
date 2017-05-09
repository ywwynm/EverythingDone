package com.ywwynm.everythingdone;

import android.os.Environment;

/**
 * Created by ywwynm on 2015/5/21.
 * Def for EverythingDone
 */
public final class Def {

    private Def() { }

    public static final class Meta {

        private Meta() {}

        public static final String DISTRIBUTE_EDITION = "GENERAL";
//        public static final String DISTRIBUTE_EDITION = "GOOGLE PLAY";

        public static final String META_DATA_NAME           = "EverythingDone_metadata";
        public static final String PREFERENCES_NAME         = "EverythingDone_preferences";
        public static final String THINGS_COUNTS_NAME       = "EverythingDone_things_counts";
        public static final String DOING_STRATEGY_NAME      = "EverythingDone_doing_strategy"; // added on 2016/11/22
        public static final String RESTORE_DONE_FILE_NAME   = "restore_done.dat";
        public static final String FEEDBACK_ERROR_FILE_NAME = "feedback_error.dat";

        public static final String DATABASE_NAME = "EverythingDoneData.db";
        public static final int DATABASE_VERSION = 8;

        public static final int ONGOING_NOTIFICATION_ID = Integer.MAX_VALUE;

        public static final String APP_AUTHORITY = "com.ywwynm.everythingdone";

        public static final String APP_FILE_DIR =
                Environment.getExternalStorageDirectory().getAbsolutePath() + "/EverythingDone";

        public static final String FEEDBACK_EMAIL = "everythingdonefeedback@gmail.com";

        public static final String ROBOTO_MONO = "roboto-mono-min.ttf";

        public static final String KEY_START_USING_TIME         = "start_using_time";
        public static final String KEY_LAST_BACKUP_TIME         = "last_backup_time";

        public static final String KEY_DRAWER_HEADER            = "drawer_header";
        public static final String KEY_NOTICEABLE_NOTIFICATION  = "noticeable_notification"; // 2016/11/9
        // toggle checklist item on thing card
        public static final String KEY_TOGGLE_CLI_OTC           = "toggle_cli_otc";
        public static final String KEY_SIMPLE_FCLI              = "simple_fcli";
        public static final String KEY_AUTO_LINK                = "auto_link"; // 2016/11/11
        public static final String KEY_TWICE_BACK               = "twice_back";
        public static final String KEY_LANGUAGE_CODE            = "language_code";

        public static final String KEY_RINGTONE_REMINDER        = "ringtone_reminder";
        public static final String KEY_RINGTONE_HABIT           = "ringtone_habit";
        public static final String KEY_RINGTONE_GOAL            = "ringtone_goal";
        public static final String KEY_RINGTONE_AUTO_NOTIFY     = "ringtone_auto_notify";

        public static final String KEY_AUTO_SAVE_EDITS          = "auto_save_edits";

        public static final String KEY_PRIVATE_PASSWORD         = "private_password";
        public static final String KEY_USE_FINGERPRINT          = "use_fingerprint";

        // added on 2016/11/22
        public static final String KEY_AUTO_START_DOING         = "auto_start_doing";
        public static final String KEY_AUTO_STRICT_MODE         = "auto_strict_mode";
        // added on 2016/11/26
        public static final String KEY_ASD_TIME_REMINDER        = "asd_time_reminder";
        public static final String KEY_ASD_TIME_HABIT           = "asd_time_habit";

        public static final String KEY_QUICK_CREATE             = "quick_create";
        public static final String KEY_CLOSE_NOTIFICATION_LATER = "close_notification_later"; // 2017/1/18
        public static final String KEY_ONGOING_LOCKSCREEN       = "ongoing_lockscreen";
        public static final String KEY_DAILY_TODO               = "daily_todo"; // 2017/5/9
        public static final String KEY_AUTO_NOTIFY              = "auto_notify";

        public static final String KEY_ONGOING_THING_ID         = "ongoing_thing_id";

        public static final String KEY_1_0_3_TO_1_0_4           = "1.0.3_to_1.0.4";
        public static final String KEY_1_0_4_TO_1_0_5           = "1.0.4_to_1.0.5";
        public static final String KEY_1_1_4_TO_1_1_5           = "1.1.4_to_1.1.5";
        public static final String KEY_1_2_7_TO_1_3_0           = "1.2.7_to_1.3.0";
        public static final String KEY_1_3_0_TO_1_3_1           = "1.3.0_to_1.3.1";
        public static final String KEY_1_3_3_TO_1_3_4           = "1.3.3_to_1.3.4";

        public static final String KEY_NOTIFY_KEEP_ALARMS       = "notify_keep_alarms";

    }

    public static final class LimitForGettingThings {

        private LimitForGettingThings() {}

        public static final int ALL_UNDERWAY      = 0;
        public static final int NOTE_UNDERWAY     = 1;
        public static final int REMINDER_UNDERWAY = 2;
        public static final int HABIT_UNDERWAY    = 3;
        public static final int GOAL_UNDERWAY     = 4;
        public static final int ALL_FINISHED      = 5;
        public static final int ALL_DELETED       = 6;

    }

    public static final class Database {

        private Database() {}

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

        public static final String TABLE_REMINDERS                 = "reminders";
        public static final String COLUMN_ID_REMINDERS             = "id";
        public static final String COLUMN_NOTIFY_TIME_REMINDERS    = "notify_time";
        public static final String COLUMN_STATE_REMINDERS          = "state";
        public static final String COLUMN_NOTIFY_MILLIS_REMINDERS  = "notify_millis";
        public static final String COLUMN_CREATE_TIME_REMINDERS    = "create_time";
        public static final String COLUMN_UPDATE_TIME_REMINDERS    = "update_time";

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
        public static final String COLUMN_TYPE_HABIT_RECORDS         = "type";

        public static final String TABLE_APP_WIDGET           = "app_widget";
        public static final String COLUMN_ID_APP_WIDGET       = "id";
        public static final String COLUMN_THING_ID_APP_WIDGET = "thing_id";
        public static final String COLUMN_SIZE_APP_WIDGET     = "size";
        public static final String COLUMN_ALPHA_APP_WIDGET    = "alpha";
        public static final String COLUMN_STYLE_APP_WIDGET    = "style";

        public static final String TABLE_DOING_RECORDS             = "doing_records";
        public static final String COLUMN_ID_DOING                 = "id";
        public static final String COLUMN_THING_ID_DOING           = "thing_id";
        public static final String COLUMN_THING_TYPE_DOING         = "thing_type";
        public static final String COLUMN_ADD5_TIMES_DOING         = "add5_times";
        public static final String COLUMN_PLAYED_TIMES_DOING       = "played_times";
        public static final String COLUMN_TOTAL_PLAY_TIME_DOING    = "total_play_time";
        public static final String COLUMN_PREDICT_DOING_TIME_DOING = "predict_doing_time";
        public static final String COLUMN_START_TIME_DOING         = "start_time";
        public static final String COLUMN_END_TIME_DOING           = "end_time";
        public static final String COLUMN_STOP_REASON_DOING        = "stop_reason";
        public static final String COLUMN_START_TYPE_DOING         = "start_type";
        public static final String COLUMN_SHOULD_ASM_DOING         = "should_asm";

    }

    public static final class Communication {

        private Communication() {}

        private static final String PREFIX = "com.ywwynm.everythingdone.";

        public static final int REQUEST_ACTIVITY_THINGS       = 0;
        public static final int REQUEST_ACTIVITY_DETAIL       = 1;
        public static final int REQUEST_ACTIVITY_IMAGE_VIEWER = 2;
        public static final int REQUEST_ACTIVITY_SETTINGS     = 3;
        public static final int REQUEST_TAKE_PHOTO            = 4;
        public static final int REQUEST_CAPTURE_VIDEO         = 5;
        public static final int REQUEST_CHOOSE_MEDIA_FILE     = 6;
        public static final int REQUEST_CHOOSE_IMAGE_FILE     = 7;
        public static final int REQUEST_CHOOSE_AUDIO_FILE     = 8;

        public static final int REQUEST_PERMISSION_TAKE_PHOTO          = 0;
        public static final int REQUEST_PERMISSION_SHOOT_VIDEO         = 1;
        public static final int REQUEST_PERMISSION_RECORD_AUDIO        = 2;
        public static final int REQUEST_PERMISSION_CHOOSE_MEDIA_FILE   = 3;
        public static final int REQUEST_PERMISSION_SCREENSHOT          = 4;
        public static final int REQUEST_PERMISSION_SHARE_APP           = 5;
        public static final int REQUEST_PERMISSION_CHOOSE_IMAGE_FILE   = 6;
        public static final int REQUEST_PERMISSION_CHOOSE_AUDIO_FILE   = 7;
        public static final int REQUEST_PERMISSION_BACKUP              = 8;
        public static final int REQUEST_PERMISSION_RESTORE             = 9;
        public static final int REQUEST_PERMISSION_SEND_ERROR_FEEDBACK = 10;
        public static final int REQUEST_PERMISSION_LOAD_THINGS         = 11; // ThingsActivity
        public static final int REQUEST_PERMISSION_EXPORT_MAIN         = 12;
        public static final int REQUEST_PERMISSION_EXPORT_DETAIL       = 13;
        public static final int REQUEST_PERMISSION_LOAD_THING          = 14; // DetailActivity
        public static final int REQUEST_PERMISSION_LOAD_THINGS_2       = 15; // BaseThingWidgetConfiguration

        // On some sony/oneplus devices, reading ringtone's title needs READ_EXTERNAL_SDCARD
        // permission, so we request it directly before SettingsActivity's init.
        public static final int REQUEST_PERMISSION_OPEN_SETTINGS       = 16;

        // added on 2017/3/28 to implement restore2
        public static final int REQUEST_CHOOSE_BACKUP_FILE             = 17;

        public static final String KEY_SENDER_NAME          = PREFIX + "key.sender_name";
        public static final String KEY_DETAIL_ACTIVITY_TYPE = PREFIX + "key.detail_activity_type";

        public static final String KEY_THING        = PREFIX + "key.thing";
        public static final String KEY_ID           = PREFIX + "key.id";
        public static final String KEY_COLOR        = PREFIX + "key.color";
        public static final String KEY_POSITION     = PREFIX + "key.position";
        public static final String KEY_TYPE_BEFORE  = PREFIX + "key.type_before";
        public static final String KEY_STATE_AFTER  = PREFIX + "key.state_after";
        public static final String KEY_CREATED_DONE = PREFIX + "key.created_done";

        public static final String KEY_RESULT_CODE = PREFIX + "key.result_code";

        public static final String KEY_CALL_CHANGE = PREFIX + "key.call_change";

        public static final String KEY_EDITABLE       = PREFIX + "key.editable";
        public static final String KEY_TYPE_PATH_NAME = PREFIX + "key.type_path_name";

        public static final String KEY_TIME = PREFIX + "key.time";

        public static final String KEY_HELP_TITLES   = PREFIX + "key.help_titles";
        public static final String KEY_HELP_CONTENTS = PREFIX + "key.help_contents";

        public static final String KEY_WIDGET_ID        = PREFIX + "key.widget_id";
        public static final String KEY_LIMIT            = PREFIX + "key.limit";

        // added for notification action for private thing
        public static final String KEY_TITLE            = PREFIX + "key.title";

        public static final int RESULT_NO_UPDATE                        = 0;
        public static final int RESULT_CREATE_THING_DONE                = 1;
        public static final int RESULT_CREATE_BLANK_THING               = 2;
        public static final int RESULT_ABANDON_NEW_THING                = 3;
        public static final int RESULT_UPDATE_THING_DONE_TYPE_SAME      = 4;
        public static final int RESULT_UPDATE_THING_DONE_TYPE_DIFFERENT = 5;
        public static final int RESULT_UPDATE_THING_STATE_DIFFERENT     = 6;
        public static final int RESULT_STICKY_THING_OR_CANCEL           = 7;
        public static final int RESULT_DOING_OR_CANCEL                  = 8;
        public static final int RESULT_JUST_NOTIFY_DATASET_CHANGED      = 9;

        public static final int RESULT_UPDATE_IMAGE_DONE = 1;

        public static final int RESULT_UPDATE_DRAWER_HEADER_DONE = 1;

        public static final String NOTIFICATION_ACTION_FINISH =
                PREFIX + "action.notification.finish";
        public static final String NOTIFICATION_ACTION_DELAY  =
                PREFIX + "action.notification.delay";
        public static final String NOTIFICATION_ACTION_START_DOING =
                PREFIX + "action.notification.start_doing";
        public static final String NOTIFICATION_ACTION_GET_IT =
                PREFIX + "action.notification.get_it";
        public static final String NOTIFICATION_ACTION_CANCEL =
                PREFIX + "action.notification.cancel";

        // added for finish(or finish this time) action in single thing app widget
        public static final String WIDGET_ACTION_FINISH = PREFIX + "action.widget.finish";

        public static final String BROADCAST_ACTION_UPDATE_MAIN_UI     =
                PREFIX + "action.broadcast.update_main_ui";

        public static final String BROADCAST_ACTION_FINISH_DETAILACTIVITY =
                PREFIX + "action.broadcast.finish_detailactivity";

        // added for update checklist in single thing app widget
        public static final String BROADCAST_ACTION_UPDATE_CHECKLIST   =
                PREFIX + "action.broadcast.update_checklist";

        // added for in-app language setting
        public static final String BROADCAST_ACTION_RESP_LOCALE_CHANGE =
                PREFIX + "action.broadcast.resp_locale_change";

        public static final String AUTHENTICATE_ACTION_VIEW   =
                PREFIX + "action.authenticate.view";

        // added mainly for notification action for private thing
        public static final String AUTHENTICATE_ACTION_FINISH =
                PREFIX + "action.authenticate.finish";
        public static final String AUTHENTICATE_ACTION_DELAY  =
                PREFIX + "action.authenticate.delay";
        public static final String AUTHENTICATE_ACTION_START_DOING =
                PREFIX + "action.authenticate.start_doing";

        public static final String SHORTCUT_ACTION_CREATE =
                PREFIX + "action.shortcut.create";
        public static final String SHORTCUT_ACTION_CHECK_UPCOMING =
                PREFIX + "action.shortcut.check_upcoming";
        public static final String SHORTCUT_ACTION_CHECK_STICKY =
                PREFIX + "action.shortcut.check_sticky";

    }

    public static final class PickerType {

        private PickerType() {}

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
