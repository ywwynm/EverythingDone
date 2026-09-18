package com.ywwynm.everythingdone.views.particledismiss

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES30
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 逐帧成本归因：对真实动画的材料，在**自己的**离屏上下文里把同一条路径重复跑，
 * 每次临时跳过一段（[ParticleAblation]），用墙钟的差值给出该段的份额。
 *
 * 为什么不直接用分段计时查询：Adreno 的 `EXT_disjoint_timer_query` 不计 compute dispatch
 * （见 `.claude/rules/adb.md`），而按段 `glFinish` 会把流水线串行化，只能比相对量级。
 * 消融是唯一能给出份额的口径（阶段 0 的结论，phase0-baseline 第 2 节）。
 *
 * 三条路径与线上的每帧工作一一对应：
 * - `dismiss`（现场消散）：`draw(t, batchPressure=true)` = 4 步积分 + 1 次压力更新 + 2 遍绘制 + resolve；
 * - `appear`（回放出现）：`drawReverseFrame(i)` = 1 次历史恢复 + 2 遍绘制 + resolve；
 * - `preread`（左滑预读）：`stepReverse()` = 一个采样的现场消散量，左滑每拍要补 1–2 个。
 *
 * 条件之间**交错**执行（外层 repeat、内层条件），避免 DVFS 漂移被读成份额差。
 * 每次只留当前这一遍的耗时数组，不保留任何整帧像素（离屏对照 OOM 的教训）。
 */
internal object ParticleSegmentBench {

    private val lock = Any()

    fun run(context: Context, spec: ParticleDismissSpec, width: Int, height: Int, output: File,
            repeats: Int = 2, forwardFrames: Int = 60) {
        Thread({
            synchronized(lock) {
                val report = JSONObject().put("width", width).put("height", height)
                    .put("repeats", repeats).put("startedAt", System.nanoTime())
                val file = File(output, "segment-bench.json")
                runCatching { bench(context, spec, width, height, report, repeats, forwardFrames) }
                    .onFailure { report.put("error", it.stackTraceToString()) }
                runCatching { file.writeText(report.toString()) }
                    .onFailure { File(output, "segment-bench-error.txt").writeText(it.stackTraceToString()) }
            }
        }, "ParticleSegmentBench").start()
    }

    /**
     * 逐帧成本专项第一批的**逐项 A/B**：同一份材料、同一条路径，只切
     * [ParticlePerfTuning.withPerFrame] 的那一位，量整段墙钟。
     *
     * 与消融不同，这里两侧都是**正确**的画面，所以三条路径全都成立（消融只对不回灌模拟的
     * 段有效）。纪律照旧：先热身一遍丢掉、外层配对内层条件、整段只在末尾 `glFinish` 一次。
     */
    fun runPerFrame(context: Context, spec: ParticleDismissSpec, width: Int, height: Int,
                    output: File, repeats: Int = 2, forwardFrames: Int = 60) {
        Thread({
            synchronized(lock) {
                val report = JSONObject().put("width", width).put("height", height)
                    .put("repeats", repeats).put("startedAt", System.nanoTime())
                val file = File(output, "per-frame-bench.json")
                runCatching { perFrame(context, spec, width, height, report, repeats, forwardFrames) }
                    .onFailure { report.put("error", it.stackTraceToString()) }
                runCatching { file.writeText(report.toString()) }
                    .onFailure { File(output, "per-frame-bench-error.txt").writeText(it.stackTraceToString()) }
            }
        }, "ParticlePerFrameBench").start()
    }

    /**
     * 第二阶段的**逐项墙钟 A/B**。条件表见 [ParticlePhase2Conditions]，两侧各自用
     * [ParticlePerfTuning.withPhase2] 的线程局部掩码切换，纪律与 [runPerFrame] 相同：
     * 先热身一遍丢掉、外层配对内层条件、整段只在末尾 `glFinish` 一次。
     */
    fun runPhase2(context: Context, spec: ParticleDismissSpec, width: Int, height: Int,
                  output: File, repeats: Int = 2, forwardFrames: Int = 60,
                  names: Collection<String> = emptyList()) {
        Thread({
            synchronized(lock) {
                val report = JSONObject().put("width", width).put("height", height)
                    .put("repeats", repeats).put("startedAt", System.nanoTime())
                val file = File(output, "phase2-bench.json")
                runCatching { phase2(context, spec, width, height, report, repeats, forwardFrames, names) }
                    .onFailure { report.put("error", it.stackTraceToString()) }
                runCatching { file.writeText(report.toString()) }
                    .onFailure { File(output, "phase2-bench-error.txt").writeText(it.stackTraceToString()) }
            }
        }, "ParticlePhase2Bench").start()
    }

