package com.ywwynm.everythingdone.taptransfer.ui.receive

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.RecyclerView
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.taptransfer.ui.flight.TapSessionRecorder
import com.ywwynm.everythingdone.views.ActivityHeader
import kotlin.math.abs
import kotlin.math.min

/**
 * 首页列表滑到顶之后再往下拉，拉出「接收记事」按钮（决策 2026-09-24）。
 *
 * 拉的时候列表与头部一起往下移，在 actionbar 下方让出一条空位，按钮在空位里逐渐显出来；松手时
 * 拉过一大半就停在打开的位置，否则收回。打开之后往上滑先收起按钮，收完再接着滚动列表。
 *
 * 列表整体用 `translationY` 下移而不是改内边距：每帧改内边距会让交错网格整体重新布局。
 *
 * 读屏用户做不了下拉手势：头部的大标题上另有一个「接收记事」无障碍动作（[installAccessibilityAction]）。
 */
class TapReceivePull(
    private val container: FrameLayout,
    private val recyclerView: RecyclerView,
    private val header: ActivityHeader?,
    private val actionbar: View,
    private val isEligible: () -> Boolean,
    /** 详情页通过菜单进入，共用位移、按钮和提示，不安装首页下拉手势。 */
    private val allowPullGesture: Boolean = true,
    private val onOffsetChanged: (Float) -> Unit = {},
    private val onClick: () -> Unit
) : RecyclerView.OnItemTouchListener {

    private val context = container.context
    private val density = context.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    val button: View = LayoutInflater.from(context).inflate(R.layout.overlay_tap_receive_button, container, false)
    private val pill: TextView = button.findViewById(R.id.tv_tap_receive)
    private val hint: TextView = button.findViewById(R.id.tv_tap_receive_hint)

    private var offset = 0f
    private var receiving = false
    private var animator: ValueAnimator? = null

    /** 按钮（含提示行）的高度；文案或样式变了才重新量。拉动时每个触摸事件都要用到它。 */
    private var buttonHeight = -1f

    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var crossedThreshold = false

    /**
     * 这次手势按下时列表已经在顶部（或按钮已经拉出）。只有这样的手势才拉得出按钮：一路把列表滑回顶部、
     * 手指不停接着往下拉，拉出来的会是列表的顶部，而不是按钮（2026-09-26 用户指出按钮不该这样出现）。
     */
    private var downAtTop = false

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            // 列表被别的原因滚动（新建记事后滚到新卡片等）时收起按钮
            if (dy != 0 && offset > 0f && !dragging && !receiving && animator == null) {
                closeSlot(true)
            }
        }
    }

    fun attach() {
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        )
        container.addView(button, lp)
        button.visibility = View.INVISIBLE
        button.setOnClickListener(null)
        pill.setOnClickListener { onClick() }
        applyStyle()
        if (allowPullGesture) {
            recyclerView.addOnItemTouchListener(this)
            recyclerView.addOnScrollListener(scrollListener)
        }
        header?.setOnPullResetListener {
            if (receiving) {
                // 旋转屏幕等情况下头部会复位；接收状态下按钮不收，头部的位移补回来
                header?.setPullOffset(offset)
            } else {
                closeSlot(false)
            }
        }
        actionbar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionButton() }
        positionButton()
        if (allowPullGesture) installAccessibilityAction()
    }

    /**
     * 读屏的「接收记事」：挂在头部的大标题上（读屏焦点会停在它上面），界面上看不到。效果与拉出按钮再点它相同：
     * 列表回到顶部、按钮拉出来，再与点按钮一样进入接收（缺什么先引导）。只在拉得出按钮、且不在接收状态时提供：
     * 接收状态下按钮一直在，「退出接收」本身就能点。
     */
    private fun installAccessibilityAction() {
        val header = header ?: return
        val label = context.getString(R.string.tap_receive_button)
        ViewCompat.setAccessibilityDelegate(header.titleView, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                if (!receiving && isEligible()) {
                    info.addAction(
                        AccessibilityNodeInfoCompat.AccessibilityActionCompat(R.id.accessibility_action_tap_receive, label)
                    )
                }
            }

            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                if (action != R.id.accessibility_action_tap_receive) {
                    return super.performAccessibilityAction(host, action, args)
                }
                if (receiving || !isEligible()) {
                    return false
                }
                openForAccessibility()
                return true
            }
        })
    }

    private fun openForAccessibility() {
        recyclerView.stopScroll()
        recyclerView.scrollToPosition(0)
        animateTo(slot())
        onClick()
        if (receiving) {
            // 进入了接收：读屏焦点移到「退出接收」上，读出按钮与下面的提示
            pill.post { pill.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null) }
        }
    }

    /** 收起按钮，列表回到原位。接收状态下不收。 */
    fun closeSlot(anim: Boolean) {
        if (receiving) {
            return
        }
        if (anim) {
            animateTo(0f)
        } else {
            animator?.cancel()
            animator = null
            applyOffset(0f)
        }
    }

    /**
     * 进入 / 退出接收状态：按钮换成「退出接收」并多出一行提示，空位随之变高。
     *
     * 退出时列表若已不在顶部（新卡片在置顶之下，接收时滚过去了），按钮随之收起：「接收记事」只在列表
     * 滑到顶、再往下拉时出现，停在滚过的列表上方会压住头部。
     */
    fun setReceiving(receiving: Boolean, animate: Boolean = true) {
        this.receiving = receiving
        pill.setText(if (receiving) R.string.tap_receive_exit else R.string.tap_receive_button)
        setHint(if (receiving) context.getString(R.string.tap_receive_hint) else null)
        applyStyle()
        if (!animate) {
            animator?.cancel()
            animator = null
            applyOffset(if (receiving) slot() else 0f)
            return
        }
        button.post {
            when {
                this.receiving -> animateTo(slot())
                !allowPullGesture -> animateTo(0f)
                offset > 0f -> animateTo(if (recyclerView.canScrollVertically(-1)) 0f else slot())
            }
        }
    }

    fun setHint(text: String?) {
        TapSessionRecorder.current?.captureSoon("pull", button, 60L)
        buttonHeight = -1f
        if (text.isNullOrEmpty()) {
            hint.visibility = View.GONE
        } else {
            hint.text = text
            hint.visibility = View.VISIBLE
        }
    }

    /** 详情页使用记事的前景色，保证彩色背景上的提示可读。 */
    fun setHintTextColor(color: Int) { hint.setTextColor(color) }

    /** 调试（会话记录）：列表当前的下移量与空位高度。 */
    val currentOffset: Float get() = offset
    val slotHeight: Float get() = slot()
    val isMoving: Boolean get() = animator != null

    /** 按钮（含提示）打开后的下边缘，容器坐标：接收状态的轮廓从这里往下排。 */
    fun openBottom(): Float {
        return buttonTop() + measuredButtonHeight()
    }

    // ---------------------------------------------------------------- 手势

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        if (receiving) {
            return false
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 一律用屏幕坐标：列表本身随拉动下移，列表坐标系里的 y 会把位移抵消掉一部分
                downX = e.rawX
                downY = e.rawY
                lastY = e.rawY
                dragging = false
                downAtTop = offset > 0f || !rv.canScrollVertically(-1)
                crossedThreshold = offset >= slot() * OPEN_THRESHOLD
                if (animator != null && offset > 0f) {
                    // 回弹途中按住：从当前位置接着拉
                    animator?.cancel()
                    animator = null
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    val dy = e.rawY - downY
                    val dx = e.rawX - downX
                    if (abs(dy) > touchSlop && abs(dy) > abs(dx) * 1.2f) {
                        val pullDown = dy > 0f && downAtTop && !rv.canScrollVertically(-1) &&
                            (offset > 0f || isEligible())
                        val collapse = dy < 0f && offset > 0f
                        if (pullDown || collapse) {
                            dragging = true
                            lastY = e.rawY
                            rv.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging && offset > 0f && animator == null) {
                    settle()
                }
            }
        }
        return dragging
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dy = e.rawY - lastY
                lastY = e.rawY
                val slot = slot()
                if (dy > 0f) {
                    if (offset <= 0f && rv.canScrollVertically(-1)) {
                        // 同一次手势里先往上收起按钮、把列表滚了下去，又往回拉：先把列表滚回顶部，
                        // 到顶之后才拉出按钮。否则按钮会停在滚过的列表上方，压住折叠了一半的头部
                        rv.scrollBy(0, -dy.toInt())
                        return
                    }
                    val resistance = if (offset < slot) PULL_RESISTANCE else OVER_PULL_RESISTANCE
                    applyOffset(min(offset + dy * resistance, slot * MAX_PULL))
                } else {
                    val next = offset + dy
                    if (next >= 0f) {
                        applyOffset(next)
                    } else {
                        applyOffset(0f)
                        rv.scrollBy(0, (-next).toInt())
                    }
                }
                val over = offset >= slot * OPEN_THRESHOLD
                if (over != crossedThreshold) {
                    crossedThreshold = over
                    if (over) {
                        rv.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                settle()
            }
        }
    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}

    private fun settle() {
        val slot = slot()
        animateTo(if (offset >= slot * OPEN_THRESHOLD) slot else 0f)
    }

    // ---------------------------------------------------------------- 位移与样式

    private fun animateTo(target: Float) {
        animator?.cancel()
        if (offset == target) {
            animator = null
            applyOffset(target)
            return
        }
        val a = ValueAnimator.ofFloat(offset, target)
        a.duration = SETTLE_MS
        a.interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
        a.addUpdateListener { applyOffset(it.animatedValue as Float) }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (animator === animation) {
                    animator = null
                }
            }
        })
        animator = a
        a.start()
    }

    private fun applyOffset(px: Float) {
        TapSessionRecorder.current?.event("offset") { put("px", px.toDouble()); put("slot", slot().toDouble()) }
        offset = px
        recyclerView.translationY = px
        header?.setPullOffset(px)
        val f = (px / slot()).coerceIn(0f, 1f)
        button.visibility = if (px > 0.5f) View.VISIBLE else View.INVISIBLE
        button.alpha = f * f
        val scale = 0.86f + 0.14f * f
        button.scaleX = scale
        button.scaleY = scale
        button.translationY = (1f - f) * -APPEAR_SHIFT_DP * density
        onOffsetChanged(px)
    }

    /** 空位高度：按钮（含提示行）加上下留白。 */
    private fun slot(): Float {
        return measuredButtonHeight() + (TOP_GAP_DP + BOTTOM_GAP_DP) * density
    }

    private fun measuredButtonHeight(): Float {
        if (buttonHeight < 0f) {
            button.measure(
                View.MeasureSpec.makeMeasureSpec(container.width.coerceAtLeast(1), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            buttonHeight = button.measuredHeight.toFloat()
        }
        return buttonHeight
    }

    private fun buttonTop(): Float {
        val rect = Rect(0, 0, actionbar.width, actionbar.height)
        container.offsetDescendantRectToMyCoords(actionbar, rect)
        return rect.bottom + TOP_GAP_DP * density
    }

    private fun positionButton() {
        val lp = button.layoutParams as FrameLayout.LayoutParams
        val top = buttonTop().toInt() - container.paddingTop
        if (lp.topMargin != top) {
            lp.topMargin = top
            button.layoutParams = lp
        }
    }

    private fun applyStyle() {
        buttonHeight = -1f
        val icon = ContextCompat.getDrawable(context, R.drawable.ic_tap_transfer)!!.mutate()
        val size = (20f * density).toInt()
        icon.setBounds(0, 0, size, size)
        val radius = 20f * density
        if (receiving) {
            val bg = GradientDrawable()
            bg.cornerRadius = radius
            bg.setColor(ContextCompat.getColor(context, R.color.app_chrome_surface_elevated))
            bg.setStroke((1f * density).toInt().coerceAtLeast(1), ContextCompat.getColor(context, R.color.app_chrome_divider))
            pill.background = RippleDrawable(
                ColorStateList.valueOf(ContextCompat.getColor(context, R.color.app_chrome_ripple)), bg, null
            )
            pill.setTextColor(ContextCompat.getColor(context, R.color.app_chrome_on_surface_primary))
            icon.setTint(ContextCompat.getColor(context, R.color.app_chrome_on_surface_secondary))
        } else {
            val accent: ThingBackground = App.defaultAccentBackground
            val bg = GradientDrawable(
                GradientDrawable.Orientation.BL_TR,
                intArrayOf(accent.color, if (accent.mode == ThingBackground.Mode.GRADIENT) accent.endColor else accent.color)
            )
            bg.cornerRadius = radius
            pill.background = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), bg, null)
            pill.setTextColor(0xFFFFFFFF.toInt())
            icon.setTint(0xFFFFFFFF.toInt())
        }
        pill.elevation = if (receiving) 0f else 2f * density
        pill.setCompoundDrawablesRelative(icon, null, null, null)
    }

    private companion object {
        const val TOP_GAP_DP = 10f
        const val BOTTOM_GAP_DP = 14f
        const val APPEAR_SHIFT_DP = 10f

        /** 拉过空位的这个比例松手就停在打开的位置。 */
        const val OPEN_THRESHOLD = 0.7f

        /** 手指移动与列表下移的比例：拉起来有阻力，看得出是在「拉」而不是在滚动。 */
        const val PULL_RESISTANCE = 0.55f
        const val OVER_PULL_RESISTANCE = 0.2f
        const val MAX_PULL = 1.5f
        const val SETTLE_MS = 260L
    }
}
