package com.ywwynm.everythingdone.views.recording

import android.content.Context
import android.os.Build
import com.ywwynm.everythingdone.Def
import java.io.File

/** 通知"停止并保留"后待用户处理的录音；持久化以跨进程回收存活。 */
data class StoppedRecordingSession(
    val file: File,
    val thingId: Long,
    val durationMillis: Long,
    val mode: AudioInputMode,
    /** 停止原因（容量上限、投影撤销等）；跨进程恢复后 Dialog 仍能说明为何停止。 */
    val notice: AudioRecordingNotice = AudioRecordingNotice.NONE
)

object AudioInputPreferences {
    private const val KEY_AUDIO_INPUT_MODE = "audio_recording_input_mode"
    private const val KEY_AUDIO_INPUT_MODE_CONFIRMED = "audio_recording_input_mode_confirmed"
    private const val KEY_NOTIFICATION_PERMISSION_REQUESTED =
        "audio_recording_notification_permission_requested"
    private const val KEY_DIRECTION_SAMPLE_DELAY_HINT_SHOWN =
        "audio_recording_direction_sample_delay_hint_shown"
    private const val KEY_STOPPED_FILE = "audio_recording_stopped_file"
    private const val KEY_STOPPED_THING_ID = "audio_recording_stopped_thing_id"
    private const val KEY_STOPPED_DURATION = "audio_recording_stopped_duration"
    private const val KEY_STOPPED_MODE = "audio_recording_stopped_mode"
    private const val KEY_STOPPED_NOTICE = "audio_recording_stopped_notice"

    fun load(context: Context): AudioInputMode {
        val preferences = preferences(context)
        val value = preferences.getString(KEY_AUDIO_INPUT_MODE, null)
        val decoded = AudioInputMode.fromPreference(value)
        val stored = AudioInputSelectionPersistencePolicy.restore(
            storedMode = decoded,
            systemModeConfirmed = preferences.getBoolean(KEY_AUDIO_INPUT_MODE_CONFIRMED, false)
        )
        return if (stored != decoded ||
            stored.requiresSystemAudio && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        ) {
            save(context, AudioInputMode.MICROPHONE)
            AudioInputMode.MICROPHONE
        } else {
            stored
        }
    }

    fun save(context: Context, mode: AudioInputMode) {
        preferences(context).edit()
            .putString(KEY_AUDIO_INPUT_MODE, mode.preferenceValue)
            .putBoolean(KEY_AUDIO_INPUT_MODE_CONFIRMED, true)
            .apply()
    }

    /**
     * 持久化通知停止后的待处理录音。服务被系统回收（Android 8+ 会主动停止空闲后台
     * 服务）后，重建的服务据此恢复停止态快照，已录 WAV 不会变成无人认领的孤儿文件。
     */
    fun saveStoppedSession(context: Context, session: StoppedRecordingSession) {
        preferences(context).edit()
            .putString(KEY_STOPPED_FILE, session.file.absolutePath)
            .putLong(KEY_STOPPED_THING_ID, session.thingId)
            .putLong(KEY_STOPPED_DURATION, session.durationMillis)
            .putString(KEY_STOPPED_MODE, session.mode.preferenceValue)
            .putString(KEY_STOPPED_NOTICE, session.notice.name)
            .apply()
    }

    /** 读取待处理停止态；WAV 已不存在（被手动清理等）时清除记录并返回 null。 */
    fun loadStoppedSession(context: Context): StoppedRecordingSession? {
        val preferences = preferences(context)
        val path = preferences.getString(KEY_STOPPED_FILE, null) ?: return null
        val file = File(path)
        if (!file.exists()) {
            clearStoppedSession(context)
            return null
        }
        return StoppedRecordingSession(
            file = file,
            thingId = preferences.getLong(KEY_STOPPED_THING_ID, -1L),
            durationMillis = preferences.getLong(KEY_STOPPED_DURATION, 0L),
            mode = AudioInputMode.fromPreference(preferences.getString(KEY_STOPPED_MODE, null)),
            notice = preferences.getString(KEY_STOPPED_NOTICE, null)
                ?.let { value -> AudioRecordingNotice.entries.firstOrNull { it.name == value } }
                ?: AudioRecordingNotice.NONE
        )
    }

    fun clearStoppedSession(context: Context) {
        preferences(context).edit()
            .remove(KEY_STOPPED_FILE)
            .remove(KEY_STOPPED_THING_ID)
            .remove(KEY_STOPPED_DURATION)
            .remove(KEY_STOPPED_MODE)
            .remove(KEY_STOPPED_NOTICE)
            .apply()
    }

    /** 快查询：待处理停止态所属的记事 id；无记录（或文件已失效）返回 -1。 */
    fun stoppedSessionThingId(context: Context): Long =
        loadStoppedSession(context)?.thingId ?: -1L

    fun hasRequestedNotificationPermission(context: Context): Boolean =
        preferences(context).getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)

    fun markNotificationPermissionRequested(context: Context) {
        preferences(context).edit().putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true).apply()
    }

    fun hasShownDirectionSampleDelayHint(context: Context): Boolean =
        preferences(context).getBoolean(KEY_DIRECTION_SAMPLE_DELAY_HINT_SHOWN, false)

    fun markDirectionSampleDelayHintShown(context: Context) {
        preferences(context).edit()
            .putBoolean(KEY_DIRECTION_SAMPLE_DELAY_HINT_SHOWN, true)
            .apply()
    }

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(
        Def.Meta.PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )
}
