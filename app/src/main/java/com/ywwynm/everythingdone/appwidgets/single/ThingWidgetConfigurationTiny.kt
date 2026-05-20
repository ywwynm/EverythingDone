package com.ywwynm.everythingdone.appwidgets.single

/**
 * Created by ywwynm on 2016/8/2.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Configuration Activity for tiny thing widget
 */
open class ThingWidgetConfigurationTiny : BaseThingWidgetConfiguration() {
    override fun getSenderClass(): Class<*>? {
        return ThingWidgetTiny::class.java
    }
}
