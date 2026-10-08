package com.ywwynm.everythingdone.views.particledismiss

import android.app.Activity
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random

/**
 * 仅调试：飘散渲染的离屏逐帧导出，与桌面实现 `harmony_native.py` 逐帧对照
 * （`docs/features/harmony-particle-android-port/parity/`）。
 *
 * 输入：外部私有目录 `drift-parity/<run>/spec.json`（驱动脚本推上来）与同目录的快照 PNG。快照按真实路径解码成预乘字节
 * （`BitmapFactory` 缺省预乘、`copyPixelsToBuffer`），模型与渲染器都是正式代码，只把出帧目标换成离屏帧缓冲、
 * 底色换成纯色。
 *
 * 输出（同目录）：`snapshot.rgba`（解码后的预乘字节，桌面直接用同一份）、`frame-<序号>.png`（自上而下）、
 * `result.json`（模型推出的量与内容分析的结果）；失败时写 `error.txt`。不读写记事、不改设置。
 *
 * 用法：`am start -n <包>/.views.particledismiss.DriftParityRenderActivity --es run <名字>`
 */
class DriftParityRenderActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 另一种用法 `--es inputs <名字>`：按 cases.json 逐条调用正式的 ParticleDriftModel.random，把生成的输入写进
        // inputs.json，供桌面模拟器的 Python 移植（android_drift.py）逐位核对
        intent.getStringExtra("inputs")?.let { name ->
            val folder = File(getExternalFilesDir(null), "drift-parity/$name")
            runCatching { DriftParityRender.inputs(assets, folder) }.onFailure {
                Log.e("DriftParity", "输入导出失败", it)
                runCatching { File(folder, "error.txt").writeText(Log.getStackTraceString(it)) }
            }
            finish()
            return
        }
        // 第三种用法 `--es bench <名字>`：GPU 基准（bench.json → bench-result.json），见 DriftParityRender.bench
        intent.getStringExtra("bench")?.let { name ->
            val folder = File(getExternalFilesDir(null), "drift-parity/$name")
            val assets = assets
            ParticleGl.post {
                runCatching { DriftParityRender.bench(assets, folder) }.onFailure {
                    Log.e("DriftParity", "基准失败", it)
                    runCatching { File(folder, "error.txt").writeText(Log.getStackTraceString(it)) }
                }
                runOnUiThread { finish() }
            }
            return
        }
        val run = intent.getStringExtra("run")
        if (run == null) {
            finish()
            return
        }
        val folder = File(getExternalFilesDir(null), "drift-parity/$run")
        val assets = assets
        // 导出完成之前留在前台：进程一旦转入缓存状态，三星的冻结机制会停住 GL 线程（写到一半的帧就此中断）
        ParticleGl.post {
            DriftParityRender.run(assets, folder)
            runOnUiThread { finish() }
        }
    }
}

internal object DriftParityRender {

    private const val TAG = "DriftParity"

    /** 按 cases.json（[{width, height, dx, dy, seed, amount, size}]）生成一次动画的输入，写 inputs.json。 */
    fun inputs(assets: AssetManager, folder: File) {
        val cases = JSONArray(File(folder, "cases.json").readText())
        val styles = ParticleDriftStyles.all(assets)
        val out = JSONArray()
        for (k in 0 until cases.length()) {
            val c = cases.getJSONObject(k)
            val model = ParticleDriftModel.random(c.getInt("width"), c.getInt("height"),
                c.getDouble("dx").toFloat(), c.getDouble("dy").toFloat(), c.optDouble("amount", 1.0).toFloat(),
                ParticleDriftModel.seededRandom(c.getInt("seed")), styles, size = c.optDouble("size", 1.0).toFloat(),
                touchGap = if (c.isNull("touchGap")) null else c.getDouble("touchGap").toFloat(),
                touchStrength = if (c.isNull("touchStrength")) null else c.getDouble("touchStrength").toFloat())
            out.put(JSONObject()
                .put("style", model.styleName ?: JSONObject.NULL)
                .put("latticeWidth", model.latticeWidth).put("latticeHeight", model.latticeHeight)
                .put("lattice", JSONArray(model.lattice.map { it.toDouble() }))
                .put("windX", model.windX.toDouble()).put("windY", model.windY.toDouble())
                .put("count", model.count)
                .put("sizeScale", model.sizeScale.toDouble())
                .put("touchGap", model.touchGap?.toDouble() ?: JSONObject.NULL)
                .put("fadeSizePower", model.fadeSizePower.toDouble())
                .put("fadeAlphaPower", model.fadeAlphaPower.toDouble())
                .put("overAlphaMul", model.overAlphaMul.toDouble())
                .put("turbulenceOffset", JSONArray(listOf(model.turbulenceOffsetX.toDouble(),
                    model.turbulenceOffsetY.toDouble())))
                .put("turbulenceFrame", model.turbulenceFrame?.let { f -> JSONArray(f.map { it.toDouble() }) }
                    ?: JSONObject.NULL)
                .put("scaleU", model.scaleU.toDouble())
                .put("startSeconds", model.startSeconds.toDouble())
                .put("bodyGoneSeconds", model.bodyGoneSeconds.toDouble())
                .put("totalSeconds", model.totalSeconds.toDouble()))
        }
        File(folder, "inputs.json").writeText(out.toString())
        Log.i(TAG, "输入导出完成 ${out.length()} 条 → ${folder.path}")
    }

