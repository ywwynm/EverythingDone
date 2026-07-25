package com.ywwynm.everythingdone

import android.content.Context

/**
 * Created by ywwynm on 2015/5/21.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Def for EverythingDone
 */
object Def {

    /**
     * Get app-private external files directory for storing attachments, ringtones, etc.
     * Uses getExternalFilesDir on API 29+, falls back to legacy path on older devices.
     */
    @JvmStatic
    fun getAppFileDir(context: Context?): String? {
        return context!!.getExternalFilesDir(null)!!.absolutePath
    }

    object Meta {

        const val DISTRIBUTE_EDITION: String = "GENERAL"
//        const val DISTRIBUTE_EDITION: String = "GOOGLE PLAY"

        const val META_DATA_NAME: String           = "EverythingDone_metadata"
        const val PREFERENCES_NAME: String         = "EverythingDone_preferences"
        const val THINGS_COUNTS_NAME: String       = "EverythingDone_things_counts"
        const val DOING_STRATEGY_NAME: String      = "EverythingDone_doing_strategy" // added on 2016/11/22
        const val RESTORE_DONE_FILE_NAME: String   = "restore_done.dat"
        const val FEEDBACK_ERROR_FILE_NAME: String = "feedback_error.dat"

        const val DATABASE_NAME: String = "EverythingDoneData.db"
        const val DATABASE_VERSION: Int = 21

        const val ONGOING_NOTIFICATION_ID: Int = Int.MAX_VALUE

        const val APP_AUTHORITY: String = "com.ywwynm.everythingdone"

        const val FEEDBACK_EMAIL: String = "everythingdonefeedback@gmail.com"

        const val ROBOTO_MONO: String = "roboto-mono-min.ttf"

        const val KEY_START_USING_TIME: String         = "start_using_time"
        const val KEY_LAST_BACKUP_TIME: String         = "last_backup_time"
        const val KEY_LAST_ALARM_REBUILD: String       = "last_alarm_rebuild"

        const val KEY_DRAWER_HEADER: String            = "drawer_header"
        const val KEY_DRAWER_HEADER_CROP: String       = "drawer_header_crop" // 2026/6/25
        const val KEY_NOTICEABLE_NOTIFICATION: String  = "noticeable_notification" // 2016/11/9
        // toggle checklist item on thing card
        const val KEY_TOGGLE_CLI_OTC: String           = "toggle_cli_otc"
        const val KEY_SIMPLE_FCLI: String              = "simple_fcli"
        const val KEY_AUTO_LINK: String                = "auto_link" // 2016/11/11
        const val KEY_AUTOPLAY_COVER_DYNAMIC: String   = "autoplay_cover_dynamic" // 2026/6/30
        const val KEY_AUTOPLAY_DETAIL_DYNAMIC: String  = "autoplay_detail_dynamic" // 2026/7/25
        const val KEY_TWICE_BACK: String               = "twice_back"
        const val KEY_LANGUAGE_CODE: String            = "language_code"
        const val KEY_FOLLOW_SYSTEM_DARK_MODE: String  = "follow_system_dark_mode"
        const val KEY_FORCE_DARK_MODE: String          = "force_dark_mode"

        const val KEY_RINGTONE_REMINDER: String        = "ringtone_reminder"
        const val KEY_RINGTONE_HABIT: String           = "ringtone_habit"
        const val KEY_RINGTONE_GOAL: String            = "ringtone_goal"
        const val KEY_RINGTONE_AUTO_NOTIFY: String     = "ringtone_auto_notify"

        const val KEY_AUTO_SAVE_EDITS: String          = "auto_save_edits"

