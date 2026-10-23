@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.activities

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import androidx.lifecycle.Lifecycle
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
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import com.ywwynm.everythingdone.views.GradientRippleDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import com.ywwynm.everythingdone.taptransfer.ui.receive.TapDetailReceiveController
import com.ywwynm.everythingdone.taptransfer.protocol.TapFileTransfer
import com.ywwynm.everythingdone.thingfile.PendingThing
import com.ywwynm.everythingdone.thingfile.ThingFileUpdate
import androidx.annotation.StringRes
import androidx.core.app.ActivityCompat
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.core.util.Pair
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.appcompat.widget.Toolbar
import com.ywwynm.everythingdone.helpers.DetailToolbarContrast
import androidx.recyclerview.widget.ItemTouchHelper
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.text.util.Linkify
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.Window
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.FrequentSettings
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.utils.LocalizedText
import com.ywwynm.everythingdone.adapters.AudioAttachmentAdapter
import com.ywwynm.everythingdone.adapters.CheckListAdapter
import com.ywwynm.everythingdone.adapters.ImageAttachmentAdapter
import com.ywwynm.everythingdone.helpers.DetailAttachmentPlaybackController
import com.ywwynm.everythingdone.helpers.DetailContentLayout
import com.ywwynm.everythingdone.helpers.DetailAttachmentItemAnimator
import com.ywwynm.everythingdone.helpers.DetailViewPreparation
import com.ywwynm.everythingdone.helpers.DetailScreenshotRenderer
import com.ywwynm.everythingdone.appwidgets.AppWidgetHelper
import com.ywwynm.everythingdone.appwidgets.CreateWidget
import com.ywwynm.everythingdone.collections.ThingActionsList
import com.ywwynm.everythingdone.database.HabitDAO
import com.ywwynm.everythingdone.database.ReminderDAO
import com.ywwynm.everythingdone.database.ThingDAO
import com.ywwynm.everythingdone.database.ThingFolderDAO
import com.ywwynm.everythingdone.fragments.AddAttachmentDialogFragment
import com.ywwynm.everythingdone.fragments.AlertDialogFragment
import com.ywwynm.everythingdone.fragments.AttachmentInfoDialogFragment
import com.ywwynm.everythingdone.fragments.AudioPlayDialogFragment
import com.ywwynm.everythingdone.fragments.AudioRecordDialogFragment
import com.ywwynm.everythingdone.fragments.CameraColorSamplingDialogFragment
import com.ywwynm.everythingdone.fragments.ChooserDialogFragment
import com.ywwynm.everythingdone.fragments.ColorInfoDialogFragment
import com.ywwynm.everythingdone.fragments.DateTimeDialogFragment
import com.ywwynm.everythingdone.fragments.HabitDetailDialogFragment
import com.ywwynm.everythingdone.fragments.HabitRecordDialogFragment
import com.ywwynm.everythingdone.fragments.LoadingDialogFragment
import com.ywwynm.everythingdone.fragments.LongTextDialogFragment
import com.ywwynm.everythingdone.fragments.MediaCropAppearanceDialogFragment
import com.ywwynm.everythingdone.fragments.PatternLockDialogFragment
import com.ywwynm.everythingdone.fragments.ThingDoingDialogFragment
import com.ywwynm.everythingdone.fragments.TwoOptionsDialogFragment
import com.ywwynm.everythingdone.thingfile.ThingFileSharer
import com.ywwynm.everythingdone.taptransfer.ui.share.TapShareOverlay
import com.ywwynm.everythingdone.helpers.AppUpdateHelper
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.helpers.AuthenticationHelper
import com.ywwynm.everythingdone.helpers.CheckListHelper
import com.ywwynm.everythingdone.helpers.LineSpacingHelper
import com.ywwynm.everythingdone.helpers.ScreenshotHelper
import com.ywwynm.everythingdone.helpers.SendInfoHelper
import com.ywwynm.everythingdone.helpers.ThingDoingHelper
import com.ywwynm.everythingdone.helpers.ThingCardMediaHelper
import com.ywwynm.everythingdone.helpers.ThingExporter
import com.ywwynm.everythingdone.managers.ThingManager
import com.ywwynm.everythingdone.model.DetailAttachmentMediaAppearance
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
import com.ywwynm.everythingdone.utils.BitmapUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.FileUtil
import com.ywwynm.everythingdone.utils.KeyboardUtil
import com.ywwynm.everythingdone.utils.LocaleUtil
import com.ywwynm.everythingdone.utils.SystemNotificationUtil
import com.ywwynm.everythingdone.utils.UriPathConverter
import com.ywwynm.everythingdone.views.Snackbar
import com.ywwynm.everythingdone.views.DrawerNavigationView
import com.ywwynm.everythingdone.views.ThingCardCropEditorController
import com.ywwynm.everythingdone.views.ThingCardCropEditorView
import com.ywwynm.everythingdone.views.RatioSlider
import com.ywwynm.everythingdone.views.ThingCardVideoCropEditorView
import com.ywwynm.everythingdone.fragments.ThingBackgroundEditorBottomSheet
import com.ywwynm.everythingdone.services.AudioRecordingService
import com.ywwynm.everythingdone.views.ThingBackgroundEditor
import com.ywwynm.everythingdone.views.particledismiss.ParticleLaunchTrace
import com.ywwynm.everythingdone.views.particledismiss.ThingCreationTransition
import com.ywwynm.everythingdone.views.ThingDetailTransition
import com.ywwynm.everythingdone.views.MediaPreviewTransition
import com.ywwynm.everythingdone.views.ThingDetailReturnTransition
import com.ywwynm.everythingdone.views.pickers.DateTimePicker
import com.ywwynm.everythingdone.views.recording.AudioInputPreferences
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

import java.time.ZonedDateTime

import java.io.File
import java.util.ArrayList
import java.util.Calendar
import java.util.Collections
import java.util.HashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.ywwynm.everythingdone.helpers.DetailCropPreviewLoader
import com.ywwynm.everythingdone.interaction.UiInteractions
import androidx.core.view.isVisible
import androidx.core.view.size
import androidx.core.view.get
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.edit
import androidx.fragment.app.DialogFragment

@SuppressLint("NewApi")
class DetailActivity : EverythingDoneBaseActivity(), MediaCropAppearanceDialogFragment.Host {

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
    private var mDetailAttachmentMediaAppearance: DetailAttachmentMediaAppearance =
        DetailAttachmentMediaAppearance.default()
    private var mThingIndex: Int = 0
    private var mListPosition: Int = -1
    private var mListProjectionKey: String? = null
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
    private var mNightModeMask: Int = 0
    private var mRenderedThingSnapshot: ThingSnapshot? = null
    private var mReloadFromStorageOnResume: Boolean = false
    private var mExternalUpdateRefreshRetries: Int = 0
    private var mStatusBarTopInset: Int = 0
    private var mScrollViewHasStatusBarMarginTop: Boolean = true

    private var mFlRoot: FrameLayout? = null
    private var mBgEditorSheet: ThingBackgroundEditorBottomSheet? = null
    private var mColorEditorBgFrom: ThingBackground? = null
    private var mPendingWorldSlot: Int = ThingBackgroundEditor.SLOT_PURE
    private var mStatusBar: View? = null
    private var mActionbar: Toolbar? = null
    private var mIbBack: ImageButton? = null
    private var mActionBarShadow: View? = null
    private var mImageCover: View? = null
    private var mToolbarContrast: DetailToolbarContrast? = null

    private var mImageAttachmentAdapter: ImageAttachmentAdapter? = null

    /** 详情附件网格的 Detail Autoplay 调度器，见 ADR-0017。 */
    private var mAttachmentPlaybackController: DetailAttachmentPlaybackController? = null

    private var mDetailList: RecyclerView? = null
    private lateinit var mDetailContent: DetailContentLayout
    private var mChecklistVisible = false
    private var mChecklistFocusRequest = 0
    private var mLastChecklistDragPosition = RecyclerView.NO_POSITION
    private val hasImageAttachments: Boolean get() = (mImageAttachmentAdapter?.itemCount ?: 0) > 0
    private val hasAudioAttachments: Boolean get() = (mAudioAttachmentAdapter?.itemCount ?: 0) > 0
    private var mTapShareOverlay: TapShareOverlay? = null
    private var mEtTitle: EditText? = null
    private var mEtContent: EditText? = null
    private var mTvThingFolderPath: TextView? = null
    private var mTvUpdateTime: TextView? = null

    private var mCheckListAdapter: CheckListAdapter? = null
    private var mLlMoveChecklist: LinearLayout? = null
    private var mTvMoveChecklistAsBt: TextView? = null
    private var mChecklistTouchHelper: ItemTouchHelper? = null
    private var mTouchSection: RecyclerView.Adapter<*>? = null

    // 清单拖拽沿用恒速边缘滚动，并按同级兄弟边界停止。
    private var mDraggedChecklistVH: RecyclerView.ViewHolder? = null
    private var mDragScrollRunnable: Runnable? = null
    private var mLastTouchScreenY: Int = 0  // 实时手指屏幕 Y，用于自动滚动的边缘判定（不受补偿平移影响）
    // 上一次触摸事件发生时父容器的 scrollY。补偿量 = 当前 scrollY − 该值，即“自上次手指事件以来父容器
    // 又滚动了多少”。ItemTouchHelper 的 dY 已在每次 MOVE 时把当时的滚动量算进去，故只需补这段增量，
    // 不能像之前那样无脑累加（否则与 dY 双重计数，被拖项会以两倍速度飞出屏幕而“消失”）。
    // 拖拽“子树收束”：拖拽开始时把被拖项的子树从列表临时移除（收束动画，拖拽中只剩父项作代理跟手），
    // 暂存于此，松手时再插回代理下方（展开动画）。整段拖拽只记一次 UPDATE_CHECKLIST 撤销动作。
    private var mCollapsedChildren: MutableList<String?>? = null
    private var mDragBeforeContent: String? = null
    // 仅移动模式期间挂载，做子树收束/展开动画并门控 onMove 的瞬时重排（见 ChecklistDragItemAnimator）。
    private var mChecklistDragAnimator: ChecklistDragItemAnimator? = null
    // 被拖父项上的胶囊轮廓（overlay drawable + 宿主视图），随拖拽渐显、松手渐隐。
    private var mCapsuleHostView: View? = null
    private var mCapsuleDrawable: GradientDrawable? = null

    private var mAudioAttachmentAdapter: AudioAttachmentAdapter? = null

    private var mFlQuickRemindAsBt: FrameLayout? = null

    private var mNormalSnackbar: Snackbar? = null

    private fun normalSnackbar(): Snackbar = mNormalSnackbar ?: Snackbar(
        mApp!!, Snackbar.NORMAL, mFlRoot!!, null
    ).also { mNormalSnackbar = it }

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
        val thingCardImagePlacement: Int,
        val detailAttachmentMediaAppearance: String
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

    private var tapUpdateReceiver: TapDetailReceiveController? = null
    private var deferTapUpdateSetup = false
    private val tapHistoryOwner = Any()
    private val tapHistoryAttachments = LinkedHashSet<String>()
    private val tapUpdateOrigin = java.util.UUID.randomUUID().toString()

    private data class ReceivedEditState(
        val thing: Thing,
        val alarms: ThingFileUpdate.Alarms?,
        val draftParams: ReminderHabitParams? = null,
        val draftChecked: Boolean = false,
        val draftPicked: Int = -1,
        val draftDescription: String = ""
    )

