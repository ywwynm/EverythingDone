@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.adapters

import android.animation.ObjectAnimator
import android.content.res.ColorStateList
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.text.TextUtils
import android.util.LruCache
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.FrequentSettings
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.appwidgets.AppWidgetHelper
import com.ywwynm.everythingdone.helpers.CheckListHelper
import com.ywwynm.everythingdone.helpers.DebugFileLogger
import com.ywwynm.everythingdone.helpers.ThingCardMediaHelper
import com.ywwynm.everythingdone.managers.ModeManager
import com.ywwynm.everythingdone.managers.ThingManager
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.model.ThingFolder
import com.ywwynm.everythingdone.model.ThingFolderCardPresentation
import com.ywwynm.everythingdone.model.ThingListEntry
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.SystemNotificationUtil
import com.ywwynm.everythingdone.views.GradientRippleDrawable
import com.ywwynm.everythingdone.views.InterceptTouchCardView
import java.util.Locale

/**
 * Created by ywwynm on 2015/5/28.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Adapter for things.
 */
open class ThingsAdapter(app: App?, listener: OnItemTouchedListener?) : BaseThingsAdapter(app) {

    private var mApp: App? = app
    private var mThingManager: ThingManager? = ThingManager.getInstance(mApp)

    private var mOnItemTouchedListener: OnItemTouchedListener? = listener

    // decrease memory usage as much as possible.
    private var mOnTouchListener: View.OnTouchListener =
        View.OnTouchListener { v, event ->
            animateCardOnTouch(v, event)
            mOnItemTouchedListener!!.onItemTouch(v, event)
        }

    private var mModeManager: ModeManager? = null

    private var mShouldThingsAnimWhenAppearing: Boolean = true

    private var mAnimHandler: Handler = Handler()
    private var mActivityHeaderSpacerHeightPx: Int = 0

    interface OnNewItemBoundListener {
        fun onNewItemBound(listPosition: Int, holder: BaseThingViewHolder?)
    }

    private var mArmedNewItemListPosition: Int = -1
    private var mArmedNewItemId: Long = -1L
    private var mArmedNewItemListener: OnNewItemBoundListener? = null

    open fun setModeManager(modeManager: ModeManager?) {
        mModeManager = modeManager
    }

    open fun setActivityHeaderSpacerHeightPx(heightPx: Int) {
        val sanitizedHeight = heightPx.coerceAtLeast(0)
        if (mActivityHeaderSpacerHeightPx == sanitizedHeight) return
        mActivityHeaderSpacerHeightPx = sanitizedHeight
        if (itemCount > 0) {
            notifyItemChanged(0)
        }
    }

    override fun getCurrentMode(): Int = mModeManager?.getCurrentMode() ?: ModeManager.NORMAL

    override fun getThings(): List<Thing?>? = mThingManager!!.getThings()

    protected open fun getEntries(): List<ThingListEntry>? = mThingManager!!.getThingListEntries()

    override fun getStickyThingParentFolderBackground(thing: Thing): ThingBackground? {
        val folderId = thing.folderId ?: return null
        return mThingManager!!.getFolderById(folderId)?.getBackground()
    }

    protected override fun getThingAt(position: Int): Thing? {
        val entry = getEntries()?.getOrNull(position)
        if (entry is ThingListEntry.ThingEntry) return entry.thing
        return getThings()?.getOrNull(position)
    }

    protected override fun getEntryCount(): Int {
        return getEntries()?.size ?: (getThings()?.size ?: 0)
    }

    override fun shouldDimUnselectedContent(currentMode: Int): Boolean {
        return currentMode == ModeManager.SELECTING || currentMode == ModeManager.MOVING
    }

    override fun isThingEffectivelyPrivate(thing: Thing): Boolean {
        return (thing.isPrivate() || mThingManager!!.isCurrentFolderEffectivelyPrivate())
            && !mThingManager!!.isCurrentFolderPrivacyAuthenticated()
            && !isThingRevealedByAuth(thing.id)
    }

    // 外观编辑期间，对正在编辑的那个文件夹展示真实外观（含缩略图/预览）；其余文件夹仍走遮蔽后的
    // effective 外观，私密文件夹在普通列表里照常只显示锁。面板关闭时由调用方清空该 id。
    private var mAppearanceRevealFolderId: Long? = null

    open fun setAppearanceRevealFolderId(id: Long?) {
        mAppearanceRevealFolderId = id
    }

    private class CachedFolderThumbnails(val signature: String, val view: View)

    // 诊断用：一次取缩略图树的结果与各段耗时。cacheState：HIT / MISS_NEW（首次无缓存）/
    // MISS_SIG（签名不符）/ MISS_DETACHED（缓存树仍挂载在别处、未能复用）。
    private class FolderThumbnailObtain(
        val view: View,
        val cacheState: String,
        val signatureNanos: Long,
        val buildNanos: Long
    )

    // 大文件夹缩略图整棵预览树缓存：键为文件夹 id，值带一份内容签名。命中（签名一致且该树当前未挂载）
    // 时原样复用已构建好的缩略图树，跳过整棵 card_thing 的重复 inflate / 重绑 / 重缩放——这正是滑动到
    // 大文件夹卡顿、以及 notifyDataSetChanged（全表多处）全量重绑卡顿的主因。签名取保守口径：任何影响
    // 预览渲染的字段变化都会改变签名而触发重建，宁可多重建，也绝不复用出过期内容。LruCache 自动淘汰
    // 最久未用项，限制内存占用。
    private val mFolderThumbnailCache =
        LruCache<Long, CachedFolderThumbnails>(FOLDER_THUMBNAIL_CACHE_MAX_ENTRIES)

    // 上次绑定时所见的"缩略图缓存失效令牌"。loadThings/searchThings 每次都把 entry 列表重建为新实例，
    // 据此检测"数据是否 reload 过"：令牌引用变化即清空缩略图缓存，作为内容签名之外的兜底，确保任何经
    // reload 反映出来的更改（含习惯/目标打卡这类不改记事字段、不 bump updateTime 的更改）都重建。滚动与
    // 非 reload 的刷新（模式切换、选择）不替换列表实例，缓存照常命中复用。
    private var mLastFolderThumbnailCacheToken: Any? = null

    /**
     * 缩略图整树缓存的失效令牌来源：引用变化即视为数据 reload、清空缓存。默认取当前 entry 列表实例
     * （loadThings/searchThings 每次重建为新实例）。Widget 配置等不走主列表 entry、而是经
     * bindFolderHolder→bindFolderCard 直接复用文件夹卡渲染的子类，应覆写为各自真实的数据源实例。
     */
    protected open fun folderThumbnailCacheToken(): Any? = getEntries()

    private fun invalidateFolderThumbnailCacheIfReloaded() {
        val token = folderThumbnailCacheToken()
        if (token !== mLastFolderThumbnailCacheToken) {
            val hadPrev = mLastFolderThumbnailCacheToken != null
            val cleared = mFolderThumbnailCache.size()
            mLastFolderThumbnailCacheToken = token
            mFolderThumbnailCache.evictAll()
            if (DEBUG_FOLDER_THUMBNAIL_PERF && hadPrev) {
                DebugFileLogger.log(
                    FOLDER_THUMBNAIL_PERF_LOG,
                    "evictAll: 数据列表实例变化（reload），清空缓存 cleared=$cleared 条",
                    "[FolderThumb]"
                )
            }
        }
    }

    /**
     * 该私密文件夹的内容是否应揭示（按真实外观显示内容/预览，而非上锁）：正在外观编辑、全局显示
     * 私密、或本会话已认证过该文件夹时为真。一旦本会话认证过某私密文件夹，其卡片即按真实外观显示
     * 内容（含大文件夹缩略图），与"访问即信任"一致；非私密文件夹永远为真（effectivePrivate 才遮蔽）。
     */
    private fun shouldRevealFolderContent(folder: ThingFolder): Boolean {
        return folder.id == mAppearanceRevealFolderId ||
            shouldShowPrivateContent() ||
            isFolderRevealedByAuth(folder.id)
    }

    /**
     * 会话级"已认证→揭示"的可覆写接缝。主 app 走单例 ThingManager 的会话认证集；widget 配置等
     * 从 app 外启动、维护各自本地认证会话的子类应覆写为本地语义（决策 4：app 外入口不共享主会话
     * 认证，避免主 app 认证过的私密内容泄露到桌面配置界面）。
     */
    protected open fun isFolderRevealedByAuth(folderId: Long): Boolean {
        return mThingManager!!.isFolderPrivacyAuthenticated(folderId)
    }

    protected open fun isThingRevealedByAuth(thingId: Long): Boolean {
        return mThingManager!!.isThingPrivacyAuthenticated(thingId)
    }

    private fun presentationFor(folder: ThingFolder): ThingFolderCardPresentation {
        // effectiveCardPresentation 仅在 folder 自身私密时才与真实值不同；非自身私密的文件夹直接返回
        // 真实外观，省去 shouldRevealFolderContent 里 isFolderPrivacyAuthenticated 的祖先路径遍历
        // （getFolderPath 是逐级 DB 查询，presentationFor 每次绑卡片会调多次）。
        if (!folder.isPrivate) return folder.cardPresentation
        return if (shouldRevealFolderContent(folder)) {
            folder.cardPresentation
        } else {
            folder.effectiveCardPresentation()
        }
    }

    /**
     * 该文件夹当前是否以“大文件夹（缩略图）”形态显示——揭示感知：本会话已认证（揭示）的私密大
     * 文件夹算大文件夹。拖拽悬停据此选“加入大/小文件夹”的动画，必须用揭示感知的 [presentationFor]，
     * 否则揭示态大文件夹会被 effectiveCardPresentation 当成小文件夹、播放错误的小文件夹动画。
     */
    open fun isFolderShownAsThumbnails(folder: ThingFolder): Boolean {
        return presentationFor(folder).mode == ThingFolderCardPresentation.MODE_THUMBNAILS
    }

    open fun shouldThingsAnimWhenAppearing(): Boolean = mShouldThingsAnimWhenAppearing

    open fun setShouldThingsAnimWhenAppearing(shouldThingsAnimWhenAppearing: Boolean) {
        mShouldThingsAnimWhenAppearing = shouldThingsAnimWhenAppearing
    }

    /**
     * Arm a one-shot animation for a freshly created thing.
     */
    open fun armNewItemAnimation(
        listPosition: Int,
        thingId: Long,
        listener: OnNewItemBoundListener?
    ) {
        mArmedNewItemListPosition = listPosition
        mArmedNewItemId       = thingId
        mArmedNewItemListener = listener
    }

