package com.ywwynm.everythingdone.views.particledismiss

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES30
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer

/** 只读观察真实动画输入后离屏比较 GPU 输出，不触发页面或修改记事。诊断轮不计性能。 */
internal object ParticleTrajectoryCheck {
    fun run(context: Context, spec: ParticleDismissSpec, width: Int, height: Int, output: File,
            inspectFrames: Set<Int> = emptySet(), diagnostics: Boolean = false,
            concurrentLoad: Boolean = false, repeats: Int = 3, pinFrame: Int = -1) {
        Thread({
            // withBaselineGpu 改的是进程级开关：同一轮里出现与消散两个动画层几乎同时释放，
            // 两个检查并行跑会互相改掉对方的开关，得到假通过/假失败。必须串行。
            synchronized(lock) {
                check(context, spec, width, height, output, inspectFrames, diagnostics,
                    concurrentLoad, repeats, pinFrame)
            }
        }, "ParticleTrajectoryCheck").start()
    }

    private val lock = Any()
    private val sequence = java.util.concurrent.atomic.AtomicInteger()

    private fun check(context: Context, spec: ParticleDismissSpec, width: Int, height: Int,
                      output: File, inspectFrames: Set<Int>, diagnostics: Boolean = false,
                      concurrentLoad: Boolean = false, repeats: Int = 3, pinFrame: Int = -1) {
            val result = JSONObject().put("width", width).put("height", height).put("seed", spec.hashSeed)
            var load: ConcurrentLoad? = null
            try {
                verifyUploads()
                result.put("uploadBytesEqual", true)
                val input = ParticleMicroflakeRenderer.fromSpec(context.assets, width, height,
                    context.resources.displayMetrics.density, spec)
                if (concurrentLoad) load = ConcurrentLoad(context.assets, input).also { it.start() }
                result.put("diagnosticMode", diagnostics).put("concurrentLoad", concurrentLoad)
                withEgl(context.assets, width, height) { shared ->
                    val hashes = ArrayList<String>()
                    val referencePixels = mutableMapOf<Int, ByteArray>()
                    val pixels = ByteBuffer.allocateDirect(width * height * 4)
                    fun hash(): String {
                        pixels.clear()
                        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
                        return java.security.MessageDigest.getInstance("SHA-256").apply { update(pixels) }
                            .digest().joinToString("") { "%02x".format(it) }
                    }
                    // 参考路径：逐次 Jacobi 的原始求解器，且第一批各项 GPU 改动全部关闭。
                    ParticlePerfTuning.withBaselineGpu {
                        ParticleMicroflakeRenderer(context.assets, shared, input).use { renderer ->
                            renderer.prepare()
                            for (frame in 0..60) {
                                renderer.draw(frame / 60f); hashes += hash()
                                if (frame in inspectFrames && referencePixels.size < 2) {
                                    pixels.rewind()
                                    referencePixels[frame] = ByteArray(pixels.remaining()).also { pixels.get(it) }
                                }
                            }
                        }
                    }
                    val mismatches = JSONArray()
                    val mismatchedPixels = linkedMapOf<Int, ByteArray>()
                    ParticleMicroflakeRenderer(context.assets, shared, input).use { renderer ->
                        renderer.prepare()
                        for (frame in 0..60) {
                            renderer.draw(frame / 60f, batchPressure = true)
                            if (hash() != hashes[frame]) {
                                mismatches.put(frame)
                                if (mismatchedPixels.size < 2) {
                                    pixels.rewind()
                                    mismatchedPixels[frame] = ByteArray(pixels.remaining()).also { pixels.get(it) }
                                }
                            }
                        }
                    }
                    result.put("frames", 61).put("mismatches", mismatches).put("passed", mismatches.length() == 0)
                        .put("gpu", GLES30.glGetString(GLES30.GL_RENDERER))
                        .put("largeTile", ParticlePerfTuning.jacobiLargeTile)
                        .put("jacobiBatch", ParticlePerfTuning.jacobiLargeBatch)
                        .put("historyMerged", ParticlePerfTuning.historyInIntegration)
                        .put("skipBirth", ParticlePerfTuning.skipBeforeFirstBirth)
                    // 出现路径单独对照：历史转存合并（2.1）、首生前跳过（2.4）与大 tile（2.2）
                    // 只在这条路径上生效，消散路径的对照看不到它们。
                    fun reverseHashes(baseline: Boolean): List<String> {
                        val body = {
                            ParticleMicroflakeRenderer(context.assets, shared, input).use { renderer ->
                                renderer.prepare()
                                check(renderer.prepareReverse()) { "逆向准备被取消" }
                                val samples = ParticleReversePlan.samplesFor(
                                    ParticleReversePlan.APPEARANCE_SECONDS, 60f)
                                (0..samples).map { frame -> renderer.drawReverseFrame(frame); hash() }
                            }
                        }
                        return if (baseline) ParticlePerfTuning.withBaselineGpu(body) else body()
                    }
                    val reverseReference = reverseHashes(true)
                    val reverseOptimized = reverseHashes(false)
                    val reverseMismatches = JSONArray()
                    for (i in reverseReference.indices) {
                        if (reverseReference[i] != reverseOptimized[i]) reverseMismatches.put(i)
                    }
                    result.put("reverseFrames", reverseReference.size)
                        .put("reverseMismatches", reverseMismatches)
                        .put("reversePassed", reverseMismatches.length() == 0)
                    if (reverseMismatches.length() != 0) result.put("passed", false)
                    // 绝对散列落盘：跨版本对照只能靠它，后续批次改完直接与本次的数组比。
                    result.put("forwardHashes", JSONArray(hashes))
                        .put("reverseHashes", JSONArray(reverseOptimized))
                    // 2c：只积分到起点采样，再从起点播到 0。保留下来的每一帧都必须与完整
                    // 预计算时的同一帧逐位相同——时间值仍按总采样数归一，历史内容一个字节没变。
                    val samples = ParticleReversePlan.samplesFor(
                        ParticleReversePlan.APPEARANCE_SECONDS, 60f)
                    val start = ParticleReversePlan.appearanceStartFrame(input.materials.values, samples,
                        ParticlePerfTuning.appearanceAliveRatio)
                    val truncated = JSONArray()
                    ParticleMicroflakeRenderer(context.assets, shared, input).use { renderer ->
                        renderer.prepare()
                        renderer.beginReverse(samples, start)
                        while (renderer.reverseIncomplete) check(renderer.stepReverse { false })
                        renderer.endReverse()
                        for (frame in start downTo 0) {
                            renderer.drawReverseFrame(frame)
                            if (hash() != reverseOptimized[frame]) truncated.put(frame)
                        }
                    }
                    result.put("appearanceStart", start).put("appearanceSamples", samples)
                        .put("truncatedMismatches", truncated)
                        .put("truncatedPassed", truncated.length() == 0)
                    if (truncated.length() != 0) result.put("passed", false)
                    // 第四批 ②：「命中提前算好的轨迹」这条路与正常路径的两条对照。
                    //
                    // A. 预测快照 == 真实快照时，两条路径必须**逐位相同**：证明「先建前景纹理、
                    //    预算完再 replaceForeground」这一串操作本身不改变任何一帧。
                    // B. 预测快照 != 真实快照时，**末帧**（t=0）仍必须等于真实页面：
                    //    `material.vert` 第 0 遍按 `uv = source/card` 采样、画在 `world = offset+source`，
                    //    采样位置与屏幕位置是同一个恒等映射，所以末帧与格子分辨率无关。
                    //    这里故意用一张 R/B 对调的"预测图"建材（面板分类、片数、寿命全都不同），
                    //    再把真实像素换上去，末帧散列必须与 reverseOptimized[0] 相同。
                    val adopted = JSONArray()
                    val realPixels = checkNotNull(input.sourcePixels) { "缺少源像素" }
                    val realUpload = ParticleMicroflakePreparation.Pixels(
                        spec.snapshot.width, spec.snapshot.height,
                        ParticleMicroflakePreparation.packColors(realPixels), realPixels)
                    fun adoptedHashes(predicted: IntArray): List<String> {
                        val predictedInput = ParticleMicroflakeRenderer.fromPixels(
                            context.assets, width, height, context.resources.displayMetrics.density,
                            predicted, spec.snapshot.width, spec.snapshot.height,
                            spec.originXPx, spec.originYPx, spec.virtualTouchXPx, spec.virtualTouchYPx,
                            spec.hashSeed, spec.touchGap, spec.touchStrength)
                        return ParticleMicroflakeRenderer(context.assets, shared).use { renderer ->
                            renderer.preparePipeline(ParticleMicroflakeRenderer.sharedResources(context.assets))
                            renderer.prepareReversePipeline()
                            // 先用「预测图」上纹理，预算完再换成真实像素——与线上收养的顺序一致。
                            renderer.prepareForeground(ParticleMicroflakePreparation.Pixels(
                                spec.snapshot.width, spec.snapshot.height,
                                ParticleMicroflakePreparation.packColors(predicted), predicted))
                            renderer.prepare(predictedInput)
                            val predictedStart = ParticleReversePlan.appearanceStartFrame(
                                renderer.materialValues, samples, ParticlePerfTuning.appearanceAliveRatio)
                            renderer.beginReverse(samples, predictedStart, precomputeProbe = false,
                                throttled = true)
                            while (renderer.reverseIncomplete) check(renderer.stepReverse { false })
                            renderer.endReverse()
                            realUpload.rgba.rewind()
                            renderer.replaceForeground(realUpload)
                            (predictedStart downTo 0).map { frame -> renderer.drawReverseFrame(frame); hash() }
                        }
                    }
                    val sameSnapshot = adoptedHashes(realPixels)
                    for ((index, frame) in (start downTo 0).withIndex()) {
                        if (index < sameSnapshot.size && sameSnapshot[index] != reverseOptimized[frame]) {
                            adopted.put(frame)
                        }
                    }
                    val swapped = IntArray(realPixels.size) { i ->
                        val c = realPixels[i]
                        (c and 0xff00ff00.toInt()) or ((c ushr 16) and 255) or ((c and 255) shl 16)
                    }
                    val differentFinal = adoptedHashes(swapped).lastOrNull()
                    result.put("prewarmSameSnapshotMismatches", adopted)
                        .put("prewarmSameSnapshotPassed", adopted.length() == 0)
                        .put("prewarmFinalFrameEqual", differentFinal == reverseOptimized[0])
                    if (adopted.length() != 0 || differentFinal != reverseOptimized[0]) {
                        result.put("passed", false)
                    }
                    // 第二批新增的最大风险是「一个上下文里多个会话共用同一批程序对象与
                    // 累积纹理」：uniform 只要漏重设一项，交错播放就会画错。这里把两个会话
                    // 逐帧交错跑一遍，两边都必须与各自单独跑时逐位相同。
                    val interleaved = JSONArray()
                    ParticleMicroflakeRenderer(context.assets, shared, input).use { first ->
                        ParticleMicroflakeRenderer(context.assets, shared, input).use { second ->
                            first.prepare(); second.prepare()
                            for (frame in 0..60) {
                                first.draw(frame / 60f, batchPressure = true)
                                if (hash() != hashes[frame]) interleaved.put(frame)
                                second.draw(frame / 60f, batchPressure = true)
                                if (hash() != hashes[frame]) interleaved.put(-frame - 1)
                            }
                        }
                    }
                    // 真实的并发形态是「上一个弹窗消散 + 下一个弹窗出现」：一个逆向会话与一个
                    // 正向会话交错。只有逆向那一个占历史缓冲，不会把两份数十 MB 的缓存同时挂住。
                    ParticleMicroflakeRenderer(context.assets, shared, input).use { appearing ->
                        ParticleMicroflakeRenderer(context.assets, shared, input).use { dismissing ->
                            appearing.prepare(); dismissing.prepare()
                            val samples = ParticleReversePlan.samplesFor(
                                ParticleReversePlan.APPEARANCE_SECONDS, 60f)
                            appearing.beginReverse(samples)
                            while (appearing.reverseIncomplete) {
                                check(appearing.stepReverse { false })
                                // 预计算过程中夹一帧正向绘制，覆盖「预计算与播放交错」这条路径。
                                dismissing.draw(0f, batchPressure = true)
                            }
                            appearing.endReverse()
                            if (hash() != hashes[0]) interleaved.put(-1000)
                            for (frame in 0..samples) {
                                appearing.drawReverseFrame(frame)
                                if (hash() != reverseOptimized[frame]) interleaved.put(1000 + frame)
                                dismissing.draw(frame / 60f, batchPressure = true)
                                if (hash() != hashes[frame]) interleaved.put(-1000 - frame)
                            }
                        }
                    }
                    result.put("interleavedMismatches", interleaved)
                        .put("interleavedPassed", interleaved.length() == 0)
                    if (interleaved.length() != 0) result.put("passed", false)
                    // 不用换一个随机种子的通过结果掩盖失败：记录差异幅度与重复性。
                    val differences = JSONArray()
                    for ((frame, optimized) in mismatchedPixels) {
                        ParticlePerfTuning.withBaselineGpu {
                            ParticleMicroflakeRenderer(context.assets, shared, input).use { renderer ->
                                renderer.prepare()
                                for (index in 0..frame) renderer.draw(index / 60f)
                                val repeatedHash = hash()
                                pixels.rewind()
                                var changedPixels = 0; var maximum = 0; var total = 0L
                                for (i in optimized.indices step 4) {
                                    var changed = false
                                    repeat(4) { channel ->
                                        val delta = kotlin.math.abs((pixels.get().toInt() and 255) - (optimized[i+channel].toInt() and 255))
                                        if (delta > 0) changed = true
                                        maximum = maxOf(maximum, delta); total += delta
                                    }
                                    if (changed) changedPixels++
                                }
                                val detail = JSONObject().put("frame", frame).put("referenceRepeatEqual", repeatedHash == hashes[frame])
                                    .put("changedPixels", changedPixels).put("maxChannelDifference", maximum)
                                    .put("meanChannelDifference", total.toDouble()/optimized.size)
                                referencePixels[frame]?.let { original ->
                                    var changed = 0; var maxDelta = 0
                                    for (i in original.indices step 4) {
                                        var pixelChanged = false
                                        repeat(4) { channel ->
                                            val delta = kotlin.math.abs((original[i+channel].toInt() and 255) - (optimized[i+channel].toInt() and 255))
                                            if (delta > 0) pixelChanged = true
                                            maxDelta = maxOf(maxDelta, delta)
                                        }
                                        if (pixelChanged) changed++
                                    }
                                    detail.put("initialReferenceChangedPixels", changed).put("initialReferenceMaxChannelDifference", maxDelta)
                                }
                                differences.put(detail)
                            }
                        }
                    }
                    result.put("pixelDifferences", differences)
                    // 第五批：单像素差异的定性。见 batch5-results 第 3 节。
                    if (diagnostics) {
                        // 报告对象先挂上去：定位阶段抛异常（例如整帧像素撑爆堆）时，
                        // 前面那些自重复统计不能跟着一起丢。
                        val report = JSONObject()
                        result.put("diagnostics", report)
                        runCatching {
                            diagnose(context, shared, input, width, height, { hash() }, pixels,
                                samples, start, repeats, pinFrame, report)
                        }.onFailure { report.put("error", it.stackTraceToString()) }
                    }
                }
            } catch (error: Throwable) { result.put("passed", false).put("error", error.stackTraceToString()) }
            finally {
                load?.let { result.put("loadSamples", it.stop()) }
                spec.snapshot.recycle()
            }
            // 一轮里出现与消散各产生一个动画层，两次结果都要留下，不能互相覆盖。
            File(output, "gpu-parity-%03d.json".format(sequence.incrementAndGet())).writeText(result.toString())
    }

