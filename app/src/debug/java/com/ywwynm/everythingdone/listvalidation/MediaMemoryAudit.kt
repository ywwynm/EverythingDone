package com.ywwynm.everythingdone.listvalidation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Debug
import android.os.SystemClock
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.bitmap_recycle.LruBitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapResource
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.bumptech.glide.request.target.ViewTarget
import com.ywwynm.everythingdone.helpers.MediaCropBitmapRenderer
import com.ywwynm.everythingdone.helpers.MediaCropTransformation
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference

/** 独立包中的只读资源采样。持有 weak reference，避免探针本身延长 GIF 生命周期。 */
internal object MediaMemoryAudit {
    private val loaders = ArrayList<Pair<WeakReference<Any>, String>>()

    fun field(instance: Any?, name: String): Any? {
        var type = instance?.javaClass
        while (type != null) {
            try { return type.getDeclaredField(name).apply { isAccessible = true }.get(instance) }
            catch (_: NoSuchFieldException) { type = type.superclass }
        }
        return null
    }

    private fun poolStats(pool: Any): JSONObject = JSONObject().also { json ->
        listOf("hits", "misses", "puts", "evictions", "currentSize", "maxSize").forEach { json.put(it, field(pool, it)) }
    }

    private fun requestModel(request: Any?): String? =
        field(request, "model")?.toString() ?: field(request, "primary")?.let { requestModel(it) }