    open fun clearArmedNewItemAnimation() {
        mArmedNewItemListPosition = -1
        mArmedNewItemId       = -1L
        mArmedNewItemListener = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseThingViewHolder {
        return ThingViewHolder(mInflater!!.inflate(R.layout.card_thing, parent, false))
    }

    override fun getItemViewType(position: Int): Int {
        val entry = getEntries()?.getOrNull(position)
        if (entry is ThingListEntry.FolderEntry) return VIEW_TYPE_THING_FOLDER
        return super.getItemViewType(position)
    }

    override fun onViewRecycled(holder: BaseThingViewHolder) {
        super.onViewRecycled(holder)
        // holder 被回收进 RecycledViewPool 时，把它挂着的缩略图树从视图层级摘下（树本身仍按 folderId 留在
        // mFolderThumbnailCache 中）。否则该树的 parent 仍指向这个待命 holder，等同一文件夹换到另一个 holder
        // 重绑时，会因 view.parent != null 无法复用（MISS_DETACHED）而重建整棵树，使缓存形同虚设。摘下后
        // parent 归零，任意 holder 都能复用缓存树。对非文件夹卡是 no-op（其 llTextContent 无缩略图标记视图）。
        removeFolderThumbnailViews(holder)
    }

    override fun onBindViewHolder(holder: BaseThingViewHolder, position: Int) {
        val entry = getEntries()?.getOrNull(position)
        holder.itemView.setTag(
            R.id.tag_thing_card_bound_stable_id,
            entry?.stableId ?: getThingAt(position)?.id ?: Long.MIN_VALUE
        )
        holder.itemView.visibility = View.VISIBLE
        holder.itemView.alpha = 1.0f
        holder.itemView.scaleX = 1.0f
        holder.itemView.scaleY = 1.0f
        holder.itemView.translationZ = 0.0f

        if (entry is ThingListEntry.FolderEntry) {
            bindFolderCard(holder, entry)
            if (mShouldThingsAnimWhenAppearing) {
                playAppearingAnimation(holder.cv!!, position)
            }
            return
        }

        removeFolderDynamicViews(holder)
        holder.cv?.setTag(R.id.tag_thing_folder_thumbnail_surface, false)
        holder.cv?.maxCardElevation = mApp!!.resources.getDimension(R.dimen.thing_card_dragging_elevation)
        applyDefaultCardOutline(holder.cv)
        val thing = getThingAt(position)!!
        distinguishHeaderAndOthers(thing, holder.cv)
        super.onBindViewHolder(holder, position)

        val armed = isArmedFor(position)

        if (!armed) {
            if (getCurrentMode() == ModeManager.NORMAL
                && holder.llContent!!.alpha != 1f
            ) {
                holder.llContent.alpha = 1f
            }
            if (holder.cv!!.alpha != 1f)        holder.cv.alpha = 1f
        }

        if (mShouldThingsAnimWhenAppearing && !armed) {
            playAppearingAnimation(holder.cv!!, position)
        }

        maybeTriggerArmedNewItemAnimation(holder, position)
    }

    private fun isArmedFor(listPosition: Int): Boolean {
        if (mArmedNewItemListener == null) return false
        if (listPosition != mArmedNewItemListPosition) return false
        if (mArmedNewItemId == -1L) return true
        return getThingAt(listPosition)?.id == mArmedNewItemId
    }

    private fun maybeTriggerArmedNewItemAnimation(
        holder: BaseThingViewHolder,
        listPosition: Int
    ) {
        if (!isArmedFor(listPosition)) return

        val listener = mArmedNewItemListener
        val firedListPosition = listPosition
        clearArmedNewItemAnimation()

        holder.cv!!.visibility = View.INVISIBLE
        holder.llContent!!.alpha = 1f
        holder.cv.alpha = 1f

        holder.cv.post(object : Runnable {
            override fun run() {
                if (holder.cv.width == 0 || holder.cv.height == 0) {
                    holder.cv.post(this)
                    return
                }
                listener!!.onNewItemBound(firedListPosition, holder)
            }
        })
    }

    override fun isFullSpanThingCard(thing: Thing): Boolean {
        return thing.type != Thing.HEADER
                && thing.type < Thing.NOTIFICATION_UNDERWAY
                && thing.thingCardAppearance.spanMode == Thing.THING_CARD_SPAN_FULL
    }

    private fun distinguishHeaderAndOthers(thing: Thing, cv: CardView?) {
        val header = thing.type == Thing.HEADER
        val mX = mApp!!.resources.getDimensionPixelSize(R.dimen.thing_card_outer_spacing)
        val mY = if (header) 0 else mX

        val height: Int = if (header) {
            getActivityHeaderSpacerHeight()
        } else {
            StaggeredGridLayoutManager.LayoutParams.WRAP_CONTENT
        }

        cv!!.visibility = if (header) View.INVISIBLE else View.VISIBLE
        val lp = cv.layoutParams as StaggeredGridLayoutManager.LayoutParams
        lp.height = height
        lp.setMargins(mX, mY, mX, mY)
        lp.isFullSpan = header || isFullSpanThingCard(thing)
    }

    private fun getActivityHeaderSpacerHeight(): Int {
        if (App.isSearching) {
            // 搜索态无 Activity Header：让首个结果卡片到 actionbar 底的间距 = 卡片间距(16dp)。卡片自带
            // thing_card_outer_spacing(8dp) 上边距，这里的 spacer 再补一个 8dp，合计 16dp——与卡片之间、
            // 以及非搜索折叠态首卡到 actionbar 的间距一致（后者由 ActivityHeader.TITLE_DOCK_RESIDUAL_DP 保证）。
            return mApp!!.resources.getDimensionPixelSize(R.dimen.thing_card_outer_spacing)
        }
        val defaultHeight = (mDensity * 102).toInt()
        return mActivityHeaderSpacerHeightPx.coerceAtLeast(defaultHeight)
    }

    private fun distinguishFolder(folder: ThingFolder, cv: CardView?) {
        val mX = mApp!!.resources.getDimensionPixelSize(R.dimen.thing_card_outer_spacing)
        val lp = cv!!.layoutParams as StaggeredGridLayoutManager.LayoutParams
        lp.height = StaggeredGridLayoutManager.LayoutParams.WRAP_CONTENT
        lp.setMargins(mX, mX, mX, mX)
        lp.isFullSpan =
            presentationFor(folder).spanMode == ThingFolderCardPresentation.SPAN_FULL
        cv.visibility = View.VISIBLE
    }

    protected fun bindFolderCard(
        holder: BaseThingViewHolder,
        entry: ThingListEntry.FolderEntry
    ) {
        // reload 兜底放在 bindFolderCard 入口而非 onBindViewHolder：Widget 配置经
        // bindFolderHolder→bindFolderCard 直接复用文件夹卡渲染、不走 onBindViewHolder，此处确保该路径
        // 也能触发缓存失效。
        invalidateFolderThumbnailCacheIfReloaded()
        val folder = entry.folder
        distinguishFolder(folder, holder.cv)
        resetFolderCardHolder(holder)
        bindFolderCardSurface(holder, folder)
        bindFolderCardContent(holder, entry)
        bindFolderSelectionAppearance(holder, folder)
    }

    private fun resetFolderCardHolder(holder: BaseThingViewHolder) {
        removeFolderDynamicViews(holder)
        holder.cv!!.animate().cancel()
        holder.cv.scaleX = 1.0f
        holder.cv.scaleY = 1.0f
        holder.cv.maxCardElevation = mApp!!.resources.getDimension(R.dimen.thing_card_dragging_elevation)
        holder.cv.cardElevation = mApp!!.resources.getDimension(R.dimen.thing_card_normal_elevation)
        holder.cv.setTag(R.id.tag_thing_folder_thumbnail_surface, false)
        applyDefaultCardOutline(holder.cv)
        holder.cv.setShouldInterceptTouchEvent(false)

        holder.ivMediaBackground!!.visibility = View.GONE
        holder.vMediaBackgroundMask!!.visibility = View.GONE
        holder.flImageAttachment!!.visibility = View.GONE
        holder.ivImageAttachment!!.setImageDrawable(null)
        holder.tvImageCount!!.visibility = View.GONE
        holder.pbLoading!!.visibility = View.GONE
        holder.vImageCover!!.visibility = View.GONE
        holder.tvContent!!.visibility = View.VISIBLE
        holder.rvChecklist!!.visibility = View.GONE
        resetFolderBottomSpacing(holder)
        holder.llMediaCount!!.visibility = View.GONE
        holder.llInlineMediaAttachment!!.visibility = View.GONE
        holder.llAudioAttachment!!.visibility = View.GONE
        holder.rlReminder!!.visibility = View.GONE
        holder.rlHabit!!.visibility = View.GONE
        holder.flDoing!!.visibility = View.GONE
        holder.tvTitle!!.visibility = View.GONE
        holder.ivPrivateThing!!.visibility = View.GONE

        holder.llContent!!.orientation = android.widget.LinearLayout.VERTICAL
        holder.llContent.alpha = 1.0f
        holder.llContent.minimumWidth = 0
        holder.llContent.minimumHeight = 0
        holder.llContent.background = null

        val contentLp = holder.llContent.layoutParams
        contentLp.width = ViewGroup.LayoutParams.WRAP_CONTENT
        contentLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        holder.llContent.layoutParams = contentLp

        val textLp = holder.llTextContent!!.layoutParams as android.widget.LinearLayout.LayoutParams
        textLp.width = ViewGroup.LayoutParams.WRAP_CONTENT
        textLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        textLp.weight = 0f
        holder.llTextContent.layoutParams = textLp
        holder.llTextContent.minimumHeight = 0
    }

    private fun resetFolderBottomSpacing(holder: BaseThingViewHolder) {
        val statusSpacer = holder.vBottomStatusSpacer ?: return
        val statusLp = statusSpacer.layoutParams as LinearLayout.LayoutParams
        statusSpacer.visibility = View.GONE
        statusLp.height = 0
        statusLp.weight = 0f
        statusSpacer.layoutParams = statusLp

        val paddingBottom = holder.vPaddingBottom ?: return
        val paddingLp = paddingBottom.layoutParams as LinearLayout.LayoutParams
        paddingBottom.visibility = View.VISIBLE
        paddingLp.height = (mDensity * 16).toInt()
        paddingLp.weight = 0f
        paddingBottom.layoutParams = paddingLp
    }

    private fun bindFolderCardSurface(holder: BaseThingViewHolder, folder: ThingFolder) {
        val background = folder.getBackground()
        val presentation = presentationFor(folder)
        val thumbnailMode =
            presentation.mode == ThingFolderCardPresentation.MODE_THUMBNAILS
        if (thumbnailMode) {
            applyThumbnailFolderCardSurface(holder, background, folder.getColor())
        } else {
            holder.cv!!.setTag(R.id.tag_thing_folder_thumbnail_surface, false)
            holder.cv.maxCardElevation = mApp!!.resources.getDimension(R.dimen.thing_card_dragging_elevation)
            holder.cv.cardElevation = mApp!!.resources.getDimension(R.dimen.thing_card_normal_elevation)
            applyDefaultCardOutline(holder.cv)
            holder.llContent!!.background = null
            BackgroundUtil.applyCardBackground(holder.cv, background)
        }

        if (thumbnailMode) {
            // 大文件夹缩略图卡片：触摸 ripple 用该文件夹自身颜色（纯色或渐变）。
            val rippleBackground = background ?: ThingBackground.pure(folder.getColor())
            val radius = mApp!!.resources.getDimension(R.dimen.thing_card_corner_radius)
            holder.cv!!.foreground = GradientRippleDrawable(
                rippleBackground,
                shapeOval = false,
                cornerRadiusPx = radius
            )
        } else {
            val baseColor = background?.representativeColor() ?: folder.getColor()
            holder.cv!!.foreground = ContextCompat.getDrawable(
                mApp!!,
                if (BackgroundUtil.isLight(baseColor))
                    R.drawable.selectable_item_background
                else
                    R.drawable.selectable_item_background_light
            )
        }
    }

    private fun bindFolderSelectionAppearance(
        holder: BaseThingViewHolder,
        folder: ThingFolder
    ) {
        val card = holder.cv ?: return
        val selected = folder.isSelected()
        val currentMode = getCurrentMode()
        val background = folder.getBackground()
        val presentation = presentationFor(folder)
        val thumbnailMode =
            presentation.mode == ThingFolderCardPresentation.MODE_THUMBNAILS
        val dimUnselected = shouldDimUnselectedContent(currentMode) && !selected
        holder.llContent!!.alpha = if (dimUnselected) 0.42f else 1.0f

        if (!thumbnailMode) {
            BackgroundUtil.applyCardBackground(
                card,
                if (dimUnselected) lightVariant(background) else background
            )
        }

        if (currentMode == ModeManager.MOVING && selected) {
            scheduleMovingCardScaleRecoveryIfReleased(card, "folder")
            ObjectAnimator.ofFloat(card, "scaleX", 1.11f).setDuration(96).start()
            ObjectAnimator.ofFloat(card, "scaleY", 1.11f).setDuration(96).start()
            ObjectAnimator.ofFloat(
                card,
                "cardElevation",
                mApp!!.resources.getDimension(R.dimen.thing_card_dragging_elevation)
            ).setDuration(96).start()
        } else {
            val oldToken = card.getTag(R.id.tag_thing_card_moving_scale_recovery_token)
            if (oldToken != null || card.scaleX != 1.0f || card.scaleY != 1.0f) {
                logCardScaleRecoveryDebug(
                    "folder-normal view=${System.identityHashCode(card)} " +
                        "oldToken=${System.identityHashCode(oldToken)} " +
                        "mode=$currentMode selected=$selected " +
                        "finger=${card.getTag(R.id.tag_thing_card_finger_down)} " +
                        "drag=${card.getTag(R.id.tag_thing_card_drag_active)} " +
                        "scale=${card.scaleX}/${card.scaleY}"
                )
            }
            card.setTag(R.id.tag_thing_card_moving_scale_recovery_token, null)
            card.animate().cancel()
            card.scaleX = 1.0f
            card.scaleY = 1.0f
            card.cardElevation = mApp!!.resources.getDimension(R.dimen.thing_card_normal_elevation)
        }
    }

    private fun applyThumbnailFolderCardSurface(
        holder: BaseThingViewHolder,
        background: ThingBackground?,
        fallbackColor: Int
    ) {
        val radius = mApp!!.resources.getDimension(R.dimen.thing_card_corner_radius)
        val cardBackground = getThumbnailFolderSurfaceBackground(background, fallbackColor)
        holder.cv!!.setTag(R.id.tag_thing_folder_thumbnail_surface, true)
        BackgroundUtil.applyCardBackground(holder.cv, cardBackground)
        (holder.cv.background as? GradientDrawable)?.cornerRadius = radius
        holder.cv.maxCardElevation = mApp!!.resources.getDimension(R.dimen.thing_card_dragging_elevation)
        holder.cv.cardElevation = mApp!!.resources.getDimension(R.dimen.thing_card_normal_elevation)
        applyRoundedCardOutline(holder.cv, radius)

        val strokeBackground = background ?: ThingBackground.pure(fallbackColor)
        val outline = BackgroundUtil.GradientStrokeDrawable(
            strokeBackground,
            radius,
            (mDensity * 1.5f).coerceAtLeast(1f)
        )
        holder.llContent!!.background = outline
    }

    private fun getThumbnailFolderSurfaceBackground(
        background: ThingBackground?,
        fallbackColor: Int
    ): ThingBackground {
        val listBackgroundColor = ContextCompat.getColor(mApp!!, R.color.bg_activity_things)
        return BackgroundUtil.mutedSurfaceBackground(
            background ?: ThingBackground.pure(fallbackColor),
            listBackgroundColor
        )
    }

    private fun applyDefaultCardOutline(card: CardView?) {
        card ?: return
        card.clipToOutline = true
        card.outlineProvider = ViewOutlineProvider.BACKGROUND
    }

    private fun applyRoundedCardOutline(card: CardView?, radius: Float) {
        card ?: return
        card.clipToOutline = true
        card.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
    }

    private fun bindFolderCardContent(
        holder: BaseThingViewHolder,
        entry: ThingListEntry.FolderEntry
    ) {
        val folder = entry.folder
        val background = folder.getBackground()
        // 私密文件夹：本会话已认证（含正在外观编辑、全局显示私密）时放行内容遮蔽，卡片按真实外观
        // 显示内容/预览；未认证才上锁。揭示判据统一收口到 shouldRevealFolderContent。
        val hiddenPrivate = entry.effectivePrivate && !shouldShowFolderPrivateContent() &&
            !shouldRevealFolderContent(folder)
        val presentation = presentationFor(folder)
        val thumbnailMode =
            presentation.mode == ThingFolderCardPresentation.MODE_THUMBNAILS
        val baseColor = if (thumbnailMode) {
            getThumbnailFolderSurfaceBackground(background, folder.getColor()).representativeColor()
        } else {
            background?.representativeColor() ?: folder.getColor()
        }
        bindFolderCardHeader(
            holder,
            folder.title,
            // A folder that is itself trashed (a Trashed Thing Folder) uses an
            // icon with a delete mark, distinguishing it from a Projection Folder
            // that only contains trashed descendants in the recycle bin.
            if (folder.isDeleted()) R.drawable.ic_thing_folder_deleted else R.drawable.ic_thing_folder,
            baseColor,
            if (thumbnailMode) background else null,
            folder = folder
        )

        if (hiddenPrivate) {
            // 文件夹被遮蔽（重新上锁）时丢弃其已渲染的缩略图树，避免私密预览内容滞留缓存。
            mFolderThumbnailCache.remove(folder.id)
            bindFolderPrivateLock(holder, baseColor)
        } else {
            bindFolderCardCount(holder, entry, baseColor)

            if (presentation.mode == ThingFolderCardPresentation.MODE_THUMBNAILS) {
                bindFolderThumbnails(holder, entry, baseColor)
            }
        }

        if (folder.isSticky()) {
            holder.ivStickyOngoing!!.visibility = View.VISIBLE
            holder.ivStickyOngoing.setImageResource(R.drawable.ic_sticky)
            holder.ivStickyOngoing.contentDescription = mApp!!.getString(R.string.sticky_thing)
            tintFolderStickyIcon(holder.ivStickyOngoing, folder, baseColor)
        } else {
            holder.ivStickyOngoing!!.visibility = View.GONE
        }
    }

    private fun tintFolderStickyIcon(
        icon: ImageView?,
        folder: ThingFolder,
        fallbackBaseColor: Int
    ) {
        icon ?: return
        if (folder.parentFolderId == null) {
            icon.setImageDrawable(
                BackgroundUtil.tintDrawable(
                    mApp!!.resources, icon.drawable, App.defaultAccentBackground
                )
            )
            return
        }
        val parentBackground = mThingManager!!.getFolderById(folder.parentFolderId!!)?.getBackground()
        if (parentBackground != null) {
            ImageViewCompat.setImageTintList(icon, null)
            icon.setImageDrawable(
                BackgroundUtil.tintDrawable(mApp!!.resources, icon.drawable, parentBackground)
            )
            return
        }
        tintCardIcon(icon, fallbackBaseColor)
    }

    private fun bindFolderPrivateLock(holder: BaseThingViewHolder, baseColor: Int) {
        val lock = holder.ivPrivateThing ?: return
        holder.tvContent!!.visibility = View.GONE
        holder.rvChecklist!!.visibility = View.GONE
        holder.llAudioAttachment!!.visibility = View.GONE
        holder.rlReminder!!.visibility = View.GONE
        holder.rlHabit!!.visibility = View.GONE
        resetFolderBottomSpacing(holder)
        lock.visibility = View.VISIBLE
        lock.setImageResource(R.drawable.ic_locked_big)
        lock.contentDescription = mApp!!.getString(R.string.private_thing_folder)
        ImageViewCompat.setImageTintList(
            lock,
            ColorStateList.valueOf(textColorSecondary(baseColor))
        )
        val lp = lock.layoutParams as LinearLayout.LayoutParams
        val iconSize = (mDensity * 48).toInt()
        lp.width = iconSize
        lp.height = iconSize
        lp.gravity = Gravity.CENTER_HORIZONTAL
        lp.topMargin = (mDensity * 16).toInt()
        lp.bottomMargin = 0
        lock.layoutParams = lp
    }

    protected open fun shouldShowFolderPrivateContent(): Boolean {
        return shouldShowPrivateContent() || mThingManager!!.isCurrentFolderPrivacyAuthenticated()
    }

    private fun bindFolderCardHeader(
        holder: BaseThingViewHolder,
        title: String,
        iconRes: Int,
        baseColor: Int,
        titleBackground: ThingBackground? = null,
        folder: ThingFolder? = null
    ) {
        val container = holder.llTextContent ?: return
        removeFolderHeaderViews(holder)

        val paddingSide = (mDensity * 16).toInt()
        val row = LinearLayout(mApp)
        row.tag = FOLDER_HEADER_VIEW_TAG
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.TOP
        row.setPadding(paddingSide, paddingSide, paddingSide, 0)

        val iconSize = (mDensity * 20).toInt()
        val icon = ImageView(mApp)
        icon.contentDescription = mApp!!.getString(R.string.thing_folder)
        icon.scaleType = ImageView.ScaleType.CENTER_INSIDE
        if (folder != null && folder.isPrivate && !folder.isDeleted() &&
            shouldRevealFolderContent(folder)
        ) {
            // 已鉴权（揭示）的私密文件夹：仿 Drawer，folder 图标内嵌"开锁"。未鉴权态卡片本就有
            // 居中大锁（bindFolderPrivateLock），不在图标里重复加锁，故此分支只处理揭示态开锁。
            // 图标填充沿用既有的"按形态着色"：大文件夹（titleBackground 非空）用文件夹自身背景色，
            // 小文件夹 / 缩略图里的预览（小文件夹形态，titleBackground 为空）用自适应墨色
            // （textColorPrimary，偏黑/白）。FolderIconDrawable 据填充色把锁画成对比色，开锁清晰可见。
            val iconBg = titleBackground ?: ThingBackground.pure(textColorPrimary(baseColor))
            ImageViewCompat.setImageTintList(icon, null)
            icon.setImageDrawable(
                com.ywwynm.everythingdone.views.DrawerNavigationView.FolderIconDrawable(
                    iconBg, privateFolder = true, authenticated = true
                )
            )
        } else {
            icon.setImageResource(iconRes)
            if (titleBackground != null) {
                ImageViewCompat.setImageTintList(icon, null)
                icon.setImageDrawable(
                    BackgroundUtil.tintDrawable(mApp!!.resources, icon.drawable, titleBackground)
                )
            } else {
                ImageViewCompat.setImageTintList(
                    icon,
                    ColorStateList.valueOf(textColorPrimary(baseColor))
                )
            }
        }
        row.addView(icon, LinearLayout.LayoutParams(iconSize, iconSize))

        val titleView = TextView(mApp)
        titleView.text = title
        titleView.maxLines = 2
        titleView.ellipsize = TextUtils.TruncateAt.END
        titleView.includeFontPadding = false
        titleView.textSize = 16f
        titleView.typeface = android.graphics.Typeface.DEFAULT_BOLD
        if (titleBackground != null) {
            BackgroundUtil.applyTextBackground(titleView, titleBackground)
        } else {
            titleView.setTextColor(textColorPrimary(baseColor))
        }
        val titleLp = LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        )
        titleLp.marginStart = (mDensity * 10).toInt()
        titleLp.topMargin = (mDensity * 1).toInt()
        row.addView(titleView, titleLp)

        container.addView(row, 0)
    }

