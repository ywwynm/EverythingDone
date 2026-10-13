package com.ywwynm.everythingdone.views.particledismiss

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import com.ywwynm.everythingdone.views.InterceptTouchCardView

/** 首尾用完整画面保护交接；播放期间撤下底图，由原窗口继续显示动态背景。 */
@RequiresApi(34)
internal class ParticleOpaqueGlCardHandoff(
    private val source: View,
    private val window: ParticleWindowSurface,
    private val card: ParticleGlBitmapLayer,
    private var foreground: List<ParticleForegroundSurface>,
    private val sources: List<View>,
    private val onFailure: () -> Unit,
    preparedBackdrop: ParticleGlBitmapLayer? = null,
    private val liveBackdrop: ParticleLiveBackdrop? = null
) : ParticleCardHandoff {
    private val main = Handler(Looper.getMainLooper())
    private val state = ParticleGlHandoffState()
    private val diagnostic = ParticlePresentationDiagnostics.of(source)
    private val name = System.identityHashCode(this).toString()
    private val fast = preparedBackdrop != null
    private val backdrop = preparedBackdrop ?: ParticleGlBitmapLayer("backdrop-$name", -1, false, underlay = true)
    private val group = ParticleGlBitmapGroup(listOf(backdrop, card) + foreground.mapNotNull { it.glLayer }) {
        session?.setVisible(false)
    }
    private var claimed = false
    @Volatile private var returning = false
    @Volatile private var groupEntered = false
    private var session: ParticleLayerSession? = null
    private var restoration: (() -> Unit)? = null
    private var completion: (() -> Unit)? = null
    private val timeout = Runnable {
        diagnostic?.event("opaque_handoff_timeout", mapOf("phase" to state.phase.name))
        if (state.phase == ParticleGlHandoffState.Phase.RETURNING) restoreAndClose() else onFailure()
    }

    override fun start(window: ParticleWindowSurface, session: ParticleLayerSession, reverse: Boolean) {
        if (!state.beginStart()) return
        this.session = session
        main.postDelayed(timeout, 3600)
        // 此路径不隐藏整张 Surface；确认弹窗的尾部颗粒仍可与批量卡片共同播放。
        window.claimGlVisibility(this, session, exclusiveSurface = false) { accepted ->
            if (state.phase != ParticleGlHandoffState.Phase.STAGING) {
                if (accepted) window.releaseGlVisibility(this) { }; return@claimGlVisibility
            }
            if (!accepted) { onFailure(); return@claimGlVisibility }
            claimed = true
            if (fast) { stageEntry(session, reverse); return@claimGlVisibility }
            val root = source.rootView as? ViewGroup
            if (root == null) { onFailure(); return@claimGlVisibility }
            ParticleBackdropSnapshot.capture(root, sources + foreground.map { it.nativeViewForBackdrop() }, diagnostic) { bitmap ->
                if (state.phase != ParticleGlHandoffState.Phase.STAGING) { bitmap?.recycle(); return@capture }
                if (bitmap == null) { onFailure(); return@capture }
                window.uploadGlBitmap(backdrop, bitmap, 0f, 0f, 1f) { ready ->
                    if (state.phase != ParticleGlHandoffState.Phase.STAGING) return@uploadGlBitmap
                    if (!ready) {
                        onFailure(); return@uploadGlBitmap
                    }
                    stageEntry(session, reverse)
                }
            }
        }
    }

    private fun stageEntry(session: ParticleLayerSession, reverse: Boolean) {
        liveBackdrop?.start()
        if (!foreground.all { it.enterGl(hideNative = false) }) { onFailure(); return }
        window.changeGlLayersCommitted("gl-window-opaque-enter-$name", { submitted ->
            if (state.phase != ParticleGlHandoffState.Phase.STAGING) return@changeGlLayersCommitted
            if (!submitted || !state.acceptStartFrame()) { onFailure(); return@changeGlLayersCommitted }
            main.removeCallbacks(timeout)
            diagnostic?.event("opaque_handoff_started", mapOf("nativeForegroundHidden" to false,
                "surfaceAlpha" to window.view.alpha, "reverse" to reverse))
            if (liveBackdrop != null) revealNativeBackground()
            if (!fast) window.changeGlLayers {
                if (!group.closing && !returning) { card.replacedBy = session; session.setVisible(true) }
            }
        }) {
            if (!group.closing && !returning) {
                groupEntered = true
                backdrop.visible = true; card.visible = !reverse
                foreground.mapNotNull { it.glLayer }.forEach { it.visible = true }
                // 底图、静态卡片、前景与粒子在同一缓冲绘制，无需先呈现一个静态帧再开播。
                if (fast) { card.replacedBy = session; session.setVisible(true) }
            }
        }
    }

    private fun revealNativeBackground() {
        ParticleHostPresentation.afterChange(source, diagnostic, "opaque_enter_native_hide", {
            foreground.forEach { it.hideNativeForGl() }
            sources.forEach {
                (it as? InterceptTouchCardView)?.setParticleAlphaLock(0f)
                it.alpha = 0f
            }
        }) { presented ->
            val reveal = {
                if (state.phase == ParticleGlHandoffState.Phase.ACTIVE && !returning) {
                    liveBackdrop?.stop()
                    window.changeGlLayers {
                        if (!group.closing && !returning) {
                            backdrop.visible = false
                            diagnostic?.event("native_dynamic_background_visible", mapOf(
                                "presentFence" to presented, "meaning" to "native_window_background_not_snapshot"))
                        }
                    }
                }
            }
            if (presented) reveal()
            else ParticleHostFrame.after(source, diagnostic, "opaque_enter_native_hide_unverified", reveal)
        }
    }

    override fun leave(window: ParticleWindowSurface, session: ParticleLayerSession, showCard: Boolean,
                       restore: () -> Unit, done: () -> Unit) {
        if (!state.beginReturn()) {
            if (state.phase == ParticleGlHandoffState.Phase.CLOSED) { restore(); done() }
            return
        }
        returning = true
        liveBackdrop?.stop()
        restoration = restore; completion = done
        main.removeCallbacks(timeout); main.postDelayed(timeout, 3000)
        if (liveBackdrop != null && groupEntered) liveBackdrop.fresh { ready ->
            if (state.phase != ParticleGlHandoffState.Phase.RETURNING) return@fresh
            if (ready) { liveBackdrop.start(); stageReturn(session, showCard) }
            else restoreAndClose()
        } else stageReturn(session, showCard)
    }

    private fun stageReturn(session: ParticleLayerSession, showCard: Boolean) {
        window.changeGlLayersCommitted("gl-window-opaque-return-$name", {
            if (state.phase == ParticleGlHandoffState.Phase.RETURNING) restoreAndClose()
        }) {
            session.setVisible(false); card.replacedBy = null
            // 预热未完成就松手／取消时，整组还没有进入 GL。不能仅打开末帧卡片，
            // 否则它会覆盖仍由窗口绘制的 FAB；迟到的准备任务也不能再进入。
            if (!group.closing) {
                backdrop.visible = groupEntered
                card.visible = showCard && groupEntered
                if (!groupEntered) diagnostic?.event("opaque_return_without_entry",
                    mapOf("cardShown" to false, "reason" to "native_view_kept_during_preparation"))
            }
        }
    }

    private fun restoreAndClose() {
        val restore = restoration ?: return
        restoration = null
        main.removeCallbacks(timeout)
        ParticleHostPresentation.afterChange(source, diagnostic, "opaque_return", {
            foreground.forEach { it.restoreNativeForGl() }
            restore()
        }) { presented ->
            diagnostic?.event("opaque_handoff_native_ready", mapOf("presentFence" to presented))
            if (presented || !source.isAttachedToWindow) dispose()
            else ParticleHostFrame.after(source, diagnostic, "opaque_return_unverified") { dispose() }
        }
    }

    override fun dispose() {
        if (!state.close()) return
        liveBackdrop?.close()
        returning = true
        main.removeCallbacks(timeout)
        restoration?.let { restoration = null; it() }
        val fronts = foreground; foreground = emptyList()
        // 资源撤除排入单个 GL 任务后才释放前景；成员释放不会提交卡片独留的缓冲。
        window.closeGlGroup(group) { cleared ->
            fronts.forEach { it.release(onFailure, synchronizedGlRestore = true) }
            val finish = {
                diagnostic?.event("opaque_handoff_closed", mapOf("groupCleared" to cleared,
                    "surfaceAlpha" to window.view.alpha))
                completion?.let { completion = null; main.post(it) }
            }
            if (claimed) window.releaseGlVisibility(this) { claimed = false; finish() } else finish()
        }
    }
}
