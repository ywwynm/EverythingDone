package com.ywwynm.everythingdone.views.particledismiss

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.View
import com.ywwynm.everythingdone.BuildConfig
import kotlin.math.cbrt
import kotlin.math.pow

/**
 * 飘散颗粒的合成方式按背景亮度选（用户 2026-10-05 裁定）：暗背景加光、亮背景标准 src-over。
 *
 * 取样：把 Activity 窗口上快照四周（快照尺寸各向外扩一半，截在窗口内）缩成 [SAMPLE] × [SAMPLE] 读回来，
 * 去掉快照本身那一块——卡片就在这个窗口里，读到的是卡片自己；弹窗在另一个窗口，这一块是弹窗下面的页面，一样去掉，
 * 颗粒飞过的是四周。剩下的像素求平均相对亮度，换算成 CIE L*，低于 [DARK_LIGHTNESS] 记为暗。
 * 弹窗的暗层（[DialogDimLayer]，黑色、挂在 Activity 窗口里）PixelCopy 读得到，但它在消散的前半段就淡掉了、出现时才淡入，
 * 不计入：界面按 sRGB 编码值混合，按取样那一刻的遮盖度 a 把每个通道的编码值除以 (1 − a) 还原，再求亮度。
 *
 * PixelCopy 是异步的：会话在开画之前轮询 [poll]，超过 [WAIT_MS] 还没回来就按深浅色主题定。
 */
internal class ParticleDriftBackground private constructor(private val fallback: Boolean) {

    @Volatile private var decided: Boolean? = null

    /** 取样得到的 L*；没取到时为 NaN。只用于日志与验收。 */
    @Volatile var lightness = Float.NaN
        private set

    private val deadline = System.nanoTime() + WAIT_MS * 1_000_000L

    /** GL 线程轮询：取到了或过了时限给出 true（加光）/ false（标准 src-over），否则 null。 */
    fun poll(): Boolean? = decided ?: if (System.nanoTime() >= deadline) fallback else null

    /** 取样那一刻暗层的遮盖度；只用于日志。 */
    @Volatile private var dimCoverage = 0f

    /** 日志用：结果来自取样还是主题。 */
    val source: String get() = if (decided != null)
        "取样 L*=%.0f".format(lightness) + (if (dimCoverage > 0f) " 暗层=%.2f".format(dimCoverage) else "") else "主题"

    private fun copy(activity: Activity, anchor: View, spec: ParticleDismissSpec) {
        val window = activity.window ?: return
        val decor = window.decorView
        if (!decor.isAttachedToWindow || decor.width <= 0 || decor.height <= 0) return
        val anchorOnScreen = IntArray(2).also { anchor.getLocationOnScreen(it) }
        val decorOnScreen = IntArray(2).also { decor.getLocationOnScreen(it) }
        val left = anchorOnScreen[0] - decorOnScreen[0] + spec.originXPx
        val top = anchorOnScreen[1] - decorOnScreen[1] + spec.originYPx
        val width = spec.snapshot.width.toFloat()
        val height = spec.snapshot.height.toFloat()
        val outer = Rect(
            (left - width * .5f).toInt().coerceAtLeast(0), (top - height * .5f).toInt().coerceAtLeast(0),
            (left + width * 1.5f).toInt().coerceAtMost(decor.width),
            (top + height * 1.5f).toInt().coerceAtMost(decor.height))
        if (outer.width() < SAMPLE || outer.height() < SAMPLE) return
        // 快照那一块在取样图上的位置
        val sx = SAMPLE.toFloat() / outer.width()
        val sy = SAMPLE.toFloat() / outer.height()
        val inner = Rect(((left - outer.left) * sx).toInt(), ((top - outer.top) * sy).toInt(),
            ((left + width - outer.left) * sx).toInt() + 1, ((top + height - outer.top) * sy).toInt() + 1)
        val dim = DialogDimLayer.coverage(decor)
        val bitmap = Bitmap.createBitmap(SAMPLE, SAMPLE, Bitmap.Config.ARGB_8888)
        val copied: (Int) -> Unit = { result ->
            if (result == PixelCopy.SUCCESS) decide(bitmap, inner, dim)
            else if (BuildConfig.DEBUG) Log.i(ParticleDriftRenderer.TAG, "飘散背景取样失败 result=$result，按主题")
            bitmap.recycle()
        }
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            val content = ParticleDoingPage.contentOf(activity)
            if (content != null && ParticleDoingPage.of(content)?.copyFrame(outer, bitmap, copied) == true) return
        }
        PixelCopy.request(window, outer, bitmap, copied, Handler(Looper.getMainLooper()))
    }

    private fun decide(bitmap: Bitmap, inner: Rect, dim: Float) {
        val pixels = IntArray(SAMPLE * SAMPLE)
        bitmap.getPixels(pixels, 0, SAMPLE, 0, 0, SAMPLE, SAMPLE)
        // 四周留下的像素太少（快照几乎占满了窗口）时整块都算。
        val ring = SAMPLE * SAMPLE - inner.width().coerceAtLeast(0) * inner.height().coerceAtLeast(0)
        val skipInner = ring >= SAMPLE * SAMPLE / 8
        var sum = 0.0
        var count = 0
        val gain = undimGain(dim)
        for (y in 0 until SAMPLE) for (x in 0 until SAMPLE) {
            if (skipInner && inner.contains(x, y)) continue
            sum += relativeLuminance(pixels[y * SAMPLE + x], gain)
            count++
        }
        if (count == 0) return
        val l = lightness(sum / count)
        lightness = l
        dimCoverage = dim
        decided = l < DARK_LIGHTNESS
        ParticleGl.wake()
    }

    companion object {
        /** 等取样结果的上限：会话准备快照本来就要几十毫秒，绝大多数时候不会多等。 */
        const val WAIT_MS = 48L

        /** L* 低于它算暗背景（加光）。L* 50 约为 sRGB 中灰 119。 */
        const val DARK_LIGHTNESS = 50f

        private const val SAMPLE = 32

        /** 主线程：发起取样。取样发不出去时直接按主题定。 */
        fun request(activity: Activity, anchor: View, spec: ParticleDismissSpec): ParticleDriftBackground {
            val night = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
            val probe = ParticleDriftBackground(night)
            runCatching { probe.copy(activity, anchor, spec) }.onFailure {
                if (BuildConfig.DEBUG) Log.i(ParticleDriftRenderer.TAG, "飘散背景取样没发出去，按主题", it)
            }
            return probe
        }

        /** sRGB 像素（各通道编码值先乘 [gain]，截到 1）→ 相对亮度 Y（线性光，Rec. 709 系数）。 */
        fun relativeLuminance(argb: Int, gain: Double = 1.0): Double {
            fun linear(channel: Int): Double {
                val c = (channel / 255.0 * gain).coerceAtMost(1.0)
                return if (c <= .04045) c / 12.92 else ((c + .055) / 1.055).pow(2.4)
            }
            return .2126 * linear(argb shr 16 and 0xFF) + .7152 * linear(argb shr 8 and 0xFF) +
                .0722 * linear(argb and 0xFF)
        }

        /** 去掉黑色暗层（遮盖度 [dim]）要乘在编码值上的倍数；暗层几乎全黑时不再放大。 */
        fun undimGain(dim: Float): Double = 1.0 / (1.0 - dim.coerceIn(0f, .95f))

        /** 相对亮度 Y → CIE L*。 */
        fun lightness(y: Double): Float =
            (if (y > 216.0 / 24389.0) 116.0 * cbrt(y) - 16.0 else y * 24389.0 / 27.0).toFloat()
    }
}
