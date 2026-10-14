package com.ywwynm.everythingdone.helpers

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import android.widget.ImageView
import androidx.fragment.app.FragmentActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.model.DoingBackgroundCrops
import java.io.File

/** 图片仍留在 Doing 原有内容树内，沿用整页粒子呈现与文字动画的绘制过程。 */
class DoingBackgroundDisplay(private val activity: FragmentActivity, private val view: ImageView) : AutoCloseable {
    private val requests = Glide.with(activity)
    private val preferences = activity.getSharedPreferences(Def.Meta.PREFERENCES_NAME, 0)
    private data class Request(val path: String, val modified: Long, val width: Int, val height: Int,
                               val crop: DoingBackgroundCrops.Frame)
    private var current: Request? = null
    private val layoutListener = View.OnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
        if (r - l != oldR - oldL || b - t != oldB - oldT) refresh()
    }

    init { view.addOnLayoutChangeListener(layoutListener) }

    fun refresh() {
        if (activity.isDestroyed || view.width <= 0 || view.height <= 0) return
        val path = preferences.getString(Def.Meta.KEY_DOING_BACKGROUND, null)
        val file = path?.let(::File)?.takeIf { it.isFile && it.canRead() }
        if (file == null) {
            current = null
            requests.clear(view)
            view.setImageDrawable(null)
            view.setBackgroundColor(Color.TRANSPARENT)
            return
        }
        val crops = DoingBackgroundCrops.fromJson(preferences.getString(Def.Meta.KEY_DOING_BACKGROUND_CROPS, null))
        val request = Request(file.path, file.lastModified(), view.width, view.height,
            crops.forSize(view.width, view.height))
        if (request == current) return
        current = request
        // 透明图片也有稳定底色；失败时重新露出默认系统壁纸。
        view.setBackgroundColor(Color.BLACK)
        requests.load(file).signature(com.bumptech.glide.signature.ObjectKey(request.modified))
            .override(request.width, request.height)
            .transform(MediaCropTransformation(request.width, request.height,
                MediaCropBitmapRenderer.Crop(request.crop.centerX, request.crop.centerY, request.crop.scale)))
            .dontAnimate()
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                    Log.w("DoingBackground", "无法加载正在做事背景，恢复默认背景", e)
                    if (current == request) {
                        current = null
                        view.setBackgroundColor(Color.TRANSPARENT)
                    }
                    return false
                }
                override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?,
                    dataSource: DataSource, isFirstResource: Boolean): Boolean = false
            }).into(view)
    }

    override fun close() {
        view.removeOnLayoutChangeListener(layoutListener)
        requests.clear(view)
    }
}
