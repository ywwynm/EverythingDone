package com.ywwynm.everythingdone.helpers

import android.graphics.Bitmap
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import java.security.MessageDigest

/**
 * Glide [BitmapTransformation] that applies the project's Thing Card / detail
 * attachment crop (center / scale / aspect) to each decoded frame by reusing
 * [MediaCropBitmapRenderer].
 *
 * For an Animated Image (GIF / animated WebP), Glide applies this transformation
 * to every frame, so the result is an animated drawable whose frames are already
 * cropped to the target box and shown with `CENTER_CROP` — the same display
 * contract as the baked static bitmap, just animated, without returning to the
 * fragile `ImageView.imageMatrix` path. See ADR-0007.
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
        return MediaCropBitmapRenderer.renderCrop(
            toTransform, targetWidth, targetHeight, crop
        ) ?: toTransform
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
