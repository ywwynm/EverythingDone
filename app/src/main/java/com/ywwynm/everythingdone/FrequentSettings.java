package com.ywwynm.everythingdone;

import android.content.Context;
import android.content.SharedPreferences;

import com.ywwynm.everythingdone.utils.LocaleUtil;

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

        String languageCode = sp.getString(Def.Meta.KEY_LANGUAGE_CODE,
                LocaleUtil.LANGUAGE_CODE_FOLLOW_SYSTEM + "_");
        settingsMap.put(Def.Meta.KEY_LANGUAGE_CODE, languageCode);

        boolean toggleCliOtc = sp.getBoolean(Def.Meta.KEY_TOGGLE_CLI_OTC, false);
        settingsMap.put(Def.Meta.KEY_TOGGLE_CLI_OTC, toggleCliOtc);

        boolean simpleFCli = sp.getBoolean(Def.Meta.KEY_SIMPLE_FCLI, false);
        settingsMap.put(Def.Meta.KEY_SIMPLE_FCLI, simpleFCli);

        boolean autoLink = sp.getBoolean(Def.Meta.KEY_AUTO_LINK, false);
        settingsMap.put(Def.Meta.KEY_AUTO_LINK, autoLink);

        boolean twiceBack = sp.getBoolean(Def.Meta.KEY_TWICE_BACK, false);
        settingsMap.put(Def.Meta.KEY_TWICE_BACK, twiceBack);

        boolean closeLater = sp.getBoolean(Def.Meta.KEY_CLOSE_NOTIFICATION_LATER, false);
        settingsMap.put(Def.Meta.KEY_CLOSE_NOTIFICATION_LATER, closeLater);

        boolean autoSaveEdits = sp.getBoolean(Def.Meta.KEY_AUTO_SAVE_EDITS, false);
        settingsMap.put(Def.Meta.KEY_AUTO_SAVE_EDITS, autoSaveEdits);

        long curOngoingId = sp.getLong(Def.Meta.KEY_ONGOING_THING_ID, -1L);
        settingsMap.put(Def.Meta.KEY_ONGOING_THING_ID, curOngoingId);
    }

    public static void put(String key, Object value) {
        settingsMap.put(key, value);
    }

    public static boolean getBoolean(String key) {
        return getBoolean(key, false);
    }
    
    public static boolean getBoolean(String key, boolean defValue) {
        if (settingsMap.containsKey(key)) {
            return (boolean) settingsMap.get(key);
        } else {
            boolean value = getBooleanFromSp(key, defValue);
            put(key, value);
            return value;
        }
    }

    public static long getLong(String key) {
        return getLong(key, -1L);
    }

    public static long getLong(String key, long defValue) {
        if (settingsMap.containsKey(key)) {
            return (Long) settingsMap.get(key);
        } else {
            long value = getLongFromSp(key, defValue);
            put(key, value);
            return value;
        }
    }

    public static String getString(String key, String defValue) {
        if (settingsMap.containsKey(key)) {
            return (String) settingsMap.get(key);
        } else {
            String value = getStringFromSp(key, defValue);
            put(key, value);
            return value;
        }
    }

    private static SharedPreferences getSp() {
        return App.getApp().getSharedPreferences(
                Def.Meta.PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    private static boolean getBooleanFromSp(String key, boolean defValue) {
        return getSp().getBoolean(key, defValue);
    }

    private static long getLongFromSp(String key, long defValue) {
        return getSp().getLong(key, defValue);
    }

    private static String getStringFromSp(String key, String defValue) {
        return getSp().getString(key, defValue);
    }

}
