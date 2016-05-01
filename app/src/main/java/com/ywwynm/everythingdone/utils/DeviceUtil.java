package com.ywwynm.everythingdone.utils;

import android.os.Build;

/**
 * Created by ywwynm on 2016/3/11.
 * utils to get device information.
 */
public class DeviceUtil {

    public static final String TAG = "EverythingDone$DeviceUtil";

    public static boolean hasKitKatApi() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT;
    }
    public static boolean hasLollipopApi() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;
    }

    public static boolean hasMarshmallowApi() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
    }

    public static String getDeviceInfo() {
        return "OS Version:   " + getAndroidVersion() + "\n" +
               "Manufacturer: " + getManufacturer()   + "\n" +
               "Phone Model:  " + getPhoneModel()     + "\n";
    }

    public static String getAndroidVersion() {
        return Build.VERSION.RELEASE + "_" + Build.VERSION.SDK_INT;
    }

    public static String getManufacturer() {
        return Build.MANUFACTURER;
    }

    public static String getPhoneModel() {
        return Build.MODEL;
    }

}
