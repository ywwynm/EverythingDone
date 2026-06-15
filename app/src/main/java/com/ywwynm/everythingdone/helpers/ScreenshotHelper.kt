@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.ywwynm.everythingdone.helpers

import android.content.Context
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.AsyncTask
import androidx.core.content.FileProvider
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.adapters.AudioAttachmentAdapter
import com.ywwynm.everythingdone.adapters.CheckListAdapter
import com.ywwynm.everythingdone.adapters.ImageAttachmentAdapter
import com.ywwynm.everythingdone.database.HabitDAO
import com.ywwynm.everythingdone.database.ReminderDAO
import com.ywwynm.everythingdone.fragments.LoadingDialogFragment
import com.ywwynm.everythingdone.model.Habit
import com.ywwynm.everythingdone.model.Reminder
import com.ywwynm.everythingdone.model.ReminderHabitParams
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.utils.BitmapUtil
import com.ywwynm.everythingdone.utils.DateTimeUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.FileUtil
import com.ywwynm.everythingdone.utils.StringUtil

import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import androidx.core.graphics.createBitmap

/**
 * Created by qiizhang on 2016/9/5.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Helper class to get screenshot, especially when there is a scrollable view.
 */
object ScreenshotHelper {

    const val TAG: String = "ScreenshotHelper"

    private var sScreenshotFiles: MutableList<File?>? = null

    interface ScreenshotCallback {
        fun onTaskDone(file: File?)
    }

    open class ShareCallback(context: Context?, ldf: LoadingDialogFragment?, shareTitle: String?) : ScreenshotCallback {

        private var mWrContext: WeakReference<Context?>? = WeakReference(context)
        private var mWrLdf: WeakReference<LoadingDialogFragment?>? = WeakReference(ldf)
        private var mShareTitle: String? = shareTitle

        override fun onTaskDone(file: File?) {
            if (mWrLdf != null) {
                val ldf: LoadingDialogFragment? = mWrLdf!!.get()
                ldf?.dismiss()
            }

            if (mWrContext == null) return
            val context: Context = mWrContext!!.get() ?: return
            val screenshotFile = file ?: return

            val intent = Intent(Intent.ACTION_SEND)
            val mimeType = "image/jpeg"
            val uri: Uri = FileProvider.getUriForFile(
                    context, "com.ywwynm.everythingdone", screenshotFile)
            intent.setDataAndType(uri, mimeType)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = ClipData.newUri(
                    context.contentResolver, mShareTitle ?: screenshotFile.name, uri)
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            mShareTitle?.let { intent.putExtra(Intent.EXTRA_TITLE, it) }
            val chooserIntent = Intent.createChooser(intent, mShareTitle)
            chooserIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(chooserIntent)
        }
    }

    @JvmStatic
    fun startScreenshot(view: View?, callback: ScreenshotCallback?) {
        startScreenshot(view, 0, callback)
    }

    @JvmStatic
    fun startScreenshot(view: View?, color: Int, callback: ScreenshotCallback?) {
        if (sScreenshotFiles == null) {
            sScreenshotFiles = ArrayList()
        }
        ScreenshotTask(callback).execute(view, color)
    }

    @JvmStatic
    fun clearGeneratedScreenshots() {
        if (sScreenshotFiles != null) {
            for (generatedFile in sScreenshotFiles!!) {
                FileUtil.deleteFile(generatedFile)
            }
            sScreenshotFiles!!.clear()
        }
    }

    private fun getScreenshot(vararg params: Any?): File? {
        if (params.size == 1) {
            return null
        }
        val view: View = params[0] as? View? ?: return null
        val color: Int = params[1] as Int
        return getScreenshot(view, color)
    }

    private fun getScreenshot(view: View, color: Int): File? {
        if (view is ScrollView) {
            return getScreenShotForScrollViews(view, color)
        } else if (view is NestedScrollView) {
            return getScreenShotForScrollViews(view, color)
        }
        return null
    }

