@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.activities

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.util.Pair
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.FrequentSettings
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.adapters.BaseThingsAdapter
import com.ywwynm.everythingdone.database.HabitDAO
import com.ywwynm.everythingdone.managers.ModeManager
import com.ywwynm.everythingdone.model.HabitReminder
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.receivers.HabitNotificationActionReceiver
import com.ywwynm.everythingdone.receivers.ReminderNotificationActionReceiver
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.DeviceUtil
import com.ywwynm.everythingdone.utils.DisplayUtil

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

import java.util.ArrayList
import java.util.Collections
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible

/**
 * Created by qiizhang on 2016/11/10.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * An Activity to provide more noticeable notification for Reminders/Habits
 */
open class NoticeableNotificationActivity : EverythingDoneBaseActivity() {

    private var mDialogWidth: Int = 0

    private var mIsHabit: Boolean = false

    private var mThing: Thing? = null
    private var mPosition: Int = 0

    private var mHrId: Long = 0
    private var mHrTime: Long = 0

    private var mActionsTexts: MutableList<Int>? = null
    private var mActionsIcons: MutableList<Int>? = null
    private var mActions: MutableList<View.OnClickListener>? = null

    private var mIvTitle: ImageView? = null
    private var mTvTitle: TextView? = null

    private var mRvThing: RecyclerView? = null

    private var mFlActions: Array<FrameLayout?>? = null
    private var mIvActions: Array<ImageView?>? = null

    private var mFlCancelAsBt: FrameLayout? = null

