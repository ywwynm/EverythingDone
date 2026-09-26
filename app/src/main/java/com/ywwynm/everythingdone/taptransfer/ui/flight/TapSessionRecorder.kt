package com.ywwynm.everythingdone.taptransfer.ui.flight

import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.view.ViewTreeObserver
import androidx.recyclerview.widget.RecyclerView
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.taptransfer.TapTransfer
import com.ywwynm.everythingdone.views.particledismiss.ParticleHardwareSnapshot
import com.ywwynm.everythingdone.views.particledismiss.ParticlePerfTuning
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 调试：把一次完整的碰一碰会话（发送或接收）记下来，桌面模拟器（`docs/features/thing-transfer/particle-sim`）
 * 据此把整段画面重放出来——事件时间线、各层的离屏截图、列表让位时每张卡片逐帧的位置，以及其中的
 * 粒子飞行（[TapFixtureDump] 写在本会话目录的 `flight-<n>/` 下）。
 *
 * 只在 debug 包、且 `tap_transfer_debug.xml` 的 `dump_fixtures` 为真时工作；其余时候 [start] 返回 null，
 * 各处钩子拿到的 [current] 也是 null，什么都不做。
 *
 * 离屏截图不会让屏幕闪：[captureView] 在同一条主线程消息里临时隐去 `hide` 里的视图、同步录下显示列表、
 * 再恢复，这之间不会出任何一帧；出图与写盘在别的线程。
 */
