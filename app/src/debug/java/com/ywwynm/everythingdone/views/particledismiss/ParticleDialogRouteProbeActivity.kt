package com.ywwynm.everythingdone.views.particledismiss

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.activities.NoticeableNotificationActivity
import com.ywwynm.everythingdone.activities.SettingsActivity
import com.ywwynm.everythingdone.database.ThingDAO
import com.ywwynm.everythingdone.model.Thing

/** 仅调试包：打开真实入口；通知测试只允许已打开的普通记事，不触发提醒动作。 */
class ParticleDialogRouteProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.getStringExtra("route")) {
            "notice" -> {
                // 默认沿用「已打开的普通记事」这条安全路径；显式传 thingId 时按该记事打开，
                // 用于验证提醒记事上的动作按钮（开始做事等）所引出的弹窗。
                val explicitId = intent.getLongExtra("thingId", -1L)
                val id = if (explicitId > 0) explicitId else App.getRunningDetailActivities().lastOrNull()
                val thing = id?.let { ThingDAO.getInstance(this)?.getThingById(it) }
                if (thing != null && (explicitId > 0 || thing.type == Thing.NOTE)) {
                    startActivity(NoticeableNotificationActivity.getOpenIntentForReminder(this, thing.id, -1))
                }
            }
            "settings" -> startActivity(Intent(this, SettingsActivity::class.java))
        }
        finish()
    }
}
