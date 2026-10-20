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
import com.ywwynm.everythingdone.database.ThingFolderDAO
import com.ywwynm.everythingdone.helpers.AlarmHelper
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.managers.ThingManager
import com.ywwynm.everythingdone.model.Reminder
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingFolder

import java.io.File
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
 * 落点：记事文件导入（分享面板、「打开方式」、设置页）一律落在根目录（2026-09-23 用户确认）；
 * 碰一碰收到的落在发起接收时所在的文件夹（2026-09-24），由调用方传入 `folderId`。
 *
 * 文件夹（版本 2，决策 2026-09-28）按文件里的层级重建：导入的文件夹不是私密的；文件夹里的先后与置顶照发送方，
 * 顶层的文件夹与记事一样落在置顶之后。同名文件夹照样新建，不合并。
 */
object ThingFileImporter {

    private const val TAG: String = "ThingFileImporter"

    private val sMain = Handler(Looper.getMainLooper())

    /**
     * @param imported 导进来的记事条数（含文件夹里的）。
     * @param folders 导进来的文件夹个数（含子文件夹）。
     * @param topFolders 其中顶层的文件夹个数；[topThings] 是顶层的记事条数。完成提示按它们措辞。
     */
    class Result(
        val imported: Int,
        val failed: Int,
        val folders: Int = 0,
        val topFolders: Int = 0,
        val topThings: Int = imported
    )

    /** 导入时通知界面：都在主线程，与对应的写入在同一条消息里。 */
    interface Listener {
        /** 顶层的一条记事写进去了：碰一碰据此立刻把新卡片插进列表，列表不会在两次通知之间看到对不上的数据。 */
        fun onThingCommitted(thing: Thing) {}

        /**
         * 顶层的一个文件夹连同子树都写进去了。要等子树写完才通知：「正在进行」列表不显示没有记事的文件夹，
         * 刚建好的空文件夹插不进列表。子树里一条记事都没导成时文件夹会撤掉，不通知。
         */
        fun onFolderCommitted(folder: ThingFolder) {}
    }

    /** 一条记事挪好之后的附件：新的 attachment 串、外观键的映射、挪到的文件。 */
    internal class Moved(
        val attachment: String,
        val keyMapping: Map<String, String>,
        val files: List<File>
    )

    /**
     * 只导记事（不带文件夹）。见另一个 [import]。
     *
     * @param onCommitted 每条记事写进数据库之后在主线程回调，与写入在同一条消息里。
     */
    @JvmStatic
    @JvmOverloads
    @WorkerThread
    fun import(
        context: Context,
        pending: List<PendingThing>,
        folderId: Long? = null,
        onCommitted: ((Thing) -> Unit)? = null
    ): Result {
        val listener = onCommitted?.let { callback ->
            object : Listener {
                override fun onThingCommitted(thing: Thing) = callback(thing)
            }
        }
        return import(context, pending, emptyList(), folderId, listener)
    }

    /**
     * 必须在后台线程调用：附件在调用线程挪，数据库与 [ThingManager] 内存列表的写入逐条切到
     * 主线程做。
     *
     * 为什么要切：内存列表由首页在主线程读（列表绑定；提醒到点时 `App.getThingAndPosition`
     * 也在主线程遍历它），详情页新建记事同样在主线程写它。在后台线程改它，首页在分屏里可见、
     * 或导入期间恰好有提醒触发时就是并发读写。每条记事、每个文件夹单独切一次，几十条也不会长时间占住主线程。
     *
     * @param folders [pending] 所在的文件夹（先父后子）。记事或文件夹指向不在其中的文件夹时当作顶层。
     * @param folderId 顶层的项落在哪个文件夹，null 为根目录。
     */
    @JvmStatic
    @WorkerThread
    fun import(
        context: Context,
        pending: List<PendingThing>,
        folders: List<PendingFolder>,
        folderId: Long?,
        listener: Listener?
    ): Result {
        check(Looper.myLooper() != Looper.getMainLooper()) { "ThingFileImporter.import 不能在主线程调用" }
        val run = Run(context.applicationContext, pending, folders, listener)
        for (item in ThingFileTree.topLevel(run.levels)) {
            when (item) {
                is ThingItem -> run.importThing(item, folderId, top = true)
                is FolderItem -> run.importFolder(item, folderId, top = true)
            }
        }
        if (run.imported > 0) {
            // 详情页新建记事后同样会刷新列表小部件（DetailActivity.afterCreateOrUpdateThing）
            runOnMain {
                try {
                    AppWidgetHelper.updateAllThingsListAppWidgets(run.context)
                } catch (e: Exception) {
                    Log.w(TAG, "刷新列表小部件失败", e)
                }
            }
        }
        return Result(run.imported, run.failed, run.folders, run.topFolders, run.topThings)
    }