    private fun getScreenShotForScrollViews(scrollView: FrameLayout, color: Int): File? {
        val count: Int = scrollView.childCount
        var height = 0
        for (i in 0 until count) {
            val view: View = scrollView.getChildAt(i)
            height += view.height
        }

        // get screen shot bitmap
        val bitmap: Bitmap = createBitmap(scrollView.width, height)
        val canvas = Canvas(bitmap)
        canvas.drawColor(color)
        scrollView.draw(canvas)

        var name = "screenshot_"
        name += SimpleDateFormat("yyyyMMddHHmmss").format(Date())
        name += ".jpeg"
        return BitmapUtil.saveBitmapToStorage(FileUtil.getTempPath(scrollView.context), name, bitmap)
    }

    private class ScreenshotTask(callback: ScreenshotCallback?) : AsyncTask<Any?, Any?, File?>() {

        private var mCallback: ScreenshotCallback? = callback

        override fun doInBackground(vararg params: Any?): File? {
            // sleep this thread for 1.6s to make sure that possibly-existed scrollbar have been
            // drawn completely. As a result, we can get screenshot of view by view.draw(Canvas).
            // Otherwise, we may get Exception and crash because we draw something in worker thread.
            try {
                Thread.sleep(1600)
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
            return getScreenshot(*params)
        }

        override fun onPostExecute(file: File?) {
            sScreenshotFiles!!.add(file)
            if (mCallback != null) {
                mCallback!!.onTaskDone(file)
            }
        }
    }


    // ---------- helper constants/methods/classes for screenshot in DetailActivity ---------- //

    private const val UPDATE_TITLE: Int            = 0
    private const val UPDATE_TITLE_PADDING: Int    = 1
    private const val UPDATE_CONTENT: Int          = 2
    private const val UPDATE_CONTENT_MARGIN: Int   = 3
    private const val UPDATE_CHECKLIST: Int        = 4
    private const val UPDATE_CHECKLIST_MARGIN: Int = 5
    private const val UPDATE_IMAGE: Int            = 6
    private const val UPDATE_AUDIO: Int            = 7
    private const val UPDATE_AUDIO_MARGIN: Int     = 8

    private val density: Float = DisplayUtil.getScreenDensity(App.getApp())

    /**
     * 1. For Reminder:
     * same as in thing card
     *
     * 2. For Habit:
     *
     * 3. For Goal:
     */
    @JvmStatic
    fun showTypeInfo(
            layout: View?, thingId: Long, @Thing.Type typeBefore: Int, @Thing.Type typeAfter: Int, @Thing.State thingState: Int,
            rhParams: ReminderHabitParams?) {
        val ivIcon: ImageView = layout!!.findViewById(R.id.iv_icon_type_info)
        val context: Context = ivIcon.context
        @DrawableRes val iconRes: Int = Thing.getTypeIconWhiteLarge(typeAfter)
        val d1: Drawable = ContextCompat.getDrawable(ivIcon.context, iconRes)!!
        val d2: Drawable = d1.mutate()
        d2.setColorFilter(ContextCompat.getColor(context, R.color.white_66p), PorterDuff.Mode.SRC_IN)
        ivIcon.setImageDrawable(d2)

        val tvInfo: TextView = layout.findViewById(R.id.tv_type_info)
        val llp: LinearLayout.LayoutParams = tvInfo.layoutParams as LinearLayout.LayoutParams
        if (Thing.isReminderType(typeAfter)) {
            var reminderInMillis: Long = rhParams!!.reminderInMillis
            if (reminderInMillis == -1L) {
                val timeAfterType: IntArray = rhParams.reminderAfterTime!!
                reminderInMillis = DateTimeUtil.getActualTimeAfterSomeTime(timeAfterType)
            }

            val info: String?
            val reminder: Reminder? = ReminderDAO.getInstance(context)!!.getReminderById(thingId)
            if (reminder == null
                    || (thingState == Thing.UNDERWAY
                        && (reminder.notifyTime != reminderInMillis
                            || typeBefore != typeAfter))) {
                info = if (typeAfter == Thing.REMINDER) {
                    DateTimeUtil.getDateTimeStrReminder(
                        context, reminderInMillis, Thing.UNDERWAY, Reminder.UNDERWAY, true)
                } else {
                    DateTimeUtil.getDateTimeStrGoal(context,
                        reminderInMillis, System.currentTimeMillis(), 0,
                        Thing.UNDERWAY, Reminder.UNDERWAY)
                }
            }  else {
                info = if (typeAfter == Thing.REMINDER) {
                    DateTimeUtil.getDateTimeStrReminder(context, thingId, true)
                } else {
                    if (thingState == Thing.UNDERWAY) {
                        DateTimeUtil.getDateTimeStrGoal(context, thingId)
                    } else { // thingState == Thing.FINISHED
                        DateTimeUtil.getShouldBeAchievedBeforeStr(
                            context, reminderInMillis, true)
                    }
                }
            }
            tvInfo.append(StringUtil.lowerFirst(info))
            if (typeAfter == Thing.REMINDER) {
                llp.topMargin = (density * 0.5).toInt()
            } else {
                llp.topMargin = (density * 1.5).toInt()
            }
        } else if (typeAfter == Thing.HABIT) {
            var info: String?
            val habit: Habit? = HabitDAO.getInstance(context)!!.getHabitById(thingId)
            val rhHabitType: Int = rhParams!!.habitType
            val rhHabitDetail: String? = rhParams.habitDetail
            if (habit == null
                    || (thingState == Thing.UNDERWAY
                        && (habit.type != rhHabitType
                            || !habit.detail.equals(rhHabitDetail)))) {
                info = DateTimeUtil.getDateTimeStrRec(
                    context, rhHabitType, rhHabitDetail)
                if (info != null && info.startsWith("at ")) {
                    info = info.substring(3, info.length)
                }
            } else {
                info = SendInfoHelper.getHabitShareInfo(context, thingId, thingState)
            }
            tvInfo.append(StringUtil.lowerFirst(info))
            llp.topMargin = (density * 0.5).toInt()
        } else { // other types
            layout.visibility = View.GONE
            return // don't show layout
        }

        tvInfo.requestLayout()
        layout.visibility = View.VISIBLE
    }

