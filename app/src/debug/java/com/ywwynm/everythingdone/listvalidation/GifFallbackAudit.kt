package com.ywwynm.everythingdone.listvalidation

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.view.View
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.load.Options
import com.bumptech.glide.signature.ObjectKey
import com.ywwynm.everythingdone.adapters.BaseThingsAdapter
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/** 真实坏 GIF → 静态 fallback 请求链；仅延迟夹具 PNG 的读取，验证加载圈延迟不会越过失败回调。 */
internal object GifFallbackAudit {
    fun run(activity: GifLifecycleActivity, adapter: BaseThingsAdapter,
            holder: BaseThingsAdapter.BaseThingViewHolder, firstFrame: Bitmap, savedRequest: Any,
            output: File, main: (() -> Unit) -> Unit): JSONObject {
        val invalid = File(activity.filesDir, "fallback-invalid.gif").apply { writeText("invalid GIF fixture") }
        val fallback = File(activity.filesDir, "fallback-delayed.png")
        fallback.outputStream().use { firstFrame.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Glide.get(activity).registry.prepend(String::class.java, InputStream::class.java,
            object : ModelLoaderFactory<String, InputStream> {
                override fun teardown() = Unit
                override fun build(factory: MultiModelLoaderFactory): ModelLoader<String, InputStream> =
                    object : ModelLoader<String, InputStream> {
                        override fun handles(model: String) = model == fallback.path
                        override fun buildLoadData(model: String, width: Int, height: Int, options: Options) =
                            ModelLoader.LoadData(ObjectKey(model), object : DataFetcher<InputStream> {
                                private var stream: InputStream? = null
                                @Volatile private var canceled = false
                                override fun getDataClass() = InputStream::class.java
                                override fun getDataSource() = DataSource.LOCAL
                                override fun cancel() { canceled = true }
                                override fun cleanup() { stream?.close() }
                                override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
                                    SystemClock.sleep(360)
                                    if (!canceled) {
                                        stream = fallback.inputStream()
                                        callback.onDataReady(stream)
                                    }
                                }
                            })
                    }
            })
        val image = checkNotNull(holder.ivImageAttachment)
        main {
            activity.content.addView(holder.itemView)
            val loader = BaseThingsAdapter::class.java.declaredMethods.single { it.name == "loadAnimatedThingCardThumbnail" }
                .apply { isAccessible = true }
            loader.invoke(adapter, holder, image, invalid.path, firstFrame.width, firstFrame.height,
                MediaMemoryAudit.field(savedRequest, "crop"), "invalid-gif-fallback-fixture", fallback.path, null)
        }
        SystemClock.sleep(240)
        val row = JSONObject()
        main { row.put("noDelayedSpinnerAfterFailure", holder.pbLoading?.visibility != View.VISIBLE) }
        val deadline = SystemClock.uptimeMillis() + 5000
        var shown = false
        do {
            main { shown = image.drawable is BitmapDrawable }
            SystemClock.sleep(30)
        } while (!shown && SystemClock.uptimeMillis() < deadline)
        main {
            row.put("fallbackShown", shown).put("fallbackSpinnerHidden", holder.pbLoading?.visibility != View.VISIBLE)
            activity.content.removeView(holder.itemView)
            adapter.onViewRecycled(holder)
        }
        File(output, "gif-fallback.json").writeText(row.toString(2))
        check(row.getBoolean("noDelayedSpinnerAfterFailure") && shown && row.getBoolean("fallbackSpinnerHidden")) {
            "坏 GIF 的静态回退残留加载圈：$row"
        }
        return row
    }
}
