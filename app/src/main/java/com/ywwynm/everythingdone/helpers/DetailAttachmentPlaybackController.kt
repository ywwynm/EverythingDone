package com.ywwynm.everythingdone.helpers

import android.graphics.Rect
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import com.ywwynm.everythingdone.adapters.ImageAttachmentAdapter
import com.ywwynm.everythingdone.model.DetailAutoplayMode
import java.util.TreeSet

/**
 * 详情附件网格的播放调度器（**Detail Autoplay**）。见 ADR-0017 与
 * docs/features/detail-animated-playback/。
 *
 * 它只做"谁该在播"的决策，真正的 Glide 加载与 GifDrawable 控制留在
 * [ImageAttachmentAdapter]；两者之间只有 [ImageAttachmentAdapter.refreshPlayback] 与
 * [shouldPlay] / [shouldLoop] / [wantsDerivedPreview] 三个查询。
 *
 * 为什么需要自己算可见性：详情附件 RecyclerView 的高度按总行数写死、
 * `isNestedScrollingEnabled = false`，**所有项一次性全量布局并 attach**，因此 Glide
 * 依赖 View 可见性的屏外自动暂停在这里完全失效——滚出屏幕的 GIF 照样在解码。
 *
 * 可见性用 [View.getGlobalVisibleRect] 直接量"这一项有多少真的露在屏幕上"，
 * 而不是按行高做算术：前者天然吃掉 full-span、定制尺寸、软键盘、系统栏等所有情况。
 * 进入/离开用不同阈值做**滞回**，否则手指停在边界上会反复触发重播。
 */
