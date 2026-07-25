@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.activities

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import androidx.core.content.ContextCompat
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.view.WindowManager
import androidx.annotation.StringRes
import androidx.core.util.Pair
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.ItemTouchHelper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

import com.github.adnansm.timelytextview.TimelyClockView
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.adapters.BaseThingsAdapter
import com.ywwynm.everythingdone.adapters.CheckListAdapter
import com.ywwynm.everythingdone.database.ThingDAO
import com.ywwynm.everythingdone.database.ThingFolderDAO
import com.ywwynm.everythingdone.fragments.AlertDialogFragment
import com.ywwynm.everythingdone.helpers.CheckListHelper
import com.ywwynm.everythingdone.helpers.RemoteActionHelper
import com.ywwynm.everythingdone.managers.ModeManager
import com.ywwynm.everythingdone.model.DoingRecord
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.services.DoingService
import com.ywwynm.everythingdone.utils.DeviceUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.views.FloatingActionButton

import java.util.Collections

import jp.wasabeef.blurry.Blurry
import androidx.core.graphics.toColorInt
import kotlin.math.max
import kotlin.math.min

/**
 * Created by qiizhang on 2016/10/31.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * An Activity showing the thing that are currently doing
 */
open class DoingActivity : EverythingDoneBaseActivity() {

    private var mApp: App? = null

    private var mCardWidth: Int = 0

    private var mDoingBinder: DoingService.DoingBinder? = null

