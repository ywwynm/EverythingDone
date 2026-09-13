package com.ywwynm.everythingdone.activities

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.ywwynm.everythingdone.fragments.ActivityContentDialogFragment
import com.ywwynm.everythingdone.views.particledismiss.ParticleDismissController

/** 浮动配置／通知保留 Activity 业务入口，用薄 DialogFragment 承载原有内容。 */
abstract class ParticleDialogContentActivity : AppCompatActivity() {
    protected open val useParticleContentDialog: Boolean = false
    internal open val particleDialogCanceledOnTouchOutside: Boolean = true
    private var dialogContent: View? = null
    private var closingContent = false

    internal fun obtainParticleDialogContent(): View {
        dialogContent?.let { return it }
        val container = window.decorView.findViewById<ViewGroup>(android.R.id.content)
        val content = checkNotNull(container.getChildAt(0))
        container.removeView(content)
        if (content.layoutParams.height == ViewGroup.LayoutParams.MATCH_PARENT) {
            content.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        dialogContent = content
        return content
    }

    override fun <T : View?> findViewById(id: Int): T =
        dialogContent?.findViewById<T>(id) ?: super.findViewById(id)

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        if (useParticleContentDialog) {
            // 部分系统仍给 Dialog 主题的透明 Activity 补 DIM_BEHIND；暗层只由内部弹窗管理。
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setDimAmount(0f)
            window.setWindowAnimations(0)
        }
        if (useParticleContentDialog && !isFinishing &&
            supportFragmentManager.findFragmentByTag(CONTENT_TAG) == null) {
            ActivityContentDialogFragment().show(supportFragmentManager, CONTENT_TAG)
        }
    }

    override fun finish() {
        if (useParticleContentDialog) {
            val fragment = supportFragmentManager.findFragmentByTag(CONTENT_TAG) as? ActivityContentDialogFragment
            if (!closingContent && fragment?.dialog?.isShowing == true) {
                closingContent = true
                fragment.dismiss()
                return
            }
            if (closingContent && fragment?.dialog?.isShowing == true) return
            if (ParticleDismissController.finishAfterAnimations(this) { super.finish() }) return
        }
        super.finish()
    }

    companion object { private const val CONTENT_TAG = "activity-content-dialog" }
}
