package com.ywwynm.everythingdone.views.particledismiss

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi

/** 独立 Surface 仍由软件路径挖洞；普通叶控件用软件内容和独立 RenderNode 重建系统投影。 */
@RequiresApi(29)
internal object ParticleSoftwareShadowSnapshot {
    fun capture(root: View, done: (Bitmap?) -> Unit) {
        val run = ParticlePresentationDiagnostics.of(root)
        val casters = mutableListOf<View>()
        fun visit(view: View) {
            if (view.visibility != View.VISIBLE || ParticleSurfaceCopy.isSource(view)) return
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) visit(view.getChildAt(i))
            } else if (view.width > 0 && view.height > 0 && view.matrix.isIdentity) {
                // 出现捕获时普通按钮可能尚在初始状态动画中；直接取当前状态的最终 elevation。
                if (!view.isPressed) view.stateListAnimator?.jumpToCurrentState()
                if (view.z > 0f) casters += view
            }
        }
        visit(root)
        val positions = casters.map { view ->
            val at = IntArray(2); view.getLocationOnScreen(at)
            Triple(view, at, ParticleCardSnapshot.of(view, view.z))
        }.filter { it.third != null }.sortedBy { it.first.z }
        val base = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val rootAt = IntArray(2); root.getLocationOnScreen(rootAt)
        val restoreFeedback = ParticleTouchFeedback.omitFromSnapshot(root)
        val drawn = try {
            // 普通控件在同一调用栈内排除／恢复。SurfaceView 的可见性和真实节点始终不变。
            positions.forEach { it.first.visibility = View.INVISIBLE }
            root.draw(Canvas(base))
            true
        } catch (_: Exception) {
            false
        } finally {
            positions.forEach { it.first.visibility = View.VISIBLE }
            restoreFeedback()
        }
        if (!drawn) { base.recycle(); done(null); return }
        run?.event("surface_ordinary_shadows", mapOf("casters" to positions.map {
            mapOf("class" to it.first.javaClass.simpleName, "z" to it.first.z)
        }, "liveSurfaceRendered" to false))
        fun next(index: Int) {
            if (index == positions.size) {
                ParticleCaptureDiagnostics.begin("软件挖洞+普通控件硬件阴影")
                done(base); return
            }
            val (view, at, optionalShadow) = positions[index]
            val shadow = checkNotNull(optionalShadow)
            ParticleHardwareSnapshot.captureSoftwareShadow(view, shadow) { bitmap ->
                if (bitmap == null) { base.recycle(); done(null); return@captureSoftwareShadow }
                Canvas(base).drawBitmap(bitmap, (at[0] - rootAt[0] + shadow.bounds.left).toFloat(),
                    (at[1] - rootAt[1] + shadow.bounds.top).toFloat(), null)
                bitmap.recycle(); next(index + 1)
            }
        }
        next(0)
    }
}