    private val mServiceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, iBinder: IBinder) {
            mDoingBinder = iBinder as DoingService.DoingBinder
            initAfterBindService()
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
        }
    }

    private val mReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (BROADCAST_ACTION_JUST_FINISH == intent.action) {
                finish()
            }
        }
    }

    private var mThing: Thing? = null

    private var mIvBg: ImageView? = null

    private var mClockView: TimelyClockView? = null

    private var mRecyclerView: RecyclerView? = null
    private var mTvSwipeToFinish: TextView? = null

    private var mLlBottom: LinearLayout? = null
    private var mFlAdd5Min: FrameLayout? = null
    private var mFlStrictMode: FrameLayout? = null
    private var mFabStrictMode: FloatingActionButton? = null
    private var mFlCancel: FrameLayout? = null

    private var mInfinityHandler: Handler? = null

    private var mServiceUnbind: Boolean = false

    override fun getLayoutResource(): Int = R.layout.activity_doing

    override fun onStart() {
        super.onStart()
        if (mDoingBinder != null) {
            mDoingBinder!!.setStartPlayTime(-1L)
        }
    }

    override fun onStop() {
        super.onStop()
        Log.i(TAG, "onStop")
        if (!DeviceUtil.isScreenOn(this)) {
            Log.i(TAG, "onStop called because of closing screen")
        } else if (mDoingBinder != null && mDoingBinder!!.isInStrictMode()) {
            mDoingBinder!!.setPlayedTimes(mDoingBinder!!.getPlayedTimes() + 1)
            mDoingBinder!!.setStartPlayTime(System.currentTimeMillis())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(mReceiver)
        if (mInfinityHandler != null) {
            mInfinityHandler!!.removeMessages(96)
            mInfinityHandler = null
        }
        if (!mServiceUnbind) {
            mDoingBinder!!.setCountdownListener(null)
            unbindService(mServiceConnection)
        }
    }

    override fun beforeSetContentView() {
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        window.setFormat(PixelFormat.TRANSLUCENT)
        window.setBackgroundDrawable(null)
    }

    /**
     * AppCompat wraps the user's content view inside a "sub-decor" view that has
     * its own opaque background. Walk up from android.R.id.content to the decor
     * view and force every level to transparent so the wallpaper is visible.
     */
    private fun clearOpaqueAncestorBackgrounds() {
        var v: View? = findViewById(android.R.id.content)
        val decor: View = window.decorView
        while (v != null) {
            v.background = null
            if (v === decor || v.parent !is View) break
            v = v.parent as View
        }
    }

    override fun beforeInit() {
        val intent = Intent(this, DoingService::class.java)
        bindService(intent, mServiceConnection, BIND_AUTO_CREATE)

        val filter = IntentFilter(BROADCAST_ACTION_JUST_FINISH)
        ContextCompat.registerReceiver(
            this, mReceiver, filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun initMembers() {
        mApp = App.getApp()
    }

    private fun getDoingThingCardWidth(thing: Thing): Int {
        val configuredWidth = resources.getDimensionPixelSize(
            if (isFullSpanThingCard(thing)) {
                R.dimen.thing_card_single_surface_full_span_width
            } else {
                R.dimen.thing_card_single_surface_normal_width
            }
        )
        val horizontalMargin = resources.getDimensionPixelSize(
            R.dimen.thing_card_single_surface_horizontal_margin
        )
        val maxWidth = max(1, DisplayUtil.getScreenSize(mApp).x - horizontalMargin * 2)
        return min(maxWidth, configuredWidth)
    }

    private fun isFullSpanThingCard(thing: Thing): Boolean {
        return thing.type != Thing.HEADER
                && thing.type < Thing.NOTIFICATION_UNDERWAY
                && thing.thingCardAppearance.spanMode == Thing.THING_CARD_SPAN_FULL
    }

    override fun findViews() {
        mIvBg = f(R.id.iv_bg_doing)

        mClockView = f(R.id.clock_time_doing)

        mRecyclerView = f(R.id.rv_thing_doing)
        mTvSwipeToFinish = f(R.id.tv_swipe_to_finish_doing)

        mLlBottom      = f(R.id.ll_bottom_buttons_doing)
        mFlAdd5Min     = f(R.id.fl_add_5_min)
        mFlStrictMode  = f(R.id.fl_strict_mode)
        mFabStrictMode = f(R.id.fab_strict_mode)
        mFlCancel      = f(R.id.fl_cancel_doing)

        val sp = getSharedPreferences(com.ywwynm.everythingdone.Def.Meta.PREFERENCES_NAME, MODE_PRIVATE)
        val digitStyle = sp.getString(com.ywwynm.everythingdone.Def.Meta.KEY_DOING_DIGIT_STYLE, "poppins") ?: "poppins"
        val digitFill = (sp.getString(com.ywwynm.everythingdone.Def.Meta.KEY_DOING_DIGIT_RENDER, "fill") ?: "fill") == "fill"
        mClockView!!.setStyleName(digitStyle)
        mClockView!!.setRenderMode(digitFill)
        mClockView!!.setClockMode(TimelyClockView.MODE_AUTO_HIDE_HOUR)
        mClockView!!.setHostDark(true)
    }

    override fun initUI() {
        DisplayUtil.expandLayoutToFullscreenAboveLollipop(this)

        initBackground()
        initBottomButtons()
    }

    private fun initBackground() {
        clearOpaqueAncestorBackgrounds()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes.setWallpaperTouchEventsEnabled(false)
        }
        mIvBg!!.setBackgroundColor(0x66000000)
    }

    private fun initBottomButtons() {
        DisplayUtil.applyBottomInsetAsMargin(mLlBottom)

        mFlAdd5Min!!.scaleX = 0f
        mFlAdd5Min!!.scaleY = 0f
        mFlStrictMode!!.scaleX = 0f
        mFlStrictMode!!.scaleY = 0f
        mFlCancel!!.scaleX = 0f
        mFlCancel!!.scaleY = 0f
    }

    override fun setActionbar() { }

    override fun setEvents() {
        setRecyclerViewEvent()
    }

    private fun setRecyclerViewEvent() {
        mRecyclerView!!.viewTreeObserver.addOnGlobalLayoutListener {
            val maxHeight = getThingCardRegionMaxHeight()
            if (maxHeight > 0 && mRecyclerView!!.height > maxHeight) {
                val vlp: ViewGroup.LayoutParams = mRecyclerView!!.layoutParams
                vlp.height = maxHeight
                mRecyclerView!!.requestLayout()
                mRecyclerView!!.overScrollMode = View.OVER_SCROLL_ALWAYS
            }
        }
        val helper = ItemTouchHelper(CardTouchCallback())
        helper.attachToRecyclerView(mRecyclerView)
    }

    private fun getThingCardRegionMaxHeight(): Int {
        val rvLocation = IntArray(2)
        val bottomLocation = IntArray(2)
        mRecyclerView!!.getLocationOnScreen(rvLocation)
        mLlBottom!!.getLocationOnScreen(bottomLocation)

        val bottomLimit = bottomLocation[1]
        if (bottomLimit <= rvLocation[1]) return 0

        val verticalMargin = resources.getDimensionPixelSize(
            R.dimen.doing_thing_card_vertical_margin
        )
        return bottomLimit - rvLocation[1] - getSwipeToFinishReservedHeight() - verticalMargin
    }

    private fun getSwipeToFinishReservedHeight(): Int {
        val view = mTvSwipeToFinish ?: return 0
        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams
        return view.height + (lp?.topMargin ?: 0)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // singleTask 被重新带到前台（点击卡片 / 通知 / 小组件）：重新取当前正在做的 Thing（可能已
        // 切换），用数据库最新数据覆盖后重渲染卡片，使外观 / 内容改动立即生效。
        val binder = mDoingBinder ?: return
        mThing = binder.getThing() ?: return
        overlayLatestThingFromDb()
        applyClockThingBackground()
        initRecyclerView()
    }

    /** 用数据库里的最新数据覆盖当前渲染用的 mThing（id 不变），使外观 / 内容改动即时生效。 */
    private fun overlayLatestThingFromDb() {
        val id = mThing?.id ?: return
        ThingDAO.getInstance(mApp)?.getThingById(id)?.let { mThing = it }
    }

    private fun initAfterBindService() {
        mThing = mDoingBinder!!.getThing()
        if (mThing == null) {
            Toast.makeText(this, R.string.doing_toast_pass_data_error, Toast.LENGTH_LONG).show()
            DoingService.sStopReason = DoingRecord.STOP_REASON_INIT_FAILED
            finishWithStoppingService()
            return
        }

        // 开始做之后卡片外观 / 内容可能已在首页被修改并写入数据库，但 Service 持有的是开始计时时
        // 的旧 Thing 对象。渲染前用数据库最新数据覆盖，使外观等改动在 DoingActivity 立即生效。
        overlayLatestThingFromDb()
        applyClockThingBackground()

        if (mDoingBinder!!.isInStrictMode()) {
            Toast.makeText(this, R.string.doing_toast_already_strict_mode, Toast.LENGTH_LONG).show()
        }

        playBackgroundAnimation()

        updateTimeViews()

        initRecyclerView()
        DisplayUtil.applyBottomInsetAsScrollPadding(mRecyclerView)
        updateBottomButtons()

        if (mDoingBinder!!.getTimeInMillis() == -1L) {
            mInfinityHandler = Handler { message ->
                if (message.what == 96) {
                    val targetAlpha = if (mClockView!!.alpha > INFINITE_CLOCK_ALPHA_MID) {
                        INFINITE_CLOCK_ALPHA_LOW
                    } else {
                        INFINITE_CLOCK_ALPHA_HIGH
                    }
                    mClockView!!.animate().setDuration(3600).alpha(targetAlpha)
                    mInfinityHandler!!.sendEmptyMessageDelayed(96, 3600)
                }
                false
            }
        }

        startCountdownAndPlayAnimations()
    }

    private fun playBackgroundAnimation() {
        mIvBg!!.postDelayed({
            Blurry.with(mApp)
                .radius(16)
                .sampling(4)
                .color("#36000000".toColorInt())
                .animate(1600)
                .onto(f<View>(R.id.fl_bg_cover_doing) as ViewGroup)
        }, 160)
    }

    private fun updateTimeViews() {
        if (mDoingBinder!!.getTimeInMillis() == -1L) { // time is infinite
            sizeClockView((DisplayUtil.getScreenDensity(mApp) * 128).toInt())
            mClockView!!.setInfinite(true)
            mClockView!!.alpha = 0f
        } else {
            updateClockForLeftTime(mDoingBinder!!.getLeftTime(), true)
        }
    }

    private fun updateClockForLeftTime(leftTime: Long, updateDigits: Boolean) {
        val density: Float = DisplayUtil.getScreenDensity(mApp)
        val boxH = if (leftTime < HOUR_MILLIS) (density * 72).toInt() else (density * 56).toInt()
        sizeClockView(boxH)
        if (updateDigits) {
            mClockView!!.setTimeMillis(leftTime, false)
        }
    }

    private fun sizeClockView(boxHpx: Int) {
        val v = mClockView ?: return
        val lp = v.layoutParams
        lp.height = boxHpx
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        v.requestLayout()
    }

    private fun applyClockThingBackground() {
        val clock = mClockView ?: return
        val bg = mThing?.getBackground()
            ?: ThingBackground.pure(mThing?.getColor() ?: ContextCompat.getColor(this, R.color.app_accent))
        if (bg.mode == ThingBackground.Mode.GRADIENT) {
            clock.setInkGradient(bg.color, bg.endColor, timelyOrientation(bg.orientation))
        } else {
            clock.setInkColor(bg.color)
        }
        clock.setHostDark(true)
    }

    private fun timelyOrientation(orientation: ThingBackground.Orientation): Int {
        return when (orientation) {
            ThingBackground.Orientation.L_R -> TimelyClockView.ORIENTATION_L_R
            ThingBackground.Orientation.R_L -> TimelyClockView.ORIENTATION_R_L
            ThingBackground.Orientation.T_B -> TimelyClockView.ORIENTATION_T_B
            ThingBackground.Orientation.B_T -> TimelyClockView.ORIENTATION_B_T
            ThingBackground.Orientation.LT_RB -> TimelyClockView.ORIENTATION_LT_RB
            ThingBackground.Orientation.RB_LT -> TimelyClockView.ORIENTATION_RB_LT
            ThingBackground.Orientation.RT_LB -> TimelyClockView.ORIENTATION_RT_LB
            ThingBackground.Orientation.LB_RT -> TimelyClockView.ORIENTATION_LB_RT
        }
    }

    private fun initRecyclerView() {
        mCardWidth = getDoingThingCardWidth(mThing!!)
        val p = max(0, (DisplayUtil.getScreenSize(mApp).x - mCardWidth) / 2)
        mRecyclerView!!.setPadding(
            p, mRecyclerView!!.paddingTop, p, mRecyclerView!!.paddingBottom
        )

        val singleThing: List<Thing?> = Collections.singletonList(mThing)
        val adapter: BaseThingsAdapter = object : BaseThingsAdapter(this@DoingActivity) {

            override fun getCurrentMode(): Int = ModeManager.NORMAL

            override fun getThings(): List<Thing?> = singleThing

            override fun isFullSpanThingCard(thing: Thing): Boolean {
                return this@DoingActivity.isFullSpanThingCard(thing)
            }

            // 置顶标识着色与首页一致：根目录置顶用 accent 渐变（基类已处理），文件夹内置顶用所属
            // 父文件夹的颜色。基类默认返回 null，会让文件夹内置顶退化成黄色原图，故这里补上。
            override fun getStickyThingParentFolderBackground(thing: Thing): ThingBackground? {
                val folderId = thing.folderId ?: return null
                return ThingFolderDAO.getInstance(mApp)?.getFolderById(folderId)?.getBackground()
            }

            override fun onBindViewHolder(holder: BaseThingViewHolder, position: Int) {
                super.onBindViewHolder(holder, position)
                holder.cv!!.cardElevation = 0f
                holder.tvTitle!!.maxLines = Int.MAX_VALUE
                holder.tvContent!!.maxLines = Int.MAX_VALUE
                holder.rlReminder!!.visibility = View.GONE
                holder.rlHabit!!.visibility = View.GONE
                holder.vReminderSeparator!!.visibility = View.GONE
                holder.vHabitSeparator1!!.visibility = View.GONE
                holder.flDoing!!.visibility = View.GONE
            }

            override fun onChecklistAdapterInitialized(
                holder: BaseThingViewHolder, adapter: CheckListAdapter, thing: Thing
            ) {
                super.onChecklistAdapterInitialized(holder, adapter, thing)
                holder.cv!!.setShouldInterceptTouchEvent(false)
                adapter.setTvItemClickCallback(object : CheckListAdapter.TvItemClickCallback {
                    override fun onItemClick(itemPos: Int) {
                        val updatedContent: String = CheckListHelper.toggleChecklistItem(
                            thing.content, itemPos
                        )!!
                        thing.content = updatedContent
                        mDoingBinder!!.setThing(thing)
                        notifyDataSetChanged()
                        RemoteActionHelper.toggleChecklistItem(mApp, thing.id, itemPos)
                    }

                    override fun onItemSpaceClick(v: View?) {}
                })
            }
        }
        adapter.setCardWidth(mCardWidth)
        adapter.setShouldShowPrivateContent(true)
        adapter.setChecklistMaxItemCount(-1)
        mRecyclerView!!.layoutManager = SlowScrollLinearLayoutManager(this)
        mRecyclerView!!.adapter = adapter
        updateThingCardSurfaceAvailableHeight(adapter)
    }

    private fun updateThingCardSurfaceAvailableHeight(adapter: BaseThingsAdapter) {
        mRecyclerView!!.post {
            adapter.setThingCardSurfaceAvailableHeight(getDoingThingCardAvailableHeight())
            adapter.notifyDataSetChanged()
        }
    }

    private fun getDoingThingCardAvailableHeight(): Int {
        val recyclerView = mRecyclerView ?: return DisplayUtil.getScreenSize(mApp).y
        val bottomButtons = mLlBottom ?: return DisplayUtil.getScreenSize(mApp).y
        val recyclerLocation = IntArray(2)
        val bottomLocation = IntArray(2)
        recyclerView.getLocationOnScreen(recyclerLocation)
        bottomButtons.getLocationOnScreen(bottomLocation)
        val height = bottomLocation[1] - recyclerLocation[1]
        if (height > 0) return height

        val parent = recyclerView.parent as? View
        if (parent != null && parent.height > 0) {
            return parent.height
        }
        return DisplayUtil.getScreenSize(mApp).y
    }

    private fun updateBottomButtons() {
        if (mDoingBinder!!.getTimeInMillis() == -1L) {
            mFlAdd5Min!!.visibility = View.GONE
        }

        if (mDoingBinder!!.isInStrictMode()) {
            mFabStrictMode!!.setImageResource(R.drawable.ic_doing_strict_mode_on)
            mFabStrictMode!!.contentDescription = getString(R.string.cd_doing_strict_mode_on)
        } else {
            mFabStrictMode!!.setImageResource(R.drawable.ic_doing_strict_mode_off)
            mFabStrictMode!!.contentDescription = getString(R.string.cd_doing_strict_mode_off)
        }
    }

    private fun startCountdownAndPlayAnimations() {
        // after 1760ms, background blur animation will finish
        val intent: Intent = getIntent()
        val resume: Boolean = intent.getBooleanExtra(KEY_RESUME, false)
        mRecyclerView!!.postDelayed({
            playEnterAnimations()

            mDoingBinder!!.setCountdownListener(object : DoingService.DoingListener {

                override fun onLeftTimeChanged(
                    numbersFrom: IntArray?, numbersTo: IntArray?,
                    leftTimeBefore: Long, leftTimeAfter: Long
                ) {
                    playTimelyAnimation(numbersFrom!!, numbersTo!!, leftTimeAfter)
                }

                override fun onAdd5Min(leftTime: Long) {
                    updateClockForLeftTime(leftTime, true)
                }

                override fun onCountdownFailed() {
                    finish()
                }

                override fun onCountdownEnd() {
                }
            })
            mDoingBinder!!.startCountdown(resume)

            if (mDoingBinder!!.getTimeInMillis() == -1L && mInfinityHandler != null) {
                mInfinityHandler!!.sendEmptyMessageDelayed(96, 1760)
            }
        }, 1000)
    }

    private fun playEnterAnimations() {
        mRecyclerView!!.postDelayed({
            if (mDoingBinder!!.getTimeInMillis() == -1L) {
                mClockView!!.animate().setDuration(1600).alpha(INFINITE_CLOCK_ALPHA_HIGH)
            } else {
                mClockView!!.alpha = 0f
            }
            f<View>(R.id.tv_swipe_to_finish_doing).animate().setDuration(1600).alpha(1f)
            mRecyclerView!!.animate().setDuration(1600).alpha(0.84f)
            mRecyclerView!!.scrollBy(0, Int.MAX_VALUE)
        }, 160) // executed after 1160ms, animation ends at 2760ms
        mRecyclerView!!.postDelayed({
            mRecyclerView!!.smoothScrollToPosition(0)

            val oi = OvershootInterpolator()
            mFlAdd5Min!!.animate().setDuration(360).setInterpolator(oi).scaleX(1f)
            mFlAdd5Min!!.animate().setDuration(360).setInterpolator(oi).scaleY(1f)
            mFlStrictMode!!.animate().setDuration(360).setInterpolator(oi).scaleX(1f)
            mFlStrictMode!!.animate().setDuration(360).setInterpolator(oi).scaleY(1f)
            mFlCancel!!.animate().setDuration(360).setInterpolator(oi).scaleX(1f)
            mFlCancel!!.animate().setDuration(360).setInterpolator(oi).scaleY(1f)
        }, 1200) // executed after 1360ms, animation ends at 1720ms
    }

    private fun playTimelyAnimation(from: IntArray, to: IntArray, leftTimeAfter: Long) {
        updateClockForLeftTime(leftTimeAfter, false)
        mClockView!!.alpha = 1f
        mClockView!!.animateDigits(from, to, leftTimeAfter)
    }

    open fun onClick(view: View) {
        val id = view.id
        if (id == R.id.fab_add_5_min) {
            if (mDoingBinder!!.canAdd5Min()) {
                mDoingBinder!!.add5Min()
            }
        } else if (id == R.id.fab_strict_mode) {
            toggleStrictMode()
        } else if (id == R.id.fab_cancel_doing) {
            val adf = AlertDialogFragment()
            adf.setConfirmBackground(mThing!!.getBackground())
            adf.setContent(getString(R.string.doing_alert_stop_doing_content))
            adf.setConfirmListener(object : AlertDialogFragment.ConfirmListener {
                override fun onConfirm() {
                    DoingService.sStopReason = DoingRecord.STOP_REASON_CANCEL_USER
                    finishWithStoppingService()
                }
            })
            adf.show(supportFragmentManager, AlertDialogFragment.TAG)
        }
    }

    private fun toggleStrictMode() {
        val inStrictMode = mDoingBinder!!.isInStrictMode()
        if (inStrictMode) {
            if (!mDoingBinder!!.hasTurnedStrictModeOff()) {
                mFabStrictMode!!.setImageResource(R.drawable.ic_doing_strict_mode_off)
                mFabStrictMode!!.contentDescription = getString(R.string.cd_doing_strict_mode_off)
            } else {
                showAlertDialog(
                    R.string.doing_alert_close_strict_twice_title,
                    R.string.doing_alert_close_strict_twice_content
                )
                return
            }
        } else {
            if (!mDoingBinder!!.hasTurnedStrictModeOn()) {
                showAlertDialog(
                    R.string.doing_alert_first_strict_mode_title,
                    R.string.doing_alert_first_strict_mode_content
                )
            }
            mFabStrictMode!!.setImageResource(R.drawable.ic_doing_strict_mode_on)
            mFabStrictMode!!.contentDescription = getString(R.string.cd_doing_strict_mode_on)
        }
        mDoingBinder!!.setInStrictMode(!inStrictMode)
        mDoingBinder!!.setPlayedTimes(0)
        mDoingBinder!!.setStartPlayTime(-1L)
        mDoingBinder!!.setTotalPlayedTime(0)
    }

    private fun showAlertDialog(@StringRes titleRes: Int, @StringRes contentRes: Int) {
        val adf = AlertDialogFragment()
        adf.setTitleBackground(mThing!!.getBackground())
        adf.setConfirmBackground(mThing!!.getBackground())
        adf.setShowCancel(false)
        adf.setTitle(getString(titleRes))
        adf.setContent(getString(contentRes))
        adf.show(supportFragmentManager, AlertDialogFragment.TAG)
    }

    private fun finishWithStoppingService() {
        App.setDoingThingId(-1L)
        unbindService(mServiceConnection)
        stopService(Intent(this, DoingService::class.java))
        mServiceUnbind = true
        finish()
    }

    private class SlowScrollLinearLayoutManager(context: Context?) : LinearLayoutManager(context) {

        private val mSmoothScroller: LinearSmoothScroller = object : LinearSmoothScroller(context) {
            override fun calculateTimeForScrolling(dx: Int): Int = 360
        }

        override fun smoothScrollToPosition(
            recyclerView: RecyclerView, state: RecyclerView.State, position: Int
        ) {
            mSmoothScroller.targetPosition = position
            startSmoothScroll(mSmoothScroller)
        }
    }

    private inner class CardTouchCallback : ItemTouchHelper.Callback() {

        override fun getMovementFlags(
            recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
        ): Int {
            val swipeFlags = ItemTouchHelper.START or ItemTouchHelper.END
            return makeMovementFlags(0, swipeFlags)
        }

        override fun onMove(
            recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean = false

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val pair: Pair<Thing, Int> = App.getThingAndPosition(mApp, mThing!!.id, -1)
            DoingService.sStopReason = DoingRecord.STOP_REASON_FINISH
            if (mThing!!.type == Thing.HABIT) {
                if (!RemoteActionHelper.finishHabitOnce(
                        mApp, mThing, pair.second ?: -1, DoingService.sHrTime
                    )) {
                    DoingService.sStopReason = DoingRecord.STOP_REASON_CANCEL_USER
                }
            } else {
                RemoteActionHelper.finishReminder(mApp, mThing, pair.second ?: -1)
            }
            finishWithStoppingService()
        }

        override fun onChildDraw(
            c: Canvas, recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder, dX: Float, dY: Float,
            actionState: Int, isCurrentlyActive: Boolean
        ) {
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                val displayWidth = DisplayUtil.getDisplaySize(mApp).x
                val v: View = mRecyclerView!!
                if (dX < 0) {
                    v.alpha = 1.0f + dX / v.right
                } else {
                    v.alpha = 1.0f - dX / (displayWidth - v.left)
                }
            }
        }
    }

    companion object {
        const val TAG: String = "DoingActivity"

        const val KEY_RESUME: String = "$TAG.resume"

        const val BROADCAST_ACTION_JUST_FINISH: String = "$TAG.just_finish"

        private const val MINUTE_MILLIS: Long = 60 * 1000L
        private const val HOUR_MILLIS: Long   = 60 * MINUTE_MILLIS
        private const val INFINITE_CLOCK_ALPHA_HIGH: Float = 1.0f
        private const val INFINITE_CLOCK_ALPHA_LOW: Float = 0.75f
        private const val INFINITE_CLOCK_ALPHA_MID: Float = 0.875f

        @JvmStatic
        fun getOpenIntent(context: Context?, resume: Boolean): Intent {
            return Intent(context, DoingActivity::class.java).putExtra(KEY_RESUME, resume)
        }
    }
}