    // ==================== 第五批：单像素差异的定性 ====================
    //
    // 第四批在 OPPO 上两份对照各出现一次「整帧只有 1 个像素不同」，判为「并发 GPU 负载下的
    // 偶发」。这一节把那个判断换成可证伪的实验：**每条路径自己跑多遍**，先回答
    //
    //   ① 同一条路径重复跑，自己与自己逐位相同吗？（不同 ⇒ 非确定性在这条路径内部）
    //   ② 参考路径与优化路径的差异，是出在 GPU 算出来的**状态缓冲**上，还是只出在最后
    //      那一次光栅化上？（状态相同而画面不同 ⇒ 差异不在我们的 compute 里）
    //   ③ 差异像素落在哪里，两边各是什么值。
    //
    // 三条都不依赖「这一轮的随机种子恰好撞上」，所以 `diagnosticOnly` + 固定 `gpuCheckSeed`
    // 跑两遍（并发负载开 / 关）就能把「与并发有关」与「与种子有关」分开。

    /**
     * 一次路径运行的产物：逐帧画面散列、逐帧状态缓冲散列（可选）、指定帧的原始像素，
     * 以及 [unstableReads]——同一帧连读两次就不一样的帧号。
     *
     * [unstableReads] 是把「对照工具自己的问题」与「渲染结果真的不一样」分开的判据：
     * 画面没有重画，只是又 `glReadPixels` 了一次；两次读到的不一样只可能是回读本身不稳，
     * 与粒子管线无关。
     */
    private class Trace(val hashes: List<String>, val states: List<String>,
                        val pixels: Map<Int, ByteArray>, val unstableReads: List<Int> = emptyList())

