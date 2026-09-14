package com.ywwynm.everythingdone.utils

import android.content.SharedPreferences
import com.ywwynm.everythingdone.Def
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class ThingAnimationPreferencesTest {
    private fun preferences(values: Map<String, Any>): SharedPreferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)
    ) { _, method, args ->
        when (method.name) {
            "getBoolean" -> (values[args[0]] ?: args[1]) as Boolean
            "getInt" -> (values[args[0]] ?: args[1]) as Int
            else -> error("未预期的偏好操作：${method.name}")
        }
    } as SharedPreferences

    @Test fun `旧Boolean设置映射到原效果且不按Int读取旧键`() {
        assertEquals(0, ThingAnimationPreferences.creation(preferences(emptyMap())))
        assertEquals(0, ThingAnimationPreferences.creation(preferences(mapOf(Def.Meta.KEY_CREATE_ANIMATION_STYLE to false))))
        assertEquals(1, ThingAnimationPreferences.creation(preferences(mapOf(Def.Meta.KEY_CREATE_ANIMATION_STYLE to true))))
    }

    @Test fun `新档位覆盖旧设置并在未知档位下保留原效果`() {
        for (mode in 0..2) {
            assertEquals(mode, ThingAnimationPreferences.creation(preferences(mapOf(
                Def.Meta.KEY_CREATE_ANIMATION_STYLE to true, ThingAnimationPreferences.CREATE_KEY to mode))))
        }
        assertEquals(1, ThingAnimationPreferences.creation(preferences(mapOf(
            Def.Meta.KEY_CREATE_ANIMATION_STYLE to true, ThingAnimationPreferences.CREATE_KEY to 999))))
    }
}