    private fun bindFolderCardCount(
        holder: BaseThingViewHolder,
        entry: ThingListEntry.FolderEntry,
        baseColor: Int
    ) {
        val container = holder.llTextContent ?: return
        removeFolderCountViews(holder)

        holder.tvContent!!.visibility = View.GONE
        val countText = getFolderCardCountText(entry)
        if (countText.isEmpty()) return

        val paddingSide = (mDensity * 16).toInt()
        val countStartPadding = paddingSide + (mDensity * 2).toInt()
        val countView = TextView(mApp)
        countView.tag = FOLDER_COUNT_VIEW_TAG
        countView.setPadding(countStartPadding, (mDensity * 4).toInt(), paddingSide, 0)
        countView.textSize = 11f
        countView.maxLines = 1
        countView.ellipsize = TextUtils.TruncateAt.END
        countView.text = countText
        countView.setTextColor(textColorTertiary(baseColor))
        countView.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val insertIndex = (findFolderHeaderIndex(container) + 1)
            .coerceIn(0, container.childCount)
        container.addView(countView, insertIndex)
    }

    private fun getFolderCardCountText(entry: ThingListEntry.FolderEntry): String {
        val folderCount = entry.directFolderCount
        val thingCount = entry.recursiveThingCount
        return when {
            folderCount > 0 && thingCount > 0 -> mApp!!.getString(
                R.string.thing_folder_count_folders_things,
                folderCount,
                thingCount
            )
            folderCount > 0 -> mApp!!.getString(
                R.string.thing_folder_count_folders,
                folderCount
            )
            thingCount > 0 -> mApp!!.getString(R.string.thing_folder_count, thingCount)
            else -> ""
        }
    }

