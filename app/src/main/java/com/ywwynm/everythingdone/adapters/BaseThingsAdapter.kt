@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.adapters

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.collection.LongSparseArray
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import android.util.Log
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView

import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.VideoDecoder
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.signature.ObjectKey
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.FrequentSettings
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.database.HabitDAO
import com.ywwynm.everythingdone.database.ReminderDAO
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.helpers.CheckListHelper
import com.ywwynm.everythingdone.helpers.DebugFileLogger
import com.ywwynm.everythingdone.helpers.MediaCropBitmapRenderer
import com.ywwynm.everythingdone.helpers.MediaCropTransformation
import com.ywwynm.everythingdone.helpers.ThingCardMediaHelper
import com.ywwynm.everythingdone.managers.ModeManager
import com.ywwynm.everythingdone.model.Habit
import com.ywwynm.everythingdone.model.Reminder
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingCardAppearance
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.DateTimeUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.views.HabitRecordPresenter
import com.ywwynm.everythingdone.views.InterceptTouchCardView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Created by ywwynm on 2016/7/31.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Basic things adapter for RecyclerView. Created for re-use.
 */
abstract class BaseThingsAdapter(context: Context?) :
    RecyclerView.Adapter<BaseThingsAdapter.BaseThingViewHolder>() {

    @JvmField
    protected var mInflater: LayoutInflater? = LayoutInflater.from(context)
    @JvmField
    protected var mDensity: Float = DisplayUtil.getScreenDensity(context)

    private var mContext: Context? = context

    private var mCheckListAdapters: LongSparseArray<CheckListAdapter?>? = LongSparseArray()
    private var mRecyclerView: RecyclerView? = null

    private var mReminderDAO: ReminderDAO? = ReminderDAO.getInstance(context)
    private var mHabitDAO: HabitDAO? = HabitDAO.getInstance(context)

    private var mImageRequestManager: RequestManager? = Glide.with(context!!)
    private val mLoadedThingCardImageKeys: MutableSet<String> = HashSet()
    private val mThingCardMediaBitmapCache: LruCache<String, Bitmap> =
        object : LruCache<String, Bitmap>(getThingCardMediaBitmapCacheMaxBytes(context)) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }

    private var mCardWidth: Int = DisplayUtil.getThingCardWidth(context)
    private var mFullSpanCardWidth: Int = DisplayUtil.getThingCardWidth(context)
    private var mThingCardSurfaceAvailableHeightOverride: Int = 0
    private var mShouldShowPrivateContent: Boolean = false
    private var mChecklistMaxItemCount: Int = 8
    private var mAnimatedPlaybackEnabled: Boolean = true

    private data class ThingCardMediaDebugInfo(
        val thingId: Long,
        val titlePreview: String,
        val contentPreview: String,
        val placementName: String,
        val mediaSourceName: String,
        val mediaPath: String,
        val isVideo: Boolean
    ) {
        fun identity(): String {
            return "thingId=$thingId placement=$placementName " +
                    "video=$isVideo media=$mediaSourceName path=$mediaPath " +
                    "title=\"$titlePreview\" content=\"$contentPreview\""
        }
    }

    protected abstract fun getCurrentMode(): Int
    protected abstract fun getThings(): List<Thing?>?

    protected open fun getThingAt(position: Int): Thing? {
        return getThings()!![position]
    }

    protected open fun getEntryCount(): Int {
        return getThings()!!.size
    }

    protected open fun isFullSpanThingCard(thing: Thing): Boolean = false

    /**
     * Pick a text/foreground colour to draw on top of a card whose background
     * is `thingColor`. Always luminance-adaptive: black-side on light
     * cards, white-side on dark cards.
     */
    protected open fun textColorPrimary(thingColor: Int): Int {
        return if (BackgroundUtil.isLight(thingColor)) black_86p else white_86p
    }

    protected open fun textColorSecondary(thingColor: Int): Int {
        return if (BackgroundUtil.isLight(thingColor)) black_76p else white_76p
    }

    protected open fun textColorTertiary(thingColor: Int): Int {
        return if (BackgroundUtil.isLight(thingColor)) black_66p else white_66p
    }

    protected open fun textColorDisabled(thingColor: Int): Int {
        return if (BackgroundUtil.isLight(thingColor)) black_54p else white_54p
    }

    /** Phase 8+: tint a single-tone white card ImageView to black on light backgrounds. */
    protected open fun tintCardIcon(iv: ImageView?, thingColor: Int) {
        if (iv == null) return
        androidx.core.widget.ImageViewCompat.setImageTintList(
            iv,
            if (BackgroundUtil.isLight(thingColor))
                android.content.res.ColorStateList.valueOf(android.graphics.Color.BLACK)
            else null
        )
    }

    /** Phase 8+: tint a dashed-line separator drawable to mirror-opacity black on light backgrounds. */
    protected open fun tintCardSeparator(separator: View?, thingColor: Int) {
        if (separator == null) return
        val d: Drawable = separator.background ?: return
        val color = if (BackgroundUtil.isLight(thingColor))
            0x89000000.toInt()   // 53% black
        else 0x89FFFFFF.toInt()  // 53% white (original)
        d.mutate().setColorFilter(color, PorterDuff.Mode.SRC_IN)
    }

    open fun setCardWidth(cardWidth: Int) {
        mCardWidth = cardWidth
        mFullSpanCardWidth = cardWidth
    }

    open fun setFullSpanCardWidth(cardWidth: Int) {
        mFullSpanCardWidth = cardWidth
    }

    open fun setThingCardSurfaceAvailableHeight(height: Int) {
        mThingCardSurfaceAvailableHeightOverride = max(0, height)
    }

    open fun setShouldShowPrivateContent(shouldShowPrivateContent: Boolean) {
        mShouldShowPrivateContent = shouldShowPrivateContent
    }

    /**
     * When false, Animated Image sources render a single static frame instead of
     * playing. Used by the widget configuration preview to mirror the
     * non-animating placed RemoteViews widget. See ADR-0007.
     */
    open fun setAnimatedPlaybackEnabled(enabled: Boolean) {
        mAnimatedPlaybackEnabled = enabled
    }

    open fun shouldShowPrivateContent(): Boolean {
        return mShouldShowPrivateContent
    }

    open fun setChecklistMaxItemCount(checklistMaxItemCount: Int) {
        mChecklistMaxItemCount = checklistMaxItemCount
    }

    protected fun getBoundNormalThingCardWidth(): Int = mCardWidth

    protected fun getBoundFullSpanThingCardWidth(): Int = mFullSpanCardWidth

    /**
     * The current home list column count (StaggeredGridLayoutManager span), or 0
     * when no staggered layout manager is attached yet. Read live so callers stay
     * correct across orientation changes, which update the span and rebind.
     */
    protected fun getBoundListSpanCount(): Int =
        (mRecyclerView?.layoutManager as? StaggeredGridLayoutManager)?.spanCount ?: 0

    protected open fun getThingCardTitleTextSize(
        thing: Thing,
        fullSpan: Boolean
    ): Float = 16f

    protected open fun getThingCardContentMaxLines(
        thing: Thing,
        fullSpan: Boolean
    ): Int {
        return if (fullSpan) FULL_SPAN_TEXT_MAX_LINES else NORMAL_TEXT_MAX_LINES
    }

    protected open fun getThingCardContentTextSize(
        thing: Thing,
        content: String,
        defaultTextSizeSp: Float
    ): Float = defaultTextSizeSp

    protected open fun getThingCardChecklistMaxItemCount(
        thing: Thing,
        fullSpan: Boolean
    ): Int {
        return if (fullSpan) FULL_SPAN_CHECKLIST_MAX_ITEM_COUNT else mChecklistMaxItemCount
    }

    protected open fun getThingCardChecklistTextSize(
        thing: Thing,
        fullSpan: Boolean
    ): Float? = null

    protected open fun getThingCardHabitSummaryTextSize(thing: Thing): Float? = null

    protected open fun shouldShowThingCardHabitDetails(thing: Thing): Boolean = true

    protected open fun getThingCardMediaBitmapCache(): LruCache<String, Bitmap> =
        mThingCardMediaBitmapCache

    protected open fun shouldLogThingCardMediaDebug(
        thing: Thing,
        mediaSource: ThingCardMediaHelper.MediaSource,
        @Thing.ThingCardImagePlacement placement: Int
    ): Boolean = false

    private fun createThingCardMediaDebugInfo(
        thing: Thing,
        mediaSource: ThingCardMediaHelper.MediaSource,
        @Thing.ThingCardImagePlacement placement: Int
    ): ThingCardMediaDebugInfo {
        return ThingCardMediaDebugInfo(
            thingId = thing.id,
            titlePreview = previewThingCardDebugText(thing.title),
            contentPreview = previewThingCardDebugText(thing.content),
            placementName = thingCardImagePlacementName(placement),
            mediaSourceName = mediaSource.typePathName,
            mediaPath = mediaSource.pathName,
            isVideo = mediaSource.isVideo
        )
    }

    private fun previewThingCardDebugText(value: String?): String {
        val normalized = value
            ?.replace('\n', ' ')
            ?.replace('\r', ' ')
            ?.replace('\t', ' ')
            ?.trim()
            .orEmpty()
        return if (normalized.length <= DEBUG_THING_CARD_TEXT_PREVIEW_LENGTH) {
            normalized
        } else {
            normalized.take(DEBUG_THING_CARD_TEXT_PREVIEW_LENGTH) + "..."
        }
    }

    private fun thingCardImagePlacementName(
        @Thing.ThingCardImagePlacement placement: Int
    ): String {
        return when (placement) {
            Thing.THING_CARD_IMAGE_PLACEMENT_TOP -> "top"
            Thing.THING_CARD_IMAGE_PLACEMENT_BOTTOM -> "bottom"
            Thing.THING_CARD_IMAGE_PLACEMENT_LEFT -> "left"
            Thing.THING_CARD_IMAGE_PLACEMENT_RIGHT -> "right"
            else -> "unknown:$placement"
        }
    }

    private fun setThingCardMediaDebugInfo(
        holder: BaseThingViewHolder,
        debugInfo: ThingCardMediaDebugInfo?
    ) {
        holder.ivImageAttachment?.setTag(R.id.tag_thing_card_media_debug_info, debugInfo)
    }

    private fun getThingCardMediaDebugInfo(imageView: ImageView?): ThingCardMediaDebugInfo? {
        return imageView?.getTag(R.id.tag_thing_card_media_debug_info)
                as? ThingCardMediaDebugInfo
    }

    private fun logThingCardMediaDebug(
        debugInfo: ThingCardMediaDebugInfo?,
        message: String
    ) {
        if (debugInfo == null) return
        Log.i(TAG, "$DEBUG_THING_FOLDER_VIDEO_CROP_PREFIX $message ${debugInfo.identity()}")
    }

    private fun debugViewSize(view: View?): String {
        return if (view == null) {
            "null"
        } else {
            "${view.width}x${view.height}"
        }
    }

    private fun debugLayoutParamsSize(view: View?): String {
        val layoutParams = view?.layoutParams ?: return "null"
        return "${layoutParams.width}x${layoutParams.height}"
    }

    private fun debugDrawableSize(drawable: Drawable?): String {
        return if (drawable == null) {
            "null"
        } else {
            "${drawable.intrinsicWidth}x${drawable.intrinsicHeight}:${drawable.javaClass.simpleName}"
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        mRecyclerView = recyclerView
        refreshCardWidthFromRecyclerView()
    }

    fun setHostRecyclerViewForDelegatedBinding(recyclerView: RecyclerView?) {
        mRecyclerView = recyclerView
        refreshCardWidthFromRecyclerView()
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        if (mRecyclerView === recyclerView) {
            mRecyclerView = null
        }
        super.onDetachedFromRecyclerView(recyclerView)
    }

    private fun refreshCardWidthFromRecyclerView() {
        val recyclerView = mRecyclerView ?: return
        val width = recyclerView.width - recyclerView.paddingLeft - recyclerView.paddingRight
        if (width <= 0) return

        val layoutManager = recyclerView.layoutManager as? StaggeredGridLayoutManager ?: return
        val spanCount = layoutManager.spanCount
        if (spanCount <= 0) return

        val spacing = mContext!!.resources.getDimensionPixelSize(R.dimen.thing_card_outer_spacing)
        // width already excludes RecyclerView padding, so only subtract item margins here.
        val cardWidth = (width - spacing * 2 * spanCount) / spanCount
        if (cardWidth > 0) {
            mCardWidth = cardWidth
        }
        val fullSpanCardWidth = width - spacing * 2
        if (fullSpanCardWidth > 0) {
            mFullSpanCardWidth = fullSpanCardWidth
        }
    }

    private fun getCardContentWidth(thing: Thing): Int {
        return if (isFullSpanThingCard(thing)) mFullSpanCardWidth else mCardWidth
    }

    private fun shouldUseFixedCardContentWidth(thing: Thing): Boolean {
        return isFullSpanThingCard(thing)
                || isThingEffectivelyPrivate(thing) && !mShouldShowPrivateContent
                || ThingCardMediaHelper.resolveEffectiveMediaSource(thing) != null
    }

    private fun shouldUseMediaBackgroundForeground(thing: Thing): Boolean {
        return thing.thingCardAppearance.mediaBackgroundEnabled
                && ThingCardMediaHelper.resolveEffectiveMediaSource(thing) != null
                && !(isThingEffectivelyPrivate(thing) && !mShouldShowPrivateContent)
    }

    private fun getThingCardForegroundBaseColor(thing: Thing): Int {
        return if (shouldUseMediaBackgroundForeground(thing)) Color.BLACK else thing.getColor()
    }

    private fun applyCardContentGeometry(holder: BaseThingViewHolder, thing: Thing) {
        val fullSpan = isFullSpanThingCard(thing)
        holder.llContent!!.orientation = LinearLayout.VERTICAL
        val lp = holder.llContent!!.layoutParams
        val fixedWidth = shouldUseFixedCardContentWidth(thing)
        lp.width = if (fixedWidth) {
            getCardContentWidth(thing)
        } else {
            ViewGroup.LayoutParams.WRAP_CONTENT
        }
        holder.llContent.layoutParams = lp
        holder.llContent.minimumWidth = 0
        holder.llContent.minimumHeight = 0

        val textLp = holder.llTextContent!!.layoutParams as LinearLayout.LayoutParams
        textLp.width = if (fixedWidth) {
            ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            ViewGroup.LayoutParams.WRAP_CONTENT
        }
        textLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        textLp.weight = 0f
        holder.llTextContent.layoutParams = textLp
        holder.llTextContent.minimumHeight = 0
        holder.vBottomStatusSpacer!!.visibility = View.GONE
        val spacerLp = holder.vBottomStatusSpacer.layoutParams as LinearLayout.LayoutParams
        spacerLp.height = 0
        spacerLp.weight = 0f
        holder.vBottomStatusSpacer.layoutParams = spacerLp
        holder.llMediaCount!!.visibility = View.GONE
        holder.llInlineMediaAttachment!!.visibility = View.GONE
        setThingCardPaddingBottomHeight(holder, THING_CARD_DEFAULT_PADDING_BOTTOM_DP)

        val imageLp = holder.flImageAttachment!!.layoutParams as LinearLayout.LayoutParams
        imageLp.width = ViewGroup.LayoutParams.MATCH_PARENT
        imageLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        imageLp.weight = 0f
        holder.flImageAttachment.layoutParams = imageLp

        holder.tvContent!!.maxLines = getThingCardContentMaxLines(thing, fullSpan)

        val iconSize = if (fullSpan) {
            mContext!!.resources.getDimensionPixelSize(R.dimen.thing_card_full_span_private_icon_size)
        } else {
            (mDensity * PRIVATE_THING_ICON_NORMAL_DP).toInt()
        }
        val iconLp = holder.ivPrivateThing!!.layoutParams as LinearLayout.LayoutParams
        iconLp.width = iconSize
        iconLp.height = iconSize
        holder.ivPrivateThing.layoutParams = iconLp
    }

    private fun setNormalCardGeometry(cv: CardView) {
        val oldToken = cv.getTag(R.id.tag_thing_card_moving_scale_recovery_token)
        if (oldToken != null || cv.scaleX != 1.0f || cv.scaleY != 1.0f) {
            logCardScaleRecoveryDebug(
                "normal-geometry view=${System.identityHashCode(cv)} " +
                    "oldToken=${System.identityHashCode(oldToken)} " +
                    "finger=${cv.getTag(R.id.tag_thing_card_finger_down)} " +
                    "drag=${cv.getTag(R.id.tag_thing_card_drag_active)} " +
                    "scale=${cv.scaleX}/${cv.scaleY}"
            )
        }
        cv.setTag(R.id.tag_thing_card_moving_scale_recovery_token, null)
        cv.animate().cancel()
        cv.scaleX = 1.0f
        cv.scaleY = 1.0f
        cv.cardElevation = mContext!!.resources.getDimension(R.dimen.thing_card_normal_elevation)
    }

    protected fun scheduleMovingCardScaleRecoveryIfReleased(cv: CardView, source: String) {
        val token = Any()
        cv.setTag(R.id.tag_thing_card_moving_scale_recovery_token, token)
        val tokenId = System.identityHashCode(token)
        logCardScaleRecoveryDebug(
            "schedule source=$source view=${System.identityHashCode(cv)} " +
                "token=$tokenId attached=${cv.isAttachedToWindow} " +
                "finger=${cv.getTag(R.id.tag_thing_card_finger_down)} " +
                "drag=${cv.getTag(R.id.tag_thing_card_drag_active)} " +
                "scale=${cv.scaleX}/${cv.scaleY}"
        )
        cv.postDelayed({
            if (!cv.isAttachedToWindow) {
                logCardScaleRecoveryDebug(
                    "check-detached source=$source view=${System.identityHashCode(cv)} " +
                        "token=$tokenId"
                )
                return@postDelayed
            }
            val currentToken = cv.getTag(R.id.tag_thing_card_moving_scale_recovery_token)
            if (currentToken !== token) {
                logCardScaleRecoveryDebug(
                    "check-stale source=$source view=${System.identityHashCode(cv)} " +
                        "token=$tokenId currentToken=${System.identityHashCode(currentToken)} " +
                        "finger=${cv.getTag(R.id.tag_thing_card_finger_down)} " +
                        "drag=${cv.getTag(R.id.tag_thing_card_drag_active)} " +
                        "scale=${cv.scaleX}/${cv.scaleY}"
                )
                return@postDelayed
            }
            val fingerDown = cv.getTag(R.id.tag_thing_card_finger_down) == true
            val dragActive = cv.getTag(R.id.tag_thing_card_drag_active) == true
            val stillEnlarged = cv.scaleX > 1.0f + MOVING_SCALE_RECOVERY_EPSILON ||
                cv.scaleY > 1.0f + MOVING_SCALE_RECOVERY_EPSILON
            logCardScaleRecoveryDebug(
                "check source=$source view=${System.identityHashCode(cv)} " +
                    "token=$tokenId fingerDown=$fingerDown " +
                    "dragActive=$dragActive stillEnlarged=$stillEnlarged " +
                    "scale=${cv.scaleX}/${cv.scaleY}"
            )
            if (!fingerDown && stillEnlarged) {
                logCardScaleRecoveryDebug(
                    "recover source=$source view=${System.identityHashCode(cv)} " +
                        "token=$tokenId scale=${cv.scaleX}/${cv.scaleY}"
                )
                ObjectAnimator.ofFloat(cv, "scaleX", 1.0f)
                    .setDuration(MOVING_SCALE_RECOVERY_DURATION)
                    .start()
                ObjectAnimator.ofFloat(cv, "scaleY", 1.0f)
                    .setDuration(MOVING_SCALE_RECOVERY_DURATION)
                    .start()
            }
        }, MOVING_SCALE_RECOVERY_CHECK_DELAY)
    }

    protected open fun shouldDimUnselectedContent(currentMode: Int): Boolean {
        return false
    }

    protected open fun isThingEffectivelyPrivate(thing: Thing): Boolean {
        return thing.isPrivate()
    }

    private fun applyUnselectedContentAlpha(
        holder: BaseThingViewHolder,
        background: ThingBackground?,
        dim: Boolean
    ) {
        holder.llContent!!.alpha = 1.0f
        holder.flImageAttachment!!.alpha = 1.0f
        holder.ivImageAttachment!!.alpha = 1.0f
        holder.vImageCover!!.alpha = 1.0f
        holder.pbLoading!!.alpha = 1.0f

        val adaptiveAlpha = if (dim) {
            if (background != null && BackgroundUtil.isLight(background)) {
                UNSELECTED_DARK_CONTENT_ALPHA
            } else {
                UNSELECTED_LIGHT_CONTENT_ALPHA
            }
        } else {
            1.0f
        }

        holder.tvTitle!!.alpha = adaptiveAlpha
        holder.tvImageCount!!.alpha = adaptiveAlpha
        holder.llMediaCount!!.alpha = adaptiveAlpha
        holder.llInlineMediaAttachment!!.alpha = adaptiveAlpha
        holder.ivMediaBackground!!.alpha = adaptiveAlpha
        holder.ivPrivateThing!!.alpha = adaptiveAlpha
        holder.tvContent!!.alpha = adaptiveAlpha
        holder.rvChecklist!!.alpha = adaptiveAlpha
        holder.llAudioAttachment!!.alpha = adaptiveAlpha
        holder.rlReminder!!.alpha = adaptiveAlpha
        holder.rlHabit!!.alpha = adaptiveAlpha
        holder.ivStickyOngoing!!.alpha = adaptiveAlpha
        // "正在做"蒙层（含其内图标 / 文字）在未选中态做轻微淡化以区分选中态；但只能轻微淡，
        // 否则蒙层会盖不住下层内容，故用比普通内容弱得多的专用透明度。
        holder.flDoing!!.alpha = if (dim) UNSELECTED_DOING_COVER_ALPHA else 1.0f
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseThingViewHolder {
        return BaseThingViewHolder(mInflater!!.inflate(R.layout.card_thing, parent, false))
    }

    override fun onBindViewHolder(holder: BaseThingViewHolder, position: Int) {
        refreshCardWidthFromRecyclerView()

        val thing: Thing = getThingAt(position)!!
        setContentViewAppearance(holder, thing)
        setCardAppearance(holder, thing.getBackground(), thing.isSelected())
    }

    private fun setContentViewAppearance(holder: BaseThingViewHolder, thing: Thing) {
        applyCardContentGeometry(holder, thing)

        updateCardForStickyOrOngoingNotification(holder, thing)
        updateCardForTitle(holder, thing)

        if (isThingEffectivelyPrivate(thing) && !mShouldShowPrivateContent) {
            holder.cv!!.setShouldInterceptTouchEvent(true)
            holder.ivPrivateThing!!.visibility = View.VISIBLE
            androidx.core.widget.ImageViewCompat.setImageTintList(
                holder.ivPrivateThing,
                if (BackgroundUtil.isLight(getThingCardForegroundBaseColor(thing)))
                    android.content.res.ColorStateList.valueOf(android.graphics.Color.BLACK)
                else null
            )
            holder.flImageAttachment!!.visibility = View.GONE
            hideThingCardMediaBackground(holder)
            holder.tvContent!!.visibility = View.GONE
            holder.rvChecklist!!.visibility = View.GONE
            holder.llAudioAttachment!!.visibility = View.GONE
            holder.rlReminder!!.visibility = View.GONE
            holder.rlHabit!!.visibility = View.GONE
            holder.vPaddingBottom!!.visibility = View.VISIBLE
            updateFullSpanSparseMinHeight(holder, thing)
        } else {
            holder.ivPrivateThing!!.visibility = View.GONE

            updateCardForContent(holder, thing)
            updateCardForReminder(holder, thing)
            updateCardForHabit(holder, thing)
            updateCardForAudioAttachment(holder, thing)
            updateCardForImageAttachment(holder, thing)

            updateCardSeparatorsIfNeeded(holder)

            enlargeHiddenMediaCountLayoutIfNeeded(holder)
            enlargeAudioLayoutIfNeeded(holder)
            updateFullSpanSparseMinHeight(holder, thing)
        }

        updateCardForDoing(holder, thing)
    }

    private fun updateCardForStickyOrOngoingNotification(holder: BaseThingViewHolder, thing: Thing) {
        val sticky = thing.location < 0
        val ongoing = FrequentSettings.getLong(Def.Meta.KEY_ONGOING_THING_ID) == thing.id
        if (!sticky && !ongoing) {
            holder.ivStickyOngoing!!.visibility = View.GONE
        } else {
            holder.ivStickyOngoing!!.visibility = View.VISIBLE
            if (getCurrentMode() != ModeManager.NORMAL && !thing.isSelected()) {
                holder.ivStickyOngoing.setImageResource(
                    if (sticky)
                        R.drawable.ic_sticky_not_selected
                    else R.drawable.ic_ongoing_notication_not_selected
                )
            } else {
                holder.ivStickyOngoing.setImageResource(
                    if (sticky) R.drawable.ic_sticky else R.drawable.ic_ongoing_notication
                )
            }
            @StringRes val cdRes = if (sticky) R.string.sticky_thing else R.string.ongoing_thing
            holder.ivStickyOngoing.contentDescription = mContext!!.getString(cdRes)
            tintThingStickyOngoingIcon(
                holder.ivStickyOngoing,
                thing,
                getThingCardForegroundBaseColor(thing)
            )
        }
    }

    private fun tintThingStickyOngoingIcon(
        icon: ImageView?,
        thing: Thing,
        foregroundBaseColor: Int
    ) {
        if (icon == null) return
        if (thing.location < 0 && thing.folderId == null) {
            icon.setImageDrawable(
                BackgroundUtil.tintDrawable(
                    mContext!!.resources, icon.drawable, App.defaultAccentBackground
                )
            )
            return
        }
        if (thing.location < 0) {
            val background = getStickyThingParentFolderBackground(thing)
            if (background != null) {
                androidx.core.widget.ImageViewCompat.setImageTintList(icon, null)
                icon.setImageDrawable(
                    BackgroundUtil.tintDrawable(mContext!!.resources, icon.drawable, background)
                )
                return
            }
        }
        tintCardIcon(icon, foregroundBaseColor)
    }

    protected open fun getStickyThingParentFolderBackground(thing: Thing): ThingBackground? {
        return null
    }

    private fun updateCardForTitle(holder: BaseThingViewHolder, thing: Thing) {
        val title: String = thing.getTitleToDisplay()!!
        if (!title.isEmpty()) {
            val p = (mDensity * 16).toInt()
            holder.tvTitle!!.visibility = View.VISIBLE
            holder.tvTitle.setPadding(p, p, p, 0)
            holder.tvTitle.text = title
            holder.tvTitle.textSize = getThingCardTitleTextSize(thing, isFullSpanThingCard(thing))
            holder.tvTitle.setTextColor(textColorPrimary(getThingCardForegroundBaseColor(thing)))
        } else {
            holder.tvTitle!!.visibility = View.GONE
        }
    }

    @SuppressLint("NewApi")
    private fun updateCardForContent(holder: BaseThingViewHolder, thing: Thing) {
        val p = (mDensity * 16).toInt()
        val content: String = thing.content!!
        if (!content.isEmpty()) {
            if (!CheckListHelper.isCheckListStr(content)) {
                holder.rvChecklist!!.visibility = View.GONE
                holder.tvContent!!.visibility = View.VISIBLE

                val length = content.length
                val defaultTextSizeSp = if (length <= 60) {
                    -0.14f * length + 24.14f
                } else {
                    16f
                }
                holder.tvContent.textSize = getThingCardContentTextSize(
                    thing,
                    content,
                    defaultTextSizeSp
                )

                holder.tvContent.setPadding(p, p, p, 0)
                holder.tvContent.text = content
                holder.tvContent.setTextColor(
                    textColorSecondary(getThingCardForegroundBaseColor(thing))
                )
            } else {
                holder.tvContent!!.visibility = View.GONE
                holder.rvChecklist!!.visibility = View.VISIBLE

                val id = thing.id
                val items: MutableList<String?> = CheckListHelper.toCheckListItems(content, false)
                var adapter: CheckListAdapter? = mCheckListAdapters!!.get(id)
                if (adapter == null) {
                    adapter = CheckListAdapter(mContext,
                        CheckListAdapter.TEXTVIEW, items)
                    mCheckListAdapters!!.put(id, adapter)
                } else {
                    adapter.setItems(items)
                }
                adapter.setThingColor(getThingCardForegroundBaseColor(thing))
                val fullSpan = isFullSpanThingCard(thing)
                adapter.setMaxItemCount(getThingCardChecklistMaxItemCount(thing, fullSpan))
                adapter.setFixedTextSize(getThingCardChecklistTextSize(thing, fullSpan))
                onChecklistAdapterInitialized(holder, adapter, thing)
                holder.rvChecklist.adapter = adapter
                holder.rvChecklist.layoutManager = LinearLayoutManager(mContext)

                val rp = (mDensity * 6).toInt()
                holder.rvChecklist.setPaddingRelative(rp, p, p, 0)
            }
        } else {
            holder.tvContent!!.visibility = View.GONE
            holder.rvChecklist!!.visibility = View.GONE
        }
    }

    protected open fun onChecklistAdapterInitialized(
        holder: BaseThingViewHolder, adapter: CheckListAdapter, thing: Thing
    ) {
        // do nothing here
    }

    private fun updateCardForReminder(holder: BaseThingViewHolder, thing: Thing) {
        val thingType = thing.type
        if (!Thing.isReminderType(thingType)) {
            holder.rlReminder!!.visibility = View.GONE
            return
        }

        val reminder: Reminder? = mReminderDAO!!.getReminderById(thing.id)
        if (reminder == null) {
            holder.rlReminder!!.visibility = View.GONE
            return
        }

        val p = (mDensity * 16).toInt()
        holder.rlReminder!!.visibility = View.VISIBLE
        holder.rlReminder.setPadding(p, p, p, 0)

        val params = holder.ivReminder!!.layoutParams as RelativeLayout.LayoutParams
        if (thingType == Thing.REMINDER) {
            params.setMargins(0, (mDensity * 2).toInt(), 0, 0)
            holder.ivReminder.setImageResource(R.drawable.card_reminder)
            holder.ivReminder.contentDescription = mContext!!.getString(R.string.reminder)
            holder.tvReminderTime!!.textSize = 12f

            holder.tvReminderTime.text =
                DateTimeUtil.getDateTimeStrReminder(mContext, thing, reminder)
        } else {
            params.setMargins(0, (mDensity * 1.6).toInt(), 0, 0)
            holder.ivReminder.setImageResource(R.drawable.card_goal)
            holder.ivReminder.contentDescription = mContext!!.getString(R.string.goal)
            holder.tvReminderTime!!.textSize = 16f

            holder.tvReminderTime.text =
                DateTimeUtil.getDateTimeStrGoal(mContext, thing, reminder)
        }
        val foregroundBaseColor = getThingCardForegroundBaseColor(thing)
        holder.tvReminderTime.setTextColor(textColorTertiary(foregroundBaseColor))
        tintCardIcon(holder.ivReminder, foregroundBaseColor)
        tintCardSeparator(holder.vReminderSeparator, foregroundBaseColor)
    }

    @SuppressLint("SetTextI18n")
    private fun updateCardForHabit(holder: BaseThingViewHolder, thing: Thing) {
        if (thing.type != Thing.HABIT) {
            holder.rlHabit!!.visibility = View.GONE
            return
        }

        val habit: Habit? = mHabitDAO!!.getHabitById(thing.id)
        if (habit == null) {
            holder.rlHabit!!.visibility = View.GONE
            return
        }

        val p = (mDensity * 16).toInt()
        holder.rlHabit!!.visibility = View.VISIBLE
        holder.rlHabit.setPadding(p, p, p, 0)

        var summary: String = habit.getSummary(mContext)!!
        if (thing.state == Thing.UNDERWAY && habit.isPaused()) {
            summary += ", " + habit.getStateDescription(mContext)
        }
        holder.tvHabitSummary!!.text = summary
        getThingCardHabitSummaryTextSize(thing)?.let {
            holder.tvHabitSummary.textSize = it
        }
        val foregroundBaseColor = getThingCardForegroundBaseColor(thing)
        holder.tvHabitSummary.setTextColor(textColorTertiary(foregroundBaseColor))
        holder.tvHabitNextReminder!!.setTextColor(textColorDisabled(foregroundBaseColor))
        holder.tvHabitLastFive!!.setTextColor(textColorDisabled(foregroundBaseColor))
        holder.tvHabitFinishedThisT!!.setTextColor(textColorTertiary(foregroundBaseColor))
        tintCardIcon(holder.ivHabit, foregroundBaseColor)
        tintCardSeparator(holder.vHabitSeparator1, foregroundBaseColor)
        tintCardSeparator(holder.vHabitSeparator2, foregroundBaseColor)
        holder.habitRecordPresenter.setThingColor(foregroundBaseColor)

        if (!shouldShowThingCardHabitDetails(thing)) {
            holder.tvHabitNextReminder.visibility = View.GONE
            holder.vHabitSeparator2!!.visibility = View.GONE
            holder.tvHabitLastFive.visibility = View.GONE
            holder.llHabitRecord!!.visibility = View.GONE
            holder.tvHabitFinishedThisT.visibility = View.GONE
            return
        }

        if (thing.state == Thing.UNDERWAY && !habit.isPaused()) {
            holder.tvHabitNextReminder.visibility = View.VISIBLE
            holder.vHabitSeparator2!!.visibility = View.VISIBLE
            holder.tvHabitLastFive.visibility = View.VISIBLE
            holder.llHabitRecord!!.visibility = View.VISIBLE
            holder.tvHabitFinishedThisT.visibility = View.VISIBLE

            val next = mContext!!.getString(R.string.habit_next_reminder)
            holder.tvHabitNextReminder.text =
                next + " " + habit.getNextReminderDescription(mContext)

            val record: String = habit.record!!
            val lastFive: StringBuilder
            val len = record.length
            if (len >= 5) {
                lastFive = StringBuilder(record.substring(len - 5, len))
            } else {
                lastFive = StringBuilder(record)
                for (i in 0 until 5 - len) {
                    lastFive.append("?")
                }
            }
            holder.habitRecordPresenter.setRecord(lastFive.toString())

            holder.tvHabitFinishedThisT.text = habit.getFinishedTimesThisTStr(mContext)
        } else {
            holder.tvHabitNextReminder.visibility = View.GONE
            holder.vHabitSeparator2!!.visibility = View.GONE
            holder.tvHabitLastFive.visibility = View.GONE
            holder.llHabitRecord!!.visibility = View.GONE
            holder.tvHabitFinishedThisT.visibility = View.GONE
        }
    }

    private fun getEffectiveThingCardImagePlacement(thing: Thing): Int {
        val savedPlacement = thing.thingCardAppearance.imagePlacement
        val placement = when (savedPlacement) {
            Thing.THING_CARD_IMAGE_PLACEMENT_TOP,
            Thing.THING_CARD_IMAGE_PLACEMENT_BOTTOM,
            Thing.THING_CARD_IMAGE_PLACEMENT_LEFT,
            Thing.THING_CARD_IMAGE_PLACEMENT_RIGHT -> savedPlacement
            else -> Thing.THING_CARD_IMAGE_PLACEMENT_TOP
        }
        if (!isFullSpanThingCard(thing)
            && (placement == Thing.THING_CARD_IMAGE_PLACEMENT_LEFT
                    || placement == Thing.THING_CARD_IMAGE_PLACEMENT_RIGHT)
        ) {
            return Thing.THING_CARD_IMAGE_PLACEMENT_TOP
        }
        return placement
    }

    private fun isSideImagePlacement(@Thing.ThingCardImagePlacement placement: Int): Boolean {
        return placement == Thing.THING_CARD_IMAGE_PLACEMENT_LEFT
                || placement == Thing.THING_CARD_IMAGE_PLACEMENT_RIGHT
    }

    private fun moveThingCardChild(parent: LinearLayout, child: View, index: Int) {
        if (parent.indexOfChild(child) == index) return

        parent.removeView(child)
        parent.addView(child, index)
    }

    private fun hasMainContentAboveImage(holder: BaseThingViewHolder): Boolean {
        return holder.tvTitle!!.isVisible
                || holder.tvContent!!.isVisible
                || holder.rvChecklist!!.isVisible
                || holder.llAudioAttachment!!.isVisible
                || holder.rlReminder!!.isVisible
                || holder.rlHabit!!.isVisible
                || holder.ivPrivateThing!!.isVisible
    }

    private fun setThingCardImageFrameSize(
        holder: BaseThingViewHolder,
        width: Int,
        height: Int
    ) {
        val imageLp = holder.ivImageAttachment!!.layoutParams as FrameLayout.LayoutParams
        if (imageLp.width != width || imageLp.height != height) {
            imageLp.width = width
            imageLp.height = height
            holder.ivImageAttachment.layoutParams = imageLp
        }
        if (holder.ivImageAttachment.scaleType != ImageView.ScaleType.CENTER_CROP) {
            holder.ivImageAttachment.scaleType = ImageView.ScaleType.CENTER_CROP
        }

        val coverLp = holder.vImageCover!!.layoutParams as FrameLayout.LayoutParams
        if (coverLp.width != width || coverLp.height != height) {
            coverLp.width = width
            coverLp.height = height
            holder.vImageCover.layoutParams = coverLp
        }
    }

    private fun getThingCardThumbnailCrop(
        thing: Thing,
        mediaSource: ThingCardMediaHelper.MediaSource
    ): ThingCardAppearance.ThingCardThumbnailCrop {
        return thing.thingCardAppearance.sources[mediaSource.typePathName]
            ?.thumbnailCropWithTargetRatio(getDefaultThingCardThumbnailTargetAspectRatio(thing))
            ?: ThingCardAppearance.ThingCardThumbnailCrop(
                sourceAspectRatio = getDefaultThingCardThumbnailTargetAspectRatio(thing)
            )
    }

    private fun getThingCardSidePanelCrop(
        thing: Thing,
        mediaSource: ThingCardMediaHelper.MediaSource
    ): ThingCardAppearance.ThingCardThumbnailCrop {
        return thing.thingCardAppearance.sources[mediaSource.typePathName]
            ?.sidePanelCrop()
            ?: ThingCardAppearance.ThingCardThumbnailCrop()
    }

    private fun getThingCardMediaBackgroundCrop(
        thing: Thing,
        mediaSource: ThingCardMediaHelper.MediaSource
    ): ThingCardAppearance.ThingCardMediaBackgroundCrop {
        return thing.thingCardAppearance.sources[mediaSource.typePathName]
            ?.mediaBackgroundCrop()
            ?: ThingCardAppearance.ThingCardMediaBackgroundCrop()
    }

    private fun getThingCardMediaBackgroundSourceAspectRatio(
        thing: Thing,
        mediaSource: ThingCardMediaHelper.MediaSource
    ): Double? {
        return thing.thingCardAppearance.sources[mediaSource.typePathName]
            ?.mediaBackgroundTargetAspectRatio()
    }

    private fun getThingCardVideoFrameMs(
        thing: Thing,
        mediaSource: ThingCardMediaHelper.MediaSource
    ): Long? {
        if (!mediaSource.isVideo) return null
        val frameMs = thing.thingCardAppearance.sources[mediaSource.typePathName]?.videoFrameMs
        return if (frameMs != null && frameMs >= 0L) frameMs else null
    }

    private fun loadThingCardImage(
        holder: BaseThingViewHolder,
        pathName: String,
        imageW: Int,
        imageH: Int,
        crop: ThingCardAppearance.ThingCardThumbnailCrop,
        videoFrameMs: Long?
    ) {
        val imageView = holder.ivImageAttachment ?: return
        val loadKey = getThingCardImageLoadKey(
            pathName,
            imageW,
            imageH,
            videoFrameMs,
            crop
        )
        val renderRequest = ThingCardThumbnailRenderRequest(
            loadKey,
            imageW,
            imageH,
            crop
        )
        val debugInfo = getThingCardMediaDebugInfo(imageView)
        imageView.setTag(
            R.id.tag_thing_card_image_render_request,
            renderRequest
        )
        logThingCardMediaDebug(
            debugInfo,
            "load start key=$loadKey request=${imageW}x$imageH " +
                    "videoFrameMs=$videoFrameMs crop=$crop " +
                    "iv=${debugViewSize(imageView)} ivLp=${debugLayoutParamsSize(imageView)} " +
                    "frame=${debugViewSize(holder.flImageAttachment)} " +
                    "frameLp=${debugLayoutParamsSize(holder.flImageAttachment)}"
        )
        if (imageView.getTag(R.id.tag_thing_card_image_load_key) == loadKey) {
            holder.pbLoading!!.visibility = View.GONE
            logThingCardMediaDebug(debugInfo, "load same-key apply key=$loadKey")
            applyCurrentThingCardThumbnailRenderRequest(imageView)
            return
        }
        if (mAnimatedPlaybackEnabled && videoFrameMs == null &&
            AttachmentHelper.isAnimatedImageCandidate(pathName)
        ) {
            loadAnimatedThingCardThumbnail(
                holder, imageView, pathName, imageW, imageH, crop, loadKey
            )
            return
        }
        if (applyCachedThingCardMediaBitmap(
                imageView, loadKey, R.id.tag_thing_card_image_load_key
            )
        ) {
            mLoadedThingCardImageKeys.add(loadKey)
            holder.pbLoading!!.visibility = View.GONE
            logThingCardMediaDebug(
                getThingCardMediaDebugInfo(imageView),
                "load cache-hit apply key=$loadKey drawable=${debugDrawableSize(imageView.drawable)}"
            )
            applyCurrentThingCardThumbnailRenderRequest(imageView)
            return
        }

        val reusableDrawable = imageView.drawable?.takeIf {
            isSameThingCardImageSource(
                imageView.getTag(R.id.tag_thing_card_image_load_key) as? String,
                pathName,
                videoFrameMs
            )
        }
        if (reusableDrawable != null) {
            logThingCardMediaDebug(
                debugInfo,
                "load reusable-drawable placeholder key=$loadKey " +
                        "drawable=${debugDrawableSize(reusableDrawable)}"
            )
        }

        val imageWasLoaded = mLoadedThingCardImageKeys.contains(loadKey)
        holder.pbLoading!!.visibility =
            if (imageWasLoaded || reusableDrawable != null) View.GONE else View.VISIBLE
        if (reusableDrawable == null) {
            mImageRequestManager!!.clear(imageView)
        }
        imageView.setTag(R.id.tag_thing_card_image_load_key, loadKey)
        val request = mImageRequestManager!!
            .load(pathName)
            .override(imageW, imageH)
            .dontTransform()
            .disallowHardwareConfig()
            .signature(getThingCardMediaCacheSignature(loadKey))
        if (reusableDrawable != null) {
            request.placeholder(reusableDrawable)
        }
        if (videoFrameMs != null) {
            request.apply(
                RequestOptions.frameOf(videoFrameMs * 1000L)
                    .set(VideoDecoder.FRAME_OPTION, MediaMetadataRetriever.OPTION_CLOSEST)
            )
        }
        request
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?, model: Any?, target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    logThingCardMediaDebug(
                        getThingCardMediaDebugInfo(imageView),
                        "load failed key=$loadKey error=\"${previewThingCardDebugText(e?.message)}\""
                    )
                    if (imageView.getTag(
                            R.id.tag_thing_card_image_load_key
                        ) == loadKey
                    ) {
                        holder.pbLoading!!.visibility = View.GONE
                        holder.flImageAttachment!!.visibility = View.GONE
                        holder.tvImageCount!!.visibility = View.GONE
                        holder.vImageCover!!.visibility = View.GONE
                        holder.vPaddingBottom!!.visibility = View.VISIBLE
                        imageView.setTag(
                            R.id.tag_thing_card_image_render_request,
                            null
                        )
                    }
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable, model: Any, target: Target<Drawable>?,
                    dataSource: DataSource, isFirstResource: Boolean
                ): Boolean {
                    val bakedBitmap = MediaCropBitmapRenderer.renderCrop(
                        resource,
                        imageW,
                        imageH,
                        getThingCardThumbnailBitmapCrop(crop)
                    )
                    if (bakedBitmap != null) {
                        cacheThingCardMediaBitmap(loadKey, bakedBitmap)
                        mLoadedThingCardImageKeys.add(loadKey)
                        if (imageView.getTag(
                                R.id.tag_thing_card_image_load_key
                            ) == loadKey
                        ) {
                            holder.pbLoading!!.visibility = View.GONE
                            imageView.setImageBitmap(bakedBitmap)
                            imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                            imageView.imageMatrix = null
                            logThingCardMediaDebug(
                                getThingCardMediaDebugInfo(imageView),
                                "resource baked key=$loadKey " +
                                        "source=${debugDrawableSize(resource)} " +
                                        "bitmap=${bakedBitmap.width}x${bakedBitmap.height} " +
                                        "dataSource=$dataSource first=$isFirstResource " +
                                        "iv=${debugViewSize(imageView)} " +
                                        "frame=${debugViewSize(holder.flImageAttachment)}"
                            )
                        }
                        return true
                    }

                    logThingCardMediaDebug(
                        getThingCardMediaDebugInfo(imageView),
                        "resource bake failed fallback-center-crop key=$loadKey " +
                                "source=${debugDrawableSize(resource)}"
                    )
                    if (imageView.getTag(
                            R.id.tag_thing_card_image_load_key
                        ) != loadKey
                    ) {
                        return true
                    }
                    holder.pbLoading!!.visibility = View.GONE
                    imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                    imageView.imageMatrix = null
                    logThingCardMediaDebug(
                        getThingCardMediaDebugInfo(imageView),
                        "resource ready key=$loadKey drawable=${debugDrawableSize(resource)} " +
                                "dataSource=$dataSource first=$isFirstResource " +
                                "iv=${debugViewSize(imageView)} " +
                                "frame=${debugViewSize(holder.flImageAttachment)}"
                    )
                    return false
                }
            })
            .dontAnimate()
            .into(imageView)
    }

    private fun loadAnimatedThingCardThumbnail(
        holder: BaseThingViewHolder,
        imageView: ImageView,
        pathName: String,
        imageW: Int,
        imageH: Int,
        crop: ThingCardAppearance.ThingCardThumbnailCrop,
        loadKey: String
    ) {
        // Animated Image (GIF / animated WebP) thumbnail: load as a Drawable and
        // crop each frame with MediaCropTransformation so it animates while keeping
        // the user's crop. Skips the baked-bitmap LruCache. See ADR-0007.
        mImageRequestManager!!.clear(imageView)
        imageView.setTag(R.id.tag_thing_card_image_load_key, loadKey)
        holder.pbLoading!!.visibility = View.VISIBLE
        mImageRequestManager!!
            .load(pathName)
            .override(imageW, imageH)
            .transform(
                MediaCropTransformation(imageW, imageH, getThingCardThumbnailBitmapCrop(crop))
            )
            .signature(getThingCardMediaCacheSignature(loadKey))
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?, model: Any?, target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    if (imageView.getTag(R.id.tag_thing_card_image_load_key) == loadKey) {
                        holder.pbLoading!!.visibility = View.GONE
                        holder.flImageAttachment!!.visibility = View.GONE
                        holder.tvImageCount!!.visibility = View.GONE
                        holder.vImageCover!!.visibility = View.GONE
                        holder.vPaddingBottom!!.visibility = View.VISIBLE
                        imageView.setTag(R.id.tag_thing_card_image_render_request, null)
                    }
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable, model: Any, target: Target<Drawable>?,
                    dataSource: DataSource, isFirstResource: Boolean
                ): Boolean {
                    if (imageView.getTag(R.id.tag_thing_card_image_load_key) == loadKey) {
                        holder.pbLoading!!.visibility = View.GONE
                        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                        imageView.imageMatrix = null
                    }
                    return false
                }
            })
            .into(imageView)
    }

    private fun loadThingCardMediaBackground(
        holder: BaseThingViewHolder,
        thing: Thing,
        pathName: String,
        imageW: Int,
        imageH: Int,
        crop: ThingCardAppearance.ThingCardMediaBackgroundCrop,
        sourceAspectRatio: Double?,
        videoFrameMs: Long?
    ) {
        val imageView = holder.ivMediaBackground ?: return
        val loadKey = getThingCardMediaBackgroundLoadKey(
            pathName,
            imageW,
            imageH,
            videoFrameMs,
            crop,
            sourceAspectRatio
        )
        val renderRequest = ThingCardMediaBackgroundRenderRequest(
            loadKey,
            imageW,
            imageH,
            crop,
            sourceAspectRatio
        )
        imageView.setTag(
            R.id.tag_thing_card_media_background_render_request,
            renderRequest
        )
        if (imageView.getTag(R.id.tag_thing_card_media_background_load_key) == loadKey) {
            applyCurrentThingCardMediaBackgroundRenderRequest(imageView)
            return
        }
        if (mAnimatedPlaybackEnabled && videoFrameMs == null &&
            AttachmentHelper.isAnimatedImageCandidate(pathName)
        ) {
            loadAnimatedThingCardMediaBackground(
                holder, imageView, thing, pathName, imageW, imageH,
                crop, sourceAspectRatio, loadKey
            )
            return
        }
        if (applyCachedThingCardMediaBitmap(
                imageView, loadKey, R.id.tag_thing_card_media_background_load_key
            )
        ) {
            applyCurrentThingCardMediaBackgroundRenderRequest(imageView)
            return
        }

        val reusableDrawable = imageView.drawable?.takeIf {
            isSameThingCardMediaBackgroundSource(
                imageView.getTag(R.id.tag_thing_card_media_background_load_key) as? String,
                pathName,
                videoFrameMs
            )
        }
        if (reusableDrawable != null) {
            imageView.scaleType = ImageView.ScaleType.CENTER_CROP
            imageView.imageMatrix = null
        }

        if (reusableDrawable == null) {
            mImageRequestManager!!.clear(imageView)
        }
        imageView.setTag(R.id.tag_thing_card_media_background_load_key, loadKey)
        val request = mImageRequestManager!!
            .load(pathName)
            .override(imageW, imageH)
            .dontTransform()
            .disallowHardwareConfig()
            .signature(getThingCardMediaCacheSignature(loadKey))
        if (reusableDrawable != null) {
            request.placeholder(reusableDrawable)
        }
        if (videoFrameMs != null) {
            request.apply(
                RequestOptions.frameOf(videoFrameMs * 1000L)
                    .set(VideoDecoder.FRAME_OPTION, MediaMetadataRetriever.OPTION_CLOSEST)
            )
        }
        request
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?, model: Any?, target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    if (imageView.getTag(R.id.tag_thing_card_media_background_load_key) == loadKey) {
                        imageView.visibility = View.GONE
                        holder.vMediaBackgroundMask!!.visibility = View.GONE
                        resetThingCardMediaBackgroundOverlaySize(holder)
                        applyThingCardForegroundColors(holder, thing, thing.getColor())
                        imageView.setTag(
                            R.id.tag_thing_card_media_background_render_request,
                            null
                        )
                    }
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable, model: Any, target: Target<Drawable>?,
                    dataSource: DataSource, isFirstResource: Boolean
                ): Boolean {
                    val bakedBitmap = MediaCropBitmapRenderer.renderCrop(
                        resource,
                        imageW,
                        imageH,
                        getThingCardMediaBackgroundBitmapCrop(crop, sourceAspectRatio)
                    )
                    if (bakedBitmap != null) {
                        cacheThingCardMediaBitmap(loadKey, bakedBitmap)
                        if (imageView.getTag(
                                R.id.tag_thing_card_media_background_load_key
                            ) == loadKey
                        ) {
                            imageView.setImageBitmap(bakedBitmap)
                            imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                            imageView.imageMatrix = null
                        }
                        return true
                    }

                    if (imageView.getTag(
                            R.id.tag_thing_card_media_background_load_key
                        ) != loadKey
                    ) {
                        return true
                    }
                    imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                    imageView.imageMatrix = null
                    return false
                }
            })
            .dontAnimate()
            .into(imageView)
    }

    private fun loadAnimatedThingCardMediaBackground(
        holder: BaseThingViewHolder,
        imageView: ImageView,
        thing: Thing,
        pathName: String,
        imageW: Int,
        imageH: Int,
        crop: ThingCardAppearance.ThingCardMediaBackgroundCrop,
        sourceAspectRatio: Double?,
        loadKey: String
    ) {
        // Animated Image media background: animate while keeping the crop. Foreground
        // colours use a fixed dark base in media-background mode (not sampled from the
        // media), so animation does not affect them. Skips the LruCache. See ADR-0007.
        mImageRequestManager!!.clear(imageView)
        imageView.setTag(R.id.tag_thing_card_media_background_load_key, loadKey)
        mImageRequestManager!!
            .load(pathName)
            .override(imageW, imageH)
            .transform(
                MediaCropTransformation(
                    imageW, imageH, getThingCardMediaBackgroundBitmapCrop(crop, sourceAspectRatio)
                )
            )
            .signature(getThingCardMediaCacheSignature(loadKey))
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?, model: Any?, target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    if (imageView.getTag(
                            R.id.tag_thing_card_media_background_load_key
                        ) == loadKey
                    ) {
                        imageView.visibility = View.GONE
                        holder.vMediaBackgroundMask!!.visibility = View.GONE
                        resetThingCardMediaBackgroundOverlaySize(holder)
                        applyThingCardForegroundColors(holder, thing, thing.getColor())
                        imageView.setTag(
                            R.id.tag_thing_card_media_background_render_request,
                            null
                        )
                    }
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable, model: Any, target: Target<Drawable>?,
                    dataSource: DataSource, isFirstResource: Boolean
                ): Boolean {
                    if (imageView.getTag(
                            R.id.tag_thing_card_media_background_load_key
                        ) == loadKey
                    ) {
                        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                        imageView.imageMatrix = null
                    }
                    return false
                }
            })
            .into(imageView)
    }

    private fun isSameThingCardMediaBackgroundSource(
        loadKey: String?,
        pathName: String,
        videoFrameMs: Long?
    ): Boolean {
        if (loadKey == null) return false
        return loadKey.startsWith(
            "background:${getThingCardMediaSourceKey(pathName, videoFrameMs)}:"
        )
    }

    private fun isSameThingCardImageSource(
        loadKey: String?,
        pathName: String,
        videoFrameMs: Long?
    ): Boolean {
        if (loadKey == null) return false
        return loadKey.startsWith("${getThingCardMediaSourceKey(pathName, videoFrameMs)}:")
    }

    private fun applyThingCardForegroundColors(
        holder: BaseThingViewHolder,
        thing: Thing,
        foregroundBaseColor: Int
    ) {
        holder.tvTitle!!.setTextColor(textColorPrimary(foregroundBaseColor))
        holder.tvContent!!.setTextColor(textColorSecondary(foregroundBaseColor))
        holder.tvImageCount!!.setTextColor(textColorSecondary(foregroundBaseColor))
        applyThingCardOverlayMediaCountColors(holder)
        holder.tvInlineMediaCount!!.setTextColor(textColorTertiary(foregroundBaseColor))
        applyThingCardMediaCountIcon(holder.ivInlineMediaCount, foregroundBaseColor)
        holder.tvAudioCount!!.setTextColor(textColorTertiary(foregroundBaseColor))
        holder.tvReminderTime!!.setTextColor(textColorTertiary(foregroundBaseColor))
        holder.tvHabitSummary!!.setTextColor(textColorTertiary(foregroundBaseColor))
        holder.tvHabitNextReminder!!.setTextColor(textColorDisabled(foregroundBaseColor))
        holder.tvHabitLastFive!!.setTextColor(textColorDisabled(foregroundBaseColor))
        holder.tvHabitFinishedThisT!!.setTextColor(textColorTertiary(foregroundBaseColor))

        tintThingStickyOngoingIcon(holder.ivStickyOngoing, thing, foregroundBaseColor)
        tintCardIcon(holder.ivReminder, foregroundBaseColor)
        tintCardIcon(holder.ivHabit, foregroundBaseColor)
        tintCardSeparator(holder.vReminderSeparator, foregroundBaseColor)
        tintCardSeparator(holder.vHabitSeparator1, foregroundBaseColor)
        tintCardSeparator(holder.vHabitSeparator2, foregroundBaseColor)
        holder.habitRecordPresenter.setThingColor(foregroundBaseColor)

        mCheckListAdapters!!.get(thing.id)?.setThingColor(foregroundBaseColor)
        holder.rvChecklist!!.adapter?.notifyDataSetChanged()
        val dark = BackgroundUtil.isLight(foregroundBaseColor)
        holder.ivAudioCount!!.setImageResource(
            if (dark) {
                R.drawable.card_audio_attachment_black
            } else {
                R.drawable.card_audio_attachment
            }
        )
    }

    private fun applyThingCardMediaCountIcon(
        holder: BaseThingViewHolder,
        foregroundBaseColor: Int
    ) {
        applyThingCardMediaCountIcon(holder.ivMediaCount, foregroundBaseColor)
    }

    private fun applyThingCardOverlayMediaCountColors(holder: BaseThingViewHolder) {
        holder.tvMediaCount?.setTextColor(
            ContextCompat.getColor(mContext!!, R.color.white_66p)
        )
        holder.ivMediaCount ?: return
        androidx.core.widget.ImageViewCompat.setImageTintList(holder.ivMediaCount, null)
        holder.ivMediaCount.clearColorFilter()
        holder.ivMediaCount.setImageResource(R.drawable.card_image_attachment_count)
    }

    private fun applyThingCardMediaCountIcon(
        icon: ImageView?,
        foregroundBaseColor: Int
    ) {
        icon ?: return
        androidx.core.widget.ImageViewCompat.setImageTintList(icon, null)
        icon.clearColorFilter()
        icon.setImageResource(
            if (BackgroundUtil.isLight(foregroundBaseColor)) {
                R.drawable.card_image_attachment_count_black
            } else {
                R.drawable.card_image_attachment_count
            }
        )
    }

    private fun getThingCardMediaBitmapCacheMaxBytes(context: Context?): Int {
        val activityManager = context?.getSystemService(
            Context.ACTIVITY_SERVICE
        ) as? ActivityManager
        val memoryClassBytes = (activityManager?.memoryClass ?: DEFAULT_MEMORY_CLASS_MB) *
                BYTES_PER_MEGABYTE
        return min(
            THING_CARD_MEDIA_BITMAP_CACHE_MAX_BYTES,
            max(THING_CARD_MEDIA_BITMAP_CACHE_MIN_BYTES, memoryClassBytes / 8)
        )
    }

    private fun getThingCardMediaSourceKey(
        pathName: String,
        videoFrameMs: Long?
    ): String {
        val file = File(pathName)
        val exists = file.exists()
        val fileSize = if (exists) file.length() else 0L
        val lastModified = if (exists) file.lastModified() else 0L
        return "$pathName:$fileSize:$lastModified:$videoFrameMs"
    }

    private fun getThingCardImageLoadKey(
        pathName: String,
        imageW: Int,
        imageH: Int,
        videoFrameMs: Long?
    ): String {
        return "${getThingCardMediaSourceKey(pathName, videoFrameMs)}:$imageW:$imageH"
    }

    private fun getThingCardImageLoadKey(
        pathName: String,
        imageW: Int,
        imageH: Int,
        videoFrameMs: Long?,
        crop: ThingCardAppearance.ThingCardThumbnailCrop
    ): String {
        val baseKey = getThingCardImageLoadKey(pathName, imageW, imageH, videoFrameMs)
        return "$baseKey:crop:${getThingCardThumbnailBitmapCrop(crop).fingerprint()}"
    }

    private fun getThingCardMediaBackgroundLoadKey(
        pathName: String,
        imageW: Int,
        imageH: Int,
        videoFrameMs: Long?,
        crop: ThingCardAppearance.ThingCardMediaBackgroundCrop,
        sourceAspectRatio: Double?
    ): String {
        return "background:${getThingCardImageLoadKey(pathName, imageW, imageH, videoFrameMs)}" +
                ":crop:${getThingCardMediaBackgroundBitmapCrop(crop, sourceAspectRatio).fingerprint()}"
    }

    private fun getThingCardMediaCacheSignature(loadKey: String): ObjectKey {
        return ObjectKey(loadKey)
    }

    private fun applyCachedThingCardMediaBitmap(
        imageView: ImageView,
        loadKey: String,
        loadKeyTagId: Int
    ): Boolean {
        val cache = getThingCardMediaBitmapCache()
        val bitmap = cache.get(loadKey) ?: return false
        if (bitmap.isRecycled) {
            cache.remove(loadKey)
            return false
        }
        mImageRequestManager!!.clear(imageView)
        imageView.setImageBitmap(bitmap)
        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
        imageView.imageMatrix = null
        imageView.setTag(loadKeyTagId, loadKey)
        return true
    }

    private fun cacheThingCardMediaBitmap(loadKey: String, bitmap: Bitmap) {
        if (bitmap.isRecycled) return

        val estimatedSoftwareBytes = bitmap.width.toLong() *
                bitmap.height.toLong() * BYTES_PER_ARGB_8888_PIXEL
        val cache = getThingCardMediaBitmapCache()
        if (estimatedSoftwareBytes > cache.maxSize()) return

        val cachedBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return
        cache.put(loadKey, cachedBitmap)
    }

    private fun getThingCardThumbnailBitmapCrop(
        crop: ThingCardAppearance.ThingCardThumbnailCrop
    ): MediaCropBitmapRenderer.Crop {
        return MediaCropBitmapRenderer.Crop(
            centerX = crop.centerX,
            centerY = crop.centerY,
            userScale = crop.scale,
            sourceAspectRatio = crop.sourceAspectRatio
        )
    }

    private fun getThingCardMediaBackgroundBitmapCrop(
        crop: ThingCardAppearance.ThingCardMediaBackgroundCrop,
        sourceAspectRatio: Double?
    ): MediaCropBitmapRenderer.Crop {
        return MediaCropBitmapRenderer.Crop(
            centerX = crop.centerX,
            centerY = crop.centerY,
            userScale = crop.scale,
            sourceAspectRatio = sourceAspectRatio
        )
    }

    private data class ThingCardThumbnailRenderRequest(
        val loadKey: String,
        val imageW: Int,
        val imageH: Int,
        val crop: ThingCardAppearance.ThingCardThumbnailCrop
    )

    private data class ThingCardMediaBackgroundRenderRequest(
        val loadKey: String,
        val imageW: Int,
        val imageH: Int,
        val crop: ThingCardAppearance.ThingCardMediaBackgroundCrop,
        val sourceAspectRatio: Double?
    )

    private fun applyCurrentThingCardThumbnailRenderRequest(imageView: ImageView?) {
        val request = imageView?.getTag(
            R.id.tag_thing_card_image_render_request
        ) as? ThingCardThumbnailRenderRequest ?: return
        logThingCardMediaDebug(
            getThingCardMediaDebugInfo(imageView),
            "apply current-thumbnail request=${request.imageW}x${request.imageH} " +
                    "key=${request.loadKey} crop=${request.crop} " +
                    "iv=${debugViewSize(imageView)} drawable=${debugDrawableSize(imageView.drawable)}"
        )
        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
        imageView.imageMatrix = null
        logThingCardMediaDebug(
            getThingCardMediaDebugInfo(imageView),
            "apply current-thumbnail baked-bitmap key=${request.loadKey}"
        )
    }

    private fun applyCurrentThingCardMediaBackgroundRenderRequest(imageView: ImageView?) {
        val request = imageView?.getTag(
            R.id.tag_thing_card_media_background_render_request
        ) as? ThingCardMediaBackgroundRenderRequest ?: return
        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
        imageView.imageMatrix = null
    }

    fun applyThingCardMediaBackgroundHeightToBoundHolder(
        holder: BaseThingViewHolder?,
        thing: Thing?
    ): Boolean {
        if (holder == null || thing == null) return false
        if (!thing.thingCardAppearance.mediaBackgroundEnabled) return false
        if (holder.ivMediaBackground?.isVisible != true) return false

        val mediaSource = ThingCardMediaHelper.resolveEffectiveMediaSource(thing) ?: return false
        updateCardForMediaBackground(holder, thing, mediaSource, false)
        return true
    }

    fun applyThingCardMediaCropToBoundHolder(
        holder: BaseThingViewHolder?,
        thing: Thing?
    ): Boolean {
        if (holder == null || thing == null) return false
        val mediaSource = ThingCardMediaHelper.resolveEffectiveMediaSource(thing) ?: return false
        if (thing.thingCardAppearance.mediaBackgroundEnabled) {
            val targetW = getCardContentWidthForMeasuredHolder(holder)
                .takeIf { it > 0 }
                ?: holder.ivMediaBackground?.width?.takeIf { it > 0 }
                ?: holder.cv?.width
                ?: 0
            val targetH = holder.ivMediaBackground?.height?.takeIf { it > 0 }
                ?: getThingCardMediaBackgroundEffectiveTargetHeight(holder, thing, mediaSource)
            if (targetW <= 0 || targetH <= 0 || holder.ivMediaBackground?.isVisible != true) {
                return false
            }
            val crop = getThingCardMediaBackgroundCrop(thing, mediaSource)
            val sourceAspectRatio = getThingCardMediaBackgroundSourceAspectRatio(
                thing,
                mediaSource
            )
            val imageView = holder.ivMediaBackground ?: return false
            setThingCardMediaBackgroundOverlaySize(holder, targetW, targetH)
            val videoFrameMs = getThingCardVideoFrameMs(thing, mediaSource)
            val loadKey = getThingCardMediaBackgroundLoadKey(
                mediaSource.pathName,
                targetW,
                targetH,
                videoFrameMs,
                crop,
                sourceAspectRatio
            )
            val currentLoadKey = imageView.getTag(
                R.id.tag_thing_card_media_background_load_key
            ) as? String
            if (currentLoadKey != loadKey || imageView.drawable == null) {
                loadThingCardMediaBackground(
                    holder,
                    thing,
                    mediaSource.pathName,
                    targetW,
                    targetH,
                    crop,
                    sourceAspectRatio,
                    videoFrameMs
                )
                return true
            }
            imageView.setTag(
                R.id.tag_thing_card_media_background_render_request,
                ThingCardMediaBackgroundRenderRequest(
                    loadKey,
                    targetW,
                    targetH,
                    crop,
                    sourceAspectRatio
                )
            )
            applyCurrentThingCardMediaBackgroundRenderRequest(imageView)
            return true
        }

        val targetW = holder.flImageAttachment?.width ?: 0
        val targetH = holder.flImageAttachment?.height ?: 0
        val debugInfo = getThingCardMediaDebugInfo(holder.ivImageAttachment)
        if (targetW <= 0 || targetH <= 0 || holder.flImageAttachment?.isVisible != true) {
            logThingCardMediaDebug(
                debugInfo,
                "replay skipped invalid foreground target=${targetW}x$targetH " +
                        "visible=${holder.flImageAttachment?.isVisible} " +
                        "frame=${debugViewSize(holder.flImageAttachment)} " +
                        "frameLp=${debugLayoutParamsSize(holder.flImageAttachment)}"
            )
            return false
        }
        val placement = getEffectiveThingCardImagePlacement(thing)
        val crop = if (isSideImagePlacement(placement)) {
            getThingCardSidePanelCrop(thing, mediaSource)
        } else {
            getThingCardThumbnailCrop(thing, mediaSource)
        }
        val imageView = holder.ivImageAttachment ?: return false
        val videoFrameMs = getThingCardVideoFrameMs(thing, mediaSource)
        val loadKey = getThingCardImageLoadKey(
            mediaSource.pathName,
            targetW,
            targetH,
            videoFrameMs,
            crop
        )
        val currentLoadKey = imageView.getTag(R.id.tag_thing_card_image_load_key) as? String
        if (currentLoadKey != loadKey || imageView.drawable == null) {
            loadThingCardImage(
                holder,
                mediaSource.pathName,
                targetW,
                targetH,
                crop,
                videoFrameMs
            )
            return true
        }
        imageView.setTag(
            R.id.tag_thing_card_image_render_request,
            ThingCardThumbnailRenderRequest(loadKey, targetW, targetH, crop)
        )
        logThingCardMediaDebug(
            debugInfo,
            "replay foreground target=${targetW}x$targetH key=$loadKey " +
                    "placement=${thingCardImagePlacementName(placement)} crop=$crop " +
                    "iv=${debugViewSize(imageView)} ivLp=${debugLayoutParamsSize(imageView)} " +
                    "frame=${debugViewSize(holder.flImageAttachment)} " +
                    "frameLp=${debugLayoutParamsSize(holder.flImageAttachment)}"
        )
        applyCurrentThingCardThumbnailRenderRequest(imageView)
        return true
    }

    private fun syncSideImageProjectionAfterMeasure(
        holder: BaseThingViewHolder,
        bindToken: String,
        thing: Thing,
        pathName: String,
        crop: ThingCardAppearance.ThingCardThumbnailCrop,
        videoFrameMs: Long?
    ) {
        holder.llContent!!.post {
            if (holder.flImageAttachment!!.getTag(
                    R.id.tag_thing_card_side_image_bind_token
                ) != bindToken
            ) {
                return@post
            }
            if (!holder.flImageAttachment.isVisible) return@post

            val projection = getSideImageProjection(holder, thing)
            val imageLp = holder.flImageAttachment.layoutParams as LinearLayout.LayoutParams
            val textLp = holder.llTextContent!!.layoutParams as LinearLayout.LayoutParams
            if (imageLp.height == projection.imageHeight &&
                    imageLp.width == projection.imageWidth &&
                    textLp.width == projection.textWidth) {
                return@post
            }

            applySideImageProjectionLayout(holder, projection)
            setThingCardImageFrameSize(
                holder, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            loadThingCardImage(
                holder, pathName, projection.imageWidth, projection.imageHeight, crop, videoFrameMs
            )
        }
    }

    private fun applyThingCardImagePlacementLayout(
        holder: BaseThingViewHolder,
        thing: Thing,
        @Thing.ThingCardImagePlacement placement: Int,
        sideImageProjection: ThingCardSideImageProjection? = null
    ) {
        val parent = holder.llContent!!
        val image = holder.flImageAttachment!!
        val textContent = holder.llTextContent!!

        if (isSideImagePlacement(placement)) {
            parent.orientation = LinearLayout.HORIZONTAL
            parent.layoutDirection = View.LAYOUT_DIRECTION_LTR
            textContent.layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            if (placement == Thing.THING_CARD_IMAGE_PLACEMENT_LEFT) {
                moveThingCardChild(parent, image, 0)
                moveThingCardChild(parent, textContent, 1)
            } else {
                moveThingCardChild(parent, textContent, 0)
                moveThingCardChild(parent, image, 1)
            }

            parent.minimumHeight = getSideImageMinHeight()
            applySideImageProjectionLayout(
                holder, sideImageProjection ?: getSideImageProjection(holder, thing)
            )
        } else {
            parent.orientation = LinearLayout.VERTICAL
            parent.layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            textContent.layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            if (placement == Thing.THING_CARD_IMAGE_PLACEMENT_BOTTOM) {
                moveThingCardChild(parent, textContent, 0)
                moveThingCardChild(parent, image, 1)
            } else {
                moveThingCardChild(parent, image, 0)
                moveThingCardChild(parent, textContent, 1)
            }

            val imageLp = image.layoutParams as LinearLayout.LayoutParams
            imageLp.width = ViewGroup.LayoutParams.MATCH_PARENT
            imageLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            imageLp.weight = 0f
            imageLp.setMargins(
                0,
                if (placement == Thing.THING_CARD_IMAGE_PLACEMENT_BOTTOM
                    && hasMainContentAboveImage(holder)
                ) {
                    (mDensity * 16).toInt()
                } else {
                    0
                },
                0,
                0
            )
            image.layoutParams = imageLp

            val textLp = textContent.layoutParams as LinearLayout.LayoutParams
            textLp.width = ViewGroup.LayoutParams.MATCH_PARENT
            textLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            textLp.weight = 0f
            textLp.setMargins(0, 0, 0, 0)
            textContent.layoutParams = textLp
        }
    }

    private fun applySideImageProjectionLayout(
        holder: BaseThingViewHolder,
        projection: ThingCardSideImageProjection
    ) {
        val imageLp = holder.flImageAttachment!!.layoutParams as LinearLayout.LayoutParams
        imageLp.width = projection.imageWidth
        imageLp.height = projection.imageHeight
        imageLp.weight = 0f
        imageLp.setMargins(0, 0, 0, 0)
        holder.flImageAttachment.layoutParams = imageLp

        val textLp = holder.llTextContent!!.layoutParams as LinearLayout.LayoutParams
        textLp.width = projection.textWidth
        textLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        textLp.weight = 0f
        textLp.setMargins(0, 0, 0, 0)
        holder.llTextContent.layoutParams = textLp
    }

    private fun getSideImageTargetAspectRatio(thing: Thing): Double? {
        val mediaSource = ThingCardMediaHelper.resolveEffectiveMediaSource(thing)
            ?: return null
        return thing.thingCardAppearance.sources[mediaSource.typePathName]
            ?.sidePanelTargetAspectRatio()
    }

    private fun clampSideImageWidth(width: Int, contentWidth: Int): Int {
        return max(
            getSideImageMinWidth(contentWidth),
            min(getSideImageMaxWidth(contentWidth), width)
        )
    }

    private fun getSideImageProjection(
        holder: BaseThingViewHolder,
        thing: Thing
    ): ThingCardSideImageProjection {
        val contentWidth = max(1, getCardContentWidth(thing))
        val percentWidth = getSideImagePercentWidth(thing, contentWidth)
        val targetRatio = getSideImageTargetAspectRatio(thing)
        if (targetRatio == null || targetRatio <= 0.0 ||
                targetRatio.isNaN() || targetRatio.isInfinite()) {
            return getSideImageProjectionForWidth(holder, contentWidth, percentWidth)
        }

        var width = percentWidth
        var bestProjection = getSideImageProjectionForWidth(holder, contentWidth, width)
        var bestError = Int.MAX_VALUE
        repeat(SIDE_IMAGE_PROJECTION_MAX_ITERATIONS) {
            val projection = getSideImageProjectionForWidth(holder, contentWidth, width)
            val nextWidth = clampSideImageWidth(
                (projection.imageHeight * targetRatio).roundToInt(),
                contentWidth
            )
            val error = abs(nextWidth - projection.imageWidth)
            if (error < bestError) {
                bestProjection = projection
                bestError = error
            }
            if (error <= SIDE_IMAGE_PROJECTION_TOLERANCE_PX) {
                return projection
            }
            width = nextWidth
        }
        return bestProjection
    }

    private fun getSideImageProjectionForWidth(
        holder: BaseThingViewHolder,
        contentWidth: Int,
        imageWidth: Int
    ): ThingCardSideImageProjection {
        val clampedWidth = clampSideImageWidth(imageWidth, contentWidth)
        val textWidth = max(1, contentWidth - clampedWidth)
        val textHeight = measureThingCardSideTextContentHeight(holder, textWidth)
        return ThingCardSideImageProjection(
            imageWidth = clampedWidth,
            imageHeight = max(getSideImageMinHeight(), textHeight),
            textWidth = textWidth
        )
    }

    private fun measureThingCardSideTextContentHeight(
        holder: BaseThingViewHolder,
        width: Int
    ): Int {
        val textContent = holder.llTextContent ?: return 0
        if (width <= 0) return textContent.measuredHeight

        val textLp = textContent.layoutParams as LinearLayout.LayoutParams
        val oldTextWidth = textLp.width
        val oldTextHeight = textLp.height
        val oldTextWeight = textLp.weight
        return try {
            textLp.width = width
            textLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            textLp.weight = 0f
            val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            textContent.measure(widthSpec, heightSpec)
            textContent.measuredHeight
        } finally {
            textLp.width = oldTextWidth
            textLp.height = oldTextHeight
            textLp.weight = oldTextWeight
        }
    }

    private fun getSideImagePercentWidth(thing: Thing, contentWidth: Int): Int {
        val imagePercent = normalizeThingCardSideMediaWidth(
            thing.thingCardAppearance.sideMediaWidthPercent
        )
        return clampSideImageWidth(contentWidth * imagePercent / 100, contentWidth)
    }

    private fun getSideImageMinWidth(contentWidth: Int): Int {
        val minPercent = mContext!!.resources.getInteger(
            R.integer.thing_card_side_media_width_min_percent
        )
        return max(1, contentWidth * minPercent / 100)
    }

    private fun getSideImageMaxWidth(contentWidth: Int): Int {
        val maxPercent = mContext!!.resources.getInteger(
            R.integer.thing_card_side_media_width_max_percent
        )
        return max(1, contentWidth * maxPercent / 100)
    }

    private fun normalizeThingCardSideMediaWidth(widthPercent: Int): Int {
        val minPercent = mContext!!.resources.getInteger(
            R.integer.thing_card_side_media_width_min_percent
        )
        val maxPercent = mContext!!.resources.getInteger(
            R.integer.thing_card_side_media_width_max_percent
        )
        return max(minPercent, min(maxPercent, widthPercent))
    }

    private fun getSideImageMinHeight(): Int {
        return mContext!!.resources.getDimensionPixelSize(
            R.dimen.thing_card_full_span_side_image_min_height
        )
    }

    private fun getThumbnailSourceAspectRatio(thing: Thing): Float {
        return getThingCardThumbnailTargetAspectRatio(thing)
    }

    protected fun getThingCardThumbnailTargetAspectRatio(thing: Thing): Float {
        val mediaSource = ThingCardMediaHelper.resolveEffectiveMediaSource(thing)
        val defaultRatio = getDefaultThingCardThumbnailTargetAspectRatio(thing)
        val sourceAspectRatio = mediaSource?.let {
            thing.thingCardAppearance.sources[it.typePathName]
                ?.thumbnailCropWithTargetRatio(defaultRatio)
                ?.sourceAspectRatio
        }
        if (sourceAspectRatio != null && sourceAspectRatio > 0.0) {
            return sourceAspectRatio.toFloat()
        }
        return defaultRatio.toFloat()
    }

    private fun getDefaultThingCardThumbnailTargetAspectRatio(thing: Thing): Double {
        return if (isFullSpanThingCard(thing)) 16.0 / 9.0 else 4.0 / 3.0
    }

    private fun getThingCardSurfaceAvailableHeight(): Int {
        if (mThingCardSurfaceAvailableHeightOverride > 0) {
            return mThingCardSurfaceAvailableHeightOverride
        }
        val recyclerView = mRecyclerView
        if (recyclerView != null) {
            val height = recyclerView.height - recyclerView.paddingTop - recyclerView.paddingBottom
            if (height > 0) return height
        }
        return DisplayUtil.getScreenSize(mContext).y
    }

    private fun getThumbnailHeightMinPercent(thing: Thing): Int {
        return mContext!!.resources.getInteger(
            if (isFullSpanThingCard(thing)) {
                R.integer.thing_card_full_span_thumbnail_min_height_percent
            } else {
                R.integer.thing_card_normal_thumbnail_min_height_percent
            }
        )
    }

    private fun updateThingCardImageCountLayout(
        holder: BaseThingViewHolder,
        @Thing.ThingCardImagePlacement placement: Int
    ) {
        val countLp = holder.tvImageCount!!.layoutParams as FrameLayout.LayoutParams
        val gravityHorizontal = if (placement == Thing.THING_CARD_IMAGE_PLACEMENT_RIGHT) {
            Gravity.RIGHT
        } else {
            Gravity.LEFT
        }
        val gravity = Gravity.BOTTOM or gravityHorizontal
        if (countLp.gravity != gravity) {
            countLp.gravity = gravity
            holder.tvImageCount.layoutParams = countLp
        }
    }

    private fun getThingCardMediaBackgroundClampedTargetHeight(
        thing: Thing,
        mediaSource: ThingCardMediaHelper.MediaSource
    ): Int {
        val sourceAppearance = thing.thingCardAppearance.sources[mediaSource.typePathName]
        val targetAspectRatio = sourceAppearance?.mediaBackgroundTargetAspectRatio()
        if (targetAspectRatio == null || targetAspectRatio <= 0.0) return 0

        val targetHeight = (getCardContentWidth(thing) / targetAspectRatio).toInt()
        val maxHeight = getThingCardSurfaceAvailableHeight() *
                mContext!!.resources.getInteger(
                        R.integer.thing_card_media_background_home_max_height_percent
                ) / 100
        return min(targetHeight, maxHeight)
    }

    private fun updateCardForMediaBackground(
        holder: BaseThingViewHolder,
        thing: Thing,
        mediaSource: ThingCardMediaHelper.MediaSource,
        resetContentExpansion: Boolean = true
    ) {
        holder.flImageAttachment!!.setTag(R.id.tag_thing_card_side_image_bind_token, null)
        holder.ivImageAttachment!!.setTag(R.id.tag_thing_card_image_load_key, null)
        holder.ivImageAttachment!!.setTag(R.id.tag_thing_card_image_render_request, null)
        mImageRequestManager!!.clear(holder.ivImageAttachment!!)
        holder.flImageAttachment.visibility = View.GONE
        holder.tvImageCount!!.visibility = View.GONE
        holder.vImageCover!!.visibility = View.GONE
        holder.llInlineMediaAttachment!!.visibility = View.GONE

        val sourceAppearance = thing.thingCardAppearance.sources[mediaSource.typePathName]
        val clampedTargetHeight = getThingCardMediaBackgroundClampedTargetHeight(
            thing, mediaSource
        )
        val mediaBackground = holder.ivMediaBackground!!
        val mediaBackgroundMask = holder.vMediaBackgroundMask!!
        if (resetContentExpansion) {
            resetThingCardMediaBackgroundContentExpansion(holder)
        }
        updateThingCardMediaBackgroundInlineCount(holder, thing)
        val effectiveTargetHeight = getThingCardMediaBackgroundEffectiveTargetHeight(
            holder, clampedTargetHeight
        )
        val targetWidth = getCardContentWidth(thing)
        holder.llContent!!.minimumHeight = 0
        setThingCardMediaBackgroundOverlaySize(holder, targetWidth, effectiveTargetHeight)
        holder.llContent!!.requestLayout()
        holder.cv!!.requestLayout()

        mediaBackground.visibility = View.VISIBLE
        mediaBackgroundMask.visibility = View.VISIBLE
        mediaBackgroundMask.alpha = normalizeThingCardMaskStrength(
                sourceAppearance?.mediaBackgroundMaskStrength()
                        ?: getThingCardDefaultMaskStrength()
        )

        val backgroundCrop = getThingCardMediaBackgroundCrop(thing, mediaSource)
        val backgroundSourceAspectRatio = getThingCardMediaBackgroundSourceAspectRatio(
            thing,
            mediaSource
        )
        val videoFrameMs = getThingCardVideoFrameMs(thing, mediaSource)
        val bindToken = thing.id.toString() + ":" + mediaSource.typePathName +
                ":" + effectiveTargetHeight + ":" + backgroundCrop.toString() +
                ":" + backgroundSourceAspectRatio + ":" + videoFrameMs
        mediaBackground.setTag(R.id.tag_thing_card_media_background_bind_token, bindToken)

        updateThingCardMediaBackgroundBottomStatusLayout(
            holder, bindToken, effectiveTargetHeight
        )
        loadThingCardMediaBackgroundAfterLayout(
                holder,
                thing,
                mediaSource.pathName,
                backgroundCrop,
                backgroundSourceAspectRatio,
                videoFrameMs,
                targetWidth,
                effectiveTargetHeight,
                bindToken
        )

        holder.vPaddingBottom!!.visibility = View.VISIBLE
    }

    private fun loadThingCardMediaBackgroundAfterLayout(
        holder: BaseThingViewHolder,
        thing: Thing,
        pathName: String,
        backgroundCrop: ThingCardAppearance.ThingCardMediaBackgroundCrop,
        backgroundSourceAspectRatio: Double?,
        videoFrameMs: Long?,
        intendedTargetWidth: Int,
        intendedTargetHeight: Int,
        bindToken: String
    ) {
        val card = holder.cv ?: return
        val mediaBackground = holder.ivMediaBackground ?: return
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val observer = card.viewTreeObserver
                if (observer.isAlive) {
                    observer.removeOnPreDrawListener(this)
                }
                if (mediaBackground.getTag(
                        R.id.tag_thing_card_media_background_bind_token
                    ) != bindToken
                ) {
                    return true
                }
                if (!mediaBackground.isVisible) return true

                val targetW = getThingCardMediaBackgroundTargetWidth(
                    holder, intendedTargetWidth
                )
                val targetH = getThingCardMediaBackgroundEffectiveTargetHeight(
                    holder, intendedTargetHeight
                )
                if (targetW <= 0 || targetH <= 0) return true
                setThingCardMediaBackgroundOverlaySize(holder, targetW, targetH)
                loadThingCardMediaBackground(
                        holder,
                        thing,
                        pathName,
                        targetW,
                        targetH,
                        backgroundCrop,
                        backgroundSourceAspectRatio,
                        videoFrameMs
                )
                return true
            }
        }
        val observer = card.viewTreeObserver
        if (observer.isAlive) {
            observer.addOnPreDrawListener(listener)
        } else {
            card.post {
                if (mediaBackground.getTag(
                        R.id.tag_thing_card_media_background_bind_token
                    ) != bindToken
                ) {
                    return@post
                }
                if (!mediaBackground.isVisible) return@post
                val targetW = getThingCardMediaBackgroundTargetWidth(
                    holder, intendedTargetWidth
                )
                val targetH = getThingCardMediaBackgroundEffectiveTargetHeight(
                    holder, intendedTargetHeight
                )
                if (targetW <= 0 || targetH <= 0) return@post
                setThingCardMediaBackgroundOverlaySize(holder, targetW, targetH)
                loadThingCardMediaBackground(
                        holder,
                        thing,
                        pathName,
                        targetW,
                        targetH,
                        backgroundCrop,
                        backgroundSourceAspectRatio,
                        videoFrameMs
                )
            }
        }
    }

    private fun getThingCardMediaBackgroundEffectiveTargetHeight(
        holder: BaseThingViewHolder,
        thing: Thing,
        mediaSource: ThingCardMediaHelper.MediaSource
    ): Int {
        return getThingCardMediaBackgroundEffectiveTargetHeight(
            holder,
            getThingCardMediaBackgroundClampedTargetHeight(thing, mediaSource)
        )
    }

    private fun getThingCardMediaBackgroundEffectiveTargetHeight(
        holder: BaseThingViewHolder,
        clampedTargetHeight: Int
    ): Int {
        return max(
            clampedTargetHeight,
            measureThingCardMediaBackgroundNaturalContentHeight(holder)
        )
    }

    private fun getThingCardMediaBackgroundTargetWidth(
        holder: BaseThingViewHolder,
        intendedTargetWidth: Int
    ): Int {
        val measuredContentWidth = getCardContentWidthForMeasuredHolder(holder)
        if (measuredContentWidth > 0) return measuredContentWidth
        if (intendedTargetWidth > 0) return intendedTargetWidth
        return holder.ivMediaBackground?.width?.takeIf { it > 0 }
            ?: holder.cv?.width?.takeIf { it > 0 }
            ?: 0
    }

    private fun setThingCardMediaBackgroundOverlaySize(
        holder: BaseThingViewHolder,
        width: Int,
        height: Int
    ) {
        setThingCardMediaBackgroundOverlaySize(holder.ivMediaBackground, width, height)
        setThingCardMediaBackgroundOverlaySize(holder.vMediaBackgroundMask, width, height)
    }

    private fun setThingCardMediaBackgroundOverlaySize(
        view: View?,
        width: Int,
        height: Int
    ) {
        if (view == null) return
        val lp = view.layoutParams as FrameLayout.LayoutParams
        val targetWidth = if (width > 0) width else ViewGroup.LayoutParams.MATCH_PARENT
        val targetHeight = if (height > 0) height else ViewGroup.LayoutParams.MATCH_PARENT
        if (lp.width != targetWidth || lp.height != targetHeight) {
            lp.width = targetWidth
            lp.height = targetHeight
            view.layoutParams = lp
        }
    }

    private fun resetThingCardMediaBackgroundOverlaySize(holder: BaseThingViewHolder) {
        resetThingCardMediaBackgroundOverlaySize(holder.ivMediaBackground)
        resetThingCardMediaBackgroundOverlaySize(holder.vMediaBackgroundMask)
    }

    private fun resetThingCardMediaBackgroundOverlaySize(view: View?) {
        if (view == null) return
        val lp = view.layoutParams as FrameLayout.LayoutParams
        if (lp.width != ViewGroup.LayoutParams.MATCH_PARENT ||
                lp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            view.layoutParams = lp
        }
    }

    private fun resetThingCardMediaBackgroundContentExpansion(holder: BaseThingViewHolder) {
        val textLp = holder.llTextContent!!.layoutParams as LinearLayout.LayoutParams
        if (textLp.height != ViewGroup.LayoutParams.WRAP_CONTENT || textLp.weight != 0f) {
            textLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            textLp.weight = 0f
            holder.llTextContent.layoutParams = textLp
        }

        val spacer = holder.vBottomStatusSpacer!!
        val spacerLp = spacer.layoutParams as LinearLayout.LayoutParams
        if (spacer.visibility != View.GONE || spacerLp.height != 0 || spacerLp.weight != 0f) {
            spacer.visibility = View.GONE
            spacerLp.height = 0
            spacerLp.weight = 0f
            spacer.layoutParams = spacerLp
        }
    }

    private fun updateThingCardMediaBackgroundInlineCount(
        holder: BaseThingViewHolder,
        thing: Thing
    ) {
        val str = AttachmentHelper.getImageAttachmentCountStr(thing.attachment, mContext)
        if (str == null) {
            holder.llMediaCount!!.visibility = View.GONE
            setThingCardPaddingBottomHeight(holder, THING_CARD_DEFAULT_PADDING_BOTTOM_DP)
            return
        }

        val p = (mDensity * 16).toInt()
        val topMargin = (mDensity * 8).toInt()
        val bottomMargin = (mDensity * 12).toInt()
        val countLp = holder.llMediaCount!!.layoutParams as FrameLayout.LayoutParams
        val gravity = Gravity.BOTTOM or Gravity.LEFT
        if (countLp.gravity != gravity ||
                countLp.leftMargin != p ||
                countLp.topMargin != topMargin ||
                countLp.rightMargin != p ||
                countLp.bottomMargin != bottomMargin) {
            countLp.gravity = gravity
            countLp.setMargins(p, topMargin, p, bottomMargin)
            holder.llMediaCount.layoutParams = countLp
        }
        holder.llMediaCount!!.visibility = View.VISIBLE
        holder.llMediaCount.alpha = 1.0f
        holder.tvMediaCount!!.setPadding(0, 0, 0, 0)
        holder.tvMediaCount.text = str
        applyThingCardOverlayMediaCountColors(holder)
        setThingCardMediaCountIconLayout(
            holder.ivMediaCount,
            THING_CARD_MEDIA_COUNT_ICON_NORMAL_WIDTH_DP,
            THING_CARD_COUNT_ICON_NORMAL_HEIGHT_DP,
            0
        )
        setThingCardCountTextStartMargin(
            holder.tvMediaCount,
            THING_CARD_MEDIA_COUNT_TEXT_NORMAL_MARGIN_START_DP
        )
        setThingCardPaddingBottomHeight(holder, THING_CARD_MEDIA_COUNT_PADDING_BOTTOM_DP)
    }

    private fun setThingCardPaddingBottomHeight(holder: BaseThingViewHolder, heightDp: Int) {
        val targetHeight = (mDensity * heightDp).toInt()
        val lp = holder.vPaddingBottom!!.layoutParams as LinearLayout.LayoutParams
        if (lp.height != targetHeight) {
            lp.height = targetHeight
            holder.vPaddingBottom.layoutParams = lp
        }
    }

    private fun updateThingCardMediaBackgroundBottomStatusLayout(
        holder: BaseThingViewHolder,
        bindToken: String,
        targetHeight: Int
    ) {
        if (!hasThingCardBottomStatus(holder)) {
            holder.vBottomStatusSpacer!!.visibility = View.GONE
            return
        }

        holder.llTextContent!!.post {
            val mediaBackground = holder.ivMediaBackground!!
            if (!mediaBackground.isVisible) return@post
            if (mediaBackground.getTag(R.id.tag_thing_card_media_background_bind_token) !=
                    bindToken) {
                return@post
            }

            val naturalHeight = measureThingCardMediaBackgroundNaturalContentHeight(holder)
            val expandedHeight = max(targetHeight, naturalHeight)
            if (expandedHeight <= 0) return@post

            val textLp = holder.llTextContent.layoutParams as LinearLayout.LayoutParams
            if (textLp.height != expandedHeight) {
                textLp.height = expandedHeight
                textLp.weight = 0f
                holder.llTextContent.layoutParams = textLp
            }

            val spacer = holder.vBottomStatusSpacer!!
            val spacerLp = spacer.layoutParams as LinearLayout.LayoutParams
            if (spacer.visibility != View.VISIBLE || spacerLp.weight != 1f) {
                spacer.visibility = View.VISIBLE
                spacerLp.height = 0
                spacerLp.weight = 1f
                spacer.layoutParams = spacerLp
            }
        }
    }

    private fun hasThingCardBottomStatus(holder: BaseThingViewHolder): Boolean {
        return holder.llAudioAttachment!!.isVisible
                || holder.rlReminder!!.isVisible
                || holder.rlHabit!!.isVisible
    }

    private fun measureThingCardMediaBackgroundNaturalContentHeight(
        holder: BaseThingViewHolder
    ): Int {
        val textContent = holder.llTextContent ?: return 0
        val width = getCardContentWidthForMeasuredHolder(holder)
            .takeIf { it > 0 }
            ?: textContent.width.takeIf { it > 0 }
            ?: holder.cv?.width?.takeIf { it > 0 }
            ?: 0
        if (width <= 0) return textContent.measuredHeight

        val textLp = textContent.layoutParams as LinearLayout.LayoutParams
        val oldTextHeight = textLp.height
        val oldTextWeight = textLp.weight
        val spacer = holder.vBottomStatusSpacer
        val spacerLp = spacer?.layoutParams as? LinearLayout.LayoutParams
        val oldSpacerVisibility = spacer?.visibility
        val oldSpacerHeight = spacerLp?.height
        val oldSpacerWeight = spacerLp?.weight

        return try {
            textLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            textLp.weight = 0f
            if (spacer != null && spacerLp != null) {
                spacer.visibility = View.GONE
                spacerLp.height = 0
                spacerLp.weight = 0f
            }
            val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            textContent.measure(widthSpec, heightSpec)
            textContent.measuredHeight
        } finally {
            textLp.height = oldTextHeight
            textLp.weight = oldTextWeight
            if (spacer != null && spacerLp != null) {
                if (oldSpacerVisibility != null) {
                    spacer.visibility = oldSpacerVisibility
                }
                if (oldSpacerHeight != null && oldSpacerWeight != null) {
                    spacerLp.height = oldSpacerHeight
                    spacerLp.weight = oldSpacerWeight
                }
            }
        }
    }

    private fun getCardContentWidthForMeasuredHolder(holder: BaseThingViewHolder): Int {
        val lpWidth = holder.llContent?.layoutParams?.width ?: 0
        return if (lpWidth > 0) lpWidth else 0
    }

    private fun hideThingCardMediaBackground(holder: BaseThingViewHolder) {
        val mediaBackground = holder.ivMediaBackground!!
        mediaBackground.setTag(R.id.tag_thing_card_media_background_load_key, null)
        mediaBackground.setTag(R.id.tag_thing_card_media_background_render_request, null)
        mediaBackground.setTag(R.id.tag_thing_card_media_background_bind_token, null)
        mImageRequestManager!!.clear(mediaBackground)
        mediaBackground.visibility = View.GONE
        resetThingCardMediaBackgroundOverlaySize(holder)
        holder.vMediaBackgroundMask!!.visibility = View.GONE
        holder.llMediaCount!!.visibility = View.GONE
    }

    private fun normalizeThingCardMaskStrength(value: Double): Float {
        if (value.isNaN() || value.isInfinite()) {
            return getThingCardDefaultMaskStrength().toFloat()
        }
        return max(0.0, min(1.0, value)).toFloat()
    }

    private fun getThingCardDefaultMaskStrength(): Double {
        return mContext!!.resources.getInteger(
            R.integer.thing_card_media_background_default_mask_strength_percent
        ) / 100.0
    }

    private fun updateCardForImageAttachment(holder: BaseThingViewHolder, thing: Thing) {
        val attachment: String? = thing.attachment
        val mediaSource = ThingCardMediaHelper.resolveEffectiveMediaSource(thing)
        if (mediaSource != null) {
            holder.llInlineMediaAttachment!!.visibility = View.GONE
            setThingCardMediaDebugInfo(holder, null)
            if (thing.thingCardAppearance.mediaBackgroundEnabled) {
                updateCardForMediaBackground(holder, thing, mediaSource)
                return
            }

            hideThingCardMediaBackground(holder)
            holder.flImageAttachment!!.visibility = View.VISIBLE
            val placement = getEffectiveThingCardImagePlacement(thing)
            val debugInfo = if (shouldLogThingCardMediaDebug(thing, mediaSource, placement)) {
                createThingCardMediaDebugInfo(thing, mediaSource, placement)
            } else {
                null
            }
            setThingCardMediaDebugInfo(holder, debugInfo)
            val pathName = mediaSource.pathName

            val sideImage = isSideImagePlacement(placement)
            val sideImageProjection = if (sideImage) {
                getSideImageProjection(holder, thing)
            } else {
                null
            }
            applyThingCardImagePlacementLayout(
                holder,
                thing,
                placement,
                sideImageProjection
            )

            val imageW = if (sideImage) {
                (holder.flImageAttachment.layoutParams as LinearLayout.LayoutParams).width
            } else {
                getCardContentWidth(thing)
            }
            val imageH = getImageHeight(thing, imageW)
            val thumbnailCrop = if (sideImage) {
                getThingCardSidePanelCrop(thing, mediaSource)
            } else {
                getThingCardThumbnailCrop(thing, mediaSource)
            }
            val videoFrameMs = getThingCardVideoFrameMs(thing, mediaSource)

            val paramsLayout = holder.flImageAttachment.layoutParams as LinearLayout.LayoutParams
            if (!sideImage) {
                paramsLayout.width = ViewGroup.LayoutParams.MATCH_PARENT
                paramsLayout.height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            holder.flImageAttachment.layoutParams = paramsLayout

            val bindToken = buildString {
                append(thing.id)
                append(":")
                append(placement)
                append(":")
                append(mediaSource.typePathName)
                if (sideImage && sideImageProjection != null) {
                    append(":")
                    append(sideImageProjection.imageWidth)
                    append("x")
                    append(sideImageProjection.imageHeight)
                    append(":")
                    append(thumbnailCrop)
                    append(":")
                    append(videoFrameMs)
                }
            }
            holder.flImageAttachment.setTag(R.id.tag_thing_card_side_image_bind_token, bindToken)

            if (sideImage) {
                setThingCardImageFrameSize(
                    holder,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                logThingCardMediaDebug(
                    debugInfo,
                    "bind side imageW=$imageW imageH=${sideImageProjection!!.imageHeight} " +
                            "targetAspect=${getThingCardThumbnailTargetAspectRatio(thing)} " +
                            "surfaceAvailableHeight=${getThingCardSurfaceAvailableHeight()} " +
                            "videoFrameMs=$videoFrameMs crop=$thumbnailCrop " +
                            "flLp=${debugLayoutParamsSize(holder.flImageAttachment)} " +
                            "ivLp=${debugLayoutParamsSize(holder.ivImageAttachment)} " +
                            "fl=${debugViewSize(holder.flImageAttachment)} " +
                            "iv=${debugViewSize(holder.ivImageAttachment)}"
                )
                loadThingCardImage(
                    holder, pathName, imageW, sideImageProjection!!.imageHeight,
                    thumbnailCrop, videoFrameMs
                )
                syncSideImageProjectionAfterMeasure(
                    holder, bindToken, thing, pathName, thumbnailCrop, videoFrameMs
                )
            } else {
                setThingCardImageFrameSize(
                    holder,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    imageH
                )
                logThingCardMediaDebug(
                    debugInfo,
                    "bind top-bottom imageW=$imageW imageH=$imageH " +
                            "targetAspect=${getThingCardThumbnailTargetAspectRatio(thing)} " +
                            "surfaceAvailableHeight=${getThingCardSurfaceAvailableHeight()} " +
                            "videoFrameMs=$videoFrameMs crop=$thumbnailCrop " +
                            "flLp=${debugLayoutParamsSize(holder.flImageAttachment)} " +
                            "ivLp=${debugLayoutParamsSize(holder.ivImageAttachment)} " +
                            "fl=${debugViewSize(holder.flImageAttachment)} " +
                            "iv=${debugViewSize(holder.ivImageAttachment)}"
                )
                loadThingCardImage(
                    holder,
                    pathName,
                    imageW,
                    imageH,
                    thumbnailCrop,
                    videoFrameMs
                )
            }

            if (holder.tvTitle!!.isGone
                && holder.tvContent!!.isGone
                && holder.rvChecklist!!.isGone
                && holder.llAudioAttachment!!.isGone
                && holder.rlReminder!!.isGone
                && holder.rlHabit!!.isGone
            ) {
                holder.vPaddingBottom!!.visibility = View.GONE
            } else if (placement == Thing.THING_CARD_IMAGE_PLACEMENT_BOTTOM) {
                holder.vPaddingBottom!!.visibility = View.GONE
            } else {
                holder.vPaddingBottom!!.visibility = View.VISIBLE
            }

            holder.tvImageCount!!.text =
                AttachmentHelper.getImageAttachmentCountStr(attachment, mContext)
            holder.tvImageCount.visibility = View.VISIBLE
            holder.tvImageCount.setTextColor(
                textColorSecondary(getThingCardForegroundBaseColor(thing))
            )
            updateThingCardImageCountLayout(holder, placement)

            if (getCurrentMode() == ModeManager.NORMAL) {
                holder.vImageCover!!.visibility = View.GONE
            } else {
                holder.vImageCover!!.visibility =
                    if (thing.isSelected()) View.GONE else View.VISIBLE
            }
        } else {
            hideThingCardMediaBackground(holder)
            setThingCardMediaDebugInfo(holder, null)
            holder.flImageAttachment!!.setTag(R.id.tag_thing_card_side_image_bind_token, null)
            holder.ivImageAttachment!!.setTag(R.id.tag_thing_card_image_load_key, null)
            holder.ivImageAttachment!!.setTag(R.id.tag_thing_card_image_render_request, null)
            mImageRequestManager!!.clear(holder.ivImageAttachment!!)
            holder.vPaddingBottom!!.visibility = View.VISIBLE
            holder.flImageAttachment!!.visibility = View.GONE
            holder.tvImageCount!!.visibility = View.GONE
            holder.vImageCover!!.visibility = View.GONE
            updateCardForHiddenMediaAttachmentCount(holder, thing)
        }
    }

    private fun updateCardForHiddenMediaAttachmentCount(
        holder: BaseThingViewHolder,
        thing: Thing
    ) {
        if (!ThingCardAppearance.isMediaSourceNone(thing.thingCardAppearance.mediaSourceKey)) {
            holder.llInlineMediaAttachment!!.visibility = View.GONE
            return
        }

        val str = AttachmentHelper.getImageAttachmentCountStr(thing.attachment, mContext)
        if (str == null) {
            holder.llInlineMediaAttachment!!.visibility = View.GONE
            return
        }

        holder.llInlineMediaAttachment!!.visibility = View.VISIBLE
        holder.llInlineMediaAttachment.alpha = 1.0f
        val dp16 = (mDensity * 16).toInt()
        holder.llInlineMediaAttachment.setPadding(dp16, dp16 / 4 * 3, dp16, 0)
        setThingCardPaddingBottomHeight(holder, THING_CARD_DEFAULT_PADDING_BOTTOM_DP)
        holder.tvInlineMediaCount!!.text = str

        val foregroundBaseColor = getThingCardForegroundBaseColor(thing)
        holder.tvInlineMediaCount.setTextColor(textColorTertiary(foregroundBaseColor))
        applyThingCardMediaCountIcon(holder.ivInlineMediaCount, foregroundBaseColor)
    }

    protected open fun getThingCardForegroundThumbnailHeight(thing: Thing, imageW: Int): Int {
        val aspectRatio = getThingCardThumbnailTargetAspectRatio(thing)
        val rawHeight = max(1, (imageW / aspectRatio).toInt())
        val availableHeight = getThingCardSurfaceAvailableHeight()
        val minHeight = max(1, availableHeight * getThumbnailHeightMinPercent(thing) / 100)
        val maxHeight = max(
            minHeight,
            availableHeight * mContext!!.resources.getInteger(
                R.integer.thing_card_thumbnail_max_height_percent
            ) / 100
        )
        return max(minHeight, min(rawHeight, maxHeight))
    }

    private fun getImageHeight(thing: Thing, imageW: Int): Int {
        return getThingCardForegroundThumbnailHeight(thing, imageW)
    }

    private fun updateCardForAudioAttachment(holder: BaseThingViewHolder, thing: Thing) {
        val attachment: String? = thing.attachment
        val str: String? = AttachmentHelper.getAudioAttachmentCountStr(attachment, mContext)
        if (str == null) {
            holder.llAudioAttachment!!.visibility = View.GONE
        } else {
            holder.llAudioAttachment!!.visibility = View.VISIBLE
            val p = (mDensity * 16).toInt()
            holder.llAudioAttachment.setPadding(p, p / 4 * 3, p, 0)

            holder.tvAudioCount!!.text = str

            val foregroundBaseColor = getThingCardForegroundBaseColor(thing)
            val dark = BackgroundUtil.isLight(foregroundBaseColor)
            holder.ivAudioCount!!.setImageResource(
                if (dark)
                    R.drawable.card_audio_attachment_black
                else R.drawable.card_audio_attachment
            )
            holder.tvAudioCount.setTextColor(textColorTertiary(foregroundBaseColor))
        }
    }

    private fun updateCardSeparatorsIfNeeded(holder: BaseThingViewHolder) {
        if (holder.flImageAttachment!!.isVisible
            && holder.tvTitle!!.isGone
            && holder.tvContent!!.isGone
            && holder.rvChecklist!!.isGone
            && holder.llInlineMediaAttachment!!.isGone
            && holder.llAudioAttachment!!.isGone
        ) {
            if (holder.rlReminder!!.isVisible) {
                holder.vReminderSeparator!!.visibility = View.GONE
            } else if (holder.rlHabit!!.isVisible) {
                holder.vHabitSeparator1!!.visibility = View.GONE
            }
        } else {
            if (holder.rlReminder!!.isVisible) {
                holder.vReminderSeparator!!.visibility = View.VISIBLE
            } else if (holder.rlHabit!!.isVisible) {
                holder.vHabitSeparator1!!.visibility = View.VISIBLE
            }
        }
    }

    private fun enlargeHiddenMediaCountLayoutIfNeeded(holder: BaseThingViewHolder) {
        if (holder.llInlineMediaAttachment!!.visibility != View.VISIBLE) {
            return
        }

        val llp2 = holder.tvInlineMediaCount!!.layoutParams as LinearLayout.LayoutParams
        val dp16 = (mDensity * 16).toInt()
        if (holder.flImageAttachment!!.isGone
            && holder.tvTitle!!.isGone
            && holder.tvContent!!.isGone
            && holder.rvChecklist!!.isGone
            && holder.llAudioAttachment!!.isGone
        ) {
            setThingCardMediaCountIconLayout(
                holder.ivInlineMediaCount,
                THING_CARD_MEDIA_COUNT_ICON_LARGE_WIDTH_DP,
                THING_CARD_COUNT_ICON_LARGE_HEIGHT_DP,
                THING_CARD_COUNT_ICON_LARGE_TOP_MARGIN_DP
            )
            holder.tvInlineMediaCount.textSize = 18f

            val marginStart = (mDensity *
                    THING_CARD_MEDIA_COUNT_TEXT_LARGE_MARGIN_START_DP).toInt()
            llp2.setMargins(marginStart, llp2.topMargin, llp2.rightMargin, llp2.bottomMargin)
            llp2.marginStart = marginStart

            holder.llInlineMediaAttachment.setPadding(dp16, dp16, dp16, 0)
        } else {
            setThingCardMediaCountIconLayout(
                holder.ivInlineMediaCount,
                THING_CARD_MEDIA_COUNT_ICON_NORMAL_WIDTH_DP,
                THING_CARD_COUNT_ICON_NORMAL_HEIGHT_DP,
                0
            )
            holder.tvInlineMediaCount.textSize = 11f

            val marginStart = (mDensity *
                    THING_CARD_MEDIA_COUNT_TEXT_NORMAL_MARGIN_START_DP).toInt()
            llp2.setMargins(marginStart, llp2.topMargin, llp2.rightMargin, llp2.bottomMargin)
            llp2.marginStart = marginStart

            holder.llInlineMediaAttachment.setPadding(dp16, dp16 / 4 * 3, dp16, 0)
        }
    }

    private fun enlargeAudioLayoutIfNeeded(holder: BaseThingViewHolder) {
        if (holder.llAudioAttachment!!.visibility != View.VISIBLE) {
            return
        }

        val llp2 = holder.tvAudioCount!!.layoutParams as LinearLayout.LayoutParams
        val dp8  = (mDensity * 8).toInt()
        val dp12 = (mDensity * 12).toInt()
        val dp16 = (mDensity * 16).toInt()
        if (holder.flImageAttachment!!.isGone
            && holder.tvTitle!!.isGone
            && holder.tvContent!!.isGone
            && holder.rvChecklist!!.isGone
            && holder.llInlineMediaAttachment!!.isGone
        ) {
            setThingCardAttachmentCountIconLayout(
                holder.ivAudioCount,
                THING_CARD_COUNT_ICON_LARGE_WIDTH_DP,
                THING_CARD_COUNT_ICON_LARGE_HEIGHT_DP,
                THING_CARD_COUNT_ICON_LARGE_TOP_MARGIN_DP
            )
            holder.tvAudioCount.textSize = 18f

            llp2.setMargins(dp12, llp2.topMargin, llp2.rightMargin, llp2.bottomMargin)
            llp2.marginStart = dp12

            holder.llAudioAttachment.setPadding(dp16, dp16, dp16, 0)
        } else {
            setThingCardAttachmentCountIconLayout(
                holder.ivAudioCount,
                THING_CARD_COUNT_ICON_NORMAL_WIDTH_DP,
                THING_CARD_COUNT_ICON_NORMAL_HEIGHT_DP,
                0
            )
            holder.tvAudioCount.textSize = 11f

            llp2.setMargins(dp8, llp2.topMargin, llp2.rightMargin, llp2.bottomMargin)
            llp2.marginStart = dp8

            holder.llAudioAttachment.setPadding(dp16, dp16 / 4 * 3, dp16, 0)
        }
    }

    private fun setThingCardAttachmentCountIconLayout(
        icon: ImageView?,
        widthDp: Int,
        heightDp: Int,
        topMarginDp: Int
    ) {
        icon ?: return
        icon.scaleType = ImageView.ScaleType.FIT_CENTER
        val lp = icon.layoutParams as LinearLayout.LayoutParams
        val width = (mDensity * widthDp).toInt()
        val height = (mDensity * heightDp).toInt()
        val topMargin = (mDensity * topMarginDp).toInt()
        if (lp.width != width || lp.height != height || lp.topMargin != topMargin) {
            lp.width = width
            lp.height = height
            lp.topMargin = topMargin
            icon.layoutParams = lp
        }
    }

    private fun setThingCardMediaCountIconLayout(
        icon: ImageView?,
        widthDp: Int,
        heightDp: Int,
        topMarginDp: Int
    ) {
        setThingCardAttachmentCountIconLayout(icon, widthDp, heightDp, topMarginDp)
        val shift = (mDensity * THING_CARD_MEDIA_COUNT_ICON_SHIFT_DP).toInt()
        if (icon != null && (
                icon.paddingLeft != shift ||
                icon.paddingTop != shift ||
                icon.paddingRight != 0 ||
                icon.paddingBottom != 0)) {
            icon.setPadding(shift, shift, 0, 0)
        }
    }

    private fun setThingCardCountTextStartMargin(textView: TextView?, marginStartDp: Int) {
        textView ?: return
        val lp = textView.layoutParams as LinearLayout.LayoutParams
        val marginStart = (mDensity * marginStartDp).toInt()
        if (lp.leftMargin != marginStart || lp.marginStart != marginStart) {
            lp.setMargins(marginStart, lp.topMargin, lp.rightMargin, lp.bottomMargin)
            lp.marginStart = marginStart
            textView.layoutParams = lp
        }
    }

    private fun updateFullSpanSparseMinHeight(holder: BaseThingViewHolder, thing: Thing) {
        if (!isFullSpanThingCard(thing)) return
        if (holder.ivMediaBackground!!.isVisible) return
        if (holder.llInlineMediaAttachment!!.isVisible) return
        if (holder.flImageAttachment!!.isVisible
            || holder.rvChecklist!!.isVisible
            || holder.rlReminder!!.isVisible
            || holder.rlHabit!!.isVisible
        ) {
            return
        }
        if (holder.tvContent!!.isVisible && thing.content!!.length > SHORT_TEXT_MAX_LENGTH) {
            return
        }
        if (holder.tvTitle!!.isVisible
            || holder.tvContent!!.isVisible
            || holder.llAudioAttachment!!.isVisible
            || holder.ivPrivateThing!!.isVisible
        ) {
            holder.llContent!!.minimumHeight = mContext!!.resources.getDimensionPixelSize(
                R.dimen.thing_card_full_span_sparse_min_height
            )
        }
    }

    private fun updateCardForDoing(holder: BaseThingViewHolder, thing: Thing) {
        if (App.getDoingThingId() == thing.id) {
            holder.flDoing!!.visibility = View.VISIBLE
            // 蒙层保持 match_parent，铺满由 InterceptTouchCardView.onMeasure 在卡片尺寸确定后强制
            // 重测保证（此 RecyclerView 下 FrameLayout 第二趟 match_parent 测量不一定触发）。这里
            // 只按卡片最终高度算图标 / 文字缩放：已稳定布局立即算，否则布局后再算。
            val cv = holder.cv!!
            if (cv.height > 0 && !cv.isLayoutRequested) {
                holder.applyDoingCoverScale()
            } else {
                cv.post { holder.applyDoingCoverScale() }
            }
        } else {
            holder.flDoing!!.visibility = View.GONE
        }
    }

    private fun setCardAppearance(
        holder: BaseThingViewHolder,
        background: ThingBackground?, selected: Boolean
    ) {
        val cv: CardView = holder.cv!!
        val currentMode = getCurrentMode()
        val dimUnselectedContent = shouldDimUnselectedContent(currentMode) && !selected
        applyUnselectedContentAlpha(holder, background, dimUnselectedContent)

        if (currentMode == ModeManager.MOVING) {
            if (selected) {
                scheduleMovingCardScaleRecoveryIfReleased(cv, "thing")
                ObjectAnimator.ofFloat(cv, "scaleX", 1.11f).setDuration(96).start()
                ObjectAnimator.ofFloat(cv, "scaleY", 1.11f).setDuration(96).start()
                ObjectAnimator.ofFloat(
                    cv, "cardElevation",
                    mContext!!.resources.getDimension(R.dimen.thing_card_dragging_elevation)
                )
                    .setDuration(96).start()
                BackgroundUtil.applyCardBackground(cv, background)
            } else {
                setNormalCardGeometry(cv)
                BackgroundUtil.applyCardBackground(cv, lightVariant(background))
            }
        } else if (currentMode == ModeManager.SELECTING) {
            setNormalCardGeometry(cv)
            if (selected) {
                BackgroundUtil.applyCardBackground(cv, background)
            } else {
                BackgroundUtil.applyCardBackground(cv, lightVariant(background))
            }
        } else {
            setNormalCardGeometry(cv)
            BackgroundUtil.applyCardBackground(cv, background)
        }

        val cardIsLight = background != null
                && BackgroundUtil.isLight(background)
        holder.cv.foreground = ContextCompat.getDrawable(
            mContext!!,
            if (cardIsLight)
                R.drawable.selectable_item_background
            else R.drawable.selectable_item_background_light
        )
    }

    /** Produce a washed-out variant of `bg` for unselected cards. */
    private fun lightVariant(bg: ThingBackground?): ThingBackground {
        if (bg!!.mode === ThingBackground.Mode.PURE) {
            return ThingBackground.pure(DisplayUtil.getLightColor(bg.color, mContext))
        }
        return ThingBackground.gradient(
            DisplayUtil.getLightColor(bg.color,    mContext),
            DisplayUtil.getLightColor(bg.endColor, mContext),
            bg.orientation
        )
    }

    override fun getItemViewType(position: Int): Int {
        return getThingAt(position)!!.type
    }

    override fun getItemCount(): Int {
        return getEntryCount()
    }

    open class BaseThingViewHolder(item: View?) : BaseViewHolder(item) {

        @JvmField val cv: InterceptTouchCardView? = f(R.id.cv_thing)
        @JvmField val ivMediaBackground: ImageView? = f(R.id.iv_thing_media_background)
        @JvmField val vMediaBackgroundMask: View? = f(R.id.view_thing_media_background_mask)
        @JvmField val llContent: LinearLayout? = f(R.id.ll_thing_content)
        @JvmField val llTextContent: LinearLayout? = f(R.id.ll_thing_text_content)
        @JvmField val vPaddingBottom: View? = f(R.id.view_thing_padding_bottom)

        @JvmField val ivStickyOngoing: ImageView?   = f(R.id.iv_thing_sticky_ongoing)
        @JvmField val flDoing: FrameLayout? = f(R.id.fl_thing_doing_cover)
        @JvmField val tvDoing: TextView? = f(R.id.tv_thing_doing)

        @JvmField val flImageAttachment: FrameLayout? = f(R.id.fl_thing_image)
        @JvmField val ivImageAttachment: ImageView?   = f(R.id.iv_thing_image)
        @JvmField val tvImageCount: TextView?    = f(R.id.tv_thing_image_attachment_count)
        @JvmField val pbLoading: ProgressBar? = f(R.id.pb_thing_image_attachment)
        @JvmField val vImageCover: View?        = f(R.id.view_thing_image_cover)

        @JvmField val tvTitle: TextView?  = f(R.id.tv_thing_title)
        @JvmField val ivPrivateThing: ImageView? = f(R.id.iv_private_thing)

        @JvmField val tvContent: TextView?     = f(R.id.tv_thing_content)
        @JvmField val rvChecklist: RecyclerView? = f(R.id.rv_check_list)

        @JvmField val vBottomStatusSpacer: View? = f(R.id.view_thing_bottom_status_spacer)
        @JvmField val llMediaCount: LinearLayout? = f(R.id.ll_thing_media_attachment_count)
        @JvmField val ivMediaCount: ImageView? = f(R.id.iv_thing_media_attachment_count)
        @JvmField val tvMediaCount: TextView? = f(R.id.tv_thing_media_attachment_count)

        @JvmField val llInlineMediaAttachment: LinearLayout? =
            f(R.id.ll_thing_inline_media_attachment_count)
        @JvmField val ivInlineMediaCount: ImageView? =
            f(R.id.iv_thing_inline_media_attachment_count)
        @JvmField val tvInlineMediaCount: TextView? =
            f(R.id.tv_thing_inline_media_attachment_count)

        @JvmField val llAudioAttachment: LinearLayout? = f(R.id.ll_thing_audio_attachment)
        @JvmField val ivAudioCount: ImageView?    = f(R.id.iv_thing_audio_attachment_count)
        @JvmField val tvAudioCount: TextView?     = f(R.id.tv_thing_audio_attachment_count)

        @JvmField val rlReminder: RelativeLayout? = f(R.id.rl_thing_reminder)
        @JvmField val vReminderSeparator: View?           = f(R.id.view_reminder_separator)
        @JvmField val ivReminder: ImageView?      = f(R.id.iv_thing_reminder)
        @JvmField val tvReminderTime: TextView?       = f(R.id.tv_thing_reminder_time)

        @JvmField val rlHabit: RelativeLayout?       = f(R.id.rl_thing_habit)
        @JvmField val vHabitSeparator1: View?                 = f(R.id.view_habit_separator_1)
        @JvmField val ivHabit: ImageView?            = f(R.id.iv_thing_habit)
        @JvmField val tvHabitSummary: TextView?             = f(R.id.tv_thing_habit_summary)
        @JvmField val tvHabitNextReminder: TextView?        = f(R.id.tv_thing_habit_next_reminder)
        @JvmField val vHabitSeparator2: View?                 = f(R.id.view_habit_separator_2)
        @JvmField val llHabitRecord: LinearLayout?         = f(R.id.ll_thing_habit_record)
        @JvmField val tvHabitLastFive: TextView?            = f(R.id.tv_thing_habit_last_five_record)
        @JvmField val habitRecordPresenter: HabitRecordPresenter = HabitRecordPresenter(arrayOf(
            f(R.id.iv_thing_habit_record_1),
            f(R.id.iv_thing_habit_record_2),
            f(R.id.iv_thing_habit_record_3),
            f(R.id.iv_thing_habit_record_4),
            f(R.id.iv_thing_habit_record_5)
        ))
        @JvmField val tvHabitFinishedThisT: TextView? = f(R.id.tv_thing_habit_finished_this_t)

        init {
            pbLoading!!.post {
                BackgroundUtil.applyProgressBarGradient(
                    pbLoading!!, App.defaultAccentBackground
                )
            }
        }

        /**
         * 按当前卡片高度，把"正在做"蒙层里的图标、文字、图文间距统一等比缩放，并按高度比例
         * 留出上下间距，使卡片越矮内容越小且图标与文字始终协调；卡片足够高时保持原始大小。
         * bind 与右滑预览两条路径共用：调用前卡片需已测量（cv.height > 0），蒙层一出现即为
         * 最终大小、不再 post 后跳变。
         */
        fun applyDoingCoverScale() {
            val tv = tvDoing ?: return
            val card = cv ?: return
            val cardHeight = card.height
            val cardWidth = card.width
            if (cardHeight <= 0 || cardWidth <= 0) return
            val res = itemView.resources
            val density = res.displayMetrics.density
            val intrinsicIconH = 48f * density
            val intrinsicIconW = 44f * density
            val baseTextSizePx = res.getDimension(R.dimen.tv_thing_doing_text_size)
            val baseDrawablePadding = 4f * density

            // 高度方向：按 18% 上下留白限制图标高度，矮卡片整体缩小。
            val minVerticalPadding = cardHeight * 0.18f
            val maxIconH = cardHeight - minVerticalPadding * 2f
            val heightScale = if (maxIconH > 0f && maxIconH < intrinsicIconH) {
                maxIconH / intrinsicIconH
            } else {
                1f
            }

            // 宽度方向：让"图标 + 间距 + 文字"在卡片宽度内一行放下、不换行、不贴边。窄卡片
            // （如大文件夹缩略图里宽 < 高的预览卡）下这是决定因素，避免图标贴左边、文字换行，
            // 使蒙层始终是正常列表样子的等比缩小版，而非按高度单独缩放的变形。
            val tvMarginStart =
                (tv.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart?.toFloat() ?: 0f
            val measurePaint = android.text.TextPaint(tv.paint)
            measurePaint.textSize = baseTextSizePx
            val textWidth = measurePaint.measureText(tv.text?.toString() ?: "")
            val intrinsicContentWidth = intrinsicIconW + baseDrawablePadding + textWidth
            val availableWidth = cardWidth - tvMarginStart - cardWidth * 0.16f
            val widthScale = if (intrinsicContentWidth > 0f && availableWidth < intrinsicContentWidth) {
                (availableWidth / intrinsicContentWidth).coerceAtLeast(0f)
            } else {
                1f
            }

            val scale = minOf(heightScale, widthScale)
            val drawable = ContextCompat.getDrawable(itemView.context, R.drawable.vec_ic_doing_thing)
                ?.mutate() ?: return
            drawable.setBounds(0, 0, (intrinsicIconW * scale).toInt(), (intrinsicIconH * scale).toInt())
            tv.maxLines = 1
            tv.setCompoundDrawablesRelative(drawable, null, null, null)
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, baseTextSizePx * scale)
            tv.compoundDrawablePadding = (baseDrawablePadding * scale).toInt()
        }
    }

    private data class ThingCardSideImageProjection(
        val imageWidth: Int,
        val imageHeight: Int,
        val textWidth: Int
    )

    companion object {
        const val TAG: String = "BaseThingsAdapter"

        private var white_86p: Int = 0
        private var white_76p: Int = 0
        private var white_66p: Int = 0
        private var white_54p: Int = 0
        private var black_86p: Int = 0
        private var black_76p: Int = 0
        private var black_66p: Int = 0
        private var black_54p: Int = 0

        private const val UNSELECTED_DARK_CONTENT_ALPHA = 0.32f
        private const val UNSELECTED_LIGHT_CONTENT_ALPHA = 0.64f
        // Doing 蒙层未选中时只做轻微淡化：仍需盖住下层内容，故比普通内容淡化弱得多。
        private const val UNSELECTED_DOING_COVER_ALPHA = 0.8f
        private const val NORMAL_TEXT_MAX_LINES = 9
        private const val FULL_SPAN_TEXT_MAX_LINES = 14
        private const val FULL_SPAN_CHECKLIST_MAX_ITEM_COUNT = 12
        private const val SHORT_TEXT_MAX_LENGTH = 60
        private const val PRIVATE_THING_ICON_NORMAL_DP = 48
        private const val THING_CARD_DEFAULT_PADDING_BOTTOM_DP = 16
        private const val THING_CARD_MEDIA_COUNT_PADDING_BOTTOM_DP = 44
        private const val THING_CARD_COUNT_ICON_NORMAL_WIDTH_DP = 14
        private const val THING_CARD_COUNT_ICON_NORMAL_HEIGHT_DP = 14
        private const val THING_CARD_COUNT_ICON_LARGE_WIDTH_DP = 16
        private const val THING_CARD_COUNT_ICON_LARGE_HEIGHT_DP = 16
        private const val THING_CARD_COUNT_ICON_LARGE_TOP_MARGIN_DP = 1
        private const val THING_CARD_MEDIA_COUNT_ICON_NORMAL_WIDTH_DP = 16
        private const val THING_CARD_MEDIA_COUNT_ICON_LARGE_WIDTH_DP = 18
        private const val THING_CARD_MEDIA_COUNT_TEXT_NORMAL_MARGIN_START_DP = 6
        private const val THING_CARD_MEDIA_COUNT_TEXT_LARGE_MARGIN_START_DP = 10
        private const val THING_CARD_MEDIA_COUNT_ICON_SHIFT_DP = 1
        private const val FULL_SPAN_IMAGE_MAX_SCREEN_HEIGHT_RATIO = 0.36f
        private const val SIDE_IMAGE_PROJECTION_MAX_ITERATIONS = 6
        private const val SIDE_IMAGE_PROJECTION_TOLERANCE_PX = 1
        private const val DEFAULT_MEMORY_CLASS_MB = 64
        private const val BYTES_PER_MEGABYTE = 1024 * 1024
        private const val BYTES_PER_ARGB_8888_PIXEL = 4
        private const val THING_CARD_MEDIA_BITMAP_CACHE_MIN_BYTES = 8 * BYTES_PER_MEGABYTE
        private const val THING_CARD_MEDIA_BITMAP_CACHE_MAX_BYTES = 24 * BYTES_PER_MEGABYTE
        private const val DEBUG_THING_FOLDER_VIDEO_CROP_PREFIX = "[DEBUG-tf-video-crop]"
        private const val DEBUG_THING_CARD_TEXT_PREVIEW_LENGTH = 80
        private const val MOVING_SCALE_RECOVERY_CHECK_DELAY = 112L
        private const val MOVING_SCALE_RECOVERY_DURATION = 96L
        private const val MOVING_SCALE_RECOVERY_EPSILON = 0.001f
        private const val CARD_SCALE_RECOVERY_DEBUG = false
        private const val CARD_SCALE_RECOVERY_LOG_NAME = "thing_card_scale_recovery.log"
        private const val CARD_SCALE_RECOVERY_DEBUG_PREFIX = "[DEBUG-card-scale-recovery]"

        @JvmStatic
        fun logCardScaleRecoveryDebug(message: String) {
            if (!CARD_SCALE_RECOVERY_DEBUG) return
            DebugFileLogger.log(
                CARD_SCALE_RECOVERY_LOG_NAME,
                message,
                CARD_SCALE_RECOVERY_DEBUG_PREFIX,
                startSession = true
            )
        }

        init {
            val context: Context = App.getApp()!!
            white_86p = ContextCompat.getColor(context, R.color.white_86p)
            white_76p = ContextCompat.getColor(context, R.color.white_76p)
            white_66p = ContextCompat.getColor(context, R.color.white_66p)
            white_54p = ContextCompat.getColor(context, R.color.white_54p)
            black_86p = ContextCompat.getColor(context, R.color.black_86p)
            black_76p = ContextCompat.getColor(context, R.color.black_76p)
            black_66p = ContextCompat.getColor(context, R.color.black_66p)
            black_54p = ContextCompat.getColor(context, R.color.black_54p)
        }
    }
}
