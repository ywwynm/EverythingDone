package com.ywwynm.everythingdone.views.recording.fablesol

import android.annotation.TargetApi
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Region
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import android.view.SurfaceControl
import android.view.View
import android.view.ViewTreeObserver
import org.json.JSONObject

/** N 对照：仅用公开 API 创建自己的图层，不创建 SurfaceView 或它的背景层。 */
@TargetApi(33)
internal class FableSolCompositionControlView(
    context: Context,
    private val cornerRadiusPx: Float,
    private val comparison: () -> JSONObject,
    private val log: (String, JSONObject) -> Unit,
    private val report: (Boolean, String) -> Unit
) : View(context), AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private var active: Session? = null
    private var nextSession = 0
    private var closed = false
    private var failure: String? = null
    private val preDraw = ViewTreeObserver.OnPreDrawListener { reconcile(); true }

    private class Session(
        val id: Int, val control: SurfaceControl, val surface: Surface,
        val width: Int, val height: Int, val worker: HandlerThread
    ) {
        val render = Handler(worker.looper)
        // renderer 只在其工作线程读写；输出与提交状态在主线程发布。
        var renderer: FableSolCompositionRenderer? = null
        var frameReady = false
        var committed = false
        var holeDrawn = false
        var announced = false
        var visible = false
        var transaction = 0
        var x = Int.MIN_VALUE
        var y = Int.MIN_VALUE
        var output: String? = null
    }

    val ready: Boolean get() = active?.let {
        failure == null && it.frameReady && it.committed && it.holeDrawn && it.visible && it.surface.isValid
    } == true

    val copySurface: Surface? get() = active?.surface

    fun diagnostics(): JSONObject = JSONObject().put("surfaceViewCreated", false)
        .put("ready", ready).put("closed", closed).put("failure", failure ?: JSONObject.NULL)
        .put("requestedFormat", "RGBA_8888").put("requestedOpaque", false)
        .put("requestedLayer", -1).put("nativeRoundCornerApiUsed", false)
        .apply {
            active?.let {
                put("session", it.id).put("controlIdentity", System.identityHashCode(it.control))
                put("surfaceIdentity", System.identityHashCode(it.surface))
                put("controlValid", it.control.isValid).put("surfaceValid", it.surface.isValid)
                put("width", it.width).put("height", it.height)
                put("surfaceX", it.x).put("surfaceY", it.y)
                put("verifiedOutput", it.output).put("frameReady", it.frameReady)
                put("visible", it.visible).put("transactionCommitted", it.committed)
                put("windowHoleDrawn", it.holeDrawn).put("transaction", it.transaction)
            }
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(preDraw)
        parent?.requestTransparentRegion(this)
        post { reconcile() }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // View 构造中也可能收到回调，实际创建只在附着后的 preDraw 中进行。
        if (isAttachedToWindow) post { reconcile() }
    }

    private fun reconcile() {
        if (closed || failure != null || !isAttachedToWindow || width < 1 || height < 1) return
        var session = active
        if (session != null && (session.width != width || session.height != height)) {
            release("size_changed")
            session = null
        }
        try {
            if (session == null) {
                if (windowVisibility != VISIBLE || !isShown) return
                if (rootSurfaceControl == null) return
                session = createSession()
            }
            val xy = IntArray(2).also(::getLocationInSurface)
            val visible = session.frameReady && windowVisibility == VISIBLE && isShown
            if (xy[0] != session.x || xy[1] != session.y || visible != session.visible) {
                submit(session, xy, visible)
            }
        } catch (error: Throwable) { failed(error) }
    }

    private fun createSession(): Session {
        val id = ++nextSession
        var control: SurfaceControl? = null
        var surface: Surface? = null
        var worker: HandlerThread? = null
        try {
            control = SurfaceControl.Builder().setName("FableSolDirectDiagnostic-$id")
                .setBufferSize(width, height).setFormat(PixelFormat.RGBA_8888)
                .setOpaque(false).setHidden(true).build()
            surface = Surface(control)
            check(surface.isValid && control.isValid) { "direct_surface_invalid" }
            worker = HandlerThread("FableSolDirect-$id").also { it.start() }
            val session = Session(id, control, surface, width, height, worker)
            active = session
            log("direct_surface_created", diagnostics())
            val snapshot = comparison()
            session.render.post {
                try {
                    val renderer = FableSolCompositionRenderer(context.assets, session.surface, true, cornerRadiusPx) { name, fields ->
                        log("direct_$name", fields.put("session", id).put("comparison", snapshot))
                    }
                    session.renderer = renderer
                    check(renderer.output == FableSolScreenOutput.PQ) { "PQ unavailable: ${renderer.diagnostic}" }
                    renderer.draw(session.width, session.height, snapshot, false)
                    main.post {
                        if (active !== session || closed) return@post
                        session.output = FableSolScreenOutput.PQ.name
                        session.frameReady = true
                        log("direct_buffer_submitted", diagnostics())
                        reconcile()
                        invalidate()
                    }
                } catch (error: Throwable) {
                    main.post { if (active === session && !closed) failed(error) }
                }
            }
            // 即使 EGL／事务回调卡住，也留下可导出的失败状态。
            main.postDelayed({
                if (active === session && !closed && !session.announced) failed(IllegalStateException("direct_ready_timeout_6000ms"))
            }, 6000)
            return session
        } catch (error: Throwable) {
            if (active == null) {
                surface?.release()
                control?.release()
                worker?.quitSafely()
            }
            throw error
        }
    }

    private fun submit(session: Session, xy: IntArray, visible: Boolean) {
        val root = checkNotNull(rootSurfaceControl) { "root_surface_unavailable" }
        val transaction = checkNotNull(root.buildReparentTransaction(session.control)) { "reparent_unavailable" }
        val revision = ++session.transaction
        session.committed = false
        session.visible = visible
        session.x = xy[0]
        session.y = xy[1]
        try {
            transaction.setPosition(session.control, xy[0].toFloat(), xy[1].toFloat())
                .setCrop(session.control, Rect(0, 0, session.width, session.height))
                .setLayer(session.control, -1).setOpaque(session.control, false)
                .setAlpha(session.control, 1f).setVisibility(session.control, visible)
            transaction.addTransactionCommittedListener(context.mainExecutor) {
                log("direct_transaction_committed", JSONObject().put("session", session.id)
                    .put("transaction", revision).put("visible", visible)
                    .put("stillCurrent", active === session && revision == session.transaction && !closed))
                if (active === session && revision == session.transaction && !closed) {
                    session.committed = true
                    announce(session)
                }
            }
            check(root.applyTransactionOnDraw(transaction)) { "transaction_on_draw_rejected" }
            log("direct_transaction_scheduled", diagnostics().put("rootIdentity", System.identityHashCode(root)))
            invalidate()
        } finally { transaction.close() }
    }

    override fun onDraw(canvas: Canvas) {
        val session = active ?: return
        if (!session.frameReady || !session.visible || failure != null) return
        // 与原 SurfaceView 一样为下层内容留透明区域；原生白圈随后由兄弟 View 绘制。
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), clear)
        if (!session.holeDrawn) {
            session.holeDrawn = true
            val hardware = canvas.isHardwareAccelerated
            post {
                if (active === session && !closed) {
                    log("direct_window_hole_drawn", diagnostics().put("hardwareCanvas", hardware))
                    announce(session)
                }
            }
        }
    }

    override fun gatherTransparentRegion(region: Region?): Boolean {
        val xy = IntArray(2).also(::getLocationInWindow)
        region?.op(xy[0], xy[1], xy[0] + width, xy[1] + height, Region.Op.UNION)
        // 不能因为透明区域之外被填满就声明整个窗口 opaque。
        return false
    }

    private fun announce(session: Session) {
        if (ready && !session.announced) {
            session.announced = true
            log("direct_ready", diagnostics())
            report(true, "N：PQ 首帧与图层事务已提交；请检查黑角、白圈和高光。")
        }
    }

    private fun failed(error: Throwable) {
        failure = error.toString()
        log("direct_failed", diagnostics())
        release("failed")
        invalidate()
        report(false, "N 创建或提交失败：${error.message}；请导出日志，此状态不能用于 PQ 对比。")
    }

    fun close(reason: String) {
        if (closed) return
        closed = true
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(preDraw)
        release(reason)
    }

    override fun close() = close("closed")

    private fun release(reason: String) {
        val session = active ?: return
        active = null
        log("direct_release_requested", JSONObject().put("session", session.id).put("reason", reason))
        // 先从合成树摘下；GPU 资源在原线程串行释放，不让旧图层覆盖下一组对照。
        try {
            SurfaceControl.Transaction().use {
                it.setVisibility(session.control, false).reparent(session.control, null).apply()
            }
        } catch (error: Throwable) {
            log("direct_detach_failed", JSONObject().put("session", session.id).put("error", error.toString()))
        }
        session.render.post {
            try {
                session.renderer?.close()
            } catch (error: Throwable) {
                log("direct_release_error", JSONObject().put("session", session.id).put("error", error.toString()))
            } finally {
                session.renderer = null
                session.surface.release()
                session.control.release()
                log("direct_released", JSONObject().put("session", session.id).put("reason", reason))
                session.worker.quitSafely()
            }
        }
        main.postDelayed({
            if (session.worker.isAlive) log("direct_release_pending", JSONObject().put("session", session.id).put("reason", reason))
        }, 2500)
    }

    override fun onDetachedFromWindow() {
        close("view_detached")
        super.onDetachedFromWindow()
    }
}
