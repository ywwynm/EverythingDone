package com.ywwynm.everythingdone.views.particledismiss

import android.graphics.Bitmap
import android.view.animation.AnimationUtils
import com.ywwynm.everythingdone.BuildConfig
import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.roundToInt

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

    fun seek(value: Float) {
        val next = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
        if (BuildConfig.DEBUG && next != progress) synchronized(changes) {
            if (changes.size < 2048) {
                changes += System.nanoTime()
                // 在主线程的一帧之内（输入、动画、绘制阶段）这是本帧的 vsync 时刻（毫秒）；JVM 单测里没有实现，记 -1
                changeFrames += runCatching { AnimationUtils.currentAnimationTimeMillis() }.getOrDefault(-1L)
            }
        }
        progress = next
    }

    /** 仅调试包：位置真的变了的时刻与它所属的主线程帧，用来判断跟手出帧受不受位置更新的频率与相位限制。 */
    private val changes = ArrayList<Long>()
    private val changeFrames = ArrayList<Long>()

    /** 仅调试包：每次取位置出帧的节拍目标、取位置的时刻、取到的是第几次变化。 */
    private val reads = ArrayList<LongArray>()

    /**
     * 仅调试包：位置变化次数与间隔；出帧取到的位置落后几拍（0/1/2/3+）；漏掉的节拍里那一帧主线程有没有更新
     * （有、但晚于 GL 读取，即两条线程在同一个 vsync 之后抢先后）；一次跨过两次以上更新的帧数；
     * 主线程更新距本帧 vsync、GL 读取距节拍目标的中位 / p90（毫秒）。
     */
    fun changeSummary(): String = synchronized(changes) {
        if (changes.size < 2) return "位置变化=${changes.size}"
        val gaps = (1 until changes.size).map { (changes[it] - changes[it - 1]) / 1e6 }.sorted()
        val base = "位置变化=${changes.size} 间隔中位Ms=${"%.1f".format(gaps[gaps.size / 2])} " +
            "用时Ms=${(changes.last() - changes.first()) / 1_000_000}"
        if (reads.size < 2) return base
        val period = ParticleClock.outputPeriodNanos.toDouble()
        val lags = IntArray(4)
        for (read in reads) {
            val index = read[2].toInt()
            if (index < 0) continue
            lags[((read[0] - changeFrames[index] * 1_000_000L) / period).roundToInt().coerceIn(0, 3)]++
        }
        var skipped = 0; var lateUpdate = 0; var noUpdate = 0; var jumps = 0
        for (k in 1 until reads.size) {
            if (reads[k][2] - reads[k - 1][2] >= 2) jumps++
            val missing = ((reads[k][0] - reads[k - 1][0]) / period).roundToInt() - 1
            for (m in 1..missing) {
                skipped++
                val vsyncMs = (reads[k - 1][0] + (m * period).toLong()) / 1_000_000L
                if (changeFrames.any { abs(it - vsyncMs) <= 1 }) lateUpdate++ else noUpdate++
            }
        }
        val updateOffsets = changes.indices.map { (changes[it] - changeFrames[it] * 1_000_000L) / 1e6 }.sorted()
        val readOffsets = reads.map { (it[1] - it[0]) / 1e6 }.sorted()
        "$base 出帧=${reads.size} 取位落后拍数=${lags.joinToString("/")} 漏拍=$skipped（本拍有更新但晚于读取=$lateUpdate " +
            "本拍无更新=$noUpdate） 一次跨两次以上更新=$jumps 更新距本帧vsyncMs=${spread(updateOffsets)} " +
            "读取距节拍Ms=${spread(readOffsets)}"
    }

    private fun spread(sorted: List<Double>) =
        if (sorted.isEmpty()) "-" else "%.1f/%.1f".format(sorted[sorted.size / 2], sorted[sorted.size * 9 / 10])

    @Synchronized fun submitted(timestamp: Long, value: Float) {
        if (BuildConfig.DEBUG) synchronized(changes) {
            if (reads.size < 2048) reads += longArrayOf(timestamp, System.nanoTime(), (changes.size - 1).toLong())
        }
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
