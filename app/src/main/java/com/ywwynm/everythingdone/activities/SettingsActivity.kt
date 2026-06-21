@file:Suppress("DEPRECATION", "UNCHECKED_CAST", "OVERRIDE_DEPRECATION")

package com.ywwynm.everythingdone.activities

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.AsyncTask
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.graphics.drawable.Drawable
import androidx.annotation.StringRes
import androidx.appcompat.app.ActionBar
import androidx.appcompat.widget.Toolbar
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

import com.google.android.material.snackbar.Snackbar
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.FrequentSettings
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.appwidgets.AppWidgetHelper
import com.ywwynm.everythingdone.database.HabitDAO
import com.ywwynm.everythingdone.database.ThingDAO
import com.ywwynm.everythingdone.fragments.AlertDialogFragment
import com.ywwynm.everythingdone.fragments.ChooserDialogFragment
import com.ywwynm.everythingdone.fragments.LoadingDialogFragment
import com.ywwynm.everythingdone.fragments.PatternLockDialogFragment
import com.ywwynm.everythingdone.fragments.TwoOptionsDialogFragment
import com.ywwynm.everythingdone.helpers.AlarmHelper
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.helpers.AuthenticationHelper
import com.ywwynm.everythingdone.helpers.AutoNotifyHelper
import com.ywwynm.everythingdone.helpers.BackupHelper
import com.ywwynm.everythingdone.helpers.DailyTodoHelper
import com.ywwynm.everythingdone.helpers.FingerprintHelper
import com.ywwynm.everythingdone.helpers.NotificationReliabilityHelper
import com.ywwynm.everythingdone.helpers.ThingDoingHelper
import com.ywwynm.everythingdone.model.DoingRecord
import com.ywwynm.everythingdone.model.HabitReminder
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.permission.SimplePermissionCallback
import com.ywwynm.everythingdone.receivers.LocaleChangeReceiver
import com.ywwynm.everythingdone.services.DoingService
import com.ywwynm.everythingdone.utils.AppearanceUtil
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.DateTimeUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.EdgeEffectUtil
import com.ywwynm.everythingdone.utils.FileUtil
import com.ywwynm.everythingdone.utils.LocaleUtil
import com.ywwynm.everythingdone.utils.StringUtil
import com.ywwynm.everythingdone.utils.SystemNotificationUtil
import com.ywwynm.everythingdone.utils.UriPathConverter

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.ArrayList

@SuppressLint("CommitPrefEdits")
class SettingsActivity : EverythingDoneBaseActivity() {

    private var mPreferences: SharedPreferences? = null

    private var mNightModeMask: Int = 0

    private var mTvDrawerHeader: TextView? = null
    private var mTvLanguage: TextView? = null
    private var mCbFollowSystemDarkMode: CheckBox? = null
    private var mRlForceDarkModeAsBt: RelativeLayout? = null
    private var mCbForceDarkMode: CheckBox? = null
    private var mCbNn: CheckBox? = null

    private var mLlBatteryOptimization: LinearLayout? = null
    private var mTvBatteryOptimizationStatus: TextView? = null
    private var mLlAutostart: LinearLayout? = null
    private var mTvAutostartStatus: TextView? = null
    private var mLlPostNotifications: LinearLayout? = null
    private var mTvPostNotificationsStatus: TextView? = null
    private var mLlChannelsStatus: LinearLayout? = null
    private var mTvChannelsStatus: TextView? = null
    private var mLlFullScreenIntent: LinearLayout? = null
    private var mTvFullScreenIntentStatus: TextView? = null

    private var mCbToggleCli: CheckBox? = null
    private var mToggleCliOtc: Boolean = false

    private var mCbSimpleFCli: CheckBox? = null
    private var mSimpleFCli: Boolean = false

    private var mCbAutoLink: CheckBox? = null
    private var mCbTwiceBack: CheckBox? = null
    private var mCbCreateAnimationStyle: CheckBox? = null

    private var mRingtoneManager: RingtoneManager? = null
    private var mChosenRingtoneUris: Array<Uri?>? = null
    private var mChosenRingtoneTitles: Array<String?>? = null
    private var mChoosingIndex: Int = 0
    private var mPlayingRingtone: Ringtone? = null
    private var mLlsRingtone: Array<LinearLayout?>? = null
    private var mTvsRingtone: Array<TextView?>? = null
    private var mCdfsRingtone: Array<ChooserDialogFragment?>? = null

    private var mTvASE: TextView? = null
    private var mAutoSaveEdits: Boolean = false

    private var mTvRestoreLastInfo: TextView? = null
    private var mLdfBackup: LoadingDialogFragment? = null
    private var mLdfRestore: LoadingDialogFragment? = null

    private var mRlFgprtAsBt: RelativeLayout? = null
    private var mCbFgprt: CheckBox? = null

    private var mTvASD: TextView? = null
    private var mASDPicked: Int = 0

    private var mLlASDTimes: Array<LinearLayout?>? = null
    private var mTvASDTimeTitles: Array<TextView?>? = null
    private var mTvASDTimes: Array<TextView?>? = null
    private var mASDTimesPicked: IntArray = intArrayOf(0, 0)

    private var mTvASM: TextView? = null
    private var mASMPicked: Int = 0

    private var mCbQuickCreate: CheckBox? = null
    private var mCbCloseNotificationLater: CheckBox? = null
    private var mCbOngoingLockscreen: CheckBox? = null

    private var mDTPicked: Int = 0
    private var mTvDT: TextView? = null

    private var mANPicked: Int = 0
    private var mTvAN: TextView? = null
    private var mCdfAN: ChooserDialogFragment? = null

    private var mLlANRingtoneAsBt: LinearLayout? = null
    private var mTvANRingtoneTitle: TextView? = null
    private var mTvANRingtone: TextView? = null

