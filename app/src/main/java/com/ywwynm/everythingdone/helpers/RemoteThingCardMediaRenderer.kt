@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingCardAppearance
import com.ywwynm.everythingdone.permission.PermissionUtil
import com.ywwynm.everythingdone.utils.BitmapUtil
import kotlin.math.max
import kotlin.math.min

/**
 * App-side renderer for remote card surfaces. RemoteViews and standard
 * notifications cannot run the normal ImageView matrix crop pipeline, so this
 * object bakes the selected media, exact video frame, and saved crop into a
 * bitmap before crossing into the launcher or system notification process.
 */
object RemoteThingCardMediaRenderer {

    private const val MAX_DECODE_DIMENSION = 2048
    private const val MIN_TARGET_SIZE = 1

    data class ThumbnailRequest(
        val bitmap: Bitmap,
        val source: ThingCardMediaHelper.MediaSource,
        val targetWidth: Int,
        val targetHeight: Int
    )

    data class MediaBackgroundRequest(
        val bitmap: Bitmap,
        val source: ThingCardMediaHelper.MediaSource,
        val targetWidth: Int,
        val targetHeight: Int
    )

    @JvmStatic
    fun resolveRenderableMediaSource(
        context: Context,
        thing: Thing?
    ): ThingCardMediaHelper.MediaSource? {
        if (thing == null || thing.isPrivate()) return null
        val source = ThingCardMediaHelper.resolveEffectiveMediaSource(thing) ?: return null
        return if (hasPermissionForSource(context, source)) source else null
    }

    @JvmStatic
    fun getThumbnailTargetHeight(
        thing: Thing,
        targetWidth: Int,
        maxTargetHeight: Int
    ): Int {
        val width = max(MIN_TARGET_SIZE, targetWidth)
        val aspectRatio = getThumbnailSourceAspectRatio(thing)
        val rawHeight = max(MIN_TARGET_SIZE, (width / aspectRatio).toInt())
        val maxHeight = max(MIN_TARGET_SIZE, maxTargetHeight)
        return min(rawHeight, maxHeight)
    }

    @JvmStatic
    fun renderThumbnail(
        context: Context,
        thing: Thing,
        targetWidth: Int,
        targetHeight: Int
    ): ThumbnailRequest? {
        val source = resolveRenderableMediaSource(context, thing) ?: return null
        val width = max(MIN_TARGET_SIZE, targetWidth)
        val height = max(MIN_TARGET_SIZE, targetHeight)
        val crop = getThingCardThumbnailCrop(thing, source)
        val frameMs = getThingCardVideoFrameMs(thing, source)
        val sourceBitmap = decodeSourceBitmap(source, crop.scale, width, height, frameMs)
            ?: return null
        val cropped = renderCrop(sourceBitmap, crop.centerX, crop.centerY, crop.scale, width, height)
        return ThumbnailRequest(cropped, source, width, height)
    }

    @JvmStatic
    fun renderMediaBackground(
        context: Context,
        thing: Thing,
        targetWidth: Int,
        targetHeight: Int
    ): MediaBackgroundRequest? {
        if (!thing.thingCardAppearance.mediaBackgroundEnabled) return null
        val source = resolveRenderableMediaSource(context, thing) ?: return null
        val width = max(MIN_TARGET_SIZE, targetWidth)
        val height = max(MIN_TARGET_SIZE, targetHeight)
        val crop = getThingCardMediaBackgroundCrop(thing, source)
        val frameMs = getThingCardVideoFrameMs(thing, source)
        val sourceBitmap = decodeSourceBitmap(source, crop.scale, width, height, frameMs)
            ?: return null
        val cropped = renderCrop(sourceBitmap, crop.centerX, crop.centerY, crop.scale, width, height)
        val maskAlpha = (getThingCardMediaBackgroundMaskStrength(thing, source) * 255).toInt()
            .coerceIn(0, 255)
        if (maskAlpha > 0) {
            Canvas(cropped).drawColor(Color.argb(maskAlpha, 0, 0, 0))
        }
        return MediaBackgroundRequest(cropped, source, width, height)
    }

    private fun hasPermissionForSource(
        context: Context,
        source: ThingCardMediaHelper.MediaSource
    ): Boolean {
        return if (source.isVideo) {
            PermissionUtil.hasVideoPermission(context)
        } else {
            PermissionUtil.hasImagePermission(context)
        }
    }

    private fun getThumbnailSourceAspectRatio(thing: Thing): Float {
        val source = ThingCardMediaHelper.resolveEffectiveMediaSource(thing)
        val sourceAspectRatio = source?.let {
            thing.thingCardAppearance.sources[it.typePathName]
                ?.thumbnailCrop
                ?.sourceAspectRatio
        }
        if (sourceAspectRatio != null && sourceAspectRatio > 0.0) {
            return sourceAspectRatio.toFloat()
        }
        return if (thing.thingCardAppearance.spanMode == Thing.THING_CARD_SPAN_FULL) {
            16f / 9f
        } else {
            4f / 3f
        }
    }

    private fun getThingCardThumbnailCrop(
        thing: Thing,
        mediaSource: ThingCardMediaHelper.MediaSource
    ): ThingCardAppearance.ThingCardThumbnailCrop {
        return thing.thingCardAppearance.sources[mediaSource.typePathName]
            ?.thumbnailCrop
            ?: ThingCardAppearance.ThingCardThumbnailCrop()
    }

