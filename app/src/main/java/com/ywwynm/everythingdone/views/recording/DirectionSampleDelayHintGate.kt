package com.ywwynm.everythingdone.views.recording

/**
 * 把 MediaProjection 往返与普通传感器生命周期分开：只有授权成功返回后一直没有新样本，
 * 才允许一次性提示。方法会分别由主线程和传感器线程调用，因此状态访问统一加锁。
 */
internal class DirectionSampleDelayHintGate(alreadyShown: Boolean) {

    private var shown = alreadyShown
    private var projectionRequestActive = false
    private var sampleSequenceBeforeReturn = 0L
    private var waitingForSample = false

    @Synchronized
    fun onProjectionRequestStarted(currentSampleSequence: Long) {
        waitingForSample = false
        if (shown) {
            projectionRequestActive = false
            return
        }
        projectionRequestActive = true
        sampleSequenceBeforeReturn = currentSampleSequence
    }

    /** 在离开应用的最后时刻重取基线，排除授权页出现前仍在途的正常样本。 */
    @Synchronized
    fun onHostPausedForProjection(currentSampleSequence: Long) {
        if (projectionRequestActive) {
            sampleSequenceBeforeReturn = currentSampleSequence
        }
    }

    /** 返回 true 表示调用方需要启动等待计时器。 */
    @Synchronized
    fun onProjectionResult(
        granted: Boolean,
        monitoringEnabled: Boolean,
        currentSampleSequence: Long
    ): Boolean {
        if (!projectionRequestActive || shown || !granted || !monitoringEnabled) {
            projectionRequestActive = false
            waitingForSample = false
            return false
        }
        projectionRequestActive = false
        waitingForSample = currentSampleSequence <= sampleSequenceBeforeReturn
        return waitingForSample
    }

    @Synchronized
    fun onDirectionSample(): Boolean {
        val cancelledWait = waitingForSample
        waitingForSample = false
        return cancelledWait
    }

    /** 返回 true 表示本次应显示提示；成功一次后，该实例不会再次返回 true。 */
    @Synchronized
    fun onWaitExpired(): Boolean {
        if (shown || !waitingForSample) return false
        waitingForSample = false
        shown = true
        return true
    }

    @Synchronized
    fun cancel() {
        projectionRequestActive = false
        waitingForSample = false
    }
}
