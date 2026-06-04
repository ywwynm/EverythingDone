package com.ywwynm.everythingdone.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

interface ThingCardCropEditorController {
    fun getCropCenterX(): Double
    fun getCropCenterY(): Double
    fun getCropUserScale(): Double
    fun getTargetAspectRatio(): Double
    fun setTargetAspectRatio(targetAspectRatio: Double)
}

class ThingCardCropEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), ThingCardCropEditorController {

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAA000000.toInt()
        style = Paint.Style.FILL
    }
    private val bitmapMatrix = Matrix()
    private val previewRect = RectF()
    private val cropRect = RectF()
    private val previewPath = Path()
    private val cropPath = Path()
    private val overlayPath = Path()
    private var imageScale: Float = 1f
    private var imageLeft: Float = 0f
    private var imageTop: Float = 0f
    private val cornerRadius: Float = resources.displayMetrics.density * 12f

    private var bitmap: Bitmap? = null
    private var targetAspectRatio: Float = 1f
    private var centerX: Float = 0.5f
    private var centerY: Float = 0.5f
    private var userScale: Float = 1f
    private var lastX: Float = 0f
    private var lastY: Float = 0f
    private var dragging: Boolean = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                userScale = clampUserScale(userScale * detector.scaleFactor)
                clampCropCenter()
                invalidate()
                return true
            }
        }
    )

    fun setCropBitmap(
        bitmap: Bitmap,
        targetAspectRatio: Double,
        centerX: Double,
        centerY: Double,
        userScale: Double
    ) {
        this.bitmap = bitmap
        this.targetAspectRatio = clampTargetAspectRatio(targetAspectRatio.toFloat())
        this.centerX = clampRatio(centerX.toFloat())
        this.centerY = clampRatio(centerY.toFloat())
        this.userScale = clampUserScale(userScale.toFloat())
        invalidate()
    }

    fun setSourceBitmap(bitmap: Bitmap) {
        this.bitmap = bitmap
        clampCropCenter()
        invalidate()
    }

    override fun getCropCenterX(): Double = centerX.toDouble()

    override fun getCropCenterY(): Double = centerY.toDouble()

    override fun getCropUserScale(): Double = userScale.toDouble()

    override fun getTargetAspectRatio(): Double = targetAspectRatio.toDouble()

    override fun setTargetAspectRatio(targetAspectRatio: Double) {
        this.targetAspectRatio = clampTargetAspectRatio(targetAspectRatio.toFloat())
        clampCropCenter()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cropBitmap = bitmap ?: return
        updateGeometry(cropBitmap)
        clampCropCenter()

        bitmapMatrix.reset()
        bitmapMatrix.setScale(imageScale, imageScale)
        bitmapMatrix.postTranslate(imageLeft, imageTop)

        val saved = canvas.save()
        canvas.clipPath(previewPath)
        canvas.drawBitmap(cropBitmap, bitmapMatrix, null)
        canvas.drawPath(overlayPath, overlayPaint)
        canvas.restoreToCount(saved)

        canvas.drawRoundRect(cropRect, cornerRadius, cornerRadius, framePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                lastX = event.x
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging && !scaleDetector.isInProgress) {
                    panBy(event.x - lastX, event.y - lastY)
                    lastX = event.x
                    lastY = event.y
                }
                return true
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                return true
            }
        }
        return true
    }

    private fun panBy(dx: Float, dy: Float) {
        val cropBitmap = bitmap ?: return
        updateGeometry(cropBitmap)
        if (imageScale <= 0f) return

        val scaledW = cropBitmap.width * imageScale
        val scaledH = cropBitmap.height * imageScale
        val newLeft = clampImageOffset(imageLeft + dx, cropRect.right - scaledW, cropRect.left)
        val newTop = clampImageOffset(imageTop + dy, cropRect.bottom - scaledH, cropRect.top)
        centerX = clampRatio((cropRect.centerX() - newLeft) / scaledW)
        centerY = clampRatio((cropRect.centerY() - newTop) / scaledH)
        invalidate()
    }

    private fun updateGeometry(cropBitmap: Bitmap) {
        val padding = 16f * resources.displayMetrics.density
        val availableW = max(1f, width - padding * 2f)
        val availableH = max(1f, height - padding * 2f)
        previewRect.set(padding, padding, padding + availableW, padding + availableH)

        val availableAspectRatio = availableW / availableH
        val frameW: Float
        val frameH: Float
        if (availableAspectRatio > targetAspectRatio) {
            frameH = availableH
            frameW = frameH * targetAspectRatio
        } else {
            frameW = availableW
            frameH = frameW / targetAspectRatio
        }
        val frameLeft = (width - frameW) / 2f
        val frameTop = (height - frameH) / 2f
        cropRect.set(frameLeft, frameTop, frameLeft + frameW, frameTop + frameH)

        val coverScale = max(
            cropRect.width() / cropBitmap.width.toFloat(),
            cropRect.height() / cropBitmap.height.toFloat()
        )
        imageScale = coverScale * userScale
        val scaledW = cropBitmap.width * imageScale
        val scaledH = cropBitmap.height * imageScale
        imageLeft = clampImageOffset(
            cropRect.centerX() - centerX * scaledW,
            cropRect.right - scaledW,
            cropRect.left
        )
        imageTop = clampImageOffset(
            cropRect.centerY() - centerY * scaledH,
            cropRect.bottom - scaledH,
            cropRect.top
        )

        previewPath.reset()
        previewPath.addRoundRect(
            previewRect,
            cornerRadius,
            cornerRadius,
            Path.Direction.CW
        )
        cropPath.reset()
        cropPath.addRoundRect(cropRect, cornerRadius, cornerRadius, Path.Direction.CW)
        overlayPath.set(previewPath)
        overlayPath.op(cropPath, Path.Op.DIFFERENCE)
    }

    private fun clampCropCenter() {
        val cropBitmap = bitmap ?: return
        if (cropRect.isEmpty) updateGeometry(cropBitmap)
        val scaledW = cropBitmap.width * imageScale
        val scaledH = cropBitmap.height * imageScale
        if (scaledW <= 0f || scaledH <= 0f) return
        centerX = clampRatio((cropRect.centerX() - imageLeft) / scaledW)
        centerY = clampRatio((cropRect.centerY() - imageTop) / scaledH)
    }

    private fun clampRatio(value: Float): Float = clamp(value, 0f, 1f)

    private fun clampUserScale(value: Float): Float = clamp(value, 1f, 3f)

    private fun clampTargetAspectRatio(value: Float): Float = clamp(value, 0.1f, 10f)

    private fun clamp(value: Float, minValue: Float, maxValue: Float): Float {
        return max(minValue, min(maxValue, value))
    }

    private fun clampImageOffset(value: Float, minValue: Float, maxValue: Float): Float {
        if (minValue > maxValue) return (minValue + maxValue) / 2f
        return clamp(value, minValue, maxValue)
    }

}
