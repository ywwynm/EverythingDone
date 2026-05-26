@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.activities

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.ActivityManager
import android.os.Build
import androidx.activity.OnBackPressedCallback
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.google.android.material.navigation.NavigationView
import androidx.core.app.ActivityCompat
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.graphics.drawable.DrawerArrowDrawable
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.ItemTouchHelper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewAnimationUtils
import android.view.ViewTreeObserver
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources

import com.bumptech.glide.Glide
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.FrequentSettings
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.adapters.BaseThingsAdapter
import com.ywwynm.everythingdone.adapters.ThingsAdapter
import com.ywwynm.everythingdone.adapters.ThingsAdapterWrapper
import com.ywwynm.everythingdone.appwidgets.AppWidgetHelper
import com.ywwynm.everythingdone.database.HabitDAO
import com.ywwynm.everythingdone.database.ReminderDAO
import com.ywwynm.everythingdone.fragments.AlertDialogFragment
import com.ywwynm.everythingdone.fragments.LongTextDialogFragment
import com.ywwynm.everythingdone.fragments.ThreeActionsAlertDialogFragment
import com.ywwynm.everythingdone.helpers.AlarmHelper
import com.ywwynm.everythingdone.helpers.AppUpdateHelper
import com.ywwynm.everythingdone.helpers.AuthenticationHelper
import com.ywwynm.everythingdone.helpers.CheckListHelper
import com.ywwynm.everythingdone.helpers.SendInfoHelper
import com.ywwynm.everythingdone.helpers.ThingDoingHelper
import com.ywwynm.everythingdone.helpers.ThingExporter
import com.ywwynm.everythingdone.managers.ModeManager
import com.ywwynm.everythingdone.managers.ThingManager
import com.ywwynm.everythingdone.model.DoingRecord
import com.ywwynm.everythingdone.model.Habit
import com.ywwynm.everythingdone.model.HabitRecord
import com.ywwynm.everythingdone.model.Reminder
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.model.ThingsCounts
import com.ywwynm.everythingdone.permission.PermissionUtil
import com.ywwynm.everythingdone.permission.SimplePermissionCallback
import com.ywwynm.everythingdone.services.DoingService
import com.ywwynm.everythingdone.utils.AppearanceUtil
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.EdgeEffectUtil
import com.ywwynm.everythingdone.utils.FileUtil
import com.ywwynm.everythingdone.utils.KeyboardUtil
import com.ywwynm.everythingdone.utils.LocaleUtil
import com.ywwynm.everythingdone.utils.SystemNotificationUtil
import com.ywwynm.everythingdone.views.ActivityHeader
import com.ywwynm.everythingdone.views.DrawerHeader
import com.ywwynm.everythingdone.views.FloatingActionButton
import com.ywwynm.everythingdone.views.Snackbar
import com.ywwynm.everythingdone.views.ThingsStaggeredLayoutManager
import com.ywwynm.everythingdone.views.pickers.ColorPicker
import com.ywwynm.everythingdone.views.reveal.RevealLayout
import com.ywwynm.everythingdone.views.reveal.ShiningBorder

import java.io.File
import java.util.ArrayList
import java.util.HashSet
import kotlin.math.hypot
import androidx.core.view.get
import androidx.core.graphics.toColorInt

class ThingsActivity : EverythingDoneBaseActivity() {

    private var mApp: App? = null

    private var mThingManager: ThingManager? = null

    private var mRevealLayout: RevealLayout? = null
    private var mShiningBorder: ShiningBorder? = null
    // Cached "full-screen" defaults set in findViews() — used to restore mShiningBorder
    // after a card-scoped animation overrode them.
    private var mShiningBorderDefaultStroke: Float = 0f
    private var mShiningBorderDefaultCornerRadius: Float = 0f
    private var mShiningBorderDefaultDuration: Long = 0L
    private var mShiningBorderDefaultParticleBaseSize: Float = 0f
    private var mShiningBorderDefaultMaxParticles: Int = 160
    private var mViewToReveal: View? = null
    private var mTvNoResult: TextView? = null

    private var mActionbar: Toolbar? = null
    private var mViewInsideActionbar: View? = null

    private var mEtSearch: EditText? = null
    private var mColorPicker: ColorPicker? = null

    private var mModeManager: ModeManager? = null

    private var mDrawerLayout: DrawerLayout? = null
    private var mDrawer: NavigationView? = null
    private var mDrawerHeader: DrawerHeader? = null
    private var mPreviousItem: MenuItem? = null

    private var mActivityHeader: ActivityHeader? = null

    private var mFab: FloatingActionButton? = null

    private var mRecyclerView: RecyclerView? = null
    private var mAdapter: ThingsAdapterWrapper? = null
    private var mThingsTouchHelper: ItemTouchHelper? = null
    private var mStaggeredGridLayoutManager: ThingsStaggeredLayoutManager? = null

    private var mSpan: Int = 0

    private var mNormalSnackbar: Snackbar? = null
    private var mUndoSnackbar: Snackbar? = null
    private var mHabitSnackbar: Snackbar? = null
    private var mUndoThings: MutableList<Thing>? = null
    private var mUndoPositions: MutableList<Int>? = null
    private var mUndoLocations: MutableList<Long>? = null
    private var mUndoHabitRecords: MutableList<HabitRecord>? = null
    private var mThingsIdsToUpdateWidget: HashSet<Long>? = null
    private var mUndoAll: Boolean = false
    private var mStateToUndoFrom: Int = 0

    /**
     * Used to know whether scrolling of mRecyclerView is caused by swipe-to-dismiss
     * or user's touch event.
     */
    private var mScrollCausedByFinger: Boolean = true

    /**
     * Used to know whether reveal animation for entering DetailActivity with type CREATE
     * is playing or not.
     */
    private var mIsRevealAnimPlaying: Boolean = false

    private var mCanSeeUi: Boolean = false
    private var mUpdateMainUiInOnResume: Boolean = true
    private var mRestoredFromSavedState: Boolean = false

    private val initRecyclerViewRunnable: Runnable = Runnable {
        if (mAdapter!!.getItemCount() <=
            ThingsCounts.getInstance(mApp)!!.getThingsCountForActivityHeader(mApp!!.getLimit())
        ) {
            mThingManager!!.loadThings()
        }
        mAdapter!!.setShouldThingsAnimWhenAppearing(!mRestoredFromSavedState)
        mAdapter!!.attachToRecyclerView(mRecyclerView)
        mStaggeredGridLayoutManager = ThingsStaggeredLayoutManager(
            mSpan, StaggeredGridLayoutManager.VERTICAL
        )
        mRecyclerView!!.layoutManager = mStaggeredGridLayoutManager
    }

    private var mRemoteIntent: Intent? = null

