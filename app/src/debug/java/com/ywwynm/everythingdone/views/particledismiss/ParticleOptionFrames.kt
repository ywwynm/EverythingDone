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
import kotlin.math.abs
import kotlin.math.min

/**
 * 第三批任务二：给用户定夺的两个 **V 级**选项各出一组**受控对照**。
 *
 * - **E12 按 dp 定微片尺寸**：现状（按像素分格、顶到 `max_cells`）/ 2.2 dp / 3.0 dp；
 * - **C7 压力迭代数**：100（现状）/ 60 / 40。
 *
 * 受控的含义：同一张快照、同一个种子、同一个触点、同一条离屏路径，只切被测的那一个值。
 * 每档取 10% / 50% / 90% 三个进度的整帧与 4 倍最近邻放大裁剪，并量出每帧 GPU 时间、
 * 出现路径 33 个采样的预计算总时长、以及按下时建材的 CPU 耗时。
 *
 * 只读一次真实动画的快照与 spec，自带 EGL 上下文离屏渲染，不碰生产的常驻上下文、不改记事。
 * 默认值一个都不改——这份产物只是给用户看图用的。
 */
internal object ParticleOptionFrames {

    /** 取样的进度点。 */
    private val PROGRESS = floatArrayOf(.1f, .5f, .9f)

    /** 出现路径的采样数，与弹窗实际用的那一档对齐。 */
    private const val APPEARANCE_SAMPLES = 33

    /** 每帧时间取多少帧的平均；先热身再计时。 */
    private const val TIMED_FRAMES = 20
    private const val WARMUP_FRAMES = 4

    private const val THUMBNAIL_WIDTH = 540
    private const val ZOOM_SIDE = 320
    private const val ZOOM_SCALE = 4

    private val lock = Any()

    private class Option(val group: String, val name: String,
                         val cellDp: Float, val iterations: Int,
                         val phase2On: Int = 0, val phase2Off: Int = 0,
                         /** U7 判据：另出一份累积纹理里 alpha 的直方图。 */
                         val alphaHistogram: Boolean = false)

    private val OPTIONS = listOf(
        Option("E12", "current", 0f, 100),
        Option("E12", "dp2.2", 2.2f, 100),
        Option("E12", "dp3.0", 3.0f, 100),
        Option("C7", "jacobi100", 0f, 100),
        Option("C7", "jacobi60", 0f, 60),
        Option("C7", "jacobi40", 0f, 40),
        // 第二阶段的三项 V 级：本轮只出图与数字，默认值一个不改。
        Option("U7", "rgba16f", 0f, 100, 0, ParticlePerfTuning.P2_SRGB8_ACCUMULATION,
            alphaHistogram = true),
        Option("U7", "srgb8", 0f, 100, ParticlePerfTuning.P2_SRGB8_ACCUMULATION, 0,
            alphaHistogram = true),
        Option("U8", "bilinear", 0f, 100, 0, ParticlePerfTuning.P2_NEAREST_SPLAT),
        Option("U8", "nearest", 0f, 100, ParticlePerfTuning.P2_NEAREST_SPLAT, 0),
        Option("U10", "explicit", 0f, 100, 0,
            ParticlePerfTuning.P2_HARDWARE_TRILINEAR or ParticlePerfTuning.P2_MERGED_FIELD),
        Option("U10", "hardware", 0f, 100,
            ParticlePerfTuning.P2_HARDWARE_TRILINEAR or ParticlePerfTuning.P2_MERGED_FIELD, 0))

    fun run(context: Context, spec: ParticleDismissSpec, snapshot: Bitmap,
            width: Int, height: Int, output: File) {
        Thread({
            synchronized(lock) {
                val folder = File(output, "options").apply { mkdirs() }
                val report = JSONObject().put("width", width).put("height", height)
                    .put("seed", spec.hashSeed)
                    .put("density", context.resources.displayMetrics.density)
                    .put("snapshot", JSONArray(listOf(snapshot.width, snapshot.height)))
                // 先挂进结果里再往里填：中途抛异常时前面量到的仍然留得住。
                val options = JSONArray()
                report.put("options", options)
                try {
                    export(context, spec, snapshot, width, height, folder, options, report)
                } catch (error: Throwable) {
                    report.put("error", error.stackTraceToString())
                } finally {
                    runCatching { snapshot.recycle() }
                    File(folder, "report.json").writeText(report.toString(2))
                }
            }
        }, "ParticleOptionFrames").start()
    }

