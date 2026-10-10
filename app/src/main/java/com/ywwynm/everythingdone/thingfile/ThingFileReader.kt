package com.ywwynm.everythingdone.thingfile

import android.content.Context
import android.util.Log

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.model.DetailAttachmentMediaAppearance
import com.ywwynm.everythingdone.model.Habit
import com.ywwynm.everythingdone.model.Reminder
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.model.ThingCardAppearance
import com.ywwynm.everythingdone.model.ThingFolderCardPresentation

import org.json.JSONObject

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

/**
 * 读取记事文件，得到一组尚未写进数据库的 [PendingThing]（版本 2 起还有 [PendingFolder]）。格式见 [ThingFile]。
 *
 * 附件先解压到 [Def.getAppFileDir] 下的临时目录，真正导入（用户在预览列表里确认）时才挪进
 * 附件目录。调用方无论成功与否都应在用完后调用 [cleanUp]。
 */
object ThingFileReader {

    private const val TAG: String = "ThingFileReader"

    private const val IMPORT_DIR: String = "/thing_file_import"

    private const val BUFFER: Int = 64 * 1024

    /**
     * 解压时给系统留的空间。附件不设体积上限（见 decisions.md），能不能放下只由剩余空间决定：
     * 低于这个值就停下并明确报「存储空间不足」，而不是写满存储或把附件静默丢掉。
     */
    private const val FREE_SPACE_RESERVE_BYTES: Long = 64L * 1024 * 1024

    /** 每写这么多字节查一次剩余空间。 */
    private const val FREE_SPACE_CHECK_INTERVAL: Long = 4L * 1024 * 1024

    /**
     * 清单读进内存的上限。正常的清单是纯文本，几 KB 到几 MB；畸形文件里一个上 GB 的
     * `things.json` 会让读取直接 OOM。
     */
    private const val MAX_MANIFEST_BYTES: Int = 32 * 1024 * 1024

    /** 超过这么久的导入临时目录视为残留：上一次导入中途进程被杀，目录没来得及清。 */
    private const val STALE_TEMP_DIR_MS: Long = 24L * 60 * 60 * 1000

    sealed class Result {
        /**
         * [folders] 按先父后子排（上一级总在前面）；只含记事的文件为空。每条记事的 [PendingThing.folderKey]
         * 都指向 [folders] 里的一个，或为 null（顶层）。
         */
        class Ok(
            val things: List<PendingThing>,
            val tempDir: File,
            val folders: List<PendingFolder> = emptyList(),
            /** 更新当前记事时按原始清单判断，不能把过滤后的单条误当作独立单条分享。 */
            val singleStandaloneThing: Boolean = false
        ) : Result()

        /** 文件是本格式，但版本比本机新。 */
        class TooNew(val version: Int) : Result()

        /** 不是本格式的文件——可能只是一个普通 zip。 */
        object NotThingFile : Result()

        /**
         * 读不到这个文件：它在别的应用的目录里，分区存储不让我们直接读。
         *
         * 互传类应用把文件落在自己的目录下（如 `Download/MzShare/`，属它自己的 uid），
         * 文件管理器又以 `file://` 交过来时就会这样。`READ_EXTERNAL_STORAGE` 在
         * Android 13+ 已被拆成 READ_MEDIA_*，而记事文件不是媒体，一条都不覆盖。
         * 唯一能走通的是让用户在系统文件选择器里选中它，SAF 给的 content URI 有权限。
         */
        object NoAccess : Result()

        /** [message] 已是给用户看的完整文字。 */
        class Broken(val message: String) : Result()
    }

    private class NoSpaceException : IOException("no space left for extracting")

    private class ManifestTooLargeException : IOException("manifest too large")

    /** 清单里声明的附件在压缩包里找不到，或类型不认识：文件不完整。 */
    private class BrokenAttachmentException(val displayName: String) : Exception(displayName)

