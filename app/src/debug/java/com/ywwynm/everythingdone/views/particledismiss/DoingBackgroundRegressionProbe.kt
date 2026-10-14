package com.ywwynm.everythingdone.views.particledismiss

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import com.github.adnansm.timelytextview.TimelyClockView
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.DoingActivity
import com.ywwynm.everythingdone.activities.SettingsActivity
import com.ywwynm.everythingdone.fragments.MediaCropAppearanceDialogFragment
import com.ywwynm.everythingdone.fragments.ThreeOptionsDialogFragment
import com.ywwynm.everythingdone.model.DoingBackgroundCrops
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.views.ThingCardCropEditorView
import java.io.File

/** 三机合并验收：真实设置和裁切组件、真实 Doing 内容树、虚构记事与时间源。 */
class DoingBackgroundRegressionProbe : Instrumentation() {
    private lateinit var folder: File
    private val checks = mutableListOf<String>()
    private var settings: SettingsActivity? = null
    private var doing: ParticleDoingProbeActivity? = null
    private var testSettings = true
    private var settingsOnly = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        testSettings = arguments?.getString("skip_settings") != "true"
        settingsOnly = arguments?.getString("settings_only") == "true"
        start()
    }

    private fun <T> ui(block: () -> T): T {
        var result: Result<T>? = null
        runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (!ui(predicate)) {
            check(SystemClock.uptimeMillis() < deadline) { "等待超时：$label" }
            SystemClock.sleep(40)
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun cropDialog(): MediaCropAppearanceDialogFragment? = settings?.supportFragmentManager
        ?.findFragmentByTag(MediaCropAppearanceDialogFragment.TAG) as? MediaCropAppearanceDialogFragment

    private fun screenshot(name: String) {
        val bitmap = checkNotNull(uiAutomation.takeScreenshot())
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun rotateDisplay(rotation: Int?) {
        uiAutomation.executeShellCommand(if (rotation == null) "wm user-rotation free"
            else "wm user-rotation lock $rotation").use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }

    override fun onStart() {
        folder = File(targetContext.getExternalFilesDir(null), "doing-background-regression").apply { mkdirs() }
        val preferences = targetContext.getSharedPreferences(Def.Meta.PREFERENCES_NAME, 0)
        val oldPath = preferences.getString(Def.Meta.KEY_DOING_BACKGROUND, null)
        val oldCrops = preferences.getString(Def.Meta.KEY_DOING_BACKGROUND_CROPS, null)
        val oldAutoRotation = Settings.System.getInt(targetContext.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1)
        val oldRotation = Settings.System.getInt(targetContext.contentResolver, Settings.System.USER_ROTATION, 0)
        var imported: String? = null
        var report: ParticlePresentationReports.Report? = null
        val result = runCatching {
            val source = File(targetContext.filesDir, "doing-background-fixture.png")
            val bitmap = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                val paint = Paint()
                for (y in 0 until 12) for (x in 0 until 16) {
                    paint.color = Color.rgb(x * 16, y * 21, 140)
                    drawRect(x * 100f, y * 100f, (x + 1) * 100f, (y + 1) * 100f, paint)
                }
            }
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            if (testSettings) {
            preferences.edit().remove(Def.Meta.KEY_DOING_BACKGROUND).remove(Def.Meta.KEY_DOING_BACKGROUND_CROPS).commit()
            val activity = startActivitySync(Intent(targetContext, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
            settings = activity
            await("设置布局") { activity.findViewById<View>(R.id.ll_doing_background_as_bt).height > 0 }
            ui {
                check(activity.findViewById<View>(R.id.ll_doing_background_as_bt).height ==
                    activity.findViewById<View>(R.id.ll_change_drawer_header_as_bt).height)
                activity.findViewById<View>(R.id.ll_doing_background_as_bt).performClick()
            }
            await("默认背景菜单") {
                val view = activity.supportFragmentManager.findFragmentByTag(ThreeOptionsDialogFragment.TAG)?.view
                view != null && view.findViewById<View>(R.id.tv_action_end).visibility == View.GONE
            }
            ui { (activity.supportFragmentManager.findFragmentByTag(ThreeOptionsDialogFragment.TAG)
                as ThreeOptionsDialogFragment).dismiss() }
            await("关闭菜单") { activity.supportFragmentManager.findFragmentByTag(ThreeOptionsDialogFragment.TAG) == null }
            // 经真实 ActivityResult 分发返回不提供 _data 的本地 PNG，覆盖新旧结果处理重复执行。
            val pickerErrors = mutableSetOf<String>()
            val errorTexts = setOf(activity.getString(R.string.error_cannot_add_from_network),
                activity.getString(R.string.error_unsupported_file_type))
            val errorObserver = ViewTreeObserver.OnGlobalLayoutListener {
                descendants(activity.window.decorView).filterIsInstance<TextView>()
                    .filter { it.isShown && it.text.toString() in errorTexts }
                    .forEach { pickerErrors += it.text.toString() }
            }
            var pickerLaunchCount = 0
            val picker = object : ActivityMonitor() {
                override fun onStartActivity(intent: Intent): ActivityResult? {
                    if (intent.action != Intent.ACTION_GET_CONTENT || intent.type != "image/*") return null
                    pickerLaunchCount++
                    return ActivityResult(Activity.RESULT_OK, Intent().setData(
                        FileProvider.getUriForFile(activity, "com.ywwynm.everythingdone", source)))
                }
            }
            ui { activity.window.decorView.viewTreeObserver.addOnGlobalLayoutListener(errorObserver) }
            addMonitor(picker)
            try {
                ui { activity.findViewById<View>(R.id.ll_doing_background_as_bt).performClick() }
                await("选择背景图片菜单") {
                    activity.supportFragmentManager.findFragmentByTag(ThreeOptionsDialogFragment.TAG)?.view != null
                }
                ui { activity.supportFragmentManager.findFragmentByTag(ThreeOptionsDialogFragment.TAG)?.view
                    ?.findViewById<View>(R.id.tv_action_middle)?.performClick() }
                await("导入并加载裁切") {
                    cropDialog()?.view?.let { descendants(it).filterIsInstance<TextView>()
                        .any { text -> text.text == activity.getString(R.string.confirm) && text.isEnabled } } == true
                }
                imported = preferences.getString(Def.Meta.KEY_DOING_BACKGROUND, null)
                check(imported != null && imported != source.path) { "content URI 未复制为持久图片" }
                SystemClock.sleep(2400) // 等待现有对话框凝聚动画结束后检查可见内容。
                ui {
                    check(pickerLaunchCount == 1) { "图片选择器未通过真实菜单恰好启动一次" }
                    check(pickerErrors.isEmpty()) { "PNG 已成功复制并进入裁切，却同时提示：$pickerErrors" }
                }
                checks += "真实 ActivityResult 选取无直接路径的本地 PNG 成功，未出现网络或格式错误提示"
            } finally {
                removeMonitor(picker)
                ui { activity.window.decorView.viewTreeObserver.removeOnGlobalLayoutListener(errorObserver) }
            }
            var editor = ui { descendants(checkNotNull(cropDialog()?.view)).filterIsInstance<ThingCardCropEditorView>().single() }
            fun setFrame(landscape: Boolean, frame: DoingBackgroundCrops.Frame) = ui {
                val tabText = activity.getString(if (landscape) R.string.doing_background_landscape
                    else R.string.doing_background_portrait)
                descendants(checkNotNull(cropDialog()?.view)).filterIsInstance<TextView>()
                    .single { it.text == tabText }.performClick()
                val field = ThingCardCropEditorView::class.java.getDeclaredField("bitmap").apply { isAccessible = true }
                editor.setCropBitmap(field.get(editor) as Bitmap, editor.getTargetAspectRatio(),
                    frame.centerX, frame.centerY, frame.scale)
            }
            setFrame(false, DoingBackgroundCrops.Frame(.3, .4, 2.0))
            val portraitRatio = ui { editor.getTargetAspectRatio() }
            setFrame(true, DoingBackgroundCrops.Frame(.7, .6, 2.5))
            val landscapeRatio = ui { editor.getTargetAspectRatio() }
            check(kotlin.math.abs(portraitRatio * landscapeRatio - 1.0) < 0.00001)
            waitForIdleSync()
            screenshot("crop-landscape-tab")
            ui { descendants(checkNotNull(cropDialog()?.view)).filterIsInstance<TextView>()
                .single { it.text == activity.getString(R.string.confirm) }.performClick() }
            await("保存裁切") { cropDialog() == null }
            val stored = DoingBackgroundCrops.fromJson(preferences.getString(Def.Meta.KEY_DOING_BACKGROUND_CROPS, null))
            check(kotlin.math.abs(stored.portrait.centerX - .3) < .001 && stored.portrait.scale == 2.0)
            check(kotlin.math.abs(stored.landscape.centerX - .7) < .001 && stored.landscape.scale == 2.5)
            checks += "72dp 设置行、默认菜单、content URI 导入、两套固定比例和独立保存通过"
            ui { activity.findViewById<View>(R.id.ll_doing_background_as_bt).performClick() }
            await("图片菜单") {
                activity.supportFragmentManager.findFragmentByTag(ThreeOptionsDialogFragment.TAG)?.view
                    ?.findViewById<View>(R.id.tv_action_end)?.visibility == View.VISIBLE
            }
            ui { activity.supportFragmentManager.findFragmentByTag(ThreeOptionsDialogFragment.TAG)?.view
                ?.findViewById<View>(R.id.tv_action_end)?.performClick() }
            await("重开裁切") { cropDialog()?.view != null }
            SystemClock.sleep(2400)
            editor = ui { descendants(checkNotNull(cropDialog()?.view)).filterIsInstance<ThingCardCropEditorView>().single() }
            ui { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            await("裁切窗口旋转") { activity.resources.configuration.orientation == 2 &&
                cropDialog()?.dialog?.window?.decorView?.height?.let { it <= activity.window.decorView.height } == true }
            SystemClock.sleep(600)
            screenshot("crop-window-landscape")
            ui { descendants(checkNotNull(cropDialog()?.view)).filterIsInstance<TextView>()
                .single { it.text == activity.getString(R.string.cancel) }.performClick() }
            await("取消裁切") { cropDialog() == null }
            check(stored == DoingBackgroundCrops.fromJson(preferences.getString(Def.Meta.KEY_DOING_BACKGROUND_CROPS, null)))
            ui { activity.finish() }
            checks += "仅自选图片显示调整项、裁切窗口旋转和取消保留原配置通过"
            } else {
                preferences.edit().putString(Def.Meta.KEY_DOING_BACKGROUND, source.path)
                    .putString(Def.Meta.KEY_DOING_BACKGROUND_CROPS, DoingBackgroundCrops(
                        DoingBackgroundCrops.Frame(.3, .4, 2.0), DoingBackgroundCrops.Frame(.7, .6, 2.5)).toJson()).commit()
            }

            if (settingsOnly) return@runCatching
            report = ui { ParticlePresentationReports.start(targetContext, "Doing", "背景与方向回归",
                ParticlePresentationDiagnostics.Options(images = false, glForeground = true, opaqueGlHandoff = true,
                    surfaceRecovery = true, fastOpaqueHandoff = true, unified = true, fixture = false),
                "虚构记事，不写数据库", checkpoints = false) }
            val page = startActivitySync(Intent(targetContext, ParticleDoingProbeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(ParticlePresentationReports.EXTRA_RUN, report!!.id)
                .putExtra("doing_long_card", true)) as ParticleDoingProbeActivity
            doing = page
            ui { page.command("business") }
            val clock = ui { page.findViewById<TimelyClockView>(R.id.clock_time_doing) }
            fun field(name: String): Any? = DoingActivity::class.java.getDeclaredField(name)
                .apply { isAccessible = true }.get(page)
            val scene = ui { field("mParticlePage") }
            val timeField = ParticleDoingProbeActivity::class.java.getDeclaredField("probeTime").apply { isAccessible = true }
            var previousTime = ui { timeField.getLong(page) }
            ui { page.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER }
            val naturalLandscape = ui { (page.resources.configuration.orientation == 2) xor
                (page.windowManager.defaultDisplay.rotation % 2 == 1) }
            for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)) {
                val landscape = orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                rotateDisplay(if (landscape xor naturalLandscape) 1 else 0)
                await("做事页方向和背景") {
                    val image = page.findViewById<ImageView>(R.id.iv_custom_bg_doing)
                    val drawable = image.drawable as? BitmapDrawable
                    (image.width > image.height) == landscape && drawable?.bitmap?.width == image.width &&
                        drawable.bitmap.height == image.height
                }
                await("倒计时继续并完成入场") { timeField.getLong(page) < previousTime &&
                    kotlin.math.abs(page.findViewById<RecyclerView>(R.id.rv_thing_doing).alpha - .84f) < .001f &&
                    page.findViewById<RecyclerView>(R.id.rv_thing_doing).scrollState == RecyclerView.SCROLL_STATE_IDLE }
                SystemClock.sleep(650) // 系统旋转合成结束后再取屏幕像素并注入触摸。
                ui {
                    check(!page.isDestroyed && page.findViewById<TimelyClockView>(R.id.clock_time_doing) === clock)
                    check(scene != null && field("mParticlePage") === scene)
                    val time = timeField.getLong(page)
                    check(time < previousTime) { "旋转后计时没有继续" }; previousTime = time
                    val rv = page.findViewById<RecyclerView>(R.id.rv_thing_doing)
                    check(rv.getChildAt(0).width <= rv.width && rv.height > 0)
                    check(kotlin.math.abs(rv.getChildAt(0).left + rv.getChildAt(0).width / 2f - rv.width / 2f) <= 2f) {
                        "系统栏更新后卡片没有居中"
                    }
                    verifyControlsLayout(page, landscape)
                }
                screenshot(if (landscape) "doing-landscape" else "doing-portrait")
                swipeAndReturn(page, landscape)
                if (landscape) {
                    val button = ui {
                        android.graphics.Rect().also {
                            check(page.findViewById<View>(R.id.fab_add_5_min).getGlobalVisibleRect(it))
                        }
                    }
                    val before = ui { timeField.getLong(page) }
                    val downTime = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                            button.centerX().toFloat(), button.centerY().toFloat(), 0)
                        sendPointerSync(event)
                        event.recycle()
                    }
                    await("横屏加五分钟按钮可点击") { timeField.getLong(page) > before + 290000L }
                    previousTime = ui { timeField.getLong(page) }
                    checks += "横屏真实触摸加五分钟按钮通过"
                }
            }
            val events = report!!.json().getJSONArray("events")
            val names = (0 until events.length()).map { events.getJSONObject(it).getString("event") }
            check("doing_scene_resized" in names) { "整页粒子场景没有按旋转调整" }
            check("swipe_pixels_visible" in names) { "没有实际显示滑动粒子" }
            check(names.none { it == "doing_scene_failed" }) { "整页粒子场景发生回退" }
            File(folder, "particle-report.json").writeText(report!!.json().toString(2))
            checks += "竖→横→竖保留同一计时控件与粒子场景，倒计时持续、左右滑动回拉后透明度和位置恢复"
            ui {
                preferences.edit().remove(Def.Meta.KEY_DOING_BACKGROUND).apply()
                (field("mBackgroundDisplay") as com.ywwynm.everythingdone.helpers.DoingBackgroundDisplay).refresh()
                check(page.findViewById<ImageView>(R.id.iv_custom_bg_doing).drawable == null)
            }
            checks += "恢复默认背景通过"
        }
        report?.let { File(folder, "particle-report.json").writeText(it.json().toString(2)) }
        ui { doing?.finish(); if (settings?.isDestroyed == false) settings?.finish() }
        rotateDisplay(oldRotation)
        if (oldAutoRotation == 1) rotateDisplay(null)
        preferences.edit().putString(Def.Meta.KEY_DOING_BACKGROUND, oldPath)
            .putString(Def.Meta.KEY_DOING_BACKGROUND_CROPS, oldCrops).commit()
        imported?.takeIf { it != oldPath }?.let { File(it).delete() }
        File(targetContext.filesDir, "doing-background-fixture.png").delete()
        val summary = checks.joinToString("\n") + "\n" + result.fold({ "PASS" }, { it.stackTraceToString() })
        File(folder, "result.txt").writeText(summary)
        finish(if (result.isSuccess) 0 else 1, Bundle().apply { putString("result", summary) })
    }

    private fun verifyControlsLayout(page: Activity, landscape: Boolean) {
        val buttons = page.findViewById<LinearLayout>(R.id.ll_bottom_buttons_doing)
        val onSide = landscape && !DisplayUtil.isTablet(page)
        check(buttons.orientation == if (onSide) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL)
        val frames = (0 until buttons.childCount).map { buttons.getChildAt(it) }
        val rects = frames.map { frame ->
            android.graphics.Rect().also {
                check(frame.getGlobalVisibleRect(it) && it.width() == frame.width && it.height() == frame.height) {
                    "操作按钮未完整显示"
                }
            }
        }
        rects.zipWithNext().forEach { (a, b) ->
            check(if (onSide) a.centerX() == b.centerX() && a.bottom <= b.top
                else a.centerY() == b.centerY() && a.right <= b.left) { "操作按钮排列或间距错误" }
        }
        val rv = page.findViewById<RecyclerView>(R.id.rv_thing_doing)
        val rvLocation = IntArray(2).also(rv::getLocationOnScreen)
        val full = page.findViewById<View>(R.id.iv_custom_bg_doing)
        val origin = IntArray(2).also(full::getLocationOnScreen)
        val pageCenterX = origin[0] + full.width / 2f
        val card = rv.getChildAt(0)
        val hint = page.findViewById<View>(R.id.tv_swipe_to_finish_doing)
        listOf(page.findViewById<View>(R.id.clock_time_doing), card, hint).forEach { view ->
            val location = IntArray(2).also(view::getLocationOnScreen)
            check(kotlin.math.abs(location[0] + view.width / 2f - pageCenterX) <= 2f) {
                "${page.resources.getResourceEntryName(view.id)} 未相对整页水平居中"
            }
        }
        if (onSide) {
            val cardLocation = IntArray(2).also(card::getLocationOnScreen)
            check(cardLocation[0] + card.width <= rects.first().left) { "右侧按钮与卡片重叠" }
            val bars = checkNotNull(ViewCompat.getRootWindowInsets(page.window.decorView))
                .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            check(rects.first().top >= origin[1] + bars.top &&
                rects.last().bottom <= origin[1] + full.height - bars.bottom) { "按钮进入系统栏区域" }
            val hintMargin = (hint.layoutParams as ViewGroup.MarginLayoutParams).topMargin
            val previousMaxHeight = origin[1] + full.height - bars.bottom -
                page.resources.getDimensionPixelSize(R.dimen.doing_bottom_controls_margin) - frames.first().height -
                rvLocation[1] - hint.height - hintMargin -
                page.resources.getDimensionPixelSize(R.dimen.doing_thing_card_vertical_margin)
            val gainDp = (rv.height - previousMaxHeight) / page.resources.displayMetrics.density
            check(gainDp >= 72f) { "卡片高度没有释放底部按钮占用：增加 $gainDp dp" }
            checks += "手机横屏计时、卡片和提示相对整页水平居中；按钮右侧竖排无重叠，长卡片可用高度增加 %.1f dp，按钮完整可见且避开系统栏".format(gainDp)
        } else {
            check(rvLocation[1] + rv.height <= rects.first().top) { "底部按钮与卡片重叠" }
            if (landscape) checks += "平板横屏保持底部横排，按钮与卡片无重叠"
        }
    }

    private fun swipeAndReturn(page: Activity, landscape: Boolean) {
        val rect = ui {
            val card = page.findViewById<RecyclerView>(R.id.rv_thing_doing).getChildAt(0)
            android.graphics.Rect().also { check(card.getGlobalVisibleRect(it)) }
        }
        val start = SystemClock.uptimeMillis()
        fun touch(action: Int, x: Float) {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, rect.centerY().toFloat(), 0)
            sendPointerSync(event); event.recycle()
        }
        val x = rect.centerX().toFloat()
        val distance = rect.width() * .28f * if (landscape) 1f else -1f
        touch(MotionEvent.ACTION_DOWN, x)
        for (step in 1..18) { touch(MotionEvent.ACTION_MOVE, x + distance * step / 18); SystemClock.sleep(16) }
        screenshot(if (landscape) "swipe-landscape" else "swipe-portrait")
        for (step in 17 downTo 0) { touch(MotionEvent.ACTION_MOVE, x + distance * step / 18); SystemClock.sleep(16) }
        SystemClock.sleep(120)
        touch(MotionEvent.ACTION_UP, x)
        await("滑动回拉恢复") {
            val rv = page.findViewById<RecyclerView>(R.id.rv_thing_doing)
            val card = rv.getChildAt(0)
            kotlin.math.abs(card.translationX) < .1f && card.alpha == 1f && kotlin.math.abs(rv.alpha - .84f) < .01f
        }
    }
}
