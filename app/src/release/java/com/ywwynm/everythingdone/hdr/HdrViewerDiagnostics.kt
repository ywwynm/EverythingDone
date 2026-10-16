package com.ywwynm.everythingdone.hdr

import android.content.Context

internal object HdrViewerDiagnostics {
    const val forceCompatibility = false
    fun event(context: Context, name: String, details: String) = Unit
}
