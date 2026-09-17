package com.ywwynm.everythingdone.views.particledismiss

import android.view.View
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.views.InterceptTouchCardView

internal object ParticleCardPose {
    fun hold(view: View) {
        (view as? InterceptTouchCardView)?.particleAnimationRunning = true
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationZ = 0f
        view.elevation = view.resources.getDimension(R.dimen.thing_card_normal_elevation)
    }

    fun release(view: View) {
        (view as? InterceptTouchCardView)?.let {
            it.particleAnimationRunning = false
            it.setParticleAlphaLock(null)
        }
    }
}