        const val KEY_HOME_EMPTY_STATE_HISTORY_INITIALIZED: String =
                "home_empty_state_history_initialized"
        const val KEY_HOME_EMPTY_STATE_HAS_CREATED_USER_CONTENT: String =
                "home_empty_state_has_created_user_content"
        const val KEY_HOME_EMPTY_STATE_HAS_CREATED_NOTE: String =
                "home_empty_state_has_created_note"
        const val KEY_HOME_EMPTY_STATE_HAS_CREATED_REMINDER: String =
                "home_empty_state_has_created_reminder"
        const val KEY_HOME_EMPTY_STATE_HAS_CREATED_HABIT: String =
                "home_empty_state_has_created_habit"
        const val KEY_HOME_EMPTY_STATE_HAS_CREATED_GOAL: String =
                "home_empty_state_has_created_goal"

        const val KEY_PRIVATE_PASSWORD: String         = "private_password"
        const val KEY_USE_FINGERPRINT: String          = "use_fingerprint"

        // added on 2016/11/22
        const val KEY_AUTO_START_DOING: String         = "auto_start_doing"
        const val KEY_AUTO_STRICT_MODE: String         = "auto_strict_mode"

        const val KEY_DOING_DIGIT_STYLE: String        = "doing_digit_style"
        const val KEY_DOING_DIGIT_RENDER: String       = "doing_digit_render"
        // added on 2016/11/26
        const val KEY_ASD_TIME_REMINDER: String        = "asd_time_reminder"
        const val KEY_ASD_TIME_HABIT: String           = "asd_time_habit"

        const val KEY_QUICK_CREATE: String             = "quick_create"
        const val KEY_CLOSE_NOTIFICATION_LATER: String = "close_notification_later" // 2017/1/18
        const val KEY_ONGOING_LOCKSCREEN: String       = "ongoing_lockscreen"
        const val KEY_DAILY_TODO: String               = "daily_todo" // 2017/5/9
        const val KEY_AUTO_NOTIFY: String              = "auto_notify"

        const val KEY_ONGOING_THING_ID: String         = "ongoing_thing_id"

        const val KEY_1_0_3_TO_1_0_4: String           = "1.0.3_to_1.0.4"
        const val KEY_1_0_4_TO_1_0_5: String           = "1.0.4_to_1.0.5"
        const val KEY_1_1_4_TO_1_1_5: String           = "1.1.4_to_1.1.5"
        const val KEY_1_2_7_TO_1_3_0: String           = "1.2.7_to_1.3.0"
        const val KEY_1_3_0_TO_1_3_1: String           = "1.3.0_to_1.3.1"
        const val KEY_1_3_3_TO_1_3_4: String           = "1.3.3_to_1.3.4"

        const val KEY_NOTIFY_KEEP_ALARMS: String       = "notify_keep_alarms"

        const val KEY_CREATE_ANIMATION_STYLE: String   = "create_animation_style"

    }

    object ThingStatus {

        const val UNDERWAY: Int = 0
        const val FINISHED: Int = 1
        const val DELETED: Int  = 2

    }

    object Database {

        const val TABLE_THINGS: String              = "things"
        const val COLUMN_ID_THINGS: String          = "id"
        const val COLUMN_TYPE_THINGS: String        = "type"
        const val COLUMN_STATE_THINGS: String       = "state"
        const val COLUMN_COLOR_THINGS: String       = "color"
        const val COLUMN_TITLE_THINGS: String       = "title"
        const val COLUMN_CONTENT_THINGS: String     = "content"
        const val COLUMN_ATTACHMENT_THINGS: String  = "attachment"
        const val COLUMN_LOCATION_THINGS: String    = "location"
        const val COLUMN_CREATE_TIME_THINGS: String = "create_time"
        const val COLUMN_UPDATE_TIME_THINGS: String = "update_time"
        const val COLUMN_FINISH_TIME_THINGS: String = "finish_time"
        const val COLUMN_BACKGROUND_THINGS: String  = "background" /* added in version 9 */
        const val COLUMN_THING_CARD_SPAN_MODE_THINGS: String = "thing_card_span_mode" /* renamed in version 12 */
        const val COLUMN_THING_CARD_IMAGE_PLACEMENT_THINGS: String = "thing_card_image_placement" /* renamed in version 12 */
        const val COLUMN_THING_CARD_APPEARANCE_THINGS: String = "thing_card_appearance" /* added in version 13 */
        const val COLUMN_DETAIL_ATTACHMENT_MEDIA_APPEARANCE_THINGS: String = "detail_attachment_media_appearance" /* added in version 14 */
        const val COLUMN_FOLDER_ID_THINGS: String = "folder_id" /* added in version 15 */
        const val COLUMN_STATE_BEFORE_DELETE_THINGS: String = "state_before_delete" /* added in version 18 */
        const val COLUMN_LEGACY_HOME_CARD_SPAN_MODE_THINGS: String = "home_card_span_mode" /* added in version 10, renamed in version 12 */
        const val COLUMN_LEGACY_HOME_CARD_IMAGE_PLACEMENT_THINGS: String = "home_card_image_placement" /* added in version 11, renamed in version 12 */

