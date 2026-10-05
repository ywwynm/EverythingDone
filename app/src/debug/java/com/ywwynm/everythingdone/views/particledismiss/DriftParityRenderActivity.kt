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

    /** 按 cases.json（[{width, height, dx, dy, seed, amount}]）生成一次动画的输入，写 inputs.json。 */
    fun inputs(assets: AssetManager, folder: File) {
        val cases = JSONArray(File(folder, "cases.json").readText())
        val styles = ParticleDriftStyles.all(assets)
        val out = JSONArray()
        for (k in 0 until cases.length()) {
            val c = cases.getJSONObject(k)
            val model = ParticleDriftModel.random(c.getInt("width"), c.getInt("height"),
                c.getDouble("dx").toFloat(), c.getDouble("dy").toFloat(), c.optDouble("amount", 1.0).toFloat(),
                ParticleDriftModel.seededRandom(c.getInt("seed")), styles)
            out.put(JSONObject()
                .put("style", model.styleName ?: JSONObject.NULL)
                .put("latticeWidth", model.latticeWidth).put("latticeHeight", model.latticeHeight)
                .put("lattice", JSONArray(model.lattice.map { it.toDouble() }))
                .put("windX", model.windX.toDouble()).put("windY", model.windY.toDouble())
                .put("count", model.count)
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
            turbulenceFrame = frame?.let { FloatArray(4) { i -> it.getDouble(i).toFloat() } })
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
