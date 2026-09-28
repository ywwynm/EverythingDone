package com.ywwynm.everythingdone.taptransfer.ui.card

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.adapters.BaseThingsAdapter
import com.ywwynm.everythingdone.adapters.ThingsAdapter
import com.ywwynm.everythingdone.database.ThingFolderDAO
import com.ywwynm.everythingdone.managers.ModeManager
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.model.ThingFolder
import com.ywwynm.everythingdone.model.ThingFolderCardPresentation
import com.ywwynm.everythingdone.model.ThingListEntry
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.views.ThingCardChecklistView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 用首页的卡片渲染一条记事或一个文件夹：布局、字号、内容行数、清单条数、附件与封面、文件夹的计数与缩略图的规则
 * 与首页逐项相同（直接继承首页的适配器，只把数据换成这一项）。
 *
 * 握手里交给接收方的卡片形状按首页的尺寸量（[listShapes]）；分享界面抬起的卡片更宽，放不下时删减内容
 * 而不缩小（[LiftedCard]）。
 */
internal object TapThingCards {

    /**
     * 抬起或量形状的一项：一条记事，或一个文件夹在首页列表里的那一项（计数、缩略图与列表一致，
     * 决策 2026-09-28 第 12 条）。
     */
    sealed class Item {
        class ThingItem(val thing: Thing) : Item()
        class FolderItem(val entry: ThingListEntry.FolderEntry) : Item()
    }

    /**
     * 首页列表在这个窗口里给卡片的尺寸：普通卡片宽、通栏卡片宽，以及卡片可用高度（封面缩略图的最小与最大
     * 高度、封面作背景时的最大高度都按它钳制）。首页适配器是从列表上读这三个量的，这里按同样的规则从窗口算：
     * 列表铺满窗口，左右各留一个卡片外边距，上边让出状态栏与 actionbar、下边让出导航栏
     * （`DisplayUtil.applyImmersiveListInsetPadding`）。
     */
    class ListMetrics(val normalWidth: Int, val fullWidth: Int, val surfaceHeight: Int) {
        fun widthOf(fullSpan: Boolean): Int = if (fullSpan) fullWidth else normalWidth
    }

    fun listMetrics(activity: Activity): ListMetrics {
        val res = activity.resources
        val spacing = res.getDimensionPixelSize(R.dimen.thing_card_outer_spacing)
        val decor = activity.window.decorView
        val screen = DisplayUtil.getScreenSize(activity)
        val width = if (decor.width > 0) decor.width else res.displayMetrics.widthPixels
        val height = if (decor.height > 0) decor.height else screen.y
        val span = DisplayUtil.homeListSpanCount(activity)
        // 列表内容宽度：去掉列表左右的内边距；每张卡片左右各有一个外边距
        val content = width - spacing * 2
        val bars = ViewCompat.getRootWindowInsets(decor)
            ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val top = (bars?.top ?: 0) + DisplayUtil.resolveActionBarSize(activity)
        val bottom = bars?.bottom ?: 0
        return ListMetrics(
            normalWidth = ((content - spacing * 2 * span) / span).coerceAtLeast(1),
            fullWidth = (content - spacing * 2).coerceAtLeast(1),
            surfaceHeight = (height - top - bottom).coerceAtLeast(1)
        )
    }

    /**
     * 这几项的卡片在本机首页列表里的形状：高宽比（高 / 宽）与是否通栏，与首页排出来的一样（[create]），
     * 私密记事按不显示内容的样子量（接收方就是这样显示的）；文件夹按显示内容的样子量（接收方收到的文件夹
     * 不是私密的）。握手把它交给接收方，接收方按自己的列宽乘回去预计落点。最多量 [limit] 项；在主线程调用。
     */
    fun listShapes(activity: Activity, items: List<Item>, limit: Int): Pair<FloatArray, BooleanArray> {
        val metrics = listMetrics(activity)
        val count = min(items.size, limit)
        val aspects = FloatArray(count)
        val spans = BooleanArray(count)
        for (k in 0 until count) {
            val card = create(activity, items[k], metrics, revealPrivate = false)
            val width = card.layoutParams.width
            card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            aspects[k] = card.measuredHeight.coerceAtLeast(1).toFloat() / width
            // 首页至少两列，普通卡片总比通栏窄
            spans[k] = width == metrics.fullWidth
        }
        return aspects to spans
    }

