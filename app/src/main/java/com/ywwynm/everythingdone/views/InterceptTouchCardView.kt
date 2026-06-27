package com.ywwynm.everythingdone.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.cardview.widget.CardView
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.ywwynm.everythingdone.R

/**
 * Created by ywwynm on 2015/9/18.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * A CardView that can interrupt touch event so that inner RecyclerView cannot handle it.
 *
 * updated on 2016/9/6
 * Because we want to let user have the ability to finish/unfinish checklist item directly on
 * thing card, we now can set if we should intercept touch events here.
 */
open class InterceptTouchCardView : CardView {

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    private var mShouldInterceptTouchEvent: Boolean = true

    // 缓存"正在做"蒙层引用，避免每次测量都遍历子节点（滚动时测量频繁）。
    private var mDoingCover: View? = null
    private var mDoingCoverResolved: Boolean = false

    // 复用，避免软件绘制时分配（仅软件绘制路径用到）。
    private val mSoftwareRoundPath = Path()
    private val mSoftwareCornerPath = Path()
    private val mSoftwareCornerClearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    fun setShouldInterceptTouchEvent(shouldInterceptTouchEvent: Boolean) {
        mShouldInterceptTouchEvent = shouldInterceptTouchEvent
    }

    /**
     * 软件绘制（如拖拽 / 移入文件夹动画把卡片 `draw` 到 bitmap 做悬浮快照）**不会**应用
     * `clipToOutline` 的圆角裁切——那是硬件 RenderNode 的行为。结果是卡片里贴边的图片、视频背景、
     * 右上角置顶标识等在快照里被画成直角矩形，拖拽时就看到缩略图卡片对应的角 / 边不再是圆角（结束
     * 拖拽、回到正常硬件渲染后又变圆角）。大文件夹里每张缩略图预览卡都是本类实例，故尤为明显。
     *
     * 这里在软件画布上把子 View 画进一个离屏层，画完后把"四角 = 矩形 − 圆角矩形"用 `CLEAR` 抗锯齿
     * 地擦掉，使子 View 被裁成圆角且边缘平滑（不能用 `drawRoundRect` + `DST_IN`：`drawRoundRect` 只覆盖
     * 圆角矩形内部、四角不会被绘制操作触及，xfermode 也就清不掉四角）。硬件绘制（正常上屏）直接走
     * 父类、依赖 `clipToOutline`，不受影响、无额外开销（仅一次布尔判断即返回）。
     */
    override fun dispatchDraw(canvas: Canvas) {
        val r = radius
        if (!canvas.isHardwareAccelerated && clipToOutline && r > 0f
            && width > 0 && height > 0
        ) {
            val w = width.toFloat()
            val h = height.toFloat()
            val save = canvas.saveLayer(0f, 0f, w, h, null)
            super.dispatchDraw(canvas)
            mSoftwareRoundPath.reset()
            mSoftwareRoundPath.addRoundRect(0f, 0f, w, h, r, r, Path.Direction.CW)
            mSoftwareCornerPath.reset()
            mSoftwareCornerPath.addRect(0f, 0f, w, h, Path.Direction.CW)
            mSoftwareCornerPath.op(mSoftwareRoundPath, Path.Op.DIFFERENCE)
            canvas.drawPath(mSoftwareCornerPath, mSoftwareCornerClearPaint)
            canvas.restoreToCount(save)
        } else {
            super.dispatchDraw(canvas)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // "正在做"蒙层是 wrap_content 卡片下的 match_parent 子 View。本 RecyclerView 下卡片常拿到
        // EXACTLY 测量规格，FrameLayout 不一定跑第二趟 match_parent 测量，蒙层就退化成内容大小、
        // 铺不满卡片。卡片自身尺寸已由其它内容在 super 中确定，这里再把可见的蒙层按卡片最终内容区
        // 强制重测，使其铺满且内部图文居中。因为发生在 super 之后，不影响卡片高度（不会撑高）。
        if (!mDoingCoverResolved) {
            mDoingCover = findOwnDoingCover()
            mDoingCoverResolved = true
        }
        val cover = mDoingCover ?: return
        if (cover.visibility == View.GONE) return
        val w = measuredWidth - paddingLeft - paddingRight
        val h = measuredHeight - paddingTop - paddingBottom
        if (w <= 0 || h <= 0) return
        if (cover.measuredWidth != w || cover.measuredHeight != h) {
            cover.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
            )
        }
    }

    /**
     * 只在本卡片的直接子节点里找"正在做"蒙层，**不能用 findViewById**。
     *
     * card_thing 布局的根就是 InterceptTouchCardView，蒙层 fl_thing_doing_cover 是它的直接子 View。
     * 而"缩略图模式文件夹卡"会把若干预览记事卡（同样用 card_thing inflate、带相同的
     * R.id.fl_thing_doing_cover）作为子 View 加进自己的内容区。findViewById 会深度优先遍历整棵子树，
     * 在遇到文件夹卡自己的蒙层之前，先命中**第一个预览卡的蒙层**。一旦把那个预览（恰为正在做的记事，
     * 蒙层可见）的蒙层当成自己的、按整张文件夹卡尺寸强制重测，该预览里居中的图标 + 文字就会被摆到
     * 整张文件夹卡的中心、跑到小预览卡之外被裁掉——表现为"蒙层在、图标/文字不显示"，且只发生在
     * 文件夹里第一个预览卡上。改为只在直接子节点里找，保证每张卡都只处理自己的蒙层。
     */
    private fun findOwnDoingCover(): View? {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.id == R.id.fl_thing_doing_cover) {
                return child
            }
        }
        return null
    }

    /**
     * if [mShouldInterceptTouchEvent] is `true`, then we will intercept touch event
     * so that inner views cannot receive it. Otherwise, inner views can still handle their own
     * touch events.
     *
     * If a ViewGroup contains a RecyclerView and has an OnTouchListener or something like that,
     * touch events will be directly delivered to inner RecyclerView and handled by it. As a result,
     * parent ViewGroup won't receive the touch event any longer. So this class is created to solve
     * this problem.
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return mShouldInterceptTouchEvent
    }
}