    private val mReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getStringExtra("tap_update_origin") == tapUpdateOrigin) return
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

    /** 当前记事 id；录音服务用它绑定会话与通知落点校验。 */
    fun currentThingId(): Long = mThing?.id ?: -1L

    override fun getLayoutResource(): Int = R.layout.activity_detail

    private var creationTransition: ThingCreationTransition? = null
    private var detailTransition: ThingDetailTransition? = null
    private var preparedDetailSections: LinearLayout? = null
    private var preparedQuickPicker: View? = null

    override fun setContentView(layoutResID: Int) {
        val prepared = if (layoutResID == R.layout.activity_detail) DetailViewPreparation.take(this) else null
        if (prepared == null) super.setContentView(layoutResID)
        else {
            preparedDetailSections = prepared.sections
            preparedQuickPicker = prepared.quickPicker
            super.setContentView(prepared.root)
        }
    }

    /** 新建时可以等入场动画结束再做、与首帧无关的那点工作。 */
    private var deferredAfterEnter: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThingDetailTransition.mark("create")
        val key = ThingCreationTransition.EXTRA_TOKEN
        if (savedInstanceState != null) intent.removeExtra(key)
        if (savedInstanceState != null) intent.removeExtra(ThingDetailTransition.EXTRA_TOKEN)
        if (intent.hasExtra(key)) mark("detail-create")
        super.onCreate(savedInstanceState)
        ThingDetailTransition.mark("created")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::mDetailContent.isInitialized) {
            outState.putBundle("detail_fixed_views", mDetailContent.saveFixedViewState())
        }
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        if (::mDetailContent.isInitialized) {
            savedInstanceState.getBundle("detail_fixed_views")?.let(mDetailContent::restoreFixedViewState)
        }
    }

    /** 2a 的链路分段：只记新建这条路径；release 包里是空调用。 */
    private fun mark(label: String) {
        if (mType != CREATE) return
        ParticleLaunchTrace.mark(label)
    }

    override fun init() {
        ThingDetailTransition.mark("inflated")
        initMembers() // if we found thing is null, just finish this Activity
        ThingDetailTransition.mark("members")
        if (mThing != null) {
            mark("detail-members")
            findViews()
            ThingDetailTransition.mark("views")
            mark("detail-views")
            initUI()
            ThingDetailTransition.mark("ui")
            mark("detail-ui")
            recordRenderedThingSnapshot()
            setActionbar()
            setEvents()
            // 接收控制器及 NFC 查询不参与展开画面，等转场结束后再准备。
            deferTapUpdateSetup = intent.hasExtra(ThingDetailTransition.EXTRA_TOKEN)
            if (!deferTapUpdateSetup) setupTapUpdate()
            ThingDetailTransition.mark("events")
            mark("detail-events")

            creationTransition = ThingCreationTransition
                .attach(this, mFlRoot!!, mThing!!.getBackground() ?: ThingBackground.pure(mThing!!.getColor()))
            ThingDetailReturnTransition.bindOrigin(this, mThing!!.id)
            detailTransition = ThingDetailTransition.attach(this, mFlRoot!!, mThing!!.id) {
                if (!committedReturnPending) mAttachmentPlaybackController?.setTransitionPaused(false)
                // 本帧先完成真实页面交接，服务查询不占用最后一帧的绘制。
                mFlRoot!!.post { finishDeferredTapUpdateSetup() }
            }
            if (detailTransition == null) {
                mAttachmentPlaybackController?.setTransitionPaused(false)
                finishDeferredTapUpdateSetup()
            }
            // 入场动画期间主线程要留给首帧与粒子交接；没有入场动画时立即执行。
            deferredAfterEnter?.let { pending ->
                deferredAfterEnter = null
                val run = Runnable { if (!isFinishing && !isDestroyed) pending.run() }
                val transition = creationTransition
                if (transition == null) mFlRoot!!.post(run) else transition.afterFinish(run)
            }

            var intentFilter = IntentFilter(Def.Communication.BROADCAST_ACTION_UPDATE_MAIN_UI)
            ContextCompat.registerReceiver(this, mReceiver, intentFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED)

            intentFilter = IntentFilter(Def.Communication.BROADCAST_ACTION_FINISH_DETAILACTIVITY)
            ContextCompat.registerReceiver(this, mReceiver, intentFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }

    override fun beforeSetContentView() {
        super.beforeSetContentView()
        ThingDetailTransition.mark("before-layout")
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

        mThingIndex = intent.getIntExtra(Def.Communication.KEY_POSITION, 1)
        mListPosition = intent.getIntExtra(Def.Communication.KEY_LIST_POSITION, -1)
        mListProjectionKey = intent.getStringExtra(Def.Communication.KEY_LIST_PROJECTION)

        val thingManager: ThingManager = ThingManager.getInstance(mApp)!!
        if (mType == CREATE) {
            createActivitiesCount++

            val newId = thingManager.getHeaderId()
            App.getRunningDetailActivities().add(newId)

            App.ensureNewThingBackground()
            var color = intent.getIntExtra(Def.Communication.KEY_COLOR, App.newThingColor)
            if (color == 0) color = DisplayUtil.getRandomColor(mApp)

            mThing = Thing(newId, Thing.NOTE, color, newId)
            val folderId = intent.getLongExtra(Def.Communication.KEY_FOLDER_ID, Long.MIN_VALUE)
            if (folderId != Long.MIN_VALUE) {
                mThing!!.folderId = folderId
            }

            val bgJson: String? = intent.getStringExtra(Def.Communication.KEY_BACKGROUND)
            if (bgJson != null) {
                val bg: ThingBackground? = ThingBackground.fromJson(bgJson)
                if (bg != null) mThing!!.setBackground(bg)
            }

            // 粒子第四批 ②：随机掷点提前到首页（可见时 / 创建页关闭后）做，这里只标记「用掉了」。
            // 首页据此在空闲时预先建材并预算出现轨迹；任何读预掷值的地方都先 ensure 一次，
            // 已消费而还没重掷时立刻补掷，两条记事绝不会同色。
            App.consumeNewThingBackground()
            // 常驻「快速新建」通知与创建页的首帧没有关系（读偏好 + 建 PendingIntent + 两次
            // NotificationManager binder），却整块压在 onCreate 的主线程上。挪到首帧之后，
            // 行为不变——它依赖的 App.newThingColor 上一行已经定好了。
            deferredAfterEnter = Runnable { SystemNotificationUtil.tryToCreateQuickCreateNotification(this) }

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
        mDetailAttachmentMediaAppearance = mThing!!.detailAttachmentMediaAppearance
        if (mEditable) {
            mShowNormalSnackbar = Runnable {
                normalSnackbar().show()
            }
        }

        setSpans()

        if (mEditable) {
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
            if (isIncomingMediaShare(type)) {
                val data: Uri? = getIncomingShareUri(intent)
                val typePathName: String? = getTypePathNameFromIncomingShare(data, type)
                if (typePathName != null) {
                    mThing!!.attachment = AttachmentHelper.SIGNAL + typePathName
                }
            }
        } else if (Intent.ACTION_SEND_MULTIPLE == action) {
            val sb = StringBuilder()
            for (data in getIncomingShareUris(intent)) {
                val typePathName: String? = getTypePathNameFromIncomingShare(data, type)
                if (typePathName != null) {
                    sb.append(AttachmentHelper.SIGNAL).append(typePathName)
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

    private fun isIncomingMediaShare(type: String?): Boolean {
        return type?.let {
            it.contains("image/") || it.contains("video/") || it.contains("audio/")
        } ?: false
    }

    private fun getIncomingShareUri(intent: Intent): Uri? {
        val stream: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)
        if (stream != null) return stream
        if (intent.data != null) return intent.data
        return getClipDataUris(intent).firstOrNull()
    }

    private fun getIncomingShareUris(intent: Intent): List<Uri> {
        val ret: MutableList<Uri> = ArrayList()
        val seen: MutableSet<String> = HashSet()

        fun add(uri: Uri?) {
            if (uri == null) return
            if (seen.add(uri.toString())) {
                ret.add(uri)
            }
        }

        val streams: ArrayList<Uri>? = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        streams?.forEach { add(it) }
        getClipDataUris(intent).forEach { add(it) }
        add(intent.data)

        return ret
    }

    private fun getClipDataUris(intent: Intent): List<Uri> {
        val clipData: ClipData = intent.clipData ?: return emptyList()
        val uris: MutableList<Uri> = ArrayList()
        for (i in 0 until clipData.itemCount) {
            clipData.getItemAt(i).uri?.let { uris.add(it) }
        }
        return uris
    }

    private fun getTypePathNameFromIncomingShare(uri: Uri?, sharedMimeType: String?): String? {
        if (uri == null) return null

        val resolvedMimePostfix: String? = try {
            FileUtil.getPostfixFromMimeType(this, uri)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot resolve shared media mime type: $uri", e)
            null
        }
        val mimePostfix: String? = resolvedMimePostfix
            ?: getPostfixFromSharedMimeType(sharedMimeType)
        var pathName: String? = UriPathConverter.getLocalPathName(this, uri)
        if (pathName == null && mimePostfix != null) {
            pathName = FileUtil.copyUriToFile(this, uri, mimePostfix)
        }

        return pathName?.let { getTypePathName(it, mimePostfix, uri) }
    }

    private fun getPostfixFromSharedMimeType(mimeType: String?): String? {
        return when {
            mimeType == null -> null
            mimeType == "image/jpeg" || mimeType == "image/jpg" -> ".jpg"
            mimeType == "image/png" -> ".png"
            mimeType == "image/gif" -> ".gif"
            mimeType == "image/webp" -> ".webp"
            mimeType.startsWith("image/") -> ".jpg"
            mimeType.startsWith("video/") -> ".mp4"
            mimeType == "audio/mpeg" -> ".mp3"
            mimeType == "audio/wav" -> ".wav"
            mimeType.startsWith("audio/") -> ".mp3"
            else -> null
        }
    }

    private fun getDailyTodoTitle(): String {
        return LocalizedText.dailyTodoTitle(this)
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
        mThingIndex = pair.second ?: -1
        mListPosition = -1
        mListProjectionKey = null
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
            thing.thingCardImagePlacement,
            thing.detailAttachmentMediaAppearance.toJson()
        )
    }

    private fun loadLatestThingAndPosition(id: Long): Pair<Thing?, Int> {
        val pair: Pair<Thing, Int> = App.getThingAndPosition(mApp, id, mThingIndex)
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
        mThingIndex = pair.second ?: -1
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
                                    normalSnackbar().setMessage(R.string.error_activity_not_found)
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
                            df.show(supportFragmentManager, TwoOptionsDialogFragment.TAG)
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

        mDetailList = f(R.id.sv_detail)
        mDetailContent = DetailContentLayout(mDetailList!!, preparedDetailSections)
        preparedDetailSections = null
        mToolbarContrast = DetailToolbarContrast(mFlRoot!!, mActionbar!!, mDetailList!!,
            mImageCover!!, ::getAccentBackground)
        mAttachmentPlaybackController = DetailAttachmentPlaybackController(mDetailList!!)
        mAttachmentPlaybackController?.setTransitionPaused(intent.hasExtra(ThingDetailTransition.EXTRA_TOKEN))
        mEtTitle = mDetailContent.view(R.id.et_title)
        mEtContent = mDetailContent.view(R.id.et_content)
        mTvThingFolderPath = mDetailContent.view(R.id.tv_thing_folder_path)
        mTvUpdateTime = mDetailContent.view(R.id.tv_update_time)
        mLlMoveChecklist = mDetailContent.view(R.id.ll_move_checklist)
        mTvMoveChecklistAsBt = mDetailContent.view(R.id.tv_move_checklist_as_bt)

        mFlQuickRemindAsBt = f(R.id.fl_quick_remind_as_bt)
        cbQuickRemind      = f(R.id.cb_quick_remind)
        BackgroundUtil.applyCheckboxAccent(cbQuickRemind!!, App.defaultAccentBackground)
        tvQuickRemind      = f(R.id.tv_quick_remind)

        if (mEditable) {
            createEditablePickers()
        }
        preparedQuickPicker = null
    }

    private fun createDateTimeDialogFragment() {
        mDateTimeDialogFragment = DateTimeDialogFragment.newInstance(mThing)
    }

    private fun createEditablePickers() {
        val decorView: View = window.decorView
        quickRemindPicker = DateTimePicker(
            this, decorView,
            Def.PickerType.AFTER_TIME, getAccentColor(), preparedQuickPicker
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
        }

        mEtTitle!!.setText(mThing!!.getTitleToDisplay())
        if (mThing!!.isPrivate()) {
            setAsPrivateThingUiAndAddAction()
        }
        updateThingFolderPath()

        initUiForThingContent()
        initUiForThingAttachment()

        initUiTvUpdateFinish(thingType, thingState)

        initUiBottomBar()

        updateDescriptions(mThing!!.getColor())
    }

    private fun initBackButton(@Thing.Type thingType: Int) {
        when (thingType) {
            Thing.REMINDER -> {
                mIbBack!!.setImageResource(R.drawable.act_back_reminder)
                mIbBack!!.contentDescription = getString(R.string.cd_back_reminder)
            }
            Thing.HABIT -> {
                mIbBack!!.setImageResource(R.drawable.act_back_habit)
                mIbBack!!.contentDescription = getString(R.string.cd_back_habit)
            }
            Thing.GOAL -> {
                mIbBack!!.setImageResource(R.drawable.act_back_goal)
                mIbBack!!.contentDescription = getString(R.string.cd_back_goal)
            }
            else -> {
                mIbBack!!.setImageResource(R.drawable.act_back_note)
                mIbBack!!.contentDescription = getString(R.string.cd_back_note)
            }
        }
    }

    private fun updateThingFolderPath() {
        val folderId = mThing?.folderId
        if (folderId == null) {
            mTvThingFolderPath!!.setCompoundDrawablesRelative(null, null, null, null)
            mTvThingFolderPath!!.visibility = View.GONE
            return
        }

        val folders = ThingFolderDAO.getInstance(mApp)!!.getFolderPath(folderId)
        val manager = ThingManager.getInstance(mApp)
        if (folders.isEmpty()) {
            mTvThingFolderPath!!.setCompoundDrawablesRelative(null, null, null, null)
            mTvThingFolderPath!!.visibility = View.GONE
            return
        }

        val path = folders.joinToString("/") { folder ->
            if (folder.isPrivate && manager?.isFolderPrivacyAuthenticated(folder.id) != true) {
                getString(R.string.private_thing_folder)
            } else {
                folder.title
            }
        }
        val lastFolder = folders.last()
        val icon = DrawerNavigationView.FolderIconDrawable(
            lastFolder.getBackground() ?: ThingBackground.pure(lastFolder.getColor()),
            lastFolder.isPrivate,
            // 已鉴权私密文件夹画开锁，与路径文字按认证显示真实名一致。
            manager?.isFolderPrivacyAuthenticated(lastFolder.id) == true
        )
        val iconSize = (18 * screenDensity).toInt().coerceAtLeast(1)
        val iconShiftY = (screenDensity * 1).toInt().coerceAtLeast(1)
        icon.setBounds(0, iconShiftY, iconSize, iconSize + iconShiftY)
        mTvThingFolderPath!!.setCompoundDrawablesRelative(icon, null, null, null)
        mTvThingFolderPath!!.text = path
        mTvThingFolderPath!!.visibility = View.VISIBLE
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
                mChecklistVisible = true
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

                if (mEditable) mCheckListAdapter!!.setExpanded(false)
                syncDetailContent()
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
        val tvFinishTime: TextView = mDetailContent.view(R.id.tv_finish_time)!!
        tvFinishTime.paint.textSkewX = -0.25f
        if (mType == CREATE) {
            mTvUpdateTime!!.text = ""
            tvFinishTime.visibility = View.GONE
        } else {
            val isChinese = LocaleUtil.isChinese(this)
            val timestampKey = if (mThing!!.createTime == mThing!!.updateTime)
                R.string.l10n_created_at else R.string.l10n_updated_at
            mTvUpdateTime!!.text = getString(timestampKey,
                DateTimeUtil.getDateTimeStrAt(mThing!!.updateTime, mApp, true))

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
                tvFinishTime.text = getString(R.string.l10n_habit_developed_at,
                    DateTimeUtil.getDateTimeStrAt(mThing!!.finishTime, mApp, true))
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
                    val params = mDetailList!!.layoutParams as FrameLayout.LayoutParams
                    params.setMargins(0, params.topMargin, 0, 0)
                }
            }
            cbQuickRemind!!.contentDescription =
                getString(R.string.l10n_share_reminder, tvQuickRemind!!.text)
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
                    tddf.show(supportFragmentManager, ThingDoingDialogFragment.TAG)
                }
            }

            val d1: Drawable = ContextCompat.getDrawable(this, R.drawable.vec_ic_start_thing)!!
            val d2: Drawable = d1.mutate()
            // 按钮是 accent+accent2 渐变（按深色处理），图标改为偏白；vec 图标无透明度，SRC_IN 即可。
            d2.setColorFilter(
                ContextCompat.getColor(this, R.color.white_86p),
                PorterDuff.Mode.SRC_IN
            )
            val iv: ImageView = f(R.id.iv_doing_detail)!!
            iv.setImageDrawable(d2)

            fl.foreground = BackgroundUtil.circularRipple(BackgroundUtil.RIPPLE_LIGHT)
            BackgroundUtil.applyOvalBackground(fl, App.defaultAccentBackground)
        }
    }

    override fun setActionbar() {
        setSupportActionBar(mActionbar)
        if (supportActionBar != null) {
            supportActionBar!!.title = null
        }
        mIbBack!!.setOnClickListener {
            // 左上角返回 icon 始终直接退出详情（即使处于清单项移动模式）；移动模式由系统返回键退出。
            returnToThingsActivity(true, true)
        }
    }

    override fun setEvents() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (mDetailCropPreviewModal != null) {
                    cancelDetailCropPreview()
                    return
                }
                // 处于清单项移动模式时，返回键先完成并退出移动模式，而不是退出详情界面。
                if (isChecklistMoveMode()) {
                    exitChecklistMoveMode()
                    return
                }
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

                override fun onKeyboardShow(keyboardHeight: Int) {
                    mDetailList!!.post { revealFocusedEditor() }
                }

                override fun onKeyboardHide() {
                    // No-op — padding restoration is handled by the inset chain.
                }
            })
            // 输入法动画会在“已显示”回调之后继续缩小列表视口；按最终布局再次避让光标。
            mDetailList!!.addOnLayoutChangeListener { view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if ((right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) &&
                    ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
                    view.post { revealFocusedEditor() }
                }
            }
        }

        if (mShouldAutoLink) {
            mEtContent!!.setOnTouchListener(mSpannableTouchListener)
            mEtContent!!.setOnClickListener(mEtContentClickListener)
            mEtContent!!.setOnLongClickListener(mEtContentLongClickListener)
        }

        if (mEditable) {
            LineSpacingHelper.helpCorrectSpacingForNewLine(mEtContent)
            if (!DeviceUtil.isFlyme()) {
                val appAccent = App.defaultAccentBackground.representativeColor()
                val cursorWidth = (1.5 * screenDensity).toInt()
                val lastLineCursorHeightVary = (-1 * screenDensity).toInt()
                LineSpacingHelper.setTextCursorDrawable(
                    mEtContent, appAccent, cursorWidth,
                    (-4 * screenDensity).toInt(), lastLineCursorHeightVary
                )
            }

            setEditTextWatchers()
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
        mDetailList!!.requestLayout()
    }

    private fun setMoveChecklistEvent() {
        if (!mEditable) return
        ensureDetailTouchHelper()

        mTvMoveChecklistAsBt!!.setOnClickListener {
            if (!mCheckListAdapter!!.isDragging()) {
                enterChecklistMoveMode()
            } else {
                exitChecklistMoveMode()
            }
        }

        mCheckListAdapter!!.setIvStateTouchCallback(object : CheckListAdapter.IvStateTouchCallback {
            override fun onTouch(pos: Int) {
                mDetailContent.holder(mCheckListAdapter!!, pos)?.let {
                    mChecklistTouchHelper!!.startDrag(it)
                }
            }
        })
    }

    /** 清单项移动（拖拽）模式是否开启。 */
    private fun isChecklistMoveMode(): Boolean = mCheckListAdapter?.isDragging() == true

    private fun enterChecklistMoveMode() {
        mTvMoveChecklistAsBt!!.setText(R.string.act_back_from_move_checklist)
        mTvMoveChecklistAsBt!!.setCompoundDrawablesRelativeWithIntrinsicBounds(
            R.drawable.act_back_from_move_checklist, 0, 0, 0
        )
        mCheckListAdapter!!.setDragging(true)
        // 移动模式增加清单收束／展开动画；附件仍保留位移，普通编辑不做变化动画。
        if (mChecklistDragAnimator == null) mChecklistDragAnimator = ChecklistDragItemAnimator()
        mDetailList!!.itemAnimator = mChecklistDragAnimator
        // 不裁剪子项，让被拖项的轮廓可在 itemView 下方溢出一点（多行文字时给文字底与轮廓底留白用），不被裁掉。
        mDetailList!!.clipChildren = false
        mDetailList!!.clipToPadding = false
        mCheckListAdapter!!.notifyDataSetChanged()
    }

    private fun exitChecklistMoveMode() {
        mTvMoveChecklistAsBt!!.setText(R.string.act_move_check_list)
        mTvMoveChecklistAsBt!!.setCompoundDrawablesRelativeWithIntrinsicBounds(
            R.drawable.act_move_checklist, 0, 0, 0
        )
        mCheckListAdapter!!.setDragging(false)
        removeChecklistCapsule()                 // 兜底：移动模式结束清掉可能残留的轮廓
        mDetailContent.restoreAttachmentAnimator()
        mDetailList!!.clipChildren = true       // 恢复默认裁剪
        mDetailList!!.clipToPadding = false
        mCheckListAdapter!!.notifyDataSetChanged()
    }

    fun updateDescriptions(color: Int) {
        var title: String
        if (mType == CREATE) {
            title = getString(R.string.title_create_thing)
        } else {
            title = if (mEditable) LocalizedText.typeTitle(this, getThingTypeAfter(), share = false)
                else Thing.getTypeStr(getThingTypeAfter(), mApp)!!
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
        updateTapUpdateMenu(menu)
        updateUndoRedoActionButtonState()
        mToolbarContrast?.refresh()
        return true
    }

    private fun updateTapUpdateMenu(menu: Menu) {
        val available = canTapUpdate()
        menu.findItem(R.id.act_tap_update)?.isVisible = available
        if (available) {
            if (menu.findItem(R.id.act_undo) == null) {
                menu.add(Menu.NONE, R.id.act_undo, Menu.NONE, R.string.act_undo)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
                menu.add(Menu.NONE, R.id.act_redo, Menu.NONE, R.string.act_redo)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val itemId = item.itemId
        if (itemId == R.id.act_add_attachment) {
            AddAttachmentDialogFragment.newInstance().show(
                supportFragmentManager, AddAttachmentDialogFragment.TAG
            )
        } else if (itemId == R.id.act_check_list) {
            toggleCheckList()
        } else if (itemId == R.id.act_change_color) {
            showThingBackgroundEditor()
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
                hddf.show(supportFragmentManager, HabitDetailDialogFragment.TAG)
            }
        } else if (itemId == R.id.act_check_update_habit_record) {
            if (mHabit != null) {
                val hrdf = HabitRecordDialogFragment()
                mHabit = HabitDAO.getInstance(this)!!.getHabitById(mHabit!!.id)
                hrdf.setHabit(mHabit)
                hrdf.setEditable(mEditable)
                hrdf.show(supportFragmentManager, HabitRecordDialogFragment.TAG)
            }
        } else if (itemId == R.id.act_tap_update) {
            startTapUpdate()
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
            ThingExporter.startExporting(
                this@DetailActivity,
                getAccentBackground() ?: ThingBackground.pure(getAccentColor()),
                mThing
            )
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
        ThingDetailReturnTransition.forgetOrigin(this)
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

    private var committedReturnPending = false

    /** 只在校验、确认及业务结果已经完成的退出点调用，普通 finish／后台保存不进入动画。 */
    private fun finishCommittedReturn() {
        if (committedReturnPending || isFinishing) return
        committedReturnPending = true
        dontSaveAfterOnPause = true
        val thing = mThing
        if (mType != CREATE && thing != null &&
            ThingDetailReturnTransition.captureAndFinish(this, thing,
                detailTransition?.takeIf { it.isRunning }) { finish() }) return
        finish()
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
        if (mChecklistVisible) {
            before = CheckListHelper.toCheckListStr(mCheckListAdapter!!.getItems())
            toggleCheckListActionItem(mActionbar!!.menu, false)
            mEtContent!!.visibility = View.VISIBLE
            mChecklistVisible = false
            syncDetailContent()
            if (mLlMoveChecklist != null) {
                // don't know why this is possible but some user's log showed that this can happen
                mLlMoveChecklist!!.visibility = View.GONE
            }
            if (mCheckListAdapter!!.isDragging()) exitChecklistMoveMode()

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
            mChecklistVisible = true
            mEtContent!!.visibility = View.GONE
            mLlMoveChecklist!!.visibility = View.VISIBLE

            val content: String = mEtContent!!.text.toString()
            before = content
            val items: MutableList<String?> = CheckListHelper.toCheckListItems(content, true)
            var focusFirst = false
            // 空清单 = 一个空项 + 添加行；空项新格式是 "01"（状态位+层级位），用 isEmptyItem 判断而非旧的 "0"。
            if (items.size == 2 && CheckListHelper.isEmptyItem(items[0])) {
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
                mCheckListAdapter!!.setItemsChangeCallback(CheckListItemsChangeCallback())
                mCheckListAdapter!!.setActionCallback(CheckListActionCallback())
            } else {
                mCheckListAdapter!!.setItems(items)
            }
            mCheckListAdapter!!.setShouldAutoLink(mShouldAutoLink)
            mCheckListAdapter!!.setThingColor(mThing!!.getColor())
            setChecklistExpandShrinkEvent()
            syncDetailContent()

            setMoveChecklistEvent()

            if (focusFirst) {
                focusChecklistItem(0, null, true)
            } else {
                KeyboardUtil.hideKeyboard(currentFocus)
            }
        }
        if (shouldAddToActionList) {
            mActionList!!.addAction(ThingAction(ThingAction.TOGGLE_CHECKLIST, before, null))
        }
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

        adf.setTitle(getString(R.string.set_password_first_title))
        adf.setContent(getString(R.string.warning_should_set_password_first))
        adf.show(supportFragmentManager, AlertDialogFragment.TAG)
    }

    private fun tryToCancelPrivateThing() {
        // 访问即信任（见 docs/adr 与 private-content/decisions.md 取消私密决议）：能进到详情页
        // 就已经通过认证，取消私密不再二次验证，直接取消即可。
        cancelPrivateThingUiAndAddAction()
        if (shouldAddToActionList) {
            mActionList!!.addAction(ThingAction(ThingAction.TOGGLE_PRIVATE, null, null))
        }
    }

    private fun cancelPrivateThingUiAndAddAction() {
        // 取消私密：清掉该记事的会话认证，避免回到列表后同会话重新设私密时被误揭示（与文件夹对称）。
        mThing?.let { ThingManager.getInstance(mApp)?.clearThingPrivacyAuthenticated(it.id) }
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

    private fun updateUndoRedoActionButtonState() {
        val undoItem: MenuItem = mActionbar!!.menu.findItem(R.id.act_undo) ?: return
        undoItem.isEnabled = mActionList!!.canUndo()

        val redoItem: MenuItem = mActionbar!!.menu.findItem(R.id.act_redo)
        redoItem.isEnabled = mActionList!!.canRedo()
    }

    private fun undoOrRedo(action: ThingAction?, undo: Boolean) {
        if (action == null) return
        if (action.getType() == ThingAction.RECEIVE_CONTENT_UPDATE) {
            val state = (if (undo) action.getBefore() else action.getAfter()) as ReceivedEditState
            val written = try {
                ThingFileUpdate.write(this, state.thing, state.alarms)
            } catch (e: Exception) {
                Log.e("TapDetailReceive", "撤销或重做接收更新失败", e)
                // 历史游标已由菜单移动，失败时退回，不能把未执行的动作当作成功。
                if (undo) mActionList!!.redo() else mActionList!!.undo()
                Toast.makeText(this, R.string.tap_update_failed, Toast.LENGTH_LONG).show()
                updateUndoRedoActionButtonState()
                return
            }
            ThingFileUpdate.synchronizeAlarms(this, written)
            ThingManager.getInstance(this)!!.onContentUpdateCommitted(written.before, written.after)
            renderReceivedState(state.copy(thing = written.after))
            publishReceivedUpdate(written.before.type)
            updateUndoRedoActionButtonState()
            return
        }
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
                    mDetailList!!.post {
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
            ThingAction.UPDATE_DETAIL_ATTACHMENT_MEDIA_APPEARANCE ->
                undoOrRedoDetailAttachmentMediaAppearance(action, undo)
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
        val appearanceBefore = action.getExtras()!!.getString(
            ThingAction.KEY_DETAIL_ATTACHMENT_MEDIA_APPEARANCE_BEFORE
        )
        val appearanceAfter = action.getExtras()!!.getString(
            ThingAction.KEY_DETAIL_ATTACHMENT_MEDIA_APPEARANCE_AFTER
        )
        if (undo) {
            if (!atpn.startsWith(AttachmentHelper.AUDIO.toString())) {
                setDetailAttachmentMediaAppearanceFromJson(appearanceBefore, false)
            }
            attachmentTypePathName = atpn
            addAttachment(position)
        } else {
            if (atpn.startsWith(AttachmentHelper.AUDIO.toString())) {
                notifyAudioAttachmentsChanged(false, position)
            } else {
                setDetailAttachmentMediaAppearanceFromJson(appearanceAfter, false)
                notifyImageAttachmentsChanged(false, position)
            }
        }
    }

    private fun undoOrRedoDetailAttachmentMediaAppearance(action: ThingAction, undo: Boolean) {
        val json = (if (undo) action.getBefore() else action.getAfter()) as? String
        setDetailAttachmentMediaAppearanceFromJson(json, true)
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
            normalSnackbar().setMessage(getString(R.string.alert_cannot_share_empty_thing))
            mFlRoot!!.postDelayed(mShowNormalSnackbar, KeyboardUtil.HIDE_DELAY.toLong())
            return
        }
        if (mTapShareOverlay != null) {
            return
        }
        // 分享界面从页面上截取当前的内容抬升成卡片：键盘开着时先收起，等布局稳定再截
        val imeVisible = ViewCompat.getRootWindowInsets(window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        if (imeVisible) {
            KeyboardUtil.hideKeyboard(currentFocus)
            mFlRoot!!.postDelayed({
                // 等键盘收起的这段时间里页面可能退到了后台：那时窗口没有可截的画面，不打开
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    openShareOverlay()
                }
            }, KeyboardUtil.HIDE_DELAY.toLong())
        } else {
            openShareOverlay()
        }
    }

    /**
     * 分享界面（决策 2026-09-24「卡片在上、面板在下」）：这条记事以首页卡片的样子从页面里升起，
     * 页面内容保持显示；下方是碰一碰与三项。三项的区别不是产物形态而是接收方是谁：前两项给任何人
     * 看，「记事文件」只有装了完事儿的人能用，但它是唯一能还原成一条记事的产物。
     */
    private fun openShareOverlay() {
        if (mTapShareOverlay != null || mThing == null) {
            return
        }
        val accentBackground = getAccentBackground() ?: ThingBackground.pure(getAccentColor())
        val snapshot = buildShareSnapshot()
        mTapShareOverlay = TapShareOverlay.show(this, TapShareOverlay.Request(
            listOf(snapshot), emptyList(), accentBackground,
            object : TapShareOverlay.Host {
                override val longScreenshotAvailable: Boolean = true

                override fun shareTextAndImages() {
                    SendInfoHelper.shareThing(this@DetailActivity, buildShareSnapshot())
                }

                override fun shareLongScreenshot() {
                    shareThingInScreenshot()
                }

                override fun shareThingFile() {
                    // 不鉴权：能看到内容说明已通过鉴权（访问即信任），与另两项一致
                    ThingFileSharer.share(
                        this@DetailActivity, accentBackground, listOf(buildShareSnapshot())
                    )
                }

                override fun onShareClosed(sentByTap: Boolean) {
                    mTapShareOverlay = null
                }
            }
        ))
    }

    /**
     * 首页选择模式里选一条点「长截图」时，打开这条记事并在这里生成（长截图要用详情页排好版的
     * 视图）。只执行一次：旋转屏幕重建时不再生成。
     */
    private fun shareLongScreenshotIfRequested() {
        if (!intent.getBooleanExtra(KEY_SHARE_LONG_SCREENSHOT, false)) {
            return
        }
        intent.removeExtra(KEY_SHARE_LONG_SCREENSHOT)
        mFlRoot?.postDelayed({
            if (!isFinishing && !isDestroyed && mThing != null) {
                shareThingInScreenshot()
            }
        }, LONG_SCREENSHOT_DELAY_MS)
    }

    /**
     * 分享用的快照：界面上当前的标题、正文、附件、外观与背景。
     *
     * 界面上的修改只在保存或 onPause 时才写回 [mThing]，而分享对话框不触发 onPause，直接用
     * [mThing] 分享出去的是上次保存的版本。快照不写回 [mThing]、不落库、不登记待删除的附件。
     *
     * 提醒与习惯仍以数据库里的为准：它们的改动要经过类型判定与确认对话框，只在离开详情页时生效。
     */
    private fun buildShareSnapshot(): Thing {
        val snapshot = Thing(mThing!!)
        snapshot.title = getThingTitle()
        snapshot.content = getThingContent()
        val attachment = currentAttachmentStr()
        snapshot.attachment = attachment
        snapshot.thingCardSpanMode = mThingCardSpanMode
        snapshot.thingCardImagePlacement = mThingCardImagePlacement
        snapshot.detailAttachmentMediaAppearance = normalizedDetailAttachmentMediaAppearance(attachment)
        getAccentBackground()?.let { snapshot.setBackground(it) }
        return snapshot
    }

    private fun canTapUpdate(): Boolean {
        if (deferTapUpdateSetup) return false
        val thing = mThing ?: return false
        return mType == UPDATE && Thing.isRealThingType(thing.type) &&
            (thing.state == Thing.UNDERWAY || thing.state == Thing.FINISHED) &&
            ThingFolderDAO.getInstance(this)?.isEffectivelyDeleted(thing.folderId) != true &&
            com.ywwynm.everythingdone.taptransfer.TapTransfer.isEntryAvailable(this)
    }

    private fun finishDeferredTapUpdateSetup() {
        if (!deferTapUpdateSetup) return
        deferTapUpdateSetup = false
        if (isFinishing || isDestroyed) return
        setupTapUpdate()
        // 只补齐溢出菜单状态，不重建正在交接的可见工具栏按钮。
        mActionbar?.menu?.let(::updateTapUpdateMenu)
        updateUndoRedoActionButtonState()
    }

    private fun setupTapUpdate() {
        if (tapUpdateReceiver != null || !canTapUpdate()) return
        tapUpdateReceiver = TapDetailReceiveController(this,
            object : TapDetailReceiveController.Host {
                override val container: FrameLayout get() = mFlRoot!!
                override val recyclerView: RecyclerView get() = mDetailList!!
                override val actionbar: View get() = mActionbar!!
                override val foregroundColor: Int get() = mEtTitle!!.currentTextColor
                override val accentBackground: ThingBackground get() =
                    getAccentBackground() ?: ThingBackground.pure(getAccentColor())
                override fun canReceive() = canTapUpdate()
                override fun canPullToReceive() = canTapUpdate() && mTapShareOverlay == null &&
                    mDetailCropPreviewModal == null && !isChecklistMoveMode() &&
                    ViewCompat.getRootWindowInsets(mFlRoot!!)?.isVisible(WindowInsetsCompat.Type.ime()) != true
                override fun existingAttachments(): List<TapFileTransfer.LocalAttachment> {
                    val snapshot = if (mEditable) buildShareSnapshot() else Thing(mThing!!)
                    retainReceivedAttachments(snapshot)
                    return snapshot.attachment.orEmpty().split(AttachmentHelper.SIGNAL).drop(1).mapNotNull {
                        val type = it.firstOrNull()?.digitToIntOrNull() ?: return@mapNotNull null
                        if (it.length < 2) null else TapFileTransfer.LocalAttachment(type, File(it.substring(1)))
                    }
                }
                override fun prepareForReceive() {
                    KeyboardUtil.hideKeyboard(window)
                    mEtTitle?.clearFocus()
                    mEtContent?.clearFocus()
                    mDetailList?.stopScroll()
                    mAudioAttachmentAdapter?.stopPlaying()
                    cancelDetailCropPreview()
                    settleChecklistDragBeforePause()
                }
                override fun needsSettingsChoice(pending: PendingThing): Boolean =
                    mThing?.type != Thing.NOTE || getThingTypeAfter() != Thing.NOTE ||
                        pending.thing.type != Thing.NOTE
                override fun isContentReadyForSnapshot(): Boolean {
                    val list = mDetailList!!
                    if (list.isLayoutRequested || list.isComputingLayout) return false
                    return (0 until list.childCount).none { index ->
                        val row = list.getChildAt(index)
                        row.bottom > 0 && row.top < list.height &&
                            row.findViewById<View>(R.id.pb_image_attachment)?.visibility == View.VISIBLE
                    }
                }
                override fun apply(prepared: ThingFileUpdate.Prepared, settings: ThingFileUpdate.Settings) {
                    applyReceivedContent(prepared, settings)
                }
            })
    }

    private fun startTapUpdate() {
        setupTapUpdate()
        tapUpdateReceiver?.start()
    }

    private fun captureReceivedState(includeAlarms: Boolean): ReceivedEditState {
        val thing = if (mEditable) buildShareSnapshot() else Thing(mThing!!)
        return ReceivedEditState(thing,
            if (includeAlarms) ThingFileUpdate.captureAlarms(this, thing.id) else null,
            if (mEditable) ReminderHabitParams(rhParams) else null,
            cbQuickRemind!!.isChecked,
            if (mEditable) getQuickRemindPickedIndexForRecreate() else -1,
            tvQuickRemind!!.text.toString())
    }

    private fun retainReceivedAttachments(vararg things: Thing) {
        things.forEach { thing ->
            thing.attachment.orEmpty().split(AttachmentHelper.SIGNAL).drop(1)
                .filter { it.length > 1 }.forEach { tapHistoryAttachments.add(it.substring(1)) }
        }
        mApp!!.retainAttachmentsForHistory(tapHistoryOwner, tapHistoryAttachments)
    }

    private fun applyReceivedContent(prepared: ThingFileUpdate.Prepared, settings: ThingFileUpdate.Settings) {
        check(canTapUpdate())
        prepared.ensureFilesAvailable()
        val replaceSettings = settings == ThingFileUpdate.Settings.USE_RECEIVED
        val before = captureReceivedState(replaceSettings)
        val desired = ThingFileUpdate.merge(before.thing, prepared.content, settings)
        retainReceivedAttachments(before.thing, desired)
        val selectedAlarms = if (replaceSettings)
            ThingFileUpdate.receivedAlarms(prepared.pending, desired.id) else null
        val written = ThingFileUpdate.write(this, desired, selectedAlarms)
        prepared.markCommitted()
        val after = if (replaceSettings) ReceivedEditState(written.after, written.alarms)
            else before.copy(thing = written.after)
        mActionList!!.addAction(ThingAction(ThingAction.RECEIVE_CONTENT_UPDATE, before, after))
        ThingFileUpdate.synchronizeAlarms(this, written)
        ThingManager.getInstance(this)!!.onContentUpdateCommitted(written.before, written.after)
        renderReceivedState(after)
        publishReceivedUpdate(written.before.type)
    }

    /** 重建当前详情的分区，不重建 Activity，也不清空已有编辑历史。 */
    private fun renderReceivedState(state: ReceivedEditState) {
        val recordActions = shouldAddToActionList
        shouldAddToActionList = false
        try {
            mAudioAttachmentAdapter?.stopPlaying()
            mAttachmentPlaybackController?.dispose()
            mAttachmentPlaybackController = DetailAttachmentPlaybackController(mDetailList!!)
            mImageAttachmentAdapter = null
            mAudioAttachmentAdapter = null
            mCheckListAdapter = null
            mChecklistVisible = false
            mLlMoveChecklist!!.visibility = View.GONE
            mImageCover!!.visibility = View.GONE
            mEtTitle!!.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
            mThing = Thing(state.thing)
            mThingIndex = ThingManager.getInstance(this)!!.getPosition(mThing!!.id)
            mReminder = ReminderDAO.getInstance(this)!!.getReminderById(mThing!!.id)
            mHabit = if (mThing!!.type == Thing.HABIT) HabitDAO.getInstance(this)!!.getHabitById(mThing!!.id) else null
            mThingCardSpanMode = mThing!!.thingCardSpanMode
            mThingCardImagePlacement = mThing!!.thingCardImagePlacement
            mDetailAttachmentMediaAppearance = mThing!!.detailAttachmentMediaAppearance
            mChangeBackgroundTo = mThing!!.getBackground()
            mChangeColorTo = mThing!!.getColor()
            cbQuickRemind!!.isChecked = false
            rhParams = ReminderHabitParams()
            f<View>(R.id.ll_bottom_bar_detail).visibility = View.VISIBLE
            val listParams = mDetailList!!.layoutParams as ViewGroup.MarginLayoutParams
            listParams.bottomMargin = (56 * resources.displayMetrics.density).toInt()
            mDetailList!!.layoutParams = listParams
            initUI()
            syncDetailContent()
            if (mEditable) createDateTimeDialogFragment()
            state.draftParams?.let {
                rhParams = ReminderHabitParams(it)
                if (state.draftPicked >= 0) quickRemindPicker?.pickForUI(state.draftPicked)
                tvQuickRemind!!.text = state.draftDescription
                cbQuickRemind!!.isChecked = state.draftChecked
            }
            // 初始化控件时读到的是已保存设置，类型提示与无障碍说明须按恢复后的草稿刷新。
            if (mEditable) updateBackButton()
            cbQuickRemind!!.contentDescription = getString(R.string.l10n_share_reminder, tvQuickRemind!!.text)
            mDetailList!!.scrollToPosition(0)
            mActionbar!!.menu.clear()
            onCreateOptionsMenu(mActionbar!!.menu)
            updateDescriptions(getAccentColor())
            recordRenderedThingSnapshot()
        } finally { shouldAddToActionList = recordActions }
    }

    private var tapUpdateRevision = 0

    private fun publishReceivedUpdate(typeBefore: Int) {
        val result = if (typeBefore == mThing!!.type) Def.Communication.RESULT_UPDATE_THING_DONE_TYPE_SAME
            else Def.Communication.RESULT_UPDATE_THING_DONE_TYPE_DIFFERENT
        val update = Intent(Def.Communication.BROADCAST_ACTION_UPDATE_MAIN_UI).apply {
            setPackage(packageName)
            putExtra("tap_update_origin", tapUpdateOrigin)
            putExtra("tap_update_revision", ++tapUpdateRevision)
            putExtra(Def.Communication.KEY_RESULT_CODE, result)
            putExtra(Def.Communication.KEY_TYPE_BEFORE, typeBefore)
            putExtra(Def.Communication.KEY_THING, mThing)
        }
        putMainListPositions(update)
        App.setLastUpdateUiIntent(update)
        setResult(result, update)
        sendBroadcast(update)
        AppWidgetHelper.updateSingleThingAppWidgets(this, mThing!!.id)
        AppWidgetHelper.updateThingsListAppWidgetsForType(this, typeBefore)
        if (typeBefore != mThing!!.type) AppWidgetHelper.updateThingsListAppWidgetsForType(this, mThing!!.type)
        SystemNotificationUtil.tryToCreateThingOngoingNotification(this)
    }

    private fun shareThingInScreenshot() {
        val ldf = LoadingDialogFragment()
        val color = getAccentColor()
        ldf.setAccentBackground(getAccentBackground() ?: ThingBackground.pure(color))
        ldf.setTitle(getString(R.string.please_wait))
        ldf.setContent(getString(R.string.generating_screenshot))
        ldf.show(supportFragmentManager, LoadingDialogFragment.TAG)

        mAudioAttachmentAdapter?.stopPlaybackForScreenshot()
        val renderer = DetailScreenshotRenderer(
            this, mDetailContent, getAccentBackground() ?: ThingBackground.pure(color), mEditable,
            mCheckListAdapter.takeIf { mChecklistVisible }, mImageAttachmentAdapter?.getItems(),
            mDetailAttachmentMediaAppearance, mMaxSpanImage, mAudioAttachmentAdapter?.getItems(),
            mSpanAudio, mShouldAutoLink
        )
        ScreenshotHelper.showTypeInfo(renderer.typeInfo, mThing!!.id, mThing!!.type,
            getThingTypeAfter(), mThing!!.state, rhParams)
        renderer.render { bitmap ->
            ScreenshotHelper.saveScreenshot(this, bitmap, object : ScreenshotHelper.ShareCallback(
                this, ldf, SendInfoHelper.getShareThingTitle(this, mThing)
            ) {
                override fun onTaskDone(file: File?) {
                    if (isFinishing || isDestroyed) return
                    super.onTaskDone(file)
                    if (file == null) Toast.makeText(this@DetailActivity,
                        R.string.error_generating_screenshot, Toast.LENGTH_SHORT).show()
                }
            })
        }
    }

    private fun canShareThing(): Boolean {
        var canShare = true
        if (mEtTitle!!.text.toString().isEmpty()
            && !hasImageAttachments
            && !hasAudioAttachments
        ) {
            if (mEtContent!!.isVisible) {
                if (mEtContent!!.text.toString().isEmpty()) {
                    canShare = false
                }
            } else if (mChecklistVisible) {
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
        } else if (mChecklistVisible
            && mCheckListAdapter != null
        ) {
            content = CheckListHelper.toContentStr(
                CheckListHelper.toCheckListStr(mCheckListAdapter!!.getItems()), "- [ ] ", "- [x] "
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
        detailTransition?.finish()
        detailTransition = null
        creationTransition?.finish()
        creationTransition = null
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

        syncDetailContent()
        if (hasImageAttachments) refreshImageAttachmentLayout(true)
        if (hasAudioAttachments) mAudioAttachmentAdapter!!.notifyDataSetChanged()
    }

    private fun handleAppearanceModeChanged() {
        val quickPickedIndex = if (mEditable) getQuickRemindPickedIndexForRecreate() else -1

        delegate.applyDayNight()
        mActionbar?.dismissPopupMenus()
        dismissDetailDialogFragmentsForAppearance()

        if (mEditable) {
            dismissEditableOverlays()
            createEditablePickers()
            setQuickRemindPickerEvent()
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
        mBgEditorSheet?.dismissAllowingStateLoss()
        quickRemindPicker?.dismiss()
        mNormalSnackbar?.dismiss()
    }

    private fun dismissDetailDialogFragmentsForAppearance() {
        cancelDetailCropPreview()
        // 录音 Dialog 不参与通用 dismiss：它背后的会话可能正在录音，dismiss 会当作用户
        // 取消而丢弃录音（系统定时深浅色切换时静默发生）。它对深浅色的适配走原地换肤。
        (supportFragmentManager.findFragmentByTag(AudioRecordDialogFragment.TAG)
            as? AudioRecordDialogFragment)?.onHostAppearanceChanged()
        val tags = arrayOf(
            AddAttachmentDialogFragment.TAG,
            AlertDialogFragment.TAG,
            AttachmentInfoDialogFragment.TAG,
            AudioPlayDialogFragment.TAG,
            CameraColorSamplingDialogFragment.TAG,
            ChooserDialogFragment.TAG,
            ColorInfoDialogFragment.TAG,
            DateTimeDialogFragment.TAG,
            MediaCropAppearanceDialogFragment.TAG,
            HabitDetailDialogFragment.TAG,
            HabitRecordDialogFragment.TAG,
            LoadingDialogFragment.TAG,
            LongTextDialogFragment.TAG,
            PatternLockDialogFragment.TAG,
            ThingDoingDialogFragment.TAG,
            TwoOptionsDialogFragment.TAG
        )
        for (tag in tags) {
            val fragment = supportFragmentManager.findFragmentByTag(tag)
            if (fragment is DialogFragment) {
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
        mark("detail-resume")
        refreshFromExternalUpdateIfNeeded()
        shareLongScreenshotIfRequested()
        // 用户可能刚从设置页改了 Detail Autoplay 档位。
        mAttachmentPlaybackController?.onResume()
        openAudioRecordingDialogFromNotification(intent)
        maybeRestoreStoppedRecordingSession()
    }

    /**
     * 打开的记事有待处理的停止态录音（含进程被回收后的持久化记录）时自动恢复停止态
     * Dialog——用户划掉"已停止"通知后，这是找回录音的兜底入口。录音进行中不在此列
     * （通知与图标接力已覆盖，编辑记事不应被打断）。
     */
    private fun maybeRestoreStoppedRecordingSession() {
        val thingId = currentThingId()
        if (thingId == -1L) return
        if (AudioInputPreferences
                .stoppedSessionThingId(this) != thingId
        ) {
            return
        }
        if (supportFragmentManager.findFragmentByTag(AudioRecordDialogFragment.TAG) != null) return
        mFlRoot?.post {
            if (!isFinishing &&
                supportFragmentManager.findFragmentByTag(AudioRecordDialogFragment.TAG) == null
            ) {
                AudioRecordDialogFragment().show(
                    supportFragmentManager,
                    AudioRecordDialogFragment.TAG
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        ThingDetailReturnTransition.forgetOrigin(this)
        super.onNewIntent(intent)
        setIntent(intent)
        openAudioRecordingDialogFromNotification(intent)
    }

    private fun openAudioRecordingDialogFromNotification(sourceIntent: Intent?) {
        if (sourceIntent?.getBooleanExtra(
                AudioRecordingService.EXTRA_OPEN_RECORDING_DIALOG,
                false
            ) != true
        ) return
        sourceIntent.removeExtra(
            AudioRecordingService.EXTRA_OPEN_RECORDING_DIALOG
        )
        // 会话存在性同时看进程内状态与持久化停止态：进程被回收后 activeSession 为
        // false，但待处理录音仍在偏好里，绑定服务即可恢复。
        val active = AudioRecordingService.activeSession
        val sessionThingId = AudioRecordingService
            .activeSessionThingId
            .takeIf { active && it != -1L }
            ?: AudioInputPreferences
                .stoppedSessionThingId(this)
        if (!active && sessionThingId == -1L) return
        // CLEAR_TOP|SINGLE_TOP 可能复用一个正展示其他记事的实例（用户此间打开过别的记事），
        // 直接在它上面挂录音 Dialog 会把录音附件保存到错误的记事。此时按会话的返回入口
        // 重启自身；会话属于新建记事（id 记为 -1）时不校验，避免新建 id 每次再生成导致
        // 重启循环。
        if (sessionThingId != -1L && mThing != null && mThing!!.id != sessionThingId) {
            val restart = AudioRecordingService
                .activeReturnIntent?.let(::Intent)
                ?: getOpenIntentForUpdate(this, null, sessionThingId, -1)
            restart.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            restart.putExtra(
                AudioRecordingService.EXTRA_OPEN_RECORDING_DIALOG,
                true
            )
            finish()
            startActivity(restart)
            return
        }
        if (supportFragmentManager.findFragmentByTag(AudioRecordDialogFragment.TAG) != null) return
        mFlRoot?.post {
            if (!isFinishing &&
                supportFragmentManager.findFragmentByTag(AudioRecordDialogFragment.TAG) == null
            ) {
                AudioRecordDialogFragment().show(
                    supportFragmentManager,
                    AudioRecordDialogFragment.TAG
                )
            }
        }
    }

    override fun onPause() {
        detailTransition?.finish()
        detailTransition = null
        cancelDetailCropPreview()
        creationTransition?.finish()
        creationTransition = null
        settleChecklistDragBeforePause()
        super.onPause()
        if (mEditable && mExecutor != null && !dontSaveAfterOnPause
            && FrequentSettings.getBoolean(Def.Meta.KEY_AUTO_SAVE_EDITS)
        ) {
            val b = saveAfterOnPause()
            if (!savedAfterOnPause) {
                savedAfterOnPause = b
            }
            if (b) {
                // 新建记事首次入库后，进行中的录音会话把归属从 -1 升级为正式 id；
                // onPause 恰好先于一切后台化动作，升级窗口覆盖录音后台化之前。
                (supportFragmentManager.findFragmentByTag(AudioRecordDialogFragment.TAG)
                    as? AudioRecordDialogFragment)?.onHostThingSaved()
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
        applyDetailAttachmentMediaAppearanceDraftToThing(attachment)
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
            putShortcutCreateFolderProjection(intent)

            resultCode = Def.Communication.RESULT_CREATE_THING_DONE
            intent.putExtra(Def.Communication.KEY_RESULT_CODE, resultCode)

            val change: Boolean = ThingManager.getInstance(mApp)!!.create(mThing, true, true)
            intent.putExtra(Def.Communication.KEY_CALL_CHANGE,  change)
            intent.putExtra(Def.Communication.KEY_CREATED_DONE, true)

            sendBroadCastToUpdateMainUI(intent, resultCode)
        } else {
            // only update color, title, content, attachment and update time now
            if (mType == CREATE) {
                mThingIndex = App.getThingAndPosition(mApp, mThing!!.id, -1).second ?: -1
            }
            @Thing.Type val typeBefore: Int = mThing!!.type
            var updateResult = -1
            if (mThingIndex != -1) {
                putMainListPositions(intent)
                updateResult = ThingManager.getInstance(mApp)!!.update(
                    typeBefore, mThing, mThingIndex, true
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
        ThingDetailReturnTransition.forgetOrigin(this)
        detailTransition?.finish()
        detailTransition = null
        tapUpdateReceiver?.exit()
        mApp?.releaseAttachmentsForHistory(tapHistoryOwner)
        mDetailCropPreviewLoader?.close()
        mDetailCropPreviewLoader = null
        mAttachmentPlaybackController?.dispose()
        mToolbarContrast?.dispose()
        stopChecklistDragAutoScroll()
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
        // androidx 的 ActivityResult API（录音 Dialog 的 MediaProjection 授权走的就是它）依赖
        // 这条 super 链把结果分发给 registerForActivityResult 的回调；不调 super 时回调永远
        // 不执行，授权结果被静默丢弃。registry 的 requestCode 从 0x10000 起，与下面的旧式
        // 常量不冲突。
        super.onActivityResult(requestCode, resultCode, data)
        val legacyAttachmentRequest = requestCode == Def.Communication.REQUEST_TAKE_PHOTO
                || requestCode == Def.Communication.REQUEST_CAPTURE_VIDEO
                || requestCode == Def.Communication.REQUEST_CHOOSE_MEDIA_FILE
        if (resultCode == RESULT_OK && legacyAttachmentRequest) {
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
                    normalSnackbar().setMessage(R.string.error_cannot_add_from_network)
                    mFlRoot!!.postDelayed(mShowNormalSnackbar, KeyboardUtil.HIDE_DELAY.toLong())
                    return
                }
                Log.d(TAG, "chooseMediaFile pathName=$pathName postfix=" + FileUtil.getPostfix(pathName) + " mimeFallback=$mimeFallback")
                attachmentTypePathName = getTypePathName(pathName, mimeFallback, uri)
                Log.d(TAG, "chooseMediaFile attachmentTypePathName=$attachmentTypePathName")
                if (attachmentTypePathName == null) {
                    Log.w(TAG, "chooseMediaFile getTypePathName returned null, showing error")
                    normalSnackbar().setMessage(R.string.error_unsupported_file_type)
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
        } else if (requestCode == Def.Communication.REQUEST_ACTIVITY_IMAGE_VIEWER
                && resultCode == Def.Communication.RESULT_UPDATE_IMAGE_DONE) {
            val items: List<String?> = data!!.getStringArrayListExtra(
                Def.Communication.KEY_TYPE_PATH_NAME
            )!! as List<String?>
            if (mImageAttachmentAdapter!!.getItems() != items) {
                mImageAttachmentAdapter!!.setItems(items)
                refreshImageAttachmentLayout(true)
            }
        }
    }

    fun showNormalSnackbar(stringRes: Int) {
        normalSnackbar().setMessage(stringRes)
        normalSnackbar().show()
    }

    /**
     * 录音会话属于另一条记事时的入口拦截提示："前往"跳回该会话的详情页并恢复录音
     * Dialog。不拦截的话，第二个录音 Dialog 会接管会话并把录音保存到错误的记事。
     */
    fun showRecordingBusySnackbar() {
        val snackbar = Snackbar(
            mApp!!, Snackbar.UNDO, mFlRoot!!, null
        )
        snackbar.setMessage(R.string.audio_recording_busy_other_thing)
        snackbar.setUndoText(R.string.audio_recording_go_to)
        snackbar.setUndoListener {
            // 进程被回收后服务的返回入口为空，按持久化停止态的记事 id 构造标准打开 intent。
            val target = AudioRecordingService
                .activeReturnIntent?.let(::Intent)
                ?: AudioInputPreferences
                    .stoppedSessionThingId(this)
                    .takeIf { it != -1L }
                    ?.let { getOpenIntentForUpdate(this, null, it, -1) }
                ?: return@setUndoListener
            target.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            target.putExtra(
                AudioRecordingService.EXTRA_OPEN_RECORDING_DIALOG,
                true
            )
            snackbar.dismiss()
            startActivity(target)
        }
        snackbar.show()
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
        val params = mDetailList!!.layoutParams as FrameLayout.LayoutParams
        if (mScrollViewHasStatusBarMarginTop) {
            val mt: Int = (screenDensity * 56).toInt() + mStatusBarTopInset
            params.setMargins(0, mt, 0, params.bottomMargin)
        } else {
            params.setMargins(0, 0, 0, params.bottomMargin)
        }
    }

    private fun initImageAttachmentUI(items: List<String?>) {
        mImageAttachmentAdapter = ImageAttachmentAdapter(
            this, mEditable, items, ImageAttachmentClickCallback(),
            if (mEditable) ImageAttachmentRemoveCallback() else null,
            if (mEditable) ImageAttachmentAppearanceCallback() else null,
            mMaxSpanImage, mDetailAttachmentMediaAppearance
        )
        mImageAttachmentAdapter!!.setAccentBackground(getAccentBackground())
        mAttachmentPlaybackController?.setAdapter(mImageAttachmentAdapter)
        refreshImageAttachmentLayout(false)
        if (mEditable) ensureDetailTouchHelper()
    }

    private fun notifyImageAttachmentsChanged(add: Boolean, position: Int) {
        val items = mImageAttachmentAdapter!!.getItems()!! as MutableList<String?>
        if (add) items.add(position, attachmentTypePathName) else items.removeAt(position)
        refreshImageAttachmentLayout(true)
    }

    private fun refreshImageAttachmentLayout(notify: Boolean) {
        val adapter = mImageAttachmentAdapter ?: return
        adapter.setMaxSpan(mMaxSpanImage)
        adapter.setDetailAttachmentMediaAppearance(mDetailAttachmentMediaAppearance)
        if (hasImageAttachments) setImageCover() else mImageCover!!.visibility = View.GONE
        setScrollViewMarginTop(!hasImageAttachments)
        // 先发送数量变化通知，再让 ConcatAdapter 重算分区和 span。
        if (notify) adapter.notifyDataSetChanged()
        syncDetailContent()
        if (notify) mAttachmentPlaybackController?.onItemsChanged()
    }

    private fun syncDetailContent() {
        mAudioAttachmentAdapter?.setLayoutSpan(mSpanAudio, resources.displayMetrics.widthPixels)
        mDetailContent.update(mImageAttachmentAdapter, mCheckListAdapter, mChecklistVisible,
            mAudioAttachmentAdapter, mSpanAudio)
    }

    private fun setDetailAttachmentMediaAppearanceFromJson(
        json: String?,
        notify: Boolean
    ) {
        mDetailAttachmentMediaAppearance = DetailAttachmentMediaAppearance.fromJson(json)
            ?: DetailAttachmentMediaAppearance.default()
        if (mImageAttachmentAdapter != null) {
            refreshImageAttachmentLayout(notify)
        }
    }

    private fun applyDetailAttachmentMediaAppearance(
        appearance: DetailAttachmentMediaAppearance
    ) {
        val beforeJson = mDetailAttachmentMediaAppearance.toJson()
        val afterJson = appearance.toJson()
        if (beforeJson == afterJson) return

        mDetailAttachmentMediaAppearance = appearance
        refreshImageAttachmentLayout(true)

        if (shouldAddToActionList) {
            mActionList!!.addAction(
                ThingAction(
                    ThingAction.UPDATE_DETAIL_ATTACHMENT_MEDIA_APPEARANCE,
                    beforeJson,
                    afterJson
                )
            )
        }
    }

    private fun initAudioAttachmentUI(items: List<String?>) {
        mAudioAttachmentAdapter = AudioAttachmentAdapter(
            this, getAccentColor(), mEditable, items,
            if (mEditable) AudioAttachmentRemoveCallback() else null
        )
        mAudioAttachmentAdapter!!.setAccentBackground(getAccentBackground())
        syncDetailContent()
        if (mEditable) ensureDetailTouchHelper()
    }

    private fun notifyAudioAttachmentsChanged(add: Boolean, position: Int) {
        val adapter = mAudioAttachmentAdapter!!
        val items = adapter.getItems()!! as MutableList<String?>
        val playing = adapter.getPlayingIndex()
        if (add) {
            if (playing >= position) adapter.setPlayingIndex(playing + 1)
            items.add(position, attachmentTypePathName)
            adapter.notifyItemInserted(position)
        } else {
            if (playing == position) adapter.stopPlaying()
            else if (playing > position) adapter.setPlayingIndex(playing - 1)
            items.removeAt(position)
            adapter.notifyItemRemoved(position)
        }
        syncDetailContent()
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
        mActionbar!!.setOnClickListener { mDetailList!!.smoothScrollToPosition(0) }
        mDetailList!!.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updateScrollChrome()
            }
        })
        mDetailList!!.viewTreeObserver.addOnGlobalLayoutListener { updateScrollChrome() }
    }

    private fun updateScrollChrome() {
        val list = mDetailList ?: return
        val titlePosition = mDetailContent.positionOf(R.id.et_title)
        val title = mDetailContent.layoutManager.findViewByPosition(titlePosition)
        val pastTitle = mDetailContent.layoutManager.findFirstVisibleItemPosition() > titlePosition
        if (hasImageAttachments) {
            // 标题前沿就是媒体区末沿 + 原来的 12dp 间距，避免用变高行的估计滚动距离。
            val imageBottom = title?.let { it.top - 12 * screenDensity }
                ?: if (pastTitle) Float.NEGATIVE_INFINITY else Float.POSITIVE_INFINITY
            val toolbarBottom = 56 * screenDensity + mStatusBarTopInset
            mActionBarShadow!!.alpha = ((toolbarBottom - 20 * screenDensity - imageBottom) /
                (20 * screenDensity)).coerceIn(0f, 1f)
            val alpha = (((toolbarBottom - 8 * screenDensity - imageBottom) /
                (16 * screenDensity)).coerceIn(0f, 1f) * 255).toInt()
            val color = DisplayUtil.getTransparentColor(getAccentColor(), alpha)
            mStatusBar!!.setBackgroundColor(color)
            mActionbar!!.setBackgroundColor(color)
        } else {
            val offset = title?.let { -it.top.toFloat() }
                ?: if (pastTitle) Float.POSITIVE_INFINITY else 0f
            mActionBarShadow!!.alpha = ((offset - 14 * screenDensity) /
                (20 * screenDensity)).coerceIn(0f, 1f)
        }
        updateBottomBarShadow()
    }

    private fun updateBottomBarShadow() {
        val list = mDetailList ?: return
        if (!f<View>(R.id.ll_bottom_bar_detail).isVisible) {
            f<View>(R.id.bottom_bar_shadow)!!.alpha = 0f
            return
        }
        val last = mDetailContent.layoutManager.findViewByPosition(mDetailContent.adapter.itemCount - 1)
        val remaining = last?.let { it.bottom + list.paddingBottom - list.height }
            ?: if (list.canScrollVertically(1)) Int.MAX_VALUE else 0
        f<View>(R.id.bottom_bar_shadow)!!.alpha =
            ((remaining - 16 * screenDensity) / (24 * screenDensity)).coerceIn(0f, 1f)
    }

    private fun revealFocusedEditor() {
        val editor = currentFocus as? EditText ?: return
        val layout = editor.layout ?: return
        val line = layout.getLineForOffset(editor.selectionEnd.coerceIn(0, editor.length()))
        val rect = Rect(0, layout.getLineTop(line) + editor.totalPaddingTop,
            editor.width, layout.getLineBottom(line) + editor.totalPaddingTop)
        rect.inset(0, -(16 * screenDensity).toInt())
        editor.requestRectangleOnScreen(rect, false)
    }

    private fun focusChecklistItem(position: Int, cursor: Int?, keyboard: Boolean) {
        val adapter = mCheckListAdapter ?: return
        val global = mDetailContent.globalPosition(adapter, position)
        if (global == RecyclerView.NO_POSITION) return
        val request = ++mChecklistFocusRequest
        val list = mDetailList!!
        if (mDetailContent.holder(adapter, position) == null) list.scrollToPosition(global)
        fun apply(attempt: Int) {
            if (request != mChecklistFocusRequest || !mChecklistVisible || adapter !== mCheckListAdapter) return
            val holder = mDetailContent.holder(adapter, position) as? CheckListAdapter.EditTextHolder
            if (holder == null) {
                if (attempt < 4) list.postOnAnimation { apply(attempt + 1) }
                return
            }
            val editor = holder.et ?: return
            editor.requestFocus()
            if (cursor != null) editor.setSelection(cursor.coerceIn(0, editor.length()))
            if (keyboard) KeyboardUtil.showKeyboard(editor)
            revealFocusedEditor()
        }
        list.post { apply(0) }
    }

    private fun showThingBackgroundEditor() {
        val sheet = ThingBackgroundEditorBottomSheet()
        sheet.setInitialBackground(getAccentBackground())
        sheet.setOnBackgroundChangedListener { bg -> previewColorEditorBackground(bg) }
        sheet.setOnPickFromWorldListener { slot ->
            mPendingWorldSlot = slot
            openCameraColorSampler()
        }
        sheet.setOnResult { confirmed ->
            val from = mColorEditorBgFrom
            if (confirmed) {
                val finalBg = getAccentBackground()
                if (from != null && finalBg != null && from != finalBg && shouldAddToActionList) {
                    mActionList!!.addAction(ThingAction(ThingAction.UPDATE_COLOR, from, finalBg))
                    updateUndoRedoActionButtonState()
                }
            } else {
                // 取消/返回/点外部：回到打开时的颜色，不记 undo。
                if (from != null) previewColorEditorBackground(from)
            }
            mBgEditorSheet = null
        }
        sheet.tryShow(supportFragmentManager, ThingBackgroundEditorBottomSheet.TAG) {
            mBgEditorSheet = sheet
            // 被受理后才保存会话快照；重复请求不得覆盖仍在使用的编辑器。
            mColorEditorBgFrom = getAccentBackground()
        }
    }

    /** 编辑器实时预览：立即套用到屏幕背景（不带动画、不记 undo），由会话结束统一记一条。 */
    private fun previewColorEditorBackground(bg: ThingBackground) {
        mChangeColorTo = bg.representativeColor()
        mChangeBackgroundTo = bg
        mLastAnimatedBackground = bg
        renderCameraPreviewBackground(bg)
    }

    private fun showColorInfoDialog() {
        val df = ColorInfoDialogFragment()
        df.setThingBackground(getAccentBackground())
        df.show(supportFragmentManager, ColorInfoDialogFragment.TAG)
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
        val df = CameraColorSamplingDialogFragment()
        df.setInitialColor(getAccentColor())
        df.setOnColorListener(object : CameraColorSamplingDialogFragment.OnColorListener {
            override fun onPreviewColor(color: Int) {
                // 采样过程只在相机 dialog 内预览。
            }

            override fun onUseColor(color: Int) {
                // 回流到编辑器对应色区，再由编辑器实时预览；undo 由编辑器会话结束统一记。
                mBgEditorSheet?.applyWorldColor(mPendingWorldSlot, color)
            }

            override fun onCancelColorSampling() {
            }
        })
        df.show(supportFragmentManager, CameraColorSamplingDialogFragment.TAG)
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

    private fun applyForegroundColors(color: Int) {
        // 前景明暗以完整背景为准：accent 渐变（accent+accent2）代表色偏亮会被误判为浅色，
        // isLight(ThingBackground) 已对其特判为深色背景 → 前景走白。color 仍保留原值用于
        // ripple、checklist 等强调色。
        val accentBg = getAccentBackground()
        val light = if (accentBg != null) {
            BackgroundUtil.isLight(accentBg)
        } else {
            BackgroundUtil.isLight(color)
        }
        val onRgb = if (light) 0x000000 else 0xFFFFFF
        fun on(alpha: Float): Int =
            (Math.round(alpha.coerceIn(0f, 1f) * 255f) shl 24) or onRgb
        val primary   = on(BackgroundUtil.ON_ALPHA_PRIMARY)
        val secondary = on(BackgroundUtil.ON_ALPHA_SECONDARY)
        val tertiary  = on(BackgroundUtil.ON_ALPHA_TERTIARY)

        mEtTitle!!.setTextColor(primary)
        mEtTitle!!.setHintTextColor(primary)

        TextViewCompat.setCompoundDrawableTintList(
            mEtTitle!!,
            if (light)
                ColorStateList.valueOf(Color.BLACK)
            else ColorStateList.valueOf(Color.WHITE)
        )
        mEtContent!!.setTextColor(secondary)
        mEtContent!!.setHintTextColor(secondary)
        mTvThingFolderPath!!.setTextColor(tertiary)
        mTvUpdateTime!!.setTextColor(tertiary)

        val tvFinishTime: TextView? = mDetailContent.view(R.id.tv_finish_time)
        tvFinishTime?.setTextColor(tertiary)
        val tvTypeInfo: TextView? = mDetailContent.view(R.id.tv_type_info)
        tvTypeInfo?.setTextColor(tertiary)
        if (mTvMoveChecklistAsBt != null) {
            mTvMoveChecklistAsBt!!.setTextColor(tertiary)
            TextViewCompat.setCompoundDrawableTintList(
                mTvMoveChecklistAsBt!!,
                if (light)
                    ColorStateList.valueOf(Color.BLACK)
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
                if (light) {
                    underline.setColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN)
                } else {
                    underline.clearColorFilter()
                }
            }
        }
        if (cbQuickRemind != null) {
            BackgroundUtil.applyCheckboxAccent(
                cbQuickRemind!!,
                App.defaultAccentBackground,
                secondary
            )
        }

        if (mCheckListAdapter != null) {
            mCheckListAdapter!!.setThingColor(color)
            mCheckListAdapter!!.notifyDataSetChanged()
        }

        val lightAccent = light
        val iconTint: ColorStateList? = if (lightAccent)
            ColorStateList.valueOf(Color.BLACK)
        else null
        val ivIconTypeInfo: ImageView? = mDetailContent.view(R.id.iv_icon_type_info)
        if (ivIconTypeInfo != null) {
            ImageViewCompat.setImageTintList(ivIconTypeInfo, iconTint)
        }
        mToolbarContrast?.refresh()

        val lightBg = light
        mFlRoot!!.post {
            val entering = detailTransition?.takeIf { it.isRunning }
            if (entering != null) {
                entering.setTargetLightStatusBars(lightBg)
            } else if (lightBg) {
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
                getString(R.string.l10n_share_reminder, tvQuickRemind!!.text)
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
                mDateTimeDialogFragment!!.show(supportFragmentManager, DateTimeDialogFragment.TAG)
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
        adf.show(supportFragmentManager, AlertDialogFragment.TAG)
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
        if (committedReturnPending || isFinishing) return false
        if (mAudioAttachmentAdapter != null && mAudioAttachmentAdapter!!.getPlayingIndex() != -1) {
            mAudioAttachmentAdapter!!.stopPlaying()
        }

        if (!mEditable) {
            setResult(Def.Communication.RESULT_NO_UPDATE)
            finishCommittedReturn()
            return false
        }

        KeyboardUtil.hideKeyboard(currentFocus)
        mNormalSnackbar?.dismiss()

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
            normalSnackbar().setMessage(R.string.error_later)
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

        // 无修改返回不重新渲染桌面小组件：视频静态封面取帧会占住主线程，延后转场回调。
        afterCreateOrUpdateThing(intent, resultCode,
            refreshWidgets = resultCode != Def.Communication.RESULT_NO_UPDATE)
        finishCommittedReturn()
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
            val detailAppearance = normalizedDetailAttachmentMediaAppearance(attachment)
            val proposedBg: ThingBackground? = if (mChangeBackgroundTo != null)
                mChangeBackgroundTo
            else mThing!!.getBackground()
            val noUpdate = Thing.noUpdate(
                mThing, title, content, attachment, typeAfter, proposedBg,
                mThingCardSpanMode, mThingCardImagePlacement, detailAppearance
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

    private fun normalizedDetailAttachmentMediaAppearance(
        attachment: String
    ): DetailAttachmentMediaAppearance {
        val availableKeys = ThingCardMediaHelper.getMediaSourceKeysFromAttachment(attachment)
        return mDetailAttachmentMediaAppearance.retainSources(availableKeys)
    }

    private fun applyDetailAttachmentMediaAppearanceDraftToThing(attachment: String) {
        mDetailAttachmentMediaAppearance = normalizedDetailAttachmentMediaAppearance(attachment)
        mThing!!.detailAttachmentMediaAppearance = mDetailAttachmentMediaAppearance
    }

    private fun afterCreateOrUpdateThing(intent: Intent, resultCode: Int, refreshWidgets: Boolean = true) {
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

        // 自动保存沿用默认刷新；只有正常返回且正式判定为无修改时跳过。
        if (refreshWidgets) {
            AppWidgetHelper.updateSingleThingAppWidgets(this, mThing!!.id)
            AppWidgetHelper.updateThingsListAppWidgetsForType(this, mThing!!.type)
        }

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
        val alarmBearing = type == Thing.REMINDER || type == Thing.HABIT || type == Thing.GOAL
        if (!alarmBearing) return

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) return

        doWithPermissionChecked(object : SimplePermissionCallback(this) {
            override fun onDenied() {
                // No-op
            }
        }, Def.Communication.REQUEST_PERMISSION_NOTIFICATION,
            Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun captureMainListPositionForResult() {
        val manager = ThingManager.getInstance(mApp)!!
        if (mListProjectionKey == null) {
            mListProjectionKey = manager.getProjection().key()
        }
        if (mListPosition >= 0) return
        val thing = mThing ?: return
        mListPosition = manager.getListPositionForThingId(thing.id)
    }

    private fun putMainListPositions(intent: Intent) {
        captureMainListPositionForResult()
        intent.putExtra(Def.Communication.KEY_POSITION, mThingIndex)
        intent.putExtra(Def.Communication.KEY_LIST_POSITION, mListPosition)
        intent.putExtra(Def.Communication.KEY_LIST_PROJECTION, mListProjectionKey)
    }

    private fun putShortcutCreateFolderProjection(intent: Intent) {
        if (mType != CREATE || mSenderName != ShortcutActivity.TAG) return
        intent.putExtra(Def.Communication.KEY_SENDER_NAME, ShortcutActivity.TAG)
        val folderId = mThing?.folderId ?: return
        intent.putExtra(Def.Communication.KEY_FOLDER_ID, folderId)
    }

    private fun returnToThingsActivity(stateAfter: Int) {
        if (committedReturnPending || isFinishing) return
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

        putMainListPositions(intent)
        intent.putExtra(Def.Communication.KEY_STATE_AFTER, stateAfter)

        if (mThingIndex == -1) {
            val stateBefore = mThing!!.state
            val locationToUpdate = manager.getLocationForStateChange(mThing)
            mThing = Thing.getSameCheckStateThing(mThing, stateBefore, stateAfter)
            val dao: ThingDAO = ThingDAO.getInstance(mApp)!!
            dao.updateState(
                mThing, locationToUpdate, stateBefore, stateAfter, true, true,
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
                    mThing, mThingIndex, mThing!!.location,
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
        if (mChecklistVisible) {
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

    /** 界面上当前的附件串。只读，不登记待删除的附件（分享时要用，不能有副作用）。 */
    private fun currentAttachmentStr(): String {
        var imageItems: List<String?>? = null
        var audioItems: List<String?>? = null
        if (hasImageAttachments) {
            imageItems = mImageAttachmentAdapter!!.getItems()
        }
        if (hasAudioAttachments) {
            audioItems = mAudioAttachmentAdapter!!.getItems()
        }
        return AttachmentHelper.toAttachmentStr(imageItems, audioItems)
    }

    private fun getThingAttachment(): String {
        val attachment: String = currentAttachmentStr()
        val attachmentsToDelete: List<String?>? = AttachmentHelper
            .getAttachmentsToDelete(mThing!!.attachment, attachment)
        if (!attachmentsToDelete.isNullOrEmpty()) {
            mApp!!.addAttachmentsToDeleteFile(attachmentsToDelete)
        }
        return attachment
    }

    private fun createFailed(resultCode: Int) {
        if (savedAfterOnPause) {
            mThingIndex = App.getThingAndPosition(mApp, mThing!!.id, -1).second ?: -1
            ThingManager.getInstance(mApp)!!.updateState(
                mThing, mThingIndex, mThing!!.location, Thing.UNDERWAY, Thing.DELETED_FOREVER,
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
        applyDetailAttachmentMediaAppearanceDraftToThing(attachment)
        if (mChangeBackgroundTo != null) {
            mThing!!.setBackground(mChangeBackgroundTo)
        } else {
            mThing!!.setColor(color)
        }

        val currentTime = System.currentTimeMillis()
        mThing!!.createTime = currentTime
        mThing!!.updateTime = currentTime

        intent.putExtra(Def.Communication.KEY_THING, mThing)
        putShortcutCreateFolderProjection(intent)
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
        applyDetailAttachmentMediaAppearanceDraftToThing(attachment)
        if (mChangeBackgroundTo != null) {
            mThing!!.setBackground(mChangeBackgroundTo)
        } else {
            mThing!!.setColor(color)
        }
        mThing!!.updateTime = System.currentTimeMillis()

        intent.putExtra(Def.Communication.KEY_TYPE_BEFORE, typeBefore)
        intent.putExtra(Def.Communication.KEY_THING, mThing)

        val sameType = mApp!!.getStatus() == Def.ThingStatus.UNDERWAY
            || Thing.sameType(typeBefore, typeAfter)
        val resultCode: Int = if (sameType) {
            Def.Communication.RESULT_UPDATE_THING_DONE_TYPE_SAME
        } else {
            Def.Communication.RESULT_UPDATE_THING_DONE_TYPE_DIFFERENT
        }

        if (mThingIndex != -1) {
            putMainListPositions(intent)
            val updateResult = ThingManager.getInstance(mApp)!!.update(
                typeBefore, mThing, mThingIndex, true
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
        captureMainListPositionForResult()

        if (mThing!!.location < 0) {
            ThingManager.getInstance(mApp)!!.cancelStickyThing(mThing, mThingIndex)
        } else {
            ThingManager.getInstance(mApp)!!.stickyThingOnTop(mThing, mThingIndex)
        }
        val resultCode = Def.Communication.RESULT_STICKY_THING_OR_CANCEL
        val intent = Intent(Def.Communication.BROADCAST_ACTION_UPDATE_MAIN_UI)
        intent.putExtra(Def.Communication.KEY_RESULT_CODE, resultCode)
        intent.putExtra(Def.Communication.KEY_THING, mThing)
        putMainListPositions(intent)

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
        putMainListPositions(intent)
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
        finishCommittedReturn()
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
            focusChecklistItem(position, null, true)
        }

        override fun onRemove(position: Int, item: String?, cursorPos: Int) {
            if (item == null && position >= 0) focusChecklistItem(position, cursorPos, false)
            else KeyboardUtil.hideKeyboard(currentFocus)
        }
    }

    private inner class ImageAttachmentAppearanceCallback :
        ImageAttachmentAdapter.AppearanceCallback {

        override fun onEditAppearance(pos: Int) {
            showDetailAttachmentMediaAppearanceEditor(pos)
        }
    }

    private var mDetailCropPreviewLoader: DetailCropPreviewLoader? = null
    private var mDetailCropPreviewModal: UiInteractions.Modal? = null

    private fun cancelDetailCropPreview() {
        if (mDetailCropPreviewModal != null) mDetailCropPreviewLoader?.cancel()
        mDetailCropPreviewModal?.release()
        mDetailCropPreviewModal = null
    }

    private fun showDetailAttachmentMediaAppearanceEditor(position: Int) {
        val key = mImageAttachmentAdapter?.getItems()?.getOrNull(position) ?: return
        val source = ThingCardMediaHelper.toMediaSource(key) ?: return
        val owner = UiInteractions.open(this, "detail-attachment-preview") ?: return
        mDetailCropPreviewModal = owner
        val frameMs = mDetailAttachmentMediaAppearance.source(key)?.videoFrameMs ?: 0L
        val loader = mDetailCropPreviewLoader ?: DetailCropPreviewLoader().also { mDetailCropPreviewLoader = it }
        fun show(metadata: DetailCropPreviewLoader.Metadata, preview: DetailCropPreviewLoader.Preview? = null) {
            if (mDetailCropPreviewModal !== owner) return
            mDetailCropPreviewModal = null
            owner.release()
            if (isFinishing || isDestroyed || supportFragmentManager.isStateSaved ||
                mImageAttachmentAdapter?.getItems()?.getOrNull(position) != key) return
            MediaCropAppearanceDialogFragment.newInstance(
                MediaCropAppearanceDialogFragment.REQUEST_DETAIL_ATTACHMENT, position
            ).apply {
                detailPreviewMetadata = metadata
                preparedDetailPreview = preview
            }.show(
                supportFragmentManager, MediaCropAppearanceDialogFragment.TAG
            )
        }
        loader.loadMetadata(source) { result ->
            if (mDetailCropPreviewModal !== owner) return@loadMetadata
            val metadata = result.getOrNull()
            if (metadata != null) show(metadata, loader.preparedPreview(key, frameMs)) else {
                // 少数来源不能单独读取尺寸时，仍用原解码器兜底，且不回到主线程解码。
                loader.load(source, frameMs, { loadDetailAttachmentCropPreview(source, frameMs) }) { decoded ->
                    if (mDetailCropPreviewModal !== owner) return@load
                    val preview = decoded.getOrNull()
                    if (preview != null) show(DetailCropPreviewLoader.Metadata(key,
                        preview.bitmap.width, preview.bitmap.height, preview.durationMs), preview)
                    else {
                        cancelDetailCropPreview()
                        Toast.makeText(this, R.string.detail_media_preview_load_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun getMediaCropAppearanceDialogWidthPx(
        fragment: MediaCropAppearanceDialogFragment,
        requestKey: String,
        position: Int
    ): Int {
        if (requestKey != MediaCropAppearanceDialogFragment.REQUEST_DETAIL_ATTACHMENT) {
            return ViewGroup.LayoutParams.WRAP_CONTENT
        }
        return getDetailAttachmentAppearanceDialogWidth()
    }

    override fun createMediaCropAppearanceDialogContent(
        fragment: MediaCropAppearanceDialogFragment,
        requestKey: String,
        position: Int
    ): MediaCropAppearanceDialogFragment.Content? {
        if (requestKey != MediaCropAppearanceDialogFragment.REQUEST_DETAIL_ATTACHMENT) return null
        val typePathName = mImageAttachmentAdapter?.getItems()?.getOrNull(position) ?: return null
        val source = ThingCardMediaHelper.toMediaSource(typePathName) ?: return null
        val frameMs = mDetailAttachmentMediaAppearance.source(source.typePathName)?.videoFrameMs ?: 0L
        var preview = fragment.preparedDetailPreview?.takeIf {
            it.typePathName == source.typePathName && it.frameMs == frameMs
        }
        // 新窗口把尺寸写入 Fragment 参数；仅兼容升级前留下的旧窗口恢复状态。
        val metadata = fragment.detailPreviewMetadata?.takeIf { it.typePathName == source.typePathName }
            ?: (preview ?: loadDetailAttachmentCropPreview(source, frameMs)?.also { preview = it })?.let {
                DetailCropPreviewLoader.Metadata(it.typePathName, it.bitmap.width, it.bitmap.height, it.durationMs)
            } ?: return null
        var bitmap = preview?.bitmap
        var previewReady = bitmap != null
        var disposed = false
        var actionRow: View? = null
        val isFirst = position == 0
        val isSingle = mImageAttachmentAdapter?.itemCount == 1
        val canToggleFullSpan = isFirst && !isSingle

        var draftSource = createEditableDetailAttachmentSourceAppearance(source)
        draftSource = draftSource.ensurePresentation(
            DetailAttachmentMediaAppearance.PRESENTATION_GRID
        )
        if (isFirst && (isSingle || draftSource.fullSpanEnabled)) {
            draftSource = draftSource.ensurePresentation(
                DetailAttachmentMediaAppearance.PRESENTATION_FULL_SPAN,
                DetailAttachmentMediaAppearance.PRESENTATION_GRID
            )
        }
        var activePresentationKey = if (isFirst && (isSingle || draftSource.fullSpanEnabled)) {
            DetailAttachmentMediaAppearance.PRESENTATION_FULL_SPAN
        } else {
            DetailAttachmentMediaAppearance.PRESENTATION_GRID
        }

        val density = resources.displayMetrics.density
        val dialogWidth = getDetailAttachmentAppearanceDialogWidth()
        val contentHorizontalMargin = resources.getDimensionPixelSize(
            R.dimen.app_chrome_dialog_title_margin_horizontal
        )

        val root = object : LinearLayout(this) {
            override fun onInterceptTouchEvent(event: MotionEvent): Boolean =
                (!previewReady && event.y < (actionRow?.top ?: height)) || super.onInterceptTouchEvent(event)
            override fun onTouchEvent(event: MotionEvent): Boolean = !previewReady || super.onTouchEvent(event)
        }
        root.orientation = LinearLayout.VERTICAL
        root.clipChildren = false
        root.clipToPadding = false
        root.setBackgroundResource(R.drawable.bg_app_chrome_surface_elevated_rounded)

        val title = TextView(this)
        title.setText(getDetailAttachmentAppearanceTitleRes(source))
        applyDetailAttachmentAppearanceAccentText(title)
        title.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            resources.getDimension(R.dimen.app_chrome_dialog_title_text_size)
        )
        title.setTypeface(title.typeface, Typeface.BOLD)
        title.includeFontPadding = false
        root.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(
                    contentHorizontalMargin,
                    resources.getDimensionPixelSize(R.dimen.app_chrome_dialog_title_margin_top),
                    contentHorizontalMargin,
                    0
                )
            }
        )

        fun createScrollSeparator() = View(this).apply {
            setBackgroundResource(R.color.app_chrome_divider)
            visibility = View.INVISIBLE
        }
        val topSeparator = createScrollSeparator().apply { id = R.id.view_separator_1 }
        val bottomSeparator = createScrollSeparator().apply { id = R.id.view_separator_2 }
        val separatorHeight = max(1, density.roundToInt())
        root.addView(topSeparator, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, separatorHeight
        ).apply { topMargin = (density * 12).toInt() })
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            // 外层为按钮波纹允许越界绘制；滚动区必须单独裁剪，防止预览覆盖标题与按钮。
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BOUNDS
        }
        val editorContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, (density * 6).toInt())
        }
        scroll.addView(editorContent, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        // 内容较少时保持紧凑；窗口高度不足时只收缩中间区域，标题与按钮始终可见。
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        root.addView(bottomSeparator, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, separatorHeight
        ))
        fun updateScrollSeparators() {
            topSeparator.visibility = if (scroll.canScrollVertically(-1)) View.VISIBLE else View.INVISIBLE
            bottomSeparator.visibility = if (scroll.canScrollVertically(1)) View.VISIBLE else View.INVISIBLE
        }
        val scrollListener = ViewTreeObserver.OnScrollChangedListener { updateScrollSeparators() }
        val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { updateScrollSeparators() }
        scroll.viewTreeObserver.addOnScrollChangedListener(scrollListener)
        scroll.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)

        var onWidthModeChanged: ((Boolean) -> Unit)? = null
        val widthControls = if (canToggleFullSpan) {
            createDetailAttachmentWidthControls(
                draftSource.fullSpanEnabled,
                getDetailAttachmentAppearanceWidthLabelRes(source)
            ) { wide ->
                onWidthModeChanged?.invoke(wide)
            }
        } else {
            null
        }
        if (widthControls != null) {
            editorContent.addView(
                widthControls.view,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (density * 40).toInt()
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

        val initialPresentation = draftSource.presentationOrDefault(activePresentationKey)
        val videoCropView: ThingCardVideoCropEditorView?
        val cropEditor: ThingCardCropEditorController
        val cropEditorView: View
        var videoFrameControls: DetailAttachmentVideoFrameControls? = null

        if (source.isVideo) {
            val view = ThingCardVideoCropEditorView(this)
            view.setAccentBackground(getAccentBackground() ?: ThingBackground.pure(getAccentColor()))
            view.setCropVideo(
                source.pathName,
                initialPresentation.targetAspectRatio,
                initialPresentation.crop.centerX,
                initialPresentation.crop.centerY,
                initialPresentation.crop.scale,
                draftSource.videoFrameMs ?: 0L,
                bitmap,
                metadata.width,
                metadata.height
            )
            videoCropView = view
            cropEditor = view
            cropEditorView = view
        } else {
            val view = ThingCardCropEditorView(this)
            view.setCropBitmap(
                bitmap,
                initialPresentation.targetAspectRatio,
                initialPresentation.crop.centerX,
                initialPresentation.crop.centerY,
                initialPresentation.crop.scale
            )
            videoCropView = null
            cropEditor = view
            cropEditorView = view
        }
        cropEditorView.setOnTouchListener { v, _ ->
            v.parent?.requestDisallowInterceptTouchEvent(true)
            false
        }

        val previewContainer = FrameLayout(this)
        previewContainer.addView(cropEditorView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val loadingIndicator = android.widget.ProgressBar(this).apply {
            BackgroundUtil.applyProgressBarGradient(
                this, getAccentBackground() ?: ThingBackground.pure(getAccentColor())
            )
            contentDescription = getString(R.string.detail_media_preview_loading)
            visibility = if (!previewReady && !source.isVideo) View.VISIBLE else View.GONE
        }
        previewContainer.addView(loadingIndicator, FrameLayout.LayoutParams(
            (density * 28).toInt(), (density * 28).toInt(), Gravity.CENTER))
        editorContent.addView(
            previewContainer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                getDetailAttachmentCropEditorPreviewHeight(
                    metadata.width,
                    metadata.height,
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

        if (source.isVideo && videoCropView != null) {
            videoFrameControls = createDetailAttachmentVideoFrameControls(
                videoCropView,
                draftSource.videoFrameMs ?: 0L,
                metadata.durationMs
            )
            if (videoFrameControls != null) {
                editorContent.addView(
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
        }

        // 后续附件固定为网格裁剪，不创建永远不可见的比例滑杆及其渐变资源。
        val ratioControls = if (isFirst) createDetailAttachmentRatioControls(
            cropEditor, initialPresentation.targetAspectRatio,
            getDetailAttachmentAppearanceRatioLabelRes(source)
        ) else null
        ratioControls?.view?.visibility =
            if (activePresentationKey == DetailAttachmentMediaAppearance.PRESENTATION_FULL_SPAN) {
                View.VISIBLE
            } else {
                View.GONE
            }
        if (ratioControls != null) editorContent.addView(
            ratioControls.view,
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

        fun saveCurrentPresentationToDraft() {
            val targetAspectRatio =
                if (activePresentationKey == DetailAttachmentMediaAppearance.PRESENTATION_GRID) {
                    DetailAttachmentMediaAppearance.DEFAULT_TARGET_ASPECT_RATIO
                } else {
                    cropEditor.getTargetAspectRatio()
                }
            draftSource = draftSource.withPresentation(
                activePresentationKey,
                DetailAttachmentMediaAppearance.MediaPresentationAppearance(
                    targetAspectRatio = targetAspectRatio,
                    crop = DetailAttachmentMediaAppearance.DetailMediaCrop(
                        centerX = cropEditor.getCropCenterX(),
                        centerY = cropEditor.getCropCenterY(),
                        scale = cropEditor.getCropUserScale()
                    )
                )
            )
        }

        fun selectedVideoFrameMs(): Long {
            return videoFrameControls?.getFrameMs?.invoke() ?: draftSource.videoFrameMs ?: 0L
        }

        fun loadActivePresentationIntoEditor() {
            val presentation = draftSource.presentationOrDefault(activePresentationKey)
            if (source.isVideo && videoCropView != null) {
                videoCropView.setCropVideo(
                    source.pathName,
                    presentation.targetAspectRatio,
                    presentation.crop.centerX,
                    presentation.crop.centerY,
                    presentation.crop.scale,
                    selectedVideoFrameMs(),
                    bitmap,
                    metadata.width,
                    metadata.height
                )
            } else if (cropEditorView is ThingCardCropEditorView) {
                cropEditorView.setCropBitmap(
                    bitmap,
                    presentation.targetAspectRatio,
                    presentation.crop.centerX,
                    presentation.crop.centerY,
                    presentation.crop.scale
                )
            }
            ratioControls?.setRatio(presentation.targetAspectRatio)
            ratioControls?.view?.visibility =
                if (activePresentationKey ==
                    DetailAttachmentMediaAppearance.PRESENTATION_FULL_SPAN
                ) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
        }

        onWidthModeChanged = { isWide ->
            saveCurrentPresentationToDraft()
            draftSource = draftSource.withFullSpanEnabled(isWide)
            activePresentationKey = if (isWide) {
                draftSource = draftSource.ensurePresentation(
                    DetailAttachmentMediaAppearance.PRESENTATION_FULL_SPAN,
                    DetailAttachmentMediaAppearance.PRESENTATION_GRID
                )
                DetailAttachmentMediaAppearance.PRESENTATION_FULL_SPAN
            } else {
                DetailAttachmentMediaAppearance.PRESENTATION_GRID
            }
            loadActivePresentationIntoEditor()
        }

        val buttons = LinearLayout(this)
        actionRow = buttons
        buttons.gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.clipChildren = false
        buttons.clipToPadding = false
        buttons.addView(createDetailAttachmentAppearanceButton(R.string.cancel, false) {
            fragment.dismiss()
        })
        val confirmButton = createDetailAttachmentAppearanceButton(R.string.confirm) {
            if (!previewReady) return@createDetailAttachmentAppearanceButton
            saveCurrentPresentationToDraft()
            if (isFirst) {
                draftSource = draftSource.withFullSpanEnabled(
                    isSingle || widthControls?.isWide?.invoke() == true
                )
            }
            if (source.isVideo) {
                draftSource = draftSource.withVideoFrameMs(selectedVideoFrameMs())
            }
            applyDetailAttachmentMediaAppearance(
                mDetailAttachmentMediaAppearance.withSource(source.typePathName, draftSource)
            )
            fragment.dismiss()
        }
        confirmButton.isEnabled = previewReady
        confirmButton.alpha = if (previewReady) 1f else 0.38f
        buttons.addView(confirmButton)
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
                        R.dimen.app_chrome_dialog_divided_action_row_margin_top
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

        val waitingControls = listOfNotNull(cropEditorView, widthControls?.view,
            ratioControls?.view, videoFrameControls?.view)
        val enabledStates = mutableListOf<kotlin.Pair<View, Boolean>>()
        fun rememberEnabledStates(view: View) {
            enabledStates += view to view.isEnabled
            if (view is ViewGroup) for (i in 0 until view.childCount) rememberEnabledStates(view.getChildAt(i))
        }
        var cancelPreview: (() -> Unit)? = null
        if (!previewReady) {
            // 先保存整棵控件树的状态，再统一禁用。RatioSlider.setEnabled 会同步改动
            // 内部 SeekBar，边保存边禁用会误把子控件的临时禁用状态当作原状态。
            waitingControls.forEach(::rememberEnabledStates)
            enabledStates.forEach { (view, _) -> view.isEnabled = false }
            val loader = mDetailCropPreviewLoader ?: DetailCropPreviewLoader().also { mDetailCropPreviewLoader = it }
            cancelPreview = loader.load(source, frameMs, { loadDetailAttachmentCropPreview(source, frameMs, metadata.durationMs) }) { result ->
                if (disposed || isFinishing || isDestroyed || fragment.isRemoving) return@load
                val loaded = result.getOrNull()
                if (loaded == null) {
                    Toast.makeText(this, R.string.detail_media_preview_load_failed, Toast.LENGTH_SHORT).show()
                    fragment.dismiss()
                    return@load
                }
                bitmap = loaded.bitmap
                if (videoCropView != null) videoCropView.setFallbackBitmap(loaded.bitmap)
                else loadActivePresentationIntoEditor()
                loadingIndicator.visibility = View.GONE
                previewReady = true
                enabledStates.forEach { (view, enabled) -> view.isEnabled = enabled }
                confirmButton.isEnabled = true
                confirmButton.alpha = 1f
            }
        }
        return MediaCropAppearanceDialogFragment.Content(root) {
            disposed = true
            if (scroll.viewTreeObserver.isAlive) {
                scroll.viewTreeObserver.removeOnScrollChangedListener(scrollListener)
                scroll.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
            }
            cancelPreview?.invoke()
            videoFrameControls?.stopPlayback?.invoke()
            videoCropView?.release()
        }
    }

    @StringRes
    private fun getDetailAttachmentAppearanceTitleRes(
        source: ThingCardMediaHelper.MediaSource
    ): Int {
        return if (source.isVideo) {
            R.string.detail_attachment_video_appearance_title
        } else {
            R.string.detail_attachment_image_appearance_title
        }
    }

    @StringRes
    private fun getDetailAttachmentAppearanceWidthLabelRes(
        source: ThingCardMediaHelper.MediaSource
    ): Int {
        return if (source.isVideo) {
            R.string.detail_attachment_media_appearance_video_display_width
        } else {
            R.string.detail_attachment_media_appearance_display_width
        }
    }

    @StringRes
    private fun getDetailAttachmentAppearanceRatioLabelRes(
        source: ThingCardMediaHelper.MediaSource
    ): Int {
        return if (source.isVideo) {
            R.string.detail_attachment_media_appearance_video_ratio
        } else {
            R.string.detail_attachment_media_appearance_image_ratio
        }
    }

    private fun createEditableDetailAttachmentSourceAppearance(
        source: ThingCardMediaHelper.MediaSource
    ): DetailAttachmentMediaAppearance.SourceAppearance {
        val existing = mDetailAttachmentMediaAppearance.source(source.typePathName)
        return (existing ?: DetailAttachmentMediaAppearance.SourceAppearance()).copy(
            fileSize = source.fileSize,
            lastModified = source.lastModified
        )
    }

    private fun getDetailAttachmentAppearanceDialogWidth(): Int {
        val screenWidth = DisplayUtil.getScreenSize(this).x
        val maxWidth = resources.getDimensionPixelSize(R.dimen.thing_card_appearance_max_width)
        val horizontalInset = (resources.displayMetrics.density * 32).toInt()
        return min(max(1, screenWidth - horizontalInset), maxWidth)
    }

    private fun getDetailAttachmentCropEditorPreviewHeight(
        sourceWidth: Int,
        sourceHeight: Int,
        dialogWidth: Int,
        contentHorizontalMargin: Int
    ): Int {
        val screenSize = DisplayUtil.getScreenSize(this)
        val contentWidth = max(1, dialogWidth - contentHorizontalMargin * 2)
        val rawHeight = (contentWidth * sourceHeight.toDouble() /
            max(1, sourceWidth).toDouble()).toInt()
        val minHeight = (resources.displayMetrics.density * 160).toInt()
        val maxHeight = (screenSize.y * 0.52f).toInt()
        return max(minHeight, min(maxHeight, rawHeight))
    }

    private data class DetailAttachmentVideoFrameControls(
        val view: View,
        val getFrameMs: () -> Long,
        val stopPlayback: () -> Unit
    )

    private fun createDetailAttachmentVideoFrameControls(
        cropView: ThingCardVideoCropEditorView,
        initialFrameMs: Long,
        durationMs: Long
    ): DetailAttachmentVideoFrameControls? {
        if (durationMs <= 0L) return null
        val maxFrameMs = getDetailAttachmentVideoFrameMaxMs(durationMs)
        val density = resources.displayMetrics.density
        var frameMs = clampDetailAttachmentVideoFrameMs(initialFrameMs, durationMs)
        var updatingSeekBarFromVideo = false

        val container = LinearLayout(this)
        container.orientation = LinearLayout.HORIZONTAL
        container.gravity = Gravity.CENTER_VERTICAL

        lateinit var playPauseButton: ImageView
        lateinit var seekBar: SeekBar

        fun updatePlayPauseButton(isPlaying: Boolean = cropView.isPlaying()) {
            playPauseButton.setImageResource(
                if (isPlaying) R.drawable.ic_thing_card_crop_pause
                else R.drawable.ic_thing_card_crop_play
            )
            playPauseButton.contentDescription = getString(
                if (isPlaying) R.string.thing_card_appearance_video_frame_pause
                else R.string.thing_card_appearance_video_frame_play
            )
            playPauseButton.setColorFilter(
                ContextCompat.getColor(this, R.color.app_chrome_on_surface_secondary)
            )
        }

        fun setFrameMs(newFrameMs: Long, seekVideo: Boolean = true) {
            frameMs = clampDetailAttachmentVideoFrameMs(newFrameMs, durationMs)
            if (seekBar.progress != frameMs.toInt()) {
                updatingSeekBarFromVideo = !seekVideo
                seekBar.progress = frameMs.toInt()
                updatingSeekBarFromVideo = false
            }
            if (seekVideo) {
                cropView.seekTo(frameMs)
            }
        }

        playPauseButton = createDetailAttachmentAppearanceIconButton(
            R.drawable.ic_thing_card_crop_play,
            R.string.thing_card_appearance_video_frame_play
        )
        playPauseButton.setOnClickListener {
            if (cropView.isPlaying()) {
                cropView.pause()
            } else {
                if (frameMs >= maxFrameMs) setFrameMs(0L, true)
                cropView.play()
            }
            updatePlayPauseButton()
        }
        updatePlayPauseButton()
        container.addView(
            playPauseButton,
            LinearLayout.LayoutParams((density * 40).toInt(), (density * 40).toInt())
        )

        val stopButton = createDetailAttachmentAppearanceIconButton(
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
            LinearLayout.LayoutParams((density * 40).toInt(), (density * 40).toInt()).apply {
                marginEnd = (density * 6).toInt()
                rightMargin = (density * 6).toInt()
            }
        )

        seekBar = SeekBar(this)
        seekBar.max = maxFrameMs
        seekBar.progress = frameMs.toInt()
        DisplayUtil.setSeekBarBackground(
            seekBar,
            getAccentBackground() ?: ThingBackground.pure(getAccentColor())
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
            LinearLayout.LayoutParams(0, (density * 40).toInt(), 1f)
        )

        return DetailAttachmentVideoFrameControls(
            view = container,
            getFrameMs = { cropView.getCurrentFrameMs() },
            stopPlayback = { cropView.pause() }
        )
    }

    private data class DetailAttachmentRatioControls(
        val view: View,
        val setRatio: (Double) -> Unit
    )

    private data class DetailAttachmentWidthControls(
        val view: View,
        val isWide: () -> Boolean
    )

    private fun createDetailAttachmentWidthControls(
        initialWide: Boolean,
        @StringRes labelRes: Int,
        onWideChanged: (Boolean) -> Unit
    ): DetailAttachmentWidthControls {
        val density = resources.displayMetrics.density
        val container = LinearLayout(this)
        container.orientation = LinearLayout.HORIZONTAL
        container.gravity = Gravity.CENTER_VERTICAL

        val label = TextView(this)
        label.setText(labelRes)
        label.setTextColor(ContextCompat.getColor(this, R.color.app_chrome_on_surface_hint))
        label.textSize = 13f
        label.gravity = Gravity.CENTER_VERTICAL
        label.maxLines = 1
        label.includeFontPadding = false
        container.addView(
            label,
            LinearLayout.LayoutParams(
                (density * 104).toInt(),
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        var wide = initialWide
        val normalButton = createDetailAttachmentWidthChoice(
            R.string.detail_attachment_media_appearance_width_normal
        )
        val wideButton = createDetailAttachmentWidthChoice(
            R.string.detail_attachment_media_appearance_width_wide
        )

        fun bind() {
            bindDetailAttachmentAppearanceChoice(normalButton, !wide, true)
            bindDetailAttachmentAppearanceChoice(wideButton, wide, true)
        }

        normalButton.setOnClickListener {
            if (!wide) return@setOnClickListener
            wide = false
            bind()
            onWideChanged(false)
        }
        wideButton.setOnClickListener {
            if (wide) return@setOnClickListener
            wide = true
            bind()
            onWideChanged(true)
        }

        listOf(normalButton, wideButton).forEach { button ->
            container.addView(
                button,
                LinearLayout.LayoutParams(
                    0,
                    (density * 32).toInt(),
                    1f
                ).apply {
                    marginStart = (density * 2).toInt()
                    marginEnd = (density * 2).toInt()
                }
            )
        }
        bind()

        return DetailAttachmentWidthControls(
            view = container,
            isWide = { wide }
        )
    }

    private fun createDetailAttachmentWidthChoice(@StringRes textRes: Int): TextView {
        val button = TextView(this)
        button.setText(textRes)
        button.gravity = Gravity.CENTER
        button.includeFontPadding = false
        button.textSize = 14f
        button.isClickable = true
        button.isFocusable = true
        // ripple 由 bindDetailAttachmentAppearanceChoice 按选中态设置（未选中=记事色，选中=自适应）。
        return button
    }

    private fun bindDetailAttachmentAppearanceChoice(
        view: TextView,
        selected: Boolean,
        enabled: Boolean
    ) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1.0f else 0.38f
        if (selected && enabled) {
            applyDetailAttachmentAppearanceSelectedPill(view)
        } else {
            // 未选中胶囊：触摸 ripple 用当前记事颜色（胶囊形）。
            val accentBg = getAccentBackground() ?: ThingBackground.pure(getAccentColor())
            view.background = GradientRippleDrawable(accentBg, shapeOval = false, cornerRadiusPx = -1f)
            setDetailAttachmentAppearancePlainTextColor(
                view,
                ContextCompat.getColor(this, R.color.app_chrome_on_surface_secondary)
            )
        }
    }

    private fun applyDetailAttachmentAppearanceSelectedPill(textView: TextView) {
        val accentBackground = getAccentBackground()
        val background = if (accentBackground != null) {
            BackgroundUtil.makeTranslucentGradient(accentBackground, 255)
        } else {
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(getAccentColor())
            }
        }
        background.cornerRadius = 1000f
        // 选中胶囊已铺记事色，触摸 ripple 按记事色明暗自适应（胶囊形裁剪）。
        val pillMask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 1000f
            setColor(Color.WHITE)
        }
        textView.background = RippleDrawable(
            ColorStateList.valueOf(
                BackgroundUtil.adaptiveRippleColor(accentBackground)
            ),
            background,
            pillMask
        )

        val light = if (accentBackground != null) {
            BackgroundUtil.isLight(accentBackground)
        } else {
            BackgroundUtil.isLight(getAccentColor())
        }
        val foregroundColor = ContextCompat.getColor(
            this,
            if (light) R.color.black_86p else R.color.white_86p
        )
        setDetailAttachmentAppearancePlainTextColor(textView, foregroundColor)
    }

    private fun applyDetailAttachmentAppearanceAccentText(textView: TextView?) {
        if (textView == null) return
        val accentBackground = getAccentBackground()
        if (accentBackground != null) {
            BackgroundUtil.applyTextBackground(textView, accentBackground)
        } else {
            setDetailAttachmentAppearancePlainTextColor(textView, getAccentColor())
        }
    }

    private fun setDetailAttachmentAppearancePlainTextColor(textView: TextView?, color: Int) {
        if (textView == null) return
        textView.paint.setShader(null)
        textView.setTextColor(color)
        textView.invalidate()
    }

    private fun createDetailAttachmentRatioControls(
        cropView: ThingCardCropEditorController,
        initialAspectRatio: Double,
        @StringRes labelRes: Int
    ): DetailAttachmentRatioControls {
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL

        val label = TextView(this)
        label.setText(labelRes)
        label.setTextColor(ContextCompat.getColor(this, R.color.app_chrome_on_surface_hint))
        label.textSize = 13f
        label.gravity = Gravity.CENTER_VERTICAL
        container.addView(
            label,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.density * 22).toInt()
            )
        )

        val ratioSlider = RatioSlider(this)
        ratioSlider.setAccentBackground(
            getAccentBackground() ?: ThingBackground.pure(getAccentColor()),
            ContextCompat.getColor(this, R.color.app_chrome_on_surface_hint)
        )
        ratioSlider.setRange(
            DetailAttachmentMediaAppearance.MIN_FULL_SPAN_TARGET_ASPECT_RATIO,
            DetailAttachmentMediaAppearance.MAX_FULL_SPAN_TARGET_ASPECT_RATIO
        )
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
        return DetailAttachmentRatioControls(
            view = container,
            setRatio = { ratio ->
                ratioSlider.setRatio(ratio)
                cropView.setTargetAspectRatio(ratioSlider.getRatio())
            }
        )
    }

    private fun createDetailAttachmentAppearanceButton(
        textRes: Int,
        useAccent: Boolean = true,
        onClick: () -> Unit
    ): TextView {
        val button = TextView(this)
        button.setText(textRes)
        val accentBg = getAccentBackground()
        if (useAccent) {
            // 确定按钮文字用记事颜色（纯色 / 渐变）。
            if (accentBg != null) {
                BackgroundUtil.applyTextBackground(button, accentBg)
            } else {
                button.setTextColor(getAccentColor())
            }
        } else {
            button.setTextColor(ContextCompat.getColor(this, R.color.app_chrome_dialog_cancel))
        }
        button.gravity = Gravity.CENTER
        button.includeFontPadding = false
        button.setAllCaps(true)
        button.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            resources.getDimensionPixelSize(R.dimen.app_chrome_dialog_action_button_height)
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
        if (useAccent) {
            // 确定按钮触摸 ripple 也用记事颜色（纯色 / 渐变），胶囊形；内边距 / 字号与其它 dialog 的
            // 确定按钮对齐（取消按钮由 installAppChromeDialogActionButton 设置同样的值）。
            button.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                resources.getDimension(R.dimen.app_chrome_dialog_action_text_size)
            )
            GradientRippleDrawable.applyAccentRipple(button, accentBg, getAccentColor())
            val padH = resources.getDimensionPixelSize(
                R.dimen.app_chrome_dialog_action_button_padding_horizontal
            )
            val padV = resources.getDimensionPixelSize(
                R.dimen.app_chrome_dialog_action_button_padding_vertical
            )
            button.setPaddingRelative(padH, padV, padH, padV)
        } else {
            BackgroundUtil.installAppChromeDialogActionButton(button, this)
        }
        button.setOnClickListener { onClick() }
        return button
    }

    private fun createDetailAttachmentAppearanceIconButton(
        iconRes: Int,
        contentDescriptionRes: Int
    ): ImageView {
        val button = ImageView(this)
        button.setImageResource(iconRes)
        button.contentDescription = getString(contentDescriptionRes)
        button.setColorFilter(ContextCompat.getColor(this, R.color.app_chrome_on_surface_secondary))
        button.scaleType = ImageView.ScaleType.CENTER
        button.isClickable = true
        button.isFocusable = true
        val padding = (resources.displayMetrics.density * 8).toInt()
        button.setPadding(padding, padding, padding, padding)
        // 播放/暂停、停止按钮触摸 ripple 用记事颜色，圆形。
        button.background = GradientRippleDrawable(
            getAccentBackground() ?: ThingBackground.pure(getAccentColor()),
            shapeOval = true
        )
        return button
    }

    private fun loadDetailAttachmentCropPreview(
        source: ThingCardMediaHelper.MediaSource,
        frameMs: Long,
        knownDurationMs: Long? = null
    ): DetailCropPreviewLoader.Preview? {
        val durationMs = knownDurationMs ?: if (source.isVideo) AttachmentHelper.getVideoDurationMs(source.pathName) else 0L
        val bitmap = if (source.isVideo) {
            loadDetailAttachmentCropEditorVideoFrame(source, frameMs, durationMs)
        } else {
            decodeDetailAttachmentCropEditorImage(source.pathName)
        } ?: return null
        return DetailCropPreviewLoader.Preview(source.typePathName, frameMs, bitmap, durationMs)
    }

    private fun loadDetailAttachmentCropEditorVideoFrame(
        source: ThingCardMediaHelper.MediaSource,
        frameMs: Long,
        durationMs: Long
    ): Bitmap? {
        val clampedFrameMs = clampDetailAttachmentVideoFrameMs(frameMs, durationMs)
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(source.pathName)
            retriever.getFrameAtTime(
                clampedFrameMs * 1000L,
                MediaMetadataRetriever.OPTION_CLOSEST
            )
        } catch (_: Exception) {
            AttachmentHelper.getImageFromVideo(source.pathName)
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun decodeDetailAttachmentCropEditorImage(pathName: String): Bitmap? {
        val maxSize = 2048
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeFile(pathName, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            val decoded = BitmapFactory.decodeFile(pathName) ?: return null
            return BitmapUtil.tryToGetRotatedBitmap(decoded, pathName)
        }

        val options = BitmapFactory.Options()
        options.inSampleSize = getDetailAttachmentCropEditorImageSampleSize(
            bounds.outWidth,
            bounds.outHeight,
            maxSize
        )
        val decoded = BitmapFactory.decodeFile(pathName, options) ?: return null
        return BitmapUtil.tryToGetRotatedBitmap(decoded, pathName)
    }

    private fun getDetailAttachmentCropEditorImageSampleSize(
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

    private fun clampDetailAttachmentVideoFrameMs(value: Long, durationMs: Long): Long {
        return max(0L, min(getDetailAttachmentVideoFrameMaxMs(durationMs).toLong(), value))
    }

    private fun getDetailAttachmentVideoFrameMaxMs(durationMs: Long): Int {
        if (durationMs <= 0L) return 0
        return min(
            Int.MAX_VALUE.toLong(),
            max(0L, durationMs - DETAIL_ATTACHMENT_VIDEO_END_FRAME_GUARD_MS)
        ).toInt()
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
            val items = mImageAttachmentAdapter!!.getItems() as ArrayList<String?>?
            intent.putExtra(Def.Communication.KEY_TYPE_PATH_NAME, items)
            intent.putExtra(Def.Communication.KEY_POSITION, pos)
            // 把每个附件的 Thing Card Video Frame 一并带过去：全屏的视频静帧与自动播放的
            // 起点都用它，才能与详情网格看到的是同一帧。见 ADR-0017。
            if (items != null) {
                val frames = LongArray(items.size) { i ->
                    mDetailAttachmentMediaAppearance.source(items[i])?.videoFrameMs ?: -1L
                }
                intent.putExtra(Def.Communication.KEY_VIDEO_FRAME_MS_LIST, frames)
            }

            MediaPreviewTransition.launch(this@DetailActivity, intent, v ?: return)
        }
    }

    /** ConcatAdapter 的局部位置仅在当前绑定有效时使用；返回按完整附件身份匹配。 */
    internal fun findMediaPreviewSource(typePathName: String?): View? {
        if (typePathName == null) return null
        val adapter = mImageAttachmentAdapter ?: return null
        val list = mDetailContent.recyclerView
        for (i in 0 until list.childCount) {
            val holder = list.getChildViewHolder(list.getChildAt(i))
            if (holder.bindingAdapter !== adapter) continue
            if (adapter.getItems()?.getOrNull(holder.bindingAdapterPosition) == typePathName) {
                return (holder as? ImageAttachmentAdapter.ImageViewHolder)?.fl
            }
        }
        return null
    }

    internal fun isMediaPreviewLayoutReady(typePathName: String? = null, includeStoppedWindow: Boolean = false): Boolean = mDetailContent.recyclerView.let {
        !it.isComputingLayout && !it.isLayoutRequested && !it.hasPendingAdapterUpdates() &&
            // 返回的目标页可能在后台被 Glide 清理；等实际图片恢复后再冻结画面及顶栏配色。
            (findMediaPreviewSource(typePathName)?.findViewById<ImageView>(R.id.iv_image_attachment)?.let { image ->
                image.drawable != null
            } != false) &&
            mToolbarContrast?.isReadyForSnapshot(includeStoppedWindow) != false
    }

    /** 按详情实际绘制顺序提取媒体上方控件，供手势返回在原位置渐显。 */
    internal fun mediaPreviewChromeViews(): List<View> = buildList {
        val list = mDetailContent.recyclerView
        for (i in 0 until list.childCount) {
            val holder = list.getChildViewHolder(list.getChildAt(i)) as? ImageAttachmentAdapter.ImageViewHolder ?: continue
            val container = holder.fl ?: continue
            for (j in 0 until container.childCount) {
                val child = container.getChildAt(j)
                if (child !== holder.ivImage) add(child)
            }
        }
        mImageCover?.let(::add)
        mActionbar?.let(::add)
    }

    internal fun mediaPreviewToolbarGeneration(): Int = mToolbarContrast?.snapshotGeneration ?: 0

    internal fun setMediaPreviewTransitionPaused(paused: Boolean) {
        mAttachmentPlaybackController?.setTransitionPaused(paused)
    }

    /** 与详情裁剪器使用同一中心和缩放，供完整原图在转场中连续改变取景。 */
    internal fun mediaPreviewImageBounds(key: String?, imageWidth: Int, imageHeight: Int): android.graphics.RectF? {
        val source = findMediaPreviewSource(key) ?: return null
        val adapter = mImageAttachmentAdapter ?: return null
        val position = adapter.getItems()?.indexOf(key) ?: return null
        if (position < 0 || imageWidth <= 0 || imageHeight <= 0) return null
        val image = source.findViewById<ImageView>(R.id.iv_image_attachment) ?: return null
        val presentationKey = if (adapter.isFullSpanPosition(position))
            DetailAttachmentMediaAppearance.PRESENTATION_FULL_SPAN else DetailAttachmentMediaAppearance.PRESENTATION_GRID
        val crop = mDetailAttachmentMediaAppearance.source(key)?.presentationOrDefault(presentationKey)?.crop
            ?: DetailAttachmentMediaAppearance.DetailMediaCrop()
        val scale = max(image.width.toFloat() / imageWidth, image.height.toFloat() / imageHeight) * crop.scale.toFloat()
        val width = imageWidth * scale
        val height = imageHeight * scale
        val left = (image.width / 2f - crop.centerX.toFloat() * width).coerceIn(min(0f, image.width - width), 0f)
        val top = (image.height / 2f - crop.centerY.toFloat() * height).coerceIn(min(0f, image.height - height), 0f)
        return ThingDetailTransition.bounds(image, window.decorView).let {
            android.graphics.RectF(it.left + left, it.top + top, it.left + left + width, it.top + top + height)
        }
    }

    private inner class ImageAttachmentRemoveCallback : ImageAttachmentAdapter.RemoveCallback {

        override fun onRemove(pos: Int) {
            val item: String = mImageAttachmentAdapter!!.getItems()!![pos]!!
            val appearanceBefore = mDetailAttachmentMediaAppearance.toJson()
            mDetailAttachmentMediaAppearance =
                mDetailAttachmentMediaAppearance.removeSource(item)
            val appearanceAfter = mDetailAttachmentMediaAppearance.toJson()
            notifyImageAttachmentsChanged(false, pos)

            KeyboardUtil.hideKeyboard(currentFocus)

            if (shouldAddToActionList) {
                val action = ThingAction(
                    ThingAction.DELETE_ATTACHMENT, pos, item
                )
                action.getExtras()!!.putString(
                    ThingAction.KEY_DETAIL_ATTACHMENT_MEDIA_APPEARANCE_BEFORE,
                    appearanceBefore
                )
                action.getExtras()!!.putString(
                    ThingAction.KEY_DETAIL_ATTACHMENT_MEDIA_APPEARANCE_AFTER,
                    appearanceAfter
                )
                mActionList!!.addAction(action)
            }
        }
    }

    private inner class AudioAttachmentRemoveCallback : AudioAttachmentAdapter.RemoveCallback {

        override fun onRemoved(pos: Int) {
            val item: String = mAudioAttachmentAdapter!!.getItems()!![pos]!!
            notifyAudioAttachmentsChanged(false, pos)

            KeyboardUtil.hideKeyboard(currentFocus)

            if (shouldAddToActionList) {
                mActionList!!.addAction(ThingAction(
                    ThingAction.DELETE_ATTACHMENT, pos, item
                ))
            }
        }
    }

    /**
     * 多级清单拖拽：只在"同一 owner、同层级的兄弟带"内重排，连同被拖项的整棵子树一起作为块移动，
     * 且不跨越未完成/已完成分隔线。见 docs/features/multi-level-checklist/decisions.md（拖拽规则）。
     */
    private fun moveChecklist(from: Int, to: Int): Boolean {
        val items: MutableList<String?> = mCheckListAdapter!!.getItems()!!
        if (from !in items.indices || to !in items.indices) return false
        if (!CheckListHelper.isItem(items[from]) || !CheckListHelper.isItem(items[to])) return false

        // 不跨越未完成/已完成分隔线（3）。
        val sep = items.indexOf("3")
        if (sep != -1 && (from > sep) != (to > sep)) return false

        // 只在兄弟之间重排：owner 相同、层级相同。落点落在自己子树内（更深层）会因层级不同被拒。
        if (CheckListHelper.levelOf(items[from]) != CheckListHelper.levelOf(items[to])) return false
        if (CheckListHelper.ownerIndexOf(items, from) != CheckListHelper.ownerIndexOf(items, to)) return false

        val before: String = CheckListHelper.toCheckListStr(items)
        val fromEnd = CheckListHelper.subtreeEndIndexOf(items, from)
        val blockSize = fromEnd - from + 1

        // 整棵子树作为块移动，但**全程只用 notifyItemMoved**（绝不用 notifyDataSetChanged，否则会回收
        // 被拖拽的视图，导致 ItemTouchHelper 脱手、根节点自己跳到新位置）。
        if (to < from) {
            // 上移：把块内各项依次移到目标兄弟之前（块整体上移）。
            for (i in 0 until blockSize) moveChecklistItem(from + i, to + i)
        } else {
            // 下移：把目标兄弟整棵子树的各项依次上移到块之前，等价于块整体下移到该兄弟子树之后。
            val count = CheckListHelper.subtreeEndIndexOf(items, to) - fromEnd
            for (i in 0 until count) moveChecklistItem(fromEnd + 1 + i, from + i)
        }

        val after: String = CheckListHelper.toCheckListStr(items)
        if (shouldAddToActionList && before != after) {
            mActionList!!.addAction(ThingAction(ThingAction.UPDATE_CHECKLIST, before, after))
        }

        return true
    }

    /** 单步移动一项并用 notifyItemMoved 通知（不回收任何视图，拖拽不脱手）。 */
    private fun moveChecklistItem(from: Int, to: Int) {
        mCheckListAdapter!!.moveItem(from, to)
        mLastChecklistDragPosition = mDraggedChecklistVH?.bindingAdapterPosition ?: to
    }

    /**
     * 拖拽开始：记录整段拖拽前的内容串（供松手时记一次撤销动作），并把被拖项的子树从列表临时移除——
     * 触发删除动画形成“子项收束进父项”的观感，拖拽中列表里只剩父项一行作为代理跟手。
     */
    private fun beginChecklistDrag(vh: RecyclerView.ViewHolder) {
        val items = mCheckListAdapter?.getItems() ?: return
        mDragBeforeContent = CheckListHelper.toCheckListStr(items)
        startChecklistCapsule(vh)                       // 被拖项渐显胶囊轮廓
        val from = vh.bindingAdapterPosition
        mLastChecklistDragPosition = from
        if (from == RecyclerView.NO_POSITION || from !in items.indices) return
        if (!CheckListHelper.isItem(items[from])) return
        val end = CheckListHelper.subtreeEndIndexOf(items, from)
        val childCount = end - from
        if (childCount <= 0) { mCollapsedChildren = null; return }
        val children = ArrayList<String?>(childCount)
        for (i in 0 until childCount) children.add(items[from + 1 + i])
        repeat(childCount) { items.removeAt(from + 1) }
        mCollapsedChildren = children
        // 收束期间放开 move 动画，让被收子项下方的兄弟平滑上滑填补；短延时后关掉，使拖拽 onMove 重排仍瞬时。
        mChecklistDragAnimator?.allowMoveAnimation = true
        mCheckListAdapter!!.notifyItemRangeRemoved(from + 1, childCount)
        mDetailList?.postDelayed({ mChecklistDragAnimator?.allowMoveAnimation = false }, 260L)
    }

    /**
     * 拖拽结束：把收束的子树插回代理（被拖项）落点之后，触发插入动画形成“子项展开”的观感；
     * 子树各项层级随父项一并平移，owner/层级不变，无需归一化。最后把整段拖拽记成一次 UPDATE_CHECKLIST。
     */
    private fun endChecklistDrag(vh: RecyclerView.ViewHolder?) {
        val items = mCheckListAdapter?.getItems()
        val before = mDragBeforeContent
        mDragBeforeContent = null
        if (items == null) { mCollapsedChildren = null; return }
        fadeOutChecklistCapsule()                       // 落点确定后胶囊轮廓渐隐消失
        val children = mCollapsedChildren
        mCollapsedChildren = null
        if (children != null && children.isNotEmpty()) {
            var pos = vh?.bindingAdapterPosition ?: RecyclerView.NO_POSITION
            if (pos == RecyclerView.NO_POSITION) pos = mLastChecklistDragPosition
            if (pos in items.indices) {
                val insertAt = pos + 1
                items.addAll(insertAt, children)
                // 展开期间放开 move 动画，让下方兄弟平滑下移让位、子项向下展开。
                mChecklistDragAnimator?.allowMoveAnimation = true
                mCheckListAdapter!!.notifyItemRangeInserted(insertAt, children.size)
                mDetailList?.postDelayed({ mChecklistDragAnimator?.allowMoveAnimation = false }, 260L)
            }
        }
        if (before != null && shouldAddToActionList) {
            val after = CheckListHelper.toCheckListStr(items)
            if (before != after) {
                mActionList!!.addAction(ThingAction(ThingAction.UPDATE_CHECKLIST, before, after))
            }
        }
        // 收束/展开等动画结束后，复位可能残留的变换，兜底消除"项停在两行间重叠"。
        mDetailList?.postDelayed({ resetChecklistChildTransforms() }, 300L)
    }

    /** 自动保存必须读取完整子树；不能等失焦后的拖拽落位动画才把子项插回。 */
    private fun settleChecklistDragBeforePause() {
        if (mDragBeforeContent == null) return
        val holder = mDraggedChecklistVH
        val now = android.os.SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        try { mDetailList?.dispatchTouchEvent(cancel) } finally { cancel.recycle() }
        stopChecklistDragAutoScroll()
        endChecklistDrag(holder)
        mDetailList?.itemAnimator?.endAnimations()
        removeChecklistCapsule()
        resetChecklistChildTransforms()
    }

    /**
     * 拖拽代理（单行）在同 owner 同级兄弟之间整段跨越目标兄弟的子树移动；越出父子树范围即拒绝
     * （二/三级项不会被拖出其父节点的子列表）。代理自身的子树在拖拽期间已收束移除，故其相邻项要么是
     * 同级兄弟、要么是更浅的边界项/控制标记。
     */
    private fun moveChecklistDragProxy(from: Int, to: Int): Boolean {
        if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
        val items = mCheckListAdapter!!.getItems()!!
        if (from !in items.indices || to !in items.indices || from == to) return false
        if (!CheckListHelper.isItem(items[from])) return false
        val level = CheckListHelper.levelOf(items[from])
        if (to > from) {
            // 下移：跨过下一个同级兄弟的整棵子树。
            val nextStart = from + 1
            if (nextStart >= items.size) return false
            val next = items[nextStart]
            if (!CheckListHelper.isItem(next)) return false               // 控制标记 → 父子树下边界
            if (CheckListHelper.levelOf(next) != level) return false       // 更浅 → 越出父子树范围
            val nextEnd = CheckListHelper.subtreeEndIndexOf(items, nextStart)
            // 滞后：手指越过目标兄弟"整棵子树"的中线才跨越，否则刚一接触就跨越、跨完手指仍落在它身上会被
            // 反向再次触发，造成目标项上下反复横跳。
            if (!fingerPastSubtreeMid(nextStart, nextEnd, down = true)) return false
            moveChecklistItem(from, nextEnd)
            return true
        } else {
            // 上移：跨过上一个同级兄弟的整棵子树，落到该兄弟之前。
            var s = from - 1
            while (s > 0 && CheckListHelper.isItem(items[s]) && CheckListHelper.levelOf(items[s]) > level) s--
            if (s < 0 || !CheckListHelper.isItem(items[s]) || CheckListHelper.levelOf(items[s]) != level) return false
            if (!fingerPastSubtreeMid(s, from - 1, down = false)) return false   // 同上：滞后消抖
            moveChecklistItem(from, s)
            return true
        }
    }

    /**
     * 手指当前屏幕 Y 是否越过了 [startIdx, endIdx] 这棵子树的中线（按拖拽方向）。用于拖拽跨越的滞后消抖：
     * 往下需手指越过中线偏下、往上需越过中线偏上才跨越，跨越后手指自然落在子树另一侧、不会立即反向触发。
     * 子树比视口更长时，等滚动方向的远端可见再跨越，避免误判离屏高度。
     */
    private fun fingerPastSubtreeMid(startIdx: Int, endIdx: Int, down: Boolean): Boolean {
        val rv = mDetailList ?: return true
        val adapter = mCheckListAdapter ?: return false
        val sVH = mDetailContent.holder(adapter, startIdx)
        val eVH = mDetailContent.holder(adapter, endIdx)
        // 长子树无法同时呈现两端时，等滚动方向的远端进入视口再跨越，不估计离屏高度。
        val edge = if (down) eVH else sVH
        if (sVH == null || eVH == null) {
            val view = edge?.itemView ?: return false
            val loc = IntArray(2); view.getLocationOnScreen(loc)
            val mid = loc[1] + view.height / 2
            return if (down) mLastTouchScreenY >= mid else mLastTouchScreenY <= mid
        }
        val sLoc = IntArray(2); sVH.itemView.getLocationOnScreen(sLoc)
        val eLoc = IntArray(2); eVH.itemView.getLocationOnScreen(eLoc)
        val mid = (sLoc[1] + eLoc[1] + eVH.itemView.height) / 2
        return if (down) mLastTouchScreenY >= mid else mLastTouchScreenY <= mid
    }

    /**
     * 拖拽代理在某方向上是否还有可移动的同级兄弟（与 [moveChecklistDragProxy] 的边界判定同源）。
     * 供自动滚动裁边用：代理在该方向已无兄弟可去（碰到父子树/区域边界）就不再自动滚动。
     */
    private fun canChecklistProxyMove(pos: Int, down: Boolean): Boolean {
        val items = mCheckListAdapter?.getItems() ?: return false
        if (pos !in items.indices || !CheckListHelper.isItem(items[pos])) return false
        val level = CheckListHelper.levelOf(items[pos])
        return if (down) {
            val n = pos + 1
            n < items.size && CheckListHelper.isItem(items[n]) && CheckListHelper.levelOf(items[n]) == level
        } else {
            var s = pos - 1
            while (s > 0 && CheckListHelper.isItem(items[s]) && CheckListHelper.levelOf(items[s]) > level) s--
            s >= 0 && CheckListHelper.isItem(items[s]) && CheckListHelper.levelOf(items[s]) == level
        }
    }

    /**
     * 给被拖项加一个圆角矩形描边轮廓（overlay），范围涵盖状态图标与文本，线条用该项文字色，渐显出现。
     * 坐标用屏幕坐标换算到 itemView 坐标系：et 在内层 LinearLayout 里，直接用 et.right 会少算图标列偏移、
     * 盖不住右侧文本；矩形再夹到 itemView 内并留出描边宽度，保证下/右描边不被裁。
     */
    private fun startChecklistCapsule(vh: RecyclerView.ViewHolder) {
        removeChecklistCapsule()
        val holder = vh as? CheckListAdapter.EditTextHolder ?: return
        val v = holder.itemView
        val ivState = holder.ivState ?: return
        val et = holder.et ?: return
        if (v.width <= 0 || v.height <= 0) return
        val density = DisplayUtil.getScreenDensity(mApp)
        val strokeW = Math.max(1, Math.round(1.5f * density))
        val basePad = Math.round(3f * density)
        val iLoc = IntArray(2); v.getLocationOnScreen(iLoc)
        val sLoc = IntArray(2); ivState.getLocationOnScreen(sLoc)
        val eLoc = IntArray(2); et.getLocationOnScreen(eLoc)
        val sLeft = sLoc[0] - iLoc[0]
        val sTop = sLoc[1] - iLoc[1]
        val eLeft = eLoc[0] - iLoc[0]
        val eTop = eLoc[1] - iLoc[1]
        // ivState 控件（36dp）比里面的 icon 大、icon 居中（scaleType=center），控件比 icon 多出的单边留白
        // 才是左/上"视觉 padding"的真实来源；右/下没有这种控件，只用 basePad 会偏窄。取 icon 实际尺寸算出
        // 该留白，令四边到“内容真实视觉边缘”的 padding 统一为 basePad + 该留白，从而四边视觉对称。
        val icon = ivState.drawable
        val iconW = icon?.intrinsicWidth ?: -1
        val iconH = icon?.intrinsicHeight ?: -1
        val insetX = if (iconW in 1 until ivState.width) (ivState.width - iconW) / 2 else 0
        val insetY = if (iconH in 1 until ivState.height) (ivState.height - iconH) / 2 else 0
        val padX = basePad + insetX
        val padY = basePad + insetY
        // icon / 文字的真实视觉边缘（扣掉控件留白、EditText 内边距）。
        val iconVisualLeft = sLeft + insetX
        val iconVisualTop = sTop + insetY
        val iconVisualBottom = sTop + insetY + (if (iconH > 0) iconH else ivState.height)
        val textVisualRight = eLeft + et.width - et.totalPaddingRight
        val textVisualTop = eTop + et.totalPaddingTop
        val textVisualBottom = eTop + et.height - et.totalPaddingBottom
        var left = (iconVisualLeft - padX).coerceAtLeast(0)
        var top = (Math.min(iconVisualTop, textVisualTop) - padY).coerceAtLeast(0)
        var right = (textVisualRight + padX).coerceAtMost(v.width - strokeW)
        // 下边允许在 itemView 下方溢出 padY（移动模式 clipChildren=false 不裁），多行时文字底与轮廓底同样留白。
        var bottom = (Math.max(iconVisualBottom, textVisualBottom) + padY).coerceAtMost(v.height + padY)
        if (right <= left || bottom <= top) return
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.setColor(Color.TRANSPARENT)
        d.setStroke(strokeW, et.currentTextColor)
        d.cornerRadius = 10f * density          // 圆角矩形，圆角 10dp
        d.setBounds(left, top, right, bottom)
        d.alpha = 0
        v.overlay.add(d)
        mCapsuleHostView = v
        mCapsuleDrawable = d
        ValueAnimator.ofInt(0, 255).apply {
            duration = 180
            addUpdateListener { d.alpha = it.animatedValue as Int; v.invalidate() }
            start()
        }
    }

    /** 胶囊轮廓渐隐后移除。 */
    private fun fadeOutChecklistCapsule() {
        val v = mCapsuleHostView ?: return
        val d = mCapsuleDrawable ?: return
        mCapsuleHostView = null
        mCapsuleDrawable = null
        ValueAnimator.ofInt(d.alpha, 0).apply {
            duration = 180
            addUpdateListener { d.alpha = it.animatedValue as Int; v.invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) { v.overlay.remove(d); v.invalidate() }
            })
            start()
        }
    }

    /** 立即移除轮廓（无动画，兜底用）。 */
    private fun removeChecklistCapsule() {
        val v = mCapsuleHostView
        val d = mCapsuleDrawable
        mCapsuleHostView = null
        mCapsuleDrawable = null
        if (v != null && d != null) { v.overlay.remove(d); v.invalidate() }
    }

    /**
     * 落位后把所有可见项的动画残留变换复位，消除偶发的"项停在两行之间、相互重叠"——根因是某个收束/展开
     * 或重排的 translation/scale 动画被打断、未归零。仅在拖拽确已结束（无被拖项）时执行，避免打断新拖拽。
     */
    private fun resetChecklistChildTransforms() {
        if (mDraggedChecklistVH != null) return
        val rv = mDetailList ?: return
        for (i in 0 until rv.childCount) {
            val c = rv.getChildAt(i) ?: continue
            if (rv.getChildViewHolder(c).bindingAdapter !== mCheckListAdapter) continue
            c.translationX = 0f
            c.translationY = 0f
            c.scaleX = 1f
            c.scaleY = 1f
            c.alpha = 1f
        }
    }

    /**
     * 仅清单项移动模式期间挂载的 ItemAnimator：
     * - animateRemove：从顶部 pivot 做 scaleY→0 + alpha→0，被收子项“向上收缩”进父项；
     * - animateAdd：从顶部 pivot 由 scaleY 0→1 + alpha 0→1，子项“向下展开”还原；
     * - animateMove：默认（拖拽 onMove 重排）时**瞬时**完成，不与 ItemTouchHelper 的跟手平移冲突；仅在收束/
     *   展开期间（allowMoveAnimation=true）让下方兄弟平滑滑动填补/让位；被拖代理本身任何时候都不做 move 动画。
     * isRunning 计入自管的收缩/展开动画，避免 RecyclerView 误判动画结束而提前回收被动画的视图。
     */
    private inner class ChecklistDragItemAnimator : DetailAttachmentItemAnimator() {
        var allowMoveAnimation = false
        private val running = HashSet<RecyclerView.ViewHolder>()

        init {
            removeDuration = 180
            addDuration = 180
            moveDuration = 180
        }

        override fun animateRemove(holder: RecyclerView.ViewHolder): Boolean {
            if (holder.bindingAdapter !== mCheckListAdapter) return super.animateRemove(holder)
            endViewAnimation(holder)
            val v = holder.itemView
            v.pivotY = 0f
            running.add(holder)
            dispatchRemoveStarting(holder)
            v.animate().scaleY(0f).alpha(0f).setDuration(removeDuration)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: Animator) {
                        v.animate().setListener(null)
                        v.scaleY = 1f; v.alpha = 1f
                        running.remove(holder)
                        dispatchRemoveFinished(holder)
                        if (!isRunning) dispatchAnimationsFinished()
                    }
                }).start()
            return true
        }

        override fun animateAdd(holder: RecyclerView.ViewHolder): Boolean {
            if (holder.bindingAdapter !== mCheckListAdapter) return super.animateAdd(holder)
            endViewAnimation(holder)
            val v = holder.itemView
            v.pivotY = 0f; v.scaleY = 0f; v.alpha = 0f
            running.add(holder)
            dispatchAddStarting(holder)
            v.animate().scaleY(1f).alpha(1f).setDuration(addDuration)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: Animator) {
                        v.animate().setListener(null)
                        v.scaleY = 1f; v.alpha = 1f
                        running.remove(holder)
                        dispatchAddFinished(holder)
                        if (!isRunning) dispatchAnimationsFinished()
                    }
                }).start()
            return true
        }

        override fun shouldAnimateMove(holder: RecyclerView.ViewHolder): Boolean =
            if (holder.bindingAdapter === mCheckListAdapter) allowMoveAnimation && holder !== mDraggedChecklistVH
            else super.shouldAnimateMove(holder)

        private fun endViewAnimation(holder: RecyclerView.ViewHolder) {
            if (running.remove(holder)) {
                val v = holder.itemView
                v.animate().setListener(null).cancel()
                v.scaleY = 1f; v.alpha = 1f
            }
        }

        override fun endAnimation(holder: RecyclerView.ViewHolder) {
            endViewAnimation(holder)
            super.endAnimation(holder)
        }

        override fun endAnimations() {
            for (h in ArrayList(running)) endViewAnimation(h)
            super.endAnimations()
            dispatchAnimationsFinished()
        }

        override fun isRunning(): Boolean = running.isNotEmpty() || super.isRunning()
    }

    private inner class CheckListTouchCallback : ItemTouchHelper.Callback() {

        override fun getMovementFlags(
            recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
        ): Int {
            if (viewHolder.bindingAdapter !== mCheckListAdapter || !isChecklistMoveMode()) return 0
            val item = mCheckListAdapter?.getItems()?.getOrNull(viewHolder.bindingAdapterPosition)
            if (!CheckListHelper.isItem(item)) return 0
            return makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
        }

        override fun onMove(
            recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            if (viewHolder.bindingAdapter !== mCheckListAdapter || target.bindingAdapter !== mCheckListAdapter) return false
            // 子树已收束，被拖项是单行代理：在“同 owner 同级兄弟”之间整段跨越目标兄弟的子树。
            return moveChecklistDragProxy(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) { }

        override fun isItemViewSwipeEnabled(): Boolean = false

        override fun isLongPressDragEnabled(): Boolean = false

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                beginChecklistDrag(viewHolder)   // 收束子树（只剩父项代理跟手）
                startChecklistDragAutoScroll(viewHolder)
            } else if (actionState == ItemTouchHelper.ACTION_STATE_IDLE) {
                // 松手即停止滚动；不能等落位动画结束，否则恢复动画中的行会被继续滚出并回收。
                stopChecklistDragAutoScroll()
            }
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            stopChecklistDragAutoScroll()
            if (recyclerView.isComputingLayout) {
                // 离屏回收也可能触发 clearView，布局期间不能插回子树并通知 Adapter。
                val position = viewHolder.bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) mLastChecklistDragPosition = position
                recyclerView.post { endChecklistDrag(viewHolder) }
            } else {
                endChecklistDrag(viewHolder)
            }
        }

        // 由现有边缘触发器保持恒速，避免与 ItemTouchHelper 默认加速滚动叠加。
        override fun interpolateOutOfBoundsScroll(recyclerView: RecyclerView, viewSize: Int,
            viewSizeOutOfBounds: Int, totalSize: Int, msSinceStartScroll: Long): Int = 0
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        mLastTouchScreenY = ev.rawY.toInt()  // 记录实时手指屏幕 Y，供拖拽自动滚动边缘判定
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val list = mDetailList
            val origin = IntArray(2)
            list?.getLocationOnScreen(origin)
            mTouchSection = list?.findChildViewUnder(ev.rawX - origin[0], ev.rawY - origin[1])
                ?.let { list.getChildViewHolder(it).bindingAdapter }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun ensureDetailTouchHelper() {
        if (mChecklistTouchHelper != null) return
        mChecklistTouchHelper = ItemTouchHelper(DetailTouchCallback()).also {
            it.attachToRecyclerView(mDetailList)
        }
    }

    /** 同一滚动列表注册一个拖拽协调器，按实际所属分区分发清单与附件原有的不同规则。 */
    private inner class DetailTouchCallback : ItemTouchHelper.Callback() {
        private val checklist = CheckListTouchCallback()
        private val images = AttachmentTouchCallback(true)
        private val audios = AttachmentTouchCallback(false)
        private var active: ItemTouchHelper.Callback? = null

        private fun callback(holder: RecyclerView.ViewHolder): ItemTouchHelper.Callback? = when (holder.bindingAdapter ?: return null) {
            mCheckListAdapter -> checklist
            mImageAttachmentAdapter -> images
            mAudioAttachmentAdapter -> audios
            else -> null
        }

        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int =
            callback(viewHolder)?.getMovementFlags(recyclerView, viewHolder) ?: 0

        override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder): Boolean = callback(viewHolder)?.onMove(recyclerView, viewHolder, target) ?: false

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) { }
        override fun isItemViewSwipeEnabled() = false
        override fun isLongPressDragEnabled(): Boolean = when (mTouchSection) {
            mImageAttachmentAdapter -> (mImageAttachmentAdapter?.itemCount ?: 0) > 1
            mAudioAttachmentAdapter -> (mAudioAttachmentAdapter?.itemCount ?: 0) > 1
            else -> false
        }

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            if (viewHolder != null) active = callback(viewHolder)
            active?.onSelectedChanged(viewHolder, actionState)
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            (callback(viewHolder) ?: active)?.clearView(recyclerView, viewHolder)
        }

        override fun interpolateOutOfBoundsScroll(recyclerView: RecyclerView, viewSize: Int,
            viewSizeOutOfBounds: Int, totalSize: Int, msSinceStartScroll: Long): Int =
            if (active === checklist) 0 else super.interpolateOutOfBoundsScroll(recyclerView, viewSize,
                viewSizeOutOfBounds, totalSize, msSinceStartScroll)
    }

    /**
     * 拖拽自动滚动：手指靠近视口上/下边缘时，以恒定速度滚动统一内容列表；
     * 拖未完成项往下滚到未完成/已完成分割线即停，拖已完成项往上滚到分割线即停；全程不脱手。
     */
    private fun startChecklistDragAutoScroll(vh: RecyclerView.ViewHolder) {
        mDraggedChecklistVH = vh
        val sv = mDetailList ?: return
        val rv = mDetailList ?: return
        if (mDragScrollRunnable != null) return
        val density = DisplayUtil.getScreenDensity(mApp)
        val edge = (110 * density).toInt()
        val step = (6 * density).toInt()  // 每帧恒定滚动量，不加速
        val runnable = object : Runnable {
            override fun run() {
                if (mDraggedChecklistVH == null) return
                val svLoc = IntArray(2)
                sv.getLocationOnScreen(svLoc)
                val svTop = svLoc[1]
                val svBottom = svLoc[1] + sv.height

                // 用实时手指屏幕 Y 判边缘（不依赖被拖视图位置，避免补偿平移造成反馈失控）。
                val fingerY = mLastTouchScreenY
                var dy = when {
                    fingerY < svTop + edge -> -step
                    fingerY > svBottom - edge -> step
                    else -> 0
                }
                dy = clampChecklistAutoScroll(dy)
                if (dy != 0) {
                    sv.scrollBy(0, dy)
                    // ItemTouchHelper 默认滚动已禁用；手指静止时没有 MOVE 事件，需在滚动后
                    // 使用同一套子树中线与边界规则推进代理，不能只滚内容却不改变落点。
                    val dragged = mDraggedChecklistVH?.bindingAdapterPosition ?: RecyclerView.NO_POSITION
                    if (dragged != RecyclerView.NO_POSITION) {
                        moveChecklistDragProxy(dragged, dragged + if (dy > 0) 1 else -1)
                    }
                    rv.invalidate()
                }
                sv.postOnAnimation(this)
            }
        }
        mDragScrollRunnable = runnable
        sv.postOnAnimation(runnable)
    }

    private fun stopChecklistDragAutoScroll() {
        mDragScrollRunnable?.let { mDetailList?.removeCallbacks(it) }
        mDragScrollRunnable = null
        mDraggedChecklistVH = null
    }

    /**
     * 把自动滚动量按"拖拽边界"裁掉：被拖项在某方向**已无同级兄弟可去**（碰到父子树/区域边界）时，就不再
     * 往该方向自动滚动。判定与 [moveChecklistDragProxy]/[canChecklistProxyMove] 同源——只要还有兄弟（哪怕
     * 在视口外）就允许滚动把它带进来，故二/三级项不会被拖出父节点范围、一级项到分割线即停，且不依赖屏幕
     * 几何（被拖项贴手指、其几何会受补偿平移影响，不可作判据）。
     */
    private fun clampChecklistAutoScroll(dy: Int): Int {
        if (dy == 0) return 0
        val draggedPos = mDraggedChecklistVH?.bindingAdapterPosition ?: return dy
        if (draggedPos == RecyclerView.NO_POSITION) return dy
        if (dy > 0 && !canChecklistProxyMove(draggedPos, down = true)) return 0
        if (dy < 0 && !canChecklistProxyMove(draggedPos, down = false)) return 0
        return dy
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
            // 拖拽过程中 itemCount 不变，只需重算 span/总高度并发送细粒度移动事件。全量刷新会
            // 清理、回收每个 Glide GIF target，是 2026-07-31 生命周期闪退的高频放大器。
            refreshImageAttachmentLayout(false)
            mImageAttachmentAdapter!!.notifyItemMoved(from, to)
            mAttachmentPlaybackController?.onItemsChanged()
        } else {
            mAudioAttachmentAdapter!!.notifyItemMoved(from, to)
            val playing = mAudioAttachmentAdapter!!.getPlayingIndex()
            mAudioAttachmentAdapter!!.setPlayingIndex(when {
                playing == from -> to
                from < playing && playing <= to -> playing - 1
                to <= playing && playing < from -> playing + 1
                else -> playing
            })
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
            val adapter = if (isImageAttachmentAdapter) mImageAttachmentAdapter else mAudioAttachmentAdapter
            if (viewHolder.bindingAdapter !== adapter || (adapter?.itemCount ?: 0) <= 1) return 0
            val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN or
                ItemTouchHelper.START or ItemTouchHelper.END
            return makeMovementFlags(dragFlags, 0)
        }

        override fun onMove(
            recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val adapter = if (isImageAttachmentAdapter) mImageAttachmentAdapter else mAudioAttachmentAdapter
            if (viewHolder.bindingAdapter !== adapter || target.bindingAdapter !== adapter) return false
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false

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


    companion object {
        const val TAG: String = "DetailActivity"
        private const val EXTERNAL_UPDATE_REFRESH_MAX_RETRIES: Int = 3
        private const val DETAIL_ATTACHMENT_VIDEO_END_FRAME_GUARD_MS: Int = 50

        const val CREATE: Int = 0
        const val UPDATE: Int = 1

        /** 打开后直接生成长截图并分享（首页选择模式的分享界面里选了「长截图」）。 */
        const val KEY_SHARE_LONG_SCREENSHOT: String = "share_long_screenshot"

        /** 打开后等附件的缩略图加载出来再截，否则长截图里是空白的占位。 */
        private const val LONG_SCREENSHOT_DELAY_MS: Long = 700L

        @JvmField var createActivitiesCount: Int = 0

        /**
         * Phase 4.e: open the DetailActivity in CREATE mode with a full ThingBackground.
         */
        @JvmStatic
        fun getOpenIntentForCreate(
            context: Context?,
            senderName: String?,
            bg: ThingBackground?,
            folderId: Long? = null
        ): Intent {
            val intent = Intent(context, DetailActivity::class.java)
            intent.putExtra(Def.Communication.KEY_SENDER_NAME, senderName)
            intent.putExtra(Def.Communication.KEY_DETAIL_ACTIVITY_TYPE, CREATE)
            if (folderId != null) {
                intent.putExtra(Def.Communication.KEY_FOLDER_ID, folderId)
            }
            if (bg != null) {
                intent.putExtra(Def.Communication.KEY_COLOR,      bg.representativeColor())
                intent.putExtra(Def.Communication.KEY_BACKGROUND, bg.toJson())
            }
            return intent
        }

        @JvmStatic
        fun getOpenIntentForUpdate(
            context: Context?,
            senderName: String?,
            id: Long,
            thingIndex: Int,
            listPosition: Int = -1,
            listProjectionKey: String? = null
        ): Intent {
            if (App.getDoingThingId() == id) {
                return DoingActivity.getOpenIntent(context, true)
            } else {
                val intent = Intent(context, DetailActivity::class.java)
                intent.putExtra(Def.Communication.KEY_SENDER_NAME, senderName)
                intent.putExtra(Def.Communication.KEY_DETAIL_ACTIVITY_TYPE, UPDATE)
                intent.putExtra(Def.Communication.KEY_ID, id)
                intent.putExtra(Def.Communication.KEY_POSITION, thingIndex)
                intent.putExtra(Def.Communication.KEY_LIST_POSITION, listPosition)
                if (listProjectionKey != null) {
                    intent.putExtra(Def.Communication.KEY_LIST_PROJECTION, listProjectionKey)
                }
                return intent
            }
        }
    }
}