    fun snapshot(context: Context, output: File, phase: String): Int {
        check(context.packageName == "com.ywwynm.everythingdone.batchvalidation")
        val glide = Glide.get(context)
        val managers = field(glide, "managers") as? Collection<*> ?: field(glide, "requestManagers") as? Collection<*>
        val images = JSONArray()
        managers?.toList()?.forEach { manager ->
            val targets = field(field(manager, "targetTracker"), "targets") as? Collection<*> ?: return@forEach
            targets.toList().forEach { target ->
                val view = (target as? ViewTarget<*, *>)?.view as? ImageView ?: return@forEach
                val gif = view.drawable as? GifDrawable ?: return@forEach
                val loader = field(field(gif, "state"), "frameLoader") ?: return@forEach
                val model = requestModel(target.request).orEmpty()
                if (loaders.none { it.first.get() === loader }) loaders.add(WeakReference(loader) to model)
                val rect = Rect()
                val ancestry = JSONArray()
                var ancestor: android.view.View? = view
                while (ancestor != null) {
                    ancestry.put(JSONObject().put("class", ancestor.javaClass.simpleName).put("view", System.identityHashCode(ancestor))
                        .put("id", ancestor.id).put("attached", ancestor.isAttachedToWindow).put("visibility", ancestor.visibility)
                        .put("bound", ancestor.getTag(com.ywwynm.everythingdone.R.id.tag_thing_card_bound_stable_id)))
                    ancestor = ancestor.parent as? android.view.View
                }
                images.put(JSONObject().put("view", System.identityHashCode(view)).put("attached", view.isAttachedToWindow)
                    .put("shown", view.isShown).put("inViewport", view.getGlobalVisibleRect(rect))
                    .put("loader", System.identityHashCode(loader)).put("running", gif.isRunning)
                    .put("frame", gif.frameIndex).put("gifReportedBytes", gif.size).put("model", model)
                    .put("drawableVisible", gif.isVisible).put("ancestry", ancestry))
            }
        }
        val states = JSONArray()
        val bitmaps = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Bitmap, Boolean>())
        loaders.forEach { (ref, model) ->
            val loader = ref.get() ?: return@forEach
            val frames = JSONArray()
            for (name in listOf("firstFrame", "current", "next", "pendingTarget")) {
                val entry = field(loader, name)
                val bitmap = (entry as? Bitmap) ?: field(entry, "resource") as? Bitmap ?: continue
                if (bitmap.isRecycled) continue
                bitmaps.add(bitmap)
                frames.put(JSONObject().put("slot", name).put("bitmap", System.identityHashCode(bitmap))
                    .put("width", bitmap.width).put("height", bitmap.height).put("bytes", bitmap.allocationByteCount))
            }
            val decoder = field(loader, "gifDecoder")
            states.put(JSONObject().put("loader", System.identityHashCode(loader)).put("model", model)
                .put("decoder", System.identityHashCode(decoder)).put("running", field(loader, "isRunning"))
                .put("cleared", field(loader, "isCleared")).put("pending", field(loader, "isLoadPending"))
                .put("callbacks", (field(loader, "callbacks") as? Collection<*>)?.size)
                .put("frame", field(decoder, "framePointer")).put("frames", frames))
        }
        val memory = Debug.MemoryInfo(); Debug.getMemoryInfo(memory)
        val runtime = Runtime.getRuntime()
        val record = JSONObject().put("time", SystemClock.uptimeMillis()).put("phase", phase)
            .put("pssKb", memory.totalPss).put("memoryStats", JSONObject(memory.memoryStats))
            .put("nativeAllocatedBytes", Debug.getNativeHeapAllocatedSize())
            .put("javaUsedBytes", runtime.totalMemory() - runtime.freeMemory())
            .put("pool", poolStats(glide.bitmapPool)).put("managerCount", managers?.size)
            .put("trackedLoadersTotal", loaders.size).put("aliveLoaders", states)
            .put("knownGifFrameBitmapBytes", bitmaps.sumOf { it.allocationByteCount.toLong() }).put("images", images)
        File(output, "media-memory.jsonl").appendText(record.toString() + "\n")
        return (0 until states.length()).count { states.getJSONObject(it).optBoolean("running") }
    }

    fun recyclerOwners(list: androidx.recyclerview.widget.RecyclerView, output: File) {
        val rows = JSONArray()
        fun add(role: String, holders: Any?) {
            (holders as? Collection<*>)?.forEach { holder ->
                val view = (holder as? androidx.recyclerview.widget.RecyclerView.ViewHolder)?.itemView ?: return@forEach
                rows.put(JSONObject().put("role", role).put("view", System.identityHashCode(view))
                    .put("bound", view.getTag(com.ywwynm.everythingdone.R.id.tag_thing_card_bound_stable_id)))
            }
        }
        val recycler = field(list, "mRecycler")
        add("cache", field(recycler, "mCachedViews"))
        add("scrap", field(recycler, "mAttachedScrap"))
        val pool = field(list.recycledViewPool, "mScrap") as? android.util.SparseArray<*>
        if (pool != null) for (i in 0 until pool.size()) add("pool", field(pool.valueAt(i), "mScrapHeap"))
        File(output, "recycler-owners.json").writeText(rows.toString(2))
    }

    /** 在真实 BitmapTransformation/BitmapResource seam 验证返回池的同尺寸输出是否再次被使用。 */
    fun cropReuse(context: Context, output: File): JSONObject {
        check(context.packageName == "com.ywwynm.everythingdone.batchvalidation")
        cropPixels(output)
        val source = Bitmap.createBitmap(720, 405, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { color = 0x77dd2266 }
        Canvas(source).drawRect(40f, 10f, 680f, 390f, paint)
        val pool = LruBitmapPool(64L * 1024 * 1024)
        val transform = MediaCropTransformation(1256, 2300, MediaCropBitmapRenderer.Crop(.37, .64, 1.3))
        val method = MediaCropTransformation::class.java.getDeclaredMethod("transform",
            com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool::class.java, Bitmap::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
        val refs = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Bitmap, Boolean>())
        var last: Bitmap? = null
        var reused = 0
        val start = SystemClock.elapsedRealtime()
        repeat(120) {
            val bitmap = method.invoke(transform, pool, source, 1256, 2300) as Bitmap
            if (bitmap === last) reused++
            refs.add(bitmap)
            last = bitmap
            BitmapResource.obtain(bitmap, pool)!!.recycle()
        }
        val result = JSONObject().put("frames", 120).put("uniqueOutputs", refs.size)
            .put("sameOutputReused", reused).put("pool", poolStats(pool))
            .put("elapsedMs", SystemClock.elapsedRealtime() - start)
        File(output, "crop-reuse.json").writeText(result.toString(2))
        pool.clearMemory(); source.recycle()
        check(reused >= 115) { "同尺寸裁切帧未复用已归还位图：$result" }
        return result
    }

    private fun cropPixels(output: File) {
        val results = JSONArray()
        val dimensions = listOf(127 to 89, 47 to 293, 511 to 317)
        val crops = listOf(MediaCropBitmapRenderer.Crop(.5, .5, 1.0),
            MediaCropBitmapRenderer.Crop(0.0, 1.0, 2.4, 1.3),
            MediaCropBitmapRenderer.Crop(.73, .28, 1.17, .45),
            MediaCropBitmapRenderer.Crop(Double.NaN, Double.POSITIVE_INFINITY, .1, -2.0))
        val method = MediaCropTransformation::class.java.getDeclaredMethod("transform",
            com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool::class.java, Bitmap::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
        val poolConfigs = if (android.os.Build.VERSION.SDK_INT >= 26) listOf(Bitmap.Config.ARGB_8888, Bitmap.Config.RGBA_F16) else listOf(Bitmap.Config.ARGB_8888)
        for (poolConfig in poolConfigs) for (opaque in listOf(false, true)) for ((w, h) in dimensions) for ((index, crop) in crops.withIndex()) {
            val source = Bitmap.createBitmap(83, 61, if (opaque) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888)
            val pixels = IntArray(83 * 61) { i -> ((if (opaque) 255 else i % 256) shl 24) or
                (((i * 19) % 256) shl 16) or (((i * 37) % 256) shl 8) or ((i * 11) % 256) }
            source.setPixels(pixels, 0, 83, 0, 0, 83, 61)
            val reference = checkNotNull(MediaCropBitmapRenderer.renderCrop(source, w, h, crop))
            val expected = IntArray(w * h); reference.getPixels(expected, 0, w, 0, 0, w, h)
            val pool = LruBitmapPool(4L * 1024 * 1024)
            val dirty = Bitmap.createBitmap(w, h, poolConfig)
            dirty.eraseColor(android.graphics.Color.MAGENTA)
            if (android.os.Build.VERSION.SDK_INT >= 26 && poolConfig == Bitmap.Config.ARGB_8888) {
                dirty.setColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.DISPLAY_P3))
            }
            pool.put(dirty)
            val transformed = method.invoke(MediaCropTransformation(w, h, crop), pool, source, w, h) as Bitmap
            val actual = IntArray(w * h); transformed.getPixels(actual, 0, w, 0, 0, w, h)
            val bytes = java.nio.ByteBuffer.allocate(expected.size * 4)
            expected.forEach { bytes.putInt(it) }
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
            results.put(JSONObject().put("case", "$opaque-$w-$h-$index").put("poolConfig", poolConfig.name).put("sha256", digest)
                .put("equal", expected.contentEquals(actual)).put("density", transformed.density)
                .put("colorSpace", if (android.os.Build.VERSION.SDK_INT >= 26) transformed.colorSpace.toString() else "legacy"))
            check(expected.contentEquals(actual)) { "裁切像素变化：$opaque $w $h $index" }
            BitmapResource.obtain(transformed, pool)!!.recycle(); pool.clearMemory(); source.recycle(); reference.recycle()
        }
        File(output, "crop-pixels.json").writeText(results.toString(2))
    }
}
