package com.ywwynm.everythingdone.views.reveal;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.util.AttributeSet;
import android.view.View;

import com.ywwynm.everythingdone.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * A view that animates a line traveling along the screen border.
 * A bright "shining" segment travels along the path, followed by a dimmer
 * "ordinary" segment, creating a border-lighting effect.
 *
 * Ported from Everything-Android's ShiningBorder (Kotlin) to Java.
 */
public class ShiningBorder extends View {

    public static final int DIRECTION_CW  = 0;
    public static final int DIRECTION_CCW = 1;

    private final Path  mPath  = new Path();
    private final Paint mPaint;
    private final Paint mParticlePaint;

    private PathFrame mPathFrame;
    private boolean   mPathAssigned;
    private boolean   mReassignBeforePlay;

    private float[] mBackgroundPoints;
    private float[] mShiningPoints;

    private ValueAnimator mProgressAnimator;
    private boolean       mForceStop;

    // --- configurable properties ---

    private float   mStrokeWidth       = dpToPx(2);
    private float   mCornerRadius;
    private long    mAnimationDuration = 1200;
    private boolean mRepeatAnimation;
    private int     mAnimationDirection = DIRECTION_CW;

    private int     mShiningColor;
    private int     mOrdinaryColor;
    private boolean mRemainOrdinaryPath;

    // --- particles ---
    private final List<Particle> mParticles = new ArrayList<>();
    private final Random mRandom = new Random();
    private int     mMaxParticles = 160;
    private float   mParticleBaseSize = dpToPx(2.2f);
    private float   mLastSpawnProgress = -1f;
    private float   mNextSpawnThreshold;

    // --- callbacks ---

    public interface OnAnimationStartListener {
        void onAnimationStart(ShiningBorder border);
    }

    public interface OnAnimationEndListener {
        void onAnimationEnd(ShiningBorder border);
    }

    public interface OnProgressUpdateListener {
        void onProgressUpdate(ShiningBorder border, float progress);
    }

    private OnAnimationStartListener   mOnStartListener;
    private OnAnimationEndListener     mOnEndListener;
    private OnProgressUpdateListener   mOnProgressListener;

    // --- constructors ---

    public ShiningBorder(Context context) {
        this(context, null);
    }

    public ShiningBorder(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ShiningBorder(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.ShiningBorder);
            mStrokeWidth = a.getDimension(
                    R.styleable.ShiningBorder_strokeWidth, mStrokeWidth);
            mCornerRadius = a.getDimension(
                    R.styleable.ShiningBorder_strokeCornerRadius, mCornerRadius);
            mAnimationDuration = a.getInteger(
                    R.styleable.ShiningBorder_animationDuration, (int) mAnimationDuration);
            mRepeatAnimation = a.getBoolean(
                    R.styleable.ShiningBorder_repeatAnimation, mRepeatAnimation);
            mAnimationDirection = a.getInt(
                    R.styleable.ShiningBorder_animationDirection, mAnimationDirection);
            mRemainOrdinaryPath = a.getBoolean(
                    R.styleable.ShiningBorder_remainOrdinaryPath, mRemainOrdinaryPath);
            a.recycle();
        }

        mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        mPaint.setStrokeWidth(mStrokeWidth);

        mParticlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mParticlePaint.setStyle(Paint.Style.FILL);
    }

    // --- getters (used by callers that need to temporarily override a value) ---

    public float getStrokeWidth()         { return mStrokeWidth; }
    public float getCornerRadius()        { return mCornerRadius; }
    public long  getAnimationDuration()   { return mAnimationDuration; }
    public boolean getRemainOrdinaryPath(){ return mRemainOrdinaryPath; }
    public int   getAnimationDirection()  { return mAnimationDirection; }

    // --- setters ---

    public void setStrokeWidth(float px) {
        mStrokeWidth = px;
        mPaint.setStrokeWidth(px);
        if (mPathAssigned) {
            assignPathAndFrame(getLeft(), getTop(), getRight(), getBottom());
        }
    }

    public void setCornerRadius(float px) {
        mCornerRadius = px;
        if (mPathAssigned) {
            assignPathAndFrame(getLeft(), getTop(), getRight(), getBottom());
        }
    }

    public void setAnimationDuration(long ms) {
        mAnimationDuration = ms;
    }

    public void setRepeatAnimation(boolean repeat) {
        mRepeatAnimation = repeat;
    }

    public void setAnimationDirection(int direction) {
        mAnimationDirection = direction;
        if (mPathAssigned) {
            assignPathAndFrame(getLeft(), getTop(), getRight(), getBottom());
        }
    }

    public void setShiningColor(int color) {
        mShiningColor = color;
    }

    public void setOrdinaryColor(int color) {
        mOrdinaryColor = color;
    }

    public void setRemainOrdinaryPath(boolean remain) {
        mRemainOrdinaryPath = remain;
    }

    public void setMaxParticles(int maxParticles) {
        mMaxParticles = maxParticles;
    }

    public void setParticleBaseSize(float px) {
        mParticleBaseSize = px;
    }

    public void setOnAnimationStartListener(OnAnimationStartListener listener) {
        mOnStartListener = listener;
    }

    public void setOnAnimationEndListener(OnAnimationEndListener listener) {
        mOnEndListener = listener;
    }

    public void setOnProgressUpdateListener(OnProgressUpdateListener listener) {
        mOnProgressListener = listener;
    }

    // --- path management ---

    public void assignPathAndFrame(int pathLeft, int pathTop, int pathRight, int pathBottom) {
        mPath.reset();
        float halfStroke = mStrokeWidth / 2f;
        float l = pathLeft + halfStroke;
        float t = pathTop + halfStroke;
        float r = pathRight - halfStroke;
        float b = pathBottom - halfStroke;

        if (mAnimationDirection == DIRECTION_CW) {
            addRoundRectCW(mPath, l, t, r, b, mCornerRadius);
        } else {
            addRoundRectCCW(mPath, l, t, r, b, mCornerRadius);
        }
        mPathFrame = new PathFrame(mPath);
    }

    public void assignPathAndFrame() {
        assignPathAndFrame(getLeft(), getTop(), getRight(), getBottom());
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mReassignBeforePlay = true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (canvas == null) return;

        if (!mPathAssigned) {
            assignPathAndFrame();
            mPathAssigned = true;
        }

        mPaint.setColor(mOrdinaryColor);
        if (mBackgroundPoints != null) {
            canvas.drawPoints(mBackgroundPoints, mPaint);
        }

        mPaint.setColor(mShiningColor);
        if (mShiningPoints != null) {
            canvas.drawPoints(mShiningPoints, mPaint);
        }

        // Particles
        if (!mParticles.isEmpty()) {
            drawParticles(canvas);
        }
    }

    // --- animation ---

    public void startAnimation() {
        if (mReassignBeforePlay) {
            assignPathAndFrame();
            mReassignBeforePlay = false;
        }
        stopAnimation();
        mForceStop = false;
        mParticles.clear();
        mLastSpawnProgress = -1f;

        if (mOnStartListener != null) {
            mOnStartListener.onAnimationStart(this);
        }

        startProgressAnimation();
    }

    public void stopAnimation() {
        mForceStop = true;
        if (mProgressAnimator != null && mProgressAnimator.isRunning()) {
            mProgressAnimator.cancel();
        }
        mParticles.clear();
    }

    public void resetTrace() {
        mShiningPoints   = mPathFrame.getFrame(0f, 0f);
        mBackgroundPoints = mPathFrame.getFrame(0f, 0f);
        mParticles.clear();
        mLastSpawnProgress = -1f;
        invalidate();
    }

    private void startProgressAnimation() {
        mProgressAnimator = ValueAnimator.ofFloat(-0.6f, 1f);
        mProgressAnimator.setDuration(mAnimationDuration);

        mProgressAnimator.addUpdateListener(anim -> {
            float p = (float) anim.getAnimatedValue();

            float shiningHead  = (0.6f + p) * 2.5f;
            float shiningTail  = 0.4f + p;
            float ordinaryHead = shiningHead;
            float ordinaryTail = mRemainOrdinaryPath ? 0f : p;

            if (shiningTail > 0.4f) {
                shiningTail = (0.4f + p - 0.4f) * 1.6f + 0.4f;
            }
            if (ordinaryHead > 0.6f) {
                ordinaryHead = ((0.6f + p) * 2.5f - 0.6f) * 0.4f + 0.6f;
                shiningHead  = ordinaryHead;
            }

            if (shiningTail  < 0f) shiningTail  = 0f;
            if (ordinaryTail < 0f) ordinaryTail = 0f;
            if (shiningHead  > 1f) shiningHead  = 1f;
            if (ordinaryHead > 1f) ordinaryHead = 1f;

            mShiningPoints    = mPathFrame.getFrame(shiningTail,  shiningHead);
            mBackgroundPoints = mPathFrame.getFrame(ordinaryTail, ordinaryHead);

            // Spawn particles along the shining segment with random distance threshold
            if (shiningHead > 0f && mPathFrame != null) {
                if (mLastSpawnProgress < 0f) {
                    mLastSpawnProgress = shiningHead;
                    mNextSpawnThreshold = 0.005f + mRandom.nextFloat() * 0.012f;
                }
                if (shiningHead - mLastSpawnProgress >= mNextSpawnThreshold) {
                    mLastSpawnProgress = shiningHead;
                    mNextSpawnThreshold = 0.005f + mRandom.nextFloat() * 0.015f;
                    spawnParticlesAlongSegment(shiningTail, shiningHead, mShiningColor);
                }
            }
            updateParticles();
        });

        mProgressAnimator.addUpdateListener(anim -> {
            float p = (float) anim.getAnimatedValue();
            float k = 1f / 1.6f;
            float progress = k * p + 1f - k;
            if (mOnProgressListener != null) {
                mOnProgressListener.onProgressUpdate(this, progress);
            }
            invalidate();
        });

        mProgressAnimator.addListener(new android.animation.Animator.AnimatorListener() {
            @Override public void onAnimationStart(android.animation.Animator a) {}
            @Override public void onAnimationCancel(android.animation.Animator a) {}

            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                invalidate();
                if (mOnEndListener != null) {
                    mOnEndListener.onAnimationEnd(ShiningBorder.this);
                }
                if (mRepeatAnimation && !mForceStop) {
                    mParticles.clear();
                    startAnimation();
                }
            }

            @Override public void onAnimationRepeat(android.animation.Animator a) {}
        });

        mProgressAnimator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopAnimation();
    }

    // --- particles ---

    private void spawnParticlesAlongSegment(float tail, float head, int baseColor) {
        // Scatter 1~3 particles randomly along the front part of the shining segment.
        // Use squared distribution so they skew toward the head.
        float segStart = Math.max(0f, head - 0.12f);
        int count = mRandom.nextInt(3) + 1; // 1~3
        for (int i = 0; i < count && mParticles.size() < mMaxParticles; i++) {
            float r = mRandom.nextFloat();
            float t = segStart + (head - segStart) * (1f - r * r);
            float[] pos = mPathFrame.getPointAtProgress(t);
            if (pos == null) continue;
            boolean focus = (r < 0.15f);
            float ox = pos[0] + (mRandom.nextFloat() - 0.5f) * mStrokeWidth * (focus ? 3.5f : 12.0f);
            float oy = pos[1] + (mRandom.nextFloat() - 0.5f) * mStrokeWidth * (focus ? 3.5f : 12.0f);
            spawnSingleParticle(ox, oy, baseColor, focus);
        }
    }

    private void spawnSingleParticle(float x, float y, int baseColor, boolean isFocus) {
        Particle p = new Particle();
        p.x = x;
        p.y = y;
        // initial small random velocity for jittery motion
        p.vx = (mRandom.nextFloat() - 0.5f) * dpToPx(2.0f);
        p.vy = (mRandom.nextFloat() - 0.5f) * dpToPx(2.0f);

        if (isFocus) {
            p.size = mParticleBaseSize * (1.3f + mRandom.nextFloat() * 1.0f);
            p.maxLife = 28 + mRandom.nextInt(30);
            p.alphaRatio = 0.80f + mRandom.nextFloat() * 0.20f;
            // Brighter variant of base color
            p.color = varyColor(baseColor, 0.08f + mRandom.nextFloat() * 0.18f);
        } else {
            p.size = mParticleBaseSize * (0.35f + mRandom.nextFloat() * 1.0f);
            p.maxLife = 22 + mRandom.nextInt(36);
            p.alphaRatio = 0.40f + mRandom.nextFloat() * 0.50f;
            // Random lightness variation
            p.color = varyColor(baseColor, -0.12f + mRandom.nextFloat() * 0.30f);
        }

        p.life = 0;
        p.isFocus = isFocus;
        mParticles.add(p);
    }

    private void updateParticles() {
        Iterator<Particle> it = mParticles.iterator();
        while (it.hasNext()) {
            Particle p = it.next();
            // Brownian motion: random acceleration
            p.vx += (mRandom.nextFloat() - 0.5f) * dpToPx(1.2f);
            p.vy += (mRandom.nextFloat() - 0.5f) * dpToPx(1.2f);
            // Dampen so they jitter in place rather than fly away
            p.vx *= 0.94f;
            p.vy *= 0.94f;
            p.x += p.vx;
            p.y += p.vy;
            p.life += 1;
            if (p.life >= p.maxLife) {
                it.remove();
            }
        }
    }

    private void drawParticles(Canvas canvas) {
        for (Particle p : mParticles) {
            float lifeRatio = p.life / p.maxLife;

            // Fade in quickly, then ease-out fade
            float alpha;
            if (lifeRatio < 0.15f) {
                alpha = lifeRatio / 0.15f;
            } else {
                float t = (lifeRatio - 0.15f) / 0.85f;
                alpha = 1f - t * t;
            }
            alpha *= p.alphaRatio;
            if (alpha <= 0.01f) continue;

            int a = (int) (alpha * 255);
            int rgb = p.color & 0x00FFFFFF;

            // Breathing size effect
            float breathing = 1f + 0.12f * (float) Math.sin(p.life * 0.6f + p.x * 0.1f);
            float drawSize = p.size * breathing;

            // Glow layer (soft, larger)
            int glowAlpha = p.isFocus ? (int) (alpha * 70) : (int) (alpha * 50);
            mParticlePaint.setColor(rgb | (glowAlpha << 24));
            float glowRadius = p.isFocus ? drawSize * 3.0f : drawSize * 2.2f;
            canvas.drawCircle(p.x, p.y, glowRadius, mParticlePaint);

            // Core layer (bright, smaller)
            mParticlePaint.setColor(rgb | (a << 24));
            float coreRadius = p.isFocus ? drawSize * 1.2f : drawSize * (0.55f + 0.45f * alpha);
            canvas.drawCircle(p.x, p.y, coreRadius, mParticlePaint);
        }
    }

    /**
     * Vary the lightness of a color using HSL space.
     * Positive delta makes it brighter, negative darker.
     */
    private int varyColor(int baseColor, float lightnessDelta) {
        float[] hsl = new float[3];
        androidx.core.graphics.ColorUtils.colorToHSL(baseColor, hsl);
        hsl[2] = Math.max(0f, Math.min(1f, hsl[2] + lightnessDelta));
        return androidx.core.graphics.ColorUtils.HSLToColor(hsl);
    }

    private static class Particle {
        float x, y;
        float vx, vy;
        float size;
        float life;
        float maxLife;
        int color;
        float alphaRatio;
        boolean isFocus;
    }

    // --- Path utilities ---

    static void addRoundRectCCW(Path path, float left, float top, float right, float bottom, float radius) {
        float r = Math.max(radius, 0f);
        float w = right - left;
        float h = bottom - top;
        float rw = w - 2f * r;
        float rh = h - 2f * r;

        path.moveTo(right - r, top);
        path.rLineTo(-rw, 0f);
        path.arcTo(left, top, left + 2f * r, top + 2f * r, 270f, -90f, false);
        path.rLineTo(0f, rh);
        path.arcTo(left, bottom - 2f * r, left + 2f * r, bottom, 180f, -90f, false);
        path.rLineTo(rw, 0f);
        path.arcTo(right - 2f * r, bottom - 2f * r, right, bottom, 90f, -90f, false);
        path.rLineTo(0f, -rh + r);
        path.arcTo(right - 2f * r, top, right, top + 2f * r, 0f, -90f, false);
    }

    static void addRoundRectCW(Path path, float left, float top, float right, float bottom, float radius) {
        float r = Math.max(radius, 0f);
        float w = right - left;
        float h = bottom - top;
        float rw = w - 2f * r;
        float rh = h - 2f * r;

        path.moveTo(left, bottom - r);
        path.rLineTo(0f, -rh + r);
        path.arcTo(left, top, left + 2f * r, top + 2f * r, 180f, 90f, false);
        path.rLineTo(rw, 0f);
        path.arcTo(right - 2f * r, top, right, top + 2f * r, 270f, 90f, false);
        path.rLineTo(0f, rh);
        path.arcTo(right - 2f * r, bottom - 2f * r, right, bottom, 0f, 90f, false);
        path.rLineTo(-rw + r, 0f);
        path.arcTo(left, bottom - 2f * r, left + 2f * r, bottom, 90f, 90f, false);
    }

    private static float dpToPx(float dp) {
        return dp * android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, 1f,
                android.content.res.Resources.getSystem().getDisplayMetrics());
    }

    // --- PathFrame: discretizes a Path into an array of points ---

    private static class PathFrame {
        private final float[] mData;
        private final int     mPointCount;

        PathFrame(Path path) {
            PathMeasure pm = new PathMeasure(path, false);
            float length = pm.getLength();
            int rawCount = (int) length + 1;
            float[] rawData = new float[rawCount * 2];
            float[] pos = new float[2];
            int index = 0;
            for (int i = 0; i < rawCount; i++) {
                float distance = i * length / (rawCount - 1);
                pm.getPosTan(distance, pos, null);
                rawData[index]     = pos[0];
                rawData[index + 1] = pos[1];
                index += 2;
            }
            mData = rawData;
            mPointCount = mData.length;
        }

        float[] getFrame(float start, float end) {
            int si = (int) (mPointCount * start);
            int ei = (int) (mPointCount * end);
            if (si % 2 != 0) si--;
            if (ei % 2 != 0) ei++;
            if (si < ei) {
                return Arrays.copyOfRange(mData, si, ei);
            }
            return null;
        }

        float[] getPointAtProgress(float progress) {
            int totalPoints = mPointCount / 2;
            if (totalPoints <= 0) return null;
            int pointIndex = (int) (totalPoints * progress);
            if (pointIndex < 0) pointIndex = 0;
            if (pointIndex >= totalPoints) pointIndex = totalPoints - 1;
            int dataIndex = pointIndex * 2;
            return new float[] { mData[dataIndex], mData[dataIndex + 1] };
        }
    }
}
