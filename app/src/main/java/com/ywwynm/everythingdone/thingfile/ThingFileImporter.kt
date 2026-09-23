package com.ywwynm.everythingdone.thingfile

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

import androidx.annotation.MainThread
import androidx.annotation.WorkerThread

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.appwidgets.AppWidgetHelper
import com.ywwynm.everythingdone.database.HabitDAO
import com.ywwynm.everythingdone.database.ReminderDAO
import com.ywwynm.everythingdone.database.ThingDAO
import com.ywwynm.everythingdone.helpers.AlarmHelper
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.managers.ThingManager
import com.ywwynm.everythingdone.model.Reminder
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.utils.FileUtil

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/**
 * 把 [ThingFileReader] 读出来的 [PendingThing] 真正写进本机。
 *
 * 逐条独立导入：某一条失败不影响其他条，最后报告成功与失败的条数。失败的那一条不留下任何
 * 数据——不能导入一条缺附件、缺提醒或缺习惯的记事却报成功。
 *
 * 导入的记事一律落在根目录（2026-09-23 用户确认；以后有了新的导入入口再定是否跟随文件夹）。
 */
object ThingFileImporter {

    private const val TAG: String = "ThingFileImporter"

    private const val BUFFER: Int = 64 * 1024

    private val sMain = Handler(Looper.getMainLooper())

    class Result(val imported: Int, val failed: Int)

    /** 一条记事挪好之后的附件：新的 attachment 串、外观键的映射、挪到的文件。 */
    private class Moved(
        val attachment: String,
        val keyMapping: Map<String, String>,
        val files: List<File>
    )

    /**
     * 必须在后台线程调用：附件在调用线程挪，数据库与 [ThingManager] 内存列表的写入逐条切到
     * 主线程做。
     *
     * 为什么要切：内存列表由首页在主线程读（列表绑定；提醒到点时 `App.getThingAndPosition`
     * 也在主线程遍历它），详情页新建记事同样在主线程写它。在后台线程改它，首页在分屏里可见、
     * 或导入期间恰好有提醒触发时就是并发读写。每条记事单独切一次，几十条也不会长时间占住主线程。
     */
    @JvmStatic
    @WorkerThread
    fun import(context: Context, pending: List<PendingThing>): Result {
        check(Looper.myLooper() != Looper.getMainLooper()) { "ThingFileImporter.import 不能在主线程调用" }
        val appContext = context.applicationContext
        // 整次导入共用一个时间戳，文件名再带记事序号与附件序号，同一次导入里不会重名
        val stamp = System.currentTimeMillis()
        var imported = 0
        var failed = 0
        for ((index, p) in pending.withIndex()) {
            val moved = try {
                moveAttachments(p, stamp, index)
            } catch (e: IOException) {
                Log.e(TAG, "挪第 $index 条记事的附件失败", e)
                failed++
                continue
            }
            val ok = runOnMain { commit(appContext, p, moved) }
            if (ok) {
                imported++
            } else {
                moved.files.forEach { it.delete() }
                failed++
            }
        }
        if (imported > 0) {
            // 详情页新建记事后同样会刷新列表小部件（DetailActivity.afterCreateOrUpdateThing）
            runOnMain {
                try {
                    AppWidgetHelper.updateAllThingsListAppWidgets(appContext)
                } catch (e: Exception) {
                    Log.w(TAG, "刷新列表小部件失败", e)
                }
            }
        }
        return Result(imported, failed)
    }

    /**
     * 把一条记事写进数据库。顺序照 DetailActivity 新建记事：**先建提醒与习惯，再建记事**。
     *
     * `ThingManager.create` 内部的 `AutoNotifyHelper.createAutoNotify` 在用户开了「自动提醒」时
     * 会对提醒 / 习惯行做非空断言；反过来先建记事，就会在那里抛异常，留下一条缺提醒或缺习惯的
     * 记事（缺习惯的那条点开即崩）。
     *
     * @return 成功与否。失败时本条已写入的提醒与习惯都会撤掉：它们的 id 是当前的 headerId，
     *         记事没建成时 headerId 不会前进，留着就会挂到下一条新建的记事上。
     */
    @MainThread
    private fun commit(context: Context, p: PendingThing, moved: Moved): Boolean {
        val manager = ThingManager.getInstance(context) ?: return false
        val thing = p.thing
        // create() 内部会把 id 设成同一个 headerId；location 它不动，这里自己设（与详情页新建一致）
        val id = manager.getHeaderId()
        var reminderCreated = false
        var habitCreated = false
        try {
            thing.id = id
            thing.location = id
            thing.attachment = moved.attachment
            // 两份外观设置（附件显示比例、裁剪、视频帧、卡片封面的选择与位置）此刻以记事文件里的
            // 条目名作键，换成本机的新 typePathName。不换的话它们会全部对不上而丢失。
            thing.thingCardAppearance = thing.thingCardAppearance.remapSources(moved.keyMapping)
            thing.detailAttachmentMediaAppearance =
                thing.detailAttachmentMediaAppearance.remapSources(moved.keyMapping)
            thing.folderId = null
            // 一律以「未完成」写入：ThingsCounts.handleCreation 硬编码了 Thing.UNDERWAY，带着
            // 「已完成」直接建会让计数与数据库里的 state 对不上。
            thing.state = Thing.UNDERWAY
            thing.finishTime = 0L

            p.reminder?.let { r ->
                createReminder(context, id, r.notifyTime)
                reminderCreated = true
            }
            p.habit?.let { h ->
                h.id = id
                h.initHabitReminders()
                HabitDAO.getInstance(context)!!.createHabit(h)
                habitCreated = true
            }
            manager.create(
                thing, true, shouldShowInCurrentList(manager), inheritCurrentFolder = false
            )
            return true
        } catch (e: Exception) {
            Log.e(TAG, "导入一条记事失败", e)
            if (ThingDAO.getInstance(context)?.getThingById(id) != null) {
                // 记事行已经写进去了，异常出在 create() 的收尾步骤。提醒与习惯留着才与记事一致
                return true
            }
            if (reminderCreated) {
                ReminderDAO.getInstance(context)?.delete(id)
            }
            if (habitCreated) {
                HabitDAO.getInstance(context)?.deleteHabit(id)
            }
            return false
        }
    }

