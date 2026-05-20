package com.ywwynm.everythingdone.permission

import android.widget.Toast
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.EverythingDoneBaseActivity

/**
 * Created by ywwynm on 2016/5/21.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * simple permission callback
 */
open class SimplePermissionCallback(activity: EverythingDoneBaseActivity?) : PermissionCallback {

    private var mActivity: EverythingDoneBaseActivity? = activity

    override fun onGranted() {

    }

    override fun onDenied() {
        Toast.makeText(mActivity, R.string.error_permission_denied, Toast.LENGTH_LONG).show()
    }
}
