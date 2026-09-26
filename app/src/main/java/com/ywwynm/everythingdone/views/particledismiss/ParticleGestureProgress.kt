package com.ywwynm.everythingdone.views.particledismiss

import android.graphics.Bitmap
import java.util.TreeMap

/**
 * 手指与 ItemTouchHelper 的松手恢复动画共用绝对距离，不累加时间或位移。
 *
 * 碰一碰也用它按外部给的位置取帧：发送的流出与重新抬起按进度动画 [seek]；接收的两层按倒放时间轴每帧
 * [seek]，并且
 * - [prepareAll]：整段历史先算完、之前一帧不画（接收的两层从时间轴的末端开始播，按需补档会先画出
 *   还没散开的卡片）；
 * - [layerAlpha]：整层的不透明度（两层交叉淡化），会话每次绘制前交给渲染器；
 * - [drawSurface]：是否画「原表面」那一遍。进度期间那一层只画颗粒，代理卡片本身不能露出来；
 * - [supplyForeground]：规格打开了 `deferForeground` 时，真实快照稍后交进来（拼卡那一层提前建）。
 */
internal class ParticleGestureProgress(
    initiallyActive: Boolean = true,
    val prepareAll: Boolean = false,
    val drawSurface: Boolean = true
) {
    /** 整层的不透明度（0–1），主线程写、GL 线程读。只有规格打开了 `layerAlpha` 的会话才用它。 */
    @Volatile var layerAlpha = 1f

    /** 稍后交进来的真实快照（与占位快照同尺寸）；主线程写、GL 线程读。 */
    @Volatile var foreground: Bitmap? = null
        private set

    /** 交进真实快照并唤醒 GL 线程：会话换上前景贴图之后出第一帧。 */
    fun supplyForeground(bitmap: Bitmap) {
        foreground = bitmap
        ParticleGl.wake()
    }

    @Volatile var active = initiallyActive
        private set
    @Volatile var activatedAtNanos = if (initiallyActive) System.nanoTime() else 0L
        private set
    fun activate() {
        if (active) return
        activatedAtNanos = System.nanoTime()
        active = true
    }
    @Volatile var progress = 0f
        private set
    @Volatile var presented = false
    @Volatile var presentedProgress = 0f
        private set
    private val submissions = TreeMap<Long, Float>()
    @Volatile private var completing = false
    @Volatile private var completionFloor = 0f
    private var lastSubmittedProgress = 0f

    /** 完成动作只能继续消散；已提交的帧可能早于呈现通知出现在窗口中。 */
    @Synchronized fun beginCompletion() {
        completionFloor = lastSubmittedProgress
        completing = true
    }

    fun update(displacement: Float, width: Int) {
        seek(if (width > 0 && displacement.isFinite()) -displacement / width else 0f)
    }

    fun seek(value: Float) { progress = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f }

    @Synchronized fun submitted(timestamp: Long, value: Float) {
        lastSubmittedProgress = value
        if (completing) completionFloor = maxOf(completionFloor, value)
        submissions[timestamp] = value
        while (submissions.size > 120) submissions.pollFirstEntry()
    }

    /** 使用窗口实际消费的帧，不能把最新手指位置误当成已显示进度。 */
    @Synchronized fun progressAt(timestamp: Long): Float {
        submissions[timestamp]?.let { presentedProgress = it }
        submissions.headMap(timestamp, false).clear()
        return presentedProgress
    }

    /** 识别左滑后首帧直接使用当前距离，不再等待一个已经过时的零进度帧上屏。 */
    fun framePosition(samples: Int): Float =
        (if (!active) 0f else if (completing) maxOf(progress, completionFloor) else progress) * samples
}
