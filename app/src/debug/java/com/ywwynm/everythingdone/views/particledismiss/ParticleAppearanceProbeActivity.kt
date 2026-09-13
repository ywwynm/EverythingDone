package com.ywwynm.everythingdone.views.particledismiss

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.fragments.AlertDialogFragment
import com.ywwynm.everythingdone.fragments.BaseDialogFragment
import com.ywwynm.everythingdone.fragments.ChooserDialogFragment
import com.ywwynm.everythingdone.fragments.DebugUpdateDialogFragment
import com.ywwynm.everythingdone.fragments.LongTextDialogFragment
import com.ywwynm.everythingdone.fragments.ColorInfoDialogFragment
import com.ywwynm.everythingdone.helpers.DebugApkUpdateInfo
import com.ywwynm.everythingdone.model.ThingBackground
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * 固定虚构内容，调用真实弹窗及生产出现管线；不访问用户记事、不改动画设置。
 * 通过反射复制实际动画输入，避免为了测试向生产渲染器添加截屏或配色分支。
 */
class ParticleAppearanceProbeActivity : AppCompatActivity() {
    private lateinit var fragment: BaseDialogFragment
    private lateinit var output: File
    private var input: Bitmap? = null
    private var completed = false
    private var stableFrames = 0
    private var startedAt = 0L
    private var snapshotAt = 0L
    private var capturedOrigin = floatArrayOf(0f, 0f)
    private val layouts = JSONArray()
    private val kind get() = intent.getStringExtra("kind") ?: "chooser"
    private val palette get() = intent.getStringExtra("palette") ?: "gradient"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runId = intent.getStringExtra("run_id") ?: "latest"
        require(runId.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        output = File(getExternalFilesDir(null), "particle-appearance-probe/$runId").apply { mkdirs() }
        setContentView(TextView(this).apply {
            text = "弹窗配色验证\n固定测试内容，不修改记事"
            setTextColor(Color.DKGRAY)
            setBackgroundColor(0xffbbcbd4.toInt())
            setPadding(32, 80, 32, 32)
        })
        window.decorView.post {
            try {
                check(BaseDialogFragment.particleAnimationMode(this) and
                    BaseDialogFragment.PARTICLE_ANIMATION_SHOW_BIT != 0) { "当前设置未启用出现动画" }
                val first = Color.rgb(242, 80, 65)
                val last = Color.rgb(30, 120, 235)
                val background = when (palette) {
                    "pure" -> ThingBackground.pure(first)
                    "reverse" -> ThingBackground.gradient(last, first, ThingBackground.Orientation.L_R)
                    else -> ThingBackground.gradient(first, last, ThingBackground.Orientation.L_R)
                }
                fragment = if (kind == "update") DebugUpdateDialogFragment().apply {
                    val notes = (1..36).joinToString("\n\n") { "第 $it 项：固定更新说明，用于检查长内容滚动区域与出现动画的尺寸交接。" }
                    setUpdateInfo(DebugApkUpdateInfo(versionName = "测试版本", debugUpdateCode = 1L,
                        releaseNotes = if (palette == "short") "固定短说明。" else notes), "28 MB", "2026-09-13")
                } else if (kind == "color-info") ColorInfoDialogFragment().apply {
                    setThingBackground(background)
                } else if (kind == "long-text") LongTextDialogFragment().apply {
                    setTitle("长内容尺寸验证")
                    setContent((1..32).joinToString("\n") { "第 $it 行固定内容，不访问或修改用户记事。" })
                    setConfirmText("确认")
                    setAccentBackground(background)
                } else if (kind == "alert") AlertDialogFragment().apply {
                    setTitle("确认测试操作")
                    setContent("这是一条固定测试提示，不会执行实际操作。")
                    setTitleBackground(background)
                    setConfirmBackground(background)
                    setConfirmText("确认操作")
                } else ChooserDialogFragment().apply {
                    setTitle("选择显示方案")
                    setConfirmText("确认选择")
                    setAccentBackground(background)
                    setItems(mutableListOf("跟随系统设置", "简体中文", "English"))
                    setShouldShowMore(false)
                }
                startedAt = SystemClock.uptimeMillis()
                fragment.showNow(supportFragmentManager, "appearance-probe")
                sample()
            } catch (e: Exception) { fail(e) }
        }
    }

