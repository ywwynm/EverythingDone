package com.ywwynm.everythingdone.thingfile

import android.content.Context
import android.util.Log

import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.database.HabitDAO
import com.ywwynm.everythingdone.database.ReminderDAO
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.model.Habit
import com.ywwynm.everythingdone.model.Reminder
import com.ywwynm.everythingdone.model.Thing

import org.json.JSONArray
import org.json.JSONObject

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 把一条或多条 [Thing] 写成记事文件。格式见 [ThingFile]。
 *
 * 附件全部内嵌，不设体积上限——体积只在界面上报数，不做阻挡也不降质。
 */
object ThingFileWriter {

    private const val TAG: String = "ThingFileWriter"

    private const val BUFFER: Int = 64 * 1024

    sealed class Result {
        class Ok(
            val file: File,
            val thingCount: Int,
            val attachmentCount: Int,
            val bytes: Long
        ) : Result()

        /** [message] 已是给用户看的完整文字。 */
        class Failed(val message: String) : Result()
    }

    /**
     * @param things 要导出的记事，顺序即文件里的顺序。
     * @param keptAttachments 每条记事保留哪些附件，键是 [Thing.id]，值是保留的
     *        typePathName 集合；整体传 null 表示全部保留，某条记事缺键同样表示全部保留。
     * @param dst 目标文件，调用方负责它的父目录存在。
     */
    @JvmStatic
    @JvmOverloads
    fun write(
        context: Context,
        things: List<Thing>,
        dst: File,
        keptAttachments: Map<Long, Set<String>>? = null
    ): Result {
        if (things.isEmpty()) {
            return Result.Failed(context.getString(R.string.thing_file_error_no_things))
        }
        val reminderDao = ReminderDAO.getInstance(context)
        val habitDao = HabitDAO.getInstance(context)

        var attachmentCount = 0
        try {
            FileOutputStream(dst).use { fos ->
                ZipOutputStream(fos).use { zos ->
                    // 附件多为已压缩的媒体，用最快级别；清单是文本，仍有压缩收益。
                    zos.setLevel(Deflater.BEST_SPEED)

                    val thingsJson = JSONArray()
                    for ((index, thing) in things.withIndex()) {
                        val kept = keptAttachments?.get(thing.id)
                        val files = writeAttachments(zos, thing, index, kept)
                        attachmentCount += files.size
                        thingsJson.put(
                            toJson(
                                thing,
                                files,
                                reminderDao?.getReminderById(thing.id),
                                habitDao?.getHabitById(thing.id)
                            )
                        )
                    }

                    val manifest = JSONObject()
                    manifest.put(ThingFile.KEY_FORMAT, ThingFile.FORMAT)
                    manifest.put(ThingFile.KEY_VERSION, ThingFile.VERSION)
                    manifest.put(ThingFile.KEY_APP_VERSION_CODE, appVersionCode(context))
                    manifest.put(ThingFile.KEY_EXPORT_TIME, System.currentTimeMillis())
                    manifest.put(ThingFile.KEY_THINGS, thingsJson)

                    zos.putNextEntry(ZipEntry(ThingFile.ENTRY_MANIFEST))
                    zos.write(manifest.toString().toByteArray(StandardCharsets.UTF_8))
                    zos.closeEntry()
                }
            }
        } catch (e: Exception) {
            // IOException 之外还有 JSONException 等：清单构造不出来就没有可用产物，同样按失败处理
            Log.e(TAG, "写记事文件失败", e)
            dst.delete()
            if (ThingFileMessages.isNoSpace(e)) {
                return Result.Failed(context.getString(R.string.thing_file_error_no_space))
            }
            return Result.Failed(
                ThingFileMessages.of(context, R.string.thing_file_error_write, e.message)
            )
        }
        return Result.Ok(dst, things.size, attachmentCount, dst.length())
    }

    /**
     * 把一条记事保留下来的附件写进 zip，返回它们在 zip 里的条目描述。
     * 原文件已经不在（用户在系统里删掉了）时静默跳过——与列表、详情页的既有行为一致。
     */
    private fun writeAttachments(
        zos: ZipOutputStream,
        thing: Thing,
        index: Int,
        kept: Set<String>?
    ): List<AttachmentEntry> {
        val attachment = thing.attachment
        if (attachment.isNullOrEmpty() || !attachment.contains(AttachmentHelper.SIGNAL)) {
            return emptyList()
        }
        val result = ArrayList<AttachmentEntry>()
        val typePathNames = attachment.split(AttachmentHelper.SIGNAL)
        var n = 0
        for (i in 1 until typePathNames.size) {
            val typePathName = typePathNames[i]
            if (typePathName.isEmpty()) {
                continue
            }
            if (kept != null && !kept.contains(typePathName)) {
                continue
            }
            val type = typePathName[0].digitToIntOrNull() ?: continue
            val file = File(typePathName.substring(1))
            if (!file.exists()) {
                continue
            }
            val entryName = "${ThingFile.ATTACHMENT_DIR}/$index/${n}_${file.name}"
            zos.putNextEntry(ZipEntry(entryName))
            copy(file, zos)
            zos.closeEntry()
            result.add(AttachmentEntry(type, file.name, entryName, typePathName))
            n++
        }
        return result
    }