    /**
     * 取该文件夹的整棵缩略图预览树：签名命中且当前未挂载时原样复用缓存树，否则按需构建并写入缓存。
     * 仅在 [CachedFolderThumbnails.view] 的 parent 为空时才复用，避免在列表项动画的瞬时双 holder 期把
     * 仍挂载在另一个 holder 上的树“偷走”，造成闪动。
     */
    private fun obtainFolderThumbnailTree(
        folder: ThingFolder,
        previewEntries: List<ThingListEntry>,
        fullSpan: Boolean
    ): FolderThumbnailObtain {
        val tSig0 = System.nanoTime()
        val signature = folderThumbnailSignature(folder, previewEntries, fullSpan)
        val signatureNanos = System.nanoTime() - tSig0
        val cached = mFolderThumbnailCache.get(folder.id)
        if (cached != null && cached.signature == signature && cached.view.parent == null) {
            return FolderThumbnailObtain(cached.view, "HIT", signatureNanos, 0L)
        }
        val cacheState = when {
            cached == null -> "MISS_NEW"
            cached.signature != signature -> "MISS_SIG"
            else -> "MISS_DETACHED"
        }
        val tBuild0 = System.nanoTime()
        val view = if (fullSpan) {
            createFolderThumbnailMasonryView(previewEntries)
        } else {
            createFolderThumbnailListView(previewEntries)
        }
        val buildNanos = System.nanoTime() - tBuild0
        mFolderThumbnailCache.put(folder.id, CachedFolderThumbnails(signature, view))
        return FolderThumbnailObtain(view, cacheState, signatureNanos, buildNanos)
    }

    private fun logFolderThumbnailPerf(
        folder: ThingFolder,
        fullSpan: Boolean,
        count: Int,
        totalCount: Int,
        obtain: FolderThumbnailObtain,
        attachNanos: Long,
        bindNanos: Long,
        holder: BaseThingViewHolder
    ) {
        // 额外测一次该大卡内容的 measure 耗时：缓存命中省掉了 inflate/绑定/缩放，但 RecyclerView 仍要对这
        // 棵大子树（6~10 张完整记事卡）做 measure/layout——“缓存了却仍卡”往往卡在这里，而它不在 bind 耗时内。
        val measureNanos = debugMeasureFolderCard(holder, fullSpan)
        val span = if (fullSpan) "FULL/${folderThumbnailFullSpanColumnCount()}col" else "NORMAL"
        DebugFileLogger.log(
            FOLDER_THUMBNAIL_PERF_LOG,
            "id=${folder.id} \"${folder.title}\" $span entries=$count/$totalCount " +
                "scroll=${hostScrollStateName()} cache=${obtain.cacheState} " +
                "sig=${formatMillis(obtain.signatureNanos)} build=${formatMillis(obtain.buildNanos)} " +
                "attach=${formatMillis(attachNanos)} measure=${formatMillis(measureNanos)} " +
                "bind=${formatMillis(bindNanos)}",
            "[FolderThumb]"
        )
    }

    private fun formatMillis(nanos: Long): String =
        if (nanos < 0) "n/a" else String.format(Locale.US, "%.2fms", nanos / 1_000_000.0)

    private fun debugMeasureFolderCard(holder: BaseThingViewHolder, fullSpan: Boolean): Long {
        val cv = holder.cv ?: return -1L
        val width = if (fullSpan) getBoundFullSpanThingCardWidth() else getBoundNormalThingCardWidth()
        if (width <= 0) return -1L
        return try {
            val t0 = System.nanoTime()
            cv.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            System.nanoTime() - t0
        } catch (e: RuntimeException) {
            -1L
        }
    }

    /**
     * 缩略图预览树的内容签名。涵盖一切会改变这棵树渲染结果的输入：当前模式（决定预览卡是否可点击）、
     * 列数与列宽、是否全局显示私密、正在做的记事 id、文件夹自身版本与是否处于外观编辑揭示态，以及每个
     * 预览条目的身份与“渲染输入指纹”——记事取标题/正文/附件/背景/卡片外观，子文件夹取标题/背景/外观/
     * 计数/私密。直接对渲染输入取指纹（而非只挑 updateTime）是为了不依赖各写路径是否 bump updateTime：
     * 例如 renameFolder / updateFolderAppearance 改名改外观、习惯打卡等并不刷新 updateTime，但它们都会改变
     * 上述渲染输入，故内容一变签名即变。取保守口径：任一字段变化即触发重建，绝不复用出过期内容。
     */
    private fun folderThumbnailSignature(
        folder: ThingFolder,
        previewEntries: List<ThingListEntry>,
        fullSpan: Boolean
    ): String {
        val sb = StringBuilder(64)
        sb.append(getCurrentMode()).append('|')
        sb.append(if (fullSpan) folderThumbnailFullSpanColumnCount() else 1).append('|')
        sb.append(
            if (fullSpan) getFolderThumbnailFullPreviewWidth()
            else getFolderThumbnailNormalPreviewWidth()
        ).append('|')
        sb.append(if (shouldShowFolderPrivateContent()) 1 else 0).append('|')
        sb.append(App.getDoingThingId()).append('|')
        sb.append(folder.id).append(':').append(folder.updateTime).append('@')
        sb.append(if (mAppearanceRevealFolderId == folder.id) 1 else 0).append('|')
        for (e in previewEntries) {
            when (e) {
                is ThingListEntry.ThingEntry -> {
                    val t = e.thing
                    // 直接纳入会改变预览渲染的输入指纹（标题/正文/附件/背景/卡片外观），不依赖各写路径是否
                    // bump updateTime（习惯打卡等更改可能不刷新 updateTime），确保内容一变签名即变。
                    sb.append('T').append(t.id).append(',').append(t.type).append(',')
                        .append(t.state).append(',').append(t.updateTime).append(',')
                        .append(t.location).append(',')
                        .append(if (t.isPrivate()) 1 else 0).append(',')
                        .append(if (isThingRevealedByAuth(t.id)) 1 else 0).append(',')
                        .append(t.title?.hashCode() ?: 0).append(',')
                        .append(t.content?.hashCode() ?: 0).append(',')
                        .append(t.attachment?.hashCode() ?: 0).append(',')
                        .append(t.getBackground()?.toJson()?.hashCode() ?: 0).append(',')
                        .append(t.thingCardAppearance.toJson().hashCode())
                }
                is ThingListEntry.FolderEntry -> {
                    val f = e.folder
                    // 子文件夹 summary 预览：标题/背景指纹直接纳入。renameFolder / updateFolderAppearance
                    // 改名改外观后并不 bump folder.updateTime，只靠 updateTime 会漏刷新子文件夹缩略图。
                    sb.append('F').append(f.id).append(',').append(f.updateTime).append(',')
                        .append(f.location).append(',')
                        .append(presentationFor(f).spanMode).append(',')
                        .append(if (f.isPrivate) 1 else 0).append(',')
                        // 私密子文件夹的揭示态（本会话是否已认证揭示）必须入签名：否则后台返回清空认证后
                        // Activity 只 notifyDataSetChanged、entry 列表引用不变，曾揭示的子文件夹 summary 会
                        // 复用旧揭示态 View 造成泄露。与 presentationFor / getFolderThumbnailEntries 同源判定，
                        // 揭示态翻转即签名变、强制重建为锁态（在主列表与 Widget 配置各按其揭示语义判定）。
                        .append(if (!f.isPrivate || shouldRevealFolderContent(f)) 1 else 0).append(',')
                        .append(e.thumbnailEntryCount).append(',')
                        .append(f.title.hashCode()).append(',')
                        .append(f.getBackground()?.toJson()?.hashCode() ?: 0)
                }
            }
            sb.append(';')
        }
        return sb.toString()
    }

    private fun bindFolderThumbnails(
        holder: BaseThingViewHolder,
        entry: ThingListEntry.FolderEntry,
        baseColor: Int
    ) {
        val entries = getFolderThumbnailEntries(entry)
        val container = holder.llTextContent ?: return
        allowFolderThumbnailShadowOverflow(container)
        val presentation = presentationFor(entry.folder)
        val fullSpan = presentation.spanMode == ThingFolderCardPresentation.SPAN_FULL
        // Full-span masonry shows screen-aware count (phone fixed, tablet 2×columns);
        // normal-span list keeps the model's fixed preview limit.
        val displayLimit = if (fullSpan) {
            folderThumbnailFullSpanDisplayCount()
        } else {
            presentation.effectiveThumbnailPreviewLimit()
        }
        val count = entries.size.coerceAtMost(displayLimit)
        val insertStart = (findFolderCountIndex(container) + 1)
            .coerceIn(0, container.childCount)
        val tStart = System.nanoTime()
        var obtain: FolderThumbnailObtain? = null
        var attachNanos = 0L
        if (count > 0) {
            val previewEntries = entries.take(count)
            obtain = obtainFolderThumbnailTree(entry.folder, previewEntries, fullSpan)
            val tAttach0 = System.nanoTime()
            container.addView(obtain.view, insertStart)
            attachNanos = System.nanoTime() - tAttach0
        }
        val totalCount = if (entry.thumbnailEntryCount > 0) {
            entry.thumbnailEntryCount
        } else {
            entries.size
        }
        if (count > 0 && totalCount > count) {
            container.addView(
                createFolderThumbnailEllipsisView(holder, baseColor),
                insertStart + 1
            )
        }
        val bindNanos = System.nanoTime() - tStart
        if (DEBUG_FOLDER_THUMBNAIL_PERF && obtain != null) {
            logFolderThumbnailPerf(
                entry.folder, fullSpan, count, totalCount, obtain, attachNanos, bindNanos, holder
            )
        }
    }

    private fun getFolderThumbnailEntries(
        entry: ThingListEntry.FolderEntry
    ): List<ThingListEntry> {
        // 该有效私密文件夹是否应揭示真实内容（如处于已认证的祖先私密文件夹内）。
        // entry.effectivePrivate 短路：非有效私密文件夹无遮蔽可绕过，免去揭示判定的路径遍历。
        val reveal = entry.effectivePrivate && shouldRevealFolderContent(entry.folder)
        // 外观编辑预览中的文件夹：草稿外观（如刚把普通宽度切到 full-span）改变了取数上限，但 entry 缓存
        // 的投影是列表构建时按旧外观取的（普通宽度=3）。预览必须按草稿外观实时取数，否则 full-span 预览
        // 只显示普通宽度的 3 个，要等点击确定、列表重建后才正确显示 6 个。
        val appearanceReveal = entry.folder.id == mAppearanceRevealFolderId
        // 投影预算的 thumbnailEntries 是按 revealRoot=false 算的：有效私密文件夹的直接记事在
        // getDirectThumbnailThings 被整条过滤、只剩子文件夹。故揭示时不能用这份缓存（否则"只见
        // 子文件夹、不见记事"），必须按 revealRoot 重新实时取数。
        if (!reveal && !appearanceReveal && entry.thumbnailEntries.isNotEmpty()) {
            return entry.thumbnailEntries
        }
        if (presentationFor(entry.folder).mode ==
                ThingFolderCardPresentation.MODE_THUMBNAILS
        ) {
            val previewEntries = mThingManager!!.getFolderThumbnailPreviewEntries(
                entry.folder,
                revealRoot = reveal
            )
            if (previewEntries.isNotEmpty()) return previewEntries
        }
        return entry.thumbnailThings.map { ThingListEntry.ThingEntry(it) }
    }