    private fun sha(buffer: ByteBuffer): String =
        java.security.MessageDigest.getInstance("SHA-256").apply { update(buffer) }
            .digest().joinToString("") { "%02x".format(it) }

    private fun diagnose(context: Context, shared: ParticleMicroflakeRenderer.Shared,
                         input: ParticleMicroflakeRenderer.Input, width: Int, height: Int,
                         hash: () -> String, pixels: ByteBuffer,
                         samples: Int, start: Int, repeats: Int, pinFrame: Int,
                         report: JSONObject): JSONObject {
        val rounds = repeats.coerceIn(1, 24)
        report.put("repeats", rounds)
        // 每一帧都连读两次；两次不一样说明是回读本身不稳（画面并没有重画），与粒子管线无关。
        val unstableReads = JSONArray()
        report.put("unstableReads", unstableReads)

        fun capture(frames: Set<Int>, frame: Int, sink: MutableMap<Int, ByteArray>) {
            if (frame !in frames) return
            pixels.rewind()
            sink[frame] = ByteArray(pixels.remaining()).also { pixels.get(it) }
        }

        /** 正向消散 61 帧。[baseline] 为真时用逐次 Jacobi 的原始求解器。 */
        fun forward(baseline: Boolean, capturing: Set<Int> = emptySet(),
                    withState: Boolean = false): Trace {
            val body = {
                ParticleMicroflakeRenderer(context.assets, shared, input).use { renderer ->
                    renderer.prepare()
                    val h = ArrayList<String>(61); val s = ArrayList<String>(61)
                    val p = linkedMapOf<Int, ByteArray>(); val unstable = ArrayList<Int>()
                    for (frame in 0..60) {
                        renderer.draw(frame / 60f, batchPressure = !baseline)
                        val first = hash()
                        if (hash() != first) unstable += frame
                        h += first; capture(capturing, frame, p)
                        if (withState) s += sha(renderer.readState())
                    }
                    Trace(h, s, p, unstable)
                }
            }
            val trace = if (baseline) ParticlePerfTuning.withBaselineGpu(body) else body()
            val tag = if (baseline) "forward-reference" else "forward-optimized"
            for (f in trace.unstableReads) unstableReads.put("$tag:$f")
            return trace
        }

        /** 出现路径：预计算到 [target]，再从 [target] 逐帧倒放到 0（与线上播放顺序一致）。 */
        fun reverse(target: Int, capturing: Set<Int> = emptySet(), withState: Boolean = false): Trace {
            return ParticleMicroflakeRenderer(context.assets, shared, input).use { renderer ->
                renderer.prepare()
                renderer.beginReverse(samples, target)
                while (renderer.reverseIncomplete) check(renderer.stepReverse { false })
                renderer.endReverse()
                val h = arrayOfNulls<String>(samples + 1); val s = arrayOfNulls<String>(samples + 1)
                val p = linkedMapOf<Int, ByteArray>(); val unstable = ArrayList<Int>()
                for (frame in target downTo 0) {
                    renderer.drawReverseFrame(frame)
                    val first = hash()
                    if (hash() != first) unstable += frame
                    h[frame] = first; capture(capturing, frame, p)
                    if (withState) s[frame] = sha(renderer.readState())
                }
                for (f in unstable) unstableReads.put("reverse$target:$f")
                Trace(h.map { it ?: "" }, s.map { it ?: "" }, p, unstable)
            }
        }

        fun mismatches(a: List<String>, b: List<String>): List<Int> =
            a.indices.filter { it < b.size && a[it].isNotEmpty() && b[it].isNotEmpty() && a[it] != b[it] }

        // ---- ① 自重复：同一条路径连着跑 rounds 遍，每一遍都与第一遍比 ----
        val referenceForward = forward(true)
        val referenceSelf = JSONArray()
        val optimizedRuns = ArrayList<Trace>()
        val optimizedSelf = JSONArray()
        val abRuns = JSONArray()
        repeat(rounds) { round ->
            val optimized = forward(false)
            optimizedRuns += optimized
            abRuns.put(JSONArray(mismatches(referenceForward.hashes, optimized.hashes)))
            if (round > 0) optimizedSelf.put(
                JSONArray(mismatches(optimizedRuns[0].hashes, optimized.hashes)))
        }
        repeat(rounds - 1) {
            referenceSelf.put(JSONArray(mismatches(referenceForward.hashes, forward(true).hashes)))
        }
        report.put("forwardReferenceSelfMismatches", referenceSelf)
            .put("forwardOptimizedSelfMismatches", optimizedSelf)
            .put("forwardAbMismatches", abRuns)

        val fullRuns = ArrayList<Trace>()
        val truncatedRuns = ArrayList<Trace>()
        val reverseSelf = JSONArray()
        val truncatedSelf = JSONArray()
        val truncatedAb = JSONArray()
        repeat(rounds) { round ->
            val full = reverse(samples)
            fullRuns += full
            if (round > 0) reverseSelf.put(JSONArray(mismatches(fullRuns[0].hashes, full.hashes)))
            val truncated = reverse(start)
            truncatedRuns += truncated
            if (round > 0) truncatedSelf.put(
                JSONArray(mismatches(truncatedRuns[0].hashes, truncated.hashes)))
            truncatedAb.put(JSONArray(mismatches(fullRuns[0].hashes, truncated.hashes)))
        }
        report.put("reverseFullSelfMismatches", reverseSelf)
            .put("truncatedSelfMismatches", truncatedSelf)
            .put("truncatedAbMismatches", truncatedAb)
            .put("appearanceStart", start).put("appearanceSamples", samples)

        // ---- ②③ 把差异定位到像素、并判断它是出在状态缓冲还是光栅化上 ----
        fun frames(rows: JSONArray): List<Int> = (0 until rows.length()).flatMap { i ->
            val row = rows.getJSONArray(i); (0 until row.length()).map { row.getInt(it) }
        }.distinct().sorted()

        val located = JSONArray()
        report.put("located", located)
        val forwardBad = frames(abRuns)
        val truncatedBad = frames(truncatedAb)
        if (forwardBad.isNotEmpty()) {
            val one = forwardBad.take(1).toSet()
            val a = forward(true, one, withState = true)
            val b = forward(false, one, withState = true)
            for (frame in one) located.put(locate("forward-ab", frame, width, height, a, b))
        }
        if (truncatedBad.isNotEmpty()) {
            val one = truncatedBad.take(1).toSet()
            val a = reverse(samples, one, withState = true)
            val b = reverse(start, one, withState = true)
            for (frame in one) located.put(locate("truncated-ab", frame, width, height, a, b))
        }

        // ---- ④ 自重复出现分歧时定点复现：同一条路径反复跑到那一帧，直到再次分歧 ----
        //
        // 这一条才是第五批要定性的东西。A/B 两条路径**用的是不同的着色器**，差一个 ULP
        // 可以用代码生成解释；而「同一条路径与它自己不一样」没有任何代码上的自变量可言。
        val pinned = JSONArray()
        report.put("pinpointed", pinned)
        val selfCases = listOf(
            Triple("forward-reference-self", frames(referenceSelf),
                { f: Set<Int> -> forward(true, f, true) }),
            Triple("forward-optimized-self", frames(optimizedSelf),
                { f: Set<Int> -> forward(false, f, true) }),
            Triple("reverse-full-self", frames(reverseSelf),
                { f: Set<Int> -> reverse(samples, f, true) }),
            Triple("truncated-self", frames(truncatedSelf),
                { f: Set<Int> -> reverse(start, f, true) }))
        for ((label, list, again) in selfCases) {
            // 已知的临界帧可以直接指定（`gpuCheckPinFrame`）：自分歧一遍只有百分之几的概率，
            // 等它在 phase 1 里自己撞上来太靠运气，不如对着它反复跑。
            val forced = if (pinFrame in 0..samples && label.startsWith("reverse") ||
                pinFrame in 0..start && label.startsWith("truncated")) listOf(pinFrame) else emptyList()
            for (frame in (list + forced).distinct().take(2)) {
                val detail = JSONObject().put("comparison", label).put("frame", frame)
                // 一帧的原始像素在平板上是 16.9 MB，256 MB 堆里只能同时留两份：
                // 第一遍与「和它不一样的那一遍」。对得上的中间几遍立刻丢掉。
                val first = again(setOf(frame))
                var diverged: Trace? = null
                var attempts = 1
                while (attempts < PINPOINT_ATTEMPTS && diverged == null) {
                    val next = again(setOf(frame))
                    attempts++
                    if (first.hashes.getOrNull(frame) != next.hashes.getOrNull(frame)) diverged = next
                }
                detail.put("attempts", attempts).put("reproduced", diverged != null)
                diverged?.let {
                    detail.put("difference", locate(label, frame, width, height, first, it))
                }
                pinned.put(detail)
            }
        }
        return report
    }

