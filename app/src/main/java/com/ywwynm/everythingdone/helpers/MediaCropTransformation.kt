package com.ywwynm.everythingdone.helpers

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.os.Build
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import java.security.MessageDigest

/**
 * Glide [BitmapTransformation] that applies the project's Thing Card / detail
 * attachment crop (center / scale / aspect) to each decoded frame by reusing
 * [MediaCropBitmapRenderer].
 *
 * GIF 由 Glide 将该变换应用到每帧。平台动态 WebP 不支持位图变换，详情页使用
 * [AnimatedMediaCropDrawable] 在绘制时应用同一裁剪几何，不能传入此变换。
 */
class MediaCropTransformation(
    private val targetWidth: Int,
    private val targetHeight: Int,
    private val crop: MediaCropBitmapRenderer.Crop
) : BitmapTransformation() {

    private val id = "$ID:$targetWidth:$targetHeight:${crop.fingerprint()}"

    override fun transform(
        pool: BitmapPool,
        toTransform: Bitmap,
        outWidth: Int,
        outHeight: Int
    ): Bitmap {
        if (targetWidth <= 0 || targetHeight <= 0 || toTransform.isRecycled ||
            toTransform.width <= 0 || toTransform.height <= 0) return toTransform

        // 每帧由 Glide 归还到池中；get 会清空旧像素，透明 GIF 不会残留上一帧。
        val output = pool.get(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 池也可能收过广色域图片，保持原 createBitmap 路径的 sRGB 输出。
            output.setColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        }
        MediaCropBitmapRenderer.renderCropInto(toTransform, output, crop)
        return output
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update(id.toByteArray(Charsets.UTF_8))
    }

    override fun equals(other: Any?): Boolean {
        return other is MediaCropTransformation && other.id == id
    }

    override fun hashCode(): Int = id.hashCode()

    companion object {
        private const val ID =
            "com.ywwynm.everythingdone.helpers.MediaCropTransformation"
    }
}
