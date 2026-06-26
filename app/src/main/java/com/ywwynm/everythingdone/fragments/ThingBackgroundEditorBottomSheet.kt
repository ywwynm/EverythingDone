@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.fragments

import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView

import androidx.core.widget.NestedScrollView

import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.views.ThingBackgroundEditor

/**
 * 详情页改变记事颜色的底部面板。是平台 [android.app.DialogFragment]（经 [BaseDialogFragment]，
 * 平台 fragmentManager 显示），靠窗口 gravity 固定在底部——**不是**可拖拽的 Material BottomSheet，
 * 因此打开即按内容全展开，不需要用户上滑。内容靠 `ScrollAwareColumn` 在空间不足(键盘弹出)时让
 * 中间编辑器内部滚动，标题与取消/确定固定。取消=放弃(回到打开时颜色)，确定=提交。
 */
class ThingBackgroundEditorBottomSheet : BaseDialogFragment() {

    private var editor: ThingBackgroundEditor? = null
    private var scroll: NestedScrollView? = null
    private var sepTop: View? = null
    private var sepBottom: View? = null
    private var confirmBt: TextView? = null

    private var initialBackground: ThingBackground? = null
    private var confirmed = false

    private var onBackgroundChangedListener: ((ThingBackground) -> Unit)? = null
    private var onPickFromWorldListener: ((slot: Int) -> Unit)? = null
    private var onResultListener: ((confirmed: Boolean) -> Unit)? = null

    fun setInitialBackground(bg: ThingBackground?) { initialBackground = bg }
    fun setOnBackgroundChangedListener(l: ((ThingBackground) -> Unit)?) { onBackgroundChangedListener = l }
    fun setOnPickFromWorldListener(l: ((slot: Int) -> Unit)?) { onPickFromWorldListener = l }
    /** 关闭时回调一次：confirmed=true 表示用户点了确定，false 表示取消/返回/点外部。 */
    fun setOnResult(l: ((confirmed: Boolean) -> Unit)?) { onResultListener = l }

    /** 从世界取色回流。 */
    fun applyWorldColor(slot: Int, color: Int) {
        editor?.applyWorldColor(slot, color)
    }

    override fun getLayoutResource(): Int = R.layout.fragment_thing_background_editor

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)

        val ed: ThingBackgroundEditor = f(R.id.editor_tbe_sheet)!!
        editor = ed
        scroll = f(R.id.scroll_tbe_sheet)
        sepTop = f(R.id.sep_tbe_top)
        sepBottom = f(R.id.sep_tbe_bottom)
        confirmBt = f(R.id.tv_confirm_as_bt_tbe)

        ed.setTitleView(f(R.id.tv_tbe_sheet_title))
        ed.setBackground(initialBackground)
        tintConfirm(ed.getThingBackground())
        ed.onBackgroundChanged = { bg ->
            onBackgroundChangedListener?.invoke(bg)
            tintConfirm(bg)
            updateSeparators()
        }
        ed.onRequestPickFromWorld = { slot -> onPickFromWorldListener?.invoke(slot) }

        f<TextView>(R.id.tv_cancel_as_bt_tbe)!!.setOnClickListener { confirmed = false; dismiss() }
        confirmBt!!.setOnClickListener { confirmed = true; dismiss() }

        scroll?.let { sv ->
            sv.setOnScrollChangeListener(
                NestedScrollView.OnScrollChangeListener { _, _, _, _, _ -> updateSeparators() }
            )
            sv.viewTreeObserver.addOnGlobalLayoutListener { updateSeparators() }
            sv.post { updateSeparators() }
        }

        return mContentView
    }

    private fun tintConfirm(bg: ThingBackground?) {
        bg ?: return
        confirmBt?.let { BackgroundUtil.applyTextBackground(it, bg) }
    }

    private fun updateSeparators() {
        val sv = scroll ?: return
        sepTop?.visibility = if (sv.canScrollVertically(-1)) View.VISIBLE else View.INVISIBLE
        sepBottom?.visibility = if (sv.canScrollVertically(1)) View.VISIBLE else View.INVISIBLE
    }

    override fun getDialogWindowWidthPx(): Int {
        val res = resources
        val margin = res.getDimensionPixelSize(R.dimen.thing_card_outer_spacing)
        val maxW = res.getDimensionPixelSize(R.dimen.thing_card_appearance_max_width)
        return minOf(res.displayMetrics.widthPixels - 2 * margin, maxW)
    }

    override fun onStart() {
        super.onStart()
        val window = dialog?.window ?: return
        window.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        window.setWindowAnimations(R.style.EverythingDoneAnimationBottomPanel)
        val lp = window.attributes
        lp.y = resources.getDimensionPixelSize(R.dimen.thing_card_outer_spacing)
        window.attributes = lp
        // 不压暗背后，否则详情页的 Thing Background 颜色会看起来偏暗、不准确。
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0f)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onResultListener?.invoke(confirmed)
    }

    companion object {
        const val TAG: String = "ThingBackgroundEditorBottomSheet"
    }
}
