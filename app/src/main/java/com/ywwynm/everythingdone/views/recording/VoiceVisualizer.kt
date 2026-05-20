package com.ywwynm.everythingdone.views.recording

import android.content.Context
import android.content.res.TypedArray
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.widget.FrameLayout

import com.ywwynm.everythingdone.R

/**
 * A class that draws visualizations of data received from [AudioRecorder]
 *
 * Created by tyorikan on 2015/06/08.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Updated by ywwynm on 2015/9/28 to meet requirements
 */
open class VoiceVisualizer(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {

    private var mNumColumns: Int = 0
    private var mType: Int = 0
    private var mRenderRange: Int = 0

    private var mBaseY: Int = 0

    private var mCanvas: Canvas? = null
    private var mCanvasBitmap: Bitmap? = null
    private val mRect: Rect = Rect()
    private val mPaint: Paint = Paint()
    private val mFadePaint: Paint = Paint()

    private val mMatrix: Matrix = Matrix()

    private var mColumnWidth: Float = 0f

    init {
        init(context, attrs)
        mFadePaint.setColor(Color.argb(138, 255, 255, 255))
    }

    private fun init(context: Context, attrs: AttributeSet?) {
        val args: TypedArray = context.obtainStyledAttributes(attrs, R.styleable.VoiceVisualizer)!!
        mNumColumns = args.getInteger(R.styleable.VoiceVisualizer_numColumns, DEFAULT_NUM_COLUMNS)

        mPaint.setColor(args.getColor(R.styleable.VoiceVisualizer_renderColor, Color.BLACK))

        mType = args.getInt(R.styleable.VoiceVisualizer_renderType, Type.BAR.getFlag())
        mRenderRange = args.getInteger(R.styleable.VoiceVisualizer_renderRange, RENDER_RANGE_TOP)
        args.recycle()
    }

    fun setRenderColor(renderColor: Int) {
        mPaint.setColor(renderColor)
    }

    /**
     * @param baseY center Y position of visualizer
     */
    fun setBaseY(baseY: Int) {
        mBaseY = baseY
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Create canvas once we're ready to draw
        mRect.set(0, 0, getWidth(), getHeight())

        if (mCanvasBitmap == null) {
            mCanvasBitmap = Bitmap.createBitmap(
                    canvas.getWidth(), canvas.getHeight(), Bitmap.Config.ARGB_8888)
        }

        if (mCanvas == null) {
            mCanvas = Canvas(mCanvasBitmap!!)
        }

        if (mNumColumns > getWidth()) {
            mNumColumns = DEFAULT_NUM_COLUMNS
        }

        mColumnWidth = getWidth().toFloat() / mNumColumns.toFloat()

        if (mBaseY == 0) {
            mBaseY = getHeight()
        }

        canvas.drawBitmap(mCanvasBitmap!!, mMatrix, null)
    }

    /**
     * receive volume from [AudioRecorder]
     *
     * @param volume volume from mic input
     */
    internal fun receive(volume: Int) {
        Handler(Looper.getMainLooper()).post(object : Runnable {
            override fun run() {
                if (mCanvas == null) {
                    return
                }

                if (volume == 0) {
                    mCanvas!!.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                } else if ((mType and Type.FADE.getFlag()) != 0) {
                    // Fade out old contents
                    mFadePaint.setXfermode(PorterDuffXfermode(PorterDuff.Mode.MULTIPLY))
                    mCanvas!!.drawPaint(mFadePaint)
                } else {
                    mCanvas!!.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                }

                if ((mType and Type.BAR.getFlag()) != 0) {
                    this@VoiceVisualizer.drawBar(volume)
                }
                if ((mType and Type.PIXEL.getFlag()) != 0) {
                    this@VoiceVisualizer.drawPixel(volume)
                }
                this@VoiceVisualizer.invalidate()
            }
        })
    }

    private fun drawBar(volume: Int) {
        for (i in 0 until mNumColumns) {
            val height: Float = getRandomHeight(volume)
            val left: Float = i * mColumnWidth
            val right: Float = (i + 1) * mColumnWidth

            val rect: RectF = createRectF(left, right, height)
            mCanvas!!.drawRect(rect, mPaint)
        }
    }

    private fun getRandomHeight(volume: Int): Float {
        val randomVolume: Double = Math.random() * volume + 1
        val height: Float = when (mRenderRange) {
            RENDER_RANGE_TOP -> mBaseY.toFloat()
            RENDER_RANGE_BOTTOM -> (getHeight() - mBaseY).toFloat()
            else -> getHeight().toFloat()
        }

        val shrinkFactor: Float = if (volume < 50) 160f else 80f

        return (height / shrinkFactor) * randomVolume.toFloat()
    }

    private fun drawPixel(volume: Int) {
        for (i in 0 until mNumColumns) {
            val height: Float = getRandomHeight(volume)
            val left: Float = i * mColumnWidth
            val right: Float = (i + 1) * mColumnWidth

            var drawCount: Int = (height / (right - left)).toInt()
            if (drawCount == 0) {
                drawCount = 1
            }
            val drawHeight: Float = height / drawCount

            // draw each pixel
            for (j in 0 until drawCount) {

                val top: Float
                val bottom: Float
                val rect: RectF

                when (mRenderRange) {
                    RENDER_RANGE_TOP -> {
                        bottom = mBaseY - (drawHeight * j)
                        top = bottom - drawHeight
                        rect = RectF(left, top, right, bottom)
                    }
                    RENDER_RANGE_BOTTOM -> {
                        top = mBaseY + (drawHeight * j)
                        bottom = top + drawHeight
                        rect = RectF(left, top, right, bottom)
                    }
                    RENDER_RANGE_TOP_BOTTOM -> {
                        bottom = mBaseY - (height / 2) + (drawHeight * j)
                        top = bottom - drawHeight
                        rect = RectF(left, top, right, bottom)
                    }
                    else -> return
                }
                mCanvas!!.drawRect(rect, mPaint)
            }
        }
    }

    private fun createRectF(left: Float, right: Float, height: Float): RectF {
        return when (mRenderRange) {
            RENDER_RANGE_TOP -> RectF(left, mBaseY - height, right, mBaseY.toFloat())
            RENDER_RANGE_BOTTOM -> RectF(left, mBaseY.toFloat(), right, mBaseY + height)
            RENDER_RANGE_TOP_BOTTOM -> RectF(left, mBaseY - height, right, mBaseY + height)
            else -> RectF(left, mBaseY - height, right, mBaseY.toFloat())
        }
    }

    /**
     * visualizer type
     */
    enum class Type(private val mFlag: Int) {
        BAR(0x1), PIXEL(0x2), FADE(0x4);

        fun getFlag(): Int {
            return mFlag
        }
    }

    companion object {
        private const val DEFAULT_NUM_COLUMNS: Int = 20
        private const val RENDER_RANGE_TOP: Int = 0
        private const val RENDER_RANGE_BOTTOM: Int = 1
        private const val RENDER_RANGE_TOP_BOTTOM: Int = 2
    }
}
