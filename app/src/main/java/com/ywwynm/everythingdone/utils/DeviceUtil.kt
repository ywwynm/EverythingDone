package com.ywwynm.everythingdone.utils

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.PowerManager

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.Properties

/**
 * Created by ywwynm on 2016/3/11.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * utils to get device information.
 */
object DeviceUtil {

    const val TAG: String = $$"EverythingDone$DeviceUtil"

    @JvmStatic
    fun getDeviceInfo(): String {
        return "OS Version:   " + getAndroidVersion() + "\n" +
               "Manufacturer: " + getManufacturer()   + "\n" +
               "Phone Model:  " + getPhoneModel()     + "\n"
    }

    @JvmStatic
    fun getAndroidVersion(): String {
        return Build.VERSION.RELEASE + "_" + Build.VERSION.SDK_INT
    }

    @JvmStatic
    fun getManufacturer(): String? {
        return Build.MANUFACTURER
    }

    @JvmStatic
    fun getPhoneModel(): String? {
        return Build.MODEL
    }

    @JvmStatic
    fun isScreenOn(context: Context?): Boolean {
        val pm: PowerManager = context!!.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isInteractive()
    }

    @JvmStatic
    fun isEMUI(): Boolean {
        // ro.build.version.emui
        return getProperty("ro.build.version.emui") != null
    }

    @JvmStatic
    fun isMiuiButNotV5(): Boolean {
        // ro.miui.ui.version.name
        val prop: String? = getProperty("ro.miui.ui.version.name")
        return prop != null && !prop.equals("v5", ignoreCase = true)
    }

    @JvmStatic
    fun isFlyme(): Boolean {
        return Build.MANUFACTURER.equals("meizu", ignoreCase = true)
    }

    private fun getProperty(key: String?): String? {
        var fis: FileInputStream? = null
        try {
            val properties = Properties()
            fis = FileInputStream(
                    File(Environment.getRootDirectory(), "build.prop"))
            properties.load(fis)
            return properties.getProperty(key, null)
        } catch (e: IOException) {
            return null
        } finally {
            FileUtil.closeStream(fis)
        }
    }

}
