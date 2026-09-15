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
            inspectFrames: Set<Int> = emptySet()) {
        Thread({
            // withBaselineGpu 改的是进程级开关：同一轮里出现与消散两个动画层几乎同时释放，
            // 两个检查并行跑会互相改掉对方的开关，得到假通过/假失败。必须串行。
            synchronized(lock) { check(context, spec, width, height, output, inspectFrames) }
        }, "ParticleTrajectoryCheck").start()
    }

    private val lock = Any()
    private val sequence = java.util.concurrent.atomic.AtomicInteger()

    private fun check(context: Context, spec: ParticleDismissSpec, width: Int, height: Int,
                      output: File, inspectFrames: Set<Int>) {
            val result = JSONObject().put("width", width).put("height", height).put("seed", spec.hashSeed)
            try {
                verifyUploads()
                result.put("uploadBytesEqual", true)
                val input = ParticleMicroflakeRenderer.fromSpec(context.assets, width, height,
                    context.resources.displayMetrics.density, spec)
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
                        ParticlePerfTuning.creationAppearanceAliveRatio)
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
                }
            } catch (error: Throwable) { result.put("passed", false).put("error", error.stackTraceToString()) }
            finally { spec.snapshot.recycle() }
            // 一轮里出现与消散各产生一个动画层，两次结果都要留下，不能互相覆盖。
            File(output, "gpu-parity-%03d.json".format(sequence.incrementAndGet())).writeText(result.toString())
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
