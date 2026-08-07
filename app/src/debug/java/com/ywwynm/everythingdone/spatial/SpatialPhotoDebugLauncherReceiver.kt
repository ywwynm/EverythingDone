package com.ywwynm.everythingdone.spatial

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.activities.ImageViewerActivity
import com.ywwynm.everythingdone.activities.SpatialPhotoSettingsActivity
import java.io.File
import java.util.ArrayList

/**
 * debug 真机验证入口。只启动正式 Activity，不绕过产品推理、存储或渲染代码。
 */
class SpatialPhotoDebugLauncherReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val target = intent.getStringExtra(EXTRA_TARGET) ?: TARGET_SETTINGS
        val activityIntent = if (target == TARGET_VIEWER) {
            val path = intent.getStringExtra(EXTRA_PATH) ?: return
            if (!File(path).isFile) return
            Intent(context, ImageViewerActivity::class.java).apply {
                putStringArrayListExtra(
                    Def.Communication.KEY_TYPE_PATH_NAME,
                    ArrayList(listOf("0$path"))
                )
                putExtra(Def.Communication.KEY_POSITION, 0)
                putExtra(Def.Communication.KEY_EDITABLE, false)
                putExtra(Def.Communication.KEY_COLOR, 0xff5b6fb8.toInt())
            }
        } else {
            Intent(context, SpatialPhotoSettingsActivity::class.java)
        }
        activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(activityIntent)
    }

    companion object {
        private const val EXTRA_TARGET = "target"
        private const val EXTRA_PATH = "path"
        private const val TARGET_SETTINGS = "settings"
        private const val TARGET_VIEWER = "viewer"
    }
}
