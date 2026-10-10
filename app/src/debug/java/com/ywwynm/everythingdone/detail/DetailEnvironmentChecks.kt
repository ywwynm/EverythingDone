package com.ywwynm.everythingdone.detail

import android.app.Instrumentation
import android.app.UiAutomation
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.helpers.DetailContentLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 检查真实窗口、输入法和设备旋转，不用手工调用配置回调代替系统变化。 */
internal class DetailEnvironmentChecks(
    private val instrumentation: Instrumentation,
    private val activity: DetailActivity,
    private val output: File,
    private val name: String
) {
    private val results = JSONArray()
    private val content = DetailActivity::class.java.getDeclaredField("mDetailContent")
        .apply { isAccessible = true }.get(activity) as DetailContentLayout
    private val editor = content.view<EditText>(R.id.et_content)
    private val list = content.recyclerView

    fun run() {
        checkStep("正文复制粘贴、选择区与离屏返回") {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val before = main { editor.text.toString() }
            val clip = main { clipboard.primaryClip }
            try {
                main {
                    editor.requestFocus()
                    editor.setSelection(2, 6)
                    check(editor.onTextContextMenuItem(android.R.id.copy))
                    check(clipboard.primaryClip!!.getItemAt(0).text.toString() == before.substring(2, 6))
                    editor.setSelection(editor.length())
                    check(editor.onTextContextMenuItem(android.R.id.paste))
                    check(editor.text.toString() == before + before.substring(2, 6))
                    editor.setSelection(3, 8)
                    list.scrollToPosition(content.adapter.itemCount - 1)
                }
                idle()
                main { list.scrollToPosition(content.positionOf(R.id.et_content)) }
                idle()
                main { check(editor.selectionStart == 3 && editor.selectionEnd == 8) }
            } finally {
                main {
                    editor.setText(before)
                    (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .hideSoftInputFromWindow(editor.windowToken, 0)
                    if (clip != null) clipboard.setPrimaryClip(clip) else clipboard.clearPrimaryClip()
                }
            }
        }
        checkStep("真实输入法显示后长正文末尾光标可见并可继续输入") {
            val before = main { editor.text.toString() }
            await { ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) != true }
            main { list.scrollToPosition(content.positionOf(R.id.et_content)) }
            idle()
            main {
                editor.requestFocus()
                editor.setSelection(editor.length())
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
            await { ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            await { caretVisible() }
            main { editor.text.insert(editor.selectionEnd, "【键盘输入】") }
            await { caretVisible() }
            main { check(editor.text.toString() == before + "【键盘输入】") }
            screenshot("keyboard")
            main {
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(editor.windowToken, 0)
                editor.setText(before)
                editor.setSelection(3, 8)
            }
            await { ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) != true }
        }
        checkStep("真实横竖屏保留正文选择区并重新计算窗口几何") {
            val rotation = main { activity.display!!.rotation }
            val autoRotate = Settings.System.getInt(activity.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) != 0
            val requested = main { activity.requestedOrientation }
            File(output, "$name-orientation-before.json").writeText(JSONObject().put("rotation", rotation)
                .put("auto_rotate", autoRotate).put("requested", requested).toString(2))
            val original = main { editor.text.toString() }
            val dimensions = JSONArray()
            try {
                main { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED; editor.setSelection(3, 8) }
                for (target in listOf(UiAutomation.ROTATION_FREEZE_0, UiAutomation.ROTATION_FREEZE_90)) {
                    check(instrumentation.uiAutomation.setRotation(target))
                    await { activity.display!!.rotation == target }
                    idle()
                    main {
                        check(editor.text.toString() == original)
                        check(editor.selectionStart == 3 && editor.selectionEnd == 8)
                        dimensions.put(JSONObject().put("rotation", target).put("width", list.width).put("height", list.height)
                            .put("image_columns", field<Int>("mMaxSpanImage")).put("audio_columns", field<Int>("mSpanAudio")))
                    }
                    screenshot("rotation-$target")
                }
                check(dimensions.getJSONObject(0).getInt("width") != dimensions.getJSONObject(1).getInt("width")) {
                    "物理旋转后应用窗口宽度没有变化：$dimensions"
                }
                File(output, "$name-rotations.json").writeText(dimensions.toString(2))
            } finally {
                instrumentation.uiAutomation.setRotation(if (autoRotate) UiAutomation.ROTATION_UNFREEZE else rotation)
                main { activity.requestedOrientation = requested }
                idle()
            }
        }
    }

    private fun caretVisible(): Boolean {
        val layout = editor.layout ?: return false
        val line = layout.getLineForOffset(editor.selectionEnd.coerceAtLeast(0))
        val location = IntArray(2).also(editor::getLocationOnScreen)
        val bottom = location[1] + editor.totalPaddingTop + layout.getLineBottom(line) - editor.scrollY
        val viewport = Rect()
        if (!list.getGlobalVisibleRect(viewport)) return false
        val frame = Rect().also { activity.window.decorView.getWindowVisibleDisplayFrame(it) }
        return bottom in maxOf(viewport.top, frame.top)..minOf(viewport.bottom, frame.bottom)
    }

    private fun screenshot(suffix: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(output, "$name-$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun checkStep(label: String, block: () -> Unit) {
        val entry = JSONObject().put("check", label)
        try { block(); entry.put("passed", true) }
        catch (failure: Throwable) {
            entry.put("passed", false).put("error", failure.stackTraceToString())
            entry.put("editor", main {
                val location = IntArray(2).also(editor::getLocationOnScreen)
                JSONObject().put("selection", editor.selectionEnd).put("length", editor.length())
                    .put("focused", editor.hasFocus()).put("height", editor.height).put("top", location[1])
                    .put("scroll_y", editor.scrollY).put("list_height", list.height).put("caret_visible", caretVisible())
            })
            screenshot("failure"); throw failure
        }
        finally { results.put(entry); File(output, "$name-environment.json").writeText(results.toString(2)) }
    }
    private fun idle() { instrumentation.waitForIdleSync(); await { !list.isLayoutRequested && !list.isComputingLayout } }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 7000
        while (SystemClock.uptimeMillis() < deadline) { if (main(condition)) return; SystemClock.sleep(40) }
        error("等待真实窗口状态超时")
    }
    private fun <T> main(block: () -> T): T {
        var value: Result<T>? = null
        instrumentation.runOnMainSync { value = runCatching(block) }
        return value!!.getOrThrow()
    }
    @Suppress("UNCHECKED_CAST") private fun <T> field(name: String): T = DetailActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity) as T
}
