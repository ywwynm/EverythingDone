package com.ywwynm.everythingdone.taptransfer.ui.flight

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.views.particledismiss.ParticleCardSurfaceHandoff
import com.ywwynm.everythingdone.views.particledismiss.ParticleDismissOverlay
import com.ywwynm.everythingdone.views.particledismiss.ParticleDismissSpec
import com.ywwynm.everythingdone.views.particledismiss.ParticleGestureProgress
import com.ywwynm.everythingdone.views.particledismiss.ParticleHardwareSnapshot
import com.ywwynm.everythingdone.views.particledismiss.ParticleOverlayHost
import com.ywwynm.everythingdone.views.particledismiss.ParticleReleaseOrigin
import com.ywwynm.everythingdone.views.particledismiss.ParticleSink
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 碰一碰的粒子：直接用首页与弹窗的粒子消散（`views/particledismiss`）——同一套微片、剥离与流场，
 * 只多一个终点（[ParticleSink]，NFC 天线的位置）：颗粒汇成流被吸进那一点，倒放时从那一点流出。
 * 接收方还多一组终点：屏幕边框，一部分颗粒从离它最近的那条边流出。
 *
 * 坐标一律是 DecorView 里的像素（粒子层挂在 DecorView 上）。
 */
internal object TapParticleFlight {

    /** 视图在 DecorView 里的位置。 */
    fun boundsInDecor(activity: Activity, view: View, local: Rect, out: RectF) {
        val decor = activity.window.decorView
        val at = IntArray(2)
        val decorAt = IntArray(2)
        view.getLocationInWindow(at)
        decor.getLocationInWindow(decorAt)
        out.set(
            (at[0] - decorAt[0] + local.left).toFloat(), (at[1] - decorAt[1] + local.top).toFloat(),
            (at[0] - decorAt[0] + local.right).toFloat(), (at[1] - decorAt[1] + local.bottom).toFloat()
        )
    }

    /** 截图上离终点最近的那一点（归一化），消散从这里开始、向远端推开。 */
    fun releaseOriginToward(bounds: RectF, sinkX: Float, sinkY: Float): ParticleReleaseOrigin {
        val x = sinkX.coerceIn(bounds.left, bounds.right)
        val y = sinkY.coerceIn(bounds.top, bounds.bottom)
        return ParticleReleaseOrigin(
            ((x - bounds.left) / bounds.width().coerceAtLeast(1f)).coerceIn(0f, 1f),
            ((y - bounds.top) / bounds.height().coerceAtLeast(1f)).coerceIn(0f, 1f)
        )
    }

