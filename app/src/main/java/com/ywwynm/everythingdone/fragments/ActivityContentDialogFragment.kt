package com.ywwynm.everythingdone.fragments

import android.content.DialogInterface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.ywwynm.everythingdone.activities.ParticleDialogContentActivity

/** Activity 继续管理业务、结果和控件，窗口和动画统一交给 BaseDialogFragment。 */
class ActivityContentDialogFragment : BaseDialogFragment() {
    private var contentWidthPx = ViewGroup.LayoutParams.WRAP_CONTENT
    override fun getLayoutResource(): Int = 0
    override fun createDialogContent(inflater: LayoutInflater, container: ViewGroup?): View =
        (requireActivity() as ParticleDialogContentActivity).obtainParticleDialogContent().also {
            // Dialog.setContentView 会重写根 View 的 LayoutParams，必须在交给窗口前保留宽度。
            contentWidthPx = it.layoutParams?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT
        }

    override fun getDialogWindowWidthPx(): Int {
        val width = contentWidthPx
        val available = resources.displayMetrics.widthPixels - (32 * resources.displayMetrics.density).toInt()
        return if (width > 0) minOf(width, available) else width
    }

    override fun onStart() {
        super.onStart()
        dialog?.setCanceledOnTouchOutside((requireActivity() as ParticleDialogContentActivity).particleDialogCanceledOnTouchOutside)
    }

    override fun onDismiss(dialog: DialogInterface) {
        val host = activity
        super.onDismiss(dialog)
        if (host?.isChangingConfigurations != true) host?.finish()
    }
}