    /** 文件里的一项：记事或文件夹，连同它在哪一层、排第几。 */
    private sealed class Item {
        abstract val placement: ThingFileTree.Placement
    }

    private class ThingItem(val pending: PendingThing, override val placement: ThingFileTree.Placement) : Item()

    private class FolderItem(val pending: PendingFolder, override val placement: ThingFileTree.Placement) : Item()

    /** 一次导入的状态与计数。 */
    private class Run(
        val context: Context,
        pending: List<PendingThing>,
        folders: List<PendingFolder>,
        private val listener: Listener?
    ) {
        val levels: Map<Int?, List<Item>>

        var imported = 0
        var failed = 0
        var folders = 0
        var topFolders = 0
        var topThings = 0

        init {
            val known = folders.mapTo(HashSet()) { it.key }
            val items = ArrayList<Item>(pending.size + folders.size)
            pending.forEachIndexed { i, p ->
                items.add(ThingItem(p, ThingFileTree.Placement(p.folderKey?.takeIf { it in known }, p.order, i)))
            }
            folders.forEachIndexed { i, f ->
                val parent = f.parentKey?.takeIf { it in known && it != f.key }
                items.add(FolderItem(f, ThingFileTree.Placement(parent, f.order, pending.size + i)))
            }
            levels = ThingFileTree.group(items) { it.placement }
        }

        /** 导一条记事。[top] 为 true 时它落在 [parentId] 这一层的置顶之后，并通知界面。 */
        fun importThing(item: ThingItem, parentId: Long?, top: Boolean): Boolean {
            val p = item.pending
            val moved = try {
                moveAttachments(p)
            } catch (e: IOException) {
                Log.e(TAG, "挪第 ${item.placement.index} 条记事的附件失败", e)
                failed++
                return false
            }
            val ok = runOnMain {
                val committed = commit(context, p, moved, parentId, top)
                if (committed && top && listener != null) {
                    try {
                        listener.onThingCommitted(p.thing)
                    } catch (e: Exception) {
                        // 记事已经写进去了，界面上没插进列表只影响显示，不能因此报导入失败
                        Log.e(TAG, "导入后通知界面失败", e)
                    }
                }
                committed
            }
            if (ok) {
                imported++
                if (top) topThings++
            } else {
                moved.files.forEach { it.delete() }
                failed++
            }
            return ok
        }

        /** 建一个文件夹并导入它的子树，返回导成的记事条数（含子文件夹里的）。 */
        fun importFolder(item: FolderItem, parentId: Long?, top: Boolean): Int {
            val f = item.pending
            val folder = try {
                runOnMain {
                    val manager = ThingManager.getInstance(context) ?: return@runOnMain null
                    val dao = ThingFolderDAO.getInstance(context) ?: return@runOnMain null
                    // 顶层的文件夹不置顶（决策 2026-09-28 第 7 条）
                    val location = dao.getFirstChildLocation(parentId, sticky = !top && f.sticky)
                    manager.createImportedFolder(
                        parentId, f.title, f.background, f.cardPresentation, location, f.createTime, f.updateTime
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "建文件夹失败", e)
                null
            }
            if (folder == null) {
                failed += countThings(f.key)
                return 0
            }
            folders++
            var count = 0
            for (child in ThingFileTree.creationOrder(levels, f.key)) {
                count += when (child) {
                    is ThingItem -> if (importThing(child, folder.id, top = false)) 1 else 0
                    is FolderItem -> importFolder(child, folder.id, top = false)
                }
            }
            if (count == 0) {
                runOnMain { ThingManager.getInstance(context)?.deleteImportedFolder(folder.id) }
                folders--
                return 0
            }
            if (top) {
                topFolders++
                runOnMain {
                    val manager = ThingManager.getInstance(context)
                    if (manager != null && shouldShowInCurrentList(manager, parentId)) {
                        manager.rebuildListEntries()
                    }
                    try {
                        listener?.onFolderCommitted(folder)
                    } catch (e: Exception) {
                        Log.e(TAG, "导入后通知界面失败", e)
                    }
                }
            }
            return count
        }

        private fun countThings(key: Int): Int = levels[key].orEmpty().sumOf {
            when (it) {
                is ThingItem -> 1
                is FolderItem -> countThings(it.pending.key)
            }
        }
    }

    /**
     * 把一条记事写进数据库。顺序照 DetailActivity 新建记事：**先建提醒与习惯，再建记事**。
     *
     * `ThingManager.create` 内部的 `AutoNotifyHelper.createAutoNotify` 在用户开了「自动提醒」时
     * 会对提醒 / 习惯行做非空断言；反过来先建记事，就会在那里抛异常，留下一条缺提醒或缺习惯的
     * 记事（缺习惯的那条点开即崩）。
     *
     * @param top 顶层的记事落在置顶之后（location 取 id，与详情页新建一致）；文件夹里的放到所在区段（置顶或
     *        普通）的最前面，由调用方倒着建出发送方的先后（[ThingFileTree]）。
     * @return 成功与否。失败时本条已写入的提醒与习惯都会撤掉：它们的 id 是当前的 headerId，
     *         记事没建成时 headerId 不会前进，留着就会挂到下一条新建的记事上。
     */
    @MainThread
    private fun commit(context: Context, p: PendingThing, moved: Moved, folderId: Long?, top: Boolean): Boolean {
        val manager = ThingManager.getInstance(context) ?: return false
        val thing = p.thing
        // create() 内部会把 id 设成同一个 headerId；location 它不动，这里自己设
        val id = manager.getHeaderId()
        var reminderCreated = false
        var habitCreated = false
        try {
            thing.id = id
            thing.location = if (top) {
                id
            } else {
                ThingFolderDAO.getInstance(context)!!.getFirstChildLocation(folderId, p.sticky)
            }
            thing.attachment = moved.attachment
            // 两份外观设置（附件显示比例、裁剪、视频帧、卡片封面的选择与位置）此刻以记事文件里的
            // 条目名作键，换成本机的新 typePathName。不换的话它们会全部对不上而丢失。
            thing.thingCardAppearance = thing.thingCardAppearance.remapSources(moved.keyMapping)
            thing.detailAttachmentMediaAppearance =
                thing.detailAttachmentMediaAppearance.remapSources(moved.keyMapping)
            thing.folderId = folderId
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
                thing, true, top && shouldShowInCurrentList(manager, folderId), inheritCurrentFolder = false
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
     * 导入的记事（或文件夹）落在 [folderId]、状态为未完成。只有首页当前正显示「这个文件夹 · 未完成 ·
     * 不按类型筛选 · 不在搜索」时才直接插进内存列表；其余情况插进去就是列错了地方，交给首页
     * 收到 justNotifyAll 后从数据库重读。
     */
    private fun shouldShowInCurrentList(manager: ThingManager, folderId: Long?): Boolean {
        val projection = manager.getProjection()
        return projection.status == Def.ThingStatus.UNDERWAY
                && projection.currentFolderId == folderId
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
    internal fun moveAttachments(p: PendingThing): Moved {
        val entries = p.attachmentFiles.indices.map { i ->
            ThingFileAttachmentStore.Entry(p.attachmentKeys[i], p.attachmentTypes[i], p.attachmentFiles[i],
                name = p.attachmentNames[i],
                reused = p.attachmentKeys[i] in p.reusedAttachmentKeys)
        }
        val moved = ThingFileAttachmentStore.move(entries, File(Def.getAppFileDir(App.getApp())), AttachmentHelper.SIGNAL)
        val sb = StringBuilder()
        val mapping = HashMap<String, String>(p.attachmentFiles.size)
        for (entry in entries) {
            val typePathName = entry.type.toString() + moved.filesByKey.getValue(entry.key).absolutePath
            sb.append(AttachmentHelper.SIGNAL).append(typePathName)
            mapping[entry.key] = typePathName
        }
        return Moved(sb.toString(), mapping, moved.newFiles)
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
