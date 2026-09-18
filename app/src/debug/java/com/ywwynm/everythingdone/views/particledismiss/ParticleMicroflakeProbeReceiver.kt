package com.ywwynm.everythingdone.views.particledismiss

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES30
import android.opengl.GLES31
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

/** 只读 adb 预先放入专用目录的测试素材，不访问记事或应用设置。 */
class ParticleMicroflakeProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra("shaderMatrix", false)) {
            // 逐帧成本专项第二阶段的纪律：**每一项都要先在华为上编译一次**
            // （Maleoon 910 的链接检查最严，Adreno 的宽容会掩盖问题）。这里把条件表里
            // 每个条件的两侧各编译一遍整套程序，只报链接结果，不跑任何动画。
            if (!running.compareAndSet(false, true)) return
            val app = context.applicationContext
            Thread({
                val out = File(app.getExternalFilesDir(null), "particle-unified/generated").apply { mkdirs() }
                val report = JSONObject()
                runCatching { shaderMatrix(app, report) }
                    .onFailure { report.put("error", it.stackTraceToString()) }
                runCatching { File(out, "shader-matrix.json").writeText(report.toString(2)) }
                running.set(false)
            }, "ParticleShaderMatrix").start()
            return
        }
        if (intent.getBooleanExtra("nativeCheck", false)) {
            if (!running.compareAndSet(false, true)) return
            val app = context.applicationContext
            val checkId = intent.getStringExtra("checkId") ?: "manual"
            Thread({
                val out = File(app.getExternalFilesDir(null), "particle-unified/generated").apply { mkdirs() }
                try {
                    check(ParticleMaterialNative.available) { "本地材料库未加载，不能用回退路径通过验证" }
                    val rules = ParticleMicroflakeRenderer.sharedResources(app.assets).rules
                    val cases = JSONArray()
                    val pixels = IntArray(256) { i -> when {
                        i % 11 == 0 -> 0x00ffffff
                        i % 7 == 0 -> 0xffe86520.toInt()
                        i % 5 == 0 -> 0xff1268bb.toInt()
                        else -> -1
                    } }
                    for ((w,h) in listOf(120f to 160f, 47f to 211f, 231f to 39f, 630f to 1074f)) {
                        for (angle in listOf(0f, 90f, 122f, 135f, 270f)) for (seed in listOf(909602L, 4294967305L)) {
                            val source = if (seed == 909602L) pixels else IntArray(256) { i ->
                                val rgb=(ParticleMicroflakeModel.randomValue(i,37)*16777216).toInt()
                                ((if(i%13==0) 64 else 255) shl 24) or rgb
                            }
                            fun build() = ParticleMicroflakeModel.build(w,h,source,16,16,angle,seed,rules)
                            val start = System.nanoTime()
                            val expected = ParticleMaterialNative.reference { build() }
                            val middle = System.nanoTime()
                            val actual = build()
                            val end = System.nanoTime()
                            check(expected.count == actual.count)
                            val originalPixels=source.copyOf()
                            val rgba=ByteBuffer.allocateDirect(source.size*4).order(ByteOrder.LITTLE_ENDIAN)
                            val state=ByteBuffer.allocateDirect(actual.count*8*4).order(ByteOrder.nativeOrder())
                            ParticleMaterialNative.packUploads(source,actual.values,actual.count,rgba,state)
                            check(source.contentEquals(originalPixels)) { "上传准备修改了原像素" }
                            val colors=rgba.asIntBuffer()
                            for(i in source.indices) {
                                val c=source[i]
                                check(colors.get(i)==((c and 0xff00ff00.toInt()) or ((c ushr 16) and 255) or ((c and 255) shl 16)))
                            }
                            val packed=FloatArray(actual.count*8);state.asFloatBuffer().get(packed)
                            for(i in 0 until actual.count) for(k in 0..7) {
                                check(packed[i*8+k]==if(k<2)actual.values[i*12+k] else 0f) { "首帧状态缓冲不一致" }
                            }
                            val errors = JSONArray()
                            for ((a,b) in listOf(expected.values to actual.values, expected.pigment to actual.pigment,
                                expected.peelCompression to actual.peelCompression)) {
                                var maxError=0f
                                for (i in a.indices) {
                                    val delta=abs(a[i]-b[i]); maxError=max(maxError,delta)
                                    check(b[i].isFinite() && delta <= 3e-5f) { "材料差异 w=$w h=$h angle=$angle seed=$seed index=$i expected=${a[i]} actual=${b[i]}" }
                                }
                                errors.put(maxError)
                            }
                            cases.put(JSONObject().put("width",w).put("height",h).put("angle",angle).put("seed",seed)
                                .put("count",actual.count).put("referenceMs",(middle-start)/1e6).put("nativeMs",(end-middle)/1e6)
                                .put("maxErrors",errors).put("stages",JSONObject(actual.statistics)))
                        }
                    }
                    // 3b：并行道数不得改变任何一个值。每一道只写自己那些下标、只读别的缓冲，
                    // 所以「1 道」与「默认道数」的输出必须逐位相同（不是近似相同）。
                    val lanes = JSONObject().put("defaultLanes", ParticleMaterialNative.lanes())
                    val laneCases = JSONArray()
                    for ((w,h) in listOf(320f to 480f, 630f to 1074f)) {
                        val source = IntArray(256) { i ->
                            val rgb=(ParticleMicroflakeModel.randomValue(i,53)*16777216).toInt()
                            ((if(i%17==0) 96 else 255) shl 24) or rgb
                        }
                        fun build() = ParticleMicroflakeModel.build(w,h,source,16,16,294f,20260915L,rules)
                        ParticleMaterialNative.setLanes(1)
                        check(ParticleMaterialNative.lanes() == 1) { "没能切到串行" }
                        val serial = try { build() } finally { ParticleMaterialNative.setLanes(0) }
                        // 恢复之后必须真的回到多道，否则这条对照比的是两次串行运行（恒等式）。
                        check(ParticleMaterialNative.lanes() > 1) { "没能恢复并行道数" }
                        val parallel = build()
                        check(serial.values.contentEquals(parallel.values)) { "并行道数改变了材料值 w=$w h=$h" }
                        check(serial.pigment.contentEquals(parallel.pigment)) { "并行道数改变了色素 w=$w h=$h" }
                        check(serial.peelCompression.contentEquals(parallel.peelCompression)) { "并行道数改变了压缩 w=$w h=$h" }
                        laneCases.put(JSONObject().put("width",w).put("height",h).put("count",parallel.count)
                            .put("bitIdentical",true))
                    }
                    lanes.put("cases",laneCases)
                    // 第四批 ④：工作线程跟随调用线程的优先级。优先级不改变任何数值，逐位
                    // 对照看不见它，只能在两种调用线程优先级下各建一次材料、把两个 nice 读回来比。
                    val priorities = JSONArray()
                    for (wanted in listOf(android.os.Process.THREAD_PRIORITY_DISPLAY,
                        android.os.Process.THREAD_PRIORITY_DEFAULT)) {
                        val done = java.util.concurrent.CountDownLatch(1)
                        var row: JSONObject? = null
                        Thread({
                            android.os.Process.setThreadPriority(wanted)
                            row = JSONObject().put("wanted", wanted)
                                .put("callerNice", ParticleMaterialNative.threadPriority())
                                .put("laneNice", ParticleMaterialNative.probeLanePriority())
                            done.countDown()
                        }, "ParticleMaterialPriority").start()
                        done.await()
                        val entry = checkNotNull(row)
                        check(entry.getInt("laneNice") == entry.getInt("callerNice")) {
                            "工作线程没有跟随调用线程优先级 $entry" }
                        priorities.put(entry)
                    }
                    lanes.put("priorities", priorities)
                    File(out,"native-material-parity.json").writeText(JSONObject().put("ok",true).put("checkId",checkId)
                        .put("uploadBuffersVerified",true).put("lanes",lanes).put("cases",cases).toString(2))
                } catch (error: Throwable) {
                    File(out,"native-material-parity.json").writeText(JSONObject().put("ok",false).put("checkId",checkId).put("error",error.stackTraceToString()).toString(2))
                } finally { running.set(false) }
            }, "ParticleNativeCheck").start()
            return
        }
        val scenes = listOf("ironman", "ironman-up-reference", "thanos", "kobe", "language", "color", "attachment", "attachment-image",
            "holdout-notification", "holdout-photo", "holdout-dark", "holdout-wide", "holdout-tall", "holdout-alpha",
            "holdout-coffee", "holdout-colored-panel", "holdout-monochrome", "holdout-compact-dialog")
        val requested = intent.getStringExtra("scene")
        val recordingScenes = listOf("user-device-1", "user-device-2", "user-device-3")
        val selected = if (requested == null) scenes else listOf(requested).filter { it in scenes || it in recordingScenes }
        if (selected.isEmpty() || !running.compareAndSet(false, true)) return
        val scale = intent.getFloatExtra("scale", 1f).coerceIn(.5f, 2.5f)
        val referenceMaterial = intent.getBooleanExtra("referenceMaterial", false)
        val reverseCheck = intent.getBooleanExtra("reverseCheck", false)
        val reverseIntervals = intent.getIntExtra("reverseSamples", ParticleReversePlan.samplesFor(ParticleReversePlan.APPEARANCE_SECONDS, 60f)).coerceIn(1, 60)
        val saveReverseFrames = intent.getBooleanExtra("saveReverseFrames", false)
        val checkId = intent.getStringExtra("checkId") ?: "manual"
        val direction = if (intent.hasExtra("direction")) intent.getFloatExtra("direction", 270f) else null
        val seed = if (intent.hasExtra("seed")) intent.getLongExtra("seed", 909602L) else null
        val app = context.applicationContext
        Thread({
            val root = File(app.getExternalFilesDir(null), "particle-unified")
            val out = File(root, "generated").apply { mkdirs() }
            try {
                fun renderSelected() { for (scene in selected) run(app, root, out, scene, scale, reverseCheck, saveReverseFrames, checkId, direction, seed, reverseIntervals) }
                if (referenceMaterial) ParticleMaterialNative.reference { renderSelected() } else renderSelected()
                File(out, "done.json").writeText(JSONObject().put("scenes", JSONArray(selected)).put("ok", true).toString())
            } catch (error: Throwable) {
                File(out, "error.txt").writeText(error.stackTraceToString())
                File(out, "failed-check.json").writeText(JSONObject().put("checkId", checkId)
                    .put("error", error.stackTraceToString()).toString())
                Log.e("ParticleProbe", "验证失败", error)
            } finally { running.set(false) }
        }, "ParticleProbe").start()
    }

    private fun run(context: Context, root: File, out: File, scene: String, scale: Float, reverseCheck: Boolean, saveReverseFrames: Boolean, checkId: String, direction: Float?, seed: Long?, reverseIntervals: Int) {
        val meta = JSONObject(File(root, "$scene.json").readText())
        if (direction != null) meta.put("direction", direction.toDouble())
        if (seed != null) meta.put("seed", seed)
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
        val report = JSONObject().put("scene", scene).put("checkId", checkId).put("generated", true).put("width", width).put("height", height)
            .put("nativeMaterial", ParticleMaterialNative.enabled)
            .put("modelHash", JSONObject(context.assets.open("particle-dismiss/model.json").bufferedReader().use { it.readText() }).getString("model_hash"))
            .put("count", materials.count).put("modelMs", (System.nanoTime() - before) / 1e6).put("modelStages", JSONObject(materials.statistics))
        val touchGap = meta.optDouble("touch_gap", rules.number("touch_gap_default").toDouble()).toFloat()
        val touchStrength = if (meta.has("touch_gap")) {
            val angle = Math.toRadians(meta.getDouble("direction"))
            val ux = cos(angle); val uy = -sin(angle)
            val edge = min(cardWidth / (2 * max(abs(ux), 1e-9)), cardHeight / (2 * max(abs(uy), 1e-9)))
            val length = edge + min(cardWidth, cardHeight) * touchGap
            val bounds = meta.optJSONArray("touch_rect") ?: JSONArray(listOf(0, 0, fw, fh))
            ParticleFlowGeometry.touchStrength((cardWidth * .5 + ux * length).toFloat(),
                (cardHeight * .5 + uy * length).toFloat(), cardWidth.toFloat(), cardHeight.toFloat(),
                (bounds.getDouble(0) - rect.getDouble(0)).toFloat(), (bounds.getDouble(1) - rect.getDouble(1)).toFloat(),
                (bounds.getDouble(2) - rect.getDouble(0)).toFloat(), (bounds.getDouble(3) - rect.getDouble(1)).toFloat())
        } else .5f
        val input = ParticleMicroflakeRenderer.Input(fw.toFloat(), fh.toFloat(), cardWidth.toFloat(), cardHeight.toFloat(),
            rect.getDouble(0).toFloat(), rect.getDouble(1).toFloat(), meta.getDouble("direction").toFloat(),
            bitmap, materials, context.assets.open("particle-dismiss/common-flow.f16").use { it.readBytes() }, rules,
            touchGap = touchGap, touchStrength = touchStrength)
        report.put("direction", input.direction).put("touchGap", input.touchGap).put("touchStrength", input.touchStrength)
        fun saveValues(name: String, values: FloatArray) {
            val bytes = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            bytes.asFloatBuffer().put(values); File(out, "$scene-$name.f32").writeBytes(bytes.array())
        }
        saveValues("materials", materials.values); saveValues("pigment", materials.pigment)
        saveValues("peel-compression", materials.peelCompression)
        withEgl(context.assets, width, height) { shared ->
            val forwardHashes = ArrayList<String>()
            val reversePlan = ParticleReversePlan(materials.values, reverseIntervals)
            fun frameHash(): String {
                val buffer = ByteBuffer.allocateDirect(width * height * 4)
                GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
                return java.security.MessageDigest.getInstance("SHA-256").apply { update(buffer) }
                    .digest().joinToString("") { "%02x".format(it) }
            }
            report.put("renderer", GLES30.glGetString(GLES30.GL_RENDERER)).put("version", GLES30.glGetString(GLES30.GL_VERSION))
            saveValues("grid-jitter", readGridJitter(context, materials.columns + 1, materials.rows + 1))
            ParticleMicroflakeRenderer(context.assets, shared, input).use { renderer ->
                val prepare = System.nanoTime()
                renderer.preparePipeline(ParticleMicroflakeRenderer.sharedResources(context.assets))
                renderer.prepare(); GLES30.glFinish()
                report.put("prepareMs", (System.nanoTime() - prepare) / 1e6)
                val samples = JSONArray()
                val normalTimes = (0..60).associateBy { it / 60f }
                val reverseTimes = if (reverseCheck) (0..reversePlan.samples).map { reversePlan.time(it) } else emptyList()
                for (t in (normalTimes.keys + reverseTimes).sorted()) {
                    val i = normalTimes[t]
                    val start = System.nanoTime(); renderer.draw(t); GLES30.glFinish()
                    if (t in reverseTimes) forwardHashes.add(frameHash())
                    if (i != null) samples.put((System.nanoTime() - start) / 1e6)
                    if (i in listOf(0, 10, 15, 18, 20, 34, 40, 48, 60)) {
                        saveFrame(File(out, "$scene-$i.png"), width, height)
                    }
                    if (i == 18 || i == 34 || i == 40) {
                        val state = renderer.readState(); val bytes = ByteArray(state.remaining()); state.get(bytes)
                        File(out, "$scene-state${i.toString().padStart(3, '0')}.f32").writeBytes(bytes)
                    }
                }
                check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "渲染产生 GL 错误" }
                report.put("frameMs", samples)
            }
            if (reverseCheck) {
                // 优化的正向压力批次也与独立旧解算逐帧比较，不能拿自身充当基准。
                ParticleMicroflakeRenderer(context.assets, shared, input).use { renderer ->
                    renderer.prepare()
                    for (i in 0..reversePlan.samples) {
                        renderer.draw(reversePlan.time(i), batchPressure = true)
                        check(frameHash() == forwardHashes[i]) { "正向压力批次改变画面：$scene $i" }
                    }
                }
                report.put("forwardBatchVerified", true)
                val reverseSamples = JSONArray()
                ParticleMicroflakeRenderer(context.assets, shared, input).use { renderer ->
                    renderer.prepare()
                    val prepare = System.nanoTime()
                    // 同时覆盖前台有动画时的小批次调度分支，逐帧像素仍必须与原正向模型相等。
                    ParticleGpuWork.beginPlayback()
                    try { check(renderer.prepareReverse(reversePlan.samples)) }
                    finally { ParticleGpuWork.endPlayback() }
                    report.put("reverseYieldVerified", true)
                    report.put("reversePrepareMs", (System.nanoTime() - prepare) / 1e6)
                    for (i in reversePlan.samples downTo 0) {
                        renderer.drawReverseFrame(i)
                        val actual = frameHash()
                        check(actual == forwardHashes[i]) { "逆向帧与同一模型正向帧不一致：$scene $i" }
                        reverseSamples.put(JSONObject().put("frame", i).put("sha256", actual))
                        if (saveReverseFrames) saveFrame(File(out, "$scene-reverse-$i.png"), width, height)
                    }
                    check(GLES30.glGetError() == GLES30.GL_NO_ERROR)
                }
                report.put("reverseFrameParity", reverseSamples).put("reverseVerified", true)
                    .put("reverseFramesSaved", saveReverseFrames)
                    .put("reverseSamples", reversePlan.samples).put("appearanceSeconds", ParticleReversePlan.APPEARANCE_SECONDS)
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

    /** 直接复用实际材质中的函数，区分输运差异和 GPU 三角函数造成的几何抖动差异。 */
    private fun readGridJitter(context: Context, columns: Int, rows: Int): FloatArray {
        val material = context.assets.open("particle-dismiss/material.vert").bufferedReader().use { it.readText() }
        val start = material.indexOf("uint grid_hash").takeIf { it >= 0 } ?: material.indexOf("float hash(vec2")
        check(start >= 0) { "材质中缺少网格随机函数" }
        val functions = material.substring(start, material.indexOf("void main()"))
        val count = columns * rows
        val source = """#version 310 es
            precision highp float;
            precision highp int;
            layout(local_size_x=64) in;
            layout(std430,binding=0) buffer Output { vec2 offsets[]; };
            $functions
            void main(){uint i=gl_GlobalInvocationID.x;if(i>=${count}u)return;
                offsets[i]=jitter(vec2(i%${columns}u,i/${columns}u));}
        """.trimIndent()
        val shader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
        val program = GLES30.glCreateProgram()
        val buffer = IntArray(1)
        try {
            GLES30.glShaderSource(shader, source); GLES30.glCompileShader(shader)
            val status = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { GLES30.glGetShaderInfoLog(shader) }
            GLES30.glAttachShader(program, shader); GLES30.glLinkProgram(program)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { GLES30.glGetProgramInfoLog(program) }
            GLES30.glGenBuffers(1, buffer, 0)
            GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffer[0])
            GLES30.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, count * 8, null, GLES30.GL_DYNAMIC_READ)
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, buffer[0])
            GLES30.glUseProgram(program); GLES31.glDispatchCompute((count + 63) / 64, 1, 1)
            GLES31.glMemoryBarrier(GLES31.GL_BUFFER_UPDATE_BARRIER_BIT); GLES30.glFinish()
            val mapped = GLES30.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, count * 8,
                GLES30.GL_MAP_READ_BIT) as ByteBuffer
            val result = FloatArray(count * 2)
            mapped.order(ByteOrder.nativeOrder()).asFloatBuffer().get(result)
            GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
            return result
        } finally {
            GLES30.glDeleteBuffers(1, buffer, 0); GLES30.glDeleteProgram(program); GLES30.glDeleteShader(shader)
        }
    }

    /**
     * 把第二阶段条件表里每个条件的**两侧**各编译一遍整套程序（正向 + 逆向管线），
     * 只报链接结果与信息日志。一轮几秒，不跑任何动画、不碰记事。
     */
    private fun shaderMatrix(context: Context, report: JSONObject) {
        val rows = JSONArray()
        withEgl(context.assets, 256, 256) { shared ->
            report.put("renderer", GLES30.glGetString(GLES30.GL_RENDERER) ?: "")
                .put("adreno", ParticleMicroflakeRenderer.adreno())
            fun one(name: String, on: Int, off: Int) {
                val row = JSONObject().put("variant", name)
                val error = runCatching {
                    ParticlePerfTuning.withPhase2(on, off) {
                        ParticleMicroflakeRenderer(context.assets, shared).use {
                            it.preparePipeline()
                            it.prepareReversePipeline()
                        }
                    }
                }.exceptionOrNull()
                row.put("linked", error == null)
                if (error != null) row.put("log", error.message ?: error.toString())
                rows.put(row)
            }
            for (condition in ParticlePhase2Conditions.ALL) {
                one(condition.name + "@base", condition.baseOn, condition.baseOff)
                one(condition.name, condition.testOn, condition.testOff)
            }
            // 全开一次：各项的宏之间可能互相冲突（例如 varying 两侧精度与逐片量下沉同时改）。
            var all = 0
            for ((_, bit) in ParticlePerfTuning.PHASE2_KEYS) all = all or bit
            one("all-except-probe", all and ParticlePerfTuning.P2_PROBE_SINGLE_CELL.inv(), 0)
        }
        report.put("variants", rows)
            .put("failed", JSONArray((0 until rows.length()).map { rows.getJSONObject(it) }
                .filter { !it.getBoolean("linked") }.map { it.getString("variant") }))
    }

    private fun withEgl(assets: android.content.res.AssetManager, width: Int, height: Int,
                        action: (ParticleMicroflakeRenderer.Shared) -> Unit) {
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
        // 探针自带上下文，共享资源也必须是这个上下文私有的一份（程序对象不跨上下文有效）。
        val shared = ParticleMicroflakeRenderer.Shared(assets)
        try { shared.ensure(width, height); action(shared) } finally {
            runCatching { shared.close() }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface); EGL14.eglDestroyContext(display, gl); EGL14.eglReleaseThread()
            // display 与应用 HWUI 共用，不能终止其他活跃渲染器。
        }
    }

    companion object { private val running = AtomicBoolean() }
}