    private fun getThingCardMediaBackgroundCrop(
        thing: Thing,
        mediaSource: ThingCardMediaHelper.MediaSource
    ): ThingCardAppearance.ThingCardMediaBackgroundCrop {
        return thing.thingCardAppearance.sources[mediaSource.typePathName]
            ?.backgroundCrop
            ?: ThingCardAppearance.ThingCardMediaBackgroundCrop()
    }

    private fun getThingCardMediaBackgroundMaskStrength(
        thing: Thing,
        mediaSource: ThingCardMediaHelper.MediaSource
    ): Double {
        val value = thing.thingCardAppearance.sources[mediaSource.typePathName]
            ?.mediaBackgroundMaskStrength
            ?: ThingCardAppearance.DEFAULT_MASK_STRENGTH
        if (value.isNaN() || value.isInfinite()) {
            return ThingCardAppearance.DEFAULT_MASK_STRENGTH
        }
        return max(0.0, min(1.0, value))
    }

    private fun getThingCardVideoFrameMs(
        thing: Thing,
        mediaSource: ThingCardMediaHelper.MediaSource
    ): Long? {
        if (!mediaSource.isVideo) return null
        return thing.thingCardAppearance.sources[mediaSource.typePathName]
            ?.videoFrameMs
            ?.takeIf { it >= 0L }
    }

    private fun decodeSourceBitmap(
        source: ThingCardMediaHelper.MediaSource,
        userScale: Double,
        targetWidth: Int,
        targetHeight: Int,
        videoFrameMs: Long?
    ): Bitmap? {
        return if (source.isVideo) {
            decodeVideoFrame(source.pathName, videoFrameMs)
        } else {
            decodeImage(source.pathName, userScale, targetWidth, targetHeight)
        }
    }

    private fun decodeImage(
        pathName: String,
        userScale: Double,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeFile(pathName, bounds)
        val originalWidth = bounds.outWidth
        val originalHeight = bounds.outHeight
        if (originalWidth <= 0 || originalHeight <= 0) return null

        val scale = normalizeUserScale(userScale).coerceAtMost(2.5f)
        val requestedWidth = min(MAX_DECODE_DIMENSION, max(targetWidth, (targetWidth * scale).toInt()))
        val requestedHeight = min(MAX_DECODE_DIMENSION, max(targetHeight, (targetHeight * scale).toInt()))
        val options = BitmapFactory.Options()
        options.inSampleSize = calculateInSampleSize(
            originalWidth,
            originalHeight,
            requestedWidth,
            requestedHeight
        )
        options.inJustDecodeBounds = false
        val decoded = BitmapFactory.decodeFile(pathName, options) ?: return null
        return BitmapUtil.tryToGetRotatedBitmap(decoded, pathName)
    }

    private fun decodeVideoFrame(pathName: String, videoFrameMs: Long?): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(pathName)
            val frameUs = max(0L, videoFrameMs ?: 0L) * 1000L
            retriever.getFrameAtTime(frameUs, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: retriever.frameAtTime
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun renderCrop(
        source: Bitmap,
        centerXValue: Double,
        centerYValue: Double,
        userScaleValue: Double,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap {
        val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val sourceWidth = source.width
        val sourceHeight = source.height
        if (sourceWidth <= 0 || sourceHeight <= 0) return output

        val coverScale = max(
            targetWidth.toFloat() / sourceWidth.toFloat(),
            targetHeight.toFloat() / sourceHeight.toFloat()
        )
        val userScale = normalizeUserScale(userScaleValue)
        val effectiveScale = coverScale * userScale
        val scaledWidth = sourceWidth * effectiveScale
        val scaledHeight = sourceHeight * effectiveScale

        val centerX = normalizeCropRatio(centerXValue) * scaledWidth
        val centerY = normalizeCropRatio(centerYValue) * scaledHeight
        val left = clampCropOffset(targetWidth / 2f - centerX, targetWidth - scaledWidth, 0f)
        val top = clampCropOffset(targetHeight / 2f - centerY, targetHeight - scaledHeight, 0f)

        val matrix = Matrix()
        matrix.setScale(effectiveScale, effectiveScale)
        matrix.postTranslate(left, top)
        canvas.drawBitmap(source, matrix, null)
        return output
    }

    private fun calculateInSampleSize(
        originalWidth: Int,
        originalHeight: Int,
        requestedWidth: Int,
        requestedHeight: Int
    ): Int {
        var inSampleSize = 1
        if (originalHeight > requestedHeight || originalWidth > requestedWidth) {
            val halfHeight = originalHeight / 2
            val halfWidth = originalWidth / 2
            while (halfHeight / inSampleSize >= requestedHeight
                && halfWidth / inSampleSize >= requestedWidth
            ) {
                inSampleSize *= 2
            }
        }
        return max(1, inSampleSize)
    }

    private fun normalizeCropRatio(value: Double): Float {
        if (value.isNaN() || value.isInfinite()) {
            return ThingCardAppearance.DEFAULT_CROP_CENTER.toFloat()
        }
        return max(0.0, min(1.0, value)).toFloat()
    }

    private fun normalizeUserScale(value: Double): Float {
        if (value.isNaN() || value.isInfinite()) {
            return ThingCardAppearance.DEFAULT_USER_SCALE.toFloat()
        }
        return max(ThingCardAppearance.DEFAULT_USER_SCALE, value).toFloat()
    }

    private fun clampCropOffset(value: Float, minValue: Float, maxValue: Float): Float {
        return max(minValue, min(maxValue, value))
    }
}
