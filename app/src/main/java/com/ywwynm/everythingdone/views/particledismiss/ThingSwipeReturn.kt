package com.ywwynm.everythingdone.views.particledismiss

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.RecyclerView
import com.ywwynm.everythingdone.utils.ThingAnimationPreferences

/**
 * 滑动之后仍然留在列表里的卡片，怎么回到原位。
 *
 * 习惯左滑「完成」只是记一次打卡，目标等记事也可能仍留在原位，私密记事还要先认证、卡片同样
 * 得先回来。按设置里的「滑动完成记事动画风格」把回位播成动画：滑动档从左侧滑回并淡入，
 * 粒子档倒放消散、粒子重新聚成卡片。
 *
 * ## 真机实测的前提（2026-09-16，OPD2515）
 *
 * 这块按推断写过两版都不成立，下面每一条都是探针量出来的：
 *
 * 1. 滑走之后卡片停在 `translationX = -RecyclerView.width`（这台设备上 −2520 px，不是卡片自己
 *    的 577 px），透明度 0。
 * 2. **被滑走的那张 itemView 仍归 ItemTouchHelper 管**：它在待清理队列里，每一帧都会被按回
 *    −2520，直到 `clearView` 到来才松手。因此**绝不能在这张 View 上做回位**——之前两版都栽在
 *    这里。
 * 3. **`clearView` 由「这张 View 从窗口上摘下来」触发**，而摘下来又要靠 `notifyItemChanged`
 *    引发的换位动画：实测约 290 ms 到。如果用 payload 让 holder 原地复用，这张 View 永远不被
 *    摘下，`clearView` 2.6 秒都不来，卡片就一直停在屏外——这就是之前「卡片直接不见了」。
 * 4. **`notifyItemChanged` 会在约 25 ms 换上另一张 View**，它由 `DefaultItemAnimator` 从
 *    `tx = −2520`、透明度 0 动画回原位。这张新 View 不归 ItemTouchHelper 管，是自由的。
 *    默认那段动画**并不是没有**，而是卡片要走完约 75% 才进入屏幕，只剩最后几十毫秒可见——
 *    这正是「啪地弹回来」的由来。
 *
 * 所以这里的做法是：**接管那张新 View**。在业务判定的下一帧把它找出来，结束掉默认的换位动画，
 * 按档位重新播一遍——滑动档从卡片自己左边一个身位滑回来（全程可见），粒子档倒放消散。
 * 接管不成（找不到新 View 等）就什么也不做，默认动画照旧把卡片放回原位，最坏只是没有改善，
 * 不会把卡片弄丢。
 */