    @JvmStatic
    fun read(context: Context, input: InputStream): Result {
        val tempDir = File(Def.getAppFileDir(context) + IMPORT_DIR, System.currentTimeMillis().toString())
        if (!tempDir.mkdirs() && !tempDir.isDirectory) {
            return Result.Broken(context.getString(R.string.thing_file_error_temp_dir))
        }
        var manifestJson: String? = null
        val extracted = HashMap<String, File>()
        try {
            ZipInputStream(input).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    if (entry.isDirectory) {
                        zis.closeEntry()
                        continue
                    }
                    val name = entry.name
                    if (name == ThingFile.ENTRY_MANIFEST) {
                        manifestJson = readManifest(zis)
                    } else if (isSafeAttachmentEntry(name)) {
                        val dst = File(tempDir, name.replace('/', '_'))
                        extractTo(zis, dst, tempDir)
                        extracted[name] = dst
                    }
                    zis.closeEntry()
                }
            }
        } catch (e: ManifestTooLargeException) {
            cleanUp(tempDir)
            return Result.Broken(context.getString(R.string.thing_file_error_manifest))
        } catch (e: IOException) {
            Log.e(TAG, "解压记事文件失败", e)
            cleanUp(tempDir)
            if (e is NoSpaceException || ThingFileMessages.isNoSpace(e)) {
                return Result.Broken(context.getString(R.string.thing_file_error_no_space))
            }
            return Result.Broken(
                ThingFileMessages.of(context, R.string.thing_file_error_unzip, e.message)
            )
        }

        val json = manifestJson
        if (json == null) {
            cleanUp(tempDir)
            return Result.NotThingFile
        }
        return try {
            parse(context, json, extracted, tempDir)
        } catch (e: Exception) {
            Log.e(TAG, "解析记事文件清单失败", e)
            cleanUp(tempDir)
            Result.Broken(ThingFileMessages.of(context, R.string.thing_file_error_manifest, e.message))
        }
    }

    private fun parse(
        context: Context,
        json: String,
        extracted: Map<String, File>,
        tempDir: File
    ): Result {
        val manifest = JSONObject(json)
        if (manifest.optString(ThingFile.KEY_FORMAT) != ThingFile.FORMAT) {
            cleanUp(tempDir)
            return Result.NotThingFile
        }
        val version = manifest.optInt(ThingFile.KEY_VERSION, 0)
        if (version > ThingFile.VERSION) {
            cleanUp(tempDir)
            return Result.TooNew(version)
        }
        val arr = manifest.optJSONArray(ThingFile.KEY_THINGS)
        if (arr == null || arr.length() == 0) {
            cleanUp(tempDir)
            return Result.Broken(context.getString(R.string.thing_file_error_no_things))
        }
        val folders = parseFolders(manifest)
        val folderKeys = folders.mapTo(HashSet()) { it.key }
        val things = ArrayList<PendingThing>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            // 单条坏掉不影响其他条：逐条独立解析，解析不出来就跳过。
            // 附件缺失例外——那说明整个文件不完整，报出来，不能导入一条缺附件的记事却报成功。
            try {
                toPendingThing(o, extracted, folderKeys)?.let { things.add(it) }
            } catch (e: BrokenAttachmentException) {
                cleanUp(tempDir)
                return Result.Broken(
                    context.getString(R.string.thing_file_error_attachment, e.displayName)
                )
            } catch (e: Exception) {
                Log.w(TAG, "第 $i 条记事解析失败，已跳过", e)
            }
        }
        if (things.isEmpty()) {
            cleanUp(tempDir)
            return Result.Broken(context.getString(R.string.thing_file_error_no_valid_things))
        }
        val singleStandalone = arr.length() == 1 && things.size == 1 &&
            (manifest.optJSONArray(ThingFile.KEY_FOLDERS)?.length() ?: 0) == 0 &&
            arr.getJSONObject(0).isNull(ThingFile.KEY_IN_FOLDER)
        return Result.Ok(things, tempDir, dropEmptyFolders(folders, things), singleStandalone)
    }

    /**
     * 版本 2 的文件夹。上一级在它之前没有出现过（畸形文件：指向不存在的、或指向自己之后的文件夹）时
     * 当作顶层：不丢内容，也不可能成环。编号重复的只认第一个。
     */
    private fun parseFolders(manifest: JSONObject): List<PendingFolder> {
        val arr = manifest.optJSONArray(ThingFile.KEY_FOLDERS) ?: return emptyList()
        val result = ArrayList<PendingFolder>(arr.length())
        val seen = HashSet<Int>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (!o.has(ThingFile.KEY_FOLDER_KEY)) continue
            val key = o.optInt(ThingFile.KEY_FOLDER_KEY)
            if (!seen.add(key)) continue
            val parent = if (o.has(ThingFile.KEY_IN_FOLDER)) o.optInt(ThingFile.KEY_IN_FOLDER) else null
            val bg = o.optJSONObject(ThingFile.KEY_BACKGROUND)?.let { ThingBackground.fromJson(it.toString()) }
            val presentation = o.optJSONObject(ThingFile.KEY_CARD_PRESENTATION)
                ?.let { ThingFolderCardPresentation.fromJson(it.toString()) }
            val now = System.currentTimeMillis()
            val createTime = o.optLong(ThingFile.KEY_CREATE_TIME, now)
            result.add(
                PendingFolder(
                    key = key,
                    parentKey = parent?.takeIf { it != key && seen.contains(it) },
                    title = o.optString(ThingFile.KEY_TITLE),
                    background = bg ?: App.defaultAccentBackground,
                    cardPresentation = presentation ?: ThingFolderCardPresentation.default(),
                    createTime = createTime,
                    updateTime = o.optLong(ThingFile.KEY_UPDATE_TIME, createTime),
                    order = o.optInt(ThingFile.KEY_ORDER, i),
                    sticky = o.optBoolean(ThingFile.KEY_STICKY, false)
                )
            )
        }
        return result
    }

    /**
     * 去掉子树里一条记事也没有的文件夹：本机的「正在进行」列表不显示这样的文件夹，导进来就是看不见的空壳
     * （记事因为解析失败被跳过时会出现）。
     */
    private fun dropEmptyFolders(folders: List<PendingFolder>, things: List<PendingThing>): List<PendingFolder> {
        if (folders.isEmpty()) return folders
        val parentOf = folders.associate { it.key to it.parentKey }
        val nonEmpty = HashSet<Int>()
        for (thing in things) {
            var key = thing.folderKey
            // 上一级总在前面，沿父链往上走必然终止
            while (key != null && nonEmpty.add(key)) {
                key = parentOf[key]
            }
        }
        return folders.filter { it.key in nonEmpty }
    }

    /**
     * 类型不认识时返回 null（整条跳过）；附件缺失或类型不认识时抛 [BrokenAttachmentException]。
     * 所在的文件夹不在 [folderKeys] 里时当作顶层。
     */
    private fun toPendingThing(o: JSONObject, extracted: Map<String, File>, folderKeys: Set<Int>): PendingThing? {
        val files = ArrayList<File>()
        val types = ArrayList<Int>()
        val keys = ArrayList<String>()
        val sb = StringBuilder()
        val arr = o.optJSONArray(ThingFile.KEY_ATTACHMENTS)
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val ao = arr.optJSONObject(i) ?: continue
                val entry = ao.optString(ThingFile.KEY_ATTACHMENT_ENTRY)
                val displayName = ao.optString(ThingFile.KEY_ATTACHMENT_NAME).ifEmpty { entry }
                val file = extracted[entry] ?: throw BrokenAttachmentException(displayName)
                // attachment 串里类型只占一位，认不出的类型写进去会把路径也读歪
                val type = ao.optInt(ThingFile.KEY_ATTACHMENT_TYPE, AttachmentHelper.IMAGE)
                if (type != AttachmentHelper.IMAGE && type != AttachmentHelper.VIDEO
                    && type != AttachmentHelper.AUDIO
                ) {
                    throw BrokenAttachmentException(displayName)
                }
                sb.append(AttachmentHelper.SIGNAL).append(type).append(file.absolutePath)
                files.add(file)
                types.add(type)
                keys.add(entry)
            }
        }

        val reminderJson = o.optJSONObject(ThingFile.KEY_REMINDER)
        val notifyTime = reminderJson?.optLong(ThingFile.KEY_REMINDER_NOTIFY_TIME) ?: 0L
        val habitCandidate = o.optJSONObject(ThingFile.KEY_HABIT)?.let { h ->
            // 与 DetailActivity.createHabit 同一形态：只有 type 与 detail 来自文件，
            // 其余是新建习惯的初值，firstTime 由导入时的 initHabitReminders() 算出。
            Habit(
                0L,
                h.optInt(ThingFile.KEY_HABIT_TYPE),
                0,
                h.optString(ThingFile.KEY_HABIT_DETAIL),
                "",
                "",
                System.currentTimeMillis(),
                0L
            )
        }?.takeIf { isHabitDefinitionValid(it) }

        val type = ThingFile.normalizeType(
            o.optInt(ThingFile.KEY_TYPE, Thing.NOTE),
            hasReminder = notifyTime > 0L,
            hasHabit = habitCandidate != null
        ) ?: return null

        // 这个 color 只是构造时的初值，下面一定会被 setBackground 覆盖；
        // 清单里没带 background 时才真正用到它（退回应用默认色）。
        val fallbackBackground = App.defaultAccentBackground
        val thing = Thing(
            0L,
            type,
            o.optInt(ThingFile.KEY_STATE, Thing.UNDERWAY),
            fallbackBackground.color,
            o.optString(ThingFile.KEY_TITLE),
            o.optString(ThingFile.KEY_CONTENT),
            sb.toString(),
            0L,
            o.optLong(ThingFile.KEY_CREATE_TIME, System.currentTimeMillis()),
            o.optLong(ThingFile.KEY_UPDATE_TIME, System.currentTimeMillis()),
            o.optLong(ThingFile.KEY_FINISH_TIME, 0L)
        )

        val bgJson = o.optJSONObject(ThingFile.KEY_BACKGROUND)
        val background = if (bgJson != null) ThingBackground.fromJson(bgJson.toString()) else null
        thing.setBackground(background ?: fallbackBackground)
        o.optJSONObject(ThingFile.KEY_CARD_APPEARANCE)?.let { ca ->
            ThingCardAppearance.fromJson(ca.toString())?.let { thing.thingCardAppearance = it }
        }
        o.optJSONObject(ThingFile.KEY_DETAIL_APPEARANCE)?.let { da ->
            DetailAttachmentMediaAppearance.fromJson(da.toString())?.let {
                thing.detailAttachmentMediaAppearance = it
            }
        }

        // 只有 REMINDER / GOAL 留提醒、只有 HABIT 留习惯：规整成 NOTE 的那条不能带着闹钟进来
        val reminder = if (type == Thing.REMINDER || type == Thing.GOAL) {
            Reminder(
                0L,
                notifyTime,
                reminderJson!!.optInt(ThingFile.KEY_REMINDER_STATE),
                reminderJson.optLong(ThingFile.KEY_REMINDER_NOTIFY_MILLIS),
                thing.createTime,
                thing.updateTime
            )
        } else {
            null
        }
        val habit = if (type == Thing.HABIT) habitCandidate else null

        val folderKey = if (o.has(ThingFile.KEY_IN_FOLDER)) {
            o.optInt(ThingFile.KEY_IN_FOLDER).takeIf { it in folderKeys }
        } else {
            null
        }
        // 两份外观设置此刻仍以条目名作键，由 ThingFileImporter 在挪完附件后换成新路径
        return PendingThing(
            thing, reminder, habit, files, types, keys,
            folderKey = folderKey,
            order = o.optInt(ThingFile.KEY_ORDER, 0),
            sticky = o.optBoolean(ThingFile.KEY_STICKY, false)
        )
    }

    /**
     * 周期定义能不能解析成至少一个提醒时刻。解析不了的习惯导进去，第一次展示或到点就会出错。
     * 这里算出的时刻不保留，导入时会按那一刻重新算。
     */
    private fun isHabitDefinitionValid(habit: Habit): Boolean {
        return try {
            habit.initHabitReminders()
            !habit.habitReminders.isNullOrEmpty()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 只接受 `attachments/` 下的普通条目。挡住 zip slip：条目名里出现 `..` 或绝对路径时
     * 解压会写到目录之外。
     */
    private fun isSafeAttachmentEntry(name: String): Boolean {
        if (!name.startsWith(ThingFile.ATTACHMENT_DIR + "/")) {
            return false
        }
        if (name.contains("..") || name.startsWith("/") || name.contains(":")) {
            return false
        }
        return true
    }

    private fun readManifest(zis: ZipInputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(BUFFER)
        while (true) {
            val n = zis.read(buf)
            if (n <= 0) {
                break
            }
            if (out.size() + n > MAX_MANIFEST_BYTES) {
                throw ManifestTooLargeException()
            }
            out.write(buf, 0, n)
        }
        return String(out.toByteArray(), StandardCharsets.UTF_8)
    }

    /** 失败时删掉写了一半的文件并把异常抛给调用方：一个附件解不出来，整份文件就不完整。 */
    private fun extractTo(zis: ZipInputStream, dst: File, dir: File) {
        try {
            FileOutputStream(dst).use { fos ->
                val buf = ByteArray(BUFFER)
                // 从 CHECK_INTERVAL 起算，保证第一次写之前先查一次
                var sinceCheck = FREE_SPACE_CHECK_INTERVAL
                while (true) {
                    val n = zis.read(buf)
                    if (n <= 0) {
                        break
                    }
                    if (sinceCheck >= FREE_SPACE_CHECK_INTERVAL) {
                        if (dir.usableSpace < FREE_SPACE_RESERVE_BYTES) {
                            throw NoSpaceException()
                        }
                        sinceCheck = 0
                    }
                    fos.write(buf, 0, n)
                    sinceCheck += n
                }
            }
        } catch (e: IOException) {
            dst.delete()
            throw e
        }
    }

    /** 导入结束（无论成功与否）都要调用，临时目录里可能有上百 MB 的附件。 */
    @JvmStatic
    fun cleanUp(tempDir: File?) {
        if (tempDir == null || !tempDir.exists()) {
            return
        }
        tempDir.listFiles()?.forEach { it.delete() }
        tempDir.delete()
    }

    /**
     * 清掉残留的导入临时目录：上一次导入中途进程被杀时，[cleanUp] 没有机会执行。
     *
     * 只清超过 [STALE_TEMP_DIR_MS] 的——目录名是创建时刻，同时开着的另一个导入页正在用的
     * 目录一定比这新，不会被误删。
     */
    @JvmStatic
    fun cleanUpStale(context: Context) {
        val root = File(Def.getAppFileDir(context) + IMPORT_DIR)
        val now = System.currentTimeMillis()
        root.listFiles()?.forEach { dir ->
            val createdAt = dir.name.toLongOrNull() ?: dir.lastModified()
            if (now - createdAt > STALE_TEMP_DIR_MS) {
                cleanUp(dir)
            }
        }
    }
}
