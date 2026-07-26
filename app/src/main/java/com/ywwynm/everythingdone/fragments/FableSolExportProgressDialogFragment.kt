package com.ywwynm.everythingdone.fragments

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.DetailActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.services.FableSolVideoExportService
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.views.GradientRippleDrawable
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolVideoExportBus
import java.io.File

/**
 * 导出进度对话框。
 *
 * 它**不驱动**导出——导出始终在前台服务里跑、通知栏也始终有通知，这个对话框只是同一份
 * 状态的观察者。「在后台运行」只是关掉它；关掉之后导出照常，通知照常。
 *
 * 导出完成时若对话框还开着，就地显示最终规格、文件大小与位置，并把动作切换为
 * 「分享」「添加为附件」。视频在成功返回前已经默认提交到公共相册。
 */
class FableSolExportProgressDialogFragment : BaseDialogFragment() {

    private var mActivity: Activity? = null
    private var mTvTitle: TextView? = null
    private var mTvStatus: TextView? = null
    private var mProgressBar: ProgressBar? = null
    private var mTvSecondary: TextView? = null
    private var mTvPrimary: TextView? = null

    /** 本对话框负责的那一个导出；总线上其它任务的消息一律忽略。 */
    private var mJobId: Long = 0L

    private val mListener: (FableSolVideoExportBus.State) -> Unit = { state ->
        if (state.jobId == mJobId) render(state)
    }

    override fun getLayoutResource(): Int = R.layout.dialog_fablesol_export_progress