    private fun findOverlay(view: View): ParticleDismissOverlay? {
        if (view is ParticleDismissOverlay) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            findOverlay(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun sample() {
        if (completed || isDestroyed) return
        try {
            val overlay = findOverlay(window.decorView)
            if (input == null && overlay != null) {
                val field = ParticleDismissOverlay::class.java.getDeclaredField("spec").apply { isAccessible = true }
                val spec = field.get(overlay) as ParticleDismissSpec
                check(spec.reverse) { "抓到了消散动画而非出现动画" }
                input = checkNotNull(spec.snapshot.copy(Bitmap.Config.ARGB_8888, false))
                snapshotAt = SystemClock.uptimeMillis()
                capturedOrigin = floatArrayOf(spec.originXPx, spec.originYPx)
            }
            val decor = fragment.dialog?.window?.decorView
            if (decor != null) {
                val location = IntArray(2); decor.getLocationOnScreen(location)
                val previous = if (layouts.length() == 0) null else layouts.getJSONObject(layouts.length() - 1)
                if (previous == null || previous.getInt("width") != decor.width || previous.getInt("height") != decor.height ||
                    previous.getInt("x") != location[0] || previous.getInt("y") != location[1]) {
                    layouts.put(JSONObject().put("atMs", SystemClock.uptimeMillis() - startedAt)
                        .put("width", decor.width).put("height", decor.height).put("x", location[0]).put("y", location[1]))
                }
            }
            stableFrames = if (input != null && overlay == null && decor?.alpha == 1f) stableFrames + 1 else 0
            if (stableFrames >= 3 && decor != null) {
                val method = ParticleDismissController::class.java.getDeclaredMethod(
                    "captureSnapshot", View::class.java, android.content.Context::class.java
                ).apply { isAccessible = true }
                val finalImage = checkNotNull(method.invoke(ParticleDismissController, decor, this) as? Bitmap)
                val regions = regions(decor)
                val finalLocation = IntArray(2).also { decor.getLocationOnScreen(it) }
                val hostLocation = IntArray(2).also { window.decorView.getLocationOnScreen(it) }
                val finalOrigin = listOf(finalLocation[0] - hostLocation[0], finalLocation[1] - hostLocation[1])
                val elapsed = SystemClock.uptimeMillis() - startedAt
                completed = true
                val original = checkNotNull(input)
                Thread {
                    try {
                        save(original, "animation-input.png")
                        save(finalImage, "shown-dialog.png")
                        val result = JSONObject().put("kind", kind).put("palette", palette)
                            .put("inputWidth", original.width).put("inputHeight", original.height)
                            .put("inputOrigin", JSONArray(capturedOrigin.toList())).put("layouts", layouts)
                            .put("finalOrigin", JSONArray(finalOrigin))
                            .put("positionMatches", capturedOrigin[0] == finalOrigin[0].toFloat() && capturedOrigin[1] == finalOrigin[1].toFloat())
                            .put("snapshotMs", snapshotAt - startedAt).put("completedMs", elapsed)
                            .put("sizeMatches", original.width == finalImage.width && original.height == finalImage.height)
                            .put("width", original.width).put("height", original.height)
                        if (original.width != finalImage.width || original.height != finalImage.height) {
                            File(output, "result.json").writeText(result.put("error", "出现快照尺寸与最终弹窗不同").toString(2))
                            return@Thread
                        }
                        val a = IntArray(original.width * original.height)
                        val b = IntArray(a.size)
                        original.getPixels(a, 0, original.width, 0, 0, original.width, original.height)
                        finalImage.getPixels(b, 0, finalImage.width, 0, 0, finalImage.width, finalImage.height)
                        val metrics = JSONArray()
                        for ((name, rect) in regions) metrics.put(compare(name, rect, a, b, original.width))
                        result.put("regions", metrics)
                        result.put("whole", compare("whole", Rect(0, 0, original.width, original.height), a, b, original.width))
                        File(output, "result.json").writeText(result.toString(2))
                    } catch (e: Exception) {
                        File(output, "result.json").writeText(JSONObject().put("error", e.stackTraceToString()).toString(2))
                    } finally {
                        original.recycle()
                        finalImage.recycle()
                        runOnUiThread { finish() }
                    }
                }.start()
                return
            }
            check(SystemClock.uptimeMillis() - startedAt < 8000) { "未完成出现动画或无法取得其输入快照" }
            window.decorView.postOnAnimation { sample() }
        } catch (e: Exception) { fail(e) }
    }

    private fun regions(decor: View): List<Pair<String, Rect>> {
        val ids = if (kind == "color-info") listOf(
            "title" to R.id.tv_title_color_info, "confirm" to R.id.tv_confirm_as_bt_color_info,
            "content" to R.id.sv_color_info
        ) else if (kind == "update") listOf(
            "title" to R.id.tv_title_debug_update, "confirm" to R.id.tv_download_as_bt_debug_update,
            "content" to R.id.sv_debug_update
        ) else if (kind == "long-text") listOf(
            "title" to R.id.tv_title_long_text, "confirm" to R.id.tv_confirm_as_bt_long_text,
            "content" to R.id.sv_long_text
        ) else if (kind == "alert") listOf(
            "title" to R.id.tv_title_alert, "confirm" to R.id.tv_confirm_as_bt_alert,
            "content" to R.id.tv_content_alert
        ) else listOf(
            "title" to R.id.tv_title_fragment_chooser, "confirm" to R.id.tv_confirm_as_bt_fragment_chooser,
            "choices" to R.id.rv_fragment_chooser
        )
        val origin = IntArray(2).also { decor.getLocationOnScreen(it) }
        return ids.map { (name, id) ->
            val view = checkNotNull(decor.findViewById<View>(id))
            val p = IntArray(2).also { view.getLocationOnScreen(it) }
            name to Rect(p[0] - origin[0], p[1] - origin[1],
                p[0] - origin[0] + view.width, p[1] - origin[1] + view.height)
        }
    }

    private fun compare(name: String, rect: Rect, a: IntArray, b: IntArray, width: Int): JSONObject {
        var changed = 0
        var maxDelta = 0
        var total = 0L
        for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) {
            val i = y * width + x
            val delta = max(abs(Color.red(a[i]) - Color.red(b[i])),
                max(abs(Color.green(a[i]) - Color.green(b[i])), abs(Color.blue(a[i]) - Color.blue(b[i]))))
            if (delta > 2) changed++
            maxDelta = max(maxDelta, delta)
            total += delta
        }
        return JSONObject().put("name", name).put("bounds", JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom)))
            .put("changedPixels", changed).put("maxChannelDelta", maxDelta)
            .put("meanMaxChannelDelta", total.toDouble() / (rect.width() * rect.height()))
    }

    private fun save(bitmap: Bitmap, name: String) {
        File(output, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun fail(e: Exception) {
        completed = true
        File(output, "result.json").writeText(JSONObject().put("error", e.stackTraceToString()).toString(2))
        finish()
    }
}
