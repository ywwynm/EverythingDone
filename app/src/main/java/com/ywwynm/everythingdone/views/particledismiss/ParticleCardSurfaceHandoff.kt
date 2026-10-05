package com.ywwynm.everythingdone.views.particledismiss

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.DataSpace
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.SurfaceControl
import android.view.View
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.views.InterceptTouchCardView
import java.util.concurrent.Executors

/**
 * 仅在交接时显示卡片快照。与原 View 按窗口帧同步，与粒子按 SurfaceView 的缓冲帧同步。
 * 正常播放仍使用一窗一 Surface，不在 HWUI 中逐帧合成粒子纹理。
 */
@RequiresApi(34)
internal class ParticleCardSurfaceHandoff private constructor(
    private val source: View,
    private val bitmap: Bitmap,
    private val layer: SurfaceControl,
    private val onForegroundFailure: () -> Unit,
    private val sources: List<View>
) {
    private val main = Handler(Looper.getMainLooper())
    private var foreground = emptyList<ParticleForegroundSurface>()
    private var entering = false
    private var sourceHidden = false
    private var closing = false
    private var released = false
    private var restoration: (() -> Unit)? = null
    private var completed: (() -> Unit)? = null
    private val rescue = Runnable { restoreInWindow() }
    private val windowCallbacks = mutableSetOf<Runnable>()

    fun enter(ready: (SurfaceControl.Transaction) -> Unit) {
        if (entering || closing || released) return
        val transaction = SurfaceControl.Transaction().setVisibility(layer, true)
        if (foreground.any { !it.enter(transaction) }) {
            transaction.close(); onForegroundFailure(); return
        }
        entering = true
        sourceHidden = true
        hideSources()
        onWindowFrame(transaction) {
            if (!closing && !released) ready(SurfaceControl.Transaction().setVisibility(layer, false))
        }
    }

    /** 回聚从不可见开始，只有末端才需要静态快照交接。 */
    fun keepSourceHidden(ready: () -> Unit) {
        if (sourceHidden || closing || released) return
        val transaction = SurfaceControl.Transaction()
        if (foreground.any { !it.enter(transaction) }) {
            transaction.close(); onForegroundFailure(); return
        }
        sourceHidden = true
        hideSources()
        if (foreground.isEmpty()) { transaction.close(); ready() }
        else onWindowFrame(transaction) {
            if (!closing && !released) ready()
        }
    }

    fun leave(window: ParticleWindowSurface, session: ParticleLayerSession,
              showCard: Boolean, restore: () -> Unit, done: () -> Unit) {
        if (closing || released) return
        closing = true
        restoration = restore
        completed = done
        // 只预热、尚未接管卡片就取消时，原 View 一直可见，不能再叠一份快照。
        if (!sourceHidden) { dispose(); return }
        main.postDelayed(rescue, 1000)
        val transaction = SurfaceControl.Transaction().setVisibility(layer, showCard)
            .addTransactionCommittedListener({ main.post(it) }) { restoreInWindow() }
        window.endCardFrame(session, transaction)
    }

    private fun restoreInWindow() {
        if (released) return
        val restore = restoration ?: return
        restoration = null
        main.removeCallbacks(rescue)
        restore()
        onWindowFrame(SurfaceControl.Transaction().setVisibility(layer, false).reparent(layer, null)) {
            dispose()
        }
    }

    private fun hideSources() {
        for (view in sources) {
            (view as? InterceptTouchCardView)?.setParticleAlphaLock(0f)
            view.alpha = 0f
        }
    }

    /** 在退出页面等没有下一帧的场合直接释放自有图层。 */
    fun dispose() {
        if (released) return
        released = true
        main.removeCallbacks(rescue)
        windowCallbacks.forEach { main.removeCallbacks(it) }
        windowCallbacks.clear()
        runCatching { SurfaceControl.Transaction().use { it.reparent(layer, null).apply() } }
        layer.release()
        bitmap.recycle()
        foreground.forEach { it.release(onForegroundFailure) }; foreground = emptyList()
        val restore = restoration
        restoration = null
        restore?.invoke()
        val done = completed
        completed = null
        // detach 可能处于父容器遍历中，后续移除 Overlay 留到调用栈返回。
        if (done != null) main.post { done() }
    }

    private fun onWindowFrame(transaction: SurfaceControl.Transaction, done: () -> Unit) {
        var finished = false
        val finish = object : Runnable {
            override fun run() {
                if (finished || released) return
                finished = true
                main.removeCallbacks(this)
                windowCallbacks.remove(this)
                done()
            }
        }
        windowCallbacks += finish
        transaction.addTransactionCommittedListener({ main.post(it) }) { finish.run() }
        val root = source.rootSurfaceControl
        val queued = transaction.use {
            if (source.isAttachedToWindow && root != null && root.applyTransactionOnDraw(it)) true
            else { it.apply(); false }
        }
        if (queued) {
            source.rootView.invalidate()
            // 只用于窗口停止绘制后的资源回收，不作为正常交接时钟。
            main.postDelayed(finish, 1000)
        } else finish.run()
    }

    companion object {
        private val worker = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ParticleCardHandoffCopy")
        }
        private val main = Handler(Looper.getMainLooper())
        // 仅供虚构卡片在同一 APK 内做对照，不读取或写入应用偏好。
        var forceTextureForTests = false
        var seedForTests: Int? = null
        fun seed() = (if (BuildConfig.DEBUG) seedForTests else null)
            ?: (Math.random() * Int.MAX_VALUE).toInt()
        fun supported() = Build.VERSION.SDK_INT >= 34 &&
            !(BuildConfig.DEBUG && forceTextureForTests)

        fun prepare(source: View, spec: ParticleDismissSpec, particleView: SurfaceView,
                    onForegroundFailure: () -> Unit,
                    sources: List<View> = listOf(source),
                    done: (ParticleCardSurfaceHandoff?) -> Unit) {
            worker.execute {
                val hardware = runCatching { spec.snapshot.copy(Bitmap.Config.HARDWARE, false) }.getOrNull()
                main.post {
                    val root = source.rootSurfaceControl
                    if (hardware == null || root == null || !source.isAttachedToWindow) {
                        hardware?.recycle(); done(null); return@post
                    }
                    var layer: SurfaceControl? = null
                    val handoff = runCatching {
                        val control = SurfaceControl.Builder().setName("ParticleCardHandoff")
                            .setFormat(PixelFormat.RGBA_8888).setOpaque(false)
                            .setBufferSize(hardware.width, hardware.height).build()
                        layer = control
                        val attach = checkNotNull(root.buildReparentTransaction(control))
                        hardware.hardwareBuffer.use { buffer ->
                            attach.setBuffer(control, buffer).setDataSpace(control, DataSpace.DATASPACE_SRGB)
                                .setPosition(control, spec.originXPx, spec.originYPx)
                                .setLayer(control, Int.MAX_VALUE - 1).setVisibility(control, false).apply()
                        }
                        attach.close()
                        ParticleCardSurfaceHandoff(source, hardware, control, onForegroundFailure, sources)
                    }.getOrElse {
                        layer?.release(); hardware.recycle(); null
                    }
                    if (handoff == null) done(null)
                    else ParticleForegroundSurface.prepare(source, particleView, onForegroundFailure, { outcome ->
                        if (outcome.isFailure) { handoff.dispose(); done(null) }
                        else { handoff.foreground = outcome.getOrThrow(); done(handoff) }
                    })
                }
            }
        }
    }
}
