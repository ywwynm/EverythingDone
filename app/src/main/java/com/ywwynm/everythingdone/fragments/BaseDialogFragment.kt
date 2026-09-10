package com.ywwynm.everythingdone.fragments

import android.app.Dialog
import android.content.Context
import android.graphics.Outline
import android.graphics.PointF
import android.os.Bundle
import android.os.SystemClock
import android.view.ContextThemeWrapper
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
import android.widget.TextView

import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.views.particledismiss.DialogDimLayer
import com.ywwynm.everythingdone.views.particledismiss.ParticleDismissController
import com.ywwynm.everythingdone.views.particledismiss.ParticleDismissStartGate

/**
 * Created by ywwynm on 2015/9/29.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * A subclass of [DialogFragment] without dialog title
 */
abstract class BaseDialogFragment : DialogFragment() {

    companion object {
        /** 档位 bit0：出现（凝聚）动画。 */
        const val PARTICLE_ANIMATION_SHOW_BIT = 1

        /** 档位 bit1：消失（消散）动画。 */
        const val PARTICLE_ANIMATION_DISMISS_BIT = 2

        /** 默认档位：出现与消失都启用。 */
        const val PARTICLE_ANIMATION_DEFAULT = 3

        /** 读用户设置的粒子动画档位（设置页实时写入，此处实时读取）。 */
        fun particleAnimationMode(context: Context): Int =
            context.getSharedPreferences(Def.Meta.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getInt(Def.Meta.KEY_DIALOG_PARTICLE_ANIMATION, PARTICLE_ANIMATION_DEFAULT)
    }

    @JvmField
    protected var mContentView: View? = null

    /** 进程/配置重建恢复的实例不重播凝聚出现动画。 */
    private var recreatedFromSavedState = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recreatedFromSavedState = savedInstanceState != null
        setStyle(STYLE_NO_TITLE, R.style.EverythingDoneTheme_Dialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val themedInflater = inflater.cloneInContext(
            dialog?.context
                ?: ContextThemeWrapper(requireContext(), R.style.EverythingDoneTheme_Dialog)
        )
        mContentView = themedInflater.inflate(getLayoutResource(), container, false)
        installRoundedOutline(mContentView)
        installCompactDialogButtonRipples(mContentView)
        return mContentView
    }

    @LayoutRes
    protected abstract fun getLayoutResource(): Int

    protected open fun getDialogWindowWidthPx(): Int = ViewGroup.LayoutParams.WRAP_CONTENT

    @Suppress("UNCHECKED_CAST")
    protected fun <T : View?> f(view: View?, @IdRes id: Int): T {
        return view!!.findViewById<View>(id) as T
    }

    protected fun <T : View?> f(@IdRes id: Int): T {
        return f(mContentView, id)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // 与 super.onCreateDialog 唯一的差别是 dialog 实现类，主题、样式仍由 setStyle 决定
        val dialog = GestureAnchoredDialog(requireContext(), theme, useParticleDismiss())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        return dialog
    }

    /**
     * 是否在 dismiss 时播放粒子消散动画（见 docs/features/dialog-particle-dismiss/）。
     * AlertDialogFragment 样板验收后于 2026-08-26 铺开为默认开启；个别 Dialog 需
     * 关闭时 override 返回 false。含 SurfaceView/TextureView 的 Dialog（音频播放/
     * 录制等）由 ParticleDismissController 在运行时检测并自动降级为普通退出，
     * 无需在此关闭。
     */
    protected open fun useParticleDismiss(): Boolean = true

    override fun onStart() {
        super.onStart()
        val dialog = dialog ?: return
        dialog.window?.setBackgroundDrawable(
            ContextCompat.getDrawable(dialog.context, R.drawable.bg_app_chrome_surface_elevated_rounded)
        )
        installRoundedOutline(dialog.window?.decorView)
        dialog.window?.setLayout(
            getDialogWindowWidthPx(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        // 双保险：主题已声明禁用系统 dim，个别机型/解析路径若仍带上
        // FLAG_DIM_BEHIND，会与接管暗层叠加导致 show 期偏黑（dismiss 拦截后
        // 系统 dim 随 window 消失、亮度跳变）。主题生效时本调用是 no-op。
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        // 背景暗层全程由应用接管（主题已禁用系统 dim）：show 时淡入，dismiss
        // 时按路径淡出——单一图层无跨窗口交接，关闭瞬间不会闪烁
        val gestureDialog = dialog as? GestureAnchoredDialog ?: return
        if (useParticleDismiss() && particleAnimationMode(gestureDialog.context) and PARTICLE_ANIMATION_DISMISS_BIT != 0) {
            ParticleDismissController.warmDismissModel(gestureDialog.context)
        }
        if (gestureDialog.dimLayer == null) {
            activity?.let { gestureDialog.dimLayer = DialogDimLayer.attach(it) }
        }

        // 凝聚出现动画（约 320ms）：受子类开关与用户设置档位（bit0 = 出现）
        // 双重控制；重建恢复的实例不重播。窗口动画不置零：凝聚以 alpha=0
        // 隐藏面板、窗口全程正常显示，enter 动画（主题淡入或子类的底部滑入）
        // 在透明期内照常播完，不会挂起到凝聚结束才播（置零的旧方案会被子类
        // onStart 在 super 之后 setWindowAnimations 覆盖——底部面板闪烁的
        // 2026-08-26 根因；且会连带吃掉"仅出现时"档位下的窗口退出动画）
        if (useParticleDismiss() && !recreatedFromSavedState &&
            !gestureDialog.condenseAttempted &&
            particleAnimationMode(gestureDialog.context) and PARTICLE_ANIMATION_SHOW_BIT != 0
        ) {
            gestureDialog.condenseAttempted = true
            ParticleDismissController.startCondense(gestureDialog) { overlay ->
                gestureDialog.condenseOverlay = overlay
            }
        }
    }

    private fun installRoundedOutline(view: View?) {
        view ?: return
        val radius = view.resources.getDimension(R.dimen.app_chrome_dialog_popup_corner_radius)
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radius)
            }
        }
    }

