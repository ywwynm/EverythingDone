package com.ywwynm.everythingdone.listvalidation

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.ViewGroup
import android.view.View
import android.widget.FrameLayout
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.bumptech.glide.Glide
import com.ywwynm.everythingdone.adapters.BaseThingsAdapter
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.helpers.VideoCoverPreviewManager
import com.ywwynm.everythingdone.managers.ModeManager
import com.ywwynm.everythingdone.model.Thing
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class GifLifecycleActivity : Activity() {
    lateinit var content: FrameLayout
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(packageName == "com.ywwynm.everythingdone.batchvalidation")
        content = FrameLayout(this)
        setContentView(content)
    }
}

/** 正式卡片绑定在请求交付前离屏，随后复用同一 holder；不替换 Glide 解码器或回调。 */
internal object GifLifecycleAudit {
    fun run(instrumentation: Instrumentation, output: File, main: (() -> Unit) -> Unit): JSONObject {
        val context = instrumentation.targetContext
        check(context.packageName == "com.ywwynm.everythingdone.batchvalidation")
        val source = File(context.filesDir, "lifecycle-source.mp4")
        if (!source.exists()) context.assets.open("complex-drag/source.mp4").use { input -> source.outputStream().use(input::copyTo) }
        val gifFile = checkNotNull(VideoCoverPreviewManager.generateBlocking(context, source.path, null))
        val activity = instrumentation.startActivitySync(Intent(context, GifLifecycleActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as GifLifecycleActivity
        val rows = JSONArray()
        try {
            for (background in listOf(false, true)) {
                val thing = Thing(1L, Thing.NOTE, 0xff3388aa.toInt(), 1L).apply {
                    title = "异步交付生命周期测试"
                    attachment = AttachmentHelper.SIGNAL + AttachmentHelper.IMAGE + gifFile.path
                    thingCardAppearance = thingCardAppearance.copy(mediaBackgroundEnabled = background)
                }
                val adapter = object : BaseThingsAdapter(context.applicationContext) {
                    override fun getCurrentMode() = ModeManager.NORMAL
                    override fun getThings(): List<Thing> = listOf(thing)
                }
                lateinit var holder: BaseThingsAdapter.BaseThingViewHolder
                main {
                    holder = adapter.onCreateViewHolder(activity.content, 0)
                    activity.content.addView(holder.itemView)
                    adapter.onBindViewHolder(holder, 0)
                    // 在主线程交付异步结果前先移除目标。
                    activity.content.removeView(holder.itemView)
                }
                val image = checkNotNull(if (background) holder.ivMediaBackground else holder.ivImageAttachment)
                var gif: GifDrawable? = null
                val deadline = SystemClock.uptimeMillis() + 20000
                while (gif == null && SystemClock.uptimeMillis() < deadline) {
                    main { gif = image.drawable as? GifDrawable }; SystemClock.sleep(30)
                }
                val drawable = checkNotNull(gif) { "正式卡片没有加载 GIF" }
                val originalFirstFrame = checkNotNull(drawable.firstFrame).copy(Bitmap.Config.ARGB_8888, false)
                val savedRequest = image.getTag(if (background) com.ywwynm.everythingdone.R.id.tag_thing_card_media_background_render_request
                    else com.ywwynm.everythingdone.R.id.tag_thing_card_image_render_request)!!
                SystemClock.sleep(180)
                val row = JSONObject().put("background", background)
                fun cacheState(): JSONObject {
                    @Suppress("UNCHECKED_CAST")
                    val cache = MediaMemoryAudit.field(adapter, "mThingCardMediaBitmapCache") as android.util.LruCache<String, Bitmap>
                    return JSONObject().put("bytes", cache.size()).put("max", cache.maxSize())
                        .put("entries", JSONArray(cache.snapshot().map { (key, bitmap) ->
                            JSONObject().put("key", key).put("w", bitmap.width).put("h", bitmap.height)
                        }))
                }
                main { row.put("firstCache", cacheState()).put("firstKey", image.getTag(if (background)
                    com.ywwynm.everythingdone.R.id.tag_thing_card_media_background_load_key else com.ywwynm.everythingdone.R.id.tag_thing_card_image_load_key)) }
                main {
                    row.put("lateDeliveryStopped", !drawable.isRunning)
                    activity.content.addView(holder.itemView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                SystemClock.sleep(200)
                var firstFrame = -1
                main { firstFrame = drawable.frameIndex }
                SystemClock.sleep(220)
                main {
                    row.put("attachedPlays", drawable.isRunning && drawable.frameIndex != firstFrame)
                    activity.content.removeView(holder.itemView)
                }
                SystemClock.sleep(180)
                main {
                    row.put("detachedStopped", !drawable.isRunning)
                    activity.content.addView(holder.itemView)
                }
                SystemClock.sleep(200)
                main { firstFrame = drawable.frameIndex }
                SystemClock.sleep(220)
                main {
                    row.put("reattachedPlays", drawable.isRunning && drawable.frameIndex != firstFrame)
                    row.put("sameDrawable", image.drawable === drawable)
                    activity.content.removeView(holder.itemView)
                    adapter.onViewRecycled(holder)
                    row.put("clearedStopped", !drawable.isRunning)
                    row.put("recycledCleared", image.drawable == null)
                    Glide.get(context).clearMemory()
                    // 背景绑定要等重新测量；在同一正式加载入口固定原几何，检查交付前的同步回显。
                    val loader = BaseThingsAdapter::class.java.declaredMethods.single { it.name ==
                        if (background) "loadAnimatedThingCardMediaBackground" else "loadAnimatedThingCardThumbnail" }
                        .apply { isAccessible = true }
                    val width = MediaMemoryAudit.field(savedRequest, "imageW")
                    val height = MediaMemoryAudit.field(savedRequest, "imageH")
                    val crop = MediaMemoryAudit.field(savedRequest, "crop")
                    val key = MediaMemoryAudit.field(savedRequest, "loadKey")
                    if (background) loader.invoke(adapter, holder, image, thing, gifFile.path, width, height, crop,
                        MediaMemoryAudit.field(savedRequest, "sourceAspectRatio"), key, null, null)
                    else loader.invoke(adapter, holder, image, gifFile.path, width, height, crop, key, null, null)
                    activity.content.addView(holder.itemView)
                    val poster = (image.drawable as? BitmapDrawable)?.bitmap
                    row.put("rebindImmediatePoster", poster != null)
                    row.put("posterPixelsMatch", poster?.sameAs(originalFirstFrame) == true)
                    row.put("posterNoSpinner", background || holder.pbLoading?.visibility == View.GONE)
                    row.put("returnCache", cacheState()).put("returnKey", image.getTag(if (background)
                        com.ywwynm.everythingdone.R.id.tag_thing_card_media_background_load_key else com.ywwynm.everythingdone.R.id.tag_thing_card_image_load_key))
                }
                var rebound: GifDrawable? = null
                val rebindDeadline = SystemClock.uptimeMillis() + 20000
                while (rebound == null && SystemClock.uptimeMillis() < rebindDeadline) {
                    main { rebound = image.drawable as? GifDrawable }; SystemClock.sleep(30)
                }
                SystemClock.sleep(150)
                main {
                    row.put("reboundPlays", rebound?.isRunning == true)
                    activity.content.removeView(holder.itemView)
                    adapter.onViewRecycled(holder)
                    Glide.get(context).clearMemory()
                    check(gifFile.setLastModified(gifFile.lastModified() + 5000))
                    adapter.onBindViewHolder(holder, 0)
                    row.put("changedFileRejectsPoster", image.drawable !is BitmapDrawable)
                    adapter.onViewRecycled(holder)
                }
                if (!background) row.put("failureFallback", GifFallbackAudit.run(activity, adapter, holder,
                    originalFirstFrame, savedRequest, output, main))
                originalFirstFrame.recycle()
                rows.put(row)
            }
        } finally { main { activity.finish() } }
        val result = JSONObject().put("cases", rows)
        File(output, "gif-lifecycle.json").writeText(result.toString(2))
        for (i in 0 until rows.length()) for (key in listOf("lateDeliveryStopped", "attachedPlays", "detachedStopped", "reattachedPlays", "sameDrawable", "clearedStopped", "recycledCleared", "reboundPlays", "rebindImmediatePoster", "posterPixelsMatch", "posterNoSpinner", "changedFileRejectsPoster")) {
            check(rows.getJSONObject(i).getBoolean(key)) { "GIF 生命周期断言失败 $key：$result" }
        }
        return result
    }
}
