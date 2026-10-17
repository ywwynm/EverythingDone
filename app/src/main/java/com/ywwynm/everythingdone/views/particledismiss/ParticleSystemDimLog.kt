package com.ywwynm.everythingdone.views.particledismiss

import android.view.Window
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.helpers.DebugFileLogger

/** 仅由卓易通音频兼容分支调用，记录交接结果，不保存截图或音频。 */
internal object ParticleSystemDimLog {
    fun record(window: Window, event: String, detail: String = "") {
        if (BuildConfig.DEBUG) DebugFileLogger.log("fablesol_dialog_dim.log",
            "window=${System.identityHashCode(window)} event=$event $detail", "[DEBUG-fablesol-system-dim]")
    }
}
