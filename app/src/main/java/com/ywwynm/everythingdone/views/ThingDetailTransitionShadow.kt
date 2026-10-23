package com.ywwynm.everythingdone.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import com.ywwynm.everythingdone.views.particledismiss.ParticleCardClip
import kotlin.math.roundToInt

/** 只承载投影的原生节点；父层先绘制背景，再绘制本节点，最后绘制卡片内容。 */
internal class ThingDetailTransitionShadow(context: Context) : View(context) {
    internal data class Style(val elevation: Float, val alpha: Float, val ambient: Int, val spot: Int,
                              val clip: RectF) {
        companion object {
            fun capture(view: View, host: View): Style? {
                val outline = Outline().apply { alpha = 1f }
                view.outlineProvider?.getOutline(view, outline)
                if (view.z <= 0f || outline.isEmpty || outline.alpha <= 0f) return null
                // 允许阴影超出卡片，仍遵循父列表／文件夹缩略区域和首页顶栏的裁剪。
                val expanded = Rect(-host.width, -host.height, view.width + host.width, view.height + host.height)
                val visible = ParticleCardClip.visibleRect(view, expanded) ?: expanded
                val rect = ThingDetailTransition.bounds(view, host)
                val sx = rect.width() / view.width
                val sy = rect.height() / view.height
                val clip = RectF(rect.left + visible.left * sx, rect.top + visible.top * sy,
                    rect.left + visible.right * sx, rect.top + visible.bottom * sy)
                return Style(view.z, outline.alpha,
                    if (Build.VERSION.SDK_INT >= 28) view.outlineAmbientShadowColor else Color.BLACK,
                    if (Build.VERSION.SDK_INT >= 28) view.outlineSpotShadowColor else Color.BLACK, clip)
            }
        }
    }

    private val shape = Rect()
    private var radius = 0f
    private var outlineAlpha = 0f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        setWillNotDraw(false)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(shape, radius)
                outline.alpha = outlineAlpha
            }
        }
    }

    fun update(rect: RectF, radius: Float, style: Style?, depth: Float) {
        val left = rect.left.roundToInt()
        val top = rect.top.roundToInt()
        val right = rect.right.roundToInt()
        val bottom = rect.bottom.roundToInt()
        val alpha = style?.alpha ?: 0f
        // 使用与真实卡片相同的节点原点和尺寸，厂商 HWUI 的点光源投影也依赖节点坐标系。
        layout(left, top, right, bottom)
        if (shape.right != width || shape.bottom != height ||
            this.radius != radius || outlineAlpha != alpha) {
            shape.set(0, 0, width, height)
            this.radius = radius
            outlineAlpha = alpha
            invalidateOutline()
        }
        elevation = depth.coerceAtLeast(0f)
        if (Build.VERSION.SDK_INT >= 28 && style != null) {
            outlineAmbientShadowColor = style.ambient
            outlineSpotShadowColor = style.spot
        }
    }

    override fun onDraw(canvas: Canvas) {
        // 保留有效显示列表；HWUI 依据 outline 和 elevation 生成投影，调用方排除卡片内部。
        canvas.drawColor(Color.TRANSPARENT)
    }
}
