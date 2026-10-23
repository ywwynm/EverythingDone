package com.ywwynm.everythingdone.views.particledismiss

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RenderNode
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi

/**
 * 独立 Surface 只取生产者像素，不把它的真实 RenderNode 交给第二个 HardwareRenderer。
 * 仅拆开包含独立 Surface 的祖先；其余完整子树沿用系统硬件绘制，保留控件自身的绘制行为。
 */
@RequiresApi(29)
internal object ParticleViewCaptureTree {
    /** 持续背景保留普通子树的显示列表；动态子节点仍由原窗口更新它们自己的 RenderNode。 */
    class Cache {
        private class Entry {
            val node = RenderNode("particleBackdropContent").apply { setClipToBounds(false) }
            val own = RenderNode("particleBackdropOwn").apply { setClipToBounds(false) }
            val decorations = RenderNode("particleBackdropDecorations").apply { setClipToBounds(false) }
            val wrapper = RenderNode("particleBackdropPose")
            var width = -1
            var height = -1
            var structure: List<Any>? = null
        }
        private val entries = java.util.IdentityHashMap<View, Entry>()
        private fun record(node: RenderNode, view: View, block: (Canvas) -> Unit) {
            node.setPosition(0, 0, view.width, view.height)
            val canvas = node.beginRecording(view.width, view.height)
            try { block(canvas) } finally { node.endRecording() }
        }
        fun update(view: View, frames: Map<View, Bitmap>, excluded: Set<View>, affected: Set<View>,
                   forceRecording: Boolean = false): RenderNode {
            val entry = entries.getOrPut(view) { Entry() }
            val resized = entry.width != view.width || entry.height != view.height
            entry.width = view.width; entry.height = view.height
            if (view is ViewGroup && view in affected) {
                val children = (0 until view.childCount).map { view.getChildAt(view.getChildDrawingOrder(it)) }
                record(entry.own, view) { drawOwn(view, children, it) }
                record(entry.decorations, view) { view.onDrawForeground(it) }
                val visible = children.filter { it.visibility == View.VISIBLE && it !in excluded &&
                    it !is ParticleOverlaySurfaceView && it !is ParticleTransparentSurfaceContainer &&
                    it.width > 0 && it.height > 0 }
                val nodes = visible.map { child ->
                    val content = update(child, frames, excluded, affected, forceRecording)
                    val wrapped = checkNotNull(entries[child]).wrapper
                    val resizedChild = wrapped.width != child.width || wrapped.height != child.height
                    pose(wrapped, child, view)
                    if (resizedChild || !wrapped.hasDisplayList()) {
                        val target = wrapped.beginRecording(child.width, child.height)
                        try { target.drawRenderNode(content) } finally { wrapped.endRecording() }
                    }
                    wrapped
                }
                val structure: List<Any> = visible + listOf(view.width, view.height, view.clipToPadding,
                    view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
                if (entry.structure != structure || !entry.node.hasDisplayList()) {
                    entry.structure = structure
                    record(entry.node, view) { target ->
                        target.drawRenderNode(entry.own)
                        val save = target.save()
                        if (view.clipToPadding && (view.paddingLeft != 0 || view.paddingTop != 0 ||
                                    view.paddingRight != 0 || view.paddingBottom != 0))
                            target.clipRect(view.paddingLeft, view.paddingTop, view.width - view.paddingRight,
                                view.height - view.paddingBottom)
                        target.enableZ(); nodes.forEach(target::drawRenderNode); target.disableZ()
                        target.restoreToCount(save); target.drawRenderNode(entry.decorations)
                    }
                }
            } else if (forceRecording || resized || view.isDirty || view.isLayoutRequested || !entry.node.hasDisplayList() ||
                ParticleSurfaceCopy.isSource(view) || view is TextureView) {
                record(entry.node, view) { target ->
                    when (view) {
                        is SurfaceView, is ParticleDirectSurfaceSource -> frames[view]?.let { target.drawBitmap(it, null,
                            Rect(0, 0, view.width, view.height), Paint(Paint.FILTER_BITMAP_FLAG)) }
                        else -> view.draw(target)
                    }
                }
            }
            return entry.node
        }
        fun close() {
            check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            entries.values.forEach { listOf(it.node, it.own, it.decorations, it.wrapper).forEach(RenderNode::discardDisplayList) }
            // view.draw 借用了真实子节点，却没有成为原 View 的显示列表。
            // 离屏树释放后，子节点可能被 HWUI 清除，而原 View 仍缓存着“有效”的父列表。
            // 只在释放时通知记录过的 View 重建；不能每帧 invalidate 整个列表，也不能只
            // invalidate DecorView（未失效的中间父列表会跳过已经丢失的后代）。
            entries.keys.forEach(View::invalidate)
            entries.clear()
        }
    }

