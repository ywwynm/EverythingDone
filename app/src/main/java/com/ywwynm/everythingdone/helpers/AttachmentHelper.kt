@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.helpers

import androidx.fragment.app.FragmentActivity
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.IOException
import androidx.core.util.Pair
import androidx.recyclerview.widget.RecyclerView
import android.widget.LinearLayout

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.fragments.AttachmentInfoDialogFragment
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.DateTimeUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.FileUtil
import com.ywwynm.everythingdone.utils.LocaleUtil

import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date

/**
 * Created by ywwynm on 2015/9/23.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Utils for attachment
 */
object AttachmentHelper {

    const val TAG: String = "AttachmentHelper"

    @JvmField
    val SIGNAL: String = App.getApp()!!.getString(R.string.base_signal)
    const val SIZE_SEPARATOR: String = "`"

    const val IMAGE: Int  = 0
    const val VIDEO: Int  = 1
    const val AUDIO: Int  = 2

    @JvmStatic
    fun isValidForm(attachment: String?): Boolean {
        return !attachment!!.isEmpty() && attachment != "to QQ"
    }

    @JvmStatic
    fun getOriginalFiles(attachmentStr: String?): List<File?>? {
        if (attachmentStr == null || !attachmentStr.contains(SIGNAL)) {
            return null
        }
        val files: MutableList<File?> = ArrayList()
        val typePathNames: Array<String> = attachmentStr.split(SIGNAL.toRegex()).toTypedArray()
        for (i in 1 until typePathNames.size) {
            val pathName: String = typePathNames[i].substring(1, typePathNames[i].length)
            val file = File(pathName)
            if (file.exists()) {
                files.add(file)
            }
        }
        return files
    }

    @JvmStatic
    fun toAttachmentItems(attachmentStr: String?): Pair<List<String?>, List<String?>> {
        val imageItems: MutableList<String?> = ArrayList()
        val audioItems: MutableList<String?> = ArrayList()

        val typePathNames: Array<String> = attachmentStr!!.split(SIGNAL.toRegex()).toTypedArray()
        for (i in 1 until typePathNames.size) {
            val pathName: String = typePathNames[i].substring(1, typePathNames[i].length)
            val file = File(pathName)

            // if user delete an attachment directly through deleting original file, we should
            // find it out.
            if (file.exists()) {
                if (!typePathNames[i].startsWith(AUDIO.toString())) {
                    imageItems.add(typePathNames[i])
                } else {
                    audioItems.add(typePathNames[i])
                }
            }
        }

        return Pair(imageItems, audioItems)
    }

    @JvmStatic
    fun toAttachmentStr(imageItems: List<String?>?, audioItems: List<String?>?): String {
        val sb: StringBuilder = StringBuilder()
        if (imageItems != null) {
            for (typePathName in imageItems) {
                sb.append(SIGNAL).append(typePathName)
            }
        }
        if (audioItems != null) {
            for (typePathName in audioItems) {
                sb.append(SIGNAL).append(typePathName)
            }
        }
        return sb.toString()
    }

    @JvmStatic
    fun getFirstImageTypePathName(attachment: String?): String? {
        if (!isValidForm(attachment)) {
            return null
        }
        val typePathNames: Array<String> = attachment!!.split(SIGNAL.toRegex()).toTypedArray()
        for (i in 1 until typePathNames.size) {
            if (!typePathNames[i].startsWith(AUDIO.toString())) {
                val pathName: String = typePathNames[i].substring(1, typePathNames[i].length)
                if (File(pathName).exists()) {
                    return typePathNames[i]
                }
            }
        }
        return null
    }

