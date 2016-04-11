package com.ywwynm.everythingdone.utils;

import android.content.Context;

import com.ywwynm.everythingdone.R;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * Created by ywwynm on 2015/8/3.
 * Helper for localization
 */
public class LocaleUtil {

    public static final String TAG = "EverythingDone$LocaleUtil";

    public static boolean isChinese(Context context) {
        return isSimplifiedChinese(context) || isTraditionalChinese(context);
    }

    public static boolean isSimplifiedChinese(Context context) {
        Locale locale = context.getResources().getConfiguration().locale;
        return locale.getLanguage().equals(Locale.SIMPLIFIED_CHINESE.getLanguage());
    }

    public static boolean isTraditionalChinese(Context context) {
        Locale locale = context.getResources().getConfiguration().locale;
        return locale.getLanguage().equals(Locale.TRADITIONAL_CHINESE.getLanguage());
    }

    public static boolean isEnglish(Context context) {
        Locale locale = context.getResources().getConfiguration().locale;
        return locale.getLanguage().equals(Locale.ENGLISH.getLanguage());
    }

    public static String getTimesStr(Context context, int times) {
        String timesStr = context.getString(R.string.times);
        if (isChinese(context)) {
            return times + " " + timesStr;
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