    private fun installCompactDialogButtonRipples(view: View?) {
        view ?: return
        if (view is TextView && isCompactDialogButton(view)) {
            BackgroundUtil.installAppChromeDialogActionButton(view, view.context)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                installCompactDialogButtonRipples(view.getChildAt(i))
            }
        }
    }

    private fun isCompactDialogButton(view: TextView): Boolean {
        if (view.id == View.NO_ID) return false
        val entryName = try {
            view.resources.getResourceEntryName(view.id)
        } catch (_: Exception) {
            return false
        }
        if (!entryName.contains("_as_bt")) return false

        val lp = view.layoutParams ?: return false
        if (lp.width != ViewGroup.LayoutParams.WRAP_CONTENT) return false

        return true
    }

    override fun show(manager: FragmentManager, tag: String?) {
        if (!isAdded) {
            try {
                super.show(manager, tag)
            } catch (_: IllegalStateException) {
                // ignore this
            }
        }
    }

    override fun dismiss() {
        dismissViaDialog()
    }

    override fun dismissAllowingStateLoss() {
        dismissViaDialog()
    }

    /**
     * 按钮路径闪烁根因（2026-08-26，用户按钮关闭 100% 复现）：fragment 的
     * dismissInternal 先调 Dialog.dismiss()（被粒子流程拦截、真实 dismiss
     * 推迟到快照层上屏后），但它**接着**提交的 remove 事务只隔一条主线程
     * 消息就执行 onDestroyView、把内容 view 从仍在屏的窗口里摘空——而
     * PixelCopy 要 1–2 帧后才回调：画面上 dialog 先被摘空一瞬、快照层再
     * "重现"。点外部关闭无此问题：cancel → Dialog.dismiss 链里 fragment
     * 的移除发生在真实 dismiss 之后。
     *
     * 修复：dismiss 一律只走 Dialog.dismiss()（粒子拦截点），fragment 的
     * 移除由真实 dismiss 的 onDismiss 回调走 dismissInternal 自动补全——
     * 与点外部关闭完全同链。onDestroyView 对 Dialog.dismiss 的重入由
     * particleFlowPending 与 Dialog 自身的幂等吸收。dialog 不在时退回
     * 原始 dismissAllowingStateLoss（保持不抛 IllegalStateException 的
     * 既有语义）。
     */
    private fun dismissViaDialog() {
        val d = dialog
        if (d != null && d.isShowing) {
            d.dismiss()
        } else {
            super.dismissAllowingStateLoss()
        }
    }
}

