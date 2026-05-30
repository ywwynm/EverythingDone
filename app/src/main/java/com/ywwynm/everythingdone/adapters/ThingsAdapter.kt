@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.adapters

import android.animation.ObjectAnimator
import android.os.Handler
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.FrequentSettings
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.appwidgets.AppWidgetHelper
import com.ywwynm.everythingdone.helpers.CheckListHelper
import com.ywwynm.everythingdone.managers.ModeManager
import com.ywwynm.everythingdone.managers.ThingManager
import com.ywwynm.everythingdone.model.Thing
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

    override fun shouldDimUnselectedContent(currentMode: Int): Boolean {
        return currentMode == ModeManager.SELECTING || currentMode == ModeManager.MOVING
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

    override fun onBindViewHolder(holder: BaseThingViewHolder, position: Int) {
        val thing = getThings()!![position]!!
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
        return getThings()!![position]!!.id == mArmedNewItemId
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

    override fun isFullSpanHomeCard(thing: Thing): Boolean {
        return thing.type != Thing.HEADER
                && thing.type < Thing.NOTIFICATION_UNDERWAY
                && thing.homeCardSpanMode == Thing.HOME_CARD_SPAN_FULL
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
        lp.isFullSpan = header || isFullSpanHomeCard(thing)
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
    }
}
