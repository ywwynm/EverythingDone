package com.ywwynm.everythingdone.utils

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * 全应用唯一的动画时间基准。
 *
 * 第一批曾用「应用基准（`Settings.Global`）↔ 框架实际缩放」的反向换算，让自绘涟漪与暗层
 * 不跟随框架缩放、其余动画仍跟随；口径不统一（2026-09-14 用户裁定撤回）。第二批改为单一基准：
 *
 * - API 33+：读公开的 [ValueAnimator.getDurationScale]，并用
 *   [ValueAnimator.registerDurationScaleChangeListener] 跟随系统设置的变化。
 *   这正是框架给每个 [ValueAnimator] 施加的那个值，两边不可能再不一致。
 * - API 33 以下：回退 `Settings.Global.ANIMATOR_DURATION_SCALE`（框架自己读的也是这一行）。
 *
 * 自算时长（粒子播放、看门狗、触摸反馈结算超时）乘 [scale]；交给 [ValueAnimator] /
 * [android.view.ViewPropertyAnimator] 的一律传**标称时长**，由框架乘同一个值。
 * 1× 下两条路径都等于标称值，与改动前的发布配置完全一致。
 */
object AnimatorDurationScale {

    @Volatile private var appContext: Context? = null

    /** 最近一次读到的缩放；≤0 表示尚未读到。 */
    @Volatile private var cached = -1f

    @Volatile private var attached = false

    /** 进程启动后尽早调用一次：记下应用上下文并读出当前缩放。必须在主线程调用。 */
    @JvmStatic
    fun attach(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        cached = read()
        if (attached) return
        attached = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) runCatching {
            ValueAnimator.registerDurationScaleChangeListener { value -> cached = sanitize(value) }
        }
    }

    /** 当前时间基准；始终 ≥ [MINIMUM]，可直接作除数。 */
    @JvmStatic
    fun scale(): Float {
        val value = cached
        return if (value > 0f) value else read()
    }

    /** 有上下文时可用于 API 33 以下的回退读取。 */
    @JvmStatic
    fun scale(context: Context): Float {
        if (appContext == null) appContext = context.applicationContext
        val value = cached
        return if (value > 0f) value else sanitize(settingsScale(context))
    }

    private fun read(): Float {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val value = runCatching { ValueAnimator.getDurationScale() }.getOrNull()
            if (value != null) return sanitize(value)
        }
        val context = appContext ?: return 1f
        return sanitize(settingsScale(context))
    }

    private fun settingsScale(context: Context): Float = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)

    /**
     * 系统把动画整体关掉时缩放是 0；粒子路径在那种情况下根本不启动（`areAnimatorsEnabled`），
     * 但时长仍会被当除数用，所以给一个下限。纯算术，便于 JVM 测试。
     */
    @JvmStatic
    fun sanitize(value: Float): Float =
        if (!value.isFinite() || value <= 0f) MINIMUM else value.coerceAtLeast(MINIMUM)

    /** 下限：与第一批一致，避免除零并防止把短动画压成 0 帧。 */
    const val MINIMUM = .1f
}
