package com.ywwynm.everythingdone.views.particledismiss

import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.views.ScrollAwareColumn
import org.json.JSONObject
import java.io.File

/** 复用真实外观面板布局，A 颜色页 → 关闭 → B 外观页；固定标题，不访问或修改用户记事。 */
class ParticlePanelReuseProbeActivity : AppCompatActivity() {
    private lateinit var panel: ScrollAwareColumn
    private lateinit var output: File
    private val animator by lazy { ParticlePanelAnimator(this) }
    private val result = JSONObject()
    private var phase = 0
    private var phaseAt = 0L
    private var snapshot: Bitmap? = null
    private var done = false
    private fun tree(): org.json.JSONArray {
        val data = org.json.JSONArray()
        fun visit(v: View) {
            data.put(JSONObject().put("id", if(v.id == View.NO_ID) "none" else resources.getResourceEntryName(v.id))
                .put("at", "${v.left},${v.top},${v.right},${v.bottom}").put("alpha", v.alpha)
                .put("visibility", v.visibility).put("text", (v as? TextView)?.text?.toString()))
            if(v is ViewGroup) for(i in 0 until v.childCount) visit(v.getChildAt(i))
        }
        visit(panel); return data
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val run = intent.getStringExtra("run_id") ?: "latest"
        require(run.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        output = File(getExternalFilesDir(null), "particle-panel-reuse/$run").apply { mkdirs() }
        val root = FrameLayout(this).apply { setBackgroundColor(0xff566c7a.toInt()) }
        setContentView(root)
        panel = layoutInflater.inflate(R.layout.panel_thing_card_appearance, root, false) as ScrollAwareColumn
        panel.maxMeasuredHeightPx = (resources.displayMetrics.heightPixels * .6).toInt()
        root.addView(panel)
        panel.visibility = View.VISIBLE
        page(false, "A 的外观")
        phaseAt = SystemClock.uptimeMillis()
        panel.postOnAnimation { sample() }
    }
    private fun page(color: Boolean, title: String) {
        panel.findViewById<TextView>(R.id.tv_thing_card_appearance_title).text = title
        for (id in intArrayOf(R.id.ll_thing_card_appearance_title_row, R.id.ll_tca_appearance_body))
            panel.findViewById<View>(id).visibility = if (color) View.GONE else View.VISIBLE
        for (id in intArrayOf(R.id.ll_tca_color_page_title, R.id.scroll_tca_color_page))
            panel.findViewById<View>(id).visibility = if (color) View.VISIBLE else View.GONE
    }
    private fun sample() {
        if (done) return
        try {
            val elapsed = SystemClock.uptimeMillis() - phaseAt
            val decor = window.decorView as ViewGroup
            val overlays = (0 until decor.childCount).mapNotNull { decor.getChildAt(it) as? ParticleDismissOverlay }
            when (phase) {
                0 -> if (elapsed > 500) { page(true, "A 的颜色"); phase = 1; phaseAt = SystemClock.uptimeMillis() }
                1 -> if (elapsed > 600) {
                    animator.dismiss(panel) { panel.visibility = View.GONE }
                    phase = 2
                }
                2 -> if (panel.visibility == View.GONE && overlays.isEmpty()) {
                    page(false, "B 的外观")
                    panel.visibility = View.VISIBLE
                    animator.appear(panel)
                    phase = 3; phaseAt = SystemClock.uptimeMillis()
                }
                3 -> {
                    val overlay = overlays.firstOrNull { !it.isDisappearance }
                    if (snapshot == null && overlay != null) {
                        val spec = ParticleDismissOverlay::class.java.getDeclaredField("spec").apply { isAccessible = true }
                            .get(overlay) as ParticleDismissSpec
                        snapshot = spec.snapshot.copy(Bitmap.Config.ARGB_8888, false)
                        result.put("captureMs", elapsed).put("transitionRunningAtCapture", panel.layoutTransition?.isRunning == true)
                        result.put("inputTree", tree())
                    }
                    if (snapshot != null && overlays.isEmpty() && panel.alpha == 1f && elapsed > 1500) {
                        val expected = ParticleDismissController::class.java.getDeclaredMethod("captureSnapshot", View::class.java, android.content.Context::class.java)
                            .apply { isAccessible = true }.invoke(ParticleDismissController, panel, this) as Bitmap
                        result.put("matchesFinal", expected.sameAs(snapshot))
                            .put("finalTree", tree())
                            .put("inputWidth", snapshot!!.width).put("inputHeight", snapshot!!.height)
                            .put("finalWidth", expected.width).put("finalHeight", expected.height)
                        File(output, "input.png").outputStream().use { snapshot!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        File(output, "final.png").outputStream().use { expected.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        finishResult(); return
                    }
                    check(elapsed < 8000) { "新面板未完成" }
                }
            }
            window.decorView.postOnAnimation { sample() }
        } catch (e: Throwable) { result.put("error", e.stackTraceToString()); finishResult() }
    }
    private fun finishResult() {
        done = true; snapshot?.recycle()
        File(output, "result.json").writeText(result.toString(2)); finish()
    }
}
