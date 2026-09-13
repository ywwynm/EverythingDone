package com.ywwynm.everythingdone.views.particledismiss

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView
import com.ywwynm.everythingdone.activities.ParticleDialogContentActivity
import com.ywwynm.everythingdone.fragments.AudioPlayDialogFragment
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.fragments.BaseDialogFragment
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.views.recording.fablesol.WaveVisualizerFableSolHost
import org.json.JSONObject
import java.io.File

/** 固定内容，真实窗口及 FableSol 渲染器；不读取记事、不启动录音。 */
class ParticleCoverageProbeActivity : ParticleDialogContentActivity() {
    private var fragment: BaseDialogFragment? = null
    private var panel: View? = null
    private val panelAnimator by lazy { ParticlePanelAnimator(this) }
    private val kind get() = intent.getStringExtra("kind") ?: "plain"
    override val useParticleContentDialog get() = kind == "floating"
    private lateinit var output: File
    private val result = JSONObject()
    private var started = 0L
    private var dismissAt = 0L
    private var completed = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val run = intent.getStringExtra("run_id") ?: "latest"
        require(run.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        output = File(getExternalFilesDir(null), "particle-coverage/$run").apply { mkdirs() }
        result.put("kind", kind).put("appearance", false).put("dismissal", false)
        if (kind != "transparent") setContentView(TextView(this).apply {
            setBackgroundColor(0xff566c7a.toInt()); text = "固定动画接入测试"
        })
        started = SystemClock.uptimeMillis()
        if (kind == "floating") {
            setContentView(TextView(this).apply {
                setBackgroundColor(Color.WHITE); text = "浮动 Activity 内容\n关闭后粒子须播放完整"; textSize = 20f
                setTextColor(Color.RED); setPadding(40, 40, 40, 40)
            }, ViewGroup.LayoutParams((280 * resources.displayMetrics.density).toInt(), -2))
        } else if (kind == "panel") {
            val root = FrameLayout(this).apply { setBackgroundColor(0xff566c7a.toInt()) }
            val dp = resources.displayMetrics.density
            panel = TextView(this).apply {
                text = "页面内面板\n出现、返回消散及恢复布局"; textSize = 20f; setTextColor(Color.RED)
                setPadding(40, 40, 40, 40); setBackgroundColor(Color.WHITE)
            }
            root.addView(panel, FrameLayout.LayoutParams((280*dp).toInt(), (240*dp).toInt(), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
            setContentView(root)
            window.decorView.post { panelAnimator.appear(panel!!) }
        } else if (kind.startsWith("audio-play")) {
            // 生成静音 WAV，只验证真实播放器/海浪窗口，不访问音频附件、不使用麦克风。
            val rate = 16000; val size = rate * 2 * 6
            val bytes = java.nio.ByteBuffer.allocate(44 + size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bytes.put("RIFF".toByteArray()).putInt(36 + size).put("WAVEfmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(1).putInt(rate).putInt(rate*2).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(size)
            val wav = File(output, "silence.wav").apply { writeBytes(bytes.array()) }
            AudioPlayDialogFragment.show(supportFragmentManager, listOf(wav.path), 0)
        } else {
            fragment = ParticleCoverageDialog().apply { arguments = Bundle().apply { putString("kind", kind) } }
            fragment!!.show(supportFragmentManager, "coverage")
        }
        if (kind == "surface-quick") window.decorView.postDelayed({
            dismissAt = SystemClock.uptimeMillis()
            fragment?.dismiss()
        }, 100)
        window.decorView.postOnAnimation { sample() }
    }
    private fun sample() {
        if (completed || isDestroyed) return
        try {
            val root = window.decorView as ViewGroup
            if (fragment == null) fragment = supportFragmentManager.fragments.filterIsInstance<BaseDialogFragment>().firstOrNull()
            val overlays = (0 until root.childCount).mapNotNull { root.getChildAt(it) as? ParticleDismissOverlay }
            overlays.forEach { overlay ->
                val field = ParticleDismissOverlay::class.java.getDeclaredField("spec").apply { isAccessible = true }
                val spec = field.get(overlay) as ParticleDismissSpec
                val name = if (spec.reverse) "appearance" else "dismissal"
                if (!result.getBoolean(name)) {
                    File(output, "$name.png").outputStream().use { spec.snapshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    result.put(name, true).put("${name}Ms", SystemClock.uptimeMillis() - started)
                    result.put("${name}DurationSeconds", if (spec.reverse) spec.playbackDurationS else 1f)
                    overlay.afterRelease { result.put("${name}ReleasedMs", SystemClock.uptimeMillis() - started) }
                }
            }
            val elapsed = SystemClock.uptimeMillis() - started
            if (dismissAt == 0L && elapsed > 2500 && overlays.isEmpty() &&
                (fragment?.dialog?.isShowing == true || panel?.visibility == View.VISIBLE)) {
                val surfaceInfo = org.json.JSONArray()
                fun inspect(view: View) {
                    if (view is SurfaceView) {
                        val bounds = android.graphics.Rect()
                        surfaceInfo.put(JSONObject().put("class", view.javaClass.simpleName)
                            .put("w", view.width).put("h", view.height).put("visibility", view.visibility)
                            .put("shown", view.isShown).put("visibleRect", view.getGlobalVisibleRect(bounds))
                            .put("bounds", bounds.toShortString()).put("surfaceValid", view.holder.surface.isValid))
                        if (view.width > 0 && view.height > 0 && view.holder.surface.isValid) {
                            val copy = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                            PixelCopy.request(view, copy, { status ->
                                result.put("surfaceCopy", status)
                                File(output, "surface-raw.png").outputStream().use { copy.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                copy.recycle()
                            }, android.os.Handler(mainLooper))
                        }
                    }
                    if (view is ViewGroup) for (i in 0 until view.childCount) inspect(view.getChildAt(i))
                }
                (fragment?.dialog?.window?.decorView ?: panel)?.let(::inspect)
                result.put("surfaces", surfaceInfo)
                dismissAt = SystemClock.uptimeMillis()
                if (kind.endsWith("-handoff")) {
                    verifySurfaceHandoff()
                    return
                }
                if (panel != null) {
                    panelAnimator.dismissFromBack()
                    if (!panelAnimator.dismiss(panel!!) { panel!!.visibility = View.GONE }) panel!!.visibility = View.GONE
                } else {
                    fragment?.dismiss()
                    if (kind == "finish-during-capture") finish()
                }
            }
            if (dismissAt != 0L && SystemClock.uptimeMillis() - dismissAt > 1800 && overlays.isEmpty()) {
                result.put("removed", fragment?.dialog?.isShowing != true && panel?.visibility != View.VISIBLE)
                completed = true; File(output, "result.json").writeText(result.toString(2)); finish(); return
            }
            check(elapsed < 12000) { "动画或窗口没有完成清理" }
            window.decorView.postOnAnimation { sample() }
        } catch (error: Throwable) {
            completed = true
            File(output, "result.json").writeText(result.put("error", error.stackTraceToString()).toString(2)); finish()
        }
    }

    /** 最小交接复现：独立 Surface 已退出而窗口还在时，保护前后的缓冲差异。 */
    private fun verifySurfaceHandoff() {
        val dialog = checkNotNull(fragment?.dialog)
        val sourceWindow = checkNotNull(dialog.window)
        val root = sourceWindow.decorView
        val surfaces = mutableListOf<SurfaceView>()
        fun visit(v: View) {
            if (v is SurfaceView && v.isShown) surfaces += v
            if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
        }
        visit(root)
        check(surfaces.isNotEmpty())
        val hold = ParticleSnapshot.hold(root)
        val handler = android.os.Handler(mainLooper)
        val images = mutableListOf<Bitmap>()
        var cover: (() -> Unit)? = null
        var ended = false
        fun finishTest(error: String? = null) {
            if (ended) return
            ended = true
            if (error != null) result.put("error", error)
            cover?.invoke(); hold()
            surfaces.forEach { it.visibility = View.VISIBLE }
            result.put("handoff", true)
            // 位图写入与数值比较只在诊断运行使用，不纳入帧率结果。
            images.forEachIndexed { i, bitmap ->
                File(output, "handoff-$i.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            fragment?.dismiss()
            dismissAt = SystemClock.uptimeMillis()
            window.decorView.postOnAnimation { sample() }
        }
        fun captureAfterCommit(next: (Bitmap) -> Unit) {
            root.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    root.viewTreeObserver.removeOnPreDrawListener(this)
                    root.viewTreeObserver.registerFrameCommitCallback {
                        handler.post {
                            if (!ended) {
                                val at = IntArray(2); root.getLocationInWindow(at)
                                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                                try {
                                    PixelCopy.request(sourceWindow, android.graphics.Rect(at[0], at[1], at[0]+root.width, at[1]+root.height), bitmap, { status ->
                                        if (ended) bitmap.recycle()
                                        else if (status == PixelCopy.SUCCESS) { images += bitmap; next(bitmap) }
                                        else { bitmap.recycle(); finishTest("Window PixelCopy: $status") }
                                    }, handler)
                                } catch (e: Exception) { bitmap.recycle(); finishTest(e.toString()) }
                            }
                        }
                    }
                    return true
                }
            })
            root.invalidate()
        }
        ParticleSnapshot.capture(sourceWindow, root) { snapshot ->
            if (snapshot == null) { finishTest("原图捕获失败"); return@capture }
            images += snapshot
            cover = ParticleSnapshot.coverSurfaces(root, snapshot)
            captureAfterCommit {
                surfaces.forEach { it.visibility = View.INVISIBLE }
                captureAfterCommit {
                    cover?.invoke(); cover = null
                    captureAfterCommit { finishTest() }
                }
            }
        }
        handler.postDelayed({ if (!ended) finishTest("Surface 交接诊断超时") }, 3500)
    }

    override fun finish() {
        if (kind == "finish-during-capture" &&
            ParticleDismissController.finishAfterAnimations(this) { super.finish() }) return
        super.finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::output.isInitialized && kind in setOf("floating", "finish-during-capture")) {
            result.put("removed", true).put("hostWaitMs", SystemClock.uptimeMillis() - dismissAt)
            File(output, "result.json").writeText(result.toString(2))
        }
    }
}

class ParticleCoverageDialog : BaseDialogFragment() {
    override fun getLayoutResource() = R.layout.fragment_thing_doing
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        val dp = context.resources.displayMetrics.density
        val kind = requireArguments().getString("kind")
        val root = FrameLayout(context).apply {
            minimumWidth = (280 * dp).toInt(); minimumHeight = (350 * dp).toInt(); setBackgroundColor(Color.WHITE)
        }
        if (kind?.startsWith("surface") == true) root.addView(WaveVisualizerFableSolHost(context).apply {
            setThingBackground(ThingBackground.pure(0xff149ca3.toInt()))
            setPresentationAlpha(1f)
            val field = WaveVisualizerFableSolHost::class.java.getDeclaredField("canvasFallback").apply { isAccessible = true }
            check(field.get(this) == null) { "GLES 正常路径提前创建了 Canvas 备用实例" }
            if (kind == "surface-fallback") {
                setSimulationPaused(true); setFrozen(true)
                WaveVisualizerFableSolHost::class.java.getDeclaredMethod("activateCanvasFallback")
                    .apply { isAccessible = true }.invoke(this)
                val fallback = checkNotNull(field.get(this))
                for (flag in listOf("simulationPaused", "frozen")) {
                    check(fallback.javaClass.getDeclaredField(flag).apply { isAccessible = true }.getBoolean(fallback))
                }
                setFrozen(false); setSimulationPaused(false)
            }
        }, FrameLayout.LayoutParams(-1, -1))
        if (kind == "hidden-surface") root.addView(SurfaceView(context).apply { visibility = View.GONE })
        if (kind == "texture") root.addView(TextureView(context).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: android.graphics.SurfaceTexture, width: Int, height: Int) {
                    val surface = Surface(texture); val canvas = surface.lockCanvas(null)
                    canvas.drawColor(0xff149ca3.toInt()); surface.unlockCanvasAndPost(canvas); surface.release()
                }
                override fun onSurfaceTextureSizeChanged(s: android.graphics.SurfaceTexture, w: Int, h: Int) = Unit
                override fun onSurfaceTextureDestroyed(s: android.graphics.SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(s: android.graphics.SurfaceTexture) = Unit
            }
        }, FrameLayout.LayoutParams((200 * dp).toInt(), (210 * dp).toInt()).apply {
            leftMargin = (40 * dp).toInt(); topMargin = (60 * dp).toInt()
        })
        root.addView(TextView(context).apply {
            text = "标题与独立画面\n前景文字必须保留"; textSize = 20f; setTextColor(Color.RED)
            setPadding((24 * dp).toInt(), (24 * dp).toInt(), 0, 0)
        })
        mContentView = root; return root
    }
}
