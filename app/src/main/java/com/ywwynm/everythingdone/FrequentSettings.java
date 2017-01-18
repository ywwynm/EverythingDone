package com.ywwynm.everythingdone;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;

/**
 * Created by ywwynm on 2017/1/17.
 * Store settings that will be seen frequently during app lifecycle, which will also influence
 * ui very constantly. Usually, these settings belong to settings for UI that can be seen under
 * ui category in {@link com.ywwynm.everythingdone.activities.SettingsActivity}
 */
public class FrequentSettings {

    public static final String TAG = "FrequentSettings";

    private static HashMap<String, Object> settingsMap;

    static {
        loadFromSharedPreferences();
    }

    private FrequentSettings() {}

    private static void loadFromSharedPreferences() {
        App app = App.getApp();
        SharedPreferences sp = app.getSharedPreferences(
                Def.Meta.PREFERENCES_NAME, Context.MODE_PRIVATE);
        settingsMap = new HashMap<>();

        boolean toggleCliOtc = sp.getBoolean(Def.Meta.KEY_TOGGLE_CLI_OTC, false);
        settingsMap.put(Def.Meta.KEY_TOGGLE_CLI_OTC, toggleCliOtc);

        boolean simpleFCli = sp.getBoolean(Def.Meta.KEY_SIMPLE_FCLI, false);
        settingsMap.put(Def.Meta.KEY_SIMPLE_FCLI, simpleFCli);

        boolean autoLink = sp.getBoolean(Def.Meta.KEY_AUTO_LINK, false);
        settingsMap.put(Def.Meta.KEY_AUTO_LINK, autoLink);
    }

    public static void put(String key, Object value) {
        settingsMap.put(key, value);
    }

    private static boolean getBooleanFromSp(String key, boolean defaultValue) {
        SharedPreferences sp = App.getApp().getSharedPreferences(
                Def.Meta.PREFERENCES_NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(key, defaultValue);
    }

    public static boolean getBoolean(String key) {
        return getBoolean(key, false);
    }
    
    public static boolean getBoolean(String key, boolean defaultValue) {
        if (settingsMap.containsKey(key)) {
            return (boolean) settingsMap.get(key);
        } else {
            return getBooleanFromSp(key, defaultValue);
        }
    }

}
