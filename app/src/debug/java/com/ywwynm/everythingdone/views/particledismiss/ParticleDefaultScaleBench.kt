package com.ywwynm.everythingdone.views.particledismiss

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.opengl.GLES30
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

/** 同一快照与随机种子的默认基准对照；只创建合成内容，不访问用户记事或偏好。 */
internal object ParticleDefaultScaleBench {
    fun run(context: Context, folder: File) {
        val assets = context.assets
        val resources = ParticleMicroflakeRenderer.sharedResources(assets)
        val rows = JSONArray()
        val fw = 1440; val fh = 3088
        ParticleSegmentBench.withEgl(assets, fw, fh) { shared ->
            for ((scene, dimensions) in listOf("card" to (630 to 300),
                "attachment" to (1050 to 1047), "color" to (1380 to 2281))) {
                val (width, height) = dimensions
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.rgb(245, 243, 240))
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                for (i in 0..5) {
                    paint.color = Color.HSVToColor(floatArrayOf(i * 60f, .75f, .75f))
                    val x = width * (.06f + (i % 3) * .31f)
                    val y = height * (.12f + (i / 3) * .55f)
                    canvas.drawRoundRect(x, y, x + width * .22f, y + height * .18f, 15f, 15f, paint)
                }
                paint.color = Color.rgb(48, 48, 48); paint.textSize = 36f
                canvas.drawText("Particle default scale", 30f, height * .55f, paint)
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                val driftSamples = ParticleDriftContent.samplePositions(width, height).map { index ->
                    val c = pixels[index]
                    (c and -0x1000000) or ((c and 255) shl 16) or (c and 0xff00) or ((c ushr 16) and 255)
                }.toIntArray()
                val loads = if (scene == "card") listOf(1f to 1f) else listOf(1f to 1f, 1.6f to 3f)
                for ((amount, size) in loads) {
                    val names = listOf("before", "aligned")
                    val buildMs = JSONObject()
                    val materials = names.associateWith { name ->
                        val start = System.nanoTime()
                        ParticleMicroflakeModel.build(width / 2f, height / 2f, pixels, width, height,
                            135f, 1008129L, resources.rules, cellDp = 0f, particleAmount = amount,
                            particleSizeScale = size, matchDriftDefaults = name == "aligned").also {
                            buildMs.put(name, (System.nanoTime() - start) / 1e6)
                        }
                    }
                    fun renderer(name: String): ParticleMicroflakeRenderer {
                        val input = ParticleMicroflakeRenderer.Input(fw / 2f, fh / 2f, width / 2f, height / 2f,
                            (fw - width) / 4f, (fh - height) / 4f, 135f, bitmap, materials.getValue(name),
                            resources.guide, resources.rules, pixels, confidence = resources.confidence)
                        return ParticleFastOptions.withOptions(ParticleFastOptions()) {
                            ParticleMicroflakeRenderer(assets, shared, input)
                        }
                    }
                    fun prepare(r: ParticleMicroflakeRenderer) {
                        ParticlePerfTuning.withPhase2(off = ParticlePerfTuning.P2_SRGB8_ACCUMULATION) { r.prepare() }
                    }
                    fun measure(name: String, replay: Boolean): Double = renderer(name).use { r ->
                        prepare(r)
                        if (replay) check(r.prepareReverse(36)) else r.draw(0f, batchPressure = true)
                        GLES30.glFinish()
                        val start = System.nanoTime()
                        if (replay) for (f in 0..72) r.drawReverseProgress(f / 72f, 36)
                        else for (f in 1..120) r.draw(f / 120f, batchPressure = true)
                        GLES30.glFinish()
                        (System.nanoTime() - start) / 1e6
                    }
                    val costs = names.associateWith { JSONObject() }
                    for ((path, replay) in listOf("forward" to false, "replay" to true)) {
                        names.forEach { measure(it, replay) }
                        val samples = names.associateWith { ArrayList<Double>() }
                        repeat(3) { rep -> for (i in names.indices) {
                            val name = names[(rep + i) % names.size]
                            samples.getValue(name).add(measure(name, replay))
                        } }
                        for (name in names) costs.getValue(name).put(path, JSONObject()
                            .put("medianMs", samples.getValue(name).sorted()[1])
                            .put("samplesMs", JSONArray(samples.getValue(name))))
                    }
                    val driftBase = ParticleDriftModel.count(width, height, amount)
                    val drift = ParticleDriftContent.analyseSamples(width, height, driftSamples, driftBase)
                    val counts = JSONObject(); val diameters = JSONObject()
                    for (name in names) {
                        val m = materials.getValue(name)
                        counts.put(name, m.count)
                        diameters.put(name, sqrt(m.cellX * m.particleScaleX * m.cellY * m.particleScaleY) /
                            ParticleMicroflakeModel.DP_UNITS * .9)
                    }
                    counts.put("drift", driftBase + drift.replicas)
                    val row = JSONObject().put("scene", scene).put("amount", amount.toDouble())
                        .put("size", size.toDouble()).put("counts", counts).put("contourDp", diameters)
                        .put("costs", JSONObject(costs)).put("buildMsUnpaired", buildMs)
                    val endpoints = HashMap<String, java.nio.ByteBuffer>()
                    for (name in names) renderer(name).use { r ->
                        prepare(r)
                        for (f in listOf(0, 12, 24, 40, 60, 80, 100)) {
                            r.draw(f / 100f, batchPressure = true)
                            val picture = ParticleFastBench.readPixels(fw, fh)
                            if (f == 0) endpoints[name] = picture
                            if (size == 1f && f in listOf(0, 24, 40, 60, 80))
                                ParticleFastBench.save(folder, "$scene-$name-$f.png", picture, fw, fh)
                            val state = r.readState().asFloatBuffer()
                            while (state.hasRemaining()) check(state.get().isFinite()) { "$scene $name $f 状态非有限" }
                        }
                    }
                    val endpoint = ParticleFastBench.pixelDifference(endpoints.getValue("before"), endpoints.getValue("aligned"))
                    check(endpoint.getInt("maxChannel255") <= 1) { "默认基准改变了静止快照：$endpoint" }
                    row.put("initialSnapshotDifference", endpoint)
                    rows.put(row)
                    File(folder, "progress.json").writeText(rows.toString(2))
                }
                bitmap.recycle()
            }
            File(folder, "default-scale-result.json").writeText(JSONObject()
                .put("renderer", GLES30.glGetString(GLES30.GL_RENDERER)).put("cases", rows).toString(2))
        }
    }
}
