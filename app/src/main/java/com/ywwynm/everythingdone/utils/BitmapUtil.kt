package com.ywwynm.everythingdone.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.media.ExifInterface

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Created by ywwynm on 2015/9/23.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Utils for Bitmap
 */
object BitmapUtil {

    const val TAG: String = "BitmapUtil"

    private fun calculateInSampleSize(oWidth: Int, oHeight: Int, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        if (oHeight > reqWidth || oWidth > reqHeight) {
            val halfHeight: Int = oHeight / 2
            val halfWidth: Int = oWidth / 2
            while ((halfHeight / inSampleSize) > reqHeight
                    && (halfWidth / inSampleSize) > reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    @JvmStatic
    fun createLayeredBitmap(d: Drawable?, color: Int): Bitmap? {
        return createLayeredBitmap(d,
                com.ywwynm.everythingdone.model.ThingBackground.pure(color))
    }

    /**
     * Phase 8: gradient-aware [createLayeredBitmap]. PURE
     * stays a flat [ColorDrawable] layer; GRADIENT uses a
     * [android.graphics.drawable.GradientDrawable] with the bg's stops
     * and orientation so the rendered bitmap carries the gradient. Foreground
     * drawable `d` layers on top unchanged.
     *
     * Used by `SystemNotificationUtil.extendWearable` (and any other
     * caller that needs to bake an accent into a fixed-size bitmap — notably
     * RemoteViews paths that can't accept a Shader).
     */
    @JvmStatic
    fun createLayeredBitmap(
            d: Drawable?, bg: com.ywwynm.everythingdone.model.ThingBackground?): Bitmap {
        val background: Drawable
        if (bg == null || bg.mode === com.ywwynm.everythingdone.model.ThingBackground.Mode.PURE) {
            background = ColorDrawable(bg?.color ?: 0)
        } else {
            val gd: android.graphics.drawable.GradientDrawable =
                    android.graphics.drawable.GradientDrawable()
            gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE)
            gd.colors = intArrayOf(bg.color, bg.endColor)
            gd.setOrientation(toAndroidOrientation(bg.orientation))
            background = gd
        }

        val lb = LayerDrawable(arrayOf(background, d))

        val w: Int = d!!.intrinsicWidth
        val h: Int = d.intrinsicHeight
        val bm: Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        lb.setBounds(0, 0, w, h)
        lb.draw(Canvas(bm))

        return bm
    }

    private fun toAndroidOrientation(
            o: com.ywwynm.everythingdone.model.ThingBackground.Orientation?): android.graphics.drawable.GradientDrawable.Orientation {
        when (o) {
            com.ywwynm.everythingdone.model.ThingBackground.Orientation.L_R   -> return android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT
            com.ywwynm.everythingdone.model.ThingBackground.Orientation.T_B   -> return android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM
            com.ywwynm.everythingdone.model.ThingBackground.Orientation.LT_RB -> return android.graphics.drawable.GradientDrawable.Orientation.TL_BR
            com.ywwynm.everythingdone.model.ThingBackground.Orientation.RT_LB -> return android.graphics.drawable.GradientDrawable.Orientation.TR_BL
            com.ywwynm.everythingdone.model.ThingBackground.Orientation.LB_RT -> return android.graphics.drawable.GradientDrawable.Orientation.BL_TR
            com.ywwynm.everythingdone.model.ThingBackground.Orientation.RB_LT -> return android.graphics.drawable.GradientDrawable.Orientation.BR_TL
            com.ywwynm.everythingdone.model.ThingBackground.Orientation.R_L   -> return android.graphics.drawable.GradientDrawable.Orientation.RIGHT_LEFT
            com.ywwynm.everythingdone.model.ThingBackground.Orientation.B_T   -> return android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP
            else -> {}
        }
        return android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT
    }

    @JvmStatic
    fun createScaledBitmap(src: Bitmap?, reqWidth: Int, reqHeight: Int, inside: Boolean): Bitmap? {
        if (src == null) {
            return null
        }

        val oWidth: Int  = src.getWidth()
        val oHeight: Int = src.getHeight()

        val dst: Bitmap
        if (inside && oWidth <= reqWidth && oHeight <= reqHeight) {
            dst = src
        } else {
            val fW: Float = oWidth.toFloat()  / reqWidth
            val fH: Float = oHeight.toFloat() / reqHeight
            val maintainedSide = if (inside) {
                if (fW >= fH) oWidth else oHeight
            } else {
                if (fW <= fH) oWidth else oHeight
            }
            if (maintainedSide == oWidth) {
                val height: Int = oHeight * reqWidth / oWidth
                dst = Bitmap.createScaledBitmap(src, reqWidth, height, !inside)
            } else {
                val width: Int = oWidth * reqHeight / oHeight
                dst = Bitmap.createScaledBitmap(src, width, reqHeight, !inside)
            }
        }
        if (src !== dst) {
            src.recycle()
        }
        return dst
    }

    @JvmStatic
    fun createCroppedBitmap(src: Bitmap?, reqWidth: Int, reqHeight: Int): Bitmap {
        val scaledBm: Bitmap = createScaledBitmap(src, reqWidth, reqHeight, false)!!

        var x = 0
        var y = 0
        val oWidth: Int  = scaledBm.getWidth()
        val oHeight: Int = scaledBm.getHeight()

        if (reqWidth < oWidth) {
            x = (oWidth - reqWidth) / 2
        }
        if (reqHeight < oHeight) {
            y = (oHeight - reqHeight) / 2
        }

        val croppedBm: Bitmap = Bitmap.createBitmap(scaledBm, x, y, reqWidth, reqHeight)
        if (scaledBm !== croppedBm) {
            scaledBm.recycle()
        }
        return croppedBm
    }

    @JvmStatic
    fun decodeFileWithRequiredSize(pathName: String?, reqWidth: Int, reqHeight: Int): Bitmap? {
        val options: BitmapFactory.Options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        BitmapFactory.decodeFile(pathName, options)

        val oWidth: Int  = options.outWidth
        val oHeight: Int = options.outHeight

        if (oWidth == 0 || oHeight == 0) {
            return null
        }

        options.inSampleSize = calculateInSampleSize(oWidth, oHeight, reqWidth, reqHeight)
        options.inJustDecodeBounds = false
        var src: Bitmap? = BitmapFactory.decodeFile(pathName, options)
        src = tryToGetRotatedBitmap(src, pathName)

        return createCroppedBitmap(src, reqWidth, reqHeight)
    }

    @JvmStatic
    fun decodeFileFitsSize(pathName: String?, fWidth: Int, fHeight: Int): Bitmap? {
        val options: BitmapFactory.Options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        BitmapFactory.decodeFile(pathName, options)

        var src: Bitmap?
        val oWidth: Int  = options.outWidth
        val oHeight: Int = options.outHeight

        if (oWidth >= fWidth && oHeight >= fHeight) {
            options.inSampleSize = calculateInSampleSize(oWidth, oHeight, fWidth, fHeight)
            options.inJustDecodeBounds = false
            src = BitmapFactory.decodeFile(pathName, options)
        } else {
            src = BitmapFactory.decodeFile(pathName)
        }
        src = tryToGetRotatedBitmap(src, pathName)

        return createScaledBitmap(src, fWidth, fHeight, true)
    }

    @JvmStatic
    fun tryToGetRotatedBitmap(src: Bitmap?, pathName: String?): Bitmap? {
        try {
            val exif = ExifInterface(pathName!!)
            val orientation: Int = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                else -> return src
            }
            val ret: Bitmap = Bitmap.createBitmap(src!!, 0, 0, src.getWidth(),
                    src.getHeight(), matrix, true)
            if (ret !== src) {
                src.recycle()
            }
            return ret
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return src
    }

    @JvmStatic
    fun saveBitmapToStorage(parentPath: String?, name: String?, bitmap: Bitmap?): File? {
        val file: File = FileUtil.createFile(parentPath, name) ?: return null

        var fos: FileOutputStream? = null
        try {
            fos = FileOutputStream(file)
            bitmap!!.compress(Bitmap.CompressFormat.JPEG, 100, fos)
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            FileUtil.closeStream(fos)
        }
        return file
    }
}
