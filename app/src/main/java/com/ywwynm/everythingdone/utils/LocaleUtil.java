package com.ywwynm.everythingdone.utils;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.support.annotation.NonNull;
import android.util.DisplayMetrics;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.FrequentSettings;
import com.ywwynm.everythingdone.R;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * Created by ywwynm on 2015/8/3.
 * Helper for localization
 */
public class LocaleUtil {

    public static final String TAG = "EverythingDone$LocaleUtil";

    private LocaleUtil() {}

    @SuppressWarnings("deprecation")
    public static Locale getSystemLocale(Context context) {
        if (DeviceUtil.hasNougatApi()) {
            return context.getResources().getConfiguration().getLocales().get(0);
        } else {
            return context.getResources().getConfiguration().locale;
        }
    }

    @SuppressWarnings("deprecation")
    public static void setAppLocale(Configuration configuration, Locale locale) {
        if (DeviceUtil.hasNougatApi()) {
            configuration.setLocale(locale);
        } else {
            configuration.locale = locale;
        }
    }

    public static boolean isChinese(Context context) {
        return isSimplifiedChinese(context) || isTraditionalChinese(context);
    }

    public static boolean isSimplifiedChinese(Context context) {
        return getSystemLocale(context).getLanguage()
                .equals(Locale.SIMPLIFIED_CHINESE.getLanguage());
    }

    public static boolean isTraditionalChinese(Context context) {
        return getSystemLocale(context).getLanguage()
                .equals(Locale.TRADITIONAL_CHINESE.getLanguage());
    }

    public static final String LANGUAGE_CODE_FOLLOW_SYSTEM = "follow system";

    public static String getLanguageDescription(String languageCode) {
        Resources res = App.getApp().getResources();
        String[] lanCodes = res.getStringArray(R.array.language_codes);
        int index = 0;
        for (int i = 0; i < lanCodes.length; i++) {
            if (lanCodes[i].equals(languageCode)) {
                index = i;
                break;
            }
        }
        return res.getStringArray(R.array.languages)[index];
    }

    public static void changeLanguage() {
        String languageCode = FrequentSettings.getString(
                Def.Meta.KEY_LANGUAGE_CODE, LANGUAGE_CODE_FOLLOW_SYSTEM + "_");
        String[] lanCon = languageCode.split("_");
        if (lanCon.length == 1) {
            String lan = lanCon[0];
            lanCon = new String[2];
            lanCon[0] = lan;
            lanCon[1] = "";
        }
        changeLanguage(lanCon[0], lanCon[1]);
    }

    public static void changeLanguage(@NonNull String language, @NonNull String countryOrDistinct) {
        if (LANGUAGE_CODE_FOLLOW_SYSTEM.equals(language)) {
            language = Locale.getDefault().getLanguage();
            countryOrDistinct = Locale.getDefault().getCountry();
        }
        Resources resources = App.getApp().getResources();
        DisplayMetrics dm = resources.getDisplayMetrics();
        Configuration configuration = resources.getConfiguration();
        setAppLocale(configuration, new Locale(language, countryOrDistinct));
        resources.updateConfiguration(configuration, dm);
    }

    public static String getTimesStr(Context context, int times) {
        return getTimesStr(context, times, true);
    }

    public static String getTimesStr(Context context, int times, boolean shouldHasGapStr) {
        String timesStr = context.getString(R.string.times);
        if (isChinese(context)) {
            return times + (shouldHasGapStr ? " " : "") + timesStr;
        } else {
            if (times == 0) {
                return "0 time";
            } else if (times == 1) {
                return "once";
            } else if (times == 2) {
                return "twice";
            } else {
                return times + " " + timesStr + "s";
            }
        }
    }

    public static String getPercentStr(int num1, int num2) {
        if (num2 == 0) {
            return "0 %";
        } else {
            NumberFormat nf = NumberFormat.getPercentInstance();
            nf.setMaximumFractionDigits(2);
            String str = nf.format((float) num1 / num2);
            return str.substring(0, str.length() - 1) + " %";
        }
    }

}