/**
 * 让「点击 dialog 外部取消」只在手势**起点**也落在 dialog 之外时才成立。
 *
 * 系统的 `Window.shouldCloseOnTouch` 只看 [MotionEvent.ACTION_UP] 的落点，不关心手势
 * 从哪里开始：从 dialog 内部没有控件消费 touch 的位置（空白区、纯展示的文本等）按下，
 * 手指滑到 dialog 外抬起，也会被判定成「点击外部」而取消 dialog。这里记住手势起点，
 * 起点在 dialog 内时整段手势都不参与取消判定。
 *
 * 只拦截取消判定这一条路径，其余行为不变：back 键取消、[setCancelable]、
 * [setCanceledOnTouchOutside]、cancel/dismiss 回调、dialog 内控件的事件分发都照原样走。
 *
 * 继承 [ComponentDialog] 而不是 [Dialog]：androidx 的 [DialogFragment.onCreateDialog] 默认返回
 * 前者，它带的 `OnBackPressedDispatcher` 是返回键与 predictive back 的落点，不能退化掉。
 */
/** 抬手之后多久之内的触点仍算作「这一次关闭的触点」。一次 post 的点击远小于这个值。 */
private const val RECENT_TOUCH_MS = 300L

private class GestureAnchoredDialog(
    context: Context, themeResId: Int,
    private val particleDismissEnabled: Boolean
) : ComponentDialog(context, themeResId) {

    /** 当前手势的起点是否在 dialog 之外。收不到 ACTION_DOWN 时保持系统默认行为 */
    private var downOutside = true

    /**
     * 当前正在分发、且可能直接触发 dismiss 的触点。仅在 dispatchTouchEvent
     * 调用栈内有效；返回、异步完成和代码关闭不会误用更早的历史触点。
     */
    private var dismissTouchInDispatch: PointF? = null

    /**
     * 最近一次抬手的位置与时刻。
     *
     * 点「取消」这类按钮关闭时，[dismissTouchInDispatch] 是空的：`View.onTouchEvent` 对
     * ACTION_UP 走的是 `post(mPerformClick)`，`OnClickListener` 落在**后一条消息**里，那时
     * dispatchTouchEvent 的 finally 早已把它清掉。2026-09-03 真机实测：点按钮关闭与按返回键
     * 关闭的画面完全同型、最早释放质心只差 12 px（面板 1200×587），也就是说方向退回了默认值，
     * 而这正是应用里最常见的关闭路径。
     *
     * 抬手之后的一小段时间里保留这个点，超过 [RECENT_TOUCH_MS] 就当作与本次关闭无关
     * （返回键、异步完成、代码关闭因此仍然拿不到历史触点）。
     */
    private var lastUpTouch: PointF? = null
    private var lastUpTouchUptimeMs = 0L

    /** 返回键与完成的返回手势不能沿用刚才滑动或点击的历史触点。 */
    private var dismissFromBack = false

    init {
        onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                dismissFromBack = true
                lastUpTouch = null
                isEnabled = false
                try {
                    // 继续交给 ComponentDialog 的 fallback，保留 setCancelable(false)
                    // 与 cancel 回调；子内容后来注册的返回回调仍有更高优先级。
                    onBackPressedDispatcher.onBackPressed()
                } finally {
                    isEnabled = true
                    dismissFromBack = false
                }
            }
        })
    }

    /** 应用接管的背景暗层，show 时由 fragment 注入；随 dismiss 路径淡出 */
    var dimLayer: DialogDimLayer? = null

    /** 凝聚出现动画只播一次（onStart 可能因可见性变化多次回调） */
    var condenseAttempted = false

    /** 进行中的凝聚动画层：dismiss 拦截时必须先释放，防止其残留凝固在屏上 */
    var condenseOverlay: com.ywwynm.everythingdone.views.particledismiss.ParticleDismissOverlay? = null

    /**
     * 所有消失路径的汇聚点：代码 dismiss（fragment 的 dismissInternal /
     * onDestroyView 都会调到 Dialog.dismiss）、back 键与点击外部（cancel 内部
     * 也调 dismiss）。粒子动画接管成功后把窗口退出动画置空、暗层按动画等长
     * 淡出（前慢后快，粒子浓密期背景保持暗），并把真实 dismiss 推迟到动画层
     * 实际上屏后（约 1–2 帧、含 100ms 兜底）——先就位、后揭开，内容层才无缝。
     * 普通路径暗层短淡出。window 自身无系统 dim（主题已禁用），移除时无可
     * 过渡之物。
     */
    /** 粒子流程已接管、真实 dismiss 等待异步回调统一执行 */
    private var particleFlowPending = false

    override fun dismiss() {
        // 凝聚尚未收尾就 dismiss（如检查更新的 loading 快速完成）：先释放凝聚
        // 动画层，否则它的末帧会残留在界面上（"Dialog 凝固"）
        condenseOverlay?.release()
        condenseOverlay = null

        // fragment 的关闭链会两次调到 Dialog.dismiss()（dismissInternal 与
        // onDestroyView 各一次）：粒子流程接管期间的重入必须忽略，否则重入的
        // 这次立即移除 window，异步抓图回调时 decor 已 detach、动画被放弃
        // （2026-08-26 探针定位）
        if (particleFlowPending) return

        val modeAllows = BaseDialogFragment.particleAnimationMode(context) and
            BaseDialogFragment.PARTICLE_ANIMATION_DISMISS_BIT != 0
        if (particleDismissEnabled && modeAllows && !particleDismissAttempted) {
            particleDismissAttempted = true
            val startGate = ParticleDismissStartGate {
                particleFlowPending = false
                completeParticleDismiss()
            }
            val started = ParticleDismissController.start(
                dialog = this,
                touchInWindow = dismissTouchAnchor(),
                onAnimationStarted = Runnable {
                    dimLayer?.fadeOutAndDetach(
                        ParticleDismissController.dismissAnimatorDurationMs(),
                        android.animation.TimeInterpolator { progress ->
                            val p = ((progress - .18f) / .55f).coerceIn(0f, 1f)
                            p * p
                        }
                    )
                    startGate.markAnimationStarted()
                },
                onOverlayShown = Runnable {
                    startGate.markOverlayShown()
                }
            )
            if (started) {
                particleFlowPending = true
                window?.setWindowAnimations(0)
                return
            }
        }
        dimLayer?.fadeOutAndDetach(DialogDimLayer.FADE_OUT_MS)
        super.dismiss()
    }

    private fun completeParticleDismiss() {
        super.dismiss()
    }

    private var particleDismissAttempted = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // View.OnClickListener、点外 cancel 等关闭行为会在 super 的事件分发
        // 调用栈内同步发生；只在这段调用栈暴露触点，天然排除返回键和稍后的
        // 自动 dismiss。保存/恢复旧值使极少见的嵌套分发仍保持正确。
        val previousTouch = dismissTouchInDispatch
        dismissTouchInDispatch = if (ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            null
        } else {
            PointF(ev.x, ev.y)
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP -> {
                lastUpTouch = PointF(ev.x, ev.y)
                lastUpTouchUptimeMs = SystemClock.uptimeMillis()
            }
            MotionEvent.ACTION_CANCEL -> lastUpTouch = null
        }
        return try {
            super.dispatchTouchEvent(ev)
        } finally {
            dismissTouchInDispatch = previousTouch
        }
    }

    /** 返回使用左上虚拟触点；其他关闭先取分发中或刚抬手的真实触点。 */
    private fun dismissTouchAnchor(): PointF? {
        if (dismissFromBack) {
            val decor = window?.decorView ?: return null
            val reach = maxOf(decor.width, decor.height).toFloat()
            return PointF(decor.width / 2f - reach, decor.height / 2f - reach)
        }
        dismissTouchInDispatch?.let { return it }
        val up = lastUpTouch ?: return null
        val elapsed = SystemClock.uptimeMillis() - lastUpTouchUptimeMs
        return if (elapsed in 0..RECENT_TOUCH_MS) up else null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> downOutside = isOutOfBounds(event)
            // window 非 modal 时，落在外部的按下会以 ACTION_OUTSIDE 到来，起点必然在外
            MotionEvent.ACTION_OUTSIDE -> downOutside = true
        }
        if (!downOutside) return false
        return super.onTouchEvent(event)
    }

    /** 与 framework `Window.isOutOfBounds` 等价：event 坐标以 decorView 为原点，允许 slop 误差 */
    private fun isOutOfBounds(event: MotionEvent): Boolean {
        val decorView = window?.decorView ?: return true
        val slop = ViewConfiguration.get(context).scaledWindowTouchSlop
        val x = event.x.toInt()
        val y = event.y.toInt()
        return x < -slop || y < -slop ||
                x > decorView.width + slop || y > decorView.height + slop
    }
}