    /**
     * 截一张视图的像素（透明底），[clip] 为视图坐标里要截的那一块。在渲染线程上画、后台线程读回，
     * 回调在主线程；失败时给 null。
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun capture(view: View, clip: Rect, done: (Bitmap?) -> Unit) {
        ParticleHardwareSnapshot.capture(view, clip, null, done)
    }

    /**
     * 把几张卡片按它们在屏幕上的位置合成一张快照，卡片之间透明。只截 [visible]（DecorView 坐标）
     * 以内的部分：伸到屏幕外的那一截不需要颗粒。回调在主线程，给出快照与它在 DecorView 里的范围；
     * 一张也没截到时快照为 null。
     *
     * 合成一张而不是每张卡片各起一个粒子层：一个会话的片数有上限，几张卡片共用这份预算；各起
     * 一层的话轨迹缓冲要成倍占用显存（三星上一张卡片约 35 MB）。
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun captureComposite(activity: Activity, views: List<View>, visible: RectF, done: (Bitmap?, RectF) -> Unit) {
        class Part(val view: View, val rect: RectF, val clip: Rect)
        val parts = ArrayList<Part>()
        val full = RectF()
        for (view in views) {
            if (view.width <= 0 || view.height <= 0) continue
            boundsInDecor(activity, view, Rect(0, 0, view.width, view.height), full)
            val shown = RectF(full)
            if (!shown.intersect(visible)) continue
            val clip = Rect(
                floor(shown.left - full.left).toInt(), floor(shown.top - full.top).toInt(),
                ceil(shown.right - full.left).toInt(), ceil(shown.bottom - full.top).toInt()
            )
            clip.intersect(0, 0, view.width, view.height)
            if (clip.isEmpty) continue
            parts.add(Part(view, RectF(full.left + clip.left, full.top + clip.top,
                full.left + clip.right, full.top + clip.bottom), clip))
        }
        val union = RectF()
        if (parts.isEmpty()) {
            done(null, union)
            return
        }
        parts.forEach { union.union(it.rect) }
        union.set(floor(union.left), floor(union.top), ceil(union.right), ceil(union.bottom))
        val bitmaps = arrayOfNulls<Bitmap>(parts.size)
        var remaining = parts.size
        parts.forEachIndexed { index, part ->
            capture(part.view, part.clip) { bitmap ->
                bitmaps[index] = bitmap
                remaining--
                if (remaining > 0) return@capture
                if (bitmaps.all { it == null }) {
                    done(null, union)
                    return@capture
                }
                val composite = Bitmap.createBitmap(union.width().toInt(), union.height().toInt(), Bitmap.Config.ARGB_8888)
                val canvas = Canvas(composite)
                parts.forEachIndexed { k, p ->
                    bitmaps[k]?.let {
                        if (BuildConfig.DEBUG) Log.i(TAG, "合成快照 第 $k 张 ${it.width}x${it.height} 不透明占比=${opaqueShare(it)}")
                        canvas.drawBitmap(it, p.rect.left - union.left, p.rect.top - union.top, null)
                        it.recycle()
                    }
                }
                done(composite, union)
            }
        }
    }

    /** 调试：位图里不透明像素的占比（抽样），截图是不是空的一眼就知道。 */
    private fun opaqueShare(bitmap: Bitmap): Float {
        var opaque = 0
        var total = 0
        val step = 8
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                if (bitmap.getPixel(x, y) ushr 24 > 0) opaque++
                total++
                x += step
            }
            y += step
        }
        return if (total == 0) 0f else opaque.toFloat() / total
    }

    private fun spec(
        params: TapParticleParams, snapshot: Bitmap, bounds: RectF, sink: PointF, reverse: Boolean, durationS: Float,
        frame: RectF? = null
    ): ParticleDismissSpec {
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "粒子飞行 reverse=$reverse sink=(${sink.x.toInt()},${sink.y.toInt()}) " +
                "bounds=(${bounds.left.toInt()},${bounds.top.toInt()})-(${bounds.right.toInt()},${bounds.bottom.toInt()}) " +
                "frame=${frame?.toShortString()} duration=$durationS")
        }
        return ParticleDismissSpec(
            snapshot, bounds.left, bounds.top,
            // 消散方向按「截图中心 → 这一点」求：颗粒一开始就朝终点剥离
            sink.x, sink.y,
            durationScale = 1f,
            hashSeed = ParticleCardSurfaceHandoff.seed(),
            reverse = reverse,
            playbackDurationS = durationS,
            // 触点强度取中值：运动幅度的缩放为 1，终点的坐标不必换算
            touchStrength = .5f,
            releaseOrigin = releaseOriginToward(bounds, sink.x, sink.y),
            sink = ParticleSink(sink.x, sink.y, frame, if (frame != null) params.frameShare else 0f, params.sink)
        )
    }

    private fun decorOf(activity: Activity): ViewGroup? = activity.window?.decorView as? ViewGroup

    /**
     * 发送方：卡片按拖动进度化成粒子、流进天线；进度可前可后，往回拖就是从天线流回来重新拼成
     * 卡片。一份轨迹同时服务流出与流回（与左滑完成用的是同一种会话）。
     */
    class Outflow(
        private val activity: Activity,
        snapshot: Bitmap,
        bounds: RectF,
        sink: PointF,
        private val listener: Listener
    ) {
        interface Listener {
            /** 粒子层的第一帧已经上屏：此刻藏起真实卡片，画面由粒子层接管。 */
            fun onTakenOver()

            /** 流回到头、那一帧已经上屏：此刻显示真实卡片，本层随后自己撤下。 */
            fun onReturned()

            /** 粒子层没能建起来或中途失败：调用方把真实卡片显示回来。 */
            fun onLost()
        }

        private val main = Handler(Looper.getMainLooper())
        private val gesture = ParticleGestureProgress(initiallyActive = true)
        private var overlay: ParticleDismissOverlay? = null
        private var animator: ValueAnimator? = null
        private var takenOver = false
        private var returning = false
        private var released = false

        /** 最近一帧真正上屏的进度。 */
        var presented = 0f
            private set

        /** 当前要求的进度（动画的目标值在走的那一刻）。 */
        var position = 0f
            private set

        init {
            val decor = decorOf(activity)
            if (decor == null) {
                snapshot.recycle()
                main.post { listener.onLost() }
            } else {
                val params = TapParticleParams.get(activity)
                val spec = spec(params, snapshot, bounds, sink, reverse = false, durationS = 1f)
                val dump = TapFixtureDump.begin(activity, "send", spec, bounds, sink, null, params)
                val created = ParticleDismissOverlay(
                    activity, spec,
                    onAnimationStarted = Runnable {
                        if (!released && !takenOver) {
                            takenOver = true
                            TapSessionRecorder.current?.event("takeover")
                            listener.onTakenOver()
                            // 卡片刚藏起：等这一帧提交之后的窗口就是粒子层底下的画面
                            dump?.let { afterFrame(decor) { it.captureWindow("background") } }
                        }
                    },
                    onDone = Runnable {
                        // 不是我们主动撤的：会话失败或看门狗收尾
                        if (!released) {
                            released = true
                            listener.onLost()
                        }
                    },
                    onPresentedProgress = { progress -> onPresented(progress) },
                    gesture = gesture
                )
                overlay = created
                ParticleOverlayHost.attach(decor, created)
            }
        }

        val isTakenOver get() = takenOver

        /** 把进度动画到 [target]；[onEnd] 在动画走完时回调（画面可能还差一两帧上屏）。 */
        fun animateTo(target: Float, durationMs: Long, interpolator: TimeInterpolator, onEnd: (() -> Unit)?) {
            if (released) return
            animator?.let { it.removeAllListeners(); it.cancel() }
            returning = target <= 0f
            val start = position
            val span = abs(target - start)
            if (span < 1e-4f) {
                seek(target)
                onEnd?.invoke()
                checkReturned()
                return
            }
            animator = ValueAnimator.ofFloat(start, target).apply {
                duration = (durationMs * span).toLong().coerceAtLeast(1L)
                this.interpolator = interpolator
                val actual = duration
                TapSessionRecorder.current?.event("progress") {
                    put("from", start.toDouble()); put("to", target.toDouble()); put("duration", actual)
                }
                addUpdateListener { seek(it.animatedValue as Float) }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        onEnd?.invoke()
                        checkReturned()
                    }
                })
                start()
            }
        }

        private fun seek(value: Float) {
            position = value
            gesture.seek(value)
        }

        private fun onPresented(progress: Float) {
            presented = progress
            checkReturned()
        }

        /** 流回到头的那一帧上屏之后交还真实卡片，再撤粒子层（先显后撤）。 */
        private fun checkReturned() {
            if (released || !returning || position > 0f || presented > RETURNED_EPSILON) return
            if (animator?.isRunning == true) return
            released = true
            TapSessionRecorder.current?.event("returned")
            listener.onReturned()
            overlay?.release()
            overlay = null
        }

        /** 立即撤掉（关闭分享界面等）；不回调监听。 */
        fun release() {
            if (released) return
            released = true
            animator?.let { it.removeAllListeners(); it.cancel() }
            animator = null
            overlay?.release()
            overlay = null
        }

        private companion object {
            /** 上屏的进度落到这以下就算流回到头：整张卡片已经是完整的表面。 */
            const val RETURNED_EPSILON = .002f
        }
    }

    /**
     * 接收方：颗粒从天线（以及 [frame] 的四条边）流出、在卡片自己的位置上拼成收到的卡片（倒放
     * 一次完整的消散，先把轨迹算好再播），播完停在末帧（与真实卡片逐像素相同），等调用方显示
     * 真实卡片后再撤。
     */
    class Inflow(
        private val activity: Activity,
        snapshot: Bitmap,
        bounds: RectF,
        sink: PointF,
        durationS: Float,
        private val listener: Listener,
        frame: RectF? = null
    ) {
        interface Listener {
            /** 第一帧上屏（颗粒开始流出）。 */
            fun onStarted()

            /** 拼完：画面停在末帧。此刻显示真实卡片，然后调用 [release]。 */
            fun onAssembled()

            /** 粒子层没能建起来：调用方直接显示卡片。 */
            fun onLost()
        }

        private val main = Handler(Looper.getMainLooper())
        private var overlay: ParticleDismissOverlay? = null
        private var started = false
        private var assembled = false
        private var released = false

        init {
            val decor = decorOf(activity)
            if (decor == null) {
                snapshot.recycle()
                main.post { listener.onLost() }
            } else {
                val params = TapParticleParams.get(activity)
                val spec = spec(params, snapshot, bounds, sink, reverse = true, durationS = durationS, frame = frame)
                val dump = TapFixtureDump.begin(activity, "receive", spec, bounds, sink, frame, params)
                val created = ParticleDismissOverlay(
                    activity, spec,
                    onAnimationStarted = Runnable {
                        if (!released && !started) {
                            started = true
                            TapSessionRecorder.current?.event("inflow-start")
                            listener.onStarted()
                            // 此刻边框还在；边框淡去之后再截一张，模拟器在两张之间按淡去的时长过渡
                            dump?.captureWindow("background")
                            dump?.captureWindow("background-settled", (params.borderFadeS * 1000f).toLong() + 250L)
                        }
                    },
                    onDone = Runnable {
                        if (released || assembled) return@Runnable
                        if (started) {
                            // 倒放播完：本层保持末帧，等调用方把真实卡片显示出来
                            assembled = true
                            TapSessionRecorder.current?.event("assembled")
                            listener.onAssembled()
                        } else {
                            released = true
                            listener.onLost()
                        }
                    }
                )
                overlay = created
                ParticleOverlayHost.attach(decor, created)
            }
        }

        /** 真实卡片已经显示：等它画出一帧再撤粒子层，交接处不闪。 */
        fun release(after: View) {
            if (released) return
            released = true
            val current = overlay
            overlay = null
            if (current == null) return
            afterFrame(after) { current.release() }
        }

        /** 立即撤掉，不回调监听。 */
        fun cancel() {
            if (released) return
            released = true
            overlay?.release()
            overlay = null
        }
    }

    /** 在 [view] 所在窗口的下一帧真正提交之后执行（带兜底）。 */
    fun afterFrame(view: View, action: () -> Unit) {
        val main = Handler(Looper.getMainLooper())
        var fired = false
        val fire = Runnable {
            if (!fired) {
                fired = true
                action()
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && view.isAttachedToWindow &&
            view.viewTreeObserver.isAlive) {
            runCatching { view.viewTreeObserver.registerFrameCommitCallback { main.post(fire) } }
            view.invalidate()
        }
        main.postDelayed(fire, FRAME_FALLBACK_MS)
    }

    private const val FRAME_FALLBACK_MS = 120L
    private const val TAG = "TapFlight"
}
