package com.ywwynm.everythingdone.spatial

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.activities.ImageViewerActivity
import com.ywwynm.everythingdone.activities.SpatialPhotoSettingsActivity
import java.io.File
import java.util.ArrayList

/**
 * ADB 必须从前台 Activity 发起产品界面，否则 Android 的后台启动限制会拦截 debug receiver。
 */
class SpatialPhotoDebugLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = intent.getStringExtra(EXTRA_TARGET) ?: TARGET_SETTINGS
        val next = if (target == TARGET_VIEWER) {
            val path = intent.getStringExtra(EXTRA_PATH)
            if (path.isNullOrBlank() || !File(path).isFile) {
                finish()
                return
            }
            Intent(this, ImageViewerActivity::class.java).apply {
                putStringArrayListExtra(
                    Def.Communication.KEY_TYPE_PATH_NAME,
                    ArrayList(listOf("0$path"))
                )
                putExtra(Def.Communication.KEY_POSITION, 0)
                putExtra(Def.Communication.KEY_EDITABLE, false)
                putExtra(Def.Communication.KEY_COLOR, 0xff5b6fb8.toInt())
            }
        } else {
            Intent(this, SpatialPhotoSettingsActivity::class.java)
        }
        startActivity(next)
        finish()
    }

    companion object {
        private const val EXTRA_TARGET = "target"
        private const val EXTRA_PATH = "path"
        private const val TARGET_SETTINGS = "settings"
        private const val TARGET_VIEWER = "viewer"
    }
}
