@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.views

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.TargetApi
import android.content.Context
import android.content.res.TypedArray
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Debug
import android.os.Parcel
import android.os.Parcelable
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.animation.AnimationUtils
import android.view.animation.Interpolator

import com.ywwynm.everythingdone.R

import java.util.ArrayList
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Created by AmniX
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Visit https://github.com/AmniX/MaterialPatternllockView for more details.
 * Changed by ywwynm on 2016/5/22 for project demand
 * A pattern lock view
 */
open class PatternLockView @TargetApi(Build.VERSION_CODES.LOLLIPOP) constructor(
        context: Context, attrs: AttributeSet?
) : View(context, attrs) {

    private val mCellStates: Array<Array<CellState>>
    private val mDotSize: Int
    private val mDotSizeActivated: Int
    private val mPathWidth: Int
    private val mCurrentPath: Path = Path()
    private val mInvalidate: Rect = Rect()
    private val mTmpInvalidateRect: Rect = Rect()
    private var mDrawingProfilingStarted: Boolean = false
    private val mPaint: Paint = Paint()
    private val mPathPaint: Paint = Paint()
    private var mOnPatternListener: OnPatternListener? = null
    private val mPattern: ArrayList<Cell> = ArrayList(MATRIX_SIZE)

    /**
     * Lookup table for the circles of the pattern we are currently drawing. This will be the
     * cells of the complete pattern unless we are animating, in which case we use this to hold
     * the cells we are drawing for the in progress animation.
     */
    private val mPatternDrawLookup: Array<BooleanArray> = Array(LOCK_SIZE) { BooleanArray(LOCK_SIZE) }

    /**
     * the in progress point: - during interaction: where the user's finger is - during animation:
     * the current tip of the animating line
     */
    private var mInProgressX: Float = -1f
    private var mInProgressY: Float = -1f

    private var mAnimatingPeriodStart: Long = 0

    private var mPatternDisplayMode: DisplayMode = DisplayMode.Correct
    private var mInputEnabled: Boolean = true
    private var mInStealthMode: Boolean = false
    private var mEnableHapticFeedback: Boolean = true
    private var mPatternInProgress: Boolean = false

    private val mHitFactor: Float = 0.6f

    private var mSquareWidth: Float = 0f
    private var mSquareHeight: Float = 0f
    private var mPathColor: Int = 0
    private var mWrongColor: Int = 0
    private var mCorrectColor: Int = 0
    private var mFastOutSlowInInterpolator: Interpolator? = null
    private var mLinearOutSlowInInterpolator: Interpolator? = null

    constructor(context: Context) : this(context, null)

    init {

        isClickable = true
        mPathPaint.isAntiAlias = true
        mPathPaint.isDither = true

        val typedArray: TypedArray = context.obtainStyledAttributes(attrs, R.styleable.PatternLockView)!!
        mPathColor = typedArray.getColor(R.styleable.PatternLockView_pathColor, Color.WHITE)
        mWrongColor = typedArray.getColor(R.styleable.PatternLockView_wrongColor, Color.RED)
        mCorrectColor = typedArray.getColor(R.styleable.PatternLockView_correctColor, Color.GREEN)
        typedArray.recycle()

        mPathPaint.setColor(mPathColor)
        mPathPaint.style = Paint.Style.STROKE
        mPathPaint.strokeJoin = Paint.Join.ROUND
        mPathPaint.strokeCap = Paint.Cap.ROUND

        mPathWidth = dpToPx(3f)
        mPathPaint.strokeWidth = mPathWidth.toFloat()
        mDotSize = dpToPx(12f)
        mDotSizeActivated = dpToPx(28f)
        mPaint.isAntiAlias = true
        mPaint.isDither = true

        mCellStates = Array(LOCK_SIZE) { i ->
            Array(LOCK_SIZE) { j ->
                val cs = CellState()
                cs.size = mDotSize.toFloat()
                cs
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                && !isInEditMode
        ) {
            mFastOutSlowInInterpolator = AnimationUtils.loadInterpolator(
                    context, android.R.interpolator.fast_out_slow_in)
            mLinearOutSlowInInterpolator = AnimationUtils.loadInterpolator(
                    context, android.R.interpolator.linear_out_slow_in)
        }
    }

    private fun dpToPx(dpValue: Float): Int {
        val scale: Float = resources!!.displayMetrics!!.density
        return (dpValue * scale + 0.5f).toInt()
    }

    fun setPathColor(pathColor: Int) {
        mPathColor = pathColor
    }

    fun getPathColor(): Int {
        return mPathColor
    }

    fun setCorrectColor(correctColor: Int) {
        mCorrectColor = correctColor
    }

    fun getCorrectColor(): Int {
        return mCorrectColor
    }

    fun setWrongColor(wrongColor: Int) {
        mWrongColor = wrongColor
    }

    fun getWrongColor(): Int {
        return mWrongColor
    }

    fun getCellStates(): Array<Array<CellState>> {
        return mCellStates
    }

    /**
     * @return Whether the view is in stealth mode.
     */
    fun isInStealthMode(): Boolean {
        return mInStealthMode
    }

    /**
     * Set whether the view is in stealth mode. If `true`, there will be no visible feedback
     * as the user enters the pattern.
     *
     * @param inStealthMode Whether in stealth mode.
     */
    fun setInStealthMode(inStealthMode: Boolean) {
        mInStealthMode = inStealthMode
    }

    /**
     * @return Whether the view has tactile feedback enabled.
     */
    fun isTactileFeedbackEnabled(): Boolean {
        return mEnableHapticFeedback
    }

    /**
     * Set whether the view will use tactile feedback. If `true`, there will be tactile
     * feedback as the user enters the pattern.
     *
     * @param tactileFeedbackEnabled Whether tactile feedback is enabled
     */
    fun setTactileFeedbackEnabled(tactileFeedbackEnabled: Boolean) {
        mEnableHapticFeedback = tactileFeedbackEnabled
    }

    /**
     * Set the call back for pattern detection.
     *
     * @param onPatternListener The call back.
     */
    fun setOnPatternListener(onPatternListener: OnPatternListener?) {
        mOnPatternListener = onPatternListener
    }

    /**
     * Retrieves current pattern.
     *
     * @return current displaying pattern. **Note:** This is an independent list with the view's
     * pattern itself.
     */
    @Suppress("UNCHECKED_CAST")
    fun getPattern(): List<Cell> {
        return mPattern.clone() as List<Cell>
    }

    /**
     * Set the pattern explicitly (rather than waiting for the user to input a pattern).
     *
     * @param displayMode How to display the pattern.
     * @param pattern     The pattern.
     */
    fun setPattern(displayMode: DisplayMode, pattern: List<Cell>) {
        mPattern.clear()
        mPattern.addAll(pattern)
        clearPatternDrawLookup()
        for (cell in pattern) {
            mPatternDrawLookup[cell.row][cell.column] = true
        }

        setDisplayMode(displayMode)
    }

    /**
     * Clear the pattern lookup table.
     */
    private fun clearPatternDrawLookup() {
        for (i in 0 until LOCK_SIZE) {
            for (j in 0 until LOCK_SIZE) {
                mPatternDrawLookup[i][j] = false
            }
        }
    }

    private fun getCenterXForColumn(column: Int): Float {
        return getPaddingLeft() + column * mSquareWidth + mSquareWidth / 2f
    }

    private fun getCenterYForRow(row: Int): Float {
        return paddingTop + row * mSquareHeight + mSquareHeight / 2f
    }

    /**
     * Gets display mode.
     *
     * @return display mode.
     */
    fun getDisplayMode(): DisplayMode {
        return mPatternDisplayMode
    }

    /**
     * Set the display mode of the current pattern. This can be useful, for instance, after
     * detecting a pattern to tell this view whether change the in progress result to correct
     * or wrong.
     *
     * @param displayMode The display mode.
     */
    fun setDisplayMode(displayMode: DisplayMode) {
        mPatternDisplayMode = displayMode
        if (displayMode == DisplayMode.Animate) {
            if (mPattern.isEmpty()) {
                throw IllegalStateException(
                        "you must have a pattern to "
                                + "animate if you want to set the display mode to animate")
            }
            mAnimatingPeriodStart = SystemClock.elapsedRealtime()
            val first: Cell = mPattern[0]
            mInProgressX = getCenterXForColumn(first.column)
            mInProgressY = getCenterYForRow(first.row)
            clearPatternDrawLookup()
        }
        invalidate()
    }

    fun getSimplePattern(): String {
        return getSimplePattern(mPattern)
    }

    private fun getSimplePattern(pattern: List<Cell>): String {
        val stringBuilder: StringBuilder = StringBuilder()
        for (cell in pattern) {
            stringBuilder.append(getSimpleCellPosition(cell))
        }
        return stringBuilder.toString()
    }

    private fun getSimpleCellPosition(cell: Cell?): String {
        if (cell == null) {
            return ""
        }
        return (cell.row * 3 + cell.column + 1).toString()
    }

    private fun notifyCellAdded() {
        mOnPatternListener?.onPatternCellAdded(mPattern, getSimplePattern(mPattern))
    }

    private fun notifyPatternStarted() {
        mOnPatternListener?.onPatternStart()
    }

    private fun notifyPatternDetected() {
        mOnPatternListener?.onPatternDetected(mPattern, getSimplePattern(mPattern))
    }

    private fun notifyPatternCleared() {
        mOnPatternListener?.onPatternCleared()
    }

    /**
     * Clear the pattern.
     */
    fun clearPattern() {
        resetPattern()
    }

    /**
     * Reset all pattern state.
     */
    private fun resetPattern() {
        mPattern.clear()
        clearPatternDrawLookup()
        mPatternDisplayMode = DisplayMode.Correct
        invalidate()
    }

    /**
     * Disable input (for instance when displaying a message that will timeout so user doesn't
     * get view into messy state).
     */
    fun disableInput() {
        mInputEnabled = false
    }

    /**
     * Enable input.
     */
    fun enableInput() {
        mInputEnabled = true
    }

    /**
     * Determines whether the point x, y will add a new point to the current pattern (in addition
     * to finding the cell, also makes heuristic choices such as filling in gaps based on
     * current pattern).
     *
     * @param x The x coordinate.
     * @param y The y coordinate.
     */
    private fun detectAndAddHit(x: Float, y: Float): Cell? {
        val cell: Cell? = checkForNewHit(x, y)
        if (cell != null) {

            // check for gaps in existing pattern
            var fillInGapCell: Cell? = null
            val pattern: ArrayList<Cell> = mPattern
            if (!pattern.isEmpty()) {
                val lastCell: Cell = pattern[pattern.size - 1]
                val dRow: Int = cell.row - lastCell.row
                val dColumn: Int = cell.column - lastCell.column

                var fillInRow: Int = lastCell.row
                var fillInColumn: Int = lastCell.column

                if (abs(dRow) == 2 && abs(dColumn) != 1) {
                    fillInRow = lastCell.row + (if (dRow > 0) 1 else -1)
                }

                if (abs(dColumn) == 2 && abs(dRow) != 1) {
                    fillInColumn = lastCell.column + (if (dColumn > 0) 1 else -1)
                }

                fillInGapCell = Cell.of(fillInRow, fillInColumn)
            }

            if (fillInGapCell != null
                    && !mPatternDrawLookup[fillInGapCell.row][fillInGapCell.column]) {
                addCellToPattern(fillInGapCell)
            }
            addCellToPattern(cell)
            if (mEnableHapticFeedback) {
                performHapticFeedback(
                        HapticFeedbackConstants.VIRTUAL_KEY,
                        HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
                                or HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
            }
            return cell
        }
        return null
    }

    private fun addCellToPattern(newCell: Cell) {
        mPatternDrawLookup[newCell.row][newCell.column] = true
        mPattern.add(newCell)
        if (!mInStealthMode) {
            startCellActivatedAnimation(newCell)
        }
        notifyCellAdded()
    }

    private fun startCellActivatedAnimation(cell: Cell) {
        val cellState: CellState = mCellStates[cell.row][cell.column]
        startSizeAnimation(mDotSize.toFloat(), mDotSizeActivated.toFloat(), 96,
                mLinearOutSlowInInterpolator, cellState, object : Runnable {

                    override fun run() {
                        startSizeAnimation(mDotSizeActivated.toFloat(), mDotSize.toFloat(), 192,
                                mFastOutSlowInInterpolator, cellState, null)
                    }
                })
        startLineEndAnimation(cellState, mInProgressX, mInProgressY,
                getCenterXForColumn(cell.column), getCenterYForRow(cell.row))
    }

    private fun startLineEndAnimation(state: CellState,
                                      startX: Float, startY: Float, targetX: Float,
                                      targetY: Float) {
        val valueAnimator: ValueAnimator = ValueAnimator.ofFloat(0f, 1f)
        valueAnimator
                .addUpdateListener(object : ValueAnimator.AnimatorUpdateListener {

                    override fun onAnimationUpdate(animation: ValueAnimator) {
                        val t: Float = animation.getAnimatedValue() as Float
                        state.lineEndX = (1 - t) * startX + t * targetX
                        state.lineEndY = (1 - t) * startY + t * targetY
                        invalidate()
                    }

                })
        valueAnimator.addListener(object : AnimatorListenerAdapter() {

            override fun onAnimationEnd(animation: Animator) {
                state.lineAnimator = null
            }

        })
        valueAnimator.interpolator = mFastOutSlowInInterpolator
        valueAnimator.setDuration(100)
        valueAnimator.start()
        state.lineAnimator = valueAnimator
    }

    private fun startSizeAnimation(start: Float, end: Float, duration: Long,
                                   interpolator: Interpolator?, state: CellState,
                                   endRunnable: Runnable?) {
        val valueAnimator: ValueAnimator = ValueAnimator.ofFloat(start, end)
        valueAnimator
                .addUpdateListener(object : ValueAnimator.AnimatorUpdateListener {

                    override fun onAnimationUpdate(animation: ValueAnimator) {
                        state.size = animation.getAnimatedValue() as Float
                        invalidate()
                    }

                })
        if (endRunnable != null) {
            valueAnimator.addListener(object : AnimatorListenerAdapter() {

                override fun onAnimationEnd(animation: Animator) {
                    endRunnable.run()
                }

            })
        }
        valueAnimator.interpolator = interpolator
        valueAnimator.setDuration(duration)
        valueAnimator.start()
    }

    // helper method to find which cell a point maps to
    private fun checkForNewHit(x: Float, y: Float): Cell? {

        val rowHit: Int = getRowHit(y)
        if (rowHit < 0) {
            return null
        }
        val columnHit: Int = getColumnHit(x)
        if (columnHit < 0) {
            return null
        }

        if (mPatternDrawLookup[rowHit][columnHit]) {
            return null
        }
        return Cell.of(rowHit, columnHit)
    }

    /**
     * Helper method to find the row that y falls into.
     *
     * @param y The y coordinate
     * @return The row that y falls in, or -1 if it falls in no row.
     */
    private fun getRowHit(y: Float): Int {

        val squareHeight: Float = mSquareHeight
        val hitSize: Float = squareHeight * mHitFactor

        val offset: Float = paddingTop + (squareHeight - hitSize) / 2f
        for (i in 0 until LOCK_SIZE) {

            val hitTop: Float = offset + squareHeight * i
            if (y >= hitTop && y <= hitTop + hitSize) {
                return i
            }
        }
        return -1
    }

    /**
     * Helper method to find the column x falls into.
     *
     * @param x The x coordinate.
     * @return The column that x falls in, or -1 if it falls in no column.
     */
    private fun getColumnHit(x: Float): Int {
        val squareWidth: Float = mSquareWidth
        val hitSize: Float = squareWidth * mHitFactor

        val offset: Float = getPaddingLeft() + (squareWidth - hitSize) / 2f
        for (i in 0 until LOCK_SIZE) {

            val hitLeft: Float = offset + squareWidth * i
            if (x >= hitLeft && x <= hitLeft + hitSize) {
                return i
            }
        }
        return -1
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if ((context.getSystemService(
                Context.ACCESSIBILITY_SERVICE) as AccessibilityManager).isTouchExplorationEnabled
        ) {
            val action: Int = event.action
            when (action) {
                MotionEvent.ACTION_HOVER_ENTER ->
                    event.setAction(MotionEvent.ACTION_DOWN)
                MotionEvent.ACTION_HOVER_MOVE ->
                    event.setAction(MotionEvent.ACTION_MOVE)
                MotionEvent.ACTION_HOVER_EXIT ->
                    event.setAction(MotionEvent.ACTION_UP)
                else -> { }
            }
            onTouchEvent(event)
            event.setAction(action)
        }
        return super.onHoverEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!mInputEnabled || !isEnabled) {
            return false
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                handleActionDown(event)
                return true
            }
            MotionEvent.ACTION_UP -> {
                handleActionUp(event)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                handleActionMove(event)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                /*
                 * Original source check for mPatternInProgress == true first before
                 * calling next three lines. But if we do that, there will be
                 * nothing happened when the user taps at empty area and releases
                 * the finger. We want the pattern to be reset and the message will
                 * be updated after the user did that.
                 */
                mPatternInProgress = false
                resetPattern()
                notifyPatternCleared()

                if (PROFILE_DRAWING) {
                    if (mDrawingProfilingStarted) {
                        Debug.stopMethodTracing()
                        mDrawingProfilingStarted = false
                    }
                }
                return true
            }
        }
        return false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val width: Int = w - getPaddingLeft() - getPaddingRight()
        mSquareWidth = width / LOCK_SIZE.toFloat()

        val height: Int = h - paddingTop - paddingBottom
        mSquareHeight = height / LOCK_SIZE.toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        val pattern: ArrayList<Cell> = mPattern
        val count: Int = pattern.size
        val drawLookup: Array<BooleanArray> = mPatternDrawLookup

        if (mPatternDisplayMode == DisplayMode.Animate) {

            // figure out which circles to draw

            // + 1 so we pause on complete pattern
            val oneCycle: Int = (count + 1) * MILLIS_PER_CIRCLE_ANIMATING
            val spotInCycle: Int = ((SystemClock.elapsedRealtime() - mAnimatingPeriodStart) % oneCycle).toInt()
            val numCircles: Int = spotInCycle / MILLIS_PER_CIRCLE_ANIMATING

            clearPatternDrawLookup()
            for (i in 0 until numCircles) {
                val cell: Cell = pattern[i]
                drawLookup[cell.row][cell.column] = true
            }

            // figure out in progress portion of ghosting line

            val needToUpdateInProgressPoint: Boolean = numCircles in 1..<count

            if (needToUpdateInProgressPoint) {
                val percentageOfNextCircle: Float = (spotInCycle % MILLIS_PER_CIRCLE_ANIMATING).toFloat() /
                        MILLIS_PER_CIRCLE_ANIMATING

                val currentCell: Cell = pattern[numCircles - 1]
                val centerX: Float = getCenterXForColumn(currentCell.column)
                val centerY: Float = getCenterYForRow(currentCell.row)

                val nextCell: Cell = pattern[numCircles]
                val dx: Float = percentageOfNextCircle *
                        (getCenterXForColumn(nextCell.column) - centerX)
                val dy: Float = percentageOfNextCircle *
                        (getCenterYForRow(nextCell.row) - centerY)
                mInProgressX = centerX + dx
                mInProgressY = centerY + dy
            }
            // TODO: Infinite loop here...
            invalidate()
        }

        val currentPath: Path = mCurrentPath
        currentPath.rewind()

        // draw the circles
        for (i in 0 until LOCK_SIZE) {
            val centerY: Float = getCenterYForRow(i)
            for (j in 0 until LOCK_SIZE) {
                val cellState: CellState = mCellStates[i][j]
                val centerX: Float = getCenterXForColumn(j)
                val size: Float = cellState.size * cellState.scale
                val translationY: Float = cellState.translateY
                drawCircle(canvas, centerX.toInt().toFloat(), centerY.toInt() + translationY,
                        size, drawLookup[i][j], cellState.alpha)
            }
        }

        // TODO: the path should be created and cached every time we hit-detect
        // a cell
        // only the last segment of the path should be computed here
        // draw the path of the pattern (unless we are in stealth mode)
        val drawPath: Boolean = !mInStealthMode

        if (drawPath) {
            mPathPaint.setColor(getCurrentColor(true /* partOfPattern */))

            var anyCircles = false
            var lastX = 0f
            var lastY = 0f
            for (i in 0 until count) {
                val cell: Cell = pattern[i]

                // only draw the part of the pattern stored in
                // the lookup table (this is only different in the case
                // of animation).
                if (!drawLookup[cell.row][cell.column]) {
                    break
                }
                anyCircles = true

                val centerX: Float = getCenterXForColumn(cell.column)
                val centerY: Float = getCenterYForRow(cell.row)
                if (i != 0) {
                    val state: CellState = mCellStates[cell.row][cell.column]
                    currentPath.rewind()
                    currentPath.moveTo(lastX, lastY)
                    if (state.lineEndX != Float.MIN_VALUE
                            && state.lineEndY != Float.MIN_VALUE) {
                        currentPath.lineTo(state.lineEndX, state.lineEndY)
                    } else {
                        currentPath.lineTo(centerX, centerY)
                    }
                    canvas.drawPath(currentPath, mPathPaint)
                }
                lastX = centerX
                lastY = centerY
            }

            // draw last in progress section
            if ((mPatternInProgress || mPatternDisplayMode == DisplayMode.Animate)
                    && anyCircles) {
                currentPath.rewind()
                currentPath.moveTo(lastX, lastY)
                currentPath.lineTo(mInProgressX, mInProgressY)

                mPathPaint.setAlpha((calculateLastSegmentAlpha(
                        mInProgressX, mInProgressY, lastX, lastY) * 255f).toInt())
                canvas.drawPath(currentPath, mPathPaint)
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val minimumWidth: Int = suggestedMinimumWidth
        val minimumHeight: Int = suggestedMinimumHeight
        var viewWidth: Int = resolveMeasured(widthMeasureSpec, minimumWidth)
        var viewHeight: Int = resolveMeasured(heightMeasureSpec, minimumHeight)
        val v: Int = min(viewWidth, viewHeight)
        viewWidth = v
        viewHeight = v
        setMeasuredDimension(viewWidth, viewHeight)
    }

    private fun resolveMeasured(measureSpec: Int, desired: Int): Int {
        val specSize: Int = MeasureSpec.getSize(measureSpec)
        val result: Int = when (MeasureSpec.getMode(measureSpec)) {
            MeasureSpec.UNSPECIFIED -> desired
            MeasureSpec.AT_MOST -> max(specSize, desired)
            else -> specSize  // MeasureSpec.EXACTLY (default)
        }
        return result
    }

    /**
     * @param partOfPattern Whether this circle is part of the pattern.
     */
    private fun drawCircle(canvas: Canvas, centerX: Float, centerY: Float,
                           size: Float, partOfPattern: Boolean, alpha: Float) {
        mPaint.setColor(getCurrentColor(partOfPattern))
        mPaint.setAlpha((alpha * 255).toInt())
        canvas.drawCircle(centerX, centerY, size / 2, mPaint)
    }

    private fun getCurrentColor(partOfPattern: Boolean): Int {
        return if (!partOfPattern || mInStealthMode || mPatternInProgress) {
            // unselected circle
            mPathColor
        } else if (mPatternDisplayMode == DisplayMode.Wrong) {
            // the pattern is wrong
            mWrongColor
        } else if (mPatternDisplayMode == DisplayMode.Correct
            || mPatternDisplayMode == DisplayMode.Animate) {
            mCorrectColor
        } else {
            throw IllegalStateException("unknown display mode $mPatternDisplayMode")
        }
    }

    private fun calculateLastSegmentAlpha(x: Float, y: Float, lastX: Float,
                                          lastY: Float): Float {
        val diffX: Float = x - lastX
        val diffY: Float = y - lastY
        val dist: Float = sqrt((diffX * diffX + diffY * diffY).toDouble()).toFloat()
        val frac: Float = dist / mSquareWidth
        return min(1f, max(0f, (frac - 0.3f) * 4f))
    }

    private fun handleActionMove(event: MotionEvent) {
        // Handle all recent motion events so we don't skip any cells even when
        // the device
        // is busy...
        val radius: Float = mPathWidth.toFloat()
        val historySize: Int = event.historySize
        mTmpInvalidateRect.setEmpty()
        var invalidateNow = false
        for (i in 0 until historySize + 1) {
            val x: Float = if (i < historySize) event.getHistoricalX(i) else event.x
            val y: Float = if (i < historySize) event.getHistoricalY(i) else event.y
            val hitCell: Cell? = detectAndAddHit(x, y)
            val patternSize: Int = mPattern.size
            if (hitCell != null && patternSize == 1) {
                mPatternInProgress = true
                notifyPatternStarted()
            }
            // note current x and y for rubber banding of in progress patterns
            val dx: Float = abs(x - mInProgressX)
            val dy: Float = abs(y - mInProgressY)
            if (dx > DRAG_THRESH_HOLD || dy > DRAG_THRESH_HOLD) {
                invalidateNow = true
            }

            if (mPatternInProgress && patternSize > 0) {
                val pattern: ArrayList<Cell> = mPattern
                val lastCell: Cell = pattern[patternSize - 1]
                val lastCellCenterX: Float = getCenterXForColumn(lastCell.column)
                val lastCellCenterY: Float = getCenterYForRow(lastCell.row)

                // Adjust for drawn segment from last cell to (x,y). Radius
                // accounts for line width.
                var left: Float = min(lastCellCenterX, x) - radius
                var right: Float = max(lastCellCenterX, x) + radius
                var top: Float = min(lastCellCenterY, y) - radius
                var bottom: Float = max(lastCellCenterY, y) + radius

                // Invalidate between the pattern's new cell and the pattern's
                // previous cell
                if (hitCell != null) {
                    val width: Float = mSquareWidth * 0.5f
                    val height: Float = mSquareHeight * 0.5f
                    val hitCellCenterX: Float = getCenterXForColumn(hitCell.column)
                    val hitCellCenterY: Float = getCenterYForRow(hitCell.row)

                    left = min(hitCellCenterX - width, left)
                    right = max(hitCellCenterX + width, right)
                    top = min(hitCellCenterY - height, top)
                    bottom = max(hitCellCenterY + height, bottom)
                }

                // Invalidate between the pattern's last cell and the previous
                // location
                mTmpInvalidateRect.union(Math.round(left), Math.round(top),
                        Math.round(right), Math.round(bottom))
            }
        }
        mInProgressX = event.x
        mInProgressY = event.y

        // To save updates, we only invalidate if the user moved beyond a
        // certain amount.
        if (invalidateNow) {
            mInvalidate.union(mTmpInvalidateRect)
            invalidate(mInvalidate)
            mInvalidate.set(mTmpInvalidateRect)
        }
    }

    private fun sendAccessEvent(resId: Int) {
        announceForAccessibility(context.getString(resId))
    }

    private fun handleActionUp(event: MotionEvent) {
        // report pattern detected
        if (!mPattern.isEmpty()) {
            mPatternInProgress = false
            cancelLineAnimations()
            notifyPatternDetected()
            invalidate()
        }
        if (PROFILE_DRAWING) {
            if (mDrawingProfilingStarted) {
                Debug.stopMethodTracing()
                mDrawingProfilingStarted = false
            }
        }
    }

    private fun cancelLineAnimations() {
        for (i in 0 until LOCK_SIZE) {
            for (j in 0 until LOCK_SIZE) {
                val state: CellState = mCellStates[i][j]
                val animator: ValueAnimator? = state.lineAnimator
                if (animator != null) {
                    animator.cancel()
                    state.lineEndX = Float.MIN_VALUE
                    state.lineEndY = Float.MIN_VALUE
                }
            }
        }
    }

    private fun handleActionDown(event: MotionEvent) {
        resetPattern()
        val x: Float = event.x
        val y: Float = event.y
        val hitCell: Cell? = detectAndAddHit(x, y)
        if (hitCell != null) {
            mPatternInProgress = true
            mPatternDisplayMode = DisplayMode.Correct
            notifyPatternStarted()
        } else {
            /*
             * Original source check for mPatternInProgress == true first before
             * calling this block. But if we do that, there will be nothing
             * happened when the user taps at empty area and releases the
             * finger. We want the pattern to be reset and the message will be
             * updated after the user did that.
             */
            mPatternInProgress = false
            notifyPatternCleared()
        }
        if (hitCell != null) {
            val startX: Float = getCenterXForColumn(hitCell.column)
            val startY: Float = getCenterYForRow(hitCell.row)

            val widthOffset: Float = mSquareWidth / 2f
            val heightOffset: Float = mSquareHeight / 2f

            invalidate((startX - widthOffset).toInt(),
                    (startY - heightOffset).toInt(),
                    (startX + widthOffset).toInt(), (startY + heightOffset).toInt())
        }
        mInProgressX = x
        mInProgressY = y
        if (PROFILE_DRAWING) {
            if (!mDrawingProfilingStarted) {
                Debug.startMethodTracing("LockPatternDrawing")
                mDrawingProfilingStarted = true
            }
        }
    }

    /**
     * How to display the current pattern.
     */
    enum class DisplayMode {

        /**
         * The pattern drawn is correct (i.e draw it in a friendly color)
         */
        Correct,

        /**
         * Animate the pattern (for demo, and help).
         */
        Animate,

        /**
         * The pattern is wrong (i.e draw a foreboding color)
         */
        Wrong
    }

    class Cell : Parcelable {

        @JvmField val row: Int
        @JvmField val column: Int

        /**
         * @param row    number or row
         * @param column number of column
         */
        private constructor(row: Int, column: Int) {
            checkRange(row, column)
            this.row = row
            this.column = column
        }

        private constructor(parcelIn: Parcel) {
            column = parcelIn.readInt()
            row = parcelIn.readInt()
        }

        /**
         * Gets the ID. It is counted from left to right, top to bottom of the matrix, starting by zero.
         *
         * @return the ID.
         */
        fun getId(): Int {
            return row * LOCK_SIZE + column
        }

        override fun equals(other: Any?): Boolean {
            if (other is Cell) {
                return column == other.column
                        && row == other.row
            }
            return super.equals(other)
        }

        override fun hashCode(): Int {
            var result: Int = row
            result = 31 * result + column
            return result
        }

        /**
         * @return Row and Column in String.
         */
        override fun toString(): String {
            return "(ROW=$row,COL=$column)"
        }

        override fun describeContents(): Int {
            return 0
        }

        override fun writeToParcel(dest: Parcel, flags: Int) {
            dest.writeInt(column)
            dest.writeInt(row)
        }

        companion object {
            @JvmField val CREATOR: Parcelable.Creator<Cell> = object : Parcelable.Creator<Cell> {

                override fun createFromParcel(parcelIn: Parcel): Cell {
                    return Cell(parcelIn)
                }

                override fun newArray(size: Int): Array<Cell?> {
                    return arrayOfNulls(size)
                }
            }

            @JvmField val sCells: Array<Array<Cell>> = Array(LOCK_SIZE) { i ->
                Array(LOCK_SIZE) { j -> Cell(i, j) }
            }

            private fun checkRange(row: Int, column: Int) {
                if (row < 0 || row > LOCK_SIZE - 1) {
                    throw IllegalArgumentException("row must be in range 0-"
                            + (LOCK_SIZE - 1))
                }
                if (column < 0 || column > LOCK_SIZE - 1) {
                    throw IllegalArgumentException("column must be in range 0-"
                            + (LOCK_SIZE - 1))
                }
            }

            /**
             * Gets a cell from its ID.
             *
             * @param id the cell ID.
             * @return the cell.
             * @author Hai Bison
             * @since v2.7 beta
             */
            @JvmStatic
            @Synchronized
            fun of(id: Int): Cell {
                return of(id / LOCK_SIZE, id % LOCK_SIZE)
            }

            /**
             * @param row    The row of the cell.
             * @param column The column of the cell.
             */
            @JvmStatic
            @Synchronized
            fun of(row: Int, column: Int): Cell {
                checkRange(row, column)
                return sCells[row][column]
            }
        }
    }

    /**
     * The call back abstract class for detecting patterns entered by the user.
     */
    abstract class OnPatternListener {

        /**
         * A new pattern has begun.
         */
        open fun onPatternStart() {

        }

        /**
         * The pattern was cleared.
         */
        open fun onPatternCleared() {

        }

        /**
         * The user extended the pattern currently being drawn by one cell.
         *
         * @param pattern The pattern with newly added cell.
         */
        open fun onPatternCellAdded(pattern: List<Cell>, simplePattern: String) {

        }

        /**
         * A pattern was detected from the user.
         *
         * @param pattern The pattern.
         */
        open fun onPatternDetected(pattern: List<Cell>, simplePattern: String) {

        }
    }

    class CellState {
        @JvmField var scale: Float = 1.0f
        @JvmField var translateY: Float = 0.0f
        @JvmField var alpha: Float = 1.0f
        @JvmField var size: Float = 0f
        @JvmField var lineEndX: Float = Float.MIN_VALUE
        @JvmField var lineEndY: Float = Float.MIN_VALUE
        @JvmField var lineAnimator: ValueAnimator? = null
    }

    /**
     * The parcelable for saving and restoring a lock pattern view.
     */
    private class SavedState : BaseSavedState {

        private val mSerializedPattern: String?
        private val mDisplayMode: Int
        private val mInputEnabled: Boolean
        private val mInStealthMode: Boolean
        private val mTactileFeedbackEnabled: Boolean

        /**
         * Constructor called from [PatternLockView.onSaveInstanceState]
         */
        constructor(superState: Parcelable?, serializedPattern: String?,
                    displayMode: Int, inputEnabled: Boolean, inStealthMode: Boolean,
                    tactileFeedbackEnabled: Boolean) : super(superState) {
            mSerializedPattern = serializedPattern
            mDisplayMode = displayMode
            mInputEnabled = inputEnabled
            mInStealthMode = inStealthMode
            mTactileFeedbackEnabled = tactileFeedbackEnabled
        }

        /**
         * Constructor called from [CREATOR]
         */
        private constructor(parcelIn: Parcel) : super(parcelIn) {
            mSerializedPattern = parcelIn.readString()
            mDisplayMode = parcelIn.readInt()

            val loader: ClassLoader? = javaClass.getClassLoader()
            mInputEnabled = parcelIn.readValue(loader) as Boolean
            mInStealthMode = parcelIn.readValue(loader) as Boolean
            mTactileFeedbackEnabled = parcelIn.readValue(loader) as Boolean
        }

        fun getSerializedPattern(): String? {
            return mSerializedPattern
        }

        fun getDisplayMode(): Int {
            return mDisplayMode
        }

        fun isInputEnabled(): Boolean {
            return mInputEnabled
        }

        fun isInStealthMode(): Boolean {
            return mInStealthMode
        }

        fun isTactileFeedbackEnabled(): Boolean {
            return mTactileFeedbackEnabled
        }

        override fun writeToParcel(dest: Parcel, flags: Int) {
            super.writeToParcel(dest, flags)
            dest.writeString(mSerializedPattern)
            dest.writeInt(mDisplayMode)
            dest.writeValue(mInputEnabled)
            dest.writeValue(mInStealthMode)
            dest.writeValue(mTactileFeedbackEnabled)
        }

        companion object {
            @Suppress("unused")
            @JvmField val CREATOR: Parcelable.Creator<SavedState> = object : Parcelable.Creator<SavedState> {

                override fun createFromParcel(parcelIn: Parcel): SavedState {
                    return SavedState(parcelIn)
                }

                override fun newArray(size: Int): Array<SavedState?> {
                    return arrayOfNulls(size)
                }
            }
        }
    }

    companion object {
        /**
         * @author Aman Tonk
         */
        const val LOCK_SIZE: Int = 3
        /**
         * The size of the pattern's matrix.
         */
        const val MATRIX_SIZE: Int = LOCK_SIZE * LOCK_SIZE
        private const val PROFILE_DRAWING: Boolean = false
        /**
         * How many milliseconds we spend animating each circle of a lock pattern if the animating
         * mode is set. The entire animation should take this constant * the length of the pattern
         * to complete.
         */
        private const val MILLIS_PER_CIRCLE_ANIMATING: Int = 700
        /**
         * This can be used to avoid updating the display for very small motions or noisy panels.
         * It didn't seem to have much impact on the devices tested, so currently set to 0.
         */
        private const val DRAG_THRESH_HOLD: Float = 0.0f
    }
}