    private val mUpdateUiReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            var mRemoteIntentInfo = "mRemoteIntent[null]"
            if (mRemoteIntent != null) {
                mRemoteIntentInfo = "mRemoteIntent.resultCode[" +
                    mRemoteIntent!!.getIntExtra(
                        Def.Communication.KEY_RESULT_CODE,
                        Def.Communication.RESULT_NO_UPDATE
                    ) + "]"
            }
            Log.i(TAG, "UPDATE_MAIN_UI broadcast received, "
                + "canSeeThingsActivity[" + mCanSeeUi + "], "
                + mRemoteIntentInfo)
            if (!mCanSeeUi) {
                if (mRemoteIntent != null) {
                    updateMainUi(mRemoteIntent!!)
                    mDrawerLayout!!.postDelayed({
                        mRemoteIntent = intent
                    }, 600)
                } else {
                    mRemoteIntent = intent
                }
                return
            }
            updateMainUi(intent)
        }
    }

    private var mShouldCloseDrawer: Boolean = false

    private var mDontPickSearchColor: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        mRestoredFromSavedState = savedInstanceState != null

        super.onCreate(savedInstanceState)

        setDrawer()

        val filter = IntentFilter(Def.Communication.BROADCAST_ACTION_UPDATE_MAIN_UI)
        ContextCompat.registerReceiver(
            this, mUpdateUiReceiver, filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun getLayoutResource(): Int = R.layout.activity_things

    override fun beforeInit() {
        // this will be only called in onCreate(), which means this Activity has unregistered
        // receiver. So these two boolean values are useless now.
        App.setSomethingUpdatedSpecially(false)
        App.setJustNotifyAll(false)
        if (App.isSearching) {
            App.isSearching = false // maybe we searched in multi-window mode and quit it later
        }

        AppUpdateHelper.getInstance(this)!!.showInfo(this)

        tryToShowFeedbackErrorDialog()

        val intent: Intent? = getIntent()
        if (intent != null) {
            val limit = intent.getIntExtra(Def.Communication.KEY_LIMIT, -1)
            if (limit != -1 && limit != App.getApp()!!.getLimit()) {
                App.getApp()!!.setLimit(limit, true)
            }
        }
    }

    private fun tryToShowFeedbackErrorDialog() {
        val file = File(applicationInfo.dataDir + "/files/" + Def.Meta.FEEDBACK_ERROR_FILE_NAME)
        if (file.exists()) {
            val adf = AlertDialogFragment()
            val color = DisplayUtil.getRandomColor(this)
            adf.setTitleColor(color)
            adf.setConfirmColor(color)
            adf.setTitle(getString(R.string.app_crash_title))
            adf.setContent(getString(R.string.app_crash_content))
            adf.setConfirmText(getString(R.string.app_crash_send_now))
            adf.setConfirmListener(object : AlertDialogFragment.ConfirmListener {
                override fun onConfirm() {
                    tryToFeedbackError()
                }
            })
            adf.setCancelListener(object : AlertDialogFragment.CancelListener {
                override fun onCancel() {
                    deleteFeedbackFile()
                }
            })
            adf.show(fragmentManager, AlertDialogFragment.TAG)
        }
    }

    private fun tryToFeedbackError() {
        SendInfoHelper.sendFeedback(this@ThingsActivity, true)
        deleteFeedbackFile()
    }

    private fun deleteFeedbackFile() {
        val file = File(applicationInfo.dataDir + "/files/" + Def.Meta.FEEDBACK_ERROR_FILE_NAME)
        FileUtil.deleteFile(file)
    }

    override fun onPostResume() {
        super.onPostResume()
        checkIfReminderHabitsCorrect()
    }

    private fun checkIfReminderHabitsCorrect() {
        if (App.getApp()!!.getLimit() >= Def.LimitForGettingThings.ALL_FINISHED) {
            return
        }
        Thread(object : Runnable {
            override fun run() {
                val app = App.getApp()!!
                val things: List<Thing?> = ThingManager.getInstance(app)!!.getThings()!!
                val reminderDAO: ReminderDAO = ReminderDAO.getInstance(app)!!
                val habitDAO: HabitDAO = HabitDAO.getInstance(app)!!
                // 在某些启用对齐唤醒的设备上，闹钟的时间可能会有偏差，不需要提醒
                val CHANCE: Long = 6 * 60 * 1000L
                for (thing in things) {
                    val id = thing!!.id
                    @Thing.Type val type = thing.type
                    if (Thing.isReminderType(type)) {
                        val reminder: Reminder? = reminderDAO.getReminderById(id)
                        if (reminder != null
                            && reminder.notifyTime + CHANCE < System.currentTimeMillis()
                            && reminder.state == Reminder.UNDERWAY
                        ) {
                            alertReminderHabitIncorrect()
                            return
                        }
                    } else if (type == Thing.HABIT) {
                        val habit: Habit? = habitDAO.getHabitById(id)
                        if (habit != null
                            && habit.getMinHabitReminderTime() + CHANCE < System.currentTimeMillis()
                        ) {
                            alertReminderHabitIncorrect()
                            return
                        }
                    }
                }
                // No Reminders/Habits/Goals are in "wrong state" but maybe we should still create
                // all alarms again since we can't know if alarms of things underway are active.
                AlarmHelper.createAllAlarms(app, false)
            }
        }).start()
    }

    private fun alertReminderHabitIncorrect() {
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            val color = DisplayUtil.getRandomColor(App.getApp())
            if (!LocaleUtil.isChinese(App.getApp())) {
                val ltdf = LongTextDialogFragment()
                ltdf.setAccentColor(color)
                ltdf.setShowCancel(false)
                ltdf.setTitle(getString(R.string.title_incorrect_reminder_habit))
                ltdf.setContent(getString(R.string.content_incorrect_reminder_habit))
                ltdf.setConfirmText(getString(R.string.act_reset_alarms))
                ltdf.setConfirmListener {
                    AlarmHelper.createAllAlarms(App.getApp(), true)
                    if (mAdapter != null) {
                        mAdapter!!.notifyDataSetChanged()
                    }
                }
                if (mCanSeeUi) {
                    ltdf.show(fragmentManager, LongTextDialogFragment.TAG)
                }
            } else {
                val adf = AlertDialogFragment()
                adf.setTitleColor(color)
                adf.setConfirmColor(color)
                adf.setShowCancel(false)
                adf.setTitle(getString(R.string.title_incorrect_reminder_habit))
                adf.setContent(getString(R.string.content_incorrect_reminder_habit))
                adf.setConfirmText(getString(R.string.act_reset_alarms))
                adf.setConfirmListener(object : AlertDialogFragment.ConfirmListener {
                    override fun onConfirm() {
                        AlarmHelper.createAllAlarms(App.getApp(), true)
                        if (mAdapter != null) {
                            mAdapter!!.notifyDataSetChanged()
                        }
                    }
                })
                if (mCanSeeUi) {
                    adf.show(fragmentManager, AlertDialogFragment.TAG)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        mCanSeeUi = true
        mAdapter!!.setShouldWaitNotify(false)
    }

    override fun onResume() {
        super.onResume()
        updateTaskDescription()

        var mRemoteIntentInfo = "mRemoteIntent[null]"
        if (mRemoteIntent != null) {
            mRemoteIntentInfo = "mRemoteIntent.resultCode[" +
                mRemoteIntent!!.getIntExtra(
                    Def.Communication.KEY_RESULT_CODE,
                    Def.Communication.RESULT_NO_UPDATE
                ) + "]"
        }
        Log.i(TAG, "onResume called, mUpdateMainUiInOnResume[" + mUpdateMainUiInOnResume + "], "
            + "justNotifyAll[" + App.justNotifyAll() + "], "
            + mRemoteIntentInfo)

        if (mUpdateMainUiInOnResume) {
            if (App.justNotifyAll()) {
                mRecyclerView!!.postDelayed({
                    justNotifyAll()
                    mRemoteIntent = null
                }, 540)
            } else if (mRemoteIntent != null) {
                updateMainUi(mRemoteIntent!!)
            } else {
                mAdapter!!.tryToNotify()
            }
        }

        mFab!!.rippleColor = App.newThingColor
        mActivityHeader!!.updateText()
        if (App.isSearching) {
            updateSearchNoResult(0)
        } else {
            hideSearchNoResult()
        }

        KeyboardUtil.hideKeyboard(currentFocus)
    }

    override fun onPause() {
        super.onPause()
        dismissSnackbars()
        mScrollCausedByFinger = false

        KeyboardUtil.hideKeyboard(currentFocus)
    }

    override fun onStop() {
        super.onStop()
        mCanSeeUi = false
        mAdapter!!.setShouldWaitNotify(true)
        mApp!!.deleteAttachmentFiles()
        if (mShouldCloseDrawer) {
            mDrawerLayout!!.closeDrawer(GravityCompat.START, false)
            mShouldCloseDrawer = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(mUpdateUiReceiver)
        mApp!!.setDetailActivityRun(false)
        updateTaskDescription()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // launched from things list widget
        val newLimit = intent.getIntExtra(Def.Communication.KEY_LIMIT, -1)
        if (newLimit != -1 && mApp!!.getLimit() != newLimit) {
            if (mModeManager!!.getCurrentMode() != ModeManager.NORMAL) {
                mModeManager!!.backNormalMode(0)
            }
            if (App.isSearching) {
                toggleSearching(false)
            }
            changeToLimit(newLimit, true)
            KeyboardUtil.hideKeyboard(window)
        }
    }

    private fun updateTaskDescription() {
        val bmd: BitmapDrawable? = AppCompatResources.getDrawable(this, R.mipmap.ic_launcher) as BitmapDrawable?
        if (bmd != null) {
            val bm: Bitmap = bmd.bitmap
            setTaskDescription(
                ActivityManager.TaskDescription(
                    getString(R.string.everythingdone), bm,
                    ContextCompat.getColor(this, R.color.bg_activity_things)
                )
            )
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (App.isSearching) {
            menuInflater.inflate(R.menu.menu_search, menu)
            tintHomeMenuIconsForAppearance(menu)
            mColorPicker!!.setTintTarget(menu.findItem(R.id.act_select_color).icon!!)
            mColorPicker!!.updateAnchor()
            return true
        }

        val limit = mApp!!.getLimit()
        if (limit <= Def.LimitForGettingThings.GOAL_UNDERWAY) {
            menuInflater.inflate(R.menu.menu_things_underway, menu)
            if (limit == Def.LimitForGettingThings.NOTE_UNDERWAY) {
                menu.findItem(R.id.act_sort_by_alarm).isVisible = false
            }
        } else if (limit == Def.LimitForGettingThings.ALL_FINISHED) {
            menuInflater.inflate(R.menu.menu_things_finished, menu)
        } else {
            menuInflater.inflate(R.menu.menu_things_deleted, menu)
        }
        tintHomeMenuIconsForAppearance(menu)
        return true
    }

    private fun tintHomeMenuIconsForAppearance(menu: Menu) {
        if (!AppearanceUtil.isDarkMode(this)) return

        val tint = ContextCompat.getColor(this, R.color.app_accent)
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            item.icon = DisplayUtil.opaqueTintDrawable(this, item.icon, tint)
        }
    }

    private fun applyDrawerMenuIconTintForAppearance() {
        mDrawer!!.itemIconTintList = null
        if (!AppearanceUtil.isDarkMode(this)) return

        val tint = ContextCompat.getColor(this, R.color.app_chrome_control_unchecked)
        tintDrawerMenuIcons(mDrawer!!.menu, tint)
    }

    private fun tintDrawerMenuIcons(menu: Menu, tint: Int) {
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            item.icon = DisplayUtil.opaqueTintDrawable(this, item.icon, tint)
            if (item.hasSubMenu()) {
                tintDrawerMenuIcons(item.subMenu!!, tint)
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val size = mThingManager!!.getThings()!!.size
        val itemId = item.itemId
        if (itemId == R.id.act_search) {
            toggleSearching(true)
        } else if (itemId == R.id.act_finish_all) {
            if (size != 1) {
                handleUpdateStates(Thing.FINISHED)
            }
        } else if (itemId == R.id.act_delete_all) {
            if (size != 1) {
                handleUpdateStates(Thing.DELETED)
            }
        } else if (itemId == R.id.act_delete_all_forever) {
            if (size != 1) {
                handleUpdateStates(Thing.DELETED_FOREVER)
            }
        } else if (itemId == R.id.act_sort_by_alarm) {
            mRecyclerView!!.scrollToPosition(0)
            mActivityHeader!!.reset(true)
            mFab!!.showFromBottom()
            mThingManager!!.updateLocationsByAlarmTime()
            mAdapter!!.setShouldThingsAnimWhenAppearing(true)
            mAdapter!!.notifyDataSetChanged()
            AppWidgetHelper.updateAllThingsListAppWidgets(mApp)
        } else if (itemId == R.id.act_select_color) {
            dismissSnackbars()
            mColorPicker!!.setAnchor(findViewById(R.id.act_select_color))
            mColorPicker!!.show()
        }
        return super.onOptionsItemSelected(item)
    }

    private var lastClickBack: Long = -1

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == Def.Communication.REQUEST_ACTIVITY_DETAIL) {
            if (data != null) updateMainUi(data, resultCode)
        } else if (requestCode == Def.Communication.REQUEST_ACTIVITY_SETTINGS) {
            if (resultCode == Def.Communication.RESULT_UPDATE_DRAWER_HEADER_DONE) {
                mDrawerHeader!!.updateDrawerHeader()
            }
        }
    }

    private fun updateMainUi(data: Intent) {
        val resultCode = data.getIntExtra(
            Def.Communication.KEY_RESULT_CODE,
            Def.Communication.RESULT_NO_UPDATE
        )
        updateMainUi(data, resultCode)
    }

    private fun updateMainUi(data: Intent, resultCode: Int) {
        Log.i(TAG, "updateMainUi called, resultCode[$resultCode]")
        mUpdateMainUiInOnResume = false
        dismissSnackbars()
        when (resultCode) {
            Def.Communication.RESULT_JUST_NOTIFY_DATASET_CHANGED ->
                mDrawerLayout!!.postDelayed({
                    justNotifyAll()
                    mUpdateMainUiInOnResume = true
                    mRemoteIntent = null
                }, 560)
            Def.Communication.RESULT_CREATE_THING_DONE ->
                updateMainUiForCreateDone(data)
            Def.Communication.RESULT_CREATE_BLANK_THING ->
                mDrawerLayout!!.postDelayed({
                    mNormalSnackbar!!.setMessage(R.string.sb_cannot_be_blank)
                    mNormalSnackbar!!.show()
                    mUpdateMainUiInOnResume = true
                    if (mCanSeeUi) {
                        App.setSomethingUpdatedSpecially(false)
                    }
                    mRemoteIntent = null
                }, 560)
            Def.Communication.RESULT_ABANDON_NEW_THING ->
                mDrawerLayout!!.postDelayed({
                    mNormalSnackbar!!.setMessage(R.string.sb_abandon_new_thing)
                    mNormalSnackbar!!.show()
                    mUpdateMainUiInOnResume = true
                    if (mCanSeeUi) {
                        App.setSomethingUpdatedSpecially(false)
                    }
                    mRemoteIntent = null
                }, 560)
            Def.Communication.RESULT_UPDATE_THING_DONE_TYPE_SAME ->
                updateMainUiForUpdateSameType(data)
            Def.Communication.RESULT_UPDATE_THING_DONE_TYPE_DIFFERENT ->
                updateMainUiForUpdateDifferentType(data)
            Def.Communication.RESULT_UPDATE_THING_STATE_DIFFERENT ->
                updateMainUiForUpdateDifferentState(data)
            Def.Communication.RESULT_STICKY_THING_OR_CANCEL ->
                updateMainUiForStickyOrCancel(data)
            Def.Communication.RESULT_DOING_OR_CANCEL ->
                updateMainUiForDoingOrCancel(data)
            else -> {
                // RESULT_NO_UPDATE and any other
                if (mRemoteIntent == null) {
                    mUpdateMainUiInOnResume = true
                    if (mCanSeeUi) {
                        App.setSomethingUpdatedSpecially(false)
                        App.setJustNotifyAll(false)
                    }
                } else {
                    val mRemoteIntentResultCode = mRemoteIntent!!.getIntExtra(
                        Def.Communication.KEY_RESULT_CODE, Def.Communication.RESULT_NO_UPDATE
                    )
                    if (mRemoteIntentResultCode == Def.Communication.RESULT_NO_UPDATE) {
                        mRecyclerView!!.postDelayed({
                            mAdapter!!.tryToNotify()
                            mUpdateMainUiInOnResume = true
                            if (mCanSeeUi) {
                                App.setSomethingUpdatedSpecially(false)
                            }
                            mRemoteIntent = null
                        }, 540)
                    } else {
                        updateMainUi(mRemoteIntent!!, mRemoteIntentResultCode)
                    }
                }
            }
        }
    }

    private fun updateMainUiForCreateDone(data: Intent) {
        if (App.isSearching) {
            toggleSearching(false)
        }

        if (mApp!!.getLimit() != Def.LimitForGettingThings.ALL_UNDERWAY) {
            mFab!!.spread()
            mApp!!.setLimit(Def.LimitForGettingThings.ALL_UNDERWAY, true)
            invalidateOptionsMenu()
            mRecyclerView!!.scrollToPosition(0)
            mAdapter!!.setShouldThingsAnimWhenAppearing(false)
            mAdapter!!.notifyDataSetChanged()
            mActivityHeader!!.reset(false)
        }

        val underway: MenuItem = mDrawer!!.menu[0]
        mPreviousItem!!.isChecked = false
        underway.isChecked = true
        mPreviousItem = underway

        val createdDone = data.getBooleanExtra(Def.Communication.KEY_CREATED_DONE, false)
        val justNotifyAll = App.justNotifyAll()
        val thingToCreate: Thing = data.getParcelableExtra(Def.Communication.KEY_THING)!!

        mDrawerLayout!!.postDelayed({
            val change = if (createdDone) {
                data.getBooleanExtra(Def.Communication.KEY_CALL_CHANGE, false)
            } else {
                mThingManager!!.create(thingToCreate, true, true)
            }
            mRecyclerView!!.postDelayed({
                val newPos = mThingManager!!.getPositionToInsertNewThing()
                val newId = thingToCreate.id
                val bg: ThingBackground? = thingToCreate.getBackground()
                mAdapter!!.armNewItemAnimation(newPos, newId,
                    object : ThingsAdapter.OnNewItemBoundListener {
                        override fun onNewItemBound(position: Int, holder: BaseThingsAdapter.BaseThingViewHolder?) {
                            playNewItemAnimation(holder!!, bg!!)
                        }
                    })
                if (justNotifyAll) {
                    justNotifyAll()
                } else {
                    if (change) {
                        mAdapter!!.notifyItemChanged(1)
                    } else {
                        mAdapter!!.notifyItemInserted(newPos)
                    }
                }
                afterUpdateMainUiForCreateDone()
            }, 300)
        }, 300)
    }

    private fun afterUpdateMainUiForCreateDone() {
        if (mModeManager!!.getCurrentMode() == ModeManager.SELECTING) {
            updateSelectingUi(false)
        }
        mActivityHeader!!.updateText()
        mDrawerHeader!!.updateTexts()
        mUpdateMainUiInOnResume = true
        if (mCanSeeUi) {
            App.setSomethingUpdatedSpecially(false)
        }
        mRemoteIntent = null
    }

    private fun updateMainUiForUpdateSameType(data: Intent) {
        @Thing.Type val typeBefore: Int = data.getIntExtra(
            Def.Communication.KEY_TYPE_BEFORE, Thing.NOTE
        )
        val contentUpdatedThing: Thing? = data.getParcelableExtra(Def.Communication.KEY_THING)
        val justNotifyAll = App.justNotifyAll()
        Log.i(TAG, "updateMainUiForUpdateSameType called, "
            + "typeBefore[" + typeBefore + "], "
            + "justNotifyAll[" + justNotifyAll + "]")
        mDrawerLayout!!.postDelayed({
            Log.i(TAG, "updateMainUiForUpdateSameType: delayed Runnable started.")
            @Thing.State var thingState: Int = Thing.UNDERWAY
            if (contentUpdatedThing != null) {
                thingState = contentUpdatedThing.state
            }
            if (justNotifyAll) {
                justNotifyAll()
            } else if (Thing.isTypeStateMatchLimit(typeBefore, thingState, mApp!!.getLimit())) {
                val pos = data.getIntExtra(Def.Communication.KEY_POSITION, 1)
                Log.i(TAG, "type and state match current limit, "
                    + "thing's position[" + pos + "], "
                    + "isSearching[" + App.isSearching + "]")
                if (!App.isSearching) {
                    mAdapter!!.notifyItemChanged(pos)
                } else {
                    val things: MutableList<Thing?> = mThingManager!!.getThings()!!
                    if (pos > 0 && pos < things.size) {
                        val thing: Thing = things[pos]!!
                        if (thing.matchSearchRequirement(
                                mEtSearch!!.text.toString(),
                                mColorPicker!!.getPickedColor()
                            )
                        ) {
                            mAdapter!!.notifyItemChanged(pos)
                        } else {
                            things.removeAt(pos)
                            mAdapter!!.notifyItemRemoved(pos)
                        }
                        handleSearchResults()
                    }
                }
                if (mModeManager!!.getCurrentMode() == ModeManager.SELECTING) {
                    updateSelectingUi(false)
                }
            }
            mDrawerHeader!!.updateCompletionRate()
            mUpdateMainUiInOnResume = true
            if (mCanSeeUi) {
                App.setSomethingUpdatedSpecially(false)
            }
            mRemoteIntent = null
        }, 560)
    }

    private fun updateMainUiForUpdateDifferentType(data: Intent) {
        val thing: Thing = data.getParcelableExtra(Def.Communication.KEY_THING)!!
        @Thing.Type val typeBefore: Int = data.getIntExtra(
            Def.Communication.KEY_TYPE_BEFORE, Thing.NOTE
        )

        val justNotifyAll = App.justNotifyAll()
        mDrawerLayout!!.postDelayed({
            val type = thing.type
            val curLimit = mApp!!.getLimit()
            val limitMatched = Thing.isTypeStateMatchLimit(type, Thing.UNDERWAY, curLimit)

            if (justNotifyAll || limitMatched) {
                justNotifyAll()
            } else if (Thing.isTypeStateMatchLimit(typeBefore, Thing.UNDERWAY, curLimit)) {
                if (App.isSearching) {
                    mAdapter!!.notifyItemRemoved(data.getIntExtra(
                        Def.Communication.KEY_POSITION, 1
                    ))
                    handleSearchResults()
                } else {
                    val change = data.getBooleanExtra(Def.Communication.KEY_CALL_CHANGE, false)
                    if (change) {
                        mAdapter!!.notifyItemChanged(1)
                    } else {
                        mAdapter!!.notifyItemRemoved(data.getIntExtra(
                            Def.Communication.KEY_POSITION, 1
                        ))
                    }
                }
                if (mModeManager!!.getCurrentMode() == ModeManager.SELECTING) {
                    updateSelectingUi(false)
                }
            }

            mDrawerHeader!!.updateCompletionRate()
            mUpdateMainUiInOnResume = true
            if (mCanSeeUi) {
                App.setSomethingUpdatedSpecially(false)
            }
            mRemoteIntent = null
        }, 560)
    }

    private fun updateMainUiForUpdateDifferentState(data: Intent) {
        val thing: Thing = data.getParcelableExtra(Def.Communication.KEY_THING)!!
        @Thing.State val stateAfter: Int = data.getIntExtra(
            Def.Communication.KEY_STATE_AFTER, Thing.UNDERWAY
        )
        val position = data.getIntExtra(Def.Communication.KEY_POSITION, 1)
        val changed = data.getBooleanExtra(Def.Communication.KEY_CALL_CHANGE, false)
        val justNotifyAll = App.justNotifyAll()
        Log.i(TAG, "updateMainUiForUpdateDifferentState called, "
            + "stateAfter[" + stateAfter + "], "
            + "position[" + position + "], "
            + "call change[" + changed + "], "
            + "justNotifyAll[" + justNotifyAll + "]")

        if (mStateToUndoFrom != stateAfter) {
            dismissSnackbars()
        }

        celebrateHabitGoalFinish(thing, thing.state, stateAfter)

        mDrawerLayout!!.postDelayed({
            Log.i(TAG, "updateMainUiForUpdateDifferentState: delayed Runnable started.")
            val type = thing.type
            val curLimit = mApp!!.getLimit()
            val limitMatched = Thing.isTypeStateMatchLimit(type, stateAfter, curLimit)
            Log.i(TAG, "type[" + type + "], "
                + "curLimit[" + curLimit + "], "
                + "limitMatched[" + limitMatched + "]")
            if (justNotifyAll || limitMatched) {
                justNotifyAll()
            } else if (Thing.isTypeStateMatchLimit(type, thing.state, curLimit)) {
                mUndoThings!!.add(thing)
                mThingsIdsToUpdateWidget!!.add(thing.id)
                mUndoPositions!!.add(position)
                mUndoLocations!!.add(thing.location)
                mStateToUndoFrom = stateAfter
                if (changed) {
                    mAdapter!!.notifyItemChanged(1)
                    updateUIAfterStateUpdated(
                        stateAfter,
                        mRecyclerView!!.itemAnimator!!.changeDuration, false
                    )
                } else {
                    mAdapter!!.notifyItemRemoved(position)
                    updateUIAfterStateUpdated(
                        stateAfter,
                        mRecyclerView!!.itemAnimator!!.removeDuration, false
                    )
                }
            }

            mUpdateMainUiInOnResume = true
            if (mCanSeeUi) {
                App.setSomethingUpdatedSpecially(false)
            }
            mRemoteIntent = null
        }, 560)
    }

    private fun updateMainUiForStickyOrCancel(data: Intent) {
        val thing: Thing = data.getParcelableExtra(Def.Communication.KEY_THING)!!
        val isStickyBefore = thing.location > 0 // just used for log
        val oldPosition = data.getIntExtra(Def.Communication.KEY_POSITION, -1)
        val newPosition = mThingManager!!.getPosition(thing.id)
        val justNotifyAll = App.justNotifyAll()
        Log.i(TAG, "updateMainUiForStickyOrCancel called, "
            + "isStickyBefore[" + isStickyBefore + "], "
            + "oldPosition[" + oldPosition + "], "
            + "newPosition[" + newPosition + "], "
            + "justNotifyAll[" + justNotifyAll + "]")

        mDrawerLayout!!.postDelayed({
            Log.i(TAG, "updateMainUiForStickyOrCancel: delayed Runnable started.")
            if (justNotifyAll) {
                justNotifyAll()
            } else if (oldPosition != -1 && newPosition != -1) {
                mAdapter!!.notifyItemMoved(oldPosition, newPosition)
                mDrawerLayout!!.postDelayed({
                    mAdapter!!.notifyItemChanged(newPosition)
                }, mRecyclerView!!.itemAnimator!!.moveDuration)
            }

            mDrawerHeader!!.updateCompletionRate()
            mUpdateMainUiInOnResume = true
            if (mCanSeeUi) {
                App.setSomethingUpdatedSpecially(false)
            }
            mRemoteIntent = null
        }, 560)
    }

    private fun updateMainUiForDoingOrCancel(data: Intent) {
        Log.i(TAG, "updateMainUiForDoingOrCancel called")
        val thing: Thing = data.getParcelableExtra(Def.Communication.KEY_THING)!!
        val justNotifyAll = App.justNotifyAll()
        mDrawerLayout!!.postDelayed({
            Log.i(TAG, "updateMainUiForDoingOrCancel: delayed Runnable started.")
            if (justNotifyAll) {
                justNotifyAll()
            } else {
                val position = mThingManager!!.getPosition(thing.id)
                if (position != -1) {
                    mAdapter!!.notifyItemChanged(position)
                }
                mUpdateMainUiInOnResume = true
                mRemoteIntent = null
                if (mCanSeeUi) {
                    App.setSomethingUpdatedSpecially(false)
                }
            }
        }, 560)
    }

    private fun justNotifyAll() {
        if (App.isSearching) {
            mThingManager!!.searchThings(mEtSearch!!.text.toString(), mColorPicker!!.getPickedColor())
            handleSearchResults()
        } else {
            mThingManager!!.loadThings()
        }

        mActivityHeader!!.updateText()
        mDrawerHeader!!.updateCompletionRate()

        if (mModeManager!!.getCurrentMode() == ModeManager.SELECTING) {
            updateSelectingUi(false)
        }

        mAdapter!!.setShouldThingsAnimWhenAppearing(true)
        mAdapter!!.notifyDataSetChanged()

        if (mCanSeeUi) {
            App.setSomethingUpdatedSpecially(false)
            App.setJustNotifyAll(false)
        }

        mUpdateMainUiInOnResume = true
    }

    override fun initMembers() {
        mApp = application as App
        mThingManager = ThingManager.getInstance(mApp)

        mUndoThings              = ArrayList()
        mUndoPositions           = ArrayList()
        mUndoLocations           = ArrayList()
        mUndoHabitRecords        = ArrayList()
        mThingsIdsToUpdateWidget = HashSet()
    }

    override fun findViews() {
        mRevealLayout = f(R.id.reveal_layout)
        mShiningBorder = f(R.id.shining_border)
        mShiningBorder!!.setStrokeWidth(8 * resources.displayMetrics.density)
        mShiningBorder!!.setAnimationDuration(1290)
        mShiningBorderDefaultStroke          = mShiningBorder!!.getStrokeWidth()
        mShiningBorderDefaultCornerRadius    = mShiningBorder!!.getCornerRadius()
        mShiningBorderDefaultDuration        = mShiningBorder!!.getAnimationDuration()
        mShiningBorderDefaultParticleBaseSize = 2.2f * resources.displayMetrics.density
        mViewToReveal = f(R.id.view_to_reveal)
        mTvNoResult   = f(R.id.tv_no_result)
        updateSearchNoResult(0)

        mActionbar = f(R.id.actionbar)
        mViewInsideActionbar = f(R.id.view_inside_actionbar)
        mEtSearch = f(R.id.et_search)
        val contextualToolbar: Toolbar = f(R.id.contextual_toolbar)!!
        contextualToolbar.setTitleTextColor(ContextCompat.getColor(this, R.color.black_54p))
        val rlContextualToolbar: RelativeLayout = f(R.id.rl_contextual_toolbar)!!
        mColorPicker = ColorPicker(this, window.decorView, Def.PickerType.HUE_BUCKET)

        mDrawerLayout = f(R.id.drawer_layout)
        mDrawer       = f(R.id.drawer)
        applyDrawerMenuIconTintForAppearance()

        val dhView: View = mDrawer!!.getHeaderView(0)
        mDrawerHeader = DrawerHeader(
            mApp!!,
            f(dhView, R.id.iv_drawer_header),
            f(dhView, R.id.tv_dh_location),
            f(dhView, R.id.tv_dh_completion_rate)
        )

        mFab = f(R.id.fab_create)

        mRecyclerView  = f(R.id.rv_things)
        val adapter = ThingsAdapter(mApp, OnThingTouchedListener())
        mAdapter = ThingsAdapterWrapper(adapter)

        mActivityHeader = ActivityHeader(
            mApp!!, mRecyclerView!!,
            f(R.id.actionbar_shadow)!!,
            f(R.id.rl_header),
            f(R.id.tv_header_title),
            f(R.id.tv_header_subtitle)
        )

        val fl: FrameLayout = f(R.id.fl_things)!!
        mNormalSnackbar = Snackbar(mApp!!, Snackbar.NORMAL, fl, mFab)
        mUndoSnackbar   = Snackbar(mApp!!, Snackbar.UNDO,   fl, mFab)
        mHabitSnackbar  = Snackbar(mApp!!, Snackbar.UNDO,   fl, mFab)

        mModeManager = ModeManager(
            mApp, mDrawerLayout, mFab, mActivityHeader,
            rlContextualToolbar, contextualToolbar, OnNavigationIconClickedListener(),
            OnContextualMenuClickedListener(), mRecyclerView, adapter
        )
        adapter.setModeManager(mModeManager)
        mActivityHeader!!.setModeManager(mModeManager!!)
    }

    override fun initUI() {
        if (AppearanceUtil.isDarkMode(this)) {
            DisplayUtil.cancelDarkStatusBar(this)
        } else {
            DisplayUtil.darkStatusBar(this)
        }

        DisplayUtil.expandLayoutToStatusBarAboveLollipop(this)

        val statusbar: View = f(R.id.view_status_bar)!!
        val dlp1 = statusbar.layoutParams as DrawerLayout.LayoutParams
        dlp1.height = DisplayUtil.getStatusbarHeight(this)
        statusbar.requestLayout()

        val fl: FrameLayout = f(R.id.fl_things)!!
        val dlp2 = fl.layoutParams as DrawerLayout.LayoutParams
        dlp2.setMargins(0, dlp1.height, 0, 0)
        fl.requestLayout()

        // These two lines can make layout expand into statusbar on Kitkat and will not
        // influence the ui above Lollipop
        mDrawerLayout!!.fitsSystemWindows = false
        mDrawer!!.fitsSystemWindows = false

        mDrawerLayout!!.setScrimColor("#84000000".toColorInt())

        val decor: View = window.decorView
        decor.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    initRecyclerViewUi()
                    mActivityHeader!!.computeFactors(mActionbar)
                    decor.viewTreeObserver.removeOnPreDrawListener(this)
                    return true
                }
            })

        val item: MenuItem = mDrawer!!.menu[mApp!!.getLimit()]
        item.isCheckable = true
        item.isChecked = true
        mPreviousItem = item

        mActivityHeader!!.updateText()
        if (mModeManager!!.getCurrentMode() == ModeManager.SELECTING) {
            mModeManager!!.showContextualToolbar(false)
            mFab!!.shrinkWithoutAnim()
        }

        DisplayUtil.applyBottomInsetAsMargin(mFab)
        DisplayUtil.applyBottomInsetAsScrollPadding(mRecyclerView)
    }

    private fun initRecyclerViewUi() {
        computeSpanCount()

        if (!PermissionUtil.hasStoragePermission(this)
            && PermissionUtil.shouldRequestPermissionWhenLoadingThings(mThingManager!!.getThings())
        ) {
            doWithPermissionChecked(
                object : SimplePermissionCallback(this) {
                    override fun onGranted() {
                        if (mRestoredFromSavedState) {
                            initRecyclerViewRunnable.run()
                        } else {
                            mRecyclerView!!.postDelayed(initRecyclerViewRunnable, 240)
                        }
                    }

                    override fun onDenied() {
                        super.onDenied()
                        finish()
                    }
                },
                Def.Communication.REQUEST_PERMISSION_LOAD_THINGS,
                *PermissionUtil.getRequiredPermissionsForThings(
                    mThingManager!!.getThings()
                )
            )
        } else {
            // post here to make sure that animation plays well and completely
            if (mRestoredFromSavedState) {
                initRecyclerViewRunnable.run()
            } else {
                mRecyclerView!!.postDelayed(initRecyclerViewRunnable, 240)
            }
        }
    }

    /**
     * Focus on change of screen orientation.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        dismissSnackbars()
        mColorPicker!!.dismiss()

        mActivityHeader!!.computeFactors(mActionbar)
        if (!App.isSearching) {
            mActivityHeader!!.reset(false)
        }

        mRecyclerView!!.visibility = View.INVISIBLE
        mAdapter!!.setShouldThingsAnimWhenAppearing(true)
        mRecyclerView!!.visibility = View.VISIBLE
        computeSpanCount()
        if (mStaggeredGridLayoutManager != null) {
            mStaggeredGridLayoutManager!!.spanCount = mSpan
        }
        if (mThingManager!!.getThings()!!.size > 1) {
            mRecyclerView!!.scrollToPosition(0)
        }
        mAdapter!!.notifyDataSetChanged()

        mModeManager!!.updateTitleTextSize()
        if (mModeManager!!.getCurrentMode() != ModeManager.SELECTING && !App.isSearching
            && mApp!!.getLimit() <= Def.LimitForGettingThings.GOAL_UNDERWAY
        ) {
            mFab!!.showFromBottom()
        }
    }

    private fun computeSpanCount() {
        mSpan = if (DisplayUtil.isTablet(this)) 3 else 2
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            if (DisplayUtil.isInMultiWindow(this)) {
                val decor: View = window.decorView
                val display: Point = DisplayUtil.getDisplaySize(this)
                if (decor.width != display.x) {
                    mSpan++
                }
            } else {
                mSpan++
            }
        }
    }

    override fun setActionbar() {
        setSupportActionBar(mActionbar)
        val actionBar: ActionBar? = supportActionBar
        if (actionBar != null) {
            actionBar.title = null
            actionBar.setHomeButtonEnabled(true)
            actionBar.setDisplayHomeAsUpEnabled(true)
        }
    }

    override fun setEvents() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (mDrawerLayout!!.isDrawerOpen(GravityCompat.START)) {
                    mDrawerLayout!!.closeDrawer(GravityCompat.START)
                } else {
                    if (mModeManager!!.getCurrentMode() == ModeManager.SELECTING) {
                        mModeManager!!.backNormalMode(0)
                        return
                    } else if (App.isSearching) {
                        toggleSearching(true)
                        return
                    }
                    if (!FrequentSettings.getBoolean(Def.Meta.KEY_TWICE_BACK)) {
                        mApp!!.setLimit(Def.LimitForGettingThings.ALL_UNDERWAY, true)
                        finish()
                    } else {
                        if (lastClickBack == -1L || System.currentTimeMillis() - lastClickBack > 1600) {
                            lastClickBack = System.currentTimeMillis()
                            Toast.makeText(this@ThingsActivity, R.string.press_again_to_exit, Toast.LENGTH_SHORT).show()
                        } else {
                            lastClickBack = -1
                            mApp!!.setLimit(Def.LimitForGettingThings.ALL_UNDERWAY, true)
                            finish()
                        }
                    }
                }
            }
        })

        mActionbar!!.setOnClickListener {
            mScrollCausedByFinger = true
            mRecyclerView!!.smoothScrollToPosition(0)
        }

        mDrawer!!.getHeaderView(0).setOnClickListener {
            val intent = Intent(this@ThingsActivity, StatisticActivity::class.java)
            startActivity(intent)
            mShouldCloseDrawer = true
        }

        setFabEvents()
        setRecyclerViewEvents()
        setUndoSnackbarEvents()
        setSearchEvents()
    }

    private fun setFabEvents() {
        mFab!!.attachToRecyclerView(mRecyclerView!!)
        mFab!!.bindSnackbars(mNormalSnackbar, mUndoSnackbar, mHabitSnackbar)
        mFab!!.setOnClickListener {
            if (mIsRevealAnimPlaying) {
                return@setOnClickListener
            }
            mIsRevealAnimPlaying = true

            dismissSnackbars()
            mFab!!.isClickable = false

            val intent: Intent = DetailActivity.getOpenIntentForCreate(
                this@ThingsActivity, TAG,
                if (App.newThingBackground != null)
                    App.newThingBackground
                else ThingBackground.pure(App.newThingColor)
            )

            val useShiningBorder = getSharedPreferences(
                Def.Meta.PREFERENCES_NAME, MODE_PRIVATE
            ).getBoolean(Def.Meta.KEY_CREATE_ANIMATION_STYLE, false)

            if (useShiningBorder) {
                var bg: ThingBackground? = App.newThingBackground
                if (bg == null) bg = ThingBackground.pure(App.newThingColor)
                val shiningCol: Int
                val ordinaryCol: Int
                if (bg.mode === ThingBackground.Mode.PURE) {
                    shiningCol  = bg.color
                    ordinaryCol = DisplayUtil.getLightColor(bg.color, this@ThingsActivity)
                } else {
                    shiningCol  = bg.endColor
                    ordinaryCol = bg.color
                }
                mShiningBorder!!.setShiningColor(shiningCol)
                mShiningBorder!!.setOrdinaryColor(ordinaryCol)
                mShiningBorder!!.visibility = View.VISIBLE
                mShiningBorder!!.startAnimation()

                mShiningBorder!!.setOnAnimationEndListener(object : ShiningBorder.OnAnimationEndListener {
                    override fun onAnimationEnd(border: ShiningBorder) {
                        startActivityForResult(
                            intent, Def.Communication.REQUEST_ACTIVITY_DETAIL
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
                        } else {
                            overridePendingTransition(0, 0)
                        }
                    }
                })

                val delay = 1200 + (if (mApp!!.hasDetailActivityRun()) 360 else 1000)
                mRevealLayout!!.postDelayed({
                    mRecyclerView!!.scrollToPosition(0)
                    mActivityHeader!!.reset(false)
                    mIsRevealAnimPlaying = false
                    mFab!!.showFromBottom()
                    mFab!!.isClickable = true
                    mShiningBorder!!.visibility = View.INVISIBLE
                    mShiningBorder!!.resetTrace()
                }, delay.toLong())
            } else {
                val location = IntArray(2)
                mFab!!.getLocationInWindow(location)
                location[0] += mFab!!.width / 2
                location[1] += mFab!!.height / 2
                BackgroundUtil.applyBackground(mViewToReveal, App.newThingBackground)
                mViewToReveal!!.visibility = View.VISIBLE
                mRevealLayout!!.visibility = View.VISIBLE

                mRevealLayout!!.show(location[0], location[1])

                mRevealLayout!!.postDelayed({
                    startActivityForResult(
                        intent, Def.Communication.REQUEST_ACTIVITY_DETAIL
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
                    } else {
                        overridePendingTransition(0, 0)
                    }
                }, 600)

                val delay = if (mApp!!.hasDetailActivityRun()) 960 else 1600
                mRevealLayout!!.postDelayed({
                    mRecyclerView!!.scrollToPosition(0)
                    mActivityHeader!!.reset(false)
                    mIsRevealAnimPlaying = false
                    mFab!!.showFromBottom()
                    mFab!!.isClickable = true
                    mRevealLayout!!.visibility = View.INVISIBLE
                    mViewToReveal!!.visibility = View.INVISIBLE
                }, delay.toLong())
            }
        }
    }

    /**
     * Plays a per-item entry animation on the freshly-bound card of a newly created thing.
     */
    private fun playNewItemAnimation(
        holder: BaseThingsAdapter.BaseThingViewHolder, bg: ThingBackground
    ) {
        val useShining = getSharedPreferences(Def.Meta.PREFERENCES_NAME, MODE_PRIVATE)
            .getBoolean(Def.Meta.KEY_CREATE_ANIMATION_STYLE, false)
        holder.cv!!.clearAnimation()
        holder.cv.visibility = View.INVISIBLE
        mIsRevealAnimPlaying = true
        holder.cv.postDelayed({
            if (holder.cv.windowToken == null) {
                mIsRevealAnimPlaying = false
                return@postDelayed
            }
            holder.cv.clearAnimation()
            holder.cv.visibility = View.INVISIBLE
            if (useShining) {
                playNewItemShiningBorder(holder, bg)
            } else {
                playNewItemReveal(holder)
            }
        }, 180)
    }

    private fun playNewItemShiningBorder(
        holder: BaseThingsAdapter.BaseThingViewHolder, bg: ThingBackground
    ) {
        val cardLoc = IntArray(2)
        holder.cv!!.getLocationInWindow(cardLoc)
        val borderLoc = IntArray(2)
        mShiningBorder!!.getLocationInWindow(borderLoc)
        val left   = cardLoc[0] - borderLoc[0]
        val top    = cardLoc[1] - borderLoc[1]
        val right  = left + holder.cv.width
        val bottom = top  + holder.cv.height

        val density = DisplayUtil.getScreenDensity(this)

        val shiningCol: Int
        val ordinaryCol: Int
        if (bg.mode === ThingBackground.Mode.PURE) {
            shiningCol  = bg.color
            ordinaryCol = DisplayUtil.getLightColor(bg.color, this)
        } else {
            shiningCol  = bg.endColor
            ordinaryCol = bg.color
        }

        // ---- Card-scoped overrides ----
        mShiningBorder!!.setStrokeWidth(density * 1.5f)
        mShiningBorder!!.setCornerRadius(resources.getDimension(R.dimen.thing_card_corner_radius))
        mShiningBorder!!.setShiningColor(shiningCol)
        mShiningBorder!!.setOrdinaryColor(ordinaryCol)
        mShiningBorder!!.setRemainOrdinaryPath(false)
        mShiningBorder!!.setRepeatAnimation(false)
        mShiningBorder!!.setAnimationDuration(1600)
        mShiningBorder!!.setParticleBaseSize(density * 0.6f)
        mShiningBorder!!.setMaxParticles(80)
        mShiningBorder!!.assignPathAndFrame(left, top, right, bottom)

        // Card stays INVISIBLE for the whole trace; only at the end do we reveal it.
        mShiningBorder!!.setOnProgressUpdateListener(null)
        mShiningBorder!!.setOnAnimationEndListener(object : ShiningBorder.OnAnimationEndListener {
            override fun onAnimationEnd(border: ShiningBorder) {
                holder.cv.alpha = 0f
                holder.cv.visibility = View.VISIBLE
                holder.cv.animate().alpha(1f).setDuration(220).start()
                mShiningBorder!!.visibility = View.INVISIBLE
                mShiningBorder!!.resetTrace()
                mShiningBorder!!.setOnAnimationEndListener(null)
                restoreShiningBorderDefaults()
                mIsRevealAnimPlaying = false
            }
        })
        mShiningBorder!!.visibility = View.VISIBLE
        mShiningBorder!!.startAnimation()
    }

    private fun restoreShiningBorderDefaults() {
        mShiningBorder!!.setStrokeWidth(mShiningBorderDefaultStroke)
        mShiningBorder!!.setCornerRadius(mShiningBorderDefaultCornerRadius)
        mShiningBorder!!.setAnimationDuration(mShiningBorderDefaultDuration)
        mShiningBorder!!.setParticleBaseSize(mShiningBorderDefaultParticleBaseSize)
        mShiningBorder!!.setMaxParticles(mShiningBorderDefaultMaxParticles)
        mShiningBorder!!.assignPathAndFrame()
    }

    private fun playNewItemReveal(holder: BaseThingsAdapter.BaseThingViewHolder) {
        val card: View = holder.cv!!
        val w = card.width
        val h = card.height
        if (w == 0 || h == 0) {
            card.visibility = View.VISIBLE
            return
        }
        val cx = w
        val cy = h
        val finalRadius = hypot(w.toDouble(), h.toDouble()).toFloat()
        val reveal: Animator = ViewAnimationUtils.createCircularReveal(card, cx, cy, 0f, finalRadius)
        reveal.duration = 540
        reveal.interpolator = AccelerateDecelerateInterpolator()
        reveal.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(a: Animator) {
                card.alpha = 1f
                card.visibility = View.VISIBLE
            }
            override fun onAnimationCancel(a: Animator) {
                card.alpha = 1f
                card.visibility = View.VISIBLE
                mIsRevealAnimPlaying = false
            }
            override fun onAnimationEnd(a: Animator) {
                mIsRevealAnimPlaying = false
            }
        })
        reveal.start()
    }

    private fun setRecyclerViewEvents() {
        mRecyclerView!!.setOnTouchListener { _, event ->
            val action = event.action
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_MOVE
            ) {
                if (!mScrollCausedByFinger) {
                    mScrollCausedByFinger = true
                }
                if (mAdapter!!.shouldThingsAnimWhenAppearing()) {
                    mAdapter!!.setShouldThingsAnimWhenAppearing(false)
                }
            }
            false
        }
        mRecyclerView!!.addOnScrollListener(object : RecyclerView.OnScrollListener() {

            val edgeColor: Int = EdgeEffectUtil.getEdgeColorDark()

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (mScrollCausedByFinger) {
                    dismissSnackbars()
                    val positions = IntArray(mSpan)
                    mStaggeredGridLayoutManager!!.findFirstVisibleItemPositions(positions)
                    mActivityHeader!!.updateAll(positions[0], false)
                }
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                EdgeEffectUtil.forRecyclerView(recyclerView, edgeColor)
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    Glide.with(mApp!!).resumeRequests()
                } else { // dragging or settling
                    Glide.with(mApp!!).pauseRequests()
                }
            }
        })

        mThingsTouchHelper = ItemTouchHelper(ThingsTouchCallback())
        mThingsTouchHelper!!.attachToRecyclerView(mRecyclerView)
    }

    private fun setUndoSnackbarEvents() {
        mUndoSnackbar!!.setUndoListener(View.OnClickListener {
            if (mUndoThings!!.isEmpty()) { // occurs when click button very quickly
                return@OnClickListener
            }

            val stateAfter = mUndoThings!![0].state
            mScrollCausedByFinger = false

            if (mUndoAll) {
                mThingManager!!.undoUpdateStates(
                    mUndoThings as List<Thing?>,
                    mUndoPositions as List<Int?>,
                    mUndoLocations as List<Long?>,
                    mStateToUndoFrom, stateAfter
                )
                mUndoThings!!.clear()
                mUndoPositions!!.clear()
                mUndoLocations!!.clear()
                if (mStateToUndoFrom == Thing.DELETED_FOREVER) {
                    mApp!!.getThingsToDeleteForever()!!.clear()
                }

                mAdapter!!.notifyDataSetChanged()
                mUndoAll = false
                updateUIAfterStateUpdated(
                    stateAfter,
                    mRecyclerView!!.itemAnimator!!.changeDuration, true
                )
            } else if (!mUndoThings!!.isEmpty()) {
                val size = mUndoThings!!.size
                val thing = mUndoThings!![size - 1]
                val position = mUndoPositions!![size - 1]
                val location = mUndoLocations!![size - 1]
                mUndoThings!!.removeAt(size - 1)
                mUndoPositions!!.removeAt(size - 1)
                mUndoLocations!!.removeAt(size - 1)

                if (mStateToUndoFrom == Thing.DELETED_FOREVER) {
                    mApp!!.getThingsToDeleteForever()!!.remove(thing)
                }

                val change = mThingManager!!.updateState(
                    thing, position, location, mStateToUndoFrom, stateAfter, true, true
                )

                if (change) {
                    mAdapter!!.notifyItemChanged(1)
                    updateUIAfterStateUpdated(
                        stateAfter,
                        mRecyclerView!!.itemAnimator!!.changeDuration, true
                    )
                } else {
                    mAdapter!!.notifyItemInserted(position)
                    mRecyclerView!!.smoothScrollToPosition(position)
                    updateUIAfterStateUpdated(
                        stateAfter,
                        mRecyclerView!!.itemAnimator!!.addDuration, true
                    )
                }
            }
            if (App.isSearching) {
                handleSearchResults()
            }
            if (mUndoThings!!.isEmpty()) {
                dismissSnackbars()
            }
        })
        mHabitSnackbar!!.setUndoListener {
            if (!mUndoHabitRecords!!.isEmpty()) {
                var size = mUndoHabitRecords!!.size
                val hr: HabitRecord = mUndoHabitRecords!![size - 1]
                val position = mUndoPositions!![size - 1]
                mThingsIdsToUpdateWidget!!.remove(hr.habitId)
                mUndoHabitRecords!!.removeAt(size - 1)
                mUndoPositions!!.removeAt(size - 1)

                HabitDAO.getInstance(mApp)!!.undoFinishOneTime(hr)

                if (!mUndoThings!!.isEmpty()) {
                    size = mUndoThings!!.size
                    val thing = mUndoThings!![size - 1]
                    mUndoThings!!.removeAt(size - 1)
                    mThingsIdsToUpdateWidget!!.remove(thing.id)
                    mThingManager!!.update(thing.type, thing, position, false)
                    mThingManager!!.getThings()!![position] = thing
                }

                mAdapter!!.notifyItemChanged(position)
                mDrawerHeader!!.updateCompletionRate()

                if (mUndoHabitRecords!!.isEmpty()) {
                    mHabitSnackbar!!.dismiss()
                    for (id in mThingsIdsToUpdateWidget!!) {
                        AppWidgetHelper.updateSingleThingAppWidgets(this@ThingsActivity, id)
                    }
                    AppWidgetHelper.updateThingsListAppWidgetsForType(mApp, Thing.HABIT)
                }
            }
        }
    }

    private fun setSearchEvents() {
        mEtSearch!!.onFocusChangeListener = View.OnFocusChangeListener { _, _ ->
            dismissSnackbars()
        }

        mEtSearch!!.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                beginSearchThings()
            }
        })

        mEtSearch!!.setOnEditorActionListener(TextView.OnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                KeyboardUtil.hideKeyboard(mEtSearch)
                beginSearchThings()
                return@OnEditorActionListener true
            }
            false
        })

        KeyboardUtil.addKeyboardCallback(window, object : KeyboardUtil.KeyboardCallback {
            override fun onKeyboardShow(keyboardHeight: Int) {
                updateSearchNoResult(keyboardHeight)
            }

            override fun onKeyboardHide() {
                updateSearchNoResult(0)
            }
        })

        mColorPicker!!.setPickedListener {
            mRecyclerView!!.overScrollMode = View.OVER_SCROLL_ALWAYS
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            KeyboardUtil.hideKeyboard(mEtSearch)
            searchThings()
        }
    }

    private fun beginSearchThings() {
        if (mColorPicker!!.getPickedIndex() < 0) {
            if (mDontPickSearchColor) {
                mDontPickSearchColor = false
            } else {
                mColorPicker!!.pickForUI(0)
            }
        }
        mRecyclerView!!.overScrollMode = View.OVER_SCROLL_ALWAYS
        searchThings()
    }

    private fun updateSearchNoResult(keyboardHeight: Int) {
        mRevealLayout!!.setPadding(0, 0, 0, keyboardHeight)
        mTvNoResult!!.setTextColor(ContextCompat.getColor(this, R.color.app_chrome_on_surface_hint))
        if (keyboardHeight != 0) {
            mTvNoResult!!.text = getString(R.string.no_result) + "..."
            setSearchNoResultImage(false)
        } else {
            mTvNoResult!!.setText(R.string.no_result)
            setSearchNoResultImage(true)
        }
    }

    private fun setSearchNoResultImage(show: Boolean) {
        if (!show) {
            mTvNoResult!!.setCompoundDrawablesRelativeWithIntrinsicBounds(
                null, null, null, null
            )
            return
        }
        val raw: Drawable? = ContextCompat.getDrawable(this, R.drawable.img_no_result)
        val image: Drawable? = if (AppearanceUtil.isDarkMode(this)) {
            DisplayUtil.opaqueTintDrawable(
                this,
                raw,
                ContextCompat.getColor(this, R.color.app_chrome_on_surface_hint)
            )
        } else {
            raw
        }
        mTvNoResult!!.setCompoundDrawablesRelativeWithIntrinsicBounds(
            null, image, null, null
        )
    }

    private fun hideSearchNoResult() {
        mTvNoResult!!.animate().cancel()
        mTvNoResult!!.alpha = 0f
        mTvNoResult!!.visibility = View.INVISIBLE
        mRevealLayout!!.visibility = View.INVISIBLE
        mRevealLayout!!.setPadding(0, 0, 0, 0)
    }

    private fun searchThings() {
        mThingManager!!.searchThings(mEtSearch!!.text.toString(), mColorPicker!!.getPickedColor())
        mAdapter!!.setShouldThingsAnimWhenAppearing(false)
        mAdapter!!.notifyDataSetChanged()
        handleSearchResults()
    }

    private fun setDrawer() {
        mDrawerHeader!!.updateTexts()
        val toggle: ActionBarDrawerToggle = object : ActionBarDrawerToggle(
            this, mDrawerLayout,
            R.string.cd_open_drawer, R.string.cd_close_drawer
        ) {
            override fun onDrawerOpened(drawerView: View) {
                super.onDrawerOpened(drawerView)
                dismissSnackbars()
            }
        }
        mDrawerLayout!!.addDrawerListener(toggle)
        toggle.syncState()
        applyHomeNavigationIconTintForAppearance()

        mActionbar!!.setNavigationOnClickListener(OnNavigationIconClickedListener())
        mDrawer!!.setNavigationItemSelectedListener(NavigationView.OnNavigationItemSelectedListener { menuItem ->
            if (mPreviousItem!! != menuItem) {
                val newLimit: Int = when (menuItem.itemId) {
                    R.id.drawer_underway -> Def.LimitForGettingThings.ALL_UNDERWAY
                    R.id.drawer_note -> Def.LimitForGettingThings.NOTE_UNDERWAY
                    R.id.drawer_reminder -> Def.LimitForGettingThings.REMINDER_UNDERWAY
                    R.id.drawer_habit -> Def.LimitForGettingThings.HABIT_UNDERWAY
                    R.id.drawer_goal -> Def.LimitForGettingThings.GOAL_UNDERWAY
                    R.id.drawer_finished -> Def.LimitForGettingThings.ALL_FINISHED
                    R.id.drawer_deleted -> Def.LimitForGettingThings.ALL_DELETED
                    R.id.drawer_settings -> {
                        val intent = Intent(this@ThingsActivity, SettingsActivity::class.java)
                        startActivityForResult(intent, Def.Communication.REQUEST_ACTIVITY_SETTINGS)
                        mShouldCloseDrawer = true
                        return@OnNavigationItemSelectedListener true
                    }
                    R.id.drawer_help -> {
                        startActivity(Intent(this@ThingsActivity, HelpActivity::class.java))
                        mShouldCloseDrawer = true
                        return@OnNavigationItemSelectedListener true
                    }
                    R.id.drawer_about -> {
                        startActivity(Intent(this@ThingsActivity, AboutActivity::class.java))
                        mShouldCloseDrawer = true
                        return@OnNavigationItemSelectedListener true
                    }
                    else -> return@OnNavigationItemSelectedListener true
                }

                mDrawerLayout!!.closeDrawer(GravityCompat.START)
                checkDrawerItem(menuItem)

                changeToLimit(newLimit, false)
            }
            true
        })
    }

    private fun applyHomeNavigationIconTintForAppearance() {
        val icon = mActionbar!!.navigationIcon ?: return
        val tint = ContextCompat.getColor(
            this,
            if (AppearanceUtil.isDarkMode(this)) {
                R.color.app_accent
            } else {
                R.color.app_chrome_on_surface_secondary
            }
        )
        if (icon is DrawerArrowDrawable) {
            icon.color = tint
        } else {
            mActionbar!!.navigationIcon = DisplayUtil.opaqueTintDrawable(this, icon, tint)
        }
    }

    private fun changeToLimit(newLimit: Int, updateDrawerItem: Boolean) {
        if (updateDrawerItem) {
            val menuItem: MenuItem = mDrawer!!.menu[newLimit]
            checkDrawerItem(menuItem)
        }

        mRecyclerView!!.visibility = View.INVISIBLE
        mApp!!.setLimit(newLimit, false)
        invalidateOptionsMenu()
        mRecyclerView!!.scrollToPosition(0)
        mActivityHeader!!.reset(true)

        mRecyclerView!!.postDelayed({
            mRecyclerView!!.visibility = View.VISIBLE
            mAdapter!!.setShouldThingsAnimWhenAppearing(true)
            mThingManager!!.loadThings()
            mAdapter!!.notifyDataSetChanged()
        }, 360)

        mActivityHeader!!.updateText()
        mDrawerHeader!!.updateTexts()
        if (newLimit <= Def.LimitForGettingThings.GOAL_UNDERWAY) {
            mFab!!.spread()
        } else {
            mFab!!.shrink()
        }
    }

    private fun checkDrawerItem(menuItem: MenuItem) {
        menuItem.isCheckable = true
        menuItem.isChecked = true
        mPreviousItem!!.isChecked = false
        mPreviousItem = menuItem
    }

    private fun handleUpdateStates(stateAfter: Int) {
        if (mThingManager!!.isThingsEmpty()) {
            return
        }
        dismissSnackbars()

        val things: MutableList<Thing?> = mThingManager!!.getThings()!!
        var thingsToDeleteForever: MutableList<Thing?> = ArrayList<Thing?>()
        if (stateAfter == Thing.DELETED_FOREVER) {
            thingsToDeleteForever = mApp!!.getThingsToDeleteForever()!!
        }
        var containsHabitOrGoal = false
        var thing: Thing
        val size = things.size
        if (mModeManager!!.getCurrentMode() == ModeManager.SELECTING) {
            for (i in 1 until size) {
                thing = things[i]!!
                if (thing.isSelected()) {
                    val type = thing.type
                    if (Thing.isImportantType(type)) {
                        containsHabitOrGoal = true
                    }
                    SystemNotificationUtil.cancelNotification(thing.id, type, mApp)
                    mUndoThings!!.add(thing)
                    mThingsIdsToUpdateWidget!!.add(thing.id)
                    mUndoLocations!!.add(thing.location)
                    if (stateAfter == Thing.DELETED_FOREVER) {
                        thingsToDeleteForever.add(Thing(thing))
                    }
                }
            }
        } else {
            for (i in 1 until size) {
                thing = things[i]!!
                val type = thing.type
                if (Thing.isImportantType(type)) {
                    containsHabitOrGoal = true
                }
                val thingId = thing.id
                if (App.getDoingThingId() != thingId) {
                    SystemNotificationUtil.cancelNotification(thingId, type, mApp)
                    mUndoThings!!.add(thing)
                    mThingsIdsToUpdateWidget!!.add(thing.id)
                    mUndoLocations!!.add(thing.location)
                    if (stateAfter == Thing.DELETED_FOREVER) {
                        thingsToDeleteForever.add(Thing(thing))
                    }
                }
            }
        }

        val stateBefore = things[1]!!.state
        if (containsHabitOrGoal && stateBefore == Thing.UNDERWAY && mUndoThings!!.size > 1) {
            // if mUntoThing.size == 1, it means that it is user's decision
            alertForHabitGoal(stateBefore, stateAfter)
        } else {
            for (undoThing in mUndoThings!!) {
                undoThing.selected = false
            }
            for (thingToDelete in thingsToDeleteForever) {
                thingToDelete!!.selected = false
            }
            handleUpdateStates(stateBefore, stateAfter)
        }
    }

    private fun alertForHabitGoal(stateBefore: Int, stateAfter: Int) {
        val df = ThreeActionsAlertDialogFragment()
        val color = DisplayUtil.getRandomColor(this)
        df.setTitleColor(color)
        df.setContinueColor(color)
        df.setTitle(getString(R.string.alert_continue))
        df.setContent(getString(R.string.alert_find_habit_goal))
        df.setFirstAction(getString(R.string.continue_get_rid_of_habit_goal))
        df.setSecondAction(getString(R.string.continue_for_alert))
        df.setOnClickListener(object : ThreeActionsAlertDialogFragment.OnClickListener {
            override fun onFirstClicked() {
                val thingsToDelete: MutableList<Thing?> = mApp!!.getThingsToDeleteForever()!!
                val iterator: MutableIterator<Thing> = mUndoThings!!.iterator()
                while (iterator.hasNext()) {
                    val t: Thing = iterator.next()
                    t.selected = false
                    if (Thing.isImportantType(t.type)) {
                        iterator.remove()
                        mThingsIdsToUpdateWidget!!.remove(t.id)
                        mUndoLocations!!.remove(t.location)
                        thingsToDelete.remove(t)
                    }
                }
                handleUpdateStates(stateBefore, stateAfter)
            }

            override fun onSecondClicked() {
                for (undoThing in mUndoThings!!) {
                    undoThing.selected = false
                }
                handleUpdateStates(stateBefore, stateAfter)
            }

            override fun onThirdClicked() {
                val thingsToDelete: MutableList<Thing?> = mApp!!.getThingsToDeleteForever()!!
                for (undoThing in mUndoThings!!) {
                    thingsToDelete.remove(undoThing)
                }
                mUndoThings!!.clear()
                mThingsIdsToUpdateWidget!!.clear()
                mUndoLocations!!.clear()
            }
        })
        df.show(fragmentManager, ThreeActionsAlertDialogFragment.TAG)
    }

    private fun handleUpdateStates(stateBefore: Int, stateAfter: Int) {
        if (mUndoThings!!.isEmpty()) {
            return
        }
        mStateToUndoFrom = stateAfter
        @Suppress("UNCHECKED_CAST")
        mUndoPositions = mThingManager!!.updateStates(
            mUndoThings as List<Thing?>, stateBefore, stateAfter
        )!! as MutableList<Int>
        mAdapter!!.notifyDataSetChanged()
        mUndoAll = true
        if (!mUndoThings!!.isEmpty()) {
            updateUIAfterStateUpdated(
                stateAfter,
                mRecyclerView!!.itemAnimator!!.removeDuration, true
            )
        }
        if (App.isSearching) {
            handleSearchResults()
        }
    }

    private fun updateUIAfterStateUpdated(stateAfter: Int, timeDelay: Long, shouldForceBackNormalMode: Boolean) {
        mActivityHeader!!.updateText()
        mDrawerHeader!!.updateCompletionRate()

        mScrollCausedByFinger = false
        mRecyclerView!!.postDelayed({
            val positions = IntArray(mSpan)
            mStaggeredGridLayoutManager!!.findFirstVisibleItemPositions(positions)
            mActivityHeader!!.updateAll(positions[0], true)
        }, timeDelay)

        if (mModeManager!!.getCurrentMode() == ModeManager.SELECTING) {
            updateSelectingUi(shouldForceBackNormalMode)
            if (shouldForceBackNormalMode) {
                showUndoSnackbar(stateAfter)
            }
        } else {
            showUndoSnackbar(stateAfter)
        }
    }

    private fun updateSelectingUi(shouldForceToBackNormalMode: Boolean) {
        if (shouldForceToBackNormalMode) {
            mModeManager!!.backNormalMode(0)
        } else {
            val things: List<Thing?> = mThingManager!!.getThings()!!
            if (things.size == 1) {
                if (App.isSearching) {
                    mModeManager!!.backNormalMode(0)
                }
            } else {
                if (things[1]!!.type >= Thing.NOTIFICATION_UNDERWAY) {
                    mModeManager!!.backNormalMode(0)
                }
                mModeManager!!.updateSelectedCount()
            }
        }
    }

    private fun showUndoSnackbar(stateAfter: Int) {
        val messages: Array<String?> = getUndoMessages(stateAfter, mUndoThings!!.size)
        mUndoSnackbar!!.setMessage(messages[0]!!)
        mUndoSnackbar!!.setUndoText(messages[1]!!)
        mUndoSnackbar!!.show()
    }

    private fun showHabitSnackbar() {
        val finished = getString(R.string.sb_finish_habit)
        mHabitSnackbar!!.setMessage(
            finished + " " + LocaleUtil.getTimesStr(mApp, mUndoHabitRecords!!.size)
        )
        mHabitSnackbar!!.setUndoText(R.string.act_sb_undo_finish)
        mHabitSnackbar!!.show()
    }

    private fun getUndoMessages(stateAfter: Int, count: Int): Array<String?> {
        val sb = StringBuilder()
        var updateStr = ""
        var undoStr = ""
        val limit = mApp!!.getLimit()
        when (stateAfter) {
            Thing.UNDERWAY -> {
                updateStr = getString(R.string.sb_underway)
                if (limit <= Def.LimitForGettingThings.GOAL_UNDERWAY) {
                    updateStr = getString(R.string.sb_finish)
                    undoStr = getString(R.string.act_sb_undo_finish)
                } else if (limit == Def.LimitForGettingThings.ALL_FINISHED) {
                    undoStr = getString(R.string.act_sb_undo_underway)
                } else {
                    undoStr = getString(R.string.act_sb_undo)
                }
            }
            Thing.FINISHED -> {
                updateStr = getString(R.string.sb_finish)
                undoStr = if (limit <= Def.LimitForGettingThings.GOAL_UNDERWAY) {
                    getString(R.string.act_sb_undo_finish)
                } else {
                    getString(R.string.act_sb_undo)
                }
            }
            Thing.DELETED -> {
                updateStr = getString(R.string.sb_delete)
                undoStr = getString(R.string.act_sb_undo)
            }
            Thing.DELETED_FOREVER -> {
                updateStr = getString(R.string.sb_delete_forever)
                undoStr = getString(R.string.act_sb_undo)
            }
            else -> {}
        }
        if (LocaleUtil.isChinese(mApp)) {
            sb.append(updateStr).append(" ").append(count)
                .append(" ").append(getString(R.string.a_thing))
        } else {
            var str: String = mApp!!.getString(R.string.a_thing)
            if (count > 1) {
                str += "s"
            }
            sb.append(count).append(" ").append(str).append(" ").append(updateStr)
        }
        return arrayOf(sb.toString(), undoStr)
    }

    private fun dismissSnackbars() {
        if (mNormalSnackbar!!.isShowing()) {
            mNormalSnackbar!!.dismiss()
        }
        if (mUndoSnackbar!!.isShowing()) {
            mUndoSnackbar!!.dismiss()
        }
        if (mHabitSnackbar!!.isShowing()) {
            mHabitSnackbar!!.dismiss()
        }

        for (id in mThingsIdsToUpdateWidget!!) {
            AppWidgetHelper.updateSingleThingAppWidgets(this, id)
        }
        if (!mThingsIdsToUpdateWidget!!.isEmpty()) {
            AppWidgetHelper.updateAllThingsListAppWidgets(this)
        }

        mUndoThings!!.clear()
        mThingsIdsToUpdateWidget!!.clear()
        mUndoPositions!!.clear()
        mUndoLocations!!.clear()
        mUndoHabitRecords!!.clear()
        mUndoAll = false
        mThingManager!!.clearLists()

        mApp!!.releaseResourcesAfterDeleteForever()
    }

    private fun toggleSearching(shouldThingsAnimWhenAppearing: Boolean) {
        dismissSnackbars()
        val toNormal = App.isSearching
        if (toNormal) {
            mActionbar!!.setNavigationContentDescription(R.string.cd_open_drawer)
            mDrawerLayout!!.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
            mViewInsideActionbar!!.visibility = View.VISIBLE
            mColorPicker!!.pickForUI(-1)

            mEtSearch!!.isEnabled = false
            mEtSearch!!.animate().alpha(0f).setDuration(160)

            mRecyclerView!!.overScrollMode = View.OVER_SCROLL_ALWAYS
            mRecyclerView!!.scrollToPosition(0)

            mActivityHeader!!.reset(false)
            mRecyclerView!!.postDelayed({
                mActivityHeader!!.setShouldListenToScroll(true)
            }, 160)
            if (mApp!!.getLimit() <= Def.LimitForGettingThings.NOTE_UNDERWAY) {
                mFab!!.spread()
            }
            mApp!!.setLimit(mApp!!.getLimit(), true)
            mActivityHeader!!.updateText()
            mDrawerHeader!!.updateCompletionRate()
            mAdapter!!.setShouldThingsAnimWhenAppearing(shouldThingsAnimWhenAppearing)
            hideSearchNoResult()
            DisplayUtil.playDrawerToggleAnim(mActionbar!!.navigationIcon as DrawerArrowDrawable)
        } else {
            mActionbar!!.setNavigationContentDescription(R.string.cd_quit_searching)
            mDrawerLayout!!.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
            mViewInsideActionbar!!.visibility = View.GONE
            mEtSearch!!.isEnabled = true
            mEtSearch!!.setText("")
            KeyboardUtil.showKeyboard(mEtSearch)
            mEtSearch!!.animate().alpha(1.0f).setDuration(160)

            mRecyclerView!!.overScrollMode = View.OVER_SCROLL_NEVER
            mRecyclerView!!.scrollBy(0, Int.MIN_VALUE)

            mActivityHeader!!.setShouldListenToScroll(false)
            mActivityHeader!!.hideTitles()
            mActivityHeader!!.showActionbarShadow(1.0f)
            mFab!!.shrink()

            mThingManager!!.getThings()!!.clear()

            DisplayUtil.playDrawerToggleAnim(mActionbar!!.navigationIcon as DrawerArrowDrawable)
        }
        mAdapter!!.notifyDataSetChanged()
        App.isSearching = !toNormal
        applyHomeNavigationIconTintForAppearance()
        invalidateOptionsMenu()
        mDontPickSearchColor = true
    }

    private fun handleSearchResults() {
        if (!App.isSearching) {
            hideSearchNoResult()
            return
        }
        if (mThingManager!!.getThings()!!.size == 1) {
            mTvNoResult!!.visibility = View.VISIBLE
            mRevealLayout!!.visibility = View.VISIBLE
            mTvNoResult!!.animate().alpha(1f).setDuration(360)
        } else {
            mTvNoResult!!.animate().alpha(0f).setDuration(160)
            mRevealLayout!!.postDelayed({
                if (App.isSearching && mThingManager!!.getThings()!!.size == 1) {
                    return@postDelayed
                }
                mRevealLayout!!.visibility = View.INVISIBLE
                mTvNoResult!!.visibility = View.INVISIBLE
            }, 160)
        }
    }

    private fun celebrateHabitGoalFinish(thing: Thing, stateBefore: Int, stateAfter: Int) {
        if (stateBefore != Thing.UNDERWAY || stateAfter != Thing.FINISHED) {
            return
        }
        val type = thing.type
        if (type == Thing.HABIT || type == Thing.GOAL) {
            val id = thing.id
            val adf = AlertDialogFragment()
            adf.setShowCancel(false)
            adf.setTitle(getString(R.string.congratulations))
            adf.setTitleBackground(thing.getBackground())
            adf.setConfirmBackground(thing.getBackground())
            val content: String?
            if (type == Thing.HABIT) {
                val habit: Habit = HabitDAO.getInstance(mApp)!!.getHabitById(id)!!
                content = habit.getCelebrationText(mApp)
            } else {
                val reminder: Reminder = ReminderDAO.getInstance(mApp)!!.getReminderById(id)!!
                content = reminder.getCelebrationText(mApp)
            }
            adf.setContent(content)
            adf.show(fragmentManager, AlertDialogFragment.TAG)
        }
    }

    internal inner class OnNavigationIconClickedListener : View.OnClickListener {

        override fun onClick(v: View) {
            if (App.isSearching) {
                toggleSearching(true)
            } else {
                mDrawerLayout!!.openDrawer(GravityCompat.START)
                dismissSnackbars()
            }
        }
    }

    internal inner class OnThingTouchedListener : ThingsAdapter.OnItemTouchedListener {
        override fun onItemTouch(v: View?, event: MotionEvent?): Boolean {
            val action = event!!.action
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_MOVE
            ) {
                if (!mScrollCausedByFinger) {
                    mScrollCausedByFinger = true
                }
                if (mAdapter!!.shouldThingsAnimWhenAppearing()) {
                    mAdapter!!.setShouldThingsAnimWhenAppearing(false)
                }
            }
            return false
        }

        override fun onItemClick(v: View?, position: Int) {
            if (position <= 0 || mIsRevealAnimPlaying) {
                return
            }
            dismissSnackbars()
            KeyboardUtil.hideKeyboard(currentFocus)

            val things: List<Thing?> = mThingManager!!.getThings()!!
            if (position >= things.size) {
                return
            }

            if (mModeManager!!.getCurrentMode() != ModeManager.SELECTING) {
                if (mRecyclerView!!.itemAnimator!!.isRunning) {
                    return
                }

                val thing: Thing = things[position]!!
                if (thing.isPrivate()) {
                    val activity = this@ThingsActivity
                    val sp: SharedPreferences = getSharedPreferences(
                        Def.Meta.PREFERENCES_NAME, MODE_PRIVATE
                    )
                    val cp: String? = sp.getString(Def.Meta.KEY_PRIVATE_PASSWORD, null)

                    AuthenticationHelper.authenticate(
                        activity, thing.getBackground(),
                        getString(R.string.check_private_thing), cp,
                        object : AuthenticationHelper.AuthenticationCallback {
                            override fun onAuthenticated() {
                                openDetailActivityForUpdate(thing, position, v!!)
                            }

                            override fun onCancel() {
                            }
                        })
                } else {
                    openDetailActivityForUpdate(thing, position, v!!)
                }
            } else {
                val thing: Thing = things[position]!!
                if (App.getDoingThingId() != thing.id) {
                    thing.selected = !thing.isSelected()
                    mAdapter!!.notifyItemChanged(position)
                    mModeManager!!.updateSelectedCount()
                    mModeManager!!.updateMenuItems()
                }
            }
        }

        private fun openDetailActivityForUpdate(thing: Thing, position: Int, v: View) {
            val intent: Intent = DetailActivity.getOpenIntentForUpdate(
                this@ThingsActivity, TAG, thing.id, position
            )
            val transition: ActivityOptionsCompat = ActivityOptionsCompat.makeScaleUpAnimation(
                v, 0, 0, v.width, v.height
            )
            ActivityCompat.startActivityForResult(
                this@ThingsActivity, intent, Def.Communication.REQUEST_ACTIVITY_DETAIL,
                transition.toBundle()
            )
        }

        override fun onItemLongClick(v: View?, position: Int): Boolean {
            if (position == 0) {
                return false
            }
            dismissSnackbars()

            val things: List<Thing?> = mThingManager!!.getThings()!!
            if (position >= things.size) {
                return false
            }

            val thing: Thing = things[position]!!
            if (mModeManager!!.getCurrentMode() == ModeManager.NORMAL
                && thing.type <= Thing.NOTIFICATION_GOAL
                && App.getDoingThingId() != thing.id
            ) {
                mDrawerLayout!!.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
                if (mApp!!.getLimit() <= Def.LimitForGettingThings.GOAL_UNDERWAY) {
                    mModeManager!!.toMovingMode(position)
                    mRecyclerView!!.post {
                        mThingsTouchHelper!!.startDrag(
                            mRecyclerView!!.findViewHolderForAdapterPosition(position)!!
                        )
                    }
                } else {
                    things[position]!!.selected = true
                    mModeManager!!.toSelectingMode(position)
                }
            } else {
                mModeManager!!.backNormalMode(position)
            }
            return true
        }
    }

    internal inner class ThingsTouchCallback : ItemTouchHelper.Callback() {

        private var swiped: Boolean = false
        private var moved: Boolean = false
        private var firstMove: Boolean = true
        private var finalFrom: Int = 0
        private var finalTo: Int = 0

        override fun getMovementFlags(
            recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
        ): Int {
            val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN or
                ItemTouchHelper.START or ItemTouchHelper.END
            val swipeFlags = ItemTouchHelper.START or ItemTouchHelper.END
            return makeMovementFlags(dragFlags, swipeFlags)
        }

        override fun onMove(
            recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val from = viewHolder.adapterPosition
            val to = target.adapterPosition
            if (canMove(from, to)) {
                mThingManager!!.move(from, to)
                mAdapter!!.notifyItemMoved(from, to)
                if (firstMove) {
                    finalFrom = from
                    firstMove = false
                }
                finalTo = to
                moved = true
                return true
            }
            return false
        }

        private fun canMove(from: Int, to: Int): Boolean {
            val things: List<Thing?> = mThingManager!!.getThings()!!
            val size = things.size
            if (from !in 0..<size || to < 0 || to >= size) {
                return false
            }

            val t1: Thing = things[from]!!
            val t2: Thing = things[to]!!
            if (t1.type == Thing.HEADER || t2.type == Thing.HEADER) {
                return false
            }

            val loc1 = t1.location
            val loc2 = t2.location
            return if (loc1 < 0 && loc2 < 0) { // both are sticky things
                true
            } else if (loc1 >= 0 && loc2 >= 0) { // both are not sticky things
                true
            } else false
        }

        override fun isLongPressDragEnabled(): Boolean = false

        override fun isItemViewSwipeEnabled(): Boolean {
            return mApp!!.getLimit() <= Def.LimitForGettingThings.GOAL_UNDERWAY
                && mModeManager!!.getCurrentMode() != ModeManager.SELECTING
                && !mThingManager!!.isThingsEmpty()
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val position = viewHolder.adapterPosition
            if (position <= 0) {
                return
            }

            val things: List<Thing?> = mThingManager!!.getThings()!!
            if (position >= things.size) {
                return
            }

            val thingToSwipe: Thing = things[position]!!
            val id = thingToSwipe.id
            @Thing.Type val thingType = thingToSwipe.type
            if (thingType > Thing.NOTIFICATION_GOAL) {
                // without this line, the item will disappear and leave a white space...
                mAdapter!!.notifyItemChanged(position)
                return
            }

            prepareBeforeSwipingThing(id, thingType)

            if (direction == ItemTouchHelper.START) {
                if (App.getDoingThingId() == id) {
                    DoingService.sSendBroadcastToUpdateMainUi = false
                }
                if (thingType == Thing.HABIT) {
                    tryToFinishHabitOnceBySwiping(thingToSwipe, position)
                } else {
                    tryToFinishOtherBySwiping(thingToSwipe, position)
                }
            } else {
                if (thingType == Thing.HABIT) {
                    val habit: Habit? = HabitDAO.getInstance(mApp)!!.getHabitById(id)
                    if (habit != null && habit.isPaused()) {
                        mNormalSnackbar!!.setMessage(R.string.alert_habit_paused)
                        mNormalSnackbar!!.show()
                        return
                    }
                }
                mAdapter!!.notifyItemChanged(position)
                if (App.getDoingThingId() == thingToSwipe.id) {
                    Toast.makeText(
                        this@ThingsActivity, R.string.start_doing_doing_this_thing,
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    if (thingToSwipe.isPrivate()) {
                        val cp: String? = getSharedPreferences(Def.Meta.PREFERENCES_NAME, MODE_PRIVATE)
                            .getString(Def.Meta.KEY_PRIVATE_PASSWORD, null)
                        AuthenticationHelper.authenticate(
                            this@ThingsActivity,
                            thingToSwipe.getBackground(),
                            getString(R.string.start_doing_full_title), cp,
                            object : AuthenticationHelper.AuthenticationCallback {
                                override fun onAuthenticated() {
                                    val helper = ThingDoingHelper(this@ThingsActivity, thingToSwipe)
                                    helper.tryToOpenStartDoingActivityUser(thingToSwipe.getBackground())
                                }

                                override fun onCancel() {
                                }
                            })
                    } else {
                        val helper = ThingDoingHelper(this@ThingsActivity, thingToSwipe)
                        helper.tryToOpenStartDoingActivityUser()
                    }
                }
            }

            swiped = true
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            val position = viewHolder.adapterPosition
            if (moved) {
                mThingManager!!.updateLocations(finalFrom, finalTo)
                moved = false
                firstMove = true
                if (finalFrom == finalTo) {
                    mModeManager!!.toSelectingMode(position)
                } else {
                    mModeManager!!.backNormalMode(position)
                }
            } else {
                if (!swiped) {
                    mModeManager!!.toSelectingMode(position)
                } else {
                    swiped = false
                }
            }
            hasSwipedRight = false
        }

        var hasSwipedRight: Boolean = false

        override fun onChildDraw(
            c: Canvas, recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder, dX: Float, dY: Float,
            actionState: Int, isCurrentlyActive: Boolean
        ) {
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                val displayWidth = DisplayUtil.getDisplaySize(mApp).x
                val v: View = viewHolder.itemView
                val holder = viewHolder as BaseThingsAdapter.BaseThingViewHolder
                val position = viewHolder.adapterPosition
                val things: List<Thing?> = mThingManager!!.getThings()!!
                if (position < 0 || position >= things.size) {
                    return
                }

                val thing: Thing = things[position]!!
                if (dX < 0) {
                    holder.flDoing!!.alpha = 1.0f
                    if (App.getDoingThingId() != thing.id) {
                        holder.flDoing.visibility = View.GONE
                    }
                    v.alpha = 1.0f + dX / v.right
                } else if (dX > 0) {
                    if (App.getDoingThingId() == thing.id) {
                        swiped = true
                        return
                    }

                    holder.flDoing!!.visibility = View.VISIBLE
                    if (!hasSwipedRight) {
                        val lp = holder.flDoing.layoutParams as FrameLayout.LayoutParams
                        lp.width  = holder.cv!!.width
                        lp.height = holder.cv.height
                        holder.flDoing.requestLayout()
                        hasSwipedRight = true
                    }

                    var alpha: Float = dX / (displayWidth - v.left) * 2
                    if (alpha > 1.0f) alpha = 1.0f
                    holder.flDoing.alpha = alpha
                } else {
                    v.alpha = 1.0f
                    holder.flDoing!!.alpha = 1.0f
                    if (App.getDoingThingId() != thing.id) {
                        holder.flDoing.visibility = View.GONE
                    }
                }
                swiped = dX != 0f
            }
        }
    }

    private fun prepareBeforeSwipingThing(id: Long, @Thing.Type thingType: Int) {
        if (mNormalSnackbar!!.isShowing()) {
            mNormalSnackbar!!.dismiss()
        }

        if (mUndoSnackbar!!.isShowing()) {
            if (mStateToUndoFrom != Thing.FINISHED || thingType == Thing.HABIT) {
                dismissSnackbars()
            }
        }

        SystemNotificationUtil.cancelNotification(id, thingType, mApp)
        mThingsIdsToUpdateWidget!!.add(id)
    }

    private fun tryToFinishHabitOnceBySwiping(thingToSwipe: Thing, position: Int) {
        val id = thingToSwipe.id
        if (App.getDoingThingId() == id) {
            DoingService.sStopReason = DoingRecord.STOP_REASON_FINISH
        }

        @Thing.Type val thingType = thingToSwipe.type
        val habitDAO: HabitDAO = HabitDAO.getInstance(mApp)!!
        val habit: Habit? = habitDAO.getHabitById(id)
        if (habit != null) {
            if (habit.allowFinish()) {
                if (!mUndoHabitRecords!!.isEmpty()) {
                    val hr: HabitRecord = mUndoHabitRecords!![mUndoHabitRecords!!.size - 1]
                    if (hr.habitId != id) {
                        dismissSnackbars()
                    }
                }
                val thingContent: String = thingToSwipe.content!!
                if (CheckListHelper.isCheckListStr(thingContent)) {
                    mUndoThings!!.add(thingToSwipe)
                    val thing = Thing(thingToSwipe)
                    thing.content = thingContent.replace(
                        (CheckListHelper.SIGNAL + "1").toRegex(),
                        CheckListHelper.SIGNAL + "0"
                    )
                    mThingManager!!.update(thingType, thing, position, false)
                    mThingManager!!.getThings()!![position] = thing
                }
                mUndoHabitRecords!!.add(habitDAO.finishOneTime(habit)!!)
                mUndoPositions!!.add(position)
                showHabitSnackbar()
            } else {
                dismissSnackbars()
                if (habit.isPaused()) {
                    mNormalSnackbar!!.setMessage(R.string.alert_habit_paused)
                } else if (habit.record!!.isEmpty() && habit.remindedTimes == 0) {
                    mNormalSnackbar!!.setMessage(R.string.alert_cannot_finish_habit_first_time)
                } else {
                    mNormalSnackbar!!.setMessage(R.string.alert_cannot_finish_habit_more_times)
                }
                mNormalSnackbar!!.show()
                if (App.getDoingThingId() == id) {
                    DoingService.sStopReason = DoingRecord.STOP_REASON_CANCEL_USER
                }
            }
        }

        if (App.getDoingThingId() == id) {
            val justFinishIntent = Intent(DoingActivity.BROADCAST_ACTION_JUST_FINISH)
            justFinishIntent.setPackage(packageName)
            sendBroadcast(justFinishIntent)
            stopService(Intent(this@ThingsActivity, DoingService::class.java))
            App.setDoingThingId(-1L)
        }

        mAdapter!!.notifyItemChanged(position)
        mDrawerHeader!!.updateCompletionRate()
    }

    private fun tryToFinishOtherBySwiping(thingToSwipe: Thing, position: Int) {
        if (mHabitSnackbar!!.isShowing()) {
            dismissSnackbars()
        }

        @Thing.State val state = thingToSwipe.state
        val location = thingToSwipe.location
        mUndoThings!!.add(thingToSwipe)
        mUndoPositions!!.add(position)
        mUndoLocations!!.add(location)
        mStateToUndoFrom = Thing.FINISHED

        if (App.getDoingThingId() == thingToSwipe.id) {
            DoingService.sStopReason = DoingRecord.STOP_REASON_FINISH
            val justFinishIntent2 = Intent(DoingActivity.BROADCAST_ACTION_JUST_FINISH)
            justFinishIntent2.setPackage(packageName)
            sendBroadcast(justFinishIntent2)
            stopService(Intent(this@ThingsActivity, DoingService::class.java))
            App.setDoingThingId(-1L)
        }

        val changed = mThingManager!!.updateState(
            thingToSwipe, position, location,
            state, Thing.FINISHED, false, true
        )
        mScrollCausedByFinger = false

        if (App.isSearching) {
            if (mThingManager!!.getThings()!!.size == 1) {
                handleSearchResults()
            }
        }

        celebrateHabitGoalFinish(thingToSwipe, state, Thing.FINISHED)
        if (changed) {
            mAdapter!!.notifyItemChanged(position)
            updateUIAfterStateUpdated(
                Thing.FINISHED,
                mRecyclerView!!.itemAnimator!!.changeDuration, true
            )
        } else {
            mAdapter!!.notifyItemRemoved(position)
            updateUIAfterStateUpdated(
                Thing.FINISHED,
                mRecyclerView!!.itemAnimator!!.removeDuration, true
            )
        }
    }

    internal inner class OnContextualMenuClickedListener : Toolbar.OnMenuItemClickListener {
        override fun onMenuItemClick(item: MenuItem): Boolean {
            val itemId = item.itemId
            if (itemId == R.id.act_select_all) {
                if (mThingManager!!.getSelectedCount() == mThingManager!!.getThings()!!.size - 1) {
                    mThingManager!!.setSelectedTo(false)
                } else {
                    mThingManager!!.setSelectedTo(true)
                }
                mAdapter!!.notifyDataSetChanged()
                mModeManager!!.updateMenuItems()
            } else if (itemId == R.id.act_delete_selected) {
                handleUpdateStates(Thing.DELETED)
            } else if (itemId == R.id.act_finish_selected) {
                handleUpdateStates(Thing.FINISHED)
            } else if (itemId == R.id.act_restore_selected) {
                handleUpdateStates(Thing.UNDERWAY)
            } else if (itemId == R.id.act_delete_selected_forever) {
                handleUpdateStates(Thing.DELETED_FOREVER)
            } else if (itemId == R.id.act_sticky) {
                val oldPosition = mThingManager!!.getSingleSelectedPosition()
                if (oldPosition != -1) {
                    mModeManager!!.backNormalMode(oldPosition)
                    mRecyclerView!!.postDelayed({
                        if (oldPosition >= mThingManager!!.getThings()!!.size) return@postDelayed
                        val thing: Thing = mThingManager!!.getThings()!![oldPosition]!!
                        val newPosition: Int
                        if (thing.location < 0) {
                            mThingManager!!.cancelStickyThing(thing, oldPosition)
                            newPosition = mThingManager!!.getPositionToInsertNewThing()
                        } else {
                            mThingManager!!.stickyThingOnTop(thing, oldPosition)
                            newPosition = 1
                        }
                        mAdapter!!.notifyItemMoved(oldPosition, newPosition)
                        // notifyItemMoved will not call adapter.bindViewHolder again
                        mRecyclerView!!.postDelayed({
                            mAdapter!!.notifyItemChanged(newPosition)
                        }, mRecyclerView!!.itemAnimator!!.moveDuration)
                    }, 160)
                }
            } else if (itemId == R.id.act_export) {
                val accentColor = DisplayUtil.getRandomColor(App.getApp())
                ThingExporter.startExporting(
                    this@ThingsActivity, accentColor,
                    *(mThingManager!!.getSelectedThings() ?: emptyArray())
                )
                mModeManager!!.backNormalMode(0)
            }
            mModeManager!!.updateSelectedCount()
            return false
        }
    }

    companion object {
        const val TAG: String = "ThingsActivity"
    }
}
