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
import com.ywwynm.everythingdone.views.particledismiss.ParticleHandoffSource
import com.ywwynm.everythingdone.views.particledismiss.ParticleHardwareSnapshot
import com.ywwynm.everythingdone.views.particledismiss.ParticleOverlayHost
import com.ywwynm.everythingdone.views.particledismiss.ParticleReleaseOrigin
import com.ywwynm.everythingdone.views.particledismiss.ParticleSink
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 碰一碰的粒子：直接用首页与弹窗的粒子消散（`views/particledismiss`）——同一套微片、剥离与流场，
 * 只多一个终点（[ParticleSink] 的第三版柔性终点，NFC 天线的位置）：颗粒整团滑进天线，倒放时从那里
 * 浮现。接收方还多一组终点：屏幕四周的光尘带，一部分颗粒从离它最近的那一段流进来。
 *
 * 两处都是手势会话，按外部给的位置取帧：发送按进度动画（[Outflow]），接收按倒放时间轴（[ReceiveLayer]）。
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
    /** 合成快照里的一张：视图、它在 DecorView 里被截的那一块、视图坐标里的截取范围。 */
    private class Part(val view: View, val rect: RectF, val clip: Rect)

    /**
     * 几张卡片在 [visible] 以内的部分与它们的外接矩形（取整）；一张也不在里面时外接矩形为空。[settled] 时按
     * 布局位置算（去掉卡片自己的平移）：列表让位动画还在播时，这就是动画走完之后的位置。
     */
    private fun compositeParts(activity: Activity, views: List<View>, visible: RectF,
                               settled: Boolean = false): Pair<List<Part>, RectF> {
        val parts = ArrayList<Part>()
        val full = RectF()
        for (view in views) {
            if (view.width <= 0 || view.height <= 0) continue
            boundsInDecor(activity, view, Rect(0, 0, view.width, view.height), full)
            if (settled) full.offset(-view.translationX, -view.translationY)
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
        parts.forEach { union.union(it.rect) }
        if (!union.isEmpty) union.set(floor(union.left), floor(union.top), ceil(union.right), ceil(union.bottom))
        return parts to union
    }

    /**
     * 列表让位动画走完之后 [captureComposite] 会截出的范围（DecorView 坐标，按布局位置、不含卡片自己的平移），
     * 不截图。拼卡那一层提前建时按它画占位快照，之后截出的真实快照与它同尺寸、同原点；一张也不在 [visible]
     * 里时返回 null。
     */
    fun compositeBounds(activity: Activity, views: List<View>, visible: RectF): RectF? =
        compositeParts(activity, views, visible, settled = true).second.takeUnless { it.isEmpty }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun captureComposite(activity: Activity, views: List<View>, visible: RectF, done: (Bitmap?, RectF) -> Unit) {
        val (parts, union) = compositeParts(activity, views, visible)
        if (parts.isEmpty()) {
            done(null, union)
            return
        }
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

    /**
     * 碰一碰的粒子规格。两处都按外部给的位置取帧（手势会话），不按时长播：发送按进度动画，接收按倒放时间轴。
     *
     * @param field 第三版的柔性终点（发送 [TapParticleParams.sendField]，接收 [TapParticleParams.receiveField]）
     * @param dynamics 算运动用的位图（与 [snapshot] 同尺寸），见 [ParticleDismissSpec.dynamics]
     */
    private fun spec(
        snapshot: Bitmap, bounds: RectF, sink: PointF, frame: RectF?, frameShare: Float, params: TapParticleParams,
        field: ParticleSink.Field, dynamics: Bitmap? = null, handoff: ParticleHandoffSource? = null,
        layerAlpha: Boolean = false, deferForeground: Boolean = false,
        seed: Int = ParticleCardSurfaceHandoff.seed()
    ): ParticleDismissSpec {
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "粒子飞行 sink=(${sink.x.toInt()},${sink.y.toInt()}) " +
                "bounds=(${bounds.left.toInt()},${bounds.top.toInt()})-(${bounds.right.toInt()},${bounds.bottom.toInt()}) " +
                "frame=${frame?.toShortString()} cards=${field.cards.size} dynamics=${dynamics != null} " +
                "handoff=${handoff != null} layerAlpha=$layerAlpha deferForeground=$deferForeground")
        }
        return ParticleDismissSpec(
            snapshot, bounds.left, bounds.top,
            // 消散方向按「截图中心 → 这一点」求：颗粒一开始就朝终点剥离
            sink.x, sink.y,
            durationScale = 1f,
            hashSeed = seed,
            reverse = false,
            playbackDurationS = 1f,
            // 触点强度取中值：运动幅度的缩放为 1，终点的坐标不必换算
            touchStrength = .5f,
            releaseOrigin = releaseOriginToward(bounds, sink.x, sink.y),
            sink = ParticleSink(sink.x, sink.y, frame, frameShare, params.sink, field),
            dynamics = dynamics,
            handoff = handoff,
            layerAlpha = layerAlpha,
            deferForeground = deferForeground
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
                // 第三版：没有边框，整团滑进天线（末段不旋转）
                val spec = spec(snapshot, bounds, sink, null, 0f, params, params.sendField)
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
     * 接收方的一层（第三版）：一次消散的倒放，按外部给的正向位置取帧（[seek]：0 = 拼好，1 = 全部在终点），
     * 整层的不透明度由 [alpha] 给出。颗粒从天线处浮现、从屏幕四周的光尘带流进来，在卡片的位置上拼成卡片。
     *
     * 接收用两层，共用一条倒放时间轴（`TapReceiveTimeline`），流入开始时交叉淡化：
     * - 进度期间那一层：记事内容还没到，按预计落点上的代表色圆角矩形（[snapshot]）算，只画颗粒
     *   （[drawSurface] 为 false），代理卡片本身不露出来；
     * - 拼卡那一层：列表腾出位置之后按真实位置建，运动按同样大小的代表色卡片（[dynamics]）算、颜色取真实
     *   快照（[snapshot]），与前一层逐片重合；光尘就近接手流向四周的一部分颗粒（[handoff]）。
     *
     * 整段历史先算完才出第一帧；不自己结束，由调用方撤。[dynamics] 在建材线程上读，之后交给垃圾回收
     * （不在这里回收：撤下时建材可能还没读完）。
     *
     * [deferForeground] 时 [snapshot] 只是占位（代表色卡片，材料按它建），拼卡那一层可以在列表刚排好、图片还没
     * 加载完的时候就开始建材与预算；真实快照截出来之后由 [supplyForeground] 交进来，换上前景贴图才出第一帧。
     */
    class ReceiveLayer(
        activity: Activity,
        snapshot: Bitmap,
        bounds: RectF,
        sink: PointF,
        frame: RectF,
        cards: List<RectF>,
        position: Float,
        alpha: Float,
        drawSurface: Boolean,
        private val listener: Listener,
        dynamics: Bitmap? = null,
        handoff: ParticleHandoffSource? = null,
        dumpKind: String? = null,
        private val deferForeground: Boolean = false,
        /** 一次接收里两层用同一个种子：预计准确时两层逐片相同，交叉淡化只换颜色（与模拟器相同）。 */
        seed: Int
    ) {
        interface Listener {
            /** 整段算完、第一帧已经上屏。 */
            fun onStarted()

            /** 窗口实际呈现的正向位置（0–1）。 */
            fun onPresented(position: Float)

            /** 粒子层没能建起来或中途失败。 */
            fun onLost()
        }

        private val main = Handler(Looper.getMainLooper())
        private val gesture = ParticleGestureProgress(initiallyActive = true, prepareAll = true, drawSurface = drawSurface)
        private var overlay: ParticleDismissOverlay? = null
        private var dump: TapFixtureDump? = null
        private var started = false
        private var released = false

        /** 这一层建材时的终点几何与卡片矩形（动画层像素），光尘接手与会话记录用。 */
        val cards: List<RectF> = cards.map { RectF(it) }

        init {
            gesture.seek(position)
            gesture.layerAlpha = alpha.coerceIn(0f, 1f)
            val decor = decorOf(activity)
            if (decor == null) {
                snapshot.recycle()
                main.post { listener.onLost() }
            } else {
                val params = TapParticleParams.get(activity)
                val spec = spec(snapshot, bounds, sink, frame, params.frameShare, params, params.receiveField(cards),
                    dynamics, handoff, layerAlpha = true, deferForeground = deferForeground, seed = seed)
                val dump = dumpKind?.let { TapFixtureDump.begin(activity, it, spec, bounds, sink, frame, params) }
                this.dump = dump
                val created = ParticleDismissOverlay(
                    activity, spec,
                    onAnimationStarted = Runnable {
                        if (!released && !started) {
                            started = true
                            listener.onStarted()
                            dump?.captureWindow("background")
                        }
                    },
                    onDone = Runnable {
                        // 不是我们主动撤的：会话失败或看门狗收尾
                        if (!released) {
                            released = true
                            listener.onLost()
                        }
                    },
                    onPresentedProgress = { presented -> if (!released) listener.onPresented(presented) },
                    gesture = gesture
                )
                overlay = created
                ParticleOverlayHost.attach(decor, created)
            }
        }

        val isStarted: Boolean get() = started

        fun seek(position: Float) {
            gesture.seek(position)
        }

        /** 提前建的这一层交进真实快照（与占位快照同尺寸）：会话换上前景贴图之后出第一帧。 */
        fun supplyForeground(bitmap: Bitmap) {
            if (released || !deferForeground) return
            dump?.replaceSnapshot(bitmap)
            gesture.supplyForeground(bitmap)
        }

        var alpha: Float
            get() = gesture.layerAlpha
            set(value) {
                gesture.layerAlpha = value.coerceIn(0f, 1f)
            }

        /** 等 [after] 所在窗口画出下一帧再撤（真实卡片刚显示出来，交接处不闪）；[after] 为空时立即撤。 */
        fun release(after: View?) {
            if (released) return
            released = true
            val current = overlay
            overlay = null
            if (current == null || after == null) {
                current?.release()
                return
            }
            afterFrame(after) { current.release() }
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
