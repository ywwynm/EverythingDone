package com.ywwynm.everythingdone.taptransfer.ui.flight

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.taptransfer.TapTransfer
import com.ywwynm.everythingdone.views.particledismiss.ParticleDismissSpec
import com.ywwynm.everythingdone.views.particledismiss.ParticlePerfTuning
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 调试：把一次碰一碰粒子飞行的输入落盘，桌面模拟器（`docs/features/thing-transfer/particle-sim`）
 * 拿它原样重放——同一张快照、同一个种子、同一套几何。只在 debug 包、且 `tap_transfer_debug.xml`
 * 的 `dump_fixtures` 为真时工作，其余时候 [begin] 直接返回 null。
 *
 * 目录是外部私有目录下的 `tap-fixtures/<时刻>-<send|receive>/`：
 * - `snapshot.png`：交给粒子层的快照（透明底，与 [ParticleDismissSpec.snapshot] 逐像素相同）；
 * - `background.png`：粒子层底下的窗口画面。粒子层是独立的 SurfaceView，窗口截图里没有它：
 *   发送取接管之后（卡片已藏起），接收取颗粒刚开始流出时（边框还在）；
 * - `background-settled.png`（仅接收）：边框淡去之后的同一画面；
 * - `scene.json`：几何、种子、本机粒子设置与当时的参数文件。
 */
internal class TapFixtureDump private constructor(private val activity: Activity, val dir: File) {

    private val main = Handler(Looper.getMainLooper())

    /** 在 [delayMs] 之后截一张窗口画面存为 `<name>.png`。 */
    fun captureWindow(name: String, delayMs: Long = 0L) {
        main.postDelayed({ copyWindow(name) }, delayMs)
    }

    private fun copyWindow(name: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.isDestroyed) return
        val window = activity.window ?: return
        val decor = window.decorView
        if (decor.width <= 0 || decor.height <= 0) return
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(window, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) {
                    write(name, bitmap)
                } else {
                    Log.w(TAG, "窗口截图失败 $name result=$result")
                    bitmap.recycle()
                }
            }, main)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "窗口截图失败 $name", error)
            bitmap.recycle()
        }
    }

    private fun write(name: String, bitmap: Bitmap) {
        IO.execute {
            runCatching {
                FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }.onFailure { Log.w(TAG, "写 $name 失败", it) }
            bitmap.recycle()
            Log.i(TAG, "已写 ${dir.name}/$name.png")
        }
    }

    companion object {
        private const val TAG = "TapFixture"
        private val IO = Executors.newSingleThreadExecutor()

        /**
         * 记下这一次飞行的输入；[spec] 必须是随后真正交给粒子层的那一份。快照在这里复制一份，
         * 粒子层随后可以自由回收原件。
         */
        fun begin(
            activity: Activity, kind: String, spec: ParticleDismissSpec, bounds: RectF, sink: PointF,
            frame: RectF?, params: TapParticleParams
        ): TapFixtureDump? {
            if (!BuildConfig.DEBUG || !TapTransfer.isFixtureDumpEnabled(activity)) return null
            // 正在记录整段会话时，飞行写在会话目录里，时间线上记一条 flight
            val session = TapSessionRecorder.current
            val dir = if (session != null) {
                session.nextFlightDir().also { flight -> session.event("flight") { put("name", flight.name) } }
            } else {
                val root = activity.getExternalFilesDir("tap-fixtures") ?: return null
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
                File(root, "$stamp-$kind").takeIf { it.mkdirs() } ?: return null
            }
            val dump = TapFixtureDump(activity, dir)
            val decor = activity.window.decorView
            val metrics = activity.resources.displayMetrics
            val scene = JSONObject()
                .put("kind", kind)
                .put("device", Build.MODEL)
                .put("screen", JSONArray().put(decor.width).put(decor.height))
                .put("density", metrics.density.toDouble())
                .put("bounds", rect(bounds))
                .put("sink", JSONArray().put(sink.x.toDouble()).put(sink.y.toDouble()))
                .put("frame", frame?.let { rect(it) } ?: JSONObject.NULL)
                .put("frame_share", spec.sink?.frameShare?.toDouble() ?: 0.0)
                .put("reverse", spec.reverse)
                .put("duration_s", spec.playbackDurationS.toDouble())
                .put("seed", spec.hashSeed)
                .put("release_origin", spec.releaseOrigin?.let { JSONArray().put(it.x.toDouble()).put(it.y.toDouble()) }
                    ?: JSONObject.NULL)
                .put("touch_strength", (spec.touchStrength ?: .5f).toDouble())
                .put("cell_dp", ParticlePerfTuning.microflakeCellDp.toDouble())
                .put("jacobi_iterations", ParticlePerfTuning.jacobiIterations)
                .put("srgb_accumulation", ParticlePerfTuning.phase2(ParticlePerfTuning.P2_SRGB8_ACCUMULATION))
                .put("params", params.asJson())
            val snapshot = spec.snapshot.copy(Bitmap.Config.ARGB_8888, false)
            IO.execute {
                runCatching {
                    File(dir, "scene.json").writeText(scene.toString(2), Charsets.UTF_8)
                    FileOutputStream(File(dir, "snapshot.png")).use { snapshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }.onFailure { Log.w(TAG, "写场景失败", it) }
                snapshot.recycle()
                Log.i(TAG, "已写 ${dir.name}/scene.json 与 snapshot.png")
            }
            return dump
        }

        private fun rect(r: RectF) = JSONArray().put(r.left.toDouble()).put(r.top.toDouble())
            .put(r.right.toDouble()).put(r.bottom.toDouble())
    }
}
