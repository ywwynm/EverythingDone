package com.ywwynm.everythingdone.taptransfer.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.CheckBox
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.fragments.BaseDialogFragment
import com.ywwynm.everythingdone.taptransfer.TapTransfer

/** 查看期间持续刷新；打开同 Activity 的对话框不会退出收发状态。 */
class TapDiagnosticsDialog : BaseDialogFragment() {
    private var text: TextView? = null
    private val refresh = object : Runnable {
        override fun run() {
            val view = text ?: return
            val report = TapDiagnostics.report(view.context)
            if (view.text.toString() != report) view.text = report
            view.postDelayed(this, 600)
        }
    }

    override fun getLayoutResource(): Int = R.layout.dialog_tap_diagnostics
    override fun useParticleDismiss(): Boolean = false
    override fun getDialogWindowWidthPx(): Int =
        minOf(resources.displayMetrics.widthPixels - (32 * resources.displayMetrics.density).toInt(),
            (600 * resources.displayMetrics.density).toInt())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        super.onCreateView(inflater, container, savedInstanceState)
        if (!TapDiagnostics.enabled) {
            mContentView?.visibility = View.GONE
            dismissAllowingStateLoss()
            return mContentView
        }
        text = f(R.id.tap_diagnostic_report)
        f<CheckBox>(R.id.tap_diagnostic_receiver_group)!!.apply {
            isChecked = TapTransfer.preferReceiverGroup(requireContext())
            setOnCheckedChangeListener { _, checked -> TapTransfer.setPreferReceiverGroup(requireContext(), checked) }
        }
        f<View>(R.id.tap_diagnostic_scroll)!!.layoutParams.height =
            (resources.displayMetrics.heightPixels * 0.45f).toInt()
        f<View>(R.id.tap_diagnostic_copy)!!.setOnClickListener {
            val context = requireContext()
            val report = TapDiagnostics.report(context)
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText(getString(R.string.tap_diagnostic_title), report))
            Toast.makeText(context, R.string.tap_diagnostic_copied, Toast.LENGTH_SHORT).show()
        }
        f<View>(R.id.tap_diagnostic_close)!!.setOnClickListener { dismiss() }
        f<View>(R.id.tap_diagnostic_share)!!.setOnClickListener { TapDiagnosticsSharer.share(requireActivity()) }
        return mContentView
    }

    override fun onStart() { super.onStart(); refresh.run() }
    override fun onStop() { text?.removeCallbacks(refresh); super.onStop() }
    override fun onDestroyView() { text?.removeCallbacks(refresh); text = null; super.onDestroyView() }

    companion object {
        fun bind(button: View, activity: FragmentActivity) {
            button.visibility = if (TapDiagnostics.enabled) View.VISIBLE else View.GONE
            if (!TapDiagnostics.enabled) {
                button.setOnClickListener(null)
                return
            }
            button.setOnClickListener {
                val fm = activity.supportFragmentManager
                if (TapDiagnostics.enabled && !fm.isStateSaved && fm.findFragmentByTag("TapDiagnostics") == null)
                    TapDiagnosticsDialog().show(fm, "TapDiagnostics")
            }
        }
    }
}
