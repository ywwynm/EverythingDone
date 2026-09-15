package com.ywwynm.everythingdone.views.particledismiss

import android.content.Context
import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES30
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 2d：为创建页的出现动画离屏导出三组对比帧——现状、只开 2b、2b+2c。
 *
 * 只读一次真实动画的快照与 spec（观察器复制过来的那一份），自带 EGL 上下文离屏渲染，
 * 不碰生产的常驻上下文，也不改任何记事。产物写进本轮的观察器输出目录。
 */
internal object ParticleAppearanceFrames {

    /** 起始段的取样时刻（毫秒）。 */
    private val TIMES = intArrayOf(0, 50, 100, 150, 250)

    /** 3b 的四种输入，逐项叠加。 */
    private val VARIANTS = listOf("baseline", "dir", "dir-gap", "dir-gap-strength")

    /** 整帧缩略图的宽度；右下角放大块的边长与倍数。 */
    private const val THUMBNAIL_WIDTH = 480
    private const val ZOOM_SIDE_FRACTION = .28f
    private const val ZOOM_SCALE = 3

    private val lock = Any()

    fun run(context: Context, spec: ParticleDismissSpec, snapshot: Bitmap,
            width: Int, height: Int, output: File) {
        Thread({
            synchronized(lock) {
                val folder = File(output, "appearance-frames").apply { mkdirs() }
                val report = JSONObject().put("width", width).put("height", height)
                    .put("seed", spec.hashSeed)
                try {
                    export(context, spec, snapshot, width, height, folder, report)
                } catch (error: Throwable) {
                    report.put("error", error.stackTraceToString())
                } finally {
                    runCatching { snapshot.recycle() }
                    File(folder, "report.json").writeText(report.toString(2))
                }
            }
        }, "ParticleAppearanceFrames").start()
    }

    private fun export(context: Context, spec: ParticleDismissSpec, snapshot: Bitmap,
                       width: Int, height: Int, folder: File, report: JSONObject) {
        val density = context.resources.displayMetrics.density
        val samples = ParticleReversePlan.samplesFor(
            ParticleReversePlan.APPEARANCE_SECONDS * spec.durationScale.coerceAtLeast(.1f), 60f)
        val variants = JSONArray()
        // 整屏回读每次 17 MB；只分配一次，十五次取样共用。
        val readback = ByteBuffer.allocateDirect(width * height * 4)
        val scratch = IntArray(width * height)
        withEgl(context.assets, width, height) { shared ->
            for (name in VARIANTS) {
                val variant = specFor(name, spec, snapshot)
                val input = ParticleMicroflakeRenderer.fromSpec(context.assets, width, height, density, variant)
                val counts = ParticleReversePlan.aliveCounts(input.materials.values, samples)
                // 归因轮要比的是「每个采样多贵」，所以四种输入一律完整预计算到 samples；
                // 2c 的起点只算出来记录，不影响这里的计时。
                val start = samples
                val playbackMs = ParticleReversePlan.APPEARANCE_SECONDS * 1000.0 *
                    spec.durationScale.coerceAtLeast(.1f) * start / samples
                val frames = JSONArray()
                var precomputeMs = 0.0
                ParticleMicroflakeRenderer(context.assets, shared, input).use { renderer ->
                    renderer.prepare()
                    // 同一张快照、同一个种子，三种输入各算一遍：这是「2b 的材料输入是否让每个
                    // 采样更贵」唯一不受随机背景色干扰的对照。只积分到本变体的起点。
                    val started = System.nanoTime()
                    renderer.beginReverse(samples, start)
                    while (renderer.reverseIncomplete) check(renderer.stepReverse { false }) { "逆向准备被取消" }
                    renderer.endReverse()
                    GLES30.glFinish()
                    precomputeMs = (System.nanoTime() - started) / 1e6
                    for (ms in TIMES) {
                        val progress = (ms / playbackMs).coerceIn(0.0, 1.0).toFloat()
                        val frame = ((1f - progress) * start).roundToInt().coerceIn(0, samples)
                        renderer.drawReverseFrame(frame)
                        save(folder, "$name-${ms}ms", width, height, readback, scratch)
                        frames.put(JSONObject().put("ms", ms).put("frame", frame)
                            .put("aliveRatio", counts[frame].toDouble() / input.materials.count))
                    }
                }
                variants.put(JSONObject().put("name", name).put("direction", input.direction)
                    .put("touchGap", input.touchGap).put("touchStrength", input.touchStrength)
                    .put("start", start).put("samples", samples)
                    .put("precomputeMs", precomputeMs)
                    .put("precomputePerSampleMs", precomputeMs / (start + 1))
                    // 2c 的起点在两个候选门槛下分别是多少（只算不用，供选门槛）。
                    .put("skipStart5", ParticleReversePlan.startFrameOf(counts, input.materials.count, .05f))
                    .put("skipStart10", ParticleReversePlan.startFrameOf(counts, input.materials.count, .10f))
                    .put("skipStart15", ParticleReversePlan.startFrameOf(counts, input.materials.count, .15f))
                    .put("playbackMs", playbackMs).put("count", input.materials.count)
                    .put("aliveCounts", JSONArray(counts.toList())).put("frames", frames))
            }
        }
        report.put("variants", variants)
    }