    private fun phase2(context: Context, spec: ParticleDismissSpec, width: Int, height: Int,
                       report: JSONObject, repeats: Int, forwardFrames: Int,
                       names: Collection<String>) {
        val input = ParticleMicroflakeRenderer.fromSpec(context.assets, width, height,
            context.resources.displayMetrics.density, spec)
        val samples = ParticleReversePlan.samplesFor(ParticleReversePlan.APPEARANCE_SECONDS, 60f)
        val conditions = ParticlePhase2Conditions.byName(names)
        withEgl(context.assets, width, height) { shared ->
            report.put("renderer", GLES30.glGetString(GLES30.GL_RENDERER) ?: "")
                .put("count", input.materials.count)
                .put("conditions", JSONArray(conditions.map { it.name }))
            fun dismissRun(on: Int, off: Int): Double = ParticlePerfTuning.withPhase2(on, off) {
                ParticleMicroflakeRenderer(context.assets, shared, input).use { renderer ->
                    renderer.prepare()
                    renderer.draw(0f, batchPressure = true)
                    GLES30.glFinish()
                    val start = System.nanoTime()
                    for (frame in 1..forwardFrames) renderer.draw(frame / forwardFrames.toFloat(), batchPressure = true)
                    GLES30.glFinish()
                    (System.nanoTime() - start) / 1e6
                }
            }
            fun appearRun(on: Int, off: Int): Double = ParticlePerfTuning.withPhase2(on, off) {
                ParticleMicroflakeRenderer(context.assets, shared, input).use { renderer ->
                    renderer.prepare()
                    check(renderer.prepareReverse(samples)) { "逆向准备被取消" }
                    GLES30.glFinish()
                    val start = System.nanoTime()
                    for (frame in samples downTo 0) renderer.drawReverseFrame(frame)
                    GLES30.glFinish()
                    (System.nanoTime() - start) / 1e6
                }
            }
            fun prereadRun(on: Int, off: Int): Double = ParticlePerfTuning.withPhase2(on, off) {
                ParticleMicroflakeRenderer(context.assets, shared, input).use { renderer ->
                    renderer.prepare()
                    renderer.beginReverse(samples, samples, precomputeProbe = false)
                    val start = System.nanoTime()
                    while (renderer.reverseIncomplete) check(renderer.stepReverse { false }) { "预读被取消" }
                    GLES30.glFinish()
                    val elapsed = (System.nanoTime() - start) / 1e6
                    renderer.endReverse()
                    elapsed
                }
            }
            val paths = listOf<Triple<String, (Int, Int) -> Double, Int>>(
                Triple("dismiss", ::dismissRun, forwardFrames),
                Triple("appear", ::appearRun, samples),
                Triple("preread", ::prereadRun, samples))
            for ((name, run, units) in paths) {
                val bucket = JSONObject().put("units", units)
                run(0, 0)  // 热身，不计
                for (pair in 0 until repeats) for (condition in conditions) {
                    record(bucket, "base@" + condition.name, run(condition.baseOn, condition.baseOff))
                    record(bucket, condition.name, run(condition.testOn, condition.testOff))
                }
                report.put(name, bucket)
            }
            check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "第二阶段 A/B 结束时有 GL 错误" }
        }
    }

    /** 条件表：整批，以及四项各自单开。名字直接进产物，分析脚本按它取数。 */
    private val PER_FRAME = listOf(
        "all" to ParticlePerfTuning.PER_FRAME_ALL,
        "a1-records" to ParticlePerfTuning.PER_FRAME_DRAW_RECORDS,
        "c10-region" to ParticlePerfTuning.PER_FRAME_ACTIVE_REGION)

    private fun perFrame(context: Context, spec: ParticleDismissSpec, width: Int, height: Int,
                         report: JSONObject, repeats: Int, forwardFrames: Int) {
        val input = ParticleMicroflakeRenderer.fromSpec(context.assets, width, height,
            context.resources.displayMetrics.density, spec)
        val samples = ParticleReversePlan.samplesFor(ParticleReversePlan.APPEARANCE_SECONDS, 60f)
        withEgl(context.assets, width, height) { shared ->
            report.put("renderer", GLES30.glGetString(GLES30.GL_RENDERER) ?: "")
                .put("count", input.materials.count)
            // ---- 1. 现场消散 ----
            val dismiss = JSONObject()
            fun dismissRun(enabled: Int, first: Boolean): Double =
                ParticlePerfTuning.withPerFrame(enabled) {
                    ParticleMicroflakeRenderer(context.assets, shared, input).use { renderer ->
                        renderer.prepare()
                        if (first) report.put("sizes", JSONArray(renderer.diagnosticSizes.toList()))
                        renderer.draw(0f, batchPressure = true)
                        GLES30.glFinish()
                        val start = System.nanoTime()
                        for (frame in 1..forwardFrames) {
                            renderer.probeFrame(frame, "play")
                            renderer.draw(frame / forwardFrames.toFloat(), batchPressure = true)
                        }
                        GLES30.glFinish()
                        (System.nanoTime() - start) / 1e6
                    }
                }
            dismissRun(0, true)
            for (pair in 0 until repeats) for ((name, mask) in PER_FRAME) {
                record(dismiss, "base@$name", dismissRun(0, false))
                record(dismiss, name, dismissRun(mask, false))
            }
            report.put("dismiss", dismiss).put("dismissFrames", forwardFrames)
            // ---- 2. 回放出现：每个条件自己算一份历史（改动会影响轨迹），只计播放那一段 ----
            val appear = JSONObject()
            val precompute = JSONObject()
            fun appearRun(enabled: Int): Double = ParticlePerfTuning.withPerFrame(enabled) {
                ParticleMicroflakeRenderer(context.assets, shared, input).use { renderer ->
                    renderer.prepare()
                    check(renderer.prepareReverse(samples)) { "逆向准备被取消" }
                    GLES30.glFinish()
                    val start = System.nanoTime()
                    for (frame in samples downTo 0) {
                        renderer.probeFrame(frame, "appear")
                        renderer.drawReverseFrame(frame)
                    }
                    GLES30.glFinish()
                    (System.nanoTime() - start) / 1e6
                }
            }
            appearRun(0)
            for (pair in 0 until repeats) for ((name, mask) in PER_FRAME) {
                record(appear, "base@$name", appearRun(0))
                record(appear, name, appearRun(mask))
            }
            report.put("appear", appear).put("appearSamples", samples)
            // ---- 3. 左滑预读：每个条件从零开始算一遍整段历史 ----
            val preread = JSONObject()
            fun prereadRun(enabled: Int): Double = ParticlePerfTuning.withPerFrame(enabled) {
                ParticleMicroflakeRenderer(context.assets, shared, input).use { renderer ->
                    renderer.prepare()
                    renderer.beginReverse(samples, samples, precomputeProbe = false)
                    val start = System.nanoTime()
                    var index = 0
                    while (renderer.reverseIncomplete) {
                        renderer.probeFrame(index, "preread")
                        check(renderer.stepReverse { false }) { "预读被取消" }
                        index++
                    }
                    GLES30.glFinish()
                    val elapsed = (System.nanoTime() - start) / 1e6
                    renderer.endReverse()
                    elapsed
                }
            }
            prereadRun(0)
            for (pair in 0 until repeats) for ((name, mask) in PER_FRAME) {
                record(preread, "base@$name", prereadRun(0))
                record(preread, name, prereadRun(mask))
            }
            report.put("preread", preread).put("precompute", precompute)
            check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "A/B 结束时有 GL 错误" }
        }
    }

    /**
     * 条件表。**只留不会反馈进模拟的那几项**：2026-09-17 首轮实测发现，跳掉压力里的任何
     * 一级（splat / blur / jacobi / project）都会改变压力场的内容，进而改变活动 tile 列表
     * 与粒子的散布范围——`no-jacobi` 与 `no-project` 因此比基线还**慢** 9%–23%。这类消融
     * 测的不是「那一段值多少」，而是「场坏掉之后整条管线变成什么样」，不能当份额用。
     *
     * 绘制侧（pass1 / resolve / clear）与历史恢复不回灌模拟，消融是干净的。
     * `no-pressure`（整段不做）保留，但同样会让粒子的散布变化，只能当量级看。
     */
    private val DRAW = listOf("no-pass0" to ParticleAblation.PASS0,
        "no-pass1" to ParticleAblation.PASS1, "no-resolve" to ParticleAblation.RESOLVE,
        "no-clear" to ParticleAblation.CLEAR)

    private val DISMISS = DRAW + listOf("no-pressure" to ParticleAblation.PRESSURE)

    private val APPEAR = DRAW

    private val PREREAD = listOf("no-history" to ParticleAblation.HISTORY,
        "no-pressure" to ParticleAblation.PRESSURE)

    private fun bench(context: Context, spec: ParticleDismissSpec, width: Int, height: Int,
                      report: JSONObject, repeats: Int, forwardFrames: Int) {
        val input = ParticleMicroflakeRenderer.fromSpec(context.assets, width, height,
            context.resources.displayMetrics.density, spec)
        val samples = ParticleReversePlan.samplesFor(ParticleReversePlan.APPEARANCE_SECONDS, 60f)
        withEgl(context.assets, width, height) { shared ->
            report.put("renderer", GLES30.glGetString(GLES30.GL_RENDERER) ?: "")
                .put("count", input.materials.count)
            // ---- 1. 现场消散 ----
            // 成对执行：每个条件都紧挨着它自己的基线跑一遍，比值按对取。
            // 两条纪律来自 2026-09-17 首轮的失败：
            // ① **整条序列只在末尾 glFinish 一次**。逐帧 glFinish 会在每次 CPU 提交期间让
            //    GPU 空转，调速器随后降频——首轮实测同一条件的两遍差到 2–3 倍，而且是
            //    「先快后慢再稳住」的形状，不是热漂移。
            // ② 先跑一遍热身丢掉，再按「外层配对、内层条件」轮转，让漂移平摊到所有条件上。
            val dismiss = JSONObject()
            fun dismissRun(mask: Int, first: Boolean): Double =
                ParticleMicroflakeRenderer(context.assets, shared, input).use { renderer ->
                    renderer.prepare()
                    if (first) report.put("sizes", JSONArray(renderer.diagnosticSizes.toList()))
                    renderer.draw(0f, batchPressure = true)
                    GLES30.glFinish()
                    ParticleAblation.with(mask) {
                        val start = System.nanoTime()
                        for (frame in 1..forwardFrames) {
                            renderer.probeFrame(frame, "play")
                            renderer.draw(frame / forwardFrames.toFloat(), batchPressure = true)
                        }
                        GLES30.glFinish()
                        (System.nanoTime() - start) / 1e6
                    }
                }
            dismissRun(0, true)  // 热身，不计
            for (pair in 0 until repeats) for ((name, mask) in DISMISS) {
                record(dismiss, "base@$name", dismissRun(0, false))
                record(dismiss, name, dismissRun(mask, false))
            }
            report.put("dismiss", dismiss).put("dismissFrames", forwardFrames)
            // ---- 2. 回放出现：历史只算一次，之后所有条件共用同一份（没有任何反馈进模拟）----
            val appear = JSONObject()
            ParticleMicroflakeRenderer(context.assets, shared, input).use { renderer ->
                renderer.prepare()
                val precompute = System.nanoTime()
                check(renderer.prepareReverse(samples)) { "逆向准备被取消" }
                GLES30.glFinish()
                report.put("precomputeMs", (System.nanoTime() - precompute) / 1e6)
                    .put("appearSamples", samples)
                fun appearRun(mask: Int): Double = ParticleAblation.with(mask) {
                    val start = System.nanoTime()
                    for (frame in samples downTo 0) {
                        renderer.probeFrame(frame, "appear")
                        renderer.drawReverseFrame(frame)
                    }
                    GLES30.glFinish()
                    (System.nanoTime() - start) / 1e6
                }
                appearRun(0)
                for (pair in 0 until repeats) for ((name, mask) in APPEAR) {
                    record(appear, "base@$name", appearRun(0))
                    record(appear, name, appearRun(mask))
                }
            }
            report.put("appear", appear)
            // ---- 3. 左滑预读：每个条件从零开始算一遍整段历史 ----
            val preread = JSONObject()
            fun prereadRun(mask: Int): Double =
                ParticleMicroflakeRenderer(context.assets, shared, input).use { renderer ->
                    renderer.prepare()
                    ParticleAblation.with(mask) {
                        renderer.beginReverse(samples, samples, precomputeProbe = false)
                        val start = System.nanoTime()
                        var index = 0
                        while (renderer.reverseIncomplete) {
                            renderer.probeFrame(index, "preread")
                            check(renderer.stepReverse { false }) { "预读被取消" }
                            index++
                        }
                        GLES30.glFinish()
                        val elapsed = (System.nanoTime() - start) / 1e6
                        renderer.endReverse()
                        elapsed
                    }
                }
            prereadRun(0)
            for (pair in 0 until repeats) for ((name, mask) in PREREAD) {
                record(preread, "base@$name", prereadRun(0))
                record(preread, name, prereadRun(mask))
            }
            report.put("preread", preread)
            check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "基准结束时有 GL 错误" }
        }
    }

    /** 每个条件累积每一遍的整段耗时；不保留像素，也不逐帧 glFinish。 */
    private fun record(target: JSONObject, name: String, total: Double) {
        val row = target.optJSONArray(name) ?: JSONArray()
        row.put(round2(total))
        target.put(name, row)
    }

    private fun round2(v: Double) = Math.round(v * 100) / 100.0

    private fun withEgl(assets: android.content.res.AssetManager, width: Int, height: Int,
                        action: (ParticleMicroflakeRenderer.Shared) -> Unit) {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1))
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, 0x40,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE),
            0, configs, 0, 1, count, 0) && count[0] > 0)
        val gl = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        val surface = EGL14.eglCreatePbufferSurface(display, configs[0],
            intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, surface, surface, gl))
        val shared = ParticleMicroflakeRenderer.Shared(assets)
        try { shared.ensure(width, height); action(shared) } finally {
            runCatching { shared.close() }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, gl)
            EGL14.eglReleaseThread()
        }
    }
}