    private fun export(context: Context, spec: ParticleDismissSpec, snapshot: Bitmap,
                       width: Int, height: Int, folder: File, options: JSONArray,
                       report: JSONObject) {
        val density = context.resources.displayMetrics.density
        val readback = ByteBuffer.allocateDirect(width * height * 4)
        val scratch = IntArray(width * height)
        // 逐像素对照只在**中心 [ZOOM_SIDE] 见方的裁剪区**上做，就是放大图那一块。
        // 整帧 RGBA8 在平板上是 22 MB，留三张参照再加三张当前必然把堆撑爆。
        var reference: Array<IntArray>? = null
        var referenceGroup = ""
        // 原 spec 的快照在动画层释放时已经被回收，必须换成观察器复制过来的那一张；
        // 其余字段照抄，受控对照要的就是「只切被测的那一个值」。
        val subject = ParticleDismissSpec(snapshot, spec.originXPx, spec.originYPx,
            spec.virtualTouchXPx, spec.virtualTouchYPx, spec.durationScale, spec.hashSeed,
            touchGap = spec.touchGap, touchStrength = spec.touchStrength,
            materialAreaScale = spec.materialAreaScale)
        withEgl(context.assets, width, height) { shared ->
            // C7 排查要用的几条硬上限：批处理求解器一个工作组要 256 个调用与 16 KiB 共享内存，
            // 正好卡在 GLES 3.1 的下限上；Maleoon 上是否真的给足，只有读回来才知道。
            report.put("renderer", GLES30.glGetString(GLES30.GL_RENDERER))
                .put("glVersion", GLES30.glGetString(GLES30.GL_VERSION))
                .put("maxComputeSharedMemory", limit(0x8262))
                .put("maxComputeWorkGroupInvocations", limit(0x90EB))
                .put("maxComputeSsbo", limit(0x90DB))
            for (option in OPTIONS) {
                if (option.group != referenceGroup) { reference = null; referenceGroup = option.group }
                val row = JSONObject().put("group", option.group).put("name", option.name)
                    .put("cellDp", option.cellDp).put("iterations", option.iterations)
                options.put(row)
                withOption(option) {
                    val built = System.nanoTime()
                    val input = ParticleMicroflakeRenderer.fromSpec(
                        context.assets, width, height, density, subject)
                    row.put("buildMs", (System.nanoTime() - built) / 1e6)
                    row.put("count", input.materials.count)
                    val frames = JSONArray()
                    row.put("frames", frames)
                    // `draw(time)` 只许时间单调前进（内部断言「运行时不能倒放材料状态」），
                    // 所以每一段各用一个新的渲染器：画面、现场计时、出现路径互不影响。
                    fun fresh() = ParticleMicroflakeRenderer(context.assets, shared, input)
                        .apply { prepare() }
                    // ① 画面：三个进度点的整帧与 4 倍放大块。
                    fresh().use { renderer ->
                        val captured = ArrayList<IntArray>(PROGRESS.size)
                        for ((index, progress) in PROGRESS.withIndex()) {
                            renderer.draw(progress, batchPressure = true)
                            val label = "${option.group}-${option.name}-${(progress * 100).toInt()}"
                            val pixels = read(width, height, readback, scratch)
                            save(folder, label, width, height, pixels)
                            val patch = crop(pixels, width, height)
                            captured += patch
                            val base = reference?.getOrNull(index)
                            val frame = (if (base != null) compare(base, patch) else JSONObject())
                                .put("progress", progress)
                            // C7 排查：画面之外再记压力求解那一步的中间状态与积分结果的散列。
                            // 「迭代数换了画面却逐位不变」有三种可能，这两项正好把它们分开：
                            // 活动 tile 数为 0 ⇒ 间接分发根本没跑；压力场的和随迭代数变而
                            // 状态散列不变 ⇒ 压力没进积分；两者都不变 ⇒ 真的收敛了。
                            runCatching {
                                val d = renderer.pressureDiagnostics()
                                frame.put("grid", JSONArray(listOf(d[0].toInt(), d[1].toInt())))
                                    .put("activeTiles", d[2].toInt())
                                    .put("occupiedCells", d[3].toInt())
                                    .put("nonZeroPressure", d[4].toInt())
                                    .put("pressureSum", d[5])
                                    .put("pressurePeak", d[6])
                                    .put("region", JSONArray((7..10).map { d[it].toInt() }))
                                    .put("splatBox", JSONArray((11..14).map { d[it].toInt() }))
                                    .put("splattedCells", d[15].toInt())
                                    .put("splattedIndex", d[16].toInt())
                                    .put("stateHash", hash(renderer.readState()))
                            }.onFailure { frame.put("diagnosticsError", it.toString()) }
                            // U7：8 位量化够不够用，要看累积纹理里 alpha 真正落在哪些档上。
                            if (option.alphaHistogram) runCatching {
                                frame.put("alphaHistogram", JSONArray(
                                    renderer.accumulationAlphaHistogram().toList()))
                            }.onFailure { frame.put("alphaHistogramError", it.toString()) }
                            frames.put(frame)
                        }
                        if (reference == null) reference = captured.toTypedArray()
                    }
                    // ② 现场消散的每帧成本：热身之后连着画，末尾只 glFinish 一次。
                    fresh().use { renderer ->
                        for (i in 0 until WARMUP_FRAMES) renderer.draw(.02f * (i + 1), batchPressure = true)
                        GLES30.glFinish()
                        val started = System.nanoTime()
                        for (i in 0 until TIMED_FRAMES) {
                            renderer.draw(.2f + .6f * i / TIMED_FRAMES, batchPressure = true)
                        }
                        GLES30.glFinish()
                        row.put("liveFrameMs", (System.nanoTime() - started) / 1e6 / TIMED_FRAMES)
                    }
                    // ③ 出现路径：33 个采样的预计算总时长，以及回放一帧的成本。
                    fresh().use { renderer ->
                        val precomputeStart = System.nanoTime()
                        renderer.beginReverse(APPEARANCE_SAMPLES, APPEARANCE_SAMPLES)
                        while (renderer.reverseIncomplete) {
                            check(renderer.stepReverse { false }) { "逆向准备被取消" }
                        }
                        renderer.endReverse()
                        GLES30.glFinish()
                        row.put("precomputeMs", (System.nanoTime() - precomputeStart) / 1e6)
                        repeat(WARMUP_FRAMES) { renderer.drawReverseFrame(APPEARANCE_SAMPLES / 2) }
                        GLES30.glFinish()
                        val playbackStart = System.nanoTime()
                        repeat(TIMED_FRAMES) {
                            renderer.drawReverseFrame(it * APPEARANCE_SAMPLES / TIMED_FRAMES)
                        }
                        GLES30.glFinish()
                        row.put("playbackFrameMs",
                            (System.nanoTime() - playbackStart) / 1e6 / TIMED_FRAMES)
                    }
                }
            }
        }
    }