    @JvmStatic
    fun getImageAttachmentCountStr(attachment: String?, context: Context?): String? {
        if (!isValidForm(attachment)) {
            return null
        }

        val typePathNames: Array<String> = attachment!!.split(SIGNAL.toRegex()).toTypedArray()
        var imageCount = 0
        var videoCount = 0
        for (i in 1 until typePathNames.size) {
            val file = File(typePathNames[i].substring(1, typePathNames[i].length))
            if (file.exists()) {
                if (typePathNames[i].startsWith(IMAGE.toString())) {
                    imageCount++
                } else if (typePathNames[i].startsWith(VIDEO.toString())) {
                    videoCount++
                }
            }
        }

        if (imageCount == 0 && videoCount == 0) {
            return null
        } else {
            var images: String = context!!.getString(R.string.images)
            var videos: String = context.getString(R.string.videos)
            if (!LocaleUtil.isChinese(context)) {
                if (imageCount > 1) {
                    images += "s"
                }
                if (videoCount > 1) {
                    videos += "s"
                }
            }

            return if (imageCount != 0 && videoCount == 0) {
                "$imageCount $images"
            } else if (imageCount == 0) {
                "$videoCount $videos"
            } else {
                "$imageCount $images, $videoCount $videos"
            }
        }
    }

    @JvmStatic
    fun getAudioAttachmentCountStr(attachment: String?, context: Context?): String? {
        if (!isValidForm(attachment)) {
            return null
        }
        val typePathNames: Array<String> = attachment!!.split(SIGNAL.toRegex()).toTypedArray()
        var count = 0
        for (i in 1 until typePathNames.size) {
            val file = File(typePathNames[i].substring(1, typePathNames[i].length))
            if (file.exists() && typePathNames[i].startsWith(AUDIO.toString())) {
                count++
            }
        }

        if (count == 0) {
            return null
        } else {
            var audios: String = context!!.getString(R.string.audios)
            if (!LocaleUtil.isChinese(context) && count > 1) {
                audios += "s"
            }
            return "$count $audios"
        }
    }

    @JvmStatic
    fun createAttachmentFile(type: Int): File? {
        val folderName: String
        val fileType: String
        when (type) {
            IMAGE -> {
                folderName = "images"
                fileType = ".jpg"
            }
            VIDEO -> {
                folderName = "videos"
                fileType = ".mp4"
            }
            else -> {
                folderName = "audios"
                fileType = ".wav"
            }
        }

        val fileName: String = SimpleDateFormat("yyyyMMddHHmmss").format(Date()) + fileType
        return FileUtil.createFile(Def.getAppFileDir(App.getApp()) + "/" + folderName, fileName)
    }