        const val TABLE_THING_FOLDERS: String = "thing_folders"
        const val COLUMN_ID_THING_FOLDERS: String = "id"
        const val COLUMN_PARENT_FOLDER_ID_THING_FOLDERS: String = "parent_folder_id"
        const val COLUMN_TITLE_THING_FOLDERS: String = "title"
        const val COLUMN_STATE_THING_FOLDERS: String = "state"
        const val COLUMN_COLOR_THING_FOLDERS: String = "color"
        const val COLUMN_BACKGROUND_THING_FOLDERS: String = "background"
        const val COLUMN_LOCATION_THING_FOLDERS: String = "location"
        const val COLUMN_IS_PRIVATE_THING_FOLDERS: String = "is_private"
        const val COLUMN_CREATE_TIME_THING_FOLDERS: String = "create_time"
        const val COLUMN_UPDATE_TIME_THING_FOLDERS: String = "update_time"
        const val COLUMN_CARD_PRESENTATION_THING_FOLDERS: String = "card_presentation"

        const val TABLE_REMINDERS: String                 = "reminders"
        const val COLUMN_ID_REMINDERS: String             = "id"
        const val COLUMN_NOTIFY_TIME_REMINDERS: String    = "notify_time"
        const val COLUMN_STATE_REMINDERS: String          = "state"
        const val COLUMN_NOTIFY_MILLIS_REMINDERS: String  = "notify_millis"
        const val COLUMN_CREATE_TIME_REMINDERS: String    = "create_time"
        const val COLUMN_UPDATE_TIME_REMINDERS: String    = "update_time"

        const val TABLE_HABITS: String                 = "habits"
        const val COLUMN_ID_HABITS: String             = "id"
        const val COLUMN_TYPE_HABITS: String           = "type"
        const val COLUMN_REMINDED_TIMES_HABITS: String = "reminded_times"
        const val COLUMN_DETAIL_HABITS: String         = "detail"
        const val COLUMN_RECORD_HABITS: String         = "record"
        const val COLUMN_INTERVAL_INFO_HABITS: String  = "interval_info"
        const val COLUMN_CREATE_TIME_HABITS: String    = "create_time"
        const val COLUMN_FIRST_TIME_HABITS: String     = "first_time"

        const val TABLE_HABIT_REMINDERS: String              = "habit_reminders"
        const val COLUMN_ID_HABIT_REMINDERS: String          = "id"
        const val COLUMN_HABIT_ID_HABIT_REMINDERS: String    = "habit_id"
        const val COLUMN_NOTIFY_TIME_HABIT_REMINDERS: String = "notify_time"

        const val TABLE_HABIT_RECORDS: String               = "habit_records"
        const val COLUMN_ID_HABIT_RECORDS: String           = "id"
        const val COLUMN_HABIT_ID_HABIT_RECORDS: String     = "habit_id"
        const val COLUMN_HR_ID_HABIT_RECORDS: String        = "habit_reminder_id"
        const val COLUMN_RECORD_TIME_HABIT_RECORDS: String  = "record_time"
        const val COLUMN_RECORD_YEAR_HABIT_RECORDS: String  = "record_year"
        const val COLUMN_RECORD_MONTH_HABIT_RECORDS: String = "record_month"
        const val COLUMN_RECORD_WEEK_HABIT_RECORDS: String  = "record_week"
        const val COLUMN_RECORD_DAY_HABIT_RECORDS: String   = "record_day"
        const val COLUMN_TYPE_HABIT_RECORDS: String         = "type"