    /**
     * 渲染一张卡片。返回的视图已绑定、可直接加进布局，宽度是这一项在首页上的宽度（普通或通栏）、高度随内容。
     *
     * @param revealPrivate 私密记事是否显示内容。分享方在分享前已经验证过身份，显示；
     *        接收方按首页的规则，不显示。文件夹一律显示（收到的文件夹不是私密的）。
     */
    fun create(activity: Activity, item: Item, metrics: ListMetrics, revealPrivate: Boolean): View =
        LiftedCard.of(activity, item, metrics, revealPrivate).view

    /**
     * 这一项在首页上的代表色：接收方在记事内容到达之前按它画代理卡片。缩略图形态的文件夹卡片是一块偏灰的底
     * （按文件夹颜色调过的列表底色），不是文件夹的颜色。
     */
    fun representativeColor(context: Context, item: Item): Int = when (item) {
        is Item.ThingItem -> item.thing.getBackground()?.representativeColor() ?: item.thing.getColor()
        is Item.FolderItem -> folderColor(context, item.entry.folder, item.entry.folder.cardPresentation)
    }

    /** 文件夹卡片在首页上的代表色（[presentation] 取实际显示的那份外观）。 */
    fun folderColor(context: Context, folder: ThingFolder, presentation: ThingFolderCardPresentation): Int {
        val background = folder.getBackground() ?: ThingBackground.pure(folder.getColor())
        if (presentation.mode == ThingFolderCardPresentation.MODE_THUMBNAILS) {
            val list = ContextCompat.getColor(context, R.color.bg_activity_things)
            return BackgroundUtil.mutedSurfaceBackground(background, list).representativeColor()
        }
        return background.representativeColor()
    }

    private fun newAdapter(activity: Activity, thing: Thing, metrics: ListMetrics,
                           revealPrivate: Boolean): SingleThingAdapter {
        val adapter = SingleThingAdapter(activity.application as App, thing, revealPrivate)
        setUp(adapter, metrics, revealPrivate)
        return adapter
    }

    private fun setUp(adapter: ThingsAdapter, metrics: ListMetrics, revealPrivate: Boolean) {
        adapter.setCardWidth(metrics.normalWidth)
        adapter.setFullSpanCardWidth(metrics.fullWidth)
        adapter.setThingCardSurfaceAvailableHeight(metrics.surfaceHeight)
        adapter.setShouldShowPrivateContent(revealPrivate)
        // 动图、视频封面只取静态的一帧：卡片会被截成粒子
        adapter.setAnimatedPlaybackEnabled(false)
    }

