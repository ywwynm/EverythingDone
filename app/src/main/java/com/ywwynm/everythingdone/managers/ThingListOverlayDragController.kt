package com.ywwynm.everythingdone.managers

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.adapters.BaseThingsAdapter
import com.ywwynm.everythingdone.helpers.DebugFileLogger
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.model.ThingListEntry
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.views.ThingsStaggeredLayoutManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class ThingListOverlayDragController(
    private val host: Host
) {

    interface Host {
        val recyclerView: RecyclerView
        val overlayParent: ViewGroup

        fun createOverlayDragSource(listPosition: Int): DragSource?
        fun getEntry(listPosition: Int): ThingListEntry?
        fun getListPositionForStableId(stableId: Long): Int
        fun buildFolderDropCandidate(
            source: DragSource,
            targetListPosition: Int
        ): FolderDropCandidate?

        fun showFolderDropHover(candidate: FolderDropCandidate)
        fun clearFolderDropHover(animateRestore: Boolean)
        fun commitFolderDrop(candidate: FolderDropCandidate): FolderDropCommitResult
        fun commitReorder(
            source: DragSource,
            targetStableId: Long,
            insertAfter: Boolean
        ): Boolean
        fun enterSelectionMode(source: DragSource): Boolean
        fun cancelOverlayDrag(source: DragSource)
        fun notifySourcePlaceholderChanged(source: DragSource)
        fun onOverlayDragActiveChanged(active: Boolean)
        fun isOverlayDragSourceStillVisible(source: DragSource): Boolean
    }

    data class DragSource(
        val kind: Kind,
        val stableId: Long,
        val thingId: Long?,
        val folderId: Long?,
        val originalListPosition: Int,
        val background: ThingBackground
    ) {
        enum class Kind {
            THING,
            FOLDER
        }
    }

    data class FolderDropCandidate(
        val action: FolderDropAction,
        val targetListPosition: Int,
        val sourceThingId: Long?,
        val sourceFolderId: Long?,
        val targetThingId: Long?,
        val targetFolderId: Long?,
        val background: ThingBackground?
    )

    enum class FolderDropAction {
        CREATE,
        MOVE_TO_FOLDER
    }

    data class FolderDropCommitResult(
        val committed: Boolean,
        val onVisualFinished: (() -> Unit)? = null
    )

    private data class ReorderCandidate(
        val targetStableId: Long,
        val targetListPosition: Int,
        val insertAfter: Boolean
    )

    private data class OverlayTarget(
        val x: Float,
        val y: Float,
        val scaleX: Float,
        val scaleY: Float
    )

    private data class Session(
        val source: DragSource,
        val sourceView: View,
        val overlay: DragOverlayImageView,
        val bitmap: Bitmap,
        val pointerId: Int,
        val fingerOffsetX: Float,
        val fingerOffsetY: Float,
        val attachListener: RecyclerView.OnChildAttachStateChangeListener,
        val placeholderPreDrawListener: ViewTreeObserver.OnPreDrawListener
    )

    val isActive: Boolean
        get() = session != null

    private var session: Session? = null
    private var lastRawX: Float = 0f
    private var lastRawY: Float = 0f
    private var lastPointerInRecyclerX: Float = 0f
    private var lastPointerInRecyclerY: Float = 0f
    private var hoverCandidate: FolderDropCandidate? = null
    private var hoverStartedAt: Long = 0L
    private var hoverFrameCount: Int = 0
    private var armedFolderDrop: FolderDropCandidate? = null
    private var reorderCandidate: ReorderCandidate? = null
    private var insertionDecoration: InsertionLineDecoration? = null
    private var autoScrollRunnable: Runnable? = null
    private var autoScrollVelocity: Int = 0
    private var finishing: Boolean = false
    private var sourcePlaceholderLocked: Boolean = false

    fun start(
        listPosition: Int,
        sourceHolder: RecyclerView.ViewHolder,
        rawX: Float,
        rawY: Float
    ): Boolean {
        if (session != null) {
            cancel("restart")
        }
        val source = host.createOverlayDragSource(listPosition) ?: return false
        val sourceView = sourceHolder.itemView
        if (sourceView.width <= 0 || sourceView.height <= 0) return false

        val bitmap = try {
            Bitmap.createBitmap(
                sourceView.width,
                sourceView.height,
                Bitmap.Config.ARGB_8888
            ).also { sourceView.draw(Canvas(it)) }
        } catch (_: RuntimeException) {
            return false
        }

        val parent = host.overlayParent
        val rootLocation = IntArray(2)
        parent.getLocationOnScreen(rootLocation)
        val sourceRect = getViewRectInRoot(sourceView, includeTransientTranslations = true)
            ?: return false
        val overlayScaleX = max(DRAG_OVERLAY_SCALE, sourceView.scaleX)
        val overlayScaleY = max(DRAG_OVERLAY_SCALE, sourceView.scaleY)
        val overlayElevation = parent.resources.getDimension(R.dimen.thing_card_dragging_elevation)
        val coverTransparentInteriorShadow = shouldCoverTransparentInteriorShadow(sourceHolder)
        val overlayContentInset = if (coverTransparentInteriorShadow) {
            (overlayElevation * OVERLAY_SYSTEM_SHADOW_INSET_RATIO).roundToInt()
        } else {
            0
        }
        val overlayContentBackground = if (coverTransparentInteriorShadow) {
            BackgroundUtil.mutedSurfaceBackground(
                source.background,
                parent.context.getColor(R.color.bg_activity_things)
            )
        } else {
            null
        }

        val sourceLeftInRoot = sourceRect.left - getScaleOutset(sourceView.width, overlayScaleX)
        val sourceTopInRoot = sourceRect.top - getScaleOutset(sourceView.height, overlayScaleY)
        val overlayLeftInRoot = getOverlayLayoutXForContentLeft(
            overlayContentInset,
            0f,
            overlayScaleX,
            sourceLeftInRoot
        )
        val overlayTopInRoot = getOverlayLayoutYForContentTop(
            overlayContentInset,
            0f,
            overlayScaleY,
            sourceTopInRoot
        )
        val offsetX = rawX - (rootLocation[0] + overlayLeftInRoot)
        val offsetY = rawY - (rootLocation[1] + overlayTopInRoot)

        val overlay = DragOverlayImageView(parent.context).apply {
            configure(
                contentInsetPx = overlayContentInset,
                cornerRadiusPx = parent.resources.getDimension(R.dimen.thing_card_corner_radius),
                coverTransparentInteriorShadow = coverTransparentInteriorShadow,
                contentBackground = overlayContentBackground
            )
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_XY
            pivotX = 0f
            pivotY = 0f
            elevation = overlayElevation
            x = overlayLeftInRoot
            y = overlayTopInRoot
            scaleX = overlayScaleX
            scaleY = overlayScaleY
            outlineProvider = createCardOutlineProvider(parent, overlayContentInset)
            clipToOutline = true
        }
        parent.addView(
            overlay,
            FrameLayout.LayoutParams(
                sourceView.width + overlayContentInset * 2,
                sourceView.height + overlayContentInset * 2
            )
        )

        applySourcePlaceholder(sourceView)
        host.recyclerView.stopScroll()
        host.onOverlayDragActiveChanged(true)
        sourcePlaceholderLocked = true

        val attachListener = object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                if (sourcePlaceholderLocked && isSourcePlaceholderView(view, source)) {
                    applySourcePlaceholder(view)
                    return
                }
                val holder = host.recyclerView.getChildViewHolder(view)
                val position = holder.adapterPosition
                val entry = host.getEntry(position) ?: return
                if (sourcePlaceholderLocked && entry.stableId == source.stableId) {
                    applySourcePlaceholder(view)
                }
            }

            override fun onChildViewDetachedFromWindow(view: View) = Unit
        }
        host.recyclerView.addOnChildAttachStateChangeListener(attachListener)

        val placeholderPreDrawListener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val current = session
                if (current != null &&
                    sourcePlaceholderLocked &&
                    current.source.stableId == source.stableId
                ) {
                    ensureVisibleSourcePlaceholder(current)
                }
                return true
            }
        }
        host.recyclerView.viewTreeObserver.addOnPreDrawListener(placeholderPreDrawListener)

        session = Session(
            source,
            sourceView,
            overlay,
            bitmap,
            0,
            offsetX,
            offsetY,
            attachListener,
            placeholderPreDrawListener
        )
        lastRawX = rawX
        lastRawY = rawY
        updatePointerInRecycler(rawX, rawY)
        updateFrame()
        updateAutoScroll()
        log(
            "start kind=${source.kind} stable=${source.stableId} " +
                "position=$listPosition size=${sourceView.width}x${sourceView.height} " +
                "scale=$overlayScaleX/$overlayScaleY " +
                "inset=$overlayContentInset coverInterior=$coverTransparentInteriorShadow " +
                "offset=${offsetX.roundToInt()},${offsetY.roundToInt()}",
            startSession = true
        )
        return true
    }

    fun handleTouchEvent(event: MotionEvent): Boolean {
        val current = session ?: return false
        if (finishing) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = event.findPointerIndex(current.pointerId)
                if (pointerIndex < 0) {
                    cancel("missing-pointer")
                    return true
                }
                val rawX = event.getRawXCompat(pointerIndex)
                val rawY = event.getRawYCompat(pointerIndex)
                lastRawX = rawX
                lastRawY = rawY
                updatePointerInRecycler(rawX, rawY)
                moveOverlayToPointer(rawX, rawY)
                updateFrame()
                updateAutoScroll()
                return true
            }
            MotionEvent.ACTION_UP -> {
                release()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancel("touch-cancel")
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == current.pointerId) {
                    release()
                    return true
                }
            }
        }
        return true
    }

    fun cancel(reason: String) {
        val current = session ?: return
        log("cancel reason=$reason stable=${current.source.stableId}")
        stopAutoScroll()
        clearFolderHover(true)
        clearInsertionLine()
        animateOverlayBackOrFade(current) {
            host.cancelOverlayDrag(current.source)
            endSession(current, restoreSource = true, notifySource = true)
        }
    }

    private fun release() {
        val current = session ?: return
        stopAutoScroll()
        updateFrame()
        val drop = armedFolderDrop
        if (drop != null && isFolderDropCandidateStillVisible(current, drop)) {
            finishFolderDrop(current, drop)
            return
        }
        clearFolderHover(true)
        val reorder = reorderCandidate
        if (reorder != null) {
            finishReorder(current, reorder)
        } else {
            finishSelection(current)
        }
    }

    private fun updateFrame() {
        val current = session ?: return
        ensureVisibleSourcePlaceholder(current)
        updateFolderDrop(current)
        if (armedFolderDrop == null) {
            updateReorderCandidate(current)
        } else {
            clearInsertionLine()
        }
    }

    private fun moveOverlayToPointer(rawX: Float, rawY: Float) {
        val current = session ?: return
        val rootLocation = IntArray(2)
        host.overlayParent.getLocationOnScreen(rootLocation)
        current.overlay.x = rawX - current.fingerOffsetX - rootLocation[0]
        current.overlay.y = rawY - current.fingerOffsetY - rootLocation[1]
    }

    private fun updatePointerInRecycler(rawX: Float, rawY: Float) {
        val recyclerLocation = IntArray(2)
        host.recyclerView.getLocationOnScreen(recyclerLocation)
        lastPointerInRecyclerX = rawX - recyclerLocation[0]
        lastPointerInRecyclerY = rawY - recyclerLocation[1]
    }

    private fun updateFolderDrop(current: Session) {
        val targetHolder = findFolderDropTargetUnderOverlayTopLeft(current)
        val candidate = targetHolder?.adapterPosition
            ?.takeIf { it != RecyclerView.NO_POSITION }
            ?.let { host.buildFolderDropCandidate(current.source, it) }
        if (candidate == null) {
            clearFolderHover(true)
            return
        }
        val now = SystemClock.uptimeMillis()
        if (!isSameFolderCandidate(hoverCandidate, candidate)) {
            hoverCandidate = candidate
            hoverStartedAt = now
            hoverFrameCount = 1
            if (!isSameFolderCandidate(armedFolderDrop, candidate)) {
                clearFolderHover(true, resetHover = false)
            }
            log("hover-enter action=${candidate.action} target=${candidate.targetListPosition}")
            return
        }
        hoverFrameCount += 1
        if (now - hoverStartedAt < FOLDER_DROP_HOVER_ARM_DELAY_MS ||
            hoverFrameCount < FOLDER_DROP_HOVER_ARM_MIN_FRAMES
        ) {
            return
        }
        if (!isSameFolderCandidate(armedFolderDrop, candidate)) {
            armedFolderDrop = candidate
            host.showFolderDropHover(candidate)
            clearInsertionLine()
            log("hover-armed action=${candidate.action} target=${candidate.targetListPosition}")
        }
    }

    private fun findFolderDropTargetUnderOverlayTopLeft(
        current: Session
    ): RecyclerView.ViewHolder? {
        // Drag-to-folder (both creating a folder and moving into an existing one)
        // is only available in the 正在进行 status. In 已完成 / 回收站 a drag is
        // reorder-only; structural moves go through the explicit move dialog.
        if (App.getApp()?.getStatus() != Def.ThingStatus.UNDERWAY) return null
        val recyclerView = host.recyclerView
        val recyclerLocation = IntArray(2)
        val rootLocation = IntArray(2)
        recyclerView.getLocationOnScreen(recyclerLocation)
        host.overlayParent.getLocationOnScreen(rootLocation)
        val x = getOverlayContentLeft(current.overlay) + rootLocation[0] - recyclerLocation[0]
        val y = getOverlayContentTop(current.overlay) + rootLocation[1] - recyclerLocation[1]
        val minPenetration = getFolderDropTargetInsetPx(recyclerView)
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i)
            val holder = recyclerView.getChildViewHolder(child)
            if (holder.adapterPosition == RecyclerView.NO_POSITION) continue
            val entry = host.getEntry(holder.adapterPosition) ?: continue
            if (entry.stableId == current.source.stableId) continue
            val left = child.left + child.translationX
            val top = child.top + child.translationY
            val right = child.right + child.translationX
            val bottom = child.bottom + child.translationY
            val horizontalInset = minPenetration
                .coerceAtMost(((right - left) - 1f) / 2f)
                .coerceAtLeast(0f)
            val verticalInset = minPenetration
                .coerceAtMost(((bottom - top) - 1f) / 2f)
                .coerceAtLeast(0f)
            if (x >= left + horizontalInset &&
                x < right - horizontalInset &&
                y >= top + verticalInset &&
                y < bottom - verticalInset
            ) {
                return holder
            }
        }
        return null
    }

    private fun getFolderDropTargetInsetPx(recyclerView: RecyclerView): Float {
        return recyclerView.resources.getDimension(R.dimen.folder_drop_target_inset)
    }

    private fun clearFolderHover(
        animateRestore: Boolean,
        resetHover: Boolean = true
    ) {
        if (armedFolderDrop != null) {
            host.clearFolderDropHover(animateRestore)
            log("hover-clear animate=$animateRestore")
        }
        armedFolderDrop = null
        if (resetHover) {
            hoverCandidate = null
            hoverStartedAt = 0L
            hoverFrameCount = 0
        }
    }

    private fun updateReorderCandidate(current: Session) {
        if (isPointerInsideVisibleSource(current)) {
            reorderCandidate = null
            hideInsertionLine()
            return
        }
        val candidate = findReorderCandidate(current)
        reorderCandidate = candidate ?: reorderCandidate
        if (candidate == null) {
            hideInsertionLine()
            return
        }
        showInsertionLine(candidate)
    }

    private fun findReorderCandidate(current: Session): ReorderCandidate? {
        val recyclerView = host.recyclerView
        var nearestHolder: RecyclerView.ViewHolder? = null
        var nearestDistance = Float.MAX_VALUE
        var nearestInsertAfter = false
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i)
            val holder = recyclerView.getChildViewHolder(child)
            val position = holder.adapterPosition
            if (position <= 0 || position == RecyclerView.NO_POSITION) continue
            val entry = host.getEntry(position) ?: continue
            if (entry.stableId == current.source.stableId) continue
            val left = child.left + child.translationX
            val top = child.top + child.translationY
            val right = child.right + child.translationX
            val bottom = child.bottom + child.translationY
            if (lastPointerInRecyclerX >= left &&
                lastPointerInRecyclerX < right &&
                lastPointerInRecyclerY >= top &&
                lastPointerInRecyclerY < bottom
            ) {
                val insertAfter = lastPointerInRecyclerY >= (top + bottom) / 2f
                if (!canShowReorderCandidate(current, entry)) return null
                return ReorderCandidate(
                    entry.stableId,
                    position,
                    insertAfter
                )
            }
            val topDistance = abs(lastPointerInRecyclerY - top)
            if (topDistance < nearestDistance) {
                nearestDistance = topDistance
                nearestHolder = holder
                nearestInsertAfter = false
            }
            val bottomDistance = abs(lastPointerInRecyclerY - bottom)
            if (bottomDistance < nearestDistance) {
                nearestDistance = bottomDistance
                nearestHolder = holder
                nearestInsertAfter = true
            }
        }
        val holder = nearestHolder ?: return null
        val position = holder.adapterPosition
        val entry = host.getEntry(position) ?: return null
        if (!canShowReorderCandidate(current, entry)) return null
        return ReorderCandidate(entry.stableId, position, nearestInsertAfter)
    }

    private fun canShowReorderCandidate(current: Session, targetEntry: ThingListEntry): Boolean {
        val sourcePosition = host.getListPositionForStableId(current.source.stableId)
        val sourceEntry = host.getEntry(sourcePosition) ?: return false
        return (sourceEntry.location < 0) == (targetEntry.location < 0)
    }

    private fun isPointerInsideVisibleSource(current: Session): Boolean {
        val recyclerView = host.recyclerView
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i)
            val holder = recyclerView.getChildViewHolder(child)
            val position = holder.adapterPosition
            if (position == RecyclerView.NO_POSITION) continue
            val entry = host.getEntry(position) ?: continue
            if (entry.stableId != current.source.stableId) continue
            val left = child.left + child.translationX
            val top = child.top + child.translationY
            val right = child.right + child.translationX
            val bottom = child.bottom + child.translationY
            return lastPointerInRecyclerX >= left &&
                lastPointerInRecyclerX < right &&
                lastPointerInRecyclerY >= top &&
                lastPointerInRecyclerY < bottom
        }
        return false
    }

    private fun showInsertionLine(candidate: ReorderCandidate) {
        val recyclerView = host.recyclerView
        val holder = recyclerView.findViewHolderForAdapterPosition(candidate.targetListPosition)
            ?: return hideInsertionLine()
        val child = holder.itemView
        val lineY = findInsertionLineY(child, candidate.insertAfter)
        val rect = RectF(
            child.left.toFloat(),
            lineY,
            child.right.toFloat(),
            lineY
        )
        val decoration = insertionDecoration ?: InsertionLineDecoration().also {
            insertionDecoration = it
            recyclerView.addItemDecoration(it)
        }
        val thickness = LINE_THICKNESS_DP * recyclerView.resources.displayMetrics.density
        decoration.update(rect, session?.source?.background, thickness)
        recyclerView.invalidateItemDecorations()
    }

    private fun findInsertionLineY(child: View, insertAfter: Boolean): Float {
        val recyclerView = host.recyclerView
        val sourceStableId = session?.source?.stableId
        var neighborEdge: Float? = null
        for (i in 0 until recyclerView.childCount) {
            val other = recyclerView.getChildAt(i)
            if (other === child) continue
            val holder = recyclerView.getChildViewHolder(other)
            val entry = host.getEntry(holder.adapterPosition) ?: continue
            if (entry.stableId == sourceStableId) continue
            val overlapsHorizontally =
                other.left < child.right && other.right > child.left
            if (!overlapsHorizontally) continue
            if (insertAfter) {
                if (other.top >= child.bottom) {
                    val current = neighborEdge
                    if (current == null || other.top < current) {
                        neighborEdge = other.top.toFloat()
                    }
                }
            } else if (other.bottom <= child.top) {
                val current = neighborEdge
                if (current == null || other.bottom > current) {
                    neighborEdge = other.bottom.toFloat()
                }
            }
        }
        val density = recyclerView.resources.displayMetrics.density
        val edgeOffset = INSERTION_LINE_EDGE_OFFSET_DP * density
        return if (insertAfter) {
            val preferred = child.bottom + edgeOffset
            val nextTop = neighborEdge
            if (nextTop != null) {
                preferred.coerceAtMost((child.bottom + nextTop) / 2f)
            } else {
                preferred
            }
        } else {
            val preferred = child.top - edgeOffset
            val previousBottom = neighborEdge
            if (previousBottom != null) {
                preferred.coerceAtLeast((previousBottom + child.top) / 2f)
            } else {
                preferred
            }
        }
    }

    private fun hideInsertionLine() {
        insertionDecoration?.hide()
        host.recyclerView.invalidateItemDecorations()
    }

    private fun clearInsertionLine() {
        val decoration = insertionDecoration ?: return
        host.recyclerView.removeItemDecoration(decoration)
        insertionDecoration = null
        host.recyclerView.invalidate()
    }

    private fun finishFolderDrop(current: Session, candidate: FolderDropCandidate) {
        finishing = true
        clearInsertionLine()
        clearFolderHover(false)
        val targetRect = if (candidate.action == FolderDropAction.CREATE) {
            getTargetRectInRoot(candidate.targetListPosition)
        } else {
            null
        }
        val result = host.commitFolderDrop(candidate)
        if (!result.committed ||
            candidate.action == FolderDropAction.CREATE && targetRect == null
        ) {
            host.clearFolderDropHover(true)
            animateOverlayBackOrFade(current) {
                endSession(current, restoreSource = true, notifySource = true)
            }
            return
        }
        val finish: () -> Unit = {
            endSession(current, restoreSource = false, notifySource = true)
            result.onVisualFinished?.invoke()
        }
        if (candidate.action == FolderDropAction.MOVE_TO_FOLDER) {
            animateOverlayShrinkToTopLeft(current, finish)
        } else {
            animateOverlayIntoTarget(current, targetRect!!, finish)
        }
    }

    private fun finishReorder(current: Session, candidate: ReorderCandidate) {
        finishing = true
        clearFolderHover(true)
        clearInsertionLine()
        current.overlay.animate().cancel()
        val committed = host.commitReorder(
            current.source,
            candidate.targetStableId,
            candidate.insertAfter
        )
        if (!committed) {
            finishSelection(current)
            return
        }
        requestFinalSpanAssignmentLayout()
        animateReorderOverlayToFinalSource(current)
    }

    private fun animateReorderOverlayToFinalSource(current: Session) {
        if (session !== current) return
        waitForFinalSourceLayoutReady(current) { targetRect ->
            host.recyclerView.postOnAnimation {
                animateReorderOverlayToSettledFinalSource(current, targetRect)
            }
        }
    }

    private fun animateReorderOverlayToSettledFinalSource(
        current: Session,
        targetRect: RectF
    ) {
        if (session !== current) return
        ensureVisibleSourcePlaceholder(current)
        val duration = host.recyclerView.itemAnimator?.moveDuration
            ?: REORDER_SETTLE_ANIM_DURATION
        val target = createOverlayTarget(current.overlay, targetRect)
        var overlaySettled = false
        var finished = false
        fun finishIfReady() {
            if (finished || session !== current || !overlaySettled) return
            finished = true
            current.overlay.x = target.x
            current.overlay.y = target.y
            current.overlay.scaleX = target.scaleX
            current.overlay.scaleY = target.scaleY
            endSession(current, restoreSource = false, notifySource = false) {
                restoreVisibleSourcePlaceholders(current.source)
            }
        }
        var overlayAnimationCanceled = false
        current.overlay.animate().cancel()
        current.overlay.animate()
            .x(target.x)
            .y(target.y)
            .scaleX(target.scaleX)
            .scaleY(target.scaleY)
            .alpha(1f)
            .setDuration(duration)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (overlayAnimationCanceled) return
                    overlaySettled = true
                    finishIfReady()
                }

                override fun onAnimationCancel(animation: Animator) {
                    overlayAnimationCanceled = true
                }
            })
            .start()
    }

    private fun waitForFinalSourceLayoutReady(
        current: Session,
        attempt: Int = 0,
        onSettled: (RectF) -> Unit
    ) {
        host.recyclerView.postOnAnimation {
            if (session !== current) return@postOnAnimation
            runAfterNextPreDraw {
                if (session !== current) return@runAfterNextPreDraw
                ensureVisibleSourcePlaceholder(current)
                val rect = getSettledSourceLayoutRectInRoot(current.source)
                if (rect != null &&
                    isRecyclerViewLayoutReadyForSynchronizedSettle()
                ) {
                    onSettled(rect)
                    return@runAfterNextPreDraw
                }
                if (attempt < REORDER_SETTLE_LAYOUT_MAX_ATTEMPTS) {
                    waitForFinalSourceLayoutReady(
                        current,
                        attempt + 1,
                        onSettled
                    )
                    return@runAfterNextPreDraw
                }
                if (rect != null) {
                    onSettled(rect)
                } else {
                    animateOverlayFadeOut(current) {
                        endSession(current, restoreSource = true, notifySource = true)
                    }
                }
            }
        }
    }

    private fun requestFinalSpanAssignmentLayout() {
        val layoutManager = host.recyclerView.layoutManager
        when (layoutManager) {
            is ThingsStaggeredLayoutManager -> {
                layoutManager.prepareForOverlayReorderAnimation()
            }
            is StaggeredGridLayoutManager -> {
                layoutManager.requestSimpleAnimationsInNextLayout()
                layoutManager.invalidateSpanAssignments()
            }
            else -> {
                host.recyclerView.requestLayout()
            }
        }
    }

    private fun finishSelection(current: Session) {
        finishing = true
        clearFolderHover(true)
        clearInsertionLine()
        animateOverlayBackOrFade(current) {
            restoreVisibleSourcePlaceholders(current.source)
            host.enterSelectionMode(current.source)
            endSession(current, restoreSource = true, notifySource = true)
        }
    }

    private fun animateOverlayIntoTarget(
        current: Session,
        targetRect: RectF,
        onFinished: () -> Unit
    ) {
        val targetScale = 0.16f
        val targetX = getOverlayLayoutXForContentCenter(
            current.overlay,
            targetRect.centerX(),
            targetScale
        )
        val targetY = getOverlayLayoutYForContentCenter(
            current.overlay,
            targetRect.centerY(),
            targetScale
        )
        current.overlay.animate().cancel()
        current.overlay.animate()
            .x(targetX)
            .y(targetY)
            .scaleX(targetScale)
            .scaleY(targetScale)
            .alpha(0f)
            .setDuration(FOLDER_DROP_COMMIT_ANIM_DURATION)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onFinished()
                }

                override fun onAnimationCancel(animation: Animator) {
                    onFinished()
                }
            })
            .start()
    }

    private fun animateOverlayShrinkToTopLeft(
        current: Session,
        onFinished: () -> Unit
    ) {
        var finished = false
        fun finishOnce() {
            if (finished) return
            finished = true
            current.overlay.pivotX = current.overlay.contentInsetPx.toFloat()
            current.overlay.pivotY = current.overlay.contentInsetPx.toFloat()
            current.overlay.scaleX = 0f
            current.overlay.scaleY = 0f
            current.overlay.alpha = 1f
            onFinished()
        }
        current.overlay.animate().cancel()
        setOverlayPivotToContentTopLeft(current.overlay)
        current.overlay.animate()
            .scaleX(0f)
            .scaleY(0f)
            .alpha(1f)
            .setDuration(FOLDER_DROP_COMMIT_ANIM_DURATION)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    finishOnce()
                }

                override fun onAnimationCancel(animation: Animator) {
                    finishOnce()
                }
            })
            .start()
    }

    private fun animateOverlayBackOrFade(
        current: Session,
        onFinished: () -> Unit
    ) {
        val targetRect = getSourceLayoutRectInRoot(current.source)
        if (targetRect == null) {
            current.overlay.animate().cancel()
            current.overlay.animate()
                .alpha(0f)
                .setDuration(CANCEL_FADE_ANIM_DURATION)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        onFinished()
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        onFinished()
                    }
                })
                .start()
            return
        }
        val target = createOverlayTarget(current.overlay, targetRect)
        var finished = false
        fun finishOnce() {
            if (finished) return
            finished = true
            current.overlay.x = target.x
            current.overlay.y = target.y
            current.overlay.scaleX = target.scaleX
            current.overlay.scaleY = target.scaleY
            onFinished()
        }
        current.overlay.animate().cancel()
        current.overlay.animate()
            .x(target.x)
            .y(target.y)
            .scaleX(target.scaleX)
            .scaleY(target.scaleY)
            .alpha(1f)
            .setDuration(SELECTION_RETURN_ANIM_DURATION)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    finishOnce()
                }

                override fun onAnimationCancel(animation: Animator) {
                    finishOnce()
                }
            })
            .start()
    }

    private fun createOverlayTarget(
        overlay: DragOverlayImageView,
        targetRect: RectF
    ): OverlayTarget {
        val contentWidth = overlay.contentWidth
        val contentHeight = overlay.contentHeight
        val scaleX = if (contentWidth > 0) {
            targetRect.width() / contentWidth
        } else {
            1f
        }
        val scaleY = if (contentHeight > 0) {
            targetRect.height() / contentHeight
        } else {
            1f
        }
        return OverlayTarget(
            getOverlayLayoutXForContentLeft(overlay, targetRect.left, scaleX),
            getOverlayLayoutYForContentTop(overlay, targetRect.top, scaleY),
            scaleX,
            scaleY
        )
    }

    private fun animateOverlayFadeOut(
        current: Session,
        onFinished: () -> Unit
    ) {
        current.overlay.animate().cancel()
        current.overlay.animate()
            .alpha(0f)
            .setDuration(CANCEL_FADE_ANIM_DURATION)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onFinished()
                }

                override fun onAnimationCancel(animation: Animator) {
                    onFinished()
                }
            })
            .start()
    }

    private fun getSourceLayoutRectInRoot(source: DragSource): RectF? {
        val position = host.getListPositionForStableId(source.stableId)
        if (position == RecyclerView.NO_POSITION || position < 0) return null
        return getItemLayoutRectInRoot(position)
    }

    private fun getSettledSourceLayoutRectInRoot(source: DragSource): RectF? {
        val recyclerView = host.recyclerView
        if (recyclerView.isComputingLayout || recyclerView.hasPendingAdapterUpdates()) {
            return null
        }
        val position = host.getListPositionForStableId(source.stableId)
        if (position == RecyclerView.NO_POSITION || position < 0) return null
        val holder = recyclerView.findViewHolderForAdapterPosition(position) ?: return null
        if (holder.adapterPosition != position) return null
        if (holder.itemView.getTag(R.id.tag_thing_card_bound_stable_id) != source.stableId) {
            return null
        }
        return getViewRectInRoot(holder.itemView, includeTransientTranslations = false)
    }

    private fun isRecyclerViewLayoutReadyForSynchronizedSettle(): Boolean {
        val recyclerView = host.recyclerView
        return !recyclerView.isComputingLayout &&
            !recyclerView.hasPendingAdapterUpdates() &&
            !recyclerView.isLayoutRequested &&
            recyclerView.scrollState == RecyclerView.SCROLL_STATE_IDLE
    }

    private fun createCardOutlineProvider(
        parent: ViewGroup,
        contentInsetPx: Int = 0
    ): ViewOutlineProvider {
        val radius = parent.resources.getDimension(R.dimen.thing_card_corner_radius)
        return object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(
                    contentInsetPx,
                    contentInsetPx,
                    view.width - contentInsetPx,
                    view.height - contentInsetPx,
                    radius
                )
            }
        }
    }

    private fun shouldCoverTransparentInteriorShadow(
        sourceHolder: RecyclerView.ViewHolder
    ): Boolean {
        val holder = sourceHolder as? BaseThingsAdapter.BaseThingViewHolder
        return holder?.cv?.getTag(R.id.tag_thing_folder_thumbnail_surface) == true
    }

    private fun getOverlayContentLeft(overlay: DragOverlayImageView): Float {
        return overlay.x +
            overlay.pivotX +
            (overlay.contentInsetPx - overlay.pivotX) * overlay.scaleX
    }

    private fun getOverlayContentTop(overlay: DragOverlayImageView): Float {
        return overlay.y +
            overlay.pivotY +
            (overlay.contentInsetPx - overlay.pivotY) * overlay.scaleY
    }

    private fun getOverlayLayoutXForContentLeft(
        overlay: DragOverlayImageView,
        contentLeft: Float,
        scaleX: Float
    ): Float {
        return getOverlayLayoutXForContentLeft(
            overlay.contentInsetPx,
            overlay.pivotX,
            scaleX,
            contentLeft
        )
    }

    private fun getOverlayLayoutYForContentTop(
        overlay: DragOverlayImageView,
        contentTop: Float,
        scaleY: Float
    ): Float {
        return getOverlayLayoutYForContentTop(
            overlay.contentInsetPx,
            overlay.pivotY,
            scaleY,
            contentTop
        )
    }

    private fun getOverlayLayoutXForContentLeft(
        contentInsetPx: Int,
        pivotX: Float,
        scaleX: Float,
        contentLeft: Float
    ): Float {
        return contentLeft - pivotX - (contentInsetPx - pivotX) * scaleX
    }

    private fun getOverlayLayoutYForContentTop(
        contentInsetPx: Int,
        pivotY: Float,
        scaleY: Float,
        contentTop: Float
    ): Float {
        return contentTop - pivotY - (contentInsetPx - pivotY) * scaleY
    }

    private fun getOverlayLayoutXForContentCenter(
        overlay: DragOverlayImageView,
        contentCenterX: Float,
        scaleX: Float
    ): Float {
        val localCenterX = overlay.contentInsetPx + overlay.contentWidth / 2f
        return contentCenterX - overlay.pivotX - (localCenterX - overlay.pivotX) * scaleX
    }

    private fun getOverlayLayoutYForContentCenter(
        overlay: DragOverlayImageView,
        contentCenterY: Float,
        scaleY: Float
    ): Float {
        val localCenterY = overlay.contentInsetPx + overlay.contentHeight / 2f
        return contentCenterY - overlay.pivotY - (localCenterY - overlay.pivotY) * scaleY
    }

    private fun setOverlayPivotToContentTopLeft(overlay: DragOverlayImageView) {
        val contentLeft = getOverlayContentLeft(overlay)
        val contentTop = getOverlayContentTop(overlay)
        val pivot = overlay.contentInsetPx.toFloat()
        overlay.pivotX = pivot
        overlay.pivotY = pivot
        overlay.x = getOverlayLayoutXForContentLeft(overlay, contentLeft, overlay.scaleX)
        overlay.y = getOverlayLayoutYForContentTop(overlay, contentTop, overlay.scaleY)
    }

    private fun getTargetRectInRoot(listPosition: Int): RectF? {
        val holder = host.recyclerView.findViewHolderForAdapterPosition(listPosition)
            ?: return null
        val targetView = (holder as? BaseThingsAdapter.BaseThingViewHolder)?.cv
            ?: holder.itemView
        return getViewRectInRoot(targetView, includeTransientTranslations = true)
    }

    private fun getItemLayoutRectInRoot(listPosition: Int): RectF? {
        val holder = host.recyclerView.findViewHolderForAdapterPosition(listPosition)
            ?: return null
        return getViewRectInRoot(holder.itemView, includeTransientTranslations = false)
    }

    private fun getViewRectInRoot(
        view: View,
        includeTransientTranslations: Boolean
    ): RectF? {
        if (view.width <= 0 || view.height <= 0) return null
        val origin = getViewLayoutOriginInRoot(view, includeTransientTranslations)
            ?: return getViewScreenRectInRoot(view, includeTransientTranslations)
        val left = origin[0]
        val top = origin[1]
        return RectF(
            left,
            top,
            left + view.width,
            top + view.height
        )
    }

    private fun getScaleOutset(size: Int, scale: Float): Float {
        return size * (scale - 1f) / 2f
    }

    private fun getViewLayoutOriginInRoot(
        view: View,
        includeTransientTranslations: Boolean
    ): FloatArray? {
        val origin = FloatArray(2)
        var current: View? = view
        while (current != null && current !== host.overlayParent) {
            origin[0] += current.left.toFloat()
            origin[1] += current.top.toFloat()
            if (includeTransientTranslations) {
                origin[0] += current.translationX
                origin[1] += current.translationY
            }
            val parent = current.parent as? View ?: return null
            origin[0] -= parent.scrollX.toFloat()
            origin[1] -= parent.scrollY.toFloat()
            current = parent
        }
        return if (current === host.overlayParent) origin else null
    }

    private fun getViewScreenRectInRoot(
        view: View,
        includeTransientTranslations: Boolean
    ): RectF? {
        val rootLocation = IntArray(2)
        val viewLocation = IntArray(2)
        host.overlayParent.getLocationOnScreen(rootLocation)
        view.getLocationOnScreen(viewLocation)
        val translation = if (includeTransientTranslations) {
            FloatArray(2)
        } else {
            getAccumulatedTranslationToOverlayRoot(view)
        }
        val left = viewLocation[0] - rootLocation[0].toFloat() - translation[0]
        val top = viewLocation[1] - rootLocation[1].toFloat() - translation[1]
        return RectF(left, top, left + view.width, top + view.height)
    }

    private fun getAccumulatedTranslationToOverlayRoot(view: View): FloatArray {
        val translation = FloatArray(2)
        var current: View? = view
        while (current != null && current !== host.overlayParent) {
            translation[0] += current.translationX
            translation[1] += current.translationY
            current = current.parent as? View
        }
        return translation
    }

    private fun runAfterNextPreDraw(action: () -> Unit) {
        val recyclerView = host.recyclerView
        if (!recyclerView.isAttachedToWindow) {
            recyclerView.post(action)
            return
        }
        val observer = recyclerView.viewTreeObserver
        observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val activeObserver = if (observer.isAlive) {
                    observer
                } else {
                    recyclerView.viewTreeObserver
                }
                activeObserver.removeOnPreDrawListener(this)
                action()
                return true
            }
        })
        recyclerView.invalidate()
    }

    private fun isFolderDropCandidateStillVisible(
        current: Session,
        candidate: FolderDropCandidate
    ): Boolean {
        val holder = findFolderDropTargetUnderOverlayTopLeft(current) ?: return false
        val position = holder.adapterPosition
        if (position != candidate.targetListPosition) return false
        val latest = host.buildFolderDropCandidate(current.source, position) ?: return false
        return isSameFolderCandidate(candidate, latest)
    }

    private fun updateAutoScroll() {
        val current = session ?: return
        val recyclerView = host.recyclerView
        val density = recyclerView.resources.displayMetrics.density
        val edgeSize = EDGE_SCROLL_ZONE_DP * density
        val maxStep = (MAX_EDGE_SCROLL_STEP_DP * density).roundToInt().coerceAtLeast(1)
        val height = recyclerView.height.toFloat()
        val y = lastPointerInRecyclerY
        autoScrollVelocity = when {
            y < edgeSize -> {
                val depth = ((edgeSize - y) / edgeSize).coerceIn(0f, 1f)
                -(max(1f, maxStep * depth).roundToInt())
            }
            y > height - edgeSize -> {
                val depth = ((y - (height - edgeSize)) / edgeSize).coerceIn(0f, 1f)
                max(1f, maxStep * depth).roundToInt()
            }
            else -> 0
        }
        if (autoScrollVelocity == 0) {
            stopAutoScroll()
            return
        }
        if (autoScrollRunnable != null) return
        autoScrollRunnable = object : Runnable {
            override fun run() {
                if (session !== current || autoScrollVelocity == 0 || finishing) {
                    autoScrollRunnable = null
                    return
                }
                recyclerView.scrollBy(0, autoScrollVelocity)
                updatePointerInRecycler(lastRawX, lastRawY)
                updateFrame()
                recyclerView.postOnAnimation(this)
            }
        }
        recyclerView.postOnAnimation(autoScrollRunnable!!)
    }

    private fun stopAutoScroll() {
        autoScrollVelocity = 0
        autoScrollRunnable = null
    }

    private fun endSession(
        current: Session,
        restoreSource: Boolean,
        notifySource: Boolean,
        afterOverlayRemoved: (() -> Unit)? = null
    ) {
        if (session !== current) return
        host.recyclerView.removeOnChildAttachStateChangeListener(current.attachListener)
        val observer = host.recyclerView.viewTreeObserver
        if (observer.isAlive) {
            observer.removeOnPreDrawListener(current.placeholderPreDrawListener)
        }
        current.overlay.animate().setListener(null)
        current.overlay.animate().cancel()
        current.overlay.setImageDrawable(null)
        (current.overlay.parent as? ViewGroup)?.removeView(current.overlay)
        afterOverlayRemoved?.invoke()
        if (restoreSource) {
            restoreSourceView(current)
            restoreVisibleSourcePlaceholders(current.source)
        }
        current.bitmap.recycle()
        if (notifySource) {
            host.notifySourcePlaceholderChanged(current.source)
        }
        session = null
        hoverCandidate = null
        hoverStartedAt = 0L
        hoverFrameCount = 0
        armedFolderDrop = null
        reorderCandidate = null
        finishing = false
        sourcePlaceholderLocked = false
        host.onOverlayDragActiveChanged(false)
    }

    private fun restoreSourceView(current: Session) {
        current.sourceView.animate().cancel()
        current.sourceView.setTag(R.id.tag_thing_card_moving_scale_recovery_token, null)
        current.sourceView.visibility = View.VISIBLE
        current.sourceView.alpha = 1f
        current.sourceView.scaleX = 1f
        current.sourceView.scaleY = 1f
        current.sourceView.setTag(R.id.tag_thing_card_drag_active, false)
        current.sourceView.setTag(R.id.tag_thing_card_finger_down, false)
        findAttachedThingHolder(current.sourceView)?.cv?.let { card ->
            card.animate().cancel()
            card.setTag(R.id.tag_thing_card_moving_scale_recovery_token, null)
            card.scaleX = 1f
            card.scaleY = 1f
            card.setTag(R.id.tag_thing_card_drag_active, false)
            card.setTag(R.id.tag_thing_card_finger_down, false)
        }
    }

    private fun ensureVisibleSourcePlaceholder(current: Session) {
        if (!sourcePlaceholderLocked) return
        if (!host.isOverlayDragSourceStillVisible(current.source)) return
        var applied = false
        forEachVisibleSourcePlaceholder(current.source) { view ->
            applySourcePlaceholder(view)
            applied = true
        }
        if (applied) return
        val position = host.getListPositionForStableId(current.source.stableId)
        val holder = host.recyclerView.findViewHolderForAdapterPosition(position) ?: return
        applySourcePlaceholder(holder.itemView)
    }

    private fun applySourcePlaceholder(view: View) {
        view.setTag(R.id.tag_thing_card_moving_scale_recovery_token, null)
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.scaleX = 1f
        view.scaleY = 1f
        view.setTag(R.id.tag_thing_card_drag_active, true)
        view.setTag(R.id.tag_thing_card_finger_down, true)
        findAttachedThingHolder(view)?.cv?.let { card ->
            card.animate().cancel()
            card.setTag(R.id.tag_thing_card_moving_scale_recovery_token, null)
            card.scaleX = 1f
            card.scaleY = 1f
            card.setTag(R.id.tag_thing_card_drag_active, true)
            card.setTag(R.id.tag_thing_card_finger_down, true)
        }
    }

    private fun restoreVisibleSourcePlaceholders(source: DragSource) {
        sourcePlaceholderLocked = false
        forEachVisibleSourcePlaceholder(source) { view ->
            restoreSourcePlaceholderView(view)
        }
        val position = host.getListPositionForStableId(source.stableId)
        if (position <= 0) return
        val holder = host.recyclerView.findViewHolderForAdapterPosition(position) ?: return
        restoreSourcePlaceholderView(holder.itemView)
    }

    private fun restoreSourcePlaceholderView(view: View) {
        view.animate().cancel()
        view.setTag(R.id.tag_thing_card_moving_scale_recovery_token, null)
        view.visibility = View.VISIBLE
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
        view.setTag(R.id.tag_thing_card_drag_active, false)
        view.setTag(R.id.tag_thing_card_finger_down, false)
        findAttachedThingHolder(view)?.cv?.let { card ->
            card.animate().cancel()
            card.setTag(R.id.tag_thing_card_moving_scale_recovery_token, null)
            card.scaleX = 1f
            card.scaleY = 1f
            card.setTag(R.id.tag_thing_card_drag_active, false)
            card.setTag(R.id.tag_thing_card_finger_down, false)
        }
    }

    private fun forEachVisibleSourcePlaceholder(source: DragSource, action: (View) -> Unit) {
        val recyclerView = host.recyclerView
        for (i in 0 until recyclerView.childCount) {
            val view = recyclerView.getChildAt(i)
            if (isSourcePlaceholderView(view, source)) {
                action(view)
            }
        }
    }

    private fun isSourcePlaceholderView(view: View, source: DragSource): Boolean {
        if (view === session?.sourceView && session?.source?.stableId == source.stableId) {
            return true
        }
        return view.getTag(R.id.tag_thing_card_bound_stable_id) == source.stableId
    }

    private fun findAttachedThingHolder(view: View): BaseThingsAdapter.BaseThingViewHolder? {
        if (view.parent !== host.recyclerView) return null
        return host.recyclerView.getChildViewHolder(view) as? BaseThingsAdapter.BaseThingViewHolder
    }

    private fun isSameFolderCandidate(
        a: FolderDropCandidate?,
        b: FolderDropCandidate?
    ): Boolean {
        if (a == null || b == null) return false
        return a.action == b.action &&
            a.sourceThingId == b.sourceThingId &&
            a.sourceFolderId == b.sourceFolderId &&
            a.targetThingId == b.targetThingId &&
            a.targetFolderId == b.targetFolderId
    }

    private fun MotionEvent.getRawXCompat(pointerIndex: Int): Float {
        return rawX - x + getX(pointerIndex)
    }

    private fun MotionEvent.getRawYCompat(pointerIndex: Int): Float {
        return rawY - y + getY(pointerIndex)
    }

    private fun log(message: String, startSession: Boolean = false) {
        if (!OVERLAY_DRAG_DEBUG) return
        DebugFileLogger.log(
            "thing_list_overlay_drag.log",
            message,
            "[DEBUG-overlay-drag]",
            startSession
        )
    }

    private class DragOverlayImageView(context: Context) : ImageView(context) {
        private data class BitmapTile(
            val bitmap: Bitmap,
            val sourceX: Int,
            val sourceY: Int
        )

        var contentInsetPx: Int = 0
            private set

        val contentWidth: Int
            get() = (width - contentInsetPx * 2).coerceAtLeast(0)

        val contentHeight: Int
            get() = (height - contentInsetPx * 2).coerceAtLeast(0)

        private val contentRect = RectF()
        private val tileDstRect = RectF()
        private val contentBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val bitmapTiles = ArrayList<BitmapTile>()
        private var overlayBitmap: Bitmap? = null
        private var cornerRadiusPx: Float = 0f
        private var coverTransparentInteriorShadow: Boolean = false
        private var contentBackground: ThingBackground? = null

        fun configure(
            contentInsetPx: Int,
            cornerRadiusPx: Float,
            coverTransparentInteriorShadow: Boolean,
            contentBackground: ThingBackground?
        ) {
            this.contentInsetPx = contentInsetPx.coerceAtLeast(0)
            this.cornerRadiusPx = cornerRadiusPx
            this.coverTransparentInteriorShadow =
                coverTransparentInteriorShadow && this.contentInsetPx > 0
            this.contentBackground =
                if (this.coverTransparentInteriorShadow) contentBackground else null
            setPadding(
                this.contentInsetPx,
                this.contentInsetPx,
                this.contentInsetPx,
                this.contentInsetPx
            )
            setLayerType(View.LAYER_TYPE_NONE, null)
        }

        override fun setImageBitmap(bm: Bitmap?) {
            releaseBitmapTiles()
            overlayBitmap = bm
            if (bm != null && shouldDrawBitmapAsTiles(bm)) {
                if (createBitmapTiles(bm)) {
                    super.setImageDrawable(null)
                    invalidate()
                    return
                }
                releaseBitmapTiles()
            }
            super.setImageBitmap(bm)
        }

        override fun setImageDrawable(drawable: Drawable?) {
            if (drawable == null) {
                overlayBitmap = null
                releaseBitmapTiles()
            }
            super.setImageDrawable(drawable)
        }

        override fun onDraw(canvas: Canvas) {
            if (coverTransparentInteriorShadow) {
                drawInteriorShadowCover(canvas)
            }
            if (bitmapTiles.isEmpty()) {
                super.onDraw(canvas)
            } else {
                drawBitmapTiles(canvas)
            }
        }

        private fun shouldDrawBitmapAsTiles(bitmap: Bitmap): Boolean {
            return bitmap.width > OVERLAY_BITMAP_TILE_MAX_SIZE ||
                bitmap.height > OVERLAY_BITMAP_TILE_MAX_SIZE
        }

        private fun createBitmapTiles(bitmap: Bitmap): Boolean {
            return try {
                var y = 0
                while (y < bitmap.height) {
                    val tileHeight = (bitmap.height - y)
                        .coerceAtMost(OVERLAY_BITMAP_TILE_MAX_SIZE)
                    var x = 0
                    while (x < bitmap.width) {
                        val tileWidth = (bitmap.width - x)
                            .coerceAtMost(OVERLAY_BITMAP_TILE_MAX_SIZE)
                        bitmapTiles += BitmapTile(
                            Bitmap.createBitmap(bitmap, x, y, tileWidth, tileHeight),
                            x,
                            y
                        )
                        x += tileWidth
                    }
                    y += tileHeight
                }
                bitmapTiles.isNotEmpty()
            } catch (_: RuntimeException) {
                false
            }
        }

        private fun drawBitmapTiles(canvas: Canvas) {
            val bitmap = overlayBitmap ?: return
            if (bitmap.width <= 0 || bitmap.height <= 0) return
            val scaleX = contentWidth.toFloat() / bitmap.width
            val scaleY = contentHeight.toFloat() / bitmap.height
            for (tile in bitmapTiles) {
                tileDstRect.set(
                    contentInsetPx + tile.sourceX * scaleX,
                    contentInsetPx + tile.sourceY * scaleY,
                    contentInsetPx + (tile.sourceX + tile.bitmap.width) * scaleX,
                    contentInsetPx + (tile.sourceY + tile.bitmap.height) * scaleY
                )
                canvas.drawBitmap(tile.bitmap, null, tileDstRect, bitmapPaint)
            }
        }

        private fun releaseBitmapTiles() {
            for (tile in bitmapTiles) {
                if (!tile.bitmap.isRecycled) {
                    tile.bitmap.recycle()
                }
            }
            bitmapTiles.clear()
        }

        private fun drawInteriorShadowCover(canvas: Canvas) {
            if (contentWidth <= 0 || contentHeight <= 0) return
            val bg = contentBackground ?: return
            contentRect.set(
                contentInsetPx.toFloat(),
                contentInsetPx.toFloat(),
                (width - contentInsetPx).toFloat(),
                (height - contentInsetPx).toFloat()
            )
            contentBackgroundPaint.shader = null
            contentBackgroundPaint.color = bg.color
            if (bg.mode == ThingBackground.Mode.GRADIENT) {
                contentBackgroundPaint.shader = createContentBackgroundShader(bg, contentRect)
            }
            canvas.drawRoundRect(
                contentRect,
                cornerRadiusPx,
                cornerRadiusPx,
                contentBackgroundPaint
            )
            contentBackgroundPaint.shader = null
        }

        private fun createContentBackgroundShader(
            bg: ThingBackground,
            rect: RectF
        ): LinearGradient {
            val left = rect.left
            val right = rect.right
            val top = rect.top
            val bottom = rect.bottom
            val centerY = rect.centerY()
            val points = when (bg.orientation) {
                ThingBackground.Orientation.L_R -> floatArrayOf(left, centerY, right, centerY)
                ThingBackground.Orientation.T_B -> floatArrayOf(left, top, left, bottom)
                ThingBackground.Orientation.LT_RB -> floatArrayOf(left, top, right, bottom)
                ThingBackground.Orientation.RT_LB -> floatArrayOf(right, top, left, bottom)
                ThingBackground.Orientation.LB_RT -> floatArrayOf(left, bottom, right, top)
                ThingBackground.Orientation.RB_LT -> floatArrayOf(right, bottom, left, top)
                ThingBackground.Orientation.R_L -> floatArrayOf(right, centerY, left, centerY)
                ThingBackground.Orientation.B_T -> floatArrayOf(left, bottom, left, top)
            }
            return LinearGradient(
                points[0],
                points[1],
                points[2],
                points[3],
                bg.color,
                bg.endColor,
                Shader.TileMode.CLAMP
            )
        }
    }

    private class InsertionLineDecoration : RecyclerView.ItemDecoration() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private var background: ThingBackground? = null
        private var visible: Boolean = false

        fun update(line: RectF, background: ThingBackground?, thickness: Float) {
            rect.set(
                line.left,
                line.top - thickness / 2f,
                line.right,
                line.top + thickness / 2f
            )
            this.background = background
            visible = true
        }

        fun hide() {
            visible = false
        }

        fun lineRect(): RectF? {
            if (!visible) return null
            return RectF(rect)
        }

        override fun onDrawOver(
            c: Canvas,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            if (!visible || rect.width() <= 0f) return
            val bg = background
            paint.shader = null
            paint.color = bg?.color ?: Color.TRANSPARENT
            if (bg?.mode == ThingBackground.Mode.GRADIENT) {
                paint.shader = createGradientShader(bg, rect)
            }
            val radius = rect.height() / 2f
            c.drawRoundRect(rect, radius, radius, paint)
            paint.shader = null
        }

        private fun createGradientShader(
            bg: ThingBackground,
            rect: RectF
        ): LinearGradient {
            val left = rect.left
            val right = rect.right
            val top = rect.top
            val bottom = rect.bottom
            val centerY = rect.centerY()
            val points = when (bg.orientation) {
                ThingBackground.Orientation.L_R -> floatArrayOf(left, centerY, right, centerY)
                ThingBackground.Orientation.T_B -> floatArrayOf(left, top, left, bottom)
                ThingBackground.Orientation.LT_RB -> floatArrayOf(left, top, right, bottom)
                ThingBackground.Orientation.RT_LB -> floatArrayOf(right, top, left, bottom)
                ThingBackground.Orientation.LB_RT -> floatArrayOf(left, bottom, right, top)
                ThingBackground.Orientation.RB_LT -> floatArrayOf(right, bottom, left, top)
                ThingBackground.Orientation.R_L -> floatArrayOf(right, centerY, left, centerY)
                ThingBackground.Orientation.B_T -> floatArrayOf(left, bottom, left, top)
            }
            return LinearGradient(
                points[0],
                points[1],
                points[2],
                points[3],
                bg.color,
                bg.endColor,
                Shader.TileMode.CLAMP
            )
        }
    }

    companion object {
        private const val OVERLAY_DRAG_DEBUG = false
        private const val DRAG_OVERLAY_SCALE = 1.11f
        private const val OVERLAY_SYSTEM_SHADOW_INSET_RATIO = 2f
        private const val OVERLAY_BITMAP_TILE_MAX_SIZE = 1024
        private const val FOLDER_DROP_HOVER_ARM_DELAY_MS = 130L
        private const val FOLDER_DROP_HOVER_ARM_MIN_FRAMES = 2
        private const val FOLDER_DROP_COMMIT_ANIM_DURATION = 190L
        private const val REORDER_SETTLE_ANIM_DURATION = 120L
        private const val REORDER_SETTLE_LAYOUT_MAX_ATTEMPTS = 150
        private const val SELECTION_RETURN_ANIM_DURATION = 120L
        private const val CANCEL_FADE_ANIM_DURATION = 96L
        private const val EDGE_SCROLL_ZONE_DP = 88f
        private const val MAX_EDGE_SCROLL_STEP_DP = 22f
        private const val INSERTION_LINE_EDGE_OFFSET_DP = 6f
        private const val LINE_THICKNESS_DP = 4f
    }
}
