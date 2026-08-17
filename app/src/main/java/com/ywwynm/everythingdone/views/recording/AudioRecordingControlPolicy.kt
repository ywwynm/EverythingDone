package com.ywwynm.everythingdone.views.recording

data class AudioRecordingControlAvailability(
    val mainActionEnabled: Boolean,
    val sourceSelectorEnabled: Boolean,
    val stoppedActionsEnabled: Boolean,
    /** 停止态的"放弃"按钮。收尾（busy）期间也可用——用户不必等大文件封装完才能丢弃。 */
    val cancelEnabled: Boolean
)

/**
 * 录音 Dialog 的控件可用性策略。
 *
 * 开始录音必须等待输入完成配置；来源选择则是失败恢复入口，所以在空闲、配置失败或尚未配置
 * 的准备态仍应可用。只有授权页尚未返回、服务正在切换来源或录音已经开始后才锁定来源。
 */
object AudioRecordingControlPolicy {

    fun resolve(
        snapshot: AudioRecordingSnapshot,
        binderConnected: Boolean,
        projectionRequestInFlight: Boolean
    ): AudioRecordingControlAvailability {
        if (!binderConnected) {
            return AudioRecordingControlAvailability(
                mainActionEnabled = false,
                sourceSelectorEnabled = false,
                stoppedActionsEnabled = false,
                cancelEnabled = false
            )
        }

        val mainActionEnabled = !snapshot.busy && when (snapshot.phase) {
            AudioRecordingPhase.PREPARED -> snapshot.configured
            AudioRecordingPhase.RECORDING -> true
            // 停止态的主按钮是"保存"：封装失败（无文件）时必须禁用，否则点击只会
            // 静默关闭 Dialog 而不添加任何附件。
            AudioRecordingPhase.STOPPED -> snapshot.savedFile != null
            AudioRecordingPhase.IDLE,
            AudioRecordingPhase.ERROR -> false
        }
        val sourceSelectorEnabled = !snapshot.busy &&
            !projectionRequestInFlight &&
            when (snapshot.phase) {
                AudioRecordingPhase.IDLE,
                AudioRecordingPhase.PREPARED,
                AudioRecordingPhase.ERROR -> true
                AudioRecordingPhase.RECORDING,
                AudioRecordingPhase.STOPPED -> false
            }
        val stoppedActionsEnabled = !snapshot.busy &&
            snapshot.phase == AudioRecordingPhase.STOPPED

        return AudioRecordingControlAvailability(
            mainActionEnabled = mainActionEnabled,
            sourceSelectorEnabled = sourceSelectorEnabled,
            stoppedActionsEnabled = stoppedActionsEnabled,
            cancelEnabled = snapshot.phase == AudioRecordingPhase.STOPPED
        )
    }
}
