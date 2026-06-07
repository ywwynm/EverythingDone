@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.activities

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.Manifest
import androidx.activity.OnBackPressedCallback
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.ActivityCompat
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.core.util.Pair
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.ItemTouchHelper
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.text.util.Linkify
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.FrequentSettings
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.adapters.AudioAttachmentAdapter
import com.ywwynm.everythingdone.adapters.CheckListAdapter
import com.ywwynm.everythingdone.adapters.ImageAttachmentAdapter
import com.ywwynm.everythingdone.appwidgets.AppWidgetHelper
import com.ywwynm.everythingdone.appwidgets.CreateWidget
import com.ywwynm.everythingdone.collections.ThingActionsList
import com.ywwynm.everythingdone.database.HabitDAO
import com.ywwynm.everythingdone.database.ReminderDAO
import com.ywwynm.everythingdone.database.ThingDAO
import com.ywwynm.everythingdone.fragments.AddAttachmentDialogFragment
import com.ywwynm.everythingdone.fragments.AlertDialogFragment
import com.ywwynm.everythingdone.fragments.AttachmentInfoDialogFragment
import com.ywwynm.everythingdone.fragments.AudioRecordDialogFragment
import com.ywwynm.everythingdone.fragments.CameraColorSamplingDialogFragment
import com.ywwynm.everythingdone.fragments.ChooserDialogFragment
import com.ywwynm.everythingdone.fragments.ColorInfoDialogFragment
import com.ywwynm.everythingdone.fragments.DateTimeDialogFragment
import com.ywwynm.everythingdone.fragments.GradientOrientationDialogFragment
import com.ywwynm.everythingdone.fragments.HabitDetailDialogFragment
import com.ywwynm.everythingdone.fragments.HabitRecordDialogFragment
import com.ywwynm.everythingdone.fragments.LoadingDialogFragment
import com.ywwynm.everythingdone.fragments.LongTextDialogFragment
import com.ywwynm.everythingdone.fragments.PatternLockDialogFragment
import com.ywwynm.everythingdone.fragments.ThingDoingDialogFragment
import com.ywwynm.everythingdone.fragments.TwoOptionsDialogFragment
import com.ywwynm.everythingdone.helpers.AppUpdateHelper
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.helpers.AuthenticationHelper
import com.ywwynm.everythingdone.helpers.CheckListHelper
import com.ywwynm.everythingdone.helpers.LineSpacingHelper
import com.ywwynm.everythingdone.helpers.ScreenshotHelper
import com.ywwynm.everythingdone.helpers.SendInfoHelper
import com.ywwynm.everythingdone.helpers.ThingDoingHelper
import com.ywwynm.everythingdone.helpers.ThingExporter
import com.ywwynm.everythingdone.managers.ThingManager
import com.ywwynm.everythingdone.model.Habit
import com.ywwynm.everythingdone.model.Reminder
import com.ywwynm.everythingdone.model.ReminderHabitParams
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.model.ThingAction
import com.ywwynm.everythingdone.permission.PermissionUtil
import com.ywwynm.everythingdone.permission.SimplePermissionCallback
import com.ywwynm.everythingdone.receivers.AutoNotifyReceiver
import com.ywwynm.everythingdone.receivers.DailyCreateTodoReceiver
import com.ywwynm.everythingdone.receivers.HabitReceiver
import com.ywwynm.everythingdone.receivers.ReminderReceiver
import com.ywwynm.everythingdone.utils.DateTimeUtil
import com.ywwynm.everythingdone.utils.DeviceUtil
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.FileUtil
import com.ywwynm.everythingdone.utils.KeyboardUtil
import com.ywwynm.everythingdone.utils.LocaleUtil
import com.ywwynm.everythingdone.utils.SystemNotificationUtil
import com.ywwynm.everythingdone.utils.UriPathConverter
import com.ywwynm.everythingdone.views.Snackbar
import com.ywwynm.everythingdone.views.pickers.ColorPicker
import com.ywwynm.everythingdone.views.pickers.DateTimePicker

import java.time.ZonedDateTime

import java.io.File
import java.util.ArrayList
import java.util.Calendar
import java.util.Collections
import java.util.HashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.core.view.isVisible
import androidx.core.view.size
import androidx.core.view.get
import androidx.core.content.edit

@SuppressLint("NewApi")
class DetailActivity : EverythingDoneBaseActivity() {

    @JvmField var screenDensity: Float = 0f

    // type + path + name of attachment to add
    @JvmField var attachmentTypePathName: String? = null
    @JvmField var cameraOutputUri: Uri? = null

    @JvmField var rhParams: ReminderHabitParams = ReminderHabitParams()

    @JvmField var quickRemindPicker: DateTimePicker? = null
    @JvmField var cbQuickRemind: CheckBox? = null
    @JvmField var tvQuickRemind: TextView? = null

    private var mSenderName: String? = null
    private var mType: Int = 0

    private var mApp: App? = null

    private var mEditable: Boolean = false

    private var mThing: Thing? = null
    private var mThingCardSpanMode: Int = Thing.THING_CARD_SPAN_NORMAL
    private var mThingCardImagePlacement: Int = Thing.THING_CARD_IMAGE_PLACEMENT_DEFAULT
    private var mPosition: Int = 0
    private var mReminder: Reminder? = null
    private var mHabit: Habit? = null

    private var mHabitFinishedThisTime: Boolean = false

    // added on 2017/2/10 used to know if user has changed a habit's finishing records
    private var mHabitRecordEdited: Boolean = false

    fun setHabitRecordEdited(habitRecordEdited: Boolean) {
        mHabitRecordEdited = habitRecordEdited
    }

    private var mMaxSpanImage: Int = 0
    private var mSpanAudio: Int = 0

    private var mDateTimeDialogFragment: DateTimeDialogFragment? = null
    private var mLimit: Int = -1
    private var mNightModeMask: Int = 0
    private var mRenderedThingSnapshot: ThingSnapshot? = null
    private var mReloadFromStorageOnResume: Boolean = false
    private var mExternalUpdateRefreshRetries: Int = 0
    private var mStatusBarTopInset: Int = 0
    private var mScrollViewHasStatusBarMarginTop: Boolean = true

    private var mFlRoot: FrameLayout? = null
    private var mColorPicker: ColorPicker? = null
    private var mStatusBar: View? = null
    private var mActionbar: Toolbar? = null
    private var mIbBack: ImageButton? = null
    private var mActionBarShadow: View? = null
    private var mImageCover: View? = null

    private var mRvImageAttachment: RecyclerView? = null
    private var mImageAttachmentAdapter: ImageAttachmentAdapter? = null
    private var mImageLayoutManager: GridLayoutManager? = null

    private var mScrollView: NestedScrollView? = null
    private var mEtTitle: EditText? = null
    private var mEtContent: EditText? = null
    private var mTvUpdateTime: TextView? = null

    private var mRvCheckList: RecyclerView? = null
    private var mCheckListAdapter: CheckListAdapter? = null
    private var mLlmCheckList: CannotScrollLinearLayoutManager? = null
    private var mLlMoveChecklist: LinearLayout? = null
    private var mTvMoveChecklistAsBt: TextView? = null
    private var mChecklistTouchHelper: ItemTouchHelper? = null

    private var mRvAudioAttachment: RecyclerView? = null
    private var mAudioAttachmentAdapter: AudioAttachmentAdapter? = null
    private var mAudioLayoutManager: GridLayoutManager? = null

    private var mFlQuickRemindAsBt: FrameLayout? = null

    private var mNormalSnackbar: Snackbar? = null

    private var mChangeColorTo: Int = 0

    private var mExecutor: ExecutorService? = null

    private var mShowNormalSnackbar: Runnable? = null

    private var mRemoveDetailActivityInstance: Boolean = false
    private var mMinusCreateActivitiesCount: Boolean = false

    private var mTouchMovedCountMap: HashMap<View, Int>? = null
    private var mOnLongClickedMap: HashMap<View, Boolean>? = null

    private var mShouldAutoLink: Boolean = false

    private data class ThingSnapshot(
        val id: Long,
        val type: Int,
        val state: Int,
        val color: Int,
        val background: String?,
        val title: String?,
        val content: String?,
        val attachment: String?,
        val location: Long,
        val createTime: Long,
        val updateTime: Long,
        val finishTime: Long,
        val thingCardSpanMode: Int,
        val thingCardImagePlacement: Int
    )

    /**
     * This OnTouchListener will listen to click events that should be handled by
     * link/phoneNum/email/maps in mEtContent and other EditTexts.
     */
    @JvmField var mSpannableTouchListener: View.OnTouchListener? = null
    private var mEtContentClickListener: View.OnClickListener? = null
    private var mEtContentLongClickListener: View.OnLongClickListener? = null

    private var mActionList: ThingActionsList? = null
    @JvmField var shouldAddToActionList: Boolean = false