    private fun initSystemRingtoneList(ldf: LoadingDialogFragment, index: Int) {
        sRingtoneTitleList = ArrayList()
        sRingtoneUriList   = ArrayList()
        object : Thread() {
            override fun run() {
                val context: Context = App.getApp()!!
                val manager = RingtoneManager(context)
                manager.setType(RingtoneManager.TYPE_NOTIFICATION)

                val defaultUri: Uri? = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                if (defaultUri != null) {
                    val dr: Ringtone? = RingtoneManager.getRingtone(context, defaultUri)
                    if (dr != null) {
                        sRingtoneUriList!!.add(defaultUri)
                        sRingtoneTitleList!!.add(StringUtil.replaceChineseBrackets(dr.getTitle(context)))
                    }
                }

                val preferences: SharedPreferences = context.getSharedPreferences(
                    Def.Meta.PREFERENCES_NAME, MODE_PRIVATE
                )
                for (key in sKeysRingtone) {
                    val uriStr: String = preferences.getString(key, FOLLOW_SYSTEM)!!
                    if (FOLLOW_SYSTEM == uriStr) continue

                    val uri: Uri = Uri.parse(uriStr)
                    if (manager.getRingtonePosition(uri) != -1) continue

                    val pathName: String? = UriPathConverter.getLocalPathName(context, uri)
                    if (pathName == null) {
                        preferences.edit().putString(key, FOLLOW_SYSTEM).apply()
                        continue
                    }

                    val file = File(pathName)
                    if (!file.exists()) {
                        preferences.edit().putString(key, FOLLOW_SYSTEM).apply()
                    } else if (!sRingtoneUriList!!.contains(uri)) {
                        sRingtoneUriList!!.add(uri)
                        sRingtoneTitleList!!.add(getRingtoneTitle(context, manager, uri))
                    }
                }

                val cursor = manager.cursor
                val count = cursor.count
                for (i in 0 until count) {
                    sRingtoneUriList!!.add(manager.getRingtoneUri(i))
                    sRingtoneTitleList!!.add(
                        StringUtil.replaceChineseBrackets(manager.getRingtone(i).getTitle(context))
                    )
                }
                cursor.close()

                val handler = Handler(Looper.getMainLooper())
                handler.post {
                    ldf.dismiss()
                    showRingtoneDialog(index)
                }
            }
        }.start()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode == RESULT_OK) {
            val uri: Uri = data?.data ?: return

            if (requestCode == Def.Communication.REQUEST_CREATE_BACKUP_FILE) {
                showBackupLoadingDialog()
                BackupTask().execute(uri)
                return
            }

            if (requestCode == Def.Communication.REQUEST_CHOOSE_BACKUP_FILE) {
                startToRestore(uri)
                return
            }

            val pathName: String? = UriPathConverter.getLocalPathName(this, uri)
            val root: View = f(R.id.rl_settings_root)!!
            if (pathName == null) {
                Snackbar.make(root, R.string.error_cannot_add_from_network, Snackbar.LENGTH_SHORT).show()
                return
            }

            val postfix: String? = FileUtil.getPostfix(pathName)
            if (!isSupportedFilePostfix(requestCode, postfix)) {
                Snackbar.make(root, R.string.error_unsupported_file_type, Snackbar.LENGTH_SHORT).show()
                return
            }

            if (requestCode == Def.Communication.REQUEST_CHOOSE_IMAGE_FILE) {
                mTvDrawerHeader!!.textSize = 12f
                mTvDrawerHeader!!.text = pathName
            } else if (requestCode == Def.Communication.REQUEST_CHOOSE_AUDIO_FILE) {
                setFileRingtone(pathName)
            }
        }
    }

    private fun isSupportedFilePostfix(requestCode: Int, postfix: String?): Boolean {
        return when (requestCode) {
            Def.Communication.REQUEST_CHOOSE_IMAGE_FILE -> AttachmentHelper.isImageFile(postfix)
            Def.Communication.REQUEST_CHOOSE_AUDIO_FILE -> AttachmentHelper.isAudioFile(postfix)
            Def.Communication.REQUEST_CHOOSE_BACKUP_FILE -> BackupHelper.isSupportedBackupFilePostfix(postfix)
            else -> false
        }
    }

    private fun setFileRingtone(pathName: String) {
        val audioName: String = FileUtil.getNameWithoutPostfix(pathName)!!
        val srcFile = File(pathName)
        val dstFile: File = FileUtil.createFile(
            Def.getAppFileDir(this) + "/ringtone", srcFile.name
        )!!
        try {
            FileUtil.copyFile(srcFile, dstFile)
        } catch (e: IOException) {
            e.printStackTrace()
        }
        val uri: Uri = FileProvider.getUriForFile(this, "com.ywwynm.everythingdone", dstFile)
        if (!sRingtoneUriList!!.contains(uri)) {
            sRingtoneTitleList!!.add(1, audioName)
            sRingtoneUriList!!.add(1, uri)
        }
        mCdfsRingtone!![mChoosingIndex]!!.pick(1)
        mCdfsRingtone!![mChoosingIndex]!!.notifyDataSetChanged()

        val ringtone: Ringtone = RingtoneManager.getRingtone(this, uri)!!
        ringtone.play()
        mPlayingRingtone = ringtone
        f<View>(R.id.rl_settings_root).postDelayed({
            if (ringtone.isPlaying) {
                ringtone.stop()
            }
        }, 6000)
    }

    override fun getLayoutResource(): Int = R.layout.activity_settings

    override fun onResume() {
        super.onResume()
        initUiPrivacy()
        updateNotificationReliabilityUi()
    }

    private fun updateNotificationReliabilityUi() {
        if (mTvBatteryOptimizationStatus == null) return

        val notif: Boolean = NotificationReliabilityHelper.areNotificationsEnabled(this)
        mTvPostNotificationsStatus!!.setText(
            if (notif) R.string.settings_notifications_enabled_on
            else R.string.settings_notifications_enabled_off
        )

        val disabled: List<String?> = NotificationReliabilityHelper.getDisabledCriticalChannels(this)
        val total: Int = NotificationReliabilityHelper.CRITICAL_CHANNEL_IDS.size
        if (disabled.isEmpty()) {
            mTvChannelsStatus!!.text = getString(R.string.settings_channels_all_on, total)
        } else {
            mTvChannelsStatus!!.text = getString(
                R.string.settings_channels_some_off, disabled.size, total
            )
        }

        val fsi: Boolean = NotificationReliabilityHelper.canUseFullScreenIntent(this)
        mTvFullScreenIntentStatus!!.setText(
            if (fsi) R.string.settings_full_screen_intent_on
            else R.string.settings_full_screen_intent_off
        )

        val ignored: Boolean = NotificationReliabilityHelper.isBatteryOptimizationIgnored(this)
        mTvBatteryOptimizationStatus!!.setText(
            if (ignored) R.string.settings_battery_optimization_on
            else R.string.settings_battery_optimization_off
        )

        mTvAutostartStatus!!.setText(
            if (NotificationReliabilityHelper.needsVendorAutostartHint())
                R.string.settings_autostart_desc
            else R.string.settings_autostart_not_needed
        )
    }

    override fun onStop() {
        super.onStop()
        if (mPlayingRingtone != null && mPlayingRingtone!!.isPlaying) {
            mPlayingRingtone!!.stop()
        }
    }

    override fun initMembers() {
        initStaticVariables()

        mPreferences = getSharedPreferences(Def.Meta.PREFERENCES_NAME, MODE_PRIVATE)
        mNightModeMask = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK

        initMembersRingtone()

        mASDTimesPicked = IntArray(2)
    }

    private fun initStaticVariables() {
        if (sANItems == null) {
            sANItems = ArrayList()
            sANItems!!.add(getString(R.string.disable))
            for (i in AutoNotifyHelper.AUTO_NOTIFY_TIMES.indices) {
                sANItems!!.add(
                    DateTimeUtil.getDateTimeStr(
                        AutoNotifyHelper.AUTO_NOTIFY_TYPES[i],
                        AutoNotifyHelper.AUTO_NOTIFY_TIMES[i], this
                    )
                )
            }
        }

        if (sDTItems == null) {
            sDTItems = DailyTodoHelper.getDailyTodoItems(this).toMutableList()
        }
    }

    private fun initMembersRingtone() {
        mRingtoneManager = RingtoneManager(this)
        mRingtoneManager!!.setType(RingtoneManager.TYPE_NOTIFICATION)

        mChosenRingtoneUris   = arrayOfNulls(4)
        mChosenRingtoneTitles = arrayOfNulls(4)
        mCdfsRingtone         = arrayOfNulls(4)

        var defaultUri: Uri? = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        if (defaultUri == null) defaultUri = sRingtoneUriList!![0]
        for (i in mChosenRingtoneUris!!.indices) {
            val value: String = mPreferences!!.getString(sKeysRingtone[i], FOLLOW_SYSTEM)!!
            if (FOLLOW_SYSTEM == value) {
                mChosenRingtoneUris!![i] = defaultUri
            } else {
                val uri: Uri = Uri.parse(value)
                mChosenRingtoneUris!![i] = uri
                if (isFileRingtone(mRingtoneManager!!, uri)) {
                    val pathName: String? = UriPathConverter.getLocalPathName(this, uri)
                    if (pathName == null || !File(pathName).exists()) {
                        mChosenRingtoneUris!![i] = defaultUri
                    }
                }
            }

            mChosenRingtoneTitles!![i] = getRingtoneTitle(
                this, mRingtoneManager!!, mChosenRingtoneUris!![i]!!
            )
        }
    }

    override fun findViews() {
        mTvDrawerHeader = f(R.id.tv_drawer_header_path)
        mTvLanguage     = f(R.id.tv_app_language)
        mCbFollowSystemDarkMode = f(R.id.cb_follow_system_dark_mode)
        mRlForceDarkModeAsBt    = f(R.id.rl_force_dark_mode_as_bt)
        mCbForceDarkMode        = f(R.id.cb_force_dark_mode)
        mCbNn           = f(R.id.cb_noticeable_notification)

        mLlBatteryOptimization       = f(R.id.ll_battery_optimization_as_bt)
        mTvBatteryOptimizationStatus = f(R.id.tv_battery_optimization_status)
        mLlAutostart                 = f(R.id.ll_autostart_as_bt)
        mTvAutostartStatus           = f(R.id.tv_autostart_status)
        mLlPostNotifications         = f(R.id.ll_post_notifications_as_bt)
        mTvPostNotificationsStatus   = f(R.id.tv_post_notifications_status)
        mLlChannelsStatus            = f(R.id.ll_channels_status_as_bt)
        mTvChannelsStatus            = f(R.id.tv_channels_status)
        mLlFullScreenIntent          = f(R.id.ll_full_screen_intent_as_bt)
        mTvFullScreenIntentStatus    = f(R.id.tv_full_screen_intent_status)

        mCbToggleCli  = f(R.id.cb_toggle_checklist)
        mCbSimpleFCli = f(R.id.cb_simple_finished_checklist)
        mCbAutoLink   = f(R.id.cb_auto_link)
        mCbTwiceBack  = f(R.id.cb_twice_back)
        mCbCreateAnimationStyle = f(R.id.cb_create_animation_style)

        mLlsRingtone    = arrayOfNulls(4)
        mLlsRingtone!![0] = f(R.id.ll_ringtone_reminder_as_bt)
        mLlsRingtone!![1] = f(R.id.ll_ringtone_habit_as_bt)
        mLlsRingtone!![2] = f(R.id.ll_ringtone_goal_as_bt)

        mTvsRingtone    = arrayOfNulls(4)
        mTvsRingtone!![0] = f(R.id.tv_ringtone_reminder)
        mTvsRingtone!![1] = f(R.id.tv_ringtone_habit)
        mTvsRingtone!![2] = f(R.id.tv_ringtone_goal)

        mTvASE             = f(R.id.tv_auto_save_edits)
        mTvRestoreLastInfo = f(R.id.tv_restore_last_info)

        mRlFgprtAsBt = f(R.id.rl_use_fingerprint_as_bt)
        mCbFgprt     = f(R.id.cb_use_fingerprint)

        mTvASD = f(R.id.tv_auto_start_doing)

        mLlASDTimes    = arrayOfNulls(2)
        mLlASDTimes!![0] = f(R.id.ll_asd_time_reminder_as_bt)
        mLlASDTimes!![1] = f(R.id.ll_asd_time_habit_as_bt)

        mTvASDTimeTitles    = arrayOfNulls(2)
        mTvASDTimeTitles!![0] = f(R.id.tv_asd_time_reminder_title)
        mTvASDTimeTitles!![1] = f(R.id.tv_asd_time_habit_title)

        mTvASDTimes    = arrayOfNulls(2)
        mTvASDTimes!![0] = f(R.id.tv_asd_time_reminder)
        mTvASDTimes!![1] = f(R.id.tv_asd_time_habit)

        mTvASM = f(R.id.tv_auto_strict_mode)

        mCbQuickCreate            = f(R.id.cb_quick_create)
        mCbCloseNotificationLater = f(R.id.cb_close_notification_later)
        mCbOngoingLockscreen      = f(R.id.cb_ongoing_lockscreen)

        mTvDT = f(R.id.tv_daily_todo)
        mTvAN = f(R.id.tv_advanced_auto_notify_time)

        mLlANRingtoneAsBt  = f(R.id.ll_ringtone_auto_notify_as_bt)
        mTvANRingtoneTitle = f(R.id.tv_ringtone_auto_notify_title)
        mTvANRingtone      = f(R.id.tv_ringtone_auto_notify)

        mLlsRingtone!![3] = mLlANRingtoneAsBt
        mTvsRingtone!![3] = mTvANRingtone
    }

    override fun initUI() {
        DisplayUtil.expandLayoutToStatusBarAboveLollipop(this)
        DisplayUtil.expandStatusBarViewAboveKitkat(f(R.id.view_status_bar))
        DisplayUtil.darkStatusBar(this)

        val accentBg = App.defaultAccentBackground
        BackgroundUtil.applyBackground(f<View>(R.id.view_status_bar), accentBg)
        BackgroundUtil.applyBackground(f<View>(R.id.actionbar), accentBg)
        applyGradientCheckBoxes()

        val svSettings: ScrollView = f(R.id.sv_settings)!!
        EdgeEffectUtil.forScrollView(svSettings, App.defaultAccentBackground.color)
        DisplayUtil.applyBottomInsetAsScrollPadding(svSettings)
        tintSettingsIconsForAppearance()
        applySettingsGroupTitleAccents()
        installLocalButtonRipples()

        initUiUserInterface()
        initUiRingtone()
        initUiData()
        initUiStartDoing()
        initUiAdvanced()
    }

    private fun installLocalButtonRipples() {
        BackgroundUtil.installAppChromeCircleRipple(f(R.id.iv_auto_save_edits_help_as_bt), this)
        BackgroundUtil.installAppChromeCircleRipple(f(R.id.iv_auto_strict_mode_help_as_bt), this)
        BackgroundUtil.installAppChromeCircleRipple(f(R.id.iv_daily_todo_help_as_bt), this)
        BackgroundUtil.installAppChromeCircleRipple(f(R.id.iv_auto_notify_help_as_bt), this)
    }

    private fun applySettingsGroupTitleAccents() {
        val bg = App.defaultAccentBackground
        applySettingsGroupTitleAccent(R.id.tv_title_group_ui_settings, bg)
        applySettingsGroupTitleAccent(
            R.id.tv_title_group_notification_reliability_settings,
            bg,
            iconOffsetXDp = -2
        )
        applySettingsGroupTitleAccent(R.id.tv_title_group_ringtone_settings, bg)
        applySettingsGroupTitleAccent(R.id.tv_title_group_data_settings, bg)
        applySettingsGroupTitleAccent(R.id.tv_title_group_privacy_settings, bg)
        applySettingsGroupTitleAccent(
            R.id.tv_title_group_start_doing_settings,
            bg,
            ContextCompat.getDrawable(this, R.drawable.vec_ic_start_thing),
            22
        )
        applySettingsGroupTitleAccent(R.id.tv_title_group_advanced_settings, bg)
    }

    private fun applySettingsGroupTitleAccent(
        textViewId: Int,
        bg: ThingBackground,
        iconOverride: Drawable? = null,
        iconSizeDp: Int = 20,
        iconOffsetXDp: Int = 0
    ) {
        val tv: TextView = f(textViewId) ?: return
        BackgroundUtil.applyTextBackground(tv, bg)
        val source = iconOverride
            ?: tv.compoundDrawablesRelative[0]
            ?: tv.compoundDrawables[0]
            ?: return
        val icon = BackgroundUtil.tintDrawable(resources, source, bg) ?: return
        val size = (iconSizeDp * resources.displayMetrics.density).toInt()
        val offsetX = (iconOffsetXDp * resources.displayMetrics.density).toInt()
        icon.setBounds(offsetX, 0, offsetX + size, size)
        tv.setCompoundDrawablesRelative(icon, null, null, null)
    }

    private fun tintSettingsIconsForAppearance() {
        val tint = ContextCompat.getColor(this, R.color.app_chrome_control_unchecked)
        tintSettingsIcons(f(R.id.sv_settings), tint)
    }

    private fun tintSettingsIcons(view: View?, tint: Int) {
        when (view) {
            is TextView -> tintTextViewCompoundDrawables(view)
            is ImageView -> {
                if (AppearanceUtil.isDarkMode(this)) {
                    view.setImageDrawable(
                        DisplayUtil.opaqueTintDrawable(this, view.drawable, tint)
                    )
                }
            }
            is ViewGroup -> {
                for (i in 0 until view.childCount) {
                    tintSettingsIcons(view.getChildAt(i), tint)
                }
            }
        }
    }

    private fun tintTextViewCompoundDrawables(view: TextView) {
        val drawables = view.compoundDrawablesRelative
        var changed = false
        for (i in drawables.indices) {
            val drawable = drawables[i]
            if (drawable != null) {
                drawables[i] = DisplayUtil.opaqueTintDrawable(
                    this, drawable, view.currentTextColor
                )
                changed = true
            }
        }
        if (changed) {
            view.setCompoundDrawablesRelativeWithIntrinsicBounds(
                drawables[0], drawables[1], drawables[2], drawables[3]
            )
        }
    }

    private fun applyGradientCheckBoxes() {
        val bg = App.defaultAccentBackground
        listOf(
            mCbFollowSystemDarkMode, mCbForceDarkMode, mCbNn,
            mCbToggleCli, mCbSimpleFCli, mCbAutoLink, mCbTwiceBack,
            mCbCreateAnimationStyle, mCbFgprt, mCbQuickCreate,
            mCbCloseNotificationLater, mCbOngoingLockscreen
        ).forEach { if (it != null) BackgroundUtil.applyCheckboxAccent(it, bg) }
    }

    private fun initUiUserInterface() {
        val header: String = mPreferences!!.getString(
            Def.Meta.KEY_DRAWER_HEADER, DEFAULT_DRAWER_HEADER
        )!!
        if (DEFAULT_DRAWER_HEADER == header) {
            mTvDrawerHeader!!.textSize = 14f
            mTvDrawerHeader!!.setText(R.string.default_drawer_header)
        } else {
            mTvDrawerHeader!!.textSize = 12f
            mTvDrawerHeader!!.text = header
        }

        val languageCode: String = FrequentSettings.getString(
            Def.Meta.KEY_LANGUAGE_CODE, LocaleUtil.LANGUAGE_CODE_FOLLOW_SYSTEM + "_"
        )!!
        mTvLanguage!!.text = LocaleUtil.getLanguageDescription(languageCode)

        mCbFollowSystemDarkMode!!.isChecked = mPreferences!!.getBoolean(
            Def.Meta.KEY_FOLLOW_SYSTEM_DARK_MODE, false
        )
        mCbForceDarkMode!!.isChecked = mPreferences!!.getBoolean(
            Def.Meta.KEY_FORCE_DARK_MODE, false
        )
        updateUiAppearanceMode()

        val nn: Boolean = mPreferences!!.getBoolean(Def.Meta.KEY_NOTICEABLE_NOTIFICATION, true)
        mCbNn!!.isChecked = nn

        mToggleCliOtc = mPreferences!!.getBoolean(Def.Meta.KEY_TOGGLE_CLI_OTC, false)
        mCbToggleCli!!.isChecked = mToggleCliOtc

        mSimpleFCli = mPreferences!!.getBoolean(Def.Meta.KEY_SIMPLE_FCLI, false)
        mCbSimpleFCli!!.isChecked = mSimpleFCli

        val autoLink: Boolean = mPreferences!!.getBoolean(Def.Meta.KEY_AUTO_LINK, true)
        mCbAutoLink!!.isChecked = autoLink

        val twiceBack: Boolean = mPreferences!!.getBoolean(Def.Meta.KEY_TWICE_BACK, false)
        mCbTwiceBack!!.isChecked = twiceBack

        val createAnimationStyle: Boolean = mPreferences!!.getBoolean(
            Def.Meta.KEY_CREATE_ANIMATION_STYLE, false
        )
        mCbCreateAnimationStyle!!.isChecked = createAnimationStyle
    }

    private fun initUiRingtone() {
        for (i in 0 until 3) {
            mTvsRingtone!![i]!!.text = mChosenRingtoneTitles!![i]
        }
    }

    private fun initUiData() {
        mAutoSaveEdits = FrequentSettings.getBoolean(Def.Meta.KEY_AUTO_SAVE_EDITS)
        mTvASE!!.setText(if (mAutoSaveEdits) R.string.enabled else R.string.disabled)

        updateUiRestore()
    }

    private fun updateUiRestore() {
        mTvRestoreLastInfo!!.text = BackupHelper.getLastBackupTimeString()
    }

    private fun initUiPrivacy() {
        val password: String? = mPreferences!!.getString(Def.Meta.KEY_PRIVATE_PASSWORD, null)
        val tv: TextView = f(R.id.tv_set_password_title)!!
        if (password == null) {
            tv.setText(R.string.set_app_password)
        } else {
            tv.setText(R.string.change_app_password)
        }

        initUiFingerprint()
    }

    private fun initUiFingerprint() {
        val tvTitle: TextView  = f(R.id.tv_use_fingerprint_title)!!
        val tvDscrpt: TextView = f(R.id.tv_use_fingerprint_description)!!

        val fph: FingerprintHelper = FingerprintHelper.getInstance()!!
        val password: String? = mPreferences!!.getString(Def.Meta.KEY_PRIVATE_PASSWORD, null)
        if (password == null || !fph.isFingerprintReady()) {
            mRlFgprtAsBt!!.isEnabled = false
            mCbFgprt!!.isEnabled = false
            tvTitle.setTextColor(ContextCompat.getColor(this, R.color.app_chrome_divider))
            tvDscrpt.setTextColor(ContextCompat.getColor(this, R.color.app_chrome_ripple))

            if (password == null) {
                tvDscrpt.setText(R.string.password_not_set)
            } else {
                tvDscrpt.textSize = if (LocaleUtil.isChinese(this)) 14f else 12f
                if (!fph.supportFingerprint()) {
                    tvDscrpt.setText(R.string.not_support_fgprt)
                } else if (!fph.hasSystemFingerprintSet()) {
                    tvDscrpt.setText(R.string.system_fgprt_not_set)
                } else if (!fph.hasFingerprintRegistered()) {
                    tvDscrpt.setText(R.string.fgprt_not_enrolled)
                }
            }
        } else {
            mRlFgprtAsBt!!.isEnabled = true
            mCbFgprt!!.isEnabled = true
            tvTitle.setTextColor(ContextCompat.getColor(this, R.color.app_chrome_on_surface_secondary))
            tvDscrpt.setTextColor(ContextCompat.getColor(this, R.color.app_chrome_on_surface_hint))
            tvDscrpt.setText(R.string.use_fingerprint_to_verify)
        }

        val useFingerprint: Boolean = mPreferences!!.getBoolean(Def.Meta.KEY_USE_FINGERPRINT, false)
        mCbFgprt!!.isChecked = useFingerprint
    }

    private fun initUiStartDoing() {
        mASDPicked = mPreferences!!.getInt(Def.Meta.KEY_AUTO_START_DOING, 0)
        val options: Array<String> = resources.getStringArray(R.array.auto_start_doing_states)
        mTvASD!!.text = options[mASDPicked]

        enableOrDisableASDTimesUi()

        val pickedStr = arrayOfNulls<String>(2)
        pickedStr[0] = mPreferences!!.getString(
            Def.Meta.KEY_ASD_TIME_REMINDER,
            ThingDoingHelper.START_DOING_TIME_FOLLOW_GENERAL_PICKED
        )
        pickedStr[1] = mPreferences!!.getString(
            Def.Meta.KEY_ASD_TIME_HABIT,
            ThingDoingHelper.START_DOING_TIME_FOLLOW_GENERAL_PICKED
        )
        mASDTimesPicked[0] = ThingDoingHelper.getStartDoingTimeIndex(pickedStr[0], false)
        mASDTimesPicked[1] = ThingDoingHelper.getStartDoingTimeIndex(pickedStr[1], false)

        val items: List<String?> = ThingDoingHelper.getStartDoingTimeItems(this)
        mTvASDTimes!![0]!!.text = items[mASDTimesPicked[0]]
        mTvASDTimes!![1]!!.text = items[mASDTimesPicked[1]]

        mASMPicked = mPreferences!!.getInt(Def.Meta.KEY_AUTO_STRICT_MODE, 0)
        mTvASM!!.text = options[mASMPicked]
    }

    private fun enableOrDisableASDTimesUi() {
        val black_54p = ContextCompat.getColor(this, R.color.app_chrome_on_surface_secondary)
        val black_26p = ContextCompat.getColor(this, R.color.app_chrome_on_surface_hint)
        val black_14p = ContextCompat.getColor(this, R.color.app_chrome_divider)
        val black_10p = ContextCompat.getColor(this, R.color.app_chrome_ripple)

        val enabled = booleanArrayOf(mASDPicked % 2 != 0, mASDPicked >= 2)
        for (i in enabled.indices) {
            if (enabled[i]) {
                mLlASDTimes!![i]!!.isEnabled = true
                mTvASDTimeTitles!![i]!!.setTextColor(black_54p)
                mTvASDTimes!![i]!!.setTextColor(black_26p)
            } else {
                mLlASDTimes!![i]!!.isEnabled = false
                mTvASDTimeTitles!![i]!!.setTextColor(black_14p)
                mTvASDTimes!![i]!!.setTextColor(black_10p)
            }
        }
    }

    private fun initUiAdvanced() {
        val qc: Boolean = mPreferences!!.getBoolean(Def.Meta.KEY_QUICK_CREATE, false)
        mCbQuickCreate!!.isChecked = qc

        val closeLater: Boolean = mPreferences!!.getBoolean(Def.Meta.KEY_CLOSE_NOTIFICATION_LATER, false)
        mCbCloseNotificationLater!!.isChecked = closeLater

        val ongoingLockscreen: Boolean = FrequentSettings.getBoolean(Def.Meta.KEY_ONGOING_LOCKSCREEN)
        mCbOngoingLockscreen!!.isChecked = ongoingLockscreen

        mDTPicked = mPreferences!!.getInt(Def.Meta.KEY_DAILY_TODO, 0)
        updateUiDailyTodo()

        mANPicked = mPreferences!!.getInt(Def.Meta.KEY_AUTO_NOTIFY, 0)
        updateUiAutoNotifyRingtone()
    }

    private fun updateUiDailyTodo() {
        if (mDTPicked == 0) {
            mTvDT!!.setText(R.string.disabled)
        } else {
            mTvDT!!.text = sDTItems!![mDTPicked]
        }
    }

    private fun updateUiAutoNotifyRingtone() {
        if (mANPicked == 0) {
            mTvAN!!.setText(R.string.disabled)
            mLlANRingtoneAsBt!!.isEnabled = false
            mTvANRingtoneTitle!!.setTextColor(ContextCompat.getColor(this, R.color.app_chrome_divider))
            mTvANRingtone!!.text = ""
        } else {
            mTvAN!!.text = sANItems!![mANPicked - 1]
            mLlANRingtoneAsBt!!.isEnabled = true
            mTvANRingtoneTitle!!.setTextColor(ContextCompat.getColor(this, R.color.app_chrome_on_surface_secondary))
            mTvANRingtone!!.text = mChosenRingtoneTitles!![mChosenRingtoneTitles!!.size - 1]
        }
    }

    override fun setActionbar() {
        val toolbar: Toolbar = f(R.id.actionbar)!!
        setSupportActionBar(toolbar)
        val actionBar: ActionBar? = supportActionBar
        actionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    override fun setEvents() {
        setUiEvents()
        setRingtoneEvents()
        setDataEvents()
        setPrivacyEvents()
        setStartDoingEvents()
        setAdvancedEvents()
    }

    private fun setUiEvents() {
        f<View>(R.id.ll_change_drawer_header_as_bt).setOnClickListener {
            showChangeDrawerHeaderDialog()
        }

        f<View>(R.id.ll_app_language_as_bt).setOnClickListener {
            showChooseLanguageDialog()
        }

        f<View>(R.id.rl_follow_system_dark_mode_as_bt).setOnClickListener {
            mCbFollowSystemDarkMode!!.isChecked = !mCbFollowSystemDarkMode!!.isChecked
            persistAppearanceModeAndApply()
        }

        mRlForceDarkModeAsBt!!.setOnClickListener {
            if (!mRlForceDarkModeAsBt!!.isEnabled) return@setOnClickListener
            mCbForceDarkMode!!.isChecked = !mCbForceDarkMode!!.isChecked
            persistAppearanceModeAndApply()
        }

        f<View>(R.id.rl_noticeable_notification_as_bt).setOnClickListener {
            mCbNn!!.isChecked = !mCbNn!!.isChecked
        }

        mLlPostNotifications!!.setOnClickListener {
            if (NotificationReliabilityHelper.areNotificationsEnabled(this@SettingsActivity)) {
                NotificationReliabilityHelper.openAppNotificationSettings(this@SettingsActivity)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(
                    this@SettingsActivity, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                doWithPermissionChecked(
                    object : SimplePermissionCallback(this@SettingsActivity) {
                        override fun onDenied() {
                            super.onDenied()
                            NotificationReliabilityHelper.openAppNotificationSettings(this@SettingsActivity)
                        }
                    },
                    Def.Communication.REQUEST_PERMISSION_NOTIFICATION,
                    Manifest.permission.POST_NOTIFICATIONS
                )
            } else {
                NotificationReliabilityHelper.openAppNotificationSettings(this@SettingsActivity)
            }
        }

        mLlChannelsStatus!!.setOnClickListener {
            val disabled: List<String?> = NotificationReliabilityHelper
                .getDisabledCriticalChannels(this@SettingsActivity)
            if (!disabled.isEmpty()) {
                NotificationReliabilityHelper.openChannelSettings(
                    this@SettingsActivity, disabled[0]!!
                )
            } else {
                NotificationReliabilityHelper.openAppNotificationSettings(this@SettingsActivity)
            }
        }

        mLlFullScreenIntent!!.setOnClickListener {
            NotificationReliabilityHelper.openFullScreenIntentSettings(this@SettingsActivity)
        }

        mLlBatteryOptimization!!.setOnClickListener {
            if (NotificationReliabilityHelper.isBatteryOptimizationIgnored(this@SettingsActivity)) {
                NotificationReliabilityHelper.openAppDetailsSettings(this@SettingsActivity)
            } else {
                NotificationReliabilityHelper.requestIgnoreBatteryOptimization(this@SettingsActivity)
            }
        }

        mLlAutostart!!.setOnClickListener {
            val opened: Boolean = NotificationReliabilityHelper
                .openVendorAutostartSettings(this@SettingsActivity)
            if (!opened) {
                Toast.makeText(
                    this@SettingsActivity, R.string.settings_autostart_unavailable, Toast.LENGTH_LONG
                ).show()
            }
        }

        f<View>(R.id.rl_toggle_checklist_as_bt).setOnClickListener {
            mCbToggleCli!!.isChecked = !mCbToggleCli!!.isChecked
        }
        f<View>(R.id.rl_simple_finished_checklist_as_bt).setOnClickListener {
            mCbSimpleFCli!!.isChecked = !mCbSimpleFCli!!.isChecked
        }
        f<View>(R.id.rl_auto_link_as_bt).setOnClickListener {
            mCbAutoLink!!.isChecked = !mCbAutoLink!!.isChecked
        }
        f<View>(R.id.rl_twice_back_as_bt).setOnClickListener {
            mCbTwiceBack!!.isChecked = !mCbTwiceBack!!.isChecked
        }
        f<View>(R.id.rl_create_animation_style_as_bt).setOnClickListener {
            mCbCreateAnimationStyle!!.isChecked = !mCbCreateAnimationStyle!!.isChecked
        }
    }

    private fun updateUiAppearanceMode() {
        val followSystem = mCbFollowSystemDarkMode!!.isChecked
        mRlForceDarkModeAsBt!!.visibility = if (followSystem) View.GONE else View.VISIBLE
    }

    private fun persistAppearanceModeAndApply() {
        updateUiAppearanceMode()
        storeConfiguration(false)
        AppearanceUtil.applyDefaultNightMode()
    }

    private fun setRingtoneEvents() {
        for (i in 0 until 4) {
            val j = i
            mLlsRingtone!![j]!!.setOnClickListener {
                if (sRingtoneTitleList == null) {
                    val ldf: LoadingDialogFragment = createLoadingDialog(
                        R.string.please_wait, R.string.ringtone_loading
                    )
                    ldf.show(fragmentManager, LoadingDialogFragment.TAG)
                    initSystemRingtoneList(ldf, j)
                } else {
                    showRingtoneDialog(j)
                }
            }
        }
    }

    private fun showRingtoneDialog(index: Int) {
        if (mCdfsRingtone!![index] == null) {
            initRingtoneFragment(index)
        }
        mCdfsRingtone!![index]!!.show(fragmentManager, ChooserDialogFragment.TAG)
    }

    private fun setDataEvents() {
        f<View>(R.id.rl_auto_save_edits_as_bt).setOnClickListener {
            val cdf = ChooserDialogFragment()
            cdf.setAccentBackground(App.defaultAccentBackground)
            cdf.setTitle(getString(R.string.auto_save_edits_title))
            val items: MutableList<String?> = mutableListOf(
                getString(R.string.enable), getString(R.string.disable)
            )
            cdf.setItems(items)
            cdf.setShouldShowMore(false)
            cdf.setInitialIndex(if (mAutoSaveEdits) 0 else 1)
            cdf.setConfirmListener {
                val picked = cdf.getPickedIndex()
                mAutoSaveEdits = picked == 0
                mTvASE!!.setText(if (mAutoSaveEdits) R.string.enabled else R.string.disabled)
            }
            cdf.show(fragmentManager, ChooserDialogFragment.TAG)
        }
        f<View>(R.id.iv_auto_save_edits_help_as_bt).setOnClickListener {
            createAlertDialog(
                false, R.string.auto_save_edits_title, R.string.auto_save_edits_help_info,
                R.string.act_get_it
            ).show(fragmentManager, AlertDialogFragment.TAG)
        }

        f<View>(R.id.tv_backup_as_bt).setOnClickListener {
            showBackupDialog()
        }
        f<View>(R.id.ll_restore_as_bt).setOnClickListener {
            showRestoreDialog()
        }
    }

    private fun setPrivacyEvents() {
        f<View>(R.id.ll_set_password_as_bt).setOnClickListener {
            val passwordBefore: String? = mPreferences!!.getString(Def.Meta.KEY_PRIVATE_PASSWORD, null)
            if (passwordBefore == null) {
                beginSetPassword()
            } else {
                beginChangePassword(passwordBefore)
            }
        }

        mRlFgprtAsBt!!.setOnClickListener {
            val toUseFingerprint: Boolean = !mCbFgprt!!.isChecked
            if (!toUseFingerprint) {
                val pldf = PatternLockDialogFragment()
                pldf.setValidateTitle(getString(R.string.use_fingerprint))
                pldf.setCorrectPassword(
                    mPreferences!!.getString(Def.Meta.KEY_PRIVATE_PASSWORD, null)
                )
                pldf.setAuthenticationCallback(object : AuthenticationHelper.AuthenticationCallback {
                    override fun onAuthenticated() {
                        mCbFgprt!!.isChecked = false
                    }

                    override fun onCancel() {}
                })
                pldf.setAccentBackground(App.defaultAccentBackground)
                pldf.setType(PatternLockDialogFragment.TYPE_VALIDATE)
                pldf.show(fragmentManager, PatternLockDialogFragment.TAG)
            } else {
                mCbFgprt!!.isChecked = true
            }
        }
    }

    private fun beginSetPassword() {
        val pldf = PatternLockDialogFragment()
        pldf.setType(PatternLockDialogFragment.TYPE_SET)
        pldf.setAccentBackground(App.defaultAccentBackground)
        pldf.setPasswordSetDoneListener {
            mPreferences!!.edit()
                .putString(Def.Meta.KEY_PRIVATE_PASSWORD, pldf.getPassword()).apply()
            initUiPrivacy()
        }
        pldf.show(fragmentManager, PatternLockDialogFragment.TAG)
    }

    private fun beginChangePassword(passwordBefore: String) {
        val pldf = PatternLockDialogFragment()
        pldf.setType(PatternLockDialogFragment.TYPE_VALIDATE)
        pldf.setAccentBackground(App.defaultAccentBackground)
        pldf.setCorrectPassword(passwordBefore)
        pldf.setValidateTitle(getString(R.string.change_app_password))
        pldf.setAuthenticationCallback(object : AuthenticationHelper.AuthenticationCallback {
            override fun onAuthenticated() {
                beginSetPassword()
            }

            override fun onCancel() {}
        })
        pldf.show(fragmentManager, PatternLockDialogFragment.TAG)
    }

    private fun setStartDoingEvents() {
        f<View>(R.id.ll_auto_start_doing_as_bt).setOnClickListener {
            showAutoStartDoingDialog()
        }

        for (i in mLlASDTimes!!.indices) {
            val index = i
            mLlASDTimes!![i]!!.setOnClickListener {
                showAutoStartDoingTimeDialog(index)
            }
        }

        f<View>(R.id.rl_auto_strict_mode_as_bt).setOnClickListener {
            showAutoStrictModeDialog()
        }
        f<View>(R.id.iv_auto_strict_mode_help_as_bt).setOnClickListener {
            val adf: AlertDialogFragment = createAlertDialog(
                false, R.string.doing_alert_first_strict_mode_title,
                R.string.auto_strict_mode_help_content, R.string.act_get_it
            )
            adf.show(fragmentManager, AlertDialogFragment.TAG)
        }
    }

    private fun showAutoStartDoingTimeDialog(index: Int) {
        val cdf = ChooserDialogFragment()
        cdf.setAccentBackground(App.defaultAccentBackground)
        cdf.setShouldShowMore(false)
        @StringRes val titleRes = if (index == 0)
            R.string.auto_start_doing_time_reminder_title
        else R.string.auto_start_doing_time_habit_title
        cdf.setTitle(getString(titleRes))
        cdf.setItems(ThingDoingHelper.getStartDoingTimeItems(this).toMutableList())
        cdf.setInitialIndex(mASDTimesPicked[index])
        cdf.setConfirmListener {
            val picked = cdf.getPickedIndex()
            mASDTimesPicked[index] = picked
            val items: List<String?> = ThingDoingHelper.getStartDoingTimeItems(applicationContext)
            mTvASDTimes!![index]!!.text = items[picked]
        }
        cdf.show(fragmentManager, ChooserDialogFragment.TAG)
    }

    private fun showAutoStartDoingDialog() {
        val cdf: ChooserDialogFragment = createChooserDialogForStartDoing()
        cdf.setTitle(getString(R.string.auto_start_doing_title))
        cdf.setInitialIndex(mASDPicked)
        cdf.setConfirmListener {
            mASDPicked = cdf.getPickedIndex()
            val states: Array<String> = resources.getStringArray(R.array.auto_start_doing_states)
            mTvASD!!.text = states[mASDPicked]
            enableOrDisableASDTimesUi()
        }
        cdf.show(fragmentManager, ChooserDialogFragment.TAG)
    }

    private fun showAutoStrictModeDialog() {
        val cdf: ChooserDialogFragment = createChooserDialogForStartDoing()
        cdf.setTitle(getString(R.string.auto_strict_mode_title))
        cdf.setInitialIndex(mASMPicked)
        cdf.setConfirmListener {
            val states: Array<String> = resources.getStringArray(R.array.auto_start_doing_states)
            mASMPicked = cdf.getPickedIndex()
            mTvASM!!.text = states[mASMPicked]
        }
        cdf.show(fragmentManager, ChooserDialogFragment.TAG)
    }

    private fun createChooserDialogForStartDoing(): ChooserDialogFragment {
        val cdf = ChooserDialogFragment()
        cdf.setAccentBackground(App.defaultAccentBackground)
        cdf.setShouldShowMore(false)
        val options: Array<String> = resources.getStringArray(R.array.auto_start_doing_options)
        cdf.setItems(options.toMutableList() as MutableList<String?>)
        return cdf
    }

    private fun setAdvancedEvents() {
        setQuickCreateEvents()

        f<View>(R.id.rl_close_notification_later_as_bt).setOnClickListener {
            mCbCloseNotificationLater!!.isChecked = !mCbCloseNotificationLater!!.isChecked
        }
        f<View>(R.id.rl_ongoing_lockscreen_as_bt).setOnClickListener {
            val ongoingLockscreen: Boolean = !mCbOngoingLockscreen!!.isChecked
            mCbOngoingLockscreen!!.isChecked = ongoingLockscreen
            FrequentSettings.put(Def.Meta.KEY_ONGOING_LOCKSCREEN, ongoingLockscreen)
            SystemNotificationUtil.tryToCreateThingOngoingNotification(App.getApp())
        }

        f<View>(R.id.ll_daily_todo_as_bt).setOnClickListener {
            showDailyTodoFragment()
        }
        f<View>(R.id.iv_daily_todo_help_as_bt).setOnClickListener {
            val adf: AlertDialogFragment = createAlertDialog(
                false, R.string.create_daily_todo_automatically,
                R.string.create_daily_todo_help_info, R.string.act_get_it
            )
            adf.show(fragmentManager, AlertDialogFragment.TAG)
        }

        f<View>(R.id.ll_advanced_auto_notify_as_bt).setOnClickListener {
            if (mCdfAN == null) {
                initAutoNotifyFragment()
            }
            mCdfAN!!.show(fragmentManager, ChooserDialogFragment.TAG)
        }
        f<View>(R.id.iv_auto_notify_help_as_bt).setOnClickListener {
            val adf: AlertDialogFragment = createAlertDialog(
                false, R.string.auto_notify, R.string.auto_notify_help_info, R.string.act_get_it
            )
            adf.show(fragmentManager, AlertDialogFragment.TAG)
        }
    }

    private fun showDailyTodoFragment() {
        val cdf = ChooserDialogFragment()
        cdf.setAccentBackground(App.defaultAccentBackground)
        cdf.setShouldShowMore(false)
        cdf.setTitle(getString(R.string.daily_todo_set_time_title))
        cdf.setItems(sDTItems)
        cdf.setInitialIndex(mDTPicked)
        cdf.setConfirmListener {
            mDTPicked = cdf.getPickedIndex()
            updateUiDailyTodo()
        }
        cdf.show(fragmentManager, ChooserDialogFragment.TAG)
    }

    private fun setQuickCreateEvents() {
        f<View>(R.id.rl_quick_create_as_bt).setOnClickListener {
            mCbQuickCreate!!.toggle()
        }

        mCbQuickCreate!!.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && ContextCompat.checkSelfPermission(
                        this@SettingsActivity, Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    doWithPermissionChecked(
                        object : SimplePermissionCallback(this@SettingsActivity) {
                            override fun onGranted() {
                                SystemNotificationUtil.createQuickCreateNotification(App.getApp())
                            }
                            override fun onDenied() {
                                super.onDenied()
                                mCbQuickCreate!!.isChecked = false
                            }
                        },
                        Def.Communication.REQUEST_PERMISSION_NOTIFICATION,
                        Manifest.permission.POST_NOTIFICATIONS
                    )
                    return@setOnCheckedChangeListener
                }
                SystemNotificationUtil.createQuickCreateNotification(App.getApp())
            } else {
                NotificationManagerCompat.from(App.getApp()!!).cancel(Def.Meta.ONGOING_NOTIFICATION_ID)
            }
        }
    }

    private fun showChangeDrawerHeaderDialog() {
        val todf = TwoOptionsDialogFragment()
        todf.setStartAction(
            R.drawable.act_default_drawer_header, R.string.default_drawer_header
        ) {
            mTvDrawerHeader!!.textSize = 14f
            mTvDrawerHeader!!.setText(R.string.default_drawer_header)
            todf.dismiss()
        }
        todf.setEndAction(
            R.drawable.act_select_image_as_drawer_header, R.string.more
        ) {
            todf.dismiss()
            startChooseImageAsDrawerHeader()
        }
        todf.show(fragmentManager, TwoOptionsDialogFragment.TAG)
    }

    private fun startChooseImageAsDrawerHeader() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.setType("image/*")
        startActivityForResult(
            Intent.createChooser(intent, getString(R.string.act_choose_image_as_drawer_header)),
            Def.Communication.REQUEST_CHOOSE_IMAGE_FILE
        )
    }

    private fun showChooseLanguageDialog() {
        val cdf = ChooserDialogFragment()
        cdf.setAccentBackground(App.defaultAccentBackground)
        cdf.setTitle(getString(R.string.change_app_language))
        cdf.setShouldShowMore(false)
        val resources: Resources = resources
        val languages: Array<String> = resources.getStringArray(R.array.languages)
        val languageCodes: Array<String> = resources.getStringArray(R.array.language_codes)
        cdf.setItems(languages.toMutableList() as MutableList<String?>)

        val currentLanguageCode: String = FrequentSettings.getString(
            Def.Meta.KEY_LANGUAGE_CODE, LocaleUtil.LANGUAGE_CODE_FOLLOW_SYSTEM + "_"
        )!!
        var index = 0
        for (i in languageCodes.indices) {
            if (LocaleUtil.sameLanguageCode(languageCodes[i], currentLanguageCode)) {
                index = i
                break
            }
        }
        val initialIndex = index
        cdf.setInitialIndex(initialIndex)
        cdf.setConfirmListener(View.OnClickListener {
            val pickedIndex = cdf.getPickedIndex()
            if (pickedIndex == initialIndex) {
                return@OnClickListener
            }
            val context: Context = this@SettingsActivity
            val newLanguageCode: String = languageCodes[pickedIndex]
            FrequentSettings.put(Def.Meta.KEY_LANGUAGE_CODE, newLanguageCode)
            @SuppressLint("ApplySharedPref")
            mPreferences!!.edit().putString(Def.Meta.KEY_LANGUAGE_CODE, newLanguageCode).commit()
            LocaleUtil.applyStoredLanguageToAppCompat(context)
            if (App.getDoingThingId() != -1L) {
                Toast.makeText(context, R.string.doing_failed_change_language, Toast.LENGTH_LONG).show()
                DoingService.sStopReason = DoingRecord.STOP_REASON_CANCEL_OTHER
                stopService(Intent(context, DoingService::class.java))
            }
            App.killMeAndRestart(context, null, 0)

            val intent = Intent(context, LocaleChangeReceiver::class.java)
            intent.setAction(Def.Communication.BROADCAST_ACTION_RESP_LOCALE_CHANGE)
            val pendingIntent: PendingIntent = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val am: AlarmManager = context.getSystemService(ALARM_SERVICE) as AlarmManager
            AlarmHelper.setExactAllowWhileIdleSafe(
                am, System.currentTimeMillis() + 1600, pendingIntent
            )
        })
        cdf.show(fragmentManager, ChooserDialogFragment.TAG)
    }

    private fun showBackupDialog() {
        val adf: AlertDialogFragment = createAlertDialog(
            true, R.string.backup, R.string.backup_content
        )
        adf.setConfirmText(getString(R.string.backup_start))
        adf.setConfirmListener(object : AlertDialogFragment.ConfirmListener {
            override fun onConfirm() {
                authenticateToBackup()
            }
        })
        adf.show(fragmentManager, AlertDialogFragment.TAG)
    }

    private fun authenticateToBackup() {
        val password: String? = mPreferences!!.getString(Def.Meta.KEY_PRIVATE_PASSWORD, null)
        AuthenticationHelper.authenticate(
            this, App.defaultAccentBackground, getString(R.string.backup_start), password,
            object : AuthenticationHelper.AuthenticationCallback {
                override fun onAuthenticated() {
                    startCreateBackupFile()
                }

                override fun onCancel() {}
            }
        )
    }

    private fun startCreateBackupFile() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        intent.setType("*/*")
        val timeStr: String = java.time.ZonedDateTime.now().format(
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
        )
        intent.putExtra(Intent.EXTRA_TITLE, "ED_backup_$timeStr.bak")
        startActivityForResult(intent, Def.Communication.REQUEST_CREATE_BACKUP_FILE)
    }

    private fun showBackupLoadingDialog() {
        if (mLdfBackup == null) {
            mLdfBackup = createLoadingDialog(
                R.string.backup_loading_title, R.string.backup_loading_content
            )
        }
        mLdfBackup!!.show(fragmentManager, LoadingDialogFragment.TAG)
    }

    private fun showRestoreDialog() {
        val adf: AlertDialogFragment = createAlertDialog(
            true, R.string.restore, R.string.restore_content
        )
        adf.setConfirmText(getString(R.string.restore_choose_backup_file))
        adf.setConfirmListener(object : AlertDialogFragment.ConfirmListener {
            override fun onConfirm() {
                authenticateToRestore()
            }
        })
        adf.show(fragmentManager, AlertDialogFragment.TAG)
    }

    private fun authenticateToRestore() {
        val password: String? = mPreferences!!.getString(Def.Meta.KEY_PRIVATE_PASSWORD, null)
        AuthenticationHelper.authenticate(
            this, App.defaultAccentBackground, getString(R.string.restore_choose_backup_file), password,
            object : AuthenticationHelper.AuthenticationCallback {
                override fun onAuthenticated() {
                    startChooseBackupFile()
                }

                override fun onCancel() {}
            }
        )
    }

    private fun startChooseBackupFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.setType("*/*")
        startActivityForResult(
            Intent.createChooser(intent, getString(R.string.restore_choose_backup_file)),
            Def.Communication.REQUEST_CHOOSE_BACKUP_FILE
        )
    }

    private fun startToRestore(uri: Uri) {
        showRestoreLoadingDialog()
        RestoreTask().execute(uri)
    }

    private fun showRestoreLoadingDialog() {
        if (mLdfRestore == null) {
            mLdfRestore = createLoadingDialog(
                R.string.restore_loading_title, R.string.restore_loading_content
            )
        }
        mLdfRestore!!.show(fragmentManager, LoadingDialogFragment.TAG)
    }

    private fun initRingtoneFragment(index: Int) {
        mCdfsRingtone!![index] = ChooserDialogFragment()
        val cdf: ChooserDialogFragment = mCdfsRingtone!![index]!!
        cdf.setAccentBackground(App.defaultAccentBackground)
        cdf.setTitle(getString(R.string.chooser_ringtone))
        cdf.setItems(sRingtoneTitleList)
        cdf.setInitialIndex(sRingtoneUriList!!.indexOf(mChosenRingtoneUris!![index]))
        cdf.setShouldOverScroll(true)
        cdf.setConfirmListener {
            val pickedIndex = cdf.getPickedIndex()
            mTvsRingtone!![index]!!.text = sRingtoneTitleList!![pickedIndex]
            cdf.setInitialIndex(pickedIndex)
            mChosenRingtoneUris!![index] = sRingtoneUriList!![pickedIndex]
            mChosenRingtoneTitles!![index] = sRingtoneTitleList!![pickedIndex]
        }
        cdf.setMoreListener {
            mChoosingIndex = index
            startChooseRingtoneFromStorage()
        }
        cdf.setOnItemClickListener(View.OnClickListener {
            if (mPlayingRingtone != null) {
                mPlayingRingtone!!.stop()
            }

            val pickedIndex = cdf.getPickedIndex()
            if (pickedIndex == -1) {
                throw IllegalStateException(
                    "user picked a ringtone but getPickedIndex returned -1"
                )
            }
            var uri: Uri = sRingtoneUriList!![pickedIndex]
            val context: Context = this@SettingsActivity
            if (isFileRingtone(mRingtoneManager!!, uri)) {
                val pathName: String =
                    UriPathConverter.getLocalPathName(context, uri) ?: return@OnClickListener
                uri = FileProvider.getUriForFile(
                    context, "com.ywwynm.everythingdone", File(pathName)
                )
            }
            val ringtone: Ringtone = RingtoneManager.getRingtone(context, uri)!!
            ringtone.play()
            mPlayingRingtone = ringtone
        })
        cdf.setOnDismissListener(object : ChooserDialogFragment.OnDismissListener {
            override fun onDismiss() {
                if (mPlayingRingtone != null) {
                    mPlayingRingtone!!.stop()
                }
            }
        })
    }

    private fun startChooseRingtoneFromStorage() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.setType("audio/*")
        startActivityForResult(
            Intent.createChooser(intent, getString(R.string.chooser_ringtone)),
            Def.Communication.REQUEST_CHOOSE_AUDIO_FILE
        )
    }

    private fun initAutoNotifyFragment() {
        mCdfAN = ChooserDialogFragment()
        mCdfAN!!.setAccentBackground(App.defaultAccentBackground)
        mCdfAN!!.setShouldShowMore(false)
        mCdfAN!!.setTitle(getString(R.string.auto_notify_set_time))
        mCdfAN!!.setItems(sANItems)
        mCdfAN!!.setInitialIndex(mANPicked)
        mCdfAN!!.setConfirmListener {
            mANPicked = mCdfAN!!.getPickedIndex()
            updateUiAutoNotifyRingtone()
        }
    }

    private fun storeConfiguration(requestNotificationPermission: Boolean = true) {
        val editor: SharedPreferences.Editor = mPreferences!!.edit()

        val headerBefore: String = mPreferences!!.getString(
            Def.Meta.KEY_DRAWER_HEADER, DEFAULT_DRAWER_HEADER
        )!!
        var header: String = DEFAULT_DRAWER_HEADER
        val str: String = mTvDrawerHeader!!.text.toString()
        if (str != getString(R.string.default_drawer_header)) {
            header = str
        }
        editor.putString(Def.Meta.KEY_DRAWER_HEADER, header)
        if (headerBefore != header) {
            setResult(Def.Communication.RESULT_UPDATE_DRAWER_HEADER_DONE)
        }

        editor.putBoolean(Def.Meta.KEY_NOTICEABLE_NOTIFICATION, mCbNn!!.isChecked)

        val toggleCliOtc: Boolean = mCbToggleCli!!.isChecked
        editor.putBoolean(Def.Meta.KEY_TOGGLE_CLI_OTC, toggleCliOtc)
        FrequentSettings.put(Def.Meta.KEY_TOGGLE_CLI_OTC, toggleCliOtc)
        if (toggleCliOtc != mToggleCliOtc) {
            App.setJustNotifyAll(true)
        }

        val simpleFCli: Boolean = mCbSimpleFCli!!.isChecked
        editor.putBoolean(Def.Meta.KEY_SIMPLE_FCLI, simpleFCli)
        FrequentSettings.put(Def.Meta.KEY_SIMPLE_FCLI, simpleFCli)
        if (simpleFCli != mSimpleFCli) {
            App.setJustNotifyAll(true)
        }

        val autoLink: Boolean = mCbAutoLink!!.isChecked
        FrequentSettings.put(Def.Meta.KEY_AUTO_LINK, autoLink)
        editor.putBoolean(Def.Meta.KEY_AUTO_LINK, autoLink)

        val twiceBack: Boolean = mCbTwiceBack!!.isChecked
        FrequentSettings.put(Def.Meta.KEY_TWICE_BACK, twiceBack)
        editor.putBoolean(Def.Meta.KEY_TWICE_BACK, twiceBack)

        val followSystemDarkMode: Boolean = mCbFollowSystemDarkMode!!.isChecked
        FrequentSettings.put(Def.Meta.KEY_FOLLOW_SYSTEM_DARK_MODE, followSystemDarkMode)
        editor.putBoolean(Def.Meta.KEY_FOLLOW_SYSTEM_DARK_MODE, followSystemDarkMode)

        val forceDarkMode: Boolean = mCbForceDarkMode!!.isChecked
        FrequentSettings.put(Def.Meta.KEY_FORCE_DARK_MODE, forceDarkMode)
        editor.putBoolean(Def.Meta.KEY_FORCE_DARK_MODE, forceDarkMode)

        editor.putBoolean(Def.Meta.KEY_CREATE_ANIMATION_STYLE, mCbCreateAnimationStyle!!.isChecked)

        for (i in mChosenRingtoneUris!!.indices) {
            editor.putString(sKeysRingtone[i], mChosenRingtoneUris!![i]!!.toString())
        }

        FrequentSettings.put(Def.Meta.KEY_AUTO_SAVE_EDITS, mAutoSaveEdits)
        editor.putBoolean(Def.Meta.KEY_AUTO_SAVE_EDITS, mAutoSaveEdits)

        val isChecked: Boolean = mCbFgprt!!.isChecked
        if (isChecked) {
            FingerprintHelper.getInstance()!!.createFingerprintKeyForEverythingDone()
        }
        editor.putBoolean(Def.Meta.KEY_USE_FINGERPRINT, isChecked)

        editor.putInt(Def.Meta.KEY_AUTO_START_DOING, mASDPicked)
        editor.putString(
            Def.Meta.KEY_ASD_TIME_REMINDER,
            ThingDoingHelper.getStartDoingTimePickedStr(mASDTimesPicked[0], false)
        )
        editor.putString(
            Def.Meta.KEY_ASD_TIME_HABIT,
            ThingDoingHelper.getStartDoingTimePickedStr(mASDTimesPicked[1], false)
        )
        editor.putInt(Def.Meta.KEY_AUTO_STRICT_MODE, mASMPicked)

        editor.putBoolean(Def.Meta.KEY_QUICK_CREATE, mCbQuickCreate!!.isChecked)

        val closeLater: Boolean = mCbCloseNotificationLater!!.isChecked
        FrequentSettings.put(Def.Meta.KEY_CLOSE_NOTIFICATION_LATER, closeLater)
        editor.putBoolean(Def.Meta.KEY_CLOSE_NOTIFICATION_LATER, closeLater)

        val ongoingLockscreen: Boolean = mCbOngoingLockscreen!!.isChecked
        editor.putBoolean(Def.Meta.KEY_ONGOING_LOCKSCREEN, ongoingLockscreen)

        editor.putInt(Def.Meta.KEY_DAILY_TODO, mDTPicked)
        AlarmHelper.cancelDailyTodoAlarm(this)

        editor.putInt(Def.Meta.KEY_AUTO_NOTIFY, mANPicked)

        editor.apply()

        if (mDTPicked != 0) {
            AlarmHelper.tryToCreateDailyTodoAlarm(this)
        }

        val anyNotificationFeatureEnabled: Boolean = mCbNn!!.isChecked
            || mCbQuickCreate!!.isChecked
            || mCbCloseNotificationLater!!.isChecked
            || mCbOngoingLockscreen!!.isChecked
            || mANPicked != 0
        if (requestNotificationPermission && anyNotificationFeatureEnabled
            && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            doWithPermissionChecked(
                object : SimplePermissionCallback(this) {
                },
                Def.Communication.REQUEST_PERMISSION_NOTIFICATION,
                Manifest.permission.POST_NOTIFICATIONS
            )
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val newNightModeMask = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (newNightModeMask != mNightModeMask) {
            mNightModeMask = newNightModeMask
            storeConfiguration(false)
            recreate()
        }
    }

    override fun finish() {
        mRingtoneManager!!.stopPreviousRingtone()
        storeConfiguration()
        super.finish()
    }

    private fun createAlertDialog(
        showCancel: Boolean, @StringRes titleRes: Int, @StringRes contentRes: Int
    ): AlertDialogFragment {
        return createAlertDialog(showCancel, titleRes, contentRes, R.string.confirm)
    }

    private fun createAlertDialog(
        showCancel: Boolean, @StringRes titleRes: Int,
        @StringRes contentRes: Int, @StringRes confirmRes: Int
    ): AlertDialogFragment {
        val adf = AlertDialogFragment()
        adf.setShowCancel(showCancel)
        adf.setTitleBackground(App.defaultAccentBackground)
        adf.setConfirmBackground(App.defaultAccentBackground)
        adf.setTitle(getString(titleRes))
        adf.setContent(getString(contentRes))
        adf.setConfirmText(getString(confirmRes))
        return adf
    }

    private fun createLoadingDialog(
        @StringRes titleRes: Int, @StringRes contentRes: Int
    ): LoadingDialogFragment {
        val ldf = LoadingDialogFragment()
        ldf.setAccentBackground(App.defaultAccentBackground)
        ldf.setTitle(getString(titleRes))
        ldf.setContent(getString(contentRes))
        return ldf
    }

    private inner class BackupTask : AsyncTask<Any?, Any?, Boolean>() {

        override fun doInBackground(vararg params: Any?): Boolean {
            return BackupHelper.backup(this@SettingsActivity, params[0] as Uri)
        }

        override fun onPostExecute(success: Boolean) {
            mLdfBackup!!.dismiss()
            val titleRes: Int
            val contentRes: Int
            if (success) {
                titleRes = R.string.backup_success_title
                contentRes = R.string.backup_success_content
                updateUiRestore()
            } else {
                titleRes = R.string.backup_failed_title
                contentRes = R.string.backup_failed_content
            }
            val adf: AlertDialogFragment = createAlertDialog(false, titleRes, contentRes)
            adf.show(fragmentManager, AlertDialogFragment.TAG)
        }
    }

    private inner class RestoreTask : AsyncTask<Any?, Any?, Boolean>() {

        override fun doInBackground(vararg params: Any?): Boolean {
            val thingIds: MutableList<Long> = ArrayList()
            val reminderIds: MutableList<Long> = ArrayList()
            val habitReminderIds: MutableList<Long> = ArrayList()
            val context: Context = applicationContext
            val thingDAO: ThingDAO = ThingDAO.getInstance(context)!!
            val cursor = thingDAO.getAllThingsCursor()!!
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndex(Def.Database.COLUMN_ID_THINGS))
                @Thing.Type val type = cursor.getInt(cursor.getColumnIndex(Def.Database.COLUMN_TYPE_THINGS))
                val state = cursor.getInt(cursor.getColumnIndex(Def.Database.COLUMN_STATE_THINGS))
                if (state != Thing.UNDERWAY) continue
                thingIds.add(id)
                if (type == Thing.REMINDER || type == Thing.HABIT || type == Thing.GOAL) {
                    if (Thing.isReminderType(type)) {
                        reminderIds.add(id)
                    } else {
                        val habitReminders: List<HabitReminder?> = HabitDAO.getInstance(context)!!
                            .getHabitRemindersByHabitId(id)!!
                        for (habitReminder in habitReminders) {
                            habitReminderIds.add(habitReminder!!.id)
                        }
                    }
                }
            }
            cursor.close()

            val backupUri: Uri = params[0] as Uri
            if (BackupHelper.restore(context, backupUri)) {
                AlarmHelper.cancelAlarms(context, thingIds, reminderIds, habitReminderIds)
                try {
                    val fos: FileOutputStream = this@SettingsActivity.openFileOutput(
                        Def.Meta.RESTORE_DONE_FILE_NAME, MODE_PRIVATE
                    )
                    fos.write(getString(R.string.qq_my_love).toByteArray())
                    return true
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
            return false
        }

        override fun onPostExecute(restoreSuccessfully: Boolean) {
            mLdfRestore!!.dismiss()
            val title: String
            val content: String
            if (restoreSuccessfully) {
                title = getString(R.string.restore_success_title)
                content = getString(R.string.restore_success_content)
            } else {
                title = getString(R.string.restore_failed_title)
                content = getString(R.string.restore_failed_other)
            }
            val adf = AlertDialogFragment()
            adf.setShowCancel(false)
            adf.setTitleBackground(App.defaultAccentBackground)
            adf.setConfirmBackground(App.defaultAccentBackground)
            adf.setTitle(title)
            adf.setContent(content)
            adf.show(fragmentManager, AlertDialogFragment.TAG)

            val context: Context = this@SettingsActivity
            if (restoreSuccessfully) {
                if (App.getDoingThingId() != -1L) {
                    Toast.makeText(context, R.string.doing_failed_restore, Toast.LENGTH_LONG).show()
                    DoingService.sStopReason = DoingRecord.STOP_REASON_CANCEL_OTHER
                    stopService(Intent(context, DoingService::class.java))
                    App.setDoingThingId(-1L)
                    AppWidgetHelper.updateAllAppWidgets(context)
                }
                App.killMeAndRestart(context, null, 1200)
            }
        }
    }

    companion object {
        const val TAG: String = "SettingsActivity"

        const val DEFAULT_DRAWER_HEADER: String = "default_drawer_header"
        const val FOLLOW_SYSTEM: String = "follow_system"

        private val sKeysRingtone: Array<String> = arrayOf(
            Def.Meta.KEY_RINGTONE_REMINDER,
            Def.Meta.KEY_RINGTONE_HABIT,
            Def.Meta.KEY_RINGTONE_GOAL,
            Def.Meta.KEY_RINGTONE_AUTO_NOTIFY
        )

        private var sRingtoneTitleList: MutableList<String?>? = null
        private var sRingtoneUriList: MutableList<Uri>? = null
        private var sDTItems: MutableList<String?>? = null
        private var sANItems: MutableList<String?>? = null

        private fun getRingtoneTitle(
            context: Context, ringtoneManager: RingtoneManager, uri: Uri
        ): String? {
            return if (isFileRingtone(ringtoneManager, uri)) {
                val pathName: String? = UriPathConverter.getLocalPathName(context, uri)
                StringUtil.replaceChineseBrackets(FileUtil.getNameWithoutPostfix(pathName))
            } else {
                val ringtone: Ringtone = RingtoneManager.getRingtone(context, uri)
                StringUtil.replaceChineseBrackets(ringtone.getTitle(context))
            }
        }

        private fun isFileRingtone(ringtoneManager: RingtoneManager, uri: Uri): Boolean {
            return uri != RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                && ringtoneManager.getRingtonePosition(uri) == -1
        }
    }
}
