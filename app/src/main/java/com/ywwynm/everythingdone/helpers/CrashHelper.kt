package com.ywwynm.everythingdone.helpers

import android.app.Application
import android.content.Context
import android.os.Process

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.utils.DeviceUtil
import com.ywwynm.everythingdone.utils.FileUtil

import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.IOException
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Created by ywwynm on 2016/4/29.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * helper for crash that caused by uncaught exceptions.
 */
open class CrashHelper private constructor() : Thread.UncaughtExceptionHandler {

    private var mApplication: Application? = null
    private var mDefaultHandler: Thread.UncaughtExceptionHandler? = null

    open fun init(application: Application?) {
        mDefaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
        mApplication = application
    }

    override fun uncaughtException(thread: Thread, ex: Throwable) {
        saveCrashInfoToStorage(ex)
        createFileToShowFeedbackDialogNextLaunch()

        ex.printStackTrace()

        if (mDefaultHandler != null) {
            mDefaultHandler!!.uncaughtException(thread, ex)
        } else {
            Process.killProcess(Process.myPid())
        }
    }

    private fun saveCrashInfoToStorage(ex: Throwable) {
        val path: String = Def.getAppFileDir(mApplication) + "/log"
        val time: String = SimpleDateFormat("yyyyMMddHHmmss").format(Date())
        val name = "crash_$time.log"
        val file: File = FileUtil.createFile(path, name) ?: return

        try {
            val writer = PrintWriter(FileWriter(file))
            writer.println(time)
            writer.print("APP Version:  ")
            writer.println(BuildConfig.VERSION_NAME + "_" + BuildConfig.VERSION_CODE)
            writer.println(DeviceUtil.getDeviceInfo())
            writer.println()
            ex.printStackTrace(writer)
            writer.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun createFileToShowFeedbackDialogNextLaunch() {
        try {
            val fos: FileOutputStream = mApplication!!.openFileOutput(
                    Def.Meta.FEEDBACK_ERROR_FILE_NAME, Context.MODE_PRIVATE)
            fos.write(App.getApp()!!.getString(R.string.qq_my_love).toByteArray())
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    companion object {
        const val TAG: String = "CrashHelper"

        @JvmField
        var sCrashHelper: CrashHelper? = null

        @JvmStatic
        fun getInstance(): CrashHelper? {
            if (sCrashHelper == null) {
                synchronized(CrashHelper::class.java) {
                    if (sCrashHelper == null) {
                        sCrashHelper = CrashHelper()
                    }
                }
            }
            return sCrashHelper
        }
    }
}