    private val mReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action: String? = intent.action
            if (Def.Communication.BROADCAST_ACTION_UPDATE_MAIN_UI == action) {
                val resultCode = intent.getIntExtra(
                    Def.Communication.KEY_RESULT_CODE,
                    Def.Communication.RESULT_NO_UPDATE
                )
                if (resultCode == Def.Communication.RESULT_UPDATE_THING_DONE_TYPE_SAME) {
                    val thing: Thing? = intent.getParcelableExtra(Def.Communication.KEY_THING)
                    if (thing != null && mThing != null) {
                        val thingId = mThing!!.id
                        if (thing.id == thingId) {
                            markReloadFromStorageOnResume()
                            if (Thing.isReminderType(thing.type)) {
                                mReminder = ReminderDAO.getInstance(App.getApp())!!.getReminderById(thingId)
                                updateBottomBarForReminder()
                            } else if (thing.type == Thing.HABIT) {
                                mHabit = HabitDAO.getInstance(App.getApp())!!.getHabitById(thingId)
                            }
                        }
                    }
                } else if (resultCode == Def.Communication.RESULT_DOING_OR_CANCEL) {
                    if (mThing != null && mThing!!.id == App.getDoingThingId()) { // user start doing
                        finish()
                    }
                } else if (resultCode == Def.Communication.RESULT_UPDATE_THING_STATE_DIFFERENT) {
                    val thing: Thing? = intent.getParcelableExtra(Def.Communication.KEY_THING)
                    if (thing != null && mThing != null && thing.id == mThing!!.id) {
                        if (App.isDetailActivityVisible(mThing!!.id)) {
                            finish()
                        } else {
                            markReloadFromStorageOnResume()
                        }
                    }
                }
            } else if (Def.Communication.BROADCAST_ACTION_FINISH_DETAILACTIVITY == action) {
                val id = intent.getLongExtra(Def.Communication.KEY_ID, -1)
                if (mThing != null && mThing!!.id == id) {
                    finish()
                }
            }
        }
    }

    fun getActionList(): ThingActionsList = mActionList!!

    val type: Int get() = mType

    override fun getLayoutResource(): Int = R.layout.activity_detail

    override fun init() {
        initMembers() // if we found thing is null, just finish this Activity
        if (mThing != null) {
            findViews()
            initUI()
            recordRenderedThingSnapshot()
            setActionbar()
            setEvents()

            var intentFilter = IntentFilter(Def.Communication.BROADCAST_ACTION_UPDATE_MAIN_UI)
            ContextCompat.registerReceiver(this, mReceiver, intentFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED)

            intentFilter = IntentFilter(Def.Communication.BROADCAST_ACTION_FINISH_DETAILACTIVITY)
            ContextCompat.registerReceiver(this, mReceiver, intentFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }

    override fun initMembers() {
        mApp = application as App
        mApp!!.setDetailActivityRun(true)

        screenDensity = DisplayUtil.getScreenDensity(this)
        mNightModeMask = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK

        val intent: Intent = getIntent()
        val action: String? = intent.action
        if (Intent.ACTION_SEND == action || Intent.ACTION_SEND_MULTIPLE == action) {
            mSenderName = "intent"
            mType = CREATE
        } else {
            mSenderName = intent.getStringExtra(Def.Communication.KEY_SENDER_NAME)
            mType = intent.getIntExtra(Def.Communication.KEY_DETAIL_ACTIVITY_TYPE, UPDATE)
        }

        var id = intent.getLongExtra(Def.Communication.KEY_ID, -1)

        mPosition = intent.getIntExtra(Def.Communication.KEY_POSITION, 1)

        val thingManager: ThingManager = ThingManager.getInstance(mApp)!!
        if (mType == CREATE) {
            createActivitiesCount++

            val newId = thingManager.getHeaderId()
            App.getRunningDetailActivities().add(newId)

            var color = intent.getIntExtra(Def.Communication.KEY_COLOR, App.newThingColor)
            if (color == 0) color = DisplayUtil.getRandomColor(mApp)

            mThing = Thing(newId, Thing.NOTE, color, newId)

            val bgJson: String? = intent.getStringExtra(Def.Communication.KEY_BACKGROUND)
            if (bgJson != null) {
                val bg: ThingBackground? = ThingBackground.fromJson(bgJson)
                if (bg != null) mThing!!.setBackground(bg)
            }

            App.updateNewThingColor()
            SystemNotificationUtil.tryToCreateQuickCreateNotification(this)

            if ("intent" == mSenderName) {
                setupThingFromIntent()
            } else if (DailyCreateTodoReceiver.TAG == mSenderName) {
                mThing!!.title = getDailyTodoTitle()
            }
        } else {
            updateThingAndItsPosition(id)

            if (mThing == null) {
                finish()
                return
            }

            id = mThing!!.id
            App.getRunningDetailActivities().add(id)
            mReminder = ReminderDAO.getInstance(mApp)!!.getReminderById(id)
            if (mThing!!.type == Thing.HABIT) {
                mHabit = HabitDAO.getInstance(mApp)!!.getHabitById(id)
            }
            SystemNotificationUtil.cancelNotification(id, mThing!!.type, mApp)
        }

        mEditable = mThing!!.type != Thing.HEADER
                && mThing!!.type < Thing.NOTIFICATION_UNDERWAY
                && mThing!!.state == Thing.UNDERWAY
        mThingCardSpanMode = mThing!!.thingCardSpanMode
        mThingCardImagePlacement = mThing!!.thingCardImagePlacement
        if (mEditable) {
            mShowNormalSnackbar = Runnable {
                mNormalSnackbar!!.show()
            }
        }

        setSpans()

        if (mEditable) {
            mLimit = intent.getIntExtra(Def.Communication.KEY_LIMIT, -1)
            if (mLimit == -1) {
                mLimit = mApp!!.getLimit()
            }
            createDateTimeDialogFragment()
        }
        mExecutor = Executors.newSingleThreadExecutor()

        mActionList = ThingActionsList()
        mActionList!!.setAddActionCallback(object : ThingActionsList.AddActionCallback {
            override fun onAddAction() {
                updateUndoRedoActionButtonState()
            }
        })

        initAutoLink()
    }

    private fun setupThingFromIntent() {
        val intent: Intent = getIntent()
        val action: String? = intent.action
        val type: String? = intent.type
        if (Intent.ACTION_SEND == action) {
            if (type!!.contains("image/") || type.contains("video/")
                || type.contains("audio/")
            ) {
                val data: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)
                val pathName: String? = UriPathConverter.getLocalPathName(this, data)
                if (pathName != null) {
                    mThing!!.attachment = AttachmentHelper.SIGNAL + getTypePathName(pathName, null)
                }
            }
        } else if (Intent.ACTION_SEND_MULTIPLE == action) {
            val datas: ArrayList<Uri>? = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            val sb = StringBuilder()
            for (data in datas!!) {
                val pathName: String? = UriPathConverter.getLocalPathName(this, data)
                if (pathName != null) {
                    val typePathName: String? = getTypePathName(pathName, null)
                    if (typePathName != null) {
                        sb.append(AttachmentHelper.SIGNAL).append(typePathName)
                    }
                }
            }
            mThing!!.attachment = sb.toString()
        }
        val title: String? = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        if (title != null) {
            mThing!!.title = title
        }
        val content: String? = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (content != null) {
            mThing!!.content = content
        }
    }

    private fun getDailyTodoTitle(): String {
        val dateStr: String = DateTimeUtil.getGeneralDateStr(mApp, System.currentTimeMillis())!!
        val restPart: String = getString(R.string.daily_todo_title_rest_part)
        return if (LocaleUtil.isChinese(mApp)) {
            dateStr + restPart
        } else {
            "$restPart $dateStr"
        }
    }

    private fun updateThingAndItsPosition(oriId: Long) {
        val manager: ThingManager = ThingManager.getInstance(mApp)!!
        if (mType == CREATE) {
            val newId = manager.getHeaderId()
            val runningDetailActivities: MutableList<Long?> = App.getRunningDetailActivities()
            val index = runningDetailActivities.lastIndexOf(oriId)
            runningDetailActivities[index] = newId
            mThing = Thing(newId, Thing.NOTE, 0, newId)
            return
        }

        val pair: Pair<Thing, Int> = App.getThingAndPosition(mApp, oriId, -1)
        mThing = pair.first
        mPosition = pair.second ?: -1
    }

    private fun recordRenderedThingSnapshot() {
        mRenderedThingSnapshot = createThingSnapshot(mThing)
        mReloadFromStorageOnResume = false
        mExternalUpdateRefreshRetries = 0
    }

    private fun createThingSnapshot(thing: Thing?): ThingSnapshot? {
        if (thing == null) return null
        return ThingSnapshot(
            thing.id,
            thing.type,
            thing.state,
            thing.getColor(),
            thing.getBackground()?.toJson(),
            thing.title,
            thing.content,
            thing.attachment,
            thing.location,
            thing.createTime,
            thing.updateTime,
            thing.finishTime,
            thing.thingCardSpanMode,
            thing.thingCardImagePlacement
        )
    }

    private fun loadLatestThingAndPosition(id: Long): Pair<Thing?, Int> {
        val pair: Pair<Thing, Int> = App.getThingAndPosition(mApp, id, mPosition)
        if (pair.first != null) {
            return Pair(pair.first, pair.second ?: -1)
        }

        return Pair(ThingDAO.getInstance(mApp)!!.getThingById(id), -1)
    }

    private fun refreshFromExternalUpdateIfNeeded() {
        if (mType != UPDATE || mThing == null) return

        val renderedSnapshot = mRenderedThingSnapshot ?: createThingSnapshot(mThing)
        if (renderedSnapshot == null) return

        val pair = loadLatestThingAndPosition(mThing!!.id)
        val latestThing = pair.first
        if (latestThing == null) {
            finish()
            return
        }

        val latestSnapshot = createThingSnapshot(latestThing)
        if (!mReloadFromStorageOnResume && latestSnapshot == renderedSnapshot) return
        if (latestSnapshot == renderedSnapshot) {
            if (mExternalUpdateRefreshRetries < EXTERNAL_UPDATE_REFRESH_MAX_RETRIES) {
                mExternalUpdateRefreshRetries++
                mFlRoot!!.postDelayed({ refreshFromExternalUpdateIfNeeded() }, 180)
            } else {
                mReloadFromStorageOnResume = false
                mExternalUpdateRefreshRetries = 0
            }
            return
        }

        mReloadFromStorageOnResume = false
        mExternalUpdateRefreshRetries = 0
        mPosition = pair.second ?: -1
        // Prevent stale old-instance data from auto-saving during this recreate.
        dontSaveAfterOnPause = true
        recreate()
    }

    private fun markReloadFromStorageOnResume() {
        mReloadFromStorageOnResume = true
        mExternalUpdateRefreshRetries = 0
    }

    private fun updateDetailActivityVisibility(visible: Boolean) {
        val thing = mThing ?: return
        App.setDetailActivityVisible(thing.id, visible)
    }

    private fun initAutoLink() {
        mShouldAutoLink = FrequentSettings.getBoolean(Def.Meta.KEY_AUTO_LINK)
        if (mShouldAutoLink) {
            mTouchMovedCountMap = HashMap()
            mOnLongClickedMap = HashMap()
            mSpannableTouchListener = object : View.OnTouchListener {

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    val action = event.action
                    val onLongClicked: Boolean? = mOnLongClickedMap!![v]
                    if (onLongClicked != null && onLongClicked) {
                        return false
                    }

                    if (action == MotionEvent.ACTION_UP) {
                        val touchMovedCount: Int? = mTouchMovedCountMap!![v]
                        if (touchMovedCount != null && touchMovedCount >= 3) {
                            Log.d(TAG, "touchMoved: $touchMovedCount")
                            mTouchMovedCountMap!![v] = 0
                            return false
                        }

                        val et = v as EditText
                        val sContent: Spannable = Spannable.Factory.getInstance()
                            .newSpannable(et.text)

                        var x = event.x.toInt()
                        var y = event.y.toInt()

                        x -= et.totalPaddingLeft
                        y -= et.totalPaddingTop
                        x += et.scrollX
                        y += et.scrollY

                        val layout = et.layout
                        val line = layout.getLineForVertical(y)
                        val offset = layout.getOffsetForHorizontal(line, x.toFloat())

                        // place cursor of EditText to correct position.
                        et.requestFocus()
                        if (offset > 0) {
                            if (x > layout.getLineMax(line)) {
                                et.setSelection(offset)
                            } else et.setSelection(offset - 1)
                        }

                        val link: Array<ClickableSpan> = sContent.getSpans(offset, offset, ClickableSpan::class.java)
                        if (link.isNotEmpty()) {
                            val urlSpan = link[0] as URLSpan

                            if (!mEditable) {
                                urlSpan.onClick(et)
                                return true
                            }

                            val url: String = urlSpan.url
                            val df = TwoOptionsDialogFragment()
                            df.setViewToFocusAfterDismiss(et)

                            val startListener = View.OnClickListener {
                                df.dismiss()
                                KeyboardUtil.hideKeyboard(window)
                                try {
                                    urlSpan.onClick(et)
                                } catch (_: ActivityNotFoundException) {
                                    mNormalSnackbar!!.setMessage(R.string.error_activity_not_found)
                                    mFlRoot!!.postDelayed(mShowNormalSnackbar,
                                        KeyboardUtil.HIDE_DELAY.toLong())
                                }
                            }
                            val endListener = View.OnClickListener {
                                df.setShouldShowKeyboardAfterDismiss(true)
                                df.dismiss()
                            }

                            if (url.startsWith("tel")) {
                                df.setStartAction(R.drawable.act_dial, R.string.act_dial,
                                    startListener)
                            } else if (url.startsWith("mailto")) {
                                df.setStartAction(R.drawable.act_send_email,
                                    R.string.act_send_email, startListener)
                            } else if (url.startsWith("http") || url.startsWith("https")) {
                                df.setStartAction(R.drawable.act_open_in_browser,
                                    R.string.act_open_in_browser, startListener)
                            } else if (url.startsWith("map")) {
                                df.setStartAction(R.drawable.act_open_in_map,
                                    R.string.act_open_in_map, startListener)
                            }
                            df.setEndAction(R.drawable.act_edit, R.string.act_edit, endListener)
                            df.show(fragmentManager, TwoOptionsDialogFragment.TAG)
                            return true
                        }
                    } else if (action == MotionEvent.ACTION_MOVE) {
                        val touchMovedCount: Int? = mTouchMovedCountMap!![v]
                        mTouchMovedCountMap!![v] = if (touchMovedCount == null) 1 else touchMovedCount + 1
                    }
                    return false
                }
            }
            mEtContentClickListener = View.OnClickListener { v ->
                mTouchMovedCountMap!![v] = 0
                mOnLongClickedMap!![v] = false
            }
            mEtContentLongClickListener = View.OnLongClickListener { v ->
                mTouchMovedCountMap!![v] = 0
                mOnLongClickedMap!![v] = true
                false
            }
        }
    }

    override fun findViews() {
        mFlRoot = f(R.id.fl_root_detail)

        mStatusBar       = f(R.id.view_status_bar)
        mActionbar       = f(R.id.actionbar)
        mIbBack          = f(R.id.ib_back)
        mActionBarShadow = f(R.id.actionbar_shadow)
        mImageCover      = f(R.id.view_image_cover)

        mRvImageAttachment = f(R.id.rv_image_attachment)
        mRvImageAttachment!!.isNestedScrollingEnabled = false

        mScrollView   = f(R.id.sv_detail)
        mEtTitle      = f(R.id.et_title)
        mEtContent    = f(R.id.et_content)
        mTvUpdateTime = f(R.id.tv_update_time)

        mRvCheckList = f(R.id.rv_check_list)
        mRvCheckList!!.itemAnimator = null
        mRvCheckList!!.isNestedScrollingEnabled = false

        mRvAudioAttachment = f(R.id.rv_audio_attachment)
        mRvAudioAttachment!!.isNestedScrollingEnabled = false
        (mRvAudioAttachment!!.itemAnimator as SimpleItemAnimator).supportsChangeAnimations = false

        mFlQuickRemindAsBt = f(R.id.fl_quick_remind_as_bt)
        cbQuickRemind      = f(R.id.cb_quick_remind)
        tvQuickRemind      = f(R.id.tv_quick_remind)

        mNormalSnackbar = Snackbar(mApp!!, Snackbar.NORMAL, mFlRoot!!, null)
        if (mEditable) {
            mLlMoveChecklist     = f(R.id.ll_move_checklist)
            mTvMoveChecklistAsBt = f(R.id.tv_move_checklist_as_bt)

            createEditablePickers()
        }
    }

    private fun createDateTimeDialogFragment() {
        mDateTimeDialogFragment = DateTimeDialogFragment.newInstance(mThing, mLimit)
    }

    private fun createEditablePickers() {
        val decorView: View = window.decorView
        mColorPicker = ColorPicker(this, decorView, Def.PickerType.COLOR_EDIT)
        quickRemindPicker = DateTimePicker(
            this, decorView,
            Def.PickerType.AFTER_TIME, getAccentColor()
        )
        val currentBg: ThingBackground? = getAccentBackground()
        if (currentBg != null) quickRemindPicker!!.setAccentBackground(currentBg)
        quickRemindPicker!!.setAnchor(tvQuickRemind!!)
    }

    override fun initUI() {
        DisplayUtil.expandLayoutToStatusBarAboveLollipop(this)
        DisplayUtil.expandStatusBarViewAboveKitkat(mStatusBar) { topInset ->
            if (mStatusBarTopInset != topInset) {
                mStatusBarTopInset = topInset
                applyScrollViewMarginTop()
                if (mImageCover!!.visibility == View.VISIBLE) {
                    updateImageCoverHeight()
                }
                updateBottomBarShadow()
            }
        }
        DisplayUtil.applyBottomInsetAsPadding(mFlRoot)

        val color = mThing!!.getColor()
        if (mEditable) {
            AppUpdateHelper.updateFrom1_1_4To1_1_5(this, color)
        }

        @Thing.Type val thingType: Int  = mThing!!.type
        @Thing.State val thingState: Int = mThing!!.state

        if (DailyCreateTodoReceiver.TAG == mSenderName) {
            initBackButton(Thing.REMINDER)
        } else {
            initBackButton(thingType)
        }

        BackgroundUtil.applyBackground(mFlRoot, mThing!!.getBackground())
        applyForegroundColors(color)

        if (!mEditable) {
            mEtTitle!!.keyListener = null
            mEtContent!!.keyListener = null
            cbQuickRemind!!.isEnabled = thingState == Thing.UNDERWAY
        } else {
            mColorPicker!!.pickForBackground(mThing!!.getBackground())
        }

        mEtTitle!!.setText(mThing!!.getTitleToDisplay())
        if (mThing!!.isPrivate()) {
            setAsPrivateThingUiAndAddAction()
        }

        initUiForThingContent()
        initUiForThingAttachment()

        initUiTvUpdateFinish(thingType, thingState)

        initUiBottomBar()

        updateDescriptions(mThing!!.getColor())
    }

    private fun initBackButton(@Thing.Type thingType: Int) {
        when (thingType) {
            Thing.REMINDER, Thing.WELCOME_REMINDER -> {
                mIbBack!!.setImageResource(R.drawable.act_back_reminder)
                mIbBack!!.contentDescription = getString(R.string.cd_back_reminder)
            }
            Thing.HABIT, Thing.WELCOME_HABIT -> {
                mIbBack!!.setImageResource(R.drawable.act_back_habit)
                mIbBack!!.contentDescription = getString(R.string.cd_back_habit)
            }
            Thing.GOAL, Thing.WELCOME_GOAL -> {
                mIbBack!!.setImageResource(R.drawable.act_back_goal)
                mIbBack!!.contentDescription = getString(R.string.cd_back_goal)
            }
            else -> {
                mIbBack!!.setImageResource(R.drawable.act_back_note)
                mIbBack!!.contentDescription = getString(R.string.cd_back_note)
            }
        }
    }

    private fun initUiForThingContent() {
        if (mShouldAutoLink) {
            mEtContent!!.autoLinkMask = Linkify.ALL
        } else {
            mEtContent!!.autoLinkMask = 0
        }

        val content: String = mThing!!.content!!
        if (mType == CREATE) {
            mEtContent!!.requestFocus()
            setScrollViewMarginTop(true)
            mEtContent!!.setText(content)
            mEtContent!!.setSelection(content.length)
        } else {
            if (CheckListHelper.isCheckListStr(content)) {
                mEtContent!!.visibility = View.GONE
                mRvCheckList!!.visibility = View.VISIBLE
                if (mEditable) {
                    mLlMoveChecklist!!.visibility = View.VISIBLE
                }

                val items: MutableList<String?> = CheckListHelper.toCheckListItems(content, false)
                if (!mEditable) {
                    val state = mThing!!.state
                    items.remove("2")
                    if (state == Thing.FINISHED) {
                        items.remove("3")
                        items.remove("4")
                    } else if (items[0]!! == "2") {
                        items.remove("3")
                        items.remove("4")
                    }
                    mCheckListAdapter = CheckListAdapter(
                        this, CheckListAdapter.EDITTEXT_UNEDITABLE, items
                    )
                } else {
                    mCheckListAdapter = CheckListAdapter(
                        this, CheckListAdapter.EDITTEXT_EDITABLE, items
                    )
                    if (mShouldAutoLink) {
                        mCheckListAdapter!!.setEtTouchListener(mSpannableTouchListener)
                        mCheckListAdapter!!.setEtClickListener(mEtContentClickListener)
                        mCheckListAdapter!!.setEtLongClickListener(mEtContentLongClickListener)
                    }
                    mCheckListAdapter!!.setItemsChangeCallback(CheckListItemsChangeCallback())
                    mCheckListAdapter!!.setActionCallback(CheckListActionCallback())
                }
                mCheckListAdapter!!.setShouldAutoLink(mShouldAutoLink)
                mCheckListAdapter!!.setThingColor(mThing!!.getColor())
                setChecklistExpandShrinkEvent()

                setMoveChecklistEvent()

                mLlmCheckList = CannotScrollLinearLayoutManager(this)
                if (mEditable) {
                    mCheckListAdapter!!.setExpanded(false)
                }
                mRvCheckList!!.adapter = mCheckListAdapter
                mRvCheckList!!.layoutManager = mLlmCheckList
                if (mEditable) {
                    mRvCheckList!!.viewTreeObserver.addOnPreDrawListener(
                        object : ViewTreeObserver.OnPreDrawListener {
                            override fun onPreDraw(): Boolean {
                                expandOrShrinkChecklistFinishedItems(
                                    false, mCheckListAdapter!!.getItems(), false
                                )
                                val observer: ViewTreeObserver = mRvCheckList!!.viewTreeObserver
                                if (observer.isAlive) {
                                    observer.removeOnPreDrawListener(this)
                                }
                                return true
                            }
                        })
                }
            } else {
                mEtContent!!.visibility = View.VISIBLE
                mEtContent!!.setText(content)
            }
        }
    }

    private fun initUiForThingAttachment() {
        val attachment: String? = mThing!!.attachment
        if (AttachmentHelper.isValidForm(attachment)) {
            if (!PermissionUtil.hasStoragePermission(this)) {
                // make ui normal before asking for permission
                setScrollViewMarginTop(true)
            }
            doWithPermissionChecked(
                object : SimplePermissionCallback(this) {
                    override fun onGranted() {
                        val items: Pair<List<String?>, List<String?>> =
                            AttachmentHelper.toAttachmentItems(attachment)
                        if (!items.first.isEmpty()) {
                            initImageAttachmentUI(items.first)
                        } else {
                            setScrollViewMarginTop(true)
                        }

                        if (!items.second.isEmpty()) {
                            initAudioAttachmentUI(items.second)
                        }
                    }
                    override fun onDenied() {
                        super.onDenied()
                        finish()
                    }
                },
                Def.Communication.REQUEST_PERMISSION_LOAD_THING,
                *PermissionUtil.getRequiredPermissionsForThings(
                    Collections.singletonList(mThing)
                )
            )

        } else {
            setScrollViewMarginTop(true)
        }
    }

    private fun initUiTvUpdateFinish(thingType: Int, thingState: Int) {
        mTvUpdateTime!!.paint.textSkewX = -0.25f
        val tvFinishTime: TextView = f(R.id.tv_finish_time)!!
        tvFinishTime.paint.textSkewX = -0.25f
        if (mType == CREATE) {
            mTvUpdateTime!!.text = ""
            tvFinishTime.visibility = View.GONE
        } else {
            val isChinese = LocaleUtil.isChinese(this)
            if (mThing!!.createTime == mThing!!.updateTime) {
                mTvUpdateTime!!.setText(R.string.create_at)
            } else {
                mTvUpdateTime!!.setText(R.string.update_at)
            }
            if (!isChinese) {
                mTvUpdateTime!!.append(" ")
            }
            mTvUpdateTime!!.append(DateTimeUtil.getDateTimeStrAt(mThing!!.updateTime, mApp, true))

            // finish time
            if (thingState == Thing.FINISHED) {
                initAndShowTvFinishTime(tvFinishTime, thingType, isChinese)
            }
        }
    }

    private fun initAndShowTvFinishTime(tvFinishTime: TextView, @Thing.Type thingType: Int, isChinese: Boolean) {
        tvFinishTime.visibility = View.VISIBLE
        when (thingType) {
            Thing.HABIT -> {
                tvFinishTime.setText(R.string.finish_at_habit)
                if (!isChinese) {
                    tvFinishTime.append(" ")
                }
                tvFinishTime.append(DateTimeUtil.getDateTimeStrAt(mThing!!.finishTime, mApp, true))
            }
            Thing.GOAL -> {
                val actionStr: String
                var finishType = 1
                if (mReminder != null) {
                    finishType = mReminder!!.getFinishType(mThing!!.finishTime, true)
                }
                actionStr = when (finishType) {
                    0 -> getString(R.string.finish_at_goal_in_advance)
                    1 -> getString(R.string.finish_at_goal_normal)
                    else -> { // finishType == 2
                        getString(R.string.finish_at_goal_overdue)
                    }
                }
                tvFinishTime.text = String.format(
                    actionStr, DateTimeUtil.getDateTimeStrAt(mThing!!.finishTime, mApp, true)
                )
            }
            else -> {
                tvFinishTime.text = String.format(
                    getString(R.string.finish_at_normal),
                    DateTimeUtil.getDateTimeStrAt(mThing!!.finishTime, mApp, true)
                )
            }
        }
    }

    private fun initUiBottomBar() {
        if (mType == CREATE) {
            if (DailyCreateTodoReceiver.TAG != mSenderName) {
                quickRemindPicker!!.pickForUI(8)
                rhParams.reminderAfterTime = quickRemindPicker!!.getPickedTimeAfter()
            } else {
                quickRemindPicker!!.pickForUI(9)
                cbQuickRemind!!.isChecked = true
                val reminderInMillis: Long = ZonedDateTime.now()
                    .withHour(14).withMinute(0).withSecond(0).withNano(0)
                    .toInstant().toEpochMilli()
                tvQuickRemind!!.text = DateTimeUtil.getDateTimeStrAt(reminderInMillis, this, false)
                rhParams.reminderInMillis = reminderInMillis
            }
        } else {
            if (mReminder != null) {
                updateBottomBarForReminder()
            } else if (mHabit != null) {
                cbQuickRemind!!.isChecked = mEditable
                if (mEditable) {
                    quickRemindPicker!!.pickForUI(9)
                }
                val habitType = mHabit!!.type
                val habitDetail: String? = mHabit!!.detail
                tvQuickRemind!!.text = DateTimeUtil.getDateTimeStrRec(
                    mApp, habitType, habitDetail
                )
                rhParams.habitType = habitType
                rhParams.habitDetail = habitDetail
            } else {
                if (mEditable) {
                    quickRemindPicker!!.pickForUI(8)
                    rhParams.reminderAfterTime = quickRemindPicker!!.getPickedTimeAfter()
                } else {
                    f<View>(R.id.ll_bottom_bar_detail).visibility = View.GONE
                    val params = mScrollView!!.layoutParams as FrameLayout.LayoutParams
                    params.setMargins(0, params.topMargin, 0, 0)
                }
            }
            cbQuickRemind!!.contentDescription =
                getString(R.string.remind_me) + tvQuickRemind!!.text
        }

        initUiStartDoing()
    }

    private fun updateBottomBarForReminder() {
        if (mReminder != null) {
            cbQuickRemind!!.isChecked = mReminder!!.state == Reminder.UNDERWAY

            if (mEditable) {
                quickRemindPicker!!.pickForUI(9)
            }

            val reminderInMillis: Long = mReminder!!.notifyTime
            tvQuickRemind!!.text = DateTimeUtil.getDateTimeStrAt(reminderInMillis, this, false)
            rhParams.reminderInMillis = reminderInMillis
            val state = mReminder!!.state
            @Thing.State val thingState = mThing!!.state
            if (state != Reminder.UNDERWAY || thingState != Thing.UNDERWAY) {
                tvQuickRemind!!.append(", " + Reminder.getStateDescription(thingState, state, this))
            }
        }
    }

    private fun initUiStartDoing() {
        @Thing.Type val thingType = mThing!!.type
        val fl: FrameLayout = f(R.id.fl_start_doing_as_bt)!!
        if (mType == UPDATE && mEditable && thingType >= Thing.NOTE && thingType <= Thing.GOAL
            && !(thingType == Thing.HABIT && mHabit != null && mHabit!!.isPaused())
        ) {
            fl.visibility = View.VISIBLE
            fl.setOnClickListener {
                @Thing.Type val thingType = mThing!!.type
                if (thingType != Thing.REMINDER && thingType != Thing.HABIT) {
                    val helper = ThingDoingHelper(this@DetailActivity, mThing)
                    helper.tryToOpenStartDoingActivityUser(getAccentBackground())
                } else {
                    val tddf = ThingDoingDialogFragment()
                    tddf.setThing(mThing)
                    tddf.show(fragmentManager, ThingDoingDialogFragment.TAG)
                }
            }

            val d1: Drawable = ContextCompat.getDrawable(this, R.drawable.act_start_doing)!!
            val d2: Drawable = d1.mutate()
            d2.setColorFilter(
                ContextCompat.getColor(this, R.color.black_54p),
                PorterDuff.Mode.SRC_IN
            )
            val iv: ImageView = f(R.id.iv_doing_detail)!!
            iv.setImageDrawable(d2)

            fl.foreground = BackgroundUtil.circularRipple(BackgroundUtil.RIPPLE_DARK)
        }
    }

    override fun setActionbar() {
        setSupportActionBar(mActionbar)
        if (supportActionBar != null) {
            supportActionBar!!.title = null
        }
        mIbBack!!.setOnClickListener {
            returnToThingsActivity(true, true)
        }
    }

    override fun setEvents() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                returnToThingsActivity(true, true)
            }
        })

        setScrollEvents()

        val window: Window = window

        // set keyboard events.
        if (mEditable) {
            KeyboardUtil.addKeyboardCallback(window, object : KeyboardUtil.KeyboardCallback {
                override fun onKeyboardShow(keyboardHeight: Int) {
                    updateBottomBarShadow()
                }

                override fun onKeyboardHide() {
                    updateBottomBarShadow()
                    quickRemindPicker!!.dismiss()
                }
            })
            KeyboardUtil.addKeyboardCallback(window, object : KeyboardUtil.KeyboardCallback {

                val screenHeightDivide6: Int = DisplayUtil.getScreenSize(mApp).y / 6

                override fun onKeyboardShow(keyboardHeight: Int) {
                    if (mRvCheckList == null || mRvCheckList!!.visibility != View.VISIBLE) {
                        var toScroll = DisplayUtil.getCursorY(mEtContent)
                        toScroll += mEtTitle!!.height
                        if (mRvImageAttachment != null && mRvImageAttachment!!.isVisible) {
                            toScroll += mRvImageAttachment!!.height
                        }
                        val fToScroll = toScroll
                        mScrollView!!.post {
                            mScrollView!!.scrollTo(0, fToScroll - screenHeightDivide6)
                        }
                    }
                }

                override fun onKeyboardHide() {
                    // No-op — padding restoration is handled by the inset chain.
                }
            })
        }

        if (mShouldAutoLink) {
            mEtContent!!.setOnTouchListener(mSpannableTouchListener)
            mEtContent!!.setOnClickListener(mEtContentClickListener)
            mEtContent!!.setOnLongClickListener(mEtContentLongClickListener)
        }

        if (mEditable) {
            LineSpacingHelper.helpCorrectSpacingForNewLine(mEtContent)
            if (!DeviceUtil.isFlyme()) {
                val appAccent = ContextCompat.getColor(this, R.color.app_accent)
                val cursorWidth = (1.5 * screenDensity).toInt()
                val lastLineCursorHeightVary = (-1 * screenDensity).toInt()
                LineSpacingHelper.setTextCursorDrawable(
                    mEtContent, appAccent, cursorWidth,
                    (-4 * screenDensity).toInt(), lastLineCursorHeightVary
                )
            }

            setEditTextWatchers()
            setColorPickerEvent()
            setQuickRemindEvents()
        }

        shouldAddToActionList = true
    }

    private fun setEditTextWatchers() {
        mEtTitle!!.addTextChangedListener(ActionTextWatcher(ThingAction.UPDATE_TITLE))
        mEtContent!!.addTextChangedListener(ActionTextWatcher(ThingAction.UPDATE_CONTENT))
    }

    private fun setChecklistExpandShrinkEvent() {
        mCheckListAdapter!!.setExpandShrinkCallback(object : CheckListAdapter.ExpandShrinkCallback {
            override fun updateChecklistHeight(
                expand: Boolean, items: MutableList<String?>?, isClickingExpandOrShrink: Boolean
            ) {
                expandOrShrinkChecklistFinishedItems(expand, items, isClickingExpandOrShrink)
            }
        })
    }

    private fun expandOrShrinkChecklistFinishedItems(
        expand: Boolean, items: MutableList<String?>?, isClickingExpandOrShrink: Boolean
    ) {
        if (isClickingExpandOrShrink) {
            val focus: View? = currentFocus
            focus?.clearFocus()
        }
        val vlp: ViewGroup.LayoutParams = mRvCheckList!!.layoutParams
        vlp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        mRvCheckList!!.requestLayout()
    }

    private fun setMoveChecklistEvent() {
        if (!mEditable) return

        if (mChecklistTouchHelper == null) {
            mChecklistTouchHelper = ItemTouchHelper(CheckListTouchCallback())
        }

        mTvMoveChecklistAsBt!!.setOnClickListener {
            val isDragging = mCheckListAdapter!!.isDragging()
            if (!isDragging) {
                mTvMoveChecklistAsBt!!.setText(R.string.act_back_from_move_checklist)
                mTvMoveChecklistAsBt!!.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    R.drawable.act_back_from_move_checklist, 0, 0, 0
                )
                mCheckListAdapter!!.setDragging(true)
                mChecklistTouchHelper!!.attachToRecyclerView(mRvCheckList)
            } else {
                mTvMoveChecklistAsBt!!.setText(R.string.act_move_check_list)
                mTvMoveChecklistAsBt!!.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    R.drawable.act_move_checklist, 0, 0, 0
                )
                mCheckListAdapter!!.setDragging(false)
                mChecklistTouchHelper!!.attachToRecyclerView(null)
            }
            mCheckListAdapter!!.notifyDataSetChanged()
        }

        mCheckListAdapter!!.setIvStateTouchCallback(object : CheckListAdapter.IvStateTouchCallback {
            override fun onTouch(pos: Int) {
                mChecklistTouchHelper!!.startDrag(
                    mRvCheckList!!.findViewHolderForAdapterPosition(pos)!!
                )
            }
        })
    }

    fun updateDescriptions(color: Int) {
        var title: String
        if (mType == CREATE) {
            title = getString(R.string.title_create_thing)
        } else {
            title = if (mEditable) {
                getString(R.string.title_edit_thing)
            } else {
                ""
            }
            if (!LocaleUtil.isChinese(mApp)) {
                title += " "
            }
            title += Thing.getTypeStr(getThingTypeAfter(), mApp)
        }
        mFlRoot!!.contentDescription = title
        val bmd: BitmapDrawable? = AppCompatResources.getDrawable(this, R.mipmap.ic_launcher) as BitmapDrawable?
        if (bmd != null) {
            val bm: Bitmap = bmd.bitmap
            try {
                setTaskDescription(ActivityManager.TaskDescription(title, bm, color))
            } catch (_: Exception) {
            }
        }
    }

    @Thing.Type
    private fun getThingTypeAfter(): Int {
        if (mHabitFinishedThisTime) return Thing.HABIT
        if (mThing!!.state != Thing.UNDERWAY) return mThing!!.type
        val time = rhParams.getReminderTime()
        if (cbQuickRemind!!.isChecked) {
            return if (mReminder != null && mReminder!!.notifyTime == time) {
                mThing!!.type
            } else {
                if (rhParams.habitDetail != null) {
                    Thing.HABIT
                } else Reminder.getType(rhParams.getReminderTime(), System.currentTimeMillis())
            }
        } else {
            when (@Thing.Type val typeBefore: Int = mThing!!.type) {
                Thing.REMINDER, Thing.GOAL -> {
                    if (mReminder == null) {
                        return typeBefore
                    }
                    val reminderState = mReminder!!.state
                    return if ((reminderState == Reminder.REMINDED || reminderState == Reminder.EXPIRED)
                        && mReminder!!.notifyTime == time
                    ) {
                        typeBefore
                    } else {
                        Thing.NOTE
                    }
                }
                Thing.HABIT -> {
                    return Thing.NOTE
                }
                else -> {
                    return typeBefore
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        @Thing.Type val thingType: Int = mThing!!.type
        if (thingType == Thing.HEADER || thingType >= Thing.NOTIFICATION_UNDERWAY) {
            return true
        }
        val inflater = menuInflater
        if (mType == CREATE) {
            inflater.inflate(R.menu.menu_detail_create, menu)
        } else {
            val state = mThing!!.state
            if (state == Thing.UNDERWAY) {
                if (thingType == Thing.HABIT) {
                    val habit: Habit = HabitDAO.getInstance(applicationContext)!!
                        .getHabitById(mThing!!.id)!!
                    if (habit.allowFinish()) {
                        inflater.inflate(R.menu.menu_detail_habit_allow_finish, menu)
                    } else {
                        inflater.inflate(R.menu.menu_detail_habit_normal, menu)
                    }
                } else {
                    inflater.inflate(R.menu.menu_detail_underway, menu)
                }
                if (CheckListHelper.isCheckListStr(mThing!!.content)) {
                    toggleCheckListActionItem(menu, true)
                }
                togglePrivateThingActionItem(menu, !mThing!!.isPrivate())
                toggleStickyActionItem(menu)
                toggleOngoingActionItem(menu)
                togglePauseResumeHabitActionItem(menu)
            } else if (state == Thing.FINISHED) {
                inflater.inflate(R.menu.menu_detail_finished, menu)
                if (thingType != Thing.HABIT) {
                    menu.findItem(R.id.act_check_habit_detail).isVisible = false
                }
            } else {
                inflater.inflate(R.menu.menu_detail_deleted, menu)
                if (thingType != Thing.HABIT) {
                    menu.findItem(R.id.act_check_habit_detail).isVisible = false
                }
            }
        }
        updateUndoRedoActionButtonState()
        tintMenuIcons(BackgroundUtil.isLight(getAccentColor()))
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val itemId = item.itemId
        if (itemId == R.id.act_add_attachment) {
            AddAttachmentDialogFragment.newInstance().show(
                fragmentManager, AddAttachmentDialogFragment.TAG
            )
        } else if (itemId == R.id.act_check_list) {
            toggleCheckList()
        } else if (itemId == R.id.act_change_color) {
            mColorPicker!!.setAnchor(findViewById(R.id.act_change_color))
            mColorPicker!!.show()
        } else if (itemId == R.id.act_color_info) {
            showColorInfoDialog()
        } else if (itemId == R.id.act_set_as_private_thing) {
            togglePrivateThing()
        } else if (itemId == R.id.act_undo) {
            undoOrRedo(mActionList!!.undo(), true)
        } else if (itemId == R.id.act_redo) {
            undoOrRedo(mActionList!!.redo(), false)
        } else if (itemId == R.id.act_check_habit_detail) {
            if (mHabit != null) {
                val hddf = HabitDetailDialogFragment.newInstance()
                mHabit = HabitDAO.getInstance(this)!!.getHabitById(mHabit!!.id)
                hddf.setHabit(mHabit)
                hddf.show(fragmentManager, HabitDetailDialogFragment.TAG)
            }
        } else if (itemId == R.id.act_check_update_habit_record) {
            if (mHabit != null) {
                val hrdf = HabitRecordDialogFragment()
                mHabit = HabitDAO.getInstance(this)!!.getHabitById(mHabit!!.id)
                hrdf.setHabit(mHabit)
                hrdf.setEditable(mEditable)
                hrdf.show(fragmentManager, HabitRecordDialogFragment.TAG)
            }
        } else if (itemId == R.id.act_share) {
            chooseHowToShareThing()
        } else if (itemId == R.id.act_finish_this_time_habit) {
            HabitDAO.getInstance(mApp)!!.finishOneTime(mHabit)
            mHabitFinishedThisTime = true
            rhParams.habitType = mHabit!!.type
            rhParams.habitDetail = mHabit!!.detail
            returnToThingsActivity(true, false)
        } else if (itemId == R.id.act_finish) {
            returnToThingsActivity(Thing.FINISHED)
        } else if (itemId == R.id.act_delete) {
            returnToThingsActivity(Thing.DELETED)
        } else if (itemId == R.id.act_restore) {
            returnToThingsActivity(Thing.UNDERWAY)
        } else if (itemId == R.id.act_copy_content) {
            copyContent()
        } else if (itemId == R.id.act_export) {
            ThingExporter.startExporting(this@DetailActivity, getAccentColor(), mThing)
        } else if (itemId == R.id.act_abandon_new_thing) {
            createFailed(Def.Communication.RESULT_ABANDON_NEW_THING)
        } else if (itemId == R.id.act_sticky) {
            stickyOrCancel()
        } else if (itemId == R.id.act_ongoing_thing) {
            ongoingOrCancel()
        } else if (itemId == R.id.act_pause_resume_habit) {
            pauseOrResumeHabit()
        }
        return super.onOptionsItemSelected(item)
    }

    override fun finish() {
        if (mThing == null) {
            super.finish()
            return
        }

        val detailActivities: MutableList<Long?> = App.getRunningDetailActivities()
        detailActivities.remove(mThing!!.id)
        mRemoveDetailActivityInstance = true

        if (mType == CREATE) {
            createActivitiesCount--
            mMinusCreateActivitiesCount = true
        }
        dontSaveAfterOnPause = true
        super.finish()
    }

    private fun toggleCheckListActionItem(menu: Menu, toDisable: Boolean) {
        val item: MenuItem = menu.findItem(R.id.act_check_list)
        if (toDisable) {
            item.setIcon(R.drawable.act_disable_check_list)
            item.title = getString(R.string.act_disable_check_list)
        } else {
            item.setIcon(R.drawable.act_enable_check_list)
            item.title = getString(R.string.act_enable_check_list)
        }
    }

    private fun togglePrivateThingActionItem(menu: Menu, set: Boolean) {
        val item: MenuItem = menu.findItem(R.id.act_set_as_private_thing)
        if (set) {
            item.setTitle(R.string.act_set_as_private_thing)
        } else {
            item.setTitle(R.string.act_cancel_private_thing)
        }
    }

    private fun toggleStickyActionItem(menu: Menu) {
        val item: MenuItem = menu.findItem(R.id.act_sticky)
        if (mThing!!.location < 0) {
            item.setTitle(R.string.act_cancel_sticky)
        } else {
            item.setTitle(R.string.act_sticky_on_top)
        }
    }

    private fun toggleOngoingActionItem(menu: Menu) {
        if (mThing == null) return
        val ongoingId = FrequentSettings.getLong(Def.Meta.KEY_ONGOING_THING_ID)
        val item: MenuItem = menu.findItem(R.id.act_ongoing_thing)
        if (ongoingId == mThing!!.id) {
            item.setTitle(R.string.act_cancel_set_thing_as_ongoing)
        } else {
            item.setTitle(R.string.act_set_thing_as_ongoing)
        }
    }

    private fun togglePauseResumeHabitActionItem(menu: Menu) {
        if (mHabit == null) return
        val item: MenuItem = menu.findItem(R.id.act_pause_resume_habit)
        if (mHabit!!.isPaused()) {
            item.setTitle(R.string.act_resume_habit)
        } else {
            item.setTitle(R.string.act_pause_habit)
        }
    }

    private fun toggleCheckList() {
        val before: String
        if (mRvCheckList!!.isVisible) {
            before = CheckListHelper.toCheckListStr(mCheckListAdapter!!.getItems())
            toggleCheckListActionItem(mActionbar!!.menu, false)
            mEtContent!!.visibility = View.VISIBLE
            mRvCheckList!!.visibility = View.GONE
            if (mLlMoveChecklist != null) {
                // don't know why this is possible but some user's log showed that this can happen
                mLlMoveChecklist!!.visibility = View.GONE
            }
            mChecklistTouchHelper!!.attachToRecyclerView(null)

            val contentStr: String = CheckListHelper.toContentStr(mCheckListAdapter!!.getItems())
            val temp = shouldAddToActionList
            shouldAddToActionList = false
            mEtContent!!.setText(contentStr)
            shouldAddToActionList = temp

            if (contentStr.isEmpty()) {
                KeyboardUtil.showKeyboard(mEtContent)
            } else {
                KeyboardUtil.hideKeyboard(currentFocus)
            }
        } else {
            toggleCheckListActionItem(mActionbar!!.menu, true)
            mRvCheckList!!.visibility = View.VISIBLE
            mEtContent!!.visibility = View.GONE
            if (mLlmCheckList != null) {
                mLlMoveChecklist!!.visibility = View.VISIBLE
            }

            val content: String = mEtContent!!.text.toString()
            before = content
            val items: MutableList<String?> = CheckListHelper.toCheckListItems(content, true)
            var focusFirst = false
            if (items.size == 2 && items[0]!! == "0") {
                focusFirst = true
            }

            if (mCheckListAdapter == null) {
                mCheckListAdapter = CheckListAdapter(
                    this, CheckListAdapter.EDITTEXT_EDITABLE, items
                )
                if (mShouldAutoLink) {
                    mCheckListAdapter!!.setEtTouchListener(mSpannableTouchListener)
                    mCheckListAdapter!!.setEtClickListener(mEtContentClickListener)
                    mCheckListAdapter!!.setEtLongClickListener(mEtContentLongClickListener)
                }
                mLlmCheckList = CannotScrollLinearLayoutManager(this)
                mCheckListAdapter!!.setItemsChangeCallback(CheckListItemsChangeCallback())
                mCheckListAdapter!!.setActionCallback(CheckListActionCallback())
            } else {
                mCheckListAdapter!!.setItems(items)
            }
            mCheckListAdapter!!.setShouldAutoLink(mShouldAutoLink)
            mCheckListAdapter!!.setThingColor(mThing!!.getColor())
            setChecklistExpandShrinkEvent()
            mRvCheckList!!.adapter = mCheckListAdapter
            mRvCheckList!!.layoutManager = mLlmCheckList

            setMoveChecklistEvent()

            if (focusFirst) {
                mRvCheckList!!.post {
                    val holder = mRvCheckList!!.findViewHolderForAdapterPosition(0) as CheckListAdapter.EditTextHolder
                    KeyboardUtil.showKeyboard(holder.et)
                }
            } else {
                KeyboardUtil.hideKeyboard(currentFocus)
            }
        }
        if (shouldAddToActionList) {
            mActionList!!.addAction(ThingAction(ThingAction.TOGGLE_CHECKLIST, before, null))
        }
    }

    private fun cannotSetAsPrivateThing(): Boolean {
        return mEtTitle!!.text.toString().isEmpty()
    }

    private fun isPrivateThing(): Boolean {
        val start: Drawable? = mEtTitle!!.compoundDrawables[0]
        return start != null
    }

    private fun togglePrivateThing() {
        val sp: SharedPreferences = getSharedPreferences(Def.Meta.PREFERENCES_NAME, MODE_PRIVATE)
        val pwd: String? = sp.getString(Def.Meta.KEY_PRIVATE_PASSWORD, null)
        if (pwd == null) {
            warnNoPassword()
        } else {
            val isPrivateThing = isPrivateThing()
            if (isPrivateThing) {
                tryToCancelPrivateThing()
            } else {
                setAsPrivateThingUiAndAddAction()
                togglePrivateThingActionItem(mActionbar!!.menu, false)
            }
        }
    }

    private fun warnNoPassword() {
        val adf = AlertDialogFragment()
        adf.setShowCancel(false)

        val accent: ThingBackground? = getAccentBackground()
        adf.setTitleBackground(accent)
        adf.setConfirmBackground(accent)

        adf.setTitle(getString(R.string.cannot_set_as_private_thing_title))
        adf.setContent(getString(R.string.warning_should_set_password_first))
        adf.show(fragmentManager, AlertDialogFragment.TAG)
    }

    private fun tryToCancelPrivateThing() {
        if (!mThing!!.isPrivate()) {
            cancelPrivateThingUiAndAddAction()
            if (shouldAddToActionList) {
                mActionList!!.addAction(ThingAction(ThingAction.TOGGLE_PRIVATE, null, null))
            }
            return
        }

        val cp: String = getSharedPreferences(Def.Meta.PREFERENCES_NAME, MODE_PRIVATE)
            .getString(Def.Meta.KEY_PRIVATE_PASSWORD, null)!!
        val shouldAddToActionList = this.shouldAddToActionList
        AuthenticationHelper.authenticate(
            this, getAccentBackground(), getString(R.string.act_cancel_private_thing), cp,
            object : AuthenticationHelper.AuthenticationCallback {
                override fun onAuthenticated() {
                    cancelPrivateThingUiAndAddAction()
                    if (shouldAddToActionList) {
                        mActionList!!.addAction(ThingAction(ThingAction.TOGGLE_PRIVATE, null, null))
                    }
                }

                override fun onCancel() {}
            })
    }

    private fun cancelPrivateThingUiAndAddAction() {
        togglePrivateThingActionItem(mActionbar!!.menu, true)

        mEtTitle!!.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)

        val paddingSide = (screenDensity * 20).toInt()
        mEtTitle!!.setPadding(paddingSide, mEtTitle!!.paddingTop, paddingSide, 0)
    }

    private fun setAsPrivateThingUiAndAddAction() {
        mEtTitle!!.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_locked_small, 0, 0, 0)

        val paddingSide = (screenDensity * 16).toInt()
        mEtTitle!!.setPadding(paddingSide, mEtTitle!!.paddingTop, paddingSide, 0)

        if (shouldAddToActionList) {
            mActionList!!.addAction(ThingAction(ThingAction.TOGGLE_PRIVATE, null, null))
        }
    }

    private fun alertNoTitleWhenSetPrivateThing() {
        val adf = AlertDialogFragment()
        adf.setShowCancel(false)

        val accent: ThingBackground? = getAccentBackground()
        adf.setTitleBackground(accent)
        adf.setConfirmBackground(accent)

        adf.setTitle(getString(R.string.cannot_set_as_private_thing_title))
        adf.setContent(getString(R.string.warning_title_should_not_be_empty))
        adf.show(fragmentManager, AlertDialogFragment.TAG)
    }

    private fun updateUndoRedoActionButtonState() {
        val undoItem: MenuItem = mActionbar!!.menu.findItem(R.id.act_undo) ?: return
        undoItem.isEnabled = mActionList!!.canUndo()

        val redoItem: MenuItem = mActionbar!!.menu.findItem(R.id.act_redo)
        redoItem.isEnabled = mActionList!!.canRedo()
    }

    private fun undoOrRedo(action: ThingAction?, undo: Boolean) {
        if (action == null) return
        shouldAddToActionList = false
        val to: Any? = if (undo) action.getBefore() else action.getAfter()
        when (action.getType()) {
            ThingAction.UPDATE_TITLE ->
                undoOrRedoTitleContent(action, undo, mEtTitle!!)
            ThingAction.UPDATE_CONTENT ->
                undoOrRedoTitleContent(action, undo, mEtContent!!)
            ThingAction.TOGGLE_CHECKLIST -> {
                toggleCheckList()
                val from: String = action.getBefore() as String
                if (CheckListHelper.isCheckListStr(from) && undo) {
                    mCheckListAdapter!!.setItems(CheckListHelper.toCheckListItems(from, false))
                }
            }
            ThingAction.UPDATE_CHECKLIST -> {
                mCheckListAdapter!!.setItems(CheckListHelper.toCheckListItems(to as String?, false))
                if (!mCheckListAdapter!!.isExpanded()) {
                    mRvCheckList!!.post {
                        expandOrShrinkChecklistFinishedItems(
                            false, mCheckListAdapter!!.getItems(), false
                        )
                    }
                }
            }
            ThingAction.MOVE_CHECKLIST -> {
                val from: Any = if (undo) action.getAfter()!! else action.getBefore()!!
                moveChecklist(from as Int, to as Int)
            }
            ThingAction.UPDATE_COLOR -> {
                val bgTarget: ThingBackground = when (to) {
                    is ThingBackground -> to
                    is Int -> ThingBackground.pure(to)
                    else -> {
                        shouldAddToActionList = true
                        updateUndoRedoActionButtonState()
                        return
                    }
                }
                mColorPicker!!.pickForBackground(bgTarget)
                changeBackground(bgTarget)
            }
            ThingAction.ADD_ATTACHMENT ->
                // before: attachmentTypePathName, after: position
                undoOrRedoAddAttachment(action, undo)
            ThingAction.DELETE_ATTACHMENT ->
                // before:position, after:attachmentTypePathName
                undoOrRedoDeleteAttachment(action, undo)
            ThingAction.MOVE_ATTACHMENT -> {
                val from: Any = if (undo) action.getAfter()!! else action.getBefore()!!
                moveAttachment(
                    from as Int, to as Int,
                    action.getExtras()!!.getBoolean(ThingAction.KEY_ATTACHMENT_TYPE)
                )
            }
            ThingAction.TOGGLE_REMINDER_OR_HABIT ->
                cbQuickRemind!!.toggle()
            ThingAction.UPDATE_REMINDER_OR_HABIT ->
                undoOrRedoReminderHabit(action, undo)
            ThingAction.TOGGLE_PRIVATE ->
                togglePrivateThing()
            else -> {}
        }
        updateUndoRedoActionButtonState()
        shouldAddToActionList = true
    }

    private fun undoOrRedoTitleContent(action: ThingAction, undo: Boolean, et: EditText) {
        val to: Any = if (undo) action.getBefore()!! else action.getAfter()!!
        val cursorPos: Int = if (undo) {
            action.getExtras()!!.getInt(ThingAction.KEY_CURSOR_POS_BEFORE)
        } else {
            action.getExtras()!!.getInt(ThingAction.KEY_CURSOR_POS_AFTER)
        }
        et.setText(to.toString())
        et.setSelection(cursorPos)
    }

    private fun undoOrRedoAddAttachment(action: ThingAction, undo: Boolean) {
        // before: attachmentTypePathName, after: position
        val atpn: String = action.getBefore() as String
        val position: Int = action.getAfter() as Int
        if (undo) {
            if (atpn.startsWith(AttachmentHelper.AUDIO.toString())) {
                notifyAudioAttachmentsChanged(false, position)
            } else {
                notifyImageAttachmentsChanged(false, position)
            }
        } else {
            attachmentTypePathName = atpn
            addAttachment(position)
        }
    }

    private fun undoOrRedoDeleteAttachment(action: ThingAction, undo: Boolean) {
        // before:position, after:attachmentTypePathName
        val position: Int = action.getBefore() as Int
        val atpn: String = action.getAfter() as String
        if (undo) {
            attachmentTypePathName = atpn
            addAttachment(position)
        } else {
            if (atpn.startsWith(AttachmentHelper.AUDIO.toString())) {
                notifyAudioAttachmentsChanged(false, position)
            } else {
                notifyImageAttachmentsChanged(false, position)
            }
        }
    }

    private fun undoOrRedoReminderHabit(action: ThingAction, undo: Boolean) {
        val to: ReminderHabitParams = (if (undo) action.getBefore() else action.getAfter()) as ReminderHabitParams
        rhParams = ReminderHabitParams(to)

        val isCheckedBefore: Boolean = action.getExtras()!!.getBoolean(ThingAction.KEY_CHECKBOX_STATE)
        if (!isCheckedBefore) {
            cbQuickRemind!!.toggle()
        }
        tvQuickRemind!!.text = rhParams.getDateTimeStr()

        if (undo) {
            val pickedBefore: Int = action.getExtras()!!.getInt(ThingAction.KEY_PICKED_BEFORE)
            quickRemindPicker!!.pickForUI(pickedBefore)
        } else {
            val pickedAfter: Int = action.getExtras()!!.getInt(ThingAction.KEY_PICKED_AFTER)
            quickRemindPicker!!.pickForUI(pickedAfter)
        }
        updateDescriptions(getAccentColor())
        updateBackButton()
    }

    private fun chooseHowToShareThing() {
        if (!canShareThing()) {
            mNormalSnackbar!!.setMessage(getString(R.string.alert_cannot_share_empty_thing))
            mFlRoot!!.postDelayed(mShowNormalSnackbar, KeyboardUtil.HIDE_DELAY.toLong())
            return
        }

        val todf = TwoOptionsDialogFragment()
        todf.setStartAction(R.drawable.act_share_text_image, R.string.act_share_thing_text_image
        ) {
            todf.dismiss()
            SendInfoHelper.shareThing(this@DetailActivity, mThing)
        }
        todf.setEndAction(R.drawable.act_take_long_screenshot, R.string.act_share_thing_screenshot
        ) {
            todf.dismiss()
            shareThingInScreenshot()
        }
        todf.show(fragmentManager, TwoOptionsDialogFragment.TAG)
    }

    private fun shareThingInScreenshot() {
        val ldf = LoadingDialogFragment()
        val color = getAccentColor()
        ldf.setAccentColor(color)
        ldf.setTitle(getString(R.string.please_wait))
        ldf.setContent(getString(R.string.generating_screenshot))
        ldf.show(fragmentManager, LoadingDialogFragment.TAG)

        val typeInfoLayout: View = f(R.id.ll_type_info_screenshot)!!
        val didList: List<Int?> = prepareForScreenshot(typeInfoLayout)
        ScreenshotHelper.startScreenshot(mScrollView, color,
            object : ScreenshotHelper.ShareCallback(
                this, ldf, SendInfoHelper.getShareThingTitle(this, mThing)
            ) {
                override fun onTaskDone(file: File?) {
                    super.onTaskDone(file)
                    ScreenshotHelper.hideTypeInfo(typeInfoLayout)
                    ScreenshotHelper.updateThingUiAfterScreenshot(
                        didList,
                        mEtTitle, mEtContent,
                        mRvCheckList, mCheckListAdapter, mLlMoveChecklist,
                        mImageAttachmentAdapter,
                        mRvAudioAttachment, mAudioAttachmentAdapter
                    )
                }
            })
    }

    private fun prepareForScreenshot(typeInfoLayout: View): List<Int?> {
        ScreenshotHelper.showTypeInfo(
            typeInfoLayout, mThing!!.id, mThing!!.type,
            getThingTypeAfter(), mThing!!.state, rhParams
        )
        return ScreenshotHelper.updateThingUiBeforeScreenshot(
            mEditable, mEtTitle, mEtContent,
            mRvCheckList, mCheckListAdapter, mLlMoveChecklist,
            mRvImageAttachment, mImageAttachmentAdapter,
            mRvAudioAttachment, mAudioAttachmentAdapter
        )
    }

    private fun canShareThing(): Boolean {
        var canShare = true
        if (mEtTitle!!.text.toString().isEmpty()
            && mRvImageAttachment!!.visibility != View.VISIBLE
            && mRvAudioAttachment!!.visibility != View.VISIBLE
        ) {
            if (mEtContent!!.isVisible) {
                if (mEtContent!!.text.toString().isEmpty()) {
                    canShare = false
                }
            } else if (mRvCheckList!!.isVisible) {
                if (mEditable && mCheckListAdapter!!.itemCount == 1) {
                    canShare = false
                }
            }
        }
        return canShare
    }

    private fun copyContent() {
        var content = ""
        if (mEtContent!!.isVisible) {
            content = mEtContent!!.text.toString()
        } else if (mRvCheckList != null && mRvCheckList!!.isVisible
            && mCheckListAdapter != null
        ) {
            content = CheckListHelper.toContentStr(
                CheckListHelper.toCheckListStr(mCheckListAdapter!!.getItems()), "X  ", "√  "
            )
        }

        val clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = ClipData.newPlainText(
            getString(R.string.act_copy_content), content
        )
        clipboardManager.setPrimaryClip(clipData)
        Toast.makeText(this, R.string.success_clipboard, Toast.LENGTH_SHORT).show()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        val newNightModeMask = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val appearanceChanged = newNightModeMask != mNightModeMask
        mNightModeMask = newNightModeMask

        if (appearanceChanged) {
            handleAppearanceModeChanged()
        } else if (mEditable) {
            dismissEditableOverlays()
        }

        setSpans()

        if (mRvImageAttachment!!.isVisible) {
            val size = mImageAttachmentAdapter!!.itemCount
            AttachmentHelper.setImageRecyclerViewHeight(mRvImageAttachment, size, mMaxSpanImage)
            mImageLayoutManager!!.spanCount = if (size < mMaxSpanImage) size else mMaxSpanImage
            mImageAttachmentAdapter!!.notifyDataSetChanged()
        }

        if (mRvAudioAttachment!!.isVisible) {
            AttachmentHelper.setAudioRecyclerViewHeight(
                mRvAudioAttachment,
                mAudioAttachmentAdapter!!.itemCount, mSpanAudio
            )
            mAudioLayoutManager!!.spanCount = mSpanAudio
            mAudioAttachmentAdapter!!.notifyDataSetChanged()
        }
    }

    private fun handleAppearanceModeChanged() {
        val quickPickedIndex = if (mEditable) getQuickRemindPickedIndexForRecreate() else -1
        val pickedBackground = if (mEditable) {
            mColorPicker?.getPickedBackground() ?: getAccentBackground()
        } else {
            null
        }

        delegate.applyDayNight()
        mActionbar?.dismissPopupMenus()
        dismissDetailDialogFragmentsForAppearance()

        if (mEditable) {
            dismissEditableOverlays()
            createEditablePickers()
            setColorPickerEvent()
            setQuickRemindPickerEvent()
            mColorPicker!!.pickForBackground(pickedBackground)
            quickRemindPicker!!.pickForUI(quickPickedIndex)
            createDateTimeDialogFragment()
        }

        invalidateOptionsMenu()
    }

    private fun getQuickRemindPickedIndexForRecreate(): Int {
        val pickedIndex = quickRemindPicker?.getPickedIndex() ?: -1
        if (pickedIndex >= 0) return pickedIndex

        val reminderAfterTime = rhParams.reminderAfterTime
        if (reminderAfterTime != null) {
            val inferred = getQuickRemindIndex(reminderAfterTime)
            if (inferred >= 0) return inferred
        }

        if (rhParams.reminderInMillis != -1L || rhParams.habitDetail != null) {
            return 9
        }
        return 8
    }

    private fun getQuickRemindIndex(reminderAfterTime: IntArray): Int {
        val times = intArrayOf(1, 1, 1, 2, 1, 2, 1, 30, 15)
        val types = intArrayOf(
            Calendar.YEAR, Calendar.MONTH, Calendar.WEEK_OF_YEAR,
            Calendar.DATE, Calendar.DATE, Calendar.HOUR_OF_DAY,
            Calendar.HOUR, Calendar.MINUTE, Calendar.MINUTE
        )
        for (i in times.indices) {
            if (reminderAfterTime[0] == types[i] && reminderAfterTime[1] == times[i]) {
                return i
            }
        }
        return -1
    }

    private fun dismissEditableOverlays() {
        mColorPicker?.dismiss()
        quickRemindPicker?.dismiss()
        mNormalSnackbar?.dismiss()
    }

    private fun dismissDetailDialogFragmentsForAppearance() {
        val tags = arrayOf(
            AddAttachmentDialogFragment.TAG,
            AlertDialogFragment.TAG,
            AttachmentInfoDialogFragment.TAG,
            AudioRecordDialogFragment.TAG,
            CameraColorSamplingDialogFragment.TAG,
            ChooserDialogFragment.TAG,
            ColorInfoDialogFragment.TAG,
            DateTimeDialogFragment.TAG,
            GradientOrientationDialogFragment.TAG,
            HabitDetailDialogFragment.TAG,
            HabitRecordDialogFragment.TAG,
            LoadingDialogFragment.TAG,
            LongTextDialogFragment.TAG,
            PatternLockDialogFragment.TAG,
            ThingDoingDialogFragment.TAG,
            TwoOptionsDialogFragment.TAG
        )
        for (tag in tags) {
            val fragment = fragmentManager.findFragmentByTag(tag)
            if (fragment is android.app.DialogFragment) {
                fragment.dismissAllowingStateLoss()
            }
        }
    }

    private var dontSaveAfterOnPause: Boolean = false

    override fun onStart() {
        super.onStart()
        updateDetailActivityVisibility(true)
    }

    override fun onResume() {
        super.onResume()
        refreshFromExternalUpdateIfNeeded()
    }

    override fun onPause() {
        super.onPause()
        if (mEditable && mExecutor != null && !dontSaveAfterOnPause
            && FrequentSettings.getBoolean(Def.Meta.KEY_AUTO_SAVE_EDITS)
        ) {
            val b = saveAfterOnPause()
            if (!savedAfterOnPause) {
                savedAfterOnPause = b
            }
        }
    }

    override fun onStop() {
        updateDetailActivityVisibility(false)
        super.onStop()
    }

    private var savedAfterOnPause: Boolean = false

    private fun saveAfterOnPause(): Boolean {
        // will not create or update reminder or habit

        val title: String      = getThingTitle()
        val content: String    = getThingContent()
        val attachment: String = getThingAttachment()
        if (willBeEmptyThing(title, content, attachment)) {
            return false
        }

        mThing!!.title = title
        mThing!!.content = content
        mThing!!.attachment = attachment
        mThing!!.thingCardSpanMode = mThingCardSpanMode
        mThing!!.thingCardImagePlacement = mThingCardImagePlacement
        if (mChangeBackgroundTo != null) {
            mThing!!.setBackground(mChangeBackgroundTo)
        } else {
            mThing!!.setColor(if (mChangeColorTo != 0) mChangeColorTo else getAccentColor())
        }

        val currentTime = System.currentTimeMillis()
        mThing!!.updateTime = currentTime

        val intent = Intent()

        var resultCode = Def.Communication.RESULT_NO_UPDATE
        if (mType == CREATE && !savedAfterOnPause) {
            mThing!!.type = Thing.NOTE
            mThing!!.createTime = currentTime
            intent.putExtra(Def.Communication.KEY_THING, mThing)

            resultCode = Def.Communication.RESULT_CREATE_THING_DONE
            intent.putExtra(Def.Communication.KEY_RESULT_CODE, resultCode)

            val change: Boolean = ThingManager.getInstance(mApp)!!.create(mThing, true, true)
            intent.putExtra(Def.Communication.KEY_CALL_CHANGE,  change)
            intent.putExtra(Def.Communication.KEY_CREATED_DONE, true)

            sendBroadCastToUpdateMainUI(intent, resultCode)
        } else {
            // only update color, title, content, attachment and update time now
            if (mType == CREATE) {
                mPosition = App.getThingAndPosition(mApp, mThing!!.id, -1).second ?: -1
            }
            @Thing.Type val typeBefore: Int = mThing!!.type
            var updateResult = -1
            if (mPosition != -1) {
                intent.putExtra(Def.Communication.KEY_POSITION, mPosition)
                updateResult = ThingManager.getInstance(mApp)!!.update(
                    typeBefore, mThing, mPosition, true
                )
            } else {
                ThingDAO.getInstance(mApp)!!.update(typeBefore, mThing, true, true)
            }
            intent.putExtra(Def.Communication.KEY_THING, mThing)
            if (mType == UPDATE) {
                intent.putExtra(Def.Communication.KEY_TYPE_BEFORE, typeBefore)
                if (updateResult > 0) {
                    intent.putExtra(Def.Communication.KEY_CALL_CHANGE, updateResult == 1)
                }

                resultCode = Def.Communication.RESULT_UPDATE_THING_DONE_TYPE_SAME
                intent.putExtra(Def.Communication.KEY_RESULT_CODE, resultCode)
                sendBroadCastToUpdateMainUI(intent, resultCode)
            }
        }

        afterCreateOrUpdateThing(intent, resultCode)
        recordRenderedThingSnapshot()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        if (mThing == null) {
            return
        }

        updateDetailActivityVisibility(false)
        unregisterReceiver(mReceiver)
        Log.i(TAG, "onDestroy() called, id[" + mThing!!.id + "]")
        if (!mRemoveDetailActivityInstance) {
            App.getRunningDetailActivities().remove(mThing!!.id)
        }
        if (mType == CREATE && !mMinusCreateActivitiesCount) {
            createActivitiesCount--
        }
    }

    private fun setSpans() {
        val isTablet = DisplayUtil.isTablet(this)
        mMaxSpanImage = if (isTablet) 3 else 2
        mSpanAudio = if (isTablet) 2 else 1
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            mMaxSpanImage += 2
            mSpanAudio++
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode == RESULT_OK) {
            if (requestCode == Def.Communication.REQUEST_CHOOSE_MEDIA_FILE) {
                val uri: Uri? = data!!.data
                Log.d(TAG, "chooseMediaFile uri=$uri scheme=" + (if (uri != null) uri.scheme else "null"))
                var pathName: String? = UriPathConverter.getLocalPathName(this, uri)
                Log.d(TAG, "chooseMediaFile getLocalPathName=$pathName")
                var mimeFallback: String? = null
                if (pathName == null) {
                    val mimeType: String? = contentResolver.getType(uri!!)
                    Log.d(TAG, "chooseMediaFile mimeType=$mimeType")
                    val postfix: String? = FileUtil.getPostfixFromMimeType(this, uri)
                    Log.d(TAG, "chooseMediaFile postfixFromMime=$postfix")
                    if (postfix != null) {
                        pathName = FileUtil.copyUriToFile(this, uri, postfix)
                        Log.d(TAG, "chooseMediaFile copied to=$pathName")
                        mimeFallback = postfix
                    }
                }
                if (pathName == null) {
                    Log.w(TAG, "chooseMediaFile pathName is null, showing error")
                    mNormalSnackbar!!.setMessage(R.string.error_cannot_add_from_network)
                    mFlRoot!!.postDelayed(mShowNormalSnackbar, KeyboardUtil.HIDE_DELAY.toLong())
                    return
                }
                Log.d(TAG, "chooseMediaFile pathName=$pathName postfix=" + FileUtil.getPostfix(pathName) + " mimeFallback=$mimeFallback")
                attachmentTypePathName = getTypePathName(pathName, mimeFallback, uri)
                Log.d(TAG, "chooseMediaFile attachmentTypePathName=$attachmentTypePathName")
                if (attachmentTypePathName == null) {
                    Log.w(TAG, "chooseMediaFile getTypePathName returned null, showing error")
                    mNormalSnackbar!!.setMessage(R.string.error_unsupported_file_type)
                    mFlRoot!!.postDelayed(mShowNormalSnackbar, KeyboardUtil.HIDE_DELAY.toLong())
                    return
                }
            }
            if (cameraOutputUri != null && attachmentTypePathName != null) {
                val localPath = attachmentTypePathName!!.substring(1) // remove type prefix
                try {
                    FileUtil.copyUriToExistingFile(this, cameraOutputUri, localPath)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to copy camera output to local file", e)
                }
                cameraOutputUri = null
            }
            addAttachment(0)
        } else if (resultCode == Def.Communication.RESULT_UPDATE_IMAGE_DONE) {
            val items: List<String?> = data!!.getStringArrayListExtra(
                Def.Communication.KEY_TYPE_PATH_NAME
            )!! as List<String?>
            mImageAttachmentAdapter!!.setItems(items)
            val sizeAfter = items.size
            if (sizeAfter == 0) {
                mRvImageAttachment!!.visibility = View.GONE
                setScrollViewMarginTop(true)
            } else {
                val spanAfter = if (sizeAfter < mMaxSpanImage) sizeAfter else mMaxSpanImage
                AttachmentHelper.setImageRecyclerViewHeight(
                    mRvImageAttachment, items.size, mMaxSpanImage
                )
                mImageLayoutManager!!.spanCount = spanAfter
                mImageAttachmentAdapter!!.notifyDataSetChanged()
            }
        }
    }

    fun showNormalSnackbar(stringRes: Int) {
        mNormalSnackbar!!.setMessage(stringRes)
        mNormalSnackbar!!.show()
    }

    private fun getTypePathName(
            pathName: String,
            mimePostfix: String?,
            sourceUri: Uri? = null
    ): String? {
        val postfix: String? = FileUtil.getPostfix(pathName)
        val normalizedMimePostfix = normalizeAttachmentPostfix(mimePostfix)
        if (AttachmentHelper.isImageFile(postfix)
            || AttachmentHelper.isImageFile(normalizedMimePostfix)
        ) {
            return AttachmentHelper.IMAGE.toString() + pathName
        } else if (AttachmentHelper.isVideoFile(postfix)
            || AttachmentHelper.isVideoFile(normalizedMimePostfix)
        ) {
            val hasVideoTrack = hasVideoTrack(pathName, sourceUri)
            if (hasVideoTrack != false) {
                return AttachmentHelper.VIDEO.toString() + pathName
            } else if (AttachmentHelper.isAudioFile(postfix)
                || AttachmentHelper.isAudioFile(normalizedMimePostfix)
            ) {
                return AttachmentHelper.AUDIO.toString() + pathName
            }
        } else if (AttachmentHelper.isAudioFile(postfix)
            || AttachmentHelper.isAudioFile(normalizedMimePostfix)
        ) {
            return AttachmentHelper.AUDIO.toString() + pathName
        }
        return null
    }

    private fun normalizeAttachmentPostfix(postfix: String?): String? {
        return postfix?.let {
            if (it.startsWith(".")) it.substring(1) else it
        }
    }

    private fun hasVideoTrack(pathName: String, sourceUri: Uri?): Boolean? {
        val retriever = MediaMetadataRetriever()
        return try {
            if (sourceUri != null) {
                retriever.setDataSource(this, sourceUri)
            } else {
                retriever.setDataSource(pathName)
            }
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()
                ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()
                ?: 0
            width > 0 && height > 0
        } catch (e: Exception) {
            Log.w(TAG, "Failed to inspect media video track: $pathName", e)
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to release media metadata retriever", e)
            }
        }
    }

    fun addAttachment(position: Int) {
        if (attachmentTypePathName == null) {
            Log.e(TAG, "adding attachment while attachmentTypePathName is null!")
            return
        }
        if (!attachmentTypePathName!!.startsWith(AttachmentHelper.AUDIO.toString())) {
            if (mImageAttachmentAdapter == null) {
                initImageAttachmentUI(
                    ArrayList(Collections.singletonList(attachmentTypePathName))
                )
            } else {
                notifyImageAttachmentsChanged(true, position)
            }
        } else {
            if (mAudioAttachmentAdapter == null) {
                initAudioAttachmentUI(
                    ArrayList(Collections.singletonList(attachmentTypePathName))
                )
            } else {
                notifyAudioAttachmentsChanged(true, position)
            }
        }
        if (shouldAddToActionList) {
            mActionList!!.addAction(ThingAction(
                ThingAction.ADD_ATTACHMENT, attachmentTypePathName, position
            ))
        }
    }

    /**
     * Set margins to mScrollView. If there is an image, marginTop will be set to 0.
     */
    private fun setScrollViewMarginTop(hasMarginTop: Boolean) {
        mScrollViewHasStatusBarMarginTop = hasMarginTop
        applyScrollViewMarginTop()
    }

    private fun applyScrollViewMarginTop() {
        val params = mScrollView!!.layoutParams as FrameLayout.LayoutParams
        if (mScrollViewHasStatusBarMarginTop) {
            val mt: Int = (screenDensity * 56).toInt() + mStatusBarTopInset
            params.setMargins(0, mt, 0, params.bottomMargin)
        } else {
            params.setMargins(0, 0, 0, params.bottomMargin)
        }
    }

    private fun initImageAttachmentUI(items: List<String?>) {
        setImageCover()

        val size = items.size
        mRvImageAttachment!!.visibility = View.VISIBLE
        AttachmentHelper.setImageRecyclerViewHeight(mRvImageAttachment, size, mMaxSpanImage)
        setScrollViewMarginTop(false)

        mImageAttachmentAdapter = ImageAttachmentAdapter(
            this, mEditable, items,
            ImageAttachmentClickCallback(),
            if (mEditable) ImageAttachmentRemoveCallback() else null
        )
        mImageLayoutManager = GridLayoutManager(this, if (size < mMaxSpanImage) size else mMaxSpanImage)
        mRvImageAttachment!!.adapter = mImageAttachmentAdapter
        mRvImageAttachment!!.layoutManager = mImageLayoutManager

        if (mEditable) {
            ItemTouchHelper(AttachmentTouchCallback(true))
                .attachToRecyclerView(mRvImageAttachment)
        }
    }

    private fun notifyImageAttachmentsChanged(add: Boolean, position: Int) {
        val items: MutableList<String?> = mImageAttachmentAdapter!!.getItems()!! as MutableList<String?>

        val sizeBefore = items.size
        val spanBefore = if (sizeBefore < mMaxSpanImage) sizeBefore else mMaxSpanImage
        val sizeAfter = if (add) sizeBefore + 1 else sizeBefore - 1
        val spanAfter = if (sizeAfter < mMaxSpanImage) sizeAfter else mMaxSpanImage

        if (add) {
            if (mRvImageAttachment!!.visibility != View.VISIBLE) {
                setImageCover()
                mRvImageAttachment!!.visibility = View.VISIBLE
                setScrollViewMarginTop(false)
            }
            items.add(position, attachmentTypePathName)
            AttachmentHelper.setImageRecyclerViewHeight(mRvImageAttachment, sizeAfter, mMaxSpanImage)
            if (spanAfter == spanBefore) {
                if (sizeBefore == mMaxSpanImage || position == 0) {
                    mImageAttachmentAdapter!!.notifyDataSetChanged()
                } else {
                    mImageAttachmentAdapter!!.notifyItemInserted(position)
                }
            } else {
                mImageLayoutManager!!.spanCount = spanAfter
                mImageAttachmentAdapter!!.notifyDataSetChanged()
            }
        } else {
            items.removeAt(position)
            if (sizeAfter == 0) {
                mImageCover!!.visibility = View.GONE
                mRvImageAttachment!!.visibility = View.GONE
                setScrollViewMarginTop(true)
                return
            }
            AttachmentHelper.setImageRecyclerViewHeight(mRvImageAttachment, sizeAfter, mMaxSpanImage)
            if (spanAfter == spanBefore) {
                if (sizeBefore == mMaxSpanImage + 1 || position == 0) {
                    mImageAttachmentAdapter!!.notifyDataSetChanged()
                } else {
                    mImageAttachmentAdapter!!.notifyItemRemoved(position)
                }
            } else {
                mImageLayoutManager!!.spanCount = spanAfter
                mImageAttachmentAdapter!!.notifyDataSetChanged()
            }
        }
    }

    private fun initAudioAttachmentUI(items: List<String?>) {
        mRvAudioAttachment!!.visibility = View.VISIBLE
        AttachmentHelper.setAudioRecyclerViewHeight(mRvAudioAttachment, items.size, mSpanAudio)
        mAudioAttachmentAdapter = AudioAttachmentAdapter(
            this, getAccentColor(), mEditable, items,
            if (mEditable) AudioAttachmentRemoveCallback() else null
        )
        mAudioAttachmentAdapter!!.setAccentBackground(getAccentBackground())
        mAudioLayoutManager = GridLayoutManager(this, mSpanAudio)
        mRvAudioAttachment!!.adapter = mAudioAttachmentAdapter
        mRvAudioAttachment!!.layoutManager = mAudioLayoutManager

        if (mEditable) {
            ItemTouchHelper(AttachmentTouchCallback(false))
                .attachToRecyclerView(mRvAudioAttachment)
        }
    }

    private fun notifyAudioAttachmentsChanged(add: Boolean, position: Int) {
        val items: MutableList<String?> = mAudioAttachmentAdapter!!.getItems()!! as MutableList<String?>
        val sizeBefore = items.size
        val sizeAfter = if (add) sizeBefore + 1 else sizeBefore - 1

        if (add) {
            if (mRvAudioAttachment!!.visibility != View.VISIBLE) {
                mRvAudioAttachment!!.visibility = View.VISIBLE
            }

            val index = mAudioAttachmentAdapter!!.getPlayingIndex()
            if (index != -1 && index > position) {
                mAudioAttachmentAdapter!!.setPlayingIndex(index + 1)
            }

            items.add(position, attachmentTypePathName)
            AttachmentHelper.setAudioRecyclerViewHeight(mRvAudioAttachment, sizeAfter, mSpanAudio)
            if (sizeAfter == 1) {
                mAudioAttachmentAdapter!!.notifyDataSetChanged()
            } else {
                mAudioAttachmentAdapter!!.notifyItemInserted(position)
            }
        } else {
            items.removeAt(position)
            if (sizeAfter == 0) {
                mRvAudioAttachment!!.visibility = View.GONE
                return
            }
            AttachmentHelper.setAudioRecyclerViewHeight(mRvAudioAttachment, sizeAfter, mSpanAudio)
            mAudioAttachmentAdapter!!.notifyItemRemoved(position)
        }
    }

    private fun setImageCover() {
        updateImageCoverHeight()
        mImageCover!!.visibility = View.VISIBLE
    }

    private fun updateImageCoverHeight() {
        val fl = mImageCover!!.layoutParams as FrameLayout.LayoutParams
        fl.height = (66 * screenDensity).toInt() + mStatusBarTopInset
    }

    fun getAccentColor(): Int {
        val bg: ThingBackground? = getAccentBackground()
        return bg?.representativeColor() ?: 0
    }

    /**
     * Phase 7: the canonical "current accent" — full ThingBackground.
     * Priority: pending-pick → in-flight animation terminal → saved thing → null.
     */
    fun getAccentBackground(): ThingBackground? {
        if (mChangeBackgroundTo != null) return mChangeBackgroundTo
        if (mChangeColorTo != 0) return ThingBackground.pure(mChangeColorTo)
        if (mThing != null) return mThing!!.getBackground()
        return null
    }

    private fun setScrollEvents() {
        mActionbar!!.setOnClickListener {
            mScrollView!!.smoothScrollTo(0, 0)
        }

        val barsHeight = (screenDensity * 56).toInt()

        mScrollView!!.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener {
                _, _, scrollY, _, _ ->
            var imageHeight = 0
            if (mRvImageAttachment!!.isVisible) {
                imageHeight = mRvImageAttachment!!.height
            }

            // the scrollY that action bar shadow should begin to appear
            // the scrollY that action bar shadow should totally appear
            val shadowAY: Float = if (imageHeight == 0) {
                screenDensity * 14
            } else {
                imageHeight -
                        barsHeight - mStatusBarTopInset + screenDensity * 20
            }
            val shadowTY: Float = shadowAY + screenDensity * 20
            if (scrollY >= shadowTY) {
                mActionBarShadow!!.alpha = 1.0f
            } else if (scrollY <= shadowAY) {
                mActionBarShadow!!.alpha = 0f
            } else {
                val progress: Float = scrollY - shadowAY
                mActionBarShadow!!.alpha = progress / (shadowTY - shadowAY)
            }

            if (imageHeight != 0) {
                val abAY: Float = shadowAY - screenDensity * 12
                val abTY: Float = abAY + screenDensity * 16
                val abAlpha: Int = if (scrollY <= abAY) {
                    0
                } else if (scrollY >= abTY) {
                    255
                } else {
                    val progress: Float = (scrollY - abAY) / (abTY - abAY)
                    (progress * 255).toInt()
                }

                var color = getAccentColor()
                color = DisplayUtil.getTransparentColor(color, abAlpha)
                mStatusBar!!.setBackgroundColor(color)
                mActionbar!!.setBackgroundColor(color)
            }
        })

        if (f<View>(R.id.ll_bottom_bar_detail).isVisible) {
            val observer: ViewTreeObserver = mScrollView!!.viewTreeObserver
            observer.addOnScrollChangedListener {
                updateBottomBarShadow()
            }
            observer.addOnGlobalLayoutListener {
                updateBottomBarShadow()
            }
        }
    }

    private fun updateBottomBarShadow() {
        val windowRect = Rect()
        window.decorView.getWindowVisibleDisplayFrame(windowRect)

        val bottomBarShadow: View = f(R.id.bottom_bar_shadow)!!
        val bottomBarHeight = (screenDensity * 56).toInt()

        val scrollY = mScrollView!!.scrollY
        val childHeight = mScrollView!!.getChildAt(0).height
        var marginTop = mStatusBarTopInset
        if (mRvImageAttachment!!.visibility != View.VISIBLE) {
            marginTop += bottomBarHeight
        } else {
            marginTop -= mStatusBarTopInset
        }

        val aY: Float = childHeight - windowRect.bottom +
                bottomBarHeight + marginTop - screenDensity * 40
        val tY: Float = aY + screenDensity * 24
        if (scrollY <= aY) {
            bottomBarShadow.alpha = 1.0f
        } else if (scrollY >= tY) {
            bottomBarShadow.alpha = 0f
        } else {
            val progress: Float = tY - scrollY
            bottomBarShadow.alpha = progress / (tY - aY)
        }
    }

    private fun setColorPickerEvent() {
        mColorPicker!!.setPickedListener {
            val bgFrom: ThingBackground? = if (mLastAnimatedBackground != null)
                mLastAnimatedBackground
            else mThing!!.getBackground()
            val bgTo: ThingBackground =
                mColorPicker!!.getPickedBackground() ?: return@setPickedListener
            if (bgFrom != null && bgFrom == bgTo) return@setPickedListener
            changeBackground(bgTo)
            if (shouldAddToActionList) {
                mActionList!!.addAction(ThingAction(
                    ThingAction.UPDATE_COLOR, bgFrom, bgTo
                ))
            }
        }
        mColorPicker!!.setOnChangeOrientationListener(Runnable {
            val current: ThingBackground? = getAccentBackground()
            if (current == null || current.mode !== ThingBackground.Mode.GRADIENT) return@Runnable
            val df = GradientOrientationDialogFragment()
            df.setAccent(current)
            df.setOnPickListener(object : GradientOrientationDialogFragment.OnPickListener {
                override fun onPicked(orientation: ThingBackground.Orientation?) {
                    if (orientation === current.orientation) return
                    val bgTo: ThingBackground = ThingBackground.gradient(
                        current.color, current.endColor, orientation
                    )
                    val bgFrom: ThingBackground? = if (mLastAnimatedBackground != null)
                        mLastAnimatedBackground
                    else mThing!!.getBackground()
                    changeBackground(bgTo)
                    mColorPicker!!.pickForBackground(bgTo)
                    if (shouldAddToActionList) {
                        mActionList!!.addAction(ThingAction(
                            ThingAction.UPDATE_COLOR, bgFrom, bgTo
                        ))
                    }
                }
            })
            df.show(fragmentManager, GradientOrientationDialogFragment.TAG)
        })
        mColorPicker!!.setOnPickFromCameraListener(Runnable {
            openCameraColorSampler()
        })
    }

    private fun showColorInfoDialog() {
        val df = ColorInfoDialogFragment()
        df.setThingBackground(getAccentBackground())
        df.show(fragmentManager, ColorInfoDialogFragment.TAG)
    }

    private fun openCameraColorSampler() {
        doWithPermissionChecked(
            object : SimplePermissionCallback(this) {
                override fun onGranted() {
                    showCameraColorSampler()
                }

                override fun onDenied() {
                    showNormalSnackbar(R.string.error_permission_denied)
                }
            },
            Def.Communication.REQUEST_PERMISSION_CAMERA_COLOR,
            Manifest.permission.CAMERA
        )
    }

    private fun showCameraColorSampler() {
        val bgBefore: ThingBackground? = getAccentBackground()
        val df = CameraColorSamplingDialogFragment()
        df.setInitialColor(getAccentColor())
        df.setOnColorListener(object : CameraColorSamplingDialogFragment.OnColorListener {
            override fun onPreviewColor(color: Int) {
                // Live sampling is shown inside the dialog; Detail commits only once.
            }

            override fun onUseColor(color: Int) {
                commitCameraPreviewBackground(bgBefore, ThingBackground.pure(color))
            }

            override fun onCancelColorSampling() {
                // No Detail-side preview state needs restoring.
            }
        })
        df.show(fragmentManager, CameraColorSamplingDialogFragment.TAG)
    }

    private fun renderCameraPreviewBackground(bg: ThingBackground?) {
        bg ?: return
        val color = bg.representativeColor()
        quickRemindPicker?.setAccentBackground(bg)
        if (quickRemindPicker != null) {
            quickRemindPicker!!.pickForUI(quickRemindPicker!!.getPickedIndex())
        }
        BackgroundUtil.applyBackground(mFlRoot, bg)
        updateDescriptions(color)
        applyForegroundColors(color)
        setActionbarOverlayColor(color)
    }

    private fun commitCameraPreviewBackground(bgFrom: ThingBackground?, bgTo: ThingBackground) {
        val from = bgFrom ?: getAccentBackground()
        if (from != null && from == bgTo) {
            renderCameraPreviewBackground(bgTo)
            return
        }
        mChangeColorTo = bgTo.representativeColor()
        mChangeBackgroundTo = bgTo
        mLastAnimatedBackground = bgTo
        mColorPicker!!.pickForBackground(bgTo)
        renderCameraPreviewBackground(bgTo)
        if (shouldAddToActionList) {
            mActionList!!.addAction(ThingAction(
                ThingAction.UPDATE_COLOR, from, bgTo
            ))
        }
    }

    private fun changeBackground(bgTo: ThingBackground?) {
        if (bgTo == null) return
        val colorTo = bgTo.representativeColor()
        mChangeColorTo      = colorTo
        mChangeBackgroundTo = bgTo

        val fromBg: ThingBackground? = if (mLastAnimatedBackground != null)
            mLastAnimatedBackground
        else mThing!!.getBackground()
        val toBg: ThingBackground = bgTo
        mLastAnimatedBackground = toBg

        quickRemindPicker!!.setAccentBackground(bgTo)
        quickRemindPicker!!.pickForUI(quickRemindPicker!!.getPickedIndex())

        BackgroundUtil.animateBackground(mFlRoot, fromBg, toBg, 600)

        updateDescriptions(colorTo)
        applyForegroundColors(colorTo)

        val abAlpha = currentDrawableAlpha(mActionbar!!.background)
        val abFrom  = DisplayUtil.getTransparentColor(fromBg!!.representativeColor(), abAlpha)
        val abTo    = DisplayUtil.getTransparentColor(colorTo, abAlpha)
        ObjectAnimator.ofObject(mActionbar, "backgroundColor",
            ArgbEvaluator(), abFrom, abTo).setDuration(600).start()
        ObjectAnimator.ofObject(mStatusBar, "backgroundColor",
            ArgbEvaluator(), abFrom, abTo).setDuration(600).start()
    }

    private fun setActionbarOverlayColor(color: Int) {
        val abAlpha = currentDrawableAlpha(mActionbar!!.background)
        val abColor = DisplayUtil.getTransparentColor(color, abAlpha)
        mActionbar!!.setBackgroundColor(abColor)
        mStatusBar!!.setBackgroundColor(abColor)
    }

    private fun currentDrawableAlpha(d: Drawable?): Int {
        if (d is ColorDrawable) return Color.alpha(d.color)
        if (d == null) return 0
        return d.alpha
    }

    /**
     * Phase 4.c: remembers the "terminal" background of the most recent
     * animation so chained colour picks animate from the previously-picked state.
     */
    private var mLastAnimatedBackground: ThingBackground? = null

    /**
     * Phase 6.d: pending ThingBackground chosen by the user but not yet saved.
     */
    private var mChangeBackgroundTo: ThingBackground? = null

    private fun tintMenuIcons(lightAccent: Boolean) {
        if (mActionbar == null) return

        var overflow: Drawable? = mActionbar!!.overflowIcon
        if (overflow != null) {
            overflow = overflow.mutate()
            if (lightAccent) {
                overflow.setColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN)
            } else {
                overflow.clearColorFilter()
            }
            mActionbar!!.overflowIcon = overflow
        }

        val menu: Menu = mActionbar!!.menu ?: return
        val tint: android.content.res.ColorStateList? = if (lightAccent)
            android.content.res.ColorStateList.valueOf(Color.BLACK)
        else null
        for (i in 0 until menu.size) {
            val item: MenuItem = menu[i]
            var icon: Drawable = item.icon ?: continue
            icon = icon.mutate()
            if (lightAccent) {
                icon.setColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN)
            } else {
                icon.clearColorFilter()
            }
            item.icon = icon
            item.iconTintList = tint
        }
    }

    private fun applyForegroundColors(color: Int) {
        val primary   = BackgroundUtil.onColor(color, BackgroundUtil.ON_ALPHA_PRIMARY)
        val secondary = BackgroundUtil.onColor(color, BackgroundUtil.ON_ALPHA_SECONDARY)
        val tertiary  = BackgroundUtil.onColor(color, BackgroundUtil.ON_ALPHA_TERTIARY)

        mEtTitle!!.setTextColor(primary)
        mEtTitle!!.setHintTextColor(primary)

        androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(
            mEtTitle!!,
            if (BackgroundUtil.isLight(color))
                android.content.res.ColorStateList.valueOf(Color.BLACK)
            else android.content.res.ColorStateList.valueOf(Color.WHITE)
        )
        mEtContent!!.setTextColor(secondary)
        mEtContent!!.setHintTextColor(secondary)
        mTvUpdateTime!!.setTextColor(tertiary)

        val tvFinishTime: TextView? = f(R.id.tv_finish_time)
        tvFinishTime?.setTextColor(tertiary)
        val tvTypeInfo: TextView? = f(R.id.tv_type_info)
        tvTypeInfo?.setTextColor(tertiary)
        if (mTvMoveChecklistAsBt != null) {
            mTvMoveChecklistAsBt!!.setTextColor(tertiary)
            androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(
                mTvMoveChecklistAsBt!!,
                if (BackgroundUtil.isLight(color))
                    android.content.res.ColorStateList.valueOf(Color.BLACK)
                else null
            )
            BackgroundUtil.installThingPillRipple(mTvMoveChecklistAsBt, color)
        }

        val tvRemindMe: TextView? = f(R.id.tv_remind_me)
        tvRemindMe?.setTextColor(secondary)

        if (mFlQuickRemindAsBt != null) {
            installQuickRemindPillRipple(color)
        }

        if (tvQuickRemind != null) {
            tvQuickRemind!!.setTextColor(secondary)
            val underline: Drawable? = tvQuickRemind!!.background
            if (underline != null) {
                if (BackgroundUtil.isLight(color)) {
                    underline.setColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN)
                } else {
                    underline.clearColorFilter()
                }
            }
        }
        if (cbQuickRemind != null) {
            val boxTint = if (BackgroundUtil.isLight(color))
                BackgroundUtil.onColor(color, 0.86f) /* dark side */
            else Color.WHITE
            androidx.core.widget.CompoundButtonCompat.setButtonTintList(
                cbQuickRemind!!, android.content.res.ColorStateList.valueOf(boxTint)
            )
        }

        if (mCheckListAdapter != null) {
            mCheckListAdapter!!.setThingColor(color)
            mCheckListAdapter!!.notifyDataSetChanged()
        }

        val lightAccent = BackgroundUtil.isLight(color)
        val iconTint: android.content.res.ColorStateList? = if (lightAccent)
            android.content.res.ColorStateList.valueOf(Color.BLACK)
        else null
        if (mIbBack != null) {
            androidx.core.widget.ImageViewCompat.setImageTintList(mIbBack!!, iconTint)
        }
        val ivIconTypeInfo: ImageView? = f(R.id.iv_icon_type_info)
        if (ivIconTypeInfo != null) {
            androidx.core.widget.ImageViewCompat.setImageTintList(ivIconTypeInfo, iconTint)
        }
        tintMenuIcons(lightAccent)

        val lightBg = BackgroundUtil.isLight(color)
        mFlRoot!!.post {
            if (lightBg) {
                DisplayUtil.darkStatusBar(this@DetailActivity)
            } else {
                DisplayUtil.cancelDarkStatusBar(this@DetailActivity)
            }
        }
    }

    private fun installQuickRemindPillRipple(thingColor: Int) {
        BackgroundUtil.installThingPillRipple(mFlQuickRemindAsBt, thingColor)
    }

    private fun setQuickRemindEvents() {
        cbQuickRemind!!.setOnCheckedChangeListener { _, _ ->
            updateDescriptions(getAccentColor())
            updateBackButton()
            cbQuickRemind!!.contentDescription =
                getString(R.string.remind_me) + tvQuickRemind!!.text
            if (shouldAddToActionList) {
                mActionList!!.addAction(
                    ThingAction(
                        ThingAction.TOGGLE_REMINDER_OR_HABIT, null, null
                    )
                )
            }
            tryToNotifyKeepAlarms()
        }
        if (mThing!!.state == Thing.UNDERWAY) {
            mFlQuickRemindAsBt!!.setOnClickListener {
                quickRemindPicker!!.show()
            }
        }
        setQuickRemindPickerEvent()
    }

    private fun setQuickRemindPickerEvent() {
        quickRemindPicker!!.setPickedListener {
            val pickedBefore = quickRemindPicker!!.getPreviousIndex()
            val pickedAfter  = quickRemindPicker!!.getPickedIndex()
            if (pickedAfter == 9) {
                mDateTimeDialogFragment!!.setPickedBefore(pickedBefore)
                mDateTimeDialogFragment!!.show(fragmentManager, DateTimeDialogFragment.TAG)
            } else {
                val before = ReminderHabitParams(rhParams)
                val isChecked = cbQuickRemind!!.isChecked
                rhParams.reset()
                rhParams.reminderAfterTime = quickRemindPicker!!.getPickedTimeAfter()
                if (cbQuickRemind!!.isChecked) {
                    updateDescriptions(getAccentColor())
                    updateBackButton()
                } else {
                    val temp = shouldAddToActionList
                    shouldAddToActionList = false
                    cbQuickRemind!!.isChecked = true
                    shouldAddToActionList = temp
                }

                if (shouldAddToActionList) {
                    val action = ThingAction(
                        ThingAction.UPDATE_REMINDER_OR_HABIT, before,
                        ReminderHabitParams(rhParams)
                    )
                    action.getExtras()!!.putBoolean(ThingAction.KEY_CHECKBOX_STATE, isChecked)
                    action.getExtras()!!.putInt(ThingAction.KEY_PICKED_BEFORE, pickedBefore)
                    action.getExtras()!!.putInt(ThingAction.KEY_PICKED_AFTER, pickedAfter)
                    mActionList!!.addAction(action)
                }
            }
        }
    }

    private fun tryToNotifyKeepAlarms() {
        // no-op, legacy
    }

    private fun alertCancel(
        @StringRes titleRes: Int, @StringRes contentRes: Int,
        cancelListener: AlertDialogFragment.CancelListener?
    ) {
        alert(titleRes, contentRes, object : AlertDialogFragment.ConfirmListener {
            override fun onConfirm() {
                returnToThingsActivity(true, false)
            }
        }, cancelListener)
    }

    private fun alert(
        @StringRes titleRes: Int, @StringRes contentRes: Int,
        confirmListener: AlertDialogFragment.ConfirmListener?,
        cancelListener: AlertDialogFragment.CancelListener?
    ) {
        val adf = AlertDialogFragment()
        val accent: ThingBackground? = getAccentBackground()
        adf.setTitleBackground(accent)
        adf.setConfirmBackground(accent)
        adf.setTitle(getString(titleRes))
        adf.setContent(getString(contentRes))
        adf.setConfirmListener(confirmListener)
        adf.setCancelListener(cancelListener)
        adf.show(fragmentManager, AlertDialogFragment.TAG)
    }

    private fun alertForChangingHabit(updateHabit: Boolean) {
        @StringRes val titleRes: Int
        @StringRes val contentRes: Int
        if (updateHabit) {
            titleRes = R.string.alert_update_habit_title
            contentRes = R.string.alert_update_habit_content
        } else {
            titleRes = R.string.alert_cancel_habit_title
            contentRes = R.string.alert_cancel_habit_content
        }
        alertCancel(titleRes, contentRes, object : AlertDialogFragment.CancelListener {
            override fun onCancel() {
                mIbBack!!.setImageResource(R.drawable.act_back_habit)
                mIbBack!!.contentDescription = getString(R.string.cd_back_habit)
                cbQuickRemind!!.isChecked = true
                quickRemindPicker!!.pickForUI(9)
                rhParams.reset()
                val habitType = mHabit!!.type
                val habitDetail = mHabit!!.detail
                rhParams.habitType = habitType
                rhParams.habitDetail = habitDetail
                tvQuickRemind!!.text = DateTimeUtil.getDateTimeStrRec(
                    mApp, habitType, habitDetail
                )
            }
        })
    }

    private fun alertForCancellingGoal() {
        alertCancel(R.string.alert_cancel_goal_title, R.string.alert_cancel_goal_content,
            object : AlertDialogFragment.CancelListener {
                override fun onCancel() {
                    mIbBack!!.setImageResource(R.drawable.act_back_goal)
                    mIbBack!!.contentDescription = getString(R.string.cd_back_goal)
                    cbQuickRemind!!.isChecked = true
                    quickRemindPicker!!.pickForUI(9)
                    rhParams.reset()
                    val reminderInMillis = mReminder!!.notifyTime
                    rhParams.reminderInMillis = reminderInMillis
                    tvQuickRemind!!.text = DateTimeUtil.getDateTimeStrAt(
                        reminderInMillis, this@DetailActivity, false
                    )
                }
            })
    }

    private fun createHabit(id: Long, habitDAO: HabitDAO) {
        val habit = Habit(
            id, rhParams.habitType, 0, rhParams.habitDetail,
            "", "", System.currentTimeMillis(), 0
        )
        habit.initHabitReminders()
        habitDAO.createHabit(habit)
    }

    private fun prepareForReturnNormally(): Boolean {
        if (mAudioAttachmentAdapter != null && mAudioAttachmentAdapter!!.getPlayingIndex() != -1) {
            mAudioAttachmentAdapter!!.stopPlaying()
        }

        if (!mEditable) {
            setResult(Def.Communication.RESULT_NO_UPDATE)
            finish()
            return false
        }

        KeyboardUtil.hideKeyboard(currentFocus)
        mNormalSnackbar!!.dismiss()

        if (App.isSomethingUpdatedSpecially()) {
            updateThingAndItsPosition(mThing!!.id)
        }

        return true
    }

    private fun willBeEmptyThing(title: String, content: String, attachment: String): Boolean {
        val contentEmpty = content.isEmpty() && attachment.isEmpty()
        val b1 = DailyCreateTodoReceiver.TAG == mSenderName && contentEmpty
        val b2 = title.isEmpty() && contentEmpty
        return b1 || b2
    }

    private var shouldAlertForNotCreateDailyTodo: Boolean = true

    private fun alertForNotCreateDailyTodo() {
        val confirmListener = object : AlertDialogFragment.ConfirmListener {
            override fun onConfirm() {
                shouldAlertForNotCreateDailyTodo = false
                returnToThingsActivity(true, true)
            }
        }
        alert(
            R.string.alert_cancel_create_daily_todo_title,
            R.string.alert_cancel_create_daily_todo_content,
            confirmListener, null
        )
    }

    private fun returnToThingsActivity(alertForPrivateThing: Boolean, alertForChangingAlarms: Boolean) {
        if (!prepareForReturnNormally()) {
            return
        }

        if (alertForPrivateThing && isPrivateThing() && cannotSetAsPrivateThing()) {
            alertNoTitleWhenSetPrivateThing()
            return
        }

        val reminderTime = rhParams.getReminderTime()

        @Thing.Type val typeBefore: Int = mThing!!.type
        @Thing.Type val typeAfter: Int  = getThingTypeAfter()
        val isReminderBefore = Thing.isReminderType(typeBefore)
        val isReminderAfter  = Thing.isReminderType(typeAfter)
        val isHabitBefore    = typeBefore == Thing.HABIT
        val isHabitAfter     = typeAfter == Thing.HABIT

        if (cbQuickRemind!!.isChecked && rhParams.habitDetail == null
            && reminderTime <= System.currentTimeMillis()
        ) {
            mNormalSnackbar!!.setMessage(R.string.error_later)
            mFlRoot!!.postDelayed(mShowNormalSnackbar, 120)
            return
        }

        val title: String      = getThingTitle()
        val content: String    = getThingContent()
        val attachment: String = getThingAttachment()

        if (mType == CREATE && willBeEmptyThing(title, content, attachment)) {
            if (shouldAlertForNotCreateDailyTodo && DailyCreateTodoReceiver.TAG == mSenderName) {
                alertForNotCreateDailyTodo()
            } else {
                createFailed(Def.Communication.RESULT_CREATE_BLANK_THING)
            }
            return
        }

        val reminderUpdated: Boolean? = setOrUpdateReminder(
            isReminderBefore, isReminderAfter,
            isHabitBefore, alertForChangingAlarms, reminderTime, typeBefore, typeAfter
        )
        if (reminderUpdated == null) {
            return
        }

        val habitUpdated: Boolean =
            setOrUpdateHabit(isHabitBefore, isHabitAfter, alertForChangingAlarms) ?: return

        val color = if (mChangeColorTo != 0) mChangeColorTo else getAccentColor()
        val intent = Intent()

        val resultCode: Int? = createOrUpdateThing(
            title, content, attachment,
            typeBefore, typeAfter, color, reminderUpdated, habitUpdated, intent
        )
        if (resultCode == null) { // create empty thing
            return
        }

        if (mType == CREATE && savedAfterOnPause) {
            App.setJustNotifyAll(true)
        }

        afterCreateOrUpdateThing(intent, resultCode)
        finish()
    }

    private fun createOrUpdateThing(
        title: String, content: String, attachment: String,
        @Thing.Type typeBefore: Int, @Thing.Type typeAfter: Int, color: Int,
        reminderUpdated: Boolean, habitUpdated: Boolean, intent: Intent
    ): Int? {
        var resultCode: Int? = Def.Communication.RESULT_NO_UPDATE
        if (mType == CREATE && !savedAfterOnPause) {
            resultCode = createThing(title, content, attachment, typeAfter, color, intent)
        } else {
            val proposedBg: ThingBackground? = if (mChangeBackgroundTo != null)
                mChangeBackgroundTo
            else mThing!!.getBackground()
            val noUpdate = Thing.noUpdate(
                mThing, title, content, attachment, typeAfter, proposedBg,
                mThingCardSpanMode, mThingCardImagePlacement
            )
                && !reminderUpdated && !habitUpdated && !mHabitFinishedThisTime
                && !mHabitRecordEdited
            if (noUpdate) {
                setResult(resultCode!!)
            } else {
                if (title.isEmpty() && content.isEmpty() && attachment.isEmpty()) {
                    returnToThingsActivity(Thing.DELETED_FOREVER)
                    resultCode = null
                } else {
                    resultCode = updateThing(
                        title, content, attachment,
                        typeBefore, typeAfter, color, intent
                    )
                }
            }
        }
        return resultCode
    }

    private fun afterCreateOrUpdateThing(intent: Intent, resultCode: Int) {
        if (App.isSomethingUpdatedSpecially()
            && resultCode != Def.Communication.RESULT_NO_UPDATE
        ) {
            App.tryToSetNotifyAllToTrue(mThing, resultCode)
        }

        if (shouldSendBroadCast() && !savedAfterOnPause) {
            sendBroadCastToUpdateMainUI(intent, resultCode)
        }

        intent.putExtra(Def.Communication.KEY_THING, mThing)
        App.setLastUpdateUiIntent(intent)

        AppWidgetHelper.updateSingleThingAppWidgets(this, mThing!!.id)
        AppWidgetHelper.updateThingsListAppWidgetsForType(this, mThing!!.type)

        if (resultCode == Def.Communication.RESULT_UPDATE_THING_DONE_TYPE_SAME
            || resultCode == Def.Communication.RESULT_UPDATE_THING_DONE_TYPE_DIFFERENT
        ) {
            SystemNotificationUtil.tryToCreateThingOngoingNotification(this)
        }

        maybeRequestNotificationPermission()
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (mThing == null) return

        @Thing.Type val type = mThing!!.type
        val alarmBearing = type == Thing.REMINDER || type == Thing.WELCOME_REMINDER
            || type == Thing.HABIT || type == Thing.WELCOME_HABIT
            || type == Thing.GOAL || type == Thing.WELCOME_GOAL
        if (!alarmBearing) return

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) return

        doWithPermissionChecked(object : SimplePermissionCallback(this) {
            override fun onDenied() {
                // No-op
            }
        }, Def.Communication.REQUEST_PERMISSION_NOTIFICATION,
            android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun returnToThingsActivity(stateAfter: Int) {
        if (mAudioAttachmentAdapter != null && mAudioAttachmentAdapter!!.getPlayingIndex() != -1) {
            mAudioAttachmentAdapter!!.stopPlaying()
        }
        KeyboardUtil.hideKeyboard(currentFocus)

        val manager: ThingManager = ThingManager.getInstance(mApp)!!
        val intent = Intent()

        if (App.isSomethingUpdatedSpecially()) {
            updateThingAndItsPosition(mThing!!.id)
            App.setJustNotifyAll(true)
        }
        mThing!!.thingCardSpanMode = mThingCardSpanMode
        mThing!!.thingCardImagePlacement = mThingCardImagePlacement

        intent.putExtra(Def.Communication.KEY_POSITION, mPosition)
        intent.putExtra(Def.Communication.KEY_STATE_AFTER, stateAfter)

        if (mPosition == -1) {
            val stateBefore = mThing!!.state
            mThing = Thing.getSameCheckStateThing(mThing, stateBefore, stateAfter)
            val dao: ThingDAO = ThingDAO.getInstance(mApp)!!
            dao.updateState(
                mThing, mThing!!.location, stateBefore, stateAfter, true, true,
                false, true
            )

            val thingId = mThing!!.id
            Thing.tryToCancelOngoing(this, thingId)

            val type = mThing!!.type
            if (type == Thing.GOAL && stateAfter == Thing.UNDERWAY) {
                val reminderDAO: ReminderDAO = ReminderDAO.getInstance(mApp)!!
                val goal: Reminder = reminderDAO.getReminderById(thingId)!!
                ThingManager.getInstance(mApp)!!.getUndoGoals()!!.add(goal)
                reminderDAO.resetGoal(goal)
            }
            if (type == Thing.HABIT) {
                val habitDAO: HabitDAO = HabitDAO.getInstance(mApp)!!
                val curTime = System.currentTimeMillis()
                if (stateAfter == Thing.UNDERWAY) {
                    habitDAO.updateHabitToLatest(thingId, true, true)
                    habitDAO.addHabitIntervalInfo(thingId, "$curTime;")
                } else {
                    if (habitDAO.isPaused(thingId)) {
                        habitDAO.addHabitIntervalInfo(thingId, "$curTime;")
                    }
                    habitDAO.addHabitIntervalInfo(thingId, "$curTime,")
                }
            }
        } else {
            intent.putExtra(
                Def.Communication.KEY_CALL_CHANGE,
                manager.updateState(
                    mThing, mPosition, mThing!!.location,
                    mThing!!.state, stateAfter, false, true
                )
            )
        }

        intent.putExtra(Def.Communication.KEY_THING, mThing)

        val resultCode = Def.Communication.RESULT_UPDATE_THING_STATE_DIFFERENT

        updateUiEverywhereAndFinish(intent, resultCode)
    }

    private fun getThingTitle(): String {
        var title: String = mEtTitle!!.text.toString()
        if (isPrivateThing()) {
            title = Thing.PRIVATE_THING_PREFIX + title
        }
        return title
    }

    private fun getThingContent(): String {
        var content: String
        if (mRvCheckList!!.isVisible) {
            content = CheckListHelper.toCheckListStr(mCheckListAdapter!!.getItems())
            if (mHabitFinishedThisTime) {
                content = content.replace((CheckListHelper.SIGNAL + "1").toRegex(),
                    CheckListHelper.SIGNAL + "0")
            }
        } else {
            content = mEtContent!!.text.toString()
        }
        return content
    }

    private fun getThingAttachment(): String {
        var imageItems: List<String?>? = null
        var audioItems: List<String?>? = null
        if (mRvImageAttachment!!.isVisible) {
            imageItems = mImageAttachmentAdapter!!.getItems()
        }
        if (mRvAudioAttachment!!.isVisible) {
            audioItems = mAudioAttachmentAdapter!!.getItems()
        }
        val attachment: String = AttachmentHelper.toAttachmentStr(imageItems, audioItems)
        val attachmentsToDelete: List<String?>? = AttachmentHelper
            .getAttachmentsToDelete(mThing!!.attachment, attachment)
        if (!attachmentsToDelete.isNullOrEmpty()) {
            mApp!!.addAttachmentsToDeleteFile(attachmentsToDelete)
        }
        return attachment
    }

    private fun createFailed(resultCode: Int) {
        if (savedAfterOnPause) {
            mPosition = App.getThingAndPosition(mApp, mThing!!.id, -1).second ?: -1
            ThingManager.getInstance(mApp)!!.updateState(
                mThing, mPosition, mThing!!.location, Thing.UNDERWAY, Thing.DELETED_FOREVER,
                false, true
            )
        }

        if (App.isSomethingUpdatedSpecially()) {
            App.setJustNotifyAll(true)
        }

        val intent = Intent()
        intent.putExtra(Def.Communication.KEY_RESULT_CODE, resultCode)
        if (shouldSendBroadCast()) {
            sendBroadCastToUpdateMainUI(intent, resultCode)
        } else {
            setResult(resultCode, intent)
        }
        App.setLastUpdateUiIntent(intent)

        finish()
    }

    private fun setOrUpdateReminder(
        isReminderBefore: Boolean, isReminderAfter: Boolean, isHabitBefore: Boolean,
        alertForCancelling: Boolean, reminderTime: Long,
        @Thing.Type typeBefore: Int, @Thing.Type typeAfter: Int
    ): Boolean? {
        var reminderUpdated: Boolean? = true
        val rDao: ReminderDAO = ReminderDAO.getInstance(mApp)!!
        if (!isReminderBefore && isReminderAfter) {
            if (!isHabitBefore || !alertForCancelling) {
                rDao.create(Reminder(mThing!!.id, reminderTime))
            }
        } else if (isReminderBefore && !isReminderAfter) {
            if (typeBefore == Thing.GOAL && alertForCancelling) {
                alertForCancellingGoal()
                reminderUpdated = null
            } else {
                rDao.delete(mThing!!.id)
            }
        } else if (isReminderBefore) {
            if (mReminder!!.notifyTime == reminderTime && typeBefore == typeAfter) {
                reminderUpdated = false
            } else {
                if (typeBefore == Thing.GOAL && alertForCancelling) {
                    alertForCancellingGoal()
                    reminderUpdated = null
                } else {
                    mReminder!!.notifyTime = reminderTime
                    mReminder!!.state = Reminder.UNDERWAY
                    mReminder!!.initNotifyMinutes()
                    mReminder!!.updateTime = System.currentTimeMillis()
                    rDao.update(mReminder)
                }
            }
        } else {
            reminderUpdated = false
        }
        return reminderUpdated
    }

    private fun setOrUpdateHabit(
        isHabitBefore: Boolean, isHabitAfter: Boolean, alertForChanging: Boolean
    ): Boolean? {
        val hDao: HabitDAO = HabitDAO.getInstance(mApp)!!
        var habitUpdated: Boolean? = true
        val id = mThing!!.id
        if (!isHabitBefore && isHabitAfter) {
            createHabit(id, hDao)
        } else if (isHabitBefore && !isHabitAfter) {
            if (alertForChanging) {
                alertForChangingHabit(false)
                habitUpdated = null
            } else {
                hDao.deleteHabit(id)
            }
        } else if (isHabitBefore) {
            if (Habit.noUpdate(mHabit, rhParams.habitType, rhParams.habitDetail)) {
                habitUpdated = false
            } else {
                val noTypeUpdate = Habit.noTypeUpdate(mHabit, rhParams.habitType)
                if (alertForChanging) {
                    alertForChangingHabit(noTypeUpdate)
                    habitUpdated = null
                } else if (noTypeUpdate) {
                    val habit = Habit(mHabit!!)
                    habit.detail = rhParams.habitDetail
                    habit.remindedTimes = habit.record!!.length
                    hDao.updateHabit(habit)
                    hDao.deleteHabitReminders(habit.id)
                    habit.initHabitReminders()
                    for (habitReminder in habit.habitReminders!!) {
                        hDao.createHabitReminder(habitReminder)
                    }
                } else {
                    while (true) if (hDao.deleteHabit(id)) {
                        // ensure the old habit is deleted successfully
                        break
                    }
                    createHabit(id, hDao)
                }
            }
        } else {
            habitUpdated = false
        }
        return habitUpdated
    }

    private fun createThing(
        title: String, content: String, attachment: String,
        @Thing.Type typeAfter: Int, color: Int, intent: Intent
    ): Int {
        mThing!!.title = title
        mThing!!.content = content
        mThing!!.attachment = attachment
        mThing!!.type = typeAfter
        mThing!!.thingCardSpanMode = mThingCardSpanMode
        mThing!!.thingCardImagePlacement = mThingCardImagePlacement
        if (mChangeBackgroundTo != null) {
            mThing!!.setBackground(mChangeBackgroundTo)
        } else {
            mThing!!.setColor(color)
        }

        val currentTime = System.currentTimeMillis()
        mThing!!.createTime = currentTime
        mThing!!.updateTime = currentTime

        intent.putExtra(Def.Communication.KEY_THING, mThing)
        val resultCode = Def.Communication.RESULT_CREATE_THING_DONE

        if (shouldSendBroadCast() || createActivitiesCount > 1) {
            val change: Boolean = ThingManager.getInstance(mApp)!!.create(mThing, true, true)
            intent.putExtra(Def.Communication.KEY_CALL_CHANGE, change)
            intent.putExtra(Def.Communication.KEY_CREATED_DONE, true)
        } else {
            setResult(resultCode, intent)
        }

        return resultCode
    }

    private fun updateThing(
        title: String, content: String, attachment: String,
        @Thing.Type typeBefore: Int, @Thing.Type typeAfter: Int,
        color: Int, intent: Intent
    ): Int {
        mThing!!.title = title
        mThing!!.content = content
        mThing!!.attachment = attachment
        mThing!!.type = typeAfter
        mThing!!.thingCardSpanMode = mThingCardSpanMode
        mThing!!.thingCardImagePlacement = mThingCardImagePlacement
        if (mChangeBackgroundTo != null) {
            mThing!!.setBackground(mChangeBackgroundTo)
        } else {
            mThing!!.setColor(color)
        }
        mThing!!.updateTime = System.currentTimeMillis()

        intent.putExtra(Def.Communication.KEY_TYPE_BEFORE, typeBefore)
        intent.putExtra(Def.Communication.KEY_THING, mThing)

        val sameType = mApp!!.getLimit() == Def.LimitForGettingThings.ALL_UNDERWAY
            || Thing.sameType(typeBefore, typeAfter)
        val resultCode: Int = if (sameType) {
            Def.Communication.RESULT_UPDATE_THING_DONE_TYPE_SAME
        } else {
            Def.Communication.RESULT_UPDATE_THING_DONE_TYPE_DIFFERENT
        }

        if (mPosition != -1) {
            intent.putExtra(Def.Communication.KEY_POSITION, mPosition)
            val updateResult = ThingManager.getInstance(mApp)!!.update(
                typeBefore, mThing, mPosition, true
            )
            if (updateResult != 0) {
                intent.putExtra(Def.Communication.KEY_CALL_CHANGE, updateResult == 1)
            }
        } else {
            ThingDAO.getInstance(mApp)!!.update(typeBefore, mThing, true, true)
        }

        if (!shouldSendBroadCast()) {
            setResult(resultCode, intent)
        }

        return resultCode
    }

    private fun stickyOrCancel() {
        if (App.isSomethingUpdatedSpecially()) {
            updateThingAndItsPosition(mThing!!.id)
            App.setJustNotifyAll(true)
        }

        if (mThing!!.location < 0) {
            ThingManager.getInstance(mApp)!!.cancelStickyThing(mThing, mPosition)
        } else {
            ThingManager.getInstance(mApp)!!.stickyThingOnTop(mThing, mPosition)
        }
        val resultCode = Def.Communication.RESULT_STICKY_THING_OR_CANCEL
        val intent = Intent(Def.Communication.BROADCAST_ACTION_UPDATE_MAIN_UI)
        intent.putExtra(Def.Communication.KEY_RESULT_CODE, resultCode)
        intent.putExtra(Def.Communication.KEY_THING, mThing)
        intent.putExtra(Def.Communication.KEY_POSITION, mPosition)

        updateUiEverywhereAndFinish(intent, resultCode)
    }

    private fun ongoingOrCancel() {
        if (App.isSomethingUpdatedSpecially()) {
            updateThingAndItsPosition(mThing!!.id)
            App.setJustNotifyAll(true)
        }

        val K = Def.Meta.KEY_ONGOING_THING_ID
        val ongoingBefore: Long = FrequentSettings.getLong(K)
        if (ongoingBefore != -1L) {
            SystemNotificationUtil.cancelThingOngoingNotification(this, ongoingBefore)
        }

        val ongoingAfter: Long = if (ongoingBefore == mThing!!.id) {
            -1L
        } else {
            SystemNotificationUtil.createThingOngoingNotification(this, mThing)
            mThing!!.id
        }
        getSharedPreferences(Def.Meta.PREFERENCES_NAME, MODE_PRIVATE)
            .edit { putLong(K, ongoingAfter) }
        FrequentSettings.put(K, ongoingAfter)

        updateUiEverywhereForItemChangeAndFinish()
    }

    private fun pauseOrResumeHabit() {
        if (mHabit == null) return
        if (App.isSomethingUpdatedSpecially()) {
            updateThingAndItsPosition(mThing!!.id)
            App.setJustNotifyAll(true)
        }

        val dao: HabitDAO = HabitDAO.getInstance(this)!!
        val habitId = mHabit!!.id
        if (mHabit!!.isPaused()) {
            dao.resume(habitId)
            dao.updateHabitToLatest(habitId, false, false)
        } else {
            dao.pause(habitId)
        }
        updateUiEverywhereForItemChangeAndFinish()
    }

    private fun updateUiEverywhereForItemChangeAndFinish() {
        val resultCode = Def.Communication.RESULT_UPDATE_THING_DONE_TYPE_SAME
        val intent = Intent()
        intent.putExtra(Def.Communication.KEY_THING, mThing)
        intent.putExtra(Def.Communication.KEY_POSITION, mPosition)
        intent.putExtra(Def.Communication.KEY_RESULT_CODE, resultCode)
        intent.putExtra(Def.Communication.KEY_TYPE_BEFORE, Thing.HABIT)
        updateUiEverywhereAndFinish(intent, resultCode)
    }

    private fun updateUiEverywhereAndFinish(intent: Intent, resultCode: Int) {
        if (shouldSendBroadCast()) {
            sendBroadCastToUpdateMainUI(intent, resultCode)
        } else {
            setResult(resultCode, intent)
        }
        App.setLastUpdateUiIntent(intent)

        AppWidgetHelper.updateSingleThingAppWidgets(this, mThing!!.id)
        AppWidgetHelper.updateThingsListAppWidgetsForType(this, mThing!!.type)
        finish()
    }

    private fun shouldSendBroadCast(): Boolean {
        return mSenderName.equals(ReminderReceiver.TAG)
            || mSenderName.equals(HabitReceiver.TAG)
            || mSenderName.equals(AutoNotifyReceiver.TAG)
            || mSenderName.equals("intent")
            || mSenderName.equals(App::class.java.name)
            || mSenderName.equals(CreateWidget.TAG)
            || mSenderName.equals(AppWidgetHelper.TAG)
            || mSenderName.equals(ShortcutActivity.TAG)
            || mSenderName.equals(NoticeableNotificationActivity.TAG)
            || mSenderName.equals(DailyCreateTodoReceiver.TAG)
    }

    private fun sendBroadCastToUpdateMainUI(intent: Intent, resultCode: Int) {
        val runningDetailActivities: MutableList<Long?> = App.getRunningDetailActivities()
        val size = runningDetailActivities.size
        if (size > 1 && resultCode != Def.Communication.RESULT_NO_UPDATE) {
            App.setSomethingUpdatedSpecially(true)
        }

        intent.putExtra(Def.Communication.KEY_RESULT_CODE, resultCode)
        intent.setAction(Def.Communication.BROADCAST_ACTION_UPDATE_MAIN_UI)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    fun updateBackButton() {
        if (cbQuickRemind!!.isChecked) {
            if (rhParams.habitDetail != null) {
                mIbBack!!.setImageResource(R.drawable.act_back_habit)
                mIbBack!!.contentDescription = getString(R.string.cd_back_habit)
            } else {
                if (Reminder.getType(rhParams.getReminderTime(),
                        System.currentTimeMillis()) == Thing.GOAL
                ) {
                    mIbBack!!.setImageResource(R.drawable.act_back_goal)
                    mIbBack!!.contentDescription = getString(R.string.cd_back_goal)
                } else {
                    mIbBack!!.setImageResource(R.drawable.act_back_reminder)
                    mIbBack!!.contentDescription = getString(R.string.cd_back_reminder)
                }
            }
        } else {
            val thingType = mThing!!.type
            if (Thing.isTypeReminder(thingType)) {
                mIbBack!!.setImageResource(R.drawable.act_back_reminder)
                mIbBack!!.contentDescription = getString(R.string.cd_back_reminder)
            } else if (Thing.isTypeHabit(thingType)) {
                mIbBack!!.setImageResource(R.drawable.act_back_habit)
                mIbBack!!.contentDescription = getString(R.string.cd_back_habit)
            } else if (Thing.isTypeGoal(thingType)) {
                mIbBack!!.setImageResource(R.drawable.act_back_goal)
                mIbBack!!.contentDescription = getString(R.string.cd_back_goal)
            } else {
                mIbBack!!.setImageResource(R.drawable.act_back_note)
                mIbBack!!.contentDescription = getString(R.string.cd_back_note)
            }
        }
    }

    private inner class ActionTextWatcher(private val mActionType: Int) : TextWatcher {

        private var mBefore: String? = null
        private var mCursorPosBefore: Int = 0

        private val mEditText: EditText =
            if (mActionType == ThingAction.UPDATE_TITLE) mEtTitle!! else mEtContent!!

        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
            mBefore = s.toString()
            mCursorPosBefore = mEditText.selectionEnd
        }

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}

        override fun afterTextChanged(s: Editable) {
            if (shouldAddToActionList) {
                val action = ThingAction(mActionType, mBefore, s.toString())
                action.getExtras()!!.putInt(ThingAction.KEY_CURSOR_POS_BEFORE, mCursorPosBefore)
                action.getExtras()!!.putInt(ThingAction.KEY_CURSOR_POS_AFTER, mEditText.selectionStart)
                mActionList!!.addAction(action)
            }
        }
    }

    private inner class CheckListActionCallback : CheckListAdapter.ActionCallback {

        override fun onAction(before: String?, after: String?) {
            if (shouldAddToActionList) {
                mActionList!!.addAction(ThingAction(ThingAction.UPDATE_CHECKLIST, before, after))
            }
        }
    }

    private inner class CheckListItemsChangeCallback : CheckListAdapter.ItemsChangeCallback {

        override fun onInsert(position: Int) {
            val holder = mRvCheckList!!.findViewHolderForAdapterPosition(position) as CheckListAdapter.EditTextHolder?
            if (holder != null) {
                KeyboardUtil.showKeyboard(holder.et)
            }
        }

        override fun onRemove(position: Int, item: String?, cursorPos: Int) {
            if (item == null) {
                if (position == -1) {
                    KeyboardUtil.hideKeyboard(currentFocus)
                    return
                }
                val holder =
                    mRvCheckList!!.findViewHolderForAdapterPosition(position) as? CheckListAdapter.EditTextHolder?
                        ?: return
                holder.et!!.requestFocus()
                holder.et.setSelection(cursorPos)
            } else {
                KeyboardUtil.hideKeyboard(currentFocus)
            }
        }
    }

    private inner class ImageAttachmentClickCallback : ImageAttachmentAdapter.ClickCallback {

        override fun onClick(v: View?, pos: Int) {
            val intent = Intent(this@DetailActivity, ImageViewerActivity::class.java)
            intent.putExtra(Def.Communication.KEY_COLOR, getAccentColor())
            val accentBg: ThingBackground? = getAccentBackground()
            if (accentBg != null) {
                intent.putExtra(Def.Communication.KEY_BACKGROUND, accentBg.toJson())
            }
            intent.putExtra(Def.Communication.KEY_EDITABLE, mEditable)
            intent.putExtra(Def.Communication.KEY_TYPE_PATH_NAME,
                mImageAttachmentAdapter!!.getItems() as ArrayList<String?>?)
            intent.putExtra(Def.Communication.KEY_POSITION, pos)

            val w = v!!.width
            var startX = 0
            var startY = 0
            var startWidth = w
            var startHeight = v.height
            if (w == DisplayUtil.getDisplaySize(App.getApp()).x) {
                startX = w / 2
                startY = startHeight / 2
                startWidth = 0
                startHeight = 0
            }

            val transition: ActivityOptionsCompat = ActivityOptionsCompat.makeScaleUpAnimation(
                v, startX, startY, startWidth, startHeight
            )
            ActivityCompat.startActivityForResult(this@DetailActivity, intent,
                Def.Communication.REQUEST_ACTIVITY_IMAGE_VIEWER, transition.toBundle())
        }
    }

    private inner class ImageAttachmentRemoveCallback : ImageAttachmentAdapter.RemoveCallback {

        override fun onRemove(pos: Int) {
            val item: String = mImageAttachmentAdapter!!.getItems()!![pos]!!
            notifyImageAttachmentsChanged(false, pos)

            KeyboardUtil.hideKeyboard(currentFocus)

            if (shouldAddToActionList) {
                mActionList!!.addAction(ThingAction(
                    ThingAction.DELETE_ATTACHMENT, pos, item
                ))
            }
        }
    }

    private inner class AudioAttachmentRemoveCallback : AudioAttachmentAdapter.RemoveCallback {

        override fun onRemoved(pos: Int) {
            val item: String = mAudioAttachmentAdapter!!.getItems()!![pos]!!
            notifyAudioAttachmentsChanged(false, pos)

            KeyboardUtil.hideKeyboard(currentFocus)

            val index = mAudioAttachmentAdapter!!.getPlayingIndex()
            if (pos < index) {
                mAudioAttachmentAdapter!!.setPlayingIndex(index - 1)
            }

            if (shouldAddToActionList) {
                mActionList!!.addAction(ThingAction(
                    ThingAction.DELETE_ATTACHMENT, pos, item
                ))
            }
        }
    }

    private fun moveChecklist(from: Int, to: Int): Boolean {
        val items: MutableList<String?> = mCheckListAdapter!!.getItems()!!
        val pos2 = items.indexOf("2")
        val fromPos2 = from - pos2
        val toPos2 = to - pos2
        if (fromPos2 * toPos2 <= 0) {
            return false
        }

        val pos3 = items.indexOf("3")
        if (pos3 != -1) {
            val pos4 = pos3 + 1
            if ((pos3 in from..to) || (pos3 in to..from)) {
                return false
            }
            if ((pos4 in from..to) || (pos4 in to..from)) {
                return false
            }
        }

        val item: String = items.removeAt(from)!!
        items.add(to, item)
        mCheckListAdapter!!.notifyItemMoved(from, to)

        if (shouldAddToActionList) {
            mActionList!!.addAction(ThingAction(ThingAction.MOVE_CHECKLIST, from, to))
        }

        return true
    }

    private inner class CheckListTouchCallback : ItemTouchHelper.Callback() {

        override fun getMovementFlags(
            recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
        ): Int {
            val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN
            return makeMovementFlags(dragFlags, 0)
        }

        override fun onMove(
            recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val from = viewHolder.adapterPosition
            val to   = target.adapterPosition
            return moveChecklist(from, to)
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) { }

        override fun isItemViewSwipeEnabled(): Boolean = false

        override fun isLongPressDragEnabled(): Boolean = false
    }

    private fun moveAttachment(from: Int, to: Int, isImageAttachment: Boolean) {
        val items: MutableList<String?> = if (isImageAttachment) {
            mImageAttachmentAdapter!!.getItems()!! as MutableList<String?>
        } else {
            mAudioAttachmentAdapter!!.getItems()!! as MutableList<String?>
        }
        val typePathName: String = items.removeAt(from)!!
        items.add(to, typePathName)

        if (isImageAttachment) {
            mImageAttachmentAdapter!!.notifyItemMoved(from, to)
            if (from == 0 || to == 0) {
                mImageAttachmentAdapter!!.notifyDataSetChanged()
            }
        } else {
            mAudioAttachmentAdapter!!.notifyItemMoved(from, to)
            if (mAudioAttachmentAdapter!!.getPlayingIndex() != -1) {
                mAudioAttachmentAdapter!!.setPlayingIndex(items.indexOf(typePathName))
            }
        }

        if (shouldAddToActionList) {
            val action = ThingAction(ThingAction.MOVE_ATTACHMENT, from, to)
            action.getExtras()!!.putBoolean(ThingAction.KEY_ATTACHMENT_TYPE, isImageAttachment)
            mActionList!!.addAction(action)
        }
    }

    private inner class AttachmentTouchCallback(
        val isImageAttachmentAdapter: Boolean
    ) : ItemTouchHelper.Callback() {

        override fun getMovementFlags(
            recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
        ): Int {
            val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN or
                ItemTouchHelper.START or ItemTouchHelper.END
            return makeMovementFlags(dragFlags, 0)
        }

        override fun onMove(
            recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val from = viewHolder.adapterPosition
            val to = target.adapterPosition

            moveAttachment(from, to, isImageAttachmentAdapter)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) { }

        override fun isItemViewSwipeEnabled(): Boolean = false

        override fun isLongPressDragEnabled(): Boolean {
            return if (isImageAttachmentAdapter) {
                mImageAttachmentAdapter!!.itemCount > 1
            } else {
                mAudioAttachmentAdapter!!.itemCount > 1
            }
        }
    }

    private class CannotScrollLinearLayoutManager(context: Context?) : LinearLayoutManager(context) {

        override fun canScrollVertically(): Boolean = false
    }

    companion object {
        const val TAG: String = "DetailActivity"
        private const val EXTERNAL_UPDATE_REFRESH_MAX_RETRIES: Int = 3

        const val CREATE: Int = 0
        const val UPDATE: Int = 1

        @JvmField var createActivitiesCount: Int = 0

        /**
         * Phase 4.e: open the DetailActivity in CREATE mode with a full ThingBackground.
         */
        @JvmStatic
        fun getOpenIntentForCreate(context: Context?, senderName: String?, bg: ThingBackground?): Intent {
            val intent = Intent(context, DetailActivity::class.java)
            intent.putExtra(Def.Communication.KEY_SENDER_NAME, senderName)
            intent.putExtra(Def.Communication.KEY_DETAIL_ACTIVITY_TYPE, CREATE)
            if (bg != null) {
                intent.putExtra(Def.Communication.KEY_COLOR,      bg.representativeColor())
                intent.putExtra(Def.Communication.KEY_BACKGROUND, bg.toJson())
            }
            return intent
        }

        @JvmStatic
        fun getOpenIntentForUpdate(
            context: Context?, senderName: String?, id: Long, position: Int
        ): Intent {
            if (App.getDoingThingId() == id) {
                return DoingActivity.getOpenIntent(context, true)
            } else {
                val intent = Intent(context, DetailActivity::class.java)
                intent.putExtra(Def.Communication.KEY_SENDER_NAME, senderName)
                intent.putExtra(Def.Communication.KEY_DETAIL_ACTIVITY_TYPE, UPDATE)
                intent.putExtra(Def.Communication.KEY_ID, id)
                intent.putExtra(Def.Communication.KEY_POSITION, position)
                return intent
            }
        }
    }
}
