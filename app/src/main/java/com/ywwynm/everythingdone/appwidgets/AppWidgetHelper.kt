@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.appwidgets

import android.app.PendingIntent
import android.app.ActivityOptions
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.FrequentSettings
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.AuthenticationActivity
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.activities.ShortcutActivity
import com.ywwynm.everythingdone.activities.ThingsActivity
import com.ywwynm.everythingdone.appwidgets.list.*
import com.ywwynm.everythingdone.appwidgets.single.*
import com.ywwynm.everythingdone.database.AppWidgetDAO
import com.ywwynm.everythingdone.database.HabitDAO
import com.ywwynm.everythingdone.database.ReminderDAO
import com.ywwynm.everythingdone.database.ThingFolderDAO
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.helpers.CheckListHelper
import com.ywwynm.everythingdone.helpers.RemoteThingCardMediaRenderer
import com.ywwynm.everythingdone.helpers.ThingCardMediaHelper
import com.ywwynm.everythingdone.model.Habit
import com.ywwynm.everythingdone.model.Reminder
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.model.ThingCardAppearance
import com.ywwynm.everythingdone.model.ThingFolder
import com.ywwynm.everythingdone.model.ThingFolderCardPresentation
import com.ywwynm.everythingdone.model.ThingListEntry
import com.ywwynm.everythingdone.model.ThingWidgetInfo
import com.ywwynm.everythingdone.utils.DateTimeUtil
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Created by ywwynm on 2016/7/27.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * helper class for app widgets, especially for showing thing
 */
object AppWidgetHelper {

    const val TAG: String = "AppWidgetHelper"

    private val screenDensity: Float = DisplayUtil.getScreenDensity(App.getApp())

    private val dp12: Int = (screenDensity * 12).toInt()

    private const val WIDGET_LIST_SIDE_MEDIA_MIN_HEIGHT_DP: Int = 168
    private const val WIDGET_LIST_MEDIA_BACKGROUND_FALLBACK_HEIGHT_DP: Int = 160
    private const val WIDGET_LIST_MEDIA_BACKGROUND_MAX_HEIGHT_DP: Int = 360
    private const val WIDGET_LIST_MEDIA_BACKGROUND_MAX_BITMAP_DIMENSION_PX: Int = 960
    private const val WIDGET_LIST_MEDIA_BACKGROUND_MAX_BITMAP_PIXELS: Int = 240_000
    private const val WIDGET_LIST_MEDIA_HARD_MAX_HEIGHT_DP: Int = 720
    private const val WIDGET_REMOTE_BITMAP_MAX_DIMENSION_DP: Int = 720
    private const val WIDGET_GRID_SLOT_HORIZONTAL_PADDING_DP: Int = 4
    private const val WIDGET_SIDE_MEDIA_PROJECTION_MAX_ITERATIONS: Int = 6
    private const val WIDGET_SIDE_MEDIA_PROJECTION_TOLERANCE_PX: Int = 1

    private val COLLECTION_TEMPLATE_PENDING_INTENT_FLAGS: Int =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE

    private val WIDGET_ACTIVITY_PENDING_INTENT_FLAGS: Int =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private val ROOT_WIDGET_THING: Int         = R.id.root_widget_thing
    /** Phase 8: backing ImageView for the widget background. RemoteViews can't
     *  apply a Shader / GradientDrawable to setBackgroundColor on the root,
     *  so we render the [com.ywwynm.everythingdone.model.ThingBackground]
     *  to a bitmap and post it here via setImageViewBitmap. */
    private val IV_WIDGET_BG: Int              = R.id.iv_widget_bg

    private val IV_STICKY_ONGOING: Int         = R.id.iv_thing_sticky_ongoing
    private val IV_STICKY_ONGOING_SMALL: Int   = R.id.iv_thing_sticky_ongoing_smaller
    private val FL_DOING: Int                  = R.id.fl_thing_doing_cover

    private val FL_IMAGE_ATTACHMENT: Int       = R.id.fl_thing_image
    private val IV_IMAGE_ATTACHMENT: Int       = R.id.iv_thing_image
    private val TV_IMAGE_COUNT: Int            = R.id.tv_thing_image_attachment_count
    private val FL_IMAGE_ATTACHMENT_BOTTOM: Int = R.id.fl_thing_image_bottom
    private val IV_IMAGE_ATTACHMENT_BOTTOM: Int = R.id.iv_thing_image_bottom
    private val TV_IMAGE_COUNT_BOTTOM: Int      = R.id.tv_thing_image_attachment_count_bottom
    private val FL_IMAGE_ATTACHMENT_LEFT: Int   = R.id.fl_thing_image_left
    private val IV_IMAGE_ATTACHMENT_LEFT: Int   = R.id.iv_thing_image_left
    private val TV_IMAGE_COUNT_LEFT: Int        = R.id.tv_thing_image_attachment_count_left
    private val FL_IMAGE_ATTACHMENT_RIGHT: Int  = R.id.fl_thing_image_right
    private val IV_IMAGE_ATTACHMENT_RIGHT: Int  = R.id.iv_thing_image_right
    private val TV_IMAGE_COUNT_RIGHT: Int       = R.id.tv_thing_image_attachment_count_right
    private val TV_MEDIA_BACKGROUND_COUNT: Int  =
            R.id.tv_thing_media_background_attachment_count

    private val TV_TITLE: Int                  = R.id.tv_thing_title
    private val IV_PRIVATE_THING: Int          = R.id.iv_private_thing

    private val TV_CONTENT: Int                = R.id.tv_thing_content

    private val LV_CHECKLIST: Int              = R.id.lv_thing_check_list
    private val LL_CHECK_LIST_ITEMS: Int       = R.id.ll_check_list_items_container
    private val LL_CHECK_LIST_ITEM_ROOT: Int   = R.id.ll_check_list_tv
    private val IV_STATE_CHECK_LIST: Int       = R.id.iv_check_list_state
    private val TV_CONTENT_CHECK_LIST: Int     = R.id.tv_check_list

    private val LL_AUDIO_ATTACHMENT: Int       = R.id.ll_thing_audio_attachment
    private val IV_AUDIO_COUNT: Int            = R.id.iv_thing_audio_attachment_count
    private val TV_AUDIO_COUNT: Int            = R.id.tv_thing_audio_attachment_count
    private val LL_AUDIO_ATTACHMENT_LARGE: Int = R.id.ll_thing_audio_attachment_large
    private val IV_AUDIO_COUNT_LARGE: Int      = R.id.iv_thing_audio_attachment_count_large
    private val TV_AUDIO_COUNT_LARGE: Int      = R.id.tv_thing_audio_attachment_count_large

    private val RL_REMINDER: Int               = R.id.rl_thing_reminder
    private val V_REMINDER_SEPARATOR: Int      = R.id.view_reminder_separator
    private val IV_REMINDER: Int               = R.id.iv_thing_reminder
    private val TV_REMINDER_TIME: Int          = R.id.tv_thing_reminder_time

    private val RL_HABIT: Int                  = R.id.rl_thing_habit
    private val V_HABIT_SEPARATOR_1: Int       = R.id.view_habit_separator_1
    private val IV_HABIT: Int                  = R.id.iv_thing_habit
    private val TV_HABIT_SUMMARY: Int          = R.id.tv_thing_habit_summary
    private val TV_HABIT_NEXT_REMINDER: Int    = R.id.tv_thing_habit_next_reminder
    private val V_HABIT_SEPARATOR_2: Int       = R.id.view_habit_separator_2
    private val LL_HABIT_RECORD: Int           = R.id.ll_thing_habit_record
    private val TV_HABIT_LAST_FIVE: Int        = R.id.tv_thing_habit_last_five_record
    private val TV_HABIT_FINISHED_THIS_T: Int  = R.id.tv_thing_habit_finished_this_t

    private val RL_THING_STATE: Int            = R.id.rl_thing_state
    private val V_STATE_SEPARATOR: Int         = R.id.view_state_separator
    private val TV_THING_STATE: Int            = R.id.tv_thing_state
    private val IV_THING_STATE: Int            = R.id.iv_thing_state

    private val LL_THING_ACTION: Int           = R.id.ll_thing_action
    private val TV_THING_ACTION: Int           = R.id.tv_thing_action

    private val V_PADDING_BOTTOM: Int          = R.id.view_thing_padding_bottom

    private val LV_THINGS_LIST: Int            = R.id.lv_things_list
    private val LL_THINGS_LIST_HEADER: Int     = R.id.ll_things_list_header
    private val IV_THINGS_LIST_HEADER_BG: Int  = R.id.iv_things_list_header_bg
    private val TV_THINGS_LIST_TITLE: Int      = R.id.tv_things_list_title
    private val IV_THINGS_LIST_SETTING: Int    = R.id.iv_things_list_setting
    private val IV_THINGS_LIST_CREATE: Int     = R.id.iv_things_list_create

    private val ROOT_WIDGET_FOLDER: Int        = R.id.root_widget_folder
    private val IV_WIDGET_FOLDER_BG: Int       = R.id.iv_widget_folder_bg
    private val IV_WIDGET_FOLDER_ICON: Int     = R.id.iv_widget_folder_icon
    private val TV_WIDGET_FOLDER_TITLE: Int    = R.id.tv_widget_folder_title
    private val TV_WIDGET_FOLDER_COUNT: Int    = R.id.tv_widget_folder_count
    private val IV_WIDGET_FOLDER_LOCK: Int     = R.id.iv_widget_folder_lock

    private val LL_WIDGET_GRID_SLOT_1: Int     = R.id.ll_widget_grid_slot_1
    private val LL_WIDGET_GRID_SLOT_2: Int     = R.id.ll_widget_grid_slot_2
    private val LL_WIDGET_GRID_SLOT_3: Int     = R.id.ll_widget_grid_slot_3

