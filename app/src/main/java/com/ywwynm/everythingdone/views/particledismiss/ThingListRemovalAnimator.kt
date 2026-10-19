package com.ywwynm.everythingdone.views.particledismiss

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.utils.ThingAnimationPreferences
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 一次业务变更对应一次退场：先比较真实投影，再合成可见卡片；粒子与前景完全释放后补位。
 * 宿主在 before 中固定旧 adapter 数据，在 publish 中解除固定并派发传入的差分。
 */
internal class ThingListRemovalAnimator(
    private val activity: FragmentActivity,
    private val list: RecyclerView
) {
    private val main = Handler(Looper.getMainLooper())
    private var current: Removal? = null
    val isRunning get() = current != null

    fun start(before: () -> List<Long>, change: () -> List<Long>, publish: (DiffUtil.DiffResult) -> Unit,
              isUiAnimating: () -> Boolean = { false }, finishUi: () -> Unit = {}) {
        if (isRunning) return
        Removal(before, change, publish, isUiAnimating, finishUi).also { current = it }.waitForDialogs()
    }

    /** 页面离开时仍提交已确认的动作，立即归还 holder、布局和动画资源。 */
    fun finish() { current?.finishImmediately() }

    private inner class Removal(
        val before: () -> List<Long>,
        val change: () -> List<Long>,
        val publish: (DiffUtil.DiffResult) -> Unit,
        val isUiAnimating: () -> Boolean,
        val finishUi: () -> Unit
    ) {
        var diff: DiffUtil.DiffResult? = null
        var oldIds = emptyList<Long>()
        var removed = emptySet<Long>()
        var applied = false
        var published = false
        var closed = false
        var overlay: ParticleDismissOverlay? = null
        val sources = mutableListOf<View>()
        val releases = mutableListOf<() -> Unit>()
        val wait = Runnable { waitForDialogs() }
        var settledFrames = 0
        val settle = object : Runnable {
            override fun run() {
                if (closed) return
                if (!list.isAttachedToWindow) { finishImmediately(); return }
                if (list.isLayoutRequested || list.isComputingLayout || animator?.isRunning == true || isUiAnimating())
                    settledFrames = 0
                else settledFrames++
                if (settledFrames >= 2) close() else list.postOnAnimation(this)
            }
        }
        val originalAnimator = list.itemAnimator
        var animator = originalAnimator
        val removeDuration = animator?.removeDuration ?: 0L

        fun waitForDialogs() {
            if (closed) return
            if (activity.isFinishing || activity.isDestroyed || !list.isAttachedToWindow) {
                finishImmediately(); return
            }
            // 确认回调先于 Dialog.dismiss；至少返回消息循环一次，再观察实际可见状态。
            val dialogVisible = activity.supportFragmentManager.fragments.any {
                it is DialogFragment && it.dialog?.isShowing == true
            }
            val dialogLayer = ParticleDismissOverlay.activeIn(activity).any { it.parent != null }
            if (dialogVisible || dialogLayer) {
                main.postDelayed(wait, 32L)
                return
            }
            applyChange()
            if (!ValueAnimator.areAnimatorsEnabled() || removed.isEmpty()) {
                publishOnce(); return
            }
            for (i in 0 until list.childCount) {
                val view = list.getChildAt(i)
                val position = list.getChildAdapterPosition(view)
                if (oldIds.getOrNull(position) in removed && view.isShown && view.alpha > 0f &&
                    visibleBounds(view) != null) sources += view
            }
            if (sources.isEmpty()) { publishOnce(); return }
            if (ThingAnimationPreferences.swipe(activity) != ThingAnimationPreferences.SWIPE_PARTICLE) {
                fade(); return
            }
            capture()
        }

        fun applyChange() {
            if (applied) return
            applied = true
            list.stopScroll()
            animator?.endAnimations()
            oldIds = before().toList()
            list.suppressLayout(true)
            try {
                val comparison = ThingListRemovalDiff(oldIds, change().toList())
                removed = comparison.removed
                diff = DiffUtil.calculateDiff(comparison)
                if (originalAnimator is androidx.recyclerview.widget.DefaultItemAnimator) {
                    val positions = mutableMapOf<Long, android.graphics.Point>()
                    for (i in 0 until list.childCount) {
                        val view = list.getChildAt(i)
                        val id = oldIds.getOrNull(list.getChildAdapterPosition(view)) ?: continue
                        if (id !in removed && view.isShown && view.alpha > 0f && visibleBounds(view) != null) {
                            positions[id] = android.graphics.Point(view.x.toInt(), view.y.toInt())
                        }
                    }
                    animator = ThingListRetainedItemAnimator(originalAnimator, positions)
                    list.itemAnimator = animator
                }
            } catch (error: Throwable) {
                list.suppressLayout(false)
                current = null
                throw error
            }
        }

        /** 屏幕坐标，扣除顶栏的遮挡；FAB 保持由现有前景层覆盖粒子。 */
        fun visibleBounds(view: View): Rect? {
            val viewport = Rect()
            if (!list.getGlobalVisibleRect(viewport)) return null
            ParticleHomeForeground.opaqueBottom(view)?.let { viewport.top = maxOf(viewport.top, it) }
            val at = IntArray(2).also { view.getLocationOnScreen(it) }
            val local = ParticleCardClip.visibleRect(view) ?: Rect(0, 0, view.width, view.height)
            local.offset(at[0], at[1])
            return local.takeIf { it.intersect(viewport) && !it.isEmpty }
        }

        fun capture() {
            data class Piece(val view: View, val bounds: Rect, val shadow: ParticleCardSnapshot?,
                             val screen: Rect, val bodyArea: Double)
            val pieces = sources.mapNotNull { view ->
                ParticleCardPose.hold(view)
                releases += ParticleSnapshot.hold(view)
                val visibleBody = visibleBounds(view) ?: return@mapNotNull null
                val at = IntArray(2).also { view.getLocationOnScreen(it) }
                val shadow = ParticleCardSnapshot.of(view)
                val bounds = shadow?.bounds?.let(::Rect) ?: Rect(0, 0, view.width, view.height)
                // 阴影允许占用相邻空白，但同样不能越过列表或顶栏。
                val viewport = Rect()
                list.getGlobalVisibleRect(viewport)
                ParticleHomeForeground.opaqueBottom(view)?.let { viewport.top = maxOf(viewport.top, it) }
                viewport.offset(-at[0], -at[1])
                if (!bounds.intersect(viewport)) return@mapNotNull null
                Piece(view, bounds, shadow?.clippedTo(bounds),
                    Rect(bounds).apply { offset(at[0], at[1]) },
                    visibleBody.width().toDouble() * visibleBody.height())
            }
            if (pieces.isEmpty()) { finishImmediately(); return }
            val union = Rect(pieces.first().screen)
            pieces.drop(1).forEach { union.union(it.screen) }
            val bitmap = try { Bitmap.createBitmap(union.width(), union.height(), Bitmap.Config.ARGB_8888) }
                catch (_: OutOfMemoryError) { fade(); return }
            val canvas = Canvas(bitmap)
            var remaining = pieces.size
            var failed = false
            // 逐片只拍一次，最终只建一份粒子材料、一条会话。
            for (piece in pieces) {
                val captured: (Bitmap?) -> Unit = { image ->
                    if (image == null) failed = true
                    else {
                        if (!closed && !published) canvas.drawBitmap(image,
                            (piece.screen.left - union.left).toFloat(),
                            (piece.screen.top - union.top).toFloat(), null)
                        image.recycle()
                    }
                    remaining--
                    if (remaining == 0) {
                        if (closed || published) bitmap.recycle()
                        else if (failed) { bitmap.recycle(); fade() }
                        else play(bitmap, union, pieces.sumOf { it.bodyArea })
                    }
                }
                if (Build.VERSION.SDK_INT >= 29)
                    ParticleHardwareSnapshot.capture(piece.view, piece.bounds, piece.shadow, captured)
                else ParticleDismissController.captureContent(piece.view, piece.bounds, captured)
            }
        }

        fun play(bitmap: Bitmap, screen: Rect, bodyArea: Double) {
            val decor = activity.window.decorView as ViewGroup
            val at = IntArray(2).also { decor.getLocationOnScreen(it) }
            val x = (screen.left - at[0]).toFloat()
            val y = (screen.top - at[1]).toFloat()
            // 同一批可见卡片共用一次方向选择，保持合成材料内的运动一致。
            val angle = ParticleDismissDirection.randomRadians()
            val reach = hypot(bitmap.width.toFloat(), bitmap.height.toFloat())
            val spec = ParticleDismissSpec(bitmap, x, y,
                x + bitmap.width / 2f + cos(angle) * reach,
                y + bitmap.height / 2f + sin(angle) * reach,
                Settings.Global.getFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f),
                ParticleCardSurfaceHandoff.seed(), inWindow = true,
                materialAreaScale = maxOf(1f, (bitmap.width.toDouble() * bitmap.height / bodyArea).toFloat()))
            val layer = ParticleDismissOverlay(activity, spec,
                onSourcesRestored = Runnable { if (!published) sources.forEach { it.visibility = View.INVISIBLE } },
                cardSource = list, cardSources = sources.toList(), restoreSourceOnRelease = false)
            overlay = layer
            layer.afterRelease(Runnable { if (!closed) publishOnce() })
            ParticleOverlayHost.attach(decor, layer)
            if (!ParticleCardSurfaceHandoff.supported()) sources.forEach { it.alpha = 0f }
            if (BuildConfig.DEBUG) android.util.Log.i("ThingListRemoval",
                "particle sources=${sources.size} removed=${removed.size} bitmap=${bitmap.width}x${bitmap.height} " +
                    "cellDp=${ParticlePerfTuning.microflakeCellDpNow}")
        }

        fun fade() {
            if (closed || published) return
            var remaining = sources.size
            if (remaining == 0) { finishImmediately(); return }
            sources.forEach { view ->
                view.animate().translationX(-view.width * .12f).alpha(0f).setDuration(180L)
                    .withEndAction {
                        remaining--
                        if (remaining == 0) publishOnce()
                    }.start()
            }
        }

        fun publishOnce() {
            if (published) return
            applyChange()
            published = true
            overlay?.detachCardSources()
            sources.forEach {
                it.animate().cancel()
                ParticleCardPose.release(it)
                it.visibility = View.INVISIBLE
                it.translationX = 0f
            }
            releaseSnapshots()
            // 退场已经由本会话完成，只让 RecyclerView 负责保留项的平滑移动。
            animator?.removeDuration = 0L
            publish(checkNotNull(diff))
            list.suppressLayout(false)
            list.postOnAnimation(settle)
            if (BuildConfig.DEBUG) android.util.Log.i("ThingListRemoval", "reflow removed=${removed.size}")
        }

        fun releaseSnapshots() {
            val callbacks = releases.toList()
            releases.clear()
            callbacks.forEach { it() }
        }

        fun finishImmediately() {
            if (closed) return
            publishOnce()
            overlay?.release()
            finishUi()
            animator?.endAnimations()
            close()
        }

        fun close() {
            if (closed) return
            closed = true
            main.removeCallbacks(wait)
            list.removeCallbacks(settle)
            animator?.removeDuration = removeDuration
            if (animator !== originalAnimator && list.itemAnimator === animator) {
                if (originalAnimator is androidx.recyclerview.widget.SimpleItemAnimator &&
                    animator is androidx.recyclerview.widget.SimpleItemAnimator) {
                    originalAnimator.supportsChangeAnimations =
                        (animator as androidx.recyclerview.widget.SimpleItemAnimator).supportsChangeAnimations
                }
                list.itemAnimator = originalAnimator
            }
            releaseSnapshots()
            if (current === this) current = null
        }
    }
}
