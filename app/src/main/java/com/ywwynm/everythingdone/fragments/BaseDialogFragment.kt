package com.ywwynm.everythingdone.fragments

import android.app.Dialog
import android.content.Context
import android.graphics.Outline
import android.os.Bundle
import android.view.ContextThemeWrapper
import androidx.activity.ComponentDialog
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
import android.widget.TextView

import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.utils.BackgroundUtil

/**
 * Created by ywwynm on 2015/9/29.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * A subclass of [DialogFragment] without dialog title
 */
abstract class BaseDialogFragment : DialogFragment() {

    @JvmField
    protected var mContentView: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        val dialog = GestureAnchoredDialog(requireContext(), theme)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        return dialog
    }

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
        super.dismissAllowingStateLoss()
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
private class GestureAnchoredDialog(
    context: Context, themeResId: Int
) : ComponentDialog(context, themeResId) {

    /** 当前手势的起点是否在 dialog 之外。收不到 ACTION_DOWN 时保持系统默认行为 */
    private var downOutside = true

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
