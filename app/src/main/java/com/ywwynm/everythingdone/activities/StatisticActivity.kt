@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.ywwynm.everythingdone.activities

import android.content.SharedPreferences
import android.database.Cursor
import android.graphics.Bitmap
import android.os.AsyncTask
import androidx.core.content.ContextCompat
import androidx.appcompat.app.ActionBar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.widget.Toolbar
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.adapters.StatisticAdapter
import com.ywwynm.everythingdone.database.HabitDAO
import com.ywwynm.everythingdone.database.ReminderDAO
import com.ywwynm.everythingdone.database.ThingDAO
import com.ywwynm.everythingdone.fragments.LoadingDialogFragment
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.helpers.CheckListHelper
import com.ywwynm.everythingdone.helpers.ScreenshotHelper
import com.ywwynm.everythingdone.model.Habit
import com.ywwynm.everythingdone.model.Reminder
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingsCounts
import com.ywwynm.everythingdone.utils.BitmapUtil
import com.ywwynm.everythingdone.utils.DateTimeUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.EdgeEffectUtil
import com.ywwynm.everythingdone.utils.LocaleUtil
import com.ywwynm.everythingdone.views.FloatingActionButton

import java.time.ZonedDateTime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

import java.io.File
import java.util.Calendar

open class StatisticActivity : EverythingDoneBaseActivity() {

    private var mApp: App? = null

    private var mPreferences: SharedPreferences? = null

    private var mThingsCounts: ThingsCounts? = null
    private var mThingDAO: ThingDAO? = null

    private var mScreenDensity: Float = 0f
    private var mHeaderHeight: Float = 0f

    private var mStatusbar: View? = null
    private var mActionbar: Toolbar? = null
    private var mTitle: TextView? = null
    private var mActionbarShadow: View? = null

    private var mIvHeader: ImageView? = null
    private var mScrollView: ScrollView? = null

    private var mFab: FloatingActionButton? = null

    private var mLdf: LoadingDialogFragment? = null

    override fun getLayoutResource(): Int = R.layout.activity_statistic

    override fun initMembers() {
        mApp = application as App
        mPreferences = getSharedPreferences(Def.Meta.PREFERENCES_NAME, MODE_PRIVATE)

        mThingsCounts = ThingsCounts.getInstance(mApp)
        mThingDAO = ThingDAO.getInstance(mApp)

        mScreenDensity = DisplayUtil.getScreenDensity(mApp)

        val screenWidth = DisplayUtil.getScreenSize(mApp)!!.x
        mHeaderHeight = screenWidth * 1080f / 1920
    }

    override fun findViews() {
        mStatusbar = f(R.id.view_status_bar)
        mActionbar = f(R.id.actionbar)
        mTitle = f(R.id.tv_title_statistic)
        mActionbarShadow = f(R.id.actionbar_shadow)

        mIvHeader = f(R.id.iv_header_statistic)
        mScrollView = f(R.id.sv_statistic)

        mFab = f(R.id.fab_share)
    }

    override fun initUI() {
        EdgeEffectUtil.forScrollView(
            mScrollView,
            ContextCompat.getColor(this, R.color.blue_grey_deep_grey)
        )
        DisplayUtil.applyBottomInsetAsScrollPadding(mScrollView)

        initHeaderUI()
        initStartFromUI()
        initFinishedCreatedUI()
        initNoteUI()
        initReminderUI()
        initHabitUI()
        initGoalUI()
    }

    private fun initHeaderUI() {
        DisplayUtil.expandLayoutToStatusBarAboveLollipop(this)
        DisplayUtil.expandStatusBarViewAboveKitkat(mStatusbar)

        val D = SettingsActivity.DEFAULT_DRAWER_HEADER
        val header: String = mPreferences!!.getString(Def.Meta.KEY_DRAWER_HEADER, D)!!
        if (D == header) {
            mIvHeader!!.setImageResource(R.drawable.drawer_header_large)
        } else {
            if (!File(header).exists()) {
                mIvHeader!!.setImageResource(R.drawable.drawer_header_large)
                mPreferences!!.edit().putString(Def.Meta.KEY_DRAWER_HEADER, D).apply()
            } else {
                val bm: Bitmap? = BitmapUtil.decodeFileWithRequiredSize(
                    header,
                    (mHeaderHeight * 16 / 9).toInt(), mHeaderHeight.toInt()
                )
                mIvHeader!!.setImageBitmap(bm)
            }
        }

        val lp = mIvHeader!!.layoutParams as LinearLayout.LayoutParams
        lp.height = mHeaderHeight.toInt()
        mIvHeader!!.requestLayout()

        val mt: Float = mHeaderHeight - mScreenDensity * 28
        val flp = mFab!!.layoutParams as FrameLayout.LayoutParams
        flp.topMargin = mt.toInt()
        mFab!!.requestLayout()
    }

