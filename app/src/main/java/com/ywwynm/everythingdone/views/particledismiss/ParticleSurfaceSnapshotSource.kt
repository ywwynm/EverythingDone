package com.ywwynm.everythingdone.views.particledismiss

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View

/** 返回 true 时由生产者交付 sRGB 快照；false 表示仍使用原来的 Surface PixelCopy。 */
interface ParticleSurfaceSnapshotSource {
    fun copySdrSnapshot(done: (Bitmap?) -> Unit): Boolean
}

/** 只有层级用途的透明容器，不能作为粒子图像或恢复帧生产者。 */
internal interface ParticleTransparentSurfaceContainer

/** 只让显式支持的生产者接管色彩转换；普通 Surface 的捕获和完成判据不变。 */
internal object ParticleSurfaceCopy {
    fun isSource(view: View): Boolean = view !is ParticleTransparentSurfaceContainer &&
        (view is SurfaceView || view is ParticleDirectSurfaceSource)

    fun request(source: View, destination: Bitmap, done: (Int) -> Unit, handler: Handler) {
        val producer = source as? ParticleSurfaceSnapshotSource
        if (producer != null && producer.copySdrSnapshot { snapshot ->
                handler.post {
                    val status = when {
                        destination.isRecycled -> PixelCopy.ERROR_DESTINATION_INVALID
                        snapshot == null -> PixelCopy.ERROR_SOURCE_NO_DATA
                        else -> {
                            destination.eraseColor(Color.TRANSPARENT)
                            Canvas(destination).drawBitmap(snapshot, null,
                                Rect(0, 0, destination.width, destination.height), Paint(Paint.FILTER_BITMAP_FLAG))
                            PixelCopy.SUCCESS
                        }
                    }
                    snapshot?.recycle()
                    done(status)
                }
            }) return
        if (source is SurfaceView) PixelCopy.request(source, destination, { done(it) }, handler)
        else handler.post { done(PixelCopy.ERROR_SOURCE_NO_DATA) }
    }
}
