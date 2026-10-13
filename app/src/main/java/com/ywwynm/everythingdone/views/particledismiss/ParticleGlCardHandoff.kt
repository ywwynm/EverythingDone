package com.ywwynm.everythingdone.views.particledismiss

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import android.view.View
import androidx.annotation.RequiresApi
import com.ywwynm.everythingdone.views.InterceptTouchCardView
import java.util.concurrent.Executors

/** GL 中保留静态首尾帧和前景；粒子开始时在同一缓冲中替换静态卡片。 */
@RequiresApi(34)
internal class ParticleGlCardHandoff private constructor(
    private val source: View,
    private val window: ParticleWindowSurface,
    private val card: ParticleGlBitmapLayer,
    private var foreground: List<ParticleForegroundSurface>,
    private val sources: List<View>,
    private val onFailure: () -> Unit
) : ParticleCardHandoff {
    private val state = ParticleGlHandoffState()
    private val diagnostic = ParticlePresentationDiagnostics.of(source)
    private val name = System.identityHashCode(this).toString()
    private val geometryHandoff = ParticlePresentationPolicy.of(source).unified
    private val synchronizedHandoff = geometryHandoff || diagnostic?.options?.synchronizedGlHandoff == true
    private var gateClaimed = false
    private var originalSurfaceAlpha = 1f
    private var originalSurfaceTranslationX = 0f
    private var parkedSurfaceTranslationX = 0f
    private var gatedSession: ParticleLayerSession? = null
    private val group = ParticleGlBitmapGroup(listOf(card) + foreground.mapNotNull { it.glLayer }) {
        gatedSession?.setVisible(false)
    }
    private var sourceHidden = false
    private var restoration: (() -> Unit)? = null
    private var completion: (() -> Unit)? = null
    private val timeout = Runnable {
        diagnostic?.event("gl_handoff_timeout", mapOf("phase" to state.phase.name, "handoff" to name))
        if (state.phase == ParticleGlHandoffState.Phase.RETURNING) finishReturn()
        else onFailure()
    }

    private fun switchSurface(stage: String, visible: Boolean, change: () -> Unit, done: (Boolean) -> Unit) {
        ParticleWindowFrameSwitch.run(source, diagnostic, stage, {
            change()
            if (geometryHandoff) {
                // SurfaceView 的位置由 RenderThread 合入对应窗口帧。保持 alpha、缓冲大小和
                // 生产者不变，避开只读 SurfaceControl 属性以及 alpha=0 的独立显示切换。
                window.view.translationX = if (visible) originalSurfaceTranslationX else parkedSurfaceTranslationX
                diagnostic?.event("surface_geometry_switch", mapOf("stage" to stage,
                    "visible" to visible, "translationX" to window.view.translationX,
                    "surfaceAlpha" to window.view.alpha, "sourceAlpha" to source.alpha,
                    "sourceParentAlpha" to (source.parent as? View)?.alpha))
            } else window.view.alpha = if (visible) originalSurfaceAlpha else 0f
        }, done = done)
    }

    override fun start(window: ParticleWindowSurface, session: ParticleLayerSession, reverse: Boolean) {
        if (!state.beginStart()) return
        main.postDelayed(timeout, if (synchronizedHandoff) 3600 else 1500)
        if (synchronizedHandoff) {
            window.claimGlVisibility(this, session) { accepted ->
                if (state.phase != ParticleGlHandoffState.Phase.STAGING) {
                    if (accepted) window.releaseGlVisibility(this) { }
                    return@claimGlVisibility
                }
                if (!accepted) { onFailure(); return@claimGlVisibility }
                gateClaimed = true
                gatedSession = session
                originalSurfaceAlpha = window.view.alpha
                originalSurfaceTranslationX = window.view.translationX
                parkedSurfaceTranslationX = originalSurfaceTranslationX +
                    (source.rootView.width + window.view.width).coerceAtLeast(1).toFloat()
                switchSurface("gl_prepare_hidden", false, {}) { switched ->
                    if (state.phase == ParticleGlHandoffState.Phase.STAGING) {
                        if (switched) stageEntry(session, reverse) else onFailure()
                    }
                }
            }
        } else stageEntry(session, reverse)
    }

    private fun stageEntry(session: ParticleLayerSession, reverse: Boolean) {
        val fronts = foreground.mapNotNull { it.glLayer }
        val ready: (Boolean) -> Unit = ready@{ valid ->
            if (state.phase != ParticleGlHandoffState.Phase.STAGING) return@ready
            if (!valid) { onFailure(); return@ready }
            val hide = {
                check(foreground.all { it.enterGl() }) { "前景交接准备失效" }
                check(state.acceptStartFrame())
                sourceHidden = true
                for (view in sources) {
                    (view as? InterceptTouchCardView)?.setParticleAlphaLock(0f)
                    view.alpha = 0f
                }
                diagnostic?.event("gl_card_source_hidden", mapOf("handoff" to name, "reverse" to reverse))
            }
            val play = {
                main.removeCallbacks(timeout)
                if (state.phase == ParticleGlHandoffState.Phase.ACTIVE) window.changeGlLayers {
                    if (!group.closing) {
                        card.replacedBy = session
                        session.setVisible(true)
                    }
                }
            }
            if (synchronizedHandoff) {
                switchSurface("gl_show_native_hide", true, {
                    hide()
                    diagnostic?.event("gl_visibility_switch", mapOf("nativeHidden" to true,
                        "surfaceAlpha" to window.view.alpha))
                }) { switched ->
                    if (!switched && (state.phase == ParticleGlHandoffState.Phase.STAGING ||
                                state.phase == ParticleGlHandoffState.Phase.ACTIVE)) onFailure()
                    else if (switched && state.phase == ParticleGlHandoffState.Phase.ACTIVE) play()
                }
            } else {
                try { hide() } catch (_: Exception) { onFailure(); return@ready }
                ParticleHostFrame.after(source, diagnostic, "card_enter", play)
            }
        }
        val stage = {
            if (!group.closing) {
                card.visible = !reverse
                fronts.forEach { if (!it.closed) it.visible = true }
            }
        }
        if (synchronizedHandoff) window.changeGlLayersCommitted("gl-window-enter-$name", ready, stage)
        else window.changeGlLayers("gl-window-enter-$name", ready, stage)
    }

    override fun leave(window: ParticleWindowSurface, session: ParticleLayerSession,
                       showCard: Boolean, restore: () -> Unit, done: () -> Unit) {
        if (!state.beginReturn()) {
            if (state.phase == ParticleGlHandoffState.Phase.CLOSED) { restore(); done() }
            return
        }
        main.removeCallbacks(timeout)
        restoration = restore; completion = done
        if (!sourceHidden) { finishReturn(); return }
        main.postDelayed(timeout, if (synchronizedHandoff) 3000 else 1500)
        val ready: (Boolean) -> Unit = {
            if (state.phase == ParticleGlHandoffState.Phase.RETURNING) finishReturn()
        }
        val stage = {
            session.setVisible(false)
            card.replacedBy = null
            card.visible = showCard && !group.closing
        }
        if (synchronizedHandoff) window.changeGlLayersCommitted("gl-window-return-$name", ready, stage)
        else window.changeGlLayers("gl-window-return-$name", ready, stage)
    }

    private fun finishReturn() {
        val restore = restoration ?: return
        restoration = null
        main.removeCallbacks(timeout)
        var restored = false
        val restoreOnce = {
            if (!restored) {
                restored = true
                restore()
                // 先恢复真实属性，GL 卡片和前景仍作为完整缓冲保留；切换完成后整组撤除。
                foreground.forEach { it.restoreNativeForGl() }
                diagnostic?.event("gl_card_source_restored", mapOf("handoff" to name,
                    "sourceAlpha" to source.alpha, "sourceParentAlpha" to (source.parent as? View)?.alpha))
                if (gateClaimed) diagnostic?.event("gl_visibility_switch", mapOf("nativeHidden" to false,
                    "surfaceAlpha" to window.view.alpha))
            }
        }
        if (gateClaimed) switchSurface("native_show_gl_hide", false, restoreOnce) { _ -> restoreOnce(); dispose() }
        else { restoreOnce(); ParticleHostFrame.after(source, diagnostic, "card_return") { dispose() } }
    }

    override fun dispose() {
        if (!state.close()) return
        main.removeCallbacks(timeout)
        if (gateClaimed) {
            if (geometryHandoff) window.view.translationX = parkedSurfaceTranslationX
            else window.view.alpha = 0f
        }
        val fronts = foreground; foreground = emptyList()
        restoration?.let { restoration = null; it() }
        val finish = { completion?.let { completion = null; main.post(it) }; Unit }
        window.closeGlGroup(group) { valid ->
            fronts.forEach { it.release(onFailure, synchronizedGlRestore = true) }
            if (gateClaimed) {
                window.releaseGlVisibility(this) {
                    gateClaimed = false
                    if (geometryHandoff) window.view.translationX = originalSurfaceTranslationX
                    else window.view.alpha = originalSurfaceAlpha
                    diagnostic?.event("gl_visibility_released", mapOf("clearCommitted" to valid))
                    finish()
                }
            } else finish()
        }
        diagnostic?.event("gl_card_handoff_closed", mapOf("handoff" to name))
    }

    companion object {
        private val main = Handler(Looper.getMainLooper())
        private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "ParticleGlHandoffCopy") }

        fun prepare(source: View, spec: ParticleDismissSpec, window: ParticleWindowSurface,
                    sources: List<View>, onFailure: () -> Unit, done: (ParticleCardHandoff?) -> Unit) {
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                ParticleDoingPage.of(source)?.let {
                    it.prepareCard(source, spec, sources, onFailure, done)
                    return
                }
            }
            val policy = ParticlePresentationPolicy.of(source)
            // 壁纸／透明窗口不能把半透明背景重复盖上去。此时只交接卡片和前景，
            // 用 SurfaceView 的公开位置更新与宿主帧同步，不改变框架 SurfaceControl 属性。
            val transparentWindow = policy.unified &&
                source.rootView.background?.opacity != android.graphics.PixelFormat.OPAQUE
            ParticlePresentationDiagnostics.of(source)?.event("card_window_composition", mapOf(
                "transparentWindow" to transparentWindow,
                "handoff" to if (transparentWindow) "surface_geometry" else "opaque_backdrop"))
            if (policy.fastOpaqueHandoff && !transparentWindow) {
                prepareTogether(source, spec, window, sources, onFailure, done)
                return
            }
            // 独立软件副本的生存期覆盖异步上传，不与粒子材料线程抢回收原始快照。
            worker.execute {
                val copy = runCatching { spec.snapshot.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
                main.post {
                    if (copy == null || !source.isAttachedToWindow || window.detached) {
                        copy?.recycle(); done(null); return@post
                    }
                    val card = ParticleGlBitmapLayer("card-${System.identityHashCode(source)}-${System.nanoTime()}", 0, false)
                    window.uploadGlBitmap(card, copy, spec.originXPx, spec.originYPx, spec.sourceAlpha) { ready ->
                        if (!ready || !source.isAttachedToWindow) { window.closeGlLayer(card); done(null); return@uploadGlBitmap }
                        ParticleForegroundSurface.prepare(source, window.view as SurfaceView, onFailure, { result ->
                            val fronts = result.getOrNull()
                            if (fronts == null) { window.closeGlLayer(card); done(null) }
                            else if (policy.opaqueGlHandoff && !transparentWindow)
                                done(ParticleOpaqueGlCardHandoff(source, window, card, fronts, sources, onFailure))
                            else done(ParticleGlCardHandoff(source, window, card, fronts, sources, onFailure))
                        }, glWindow = window)
                    }
                }
            }
        }

        /** 底图、静态卡片和前景相互独立；从按下预热起并行准备，不等识别或前景上传后才抓底图。 */
        private fun prepareTogether(source: View, spec: ParticleDismissSpec, window: ParticleWindowSurface,
                                    sources: List<View>, onFailure: () -> Unit, done: (ParticleCardHandoff?) -> Unit) {
            val name = "${System.identityHashCode(source)}-${System.nanoTime()}"
            val card = ParticleGlBitmapLayer("card-$name", 0, false)
            val backdrop = ParticleGlBitmapLayer("backdrop-$name", -1, false, underlay = true)
            backdrop.copyHardwareToOwned = ParticlePresentationPolicy.of(source).unified
            var fronts: List<ParticleForegroundSurface> = emptyList()
            var live: ParticleLiveBackdrop? = null
            var remaining = 3
            var failed = false
            fun complete(ok: Boolean) {
                failed = failed || !ok
                if (--remaining != 0) return
                if (failed || !source.isAttachedToWindow || window.detached) {
                    live?.close()
                    window.closeGlLayer(card); window.closeGlLayer(backdrop)
                    fronts.forEach { it.release(onFailure) }
                    done(null)
                } else done(ParticleOpaqueGlCardHandoff(source, window, card, fronts, sources, onFailure, backdrop, live))
            }
            val root = source.rootView as? android.view.ViewGroup
            if (root == null) complete(false)
            else if (ParticlePresentationPolicy.of(source).unified) {
                live = ParticleLiveBackdrop(root, (sources + ParticleHomeForeground.views(source)).toSet(),
                    window, backdrop, ParticlePresentationDiagnostics.of(source), onFailure)
                live!!.prepare(::complete)
            }
            else ParticleBackdropSnapshot.capture(root, sources + ParticleHomeForeground.views(source),
                ParticlePresentationDiagnostics.of(source)) { bitmap ->
                if (bitmap == null) complete(false)
                else window.uploadGlBitmap(backdrop, bitmap, 0f, 0f, 1f, ::complete)
            }
            worker.execute {
                val copy = runCatching { spec.snapshot.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
                main.post {
                    if (copy == null) complete(false)
                    else window.uploadGlBitmap(card, copy, spec.originXPx, spec.originYPx, spec.sourceAlpha, ::complete)
                }
            }
            ParticleForegroundSurface.prepare(source, window.view as SurfaceView, onFailure, { result ->
                fronts = result.getOrNull().orEmpty(); complete(result.isSuccess)
            }, glWindow = window)
        }
    }
}
