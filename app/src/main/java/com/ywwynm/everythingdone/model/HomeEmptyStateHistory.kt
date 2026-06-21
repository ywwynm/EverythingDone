package com.ywwynm.everythingdone.model

import android.content.Context
import androidx.core.content.edit
import com.ywwynm.everythingdone.Def

object HomeEmptyStateHistory {

    fun isInitialized(context: Context?): Boolean {
        return prefs(context).getBoolean(
            Def.Meta.KEY_HOME_EMPTY_STATE_HISTORY_INITIALIZED,
            false
        )
    }

    fun initialize(
        context: Context?,
        hasCreatedAnyUserContent: Boolean,
        createdThingTypes: Set<Int>
    ) {
        prefs(context).edit {
            putBoolean(Def.Meta.KEY_HOME_EMPTY_STATE_HISTORY_INITIALIZED, true)
            putBoolean(
                Def.Meta.KEY_HOME_EMPTY_STATE_HAS_CREATED_USER_CONTENT,
                hasCreatedAnyUserContent
            )
            putBoolean(
                Def.Meta.KEY_HOME_EMPTY_STATE_HAS_CREATED_NOTE,
                createdThingTypes.contains(Thing.NOTE)
            )
            putBoolean(
                Def.Meta.KEY_HOME_EMPTY_STATE_HAS_CREATED_REMINDER,
                createdThingTypes.contains(Thing.REMINDER)
            )
            putBoolean(
                Def.Meta.KEY_HOME_EMPTY_STATE_HAS_CREATED_HABIT,
                createdThingTypes.contains(Thing.HABIT)
            )
            putBoolean(
                Def.Meta.KEY_HOME_EMPTY_STATE_HAS_CREATED_GOAL,
                createdThingTypes.contains(Thing.GOAL)
            )
        }
    }

    fun markThingCreated(context: Context?, @Thing.Type type: Int) {
        if (!Thing.isRealThingType(type)) return
        prefs(context).edit {
            putBoolean(Def.Meta.KEY_HOME_EMPTY_STATE_HISTORY_INITIALIZED, true)
            putBoolean(Def.Meta.KEY_HOME_EMPTY_STATE_HAS_CREATED_USER_CONTENT, true)
            putBoolean(keyForThingType(type), true)
        }
    }

    fun markFolderCreated(context: Context?) {
        prefs(context).edit {
            putBoolean(Def.Meta.KEY_HOME_EMPTY_STATE_HISTORY_INITIALIZED, true)
            putBoolean(Def.Meta.KEY_HOME_EMPTY_STATE_HAS_CREATED_USER_CONTENT, true)
        }
    }

    fun hasCreatedAnyUserContent(context: Context?): Boolean {
        return prefs(context).getBoolean(
            Def.Meta.KEY_HOME_EMPTY_STATE_HAS_CREATED_USER_CONTENT,
            false
        )
    }

    fun hasCreatedThingType(context: Context?, @Thing.Type type: Int): Boolean {
        if (!Thing.isRealThingType(type)) return true
        return prefs(context).getBoolean(keyForThingType(type), false)
    }

    private fun keyForThingType(@Thing.Type type: Int): String {
        return when (type) {
            Thing.REMINDER -> Def.Meta.KEY_HOME_EMPTY_STATE_HAS_CREATED_REMINDER
            Thing.HABIT -> Def.Meta.KEY_HOME_EMPTY_STATE_HAS_CREATED_HABIT
            Thing.GOAL -> Def.Meta.KEY_HOME_EMPTY_STATE_HAS_CREATED_GOAL
            else -> Def.Meta.KEY_HOME_EMPTY_STATE_HAS_CREATED_NOTE
        }
    }

    private fun prefs(context: Context?) =
        context!!.applicationContext.getSharedPreferences(
            Def.Meta.PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
}
