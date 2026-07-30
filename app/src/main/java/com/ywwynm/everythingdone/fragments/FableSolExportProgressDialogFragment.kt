package com.ywwynm.everythingdone.fragments

import android.app.Activity
import android.content.DialogInterface
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
import com.ywwynm.everythingdone.activities.SettingsActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.services.FableSolVideoExportService
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.views.GradientRippleDrawable
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolExportBitrateText
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolExportSpecText
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
    private var mAwaitingConfirmation = false

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
        val restored = restoredState()
        if (restored == null) {
            // 不在 onCreateView 里同步 dismiss：那是在 FragmentManager 正装配本 fragment 的
            // 时候拆它。
            view.post { dismissAllowingStateLoss() }
        } else {
            render(restored)
        }
        FableSolVideoExportBus.addListener(mListener)
        return view
    }

    override fun onDestroyView() {
        FableSolVideoExportBus.removeListener(mListener)
        super.onDestroyView()
    }

    /**
     * 装配时的初始状态。
     *
     * [FableSolVideoExportBus.newJobId] 在铸号那一刻就把排队态登记进 registry，所以只要任务
     * 是本进程发起的，`currentFor` 必然有值——**取不到只有一种可能：进程被杀过**。此时
     * FragmentManager 会把这个对话框从 savedInstanceState 里恢复出来，而服务、总线、任务
     * 全都不在了，再没有任何状态会送达。
     *
     * 不做这个判定的话它会回落成排队态，转一个永远不停的圈；对播放对话框而言，那还意味着
     * 水体永久冻结、播放永久暂停（判据只看对话框在不在）。因此直接判为"导出已中断"终态，
     * 给出确认按钮让用户关掉。
     *
     * 返回 null 表示"没什么可显示的，关掉即可"。
     */
    private fun restoredState(): FableSolVideoExportBus.State? {
        FableSolVideoExportBus.currentFor(mJobId)?.let { return it }
        // 号是本进程铸的，只是终态被 registry 限长淘汰了：任务确实跑完过，结果那时也显示过。
        if (FableSolVideoExportBus.isKnownJobId(mJobId)) return null
        return FableSolVideoExportBus.State.Failed(
            mJobId,
            getString(R.string.fablesol_export_service_interrupted)
        )
    }

    /**
     * 等待确认时，系统返回键等同于“结束导出”。点击对话框外部已在 [render] 中禁用，因此
     * 不会产生未说明的规格选择或任务状态。
     */
    override fun onCancel(dialog: DialogInterface) {
        if (mAwaitingConfirmation && mJobId > 0L) {
            FableSolVideoExportService.cancel(requireContext(), mJobId)
        }
        super.onCancel(dialog)
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
            // 走与调参滑杆同源的渐变轨道。此前只用 accent.color 给 tint，那是渐变的起点
            // 单色——换色时看不出渐变方向，也和同一个 Dialog 里其它强调色元素对不上。
            DisplayUtil.setProgressBarBackground(bar, accent)
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
        mAwaitingConfirmation =
            state is FableSolVideoExportBus.State.AwaitingConfirmation
        dialog?.setCanceledOnTouchOutside(!mAwaitingConfirmation)
        mTvTitle?.setText(
            if (mAwaitingConfirmation) {
                R.string.fablesol_export_confirmation_title
            } else {
                R.string.fablesol_export_title
            }
        )
        when (state) {
            is FableSolVideoExportBus.State.Running -> {
                bar.visibility = View.VISIBLE
                secondary.visibility = View.VISIBLE
                primary.visibility = View.VISIBLE
                if (state.total > 0 && state.total != Int.MAX_VALUE) {
                    bar.isIndeterminate = false
                    bar.progress = (state.done.toLong() * 1000L / state.total).toInt()
                    status.text = FableSolExportSpecText.appendRetrySummary(
                        requireContext(),
                        progressText(state),
                        state.retryNotice
                    )
                } else {
                    bar.isIndeterminate = true
                    status.text = FableSolExportSpecText.appendRetrySummary(
                        requireContext(),
                        getString(R.string.fablesol_export_progress_unknown),
                        state.retryNotice
                    )
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
                status.text = FableSolExportSpecText.appendRetrySummary(
                    requireContext(),
                    getString(
                        R.string.fablesol_export_dialog_done,
                        FableSolExportSpecText.specification(
                            requireContext(),
                            state.formatLabel,
                            state.codecLabel,
                            state.softwareCodec
                        ),
                        state.frameRate,
                        Formatter.formatFileSize(requireContext(), state.fileSizeBytes),
                        FableSolExportBitrateText.of(state.bitrateBps),
                        state.displayLocation,
                        FableSolExportSpecText.detail(
                            requireContext(),
                            state.pqWhiteNits,
                            state.peakNits,
                            state.highlightStartPercent,
                            state.hdr10PlusIdentity,
                            state.luminance,
                            state.hlgRange,
                            state.encoding,
                            hdr10PlusRequestedKneeNits = state.hdr10PlusRequestedKneeNits,
                            hdr10PlusKneeNits = state.hdr10PlusKneeNits,
                            hdr10PlusFbpUnavailable = state.hdr10PlusFbpUnavailable,
                            hdr10PlusSeiSamples = state.hdr10PlusSeiSamples,
                            hdr10PlusSeiTotal = state.hdr10PlusSeiTotal,
                            sdrFallbackNotice = state.sdrFallbackNotice,
                            staticMetadataConflict = state.staticMetadataConflict
                        )
                    ),
                    state.retryNotice
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
                // 「调整导出设置」只属于与设置相关的失败——规格候选耗尽、无完整规格
                // （D107、D183）；空间不足、超时、服务被杀这类环境性失败没有可调的规格，
                // 不给一个无的放矢的入口。
                val adjustable = state.adjustSettingsActionable
                secondary.visibility = if (adjustable) View.VISIBLE else View.GONE
                if (adjustable) {
                    secondary.setText(R.string.fablesol_export_adjust_settings)
                    secondary.setOnClickListener {
                        openExportSettings()
                        dismissAllowingStateLoss()
                    }
                } else {
                    secondary.setOnClickListener(null)
                }
                primary.visibility = View.VISIBLE
                primary.setText(R.string.confirm)
                primary.setOnClickListener { dismissAllowingStateLoss() }
                if (adjustable) applyCompletionActionAccent(secondary, primary)
            }

            is FableSolVideoExportBus.State.Cancelled -> dismissAllowingStateLoss()

            is FableSolVideoExportBus.State.AwaitingConfirmation -> {
                bar.visibility = View.GONE
                status.text = FableSolExportSpecText.retryConfirmation(
                    requireContext(),
                    state.notice
                )
                secondary.visibility = View.VISIBLE
                primary.visibility = View.VISIBLE
                secondary.setText(R.string.fablesol_export_end_export)
                secondary.setOnClickListener {
                    FableSolVideoExportService.cancel(requireContext(), mJobId)
                    dismissAllowingStateLoss()
                }
                primary.setText(R.string.fablesol_export_use_suggested_spec_retry)
                primary.setOnClickListener {
                    FableSolVideoExportService.acceptSuggested(requireContext(), mJobId)
                }
                applyCompletionActionAccent(secondary, primary)
            }

            // 排队与准备阶段的可用操作完全一致，区别只在状态文字：准备阶段能说清此刻在等
            // 什么（当前是 HLG 扩展信号范围的回环验证，D138），排队只能说"正在准备"。
            is FableSolVideoExportBus.State.Queued,
            is FableSolVideoExportBus.State.Preparing -> {
                bar.visibility = View.VISIBLE
                bar.isIndeterminate = true
                val preparing = if (state is FableSolVideoExportBus.State.Preparing) {
                    FableSolExportSpecText.preparingStage(requireContext(), state.stageId)
                } else {
                    getString(R.string.fablesol_export_preparing)
                }
                status.text = FableSolExportSpecText.appendRetrySummary(
                    requireContext(),
                    preparing,
                    (state as? FableSolVideoExportBus.State.Preparing)?.retryNotice
                )
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

    /**
     * 「调整导出设置」（D107）：打开 FableSol 设置并定位到「视频导出」组。只做导航——
     * 不改写用户偏好，也不自动重新发起导出。
     */
    private fun openExportSettings() {
        val host = mActivity ?: return
        host.startActivity(
            Intent(host, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_SHOW_FABLESOL_EXPORT, true)
        )
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