    @JvmStatic
    fun hideTypeInfo(layout: View?) {
        layout!!.visibility = View.GONE
    }

    @JvmStatic
    fun updateThingUiBeforeScreenshot(
            editable: Boolean,
            etTitle: EditText?, etContent: EditText?,
            rvChecklist: RecyclerView?, checkListAdapter: CheckListAdapter?,
            llMoveChecklist: LinearLayout?,
            rvImage: RecyclerView?, imageAdapter: ImageAttachmentAdapter?,
            rvAudio: RecyclerView?, audioAdapter: AudioAttachmentAdapter?): List<Int?> {
        val didList: MutableList<Int?> = ArrayList()
        val noTitle: Boolean = etTitle!!.getText().toString().isEmpty()
        val noImage: Boolean = rvImage == null || rvImage.visibility != View.VISIBLE ||
                imageAdapter == null
        if (noTitle) {
            etTitle.visibility = View.GONE
            didList.add(UPDATE_TITLE)
        } else if (noImage) {
            val top: Float = density * 20
            etTitle.setPadding(etTitle.getPaddingLeft(), top.toInt(), etTitle.getPaddingRight(), 0)
            etTitle.requestLayout()
            didList.add(UPDATE_TITLE_PADDING)
        }

        if (etContent!!.visibility == View.VISIBLE &&
                etContent.getText().toString().isEmpty()) {
            etContent.visibility = View.GONE
            didList.add(UPDATE_CONTENT)
        } else if (!noImage) {
            val llp: LinearLayout.LayoutParams =
                    etContent.layoutParams as LinearLayout.LayoutParams
            llp.topMargin = (density * 8).toInt()
            etContent.requestLayout()
            didList.add(UPDATE_CONTENT_MARGIN)
        }

        if (editable) {
            if (rvChecklist != null && rvChecklist.visibility == View.VISIBLE
                    && checkListAdapter != null && llMoveChecklist!!.visibility == View.VISIBLE) {
                // 5 possible situations for finished/unfinished items
                val items: MutableList<String?> = checkListAdapter.getItems()!!
                val unfinishedExisted: Boolean = CheckListHelper.getLastUnfinishedItemIndex(items) != -1
                items.remove("2")
                if (!unfinishedExisted) {
                    items.remove("3")
                }
                checkListAdapter.notifyDataSetChanged()
                llMoveChecklist.visibility = View.GONE
                didList.add(UPDATE_CHECKLIST)

                if (noTitle && !noImage) {
                    val llp: LinearLayout.LayoutParams =
                            rvChecklist.layoutParams as LinearLayout.LayoutParams
                    if (!unfinishedExisted) {
                        llp.topMargin = (density * -4).toInt()
                    } else {
                        llp.topMargin = (density * 8).toInt()
                    }
                    rvChecklist.requestLayout()
                    didList.add(UPDATE_CHECKLIST_MARGIN)
                }
            }
            if (!noImage) {
                imageAdapter.setTakingScreenshot(true)
                didList.add(UPDATE_IMAGE)
            }
        }
        if (rvAudio != null && rvAudio.visibility == View.VISIBLE && audioAdapter != null) {
            audioAdapter.setTakingScreenshot(true)
            didList.add(UPDATE_AUDIO)
            val noContent: Boolean = etContent.visibility != View.VISIBLE ||
                    etContent.getText().toString().isEmpty()
            if (noTitle && noContent) {
                val llp: LinearLayout.LayoutParams = rvAudio.layoutParams as LinearLayout.LayoutParams
                if (noImage) {
                    llp.topMargin = (density * 20).toInt()
                } else {
                    llp.topMargin = (density * 8).toInt()
                }
                rvAudio.requestLayout()
                didList.add(UPDATE_AUDIO_MARGIN)
            }
        }
        return didList
    }