        const val TABLE_APP_WIDGET: String           = "app_widget"
        const val COLUMN_ID_APP_WIDGET: String       = "id"
        const val COLUMN_THING_ID_APP_WIDGET: String = "thing_id"
        const val COLUMN_SIZE_APP_WIDGET: String     = "size"
        const val COLUMN_ALPHA_APP_WIDGET: String    = "alpha"
        const val COLUMN_STYLE_APP_WIDGET: String    = "style"
        const val COLUMN_TARGET_FOLDER_ID_APP_WIDGET: String = "target_folder_id" /* added in version 16 */
        const val COLUMN_TYPE_FILTER_MASK_APP_WIDGET: String = "type_filter_mask" /* added in version 16 */
        const val COLUMN_DISPLAY_MODE_APP_WIDGET: String = "display_mode" /* added in version 16 */
        const val COLUMN_STATUS_APP_WIDGET: String = "status" /* added in version 19 */

        const val TABLE_DOING_RECORDS: String             = "doing_records"
        const val COLUMN_ID_DOING: String                 = "id"
        const val COLUMN_THING_ID_DOING: String           = "thing_id"
        const val COLUMN_THING_TYPE_DOING: String         = "thing_type"
        const val COLUMN_ADD5_TIMES_DOING: String         = "add5_times"
        const val COLUMN_PLAYED_TIMES_DOING: String       = "played_times"
        const val COLUMN_TOTAL_PLAY_TIME_DOING: String    = "total_play_time"
        const val COLUMN_PREDICT_DOING_TIME_DOING: String = "predict_doing_time"
        const val COLUMN_START_TIME_DOING: String         = "start_time"
        const val COLUMN_END_TIME_DOING: String           = "end_time"
        const val COLUMN_STOP_REASON_DOING: String        = "stop_reason"
        const val COLUMN_START_TYPE_DOING: String         = "start_type"
        const val COLUMN_SHOULD_ASM_DOING: String         = "should_asm"

    }

    object Communication {

        private const val PREFIX: String = "com.ywwynm.everythingdone."

        const val REQUEST_ACTIVITY_THINGS: Int       = 0
        const val REQUEST_ACTIVITY_DETAIL: Int       = 1
        const val REQUEST_ACTIVITY_IMAGE_VIEWER: Int = 2
        const val REQUEST_ACTIVITY_SETTINGS: Int     = 3
        const val REQUEST_TAKE_PHOTO: Int            = 4
        const val REQUEST_CAPTURE_VIDEO: Int         = 5
        const val REQUEST_CHOOSE_MEDIA_FILE: Int     = 6
        const val REQUEST_CHOOSE_IMAGE_FILE: Int     = 7
        const val REQUEST_CHOOSE_AUDIO_FILE: Int     = 8

        const val REQUEST_PERMISSION_TAKE_PHOTO: Int          = 0
        const val REQUEST_PERMISSION_SHOOT_VIDEO: Int         = 1
        const val REQUEST_PERMISSION_RECORD_AUDIO: Int        = 2
        const val REQUEST_PERMISSION_CHOOSE_MEDIA_FILE: Int   = 3
        const val REQUEST_PERMISSION_SCREENSHOT: Int          = 4
        const val REQUEST_PERMISSION_SHARE_APP: Int           = 5
        const val REQUEST_PERMISSION_CHOOSE_IMAGE_FILE: Int   = 6
        const val REQUEST_PERMISSION_CHOOSE_AUDIO_FILE: Int   = 7
        const val REQUEST_PERMISSION_BACKUP: Int              = 8
        const val REQUEST_PERMISSION_RESTORE: Int             = 9
        const val REQUEST_PERMISSION_SEND_ERROR_FEEDBACK: Int = 10
        const val REQUEST_PERMISSION_LOAD_THINGS: Int         = 11 // ThingsActivity
        const val REQUEST_PERMISSION_EXPORT_MAIN: Int         = 12
        const val REQUEST_PERMISSION_EXPORT_DETAIL: Int       = 13
        const val REQUEST_PERMISSION_LOAD_THING: Int          = 14 // DetailActivity
        const val REQUEST_PERMISSION_LOAD_THINGS_2: Int       = 15 // BaseThingWidgetConfiguration

