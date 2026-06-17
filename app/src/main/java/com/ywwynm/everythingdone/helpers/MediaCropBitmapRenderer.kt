@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.helpers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.ywwynm.everythingdone.model.ThingCardAppearance
import kotlin.math.max
import kotlin.math.min

object MediaCropBitmapRenderer {

    data class Crop(
        val centerX: Double,
        val centerY: Double,
        val userScale: Double,
        val sourceAspectRatio: Double? = null
    ) {
        fun fingerprint(): String {
            return "$centerX:$centerY:$userScale:${sourceAspectRatio ?: "none"}"
        }
    }

    fun renderCrop(
        drawable: Drawable,
        targetWidth: Int,
        targetHeight: Int,
        crop: Crop
    ): Bitmap? {
        val source = getSourceBitmap(drawable) ?: return null
        return renderCrop(source, targetWidth, targetHeight, crop)
    }

    fun renderCrop(
        source: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        crop: Crop
    ): Bitmap? {
        if (targetWidth <= 0 || targetHeight <= 0 || source.isRecycled) return null
        val sourceWidth = source.width
        val sourceHeight = source.height
        if (sourceWidth <= 0 || sourceHeight <= 0) return null

        val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val sourceAspectRatio = normalizeSourceAspectRatio(crop.sourceAspectRatio)
        val cropSourceW: Float
        val cropSourceH: Float
        if (sourceAspectRatio != null) {
            val sourceRatio = sourceWidth.toFloat() / sourceHeight.toFloat()
            if (sourceAspectRatio > sourceRatio) {
                cropSourceW = sourceWidth.toFloat()
                cropSourceH = sourceWidth.toFloat() / sourceAspectRatio
            } else {
                cropSourceH = sourceHeight.toFloat()
                cropSourceW = sourceHeight.toFloat() * sourceAspectRatio
            }
        } else {
            cropSourceW = sourceWidth.toFloat()
            cropSourceH = sourceHeight.toFloat()
        }

        val coverScale = max(
            targetWidth.toFloat() / cropSourceW,
            targetHeight.toFloat() / cropSourceH
        )
        val userScale = normalizeUserScale(crop.userScale)
        val effectiveScale = coverScale * userScale
        val scaledWidth = sourceWidth * effectiveScale
        val scaledHeight = sourceHeight * effectiveScale

        val centerX = normalizeCropRatio(crop.centerX) * scaledWidth
        val centerY = normalizeCropRatio(crop.centerY) * scaledHeight
        val left = clampCropOffset(
            targetWidth / 2f - centerX,
            targetWidth - scaledWidth,
            0f
        )
        val top = clampCropOffset(
            targetHeight / 2f - centerY,
            targetHeight - scaledHeight,
            0f
        )

        val matrix = Matrix()
        matrix.setScale(effectiveScale, effectiveScale)
        matrix.postTranslate(left, top)
        Canvas(output).drawBitmap(source, matrix, null)
        return output
    }

    private fun getSourceBitmap(drawable: Drawable): Bitmap? {
        val bitmap = (drawable as? BitmapDrawable)?.bitmap
        if (bitmap != null && !bitmap.isRecycled) return bitmap

        val sourceWidth = drawable.intrinsicWidth
        val sourceHeight = drawable.intrinsicHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) return null
        val output = Bitmap.createBitmap(sourceWidth, sourceHeight, Bitmap.Config.ARGB_8888)
        val oldBounds = drawable.copyBounds()
        drawable.setBounds(0, 0, sourceWidth, sourceHeight)
        drawable.draw(Canvas(output))
        drawable.setBounds(oldBounds)
        return output
    }

    private fun normalizeSourceAspectRatio(value: Double?): Float? {
        if (value == null || value.isNaN() || value.isInfinite() || value <= 0.0) {
            return null
        }
        return value.toFloat()
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