    override fun getDialogWindowWidthPx(): Int =
        (320 * resources.displayMetrics.density).toInt()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = super.onCreateView(inflater, container, savedInstanceState) ?: return null
        mActivity = activity
        mJobId = arguments?.getLong(KEY_JOB_ID) ?: 0L
        mTvStatus = f(R.id.tv_fablesol_export_status)
        mProgressBar = f(R.id.pb_fablesol_export)
        mTvSecondary = f(R.id.tv_fablesol_export_secondary_as_bt)
        mTvPrimary = f(R.id.tv_fablesol_export_primary_as_bt)
        mTvTitle = f(R.id.tv_fablesol_export_title)
        applyAccent()
        // 排队中的任务在总线上还没有自己的消息，按"准备中"渲染而不是显示别人的进度。
        render(
            FableSolVideoExportBus.currentFor(mJobId)
                ?: FableSolVideoExportBus.State.Queued(mJobId)
        )
        FableSolVideoExportBus.addListener(mListener)
        return view
    }

    override fun onDestroyView() {
        FableSolVideoExportBus.removeListener(mListener)
        super.onDestroyView()
    }

    /**
     * 与其余对话框一致：文字按钮与进度条都跟随当前记事的强调色，触摸涟漪走同一套渐变波纹。
     * 取不到宿主记事色时回落到应用默认强调色，而不是系统蓝。
     */
    private fun applyAccent() {
        val accent = currentAccentBackground()
        // 标题与其余对话框一致：跟随记事强调色，而不是留在系统默认色上。
        mTvTitle?.let { BackgroundUtil.applyTextBackground(it, accent) }
        mProgressBar?.let { bar ->
            bar.progressTintList = ColorStateList.valueOf(accent.color)
            bar.indeterminateTintList = ColorStateList.valueOf(accent.color)
        }
        val fallback = ContextCompat.getColor(
            requireContext(), R.color.app_chrome_on_surface_strong
        )
        mTvPrimary?.let { primary ->
            BackgroundUtil.applyTextBackground(primary, accent)
            GradientRippleDrawable.applyAccentRipple(primary, accent, fallback)
        }
        mTvSecondary?.let { secondary ->
            GradientRippleDrawable.applyAccentRipple(secondary, accent, fallback)
        }
    }

    /** 完成态的两个动作不分主次，都使用当前记事的完整颜色或渐变。 */
    private fun applyCompletionActionAccent(vararg actions: TextView) {
        val accent = currentAccentBackground()
        val fallback = ContextCompat.getColor(
            requireContext(), R.color.app_chrome_on_surface_strong
        )
        for (action in actions) {
            BackgroundUtil.applyTextBackground(action, accent)
            GradientRippleDrawable.applyAccentRipple(action, accent, fallback)
        }
    }

    private fun render(state: FableSolVideoExportBus.State) {
        val status = mTvStatus ?: return
        val bar = mProgressBar ?: return
        val secondary = mTvSecondary ?: return
        val primary = mTvPrimary ?: return
        when (state) {
            is FableSolVideoExportBus.State.Running -> {
                bar.visibility = View.VISIBLE
                if (state.total > 0 && state.total != Int.MAX_VALUE) {
                    bar.isIndeterminate = false
                    bar.progress = (state.done.toLong() * 1000L / state.total).toInt()
                    status.text = progressText(state)
                } else {
                    bar.isIndeterminate = true
                    status.setText(R.string.fablesol_export_progress_unknown)
                }
                secondary.setText(R.string.fablesol_export_cancel)
                secondary.setOnClickListener {
                    FableSolVideoExportService.cancel(requireContext(), mJobId)
                    dismissAllowingStateLoss()
                }
                primary.setText(R.string.fablesol_export_run_in_background)
                primary.setOnClickListener { dismissAllowingStateLoss() }
            }

            is FableSolVideoExportBus.State.Done -> {
                bar.visibility = View.GONE
                status.text = getString(
                    R.string.fablesol_export_dialog_done,
                    if (state.hdr) "HDR" else "SDR",
                    state.frameRate,
                    Formatter.formatFileSize(requireContext(), state.fileSizeBytes),
                    state.displayLocation
                )
                val canShare = state.uri != null
                val canAttach = state.localPath
                    ?.let { File(it).isFile } == true &&
                    mActivity is DetailActivity
                secondary.visibility = if (canShare) View.VISIBLE else View.GONE
                primary.visibility = View.VISIBLE
                if (canShare) {
                    secondary.setText(R.string.fablesol_export_share)
                    secondary.setOnClickListener {
                        if (share(state)) dismissAllowingStateLoss()
                    }
                } else {
                    secondary.setOnClickListener(null)
                }
                if (canAttach) {
                    primary.setText(R.string.fablesol_export_add_attachment)
                    primary.setOnClickListener {
                        if (addAsAttachment(state.localPath)) dismissAllowingStateLoss()
                    }
                } else {
                    primary.setText(R.string.confirm)
                    primary.setOnClickListener { dismissAllowingStateLoss() }
                }
                applyCompletionActionAccent(
                    *arrayOf(secondary, primary).filter { it.isVisible }
                        .toTypedArray()
                )
            }

            is FableSolVideoExportBus.State.Failed -> {
                bar.visibility = View.GONE
                status.text = getString(R.string.fablesol_export_dialog_failed, state.message)
                secondary.visibility = View.GONE
                primary.setText(R.string.confirm)
                primary.setOnClickListener { dismissAllowingStateLoss() }
            }

            is FableSolVideoExportBus.State.Cancelled -> dismissAllowingStateLoss()

            is FableSolVideoExportBus.State.Queued -> {
                bar.visibility = View.VISIBLE
                bar.isIndeterminate = true
                status.setText(R.string.fablesol_export_preparing)
                secondary.visibility = View.VISIBLE
                primary.visibility = View.VISIBLE
                secondary.setText(R.string.fablesol_export_cancel)
                secondary.setOnClickListener {
                    FableSolVideoExportService.cancel(requireContext(), mJobId)
                    dismissAllowingStateLoss()
                }
                primary.setText(R.string.fablesol_export_run_in_background)
                primary.setOnClickListener { dismissAllowingStateLoss() }
            }
        }
    }

    private fun progressText(state: FableSolVideoExportBus.State.Running): String {
        val percent = state.done.toLong() * 100L / state.total
        if (state.etaMs < 0L) {
            return getString(R.string.fablesol_export_progress_unknown)
        }
        val minutes = (state.etaMs / 60_000L).toInt()
        val seconds = ((state.etaMs % 60_000L) / 1000L).toInt()
        val remaining = if (minutes > 0) {
            getString(R.string.fablesol_export_eta_minutes, minutes, seconds)
        } else {
            getString(R.string.fablesol_export_eta_seconds, seconds)
        }
        return getString(R.string.fablesol_export_progress, percent, remaining)
    }

    private fun share(state: FableSolVideoExportBus.State.Done): Boolean {
        val host = mActivity ?: return false
        val uri = state.uri ?: return false
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(
            send, host.getString(R.string.fablesol_export_share)
        )
        if (chooser.resolveActivity(host.packageManager) == null) return false
        host.startActivity(chooser)
        return true
    }

    private fun addAsAttachment(path: String?): Boolean {
        if (path.isNullOrEmpty()) return false
        val detail = mActivity as? DetailActivity ?: return false
        detail.attachmentTypePathName = AttachmentHelper.VIDEO.toString() + path
        detail.addAttachment(0)
        return true
    }

    private fun currentAccentBackground(): ThingBackground {
        val host = mActivity
        return (host as? DetailActivity)?.getAccentBackground()
            ?: (host as? DetailActivity)?.getAccentColor()?.let { ThingBackground.pure(it) }
            ?: App.defaultAccentBackground
    }

    companion object {
        const val TAG = "FableSolExportProgressDialogFragment"
        private const val KEY_JOB_ID = "job_id"

        fun tagFor(jobId: Long): String = "$TAG:$jobId"

        fun newInstance(jobId: Long): FableSolExportProgressDialogFragment =
            FableSolExportProgressDialogFragment().apply {
                arguments = android.os.Bundle().apply { putLong(KEY_JOB_ID, jobId) }
            }
    }
}
