@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.activities

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.ActivityManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.OnBackPressedCallback
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import androidx.core.app.ActivityCompat
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.TextViewCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.graphics.drawable.DrawerArrowDrawable
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
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
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.cardview.widget.CardView

import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.VideoDecoder
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.signature.ObjectKey
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
import com.ywwynm.everythingdone.fragments.CameraColorSamplingDialogFragment
import com.ywwynm.everythingdone.fragments.ColorInfoDialogFragment
import com.ywwynm.everythingdone.views.ThingBackgroundEditor
import com.ywwynm.everythingdone.fragments.LongTextDialogFragment
import com.ywwynm.everythingdone.fragments.MediaCropAppearanceDialogFragment
import com.ywwynm.everythingdone.fragments.MoveToThingFolderDialogFragment
import com.ywwynm.everythingdone.fragments.ThreeActionsAlertDialogFragment
import com.ywwynm.everythingdone.fragments.ThingFolderNameDialogFragment
import com.ywwynm.everythingdone.helpers.AlarmHelper
import com.ywwynm.everythingdone.helpers.AppUpdateHelper
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.helpers.AuthenticationHelper
import com.ywwynm.everythingdone.helpers.CheckListHelper
import com.ywwynm.everythingdone.helpers.HomeActionWordingHelper
import com.ywwynm.everythingdone.helpers.ThingCardMediaHelper
import com.ywwynm.everythingdone.helpers.SendInfoHelper
import com.ywwynm.everythingdone.helpers.ThingDoingHelper
import com.ywwynm.everythingdone.helpers.ThingExporter
import com.ywwynm.everythingdone.managers.ModeManager
import com.ywwynm.everythingdone.managers.ThingListOverlayDragController
import com.ywwynm.everythingdone.managers.ThingManager
import com.ywwynm.everythingdone.model.DoingRecord
import com.ywwynm.everythingdone.model.Habit
import com.ywwynm.everythingdone.model.HabitRecord
import com.ywwynm.everythingdone.model.HomeEmptyStateHistory
import com.ywwynm.everythingdone.model.Reminder
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingCardAppearance
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.model.ThingFolder
import com.ywwynm.everythingdone.model.ThingFolderCardPresentation
import com.ywwynm.everythingdone.model.ThingListEntry
import com.ywwynm.everythingdone.model.ThingWidgetInfo
import com.ywwynm.everythingdone.model.ThingsCounts
import com.ywwynm.everythingdone.permission.PermissionUtil
import com.ywwynm.everythingdone.permission.SimplePermissionCallback
import com.ywwynm.everythingdone.services.DoingService
import com.ywwynm.everythingdone.utils.AppearanceUtil
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.BitmapUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.EdgeEffectUtil
import com.ywwynm.everythingdone.utils.FileUtil
import com.ywwynm.everythingdone.utils.KeyboardUtil
import com.ywwynm.everythingdone.utils.LocaleUtil
import com.ywwynm.everythingdone.utils.SystemNotificationUtil
import com.ywwynm.everythingdone.utils.ThingsSorter
import com.ywwynm.everythingdone.views.ActivityHeader
import com.ywwynm.everythingdone.views.DrawerHeader
import com.ywwynm.everythingdone.views.DrawerNavigationView
import com.ywwynm.everythingdone.views.FloatingActionButton
import com.ywwynm.everythingdone.views.Snackbar
import com.ywwynm.everythingdone.views.ScrollAwareColumn
import com.ywwynm.everythingdone.views.ThingsStaggeredLayoutManager
import com.ywwynm.everythingdone.views.ThingCardCropEditorController
import com.ywwynm.everythingdone.views.ThingCardCropEditorView
import com.ywwynm.everythingdone.views.RatioSlider
import com.ywwynm.everythingdone.views.ThingCardVideoCropEditorView
import com.ywwynm.everythingdone.views.pickers.ThingCardAppearanceSourcePicker
import com.ywwynm.everythingdone.views.pickers.ColorPicker
import com.ywwynm.everythingdone.views.reveal.RevealLayout
import com.ywwynm.everythingdone.views.reveal.ShiningBorder

import java.io.File
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.core.view.get
import androidx.core.graphics.toColorInt

class ThingsActivity :
    EverythingDoneBaseActivity(),
    MediaCropAppearanceDialogFragment.Host,
    ThingListOverlayDragController.Host {

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
    private var mHomeEmptyState: View? = null
    private var mIvHomeEmptyState: ImageView? = null
    private var mTvHomeEmptyState: TextView? = null
    private var mOperationEmptyProjectionKey: String? = null

    private var mActionbar: Toolbar? = null
    private var mViewInsideActionbar: View? = null

    private var mEtSearch: EditText? = null
    private var mColorPicker: ColorPicker? = null

    private var mModeManager: ModeManager? = null

    private var mDrawerLayout: DrawerLayout? = null
    private var mDrawer: DrawerNavigationView? = null
    private var mDrawerHeader: DrawerHeader? = null
    private val mExpandedDrawerFolderIds = HashSet<Long>()
    private val mAuthenticatedDrawerExpandedPrivateFolderIds = HashSet<Long>()
    private var mCurrentDrawerSelectionKey: DrawerNavigationView.ItemKey? = null
    private var mInitialExternalFolderId: Long? = null
    private var mInitialExternalFolderAuthenticated: Boolean = false
    private var mInitialExternalTypeFilterMask: Int? = null

    private var mActivityHeader: ActivityHeader? = null
    private var mPendingActivityHeaderSpacerHeightPx: Int? = null
    private var mActivityHeaderSpacerApplyPosted: Boolean = false
    private val mProjectionScrollStates = HashMap<String, Parcelable>()

    private var mFab: FloatingActionButton? = null

    private var mRecyclerView: RecyclerView? = null
    private var mAdapter: ThingsAdapterWrapper? = null
    private var mThingsTouchHelper: ItemTouchHelper? = null
    private var mThingsTouchCallback: ThingsTouchCallback? = null
    private var mOverlayDragController: ThingListOverlayDragController? = null
    private var mStaggeredGridLayoutManager: ThingsStaggeredLayoutManager? = null
    private var mThingListPointerDown: Boolean = false
    private var mThingListTouchSequence: Long = 0L
    private var mThingListLastRawX: Float = 0f
    private var mThingListLastRawY: Float = 0f
    private var mOverlayDragActive: Boolean = false
    private var mPendingOverlayDragModeExitRebindReason: String? = null
    private var mThingCardAppearancePanel: View? = null
    private var mTvThingCardAppearanceTitle: TextView? = null
    private var mEtFolderCardAppearanceName: EditText? = null
    private var mBtThingCardAppearanceChangeColor: ImageView? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        updateThingListPointerState(ev, "activity")
        if (mOverlayDragController?.handleTouchEvent(ev) == true) {
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override val recyclerView: RecyclerView
        get() = mRecyclerView!!

    override val overlayParent: ViewGroup
        get() = findViewById(android.R.id.content)
    private var mLlThingCardAppearanceSource: View? = null
    private var mTvThingCardAppearanceSource: TextView? = null
    private var mLlThingCardAppearanceVideoFrame: View? = null
    private var mIvThingCardAppearanceVideoFramePreview: ImageView? = null
    private var mBtThingCardAppearanceVideoFramePrevious: TextView? = null
    private var mBtThingCardAppearanceVideoFrameNext: TextView? = null
    private var mSeekThingCardAppearanceVideoFrame: SeekBar? = null
    private var mLlThingCardAppearanceSpanControls: View? = null
    private var mBtThingCardAppearanceSpanNormal: TextView? = null
    private var mBtThingCardAppearanceSpanFull: TextView? = null
    private var mTvThingCardAppearanceMediaPosition: TextView? = null
    private var mTvFolderCardAppearanceSizeLabel: TextView? = null
    private var mLlThingCardAppearancePlacementControls: View? = null
    private var mBtThingCardAppearancePlacementTop: TextView? = null
    private var mBtThingCardAppearancePlacementBottom: TextView? = null
    private var mBtThingCardAppearancePlacementLeft: TextView? = null
    private var mBtThingCardAppearancePlacementRight: TextView? = null
    private var mBtThingCardAppearancePlacementBackground: TextView? = null
    private var mLlThingCardAppearanceSideWidth: View? = null
    private var mTvThingCardAppearanceSideWidth: TextView? = null
    private var mSeekThingCardAppearanceSideWidth: SeekBar? = null
    private var mBtThingCardAppearancePreciseCrop: TextView? = null
    private var mLlThingCardAppearanceThumbnailRatio: View? = null
    private var mThingCardAppearanceRatioSlider: RatioSlider? = null
    private var mLlThingCardAppearanceBackgroundControls: View? = null
    private var mSeekThingCardAppearanceBackgroundMask: SeekBar? = null
    private var mSeekThingCardAppearanceBackgroundHeight: SeekBar? = null
    private var mBtCancelThingCardAppearance: TextView? = null
    private var mBtConfirmThingCardAppearance: TextView? = null
    private var mThingCardAppearancePanelOriginalPaddingBottom: Int = 0
    private var mThingCardAppearancePanelThing: Thing? = null
    private var mThingCardAppearanceOriginal: ThingCardAppearance? = null
    private var mThingCardAppearanceDraft: ThingCardAppearance? = null
    private var mThingCardAppearanceOriginalBackground: ThingBackground? = null
    private var mFolderCardAppearancePanelFolder: ThingFolder? = null
    private var mFolderCardAppearanceOriginalTitle: String? = null
    private var mFolderCardAppearanceOriginalPresentation: ThingFolderCardPresentation? = null
    private var mFolderCardAppearanceOriginalBackground: ThingBackground? = null
    private var mFolderCardAppearanceDraftPresentation: ThingFolderCardPresentation? = null
    private var mThingCardAppearanceSelectedListPosition: Int = -1
    private var mThingCardAppearanceMediaSources: List<ThingCardMediaHelper.MediaSource> =
            emptyList()
    private var mThingCardAppearanceSourcePicker: ThingCardAppearanceSourcePicker? = null
    private var mThingCardAppearanceEditor: ThingBackgroundEditor? = null
    private var mScrollTcaColorPage: androidx.core.widget.NestedScrollView? = null
    private var mSepTcaTop: View? = null
    private var mSepTcaBottom: View? = null
    private var mLlTcaAppearanceBody: View? = null
    private var mLlTcaTitleRow: View? = null
    private var mLlTcaColorPageTitle: View? = null
    private var mBtTcaColorPageBack: ImageView? = null
    private var mTcaOnColorPage: Boolean = false
    private var mTcaPreciseCropWasVisible: Boolean = false
    private var mTcaPendingWorldSlot: Int = ThingBackgroundEditor.SLOT_PURE
    private var mThingCardAppearanceVideoDurationCache: MutableMap<String, Int> = HashMap()
    private var mBindingThingCardAppearancePanel: Boolean = false
    private var mBindingFolderCardAppearancePanel: Boolean = false
    private var mThingCardAppearancePreviewRefreshPosted: Boolean = false
    private var mThingCardAppearancePanelSpaceUpdateToken: Int = 0
    private var mThingCardAppearancePanelVisibilityToken: Int = 0
    private var mThingCardAppearanceVisibilityScrolling: Boolean = false
    private var mThingCardAppearanceVisibilityScrollToken: Int = 0
    private var mThingCardAppearanceVisibilityCheckToken: Int = 0
    private var mThingCardAppearancePaddingAnimator: ValueAnimator? = null
    private var mThingCardAppearanceBackgroundHeightSliderMinPercent: Int = 0
    private var mThingCardActiveRatioDragRange: ThingCardRatioRange? = null

    private var mSpan: Int = 0

    private var mNormalSnackbar: Snackbar? = null
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
    private var mIsNewItemShiningBorderActive: Boolean = false
    private var mIsNewItemShiningBorderAnimating: Boolean = false
    private var mNewItemShiningBorderCard: View? = null
    private var mNewItemShiningBorderToken: Int = 0

    /**
     * Gate for the post-create new-item entry animation: after inserting a new
     * Thing we first scroll it fully into view (just below the toolbar), then
     * reveal. The reveal is held until the card is bound/laid out AND the
     * programmatic scroll has settled, so it is never played half-hidden and the
     * shining-border path never stops a still-running scroll.
     */
    private var mNewItemRevealGating: Boolean = false
    private var mNewItemRevealScrolling: Boolean = false
    private var mNewItemRevealPosition: Int = -1
    private var mNewItemRevealBg: ThingBackground? = null
    private var mNewItemRevealHolder: BaseThingsAdapter.BaseThingViewHolder? = null

    private var mCanSeeUi: Boolean = false
    private var mUpdateMainUiInOnResume: Boolean = true
    private var mRestoredFromSavedState: Boolean = false

    private val initRecyclerViewRunnable: Runnable = Runnable {
        if (mAdapter!!.getItemCount() <=
            ThingsCounts.getInstance(mApp)!!.getThingsCountForStatus(mApp!!.getStatus(), ThingWidgetInfo.TYPE_FILTER_ALL)
        ) {
            mThingManager!!.loadThings()
        }
        mAdapter!!.setShouldThingsAnimWhenAppearing(!mRestoredFromSavedState)
        mAdapter!!.attachToRecyclerView(mRecyclerView)
        mStaggeredGridLayoutManager = ThingsStaggeredLayoutManager(
            mSpan, StaggeredGridLayoutManager.VERTICAL
        )
        mRecyclerView!!.layoutManager = mStaggeredGridLayoutManager
        updateHomeEmptyState()
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
                handleUpdateMainUiIntentWhileHidden(intent)
                return
            }
            updateMainUi(intent)
        }
    }

    private fun handleUpdateMainUiIntentWhileHidden(intent: Intent) {
        val remoteIntent: Intent? = mRemoteIntent
        if (remoteIntent == null || canReplaceHiddenRemoteIntent(remoteIntent, intent)) {
            mRemoteIntent = intent
            return
        }

        updateMainUi(remoteIntent)
        mDrawerLayout!!.postDelayed({
            mRemoteIntent = intent
        }, 600)
    }

    private fun canReplaceHiddenRemoteIntent(oldIntent: Intent, newIntent: Intent): Boolean {
        if (App.justNotifyAll()) {
            return true
        }

        val oldResultCode: Int = oldIntent.getIntExtra(
            Def.Communication.KEY_RESULT_CODE,
            Def.Communication.RESULT_NO_UPDATE
        )
        if (oldResultCode == Def.Communication.RESULT_NO_UPDATE) {
            return true
        }

        val newResultCode: Int = newIntent.getIntExtra(
            Def.Communication.KEY_RESULT_CODE,
            Def.Communication.RESULT_NO_UPDATE
        )
        if (oldResultCode != newResultCode) {
            return false
        }

        val oldThingId: Long = getRemoteIntentThingId(oldIntent)
        val newThingId: Long = getRemoteIntentThingId(newIntent)
        return oldThingId != -1L && oldThingId == newThingId
    }

    private fun getRemoteIntentThingId(intent: Intent): Long {
        val thing: Thing? = intent.getParcelableExtra(Def.Communication.KEY_THING)
        if (thing != null) {
            return thing.id
        }
        return intent.getLongExtra(Def.Communication.KEY_ID, -1L)
    }

    private var mShouldCloseDrawer: Boolean = false

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
            val status = intent.getIntExtra(Def.Communication.KEY_STATUS, -1)
            if (status != -1 && status != App.getApp()!!.getStatus()) {
                App.getApp()!!.setStatus(status, true)
            }
            mInitialExternalTypeFilterMask = getExternalTypeFilterMask(intent)
            val folderId = intent.getLongExtra(Def.Communication.KEY_FOLDER_ID, Long.MIN_VALUE)
            if (folderId != Long.MIN_VALUE) {
                mInitialExternalFolderId = folderId
                mInitialExternalFolderAuthenticated = intent.getBooleanExtra(
                    Def.Communication.KEY_FOLDER_AUTHENTICATED,
                    false
                )
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
        if (App.getApp()!!.getStatus() != Def.ThingStatus.UNDERWAY) {
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
                        finishNewItemShiningBorderAnimationIfNeeded()
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
                            finishNewItemShiningBorderAnimationIfNeeded()
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
                    justNotifyAll(false)
                    mRemoteIntent = null
                }, 540)
            } else if (mRemoteIntent != null) {
                updateMainUi(mRemoteIntent!!)
            } else {
                mAdapter!!.tryToNotify()
            }
        }

        // FAB ripple/background are applied below by refreshActivitySurfaceAndHeader →
        // applyCreateFabBackgroundForCurrentProjection, which sets a fab-colour-based
        // ripple instead of the random new-thing colour.
        refreshActivitySurfaceAndHeader()
        if (App.isSearching) {
            updateSearchNoResult(0)
        } else {
            hideSearchNoResult()
        }

        KeyboardUtil.hideKeyboard(currentFocus)
    }

    override fun onPause() {
        super.onPause()
        mOverlayDragController?.cancel("activity-pause")
        finishNewItemShiningBorderAnimationIfNeeded()
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
        val newStatus = intent.getIntExtra(Def.Communication.KEY_STATUS, -1)
        val folderId = intent.getLongExtra(Def.Communication.KEY_FOLDER_ID, Long.MIN_VALUE)
        val typeFilterMask = getExternalTypeFilterMask(intent)
        if (folderId != Long.MIN_VALUE || typeFilterMask != null) {
            openExternalProjectionFromIntent(intent)
            return
        }
        if (newStatus != -1 && mApp!!.getStatus() != newStatus) {
            if (mModeManager!!.getCurrentMode() != ModeManager.NORMAL) {
                mModeManager!!.backNormalMode(0)
            }
            if (App.isSearching) {
                toggleSearching(false)
            }
            changeToStatus(newStatus, true)
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
            mColorPicker!!.setTintTarget(menu.findItem(R.id.act_select_color))
            mColorPicker!!.updateAnchor()
            return true
        }

        when (mApp!!.getStatus()) {
            Def.ThingStatus.UNDERWAY -> {
                menuInflater.inflate(R.menu.menu_things_underway, menu)
            }
            Def.ThingStatus.FINISHED -> {
                menuInflater.inflate(R.menu.menu_things_finished, menu)
            }
            else -> {
                menuInflater.inflate(R.menu.menu_things_deleted, menu)
            }
        }
        configureCurrentFolderMenu(menu)
        tintHomeMenuIconsForAppearance(menu)
        return true
    }

    /**
     * Resets the home projection to the default (正在进行 + 全部类型 + 根目录) and
     * refreshes the list, drawer and surface. Used when backing out at root with a
     * non-default status/type filter, so the default is restored before exiting.
     */
    private fun resetRootProjectionToDefault() {
        clearOperationEmptyState()
        mApp!!.setStatus(Def.ThingStatus.UNDERWAY, false)
        mThingManager!!.setTypeFilterMask(ThingWidgetInfo.TYPE_FILTER_ALL, false)
        mThingManager!!.navigateToFolderPathIndex(-1)
        invalidateOptionsMenu()
        mRecyclerView!!.visibility = View.INVISIBLE
        mThingManager!!.loadThings()
        mAdapter!!.setShouldThingsAnimWhenAppearing(false)
        mAdapter!!.notifyDataSetChanged()
        updateHomeEmptyState()
        mRecyclerView!!.scrollToPosition(0)
        mRecyclerView!!.visibility = View.VISIBLE
        refreshActivitySurfaceAndHeader()
        mDrawerHeader!!.updateTexts()
        updateDrawerFolderItems()
        mFab!!.spread()
    }

    private fun configureCurrentFolderMenu(menu: Menu) {
        val currentFolder = mThingManager!!.getCurrentFolder()
        val inFolder = currentFolder != null
        val limitIsUnderway = mApp!!.getStatus() == Def.ThingStatus.UNDERWAY
        val limitIsDeleted = mApp!!.getStatus() == Def.ThingStatus.DELETED

        // Toolbar bulk actions operate on the current scope's visible status; hide
        // them when the projection has nothing to act on (e.g. an empty folder).
        // Wording follows the scope: generic "全部X" at root, folder wording
        // ("完成文件夹中所有记事" …) when inside a folder.
        val hasContent = mThingManager!!.hasVisibleProjectionContent()
        val currentTarget = if (inFolder) {
            HomeActionWordingHelper.StateTarget.CURRENT_FOLDER
        } else {
            HomeActionWordingHelper.StateTarget.ROOT
        }
        menu.findItem(R.id.act_finish_all)?.let {
            it.isVisible = hasContent
            it.title = HomeActionWordingHelper.stateActionTitle(
                this, Def.ThingStatus.UNDERWAY, Thing.FINISHED, currentTarget
            )
        }
        menu.findItem(R.id.act_restore_all)?.let {
            it.isVisible = hasContent
            it.title = HomeActionWordingHelper.stateActionTitle(
                this, mApp!!.getStatus(), Thing.UNDERWAY, currentTarget
            )
        }
        menu.findItem(R.id.act_delete_all)?.let {
            it.isVisible = hasContent
            it.title = HomeActionWordingHelper.stateActionTitle(
                this, Def.ThingStatus.FINISHED, Thing.DELETED, currentTarget
            )
        }
        menu.findItem(R.id.act_delete_all_forever)?.let {
            it.isVisible = hasContent
            it.title = HomeActionWordingHelper.stateActionTitle(
                this, Def.ThingStatus.DELETED, Thing.DELETED_FOREVER, currentTarget
            )
        }
        menu.findItem(R.id.act_sort_by_alarm)?.isVisible = hasContent

        // The overflow holds only structural operations on the current folder.
        // Content bulk ops (complete / restore / trash its Things) are the toolbar's
        // job inside a folder, and the long-press contextual menu's job from outside.
        menu.findItem(R.id.act_toggle_current_folder_private)?.let { item ->
            item.isVisible = inFolder && limitIsUnderway
            if (currentFolder != null) {
                item.title = HomeActionWordingHelper.privateTitle(this, currentFolder.isPrivate)
            }
        }
        menu.findItem(R.id.act_move_current_folder_to_folder)?.isVisible =
                inFolder && limitIsUnderway
        menu.findItem(R.id.act_dissolve_current_folder)?.isVisible =
                inFolder && limitIsUnderway
        // "删除文件夹中所有记事" (content) in the underway view, where the toolbar has
        // no delete; "永久删除文件夹" (structural) in the recycle bin. In the finished
        // view it is omitted because the toolbar "全部删除" already covers it.
        menu.findItem(R.id.act_delete_current_folder)?.let { item ->
            item.isVisible = inFolder && (limitIsUnderway || limitIsDeleted)
            item.title = if (limitIsDeleted) {
                HomeActionWordingHelper.structuralActionTitle(
                    this,
                    HomeActionWordingHelper.StructuralAction.DELETE_FOLDER_FOREVER,
                    HomeActionWordingHelper.StructuralTarget.CURRENT_FOLDER
                )
            } else {
                HomeActionWordingHelper.stateActionTitle(
                    this,
                    Def.ThingStatus.UNDERWAY,
                    Thing.DELETED,
                    HomeActionWordingHelper.StateTarget.CURRENT_FOLDER
                )
            }
        }
        // Colour information for the current Folder's background mirrors the Detail
        // overflow entry; available in every state whenever a Folder is open.
        menu.findItem(R.id.act_color_info)?.isVisible = inFolder
    }

    private fun tintHomeMenuIconsForAppearance(menu: Menu) {
        val tintBackground = getHomeActionbarIconTintBackground()
        tintHomeOverflowIconForAppearance(tintBackground)
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            val icon = item.icon ?: continue
            item.icon = tintToolbarDrawable(icon, tintBackground)
        }
    }

    private fun getHomeActionbarIconTintBackground(): ThingBackground {
        val folderBackground = getCurrentFolderBackgroundForChrome()
        if (folderBackground != null) {
            return folderBackground
        }
        return if (AppearanceUtil.isDarkMode(this)) {
            App.defaultAccentBackground
        } else {
            ThingBackground.pure(
                ContextCompat.getColor(this, R.color.app_chrome_on_surface_secondary)
            )
        }
    }

    private fun tintHomeOverflowIconForAppearance(background: ThingBackground) {
        val toolbar = mActionbar ?: return
        val icon = AppCompatResources.getDrawable(
            this,
            androidx.appcompat.R.drawable.abc_ic_menu_overflow_material
        ) ?: return
        toolbar.overflowIcon = tintToolbarDrawable(icon, background)
    }

    private fun tintToolbarDrawable(
        icon: Drawable?,
        background: ThingBackground
    ): Drawable? {
        if (background.mode === ThingBackground.Mode.GRADIENT) {
            return BackgroundUtil.tintDrawable(resources, icon, background)
        }
        return DisplayUtil.opaqueTintDrawable(this, icon, background.color)
    }

    private fun updateDrawerFolderItems(
        animate: Boolean = false,
        animatedFolderToggleId: Long? = null
    ) {
        val drawerItems = ArrayList<DrawerNavigationView.DrawerItem>()
        val folders = mThingManager!!.getDrawerFolders()
        val folderIds = folders.mapTo(HashSet()) { it.id }
        mExpandedDrawerFolderIds.retainAll(folderIds)
        mAuthenticatedDrawerExpandedPrivateFolderIds.retainAll(folderIds)
        val currentPathIds = mThingManager!!.getProjection().folderPath.toHashSet()

        val childrenByParent = HashMap<Long?, MutableList<ThingFolder>>()
        for (folder in folders) {
            childrenByParent.getOrPut(folder.parentFolderId) { ArrayList() }.add(folder)
        }
        for (children in childrenByParent.values) {
            children.sortWith(folderLocationComparator())
        }

        val visibleItems = ArrayList<DrawerFolderItem>()
        appendDrawerFolderItems(null, 0, childrenByParent, currentPathIds, visibleItems)
        drawerItems.add(
            createDrawerDestinationItem(
                R.id.drawer_all_things,
                if (mThingManager!!.hasAnyFolder()) R.string.all_content else R.string.all_things,
                R.drawable.drawer_all,
                groupStart = true,
                groupEnd = visibleItems.isEmpty()
            )
        )
        for ((index, visibleItem) in visibleItems.withIndex()) {
            drawerItems.add(
                createDrawerFolderItem(
                    visibleItem,
                    groupEnd = index == visibleItems.lastIndex
                )
            )
        }
        drawerItems.add(
            DrawerNavigationView.DrawerItem(
                key = DrawerNavigationView.ItemKey.FilterPanel,
                title = "",
                dividerBefore = true,
                groupStart = true,
                groupEnd = true,
                typeFilterMask = mThingManager?.getActiveTypeFilterMask()
                    ?: ThingWidgetInfo.TYPE_FILTER_ALL,
                status = mApp!!.getStatus(),
                scopeBackground = currentScopeBackground()
            )
        )
        drawerItems.add(
            createDrawerDestinationItem(
                R.id.drawer_settings,
                R.string.settings,
                R.drawable.drawer_settings,
                dividerBefore = true,
                groupStart = true
            )
        )
        drawerItems.add(
            createDrawerDestinationItem(
                R.id.drawer_help,
                R.string.help,
                R.drawable.drawer_help
            )
        )
        drawerItems.add(
            createDrawerDestinationItem(
                R.id.drawer_about,
                R.string.about,
                R.drawable.drawer_about,
                groupEnd = true
            )
        )

        val selectedKey = findDrawerSelectionKeyForCurrentProjection()
        mCurrentDrawerSelectionKey = selectedKey
        mDrawer?.submitItems(
            drawerItems,
            selectedKey,
            animate,
            animatedFolderToggleId
        )
    }

    private fun folderLocationComparator(): Comparator<ThingFolder> {
        return Comparator { folder1, folder2 ->
            val result = ThingsSorter.compareByLocationAndSticky(
                folder1.location,
                folder2.location
            )
            if (result != 0) result else folder1.title.lowercase().compareTo(folder2.title.lowercase())
        }
    }

    private fun appendDrawerFolderItems(
        parentFolderId: Long?,
        level: Int,
        childrenByParent: Map<Long?, List<ThingFolder>>,
        currentPathIds: Set<Long>,
        visibleItems: MutableList<DrawerFolderItem>
    ) {
        val children = childrenByParent[parentFolderId] ?: return
        for (folder in children) {
            val hasChildren = !childrenByParent[folder.id].isNullOrEmpty()
            val canShowChildFolders =
                hasChildren && shouldShowPrivateDrawerChildren(folder, currentPathIds)
            visibleItems.add(DrawerFolderItem(folder, level, hasChildren))
            if (canShowChildFolders && mExpandedDrawerFolderIds.contains(folder.id)) {
                appendDrawerFolderItems(
                    folder.id,
                    level + 1,
                    childrenByParent,
                    currentPathIds,
                    visibleItems
                )
            }
        }
    }

    private fun shouldShowPrivateDrawerChildren(
        folder: ThingFolder,
        currentPathIds: Set<Long>
    ): Boolean {
        return !folder.isPrivate ||
            currentPathIds.contains(folder.id) ||
            mAuthenticatedDrawerExpandedPrivateFolderIds.contains(folder.id)
    }

    private fun createDrawerDestinationItem(
        itemId: Int,
        titleRes: Int,
        iconRes: Int,
        dividerBefore: Boolean = false,
        groupStart: Boolean = false,
        groupEnd: Boolean = false
    ): DrawerNavigationView.DrawerItem {
        return DrawerNavigationView.DrawerItem(
            key = DrawerNavigationView.ItemKey.Destination(itemId),
            title = getString(titleRes),
            iconRes = iconRes,
            dividerBefore = dividerBefore,
            groupStart = groupStart,
            groupEnd = groupEnd
        )
    }

    private fun createDrawerFolderItem(
        drawerFolderItem: DrawerFolderItem,
        groupEnd: Boolean = false
    ): DrawerNavigationView.DrawerItem {
        val folder = drawerFolderItem.folder
        return DrawerNavigationView.DrawerItem(
            key = DrawerNavigationView.ItemKey.Folder(folder.id),
            title = folder.title.ifEmpty { getString(R.string.default_thing_folder_name) },
            folderBackground = folder.getBackground() ?: ThingBackground.pure(folder.getColor()),
            folderPrivate = folder.isPrivate,
            folderLevel = drawerFolderItem.level,
            hasChildFolders = drawerFolderItem.hasChildren,
            folderExpanded = mExpandedDrawerFolderIds.contains(folder.id),
            groupEnd = groupEnd
        )
    }

    private fun toggleDrawerFolderExpanded(folderId: Long) {
        if (mExpandedDrawerFolderIds.contains(folderId)) {
            mExpandedDrawerFolderIds.remove(folderId)
            updateDrawerFolderItems(animate = true, animatedFolderToggleId = folderId)
            return
        }

        val folder = mThingManager!!.getFolderById(folderId)
        if (folder != null &&
            shouldAuthenticateTransientPrivateFolderExpansion(folder) &&
            !mAuthenticatedDrawerExpandedPrivateFolderIds.contains(folderId)
        ) {
            authenticateThingFolder(folder, R.string.expand_private_thing_folder) {
                mAuthenticatedDrawerExpandedPrivateFolderIds.add(folderId)
                mExpandedDrawerFolderIds.add(folderId)
                updateDrawerFolderItems(animate = true, animatedFolderToggleId = folderId)
            }
        } else {
            mExpandedDrawerFolderIds.add(folderId)
            updateDrawerFolderItems(animate = true, animatedFolderToggleId = folderId)
        }
    }

    private fun shouldAuthenticateTransientPrivateFolderExpansion(
        folder: ThingFolder
    ): Boolean {
        return mThingManager!!.isFolderEffectivelyPrivate(folder.id) &&
            !mThingManager!!.isFolderPrivacyAuthenticated(folder.id)
    }

    private fun resetDrawerPrivateExpansionAuthentication() {
        if (mAuthenticatedDrawerExpandedPrivateFolderIds.isEmpty() &&
            mExpandedDrawerFolderIds.isEmpty()
        ) {
            return
        }
        val currentPathIds = mThingManager!!.getProjection().folderPath.toHashSet()
        var changed = mAuthenticatedDrawerExpandedPrivateFolderIds.isNotEmpty()
        mAuthenticatedDrawerExpandedPrivateFolderIds.clear()
        val iterator = mExpandedDrawerFolderIds.iterator()
        while (iterator.hasNext()) {
            val folderId = iterator.next()
            val folder = mThingManager!!.getFolderById(folderId) ?: continue
            if (folder.isPrivate && !currentPathIds.contains(folderId)) {
                iterator.remove()
                changed = true
            }
        }
        if (changed) {
            updateDrawerFolderItems()
        }
    }

    private fun findDrawerSelectionKeyForCurrentProjection(): DrawerNavigationView.ItemKey {
        // The checked Drawer row is always the current Thing Scope (全部记事 root
        // or the current folder). All three statuses — including 回收站 — are
        // expressed by the status segmented capsule in the filter panel, not by a
        // Drawer row, so the Scope row stays selected while status changes.
        val currentFolderId = mThingManager!!.getProjection().currentFolderId
        if (currentFolderId != null) {
            findVisibleDrawerFolderKey(currentFolderId)?.let { return it }
        }
        return DrawerNavigationView.ItemKey.Destination(R.id.drawer_all_things)
    }

    private fun currentScopeBackground(): ThingBackground? {
        val folder = mThingManager?.getCurrentFolder() ?: return null
        return folder.getBackground() ?: ThingBackground.pure(folder.getColor())
    }

    private fun findVisibleDrawerFolderKey(
        folderId: Long
    ): DrawerNavigationView.ItemKey.Folder? {
        val path = mThingManager!!.getFolderPath(folderId)
        if (path.isEmpty()) return null

        var nearestVisibleFolder: ThingFolder? = null
        for (i in path.indices) {
            if (i > 0 && !mExpandedDrawerFolderIds.contains(path[i - 1].id)) {
                break
            }
            nearestVisibleFolder = path[i]
            if (i < path.size - 1 && !mExpandedDrawerFolderIds.contains(path[i].id)) {
                break
            }
        }
        return nearestVisibleFolder?.let {
            DrawerNavigationView.ItemKey.Folder(it.id)
        }
    }

    private fun updateCheckedDrawerItemForCurrentProjection() {
        checkDrawerItem(findDrawerSelectionKeyForCurrentProjection())
    }

    private fun expandDrawerFolderAncestors(folderId: Long) {
        val path = mThingManager!!.getFolderPath(folderId)
        for (i in 0 until path.size - 1) {
            mExpandedDrawerFolderIds.add(path[i].id)
        }
    }

    private fun openDrawerThingFolder(folder: ThingFolder) {
        if (shouldProtectFolderForAccess(folder.id)) {
            authenticateThingFolder(folder, R.string.open_private_thing_folder) {
                openDrawerThingFolderAfterAccess(folder, true)
            }
        } else {
            openDrawerThingFolderAfterAccess(folder, false)
        }
    }

    private fun openDrawerThingFolderAfterAccess(
        folder: ThingFolder,
        authenticated: Boolean
    ) {
        mDrawerLayout!!.closeDrawer(GravityCompat.START)
        saveCurrentProjectionScrollState()
        // Selecting a folder Scope preserves the current status and type filter
        // (the three dimensions are orthogonal). openFolderPath keeps both.
        expandDrawerFolderAncestors(folder.id)
        if (folder.isPrivate) {
            mExpandedDrawerFolderIds.add(folder.id)
        }
        mThingManager!!.openFolderPath(folder.id, authenticated)
        checkDrawerItem(DrawerNavigationView.ItemKey.Folder(folder.id))
        refreshHomeAfterDrawerFolderNavigation()
    }

    private fun refreshHomeAfterDrawerFolderNavigation() {
        finishNewItemShiningBorderAnimationIfNeeded()
        clearOperationEmptyState()
        invalidateOptionsMenu()
        mRecyclerView!!.scrollToPosition(0)
        mAdapter!!.setShouldThingsAnimWhenAppearing(true)
        mAdapter!!.notifyDataSetChanged()
        updateHomeEmptyState()
        refreshActivitySurfaceAndHeader()
        mDrawerHeader!!.updateTexts()
        mFab!!.spread()
        updateDrawerFolderItems()
    }

    private fun saveCurrentProjectionScrollState() {
        val projectionKey = mThingManager?.getProjection()?.key() ?: return
        val state = mRecyclerView?.layoutManager?.onSaveInstanceState() ?: return
        mProjectionScrollStates[projectionKey] = state
    }

    private fun restoreProjectionScrollStateOrTop(projectionKey: String?) {
        val recyclerView = mRecyclerView ?: return
        if (projectionKey == null) {
            recyclerView.scrollToPosition(0)
            requestActivityHeaderStateRefreshBeforeDraw(null)
            return
        }

        val state = mProjectionScrollStates[projectionKey]
        if (state == null) {
            recyclerView.scrollToPosition(0)
            requestActivityHeaderStateRefreshBeforeDraw(projectionKey)
            return
        }

        if (mThingManager?.getProjection()?.key() != projectionKey) {
            return
        }
        recyclerView.layoutManager?.onRestoreInstanceState(state)
        recyclerView.requestLayout()
        requestActivityHeaderStateRefreshBeforeDraw(projectionKey)
    }

    private fun requestActivityHeaderStateRefreshBeforeDraw(projectionKey: String?) {
        val recyclerView = mRecyclerView ?: return
        val viewTreeObserver = recyclerView.viewTreeObserver
        if (!viewTreeObserver.isAlive) {
            requestActivityHeaderStateRefresh()
            return
        }

        viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (recyclerView.viewTreeObserver.isAlive) {
                    recyclerView.viewTreeObserver.removeOnPreDrawListener(this)
                }
                if (projectionKey == null || mThingManager?.getProjection()?.key() == projectionKey) {
                    mActivityHeader?.updateAll(findFirstVisibleThingListPosition(), false)
                }
                return true
            }
        })
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val itemId = item.itemId
        if (itemId == R.id.act_search) {
            toggleSearching(true)
        } else if (itemId == R.id.act_finish_all) {
            // Recursively finish every underway Thing in the current scope. Wording
            // follows scope: "全部完成" at root, "完成文件夹中所有记事" inside a folder.
            confirmFinishAllThingsInScope(mThingManager!!.getCurrentFolder())
        } else if (itemId == R.id.act_restore_all) {
            // Finished view restores finished Things to underway; recycle bin restores
            // trashed Things to their pre-trash state. Current scope; folder wording
            // inside a folder.
            val folder = mThingManager!!.getCurrentFolder()
            if (mApp!!.getStatus() == Def.ThingStatus.DELETED) {
                confirmRestoreTrashedThingsInScope(folder)
            } else {
                confirmUnfinishAllThingsInScope(folder)
            }
        } else if (itemId == R.id.act_delete_all) {
            // Finished view: recursively move every finished Thing in the current
            // scope to the recycle bin. "全部删除" at root, folder wording in a folder.
            confirmTrashAllFinishedInScope(mThingManager!!.getCurrentFolder())
        } else if (itemId == R.id.act_delete_all_forever) {
            // Recycle bin: recursively delete forever every trashed Thing in the
            // current scope. "全部永久删除" at root, folder wording in a folder.
            confirmDeleteForeverAllInScope(mThingManager!!.getCurrentFolder())
        } else if (itemId == R.id.act_sort_by_alarm) {
            finishNewItemShiningBorderAnimationIfNeeded()
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
        } else if (itemId == R.id.act_toggle_current_folder_private) {
            mThingManager!!.getCurrentFolder()?.let {
                toggleThingFolderPrivate(it)
                invalidateOptionsMenu()
            }
        } else if (itemId == R.id.act_move_current_folder_to_folder) {
            mThingManager!!.getCurrentFolder()?.let {
                showMoveThingFolderDialog(it)
            }
        } else if (itemId == R.id.act_dissolve_current_folder) {
            mThingManager!!.getCurrentFolder()?.let {
                showDissolveThingFolderDialog(
                    it,
                    HomeActionWordingHelper.StructuralTarget.CURRENT_FOLDER
                )
            }
        } else if (itemId == R.id.act_delete_current_folder) {
            mThingManager!!.getCurrentFolder()?.let {
                showDeleteThingFolderDialogForCurrentState(it)
            }
        } else if (itemId == R.id.act_color_info) {
            showCurrentFolderColorInfoDialog()
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showCurrentFolderColorInfoDialog() {
        // Reuse the Detail screen's colour-information dialog for the open Folder,
        // inspecting the Folder's own pure colour or gradient background.
        val background = getCurrentFolderBackgroundForChrome() ?: return
        val df = ColorInfoDialogFragment()
        df.setThingBackground(background)
        df.show(fragmentManager, ColorInfoDialogFragment.TAG)
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
        finishNewItemShiningBorderAnimationIfNeeded()
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

    private fun getResultThingIndex(data: Intent): Int {
        return data.getIntExtra(Def.Communication.KEY_POSITION, -1)
    }

    private fun isResultListProjectionCurrent(data: Intent): Boolean {
        val resultProjection = data.getStringExtra(Def.Communication.KEY_LIST_PROJECTION)
            ?: return false
        return resultProjection == mThingManager!!.getProjection().key()
    }

    private fun getResultOldListPosition(data: Intent, thingIndex: Int): Int {
        if (!isResultListProjectionCurrent(data)) return -1
        val listPosition = data.getIntExtra(Def.Communication.KEY_LIST_POSITION, -1)
        if (listPosition >= 0) return listPosition
        return getVisibleListPositionForThingIndex(thingIndex)
    }

    private fun getVisibleListPositionForResultThing(data: Intent, thingIndex: Int): Int {
        val thing: Thing? = data.getParcelableExtra(Def.Communication.KEY_THING)
        if (thing != null) {
            val listPosition = mThingManager!!.getListPositionForThingId(thing.id)
            if (listPosition >= 0) return listPosition
        }
        if (!isResultListProjectionCurrent(data)) return -1
        return getVisibleListPositionForThingIndex(thingIndex)
    }

    private fun getVisibleListPositionForThingIndex(thingIndex: Int): Int {
        val things = mThingManager!!.getThings() ?: return -1
        if (thingIndex < 0 || thingIndex >= things.size) return -1
        val thing = things[thingIndex] ?: return -1
        return mThingManager!!.getListPositionForThingId(thing.id)
    }

    private fun notifyListItemChangedOrRefresh(listPosition: Int) {
        if (listPosition >= 0 && listPosition < mAdapter!!.getItemCount()) {
            mAdapter!!.notifyItemChanged(listPosition)
        } else {
            mAdapter!!.notifyDataSetChanged()
        }
    }

    private fun notifyListItemRemovedOrRefresh(listPosition: Int) {
        if (listPosition >= 0 && listPosition < mAdapter!!.getItemCount()) {
            mAdapter!!.notifyItemRemoved(listPosition)
        } else {
            mAdapter!!.notifyDataSetChanged()
        }
    }

    private fun updateMainUiForCreateDone(data: Intent) {
        if (App.isSearching) {
            toggleSearching(false)
        }

        // Creating a new Thing always returns the list to the "全部类型" type
        // filter while keeping the current Scope and status, so the new Thing is
        // visible regardless of its type (creation never presets a type). Reset
        // before the projection reloads below. When the filter actually changed,
        // the whole projection (which things show, and every Folder Card's
        // thumbnails) changes, so a full reload+rebind is needed rather than a
        // targeted insert.
        val typeFilterWasReset = mThingManager!!.hasCustomTypeFilter()
        if (typeFilterWasReset) {
            mThingManager!!.setTypeFilterMask(ThingWidgetInfo.TYPE_FILTER_ALL, false)
        }

        if (isShortcutCreateFolderResult(data)) {
            updateMainUiForShortcutFolderCreateDone(data)
            return
        } else if (mApp!!.getStatus() != Def.ThingStatus.UNDERWAY) {
            mFab!!.spread()
            mApp!!.setStatus(Def.ThingStatus.UNDERWAY, true)
            invalidateOptionsMenu()
            mRecyclerView!!.scrollToPosition(0)
            mAdapter!!.setShouldThingsAnimWhenAppearing(false)
            mAdapter!!.notifyDataSetChanged()
            mActivityHeader!!.reset(false)
        }

        updateDrawerFolderItems()
        updateCheckedDrawerItemForCurrentProjection()

        val createdDone = data.getBooleanExtra(Def.Communication.KEY_CREATED_DONE, false)
        val justNotifyAll = App.justNotifyAll()
        val thingToCreate: Thing = data.getParcelableExtra(Def.Communication.KEY_THING)!!

        mDrawerLayout!!.postDelayed({
            if (!createdDone) {
                mThingManager!!.create(thingToCreate, true, true)
            }
            if (typeFilterWasReset) {
                // The type filter changed back to 全部类型: reload the whole
                // projection so non-matching siblings reappear and every Folder
                // Card's thumbnails rebuild for all types, then rebind fully.
                finishNewItemShiningBorderAnimationIfNeeded()
                mThingManager!!.loadThings()
                mAdapter!!.setShouldThingsAnimWhenAppearing(false)
                mAdapter!!.notifyDataSetChanged()
                afterUpdateMainUiForCreateDone()
                return@postDelayed
            }
            mRecyclerView!!.postDelayed({
                finishNewItemShiningBorderAnimationIfNeeded()
                val newId = thingToCreate.id
                val newListPosition = mThingManager!!.getListPositionForThingId(newId)
                val bg: ThingBackground? = thingToCreate.getBackground()
                // The ordinary same-list insert scrolls the new card fully into
                // view (below the toolbar) before revealing. justNotifyAll rebuilds
                // the whole list, so it keeps the immediate in-place reveal.
                val gateOnScroll = newListPosition >= 0 && !justNotifyAll
                if (gateOnScroll) {
                    beginGatedNewItemReveal(newListPosition, bg)
                }
                if (newListPosition >= 0) {
                    mAdapter!!.armNewItemAnimation(newListPosition, newId,
                        object : ThingsAdapter.OnNewItemBoundListener {
                            override fun onNewItemBound(
                                listPosition: Int,
                                holder: BaseThingsAdapter.BaseThingViewHolder?
                            ) {
                                onNewItemHolderBound(holder, bg)
                            }
                        })
                }
                if (justNotifyAll) {
                    justNotifyAll()
                } else {
                    if (newListPosition >= 0) {
                        mAdapter!!.notifyItemInserted(newListPosition)
                        scrollNewItemFullyIntoViewThenReveal(newListPosition)
                    } else {
                        mAdapter!!.notifyDataSetChanged()
                    }
                }
                afterUpdateMainUiForCreateDone()
            }, 300)
        }, 300)
    }

    private fun updateMainUiForShortcutFolderCreateDone(data: Intent) {
        openExternalProjectionFromIntent(data, shouldThingsAnimWhenAppearing = true)
        App.setJustNotifyAll(false)
        afterUpdateMainUiForCreateDone()
    }

    private fun isShortcutCreateFolderResult(data: Intent): Boolean {
        if (data.getStringExtra(Def.Communication.KEY_SENDER_NAME) != ShortcutActivity.TAG) {
            return false
        }
        return data.getLongExtra(Def.Communication.KEY_FOLDER_ID, Long.MIN_VALUE) != Long.MIN_VALUE
    }

    private fun afterUpdateMainUiForCreateDone() {
        if (mModeManager!!.getCurrentMode() == ModeManager.SELECTING) {
            updateSelectingUi(false)
        }
        refreshActivitySurfaceAndHeader()
        clearOperationEmptyState()
        updateHomeEmptyState()
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
            finishNewItemShiningBorderAnimationIfNeeded()
            Log.i(TAG, "updateMainUiForUpdateSameType: delayed Runnable started.")
            @Thing.State var thingState: Int = Thing.UNDERWAY
            if (contentUpdatedThing != null) {
                thingState = contentUpdatedThing.state
            }
            if (justNotifyAll) {
                justNotifyAll()
            } else if (Thing.isStateMatchStatus(thingState, mApp!!.getStatus())) {
                val thingIndex = getResultThingIndex(data)
                val listPosition = getVisibleListPositionForResultThing(data, thingIndex)
                if (thingIndex < 0) {
                    justNotifyAll(false)
                    mRemoteIntent = null
                    return@postDelayed
                }
                Log.i(TAG, "type and state match current status, "
                    + "thingIndex[" + thingIndex + "], "
                    + "list position[" + listPosition + "], "
                    + "isSearching[" + App.isSearching + "]")
                if (!App.isSearching) {
                    notifyListItemChangedOrRefresh(listPosition)
                } else {
                    val things: MutableList<Thing?> = mThingManager!!.getThings()!!
                    if (thingIndex > 0 && thingIndex < things.size) {
                        val thing: Thing = things[thingIndex]!!
                        if (thing.matchSearchRequirement(
                                mEtSearch!!.text.toString(),
                                mColorPicker!!.getPickedColor()
                            )
                        ) {
                            notifyListItemChangedOrRefresh(listPosition)
                        } else {
                            mThingManager!!.searchThings(
                                mEtSearch!!.text.toString(),
                                mColorPicker!!.getPickedColor()
                            )
                            mAdapter!!.notifyDataSetChanged()
                        }
                        handleSearchResults()
                    }
                }
                if (mModeManager!!.getCurrentMode() == ModeManager.SELECTING) {
                    updateSelectingUi(false)
                }
            }
            mDrawerHeader!!.updateCompletionRate()
            updateHomeEmptyState()
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
            finishNewItemShiningBorderAnimationIfNeeded()
            val type = thing.type
            val curStatus = mApp!!.getStatus()
            val limitMatched = Thing.isStateMatchStatus(Thing.UNDERWAY, curStatus)
            val thingIndex = getResultThingIndex(data)
            val oldListPosition = getResultOldListPosition(data, thingIndex)
            if (thingIndex < 0) {
                justNotifyAll(false)
                mRemoteIntent = null
                return@postDelayed
            }

            if (justNotifyAll || limitMatched) {
                justNotifyAll()
            } else if (Thing.isStateMatchStatus(Thing.UNDERWAY, curStatus)) {
                if (App.isSearching) {
                    notifyListItemRemovedOrRefresh(oldListPosition)
                    handleSearchResults()
                } else {
                    notifyListItemRemovedOrRefresh(oldListPosition)
                }
                if (mModeManager!!.getCurrentMode() == ModeManager.SELECTING) {
                    updateSelectingUi(false)
                }
            }

            mDrawerHeader!!.updateCompletionRate()
            markOperationEmptyStateIfCurrentProjectionEmpty()
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
        val thingIndex = data.getIntExtra(Def.Communication.KEY_POSITION, 1)
        val oldListPosition = getResultOldListPosition(data, thingIndex)
        val justNotifyAll = App.justNotifyAll()
        Log.i(TAG, "updateMainUiForUpdateDifferentState called, "
            + "stateAfter[" + stateAfter + "], "
            + "thingIndex[" + thingIndex + "], "
            + "listPosition[" + oldListPosition + "], "
            + "justNotifyAll[" + justNotifyAll + "]")

        if (mStateToUndoFrom != stateAfter) {
            dismissSnackbars()
        }

        celebrateHabitGoalFinish(thing, thing.state, stateAfter)

        mDrawerLayout!!.postDelayed({
            finishNewItemShiningBorderAnimationIfNeeded()
            Log.i(TAG, "updateMainUiForUpdateDifferentState: delayed Runnable started.")
            val type = thing.type
            val curStatus = mApp!!.getStatus()
            val limitMatched = Thing.isStateMatchStatus(stateAfter, curStatus)
            if (thingIndex < 0) {
                justNotifyAll(false)
                mRemoteIntent = null
                return@postDelayed
            }
            Log.i(TAG, "type[" + type + "], "
                + "curStatus[" + curStatus + "], "
                + "limitMatched[" + limitMatched + "]")
            if (justNotifyAll || limitMatched) {
                justNotifyAll()
            } else if (Thing.isStateMatchStatus(thing.state, curStatus)) {
                mUndoThings!!.add(thing)
                mThingsIdsToUpdateWidget!!.add(thing.id)
                mUndoPositions!!.add(thingIndex)
                mUndoLocations!!.add(thing.location)
                mStateToUndoFrom = stateAfter
                notifyListItemRemovedOrRefresh(oldListPosition)
                updateUIAfterStateUpdated(
                    stateAfter,
                    mRecyclerView!!.itemAnimator!!.removeDuration, false
                )
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
        val thingIndex = getResultThingIndex(data)
        val oldListPosition = getResultOldListPosition(data, thingIndex)
        val newListPosition = mThingManager!!.getListPositionForThingId(thing.id)
        val justNotifyAll = App.justNotifyAll()
        Log.i(TAG, "updateMainUiForStickyOrCancel called, "
            + "isStickyBefore[" + isStickyBefore + "], "
            + "oldListPosition[" + oldListPosition + "], "
            + "newListPosition[" + newListPosition + "], "
            + "justNotifyAll[" + justNotifyAll + "]")

        mDrawerLayout!!.postDelayed({
            finishNewItemShiningBorderAnimationIfNeeded()
            Log.i(TAG, "updateMainUiForStickyOrCancel: delayed Runnable started.")
            if (justNotifyAll) {
                justNotifyAll()
            } else if (oldListPosition != -1 && newListPosition != -1) {
                mAdapter!!.notifyItemMoved(oldListPosition, newListPosition)
                mDrawerLayout!!.postDelayed({
                    finishNewItemShiningBorderAnimationIfNeeded()
                    mAdapter!!.notifyItemChanged(newListPosition)
                }, mRecyclerView!!.itemAnimator!!.moveDuration)
            } else {
                mAdapter!!.notifyDataSetChanged()
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
            finishNewItemShiningBorderAnimationIfNeeded()
            Log.i(TAG, "updateMainUiForDoingOrCancel: delayed Runnable started.")
            if (justNotifyAll) {
                justNotifyAll()
            } else {
                val listPosition = mThingManager!!.getListPositionForThingId(thing.id)
                if (listPosition != -1) {
                    mAdapter!!.notifyItemChanged(listPosition)
                } else {
                    mAdapter!!.notifyDataSetChanged()
                }
                mUpdateMainUiInOnResume = true
                mRemoteIntent = null
                if (mCanSeeUi) {
                    App.setSomethingUpdatedSpecially(false)
                }
            }
        }, 560)
    }

    private fun justNotifyAll(shouldThingsAnimWhenAppearing: Boolean = true) {
        finishNewItemShiningBorderAnimationIfNeeded()
        if (App.isSearching) {
            mThingManager!!.searchThings(mEtSearch!!.text.toString(), mColorPicker!!.getPickedColor())
            handleSearchResults()
        } else {
            mThingManager!!.loadThings()
        }

        refreshActivitySurfaceAndHeader()
        mDrawerHeader!!.updateCompletionRate()
        updateDrawerFolderItems()

        if (mModeManager!!.getCurrentMode() == ModeManager.SELECTING) {
            updateSelectingUi(false)
        }

        mAdapter!!.setShouldThingsAnimWhenAppearing(shouldThingsAnimWhenAppearing)
        mAdapter!!.notifyDataSetChanged()
        updateHomeEmptyState()

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
        mHomeEmptyState = f(R.id.home_empty_state)
        mIvHomeEmptyState = f(R.id.iv_home_empty_state)
        mTvHomeEmptyState = f(R.id.tv_home_empty_state)
        updateHomeEmptyState()

        mActionbar = f(R.id.actionbar)
        mViewInsideActionbar = f(R.id.view_inside_actionbar)
        mEtSearch = f(R.id.et_search)
        val contextualToolbar: Toolbar = f(R.id.contextual_toolbar)!!
        contextualToolbar.setTitleTextColor(ContextCompat.getColor(this, R.color.black_54p))
        val rlContextualToolbar: RelativeLayout = f(R.id.rl_contextual_toolbar)!!
        mColorPicker = ColorPicker(this, window.decorView, Def.PickerType.HUE_BUCKET)

        mDrawerLayout = f(R.id.drawer_layout)
        mDrawer       = f(R.id.drawer)

        val dhView: View = mDrawer!!.getHeaderView()
        mDrawerHeader = DrawerHeader(
            mApp!!,
            f(dhView, R.id.iv_drawer_header),
            f(dhView, R.id.tv_dh_location),
            f(dhView, R.id.tv_dh_completion_rate)
        )

        mFab = f(R.id.fab_create)

        mRecyclerView  = f(R.id.rv_things)
        mThingCardAppearancePanel = f(R.id.thing_card_appearance_panel)
        mThingCardAppearancePanel!!.setOnTouchListener { _, _ -> true }
        mThingCardAppearancePanel!!.addOnLayoutChangeListener {
                _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (isThingCardAppearancePanelShowing() &&
                    bottom - top != oldBottom - oldTop) {
                requestThingCardAppearancePanelSpaceUpdate()
            }
        }
        mTvThingCardAppearanceTitle = f(R.id.tv_thing_card_appearance_title)
        mEtFolderCardAppearanceName = f(R.id.et_folder_card_appearance_name)
        mBtThingCardAppearanceChangeColor =
                f(R.id.bt_thing_card_appearance_change_color)
        mThingCardAppearanceEditor = f(R.id.tbe_thing_card_appearance)
        mThingCardAppearanceEditor!!.addOnLayoutChangeListener {
                _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (isThingCardAppearancePanelShowing() && mTcaOnColorPage &&
                    bottom - top != oldBottom - oldTop) {
                requestThingCardAppearancePanelSpaceUpdate()
            }
        }
        mScrollTcaColorPage = f(R.id.scroll_tca_color_page)
        mScrollTcaColorPage?.addOnLayoutChangeListener {
                _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (isThingCardAppearancePanelShowing() && mTcaOnColorPage &&
                    bottom - top != oldBottom - oldTop) {
                requestThingCardAppearancePanelSpaceUpdate()
            }
        }
        mSepTcaTop = f(R.id.sep_tca_top)
        mSepTcaBottom = f(R.id.sep_tca_bottom)
        mLlTcaAppearanceBody = f(R.id.ll_tca_appearance_body)
        mLlTcaTitleRow = f(R.id.ll_thing_card_appearance_title_row)
        mLlTcaColorPageTitle = f(R.id.ll_tca_color_page_title)
        mBtTcaColorPageBack = f(R.id.bt_tca_color_page_back)
        mThingCardAppearanceEditor!!.setTitleView(f(R.id.tv_tca_color_page_title))
        mThingCardAppearanceEditor!!.setTitleIcon(mBtTcaColorPageBack)
        mLlThingCardAppearanceSource = f(R.id.ll_thing_card_appearance_source)
        mTvThingCardAppearanceSource = f(R.id.tv_thing_card_appearance_source)
        mLlThingCardAppearanceVideoFrame = f(R.id.ll_thing_card_appearance_video_frame)
        mIvThingCardAppearanceVideoFramePreview =
                f(R.id.iv_thing_card_appearance_video_frame_preview)
        mBtThingCardAppearanceVideoFramePrevious =
                f(R.id.bt_thing_card_appearance_video_frame_previous)
        mBtThingCardAppearanceVideoFrameNext =
                f(R.id.bt_thing_card_appearance_video_frame_next)
        mSeekThingCardAppearanceVideoFrame = f(R.id.seek_thing_card_appearance_video_frame)
        mLlThingCardAppearanceSpanControls =
                f(R.id.ll_thing_card_appearance_span_controls)
        mBtThingCardAppearanceSpanNormal = f(R.id.bt_thing_card_appearance_span_normal)
        mBtThingCardAppearanceSpanFull = f(R.id.bt_thing_card_appearance_span_full)
        mTvThingCardAppearanceMediaPosition =
                f(R.id.tv_thing_card_appearance_media_position)
        mTvFolderCardAppearanceSizeLabel = f(R.id.tv_folder_card_appearance_size_label)
        mLlThingCardAppearancePlacementControls =
                f(R.id.ll_thing_card_appearance_placement_controls)
        mBtThingCardAppearancePlacementTop = f(R.id.bt_thing_card_appearance_placement_top)
        mBtThingCardAppearancePlacementBottom = f(R.id.bt_thing_card_appearance_placement_bottom)
        mBtThingCardAppearancePlacementLeft = f(R.id.bt_thing_card_appearance_placement_left)
        mBtThingCardAppearancePlacementRight = f(R.id.bt_thing_card_appearance_placement_right)
        mBtThingCardAppearancePlacementBackground =
                f(R.id.bt_thing_card_appearance_placement_background)
        mLlThingCardAppearanceSideWidth = f(R.id.ll_thing_card_appearance_side_width)
        mTvThingCardAppearanceSideWidth = f(R.id.tv_thing_card_appearance_side_width)
        mSeekThingCardAppearanceSideWidth = f(R.id.seek_thing_card_appearance_side_width)
        mBtThingCardAppearancePreciseCrop = f(R.id.bt_thing_card_appearance_precise_crop)
        mLlThingCardAppearanceThumbnailRatio =
                f(R.id.ll_thing_card_appearance_thumbnail_ratio)
        mThingCardAppearanceRatioSlider =
                f(R.id.v_thing_card_appearance_ratio_slider)
        mLlThingCardAppearanceBackgroundControls =
                f(R.id.ll_thing_card_appearance_background_controls)
        mSeekThingCardAppearanceBackgroundMask =
                f(R.id.seek_thing_card_appearance_background_mask)
        mSeekThingCardAppearanceBackgroundHeight =
                f(R.id.seek_thing_card_appearance_background_height)
        mBtCancelThingCardAppearance = f(R.id.bt_cancel_thing_card_appearance)
        mBtConfirmThingCardAppearance = f(R.id.bt_confirm_thing_card_appearance)
        val adapter = ThingsAdapter(mApp, OnThingTouchedListener())
        mAdapter = ThingsAdapterWrapper(adapter)

        mActivityHeader = ActivityHeader(
            mApp!!, mRecyclerView!!,
            f(R.id.actionbar_shadow)!!,
            f(R.id.rl_header),
            f(R.id.tv_header_title),
            f(R.id.tv_header_subtitle)
        )
        mActivityHeader!!.setHeaderSpacerHeightListener { height ->
            requestActivityHeaderSpacerHeightUpdate(height)
        }

        val fl: FrameLayout = f(R.id.fl_things)!!
        mNormalSnackbar = Snackbar(mApp!!, Snackbar.NORMAL, fl, mFab)

        mModeManager = ModeManager(
            mApp, mDrawerLayout, mFab, mActivityHeader,
            rlContextualToolbar, contextualToolbar, OnNavigationIconClickedListener(),
            OnContextualMenuClickedListener(), mRecyclerView, adapter
        )
        mModeManager!!.setShouldShowPrivateContent(adapter.shouldShowPrivateContent())
        mModeManager!!.setBackNormalModeCallback {
            cancelThingCardAppearancePanel(false)
        }
        mModeManager!!.setBackNormalModeRequestHandler {
            if (isThingCardAppearancePanelShowing()) {
                cancelThingCardAppearancePanel(false)
                true
            } else {
                false
            }
        }
        mModeManager!!.setContextualToolbarVisibilityCallback { isShowing ->
            if (isShowing) {
                applyContextualStatusBarChrome()
            } else {
                applyHomeStatusBarChrome()
            }
        }
        mModeManager!!.setMenuItemsChangedCallback {
            refreshContextualToolbarForeground()
        }
        adapter.setModeManager(mModeManager)
        mActivityHeader!!.setModeManager(mModeManager!!)
        mActivityHeader!!.setFolderPathClickListener(
            object : ActivityHeader.FolderPathClickListener {
                override fun onFolderPathSegmentClicked(folderPathIndex: Int) {
                    navigateToFolderPathSegment(folderPathIndex)
                }
            }
        )
    }

    override fun initUI() {
        applyHomeStatusBarChrome()

        DisplayUtil.expandLayoutToStatusBarAboveLollipop(this)

        installStatusBarLayoutOffsetListener()
        updateStatusBarLayoutOffsets()

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

        openInitialExternalProjectionIfNeeded()

        updateDrawerFolderItems()
        checkDrawerItem(findDrawerSelectionKeyForCurrentProjection())

        refreshActivitySurfaceAndHeader()
        if (mModeManager!!.getCurrentMode() == ModeManager.SELECTING) {
            mModeManager!!.showContextualToolbar(false)
            mFab!!.shrinkWithoutAnim()
        }

        DisplayUtil.applyBottomInsetAsMargin(mFab)
        DisplayUtil.applyBottomInsetAsMargin(mThingCardAppearancePanel)
        DisplayUtil.applyBottomInsetAsScrollPadding(mRecyclerView)
    }

    private fun openInitialExternalProjectionIfNeeded() {
        val typeFilterMask = mInitialExternalTypeFilterMask
        mInitialExternalTypeFilterMask = null
        if (typeFilterMask != null) {
            applyExternalTypeFilterMask(typeFilterMask, loadThingsNow = false)
        }
        val folderId = mInitialExternalFolderId
        if (folderId == null) {
            if (typeFilterMask != null) {
                mThingManager!!.loadThings()
            }
            return
        }
        mInitialExternalFolderId = null
        openExternalFolderProjection(folderId, mInitialExternalFolderAuthenticated, refreshUi = false)
    }

    private fun getExternalTypeFilterMask(intent: Intent): Int? {
        if (!intent.hasExtra(Def.Communication.KEY_TYPE_FILTER_MASK)) return null
        return ThingWidgetInfo.normalizedTypeFilterMask(
            intent.getIntExtra(
                Def.Communication.KEY_TYPE_FILTER_MASK,
                ThingWidgetInfo.TYPE_FILTER_ALL
            )
        )
    }

    private fun applyExternalTypeFilterMask(typeFilterMask: Int, loadThingsNow: Boolean) {
        clearOperationEmptyState()
        mThingManager!!.setTypeFilterMask(typeFilterMask, loadThingsNow)
    }

    private fun openExternalProjectionFromIntent(
        intent: Intent,
        shouldThingsAnimWhenAppearing: Boolean = true
    ) {
        if (mModeManager!!.getCurrentMode() != ModeManager.NORMAL) {
            mModeManager!!.backNormalMode(0)
        }
        clearOperationEmptyState()
        if (App.isSearching) {
            toggleSearching(false)
        }
        val status = intent.getIntExtra(
            Def.Communication.KEY_STATUS,
            Def.ThingStatus.UNDERWAY
        )
        if (mApp!!.getStatus() != status) {
            mApp!!.setStatus(status, false)
        }
        val typeFilterMask = getExternalTypeFilterMask(intent) ?: ThingWidgetInfo.TYPE_FILTER_ALL
        applyExternalTypeFilterMask(typeFilterMask, loadThingsNow = false)
        val folderId = intent.getLongExtra(Def.Communication.KEY_FOLDER_ID, Long.MIN_VALUE)
        if (folderId == Long.MIN_VALUE) {
            // Root-scope external open: reset any stale folder path to "全部记事"
            // while keeping the intended status and type filter.
            mThingManager!!.navigateToFolderPathIndex(-1)
            checkDrawerItem(findDrawerSelectionKeyForCurrentProjection())
            refreshExternalProjectionUi(shouldThingsAnimWhenAppearing)
            return
        }
        val authenticated = intent.getBooleanExtra(
            Def.Communication.KEY_FOLDER_AUTHENTICATED,
            false
        )
        openExternalFolderProjection(
            folderId,
            authenticated,
            refreshUi = true,
            shouldThingsAnimWhenAppearing = shouldThingsAnimWhenAppearing
        )
    }

    private fun openExternalFolderProjection(
        folderId: Long,
        authenticated: Boolean,
        refreshUi: Boolean,
        shouldThingsAnimWhenAppearing: Boolean = true
    ) {
        val folder = mThingManager!!.getFolderById(folderId)
        if (folder == null || folder.isDeleted()) {
            // The configured/target folder is gone or trashed: persistently fall
            // back to the "全部记事" root scope, keeping the intended status/type.
            mThingManager!!.navigateToFolderPathIndex(-1)
            if (refreshUi) refreshExternalProjectionUi(shouldThingsAnimWhenAppearing)
            return
        }
        expandDrawerFolderAncestors(folder.id)
        if (folder.isPrivate) {
            mExpandedDrawerFolderIds.add(folder.id)
        }
        mThingManager!!.openFolderPath(folder.id, authenticated)
        checkDrawerItem(DrawerNavigationView.ItemKey.Folder(folder.id))
        if (refreshUi) refreshExternalProjectionUi(shouldThingsAnimWhenAppearing)
    }

    private fun refreshExternalProjectionUi(shouldThingsAnimWhenAppearing: Boolean = true) {
        finishNewItemShiningBorderAnimationIfNeeded()
        clearOperationEmptyState()
        invalidateOptionsMenu()
        mRecyclerView!!.scrollToPosition(0)
        mActivityHeader!!.reset(true)
        mAdapter!!.setShouldThingsAnimWhenAppearing(shouldThingsAnimWhenAppearing)
        mAdapter!!.notifyDataSetChanged()
        updateHomeEmptyState()
        refreshActivitySurfaceAndHeader()
        mDrawerHeader!!.updateTexts()
        mFab!!.spread()
        updateDrawerFolderItems()
        KeyboardUtil.hideKeyboard(window)
    }

    private fun installStatusBarLayoutOffsetListener() {
        val statusbar: View = f(R.id.view_status_bar)!!
        ViewCompat.setOnApplyWindowInsetsListener(statusbar) { _, insets ->
            updateStatusBarLayoutOffsets(resolveStatusBarTopInset(insets))
            insets
        }
        statusbar.requestApplyInsets()
    }

    private fun updateStatusBarLayoutOffsets() {
        val insets = ViewCompat.getRootWindowInsets(window.decorView)
        updateStatusBarLayoutOffsets(
                if (insets != null) {
                    resolveStatusBarTopInset(insets)
                } else {
                    DisplayUtil.getCurrentTopSystemInset(window.decorView)
                }
        )
    }

    private fun resolveStatusBarTopInset(insets: WindowInsetsCompat): Int {
        return getStatusBarTopInset(insets)
                .takeIf { it > 0 }
                ?: DisplayUtil.getCurrentTopSystemInset(window.decorView)
    }

    private fun getStatusBarTopInset(insets: WindowInsetsCompat): Int {
        return insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                        or WindowInsetsCompat.Type.displayCutout()
        ).top
    }

    private fun updateStatusBarLayoutOffsets(statusBarHeight: Int) {
        val statusbar: View = f(R.id.view_status_bar)!!
        val dlp1 = statusbar.layoutParams as DrawerLayout.LayoutParams
        if (dlp1.height != statusBarHeight) {
            dlp1.height = statusBarHeight
            statusbar.requestLayout()
        }

        val contextualStatusbar: View = f(R.id.view_contextual_status_bar)!!
        val contextualStatusbarLp = contextualStatusbar.layoutParams
        if (contextualStatusbarLp.height != statusBarHeight) {
            contextualStatusbarLp.height = statusBarHeight
            contextualStatusbar.requestLayout()
        }

        val contextualToolbarWrapper: RelativeLayout = f(R.id.rl_contextual_toolbar)!!
        val contextualToolbarLp =
                contextualToolbarWrapper.layoutParams as ViewGroup.MarginLayoutParams
        if (contextualToolbarLp.topMargin != 0) {
            contextualToolbarLp.topMargin = 0
            contextualToolbarWrapper.layoutParams = contextualToolbarLp
        }

        val fl: FrameLayout = f(R.id.fl_things)!!
        val dlp2 = fl.layoutParams as DrawerLayout.LayoutParams
        if (dlp2.topMargin != statusBarHeight) {
            dlp2.setMargins(dlp2.leftMargin, statusBarHeight, dlp2.rightMargin, dlp2.bottomMargin)
            fl.requestLayout()
        }
    }

    private fun applyHomeStatusBarChrome() {
        val surfaceBackground = applyThingsActivitySurfaceBackground()
        val representativeColor = surfaceBackground.representativeColor()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = representativeColor
        }
        if (BackgroundUtil.isLight(surfaceBackground)) {
            DisplayUtil.darkStatusBar(this)
        } else {
            DisplayUtil.cancelDarkStatusBar(this)
        }
        applyHomeNavigationIconTintForAppearance()
    }

    private fun applyThingsActivitySurfaceBackground(): ThingBackground {
        val background = getThingsActivitySurfaceBackground()
        BackgroundUtil.applyBackground(findViewById<View>(R.id.fl_things), background)
        BackgroundUtil.applyBackground(findViewById<View>(R.id.view_status_bar), background)
        mRecyclerView?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        return background
    }

    private fun getThingsActivitySurfaceBackground(): ThingBackground {
        val listBackgroundColor = ContextCompat.getColor(this, R.color.bg_activity_things)
        val folderBackground = getCurrentFolderBackgroundForChrome()
        return if (folderBackground == null) {
            ThingBackground.pure(listBackgroundColor)
        } else {
            BackgroundUtil.mutedSurfaceBackground(folderBackground, listBackgroundColor)
        }
    }

    private fun getCurrentFolderBackgroundForChrome(): ThingBackground? {
        val folder = mThingManager?.getCurrentFolder() ?: return null
        return folder.getBackground() ?: ThingBackground.pure(folder.getColor())
    }

    /**
     * 选择模式下确认弹窗（移动 / 完成 / 删除 / 恢复等）标题与确认按钮的配色：单选 1 个记事或
     * 文件夹时用该项自身的颜色；多选时用当前所在文件夹的颜色，根目录则用 accent+accent2 渐变。
     */
    private fun selectionDialogBackground(): ThingBackground {
        val things = mThingManager!!.getSelectedThings()?.filterNotNull() ?: emptyList()
        val folders = mThingManager!!.getSelectedFolders().toList()
        if (things.size + folders.size == 1) {
            things.firstOrNull()?.let {
                return it.getBackground() ?: ThingBackground.pure(it.getColor())
            }
            folders.first().let {
                return it.getBackground() ?: ThingBackground.pure(it.getColor())
            }
        }
        return getCurrentFolderBackgroundForChrome() ?: App.defaultAccentBackground
    }

    private fun refreshActivitySurfaceAndHeader() {
        if (mModeManager?.getCurrentMode() == ModeManager.SELECTING) {
            applyThingsActivitySurfaceBackground()
        } else {
            applyHomeStatusBarChrome()
        }
        applyCreateFabBackgroundForCurrentProjection()
        mActivityHeader?.updateText()
        // `updateText` rebuilds title constraints; re-apply the current scroll state before draw.
        requestActivityHeaderStateRefreshBeforeDraw(mThingManager?.getProjection()?.key())
    }

    private fun applyContextualStatusBarChrome() {
        val statusBar: View = f(R.id.view_contextual_status_bar)!!
        val toolbar: Toolbar = f(R.id.contextual_toolbar)!!
        val folderBackground = getCurrentFolderBackgroundForChrome()
        val background = folderBackground
            ?: App.defaultAccentBackground
        // 强调渐变（folderBackground 为空时即 accent+accent2）按深色背景处理，前景走白。
        val foreground = BackgroundUtil.onColor(background, BackgroundUtil.ON_ALPHA_PRIMARY)

        statusBar.visibility = View.VISIBLE
        // Apply to header wrapper for unified gradient spanning status bar + toolbar,
        // keeping the shadow view below it free of the gradient bleed.
        val headerWrapper: View = f(R.id.ll_contextual_header)!!
        BackgroundUtil.applyBackground(headerWrapper, background)
        statusBar.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        toolbar.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = background.representativeColor()
        }
        if (BackgroundUtil.isLight(background)) {
            DisplayUtil.darkStatusBar(this)
        } else {
            DisplayUtil.cancelDarkStatusBar(this)
        }
        applyContextualToolbarForeground(toolbar, foreground)
    }

    private fun applyContextualToolbarForeground(toolbar: Toolbar, foreground: Int) {
        toolbar.setTitleTextColor(foreground)
        AppCompatResources.getDrawable(this, R.drawable.act_close)?.let { close ->
            toolbar.navigationIcon = DisplayUtil.opaqueTintDrawable(this, close, foreground)
        }
        toolbar.overflowIcon?.let { icon ->
            toolbar.overflowIcon = DisplayUtil.opaqueTintDrawable(this, icon, foreground)
        }
        val menu = toolbar.menu
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            val icon = item.icon ?: continue
            item.icon = DisplayUtil.opaqueTintDrawable(this, icon, foreground)
        }
    }

    private fun refreshContextualToolbarForeground() {
        val toolbar: Toolbar = f(R.id.contextual_toolbar)!!
        val folderBackground = getCurrentFolderBackgroundForChrome()
        // 根目录（folderBackground 为空）用 accent+accent2 渐变，按深色背景处理 → 前景走白；
        // 与 applyContextualStatusBarChrome 保持一致，避免渐变下前景被硬编码成黑色。
        val background = folderBackground ?: App.defaultAccentBackground
        val foreground = BackgroundUtil.onColor(background, BackgroundUtil.ON_ALPHA_PRIMARY)
        applyContextualToolbarForeground(toolbar, foreground)
        // Re-tint after the next layout pass to catch menu-item icons that
        // were lazily loaded after setVisible(true) — invisible items may
        // not have their icon drawable ready during the first tint pass.
        toolbar.post {
            applyContextualToolbarForeground(toolbar, foreground)
        }
    }

    private fun applyCreateFabBackgroundForCurrentProjection() {
        val fab = mFab ?: return
        val appAccent = App.defaultAccentBackground.representativeColor()
        val folderBackground = getCurrentFolderBackgroundForChrome()
        if (folderBackground == null) {
            fab.setThingBackground(App.defaultAccentBackground, appAccent)
            fab.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.black_54p))
            // Root FAB shows the accent+accent2 gradient. Its ripple no longer previews
            // the random new-thing colour; the gradient pairs with a light (white)
            // overlay even though its representative colour is nominally light.
            // 0x3DFFFFFF == onColor(dark, 0.24f), matching the folder branch's alpha.
            val ripple = 0x3DFFFFFF
            fab.rippleColor = ripple
            fab.setForegroundRippleColor(ripple)
            return
        }

        val representativeColor = folderBackground.representativeColor()
        fab.setThingBackground(folderBackground, appAccent)
        fab.imageTintList = ColorStateList.valueOf(
            getFabIconColor(representativeColor)
        )
        val ripple = BackgroundUtil.onColor(representativeColor, 0.24f)
        fab.rippleColor = ripple
        fab.setForegroundRippleColor(ripple)
    }

    private fun getFabIconColor(backgroundColor: Int): Int {
        return if (BackgroundUtil.isLight(backgroundColor)) {
            ContextCompat.getColor(this, R.color.black_54p)
        } else {
            ContextCompat.getColor(this, R.color.white_86p)
        }
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
        finishNewItemShiningBorderAnimationIfNeeded()
        val thingCardAppearancePanelShowing = isThingCardAppearancePanelShowing()
        dismissSnackbars()
        mColorPicker!!.dismiss()

        updateStatusBarLayoutOffsets()
        mActivityHeader!!.computeFactors(mActionbar)
        if (!App.isSearching) {
            mActivityHeader!!.reset(false)
        }

        mRecyclerView!!.visibility = View.INVISIBLE
        mAdapter!!.setShouldThingsAnimWhenAppearing(!thingCardAppearancePanelShowing)
        mRecyclerView!!.visibility = View.VISIBLE
        computeSpanCount()
        if (mStaggeredGridLayoutManager != null) {
            mStaggeredGridLayoutManager!!.spanCount = mSpan
        }
        if (mThingManager!!.getThings()!!.size > 1) {
            mRecyclerView!!.scrollToPosition(0)
        }
        mAdapter!!.notifyDataSetChanged()
        updateHomeEmptyState()
        updateThingCardAppearancePanelWidth()

        mModeManager!!.updateTitleTextSize()
        if (mModeManager!!.getCurrentMode() != ModeManager.SELECTING && !App.isSearching
            && mApp!!.getStatus() == Def.ThingStatus.UNDERWAY
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
                    if (isThingCardAppearancePanelShowing()) {
                        cancelThingCardAppearancePanel(false)
                        return
                    }
                    if (mModeManager!!.getCurrentMode() == ModeManager.SELECTING) {
                        mModeManager!!.backNormalMode(0)
                        return
                    } else if (App.isSearching) {
                        if (openParentFolderForCurrentSearchState()) {
                            return
                        }
                        toggleSearching(true)
                        return
                    }
                    if (openParentFolderForCurrentSearchState()) {
                        return
                    }
                    // At root: if the status/type filter isn't the default (正在进行 +
                    // 全部类型), back first returns to that default instead of exiting.
                    val atDefaultProjection =
                        mApp!!.getStatus() == Def.ThingStatus.UNDERWAY &&
                            !mThingManager!!.hasCustomTypeFilter()
                    if (!atDefaultProjection) {
                        resetRootProjectionToDefault()
                        return
                    }
                    if (!FrequentSettings.getBoolean(Def.Meta.KEY_TWICE_BACK)) {
                        mApp!!.setStatus(Def.ThingStatus.UNDERWAY, true)
                        finish()
                    } else {
                        if (lastClickBack == -1L || System.currentTimeMillis() - lastClickBack > 1600) {
                            lastClickBack = System.currentTimeMillis()
                            Toast.makeText(this@ThingsActivity, R.string.press_again_to_exit, Toast.LENGTH_SHORT).show()
                        } else {
                            lastClickBack = -1
                            mApp!!.setStatus(Def.ThingStatus.UNDERWAY, true)
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

        mDrawer!!.getHeaderView().setOnClickListener {
            val intent = Intent(this@ThingsActivity, StatisticActivity::class.java)
            startActivity(intent)
            mShouldCloseDrawer = true
        }

        setFabEvents()
        setRecyclerViewEvents()
        setSearchEvents()
        setThingCardAppearancePanelEvents()
    }

    private fun setThingCardAppearancePanelEvents() {
        mTvThingCardAppearanceSource!!.setOnClickListener {
            showThingCardAppearanceSourceMenu()
        }
        mBtThingCardAppearanceChangeColor!!.setOnClickListener {
            showThingCardAppearanceColorPage()
        }
        mBtTcaColorPageBack!!.setOnClickListener {
            showThingCardAppearanceBodyPage()
        }
        mThingCardAppearanceEditor!!.onBackgroundChanged = { background ->
            updateThingCardAppearanceBackgroundDraft(background)
        }
        mThingCardAppearanceEditor!!.onRequestPickFromWorld = { slot ->
            mTcaPendingWorldSlot = slot
            openThingCardAppearanceCameraColorSampler()
        }
        mScrollTcaColorPage?.let { sv ->
            sv.setOnScrollChangeListener(
                androidx.core.widget.NestedScrollView.OnScrollChangeListener { _, _, _, _, _ ->
                    updateTcaSeparators()
                }
            )
            sv.viewTreeObserver.addOnGlobalLayoutListener { updateTcaSeparators() }
        }
        mEtFolderCardAppearanceName!!.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (mBindingFolderCardAppearancePanel) return
                val folder = mFolderCardAppearancePanelFolder ?: return
                folder.title = s?.toString().orEmpty()
                requestThingCardAppearancePreviewRefresh()
            }
        })
        mSeekThingCardAppearanceVideoFrame!!.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                            seekBar: SeekBar?,
                            progress: Int,
                            fromUser: Boolean
                    ) {
                        if (!fromUser || mBindingThingCardAppearancePanel) return
                        bindThingCardAppearanceVideoFramePreviewForCurrentSource(progress.toLong())
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    }

                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        if (mBindingThingCardAppearancePanel || seekBar == null) return
                        updateThingCardVideoFrame(seekBar.progress.toLong())
                    }
                }
        )
        mBtThingCardAppearanceVideoFramePrevious!!.setOnClickListener {
            stepThingCardVideoFrame(-1)
        }
        mBtThingCardAppearanceVideoFrameNext!!.setOnClickListener {
            stepThingCardVideoFrame(1)
        }
        mBtThingCardAppearanceSpanNormal!!.setOnClickListener {
            if (isFolderCardAppearancePanelActive()) {
                updateFolderCardAppearanceDraft(
                    mFolderCardAppearanceDraftPresentation
                        ?.withSpanMode(ThingFolderCardPresentation.SPAN_NORMAL)
                )
                return@setOnClickListener
            }
            updateThingCardAppearanceDraft(
                    mThingCardAppearanceDraft?.withSpanMode(Thing.THING_CARD_SPAN_NORMAL)
            )
        }
        mBtThingCardAppearanceSpanFull!!.setOnClickListener {
            if (isFolderCardAppearancePanelActive()) {
                updateFolderCardAppearanceDraft(
                    mFolderCardAppearanceDraftPresentation
                        ?.withSpanMode(ThingFolderCardPresentation.SPAN_FULL)
                )
                return@setOnClickListener
            }
            updateThingCardAppearanceDraft(
                    mThingCardAppearanceDraft?.withSpanMode(Thing.THING_CARD_SPAN_FULL)
            )
        }
        mBtThingCardAppearancePlacementTop!!.setOnClickListener {
            if (isFolderCardAppearancePanelActive()) {
                updateFolderCardAppearanceDraft(
                    mFolderCardAppearanceDraftPresentation
                        ?.withMode(ThingFolderCardPresentation.MODE_SUMMARY)
                )
                return@setOnClickListener
            }
            updateThingCardAppearanceDraft(
                    mThingCardAppearanceDraft?.withImagePlacement(
                            Thing.THING_CARD_IMAGE_PLACEMENT_TOP
                    )?.copy(mediaBackgroundEnabled = false)
            )
        }
        mBtThingCardAppearancePlacementBottom!!.setOnClickListener {
            if (isFolderCardAppearancePanelActive()) {
                updateFolderCardAppearanceDraft(
                    mFolderCardAppearanceDraftPresentation
                        ?.withMode(ThingFolderCardPresentation.MODE_THUMBNAILS)
                )
                return@setOnClickListener
            }
            updateThingCardAppearanceDraft(
                    mThingCardAppearanceDraft?.withImagePlacement(
                            Thing.THING_CARD_IMAGE_PLACEMENT_BOTTOM
                    )?.copy(mediaBackgroundEnabled = false)
            )
        }
        mBtThingCardAppearancePlacementLeft!!.setOnClickListener {
            if (mThingCardAppearanceDraft?.spanMode == Thing.THING_CARD_SPAN_FULL) {
                updateThingCardAppearanceDraft(
                        mThingCardAppearanceDraft?.withImagePlacement(
                                Thing.THING_CARD_IMAGE_PLACEMENT_LEFT
                        )?.copy(mediaBackgroundEnabled = false)
                )
            }
        }
        mBtThingCardAppearancePlacementRight!!.setOnClickListener {
            if (mThingCardAppearanceDraft?.spanMode == Thing.THING_CARD_SPAN_FULL) {
                updateThingCardAppearanceDraft(
                        mThingCardAppearanceDraft?.withImagePlacement(
                                Thing.THING_CARD_IMAGE_PLACEMENT_RIGHT
                        )?.copy(mediaBackgroundEnabled = false)
                )
            }
        }
        mBtThingCardAppearancePlacementBackground!!.setOnClickListener {
            updateThingCardAppearanceDraft(
                    mThingCardAppearanceDraft?.copy(mediaBackgroundEnabled = true)
            )
        }
        mSeekThingCardAppearanceSideWidth!!.max =
                getThingCardAppearanceSideMediaWidthMaxPercent() -
                        getThingCardAppearanceSideMediaWidthMinPercent()
        mSeekThingCardAppearanceSideWidth!!.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                            seekBar: SeekBar?,
                            progress: Int,
                            fromUser: Boolean
                    ) {
                        if (!fromUser || mBindingThingCardAppearancePanel) {
                            return
                        }
                        val widthPercent =
                                getThingCardAppearanceSideMediaWidthMinPercent() + progress
                        updateThingCardSidePanelTargetWidthPercent(widthPercent)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {
                        mThingCardActiveRatioDragRange = getThingCardThumbnailRatioRange()
                    }

                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        if (seekBar != null) {
                            val widthPercent =
                                    getThingCardAppearanceSideMediaWidthMinPercent() +
                                            seekBar.progress
                            updateThingCardSidePanelTargetWidthPercent(widthPercent)
                        }
                        mThingCardActiveRatioDragRange = null
                    }
                }
        )
        mBtThingCardAppearancePreciseCrop!!.setOnClickListener {
            openThingCardCropEditor()
        }
        mThingCardAppearanceRatioSlider!!.setRangeProvider {
            val range = getThingCardThumbnailRatioRange()
            range.minRatio to range.maxRatio
        }
        mThingCardAppearanceRatioSlider!!.onRatioChanged = { snappedRatio ->
            updateThingCardActiveTargetAspectRatio(snappedRatio)
        }
        mSeekThingCardAppearanceBackgroundMask!!.max = 100
        mSeekThingCardAppearanceBackgroundMask!!.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                            seekBar: SeekBar?,
                            progress: Int,
                            fromUser: Boolean
                    ) {
                        if (!fromUser || mBindingThingCardAppearancePanel) return
                        updateThingCardBackgroundMask(progress / 100.0)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    }

                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    }
                }
        )
        mSeekThingCardAppearanceBackgroundHeight!!.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                            seekBar: SeekBar?,
                            progress: Int,
                            fromUser: Boolean
                    ) {
                        if (!fromUser || mBindingThingCardAppearancePanel) return
                        updateThingCardBackgroundHeight(progress)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    }

                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    }
                }
        )
        mBtCancelThingCardAppearance!!.setOnClickListener {
            cancelThingCardAppearancePanel(false)
        }
        mBtConfirmThingCardAppearance!!.setOnClickListener {
            confirmThingCardAppearancePanel()
        }
    }

    private fun openSelectedCardAppearancePanel() {
        when (val entry = mThingManager!!.getSingleSelectedEntry()) {
            is ThingListEntry.FolderEntry -> {
                if (entry.effectivePrivate) {
                    authenticateThingFolder(
                        entry.folder,
                        R.string.customize_private_thing_folder_card
                    ) {
                        openFolderCardAppearancePanel(entry.folder)
                    }
                } else {
                    openFolderCardAppearancePanel(entry.folder)
                }
            }
            is ThingListEntry.ThingEntry -> {
                if (entry.thing.isPrivate()) {
                    authenticateThing(
                        entry.thing,
                        R.string.customize_private_thing_card
                    ) {
                        openThingCardAppearancePanel()
                    }
                } else {
                    openThingCardAppearancePanel()
                }
            }
            else -> {}
        }
    }

    private fun openFolderCardAppearancePanel(folder: ThingFolder) {
        hideThingCardAppearanceKeyboard()
        mThingCardAppearancePanelThing = null
        mThingCardAppearanceOriginal = null
        mThingCardAppearanceDraft = null
        mThingCardAppearanceOriginalBackground = null
        mThingCardAppearanceMediaSources = emptyList()
        mThingCardAppearanceSourcePicker?.dismiss()
        mThingCardAppearanceSourcePicker = null

        mFolderCardAppearancePanelFolder = folder
        mFolderCardAppearanceOriginalTitle = folder.title
        mFolderCardAppearanceOriginalPresentation = folder.cardPresentation
        mFolderCardAppearanceOriginalBackground = folder.getBackground()
        mFolderCardAppearanceDraftPresentation = folder.effectiveCardPresentation()
        mThingCardAppearanceSelectedListPosition =
                mThingManager!!.getListPositionForFolderId(folder.id)
        if (mThingCardAppearanceSelectedListPosition < 0) {
            clearThingCardAppearanceDraft()
            return
        }

        val panel: View = mThingCardAppearancePanel!!
        if (panel.visibility != View.VISIBLE) {
            mThingCardAppearancePanelOriginalPaddingBottom = mRecyclerView!!.paddingBottom
        }
        updateThingCardAppearancePanelWidth()
        mAdapter!!.setThingCardSurfaceAvailableHeight(0)
        bindFolderCardAppearancePanel()
        resetThingCardAppearanceToBodyPage()
        showThingCardAppearancePanelAnimated(panel)
        requestThingCardAppearancePanelSpaceUpdate()
    }

    private fun isFolderCardAppearancePanelActive(): Boolean {
        return mFolderCardAppearancePanelFolder != null
    }

    private fun bindFolderCardAppearancePanel() {
        val folder = mFolderCardAppearancePanelFolder ?: return
        val draft = mFolderCardAppearanceDraftPresentation ?: return

        mBindingFolderCardAppearancePanel = true
        mBindingThingCardAppearancePanel = false
        mTvThingCardAppearanceTitle!!.visibility = View.GONE
        mEtFolderCardAppearanceName!!.visibility = View.VISIBLE
        if (mEtFolderCardAppearanceName!!.text.toString() != folder.title) {
            mEtFolderCardAppearanceName!!.setText(folder.title)
            mEtFolderCardAppearanceName!!.setSelection(
                    mEtFolderCardAppearanceName!!.text.length
            )
        }
        applyThingCardAppearanceAccentText(mEtFolderCardAppearanceName)
        BackgroundUtil.applyEditTextUnderline(
                mEtFolderCardAppearanceName,
                getThingCardAppearanceAccentBackground()
        )

        mLlThingCardAppearanceSource!!.visibility = View.GONE
        mLlThingCardAppearanceVideoFrame!!.visibility = View.GONE
        clearThingCardAppearanceVideoFramePreview()
        val privateFolder = folder.isPrivate
        mLlThingCardAppearanceSpanControls!!.visibility =
                if (privateFolder) View.GONE else View.VISIBLE
        mTvThingCardAppearanceMediaPosition!!.visibility = View.GONE
        mTvFolderCardAppearanceSizeLabel!!.visibility =
                if (privateFolder) View.GONE else View.VISIBLE
        mLlThingCardAppearancePlacementControls!!.visibility =
                if (privateFolder) View.GONE else View.VISIBLE
        setThingCardAppearancePlacementControlsTopMargin(10)
        mBtThingCardAppearancePlacementTop!!.setText(
                R.string.folder_card_appearance_mode_summary
        )
        mBtThingCardAppearancePlacementBottom!!.setText(
                R.string.folder_card_appearance_mode_large
        )
        mBtThingCardAppearancePlacementLeft!!.visibility = View.GONE
        mBtThingCardAppearancePlacementRight!!.visibility = View.GONE
        mBtThingCardAppearancePlacementBackground!!.visibility = View.GONE
        mLlThingCardAppearanceSideWidth!!.visibility = View.GONE
        mBtThingCardAppearancePreciseCrop!!.visibility = View.GONE
        mLlThingCardAppearanceThumbnailRatio!!.visibility = View.GONE
        mLlThingCardAppearanceBackgroundControls!!.visibility = View.GONE
        mThingCardAppearanceBackgroundHeightSliderMinPercent = 0

        bindThingCardAppearanceAccentControls()
        if (!privateFolder) {
            bindThingCardAppearanceChoice(
                    mBtThingCardAppearanceSpanNormal!!,
                    draft.spanMode == ThingFolderCardPresentation.SPAN_NORMAL,
                    true
            )
            bindThingCardAppearanceChoice(
                    mBtThingCardAppearanceSpanFull!!,
                    draft.spanMode == ThingFolderCardPresentation.SPAN_FULL,
                    true
            )
            bindThingCardAppearanceChoice(
                    mBtThingCardAppearancePlacementTop!!,
                    draft.mode == ThingFolderCardPresentation.MODE_SUMMARY,
                    true
            )
            bindThingCardAppearanceChoice(
                    mBtThingCardAppearancePlacementBottom!!,
                    draft.mode == ThingFolderCardPresentation.MODE_THUMBNAILS,
                    true
            )
        }
        mBindingFolderCardAppearancePanel = false
    }

    private fun updateFolderCardAppearanceDraft(
        newDraft: ThingFolderCardPresentation?
    ) {
        val folder = mFolderCardAppearancePanelFolder ?: return
        if (newDraft == null) return
        if (folder.isPrivate) return
        mFolderCardAppearanceDraftPresentation = newDraft
        folder.cardPresentation = newDraft
        requestThingCardAppearancePreviewRefresh()
        bindFolderCardAppearancePanel()
        requestThingCardAppearancePanelSpaceUpdate()
    }

    private fun openThingCardAppearancePanel() {
        val thing: Thing = getSingleSelectedThingForAppearance() ?: return

        hideThingCardAppearanceKeyboard()
        clearFolderCardAppearanceDraft()
        mThingCardAppearanceMediaSources = ThingCardMediaHelper.getAvailableMediaSources(
                thing.attachment
        )

        mThingCardAppearancePanelThing = thing
        mThingCardAppearanceOriginal = thing.thingCardAppearance
        mThingCardAppearanceDraft = thing.thingCardAppearance
        mThingCardAppearanceOriginalBackground = thing.getBackground()
        mThingCardAppearanceSelectedListPosition =
            mThingManager!!.getListPositionForThingId(thing.id)
        if (mThingCardAppearanceSelectedListPosition < 0) {
            return
        }

        val panel: View = mThingCardAppearancePanel!!
        if (panel.visibility != View.VISIBLE) {
            mThingCardAppearancePanelOriginalPaddingBottom = mRecyclerView!!.paddingBottom
        }
        updateThingCardAppearancePanelWidth()
        mAdapter!!.setThingCardSurfaceAvailableHeight(
                getThingCardAppearancePreviewAvailableHeight()
        )
        bindThingCardAppearancePanel()
        resetThingCardAppearanceToBodyPage()
        showThingCardAppearancePanelAnimated(panel)
        requestThingCardAppearancePanelSpaceUpdate()
    }

    private fun bindThingCardAppearancePanel() {
        val thing = mThingCardAppearancePanelThing ?: return
        val draft = mThingCardAppearanceDraft ?: return
        val hasMediaSources = mThingCardAppearanceMediaSources.isNotEmpty()
        val mediaSourceHidden = hasMediaSources &&
                ThingCardAppearance.isMediaSourceNone(draft.mediaSourceKey)
        val mediaSource = if (hasMediaSources) {
            getCurrentThingCardAppearanceMediaSource()
        } else {
            null
        }
        if (hasMediaSources && !mediaSourceHidden && mediaSource == null) return

        mBindingThingCardAppearancePanel = true
        mBindingFolderCardAppearancePanel = false
        mTvThingCardAppearanceTitle!!.visibility = View.VISIBLE
        mEtFolderCardAppearanceName!!.visibility = View.GONE
        mTvFolderCardAppearanceSizeLabel!!.visibility = View.GONE
        setThingCardAppearancePlacementControlsTopMargin(0)
        mBtThingCardAppearancePlacementTop!!.setText(R.string.thing_card_appearance_placement_top)
        mBtThingCardAppearancePlacementBottom!!.setText(
                R.string.thing_card_appearance_placement_bottom
        )
        mBtThingCardAppearancePlacementLeft!!.visibility = View.VISIBLE
        mBtThingCardAppearancePlacementRight!!.visibility = View.VISIBLE
        mBtThingCardAppearancePlacementBackground!!.visibility = View.VISIBLE
        mTvThingCardAppearanceTitle!!.text = getString(R.string.thing_card_appearance_panel_title)
        applyThingCardAppearanceAccentText(mTvThingCardAppearanceTitle)

        mLlThingCardAppearanceSource!!.visibility =
                if (hasMediaSources) View.VISIBLE else View.GONE
        if (hasMediaSources) {
            mTvThingCardAppearanceSource!!.text = if (mediaSourceHidden) {
                getString(R.string.thing_card_appearance_source_none)
            } else {
                getThingCardAppearanceSourceText(
                        mediaSource!!,
                        draft.mediaSourceKey == null
                )
            }
        }
        bindThingCardAppearanceAccentControls()
        if (!hasMediaSources || mediaSourceHidden) {
            bindThingCardAppearanceSpanControls(draft)
            bindThingCardAppearanceMediaDependentControls(true)
            mBindingThingCardAppearancePanel = false
            return
        }
        bindThingCardAppearanceMediaDependentControls(false)
        bindThingCardAppearanceVideoFrameControls()
        bindThingCardAppearanceControls(draft)
        mBindingThingCardAppearancePanel = false
    }

    private fun setThingCardAppearancePlacementControlsTopMargin(topMarginDp: Int) {
        val lp = mLlThingCardAppearancePlacementControls!!.layoutParams
                as ViewGroup.MarginLayoutParams
        val topMargin = (topMarginDp * resources.displayMetrics.density).toInt()
        if (lp.topMargin != topMargin) {
            lp.topMargin = topMargin
            mLlThingCardAppearancePlacementControls!!.layoutParams = lp
        }
    }

    private fun bindThingCardAppearanceMediaDependentControls(mediaSourceHidden: Boolean) {
        val rootVisibility = if (mediaSourceHidden) View.GONE else View.VISIBLE
        mLlThingCardAppearanceVideoFrame!!.visibility = View.GONE
        mLlThingCardAppearanceSpanControls!!.visibility = View.VISIBLE
        mTvThingCardAppearanceMediaPosition!!.visibility = rootVisibility
        mLlThingCardAppearancePlacementControls!!.visibility = rootVisibility
        if (mediaSourceHidden) {
            clearThingCardAppearanceVideoFramePreview()
            mLlThingCardAppearanceSideWidth!!.visibility = View.GONE
            mBtThingCardAppearancePreciseCrop!!.visibility = View.GONE
            mLlThingCardAppearanceThumbnailRatio!!.visibility = View.GONE
            mLlThingCardAppearanceBackgroundControls!!.visibility = View.GONE
            mThingCardAppearanceBackgroundHeightSliderMinPercent = 0
        }
    }

    private fun bindThingCardAppearanceVideoFrameControls() {
        clearThingCardAppearanceVideoFramePreview()
        mLlThingCardAppearanceVideoFrame!!.visibility = View.GONE
    }

    private fun clearThingCardAppearanceVideoFramePreview() {
        val preview = mIvThingCardAppearanceVideoFramePreview ?: return
        Glide.with(this).clear(preview)
    }

    private fun bindThingCardAppearanceVideoFramePreviewForCurrentSource(frameMs: Long) {
        val source = getCurrentThingCardAppearanceMediaSource() ?: return
        if (!source.isVideo) return
        bindThingCardAppearanceVideoFramePreview(source.pathName, frameMs)
    }

    private fun bindThingCardAppearanceVideoFramePreview(pathName: String, frameMs: Long) {
        val preview = mIvThingCardAppearanceVideoFramePreview ?: return
        val targetW = if (preview.width > 0) preview.width else DisplayUtil.getThingCardWidth(this)
        val targetH = if (preview.height > 0) preview.height else (72 * resources.displayMetrics.density).toInt()
        Glide.with(this)
                .load(pathName)
                .signature(
                        getThingCardAppearanceMediaCacheSignature(
                                pathName,
                                targetW,
                                targetH,
                                frameMs
                        )
                )
                .apply(
                        RequestOptions.frameOf(max(0L, frameMs) * 1000L)
                                .set(
                                        VideoDecoder.FRAME_OPTION,
                                        MediaMetadataRetriever.OPTION_CLOSEST
                                )
                )
                .centerCrop()
                .into(preview)
    }

    private fun stepThingCardVideoFrame(direction: Int) {
        val seekBar = mSeekThingCardAppearanceVideoFrame ?: return
        val durationMs = seekBar.max
        if (durationMs <= 0) return

        val stepMs = getThingCardVideoFrameStepMs(durationMs)
        val progress = clampThingCardAppearanceSeekProgress(
                seekBar.progress + direction * stepMs,
                durationMs
        )
        seekBar.progress = progress
        updateThingCardVideoFrame(progress.toLong())
    }

    private fun getThingCardVideoFrameStepMs(durationMs: Int): Int {
        return max(1, min(1000, max(1, durationMs / 30)))
    }

    private fun getThingCardAppearanceMediaCacheSignature(
            pathName: String,
            targetW: Int,
            targetH: Int,
            videoFrameMs: Long?
    ): ObjectKey {
        val file = File(pathName)
        val fileSize = if (file.exists()) file.length() else 0L
        val lastModified = if (file.exists()) file.lastModified() else 0L
        return ObjectKey("$pathName:$fileSize:$lastModified:$videoFrameMs:$targetW:$targetH")
    }

    private fun getThingCardAppearanceVideoDurationMs(pathName: String): Int {
        val cached = mThingCardAppearanceVideoDurationCache[pathName]
        if (cached != null) return cached

        val duration = AttachmentHelper.getVideoDurationMs(pathName)
        val durationInt = if (duration > Int.MAX_VALUE) Int.MAX_VALUE else duration.toInt()
        mThingCardAppearanceVideoDurationCache[pathName] = durationInt
        return durationInt
    }

    private fun bindThingCardAppearanceControls(draft: ThingCardAppearance) {
        val fullSpan = draft.spanMode == Thing.THING_CARD_SPAN_FULL
        val mediaBackgroundEnabled = draft.mediaBackgroundEnabled
        bindThingCardAppearanceSpanControls(draft)

        val placement = draft.imagePlacement
        val sidePlacement = placement == Thing.THING_CARD_IMAGE_PLACEMENT_LEFT ||
                placement == Thing.THING_CARD_IMAGE_PLACEMENT_RIGHT
        val placementForUi = if (!fullSpan && sidePlacement) {
            Thing.THING_CARD_IMAGE_PLACEMENT_TOP
        } else {
            placement
        }
        bindThingCardAppearanceChoice(
                mBtThingCardAppearancePlacementTop!!,
                !mediaBackgroundEnabled &&
                        (placementForUi == Thing.THING_CARD_IMAGE_PLACEMENT_TOP ||
                                placementForUi == Thing.THING_CARD_IMAGE_PLACEMENT_DEFAULT),
                true
        )
        bindThingCardAppearanceChoice(
                mBtThingCardAppearancePlacementBottom!!,
                !mediaBackgroundEnabled &&
                        placementForUi == Thing.THING_CARD_IMAGE_PLACEMENT_BOTTOM,
                true
        )
        bindThingCardAppearanceChoice(
                mBtThingCardAppearancePlacementLeft!!,
                !mediaBackgroundEnabled &&
                        placementForUi == Thing.THING_CARD_IMAGE_PLACEMENT_LEFT,
                fullSpan
        )
        bindThingCardAppearanceChoice(
                mBtThingCardAppearancePlacementRight!!,
                !mediaBackgroundEnabled &&
                        placementForUi == Thing.THING_CARD_IMAGE_PLACEMENT_RIGHT,
                fullSpan
        )
        bindThingCardAppearanceChoice(
                mBtThingCardAppearancePlacementBackground!!,
                mediaBackgroundEnabled,
                true
        )

        bindThingCardAppearanceCropControls(draft, fullSpan, sidePlacement)
        bindThingCardAppearanceSideWidthControls(draft)
        bindThingCardAppearanceBackgroundControls(draft)
    }

    private fun bindThingCardAppearanceSpanControls(draft: ThingCardAppearance) {
        val fullSpan = draft.spanMode == Thing.THING_CARD_SPAN_FULL
        mLlThingCardAppearanceSpanControls!!.visibility = View.VISIBLE
        bindThingCardAppearanceChoice(
                mBtThingCardAppearanceSpanNormal!!,
                draft.spanMode == Thing.THING_CARD_SPAN_NORMAL,
                true
        )
        bindThingCardAppearanceChoice(mBtThingCardAppearanceSpanFull!!, fullSpan, true)
    }

    private fun bindThingCardAppearanceCropControls(
            draft: ThingCardAppearance,
            fullSpan: Boolean,
            sidePlacement: Boolean
    ) {
        val source = getCurrentThingCardAppearanceMediaSource() ?: run {
            mBtThingCardAppearancePreciseCrop!!.visibility = View.GONE
            mLlThingCardAppearanceThumbnailRatio!!.visibility = View.GONE
            return
        }

        mBtThingCardAppearancePreciseCrop!!.visibility = View.VISIBLE
        mBtThingCardAppearancePreciseCrop!!.setText(
                getThingCardAppearancePreciseCropTextRes(source)
        )
        val sourceAppearance = draft.sources[source.typePathName]
        val presentationKey = getActiveThingCardPresentationKey(draft)
        val aspectRatio = getThingCardPresentationTargetAspectRatio(
                draft,
                source,
                presentationKey,
                sourceAppearance
        )
        mLlThingCardAppearanceThumbnailRatio!!.visibility = View.VISIBLE
        mThingCardAppearanceRatioSlider?.let { slider ->
            val accentBackground = getThingCardAppearanceAccentBackground()
                    ?: ThingBackground.pure(getThingCardAppearanceAccentColor())
            slider.setAccentBackground(
                    accentBackground,
                    ContextCompat.getColor(this, R.color.app_chrome_on_surface_hint)
            )
            slider.refreshRange()
            slider.setRatio(aspectRatio)
        }
    }

    private fun bindThingCardAppearanceSideWidthControls(draft: ThingCardAppearance) {
        val source = getCurrentThingCardAppearanceMediaSource()
        val activePresentation = getActiveThingCardPresentationKey(draft)
        if (source == null || activePresentation != ThingCardAppearance.PRESENTATION_SIDE_PANEL) {
            mLlThingCardAppearanceSideWidth!!.visibility = View.GONE
            return
        }

        val sourceAppearance = draft.sources[source.typePathName]
        val aspectRatio = getThingCardPresentationTargetAspectRatio(
                draft,
                source,
                activePresentation,
                sourceAppearance
        )
        val widthPercent = getThingCardSidePanelProjectedWidthPercent(aspectRatio)
        mLlThingCardAppearanceSideWidth!!.visibility = View.VISIBLE
        mTvThingCardAppearanceSideWidth!!.text = getString(
                R.string.thing_card_appearance_cover_image_width_format,
                widthPercent
        )
        mSeekThingCardAppearanceSideWidth!!.max =
                getThingCardAppearanceSideMediaWidthMaxPercent() -
                        getThingCardAppearanceSideMediaWidthMinPercent()
        mSeekThingCardAppearanceSideWidth!!.progress =
                widthPercent - getThingCardAppearanceSideMediaWidthMinPercent()
    }

    private data class ThingCardRatioRange(
            val minRatio: Double,
            val maxRatio: Double
    )

    private data class ThingCardSidePanelProjection(
            val imageWidth: Int,
            val imageHeight: Int,
            val textWidth: Int
    )

    private fun getThingCardThumbnailRatioRange(): ThingCardRatioRange {
        mThingCardActiveRatioDragRange?.let { return it }
        val draft = mThingCardAppearanceDraft
        val source = getCurrentThingCardAppearanceMediaSource()
        if (draft == null) return ThingCardRatioRange(0.5, 2.0)
        return getThingCardRatioRange(draft, getActiveThingCardPresentationKey(draft), source)
    }

    private fun getThingCardRatioRange(
            draft: ThingCardAppearance,
            presentationKey: String,
            source: ThingCardMediaHelper.MediaSource?
    ): ThingCardRatioRange {
        return when (presentationKey) {
            ThingCardAppearance.PRESENTATION_SIDE_PANEL ->
                getThingCardSidePanelRatioRange()
            ThingCardAppearance.PRESENTATION_MEDIA_BACKGROUND ->
                getThingCardMediaBackgroundRatioRange()
            else -> getThingCardTopBottomRatioRange(draft)
        }
    }

    private fun getThingCardTopBottomRatioRange(
            draft: ThingCardAppearance
    ): ThingCardRatioRange {
        val cardWidth = getThingCardAppearancePreviewCardWidth()
        val availableHeight = getThingCardAppearancePreviewAvailableHeight()
        if (cardWidth <= 0 || availableHeight <= 0) {
            return ThingCardRatioRange(0.5, 2.0)
        }

        val minHeightPercent = resources.getInteger(
                if (draft.spanMode == Thing.THING_CARD_SPAN_FULL) {
                    R.integer.thing_card_full_span_thumbnail_min_height_percent
                } else {
                    R.integer.thing_card_normal_thumbnail_min_height_percent
                }
        )
        val maxHeightPercent = resources.getInteger(
                R.integer.thing_card_thumbnail_max_height_percent
        )
        val minHeight = max(1, availableHeight * minHeightPercent / 100)
        val maxHeight = max(
                minHeight,
                availableHeight * maxHeightPercent / 100
        )
        val minRatio = max(0.1, cardWidth.toDouble() / maxHeight.toDouble())
        val maxRatio = min(10.0, cardWidth.toDouble() / minHeight.toDouble())
        if (maxRatio <= minRatio) {
            return ThingCardRatioRange(minRatio, minRatio + 0.01)
        }
        return ThingCardRatioRange(minRatio, maxRatio)
    }

    private fun getThingCardSidePanelRatioRange(): ThingCardRatioRange {
        val contentWidth = getThingCardAppearancePreviewCardWidth()
        if (contentWidth <= 0) {
            return ThingCardRatioRange(0.2, 2.0)
        }

        val minWidth = getThingCardSidePanelWidthForPercent(
                getThingCardAppearanceSideMediaWidthMinPercent(),
                contentWidth
        )
        val maxWidth = getThingCardSidePanelWidthForPercent(
                getThingCardAppearanceSideMediaWidthMaxPercent(),
                contentWidth
        )
        val minHeight = getThingCardSidePanelMeasuredHeightForMediaWidth(minWidth)
        val maxHeight = getThingCardSidePanelMeasuredHeightForMediaWidth(maxWidth)
        val minBoundaryRatio = minWidth.toDouble() / minHeight.toDouble()
        val maxBoundaryRatio = max(minWidth + 1, maxWidth).toDouble() / maxHeight.toDouble()
        val minRatio = max(0.05, min(minBoundaryRatio, maxBoundaryRatio))
        val maxRatio = min(10.0, max(minBoundaryRatio, maxBoundaryRatio))
        if (maxRatio <= minRatio) {
            return ThingCardRatioRange(minRatio, minRatio + 0.01)
        }
        return ThingCardRatioRange(minRatio, maxRatio)
    }

    private fun getThingCardSidePanelProjectedWidthPercent(
            targetAspectRatio: Double
    ): Int {
        val contentWidth = getThingCardAppearancePreviewCardWidth()
        if (contentWidth <= 0) {
            return normalizeThingCardAppearanceSideMediaWidth(
                    mThingCardAppearanceDraft?.sideMediaWidthPercent
                            ?: ThingCardAppearance.DEFAULT_SIDE_MEDIA_WIDTH_PERCENT
            )
        }
        val projection = getThingCardSidePanelProjection(targetAspectRatio, contentWidth)
        val widthPercent = (projection.imageWidth * 100.0 / contentWidth.toDouble()).roundToInt()
        return normalizeThingCardAppearanceSideMediaWidth(widthPercent)
    }

    private fun getThingCardSidePanelTargetAspectRatioForWidthPercent(
            widthPercent: Int
    ): Double {
        val contentWidth = getThingCardAppearancePreviewCardWidth()
        if (contentWidth <= 0) return 0.75
        val imageWidth = getThingCardSidePanelWidthForPercent(widthPercent, contentWidth)
        val imageHeight = getThingCardSidePanelMeasuredHeightForMediaWidth(imageWidth)
        return max(0.05, min(10.0, imageWidth.toDouble() / imageHeight.toDouble()))
    }

    private fun getThingCardSidePanelProjection(
            targetAspectRatio: Double,
            contentWidth: Int = getThingCardAppearancePreviewCardWidth()
    ): ThingCardSidePanelProjection {
        val fallbackWidth = getThingCardSidePanelWidthForPercent(
                mThingCardAppearanceDraft?.sideMediaWidthPercent
                        ?: ThingCardAppearance.DEFAULT_SIDE_MEDIA_WIDTH_PERCENT,
                contentWidth
        )
        if (contentWidth <= 0 || targetAspectRatio <= 0.0 ||
                targetAspectRatio.isNaN() || targetAspectRatio.isInfinite()) {
            return getThingCardSidePanelProjectionForWidth(fallbackWidth, max(1, contentWidth))
        }

        var width = fallbackWidth
        var bestProjection = getThingCardSidePanelProjectionForWidth(width, contentWidth)
        var bestError = Int.MAX_VALUE
        repeat(THING_CARD_SIDE_PANEL_PROJECTION_MAX_ITERATIONS) {
            val projection = getThingCardSidePanelProjectionForWidth(width, contentWidth)
            val nextWidth = clampThingCardSidePanelMediaWidth(
                    (projection.imageHeight * targetAspectRatio).roundToInt(),
                    contentWidth
            )
            val error = abs(nextWidth - projection.imageWidth)
            if (error < bestError) {
                bestProjection = projection
                bestError = error
            }
            if (error <= THING_CARD_SIDE_PANEL_PROJECTION_TOLERANCE_PX) {
                return projection
            }
            width = nextWidth
        }
        return bestProjection
    }

    private fun getThingCardSidePanelProjectionForWidth(
            imageWidth: Int,
            contentWidth: Int
    ): ThingCardSidePanelProjection {
        val clampedWidth = clampThingCardSidePanelMediaWidth(imageWidth, contentWidth)
        val textWidth = max(1, contentWidth - clampedWidth)
        val imageHeight = getThingCardSidePanelMeasuredHeightForMediaWidth(clampedWidth)
        return ThingCardSidePanelProjection(
                imageWidth = clampedWidth,
                imageHeight = imageHeight,
                textWidth = textWidth
        )
    }

    private fun getThingCardSidePanelWidthForPercent(
            widthPercent: Int,
            contentWidth: Int
    ): Int {
        if (contentWidth <= 0) return 1
        val normalizedPercent = normalizeThingCardAppearanceSideMediaWidth(widthPercent)
        return clampThingCardSidePanelMediaWidth(
                contentWidth * normalizedPercent / 100,
                contentWidth
        )
    }

    private fun clampThingCardSidePanelMediaWidth(width: Int, contentWidth: Int): Int {
        if (contentWidth <= 0) return 1
        val minWidth = max(1, contentWidth * getThingCardAppearanceSideMediaWidthMinPercent() / 100)
        val maxWidth = max(minWidth, contentWidth * getThingCardAppearanceSideMediaWidthMaxPercent() / 100)
        return max(minWidth, min(maxWidth, width))
    }

    private fun getThingCardSidePanelMeasuredHeightForMediaWidth(mediaWidth: Int): Int {
        val minHeight = resources.getDimensionPixelSize(
                R.dimen.thing_card_full_span_side_image_min_height
        )
        val contentWidth = getThingCardAppearancePreviewCardWidth()
        if (contentWidth <= 0) return minHeight

        val holder = getThingCardAppearanceSelectedHolder() ?: return minHeight
        val textWidth = max(1, contentWidth - mediaWidth)
        val textContent = holder.llTextContent ?: return minHeight
        val textLp = textContent.layoutParams as LinearLayout.LayoutParams
        val oldTextWidth = textLp.width
        val oldTextHeight = textLp.height
        val oldTextWeight = textLp.weight
        val measuredHeight = try {
            textLp.width = textWidth
            textLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            textLp.weight = 0f
            val widthSpec = View.MeasureSpec.makeMeasureSpec(textWidth, View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            textContent.measure(widthSpec, heightSpec)
            textContent.measuredHeight
        } finally {
            textLp.width = oldTextWidth
            textLp.height = oldTextHeight
            textLp.weight = oldTextWeight
        }
        return max(minHeight, measuredHeight)
    }

    private fun getThingCardAppearanceSelectedHolder():
            BaseThingsAdapter.BaseThingViewHolder? {
        val recyclerView = mRecyclerView ?: return null
        val listPosition = mThingCardAppearanceSelectedListPosition
        if (listPosition < 0) return null
        return recyclerView.findViewHolderForAdapterPosition(listPosition)
                as? BaseThingsAdapter.BaseThingViewHolder
    }

    private fun getThingCardMediaBackgroundRatioRange(): ThingCardRatioRange {
        val cardWidth = getThingCardAppearancePreviewCardWidth()
        val availableHeight = getThingCardAppearancePreviewAvailableHeight()
        if (cardWidth <= 0 || availableHeight <= 0) {
            return ThingCardRatioRange(0.5, 2.0)
        }

        val naturalHeight = max(1, getThingCardBackgroundNaturalHeight())
        val maxHeight = max(
                naturalHeight,
                availableHeight * resources.getInteger(
                        R.integer.thing_card_media_background_home_max_height_percent
                ) / 100
        )
        val minRatio = max(0.05, cardWidth.toDouble() / maxHeight.toDouble())
        val maxRatio = min(10.0, cardWidth.toDouble() / naturalHeight.toDouble())
        if (maxRatio <= minRatio) {
            return ThingCardRatioRange(minRatio, minRatio + 0.01)
        }
        return ThingCardRatioRange(minRatio, maxRatio)
    }

    private fun clampThingCardAppearanceSeekProgress(value: Int, maxValue: Int): Int {
        return max(0, min(maxValue, value))
    }

    private fun clampThingCardTargetAspectRatio(
            draft: ThingCardAppearance,
            source: ThingCardMediaHelper.MediaSource?,
            presentationKey: String,
            targetAspectRatio: Double
    ): Double {
        if (targetAspectRatio.isNaN() || targetAspectRatio.isInfinite() ||
                targetAspectRatio <= 0.0) {
            return getThingCardPresentationTargetAspectRatio(
                    draft,
                    source ?: return 1.0,
                    presentationKey
            )
        }
        val range = getThingCardRatioRange(draft, presentationKey, source)
        return max(range.minRatio, min(range.maxRatio, targetAspectRatio))
    }

    private fun normalizeThingCardAppearanceSideMediaWidth(widthPercent: Int): Int {
        return max(
                getThingCardAppearanceSideMediaWidthMinPercent(),
                min(getThingCardAppearanceSideMediaWidthMaxPercent(), widthPercent)
        )
    }

    private fun getThingCardAppearanceSideMediaWidthMinPercent(): Int {
        return resources.getInteger(R.integer.thing_card_side_media_width_min_percent)
    }

    private fun getThingCardAppearanceSideMediaWidthMaxPercent(): Int {
        return resources.getInteger(R.integer.thing_card_side_media_width_max_percent)
    }

    private fun getThingCardAppearanceDefaultMaskStrength(): Double {
        return resources.getInteger(
                R.integer.thing_card_media_background_default_mask_strength_percent
        ) / 100.0
    }

    private fun bindThingCardAppearanceBackgroundControls(draft: ThingCardAppearance) {
        if (!draft.mediaBackgroundEnabled) {
            mThingCardAppearanceBackgroundHeightSliderMinPercent = 0
            mLlThingCardAppearanceBackgroundControls!!.visibility = View.GONE
            return
        }

        val source = getCurrentThingCardAppearanceMediaSource() ?: run {
            mThingCardAppearanceBackgroundHeightSliderMinPercent = 0
            mLlThingCardAppearanceBackgroundControls!!.visibility = View.GONE
            return
        }
        val sourceAppearance = draft.sources[source.typePathName]
        mLlThingCardAppearanceBackgroundControls!!.visibility = View.VISIBLE
        (mSeekThingCardAppearanceBackgroundHeight!!.parent as? View)?.visibility = View.GONE
        mSeekThingCardAppearanceBackgroundMask!!.progress =
                clampThingCardAppearanceSeekProgress(
                        ((sourceAppearance?.mediaBackgroundMaskStrength()
                                ?: getThingCardAppearanceDefaultMaskStrength()) * 100).toInt(),
                        100
                )
    }

    private fun bindThingCardAppearanceChoice(
            view: TextView,
            selected: Boolean,
            enabled: Boolean
    ) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1.0f else 0.38f
        if (selected && enabled) {
            applyThingCardAppearanceSelectedPill(view)
        } else {
            view.background = null
            setThingCardAppearancePlainTextColor(
                    view,
                    ContextCompat.getColor(this, R.color.app_chrome_on_surface_secondary)
            )
        }
    }

    private fun applyThingCardAppearanceSelectedPill(textView: TextView) {
        val accentBackground = getThingCardAppearanceAccentBackground()
        val background = if (accentBackground != null) {
            BackgroundUtil.makeTranslucentGradient(accentBackground, 255)
        } else {
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(getThingCardAppearanceAccentColor())
            }
        }
        background.cornerRadius = 1000f
        textView.background = background

        val light = if (accentBackground != null) {
                BackgroundUtil.isLight(accentBackground)
        } else {
                BackgroundUtil.isLight(getThingCardAppearanceAccentColor())
        }
        val foregroundColor = ContextCompat.getColor(
                this,
                if (light) R.color.black_86p else R.color.white_86p
        )
        setThingCardAppearancePlainTextColor(textView, foregroundColor)
    }

    private fun bindThingCardAppearanceAccentControls() {
        val accentBackground = getThingCardAppearanceAccentBackground()
        val accentColor = getThingCardAppearanceAccentColor()
        val uncheckedColor = ContextCompat.getColor(
                this,
                R.color.app_chrome_on_surface_secondary
        )

        TextViewCompat.setCompoundDrawableTintList(
                mTvThingCardAppearanceSource!!,
                ColorStateList.valueOf(uncheckedColor)
        )
        setThingCardAppearancePlainTextColor(
                mTvThingCardAppearanceSource,
                uncheckedColor
        )

        listOf(
                mSeekThingCardAppearanceVideoFrame,
                mSeekThingCardAppearanceSideWidth,
                mSeekThingCardAppearanceBackgroundMask,
                mSeekThingCardAppearanceBackgroundHeight
        ).forEach { seekBar ->
            if (seekBar != null) {
                DisplayUtil.setSeekBarBackground(
                        seekBar,
                        accentBackground ?: ThingBackground.pure(accentColor)
                )
            }
        }
        mThingCardAppearanceRatioSlider?.setAccentBackground(
                accentBackground ?: ThingBackground.pure(accentColor),
                ContextCompat.getColor(this, R.color.app_chrome_on_surface_hint)
        )

        applyThingCardAppearanceAccentText(mBtThingCardAppearanceVideoFramePrevious)
        applyThingCardAppearanceAccentText(mBtThingCardAppearanceVideoFrameNext)
        applyThingCardAppearanceAccentText(mBtThingCardAppearancePreciseCrop)
        applyThingCardAppearanceAccentText(mBtConfirmThingCardAppearance)
        bindThingCardAppearanceColorButton()
        setThingCardAppearancePlainTextColor(
                mBtCancelThingCardAppearance,
                ContextCompat.getColor(this, R.color.app_chrome_dialog_cancel)
        )
        installThingCardAppearanceRipples()
    }

    private fun installThingCardAppearanceRipples() {
        listOf(
                mTvThingCardAppearanceSource,
                mBtThingCardAppearanceVideoFramePrevious,
                mBtThingCardAppearanceVideoFrameNext,
                mBtThingCardAppearanceSpanNormal,
                mBtThingCardAppearanceSpanFull,
                mBtThingCardAppearancePlacementTop,
                mBtThingCardAppearancePlacementBottom,
                mBtThingCardAppearancePlacementLeft,
                mBtThingCardAppearancePlacementRight,
                mBtThingCardAppearancePlacementBackground
        ).forEach { view ->
            BackgroundUtil.installAppChromePillRipple(view, this)
        }
        listOf(
                mBtThingCardAppearancePreciseCrop,
                mBtCancelThingCardAppearance,
                mBtConfirmThingCardAppearance
        ).forEach { view ->
            BackgroundUtil.installAppChromeDialogActionButton(view, this)
        }
        BackgroundUtil.installAppChromeCircleRipple(mBtThingCardAppearanceChangeColor, this)
    }

    private fun bindThingCardAppearanceColorButton() {
        val button = mBtThingCardAppearanceChangeColor ?: return
        val background = getThingCardAppearanceAccentBackground()
            ?: App.defaultAccentBackground
        val raw = ContextCompat.getDrawable(this, R.drawable.act_change_color)
        button.setImageDrawable(BackgroundUtil.tintDrawable(resources, raw, background))
    }

    /** 进入颜色页：面板内容就地切换为编辑器（不另开面板）。 */
    private fun showThingCardAppearanceColorPage() {
        if (!isThingCardAppearancePanelShowing()) return
        hideThingCardAppearanceKeyboard()
        val editor = mThingCardAppearanceEditor ?: return
        editor.setBackground(getThingCardAppearanceAccentBackground())
        mTcaPreciseCropWasVisible = mBtThingCardAppearancePreciseCrop?.visibility == View.VISIBLE
        mTcaOnColorPage = true
        applyThingCardAppearancePageVisibility()
        requestThingCardAppearancePanelSpaceUpdate()
    }

    /** 返回外观设置页（页内导航，不提交，取消/确认仍代表整个面板会话）。 */
    private fun showThingCardAppearanceBodyPage() {
        hideThingCardAppearanceKeyboard()
        mTcaOnColorPage = false
        applyThingCardAppearancePageVisibility()
        requestThingCardAppearancePanelSpaceUpdate()
    }

    /** 开面板时复位到外观页（不动 precise crop，其可见性由 bind 决定）。 */
    private fun resetThingCardAppearanceToBodyPage() {
        mTcaOnColorPage = false
        mLlTcaTitleRow?.visibility = View.VISIBLE
        mLlTcaAppearanceBody?.visibility = View.VISIBLE
        mLlTcaColorPageTitle?.visibility = View.GONE
        mScrollTcaColorPage?.visibility = View.GONE
        mSepTcaTop?.visibility = View.GONE
        mSepTcaBottom?.visibility = View.GONE
    }

    private fun applyThingCardAppearancePageVisibility() {
        val onColor = mTcaOnColorPage
        mLlTcaTitleRow?.visibility = if (onColor) View.GONE else View.VISIBLE
        mLlTcaAppearanceBody?.visibility = if (onColor) View.GONE else View.VISIBLE
        mLlTcaColorPageTitle?.visibility = if (onColor) View.VISIBLE else View.GONE
        mScrollTcaColorPage?.visibility = if (onColor) View.VISIBLE else View.GONE
        if (onColor) {
            updateTcaSeparators()
        } else {
            mSepTcaTop?.visibility = View.GONE
            mSepTcaBottom?.visibility = View.GONE
        }
        mBtThingCardAppearancePreciseCrop?.visibility = when {
            onColor -> View.GONE
            mTcaPreciseCropWasVisible -> View.VISIBLE
            else -> View.GONE
        }
    }

    /** 颜色页顶/底分割线按滚动情况显隐（到顶隐藏顶部线，到底隐藏底部线）。 */
    private fun updateTcaSeparators() {
        if (!mTcaOnColorPage) return
        val sv = mScrollTcaColorPage ?: return
        mSepTcaTop?.visibility = if (sv.canScrollVertically(-1)) View.VISIBLE else View.INVISIBLE
        mSepTcaBottom?.visibility = if (sv.canScrollVertically(1)) View.VISIBLE else View.INVISIBLE
    }

    private fun updateThingCardAppearanceBackgroundDraft(background: ThingBackground) {
        val folder = mFolderCardAppearancePanelFolder
        if (folder != null) {
            folder.setBackground(background)
            requestThingCardAppearancePreviewRefresh()
            bindFolderCardAppearancePanel()
            requestThingCardAppearancePanelSpaceUpdate()
            if (mTcaOnColorPage) applyThingCardAppearancePageVisibility()
            refreshActivitySurfaceAndHeader()
            return
        }

        val thing = mThingCardAppearancePanelThing ?: return
        thing.setBackground(background)
        requestThingCardAppearancePreviewRefresh()
        bindThingCardAppearancePanel()
        requestThingCardAppearancePanelSpaceUpdate()
        if (mTcaOnColorPage) applyThingCardAppearancePageVisibility()
    }

    private fun openThingCardAppearanceCameraColorSampler() {
        doWithPermissionChecked(
            object : SimplePermissionCallback(this) {
                override fun onGranted() {
                    showThingCardAppearanceCameraColorSampler()
                }

                override fun onDenied() {
                    mNormalSnackbar!!.setMessage(R.string.error_permission_denied)
                    mNormalSnackbar!!.show()
                }
            },
            Def.Communication.REQUEST_PERMISSION_CAMERA_COLOR,
            Manifest.permission.CAMERA
        )
    }

    private fun showThingCardAppearanceCameraColorSampler() {
        val dialog = CameraColorSamplingDialogFragment()
        dialog.setInitialColor(getThingCardAppearanceAccentColor())
        dialog.setOnColorListener(object : CameraColorSamplingDialogFragment.OnColorListener {
            override fun onPreviewColor(color: Int) {}

            override fun onUseColor(color: Int) {
                // 回流到编辑器对应色区，再由编辑器回调更新草稿。
                mThingCardAppearanceEditor?.applyWorldColor(mTcaPendingWorldSlot, color)
            }

            override fun onCancelColorSampling() {}
        })
        dialog.show(fragmentManager, CameraColorSamplingDialogFragment.TAG)
    }

    private fun applyThingCardAppearanceAccentText(textView: TextView?) {
        if (textView == null) return
        val accentBackground = getThingCardAppearanceAccentBackground()
        if (accentBackground != null) {
            BackgroundUtil.applyTextBackground(textView, accentBackground)
        } else {
            setThingCardAppearancePlainTextColor(textView, getThingCardAppearanceAccentColor())
        }
    }

    private fun setThingCardAppearancePlainTextColor(textView: TextView?, color: Int) {
        if (textView == null) return
        textView.paint.setShader(null)
        textView.setTextColor(color)
        textView.invalidate()
    }

    private fun getThingCardAppearanceAccentBackground(): ThingBackground? {
        mFolderCardAppearancePanelFolder?.let { return it.getBackground() }
        return mThingCardAppearancePanelThing?.getBackground()
    }

    private fun getThingCardAppearanceAccentColor(): Int {
        return getThingCardAppearanceAccentBackground()?.representativeColor()
                ?: App.defaultAccentBackground.representativeColor()
    }

    private fun getThingCardAppearanceSourceText(
            mediaSource: ThingCardMediaHelper.MediaSource,
            defaultSource: Boolean
    ): String {
        val fileName = File(mediaSource.pathName).name
        val displayName = if (fileName.isEmpty()) mediaSource.pathName else fileName
        val mediaType = if (defaultSource) {
            getString(R.string.thing_card_appearance_source_default)
        } else {
            getThingCardAppearanceMediaTypeText(mediaSource)
        }
        return getString(R.string.thing_card_appearance_source_format, mediaType, displayName)
    }

    private fun getThingCardAppearanceMediaTypeText(
            mediaSource: ThingCardMediaHelper.MediaSource
    ): String {
        return getString(
                if (mediaSource.isVideo) {
                    R.string.thing_card_appearance_media_video
                } else {
                    R.string.thing_card_appearance_media_image
                }
        )
    }

    private fun getThingCardAppearancePreciseCropTextRes(
            mediaSource: ThingCardMediaHelper.MediaSource
    ): Int {
        return if (mediaSource.isVideo) {
            R.string.thing_card_appearance_precise_crop_video
        } else {
            R.string.thing_card_appearance_precise_crop
        }
    }

    @StringRes
    private fun getThingCardAppearanceThumbnailRatioTextRes(
            mediaSource: ThingCardMediaHelper.MediaSource
    ): Int {
        return if (mediaSource.isVideo) {
            R.string.thing_card_appearance_thumbnail_video_shape
        } else {
            R.string.thing_card_appearance_thumbnail_shape
        }
    }

    private fun showThingCardAppearanceSourceMenu() {
        val draft = mThingCardAppearanceDraft ?: return
        val thing = mThingCardAppearancePanelThing ?: return
        val sourceView = mTvThingCardAppearanceSource ?: return
        val defaultSource = ThingCardMediaHelper.resolveEffectiveMediaSource(
                thing.attachment,
                null
        ) ?: return
        val items = ArrayList<ThingCardAppearanceSourcePicker.Item>()
        items.add(
                ThingCardAppearanceSourcePicker.Item(
                        getThingCardAppearanceSourceText(defaultSource, true),
                        null
                )
        )
        var pickedIndex = 0
        for (i in mThingCardAppearanceMediaSources.indices) {
            val source = mThingCardAppearanceMediaSources[i]
            if (source.typePathName == draft.mediaSourceKey) {
                pickedIndex = i + 1
            }
            items.add(
                    ThingCardAppearanceSourcePicker.Item(
                            getThingCardAppearanceSourceText(source, false),
                            source.typePathName
                    )
            )
        }
        items.add(
                ThingCardAppearanceSourcePicker.Item(
                        getString(R.string.thing_card_appearance_source_none),
                        ThingCardAppearance.MEDIA_SOURCE_NONE
                )
        )
        if (ThingCardAppearance.isMediaSourceNone(draft.mediaSourceKey)) {
            pickedIndex = items.size - 1
        }

        mThingCardAppearanceSourcePicker?.dismiss()
        val picker = ThingCardAppearanceSourcePicker(
                this,
                window.decorView,
                items,
                pickedIndex,
                getThingCardAppearanceAccentBackground()
        ) { item ->
            updateThingCardAppearanceDraft(
                    mThingCardAppearanceDraft?.copy(mediaSourceKey = item.sourceKey)
            )
        }
        picker.setAnchor(sourceView)
        mThingCardAppearanceSourcePicker = picker
        picker.show()
    }

    private fun getCurrentThingCardAppearanceMediaSource(): ThingCardMediaHelper.MediaSource? {
        val thing = mThingCardAppearancePanelThing ?: return null
        val draft = mThingCardAppearanceDraft ?: return null
        return ThingCardMediaHelper.resolveEffectiveMediaSource(
                thing.attachment,
                draft.mediaSourceKey
        )
    }

    private fun getActiveThingCardPresentationKey(
            draft: ThingCardAppearance
    ): String {
        if (draft.mediaBackgroundEnabled) {
            return ThingCardAppearance.PRESENTATION_MEDIA_BACKGROUND
        }
        return if (draft.spanMode == Thing.THING_CARD_SPAN_FULL &&
                (draft.imagePlacement == Thing.THING_CARD_IMAGE_PLACEMENT_LEFT ||
                        draft.imagePlacement == Thing.THING_CARD_IMAGE_PLACEMENT_RIGHT)) {
            ThingCardAppearance.PRESENTATION_SIDE_PANEL
        } else {
            ThingCardAppearance.PRESENTATION_THUMBNAIL
        }
    }

    private fun getThingCardPresentationTargetAspectRatio(
            draft: ThingCardAppearance,
            source: ThingCardMediaHelper.MediaSource,
            presentationKey: String = getActiveThingCardPresentationKey(draft),
            sourceAppearance: ThingCardAppearance.SourceAppearance? =
                    draft.sources[source.typePathName]
    ): Double {
        val savedRatio = sourceAppearance
                ?.presentation(presentationKey)
                ?.targetAspectRatio
                ?.takeIf { it > 0.0 && !it.isNaN() && !it.isInfinite() }
        if (savedRatio != null) return savedRatio

        return when (presentationKey) {
            ThingCardAppearance.PRESENTATION_MEDIA_BACKGROUND ->
                sourceAppearance?.mediaBackgroundTargetAspectRatio()
                        ?: getCurrentMediaBackgroundDisplayAspectRatio()
                        ?: 1.0
            ThingCardAppearance.PRESENTATION_SIDE_PANEL ->
                sourceAppearance?.sidePanelTargetAspectRatio()
                        ?: getLegacySidePanelTargetAspectRatio(draft)
                        ?: 0.75
            else -> {
                val defaultRatio = getDefaultThingCardThumbnailTargetAspectRatio(draft)
                sourceAppearance?.thumbnailCropWithTargetRatio(defaultRatio)?.sourceAspectRatio
                        ?: defaultRatio
            }
        }
    }

    private fun getDefaultThingCardThumbnailTargetAspectRatio(
            draft: ThingCardAppearance
    ): Double {
        return if (draft.spanMode == Thing.THING_CARD_SPAN_FULL) 16.0 / 9.0 else 4.0 / 3.0
    }

    private fun getLegacySidePanelTargetAspectRatio(
            draft: ThingCardAppearance
    ): Double? {
        val contentWidth = getThingCardAppearancePreviewCardWidth()
        if (contentWidth <= 0) return null
        val sideWidth = contentWidth * normalizeThingCardAppearanceSideMediaWidth(
                draft.sideMediaWidthPercent
        ) / 100
        val measuredSideHeight = getThingCardSidePanelMeasuredHeightForMediaWidth(sideWidth)
        return max(0.05, min(10.0, sideWidth.toDouble() / measuredSideHeight.toDouble()))
    }

    private fun getThingCardPresentationCrop(
            sourceAppearance: ThingCardAppearance.SourceAppearance?,
            presentationKey: String
    ): ThingCardAppearance.ThingCardMediaCrop {
        val presentationCrop = sourceAppearance?.presentation(presentationKey)?.crop
        if (presentationCrop != null) return presentationCrop
        return when (presentationKey) {
            ThingCardAppearance.PRESENTATION_MEDIA_BACKGROUND ->
                toThingCardMediaCrop(sourceAppearance?.mediaBackgroundCrop())
            ThingCardAppearance.PRESENTATION_SIDE_PANEL ->
                toThingCardMediaCrop(sourceAppearance?.sidePanelCrop())
            else -> toThingCardMediaCrop(sourceAppearance?.thumbnailCropWithTargetRatio(1.0))
        }
    }

    private fun toThingCardMediaCrop(
            crop: ThingCardAppearance.ThingCardThumbnailCrop?
    ): ThingCardAppearance.ThingCardMediaCrop {
        return ThingCardAppearance.ThingCardMediaCrop(
                centerX = crop?.centerX ?: ThingCardAppearance.DEFAULT_CROP_CENTER,
                centerY = crop?.centerY ?: ThingCardAppearance.DEFAULT_CROP_CENTER,
                scale = crop?.scale ?: ThingCardAppearance.DEFAULT_USER_SCALE
        )
    }

    private fun toThingCardMediaCrop(
            crop: ThingCardAppearance.ThingCardMediaBackgroundCrop?
    ): ThingCardAppearance.ThingCardMediaCrop {
        return ThingCardAppearance.ThingCardMediaCrop(
                centerX = crop?.centerX ?: ThingCardAppearance.DEFAULT_CROP_CENTER,
                centerY = crop?.centerY ?: ThingCardAppearance.DEFAULT_CROP_CENTER,
                scale = crop?.scale ?: ThingCardAppearance.DEFAULT_USER_SCALE
        )
    }

    private fun updateThingCardCurrentCrop(
            centerX: Double? = null,
            centerY: Double? = null,
            scale: Double? = null,
            sourceAspectRatio: Double? = null,
            videoFrameMs: Long? = null
    ) {
        val draft = mThingCardAppearanceDraft ?: return
        val source = getCurrentThingCardAppearanceMediaSource() ?: return
        val presentationKey = getActiveThingCardPresentationKey(draft)
        updateCurrentThingCardSourceAppearance { sourceAppearance ->
            val existing = sourceAppearance.presentation(presentationKey)
            val crop = getThingCardPresentationCrop(sourceAppearance, presentationKey)
            val newCrop = crop.copy(
                    centerX = centerX ?: crop.centerX,
                    centerY = centerY ?: crop.centerY,
                    scale = scale ?: crop.scale
            )
            val presentation = (existing ?: ThingCardAppearance.MediaPresentationAppearance())
                    .copy(
                            targetAspectRatio = sourceAspectRatio
                                    ?: existing?.targetAspectRatio
                                    ?: getThingCardPresentationTargetAspectRatio(
                                            draft,
                                            source,
                                            presentationKey,
                                            sourceAppearance
                                    ),
                            crop = newCrop
                    )
            sourceAppearance
                    .withPresentation(presentationKey, presentation)
                    .copy(videoFrameMs = videoFrameMs ?: sourceAppearance.videoFrameMs)
        }
    }

    private fun openThingCardCropEditor() {
        (fragmentManager.findFragmentByTag(MediaCropAppearanceDialogFragment.TAG)
                as? android.app.DialogFragment)?.dismissAllowingStateLoss()
        MediaCropAppearanceDialogFragment.newInstance(
                MediaCropAppearanceDialogFragment.REQUEST_THING_CARD_CROP
        ).show(
                fragmentManager,
                MediaCropAppearanceDialogFragment.TAG
        )
    }

    override fun getMediaCropAppearanceDialogWidthPx(
            fragment: MediaCropAppearanceDialogFragment,
            requestKey: String,
            position: Int
    ): Int {
        if (requestKey != MediaCropAppearanceDialogFragment.REQUEST_THING_CARD_CROP) {
            return ViewGroup.LayoutParams.WRAP_CONTENT
        }
        val density = resources.displayMetrics.density
        val windowHorizontalMargin = (density * 16).toInt()
        return getThingCardAppearanceConstrainedWidth(
                DisplayUtil.getScreenSize(this).x - windowHorizontalMargin * 2
        )
    }

    override fun createMediaCropAppearanceDialogContent(
            fragment: MediaCropAppearanceDialogFragment,
            requestKey: String,
            position: Int
    ): MediaCropAppearanceDialogFragment.Content? {
        if (requestKey != MediaCropAppearanceDialogFragment.REQUEST_THING_CARD_CROP) return null
        val draft = mThingCardAppearanceDraft ?: return null
        val source = getCurrentThingCardAppearanceMediaSource() ?: return null
        val bitmap = loadThingCardCropEditorBitmap(source) ?: return null
        val sourceAppearance = draft.sources[source.typePathName]
        val presentationKey = getActiveThingCardPresentationKey(draft)
        val crop = getThingCardPresentationCrop(sourceAppearance, presentationKey)
        val cropCenterX = crop.centerX
        val cropCenterY = crop.centerY
        val cropScale = crop.scale

        val density = resources.displayMetrics.density
        val contentHorizontalMargin = resources.getDimensionPixelSize(
                R.dimen.app_chrome_dialog_title_margin_horizontal
        )
        val dialogWidth = getMediaCropAppearanceDialogWidthPx(fragment, requestKey, position)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.clipChildren = false
        root.clipToPadding = false
        root.setBackgroundResource(R.drawable.bg_app_chrome_surface_elevated_rounded)

        val title = TextView(this)
        title.setText(getThingCardAppearancePreciseCropTextRes(source))
        applyThingCardAppearanceAccentText(title)
        title.setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX,
                resources.getDimension(R.dimen.app_chrome_dialog_title_text_size)
        )
        title.setTypeface(title.typeface, Typeface.BOLD)
        title.gravity = android.view.Gravity.CENTER_VERTICAL
        root.addView(
                title,
                LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(
                            contentHorizontalMargin,
                            resources.getDimensionPixelSize(
                                    R.dimen.app_chrome_dialog_title_margin_top
                            ),
                            contentHorizontalMargin,
                            0
                    )
                }
        )

        val rawTargetAspectRatio = getThingCardCropEditorTargetAspectRatio(draft, source)
        val ratioRange = getThingCardThumbnailRatioRange()
        val targetAspectRatio = RatioSlider.quantize(
                rawTargetAspectRatio, ratioRange.minRatio, ratioRange.maxRatio
        )
        val initialVideoFrameMs = if (source.isVideo) {
            sourceAppearance?.videoFrameMs ?: 0L
        } else {
            null
        }
        val videoCropView: ThingCardVideoCropEditorView?
        val cropEditor: ThingCardCropEditorController
        val cropEditorView: View
        if (source.isVideo) {
            val view = ThingCardVideoCropEditorView(this)
            view.setAccentBackground(
                    getThingCardAppearanceAccentBackground()
                            ?: ThingBackground.pure(getThingCardAppearanceAccentColor())
            )
            view.setCropVideo(
                    source.pathName,
                    targetAspectRatio,
                    cropCenterX,
                    cropCenterY,
                    cropScale,
                    initialVideoFrameMs ?: 0L,
                    bitmap,
                    bitmap.width,
                    bitmap.height
            )
            videoCropView = view
            cropEditor = view
            cropEditorView = view
        } else {
            val view = ThingCardCropEditorView(this)
            view.setCropBitmap(
                    bitmap,
                    targetAspectRatio,
                    cropCenterX,
                    cropCenterY,
                    cropScale
            )
            videoCropView = null
            cropEditor = view
            cropEditorView = view
        }
        root.addView(
                cropEditorView,
                LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        getThingCardCropEditorPreviewHeight(
                                bitmap,
                                dialogWidth,
                                contentHorizontalMargin
                        )
                ).apply {
                    setMargins(
                            contentHorizontalMargin,
                            (density * 6).toInt(),
                            contentHorizontalMargin,
                            0
                    )
                }
        )
        val videoFrameControls = if (source.isVideo && videoCropView != null) {
            createThingCardCropEditorVideoFrameControls(
                    videoCropView,
                    source,
                    initialVideoFrameMs ?: 0L
            )
        } else {
            null
        }
        if (videoFrameControls != null) {
            root.addView(
                    videoFrameControls.view,
                    LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(
                                contentHorizontalMargin,
                                (density * 6).toInt(),
                                contentHorizontalMargin,
                                0
                        )
                    }
            )
        }
        root.addView(
                createThingCardCropEditorRatioControls(
                        cropEditor,
                        targetAspectRatio,
                        getThingCardAppearanceThumbnailRatioTextRes(source)
                ),
                LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(
                            contentHorizontalMargin,
                            (density * 6).toInt(),
                            contentHorizontalMargin,
                            0
                    )
                }
        )

        val buttons = LinearLayout(this)
        buttons.gravity = android.view.Gravity.RIGHT or android.view.Gravity.CENTER_VERTICAL
        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.clipChildren = false
        buttons.clipToPadding = false
        buttons.addView(createThingCardCropEditorButton(R.string.cancel, false) {
            fragment.dismiss()
        })
        buttons.addView(createThingCardCropEditorButton(R.string.confirm) {
            val confirmedAspectRatio = cropEditor.getTargetAspectRatio()
            val ratioChanged = abs(confirmedAspectRatio - targetAspectRatio) > 0.0001
            val confirmedVideoFrameMs = videoFrameControls?.getFrameMs?.invoke()
            val videoFrameChanged = confirmedVideoFrameMs != null &&
                    confirmedVideoFrameMs != initialVideoFrameMs
            updateThingCardCurrentCrop(
                    centerX = cropEditor.getCropCenterX(),
                    centerY = cropEditor.getCropCenterY(),
                    scale = cropEditor.getCropUserScale(),
                    sourceAspectRatio = confirmedAspectRatio,
                    videoFrameMs = confirmedVideoFrameMs
            )
            if (!ratioChanged && !videoFrameChanged) {
                applyCurrentThingCardCropToVisiblePreview()
            }
            fragment.dismiss()
        })
        root.addView(
                buttons,
                LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(
                            resources.getDimensionPixelSize(
                                    R.dimen.app_chrome_dialog_action_row_margin_horizontal
                            ),
                            resources.getDimensionPixelSize(
                                    R.dimen.app_chrome_dialog_action_row_margin_top
                            ),
                            resources.getDimensionPixelSize(
                                    R.dimen.app_chrome_dialog_action_row_margin_horizontal
                            ),
                            resources.getDimensionPixelSize(
                                    R.dimen.app_chrome_dialog_action_row_margin_bottom
                            )
                    )
                }
        )

        return MediaCropAppearanceDialogFragment.Content(root) {
            videoFrameControls?.stopPlayback?.invoke()
            videoCropView?.release()
        }
    }

    private fun updateThingCardAppearancePanelWidth() {
        val panel = mThingCardAppearancePanel ?: return
        val lp = panel.layoutParams as? FrameLayout.LayoutParams ?: return
        val parentWidth = (panel.parent as? View)?.width ?: 0
        val containerWidth = if (parentWidth > 0) {
            parentWidth
        } else {
            DisplayUtil.getScreenSize(this).x
        }
        val availableWidth = containerWidth - lp.leftMargin - lp.rightMargin
        val targetWidth = getThingCardAppearanceConstrainedWidth(availableWidth)
        val targetGravity = android.view.Gravity.BOTTOM or
                android.view.Gravity.CENTER_HORIZONTAL
        if (lp.width != targetWidth || lp.gravity != targetGravity) {
            lp.width = targetWidth
            lp.gravity = targetGravity
            panel.layoutParams = lp
        }
    }

    private fun updateThingCardAppearancePanelMaxHeight() {
        val panel = mThingCardAppearancePanel as? ScrollAwareColumn ?: return
        val parent = panel.parent as? View ?: return
        val lp = panel.layoutParams as? FrameLayout.LayoutParams ?: return
        val parentHeight = parent.height
        if (parentHeight <= 0) {
            panel.maxMeasuredHeightPx = 0
            return
        }

        val topReserved = (mActionbar?.bottom ?: 0) + getThingCardListItemSpacingPx()
        val availableHeight = parentHeight - topReserved - lp.topMargin - lp.bottomMargin
        val cardReserved = getThingCardAppearancePanelCardReserveHeight(availableHeight)
        val maxHeight = availableHeight - cardReserved
        panel.maxMeasuredHeightPx = max(0, maxHeight)
    }

    private fun getThingCardAppearancePanelCardReserveHeight(availableHeight: Int): Int {
        if (availableHeight <= 0) return 0

        val spacing = getThingCardListItemSpacingPx()
        val peekHeight = resources.getDimensionPixelSize(
                R.dimen.thing_card_appearance_panel_card_peek_height
        )
        return min(availableHeight, peekHeight + spacing)
    }

    private fun getThingCardAppearanceConstrainedWidth(availableWidth: Int): Int {
        val maxWidth = resources.getDimensionPixelSize(R.dimen.thing_card_appearance_max_width)
        return min(max(1, availableWidth), maxWidth)
    }

    private fun getThingCardCropEditorPreviewHeight(
            bitmap: Bitmap,
            dialogWidth: Int,
            contentHorizontalMargin: Int
    ): Int {
        val screenSize = DisplayUtil.getScreenSize(this)
        val contentWidth = max(1, dialogWidth - contentHorizontalMargin * 2)
        val rawHeight = (contentWidth * bitmap.height.toDouble() /
                max(1, bitmap.width).toDouble()).toInt()
        val minHeight = (resources.displayMetrics.density * 160).toInt()
        val maxHeight = (screenSize.y * 0.52f).toInt()
        return max(minHeight, min(maxHeight, rawHeight))
    }

    private data class ThingCardCropEditorVideoFrameControls(
            val view: View,
            val getFrameMs: () -> Long,
            val stopPlayback: () -> Unit
    )

    private fun createThingCardCropEditorVideoFrameControls(
            cropView: ThingCardVideoCropEditorView,
            source: ThingCardMediaHelper.MediaSource,
            initialFrameMs: Long
    ): ThingCardCropEditorVideoFrameControls? {
        val durationMs = getThingCardAppearanceVideoDurationMs(source.pathName)
        if (durationMs <= 0) return null
        val maxFrameMs = getThingCardVideoFrameMaxMs(durationMs)

        val density = resources.displayMetrics.density
        var frameMs = clampThingCardVideoFrameMs(initialFrameMs, durationMs)
        var updatingSeekBarFromVideo = false

        val container = LinearLayout(this)
        container.orientation = LinearLayout.HORIZONTAL
        container.gravity = android.view.Gravity.CENTER_VERTICAL

        lateinit var playPauseButton: ImageView
        lateinit var seekBar: SeekBar

        fun updatePlayPauseButton(isPlaying: Boolean = cropView.isPlaying()) {
            playPauseButton.setImageResource(
                    if (isPlaying) {
                        R.drawable.ic_thing_card_crop_pause
                    } else {
                        R.drawable.ic_thing_card_crop_play
                    }
            )
            playPauseButton.contentDescription = getString(
                    if (isPlaying) {
                        R.string.thing_card_appearance_video_frame_pause
                    } else {
                        R.string.thing_card_appearance_video_frame_play
                    }
            )
            playPauseButton.setColorFilter(
                    ContextCompat.getColor(this, R.color.app_chrome_on_surface_secondary)
            )
        }

        fun setFrameMs(newFrameMs: Long, seekVideo: Boolean = true) {
            frameMs = clampThingCardVideoFrameMs(newFrameMs, durationMs)
            if (seekBar.progress != frameMs.toInt()) {
                updatingSeekBarFromVideo = !seekVideo
                seekBar.progress = frameMs.toInt()
                updatingSeekBarFromVideo = false
            }
            if (seekVideo) {
                cropView.seekTo(frameMs)
            }
        }

        playPauseButton = createThingCardCropEditorIconButton(
                R.drawable.ic_thing_card_crop_play,
                R.string.thing_card_appearance_video_frame_play
        )
        playPauseButton.setOnClickListener {
            if (cropView.isPlaying()) {
                cropView.pause()
            } else {
                if (frameMs >= maxFrameMs) {
                    setFrameMs(0L, true)
                }
                cropView.play()
            }
            updatePlayPauseButton()
        }
        updatePlayPauseButton()
        container.addView(
                playPauseButton,
                LinearLayout.LayoutParams(
                        (density * 40).toInt(),
                        (density * 40).toInt()
                )
        )

        val stopButton = createThingCardCropEditorIconButton(
                R.drawable.ic_thing_card_crop_stop,
                R.string.thing_card_appearance_video_frame_stop
        )
        stopButton.setOnClickListener {
            cropView.stopPlayback()
            setFrameMs(0L, false)
            updatePlayPauseButton()
        }
        container.addView(
                stopButton,
                LinearLayout.LayoutParams(
                        (density * 40).toInt(),
                        (density * 40).toInt()
                ).apply {
                    marginEnd = (density * 6).toInt()
                    rightMargin = (density * 6).toInt()
                }
        )

        seekBar = SeekBar(this)
        seekBar.max = maxFrameMs
        seekBar.progress = frameMs.toInt()
        DisplayUtil.setSeekBarBackground(
                seekBar,
                getThingCardAppearanceAccentBackground()
                        ?: ThingBackground.pure(getThingCardAppearanceAccentColor())
        )
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
            ) {
                if (fromUser && !updatingSeekBarFromVideo) {
                    setFrameMs(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                cropView.pause()
                updatePlayPauseButton()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar ?: return
                setFrameMs(seekBar.progress.toLong())
            }
        })
        cropView.onPositionChanged = { currentFrameMs ->
            setFrameMs(currentFrameMs, false)
        }
        cropView.onPlayingChanged = { isPlaying ->
            updatePlayPauseButton(isPlaying)
        }
        cropView.seekTo(frameMs)
        container.addView(
                seekBar,
                LinearLayout.LayoutParams(
                        0,
                        (density * 40).toInt(),
                        1f
                )
        )

        return ThingCardCropEditorVideoFrameControls(
                view = container,
                getFrameMs = { cropView.getCurrentFrameMs() },
                stopPlayback = { cropView.pause() }
        )
    }

    private fun createThingCardCropEditorIconButton(
            iconRes: Int,
            contentDescriptionRes: Int
    ): ImageView {
        val button = ImageView(this)
        button.setImageResource(iconRes)
        button.contentDescription = getString(contentDescriptionRes)
        button.setColorFilter(
                ContextCompat.getColor(this, R.color.app_chrome_on_surface_secondary)
        )
        button.scaleType = ImageView.ScaleType.CENTER
        button.isClickable = true
        button.isFocusable = true
        val padding = (resources.displayMetrics.density * 8).toInt()
        button.setPadding(padding, padding, padding, padding)
        BackgroundUtil.installAppChromeCircleRipple(button, this)
        return button
    }

    private fun createThingCardCropEditorRatioControls(
            cropView: ThingCardCropEditorController,
            initialAspectRatio: Double,
            @StringRes labelRes: Int
    ): View {
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL

        val label = TextView(this)
        label.setText(labelRes)
        label.setTextColor(ContextCompat.getColor(this, R.color.app_chrome_on_surface_hint))
        label.textSize = 13f
        label.gravity = android.view.Gravity.CENTER_VERTICAL
        container.addView(
                label,
                LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        (resources.displayMetrics.density * 22).toInt()
                )
        )

        val accentBackground = getThingCardAppearanceAccentBackground()
        val accentColor = getThingCardAppearanceAccentColor()
        val ratioSlider = RatioSlider(this)
        ratioSlider.setAccentBackground(
                accentBackground ?: ThingBackground.pure(accentColor),
                ContextCompat.getColor(this, R.color.app_chrome_on_surface_hint)
        )
        ratioSlider.setRangeProvider {
            val range = getThingCardThumbnailRatioRange()
            range.minRatio to range.maxRatio
        }
        ratioSlider.setRatio(initialAspectRatio)
        ratioSlider.onRatioChanged = { snapped ->
            cropView.setTargetAspectRatio(snapped)
        }
        container.addView(
                ratioSlider,
                LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        (resources.displayMetrics.density * 52).toInt()
                )
        )
        return container
    }

    private fun createThingCardCropEditorButton(
            textRes: Int,
            useAccent: Boolean = true,
            onClick: () -> Unit
    ): TextView {
        val button = TextView(this)
        if (textRes != 0) {
            button.setText(textRes)
        }
        if (useAccent) {
            applyThingCardAppearanceAccentText(button)
        } else {
            setThingCardAppearancePlainTextColor(
                    button,
                    ContextCompat.getColor(this, R.color.app_chrome_dialog_cancel)
            )
        }
        button.gravity = android.view.Gravity.CENTER
        button.includeFontPadding = false
        button.setAllCaps(true)
        button.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                resources.getDimensionPixelSize(
                        R.dimen.app_chrome_dialog_action_button_height
                )
        ).apply {
            if (useAccent) {
                marginEnd = resources.getDimensionPixelSize(
                        R.dimen.app_chrome_dialog_action_button_margin_end
                )
                rightMargin = resources.getDimensionPixelSize(
                        R.dimen.app_chrome_dialog_action_button_margin_end
                )
            }
        }
        BackgroundUtil.installAppChromeDialogActionButton(button, this)
        button.setOnClickListener { onClick() }
        return button
    }

    private fun applyCurrentThingCardCropToVisiblePreview() {
        val thing = mThingCardAppearancePanelThing ?: return
        val recyclerView = mRecyclerView ?: return
        val listPosition = mThingCardAppearanceSelectedListPosition
        if (listPosition < 0) return
        val holder = recyclerView.findViewHolderForAdapterPosition(listPosition)
                as? BaseThingsAdapter.BaseThingViewHolder
        mAdapter?.applyThingCardMediaCropToBoundHolder(holder, thing)
    }

    private fun applyCurrentThingCardMediaBackgroundHeightToVisiblePreview(): Boolean {
        val thing = mThingCardAppearancePanelThing ?: return false
        val recyclerView = mRecyclerView ?: return false
        val listPosition = mThingCardAppearanceSelectedListPosition
        if (listPosition < 0) return false
        val holder = recyclerView.findViewHolderForAdapterPosition(listPosition)
                as? BaseThingsAdapter.BaseThingViewHolder
        val applied = mAdapter?.applyThingCardMediaBackgroundHeightToBoundHolder(holder, thing) == true
        if (applied) {
            holder?.itemView?.requestLayout()
            recyclerView.requestLayout()
        }
        return applied
    }

    private fun getThingCardCropEditorTargetAspectRatio(
            draft: ThingCardAppearance,
            source: ThingCardMediaHelper.MediaSource
    ): Double {
        val sourceAppearance = draft.sources[source.typePathName]
        return getThingCardPresentationTargetAspectRatio(
                draft,
                source,
                getActiveThingCardPresentationKey(draft),
                sourceAppearance
        )
    }

    private fun getThingCardMediaBackgroundCropTargetHeight(
            draft: ThingCardAppearance,
            source: ThingCardMediaHelper.MediaSource,
            holder: BaseThingsAdapter.BaseThingViewHolder?,
            cardWidth: Int
    ): Int {
        if (cardWidth <= 0) return holder?.cv?.height ?: 0

        val sourceAppearance = draft.sources[source.typePathName]
        val targetAspectRatio = sourceAppearance?.mediaBackgroundTargetAspectRatio()
        var targetMinHeight = 0
        if (targetAspectRatio != null && targetAspectRatio > 0.0) {
            val maxHeight = getThingCardAppearancePreviewAvailableHeight() *
                    resources.getInteger(
                            R.integer.thing_card_media_background_home_max_height_percent
                    ) / 100
            targetMinHeight = min((cardWidth / targetAspectRatio).toInt(), maxHeight)
        }

        val naturalHeight = holder?.let {
            measureThingCardMediaBackgroundNaturalHeight(it, cardWidth)
        } ?: 0
        val measuredHeight = holder?.cv?.height ?: 0
        val computedHeight = max(targetMinHeight, naturalHeight)
        return if (computedHeight > 0) computedHeight else measuredHeight
    }

    private fun measureThingCardCropEditorNaturalHeight(view: View, width: Int): Int {
        if (width <= 0) return view.height
        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(widthSpec, heightSpec)
        return view.measuredHeight
    }

    private fun measureThingCardMediaBackgroundNaturalHeight(
            holder: BaseThingsAdapter.BaseThingViewHolder,
            width: Int
    ): Int {
        val textContent = holder.llTextContent ?: return 0
        val spacer = holder.vBottomStatusSpacer
        val textLp = textContent.layoutParams as LinearLayout.LayoutParams
        val oldTextHeight = textLp.height
        val oldTextWeight = textLp.weight
        val spacerLp = spacer?.layoutParams as? LinearLayout.LayoutParams
        val oldSpacerVisibility = spacer?.visibility
        val oldSpacerHeight = spacerLp?.height
        val oldSpacerWeight = spacerLp?.weight

        return try {
            if (textLp.height != ViewGroup.LayoutParams.WRAP_CONTENT || textLp.weight != 0f) {
                textLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                textLp.weight = 0f
                textContent.layoutParams = textLp
            }
            if (spacer != null && spacerLp != null &&
                    (spacer.visibility != View.GONE ||
                            spacerLp.height != 0 ||
                            spacerLp.weight != 0f)) {
                spacer.visibility = View.GONE
                spacerLp.height = 0
                spacerLp.weight = 0f
                spacer.layoutParams = spacerLp
            }
            measureThingCardCropEditorNaturalHeight(textContent, width)
        } finally {
            if (textLp.height != oldTextHeight || textLp.weight != oldTextWeight) {
                textLp.height = oldTextHeight
                textLp.weight = oldTextWeight
                textContent.layoutParams = textLp
            }
            if (spacer != null && spacerLp != null) {
                if (oldSpacerVisibility != null && spacer.visibility != oldSpacerVisibility) {
                    spacer.visibility = oldSpacerVisibility
                }
                if (oldSpacerHeight != null && oldSpacerWeight != null &&
                        (spacerLp.height != oldSpacerHeight ||
                                spacerLp.weight != oldSpacerWeight)) {
                    spacerLp.height = oldSpacerHeight
                    spacerLp.weight = oldSpacerWeight
                    spacer.layoutParams = spacerLp
                }
            }
        }
    }

    private fun loadThingCardCropEditorBitmap(
            source: ThingCardMediaHelper.MediaSource
    ): Bitmap? {
        return if (source.isVideo) {
            loadThingCardCropEditorVideoFrame(source)
        } else {
            decodeThingCardCropEditorImage(source.pathName)
        }
    }

    private fun loadThingCardCropEditorVideoFrame(
            source: ThingCardMediaHelper.MediaSource
    ): Bitmap? {
        val frameMs = mThingCardAppearanceDraft
                ?.sources
                ?.get(source.typePathName)
                ?.videoFrameMs
                ?: 0L
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(source.pathName)
            val durationMs = getThingCardAppearanceVideoDurationMs(source.pathName)
            val clampedFrameMs = clampThingCardVideoFrameMs(frameMs, durationMs)
            retriever.getFrameAtTime(
                    clampedFrameMs * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST
            )
        } finally {
            retriever.release()
        }
    }

    private fun decodeThingCardCropEditorImage(pathName: String): Bitmap? {
        val maxSize = 2048
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeFile(pathName, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            val decoded = BitmapFactory.decodeFile(pathName) ?: return null
            return BitmapUtil.tryToGetRotatedBitmap(decoded, pathName)
        }

        val options = BitmapFactory.Options()
        options.inSampleSize = getThingCardCropEditorImageSampleSize(
                bounds.outWidth,
                bounds.outHeight,
                maxSize
        )
        val decoded = BitmapFactory.decodeFile(pathName, options) ?: return null
        return BitmapUtil.tryToGetRotatedBitmap(decoded, pathName)
    }

    private fun getThingCardCropEditorImageSampleSize(
            width: Int,
            height: Int,
            maxSize: Int
    ): Int {
        var sampleSize = 1
        var sampledWidth = width
        var sampledHeight = height
        while (sampledWidth / 2 >= maxSize || sampledHeight / 2 >= maxSize) {
            sampleSize *= 2
            sampledWidth /= 2
            sampledHeight /= 2
        }
        return sampleSize
    }

    private fun updateThingCardActiveTargetAspectRatio(targetAspectRatio: Double) {
        val draft = mThingCardAppearanceDraft ?: return
        val source = getCurrentThingCardAppearanceMediaSource() ?: return
        val presentationKey = getActiveThingCardPresentationKey(draft)
        updateCurrentThingCardSourceAppearance { sourceAppearance ->
            val current = sourceAppearance.presentation(presentationKey)
                    ?: ThingCardAppearance.MediaPresentationAppearance(
                            crop = getThingCardPresentationCrop(sourceAppearance, presentationKey)
                    )
            sourceAppearance.withPresentation(
                    presentationKey,
                    current.copy(
                            targetAspectRatio = clampThingCardTargetAspectRatio(
                                    draft,
                                    source,
                                    presentationKey,
                                    targetAspectRatio
                            )
                    )
            )
        }
    }

    private fun updateThingCardSidePanelTargetWidthPercent(widthPercent: Int) {
        val draft = mThingCardAppearanceDraft ?: return
        val source = getCurrentThingCardAppearanceMediaSource() ?: return
        val presentationKey = getActiveThingCardPresentationKey(draft)
        if (presentationKey != ThingCardAppearance.PRESENTATION_SIDE_PANEL) return

        val targetAspectRatio = getThingCardSidePanelTargetAspectRatioForWidthPercent(
                normalizeThingCardAppearanceSideMediaWidth(widthPercent)
        )
        updateCurrentThingCardSourceAppearance { sourceAppearance ->
            val current = sourceAppearance.presentation(presentationKey)
                    ?: ThingCardAppearance.MediaPresentationAppearance(
                            crop = getThingCardPresentationCrop(sourceAppearance, presentationKey)
                    )
            sourceAppearance.withPresentation(
                    presentationKey,
                    current.copy(
                            targetAspectRatio = clampThingCardTargetAspectRatio(
                                    draft,
                                    source,
                                    presentationKey,
                                    targetAspectRatio
                            )
                    )
            )
        }
    }

    private fun updateThingCardBackgroundMask(maskStrength: Double) {
        val draft = mThingCardAppearanceDraft ?: return
        val source = getCurrentThingCardAppearanceMediaSource() ?: return
        updateCurrentThingCardSourceAppearance { sourceAppearance ->
            val presentationKey = ThingCardAppearance.PRESENTATION_MEDIA_BACKGROUND
            val current = sourceAppearance.presentation(presentationKey)
                    ?: ThingCardAppearance.MediaPresentationAppearance(
                            targetAspectRatio = getThingCardPresentationTargetAspectRatio(
                                    draft,
                                    source,
                                    presentationKey,
                                    sourceAppearance
                            ),
                            crop = getThingCardPresentationCrop(sourceAppearance, presentationKey)
                    )
            sourceAppearance.withPresentation(
                    presentationKey,
                    current.copy(maskStrength = maskStrength)
            )
        }
    }

    private fun updateThingCardBackgroundHeight(heightPercent: Int) {
        val range = getThingCardThumbnailRatioRange()
        updateThingCardActiveTargetAspectRatio(
                RatioSlider.ratioFromProgress(heightPercent, range.minRatio, range.maxRatio)
        )
        if (!applyCurrentThingCardMediaBackgroundHeightToVisiblePreview()) {
            requestThingCardAppearancePreviewRefresh()
        }
    }

    private fun updateThingCardVideoFrame(videoFrameMs: Long) {
        val source = getCurrentThingCardAppearanceMediaSource() ?: return
        if (!source.isVideo) return
        val durationMs = getThingCardAppearanceVideoDurationMs(source.pathName)
        val clampedFrameMs = clampThingCardVideoFrameMs(videoFrameMs, durationMs)

        updateCurrentThingCardSourceAppearance { sourceAppearance ->
            sourceAppearance.copy(videoFrameMs = clampedFrameMs)
        }
    }

    private fun getThingCardVideoFrameMaxMs(durationMs: Int): Int {
        if (durationMs <= 0) return 0
        return max(0, durationMs - THING_CARD_VIDEO_END_FRAME_GUARD_MS)
    }

    private fun clampThingCardVideoFrameMs(valueMs: Long, durationMs: Int): Long {
        val value = when {
            valueMs < 0L -> 0
            valueMs > Int.MAX_VALUE -> Int.MAX_VALUE
            else -> valueMs.toInt()
        }
        return clampThingCardAppearanceSeekProgress(
                value,
                getThingCardVideoFrameMaxMs(durationMs)
        ).toLong()
    }

    private fun getThingCardBackgroundHeightSliderMinPercent(
            maxPercent: Int = resources.getInteger(
                    R.integer.thing_card_media_background_home_max_height_percent
            )
    ): Int {
        val availableHeight = getThingCardAppearancePreviewAvailableHeight()
        if (availableHeight <= 0) return 0
        val naturalHeight = getThingCardBackgroundNaturalHeight()
        if (naturalHeight <= 0) return 0
        val minPercent = ceil(naturalHeight * 100.0 / availableHeight).toInt()
        return max(0, min(maxPercent, minPercent))
    }

    private fun getThingCardBackgroundNaturalHeight(): Int {
        val recyclerView = mRecyclerView ?: return 0
        val listPosition = mThingCardAppearanceSelectedListPosition
        if (listPosition < 0) return 0
        val holder = recyclerView.findViewHolderForAdapterPosition(listPosition)
                as? BaseThingsAdapter.BaseThingViewHolder
                ?: return 0
        val width = (holder.llContent?.layoutParams?.width ?: 0).takeIf { it > 0 }
                ?: getThingCardAppearancePreviewCardWidth()
        if (width <= 0) return 0
        return measureThingCardMediaBackgroundNaturalHeight(holder, width)
    }

    private fun getThingCardBackgroundHeightRatio(heightPercent: Int): Double? {
        val availableHeight = getThingCardAppearancePreviewAvailableHeight()
        val cardWidth = getThingCardAppearancePreviewCardWidth()
        if (availableHeight <= 0 || cardWidth <= 0) return null

        val maxPercent = resources.getInteger(
                R.integer.thing_card_media_background_home_max_height_percent
        )
        val targetPercent = max(1, min(maxPercent, heightPercent))
        return availableHeight * targetPercent / 100.0 / cardWidth
    }

    private fun getThingCardBackgroundHeightPercent(heightRatio: Double?): Int {
        if (heightRatio == null || heightRatio <= 0.0) {
            return 0
        }
        val availableHeight = getThingCardAppearancePreviewAvailableHeight()
        val cardWidth = getThingCardAppearancePreviewCardWidth()
        if (availableHeight <= 0 || cardWidth <= 0) return 0

        return (cardWidth * heightRatio / availableHeight * 100.0).toInt()
    }

    private fun getThingCardAppearancePreviewAvailableHeight(): Int {
        val recyclerView = mRecyclerView ?: return DisplayUtil.getScreenSize(this).y
        val height = recyclerView.height -
                recyclerView.paddingTop -
                mThingCardAppearancePanelOriginalPaddingBottom
        if (height > 0) return height
        return DisplayUtil.getScreenSize(this).y
    }

    private fun getThingCardAppearancePreviewCardWidth(): Int {
        if (mThingCardAppearanceSelectedListPosition >= 0) {
            val holder = mRecyclerView!!.findViewHolderForAdapterPosition(
                    mThingCardAppearanceSelectedListPosition
            )
            val width = holder?.itemView?.width ?: 0
            if (width > 0) return width
        }
        return DisplayUtil.getThingCardWidth(this)
    }

    private fun updateCurrentThingCardSourceAppearance(
            updater: (ThingCardAppearance.SourceAppearance) ->
                    ThingCardAppearance.SourceAppearance
    ) {
        val draft = mThingCardAppearanceDraft ?: return
        val source = getCurrentThingCardAppearanceMediaSource() ?: return
        val current = draft.sources[source.typePathName]
                ?: ThingCardAppearance.SourceAppearance(
                        fileSize = source.fileSize,
                        lastModified = source.lastModified
                )
        val newSources = LinkedHashMap(draft.sources)
        newSources[source.typePathName] = updater(current)
        updateThingCardAppearanceDraft(draft.copy(sources = newSources))
    }

    private fun updateThingCardAppearanceDraft(
            newDraft: ThingCardAppearance?,
            requestPreviewRefresh: Boolean = true,
            bindPanel: Boolean = true
    ) {
        val thing = mThingCardAppearancePanelThing ?: return
        if (newDraft == null) return

        val preparedDraft = seedThingCardPresentationForDraftTransition(
                thing,
                mThingCardAppearanceDraft,
                newDraft
        )
        mThingCardAppearanceDraft = preparedDraft
        thing.thingCardAppearance = preparedDraft
        if (requestPreviewRefresh) {
            requestThingCardAppearancePreviewRefresh()
        }
        if (bindPanel) {
            bindThingCardAppearancePanel()
            requestThingCardAppearancePanelSpaceUpdate()
        }
    }

    private fun seedThingCardPresentationForDraftTransition(
            thing: Thing,
            oldDraft: ThingCardAppearance?,
            newDraft: ThingCardAppearance
    ): ThingCardAppearance {
        val source = ThingCardMediaHelper.resolveEffectiveMediaSource(
                thing.attachment,
                newDraft.mediaSourceKey
        ) ?: return newDraft
        val targetKey = getActiveThingCardPresentationKey(newDraft)
        val current = newDraft.sources[source.typePathName]
                ?: ThingCardAppearance.SourceAppearance(
                        fileSize = source.fileSize,
                        lastModified = source.lastModified
                )
        if (current.presentation(targetKey) != null) return newDraft

        val oldKey = oldDraft?.let { getActiveThingCardPresentationKey(it) }
        val oldSourceAppearance = oldDraft?.sources?.get(source.typePathName)
        val seedPresentation = oldKey?.let { oldSourceAppearance?.presentation(it) }
        val seedCrop = seedPresentation?.crop
                ?: oldKey?.let { getThingCardPresentationCrop(oldSourceAppearance, it) }
                ?: getThingCardPresentationCrop(current, targetKey)
        val legacySideRatio = if (targetKey == ThingCardAppearance.PRESENTATION_SIDE_PANEL) {
            getLegacySidePanelTargetAspectRatio(newDraft)
        } else {
            null
        }
        val seedRatio = legacySideRatio
                ?: seedPresentation?.targetAspectRatio
                ?: oldKey?.let {
                    getThingCardPresentationTargetAspectRatio(
                            oldDraft,
                            source,
                            it,
                            oldSourceAppearance
                    )
                }
                ?: getThingCardPresentationTargetAspectRatio(newDraft, source, targetKey, current)
        val clampedRatio = clampThingCardTargetAspectRatio(
                newDraft,
                source,
                targetKey,
                seedRatio
        )
        val seededPresentation = ThingCardAppearance.MediaPresentationAppearance(
                targetAspectRatio = clampedRatio,
                crop = seedCrop,
                maskStrength = if (targetKey == ThingCardAppearance.PRESENTATION_MEDIA_BACKGROUND) {
                    seedPresentation?.maskStrength
                            ?: current.mediaBackgroundMaskStrength()
                } else {
                    null
                }
        )
        val newSources = LinkedHashMap(newDraft.sources)
        newSources[source.typePathName] = current.withPresentation(targetKey, seededPresentation)
        return newDraft.copy(sources = newSources)
    }

    private fun requestThingCardAppearancePreviewRefresh() {
        val recyclerView = mRecyclerView ?: return
        if (mThingCardAppearancePreviewRefreshPosted) return
        mThingCardAppearancePreviewRefreshPosted = true
        recyclerView.postOnAnimation {
            mThingCardAppearancePreviewRefreshPosted = false
            refreshThingCardAppearancePreviewNow()
        }
    }

    private fun refreshThingCardAppearancePreviewNow() {
        if (!isThingCardAppearancePanelShowing()) return
        val recyclerView = mRecyclerView ?: return
        val listPosition = mThingCardAppearanceSelectedListPosition
        if (listPosition < 0 || listPosition >= mAdapter!!.getItemCount()) return

        if (recyclerView.isComputingLayout) {
            requestThingCardAppearancePreviewRefresh()
            return
        }
        if (recyclerView.hasPendingAdapterUpdates()
                || recyclerView.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
            requestThingCardAppearancePreviewRefresh()
            return
        }
        recyclerView.itemAnimator?.endAnimations()

        finishNewItemShiningBorderAnimationIfNeeded()
        mAdapter!!.setShouldThingsAnimWhenAppearing(false)
        mAdapter!!.notifyItemChanged(listPosition)
        scheduleThingCardAppearanceRatioRangeRefresh()
    }

    /**
     * 卡片几何（normal↔full span、图片位置、是否作为背景、切换媒体源等）变化后，
     * RecyclerView 要在后续帧重新布局 holder，比例滑条的动态范围才会随之变化。这里在
     * 预览重新布局沉淀之后补刷一次范围，避免用户必须先拖一下滑条才更新。
     */
    private fun scheduleThingCardAppearanceRatioRangeRefresh() {
        val recyclerView = mRecyclerView ?: return
        recyclerView.postOnAnimation {
            if (!isThingCardAppearancePanelShowing()) return@postOnAnimation
            if (recyclerView.isComputingLayout
                    || recyclerView.hasPendingAdapterUpdates()
                    || recyclerView.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
                scheduleThingCardAppearanceRatioRangeRefresh()
                return@postOnAnimation
            }
            if (mLlThingCardAppearanceThumbnailRatio?.visibility == View.VISIBLE) {
                mThingCardAppearanceRatioSlider?.refreshRange()
            }
        }
    }

    private fun confirmThingCardAppearancePanel() {
        if (isFolderCardAppearancePanelActive()) {
            confirmFolderCardAppearancePanel()
            return
        }
        val thing = mThingCardAppearancePanelThing ?: return
        val draft = mThingCardAppearanceDraft ?: return

        val confirmedDraft = materializeThingCardPresentationsForConfirm(thing, draft)
        thing.thingCardAppearance = confirmedDraft
        hideThingCardAppearancePanel()
        clearThingCardAppearanceDraft()
        mThingManager!!.updateThingCardAppearance(thing)
        AppWidgetHelper.updateSingleThingAppWidgets(this, thing.id)
        AppWidgetHelper.updateAllThingsListAppWidgets(this)
        SystemNotificationUtil.tryToCreateThingOngoingNotification(mApp)
        if (mModeManager!!.getCurrentMode() == ModeManager.SELECTING) {
            mModeManager!!.backNormalMode(0)
        }
    }

    private fun confirmFolderCardAppearancePanel() {
        val folder = mFolderCardAppearancePanelFolder ?: return
        val draft = mFolderCardAppearanceDraftPresentation ?: return
        val title = mEtFolderCardAppearanceName!!.text.toString().trim()
        if (title.isEmpty()) {
            mNormalSnackbar!!.setMessage(R.string.sb_cannot_be_blank)
            mNormalSnackbar!!.show()
            return
        }
        val confirmedDraft = if (folder.isPrivate) {
            ThingFolderCardPresentation.default()
        } else {
            draft
        }
        folder.title = title
        folder.cardPresentation = confirmedDraft
        hideThingCardAppearancePanel()
        clearThingCardAppearanceDraft()
        if (mThingManager!!.updateFolderAppearance(folder, title, confirmedDraft)) {
            refreshActivitySurfaceAndHeader()
            updateDrawerFolderItems()
            AppWidgetHelper.updateAllThingsListAppWidgets(this)
        }
        if (mModeManager!!.getCurrentMode() == ModeManager.SELECTING) {
            mModeManager!!.backNormalMode(0)
        }
    }

    private fun requestActivityHeaderStateRefresh() {
        val recyclerView = mRecyclerView ?: return
        recyclerView.post {
            mActivityHeader?.updateAll(findFirstVisibleThingListPosition(), false)
        }
    }

    private fun requestActivityHeaderStateRefreshAfterThingListLayout() {
        requestActivityHeaderStateRefreshBeforeDraw(mThingManager?.getProjection()?.key())
    }

    private fun findFirstVisibleThingListPosition(): Int {
        val layoutManager = mStaggeredGridLayoutManager ?: return 0
        val positions = IntArray(mSpan.coerceAtLeast(1))
        layoutManager.findFirstVisibleItemPositions(positions)
        var firstVisible = Int.MAX_VALUE
        for (position in positions) {
            if (position >= 0 && position < firstVisible) {
                firstVisible = position
            }
        }
        return if (firstVisible == Int.MAX_VALUE) 0 else firstVisible
    }

    private fun requestActivityHeaderSpacerHeightUpdate(heightPx: Int) {
        mPendingActivityHeaderSpacerHeightPx = heightPx
        if (mActivityHeaderSpacerApplyPosted) return
        mActivityHeaderSpacerApplyPosted = true
        mRecyclerView?.post {
            applyPendingActivityHeaderSpacerHeight()
        }
    }

    private fun applyPendingActivityHeaderSpacerHeight() {
        val recyclerView = mRecyclerView
        if (recyclerView == null) {
            mActivityHeaderSpacerApplyPosted = false
            return
        }
        if (recyclerView.isComputingLayout ||
            recyclerView.scrollState != RecyclerView.SCROLL_STATE_IDLE
        ) {
            recyclerView.postOnAnimation {
                applyPendingActivityHeaderSpacerHeight()
            }
            return
        }
        val heightPx = mPendingActivityHeaderSpacerHeightPx
        mPendingActivityHeaderSpacerHeightPx = null
        mActivityHeaderSpacerApplyPosted = false
        if (heightPx != null) {
            mAdapter?.setActivityHeaderSpacerHeightPx(heightPx)
        }
    }

    private fun materializeThingCardPresentationsForConfirm(
            thing: Thing,
            draft: ThingCardAppearance
    ): ThingCardAppearance {
        val activeMaterialized = materializeActiveThingCardPresentation(thing, draft)
        return materializeLegacySidePanelPresentationIfNeeded(thing, activeMaterialized)
    }

    private fun materializeActiveThingCardPresentation(
            thing: Thing,
            draft: ThingCardAppearance
    ): ThingCardAppearance {
        return seedThingCardPresentationForDraftTransition(thing, null, draft)
    }

    private fun materializeLegacySidePanelPresentationIfNeeded(
            thing: Thing,
            draft: ThingCardAppearance
    ): ThingCardAppearance {
        if (draft.sideMediaWidthPercent == ThingCardAppearance.DEFAULT_SIDE_MEDIA_WIDTH_PERCENT) {
            return draft
        }
        val source = ThingCardMediaHelper.resolveEffectiveMediaSource(
                thing.attachment,
                draft.mediaSourceKey
        ) ?: return draft
        val current = draft.sources[source.typePathName]
                ?: ThingCardAppearance.SourceAppearance(
                        fileSize = source.fileSize,
                        lastModified = source.lastModified
                )
        if (current.presentation(ThingCardAppearance.PRESENTATION_SIDE_PANEL) != null) {
            return draft
        }

        val legacyRatio = getLegacySidePanelTargetAspectRatio(draft) ?: return draft
        val sidePresentation = ThingCardAppearance.MediaPresentationAppearance(
                targetAspectRatio = clampThingCardTargetAspectRatio(
                        draft,
                        source,
                        ThingCardAppearance.PRESENTATION_SIDE_PANEL,
                        legacyRatio
                ),
                crop = getThingCardPresentationCrop(
                        current,
                        ThingCardAppearance.PRESENTATION_SIDE_PANEL
                )
        )
        val newSources = LinkedHashMap(draft.sources)
        newSources[source.typePathName] = current.withPresentation(
                ThingCardAppearance.PRESENTATION_SIDE_PANEL,
                sidePresentation
        )
        return draft.copy(sources = newSources)
    }

    private fun getCurrentMediaBackgroundDisplayAspectRatio(): Double? {
        val recyclerView = mRecyclerView ?: return null
        val listPosition = mThingCardAppearanceSelectedListPosition
        if (listPosition < 0) return null
        val holder = recyclerView.findViewHolderForAdapterPosition(listPosition)
                as? BaseThingsAdapter.BaseThingViewHolder
                ?: return null
        val background = holder.ivMediaBackground ?: return null
        if (background.visibility != View.VISIBLE) return null
        val width = background.width.takeIf { it > 0 } ?: holder.cv?.width ?: 0
        val height = background.height
        if (width <= 0 || height <= 0) return null
        val ratio = width.toDouble() / height.toDouble()
        if (ratio.isNaN() || ratio.isInfinite() || ratio <= 0.0) return null
        return max(0.05, min(10.0, ratio))
    }

    private fun cancelThingCardAppearancePanel(shouldBackNormalMode: Boolean) {
        if (isFolderCardAppearancePanelActive()) {
            cancelFolderCardAppearancePanel(shouldBackNormalMode)
            return
        }
        val thing = mThingCardAppearancePanelThing
        val original = mThingCardAppearanceOriginal
        val originalBackground = mThingCardAppearanceOriginalBackground
        if (thing != null && original != null) {
            thing.thingCardAppearance = original
            if (originalBackground != null) {
                thing.setBackground(originalBackground)
            }
            if (mThingCardAppearanceSelectedListPosition >= 0) {
                finishNewItemShiningBorderAnimationIfNeeded()
                mAdapter!!.notifyItemChanged(mThingCardAppearanceSelectedListPosition)
            }
        }
        hideThingCardAppearancePanel()
        clearThingCardAppearanceDraft()
        refreshActivitySurfaceAndHeader()

        if (shouldBackNormalMode && mModeManager!!.getCurrentMode() == ModeManager.SELECTING) {
            mModeManager!!.backNormalMode(0)
        }
    }

    private fun cancelFolderCardAppearancePanel(shouldBackNormalMode: Boolean) {
        val folder = mFolderCardAppearancePanelFolder
        val originalTitle = mFolderCardAppearanceOriginalTitle
        val originalPresentation = mFolderCardAppearanceOriginalPresentation
        val originalBackground = mFolderCardAppearanceOriginalBackground
        if (folder != null && originalTitle != null && originalPresentation != null) {
            folder.title = originalTitle
            folder.cardPresentation = originalPresentation
            if (originalBackground != null) {
                folder.setBackground(originalBackground)
            }
            if (mThingCardAppearanceSelectedListPosition >= 0) {
                finishNewItemShiningBorderAnimationIfNeeded()
                mAdapter!!.notifyItemChanged(mThingCardAppearanceSelectedListPosition)
            }
        }
        hideThingCardAppearancePanel()
        clearThingCardAppearanceDraft()

        if (shouldBackNormalMode && mModeManager!!.getCurrentMode() == ModeManager.SELECTING) {
            mModeManager!!.backNormalMode(0)
        }
    }

    private fun hideThingCardAppearanceKeyboard() {
        KeyboardUtil.hideKeyboard(
                window,
                currentFocus ?: mEtFolderCardAppearanceName ?: mThingCardAppearancePanel
        )
    }

    private fun showThingCardAppearancePanelAnimated(panel: View) {
        val token = ++mThingCardAppearancePanelVisibilityToken
        val wasVisible = panel.visibility == View.VISIBLE
        panel.animate().cancel()
        updateThingCardAppearancePanelWidth()
        updateThingCardAppearancePanelMaxHeight()
        if (!wasVisible) {
            panel.translationY = getThingCardAppearancePanelHiddenTranslationY(panel)
            panel.visibility = View.VISIBLE
        }

        if (!panel.isLaidOut || panel.height <= 0) {
            panel.post {
                startThingCardAppearancePanelInAnimation(panel, token, !wasVisible)
            }
        } else {
            startThingCardAppearancePanelInAnimation(panel, token, !wasVisible)
        }
    }

    private fun startThingCardAppearancePanelInAnimation(
        panel: View,
        token: Int,
        fromHidden: Boolean
    ) {
        if (token != mThingCardAppearancePanelVisibilityToken) return
        if (panel.visibility != View.VISIBLE) {
            panel.visibility = View.VISIBLE
        }
        if (fromHidden) {
            panel.translationY = getThingCardAppearancePanelHiddenTranslationY(panel)
        } else if (abs(panel.translationY) <= 0.5f) {
            panel.translationY = 0f
            return
        }
        panel.animate()
                .translationY(0f)
                .setDuration(THING_CARD_APPEARANCE_PANEL_ANIM_DURATION_MS)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction {
                    if (token == mThingCardAppearancePanelVisibilityToken) {
                        panel.translationY = 0f
                    }
                }
                .start()
    }

    private fun hideThingCardAppearancePanelAnimated(
        panel: View,
        onHidden: () -> Unit
    ) {
        val token = ++mThingCardAppearancePanelVisibilityToken
        panel.animate().cancel()
        if (panel.visibility != View.VISIBLE) {
            panel.translationY = 0f
            onHidden()
            return
        }
        if (!panel.isLaidOut || panel.height <= 0) {
            panel.post {
                startThingCardAppearancePanelOutAnimation(panel, token, onHidden)
            }
        } else {
            startThingCardAppearancePanelOutAnimation(panel, token, onHidden)
        }
    }

    private fun startThingCardAppearancePanelOutAnimation(
        panel: View,
        token: Int,
        onHidden: () -> Unit
    ) {
        if (token != mThingCardAppearancePanelVisibilityToken) return
        val hiddenTranslationY = getThingCardAppearancePanelHiddenTranslationY(panel)
        if (hiddenTranslationY <= 0f) {
            completeThingCardAppearancePanelHidden(panel, token, onHidden)
            return
        }
        panel.animate()
                .translationY(hiddenTranslationY)
                .setDuration(THING_CARD_APPEARANCE_PANEL_ANIM_DURATION_MS)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction {
                    completeThingCardAppearancePanelHidden(panel, token, onHidden)
                }
                .start()
    }

    private fun completeThingCardAppearancePanelHidden(
        panel: View,
        token: Int,
        onHidden: () -> Unit
    ) {
        if (token != mThingCardAppearancePanelVisibilityToken) return
        panel.visibility = View.GONE
        panel.translationY = 0f
        onHidden()
    }

    private fun getThingCardAppearancePanelHiddenTranslationY(panel: View): Float {
        val lp = panel.layoutParams as? ViewGroup.MarginLayoutParams
        val measuredHeight = max(panel.height, panel.measuredHeight)
        val parentHeight = (panel.parent as? View)?.height ?: 0
        val hiddenHeight = if (measuredHeight > 0) {
            measuredHeight + (lp?.bottomMargin ?: 0)
        } else if (parentHeight > 0) {
            parentHeight
        } else {
            DisplayUtil.getScreenSize(this).y
        }
        return max(1, hiddenHeight).toFloat()
    }

    private fun hideThingCardAppearancePanel() {
        mThingCardAppearancePanelSpaceUpdateToken++
        mThingCardAppearanceVisibilityScrollToken++
        mThingCardAppearanceVisibilityCheckToken++
        mThingCardAppearanceVisibilityScrolling = false
        mThingCardAppearancePaddingAnimator?.cancel()
        mThingCardAppearancePaddingAnimator = null
        mThingCardAppearanceSourcePicker?.dismiss()
        mThingCardAppearanceSourcePicker = null
        val wasShowing = isThingCardAppearancePanelShowing()
        if (wasShowing) {
            hideThingCardAppearanceKeyboard()
            hideThingCardAppearancePanelAnimated(mThingCardAppearancePanel!!) {
                mRecyclerView!!.setPadding(
                        mRecyclerView!!.paddingLeft,
                        mRecyclerView!!.paddingTop,
                        mRecyclerView!!.paddingRight,
                        mThingCardAppearancePanelOriginalPaddingBottom
                )
                mAdapter!!.setThingCardSurfaceAvailableHeight(0)
                requestActivityHeaderStateRefresh()
            }
        } else {
            mAdapter!!.setThingCardSurfaceAvailableHeight(0)
            mThingCardAppearancePanel?.translationY = 0f
        }
    }

    private fun clearThingCardAppearanceDraft() {
        mThingCardAppearancePanelThing = null
        mThingCardAppearanceOriginal = null
        mThingCardAppearanceDraft = null
        mThingCardAppearanceOriginalBackground = null
        clearFolderCardAppearanceDraft()
        mThingCardAppearanceSelectedListPosition = -1
        mThingCardAppearanceMediaSources = emptyList()
        mThingCardAppearanceSourcePicker?.dismiss()
        mThingCardAppearanceSourcePicker = null
        mThingCardAppearancePreviewRefreshPosted = false
    }

    private fun clearFolderCardAppearanceDraft() {
        mFolderCardAppearancePanelFolder = null
        mFolderCardAppearanceOriginalTitle = null
        mFolderCardAppearanceOriginalPresentation = null
        mFolderCardAppearanceOriginalBackground = null
        mFolderCardAppearanceDraftPresentation = null
        mBindingFolderCardAppearancePanel = false
    }

    private fun updateRecyclerViewBottomPaddingForThingCardAppearancePanel() {
        if (!isThingCardAppearancePanelShowing()) {
            return
        }

        val recyclerView = mRecyclerView ?: return
        if (recyclerView.isComputingLayout) {
            requestThingCardAppearancePanelSpaceUpdate()
            return
        }

        updateThingCardAppearancePanelWidth()
        updateThingCardAppearancePanelMaxHeight()
        val panelHeight = mThingCardAppearancePanel!!.height
        if (panelHeight <= 0) {
            return
        }

        val extra = panelHeight + getThingCardListItemSpacingPx()
        val basePaddingBottom = mThingCardAppearancePanelOriginalPaddingBottom + extra
        val desiredPaddingBottom = getThingCardAppearancePanelListPaddingBottom(
                recyclerView,
                basePaddingBottom
        )
        if (recyclerView.paddingBottom != desiredPaddingBottom) {
            animateRecyclerViewBottomPaddingForThingCardAppearancePanel(
                    recyclerView,
                    desiredPaddingBottom
            )
        } else {
            scheduleThingCardAppearanceSelectedCardVisibleCheck()
        }
    }

    private fun getThingCardAppearancePanelListPaddingBottom(
        recyclerView: RecyclerView,
        basePaddingBottom: Int
    ): Int {
        val holder = recyclerView.findViewHolderForAdapterPosition(
                mThingCardAppearanceSelectedListPosition
        ) ?: return basePaddingBottom
        val targetTop = getThingCardAppearanceSelectedCardTargetTop(recyclerView)
        val scrollDelta = holder.itemView.top - targetTop
        if (scrollDelta <= 1) return basePaddingBottom

        val contentBottom = getThingCardListLaidOutContentBottom(recyclerView)
        if (contentBottom <= 0) return basePaddingBottom
        val requiredPaddingBottom = recyclerView.height + scrollDelta - contentBottom
        return max(basePaddingBottom, requiredPaddingBottom)
    }

    private fun getThingCardListLaidOutContentBottom(recyclerView: RecyclerView): Int {
        var bottom = 0
        for (i in 0 until recyclerView.childCount) {
            bottom = max(bottom, recyclerView.getChildAt(i).bottom)
        }
        return bottom
    }

    private fun requestThingCardAppearancePanelSpaceUpdate() {
        val panel = mThingCardAppearancePanel ?: return
        updateThingCardAppearancePanelMaxHeight()
        val token = ++mThingCardAppearancePanelSpaceUpdateToken
        panel.post {
            updateThingCardAppearancePanelMaxHeight()
            updateThingCardAppearancePanelSpaceIfCurrent(token)
            panel.postOnAnimation {
                updateThingCardAppearancePanelMaxHeight()
                updateThingCardAppearancePanelSpaceIfCurrent(token)
            }
            panel.postDelayed({
                updateThingCardAppearancePanelMaxHeight()
                updateThingCardAppearancePanelSpaceIfCurrent(token)
            }, THING_CARD_APPEARANCE_PANEL_SPACE_SETTLE_DELAY_MS)
            panel.postDelayed({
                updateThingCardAppearancePanelMaxHeight()
                updateThingCardAppearancePanelSpaceIfCurrent(token)
            }, THING_CARD_APPEARANCE_PANEL_SPACE_FINAL_DELAY_MS)
        }
    }

    private fun updateThingCardAppearancePanelSpaceIfCurrent(token: Int) {
        if (token != mThingCardAppearancePanelSpaceUpdateToken) return
        updateRecyclerViewBottomPaddingForThingCardAppearancePanel()
    }

    private fun animateRecyclerViewBottomPaddingForThingCardAppearancePanel(
        recyclerView: RecyclerView,
        desiredPaddingBottom: Int
    ) {
        mThingCardAppearancePaddingAnimator?.cancel()
        val startPaddingBottom = recyclerView.paddingBottom
        if (!recyclerView.isLaidOut || !isThingCardAppearancePanelShowing()) {
            setRecyclerViewBottomPadding(recyclerView, desiredPaddingBottom)
            scheduleThingCardAppearanceSelectedCardVisibleCheck()
            requestActivityHeaderStateRefreshAfterThingListLayout()
            return
        }

        val animator = ValueAnimator.ofInt(startPaddingBottom, desiredPaddingBottom)
        mThingCardAppearancePaddingAnimator = animator
        animator.duration = THING_CARD_APPEARANCE_PADDING_ANIM_DURATION_MS
        animator.interpolator = AccelerateDecelerateInterpolator()
        animator.addUpdateListener {
            setRecyclerViewBottomPadding(recyclerView, it.animatedValue as Int)
            requestActivityHeaderStateRefreshAfterThingListLayout()
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationCancel(animation: Animator) {
                if (mThingCardAppearancePaddingAnimator === animation) {
                    mThingCardAppearancePaddingAnimator = null
                }
            }

            override fun onAnimationEnd(animation: Animator) {
                if (mThingCardAppearancePaddingAnimator === animation) {
                    mThingCardAppearancePaddingAnimator = null
                    setRecyclerViewBottomPadding(recyclerView, desiredPaddingBottom)
                    scheduleThingCardAppearanceSelectedCardVisibleCheck()
                    requestActivityHeaderStateRefreshAfterThingListLayout()
                }
            }
        })
        animator.start()
    }

    private fun setRecyclerViewBottomPadding(
        recyclerView: RecyclerView,
        paddingBottom: Int
    ) {
        recyclerView.setPadding(
                recyclerView.paddingLeft,
                recyclerView.paddingTop,
                recyclerView.paddingRight,
                paddingBottom
        )
    }

    private fun scheduleThingCardAppearanceSelectedCardVisibleCheck(
        delayMs: Long = THING_CARD_APPEARANCE_VISIBILITY_CHECK_DELAY_MS
    ) {
        val recyclerView = mRecyclerView ?: return
        val token = ++mThingCardAppearanceVisibilityCheckToken
        recyclerView.postDelayed({
            if (token != mThingCardAppearanceVisibilityCheckToken ||
                    !isThingCardAppearancePanelShowing()) {
                return@postDelayed
            }
            recyclerView.postOnAnimation {
                if (token != mThingCardAppearanceVisibilityCheckToken ||
                        !isThingCardAppearancePanelShowing()) {
                    return@postOnAnimation
                }
                if (recyclerView.isComputingLayout) {
                    scheduleThingCardAppearanceSelectedCardVisibleCheck(
                            THING_CARD_APPEARANCE_VISIBILITY_CHECK_DELAY_MS
                    )
                    return@postOnAnimation
                }
                ensureThingCardAppearanceSelectedCardVisible()
            }
            ViewCompat.postInvalidateOnAnimation(recyclerView)
        }, delayMs)
    }

    private fun ensureThingCardAppearanceSelectedCardVisible() {
        val recyclerView = mRecyclerView ?: return
        val selectedListPosition = mThingCardAppearanceSelectedListPosition
        if (selectedListPosition <= 0) return
        if (selectedListPosition >= (mAdapter?.getItemCount() ?: 0)) return

        val holder = recyclerView.findViewHolderForAdapterPosition(selectedListPosition)
        if (holder == null) {
            smoothScrollThingCardAppearanceSelectionNearTop(selectedListPosition)
            return
        }

        val view = holder.itemView
        val visibleTop = getThingCardAppearanceSelectedCardTargetTop(recyclerView)
        val visibleBottom = recyclerView.height - recyclerView.paddingBottom
        val availableHeight = visibleBottom - visibleTop
        if (availableHeight <= 0 || view.height <= 0) return

        val scrollDelta = view.top - visibleTop
        if (abs(scrollDelta) <= 1) return
        if ((scrollDelta > 0 && !recyclerView.canScrollVertically(1)) ||
                (scrollDelta < 0 && !recyclerView.canScrollVertically(-1))) {
            return
        }

        if (recyclerView.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
            scheduleThingCardAppearanceSelectedCardVisibleCheck(
                    THING_CARD_APPEARANCE_SCROLL_RECHECK_DELAY_MS
            )
            return
        }
        beginThingCardAppearanceVisibilityScroll()
        recyclerView.smoothScrollBy(0, scrollDelta)
    }

    private fun getThingCardAppearanceSelectedCardTargetTop(recyclerView: RecyclerView): Int {
        return recyclerView.paddingTop + getThingCardListItemSpacingPx()
    }

    private fun smoothScrollThingCardAppearanceSelectionNearTop(position: Int) {
        val layoutManager = mStaggeredGridLayoutManager ?: return
        if (mRecyclerView?.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
            scheduleThingCardAppearanceSelectedCardVisibleCheck(
                    THING_CARD_APPEARANCE_SCROLL_RECHECK_DELAY_MS
            )
            return
        }
        val scroller = object : LinearSmoothScroller(this@ThingsActivity) {
            override fun getVerticalSnapPreference(): Int =
                    LinearSmoothScroller.SNAP_TO_START

            override fun calculateDyToMakeVisible(
                view: View,
                snapPreference: Int
            ): Int {
                val recyclerView = mRecyclerView ?: return 0
                val targetTop = getThingCardAppearanceSelectedCardTargetTop(recyclerView)
                return targetTop - view.top
            }
        }
        scroller.targetPosition = position
        beginThingCardAppearanceVisibilityScroll()
        layoutManager.startSmoothScroll(scroller)
    }

    private fun beginThingCardAppearanceVisibilityScroll() {
        mThingCardAppearanceVisibilityScrolling = true
        val token = ++mThingCardAppearanceVisibilityScrollToken
        requestActivityHeaderStateRefreshAfterThingListLayout()
        mRecyclerView?.postDelayed({
            if (token == mThingCardAppearanceVisibilityScrollToken &&
                mThingCardAppearanceVisibilityScrolling &&
                mRecyclerView?.scrollState == RecyclerView.SCROLL_STATE_IDLE
            ) {
                finishThingCardAppearanceVisibilityScroll()
            }
        }, THING_CARD_APPEARANCE_SCROLL_TIMEOUT_MS)
    }

    private fun finishThingCardAppearanceVisibilityScroll() {
        if (!mThingCardAppearanceVisibilityScrolling) return
        mThingCardAppearanceVisibilityScrolling = false
        scheduleThingCardAppearanceSelectedCardVisibleCheck(0L)
        requestActivityHeaderStateRefreshAfterThingListLayout()
    }

    private fun getThingCardListItemSpacingPx(): Int {
        return resources.getDimensionPixelSize(R.dimen.thing_card_outer_spacing) * 2
    }

    private fun getFolderDropTargetInsetPx(): Float {
        return resources.getDimension(R.dimen.folder_drop_target_inset)
    }

    private fun isThingCardAppearancePanelShowing(): Boolean {
        return mThingCardAppearancePanel != null &&
                mThingCardAppearancePanel!!.visibility == View.VISIBLE
    }

    private fun getSingleSelectedThingForAppearance(): Thing? {
        if (mThingManager!!.getSelectedCount() != 1) {
            return null
        }

        val selectedThings = mThingManager!!.getSelectedThings() ?: return null
        if (selectedThings.isEmpty()) {
            return null
        }
        return selectedThings[0]
    }

    private fun setFabEvents() {
        mFab!!.attachToRecyclerView(mRecyclerView!!)
        mFab!!.bindSnackbars(mNormalSnackbar)
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
                else ThingBackground.pure(App.newThingColor),
                mThingManager!!.getProjection().currentFolderId
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

    private fun beginGatedNewItemReveal(position: Int, bg: ThingBackground?) {
        mNewItemRevealGating = true
        // Treat the pre-scroll window as "still scrolling" so a card that binds
        // before the scroll decision is made does not reveal early.
        mNewItemRevealScrolling = true
        mNewItemRevealPosition = position
        mNewItemRevealBg = bg
        mNewItemRevealHolder = null
    }

    private fun clearGatedNewItemReveal() {
        mNewItemRevealGating = false
        mNewItemRevealScrolling = false
        mNewItemRevealPosition = -1
        mNewItemRevealBg = null
        mNewItemRevealHolder = null
    }

    /**
     * Abort a pending gated new-item reveal (rotation, undo, a new create, etc.).
     * The armed card may already be hidden waiting for the reveal, so restore it to
     * visible before clearing so it can never be left stuck invisible.
     */
    private fun abortGatedNewItemRevealIfNeeded() {
        if (!mNewItemRevealGating) return
        mNewItemRevealHolder?.cv?.let { card ->
            card.animate()?.cancel()
            card.alpha = 1f
            card.visibility = View.VISIBLE
        }
        mAdapter?.clearArmedNewItemAnimation()
        clearGatedNewItemReveal()
    }

    /**
     * Bridges the adapter's armed new-item callback. When the reveal is gated on a
     * scroll-into-view we just capture the (now hidden) holder and try to reveal;
     * otherwise we keep the original immediate reveal.
     */
    private fun onNewItemHolderBound(
        holder: BaseThingsAdapter.BaseThingViewHolder?,
        bg: ThingBackground?
    ) {
        if (mNewItemRevealGating) {
            mNewItemRevealHolder = holder
            maybeRevealGatedNewItem()
        } else {
            playNewItemAnimation(holder!!, bg!!)
        }
    }

    /**
     * After the new card is inserted, scroll it fully into view (snap its top just
     * below the toolbar) and reveal once the scroll settles. If it is already fully
     * visible, or the list cannot scroll any further (short list near the bottom),
     * reveal in place without scrolling.
     */
    private fun scrollNewItemFullyIntoViewThenReveal(position: Int) {
        val rv = mRecyclerView ?: return
        // Decide after layout so visibility/holder lookups are accurate.
        rv.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                rv.viewTreeObserver.removeOnPreDrawListener(this)
                if (!mNewItemRevealGating || mNewItemRevealPosition != position) {
                    return true
                }
                val lm = mStaggeredGridLayoutManager
                if (lm == null ||
                    isThingListPositionFullyVisible(position) ||
                    !rv.canScrollVertically(1)
                ) {
                    revealGatedNewItemInPlace()
                    return true
                }
                val scroller = object : LinearSmoothScroller(this@ThingsActivity) {
                    override fun getVerticalSnapPreference(): Int =
                        LinearSmoothScroller.SNAP_TO_START
                }
                scroller.targetPosition = position
                lm.startSmoothScroll(scroller)
                // Safety net: if the scroll never reports an idle transition (e.g.
                // nothing actually scrolled), unblock and reveal; if the card never
                // bound at all, give up gracefully so nothing stays hidden.
                rv.postDelayed({
                    if (!mNewItemRevealGating) return@postDelayed
                    mNewItemRevealScrolling = false
                    maybeRevealGatedNewItem()
                    if (mNewItemRevealGating) {
                        abortGatedNewItemRevealIfNeeded()
                    }
                }, NEW_ITEM_REVEAL_SCROLL_TIMEOUT_MS)
                return true
            }
        })
    }

    private fun revealGatedNewItemInPlace() {
        // The armed onNewItemBound callback is the single source of the holder, so
        // we only unblock here and let maybeReveal play once that holder arrives.
        mNewItemRevealScrolling = false
        maybeRevealGatedNewItem()
    }

    private fun maybeRevealGatedNewItem() {
        if (!mNewItemRevealGating || mNewItemRevealScrolling) return
        val rv = mRecyclerView ?: return
        if (rv.scrollState != RecyclerView.SCROLL_STATE_IDLE) return
        val holder = mNewItemRevealHolder ?: return
        val bg = mNewItemRevealBg
        clearGatedNewItemReveal()
        playNewItemAnimation(
            holder,
            bg ?: (App.newThingBackground ?: ThingBackground.pure(App.newThingColor))
        )
    }

    private fun isThingListPositionFullyVisible(position: Int): Boolean {
        val rv = mRecyclerView ?: return false
        val holder = rv.findViewHolderForAdapterPosition(position) ?: return false
        val view = holder.itemView
        return view.top >= rv.paddingTop && view.bottom <= rv.height - rv.paddingBottom
    }

    /**
     * Plays a per-item entry animation on the freshly-bound card of a newly created thing.
     */
    private fun playNewItemAnimation(
        holder: BaseThingsAdapter.BaseThingViewHolder, bg: ThingBackground
    ) {
        finishNewItemShiningBorderAnimationIfNeeded()
        val useShining = getSharedPreferences(Def.Meta.PREFERENCES_NAME, MODE_PRIVATE)
            .getBoolean(Def.Meta.KEY_CREATE_ANIMATION_STYLE, false)
        val card = holder.cv!!
        var shiningBorderToken = 0
        if (useShining) {
            shiningBorderToken = ++mNewItemShiningBorderToken
            mIsNewItemShiningBorderActive = true
            mIsNewItemShiningBorderAnimating = false
            mNewItemShiningBorderCard = card
            mRecyclerView?.stopScroll()
            mRecyclerView?.requestDisallowInterceptTouchEvent(true)
        }
        card.clearAnimation()
        card.visibility = View.INVISIBLE
        mIsRevealAnimPlaying = true
        card.postDelayed({
            if (useShining && (
                    !mIsNewItemShiningBorderActive
                        || shiningBorderToken != mNewItemShiningBorderToken
                )
            ) {
                return@postDelayed
            }
            if (card.windowToken == null) {
                if (useShining) {
                    finishNewItemShiningBorderAnimationIfNeeded()
                } else {
                    mIsRevealAnimPlaying = false
                }
                return@postDelayed
            }
            card.clearAnimation()
            card.visibility = View.INVISIBLE
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
        val card = holder.cv!!
        mNewItemShiningBorderCard = card
        mRecyclerView?.stopScroll()
        val cardLoc = IntArray(2)
        card.getLocationInWindow(cardLoc)
        val borderLoc = IntArray(2)
        mShiningBorder!!.getLocationInWindow(borderLoc)
        val left   = cardLoc[0] - borderLoc[0]
        val top    = cardLoc[1] - borderLoc[1]
        val right  = left + card.width
        val bottom = top  + card.height

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
        mIsNewItemShiningBorderAnimating = true

        // Card stays INVISIBLE for the whole trace; only at the end do we reveal it.
        mShiningBorder!!.setOnProgressUpdateListener(null)
        mShiningBorder!!.setOnAnimationEndListener(null)
        val endListener = object : ShiningBorder.OnAnimationEndListener {
            override fun onAnimationEnd(border: ShiningBorder) {
                card.alpha = 0f
                card.visibility = View.VISIBLE
                card.animate().alpha(1f).setDuration(220).start()
                mShiningBorder!!.visibility = View.INVISIBLE
                mShiningBorder!!.resetTrace()
                mShiningBorder!!.setOnAnimationEndListener(null)
                restoreShiningBorderDefaults()
                clearNewItemShiningBorderAnimationState()
            }
        }
        mShiningBorder!!.visibility = View.VISIBLE
        mShiningBorder!!.startAnimation()
        mShiningBorder!!.setOnAnimationEndListener(endListener)
    }

    private fun restoreShiningBorderDefaults() {
        mShiningBorder!!.setStrokeWidth(mShiningBorderDefaultStroke)
        mShiningBorder!!.setCornerRadius(mShiningBorderDefaultCornerRadius)
        mShiningBorder!!.setAnimationDuration(mShiningBorderDefaultDuration)
        mShiningBorder!!.setParticleBaseSize(mShiningBorderDefaultParticleBaseSize)
        mShiningBorder!!.setMaxParticles(mShiningBorderDefaultMaxParticles)
        mShiningBorder!!.assignPathAndFrame()
    }

    private fun finishNewItemShiningBorderAnimationIfNeeded() {
        // Also abort any pending scroll-into-view reveal (covers the non-shining
        // reveal style, which has no shining-border state of its own).
        abortGatedNewItemRevealIfNeeded()
        if (!mIsNewItemShiningBorderActive) {
            return
        }

        mNewItemShiningBorderToken++
        val card = mNewItemShiningBorderCard
        if (mIsNewItemShiningBorderAnimating) {
            mShiningBorder!!.setOnAnimationEndListener(null)
            mShiningBorder!!.setOnProgressUpdateListener(null)
            mShiningBorder!!.stopAnimation()
            mShiningBorder!!.visibility = View.INVISIBLE
            mShiningBorder!!.resetTrace()
            restoreShiningBorderDefaults()
        }
        card?.animate()?.cancel()
        card?.alpha = 1f
        card?.visibility = View.VISIBLE
        clearNewItemShiningBorderAnimationState()
    }

    private fun clearNewItemShiningBorderAnimationState() {
        mIsNewItemShiningBorderActive = false
        mIsNewItemShiningBorderAnimating = false
        mNewItemShiningBorderCard = null
        mRecyclerView?.requestDisallowInterceptTouchEvent(false)
        mIsRevealAnimPlaying = false
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
            if (mIsNewItemShiningBorderActive) {
                if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                    mRecyclerView!!.stopScroll()
                }
                return@setOnTouchListener true
            }
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
                    mActivityHeader!!.updateAll(findFirstVisibleThingListPosition(), false)
                } else if (mNewItemRevealScrolling) {
                    // Keep the Activity Header collapsing in step with the
                    // programmatic scroll-into-view for a freshly created Thing.
                    mActivityHeader!!.updateAll(findFirstVisibleThingListPosition(), false)
                } else if (mThingCardAppearanceVisibilityScrolling) {
                    mActivityHeader!!.updateAll(findFirstVisibleThingListPosition(), false)
                }
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                EdgeEffectUtil.forRecyclerView(recyclerView, edgeColor)
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    Glide.with(mApp!!).resumeRequests()
                    if (mNewItemRevealScrolling) {
                        mNewItemRevealScrolling = false
                        maybeRevealGatedNewItem()
                    }
                    if (mThingCardAppearanceVisibilityScrolling) {
                        finishThingCardAppearanceVisibilityScroll()
                    }
                } else { // dragging or settling
                    Glide.with(mApp!!).pauseRequests()
                }
            }
        })

        mOverlayDragController = ThingListOverlayDragController(this)
        mThingsTouchCallback = ThingsTouchCallback()
        mThingsTouchHelper = ItemTouchHelper(mThingsTouchCallback!!)
        mThingsTouchHelper!!.attachToRecyclerView(mRecyclerView)
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
            mColorPicker!!.pickForUI(0)
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
        updateHomeEmptyState()
    }

    private fun updateHomeEmptyState() {
        val text = getHomeEmptyStateText()
        if (text == null) {
            mHomeEmptyState?.visibility = View.GONE
            return
        }
        mTvHomeEmptyState?.text = text
        mTvHomeEmptyState?.setTextColor(
            ContextCompat.getColor(this, R.color.app_chrome_on_surface_hint)
        )
        setHomeEmptyStateImage()
        mHomeEmptyState?.visibility = View.VISIBLE
    }

    private fun getHomeEmptyStateText(): String? {
        val manager = mThingManager ?: return null
        if (isHomeEmptyStateSuppressedBySearch()) return null
        if (manager.hasVisibleProjectionContent()) {
            mOperationEmptyProjectionKey = null
            return null
        }

        val projection = manager.getProjection()
        if (!projection.isRoot()) {
            return getString(R.string.home_empty_folder)
        }

        if (projection.key() == mOperationEmptyProjectionKey) {
            return getOperationEmptyStateText()
        }

        getWelcomeEmptyStateText()?.let { return it }
        return getOrdinaryEmptyStateText()
    }

    private fun getWelcomeEmptyStateText(): String? {
        val projection = mThingManager!!.getProjection()
        if (projection.status != Def.ThingStatus.UNDERWAY || !projection.isRoot()) return null

        val mask = ThingWidgetInfo.normalizedTypeFilterMask(projection.typeFilterMask)
        if (mask == ThingWidgetInfo.TYPE_FILTER_ALL) {
            if (HomeEmptyStateHistory.hasCreatedAnyUserContent(this)) return null
            val title = getString(R.string.welcome_underway_title).trim()
            val content = getString(R.string.welcome_underway_content)
            return if (title.isEmpty()) content else "$title\n$content"
        }

        val type = singleConcreteThingTypeForMask(mask) ?: return null
        if (HomeEmptyStateHistory.hasCreatedThingType(this, type)) return null
        return when (type) {
            Thing.REMINDER -> getString(R.string.welcome_reminder_content)
            Thing.HABIT -> getString(R.string.welcome_habit_content)
            Thing.GOAL -> getString(R.string.welcome_goal_content)
            else -> getString(R.string.welcome_note_content)
        }
    }

    private fun getOperationEmptyStateText(): String {
        val projection = mThingManager!!.getProjection()
        return when (projection.status) {
            Def.ThingStatus.FINISHED -> getString(R.string.empty_finished)
            Def.ThingStatus.DELETED -> getString(R.string.empty_deleted)
            else -> when (singleConcreteThingTypeForMask(projection.typeFilterMask)) {
                Thing.NOTE -> getString(R.string.empty_note)
                Thing.REMINDER -> getString(R.string.empty_reminder)
                Thing.HABIT -> getString(R.string.empty_habit)
                Thing.GOAL -> getString(R.string.empty_goal)
                else -> getString(R.string.empty_underway)
            }
        }
    }

    private fun getOrdinaryEmptyStateText(): String {
        val projection = mThingManager!!.getProjection()
        return when (projection.status) {
            Def.ThingStatus.FINISHED -> getString(R.string.home_empty_finished)
            Def.ThingStatus.DELETED -> getString(R.string.home_empty_deleted)
            else -> when (singleConcreteThingTypeForMask(projection.typeFilterMask)) {
                Thing.NOTE -> getString(R.string.home_empty_note)
                Thing.REMINDER -> getString(R.string.home_empty_reminder)
                Thing.HABIT -> getString(R.string.home_empty_habit)
                Thing.GOAL -> getString(R.string.home_empty_goal)
                else -> getString(R.string.home_empty_underway)
            }
        }
    }

    private fun singleConcreteThingTypeForMask(typeFilterMask: Int): Int? {
        return when (ThingWidgetInfo.normalizedTypeFilterMask(typeFilterMask)) {
            ThingWidgetInfo.TYPE_FILTER_NOTE -> Thing.NOTE
            ThingWidgetInfo.TYPE_FILTER_REMINDER -> Thing.REMINDER
            ThingWidgetInfo.TYPE_FILTER_HABIT -> Thing.HABIT
            ThingWidgetInfo.TYPE_FILTER_GOAL -> Thing.GOAL
            else -> null
        }
    }

    private fun isHomeEmptyStateSuppressedBySearch(): Boolean {
        if (App.isSearching) return true
        val color = mColorPicker?.getPickedColor() ?: 0
        return color != 0 && color != -1979711488
    }

    private fun setHomeEmptyStateImage() {
        val raw: Drawable? = AppCompatResources.getDrawable(this, R.drawable.img_no_result)
        val image: Drawable? = if (AppearanceUtil.isDarkMode(this)) {
            DisplayUtil.opaqueTintDrawable(
                this,
                raw,
                ContextCompat.getColor(this, R.color.app_chrome_on_surface_hint)
            )
        } else {
            raw
        }
        mIvHomeEmptyState?.setImageDrawable(image)
    }

    private fun clearOperationEmptyState() {
        mOperationEmptyProjectionKey = null
    }

    private fun markOperationEmptyStateIfCurrentProjectionEmpty() {
        if (!isHomeEmptyStateSuppressedBySearch() &&
            mThingManager?.isProjectionEmptyForHomeState() == true
        ) {
            mOperationEmptyProjectionKey = mThingManager!!.getProjection().key()
        } else if (mThingManager?.hasVisibleProjectionContent() == true) {
            clearOperationEmptyState()
        }
        updateHomeEmptyState()
    }

    private fun searchThings() {
        finishNewItemShiningBorderAnimationIfNeeded()
        mHomeEmptyState?.visibility = View.GONE
        mThingManager!!.searchThings(mEtSearch!!.text.toString(), mColorPicker!!.getPickedColor())
        mAdapter!!.setShouldThingsAnimWhenAppearing(false)
        mAdapter!!.notifyDataSetChanged()
        handleSearchResults()
    }

    private fun loadThingsForCurrentSearchState() {
        if (App.isSearching) {
            mHomeEmptyState?.visibility = View.GONE
            mThingManager!!.searchThings(mEtSearch!!.text.toString(), mColorPicker!!.getPickedColor())
        } else {
            mThingManager!!.loadThings()
        }
    }

    private fun updateEmptyStateForCurrentSearchState() {
        if (App.isSearching) {
            handleSearchResults()
        } else {
            updateHomeEmptyState()
        }
    }

    private fun currentSearchKeywordForScope(): String? {
        return if (App.isSearching) mEtSearch?.text?.toString() else null
    }

    private fun currentSearchColorForScope(): Int {
        return if (App.isSearching) mColorPicker?.getPickedColor() ?: 0 else 0
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

            override fun onDrawerClosed(drawerView: View) {
                super.onDrawerClosed(drawerView)
                resetDrawerPrivateExpansionAuthentication()
            }
        }
        mDrawerLayout!!.addDrawerListener(toggle)
        toggle.syncState()
        applyHomeNavigationIconTintForAppearance()

        mActionbar!!.setNavigationOnClickListener(OnNavigationIconClickedListener())
        mDrawer!!.setOnFolderExpandClickListener { folderId ->
            toggleDrawerFolderExpanded(folderId)
        }
        mDrawer!!.setOnTypeFilterChangeListener { mask ->
            clearOperationEmptyState()
            mThingManager!!.setTypeFilterMask(mask, false)
            mRecyclerView!!.visibility = View.INVISIBLE
            mThingManager!!.loadThings()
            mAdapter!!.notifyDataSetChanged()
            updateHomeEmptyState()
            mRecyclerView!!.scrollToPosition(0)
            mRecyclerView!!.visibility = View.VISIBLE
            updateDrawerFolderItems()
            refreshActivitySurfaceAndHeader()
            mDrawerHeader!!.updateTexts()
        }
        mDrawer!!.setOnStatusFilterChangeListener { status ->
            // The status segmented capsule is an in-Drawer filter: tapping a
            // segment keeps the Drawer open so its expand/collapse animation is
            // visible, switches status while preserving the current Scope and type
            // filter, and refreshes the list behind the Drawer.
            if (mApp!!.getStatus() != status) {
                clearOperationEmptyState()
                mApp!!.setStatus(status, false)
                invalidateOptionsMenu()
                mRecyclerView!!.visibility = View.INVISIBLE
                mThingManager!!.loadThings()
                mAdapter!!.notifyDataSetChanged()
                updateHomeEmptyState()
                mRecyclerView!!.scrollToPosition(0)
                mRecyclerView!!.visibility = View.VISIBLE
                refreshActivitySurfaceAndHeader()
                mDrawerHeader!!.updateTexts()
                updateDrawerFolderItems()
                if (status == Def.ThingStatus.UNDERWAY) mFab!!.spread() else mFab!!.shrink()
            }
        }
        mDrawer!!.setOnDrawerItemClickListener { drawerItem ->
            when (val key = drawerItem.key) {
                is DrawerNavigationView.ItemKey.Folder -> {
                    if (mCurrentDrawerSelectionKey == key) return@setOnDrawerItemClickListener
                    mThingManager!!.getFolderById(key.folderId)?.let {
                        openDrawerThingFolder(it)
                    }
                }
                is DrawerNavigationView.ItemKey.Destination -> {
                    handleDrawerDestinationClick(key)
                }
                is DrawerNavigationView.ItemKey.FilterPanel -> {
                    // handled by the ThingFilterPanel inside FilterPanelHolder
                }
            }
        }
    }

    private fun handleDrawerDestinationClick(
        key: DrawerNavigationView.ItemKey.Destination
    ) {
        if (mCurrentDrawerSelectionKey == key) return

        when (key.itemId) {
            R.id.drawer_all_things -> {
                // "全部记事" is the root Thing Scope: reset to root while keeping
                // the current status and type filter.
                navigateToRootScopeFromDrawer()
                return
            }
            R.id.drawer_settings -> {
                val intent = Intent(this@ThingsActivity, SettingsActivity::class.java)
                startActivityForResult(intent, Def.Communication.REQUEST_ACTIVITY_SETTINGS)
                mShouldCloseDrawer = true
                return
            }
            R.id.drawer_help -> {
                startActivity(Intent(this@ThingsActivity, HelpActivity::class.java))
                mShouldCloseDrawer = true
                return
            }
            R.id.drawer_about -> {
                startActivity(Intent(this@ThingsActivity, AboutActivity::class.java))
                mShouldCloseDrawer = true
                return
            }
        }
        // Status (including 回收站) is no longer a Drawer destination row; it is
        // handled by the status segmented capsule via the status-change listener.
    }

    private fun navigateToRootScopeFromDrawer() {
        mDrawerLayout!!.closeDrawer(GravityCompat.START)
        saveCurrentProjectionScrollState()
        mThingManager!!.navigateToFolderPathIndex(-1)
        checkDrawerItem(findDrawerSelectionKeyForCurrentProjection())
        refreshHomeAfterDrawerFolderNavigation()
    }

    private fun applyHomeNavigationIconTintForAppearance() {
        val icon = mActionbar!!.navigationIcon ?: return
        val tintBackground = getHomeActionbarIconTintBackground()
        if (icon is DrawerArrowDrawable) {
            icon.color = tintBackground.representativeColor()
        } else {
            mActionbar!!.navigationIcon = tintToolbarDrawable(icon, tintBackground)
        }
    }

    private fun updateHomeNavigationContentDescription() {
        val descriptionRes = when {
            App.isSearching && mThingManager?.getProjection()?.isRoot() == false ->
                R.string.cd_back_parent_folder
            App.isSearching -> R.string.cd_quit_searching
            else -> R.string.cd_open_drawer
        }
        mActionbar?.setNavigationContentDescription(descriptionRes)
    }

    private fun changeToStatus(newStatus: Int, updateDrawerItem: Boolean) {
        finishNewItemShiningBorderAnimationIfNeeded()
        clearOperationEmptyState()
        if (updateDrawerItem) {
            checkDrawerItem(findDrawerSelectionKeyForCurrentProjection())
        }

        mRecyclerView!!.visibility = View.INVISIBLE
        mApp!!.setStatus(newStatus, false)
        invalidateOptionsMenu()
        mRecyclerView!!.scrollToPosition(0)
        mActivityHeader!!.reset(true)

        mRecyclerView!!.postDelayed({
            finishNewItemShiningBorderAnimationIfNeeded()
            mRecyclerView!!.visibility = View.VISIBLE
            mAdapter!!.setShouldThingsAnimWhenAppearing(true)
            mThingManager!!.loadThings()
            mAdapter!!.notifyDataSetChanged()
            updateHomeEmptyState()
            refreshActivitySurfaceAndHeader()
            updateDrawerFolderItems()
        }, 360)

        refreshActivitySurfaceAndHeader()
        updateHomeEmptyState()
        mDrawerHeader!!.updateTexts()
        if (newStatus == Def.ThingStatus.UNDERWAY) {
            mFab!!.spread()
        } else {
            mFab!!.shrink()
        }
    }

    private fun checkDrawerItem(key: DrawerNavigationView.ItemKey?) {
        mCurrentDrawerSelectionKey = key
        mDrawer?.setSelectedKey(key)
    }

    /**
     * Selecting-mode batch state change entry. Things-only selections keep the
     * Part A path (handleUpdateStates, which also raises the habit/goal dialog).
     * Folder-containing selections (multi-folder or mixed) run one aggregated
     * confirmation over the union of selected Things plus each selected Folder's
     * in-scope content, then execute via the folder content-op batch methods.
     */
    /**
     * 从一组"待完成 / 删除"的记事里剔除 Doing 记事（受保护，不被这两类动作作用）。
     * 返回（可操作集合, 原集合是否含 Doing）。Doing 记事天然处于正在进行，故只会在
     * 正在进行状态的完成 / 删除路径里出现。
     */
    private fun excludeDoingFrom(things: List<Thing>): Pair<List<Thing>, Boolean> {
        val doingId = App.getDoingThingId()
        if (doingId == -1L) return things to false
        val actionable = things.filterNot { it.id == doingId }
        return actionable to (actionable.size != things.size)
    }

    private fun confirmSelectedStateChange(stateAfter: Int) {
        val selectedThings = mThingManager!!.getSelectedThings()?.filterNotNull() ?: emptyList()
        val selectedFolders = mThingManager!!.getSelectedFolders().toList()
        if (selectedThings.isEmpty() && selectedFolders.isEmpty()) return
        when {
            selectedFolders.isEmpty() ->
                confirmThingsOnlyStateChange(stateAfter, selectedThings)
            else ->
                confirmMixedStateChange(stateAfter, selectedThings, selectedFolders)
        }
    }

    /**
     * Recycle bin structural permanent delete: destroys each selected Folder's whole
     * subtree (container + descendants of every state/type) and permanently deletes
     * each selected trashed Thing. Distinct from the content-only "永久删除…中的记事",
     * which leaves the folder containers in place.
     */
    private fun confirmDeleteSelectedStructural() {
        val selectedFolders = mThingManager!!.getSelectedFolders().toList()
        if (selectedFolders.isEmpty()) return
        val selectedThings = mThingManager!!.getSelectedThings()?.filterNotNull() ?: emptyList()
        if (selectedFolders.size == 1 && selectedThings.isEmpty()) {
            showDeleteThingFolderForeverDialog(selectedFolders.first())
            return
        }
        var subfolderCount = 0
        var thingCount = selectedThings.size
        val affected = ArrayList<Thing>(selectedThings)
        for (folder in selectedFolders) {
            subfolderCount += mThingManager!!.countDescendantFolders(folder)
            thingCount += mThingManager!!.countAllDescendantThings(folder)
            affected.addAll(mThingManager!!.getAllDescendantThings(folder))
        }
        val impact = folderImpactPhrase(subfolderCount, thingCount)
        val target = if (selectedThings.isEmpty()) {
            HomeActionWordingHelper.StructuralTarget.SELECTED_FOLDERS
        } else {
            HomeActionWordingHelper.StructuralTarget.SELECTED_ITEMS
        }
        val wording = HomeActionWordingHelper.structuralActionWording(
            this,
            HomeActionWordingHelper.StructuralAction.DELETE_FOLDER_FOREVER,
            target,
            impact,
            hiddenScopeClauseForStructural(affected, considerStatus = true),
            impactIsEmpty = subfolderCount == 0 && thingCount == 0
        )
        val background = selectionDialogBackground()
        val adf = AlertDialogFragment()
        adf.setTitleBackground(background)
        adf.setConfirmBackground(background)
        adf.setTitle(wording.dialogTitle)
        adf.setContent(wording.dialogBody)
        adf.setConfirmText(wording.confirmText)
        adf.setConfirmListener(object : AlertDialogFragment.ConfirmListener {
            override fun onConfirm() {
                for (folder in selectedFolders) {
                    mThingManager!!.deleteFolderForever(folder)
                }
                if (selectedThings.isNotEmpty()) {
                    mThingManager!!.deleteThingsForever(selectedThings, reload = false)
                }
                exitSelectingModeIfNeeded()
                refreshHomeAfterFolderUpdated()
                AppWidgetHelper.updateAllThingsListAppWidgets(this@ThingsActivity)
            }
        })
        adf.show(fragmentManager, AlertDialogFragment.TAG)
    }

    private fun confirmThingsOnlyStateChange(stateAfter: Int, selectedThings: List<Thing>) {
        if (selectedThings.isEmpty()) return
        val (actionable, excludesDoing) = excludeDoingFrom(selectedThings)
        if (actionable.isEmpty()) {
            Toast.makeText(this, R.string.no_matching_things_in_selection, Toast.LENGTH_SHORT).show()
            return
        }
        val background = selectionDialogBackground()
        val wording = HomeActionWordingHelper.stateActionWording(
            this,
            mApp!!.getStatus(),
            stateAfter,
            HomeActionWordingHelper.StateTarget.SELECTED_THINGS,
            actionable.size,
            includesSubfolders = false,
            excludesDoing = excludesDoing,
            searchScoped = App.isSearching
        )
        val adf = AlertDialogFragment()
        adf.setTitleBackground(background)
        adf.setConfirmBackground(background)
        adf.setTitle(wording.dialogTitle)
        adf.setContent(wording.dialogBody)
        adf.setConfirmText(wording.confirmText)
        adf.setConfirmListener(object : AlertDialogFragment.ConfirmListener {
            override fun onConfirm() {
                if (mApp!!.getStatus() == Def.ThingStatus.DELETED &&
                    stateAfter == Thing.UNDERWAY
                ) {
                    mThingManager!!.restoreTrashedThings(selectedThings, reload = false)
                    refreshHomeAfterScopeStateChange()
                } else {
                    handleUpdateStates(stateAfter)
                }
            }
        })
        adf.show(fragmentManager, AlertDialogFragment.TAG)
    }

    private fun confirmMixedStateChange(
        stateAfter: Int,
        selectedThings: List<Thing>,
        selectedFolders: List<ThingFolder>
    ) {
        val status = mApp!!.getStatus()
        val searchKeyword = currentSearchKeywordForScope()
        val searchColor = currentSearchColorForScope()
        val folderContent = ArrayList<Thing>()
        for (folder in selectedFolders) {
            folderContent.addAll(
                collectFolderScopeThings(folder, status, stateAfter, searchKeyword, searchColor)
            )
        }
        val rawUnion = ArrayList<Thing>(selectedThings)
        rawUnion.addAll(folderContent)
        val (union, excludesDoing) = excludeDoingFrom(rawUnion)
        if (union.isEmpty()) {
            Toast.makeText(this, R.string.no_matching_things_in_selection, Toast.LENGTH_SHORT).show()
            return
        }
        val background = selectionDialogBackground()
        val foldersOnly = selectedThings.isEmpty()
        // Subfolder reminder: shown when the affected folder content reaches into a
        // nested subfolder (a record whose folderId is not one of the selected folders).
        val selectedFolderIds = selectedFolders.map { it.id }.toHashSet()
        val includesSubfolders = union.any { it.folderId !in selectedFolderIds }
        val wording = HomeActionWordingHelper.stateActionWording(
            this,
            status,
            stateAfter,
            if (foldersOnly) {
                HomeActionWordingHelper.StateTarget.SELECTED_FOLDERS
            } else {
                HomeActionWordingHelper.StateTarget.SELECTED_ITEMS
            },
            union.size,
            includesSubfolders,
            mThingManager!!.getActiveTypeFilterMask(),
            excludesDoing,
            searchScoped = App.isSearching
        )
        val adf = AlertDialogFragment()
        adf.setTitleBackground(background)
        adf.setConfirmBackground(background)
        adf.setTitle(wording.dialogTitle)
        adf.setContent(wording.dialogBody)
        adf.setConfirmText(wording.confirmText)
        adf.setConfirmListener(object : AlertDialogFragment.ConfirmListener {
            override fun onConfirm() {
                if (stateAfter == Thing.FINISHED && union.any { Thing.isImportantType(it.type) }) {
                    showFinishScopeHabitGoalDialog(union, background)
                } else {
                    applyUnionStateChange(union, status, stateAfter)
                }
            }
        })
        adf.show(fragmentManager, AlertDialogFragment.TAG)
    }

    /** In-scope Things contributed by one selected Folder for the given verb. */
    private fun collectFolderScopeThings(
        folder: ThingFolder,
        status: Int,
        stateAfter: Int,
        keyword: String?,
        color: Int
    ): List<Thing> {
        return when {
            stateAfter == Thing.FINISHED ->
                mThingManager!!.getUnderwayThingsInScope(folder, keyword, color)
            status == Def.ThingStatus.UNDERWAY && stateAfter == Thing.DELETED ->
                mThingManager!!.getUnderwayThingsInScope(folder, keyword, color)
            status == Def.ThingStatus.FINISHED && stateAfter == Thing.DELETED ->
                mThingManager!!.getFinishedThingsInScope(folder, keyword, color)
            status == Def.ThingStatus.FINISHED && stateAfter == Thing.UNDERWAY ->
                mThingManager!!.getFinishedThingsInScope(folder, keyword, color)
            status == Def.ThingStatus.DELETED && stateAfter == Thing.UNDERWAY ->
                mThingManager!!.getTrashedThingsInScope(folder, keyword, color)
            status == Def.ThingStatus.DELETED && stateAfter == Thing.DELETED_FOREVER ->
                mThingManager!!.getTrashedThingsInScope(folder, keyword, color)
            else -> emptyList()
        }
    }

    private fun applyUnionStateChange(union: List<Thing>, status: Int, stateAfter: Int) {
        finishNewItemShiningBorderAnimationIfNeeded()
        when {
            stateAfter == Thing.FINISHED ->
                mThingManager!!.finishThings(union, reload = false)
            status == Def.ThingStatus.UNDERWAY && stateAfter == Thing.DELETED ->
                mThingManager!!.trashThingsPreservingState(union, reload = false)
            status == Def.ThingStatus.FINISHED && stateAfter == Thing.DELETED ->
                mThingManager!!.trashThings(union, reload = false)
            status == Def.ThingStatus.FINISHED && stateAfter == Thing.UNDERWAY ->
                mThingManager!!.unfinishThings(union, reload = false)
            status == Def.ThingStatus.DELETED && stateAfter == Thing.UNDERWAY ->
                mThingManager!!.restoreTrashedThings(union, reload = false)
            stateAfter == Thing.DELETED_FOREVER ->
                mThingManager!!.deleteThingsForever(union, reload = false)
        }
        refreshHomeAfterScopeStateChange()
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
                // Doing 记事受保护：选中也不被完成 / 删除，且不计入习惯 / 目标三选项判断。
                if (thing.isSelected() && thing.id != App.getDoingThingId()) {
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
        // Adapt to the current scope: the folder's pure colour / gradient inside a
        // folder, or the accent gradient at root, instead of a random colour.
        val background = mThingManager!!.getCurrentFolder()?.getBackground()
            ?: App.defaultAccentBackground
        df.setTitleBackground(background)
        df.setContinueBackground(background)
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
        finishNewItemShiningBorderAnimationIfNeeded()
        // Restoring from 回收站 returns Things to their pre-trash state. When the
        // whole restored selection shared the finished state, restore them to
        // finished; otherwise restore to underway. A single-Thing restore is
        // always uniform, so it lands in its exact pre-trash state.
        var effectiveStateAfter = stateAfter
        if (stateBefore == Thing.DELETED && stateAfter == Thing.UNDERWAY) {
            val allFinished = mUndoThings!!.all {
                it != null && mThingManager!!.getStateBeforeDelete(it.id) == Thing.FINISHED
            }
            if (allFinished) effectiveStateAfter = Thing.FINISHED
        }
        mStateToUndoFrom = effectiveStateAfter
        @Suppress("UNCHECKED_CAST")
        mUndoPositions = mThingManager!!.updateStates(
            mUndoThings as List<Thing?>, stateBefore, effectiveStateAfter
        )!! as MutableList<Int>
        mAdapter!!.notifyDataSetChanged()
        mUndoAll = true
        if (!mUndoThings!!.isEmpty()) {
            updateUIAfterStateUpdated(
                effectiveStateAfter,
                mRecyclerView!!.itemAnimator!!.removeDuration, true
            )
        }
        if (App.isSearching) {
            handleSearchResults()
        }
    }

    private fun updateUIAfterStateUpdated(stateAfter: Int, timeDelay: Long, shouldForceBackNormalMode: Boolean) {
        refreshActivitySurfaceAndHeader()
        mDrawerHeader!!.updateCompletionRate()
        markOperationEmptyStateIfCurrentProjectionEmpty()

        mScrollCausedByFinger = false
        mRecyclerView!!.postDelayed({
            mActivityHeader!!.updateAll(findFirstVisibleThingListPosition(), true)
        }, timeDelay)

        if (mModeManager!!.getCurrentMode() == ModeManager.SELECTING) {
            updateSelectingUi(shouldForceBackNormalMode)
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

    private fun dismissSnackbars() {
        if (mNormalSnackbar!!.isShowing()) {
            mNormalSnackbar!!.dismiss()
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
        finishNewItemShiningBorderAnimationIfNeeded()
        dismissSnackbars()
        val toNormal = App.isSearching
        if (toNormal) {
            clearOperationEmptyState()
            mActionbar!!.setNavigationContentDescription(R.string.cd_open_drawer)
            mDrawerLayout!!.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
            mViewInsideActionbar!!.visibility = View.VISIBLE

            mEtSearch!!.isEnabled = false
            mEtSearch!!.animate().alpha(0f).setDuration(160)

            mRecyclerView!!.overScrollMode = View.OVER_SCROLL_ALWAYS
            mRecyclerView!!.scrollToPosition(0)

            mActivityHeader!!.reset(false)
            mRecyclerView!!.postDelayed({
                mActivityHeader!!.setShouldListenToScroll(true)
            }, 160)
            if (mApp!!.getStatus() == Def.ThingStatus.UNDERWAY) {
                mFab!!.spread()
            }
            mApp!!.setStatus(mApp!!.getStatus(), true)
            refreshActivitySurfaceAndHeader()
            mDrawerHeader!!.updateCompletionRate()
            mAdapter!!.setShouldThingsAnimWhenAppearing(shouldThingsAnimWhenAppearing)
            hideSearchNoResult()
            DisplayUtil.playDrawerToggleAnim(mActionbar!!.navigationIcon as DrawerArrowDrawable)
        } else {
            mActionbar!!.setNavigationContentDescription(R.string.cd_quit_searching)
            mDrawerLayout!!.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
            mViewInsideActionbar!!.visibility = View.GONE
            mColorPicker!!.pickForUI(0)
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

            mThingManager!!.searchThings(
                mEtSearch!!.text.toString(),
                mColorPicker!!.getPickedColor()
            )
            mAdapter!!.setShouldThingsAnimWhenAppearing(false)
            mHomeEmptyState?.visibility = View.GONE

            DisplayUtil.playDrawerToggleAnim(mActionbar!!.navigationIcon as DrawerArrowDrawable)
        }
        mAdapter!!.notifyDataSetChanged()
        App.isSearching = !toNormal
        updateHomeNavigationContentDescription()
        if (App.isSearching) {
            mHomeEmptyState?.visibility = View.GONE
            handleSearchResults()
        } else {
            updateHomeEmptyState()
        }
        applyHomeNavigationIconTintForAppearance()
        invalidateOptionsMenu()
    }

    private fun handleSearchResults() {
        if (!App.isSearching) {
            hideSearchNoResult()
            updateHomeEmptyState()
            return
        }
        mHomeEmptyState?.visibility = View.GONE
        if (isSearchResultEmpty()) {
            mTvNoResult!!.visibility = View.VISIBLE
            mRevealLayout!!.visibility = View.VISIBLE
            mTvNoResult!!.animate().alpha(1f).setDuration(360)
        } else {
            mTvNoResult!!.animate().alpha(0f).setDuration(160)
            mRevealLayout!!.postDelayed({
                if (App.isSearching && isSearchResultEmpty()) {
                    return@postDelayed
                }
                mRevealLayout!!.visibility = View.INVISIBLE
                mTvNoResult!!.visibility = View.INVISIBLE
            }, 160)
        }
    }

    private fun isSearchResultEmpty(): Boolean {
        return mThingManager?.hasVisibleProjectionContent() != true
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
                if (openParentFolderForCurrentSearchState()) {
                    return
                }
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
            if (mIsNewItemShiningBorderActive) {
                if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                    mRecyclerView!!.stopScroll()
                }
                return true
            }
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

        override fun onItemClick(v: View?, listPosition: Int) {
            if (listPosition <= 0 || mIsRevealAnimPlaying) {
                return
            }
            dismissSnackbars()
            KeyboardUtil.hideKeyboard(currentFocus)

            val entry = mThingManager!!.getThingListEntry(listPosition)
            if (entry is ThingListEntry.FolderEntry) {
                if (mModeManager!!.getCurrentMode() == ModeManager.NORMAL) {
                    openThingFolder(entry)
                } else if (mModeManager!!.getCurrentMode() == ModeManager.SELECTING) {
                    if (isThingCardAppearancePanelShowing()) {
                        cancelThingCardAppearancePanel(false)
                    }
                    entry.folder.selected = !entry.folder.isSelected()
                    mAdapter!!.notifyItemChanged(listPosition)
                    mModeManager!!.updateSelectedCount()
                    mModeManager!!.updateMenuItems()
                }
                return
            }

            val thing: Thing = mThingManager!!.getThingAtListPosition(listPosition) ?: return

            if (mModeManager!!.getCurrentMode() != ModeManager.SELECTING) {
                if (mRecyclerView!!.itemAnimator!!.isRunning) {
                    return
                }
                val thingIndex = mThingManager!!.getThingIndexForListPosition(listPosition)
                if (thingIndex < 0) return

                if (isThingEffectivelyPrivateInCurrentProjection(thing)) {
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
                                openDetailActivityForUpdate(thing, thingIndex, listPosition, v!!)
                            }

                            override fun onCancel() {
                            }
                        })
                } else {
                    openDetailActivityForUpdate(thing, thingIndex, listPosition, v!!)
                }
            } else {
                // 选择模型 A：Doing 记事完全可选中；完成/删除/设为私密的保护在动作执行层处理。
                if (isThingCardAppearancePanelShowing()) {
                    cancelThingCardAppearancePanel(false)
                }
                thing.selected = !thing.isSelected()
                mAdapter!!.notifyItemChanged(listPosition)
                mModeManager!!.updateSelectedCount()
                mModeManager!!.updateMenuItems()
            }
        }

        private fun openDetailActivityForUpdate(
            thing: Thing,
            thingIndex: Int,
            listPosition: Int,
            v: View
        ) {
            val listProjectionKey = mThingManager!!.getProjection().key()
            val intent: Intent = DetailActivity.getOpenIntentForUpdate(
                this@ThingsActivity, TAG, thing.id, thingIndex, listPosition, listProjectionKey
            )
            val transition: ActivityOptionsCompat = ActivityOptionsCompat.makeScaleUpAnimation(
                v, 0, 0, v.width, v.height
            )
            ActivityCompat.startActivityForResult(
                this@ThingsActivity, intent, Def.Communication.REQUEST_ACTIVITY_DETAIL,
                transition.toBundle()
            )
        }

        override fun onFolderThumbnailClick(v: View?, thing: Thing) {
            // 仅普通模式会到这里：选择 / 移动等模式下，预览卡在 ThingsAdapter 里被设为不可点击，
            // 触摸冒泡到外层文件夹卡处理（选择 / 反选 + 整卡 ripple），与点击空白区域一致。
            if (v == null || mModeManager!!.getCurrentMode() != ModeManager.NORMAL) {
                return
            }
            dismissSnackbars()
            KeyboardUtil.hideKeyboard(currentFocus)

            val thingIndex = mThingManager!!.getPosition(thing.id)
            val listPosition = mThingManager!!.getListPositionForThingId(thing.id)
            if (thing.isPrivate() && !mThingManager!!.isCurrentFolderPrivacyAuthenticated()) {
                val sp: SharedPreferences = getSharedPreferences(
                    Def.Meta.PREFERENCES_NAME, MODE_PRIVATE
                )
                val cp: String? = sp.getString(Def.Meta.KEY_PRIVATE_PASSWORD, null)
                AuthenticationHelper.authenticate(
                    this@ThingsActivity, thing.getBackground(),
                    getString(R.string.check_private_thing), cp,
                        object : AuthenticationHelper.AuthenticationCallback {
                        override fun onAuthenticated() {
                            openDetailActivityForUpdate(thing, thingIndex, listPosition, v)
                        }

                        override fun onCancel() {
                        }
                    })
            } else {
                openDetailActivityForUpdate(thing, thingIndex, listPosition, v)
            }
        }

        override fun onFolderThumbnailFolderClick(
            v: View?,
            entry: ThingListEntry.FolderEntry
        ) {
            // 同 onFolderThumbnailClick：仅普通模式会到这里。
            if (v == null || mModeManager!!.getCurrentMode() != ModeManager.NORMAL) {
                return
            }
            dismissSnackbars()
            KeyboardUtil.hideKeyboard(currentFocus)
            openThingFolder(entry)
        }

        override fun onItemLongClick(v: View?, listPosition: Int): Boolean {
            if (mIsNewItemShiningBorderActive) {
                return true
            }
            if (listPosition == 0) {
                return false
            }
            dismissSnackbars()

            val entry = mThingManager!!.getThingListEntry(listPosition)
            if (entry is ThingListEntry.FolderEntry) {
                if (mModeManager!!.getCurrentMode() == ModeManager.NORMAL) {
                    mDrawerLayout!!.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
                    if (mApp!!.getStatus() == Def.ThingStatus.UNDERWAY) {
                        mModeManager!!.toMovingMode(listPosition)
                        startLongPressDragIfTouchStillActive(
                            listPosition,
                            mThingListTouchSequence,
                            "folder"
                        )
                    } else {
                        entry.folder.selected = true
                        mModeManager!!.toSelectingMode(listPosition)
                    }
                } else {
                    mModeManager!!.backNormalMode(listPosition)
                }
                return true
            }
            if (entry !is ThingListEntry.ThingEntry) return false

            val thing: Thing = mThingManager!!.getThingAtListPosition(listPosition) ?: return false
            if (mModeManager!!.getCurrentMode() == ModeManager.NORMAL
                && thing.type <= Thing.NOTIFICATION_GOAL
            ) {
                mDrawerLayout!!.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
                if (mApp!!.getStatus() == Def.ThingStatus.UNDERWAY) {
                    mModeManager!!.toMovingMode(listPosition)
                    startLongPressDragIfTouchStillActive(
                        listPosition,
                        mThingListTouchSequence,
                        "thing"
                    )
                } else {
                    thing.selected = true
                    mModeManager!!.toSelectingMode(listPosition)
                }
            } else {
                mModeManager!!.backNormalMode(listPosition)
            }
            return true
        }
    }

    private fun startLongPressDragIfTouchStillActive(
        listPosition: Int,
        touchSequence: Long,
        source: String
    ) {
        mRecyclerView!!.post {
            val holder = mRecyclerView!!.findViewHolderForAdapterPosition(listPosition)
            val touchStillActive =
                mThingListPointerDown && mThingListTouchSequence == touchSequence
            BaseThingsAdapter.logCardScaleRecoveryDebug(
                "startDrag-check source=$source position=$listPosition " +
                    "touchStillActive=$touchStillActive pointerDown=$mThingListPointerDown " +
                    "sequence=$mThingListTouchSequence expectedSequence=$touchSequence " +
                    "holder=${System.identityHashCode(holder?.itemView)}"
            )
            if (!touchStillActive) {
                holder?.itemView?.setTag(R.id.tag_thing_card_finger_down, false)
                holder?.itemView?.setTag(R.id.tag_thing_card_drag_active, false)
                if (mModeManager!!.getCurrentMode() == ModeManager.MOVING) {
                    mModeManager!!.toSelectingMode(listPosition)
                }
                return@post
            }
            if (holder != null) {
                val started = mOverlayDragController?.start(
                    listPosition,
                    holder,
                    mThingListLastRawX,
                    mThingListLastRawY
                ) == true
                if (!started && mModeManager!!.getCurrentMode() == ModeManager.MOVING) {
                    mModeManager!!.backNormalMode(listPosition)
                }
            } else if (mModeManager!!.getCurrentMode() == ModeManager.MOVING) {
                mModeManager!!.backNormalMode(listPosition)
            }
        }
    }

    private fun openThingFolder(entry: ThingListEntry.FolderEntry) {
        val folder = entry.folder
        if (shouldProtectEffectivePrivateContent(entry.effectivePrivate, folder.id)) {
            authenticateThingFolder(folder, R.string.open_private_thing_folder) {
                openThingFolderAfterAuthentication(folder, true)
            }
        } else {
            openThingFolderAfterAuthentication(folder)
        }
    }

    private fun openThingFolderAfterAuthentication(
        folder: ThingFolder,
        authenticated: Boolean = false
    ) {
        saveCurrentProjectionScrollState()
        clearOperationEmptyState()
        mThingManager!!.openFolder(folder.id, authenticated, loadThingsNow = false)
        loadThingsForCurrentSearchState()
        expandDrawerFolderAncestors(folder.id)
        if (folder.isPrivate) {
            mExpandedDrawerFolderIds.add(folder.id)
        }
        mAdapter!!.setShouldThingsAnimWhenAppearing(!App.isSearching)
        mAdapter!!.notifyDataSetChanged()
        updateEmptyStateForCurrentSearchState()
        mRecyclerView!!.scrollToPosition(0)
        refreshActivitySurfaceAndHeader()
        updateHomeNavigationContentDescription()
        updateDrawerFolderItems()
        invalidateOptionsMenu()
    }

    private fun openParentFolderForCurrentSearchState(): Boolean {
        if (mThingManager!!.getProjection().isRoot()) return false
        saveCurrentProjectionScrollState()
        if (!mThingManager!!.openParentFolder(loadThingsNow = false)) return false
        clearOperationEmptyState()
        val parentProjectionKey = mThingManager!!.getProjection().key()
        loadThingsForCurrentSearchState()
        mAdapter!!.setShouldThingsAnimWhenAppearing(false)
        mAdapter!!.notifyDataSetChanged()
        updateEmptyStateForCurrentSearchState()
        refreshActivitySurfaceAndHeader()
        updateHomeNavigationContentDescription()
        restoreProjectionScrollStateOrTop(parentProjectionKey)
        updateDrawerFolderItems()
        invalidateOptionsMenu()
        return true
    }


    private fun authenticateThingFolder(
        folder: ThingFolder,
        titleRes: Int = R.string.open_private_thing_folder,
        onAuthenticated: () -> Unit
    ) {
        val sp: SharedPreferences = getSharedPreferences(
            Def.Meta.PREFERENCES_NAME,
            MODE_PRIVATE
        )
        val cp: String? = sp.getString(Def.Meta.KEY_PRIVATE_PASSWORD, null)
        AuthenticationHelper.authenticate(
            this,
            folder.getBackground(),
            getString(titleRes),
            cp,
            object : AuthenticationHelper.AuthenticationCallback {
                override fun onAuthenticated() {
                    onAuthenticated()
                }

                override fun onCancel() {
            }
        })
    }

    private fun authenticateThing(
        thing: Thing,
        titleRes: Int = R.string.check_private_thing,
        onAuthenticated: () -> Unit
    ) {
        val sp: SharedPreferences = getSharedPreferences(
            Def.Meta.PREFERENCES_NAME,
            MODE_PRIVATE
        )
        val cp: String? = sp.getString(Def.Meta.KEY_PRIVATE_PASSWORD, null)
        AuthenticationHelper.authenticate(
            this,
            thing.getBackground(),
            getString(titleRes),
            cp,
            object : AuthenticationHelper.AuthenticationCallback {
                override fun onAuthenticated() {
                    onAuthenticated()
                }

                override fun onCancel() {
            }
        })
    }

    private fun updateThingListPointerState(event: MotionEvent, source: String) {
        if (event.pointerCount > 0) {
            mThingListLastRawX = event.rawX
            mThingListLastRawY = event.rawY
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mThingListPointerDown = true
                mThingListTouchSequence++
                logThingListPointerState(source, event.actionMasked)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                mThingListPointerDown = true
                logThingListPointerState(source, event.actionMasked)
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                mThingListPointerDown = false
                logThingListPointerState(source, event.actionMasked)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 1) {
                    mThingListPointerDown = false
                    logThingListPointerState(source, event.actionMasked)
                }
            }
        }
    }

    private fun logThingListPointerState(source: String, action: Int) {
        BaseThingsAdapter.logCardScaleRecoveryDebug(
            "pointer source=$source action=${motionActionName(action)} " +
                "down=$mThingListPointerDown sequence=$mThingListTouchSequence"
        )
    }

    private fun motionActionName(action: Int): String {
        return when (action) {
            MotionEvent.ACTION_DOWN -> "DOWN"
            MotionEvent.ACTION_UP -> "UP"
            MotionEvent.ACTION_CANCEL -> "CANCEL"
            MotionEvent.ACTION_OUTSIDE -> "OUTSIDE"
            MotionEvent.ACTION_POINTER_DOWN -> "POINTER_DOWN"
            MotionEvent.ACTION_POINTER_UP -> "POINTER_UP"
            else -> action.toString()
        }
    }

    private fun shouldProtectEffectivePrivateContent(
        effectivePrivate: Boolean,
        folderId: Long?
    ): Boolean {
        return effectivePrivate
            && !mAdapter!!.shouldShowPrivateContent()
            && !mThingManager!!.isFolderPrivacyAuthenticated(folderId)
    }

    private fun shouldProtectFolderForAccess(folderId: Long?): Boolean {
        return mThingManager!!.isFolderEffectivelyPrivate(folderId)
            && !mAdapter!!.shouldShowPrivateContent()
            && !mThingManager!!.isFolderPrivacyAuthenticated(folderId)
    }

    private fun toggleThingFolderPrivate(folder: ThingFolder): Boolean {
        if (!folder.isPrivate && !hasPrivatePassword()) {
            warnNoPasswordForPrivateFolder(folder)
            return false
        }
        if (mThingManager!!.updateFolderPrivate(folder, !folder.isPrivate, reload = false)) {
            refreshHomeAfterFolderUpdated()
            return true
        }
        return false
    }

    private fun hasPrivatePassword(): Boolean {
        val sp: SharedPreferences = getSharedPreferences(
            Def.Meta.PREFERENCES_NAME,
            MODE_PRIVATE
        )
        return sp.getString(Def.Meta.KEY_PRIVATE_PASSWORD, null) != null
    }

    private fun warnNoPasswordForPrivateFolder(folder: ThingFolder) {
        val adf = AlertDialogFragment()
        adf.setShowCancel(false)
        adf.setTitleBackground(folder.getBackground())
        adf.setConfirmBackground(folder.getBackground())
        adf.setTitle(HomeActionWordingHelper.cannotSetPrivateTitle(this))
        adf.setContent(getString(R.string.warning_should_set_password_first))
        adf.show(fragmentManager, AlertDialogFragment.TAG)
    }

    private fun showMoveThingFolderDialog(folder: ThingFolder) {
        val dialog = MoveToThingFolderDialogFragment()
        // 单个文件夹（"移动当前文件夹"或单选 1 个文件夹）用该文件夹自身的颜色。
        dialog.setAccentBackground(
            folder.getBackground() ?: ThingBackground.pure(folder.getColor())
        )
        dialog.setHasAnyFolder(mThingManager!!.hasAnyFolder())
        dialog.setFolders(
            mThingManager!!.getDrawerFolders(),
            folder.parentFolderId,
            getForbiddenFolderMoveTargetIds(folder)
        )
        dialog.setListener(object : MoveToThingFolderDialogFragment.Listener {
            override fun onMoveTargetConfirmed(targetFolderId: Long?) {
                moveFolderToFolderWithPrivacyCheck(folder, targetFolderId)
            }

            override fun shouldAuthenticateBeforeExpand(folder: ThingFolder): Boolean {
                return shouldAuthenticateTransientPrivateFolderExpansion(folder)
            }

            override fun onAuthenticateFolderExpand(
                folder: ThingFolder,
                onAuthenticated: () -> Unit
            ) {
                authenticateThingFolder(folder, R.string.expand_private_thing_folder) {
                    onAuthenticated()
                }
            }
        })
        dialog.show(fragmentManager, MoveToThingFolderDialogFragment.TAG)
    }

    private fun showMoveSelectedThingsDialog() {
        val selectedThings = mThingManager!!.getSelectedThings()
            ?.filterNotNull()
            ?.filter { it.type in Thing.NOTE..Thing.GOAL && it.state == Thing.UNDERWAY }
            ?: return
        if (selectedThings.isEmpty()) return

        val dialog = MoveToThingFolderDialogFragment()
        // 单选 1 条记事用其颜色，多选用当前文件夹色（根目录 accent 渐变）。
        dialog.setAccentBackground(selectionDialogBackground())
        dialog.setHasAnyFolder(mThingManager!!.hasAnyFolder())
        dialog.setFolders(
            mThingManager!!.getDrawerFolders(),
            getCommonSelectedThingsFolderId(selectedThings)
        )
        dialog.setListener(object : MoveToThingFolderDialogFragment.Listener {
            override fun onMoveTargetConfirmed(targetFolderId: Long?) {
                moveSelectedThingsToFolderWithPrivacyCheck(selectedThings, targetFolderId)
            }

            override fun shouldAuthenticateBeforeExpand(folder: ThingFolder): Boolean {
                return shouldAuthenticateTransientPrivateFolderExpansion(folder)
            }

            override fun onAuthenticateFolderExpand(
                folder: ThingFolder,
                onAuthenticated: () -> Unit
            ) {
                authenticateThingFolder(folder, R.string.expand_private_thing_folder) {
                    onAuthenticated()
                }
            }
        })
        dialog.show(fragmentManager, MoveToThingFolderDialogFragment.TAG)
    }

    private fun getForbiddenFolderMoveTargetIds(folder: ThingFolder): Set<Long> {
        val forbidden = HashSet<Long>()
        forbidden.add(folder.id)
        for (candidate in mThingManager!!.getDrawerFolders()) {
            if (mThingManager!!.isFolderDescendantOf(candidate.id, folder.id)) {
                forbidden.add(candidate.id)
            }
        }
        return forbidden
    }

    private fun getCommonSelectedThingsFolderId(things: List<Thing>): Long? {
        return getCommonSelectedMoveSourceFolderId(things, emptyList())
    }

    private fun getCommonSelectedMoveSourceFolderId(
        things: List<Thing>,
        folders: List<ThingFolder>
    ): Long? {
        val sourceFolderIds = ArrayList<Long?>()
        for (thing in things) {
            sourceFolderIds.add(thing.folderId)
        }
        for (folder in folders) {
            sourceFolderIds.add(folder.parentFolderId)
        }
        if (sourceFolderIds.isEmpty()) {
            return mThingManager!!.getProjection().currentFolderId
        }
        val firstFolderId = sourceFolderIds.first()
        for (folderId in sourceFolderIds) {
            if (folderId != firstFolderId) {
                return mThingManager!!.getProjection().currentFolderId
            }
        }
        return firstFolderId
    }

    private fun moveFolderToFolderWithPrivacyCheck(
        folder: ThingFolder,
        targetFolderId: Long?
    ) {
        if (folder.parentFolderId == targetFolderId) return
        val moveFolder = {
            moveEntriesToFolderWithVisualUpdate(
                listOf(folderMenuMoveSource(folder)),
                targetFolderId
            ) {
                mThingManager!!.moveFolderIntoFolder(folder, targetFolderId, reload = false)
            }
        }
        authenticatePrivateMoveIfNeeded(
            needsFolderMovePrivacyAuthentication(folder, targetFolderId),
            getFolderMovePrivacyBackground(folder, targetFolderId),
            moveFolder
        )
    }

    private fun moveSelectedThingsToFolderWithPrivacyCheck(
        selectedThings: List<Thing>,
        targetFolderId: Long?
    ) {
        authenticatePrivateMoveIfNeeded(
            needsSelectedThingsMovePrivacyAuthentication(selectedThings, targetFolderId),
            getSelectedThingsMovePrivacyBackground(selectedThings, targetFolderId)
        ) {
            moveSelectedThingsToFolder(selectedThings, targetFolderId)
        }
    }

    private fun needsFolderMovePrivacyAuthentication(
        folder: ThingFolder,
        targetFolderId: Long?
    ): Boolean {
        val sourceNeedsAuthentication =
            mThingManager!!.isFolderEffectivelyPrivate(folder.id) &&
                !mThingManager!!.isFolderPrivacyAuthenticated(folder.id)
        val targetNeedsAuthentication =
            mThingManager!!.isFolderEffectivelyPrivate(targetFolderId) &&
                !mThingManager!!.isFolderPrivacyAuthenticated(targetFolderId)
        return sourceNeedsAuthentication || targetNeedsAuthentication
    }

    private fun needsSelectedThingsMovePrivacyAuthentication(
        selectedThings: List<Thing>,
        targetFolderId: Long?
    ): Boolean {
        val targetPrivate = mThingManager!!.isFolderEffectivelyPrivate(targetFolderId)
        for (thing in selectedThings) {
            if (thing.folderId == targetFolderId) continue
            if (targetPrivate || thing.isPrivate() ||
                    mThingManager!!.isFolderEffectivelyPrivate(thing.folderId)
            ) {
                return true
            }
        }
        return false
    }

    private fun needsThingMovePrivacyAuthentication(
        thing: Thing,
        targetFolderId: Long?
    ): Boolean {
        if (thing.folderId == targetFolderId) return false
        return thing.isPrivate() ||
                mThingManager!!.isFolderEffectivelyPrivate(thing.folderId) ||
                mThingManager!!.isFolderEffectivelyPrivate(targetFolderId)
    }

    private fun getFolderMovePrivacyBackground(
        folder: ThingFolder,
        targetFolderId: Long?
    ): ThingBackground? {
        val targetFolder = targetFolderId?.let { mThingManager!!.getFolderById(it) }
        if (targetFolder != null && mThingManager!!.isFolderEffectivelyPrivate(targetFolder.id)) {
            return targetFolder.getBackground()
        }
        return folder.getBackground()
    }

    private fun getSelectedThingsMovePrivacyBackground(
        selectedThings: List<Thing>,
        targetFolderId: Long?
    ): ThingBackground? {
        val targetFolder = targetFolderId?.let { mThingManager!!.getFolderById(it) }
        if (targetFolder != null && mThingManager!!.isFolderEffectivelyPrivate(targetFolder.id)) {
            return targetFolder.getBackground()
        }
        return selectedThings.firstOrNull { it.isPrivate() }?.getBackground()
            ?: selectedThings.firstOrNull()?.getBackground()
    }

    private fun getThingMovePrivacyBackground(
        thing: Thing,
        targetFolderId: Long?
    ): ThingBackground? {
        val targetFolder = targetFolderId?.let { mThingManager!!.getFolderById(it) }
        if (targetFolder != null && mThingManager!!.isFolderEffectivelyPrivate(targetFolder.id)) {
            return targetFolder.getBackground()
        }
        return thing.getBackground()
    }

    private fun authenticatePrivateMoveIfNeeded(
        needsAuthentication: Boolean,
        background: ThingBackground?,
        onAuthenticated: () -> Unit
    ) {
        if (!needsAuthentication) {
            onAuthenticated()
            return
        }
        val sp: SharedPreferences = getSharedPreferences(
            Def.Meta.PREFERENCES_NAME,
            MODE_PRIVATE
        )
        val cp: String? = sp.getString(Def.Meta.KEY_PRIVATE_PASSWORD, null)
        AuthenticationHelper.authenticate(
            this,
            background ?: App.defaultAccentBackground,
            getString(R.string.move_private_thing_or_folder),
            cp,
            object : AuthenticationHelper.AuthenticationCallback {
                override fun onAuthenticated() {
                    onAuthenticated()
                }

                override fun onCancel() {}
            }
        )
    }

    private fun moveSelectedThingsToFolder(
        selectedThings: List<Thing>,
        folderId: Long?
    ) {
        val moveSources = selectedThings
            .filter { it.folderId != folderId }
            .map { folderMenuMoveSource(it) }
        moveEntriesToFolderWithVisualUpdate(
            moveSources,
            folderId
        ) {
            mThingManager!!.moveSelectedThingsIntoFolder(folderId, reload = false)
        }
    }

    private enum class FolderMenuMoveSourceKind {
        THING,
        FOLDER
    }

    private data class FolderMenuMoveSource(
        val kind: FolderMenuMoveSourceKind,
        val id: Long
    )

    private fun folderMenuMoveSource(thing: Thing): FolderMenuMoveSource {
        return FolderMenuMoveSource(
            FolderMenuMoveSourceKind.THING,
            thing.id
        )
    }

    private fun folderMenuMoveSource(folder: ThingFolder): FolderMenuMoveSource {
        return FolderMenuMoveSource(
            FolderMenuMoveSourceKind.FOLDER,
            folder.id
        )
    }

    private fun getFolderMenuMoveSourceOldPosition(
        source: FolderMenuMoveSource
    ): Int {
        return when (source.kind) {
            FolderMenuMoveSourceKind.THING ->
                mThingManager!!.getListPositionForThingId(source.id)
            FolderMenuMoveSourceKind.FOLDER ->
                mThingManager!!.getListPositionForFolderId(source.id)
        }
    }

    private fun moveEntriesToFolderWithVisualUpdate(
        movedSources: List<FolderMenuMoveSource>,
        targetFolderId: Long?,
        moveOperation: () -> Boolean
    ) {
        dismissSnackbars()
        finishNewItemShiningBorderAnimationIfNeeded()

        val oldItemCount = mAdapter!!.getItemCount()
        val sourceOldPositions = movedSources
            .map { getFolderMenuMoveSourceOldPosition(it) }
            .filter { it > 0 }
            .distinct()
            .sortedDescending()
        val visibleSourceOldPositions = sourceOldPositions
            .filter { isThingListPositionVisible(it) }
        val targetOldPosition = targetFolderId?.let { getVisibleFolderPosition(it) } ?: -1
        val targetViewportRect = getThingListVisibleViewportRect()
        val targetInitialRect = targetFolderId?.let {
            getVisibleFolderTargetRect(it, targetViewportRect)
        }
        val visuals = if (targetInitialRect != null) {
            sourceOldPositions.mapNotNull {
                captureFolderMenuMoveVisual(
                    it,
                    targetInitialRect
                )
            }
        } else {
            emptyList()
        }

        val changed = moveOperation()
        mModeManager!!.finishCurrentModeWithoutListRefresh()
        if (!changed) {
            restoreFolderMenuMoveVisualsImmediately(visuals)
            refreshHomeAfterFolderUpdated()
            return
        }
        loadThingsForCurrentSearchState()

        val newItemCount = mAdapter!!.getItemCount()
        val targetNewPosition = targetFolderId?.let { getVisibleFolderPosition(it) } ?: -1
        val removedCount = sourceOldPositions.size
        val itemCountMatchesTargetedRemoval = newItemCount == oldItemCount - removedCount
        val targetDidNotNewlyAppear = targetNewPosition <= 0 || targetOldPosition > 0
        val hasVisibleMoveChange =
            visibleSourceOldPositions.isNotEmpty() || targetInitialRect != null
        val canUseTargetedNotify =
            itemCountMatchesTargetedRemoval && targetDidNotNewlyAppear && hasVisibleMoveChange

        mAdapter!!.setShouldThingsAnimWhenAppearing(false)
        if (canUseTargetedNotify) {
            for (oldListPosition in sourceOldPositions) {
                mAdapter!!.notifyItemRemoved(oldListPosition)
            }
            if (targetNewPosition > 0) {
                mAdapter!!.notifyItemChanged(targetNewPosition)
            }
            afterFolderMenuMoveDataChanged()
            playFolderMenuMoveVisualsThenRebind(visuals)
        } else {
            mAdapter!!.notifyDataSetChanged()
            afterFolderMenuMoveDataChanged()
            playFolderMenuMoveVisualsThenRebind(visuals)
        }
    }

    private fun afterFolderMenuMoveDataChanged() {
        if (App.isSearching) {
            updateEmptyStateForCurrentSearchState()
        } else {
            markOperationEmptyStateIfCurrentProjectionEmpty()
        }
        refreshActivitySurfaceAndHeader()
        mDrawerHeader!!.updateTexts()
        updateDrawerFolderItems()
        invalidateOptionsMenu()
        AppWidgetHelper.updateAllThingsListAppWidgets(mApp)
    }

    private fun playFolderMenuMoveVisualsThenRebind(
        visuals: List<FolderDropCommitVisual>
    ) {
        if (visuals.isEmpty()) {
            rebindHomeListAfterFolderMenuMove()
            return
        }
        mRecyclerView!!.postOnAnimation {
            playFolderMenuMoveVisuals(visuals) {
                rebindHomeListAfterFolderMenuMove()
            }
        }
    }

    private fun rebindHomeListAfterFolderMenuMove() {
        runWhenThingListCanUpdate(
            "folder-menu-move-rebind",
            waitForItemAnimations = true
        ) {
            mAdapter!!.setShouldThingsAnimWhenAppearing(false)
            mAdapter!!.notifyDataSetChanged()
        }
    }

    private fun startDraggingThingFolder(folder: ThingFolder) {
        val listPosition = mThingManager!!.getThingListEntries()
            ?.indexOfFirst { it is ThingListEntry.FolderEntry && it.folder.id == folder.id }
            ?: -1
        if (listPosition <= 0) return
        mDrawerLayout!!.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        mModeManager!!.toMovingMode(listPosition)
        mRecyclerView!!.postDelayed({
            val holder = mRecyclerView!!.findViewHolderForAdapterPosition(listPosition)
            if (holder != null) {
                val location = IntArray(2)
                holder.itemView.getLocationOnScreen(location)
                val started = mOverlayDragController?.start(
                    listPosition,
                    holder,
                    location[0] + holder.itemView.width / 2f,
                    location[1] + holder.itemView.height / 2f
                ) == true
                if (!started) {
                    mModeManager!!.backNormalMode(listPosition)
                }
            } else {
                mModeManager!!.backNormalMode(listPosition)
            }
        }, 160)
    }

    private fun shouldPermanentlyDeleteFolder(folder: ThingFolder): Boolean {
        return folder.isDeleted() || mApp!!.getStatus() == Def.ThingStatus.DELETED
    }

    private fun showDeleteThingFolderDialogForCurrentState(folder: ThingFolder) {
        if (shouldPermanentlyDeleteFolder(folder)) {
            showDeleteThingFolderForeverDialog(
                folder,
                HomeActionWordingHelper.StructuralTarget.CURRENT_FOLDER
            )
        } else {
            confirmTrashFolderContent(folder)
        }
    }

    /**
     * Recursively finish every underway Thing (all types) in the given scope.
     * [folder] = null means the home root ("全部完成"); a folder means that
     * folder's whole subtree. Shows a confirmation, then the habit/goal
     * three-action dialog when the set contains habits or goals.
     */
    private fun confirmFinishAllThingsInScope(
        folder: ThingFolder?
    ) {
        val (things, excludesDoing) = excludeDoingFrom(
            mThingManager!!.getUnderwayThingsInScope(
                folder,
                currentSearchKeywordForScope(),
                currentSearchColorForScope()
            )
        )
        val background = folder?.getBackground() ?: App.defaultAccentBackground
        if (things.isEmpty()) {
            Toast.makeText(this, R.string.no_underway_things_to_finish, Toast.LENGTH_SHORT).show()
            return
        }
        val wording = stateActionWordingForScope(
            Def.ThingStatus.UNDERWAY,
            Thing.FINISHED,
            folder,
            things,
            excludesDoing
        )
        val adf = AlertDialogFragment()
        adf.setTitleBackground(background)
        adf.setConfirmBackground(background)
        adf.setTitle(wording.dialogTitle)
        adf.setContent(wording.dialogBody)
        adf.setConfirmText(wording.confirmText)
        adf.setConfirmListener(object : AlertDialogFragment.ConfirmListener {
            override fun onConfirm() {
                if (things.any { Thing.isImportantType(it.type) }) {
                    showFinishScopeHabitGoalDialog(things, background)
                } else {
                    applyFinishScope(things)
                }
            }
        })
        adf.show(fragmentManager, AlertDialogFragment.TAG)
    }

    private fun showFinishScopeHabitGoalDialog(things: List<Thing>, background: ThingBackground) {
        val df = ThreeActionsAlertDialogFragment()
        df.setTitleBackground(background)
        df.setContinueBackground(background)
        df.setTitle(getString(R.string.alert_continue))
        df.setContent(getString(R.string.alert_find_habit_goal))
        df.setFirstAction(getString(R.string.continue_get_rid_of_habit_goal))
        df.setSecondAction(getString(R.string.continue_for_alert))
        df.setOnClickListener(object : ThreeActionsAlertDialogFragment.OnClickListener {
            override fun onFirstClicked() {
                applyFinishScope(things.filter { !Thing.isImportantType(it.type) })
            }

            override fun onSecondClicked() {
                applyFinishScope(things)
            }

            override fun onThirdClicked() {
                // Cancelled: leave everything as is.
            }
        })
        df.show(fragmentManager, ThreeActionsAlertDialogFragment.TAG)
    }

    private fun applyFinishScope(things: List<Thing>) {
        if (things.isEmpty()) return
        finishNewItemShiningBorderAnimationIfNeeded()
        mThingManager!!.finishThings(things, reload = false)
        refreshHomeAfterScopeStateChange()
    }

    private fun refreshHomeAfterScopeStateChange() {
        exitSelectingModeIfNeeded()
        clearOperationEmptyState()
        mAdapter!!.setShouldThingsAnimWhenAppearing(false)
        loadThingsForCurrentSearchState()
        mAdapter!!.notifyDataSetChanged()
        updateEmptyStateForCurrentSearchState()
        refreshActivitySurfaceAndHeader()
        mDrawerHeader!!.updateTexts()
        invalidateOptionsMenu()
        AppWidgetHelper.updateAllThingsListAppWidgets(this@ThingsActivity)
    }

    /** Recursively restore a folder's finished Things to underway (Finished view). */
    private fun confirmUnfinishAllThingsInScope(
        folder: ThingFolder?
    ) {
        val things = mThingManager!!.getFinishedThingsInScope(
            folder,
            currentSearchKeywordForScope(),
            currentSearchColorForScope()
        )
        val background = folder?.getBackground() ?: App.defaultAccentBackground
        if (things.isEmpty()) {
            Toast.makeText(this, R.string.no_finished_things_to_restore, Toast.LENGTH_SHORT).show()
            return
        }
        val wording = stateActionWordingForScope(
            Def.ThingStatus.FINISHED,
            Thing.UNDERWAY,
            folder,
            things
        )
        val adf = AlertDialogFragment()
        adf.setTitleBackground(background)
        adf.setConfirmBackground(background)
        adf.setTitle(wording.dialogTitle)
        adf.setContent(wording.dialogBody)
        adf.setConfirmText(wording.confirmText)
        adf.setConfirmListener(object : AlertDialogFragment.ConfirmListener {
            override fun onConfirm() {
                mThingManager!!.unfinishThings(things, reload = false)
                refreshHomeAfterScopeStateChange()
            }
        })
        adf.show(fragmentManager, AlertDialogFragment.TAG)
    }

    /** Recursively restore a folder's trashed Things to their pre-trash state (recycle bin). */
    private fun confirmRestoreTrashedThingsInScope(
        folder: ThingFolder?
    ) {
        val things = mThingManager!!.getTrashedThingsInScope(
            folder,
            currentSearchKeywordForScope(),
            currentSearchColorForScope()
        )
        val background = folder?.getBackground() ?: App.defaultAccentBackground
        if (things.isEmpty()) {
            Toast.makeText(this, R.string.no_trashed_things_to_restore, Toast.LENGTH_SHORT).show()
            return
        }
        val wording = stateActionWordingForScope(
            Def.ThingStatus.DELETED,
            Thing.UNDERWAY,
            folder,
            things
        )
        val adf = AlertDialogFragment()
        adf.setTitleBackground(background)
        adf.setConfirmBackground(background)
        adf.setTitle(wording.dialogTitle)
        adf.setContent(wording.dialogBody)
        adf.setConfirmText(wording.confirmText)
        adf.setConfirmListener(object : AlertDialogFragment.ConfirmListener {
            override fun onConfirm() {
                mThingManager!!.restoreTrashedThings(things, reload = false)
                refreshHomeAfterScopeStateChange()
            }
        })
        adf.show(fragmentManager, AlertDialogFragment.TAG)
    }

    /**
     * Finished view toolbar "全部删除": recursively move every finished Thing in the
     * current scope (the current folder's whole subtree, or the entire tree at
     * root) to the recycle bin. All types, with a confirmation and filter reminder.
     */
    private fun confirmTrashAllFinishedInScope(
        folder: ThingFolder?
    ) {
        val things = mThingManager!!.getFinishedThingsInScope(
            folder,
            currentSearchKeywordForScope(),
            currentSearchColorForScope()
        )
        val background = folder?.getBackground() ?: App.defaultAccentBackground
        if (things.isEmpty()) {
            Toast.makeText(this, R.string.no_finished_things_to_delete, Toast.LENGTH_SHORT).show()
            return
        }
        val wording = stateActionWordingForScope(
            Def.ThingStatus.FINISHED,
            Thing.DELETED,
            folder,
            things
        )
        val adf = AlertDialogFragment()
        adf.setTitleBackground(background)
        adf.setConfirmBackground(background)
        adf.setTitle(wording.dialogTitle)
        adf.setContent(wording.dialogBody)
        adf.setConfirmText(wording.confirmText)
        adf.setConfirmListener(object : AlertDialogFragment.ConfirmListener {
            override fun onConfirm() {
                mThingManager!!.trashThings(things, reload = false)
                refreshHomeAfterScopeStateChange()
            }
        })
        adf.show(fragmentManager, AlertDialogFragment.TAG)
    }

    /**
     * "全部永久删除" (toolbar) / "永久删除文件夹中所有记事" (long-press a folder):
     * recursively delete forever every trashed Thing in the current scope. A content
     * op that follows the active type filter — it only touches what is in the recycle
     * bin, never the folder's Things in other status views. [titleRes] adapts the
     * label to the entry point.
     */
    private fun confirmDeleteForeverAllInScope(
        folder: ThingFolder?
    ) {
        val things = mThingManager!!.getTrashedThingsInScope(
            folder,
            currentSearchKeywordForScope(),
            currentSearchColorForScope()
        )
        val background = folder?.getBackground() ?: App.defaultAccentBackground
        if (things.isEmpty()) {
            Toast.makeText(
                this, R.string.no_trashed_things_to_delete_forever, Toast.LENGTH_SHORT
            ).show()
            return
        }
        val wording = stateActionWordingForScope(
            Def.ThingStatus.DELETED,
            Thing.DELETED_FOREVER,
            folder,
            things
        )
        val adf = AlertDialogFragment()
        adf.setTitleBackground(background)
        adf.setConfirmBackground(background)
        adf.setTitle(wording.dialogTitle)
        adf.setContent(wording.dialogBody)
        adf.setConfirmText(wording.confirmText)
        adf.setConfirmListener(object : AlertDialogFragment.ConfirmListener {
            override fun onConfirm() {
                mThingManager!!.deleteThingsForever(things, reload = false)
                refreshHomeAfterScopeStateChange()
            }
        })
        adf.show(fragmentManager, AlertDialogFragment.TAG)
    }

    /**
     * "（含所有子文件夹中的记事）" when the affected Things actually reach into a
     * subfolder of the scope, otherwise "". [folder] is the scope root (null = the
     * home root); a Thing is "in a subfolder" when its folderId differs from it.
     */
    private fun stateActionTargetForScope(folder: ThingFolder?): HomeActionWordingHelper.StateTarget {
        return if (folder == null) {
            HomeActionWordingHelper.StateTarget.ROOT
        } else {
            HomeActionWordingHelper.StateTarget.CURRENT_FOLDER
        }
    }

    private fun includesSubfolders(folder: ThingFolder?, things: List<Thing>): Boolean {
        val rootId = folder?.id
        return things.any { it.folderId != rootId }
    }

    private fun stateActionWordingForScope(
        status: Int,
        stateAfter: Int,
        folder: ThingFolder?,
        things: List<Thing>,
        excludesDoing: Boolean = false
    ): HomeActionWordingHelper.ActionWording {
        return HomeActionWordingHelper.stateActionWording(
            this,
            status,
            stateAfter,
            stateActionTargetForScope(folder),
            things.size,
            includesSubfolders(folder, things),
            mThingManager!!.getActiveTypeFilterMask(),
            excludesDoing,
            searchScoped = App.isSearching
        )
    }

    private fun folderImpactPhrase(folders: Int, things: Int): String {
        val parts = ArrayList<String>()
        if (folders > 0) parts.add(getString(R.string.folder_count_segment, folders))
        if (things > 0) parts.add(getString(R.string.thing_count_segment, things))
        if (parts.isEmpty()) return getString(R.string.folder_impact_empty)
        return parts.joinToString(getString(R.string.folder_impact_separator))
    }

    private fun statusNameRes(status: Int): Int = when (status) {
        Def.ThingStatus.FINISHED -> R.string.finished
        Def.ThingStatus.DELETED -> R.string.thing_status_deleted
        else -> R.string.underway
    }

    private fun hiddenScopeClauseForStructural(
        affected: List<Thing>,
        considerStatus: Boolean = true
    ): String? {
        val mask = mThingManager!!.getActiveTypeFilterMask()
        val typeTitle = ThingWidgetInfo.getTypeFilterTitle(this, mask)
        val normalizedMask = ThingWidgetInfo.normalizedTypeFilterMask(mask)
        val hasOtherType = typeTitle != null && affected.any {
            ThingWidgetInfo.normalizedTypeFilterMask(
                ThingWidgetInfo.typeFilterMaskForThingType(it.type)
            ) and normalizedMask == 0
        }
        val currentStatus = mApp!!.getStatus()
        val hasOtherStatus = considerStatus && affected.any {
            Thing.getStatusForState(it.state) != currentStatus
        }
        val hasSearchScope = App.isSearching
        if (!hasOtherStatus && !hasOtherType && !hasSearchScope) return null
        val statusName = getString(statusNameRes(currentStatus))
        return when {
            hasOtherStatus && hasOtherType && hasSearchScope ->
                getString(R.string.folder_op_scope_status_type_search, statusName, typeTitle)
            hasOtherStatus && hasOtherType ->
                getString(R.string.folder_op_scope_both, statusName, typeTitle)
            hasOtherStatus && hasSearchScope ->
                getString(R.string.folder_op_scope_status_search, statusName)
            hasOtherStatus ->
                getString(R.string.folder_op_scope_status, statusName)
            hasOtherType && hasSearchScope ->
                getString(R.string.folder_op_scope_type_filter_search, typeTitle)
            else ->
                if (hasOtherType) {
                    getString(R.string.folder_op_scope_type, typeTitle)
                } else {
                    getString(R.string.folder_op_scope_search)
                }
        }
    }

    private fun showDissolveThingFolderDialog(
        folder: ThingFolder,
        target: HomeActionWordingHelper.StructuralTarget =
            HomeActionWordingHelper.StructuralTarget.SELECTED_FOLDER
    ) {
        val affected = mThingManager!!.getAllDescendantThings(folder)
        val subfolderCount = mThingManager!!.countDescendantFolders(folder)
        val thingCount = mThingManager!!.countAllDescendantThings(folder)
        val wording = HomeActionWordingHelper.structuralActionWording(
            this,
            HomeActionWordingHelper.StructuralAction.DISSOLVE_FOLDER,
            target,
            folderImpactPhrase(subfolderCount, thingCount),
            hiddenScopeClauseForStructural(
                affected,
                considerStatus = mApp!!.getStatus() != Def.ThingStatus.DELETED
            ),
            impactIsEmpty = subfolderCount == 0 && thingCount == 0
        )
        val adf = AlertDialogFragment()
        adf.setTitleBackground(folder.getBackground())
        adf.setConfirmBackground(folder.getBackground())
        adf.setTitle(wording.dialogTitle)
        adf.setContent(wording.dialogBody)
        adf.setConfirmText(wording.confirmText)
        adf.setConfirmListener(object : AlertDialogFragment.ConfirmListener {
            override fun onConfirm() {
                if (mThingManager!!.dissolveFolder(folder)) {
                    exitSelectingModeIfNeeded()
                    refreshHomeAfterFolderUpdated()
                    AppWidgetHelper.updateAllThingsListAppWidgets(this@ThingsActivity)
                }
            }
        })
        adf.show(fragmentManager, AlertDialogFragment.TAG)
    }

    /**
     * "删除文件夹中所有记事": in the pure-skeleton model, deleting a folder trashes its
     * content rather than the container. Recursively moves the folder's non-deleted
     * Things (matching the active type filter) to the recycle bin; the folder itself
     * stays in place and naturally drops out of the non-deleted views once it has no
     * matching Things.
     */
    private fun confirmTrashFolderContent(folder: ThingFolder) {
        // Underway view: delete only the folder's underway Things (matching what is
        // visible here); finished Things are deleted from the Finished view instead.
        val things = mThingManager!!.getUnderwayThingsInScope(
            folder,
            currentSearchKeywordForScope(),
            currentSearchColorForScope()
        )
        if (things.isEmpty()) {
            Toast.makeText(this, R.string.no_things_to_delete_in_folder, Toast.LENGTH_SHORT).show()
            return
        }
        val wording = stateActionWordingForScope(
            Def.ThingStatus.UNDERWAY,
            Thing.DELETED,
            folder,
            things
        )
        val adf = AlertDialogFragment()
        adf.setTitleBackground(folder.getBackground())
        adf.setConfirmBackground(folder.getBackground())
        adf.setTitle(wording.dialogTitle)
        adf.setContent(wording.dialogBody)
        adf.setConfirmText(wording.confirmText)
        adf.setConfirmListener(object : AlertDialogFragment.ConfirmListener {
            override fun onConfirm() {
                mThingManager!!.trashThingsPreservingState(things, reload = false)
                refreshHomeAfterScopeStateChange()
            }
        })
        adf.show(fragmentManager, AlertDialogFragment.TAG)
    }

    private fun showDeleteThingFolderForeverDialog(
        folder: ThingFolder,
        target: HomeActionWordingHelper.StructuralTarget =
            HomeActionWordingHelper.StructuralTarget.SELECTED_FOLDER
    ) {
        val affected = mThingManager!!.getAllDescendantThings(folder)
        val subfolderCount = mThingManager!!.countDescendantFolders(folder)
        val thingCount = mThingManager!!.countAllDescendantThings(folder)
        val wording = HomeActionWordingHelper.structuralActionWording(
            this,
            HomeActionWordingHelper.StructuralAction.DELETE_FOLDER_FOREVER,
            target,
            folderImpactPhrase(subfolderCount, thingCount),
            hiddenScopeClauseForStructural(affected, considerStatus = true),
            impactIsEmpty = subfolderCount == 0 && thingCount == 0
        )
        val adf = AlertDialogFragment()
        adf.setTitleBackground(folder.getBackground())
        adf.setConfirmBackground(folder.getBackground())
        adf.setTitle(wording.dialogTitle)
        adf.setContent(wording.dialogBody)
        adf.setConfirmText(wording.confirmText)
        adf.setConfirmListener(object : AlertDialogFragment.ConfirmListener {
            override fun onConfirm() {
                if (mThingManager!!.deleteFolderForever(folder)) {
                    exitSelectingModeIfNeeded()
                    refreshHomeAfterFolderUpdated()
                    AppWidgetHelper.updateAllThingsListAppWidgets(this@ThingsActivity)
                }
            }
        })
        adf.show(fragmentManager, AlertDialogFragment.TAG)
    }

    private fun exitSelectingModeIfNeeded() {
        if (mModeManager!!.getCurrentMode() == ModeManager.SELECTING) {
            mModeManager!!.backNormalMode(0)
        }
    }

    private fun showRenameThingFolderDialog(folder: ThingFolder) {
        showThingFolderNameDialog(
            folder,
            R.string.rename_thing_folder,
            folder.title.ifEmpty { getString(R.string.default_thing_folder_name) },
            object : ThingFolderNameDialogFragment.Listener {
                override fun onThingFolderNameConfirmed(title: String) {
                    if (mThingManager!!.renameFolder(folder, title)) {
                        refreshHomeAfterFolderUpdated()
                    }
                }

                override fun onThingFolderNameCanceled() { }
            }
        )
    }

    private fun showCreateThingFolderNameDialog(createdDrop: CreatedThingFolderDrop) {
        showThingFolderNameDialog(
            createdDrop.folder,
            R.string.create_thing_folder_title,
            createdDrop.folder.title.ifEmpty { getString(R.string.default_thing_folder_name) },
            object : ThingFolderNameDialogFragment.Listener {
                override fun onThingFolderNameConfirmed(title: String) {
                    if (mThingManager!!.renameFolder(createdDrop.folder, title)) {
                        refreshHomeAfterFolderUpdated()
                    }
                }

                override fun onThingFolderNameCanceled() {
                    cancelCreatedThingFolderDrop(createdDrop)
                }
            }
        )
    }

    private fun showThingFolderNameDialog(
        folder: ThingFolder,
        @StringRes titleRes: Int,
        initialTitle: String,
        listener: ThingFolderNameDialogFragment.Listener
    ) {
        val dialog = ThingFolderNameDialogFragment()
        dialog.setTitleRes(titleRes)
        dialog.setInitialTitle(initialTitle)
        dialog.setAccentBackground(folder.getBackground())
        dialog.setListener(listener)
        dialog.show(fragmentManager, ThingFolderNameDialogFragment.TAG)
    }

    private fun cancelCreatedThingFolderDrop(createdDrop: CreatedThingFolderDrop) {
        val canceled = mThingManager!!.cancelCreatedFolder(
            createdDrop.folder,
            longArrayOf(createdDrop.sourceThingId, createdDrop.targetThingId),
            arrayOf(createdDrop.sourceParentFolderId, createdDrop.targetParentFolderId),
            longArrayOf(createdDrop.sourceLocation, createdDrop.targetLocation)
        )
        if (canceled) {
            refreshHomeAfterFolderCreationCanceled()
        }
    }

    private fun refreshHomeAfterFolderCreationCanceled() {
        mAdapter!!.setShouldThingsAnimWhenAppearing(false)
        loadThingsForCurrentSearchState()
        mAdapter!!.notifyDataSetChanged()
        updateEmptyStateForCurrentSearchState()
        updateHomeAfterFolderDropCommitted()
    }

    private fun refreshHomeAfterFolderUpdated() {
        mAdapter!!.setShouldThingsAnimWhenAppearing(false)
        loadThingsForCurrentSearchState()
        mAdapter!!.notifyDataSetChanged()
        if (App.isSearching) {
            updateEmptyStateForCurrentSearchState()
        } else {
            markOperationEmptyStateIfCurrentProjectionEmpty()
        }
        refreshActivitySurfaceAndHeader()
        mDrawerHeader!!.updateTexts()
        updateDrawerFolderItems()
        invalidateOptionsMenu()
    }

    private fun commitMoveThingIntoFolderDrop(
        sourceThingId: Long,
        targetFolderId: Long,
        onCommitted: (Boolean) -> Unit
    ) {
        val sourceThing = mThingManager!!.getThingById(sourceThingId) ?: run {
            onCommitted(false)
            return
        }
        val targetEntry = getVisibleFolderEntry(targetFolderId) ?: run {
            onCommitted(false)
            return
        }
        if (!canMoveThingIntoExistingFolderWith(sourceThing, targetEntry)) {
            onCommitted(false)
            return
        }
        val sourceOldListPosition = mThingManager!!.getListPositionForThingId(sourceThingId)
        if (sourceOldListPosition <= 0) {
            onCommitted(false)
            return
        }

        val commitMove = {
            dismissSnackbars()
            finishNewItemShiningBorderAnimationIfNeeded()
            mThingManager!!.moveThingIntoFolder(sourceThing, targetEntry.folder.id, reload = false)
            loadThingsForCurrentSearchState()
            val targetNewListPosition = getVisibleFolderPosition(targetFolderId)
            notifyFolderDropCommitted(sourceOldListPosition, targetNewListPosition)
            updateHomeAfterFolderDropCommitted()
            onCommitted(true)
        }
        authenticatePrivateMoveIfNeeded(
            needsThingMovePrivacyAuthentication(sourceThing, targetEntry.folder.id),
            getThingMovePrivacyBackground(sourceThing, targetEntry.folder.id),
            commitMove
        )
    }

    private fun commitMoveFolderIntoFolderDrop(
        sourceFolderId: Long,
        targetFolderId: Long,
        onCommitted: (Boolean) -> Unit
    ) {
        val sourceFolder = mThingManager!!.getFolderById(sourceFolderId) ?: run {
            onCommitted(false)
            return
        }
        val targetEntry = getVisibleFolderEntry(targetFolderId) ?: run {
            onCommitted(false)
            return
        }
        if (!canMoveFolderIntoExistingFolderWith(sourceFolder, targetEntry)) {
            onCommitted(false)
            return
        }
        val sourceOldListPosition = mThingManager!!.getListPositionForFolderId(sourceFolderId)
        if (sourceOldListPosition <= 0) {
            onCommitted(false)
            return
        }

        val commitMove = {
            dismissSnackbars()
            finishNewItemShiningBorderAnimationIfNeeded()
            mThingManager!!.moveFolderIntoFolder(
                sourceFolder,
                targetEntry.folder.id,
                reload = false
            )
            loadThingsForCurrentSearchState()
            val targetNewListPosition = getVisibleFolderPosition(targetFolderId)
            notifyFolderDropCommitted(sourceOldListPosition, targetNewListPosition)
            updateHomeAfterFolderDropCommitted()
            onCommitted(true)
        }
        authenticatePrivateMoveIfNeeded(
            needsFolderMovePrivacyAuthentication(sourceFolder, targetEntry.folder.id),
            getFolderMovePrivacyBackground(sourceFolder, targetEntry.folder.id),
            commitMove
        )
    }

    private fun commitCreateThingFolderDrop(
        sourceThingId: Long,
        targetThingId: Long,
        folderBackground: ThingBackground
    ): CreatedThingFolderDrop? {
        val sourceThing = mThingManager!!.getThingById(sourceThingId) ?: return null
        val targetThing = mThingManager!!.getThingById(targetThingId) ?: return null
        if (!canCreateThingFolderWith(sourceThing) || !canCreateThingFolderWith(targetThing)) {
            return null
        }
        val sourceOldListPosition = mThingManager!!.getListPositionForThingId(sourceThingId)
        if (sourceOldListPosition <= 0) return null
        val sourceParentFolderId = sourceThing.folderId
        val sourceLocation = sourceThing.location
        val targetParentFolderId = targetThing.folderId
        val targetLocation = targetThing.location

        dismissSnackbars()
        finishNewItemShiningBorderAnimationIfNeeded()
        val folder = mThingManager!!.createFolderFromThings(
            getString(R.string.default_thing_folder_name),
            sourceThing,
            targetThing,
            folderBackground,
            loadThingsNow = false
        ) ?: return null
        loadThingsForCurrentSearchState()
        val folderListPosition = getVisibleFolderPosition(folder.id)
        notifyFolderDropCommitted(sourceOldListPosition, folderListPosition)
        updateHomeAfterFolderDropCommitted()
        return CreatedThingFolderDrop(
            folder,
            sourceThingId,
            targetThingId,
            sourceParentFolderId,
            sourceLocation,
            targetParentFolderId,
            targetLocation
        )
    }

    private fun notifyFolderDropCommitted(sourceOldListPosition: Int, changedListPosition: Int) {
        mAdapter!!.setShouldThingsAnimWhenAppearing(false)
        if (App.isSearching) {
            mAdapter!!.notifyDataSetChanged()
            updateEmptyStateForCurrentSearchState()
            return
        }
        if (sourceOldListPosition > 0) {
            mAdapter!!.notifyItemRemoved(sourceOldListPosition)
            if (changedListPosition > 0) {
                mAdapter!!.notifyItemChanged(changedListPosition)
            }
        } else {
            mAdapter!!.notifyDataSetChanged()
        }
    }

    private fun runWhenThingListCanUpdate(
        reason: String,
        waitForItemAnimations: Boolean = false,
        block: () -> Unit
    ) {
        val recyclerView = mRecyclerView ?: return
        if (recyclerView.isComputingLayout ||
            recyclerView.hasPendingAdapterUpdates() ||
            recyclerView.isLayoutRequested ||
            recyclerView.scrollState != RecyclerView.SCROLL_STATE_IDLE
        ) {
            BaseThingsAdapter.logCardScaleRecoveryDebug(
                "defer-list-update reason=$reason " +
                    "computing=${recyclerView.isComputingLayout} " +
                    "pending=${recyclerView.hasPendingAdapterUpdates()} " +
                    "layoutRequested=${recyclerView.isLayoutRequested} " +
                    "scrollState=${recyclerView.scrollState}"
            )
            recyclerView.postDelayed({
                runWhenThingListCanUpdate(reason, waitForItemAnimations, block)
            }, 32L)
            return
        }
        val itemAnimator = recyclerView.itemAnimator
        if (waitForItemAnimations && itemAnimator != null && itemAnimator.isRunning) {
            BaseThingsAdapter.logCardScaleRecoveryDebug(
                "defer-list-update reason=$reason itemAnimatorRunning=true"
            )
            itemAnimator.isRunning(
                RecyclerView.ItemAnimator.ItemAnimatorFinishedListener {
                    recyclerView.post {
                        runWhenThingListCanUpdate(reason, true, block)
                    }
                }
            )
            return
        }
        BaseThingsAdapter.logCardScaleRecoveryDebug("run-list-update reason=$reason")
        block()
    }

    private fun updateHomeAfterFolderDropCommitted() {
        markOperationEmptyStateIfCurrentProjectionEmpty()
        refreshActivitySurfaceAndHeader()
        mDrawerHeader!!.updateTexts()
        updateDrawerFolderItems()
        AppWidgetHelper.updateAllThingsListAppWidgets(mApp)
    }

    private fun rebindHomeListAfterFolderDropModeExit() {
        runWhenThingListCanUpdate(
            "folder-drop-mode-exit-rebind",
            waitForItemAnimations = true
        ) {
            mAdapter!!.setShouldThingsAnimWhenAppearing(false)
            mAdapter!!.notifyDataSetChanged()
        }
    }

    private fun getVisibleFolderEntry(folderId: Long): ThingListEntry.FolderEntry? {
        val entries = mThingManager!!.getThingListEntries() ?: return null
        for (entry in entries) {
            if (entry is ThingListEntry.FolderEntry && entry.folder.id == folderId) {
                return entry
            }
        }
        return null
    }

    private fun getVisibleFolderPosition(folderId: Long): Int {
        val entries = mThingManager!!.getThingListEntries() ?: return -1
        for (i in entries.indices) {
            val entry = entries[i]
            if (entry is ThingListEntry.FolderEntry && entry.folder.id == folderId) {
                return i
            }
        }
        return -1
    }

    private fun captureFolderDropCommitVisual(
        viewHolder: RecyclerView.ViewHolder,
        drop: PendingFolderDrop,
        startLeftInRoot: Float? = null,
        startTopInRoot: Float? = null
    ): FolderDropCommitVisual? {
        val sourceView = viewHolder.itemView
        if (sourceView.width <= 0 || sourceView.height <= 0) return null
        val targetView = getFolderDropTargetView(drop) ?: return null
        val root = findViewById<ViewGroup>(android.R.id.content) ?: return null

        val bitmap = Bitmap.createBitmap(
            sourceView.width,
            sourceView.height,
            Bitmap.Config.ARGB_8888
        )
        sourceView.draw(Canvas(bitmap))

        val rootLocation = IntArray(2)
        val recyclerLocation = IntArray(2)
        root.getLocationOnScreen(rootLocation)
        mRecyclerView!!.getLocationOnScreen(recyclerLocation)

        val sourceLeft = recyclerLocation[0] + sourceView.left + sourceView.translationX
        val sourceTop = recyclerLocation[1] + sourceView.top + sourceView.translationY
        val sourceLeftInRoot = startLeftInRoot ?: (sourceLeft - rootLocation[0])
        val sourceTopInRoot = startTopInRoot ?: (sourceTop - rootLocation[1])
        val targetLocation = IntArray(2)
        targetView.getLocationOnScreen(targetLocation)
        val targetRect = RectF(
            targetLocation[0] - rootLocation[0].toFloat(),
            targetLocation[1] - rootLocation[1].toFloat(),
            targetLocation[0] - rootLocation[0].toFloat() + targetView.width,
            targetLocation[1] - rootLocation[1].toFloat() + targetView.height
        )

        val overlay = ImageView(this)
        overlay.setImageBitmap(bitmap)
        overlay.scaleType = ImageView.ScaleType.FIT_XY
        overlay.pivotX = 0f
        overlay.pivotY = 0f
        overlay.elevation = sourceView.elevation + resources.displayMetrics.density * 8
        root.addView(
            overlay,
            FrameLayout.LayoutParams(sourceView.width, sourceView.height)
        )
        overlay.x = sourceLeftInRoot
        overlay.y = sourceTopInRoot
        return FolderDropCommitVisual(root, overlay, sourceView, targetRect)
    }

    private fun getFolderDropTargetView(drop: PendingFolderDrop): View? {
        val listPosition = when (drop.action) {
            FOLDER_DROP_ACTION_CREATE -> {
                val targetThingId = drop.targetThingId ?: return null
                mThingManager!!.getListPositionForThingId(targetThingId)
            }
            FOLDER_DROP_ACTION_MOVE_TO_FOLDER -> {
                val targetFolderId = drop.targetFolderId ?: return null
                getVisibleFolderPosition(targetFolderId)
            }
            else -> RecyclerView.NO_POSITION
        }
        val holder = mRecyclerView!!.findViewHolderForAdapterPosition(listPosition)
            as? BaseThingsAdapter.BaseThingViewHolder
        return holder?.cv ?: holder?.itemView
    }

    private fun playFolderDropCommitVisual(
        visual: FolderDropCommitVisual?,
        onFinished: () -> Unit
    ) {
        if (visual == null) {
            onFinished()
            return
        }
        val overlay = visual.overlay
        val targetScale = 0.16f
        val targetX = visual.targetRect.centerX() - overlay.width * targetScale / 2f
        val targetY = visual.targetRect.centerY() - overlay.height * targetScale / 2f
        overlay.animate().cancel()
        overlay.animate()
            .x(targetX)
            .y(targetY)
            .scaleX(targetScale)
            .scaleY(targetScale)
            .alpha(0.0f)
            .setDuration(FOLDER_DROP_COMMIT_ANIM_DURATION)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    overlay.setImageDrawable(null)
                    visual.root.removeView(overlay)
                    onFinished()
                }

                override fun onAnimationCancel(animation: Animator) {
                    overlay.setImageDrawable(null)
                    visual.root.removeView(overlay)
                }
            })
            .start()
    }

    private fun restoreFolderDropSourceViewLater(visual: FolderDropCommitVisual?) {
        if (visual == null) return
        val delay = max(
            FOLDER_DROP_COMMIT_ANIM_DURATION,
            mRecyclerView!!.itemAnimator?.removeDuration ?: FOLDER_DROP_COMMIT_ANIM_DURATION
        ) + 80L
        mRecyclerView!!.postDelayed({
            visual.sourceView.visibility = View.VISIBLE
            visual.sourceView.alpha = 1.0f
        }, delay)
    }

    private fun restoreFolderDropVisualImmediately(visual: FolderDropCommitVisual?) {
        if (visual == null) return
        visual.overlay.animate().cancel()
        visual.overlay.setImageDrawable(null)
        visual.root.removeView(visual.overlay)
        visual.sourceView.visibility = View.VISIBLE
        visual.sourceView.alpha = 1.0f
    }

    private fun captureFolderMenuMoveVisual(
        sourceListPosition: Int,
        targetRect: RectF
    ): FolderDropCommitVisual? {
        val holder = mRecyclerView!!.findViewHolderForAdapterPosition(sourceListPosition)
            ?: return null
        val sourceView = (holder as? BaseThingsAdapter.BaseThingViewHolder)?.cv
            ?: holder.itemView
        if (!isViewVisibleInThingList(sourceView)) return null
        if (sourceView.width <= 0 || sourceView.height <= 0) return null
        val root = findViewById<ViewGroup>(android.R.id.content) ?: return null
        val sourceRect = getViewRectInActivityRoot(sourceView) ?: return null

        val bitmap = try {
            Bitmap.createBitmap(
                sourceView.width,
                sourceView.height,
                Bitmap.Config.ARGB_8888
            ).also { sourceView.draw(Canvas(it)) }
        } catch (_: RuntimeException) {
            return null
        }

        val overlay = ImageView(this)
        overlay.setImageBitmap(bitmap)
        overlay.scaleType = ImageView.ScaleType.FIT_XY
        overlay.pivotX = 0f
        overlay.pivotY = 0f
        overlay.elevation = sourceView.elevation + resources.displayMetrics.density * 8
        root.addView(
            overlay,
            FrameLayout.LayoutParams(sourceView.width, sourceView.height)
        )
        overlay.x = sourceRect.left
        overlay.y = sourceRect.top
        sourceView.visibility = View.INVISIBLE
        sourceView.alpha = 0.0f
        return FolderDropCommitVisual(
            root,
            overlay,
            sourceView,
            targetRect
        )
    }

    private fun playFolderMenuMoveVisuals(
        visuals: List<FolderDropCommitVisual>,
        onFinished: () -> Unit
    ) {
        if (visuals.isEmpty()) {
            onFinished()
            return
        }
        var remaining = visuals.size
        fun finishOne(visual: FolderDropCommitVisual) {
            visual.overlay.animate().setListener(null)
            visual.overlay.setImageDrawable(null)
            visual.root.removeView(visual.overlay)
            remaining--
            if (remaining == 0) {
                onFinished()
            }
        }

        for (visual in visuals) {
            val overlay = visual.overlay
            val targetRect = visual.targetRect
            val targetScale = 0.16f
            val targetX = targetRect.centerX() - overlay.width * targetScale / 2f
            val targetY = targetRect.centerY() - overlay.height * targetScale / 2f
            var finished = false
            fun finishOnce() {
                if (finished) return
                finished = true
                finishOne(visual)
            }
            overlay.animate().cancel()
            overlay.animate()
                .x(targetX)
                .y(targetY)
                .scaleX(targetScale)
                .scaleY(targetScale)
                .alpha(0.0f)
                .setDuration(FOLDER_DROP_COMMIT_ANIM_DURATION)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        finishOnce()
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        finishOnce()
                    }
                })
                .start()
        }
    }

    private fun restoreFolderMenuMoveVisualsImmediately(
        visuals: List<FolderDropCommitVisual>
    ) {
        for (visual in visuals) {
            restoreFolderDropVisualImmediately(visual)
        }
    }

    private fun getVisibleFolderTargetRect(
        folderId: Long,
        viewportRect: RectF? = null
    ): RectF? {
        val listPosition = getVisibleFolderPosition(folderId)
        if (listPosition <= 0) return null
        val holder = mRecyclerView!!.findViewHolderForAdapterPosition(listPosition)
            ?: return null
        val targetView = (holder as? BaseThingsAdapter.BaseThingViewHolder)?.cv
            ?: holder.itemView
        return getVisibleRectInThingList(targetView, viewportRect)
    }

    private fun isViewVisibleInThingList(view: View): Boolean {
        return getVisibleRectInThingList(view) != null
    }

    private fun isThingListPositionVisible(listPosition: Int): Boolean {
        val holder = mRecyclerView!!.findViewHolderForAdapterPosition(listPosition)
            ?: return false
        val view = (holder as? BaseThingsAdapter.BaseThingViewHolder)?.cv
            ?: holder.itemView
        return isViewVisibleInThingList(view)
    }

    private fun getVisibleRectInThingList(
        view: View,
        viewportRect: RectF? = null
    ): RectF? {
        if (!view.isShown || view.width <= 0 || view.height <= 0) return null
        val viewRect = getGlobalVisibleRectInActivityRoot(view) ?: return null
        val viewport = viewportRect ?: getThingListVisibleViewportRect() ?: return null
        val visibleRect = RectF(viewRect)
        if (!visibleRect.intersect(viewport)) return null
        if (visibleRect.width() <= 1f || visibleRect.height() <= 1f) return null
        return visibleRect
    }

    private fun getThingListVisibleViewportRect(): RectF? {
        val recyclerRect = getViewRectInActivityRoot(mRecyclerView!!) ?: return null
        val root = findViewById<ViewGroup>(android.R.id.content) ?: return recyclerRect
        val rootRect = RectF(0f, 0f, root.width.toFloat(), root.height.toFloat())
        val viewport = RectF(recyclerRect)
        if (!viewport.intersect(rootRect)) return null
        getContextualToolbarObstructionRect()?.let { obstruction ->
            subtractVerticalObstruction(viewport, obstruction)
        }
        if (viewport.width() <= 1f || viewport.height() <= 1f) return null
        return viewport
    }

    private fun getContextualToolbarObstructionRect(): RectF? {
        val contextualToolbarWrapper = findViewById<View>(R.id.rl_contextual_toolbar)
            ?: return null
        if (!contextualToolbarWrapper.isShown ||
            contextualToolbarWrapper.width <= 0 ||
            contextualToolbarWrapper.height <= 0
        ) {
            return null
        }
        return getViewRectInActivityRoot(contextualToolbarWrapper)
    }

    private fun subtractVerticalObstruction(rect: RectF, obstruction: RectF) {
        if (!RectF.intersects(rect, obstruction)) return
        if (obstruction.left > rect.left || obstruction.right < rect.right) return

        if (obstruction.top <= rect.top) {
            rect.top = rect.top.coerceAtLeast(obstruction.bottom)
            return
        }
        if (obstruction.bottom >= rect.bottom) {
            rect.bottom = rect.bottom.coerceAtMost(obstruction.top)
            return
        }

        val topHeight = obstruction.top - rect.top
        val bottomHeight = rect.bottom - obstruction.bottom
        if (bottomHeight >= topHeight) {
            rect.top = obstruction.bottom
        } else {
            rect.bottom = obstruction.top
        }
    }

    private fun getGlobalVisibleRectInActivityRoot(view: View): RectF? {
        val visibleRectOnScreen = Rect()
        if (!view.getGlobalVisibleRect(visibleRectOnScreen)) return null
        if (visibleRectOnScreen.width() <= 1 || visibleRectOnScreen.height() <= 1) {
            return null
        }
        val root = findViewById<ViewGroup>(android.R.id.content) ?: return null
        val rootLocation = IntArray(2)
        root.getLocationOnScreen(rootLocation)
        return RectF(
            visibleRectOnScreen.left - rootLocation[0].toFloat(),
            visibleRectOnScreen.top - rootLocation[1].toFloat(),
            visibleRectOnScreen.right - rootLocation[0].toFloat(),
            visibleRectOnScreen.bottom - rootLocation[1].toFloat()
        )
    }

    private fun getViewRectInActivityRoot(view: View): RectF? {
        if (view.width <= 0 || view.height <= 0) return null
        val root = findViewById<ViewGroup>(android.R.id.content) ?: return null
        val rootLocation = IntArray(2)
        val viewLocation = IntArray(2)
        root.getLocationOnScreen(rootLocation)
        view.getLocationOnScreen(viewLocation)
        val left = viewLocation[0] - rootLocation[0].toFloat()
        val top = viewLocation[1] - rootLocation[1].toFloat()
        return RectF(left, top, left + view.width, top + view.height)
    }

    private fun canCreateThingFolderWith(thing: Thing?): Boolean {
        if (thing == null) return false
        if (thing.type !in Thing.NOTE..Thing.GOAL) return false
        if (thing.state != Thing.UNDERWAY) return false
        return !isThingEffectivelyPrivateInCurrentProjection(thing)
    }

    private fun canMoveThingIntoExistingFolderWith(
        thing: Thing?,
        targetEntry: ThingListEntry.FolderEntry
    ): Boolean {
        if (thing == null) return false
        if (thing.type !in Thing.NOTE..Thing.GOAL) return false
        if (thing.state != Thing.UNDERWAY) return false
        if (thing.folderId == targetEntry.folder.id) return false
        return true
    }

    private fun canMoveFolderIntoExistingFolderWith(
        folder: ThingFolder?,
        targetEntry: ThingListEntry.FolderEntry
    ): Boolean {
        if (folder == null) return false
        if (folder.id == targetEntry.folder.id) return false
        if (folder.parentFolderId == targetEntry.folder.id) return false
        if (folder.isDeleted()) return false
        if (mThingManager!!.isFolderDescendantOf(targetEntry.folder.id, folder.id)) return false
        return true
    }

    private fun isThingEffectivelyPrivateInCurrentProjection(thing: Thing): Boolean {
        return (thing.isPrivate()
            || mThingManager!!.isCurrentFolderEffectivelyPrivate())
            && !mThingManager!!.isCurrentFolderPrivacyAuthenticated()
    }

    private fun navigateToFolderPathSegment(folderPathIndex: Int) {
        if (mModeManager!!.getCurrentMode() != ModeManager.NORMAL) {
            return
        }
        dismissSnackbars()
        saveCurrentProjectionScrollState()
        clearOperationEmptyState()
        mThingManager!!.navigateToFolderPathIndex(folderPathIndex, loadThingsNow = false)
        val projectionKey = mThingManager!!.getProjection().key()
        loadThingsForCurrentSearchState()
        mAdapter!!.setShouldThingsAnimWhenAppearing(false)
        mAdapter!!.notifyDataSetChanged()
        updateEmptyStateForCurrentSearchState()
        refreshActivitySurfaceAndHeader()
        updateHomeNavigationContentDescription()
        restoreProjectionScrollStateOrTop(projectionKey)
        updateDrawerFolderItems()
        invalidateOptionsMenu()
    }

    private data class PendingFolderDrop(
        val action: Int,
        val sourceListPosition: Int,
        val targetListPosition: Int,
        val sourceThingId: Long?,
        val sourceFolderId: Long?,
        val targetThingId: Long?,
        val targetFolderId: Long?,
        val background: ThingBackground?
    )

    private data class CreatedThingFolderDrop(
        val folder: ThingFolder,
        val sourceThingId: Long,
        val targetThingId: Long,
        val sourceParentFolderId: Long?,
        val sourceLocation: Long,
        val targetParentFolderId: Long?,
        val targetLocation: Long
    )

    private data class FolderDropHoverCandidate(
        val action: Int,
        val sourceListPosition: Int,
        val targetListPosition: Int,
        val sourceThingId: Long?,
        val sourceFolderId: Long?,
        val targetThingId: Long?,
        val targetFolderId: Long?,
        val background: ThingBackground?
    )

    private data class FolderDropCommitVisual(
        val root: ViewGroup,
        val overlay: ImageView,
        val sourceView: View,
        val targetRect: RectF
    )

    override fun createOverlayDragSource(
        listPosition: Int
    ): ThingListOverlayDragController.DragSource? {
        val entry = mThingManager!!.getThingListEntry(listPosition) ?: return null
        return when (entry) {
            is ThingListEntry.ThingEntry -> {
                val thing = entry.thing
                if (thing.type == Thing.HEADER) return null
                ThingListOverlayDragController.DragSource(
                    ThingListOverlayDragController.DragSource.Kind.THING,
                    entry.stableId,
                    thing.id,
                    null,
                    listPosition,
                    thing.getBackground() ?: ThingBackground.pure(thing.getColor())
                )
            }
            is ThingListEntry.FolderEntry -> {
                val folder = entry.folder
                ThingListOverlayDragController.DragSource(
                    ThingListOverlayDragController.DragSource.Kind.FOLDER,
                    entry.stableId,
                    null,
                    folder.id,
                    listPosition,
                    folder.getBackground() ?: ThingBackground.pure(folder.getColor())
                )
            }
        }
    }

    override fun getEntry(listPosition: Int): ThingListEntry? {
        return mThingManager!!.getThingListEntry(listPosition)
    }

    override fun getListPositionForStableId(stableId: Long): Int {
        return mThingManager!!.getListPositionForStableId(stableId)
    }

    override fun buildFolderDropCandidate(
        source: ThingListOverlayDragController.DragSource,
        targetListPosition: Int
    ): ThingListOverlayDragController.FolderDropCandidate? {
        val sourceListPosition = mThingManager!!.getListPositionForStableId(source.stableId)
        if (sourceListPosition <= 0 ||
            targetListPosition <= 0 ||
            sourceListPosition == targetListPosition
        ) {
            return null
        }

        val targetEntry = mThingManager!!.getThingListEntry(targetListPosition) ?: return null
        val sourceThing = source.thingId?.let { mThingManager!!.getThingById(it) }
        val sourceFolder = source.folderId?.let { mThingManager!!.getFolderById(it) }
        if (sourceThing != null && targetEntry is ThingListEntry.ThingEntry) {
            val targetThing = targetEntry.thing
            if (canCreateThingFolderWith(sourceThing) &&
                canCreateThingFolderWith(targetThing)
            ) {
                return ThingListOverlayDragController.FolderDropCandidate(
                    ThingListOverlayDragController.FolderDropAction.CREATE,
                    targetListPosition,
                    sourceThing.id,
                    null,
                    targetThing.id,
                    null,
                    ThingBackground.fromRandom()
                )
            }
        }
        if (targetEntry is ThingListEntry.FolderEntry &&
            sourceThing != null &&
            canMoveThingIntoExistingFolderWith(sourceThing, targetEntry)
        ) {
            return ThingListOverlayDragController.FolderDropCandidate(
                ThingListOverlayDragController.FolderDropAction.MOVE_TO_FOLDER,
                targetListPosition,
                sourceThing.id,
                null,
                null,
                targetEntry.folder.id,
                targetEntry.folder.getBackground()
            )
        }
        if (targetEntry is ThingListEntry.FolderEntry &&
            sourceFolder != null &&
            canMoveFolderIntoExistingFolderWith(sourceFolder, targetEntry)
        ) {
            return ThingListOverlayDragController.FolderDropCandidate(
                ThingListOverlayDragController.FolderDropAction.MOVE_TO_FOLDER,
                targetListPosition,
                null,
                sourceFolder.id,
                null,
                targetEntry.folder.id,
                targetEntry.folder.getBackground()
            )
        }
        return null
    }

    override fun showFolderDropHover(
        candidate: ThingListOverlayDragController.FolderDropCandidate
    ) {
        mThingsTouchCallback?.showOverlayFolderDropHover(candidate)
    }

    override fun clearFolderDropHover(animateRestore: Boolean) {
        mThingsTouchCallback?.clearOverlayFolderDropHover(animateRestore)
    }

    override fun commitFolderDrop(
        candidate: ThingListOverlayDragController.FolderDropCandidate
    ): ThingListOverlayDragController.FolderDropCommitResult {
        mThingsTouchCallback?.setOverlayFolderDropCommitInProgress(true)
        mModeManager!!.finishMovingModeWithoutListRefresh()
        val resetCommitState = {
            mThingsTouchCallback?.setOverlayFolderDropCommitInProgress(false)
        }
        if (candidate.action == ThingListOverlayDragController.FolderDropAction.MOVE_TO_FOLDER) {
            val targetFolderId = candidate.targetFolderId
            if (targetFolderId == null) {
                resetCommitState()
                rebindHomeListAfterFolderDropModeExit()
                return ThingListOverlayDragController.FolderDropCommitResult(false)
            }
            var committed: Boolean? = null
            var visualFinished = false
            fun finishIfReady() {
                val result = committed ?: return
                if (!visualFinished) return
                resetCommitState()
                if (result) {
                    rebindHomeListAfterFolderDropModeExit()
                } else {
                    mAdapter!!.notifyDataSetChanged()
                    rebindHomeListAfterFolderDropModeExit()
                }
            }
            val onCommitted: (Boolean) -> Unit = { result ->
                committed = result
                finishIfReady()
            }
            when {
                candidate.sourceThingId != null -> {
                    commitMoveThingIntoFolderDrop(
                        candidate.sourceThingId,
                        targetFolderId,
                        onCommitted
                    )
                }
                candidate.sourceFolderId != null -> {
                    commitMoveFolderIntoFolderDrop(
                        candidate.sourceFolderId,
                        targetFolderId,
                        onCommitted
                    )
                }
                else -> committed = false
            }
            if (committed == false) {
                resetCommitState()
                rebindHomeListAfterFolderDropModeExit()
                return ThingListOverlayDragController.FolderDropCommitResult(false)
            }
            return ThingListOverlayDragController.FolderDropCommitResult(true) {
                visualFinished = true
                finishIfReady()
            }
        }

        val sourceThingId = candidate.sourceThingId
        val targetThingId = candidate.targetThingId
        val createdDrop = if (sourceThingId != null && targetThingId != null) {
            commitCreateThingFolderDrop(
                sourceThingId,
                targetThingId,
                candidate.background ?: ThingBackground.fromRandom()
            )
        } else {
            null
        }
        return if (createdDrop != null) {
            ThingListOverlayDragController.FolderDropCommitResult(true) {
                resetCommitState()
                rebindHomeListAfterFolderDropModeExit()
                showCreateThingFolderNameDialog(createdDrop)
            }
        } else {
            resetCommitState()
            rebindHomeListAfterFolderDropModeExit()
            ThingListOverlayDragController.FolderDropCommitResult(false)
        }
    }

    override fun commitReorder(
        source: ThingListOverlayDragController.DragSource,
        targetStableId: Long,
        insertAfter: Boolean
    ): Boolean {
        val fromListPosition = mThingManager!!.getListPositionForStableId(source.stableId)
        val targetListPosition = mThingManager!!.getListPositionForStableId(targetStableId)
        if (fromListPosition <= 0 || targetListPosition <= 0) return false
        var toListPosition = if (insertAfter) {
            targetListPosition + 1
        } else {
            targetListPosition
        }
        if (fromListPosition < toListPosition) {
            toListPosition -= 1
        }
        val entries = mThingManager!!.getThingListEntries() ?: return false
        toListPosition = toListPosition.coerceIn(1, entries.lastIndex)
        if (fromListPosition == toListPosition) return false
        if (!mThingManager!!.canMoveListEntry(fromListPosition, toListPosition)) return false

        mAdapter!!.setShouldThingsAnimWhenAppearing(false)
        mModeManager!!.finishMovingModeWithoutListRefresh()
        mThingManager!!.move(fromListPosition, toListPosition)
        mAdapter!!.notifyItemMoved(fromListPosition, toListPosition)
        mThingManager!!.updateLocations(fromListPosition, toListPosition)
        rebindHomeListAfterOverlayDragModeExit("overlay-reorder-mode-exit-rebind")
        return true
    }

    override fun enterSelectionMode(
        source: ThingListOverlayDragController.DragSource
    ): Boolean {
        val listPosition = mThingManager!!.getListPositionForStableId(source.stableId)
        if (listPosition <= 0) {
            mModeManager!!.backNormalMode(0)
            return false
        }
        if (mModeManager!!.getCurrentMode() == ModeManager.MOVING) {
            mModeManager!!.toSelectingMode(listPosition)
        }
        mThingManager!!.setListEntrySelected(listPosition, true)
        mAdapter!!.notifyItemChanged(listPosition)
        mModeManager!!.updateSelectedCount()
        mModeManager!!.updateMenuItems()
        return true
    }

    override fun cancelOverlayDrag(source: ThingListOverlayDragController.DragSource) {
        val listPosition = mThingManager!!.getListPositionForStableId(source.stableId)
        mModeManager!!.backNormalMode(if (listPosition > 0) listPosition else 0)
    }

    override fun notifySourcePlaceholderChanged(
        source: ThingListOverlayDragController.DragSource
    ) {
        val listPosition = mThingManager!!.getListPositionForStableId(source.stableId)
        if (listPosition > 0) {
            mAdapter!!.notifyItemChanged(listPosition)
        }
    }

    override fun onOverlayDragActiveChanged(active: Boolean) {
        mOverlayDragActive = active
        if (!active) {
            val pendingReason = mPendingOverlayDragModeExitRebindReason ?: return
            mPendingOverlayDragModeExitRebindReason = null
            runOverlayDragModeExitRebind(pendingReason)
        }
    }

    override fun isOverlayDragSourceStillVisible(
        source: ThingListOverlayDragController.DragSource
    ): Boolean {
        return mThingManager!!.getListPositionForStableId(source.stableId) > 0
    }

    private fun rebindHomeListAfterOverlayDragModeExit(reason: String) {
        val delay = max(
            mRecyclerView!!.itemAnimator?.moveDuration ?: 0L,
            mRecyclerView!!.itemAnimator?.removeDuration ?: 0L
        ) + 80L
        mRecyclerView!!.postDelayed({
            runOverlayDragModeExitRebind(reason)
        }, delay)
    }

    private fun runOverlayDragModeExitRebind(reason: String) {
        if (mOverlayDragActive) {
            mPendingOverlayDragModeExitRebindReason = reason
            return
        }
        runWhenThingListCanUpdate(reason, waitForItemAnimations = true) {
            mAdapter!!.setShouldThingsAnimWhenAppearing(false)
            mAdapter!!.notifyDataSetChanged()
        }
    }

    internal inner class ThingsTouchCallback : ItemTouchHelper.Callback() {

        private var swiped: Boolean = false
        private var moved: Boolean = false
        private var firstMove: Boolean = true
        private var finalFromListPosition: Int = 0
        private var finalToListPosition: Int = 0
        private var pendingFolderDrop: PendingFolderDrop? = null
        private var activeDragViewHolder: RecyclerView.ViewHolder? = null
        private var activeDragStartListPosition: Int = RecyclerView.NO_POSITION
        private var activeDragStableId: Long = Long.MIN_VALUE
        private var preparedFolderDropCommitVisual: FolderDropCommitVisual? = null
        private var folderDropHoverCandidate: FolderDropHoverCandidate? = null
        private var folderDropHoverStartedAt: Long = 0L
        private var folderDropHoverFrameCount: Int = 0
        private var folderDropCommitInProgress: Boolean = false
        private var lastFolderDropSourceLeftInRoot: Float? = null
        private var lastFolderDropSourceTopInRoot: Float? = null
        private var highlightedFolderTargetListPosition: Int = RecyclerView.NO_POSITION
        private var highlightedFolderTargetAction: Int = FOLDER_DROP_ACTION_NONE
        private var highlightedFolderTargetCard: View? = null
        private var highlightedThumbnailFolderTargetContent: View? = null
        private var highlightedThumbnailFolderTargetFolder: ThingFolder? = null
        private val highlightedFolderTargetCards = ArrayList<View>()
        private val highlightedFolderTargetScaleTokens = HashMap<View, Int>()
        private val highlightedSummaryFolderTargets =
            ArrayList<Pair<CardView, ThingFolder>>()
        private val highlightedSummaryFolderTargetAnimators =
            HashMap<CardView, ValueAnimator>()
        private val highlightedSummaryFolderTargetTokens = HashMap<CardView, Int>()
        private val highlightedSummaryFolderCurrentBackgrounds =
            HashMap<CardView, ThingBackground>()
        private val highlightedSummaryFolderTargetContents =
            ArrayList<Pair<View, ThingFolder>>()
        private val highlightedThumbnailFolderTargets =
            ArrayList<Pair<View, ThingFolder>>()
        private var highlightedFolderTargetOutlineDecoration: FolderDropOutlineDecoration? = null
        private var highlightedFolderTargetOutlineAnimator: ValueAnimator? = null
        private val highlightedThumbnailFolderTargetAnimators =
            HashMap<View, ValueAnimator>()
        private val highlightedThumbnailFolderTargetTokens = HashMap<View, Int>()
        private val highlightedThumbnailFolderCurrentStrokeWidths = HashMap<View, Float>()

        fun showOverlayFolderDropHover(
            candidate: ThingListOverlayDragController.FolderDropCandidate
        ) {
            endRecyclerViewItemAnimationsForFolderDrop()
            updateFolderDropTargetHighlight(
                candidate.targetListPosition,
                candidate.background,
                when (candidate.action) {
                    ThingListOverlayDragController.FolderDropAction.CREATE ->
                        FOLDER_DROP_ACTION_CREATE
                    ThingListOverlayDragController.FolderDropAction.MOVE_TO_FOLDER ->
                        FOLDER_DROP_ACTION_MOVE_TO_FOLDER
                }
            )
        }

        fun clearOverlayFolderDropHover(animateRestore: Boolean) {
            updateFolderDropTargetHighlight(
                RecyclerView.NO_POSITION,
                null,
                FOLDER_DROP_ACTION_NONE,
                animateRestore
            )
            resetFolderDropHover()
        }

        fun setOverlayFolderDropCommitInProgress(inProgress: Boolean) {
            folderDropCommitInProgress = inProgress
        }

        override fun getMovementFlags(
            recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
        ): Int {
            if (mIsNewItemShiningBorderActive) {
                return 0
            }
            val dragFlags = 0
            val listPosition = viewHolder.adapterPosition
            val swipeFlags =
                if (!mOverlayDragActive &&
                    mThingManager!!.getThingListEntry(listPosition) is ThingListEntry.ThingEntry
                ) {
                    ItemTouchHelper.START or ItemTouchHelper.END
                } else {
                    0
            }
            return makeMovementFlags(dragFlags, swipeFlags)
        }

        override fun onMove(
            recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            return false
        }

        private fun canCreateFolderDrop(fromListPosition: Int, toListPosition: Int): Boolean {
            if (fromListPosition == toListPosition || fromListPosition <= 0 || toListPosition <= 0) {
                return false
            }
            if (mThingManager!!.getThingListEntry(toListPosition) !is ThingListEntry.ThingEntry) {
                return false
            }
            val source = mThingManager!!.getThingAtListPosition(fromListPosition) ?: return false
            val target = mThingManager!!.getThingAtListPosition(toListPosition) ?: return false
            return canCreateThingFolderWith(source) && canCreateThingFolderWith(target)
        }

        private fun getFolderDropTargetEntry(listPosition: Int): ThingListEntry.FolderEntry? {
            return mThingManager!!.getThingListEntry(listPosition) as? ThingListEntry.FolderEntry
        }

        private fun buildFolderDropHoverCandidate(
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): FolderDropHoverCandidate? {
            val fromListPosition = viewHolder.adapterPosition
            val toListPosition = target.adapterPosition
            if (fromListPosition == RecyclerView.NO_POSITION
                || toListPosition == RecyclerView.NO_POSITION
            ) {
                return null
            }
            val sourceEntry = mThingManager!!.getThingListEntry(fromListPosition) ?: return null
            if (activeDragStableId != Long.MIN_VALUE &&
                sourceEntry.stableId != activeDragStableId
            ) {
                return null
            }
            val sourceThing = (sourceEntry as? ThingListEntry.ThingEntry)?.thing
            val sourceFolder = (sourceEntry as? ThingListEntry.FolderEntry)?.folder
            if (sourceThing != null && canCreateFolderDrop(fromListPosition, toListPosition)) {
                val targetThing = mThingManager!!.getThingAtListPosition(toListPosition)
                    ?: return null
                val background = reusePendingCreateBackground(fromListPosition, toListPosition)
                    ?: reuseHoverCreateBackground(sourceThing.id, targetThing.id)
                    ?: ThingBackground.fromRandom()
                return FolderDropHoverCandidate(
                    FOLDER_DROP_ACTION_CREATE,
                    fromListPosition,
                    toListPosition,
                    sourceThing.id,
                    null,
                    targetThing.id,
                    null,
                    background
                )
            }
            val targetFolderEntry = getFolderDropTargetEntry(toListPosition)
            if (targetFolderEntry != null
                && sourceThing != null
                && canMoveThingIntoFolderDrop(fromListPosition, targetFolderEntry)
            ) {
                return FolderDropHoverCandidate(
                    FOLDER_DROP_ACTION_MOVE_TO_FOLDER,
                    fromListPosition,
                    toListPosition,
                    sourceThing.id,
                    null,
                    null,
                    targetFolderEntry.folder.id,
                    targetFolderEntry.folder.getBackground()
                )
            }
            if (targetFolderEntry != null
                && sourceFolder != null
                && canMoveFolderIntoFolderDrop(sourceFolder, targetFolderEntry)
            ) {
                return FolderDropHoverCandidate(
                    FOLDER_DROP_ACTION_MOVE_TO_FOLDER,
                    fromListPosition,
                    toListPosition,
                    null,
                    sourceFolder.id,
                    null,
                    targetFolderEntry.folder.id,
                    targetFolderEntry.folder.getBackground()
                )
            }
            return null
        }

        private fun canMoveThingIntoFolderDrop(
            fromListPosition: Int,
            targetEntry: ThingListEntry.FolderEntry
        ): Boolean {
            if (fromListPosition <= 0) return false
            val source = mThingManager!!.getThingAtListPosition(fromListPosition) ?: return false
            if (!canMoveThingIntoExistingFolderWith(source)) return false
            if (source.folderId == targetEntry.folder.id) return false
            return true
        }

        private fun canMoveFolderIntoFolderDrop(
            source: ThingFolder,
            targetEntry: ThingListEntry.FolderEntry
        ): Boolean {
            return canMoveFolderIntoExistingFolderWith(source, targetEntry)
        }

        private fun canMoveThingIntoExistingFolderWith(thing: Thing?): Boolean {
            if (thing == null) return false
            if (thing.type !in Thing.NOTE..Thing.GOAL) return false
            return thing.state == Thing.UNDERWAY
        }

        private fun reusePendingCreateBackground(
            fromListPosition: Int,
            toListPosition: Int
        ): ThingBackground? {
            val pending = pendingFolderDrop ?: return null
            if (pending.action != FOLDER_DROP_ACTION_CREATE) return null
            val sourceThingId = mThingManager!!.getThingAtListPosition(fromListPosition)?.id
                ?: return null
            val targetThingId = mThingManager!!.getThingAtListPosition(toListPosition)?.id
                ?: return null
            if (pending.sourceThingId != sourceThingId) return null
            if (pending.targetThingId != targetThingId) return null
            return pending.background
        }

        private fun reuseHoverCreateBackground(
            sourceThingId: Long,
            targetThingId: Long
        ): ThingBackground? {
            val candidate = folderDropHoverCandidate ?: return null
            if (candidate.action != FOLDER_DROP_ACTION_CREATE) return null
            if (candidate.sourceThingId != sourceThingId) return null
            if (candidate.targetThingId != targetThingId) return null
            return candidate.background
        }

        private fun setPendingFolderDrop(candidate: FolderDropHoverCandidate) {
            endRecyclerViewItemAnimationsForFolderDrop()
            pendingFolderDrop = PendingFolderDrop(
                candidate.action,
                candidate.sourceListPosition,
                candidate.targetListPosition,
                candidate.sourceThingId,
                candidate.sourceFolderId,
                candidate.targetThingId,
                candidate.targetFolderId,
                candidate.background
            )
            updateFolderDropTargetHighlight(
                candidate.targetListPosition,
                candidate.background,
                candidate.action
            )
        }

        private fun clearPendingFolderDrop(
            resetHover: Boolean = true,
            animateRestore: Boolean = true
        ) {
            pendingFolderDrop = null
            updateFolderDropTargetHighlight(
                RecyclerView.NO_POSITION,
                null,
                FOLDER_DROP_ACTION_NONE,
                animateRestore
            )
            if (resetHover) {
                resetFolderDropHover()
            }
        }

        private fun resetFolderDropHover() {
            folderDropHoverCandidate = null
            folderDropHoverStartedAt = 0L
            folderDropHoverFrameCount = 0
        }

        private fun isSameFolderDropCandidate(
            current: FolderDropHoverCandidate?,
            candidate: FolderDropHoverCandidate
        ): Boolean {
            if (current == null) return false
            return current.action == candidate.action
                && current.sourceThingId == candidate.sourceThingId
                && current.sourceFolderId == candidate.sourceFolderId
                && current.targetThingId == candidate.targetThingId
                && current.targetFolderId == candidate.targetFolderId
        }

        private fun isPendingFolderDropFor(candidate: FolderDropHoverCandidate): Boolean {
            val pending = pendingFolderDrop ?: return false
            return pending.action == candidate.action
                && pending.sourceThingId == candidate.sourceThingId
                && pending.sourceFolderId == candidate.sourceFolderId
                && pending.targetThingId == candidate.targetThingId
                && pending.targetFolderId == candidate.targetFolderId
        }

        private fun updateLastFolderDropSourcePosition(sourceView: View) {
            val root = findViewById<ViewGroup>(android.R.id.content) ?: return
            val rootLocation = IntArray(2)
            val recyclerLocation = IntArray(2)
            root.getLocationOnScreen(rootLocation)
            mRecyclerView!!.getLocationOnScreen(recyclerLocation)
            lastFolderDropSourceLeftInRoot =
                recyclerLocation[0] + sourceView.left + sourceView.translationX - rootLocation[0]
            lastFolderDropSourceTopInRoot =
                recyclerLocation[1] + sourceView.top + sourceView.translationY - rootLocation[1]
        }

        private fun isDraggedTopLeftInsideTarget(
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val sourceView = viewHolder.itemView
            val targetView = target.itemView
            val sourceLeft = sourceView.left + sourceView.translationX
            val sourceTop = sourceView.top + sourceView.translationY
            val targetLeft = targetView.left + targetView.translationX
            val targetTop = targetView.top + targetView.translationY
            val targetRight = targetView.right + targetView.translationX
            val targetBottom = targetView.bottom + targetView.translationY
            return sourceLeft >= targetLeft
                && sourceLeft < targetRight
                && sourceTop >= targetTop
                && sourceTop < targetBottom
        }

        private fun updateFolderDropTargetHighlight(
            listPosition: Int,
            background: ThingBackground?,
            action: Int,
            animateRestore: Boolean = true
        ) {
            val targetHolder = if (listPosition == RecyclerView.NO_POSITION) {
                null
            } else {
                getFolderDropTargetHolder(listPosition)
            }
            val targetCard = targetHolder?.cv
            if (highlightedFolderTargetListPosition == listPosition
                && highlightedFolderTargetAction == action
                && highlightedFolderTargetCard === targetCard
                && highlightedFolderTargetCards.size == 1
                && highlightedFolderTargetCards[0] === targetCard
            ) {
                return
            }
            setFolderDropTargetHighlighted(
                highlightedFolderTargetListPosition,
                false,
                animateRestore = animateRestore
            )
            highlightedFolderTargetListPosition = listPosition
            highlightedFolderTargetAction = action
            setFolderDropTargetHighlighted(
                highlightedFolderTargetListPosition,
                true,
                background,
                action,
                targetHolder
            )
        }

        private fun setFolderDropTargetHighlighted(
            listPosition: Int,
            highlighted: Boolean,
            background: ThingBackground? = null,
            action: Int = FOLDER_DROP_ACTION_NONE,
            targetHolder: BaseThingsAdapter.BaseThingViewHolder? = null,
            animateRestore: Boolean = true
        ) {
            if (!highlighted) {
                restoreFolderDropHighlightedTargets(animateRestore)
                return
            }
            if (listPosition == RecyclerView.NO_POSITION) return
            val holder = targetHolder ?: getFolderDropTargetHolder(listPosition) ?: return
            val card = holder.cv ?: return
            highlightedFolderTargetCard = card
            trackFolderDropHighlightedCard(card)
            val targetFolderEntry = getFolderDropTargetEntry(listPosition)
            val targetFolderIsThumbnail =
                targetFolderEntry?.folder?.effectiveCardPresentation()?.mode ==
                    ThingFolderCardPresentation.MODE_THUMBNAILS
            val thumbnailFolderDropTarget =
                action == FOLDER_DROP_ACTION_MOVE_TO_FOLDER && targetFolderIsThumbnail
            val summaryFolderDropTarget =
                action == FOLDER_DROP_ACTION_MOVE_TO_FOLDER &&
                    targetFolderEntry != null &&
                    !targetFolderIsThumbnail
            val scale = if (highlighted) {
                if (action == FOLDER_DROP_ACTION_MOVE_TO_FOLDER) {
                    FOLDER_MOVE_TARGET_SCALE
                } else if (action == FOLDER_DROP_ACTION_CREATE) {
                    FOLDER_CREATE_TARGET_SCALE
                } else {
                    1.0f
                }
            } else {
                1.0f
            }
            animateFolderDropCardScale(card, scale)
            if (highlighted && action == FOLDER_DROP_ACTION_CREATE && background != null) {
                showFolderDropTargetOutlineOverlay(card, background, action)
            } else if (highlighted && thumbnailFolderDropTarget) {
                highlightedThumbnailFolderTargetContent = holder.llContent
                highlightedThumbnailFolderTargetFolder = targetFolderEntry.folder
                holder.llContent?.let {
                    trackThumbnailFolderDropHighlightedContent(it, targetFolderEntry.folder)
                    animateFolderDropContentAlpha(it, targetFolderEntry.folder, true)
                }
                animateThumbnailFolderDropOutline(holder, targetFolderEntry.folder, true)
            } else if (highlighted && summaryFolderDropTarget) {
                trackSummaryFolderDropHighlightedCard(card, targetFolderEntry.folder)
                holder.llContent?.let {
                    trackSummaryFolderDropHighlightedContent(it, targetFolderEntry.folder)
                    animateFolderDropContentAlpha(it, targetFolderEntry.folder, true)
                }
                animateSummaryFolderDropBackground(card, targetFolderEntry.folder, true)
            }
        }

        private fun trackFolderDropHighlightedCard(card: View) {
            if (highlightedFolderTargetCards.none { it === card }) {
                highlightedFolderTargetCards.add(card)
            }
        }

        private fun trackThumbnailFolderDropHighlightedContent(
            content: View,
            folder: ThingFolder
        ) {
            if (highlightedThumbnailFolderTargets.none { it.first === content }) {
                highlightedThumbnailFolderTargets.add(content to folder)
            }
        }

        private fun trackSummaryFolderDropHighlightedCard(
            card: CardView,
            folder: ThingFolder
        ) {
            if (highlightedSummaryFolderTargets.none { it.first === card }) {
                highlightedSummaryFolderTargets.add(card to folder)
            }
        }

        private fun trackSummaryFolderDropHighlightedContent(
            content: View,
            folder: ThingFolder
        ) {
            if (highlightedSummaryFolderTargetContents.none { it.first === content }) {
                highlightedSummaryFolderTargetContents.add(content to folder)
            }
        }

        private fun restoreFolderDropHighlightedTargets(animate: Boolean = true) {
            if (animate) {
                clearFolderDropTargetOutlineOverlay()
            } else {
                removeFolderDropTargetOutlineDecorationImmediately()
            }
            for ((card, folder) in highlightedSummaryFolderTargets) {
                if (animate) {
                    animateSummaryFolderDropBackground(card, folder, false)
                } else {
                    resetSummaryFolderDropBackgroundImmediately(card, folder)
                }
            }
            highlightedSummaryFolderTargets.clear()
            for ((content, folder) in highlightedSummaryFolderTargetContents) {
                if (animate) {
                    animateFolderDropContentAlpha(content, folder, false)
                } else {
                    resetFolderDropContentAlphaImmediately(content, folder)
                }
            }
            highlightedSummaryFolderTargetContents.clear()
            for ((content, folder) in highlightedThumbnailFolderTargets) {
                if (animate) {
                    animateFolderDropContentAlpha(content, folder, false)
                    animateThumbnailFolderDropOutline(content, folder, false)
                } else {
                    resetFolderDropContentAlphaImmediately(content, folder)
                    resetThumbnailFolderDropOutlineImmediately(content, folder)
                }
            }
            highlightedThumbnailFolderTargets.clear()
            highlightedThumbnailFolderTargetContent = null
            highlightedThumbnailFolderTargetFolder = null

            for (card in highlightedFolderTargetCards) {
                if (animate) {
                    animateFolderDropCardScaleToNormal(card)
                } else {
                    resetFolderDropCardScaleImmediately(card)
                }
            }
            highlightedFolderTargetCards.clear()
            highlightedFolderTargetCard = null
            highlightedFolderTargetAction = FOLDER_DROP_ACTION_NONE
        }

        private fun animateSummaryFolderDropBackground(
            card: CardView,
            folder: ThingFolder,
            highlighted: Boolean
        ) {
            val targetBackground = getSummaryFolderDropBackground(folder, highlighted)
            val startBackground = highlightedSummaryFolderCurrentBackgrounds[card]
                ?: getSummaryFolderDropBackground(folder, !highlighted)
            val token = nextSummaryFolderDropBackgroundToken(card)
            highlightedSummaryFolderTargetAnimators.remove(card)?.cancel()
            val evaluator = ArgbEvaluator()
            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = FOLDER_DROP_TARGET_ANIM_DURATION
                addUpdateListener {
                    val fraction = it.animatedValue as Float
                    val frameBackground = interpolateFolderDropBackground(
                        startBackground,
                        targetBackground,
                        fraction,
                        evaluator
                    )
                    highlightedSummaryFolderCurrentBackgrounds[card] = frameBackground
                    BackgroundUtil.applyCardBackground(card, frameBackground)
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        syncSummaryFolderDropBackground(
                            card,
                            targetBackground,
                            token,
                            highlighted
                        )
                    }
                })
            }
            highlightedSummaryFolderTargetAnimators[card] = animator
            animator.start()
        }

        private fun resetSummaryFolderDropBackgroundImmediately(
            card: CardView,
            folder: ThingFolder
        ) {
            val targetBackground = getSummaryFolderDropBackground(folder, false)
            val token = nextSummaryFolderDropBackgroundToken(card)
            highlightedSummaryFolderTargetAnimators.remove(card)?.cancel()
            syncSummaryFolderDropBackground(card, targetBackground, token, false)
        }

        private fun getSummaryFolderDropBackground(
            folder: ThingFolder,
            highlighted: Boolean
        ): ThingBackground {
            val background = folder.getBackground() ?: ThingBackground.pure(folder.getColor())
            if (highlighted || folderDropCommitInProgress) return background
            val currentMode = mModeManager!!.getCurrentMode()
            val dimUnselected = (
                currentMode == ModeManager.SELECTING ||
                    currentMode == ModeManager.MOVING
                ) && !folder.isSelected()
            return if (dimUnselected) lightVariant(background) else background
        }

        private fun interpolateFolderDropBackground(
            from: ThingBackground,
            to: ThingBackground,
            fraction: Float,
            evaluator: ArgbEvaluator
        ): ThingBackground {
            val startColor = evaluator.evaluate(fraction, from.color, to.color) as Int
            val endColor = evaluator.evaluate(fraction, from.endColor, to.endColor) as Int
            return if (startColor == endColor) {
                ThingBackground.pure(startColor)
            } else {
                ThingBackground.gradient(
                    startColor,
                    endColor,
                    if (fraction < 0.5f) from.orientation else to.orientation
                )
            }
        }

        private fun lightVariant(bg: ThingBackground): ThingBackground {
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

        private fun nextSummaryFolderDropBackgroundToken(card: CardView): Int {
            val token = (highlightedSummaryFolderTargetTokens[card] ?: 0) + 1
            highlightedSummaryFolderTargetTokens[card] = token
            return token
        }

        private fun syncSummaryFolderDropBackground(
            card: CardView,
            background: ThingBackground,
            token: Int,
            highlighted: Boolean
        ) {
            if (highlightedSummaryFolderTargetTokens[card] != token) return
            BackgroundUtil.applyCardBackground(card, background)
            highlightedSummaryFolderCurrentBackgrounds[card] = background
            highlightedSummaryFolderTargetAnimators.remove(card)
            if (!highlighted) {
                highlightedSummaryFolderTargetTokens.remove(card)
                highlightedSummaryFolderCurrentBackgrounds.remove(card)
            }
        }

        private fun endRecyclerViewItemAnimationsForFolderDrop() {
            mRecyclerView?.itemAnimator?.endAnimations()
        }

        private fun animateFolderDropCardScaleToNormal(card: View) {
            animateFolderDropCardScale(card, 1.0f)
        }

        private fun animateFolderDropCardScale(card: View, targetScale: Float) {
            val token = nextFolderDropCardScaleToken(card)
            card.animate().cancel()
            card.animate()
                .setListener(null)
                .withEndAction(null)
                .scaleX(targetScale)
                .scaleY(targetScale)
                .setDuration(FOLDER_DROP_TARGET_ANIM_DURATION)
                .withEndAction {
                    syncFolderDropCardScale(card, targetScale, token)
                }
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationCancel(animation: Animator) {
                        syncFolderDropCardScale(card, targetScale, token)
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        syncFolderDropCardScale(card, targetScale, token)
                    }
                })
                .start()
        }

        private fun nextFolderDropCardScaleToken(card: View): Int {
            val token = (highlightedFolderTargetScaleTokens[card] ?: 0) + 1
            highlightedFolderTargetScaleTokens[card] = token
            return token
        }

        private fun syncFolderDropCardScale(
            card: View,
            targetScale: Float,
            token: Int
        ) {
            if (highlightedFolderTargetScaleTokens[card] != token) return
            card.scaleX = targetScale
            card.scaleY = targetScale
            card.animate().setListener(null)
            card.animate().withEndAction(null)
            if (targetScale == 1.0f) {
                highlightedFolderTargetScaleTokens.remove(card)
            }
        }

        private fun resetFolderDropCardScaleImmediately(card: View) {
            val token = nextFolderDropCardScaleToken(card)
            card.animate().cancel()
            syncFolderDropCardScale(card, 1.0f, token)
        }

        private fun getFolderDropTargetHolder(
            listPosition: Int
        ): BaseThingsAdapter.BaseThingViewHolder? {
            return mRecyclerView!!.findViewHolderForAdapterPosition(listPosition)
                as? BaseThingsAdapter.BaseThingViewHolder
        }

        private fun showFolderDropTargetOutlineOverlay(
            targetCard: View,
            background: ThingBackground,
            action: Int
        ) {
            removeFolderDropTargetOutlineDecorationImmediately()
            if (targetCard.width <= 0 || targetCard.height <= 0) return
            val outline = FolderDropOutlineDrawable(
                background,
                resources.getDimension(R.dimen.thing_card_corner_radius),
                if (action == FOLDER_DROP_ACTION_MOVE_TO_FOLDER) {
                    resources.displayMetrics.density * 3.0f
                } else {
                    resources.displayMetrics.density * 2.0f
                }
            )
            val gap = resources.displayMetrics.density * FOLDER_CREATE_OUTLINE_GAP_DP
            val targetScale = if (action == FOLDER_DROP_ACTION_CREATE) {
                FOLDER_CREATE_TARGET_SCALE
            } else {
                FOLDER_MOVE_TARGET_SCALE
            }
            val decoration = FolderDropOutlineDecoration(
                outline,
                targetCard,
                targetScale,
                gap
            )
            mRecyclerView!!.addItemDecoration(decoration)
            highlightedFolderTargetOutlineDecoration = decoration
            highlightedFolderTargetOutlineAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = FOLDER_DROP_TARGET_ANIM_DURATION
                addUpdateListener {
                    decoration.progress = it.animatedValue as Float
                    mRecyclerView!!.invalidate()
                }
                start()
            }
        }

        private fun animateThumbnailFolderDropOutline(
            holder: BaseThingsAdapter.BaseThingViewHolder,
            folder: ThingFolder,
            highlighted: Boolean
        ) {
            val content = holder.llContent ?: return
            animateThumbnailFolderDropOutline(content, folder, highlighted)
        }

        private fun animateFolderDropContentAlpha(
            content: View,
            folder: ThingFolder,
            highlighted: Boolean
        ) {
            content.animate().cancel()
            content.animate()
                .setListener(null)
                .withEndAction(null)
                .alpha(getFolderDropContentAlpha(folder, highlighted))
                .setDuration(FOLDER_DROP_TARGET_ANIM_DURATION)
                .start()
        }

        private fun resetFolderDropContentAlphaImmediately(
            content: View,
            folder: ThingFolder
        ) {
            content.animate().cancel()
            content.animate().setListener(null)
            content.animate().withEndAction(null)
            content.alpha = getFolderDropContentAlpha(folder, false)
        }

        private fun getFolderDropContentAlpha(
            folder: ThingFolder,
            highlighted: Boolean
        ): Float {
            if (highlighted || folderDropCommitInProgress) return 1.0f
            val currentMode = mModeManager!!.getCurrentMode()
            val dimUnselected = (
                currentMode == ModeManager.SELECTING ||
                    currentMode == ModeManager.MOVING
                ) && !folder.isSelected()
            return if (dimUnselected) 0.42f else 1.0f
        }

        private fun animateThumbnailFolderDropOutline(
            content: View,
            folder: ThingFolder,
            highlighted: Boolean
        ) {
            val density = resources.displayMetrics.density
            val normalStrokeWidth = (density * 1.5f).coerceAtLeast(1f)
            val highlightedStrokeWidth = (density * 3.2f).coerceAtLeast(normalStrokeWidth)
            val strokeBackground = folder.getBackground() ?: ThingBackground.pure(folder.getColor())
            val targetStrokeWidth = if (highlighted) {
                highlightedStrokeWidth
            } else {
                normalStrokeWidth
            }
            val startStrokeWidth = highlightedThumbnailFolderCurrentStrokeWidths[content]
                ?: if (highlighted) normalStrokeWidth else highlightedStrokeWidth
            val token = nextThumbnailFolderDropOutlineToken(content)
            highlightedThumbnailFolderTargetAnimators.remove(content)?.cancel()
            val outline = BackgroundUtil.GradientStrokeDrawable(
                strokeBackground,
                resources.getDimension(R.dimen.thing_card_corner_radius),
                startStrokeWidth.coerceAtLeast(1f)
            )
            content.background = outline
            val animator = ValueAnimator.ofFloat(
                startStrokeWidth,
                targetStrokeWidth
            ).apply {
                duration = FOLDER_DROP_TARGET_ANIM_DURATION
                addUpdateListener {
                    val width = it.animatedValue as Float
                    highlightedThumbnailFolderCurrentStrokeWidths[content] = width
                    outline.strokeWidthPx = width.coerceAtLeast(1f)
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        syncThumbnailFolderDropOutline(
                            content,
                            outline,
                            targetStrokeWidth,
                            token,
                            highlighted
                        )
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        syncThumbnailFolderDropOutline(
                            content,
                            outline,
                            targetStrokeWidth,
                            token,
                            highlighted
                        )
                    }
                })
            }
            highlightedThumbnailFolderTargetAnimators[content] = animator
            animator.start()
        }

        private fun nextThumbnailFolderDropOutlineToken(content: View): Int {
            val token = (highlightedThumbnailFolderTargetTokens[content] ?: 0) + 1
            highlightedThumbnailFolderTargetTokens[content] = token
            return token
        }

        private fun syncThumbnailFolderDropOutline(
            content: View,
            outline: BackgroundUtil.GradientStrokeDrawable,
            strokeWidth: Float,
            token: Int,
            highlighted: Boolean
        ) {
            if (highlightedThumbnailFolderTargetTokens[content] != token) return
            outline.strokeWidthPx = strokeWidth.coerceAtLeast(1f)
            highlightedThumbnailFolderCurrentStrokeWidths[content] = strokeWidth
            highlightedThumbnailFolderTargetAnimators.remove(content)
            if (!highlighted) {
                highlightedThumbnailFolderTargetTokens.remove(content)
                highlightedThumbnailFolderCurrentStrokeWidths.remove(content)
            }
        }

        private fun resetThumbnailFolderDropOutlineImmediately(
            content: View,
            folder: ThingFolder
        ) {
            val density = resources.displayMetrics.density
            val strokeWidth = (density * 1.5f).coerceAtLeast(1f)
            val strokeBackground = folder.getBackground() ?: ThingBackground.pure(folder.getColor())
            val token = nextThumbnailFolderDropOutlineToken(content)
            highlightedThumbnailFolderTargetAnimators.remove(content)?.cancel()
            val outline = BackgroundUtil.GradientStrokeDrawable(
                strokeBackground,
                resources.getDimension(R.dimen.thing_card_corner_radius),
                strokeWidth
            )
            content.background = outline
            syncThumbnailFolderDropOutline(
                content,
                outline,
                strokeWidth,
                token,
                false
            )
        }

        private fun clearFolderDropTargetOutlineOverlay() {
            val decoration = highlightedFolderTargetOutlineDecoration ?: return
            val recyclerView = mRecyclerView ?: return
            val startProgress = decoration.progress
            highlightedFolderTargetOutlineDecoration = null
            highlightedFolderTargetOutlineAnimator?.cancel()
            highlightedFolderTargetOutlineAnimator = null
            if (startProgress <= 0f) {
                removeFolderDropTargetOutlineDecoration(decoration)
                return
            }
            ValueAnimator.ofFloat(
                startProgress,
                0f
            ).apply {
                duration = FOLDER_DROP_TARGET_ANIM_DURATION
                var removed = false
                fun removeOnce() {
                    if (removed) return
                    removed = true
                    removeFolderDropTargetOutlineDecoration(decoration)
                }
                addUpdateListener {
                    decoration.progress = it.animatedValue as Float
                    recyclerView.invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        removeOnce()
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        removeOnce()
                    }
                })
                start()
            }
        }

        private fun removeFolderDropTargetOutlineDecorationImmediately() {
            highlightedFolderTargetOutlineAnimator?.cancel()
            highlightedFolderTargetOutlineAnimator = null
            val decoration = highlightedFolderTargetOutlineDecoration ?: return
            highlightedFolderTargetOutlineDecoration = null
            removeFolderDropTargetOutlineDecoration(decoration)
        }

        private fun removeFolderDropTargetOutlineDecoration(
            decoration: FolderDropOutlineDecoration
        ) {
            val recyclerView = mRecyclerView ?: return
            recyclerView.post {
                recyclerView.removeItemDecoration(decoration)
                recyclerView.invalidate()
            }
        }

        private fun canMove(fromListPosition: Int, toListPosition: Int): Boolean {
            return mThingManager!!.canMoveListEntry(fromListPosition, toListPosition)
        }

        override fun isLongPressDragEnabled(): Boolean = false

        override fun isItemViewSwipeEnabled(): Boolean {
            return mApp!!.getStatus() == Def.ThingStatus.UNDERWAY
                && mModeManager!!.getCurrentMode() != ModeManager.SELECTING
                && !mOverlayDragActive
                && !mThingManager!!.isThingsEmpty()
                && !mIsNewItemShiningBorderActive
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val listPosition = viewHolder.adapterPosition
            if (listPosition <= 0) {
                return
            }
            if (mThingManager!!.getThingListEntry(listPosition) !is ThingListEntry.ThingEntry) {
                mAdapter!!.notifyItemChanged(listPosition)
                return
            }

            val thingIndex = mThingManager!!.getThingIndexForListPosition(listPosition)
            if (thingIndex <= 0) {
                return
            }

            val thingToSwipe: Thing = mThingManager!!.getThingAtListPosition(listPosition) ?: return
            val id = thingToSwipe.id
            @Thing.Type val thingType = thingToSwipe.type
            if (thingType > Thing.NOTIFICATION_GOAL) {
                // without this line, the item will disappear and leave a white space...
                mAdapter!!.notifyItemChanged(listPosition)
                return
            }

            if (needsThingSwipePrivacyAuthentication(thingToSwipe)) {
                mAdapter!!.notifyItemChanged(listPosition)
                authenticateThingSwipe(thingToSwipe, direction) {
                    performThingSwipe(thingToSwipe, thingIndex, listPosition, direction)
                    swiped = true
                }
                return
            }

            performThingSwipe(thingToSwipe, thingIndex, listPosition, direction)
            swiped = true
        }

        private fun needsThingSwipePrivacyAuthentication(thing: Thing): Boolean {
            return thing.isPrivate() || mThingManager!!.isCurrentFolderEffectivelyPrivate()
        }

        private fun authenticateThingSwipe(
            thing: Thing,
            direction: Int,
            onAuthenticated: () -> Unit
        ) {
            val cp: String? = getSharedPreferences(Def.Meta.PREFERENCES_NAME, MODE_PRIVATE)
                .getString(Def.Meta.KEY_PRIVATE_PASSWORD, null)
            val title = if (direction == ItemTouchHelper.START) {
                getString(R.string.finish_private_thing_by_swipe)
            } else {
                getString(R.string.start_doing_full_title)
            }
            AuthenticationHelper.authenticate(
                this@ThingsActivity,
                thing.getBackground(),
                title,
                cp,
                object : AuthenticationHelper.AuthenticationCallback {
                    override fun onAuthenticated() {
                        onAuthenticated()
                    }

                    override fun onCancel() {}
                }
            )
        }

        private fun performThingSwipe(
            thingToSwipe: Thing,
            thingIndex: Int,
            listPosition: Int,
            direction: Int
        ) {
            val id = thingToSwipe.id
            @Thing.Type val thingType = thingToSwipe.type
            prepareBeforeSwipingThing(id, thingType)

            if (direction == ItemTouchHelper.START) {
                if (App.getDoingThingId() == id) {
                    DoingService.sSendBroadcastToUpdateMainUi = false
                }
                if (thingType == Thing.HABIT) {
                    tryToFinishHabitOnceBySwiping(thingToSwipe, thingIndex, listPosition)
                } else {
                    tryToFinishOtherBySwiping(thingToSwipe, thingIndex, listPosition)
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
                mAdapter!!.notifyItemChanged(listPosition)
                if (App.getDoingThingId() == thingToSwipe.id) {
                    Toast.makeText(
                        this@ThingsActivity, R.string.start_doing_doing_this_thing,
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    if (thingToSwipe.isPrivate()) {
                        val helper = ThingDoingHelper(this@ThingsActivity, thingToSwipe)
                        helper.tryToOpenStartDoingActivityUser(thingToSwipe.getBackground())
                    } else {
                        val helper = ThingDoingHelper(this@ThingsActivity, thingToSwipe)
                        helper.tryToOpenStartDoingActivityUser()
                    }
                }
            }
        }

        private fun commitFolderDropAfterClear(
            folderDrop: PendingFolderDrop,
            commitVisual: FolderDropCommitVisual?
        ) {
            if (folderDrop.action == FOLDER_DROP_ACTION_MOVE_TO_FOLDER) {
                val targetFolderId = folderDrop.targetFolderId
                val onCommitted: (Boolean) -> Unit = { committed ->
                    if (committed) {
                        restoreFolderDropSourceViewLater(commitVisual)
                        playFolderDropCommitVisual(commitVisual) {
                            rebindHomeListAfterFolderDropModeExit()
                        }
                    } else {
                        restoreFolderDropVisualImmediately(commitVisual)
                        rebindHomeListAfterFolderDropModeExit()
                    }
                }
                if (targetFolderId == null) {
                    restoreFolderDropVisualImmediately(commitVisual)
                    rebindHomeListAfterFolderDropModeExit()
                } else if (folderDrop.sourceThingId != null) {
                    commitMoveThingIntoFolderDrop(
                        folderDrop.sourceThingId,
                        targetFolderId,
                        onCommitted
                    )
                } else if (folderDrop.sourceFolderId != null) {
                    commitMoveFolderIntoFolderDrop(
                        folderDrop.sourceFolderId,
                        targetFolderId,
                        onCommitted
                    )
                } else {
                    restoreFolderDropVisualImmediately(commitVisual)
                    rebindHomeListAfterFolderDropModeExit()
                }
            } else {
                val targetThingId = folderDrop.targetThingId
                val sourceThingId = folderDrop.sourceThingId
                val createdDrop = if (sourceThingId != null && targetThingId != null) {
                    commitCreateThingFolderDrop(
                        sourceThingId,
                        targetThingId,
                        folderDrop.background ?: ThingBackground.fromRandom()
                    )
                } else {
                    null
                }
                if (createdDrop != null) {
                    restoreFolderDropSourceViewLater(commitVisual)
                    playFolderDropCommitVisual(commitVisual) {
                        rebindHomeListAfterFolderDropModeExit()
                        showCreateThingFolderNameDialog(createdDrop)
                    }
                } else {
                    restoreFolderDropVisualImmediately(commitVisual)
                    rebindHomeListAfterFolderDropModeExit()
                }
            }
        }

        private fun restoreClearedDragCardScale(view: View) {
            view.animate().cancel()
            if (!view.isAttachedToWindow) {
                view.scaleX = 1.0f
                view.scaleY = 1.0f
                return
            }
            if (view.scaleX == 1.0f && view.scaleY == 1.0f) return
            view.animate()
                .setListener(null)
                .withEndAction(null)
                .scaleX(1.0f)
                .scaleY(1.0f)
                .setDuration(CLEARED_DRAG_SCALE_RECOVERY_DURATION)
                .withEndAction {
                    view.scaleX = 1.0f
                    view.scaleY = 1.0f
                }
                .start()
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            viewHolder.itemView.setTag(R.id.tag_thing_card_finger_down, false)
            viewHolder.itemView.setTag(R.id.tag_thing_card_drag_active, false)
            restoreClearedDragCardScale(viewHolder.itemView)
            clearActiveTouchItemZ(viewHolder.itemView)
            viewHolder.itemView.alpha = 1.0f
            preparedFolderDropCommitVisual = null
            activeDragViewHolder = null
            lastFolderDropSourceLeftInRoot = null
            lastFolderDropSourceTopInRoot = null
            folderDropCommitInProgress = false
            moved = false
            firstMove = true
            swiped = false
            hasSwipedRight = false
            activeDragStartListPosition = RecyclerView.NO_POSITION
            activeDragStableId = Long.MIN_VALUE
        }

        var hasSwipedRight: Boolean = false

        override fun onChildDraw(
            c: Canvas, recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder, dX: Float, dY: Float,
            actionState: Int, isCurrentlyActive: Boolean
        ) {
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                keepActiveTouchItemAboveSiblings(recyclerView, viewHolder.itemView)
            }
            if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                val displayWidth = DisplayUtil.getDisplaySize(mApp).x
                val v: View = viewHolder.itemView
                val holder = viewHolder as BaseThingsAdapter.BaseThingViewHolder
                val listPosition = viewHolder.adapterPosition
                val thing: Thing = mThingManager!!.getThingAtListPosition(listPosition) ?: return
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
                        // 铺满由 InterceptTouchCardView.onMeasure 保证；这里触发一次重测并按当前
                        // 高度算图标 / 文字缩放。
                        holder.flDoing.requestLayout()
                        holder.applyDoingCoverScale()
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

        private fun keepActiveTouchItemAboveSiblings(
            recyclerView: RecyclerView,
            activeView: View
        ) {
            val zOffset = resources.displayMetrics.density * ACTIVE_TOUCH_ITEM_Z_OFFSET_DP
            var maxSiblingZ = 0.0f
            for (i in 0 until recyclerView.childCount) {
                val child = recyclerView.getChildAt(i)
                if (child === activeView) continue
                maxSiblingZ = max(maxSiblingZ, child.z)
            }
            val targetTranslationZ = (maxSiblingZ + zOffset - activeView.elevation)
                .coerceAtLeast(0.0f)
            if (abs(activeView.translationZ - targetTranslationZ) > 0.5f) {
                activeView.translationZ = targetTranslationZ
            }
        }

        private fun clearActiveTouchItemZ(activeView: View) {
            activeView.translationZ = 0.0f
        }

        private fun updatePendingFolderDropFromDraggedTopLeft(
            viewHolder: RecyclerView.ViewHolder
        ) {
            val target = findFolderDropTargetUnderDraggedTopLeft(viewHolder)
            if (target == null) {
                clearPendingFolderDrop()
                return
            }
            updateFolderDropHoverCandidate(
                buildFolderDropHoverCandidate(viewHolder, target)
            )
        }

        private fun updateFolderDropHoverCandidate(
            candidate: FolderDropHoverCandidate?
        ) {
            if (candidate == null) {
                clearPendingFolderDrop()
                return
            }
            val now = SystemClock.uptimeMillis()
            if (mRecyclerView!!.isComputingLayout
                || mRecyclerView!!.itemAnimator?.isRunning == true
            ) {
                if (pendingFolderDrop != null && isPendingFolderDropFor(candidate)
                    && !mRecyclerView!!.isComputingLayout
                ) {
                    endRecyclerViewItemAnimationsForFolderDrop()
                    return
                }
                if (!isSameFolderDropCandidate(folderDropHoverCandidate, candidate)) {
                    folderDropHoverCandidate = candidate
                }
                folderDropHoverStartedAt = now
                folderDropHoverFrameCount = 0
                clearPendingFolderDrop(resetHover = false)
                return
            }
            if (!isSameFolderDropCandidate(folderDropHoverCandidate, candidate)) {
                folderDropHoverCandidate = candidate
                folderDropHoverStartedAt = now
                folderDropHoverFrameCount = 1
                clearPendingFolderDrop(resetHover = false)
                return
            }
            folderDropHoverFrameCount += 1
            val hoverDuration = now - folderDropHoverStartedAt
            if (hoverDuration < FOLDER_DROP_HOVER_ARM_DELAY_MS
                || folderDropHoverFrameCount < FOLDER_DROP_HOVER_ARM_MIN_FRAMES
            ) {
                if (pendingFolderDrop != null && !isPendingFolderDropFor(candidate)) {
                    clearPendingFolderDrop(resetHover = false)
                }
                return
            }
            if (!isPendingFolderDropFor(candidate)) {
                setPendingFolderDrop(candidate)
            } else if (pendingFolderDrop?.targetListPosition != candidate.targetListPosition) {
                setPendingFolderDrop(candidate)
            }
        }

        private fun findFolderDropTargetUnderDraggedTopLeft(
            viewHolder: RecyclerView.ViewHolder
        ): RecyclerView.ViewHolder? {
            val source = viewHolder.itemView
            val x = getScaledViewLeftInRecycler(source)
            val y = getScaledViewTopInRecycler(source)
            val minPenetration = this@ThingsActivity.getFolderDropTargetInsetPx()
            for (i in 0 until mRecyclerView!!.childCount) {
                val child = mRecyclerView!!.getChildAt(i)
                if (child === source) continue
                val holder = mRecyclerView!!.getChildViewHolder(child)
                if (holder.adapterPosition == RecyclerView.NO_POSITION) continue
                val childLeft = child.left + child.translationX
                val childTop = child.top + child.translationY
                val childRight = child.right + child.translationX
                val childBottom = child.bottom + child.translationY
                val horizontalInset = min(
                    minPenetration,
                    ((childRight - childLeft) - 1f) / 2f
                ).coerceAtLeast(0f)
                val verticalInset = min(
                    minPenetration,
                    ((childBottom - childTop) - 1f) / 2f
                ).coerceAtLeast(0f)
                if (x >= childLeft + horizontalInset
                    && x < childRight - horizontalInset
                    && y >= childTop + verticalInset
                    && y < childBottom - verticalInset
                ) {
                    return holder
                }
            }
            return null
        }

        private fun getScaledViewLeftInRecycler(view: View): Float {
            return view.left + view.translationX + view.pivotX * (1f - view.scaleX)
        }

        private fun getScaledViewTopInRecycler(view: View): Float {
            return view.top + view.translationY + view.pivotY * (1f - view.scaleY)
        }
    }

    private fun prepareBeforeSwipingThing(id: Long, @Thing.Type thingType: Int) {
        if (mNormalSnackbar!!.isShowing()) {
            mNormalSnackbar!!.dismiss()
        }

        SystemNotificationUtil.cancelNotification(id, thingType, mApp)
        mThingsIdsToUpdateWidget!!.add(id)
    }

    private fun tryToFinishHabitOnceBySwiping(
        thingToSwipe: Thing,
        thingIndex: Int,
        listPosition: Int
    ) {
        val id = thingToSwipe.id
        if (App.getDoingThingId() == id) {
            DoingService.sStopReason = DoingRecord.STOP_REASON_FINISH
        }

        @Thing.Type val thingType = thingToSwipe.type
        val habitDAO: HabitDAO = HabitDAO.getInstance(mApp)!!
        val habit: Habit? = habitDAO.getHabitById(id)
        if (habit != null) {
            if (habit.allowFinish()) {
                val thingContent: String = thingToSwipe.content!!
                if (CheckListHelper.isCheckListStr(thingContent)) {
                    val thing = Thing(thingToSwipe)
                    thing.content = thingContent.replace(
                        (CheckListHelper.SIGNAL + "1").toRegex(),
                        CheckListHelper.SIGNAL + "0"
                    )
                    mThingManager!!.update(thingType, thing, thingIndex, false)
                    mThingManager!!.getThings()!![thingIndex] = thing
                }
                habitDAO.finishOneTime(habit)
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

        mAdapter!!.notifyItemChanged(listPosition)
        mDrawerHeader!!.updateCompletionRate()
    }

    private fun tryToFinishOtherBySwiping(
        thingToSwipe: Thing,
        thingIndex: Int,
        listPosition: Int
    ) {
        @Thing.State val state = thingToSwipe.state
        val location = thingToSwipe.location

        if (App.getDoingThingId() == thingToSwipe.id) {
            DoingService.sStopReason = DoingRecord.STOP_REASON_FINISH
            val justFinishIntent2 = Intent(DoingActivity.BROADCAST_ACTION_JUST_FINISH)
            justFinishIntent2.setPackage(packageName)
            sendBroadcast(justFinishIntent2)
            stopService(Intent(this@ThingsActivity, DoingService::class.java))
            App.setDoingThingId(-1L)
        }

        val changed = mThingManager!!.updateState(
            thingToSwipe, thingIndex, location,
            state, Thing.FINISHED, false, true
        )
        mScrollCausedByFinger = false

        if (App.isSearching) {
            if (isSearchResultEmpty()) {
                handleSearchResults()
            }
        }

        celebrateHabitGoalFinish(thingToSwipe, state, Thing.FINISHED)
        if (changed) {
            mAdapter!!.notifyItemChanged(listPosition)
            updateUIAfterStateUpdated(
                Thing.FINISHED,
                mRecyclerView!!.itemAnimator!!.changeDuration, true
            )
        } else {
            mAdapter!!.notifyItemRemoved(listPosition)
            updateUIAfterStateUpdated(
                Thing.FINISHED,
                mRecyclerView!!.itemAnimator!!.removeDuration, true
            )
        }
    }

    private fun getSingleSelectedFolder(): ThingFolder? {
        return (mThingManager!!.getSingleSelectedEntry()
                as? ThingListEntry.FolderEntry)?.folder
    }

    /**
     * Unified "move to folder" for the current selection. Things-only and
     * single-folder selections reuse the existing dialogs; multi-folder or mixed
     * selections open one dialog whose forbidden targets are the union of every
     * selected Folder and its descendants, then move Things and Folders together.
     */
    private fun confirmMoveSelected() {
        val selectedThings = mThingManager!!.getSelectedThings()?.filterNotNull()
            ?.filter { it.type in Thing.NOTE..Thing.GOAL && it.state == Thing.UNDERWAY } ?: emptyList()
        val selectedFolders = mThingManager!!.getSelectedFolders().toList()
        if (selectedThings.isEmpty() && selectedFolders.isEmpty()) return
        if (selectedFolders.isEmpty()) {
            showMoveSelectedThingsDialog()
            return
        }
        if (selectedThings.isEmpty() && selectedFolders.size == 1) {
            showMoveThingFolderDialog(selectedFolders.first())
            return
        }

        val forbidden = HashSet<Long>()
        for (folder in selectedFolders) {
            forbidden.addAll(getForbiddenFolderMoveTargetIds(folder))
        }
        val dialog = MoveToThingFolderDialogFragment()
        // 多选 / 混合，用当前文件夹色（根目录 accent 渐变）。
        dialog.setAccentBackground(selectionDialogBackground())
        dialog.setHasAnyFolder(mThingManager!!.hasAnyFolder())
        dialog.setFolders(
            mThingManager!!.getDrawerFolders(),
            getCommonSelectedMoveSourceFolderId(selectedThings, selectedFolders),
            forbidden
        )
        dialog.setListener(object : MoveToThingFolderDialogFragment.Listener {
            override fun onMoveTargetConfirmed(targetFolderId: Long?) {
                moveSelectedMixedToFolder(selectedThings, selectedFolders, targetFolderId)
            }

            override fun shouldAuthenticateBeforeExpand(folder: ThingFolder): Boolean {
                return shouldAuthenticateTransientPrivateFolderExpansion(folder)
            }

            override fun onAuthenticateFolderExpand(
                folder: ThingFolder,
                onAuthenticated: () -> Unit
            ) {
                authenticateThingFolder(folder, R.string.expand_private_thing_folder) {
                    onAuthenticated()
                }
            }
        })
        dialog.show(fragmentManager, MoveToThingFolderDialogFragment.TAG)
    }

    private fun moveSelectedMixedToFolder(
        selectedThings: List<Thing>,
        selectedFolders: List<ThingFolder>,
        targetFolderId: Long?
    ) {
        val needAuth = needsSelectedThingsMovePrivacyAuthentication(selectedThings, targetFolderId) ||
            selectedFolders.any { needsFolderMovePrivacyAuthentication(it, targetFolderId) }
        authenticatePrivateMoveIfNeeded(needAuth, App.defaultAccentBackground) {
            val moveSources = ArrayList<FolderMenuMoveSource>()
            moveSources.addAll(
                selectedThings
                    .filter { it.folderId != targetFolderId }
                    .map { folderMenuMoveSource(it) }
            )
            moveSources.addAll(
                selectedFolders
                    .filter { it.parentFolderId != targetFolderId }
                    .map { folderMenuMoveSource(it) }
            )
            moveEntriesToFolderWithVisualUpdate(
                moveSources,
                targetFolderId
            ) {
                var changed = mThingManager!!.moveSelectedThingsIntoFolder(
                    targetFolderId,
                    reload = false
                )
                for (folder in selectedFolders) {
                    if (folder.parentFolderId != targetFolderId) {
                        if (mThingManager!!.moveFolderIntoFolder(
                                folder,
                                targetFolderId,
                                reload = false
                            )
                        ) {
                            changed = true
                        }
                    }
                }
                changed
            }
        }
    }

    /** Batch sticky for the current selection: set all sticky unless all already are. */
    private fun toggleSelectedStickyBatch() {
        val thingIds = mThingManager!!.getSelectedThings()?.filterNotNull()?.map { it.id } ?: emptyList()
        val folderIds = mThingManager!!.getSelectedFolders().map { it.id }
        if (thingIds.isEmpty() && folderIds.isEmpty()) return
        val things = thingIds.mapNotNull { mThingManager!!.getThingById(it) }
        val folders = folderIds.mapNotNull { mThingManager!!.getFolderById(it) }
        val allSticky = things.all { it.location < 0 } && folders.all { it.location < 0 }
        val targetSticky = !allSticky
        mModeManager!!.backNormalMode(0)
        mRecyclerView!!.postDelayed({
            for (id in thingIds) {
                val thingIndex = mThingManager!!.getPosition(id)
                if (thingIndex < 0) continue
                val thing = mThingManager!!.getThings()!![thingIndex] ?: continue
                if ((thing.location < 0) == targetSticky) continue
                if (targetSticky) {
                    mThingManager!!.stickyThingOnTop(
                        thing,
                        thingIndex,
                        rebuildEntries = false
                    )
                } else {
                    mThingManager!!.cancelStickyThing(
                        thing,
                        thingIndex,
                        rebuildEntries = false
                    )
                }
            }
            for (id in folderIds) {
                val folder = mThingManager!!.getFolderById(id) ?: continue
                if ((folder.location < 0) != targetSticky) {
                    mThingManager!!.toggleFolderSticky(folder, reload = false)
                }
            }
            mThingManager!!.rebuildCurrentThingListEntries()
            mAdapter!!.setShouldThingsAnimWhenAppearing(false)
            mAdapter!!.notifyDataSetChanged()
            updateEmptyStateForCurrentSearchState()
            refreshActivitySurfaceAndHeader()
            mDrawerHeader!!.updateTexts()
            updateDrawerFolderItems()
        }, 160)
    }

    /** Batch privacy for the current selection: set all private unless all already are. */
    private fun toggleSelectedPrivateBatch() {
        val thingIds = mThingManager!!.getSelectedThings()?.filterNotNull()?.map { it.id } ?: emptyList()
        val folderIds = mThingManager!!.getSelectedFolders().map { it.id }
        if (thingIds.isEmpty() && folderIds.isEmpty()) return
        val things = thingIds.mapNotNull { mThingManager!!.getThingById(it) }
        val folders = folderIds.mapNotNull { mThingManager!!.getFolderById(it) }
        val allPrivate = things.all { it.isPrivate() } && folders.all { it.isPrivate }
        val targetPrivate = !allPrivate
        if (targetPrivate) {
            if (!hasPrivatePassword()) {
                val thing = things.firstOrNull()
                if (thing != null) {
                    warnNoPasswordForPrivateThing(thing)
                } else {
                    folders.firstOrNull()?.let { warnNoPasswordForPrivateFolder(it) }
                }
                return
            }
            applySelectedPrivateBatch(thingIds, folderIds, true)
        } else {
            val cp = getSharedPreferences(Def.Meta.PREFERENCES_NAME, MODE_PRIVATE)
                .getString(Def.Meta.KEY_PRIVATE_PASSWORD, null) ?: return
            AuthenticationHelper.authenticate(
                this,
                App.defaultAccentBackground,
                HomeActionWordingHelper.privateTitle(this, allPrivate = true),
                cp,
                object : AuthenticationHelper.AuthenticationCallback {
                    override fun onAuthenticated() {
                        applySelectedPrivateBatch(thingIds, folderIds, false)
                    }

                    override fun onCancel() {}
                })
        }
    }

    private fun applySelectedPrivateBatch(
        thingIds: List<Long>,
        folderIds: List<Long>,
        makePrivate: Boolean
    ) {
        var skipped = 0
        for (id in thingIds) {
            val thing = mThingManager!!.getThingById(id) ?: continue
            if (makePrivate) {
                if (thing.isPrivate()) continue
                if (thing.id == App.getDoingThingId()) { skipped++; continue }
                val title = thing.getTitleToDisplay()?.trim().orEmpty()
                thing.title = Thing.PRIVATE_THING_PREFIX + title
            } else {
                if (!thing.isPrivate()) continue
                thing.title = thing.getTitleToDisplay()
            }
            val thingIndex = mThingManager!!.getPosition(thing.id)
            if (thingIndex >= 0) {
                mThingManager!!.update(thing.type, thing, thingIndex, false)
            }
        }
        for (id in folderIds) {
            val folder = mThingManager!!.getFolderById(id) ?: continue
            if (folder.isPrivate != makePrivate) {
                mThingManager!!.updateFolderPrivate(folder, makePrivate, reload = false)
            }
        }
        mModeManager!!.backNormalMode(0)
        loadThingsForCurrentSearchState()
        mAdapter!!.setShouldThingsAnimWhenAppearing(false)
        mAdapter!!.notifyDataSetChanged()
        updateEmptyStateForCurrentSearchState()
        refreshActivitySurfaceAndHeader()
        mDrawerHeader!!.updateTexts()
        updateDrawerFolderItems()
        AppWidgetHelper.updateAllThingsListAppWidgets(this)
        SystemNotificationUtil.tryToCreateThingOngoingNotification(mApp)
        if (skipped > 0) {
            Toast.makeText(
                this, getString(R.string.private_batch_skipped), Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun toggleSelectedStickyEntry() {
        when (val entry = mThingManager!!.getSingleSelectedEntry()) {
            is ThingListEntry.ThingEntry -> toggleSelectedThingSticky(entry.thing)
            is ThingListEntry.FolderEntry -> toggleSelectedFolderSticky(entry.folder)
            else -> {}
        }
    }

    private fun toggleSelectedThingSticky(thing: Thing) {
        val oldThingPosition = mThingManager!!.getPosition(thing.id)
        if (oldThingPosition == -1) return
        val oldListPosition = getVisibleListPositionForThingIndex(oldThingPosition)
        mModeManager!!.backNormalMode(if (oldListPosition >= 0) oldListPosition else 0)
        mRecyclerView!!.postDelayed({
            if (oldThingPosition >= mThingManager!!.getThings()!!.size) return@postDelayed
            val selectedThing: Thing = mThingManager!!.getThings()!![oldThingPosition]!!
            if (selectedThing.location < 0) {
                mThingManager!!.cancelStickyThing(selectedThing, oldThingPosition)
            } else {
                mThingManager!!.stickyThingOnTop(selectedThing, oldThingPosition)
            }
            val newListPosition = mThingManager!!.getListPositionForThingId(selectedThing.id)
            if (oldListPosition >= 0 && newListPosition >= 0) {
                mAdapter!!.notifyItemMoved(oldListPosition, newListPosition)
                mRecyclerView!!.postDelayed({
                    mAdapter!!.notifyItemChanged(newListPosition)
                }, mRecyclerView!!.itemAnimator!!.moveDuration)
            } else {
                mAdapter!!.notifyDataSetChanged()
            }
        }, 160)
    }

    private fun toggleSelectedFolderSticky(folder: ThingFolder) {
        val oldListPosition = mThingManager!!.getListPositionForFolderId(folder.id)
        mModeManager!!.backNormalMode(if (oldListPosition >= 0) oldListPosition else 0)
        mRecyclerView!!.postDelayed({
            if (mThingManager!!.toggleFolderSticky(folder, reload = false)) {
                loadThingsForCurrentSearchState()
                val newListPosition = mThingManager!!.getListPositionForFolderId(folder.id)
                if (oldListPosition >= 0 && newListPosition >= 0) {
                    mAdapter!!.notifyItemMoved(oldListPosition, newListPosition)
                    mRecyclerView!!.postDelayed({
                        mAdapter!!.notifyItemChanged(newListPosition)
                    }, mRecyclerView!!.itemAnimator!!.moveDuration)
                } else {
                    mAdapter!!.notifyDataSetChanged()
                }
                refreshActivitySurfaceAndHeader()
                mDrawerHeader!!.updateTexts()
                updateDrawerFolderItems()
            }
        }, 160)
    }

    private fun toggleSelectedPrivateEntry() {
        when (val entry = mThingManager!!.getSingleSelectedEntry()) {
            is ThingListEntry.ThingEntry -> toggleSelectedThingPrivate(entry.thing)
            is ThingListEntry.FolderEntry -> {
                if (toggleThingFolderPrivate(entry.folder)) {
                    mModeManager!!.backNormalMode(0)
                }
            }
            else -> {}
        }
    }

    private fun toggleSelectedThingPrivate(thing: Thing) {
        if (!thing.isPrivate()) {
            if (!hasPrivatePassword()) {
                warnNoPasswordForPrivateThing(thing)
                return
            }
            val titleToDisplay = thing.getTitleToDisplay()?.trim().orEmpty()
            thing.title = Thing.PRIVATE_THING_PREFIX + titleToDisplay
            persistSelectedThingPrivateChange(thing)
            return
        }

        if (!hasPrivatePassword()) {
            warnNoPasswordForPrivateThing(thing)
            return
        }
        val cp = getSharedPreferences(Def.Meta.PREFERENCES_NAME, MODE_PRIVATE)
            .getString(Def.Meta.KEY_PRIVATE_PASSWORD, null) ?: return
        AuthenticationHelper.authenticate(
            this,
            thing.getBackground(),
            HomeActionWordingHelper.privateTitle(this, allPrivate = true),
            cp,
            object : AuthenticationHelper.AuthenticationCallback {
                override fun onAuthenticated() {
                    thing.title = thing.getTitleToDisplay()
                    persistSelectedThingPrivateChange(thing)
                }

                override fun onCancel() {}
            })
    }

    private fun persistSelectedThingPrivateChange(thing: Thing) {
        val thingIndex = mThingManager!!.getPosition(thing.id)
        if (thingIndex < 0) return
        mThingManager!!.update(thing.type, thing, thingIndex, false)
        mModeManager!!.backNormalMode(0)
        mAdapter!!.notifyDataSetChanged()
        AppWidgetHelper.updateSingleThingAppWidgets(this, thing.id)
        AppWidgetHelper.updateAllThingsListAppWidgets(this)
        SystemNotificationUtil.tryToCreateThingOngoingNotification(mApp)
    }

    private fun warnNoPasswordForPrivateThing(thing: Thing) {
        val adf = AlertDialogFragment()
        adf.setShowCancel(false)
        adf.setTitleBackground(thing.getBackground())
        adf.setConfirmBackground(thing.getBackground())
        adf.setTitle(HomeActionWordingHelper.cannotSetPrivateTitle(this))
        adf.setContent(getString(R.string.warning_should_set_password_first))
        adf.show(fragmentManager, AlertDialogFragment.TAG)
    }

    internal inner class OnContextualMenuClickedListener : Toolbar.OnMenuItemClickListener {
        override fun onMenuItemClick(item: MenuItem): Boolean {
            val itemId = item.itemId
            if (itemId == R.id.act_select_all) {
                if (isThingCardAppearancePanelShowing()) {
                    cancelThingCardAppearancePanel(false)
                }
                if (mThingManager!!.getSelectedCount() ==
                    mThingManager!!.getSelectableEntryCount()
                ) {
                    mThingManager!!.setSelectedTo(false)
                } else {
                    mThingManager!!.setSelectedTo(true)
                }
                mAdapter!!.notifyDataSetChanged()
                mModeManager!!.updateMenuItems()
            } else if (itemId == R.id.act_delete_selected) {
                confirmSelectedStateChange(Thing.DELETED)
            } else if (itemId == R.id.act_finish_selected) {
                confirmSelectedStateChange(Thing.FINISHED)
            } else if (itemId == R.id.act_restore_selected) {
                // Folders in the recycle bin are restored via "恢复文件夹中所有记事"
                // (act_restore_thing_folder_content); this path handles Things.
                confirmSelectedStateChange(Thing.UNDERWAY)
            } else if (itemId == R.id.act_delete_selected_forever) {
                confirmSelectedStateChange(Thing.DELETED_FOREVER)
            } else if (itemId == R.id.act_move_to_thing_folder) {
                confirmMoveSelected()
            } else if (itemId == R.id.act_sticky) {
                toggleSelectedStickyBatch()
            } else if (itemId == R.id.act_customize_card_appearance) {
                openSelectedCardAppearancePanel()
            } else if (itemId == R.id.act_set_as_private_thing) {
                toggleSelectedPrivateBatch()
            } else if (itemId == R.id.act_finish_thing_folder) {
                getSingleSelectedFolder()?.let { confirmFinishAllThingsInScope(it) }
            } else if (itemId == R.id.act_restore_thing_folder_content) {
                getSingleSelectedFolder()?.let {
                    when (mApp!!.getStatus()) {
                        Def.ThingStatus.FINISHED -> confirmUnfinishAllThingsInScope(it)
                        Def.ThingStatus.DELETED -> confirmRestoreTrashedThingsInScope(it)
                        else -> { }
                    }
                }
            } else if (itemId == R.id.act_delete_thing_folder_content) {
                getSingleSelectedFolder()?.let {
                    confirmDeleteForeverAllInScope(it)
                }
            } else if (itemId == R.id.act_dissolve_thing_folder) {
                getSingleSelectedFolder()?.let { showDissolveThingFolderDialog(it) }
            } else if (itemId == R.id.act_delete_thing_folder) {
                confirmDeleteSelectedStructural()
            } else if (itemId == R.id.act_export) {
                ThingExporter.startExporting(
                    this@ThingsActivity, App.defaultAccentBackground,
                    *(mThingManager!!.getSelectedThings() ?: emptyArray())
                )
                mModeManager!!.backNormalMode(0)
            }
            mModeManager!!.updateSelectedCount()
            return false
        }
    }

    private class FolderDropOutlineDecoration(
        private val outline: FolderDropOutlineDrawable,
        private val targetCard: View,
        private val targetScale: Float,
        private val gap: Float
    ) : RecyclerView.ItemDecoration() {

        private val targetBounds = RectF()

        var progress: Float
            get() = outline.progress
            set(value) {
                outline.progress = value
            }

        override fun onDraw(
            c: Canvas,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            if (!updateTargetBounds(parent)) return
            val horizontalInset = targetCard.width * (1.0f - targetScale) / 2.0f - gap
            val verticalInset = targetCard.height * (1.0f - targetScale) / 2.0f - gap
            outline.setBounds(
                (targetBounds.left + horizontalInset).roundToInt(),
                (targetBounds.top + verticalInset).roundToInt(),
                (targetBounds.right - horizontalInset).roundToInt(),
                (targetBounds.bottom - verticalInset).roundToInt()
            )
            outline.draw(c)
        }

        private fun updateTargetBounds(parent: RecyclerView): Boolean {
            if (!targetCard.isAttachedToWindow
                || targetCard.width <= 0
                || targetCard.height <= 0
            ) {
                return false
            }

            var left = targetCard.left + targetCard.translationX
            var top = targetCard.top + targetCard.translationY
            var ancestor = targetCard.parent
            while (ancestor is View && ancestor !== parent) {
                left += ancestor.left - ancestor.scrollX + ancestor.translationX
                top += ancestor.top - ancestor.scrollY + ancestor.translationY
                ancestor = ancestor.parent
            }
            if (ancestor !== parent) return false

            targetBounds.set(left, top, left + targetCard.width, top + targetCard.height)
            return true
        }
    }

    private class FolderDropOutlineDrawable(
        private val background: ThingBackground,
        private val radius: Float,
        private val maxStrokeWidth: Float
    ) : Drawable() {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        private val rect = RectF()
        private var externalAlpha = 255

        var progress: Float = 0f
            set(value) {
                field = value.coerceIn(0f, 1f)
                invalidateSelf()
            }

        override fun draw(canvas: Canvas) {
            if (progress <= 0f) return
            val strokeWidth = maxStrokeWidth * progress
            paint.strokeWidth = strokeWidth
            paint.alpha = (externalAlpha * progress).roundToInt()

            if (background.mode == ThingBackground.Mode.PURE) {
                paint.shader = null
                paint.color = background.color
            } else {
                paint.shader = createGradientShader()
            }

            rect.set(bounds)
            rect.inset(strokeWidth / 2f, strokeWidth / 2f)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }

        override fun setAlpha(alpha: Int) {
            externalAlpha = alpha.coerceIn(0, 255)
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        private fun createGradientShader(): LinearGradient {
            val width = bounds.width().coerceAtLeast(1).toFloat()
            val height = bounds.height().coerceAtLeast(1).toFloat()
            val left = bounds.left.toFloat()
            val top = bounds.top.toFloat()
            val coords = when (background.orientation) {
                ThingBackground.Orientation.L_R ->
                    floatArrayOf(left, top, left + width, top)
                ThingBackground.Orientation.T_B ->
                    floatArrayOf(left, top, left, top + height)
                ThingBackground.Orientation.LT_RB ->
                    floatArrayOf(left, top, left + width, top + height)
                ThingBackground.Orientation.RT_LB ->
                    floatArrayOf(left + width, top, left, top + height)
                ThingBackground.Orientation.LB_RT ->
                    floatArrayOf(left, top + height, left + width, top)
                ThingBackground.Orientation.RB_LT ->
                    floatArrayOf(left + width, top + height, left, top)
                ThingBackground.Orientation.R_L ->
                    floatArrayOf(left + width, top, left, top)
                ThingBackground.Orientation.B_T ->
                    floatArrayOf(left, top + height, left, top)
            }
            return LinearGradient(
                coords[0],
                coords[1],
                coords[2],
                coords[3],
                background.color,
                background.endColor,
                Shader.TileMode.CLAMP
            )
        }
    }

    private data class DrawerFolderItem(
        val folder: ThingFolder,
        val level: Int,
        val hasChildren: Boolean
    )

    companion object {
        const val TAG: String = "ThingsActivity"
        // Fallback delay for revealing a freshly created Thing if the
        // scroll-into-view never reports an idle state (e.g. nothing to scroll).
        private const val NEW_ITEM_REVEAL_SCROLL_TIMEOUT_MS: Long = 1200L
        private const val THING_CARD_SIDE_PANEL_PROJECTION_MAX_ITERATIONS = 6
        private const val THING_CARD_SIDE_PANEL_PROJECTION_TOLERANCE_PX = 1
        private const val THING_CARD_VIDEO_END_FRAME_GUARD_MS = 50
        private const val FOLDER_DROP_ACTION_NONE = 0
        private const val FOLDER_DROP_ACTION_CREATE = 1
        private const val FOLDER_DROP_ACTION_MOVE_TO_FOLDER = 2
        private const val FOLDER_CREATE_TARGET_SCALE = 0.92f
        private const val FOLDER_MOVE_TARGET_SCALE = 0.95f
        private const val FOLDER_CREATE_OUTLINE_GAP_DP = 6.0f
        private const val THING_CARD_APPEARANCE_PANEL_SPACE_SETTLE_DELAY_MS = 96L
        private const val THING_CARD_APPEARANCE_PANEL_SPACE_FINAL_DELAY_MS = 260L
        private const val THING_CARD_APPEARANCE_PANEL_ANIM_DURATION_MS = 190L
        private const val THING_CARD_APPEARANCE_PADDING_ANIM_DURATION_MS = 160L
        private const val THING_CARD_APPEARANCE_VISIBILITY_CHECK_DELAY_MS = 72L
        private const val THING_CARD_APPEARANCE_SCROLL_RECHECK_DELAY_MS = 180L
        private const val THING_CARD_APPEARANCE_SCROLL_TIMEOUT_MS: Long = 1200L
        private const val FOLDER_DROP_HOVER_ARM_DELAY_MS = 130L
        private const val FOLDER_DROP_HOVER_ARM_MIN_FRAMES = 2
        private const val CLEARED_DRAG_SCALE_RECOVERY_DURATION = 96L
        private const val FOLDER_DROP_TARGET_ANIM_DURATION = 160L
        private const val FOLDER_DROP_COMMIT_ANIM_DURATION = 190L
        private const val ACTIVE_TOUCH_ITEM_Z_OFFSET_DP = 4.0f
    }
}
