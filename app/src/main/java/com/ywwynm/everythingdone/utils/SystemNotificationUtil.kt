@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.utils

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.drawable.BitmapDrawable
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.FrequentSettings
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.AuthenticationActivity
import com.ywwynm.everythingdone.activities.DelayReminderActivity
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.activities.DoingActivity
import com.ywwynm.everythingdone.activities.SettingsActivity
import com.ywwynm.everythingdone.activities.StartDoingActivity
import com.ywwynm.everythingdone.database.HabitDAO
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.helpers.CheckListHelper
import com.ywwynm.everythingdone.model.Habit
import com.ywwynm.everythingdone.model.HabitReminder
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.permission.PermissionUtil
import com.ywwynm.everythingdone.receivers.DoingNotificationActionReceiver
import com.ywwynm.everythingdone.receivers.HabitNotificationActionReceiver
import com.ywwynm.everythingdone.receivers.ReminderNotificationActionReceiver
import com.ywwynm.everythingdone.services.DoingService

import java.io.File

/**
 * Created by ywwynm on 2016/2/1.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * notification utils
 */
object SystemNotificationUtil {

    const val TAG: String = "SystemNotificationUtil"

    /**
     * Create a [NotificationCompat.Builder] for a giving thing which shows its text(title,
     * content, type description, checklist and so on), color, attachment and defines basic content
     * pendingIntent.
     * Please notice that a private thing can also be notified.
     *
     * @param senderName caller's tag that tells DetailActivity who opened it
     * @param id the notification's id.
     * @param position the position of `thing` inside
     *                 [com.ywwynm.everythingdone.managers.ThingManager.mThings]
     * @param thing the [Thing] to be notified.
     * @param autoNotify `true` means this notification is used to show automatic reminder.
     * @return the builder object that contains enough information for a notification's UI.
     */
    @JvmStatic
    fun newGeneralNotificationBuilder(
            context: Context?, senderName: String?, id: Long, position: Int, thing: Thing?, autoNotify: Boolean): NotificationCompat.Builder? {

        val contentIntent: Intent = AuthenticationActivity.getOpenIntent(
                context, senderName, id, position,
                Def.Communication.AUTHENTICATE_ACTION_VIEW,
                context!!.getString(R.string.check_private_thing))!!
        val type: Int  = thing!!.type
        val color: Int = thing.getColor()
        val contentPendingIntent: PendingIntent = PendingIntent.getActivity(context,
                id.toInt(), contentIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        var defaults: Int = Notification.DEFAULT_LIGHTS
        if (!DeviceUtil.isScreenOn(context)) {
            defaults = defaults or Notification.DEFAULT_VIBRATE
        }
        /*
            After executed code above, on my Nexus 6P with Android Nougat, there will be short and
            weak vibration(twice) if screen is on and longer/stronger vibration(twice) if screen
            is off.
         */
        // TODO: 2016/9/30 Maybe we should provide different vibrations for different thing type. And it seems default vibration isn't very elegant on all devices.

        val soundUri: Uri = getSoundUri(type, context, autoNotify)!!
        context.grantUriPermission("com.android.systemui", soundUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION)

        val channelId: String = if (autoNotify) "auto_notify"
                else if (type == Thing.REMINDER) "reminder"
                else if (type == Thing.HABIT) "habit" else "goal"
        val builder: NotificationCompat.Builder = NotificationCompat.Builder(context, channelId)
                .setColor(color)
                .setDefaults(defaults)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setContentIntent(contentPendingIntent)
                .setSound(soundUri)
                .setSmallIcon(Thing.getTypeIconWhiteLarge(type))
                .setAutoCancel(true)

        val title: String        = thing.getTitleToDisplay()!!
        var content: String      = thing.content!!
        var attachment: String   = thing.attachment!!

        if (thing.isPrivate()) {
            content    = context.getString(R.string.notification_private_thing_content)
            attachment = ""
        }

        var contentTitle: String = title
        var contentText: String = content
        var style: Int = 0

        if (CheckListHelper.isCheckListStr(content)) {
            contentText = CheckListHelper.toContentStr(content, "X  ", "√  ")!!
        }

        if (title.isEmpty() && content.isEmpty()) {
            contentTitle = Thing.getTypeStr(type, context)!!
            if (AttachmentHelper.isValidForm(attachment)) {
                contentText = context.getString(R.string.notification_has_attachment)
            } else {
                contentText = context.getString(R.string.empty)
            }
        } else {
            if (title.isEmpty()) {
                contentTitle = Thing.getTypeStr(type, context)!!
                style = 1
            } else if (content.isEmpty()) {
                contentTitle = Thing.getTypeStr(type, context)!!
                contentText = title
            } else { // no empty here
                style = 1
            }
        }

        builder.setContentTitle(contentTitle).setContentText(contentText)
        if (style == 1) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
        }

        val firstImageUri: String? = AttachmentHelper.getFirstImageTypePathName(attachment)
        if (firstImageUri != null && PermissionUtil.hasImagePermission(context)) {
            val pathName: String = firstImageUri.substring(1, firstImageUri.length)
            val bigPicture: Bitmap?
            val display: Point = DisplayUtil.getDisplaySize(context)!!
            val width: Int = Math.min(display.x, display.y)
            val height: Int = width / 2
            if (firstImageUri[0] == '0') {
                bigPicture = BitmapUtil.decodeFileWithRequiredSize(pathName, width, height)
            } else {
                bigPicture = BitmapUtil.createCroppedBitmap(
                        AttachmentHelper.getImageFromVideo(pathName), width, height)
            }
            builder.setStyle(NotificationCompat.BigPictureStyle()
                    .bigPicture(bigPicture)
                    .setSummaryText(contentText))
        } else {
            extendWearable(builder, type, thing.getBackground(), autoNotify, context)
        }

        return builder
    }