    private fun createFolderThumbnailListView(entries: List<ThingListEntry>): View {
        val list = LinearLayout(mApp)
        list.tag = FOLDER_THUMBNAIL_VIEW_TAG
        list.orientation = LinearLayout.VERTICAL
        allowFolderThumbnailShadowOverflow(list)
        list.setPadding(
            (mDensity * FOLDER_THUMBNAIL_SIDE_MARGIN_DP).toInt(),
            0,
            (mDensity * FOLDER_THUMBNAIL_SIDE_MARGIN_DP).toInt(),
            0
        )

        for ((index, entry) in entries.withIndex()) {
            val previewWidth = getFolderThumbnailNormalPreviewWidth()
            val thumbnail = createFolderEntryPreviewView(
                list,
                entry,
                createFolderPreviewStyle(
                    entry = entry,
                    compact = false,
                    previewWidth = previewWidth,
                    fullSpanPreviewWidth = previewWidth
                )
            )
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (mDensity * if (index == 0) {
                FOLDER_THUMBNAIL_HEADER_GAP_DP
            } else {
                FOLDER_THUMBNAIL_ITEM_GAP_DP
            }).toInt()
            list.addView(thumbnail, lp)
        }

        return list
    }

    private fun createFolderThumbnailMasonryView(entries: List<ThingListEntry>): View {
        val grid = LinearLayout(mApp)
        grid.tag = FOLDER_THUMBNAIL_VIEW_TAG
        grid.orientation = LinearLayout.VERTICAL
        allowFolderThumbnailShadowOverflow(grid)
        grid.setPadding(
            (mDensity * FOLDER_THUMBNAIL_SIDE_MARGIN_DP).toInt(),
            0,
            (mDensity * FOLDER_THUMBNAIL_SIDE_MARGIN_DP).toInt(),
            0
        )

        val columnCount = folderThumbnailFullSpanColumnCount()
        var columnsContainer: LinearLayout? = null
        var columns: Array<LinearLayout>? = null
        var columnHeights = IntArray(columnCount)
        var hasRenderedPreview = false

        for (entry in entries) {
            if (isFolderPreviewFullSpanEntry(entry)) {
                val previewWidth = getFolderThumbnailFullPreviewWidth()
                val thumbnail = createFolderEntryPreviewView(
                    grid,
                    entry,
                    createFolderPreviewStyle(
                        entry = entry,
                        compact = false,
                        previewWidth = previewWidth,
                        fullSpanPreviewWidth = previewWidth
                    )
                )
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = (mDensity * if (hasRenderedPreview) {
                    FOLDER_THUMBNAIL_ITEM_GAP_DP
                } else {
                    FOLDER_THUMBNAIL_HEADER_GAP_DP
                }).toInt()
                grid.addView(thumbnail, lp)
                hasRenderedPreview = true
                columnsContainer = null
                columns = null
                columnHeights = IntArray(columnCount)
            } else {
                if (columnsContainer == null || columns == null) {
                    columnsContainer = createFolderThumbnailColumnsContainer(
                        grid,
                        if (hasRenderedPreview) {
                            FOLDER_THUMBNAIL_ITEM_GAP_DP
                        } else {
                            FOLDER_THUMBNAIL_HEADER_GAP_DP
                        }
                    )
                    hasRenderedPreview = true
                    columns = Array(columnCount) {
                        LinearLayout(mApp).apply {
                            orientation = LinearLayout.VERTICAL
                            allowFolderThumbnailShadowOverflow(this)
                        }
                    }
                    columnHeights = IntArray(columnCount)
                    for (i in columns!!.indices) {
                        val lp = LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f
                        )
                        if (i > 0) {
                            lp.marginStart = (mDensity * FOLDER_THUMBNAIL_COLUMN_GAP_DP).toInt()
                        }
                        columnsContainer!!.addView(columns!![i], lp)
                    }
                }

                val previewWidth = getFolderThumbnailColumnPreviewWidth(columnCount)
                val thumbnail = createFolderEntryPreviewView(
                    columnsContainer!!,
                    entry,
                    createFolderPreviewStyle(
                        entry = entry,
                        compact = true,
                        previewWidth = previewWidth,
                        fullSpanPreviewWidth = previewWidth
                    )
                )
                // 先按列宽实测缩略图真实高度，再选当前最矮列放入。此前用 estimateFolderEntryPreviewHeight
                // 的粗略估算（标题按单行、正文按固定行数、媒体按固定高度）选列，估算误差会累积，把卡片放进
                // 实际更高的列，留下明显能容纳的空列，从而把整张大文件夹卡撑高。仅在测量异常返回 0 时回退估算。
                val measuredHeight = measureFolderThumbnailPreviewHeight(thumbnail, previewWidth)
                    .takeIf { it > 0 }
                    ?: estimateFolderEntryPreviewHeight(entry, compact = true)
                val columnIndex = getShortestColumnIndex(columnHeights)
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                val itemTopMarginDp = if (columns!![columnIndex].childCount == 0) {
                    0
                } else {
                    FOLDER_THUMBNAIL_ITEM_GAP_DP
                }
                lp.topMargin = (mDensity * itemTopMarginDp).toInt()
                columns!![columnIndex].addView(thumbnail, lp)
                columnHeights[columnIndex] += (mDensity * itemTopMarginDp).toInt() + measuredHeight
            }
        }

