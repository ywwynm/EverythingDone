package com.ywwynm.everythingdone.utils;

import android.os.Build;

/**
 * Created by ywwynm on 2016/3/11.
 * utils for checking Android SDK version
 */
public class VersionUtil {

    public static final String TAG = "EverythingDone$VersionUtil";

    public static boolean hasKitKatApi() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT;
    }
    public static boolean hasLollipopApi() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;
    }

}
