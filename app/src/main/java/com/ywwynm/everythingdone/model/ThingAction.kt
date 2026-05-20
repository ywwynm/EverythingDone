package com.ywwynm.everythingdone.model

import android.os.Bundle
import androidx.annotation.IntDef

/**
 * Created by ywwynm on 2016/7/1.
 * Thing update action model
 */
open class ThingAction(type: Int, before: Any?, after: Any?) {

    private val type: Int = type
    private val before: Any? = before
    private val after: Any? = after
    private val extras: Bundle? = Bundle()

    open fun getType(): Int {
        return type
    }

    open fun getBefore(): Any? {
        return before
    }

    open fun getAfter(): Any? {
        return after
    }

    open fun getExtras(): Bundle? {
        return extras
    }

    @IntDef(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)
    @Retention(AnnotationRetention.SOURCE)
    annotation class Type

    companion object {
        const val UPDATE_TITLE: Int             = 0
        const val UPDATE_CONTENT: Int           = 1
        const val TOGGLE_CHECKLIST: Int         = 2
        const val UPDATE_CHECKLIST: Int         = 3
        const val MOVE_CHECKLIST: Int           = 4
        const val UPDATE_COLOR: Int             = 5
        const val ADD_ATTACHMENT: Int           = 6
        const val DELETE_ATTACHMENT: Int        = 7
        const val MOVE_ATTACHMENT: Int          = 8
        const val TOGGLE_REMINDER_OR_HABIT: Int = 9
        const val UPDATE_REMINDER_OR_HABIT: Int = 10
        const val TOGGLE_PRIVATE: Int           = 11

        const val KEY_ATTACHMENT_TYPE: String   = "attachment_type"
        const val KEY_CHECKBOX_STATE: String    = "checkbox_state"
        const val KEY_CURSOR_POS_BEFORE: String = "cursor_pos_before"
        const val KEY_CURSOR_POS_AFTER: String  = "cursor_pos_after"
        const val KEY_PICKED_BEFORE: String     = "picked_before"
        const val KEY_PICKED_AFTER: String      = "picked_after"
    }
}
