package com.ywwynm.everythingdone.views.recording

import java.io.File

enum class AudioRecordingPhase {
    IDLE,
    PREPARED,
    RECORDING,
    STOPPED,
    ERROR
}

enum class AudioRecordingNotice {
    NONE,
    /** 停止后的收尾（WAV 封装）进行中。保留与否要等收尾结果，此阶段不得宣称任何结论。 */
    FINALIZING,
    PROJECTION_DENIED,
    SYSTEM_INITIALIZATION_FAILED,
    /** 录音中系统撤销投影，已自动停止并保留部分录音；准备态撤销用 [SYSTEM_CAPTURE_ENDED]。 */
    SYSTEM_CAPTURE_REVOKED,
    /** 准备态（尚未录音）投影被撤销，回落麦克风；文案不得提"已保留录音"。 */
    SYSTEM_CAPTURE_ENDED,
    CAPTURE_FAILED,
    MICROPHONE_UNAVAILABLE,
    /** 起录失败（引擎无法开始录音，一个字节都没写），与录音中断的 [CAPTURE_FAILED] 区分。 */
    RECORDING_START_FAILED,
    /** 准备态 raw 文件打开/写入失败（CaptureSource.OUTPUT）。存储问题与输入源无关，不回落。 */
    FILE_OUTPUT_FAILED,
    /** 录音中 raw 写入中断，自动停止；此前写入的部分是否保留由收尾结果决定。 */
    FILE_WRITE_INTERRUPTED,
    SIZE_LIMIT_REACHED,
    STORAGE_FULL,
    /** WAV 封装失败（非空间不足），录音未能保留；与"采集故障但已保留部分录音"的 [CAPTURE_FAILED] 区分。 */
    FINALIZE_FAILED
}

data class AudioRecordingSnapshot(
    val phase: AudioRecordingPhase = AudioRecordingPhase.IDLE,
    val inputMode: AudioInputMode = AudioInputMode.MICROPHONE,
    val busy: Boolean = false,
    val configured: Boolean = false,
    val hasProjection: Boolean = false,
    val recordingBaseElapsed: Long = 0L,
    /** 停止时的最终录音时长；从通知停止后重建的 Dialog 靠它显示正确的停止态时钟。 */
    val recordedDurationMillis: Long = 0L,
    val savedFile: File? = null,
    val systemSilent: Boolean = false,
    val aecEnabled: Boolean = false,
    val notice: AudioRecordingNotice = AudioRecordingNotice.NONE
)

/**
 * 停止收尾后引擎的既有配置是否仍可信（决定"重新录音"走快速复用还是完整重建）。
 *
 * 系统类模式停止时投影已释放，必然重建。纯麦克风原本可以复用，但输入流死亡（如
 * `AudioRecord.read` 返回 ERROR_DEAD_OBJECT）后采集对象已失效——Android 要求重建
 * AudioRecord，而失效对象的 `state` 仍是 INITIALIZED，复用检查拦不住它，必须在这里
 * 判为未配置。死亡以两路信号判定，缺一不可靠：[stopNotice] 覆盖"故障触发停止"，
 * [inputFaulted]（引擎在 read 失败处即刻记录的真相）覆盖"手动/容量停止与故障并发、
 * 故障回调被抑制或被 STOPPED 态忽略"的竞态窗口。
 * 文件写入中断（[AudioRecordingNotice.FILE_WRITE_INTERRUPTED]）是存储问题，采集对象
 * 健康，保持复用。
 */
fun stoppedSessionRemainsConfigured(
    mode: AudioInputMode,
    stopNotice: AudioRecordingNotice,
    inputFaulted: Boolean
): Boolean = !mode.requiresSystemAudio &&
    stopNotice != AudioRecordingNotice.CAPTURE_FAILED &&
    !inputFaulted
