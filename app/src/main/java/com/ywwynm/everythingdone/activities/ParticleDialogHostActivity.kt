package com.ywwynm.everythingdone.activities

import android.os.Bundle
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.ywwynm.everythingdone.views.particledismiss.ParticleDismissController

/** 承载选择、认证等弹窗的透明 Activity，保留完整的动画宿主生命周期。 */
abstract class ParticleDialogHostActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this))
    }

    override fun finish() {
        if (!ParticleDismissController.finishAfterAnimations(this) { super.finish() }) super.finish()
    }
}