    private fun toJson(
        thing: Thing,
        attachments: List<AttachmentEntry>,
        reminder: Reminder?,
        habit: Habit?
    ): JSONObject {
        val o = JSONObject()
        o.put(ThingFile.KEY_TYPE, thing.type)
        o.put(ThingFile.KEY_STATE, thing.state)
        // 私密编码在标题前缀（ADR-0011）。直接写 title 会让前缀跟着过去，接收方导入后
        // 那条记事自动就是私密的——「什么都不做」的默认行为不是中立的，必须显式去掉。
        o.put(ThingFile.KEY_TITLE, thing.getTitleToDisplay() ?: "")
        o.put(ThingFile.KEY_CONTENT, thing.content ?: "")
        o.put(ThingFile.KEY_CREATE_TIME, thing.createTime)
        o.put(ThingFile.KEY_UPDATE_TIME, thing.updateTime)
        o.put(ThingFile.KEY_FINISH_TIME, thing.finishTime)
        val background = thing.getBackground()
        if (background != null) {
            o.put(ThingFile.KEY_BACKGROUND, JSONObject(background.toJson()))
        }

        // 两份外观设置（附件显示比例、裁剪、视频帧、卡片封面的选择与位置）的 key 是附件的
        // typePathName，里面含**本机的绝对路径**。原样写进文件的话，接收方导入后附件落在
        // 别的路径上，这些 key 一条都对不上，外观设置会全部丢失。
        // 因此在文件里改用与设备无关的条目名，导入时再换成接收方的新路径。
        val toEntry = HashMap<String, String>(attachments.size)
        for (a in attachments) {
            toEntry[a.typePathName] = a.entry
        }
        o.put(
            ThingFile.KEY_CARD_APPEARANCE,
            JSONObject(thing.thingCardAppearance.remapSources(toEntry).toJson())
        )
        o.put(
            ThingFile.KEY_DETAIL_APPEARANCE,
            JSONObject(thing.detailAttachmentMediaAppearance.remapSources(toEntry).toJson())
        )

        val arr = JSONArray()
        for (a in attachments) {
            val ao = JSONObject()
            ao.put(ThingFile.KEY_ATTACHMENT_TYPE, a.type)
            ao.put(ThingFile.KEY_ATTACHMENT_NAME, a.name)
            ao.put(ThingFile.KEY_ATTACHMENT_ENTRY, a.entry)
            arr.put(ao)
        }
        o.put(ThingFile.KEY_ATTACHMENTS, arr)

        if (reminder != null) {
            val r = JSONObject()
            r.put(ThingFile.KEY_REMINDER_NOTIFY_TIME, reminder.notifyTime)
            r.put(ThingFile.KEY_REMINDER_STATE, reminder.state)
            r.put(ThingFile.KEY_REMINDER_NOTIFY_MILLIS, reminder.notifyMillis)
            o.put(ThingFile.KEY_REMINDER, r)
        }
        if (habit != null) {
            val h = JSONObject()
            h.put(ThingFile.KEY_HABIT_TYPE, habit.type)
            h.put(ThingFile.KEY_HABIT_DETAIL, habit.detail ?: "")
            // 只带周期定义。record / intervalInfo / firstTime / remindedTimes 与全部
            // HabitRecord 都是发送方的履历，不是这条习惯的定义，接收方从零开始。
            o.put(ThingFile.KEY_HABIT, h)
        }
        return o
    }

    private fun copy(src: File, out: OutputStream) {
        FileInputStream(src).use { fis ->
            val buf = ByteArray(BUFFER)
            while (true) {
                val n = fis.read(buf)
                if (n <= 0) {
                    break
                }
                out.write(buf, 0, n)
            }
        }
    }

    private fun appVersionCode(context: Context): Long {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.longVersionCode
        } catch (e: Exception) {
            -1L
        }
    }

    private class AttachmentEntry(
        val type: Int,
        val name: String,
        val entry: String,
        /** 本机的 typePathName，只用来把外观设置的 key 换成 [entry]，不写进文件。 */
        val typePathName: String
    )
}