        // On some sony/oneplus devices, reading ringtone's title needs READ_EXTERNAL_SDCARD
        // permission, so we request it directly before SettingsActivity's init.
        const val REQUEST_PERMISSION_OPEN_SETTINGS: Int       = 16
        const val REQUEST_PERMISSION_CAMERA_COLOR: Int        = 18

        // added on 2017/3/28 to implement restore2
        const val REQUEST_CHOOSE_BACKUP_FILE: Int             = 17
        const val REQUEST_CREATE_BACKUP_FILE: Int            = 19

        const val REQUEST_PERMISSION_NOTIFICATION: Int        = 20

        const val KEY_SENDER_NAME: String          = PREFIX + "key.sender_name"
        const val KEY_DETAIL_ACTIVITY_TYPE: String = PREFIX + "key.detail_activity_type"

        const val KEY_THING: String        = PREFIX + "key.thing"
        const val KEY_ID: String           = PREFIX + "key.id"
        const val KEY_FOLDER_ID: String    = PREFIX + "key.folder_id"
        const val KEY_COLOR: String        = PREFIX + "key.color"
        /** JSON encoding of a [com.ywwynm.everythingdone.model.ThingBackground]; added Phase 4. */
        const val KEY_BACKGROUND: String   = PREFIX + "key.background"
        const val KEY_POSITION: String     = PREFIX + "key.position"
        /**
         * 标记“该动作广播来自全屏通知（NoticeableNotificationActivity），其内部已对私密记事鉴权过”，
         * 让动作 receiver 跳过自身的有效私密二次鉴权，避免“点完成 → 验证 → 又被要求验证一次”。仅
         * app 内部显式广播携带；桌面小部件等外部广播不带，仍各自鉴权。
         */
        const val KEY_ALREADY_AUTHENTICATED: String = PREFIX + "key.already_authenticated"
        const val KEY_LIST_POSITION: String = PREFIX + "key.list_position"
        const val KEY_LIST_PROJECTION: String = PREFIX + "key.list_projection"
        const val KEY_TYPE_BEFORE: String  = PREFIX + "key.type_before"
        const val KEY_STATE_AFTER: String  = PREFIX + "key.state_after"
        const val KEY_CREATED_DONE: String = PREFIX + "key.created_done"

        const val KEY_RESULT_CODE: String = PREFIX + "key.result_code"

        const val KEY_CALL_CHANGE: String = PREFIX + "key.call_change"

        const val KEY_EDITABLE: String       = PREFIX + "key.editable"
        const val KEY_TYPE_PATH_NAME: String = PREFIX + "key.type_path_name"
        /** 与 KEY_TYPE_PATH_NAME 等长的 Thing Card Video Frame 数组，-1 表示未设置。2026/7/25 */
        const val KEY_VIDEO_FRAME_MS_LIST: String = PREFIX + "key.video_frame_ms_list"

        const val KEY_TIME: String = PREFIX + "key.time"

        const val KEY_HELP_TITLES: String   = PREFIX + "key.help_titles"
        const val KEY_HELP_CONTENTS: String = PREFIX + "key.help_contents"

        const val KEY_WIDGET_ID: String        = PREFIX + "key.widget_id"
        const val KEY_STATUS: String           = PREFIX + "key.status"
        const val KEY_TYPE_FILTER_MASK: String = PREFIX + "key.type_filter_mask"
        const val KEY_FOLDER_AUTHENTICATED: String = PREFIX + "key.folder_authenticated"

        // added for notification action for private thing
        const val KEY_TITLE: String            = PREFIX + "key.title"

