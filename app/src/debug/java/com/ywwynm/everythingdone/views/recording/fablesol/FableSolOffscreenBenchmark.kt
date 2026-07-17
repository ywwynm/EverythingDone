package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.Context
import android.graphics.Color
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES30
import android.util.Log
import com.ywwynm.everythingdone.model.ThingBackground
import java.io.File
import java.util.Locale

/**
 * Debug 专用：EGL pbuffer 离屏跑完整 FableSol GLES 管线并统计逐帧分阶段耗时。
 *
 * 不需要可见窗口或解锁屏幕，用于在真机上对比优化前后的 CPU 构建 / GL 提交 /
 * GPU 完成成本。demo_mode 由固定种子驱动，帧节奏按 targetFps 的固定时间戳推进
 * （120fps 时物理每帧恰好 1 个子步，与真实高刷会话一致）。
 */
internal object FableSolOffscreenBenchmark {

    private const val TAG = "FableSolBench"
    private const val DUMP_EVERY = 120

    fun run(
        context: Context,
        frames: Int,
        targetFps: Int,
        hdrScene: Boolean,
        widthPx: Int,
        heightPx: Int,
        dumpTag: String? = null
    ): String {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display !== EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        try {
            val configs = arrayOfNulls<EGLConfig>(1)
            val configCount = IntArray(1)
            check(
                EGL14.eglChooseConfig(
                    display,
                    intArrayOf(
                        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT or 0x40,
                        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                        EGL14.EGL_RED_SIZE, 8,
                        EGL14.EGL_GREEN_SIZE, 8,
                        EGL14.EGL_BLUE_SIZE, 8,
                        EGL14.EGL_ALPHA_SIZE, 8,
                        EGL14.EGL_NONE
                    ),
                    0, configs, 0, 1, configCount, 0
                ) && configCount[0] > 0
            ) { "eglChooseConfig failed" }
            val config = configs[0]!!
            val eglContext = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
            )
            check(eglContext !== EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
            val surface = EGL14.eglCreatePbufferSurface(
                display, config,
                intArrayOf(
                    EGL14.EGL_WIDTH, widthPx,
                    EGL14.EGL_HEIGHT, heightPx,
                    EGL14.EGL_NONE
                ), 0
            )
            check(surface !== EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }
            check(EGL14.eglMakeCurrent(display, surface, surface, eglContext)) {
                "eglMakeCurrent failed"
            }
            try {
                return runOnCurrentContext(context, frames, targetFps, hdrScene,
                    widthPx, heightPx, dumpTag)
            } finally {
                EGL14.eglMakeCurrent(
                    display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
                EGL14.eglDestroySurface(display, surface)
                EGL14.eglDestroyContext(display, eglContext)
            }
        } finally {
            EGL14.eglTerminate(display)
        }
    }

    private fun runOnCurrentContext(
        context: Context,
        frames: Int,
        targetFps: Int,
        hdrScene: Boolean,
        widthPx: Int,
        heightPx: Int,
        dumpTag: String?
    ): String {
        val density = context.resources.displayMetrics.density.toDouble()
        val renderer = FableSolGlRenderer(context, density)
        try {
            renderer.initialize(hdrScene)
            renderer.resize(widthPx, heightPx)
            renderer.setThingBackground(
                ThingBackground.gradient(
                    Color.parseColor("#0E7490"),
                    Color.parseColor("#67E8F9"),
                    ThingBackground.Orientation.LB_RT
                )
            )
            renderer.setTuningValue("demo_mode", 1.0)
            if (hdrScene) {
                renderer.setDisplayHdrSdrRatio(2.0f)
                renderer.setHdrRecordingRequested(true)
            }

            val frameNs = (1_000_000_000L / targetFps)
            var timestamp = 1_000_000_000L
            val warmup = 120
            for (i in 0 until warmup) {
                renderer.render(timestamp)
                timestamp += frameNs
            }
            GLES30.glFinish()

            val stages = arrayOf(
                "drain", "physics", "build", "draw", "finish", "frame"
            )
            val samples = Array(stages.size) { DoubleArray(frames) }
            val dumpDir = if (dumpTag != null) {
                File(context.getExternalFilesDir(null), "fablesol_frames_$dumpTag")
                    .also { it.mkdirs() }
            } else {
                null
            }
            val readback = if (dumpDir != null) {
                java.nio.ByteBuffer.allocateDirect(widthPx * heightPx * 4)
                    .order(java.nio.ByteOrder.nativeOrder())
            } else {
                null
            }
            for (i in 0 until frames) {
                val frameStart = System.nanoTime()
                val timing = renderer.render(timestamp)
                val finishStart = System.nanoTime()
                GLES30.glFinish()
                val frameEnd = System.nanoTime()
                samples[0][i] = timing.drainNs / 1e6
                samples[1][i] = timing.physicsNs / 1e6
                samples[2][i] = timing.buildNs / 1e6
                samples[3][i] = timing.drawNs / 1e6
                samples[4][i] = (frameEnd - finishStart) / 1e6
                samples[5][i] = (frameEnd - frameStart) / 1e6
                timestamp += frameNs
                if (dumpDir != null && readback != null && (i + 1) % DUMP_EVERY == 0) {
                    // 视觉一致性对照：present 输出（默认帧缓冲 = pbuffer）原样落盘，
                    // 跨构建逐字节比较；确定性 demo 驱动保证两次运行同帧同景。
                    readback.clear()
                    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                    GLES30.glReadPixels(
                        0, 0, widthPx, heightPx,
                        GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, readback
                    )
                    val file = File(dumpDir, String.format(Locale.US, "frame_%04d.rgba", i + 1))
                    file.outputStream().channel.use { channel ->
                        readback.rewind()
                        channel.write(readback)
                    }
                }
            }

            val report = StringBuilder()
            report.append("fablesol offscreen bench frames=").append(frames)
                .append(" targetFps=").append(targetFps)
                .append(" hdrScene=").append(hdrScene)
                .append(" size=").append(widthPx).append('x').append(heightPx)
                .append(" density=").append(density)
                .append('\n')
            for (index in stages.indices) {
                val sorted = samples[index].sorted()
                fun pct(q: Double): Double {
                    val position = (q / 100.0) * (sorted.size - 1)
                    val low = position.toInt()
                    val high = minOf(low + 1, sorted.size - 1)
                    val fraction = position - low
                    return sorted[low] + (sorted[high] - sorted[low]) * fraction
                }
                report.append(
                    String.format(
                        Locale.US,
                        "%s p50=%.3f p95=%.3f p99=%.3f ms\n",
                        stages[index], pct(50.0), pct(95.0), pct(99.0)
                    )
                )
            }
            val text = report.toString()
            Log.i(TAG, text)
            val dir = File(context.getExternalFilesDir(null), "debug_logs")
            if (!dir.exists()) dir.mkdirs()
            File(dir, "fablesol_bench.log").appendText(text + "\n")
            return text
        } finally {
            renderer.release()
        }
    }
}
