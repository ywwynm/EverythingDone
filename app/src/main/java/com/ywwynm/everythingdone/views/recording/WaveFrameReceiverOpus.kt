package com.ywwynm.everythingdone.views.recording

/**
 * [WaveDriveFrameOpus] 的接收方。由 [AudioRecorder] 在采集线程按采样间隔分发；实现方（
 * [WaveVisualizerOpus]）需自行完成到 UI 线程的交接——音频帧只更新视觉目标值，绘制帧率由
 * View 自身的 vsync 帧循环决定，二者解耦。
 */
fun interface WaveFrameReceiverOpus {
    fun receive(frame: WaveDriveFrameOpus)
}
