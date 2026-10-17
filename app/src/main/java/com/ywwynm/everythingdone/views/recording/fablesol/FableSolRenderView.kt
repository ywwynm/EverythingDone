package com.ywwynm.everythingdone.views.recording.fablesol

import android.graphics.Bitmap
import android.view.View
import com.ywwynm.everythingdone.model.ThingBackground

/** 两种屏幕容器共用生产渲染器；普通设备的 SurfaceView 生命周期保持原样。 */
internal interface FableSolRenderView : FableSolFrameReceiver {
    val view: View
    val retainsFrameOnOutputChange: Boolean get() = false
    val compatibilityHdrSupported: Boolean?
    var onHdrAvailabilityChanged: (() -> Unit)?
    var onGlFailure: ((String) -> Unit)?
    fun setThingBackground(background: ThingBackground)
    fun setContainerGravity(x: Float, y: Float, z: Float)
    fun setPerformanceMonitor(monitor: FableSolPerformanceMonitor?)
    fun setPresentationAlpha(alpha: Float)
    fun setRecordingHdrActive(active: Boolean)
    fun willChangeSurfaceFormat(strength: Float): Boolean
    fun copyCurrentFrame(done: (Bitmap?) -> Unit)
    fun onNextPresented(action: () -> Unit)
    fun setHdrStrength(strength: Float)
    fun setTuningValue(key: String, value: Double)
    fun setSimulationPaused(paused: Boolean)
    fun setFrozen(value: Boolean)
    fun beginBackgroundTransition(background: ThingBackground)
    fun setContentVerticalOffsetDp(offsetDp: Float)
    fun setBottomCornerRadiusPx(radiusPx: Float)
    fun clearPendingAudio()
}
