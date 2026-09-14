package com.ywwynm.everythingdone.utils

import android.content.Context
import android.content.SharedPreferences
import com.ywwynm.everythingdone.Def

/** 新建与左滑分别选择动画；旧的 Boolean 键只用于兼容读取。 */
internal object ThingAnimationPreferences {
    const val CREATE_KEY = "thing_creation_animation"
    const val SWIPE_KEY = "swipe_complete_animation"
    const val RIPPLE = 0
    const val BORDER = 1
    const val PARTICLE = 2
    const val SLIDE = 0
    const val SWIPE_PARTICLE = 1

    fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(Def.Meta.PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun creation(context: Context): Int = creation(preferences(context))

    fun creation(preferences: SharedPreferences): Int {
        val legacy = if (preferences.getBoolean(Def.Meta.KEY_CREATE_ANIMATION_STYLE, false)) BORDER else RIPPLE
        return preferences.getInt(CREATE_KEY, legacy).takeIf { it in RIPPLE..PARTICLE } ?: legacy
    }

    fun swipe(context: Context): Int =
        preferences(context).getInt(SWIPE_KEY, SLIDE).takeIf { it in SLIDE..SWIPE_PARTICLE } ?: SLIDE
}
