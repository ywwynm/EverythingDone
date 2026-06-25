package com.ywwynm.everythingdone.views

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import android.widget.SeekBar
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.DisplayUtil
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 统一的"比例/裁切比例"调节滑条：内部组合一个 [SeekBar] 与一个 [RatioTicksView]，
 * 把档位梯子、对数映射、snapping、拖动锁范围、主题色全部封装在内。
 *
 * 四处调用点（卡片外观 panel、卡片封面裁切 dialog、详情图片 dialog、抽屉头图 dialog）
 * 共用同一套档位与手感，差异只在各自传入的 [min, max] 范围。
 */
class RatioSlider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val seekBar = SeekBar(context)
    private val ticks = RatioTicksView(context)

    private var minRatio = 0.5
    private var maxRatio = 2.0
    private var currentRatio = 1.0

    /** 动态范围回调；非拖动时由调用点通过 [refreshRange] 触发重查。 */
    private var rangeProvider: (() -> Pair<Double, Double>)? = null

    /** 拖动期间锁定的范围；非 null 表示正在拖动。 */
    private var dragRange: Pair<Double, Double>? = null

    private var suppressListener = false

    /** 仅在用户交互（拖动/点击）改变比例时回调；programmatic 的 [setRatio] 不触发。 */
    var onRatioChanged: ((Double) -> Unit)? = null

    init {
        seekBar.max = SLIDER_MAX
        addView(
            seekBar,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        ticks.isClickable = false
        ticks.isFocusable = false
        ticks.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(
            ticks,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        ticks.setRatios(minRatio, maxRatio, PRESET_RATIOS, PRESET_LABELS)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (suppressListener || !fromUser) return
                handleUserProgress(progress)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                dragRange = currentEffectiveRange()
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                handleUserProgress(seekBar.progress)
                dragRange = null
                refreshRange()
            }
        })
    }

    private fun currentEffectiveRange(): Pair<Double, Double> {
        rangeProvider?.let { return it.invoke() }
        return minRatio to maxRatio
    }

    private fun handleUserProgress(progress: Int) {
        val (lo, hi) = dragRange ?: (minRatio to maxRatio)
        val rawRatio = ratioFromProgress(progress, lo, hi)
        val snapped = snap(rawRatio, lo, hi)
        val snappedProgress = ratioToProgress(snapped, lo, hi)
        if (seekBar.progress != snappedProgress) {
            suppressListener = true
            seekBar.progress = snappedProgress
            suppressListener = false
        }
        currentRatio = snapped
        ticks.setActiveRatio(snapped)
        onRatioChanged?.invoke(snapped)
    }

    fun setRangeProvider(provider: (() -> Pair<Double, Double>)?) {
        rangeProvider = provider
        refreshRange()
    }

    /** 重新向 [rangeProvider] 取范围并重定位 thumb；拖动中忽略。 */
    fun refreshRange() {
        if (dragRange != null) return
        val (lo, hi) = rangeProvider?.invoke() ?: return
        applyRange(lo, hi)
    }

    fun setRange(min: Double, max: Double) {
        if (dragRange != null) return
        applyRange(min, max)
    }

    private fun applyRange(min: Double, max: Double) {
        minRatio = min(min, max)
        maxRatio = max(min, max)
        ticks.setRatios(minRatio, maxRatio, PRESET_RATIOS, PRESET_LABELS)
        applyRatioInternal(currentRatio)
    }

    /** programmatic 设置比例：更新 thumb 与 active 档位，不触发 [onRatioChanged]；拖动中忽略。 */
    fun setRatio(ratio: Double) {
        if (dragRange != null) return
        applyRatioInternal(ratio)
    }

    private fun applyRatioInternal(ratio: Double) {
        val lo = minRatio
        val hi = maxRatio
        val progress = ratioToProgress(ratio, lo, hi)
        val snapped = snap(ratioFromProgress(progress, lo, hi), lo, hi)
        suppressListener = true
        seekBar.progress = progress
        suppressListener = false
        currentRatio = snapped
        ticks.setActiveRatio(snapped)
    }

    fun getRatio(): Double = currentRatio

    fun setAccentBackground(background: ThingBackground, textColor: Int) {
        ticks.setAccentBackground(background, textColor)
        DisplayUtil.setSeekBarBackground(seekBar, background)
    }

    fun setColors(tickColor: Int, textColor: Int) {
        ticks.setColors(tickColor, textColor)
        DisplayUtil.setSeekBarBackground(seekBar, ThingBackground.pure(tickColor))
    }

    companion object {
        const val SLIDER_MAX = 1000
        const val SNAP_PROGRESS_DISTANCE = 28

        /** 标准档位梯子（10 档），互为倒数在对数下关于 1:1 对称。 */
        val PRESET_RATIOS = doubleArrayOf(
            1.0 / 2.0,    // 1:2
            9.0 / 16.0,   // 9:16
            2.0 / 3.0,    // 2:3
            3.0 / 4.0,    // 3:4
            1.0,          // 1:1
            4.0 / 3.0,    // 4:3
            3.0 / 2.0,    // 3:2
            16.0 / 9.0,   // 16:9
            2.0,          // 2:1
            65.0 / 24.0   // 65:24
        )
        val PRESET_LABELS = arrayOf(
            "1:2", "9:16", "2:3", "3:4", "1:1", "4:3", "3:2", "16:9", "2:1", "65:24"
        )

        private fun safeRange(min: Double, max: Double): Pair<Double, Double> {
            var lo = min(min, max)
            var hi = max(min, max)
            if (lo.isNaN() || lo.isInfinite() || lo <= 0.0) lo = 0.0001
            if (hi.isNaN() || hi.isInfinite() || hi <= lo) hi = lo * 1.0001
            return lo to hi
        }

        fun ratioToProgress(ratio: Double, min: Double, max: Double): Int {
            val (lo, hi) = safeRange(min, max)
            val denom = ln(hi / lo)
            if (denom <= 0.0) return 0
            val clamped = ratio.coerceIn(lo, hi)
            val frac = ln(clamped / lo) / denom
            return (frac * SLIDER_MAX).roundToInt().coerceIn(0, SLIDER_MAX)
        }

        fun ratioFromProgress(progress: Int, min: Double, max: Double): Double {
            val (lo, hi) = safeRange(min, max)
            val frac = progress.coerceIn(0, SLIDER_MAX).toDouble() / SLIDER_MAX
            return lo * exp(frac * ln(hi / lo))
        }

        /** 在 [min, max] 内吸附到最近的标准档位（log-progress 空间，阈值 [SNAP_PROGRESS_DISTANCE]）。 */
        fun snap(ratio: Double, min: Double, max: Double): Double {
            val (lo, hi) = safeRange(min, max)
            val target = ratioToProgress(ratio, lo, hi)
            var closest = ratio
            var closestDistance = Int.MAX_VALUE
            for (preset in PRESET_RATIOS) {
                if (preset < lo || preset > hi) continue
                val distance = abs(ratioToProgress(preset, lo, hi) - target)
                if (distance < closestDistance) {
                    closestDistance = distance
                    closest = preset
                }
            }
            return if (closestDistance <= SNAP_PROGRESS_DISTANCE) closest else ratio
        }

        /** 把任意比例量化到滑条精度（对数映射往返），用于初始目标比例对齐。 */
        fun quantize(ratio: Double, min: Double, max: Double): Double {
            return ratioFromProgress(ratioToProgress(ratio, min, max), min, max)
        }
    }
}
