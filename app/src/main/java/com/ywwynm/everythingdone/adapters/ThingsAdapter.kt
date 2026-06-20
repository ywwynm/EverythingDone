@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.adapters

import android.animation.ObjectAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.text.TextUtils
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

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.FrequentSettings
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.appwidgets.AppWidgetHelper
import com.ywwynm.everythingdone.helpers.CheckListHelper
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
        val defaultHeight = (if (App.isSearching) mDensity * 6 else mDensity * 102).toInt()
        if (App.isSearching) return defaultHeight
        return mActivityHeaderSpacerHeightPx.coerceAtLeast(defaultHeight)
    }

    private fun distinguishFolder(folder: ThingFolder, cv: CardView?) {
        val mX = mApp!!.resources.getDimensionPixelSize(R.dimen.thing_card_outer_spacing)
        val lp = cv!!.layoutParams as StaggeredGridLayoutManager.LayoutParams
        lp.height = StaggeredGridLayoutManager.LayoutParams.WRAP_CONTENT
        lp.setMargins(mX, mX, mX, mX)
        lp.isFullSpan =
            folder.effectiveCardPresentation().spanMode == ThingFolderCardPresentation.SPAN_FULL
        cv.visibility = View.VISIBLE
    }

    protected fun bindFolderCard(
        holder: BaseThingViewHolder,
        entry: ThingListEntry.FolderEntry
    ) {
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
        val presentation = folder.effectiveCardPresentation()
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

        val baseColor = if (thumbnailMode) {
            getThumbnailFolderSurfaceBackground(background, folder.getColor()).representativeColor()
        } else {
            background?.representativeColor() ?: folder.getColor()
        }
        holder.cv!!.foreground = ContextCompat.getDrawable(
            mApp!!,
            if (BackgroundUtil.isLight(baseColor))
                R.drawable.selectable_item_background
            else
                R.drawable.selectable_item_background_light
        )
    }

    private fun bindFolderSelectionAppearance(
        holder: BaseThingViewHolder,
        folder: ThingFolder
    ) {
        val card = holder.cv ?: return
        val selected = folder.isSelected()
        val currentMode = getCurrentMode()
        val background = folder.getBackground()
        val presentation = folder.effectiveCardPresentation()
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
        holder.cv.setCardBackgroundColor(cardBackground.representativeColor())
        holder.cv.maxCardElevation = mApp!!.resources.getDimension(R.dimen.thing_card_dragging_elevation)
        holder.cv.cardElevation = mApp!!.resources.getDimension(R.dimen.thing_card_normal_elevation)
        applyRoundedCardOutline(holder.cv, radius)

        val strokeColor = background?.representativeColor() ?: fallbackColor
        val outline = GradientDrawable()
        outline.setColor(Color.TRANSPARENT)
        outline.cornerRadius = radius
        outline.setStroke((mDensity * 1.5f).toInt().coerceAtLeast(1), strokeColor)
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
        val hiddenPrivate = entry.effectivePrivate && !shouldShowFolderPrivateContent()
        val presentation = folder.effectiveCardPresentation()
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
            R.drawable.ic_thing_folder,
            baseColor,
            if (thumbnailMode) background else null
        )

        if (hiddenPrivate) {
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
            ImageViewCompat.setImageTintList(
                icon,
                ColorStateList.valueOf(ContextCompat.getColor(mApp!!, R.color.app_accent))
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
        titleBackground: ThingBackground? = null
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
        icon.setImageResource(iconRes)
        icon.contentDescription = mApp!!.getString(R.string.thing_folder)
        icon.scaleType = ImageView.ScaleType.CENTER_INSIDE
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

    private fun bindFolderThumbnails(
        holder: BaseThingViewHolder,
        entry: ThingListEntry.FolderEntry,
        baseColor: Int
    ) {
        val entries = getFolderThumbnailEntries(entry)
        val container = holder.llTextContent ?: return
        allowFolderThumbnailShadowOverflow(container)
        val count = entries.size.coerceAtMost(
            entry.folder.effectiveCardPresentation().effectiveThumbnailPreviewLimit()
        )
        val insertStart = (findFolderCountIndex(container) + 1)
            .coerceIn(0, container.childCount)
        if (count > 0) {
            val previewEntries = entries.take(count)
            val thumbnails = if (
                entry.folder.effectiveCardPresentation().spanMode ==
                    ThingFolderCardPresentation.SPAN_FULL
            ) {
                createFolderThumbnailMasonryView(previewEntries)
            } else {
                createFolderThumbnailListView(previewEntries)
            }
            container.addView(thumbnails, insertStart)
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
    }

    private fun getFolderThumbnailEntries(
        entry: ThingListEntry.FolderEntry
    ): List<ThingListEntry> {
        if (entry.thumbnailEntries.isNotEmpty()) return entry.thumbnailEntries
        if (entry.folder.effectiveCardPresentation().mode ==
                ThingFolderCardPresentation.MODE_THUMBNAILS
        ) {
            val previewEntries = mThingManager!!.getFolderThumbnailPreviewEntries(entry.folder)
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

        var columnsContainer: LinearLayout? = null
        var columns: Array<LinearLayout>? = null
        var estimatedHeights = IntArray(FOLDER_THUMBNAIL_FULL_SPAN_COLUMN_COUNT)
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
                estimatedHeights = IntArray(FOLDER_THUMBNAIL_FULL_SPAN_COLUMN_COUNT)
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
                    columns = Array(FOLDER_THUMBNAIL_FULL_SPAN_COLUMN_COUNT) {
                        LinearLayout(mApp).apply {
                            orientation = LinearLayout.VERTICAL
                            allowFolderThumbnailShadowOverflow(this)
                        }
                    }
                    estimatedHeights = IntArray(FOLDER_THUMBNAIL_FULL_SPAN_COLUMN_COUNT)
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

                val columnIndex = getShortestColumnIndex(estimatedHeights)
                val previewWidth = getFolderThumbnailColumnPreviewWidth()
                val thumbnail = createFolderEntryPreviewView(
                    columns!![columnIndex],
                    entry,
                    createFolderPreviewStyle(
                        entry = entry,
                        compact = true,
                        previewWidth = previewWidth,
                        fullSpanPreviewWidth = previewWidth
                    )
                )
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
                estimatedHeights[columnIndex] += (mDensity * itemTopMarginDp).toInt() +
                    estimateFolderEntryPreviewHeight(entry, compact = true)
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
        previewHolder.cv!!.setShouldInterceptTouchEvent(true)
        previewHolder.cv.setOnClickListener {
            mOnItemTouchedListener?.onFolderThumbnailClick(it, thing)
        }
        previewHolder.cv.setOnLongClickListener(null)
        applyFolderThumbnailPreviewScale(previewHolder.cv, style)
        reapplyFolderThumbnailPreviewMediaCrop(previewHolder, previewAdapter, thing)
        applyFolderThumbnailPreviewElevation(previewHolder.cv)
        return view
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
        previewHolder.cv!!.setShouldInterceptTouchEvent(true)
        previewHolder.cv.setOnClickListener {
            mOnItemTouchedListener?.onFolderThumbnailFolderClick(it, entry)
        }
        previewHolder.cv.setOnLongClickListener(null)
        applyFolderThumbnailPreviewScale(previewHolder.cv, style)
        applyFolderThumbnailPreviewElevation(previewHolder.cv)
        return view
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
            cardPresentation = folder.effectiveCardPresentation().withMode(
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

    private fun getFolderThumbnailColumnPreviewWidth(): Int {
        val fullWidth = getFolderThumbnailFullPreviewWidth()
        val gap = (mDensity * FOLDER_THUMBNAIL_COLUMN_GAP_DP).toInt()
        return ((fullWidth - gap * (FOLDER_THUMBNAIL_FULL_SPAN_COLUMN_COUNT - 1))
            / FOLDER_THUMBNAIL_FULL_SPAN_COLUMN_COUNT).coerceAtLeast(1)
    }

    private fun isFolderPreviewFullSpanThing(thing: Thing): Boolean {
        return thing.type != Thing.HEADER
                && thing.type < Thing.NOTIFICATION_UNDERWAY
                && thing.thingCardAppearance.spanMode == Thing.THING_CARD_SPAN_FULL
    }

    private fun isFolderPreviewFullSpanEntry(entry: ThingListEntry): Boolean {
        return when (entry) {
            is ThingListEntry.FolderEntry ->
                entry.folder.effectiveCardPresentation().spanMode ==
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
        val titleTextSize: Float = 13f
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
        private const val FOLDER_THUMBNAIL_SIDE_MARGIN_DP = 16
        private const val FOLDER_THUMBNAIL_COLUMN_GAP_DP = 6
        private const val FOLDER_THUMBNAIL_HEADER_GAP_DP = 12
        private const val FOLDER_THUMBNAIL_ITEM_GAP_DP = 8
        private const val FOLDER_THUMBNAIL_PREVIEW_ELEVATION_DP = 2

        private const val FOLDER_THUMBNAIL_TEXT_SURFACE_COMPACT_DP = 150
        private const val FOLDER_THUMBNAIL_TEXT_SURFACE_TALL_DP = 170
        private const val FOLDER_THUMBNAIL_MEDIA_SURFACE_COMPACT_DP = 220
        private const val FOLDER_THUMBNAIL_MEDIA_SURFACE_TALL_DP = 260
        private const val FOLDER_THUMBNAIL_MEDIA_ESTIMATE_COMPACT_DP = 160
        private const val FOLDER_THUMBNAIL_MEDIA_ESTIMATE_TALL_DP = 200
    }
}
