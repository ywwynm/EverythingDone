@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.adapters

import android.animation.ObjectAnimator
import android.content.res.ColorStateList
import android.graphics.Color
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
import com.ywwynm.everythingdone.managers.ModeManager
import com.ywwynm.everythingdone.managers.ThingManager
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.model.ThingFolder
import com.ywwynm.everythingdone.model.ThingFolderCardPresentation
import com.ywwynm.everythingdone.model.ThingListEntry
import com.ywwynm.everythingdone.utils.BackgroundUtil
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

    interface OnNewItemBoundListener {
        fun onNewItemBound(position: Int, holder: BaseThingViewHolder?)
    }

    private var mArmedNewItemPosition: Int = -1
    private var mArmedNewItemId: Long = -1L
    private var mArmedNewItemListener: OnNewItemBoundListener? = null

    open fun setModeManager(modeManager: ModeManager?) {
        mModeManager = modeManager
    }

    override fun getCurrentMode(): Int = mModeManager!!.getCurrentMode()

    override fun getThings(): List<Thing?>? = mThingManager!!.getThings()

    private fun getEntries(): List<ThingListEntry>? = mThingManager!!.getThingListEntries()

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
    open fun armNewItemAnimation(position: Int, thingId: Long, listener: OnNewItemBoundListener?) {
        mArmedNewItemPosition = position
        mArmedNewItemId       = thingId
        mArmedNewItemListener = listener
    }

    open fun clearArmedNewItemAnimation() {
        mArmedNewItemPosition = -1
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
        holder.itemView.visibility = View.VISIBLE
        holder.itemView.alpha = 1.0f
        holder.itemView.scaleX = 1.0f
        holder.itemView.scaleY = 1.0f
        holder.itemView.translationZ = 0.0f

        val entry = getEntries()?.getOrNull(position)
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
        val thing = getThingAt(position)!!
        distinguishHeaderAndOthers(thing, holder.cv)
        super.onBindViewHolder(holder, position)

        val armed = isArmedFor(position)

        if (!armed) {
            if (mModeManager!!.getCurrentMode() == ModeManager.NORMAL
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

    private fun isArmedFor(position: Int): Boolean {
        if (mArmedNewItemListener == null) return false
        if (position != mArmedNewItemPosition) return false
        if (mArmedNewItemId == -1L) return true
        return getThingAt(position)?.id == mArmedNewItemId
    }

    private fun maybeTriggerArmedNewItemAnimation(holder: BaseThingViewHolder, position: Int) {
        if (!isArmedFor(position)) return

        val listener = mArmedNewItemListener
        val firedPosition = position
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
                listener!!.onNewItemBound(firedPosition, holder)
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
            (if (App.isSearching) mDensity * 6 else mDensity * 102).toInt()
        } else {
            StaggeredGridLayoutManager.LayoutParams.WRAP_CONTENT
        }

        cv!!.visibility = if (header) View.INVISIBLE else View.VISIBLE
        val lp = cv.layoutParams as StaggeredGridLayoutManager.LayoutParams
        lp.height = height
        lp.setMargins(mX, mY, mX, mY)
        lp.isFullSpan = header || isFullSpanThingCard(thing)
    }

    private fun distinguishFolder(folder: ThingFolder, cv: CardView?) {
        val mX = mApp!!.resources.getDimensionPixelSize(R.dimen.thing_card_outer_spacing)
        val lp = cv!!.layoutParams as StaggeredGridLayoutManager.LayoutParams
        lp.height = StaggeredGridLayoutManager.LayoutParams.WRAP_CONTENT
        lp.setMargins(mX, mX, mX, mX)
        lp.isFullSpan = folder.cardPresentation.spanMode == ThingFolderCardPresentation.SPAN_FULL
        cv.visibility = View.VISIBLE
    }

    private fun bindFolderCard(
        holder: BaseThingViewHolder,
        entry: ThingListEntry.FolderEntry
    ) {
        val folder = entry.folder
        distinguishFolder(folder, holder.cv)
        resetFolderCardHolder(holder)
        bindFolderCardSurface(holder, folder)
        bindFolderCardContent(holder, entry)
    }

    private fun resetFolderCardHolder(holder: BaseThingViewHolder) {
        removeFolderDynamicViews(holder)
        holder.cv!!.animate().cancel()
        holder.cv.scaleX = 1.0f
        holder.cv.scaleY = 1.0f
        holder.cv.maxCardElevation = mApp!!.resources.getDimension(R.dimen.thing_card_dragging_elevation)
        holder.cv.cardElevation = mApp!!.resources.getDimension(R.dimen.thing_card_normal_elevation)
        holder.cv.setTag(R.id.tag_thing_folder_thumbnail_surface, false)
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
        holder.vBottomStatusSpacer!!.visibility = View.GONE
        holder.llMediaCount!!.visibility = View.GONE
        holder.llInlineMediaAttachment!!.visibility = View.GONE
        holder.llAudioAttachment!!.visibility = View.GONE
        holder.rlReminder!!.visibility = View.GONE
        holder.rlHabit!!.visibility = View.GONE
        holder.flDoing!!.visibility = View.GONE
        holder.vPaddingBottom!!.visibility = View.VISIBLE
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
    }

    private fun bindFolderCardSurface(holder: BaseThingViewHolder, folder: ThingFolder) {
        val background = folder.getBackground()
        val thumbnailMode =
            folder.cardPresentation.mode == ThingFolderCardPresentation.MODE_THUMBNAILS
        if (thumbnailMode) {
            applyThumbnailFolderCardSurface(holder, background, folder.getColor())
        } else {
            holder.cv!!.setTag(R.id.tag_thing_folder_thumbnail_surface, false)
            holder.cv.maxCardElevation = mApp!!.resources.getDimension(R.dimen.thing_card_dragging_elevation)
            holder.cv.cardElevation = mApp!!.resources.getDimension(R.dimen.thing_card_normal_elevation)
            holder.llContent!!.background = null
            BackgroundUtil.applyCardBackground(holder.cv, background)
        }

        val baseColor = background?.representativeColor() ?: folder.getColor()
        holder.cv!!.foreground = ContextCompat.getDrawable(
            mApp!!,
            if (BackgroundUtil.isLight(baseColor))
                R.drawable.selectable_item_background
            else
                R.drawable.selectable_item_background_light
        )
    }

    private fun applyThumbnailFolderCardSurface(
        holder: BaseThingViewHolder,
        background: ThingBackground?,
        fallbackColor: Int
    ) {
        val radius = mApp!!.resources.getDimension(R.dimen.thing_card_corner_radius)
        val transparentCardBackground = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = radius
        }
        holder.cv!!.setTag(R.id.tag_thing_folder_thumbnail_surface, true)
        holder.cv.background = transparentCardBackground
        holder.cv.setCardBackgroundColor(Color.TRANSPARENT)
        holder.cv.maxCardElevation = 0f
        holder.cv.cardElevation = 0f

        val strokeColor = background?.representativeColor() ?: fallbackColor
        val outline = GradientDrawable()
        outline.setColor(Color.TRANSPARENT)
        outline.cornerRadius = radius
        outline.setStroke((mDensity * 1.5f).toInt().coerceAtLeast(1), strokeColor)
        holder.llContent!!.background = outline
    }

    private fun bindFolderCardContent(
        holder: BaseThingViewHolder,
        entry: ThingListEntry.FolderEntry
    ) {
        val folder = entry.folder
        val hiddenPrivate = entry.effectivePrivate && !shouldShowFolderPrivateContent()
        val thumbnailMode =
            folder.cardPresentation.mode == ThingFolderCardPresentation.MODE_THUMBNAILS
        val baseColor = if (thumbnailMode) {
            ContextCompat.getColor(mApp!!, R.color.bg_activity_things)
        } else {
            folder.getBackground()?.representativeColor() ?: folder.getColor()
        }
        val title = if (hiddenPrivate) {
            mApp!!.getString(R.string.private_thing_folder)
        } else {
            folder.title
        }
        bindFolderCardHeader(
            holder,
            title,
            if (hiddenPrivate) R.drawable.ic_locked_big else R.drawable.ic_thing_folder,
            baseColor
        )

        bindFolderCardCount(holder, entry.recursiveThingCount, baseColor)

        if (folder.cardPresentation.mode == ThingFolderCardPresentation.MODE_THUMBNAILS
            && !hiddenPrivate
        ) {
            bindFolderThumbnails(holder, entry)
        }

        if (folder.isSticky()) {
            holder.ivStickyOngoing!!.visibility = View.VISIBLE
            holder.ivStickyOngoing.setImageResource(R.drawable.ic_sticky)
            holder.ivStickyOngoing.contentDescription = mApp!!.getString(R.string.sticky_thing)
            if (thumbnailMode) {
                ImageViewCompat.setImageTintList(
                    holder.ivStickyOngoing,
                    ColorStateList.valueOf(textColorSecondary(baseColor))
                )
            } else {
                tintCardIcon(holder.ivStickyOngoing, baseColor)
            }
        } else {
            holder.ivStickyOngoing!!.visibility = View.GONE
        }
    }

    private fun shouldShowFolderPrivateContent(): Boolean {
        return shouldShowPrivateContent() || mThingManager!!.isCurrentFolderPrivacyAuthenticated()
    }

    private fun bindFolderCardHeader(
        holder: BaseThingViewHolder,
        title: String,
        iconRes: Int,
        baseColor: Int
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
        ImageViewCompat.setImageTintList(
            icon,
            ColorStateList.valueOf(textColorPrimary(baseColor))
        )
        row.addView(icon, LinearLayout.LayoutParams(iconSize, iconSize))

        val titleView = TextView(mApp)
        titleView.text = title
        titleView.maxLines = 2
        titleView.ellipsize = TextUtils.TruncateAt.END
        titleView.includeFontPadding = false
        titleView.textSize = 16f
        titleView.typeface = android.graphics.Typeface.DEFAULT_BOLD
        titleView.setTextColor(textColorPrimary(baseColor))
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
        thingCount: Int,
        baseColor: Int
    ) {
        val container = holder.llTextContent ?: return
        removeFolderCountViews(holder)

        holder.tvContent!!.visibility = View.GONE

        val paddingSide = (mDensity * 16).toInt()
        val countStartPadding = paddingSide + (mDensity * 2).toInt()
        val countView = TextView(mApp)
        countView.tag = FOLDER_COUNT_VIEW_TAG
        countView.setPadding(countStartPadding, (mDensity * 4).toInt(), paddingSide, 0)
        countView.textSize = 11f
        countView.maxLines = 1
        countView.ellipsize = TextUtils.TruncateAt.END
        countView.text = mApp!!.getString(R.string.thing_folder_count, thingCount)
        countView.setTextColor(textColorSecondary(baseColor))
        countView.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val insertIndex = (findFolderHeaderIndex(container) + 1)
            .coerceIn(0, container.childCount)
        container.addView(countView, insertIndex)
    }

    private fun bindFolderThumbnails(
        holder: BaseThingViewHolder,
        entry: ThingListEntry.FolderEntry
    ) {
        val things = entry.thumbnailThings
        if (things.isEmpty()) return

        val container = holder.llTextContent ?: return
        val count = things.size.coerceAtMost(entry.folder.cardPresentation.thumbnailLimit)
        val insertStart = (findFolderCountIndex(container) + 1)
            .coerceIn(0, container.childCount)
        for (i in 0 until count) {
            val thumbnail = createFolderThumbnailView(things[i])
            container.addView(thumbnail, insertStart + i)
        }
    }

    private fun createFolderThumbnailView(thing: Thing): View {
        val hiddenPrivate = thing.isPrivate() && !shouldShowFolderPrivateContent()
        val thingColor = thing.getBackground()?.representativeColor() ?: thing.getColor()
        val horizontalMargin = (mDensity * 16).toInt()
        val verticalMargin = (mDensity * 6).toInt()
        val innerPadding = (mDensity * 10).toInt()

        val view = LinearLayout(mApp)
        view.tag = FOLDER_THUMBNAIL_VIEW_TAG
        view.orientation = LinearLayout.VERTICAL
        view.isClickable = true
        view.setPadding(innerPadding, innerPadding, innerPadding, innerPadding)
        view.setOnClickListener {
            mOnItemTouchedListener?.onFolderThumbnailClick(it, thing)
        }

        val background = GradientDrawable()
        background.cornerRadius = mDensity * 6
        background.setColor(thingColor)
        view.background = background

        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(horizontalMargin, verticalMargin, horizontalMargin, 0)
        view.layoutParams = lp

        val title = TextView(mApp)
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        title.textSize = 13f
        title.setTextColor(textColorPrimary(thingColor))
        title.text = if (hiddenPrivate) {
            mApp!!.getString(R.string.private_thing)
        } else {
            getThumbnailTitle(thing)
        }
        view.addView(title)

        val content = if (hiddenPrivate) "" else thing.content?.trim().orEmpty()
        if (content.isNotEmpty()) {
            val body = TextView(mApp)
            body.ellipsize = TextUtils.TruncateAt.END
            body.maxLines = when {
                content.length > 100 -> 4
                content.length > 40 -> 3
                else -> 2
            }
            body.textSize = 12f
            body.setTextColor(textColorSecondary(thingColor))
            body.text = content
            view.addView(body)
        }

        return view
    }

    private fun getThumbnailTitle(thing: Thing): String {
        val title = thing.title?.trim().orEmpty()
        if (title.isNotEmpty()) return title
        val firstContentLine = thing.content
            ?.lineSequence()
            ?.firstOrNull { it.trim().isNotEmpty() }
            ?.trim()
            .orEmpty()
        return firstContentLine.ifEmpty { mApp!!.getString(R.string.thing) }
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

    private fun animateCardTouchDown(card: CardView) {
        val normalElevation = mApp!!.resources.getDimension(R.dimen.thing_card_normal_elevation)
        val animateElevation = card.getTag(R.id.tag_thing_folder_thumbnail_surface) != true
        card.animate().cancel()
        card.animate()
            .scaleX(CARD_TOUCH_PRESSED_SCALE)
            .scaleY(CARD_TOUCH_PRESSED_SCALE)
            .setDuration(CARD_TOUCH_DOWN_DURATION)
            .start()
        if (animateElevation) {
            ObjectAnimator.ofFloat(
                card, "cardElevation", normalElevation * CARD_TOUCH_PRESSED_ELEVATION_RATIO
            ).setDuration(CARD_TOUCH_DOWN_DURATION).start()
        }
    }

    private fun animateCardTouchRelease(card: CardView) {
        val normalElevation = mApp!!.resources.getDimension(R.dimen.thing_card_normal_elevation)
        val animateElevation = card.getTag(R.id.tag_thing_folder_thumbnail_surface) != true
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
                if (animateElevation) {
                    ObjectAnimator.ofFloat(card, "cardElevation", normalElevation)
                        .setDuration(CARD_TOUCH_SETTLE_DURATION)
                        .start()
                } else {
                    card.cardElevation = 0f
                }
            }
            .start()
        if (animateElevation) {
            ObjectAnimator.ofFloat(
                card, "cardElevation", normalElevation * CARD_TOUCH_OVERSHOOT_SCALE
            ).setDuration(CARD_TOUCH_RELEASE_DURATION).start()
        } else {
            card.cardElevation = 0f
        }
    }

    private fun playAppearingAnimation(v: View, position: Int) {
        v.visibility = View.INVISIBLE
        if (getItemViewType(position) != Thing.HEADER) {
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
            }, position * 30L)
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
                    val thingPos = holder.adapterPosition
                    if (thingPos == -1) return
                    ThingManager.getInstance(mApp)!!.update(typeBefore, thing, thingPos, false)
                    notifyItemChanged(thingPos)
                    val thingId = thing.id
                    val thingType = thing.type
                    AppWidgetHelper.updateSingleThingAppWidgets(mApp, thingId)
                    AppWidgetHelper.updateThingsListAppWidgetsForType(mApp, thingType)
                    SystemNotificationUtil.cancelNotification(thingId, thingType, mApp)
                }

                override fun onItemSpaceClick(v: View?) {
                    if (mOnItemTouchedListener != null) {
                        mOnItemTouchedListener!!.onItemClick(v, holder.adapterPosition)
                    }
                }
            })
        }
    }

    interface OnItemTouchedListener {
        fun onItemTouch(v: View?, event: MotionEvent?): Boolean
        fun onItemClick(v: View?, position: Int)
        fun onItemLongClick(v: View?, position: Int): Boolean
        fun onFolderThumbnailClick(v: View?, thing: Thing)
    }

    private inner class ThingViewHolder(item: View?) : BaseThingViewHolder(item) {

        init {
            if (mOnItemTouchedListener != null) {
                cv!!.setOnTouchListener(mOnTouchListener)
                cv.setOnClickListener { v ->
                    mOnItemTouchedListener!!.onItemClick(v, adapterPosition)
                }
                cv.setOnLongClickListener { v ->
                    mOnItemTouchedListener!!.onItemLongClick(v, adapterPosition)
                }
            }
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
    }
}
