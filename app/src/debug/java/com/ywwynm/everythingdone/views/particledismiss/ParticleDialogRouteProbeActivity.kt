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
                val id = App.getRunningDetailActivities().lastOrNull()
                val thing = id?.let { ThingDAO.getInstance(this)?.getThingById(it) }
                if (thing != null && thing.type == Thing.NOTE) {
                    startActivity(NoticeableNotificationActivity.getOpenIntentForReminder(this, thing.id, -1))
                }
            }
            "settings" -> startActivity(Intent(this, SettingsActivity::class.java))
        }
        finish()
    }
}