    fun record(canvas: Canvas, root: View, frames: Map<View, Bitmap>,
               excluded: Set<View> = emptySet(), cache: Cache? = null,
               forceRootRecording: Boolean = false,
               borrowedViews: MutableSet<View>? = null,
               shadowlessView: View? = null): List<RenderNode> {
        val nodes = mutableListOf<RenderNode>()
        val affected = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<View, Boolean>())
        fun includeAncestors(source: View) {
            var view: View? = source
            while (view != null) {
                affected += view
                if (view === root) break
                view = view.parent as? View
            }
        }
        frames.keys.forEach(::includeAncestors)
        excluded.forEach(::includeAncestors)
        // 只拆父链：卡片副本的高度已由父节点去除，卡片自身应完整绘制一次。
        // 再拆卡片会临时改写 foreground.alpha，原生 ripple 的透明内容层会使遮罩被错误恢复为 0。
        (shadowlessView?.parent as? View)?.let(::includeAncestors)
        if (cache != null && shadowlessView == null) {
            canvas.drawRenderNode(cache.update(root, frames, excluded, affected, forceRootRecording))
            return emptyList()
        }
        fun draw(view: View, target: Canvas) {
            if (view in excluded || view is ParticleOverlaySurfaceView || view is ParticleTransparentSurfaceContainer) return
            if (ParticleSurfaceCopy.isSource(view)) {
                frames[view]?.let { target.drawBitmap(it, null, Rect(0, 0, view.width, view.height),
                    Paint(Paint.FILTER_BITMAP_FLAG)) }
                return
            }
            // TextureView 仍走原生硬件绘制，保留内容矩阵和解码缓冲裁剪。
            // getBitmap 再绘制会丢失这些信息；它也不是需要隔离 RenderNode 的独立 Surface。
            if (view !is ViewGroup || view !in affected) {
                borrowedViews?.add(view)
                view.draw(target)
                return
            }
            val children = (0 until view.childCount).map { view.getChildAt(view.getChildDrawingOrder(it)) }
            val visibility = children.map { it.visibility }
            borrowedViews?.add(view)
            drawOwn(view, children, target)
            val save = target.save()
            if (view.clipToPadding && (view.paddingLeft != 0 || view.paddingTop != 0 ||
                        view.paddingRight != 0 || view.paddingBottom != 0))
                target.clipRect(view.paddingLeft, view.paddingTop, view.width - view.paddingRight,
                    view.height - view.paddingBottom)
            target.enableZ()
            children.forEachIndexed { index, child ->
                if (visibility[index] != View.VISIBLE || child in excluded || child is ParticleOverlaySurfaceView ||
                    child is ParticleTransparentSurfaceContainer ||
                    child.width <= 0 || child.height <= 0) return@forEachIndexed
                val node = RenderNode("particleCaptured-${child.javaClass.simpleName}").also { nodes += it }
                pose(node, child, view)
                if (child === shadowlessView) {
                    // 只修改副本，避免真实节点的按压动画或下一帧同步把原投影带回捕获画面。
                    node.elevation = 0f
                    node.translationZ = 0f
                }
                val recording = node.beginRecording(child.width, child.height)
                try { draw(child, recording) } finally { node.endRecording() }
                target.drawRenderNode(node)
            }
            target.disableZ(); target.restoreToCount(save)
            view.onDrawForeground(target)
        }
        // 排除项的祖先同样拆开记录。不能每帧 invalidate RecyclerView：那会让复杂卡片
        // 的原生显示列表反复失效，魅族实测仅记录背景就需要约 18ms。
        try { draw(root, canvas) }
        catch (failure: Exception) { nodes.forEach { it.discardDisplayList() }; throw failure }
        return nodes
    }

    private fun drawOwn(view: ViewGroup, children: List<View>, target: Canvas) {
        val visibility = children.map { it.visibility }
        val foreground = view.foreground
        val foregroundAlpha = foreground?.alpha
        val horizontal = view.isHorizontalScrollBarEnabled
        val vertical = view.isVerticalScrollBarEnabled
        try {
            children.forEach { it.setTransitionVisibility(View.INVISIBLE) }
            foreground?.alpha = 0
            view.isHorizontalScrollBarEnabled = false; view.isVerticalScrollBarEnabled = false
            view.draw(target)
        } finally {
            children.forEachIndexed { i, child -> child.setTransitionVisibility(visibility[i]) }
            if (foregroundAlpha != null) foreground.alpha = foregroundAlpha
            view.isHorizontalScrollBarEnabled = horizontal; view.isVerticalScrollBarEnabled = vertical
        }
    }

    private fun pose(node: RenderNode, child: View, parent: ViewGroup) {
        node.setPosition(child.left - parent.scrollX, child.top - parent.scrollY,
            child.right - parent.scrollX, child.bottom - parent.scrollY)
        node.translationX = child.translationX; node.translationY = child.translationY
        node.scaleX = child.scaleX; node.scaleY = child.scaleY
        node.rotationZ = child.rotation; node.rotationX = child.rotationX; node.rotationY = child.rotationY
        node.pivotX = child.pivotX; node.pivotY = child.pivotY
        node.cameraDistance = child.cameraDistance / child.resources.displayMetrics.densityDpi
        node.alpha = child.alpha * child.transitionAlpha
        // 与真实 View 一样，重叠内容先合成再应用整体透明度。
        // RenderNode 默认不重叠；直接沿用会把 alpha 传进数字的 DST_IN 蒙版，导致二次衰减。
        node.setHasOverlappingRendering(child.getHasOverlappingRendering())
        node.elevation = child.elevation; node.translationZ = child.translationZ
        node.setClipToBounds(parent.clipChildren); node.setClipRect(child.clipBounds)
        val outline = Outline().apply { alpha = 1f }
        child.outlineProvider?.getOutline(child, outline)
        node.setOutline(outline); node.setClipToOutline(child.clipToOutline)
        node.setAmbientShadowColor(child.outlineAmbientShadowColor)
        node.setSpotShadowColor(child.outlineSpotShadowColor)
    }
}
