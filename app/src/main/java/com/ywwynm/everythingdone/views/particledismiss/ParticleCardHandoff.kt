package com.ywwynm.everythingdone.views.particledismiss

internal interface ParticleCardHandoff {
    fun start(window: ParticleWindowSurface, session: ParticleLayerSession, reverse: Boolean)
    fun leave(window: ParticleWindowSurface, session: ParticleLayerSession,
              showCard: Boolean, restore: () -> Unit, done: () -> Unit)
    fun dispose()
}