internal class ThingSwipeReturn(
    private val activity: Activity,
    private val recyclerView: RecyclerView
) {

    private val main = Handler(Looper.getMainLooper())
    private val running = java.util.IdentityHashMap<View, Session>()

    private class Session {
        var overlay: ParticleDismissOverlay? = null
        var rescue: Runnable? = null
    }

    /**
     * 业务侧确认 [listPosition] 上这张卡片留在列表里，从这一刻起接管它的回位。
     *
     * [particles] 表示这次滑走本身可能是粒子消散：回位要与刚飞出去的那一段同一种语言，首页
     * 右滑（开始做事）一直只是滑动淡出，那就只能滑回来，不能凭设置档位倒放出一段粒子。
     */
    fun start(listPosition: Int, particles: Boolean) {
        // 这一刻位置上还是被滑走的旧 View；换位动画要到下一次布局才把新 View 放上去。
        takeOver(listPosition, particles, RESOLVE_RETRIES)
    }

    private fun takeOver(listPosition: Int, particles: Boolean, retries: Int) {
        recyclerView.post {
            if (activity.isFinishing || activity.isDestroyed) return@post
            val holder = recyclerView.findViewHolderForAdapterPosition(listPosition)
            val itemView = holder?.itemView
            if (itemView == null || itemView.width <= 0) {
                if (retries > 0) takeOver(listPosition, particles, retries - 1)
                return@post
            }
            // 结束默认的换位动画，免得它继续写 translationX / alpha。
            recyclerView.itemAnimator?.endAnimation(holder)
            play(itemView, particles)
        }
    }

    private fun play(itemView: View, particles: Boolean) {
        stop(itemView)
        val session = Session()
        running[itemView] = session
        // 兜底：无论哪一步出意外，卡片都不能留在屏外或透明。
        val rescue = Runnable {
            if (running[itemView] === session) { stop(itemView); settle(itemView) }
        }
        session.rescue = rescue
        main.postDelayed(rescue, RESCUE_MS)

        val useParticles = particles && ThingAnimationPreferences.swipe(activity) ==
            ThingAnimationPreferences.SWIPE_PARTICLE
        if (useParticles && condense(itemView, session)) return
        slideBack(itemView, session)
    }

    /** 这张卡片的回位动画是不是正在播。 */
    fun isReturning(itemView: View): Boolean = running.containsKey(itemView)

    /** 列表重建、退出页面等场合，把所有还在回位的卡片摆回原样。 */
    fun cancel() {
        for (view in running.keys.toList()) {
            stop(view)
            settle(view)
        }
    }

    private fun stop(itemView: View) {
        val session = running.remove(itemView) ?: return
        session.rescue?.let { main.removeCallbacks(it) }
        val restore = {
            ParticleDismissController.cancelAppearance(itemView)
            ParticleCardPose.release(itemView)
            itemView.alpha = 1f
        }
        val overlay = session.overlay
        if (overlay != null) overlay.releaseToSource(restore = restore) else restore()
    }

    /** 卡片应有的静止状态。 */
    private fun settle(itemView: View) {
        itemView.animate().cancel()
        itemView.translationX = 0f
        itemView.alpha = 1f
        itemView.visibility = View.VISIBLE
    }

    /** 倒放消散：粒子重新聚成卡片。起不来就返回 false，退回滑动回位。 */
    private fun condense(itemView: View, session: Session): Boolean {
        itemView.translationX = 0f
        itemView.alpha = 1f
        itemView.visibility = View.VISIBLE
        ParticleCardPose.hold(itemView)
        val started = ParticleDismissController.appearContent(
            activity, activity.window, itemView,
            appearanceDirection = APPEAR_FROM_LEFT_DEGREES,
            roundSnapshot = false,
            cardShadow = true,
            onAppearanceFinished = {
                if (running[itemView] === session) {
                    stop(itemView)
                    settle(itemView)
                }
            }) { session.overlay = it }
        if (!started) ParticleCardPose.release(itemView)
        return started
    }

    private fun slideBack(itemView: View, session: Session) {
        val distance = (if (itemView.width > 0) itemView.width else recyclerView.width).toFloat()
        itemView.translationX = -distance
        itemView.alpha = 0f
        itemView.visibility = View.VISIBLE
        // 先以透明度 0 可见地画过一帧再起步：挂在 RenderNode 上的动画在「从未参与绘制」的
        // View 上会立刻结束（与首页新卡片入场同一个坑）。
        itemView.post {
            if (running[itemView] !== session) return@post
            itemView.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(SLIDE_BACK_MS)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    if (running[itemView] === session) {
                        stop(itemView)
                        settle(itemView)
                    }
                }
                .start()
        }
    }

    private companion object {
        /**
         * 滑回原位的时长。卡片从自己左边一个身位滑回来，全程可见；与列表自身 250 ms 的移动／
         * 变更动画同量级，取偏好数 256（推导值 250，差 6）。
         */
        const val SLIDE_BACK_MS = 256L

        /** 180° 让虚拟触点落在卡片正左方，正好是左滑消散的镜像，粒子从同一侧聚回来。 */
        const val APPEAR_FROM_LEFT_DEGREES = 180f

        /** 换位动画要到下一次布局才把新 View 放上去；多给几帧，找不到就放弃接管。 */
        const val RESOLVE_RETRIES = 4

        /** 兜底：粒子倒放约 0.6 秒，留足余量。 */
        const val RESCUE_MS = 2000L
    }
}
