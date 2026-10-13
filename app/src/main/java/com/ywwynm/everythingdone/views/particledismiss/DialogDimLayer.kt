package com.ywwynm.everythingdone.views.particledismiss

import android.animation.TimeInterpolator
import android.app.Activity
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.core.view.doOnLayout
import java.util.WeakHashMap

/**
 * Dialog 的背景暗层，从出现到消失全程由应用接管（主题已禁用系统
 * FLAG_DIM_BEHIND）。
 *
 * 系统 dim 由 WMS 管理，其值变化与 window 移除在部分系统上带有不受控的过渡
 * 动画——与应用侧暗层跨窗口交接时无法保证同帧生效，是历次"关闭瞬间闪烁"的
 * 结构性根源（2026-08-26 用户提议全程接管后定案）。本层挂在宿主 Activity 的
 * DecorView 上、位于 Dialog window 之下：show 时淡入；dismiss 时由调用方按
 * 路径选择淡出时长——粒子消散路径与动画等长（前慢后快，粒子浓密期背景保持
 * 暗），普通路径短淡出。单一图层、单一 window、无交接，无闪烁窗口。
 */
internal class DialogDimLayer private constructor(
    private val view: View,
    private val decor: ViewGroup?
) {

    private var detachStarted = false
    private var appearanceStarted = false

    /**
     * 淡入等暗层真的拿到尺寸再起步。
     *
     * 宿主首帧之前挂上来时（透明宿主在 onCreate / onPostCreate 里就提交弹窗）这一层
     * 还是 0×0、也从未进入绘制树：此刻在 overlay 里启动的 RenderThread 动画会立刻
     * 结束，而且 0×0 的层本来也画不出任何东西。宿主已经布局过时 doOnLayout 当场执行，
     * 与改动前逐帧一致。
     */
    private fun fadeIn() {
        view.doOnLayout {
            if (detachStarted || appearanceStarted) return@doOnLayout
            view.animate().alpha(DIM_AMOUNT).setDuration(scaled(FADE_IN_MS)).start()
        }
    }

    /** 使用实际已合成的粒子进度；准备阶段保持透明，末帧到达目标浓度。 */
    fun setAppearanceProgress(progress: Float) {
        if (detachStarted) return
        val p = progress.coerceIn(0f, 1f)
        if (p >= 1f) {
            view.animate().cancel()
            view.alpha = DIM_AMOUNT
        } else if (!appearanceStarted) {
            appearanceStarted = true
            val from = p * p * (3f - 2f * p)
            view.alpha = DIM_AMOUNT * from
            // 首个实际纹理帧才启动，使用同一逻辑时长；交接末帧再精确收尾。
            // 让 HWUI 持续驱动暗层，避免焦点在透明 Dialog 时下方窗口被当成静止内容。
            view.animate().alpha(DIM_AMOUNT)
                .setDuration(scaled(((1f-p) * ParticleReversePlan.APPEARANCE_SECONDS * 1000).toLong()))
                .setInterpolator { fraction ->
                    val t = p + (1f-p) * fraction
                    (t*t*(3f-2f*t) - from) / (1f-from).coerceAtLeast(.000001f)
                }.start()
        }
    }

    /** 幂等：重复调用（各 dismiss 路径的兜底）只有第一次生效。 */
    fun fadeOutAndDetach(durationMs: Long, interpolator: TimeInterpolator? = null) {
        if (detachStarted) return
        detachStarted = true
        view.animate().cancel()
        val animator = view.animate().alpha(0f).setDuration(scaled(durationMs))
        if (interpolator != null) {
            animator.interpolator = interpolator
        }
        animator.withEndAction {
            synchronized(attached) { attached.remove(view) }
            ParticleOverlayHost.detach(decor, view)
        }
        animator.start()
    }

    companion object {
        /** Dialog 面板进入动画的量级。 */
        const val FADE_IN_MS = 220L

        /** 普通（非粒子）dismiss 路径的淡出时长。 */
        const val FADE_OUT_MS = 220L

        /** 主题禁用系统 dim 后由这里统一给值（平台默认浓度）。 */
        const val DIM_AMOUNT = 0.6f

        fun attach(activity: Activity, animateIn: Boolean = true): DialogDimLayer? {
            if (activity.isFinishing || activity.isDestroyed) return null
            val decor = activity.window?.decorView as? ViewGroup ?: return null
            if (!decor.isAttachedToWindow) return null
            // 唯一内容是纯黑背景，alpha 可直接作用于颜色；没有需要先合成的重叠子内容。
            val view = object : View(activity) {
                override fun hasOverlappingRendering() = false
            }.apply {
                setBackgroundColor(Color.BLACK)
                alpha = 0f
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            // Doing 的整页 GL 也必须包含 Dialog 暗层；单独留在 Decor overlay 会被
            // on-top Surface 遮住，背景控件无法按原有 0.6 浓度一起变暗。
            val drawingHost = if (android.os.Build.VERSION.SDK_INT >= 34)
                ParticleDoingPage.contentOf(activity) ?: decor else decor
            ParticleOverlayHost.attach(drawingHost, view)
            synchronized(attached) { attached[view] = decor }
            return DialogDimLayer(view, drawingHost).also { if (animateIn) it.fadeIn() }
        }

        /** 挂着的暗层 → 宿主 decor（弱引用键，层被回收即自动移除）。 */
        private val attached = WeakHashMap<View, ViewGroup>()

        /**
         * [decor] 上暗层此刻的总遮盖度：叠了几层时为 1 − 各层透过率之积。主线程调用。
         *
         * 飘散样式按背景亮度选合成方式时要把暗层折算掉（[ParticleDriftBackground]）：暗层挂在 Activity 窗口里，
         * PixelCopy 读得到它，而它在消散的前半段就淡掉了。
         */
        fun coverage(decor: View): Float {
            var pass = 1f
            synchronized(attached) {
                for ((view, host) in attached) if (host === decor && view.isAttachedToWindow) pass *= 1f - view.alpha
            }
            return 1f - pass
        }

        /**
         * 暗层与粒子跑在同一条时间基准上：ViewPropertyAnimator 自己会乘
         * [com.ywwynm.everythingdone.utils.AnimatorDurationScale] 那个值，而粒子时长也按同一个值算，
         * 所以这里交标称时长即可（第二批统一时间基准后不再做反向换算）。
         */
        private fun scaled(nominalMs: Long): Long = nominalMs
    }
}
