package com.ywwynm.everythingdone.taptransfer

import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.MotionEvent
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentDialog
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DefaultItemAnimator
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.activities.ThingsActivity
import com.ywwynm.everythingdone.adapters.BaseThingsAdapter
import com.ywwynm.everythingdone.database.DBHelper
import com.ywwynm.everythingdone.database.HabitDAO
import com.ywwynm.everythingdone.helpers.AlarmHelper
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.helpers.CheckListHelper
import com.ywwynm.everythingdone.helpers.VideoCoverPreviewManager
import com.ywwynm.everythingdone.managers.ThingManager
import com.ywwynm.everythingdone.model.*
import com.ywwynm.everythingdone.taptransfer.transport.TapIncomingOffer
import com.ywwynm.everythingdone.taptransfer.transport.TapReceiverTransport
import com.ywwynm.everythingdone.taptransfer.transport.TapReceivedPayload
import com.ywwynm.everythingdone.taptransfer.protocol.TapFileTransfer
import com.ywwynm.everythingdone.taptransfer.protocol.TapStream
import com.ywwynm.everythingdone.taptransfer.ui.receive.TapDetailReceiveController
import com.ywwynm.everythingdone.taptransfer.ui.receive.TapReceivePull
import com.ywwynm.everythingdone.taptransfer.ui.receive.TapUpdateSettingsDialog
import com.ywwynm.everythingdone.thingfile.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 只建立并清理本次临时行；驱动脚本另对原有各表做前后核对。传输回调可控，渲染与业务路径均为生产代码。 */
class TapDetailUpdateProbe : Instrumentation() {
    private var args = Bundle()
    private val ids = mutableListOf<Long>()
    private val folders = mutableListOf<Long>()
    private val files = mutableListOf<File>()
    private val cases = JSONArray()
    private val mediaEntryMeasurements = JSONArray()
    private val homeMeasurements = JSONArray()
    private var current: DetailActivity? = null
    private lateinit var helper: DBHelper
    private lateinit var db: SQLiteDatabase
    private lateinit var dir: File
    private val epoch = 1780272000000L
    private val future = 1924905600000L

    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); args = arguments ?: Bundle(); start() }

    override fun onStart() {
        waitForIdleSync()
        val prefs = targetContext.getSharedPreferences("tap_transfer_debug", 0)
        val hadSimulation = prefs.contains("simulate")
        val simulation = prefs.getBoolean("simulate", false)
        val report = JSONObject()
        try {
            helper = DBHelper(targetContext); db = helper.writableDatabase
            dir = File(targetContext.cacheDir, "tap-update-probe-${System.currentTimeMillis()}").apply { mkdirs() }
            prefs.edit().putBoolean("simulate", true).commit()
            if (args.getString("suite") == "home") homeReturnCases()
            else if (args.getString("suite") == "reuse") { attachmentReuseCases(); readerCases(); reviewCases() }
            else if (args.getString("suite") == "media-entry") mediaEntryCases()
            else if (args.getString("suite") == "review") reviewCases()
            else {
                if (args.getString("suite") != "animation") { storageCases(); readerCases() }
                detailCases()
                if (args.getString("suite") != "animation") cancellationCases()
            }
            report.put("passed", true)
        } catch (e: Throwable) {
            android.util.Log.e("TapDetailUpdateProbe", "回归失败", e)
            report.put("passed", false).put("error", e.stackTraceToString())
        } finally {
            main { current?.finish() }; waitForIdleSync()
            if (::db.isInitialized) {
                ids.forEach { id ->
                    AlarmHelper.deleteReminderAlarm(targetContext, id)
                    db.query("habit_reminders", arrayOf("id"), "habit_id=?", arrayOf("$id"), null, null, null).use {
                        while (it.moveToNext()) AlarmHelper.deleteHabitReminderAlarm(targetContext, it.getLong(0))
                    }
                    for (table in listOf("reminders", "habits", "habit_reminders", "habit_records", "things")) {
                        val column = if (table == "habit_reminders" || table == "habit_records") "habit_id" else "id"
                        db.delete(table, "$column=?", arrayOf("$id"))
                    }
                }
                folders.forEach { db.delete("thing_folders", "id=?", arrayOf("$it")) }
                HabitDAO.getInstance(targetContext)!!.refreshIdsAfterContentUpdate()
                helper.close()
            }
            files.forEach { it.delete() }
            if (::dir.isInitialized) dir.deleteRecursively()
            prefs.edit().apply { if (hadSimulation) putBoolean("simulate", simulation) else remove("simulate") }.commit()
            report.put("cases", cases).put("fixtures_removed", JSONArray(ids))
                .put("media_entry_measurements", mediaEntryMeasurements)
                .put("home_measurements", homeMeasurements)
            finish(if (report.optBoolean("passed")) 0 else 1, Bundle().apply { putString("result", report.toString()) })
        }
    }

    private fun fixture(type: Int = Thing.NOTE, state: Int = Thing.UNDERWAY, private: Boolean = false): Thing {
        val id = next("things")
        val title = (if (private) Thing.PRIVATE_THING_PREFIX else "") + "更新前 · 临时测试记事"
        db.insertOrThrow("things", null, ContentValues().apply {
            put("id", id); put("type", type); put("state", state); put("color", Color.rgb(40, 102, 114))
            put("title", title); put("content", "这是更新前的内容。\n" + "旧内容保留显示，直到新内容覆盖完成。\n".repeat(16))
            put("attachment", ""); put("location", 1000000000L + id); put("create_time", epoch)
            put("update_time", epoch); put("finish_time", if (state == Thing.FINISHED) epoch + 1 else 0L)
        })
        ids += id
        return read(id)
    }

    /** 用真实 TCP 协议在单机回环上传输，再交付生产接收回调，不依赖实体 NFC 碰触。 */
    private fun transfer(content: ThingShareContent, local: List<TapFileTransfer.LocalAttachment>,
        receiver: TapDetailReceiveController? = null, reverse: Boolean = false): TapFileTransfer.Received {
        val outgoing = ThingFileWriter.prepareTransfer(targetContext, content)
        val executor = Executors.newSingleThreadExecutor()
        val token = ByteArray(16) { it.toByte() }
        val receiverId = ByteArray(16) { (it + 32).toByte() }
        try {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                val sending = executor.submit<Long> {
                    (if (reverse) Socket(InetAddress.getLoopbackAddress(), server.localPort) else server.accept()).use { socket ->
                        socket.soTimeout = 30000
                        if (reverse) TapStream.writeSenderHello(socket.getOutputStream(), token, receiverId)
                        val hello = TapStream.readClientHello(socket.getInputStream())
                        check(hello.token.contentEquals(token) && hello.receiverId.contentEquals(receiverId))
                        val sent = TapFileTransfer.send(socket.getInputStream(), socket.getOutputStream(), outgoing)
                        TapStream.readAck(socket.getInputStream())
                        sent
                    }
                }
                val received = (if (reverse) server.accept() else Socket(InetAddress.getLoopbackAddress(), server.localPort)).use { socket ->
                    socket.soTimeout = 30000
                    if (reverse) check(TapStream.readSenderHello(socket.getInputStream()).token.contentEquals(token))
                    TapStream.writeClientHello(socket.getOutputStream(), token, receiverId)
                    TapFileTransfer.receive(socket.getInputStream(), socket.getOutputStream(),
                        File(dir, "wire-${System.nanoTime()}"), local,
                        connected = { total -> if (receiver != null) main { receiver.onConnected(total) } },
                        progress = { done, total -> if (receiver != null) main { receiver.onProgress(done, total) } }).also {
                        TapStream.writeAck(socket.getOutputStream())
                    }
                }
                check(sending.get(30, TimeUnit.SECONDS) == received.transferredBytes)
                return received
            }
        } finally { executor.shutdownNow() }
    }

    private fun attachmentReuseCases() {
        fun key(type: Int, file: File) = "$type${file.absolutePath}"
        fun keys(thing: Thing) = thing.attachment.orEmpty().split(AttachmentHelper.SIGNAL).drop(1)
        fun locals(thing: Thing) = keys(thing).map { TapFileTransfer.LocalAttachment(it[0].digitToInt(), File(it.substring(1))) }
        fun image(name: String, color: Int): File = File(dir, name).also { file ->
            val bitmap = Bitmap.createBitmap(96, 72, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
        val video = File(dir, "original.mp4").also { file -> targetContext.assets.open("detail-regression/video.mp4").use { input ->
            file.outputStream().use { input.copyTo(it) }
        } }
        val a = image("original-a.png", Color.RED)
        val b = image("original-b.png", Color.BLUE)
        val videoKey = key(1, video)
        val old = fixture().apply {
            content = "复用验证：更新前"
            attachment = AttachmentHelper.toAttachmentStr(listOf(videoKey, key(0, a), key(0, b)), emptyList())
            thingCardAppearance = ThingCardAppearance(mediaSourceKey = videoKey,
                sources = mapOf(videoKey to ThingCardAppearance.SourceAppearance(videoFrameMs = 0)))
            detailAttachmentMediaAppearance = DetailAttachmentMediaAppearance().withSource(videoKey,
                DetailAttachmentMediaAppearance.SourceAppearance(videoFrameMs = 0))
        }
        ThingFileUpdate.write(targetContext, old, null)
        val gif = checkNotNull(VideoCoverPreviewManager.generateBlocking(targetContext, video.path, 0))
        files += gif
        val gifHash = TapFileTransfer.identify(0, gif)
        val gifTime = gif.lastModified()
        val videoTime = video.lastModified()
        val workManager = androidx.work.WorkManager.getInstance(targetContext)
        fun workIds() = workManager.getWorkInfosForUniqueWork(gif.name).get(30, TimeUnit.SECONDS).map { it.id }.toSet()
        val beforeWork = workIds()
        val senderVideo = File(dir, "renamed-video.mp4").also { video.copyTo(it) }
        val senderA = File(dir, "renamed-a.png").also { a.copyTo(it) }
        val senderB = File(dir, "renamed-b.png").also { b.copyTo(it) }
        val senderKey = key(1, senderVideo)
        val incoming = Thing(old).apply {
            content = "复用验证：正文已更新，视频位于第三个附件。"
            attachment = AttachmentHelper.toAttachmentStr(listOf(key(0, senderB), key(0, senderA), senderKey), emptyList())
            thingCardAppearance = thingCardAppearance.remapSources(mapOf(videoKey to senderKey))
            detailAttachmentMediaAppearance = detailAttachmentMediaAppearance.remapSources(mapOf(videoKey to senderKey))
        }
        val activity = open(old.id)
        fun apply(thing: Thing, expectedBytes: Long, reverse: Boolean = false) {
            val receiver = begin(activity)
            val host = main { field<TapDetailReceiveController.Host>(receiver, "host") }
            val local = main { host.existingAttachments() }
            main { receiver.onTouched(TapIncomingOffer("附件复用模拟", 1, intArrayOf(thing.getColor()))) }
            val received = transfer(ThingShareContent.ofThings(listOf(thing)), local, receiver, reverse)
            check(received.transferredBytes == expectedBytes) { "附件实际传输 ${received.transferredBytes}，预期 $expectedBytes" }
            main { receiver.onReceived(TapReceivedPayload.Files(received)) }
            await(30000) { !receiver.isReceiving }
            check(read(old.id).content == thing.content)
            files += locals(read(old.id)).map { it.file }.filter { it != video && it != a && it != b }
        }
        apply(incoming, 0)
        check(keys(read(old.id)) == listOf(key(0, b), key(0, a), videoKey))
        check(video.lastModified() == videoTime && gif.lastModified() == gifTime)
        check(TapFileTransfer.identify(0, gif) == gifHash && workIds() == beforeWork)
        check(VideoCoverPreviewManager.getReadyPreview(targetContext, video.path, 0) == gif)
        check(read(old.id).thingCardAppearance.mediaSourceKey == videoKey)
        cases.put("附件 1 匹配来件 3、全部改名重排：附件传输 0 字节，原路径及 GIF 完整保留，无新生成任务")
        menu(activity, R.id.act_undo)
        check(read(old.id).attachment == old.attachment && read(old.id).content == old.content)
        menu(activity, R.id.act_redo)
        check(keys(read(old.id)) == listOf(key(0, b), key(0, a), videoKey))
        check(video.exists() && gif.exists())
        cases.put("重排复用后整次撤销／重做，旧附件与 GIF 均可用")

        val added = image("added.png", Color.GREEN)
        val mixed = Thing(incoming).apply {
            content = "新增绿色附件，删除红色附件。"
            attachment = AttachmentHelper.toAttachmentStr(listOf(key(0, added), senderKey, key(0, senderB)), emptyList())
        }
        apply(mixed, added.length(), reverse = true)
        val mixedKeys = keys(read(old.id))
        check(mixedKeys[1] == videoKey && mixedKeys[2] == key(0, b) && mixedKeys.size == 3)
        check(File(mixedKeys[0].substring(1)).readBytes().contentEquals(added.readBytes()))
        check(File(mixedKeys[0].substring(1)).extension == "png")
        menu(activity, R.id.act_undo)
        check(keys(read(old.id)) == listOf(key(0, b), key(0, a), videoKey))
        menu(activity, R.id.act_redo)
        check(keys(read(old.id)) == mixedKeys)
        cases.put("反向建组的 TCP 方向模拟：新增、删除与重排混合，仅发送新增 PNG 的 ${added.length()} 字节，撤销／重做完整")

        val frameChanged = Thing(read(old.id)).apply {
            content = "同一视频改用新的封面起始时间。"
            thingCardAppearance = thingCardAppearance.copy(sources = thingCardAppearance.sources +
                (videoKey to ThingCardAppearance.SourceAppearance(videoFrameMs = 600)))
            detailAttachmentMediaAppearance = detailAttachmentMediaAppearance.withSource(videoKey,
                DetailAttachmentMediaAppearance.SourceAppearance(videoFrameMs = 600))
        }
        apply(frameChanged, 0)
        check(read(old.id).thingCardAppearance.sources[videoKey]?.videoFrameMs == 600L)
        check(read(old.id).detailAttachmentMediaAppearance.source(videoKey)?.videoFrameMs == 600L)
        // 更新外观已可能触发唯一 Worker；测试必须走相同调度，不能再直接并发调用编码器。
        main { VideoCoverPreviewManager.requestPreview(targetContext, video.path, 600) {} }
        await(90000) { VideoCoverPreviewManager.getReadyPreview(targetContext, video.path, 600) != null }
        val changedGif = checkNotNull(VideoCoverPreviewManager.getReadyPreview(targetContext, video.path, 600))
        files += changedGif
        check(changedGif != gif && gif.exists() && video.lastModified() == videoTime)
        check(VideoCoverPreviewManager.generateBlocking(targetContext, video.path, 600) == changedGif)
        cases.put("只改封面参数仍传输 0 附件字节，参数正确映射到原视频；新参数生成独立 GIF，再次请求复用")
        uiAutomation.takeScreenshot()?.let { bitmap ->
            File(targetContext.getExternalFilesDir(null), "tap-reuse-result.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }

        val duplicateFile = File(dir, "duplicate-video.mp4").also { video.copyTo(it) }
        val duplicateKey = key(1, duplicateFile)
        val duplicate = Thing(frameChanged).apply {
            attachment = AttachmentHelper.toAttachmentStr(listOf(videoKey, duplicateKey), emptyList())
            detailAttachmentMediaAppearance = DetailAttachmentMediaAppearance()
                .withSource(videoKey, DetailAttachmentMediaAppearance.SourceAppearance(videoFrameMs = 0))
                .withSource(duplicateKey, DetailAttachmentMediaAppearance.SourceAppearance(videoFrameMs = 600))
        }
        val received = transfer(ThingShareContent.ofThings(listOf(duplicate)), listOf(TapFileTransfer.LocalAttachment(1, video)))
        check(received.transferredBytes == 0L && received.borrowedKeys.size == 1)
        val parsed = TapReceivedPayload.Files(received).read(targetContext) as ThingFileReader.Result.Ok
        val prepared = ThingFileUpdate.prepare(parsed.things.single())
        check(keys(prepared.content).toSet().size == 2)
        check(prepared.content.detailAttachmentMediaAppearance.sources.values.map { it.videoFrameMs }.toSet() == setOf(0L, 600L))
        prepared.discard(); received.discard()
        check(video.exists() && gif.exists())
        cases.put("重复内容只复用／本地复制，不合并附件条目；两个封面参数各自保留，放弃更新不删除借用文件")

        val late = transfer(ThingShareContent.ofThings(listOf(frameChanged)), locals(read(old.id)))
        val canceled = begin(activity)
        main { canceled.exit(immediate = true); canceled.onReceived(TapReceivedPayload.Files(late)) }
        check(!late.directory.exists() && video.exists() && gif.exists())
        check(read(old.id).content == frameChanged.content)
        cases.put("取消后的协商来件只清理自有目录，原视频和 GIF 保留")
        main { activity.finish() }; await { activity.isDestroyed }; current = null
    }

    /** 从真实首页打开详情并返回，观察实际通知及已绑定卡片，避免只检查数据库。 */
    private fun homeReturnCases() {
        val manager = main { ThingManager.getInstance(targetContext)!! }
        val previousProjection = main { manager.getProjection() }
        val video = File(dir, "home-original.mp4").also { file ->
            targetContext.assets.open("detail-regression/video.mp4").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }
        val picture = File(dir, "home-image.png").also { file ->
            Bitmap.createBitmap(96, 72, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.BLUE)
                file.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
                recycle()
            }
        }
        val videoKey = "1${video.path}"
        val pictureKey = "0${picture.path}"
        // 在界面绑定前备好两个封面，检查返回首页时选中的是对应缓存。
        val previews = listOf(0L, 600L).associateWith {
            checkNotNull(VideoCoverPreviewManager.generateBlocking(targetContext, video.path, it)).also(files::add)
        }
        val previewStamps = previews.mapValues { (_, file) -> file.lastModified() to TapFileTransfer.identify(0, file) }
        val original = fixture().apply {
            title = "首页刷新验证"; content = "接收前正文"
            attachment = AttachmentHelper.toAttachmentStr(listOf(videoKey, pictureKey), emptyList())
            thingCardAppearance = ThingCardAppearance(imagePlacement = Thing.THING_CARD_IMAGE_PLACEMENT_TOP,
                mediaSourceKey = videoKey, sources = mapOf(videoKey to ThingCardAppearance.SourceAppearance(videoFrameMs = 0)))
        }
        ThingFileUpdate.write(targetContext, original, null)
        main {
            manager.navigateToFolderPathIndex(-1, false)
            manager.setTypeFilterMask(ThingWidgetInfo.TYPE_FILTER_ALL, false)
            App.getApp()!!.setStatus(Def.ThingStatus.UNDERWAY, true)
        }
        val home = startActivitySync(Intent(targetContext, ThingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ThingsActivity
        var allChanges = 0
        var itemChanges = 0
        var insertedItems = 0
        var removedItems = 0
        val observer = object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() { allChanges++ }
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) { itemChanges += itemCount }
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) { insertedItems += itemCount }
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { removedItems += itemCount }
        }
        var list: RecyclerView? = null
        try {
            await(20000) { home.hasWindowFocus() && field<RecyclerView?>(home, "mRecyclerView")?.adapter != null }
            list = main { field<RecyclerView>(home, "mRecyclerView") }
            val recycler = list
            var animatedStableId = original.id
            var changeAnimations = 0
            var animatedFrames = 0
            main {
                val originalAnimator = recycler.itemAnimator as DefaultItemAnimator
                recycler.itemAnimator = object : DefaultItemAnimator() {
                    init {
                        addDuration = originalAnimator.addDuration
                        removeDuration = originalAnimator.removeDuration
                        moveDuration = originalAnimator.moveDuration
                        changeDuration = originalAnimator.changeDuration
                        supportsChangeAnimations = originalAnimator.supportsChangeAnimations
                    }
                    override fun animateChange(oldHolder: RecyclerView.ViewHolder, newHolder: RecyclerView.ViewHolder?,
                        fromX: Int, fromY: Int, toX: Int, toY: Int): Boolean {
                        if (oldHolder.itemView.getTag(R.id.tag_thing_card_bound_stable_id) == animatedStableId) changeAnimations++
                        return super.animateChange(oldHolder, newHolder, fromX, fromY, toX, toY)
                    }
                }
                recycler.viewTreeObserver.addOnPreDrawListener {
                    if ((0 until recycler.childCount).any { index ->
                        val child = recycler.getChildAt(index)
                        child.getTag(R.id.tag_thing_card_bound_stable_id) == animatedStableId && child.alpha > 0f && child.alpha < 1f
                    }) animatedFrames++
                    true
                }
            }
            main { recycler.adapter!!.registerAdapterDataObserver(observer) }
            fun assertCard(expected: Thing) {
                if (animatedStableId < 0) {
                    fun hasText(view: View): Boolean =
                        view is TextView && view.text.toString() == expected.title ||
                            view is android.view.ViewGroup && (0 until view.childCount).any { hasText(view.getChildAt(it)) }
                    main { recycler.scrollToPosition(manager.getListPositionForFolderId(-animatedStableId)) }
                    await {
                        val holder = recycler.findViewHolderForAdapterPosition(manager.getListPositionForFolderId(-animatedStableId))
                        holder != null && hasText(holder.itemView)
                    }
                    return
                }
                main { recycler.scrollToPosition(manager.getListPositionForThingId(expected.id)) }
                val frame = expected.thingCardAppearance.sources.getValue(videoKey).videoFrameMs
                val loadPrefix = "${video.path}:${video.length()}:${video.lastModified()}:$frame:"
                await(15000) {
                    val position = manager.getListPositionForThingId(expected.id)
                    val holder = recycler.findViewHolderForAdapterPosition(position) as? BaseThingsAdapter.BaseThingViewHolder
                    holder != null && holder.tvTitle?.text.toString() == expected.getTitleToDisplay() &&
                        holder.tvContent?.text.toString() == expected.content &&
                        holder.ivImageAttachment?.getTag(R.id.tag_thing_card_image_load_key)?.toString()?.startsWith(loadPrefix) == true &&
                        holder.ivImageAttachment.drawable != null && holder.pbLoading?.visibility != View.VISIBLE &&
                        (expected.type != Thing.REMINDER || holder.rlReminder?.visibility == View.VISIBLE)
                }
                check(VideoCoverPreviewManager.getReadyPreview(targetContext, video.path, frame) == previews.getValue(frame!!))
            }
            assertCard(original)
            val labels = listOf("更新后直接返回", "更换封面后返回", "更新后撤销再返回", "更新后撤销重做再返回", "更换记事类型后返回",
                "类型筛选移出", "类型筛选移入", "类型变化撤销后仍在列表", "类型变化撤销重做后移出",
                "记录筛选撤销重做后移出", "记录筛选移入", "记录筛选类型变化后撤销",
                "搜索不再匹配后移出", "搜索重新匹配后移入", "连续接收合并为一次更新",
                "文件夹预览随内容更新", "类型筛选移出文件夹投影", "类型筛选恢复文件夹投影")
            for ((step, label) in labels.withIndex()) {
                if (step < (args.getString("home_start")?.toIntOrNull() ?: 0)) continue
                if (step == 15) {
                    val folder = next("thing_folders")
                    db.insertOrThrow("thing_folders", null, ContentValues().apply {
                        put("id", folder); put("title", "首页更新临时文件夹"); put("state", Thing.UNDERWAY)
                        put("color", Color.BLUE); put("location", 1000000001L + original.id)
                        put("create_time", epoch); put("update_time", epoch)
                        put("card_presentation", ThingFolderCardPresentation(mode = ThingFolderCardPresentation.MODE_THUMBNAILS).toJson())
                    }); folders += folder
                    db.update("things", ContentValues().apply { put("folder_id", folder) }, "id=?", arrayOf("${original.id}"))
                    animatedStableId = -folder
                }
                val before = read(original.id)
                main {
                    val mask = when (step) {
                        in 5..8 -> ThingWidgetInfo.TYPE_FILTER_REMINDER
                        in 9..11, 16, 17 -> ThingWidgetInfo.TYPE_FILTER_NOTE
                        else -> ThingWidgetInfo.TYPE_FILTER_ALL
                    }
                    manager.setTypeFilterMask(mask, false)
                    if (step == 12 && !App.isSearching || step == 14 && App.isSearching) {
                        invoke(home, "toggleSearching", false)
                    }
                    if (step in 12..13) field<EditText>(home, "mEtSearch").setText("首页刷新验证")
                    invoke(home, "justNotifyAll", false)
                }
                waitForIdleSync()
                val beforeVisible = main { manager.getThingListEntries().orEmpty().any { it.stableId == animatedStableId } }
                val beforeIds = main { manager.getThingListEntries().orEmpty().map { it.stableId } }
                if (beforeVisible) assertCard(before)
                val monitor = addMonitor(DetailActivity::class.java.name, null, false)
                main {
                    home.startActivityForResult(DetailActivity.getOpenIntentForUpdate(home, ThingsActivity.TAG,
                        original.id, manager.getPosition(original.id), manager.getListPositionForThingId(original.id),
                        manager.getProjection().key()), Def.Communication.REQUEST_ACTIVITY_DETAIL)
                }
                val detail = checkNotNull(monitor.waitForActivityWithTimeout(10000)) as DetailActivity
                removeMonitor(monitor); current = detail
                await { detail.findViewById<Toolbar>(R.id.actionbar)?.menu?.findItem(R.id.act_tap_update) != null }
                val receiver = begin(detail)
                var incoming = Thing(before).apply {
                    title = "首页刷新验证 $step"; content = "首页应显示：$label"
                    attachment = AttachmentHelper.toAttachmentStr(listOf(pictureKey, videoKey), emptyList())
                    thingCardAppearance = thingCardAppearance.copy(sources = mapOf(videoKey to
                        ThingCardAppearance.SourceAppearance(videoFrameMs = if (step == 1) 600 else 0)))
                    if (step in 4..11 || step in 16..17) type = if (before.type == Thing.NOTE) Thing.REMINDER else Thing.NOTE
                    if (step == 12) title = "不再命中搜索"; if (step == 12) content = "关键词已经移除"
                }
                val host = main { field<TapDetailReceiveController.Host>(receiver, "host") }
                val local = main { host.existingAttachments() }
                val content = ThingShareContent(listOf(ThingShareContent.ThingNode(incoming,
                    reminder = if (incoming.type == Thing.REMINDER) Reminder(incoming.id, future) else null)))
                main { receiver.onTouched(TapIncomingOffer("首页返回验证", 1, intArrayOf(incoming.getColor()))) }
                val received = transfer(content, local, receiver)
                check(received.transferredBytes == 0L)
                val notificationsBefore = main { allChanges to itemChanges }
                val structureBefore = main { insertedItems to removedItems }
                val animationsBefore = main { changeAnimations to animatedFrames }
                main { receiver.onReceived(TapReceivedPayload.Files(received)) }
                if (before.type != Thing.NOTE || incoming.type != Thing.NOTE) {
                    await { detail.supportFragmentManager.findFragmentByTag(TapUpdateSettingsDialog.TAG)?.view != null }
                    main { detail.supportFragmentManager.findFragmentByTag(TapUpdateSettingsDialog.TAG)!!.requireView()
                        .findViewById<View>(R.id.tap_update_replace).performClick() }
                }
                await(30000) { !receiver.isReceiving }
                check(read(original.id).content == incoming.content)
                if (step == 14) {
                    incoming = Thing(incoming).apply { this.content = "连续接收后的最终正文" }
                    val again = begin(detail)
                    val next = transfer(ThingShareContent(listOf(ThingShareContent.ThingNode(incoming))), local, again)
                    check(next.transferredBytes == 0L)
                    main { again.onReceived(TapReceivedPayload.Files(next)) }
                    await(30000) { !again.isReceiving }
                }
                val undo = step in setOf(2, 3, 7, 8, 9, 11)
                val redo = step in setOf(3, 8, 9)
                if (undo) menu(detail, R.id.act_undo)
                if (redo) menu(detail, R.id.act_redo)
                val expected = if (undo && !redo) before else incoming
                check(read(original.id).content == expected.content)
                val inputBlocked = main {
                    if (field<Any?>(home, "mReceivedContentUpdate") == null || !beforeVisible) false
                    else {
                        val listener = home.OnThingTouchedListener()
                        val mode = field<com.ywwynm.everythingdone.managers.ModeManager>(home, "mModeManager")
                        val modeBefore = mode.getCurrentMode()
                        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
                        try { check(listener.onItemTouch(null, event)) } finally { event.recycle() }
                        listener.onItemClick(null, beforeIds.indexOf(animatedStableId))
                        check(listener.onItemLongClick(null, beforeIds.indexOf(animatedStableId)))
                        check(mode.getCurrentMode() == modeBefore)
                        true
                    }
                }
                main { invoke(detail, "returnToThingsActivity", false, false) }
                await { detail.isDestroyed && home.hasWindowFocus() }; current = null
                try {
                    await { allChanges > notificationsBefore.first || itemChanges > notificationsBefore.second ||
                        insertedItems > structureBefore.first || removedItems > structureBefore.second }
                } catch (failure: Throwable) {
                    throw IllegalStateException("$label：没有通知，之前可见=$beforeVisible，原列表=$beforeIds，" +
                        "最终列表=${main { manager.getThingListEntries().orEmpty().map { it.stableId } }}，" +
                        "原标题=${before.title}，搜索=${main { field<EditText>(home, "mEtSearch").text }}，" +
                        "待发布=${main { field<Any?>(home, "mReceivedContentUpdate") }}", failure)
                }
                val afterVisible = main { manager.getThingListEntries().orEmpty().any { it.stableId == animatedStableId } }
                if (afterVisible) assertCard(expected)
                waitForIdleSync()
                await { recycler.itemAnimator?.isRunning != true }
                val expectedChanges = if (beforeVisible && afterVisible) 1 else 0
                check(main { allChanges == notificationsBefore.first && itemChanges == notificationsBefore.second + expectedChanges }) {
                    "$label：应只更新一张卡片，实际全量=${allChanges - notificationsBefore.first}，单项=${itemChanges - notificationsBefore.second}"
                }
                check(main { insertedItems - structureBefore.first == if (!beforeVisible && afterVisible) 1 else 0 })
                check(main { removedItems - structureBefore.second == if (beforeVisible && !afterVisible) 1 else 0 })
                check(expectedChanges == 0 || main { changeAnimations > animationsBefore.first && animatedFrames > animationsBefore.second }) {
                    "$label：未观察到实际卡片更新动画"
                }
                main {
                    for (index in 0 until recycler.childCount) {
                        val child = recycler.getChildAt(index)
                        val position = recycler.getChildAdapterPosition(child)
                        val bound = child.getTag(R.id.tag_thing_card_bound_stable_id) as? Long ?: continue
                        if (position >= 0) check(manager.getThingListEntry(position)?.stableId == bound) { "$label：通知后卡片位置错乱" }
                    }
                }
                check(read(original.id).attachment == expected.attachment)
                homeMeasurements.put(JSONObject().put("case", label)
                    .put("all_changes", main { allChanges - notificationsBefore.first })
                    .put("item_changes", main { itemChanges - notificationsBefore.second })
                    .put("inserted_items", main { insertedItems - structureBefore.first })
                    .put("removed_items", main { removedItems - structureBefore.second })
                    .put("transition_input_blocked", inputBlocked)
                    .put("change_animations", main { changeAnimations - animationsBefore.first })
                    .put("animated_frames", main { animatedFrames - animationsBefore.second })
                    .put("bound_content", expected.content).put("frame_ms", expected.thingCardAppearance.sources.getValue(videoKey).videoFrameMs)
                    .put("attachment_bytes", received.transferredBytes))
                cases.put("$label：首页收到刷新通知，实际卡片正文、封面与类型正确；视频复用且传输 0 字节")
            }
            for ((frame, file) in previews) {
                check(file.lastModified() == previewStamps.getValue(frame).first &&
                    TapFileTransfer.identify(0, file) == previewStamps.getValue(frame).second)
            }
            uiAutomation.takeScreenshot()?.let { bitmap ->
                File(targetContext.getExternalFilesDir(null), "tap-home-result.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        } finally {
            main {
                current?.finish(); current = null
                list?.adapter?.unregisterAdapterDataObserver(observer)
                if (App.isSearching) invoke(home, "toggleSearching", false)
                home.finish()
                manager.navigateToFolderPathIndex(-1, false)
                previousProjection.currentFolderId?.let { manager.openFolderPath(it, loadThingsNow = false) }
                manager.setTypeFilterMask(previousProjection.typeFilterMask, false)
                App.getApp()!!.setStatus(previousProjection.status, true)
            }
            await { home.isDestroyed }
        }
    }

    /** 通过生产详情与菜单验证：接收让位不能删掉顶部附件的一段像素。 */
    private fun mediaEntryCases() {
        var failures = 0
        for (video in listOf(false, true)) {
            val source = File(dir, if (video) "entry.mp4" else "entry.png")
            if (video) targetContext.assets.open("detail-regression/video.mp4").use { input ->
                source.outputStream().use(input::copyTo)
            } else {
                val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                val colors = intArrayOf(0xFFE85D5D.toInt(), 0xFFFFD166.toInt(), 0xFF57CC99.toInt(), 0xFF48BFE3.toInt())
                repeat(4) { row ->
                    paint.color = colors[row]; canvas.drawRect(0f, row * 150f, 800f, (row + 1) * 150f, paint)
                    paint.color = Color.BLACK; paint.textSize = 54f
                    canvas.drawText(if (row == 0) "TOP - 1" else "ROW - ${row + 1}", 60f, row * 150f + 98, paint)
                }
                source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            files += source
            val original = fixture().apply {
                val key = "${if (video) AttachmentHelper.VIDEO else AttachmentHelper.IMAGE}${source.path}"
                attachment = AttachmentHelper.toAttachmentStr(listOf(key), emptyList())
                detailAttachmentMediaAppearance = DetailAttachmentMediaAppearance().withSource(key,
                    DetailAttachmentMediaAppearance.SourceAppearance(fullSpanEnabled = true))
            }
            ThingFileUpdate.write(targetContext, original, null)
            val activity = open(original.id)
            val list = activity.findViewById<RecyclerView>(R.id.sv_detail)
            await { list.findViewById<View>(R.id.iv_image_attachment)?.height?.let { it > 0 } == true &&
                list.findViewById<View>(R.id.pb_image_attachment)?.visibility != View.VISIBLE }
            val beforeHeight = main { list.findViewById<View>(R.id.iv_image_attachment).height }
            SystemClock.sleep(900)
            repeat(2) { attempt ->
                val receiver = begin(activity)
                await { !field<TapReceivePull>(receiver, "pull").isMoving && list.translationY > 0 }
                SystemClock.sleep(900)
                val valid = main {
                    val media = list.findViewById<View>(R.id.iv_image_attachment)
                    val mediaRect = Rect(0, 0, media.width, media.height)
                    list.offsetDescendantRectToMyCoords(media, mediaRect)
                    val mediaAt = IntArray(2).also(media::getLocationOnScreen)
                    val panel = activity.findViewById<View>(R.id.ll_tap_receive)
                    val panelAt = IntArray(2).also(panel::getLocationOnScreen)
                    val clippedTop = maxOf(0, (list.clipBounds?.top ?: 0) - maxOf(0, mediaRect.top))
                    val clearOfPanel = mediaAt[1] >= panelAt[1] + panel.height
                    val intact = clippedTop == 0 && clearOfPanel && media.height == beforeHeight
                    mediaEntryMeasurements.put(JSONObject().put("kind", if (video) "video" else "image")
                        .put("attempt", attempt).put("media_top", mediaAt[1]).put("media_height", media.height)
                        .put("panel_bottom", panelAt[1] + panel.height).put("translation", list.translationY.toDouble())
                        .put("clipped_top", clippedTop).put("intact", intact))
                    intact
                }
                if (!valid) failures++
                main { receiver.exit() }
                await { list.translationY == 0f }
                check(main { list.clipBounds == null })
                SystemClock.sleep(600)
            }
            check(read(original.id).attachment == original.attachment)
            main { activity.finish() }; await { activity.isDestroyed }; current = null
        }
        check(failures == 0) { "接收时顶部附件裁切或与接收控件重叠：$mediaEntryMeasurements" }
        cases.put("静态图片和视频封面两次进入／退出接收，顶部完整、原尺寸保留、控件不重叠且退出复位")
    }

    private fun incoming(type: Int = Thing.NOTE, video: Boolean = false): PendingThing {
        val thing = Thing(0, type, Thing.UNDERWAY, Color.rgb(130, 42, 85), "接收到的旅行清单", "新内容较短。", "", 0, epoch, epoch, 0)
        thing.setBackground(ThingBackground.gradient(Color.rgb(48, 56, 126), Color.rgb(164, 48, 89), ThingBackground.Orientation.LT_RB))
        thing.thingCardSpanMode = Thing.THING_CARD_SPAN_FULL
        val attachments = if (video) listOf(File(dir, "incoming.mp4").also { file ->
            targetContext.assets.open("detail-regression/video.mp4").use { input -> file.outputStream().use(input::copyTo) }
            thing.content = CheckListHelper.toCheckListStr((1..24).map {
                CheckListHelper.makeItem(if (it > 16) '1' else '0', 1, "旅行清单第 $it 项")
            })
            thing.attachment = AttachmentHelper.toAttachmentStr(listOf("1${file.path}"), emptyList())
            thing.detailAttachmentMediaAppearance = DetailAttachmentMediaAppearance().withSource(
                "attachments/0/clip.mp4", DetailAttachmentMediaAppearance.SourceAppearance(fullSpanEnabled = true))
        }) else emptyList()
        return PendingThing(thing, if (Thing.isReminderType(type)) Reminder(0, future) else null,
            if (type == Thing.HABIT) Habit(0, java.util.Calendar.DATE, 0, "12:30", "", "", epoch, 0) else null,
            attachments, if (video) listOf(AttachmentHelper.VIDEO) else emptyList(),
            if (video) listOf("attachments/0/clip.mp4") else emptyList())
    }

    private fun storageCases() {
        val old = fixture(Thing.HABIT, Thing.FINISHED, true)
        val folder = next("thing_folders")
        db.insertOrThrow("thing_folders", null, ContentValues().apply {
            put("id", folder); put("title", "更新测试临时私密文件夹"); put("state", Thing.UNDERWAY)
            put("color", Color.BLUE); put("location", folder); put("is_private", 1); put("create_time", epoch); put("update_time", epoch)
        }); folders += folder
        db.update("things", ContentValues().apply { put("folder_id", folder) }, "id=?", arrayOf("${old.id}"))
        val target = read(old.id)
        val definition = incoming(Thing.HABIT)
        ThingFileUpdate.write(targetContext, target, ThingFileUpdate.receivedAlarms(definition, target.id))
        db.update("habits", ContentValues().apply { put("record", "10"); put("reminded_times", 2); put("interval_info", "${epoch},") }, "id=?", arrayOf("${target.id}"))
        val reminderId = ThingFileUpdate.captureAlarms(targetContext, target.id).rows.getValue("habit_reminders").single().getAsLong("id")
        repeat(4) { kind ->
            db.insertOrThrow("habit_records", null, ContentValues().apply {
                put("id", next("habit_records")); put("habit_id", target.id); put("habit_reminder_id", reminderId)
                put("record_time", epoch + kind); put("record_year", 2026); put("record_month", 6); put("record_week", 23); put("record_day", 1); put("type", kind)
            })
        }
        val original = ThingFileUpdate.captureAlarms(targetContext, target.id)
        val payload = incoming(Thing.REMINDER, true)
        val prepared = ThingFileUpdate.prepare(payload); files += prepared.files
        val merged = ThingFileUpdate.merge(target, prepared.content, ThingFileUpdate.Settings.KEEP_CURRENT)
        val kept = ThingFileUpdate.write(targetContext, merged, null).after
        assertSameIdentity(target, kept)
        check(kept.type == Thing.HABIT && kept.isPrivate() && kept.content == payload.thing.content)
        check(kept.thingCardSpanMode == Thing.THING_CARD_SPAN_FULL)
        check(kept.detailAttachmentMediaAppearance.sources.keys.single().contains(prepared.files.single().path))
        check(rows(original) == rows(ThingFileUpdate.captureAlarms(targetContext, target.id)))
        cases.put("保留身份、私密、文件夹、置顶、完成状态、外观、所有设置和四种打卡记录")

        val replaced = ThingFileUpdate.write(targetContext,
            ThingFileUpdate.merge(kept, prepared.content, ThingFileUpdate.Settings.USE_RECEIVED),
            ThingFileUpdate.receivedAlarms(payload, target.id))
        prepared.markCommitted(); prepared.discard(); check(prepared.files.all { it.exists() })
        ThingFileUpdate.synchronizeAlarms(targetContext, replaced)
        check(replaced.after.type == Thing.REMINDER)
        check(ThingFileUpdate.captureAlarms(targetContext, target.id).rows.getValue("habit_records").isEmpty())
        val neighbour = fixture(Thing.HABIT, Thing.FINISHED)
        ThingFileUpdate.write(targetContext, neighbour, ThingFileUpdate.receivedAlarms(definition, neighbour.id))
        val neighbourRows = rows(ThingFileUpdate.captureAlarms(targetContext, neighbour.id))
        val undo = ThingFileUpdate.write(targetContext, target, original)
        ThingFileUpdate.synchronizeAlarms(targetContext, undo)
        val restored = ThingFileUpdate.captureAlarms(targetContext, target.id)
        check(rows(original, true) == rows(restored, true))
        check(restored.rows.getValue("habit_records").all { it.getAsLong("habit_reminder_id") == restored.rows.getValue("habit_reminders").single().getAsLong("id") })
        check(neighbourRows == rows(ThingFileUpdate.captureAlarms(targetContext, neighbour.id)))
        ThingFileUpdate.write(targetContext, replaced.after, replaced.alarms)
        cases.put("采用来件设置、撤销恢复完整记录、主键冲突重映射、重做复用决定、已提交附件不误删")

        val newHabit = incoming(Thing.HABIT)
        ThingFileUpdate.write(targetContext, ThingFileUpdate.merge(target, newHabit.thing, ThingFileUpdate.Settings.USE_RECEIVED),
            ThingFileUpdate.receivedAlarms(newHabit, target.id))
        val freshHabit = ThingFileUpdate.captureAlarms(targetContext, target.id)
        check(freshHabit.rows.getValue("habit_records").isEmpty())
        check(freshHabit.rows.getValue("habits").single().getAsString("record").isEmpty())
        check(freshHabit.rows.getValue("habits").single().getAsInteger("reminded_times") == 0)
        val previous = read(target.id)
        check(runCatching { ThingFileUpdate.write(targetContext, Thing(previous).apply { state = Thing.UNDERWAY }, null) }.isFailure)
        check(read(target.id).title == previous.title)
        cases.put("来件习惯从零开始，状态冲突时事务不修改原行")
    }

    private fun readerCases() {
        val one = incoming().thing
        val root = JSONObject().put(ThingFile.KEY_FORMAT, ThingFile.FORMAT).put(ThingFile.KEY_VERSION, 2)
        val item = JSONObject().put("type", Thing.NOTE).put("title", one.title).put("content", one.content)
        fun parse(things: JSONArray, folders: JSONArray? = null): ThingFileReader.Result.Ok {
            root.put("things", things); if (folders != null) root.put("folders", folders) else root.remove("folders")
            val file = File(dir, "manifest.edthing")
            ZipOutputStream(file.outputStream()).use { zip -> zip.putNextEntry(ZipEntry("things.json")); zip.write(root.toString().toByteArray()); zip.closeEntry() }
            return (file.inputStream().use { ThingFileReader.read(targetContext, it) } as ThingFileReader.Result.Ok).also { ThingFileReader.cleanUp(it.tempDir) }
        }
        check(parse(JSONArray().put(item)).singleStandaloneThing)
        check(!parse(JSONArray().put(item).put(JSONObject().put("type", 999))).singleStandaloneThing)
        check(!parse(JSONArray().put(item), JSONArray().put(JSONObject().put("key", 0).put("title", "文件夹"))).singleStandaloneThing)
        cases.put("原始来件单条限制，包括过滤后剩单条和空文件夹")
    }

    private fun detailCases() {
        val old = fixture(private = true)
        val activity = open(old.id)
        main { field<EditText>(activity, "mEtTitle").append("【未保存草稿】") }
        val draft = main { invoke(activity, "buildShareSnapshot") as Thing }
        val prepared = ThingFileUpdate.prepare(incoming(Thing.REMINDER, true)); files += prepared.files
        // 实际分享文件的附件键由 writer 统一映射。
        val source = ThingShareContent(listOf(ThingShareContent.ThingNode(prepared.content, reminder = prepared.pending.reminder)))
        val file = File(dir, "received.edthing")
        check(ThingFileWriter.write(targetContext, source, file) is ThingFileWriter.Result.Ok)
        val toolbar = activity.findViewById<Toolbar>(R.id.actionbar)
        main { toolbar.showOverflowMenu() }
        SystemClock.sleep(1200)
        main { toolbar.hideOverflowMenu(); activity.onOptionsItemSelected(toolbar.menu.findItem(R.id.act_tap_update)) }
        await { field<TapDetailReceiveController?>(activity, "tapUpdateReceiver")?.isReceiving == true }
        val receiver = main { field<TapDetailReceiveController>(activity, "tapUpdateReceiver") }
        main { field<TapReceiverTransport?>(receiver, "transport")?.stop() }
        SystemClock.sleep(1200)
        main {
            check(activity.findViewById<View>(R.id.sv_detail).translationY > 0f)
            check(activity.findViewById<TextView>(R.id.tv_tap_receive).text == activity.getString(R.string.tap_receive_exit))
            check(activity.findViewById<TextView>(R.id.tv_tap_receive_hint).text == activity.getString(R.string.tap_receive_hint))
            check(activity.findViewById<TextView>(R.id.tv_tap_receive_hint).currentTextColor == field<EditText>(activity, "mEtTitle").currentTextColor)
            val list = activity.findViewById<View>(R.id.sv_detail)
            check(list.clipBounds!!.bottom + list.translationY <= list.height + 1f)
            check(field<View>(receiver, "stage").height == activity.findViewById<View>(R.id.fl_root_detail).height)
        }
        main { receiver.onTouched(TapIncomingOffer("另一台测试设备", 1, intArrayOf(prepared.content.getColor()), floatArrayOf(1f), booleanArrayOf(false))) }
        repeat(8) { step ->
            main { if (step == 0) receiver.onConnected(file.length()); receiver.onProgress(file.length() * (step + 1) / 8, file.length()) }
            SystemClock.sleep(180)
        }
        check(main { activity.findViewById<TextView>(R.id.tv_tap_receive_hint).text.contains("%") })
        main { receiver.onReceived(file) }
        await { activity.supportFragmentManager.findFragmentByTag(TapUpdateSettingsDialog.TAG)?.view != null }
        SystemClock.sleep(1200)
        main { activity.supportFragmentManager.findFragmentByTag(TapUpdateSettingsDialog.TAG)!!.requireView().findViewById<View>(R.id.tap_update_keep).performClick() }
        await(25000) { !receiver.isReceiving }
        check(read(old.id).getTitleToDisplay() == prepared.content.title)
        check(read(old.id).type == Thing.NOTE && read(old.id).isPrivate())
        check(main { (invoke(activity, "buildShareSnapshot") as Thing).content } == prepared.content.content)
        val attachment = read(old.id).attachment
        files += attachment.orEmpty().split(AttachmentHelper.SIGNAL).drop(1).filter { it.length > 1 }.map { File(it.substring(1)) }
        SystemClock.sleep(1500)
        check(main { activity.findViewById<View>(R.id.sv_detail).translationY == 0f })
        check(main { activity.findViewById<View>(R.id.sv_detail).clipBounds == null })
        cases.put("接收入口紧接分享，共用首页下移、退出按钮和百分比，粒子舞台覆盖四周，完成后页面复位")
        menu(activity, R.id.act_undo)
        check(read(old.id).title == draft.title && read(old.id).content == draft.content)
        SystemClock.sleep(1200)
        menu(activity, R.id.act_redo)
        check(read(old.id).attachment == attachment)
        SystemClock.sleep(1200)
        cases.put("真实详情菜单与覆盖动画、选择保留、撤销恢复未保存草稿、重做视频和清单")

        val short = ThingFileUpdate.prepare(incoming(Thing.REMINDER)); files += short.files
        val shorterFile = File(dir, "shorter.edthing")
        check(ThingFileWriter.write(targetContext, ThingShareContent(listOf(ThingShareContent.ThingNode(
            short.content, reminder = short.pending.reminder))), shorterFile) is ThingFileWriter.Result.Ok)
        val shorterReceiver = begin(activity)
        main {
            shorterReceiver.onTouched(TapIncomingOffer("另一台测试设备", 1, intArrayOf(short.content.getColor()), floatArrayOf(1f), booleanArrayOf(false)))
            shorterReceiver.onConnected(shorterFile.length()); shorterReceiver.onProgress(shorterFile.length(), shorterFile.length())
            shorterReceiver.onReceived(shorterFile)
        }
        await { activity.supportFragmentManager.findFragmentByTag(TapUpdateSettingsDialog.TAG)?.view != null }
        SystemClock.sleep(600)
        main { activity.supportFragmentManager.findFragmentByTag(TapUpdateSettingsDialog.TAG)!!.requireView().findViewById<View>(R.id.tap_update_replace).performClick() }
        await(25000) { !shorterReceiver.isReceiving }
        SystemClock.sleep(1200)
        check(read(old.id).type == Thing.REMINDER && read(old.id).attachment!!.isEmpty())
        val historyFiles = attachment.orEmpty().split(AttachmentHelper.SIGNAL).drop(1).map { File(it.substring(1)) }
        main { App.getApp()!!.addAttachmentsToDeleteFile(historyFiles.map { it.path }); App.getApp()!!.deleteAttachmentFiles() }
        field<java.util.concurrent.ExecutorService>(App.getApp()!!, "mExecutor").submit {}.get()
        check(historyFiles.all { it.exists() })
        menu(activity, R.id.act_undo)
        check(read(old.id).type == Thing.NOTE && read(old.id).attachment == attachment)
        menu(activity, R.id.act_redo)
        check(read(old.id).type == Thing.REMINDER)
        cases.put("详情内类型与附件多次替换、撤销／重做与已有编辑历史共同工作")
        main { activity.finish() }; await { activity.isDestroyed }; current = null
        main { App.getApp()!!.deleteAttachmentFiles() }
        field<java.util.concurrent.ExecutorService>(App.getApp()!!, "mExecutor").submit {}.get()
        check(historyFiles.none { it.exists() })
        cases.put("后台清理保留历史引用附件，退出详情后回收未使用附件")

        val finished = fixture(state = Thing.FINISHED)
        val completed = open(finished.id)
        val replacement = ThingFileUpdate.prepare(incoming(Thing.REMINDER))
        main { invoke(completed, "applyReceivedContent", replacement, ThingFileUpdate.Settings.USE_RECEIVED) }
        assertSameIdentity(finished, read(finished.id))
        menu(completed, R.id.act_undo); check(read(finished.id).type == Thing.NOTE)
        menu(completed, R.id.act_redo); check(read(finished.id).type == Thing.REMINDER)
        cases.put("已完成详情提供入口与撤销／重做，原完成时间不变")
        main { completed.finish() }; waitForIdleSync(); current = null
    }

    private fun begin(activity: DetailActivity): TapDetailReceiveController {
        menu(activity, R.id.act_tap_update)
        await { field<TapDetailReceiveController?>(activity, "tapUpdateReceiver")?.isReceiving == true }
        return main { field<TapDetailReceiveController>(activity, "tapUpdateReceiver").also {
            field<TapReceiverTransport?>(it, "transport")?.stop()
        } }
    }

    /** 复查专用：通过生产传输监听器与详情编辑入口检查跨轮回调和未保存设置。 */
    private fun reviewCases() {
        val failures = mutableListOf<String>()
        fun verify(name: String, block: () -> Unit) {
            try { block(); cases.put(name) }
            catch (e: Throwable) { failures += "$name：${e.stackTraceToString()}" }
            finally {
                val activity = current
                main { activity?.finish() }
                if (activity != null) await { activity.isDestroyed }
                current = null
            }
        }
        verify("退出再进入接收后，旧监听器的迟到文件不能进入新一轮") {
            val old = fixture()
            val activity = open(old.id)
            menu(activity, R.id.act_tap_update)
            await { field<TapDetailReceiveController?>(activity, "tapUpdateReceiver")?.isReceiving == true }
            val receiver = main { field<TapDetailReceiveController>(activity, "tapUpdateReceiver") }
            val lateListener = main {
                val transport = field<TapReceiverTransport>(receiver, "transport")
                field<TapReceiverTransport.Listener>(transport, "listener").also { receiver.exit(immediate = true) }
            }
            menu(activity, R.id.act_tap_update)
            await { receiver.isReceiving }
            val currentListener = main {
                val transport = field<TapReceiverTransport>(receiver, "transport")
                field<TapReceiverTransport.Listener>(transport, "listener").also { transport.stop() }
            }
            val pending = incoming(Thing.REMINDER)
            val late = File(dir, "previous-session.edthing")
            check(ThingFileWriter.write(targetContext, ThingShareContent(listOf(
                ThingShareContent.ThingNode(pending.thing, reminder = pending.reminder))), late) is ThingFileWriter.Result.Ok)
            main { lateListener.onReceived(late) }
            field<java.util.concurrent.ExecutorService>(receiver, "worker").submit {}.get()
            waitForIdleSync()
            main {
                activity.supportFragmentManager.executePendingTransactions()
                check(activity.supportFragmentManager.findFragmentByTag(TapUpdateSettingsDialog.TAG) == null) {
                    "上一轮的来件弹出了新一轮的设置选择框"
                }
                check(field<ThingFileUpdate.Prepared?>(receiver, "prepared") == null)
                check(receiver.isReceiving)
            }
            check(!late.exists())
            check(read(old.id).title == old.title && read(old.id).content == old.content)
            val fresh = File(dir, "current-session.edthing")
            val content = incoming().thing
            check(ThingFileWriter.write(targetContext, listOf(content), fresh) is ThingFileWriter.Result.Ok)
            main {
                currentListener.onTouched(TapIncomingOffer("当前接收测试", 1, intArrayOf(Color.BLUE)))
                currentListener.onReceived(fresh)
            }
            await(25000) { !receiver.isReceiving }
            check(read(old.id).content == content.content)
            menu(activity, R.id.act_undo)
            check(read(old.id).content == old.content)
        }
        verify("保留未保存的习惯设置，更新、撤销和重做后的类型提示与参数一致") {
            val old = fixture()
            val activity = open(old.id)
            main {
                activity.rhParams = ReminderHabitParams().apply {
                    habitType = java.util.Calendar.DATE; habitDetail = "12:30"
                }
                activity.cbQuickRemind!!.isChecked = true
                activity.tvQuickRemind!!.text = activity.rhParams.getDateTimeStr()
                activity.updateBackButton()
            }
            val replacement = ThingFileUpdate.prepare(incoming())
            main { invoke(activity, "applyReceivedContent", replacement, ThingFileUpdate.Settings.KEEP_CURRENT) }
            fun assertDraft() = main {
                check(activity.rhParams.habitDetail == "12:30" && activity.cbQuickRemind!!.isChecked)
                check(invoke(activity, "getThingTypeAfter") == Thing.HABIT)
                check(field<View>(activity, "mIbBack").contentDescription == activity.getString(R.string.cd_back_habit)) {
                    "习惯草稿保留了，但返回图标仍显示其它记事类型"
                }
            }
            assertDraft()
            menu(activity, R.id.act_undo); assertDraft()
            menu(activity, R.id.act_redo); assertDraft()
            main { invoke(activity, "returnToThingsActivity", false, false) }
            await { activity.isDestroyed }
            check(read(old.id).type == Thing.HABIT)
            check(ThingFileUpdate.captureAlarms(targetContext, old.id).rows.getValue("habits").single().getAsString("detail") == "12:30")
        }
        verify("32 种类型与设置选择组合：旧编辑历史、接收撤销／重做、退出保存一致") {
            val types = listOf(Thing.NOTE, Thing.REMINDER, Thing.GOAL, Thing.HABIT)
            for (oldType in types) for (newType in types) for (choice in ThingFileUpdate.Settings.entries) {
                val original = fixture(type = oldType, private = oldType % 2 == 0)
                val oldSettings = incoming(oldType)
                val initial = ThingFileUpdate.write(targetContext, original,
                    ThingFileUpdate.receivedAlarms(oldSettings, original.id))
                main { ThingFileUpdate.synchronizeAlarms(targetContext, initial) }
                val oldAlarms = ThingFileUpdate.captureAlarms(targetContext, original.id)
                val activity = open(original.id)
                main { field<EditText>(activity, "mEtTitle").append("（接收前编辑）") }
                val draft = main { invoke(activity, "buildShareSnapshot") as Thing }
                val prepared = ThingFileUpdate.prepare(incoming(newType))
                main { invoke(activity, "applyReceivedContent", prepared, choice) }
                val expectedType = if (choice == ThingFileUpdate.Settings.KEEP_CURRENT) oldType else newType
                val after = read(original.id)
                val afterAlarms = ThingFileUpdate.captureAlarms(targetContext, original.id)
                check(after.type == expectedType && after.content == prepared.content.content)
                check(after.isPrivate() == original.isPrivate())
                assertSameIdentity(original, after)
                if (choice == ThingFileUpdate.Settings.KEEP_CURRENT) check(rows(afterAlarms) == rows(oldAlarms))
                menu(activity, R.id.act_undo)
                check(read(original.id).title == draft.title && read(original.id).type == oldType)
                check(rows(ThingFileUpdate.captureAlarms(targetContext, original.id), true) == rows(oldAlarms, true))
                menu(activity, R.id.act_undo)
                check(main { (invoke(activity, "buildShareSnapshot") as Thing).title } == original.title)
                menu(activity, R.id.act_redo)
                check(main { (invoke(activity, "buildShareSnapshot") as Thing).title } == draft.title)
                menu(activity, R.id.act_redo)
                check(read(original.id).type == expectedType)
                check(rows(ThingFileUpdate.captureAlarms(targetContext, original.id), true) == rows(afterAlarms, true))
                main { invoke(activity, "returnToThingsActivity", false, false) }
                await { activity.isDestroyed }; current = null
                check(read(original.id).type == expectedType && read(original.id).content == after.content) {
                    "退出保存改变了接收结果：$oldType → $newType / $choice"
                }
            }
        }
        check(failures.isEmpty()) { failures.joinToString("\n\n") }
    }

    private fun cancellationCases() {
        val old = fixture()
        val activity = open(old.id)
        // 键盘收起完成后才启用接收，不被窗口高度变化立即退出。
        main { com.ywwynm.everythingdone.utils.KeyboardUtil.showKeyboard(activity.window, field<EditText>(activity, "mEtTitle")) }
        await { androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true }
        val receiver = begin(activity)
        SystemClock.sleep(500)
        check(main { receiver.isReceiving })
        val multiple = File(dir, "multiple.edthing")
        check(ThingFileWriter.write(targetContext, listOf(incoming().thing, incoming().thing), multiple) is ThingFileWriter.Result.Ok)
        main { receiver.onTouched(TapIncomingOffer("无效批量测试", 2, intArrayOf(Color.BLUE, Color.RED), floatArrayOf(1f, 1f), booleanArrayOf(false, false))); receiver.onReceived(multiple) }
        await { !field<Boolean>(receiver, "busy") }
        check(main { receiver.isReceiving })
        check(read(old.id).title == old.title && read(old.id).content == old.content)
        cases.put("软键盘打开时进入接收，拒绝多条后仍等待且原记事不变")

        val cancelled = File(dir, "cancelled.edthing")
        check(ThingFileWriter.write(targetContext, listOf(incoming().thing), cancelled) is ThingFileWriter.Result.Ok)
        main { receiver.onTouched(TapIncomingOffer("取消测试", 1, intArrayOf(Color.BLUE), floatArrayOf(1f), booleanArrayOf(false))); receiver.onReceived(cancelled); receiver.exit() }
        field<java.util.concurrent.ExecutorService>(receiver, "worker").submit {}.get()
        waitForIdleSync()
        check(read(old.id).title == old.title && read(old.id).content == old.content)
        val late = File(dir, "late.edthing").apply { writeText("迟到文件") }
        main { receiver.onReceived(late) }; check(!late.exists())
        cases.put("解析中取消与退出后迟到来件不修改当前记事")

        val fast = File(dir, "fast.edthing")
        val fastContent = incoming().thing
        check(ThingFileWriter.write(targetContext, listOf(fastContent), fast) is ThingFileWriter.Result.Ok)
        val fastReceiver = begin(activity)
        main {
            fastReceiver.onTouched(TapIncomingOffer("快速来件测试", 1, intArrayOf(Color.BLUE), floatArrayOf(1f), booleanArrayOf(false)))
            fastReceiver.onReceived(fast)
        }
        await(25000) { !fastReceiver.isReceiving }
        check(read(old.id).content == fastContent.content)
        SystemClock.sleep(400)
        check(main { activity.findViewById<View>(R.id.sv_detail).translationY == 0f })
        menu(activity, R.id.act_undo)
        check(read(old.id).title == old.title && read(old.id).content == old.content)
        cases.put("普通记事快速来件等待进场位移结束后覆盖，无设置弹窗，完成后复位并可撤销")

        val dialogReceiver = begin(activity)
        val choice = File(dir, "choice.edthing")
        val received = incoming(Thing.REMINDER)
        check(ThingFileWriter.write(targetContext, ThingShareContent(listOf(ThingShareContent.ThingNode(received.thing, reminder = received.reminder))), choice) is ThingFileWriter.Result.Ok)
        main { dialogReceiver.onTouched(TapIncomingOffer("设置必选测试", 1, intArrayOf(Color.BLUE), floatArrayOf(1f), booleanArrayOf(false))); dialogReceiver.onReceived(choice) }
        await { activity.supportFragmentManager.findFragmentByTag(TapUpdateSettingsDialog.TAG)?.view != null }
        SystemClock.sleep(1000)
        val dialog = main { activity.supportFragmentManager.findFragmentByTag(TapUpdateSettingsDialog.TAG) as TapUpdateSettingsDialog }
        main {
            check(!dialog.isCancelable)
            val view = dialog.requireView()
            val description = view.findViewById<TextView>(R.id.tap_update_settings_description)
            val keep = view.findViewById<TextView>(R.id.tap_update_keep)
            val replace = view.findViewById<TextView>(R.id.tap_update_replace)
            check(description.bottom <= keep.top && keep.bottom <= replace.top)
            check(keep.lineCount == 1 && replace.lineCount == 1)
            check(keep.width == view.width && replace.width == view.width)
            val window = dialog.requireDialog() as ComponentDialog
            window.onBackPressedDispatcher.onBackPressed()
            val time = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                MotionEvent.obtain(time, time, action, -20f, -20f, 0).also {
                    window.dispatchTouchEvent(it); it.recycle()
                }
            }
        }
        SystemClock.sleep(500)
        check(main { dialog.dialog?.isShowing == true && dialogReceiver.isReceiving })
        check(read(old.id).title == old.title && read(old.id).content == old.content)
        main { activity.finish() }; await { activity.isDestroyed }; current = null
        check(read(old.id).title == old.title && read(old.id).content == old.content)
        cases.put("设置说明独立、选项单行整行，返回与点击外部不能取消；生命周期中断保留原内容")
    }

    private fun open(id: Long): DetailActivity = (startActivitySync(DetailActivity.getOpenIntentForUpdate(
        targetContext, "tap-update-regression", id, -1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DetailActivity).also {
        current = it; waitForIdleSync(); await { it.findViewById<Toolbar>(R.id.actionbar)?.menu?.findItem(R.id.act_tap_update) != null }
        main {
            val menu = it.findViewById<Toolbar>(R.id.actionbar).menu
            val shareIndex = (0 until menu.size()).first { index -> menu.getItem(index).itemId == R.id.act_share }
            check(menu.getItem(shareIndex + 1).itemId == R.id.act_tap_update)
        }
    }

    private fun menu(activity: DetailActivity, id: Int) {
        main { val toolbar = activity.findViewById<Toolbar>(R.id.actionbar); check(toolbar.menu.findItem(id).isEnabled); activity.onOptionsItemSelected(toolbar.menu.findItem(id)) }
        waitForIdleSync()
    }
    private fun assertSameIdentity(a: Thing, b: Thing) {
        check(a.id == b.id && a.folderId == b.folderId && a.location == b.location && a.createTime == b.createTime && a.state == b.state && a.finishTime == b.finishTime)
    }
    private fun rows(snapshot: ThingFileUpdate.Alarms, ignoreIds: Boolean = false): String = snapshot.rows.toSortedMap().map { (table, rows) ->
        table to rows.map { row -> row.keySet().sorted().filterNot { ignoreIds && table.startsWith("habit_") && (it == "id" || it == "habit_reminder_id") }.map { it to row.getAsString(it) } }
    }.toString()
    private fun read(id: Long): Thing = db.query("things", null, "id=?", arrayOf("$id"), null, null, null).use { check(it.moveToFirst()); Thing(it) }
    private fun next(table: String) = DatabaseUtils.longForQuery(db, "SELECT COALESCE(MAX(id),0)+1 FROM $table", null)
    private fun await(timeout: Long = 10000, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + timeout
        while (!main(condition)) { check(SystemClock.uptimeMillis() < until) { "等待界面超时" }; SystemClock.sleep(32) }
    }
    private fun <T> main(block: () -> T): T { var result: Result<T>? = null; runOnMainSync { result = runCatching(block) }; return result!!.getOrThrow() }
    @Suppress("UNCHECKED_CAST") private fun <T> field(target: Any, name: String): T = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target) as T
    private fun invoke(target: Any, name: String, vararg args: Any): Any? = target.javaClass.declaredMethods.first { it.name == name && it.parameterTypes.size == args.size }.apply { isAccessible = true }.invoke(target, *args)
}
