package com.ywwynm.everythingdone.permission

/**
 * Created by ywwynm on 2016/5/21.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * permission callback
 */
interface PermissionCallback {
    fun onGranted()
    fun onDenied()
}
