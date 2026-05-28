package com.ywwynm.everythingdone.helpers

data class DebugApkUpdateInfo(
    var channel: String? = null,
    var debugUpdateCode: Long = 0L,
    var versionCode: Long = 0L,
    var versionName: String? = null,
    var apkUrl: String? = null,
    var sha256: String? = null,
    var sizeBytes: Long = 0L,
    var publishedAt: String? = null,
    var releaseNotes: String? = null
)
