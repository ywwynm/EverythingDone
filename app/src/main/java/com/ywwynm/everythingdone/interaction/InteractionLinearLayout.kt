package com.ywwynm.everythingdone.interaction

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.LinearLayout

/** PopupWindow 没有 Activity 的 Window.Callback，使用内容根节点保留点击来源。 */
class InteractionLinearLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    var inputDispatcher: UiInteractions.InputDispatcher? = null
    override fun dispatchTouchEvent(event: MotionEvent): Boolean =
        inputDispatcher?.dispatch(event) { super.dispatchTouchEvent(it) }
            ?: super.dispatchTouchEvent(event)
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        inputDispatcher?.key(event) { super.dispatchKeyEvent(it) } ?: super.dispatchKeyEvent(event)
}
