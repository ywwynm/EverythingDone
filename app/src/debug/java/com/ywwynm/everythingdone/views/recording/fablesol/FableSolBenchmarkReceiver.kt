package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug 专用：`adb shell am broadcast` 触发离屏基准，锁屏状态也可运行。
 *
 * 示例：
 * adb shell am broadcast -a com.ywwynm.everythingdone.FABLESOL_BENCH \
 *   -n com.ywwynm.everythingdone/.views.recording.fablesol.FableSolBenchmarkReceiver \
 *   --ei frames 600 --ei fps 120 --ez hdr true
 */
class FableSolBenchmarkReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val frames = intent.getIntExtra("frames", 600).coerceIn(30, 3600)
        val fps = intent.getIntExtra("fps", 120).coerceIn(30, 240)
        val hdr = intent.getBooleanExtra("hdr", true)
        val density = appContext.resources.displayMetrics.density
        val width = intent.getIntExtra("width", (280f * density).toInt())
        val height = intent.getIntExtra("height", (420f * density).toInt())
        val dumpTag = intent.getStringExtra("dump")
        Thread({
            try {
                FableSolOffscreenBenchmark.run(
                    appContext, frames, fps, hdr, width, height, dumpTag
                )
            } catch (error: Throwable) {
                Log.e("FableSolBench", "offscreen benchmark failed", error)
            }
        }, "FableSolBench").start()
    }
}
