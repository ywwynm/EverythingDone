package com.ywwynm.everythingdone.views.reveal

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.TypedArray
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.util.AttributeSet
import android.view.View

import com.ywwynm.everythingdone.R

import java.util.ArrayList
import java.util.Arrays
import java.util.Random
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A view that animates a line traveling along the screen border.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * A bright "shining" segment travels along the path, followed by a dimmer
 * "ordinary" segment, creating a border-lighting effect.
 *
 * Ported from Everything-Android's ShiningBorder (Kotlin) to Java.
 */
open class ShiningBorder : View {

    private val mPath: Path = Path()
    private val mPaint: Paint
    private val mParticlePaint: Paint

    private var mPathFrame: PathFrame? = null
    private var mPathAssigned: Boolean = false
    private var mReassignBeforePlay: Boolean = false

    private var mBackgroundPoints: FloatArray? = null
    private var mShiningPoints: FloatArray? = null

    private var mProgressAnimator: ValueAnimator? = null
    private var mForceStop: Boolean = false

    // --- configurable properties ---

    private var mStrokeWidth: Float = dpToPx(2f)
    private var mCornerRadius: Float = 0f
    private var mAnimationDuration: Long = 1200
    private var mRepeatAnimation: Boolean = false
    private var mAnimationDirection: Int = DIRECTION_CW

    private var mShiningColor: Int = 0
    private var mOrdinaryColor: Int = 0
    private var mRemainOrdinaryPath: Boolean = false

    // --- particles ---
    private val mParticles: MutableList<Particle> = ArrayList()
    private val mRandom: Random = Random()
    private var mMaxParticles: Int = 160
    private var mParticleBaseSize: Float = dpToPx(2.2f)
    private var mLastSpawnProgress: Float = -1f
    private var mNextSpawnThreshold: Float = 0f

    // --- callbacks ---

    interface OnAnimationStartListener {
        fun onAnimationStart(border: ShiningBorder)
    }

    interface OnAnimationEndListener {
        fun onAnimationEnd(border: ShiningBorder)
    }

    interface OnProgressUpdateListener {
        fun onProgressUpdate(border: ShiningBorder, progress: Float)
    }

    private var mOnStartListener: OnAnimationStartListener? = null
    private var mOnEndListener: OnAnimationEndListener? = null
    private var mOnProgressListener: OnProgressUpdateListener? = null

    // --- constructors ---