internal class TapSessionRecorder private constructor(
    private val activity: Activity,
    val dir: File,
    val kind: String
) {
    private val main = Handler(Looper.getMainLooper())
    private val t0 = SystemClock.uptimeMillis()
    private val events = JSONArray()
    private var finished = false
    private var flights = 0

    /** 节流中的截图通道：通道名 → 状态。 */
    private class Channel(var index: Int = 0, var pending: Boolean = false, var dirty: Boolean = false,
                          var lastAt: Long = 0L, var request: (() -> Unit)? = null)

    private val channels = HashMap<String, Channel>()

    fun now(): Long = SystemClock.uptimeMillis() - t0

    /** 把一个 uptime 毫秒数换成会话时间。 */
    fun at(uptimeMs: Long): Long = uptimeMs - t0

    fun event(type: String, fill: JSONObject.() -> Unit = {}) {
        if (finished) return
        events.put(JSONObject().put("t", now()).put("type", type).apply(fill))
    }

    /** 下一段粒子飞行的落盘目录。 */
    fun nextFlightDir(): File = File(dir, "flight-${++flights}").also { it.mkdirs() }

    /**
     * 离屏抓 [view] 的内容（不含它自己的平移、缩放与透明度，那些由模拟器按事件重放），写
     * `<name>.png`，记一条 `capture` 事件：视图的布局位置（父视图坐标）、父视图在 DecorView 里的位置。
     */
    fun captureView(name: String, view: View, hide: List<View> = emptyList(), extra: JSONObject.() -> Unit = {}) {
        if (finished || view.width <= 0 || view.height <= 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val t = now()
        val parent = view.parent as? View
        val parentAt = IntArray(2)
        val decorAt = IntArray(2)
        parent?.getLocationInWindow(parentAt)
        activity.window.decorView.getLocationInWindow(decorAt)
        val hidden = hide.filter { it.visibility == View.VISIBLE }
        hidden.forEach { it.visibility = View.INVISIBLE }
        try {
            ParticleHardwareSnapshot.capture(view, null, null) { bitmap ->
                if (bitmap == null) Log.w(TAG, "离屏截图失败 $name") else write(name, bitmap)
            }
        } finally {
            hidden.forEach { it.visibility = View.VISIBLE }
        }
        event("capture") {
            put("name", name)
            put("captured", t)
            put("layout", JSONArray().put(view.left).put(view.top).put(view.right).put(view.bottom))
            put("parent", JSONArray().put(parentAt[0] - decorAt[0]).put(parentAt[1] - decorAt[1]))
            put("size", JSONArray().put(view.width).put(view.height))
            extra()
        }
    }

    /**
     * 节流的截图通道：内容频繁变化的层（按钮下的提示、面板上的进度）最多每 [minIntervalMs] 抓一张，
     * 等变化之后的那一帧布局好再抓。
     */
    fun captureSoon(channel: String, view: View, minIntervalMs: Long = 80L, hide: () -> List<View> = { emptyList() }) {
        if (finished) return
        val c = channels.getOrPut(channel) { Channel() }
        c.request = {
            val t = now()
            c.lastAt = t
            captureView("$channel-${c.index++}", view, hide()) { put("channel", channel) }
        }
        if (c.pending) {
            c.dirty = true
            return
        }
        c.pending = true
        val wait = (c.lastAt + minIntervalMs - now()).coerceAtLeast(0L)
        main.postDelayed({
            TapParticleFlight.afterFrame(view) {
                c.request?.invoke()
                c.pending = false
                if (c.dirty) {
                    c.dirty = false
                    captureSoon(channel, view, minIntervalMs, hide)
                }
            }
        }, wait)
    }

    /** 整个窗口的画面（PixelCopy，不含 SurfaceView），写 `<name>.png`。 */
    fun captureWindow(name: String) {
        if (finished || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val window = activity.window ?: return
        val decor = window.decorView
        if (decor.width <= 0 || decor.height <= 0) return
        val t = now()
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(window, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) write(name, bitmap) else bitmap.recycle()
            }, main)
            event("capture") { put("name", name); put("captured", t); put("window", true) }
        } catch (error: IllegalArgumentException) {
            bitmap.recycle()
        }
    }

    // ---------------------------------------------------------------- 列表卡片的逐帧位置

    private var tracked: RecyclerView? = null
    private var pinnedBefore = 1
    private var trackListener: ViewTreeObserver.OnPreDrawListener? = null
    private val holderIds = IdentityHashMap<RecyclerView.ViewHolder, Int>()
    private var lastFrame = ""

    /**
     * 逐帧记下 [recyclerView] 里每张卡片的位置（容器坐标，含列表与卡片自己的平移）与透明度，直到
     * [stopTracking]。卡片按 ViewHolder 编号：让位时同一张卡片始终是同一个 ViewHolder。
     */
    /**
     * 逐帧记列表里各项的位置（插入与让位）。[pinnedBefore] 是收到的记事插入的位置：它之前（头部占位项之后）
     * 的几项是置顶的，记下它们的编号，模拟器据此预计落点。
     */
    fun trackItems(recyclerView: RecyclerView, pinnedBefore: Int) {
        if (finished || tracked != null) return
        tracked = recyclerView
        this.pinnedBefore = pinnedBefore
        val listener = ViewTreeObserver.OnPreDrawListener {
            recordItems(recyclerView)
            true
        }
        trackListener = listener
        recyclerView.viewTreeObserver.addOnPreDrawListener(listener)
        recordItems(recyclerView)
    }

    fun stopTracking() {
        val rv = tracked ?: return
        trackListener?.let { if (rv.viewTreeObserver.isAlive) rv.viewTreeObserver.removeOnPreDrawListener(it) }
        recordItems(rv)
        tracked = null
        trackListener = null
    }

    private fun recordItems(rv: RecyclerView) {
        val items = JSONArray()
        val pinned = JSONArray()
        val keys = StringBuilder()
        for (k in 0 until rv.childCount) {
            val child = rv.getChildAt(k)
            val holder = rv.getChildViewHolder(child) ?: continue
            val id = holderIds.getOrPut(holder) { holderIds.size + 1 }
            if (holder.bindingAdapterPosition in 1 until pinnedBefore) pinned.put(id)
            val left = rv.left + rv.translationX + child.left + child.translationX
            val top = rv.top + rv.translationY + child.top + child.translationY
            val visible = child.visibility == View.VISIBLE
            items.put(JSONArray().put(id).put(left.toDouble()).put(top.toDouble())
                .put(child.width).put(child.height).put(if (visible) child.alpha.toDouble() else 0.0))
            keys.append(id).append(':').append(left.toInt()).append(',').append(top.toInt()).append(',')
                .append(if (visible) (child.alpha * 100).toInt() else 0).append(';')
        }
        val signature = keys.toString()
        if (signature == lastFrame) return
        lastFrame = signature
        event("items") {
            put("list", JSONArray().put(rv.left).put(rv.top + rv.translationY.toDouble()).put(rv.right)
                .put(rv.bottom + rv.translationY.toDouble()))
            put("items", items)
            if (pinned.length() > 0) put("pinned", pinned)
        }
    }

    // ---------------------------------------------------------------- 收尾

    fun finish() {
        if (finished) return
        stopTracking()
        event("end")
        finished = true
        val decor = activity.window.decorView
        val metrics = activity.resources.displayMetrics
        val session = JSONObject()
            .put("kind", kind)
            .put("device", Build.MODEL)
            .put("screen", JSONArray().put(decor.width).put(decor.height))
            .put("density", metrics.density.toDouble())
            .put("cell_dp", ParticlePerfTuning.microflakeCellDp.toDouble())
            .put("jacobi_iterations", ParticlePerfTuning.jacobiIterations)
            .put("srgb_accumulation", ParticlePerfTuning.phase2(ParticlePerfTuning.P2_SRGB8_ACCUMULATION))
            .put("params", TapParticleParams.get(activity).asJson())
            .put("events", events)
        IO.execute {
            runCatching { File(dir, "session.json").writeText(session.toString(1), Charsets.UTF_8) }
                .onFailure { Log.w(TAG, "写会话失败", it) }
            Log.i(TAG, "已写 ${dir.name}/session.json，${events.length()} 条事件")
        }
        if (current === this) current = null
    }

    private fun write(name: String, bitmap: Bitmap) {
        IO.execute {
            runCatching {
                FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }.onFailure { Log.w(TAG, "写 $name 失败", it) }
            bitmap.recycle()
        }
    }

    companion object {
        private const val TAG = "TapSession"
        private val IO = Executors.newSingleThreadExecutor()

        /** 正在记录的会话；没有打开落盘开关时恒为 null。 */
        @Volatile var current: TapSessionRecorder? = null
            private set

        /** 开始记录一次会话（发送或接收），同时只有一个；上一个没收尾的先收尾。 */
        fun start(activity: Activity, kind: String): TapSessionRecorder? {
            if (!BuildConfig.DEBUG || !TapTransfer.isFixtureDumpEnabled(activity)) return null
            current?.finish()
            val root = activity.getExternalFilesDir("tap-fixtures") ?: return null
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
            val dir = File(root, "$stamp-$kind-session")
            if (!dir.mkdirs()) return null
            return TapSessionRecorder(activity, dir, kind).also { current = it }
        }
    }
}
