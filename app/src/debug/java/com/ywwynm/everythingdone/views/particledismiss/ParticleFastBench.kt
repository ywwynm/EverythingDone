package com.ywwynm.everythingdone.views.particledismiss

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.opengl.GLES30
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** 同包、同输入交错 A/B；不改变偏好、不写记事。时间测量与像素／状态检查分开。 */
internal object ParticleFastBench {
    private val baseline = ParticleFastOptions.BASELINE

    fun flow(context: Context, folder: File, highRefresh: Boolean = false, compactCheck: Boolean = false,
             orderCheck: Boolean = false, reuseCheck: Boolean = false, drawingCheck: Boolean = false) {
        val assets = context.assets
        val resources = ParticleMicroflakeRenderer.sharedResources(assets)
        val rows = JSONArray()
        val reference = if (drawingCheck) ParticleFastOptions()
        else if (reuseCheck) ParticleFastOptions(pressureInputReuse = false, fusedCircleRecords = false)
        else if (orderCheck) ParticleFastOptions() else if (highRefresh) ParticleFastOptions(pressureTolerance = 0f,
            integrationBatch = false, compactCircles = false, fusedCircleRecords = false) else baseline
        val variants = if (drawingCheck) linkedMapOf("baseline" to reference, "standard" to reference.copy())
        else if (reuseCheck) linkedMapOf("baseline" to reference,
            "pressureInput" to reference.copy(pressureInputReuse = true),
            "fusedRecords" to reference.copy(fusedCircleRecords = true),
            "combined" to reference.copy(pressureInputReuse = true, fusedCircleRecords = true))
        else if (orderCheck) linkedMapOf("baseline" to reference, "deferred" to reference)
        else if (compactCheck) linkedMapOf("baseline" to reference,
            "compact" to reference.copy(compactCircles = true),
            "batch" to reference.copy(integrationBatch = true),
            "combined" to reference.copy(compactCircles = true, integrationBatch = true, pressureTolerance = .001f)
        ) else if (highRefresh) linkedMapOf("baseline" to reference,
            "adaptive0001" to reference.copy(pressureTolerance = .0001f),
            "adaptive001" to reference.copy(pressureTolerance = .001f),
            "latePressure" to reference.copy(pressureLateStride = true),
            "lateSteps" to reference.copy(flowLateSteps = true),
            "batch" to reference.copy(integrationBatch = true)) else linkedMapOf("baseline" to baseline,
            "color" to baseline.copy(flowColors = true),
            "circle" to baseline.copy(flowColors = true, flowCircle = true),
            "grid72" to baseline.copy(pressureResolution = 72),
            "combined72" to baseline.copy(flowColors = true, flowCircle = true, pressureResolution = 72))
        val fw = 1440; val fh = 3088
        ParticleSegmentBench.withEgl(assets, fw, fh) { shared ->
            val scenes = (if (drawingCheck) listOf("attachment" to 1047, "color" to 1582)
                else listOf("attachment" to 600, "color" to 1400)) +
                if (reuseCheck) listOf("small" to 240) else emptyList()
            for ((scene, height) in scenes) {
                val width = if (scene == "small") 600 else if (drawingCheck) {
                    if (scene == "attachment") 1050 else 1380
                } else 1200
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap); canvas.drawColor(Color.rgb(245, 243, 240))
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                for (i in 0..11) {
                    paint.color = Color.HSVToColor(floatArrayOf(i*30f, .75f, .75f))
                    val x = 70f + (i%4)*285; val y = 80f + (i/4)*(height-120)/3f
                    canvas.drawRoundRect(x, y, x+200, y+90, 15f, 15f, paint)
                }
                paint.color = Color.rgb(48,48,48);paint.textSize = 36f
                canvas.drawText("Particle performance / $scene", 70f, height-25f, paint)
                val pixels = IntArray(width*height);bitmap.getPixels(pixels,0,width,0,0,width,height)
                for (size in if (scene == "small") listOf(1f) else if (compactCheck || orderCheck) listOf(3f) else listOf(1f, 3f)) {
                    // 与三星 density=3.75 下正式 fromPixels 的材料坐标缩放一致；参数显式固定。
                    val materials = ParticleMicroflakeModel.build(width/2f, height/2f, pixels, width, height,
                        135f, 1008129L, resources.rules, particleAmount = if (scene == "small") .2f else 1.6f,
                        particleSizeScale = size)
                    val input = ParticleMicroflakeRenderer.Input(fw/2f, fh/2f, width/2f,height/2f,
                        (fw-width)/4f,(fh-height)/4f,135f,bitmap,materials,resources.guide,resources.rules,
                        pixels,confidence = resources.confidence)
                    val samples = 36
                    fun renderer(options: ParticleFastOptions) = ParticleFastOptions.withOptions(options) {
                        ParticleMicroflakeRenderer(assets,shared,input)
                    }
                    fun standard(options: ParticleFastOptions) = options === variants["standard"]
                    fun prepare(r: ParticleMicroflakeRenderer, options: ParticleFastOptions) {
                        if (!drawingCheck) { r.prepare(); return }
                        val bit = ParticlePerfTuning.P2_SRGB8_ACCUMULATION
                        ParticlePerfTuning.withPhase2(on = if (standard(options)) bit else 0,
                            off = if (standard(options)) 0 else bit) { r.prepare() }
                    }
                    fun selectFormat(options: ParticleFastOptions) {
                        if (drawingCheck) shared.ensureAccumulationFormat(standard(options))
                    }
                    fun run(options: ParticleFastOptions, path: String): Double {
                        renderer(options).use { r ->
                            prepare(r, options)
                            if (path == "replay") check(r.prepareReverse(samples))
                            if (path == "forward") r.draw(0f,batchPressure = true)
                            if (path == "prepare") r.beginReverse(samples,samples,precomputeProbe = false)
                            GLES30.glFinish()
                            val start = System.nanoTime()
                            when(path) {
                                "forward" -> for (f in 1..120) r.draw(f/120f,batchPressure = true)
                                "replay" -> if (highRefresh || reuseCheck || drawingCheck) {
                                    for(f in 0..72) r.drawReverseProgress(f/72f,samples)
                                } else for (f in samples downTo 0) r.drawReverseFrame(f)
                                else -> { while(r.reverseIncomplete) check(r.stepReverse { false });r.endReverse() }
                            }
                            GLES30.glFinish()
                            return (System.nanoTime()-start)/1e6
                        }
                    }
                    val costs = variants.keys.associateWith { JSONObject() }
                    for (path in if (orderCheck) emptyList() else if (drawingCheck) listOf("forward","replay")
                        else listOf("forward","replay","prepare")) {
                        for (options in variants.values) run(options,path)
                        val measured = variants.keys.associateWith { ArrayList<Double>() }
                        val names = variants.keys.toList()
                        for (rep in 0..2) for (k in names.indices) {
                            val name = names[(k+rep)%names.size]
                            measured.getValue(name).add(run(variants.getValue(name),path))
                        }
                        for (name in names) costs.getValue(name).put(path,
                            JSONObject().put("medianMs",measured.getValue(name).sorted()[1])
                                .put("samplesMs",JSONArray(measured.getValue(name))))
                    }
                    val row = JSONObject().put("scene",scene).put("size",size.toDouble()).put("count",materials.count)
                        .put("width",width).put("height",height)
                        .put("costs",JSONObject(costs))
                    val checks = JSONArray()
                    for (variant in variants.keys.filter { it != "baseline" && it != "combined72" }) {
                        renderer(reference).use { a -> renderer(variants.getValue(variant)).use { b ->
                            prepare(a, reference);prepare(b, variants.getValue(variant))
                            for (f in listOf(12,24,40,60,80)) {
                                val t=f/100f
                                selectFormat(reference)
                                a.draw(t,batchPressure = true);val pa=readPixels(fw,fh)
                                val sa=a.readState().asFloatBuffer()
                                selectFormat(variants.getValue(variant))
                                b.draw(t,batchPressure = true,deferFinalPressure = variant=="deferred")
                                val pb=readPixels(fw,fh)
                                val sb=b.readState().asFloatBuffer()
                                if(variant=="deferred")b.finishDeferredPressure()
                                val e=ArrayList<Float>()
                                val speeds=ArrayList<Float>()
                                for(i in 0 until materials.count) {
                                    val age=t-materials.values[i*12+2]
                                    if(age<=0f || age>=materials.values[i*12+6])continue
                                    val dx=sa.get(i*8)-sb.get(i*8);val dy=sa.get(i*8+1)-sb.get(i*8+1)
                                    e.add(sqrt(dx*dx+dy*dy)*2f)
                                    val vx=sa.get(i*8+4)-sb.get(i*8+4);val vy=sa.get(i*8+5)-sb.get(i*8+5)
                                    speeds.add(sqrt(vx*vx+vy*vy)*2f)
                                }
                                e.sort();speeds.sort()
                                val diff=pixelDifference(pa,pb).put("variant",variant).put("time",t.toDouble())
                                    .put("positionMaxPx",e.lastOrNull()?.toDouble() ?: 0.0)
                                    .put("velocityP99Px",if(speeds.isEmpty())0.0 else speeds[(speeds.size*.99).toInt()].toDouble())
                                    .put("positionP99Px",if(e.isEmpty()) 0.0 else e[(e.size*.99).toInt()].toDouble())
                                checks.put(diff)
                                if (size==3f && f in listOf(24,40,60)) {
                                    save(folder,"$scene-$variant-$f.png",pb,fw,fh)
                                    if(variant==variants.keys.elementAt(1))save(folder,"$scene-baseline-$f.png",pa,fw,fh)
                                }
                            }
                        } }
                    }
                    if (compactCheck || orderCheck) {
                        // 与正向同一时刻对照，覆盖整数历史帧之间的高刷画面。
                        renderer(reference).use { a -> renderer(reference).use { b ->
                            a.prepare();b.prepare();check(b.prepareReverse(samples))
                            for(t in listOf(.173f,.337f,.473f,.613f,.797f)) {
                                a.draw(t,batchPressure=true);val pa=readPixels(fw,fh)
                                val sa=a.readState().asFloatBuffer()
                                b.drawReverseProgress(1f-t,samples);val pb=readPixels(fw,fh)
                                val sb=b.readState().asFloatBuffer();val errors=ArrayList<Float>()
                                val extra=(t-kotlin.math.floor(t.toDouble()*240+1e-6).toInt()/240f).coerceAtLeast(0f)
                                for(i in 0 until materials.count){
                                    val age=t-materials.values[i*12+2]
                                    if(age<=0f || age>=materials.values[i*12+6])continue
                                    val dx=sa.get(i*8)+sa.get(i*8+4)*extra-sb.get(i*8)
                                    val dy=sa.get(i*8+1)+sa.get(i*8+5)*extra-sb.get(i*8+1)
                                    errors.add(sqrt(dx*dx+dy*dy)*2f)
                                }
                                errors.sort()
                                checks.put(pixelDifference(pa,pb).put("variant","interpolation").put("time",t.toDouble())
                                    .put("positionP99Px",if(errors.isEmpty())0.0 else errors[(errors.size*.99).toInt()].toDouble())
                                    .put("positionMaxPx",errors.lastOrNull()?.toDouble() ?: 0.0))
                            }
                        } }
                    }
                    row.put("checks",checks);rows.put(row)
                    File(folder,"progress.json").writeText(rows.toString(2))
                }
                bitmap.recycle()
            }
            File(folder,"flow-result.json").writeText(JSONObject().put("renderer",GLES30.glGetString(GLES30.GL_RENDERER))
                .put("cases",rows).toString(2))
        }
    }

    fun drift(assets: AssetManager, model: ParticleDriftModel, content: ParticleDriftContent.Analysis,
              rgba: ByteBuffer, spec: JSONObject, width: Int, height: Int, target: Int,
              frames: Int, repeats: Int, folder: File): JSONObject {
        val colorCheck = spec.optBoolean("colorCheck")
        val reference = if (colorCheck) ParticleFastOptions() else baseline
        val variants=if(colorCheck) linkedMapOf("baseline" to reference,
            "standard" to reference.copy(driftStandardColor=true))
        else linkedMapOf("baseline" to baseline,"tight" to baseline.copy(driftTightQuad=true),"cache" to baseline.copy(driftCache=true),
            "curve" to baseline.copy(driftCache=true,driftCurve=true))
        val ox=(width-model.snapshotWidth)/2f;val oy=(height-model.snapshotHeight)/2f
        fun renderer(options: ParticleFastOptions, selected: ParticleDriftModel = model) = ParticleFastOptions.withOptions(options) {
            ParticleDriftRenderer(assets,selected,spec.optBoolean("additive",false)).also {
                it.prepare(ParticleMicroflakePreparation.Pixels(model.snapshotWidth,model.snapshotHeight,rgba,null),content)
                it.reuseParticleFrames=false
            }
        }
        fun draw(r: ParticleDriftRenderer,t: Float) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,target);GLES30.glViewport(0,0,width,height)
            GLES30.glClearColor(0f,0f,0f,0f);GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            r.draw(t,ox,oy,target)
        }
        fun run(options: ParticleFastOptions, skip: Int = 0): DoubleArray {
            renderer(options).use { r ->
                ParticleDriftRenderer.debugSkip = skip
                GLES30.glFinish();val started=System.nanoTime()
                draw(r,0f);GLES30.glFinish();val initialized=System.nanoTime()
                for(f in 0 until frames) draw(r,model.startSeconds+(model.totalSeconds-model.startSeconds)*f/(frames-1))
                GLES30.glFinish();val end=System.nanoTime()
                ParticleDriftRenderer.debugSkip = 0
                return doubleArrayOf((initialized-started)/1e6,(end-initialized)/1e6/frames,(end-started)/1e6)
            }
        }
        for(options in variants.values)run(options)
        val names=variants.keys.toList();val measured=names.associateWith { ArrayList<DoubleArray>() }
        for(rep in 0 until repeats)for(k in names.indices){
            val name=names[(k+rep)%names.size];measured.getValue(name).add(run(variants.getValue(name)))
        }
        val costs=JSONObject()
        for(name in names){
            val samples=measured.getValue(name)
            costs.put(name,JSONObject().put("firstMs",samples.map { it[0] }.sorted()[repeats/2])
                .put("frameMs",samples.map { it[1] }.sorted()[repeats/2])
                .put("wholeMs",samples.map { it[2] }.sorted()[repeats/2])
                .put("samples",JSONArray(samples.map { JSONArray(it.toList()) })))
        }
        // 跳过三段绘制后仍保留 compute 和清屏；与全部跳过的条件配对量级。
        val onlyCompute = ParticleDriftRenderer.SKIP_PARTICLES or ParticleDriftRenderer.SKIP_COMPOSITE or
            ParticleDriftRenderer.SKIP_CARD
        val splitNames = if (colorCheck) emptyList() else names + "none"
        val split = splitNames.associateWith { ArrayList<Double>() }
        for (rep in 0 until repeats + 1) for (k in splitNames.indices) {
            val name = splitNames[(k+rep)%splitNames.size]
            val ms = run(variants[name] ?: baseline,
                onlyCompute or if (name == "none") ParticleDriftRenderer.SKIP_COMPUTE else 0)[1]
            if (rep > 0) split.getValue(name).add(ms)
        }
        val separated = JSONObject()
        for ((name, samples) in split) separated.put(name, JSONObject()
            .put("medianMs",samples.sorted()[repeats/2]).put("samplesMs",JSONArray(samples)))
        val checks=JSONArray()
        for(variant in if(colorCheck)listOf("standard") else listOf("tight","curve")) renderer(reference).use { a -> renderer(variants.getValue(variant)).use { b ->
            for(f in listOf(15,30,45,60,75,90)){
                val t=model.startSeconds+(model.totalSeconds-model.startSeconds)*f/100f
                draw(a,t);val pa=readPixels(width,height)
                draw(b,t);val pb=readPixels(width,height)
                checks.put(pixelDifference(pa,pb).put("time",t.toDouble()).put("variant",variant))
                if(f==45) {
                    val id=spec.optInt("seed",1)
                    save(folder,"fast-$id-base.png",pa,width,height);save(folder,"fast-$id-$variant.png",pb,width,height)
                }
            }
        } }
        val changed = ParticleDriftModel(model.snapshotWidth, model.snapshotHeight, model.lattice.reversedArray(),
            model.latticeWidth, model.latticeHeight, -model.windX, -model.windY, model.count,
            sizeScale = model.sizeScale*.8f, turbulenceOffsetX = .37f, touchGap = .2f)
        val invalidation = JSONArray()
        if (!colorCheck) renderer(variants.getValue("curve")).use { reused ->
            draw(reused, .5f)
            reused.useModel(changed)
            renderer(variants.getValue("curve"), changed).use { fresh ->
                for (small in listOf(false, true, false)) {
                    fun capture(r: ParticleDriftRenderer): ByteBuffer {
                        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,target)
                        GLES30.glViewport(0,0,if (small) width/2 else width,if (small) height/2 else height)
                        GLES30.glClearColor(0f,0f,0f,0f);GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                        r.draw(.6f,ox,oy,target)
                        return readPixels(width,height)
                    }
                    val diff = pixelDifference(capture(reused), capture(fresh))
                    check(diff.getInt("maxChannel255") == 0) { "飘散缓存未正确失效：$diff" }
                    invalidation.put(diff)
                }
            }
        }
        return JSONObject().put("name",spec.getString("name")).put("count",model.count+content.replicas)
            .put("costs",costs).put("computeAndClear",separated).put("checks",checks)
            .put("cacheInvalidation",invalidation)
    }

    private fun readPixels(w: Int,h: Int): ByteBuffer = ByteBuffer.allocateDirect(w*h*4).also {
        GLES30.glReadPixels(0,0,w,h,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,it)
    }
    private fun pixelDifference(a: ByteBuffer,b: ByteBuffer): JSONObject {
        var total=0L;var maximum=0;var changed=0
        for(i in 0 until a.capacity()){
            val d=abs((a.get(i).toInt() and 255)-(b.get(i).toInt() and 255))
            total+=d;maximum=max(maximum,d);if(d>2)changed++
        }
        return JSONObject().put("meanChannel255",total.toDouble()/a.capacity()).put("maxChannel255",maximum)
            .put("channelOver2Ratio",changed.toDouble()/a.capacity())
    }
    private fun save(folder: File,name: String,bytes: ByteBuffer,w: Int,h: Int){
        val colors=IntArray(w*h)
        for(y in 0 until h)for(x in 0 until w){
            val i=(y*w+x)*4
            colors[(h-1-y)*w+x]=Color.argb(bytes.get(i+3).toInt() and 255,bytes.get(i).toInt() and 255,
                bytes.get(i+1).toInt() and 255,bytes.get(i+2).toInt() and 255)
        }
        val bitmap=Bitmap.createBitmap(colors,w,h,Bitmap.Config.ARGB_8888)
        File(folder,name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
}