    @JvmStatic
    fun updateThingUiAfterScreenshot(
            didList: List<Int?>?,
            etTitle: EditText?, etContent: EditText?,
            rvChecklist: RecyclerView?, checkListAdapter: CheckListAdapter?,
            llMoveChecklist: LinearLayout?,
            imageAdapter: ImageAttachmentAdapter?,
            rvAudio: RecyclerView?, audioAdapter: AudioAttachmentAdapter?) {
        for (did in didList!!) {
            when (did) {
                UPDATE_TITLE -> etTitle!!.visibility = View.VISIBLE
                UPDATE_TITLE_PADDING -> {
                    val top: Float = density * 12
                    etTitle!!.setPadding(etTitle.getPaddingLeft(), top.toInt(), etTitle.getPaddingRight(), 0)
                    etTitle.requestLayout()
                }
                UPDATE_CONTENT -> etContent!!.visibility = View.VISIBLE
                UPDATE_CONTENT_MARGIN -> {
                    val llp: LinearLayout.LayoutParams =
                            etContent!!.layoutParams as LinearLayout.LayoutParams
                    llp.topMargin = (density * 20).toInt()
                    etContent.requestLayout()
                }
                UPDATE_CHECKLIST -> { // must be editable if go here
                    val items: MutableList<String?> = checkListAdapter!!.getItems()!!
                    val index: Int = CheckListHelper.getLastUnfinishedItemIndex(items) + 1
                    items.add(index, "2")
                    if (index + 1 >= 0 && index + 1 < items.size
                            && !items[index + 1].equals("3")) {
                        items.add(index + 1, "3")
                    }
                    checkListAdapter.notifyDataSetChanged()
                    llMoveChecklist!!.visibility = View.VISIBLE
                }
                UPDATE_CHECKLIST_MARGIN -> {
                    val llp: LinearLayout.LayoutParams =
                            rvChecklist!!.layoutParams as LinearLayout.LayoutParams
                    llp.topMargin = (density * 20).toInt()
                    rvChecklist.requestLayout()
                }
                UPDATE_IMAGE -> imageAdapter!!.setTakingScreenshot(false)
                UPDATE_AUDIO -> audioAdapter!!.setTakingScreenshot(false)
                UPDATE_AUDIO_MARGIN -> {
                    val llp: LinearLayout.LayoutParams = rvAudio!!.layoutParams as LinearLayout.LayoutParams
                    llp.topMargin = (density * 32).toInt()
                    rvAudio.requestLayout()
                }
            }
        }
    }

    // ---------- end helper things for DetailActivity ---------- //

}