    /**
     * Update single thing widgets whose UI components are bind with a [Thing] with `thingId`.
     */
    @JvmStatic
    fun updateSingleThingAppWidgets(context: Context?, thingId: Long) {
        Log.i(TAG, "updateSingleThingAppWidgets is called, thingId[$thingId]")
        val appWidgetDAO: AppWidgetDAO = AppWidgetDAO.getInstance(context)!!
        val thingWidgetInfos: List<ThingWidgetInfo?> = appWidgetDAO.getThingWidgetInfosByThingId(thingId)!!
        for (thingWidgetInfo in thingWidgetInfos) {
            val appWidgetId: Int = thingWidgetInfo!!.id
            val intent = Intent(context, getProviderClassBySize(thingWidgetInfo.size))
            intent.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS,
                    intArrayOf(appWidgetId))
            context!!.sendBroadcast(intent)
        }
    }

    @JvmStatic
    fun updateThingsListAppWidget(context: Context?, appWidgetId: Int) {
        Log.i(TAG,
            "updateThingsListAppWidget(context, appWidgetId) is called, appWidgetId[$appWidgetId]"
        )
        val intent = Intent(context, ThingsListWidget::class.java)
        intent.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS,
                intArrayOf(appWidgetId))
        context!!.sendBroadcast(intent)
    }

    @JvmStatic
    fun updateThingsListAppWidgetsForStatus(context: Context?, status: Int) {
        Log.i(TAG, "updateThingsListAppWidget(context, status) is called, status[$status]")
        val appWidgetDAO: AppWidgetDAO = AppWidgetDAO.getInstance(context)!!
        val thingWidgetInfos: List<ThingWidgetInfo?> = appWidgetDAO.getThingsListWidgetInfos()
        for (thingWidgetInfo in thingWidgetInfos) {
            updateThingsListAppWidget(context, thingWidgetInfo!!.id)
        }
    }

    @JvmStatic
    fun updateThingsListAppWidgetsForType(context: Context?, @Thing.Type type: Int) {
        Log.i(TAG, "updateThingsListAppWidgetForType is called, type[$type]")
        updateAllThingsListAppWidgets(context)
    }

    @JvmStatic
    fun updateAllThingsListAppWidgets(context: Context?) {
        Log.i(TAG, "updateAllThingsListAppWidgets is called")
        val appWidgetDAO: AppWidgetDAO = AppWidgetDAO.getInstance(context)!!
        val thingWidgetInfos: List<ThingWidgetInfo?> = appWidgetDAO.getThingsListWidgetInfos()
        for (thingWidgetInfo in thingWidgetInfos) {
            updateThingsListAppWidget(context, thingWidgetInfo!!.id)
        }
    }

    @JvmStatic
    fun updateAllAppWidgets(context: Context?) {
        Log.i(TAG, "updateAllAppWidgets is called")
        val appWidgetDAO: AppWidgetDAO = AppWidgetDAO.getInstance(context)!!
        val thingWidgetInfos: List<ThingWidgetInfo?> = appWidgetDAO.getAllThingWidgetInfos()!!
        for (thingWidgetInfo in thingWidgetInfos) {
            val appWidgetId: Int = thingWidgetInfo!!.id
            val thingId: Long = thingWidgetInfo.thingId
            val intent = if (thingId < 0) { // for things list widgets
                Intent(context, ThingsListWidget::class.java)
            } else {
                Intent(context, getProviderClassBySize(thingWidgetInfo.size))
            }
            intent.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS,
                    intArrayOf(appWidgetId))
            context!!.sendBroadcast(intent)
        }
    }

    @JvmStatic
    fun getProviderClassBySize(@ThingWidgetInfo.Size size: Int): Class<*> {
        return when (size) {
            ThingWidgetInfo.SIZE_TINY -> ThingWidgetTiny::class.java
            ThingWidgetInfo.SIZE_SMALL -> ThingWidgetSmall::class.java
            ThingWidgetInfo.SIZE_MIDDLE -> ThingWidgetMiddle::class.java
            ThingWidgetInfo.SIZE_LARGE -> ThingWidgetLarge::class.java
            ThingWidgetInfo.SIZE_4X2 -> ThingWidget4x2::class.java
            ThingWidgetInfo.SIZE_2X4 -> ThingWidget2x4::class.java
            ThingWidgetInfo.SIZE_4X3 -> ThingWidget4x3::class.java
            ThingWidgetInfo.SIZE_3X4 -> ThingWidget3x4::class.java
            ThingWidgetInfo.SIZE_5X2 -> ThingWidget5x2::class.java
            ThingWidgetInfo.SIZE_2X5 -> ThingWidget2x5::class.java
            ThingWidgetInfo.SIZE_5X3 -> ThingWidget5x3::class.java
            ThingWidgetInfo.SIZE_3X5 -> ThingWidget3x5::class.java
            ThingWidgetInfo.SIZE_5X4 -> ThingWidget5x4::class.java
            ThingWidgetInfo.SIZE_4X5 -> ThingWidget4x5::class.java
            ThingWidgetInfo.SIZE_5X5 -> ThingWidget5x5::class.java
            ThingWidgetInfo.SIZE_6X2 -> ThingWidget6x2::class.java
            ThingWidgetInfo.SIZE_2X6 -> ThingWidget2x6::class.java
            ThingWidgetInfo.SIZE_6X3 -> ThingWidget6x3::class.java
            ThingWidgetInfo.SIZE_3X6 -> ThingWidget3x6::class.java
            ThingWidgetInfo.SIZE_6X4 -> ThingWidget6x4::class.java
            ThingWidgetInfo.SIZE_4X6 -> ThingWidget4x6::class.java
            ThingWidgetInfo.SIZE_6X5 -> ThingWidget6x5::class.java
            ThingWidgetInfo.SIZE_5X6 -> ThingWidget5x6::class.java
            ThingWidgetInfo.SIZE_6X6 -> ThingWidget6x6::class.java
            else -> ThingWidgetMiddle::class.java
        }
    }

    @JvmStatic
    @ThingWidgetInfo.Size
    fun getSizeByProviderClass(clazz: Class<*>?): Int {
        return when (clazz) {
            ThingWidgetTiny::class.java -> ThingWidgetInfo.SIZE_TINY
            ThingWidgetSmall::class.java -> ThingWidgetInfo.SIZE_SMALL
            ThingWidgetMiddle::class.java -> ThingWidgetInfo.SIZE_MIDDLE
            ThingWidgetLarge::class.java -> ThingWidgetInfo.SIZE_LARGE
            ThingWidget4x2::class.java -> ThingWidgetInfo.SIZE_4X2
            ThingWidget2x4::class.java -> ThingWidgetInfo.SIZE_2X4
            ThingWidget4x3::class.java -> ThingWidgetInfo.SIZE_4X3
            ThingWidget3x4::class.java -> ThingWidgetInfo.SIZE_3X4
            ThingWidget5x2::class.java -> ThingWidgetInfo.SIZE_5X2
            ThingWidget2x5::class.java -> ThingWidgetInfo.SIZE_2X5
            ThingWidget5x3::class.java -> ThingWidgetInfo.SIZE_5X3
            ThingWidget3x5::class.java -> ThingWidgetInfo.SIZE_3X5
            ThingWidget5x4::class.java -> ThingWidgetInfo.SIZE_5X4
            ThingWidget4x5::class.java -> ThingWidgetInfo.SIZE_4X5
            ThingWidget5x5::class.java -> ThingWidgetInfo.SIZE_5X5
            ThingWidget6x2::class.java -> ThingWidgetInfo.SIZE_6X2
            ThingWidget2x6::class.java -> ThingWidgetInfo.SIZE_2X6
            ThingWidget6x3::class.java -> ThingWidgetInfo.SIZE_6X3
            ThingWidget3x6::class.java -> ThingWidgetInfo.SIZE_3X6
            ThingWidget6x4::class.java -> ThingWidgetInfo.SIZE_6X4
            ThingWidget4x6::class.java -> ThingWidgetInfo.SIZE_4X6
            ThingWidget6x5::class.java -> ThingWidgetInfo.SIZE_6X5
            ThingWidget5x6::class.java -> ThingWidgetInfo.SIZE_5X6
            ThingWidget6x6::class.java -> ThingWidgetInfo.SIZE_6X6
            else -> ThingWidgetInfo.SIZE_MIDDLE
        }
    }

    @JvmStatic
    fun getDefaultSizeDpByProviderClass(clazz: Class<*>?): IntArray {
        return intArrayOf(getWidgetDefaultWidthDp(clazz), getWidgetDefaultHeightDp(clazz))
    }

    private fun getProviderClassForAppWidgetId(
            context: Context, appWidgetId: Int, fallback: Class<*>): Class<*> {
        return try {
            val className = AppWidgetManager.getInstance(context)
                    .getAppWidgetInfo(appWidgetId)
                    ?.provider
                    ?.className
            if (className == null) {
                fallback
            } else {
                Class.forName(className)
            }
        } catch (_: Exception) {
            fallback
        }
    }

    @JvmStatic
    fun getActivityPendingIntentForWidget(
            context: Context, requestCode: Int, intent: Intent, flags: Int): PendingIntent {
        val options: Bundle? = getActivityOptionsForWidgetPendingIntent()
        return if (options == null) {
            PendingIntent.getActivity(context, requestCode, intent, flags)
        } else {
            PendingIntent.getActivity(context, requestCode, intent, flags, options)
        }
    }

    private fun getActivityOptionsForWidgetPendingIntent(): Bundle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return null
        }
        return ActivityOptions.makeBasic()
                .setPendingIntentCreatorBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
    }

    @JvmStatic
    fun createRemoteViewsForSingleThing(
            context: Context?, thing: Thing?, position: Int, appWidgetId: Int, clazz: Class<*>?): RemoteViews {
        val dao: AppWidgetDAO = AppWidgetDAO.getInstance(context)!!
        val info: ThingWidgetInfo? = dao.getThingWidgetInfoById(appWidgetId)
        var alpha = 100
        @ThingWidgetInfo.Style var style: Int = ThingWidgetInfo.STYLE_NORMAL
        if (info != null) {
            alpha = info.alpha
            style = info.style
        }
        val remoteViews = RemoteViews(context!!.packageName, R.layout.app_widget_thing)
        setAppearance(context, remoteViews, thing, appWidgetId, clazz, alpha, style)
        val contentIntent: Intent = AuthenticationActivity.getOpenIntent(
                context, TAG, thing!!.id, position,
                Def.Communication.AUTHENTICATE_ACTION_VIEW,
                context.getString(R.string.check_private_thing))
        val pendingIntent: PendingIntent = getActivityPendingIntentForWidget(
                context, appWidgetId, contentIntent, WIDGET_ACTIVITY_PENDING_INTENT_FLAGS)
        remoteViews.setOnClickPendingIntent(ROOT_WIDGET_THING, pendingIntent)
        remoteViews.setOnClickPendingIntent(FL_DOING, pendingIntent)
        return remoteViews
    }

    @JvmStatic
    fun createRemoteViewsForSingleThingPreview(
            context: Context, thing: Thing, appWidgetId: Int, clazz: Class<*>?,
            alpha: Int): RemoteViews {
        val remoteViews = RemoteViews(context.packageName, R.layout.app_widget_thing)
        setAppearance(context, remoteViews, thing, appWidgetId, clazz, alpha,
                ThingWidgetInfo.STYLE_NORMAL)
        return remoteViews
    }

    @JvmStatic
    fun createRemoteViewsForThingsList(context: Context?, appWidgetId: Int): RemoteViews {
        val appContext = context!!
        val remoteViews = RemoteViews(appContext.packageName, R.layout.app_widget_things_list)
        val dao: AppWidgetDAO = AppWidgetDAO.getInstance(appContext)!!
        val info: ThingWidgetInfo? = dao.getThingWidgetInfoById(appWidgetId)
        val folder = resolveThingsListTargetFolder(appContext, info)
        val typeFilterMask = ThingWidgetInfo.normalizedTypeFilterMask(
            info?.typeFilterMask ?: ThingWidgetInfo.TYPE_FILTER_ALL
        )
        val status = Def.ThingStatus.UNDERWAY

        setThingsListHeaderAppearance(appContext, remoteViews, info, folder, typeFilterMask)

        var intent = Intent(
            appContext,
            if (folder != null) AuthenticationActivity::class.java else ThingsActivity::class.java
        )
        if (folder != null) {
            intent.setAction(Def.Communication.AUTHENTICATE_ACTION_VIEW)
        }
        intent.putExtra(Def.Communication.KEY_STATUS, status)
        intent.putExtra(Def.Communication.KEY_TYPE_FILTER_MASK, typeFilterMask)
        if (folder != null) {
            intent.putExtra(Def.Communication.KEY_FOLDER_ID, folder.id)
        }
        var pendingIntent: PendingIntent = getActivityPendingIntentForWidget(
            appContext,
            appWidgetId,
            intent,
            WIDGET_ACTIVITY_PENDING_INTENT_FLAGS
        )
        remoteViews.setOnClickPendingIntent(LL_THINGS_LIST_HEADER, pendingIntent)

        intent = Intent(appContext, ThingsListWidgetConfiguration::class.java)
        intent.putExtra(Def.Communication.KEY_WIDGET_ID, appWidgetId)
        pendingIntent = getActivityPendingIntentForWidget(
            appContext,
            appWidgetId,
            intent,
            WIDGET_ACTIVITY_PENDING_INTENT_FLAGS
        )
        remoteViews.setOnClickPendingIntent(IV_THINGS_LIST_SETTING, pendingIntent)

        intent = Intent(appContext, ShortcutActivity::class.java)
        intent.setAction(Def.Communication.SHORTCUT_ACTION_CREATE)
        if (folder != null) {
            intent.putExtra(Def.Communication.KEY_FOLDER_ID, folder.id)
        }
        pendingIntent = getActivityPendingIntentForWidget(
            appContext,
            appWidgetId,
            intent,
            WIDGET_ACTIVITY_PENDING_INTENT_FLAGS
        )
        remoteViews.setOnClickPendingIntent(IV_THINGS_LIST_CREATE, pendingIntent)

        intent = Intent(appContext, ThingsListWidgetService::class.java)
        intent.putExtra(Def.Communication.KEY_WIDGET_ID, appWidgetId)
        intent.setData(Uri.parse(intent.toUri(Intent.URI_INTENT_SCHEME)))
        remoteViews.setRemoteAdapter(LV_THINGS_LIST, intent)

        intent = Intent(appContext, AuthenticationActivity::class.java)
        intent.setAction(Def.Communication.AUTHENTICATE_ACTION_VIEW)
        intent.putExtra(Def.Communication.KEY_TITLE, appContext.getString(R.string.check_private_thing))
        intent.putExtra(Def.Communication.KEY_SENDER_NAME, TAG)
        intent.putExtra(
            Def.Communication.KEY_DETAIL_ACTIVITY_TYPE,
            DetailActivity.UPDATE
        )
        intent.setData(Uri.parse(intent.toUri(Intent.URI_INTENT_SCHEME)))
        pendingIntent = getActivityPendingIntentForWidget(
            appContext,
            appWidgetId,
            intent,
            COLLECTION_TEMPLATE_PENDING_INTENT_FLAGS
        )
        remoteViews.setPendingIntentTemplate(LV_THINGS_LIST, pendingIntent)

        remoteViews.setScrollPosition(LV_THINGS_LIST, 0)

        return remoteViews
    }

    @JvmStatic
    fun resolveThingsListTargetFolder(context: Context, info: ThingWidgetInfo?): ThingFolder? {
        val folderId = info?.targetFolderId ?: return null
        val dao = ThingFolderDAO.getInstance(context)!!
        val folder = dao.getFolderById(folderId)
        if (folder == null || dao.isEffectivelyDeleted(folderId)) {
            // The configured folder is gone or in the recycle bin: persistently
            // rewrite the widget config back to the root scope (keeping status,
            // type filter, display mode, alpha and style) so it no longer points
            // at an unavailable folder (use-cases W3 / T4).
            AppWidgetDAO.getInstance(context)?.clearTargetFolder(info.id)
            info.targetFolderId = null
            return null
        }
        return folder
    }

    @JvmStatic
    fun getThingsListWidgetGridColumnCount(context: Context?, appWidgetId: Int): Int {
        val appContext = context!!
        val clazz = getProviderClassForAppWidgetId(
            appContext,
            appWidgetId,
            ThingsListWidget::class.java
        )
        val cellSpan = getThingsListWidgetCellSpan(clazz)
        if (cellSpan.width == 4) {
            return 2
        }
        val widthDp = pxToDp(getWidgetContentTargetWidth(appContext, appWidgetId, clazz))
        return when {
            widthDp < 220 -> 1
            widthDp < 360 -> 2
            else -> 3
        }
    }

    @JvmStatic
    fun isThingsListWidgetEntryFullSpan(item: ThingsListWidgetItem): Boolean {
        return when (item) {
            is ThingsListWidgetItem.ThingItem ->
                item.thing.thingCardSpanMode == Thing.THING_CARD_SPAN_FULL
            is ThingsListWidgetItem.FolderItem ->
                item.entry.folder.effectiveCardPresentation().spanMode ==
                    ThingFolderCardPresentation.SPAN_FULL
            is ThingsListWidgetItem.GridRow -> true
        }
    }

    @JvmStatic
    fun createRemoteViewsForThingsListGridRow(
        context: Context?,
        row: ThingsListWidgetItem.GridRow,
        appWidgetId: Int
    ): RemoteViews {
        val appContext = context!!
        val rv = RemoteViews(appContext.packageName, R.layout.app_widget_item_grid_row)
        val contentWidthOverride = if (row.columns > 1) {
            getThingsListWidgetGridSlotContentTargetWidth(appContext, appWidgetId, row.columns)
        } else {
            null
        }
        val slotIds = intArrayOf(
            LL_WIDGET_GRID_SLOT_1,
            LL_WIDGET_GRID_SLOT_2,
            LL_WIDGET_GRID_SLOT_3
        )
        for (i in slotIds.indices) {
            val slotId = slotIds[i]
            rv.removeAllViews(slotId)
            rv.setViewVisibility(slotId, if (i < row.columns) View.VISIBLE else View.GONE)
            val item = row.slots.getOrNull(i)
            if (item != null) {
                rv.addView(
                    slotId,
                    createRemoteViewsForThingsListEntry(
                        appContext,
                        item,
                        appWidgetId,
                        false,
                        contentWidthOverride
                    )
                )
                createThingsListWidgetFillInIntent(appContext, item, appWidgetId)?.let { fillIn ->
                    rv.setOnClickFillInIntent(slotId, fillIn)
                }
            }
        }
        return rv
    }

    @JvmStatic
    fun createRemoteViewsForThingsListEntry(
        context: Context?,
        item: ThingsListWidgetItem,
        appWidgetId: Int,
        bindFillInIntent: Boolean = true,
        contentWidthOverride: Int? = null
    ): RemoteViews {
        val appContext = context!!
        return when (item) {
            is ThingsListWidgetItem.ThingItem ->
                createRemoteViewsForThingsListItem(
                    appContext,
                    item.thing,
                    appWidgetId,
                    contentWidthOverride
                ).also { rv ->
                    if (bindFillInIntent) {
                        rv.setOnClickFillInIntent(
                            ROOT_WIDGET_THING,
                            createThingListWidgetThingFillInIntent(item.thing)
                        )
                    }
                }
            is ThingsListWidgetItem.FolderItem ->
                createRemoteViewsForThingsListFolderItem(appContext, item.entry, appWidgetId).also { rv ->
                    if (bindFillInIntent) {
                        rv.setOnClickFillInIntent(
                            ROOT_WIDGET_FOLDER,
                            createThingListWidgetFolderFillInIntent(appContext, item.entry, appWidgetId)
                        )
                    }
                }
            is ThingsListWidgetItem.GridRow ->
                createRemoteViewsForThingsListGridRow(appContext, item, appWidgetId)
        }
    }

    private fun getThingsListWidgetGridSlotContentTargetWidth(
        context: Context,
        appWidgetId: Int,
        columns: Int
    ): Int {
        val clazz = getProviderClassForAppWidgetId(
            context,
            appWidgetId,
            ThingsListWidget::class.java
        )
        val rowWidth = getWidgetContentTargetWidth(context, appWidgetId, clazz)
        val safeColumns = max(1, columns)
        if (safeColumns <= 1) return rowWidth
        return max(
            1,
            rowWidth / safeColumns - dpToPx(WIDGET_GRID_SLOT_HORIZONTAL_PADDING_DP)
        )
    }

    private fun createThingsListWidgetFillInIntent(
        context: Context,
        item: ThingsListWidgetItem,
        appWidgetId: Int
    ): Intent? {
        return when (item) {
            is ThingsListWidgetItem.ThingItem ->
                createThingListWidgetThingFillInIntent(item.thing)
            is ThingsListWidgetItem.FolderItem ->
                createThingListWidgetFolderFillInIntent(context, item.entry, appWidgetId)
            is ThingsListWidgetItem.GridRow -> null
        }
    }

    private fun createThingListWidgetThingFillInIntent(thing: Thing): Intent {
        val intent = Intent()
        intent.putExtra(Def.Communication.KEY_ID, thing.id)
        intent.putExtra(Def.Communication.KEY_POSITION, -1)
        return intent
    }

    private fun createThingListWidgetFolderFillInIntent(
        context: Context,
        entry: ThingListEntry.FolderEntry,
        appWidgetId: Int
    ): Intent {
        val dao = AppWidgetDAO.getInstance(context)!!
        val info = dao.getThingWidgetInfoById(appWidgetId)
        val mask = info?.typeFilterMask ?: ThingWidgetInfo.TYPE_FILTER_ALL
        val intent = Intent()
        intent.putExtra(Def.Communication.KEY_FOLDER_ID, entry.folder.id)
        intent.putExtra(
            Def.Communication.KEY_TYPE_FILTER_MASK,
            ThingWidgetInfo.normalizedTypeFilterMask(mask)
        )
        intent.putExtra(
            Def.Communication.KEY_STATUS,
            Def.ThingStatus.UNDERWAY
        )
        return intent
    }

    private fun setThingsListHeaderAppearance(
        context: Context,
        remoteViews: RemoteViews,
        info: ThingWidgetInfo?,
        folder: ThingFolder?,
        typeFilterMask: Int
    ) {
        val background = folder?.getBackground()
            ?: App.defaultAccentBackground
        val headerAlpha: Int
        var headerColor = background.color
        if (info != null && info.alpha < 0) {
            headerAlpha = if (info.alpha == ThingWidgetInfo.HEADER_ALPHA_0) {
                0
            } else {
                (abs(info.alpha) / 100f * 255).toInt()
            }
            headerColor = DisplayUtil.getTransparentColor(headerColor, headerAlpha)
        } else {
            headerAlpha = 255
        }
        remoteViews.setInt(LL_THINGS_LIST_HEADER, "setBackgroundColor", Color.TRANSPARENT)
        val headerBitmap = BackgroundUtil.renderBackgroundBitmap(background, 64, 40, headerAlpha)
        if (headerBitmap != null) {
            remoteViews.setImageViewBitmap(IV_THINGS_LIST_HEADER_BG, headerBitmap)
        } else {
            remoteViews.setInt(LL_THINGS_LIST_HEADER, "setBackgroundColor", headerColor)
        }
        remoteViews.setTextViewText(
            TV_THINGS_LIST_TITLE,
            getThingsListHeaderTitle(context, folder, typeFilterMask, info?.status ?: Def.ThingStatus.UNDERWAY)
        )
        val foreground = foregroundForWidgetBackground(context, background.representativeColor())
        remoteViews.setTextColor(TV_THINGS_LIST_TITLE, foreground)
        remoteViews.setInt(IV_THINGS_LIST_SETTING, "setColorFilter", foreground)
        remoteViews.setInt(IV_THINGS_LIST_CREATE, "setColorFilter", foreground)
    }

    private fun getThingsListHeaderTitle(
        context: Context,
        folder: ThingFolder?,
        typeFilterMask: Int,
        status: Int
    ): String {
        val typeTitle = getTypeFilterTitle(context, typeFilterMask)
        val finished = status == Def.ThingStatus.FINISHED
        val segments = ArrayList<String>()
        if (folder != null) {
            segments.add(folder.title.ifEmpty { context.getString(R.string.default_thing_folder_name) })
        }
        if (finished) {
            segments.add(context.getString(R.string.finished))
        }
        if (typeTitle != null) {
            segments.add(typeTitle)
        }
        // Root + Underway + All types falls back to the plain Underway title.
        if (segments.isEmpty()) {
            return context.getString(R.string.underway)
        }
        return segments.joinToString(" \u00B7 ")
    }

    private fun getTypeFilterTitle(context: Context, typeFilterMask: Int): String? {
        return ThingWidgetInfo.getTypeFilterTitle(context, typeFilterMask)
    }

    @JvmStatic
    fun createRemoteViewsForThingsListItem(
            context: Context?,
            thing: Thing?,
            appWidgetId: Int,
            contentWidthOverride: Int? = null): RemoteViews {
        val remoteViews = RemoteViews(context!!.packageName,
                R.layout.app_widget_item_thing)
        var alpha = 100
        @ThingWidgetInfo.Style var style: Int = ThingWidgetInfo.STYLE_NORMAL
        val dao: AppWidgetDAO = AppWidgetDAO.getInstance(context)!!
        val info: ThingWidgetInfo? = dao.getThingWidgetInfoById(appWidgetId)
        if (info != null) {
            alpha = info.alpha
            style = info.style
        }
        val clazz = getProviderClassForAppWidgetId(
                context, appWidgetId, ThingsListWidget::class.java)
        setAppearance(context, remoteViews, thing, appWidgetId,
                clazz, alpha, style, contentWidthOverride)
        return remoteViews
    }

    @JvmStatic
    fun createRemoteViewsForThingsListFolderItem(
        context: Context?,
        entry: ThingListEntry.FolderEntry,
        appWidgetId: Int
    ): RemoteViews {
        val appContext = context!!
        val remoteViews = RemoteViews(appContext.packageName, R.layout.app_widget_item_folder)
        applyFolderWidgetRootClip(remoteViews)
        val folder = entry.folder
        val background = folder.getBackground() ?: ThingBackground.pure(folder.getColor())
        val alpha = getContentAlphaForWidget(appContext, appWidgetId)
        val bitmap = BackgroundUtil.renderBackgroundBitmap(background, 64, 64, alpha)
        if (bitmap != null) {
            remoteViews.setImageViewBitmap(IV_WIDGET_FOLDER_BG, bitmap)
        }
        val foreground = widgetTextColorPrimary(appContext, background.representativeColor())
        remoteViews.setInt(IV_WIDGET_FOLDER_ICON, "setColorFilter", foreground)
        remoteViews.setTextColor(TV_WIDGET_FOLDER_TITLE, foreground)
        remoteViews.setTextColor(
                TV_WIDGET_FOLDER_COUNT,
                widgetTextColorTertiary(appContext, background.representativeColor()))
        remoteViews.setInt(
                IV_WIDGET_FOLDER_LOCK,
                "setColorFilter",
                widgetTextColorSecondary(appContext, background.representativeColor()))

        val title = folder.title.ifEmpty { appContext.getString(R.string.default_thing_folder_name) }
        remoteViews.setTextViewText(TV_WIDGET_FOLDER_TITLE, title)
        if (entry.effectivePrivate) {
            remoteViews.setViewVisibility(TV_WIDGET_FOLDER_COUNT, View.GONE)
            remoteViews.setViewVisibility(IV_WIDGET_FOLDER_LOCK, View.VISIBLE)
        } else {
            remoteViews.setViewVisibility(TV_WIDGET_FOLDER_COUNT, View.VISIBLE)
            remoteViews.setViewVisibility(IV_WIDGET_FOLDER_LOCK, View.GONE)
            remoteViews.setTextViewText(TV_WIDGET_FOLDER_COUNT, getFolderSummaryCountText(appContext, entry))
        }
        if (folder.isSticky()) {
            remoteViews.setViewVisibility(IV_STICKY_ONGOING, View.VISIBLE)
            remoteViews.setInt(IV_STICKY_ONGOING, "setImageAlpha", alpha)
            setGradientStickyMarker(
                remoteViews,
                IV_STICKY_ONGOING,
                appContext,
                R.drawable.ic_sticky,
                getThingsListStickyMarkerBackground(appContext, appWidgetId)
            )
            remoteViews.setContentDescription(
                IV_STICKY_ONGOING,
                appContext.getString(R.string.sticky_thing)
            )
        } else {
            remoteViews.setViewVisibility(IV_STICKY_ONGOING, View.GONE)
        }
        return remoteViews
    }

    private fun applyFolderWidgetRootClip(remoteViews: RemoteViews) {
        remoteViews.setInt(
                ROOT_WIDGET_FOLDER,
                "setBackgroundResource",
                R.drawable.bg_app_widget_card_clip)
        remoteViews.setBoolean(ROOT_WIDGET_FOLDER, "setClipToOutline", true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            remoteViews.setViewOutlinePreferredRadiusDimen(
                    ROOT_WIDGET_FOLDER,
                    R.dimen.thing_card_corner_radius)
        }
    }

    private fun getContentAlphaForWidget(context: Context, appWidgetId: Int): Int {
        val info = AppWidgetDAO.getInstance(context)!!.getThingWidgetInfoById(appWidgetId)
        val percent = if (info?.alpha == ThingWidgetInfo.HEADER_ALPHA_0) {
            0
        } else {
            abs(info?.alpha ?: 100)
        }
        return (percent / 100f * 255).toInt()
    }

    private fun foregroundForWidgetBackground(context: Context, backgroundColor: Int): Int {
        return if (BackgroundUtil.isLight(backgroundColor)) {
            ContextCompat.getColor(context, R.color.black_54p)
        } else {
            ContextCompat.getColor(context, R.color.white_86p)
        }
    }

    private fun widgetTextColorPrimary(context: Context, backgroundColor: Int): Int {
        return if (BackgroundUtil.isLight(backgroundColor)) {
            ContextCompat.getColor(context, R.color.black_86p)
        } else {
            ContextCompat.getColor(context, R.color.white_86p)
        }
    }

    private fun widgetTextColorSecondary(context: Context, backgroundColor: Int): Int {
        return if (BackgroundUtil.isLight(backgroundColor)) {
            ContextCompat.getColor(context, R.color.black_76p)
        } else {
            ContextCompat.getColor(context, R.color.white_76p)
        }
    }

    private fun widgetTextColorTertiary(context: Context, backgroundColor: Int): Int {
        return if (BackgroundUtil.isLight(backgroundColor)) {
            ContextCompat.getColor(context, R.color.black_66p)
        } else {
            ContextCompat.getColor(context, R.color.white_66p)
        }
    }

    private fun getFolderSummaryCountText(
        context: Context,
        entry: ThingListEntry.FolderEntry
    ): String {
        val folderCount = entry.directFolderCount
        val thingCount = entry.recursiveThingCount
        return when {
            folderCount > 0 && thingCount > 0 ->
                context.getString(R.string.thing_folder_count_folders_things, folderCount, thingCount)
            folderCount > 0 ->
                context.getString(R.string.thing_folder_count_folders, folderCount)
            else ->
                context.getString(R.string.thing_folder_count, thingCount)
        }
    }

    @JvmStatic
    fun createRemoteViewsForChecklistItem(
            context: Context?, item: String?, itemsSize: Int, isSingleThingWidget: Boolean): RemoteViews {
        return createRemoteViewsForChecklistItem(context, item, itemsSize, isSingleThingWidget, null)
    }

    /**
     * Phase 8: thing-aware checklist item renderer.
     */
    @JvmStatic
    fun createRemoteViewsForChecklistItem(
            context: Context?, item: String?, itemsSize: Int, isSingleThingWidget: Boolean, thing: Thing?): RemoteViews {
        val rv = RemoteViews(context!!.packageName, R.layout.check_list_tv_app_widget)

        if (!isSingleThingWidget) {
            rv.setInt(LL_CHECK_LIST_ITEM_ROOT, "setBackgroundResource", 0)
        }

        val state: Char = item!![0]
        val level: Int = CheckListHelper.levelOf(item)
        // 各级缩进（dp），逐级独立、便于单独微调。
        val indentDp = when (level) { 2 -> 19f; 3 -> 38f; else -> 0f }
        val indentPx: Int = Math.round(indentDp * screenDensity)
        rv.setViewPadding(LL_CHECK_LIST_ITEM_ROOT, (-6 * screenDensity).toInt() + indentPx, 0, 0, 0)
        // 状态图标下移：一级 +2.5dp，二级 +1.5dp，三级 +0.5dp。
        val iconTopPadDp = when (level) { 1 -> 2.5f; 2 -> 1.5f; else -> 0.5f }
        rv.setViewPadding(IV_STATE_CHECK_LIST, 0, Math.round(iconTopPadDp * screenDensity), 0, 0)

        val text: String = CheckListHelper.textOf(item)
        val textColor: Int
        if (state == '0') {
            rv.setImageViewResource(IV_STATE_CHECK_LIST, checklistIconResource(thing, false))
            setChecklistIconColor(rv, IV_STATE_CHECK_LIST, thing)
            if (isSingleThingWidget) {
                rv.setContentDescription(IV_STATE_CHECK_LIST,
                        context.getString(R.string.cd_checklist_unfinished_item_clickable))
            } else {
                rv.setContentDescription(IV_STATE_CHECK_LIST,
                        context.getString(R.string.cd_checklist_unfinished_item))
            }
            textColor = if (thing != null)
                    checklistItemTextColor(context, thing, false)
            else ContextCompat.getColor(context, R.color.white_76p)
            rv.setTextColor(TV_CONTENT_CHECK_LIST, CheckListHelper.colorForLevel(textColor, level, false))
            rv.setTextViewText(TV_CONTENT_CHECK_LIST, text)
        } else if (state == '1') {
            rv.setImageViewResource(IV_STATE_CHECK_LIST, checklistIconResource(thing, true))
            setChecklistIconColor(rv, IV_STATE_CHECK_LIST, thing)
            if (isSingleThingWidget) {
                rv.setContentDescription(IV_STATE_CHECK_LIST,
                        context.getString(R.string.cd_checklist_finished_item_clickable))
            } else {
                rv.setContentDescription(IV_STATE_CHECK_LIST,
                        context.getString(R.string.cd_checklist_finished_item))
            }
            textColor = if (thing != null)
                    checklistItemTextColor(context, thing, true)
            else Color.parseColor("#80FFFFFF")
            rv.setTextColor(TV_CONTENT_CHECK_LIST, CheckListHelper.colorForLevel(textColor, level, true))
            val spannable = SpannableString(text)
            spannable.setSpan(StrikethroughSpan(), 0, text.length,
                    Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
            rv.setTextViewText(TV_CONTENT_CHECK_LIST, spannable)
        }

        val levelSizeRatio = CheckListHelper.sizeRatioForLevel(level)
        // 文字相对状态图标略微下压，使第一行视觉居中（之前偏上一点）。
        val textTopNudge = (3 * screenDensity).toInt()
        if (itemsSize >= 8) {
            rv.setTextViewTextSize(TV_CONTENT_CHECK_LIST, TypedValue.COMPLEX_UNIT_SP, 14f * levelSizeRatio)
            rv.setViewPadding(TV_CONTENT_CHECK_LIST, 0, (screenDensity * 2).toInt() + textTopNudge, 0, 0)
        } else {
            val textSize: Float = -4 * itemsSize / 7f + 130f / 7
            rv.setTextViewTextSize(TV_CONTENT_CHECK_LIST, TypedValue.COMPLEX_UNIT_SP, textSize * levelSizeRatio)
            val mt: Float = -2 * textSize / 3 + 34f / 3
            rv.setViewPadding(TV_CONTENT_CHECK_LIST, 0, mt.toInt() + textTopNudge, 0, 0)
        }
        return rv
    }

    private fun setChecklistIconColor(remoteViews: RemoteViews, viewId: Int, thing: Thing?) {
        if (thing == null) {
            remoteViews.setInt(viewId, "setColorFilter", Color.WHITE)
        } else {
            setAdaptiveIconColor(remoteViews, viewId, thing)
        }
    }

    private fun checklistIconResource(thing: Thing?, finished: Boolean): Int {
        val dark: Boolean = thing != null && shouldUseDarkForeground(thing)
        return if (finished) {
            if (dark) R.drawable.checklist_checked_card_black
            else R.drawable.checklist_checked_card
        } else {
            if (dark) R.drawable.checklist_unchecked_card_black
            else R.drawable.checklist_unchecked_card
        }
    }

    /**
     * Phase 8: apply luminance-adaptive text colours to every text view on the
     * widget card.
     */
    private fun applyAdaptiveTextColors(
            context: Context, rv: RemoteViews, thing: Thing,
            mediaBackgroundForeground: Boolean) {
        val light: Boolean = if (mediaBackgroundForeground) {
            false
        } else {
            isThingBackgroundLight(thing)
        }
        val primary: Int   = if (light)
                ContextCompat.getColor(context, R.color.black_86p)
        else ContextCompat.getColor(context, R.color.white_86p)
        val secondary: Int = if (light)
                ContextCompat.getColor(context, R.color.black_76p)
        else ContextCompat.getColor(context, R.color.white_76p)
        val tertiary: Int  = if (light)
                ContextCompat.getColor(context, R.color.black_66p)
        else ContextCompat.getColor(context, R.color.white_66p)
        val disabled: Int  = if (light)
                ContextCompat.getColor(context, R.color.black_54p)
        else ContextCompat.getColor(context, R.color.white_54p)

        rv.setTextColor(TV_TITLE,                  primary)
        rv.setTextColor(TV_CONTENT,                secondary)
        rv.setTextColor(TV_REMINDER_TIME,          tertiary)
        rv.setTextColor(TV_HABIT_SUMMARY,          tertiary)
        rv.setTextColor(TV_HABIT_NEXT_REMINDER,    disabled)
        rv.setTextColor(TV_HABIT_LAST_FIVE,        disabled)
        rv.setTextColor(TV_HABIT_FINISHED_THIS_T,  tertiary)
        rv.setTextColor(TV_THING_STATE,            tertiary)
        rv.setTextColor(TV_AUDIO_COUNT,            tertiary)
        rv.setTextColor(TV_AUDIO_COUNT_LARGE,      tertiary)
        // Action label (e.g. 完成 / 本次完成) — keep it primary-prominent.
        rv.setTextColor(TV_THING_ACTION,           primary)
    }

    /**
     * Phase 8: text-colour tier for an individual checklist item.
     */
    internal fun checklistItemTextColor(context: Context, thing: Thing, finished: Boolean): Int {
        val light: Boolean = shouldUseDarkForeground(thing)
        if (finished) {
            // 50%-alpha strike-through colour — no res entry, use literal hex.
            return if (light) 0x80000000.toInt() else 0x80FFFFFF.toInt()
        }
        return ContextCompat.getColor(context,
                if (light) R.color.black_76p else R.color.white_76p)
    }

    private fun shouldUseDarkForeground(thing: Thing): Boolean {
        if (shouldUseMediaBackgroundForeground(thing)) {
            return false
        }
        return isThingBackgroundLight(thing)
    }

    private fun isThingBackgroundLight(thing: Thing): Boolean {
        val bg = thing.getBackground()
        return if (bg != null) BackgroundUtil.isLight(bg) else BackgroundUtil.isLight(thing.getColor())
    }

    private fun shouldUseMediaBackgroundForeground(thing: Thing): Boolean {
        if (!thing.thingCardAppearance.mediaBackgroundEnabled) return false
        val context = App.getApp() ?: return false
        return RemoteThingCardMediaRenderer.resolveRenderableMediaSource(context, thing) != null
    }

    private fun adaptiveIconColor(thing: Thing): Int {
        return if (shouldUseDarkForeground(thing)) Color.BLACK else Color.WHITE
    }

    private fun setAdaptiveIconColor(remoteViews: RemoteViews, viewId: Int, thing: Thing) {
        remoteViews.setInt(viewId, "setColorFilter", adaptiveIconColor(thing))
    }

    private fun setGradientStickyMarker(
        remoteViews: RemoteViews,
        viewId: Int,
        context: Context,
        @DrawableRes ivRes: Int,
        background: ThingBackground
    ) {
        val icon = ContextCompat.getDrawable(context, ivRes) ?: return
        val tinted = BackgroundUtil.tintDrawable(context.resources, icon, background) ?: return
        val width = max(1, tinted.intrinsicWidth)
        val height = max(1, tinted.intrinsicHeight)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        tinted.setBounds(0, 0, width, height)
        tinted.draw(canvas)
        remoteViews.setImageViewBitmap(viewId, bitmap)
    }

    private fun getThingsListStickyMarkerBackground(
        context: Context,
        appWidgetId: Int
    ): ThingBackground {
        val info = AppWidgetDAO.getInstance(context)!!.getThingWidgetInfoById(appWidgetId)
        val folder = resolveThingsListTargetFolder(context, info)
        return folder?.getBackground()
            ?: folder?.let { ThingBackground.pure(it.getColor()) }
            ?: App.defaultAccentBackground
    }

    private fun setAppearance(
            context: Context, remoteViews: RemoteViews, thing: Thing?, appWidgetId: Int, clazz: Class<*>?,
            alpha: Int, @ThingWidgetInfo.Style style: Int,
            contentWidthOverride: Int? = null) {
        var a: Int = alpha
        a = if (a == ThingWidgetInfo.HEADER_ALPHA_0) {
            0
        } else {
            abs(a)
        }
        a = (a / 100f * 255).toInt()
        applyWidgetRootClip(remoteViews)
        val mediaBackground = renderWidgetMediaBackground(
                context, thing!!, appWidgetId, clazz, style, a, contentWidthOverride)
        if (isThingsListWidgetClass(clazz)) {
            remoteViews.setInt(
                    ROOT_WIDGET_THING,
                    "setMinimumHeight",
                    mediaBackground?.layoutMinHeight ?: 0)
        }
        setWidgetBackground(remoteViews, thing, a, mediaBackground)

        // Phase 8: adapt all text colours on the widget card to the thing's
        // luminance.
        applyAdaptiveTextColors(context, remoteViews, thing, mediaBackground != null)
        applyAdaptiveSeparatorBackgrounds(remoteViews, thing)

        setStickyOrOngoing(
                context,
                remoteViews,
                thing,
                a,
                clazz,
                style,
                if (isThingsListWidgetClass(clazz)) {
                    getThingsListStickyMarkerBackground(context, appWidgetId)
                } else {
                    App.defaultAccentBackground
                })

        setImageAttachment(context, remoteViews, thing, appWidgetId, clazz,
                mediaBackground != null, a, style, contentWidthOverride)

        setTitleAndPrivate(context, remoteViews, thing, style)

        setContent(context, remoteViews, thing, appWidgetId, clazz)

        setAudioAttachment(context, remoteViews, thing)

        setState(context, remoteViews, thing)
        setAction(remoteViews)

        setReminder(context, remoteViews, thing)
        setHabit(context, remoteViews, thing, style)

        if (style == ThingWidgetInfo.STYLE_SIMPLE && getTitleToDisplayForSimpleStyle(thing) != null) {
            remoteViews.setViewVisibility(LV_CHECKLIST,              View.GONE)
            remoteViews.setViewVisibility(LL_CHECK_LIST_ITEMS,       View.GONE)
            remoteViews.setViewVisibility(TV_CONTENT,                View.GONE)

            remoteViews.setViewVisibility(LL_AUDIO_ATTACHMENT,       View.GONE)
            remoteViews.setViewVisibility(LL_AUDIO_ATTACHMENT_LARGE, View.GONE)
        }

        setDoing(remoteViews, thing)
    }

    private fun applyWidgetRootClip(remoteViews: RemoteViews) {
        remoteViews.setInt(
                ROOT_WIDGET_THING,
                "setBackgroundResource",
                R.drawable.bg_app_widget_card_clip)
        remoteViews.setBoolean(ROOT_WIDGET_THING, "setClipToOutline", true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            remoteViews.setViewOutlinePreferredRadiusDimen(
                    ROOT_WIDGET_THING,
                    R.dimen.thing_card_corner_radius)
        }
    }

    private fun setWidgetBackground(
            remoteViews: RemoteViews, thing: Thing, alpha: Int,
            mediaBackground: WidgetMediaBackground?) {
        if (mediaBackground != null) {
            remoteViews.setImageViewBitmap(IV_WIDGET_BG, bitmapWithAlpha(mediaBackground.bitmap, alpha))
            remoteViews.setInt(IV_WIDGET_BG, "setImageAlpha", 255)
            return
        }

        // 64×64 ≈ 16KB; saves RemoteViews bitmap budget for plain / gradient
        // backgrounds.
        val bgBm: Bitmap? = BackgroundUtil
                .renderBackgroundBitmap(thing.getBackground(), 64, 64, alpha)
        if (bgBm != null) {
            remoteViews.setImageViewBitmap(IV_WIDGET_BG, bgBm)
        }
        remoteViews.setInt(IV_WIDGET_BG, "setImageAlpha", 255)
    }

    private fun bitmapWithAlpha(source: Bitmap, alpha: Int): Bitmap {
        val normalized = alpha.coerceIn(0, 255)
        if (normalized == 255) return source
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.alpha = normalized
        canvas.drawBitmap(source, 0f, 0f, paint)
        return result
    }

    private fun renderWidgetMediaBackground(
            context: Context, thing: Thing, appWidgetId: Int, clazz: Class<*>?,
            @ThingWidgetInfo.Style style: Int, alpha: Int,
            contentWidthOverride: Int? = null): WidgetMediaBackground? {
        if (!thing.thingCardAppearance.mediaBackgroundEnabled) return null
        val mediaSource = RemoteThingCardMediaRenderer.resolveRenderableMediaSource(context, thing)
                ?: return null
        val rawTargetWidth = contentWidthOverride
                ?: getWidgetContentTargetWidth(context, appWidgetId, clazz)
        val heightTarget = getWidgetMediaBackgroundTargetHeight(
                context, thing, mediaSource, rawTargetWidth, appWidgetId, clazz, style)
        val rawTargetHeight = heightTarget.height
        if (heightTarget.clampReasons.isNotEmpty()) {
            Log.d(
                    TAG,
                    "Project widget media background for thing ${thing.id}: " +
                            "height=${heightTarget.height}, reasons=" +
                            heightTarget.clampReasons.joinToString("+")
            )
        }
        val targetSize = if (isThingsListWidgetClass(clazz)) {
            clampThingsListWidgetMediaBackgroundTargetSize(rawTargetWidth, rawTargetHeight)
        } else {
            WidgetBitmapTargetSize(rawTargetWidth, rawTargetHeight)
        }
        if (targetSize.width != rawTargetWidth || targetSize.height != rawTargetHeight) {
            Log.i(
                    TAG,
                    "Clamp widget media background for thing ${thing.id}: " +
                            "${rawTargetWidth}x${rawTargetHeight} -> " +
                            "${targetSize.width}x${targetSize.height}, reasons=" +
                            targetSize.clampReasons.joinToString("+")
            )
        }
        return try {
            val rendered = RemoteThingCardMediaRenderer.renderMediaBackground(
                    context, thing, targetSize.width, targetSize.height)
                    ?: return null
            WidgetMediaBackground(
                    rendered.bitmap,
                    if (isThingsListWidgetClass(clazz)) rawTargetHeight else null)
        } catch (e: OutOfMemoryError) {
            Log.w(
                    TAG,
                    "Skip widget media background for thing ${thing.id}; " +
                            "target ${targetSize.width}x${targetSize.height} is too large",
                    e
            )
            null
        } catch (e: Exception) {
            Log.w(
                    TAG,
                    "Skip widget media background for thing ${thing.id}; render failed",
                    e
            )
            null
        }
    }

    private fun clampThingsListWidgetMediaBackgroundTargetSize(
            width: Int,
            height: Int): WidgetBitmapTargetSize {
        val safeWidth = max(1, width)
        val safeHeight = max(1, height)
        val reasons = mutableListOf<String>()
        val dimensionScale = min(
                1.0,
                min(
                        WIDGET_LIST_MEDIA_BACKGROUND_MAX_BITMAP_DIMENSION_PX.toDouble() /
                                safeWidth.toDouble(),
                        WIDGET_LIST_MEDIA_BACKGROUND_MAX_BITMAP_DIMENSION_PX.toDouble() /
                                safeHeight.toDouble()
                )
        )
        if (dimensionScale < 1.0) {
            reasons += "list-media-background-max-bitmap-dimension:" +
                    WIDGET_LIST_MEDIA_BACKGROUND_MAX_BITMAP_DIMENSION_PX
        }
        val pixels = safeWidth.toDouble() * safeHeight.toDouble()
        val pixelScale = if (pixels <= WIDGET_LIST_MEDIA_BACKGROUND_MAX_BITMAP_PIXELS) {
            1.0
        } else {
            sqrt(WIDGET_LIST_MEDIA_BACKGROUND_MAX_BITMAP_PIXELS.toDouble() / pixels)
        }
        if (pixelScale < 1.0) {
            reasons += "list-media-background-pixel-budget:" +
                    WIDGET_LIST_MEDIA_BACKGROUND_MAX_BITMAP_PIXELS
        }
        val scale = min(dimensionScale, pixelScale)
        return WidgetBitmapTargetSize(
                max(1, (safeWidth * scale).roundToInt()),
                max(1, (safeHeight * scale).roundToInt()),
                reasons
        )
    }

    private fun getWidgetContentTargetWidth(
            context: Context, appWidgetId: Int, clazz: Class<*>?): Int {
        val defaultDp = getWidgetDefaultWidthDp(clazz)
        val widthDp = getWidgetOptionDp(
                context, appWidgetId, AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, defaultDp)
        val widthPx = (max(1, widthDp) * screenDensity).toInt()
        val maxWidth = dpToPx(WIDGET_REMOTE_BITMAP_MAX_DIMENSION_DP)
        val targetWidth = max(1, min(widthPx, maxWidth))
        if (targetWidth != widthPx) {
            Log.d(
                    TAG,
                    "Clamp widget content width for appWidgetId[$appWidgetId]: " +
                            "$widthPx -> $targetWidth, reason=remote-bitmap-max-dimension:" +
                            WIDGET_REMOTE_BITMAP_MAX_DIMENSION_DP
            )
        }
        return targetWidth
    }

    private fun getWidgetMediaBackgroundTargetHeight(
            context: Context, thing: Thing, mediaSource: ThingCardMediaHelper.MediaSource,
            targetWidth: Int, appWidgetId: Int, clazz: Class<*>?,
            @ThingWidgetInfo.Style style: Int): WidgetMediaBackgroundTargetHeight {
        val sourceAppearance = thing.thingCardAppearance.sources[mediaSource.typePathName]
        val ratio = sourceAppearance?.mediaBackgroundTargetAspectRatio()
        val reasons = mutableListOf<String>()
        if (isSingleThingWidgetClass(clazz)) {
            val widgetHeightDp = getWidgetHeightBudgetDp(context, appWidgetId, clazz)
            val maxHeightPx = dpToPx(min(widgetHeightDp, WIDGET_REMOTE_BITMAP_MAX_DIMENSION_DP))
            val contentFloorPx = dpToPx(getSingleWidgetContentFloorDp(widgetHeightDp))
            val rawHeight = if (ratio != null && ratio > 0.0) {
                (targetWidth / ratio).toInt()
            } else {
                maxHeightPx
            }
            val flooredHeight = max(contentFloorPx, rawHeight)
            if (flooredHeight != rawHeight) {
                reasons += "single-widget-content-floor:$contentFloorPx"
            }
            val targetHeight = min(maxHeightPx, flooredHeight)
            if (targetHeight != flooredHeight) {
                reasons += "single-widget-height-budget:$maxHeightPx"
            }
            return WidgetMediaBackgroundTargetHeight(targetHeight, reasons)
        }

        val fallbackDp = WIDGET_LIST_MEDIA_BACKGROUND_FALLBACK_HEIGHT_DP
        val fallbackPx = (fallbackDp * screenDensity).toInt()
        val rawHeight = if (ratio != null && ratio > 0.0) {
            (targetWidth / ratio).toInt()
        } else {
            fallbackPx
        }
        val maxHeightDp = WIDGET_LIST_MEDIA_BACKGROUND_MAX_HEIGHT_DP
        val maxHeightPx = max(1, (maxHeightDp * screenDensity).toInt())
        val desiredHeight = max(1, min(rawHeight, maxHeightPx))
        if (desiredHeight != rawHeight) {
            reasons += "list-media-background-max-height:$maxHeightPx"
        }
        val contentHeight = dpToPx(estimateThingsListWidgetContentRowHeightDp(
                context,
                thing,
                max(80, pxToDp(targetWidth) - 24),
                style))
        val flooredHeight = max(contentHeight, desiredHeight)
        if (flooredHeight != desiredHeight) {
            reasons += "list-media-background-content-floor:$contentHeight"
        }
        val hardMaxHeight = dpToPx(WIDGET_LIST_MEDIA_HARD_MAX_HEIGHT_DP)
        val targetHeight = min(hardMaxHeight, flooredHeight)
        if (targetHeight != flooredHeight) {
            reasons += "list-media-background-hard-height:$hardMaxHeight"
        }
        return WidgetMediaBackgroundTargetHeight(targetHeight, reasons)
    }

    private fun getWidgetOptionDp(
            context: Context, appWidgetId: Int, key: String, defaultValue: Int): Int {
        return try {
            val options: Bundle = AppWidgetManager.getInstance(context)
                    .getAppWidgetOptions(appWidgetId)
            val value = options.getInt(key, 0)
            if (value > 0) value else defaultValue
        } catch (_: Exception) {
            defaultValue
        }
    }

    private fun getWidgetHeightBudgetDp(
            context: Context, appWidgetId: Int, clazz: Class<*>?): Int {
        val defaultHeightDp = getWidgetDefaultHeightDp(clazz)
        val minHeightDp = getWidgetOptionDp(
                context, appWidgetId, AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        if (minHeightDp > 0) return minHeightDp
        return getWidgetOptionDp(
                context, appWidgetId, AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,
                defaultHeightDp)
    }

    private fun isSingleThingWidgetClass(clazz: Class<*>?): Boolean {
        return clazz != null && BaseThingWidget::class.java.isAssignableFrom(clazz)
    }

    private fun isThingsListWidgetClass(clazz: Class<*>?): Boolean {
        return clazz != null && ThingsListWidget::class.java.isAssignableFrom(clazz)
    }

    private fun getWidgetDefaultWidthDp(clazz: Class<*>?): Int {
        return cellsToWidgetMinDp(getWidgetCellSpan(clazz).width)
    }

    private fun getWidgetDefaultHeightDp(clazz: Class<*>?): Int {
        return cellsToWidgetMinDp(getWidgetCellSpan(clazz).height)
    }

    private fun getWidgetCellSpan(clazz: Class<*>?): WidgetCellSpan {
        return when {
            isSingleThingWidgetClass(clazz) -> getSingleWidgetCellSpan(clazz)
            isThingsListWidgetClass(clazz) -> getThingsListWidgetCellSpan(clazz)
            else -> WidgetCellSpan(3, 3)
        }
    }

    private fun getSingleWidgetCellSpan(clazz: Class<*>?): WidgetCellSpan {
        return when (clazz) {
            ThingWidgetTiny::class.java -> WidgetCellSpan(1, 1)
            ThingWidgetSmall::class.java -> WidgetCellSpan(2, 2)
            ThingWidgetMiddle::class.java -> WidgetCellSpan(3, 3)
            ThingWidgetLarge::class.java -> WidgetCellSpan(4, 4)
            ThingWidget4x2::class.java -> WidgetCellSpan(4, 2)
            ThingWidget2x4::class.java -> WidgetCellSpan(2, 4)
            ThingWidget4x3::class.java -> WidgetCellSpan(4, 3)
            ThingWidget3x4::class.java -> WidgetCellSpan(3, 4)
            ThingWidget5x2::class.java -> WidgetCellSpan(5, 2)
            ThingWidget2x5::class.java -> WidgetCellSpan(2, 5)
            ThingWidget5x3::class.java -> WidgetCellSpan(5, 3)
            ThingWidget3x5::class.java -> WidgetCellSpan(3, 5)
            ThingWidget5x4::class.java -> WidgetCellSpan(5, 4)
            ThingWidget4x5::class.java -> WidgetCellSpan(4, 5)
            ThingWidget5x5::class.java -> WidgetCellSpan(5, 5)
            ThingWidget6x2::class.java -> WidgetCellSpan(6, 2)
            ThingWidget2x6::class.java -> WidgetCellSpan(2, 6)
            ThingWidget6x3::class.java -> WidgetCellSpan(6, 3)
            ThingWidget3x6::class.java -> WidgetCellSpan(3, 6)
            ThingWidget6x4::class.java -> WidgetCellSpan(6, 4)
            ThingWidget4x6::class.java -> WidgetCellSpan(4, 6)
            ThingWidget6x5::class.java -> WidgetCellSpan(6, 5)
            ThingWidget5x6::class.java -> WidgetCellSpan(5, 6)
            ThingWidget6x6::class.java -> WidgetCellSpan(6, 6)
            else -> WidgetCellSpan(3, 3)
        }
    }

    private fun getThingsListWidgetCellSpan(clazz: Class<*>?): WidgetCellSpan {
        return when (clazz) {
            ThingsListWidget4x4::class.java -> WidgetCellSpan(4, 4)
            ThingsListWidget5x4::class.java -> WidgetCellSpan(5, 4)
            ThingsListWidget4x5::class.java -> WidgetCellSpan(4, 5)
            ThingsListWidget5x5::class.java -> WidgetCellSpan(5, 5)
            ThingsListWidget6x4::class.java -> WidgetCellSpan(6, 4)
            ThingsListWidget4x6::class.java -> WidgetCellSpan(4, 6)
            ThingsListWidget6x5::class.java -> WidgetCellSpan(6, 5)
            ThingsListWidget5x6::class.java -> WidgetCellSpan(5, 6)
            ThingsListWidget6x6::class.java -> WidgetCellSpan(6, 6)
            else -> WidgetCellSpan(3, 3)
        }
    }

    private fun cellsToWidgetMinDp(cells: Int): Int {
        return max(1, 70 * cells - 30)
    }

    private fun dpToPx(dp: Int): Int {
        return max(1, (dp * screenDensity).toInt())
    }

    private fun pxToDp(px: Int): Int {
        return max(1, (px / screenDensity).roundToInt())
    }

    private data class WidgetCellSpan(
            val width: Int,
            val height: Int)

    private fun setSeparatorVisibilities(
            remoteViews: RemoteViews, visibility: Int) {
        remoteViews.setViewVisibility(V_STATE_SEPARATOR,    visibility)
        remoteViews.setViewVisibility(V_REMINDER_SEPARATOR, visibility)
        remoteViews.setViewVisibility(V_HABIT_SEPARATOR_1,  visibility)
        remoteViews.setViewVisibility(V_HABIT_SEPARATOR_2,  visibility)
    }

    private fun applyAdaptiveSeparatorBackgrounds(remoteViews: RemoteViews, thing: Thing) {
        val separatorRes = if (shouldUseDarkForeground(thing)) {
            R.drawable.dashed_line_card_black
        } else {
            R.drawable.dashed_line_card
        }
        intArrayOf(
                V_STATE_SEPARATOR,
                V_REMINDER_SEPARATOR,
                V_HABIT_SEPARATOR_1,
                V_HABIT_SEPARATOR_2
        ).forEach { viewId ->
            remoteViews.setInt(viewId, "setBackgroundResource", separatorRes)
        }
    }

    private fun setStickyOrOngoing(context: Context, remoteViews: RemoteViews, thing: Thing,
                                   alpha: Int, clazz: Class<*>?, @ThingWidgetInfo.Style style: Int,
                                   markerBackground: ThingBackground) {
        val sticky: Boolean = thing.location < 0
        val ongoing: Boolean = FrequentSettings.getLong(Def.Meta.KEY_ONGOING_THING_ID) == thing.id
        if (!sticky && !ongoing) {
            remoteViews.setViewVisibility(IV_STICKY_ONGOING, View.GONE)
            remoteViews.setViewVisibility(IV_STICKY_ONGOING_SMALL, View.GONE)
        } else {
            @DrawableRes val ivRes: Int = if (sticky) R.drawable.ic_sticky else R.drawable.ic_ongoing_notication
            val cd: String = context.getString(if (sticky) R.string.sticky_thing else R.string.ongoing_thing)
            if (isThingsListWidgetClass(clazz) && style == ThingWidgetInfo.STYLE_SIMPLE) {
                remoteViews.setViewVisibility(IV_STICKY_ONGOING, View.GONE)
                remoteViews.setViewVisibility(IV_STICKY_ONGOING_SMALL, View.VISIBLE)
                remoteViews.setInt(IV_STICKY_ONGOING_SMALL, "setImageAlpha", alpha)
                setGradientStickyMarker(
                        remoteViews, IV_STICKY_ONGOING_SMALL, context, ivRes, markerBackground)
                remoteViews.setContentDescription(IV_STICKY_ONGOING_SMALL, cd)
            } else {
                remoteViews.setViewVisibility(IV_STICKY_ONGOING, View.VISIBLE)
                remoteViews.setViewVisibility(IV_STICKY_ONGOING_SMALL, View.GONE)
                remoteViews.setInt(IV_STICKY_ONGOING, "setImageAlpha", alpha)
                setGradientStickyMarker(
                        remoteViews, IV_STICKY_ONGOING, context, ivRes, markerBackground)
                remoteViews.setContentDescription(IV_STICKY_ONGOING, cd)
            }
        }
    }

    private fun setImageAttachment(
            context: Context, remoteViews: RemoteViews, thing: Thing, appWidgetId: Int,
            clazz: Class<*>?, mediaBackgroundApplied: Boolean,
            alpha: Int, @ThingWidgetInfo.Style style: Int,
            contentWidthOverride: Int? = null) {
        hideForegroundMediaSlots(remoteViews)
        remoteViews.setViewVisibility(TV_MEDIA_BACKGROUND_COUNT, View.GONE)

        if (thing.isPrivate()) {
            remoteViews.setViewVisibility(V_PADDING_BOTTOM, View.VISIBLE)
            return
        }

        val attachment: String = thing.attachment!!
        val mediaSource = RemoteThingCardMediaRenderer.resolveRenderableMediaSource(context, thing)
        if (mediaSource == null) {
            remoteViews.setViewVisibility(V_PADDING_BOTTOM, View.VISIBLE)
            return
        }

        if (mediaBackgroundApplied) {
            setMediaBackgroundAttachmentCount(remoteViews, attachment, context)
            remoteViews.setViewVisibility(V_PADDING_BOTTOM, View.VISIBLE)
            return
        }

        val placement = getRemoteImagePlacement(thing)
        val rendered = renderImageForWidgetSlot(
                context, thing, appWidgetId, clazz, placement, style, contentWidthOverride)
        if (rendered == null) {
            remoteViews.setViewVisibility(V_PADDING_BOTTOM, View.VISIBLE)
            return
        }

        val slot = getImageAttachmentSlot(placement)
        remoteViews.setViewVisibility(slot.containerId, View.VISIBLE)
        remoteViews.setViewVisibility(slot.imageId, View.VISIBLE)
        remoteViews.setImageViewBitmap(slot.imageId, bitmapWithAlpha(rendered.bitmap, alpha))
        remoteViews.setInt(slot.imageId, "setImageAlpha", 255)
        setImageAttachmentCount(remoteViews, slot.countId, attachment, context)

        remoteViews.setViewVisibility(V_PADDING_BOTTOM,
                if (placement == Thing.THING_CARD_IMAGE_PLACEMENT_BOTTOM) View.GONE else View.VISIBLE)
        setSeparatorVisibilities(remoteViews, View.GONE)
    }

    private data class ImageAttachmentSlot(
            val containerId: Int,
            val imageId: Int,
            val countId: Int)

    private fun hideForegroundMediaSlots(remoteViews: RemoteViews) {
        val slots = arrayOf(
                ImageAttachmentSlot(FL_IMAGE_ATTACHMENT, IV_IMAGE_ATTACHMENT, TV_IMAGE_COUNT),
                ImageAttachmentSlot(
                        FL_IMAGE_ATTACHMENT_BOTTOM, IV_IMAGE_ATTACHMENT_BOTTOM,
                        TV_IMAGE_COUNT_BOTTOM),
                ImageAttachmentSlot(
                        FL_IMAGE_ATTACHMENT_LEFT, IV_IMAGE_ATTACHMENT_LEFT,
                        TV_IMAGE_COUNT_LEFT),
                ImageAttachmentSlot(
                        FL_IMAGE_ATTACHMENT_RIGHT, IV_IMAGE_ATTACHMENT_RIGHT,
                        TV_IMAGE_COUNT_RIGHT)
        )
        for (slot in slots) {
            remoteViews.setViewVisibility(slot.containerId, View.GONE)
            remoteViews.setViewVisibility(slot.imageId, View.GONE)
            remoteViews.setViewVisibility(slot.countId, View.GONE)
            remoteViews.setInt(slot.imageId, "setImageAlpha", 255)
        }
    }

    private fun setImageAttachmentCount(
            remoteViews: RemoteViews, viewId: Int, attachment: String, context: Context) {
        val count = AttachmentHelper.getImageAttachmentCountStr(attachment, context)
        if (count == null) {
            remoteViews.setViewVisibility(viewId, View.GONE)
        } else {
            remoteViews.setViewVisibility(viewId, View.VISIBLE)
            remoteViews.setTextViewText(viewId, count)
        }
    }

    private fun setMediaBackgroundAttachmentCount(
            remoteViews: RemoteViews, attachment: String, context: Context) {
        val count = AttachmentHelper.getImageAttachmentCountStr(attachment, context)
        if (count == null) {
            remoteViews.setViewVisibility(TV_MEDIA_BACKGROUND_COUNT, View.GONE)
        } else {
            remoteViews.setViewVisibility(TV_MEDIA_BACKGROUND_COUNT, View.VISIBLE)
            remoteViews.setTextViewText(TV_MEDIA_BACKGROUND_COUNT, count)
        }
    }

    private fun getImageAttachmentSlot(
            @Thing.ThingCardImagePlacement placement: Int): ImageAttachmentSlot {
        return when (placement) {
            Thing.THING_CARD_IMAGE_PLACEMENT_BOTTOM -> ImageAttachmentSlot(
                    FL_IMAGE_ATTACHMENT_BOTTOM, IV_IMAGE_ATTACHMENT_BOTTOM, TV_IMAGE_COUNT_BOTTOM)
            Thing.THING_CARD_IMAGE_PLACEMENT_LEFT -> ImageAttachmentSlot(
                    FL_IMAGE_ATTACHMENT_LEFT, IV_IMAGE_ATTACHMENT_LEFT, TV_IMAGE_COUNT_LEFT)
            Thing.THING_CARD_IMAGE_PLACEMENT_RIGHT -> ImageAttachmentSlot(
                    FL_IMAGE_ATTACHMENT_RIGHT, IV_IMAGE_ATTACHMENT_RIGHT, TV_IMAGE_COUNT_RIGHT)
            else -> ImageAttachmentSlot(FL_IMAGE_ATTACHMENT, IV_IMAGE_ATTACHMENT, TV_IMAGE_COUNT)
        }
    }

    @Thing.ThingCardImagePlacement
    private fun getRemoteImagePlacement(thing: Thing): Int {
        return when (thing.thingCardAppearance.imagePlacement) {
            Thing.THING_CARD_IMAGE_PLACEMENT_BOTTOM,
            Thing.THING_CARD_IMAGE_PLACEMENT_LEFT,
            Thing.THING_CARD_IMAGE_PLACEMENT_RIGHT -> thing.thingCardAppearance.imagePlacement
            else -> Thing.THING_CARD_IMAGE_PLACEMENT_TOP
        }
    }

    private fun renderImageForWidgetSlot(
            context: Context, thing: Thing, appWidgetId: Int, clazz: Class<*>?,
            @Thing.ThingCardImagePlacement placement: Int,
            @ThingWidgetInfo.Style style: Int,
            contentWidthOverride: Int? = null
    ): RemoteThingCardMediaRenderer.ThumbnailRequest? {
        val target = getWidgetMediaSlotTarget(
                context, thing, appWidgetId, clazz, placement, style, contentWidthOverride)
        val presentationKey = if (isSideImagePlacement(placement)) {
            ThingCardAppearance.PRESENTATION_SIDE_PANEL
        } else {
            ThingCardAppearance.PRESENTATION_THUMBNAIL
        }
        return RemoteThingCardMediaRenderer.renderThumbnail(
                context, thing, target.width, target.height, presentationKey)
    }

    private fun isSideImagePlacement(@Thing.ThingCardImagePlacement placement: Int): Boolean {
        return placement == Thing.THING_CARD_IMAGE_PLACEMENT_LEFT
                || placement == Thing.THING_CARD_IMAGE_PLACEMENT_RIGHT
    }

    private fun getWidgetMediaSlotTarget(
            context: Context, thing: Thing, appWidgetId: Int, clazz: Class<*>?,
            @Thing.ThingCardImagePlacement placement: Int,
            @ThingWidgetInfo.Style style: Int,
            contentWidthOverride: Int? = null): WidgetMediaSlotTarget {
        val contentWidth = contentWidthOverride
                ?: getWidgetContentTargetWidth(context, appWidgetId, clazz)
        if (placement != Thing.THING_CARD_IMAGE_PLACEMENT_LEFT
                && placement != Thing.THING_CARD_IMAGE_PLACEMENT_RIGHT) {
            return WidgetMediaSlotTarget(
                    contentWidth,
                    RemoteThingCardMediaRenderer.getThumbnailTargetHeight(
                            thing,
                            contentWidth,
                            getWidgetTopBottomMediaSlotMaxHeight(context, appWidgetId, clazz)))
        }

        val percentWidth = getWidgetSideMediaPercentWidth(context, thing, contentWidth)
        val targetRatio = getWidgetSideMediaTargetAspectRatio(thing)
        if (isSingleThingWidgetClass(clazz)) {
            val height = getSingleWidgetSideMediaSlotTargetHeight(context, appWidgetId, clazz)
            val width = if (targetRatio == null) {
                percentWidth
            } else {
                val hintedWidth = (height * targetRatio).roundToInt()
                val widthClamp = clampWidgetSideMediaWidthWithReason(
                        context, contentWidth, hintedWidth)
                if (widthClamp.reason != null) {
                    Log.d(
                            TAG,
                            "Clamp widget side media for thing ${thing.id}: " +
                                    "$hintedWidth -> ${widthClamp.width}, " +
                                    "targetRatio=$targetRatio, height=$height, " +
                                    "contentWidth=$contentWidth, reason=${widthClamp.reason}"
                    )
                }
                widthClamp.width
            }
            return WidgetMediaSlotTarget(width, height)
        }

        if (targetRatio == null) {
            val height = getThingsListWidgetSideMediaSlotTargetHeight(
                    context, thing, contentWidth, percentWidth, style)
            return WidgetMediaSlotTarget(percentWidth, height)
        }

        val estimatedHeight = getThingsListWidgetSideMediaSlotTargetHeight(
                context, thing, contentWidth, percentWidth, style)
        val target = projectThingsListWidgetSideMediaSlotTarget(
                context, thing, contentWidth, percentWidth, estimatedHeight,
                targetRatio, style)
        logWidgetSideMediaProjectionBoundary(
                context, thing, contentWidth, target, targetRatio)
        return target
    }

    private fun projectThingsListWidgetSideMediaSlotTarget(
            context: Context, thing: Thing, contentWidth: Int, initialWidth: Int,
            initialHeight: Int, targetRatio: Double,
            @ThingWidgetInfo.Style style: Int): WidgetMediaSlotTarget {
        var width = clampWidgetSideMediaWidth(context, contentWidth, initialWidth)
        var bestTarget = WidgetMediaSlotTarget(width, initialHeight)
        var bestError = Int.MAX_VALUE
        repeat(WIDGET_SIDE_MEDIA_PROJECTION_MAX_ITERATIONS) {
            val height = getThingsListWidgetSideMediaSlotTargetHeight(
                    context, thing, contentWidth, width, style)
            val nextWidth = clampWidgetSideMediaWidth(
                    context, contentWidth, (height * targetRatio).roundToInt())
            val error = abs(nextWidth - width)
            if (error < bestError) {
                bestTarget = WidgetMediaSlotTarget(width, height)
                bestError = error
            }
            if (error <= WIDGET_SIDE_MEDIA_PROJECTION_TOLERANCE_PX) {
                return WidgetMediaSlotTarget(width, height)
            }
            width = nextWidth
        }
        return bestTarget
    }

    private fun getSingleWidgetSideMediaSlotTargetHeight(
            context: Context, appWidgetId: Int, clazz: Class<*>?): Int {
        val heightDp = getWidgetHeightBudgetDp(context, appWidgetId, clazz)
        return dpToPx(min(heightDp, WIDGET_REMOTE_BITMAP_MAX_DIMENSION_DP))
    }

    private fun getThingsListWidgetSideMediaSlotTargetHeight(
            context: Context, thing: Thing, contentWidth: Int, targetWidth: Int,
            @ThingWidgetInfo.Style style: Int): Int {
        val rawHeight = dpToPx(estimateThingsListWidgetSideMediaRowHeightDp(
                context, thing, contentWidth, targetWidth, style))
        return min(
                max(rawHeight, dpToPx(WIDGET_LIST_SIDE_MEDIA_MIN_HEIGHT_DP)),
                dpToPx(WIDGET_LIST_MEDIA_HARD_MAX_HEIGHT_DP))
    }

    private fun estimateThingsListWidgetSideMediaRowHeightDp(
            context: Context, thing: Thing, contentWidth: Int, targetWidth: Int,
            @ThingWidgetInfo.Style style: Int): Int {
        if (thing.isPrivate()) return WIDGET_LIST_SIDE_MEDIA_MIN_HEIGHT_DP

        val textWidthDp = max(80, pxToDp(contentWidth - targetWidth) - 24)
        val contentHeight = estimateThingsListWidgetContentRowHeightDp(
                context, thing, textWidthDp, style)

        // Ensure the row is tall enough to preserve the image's target aspect
        // ratio so that centreCrop doesn't cut off too much of the cover media.
        val imageMinHeightDp = run {
            val ratio = getWidgetSideMediaTargetAspectRatio(thing)
            if (ratio != null && ratio > 0.0) {
                val targetWidthDp = pxToDp(targetWidth)
                max(1, (targetWidthDp / ratio).roundToInt())
            } else {
                0
            }
        }

        return maxOf(WIDGET_LIST_SIDE_MEDIA_MIN_HEIGHT_DP, contentHeight, imageMinHeightDp)
    }

    private fun estimateThingsListWidgetContentRowHeightDp(
            context: Context, thing: Thing, textWidthDp: Int,
            @ThingWidgetInfo.Style style: Int): Int {
        if (thing.isPrivate()) return WIDGET_LIST_SIDE_MEDIA_MIN_HEIGHT_DP

        if (style == ThingWidgetInfo.STYLE_SIMPLE) {
            val simpleTitle = getTitleToDisplayForSimpleStyle(thing)
            if (simpleTitle != null) {
                return 12 + estimateWidgetTextHeightDp(simpleTitle, textWidthDp, 16f, 2) + 12
            }
        }

        var height = 0
        val title = thing.getTitleToDisplay()!!
        if (title.isNotEmpty()) {
            height += 12 + 22
        }

        val content = thing.content!!
        if (content.isNotEmpty()) {
            height += if (CheckListHelper.isCheckListStr(content)) {
                12 + estimateWidgetChecklistHeightDp(content)
            } else {
                val textSize = if (content.length <= 60) {
                    -0.14f * content.length + 24.14f
                } else {
                    16f
                }
                12 + estimateWidgetTextHeightDp(content, textWidthDp, textSize, 9)
            }
        }

        val audioCount = AttachmentHelper.getAudioAttachmentCountStr(thing.attachment!!, context)
        if (audioCount != null) {
            height += if (title.isEmpty()
                    && content.isEmpty()
                    && AttachmentHelper.isAllAudio(thing.attachment)) {
                12 + 28
            } else {
                9 + 18
            }
        }

        if (thing.state != Thing.UNDERWAY && thing.type != Thing.GOAL) {
            height += 12 + 34
        }
        if (ReminderDAO.getInstance(context)!!.getReminderById(thing.id) != null) {
            height += 12 + 34
        }
        val habit = HabitDAO.getInstance(context)!!.getHabitById(thing.id)
        if (habit != null) {
            height += if (style == ThingWidgetInfo.STYLE_SIMPLE
                    || thing.state != Thing.UNDERWAY
                    || habit.isPaused()) {
                70
            } else {
                132
            }
        }

        return max(1, height + 12)
    }

    private fun estimateWidgetChecklistHeightDp(checklistStr: String): Int {
        val items = CheckListHelper.toCheckListItems(checklistStr, false)
        items.remove("2")
        items.remove("3")
        items.remove("4")
        val visibleCount = min(8, items.size)
        val moreCount = if (items.size > 8) 1 else 0
        return (visibleCount + moreCount) * 24
    }

    private fun estimateWidgetTextHeightDp(
            text: String, widthDp: Int, textSizeSp: Float, maxLines: Int): Int {
        val charsPerLine = max(6, (widthDp / 8f).toInt())
        val paragraphLines = text.split('\n').sumOf { paragraph ->
            max(1, ceil(paragraph.length.toDouble() / charsPerLine).toInt())
        }
        val lines = min(maxLines, paragraphLines)
        return max(18, ceil(lines * textSizeSp * 1.25).toInt())
    }

    private fun getWidgetSideMediaPercentWidth(
            context: Context, thing: Thing, contentWidth: Int): Int {
        val sidePercent = normalizeWidgetSideMediaWidth(context, thing)
        return max(1, contentWidth * sidePercent / 100)
    }

    private fun getWidgetSideMediaMinWidth(context: Context, contentWidth: Int): Int {
        val minPercent = context.resources.getInteger(
                R.integer.thing_card_side_media_width_min_percent)
        return max(1, contentWidth * minPercent / 100)
    }

    private fun getWidgetSideMediaMaxWidth(context: Context, contentWidth: Int): Int {
        val maxPercent = context.resources.getInteger(
                R.integer.thing_card_side_media_width_max_percent)
        return max(1, contentWidth * maxPercent / 100)
    }

    private fun clampWidgetSideMediaWidth(context: Context, contentWidth: Int, width: Int): Int {
        return clampWidgetSideMediaWidthWithReason(context, contentWidth, width).width
    }

    private fun clampWidgetSideMediaWidthWithReason(
            context: Context, contentWidth: Int, width: Int): WidgetSideMediaWidthClamp {
        val minWidth = getWidgetSideMediaMinWidth(context, contentWidth)
        val maxWidth = getWidgetSideMediaMaxWidth(context, contentWidth)
        val clampedWidth = min(maxWidth, max(minWidth, width))
        val reason = when {
            width < minWidth -> "side-media-min-width:$minWidth"
            width > maxWidth -> "side-media-max-width:$maxWidth"
            else -> null
        }
        return WidgetSideMediaWidthClamp(clampedWidth, reason)
    }

    private fun logWidgetSideMediaProjectionBoundary(
            context: Context, thing: Thing, contentWidth: Int,
            target: WidgetMediaSlotTarget, targetRatio: Double) {
        val desiredWidth = (target.height * targetRatio).roundToInt()
        val minWidth = getWidgetSideMediaMinWidth(context, contentWidth)
        val maxWidth = getWidgetSideMediaMaxWidth(context, contentWidth)
        val reason = when {
            desiredWidth < minWidth -> "side-media-min-width:$minWidth"
            desiredWidth > maxWidth -> "side-media-max-width:$maxWidth"
            abs(target.width - desiredWidth) > WIDGET_SIDE_MEDIA_PROJECTION_TOLERANCE_PX ->
                "side-media-projection-best-effort"
            else -> null
        } ?: return
        Log.d(
                TAG,
                "Project widget side media for thing ${thing.id}: " +
                        "desiredWidth=$desiredWidth, target=${target.width}x${target.height}, " +
                        "targetRatio=$targetRatio, contentWidth=$contentWidth, reason=$reason"
        )
    }

    private fun getWidgetSideMediaTargetAspectRatio(thing: Thing): Double? {
        val source = ThingCardMediaHelper.resolveEffectiveMediaSource(thing) ?: return null
        val ratio = thing.thingCardAppearance.sources[source.typePathName]
                ?.sidePanelTargetAspectRatio()
                ?: return null
        if (ratio.isNaN() || ratio.isInfinite() || ratio <= 0.0) {
            Log.d(
                    TAG,
                    "Ignore widget side media target ratio for thing ${thing.id}: " +
                            "value=$ratio, reason=invalid"
            )
            return null
        }
        val normalizedRatio = max(0.05, min(4.0, ratio))
        if (normalizedRatio != ratio) {
            Log.d(
                    TAG,
                    "Clamp widget side media target ratio for thing ${thing.id}: " +
                            "$ratio -> $normalizedRatio, reason=remote-side-ratio-boundary"
            )
        }
        return normalizedRatio
    }

    private data class WidgetMediaSlotTarget(
            val width: Int,
            val height: Int)

    private data class WidgetSideMediaWidthClamp(
            val width: Int,
            val reason: String?)

    private data class WidgetMediaBackground(
            val bitmap: Bitmap,
            val layoutMinHeight: Int?)

    private data class WidgetMediaBackgroundTargetHeight(
            val height: Int,
            val clampReasons: List<String>)

    private data class WidgetBitmapTargetSize(
            val width: Int,
            val height: Int,
            val clampReasons: List<String> = emptyList())

    private fun getWidgetTopBottomMediaSlotMaxHeight(
            context: Context, appWidgetId: Int, clazz: Class<*>?): Int {
        if (!isSingleThingWidgetClass(clazz)) {
            return dpToPx(WIDGET_LIST_MEDIA_HARD_MAX_HEIGHT_DP)
        }

        val widgetHeightDp = getWidgetHeightBudgetDp(context, appWidgetId, clazz)
        val contentFloorDp = getSingleWidgetContentFloorDp(widgetHeightDp)
        return dpToPx(min(
                max(1, widgetHeightDp - contentFloorDp),
                WIDGET_REMOTE_BITMAP_MAX_DIMENSION_DP))
    }

    private fun getSingleWidgetContentFloorDp(widgetHeightDp: Int): Int {
        return min(widgetHeightDp - 1, max(72, min(144, widgetHeightDp / 3)))
    }

    private fun normalizeWidgetSideMediaWidth(context: Context, thing: Thing): Int {
        val minPercent = context.resources.getInteger(
                R.integer.thing_card_side_media_width_min_percent)
        val maxPercent = context.resources.getInteger(
                R.integer.thing_card_side_media_width_max_percent)
        return max(minPercent, min(maxPercent, thing.thingCardAppearance.sideMediaWidthPercent))
    }

    private fun setTitleAndPrivate(
            context: Context, remoteViews: RemoteViews, thing: Thing, @ThingWidgetInfo.Style style: Int) {
        if (style == ThingWidgetInfo.STYLE_NORMAL) {
            val title: String = thing.getTitleToDisplay()!!
            if (!title.isEmpty()) {
                remoteViews.setViewVisibility(TV_TITLE, View.VISIBLE)
                remoteViews.setTextViewText(TV_TITLE, title)
                remoteViews.setViewPadding(TV_TITLE, dp12, dp12, dp12, 0)
                remoteViews.setViewVisibility(V_PADDING_BOTTOM, View.VISIBLE)
                setSeparatorVisibilities(remoteViews, View.VISIBLE)
            } else {
                remoteViews.setViewVisibility(TV_TITLE, View.GONE)
            }
        } else { // simple style
            val title: String? = getTitleToDisplayForSimpleStyle(thing)
            if (title != null) {
                remoteViews.setViewVisibility(TV_TITLE, View.VISIBLE)
                // Phase 8: simple-style title uses a muted tertiary tier.
                val light: Boolean = isThingBackgroundLight(thing)
                remoteViews.setTextColor(TV_TITLE, ContextCompat.getColor(
                        context, if (light) R.color.black_66p else R.color.white_66p))
                remoteViews.setTextViewText(TV_TITLE, title)
                remoteViews.setViewPadding(TV_TITLE, dp12, dp12, dp12, 0)
                remoteViews.setViewVisibility(V_PADDING_BOTTOM, View.VISIBLE)
                setSeparatorVisibilities(remoteViews, View.VISIBLE)
            } else {
                remoteViews.setViewVisibility(TV_TITLE, View.GONE)
            }
        }

        if (!thing.isPrivate() || style == ThingWidgetInfo.STYLE_SIMPLE) {
            remoteViews.setViewVisibility(R.id.view_private_helper_1, View.GONE)
            remoteViews.setViewVisibility(IV_PRIVATE_THING, View.GONE)
            remoteViews.setViewVisibility(R.id.view_private_helper_2, View.GONE)
        } else {
            remoteViews.setViewVisibility(R.id.view_private_helper_1, View.VISIBLE)
            remoteViews.setViewVisibility(IV_PRIVATE_THING, View.VISIBLE)
            setAdaptiveIconColor(remoteViews, IV_PRIVATE_THING, thing)
            remoteViews.setViewVisibility(R.id.view_private_helper_2, View.VISIBLE)
        }
    }

    private fun getTitleToDisplayForSimpleStyle(thing: Thing): String? {
        val title: String = thing.getTitleToDisplay()!!
        if (!title.isEmpty()) {
            return title
        }
        var content: String = thing.content!!
        if (!content.isEmpty()) {
            if (CheckListHelper.isCheckListStr(content)) {
                content = CheckListHelper.toContentStr(content, "X ", "√ ")
                content = content.replace("\n".toRegex(), "\n  ")
            }
            return content
        }
        // both title and content are empty, so there should be attachments
        return null
    }

    private fun setContent(
            context: Context, remoteViews: RemoteViews, thing: Thing, appWidgetId: Int, clazz: Class<*>?) {
        val content: String = thing.content!!
        if (content.isEmpty() || thing.isPrivate()) {
            remoteViews.setViewVisibility(LV_CHECKLIST,        View.GONE)
            remoteViews.setViewVisibility(LL_CHECK_LIST_ITEMS, View.GONE)
            remoteViews.setViewVisibility(TV_CONTENT,          View.GONE)
            return
        }

        if (!CheckListHelper.isCheckListStr(content)) {
            remoteViews.setViewVisibility(LV_CHECKLIST,        View.GONE)
            remoteViews.setViewVisibility(LL_CHECK_LIST_ITEMS, View.GONE)
            remoteViews.setViewVisibility(TV_CONTENT,          View.VISIBLE)
            remoteViews.setViewPadding(TV_CONTENT, dp12, dp12, dp12, 0)
            remoteViews.setTextViewText(TV_CONTENT, content)
            val length: Int = content.length
            if (length <= 60) {
                remoteViews.setTextViewTextSize(TV_CONTENT, TypedValue.COMPLEX_UNIT_SP,
                        -0.14f * length + 24.14f)
            } else {
                remoteViews.setTextViewTextSize(TV_CONTENT, TypedValue.COMPLEX_UNIT_SP, 16f)
            }
        } else {
            if (isSingleThingWidgetClass(clazz)) {
                setChecklistForSingleThing(context, remoteViews, thing, appWidgetId, clazz)
            } else {
                setChecklistForThingsListItem(context, remoteViews, content, thing)
            }
        }

        remoteViews.setViewVisibility(V_PADDING_BOTTOM, View.VISIBLE)
        setSeparatorVisibilities(remoteViews, View.VISIBLE)
    }

    private fun setChecklistForSingleThing(
            context: Context, remoteViews: RemoteViews, thing: Thing, appWidgetId: Int, clazz: Class<*>?) {
        remoteViews.setViewVisibility(LV_CHECKLIST,        View.VISIBLE)
        remoteViews.setViewVisibility(LL_CHECK_LIST_ITEMS, View.GONE)
        remoteViews.setViewVisibility(TV_CONTENT,          View.GONE)

        remoteViews.setViewPadding(LV_CHECKLIST, dp12, dp12, dp12, 0)

        var intent = Intent(context, ChecklistWidgetService::class.java)
        intent.putExtra(Def.Communication.KEY_WIDGET_ID, appWidgetId)
        intent.putExtra(Def.Communication.KEY_ID, thing.id)
        // Very important! without this line, two different checklist items may have same content
        intent.setData(Uri.parse(intent.toUri(Intent.URI_INTENT_SCHEME)))
        remoteViews.setRemoteAdapter(LV_CHECKLIST, intent)

        intent = Intent(context, clazz)
        intent.setAction(Def.Communication.BROADCAST_ACTION_UPDATE_CHECKLIST)
        intent.setData(Uri.parse(intent.toUri(Intent.URI_INTENT_SCHEME)))
        val pendingIntent: PendingIntent = PendingIntent.getBroadcast(context, appWidgetId, intent,
                COLLECTION_TEMPLATE_PENDING_INTENT_FLAGS)
        remoteViews.setPendingIntentTemplate(R.id.lv_thing_check_list, pendingIntent)
    }

    private fun setChecklistForThingsListItem(
            context: Context, remoteViews: RemoteViews, checklistStr: String, thing: Thing) {
        remoteViews.setViewVisibility(LL_CHECK_LIST_ITEMS, View.VISIBLE)
        remoteViews.setViewVisibility(LV_CHECKLIST,        View.GONE)
        remoteViews.setViewVisibility(TV_CONTENT,          View.GONE)

        remoteViews.removeAllViews(LL_CHECK_LIST_ITEMS)

        remoteViews.setViewPadding(LL_CHECK_LIST_ITEMS, dp12, dp12, dp12, 0)

        var items: MutableList<String?> = CheckListHelper.toCheckListItems(checklistStr, false)
        items.remove("2")
        items.remove("3")
        items.remove("4")
        val size: Int = items.size

        if (size > 8) {
            items = items.subList(0, 8)
        }

        for (item in items) {
            val rvItem: RemoteViews = createRemoteViewsForChecklistItem(context, item, size, false, thing)
            remoteViews.addView(LL_CHECK_LIST_ITEMS, rvItem)
        }

        if (size > 8) {
            val rvItem = RemoteViews(context.packageName, R.layout.check_list_tv_app_widget)
            rvItem.setViewVisibility(IV_STATE_CHECK_LIST, View.GONE)
            rvItem.setTextViewText(TV_CONTENT_CHECK_LIST, "...")
            rvItem.setTextColor(TV_CONTENT_CHECK_LIST,
                    checklistItemTextColor(context, thing, false))
            rvItem.setContentDescription(TV_CONTENT_CHECK_LIST,
                    context.getString(R.string.cd_checklist_more_items))
            rvItem.setTextViewTextSize(TV_CONTENT_CHECK_LIST, TypedValue.COMPLEX_UNIT_SP, 18f)
            rvItem.setViewPadding(TV_CONTENT_CHECK_LIST, 0, (-4 * screenDensity).toInt(), 0, 0)
            remoteViews.addView(LL_CHECK_LIST_ITEMS, rvItem)
        }
    }

    private fun setAudioAttachment(context: Context, remoteViews: RemoteViews, thing: Thing) {
        val attachment: String = thing.attachment!!
        val str: String? = AttachmentHelper.getAudioAttachmentCountStr(attachment, context)
        if (str == null || thing.isPrivate()) {
            remoteViews.setViewVisibility(LL_AUDIO_ATTACHMENT, View.GONE)
            remoteViews.setViewVisibility(LL_AUDIO_ATTACHMENT_LARGE, View.GONE)
            return
        }

        if (thing.getTitleToDisplay()!!.isEmpty()
                && thing.content!!.isEmpty()
                && AttachmentHelper.isAllAudio(thing.attachment)) {
            remoteViews.setViewVisibility(LL_AUDIO_ATTACHMENT, View.GONE)
            remoteViews.setViewVisibility(LL_AUDIO_ATTACHMENT_LARGE, View.VISIBLE)
            remoteViews.setViewPadding(LL_AUDIO_ATTACHMENT_LARGE, dp12, dp12, dp12, 0)
            remoteViews.setImageViewResource(IV_AUDIO_COUNT_LARGE, R.drawable.card_audio_attachment)
            setAdaptiveIconColor(remoteViews, IV_AUDIO_COUNT_LARGE, thing)
            remoteViews.setTextViewText(TV_AUDIO_COUNT_LARGE, str)
        } else {
            remoteViews.setViewVisibility(LL_AUDIO_ATTACHMENT_LARGE, View.GONE)
            remoteViews.setViewVisibility(LL_AUDIO_ATTACHMENT, View.VISIBLE)
            remoteViews.setViewPadding(LL_AUDIO_ATTACHMENT, dp12, (screenDensity * 9).toInt(), dp12, 0)
            remoteViews.setImageViewResource(IV_AUDIO_COUNT, R.drawable.card_audio_attachment)
            setAdaptiveIconColor(remoteViews, IV_AUDIO_COUNT, thing)
            remoteViews.setTextViewText(TV_AUDIO_COUNT, str)
        }

        remoteViews.setViewVisibility(V_PADDING_BOTTOM, View.VISIBLE)
        setSeparatorVisibilities(remoteViews, View.VISIBLE)
    }

    private fun setState(context: Context, remoteViews: RemoteViews, thing: Thing) {
        @Thing.Type  val type: Int  = thing.type
        @Thing.State val state: Int = thing.state
        if (thing.isPrivate() || state == Thing.UNDERWAY || type == Thing.GOAL) {
            remoteViews.setViewVisibility(RL_THING_STATE, View.GONE)
            return
        }

        remoteViews.setViewVisibility(RL_THING_STATE, View.VISIBLE)
        remoteViews.setTextViewText(TV_THING_STATE, Thing.getStateStr(state, context))
        if (state == Thing.FINISHED) {
            remoteViews.setImageViewResource(IV_THING_STATE, R.drawable.ic_finished_widget)
            setAdaptiveIconColor(remoteViews, IV_THING_STATE, thing)
            remoteViews.setContentDescription(IV_THING_STATE, context.getString(R.string.finished))
            remoteViews.setViewPadding(IV_THING_STATE, 0, (screenDensity * 2.5).toInt(),
                    (screenDensity * 12).toInt(), 0)
        } else if (state == Thing.DELETED) {
            remoteViews.setImageViewResource(IV_THING_STATE, R.drawable.ic_deleted_widget)
            setAdaptiveIconColor(remoteViews, IV_THING_STATE, thing)
            remoteViews.setContentDescription(IV_THING_STATE, context.getString(R.string.deleted))
            remoteViews.setViewPadding(IV_THING_STATE, 0, (screenDensity * 1.5).toInt(),
                    (screenDensity * 12).toInt(), 0)
        }
        remoteViews.setViewVisibility(V_PADDING_BOTTOM, View.VISIBLE)
    }

    private fun setAction(remoteViews: RemoteViews) {
        remoteViews.setViewVisibility(LL_THING_ACTION, View.GONE)
    }

    private fun setReminder(context: Context, remoteViews: RemoteViews, thing: Thing) {
        @Thing.Type val thingType: Int = thing.type
        val reminder: Reminder? = ReminderDAO.getInstance(context)!!.getReminderById(thing.id)
        if (reminder == null || thing.isPrivate()) {
            remoteViews.setViewVisibility(RL_REMINDER, View.GONE)
            return
        }

        remoteViews.setViewVisibility(RL_REMINDER, View.VISIBLE)
        remoteViews.setViewPadding(RL_REMINDER, dp12, dp12, dp12, 0)

        if (thingType == Thing.REMINDER) {
            remoteViews.setViewPadding(IV_REMINDER, 0, (screenDensity * 2).toInt(), 0, 0)
            remoteViews.setImageViewResource(IV_REMINDER, R.drawable.card_reminder)
            setAdaptiveIconColor(remoteViews, IV_REMINDER, thing)
            remoteViews.setContentDescription(IV_REMINDER, context.getString(R.string.reminder))
            remoteViews.setTextViewTextSize(TV_REMINDER_TIME, TypedValue.COMPLEX_UNIT_SP, 12f)

            remoteViews.setTextViewText(TV_REMINDER_TIME,
                    DateTimeUtil.getDateTimeStrReminder(context, thing, reminder))
        } else {
            remoteViews.setViewPadding(IV_REMINDER, 0, (screenDensity * 1.6).toInt(), 0, 0)
            remoteViews.setImageViewResource(IV_REMINDER, R.drawable.card_goal)
            setAdaptiveIconColor(remoteViews, IV_REMINDER, thing)
            remoteViews.setContentDescription(IV_REMINDER, context.getString(R.string.goal))
            remoteViews.setTextViewTextSize(TV_REMINDER_TIME, TypedValue.COMPLEX_UNIT_SP, 16f)

            remoteViews.setTextViewText(TV_REMINDER_TIME,
                    DateTimeUtil.getDateTimeStrGoal(context, thing, reminder))
        }

        remoteViews.setViewVisibility(V_PADDING_BOTTOM, View.VISIBLE)
    }

    private fun setHabit(
            context: Context, remoteViews: RemoteViews, thing: Thing,
            @ThingWidgetInfo.Style style: Int) {
        val habit: Habit? = HabitDAO.getInstance(context)!!.getHabitById(thing.id)
        if (habit == null || thing.isPrivate())  {
            remoteViews.setViewVisibility(RL_HABIT, View.GONE)
            return
        }

        remoteViews.setViewVisibility(RL_HABIT, View.VISIBLE)
        remoteViews.setViewPadding(RL_HABIT, dp12, dp12, dp12, 0)

        var summary: String = habit.getSummary(context)!!
        if (thing.state == Thing.UNDERWAY && habit.isPaused()) {
            summary += ", " + habit.getStateDescription(context)
        }
        remoteViews.setTextViewText(TV_HABIT_SUMMARY, summary)
        setAdaptiveIconColor(remoteViews, IV_HABIT, thing)

        if (thing.state == Thing.UNDERWAY && !habit.isPaused()) {
            remoteViews.setViewVisibility(TV_HABIT_NEXT_REMINDER, View.VISIBLE)
            val next: String = context.getString(R.string.habit_next_reminder)
            remoteViews.setTextViewText(TV_HABIT_NEXT_REMINDER,
                    next + " " + habit.getNextReminderDescription(context))

            if (style == ThingWidgetInfo.STYLE_SIMPLE) {
                remoteViews.setViewVisibility(V_HABIT_SEPARATOR_2,      View.GONE)
                remoteViews.setViewVisibility(TV_HABIT_LAST_FIVE,       View.GONE)
                remoteViews.setViewVisibility(LL_HABIT_RECORD,          View.GONE)
                remoteViews.setViewVisibility(TV_HABIT_FINISHED_THIS_T, View.GONE)
                // only show summary and next reminder time
                return
            }

            remoteViews.setViewVisibility(V_HABIT_SEPARATOR_2,      View.VISIBLE)
            remoteViews.setViewVisibility(TV_HABIT_LAST_FIVE,       View.VISIBLE)
            remoteViews.setViewVisibility(LL_HABIT_RECORD,          View.VISIBLE)
            remoteViews.setViewVisibility(TV_HABIT_FINISHED_THIS_T, View.VISIBLE)

            val record: String = habit.record!!
            val lastFive: StringBuilder
            val len: Int = record.length
            if (len >= 5) {
                lastFive = StringBuilder(record.substring(len - 5, len))
            } else {
                lastFive = StringBuilder(record)
                for (i in 0 until 5 - len) {
                    lastFive.append("?")
                }
            }
            setHabitLastFive(remoteViews, context, thing, lastFive.toString())

            remoteViews.setTextViewText(TV_HABIT_FINISHED_THIS_T,
                    habit.getFinishedTimesThisTStr(context))
        } else {
            remoteViews.setViewVisibility(TV_HABIT_NEXT_REMINDER,   View.GONE)
            remoteViews.setViewVisibility(V_HABIT_SEPARATOR_2,      View.GONE)
            remoteViews.setViewVisibility(TV_HABIT_LAST_FIVE,       View.GONE)
            remoteViews.setViewVisibility(LL_HABIT_RECORD,          View.GONE)
            remoteViews.setViewVisibility(TV_HABIT_FINISHED_THIS_T, View.GONE)
        }

        remoteViews.setViewVisibility(V_PADDING_BOTTOM, View.VISIBLE)
    }

    private fun setHabitLastFive(remoteViews: RemoteViews, context: Context, thing: Thing, lastFive: String) {
        val ids: IntArray = intArrayOf(
                R.id.iv_thing_habit_record_1,
                R.id.iv_thing_habit_record_2,
                R.id.iv_thing_habit_record_3,
                R.id.iv_thing_habit_record_4,
                R.id.iv_thing_habit_record_5
        )
        val states: CharArray = lastFive.toCharArray()
        for (i in 0 until states.size) {
            if (states[i] == '0') {
                remoteViews.setImageViewResource(ids[i], R.drawable.card_habit_unfinished)
                setAdaptiveIconColor(remoteViews, ids[i], thing)
                remoteViews.setContentDescription(ids[i],
                        context.getString(R.string.cd_habit_unfinished))
            } else if (states[i] == '1') {
                remoteViews.setImageViewResource(ids[i], R.drawable.card_habit_finished)
                setAdaptiveIconColor(remoteViews, ids[i], thing)
                remoteViews.setContentDescription(ids[i],
                        context.getString(R.string.cd_habit_finished))
            } else {
                remoteViews.setImageViewResource(ids[i], R.drawable.card_habit_unknown)
                setAdaptiveIconColor(remoteViews, ids[i], thing)
                remoteViews.setContentDescription(ids[i],
                        context.getString(R.string.cd_habit_unknown))
            }
        }
    }

    private fun setDoing(
            remoteViews: RemoteViews, thing: Thing) {
        if (App.getDoingThingId() == thing.id) {
            remoteViews.setViewVisibility(FL_DOING, View.VISIBLE)
        } else {
            remoteViews.setViewVisibility(FL_DOING, View.GONE)
        }
    }

}
