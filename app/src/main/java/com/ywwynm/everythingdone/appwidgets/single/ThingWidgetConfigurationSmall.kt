package com.ywwynm.everythingdone.appwidgets.single

/**
 * Created by ywwynm on 2016/8/2.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Configuration Activity for small thing widget
 */
open class ThingWidgetConfigurationSmall : BaseThingWidgetConfiguration() {
    override fun getSenderClass(): Class<*>? {
        return ThingWidgetSmall::class.java
    }
}