    /**
     * 提醒时刻已过的，照 `AlarmHelper.createAllAlarms` 的既有做法标成已过期、不设闹钟
     * （决策：过期提醒按应用现有的过期表现走）。`ReminderDAO.create` 不看时间一律设闹钟，
     * 时刻在过去的闹钟会立刻触发，导入那一刻就会弹通知，所以建完立刻撤掉。
     */
    private fun createReminder(context: Context, id: Long, notifyTime: Long) {
        val dao = ReminderDAO.getInstance(context)!!
        val reminder = Reminder(id, notifyTime)
        if (notifyTime > System.currentTimeMillis()) {
            dao.create(reminder)
            return
        }
        reminder.state = Reminder.EXPIRED
        dao.create(reminder)
        AlarmHelper.deleteReminderAlarm(context, id)
    }

    /**
     * 导入的记事落在根目录、状态为未完成。只有首页当前正显示「根目录 · 未完成 · 不按类型筛选 ·
     * 不在搜索」时才直接插进内存列表；其余情况插进去就是列错了地方，交给首页收到
     * justNotifyAll 后从数据库重读。
     */
    private fun shouldShowInCurrentList(manager: ThingManager): Boolean {
        val projection = manager.getProjection()
        return projection.status == Def.ThingStatus.UNDERWAY
                && projection.isRoot()
                && !manager.hasCustomTypeFilter()
                && !App.isSearching
    }

    /**
     * 把一条记事的附件从临时目录挪进附件目录，返回重写过路径的 attachment 串与外观键的映射。
     *
     * 临时目录与附件目录都在应用的外部文件目录下，优先改名：不占额外空间、瞬间完成。改名失败
     * 才复制。任何一个附件失败都整条失败，并删掉这一条已经挪过去的附件。
     *
     * 不用 [AttachmentHelper.createAttachmentFile]：它的文件名只到秒，同一秒里建多个附件会重名，
     * 而且扩展名固定成 .jpg/.mp4/.wav，对 png、gif、m4a 这些附件是错的。
     */
    private fun moveAttachments(p: PendingThing, stamp: Long, thingIndex: Int): Moved {
        val sb = StringBuilder()
        val mapping = HashMap<String, String>(p.attachmentFiles.size)
        val done = ArrayList<File>(p.attachmentFiles.size)
        try {
            for (i in p.attachmentFiles.indices) {
                val src = p.attachmentFiles[i]
                val type = p.attachmentTypes[i]
                val dst = createFileFor(type, stamp, thingIndex, i, src.name)
                    ?: throw IOException("建不了附件文件：${src.name}")
                if (!src.renameTo(dst)) {
                    copyFile(src, dst)
                }
                done.add(dst)
                val newTypePathName = type.toString() + dst.absolutePath
                sb.append(AttachmentHelper.SIGNAL).append(newTypePathName)
                mapping[p.attachmentKeys[i]] = newTypePathName
            }
        } catch (e: IOException) {
            done.forEach { it.delete() }
            throw e
        }
        return Moved(sb.toString(), mapping, done)
    }

    private fun copyFile(src: File, dst: File) {
        try {
            FileInputStream(src).use { input ->
                FileOutputStream(dst).use { output ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) {
                            break
                        }
                        output.write(buf, 0, n)
                    }
                }
            }
        } catch (e: IOException) {
            dst.delete()
            throw e
        }
    }

    private fun createFileFor(
        type: Int, stamp: Long, thingIndex: Int, attachmentIndex: Int, originalName: String
    ): File? {
        val folder = when (type) {
            AttachmentHelper.IMAGE -> "images"
            AttachmentHelper.VIDEO -> "videos"
            else -> "audios"
        }
        // 保留原扩展名：记事文件里的附件就是原文件
        val postfix = FileUtil.getPostfix(originalName).let {
            if (it.isNullOrEmpty()) defaultPostfix(type) else it
        }
        val base = "import_${stamp}_${thingIndex}_$attachmentIndex"
        var file = FileUtil.createFile(
            Def.getAppFileDir(App.getApp()) + "/" + folder, "$base.$postfix"
        ) ?: return null
        // FileUtil.createFile 只拼路径、不查重，复制又会直接覆盖；同名文件（同一毫秒里的上一次
        // 导入）存在时换个名字，绝不覆盖别的记事的附件
        var k = 1
        while (file.exists()) {
            file = File(file.parentFile, "${base}_$k.$postfix")
            k++
        }
        return file
    }

    private fun defaultPostfix(type: Int): String = when (type) {
        AttachmentHelper.IMAGE -> "jpg"
        AttachmentHelper.VIDEO -> "mp4"
        else -> "wav"
    }

    private fun <T> runOnMain(block: () -> T): T {
        val task = FutureTask(Callable(block))
        sMain.post(task)
        try {
            return task.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }
}
