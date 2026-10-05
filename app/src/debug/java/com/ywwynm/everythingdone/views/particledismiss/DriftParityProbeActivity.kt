package com.ywwynm.everythingdone.views.particledismiss

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.os.Bundle
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity

/**
 * 仅调试：旧钢铁侠的 Android 对照。把参考原片里的钢铁侠卡片放在原片的太空背景上，钉住参考样式、按参考自己的风向消散，
 * 录屏后与原片逐段并排（`docs/features/harmony-particle-android-port/device/ironman_parity.py`）。不读写记事、不改设置。
 *
 * 卡片按 350 dp 宽放（参考设备上那张卡片约 350 dp，粒子数按面积换算后同为 4 万颗），竖直位置按原片画面的比例。
 * extras：`style`（缺省 `ironman`）、`direction`（度，缺省 135）、`delay_ms`（显示多久之后开始，缺省 1500）。
 */
class DriftParityProbeActivity : AppCompatActivity() {

    private val animator by lazy { ParticlePanelAnimator(this) }
    private val previousStyle = ParticleStyle.current

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        val screenHeight = resources.displayMetrics.heightPixels
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val backdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(assets.open("drift-parity/ironman-background.png").use { BitmapFactory.decodeStream(it) })
        }
        root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        val foreground = assets.open("drift-parity/ironman-foreground.png").use { BitmapFactory.decodeStream(it) }
        val width = (CARD_DP * density).toInt()
        val height = width * foreground.height / foreground.width
        // 原片：720 × 1280 的画面里卡片是 (126, 261)–(594, 735)，圆角 29
        val radius = CARD_RADIUS * width / foreground.width
        val card = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setImageBitmap(foreground)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
            clipToOutline = true
        }
        val centerY = screenHeight * CARD_CENTER_FRACTION
        root.addView(card, FrameLayout.LayoutParams(width, height).apply {
            leftMargin = (resources.displayMetrics.widthPixels - width) / 2
            topMargin = (centerY - height / 2f).toInt()
        })
        setContentView(root)

        val style = intent.getStringExtra("style") ?: ParticleDriftStyles.FAVORITE
        val direction = intent.getFloatExtra("direction", 135f)
        val delay = intent.getIntExtra("delay_ms", 1500).toLong()
        card.postDelayed({
            if (isFinishing) return@postDelayed
            ParticleStyle.current = ParticleStyle.DRIFT
            ParticleDriftStyles.forcedForTests = style
            val started = animator.dismiss(card, direction, 0f, onSettled = {
                ParticleDriftStyles.forcedForTests = null
            }) { card.visibility = View.INVISIBLE }
            if (!started) ParticleDriftStyles.forcedForTests = null
        }, delay)
    }

    override fun onDestroy() {
        ParticleDriftStyles.forcedForTests = null
        ParticleStyle.current = previousStyle
        super.onDestroy()
    }

    private companion object {
        const val CARD_DP = 350f
        const val CARD_RADIUS = 29f
        /** 原片里卡片中心在画面高度的 (261 + 735) / 2 / 1280。 */
        const val CARD_CENTER_FRACTION = 498f / 1280f
    }
}
