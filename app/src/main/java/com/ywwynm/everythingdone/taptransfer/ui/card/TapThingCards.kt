package com.ywwynm.everythingdone.taptransfer.ui.card

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.adapters.ThingsAdapter
import com.ywwynm.everythingdone.database.ThingFolderDAO
import com.ywwynm.everythingdone.managers.ModeManager
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.model.ThingListEntry
import com.ywwynm.everythingdone.utils.DisplayUtil
import kotlin.math.min

/**
 * 用首页的记事卡片渲染一条记事：布局、内容行数、清单条数、附件与背景的规则与首页逐项相同
 * （直接继承首页的适配器，只把数据换成这一条），宽度由调用方给。
 *
 * 分享界面抬升的卡片、接收时拼出来的卡片都用它，看起来就是首页上的那张卡片。
 */
internal object TapThingCards {

    /** 分享界面卡片的最大宽度：手机上接近满宽，平板上不再跟着屏幕变宽。 */
    private const val SHARE_MAX_WIDTH_DP = 400f
    private const val SHARE_SIDE_MARGIN_DP = 24f

    /** 分享界面卡片的宽度：屏幕宽度减去两侧留白，且不超过 [SHARE_MAX_WIDTH_DP]。 */
    fun shareCardWidth(activity: Activity): Int {
        val density = activity.resources.displayMetrics.density
        val screen = DisplayUtil.getScreenSize(activity).x
        return min(screen - (2f * SHARE_SIDE_MARGIN_DP * density).toInt(), (SHARE_MAX_WIDTH_DP * density).toInt())
    }

    /**
     * 渲染一张卡片。返回的视图已绑定、可直接加进布局，宽度固定为 [widthPx]、高度随内容。
     *
     * @param revealPrivate 私密记事是否显示内容。分享方在分享前已经验证过身份，显示；
     *        接收方按首页的规则，不显示。
     */
    fun create(activity: Activity, thing: Thing, widthPx: Int, revealPrivate: Boolean): View {
        val adapter = SingleThingAdapter(activity.application as App, thing, revealPrivate)
        adapter.setCardWidth(widthPx)
        adapter.setFullSpanCardWidth(widthPx)
        adapter.setShouldShowPrivateContent(revealPrivate)
        // 动图、视频封面只取静态的一帧：卡片会被截成粒子
        adapter.setAnimatedPlaybackEnabled(false)
        val parent = FrameLayout(activity)
        val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0))
        // 首页适配器绑定时按瀑布流的布局参数设外边距，先给它一份，绑完再换成普通的
        holder.itemView.layoutParams = StaggeredGridLayoutManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        adapter.onBindViewHolder(holder, 0)
        val card = holder.itemView
        card.layoutParams = FrameLayout.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        return card
    }

    private class SingleThingAdapter(
        app: App,
        private val thing: Thing,
        private val revealPrivate: Boolean
    ) : ThingsAdapter(app, null) {

        private val things: MutableList<Thing?> = mutableListOf(thing)
        private val folders = ThingFolderDAO.getInstance(app)

        init {
            setShouldThingsAnimWhenAppearing(false)
        }

        override fun getCurrentMode(): Int = ModeManager.NORMAL

        override fun getThings(): MutableList<Thing?> = things

        override fun getEntries(): List<ThingListEntry>? = null

        override fun getStickyThingParentFolderBackground(thing: Thing): ThingBackground? {
            val folderId = thing.folderId ?: return null
            return folders?.getFolderById(folderId)?.getBackground()
        }

        override fun isThingEffectivelyPrivate(thing: Thing): Boolean = thing.isPrivate() && !revealPrivate
    }
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