    /** 绑定（或重新绑定）这张卡片，绑完宽度设为 [width]、高度随内容。 */
    private fun bind(adapter: ThingsAdapter, holder: BaseThingsAdapter.BaseThingViewHolder, width: Int) {
        val card = holder.itemView
        val previous = card.layoutParams as? FrameLayout.LayoutParams
        // 首页适配器绑定时按瀑布流的布局参数设外边距，先给它一份，绑完再换成普通的
        card.layoutParams = StaggeredGridLayoutManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        adapter.onBindViewHolder(holder, 0)
        card.layoutParams = (previous ?: FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))
            .also { it.width = width }
    }

    /**
     * 分享界面里抬起的一张卡片（决策 2026-09-26「抬起的卡片放大、按首页字号排」）。起初按首页的尺寸绑定；
     * 舞台知道卡片区之后按 [widthIn] 加宽、按 [fit] 在高度上限里排版。
     *
     * @param home 首页列表的尺寸：通栏宽是加宽的依据，卡片可用高度用来钳制封面（与首页相同）。
     */
    abstract class LiftedCard(protected val home: ListMetrics) {

        abstract val fullSpan: Boolean

        abstract val view: View

        /**
         * 在宽 [areaWidth] 的卡片区里抬起时的宽度：通栏按首页的通栏宽，最宽 [LIFT_MAX_WIDTH_DP]，也不超过卡片区
         * 减去两侧与首页相同的留白（列表的内边距加卡片的外边距）；普通卡片取通栏的 [LIFT_NORMAL_RATIO]。
         */
        fun widthIn(areaWidth: Int): Int {
            val res = view.resources
            val side = res.getDimensionPixelSize(R.dimen.thing_card_outer_spacing) * 2
            val full = minOf(home.fullWidth, (LIFT_MAX_WIDTH_DP * res.displayMetrics.density).toInt(),
                areaWidth - side * 2).coerceAtLeast(1)
            return if (fullSpan) full else (full * LIFT_NORMAL_RATIO).roundToInt().coerceAtLeast(1)
        }

        /**
         * 按 [width] 重新排版，高度尽量不超过 [maxHeight]，返回排好之后的高度；可能仍超过上限，剩下的由舞台
         * 整体缩小。会重新绑定卡片：视图的透明度、缩放要在这之后再设。
         */
        abstract fun fit(width: Int, maxHeight: Int, minCoverHeight: Int): Int

        companion object {
            /** @param revealPrivate 见 [create]。 */
            fun of(activity: Activity, item: Item, home: ListMetrics, revealPrivate: Boolean): LiftedCard =
                when (item) {
                    is Item.ThingItem -> LiftedThingCard(activity, item.thing, home, revealPrivate)
                    is Item.FolderItem -> LiftedFolderCard(activity, item.entry, home)
                }
        }
    }

    /** 一条记事：字号、行数与条数的上限、封面的规则都沿用首页，放不下时删减内容。 */
    private class LiftedThingCard(activity: Activity, private val thing: Thing, home: ListMetrics,
                                  revealPrivate: Boolean) : LiftedCard(home) {

        private val adapter = newAdapter(activity, thing, home, revealPrivate)
        private val holder = adapter.onCreateViewHolder(FrameLayout(activity), adapter.getItemViewType(0))
            .also { resolveDirectionForDetachedMeasure(it.itemView) }

        override val fullSpan: Boolean = adapter.isFullSpan()

        override val view: View get() = holder.itemView

        init {
            bind(adapter, holder, home.widthOf(fullSpan))
        }

        /**
         * 先减正文行数或清单条数（末尾照首页带省略号，最少留 [MIN_KEPT] 行或条，清单的字号仍按首页那张的条数算），
         * 还不够再把上下的缩略图、作背景的图压扁（按更宽的比例裁切，最矮 [minCoverHeight]）。
         */
        override fun fit(width: Int, maxHeight: Int, minCoverHeight: Int): Int {
            val start = SystemClock.elapsedRealtimeNanos()
            val height = fitWithin(width, maxHeight, minCoverHeight)
            if (BuildConfig.DEBUG) {
                // 魅族不输出 Log.d
                Log.i(TAG, "fit ${thing.id} width=$width max=$maxHeight natural=$naturalHeight " +
                    "textCap=${adapter.textCap.takeIf { it != Int.MAX_VALUE }} " +
                    "coverCap=${adapter.coverCap.takeIf { it != Int.MAX_VALUE }} height=$height " +
                    "ms=${(SystemClock.elapsedRealtimeNanos() - start) / 1_000_000}")
            }
            return height
        }

        /** 调试：这次排版里不删减时的高度。 */
        private var naturalHeight = 0

        private fun fitWithin(width: Int, maxHeight: Int, minCoverHeight: Int): Int {
            adapter.textCap = Int.MAX_VALUE
            adapter.coverCap = Int.MAX_VALUE
            adapter.checklistSizeBasis = null
            adapter.setCardWidth(width)
            var height = rebind(width)
            naturalHeight = height
            if (height <= maxHeight) {
                return height
            }
            // 正文行数或清单条数：找出放得下的最多那个
            val checklist = holder.rvChecklist.isVisible
            val shown = when {
                checklist -> holder.rvChecklist.visibleItemCount
                holder.tvContent?.isVisible == true -> holder.tvContent.lineCount
                else -> 0
            }
            if (shown > MIN_KEPT) {
                if (checklist) {
                    adapter.checklistSizeBasis = shown
                }
                var low = MIN_KEPT
                var high = shown - 1
                var best = MIN_KEPT
                var probed = -1
                var probedHeight = 0
                while (low <= high) {
                    val mid = (low + high) ushr 1
                    adapter.textCap = mid
                    probed = mid
                    probedHeight = rebind(width)
                    if (probedHeight <= maxHeight) {
                        best = mid
                        low = mid + 1
                    } else {
                        high = mid - 1
                    }
                }
                adapter.textCap = best
                // 最后一次试的正好是结果时，卡片已经按它排好了
                height = if (probed == best) probedHeight else rebind(width)
                if (height <= maxHeight) {
                    return height
                }
            }
            // 封面压扁：作背景时卡片仍不低于文字，压了也未必全省得下来
            val cover = squashableCoverHeight()
            if (cover > minCoverHeight) {
                adapter.coverCap = max(minCoverHeight, cover - (height - maxHeight))
                height = rebind(width)
            }
            return height
        }

        private fun rebind(width: Int): Int {
            bind(adapter, holder, width)
            val card = holder.itemView
            val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            card.measure(widthSpec, heightSpec)
            // 首页在第一次布局之后才按实测的文字高度再排一次侧栏封面与作背景的封面（异步）；
            // 这里先布局一次、同步排掉，量到的就是最后的高度
            card.layout(0, 0, width, card.measuredHeight)
            val side = adapter.resyncSideImage(holder)
            val background = adapter.applyThingCardMediaBackgroundHeightToBoundHolder(holder, thing)
            if (side || background) {
                card.measure(widthSpec, heightSpec)
            }
            // 首页卡片自带的投影由舞台统一画（整叠一片），卡片自己不投
            (card as? CardView)?.cardElevation = 0f
            return card.measuredHeight
        }

        /** 可以压扁的封面此刻的高度：作背景的图，或在上、在下的缩略图；侧栏的封面高度跟着文字走，不算。 */
        private fun squashableCoverHeight(): Int {
            val background = holder.ivMediaBackground
            if (background != null && background.isVisible) {
                return background.layoutParams.height.coerceAtLeast(0)
            }
            val image = holder.flImageAttachment ?: return 0
            if (!image.isVisible || holder.llContent?.orientation != LinearLayout.VERTICAL) {
                return 0
            }
            return image.measuredHeight
        }
    }

    /**
     * 一个文件夹：与首页的文件夹卡片相同（「大」形态带缩略图）。没有可以删减的正文，放不下时只能由舞台整体
     * 缩小。私密文件夹显示内容：分享方在分享前已经验证过身份，接收方收到的也不是私密的。
     */
    private class LiftedFolderCard(activity: Activity, entry: ThingListEntry.FolderEntry, home: ListMetrics) :
        LiftedCard(home) {

        private val adapter = SingleFolderAdapter(activity.application as App, entry).also {
            setUp(it, home, revealPrivate = true)
            // 缩略图的列数跟首页列表走（列数 + 1）：在首页上就借首页的列表读列数
            activity.findViewById<RecyclerView>(R.id.rv_things)?.let { list ->
                it.setHostRecyclerViewForDelegatedBinding(list)
                it.setCardWidth(home.normalWidth)
                it.setFullSpanCardWidth(home.fullWidth)
            }
        }
        private val holder = adapter.onCreateViewHolder(FrameLayout(activity), adapter.getItemViewType(0))
            .also { resolveDirectionForDetachedMeasure(it.itemView) }

        override val fullSpan: Boolean =
            entry.folder.cardPresentation.spanMode == ThingFolderCardPresentation.SPAN_FULL

        override val view: View get() = holder.itemView

        init {
            rebind(home.widthOf(fullSpan))
        }

        override fun fit(width: Int, maxHeight: Int, minCoverHeight: Int): Int {
            adapter.setCardWidth(width)
            return rebind(width)
        }

        private fun rebind(width: Int): Int {
            bind(adapter, holder, width)
            val card = holder.itemView
            card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            card.layout(0, 0, width, card.measuredHeight)
            (card as? CardView)?.cardElevation = 0f
            return card.measuredHeight
        }
    }

    private class SingleThingAdapter(
        app: App,
        private val thing: Thing,
        private val revealPrivate: Boolean
    ) : ThingsAdapter(app, null) {

        private val things: MutableList<Thing?> = mutableListOf(thing)
        private val folders = ThingFolderDAO.getInstance(app)

        /** 正文行数、清单条数最多这么多（再与首页的上限取小）。 */
        var textCap = Int.MAX_VALUE

        /** 上下的缩略图、作背景的图最高这么高（再与首页的规则取小）。 */
        var coverCap = Int.MAX_VALUE

        /** 清单字号按这么多条算；null 表示按实际显示的条数（与首页相同）。 */
        var checklistSizeBasis: Int? = null

        init {
            setShouldThingsAnimWhenAppearing(false)
        }

        override fun getThingCardContentMaxLines(thing: Thing, fullSpan: Boolean): Int =
            min(super.getThingCardContentMaxLines(thing, fullSpan), textCap)

        override fun getThingCardChecklistMaxItemCount(thing: Thing, fullSpan: Boolean): Int =
            min(super.getThingCardChecklistMaxItemCount(thing, fullSpan), textCap)

        override fun onThingCardChecklistWillBind(
            holder: BaseThingViewHolder, checklist: ThingCardChecklistView, thing: Thing
        ) {
            super.onThingCardChecklistWillBind(holder, checklist, thing)
            checklist.sizeBasisItemCount = checklistSizeBasis
        }

        override fun getThingCardForegroundThumbnailHeight(thing: Thing, imageW: Int): Int =
            min(super.getThingCardForegroundThumbnailHeight(thing, imageW), coverCap)

        override fun getThingCardMediaBackgroundMaxHeight(thing: Thing): Int =
            min(super.getThingCardMediaBackgroundMaxHeight(thing), coverCap)

        /** 侧栏封面按当前的文字高度重新排一次；返回是否改动了。 */
        fun resyncSideImage(holder: BaseThingViewHolder): Boolean = resyncSideImageProjection(holder, thing)

        override fun getCurrentMode(): Int = ModeManager.NORMAL

        override fun getThings(): MutableList<Thing?> = things

        override fun getEntries(): List<ThingListEntry>? = null

        override fun getStickyThingParentFolderBackground(thing: Thing): ThingBackground? {
            val folderId = thing.folderId ?: return null
            return folders?.getFolderById(folderId)?.getBackground()
        }

        override fun isThingEffectivelyPrivate(thing: Thing): Boolean = thing.isPrivate() && !revealPrivate

        /** 首页上是不是通栏卡片（首页适配器自己的判断）。 */
        fun isFullSpan(): Boolean = isFullSpanThingCard(thing)
    }

    /** 只有一个文件夹的首页适配器：绑定走首页的文件夹分支，私密的一律显示内容。 */
    private class SingleFolderAdapter(
        app: App,
        entry: ThingListEntry.FolderEntry
    ) : ThingsAdapter(app, null) {

        private val entries: List<ThingListEntry> = listOf(entry)

        init {
            setShouldThingsAnimWhenAppearing(false)
        }

        override fun getCurrentMode(): Int = ModeManager.NORMAL

        override fun getEntries(): List<ThingListEntry> = entries

        override fun shouldShowFolderPrivateContent(): Boolean = true

        override fun isFolderRevealedByAuth(folderId: Long): Boolean = true

        override fun isThingRevealedByAuth(thingId: Long): Boolean = true
    }

    /**
     * 卡片在挂上窗口之前就要量好（舞台按量出的尺寸排版、缩放）。脱离窗口的视图树解析不了「继承」的布局方向
     * （根视图没有上一级），相对方向的复合图标（已揭示私密记事标题前的开锁）就落不到左侧，量出来的标题宽度不含
     * 图标。挂上窗口之后图标出现，若标题没有被重新测量、或取回了测量缓存，文字就整体右移、末尾被裁掉，而且因为
     * 排版时以为放得下而没有省略号（2026-09-28 用户截图；三星上实测脱离时量出 249 px、需要 314 px）。给根视图
     * 一个与应用相同的明确方向，测量时就能解析。
     */
    private fun resolveDirectionForDetachedMeasure(view: View) {
        view.layoutDirection = view.resources.configuration.layoutDirection
    }

    /** 抬起的通栏卡片最宽这么宽（dp），与粒子动画设置页的示例卡片同一个上限。 */
    private const val LIFT_MAX_WIDTH_DP = 640

    /** 抬起的普通卡片是通栏宽的这么多（手机、平板相同）。 */
    private const val LIFT_NORMAL_RATIO = .75f

    /** 放不下时正文最少留这么多行、清单最少留这么多条。 */
    private const val MIN_KEPT = 3

    private const val TAG = "TapLift"
}

/**
 * 装卡片的容器：卡片只是展示，触摸一律拦下、也不消费（首页适配器给卡片装的触摸监听需要列表的
 * 回调，这里没有），落到下面的视图上。
 *
 * [decoration] 画在卡片之上（例如叠放的条数标记）；它属于这个容器的显示内容，截图时一起截进去。
 */
internal class TapCardHolderLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var decoration: ((Canvas) -> Unit)? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    override fun onTouchEvent(event: MotionEvent): Boolean = false

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        decoration?.invoke(canvas)
    }
}
