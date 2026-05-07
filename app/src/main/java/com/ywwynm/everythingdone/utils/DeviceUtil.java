package com.ywwynm.everythingdone.utils;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Created by ywwynm on 2016/3/11.
 * utils to get device information.
 */
public class DeviceUtil {

    public static final String TAG = "EverythingDone$DeviceUtil";

    private DeviceUtil() {}

    // minSdk is 23 (Marshmallow), these are always true
    public static boolean hasJellyBeanMR1Api() { return true; }
    public static boolean hasKitKatApi() { return true; }
    public static boolean hasLollipopApi() { return true; }
    public static boolean hasMarshmallowApi() { return true; }

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

    public static boolean isScreenOn(Context context) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return pm.isInteractive();
    }

    public static boolean isEMUI() {
        // ro.build.version.emui
        return getProperty("ro.build.version.emui") != null;
    }

    public static boolean isMiuiButNotV5() {
        // ro.miui.ui.version.name
        String prop = getProperty("ro.miui.ui.version.name");
        return prop != null && !prop.equalsIgnoreCase("v5");
    }

    public static boolean isFlyme() {
        return Build.MANUFACTURER.equalsIgnoreCase("meizu");
    }

    private static String getProperty(String key) {
        FileInputStream fis = null;
        try {
            Properties properties = new Properties();
            fis = new FileInputStream(
                    new File(Environment.getRootDirectory(), "build.prop"));
            properties.load(fis);
            return properties.getProperty(key, null);
        } catch (IOException e) {
            return null;
        } finally {
            FileUtil.closeStream(fis);
        }
    }

}
