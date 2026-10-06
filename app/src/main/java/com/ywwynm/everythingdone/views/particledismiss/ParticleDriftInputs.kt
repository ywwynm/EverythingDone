package com.ywwynm.everythingdone.views.particledismiss

import android.content.Context
import android.util.Log
import com.ywwynm.everythingdone.BuildConfig

/**
 * 主线程上为一次飘散动画定输入：方向、参考样式或随机格点值、风、粒子数。
 *
 * - 方向：规格里「快照中心 → 虚拟触点」就是现有实现的消散方向（朝触点、左滑方向、菜单移除向左、返回键向右上；
 *   出现动画传的是倒放之前的正向方向，倒放后颗粒从来源方向汇入）。
 * - 参考样式：方向合适时按概率选用（[ParticleDriftStyles]）。
 * - 合成方式不在这里定，见 [ParticleDriftBackground]。
 * - 随机数按规格的 [ParticleDismissSpec.hashSeed]，同一次动画可复现。
 */
internal object ParticleDriftInputs {

    fun model(context: Context, spec: ParticleDismissSpec): ParticleDriftModel {
        val started = System.nanoTime()
        return build(context, spec).also {
            if (BuildConfig.DEBUG) Log.i(ParticleDriftRenderer.TAG,
                "飘散输入用时Ms=${"%.1f".format((System.nanoTime() - started) / 1e6)} 快照=${spec.snapshot.width}×${spec.snapshot.height}")
        }
    }

    private fun build(context: Context, spec: ParticleDismissSpec): ParticleDriftModel {
        val width = spec.snapshot.width
        val height = spec.snapshot.height
        val centerX = spec.originXPx + width * .5f
        val centerY = spec.originYPx + height * .5f
        val origin = spec.releaseOrigin
        return ParticleDriftModel.random(
            width = width,
            height = height,
            directionX = spec.virtualTouchXPx - centerX,
            directionY = spec.virtualTouchYPx - centerY,
            amount = ParticleStyle.driftAmount,
            size = ParticleStyle.driftSize,
            touchGap = spec.touchGap,
            touchStrength = spec.touchStrength,
            random = ParticleDriftModel.seededRandom(spec.hashSeed),
            styles = ParticleDriftStyles.all(context.assets),
            releaseX = origin?.let { it.x * width },
            releaseY = origin?.let { it.y * height })
    }
}