    /**
     * 定点复现一次自分歧最多再跑几遍。
     *
     * 「同一条路径与它自己不一样」是第五批要定性的核心事实：A/B 两条路径用的是不同的着色器，
     * 差一个 ULP 还可以用驱动的代码生成解释；自分歧没有任何代码上的自变量可言。
     */
    private const val PINPOINT_ATTEMPTS = 60

    /**
     * 一帧的差异定位。
     *
     * `stateEqual` 是关键判据：两条路径在这一帧的**八分量状态缓冲**逐位相同却画出不同的像素，
     * 说明差异不在我们的 compute 里；状态就不同则说明积分或压力链路上有分歧。
     */
    private fun locate(label: String, frame: Int, width: Int, height: Int,
                       a: Trace, b: Trace): JSONObject {
        val detail = JSONObject().put("comparison", label).put("frame", frame)
            .put("hashEqual", a.hashes.getOrNull(frame) == b.hashes.getOrNull(frame))
        val left = a.states.getOrNull(frame); val right = b.states.getOrNull(frame)
        if (!left.isNullOrEmpty() && !right.isNullOrEmpty()) {
            detail.put("stateEqual", left == right).put("stateHashA", left).put("stateHashB", right)
        }
        val pa = a.pixels[frame]; val pb = b.pixels[frame]
        if (pa == null || pb == null || pa.size != pb.size) return detail.put("pixelsCaptured", false)
        val positions = JSONArray()
        var changed = 0; var maximum = 0
        for (i in pa.indices step 4) {
            var delta = 0
            repeat(4) { c -> delta = maxOf(delta, kotlin.math.abs(
                (pa[i + c].toInt() and 255) - (pb[i + c].toInt() and 255))) }
            if (delta == 0) continue
            changed++
            maximum = maxOf(maximum, delta)
            if (positions.length() >= 32) continue
            val index = i / 4
            // glReadPixels 自左下角起逐行返回，屏幕坐标要把行号翻过来。
            positions.put(JSONObject().put("x", index % width)
                .put("y", height - 1 - index / width).put("maxChannelDelta", delta)
                .put("a", rgba(pa, i)).put("b", rgba(pb, i)))
        }
        return detail.put("pixelsCaptured", true).put("changedPixels", changed)
            .put("maxChannelDifference", maximum).put("positions", positions)
    }

