@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.ywwynm.everythingdone.helpers

import android.app.Activity
import android.content.Context
import android.os.AsyncTask

import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.fragments.AlertDialogFragment
import com.ywwynm.everythingdone.fragments.LoadingDialogFragment
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.utils.FileUtil
import com.ywwynm.everythingdone.utils.LocaleUtil

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Created by ywwynm on 2016/6/28.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * A helper class to export a [Thing] to a txt/zip file
 */
object ThingExporter {

    const val TAG: String = "ThingExporter"

    @JvmStatic
    fun startExporting(activity: Activity?, accentColor: Int, vararg things: Thing?) {
        ExportTask(activity, accentColor).execute(*things)
    }

    private class ExportTask(activity: Activity?, accentColor: Int) : AsyncTask<Thing?, Any?, Int?>() {

        private var mWrActivity: WeakReference<Activity?>? = WeakReference(activity)
        private var mWrLdf: WeakReference<LoadingDialogFragment?>? = null
        private var mAccentColor: Int = accentColor
        private var mParamsLength: Int = 0

        override fun onPreExecute() {
            val activity: Activity = mWrActivity!!.get() ?: return

            val ldf = LoadingDialogFragment()
            ldf.setAccentColor(mAccentColor)
            ldf.setTitle(activity.getString(R.string.export_loading_title))
            ldf.setContent(activity.getString(R.string.export_loading_content))

            mWrLdf = WeakReference(ldf)
            ldf.show(activity.fragmentManager, LoadingDialogFragment.TAG)
        }

        override fun doInBackground(vararg params: Thing?): Int? {
            mParamsLength = params.size
            var successTimes = 0
            for (i in 0 until mParamsLength) {
                if (mWrActivity == null) {
                    return null
                }

                val activity: Activity = mWrActivity!!.get() ?: continue

                if (export(activity, params[i])) {
                    successTimes++
                }
            }
            return successTimes
        }

        override fun onPostExecute(count: Int?) {
            if (count == null) {
                return
            }

            if (mWrActivity == null || mWrLdf == null) {
                return
            }

            val ldf: LoadingDialogFragment = mWrLdf!!.get() ?: return

            ldf.dismiss()

            val activity: Activity = mWrActivity!!.get() ?: return

            val adf = AlertDialogFragment()
            adf.setShowCancel(false)
            adf.setTitleColor(mAccentColor)
            adf.setConfirmColor(mAccentColor)

            if (count >= 1) {
                adf.setTitle(activity.getString(R.string.export_success_title))
                val content1: String = activity.getString(R.string.export_success_content_part_1)
                var content: String = String.format(content1, count)
                if (count > 1 && !LocaleUtil.isChinese(activity)) {
                    content += "s"
                }
                content += activity.getString(R.string.export_success_content_part_2)
                if (count > 1 && !LocaleUtil.isChinese(activity)) {
                    content += "s."
                }
                adf.setContent(content)
            } else {
                adf.setTitle(activity.getString(R.string.export_failed_title))
                adf.setContent(activity.getString(R.string.export_failed_content))
            }

            adf.show(activity.fragmentManager, AlertDialogFragment.TAG)
        }
    }

    /**
     * Export a thing to a file thus user can view on backup it on PC.
     * If the thing only contains text, it will be saved as a txt file.
     * Otherwise, the method will bundle text and attachments(of course, there will be a file size
     * check to prevent suffering from big file) into a zip.
     *
     * @param thing the thing to export
     * @return `true` if export successfully, `false` otherwise.
     */
    @JvmStatic
    fun export(context: Context?, thing: Thing?): Boolean {
        val thingFileName: String = getFileName(context, thing)
        // should be like "hello-world-Note-20160630143306"

        val appFileDir: String = Def.getAppFileDir(context)!!
        val parentPath = "$appFileDir/temp/$thingFileName"
        val txtFile: File? = thingToTxtFile(context, thing, parentPath)
        val attachmentFiles: List<File?>? = AttachmentHelper.getOriginalFiles(thing!!.attachment)

        if (txtFile == null && attachmentFiles.isNullOrEmpty()) {
            // the thing contains neither text content nor attachments
            return false
        }

        if (!attachmentFiles.isNullOrEmpty()) {
            for (attachmentFile in attachmentFiles) {
                val name: String = attachmentFile!!.getName()
                val dstFile: File? = FileUtil.createFile(parentPath, name)
                try {
                    FileUtil.copyFile(attachmentFile, dstFile)
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }

        val dir = File(parentPath)
        val zippedFile: File = FileUtil.createFile(
            "$appFileDir/export", "$thingFileName.zip"
        ) ?: return false

        val zipped: Boolean = FileUtil.zipDirectory(dir, zippedFile)
        if (!zipped) {
            FileUtil.deleteDirectory(dir)
            return false
        }

        FileUtil.deleteDirectory(dir)
        return true
    }

    private fun thingToTxtFile(context: Context?, thing: Thing?, parentPath: String): File? {
        val file: File = FileUtil.createFile(parentPath, getFileName(context, thing) + ".txt") ?: return null

        var fileContent: String = SendInfoHelper.getThingShareInfo(context, thing) ?: return null
        val lastN: Int = fileContent.lastIndexOf('\n')
        if (lastN == -1) {
            return null
        }

        fileContent = fileContent.substring(0, lastN) // without "from everythingdone"
        writeToFile(file, fileContent)
        return file
    }

    private fun getFileName(context: Context?, thing: Thing?): String {
        val sb: StringBuilder = StringBuilder()
        var title: String = thing!!.getTitleToDisplay()!!
        var useContent = true
        if (!title.isEmpty()) {
            title = title.trim()
            if (title.length > 10) {
                title = title.substring(0, 10)
            }
            if (FileUtil.isAppropriateAsFileName(title)) {
                sb.append(title).append("-")
                useContent = false
            }
        }

        var content: String = thing.content!!
        if (useContent && !content.isEmpty()) {
            if (CheckListHelper.isCheckListStr(content)) {
                content = CheckListHelper.toContentStr(content, "", "")
            }

            content = removeReturns(content.trim())

            if (content.length > 10) {
                content = content.substring(0, 10)
                val rIndex: Int = content.indexOf('\n')
                if (rIndex != -1) { // such as a\n\nb
                    content = content.substring(0, rIndex)
                }
            }
            if (FileUtil.isAppropriateAsFileName(content)) {
                sb.append(content).append("-")
            }
        }

        sb.append(Thing.getTypeStr(thing.type, context)).append("-")
        sb.append(SimpleDateFormat("yyyyMMddHHmmssSSS").format(Date()))

        return sb.toString()
    }

    private fun removeReturns(str: String): String {
        val count: Int = str.length
        var start = 0
        val last: Int = count - 1
        var end: Int = last
        while ((start <= end) && (str[start] <= '\n')) {
            start++
        }
        while ((end >= start) && (str[end] <= '\n')) {
            end--
        }
        if (start == 0 && end == last) {
            return str
        }
        return str.substring(start, end - start + 1)
    }

    private fun writeToFile(file: File, str: String) {
        try {
            val fw = FileWriter(file)
            val bw = BufferedWriter(fw)
            bw.write(str)
            bw.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

}