    /**
     * GPU 基准。bench.json：`{"output":[宽,高], "frames":60, "repeats":7,
     * "cases":[{"name","width","height","pattern":"page|dialog|list","seed","dx","dy","additive"}]}`。
     *
     * 每个场景：合成快照 → 正式的输入（[ParticleDriftModel.random]）与内容分析 → 渲染器。条件为整套、去掉 compute、去掉颗粒绘制、
     * 去掉叠加、去掉卡片残留、全部去掉；每个条件把整段时间轴（frames 帧，设备时刻 startSeconds → totalSeconds）画一遍，
     * 每帧先清离屏目标（与窗口每拍清屏同量），只在末尾 glFinish 一次。先整套热身一遍，条件按「外层重复、内层轮换」执行，取中位数。
     * 输出每帧的墙钟（含 GPU）与提交耗时（CPU 发命令）。
     */
    fun bench(assets: AssetManager, folder: File) {
        check(ParticleGl.ensureContextReady()) { "粒子 GL 上下文不可用" }
        ParticleDriftRenderer.ensurePrograms(assets)
        val spec = JSONObject(File(folder, "bench.json").readText())
        val output = spec.getJSONArray("output")
        val outW = output.getInt(0)
        val outH = output.getInt(1)
        val frames = spec.optInt("frames", 60)
        val repeats = spec.optInt("repeats", 7)
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        val texture = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, outW, outH, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glGenFramebuffers(1, ids, 0)
        val framebuffer = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, texture, 0)
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) { "离屏帧缓冲不完整" }
        val all = ParticleDriftRenderer.SKIP_COMPUTE or ParticleDriftRenderer.SKIP_PARTICLES or
            ParticleDriftRenderer.SKIP_COMPOSITE or ParticleDriftRenderer.SKIP_CARD
        val conditions = listOf("full" to 0, "noCompute" to ParticleDriftRenderer.SKIP_COMPUTE,
            "noParticles" to ParticleDriftRenderer.SKIP_PARTICLES, "noComposite" to ParticleDriftRenderer.SKIP_COMPOSITE,
            "noCard" to ParticleDriftRenderer.SKIP_CARD, "none" to all)
        val results = JSONArray()
        val cases = spec.getJSONArray("cases")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val width = case.getInt("width")
            val height = case.getInt("height")
            val rgba = synthetic(case.getString("pattern"), width, height, case.optInt("seed", 1))
            val model = ParticleDriftModel.random(width, height, case.optDouble("dx", 0.0).toFloat(),
                case.optDouble("dy", -1.0).toFloat(), case.optDouble("amount", 1.0).toFloat(),
                ParticleDriftModel.seededRandom(case.optInt("seed", 1)), ParticleDriftStyles.all(assets),
                size = case.optDouble("size", 1.0).toFloat())
            val content = ParticleDriftContent.analyse(width, height, rgba, model.count)
            if (spec.optBoolean("fast", false)) {
                results.put(ParticleFastBench.drift(assets, model, content, rgba, case,
                    outW, outH, framebuffer, frames, repeats, folder))
                continue
            }
            val renderer = ParticleDriftRenderer(assets, model, case.optBoolean("additive", false))
            renderer.prepare(ParticleMicroflakePreparation.Pixels(width, height, rgba, null), content)
            val originX = (outW - width) / 2f
            val originY = (outH - height) / 2f
            val times = FloatArray(frames) {
                model.startSeconds + (model.totalSeconds - model.startSeconds) * it / (frames - 1).coerceAtLeast(1)
            }
            fun pass(skip: Int, reuse: Boolean = true, timeline: FloatArray = times): LongArray {
                renderer.reuseParticleFrames = reuse
                ParticleDriftRenderer.debugSkip = skip
                GLES30.glFinish()
                val started = System.nanoTime()
                var issue = 0L
                for (time in timeline) {
                    val at = System.nanoTime()
                    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
                    GLES30.glViewport(0, 0, outW, outH)
                    GLES30.glDisable(GLES30.GL_BLEND)
                    GLES30.glClearColor(0f, 0f, 0f, 0f)
                    GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                    renderer.draw(time, originX, originY, framebuffer, null)
                    issue += System.nanoTime() - at
                }
                GLES30.glFinish()
                val total = System.nanoTime() - started
                ParticleDriftRenderer.debugSkip = 0
                return longArrayOf(total, issue)
            }
            pass(0)
            val samples = conditions.associate { it.first to ArrayList<LongArray>() }
            for (r in 0 until repeats) for (c in conditions.indices) {
                val (name, skip) = conditions[(c + r) % conditions.size]
                samples.getValue(name) += pass(skip)
            }
            // 同一份时间序列成对比较：每个模拟时刻连续画两次，模拟慢滑与末帧交接。
            val repeated = FloatArray(times.size * 2) { times[it / 2] }
            val reuseCosts = Array(2) { ArrayList<Double>() }
            repeat(repeats) { round ->
                for (offset in 0..1) {
                    val choice = (round + offset) % 2
                    reuseCosts[choice] += pass(0, choice == 1, repeated)[0] / 1e6 / repeated.size
                }
            }
            // 真正跑正式渲染器，覆盖重复时刻、倒放、移动原点、视口尺寸变化与浅/深背景。
            // baseline 与复用使用两个独立渲染器，防止基线绘制替优化路径预先填好缓存。
            val verification = if (spec.optBoolean("verifyReuse", false)) {
                val baseline = ParticleDriftRenderer(assets, model, case.optBoolean("additive", false))
                baseline.prepare(ParticleMicroflakePreparation.Pixels(width, height, rgba, null), content)
                baseline.reuseParticleFrames = false
                renderer.reuseParticleFrames = true
                val pixelsA = ByteBuffer.allocateDirect(outW * outH * 4)
                val pixelsB = ByteBuffer.allocateDirect(outW * outH * 4)
                val indices = intArrayOf(0, 0, frames / 2, frames / 2, frames / 4, frames / 4, frames - 1, frames - 1)
                var different = 0L
                for ((k, frame) in indices.withIndex()) {
                    val viewW = if (k == 5) outW - 1 else outW
                    val viewH = if (k == 5) outH - 1 else outH
                    // 不只重复同一个浮点时间：一帧内不同时间也必须命中相同的量化状态。
                    val frameNumber = kotlin.math.floor(times[frame] * ParticleDriftModel.HZ + 1e-3f)
                    val time = (frameNumber + if (k % 2 == 0) .2f else .6f) / ParticleDriftModel.HZ
                    fun read(r: ParticleDriftRenderer, dest: ByteBuffer) {
                        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
                        GLES30.glViewport(0, 0, viewW, viewH)
                        GLES30.glDisable(GLES30.GL_BLEND)
                        val background = if (k % 2 == 0) .12f else .8f
                        GLES30.glClearColor(background, background, background, 1f)
                        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                        r.draw(time, originX + k % 3, originY, framebuffer)
                        dest.clear()
                        GLES30.glReadPixels(0, 0, outW, outH, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, dest)
                    }
                    read(baseline, pixelsA)
                    read(renderer, pixelsB)
                    for (i in 0 until pixelsA.capacity()) if (pixelsA.get(i) != pixelsB.get(i)) different++
                }
                baseline.close()
                JSONObject().put("frames", indices.size).put("differentBytes", different)
            } else JSONObject()
            renderer.close()
            val perFrame = JSONObject()
            for ((name, list) in samples) {
                val totals = list.map { it[0] / 1e6 / frames }.sorted()
                val issues = list.map { it[1] / 1e6 / frames }.sorted()
                perFrame.put(name, JSONObject().put("ms", totals[totals.size / 2]).put("issueMs", issues[issues.size / 2])
                    .put("minMs", totals.first()).put("maxMs", totals.last()))
            }
            results.put(JSONObject().put("name", case.getString("name")).put("width", width).put("height", height)
                .put("count", model.count).put("replicas", content.replicas).put("style", model.styleName ?: "随机")
                .put("bodyGoneSeconds", model.bodyGoneSeconds.toDouble()).put("totalSeconds", model.totalSeconds.toDouble())
                .put("perFrame", perFrame)
                .put("repeatedFrame", JSONObject().put("baselineMs", reuseCosts[0].sorted()[repeats / 2])
                    .put("reusedMs", reuseCosts[1].sorted()[repeats / 2]))
                .put("reuseVerification", verification))
            Log.i(TAG, "基准 ${case.getString("name")} $perFrame")
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
        File(folder, "bench-result.json").writeText(JSONObject().put("output", output).put("frames", frames)
            .put("repeats", repeats).put("cases", results).toString())
        Log.i(TAG, "基准完成 → ${folder.path}")
    }

    /** 合成快照（预乘 RGBA8）：page 整屏纯色底加文字行，dialog 白底圆角加彩色按钮，list 浅灰底上几张彩色卡片。 */
    private fun synthetic(pattern: String, width: Int, height: Int, seed: Int): ByteBuffer {
        val random = Random(seed.toLong())
        val buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        val cards = List(6) { k ->
            intArrayOf(24, 24 + k * (height - 48) / 6, width - 24, 24 + (k + 1) * (height - 48) / 6 - 24,
                80 + random.nextInt(160), 80 + random.nextInt(160), 80 + random.nextInt(160))
        }
        val radius = 48
        for (y in 0 until height) for (x in 0 until width) {
            var r: Int
            var g: Int
            var b: Int
            var a = 255
            when (pattern) {
                "page" -> {
                    r = 200; g = 230; b = 150
                    if (y < 160) { r = 190; g = 220; b = 140 }
                    val line = (y - 300) / 72
                    if (y in 300..1800 && (y - 300) % 72 < 26 && x in 48 until 48 + 300 + (line * 97) % 900) {
                        r = 40; g = 40; b = 40
                    }
                }
                "dialog" -> {
                    r = 255; g = 255; b = 255
                    val cx = if (x < radius) radius - x else if (x >= width - radius) x - (width - radius - 1) else 0
                    val cy = if (y < radius) radius - y else if (y >= height - radius) y - (height - radius - 1) else 0
                    if (cx * cx + cy * cy > radius * radius) a = 0
                    if (y in 60 until 110 && x in 60 until 600) { r = 30; g = 30; b = 30 }
                    if (y in height - 120 until height - 50 && x in width - 320 until width - 60) { r = 233; g = 30; b = 99 }
                }
                else -> {
                    r = 238; g = 238; b = 238
                    for (card in cards) if (x in card[0] until card[2] && y in card[1] until card[3]) {
                        r = card[4]; g = card[5]; b = card[6]
                        if ((y - card[1]) % 60 < 22 && (y - card[1]) > 40 && x < card[0] + 400) { r = 30; g = 30; b = 30 }
                    }
                }
            }
            buffer.put((r * a / 255).toByte()).put((g * a / 255).toByte()).put((b * a / 255).toByte()).put(a.toByte())
        }
        buffer.rewind()
        return buffer
    }


    fun run(assets: AssetManager, folder: File) {
        val started = SystemClock.elapsedRealtime()
        runCatching { render(assets, folder, started) }.onFailure {
            Log.e(TAG, "离屏导出失败", it)
            runCatching { File(folder, "error.txt").writeText(Log.getStackTraceString(it)) }
        }
    }

    private fun render(assets: AssetManager, folder: File, started: Long) {
        check(ParticleGl.ensureContextReady()) { "粒子 GL 上下文不可用" }
        val spec = JSONObject(File(folder, "spec.json").readText())
        val bitmap = checkNotNull(BitmapFactory.decodeFile(File(folder, spec.getString("snapshot")).path)) { "快照解码失败" }
        val width = bitmap.width
        val height = bitmap.height
        val rgba = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        bitmap.copyPixelsToBuffer(rgba)
        rgba.rewind()
        File(folder, "snapshot.rgba").outputStream().use { out ->
            val bytes = ByteArray(rgba.capacity())
            rgba.get(bytes)
            rgba.rewind()
            out.write(bytes)
        }

        val latticeSpec = spec.getJSONObject("lattice")
        val latticeValues = latticeSpec.getJSONArray("values")
        val lattice = FloatArray(latticeValues.length()) { latticeValues.getDouble(it).toFloat() }
        val wind = spec.getJSONArray("wind")
        val offset = spec.optJSONArray("turbulenceOffset")
        val frame = spec.optJSONArray("turbulenceFrame")
        val model = ParticleDriftModel(width, height, lattice, latticeSpec.getInt("width"), latticeSpec.getInt("height"),
            wind.getDouble(0).toFloat(), wind.getDouble(1).toFloat(), spec.getInt("count"),
            fadeSizePower = spec.optDouble("fadeSizePower", .5).toFloat(),
            fadeAlphaPower = spec.optDouble("fadeAlphaPower", .5).toFloat(),
            overAlphaMul = spec.optDouble("overAlphaMul", ParticleDriftRenderer.OVER_ALPHA_MUL.toDouble()).toFloat(),
            styleName = spec.optString("style", "parity"),
            turbulenceOffsetX = offset?.getDouble(0)?.toFloat() ?: 0f,
            turbulenceOffsetY = offset?.getDouble(1)?.toFloat() ?: 0f,
            turbulenceFrame = frame?.let { FloatArray(4) { i -> it.getDouble(i).toFloat() } },
            sizeScale = spec.optDouble("sizeScale", 1.0).toFloat().let { scale ->
                if (spec.has("displayDensity")) ParticleDriftModel.sizeScaleForDisplay(
                    scale, spec.getDouble("displayDensity").toFloat()) else scale
            },
            touchGap = if (spec.isNull("touchGap")) null else spec.getDouble("touchGap").toFloat())
        val content = if (spec.optBoolean("content", false)) ParticleDriftContent.analyse(width, height, rgba, model.count)
            else ParticleDriftContent.Analysis.NONE
        val additive = spec.getBoolean("additive")
        val renderer = ParticleDriftRenderer(assets, model, additive)
        renderer.prepare(ParticleMicroflakePreparation.Pixels(width, height, rgba, null), content)

        val output = spec.getJSONArray("output")
        val outW = output.getInt(0)
        val outH = output.getInt(1)
        val origin = spec.getJSONArray("origin")
        val expansionSpec = spec.getJSONArray("expansion")
        val expansion = floatArrayOf(expansionSpec.getDouble(0).toFloat(), expansionSpec.getDouble(1).toFloat())
        val background = spec.getJSONArray("background")

        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        val texture = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, outW, outH, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glGenFramebuffers(1, ids, 0)
        val framebuffer = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, texture, 0)
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) { "离屏帧缓冲不完整" }

        val pixels = ByteBuffer.allocateDirect(outW * outH * 4).order(ByteOrder.nativeOrder())
        val row = ByteArray(outW * 4)
        val flipped = ByteBuffer.allocateDirect(outW * outH * 4).order(ByteOrder.nativeOrder())
        val image = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val times = spec.getJSONArray("times")
        val files = JSONArray()
        for (k in 0 until times.length()) {
            val time = times.getDouble(k).toFloat()
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
            GLES30.glViewport(0, 0, outW, outH)
            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glClearColor(background.getDouble(0).toFloat() / 255f, background.getDouble(1).toFloat() / 255f,
                background.getDouble(2).toFloat() / 255f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            renderer.draw(time, origin.getDouble(0).toFloat(), origin.getDouble(1).toFloat(), framebuffer, expansion)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
            GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 4)
            pixels.rewind()
            GLES30.glReadPixels(0, 0, outW, outH, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
            // 帧缓冲第 0 行在最下面：翻成自上而下
            flipped.rewind()
            for (y in outH - 1 downTo 0) {
                pixels.position(y * outW * 4)
                pixels.get(row)
                flipped.put(row)
            }
            flipped.rewind()
            image.copyPixelsFromBuffer(flipped)
            val name = "frame-%03d.png".format(k)
            File(folder, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            files.put(name)
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
        renderer.close()

        val result = JSONObject()
            .put("width", width).put("height", height)
            .put("sizeScale", model.sizeScale.toDouble())
            .put("count", model.count).put("total", model.count + content.replicas)
            .put("scaleU", model.scaleU.toDouble())
            .put("startSeconds", model.startSeconds.toDouble())
            .put("bodyGoneSeconds", model.bodyGoneSeconds.toDouble())
            .put("totalSeconds", model.totalSeconds.toDouble())
            .put("content", JSONObject()
                .put("panel", JSONArray(content.panel.map { it.toDouble() }))
                .put("panelWeight", content.panelWeight.toDouble())
                .put("grid", JSONArray(listOf(content.gridWidth, content.gridHeight)))
                .put("replicas", content.replicas)
                .put("cumulative", JSONArray(content.cumulative.map { it.toDouble() })))
            .put("frames", files)
            .put("elapsedMs", SystemClock.elapsedRealtime() - started)
        File(folder, "result.json").writeText(result.toString())
        Log.i(TAG, "离屏导出完成 ${files.length()} 帧 → ${folder.path}")
    }
}