        return grid
    }

    private fun createFolderThumbnailColumnsContainer(
        parent: LinearLayout,
        topMarginDp: Int
    ): LinearLayout {
        val row = LinearLayout(mApp)
        row.orientation = LinearLayout.HORIZONTAL
        allowFolderThumbnailShadowOverflow(row)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = (mDensity * topMarginDp).toInt()
        parent.addView(row, lp)
        return row
    }

    private fun getShortestColumnIndex(heights: IntArray): Int {
        var index = 0
        for (i in 1 until heights.size) {
            if (heights[i] < heights[index]) index = i
        }
        return index
    }

    /**
     * 按目标列宽精确测量一张缩略图预览卡的真实高度，用于瀑布流选列。预览卡此时已完成内容绑定、文字整体
     * 缩放与侧图投影结算，故按 EXACTLY(列宽) + UNSPECIFIED 高度测量即为最终高度。卡片尚未挂入列容器，
     * 独立测量不影响后续真正布局时的再次测量。
     */
    private fun measureFolderThumbnailPreviewHeight(view: View, width: Int): Int {
        val widthSpec = View.MeasureSpec.makeMeasureSpec(
            width.coerceAtLeast(1), View.MeasureSpec.EXACTLY
        )
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(widthSpec, heightSpec)
        return view.measuredHeight
    }

    private fun createFolderEntryPreviewView(
        parent: ViewGroup,
        entry: ThingListEntry,
        style: FolderThingPreviewStyle
    ): View {
        return when (entry) {
            is ThingListEntry.FolderEntry -> createFolderSummaryPreviewView(parent, entry, style)
            is ThingListEntry.ThingEntry -> createFolderThingPreviewView(
                parent,
                entry.thing,
                style
            )
        }
    }

    private fun createFolderThingPreviewView(
        parent: ViewGroup,
        thing: Thing,
        style: FolderThingPreviewStyle
    ): View {
        val view = mInflater!!.inflate(R.layout.card_thing, parent, false)
        val previewHolder = BaseThingViewHolder(view)
        val previewAdapter = FolderThingPreviewAdapter(thing, style)
        previewAdapter.onBindViewHolder(previewHolder, 0)
        configureFolderThumbnailPreviewTouch(previewHolder.cv!!) {
            mOnItemTouchedListener?.onFolderThumbnailClick(it, thing)
        }
        applyFolderThumbnailPreviewScale(previewHolder.cv, style)
        // 文字到此处才被整体缩小，而侧图卡的图片高度 = 内容列实测文本高度、绑定时按未缩放文字算的。
        // 在首帧前据缩放后的文字同步结算一次侧图投影，使卡片首帧即为最终高度；否则
        // syncSideImageProjectionAfterMeasure 的异步补算会在下一帧把高度改一下，长按等重绑时表现为
        // 缩略图“高度闪一下”，并连带挤动同列其它缩略图。
        previewAdapter.settleSideImageProjectionForPreview(previewHolder)
        reapplyFolderThumbnailPreviewMediaCrop(previewHolder, previewAdapter, thing)
        applyFolderThumbnailPreviewElevation(previewHolder.cv)
        // 若是正在做的记事：缩略图预览不经历正常列表的测量时机，updateCardForDoing 里 post 的
        // 蒙层缩放会在首帧之后再改一次尺寸，造成"闪一下"。这里按已知的预览宽度同步测量后立即
        // 定好蒙层图标 / 文字缩放，使首帧即为最终尺寸，避免闪烁。
        if (App.getDoingThingId() == thing.id) {
            preSizeDoingCoverForPreview(view, previewHolder, style)
        }
        return view
    }

    private fun preSizeDoingCoverForPreview(
        card: View,
        holder: BaseThingViewHolder,
        style: FolderThingPreviewStyle
    ) {
        val widthSpec = View.MeasureSpec.makeMeasureSpec(style.previewWidth, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        card.measure(widthSpec, heightSpec)
        card.layout(0, 0, card.measuredWidth, card.measuredHeight)
        holder.applyDoingCoverScale()
    }

    private fun createFolderSummaryPreviewView(
        parent: ViewGroup,
        entry: ThingListEntry.FolderEntry,
        style: FolderThingPreviewStyle
    ): View {
        val view = mInflater!!.inflate(R.layout.card_thing, parent, false)
        val previewHolder = BaseThingViewHolder(view)
        val previewFolder = copyFolderForSummaryPreview(entry.folder)
        val previewEntry = entry.copy(
            folder = previewFolder,
            thumbnailEntries = emptyList(),
            thumbnailEntryCount = 0,
            thumbnailThings = emptyList()
        )
        resetFolderCardHolder(previewHolder)
        setFolderPreviewContentWidth(previewHolder)
        bindFolderCardSurface(previewHolder, previewFolder)
        bindFolderCardContent(previewHolder, previewEntry)
        alignFolderSummaryPreviewTitleSize(previewHolder, style)
        configureFolderThumbnailPreviewTouch(previewHolder.cv!!) {
            mOnItemTouchedListener?.onFolderThumbnailFolderClick(it, entry)
        }
        applyFolderThumbnailPreviewScale(previewHolder.cv, style)
        applyFolderThumbnailPreviewElevation(previewHolder.cv)
        return view
    }

    /**
     * 缩略图预览卡的触摸 / 点击配置：
     * - 普通模式：预览卡自行响应点击（打开对应记事 / 进入子文件夹）并显示自身 ripple。
     * - 其它模式（尤其选择模式）：预览卡不消费点击、去掉自身 ripple，让触摸冒泡到外层大文件夹卡——
     *   由文件夹卡显示整卡 ripple、按压动画并走 onItemClick（选择 / 反选该文件夹），与点击文件夹卡
     *   空白区域完全一致。始终拦截内部触摸（setShouldInterceptTouchEvent(true)），避免预览里的清单
     *   RecyclerView 抢走点击、阻断冒泡。进入 / 退出选择模式会整表重绑（notifyDataSetChanged），故
     *   每次重建预览时按当前模式配置即可。
     */
    private fun configureFolderThumbnailPreviewTouch(
        cv: InterceptTouchCardView,
        onNormalClick: (View) -> Unit
    ) {
        cv.setShouldInterceptTouchEvent(true)
        cv.setOnLongClickListener(null)
        if (getCurrentMode() == ModeManager.NORMAL) {
            cv.setOnClickListener { onNormalClick(it) }
        } else {
            cv.setOnClickListener(null)
            cv.isClickable = false
            cv.isLongClickable = false
            cv.foreground = null
        }
    }

    private fun copyFolderForSummaryPreview(folder: ThingFolder): ThingFolder {
        val preview = ThingFolder(
            id = folder.id,
            parentFolderId = folder.parentFolderId,
            title = folder.title,
            state = folder.state,
            color = folder.getColor(),
            location = folder.location,
            isPrivate = folder.isPrivate,
            createTime = folder.createTime,
            updateTime = folder.updateTime,
            cardPresentation = presentationFor(folder).withMode(
                ThingFolderCardPresentation.MODE_SUMMARY
            )
        )
        preview.setBackground(folder.getBackground())
        return preview
    }

    private fun setFolderPreviewContentWidth(holder: BaseThingViewHolder) {
        val contentLp = holder.llContent!!.layoutParams
        contentLp.width = ViewGroup.LayoutParams.MATCH_PARENT
        holder.llContent.layoutParams = contentLp

        val textLp = holder.llTextContent!!.layoutParams as LinearLayout.LayoutParams
        textLp.width = ViewGroup.LayoutParams.MATCH_PARENT
        holder.llTextContent.layoutParams = textLp
    }

    private fun alignFolderSummaryPreviewTitleSize(
        holder: BaseThingViewHolder,
        style: FolderThingPreviewStyle
    ) {
        val container = holder.llTextContent ?: return
        val headerIndex = findFolderHeaderIndex(container)
        if (headerIndex < 0) return
        val header = container.getChildAt(headerIndex) as? ViewGroup ?: return
        var iconHeightPx = 0
        var titleView: TextView? = null
        for (i in 0 until header.childCount) {
            val child = header.getChildAt(i)
            if (iconHeightPx <= 0 && child is ImageView) {
                iconHeightPx = child.layoutParams?.height?.takeIf { it > 0 } ?: 0
            }
            if (titleView == null && child is TextView) {
                titleView = child
            }
        }
        val title = titleView ?: return
        title.textSize = style.titleTextSize
        alignFolderSummaryPreviewTitleFirstLine(title, iconHeightPx, style)
    }

    private fun alignFolderSummaryPreviewTitleFirstLine(
        titleView: TextView,
        iconHeightPx: Int,
        style: FolderThingPreviewStyle
    ) {
        if (iconHeightPx <= 0 || style.layoutScale <= 0f) return
        val titleLp = titleView.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val fontMetrics = titleView.paint.fontMetrics
        val firstLineHeightPx = fontMetrics.descent - fontMetrics.ascent
        val shapeOffsetPx =
            mDensity * FOLDER_THUMBNAIL_FOLDER_TITLE_SHAPE_OFFSET_DP / style.layoutScale
        val topMarginPx = (
            (iconHeightPx - firstLineHeightPx) / 2f *
                style.textScale / style.layoutScale +
                shapeOffsetPx
        ).roundToInt()
        titleLp.topMargin = topMarginPx
        titleView.layoutParams = titleLp
    }

    private fun applyFolderThumbnailPreviewElevation(card: CardView?) {
        val elevation = mDensity * FOLDER_THUMBNAIL_PREVIEW_ELEVATION_DP
        card?.maxCardElevation = elevation
        card?.cardElevation = elevation
    }

    private fun allowFolderThumbnailShadowOverflow(viewGroup: ViewGroup?) {
        viewGroup ?: return
        viewGroup.clipChildren = false
        viewGroup.clipToPadding = false
    }

    private fun applyFolderThumbnailPreviewScale(
        view: View?,
        style: FolderThingPreviewStyle,
        scaleLayout: Boolean = false
    ) {
        view ?: return
        if (scaleLayout) {
            scaleFolderThumbnailSpacing(view, style.layoutScale)
            scaleFolderThumbnailFixedSpacer(view, style.bottomSpacerScale)
        }
        when (view) {
            is TextView -> {
                scaleFolderThumbnailText(view, style.textScale)
                scaleFolderThumbnailCompoundDrawables(view, style.iconScale)
            }
            is ImageView -> scaleFolderThumbnailImage(view, style.iconScale)
        }
        if (view is ViewGroup) {
            allowFolderThumbnailShadowOverflow(view)
            for (i in 0 until view.childCount) {
                applyFolderThumbnailPreviewScale(
                    view.getChildAt(i),
                    style,
                    scaleLayout = true
                )
            }
        }
    }

    private fun scaleFolderThumbnailSpacing(view: View, scale: Float) {
        if (!shouldPreserveFolderThumbnailMediaSurface(view) &&
            (view.paddingLeft != 0 || view.paddingTop != 0 ||
                    view.paddingRight != 0 || view.paddingBottom != 0)
        ) {
            view.setPadding(
                (view.paddingLeft * scale).toInt().coerceAtLeast(0),
                (view.paddingTop * scale).toInt().coerceAtLeast(0),
                (view.paddingRight * scale).toInt().coerceAtLeast(0),
                (view.paddingBottom * scale).toInt().coerceAtLeast(0)
            )
        }

        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val oldMarginStart = lp.marginStart
        val oldMarginEnd = lp.marginEnd
        if (lp.leftMargin == 0 && lp.topMargin == 0 &&
            lp.rightMargin == 0 && lp.bottomMargin == 0 &&
            oldMarginStart == 0 && oldMarginEnd == 0
        ) return
        lp.setMargins(
            (lp.leftMargin * scale).toInt(),
            (lp.topMargin * scale).toInt(),
            (lp.rightMargin * scale).toInt(),
            (lp.bottomMargin * scale).toInt()
        )
        lp.marginStart = (oldMarginStart * scale).toInt()
        lp.marginEnd = (oldMarginEnd * scale).toInt()
        view.layoutParams = lp
    }

    private fun scaleFolderThumbnailFixedSpacer(view: View, scale: Float) {
        if (view.id != R.id.view_thing_padding_bottom) return
        val lp = view.layoutParams ?: return
        if (lp.height <= 0) return
        lp.height = (lp.height * scale).toInt().coerceAtLeast(1)
        view.layoutParams = lp
    }

    private fun scaleFolderThumbnailText(textView: TextView, scale: Float) {
        val scaledDensity = textView.resources.displayMetrics.scaledDensity
        if (scaledDensity <= 0f) return
        val textSizeSp = textView.textSize / scaledDensity
        val audioOnlyAdjustment = if (
            textView.id == R.id.tv_thing_audio_attachment_count && textSizeSp >= 16f
        ) {
            2f
        } else {
            0f
        }
        textView.textSize = (textSizeSp * scale - audioOnlyAdjustment).coerceAtLeast(8f)
    }

    private fun scaleFolderThumbnailImage(imageView: ImageView, scale: Float) {
        if (shouldPreserveFolderThumbnailMediaSurface(imageView)) return
        val lp = imageView.layoutParams ?: return
        val drawable = imageView.drawable
        val originalWidth = lp.width
        val originalHeight = lp.height
        var resized = false
        if (originalWidth > 0) {
            lp.width = (originalWidth * scale).toInt().coerceAtLeast(1)
            resized = true
        } else if (originalWidth == ViewGroup.LayoutParams.WRAP_CONTENT
            && originalHeight == ViewGroup.LayoutParams.WRAP_CONTENT
            && drawable != null
            && drawable.intrinsicWidth > 0
            && drawable.intrinsicHeight > 0
        ) {
            lp.width = (drawable.intrinsicWidth * scale).toInt().coerceAtLeast(1)
            lp.height = (drawable.intrinsicHeight * scale).toInt().coerceAtLeast(1)
            resized = true
        }
        if (originalHeight > 0) {
            lp.height = (originalHeight * scale).toInt().coerceAtLeast(1)
            resized = true
        }
        if (resized) {
            imageView.layoutParams = lp
            imageView.scaleX = 1f
            imageView.scaleY = 1f
        } else {
            imageView.scaleX = scale
            imageView.scaleY = scale
        }
    }

    private fun scaleFolderThumbnailCompoundDrawables(textView: TextView, scale: Float) {
        val drawables = textView.compoundDrawablesRelative
        if (drawables.all { it == null }) return
        drawables.forEach { scaleFolderThumbnailDrawable(it, scale) }
        textView.setCompoundDrawablesRelative(
            drawables[0],
            drawables[1],
            drawables[2],
            drawables[3]
        )
        textView.compoundDrawablePadding =
            (textView.compoundDrawablePadding * scale).toInt().coerceAtLeast(0)
    }

    private fun scaleFolderThumbnailDrawable(drawable: Drawable?, scale: Float) {
        drawable ?: return
        val sourceWidth = if (drawable.bounds.width() > 0) {
            drawable.bounds.width()
        } else {
            drawable.intrinsicWidth
        }
        val sourceHeight = if (drawable.bounds.height() > 0) {
            drawable.bounds.height()
        } else {
            drawable.intrinsicHeight
        }
        if (sourceWidth <= 0 || sourceHeight <= 0) return
        drawable.mutate().setBounds(
            0,
            0,
            (sourceWidth * scale).toInt().coerceAtLeast(1),
            (sourceHeight * scale).toInt().coerceAtLeast(1)
        )
    }

    private fun shouldPreserveFolderThumbnailMediaSurface(view: View): Boolean {
        return when (view.id) {
            R.id.fl_thing_image,
            R.id.iv_thing_image,
            R.id.iv_thing_media_background,
            R.id.view_thing_media_background_mask,
            R.id.view_thing_image_cover -> true
            else -> false
        }
    }

    private fun reapplyFolderThumbnailPreviewMediaCrop(
        holder: BaseThingViewHolder,
        adapter: FolderThingPreviewAdapter,
        thing: Thing
    ) {
        holder.cv?.post {
            adapter.applyThingCardMediaCropToBoundHolder(holder, thing)
        }
    }

    private fun createFolderThumbnailEllipsisView(
        holder: BaseThingViewHolder,
        baseColor: Int
    ): View {
        val ellipsis = TextView(mApp)
        ellipsis.tag = FOLDER_THUMBNAIL_VIEW_TAG
        ellipsis.includeFontPadding = false
        ellipsis.gravity = Gravity.CENTER
        ellipsis.isClickable = true
        ellipsis.text = "..."
        ellipsis.textSize = 18f
        ellipsis.setTextColor(textColorSecondary(baseColor))
        ellipsis.setPadding((mDensity * 8).toInt(), 0, (mDensity * 8).toInt(), 0)
        ellipsis.setOnClickListener {
            val listPosition = holder.adapterPosition
            if (listPosition != -1) {
                mOnItemTouchedListener?.onItemClick(holder.cv, listPosition)
            }
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.gravity = Gravity.CENTER_HORIZONTAL
        lp.setMargins(0, (mDensity * 1).toInt(), 0, 0)
        ellipsis.layoutParams = lp
        return ellipsis
    }

    private fun createFolderPreviewStyle(
        entry: ThingListEntry,
        compact: Boolean,
        previewWidth: Int,
        fullSpanPreviewWidth: Int
    ): FolderThingPreviewStyle {
        val thing = (entry as? ThingListEntry.ThingEntry)?.thing
        val hasMedia = thing != null && ThingCardMediaHelper.resolveEffectiveMediaSource(thing) != null
        val surfaceHeightDp = when {
            hasMedia && !compact -> FOLDER_THUMBNAIL_MEDIA_SURFACE_TALL_DP
            hasMedia -> FOLDER_THUMBNAIL_MEDIA_SURFACE_COMPACT_DP
            !compact -> FOLDER_THUMBNAIL_TEXT_SURFACE_TALL_DP
            else -> FOLDER_THUMBNAIL_TEXT_SURFACE_COMPACT_DP
        }
        return FolderThingPreviewStyle(
            compact = compact,
            previewWidth = previewWidth,
            fullSpanPreviewWidth = fullSpanPreviewWidth,
            surfaceAvailableHeight = (mDensity * surfaceHeightDp).toInt()
        )
    }

    private fun getFolderThumbnailNormalPreviewWidth(): Int {
        return (getBoundNormalThingCardWidth()
            - (mDensity * FOLDER_THUMBNAIL_SIDE_MARGIN_DP * 2).toInt())
            .coerceAtLeast(1)
    }

    private fun getFolderThumbnailFullPreviewWidth(): Int {
        return (getBoundFullSpanThingCardWidth()
            - (mDensity * FOLDER_THUMBNAIL_SIDE_MARGIN_DP * 2).toInt())
            .coerceAtLeast(1)
    }

    private fun getFolderThumbnailColumnPreviewWidth(columnCount: Int): Int {
        val cols = columnCount.coerceAtLeast(1)
        val fullWidth = getFolderThumbnailFullPreviewWidth()
        val gap = (mDensity * FOLDER_THUMBNAIL_COLUMN_GAP_DP).toInt()
        return ((fullWidth - gap * (cols - 1)) / cols).coerceAtLeast(1)
    }

    /**
     * Masonry column count for full-span thumbnail Folder Cards: the current home
     * list column count + 1. Read live so it follows the list span across
     * orientation changes (which rebind the whole list). Falls back to the legacy
     * fixed count when no list span is available yet.
     */
    private fun folderThumbnailFullSpanColumnCount(): Int {
        val span = getBoundListSpanCount()
        return if (span > 0) span + 1 else FOLDER_THUMBNAIL_FULL_SPAN_COLUMN_COUNT
    }

    /**
     * How many thumbnails a full-span Folder Card shows: phones keep a fixed count
     * (limited vertical space in landscape), tablets show ~2 rows (2×columns).
     */
    private fun folderThumbnailFullSpanDisplayCount(): Int {
        return if (DisplayUtil.isTablet(mApp)) {
            2 * folderThumbnailFullSpanColumnCount()
        } else {
            FOLDER_THUMBNAIL_PHONE_FULL_SPAN_DISPLAY_COUNT
        }
    }

    private fun isFolderPreviewFullSpanThing(thing: Thing): Boolean {
        return thing.type != Thing.HEADER
                && thing.type < Thing.NOTIFICATION_UNDERWAY
                && thing.thingCardAppearance.spanMode == Thing.THING_CARD_SPAN_FULL
    }

    private fun isFolderPreviewFullSpanEntry(entry: ThingListEntry): Boolean {
        return when (entry) {
            is ThingListEntry.FolderEntry ->
                presentationFor(entry.folder).spanMode ==
                    ThingFolderCardPresentation.SPAN_FULL
            is ThingListEntry.ThingEntry -> isFolderPreviewFullSpanThing(entry.thing)
        }
    }

    private fun lightVariant(bg: ThingBackground?): ThingBackground? {
        if (bg == null) return null
        return if (bg.mode === ThingBackground.Mode.PURE) {
            ThingBackground.pure(DisplayUtil.getLightColor(bg.color, mApp))
        } else {
            ThingBackground.gradient(
                DisplayUtil.getLightColor(bg.color, mApp),
                DisplayUtil.getLightColor(bg.endColor, mApp),
                bg.orientation
            )
        }
    }

    private fun estimateFolderEntryPreviewHeight(
        entry: ThingListEntry,
        compact: Boolean
    ): Int {
        return when (entry) {
            is ThingListEntry.FolderEntry -> (mDensity * if (compact) 86 else 96).toInt()
            is ThingListEntry.ThingEntry -> estimateFolderThingPreviewHeight(
                entry.thing,
                compact
            )
        }
    }

    private fun estimateFolderThingPreviewHeight(thing: Thing, compact: Boolean): Int {
        val media = if (ThingCardMediaHelper.resolveEffectiveMediaSource(thing) != null) {
            (mDensity * if (compact)
                FOLDER_THUMBNAIL_MEDIA_ESTIMATE_COMPACT_DP
            else
                FOLDER_THUMBNAIL_MEDIA_ESTIMATE_TALL_DP).toInt()
        } else 0
        val title = if (!thing.title.isNullOrBlank()) {
            (mDensity * if (compact) 32 else 36).toInt()
        } else 0
        val content = thing.content?.trim().orEmpty()
        val body = if (content.isEmpty()) {
            0
        } else if (CheckListHelper.isCheckListStr(content)) {
            val items = CheckListHelper.toCheckListItems(content, false)
                .count { it?.startsWith("0") == true || it?.startsWith("1") == true }
                .coerceAtMost(if (compact) 3 else 4)
            (mDensity * (items * 22 + 12)).toInt()
        } else {
            val lines = if (compact) 2 else 3
            (mDensity * (lines * 18 + 16)).toInt()
        }
        return media + title + body + (mDensity * 28).toInt()
    }

    private fun removeFolderDynamicViews(holder: BaseThingViewHolder) {
        removeFolderHeaderViews(holder)
        removeFolderCountViews(holder)
        removeFolderThumbnailViews(holder)
    }

    private fun removeFolderHeaderViews(holder: BaseThingViewHolder) {
        val container = holder.llTextContent ?: return
        for (i in container.childCount - 1 downTo 0) {
            if (container.getChildAt(i).tag == FOLDER_HEADER_VIEW_TAG) {
                container.removeViewAt(i)
            }
        }
    }

    private fun removeFolderCountViews(holder: BaseThingViewHolder) {
        val container = holder.llTextContent ?: return
        for (i in container.childCount - 1 downTo 0) {
            if (container.getChildAt(i).tag == FOLDER_COUNT_VIEW_TAG) {
                container.removeViewAt(i)
            }
        }
    }

    private fun removeFolderThumbnailViews(holder: BaseThingViewHolder) {
        val container = holder.llTextContent ?: return
        for (i in container.childCount - 1 downTo 0) {
            if (container.getChildAt(i).tag == FOLDER_THUMBNAIL_VIEW_TAG) {
                container.removeViewAt(i)
            }
        }
    }

    private fun findFolderHeaderIndex(container: ViewGroup): Int {
        for (i in 0 until container.childCount) {
            if (container.getChildAt(i).tag == FOLDER_HEADER_VIEW_TAG) return i
        }
        return -1
    }

    private fun findFolderCountIndex(container: ViewGroup): Int {
        for (i in 0 until container.childCount) {
            if (container.getChildAt(i).tag == FOLDER_COUNT_VIEW_TAG) return i
        }
        return -1
    }

    private fun animateCardOnTouch(v: View?, event: MotionEvent?) {
        if (v !is CardView || event == null) {
            return
        }
        updateCardFingerState(v, event)
        if (mModeManager?.getCurrentMode() != ModeManager.NORMAL) {
            return
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> animateCardTouchDown(v)
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_OUTSIDE -> animateCardTouchRelease(v)
        }
    }

    private fun updateCardFingerState(card: CardView, event: MotionEvent) {
        val action = event.actionMasked
        val mode = mModeManager?.getCurrentMode()
        val before = card.getTag(R.id.tag_thing_card_finger_down)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> card.setTag(R.id.tag_thing_card_finger_down, true)
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_OUTSIDE -> card.setTag(R.id.tag_thing_card_finger_down, false)
            MotionEvent.ACTION_CANCEL -> {
                if (mModeManager?.getCurrentMode() != ModeManager.MOVING ||
                    card.getTag(R.id.tag_thing_card_drag_active) != true
                ) {
                    card.setTag(R.id.tag_thing_card_finger_down, false)
                }
            }
        }
        if (action == MotionEvent.ACTION_DOWN ||
            action == MotionEvent.ACTION_UP ||
            action == MotionEvent.ACTION_CANCEL ||
            action == MotionEvent.ACTION_OUTSIDE
        ) {
            logCardScaleRecoveryDebug(
                "touch action=${motionActionName(action)} view=${System.identityHashCode(card)} " +
                    "mode=$mode before=$before after=${card.getTag(R.id.tag_thing_card_finger_down)} " +
                    "drag=${card.getTag(R.id.tag_thing_card_drag_active)} " +
                    "scale=${card.scaleX}/${card.scaleY}"
            )
        }
    }

    private fun motionActionName(action: Int): String {
        return when (action) {
            MotionEvent.ACTION_DOWN -> "DOWN"
            MotionEvent.ACTION_UP -> "UP"
            MotionEvent.ACTION_CANCEL -> "CANCEL"
            MotionEvent.ACTION_OUTSIDE -> "OUTSIDE"
            else -> action.toString()
        }
    }

    private fun animateCardTouchDown(card: CardView) {
        val normalElevation = mApp!!.resources.getDimension(R.dimen.thing_card_normal_elevation)
        card.animate().cancel()
        card.animate()
            .scaleX(CARD_TOUCH_PRESSED_SCALE)
            .scaleY(CARD_TOUCH_PRESSED_SCALE)
            .setDuration(CARD_TOUCH_DOWN_DURATION)
            .start()
        ObjectAnimator.ofFloat(
            card, "cardElevation", normalElevation * CARD_TOUCH_PRESSED_ELEVATION_RATIO
        ).setDuration(CARD_TOUCH_DOWN_DURATION).start()
    }

    private fun animateCardTouchRelease(card: CardView) {
        val normalElevation = mApp!!.resources.getDimension(R.dimen.thing_card_normal_elevation)
        card.animate().cancel()
        card.animate()
            .scaleX(CARD_TOUCH_OVERSHOOT_SCALE)
            .scaleY(CARD_TOUCH_OVERSHOOT_SCALE)
            .setDuration(CARD_TOUCH_RELEASE_DURATION)
            .withEndAction {
                card.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(CARD_TOUCH_SETTLE_DURATION)
                    .withEndAction(null)
                    .start()
                ObjectAnimator.ofFloat(card, "cardElevation", normalElevation)
                    .setDuration(CARD_TOUCH_SETTLE_DURATION)
                    .start()
            }
            .start()
        ObjectAnimator.ofFloat(
            card, "cardElevation", normalElevation * CARD_TOUCH_OVERSHOOT_SCALE
        ).setDuration(CARD_TOUCH_RELEASE_DURATION).start()
    }

    private fun playAppearingAnimation(v: View, listPosition: Int) {
        v.visibility = View.INVISIBLE
        if (getItemViewType(listPosition) != Thing.HEADER) {
            val animation: Animation = AnimationUtils.loadAnimation(
                mApp, R.anim.things_show
            )
            animation.setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation) {
                    if (!mShouldThingsAnimWhenAppearing) {
                        v.clearAnimation()
                    }
                }

                override fun onAnimationEnd(animation: Animation) { }

                override fun onAnimationRepeat(animation: Animation) { }
            })
            mAnimHandler.postDelayed({
                v.visibility = View.VISIBLE
                v.startAnimation(animation)
            }, listPosition * 30L)
        }
    }

    override fun onChecklistAdapterInitialized(
        holder: BaseThingViewHolder, adapter: CheckListAdapter, thing: Thing
    ) {
        super.onChecklistAdapterInitialized(holder, adapter, thing)
        val toggleCliOtc = FrequentSettings.getBoolean(Def.Meta.KEY_TOGGLE_CLI_OTC)
        if (!toggleCliOtc
            || thing.type <= Thing.HEADER
            || thing.type >= Thing.NOTIFICATION_UNDERWAY
            || thing.state != Thing.UNDERWAY
            || getCurrentMode() != ModeManager.NORMAL
            || App.getDoingThingId() == thing.id
        ) {
            holder.cv!!.setShouldInterceptTouchEvent(true)
            adapter.setTvItemClickCallback(null)
        } else {
            holder.cv!!.setShouldInterceptTouchEvent(false)
            adapter.setTvItemClickCallback(object : CheckListAdapter.TvItemClickCallback {
                override fun onItemClick(itemPos: Int) {
                    val simpleFCli = FrequentSettings.getBoolean(Def.Meta.KEY_SIMPLE_FCLI)
                    val content: String = thing.content!!
                    if (simpleFCli) {
                        val items: MutableList<String?> = CheckListHelper.toCheckListItems(content, false)
                        items.remove("2")
                        items.remove("3")
                        items.remove("4")
                        if (itemPos < 0 || itemPos >= items.size
                            || items[itemPos]!!.startsWith("1")
                        ) {
                            return
                        }
                    }

                    val updatedContent: String = CheckListHelper.toggleChecklistItem(content, itemPos)!!
                    thing.content = updatedContent
                    val typeBefore = thing.type
                    val listPosition = holder.adapterPosition
                    if (listPosition == -1) return
                    val thingIndex = mThingManager!!.getThingIndexForListPosition(listPosition)
                    if (thingIndex == -1) return
                    ThingManager.getInstance(mApp)!!.update(typeBefore, thing, thingIndex, false)
                    notifyItemChanged(listPosition)
                    val thingId = thing.id
                    val thingType = thing.type
                    AppWidgetHelper.updateSingleThingAppWidgets(mApp, thingId)
                    AppWidgetHelper.updateThingsListAppWidgetsForType(mApp, thingType)
                    SystemNotificationUtil.cancelNotification(thingId, thingType, mApp)
                }

                override fun onItemSpaceClick(v: View?) {
                    if (mOnItemTouchedListener != null) {
                        val listPosition = holder.adapterPosition
                        mOnItemTouchedListener!!.onItemClick(v, listPosition)
                    }
                }
            })
        }
    }

    interface OnItemTouchedListener {
        fun onItemTouch(v: View?, event: MotionEvent?): Boolean
        fun onItemClick(v: View?, listPosition: Int)
        fun onItemLongClick(v: View?, listPosition: Int): Boolean
        fun onFolderThumbnailClick(v: View?, thing: Thing)
        fun onFolderThumbnailFolderClick(v: View?, entry: ThingListEntry.FolderEntry)
    }

    private inner class ThingViewHolder(item: View?) : BaseThingViewHolder(item) {

        init {
            if (mOnItemTouchedListener != null) {
                cv!!.setOnTouchListener(mOnTouchListener)
                cv.setOnClickListener { v ->
                    val listPosition = adapterPosition
                    mOnItemTouchedListener!!.onItemClick(v, listPosition)
                }
                cv.setOnLongClickListener { v ->
                    val listPosition = adapterPosition
                    mOnItemTouchedListener!!.onItemLongClick(v, listPosition)
                }
            }
        }
    }

    private data class FolderThingPreviewStyle(
        val compact: Boolean,
        val previewWidth: Int,
        val fullSpanPreviewWidth: Int,
        val surfaceAvailableHeight: Int
    ) {
        val titleTextSize: Float = 12.9f
        val checklistTextSize: Float = if (compact) 10.5f else 11f
        val contentMaxLines: Int = if (compact) 2 else 3
        val checklistMaxItems: Int = if (compact) 3 else 4
        val textScale: Float = if (compact) 0.88f else 0.9f
        val iconScale: Float = textScale
        val layoutScale: Float = if (compact) 0.62f else 0.72f
        val bottomSpacerScale: Float = layoutScale

        fun contentTextSize(defaultTextSizeSp: Float): Float {
            val maxSize = if (compact) 16f else 18f
            return defaultTextSizeSp.coerceIn(12f, maxSize)
        }
    }

    private inner class FolderThingPreviewAdapter(
        private val previewThing: Thing,
        private val style: FolderThingPreviewStyle
    ) : BaseThingsAdapter(mApp) {

        private val previewThings = listOf<Thing?>(previewThing)

        init {
            setCardWidth(style.previewWidth)
            setFullSpanCardWidth(style.fullSpanPreviewWidth)
            setThingCardSurfaceAvailableHeight(style.surfaceAvailableHeight)
            setShouldShowPrivateContent(shouldShowFolderPrivateContent())
        }

        /**
         * 预览构建在 applyFolderThumbnailPreviewScale 整体缩小文字之后，据缩放后的最终文本同步结算一次
         * 侧图投影，使卡片首帧即为最终高度，避免 syncSideImageProjectionAfterMeasure 的异步补算在下一帧
         * 再改高度（长按等重绑时表现为缩略图“高度闪一下”）。非侧图卡不受影响。
         */
        fun settleSideImageProjectionForPreview(holder: BaseThingViewHolder) {
            resyncSideImageProjection(holder, previewThing)
        }

        override fun getCurrentMode(): Int = ModeManager.NORMAL

        override fun getThings(): List<Thing?>? = previewThings

        override fun getStickyThingParentFolderBackground(thing: Thing): ThingBackground? {
            val folderId = thing.folderId ?: return null
            return mThingManager!!.getFolderById(folderId)?.getBackground()
        }

        override fun getThingCardForegroundThumbnailHeight(thing: Thing, imageW: Int): Int {
            val aspectRatio = getThingCardThumbnailTargetAspectRatio(thing)
            if (aspectRatio <= 0f) return imageW.coerceAtLeast(1)
            return (imageW / aspectRatio).toInt().coerceAtLeast(1)
        }

        override fun shouldLogThingCardMediaDebug(
            thing: Thing,
            mediaSource: ThingCardMediaHelper.MediaSource,
            placement: Int
        ): Boolean {
            return shouldLogForegroundVideoCrop(thing, mediaSource, placement)
        }

        private fun shouldLogForegroundVideoCrop(
            thing: Thing,
            mediaSource: ThingCardMediaHelper.MediaSource,
            placement: Int
        ): Boolean {
            return mediaSource.isVideo &&
                    !thing.thingCardAppearance.mediaBackgroundEnabled &&
                    (
                            placement == Thing.THING_CARD_IMAGE_PLACEMENT_TOP ||
                                    placement == Thing.THING_CARD_IMAGE_PLACEMENT_BOTTOM
                            )
        }

        override fun isFullSpanThingCard(thing: Thing): Boolean = isFolderPreviewFullSpanThing(thing)

        override fun isThingEffectivelyPrivate(thing: Thing): Boolean {
            return thing.isPrivate() && !shouldShowFolderPrivateContent()
                && !this@ThingsAdapter.isThingRevealedByAuth(thing.id)
        }

        override fun shouldDimUnselectedContent(currentMode: Int): Boolean = false

        override fun getThingCardTitleTextSize(
            thing: Thing,
            fullSpan: Boolean
        ): Float = style.titleTextSize

        override fun getThingCardContentMaxLines(
            thing: Thing,
            fullSpan: Boolean
        ): Int = style.contentMaxLines

        override fun getThingCardContentTextSize(
            thing: Thing,
            content: String,
            defaultTextSizeSp: Float
        ): Float = style.contentTextSize(defaultTextSizeSp)

        override fun getThingCardChecklistMaxItemCount(
            thing: Thing,
            fullSpan: Boolean
        ): Int = style.checklistMaxItems

        override fun getThingCardChecklistTextSize(
            thing: Thing,
            fullSpan: Boolean
        ): Float? = style.checklistTextSize

        override fun getThingCardHabitSummaryTextSize(thing: Thing): Float = 12f

        override fun shouldShowThingCardHabitDetails(thing: Thing): Boolean = false

        override fun getThingCardMediaBitmapCache() =
            this@ThingsAdapter.getThingCardMediaBitmapCache()

        override fun onChecklistAdapterInitialized(
            holder: BaseThingViewHolder,
            adapter: CheckListAdapter,
            thing: Thing
        ) {
            holder.cv!!.setShouldInterceptTouchEvent(true)
            adapter.setFixedIconScale(style.iconScale)
            adapter.setTvItemClickCallback(null)
        }
    }

    companion object {
        const val TAG: String = "ThingsAdapter"

        private const val CARD_TOUCH_PRESSED_SCALE = 0.936f
        private const val CARD_TOUCH_OVERSHOOT_SCALE = 1.016f
        private const val CARD_TOUCH_PRESSED_ELEVATION_RATIO = 2f / 3f
        private const val CARD_TOUCH_DOWN_DURATION = 96L
        private const val CARD_TOUCH_RELEASE_DURATION = 160L
        private const val CARD_TOUCH_SETTLE_DURATION = 80L
        private const val VIEW_TYPE_THING_FOLDER = -1000
        private const val FOLDER_HEADER_VIEW_TAG = "folder_header_view"
        private const val FOLDER_COUNT_VIEW_TAG = "folder_count_view"
        private const val FOLDER_THUMBNAIL_VIEW_TAG = "folder_thumbnail_view"

        private const val FOLDER_THUMBNAIL_FULL_SPAN_COLUMN_COUNT = 3
        private const val FOLDER_THUMBNAIL_PHONE_FULL_SPAN_DISPLAY_COUNT = 6
        // 缩略图整树缓存上限：约覆盖一屏可见大文件夹卡及上下相邻若干个的来回滚动复用，淘汰最久未用项。
        private const val FOLDER_THUMBNAIL_CACHE_MAX_ENTRIES = 24
        // 诊断用：大文件夹缩略图绑定性能日志（命中/未命中、签名/构建/attach/measure 各段耗时、滚动状态），
        // 异步写入 debug_logs/folder_thumbnail_perf.log。完成卡顿诊断后应移除这些埋点。
        private const val DEBUG_FOLDER_THUMBNAIL_PERF = false
        private const val FOLDER_THUMBNAIL_PERF_LOG = "folder_thumbnail_perf.log"
        private const val FOLDER_THUMBNAIL_SIDE_MARGIN_DP = 16
        private const val FOLDER_THUMBNAIL_COLUMN_GAP_DP = 6
        private const val FOLDER_THUMBNAIL_HEADER_GAP_DP = 12
        private const val FOLDER_THUMBNAIL_ITEM_GAP_DP = 8
        private const val FOLDER_THUMBNAIL_PREVIEW_ELEVATION_DP = 2
        private const val FOLDER_THUMBNAIL_FOLDER_TITLE_SHAPE_OFFSET_DP = 0.36f

        private const val FOLDER_THUMBNAIL_TEXT_SURFACE_COMPACT_DP = 150
        private const val FOLDER_THUMBNAIL_TEXT_SURFACE_TALL_DP = 170
        private const val FOLDER_THUMBNAIL_MEDIA_SURFACE_COMPACT_DP = 220
        private const val FOLDER_THUMBNAIL_MEDIA_SURFACE_TALL_DP = 260
        private const val FOLDER_THUMBNAIL_MEDIA_ESTIMATE_COMPACT_DP = 160
        private const val FOLDER_THUMBNAIL_MEDIA_ESTIMATE_TALL_DP = 200
    }
}