    private fun rgba(bytes: ByteArray, offset: Int) = JSONArray(
        (0..3).map { bytes[offset + it].toInt() and 255 })

    /**
     * 与被测对照**并发**跑在另一个 EGL 上下文里的 GPU 负载。
     *
     * 形态与 [ParticleCreationPrewarm] 的提前预算完全一样（同一批着色器、同样的分批节流、
     * 同样在自己的上下文里），但开关由对照自己掌握——这样「并发负载」就成了可以独立开关的
     * 自变量，不必依赖首页此刻恰好在不在预算。不渲染，所以共享资源取 128×128 就够。
     */
    private class ConcurrentLoad(private val assets: android.content.res.AssetManager,
                                 private val input: ParticleMicroflakeRenderer.Input) {
        @Volatile private var running = true
        private val samples = java.util.concurrent.atomic.AtomicInteger()
        private var thread: Thread? = null

        fun start() {
            thread = Thread({
                runCatching {
                    ParticleTrajectoryCheck.withEgl(assets, 128, 128) { shared ->
                        while (running) {
                            ParticleMicroflakeRenderer(assets, shared, input).use { renderer ->
                                renderer.prepare()
                                val total = ParticleReversePlan.samplesFor(
                                    ParticleReversePlan.APPEARANCE_SECONDS, 60f)
                                renderer.beginReverse(total, total, precomputeProbe = false,
                                    throttled = true)
                                while (running && renderer.reverseIncomplete) {
                                    if (!renderer.stepReverse { !running }) break
                                    samples.incrementAndGet()
                                }
                                renderer.endReverse()
                            }
                        }
                    }
                }.onFailure {
                    android.util.Log.w(ParticleMicroflakeRenderer.TAG, "并发负载线程失败", it)
                }
            }, "ParticleCheckLoad").apply { start() }
        }

        /** @return 负载线程总共推进的采样数；为 0 说明这一轮根本没有制造出并发负载。 */
        fun stop(): Int {
            running = false
            runCatching { thread?.join(5_000) }
            return samples.get()
        }
    }

