package com.ywwynm.everythingdone.views.recording;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import com.ywwynm.everythingdone.R;

/**
 * A class that draws visualizations of data received from {@link AudioRecorder}
 *
 * Created by tyorikan on 2015/06/08.
 * Updated by ywwynm on 2015/9/28 to meet requirements
 */
public class VoiceVisualizer extends FrameLayout {

    private static final int DEFAULT_NUM_COLUMNS = 20;
    private static final int RENDER_RANGE_TOP = 0;
    private static final int RENDER_RANGE_BOTTOM = 1;
    private static final int RENDER_RANGE_TOP_BOTTOM = 2;

    private int mNumColumns;
    private int mType;
    private int mRenderRange;

    private int mBaseY;

    private Canvas mCanvas;
    private Bitmap mCanvasBitmap;
    private Rect mRect = new Rect();
    private Paint mPaint = new Paint();
    private Paint mFadePaint = new Paint();

    private Matrix mMatrix = new Matrix();

    private float mColumnWidth;

    public VoiceVisualizer(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
        mFadePaint.setColor(Color.argb(138, 255, 255, 255));
    }

    private void init(Context context, AttributeSet attrs) {
        TypedArray args = context.obtainStyledAttributes(attrs, R.styleable.VoiceVisualizer);
        mNumColumns = args.getInteger(R.styleable.VoiceVisualizer_numColumns, DEFAULT_NUM_COLUMNS);

        mPaint.setColor(args.getColor(R.styleable.VoiceVisualizer_renderColor, Color.BLACK));

        mType = args.getInt(R.styleable.VoiceVisualizer_renderType, Type.BAR.getFlag());
        mRenderRange = args.getInteger(R.styleable.VoiceVisualizer_renderRange, RENDER_RANGE_TOP);
        args.recycle();
    }

    public void setRenderColor(int renderColor) {
        mPaint.setColor(renderColor);
    }

    /**
     * @param baseY center Y position of visualizer
     */
    public void setBaseY(int baseY) {
        mBaseY = baseY;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Create canvas once we're ready to draw
        mRect.set(0, 0, getWidth(), getHeight());

        if (mCanvasBitmap == null) {
            mCanvasBitmap = Bitmap.createBitmap(
                    canvas.getWidth(), canvas.getHeight(), Bitmap.Config.ARGB_8888);
        }

        if (mCanvas == null) {
            mCanvas = new Canvas(mCanvasBitmap);
        }

        if (mNumColumns > getWidth()) {
            mNumColumns = DEFAULT_NUM_COLUMNS;
        }

        mColumnWidth = (float) getWidth() / (float) mNumColumns;

        if (mBaseY == 0) {
            mBaseY = getHeight();
        }

        canvas.drawBitmap(mCanvasBitmap, mMatrix, null);
    }

    /**
     * receive volume from {@link AudioRecorder}
     *
     * @param volume volume from mic input
     */
    protected void receive(final int volume) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if (mCanvas == null) {
                    return;
                }

                if (volume == 0) {
                    mCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                } else if ((mType & Type.FADE.getFlag()) != 0) {
                    // Fade out old contents
                    mFadePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.MULTIPLY));
                    mCanvas.drawPaint(mFadePaint);
                } else {
                    mCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                }

                if ((mType & Type.BAR.getFlag()) != 0) {
                    VoiceVisualizer.this.drawBar(volume);
                }
                if ((mType & Type.PIXEL.getFlag()) != 0) {
                    VoiceVisualizer.this.drawPixel(volume);
                }
                VoiceVisualizer.this.invalidate();
            }
        });
    }

    private void drawBar(int volume) {
        // TODO: 2016/7/9 wave algorithm
//        double factor = Math.random();
//        boolean up = factor < 0.5;

        for (int i = 0; i < mNumColumns; i++) {
//            if (up) {
//                factor += 0.01;
//                if (factor >= 1) {
//                    up = false;
//                    factor = Math.random();
//                }
//            } else {
//                factor -= 0.01;
//                if (factor <= 0) {
//                    up = true;
//                    factor = Math.random();
//                }
//            }

            float height = getRandomHeight(volume);
            float left = i * mColumnWidth;
            float right = (i + 1) * mColumnWidth;

            RectF rect = createRectF(left, right, height);
            mCanvas.drawRect(rect, mPaint);
        }
    }

