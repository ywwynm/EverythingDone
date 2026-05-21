package com.ywwynm.everythingdone.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def

import java.time.ZonedDateTime
import java.time.ZoneId

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException
import java.io.InputStream
import java.io.PrintWriter
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Enumeration
import java.util.Locale
import java.util.Scanner
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Created by ywwynm on 2016/3/20.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * utils for operating [File]s
 */
object FileUtil {

    const val TAG: String = "FileUtil"

    @JvmStatic
    fun getTempPath(context: Context?): String? {
        return Def.getAppFileDir(context) + "/temp"
    }

    @JvmStatic
    fun createTempAudioFile(postfix: String?): File? {
        val dir = File(Def.getAppFileDir(App.getApp()) + "/temp/audio_raw")
        if (!dir.exists()) {
            val parentCreated: Boolean = dir.mkdirs()
            if (!parentCreated) {
                return null
            }
        }

        @SuppressLint("SimpleDateFormat")
        val timeStamp: String = SimpleDateFormat("yyyyMMddHHmmss").format(Date())
        return File(dir, timeStamp + postfix)
    }

    @JvmStatic
    fun copyUriToFile(context: Context?, uri: Uri?, postfix: String?): String? {
        val folderPath: String = Def.getAppFileDir(context) + "/temp"
        val dir = File(folderPath)
        if (!dir.exists() && !dir.mkdirs()) {
            return null
        }
        @SuppressLint("SimpleDateFormat")
        val timeStamp: String = SimpleDateFormat("yyyyMMddHHmmss").format(Date())
        val dst = File(dir, "media_$timeStamp$postfix")
        try {
            context!!.contentResolver.openInputStream(uri!!).use { `in` ->
                FileOutputStream(dst).use { out ->
                    if (`in` == null) return null
                    val buf = ByteArray(8192)
                    var len: Int
                    while ((`in`.read(buf).also { len = it }) > 0) {
                        out.write(buf, 0, len)
                    }
                    return dst.absolutePath
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun copyUriToExistingFile(context: Context?, uri: Uri?, dstPath: String?) {
        val dst = File(dstPath!!)
        context!!.contentResolver.openInputStream(uri!!).use { `in` ->
            FileOutputStream(dst).use { out ->
                if (`in` == null) throw IOException("Cannot open input stream")
                val buf = ByteArray(8192)
                var len: Int
                while ((`in`.read(buf).also { len = it }) > 0) {
                    out.write(buf, 0, len)
                }
            }
        }
    }

    @JvmStatic
    fun getPostfixFromMimeType(context: Context?, uri: Uri?): String? {
        val mimeType: String = context!!.contentResolver.getType(uri!!) ?: return null
        if (mimeType.startsWith("image/")) {
            if (mimeType == "image/jpeg" || mimeType == "image/jpg") return ".jpg"
            if (mimeType == "image/png") return ".png"
            if (mimeType == "image/gif") return ".gif"
            if (mimeType == "image/webp") return ".webp"
            return ".jpg"
        } else if (mimeType.startsWith("video/")) {
            return ".mp4"
        } else if (mimeType.startsWith("audio/")) {
            if (mimeType == "audio/mpeg") return ".mp3"
            if (mimeType == "audio/wav") return ".wav"
            return ".mp3"
        }
        return null
    }

    @JvmStatic
    fun isAppropriateAsFileName(str: String?): Boolean {
        val forbid = "\\/:*?\"<>|"
        val len: Int = forbid.length
        for (i in 0 until len) {
            if (str!!.contains(forbid[i].toString())) {
                return false
            }
        }
        return true
    }

    @JvmStatic
    fun createFile(parentPath: String?, name: String?): File? {
        val parent = File(parentPath!!)
        if (!parent.exists()) {
            val parentCreated: Boolean = parent.mkdirs()
            if (!parentCreated) {
                return null
            }
        }
        return File(parent, name!!)
    }

    @JvmStatic
    fun deleteFile(pathName: String?): Boolean {
        val file = File(pathName!!)
        return deleteFile(file)
    }

    @JvmStatic
    fun deleteFile(file: File?): Boolean {
        return if (file!!.isDirectory()) {
            deleteDirectory(file)
        } else {
            file.delete()
        }
    }

    @JvmStatic
    fun deleteDirectory(pathName: String?): Boolean {
        val dir = File(pathName!!)
        return deleteDirectory(dir)
    }

    @JvmStatic
    fun deleteDirectory(dir: File?): Boolean {
        if (!dir!!.isDirectory()) {
            return false
        }

        val files: Array<File?> = dir.listFiles()!!
        for (file in files) {
            val deleted: Boolean = deleteFile(file)
            if (!deleted) return false
        }
        return dir.delete()
    }

    @JvmStatic
    fun getNameWithoutPostfix(pathName: String?): String? {
        val name: String = File(pathName!!).getName()
        val index: Int = name.lastIndexOf(".")
        return if (index == -1) {
            name
        } else {
            name.substring(0, index)
        }
    }

    @JvmStatic
    fun getPostfix(pathName: String?): String? {
        val index: Int = pathName!!.lastIndexOf(".")
        return if (index == -1) {
            ""
        } else {
            pathName.substring(index + 1).lowercase(Locale.US)
        }
    }

    @JvmStatic
    fun getFileSizeStr(file: File?): String? {
        val B = 1.0
        val KB: Double = 1024 * B
        val MB: Double = 1024 * KB
        val GB: Double = 1024 * MB

        val len: Long = file!!.length()
        val size: Double
        val unit: String
        if (len < B) {
            return "0 byte"
        } else if (len < KB) {
            size = len / B
            unit = " B"
        } else if (len < MB) {
            size = len / KB
            unit = " KB"
        } else if (len < GB) {
            size = len / MB
            unit = " MB"
        } else {
            size = len / GB
            unit = " GB"
        }

        val sizeFormat: String = DecimalFormat("#.00").format(size)
        return sizeFormat + unit
    }

    @JvmStatic
    fun getImageSize(pathName: String?): IntArray? {
        val options: BitmapFactory.Options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        BitmapFactory.decodeFile(pathName, options)
        val ret = IntArray(2)
        ret[0] = options.outWidth
        ret[1] = options.outHeight
        return ret
    }

    @JvmStatic
    fun getVideoSize(pathName: String?): IntArray? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(pathName)
            val widthStr: String = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!
            val heightStr: String = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!
            val ret = IntArray(2)
            ret[0] = widthStr.toInt()
            ret[1] = heightStr.toInt()
            return ret
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            try {
                retriever.release()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    @JvmStatic
    fun getMediaDuration(pathName: String?): Long {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(pathName)
            val durationStr: String = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION)!!
            return durationStr.toLong()
        } catch (e: Exception) {
            e.printStackTrace()
            return -1
        } finally {
            try {
                retriever.release()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    @JvmStatic
    fun getImageCreateTime(pathName: String?): ZonedDateTime? {
        try {
            val exif = ExifInterface(pathName!!)
            val datetimeStr: String = exif.getAttribute(ExifInterface.TAG_DATETIME)!!

            val datetime: Array<String> = datetimeStr.split(" ".toRegex()).toTypedArray()
            val dates: Array<String> = datetime[0].split(":".toRegex()).toTypedArray()
            val year: Int = dates[0].toInt()
            val month: Int = dates[1].toInt()
            val day: Int = dates[2].toInt()

            val times: Array<String> = datetime[1].split(":".toRegex()).toTypedArray()
            val hour: Int = times[0].toInt()
            val minute: Int = times[1].toInt()
            val second: Int = times[2].toInt()

            return ZonedDateTime.of(year, month, day, hour, minute, second, 0, ZoneId.systemDefault())
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    @JvmStatic
    fun getVideoCreateTime(pathName: String?): ZonedDateTime? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(pathName)
            val timeStr: String = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DATE)!!
            // 20160417T112003.000Z
            val year: Int   = timeStr.substring(0, 4).toInt()
            val month: Int  = timeStr.substring(4, 6).toInt()
            val day: Int    = timeStr.substring(6, 8).toInt()
            val hour: Int   = timeStr.substring(9, 11).toInt()
            val minute: Int = timeStr.substring(11, 13).toInt()
            val second: Int = timeStr.substring(13, 15).toInt()
            return ZonedDateTime.of(year, month, day, hour, minute, second, 0, ZoneId.systemDefault())
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            try {
                retriever.release()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    @JvmStatic
    fun getAudioBitrate(pathName: String?): Int {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(pathName)
            val bitrate: Int = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_BITRATE)!!.toInt()
            return bitrate / 1000
        } catch (e: Exception) {
            e.printStackTrace()
            return -1
        } finally {
            try {
                retriever.release()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    @JvmStatic
    fun getAudioSampleRate(pathName: String?): Int {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(pathName!!)
            val mf: MediaFormat = extractor.getTrackFormat(0)
            return mf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } catch (e: Exception) {
            e.printStackTrace()
            return -1
        } finally {
            extractor.release()
        }
    }

    @JvmStatic
    fun closeStream(closeable: Closeable?) {
        if (closeable != null) {
            try {
                closeable.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun copyFile(src: File?, dst: File?) {
        val bis = BufferedInputStream(FileInputStream(src))
        val bos = BufferedOutputStream(FileOutputStream(dst))

        val b = ByteArray(4096)
        var len: Int
        while ((bis.read(b).also { len = it }) != -1) {
            bos.write(b, 0, len)
        }
        bos.flush()

        bis.close()
        bos.close()
    }

    @JvmStatic
    @Throws(IOException::class)
    fun copyFilesInDirTo(sourceDir: String?, targetDir: String?) {
        val files: Array<File?> = File(sourceDir!!).listFiles()!!
        for (file in files) {
            if (file!!.isFile()) {
                val parent = File(targetDir!!)
                if (!parent.exists()) {
                    if (!parent.mkdirs()) {
                        throw IOException("Cannot create directory: " + parent.absolutePath)
                    }
                }
                val targetFile = File(parent.absolutePath, file.getName())
                copyFile(file, targetFile)
            } else {
                val dir1: String = sourceDir + "/" + file.getName()
                val dir2: String = targetDir + "/" + file.getName()
                copyFilesInDirTo(dir1, dir2)
            }
        }
    }

    @JvmStatic
    fun zipDirectory(src: File?, dst: File?, vararg exclude: String?): Boolean {
        return zipDirectory(src, dst, true, *exclude)
    }

    /**
     * 压缩文件夹
     * @param src 需要压缩的文件夹
     * @param dst 压缩后的文件
     * @param exclude `pathNames`是否指的是源文件夹中不需要压缩、添加的文件路径
     * @param pathNames 一些源文件夹中文件的路径
     * @return 压缩结果，成功返回`true`，否则返回`false`
     */
    @JvmStatic
    fun zipDirectory(src: File?, dst: File?, exclude: Boolean, vararg pathNames: String?): Boolean {
        var zout: ZipOutputStream? = null
        try {
            zout = ZipOutputStream(FileOutputStream(dst))
            val files: Array<File?> = src!!.listFiles()!!
            for (file in files) {
                if (file!!.isDirectory() || isInArray(file.absolutePath, *pathNames) != exclude) {
                    // 递归压缩，更新curPaths
                    zipFileOrDirectory(zout, file, "", exclude, *pathNames)
                }
            }
            return true
        } catch (e: IOException) {
            e.printStackTrace()
            return false
        } finally {
            closeStream(zout)
        }
    }

    private fun zipFileOrDirectory(
            zout: ZipOutputStream?, src: File?, curPath: String?, exclude: Boolean, vararg pathNames: String?) {
        var `in`: FileInputStream? = null
        try {
            if (!src!!.isDirectory()) { // zip a file
                val isInArr: Boolean = isInArray(src.absolutePath, *pathNames)
                if (isInArr == exclude) {
                    return
                }
                val buffer = ByteArray(4096)
                var bytes: Int
                `in` = FileInputStream(src)
                //实例代表一个条目内的ZIP归档
                val entry = ZipEntry(curPath + src.getName())
                //条目的信息写入底层流
                zout!!.putNextEntry(entry)
                while ((`in`.read(buffer).also { bytes = it }) != -1) {
                    zout.write(buffer, 0, bytes)
                }
                zout.closeEntry()
            } else { // zip a directory
                val entries: Array<File?> = src.listFiles()!!
                for (entry in entries) {
                    if (entry!!.isDirectory() ||
                            isInArray(entry.absolutePath, *pathNames) != exclude) {
                        // 递归压缩，更新curPaths
                        zipFileOrDirectory(zout, entry, curPath + src.getName() + File.separator,
                                exclude, *pathNames)
                    }
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            closeStream(`in`)
        }
    }

    @JvmStatic
    fun unzip(zipFileName: String?, outputDirectory: String?): Boolean {
        val separator: String = File.separator
        var zipFile: ZipFile? = null
        try {
            zipFile = ZipFile(zipFileName!!)
            val entries: Enumeration<*> = zipFile.entries()
            var zipEntry: ZipEntry
            val dst = File(outputDirectory!!)
            if (!dst.exists()) {
                if (!dst.mkdirs()) {
                    throw IOException("Cannot create directory: " + dst.absolutePath)
                }
            }

            while (entries.hasMoreElements()) {
                zipEntry = entries.nextElement() as ZipEntry
                val entryName: String = zipEntry.name
                var `in`: InputStream? = null
                var out: FileOutputStream? = null
                try {
                    if (zipEntry.isDirectory) {
                        var name: String = zipEntry.name
                        name = name.substring(0, name.length - 1)
                        val f = File(outputDirectory + separator + name)
                        if (!f.exists()) {
                            if (!f.mkdirs()) {
                                throw IOException("Cannot create directory: " + f.absolutePath)
                            }
                        }
                    } else {
                        var index: Int = entryName.lastIndexOf("\\")
                        if (index != -1) {
                            val df = File(outputDirectory + separator
                                    + entryName.substring(0, index))
                            if (!df.exists()) {
                                if (!df.mkdirs()) {
                                    throw IOException("Cannot create directory: " + df.absolutePath)
                                }
                            }
                        }
                        index = entryName.lastIndexOf("/")
                        if (index != -1) {
                            val df = File(outputDirectory + separator
                                    + entryName.substring(0, index))
                            if (!df.exists()) {
                                if (!df.mkdirs()) {
                                    throw IOException("Cannot create directory: " + df.absolutePath)
                                }
                            }
                        }
                        val f = File(outputDirectory + separator + zipEntry.name)
                        `in` = zipFile.getInputStream(zipEntry)
                        out = FileOutputStream(f)
                        var c: Int
                        val bytes = ByteArray(1024)
                        while ((`in`.read(bytes).also { c = it }) != -1) {
                            out.write(bytes, 0, c)
                        }
                        out.flush()
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                    return false
                } finally {
                    closeStream(`in`)
                    closeStream(out)
                }
            }
            return true
        } catch (e: IOException) {
            e.printStackTrace()
            return false
        } finally {
            if (zipFile != null) {
                try {
                    zipFile.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
    }

    /**
     * @return A map of all storage locations available
     * @see http://stackoverflow.com/a/15612964/3952691
     */
    @SuppressLint("SdCardPath")
    @JvmStatic
    fun getAllStorageLocations(): List<String?>? {
        val ret: ArrayList<String?> = ArrayList()

        val mounts: ArrayList<String?> = ArrayList()
        val volds: ArrayList<String?>  = ArrayList()
        mounts.add("/mnt/sdcard")
        volds.add("/mnt/sdcard")

        try {
            val mountFile = File("/proc/mounts")
            if (mountFile.exists()) {
                val scanner = Scanner(mountFile)
                while (scanner.hasNext()) {
                    val line: String = scanner.nextLine()
                    if (line.startsWith("/dev/block/vold/")) {
                        val lineElements: Array<String> = line.split(" ".toRegex()).toTypedArray()
                        val element: String = lineElements[1]

                        // don't add the default mount path
                        // it's already in the list.
                        if (element != "/mnt/sdcard") {
                            mounts.add(element)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            val voldFile = File("/system/etc/vold.fstab")
            if (voldFile.exists()) {
                val scanner = Scanner(voldFile)
                while (scanner.hasNext()) {
                    val line: String = scanner.nextLine()
                    if (line.startsWith("dev_mount")) {
                        val lineElements: Array<String> = line.split(" ".toRegex()).toTypedArray()
                        var element: String = lineElements[2]

                        if (element.contains(":"))
                            element = element.substring(0, element.indexOf(":"))
                        if (element != "/mnt/sdcard")
                            volds.add(element)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val itr: MutableIterator<String?> = mounts.iterator()
        while (itr.hasNext()) {
            val mount: String? = itr.next()
            if (!volds.contains(mount)) {
                itr.remove()
            }
        }
        volds.clear()

        val mountHash: ArrayList<String?> = ArrayList(10)

        for (mount in mounts) {
            val root = File(mount!!)
            if (root.exists() && root.isDirectory() && root.canWrite()) {
                val list: Array<File?>? = root.listFiles()
                val sb: StringBuilder = StringBuilder("[")
                if (list != null) {
                    for (f in list) {
                        sb.append(f!!.getName().hashCode()).append(":").append(f.length()).append(", ")
                    }
                }
                sb.append("]")
                val hash: String = sb.toString()
                if (!mountHash.contains(hash)) {
                    mountHash.add(hash)
                    ret.add(mount)
                }
            }
        }

        mounts.clear()

        ret.add(Environment.getExternalStorageDirectory().absolutePath)
        ret.add("/sdcard2/")
        ret.add("/sdcard3/")
        ret.add("/sdcard4/")
        ret.add("/sdcard5/")
        ret.add("/sdcard6/")
        ret.add("/storage/")

        return ret
    }

    @JvmStatic
    fun writeStringToFile(str: String?, file: File?): Boolean {
        if (file == null) return false
        var writer: PrintWriter? = null
        try {
            writer = PrintWriter(FileWriter(file))
            writer.write(str!!)
            return true
        } catch (e: IOException) {
            e.printStackTrace()
            return false
        } finally {
            writer?.close()
        }
    }

    @JvmStatic
    fun readStringFromFile(file: File?): String? {
        if (file == null) return null
        var reader: BufferedReader? = null
        try {
            reader = BufferedReader(FileReader(file))
            val sb: StringBuilder = StringBuilder()
            var line: String?
            while ((reader.readLine().also { line = it }) != null) sb.append(line)
            return sb.toString()
        } catch (e: IOException) {
            e.printStackTrace()
            return null
        } finally {
            if (reader != null) try { // I found this style is really sexy, can you find another similar one(maybe or two) in my code?
                reader.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    private fun isInArray(str: String?, vararg arr: String?): Boolean {
        for (s in arr) {
            if (s!! == str) return true
        }
        return false
    }
}