    private fun verifyUploads() {
        check(ParticleMaterialNative.available)
        val random = java.util.Random(9018)
        val pixels = IntArray(1027) { random.nextInt() }
        for (count in listOf(1, 8192)) {
            val materials = FloatArray(count * 12) { random.nextFloat() * 2000 - 1000 }
            val oldColors = ByteBuffer.allocateDirect(pixels.size * 4)
            val oldState = ByteBuffer.allocateDirect(count * 32)
            val colors = ByteBuffer.allocateDirect(pixels.size * 4)
            val state = ByteBuffer.allocateDirect(count * 32)
            ParticleMaterialNative.packUploads(pixels, materials, count, oldColors, oldState)
            ParticleMaterialNative.packColors(pixels, colors)
            ParticleMaterialNative.packState(materials, count, state)
            check(oldColors == colors && oldState == state) { "分段上传改变了原始字节" }
        }
    }

    private fun withEgl(assets: android.content.res.AssetManager, width: Int, height: Int,
                        action: (ParticleMicroflakeRenderer.Shared) -> Unit) {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1))
        val configs = arrayOfNulls<EGLConfig>(1); val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, 0x40,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE), 0, configs, 0, 1, count, 0))
        val context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        val surface = EGL14.eglCreatePbufferSurface(display, configs[0],
            intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, surface, surface, context))
        // 离屏对照自带上下文，共享资源必须也是这个上下文私有的一份。
        val shared = ParticleMicroflakeRenderer.Shared(assets)
        try { shared.ensure(width, height); action(shared) } finally {
            runCatching { shared.close() }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface); EGL14.eglDestroyContext(display, context); EGL14.eglReleaseThread()
        }
    }
}