//    private float getRandomHeight(int volume, double factor) {
//        double randomVolume = factor * volume + 1;
//        //double randomVolume = Math.random() * volume + 1;
//        float height = getHeight();
//        switch (mRenderRange) {
//            case RENDER_RANGE_TOP:
//                height = mBaseY;
//                break;
//            case RENDER_RANGE_BOTTOM:
//                height = (getHeight() - mBaseY);
//                break;
//            case RENDER_RANGE_TOP_BOTTOM:
//                height = getHeight();
//                break;
//        }
//
//        float shrinkFactor;
//        if (volume < 55) {
//            shrinkFactor = 160f;
//        } else {
//            shrinkFactor = 80f;
//        }
//
//        return (height / shrinkFactor) * (float) randomVolume;
//    }

    private float getRandomHeight(int volume) {
        double randomVolume = Math.random() * volume + 1;
        float height;
        switch (mRenderRange) {
            case RENDER_RANGE_TOP:
                height = mBaseY;
                break;
            case RENDER_RANGE_BOTTOM:
                height = (getHeight() - mBaseY);
                break;
            case RENDER_RANGE_TOP_BOTTOM:
            default:
                height = getHeight();
                break;
        }

        float shrinkFactor;
        if (volume < 50) {
            shrinkFactor = 160f;
        } else {
            shrinkFactor = 80f;
        }

        return (height / shrinkFactor) * (float) randomVolume;
    }

    private void drawPixel(int volume) {
        for (int i = 0; i < mNumColumns; i++) {
            float height = getRandomHeight(volume);
            float left = i * mColumnWidth;
            float right = (i + 1) * mColumnWidth;

            int drawCount = (int) (height / (right - left));
            if (drawCount == 0) {
                drawCount = 1;
            }
            float drawHeight = height / drawCount;

            // draw each pixel
            for (int j = 0; j < drawCount; j++) {

                float top, bottom;
                RectF rect;

                switch (mRenderRange) {
                    case RENDER_RANGE_TOP:
                        bottom = mBaseY - (drawHeight * j);
                        top = bottom - drawHeight;
                        rect = new RectF(left, top, right, bottom);
                        break;

                    case RENDER_RANGE_BOTTOM:
                        top = mBaseY + (drawHeight * j);
                        bottom = top + drawHeight;
                        rect = new RectF(left, top, right, bottom);
                        break;

                    case RENDER_RANGE_TOP_BOTTOM:
                        bottom = mBaseY - (height / 2) + (drawHeight * j);
                        top = bottom - drawHeight;
                        rect = new RectF(left, top, right, bottom);
                        break;

                    default:
                        return;
                }
                mCanvas.drawRect(rect, mPaint);
            }
        }
    }

    private RectF createRectF(float left, float right, float height) {
        switch (mRenderRange) {
            case RENDER_RANGE_TOP:
                return new RectF(left, mBaseY - height, right, mBaseY);
            case RENDER_RANGE_BOTTOM:
                return new RectF(left, mBaseY, right, mBaseY + height);
            case RENDER_RANGE_TOP_BOTTOM:
                return new RectF(left, mBaseY - height, right, mBaseY + height);
            default:
                return new RectF(left, mBaseY - height, right, mBaseY);
        }
    }

    /**
     * visualizer type
     */
    public enum Type {
        BAR(0x1), PIXEL(0x2), FADE(0x4);

        private int mFlag;

        Type(int flag) {
            mFlag = flag;
        }

        public int getFlag() {
            return mFlag;
        }
    }

}
