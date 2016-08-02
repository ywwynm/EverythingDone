package com.ywwynm.everythingdone.appwidgets.single;

/**
 * Created by ywwynm on 2016/8/2.
 * Configuration Activity for small thing widget
 */
public class ThingWidgetConfigurationSmall extends BaseThingWidgetConfiguration {
    @Override
    protected Class getSenderClass() {
        return ThingWidgetSmall.class;
    }
}
