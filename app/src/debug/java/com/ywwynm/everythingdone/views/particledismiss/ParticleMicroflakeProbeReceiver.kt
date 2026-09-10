package com.ywwynm.everythingdone.views.particledismiss

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES30
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/** 只读 adb 预先放入专用目录的测试素材，不访问记事或应用设置。 */
class ParticleMicroflakeProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val scenes = listOf("ironman", "thanos", "kobe", "language", "color", "attachment", "attachment-image",
            "holdout-notification", "holdout-photo", "holdout-dark", "holdout-wide", "holdout-tall", "holdout-alpha",
            "holdout-coffee", "holdout-colored-panel", "holdout-monochrome", "holdout-compact-dialog")
        val requested = intent.getStringExtra("scene")
        val selected = if (requested == null) scenes else listOf(requested).filter { it in scenes }
        if (selected.isEmpty() || !running.compareAndSet(false, true)) return
        val scale = intent.getFloatExtra("scale", 1f).coerceIn(.5f, 2.5f)
        val app = context.applicationContext
        Thread({
            val root = File(app.getExternalFilesDir(null), "particle-unified")
            val out = File(root, "generated").apply { mkdirs() }
            try {
                for (scene in selected) run(app, root, out, scene, scale)
                File(out, "done.json").writeText(JSONObject().put("scenes", JSONArray(selected)).put("ok", true).toString())
            } catch (error: Throwable) {
                File(out, "error.txt").writeText(error.stackTraceToString())
                Log.e("ParticleProbe", "验证失败", error)
            } finally { running.set(false) }
        }, "ParticleProbe").start()
    }

    private fun run(context: Context, root: File, out: File, scene: String, scale: Float) {
        val meta = JSONObject(File(root, "$scene.json").readText())
        val frame = meta.getJSONArray("frame"); val rect = meta.getJSONArray("rect")
        val fw = frame.getInt(0); val fh = frame.getInt(1)
        val width = (fw * scale).toInt(); val height = (fh * scale).toInt()
        val bitmap = BitmapFactory.decodeFile(File(root, "$scene.png").path) ?: error("缺少前景 $scene")
        val cardWidth = rect.getInt(2) - rect.getInt(0); val cardHeight = rect.getInt(3) - rect.getInt(1)
        val before = System.nanoTime()
        val rules = ParticleMicroflakeRules.read(context.assets.open("particle-dismiss/rules.properties"), context.assets.open("particle-dismiss/common-release.f32"))
        val materials = run {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            ParticleMicroflakeModel.build(cardWidth.toFloat(), cardHeight.toFloat(), pixels, bitmap.width,
                bitmap.height, meta.getDouble("direction").toFloat(), meta.getLong("seed"), rules)
        }
        val report = JSONObject().put("scene", scene).put("generated", true).put("width", width).put("height", height)
            .put("count", materials.count).put("modelMs", (System.nanoTime() - before) / 1e6).put("modelStages", JSONObject(materials.statistics))
        val input = ParticleMicroflakeRenderer.Input(fw.toFloat(), fh.toFloat(), cardWidth.toFloat(), cardHeight.toFloat(),
            rect.getDouble(0).toFloat(), rect.getDouble(1).toFloat(), meta.getDouble("direction").toFloat(),
            bitmap, materials, context.assets.open("particle-dismiss/common-flow.f16").use { it.readBytes() }, rules)
        fun saveValues(name: String, values: FloatArray) {
            val bytes = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            bytes.asFloatBuffer().put(values); File(out, "$scene-$name.f32").writeBytes(bytes.array())
        }
        saveValues("materials", materials.values); saveValues("pigment", materials.pigment)
        withEgl(width, height) {
            report.put("renderer", GLES30.glGetString(GLES30.GL_RENDERER)).put("version", GLES30.glGetString(GLES30.GL_VERSION))
            ParticleMicroflakeRenderer(context.assets, width, height, input).use { renderer ->
                val prepare = System.nanoTime(); renderer.prepare(); GLES30.glFinish()
                report.put("prepareMs", (System.nanoTime() - prepare) / 1e6)
                val samples = JSONArray()
                for (i in 0..60) {
                    val t = i / 60f
                    val start = System.nanoTime(); renderer.draw(t); GLES30.glFinish()
                    samples.put((System.nanoTime() - start) / 1e6)
                    if (i in listOf(0, 10, 20, 34, 48, 60)) {
                        saveFrame(File(out, "$scene-$i.png"), width, height)
                    }
                    if (i == 34) {
                        val state = renderer.readState(); val bytes = ByteArray(state.remaining()); state.get(bytes)
                        File(out, "$scene-state034.f32").writeBytes(bytes)
                    }
                }
                check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "渲染产生 GL 错误" }
                report.put("frameMs", samples)
            }
        }
        bitmap.recycle()
        File(out, "$scene.json").writeText(report.toString(2))
        Log.i("ParticleProbe", "完成 $scene 独立建材 count=${materials.count}")
    }

    private fun saveFrame(file: File, width: Int, height: Int) {
        val buffer = ByteBuffer.allocateDirect(width * height * 4)
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
        val pixels = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val r = buffer.get().toInt() and 255; val g = buffer.get().toInt() and 255
            val b = buffer.get().toInt() and 255; val a = buffer.get().toInt() and 255
            // readback 为预乘颜色；Bitmap.setPixels 接收直通颜色。
            fun straight(v: Int) = if (a == 0) 0 else ((v * 255 + a / 2) / a).coerceAtMost(255)
            pixels[(height - 1 - y) * width + x] = (a shl 24) or (straight(r) shl 16) or (straight(g) shl 8) or straight(b)
        }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun floats(file: File): FloatArray {
        val bytes = file.readBytes()
        return FloatArray(bytes.size / 4).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
    }

    private fun withEgl(width: Int, height: Int, action: () -> Unit) {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1))
        val configs = arrayOfNulls<EGLConfig>(1); val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, 0x40,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE),
            0, configs, 0, 1, count, 0) && count[0] > 0)
        val gl = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        val surface = EGL14.eglCreatePbufferSurface(display, configs[0],
            intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, surface, surface, gl))
        try { action() } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface); EGL14.eglDestroyContext(display, gl); EGL14.eglReleaseThread()
            // display 与应用 HWUI 共用，不能终止其他活跃渲染器。
        }
    }

    companion object { private val running = AtomicBoolean() }
}
