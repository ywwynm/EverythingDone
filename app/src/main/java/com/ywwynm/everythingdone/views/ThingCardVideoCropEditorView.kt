@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.ThingBackground
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class ThingCardVideoCropEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs), ThingCardCropEditorController {

    companion object {
        private const val TAG = "ThingVideoCropEditor"
        private const val POSITION_TICK_MS = 80L
        private const val VIDEO_END_FRAME_GUARD_MS = 50
        private const val FIRST_FRAME_FALLBACK_MS = 320L
    }

    var onPositionChanged: ((Long) -> Unit)? = null
    var onPlayingChanged: ((Boolean) -> Unit)? = null

    private val textureMatrix = Matrix()
    private val previewRect = RectF()
    private val cropRect = RectF()
    private val previewPath = Path()
    private val cropPath = Path()
    private val overlayPath = Path()
    private val outsidePreviewPath = Path()
    private val fullPath = Path()
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAA000000.toInt()
        style = Paint.Style.FILL
    }
    private val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.app_chrome_surface_elevated)
        style = Paint.Style.FILL
    }
    private val cornerRadius: Float = resources.displayMetrics.density * 12f

    private val textureView = TextureView(context)
    private val fallbackView = FallbackFrameView(context)
    private val overlayView = OverlayView(context)
    private val loadingView = LoadingView(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val positionRunnable = object : Runnable {
        override fun run() {
            if (!isPlaying()) return
            if (finishPlaybackIfNeeded()) return
            notifyCurrentPosition()
            mainHandler.postDelayed(this, POSITION_TICK_MS)
        }
    }
    private val firstFrameFallbackRunnable = Runnable {
        if (prepared && !firstFrameVisible) {
            if (fallbackView.hasFallbackBitmap()) {
                showFallbackPreviewOnly()
            } else {
                firstFrameVisible = true
                setLoadingVisible(false)
            }
        }
    }

    private var surface: Surface? = null
    private var player: MediaPlayer? = null
    private var prepared = false
    private var released = false
    private var pendingPlay = false
    private var playing = false
    private var firstFrameVisible = false
    private var loadingVisible = true
    private var pathName: String? = null
    private var pendingSeekMs: Long = 0L
    private var currentFrameMs: Long = 0L
    private var durationMs: Int = 0
    private var videoWidth: Int = 1
    private var videoHeight: Int = 1

    private var targetAspectRatio: Float = 1f
    private var centerX: Float = 0.5f
    private var centerY: Float = 0.5f
    private var userScale: Float = 1f
    private var imageScale: Float = 1f
    private var imageLeft: Float = 0f
    private var imageTop: Float = 0f
    private var lastX: Float = 0f
    private var lastY: Float = 0f
    private var dragging = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                userScale = clampUserScale(userScale * detector.scaleFactor)
                updateGeometry()
                clampCropCenter()
                invalidateCrop()
                return true
            }
        }
    )

    init {
        isClickable = true
        isFocusable = true
        // TextureView 是硬件合成层，会被 applyVideoTransform 手动撑到完整缩放视频尺寸，
        // 必然向上下溢出本 view 边界。dialog 容器链为了按钮 ripple 设了 clipChildren=false，
        // 单靠父容器裁剪无法可靠裁住这个硬件层，视频帧会漏到预览区之外。
        // 这里给本 view 自身加 clipToOutline，把所有子 view 强制硬裁到预览框内。
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (previewRect.isEmpty) {
                    outline.setRect(0, 0, view.width, view.height)
                } else {
                    outline.setRoundRect(
                        previewRect.left.roundToInt(),
                        previewRect.top.roundToInt(),
                        previewRect.right.roundToInt(),
                        previewRect.bottom.roundToInt(),
                        cornerRadius
                    )
                }
            }
        }
        textureView.isOpaque = true
        textureView.clipToOutline = false
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surfaceTexture: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                preparePlayer(surfaceTexture)
            }

            override fun onSurfaceTextureSizeChanged(
                surfaceTexture: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                applyVideoTransform()
            }

            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                releasePlayer()
                surface?.release()
                surface = null
                return true
            }

            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
                if (prepared && !firstFrameVisible) {
                    firstFrameVisible = true
                    mainHandler.removeCallbacks(firstFrameFallbackRunnable)
                    setLoadingVisible(false)
                }
            }
        }
        addView(
            textureView,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        addView(
            fallbackView,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        addView(
            overlayView,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        addView(
            loadingView,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        setLoadingVisible(true)
    }

    fun setCropVideo(
        pathName: String,
        targetAspectRatio: Double,
        centerX: Double,
        centerY: Double,
        userScale: Double,
        initialFrameMs: Long,
        fallbackBitmap: Bitmap?,
        fallbackWidth: Int,
        fallbackHeight: Int
    ) {
        val sourceChanged = this.pathName != null && this.pathName != pathName
        if (sourceChanged) {
            releasePlayer()
        }
        this.pathName = pathName
        this.targetAspectRatio = clampTargetAspectRatio(targetAspectRatio.toFloat())
        this.centerX = clampRatio(centerX.toFloat())
        this.centerY = clampRatio(centerY.toFloat())
        this.userScale = clampUserScale(userScale.toFloat())
        this.pendingSeekMs = max(0L, initialFrameMs)
        this.currentFrameMs = this.pendingSeekMs
        this.videoWidth = max(1, fallbackWidth)
        this.videoHeight = max(1, fallbackHeight)
        this.fallbackView.setFallbackBitmap(fallbackBitmap)
        mainHandler.removeCallbacks(firstFrameFallbackRunnable)
        updateGeometry()
        invalidateCrop()
        if (player != null) {
            if (prepared) {
                firstFrameVisible = true
                setLoadingVisible(false)
                seekTo(this.pendingSeekMs)
            } else {
                firstFrameVisible = false
                setLoadingVisible(true)
            }
            return
        }
        firstFrameVisible = false
        setLoadingVisible(true)
        textureView.surfaceTexture?.let { preparePlayer(it) }
    }

    fun setAccentBackground(background: ThingBackground?) {
        loadingView.setAccentBackground(background)
    }

    override fun getCropCenterX(): Double = centerX.toDouble()

    override fun getCropCenterY(): Double = centerY.toDouble()

    override fun getCropUserScale(): Double = userScale.toDouble()

    override fun getTargetAspectRatio(): Double = targetAspectRatio.toDouble()

    override fun setTargetAspectRatio(targetAspectRatio: Double) {
        this.targetAspectRatio = clampTargetAspectRatio(targetAspectRatio.toFloat())
        updateGeometry()
        clampCropCenter()
        invalidateCrop()
    }

    fun getCurrentFrameMs(): Long {
        if (prepared) {
            try {
                player?.currentPosition?.toLong()?.let {
                    currentFrameMs = clampFrameMs(it)
                }
            } catch (_: IllegalStateException) {
            }
        }
        return clampFrameMs(currentFrameMs)
    }

    fun play() {
        pendingPlay = true
        val mediaPlayer = player ?: return
        if (!prepared) return
        if (durationMs > 0 && mediaPlayer.currentPosition >= getMaxRenderableFrameMs()) {
            seekTo(0L)
        }
        try {
            mediaPlayer.start()
            playing = true
            startPositionTicker()
            dispatchPlayingChanged(true)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Failed to start crop editor video playback", e)
            pendingPlay = false
            playing = false
            dispatchPlayingChanged(false)
        }
    }

    fun pause() {
        pendingPlay = false
        playing = false
        val mediaPlayer = player
        if (prepared && mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying) mediaPlayer.pause()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Failed to pause crop editor video playback", e)
            }
        }
        stopPositionTicker()
        notifyCurrentPosition()
        dispatchPlayingChanged(false)
    }

    fun stopPlayback() {
        pendingPlay = false
        playing = false
        val mediaPlayer = player
        if (prepared && mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying) mediaPlayer.pause()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Failed to stop crop editor video playback", e)
            }
        }
        seekTo(0L)
        stopPositionTicker()
        notifyCurrentPosition()
        dispatchPlayingChanged(false)
    }

    fun seekTo(frameMs: Long) {
        val clamped = clampFrameMs(frameMs)
        pendingSeekMs = clamped
        currentFrameMs = clamped
        val mediaPlayer = player
        if (!prepared || mediaPlayer == null) {
            onPositionChanged?.invoke(clamped)
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                mediaPlayer.seekTo(clamped, MediaPlayer.SEEK_CLOSEST)
            } else {
                mediaPlayer.seekTo(clamped.toInt())
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Failed to seek crop editor video playback", e)
        }
        onPositionChanged?.invoke(clamped)
    }

    fun isPlaying(): Boolean {
        return playing
    }

    fun release() {
        released = true
        pendingPlay = false
        playing = false
        stopPositionTicker()
        onPositionChanged = null
        onPlayingChanged = null
        releasePlayer()
        surface?.release()
        surface = null
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = true

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

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        updateGeometry()
        invalidateOutline()
        applyVideoTransform()
        fallbackView.layout(0, 0, width, height)
        overlayView.layout(0, 0, width, height)
        loadingView.layout(0, 0, width, height)
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    private fun preparePlayer(surfaceTexture: SurfaceTexture) {
        if (released || player != null) return
        val source = pathName ?: return
        surface?.release()
        surface = Surface(surfaceTexture)
        try {
            val mediaPlayer = MediaPlayer()
            player = mediaPlayer
            mediaPlayer.setDataSource(source)
            mediaPlayer.setSurface(surface)
            mediaPlayer.setOnPreparedListener {
                prepared = true
                durationMs = max(0, it.duration)
                updateVideoSize(it.videoWidth, it.videoHeight)
                seekTo(pendingSeekMs)
                if (pendingPlay) play() else dispatchPlayingChanged(false)
            }
            mediaPlayer.setOnVideoSizeChangedListener { _, width, height ->
                updateVideoSize(width, height)
            }
            mediaPlayer.setOnSeekCompleteListener {
                notifyCurrentPosition()
                scheduleFirstFrameFallback()
            }
            mediaPlayer.setOnCompletionListener {
                finishPlayback()
            }
            mediaPlayer.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "Crop editor video playback error: what=$what extra=$extra")
                pendingPlay = false
                playing = false
                prepared = false
                firstFrameVisible = false
                setLoadingVisible(false)
                stopPositionTicker()
                dispatchPlayingChanged(false)
                true
            }
            mediaPlayer.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to prepare crop editor video playback: $source", e)
            releasePlayer()
            dispatchPlayingChanged(false)
        }
    }

    private fun updateVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        videoWidth = width
        videoHeight = height
        textureView.surfaceTexture?.setDefaultBufferSize(width, height)
        updateGeometry()
        invalidateCrop()
    }

    private fun releasePlayer() {
        stopPositionTicker()
        prepared = false
        playing = false
        player?.let {
            try {
                it.setSurface(null)
            } catch (_: Exception) {
            }
            try {
                it.release()
            } catch (_: Exception) {
            }
        }
        player = null
        mainHandler.removeCallbacks(firstFrameFallbackRunnable)
    }

    private fun startPositionTicker() {
        mainHandler.removeCallbacks(positionRunnable)
        notifyCurrentPosition()
        mainHandler.postDelayed(positionRunnable, POSITION_TICK_MS)
    }

    private fun stopPositionTicker() {
        mainHandler.removeCallbacks(positionRunnable)
    }

    private fun notifyCurrentPosition() {
        currentFrameMs = clampFrameMs(
            if (prepared) {
                try {
                    player?.currentPosition?.toLong() ?: pendingSeekMs
                } catch (_: IllegalStateException) {
                    pendingSeekMs
                }
            } else {
                pendingSeekMs
            }
        )
        onPositionChanged?.invoke(currentFrameMs)
    }

    private fun finishPlaybackIfNeeded(): Boolean {
        if (!playing || !prepared) return false
        val mediaPlayer = player ?: return false
        val position = try {
            mediaPlayer.currentPosition
        } catch (_: IllegalStateException) {
            return false
        }
        val actualPlaying = try {
            mediaPlayer.isPlaying
        } catch (_: IllegalStateException) {
            false
        }
        val endPosition = getMaxRenderableFrameMs()
        val nearEnd = durationMs > 0 && position >= max(0, durationMs - 120)
        val stoppedAtEnd = durationMs > 0 && !actualPlaying &&
                position >= max(0, durationMs - 500)
        if (!nearEnd && !stoppedAtEnd) return false
        finishPlayback(max(0L, min(endPosition.toLong(), position.toLong())))
        return true
    }

    private fun finishPlayback(
        finalFrameMs: Long = if (durationMs > 0) {
            getMaxRenderableFrameMs()
        } else {
            currentFrameMs
        }
    ) {
        pendingPlay = false
        playing = false
        stopPositionTicker()
        currentFrameMs = clampFrameMs(finalFrameMs)
        onPositionChanged?.invoke(currentFrameMs)
        dispatchPlayingChanged(false)
    }

    private fun dispatchPlayingChanged(isPlaying: Boolean) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onPlayingChanged?.invoke(isPlaying)
        } else {
            mainHandler.post {
                onPlayingChanged?.invoke(isPlaying)
            }
        }
    }

    private fun scheduleFirstFrameFallback() {
        if (firstFrameVisible || !prepared) return
        mainHandler.removeCallbacks(firstFrameFallbackRunnable)
        mainHandler.postDelayed(firstFrameFallbackRunnable, FIRST_FRAME_FALLBACK_MS)
    }

    private fun panBy(dx: Float, dy: Float) {
        updateGeometry()
        if (imageScale <= 0f) return

        val scaledW = videoWidth * imageScale
        val scaledH = videoHeight * imageScale
        val newLeft = clampImageOffset(imageLeft + dx, cropRect.right - scaledW, cropRect.left)
        val newTop = clampImageOffset(imageTop + dy, cropRect.bottom - scaledH, cropRect.top)
        centerX = clampRatio((cropRect.centerX() - newLeft) / scaledW)
        centerY = clampRatio((cropRect.centerY() - newTop) / scaledH)
        invalidateCrop()
    }

    private fun updateGeometry() {
        if (width <= 0 || height <= 0) return

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

        val sourceW = max(1, videoWidth).toFloat()
        val sourceH = max(1, videoHeight).toFloat()
        val coverScale = max(cropRect.width() / sourceW, cropRect.height() / sourceH)
        imageScale = coverScale * userScale
        val scaledW = sourceW * imageScale
        val scaledH = sourceH * imageScale
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
        previewPath.addRoundRect(previewRect, cornerRadius, cornerRadius, Path.Direction.CW)
        cropPath.reset()
        cropPath.addRoundRect(cropRect, cornerRadius, cornerRadius, Path.Direction.CW)
        overlayPath.set(previewPath)
        overlayPath.op(cropPath, Path.Op.DIFFERENCE)
        fullPath.reset()
        fullPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        outsidePreviewPath.set(fullPath)
        outsidePreviewPath.op(previewPath, Path.Op.DIFFERENCE)
    }

    private fun applyVideoTransform() {
        if (width <= 0 || height <= 0 || imageScale <= 0f) return

        val scaledW = max(1f, max(1, videoWidth) * imageScale)
        val scaledH = max(1f, max(1, videoHeight) * imageScale)
        val left = imageLeft.roundToInt()
        val top = imageTop.roundToInt()
        val right = (imageLeft + scaledW).roundToInt()
        val bottom = (imageTop + scaledH).roundToInt()
        if (
            textureView.left != left ||
            textureView.top != top ||
            textureView.right != right ||
            textureView.bottom != bottom
        ) {
            textureView.layout(left, top, right, bottom)
        }
        textureMatrix.reset()
        textureView.setTransform(textureMatrix)
        textureView.invalidate()
    }

    private fun clampCropCenter() {
        if (cropRect.isEmpty) updateGeometry()
        val scaledW = max(1, videoWidth) * imageScale
        val scaledH = max(1, videoHeight) * imageScale
        if (scaledW <= 0f || scaledH <= 0f) return
        centerX = clampRatio((cropRect.centerX() - imageLeft) / scaledW)
        centerY = clampRatio((cropRect.centerY() - imageTop) / scaledH)
    }

    private fun invalidateCrop() {
        applyVideoTransform()
        fallbackView.invalidate()
        overlayView.invalidate()
        loadingView.invalidate()
    }

    private fun setLoadingVisible(visible: Boolean) {
        loadingVisible = visible
        if (!visible) {
            mainHandler.removeCallbacks(firstFrameFallbackRunnable)
        }
        val hasFallback = fallbackView.hasFallbackBitmap()
        textureView.alpha = if (visible) 0f else 1f
        fallbackView.visibility = if (visible && hasFallback) View.VISIBLE else View.GONE
        overlayView.visibility = if (!visible || hasFallback) View.VISIBLE else View.INVISIBLE
        loadingView.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            fallbackView.invalidate()
            overlayView.invalidate()
            loadingView.invalidate()
        }
    }

    private fun showFallbackPreviewOnly() {
        loadingVisible = false
        mainHandler.removeCallbacks(firstFrameFallbackRunnable)
        textureView.alpha = 0f
        fallbackView.visibility = View.VISIBLE
        overlayView.visibility = View.VISIBLE
        loadingView.visibility = View.GONE
        fallbackView.invalidate()
        overlayView.invalidate()
    }

    private fun clampFrameMs(value: Long): Long {
        return max(0L, min(getMaxRenderableFrameMs(), value))
    }

    private fun getMaxRenderableFrameMs(): Long {
        if (durationMs <= 0) return Long.MAX_VALUE
        return max(0, durationMs - VIDEO_END_FRAME_GUARD_MS).toLong()
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

    private inner class OverlayView(context: Context) : View(context) {
        init {
            isClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            updateGeometry()
            canvas.drawPath(outsidePreviewPath, surfacePaint)
            canvas.drawPath(overlayPath, overlayPaint)
            canvas.drawRoundRect(cropRect, cornerRadius, cornerRadius, framePaint)
        }
    }

    private inner class FallbackFrameView(context: Context) : View(context) {

        private val bitmapMatrix = Matrix()
        private var fallbackBitmap: Bitmap? = null

        init {
            isClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.GONE
        }

        fun setFallbackBitmap(bitmap: Bitmap?) {
            fallbackBitmap = bitmap
            visibility = if (loadingVisible && bitmap != null) View.VISIBLE else View.GONE
            invalidate()
        }

        fun hasFallbackBitmap(): Boolean {
            return fallbackBitmap != null
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val bitmap = fallbackBitmap ?: return
            updateGeometry()
            bitmapMatrix.reset()
            bitmapMatrix.setScale(imageScale, imageScale)
            bitmapMatrix.postTranslate(imageLeft, imageTop)

            val saved = canvas.save()
            canvas.clipPath(previewPath)
            canvas.drawBitmap(bitmap, bitmapMatrix, null)
            canvas.restoreToCount(saved)
        }
    }

    private inner class LoadingView(context: Context) : View(context) {

        private val indicatorBounds = RectF()
        private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = resources.displayMetrics.density * 3f
        }
        private var accentBackground: ThingBackground? = null

        init {
            isClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun setAccentBackground(background: ThingBackground?) {
            accentBackground = background
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            updateGeometry()
            if (!fallbackView.hasFallbackBitmap()) {
                canvas.drawPath(outsidePreviewPath, surfacePaint)
                canvas.drawPath(previewPath, surfacePaint)
            }

            val size = resources.displayMetrics.density * 32f
            val cx = previewRect.centerX()
            val cy = previewRect.centerY()
            indicatorBounds.set(
                cx - size / 2f,
                cy - size / 2f,
                cx + size / 2f,
                cy + size / 2f
            )
            bindIndicatorPaint()
            val rotation = (SystemClock.uptimeMillis() % 1200L) * 360f / 1200f
            canvas.drawArc(indicatorBounds, rotation - 90f, 280f, false, indicatorPaint)

            if (loadingVisible) {
                postInvalidateOnAnimation()
            }
        }

        private fun bindIndicatorPaint() {
            val bg = accentBackground ?: ThingBackground.pure(
                ContextCompat.getColor(context, R.color.app_accent)
            )
            if (bg.mode == ThingBackground.Mode.GRADIENT) {
                indicatorPaint.shader = SweepGradient(
                    indicatorBounds.centerX(),
                    indicatorBounds.centerY(),
                    intArrayOf(bg.color, bg.endColor, bg.color),
                    floatArrayOf(0f, 0.75f, 1f)
                )
            } else {
                indicatorPaint.shader = null
                indicatorPaint.color = bg.color
            }
        }
    }
}
