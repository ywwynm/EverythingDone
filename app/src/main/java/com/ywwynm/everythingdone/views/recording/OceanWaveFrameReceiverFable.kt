package com.ywwynm.everythingdone.views.recording

/**
 * [OceanWaveAudioFrameFable] 的接收方。由 [AudioRecorder] 在采集线程按采样
 * 间隔分发；实现方需自行完成到 UI 线程的交接（帧率与绘制帧解耦）。
 */
fun interface OceanWaveFrameReceiverFable {
    fun receive(frame: OceanWaveAudioFrameFable)
}
