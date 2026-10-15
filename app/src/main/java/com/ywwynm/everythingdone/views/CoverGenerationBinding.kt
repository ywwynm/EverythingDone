package com.ywwynm.everythingdone.views

import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.graphics.Rect
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.helpers.CoverGenerationState
import java.io.Closeable

/** 复用数量行；不修改卡片高度、选择状态或列表动画。生命周期由目标 View 驱动。 */
internal class CoverGenerationBinding(
    private val card: InterceptTouchCardView,
    private val text: TextView,
    private val inlineIcon: ImageView?,
    private val count: CharSequence,
    private val observe: (Boolean, (CoverGenerationState) -> Unit, () -> Unit) -> Closeable,
    private val canRetry: () -> Boolean,
    private val onReady: () -> Unit
) : Closeable, View.OnAttachStateChangeListener {
    private var subscription: Closeable? = null
    private var disposed = false
    private var originalIcon = inlineIcon?.drawable
    private val originalPadding = text.compoundDrawablePadding
    private val glyphBounds = Rect()
    private val spinner = aligned(ProgressBar(text.context, null, android.R.attr.progressBarStyleSmall)
        .indeterminateDrawable.mutate())
    private val pie = CoverGenerationIndicator()
    private val pieIcon = aligned(pie)
    private var displayed: Drawable? = null
    internal var state: CoverGenerationState = CoverGenerationState.IDLE
        private set

    init {
        text.addOnAttachStateChangeListener(this)
        if (text.isAttachedToWindow) start(false)
    }

    private fun aligned(drawable: Drawable): Drawable {
        val size = text.textSize.toInt().coerceAtLeast(1)
        val width = inlineIcon?.let { it.layoutParams.width - it.paddingLeft - it.paddingRight } ?: size
        val height = inlineIcon?.let { it.layoutParams.height - it.paddingTop - it.paddingBottom } ?: size
        return CoverGenerationAlignedDrawable(drawable, width.coerceAtLeast(1), height.coerceAtLeast(1)) {
            val glyphCenter = text.baseline + (glyphBounds.top + glyphBounds.bottom) / 2f
            if (inlineIcon == null) {
                glyphCenter - (text.paddingTop + text.height - text.paddingBottom) / 2f
            } else {
                text.top + glyphCenter - inlineIcon.top -
                    (inlineIcon.paddingTop + inlineIcon.height - inlineIcon.paddingBottom) / 2f
            }
        }
    }

    private fun start(retry: Boolean) {
        subscription?.close()
        subscription = observe(retry, { value ->
            if (!disposed) showState(value)
        }, {
            if (!disposed && text.isAttachedToWindow) onReady()
        })
    }

    internal fun showState(value: CoverGenerationState) {
        val onlyProgressChanged = state.phase == value.phase &&
            value.phase == CoverGenerationState.Phase.GENERATING
        state = value
        if (onlyProgressChanged) pie.level = value.percent * 100
        else refreshAppearance()
    }

    fun refreshAppearance() {
        if (disposed) return
        // 外观局部绑定可能刚重设了普通媒体图标，保留它以供成功后恢复。
        inlineIcon?.drawable?.takeIf { it !== spinner && it !== pieIcon }?.let { originalIcon = it }
        val res = when (state.phase) {
            CoverGenerationState.Phase.WAITING -> R.string.cover_generation_waiting
            CoverGenerationState.Phase.PREPARING -> R.string.cover_generation_preparing
            CoverGenerationState.Phase.GENERATING -> R.string.cover_generation_running
            CoverGenerationState.Phase.FAILED -> R.string.cover_generation_failed
            else -> 0
        }
        val label = if (res == 0) count else text.context.getString(res)
        if (text.text != label) text.text = label
        val glyphText = label.toString()
        text.paint.getTextBounds(glyphText, 0, glyphText.length, glyphBounds)
        val icon = when (state.phase) {
            CoverGenerationState.Phase.WAITING, CoverGenerationState.Phase.PREPARING -> spinner
            CoverGenerationState.Phase.GENERATING, CoverGenerationState.Phase.FAILED -> pieIcon
            else -> null
        }
        pie.failed = state.phase == CoverGenerationState.Phase.FAILED
        icon?.setTint(text.currentTextColor)
        if (inlineIcon != null) {
            if (inlineIcon.drawable !== (icon ?: originalIcon)) inlineIcon.setImageDrawable(icon ?: originalIcon)
        } else if (displayed !== icon) {
            val size = text.textSize.toInt().coerceAtLeast(1)
            icon?.setBounds(0, 0, size, size)
            text.compoundDrawablePadding = if (icon == null) originalPadding else
                (4 * text.resources.displayMetrics.density).toInt()
            text.setCompoundDrawablesRelative(icon, null, null, null)
        }
        displayed = icon
        // TextView 安装 compound drawable 时可能重置 level，真实进度在安装后赋值。
        pie.level = state.percent * 100
        if (icon === spinner && text.isAttachedToWindow && text.isShown) {
            (spinner as? Animatable)?.start()
        } else (spinner as? Animatable)?.stop()
        val failed = state.phase == CoverGenerationState.Phase.FAILED
        val retryTarget = if (inlineIcon == null) text else text.parent as View
        card.setCoverGenerationRetryTarget(if (failed) retryTarget else null) {
            if (!disposed && state.phase == CoverGenerationState.Phase.FAILED && canRetry()) {
                start(true)
                true
            } else false
        }
        if (failed) {
            ViewCompat.replaceAccessibilityAction(text,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                text.context.getString(R.string.cover_generation_retry)) { _, _ ->
                if (canRetry()) { start(true); true } else false
            }
        } else ViewCompat.removeAccessibilityAction(text,
            AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK.id)
    }

    override fun onViewAttachedToWindow(v: View) { if (!disposed) start(false) }
    override fun onViewDetachedFromWindow(v: View) {
        subscription?.close()
        subscription = null
        (spinner as? Animatable)?.stop()
    }

    override fun close() {
        if (disposed) return
        disposed = true
        subscription?.close()
        text.removeOnAttachStateChangeListener(this)
        (spinner as? Animatable)?.stop()
        text.text = count
        if (inlineIcon != null) inlineIcon.setImageDrawable(originalIcon)
        else {
            text.setCompoundDrawablesRelative(null, null, null, null)
            text.compoundDrawablePadding = originalPadding
        }
        card.setCoverGenerationRetryTarget(null, null)
        ViewCompat.removeAccessibilityAction(text,
            AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK.id)
    }
}
