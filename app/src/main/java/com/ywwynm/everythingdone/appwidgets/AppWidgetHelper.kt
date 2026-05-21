@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.appwidgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews

import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.request.FutureTarget
import com.bumptech.glide.request.target.AppWidgetTarget
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.FrequentSettings
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.AuthenticationActivity
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.activities.ThingsActivity
import com.ywwynm.everythingdone.appwidgets.list.ThingsListWidget
import com.ywwynm.everythingdone.appwidgets.list.ThingsListWidgetConfiguration
import com.ywwynm.everythingdone.appwidgets.list.ThingsListWidgetService
import com.ywwynm.everythingdone.appwidgets.single.BaseThingWidget
import com.ywwynm.everythingdone.appwidgets.single.ChecklistWidgetService
import com.ywwynm.everythingdone.appwidgets.single.ThingWidgetLarge
import com.ywwynm.everythingdone.appwidgets.single.ThingWidgetMiddle
import com.ywwynm.everythingdone.appwidgets.single.ThingWidgetSmall
import com.ywwynm.everythingdone.appwidgets.single.ThingWidgetTiny
import com.ywwynm.everythingdone.database.AppWidgetDAO
import com.ywwynm.everythingdone.database.HabitDAO
import com.ywwynm.everythingdone.database.ReminderDAO
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.helpers.CheckListHelper
import com.ywwynm.everythingdone.helpers.PossibleMistakeHelper
import com.ywwynm.everythingdone.model.Habit
import com.ywwynm.everythingdone.model.Reminder
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingWidgetInfo
import com.ywwynm.everythingdone.receivers.HabitWidgetActionReceiver
import com.ywwynm.everythingdone.receivers.ReminderNotificationActionReceiver
import com.ywwynm.everythingdone.utils.DateTimeUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import kotlin.math.abs
import kotlin.math.min

/**
 * Created by ywwynm on 2016/7/27.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * helper class for app widgets, especially for showing thing
 */
object AppWidgetHelper {

    const val TAG: String = "AppWidgetHelper"

    private val screenDensity: Float = DisplayUtil.getScreenDensity(App.getApp())

    private val dp12: Int = (screenDensity * 12).toInt()

    private val ROOT_WIDGET_THING: Int         = R.id.root_widget_thing
    /** Phase 8: backing ImageView for the widget background. RemoteViews can't
     *  apply a Shader / GradientDrawable to setBackgroundColor on the root,
     *  so we render the [com.ywwynm.everythingdone.model.ThingBackground]
     *  to a bitmap and post it here via setImageViewBitmap. */
    private val IV_WIDGET_BG: Int              = R.id.iv_widget_bg

    private val IV_STICKY_ONGOING: Int         = R.id.iv_thing_sticky_ongoing
    private val IV_STICKY_ONGOING_SMALL: Int   = R.id.iv_thing_sticky_ongoing_smaller
    private val FL_DOING: Int                  = R.id.fl_thing_doing_cover

    private val IV_IMAGE_ATTACHMENT: Int       = R.id.iv_thing_image
    private val TV_IMAGE_COUNT: Int            = R.id.tv_thing_image_attachment_count

    private val TV_TITLE: Int                  = R.id.tv_thing_title
    private val IV_PRIVATE_THING: Int          = R.id.iv_private_thing

    private val TV_CONTENT: Int                = R.id.tv_thing_content

    private val LV_CHECKLIST: Int              = R.id.lv_thing_check_list
    private val LL_CHECK_LIST_ITEMS: Int       = R.id.ll_check_list_items_container
    private val LL_CHECK_LIST_ITEM_ROOT: Int   = R.id.ll_check_list_tv
    private val IV_STATE_CHECK_LIST: Int       = R.id.iv_check_list_state
    private val TV_CONTENT_CHECK_LIST: Int     = R.id.tv_check_list

    private val LL_AUDIO_ATTACHMENT: Int       = R.id.ll_thing_audio_attachment
    private val TV_AUDIO_COUNT: Int            = R.id.tv_thing_audio_attachment_count
    private val LL_AUDIO_ATTACHMENT_LARGE: Int = R.id.ll_thing_audio_attachment_large
    private val TV_AUDIO_COUNT_LARGE: Int      = R.id.tv_thing_audio_attachment_count_large