    @JvmStatic
    fun addActionsForReminderNotification(
            builder: NotificationCompat.Builder?, context: Context?, id: Long, position: Int,
            @Thing.Type type: Int, isPrivate: Boolean, color: Int) {
        addActionsForReminderNotification(builder, context, id, position, type, isPrivate,
                com.ywwynm.everythingdone.model.ThingBackground.pure(color))
    }

    /**
     * Phase 8: ThingBackground-aware reminder-notification action builder. The
     * "Start doing" and "Delay" actions go through PendingIntent → StartDoingActivity /
     * DelayReminderActivity, and the activity reads the bg back out to drive the
     * dialog accent. Without this overload the int-only path collapsed any
     * GRADIENT thing to its representative colour on the dialog.
     */
    @JvmStatic
    fun addActionsForReminderNotification(
            builder: NotificationCompat.Builder?, context: Context?, id: Long, position: Int,
            @Thing.Type type: Int, isPrivate: Boolean,
            bg: com.ywwynm.everythingdone.model.ThingBackground?) {
        if (isPrivate) {
            val finishIntent: Intent = AuthenticationActivity.getOpenIntent(
                    context, "SystemNotificationUtil", id, position,
                    Def.Communication.AUTHENTICATE_ACTION_FINISH,
                    context!!.getString(R.string.act_finish))!!
            finishIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            builder!!.addAction(R.drawable.act_finish, context.getString(R.string.act_finish),
                    PendingIntent.getActivity(context,
                            id.toInt(), finishIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        } else {
            val finishIntent: Intent = Intent(context, ReminderNotificationActionReceiver::class.java)
            finishIntent.setAction(Def.Communication.NOTIFICATION_ACTION_FINISH)
            finishIntent.putExtra(Def.Communication.KEY_ID, id)
            finishIntent.putExtra(Def.Communication.KEY_POSITION, position)
            builder!!.addAction(R.drawable.act_finish, context!!.getString(R.string.act_finish),
                    PendingIntent.getBroadcast(context,
                            id.toInt(), finishIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }

        if (type == Thing.REMINDER) {
            if (isPrivate) {
                val startIntent: Intent = AuthenticationActivity.getOpenIntent(
                        context, "SystemNotificationUtil", id, position,
                        Def.Communication.AUTHENTICATE_ACTION_START_DOING,
                        context!!.getString(R.string.act_start_doing))!!
                startIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                builder!!.addAction(R.drawable.act_start_doing,
                        context.getString(R.string.act_start_doing),
                        PendingIntent.getActivity(context,
                                id.toInt(), startIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

                val delayIntent: Intent = AuthenticationActivity.getOpenIntent(
                        context, "SystemNotificationUtil", id, position,
                        Def.Communication.AUTHENTICATE_ACTION_DELAY,
                        context.getString(R.string.act_delay))!!
                delayIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                builder.addAction(R.drawable.act_delay,
                        context.getString(R.string.act_delay),
                        PendingIntent.getActivity(context,
                                id.toInt(), delayIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            } else {
                // Phase 8: pass the full ThingBackground so the chooser dialog
                // inside StartDoingActivity / DelayReminderActivity renders a
                // gradient title / confirm / picked row.
                val startIntent: Intent = StartDoingActivity.getOpenIntent(
                        context, id, position, bg,
                        DoingService.START_TYPE_ALARM, -1)!!
                startIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                builder!!.addAction(R.drawable.act_start_doing,
                        context!!.getString(R.string.act_start_doing),
                        PendingIntent.getActivity(context,
                                id.toInt(), startIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

                val delayIntent: Intent = DelayReminderActivity.getOpenIntent(
                        context, id, position, bg)!!
                delayIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                builder.addAction(R.drawable.act_delay,
                        context.getString(R.string.act_delay),
                        PendingIntent.getActivity(context,
                                id.toInt(), delayIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
        }
    }

    @JvmStatic
    fun addActionsForHabitNotification(
            context: Context?, builder: NotificationCompat.Builder?, hrId: Long, position: Int, hrTime: Long,
            isPrivate: Boolean, thingId: Long, color: Int) {
        addActionsForHabitNotification(context, builder, hrId, position, hrTime,
                isPrivate, thingId,
                com.ywwynm.everythingdone.model.ThingBackground.pure(color))
    }

    /** Phase 8: ThingBackground-aware habit-notification action builder.
     *  See [addActionsForReminderNotification]. */
    @JvmStatic
    fun addActionsForHabitNotification(
            context: Context?, builder: NotificationCompat.Builder?, hrId: Long, position: Int, hrTime: Long,
            isPrivate: Boolean, thingId: Long,
            bg: com.ywwynm.everythingdone.model.ThingBackground?) {
        if (isPrivate) {
            val finishIntent: Intent = AuthenticationActivity.getOpenIntent(
                    context, "SystemNotificationUtil", thingId, position,
                    Def.Communication.AUTHENTICATE_ACTION_FINISH,
                    context!!.getString(R.string.act_finish_this_time_habit))!!
            finishIntent.putExtra(Def.Communication.KEY_TIME, hrTime)
            finishIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            builder!!.addAction(R.drawable.act_finish, context.getString(R.string.act_finish_this_time_habit),
                    PendingIntent.getActivity(context,
                            hrId.toInt(), finishIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        } else {
            val finishIntent: Intent = Intent(context, HabitNotificationActionReceiver::class.java)
            finishIntent.setAction(Def.Communication.NOTIFICATION_ACTION_FINISH)
            finishIntent.putExtra(Def.Communication.KEY_ID, hrId)
            finishIntent.putExtra(Def.Communication.KEY_POSITION, position)
            finishIntent.putExtra(Def.Communication.KEY_TIME, hrTime)
            builder!!.addAction(R.drawable.act_finish, context!!.getString(R.string.act_finish_this_time_habit),
                    PendingIntent.getBroadcast(context,
                            hrId.toInt(), finishIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }

        if (isPrivate) {
            val startIntent: Intent = AuthenticationActivity.getOpenIntent(
                    context, "SystemNotificationUtil", thingId, position,
                    Def.Communication.AUTHENTICATE_ACTION_START_DOING,
                    context!!.getString(R.string.act_start_doing))!!
            startIntent.putExtra(Def.Communication.KEY_TIME, hrTime)
            startIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            builder!!.addAction(R.drawable.act_start_doing,
                    context.getString(R.string.act_start_doing),
                    PendingIntent.getActivity(context,
                            hrId.toInt(), startIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        } else {
            // Phase 8: full ThingBackground for gradient on the chooser dialog.
            val startIntent: Intent = StartDoingActivity.getOpenIntent(
                    context, thingId, position, bg,
                    DoingService.START_TYPE_ALARM, hrTime)!!
            startIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            builder!!.addAction(R.drawable.act_start_doing,
                    context!!.getString(R.string.act_start_doing),
                    PendingIntent.getActivity(context,
                            hrId.toInt(), startIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }

//            Intent getItIntent = new Intent(context, HabitNotificationActionReceiver.class);
//            getItIntent.setAction(Def.Communication.NOTIFICATION_ACTION_GET_IT);
//            getItIntent.putExtra(Def.Communication.KEY_ID, hrId);
//            getItIntent.putExtra(Def.Communication.KEY_POSITION, position);
//            builder.addAction(R.drawable.act_get_it,
//                    context.getString(R.string.act_get_it),
//                    PendingIntent.getBroadcast(context,
//                            (int) hrId, getItIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        val deleteIntent: Intent = Intent(context, HabitNotificationActionReceiver::class.java)
        deleteIntent.setAction(Def.Communication.NOTIFICATION_ACTION_CANCEL)
        deleteIntent.putExtra(Def.Communication.KEY_ID, hrId)
        builder!!.setDeleteIntent(PendingIntent.getBroadcast(
                context, hrId.toInt(), deleteIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
    }

    @JvmStatic
    fun tryToCreateQuickCreateNotification(context: Context?) {
        val sp: SharedPreferences = context!!.getSharedPreferences(
                Def.Meta.PREFERENCES_NAME, Context.MODE_PRIVATE)
        if (sp.getBoolean(Def.Meta.KEY_QUICK_CREATE, false)) {
            createQuickCreateNotification(context)
        }
    }

    @JvmStatic
    fun createQuickCreateNotification(context: Context?) {
        val nmc: NotificationManagerCompat = NotificationManagerCompat.from(context!!)
        nmc.cancel(Def.Meta.ONGOING_NOTIFICATION_ID)

        val contentIntent: Intent = DetailActivity.getOpenIntentForCreate(
                context, App::class.java.getName(),
                if (App.newThingBackground != null)
                        App.newThingBackground
                else com.ywwynm.everythingdone.model.ThingBackground.pure(App.newThingColor))!!
        val contentPendingIntent: PendingIntent = PendingIntent.getActivity(context,
                Def.Meta.ONGOING_NOTIFICATION_ID, contentIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val builder: NotificationCompat.Builder = NotificationCompat.Builder(context, "quick_create")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN) /* don't show icon in status bar */
                .setColor(App.newThingColor)
                .setContentTitle(context.getString(R.string.everythingdone))
                .setContentText(context.getString(R.string.title_create_thing))
                .setContentIntent(contentPendingIntent)
                .setSmallIcon(R.drawable.act_create_white)
        nmc.notify(Def.Meta.ONGOING_NOTIFICATION_ID, builder.build())
    }

    @JvmStatic
    fun createDoingNotification(
            context: Context?, thing: Thing?, @DoingService.State doingState: Int,
            leftTimeStr: String?, hrTime: Long, highlightStrategy: Int): Notification? {
        @Thing.Type val thingType: Int = thing!!.type
        val contentText: String = getDoingNotificationContent(context, doingState, leftTimeStr)!!
        val builder: NotificationCompat.Builder = NotificationCompat.Builder(context!!, "doing")
                .setColor(thing.getColor())
                .setSmallIcon(Thing.getTypeIconWhiteLarge(thingType))
                .setContentTitle(getDoingNotificationTitle(context, thing, doingState))
                .setContentText(contentText)
        if (highlightStrategy != 0) {
            if (highlightStrategy >= 1) {
                builder.setDefaults(Notification.DEFAULT_ALL)
            }
            if (highlightStrategy >= 2) {
                builder.setPriority(Notification.PRIORITY_MAX)
            }
        }

        val thingId: Long = thing.id
        if (doingState == DoingService.STATE_DOING) {
            val contentIntent: Intent = DoingActivity.getOpenIntent(context, true)!!
            builder.setContentIntent(PendingIntent.getActivity(
                    context, thingId.toInt(), contentIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

            val finishIntent: Intent = Intent(context, DoingNotificationActionReceiver::class.java)
            finishIntent.setAction(DoingNotificationActionReceiver.ACTION_FINISH)
            finishIntent.putExtra(Def.Communication.KEY_ID, thingId)
            finishIntent.putExtra(Def.Communication.KEY_TIME, hrTime)
            builder.addAction(R.drawable.act_finish, context.getString(R.string.act_finish),
                    PendingIntent.getBroadcast(
                            context, thingId.toInt(), finishIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

            val cancelIntent: Intent = Intent(context, DoingNotificationActionReceiver::class.java)
            cancelIntent.setAction(DoingNotificationActionReceiver.ACTION_USER_CANCEL)
            cancelIntent.putExtra(Def.Communication.KEY_ID, thingId)
            builder.addAction(R.drawable.act_cancel_white, context.getString(R.string.cancel),
                    PendingIntent.getBroadcast(
                            context, thingId.toInt(), cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            val contentIntent: Intent = Intent(context, DoingNotificationActionReceiver::class.java)
            if (doingState == DoingService.STATE_FAILED_CARELESS) {
                contentIntent.setAction(DoingNotificationActionReceiver.ACTION_STOP_SERVICE)
            } else if (doingState == DoingService.STATE_FAILED_NEXT_ALARM) {
                builder.setAutoCancel(true)
            }
            builder.setContentIntent(PendingIntent.getBroadcast(
                    context, thingId.toInt(), contentIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        return builder.build()
    }

    private fun getDoingNotificationTitle(
            context: Context?, thing: Thing, @DoingService.State doingState: Int): String? {
        val nTitle: StringBuilder = StringBuilder()
        if (doingState == DoingService.STATE_DOING) {
            nTitle.append(context!!.getString(R.string.doing_currently_doing)).append(" ")
        }
        val thingTitle: String = thing.getTitleToDisplay()!!
        if (!thingTitle.isEmpty()) {
            nTitle.append(thingTitle)
        } else {
            var thingContent: String = thing.content!!
            if (!thingContent.isEmpty()) {
                if (CheckListHelper.isCheckListStr(thingContent)) {
                    thingContent = CheckListHelper.toContentStr(thingContent, "X ", "√ ")!!
                    thingContent = thingContent.replace("\n".toRegex(), "\n  ")
                }
                nTitle.append(thingContent)
            } else {
                // there should be attachment
                val attachment: String = thing.attachment!!
                if (!attachment.isEmpty() && !"to QQ".equals(attachment)) {
                    val imgStr: String? = AttachmentHelper.getImageAttachmentCountStr(attachment, context)
                    if (imgStr != null) {
                        nTitle.append(imgStr).append(", ")
                    }
                    val audStr: String? = AttachmentHelper.getAudioAttachmentCountStr(attachment, context)
                    if (audStr != null) {
                        nTitle.append(audStr)
                    }
                }
            }
        }
        return nTitle.toString()
    }

    private fun getDoingNotificationContent(
            context: Context?, @DoingService.State doingState: Int, leftTimeStr: String?): String? {
        if (doingState == DoingService.STATE_DOING) {
            return context!!.getString(R.string.doing_left_time) + " " + leftTimeStr
        } else {
            val between: String = if (LocaleUtil.isChinese(context)) ", " else ". "
            var part1: String = ""
            if (doingState == DoingService.STATE_FAILED_CARELESS) {
                part1 = context!!.getString(R.string.doing_failed_careless)
            } else if (doingState == DoingService.STATE_FAILED_NEXT_ALARM) {
                part1 = context!!.getString(R.string.doing_failed_next_alarm)
            }
            return part1 + between + context!!.getString(R.string.doing_click_to_dismiss)
        }
    }

    @JvmStatic
    fun tryToCreateThingOngoingNotification(context: Context?) {
        val curOngoingId: Long = FrequentSettings.getLong(Def.Meta.KEY_ONGOING_THING_ID)
        if (curOngoingId != -1L) {
            val thing: Thing? = App.getThingAndPosition(context, curOngoingId, -1)!!.first
            if (thing != null) {
                createThingOngoingNotification(context, thing)
            }
        }
    }

    @JvmStatic
    fun createThingOngoingNotification(context: Context?, thing: Thing?) {
        val id: Long = thing!!.id
        val builder: NotificationCompat.Builder = newGeneralNotificationBuilder(
                context, App::class.java.getName(), id, -1, thing, false)!!
        val showOnLockscreen: Boolean = FrequentSettings.getBoolean(Def.Meta.KEY_ONGOING_LOCKSCREEN)
        builder.setPriority(if (showOnLockscreen) Notification.PRIORITY_DEFAULT else Notification.PRIORITY_MIN)
                .setSound(null)
                .setDefaults(0)
                .setOngoing(true)
                .setAutoCancel(false)

        @Thing.Type val thingType: Int = thing.type
        val isPrivate: Boolean = thing.isPrivate()
        val color: Int = thing.getColor()
        if (Thing.isReminderType(thingType) || thingType == Thing.NOTE) {
            if (isPrivate) {
                val finishIntent: Intent = AuthenticationActivity.getOpenIntent(
                        context, App::class.java.getName(), id, -1,
                        Def.Communication.AUTHENTICATE_ACTION_FINISH,
                        context!!.getString(R.string.act_finish))!!
                finishIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                builder.addAction(R.drawable.act_finish, context.getString(R.string.act_finish),
                        PendingIntent.getActivity(context,
                                id.toInt(), finishIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            } else {
                val finishIntent: Intent = Intent(context, ReminderNotificationActionReceiver::class.java)
                finishIntent.setAction(Def.Communication.NOTIFICATION_ACTION_FINISH)
                finishIntent.putExtra(Def.Communication.KEY_ID, id)
                finishIntent.putExtra(Def.Communication.KEY_POSITION, -1)
                builder.addAction(R.drawable.act_finish, context!!.getString(R.string.act_finish),
                        PendingIntent.getBroadcast(context,
                                id.toInt(), finishIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }

            if (isPrivate) {
                val startIntent: Intent = AuthenticationActivity.getOpenIntent(
                        context, App::class.java.getName(), id, -1,
                        Def.Communication.AUTHENTICATE_ACTION_START_DOING,
                        context!!.getString(R.string.act_start_doing))!!
                startIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                builder.addAction(R.drawable.act_start_doing,
                        context.getString(R.string.act_start_doing),
                        PendingIntent.getActivity(context,
                                id.toInt(), startIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            } else {
                val startIntent: Intent = StartDoingActivity.getOpenIntent(
                        context, id, -1, color,
                        DoingService.START_TYPE_ALARM, -1)!!
                startIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                builder.addAction(R.drawable.act_start_doing,
                        context!!.getString(R.string.act_start_doing),
                        PendingIntent.getActivity(context,
                                id.toInt(), startIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
        } else if (thingType == Thing.HABIT) {
            if (isPrivate) {
                val finishIntent: Intent = AuthenticationActivity.getOpenIntent(
                        context, App::class.java.getName(), id, -1,
                        Def.Communication.AUTHENTICATE_ACTION_FINISH,
                        context!!.getString(R.string.act_finish_once_habit))!!
                finishIntent.putExtra(Def.Communication.KEY_TIME, -1L)
                finishIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                builder.addAction(R.drawable.act_finish, context.getString(R.string.act_finish_once_habit),
                        PendingIntent.getActivity(context,
                                id.toInt(), finishIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            } else {
                val finishIntent: Intent = Intent(context, HabitNotificationActionReceiver::class.java)
                finishIntent.setAction(Def.Communication.NOTIFICATION_ACTION_FINISH)
                finishIntent.putExtra(Def.Communication.KEY_ID, -1) // hrId -> -1
                finishIntent.putExtra(Def.Communication.KEY_POSITION, -1)
                finishIntent.putExtra(Def.Communication.KEY_TIME, -1) // hrTime -> -1
                builder.addAction(R.drawable.act_finish, context!!.getString(R.string.act_finish_once_habit),
                        PendingIntent.getBroadcast(context,
                                id.toInt(), finishIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }

            if (isPrivate) {
                val startIntent: Intent = AuthenticationActivity.getOpenIntent(
                        context, App::class.java.getName(), id, -1,
                        Def.Communication.AUTHENTICATE_ACTION_START_DOING,
                        context!!.getString(R.string.act_start_doing))!!
                startIntent.putExtra(Def.Communication.KEY_TIME, -1L)
                startIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                builder.addAction(R.drawable.act_start_doing,
                        context.getString(R.string.act_start_doing),
                        PendingIntent.getActivity(context,
                                id.toInt(), startIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            } else {
                val startIntent: Intent = StartDoingActivity.getOpenIntent(
                        context, id, -1, color,
                        DoingService.START_TYPE_ALARM, -1)!!
                startIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                builder.addAction(R.drawable.act_start_doing,
                        context!!.getString(R.string.act_start_doing),
                        PendingIntent.getActivity(context,
                                id.toInt(), startIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
        }

        var idToNotify: Int = id.toInt()
        idToNotify *= -1
        NotificationManagerCompat.from(context!!).notify(idToNotify, builder.build())
    }

    @JvmStatic
    fun cancelThingOngoingNotification(context: Context?, id: Long) {
        var idToCancel: Int = id.toInt()
        idToCancel *= -1
        NotificationManagerCompat.from(context!!).cancel(idToCancel)
    }

    @JvmStatic
    fun cancelNotification(thingId: Long, type: Int, context: Context?) {
        val nmc: NotificationManagerCompat = NotificationManagerCompat.from(context!!)
        if (Thing.isReminderType(type)) {
            nmc.cancel(thingId.toInt())
        } else if (type == Thing.HABIT) {
            val habit: Habit = HabitDAO.getInstance(context)!!.getHabitById(thingId)!!
            val habitReminders: List<HabitReminder?> = habit.habitReminders!!
            for (habitReminder in habitReminders) {
                nmc.cancel(habitReminder!!.id.toInt())
            }
        }
    }

    private fun extendWearable(builder: NotificationCompat.Builder?, type: Int,
                               bg: com.ywwynm.everythingdone.model.ThingBackground?,
                               autoNotify: Boolean, context: Context?) {
        var bmdRes: Int = R.drawable.wear_reminder
        if (autoNotify) {
            bmdRes = R.drawable.wear_auto_notify
        } else {
            if (type == Thing.HABIT) {
                bmdRes = R.drawable.wear_habit
            } else if (type == Thing.GOAL) {
                bmdRes = R.drawable.wear_goal
            }
        }
        val bmd: BitmapDrawable = ContextCompat.getDrawable(context!!, bmdRes) as BitmapDrawable
        // Phase 8: gradient-aware layered bitmap. PURE keeps the flat-color
        // path; GRADIENT lays a GradientDrawable behind the wear icon so the
        // wearable background carries the thing's gradient.
        val bm: Bitmap = BitmapUtil.createLayeredBitmap(bmd, bg)!!
        builder!!.extend(NotificationCompat.WearableExtender().setBackground(bm))
    }

    private fun getSoundUri(type: Int, context: Context?, autoNotify: Boolean): Uri? {
        val preferences: SharedPreferences = context!!.getSharedPreferences(
                Def.Meta.PREFERENCES_NAME, Context.MODE_PRIVATE)
        val key: String
        val fs: String = SettingsActivity.FOLLOW_SYSTEM
        if (autoNotify) {
            key = Def.Meta.KEY_RINGTONE_AUTO_NOTIFY
        } else {
            if (type == Thing.REMINDER) {
                key = Def.Meta.KEY_RINGTONE_REMINDER
            } else if (type == Thing.HABIT) {
                key = Def.Meta.KEY_RINGTONE_HABIT
            } else { // type == Thing.GOAL
                key = Def.Meta.KEY_RINGTONE_GOAL
            }
        }
        val uriStr: String = preferences.getString(key, fs)!!
        val rm: RingtoneManager = RingtoneManager(context)
        rm.setType(RingtoneManager.TYPE_NOTIFICATION)
        var defaultUri: Uri? = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        if (defaultUri == null) {
            defaultUri = rm.getRingtoneUri(0)
        }
        if (uriStr.equals(fs)) {
            return defaultUri
        } else {
            var uri: Uri = Uri.parse(Uri.decode(uriStr))
            if (uri !== defaultUri && rm.getRingtonePosition(uri) == -1) { // user's ringtone
                val pathName: String? = UriPathConverter.getLocalPathName(context, uri)
                if (pathName == null || !File(pathName).exists()) {
                    preferences.edit().putString(key, fs).apply()
                    return defaultUri
                }
                uri = FileProvider.getUriForFile(
                        context, Def.Meta.APP_AUTHORITY, File(pathName))
            }
            return uri
        }
    }

}
