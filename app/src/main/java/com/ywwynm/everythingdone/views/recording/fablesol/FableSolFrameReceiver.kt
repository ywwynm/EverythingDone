package com.ywwynm.everythingdone.views.recording.fablesol

/**
 * [FableSolRealtimeAnalyzer] 每次 feed 产出的一批 frames/events 的接收方。由 AudioRecorder 在
 * 采集线程分发；实现方（[WaveVisualizerFableSol]）需自行把它们交接到 UI 帧循环（并发队列），
 * 由 vsync 帧循环消费 → mapper → simulation → 渲染，二者解耦。
 */
fun interface FableSolFrameReceiver {
    fun onAudioFrames(frames: List<FableSolFeatureFrame>, events: List<FableSolEvent>)
}
