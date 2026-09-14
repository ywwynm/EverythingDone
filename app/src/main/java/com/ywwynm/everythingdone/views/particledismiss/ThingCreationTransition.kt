package com.ywwynm.everythingdone.views.particledismiss

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewAnimationUtils
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import androidx.core.view.doOnPreDraw
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.ThingAnimationPreferences
import com.ywwynm.everythingdone.utils.KeyboardUtil
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.views.reveal.ShiningBorder
import kotlin.math.hypot
import kotlin.math.max

/** 保留详情 Activity；一次性首页快照承接窗口切换，动画直接交给已布局的编辑页。 */
internal class ThingCreationTransition private constructor(
    private val activity: Activity,
    private val content: View,
    private val thingBackground: ThingBackground,
    pending: Pending
) {
    private val mode = pending.mode
    private val sourceX = pending.sourceX
    private val sourceY = pending.sourceY
    private val host = activity.window.decorView as ViewGroup
    private val input = activity.currentFocus?.takeIf { it.onCheckIsTextEditor() }
    private val originalSoftInputMode = activity.window.attributes.softInputMode
    private val backdrop = ImageView(activity).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        setImageBitmap(pending.bitmap)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    private val touchGuard = View(activity).apply {
        isClickable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    private var particle: ParticleDismissOverlay? = null
    private var reveal: Animator? = null
    private var border: ShiningBorder? = null
    private var closed = false
    private val layoutObserver = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        if (oldRight > oldLeft && oldBottom > oldTop &&
            (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom)) finish()
    }

    private fun start() {
        // 创建页默认聚焦正文。首次窗口获得焦点时先保持键盘隐藏，避免首页
        // 承接快照尚在显示就弹出键盘，并在粒子捕获期间改变可用布局高度。
        activity.window.setSoftInputMode((originalSoftInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE.inv()) or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        content.addOnLayoutChangeListener(layoutObserver)
        content.alpha = 0f
        host.addView(backdrop, 0, ViewGroup.LayoutParams(-1, -1))
        host.addView(touchGuard, ViewGroup.LayoutParams(-1, -1))
        if (mode == ThingAnimationPreferences.PARTICLE) {
            content.alpha = 1f
            val started = ParticleDismissController.appearContent(activity, activity.window, content,
                appearanceDirection = 315f, roundSnapshot = false,
                onAppearanceFinished = { finish(true) }) { particle = it }
            if (!started) finish(true)
        } else content.doOnPreDraw {
            if (closed) return@doOnPreDraw
            when (mode) {
                ThingAnimationPreferences.BORDER -> startBorder()
                else -> startRipple()
            }
        }
    }

    /** 揭示圆以首页新建按钮为圆心；没有来源坐标时退回内容右下角。 */
    private fun startRipple() {
        content.alpha = 1f
        val w = content.width.toFloat()
        val h = content.height.toFloat()
        var cx = w
        var cy = h
        if (sourceX.isFinite() && sourceY.isFinite()) {
            val location = IntArray(2)
            content.getLocationInWindow(location)
            cx = (sourceX - location[0]).coerceIn(0f, w)
            cy = (sourceY - location[1]).coerceIn(0f, h)
        }
        reveal = ViewAnimationUtils.createCircularReveal(content, cx.toInt(), cy.toInt(),
            0f, hypot(max(cx, w - cx), max(cy, h - cy))).apply {
            duration = 600
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { finish(true) }
            })
            start()
        }
    }

    private fun startBorder() {
        border = ShiningBorder(activity).apply {
            // 全屏入口从右下角出发：底边向左 → 左边向上 → 顶边向右 → 右边向下。
            setStartCorner(ShiningBorder.START_BOTTOM_RIGHT)
            val pure = thingBackground.mode == ThingBackground.Mode.PURE
            setShiningColor(if (pure) thingBackground.color else thingBackground.endColor)
            setOrdinaryColor(if (pure) DisplayUtil.getLightColor(thingBackground.color, activity) else thingBackground.color)
            setStrokeWidth(8f * activity.resources.displayMetrics.density)
            setAnimationDuration(BORDER_DURATION)
            setOnAnimationEndListener(object : ShiningBorder.OnAnimationEndListener {
                override fun onAnimationEnd(border: ShiningBorder) { enlargeContent() }
            })
            host.addView(this, ViewGroup.LayoutParams(-1, -1))
            doOnPreDraw { if (!closed) startAnimation() }
        }
    }

    /**
     * 光带走完（暗段余光已退完）后，内容以右下角为轴从 [ENLARGE_SCALE] 放大到 1。
     * 内容从第一帧起就完全不透明，没有淡入；期间首页快照仍在底层，放大结束才撤快照并恢复键盘。
     */
    private fun enlargeContent() {
        if (closed) return
        removeBorder()
        content.animate().cancel()
        content.pivotX = content.width.toFloat()
        content.pivotY = content.height.toFloat()
        content.scaleX = ENLARGE_SCALE
        content.scaleY = ENLARGE_SCALE
        content.alpha = 1f
        content.animate().scaleX(1f).scaleY(1f)
            .setDuration(ENLARGE_DURATION)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { finish(true) }
            .start()
    }

    private fun removeBorder() {
        border?.let { it.setOnAnimationEndListener(null); it.stopAnimation(); host.removeView(it) }
        border = null
    }

    fun finish(enterCompleted: Boolean = false) {
        if (closed) return
        closed = true
        content.removeOnLayoutChangeListener(layoutObserver)
        ParticleDismissController.cancelAppearance(content)
        particle?.release(); particle = null
        reveal?.cancel(); reveal = null
        removeBorder()
        // 任何中止路径都必须把放大动画留下的缩放和透明度复位。
        content.animate().cancel()
        content.scaleX = 1f
        content.scaleY = 1f
        content.alpha = 1f
        host.removeView(touchGuard)
        host.removeView(backdrop)
        backdrop.setImageDrawable(null)
        activity.window.setSoftInputMode(originalSoftInputMode)
        if (enterCompleted && input != null) content.post {
            if (!activity.isFinishing && !activity.isDestroyed && activity.hasWindowFocus()) {
                KeyboardUtil.showKeyboard(activity.window, input)
            }
        }
    }

    private data class Pending(val bitmap: Bitmap, val mode: Int, val sourceX: Float, val sourceY: Float)

    companion object {
        const val EXTRA_TOKEN = "thing_creation_transition_token"

        /** 全屏光带时长；内容放大动画在其之后再跑 [ENLARGE_DURATION]。 */
        private const val BORDER_DURATION = 1290L
        private const val ENLARGE_DURATION = 216L
        private const val ENLARGE_SCALE = 0.84f

        private val pendingTransitions = mutableMapOf<Long, Pending>()
        private val main = Handler(Looper.getMainLooper())

        /**
         * [sourceX]/[sourceY] 为触发控件中心的窗口坐标，涟漪档用它作为揭示圆心；
         * 传 [Float.NaN] 表示没有来源坐标。
         */
        fun launch(activity: Activity, intent: Intent, sourceX: Float, sourceY: Float,
                   onLaunched: (Boolean) -> Unit) {
            fun open(bitmap: Bitmap?) {
                if (activity.isFinishing || activity.isDestroyed || !activity.hasWindowFocus()) {
                    bitmap?.recycle(); onLaunched(false); return
                }
                if (bitmap != null) {
                    val token = SystemClock.elapsedRealtimeNanos()
                    pendingTransitions[token] =
                        Pending(bitmap, ThingAnimationPreferences.creation(activity), sourceX, sourceY)
                    intent.putExtra(EXTRA_TOKEN, token)
                    // 启动失败、进程重建等情况不能永久持有首页画面。
                    main.postDelayed({ pendingTransitions.remove(token)?.bitmap?.recycle() }, 10_000)
                }
                activity.startActivityForResult(intent, Def.Communication.REQUEST_ACTIVITY_DETAIL,
                    ActivityOptions.makeCustomAnimation(activity, 0, 0).toBundle())
                onLaunched(true)
            }
            if (!ValueAnimator.areAnimatorsEnabled()) open(null)
            else ParticleDismissController.captureContent(activity.window.decorView, ::open)
        }

        fun attach(activity: Activity, content: View, background: ThingBackground): ThingCreationTransition? {
            val token = activity.intent.getLongExtra(EXTRA_TOKEN, 0)
            activity.intent.removeExtra(EXTRA_TOKEN)
            val pending = pendingTransitions.remove(token) ?: return null
            if (!ValueAnimator.areAnimatorsEnabled() || activity.isFinishing) {
                pending.bitmap.recycle(); return null
            }
            return ThingCreationTransition(activity, content, background, pending).also { it.start() }
        }
    }
}
