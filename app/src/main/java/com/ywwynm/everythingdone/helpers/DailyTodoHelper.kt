package com.ywwynm.everythingdone.helpers

import android.content.Context
import androidx.core.util.Pair

import com.ywwynm.everythingdone.R

/**
 * Created by ywwynm on 2017/5/9.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * A helper class for creating daily to-do automatically
 */
object DailyTodoHelper {

    const val TAG: String = "DailyTodoHelper"

    @JvmStatic
    fun getDailyTodoItems(context: Context?): List<String?> {
        return listOf(
            context!!.getString(R.string.disable),
            "5:00",
            "5:30",
            "6:00",
            "6:30",
            "7:00",
            "7:30",
            "8:00",
            "8:30"
        )
    }

    @JvmStatic
    fun getDailyTodoTimePairs(): List<Pair<Int, Int>?> {
        return listOf(
            Pair(-1, -1),
            Pair(5, 0),
            Pair(5, 30),
            Pair(6, 0),
            Pair(6, 30),
            Pair(7, 0),
            Pair(7, 30),
            Pair(8, 0),
            Pair(8, 30)
        )
    }

}
