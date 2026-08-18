package com.ywwynm.everythingdone.permission

import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Shader
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.TextView

import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil

/**
 * 「画面暂时无法响应设备方向」提示文案的装配。
 *
 * 文案里的「系统设置」是一个**可点片段**：点它直接跳本应用的系统权限页面，因此那几个字要按
 * 当前强调色着色，渐变强调色用 shader（海浪动画设置的强调色会随记事颜色变，空间照片设置那边
 * 固定是 accent + accent2 的渐变）。
 *
 * 片段位置来自资源模板里的 `%1$s`，不靠在译文里搜关键词——13 个语言地区的词序各不相同，搜
 * 关键词迟早会在某个语言上失配。
 */
object DirectionSensorHint {

    private const val PLACEHOLDER = "%1\$s"

    /** 不可点场合（录音/播放对话框的界内提示、空间照片的 Toast）用的纯文本。 */
    fun plain(textView: TextView): String = plain(textView.resources)

    fun plain(resources: android.content.res.Resources): String {
        val template = resources.getString(R.string.direction_sensor_restricted)
        val anchor = resources.getString(R.string.direction_sensor_restricted_settings)
        val at = template.indexOf(PLACEHOLDER)
        if (at < 0) return template
        return template.substring(0, at) + anchor + template.substring(at + PLACEHOLDER.length)
    }

    /**
     * 把带可点「系统设置」片段的文案装到 [textView] 上，返回一个**重新着色**的回调：
     * 强调色变化时调用它即可（渐变要按实测排版位置重新生成 shader）。
     *
     * 渐变**精确铺在那几个字自身的范围内**：span 的 shader 在**画布坐标**里取值，原点落在
     * 文本绘制原点而不是片段左缘，所以必须用 [android.text.Layout] 查出片段的 x 与所在行的
     * 上下缘，再用局部矩阵把渐变平移过去。
     *
     * 不这么做的两种偷懒写法都不行：只按片段宽度生成而不平移，句中片段会落到 CLAMP 区外，
     * 变成一块死板的端点色；按整块文本宽度生成，片段则只取到渐变中很窄的一段——落在浅色端
     * 时在白底上几乎看不清（2026-08-18 实机确认）。
     */
    fun bind(
        textView: TextView,
        accent: () -> ThingBackground?,
        onSettingsClick: () -> Unit
    ): () -> Unit {
        val resources = textView.resources
        val template = resources.getString(R.string.direction_sensor_restricted)
        val anchor = resources.getString(R.string.direction_sensor_restricted_settings)
        val at = template.indexOf(PLACEHOLDER)
        val full = plain(resources)

        // 内容先立住：宽度在测量完成前是 0，此时还生成不了 shader。
        textView.text = full
        if (at < 0) return {}   // 译文缺占位符时退化为纯文本，不影响信息完整性

        textView.movementMethod = LinkMovementMethod.getInstance()
        textView.highlightColor = Color.TRANSPARENT

        var appliedWidth = -1
        var appliedAccent: Int? = null

        fun refresh() {
            val width = textView.width - textView.compoundPaddingLeft -
                textView.compoundPaddingRight
            if (width <= 0) return
            val background = accent()
            val accentKey = background?.let {
                it.mode.ordinal * 31 + it.color * 7 + it.endColor
            }
            // setText 会再触发一次布局，靠这两个已应用值收敛，不会来回抖。
            if (width == appliedWidth && accentKey == appliedAccent) return

            val shader = if (background != null &&
                background.mode == ThingBackground.Mode.GRADIENT
            ) {
                // 纯文本与带 span 的文本字符完全相同，排版几何一致，因此可以直接用当前
                // Layout 量出片段的位置，再把渐变平移到那里。
                val layout = textView.layout ?: return
                val line = layout.getLineForOffset(at)
                val left = layout.getPrimaryHorizontal(at)
                val right = layout.getPrimaryHorizontal(at + anchor.length)
                val top = layout.getLineTop(line).toFloat()
                val bottom = layout.getLineBottom(line).toFloat()
                BackgroundUtil.createLinearGradient(
                    background,
                    (right - left).coerceAtLeast(1f),
                    (bottom - top).coerceAtLeast(1f)
                ).also { it.setLocalMatrix(Matrix().apply { setTranslate(left, top) }) }
            } else {
                null
            }
            appliedWidth = width
            appliedAccent = accentKey

            val span = AccentLinkSpan(
                shader = shader,
                color = background?.color ?: textView.currentTextColor,
                onClick = onSettingsClick
            )
            textView.text = SpannableStringBuilder(full).apply {
                setSpan(span, at, at + anchor.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        textView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refresh() }
        refresh()
        return { appliedWidth = -1; appliedAccent = null; refresh() }
    }

    /**
     * 可点且按强调色着色的片段。**不调 `super.updateDrawState`**：ClickableSpan 默认会套上
     * 系统链接色与下划线，会把强调色整个盖掉。
     */
    private class AccentLinkSpan(
        private val shader: Shader?,
        private val color: Int,
        private val onClick: () -> Unit
    ) : ClickableSpan() {

        override fun onClick(widget: View) = onClick()

        override fun updateDrawState(ds: TextPaint) {
            if (shader != null) {
                ds.shader = shader
                // 必须把 alpha 拉回不透明：shader 的颜色仍会乘上 paint 的 alpha，而提示行
                // 本体用的是带低 alpha 的 hint 色，不这么做强调色会被冲成一片淡影
                // （2026-08-18 实机放大确认）。
                ds.alpha = 255
            } else {
                ds.color = color
            }
            ds.isUnderlineText = false
        }
    }
}