    private fun initStartFromUI() {
        val metaData: SharedPreferences = getSharedPreferences(
            Def.Meta.META_DATA_NAME, MODE_PRIVATE
        )
        val time = metaData.getLong(Def.Meta.KEY_START_USING_TIME, 0)
        val dt: ZonedDateTime = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())
        val gap = DateTimeUtil.calculateTimeGap(
            time, System.currentTimeMillis(), Calendar.DATE
        ) + 1
        val sb = StringBuilder()
        sb.append(getString(R.string.statistic_start_from_part_1))
        if (LocaleUtil.isChinese(mApp)) {
            val year  = mApp!!.getString(R.string.year)
            val month = mApp!!.getString(R.string.month)
            val day   = mApp!!.getString(R.string.day)
            sb.append(dt.format(DateTimeFormatter.ofPattern(" yyyy $year M $month d $day")))
                .append(getString(R.string.statistic_start_from_part_2))
                .append(" ").append(gap).append(" ")
        } else {
            sb.append(dt.format(DateTimeFormatter.ofPattern(" MMM d, yyyy")))
                .append(getString(R.string.statistic_start_from_part_2))
            if (gap <= 1) {
                sb.append(" this day")
            } else {
                sb.append(" these ").append(gap).append(" days ")
            }
        }
        sb.append(getString(R.string.statistic_start_from_part_3))

