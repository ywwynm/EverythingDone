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

    public static boolean hasJellyBeanMR1Api() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1;
    }

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

    @SuppressWarnings("deprecation")
    public static boolean isScreenOn(Context context) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            return pm.isInteractive();
        } else {
            return pm.isScreenOn();
        }
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