    private val RL_REMINDER: Int               = R.id.rl_thing_reminder
    private val V_REMINDER_SEPARATOR: Int      = R.id.view_reminder_separator
    private val IV_REMINDER: Int               = R.id.iv_thing_reminder
    private val TV_REMINDER_TIME: Int          = R.id.tv_thing_reminder_time

    private val RL_HABIT: Int                  = R.id.rl_thing_habit
    private val V_HABIT_SEPARATOR_1: Int       = R.id.view_habit_separator_1
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
    private val TV_THINGS_LIST_TITLE: Int      = R.id.tv_things_list_title
    private val IV_THINGS_LIST_SETTING: Int    = R.id.iv_things_list_setting
    private val IV_THINGS_LIST_CREATE: Int     = R.id.iv_things_list_create

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

    /**
     * Update things list widget whose UI components are bind with a list of things under `limit`.
     */
    @JvmStatic
    fun updateThingsListAppWidgets(context: Context?, limit: Int) {
        Log.i(TAG, "updateThingsListAppWidget(context, limit) is called, limit[$limit]")
        val appWidgetDAO: AppWidgetDAO = AppWidgetDAO.getInstance(context)!!
        val storedLimit: Int = -limit - 1
        val thingWidgetInfos: List<ThingWidgetInfo?> = appWidgetDAO.getThingWidgetInfosByThingId(storedLimit.toLong())!!
        for (thingWidgetInfo in thingWidgetInfos) {
            updateThingsListAppWidget(context, thingWidgetInfo!!.id)
        }
    }

    @JvmStatic
    fun updateThingsListAppWidgetsForType(context: Context?, @Thing.Type type: Int) {
        Log.i(TAG, "updateThingsListAppWidgetForType is called, type[$type]")
        val limits: IntArray = Thing.getLimits(type, Thing.UNDERWAY)
        for (limit in limits) {
            updateThingsListAppWidgets(context, limit)
        }
    }

    @JvmStatic
    fun updateAllThingsListAppWidgets(context: Context?) {
        Log.i(TAG, "updateAllThingsListAppWidgets is called")
        var limit: Int = Def.LimitForGettingThings.ALL_UNDERWAY
        while (limit <= Def.LimitForGettingThings.GOAL_UNDERWAY) {
            updateThingsListAppWidgets(context, limit)
            limit++
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
            else -> BaseThingWidget::class.java
        }
    }

    @JvmStatic
    @ThingWidgetInfo.Size
    fun getSizeByProviderClass(clazz: Class<*>?): Int {
        if (clazz!! == ThingWidgetTiny::class.java) {
            return ThingWidgetInfo.SIZE_TINY
        } else if (clazz == ThingWidgetSmall::class.java) {
            return ThingWidgetInfo.SIZE_SMALL
        } else if (clazz == ThingWidgetMiddle::class.java) {
            return ThingWidgetInfo.SIZE_MIDDLE
        } else if (clazz == ThingWidgetLarge::class.java) {
            return ThingWidgetInfo.SIZE_LARGE
        }
        return ThingWidgetInfo.SIZE_MIDDLE
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
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
                context, appWidgetId, contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        remoteViews.setOnClickPendingIntent(ROOT_WIDGET_THING, pendingIntent)
        remoteViews.setOnClickPendingIntent(FL_DOING, pendingIntent)
        return remoteViews
    }

