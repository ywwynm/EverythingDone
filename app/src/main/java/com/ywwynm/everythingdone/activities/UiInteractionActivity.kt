package com.ywwynm.everythingdone.activities

import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.interaction.UiInteractions

/** startActivity、ActivityResult 与旧 startActivityForResult 最终共用这些系统入口。 */
abstract class UiInteractionActivity : AppCompatActivity() {
    override fun setTheme(resid: Int) {
        super.setTheme(resid)
        theme.applyStyle(R.style.UiInteractionPolicy, true)
    }

    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        UiInteractions.launch(this, requestCode) { super.startActivityForResult(intent, requestCode, options) }
    }

    override fun startActivities(intents: Array<out Intent>, options: Bundle?) {
        UiInteractions.launch(this) { super.startActivities(intents, options) }
    }

    override fun startIntentSenderForResult(
        intent: IntentSender, requestCode: Int, fillInIntent: Intent?, flagsMask: Int,
        flagsValues: Int, extraFlags: Int, options: Bundle?
    ) {
        UiInteractions.launch(this, requestCode) {
            super.startIntentSenderForResult(intent, requestCode, fillInIntent, flagsMask,
                flagsValues, extraFlags, options)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        // 结果会早于 onResume 到达；回调里打开下一窗口时，旧请求必须已释放。
        UiInteractions.activityResult(this, requestCode)
        super.onActivityResult(requestCode, resultCode, data)
    }
}