class DetailAttachmentPlaybackController(
    private val scrollView: NestedScrollView,
    private val recyclerView: RecyclerView
) {

    companion object {
        /** 露出面积达到该比例才算"进入视口"。 */
        private const val ENTER_FRACTION = 0.6f
        /** 露出面积掉到该比例以下才算"离开视口"，与 [ENTER_FRACTION] 之间为滞回区。 */
        private const val LEAVE_FRACTION = 0.3f
    }

    private var adapter: ImageAttachmentAdapter? = null

    private var mode: Int = DetailAutoplayMode.current()

    /** 当前判定为在视口内的 position。 */
    private val visible = HashSet<Int>()

    /** 当前应处于播放态的 position（[shouldPlay] 的唯一依据）。 */
    private val playing = HashSet<Int>()

    /** 长按手动触发播放中的 position；它不受档位自动调度约束。 */
    private val manual = HashSet<Int>()

    /** 逐一播放档的待播队列，按索引升序——用户期待"从左上到右下依次亮起"。 */
    private val queue = TreeSet<Int>()

    /** 逐一播放档下当前正在播的 position，-1 表示空闲。 */
    private var sequentialPlaying: Int = -1

    private val tmpRect = Rect()

    /**
     * 长按走 RecyclerView 级的 OnItemTouchListener，而不是 item 的 OnLongClickListener：
     * `ItemTouchHelper` 的拖拽也由长按触发，且它一旦开始拖拽就会给子 View 发 ACTION_CANCEL，
     * 把子 View 自己的长按判定掐掉。这里永不拦截事件，拖拽排序照常由 ItemTouchHelper 接管
     * （二者共存是刻意的，见 decisions.md D14）。
     */
    private val longPressDetector = GestureDetector(
        recyclerView.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                val child = recyclerView.findChildViewUnder(e.x, e.y) ?: return
                val position = recyclerView.getChildAdapterPosition(child)
                if (position != RecyclerView.NO_POSITION) {
                    onManualPlay(position)
                }
            }
        }
    )

    private val touchListener = object : RecyclerView.SimpleOnItemTouchListener() {
        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            longPressDetector.onTouchEvent(e)
            return false
        }
    }

    init {
        // 用 ViewTreeObserver 而非 setOnScrollChangeListener：后者是单例 setter，
        // DetailActivity 已经用它做 action bar 阴影/着色，不能被顶掉。
        scrollView.viewTreeObserver.addOnScrollChangedListener { evaluate() }
        recyclerView.viewTreeObserver.addOnGlobalLayoutListener { evaluate() }
        recyclerView.addOnItemTouchListener(touchListener)
    }

    fun setAdapter(adapter: ImageAttachmentAdapter?) {
        this.adapter = adapter
        adapter?.setPlaybackController(this)
        onItemsChanged()
    }

    /** 附件增删/重排后 position 全部作废，清空状态重新按可见性起播。 */
    fun onItemsChanged() {
        visible.clear()
        playing.clear()
        manual.clear()
        queue.clear()
        sequentialPlaying = -1
        recyclerView.post { evaluate() }
    }

    /** 从设置页返回时档位可能已变；档位变了就整体重置，否则只重算可见性。 */
    fun onResume() {
        val newMode = DetailAutoplayMode.current()
        if (newMode != mode) {
            mode = newMode
            resetPlaybackKeepingVisibility()
        } else {
            evaluate()
        }
    }

    // —— 供 ImageAttachmentAdapter 查询 ——

    fun shouldPlay(position: Int): Boolean = playing.contains(position)

    /** 只有「同时循环播放」档是无限循环；长按手动播放永远只播一轮。 */
    fun shouldLoop(position: Int): Boolean =
        DetailAutoplayMode.loops(mode) && !manual.contains(position)

    /**
     * 是否该为该项后台生成派生 GIF。只为**视口内**的项请求——这既是限流（避免打开一条
     * 6 个视频的记事就并发起多个取帧任务），也让「关闭自动播放」档不做无用功；但用户
     * 长按明确要看时照常请求。
     */
    fun wantsDerivedPreview(position: Int): Boolean {
        if (manual.contains(position)) return true
        return visible.contains(position) && DetailAutoplayMode.requestsPreview(mode)
    }

    /**
     * 由适配器在派生 GIF 生成完成时回调：该项此前因"此刻还不能播"而落选，现在补上。
     * 已经在播的（如同时循环档下它本就在 playing 里）由适配器那次重新绑定接手，这里不重复。
     */
    fun onDerivedPreviewReady(position: Int) {
        if (!visible.contains(position)) return
        if (playing.contains(position)) return
        startForVisible(position)
    }

    /** 由适配器在 GifDrawable 播完约定轮数时回调：回静态代表帧，并推进逐一队列。 */
    fun onPlaybackFinished(position: Int) {
        manual.remove(position)
        if (playing.remove(position)) {
            adapter?.refreshPlayback(position)
        }
        if (sequentialPlaying == position) {
            sequentialPlaying = -1
            pumpQueue()
        }
    }

    // —— 内部调度 ——

    private fun onManualPlay(position: Int) {
        if (!DetailAutoplayMode.allowsManualPlay(mode)) return
        if (playing.contains(position)) return
        manual.add(position)
        startPlaying(position)
    }

    private fun evaluate() {
        if (adapter == null) return
        if (recyclerView.visibility != View.VISIBLE) return
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i) ?: continue
            val position = recyclerView.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION) continue
            val fraction = visibleFractionOf(child)
            val was = visible.contains(position)
            val now = if (was) fraction > LEAVE_FRACTION else fraction >= ENTER_FRACTION
            if (now == was) continue
            if (now) onEnterViewport(position) else onLeaveViewport(position)
        }
    }

    private fun onEnterViewport(position: Int) {
        visible.add(position)
        startForVisible(position)
    }

    private fun onLeaveViewport(position: Int) {
        visible.remove(position)
        queue.remove(position)
        manual.remove(position)
        stopPlaying(position)
        if (sequentialPlaying == position) {
            sequentialPlaying = -1
            pumpQueue()
        }
    }

    private fun startForVisible(position: Int) {
        if (!DetailAutoplayMode.autoPlays(mode)) return
        if (DetailAutoplayMode.sequential(mode)) {
            queue.add(position)
            pumpQueue()
        } else {
            if (adapter?.isPlayableNow(position) != true) return
            startPlaying(position)
        }
    }

    /**
     * 取队首里**此刻真的能播**的那一个。必须逐个筛掉不能播的：静态图片永远不会发出
     * "播完"的回调，一旦让它占住 [sequentialPlaying]，队列就此卡死，它后面的动图再也
     * 轮不到——这正是"可播附件中间穿插静态图片时逐一播放走不完"的成因。
     *
     * 被筛掉的项直接出队：静态图片本就不该再进来；派生 GIF 仍在生成的视频 / Motion Photo
     * 会在就绪时由 [onDerivedPreviewReady] 重新入队。
     */
    private fun pumpQueue() {
        if (sequentialPlaying != -1) return
        while (true) {
            val next = queue.pollFirst() ?: return
            if (!visible.contains(next)) continue
            if (adapter?.isPlayableNow(next) != true) continue
            sequentialPlaying = next
            startPlaying(next)
            return
        }
    }

    private fun startPlaying(position: Int) {
        if (!playing.add(position)) return
        adapter?.refreshPlayback(position)
    }

    private fun stopPlaying(position: Int) {
        if (!playing.remove(position)) return
        adapter?.refreshPlayback(position)
    }

    private fun resetPlaybackKeepingVisibility() {
        val wasPlaying = playing.toList()
        playing.clear()
        manual.clear()
        queue.clear()
        sequentialPlaying = -1
        wasPlaying.forEach { adapter?.refreshPlayback(it) }
        visible.sorted().forEach { startForVisible(it) }
    }

    private fun visibleFractionOf(child: View): Float {
        if (!child.isShown) return 0f
        val w = child.width
        val h = child.height
        if (w <= 0 || h <= 0) return 0f
        if (!child.getGlobalVisibleRect(tmpRect)) return 0f
        val vw = tmpRect.width().coerceAtMost(w)
        val vh = tmpRect.height().coerceAtMost(h)
        if (vw <= 0 || vh <= 0) return 0f
        return (vw.toFloat() * vh) / (w.toFloat() * h)
    }
}