    @JvmStatic
    fun createRemoteViewsForThingsList(context: Context?, limit: Int, appWidgetId: Int): RemoteViews {
        val remoteViews = RemoteViews(context!!.packageName, R.layout.app_widget_things_list)
        var headerColor: Int = ContextCompat.getColor(context, R.color.app_accent)
        remoteViews.setInt(LL_THINGS_LIST_HEADER, "setBackgroundColor", headerColor)

        val dao: AppWidgetDAO = AppWidgetDAO.getInstance(context)!!
        val info: ThingWidgetInfo? = dao.getThingWidgetInfoById(appWidgetId)
        if (info != null) {
            var alpha: Int = info.alpha
            if (alpha < 0) {
                alpha = if (alpha == ThingWidgetInfo.HEADER_ALPHA_0) {
                    0
                } else {
                    (abs(alpha) / 100f * 255).toInt()
                }
                headerColor = DisplayUtil.getTransparentColor(headerColor, alpha)
                remoteViews.setInt(LL_THINGS_LIST_HEADER, "setBackgroundColor", headerColor)
            }
        }

        remoteViews.setTextViewText(TV_THINGS_LIST_TITLE, getStringForLimit(context, limit))

        var intent = Intent(context, ThingsActivity::class.java)
        intent.putExtra(Def.Communication.KEY_LIMIT, limit)
        var pendingIntent: PendingIntent = PendingIntent.getActivity(
                context, appWidgetId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        remoteViews.setOnClickPendingIntent(LL_THINGS_LIST_HEADER, pendingIntent)

        // setting image view click event
        intent = Intent(context, ThingsListWidgetConfiguration::class.java)
        intent.putExtra(Def.Communication.KEY_WIDGET_ID, appWidgetId)
        pendingIntent = PendingIntent.getActivity(
                context, appWidgetId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        remoteViews.setOnClickPendingIntent(IV_THINGS_LIST_SETTING, pendingIntent)

        // create image view click event
        // Phase 7: prefer ThingBackground so a GRADIENT new-thing keeps its gradient.
        intent = DetailActivity.getOpenIntentForCreate(context, TAG,
                if (App.newThingBackground != null)
                        App.newThingBackground
                else com.ywwynm.everythingdone.model.ThingBackground.pure(App.newThingColor))
        intent.putExtra(Def.Communication.KEY_LIMIT, limit)
        pendingIntent = PendingIntent.getActivity(
                context, appWidgetId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        remoteViews.setOnClickPendingIntent(IV_THINGS_LIST_CREATE, pendingIntent)

        // adapter for things
        intent = Intent(context, ThingsListWidgetService::class.java)
        intent.putExtra(Def.Communication.KEY_LIMIT, limit)
        intent.putExtra(Def.Communication.KEY_WIDGET_ID, appWidgetId)
        // Very important! without this line, things list widgets of two different limits
        // may have same ListView content
        intent.setData(Uri.parse(intent.toUri(Intent.URI_INTENT_SCHEME)))
        remoteViews.setRemoteAdapter(LV_THINGS_LIST, intent)
        // don't set empty view since I want to show NOTIFY_EMPTY-type things

        // thing item click event
        intent = Intent(context, AuthenticationActivity::class.java)
        intent.setAction(Def.Communication.AUTHENTICATE_ACTION_VIEW)
        intent.putExtra(Def.Communication.KEY_TITLE, context.getString(R.string.check_private_thing))
        intent.putExtra(Def.Communication.KEY_SENDER_NAME, TAG)
        intent.putExtra(Def.Communication.KEY_DETAIL_ACTIVITY_TYPE,
                DetailActivity.UPDATE)
        intent.setData(Uri.parse(intent.toUri(Intent.URI_INTENT_SCHEME)))
        pendingIntent = PendingIntent.getActivity(context, appWidgetId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        remoteViews.setPendingIntentTemplate(LV_THINGS_LIST, pendingIntent)

        remoteViews.setScrollPosition(LV_THINGS_LIST, 0)

        return remoteViews
    }

    private fun getStringForLimit(context: Context, limit: Int): String? {
        if (limit < Def.LimitForGettingThings.ALL_UNDERWAY
                || limit > Def.LimitForGettingThings.GOAL_UNDERWAY) {
            return null
        }
        val resources: IntArray = intArrayOf(
                R.string.underway,
                R.string.note,
                R.string.reminder,
                R.string.habit,
                R.string.goal
        )
        return context.getString(resources[limit])
    }

    @JvmStatic
    fun createRemoteViewsForThingsListItem(
            context: Context?, thing: Thing?, appWidgetId: Int): RemoteViews {
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
        setAppearance(context, remoteViews, thing, appWidgetId,
                ThingsListWidget::class.java, alpha, style)
        return remoteViews
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

        rv.setViewPadding(LL_CHECK_LIST_ITEM_ROOT, (-6 * screenDensity).toInt(), 0, 0, 0)

        val state: Char = item!![0]
        val text: String = item.substring(1, item.length)
        val textColor: Int
        if (state == '0') {
            rv.setImageViewResource(IV_STATE_CHECK_LIST, R.drawable.checklist_unchecked_card)
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
            rv.setTextColor(TV_CONTENT_CHECK_LIST, textColor)
            rv.setTextViewText(TV_CONTENT_CHECK_LIST, text)
        } else if (state == '1') {
            rv.setImageViewResource(IV_STATE_CHECK_LIST, R.drawable.checklist_checked_card)
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
            rv.setTextColor(TV_CONTENT_CHECK_LIST, textColor)
            val spannable = SpannableString(text)
            spannable.setSpan(StrikethroughSpan(), 0, text.length,
                    Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
            rv.setTextViewText(TV_CONTENT_CHECK_LIST, spannable)
        }

        if (itemsSize >= 8) {
            rv.setTextViewTextSize(TV_CONTENT_CHECK_LIST, TypedValue.COMPLEX_UNIT_SP, 14f)
            rv.setViewPadding(TV_CONTENT_CHECK_LIST, 0, (screenDensity * 2).toInt(), 0, 0)
        } else {
            val textSize: Float = -4 * itemsSize / 7f + 130f / 7
            rv.setTextViewTextSize(TV_CONTENT_CHECK_LIST, TypedValue.COMPLEX_UNIT_SP, textSize)
            val mt: Float = -2 * textSize / 3 + 34f / 3
            rv.setViewPadding(TV_CONTENT_CHECK_LIST, 0, mt.toInt(), 0, 0)
        }
        return rv
    }

    /**
     * Phase 8: apply luminance-adaptive text colours to every text view on the
     * widget card.
     */
    private fun applyAdaptiveTextColors(
            context: Context, rv: RemoteViews, thing: Thing) {
        val color: Int = thing.getColor()
        val light: Boolean = com.ywwynm.everythingdone.utils.BackgroundUtil.isLight(color)
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
        val light: Boolean = com.ywwynm.everythingdone.utils.BackgroundUtil.isLight(thing.getColor())
        if (finished) {
            // 50%-alpha strike-through colour — no res entry, use literal hex.
            return if (light) 0x80000000.toInt() else 0x80FFFFFF.toInt()
        }
        return ContextCompat.getColor(context,
                if (light) R.color.black_76p else R.color.white_76p)
    }

    private fun setAppearance(
            context: Context, remoteViews: RemoteViews, thing: Thing?, appWidgetId: Int, clazz: Class<*>?,
            alpha: Int, @ThingWidgetInfo.Style style: Int) {
        var a: Int = alpha
        a = if (a == ThingWidgetInfo.HEADER_ALPHA_0) {
            0
        } else {
            abs(a)
        }
        a = (a / 100f * 255).toInt()
        // Phase 8: rasterise the (possibly gradient) ThingBackground to a
        // small bitmap and push it through RemoteViews → background ImageView.
        remoteViews.setInt(ROOT_WIDGET_THING, "setBackgroundColor",
                Color.TRANSPARENT)
        // 64×64 ≈ 16KB; saves RemoteViews bitmap budget.
        val bgBm: Bitmap? = com.ywwynm.everythingdone.utils.BackgroundUtil
                .renderBackgroundBitmap(thing!!.getBackground(), 64, 64, a)
        if (bgBm != null) {
            remoteViews.setImageViewBitmap(IV_WIDGET_BG, bgBm)
        }

        // Phase 8: adapt all text colours on the widget card to the thing's
        // luminance.
        applyAdaptiveTextColors(context, remoteViews, thing)

        setStickyOrOngoing(context, remoteViews, thing, a, clazz, style)

        setImageAttachment(context, remoteViews, thing, appWidgetId, clazz)

        setTitleAndPrivate(context, remoteViews, thing, style)

        setContent(context, remoteViews, thing, appWidgetId, clazz)

        setAudioAttachment(context, remoteViews, thing)

        setState(context, remoteViews, thing)
        setAction(context, remoteViews, thing, clazz)

        setReminder(context, remoteViews, thing)
        setHabit(context, remoteViews, thing, style)

        if (style == ThingWidgetInfo.STYLE_SIMPLE && getTitleToDisplayForSimpleStyle(thing) != null) {
            remoteViews.setViewVisibility(LV_CHECKLIST,              View.GONE)
            remoteViews.setViewVisibility(LL_CHECK_LIST_ITEMS,       View.GONE)
            remoteViews.setViewVisibility(TV_CONTENT,                View.GONE)

            remoteViews.setViewVisibility(IV_IMAGE_ATTACHMENT,       View.GONE)
            remoteViews.setViewVisibility(TV_IMAGE_COUNT,            View.GONE)
            remoteViews.setViewVisibility(LL_AUDIO_ATTACHMENT,       View.GONE)
            remoteViews.setViewVisibility(LL_AUDIO_ATTACHMENT_LARGE, View.GONE)
        }

        setDoing(remoteViews, thing)
    }

    private fun setSeparatorVisibilities(
            remoteViews: RemoteViews, visibility: Int) {
        remoteViews.setViewVisibility(V_STATE_SEPARATOR,    visibility)
        remoteViews.setViewVisibility(V_REMINDER_SEPARATOR, visibility)
        remoteViews.setViewVisibility(V_HABIT_SEPARATOR_1,  visibility)
    }

    private fun setStickyOrOngoing(context: Context, remoteViews: RemoteViews, thing: Thing,
                                   alpha: Int, clazz: Class<*>?, @ThingWidgetInfo.Style style: Int) {
        val sticky: Boolean = thing.location < 0
        val ongoing: Boolean = FrequentSettings.getLong(Def.Meta.KEY_ONGOING_THING_ID) == thing.id
        if (!sticky && !ongoing) {
            remoteViews.setViewVisibility(IV_STICKY_ONGOING, View.GONE)
            remoteViews.setViewVisibility(IV_STICKY_ONGOING_SMALL, View.GONE)
        } else {
            @DrawableRes val ivRes: Int = if (sticky) R.drawable.ic_sticky else R.drawable.ic_ongoing_notication
            val cd: String = context.getString(if (sticky) R.string.sticky_thing else R.string.ongoing_thing)
            if (clazz!! == ThingsListWidget::class.java && style == ThingWidgetInfo.STYLE_SIMPLE) {
                remoteViews.setViewVisibility(IV_STICKY_ONGOING, View.GONE)
                remoteViews.setViewVisibility(IV_STICKY_ONGOING_SMALL, View.VISIBLE)
                remoteViews.setInt(IV_STICKY_ONGOING_SMALL, "setImageAlpha", alpha)
                remoteViews.setImageViewResource(IV_STICKY_ONGOING_SMALL, ivRes)
                remoteViews.setContentDescription(IV_STICKY_ONGOING_SMALL, cd)
            } else {
                remoteViews.setViewVisibility(IV_STICKY_ONGOING, View.VISIBLE)
                remoteViews.setViewVisibility(IV_STICKY_ONGOING_SMALL, View.GONE)
                remoteViews.setInt(IV_STICKY_ONGOING, "setImageAlpha", alpha)
                remoteViews.setImageViewResource(IV_STICKY_ONGOING, ivRes)
                remoteViews.setContentDescription(IV_STICKY_ONGOING, cd)
            }
        }
    }

    private fun setImageAttachment(
            context: Context, remoteViews: RemoteViews, thing: Thing, appWidgetId: Int, clazz: Class<*>?) {
        if (thing.isPrivate()) {
            remoteViews.setViewVisibility(IV_IMAGE_ATTACHMENT, View.GONE)
            remoteViews.setViewVisibility(TV_IMAGE_COUNT,      View.GONE)
            remoteViews.setViewVisibility(V_PADDING_BOTTOM,    View.VISIBLE)
            return
        }

        val attachment: String = thing.attachment!!
        val firstImageTypePathName: String? = AttachmentHelper.getFirstImageTypePathName(attachment)
        if (firstImageTypePathName == null) {
            remoteViews.setViewVisibility(IV_IMAGE_ATTACHMENT,  View.GONE)
            remoteViews.setViewVisibility(TV_IMAGE_COUNT,       View.GONE)
            remoteViews.setViewVisibility(V_PADDING_BOTTOM,     View.VISIBLE)
            return
        }

        remoteViews.setViewVisibility(IV_IMAGE_ATTACHMENT,  View.VISIBLE)
        remoteViews.setViewVisibility(TV_IMAGE_COUNT,       View.VISIBLE)

        val pathName: String = firstImageTypePathName.substring(1, firstImageTypePathName.length)
        if (clazz!!.getSuperclass()!! == BaseThingWidget::class.java) {
            loadImageForSingleThing(context, pathName, remoteViews, appWidgetId)
        } else {
            loadImageForThingsListItem(context, pathName, remoteViews)
        }

        remoteViews.setTextViewText(TV_IMAGE_COUNT,
                AttachmentHelper.getImageAttachmentCountStr(attachment, context))

        remoteViews.setViewVisibility(V_PADDING_BOTTOM, View.GONE)
        setSeparatorVisibilities(remoteViews, View.GONE)
    }

    private fun loadImageForSingleThing(
            context: Context, pathName: String, remoteViews: RemoteViews, appWidgetId: Int) {
        val options: BitmapFactory.Options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        BitmapFactory.decodeFile(pathName, options)
        if (options.outWidth <= 0) {
            return
        }
        // Cap the bitmap dimensions — RemoteViews enforces a per-update bitmap
        // memory budget (~26MB on most devices; varies by OEM).
        val maxWidth: Int  = (screenDensity * 360).toInt()
        val reqWidth: Int  = min(options.outWidth, maxWidth)
        val reqHeight: Int = reqWidth * 3 / 4
        Glide.with(context)
                .asBitmap()
                .load(pathName)
                .override(reqWidth, reqHeight)
                .centerCrop()
                .into(AppWidgetTarget(
                        context, IV_IMAGE_ATTACHMENT, remoteViews, appWidgetId))
    }

    private fun loadImageForThingsListItem(
            context: Context, pathName: String, remoteViews: RemoteViews) {
        val width: Int  = (screenDensity * 180).toInt()
        val height: Int = width * 3 / 4
        val builder: RequestBuilder<Bitmap> =
                Glide.with(context)
                        .asBitmap()
                        .load(pathName)
                        .override(width, height)
                        .centerCrop()
        val futureTarget: FutureTarget<Bitmap> = builder.submit(width, height)
        try {
            remoteViews.setImageViewBitmap(IV_IMAGE_ATTACHMENT, futureTarget.get() as Bitmap)
        } catch (e: Exception) {
            // TODO: 2017/4/16 RemoteViews for widget update exceeds maximum bitmap memory usage
            e.printStackTrace()
            PossibleMistakeHelper.outputNewMistakeInBackground(e)
        }
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
                val light: Boolean = com.ywwynm.everythingdone.utils.BackgroundUtil
                        .isLight(thing.getColor())
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
            if (clazz!!.getSuperclass()!! == BaseThingWidget::class.java) {
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
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
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
            remoteViews.setTextViewText(TV_AUDIO_COUNT_LARGE, str)
        } else {
            remoteViews.setViewVisibility(LL_AUDIO_ATTACHMENT_LARGE, View.GONE)
            remoteViews.setViewVisibility(LL_AUDIO_ATTACHMENT, View.VISIBLE)
            remoteViews.setViewPadding(LL_AUDIO_ATTACHMENT, dp12, (screenDensity * 9).toInt(), dp12, 0)
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
            remoteViews.setContentDescription(IV_THING_STATE, context.getString(R.string.finished))
            remoteViews.setViewPadding(IV_THING_STATE, 0, (screenDensity * 2.5).toInt(),
                    (screenDensity * 12).toInt(), 0)
        } else if (state == Thing.DELETED) {
            remoteViews.setImageViewResource(IV_THING_STATE, R.drawable.ic_deleted_widget)
            remoteViews.setContentDescription(IV_THING_STATE, context.getString(R.string.deleted))
            remoteViews.setViewPadding(IV_THING_STATE, 0, (screenDensity * 1.5).toInt(),
                    (screenDensity * 12).toInt(), 0)
        }
        remoteViews.setViewVisibility(V_PADDING_BOTTOM, View.VISIBLE)
    }

    private fun setAction(context: Context, remoteViews: RemoteViews, thing: Thing, clazz: Class<*>?) {
        @Thing.Type val type: Int = thing.type
        if (type == Thing.HABIT) {
            val habit: Habit? = HabitDAO.getInstance(context)!!.getHabitById(thing.id)
            if (habit != null && habit.isPaused()) {
                remoteViews.setViewVisibility(LL_THING_ACTION, View.GONE)
                return
            }
        }

        if (thing.isPrivate() || thing.state != Thing.UNDERWAY
                || (type != Thing.REMINDER && type != Thing.GOAL && type != Thing.HABIT)
                || clazz!!.getSuperclass()!! != BaseThingWidget::class.java
        ) {
            remoteViews.setViewVisibility(LL_THING_ACTION, View.GONE)
            return
        }

        remoteViews.setViewVisibility(LL_THING_ACTION, View.VISIBLE)
        if (type == Thing.HABIT) {
            setActionForHabit(context, remoteViews, thing)
        } else {
            setActionForReminder(context, remoteViews, thing)
        }
        remoteViews.setViewVisibility(V_PADDING_BOTTOM, View.VISIBLE)
    }

    private fun setActionForReminder(context: Context, remoteViews: RemoteViews, thing: Thing) {
        remoteViews.setTextViewText(TV_THING_ACTION, context.getString(R.string.act_finish))

        val id: Long = thing.id
        val intent = Intent(context, ReminderNotificationActionReceiver::class.java)
        intent.setAction(Def.Communication.WIDGET_ACTION_FINISH)
        intent.putExtra(Def.Communication.KEY_ID, id)
        val pendingIntent: PendingIntent = PendingIntent.getBroadcast(context,
                id.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        remoteViews.setOnClickPendingIntent(TV_THING_ACTION, pendingIntent)
    }

    private fun setActionForHabit(context: Context, remoteViews: RemoteViews, thing: Thing) {
        remoteViews.setTextViewText(TV_THING_ACTION, context.getString(R.string.act_finish_this_time_habit))

        val id: Long = thing.id
        val intent = Intent(context, HabitWidgetActionReceiver::class.java)
        intent.setAction(Def.Communication.WIDGET_ACTION_FINISH)
        intent.putExtra(Def.Communication.KEY_ID, id)
        val pendingIntent: PendingIntent = PendingIntent.getBroadcast(context,
                id.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        remoteViews.setOnClickPendingIntent(TV_THING_ACTION, pendingIntent)
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
            remoteViews.setContentDescription(IV_REMINDER, context.getString(R.string.reminder))
            remoteViews.setTextViewTextSize(TV_REMINDER_TIME, TypedValue.COMPLEX_UNIT_SP, 12f)

            remoteViews.setTextViewText(TV_REMINDER_TIME,
                    DateTimeUtil.getDateTimeStrReminder(context, thing, reminder))
        } else {
            remoteViews.setViewPadding(IV_REMINDER, 0, (screenDensity * 1.6).toInt(), 0, 0)
            remoteViews.setImageViewResource(IV_REMINDER, R.drawable.card_goal)
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
            setHabitLastFive(remoteViews, context, lastFive.toString())

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

    private fun setHabitLastFive(remoteViews: RemoteViews, context: Context, lastFive: String) {
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
                remoteViews.setContentDescription(ids[i],
                        context.getString(R.string.cd_habit_unfinished))
            } else if (states[i] == '1') {
                remoteViews.setImageViewResource(ids[i], R.drawable.card_habit_finished)
                remoteViews.setContentDescription(ids[i],
                        context.getString(R.string.cd_habit_finished))
            } else {
                remoteViews.setImageViewResource(ids[i], R.drawable.card_habit_unknown)
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