        val tv: TextView = f(R.id.tv_start_from_statistic)!!
        tv.text = sb.toString()
    }

    private fun initFinishedCreatedUI() {
        FinishedCreatedTask().execute()
    }

    private fun initNoteUI() {
        val u = mThingsCounts!!.getCount(Thing.NOTE, Thing.UNDERWAY)
        val f = mThingsCounts!!.getCount(Thing.NOTE, Thing.FINISHED)
        val d = mThingsCounts!!.getCount(Thing.NOTE, Thing.DELETED)
        if (u != 0 || f != 0 || d != 0) {
            NoteTask().execute()
        } else {
            f<View>(R.id.tv_note_record_statistic)!!.visibility = View.GONE
            f<View>(R.id.cv_note_record_statistic)!!.visibility = View.GONE
        }
    }

    private fun initReminderUI() {
        val u = mThingsCounts!!.getCount(Thing.REMINDER, Thing.UNDERWAY)
        val f = mThingsCounts!!.getCount(Thing.REMINDER, Thing.FINISHED)
        val d = mThingsCounts!!.getCount(Thing.REMINDER, Thing.DELETED)
        if (u != 0 || f != 0 || d != 0) {
            ReminderTask().execute()
        } else {
            f<View>(R.id.tv_reminder_record_statistic)!!.visibility = View.GONE
            f<View>(R.id.cv_reminder_record_statistic)!!.visibility = View.GONE
        }
    }

    private fun initHabitUI() {
        val u = mThingsCounts!!.getCount(Thing.HABIT, Thing.UNDERWAY)
        val f = mThingsCounts!!.getCount(Thing.HABIT, Thing.FINISHED)
        val d = mThingsCounts!!.getCount(Thing.HABIT, Thing.DELETED)
        if (u != 0 || f != 0 || d != 0) {
            HabitTask().execute()
        } else {
            f<View>(R.id.tv_habit_record_statistic)!!.visibility = View.GONE
            f<View>(R.id.cv_habit_record_statistic)!!.visibility = View.GONE
        }
    }

    private fun initGoalUI() {
        val u = mThingsCounts!!.getCount(Thing.GOAL, Thing.UNDERWAY)
        val f = mThingsCounts!!.getCount(Thing.GOAL, Thing.FINISHED)
        val d = mThingsCounts!!.getCount(Thing.GOAL, Thing.DELETED)

        if (u != 0 || f != 0 || d != 0) {
            GoalTask().execute()
        } else {
            f<View>(R.id.tv_goal_record_statistic)!!.visibility = View.GONE
            f<View>(R.id.cv_goal_record_statistic)!!.visibility = View.GONE
        }
    }

    override fun setActionbar() {
        setSupportActionBar(mActionbar)
        val actionBar: ActionBar? = supportActionBar
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true)
            actionBar.title = null
        }
        mActionbar!!.setNavigationOnClickListener { finish() }
    }

    override fun setEvents() {
        mScrollView!!.viewTreeObserver.addOnScrollChangedListener {
            updateFabState()
            updateActionbarState()
        }

        mFab!!.setOnClickListener { startScreenshot() }
    }

    private fun startScreenshot() {
        if (mLdf == null) {
            mLdf = LoadingDialogFragment()
            mLdf!!.setAccentColor(ContextCompat.getColor(mApp!!, R.color.blue_grey_deep_grey))
            mLdf!!.setTitle(getString(R.string.please_wait))
            mLdf!!.setContent(getString(R.string.generating_screenshot))
        }
        mLdf!!.show(fragmentManager, LoadingDialogFragment.TAG)

        ScreenshotHelper.startScreenshot(
            mScrollView,
            ScreenshotHelper.ShareCallback(
                this, mLdf, getString(R.string.share_statistic)
            )
        )
    }

    private fun updateFabState() {
        val statusbarSize = DisplayUtil.getStatusbarHeight(this@StatisticActivity)
        val scrollY = mScrollView!!.scrollY
        val actionbarSize = mActionbar!!.height
        val fabY: Float = mHeaderHeight - statusbarSize - actionbarSize - actionbarSize
        if (scrollY >= fabY) {
            mFab!!.shrink()
        } else {
            mFab!!.spread()
        }
    }

    private fun updateActionbarState() {
        val statusbarSize = DisplayUtil.getStatusbarHeight(mApp)
        val scrollY = mScrollView!!.scrollY
        var color = ContextCompat.getColor(mApp!!, R.color.blue_grey_deep_grey)
        val actionbarSize = mActionbar!!.height
        val abSY: Float = mHeaderHeight - statusbarSize - 2 * actionbarSize
        val abTY: Float = abSY + actionbarSize
        if (scrollY <= abSY) {
            mStatusbar!!.setBackgroundColor(0)
            mActionbar!!.setBackgroundColor(0)
            mTitle!!.alpha = 0f
            mActionbarShadow!!.alpha = 0f
        } else if (scrollY >= abTY) {
            mStatusbar!!.setBackgroundColor(color)
            mActionbar!!.setBackgroundColor(color)
            mTitle!!.alpha = 1.0f
            mActionbarShadow!!.alpha = 1.0f
        } else {
            val progress: Float = (scrollY - abSY) / (abTY - abSY)
            color = DisplayUtil.getTransparentColor(color, (progress * 255).toInt())
            mStatusbar!!.setBackgroundColor(color)
            mActionbar!!.setBackgroundColor(color)
            mTitle!!.alpha = progress
            mActionbarShadow!!.alpha = 0f
        }
    }

    private fun getStrsForNoteRecord(): Array<String?> {
        val TITLE   = Def.Database.COLUMN_TITLE_THINGS
        val CONTENT = Def.Database.COLUMN_CONTENT_THINGS
        val ATTACH  = Def.Database.COLUMN_ATTACHMENT_THINGS

        val strs = arrayOfNulls<String>(4)
        val counts = IntArray(4)
        val cursor: Cursor = mThingDAO!!.getThingsCursor("type=" + Thing.NOTE)!!
        while (cursor.moveToNext()) {
            val title: String = cursor.getString(cursor.getColumnIndex(TITLE))
            val content: String = cursor.getString(cursor.getColumnIndex(CONTENT))
            counts[0] += title.length
            if (CheckListHelper.isCheckListStr(content)) {
                counts[0] += CheckListHelper.toContentStr(content, "", "")!!
                    .replace("\n".toRegex(), "").length
            } else {
                counts[0] += content.replace("\n".toRegex(), "").length
            }

            val attachment: String = cursor.getString(cursor.getColumnIndex(ATTACH))
            for (i in 1 until counts.size) {
                counts[i] += countOfKey(attachment, AttachmentHelper.SIGNAL + (i - 1))
            }
        }
        cursor.close()
        for (i in counts.indices) {
            strs[i] = counts[i].toString()
        }
        return strs
    }

    private fun getStrsForHabitRecord(): Array<String?> {
        // 习惯养成率
        // 完成/总次数
        // 完成率
        // 最长连续完成次数
        // 最长坚持周期数
        val strs = arrayOfNulls<String>(5)
        strs[0] = mThingsCounts!!.getCompletionRate(Def.LimitForGettingThings.HABIT_UNDERWAY)

        var fCount = 0 // finished times
        var tCount = 0 // total record times
        var maxCft  = 0 // longest continuous finish times
        var maxPit = 0 // longest persist in T

        val hDao: HabitDAO = HabitDAO.getInstance(mApp)!!
        val cursor: Cursor = mThingDAO!!.getThingsCursor("type=" + Thing.HABIT)!!
        while (cursor.moveToNext()) {
            val id = cursor.getLong(
                cursor.getColumnIndex(Def.Database.COLUMN_ID_THINGS)
            )
            val habit: Habit = hDao.getHabitById(id) ?: continue

            val record: String = habit.record!!
            fCount += countOfKey(record, "1")
            tCount += record.length

            val cft = longestKeySequenceSize(record, '1')
            if (cft > maxCft) {
                maxCft = cft
            }

            val pit = habit.getPersistInT()
            if (pit > maxPit) {
                maxPit = pit
            }
        }
        cursor.close()

        strs[1] = "$fCount / $tCount"
        strs[2] = LocaleUtil.getPercentStr(fCount, tCount)
        strs[3] = maxCft.toString()
        strs[4] = if (maxPit < 1) "<1" else maxPit.toString()
        return strs
    }

    private fun getStrsForReminderGoalRecord(isReminder: Boolean): Array<String?> {
        // 完成率
        // 平均提醒时长
        // 平均完成时间
        // 提前完成的比例
        val strs = arrayOfNulls<String>(4)
        if (isReminder) {
            strs[0] = mThingsCounts!!.getCompletionRate(
                Def.LimitForGettingThings.REMINDER_UNDERWAY
            )
        } else {
            strs[0] = mThingsCounts!!.getCompletionRate(
                Def.LimitForGettingThings.GOAL_UNDERWAY
            )
        }

        var tNtfMillis: Long = 0 // total notify time length in milliseconds
        var tFinTime: Long   = 0 // total finish time in milliseconds
        var inAdvcCount = 0 // count of reminders that have been finished in advance
        var fCount = 0

        val rDao: ReminderDAO = ReminderDAO.getInstance(mApp)!!
        val cursor: Cursor = mThingDAO!!.getThingsCursor(
            "type=" + (if (isReminder) Thing.REMINDER else Thing.GOAL)
        )!!
        while (cursor.moveToNext()) {
            val id = cursor.getLong(
                cursor.getColumnIndex(Def.Database.COLUMN_ID_THINGS)
            )
            val reminder: Reminder = rDao.getReminderById(id) ?: continue

            tNtfMillis += reminder.notifyMillis

            val state = cursor.getInt(
                cursor.getColumnIndex(Def.Database.COLUMN_STATE_THINGS)
            )
            if (state == Thing.FINISHED) {
                fCount++
                val notifyTime = reminder.notifyTime
                val finishTime = cursor.getLong(
                    cursor.getColumnIndex(Def.Database.COLUMN_FINISH_TIME_THINGS)
                )

                if (finishTime < notifyTime
                    && reminder.state != Reminder.REMINDED
                ) {
                    inAdvcCount++
                }

                if (isReminder) {
                    // for a Reminder, I think it should be finished after alarm rings.
                    if (finishTime > notifyTime) {
                        tFinTime += (finishTime - notifyTime)
                    }
                } else {
                    // for a Goal, I think it should be finished before alarm rings.
                    tFinTime += (finishTime - reminder.updateTime)
                }
            }
        }
        cursor.close()

        val tCount = cursor.count
        if (isReminder) {
            strs[1] = DateTimeUtil.getTimeLengthStr(tNtfMillis / tCount, mApp)
        } else {
            strs[1] = DateTimeUtil.getTimeLengthStrOnlyDay(tNtfMillis / tCount, mApp)
        }

        if (fCount == 0) {
            strs[2] = getString(R.string.infinity)
        } else {
            if (isReminder) {
                strs[2] = DateTimeUtil.getTimeLengthStr(tFinTime / fCount, mApp)
            } else {
                strs[2] = DateTimeUtil.getTimeLengthStrOnlyDay(tFinTime / fCount, mApp)
            }
        }

        strs[3] = LocaleUtil.getPercentStr(inAdvcCount, fCount)

        return strs
    }

    private fun countOfKey(src: String?, key: String): Int {
        if (src == null) {
            return 0
        }
        val lenBefore = src.length
        val lenAfter = src.replace(key.toRegex(), "").length
        return (lenBefore - lenAfter) / key.length
    }

    private fun longestKeySequenceSize(src: String, key: Char): Int {
        var longest = 0
        var tempCount = 0
        val len = src.length
        for (i in 0 until len) {
            if (src[i] == key) {
                tempCount++
            } else {
                tempCount = 0
            }
            if (tempCount > longest) {
                longest = tempCount
            }
        }
        return longest
    }

    internal inner class FinishedCreatedTask : AsyncTask<Any?, Any?, Array<String?>>() {

        override fun doInBackground(vararg params: Any?): Array<String?> {
            val strs = arrayOfNulls<String>(5)
            val nf = mThingsCounts!!.getCount(Thing.NOTE, Thing.FINISHED)
            val na = mThingsCounts!!.getCount(Thing.NOTE, ThingsCounts.ALL)
            strs[0] = "$nf / $na"

            val rf = mThingsCounts!!.getCount(Thing.REMINDER, Thing.FINISHED)
            val ra = mThingsCounts!!.getCount(Thing.REMINDER, ThingsCounts.ALL)
            strs[1] = "$rf / $ra"

            val hf = mThingsCounts!!.getCount(Thing.HABIT, Thing.FINISHED)
            val ha = mThingsCounts!!.getCount(Thing.HABIT, ThingsCounts.ALL)
            strs[2] = "$hf / $ha"

            val gf = mThingsCounts!!.getCount(Thing.GOAL, Thing.FINISHED)
            val ga = mThingsCounts!!.getCount(Thing.GOAL, ThingsCounts.ALL)
            strs[3] = "$gf / $ga"

            val af = nf + rf + hf + gf
            val aa = na + ra + ha + ga
            strs[4] = "$af / $aa"
            return strs
        }

        override fun onPostExecute(strings: Array<String?>) {
            val iconRes = intArrayOf(
                R.drawable.drawer_note,
                R.drawable.drawer_reminder,
                R.drawable.drawer_habit,
                R.drawable.drawer_goal,
                R.drawable.drawer_all
            )
            val firstRes = intArrayOf(
                R.string.note,
                R.string.reminder,
                R.string.habit,
                R.string.goal,
                R.string.all_things
            )
            val rv: RecyclerView = f(R.id.rv_finished_created_statistic)!!
            rv.adapter = StatisticAdapter(
                this@StatisticActivity, iconRes, firstRes, null, strings
            )
            rv.layoutManager = LinearLayoutManager(this@StatisticActivity)
        }
    }

    internal inner class NoteTask : AsyncTask<Any?, Any?, Array<String?>>() {

        override fun doInBackground(vararg params: Any?): Array<String?> {
            return getStrsForNoteRecord()
        }

        override fun onPostExecute(strings: Array<String?>) {
            val iconRes = intArrayOf(
                R.drawable.ic_char_count,
                R.drawable.ic_image_count,
                R.drawable.ic_video_count,
                R.drawable.ic_audio_count
            )
            val firstRes = intArrayOf(
                R.string.statistic_note_char_count,
                R.string.statistic_note_image_count,
                R.string.statistic_note_video_count,
                R.string.statistic_note_audio_count
            )
            val rv: RecyclerView = f(R.id.rv_note_record_statistic)!!
            val adapter = StatisticAdapter(
                this@StatisticActivity, iconRes, firstRes, null, strings
            )
            rv.adapter = adapter
            rv.layoutManager = LinearLayoutManager(this@StatisticActivity)
        }
    }

    internal inner class ReminderTask : AsyncTask<Any?, Any?, Array<String?>>() {

        override fun doInBackground(vararg params: Any?): Array<String?> {
            return getStrsForReminderGoalRecord(true)
        }

        override fun onPostExecute(strings: Array<String?>) {
            val iconRes = intArrayOf(
                R.drawable.drawer_finished,
                R.drawable.ic_average_notify_time,
                R.drawable.ic_average_finish_time,
                R.drawable.ic_finish_in_advance
            )
            val firstRes = intArrayOf(
                R.string.statistic_reminder_completion_rate,
                R.string.statistic_reminder_notify_time,
                R.string.statistic_reminder_finish_time,
                R.string.statistic_reminder_in_advance
            )
            val rv: RecyclerView = f(R.id.rv_reminder_record_statistic)!!
            val textSizes: FloatArray?
            textSizes = if (LocaleUtil.isChinese(mApp)) {
                null
            } else {
                floatArrayOf(EN.toFloat(), EN.toFloat(), EN.toFloat(), EN.toFloat())
            }
            rv.adapter = StatisticAdapter(
                this@StatisticActivity, iconRes, firstRes, textSizes, strings
            )
            rv.layoutManager = LinearLayoutManager(this@StatisticActivity)
        }
    }

    internal inner class HabitTask : AsyncTask<Any?, Any?, Array<String?>>() {

        override fun doInBackground(vararg params: Any?): Array<String?> {
            return getStrsForHabitRecord()
        }

        override fun onPostExecute(strings: Array<String?>) {
            val iconRes = intArrayOf(
                R.drawable.drawer_finished,
                R.drawable.ic_habit_finish_and_all,
                R.drawable.ic_habit_finish_rate,
                R.drawable.ic_longest_finish_times,
                R.drawable.ic_longest_pit
            )
            val firstRes = intArrayOf(
                R.string.statistic_habit_developed_rate,
                R.string.statistic_habit_finished_all,
                R.string.statistic_habit_completion_rate,
                R.string.statistic_habit_longest_finish_times,
                R.string.statistic_habit_longest_pit
            )
            val rv: RecyclerView = f(R.id.rv_habit_record_statistic)!!
            val textSizes: FloatArray
            textSizes = if (LocaleUtil.isChinese(mApp)) {
                floatArrayOf(16f, CN_SMALL.toFloat(), 16f, 16f, 16f)
            } else {
                floatArrayOf(EN.toFloat(), EN.toFloat(), 14f, EN.toFloat(), EN.toFloat())
            }
            rv.adapter = StatisticAdapter(
                this@StatisticActivity, iconRes, firstRes, textSizes, strings
            )
            rv.layoutManager = LinearLayoutManager(this@StatisticActivity)
        }
    }

    internal inner class GoalTask : AsyncTask<Any?, Any?, Array<String?>>() {

        override fun doInBackground(vararg params: Any?): Array<String?> {
            return getStrsForReminderGoalRecord(false)
        }

        override fun onPostExecute(strings: Array<String?>) {
            val iconRes = intArrayOf(
                R.drawable.drawer_finished,
                R.drawable.ic_average_notify_time_goal,
                R.drawable.ic_average_finish_time_goal,
                R.drawable.ic_finish_in_advance
            )
            val firstRes = intArrayOf(
                R.string.statistic_goal_completion_rate,
                R.string.statistic_goal_notify_time,
                R.string.statistic_reminder_finish_time,
                R.string.statistic_reminder_in_advance
            )
            val rv: RecyclerView = f(R.id.rv_goal_record_statistic)!!
            val textSizes: FloatArray?
            textSizes = if (LocaleUtil.isChinese(mApp)) {
                null
            } else {
                floatArrayOf(EN.toFloat(), 16f, EN.toFloat(), EN.toFloat())
            }
            rv.adapter = StatisticAdapter(
                this@StatisticActivity, iconRes, firstRes, textSizes, strings
            )
            rv.layoutManager = LinearLayoutManager(this@StatisticActivity)
        }
    }

    companion object {
        const val TAG: String = "StatisticActivity"

        private const val CN_SMALL = 14
        private const val EN       = 12
    }
}
