package com.ywwynm.everythingdone.helpers

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.utils.DateTimeUtil
import com.ywwynm.everythingdone.utils.FileUtil

import java.time.ZonedDateTime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.ArrayList

/**
 * Created by ywwynm on 2016/3/20.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * helper class used to backup and restore data
 */
object BackupHelper {

    const val TAG: String = "BackupHelper"

    private const val BACKUP_FILE_NAME_OLD: String = "EverythingDone.bak"
    private const val BACKUP_FILE_POSTFIX: String = "bak"

    private const val BACKUP_DIR: String = "/backup"
    private const val BACKUP_FILE_NAME_PREFIX: String = "ED_backup_"

    @JvmStatic
    fun backup(context: Context?, outputUri: Uri?): Boolean {
        val src: File = File(context!!.getApplicationInfo().dataDir)
        val tempDirPath: String = Def.getAppFileDir(context) + BACKUP_DIR
        val curTime: Long = System.currentTimeMillis()
        val dt: ZonedDateTime = Instant.ofEpochMilli(curTime).atZone(ZoneId.systemDefault())
        val timeStr: String = dt.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        val backupFileName: String = BACKUP_FILE_NAME_PREFIX + timeStr + "." + BACKUP_FILE_POSTFIX
        val dst: File = FileUtil.createFile(tempDirPath, backupFileName) ?: return false

        if (!FileUtil.zipDirectory(src, dst, false, *getBackupFilePaths(context))) {
            FileUtil.deleteFile(dst)
            return false
        }

        try {
            copyFileToUri(context, dst, outputUri)
            val sp: SharedPreferences = context.getSharedPreferences(
                    Def.Meta.META_DATA_NAME, Context.MODE_PRIVATE)
            sp.edit().putLong(Def.Meta.KEY_LAST_BACKUP_TIME, curTime).apply()
            return true
        } catch (e: IOException) {
            e.printStackTrace()
            return false
        } finally {
            FileUtil.deleteFile(dst)
        }
    }

    @JvmStatic
    fun getLastBackupTimeString(): String? {
        val context: Context = App.getApp()!!
        val sp: SharedPreferences = context.getSharedPreferences(
                Def.Meta.META_DATA_NAME, Context.MODE_PRIVATE)
        var time: Long = sp.getLong(Def.Meta.KEY_LAST_BACKUP_TIME, -1L)
        if (time == -1L) {
            val backupFile: File = File(
                    Environment.getExternalStorageDirectory(), BACKUP_FILE_NAME_OLD)
            if (backupFile.exists()) {
                time = backupFile.lastModified()
            }
        }

        if (time != -1L) {
            return context.getString(R.string.last_backup) + " " +
                    DateTimeUtil.getDateTimeStrAt(time, context, false)
        } else {
            return context.getString(R.string.no_backup_before)
        }
    }

    @JvmStatic
    fun restore(context: Context?, inputUri: Uri?): Boolean {
        val curTime: Long = System.currentTimeMillis()
        val tempDirPath: String = Def.getAppFileDir(context) + BACKUP_DIR
        val tempFile: File = File(tempDirPath, "restore_" + curTime + "." + BACKUP_FILE_POSTFIX)

        try {
            val parent: File? = tempFile.getParentFile()
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            copyUriToFile(context, inputUri, tempFile)
        } catch (e: IOException) {
            e.printStackTrace()
            FileUtil.deleteFile(tempFile)
            return false
        }

        val unzippedDirPathName: String = tempDirPath + "/" + curTime
        val unzipResult: Boolean = FileUtil.unzip(tempFile.getAbsolutePath(), unzippedDirPathName)
        FileUtil.deleteFile(tempFile)

        if (!unzipResult) return false

        try {
            FileUtil.copyFilesInDirTo(unzippedDirPathName, context!!.getApplicationInfo().dataDir)
            return true
        } catch (e: IOException) {
            e.printStackTrace()
            return false
        } finally {
            FileUtil.deleteFile(unzippedDirPathName)
        }
    }

    @JvmStatic
    fun isSupportedBackupFilePostfix(postfix: String?): Boolean {
        return postfix.equals(BACKUP_FILE_POSTFIX)
    }

    @Throws(IOException::class)
    private fun copyFileToUri(context: Context?, src: File?, dstUri: Uri?) {
        java.io.FileInputStream(src).use { `in` ->
            (context!!.getContentResolver().openOutputStream(dstUri!!)).use { out ->
                if (out == null) throw IOException("Cannot open output stream for " + dstUri)
                val buf: ByteArray = ByteArray(8192)
                var len: Int
                while ((`in`.read(buf).also { len = it }) > 0) {
                    out.write(buf, 0, len)
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun copyUriToFile(context: Context?, srcUri: Uri?, dst: File?) {
        (context!!.getContentResolver().openInputStream(srcUri!!)).use { `in` ->
            FileOutputStream(dst).use { out ->
                if (`in` == null) throw IOException("Cannot open input stream for " + srcUri)
                val buf: ByteArray = ByteArray(8192)
                var len: Int
                while ((`in`.read(buf).also { len = it }) > 0) {
                    out.write(buf, 0, len)
                }
            }
        }
    }

    private fun getBackupFilePaths(context: Context?): Array<String?> {
        val base: String = context!!.getApplicationInfo().dataDir
        val dbDir: String = base + "/databases/"
        val spDir: String = base + "/shared_prefs/"
        val xmlPostFix: String = ".xml"
        val list: ArrayList<String?> = ArrayList()
        list.add(dbDir + Def.Meta.DATABASE_NAME)
        list.add(spDir + Def.Meta.META_DATA_NAME      + xmlPostFix)
        list.add(spDir + Def.Meta.THINGS_COUNTS_NAME  + xmlPostFix)
        list.add(spDir + Def.Meta.PREFERENCES_NAME    + xmlPostFix)
        list.add(spDir + Def.Meta.DOING_STRATEGY_NAME + xmlPostFix)
        return list.toArray(arrayOfNulls<String>(list.size))
    }
}