    /**
     * 3b 的四种输入，逐项叠加，用来把「观感」与「预计算代价」各自归到哪一项上：
     * - `baseline`：315° 虚拟远触点、默认触点距离 0.65 与强度 0.5（第二批的行为）；
     * - `dir`：方向改成内容中心指向新建按钮，距离与强度仍用默认；
     * - `dir-gap`：再把触点距离换成真实值（按钮在内容内 ⇒ 0）；
     * - `dir-gap-strength`：再把触点强度换成真实值（⇒ 0），即 3a 发布的那一档。
     */
    private fun specFor(name: String, spec: ParticleDismissSpec, snapshot: Bitmap): ParticleDismissSpec {
        val centerX = spec.originXPx + snapshot.width / 2f
        val centerY = spec.originYPx + snapshot.height / 2f
        if (name == "baseline") {
            val angle = Math.toRadians(-315.0).toFloat()
            val reach = 1.1f * hypot(snapshot.width.toFloat(), snapshot.height.toFloat())
            return ParticleDismissSpec(snapshot, spec.originXPx, spec.originYPx,
                centerX + cos(angle) * reach, centerY + sin(angle) * reach,
                spec.durationScale, spec.hashSeed, reverse = true,
                playbackDurationS = ParticleReversePlan.APPEARANCE_SECONDS)
        }
        val touchX = spec.appearanceTouchXPx ?: spec.virtualTouchXPx
        val touchY = spec.appearanceTouchYPx ?: spec.virtualTouchYPx
        val localX = touchX - spec.originXPx
        val localY = touchY - spec.originYPx
        val w = snapshot.width.toFloat()
        val h = snapshot.height.toFloat()
        return ParticleDismissSpec(snapshot, spec.originXPx, spec.originYPx, touchX, touchY,
            spec.durationScale, spec.hashSeed, reverse = true,
            playbackDurationS = ParticleReversePlan.APPEARANCE_SECONDS,
            touchGap = if (name == "dir") null
                else ParticleFlowGeometry.touchGap(localX, localY, w, h),
            touchStrength = if (name != "dir-gap-strength") null
                else ParticleFlowGeometry.touchStrength(localX, localY, w, h, 0f, 0f, w, h))
    }

    /** 整帧缩略图 + 右下角放大块；两张都按预乘还原成直通颜色。 */
    private fun save(folder: File, label: String, width: Int, height: Int,
                     buffer: ByteBuffer, pixels: IntArray) {
        buffer.clear()
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
        buffer.rewind()
        for (y in 0 until height) for (x in 0 until width) {
            val r = buffer.get().toInt() and 255
            val g = buffer.get().toInt() and 255
            val b = buffer.get().toInt() and 255
            val a = buffer.get().toInt() and 255
            fun straight(v: Int) = if (a == 0) 0 else ((v * 255 + a / 2) / a).coerceAtMost(255)
            pixels[(height - 1 - y) * width + x] =
                (a shl 24) or (straight(r) shl 16) or (straight(g) shl 8) or straight(b)
        }
        val full = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        try {
            val scaled = Bitmap.createScaledBitmap(full, THUMBNAIL_WIDTH,
                (THUMBNAIL_WIDTH.toLong() * height / width).toInt().coerceAtLeast(1), true)
            write(File(folder, "$label.png"), scaled)
            scaled.recycle()
            // 右下角：粒子应当从新建按钮那一侧汇入，这一块最能看出方向性。
            val side = (min(width, height) * ZOOM_SIDE_FRACTION).toInt().coerceIn(16, min(width, height))
            val left = (width - side).coerceAtLeast(0)
            val top = (height - side).coerceAtLeast(0)
            val crop = Bitmap.createBitmap(full, left, top, width - left, height - top)
            val zoom = Bitmap.createScaledBitmap(crop, (width - left) * ZOOM_SCALE,
                (height - top) * ZOOM_SCALE, false)
            write(File(folder, "$label-zoom.png"), zoom)
            crop.recycle()
            zoom.recycle()
        } finally {
            full.recycle()
        }
    }

    private fun write(file: File, bitmap: Bitmap) =
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

    private fun withEgl(assets: android.content.res.AssetManager, width: Int, height: Int,
                        action: (ParticleMicroflakeRenderer.Shared) -> Unit) {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1))
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, 0x40,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE), 0, configs, 0, 1, count, 0) && count[0] > 0)
        val context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        val surface = EGL14.eglCreatePbufferSurface(display, configs[0],
            intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, surface, surface, context))
        val shared = ParticleMicroflakeRenderer.Shared(assets)
        try {
            shared.ensure(width, height)
            action(shared)
        } finally {
            runCatching { shared.close() }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
        }
    }
}
