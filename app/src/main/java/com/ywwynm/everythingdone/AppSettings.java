package com.ywwynm.everythingdone;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;

/**
 * Created by ywwynm on 2017/1/17.
 * Store settings for EverythingDone
 */
public class AppSettings {

    public static final String TAG = "AppSettings";

    private static HashMap<String, Object> settingsMap;

    static {
        loadFromSharedPreferences();
    }

    private AppSettings() {}

    private static void loadFromSharedPreferences() {
        App app = App.getApp();
        SharedPreferences sp = app.getSharedPreferences(
                Def.Meta.PREFERENCES_NAME, Context.MODE_PRIVATE);
        settingsMap = new HashMap<>();

        boolean simpleFCli = sp.getBoolean(Def.Meta.KEY_SIMPLE_FCLI, false);
        settingsMap.put(Def.Meta.KEY_SIMPLE_FCLI, simpleFCli);
    }

    private static boolean getBooleanFromSp(String key) {
        SharedPreferences sp = App.getApp().getSharedPreferences(
                Def.Meta.PREFERENCES_NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(key, false);
    }

    public static boolean getBoolean(String key) {
        if (settingsMap.containsKey(key)) {
            return (boolean) settingsMap.get(key);
        } else {
            return getBooleanFromSp(key);
        }
    }

}
