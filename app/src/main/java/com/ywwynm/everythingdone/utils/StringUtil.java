package com.ywwynm.everythingdone.utils;

import android.text.TextUtils;

/**
 * Created by ywwynm on 2016/10/20.
 * some utils to operate String
 */
public class StringUtil {

    public static final String TAG = "StringUtil";

    private StringUtil() {}

    public static String upperFirst(String s) {
        if (TextUtils.isEmpty(s)) {
            return "";
        }
        String first = String.valueOf(s.charAt(0));
        return first.toUpperCase() + s.substring(1, s.length());
    }

    public static String lowerFirst(String s) {
        if (TextUtils.isEmpty(s)) {
            return "";
        }
        String first = String.valueOf(s.charAt(0));
        return first.toLowerCase() + s.substring(1, s.length());
    }

    public static String replaceChineseBrackets(String s) {
        if (TextUtils.isEmpty(s)) {
            return "";
        }
        return s.replaceAll("（", "(").replaceAll("）", ")");
    }

}
