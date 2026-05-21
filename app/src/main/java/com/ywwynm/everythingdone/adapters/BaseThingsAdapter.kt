@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.adapters

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PorterDuff
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.collection.LongSparseArray
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView

import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import android.graphics.drawable.Drawable
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.FrequentSettings
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.database.HabitDAO
import com.ywwynm.everythingdone.database.ReminderDAO
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.helpers.CheckListHelper
import com.ywwynm.everythingdone.managers.ModeManager
import com.ywwynm.everythingdone.model.Habit
import com.ywwynm.everythingdone.model.Reminder
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.DateTimeUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.views.HabitRecordPresenter
import com.ywwynm.everythingdone.views.InterceptTouchCardView

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

    private var mReminderDAO: ReminderDAO? = ReminderDAO.getInstance(context)
    private var mHabitDAO: HabitDAO? = HabitDAO.getInstance(context)

    private var mImageRequestManager: RequestManager? = Glide.with(context!!)

    private var mCardWidth: Int = DisplayUtil.getThingCardWidth(context)
    private var mShouldShowPrivateContent: Boolean = false
    private var mChecklistMaxItemCount: Int = 8

    protected abstract fun getCurrentMode(): Int
    protected abstract fun getThings(): List<Thing?>?

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
    }

    open fun setShouldShowPrivateContent(shouldShowPrivateContent: Boolean) {
        mShouldShowPrivateContent = shouldShowPrivateContent
    }

    open fun setChecklistMaxItemCount(checklistMaxItemCount: Int) {
        mChecklistMaxItemCount = checklistMaxItemCount
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseThingViewHolder {
        return BaseThingViewHolder(mInflater!!.inflate(R.layout.card_thing, parent, false))
    }

    override fun onBindViewHolder(holder: BaseThingViewHolder, position: Int) {
        val thing: Thing = getThings()!![position]!!
        setContentViewAppearance(holder, thing)
        setCardAppearance(holder, thing.getBackground(), thing.isSelected())
    }

    private fun setContentViewAppearance(holder: BaseThingViewHolder, thing: Thing) {
        updateCardForStickyOrOngoingNotification(holder, thing)
        updateCardForTitle(holder, thing)

        if (thing.isPrivate() && !mShouldShowPrivateContent) {
            holder.ivPrivateThing!!.visibility = View.VISIBLE
            androidx.core.widget.ImageViewCompat.setImageTintList(
                holder.ivPrivateThing,
                if (BackgroundUtil.isLight(thing.getColor()))
                    android.content.res.ColorStateList.valueOf(android.graphics.Color.BLACK)
                else null
            )
            holder.flImageAttachment!!.visibility = View.GONE
            holder.tvContent!!.visibility = View.GONE
            holder.rvChecklist!!.visibility = View.GONE
            holder.llAudioAttachment!!.visibility = View.GONE
            holder.rlReminder!!.visibility = View.GONE
            holder.rlHabit!!.visibility = View.GONE
        } else {
            holder.ivPrivateThing!!.visibility = View.GONE

            updateCardForContent(holder, thing)
            updateCardForReminder(holder, thing)
            updateCardForHabit(holder, thing)
            updateCardForAudioAttachment(holder, thing)
            updateCardForImageAttachment(holder, thing)

            updateCardSeparatorsIfNeeded(holder)

            enlargeAudioLayoutIfNeeded(holder)
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
                holder.ivStickyOngoing!!.setImageResource(
                    if (sticky)
                        R.drawable.ic_sticky_not_selected
                    else R.drawable.ic_ongoing_notication_not_selected
                )
            } else {
                holder.ivStickyOngoing!!.setImageResource(
                    if (sticky) R.drawable.ic_sticky else R.drawable.ic_ongoing_notication
                )
            }
            @StringRes val cdRes = if (sticky) R.string.sticky_thing else R.string.ongoing_thing
            holder.ivStickyOngoing!!.contentDescription = mContext!!.getString(cdRes)
            tintCardIcon(holder.ivStickyOngoing, thing.getColor())
        }
    }

    private fun updateCardForTitle(holder: BaseThingViewHolder, thing: Thing) {
        val title: String = thing.getTitleToDisplay()!!
        if (!title.isEmpty()) {
            val p = (mDensity * 16).toInt()
            holder.tvTitle!!.visibility = View.VISIBLE
            holder.tvTitle!!.setPadding(p, p, p, 0)
            holder.tvTitle!!.text = title
            holder.tvTitle!!.setTextColor(textColorPrimary(thing.getColor()))
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
                if (length <= 60) {
                    holder.tvContent!!.textSize = -0.14f * length + 24.14f
                } else {
                    holder.tvContent!!.textSize = 16f
                }

                holder.tvContent!!.setPadding(p, p, p, 0)
                holder.tvContent!!.text = content
                holder.tvContent!!.setTextColor(textColorSecondary(thing.getColor()))
            } else {
                holder.tvContent!!.visibility = View.GONE
                holder.rvChecklist!!.visibility = View.VISIBLE

                val id = thing.id
                val items: MutableList<String?>? = CheckListHelper.toCheckListItems(content, false)
                var adapter: CheckListAdapter? = mCheckListAdapters!!.get(id)
                if (adapter == null) {
                    adapter = CheckListAdapter(mContext,
                        CheckListAdapter.TEXTVIEW, items)
                    mCheckListAdapters!!.put(id, adapter)
                } else {
                    adapter.setItems(items)
                }
                adapter.setThingColor(thing.getColor())
                adapter.setMaxItemCount(mChecklistMaxItemCount)
                onChecklistAdapterInitialized(holder, adapter, thing)
                holder.rvChecklist!!.adapter = adapter
                holder.rvChecklist!!.layoutManager = LinearLayoutManager(mContext)

                val rp = (mDensity * 6).toInt()
                holder.rvChecklist!!.setPaddingRelative(rp, p, p, 0)
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
        holder.rlReminder!!.setPadding(p, p, p, 0)

        val params = holder.ivReminder!!.layoutParams as RelativeLayout.LayoutParams
        if (thingType == Thing.REMINDER) {
            params.setMargins(0, (mDensity * 2).toInt(), 0, 0)
            holder.ivReminder!!.setImageResource(R.drawable.card_reminder)
            holder.ivReminder!!.contentDescription = mContext!!.getString(R.string.reminder)
            holder.tvReminderTime!!.textSize = 12f

            holder.tvReminderTime!!.text =
                DateTimeUtil.getDateTimeStrReminder(mContext, thing, reminder)
        } else {
            params.setMargins(0, (mDensity * 1.6).toInt(), 0, 0)
            holder.ivReminder!!.setImageResource(R.drawable.card_goal)
            holder.ivReminder!!.contentDescription = mContext!!.getString(R.string.goal)
            holder.tvReminderTime!!.textSize = 16f

            holder.tvReminderTime!!.text =
                DateTimeUtil.getDateTimeStrGoal(mContext, thing, reminder)
        }
        holder.tvReminderTime!!.setTextColor(textColorTertiary(thing.getColor()))
        tintCardIcon(holder.ivReminder, thing.getColor())
        tintCardSeparator(holder.vReminderSeparator, thing.getColor())
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
        holder.rlHabit!!.setPadding(p, p, p, 0)

        var summary: String = habit.getSummary(mContext)!!
        if (thing.state == Thing.UNDERWAY && habit.isPaused()) {
            summary += ", " + habit.getStateDescription(mContext)
        }
        holder.tvHabitSummary!!.text = summary
        holder.tvHabitSummary!!.setTextColor(textColorTertiary(thing.getColor()))
        holder.tvHabitNextReminder!!.setTextColor(textColorDisabled(thing.getColor()))
        holder.tvHabitLastFive!!.setTextColor(textColorDisabled(thing.getColor()))
        holder.tvHabitFinishedThisT!!.setTextColor(textColorTertiary(thing.getColor()))
        tintCardIcon(holder.ivHabit, thing.getColor())
        tintCardSeparator(holder.vHabitSeparator1, thing.getColor())
        tintCardSeparator(holder.vHabitSeparator2, thing.getColor())
        holder.habitRecordPresenter!!.setThingColor(thing.getColor())

        if (thing.state == Thing.UNDERWAY && !habit.isPaused()) {
            holder.tvHabitNextReminder!!.visibility = View.VISIBLE
            holder.vHabitSeparator2!!.visibility = View.VISIBLE
            holder.tvHabitLastFive!!.visibility = View.VISIBLE
            holder.llHabitRecord!!.visibility = View.VISIBLE
            holder.tvHabitFinishedThisT!!.visibility = View.VISIBLE

            val next = mContext!!.getString(R.string.habit_next_reminder)
            holder.tvHabitNextReminder!!.text =
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
            holder.habitRecordPresenter!!.setRecord(lastFive.toString())

            holder.tvHabitFinishedThisT!!.text = habit.getFinishedTimesThisTStr(mContext)
        } else {
            holder.tvHabitNextReminder!!.visibility = View.GONE
            holder.vHabitSeparator2!!.visibility = View.GONE
            holder.tvHabitLastFive!!.visibility = View.GONE
            holder.llHabitRecord!!.visibility = View.GONE
            holder.tvHabitFinishedThisT!!.visibility = View.GONE
        }
    }

    private fun updateCardForImageAttachment(holder: BaseThingViewHolder, thing: Thing) {
        val attachment: String? = thing.attachment
        val firstImageTypePathName: String? = AttachmentHelper.getFirstImageTypePathName(attachment)
        if (firstImageTypePathName != null) {
            holder.flImageAttachment!!.visibility = View.VISIBLE

            val imageW = mCardWidth
            val imageH = imageW * 3 / 4

            val paramsLayout = holder.flImageAttachment!!.layoutParams as LinearLayout.LayoutParams
            paramsLayout.width = imageW

            val paramsImage = holder.ivImageAttachment!!.layoutParams as FrameLayout.LayoutParams
            paramsImage.height = imageH

            val paramsCover = holder.vImageCover!!.layoutParams as FrameLayout.LayoutParams
            paramsCover.height = imageH

            val pathName = firstImageTypePathName.substring(1, firstImageTypePathName.length)
            mImageRequestManager!!
                .load(pathName)
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?, model: Any?, target: Target<Drawable>,
                        isFirstResource: Boolean
                    ): Boolean = false

                    override fun onResourceReady(
                        resource: Drawable, model: Any, target: Target<Drawable>?,
                        dataSource: DataSource, isFirstResource: Boolean
                    ): Boolean {
                        holder.pbLoading!!.visibility = View.GONE
                        return false
                    }
                })
                .centerCrop()
                .into(holder.ivImageAttachment!!)

            if (holder.tvTitle!!.visibility == View.GONE
                && holder.tvContent!!.visibility == View.GONE
                && holder.rvChecklist!!.visibility == View.GONE
                && holder.llAudioAttachment!!.visibility == View.GONE
                && holder.rlReminder!!.visibility == View.GONE
                && holder.rlHabit!!.visibility == View.GONE
            ) {
                holder.vPaddingBottom!!.visibility = View.GONE
            } else {
                holder.vPaddingBottom!!.visibility = View.VISIBLE
            }

            holder.tvImageCount!!.text =
                AttachmentHelper.getImageAttachmentCountStr(attachment, mContext)
            holder.tvImageCount!!.setTextColor(textColorSecondary(thing.getColor()))

            if (getCurrentMode() == ModeManager.NORMAL) {
                holder.vImageCover!!.visibility = View.GONE
            } else {
                holder.vImageCover!!.visibility =
                    if (thing.isSelected()) View.GONE else View.VISIBLE
            }
        } else {
            holder.vPaddingBottom!!.visibility = View.VISIBLE
            holder.flImageAttachment!!.visibility = View.GONE
        }
    }

    private fun updateCardForAudioAttachment(holder: BaseThingViewHolder, thing: Thing) {
        val attachment: String? = thing.attachment
        val str: String? = AttachmentHelper.getAudioAttachmentCountStr(attachment, mContext)
        if (str == null) {
            holder.llAudioAttachment!!.visibility = View.GONE
        } else {
            holder.llAudioAttachment!!.visibility = View.VISIBLE
            val p = (mDensity * 16).toInt()
            holder.llAudioAttachment!!.setPadding(p, p / 4 * 3, p, 0)

            holder.tvAudioCount!!.text = str

            val dark = BackgroundUtil.isLight(thing.getColor())
            holder.ivAudioCount!!.setImageResource(
                if (dark)
                    R.drawable.card_audio_attachment_black
                else R.drawable.card_audio_attachment
            )
            holder.tvAudioCount!!.setTextColor(textColorTertiary(thing.getColor()))
        }
    }

    private fun updateCardSeparatorsIfNeeded(holder: BaseThingViewHolder) {
        if (holder.flImageAttachment!!.visibility == View.VISIBLE
            && holder.tvTitle!!.visibility == View.GONE
            && holder.tvContent!!.visibility == View.GONE
            && holder.rvChecklist!!.visibility == View.GONE
            && holder.llAudioAttachment!!.visibility == View.GONE
        ) {
            if (holder.rlReminder!!.visibility == View.VISIBLE) {
                holder.vReminderSeparator!!.visibility = View.GONE
            } else if (holder.rlHabit!!.visibility == View.VISIBLE) {
                holder.vHabitSeparator1!!.visibility = View.GONE
            }
        } else {
            if (holder.rlReminder!!.visibility == View.VISIBLE) {
                holder.vReminderSeparator!!.visibility = View.VISIBLE
            } else if (holder.rlHabit!!.visibility == View.VISIBLE) {
                holder.vHabitSeparator1!!.visibility = View.VISIBLE
            }
        }
    }

    private fun enlargeAudioLayoutIfNeeded(holder: BaseThingViewHolder) {
        if (holder.llAudioAttachment!!.visibility != View.VISIBLE) {
            return
        }

        val llp1 = holder.ivAudioCount!!.layoutParams as LinearLayout.LayoutParams
        val llp2 = holder.tvAudioCount!!.layoutParams as LinearLayout.LayoutParams
        val dp1  = (mDensity * 1).toInt()
        val dp8  = (mDensity * 8).toInt()
        val dp12 = (mDensity * 12).toInt()
        val dp16 = (mDensity * 16).toInt()
        if (holder.flImageAttachment!!.visibility == View.GONE
            && holder.tvTitle!!.visibility == View.GONE
            && holder.tvContent!!.visibility == View.GONE
            && holder.rvChecklist!!.visibility == View.GONE
        ) {
            llp1.height = (mDensity * 16).toInt()
            llp1.topMargin = dp1
            holder.tvAudioCount!!.textSize = 18f

            llp2.setMargins(dp12, llp2.topMargin, llp2.rightMargin, llp2.bottomMargin)
            llp2.marginStart = dp12

            holder.llAudioAttachment!!.setPadding(dp16, dp16, dp16, 0)
        } else {
            llp1.height = ViewGroup.LayoutParams.WRAP_CONTENT
            llp1.topMargin = 0
            holder.tvAudioCount!!.textSize = 11f

            llp2.setMargins(dp8, llp2.topMargin, llp2.rightMargin, llp2.bottomMargin)
            llp2.marginStart = dp8

            holder.llAudioAttachment!!.setPadding(dp16, dp16 / 4 * 3, dp16, 0)
        }
        holder.ivAudioCount!!.requestLayout()
    }

    private fun updateCardForDoing(holder: BaseThingViewHolder, thing: Thing) {
        if (App.getDoingThingId() == thing.id) {
            holder.flDoing!!.visibility = View.VISIBLE
            holder.cv!!.post(Runnable {
                val lp = holder.flDoing!!.layoutParams as FrameLayout.LayoutParams
                lp.width  = holder.cv!!.width
                lp.height = holder.cv!!.height
                Log.i(TAG, "setting doing cover for thing card, " +
                        "width[" + lp.width + ", height[" + lp.height + "]")
                holder.flDoing!!.requestLayout()
            })
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
        if (currentMode == ModeManager.MOVING) {
            if (selected) {
                ObjectAnimator.ofFloat(cv, "scaleX", 1.11f).setDuration(96).start()
                ObjectAnimator.ofFloat(cv, "scaleY", 1.11f).setDuration(96).start()
                ObjectAnimator.ofFloat(cv, "CardElevation", 12 * mDensity)
                    .setDuration(96).start()
                BackgroundUtil.applyCardBackground(cv, background)
            } else {
                cv.scaleX = 1.0f
                cv.scaleY = 1.0f
                cv.cardElevation = 2 * mDensity
                BackgroundUtil.applyCardBackground(cv, lightVariant(background))
            }
        } else if (currentMode == ModeManager.SELECTING) {
            if (selected) {
                BackgroundUtil.applyCardBackground(cv, background)
            } else {
                BackgroundUtil.applyCardBackground(cv, lightVariant(background))
            }
        } else {
            BackgroundUtil.applyCardBackground(cv, background)
        }

        val cardIsLight = background != null
                && BackgroundUtil.isLight(background.representativeColor())
        holder.cv!!.foreground = ContextCompat.getDrawable(
            mContext!!,
            if (cardIsLight)
                R.drawable.selectable_item_background
            else R.drawable.selectable_item_background_light
        )
    }

    /** Produce a washed-out variant of `bg` for unselected cards. */
    private fun lightVariant(bg: ThingBackground?): ThingBackground? {
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
        return getThings()!![position]!!.type
    }

    override fun getItemCount(): Int {
        return getThings()!!.size
    }

    open class BaseThingViewHolder(item: View?) : BaseViewHolder(item) {

        @JvmField val cv: InterceptTouchCardView? = f(R.id.cv_thing)
        @JvmField val llContent: LinearLayout? = f(R.id.ll_thing_content)
        @JvmField val vPaddingBottom: View? = f(R.id.view_thing_padding_bottom)

        @JvmField val ivStickyOngoing: ImageView?   = f(R.id.iv_thing_sticky_ongoing)
        @JvmField val flDoing: FrameLayout? = f(R.id.fl_thing_doing_cover)

        @JvmField val flImageAttachment: FrameLayout? = f(R.id.fl_thing_image)
        @JvmField val ivImageAttachment: ImageView?   = f(R.id.iv_thing_image)
        @JvmField val tvImageCount: TextView?    = f(R.id.tv_thing_image_attachment_count)
        @JvmField val pbLoading: ProgressBar? = f(R.id.pb_thing_image_attachment)
        @JvmField val vImageCover: View?        = f(R.id.view_thing_image_cover)

        @JvmField val tvTitle: TextView?  = f(R.id.tv_thing_title)
        @JvmField val ivPrivateThing: ImageView? = f(R.id.iv_private_thing)

        @JvmField val tvContent: TextView?     = f(R.id.tv_thing_content)
        @JvmField val rvChecklist: RecyclerView? = f(R.id.rv_check_list)

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
        @JvmField val habitRecordPresenter: HabitRecordPresenter? = HabitRecordPresenter(arrayOf(
            f(R.id.iv_thing_habit_record_1),
            f(R.id.iv_thing_habit_record_2),
            f(R.id.iv_thing_habit_record_3),
            f(R.id.iv_thing_habit_record_4),
            f(R.id.iv_thing_habit_record_5)
        ))
        @JvmField val tvHabitFinishedThisT: TextView? = f(R.id.tv_thing_habit_finished_this_t)

        init {
            val pbColor = ContextCompat.getColor(item!!.context, R.color.app_accent)
            pbLoading!!.indeterminateDrawable.setColorFilter(pbColor, PorterDuff.Mode.SRC_IN)
        }
    }

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