    @JvmStatic
    fun calculateImageSize(context: Context?, itemSize: Int): IntArray {
        val size = IntArray(2)
        val res: Resources = context!!.resources
        val isTablet: Boolean = DisplayUtil.isTablet(context)
        val orientation: Int = res.configuration.orientation
        val displayWidth: Int = res.displayMetrics.widthPixels

        if (isTablet) {
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                if (itemSize < 5) {
                    size[0] = displayWidth / itemSize
                    size[1] = displayWidth / 3
                } else {
                    size[0] = displayWidth / 5
                    size[1] = size[0]
                }
            } else {
                when (itemSize) {
                    1 -> {
                        size[0] = displayWidth
                        size[1] = displayWidth * 3 / 4
                    }
                    2 -> {
                        size[0] = displayWidth / 2
                        size[1] = displayWidth * 3 / 4
                    }
                    else -> {
                        size[0] = displayWidth / 3
                        size[1] = size[0]
                    }
                }
            }
        } else {
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                if (itemSize < 4) {
                    size[0] = displayWidth / itemSize
                    size[1] = displayWidth / 3
                } else {
                    size[0] = displayWidth / 4
                    size[1] = size[0]
                }
            } else {
                if (itemSize == 1) {
                    size[0] = displayWidth
                    size[1] = size[0] * 3 / 4
                } else {
                    size[0] = displayWidth / 2
                    size[1] = size[0]
                }
            }
        }
        return size
    }

    @JvmStatic
    fun getImageFromVideo(pathName: String?): Bitmap? {
        var bitmap: Bitmap? = null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(pathName)
            bitmap = retriever.frameAtTime
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                retriever.release()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        return bitmap
    }

    @JvmStatic
    fun getVideoDurationMs(pathName: String?): Long {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(pathName)
            return retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                retriever.release()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        return 0L
    }

    @JvmStatic
    fun setImageRecyclerViewHeight(recyclerView: RecyclerView?, itemSize: Int, maxSpan: Int) {
        val height: Int
        val itemHeight: Int = calculateImageSize(recyclerView!!.context, itemSize)[1]
        if (itemSize <= maxSpan) {
            height = itemHeight
        } else {
            var h: Int = itemHeight * (itemSize / maxSpan)
            if (itemSize % maxSpan != 0) {
                h += itemHeight
            }
            height = h
        }

        val params: LinearLayout.LayoutParams = recyclerView.layoutParams as LinearLayout.LayoutParams
        params.height = height
        recyclerView.requestLayout()
    }

    @JvmStatic
    fun setAudioRecyclerViewHeight(recyclerView: RecyclerView?, itemSize: Int, span: Int) {
        val density: Float = recyclerView!!.context.resources.displayMetrics.density

        var rows: Int = itemSize / span
        if (itemSize % span != 0) {
            rows++
        }
        val itemHeight: Int = (density * 56).toInt() + (density * 8).toInt()

        val params: LinearLayout.LayoutParams = recyclerView.layoutParams as LinearLayout.LayoutParams
        params.height = itemHeight * rows
        recyclerView.requestLayout()
    }

    @JvmStatic
    fun showAttachmentInfoDialog(activity: FragmentActivity?, accentColor: Int, typePathName: String?) {
        showAttachmentInfoDialog(activity, ThingBackground.pure(accentColor), typePathName)
    }

    /** Phase 8: gradient-aware open. Title + confirm in the dialog render
     *  shader-painted gradient when `accentBg` is GRADIENT. The legacy
     *  int overload wraps the colour into a PURE background and delegates here. */
    @JvmStatic
    fun showAttachmentInfoDialog(activity: FragmentActivity?, accentBg: ThingBackground?, typePathName: String?) {
        val aidf = AttachmentInfoDialogFragment()
        if (accentBg != null) {
            aidf.setAccentBackground(accentBg)
        }
        aidf.setItems(getAttachmentInfo(activity, typePathName))
        aidf.show(activity!!.supportFragmentManager, AttachmentInfoDialogFragment.TAG)
    }

    private fun getAttachmentInfo(context: Context?, typePathName: String?): List<Pair<String, String>?>? {
        val type: Char = typePathName!![0]
        val pathName: String = typePathName.substring(1, typePathName.length)

        val list: MutableList<Pair<String, String>?> = ArrayList()
        val file = File(pathName)
        var fst: String = context!!.getString(R.string.file_path)
        if (!file.exists()) {
            val sec: String = context.getString(R.string.file_path_not_existed)
            list.add(Pair(fst, sec))
            return list
        }

        var sec: String = file.absolutePath
        list.add(Pair(fst, sec))

        fst = context.getString(R.string.file_size)
        sec = FileUtil.getFileSizeStr(file)
        list.add(Pair(fst, sec))

        return when (type) {
            '0' -> getAttachmentInfoImage(list, context, pathName)
            '1' -> getAttachmentInfoVideo(list, context, pathName)
            else -> getAttachmentInfoAudio(list, context, pathName)
        }
    }

    private fun getAttachmentInfoImage(
            list: MutableList<Pair<String, String>?>, context: Context, pathName: String): List<Pair<String, String>?> {
        var fst: String = context.getString(R.string.image_size)
        val size: IntArray = FileUtil.getImageSize(pathName)
        var sec: String = size[0].toString() + " * " + size[1]
        list.add(Pair(fst, sec))

        val dateTime: ZonedDateTime? = FileUtil.getImageCreateTime(pathName)
        if (dateTime == null) {
            fst = context.getString(R.string.file_last_modify_time)
            val file = File(pathName)
            sec = DateTimeUtil.getGeneralDateTimeStr(context, file.lastModified())!!
        } else {
            fst = context.getString(R.string.image_create_time)
            sec = dateTime.format(DateTimeFormatter.ofPattern(DateTimeUtil.getGeneralDateTimeFormatPattern(context)))
        }
        list.add(Pair(fst, sec))

        return list
    }

    private fun getAttachmentInfoVideo(
            list: MutableList<Pair<String, String>?>, context: Context, pathName: String): List<Pair<String, String>?>? {
        val size: IntArray = FileUtil.getVideoSize(pathName) ?: return null

        var fst: String = context.getString(R.string.video_size)
        var sec: String = size[0].toString() + " * " + size[1]
        list.add(Pair(fst, sec))

        fst = context.getString(R.string.video_duration)
        val duration: Long = FileUtil.getMediaDuration(pathName)
        sec = DateTimeUtil.getDurationBriefStr(duration)!!
        list.add(Pair(fst, sec))

        fst = context.getString(R.string.video_create_time)
        val dateTime: ZonedDateTime? = FileUtil.getVideoCreateTime(pathName)
        if (dateTime == null || dateTime < ZonedDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneId.systemDefault())) {
            fst = context.getString(R.string.file_last_modify_time)
            val file = File(pathName)
            sec = DateTimeUtil.getGeneralDateTimeStr(context, file.lastModified())!!
        } else {
            sec = dateTime.format(DateTimeFormatter.ofPattern(DateTimeUtil.getGeneralDateTimeFormatPattern(context)))
        }
        list.add(Pair(fst, sec))

        return list
    }

    private fun getAttachmentInfoAudio(
            list: MutableList<Pair<String, String>?>, context: Context, pathName: String): List<Pair<String, String>?> {
        var fst: String = context.getString(R.string.file_last_modify_time)
        val file = File(pathName)
        var sec: String = DateTimeUtil.getGeneralDateTimeStr(context, file.lastModified())!!
        list.add(Pair(fst, sec))

        fst = context.getString(R.string.audio_duration)
        val duration: Long = FileUtil.getMediaDuration(pathName)
        sec = DateTimeUtil.getDurationBriefStr(duration)!!
        list.add(Pair(fst, sec))

        fst = context.getString(R.string.audio_bitrate)
        val bitrate: Int = FileUtil.getAudioBitrate(pathName)
        sec = "$bitrate Kbps"
        list.add(Pair(fst, sec))

        fst = context.getString(R.string.audio_sample_rate)
        val sampleRate: Int = FileUtil.getAudioSampleRate(pathName)
        sec = "$sampleRate Hz"
        list.add(Pair(fst, sec))

        return list
    }

    @JvmStatic
    fun getAttachmentsToDelete(attachmentBefore: String?, attachmentAfter: String?): List<String?>? {
        if (attachmentBefore.equals(attachmentAfter)) {
            return null
        }
        val attachmentsToDelete: MutableList<String?> = ArrayList()
        val appDir: String = Def.getAppFileDir(App.getApp())!!
        val oldAppDir: String = Environment.getExternalStorageDirectory().absolutePath + "/EverythingDone"
        var pathName: String
        val attachmentsBefore: Array<String> = attachmentBefore!!.split(SIGNAL.toRegex()).toTypedArray()
        for (i in 1 until attachmentsBefore.size) {
            pathName = attachmentsBefore[i].substring(1, attachmentsBefore[i].length)
            if ((pathName.startsWith(appDir) || pathName.startsWith(oldAppDir))
                    && !attachmentAfter!!.contains(attachmentsBefore[i])) {
                attachmentsToDelete.add(pathName)
            }
        }
        return attachmentsToDelete
    }

    @JvmStatic
    fun isImageFile(postfix: String?): Boolean {
        val postfixes: Array<String?> = arrayOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "heic", "heif")
        return isInsideArray(postfixes, postfix)
    }

    /**
     * 是否可能是 Motion Photo(动态照片)的静态图容器,按扩展名粗判。只有 JPEG / HEIC 才可能内嵌视频;
     * 用作 [MotionPhotoDetector] 的前置过滤,避免对 png/gif/webp/bmp 做无谓的内容扫描。见 ADR-0014。
     */
    @JvmStatic
    fun isMotionPhotoCandidate(pathName: String?): Boolean {
        if (pathName.isNullOrEmpty()) return false
        val dot = pathName.lastIndexOf('.')
        if (dot < 0 || dot == pathName.length - 1) return false
        val postfix = pathName.substring(dot + 1).lowercase()
        return postfix == "jpg" || postfix == "jpeg" || postfix == "heic" || postfix == "heif"
    }

    @JvmStatic
    fun isAnimatedImageType(postfix: String?): Boolean {
        val postfixes: Array<String?> = arrayOf("gif", "webp")
        return isInsideArray(postfixes, postfix)
    }

    /**
     * 是否按 Animated Image(GIF / 动态 WebP)走 Glide Drawable 播放分支。仅按扩展名
     * 粗判:静态 WebP 走 Drawable 也能正确显示、不会出错,也不带 HDR gain map。见 ADR-0007。
     */
    @JvmStatic
    fun isAnimatedImageCandidate(pathName: String?): Boolean {
        if (pathName.isNullOrEmpty()) return false
        val dot = pathName.lastIndexOf('.')
        if (dot < 0 || dot == pathName.length - 1) return false
        return isAnimatedImageType(pathName.substring(dot + 1).lowercase())
    }

    @JvmStatic
    fun isVideoFile(postfix: String?): Boolean {
        val postfixes: Array<String?> = arrayOf("3gp", "mp4", "webm", "mkv")
        return isInsideArray(postfixes, postfix)
    }

    /**
     * 是否按视频文件处理(按扩展名小写粗判)。与 [isAnimatedImageCandidate] 对称:用于只拿到
     * pathName、且视频未选帧时 videoFrameMs 为 null、无法据此识别视频的场合。见 ADR-0012。
     */
    @JvmStatic
    fun isVideoCandidate(pathName: String?): Boolean {
        if (pathName.isNullOrEmpty()) return false
        val dot = pathName.lastIndexOf('.')
        if (dot < 0 || dot == pathName.length - 1) return false
        return isVideoFile(pathName.substring(dot + 1).lowercase())
    }

    @JvmStatic
    fun isAudioFile(postfix: String?): Boolean {
        val postfixes: Array<String?> = arrayOf("wav", "mp3", "3gp", "mp4", "aac", "flac", "mid", "xmf",
                "mxmf", "rtttl", "rtx", "ota", "imy", "ogg", "mkv")
        return isInsideArray(postfixes, postfix)
    }

    @JvmStatic
    fun toUriList(attachment: String?): ArrayList<Uri?>? {
        if (attachment.isNullOrEmpty()) {
            return null
        }
        val typePathNames: Array<String> = attachment.split(SIGNAL.toRegex()).toTypedArray()
        val ret: ArrayList<Uri?> = ArrayList()
        for (typePathName in typePathNames) {
            if (typePathName.isEmpty()) continue
            val pathName: String = typePathName.substring(1, typePathName.length)
            val uri: Uri = FileProvider.getUriForFile(App.getApp()!!,
                    "com.ywwynm.everythingdone", File(pathName))
            ret.add(uri)
        }
        return ret
    }

    @JvmStatic
    fun isAllImage(attachment: String?): Boolean {
        if (attachment.isNullOrEmpty()) {
            return false
        }
        return !attachment.contains(SIGNAL + VIDEO) && !attachment.contains(SIGNAL + AUDIO)
    }

    @JvmStatic
    fun isAllAudio(attachment: String?): Boolean {
        if (attachment.isNullOrEmpty()) {
            return false
        }
        return !attachment.contains(SIGNAL + IMAGE) && !attachment.contains(SIGNAL + VIDEO)
    }

    private fun isInsideArray(array: Array<String?>, value: String?): Boolean {
        for (s in array) {
            if (value.equals(s)) {
                return true
            }
        }
        return false
    }
}