    /**
     * 只在本段里切这两个 V 级调参口。
     *
     * 切换是**线程局部**的（[ParticlePerfTuning.withOptions]）：同一时刻常驻 GL 线程上的
     * 创建页提前预算也在建材、也在跑压力更新，改进程级的值会把它的配方一起改掉。
     */
    private fun withOption(option: Option, action: () -> Unit) =
        ParticlePerfTuning.withOptions(option.cellDp, option.iterations) {
            ParticlePerfTuning.withPhase2(option.phase2On, option.phase2Off, action)
        }

    private fun limit(name: Int): Int {
        val value = IntArray(1)
        GLES30.glGetIntegerv(name, value, 0)
        return value[0]
    }

    /** 积分结果的 64 位散列；只用来判「两档的粒子状态是不是逐位相同」。 */
    private fun hash(state: ByteBuffer): String {
        var value = -0x340d631b7bdddcdbL
        while (state.remaining() >= 4) value = (value xor state.int.toLong()) * 0x100000001b3L
        return java.lang.Long.toHexString(value)
    }

    /** 中心 [ZOOM_SIDE] 见方的裁剪区；逐像素对照只在这一块上做，与放大图同一区域。 */
    private fun crop(pixels: IntArray, width: Int, height: Int): IntArray {
        val side = min(ZOOM_SIDE, min(width, height))
        val left = ((width - side) / 2).coerceAtLeast(0)
        val top = ((height - side) / 2).coerceAtLeast(0)
        val out = IntArray(side * side)
        for (y in 0 until side) System.arraycopy(pixels, (top + y) * width + left, out, y * side, side)
        return out
    }

    /** 与本组第一档的逐像素差：均值、p99 与最大通道差，以及差超过 2 的像素占比。 */
    private fun compare(base: IntArray, other: IntArray): JSONObject {
        var sum = 0.0
        var max = 0
        var above = 0
        val histogram = IntArray(256)
        for (i in base.indices) {
            val a = base[i]
            val b = other[i]
            var worst = 0
            for (shift in intArrayOf(0, 8, 16, 24)) {
                val d = abs((a ushr shift and 255) - (b ushr shift and 255))
                if (d > worst) worst = d
            }
            sum += worst
            histogram[worst]++
            if (worst > max) max = worst
            if (worst > 2) above++
        }
        var seen = 0
        var p99 = 0
        val target = (base.size * .99).toInt()
        for (value in 0..255) {
            seen += histogram[value]
            if (seen >= target) { p99 = value; break }
        }
        return JSONObject().put("meanDiff", sum / base.size).put("p99Diff", p99)
            .put("maxDiff", max).put("pixelsAbove2", above.toDouble() / base.size)
    }

    private fun read(width: Int, height: Int, buffer: ByteBuffer, pixels: IntArray): IntArray {
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
        return pixels
    }

    /** 整帧缩略图 + 画面中心 4 倍最近邻放大块。 */
    private fun save(folder: File, label: String, width: Int, height: Int, pixels: IntArray) {
        val full = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        try {
            val scaled = Bitmap.createScaledBitmap(full, THUMBNAIL_WIDTH,
                (THUMBNAIL_WIDTH.toLong() * height / width).toInt().coerceAtLeast(1), true)
            write(File(folder, "$label-full.png"), scaled)
            scaled.recycle()
            val side = min(ZOOM_SIDE, min(width, height))
            val left = ((width - side) / 2).coerceAtLeast(0)
            val top = ((height - side) / 2).coerceAtLeast(0)
            val crop = Bitmap.createBitmap(full, left, top, side, side)
            val zoom = Bitmap.createScaledBitmap(crop, side * ZOOM_SCALE, side * ZOOM_SCALE, false)
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
        try {
            ParticleMicroflakeRenderer.Shared(assets).use { shared ->
                shared.ensure(width, height)
                action(shared)
            }
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
        }
    }
}
