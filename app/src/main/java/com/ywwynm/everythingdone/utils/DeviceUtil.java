package com.ywwynm.everythingdone.utils;

import android.os.Build;
import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.util.Properties;

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

    public static boolean hasNougatApi() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N;
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

    public static boolean isEMUI() {
        // ro.build.version.emui
        // ro.miui.ui.version.name
        return getProperty("ro.build.version.emui") != null;
    }

    private static String getProperty(String key) {
        try {
            Properties properties = new Properties();
            FileInputStream fis = new FileInputStream(
                    new File(Environment.getRootDirectory(), "build.prop"));
            properties.load(fis);
            String prop = properties.getProperty(key, null);
            fis.close();
            return prop;
        } catch (Exception e) {
            return null;
        }
    }

}
