package com.ywwynm.everythingdone.views.particledismiss

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.animation.Transformation
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.ThingsActivity
import com.ywwynm.everythingdone.database.ThingDAO
import com.ywwynm.everythingdone.database.ThingFolderDAO
import com.ywwynm.everythingdone.fragments.AlertDialogFragment
import com.ywwynm.everythingdone.managers.ModeManager
import com.ywwynm.everythingdone.managers.ThingManager
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingFolder
import com.ywwynm.everythingdone.model.ThingListEntry
import com.ywwynm.everythingdone.utils.ThingAnimationPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 真实首页批量操作回归。只允许独立测试包；所有数据为本探针创建的虚构数据。 */
class SelectionBatchTransitionProbe : Instrumentation() {
    private var args = Bundle()
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); args = arguments ?: Bundle(); start() }
    private fun mainSync(block: () -> Unit) {
        var error: Throwable? = null
        runOnMainSync { try { block() } catch (failure: Throwable) { error = failure } }
        error?.let { throw it }
    }
    private fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(instance)
    private fun invoke(instance: Any, name: String, value: Int) = instance.javaClass
        .getDeclaredMethod(name, Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(instance, value)
    private fun waitUntil(label: String, timeout: Long = 15000, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < until) {
            var ok = false
            mainSync { ok = condition() }
            if (ok) return
            if (label == "首页列表") {
                val root = getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).rootInActiveWindow
                if (root?.findAccessibilityNodeInfosByText("Android 应用兼容性")?.isNotEmpty() == true) {
                    root.findAccessibilityNodeInfosByViewId("android:id/button2").firstOrNull()
                        ?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                }
                // OPPO 不允许 shell 授予运行时权限；在隔离测试包的标准授权窗口中完成本地媒体测试授权。
                val permissionPackage = root?.packageName?.toString().orEmpty()
                if (args.getString("audit_appearance") == "true" && permissionPackage.endsWith("permissioncontroller")) {
                    for (id in listOf("permission_allow_all_button", "permission_allow_button", "permission_allow_foreground_only_button")) {
                        root?.findAccessibilityNodeInfosByViewId("$permissionPackage:id/$id")?.firstOrNull()
                            ?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                    }
                }
            }
            SystemClock.sleep(24)
        }
        error("等待超时：$label")
    }

    override fun onStart() {
        require(targetContext.packageName in setOf("com.ywwynm.everythingdone.batchvalidation",
            "com.ywwynm.everythingdone.directionvalidation")) {
            "拒绝在用户应用中创建或变更测试记事"
        }
        val run = args.getString("run_id") ?: "batch"
        require(run.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        val folder = File(targetContext.getExternalFilesDir(null), "batch-transition/$run").apply { mkdirs() }
        val frames = JSONArray()
        val result = JSONObject().put("run", run).put("isolatedPackage", targetContext.packageName)
        var recording = false
        val automation = getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun screenshot(name: String) {
            automation.takeScreenshot()?.let { bitmap ->
                File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        try {
            val action = args.getString("action") ?: "finish"
            val toolbarEntry = args.getString("entry") == "toolbar"
            val requestedStyle = ParticleStyle.of((args.getString("style") ?: "drift").lowercase(java.util.Locale.ROOT))
            val observedCardStyles = mutableSetOf<ParticleStyle>()
            val observedDirections = mutableSetOf<Int>()
            val status = when { action == "restore" || toolbarEntry && action == "delete" -> Def.ThingStatus.FINISHED
                action == "untrash" || action == "forever" -> Def.ThingStatus.DELETED; else -> Def.ThingStatus.UNDERWAY }
            val after = when (action) { "finish" -> Thing.FINISHED; "delete" -> Thing.DELETED
                "forever" -> Thing.DELETED_FOREVER; else -> Thing.UNDERWAY }
            val inFolder = args.getString("scope") == "folder"
            val auditAppearance = args.getString("audit_appearance") == "true"
            val count = if (args.getString("selection") == "all") 6 else 32
            val fixtureIds = (1..count).map { 8_000_000L + it } +
                if (args.getString("selection") == "all") emptyList() else (1..3).map { 8_100_000L + it }
            mainSync {
                args.getString("appearance")?.let {
                    com.ywwynm.everythingdone.FrequentSettings.put(Def.Meta.KEY_FOLLOW_SYSTEM_DARK_MODE, false)
                    com.ywwynm.everythingdone.FrequentSettings.put(Def.Meta.KEY_FORCE_DARK_MODE, it == "night")
                    com.ywwynm.everythingdone.utils.AppearanceUtil.applyDefaultNightMode()
                }
                val prefs = targetContext.getSharedPreferences(Def.Meta.META_DATA_NAME, 0).edit()
                listOf(Def.Meta.KEY_1_0_4_TO_1_0_5, Def.Meta.KEY_1_2_7_TO_1_3_0,
                    Def.Meta.KEY_1_3_0_TO_1_3_1, Def.Meta.KEY_1_3_3_TO_1_3_4).forEach { prefs.putBoolean(it, true) }
                prefs.commit()
                File(targetContext.filesDir, Def.Meta.FEEDBACK_ERROR_FILE_NAME).delete()
                ThingAnimationPreferences.preferences(targetContext).edit()
                    .putInt(ThingAnimationPreferences.SWIPE_KEY, if (args.getString("particles") == "false") 0 else 1).commit()
                ParticleTuning.setStyle(targetContext, requestedStyle)
                ParticleCardSurfaceHandoff.seedForTests = 42017
                val dao = ThingDAO.getInstance(targetContext)!!
                // OPPO 限制 shell 清应用数据；由隔离测试应用正常重置自己的固定编号虚构数据。
                dao.getThingsCursor("type=${Thing.NOTE}")!!.use { cursor ->
                    while (cursor.moveToNext()) {
                        val existing = Thing(cursor)
                        check(existing.id in 8_000_001L..8_000_032L || existing.id in 8_100_001L..8_100_003L)
                    }
                }
                com.ywwynm.everythingdone.database.DBHelper(targetContext).use { helper ->
                    helper.writableDatabase.execSQL("DELETE FROM things WHERE id BETWEEN 8000001 AND 8000032 OR id BETWEEN 8100001 AND 8100003")
                    helper.writableDatabase.execSQL("DELETE FROM thing_folders WHERE id IN (9000000,9000001)")
                }
                val folderDao = ThingFolderDAO.getInstance(targetContext)!!
                if (inFolder) folderDao.create(ThingFolder(9_000_000, "回归文件夹", Color.rgb(34, 110, 180), 0))
                if (args.getString("selection") != "all") folderDao.create(ThingFolder(9_000_001,
                    if (inFolder) 9_000_000 else null, "保留的文件夹", Thing.UNDERWAY,
                    Color.rgb(65, 140, 115), 31).apply {
                    if (args.getString("complex") == "true") cardPresentation = cardPresentation.withMode(1).withSpanMode(1)
                })
                for (i in 1..count) {
                    val thing = Thing(8_000_000L + i, Thing.NOTE,
                        intArrayOf(0xff2c82a9.toInt(), 0xffd06b38.toInt(), 0xff64904b.toInt())[i % 3], i.toLong())
                    thing.title = "回归记事 $i"
                    thing.content = (1..(2 + i % 7)).joinToString("\n") { "虚构内容 $i · 第 $it 行" }
                    if (args.getString("scroll_warm") == "true") {
                        thing.title = if (i % 3 == 0) "短卡片 $i" else "滚动记事 $i"
                        thing.content = if (i % 3 == 0) "" else (1..(i % 5 + 1)).joinToString("\n") { "检查滚动 $i" }
                    }
                    thing.state = when (status) { Def.ThingStatus.FINISHED -> Thing.FINISHED
                        Def.ThingStatus.DELETED -> Thing.DELETED; else -> Thing.UNDERWAY }
                    if (toolbarEntry && count > 6 && i == count) thing.state =
                        if (status == Def.ThingStatus.UNDERWAY) Thing.FINISHED else Thing.UNDERWAY
                    thing.finishTime = 1_791_000_000_000L + i
                    thing.folderId = if (inFolder) 9_000_000 else null
                    if (args.getString("gradients") == "true" && i % 2 == 1) thing.setBackground(
                        com.ywwynm.everythingdone.model.ThingBackground.gradient(0xff367d91.toInt(), 0xff9856aa.toInt(),
                            com.ywwynm.everythingdone.model.ThingBackground.Orientation.L_R))
                    if (args.getString("complex") == "true" && i % 9 == 0) thing.thingCardSpanMode = Thing.THING_CARD_SPAN_FULL
                    if (auditAppearance) SelectionCardAppearanceAudit.decorate(targetContext, thing, i)
                    check(!dao.create(thing, false, false))
                }
                if (args.getString("selection") != "all") for (i in 1..3) {
                    check(!dao.create(Thing(8_100_000L + i, Thing.NOTE, 0xff996666.toInt(), i.toLong()).apply {
                        title = "文件夹内虚构记事 $i"; content = "验证计数、缩略图和混合卡片"; folderId = 9_000_001
                        if (toolbarEntry && inFolder && i == 3) folderId = null
                        state = when (status) { Def.ThingStatus.FINISHED -> Thing.FINISHED
                            Def.ThingStatus.DELETED -> Thing.DELETED; else -> Thing.UNDERWAY }
                    }, false, false))
                }
                if (toolbarEntry && action == "untrash") com.ywwynm.everythingdone.database.DBHelper(targetContext).use {
                    it.writableDatabase.execSQL("UPDATE things SET state_before_delete=CASE WHEN id % 2 = 0 THEN ${Thing.FINISHED} ELSE ${Thing.UNDERWAY} END WHERE id BETWEEN 8000001 AND 8100003")
                }
                App.getApp()!!.setStatus(status, true)
                if (auditAppearance && status == Def.ThingStatus.UNDERWAY) {
                    App.setDoingThingId(SelectionCardAppearanceAudit.DOING_ID)
                    com.ywwynm.everythingdone.FrequentSettings.put(Def.Meta.KEY_ONGOING_THING_ID, 8_000_022L)
                }
                if (args.getString("protect_doing") == "true") App.setDoingThingId(8_000_001L)
                if (auditAppearance) com.ywwynm.everythingdone.FrequentSettings.put(Def.Meta.KEY_TOGGLE_CLI_OTC,
                    args.getString("checklist_taps") != "false")
            }
            val intent = Intent(targetContext, ThingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(Def.Communication.KEY_STATUS, status)
            if (inFolder) intent.putExtra(Def.Communication.KEY_FOLDER_ID, 9_000_000L)
            val activity = startActivitySync(intent) as ThingsActivity
            lateinit var list: RecyclerView
            lateinit var mode: ModeManager
            lateinit var manager: ThingManager
            waitUntil("首页列表") {
                list = field(activity, "mRecyclerView") as RecyclerView
                mode = field(activity, "mModeManager") as ModeManager
                manager = ThingManager.getInstance(activity)!!
                list.childCount >= 2 && !list.isComputingLayout && activity.hasWindowFocus()
            }
            SystemClock.sleep(700)
            val originalItemAnimator = list.itemAnimator
            if (args.getString("scroll_warm") == "true") {
                mainSync { (list.adapter as com.ywwynm.everythingdone.adapters.ThingsAdapter).setShouldThingsAnimWhenAppearing(false) }
                ParticleScrollReuseAudit.run(activity, list, mode, args, folder, ::mainSync, automation)
                result.put("ok", true)
                File(folder, "result.json").writeText(result.toString())
                finish(0, Bundle().apply { putString("result", "完成：$run") })
                return
            }
            if (auditAppearance) mainSync {
                (list.adapter as com.ywwynm.everythingdone.adapters.ThingsAdapter).setShouldThingsAnimWhenAppearing(false)
            }
            val normalAppearance = if (auditAppearance) SelectionCardAppearanceAudit.scan(list, ::mainSync, folder, "appearance-before") else null
            if (args.getString("cache_exit") == "true") {
                check(normalAppearance != null)
                mainSync {
                    list.setItemViewCacheSize(16)
                    val position = manager.getThingListEntries().orEmpty().indexOfFirst { it.stableId == SelectionCardAppearanceAudit.DOING_ID }
                    (list.layoutManager as StaggeredGridLayoutManager).scrollToPositionWithOffset(position, 0)
                }
                waitForIdleSync(); SystemClock.sleep(200)
                mainSync { mode.toSelectingMode(1) }
                waitForIdleSync(); SystemClock.sleep(400)
                mainSync { mode.backNormalMode(0) }
                SystemClock.sleep(90)
                var displacement = 0
                var cachedDoing: View? = null
                mainSync {
                    cachedDoing = (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull {
                        it.getTag(R.id.tag_thing_card_bound_stable_id) == SelectionCardAppearanceAudit.DOING_ID
                    }
                    check(cachedDoing != null) { "缓存场景没有显示正在做记事" }
                    val doing = cachedDoing!!.findViewById<View>(R.id.fl_thing_doing_cover)
                    result.put("doingDuringExit", JSONObject().put("alpha", doing.alpha)
                        .put("visible", doing.visibility).put("view", System.identityHashCode(cachedDoing)))
                    displacement = cachedDoing!!.bottom + 32
                    list.scrollBy(0, displacement)
                }
                SystemClock.sleep(450)
                mainSync {
                    result.put("doingDetached", cachedDoing?.isAttachedToWindow != true)
                    list.scrollBy(0, -displacement)
                }
                waitForIdleSync()
                mainSync {
                    result.put("doingReattached", cachedDoing?.isAttachedToWindow == true)
                    result.put("doingFinalAlpha", cachedDoing?.findViewById<View>(R.id.fl_thing_doing_cover)?.alpha)
                }
                SelectionCardAppearanceAudit.scan(list, ::mainSync, folder, "appearance-cache", normalAppearance)
                result.put("ok", true).put("cacheExit", true)
                File(folder, "result.json").writeText(result.toString())
                finish(0, Bundle().apply { putString("result", "完成：$run") })
                return
            }
            val chosen = mutableListOf<Long>()
            val expectedStates = mutableMapOf<Long, Int>()
            val untouched = mutableMapOf<Long, List<Any?>>()
            fun persistedFields(thing: Thing) = listOf(thing.state, thing.title, thing.content, thing.folderId)
            mainSync {
                val position = when (args.getString("scroll")) { "middle" -> 15; "bottom" -> count - 1; else -> 0 }
                (list.layoutManager as StaggeredGridLayoutManager).scrollToPositionWithOffset(position, 0)
            }
            waitForIdleSync()
            SystemClock.sleep(300)
            mainSync {
                val entries = manager.getThingListEntries().orEmpty()
                val viewport = android.graphics.Rect().also { list.getGlobalVisibleRect(it) }
                val toolbarBottom = activity.findViewById<View>(R.id.rl_contextual_toolbar).height
                viewport.top = maxOf(viewport.top, toolbarBottom)
                val visible = (0 until list.childCount).filter {
                    val bounds = android.graphics.Rect()
                    list.getChildAt(it).getGlobalVisibleRect(bounds) && bounds.intersect(viewport) && bounds.height() > 16
                }.map { list.getChildAdapterPosition(list.getChildAt(it)) }
                    .filter { entries.getOrNull(it) is ThingListEntry.ThingEntry &&
                        (entries[it] as ThingListEntry.ThingEntry).thing.type == Thing.NOTE }.sorted()
                val notePositions = entries.indices.filter { (entries[it] as? ThingListEntry.ThingEntry)?.thing?.type == Thing.NOTE &&
                    (args.getString("select_doing") == "true" || entries[it].stableId != App.getDoingThingId()) }
                val selected = when (args.getString("selection")) {
                    "single" -> visible.filter { it in notePositions }.take(1)
                    "all" -> notePositions
                    "offscreen" -> notePositions.filter { it !in visible }.take(3)
                    "mixed" -> visible.take(2) + notePositions.filter { it !in visible }.takeLast(2)
                    else -> visible.filter { it in notePositions }.take(3)
                }.filter { it in notePositions }.let { positions ->
                    if (args.getString("select_doing") == "true") (positions + entries.indexOfFirst {
                        it.stableId == App.getDoingThingId()
                    }.takeIf { it >= 0 }.let { listOfNotNull(it) }).distinct() else positions
                }
                if (toolbarEntry) {
                    fixtureIds.forEach { id ->
                        val thing = ThingDAO.getInstance(activity)!!.getThingById(id)!!
                        val inScope = !inFolder || thing.folderId in listOf(9_000_000L, 9_000_001L)
                        if (inScope && Thing.getStatusForState(thing.state) == status && id != App.getDoingThingId()) chosen += id
                        else untouched[id] = persistedFields(thing)
                    }
                    check(chosen.isNotEmpty())
                } else {
                    check(selected.isNotEmpty())
                    selected.forEach { chosen += entries[it].stableId }
                    mode.toSelectingMode(selected.first())
                    selected.forEach { manager.setListEntrySelected(it, true) }
                    mode.updateSelectedCount(); mode.updateMenuItems()
                    list.adapter!!.notifyDataSetChanged()
                }
                chosen.forEach { expectedStates[it] = if (toolbarEntry && action == "untrash" && it % 2 == 0L) Thing.FINISHED else after }
                result.put("selectedIds", JSONArray(chosen)).put("visiblePositions", JSONArray(visible))
                    .put("status", status).put("after", after).put("entry", if (toolbarEntry) "toolbar" else "selection")
            }
            waitForIdleSync()
            SystemClock.sleep(650)
            screenshot("before")
            if (args.getString("inspect_colors") == "true") mainSync {
                val colors = JSONArray()
                for (i in 0 until list.childCount) {
                    val card = list.getChildAt(i)
                    val id = card.getTag(R.id.tag_thing_card_bound_stable_id) as? Long ?: continue
                    val bg = card.background as? android.graphics.drawable.GradientDrawable ?: continue
                    val bitmap = android.graphics.Bitmap.createBitmap(card.width, card.height, android.graphics.Bitmap.Config.ARGB_8888)
                    card.draw(android.graphics.Canvas(bitmap))
                    val row = JSONObject().put("id", id).put("colors", JSONArray(bg.colors?.toList().orEmpty()))
                        .put("filter", bg.colorFilter?.toString()).put("forceDark", card.isForceDarkAllowed)
                        .put("pixel", bitmap.getPixel(card.width / 2, (card.height - 10).coerceAtLeast(0)))
                    colors.put(row)
                    if (id == 8000017L) File(folder, "native-card.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.eraseColor(0)
                    bg.draw(android.graphics.Canvas(bitmap))
                    row.put("backgroundPixel", bitmap.getPixel(card.width / 2, card.height / 2))
                    bitmap.eraseColor(0)
                    android.graphics.Canvas(bitmap).drawColor(bg.colors!![0])
                    row.put("drawColorPixel", bitmap.getPixel(card.width / 2, card.height / 2))
                    bitmap.recycle()
                }
                result.put("beforeColors", colors)
            }
            mainSync {
                if (toolbarEntry) {
                    check(mode.getCurrentMode() == ModeManager.NORMAL)
                    val menuId = when (action) {
                        "finish" -> R.id.act_finish_all
                        "delete" -> R.id.act_delete_all
                        "forever" -> R.id.act_delete_all_forever
                        else -> R.id.act_restore_all
                    }
                    val toolbar = field(activity, "mActionbar") as Toolbar
                    check(toolbar.menu.findItem(menuId)?.isVisible == true) { "普通工具栏动作不可见" }
                    // 经过 Toolbar 的真实菜单分发，不能直接调用范围操作或动画 helper。
                    toolbar.menu.performIdentifierAction(menuId, 0)
                } else {
                    val menuId = when (action) {
                        "finish" -> R.id.act_finish_selected
                        "delete" -> R.id.act_delete_selected
                        "forever" -> R.id.act_delete_selected_forever
                        else -> R.id.act_restore_selected
                    }
                    val toolbar = activity.findViewById<Toolbar>(R.id.contextual_toolbar)
                    check(toolbar.menu.findItem(menuId)?.isVisible == true) { "选择工具栏动作不可见" }
                    toolbar.menu.performIdentifierAction(menuId, 0)
                }
            }
            val appearance = com.ywwynm.everythingdone.fragments.BaseDialogFragment::class.java
                .getDeclaredField("appearanceSettled").apply { isAccessible = true }
            waitUntil("确认按钮") {
                activity.supportFragmentManager.fragments.filterIsInstance<AlertDialogFragment>().any {
                    it.dialog?.findViewById<View>(R.id.tv_confirm_as_bt_alert)?.isShown == true &&
                        appearance.getBoolean(it) && it.dialog?.window?.decorView?.hasWindowFocus() == true &&
                        it.dialog?.window?.attributes?.alpha == 1f
                } && ParticleDismissOverlay.activeIn(activity).isEmpty()
            }
            val startFile = File(folder, "start")
            File(folder, "ready.json").writeText(result.toString())
            if (args.getString("wait_start") == "true") {
                val until = SystemClock.uptimeMillis() + 45000
                while (!startFile.exists() && SystemClock.uptimeMillis() < until) SystemClock.sleep(40)
                check(startFile.exists())
            }
            val start = SystemClock.uptimeMillis()
            recording = true
            mainSync {
                val toolbar = activity.findViewById<Toolbar>(R.id.contextual_toolbar)
                val chrome = activity.findViewById<View>(R.id.rl_contextual_toolbar)
                val shadow = activity.findViewById<View>(R.id.actionbar_shadow)
                Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
                    override fun doFrame(frameTimeNanos: Long) {
                        if (!recording) return
                        val transform = Transformation()
                        chrome.animation?.getTransformation(SystemClock.uptimeMillis(), transform)
                        val matrix = FloatArray(9); transform.matrix.getValues(matrix)
                        val items = JSONArray()
                        if (list.isLayoutSuppressed) ParticleDismissOverlay.activeIn(activity).forEach {
                            val spec = field(it, "spec") as ParticleDismissSpec
                            observedCardStyles += spec.style
                            val dx = spec.virtualTouchXPx - spec.originXPx - spec.snapshot.width / 2f
                            val dy = spec.virtualTouchYPx - spec.originYPx - spec.snapshot.height / 2f
                            observedDirections += kotlin.math.round((Math.toDegrees(kotlin.math.atan2(-dy, dx).toDouble()) + 360) % 360).toInt()
                        }
                        for (i in 0 until list.childCount) {
                            val view = list.getChildAt(i)
                            val holder = list.getChildViewHolder(view)
                            val alphas = JSONArray()
                            fun collect(v: View) {
                                if (v is android.widget.TextView || v.alpha != 1f) alphas.put(JSONObject()
                                    .put("id", v.id).put("alpha", v.alpha))
                                if (v is ViewGroup) for (j in 0 until v.childCount) collect(v.getChildAt(j))
                            }
                            collect(view)
                            val id = view.getTag(R.id.tag_thing_card_bound_stable_id) as? Long ?: Long.MIN_VALUE
                            val background = view.findViewById<View>(R.id.cv_thing)?.background as? android.graphics.drawable.GradientDrawable
                            items.put(JSONObject().put("id", id).put("view", System.identityHashCode(view)).put("p", holder.bindingAdapterPosition)
                                .put("x", view.x).put("y", view.y).put("h", view.height).put("w", view.width)
                                .put("alpha", view.alpha).put("visible", view.visibility).put("children", alphas)
                                .put("colors", JSONArray(background?.colors?.toList().orEmpty())))
                        }
                        frames.put(JSONObject().put("t", SystemClock.uptimeMillis() - start)
                            .put("mode", mode.getCurrentMode()).put("overlays", ParticleDismissOverlay.activeIn(activity).size)
                            .put("removal", (field(activity, "mListRemovalAnimator") as? ThingListRemovalAnimator)?.isRunning == true)
                            .put("suppressed", list.isLayoutSuppressed).put("itemAnimator", list.itemAnimator?.isRunning == true)
                            .put("chromeY", chrome.translationY + matrix[5]).put("chromeH", chrome.height)
                            .put("chromeVisibility", chrome.visibility).put("menuSize", toolbar.menu.size())
                            .put("shadow", shadow.alpha).put("systemUi", activity.window.decorView.systemUiVisibility)
                            .put("darkIcons", androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars)
                            .put("items", items))
                        Choreographer.getInstance().postFrameCallback(this)
                    }
                })
            }
            val bounds = android.graphics.Rect()
            mainSync {
                val confirm = activity.supportFragmentManager.fragments.filterIsInstance<AlertDialogFragment>()
                    .first { it.dialog?.isShowing == true }.dialog!!.findViewById<View>(R.id.tv_confirm_as_bt_alert)
                check(confirm.isShown)
                val at = IntArray(2).also { confirm.getLocationOnScreen(it) }
                bounds.set(at[0], at[1], at[0] + confirm.width, at[1] + confirm.height)
            }
            val down = SystemClock.uptimeMillis()
            for (actionCode in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), actionCode,
                    bounds.exactCenterX(), bounds.exactCenterY(), 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                check(automation.injectInputEvent(event, true)); event.recycle()
                if (actionCode == android.view.MotionEvent.ACTION_DOWN) SystemClock.sleep(64)
            }
            if (args.getString("interrupt") == "back") {
                waitUntil("批量动画中断点") {
                    (field(activity, "mListRemovalAnimator") as? ThingListRemovalAnimator)?.isRunning == true &&
                        activity.supportFragmentManager.fragments.none { it is AlertDialogFragment && it.dialog?.isShowing == true }
                }
                SystemClock.sleep(150)
                sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                result.put("interrupted", true)
            }
            if (args.getString("screenshots") == "true") {
                var frame = 0
                val until = SystemClock.uptimeMillis() + 8000
                while (SystemClock.uptimeMillis() < until) {
                    screenshot("visual-${frame++}")
                    var done = false
                    mainSync {
                        done = mode.getCurrentMode() == ModeManager.NORMAL &&
                            (field(activity, "mListRemovalAnimator") as? ThingListRemovalAnimator)?.isRunning != true
                    }
                    if (done) break
                    SystemClock.sleep(55)
                }
            }
            waitUntil("业务动作和粒子完成", 25000) {
                mode.getCurrentMode() == ModeManager.NORMAL && ParticleDismissOverlay.activeIn(activity).isEmpty() &&
                    (field(activity, "mListRemovalAnimator") as? ThingListRemovalAnimator)?.isRunning != true &&
                    list.itemAnimator?.isRunning != true
            }
            SystemClock.sleep(1100)
            mainSync {
                recording = false
                result.put("finalIds", JSONArray(manager.getThingListEntries().orEmpty().map { it.stableId }))
                result.put("selectedRemaining", manager.getSelectedCount())
                check(manager.getSelectedCount() == 0)
                check(manager.getThingListEntries().orEmpty().none { it.stableId in chosen && it.stableId != App.getDoingThingId() }) { "移除未生效：${result}" }
                chosen.forEach { id ->
                    val actual = ThingDAO.getInstance(activity)!!.getThingById(id)
                    check(if (id == App.getDoingThingId()) actual?.state == Thing.UNDERWAY
                        else if (after == Thing.DELETED_FOREVER) actual == null else actual?.state == expectedStates[id])
                }
                if (toolbarEntry) {
                    untouched.forEach { (id, expected) ->
                        check(ThingDAO.getInstance(activity)!!.getThingById(id)?.let(::persistedFields) == expected) { "范围外或受保护记事改变：$id" }
                    }
                    result.put("unchangedIds", JSONArray(untouched.keys.toList()))
                    if (inFolder) check(manager.getFolderById(9_000_000L) != null) { "范围操作删除了当前容器" }
                    if (args.getString("selection") != "all") check(manager.getFolderById(9_000_001L) != null) { "范围操作删除了子文件夹" }
                    if (args.getString("protect_doing") == "true")
                        check(ThingDAO.getInstance(activity)!!.getThingById(8_000_001L)?.state == Thing.UNDERWAY)
                }
                if (toolbarEntry || args.getString("check_directions") == "true") {
                    val cardParticleFrames = (0 until frames.length()).count {
                        val frame = frames.getJSONObject(it)
                        frame.getBoolean("removal") && frame.getBoolean("suppressed") && frame.getInt("overlays") > 0
                    }
                    result.put("cardParticleFrames", cardParticleFrames)
                    result.put("cardDirections", JSONArray(observedDirections.toList()))
                    if (args.getString("check_directions") == "true") {
                        check(observedDirections.size == 1 && observedDirections.all { it == 90 || it == 135 }) {
                            "同一次卡片移除应共用一个左上或正上方向：$observedDirections"
                        }
                    }
                    result.put("requestedStyle", requestedStyle.key)
                        .put("observedCardStyles", JSONArray(observedCardStyles.map { it.key }))
                    if (args.getString("particles") != "false") {
                        check(cardParticleFrames > 0) { "菜单操作未播放卡片粒子" }
                        check(observedCardStyles == setOf(requestedStyle)) { "实际卡片粒子样式与请求不符" }
                    }
                    check(!list.isLayoutSuppressed) { "批量收尾未解除布局封锁" }
                }
                result.put("restoredItemAnimator", list.itemAnimator === originalItemAnimator)
                check(list.itemAnimator === originalItemAnimator) { "批量收尾未恢复原 ItemAnimator" }
            }
            if (normalAppearance != null) {
                screenshot("appearance-settled")
                SelectionCardAppearanceAudit.scan(list, ::mainSync, folder, "appearance-after", normalAppearance)
                if (status == Def.ThingStatus.UNDERWAY && args.getString("checklist_taps") != "false") {
                    var checklistId = -1L
                    var beforeContent = ""
                    var position = -1
                    mainSync {
                        position = manager.getThingListEntries().orEmpty().indexOfFirst { entry ->
                            val thing = (entry as? ThingListEntry.ThingEntry)?.thing
                            thing != null && thing.id != App.getDoingThingId() && thing.type == Thing.NOTE &&
                                !thing.isPrivate() && com.ywwynm.everythingdone.helpers.CheckListHelper.isCheckListStr(thing.content!!)
                        }
                        check(position >= 0)
                        val thing = (manager.getThingListEntries()!![position] as ThingListEntry.ThingEntry).thing
                        checklistId = thing.id; beforeContent = thing.content!!
                        (list.layoutManager as StaggeredGridLayoutManager).scrollToPositionWithOffset(position,
                            activity.findViewById<View>(R.id.rl_contextual_toolbar).height + 24)
                    }
                    val clickBounds = android.graphics.Rect()
                    waitUntil("清单点击目标") {
                        val card = list.findViewHolderForAdapterPosition(position)?.itemView
                        val click = card?.findViewById<View>(R.id.ll_check_list_tv)
                        if (click?.isShown == true) {
                            val at = IntArray(2).also { click.getLocationOnScreen(it) }
                            val viewport = android.graphics.Rect().also { list.getGlobalVisibleRect(it) }
                            val top = maxOf(viewport.top, activity.findViewById<View>(R.id.rl_contextual_toolbar).height) + 24
                            val bottom = viewport.bottom - 24
                            val center = at[1] + click.height / 2
                            if (center < top || center > bottom) {
                                list.scrollBy(0, center - (top + bottom) / 2)
                                false
                            } else click.isClickable && click.getGlobalVisibleRect(clickBounds) && clickBounds.height() > 16
                        } else false
                    }
                    val pressedAt = SystemClock.uptimeMillis()
                    for (code in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                        val event = android.view.MotionEvent.obtain(pressedAt, SystemClock.uptimeMillis(), code,
                            clickBounds.exactCenterX(), clickBounds.exactCenterY(), 0)
                        event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                        check(automation.injectInputEvent(event, true)); event.recycle()
                        SystemClock.sleep(24)
                    }
                    waitUntil("清单实际勾选") { ThingDAO.getInstance(activity)!!.getThingById(checklistId)?.content != beforeContent }
                    result.put("checklistActualTap", checklistId)
                }
            }
            automation.takeScreenshot()?.let { bitmap ->
                File(folder, "after.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            result.put("ok", true)
            File(folder, "result.json").writeText(result.put("frames", frames).toString())
            finish(0, Bundle().apply { putString("result", "完成：$run") })
        } catch (failure: Throwable) {
            recording = false
            runCatching {
                getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()?.let { bitmap ->
                    File(folder, "failure.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
            File(folder, "result.json").writeText(result.put("ok", false).put("error", failure.stackTraceToString())
                .put("frames", frames).toString())
            finish(1, Bundle().apply { putString("result", failure.toString()) })
        }
    }
}