        const val RESULT_NO_UPDATE: Int                        = 0
        const val RESULT_CREATE_THING_DONE: Int                = 1
        const val RESULT_CREATE_BLANK_THING: Int               = 2
        const val RESULT_ABANDON_NEW_THING: Int                = 3
        const val RESULT_UPDATE_THING_DONE_TYPE_SAME: Int      = 4
        const val RESULT_UPDATE_THING_DONE_TYPE_DIFFERENT: Int = 5
        const val RESULT_UPDATE_THING_STATE_DIFFERENT: Int     = 6
        const val RESULT_STICKY_THING_OR_CANCEL: Int           = 7
        const val RESULT_DOING_OR_CANCEL: Int                  = 8
        const val RESULT_JUST_NOTIFY_DATASET_CHANGED: Int      = 9

        const val RESULT_UPDATE_IMAGE_DONE: Int = 1

        const val RESULT_UPDATE_DRAWER_HEADER_DONE: Int = 1

        const val NOTIFICATION_ACTION_FINISH: String =
                PREFIX + "action.notification.finish"
        const val NOTIFICATION_ACTION_DELAY: String  =
                PREFIX + "action.notification.delay"
        const val NOTIFICATION_ACTION_START_DOING: String =
                PREFIX + "action.notification.start_doing"
        const val NOTIFICATION_ACTION_GET_IT: String =
                PREFIX + "action.notification.get_it"
        const val NOTIFICATION_ACTION_CANCEL: String =
                PREFIX + "action.notification.cancel"

        // added for finish(or finish this time) action in single thing app widget
        const val WIDGET_ACTION_FINISH: String = PREFIX + "action.widget.finish"

        const val BROADCAST_ACTION_UPDATE_MAIN_UI: String     =
                PREFIX + "action.broadcast.update_main_ui"

        const val BROADCAST_ACTION_FINISH_DETAILACTIVITY: String =
                PREFIX + "action.broadcast.finish_detailactivity"

        // added for update checklist in single thing app widget
        const val BROADCAST_ACTION_UPDATE_CHECKLIST: String   =
                PREFIX + "action.broadcast.update_checklist"

        // added for in-app language setting
        const val BROADCAST_ACTION_RESP_LOCALE_CHANGE: String =
                PREFIX + "action.broadcast.resp_locale_change"

        const val AUTHENTICATE_ACTION_VIEW: String   =
                PREFIX + "action.authenticate.view"

        // added mainly for notification action for private thing
        const val AUTHENTICATE_ACTION_FINISH: String =
                PREFIX + "action.authenticate.finish"
        const val AUTHENTICATE_ACTION_DELAY: String  =
                PREFIX + "action.authenticate.delay"
        const val AUTHENTICATE_ACTION_START_DOING: String =
                PREFIX + "action.authenticate.start_doing"

        const val SHORTCUT_ACTION_CREATE: String =
                PREFIX + "action.shortcut.create"
        const val SHORTCUT_ACTION_CHECK_UPCOMING: String =
                PREFIX + "action.shortcut.check_upcoming"
        const val SHORTCUT_ACTION_CHECK_STICKY: String =
                PREFIX + "action.shortcut.check_sticky"

    }

    object PickerType {

        const val COLOR_HAVE_ALL: Int             = 0
        const val COLOR_NO_ALL: Int               = 1
        /** Phase 6: 10 palette FABs + 2 trailing "random pure" / "random gradient" FABs. */
        const val COLOR_EDIT: Int                 = 8
        /** Phase 6: 1 "all colours" sentinel + 8 hue-bucket FABs for search-by-similar-colour. */
        const val HUE_BUCKET: Int                 = 9
        const val AFTER_TIME: Int                 = 2
        const val TIME_TYPE_NO_HOUR_MINUTE: Int   = 3
        const val TIME_TYPE_HAVE_HOUR_MINUTE: Int = 4
        const val DAY_OF_WEEK: Int                = 5
        const val DAY_OF_MONTH: Int               = 6
        const val MONTH_OF_YEAR: Int              = 7

    }
}