    private val mReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (BROADCAST_ACTION_JUST_FINISH == intent.action) {
                val thingId = intent.getLongExtra(Def.Communication.KEY_ID, -1L)
                if (thingId == mThing!!.id) {
                    finish()
                }
            }
        }
    }

    override fun init() {
        val intentFilter = IntentFilter(BROADCAST_ACTION_JUST_FINISH)
        ContextCompat.registerReceiver(
            this, mReceiver, intentFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        initMembers()
        if (mThing != null) {
            findViews()
            initUI()
            setActionbar()
            setEvents()
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(mReceiver)
        val nmc = NotificationManagerCompat.from(this)
        if (mIsHabit) {
            nmc.cancel(mHrId.toInt())
        } else {
            nmc.cancel(mThing!!.id.toInt())
        }
    }

    override fun getLayoutResource(): Int = R.layout.activity_noticeable_notification

    override fun initMembers() {
        mDialogWidth = (DisplayUtil.getScreenDensity(this) * 280).toInt()

        val intent: Intent = getIntent()
        mIsHabit = intent.getBooleanExtra(KEY_IS_HABIT, false)
        val thingId: Long
        if (mIsHabit) {
            mHrId = intent.getLongExtra(Def.Communication.KEY_ID, -1L)
            val habitReminder: HabitReminder =
                HabitDAO.getInstance(this)!!.getHabitReminderById(mHrId) ?: return
            thingId = habitReminder.habitId
            mHrTime = intent.getLongExtra(Def.Communication.KEY_TIME, -1L)
        } else {
            thingId = intent.getLongExtra(Def.Communication.KEY_ID, -1L)
        }

        val position = intent.getIntExtra(Def.Communication.KEY_POSITION, -1)
        val pair: Pair<Thing, Int> = App.getThingAndPosition(this, thingId, position)
        mThing = pair.first
        mPosition = pair.second ?: -1

        if (mThing != null) {
            initMemberActions()
        }
    }

    private fun initMemberActions() {
        mActionsTexts = ArrayList()
        mActionsIcons = ArrayList()
        mActions = ArrayList()
        @Thing.Type val thingType = mThing!!.type
        if (Thing.isReminderType(thingType)) {
            initMemberActionsReminder()
        } else if (thingType == Thing.HABIT) {
            initMemberActionsHabit()
        }
    }

    private fun initMemberActionsReminder() {
        mActionsTexts!!.add(R.string.act_finish)
        mActionsIcons!!.add(R.drawable.act_finish)
        mActions!!.add(View.OnClickListener {
            sendBroadcastForReminderAndLeave(Def.Communication.NOTIFICATION_ACTION_FINISH)
        })

        if (mThing!!.type == Thing.REMINDER) {
            mActionsTexts!!.add(R.string.act_start_doing)
            mActionsIcons!!.add(R.drawable.act_start_doing)
            mActions!!.add(View.OnClickListener {
                sendBroadcastForReminderAndLeave(Def.Communication.NOTIFICATION_ACTION_START_DOING)
            })

            mActionsTexts!!.add(R.string.act_delay)
            mActionsIcons!!.add(R.drawable.act_delay)
            mActions!!.add(View.OnClickListener {
                sendBroadcastForReminderAndLeave(Def.Communication.NOTIFICATION_ACTION_DELAY)
            })
        }
    }

    private fun initMemberActionsHabit() {
        mActionsTexts!!.add(R.string.act_finish_this_time_habit)
        mActionsIcons!!.add(R.drawable.act_finish)
        mActions!!.add(View.OnClickListener {
            val intent = Intent(
                this@NoticeableNotificationActivity,
                HabitNotificationActionReceiver::class.java
            )
            intent.setAction(Def.Communication.NOTIFICATION_ACTION_FINISH)
            intent.putExtra(Def.Communication.KEY_ID, mHrId)
            intent.putExtra(Def.Communication.KEY_POSITION, mPosition)
            intent.putExtra(Def.Communication.KEY_TIME, mHrTime)
            sendBroadcast(intent)
            finish()
        })

        mActionsTexts!!.add(R.string.act_start_doing)
        mActionsIcons!!.add(R.drawable.act_start_doing)
        mActions!!.add(View.OnClickListener {
            val intent = Intent(
                this@NoticeableNotificationActivity,
                HabitNotificationActionReceiver::class.java
            )
            intent.setAction(Def.Communication.NOTIFICATION_ACTION_START_DOING)
            intent.putExtra(Def.Communication.KEY_ID, mHrId)
            intent.putExtra(Def.Communication.KEY_POSITION, mPosition)
            intent.putExtra(Def.Communication.KEY_TIME, mHrTime)
            sendBroadcast(intent)
            finish()
        })
    }

    private fun sendBroadcastForReminderAndLeave(action: String) {
        val intent = Intent(this, ReminderNotificationActionReceiver::class.java)
        intent.setAction(action)
        intent.putExtra(Def.Communication.KEY_ID, mThing!!.id)
        intent.putExtra(Def.Communication.KEY_POSITION, mPosition)
        sendBroadcast(intent)
        finish()
    }

    override fun findViews() {
        mTvTitle = f(R.id.tv_noticeable_notification_title)
        mIvTitle = f(R.id.iv_noticeable_notification_title)

        mRvThing = f(R.id.rv_thing_noticeable_notification)

        mFlActions = arrayOfNulls(3)
        mFlActions!![0] = f(R.id.fl_1_noticeable_notification_as_bt)
        mFlActions!![1] = f(R.id.fl_2_noticeable_notification_as_bt)
        mFlActions!![2] = f(R.id.fl_3_noticeable_notification_as_bt)

        mIvActions = arrayOfNulls(3)
        mIvActions!![0] = f(R.id.iv_1_noticeable_notification_as_bt)
        mIvActions!![1] = f(R.id.iv_2_noticeable_notification_as_bt)
        mIvActions!![2] = f(R.id.iv_3_noticeable_notification_as_bt)

        mFlCancelAsBt = f(R.id.fl_noticeable_notification_cancel_as_bt)
    }

    override fun initUI() {
        initTitleUI()
        initRvThing()
        initActionsUI()
    }

    @SuppressLint("SetTextI18n")
    private fun initTitleUI() {
        @Thing.Type val thingType = mThing!!.type
        val iconRes = Thing.getTypeIconWhiteLarge(thingType)
        val d1: Drawable = ContextCompat.getDrawable(this, iconRes)!!
        val d2: Drawable = BackgroundUtil.tintDrawable(
            resources, d1, mThing!!.getBackground()
        )!!
        mIvTitle!!.setImageDrawable(d2)

        val typeStr: String = Thing.getTypeStr(thingType, this)!!
        mIvTitle!!.contentDescription = typeStr

        val timeStr: String = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        val title = "$typeStr • $timeStr"
        val ssb = SpannableStringBuilder(title)
        val colorSpan1 = ForegroundColorSpan(mThing!!.getColor())
        val colorSpan2 = ForegroundColorSpan("#66000000".toColorInt())
        val index = title.indexOf('•')
        ssb.setSpan(colorSpan1, 0, index - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        ssb.setSpan(colorSpan2, index - 1, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        mTvTitle!!.text = ssb
    }

    private fun initRvThing() {
        val singleThing: List<Thing?> = Collections.singletonList(Thing(mThing!!))
        val adapter: BaseThingsAdapter = object : BaseThingsAdapter(this@NoticeableNotificationActivity) {
            override fun getCurrentMode(): Int = ModeManager.NORMAL

            override fun getThings(): List<Thing?> = singleThing

            override fun onBindViewHolder(holder: BaseThingViewHolder, position: Int) {
                super.onBindViewHolder(holder, position)

                val bg: ThingBackground = mThing!!.getBackground()!!
                holder.cv!!.radius = 0f
                holder.cv.cardElevation = 0f
                if (bg.mode === ThingBackground.Mode.PURE) {
                    holder.cv.setCardBackgroundColor(bg.color)
                } else {
                    holder.cv.setCardBackgroundColor(Color.TRANSPARENT)
                    holder.cv.background =
                        BackgroundUtil.makeTranslucentGradient(bg, 255)
                }

                holder.tvTitle!!.maxLines = Int.MAX_VALUE
                holder.tvContent!!.maxLines = Int.MAX_VALUE
                holder.rlReminder!!.visibility = View.GONE
                holder.rlHabit!!.visibility = View.GONE
                holder.vReminderSeparator!!.visibility = View.GONE
                holder.vHabitSeparator1!!.visibility = View.GONE
                holder.flDoing!!.visibility = View.GONE
                holder.ivStickyOngoing!!.visibility = View.GONE

                if (holder.ivPrivateThing!!.isVisible) {
                    holder.ivPrivateThing.visibility = View.GONE
                    holder.tvContent.visibility = View.VISIBLE
                    holder.tvContent.setText(R.string.notification_private_thing_content)
                    holder.tvContent.textSize = 20f
                    val light = BackgroundUtil.isLight(mThing!!.getColor())
                    holder.tvContent.setTextColor(
                        ContextCompat.getColor(
                            applicationContext,
                            if (light) R.color.black_76p else R.color.white_76p
                        )
                    )
                    val p = (mDensity * 16).toInt()
                    holder.tvContent.setPadding(p, p, p, 0)
                }

                holder.cv.setOnClickListener {
                    val intent = DetailActivity.getOpenIntentForUpdate(
                        this@NoticeableNotificationActivity,
                        TAG,
                        mThing!!.id, mPosition
                    )
                    startActivity(intent)
                    finish()
                }
            }
        }
        adapter.setCardWidth(mDialogWidth)
        adapter.setChecklistMaxItemCount(-1)
        mRvThing!!.layoutManager = LinearLayoutManager(this)
        mRvThing!!.adapter = adapter
    }

    private fun initActionsUI() {
        val actionColor = ContextCompat.getColor(this, R.color.black_54p)
        val size = mActions!!.size
        for (i in 0 until size) {
            mFlActions!![i]!!.visibility = View.VISIBLE
            var drawable: Drawable = ContextCompat.getDrawable(this, mActionsIcons!![i])!!
            drawable = drawable.mutate()
            drawable.setColorFilter(actionColor, PorterDuff.Mode.SRC_ATOP)
            mIvActions!![i]!!.setImageDrawable(drawable)
            mFlActions!![i]!!.setOnClickListener(mActions!![i])
            mIvActions!![i]!!.contentDescription = getString(mActionsTexts!![i])
        }

        if (FrequentSettings.getBoolean(Def.Meta.KEY_CLOSE_NOTIFICATION_LATER)) {
            for (i in 0 until size) {
                mFlActions!![i]!!.alpha = 0f
                mFlActions!![i]!!.visibility = View.GONE
            }
            mFlCancelAsBt!!.alpha = 0f
            mFlCancelAsBt!!.visibility = View.GONE

            if (DeviceUtil.isScreenOn(this)) {
                animateActionsVisible()
            } else {
                mShouldShowActionsInOnResume = true
            }
        }
    }

    private fun animateActionsVisible() {
        mTvTitle!!.postDelayed({
            for (i in 0 until mActions!!.size) {
                mFlActions!![i]!!.visibility = View.VISIBLE
                mFlActions!![i]!!.animate().alpha(1f).setDuration(360).start()
            }
            mFlCancelAsBt!!.visibility = View.VISIBLE
            mFlCancelAsBt!!.animate().alpha(1f).setDuration(360).start()
        }, 2000)
    }

    private var mShouldShowActionsInOnResume: Boolean = false

    override fun onResume() {
        super.onResume()

        if (DeviceUtil.isScreenOn(this) && mShouldShowActionsInOnResume) {
            animateActionsVisible()
            mShouldShowActionsInOnResume = false
        }
    }

    override fun setActionbar() {}

    override fun setEvents() {
        mRvThing!!.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                var shrunk: Boolean = false
                override fun onGlobalLayout() {
                    val height = mRvThing!!.height
                    if (height > mDialogWidth * 1.2f) {
                        val vlp: ViewGroup.LayoutParams = mRvThing!!.layoutParams
                        vlp.height = (mDialogWidth * 1.2f).toInt()
                        mRvThing!!.requestLayout()
                        mRvThing!!.overScrollMode = View.OVER_SCROLL_ALWAYS
                        shrunk = true
                    } else if (!shrunk) {
                        mRvThing!!.overScrollMode = View.OVER_SCROLL_NEVER
                    }
                }
            })

        mFlCancelAsBt!!.setOnClickListener { finish() }
    }

    companion object {
        const val TAG: String = "NoticeableNotificationActivity"

        const val BROADCAST_ACTION_JUST_FINISH: String = "$TAG.action.just_finish"

        private const val KEY_IS_HABIT: String = "$TAG.key.is_habit"

        @JvmStatic
        fun getOpenIntentForReminder(context: Context?, thingId: Long, position: Int): Intent {
            return Intent(context, NoticeableNotificationActivity::class.java)
                .putExtra(Def.Communication.KEY_ID, thingId)
                .putExtra(Def.Communication.KEY_POSITION, position)
        }

        @JvmStatic
        fun getOpenIntentForHabit(context: Context?, hrId: Long, position: Int, hrTime: Long): Intent {
            return Intent(context, NoticeableNotificationActivity::class.java)
                .putExtra(Def.Communication.KEY_ID, hrId)
                .putExtra(Def.Communication.KEY_POSITION, position)
                .putExtra(Def.Communication.KEY_TIME, hrTime)
                .putExtra(KEY_IS_HABIT, true)
        }
    }
}