    constructor(context: Context) : this(context, null)

    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)

    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(context, attrs, defStyle) {

        if (attrs != null) {
            val a: TypedArray = context.obtainStyledAttributes(attrs, R.styleable.ShiningBorder)!!
            mStrokeWidth = a.getDimension(
                    R.styleable.ShiningBorder_strokeWidth, mStrokeWidth)
            mCornerRadius = a.getDimension(
                    R.styleable.ShiningBorder_strokeCornerRadius, mCornerRadius)
            mAnimationDuration = a.getInteger(
                    R.styleable.ShiningBorder_animationDuration, mAnimationDuration.toInt()).toLong()
            mRepeatAnimation = a.getBoolean(
                    R.styleable.ShiningBorder_repeatAnimation, mRepeatAnimation)
            mAnimationDirection = a.getInt(
                    R.styleable.ShiningBorder_animationDirection, mAnimationDirection)
            mRemainOrdinaryPath = a.getBoolean(
                    R.styleable.ShiningBorder_remainOrdinaryPath, mRemainOrdinaryPath)
            a.recycle()
        }

        mPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        mPaint.setStyle(Paint.Style.STROKE)
        mPaint.setStrokeCap(Paint.Cap.ROUND)
        mPaint.setStrokeWidth(mStrokeWidth)

        mParticlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        mParticlePaint.setStyle(Paint.Style.FILL)
    }

    // --- getters (used by callers that need to temporarily override a value) ---

    fun getStrokeWidth(): Float = mStrokeWidth
    fun getCornerRadius(): Float = mCornerRadius
    fun getAnimationDuration(): Long = mAnimationDuration
    fun getRemainOrdinaryPath(): Boolean = mRemainOrdinaryPath
    fun getAnimationDirection(): Int = mAnimationDirection

    // --- setters ---

    fun setStrokeWidth(px: Float) {
        mStrokeWidth = px
        mPaint.setStrokeWidth(px)
        if (mPathAssigned) {
            assignPathAndFrame(getLeft(), getTop(), getRight(), getBottom())
        }
    }

    fun setCornerRadius(px: Float) {
        mCornerRadius = px
        if (mPathAssigned) {
            assignPathAndFrame(getLeft(), getTop(), getRight(), getBottom())
        }
    }

    fun setAnimationDuration(ms: Long) {
        mAnimationDuration = ms
    }

    fun setRepeatAnimation(repeat: Boolean) {
        mRepeatAnimation = repeat
    }

    fun setAnimationDirection(direction: Int) {
        mAnimationDirection = direction
        if (mPathAssigned) {
            assignPathAndFrame(getLeft(), getTop(), getRight(), getBottom())
        }
    }

    fun setShiningColor(color: Int) {
        mShiningColor = color
    }

    fun setOrdinaryColor(color: Int) {
        mOrdinaryColor = color
    }

    fun setRemainOrdinaryPath(remain: Boolean) {
        mRemainOrdinaryPath = remain
    }

    fun setMaxParticles(maxParticles: Int) {
        mMaxParticles = maxParticles
    }

    fun setParticleBaseSize(px: Float) {
        mParticleBaseSize = px
    }

    fun setOnAnimationStartListener(listener: OnAnimationStartListener?) {
        mOnStartListener = listener
    }

    fun setOnAnimationEndListener(listener: OnAnimationEndListener?) {
        mOnEndListener = listener
    }

    fun setOnProgressUpdateListener(listener: OnProgressUpdateListener?) {
        mOnProgressListener = listener
    }

    // --- path management ---

    fun assignPathAndFrame(pathLeft: Int, pathTop: Int, pathRight: Int, pathBottom: Int) {
        mPath.reset()
        val halfStroke: Float = mStrokeWidth / 2f
        val l: Float = pathLeft + halfStroke
        val t: Float = pathTop + halfStroke
        val r: Float = pathRight - halfStroke
        val b: Float = pathBottom - halfStroke

        if (mAnimationDirection == DIRECTION_CW) {
            addRoundRectCW(mPath, l, t, r, b, mCornerRadius)
        } else {
            addRoundRectCCW(mPath, l, t, r, b, mCornerRadius)
        }
        mPathFrame = PathFrame(mPath)
    }

    fun assignPathAndFrame() {
        assignPathAndFrame(getLeft(), getTop(), getRight(), getBottom())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        mReassignBeforePlay = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!mPathAssigned) {
            assignPathAndFrame()
            mPathAssigned = true
        }

        mPaint.setColor(mOrdinaryColor)
        if (mBackgroundPoints != null) {
            canvas.drawPoints(mBackgroundPoints!!, mPaint)
        }

        mPaint.setColor(mShiningColor)
        if (mShiningPoints != null) {
            canvas.drawPoints(mShiningPoints!!, mPaint)
        }

        // Particles
        if (!mParticles.isEmpty()) {
            drawParticles(canvas)
        }
    }

    // --- animation ---

    fun startAnimation() {
        if (mReassignBeforePlay) {
            assignPathAndFrame()
            mReassignBeforePlay = false
        }
        stopAnimation()
        mForceStop = false
        mParticles.clear()
        mLastSpawnProgress = -1f

        mOnStartListener?.onAnimationStart(this)

        startProgressAnimation()
    }

    fun stopAnimation() {
        mForceStop = true
        val animator: ValueAnimator? = mProgressAnimator
        if (animator != null && animator.isRunning()) {
            animator.cancel()
        }
        mParticles.clear()
    }

    fun resetTrace() {
        mShiningPoints = mPathFrame!!.getFrame(0f, 0f)
        mBackgroundPoints = mPathFrame!!.getFrame(0f, 0f)
        mParticles.clear()
        mLastSpawnProgress = -1f
        invalidate()
    }

    private fun startProgressAnimation() {
        val animator: ValueAnimator = ValueAnimator.ofFloat(-0.6f, 1f)
        animator.setDuration(mAnimationDuration)
        mProgressAnimator = animator

        animator.addUpdateListener { anim ->
            val p: Float = anim.getAnimatedValue() as Float

            var shiningHead: Float = (0.6f + p) * 2.5f
            var shiningTail: Float = 0.4f + p
            var ordinaryHead: Float = shiningHead
            var ordinaryTail: Float = if (mRemainOrdinaryPath) 0f else p

            if (shiningTail > 0.4f) {
                shiningTail = (0.4f + p - 0.4f) * 1.6f + 0.4f
            }
            if (ordinaryHead > 0.6f) {
                ordinaryHead = ((0.6f + p) * 2.5f - 0.6f) * 0.4f + 0.6f
                shiningHead = ordinaryHead
            }

            if (shiningTail < 0f) shiningTail = 0f
            if (ordinaryTail < 0f) ordinaryTail = 0f
            if (shiningHead > 1f) shiningHead = 1f
            if (ordinaryHead > 1f) ordinaryHead = 1f

            mShiningPoints = mPathFrame!!.getFrame(shiningTail, shiningHead)
            mBackgroundPoints = mPathFrame!!.getFrame(ordinaryTail, ordinaryHead)

            // Spawn particles along the shining segment with random distance threshold
            if (shiningHead > 0f && mPathFrame != null) {
                if (mLastSpawnProgress < 0f) {
                    mLastSpawnProgress = shiningHead
                    mNextSpawnThreshold = 0.005f + mRandom.nextFloat() * 0.012f
                }
                if (shiningHead - mLastSpawnProgress >= mNextSpawnThreshold) {
                    mLastSpawnProgress = shiningHead
                    mNextSpawnThreshold = 0.005f + mRandom.nextFloat() * 0.015f
                    spawnParticlesAlongSegment(shiningTail, shiningHead, mShiningColor)
                }
            }
            updateParticles()
        }

        animator.addUpdateListener { anim ->
            val p: Float = anim.getAnimatedValue() as Float
            val k: Float = 1f / 1.6f
            val progress: Float = k * p + 1f - k
            mOnProgressListener?.onProgressUpdate(this, progress)
            invalidate()
        }

        animator.addListener(object : android.animation.Animator.AnimatorListener {
            override fun onAnimationStart(a: android.animation.Animator) {}
            override fun onAnimationCancel(a: android.animation.Animator) {}

            override fun onAnimationEnd(a: android.animation.Animator) {
                invalidate()
                mOnEndListener?.onAnimationEnd(this@ShiningBorder)
                if (mRepeatAnimation && !mForceStop) {
                    mParticles.clear()
                    startAnimation()
                }
            }

            override fun onAnimationRepeat(a: android.animation.Animator) {}
        })

        animator.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }

    // --- particles ---

    private fun spawnParticlesAlongSegment(tail: Float, head: Float, baseColor: Int) {
        // Scatter 1~3 particles randomly along the front part of the shining segment.
        // Use squared distribution so they skew toward the head.
        val segStart: Float = max(0f, head - 0.12f)
        val count: Int = mRandom.nextInt(3) + 1 // 1~3
        var i: Int = 0
        while (i < count && mParticles.size < mMaxParticles) {
            val r: Float = mRandom.nextFloat()
            val t: Float = segStart + (head - segStart) * (1f - r * r)
            val pos: FloatArray? = mPathFrame!!.getPointAtProgress(t)
            if (pos == null) { i++; continue }
            val focus: Boolean = (r < 0.15f)
            val ox: Float = pos[0] + (mRandom.nextFloat() - 0.5f) * mStrokeWidth * (if (focus) 3.5f else 12.0f)
            val oy: Float = pos[1] + (mRandom.nextFloat() - 0.5f) * mStrokeWidth * (if (focus) 3.5f else 12.0f)
            spawnSingleParticle(ox, oy, baseColor, focus)
            i++
        }
    }

    private fun spawnSingleParticle(x: Float, y: Float, baseColor: Int, isFocus: Boolean) {
        val p: Particle = Particle()
        p.x = x
        p.y = y
        // initial small random velocity for jittery motion
        p.vx = (mRandom.nextFloat() - 0.5f) * dpToPx(2.0f)
        p.vy = (mRandom.nextFloat() - 0.5f) * dpToPx(2.0f)

        if (isFocus) {
            p.size = mParticleBaseSize * (1.3f + mRandom.nextFloat() * 1.0f)
            p.maxLife = (28 + mRandom.nextInt(30)).toFloat()
            p.alphaRatio = 0.80f + mRandom.nextFloat() * 0.20f
            // Brighter variant of base color
            p.color = varyColor(baseColor, 0.08f + mRandom.nextFloat() * 0.18f)
        } else {
            p.size = mParticleBaseSize * (0.35f + mRandom.nextFloat() * 1.0f)
            p.maxLife = (22 + mRandom.nextInt(36)).toFloat()
            p.alphaRatio = 0.40f + mRandom.nextFloat() * 0.50f
            // Random lightness variation
            p.color = varyColor(baseColor, -0.12f + mRandom.nextFloat() * 0.30f)
        }

        p.life = 0f
        p.isFocus = isFocus
        mParticles.add(p)
    }

    private fun updateParticles() {
        val it: MutableIterator<Particle> = mParticles.iterator()
        while (it.hasNext()) {
            val p: Particle = it.next()
            // Brownian motion: random acceleration
            p.vx += (mRandom.nextFloat() - 0.5f) * dpToPx(1.2f)
            p.vy += (mRandom.nextFloat() - 0.5f) * dpToPx(1.2f)
            // Dampen so they jitter in place rather than fly away
            p.vx *= 0.94f
            p.vy *= 0.94f
            p.x += p.vx
            p.y += p.vy
            p.life += 1f
            if (p.life >= p.maxLife) {
                it.remove()
            }
        }
    }

    private fun drawParticles(canvas: Canvas) {
        for (p in mParticles) {
            val lifeRatio: Float = p.life / p.maxLife

            // Fade in quickly, then ease-out fade
            var alpha: Float
            if (lifeRatio < 0.15f) {
                alpha = lifeRatio / 0.15f
            } else {
                val t: Float = (lifeRatio - 0.15f) / 0.85f
                alpha = 1f - t * t
            }
            alpha *= p.alphaRatio
            if (alpha <= 0.01f) continue

            val a: Int = (alpha * 255).toInt()
            val rgb: Int = p.color and 0x00FFFFFF

            // Breathing size effect
            val breathing: Float = 1f + 0.12f * sin(p.life * 0.6f + p.x * 0.1f)
            val drawSize: Float = p.size * breathing

            // Glow layer (soft, larger)
            val glowAlpha: Int = if (p.isFocus) (alpha * 70).toInt() else (alpha * 50).toInt()
            mParticlePaint.setColor(rgb or (glowAlpha shl 24))
            val glowRadius: Float = if (p.isFocus) drawSize * 3.0f else drawSize * 2.2f
            canvas.drawCircle(p.x, p.y, glowRadius, mParticlePaint)

            // Core layer (bright, smaller)
            mParticlePaint.setColor(rgb or (a shl 24))
            val coreRadius: Float = if (p.isFocus) drawSize * 1.2f else drawSize * (0.55f + 0.45f * alpha)
            canvas.drawCircle(p.x, p.y, coreRadius, mParticlePaint)
        }
    }

    /**
     * Vary the lightness of a color using HSL space.
     * Positive delta makes it brighter, negative darker.
     */
    private fun varyColor(baseColor: Int, lightnessDelta: Float): Int {
        val hsl: FloatArray = FloatArray(3)
        androidx.core.graphics.ColorUtils.colorToHSL(baseColor, hsl)
        hsl[2] = max(0f, min(1f, hsl[2] + lightnessDelta))
        return androidx.core.graphics.ColorUtils.HSLToColor(hsl)
    }

    private class Particle {
        var x: Float = 0f
        var y: Float = 0f
        var vx: Float = 0f
        var vy: Float = 0f
        var size: Float = 0f
        var life: Float = 0f
        var maxLife: Float = 0f
        var color: Int = 0
        var alphaRatio: Float = 0f
        var isFocus: Boolean = false
    }

    // --- PathFrame: discretizes a Path into an array of points ---

    private class PathFrame(path: Path) {
        private val mData: FloatArray
        private val mPointCount: Int

        init {
            val pm: PathMeasure = PathMeasure(path, false)
            val length: Float = pm.getLength()
            val rawCount: Int = length.toInt() + 1
            val rawData: FloatArray = FloatArray(rawCount * 2)
            val pos: FloatArray = FloatArray(2)
            var index: Int = 0
            for (i in 0 until rawCount) {
                val distance: Float = i * length / (rawCount - 1)
                pm.getPosTan(distance, pos, null)
                rawData[index] = pos[0]
                rawData[index + 1] = pos[1]
                index += 2
            }
            mData = rawData
            mPointCount = mData.size
        }

        fun getFrame(start: Float, end: Float): FloatArray? {
            var si: Int = (mPointCount * start).toInt()
            var ei: Int = (mPointCount * end).toInt()
            if (si % 2 != 0) si--
            if (ei % 2 != 0) ei++
            if (si < ei) {
                return Arrays.copyOfRange(mData, si, ei)
            }
            return null
        }

        fun getPointAtProgress(progress: Float): FloatArray? {
            val totalPoints: Int = mPointCount / 2
            if (totalPoints <= 0) return null
            var pointIndex: Int = (totalPoints * progress).toInt()
            if (pointIndex < 0) pointIndex = 0
            if (pointIndex >= totalPoints) pointIndex = totalPoints - 1
            val dataIndex: Int = pointIndex * 2
            return floatArrayOf(mData[dataIndex], mData[dataIndex + 1])
        }
    }

    companion object {
        const val DIRECTION_CW: Int = 0
        const val DIRECTION_CCW: Int = 1

        internal fun addRoundRectCCW(path: Path, left: Float, top: Float, right: Float, bottom: Float, radius: Float) {
            val r: Float = max(radius, 0f)
            val w: Float = right - left
            val h: Float = bottom - top
            val rw: Float = w - 2f * r
            val rh: Float = h - 2f * r

            path.moveTo(right - r, top)
            path.rLineTo(-rw, 0f)
            path.arcTo(left, top, left + 2f * r, top + 2f * r, 270f, -90f, false)
            path.rLineTo(0f, rh)
            path.arcTo(left, bottom - 2f * r, left + 2f * r, bottom, 180f, -90f, false)
            path.rLineTo(rw, 0f)
            path.arcTo(right - 2f * r, bottom - 2f * r, right, bottom, 90f, -90f, false)
            path.rLineTo(0f, -rh + r)
            path.arcTo(right - 2f * r, top, right, top + 2f * r, 0f, -90f, false)
        }

        internal fun addRoundRectCW(path: Path, left: Float, top: Float, right: Float, bottom: Float, radius: Float) {
            val r: Float = max(radius, 0f)
            val w: Float = right - left
            val h: Float = bottom - top
            val rw: Float = w - 2f * r
            val rh: Float = h - 2f * r

            path.moveTo(left, bottom - r)
            path.rLineTo(0f, -rh + r)
            path.arcTo(left, top, left + 2f * r, top + 2f * r, 180f, 90f, false)
            path.rLineTo(rw, 0f)
            path.arcTo(right - 2f * r, top, right, top + 2f * r, 270f, 90f, false)
            path.rLineTo(0f, rh)
            path.arcTo(right - 2f * r, bottom - 2f * r, right, bottom, 0f, 90f, false)
            path.rLineTo(-rw + r, 0f)
            path.arcTo(left, bottom - 2f * r, left + 2f * r, bottom, 90f, 90f, false)
        }

        private fun dpToPx(dp: Float): Float {
            return dp * android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_DIP, 1f,
                    android.content.res.Resources.getSystem()!!.getDisplayMetrics())
        }
    }
}
