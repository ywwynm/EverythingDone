package com.ywwynm.everythingdone.model

import android.database.Cursor
import androidx.annotation.IntDef
import com.ywwynm.everythingdone.services.DoingService

/**
 * Created by qiizhang on 2016/11/9.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * model layer for table "doing_records"
 */
open class DoingRecord(
    var id: Long,
    var thingId: Long,
    var thingType: Int,
    var add5Times: Int,
    var playedTimes: Int,
    var totalPlayTime: Long,
    var predictDoingTime: Long,
    var startTime: Long,
    var endTime: Long,
    var stopReason: Int,
    var startType: Int,
    @get:JvmName("shouldAutoStrictMode")
    var shouldAutoStrictMode: Boolean
) {

    constructor(cursor: Cursor?) : this(
        cursor!!.getLong(0),
        cursor.getLong(1),
        cursor.getInt(2),
        cursor.getInt(3),
        cursor.getInt(4),
        cursor.getLong(5),
        cursor.getLong(6),
        cursor.getLong(7),
        cursor.getLong(8),
        cursor.getInt(9),
        cursor.getInt(10),
        cursor.getInt(11) != 0
    )

    @IntDef(0, 1, 2, 3, 4, 5)
    @Retention(AnnotationRetention.SOURCE)
    annotation class StopReason

    companion object {
        const val STOP_REASON_CANCEL_CARELESS: Int   = 0
        const val STOP_REASON_CANCEL_NEXT_ALARM: Int = 1
        const val STOP_REASON_CANCEL_USER: Int       = 2
        const val STOP_REASON_CANCEL_OTHER: Int      = 3
        const val STOP_REASON_FINISH: Int            = 4
        const val STOP_REASON_INIT_FAILED: Int       = 5
    }
}
