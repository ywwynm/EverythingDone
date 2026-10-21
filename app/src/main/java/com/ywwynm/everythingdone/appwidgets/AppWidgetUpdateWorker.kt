package com.ywwynm.everythingdone.appwidgets

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.appwidgets.list.ThingsListWidget
import com.ywwynm.everythingdone.appwidgets.single.BaseThingWidget
import com.ywwynm.everythingdone.database.AppWidgetDAO
import com.ywwynm.everythingdone.database.ThingDAO
import com.ywwynm.everythingdone.helpers.ThingPrivacyResolver
import com.ywwynm.everythingdone.managers.ThingManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 小组件只在后台读取已保存的数据并渲染；进程退出后由 WorkManager 继续完成刷新。 */
class AppWidgetUpdateWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val widgetId = inputData.getInt(KEY_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return Result.success()
        try {
            mark(widgetId, "started")
            // 正常保存是串行异步写入。不能改为读取首页的可变 Thing 或尚未落库的旧值。
            val saved = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                ThingManager.getInstance(applicationContext)!!.afterPendingWrites { saved.countDown() }
            }
            if (!saved.await(10, TimeUnit.SECONDS)) return Result.retry()
            // REPLACE 不能中断正在执行的原生视频取帧。串行提交确保旧工作不会晚于新工作覆盖桌面。
            synchronized(renderLock) {
                if (!current(widgetId)) { mark(widgetId, "superseded"); return Result.success() }
                val context = applicationContext
                val manager = AppWidgetManager.getInstance(context)
                val dao = AppWidgetDAO.getInstance(context)!!
                val info = dao.getThingWidgetInfoById(widgetId) ?: run { mark(widgetId, "missing-config"); return Result.success() }
                val binding = manager.getAppWidgetInfo(widgetId) ?: run { mark(widgetId, "unbound"); return Result.success() }
                val list = info.thingId < 0
                if (binding.provider.packageName != context.packageName) return Result.success()
                val provider = Class.forName(binding.provider.className)
                val family = if (list) ThingsListWidget::class.java else BaseThingWidget::class.java
                if (!family.isAssignableFrom(provider)) return Result.success()
                mark(widgetId, "render")
                val thing = if (list) null else ThingDAO.getInstance(context)!!.getThingById(info.thingId)
                    ?: return Result.success()
                val safeThing = thing?.let { ThingPrivacyResolver.resolveForPresentation(context, it) }
                val views = if (list) AppWidgetHelper.createRemoteViewsForThingsList(context, widgetId)
                    else AppWidgetHelper.createRemoteViewsForSingleThing(context, safeThing, -1, widgetId, provider)
                mark(widgetId, "rendered")
                if (!current(widgetId) || dao.getThingWidgetInfoById(widgetId) == null ||
                    manager.getAppWidgetInfo(widgetId)?.provider != binding.provider) return Result.success()
                // 私密变化不能因后台取帧的时间窗口发布明文；后继工作仍会重新读取最新内容。
                if (thing != null) {
                    val latest = ThingDAO.getInstance(context)!!.getThingById(thing.id) ?: return Result.success()
                    if (safeThing?.isPrivate() != ThingPrivacyResolver.isEffectivelyPrivate(context, latest))
                        return Result.retry()
                }
                manager.notifyAppWidgetViewDataChanged(widgetId,
                    if (list) R.id.lv_things_list else R.id.lv_thing_check_list)
                manager.updateAppWidget(widgetId, views)
                mark(widgetId, "published")
            }
            return Result.success()
        } catch (error: Exception) {
            if (!isStopped) Log.w(TAG, "小组件 $widgetId 刷新失败，等待重试", error)
            return Result.retry()
        }
    }

    private fun current(widgetId: Int) = !isStopped && (revisions[widgetId]?.let { it == id } ?: true)

    companion object {
        private const val TAG = "AppWidgetUpdate"
        private const val KEY_ID = "widget_id"
        private val renderLock = Any()
        private val revisions = ConcurrentHashMap<Int, UUID>()
        private val direct = Executor { it.run() }
        @Volatile internal var diagnosticObserver: ((Int, String) -> Unit)? = null
        private fun mark(id: Int, stage: String) { if (BuildConfig.DEBUG) diagnosticObserver?.invoke(id, stage) }

        /** 在发送广播前就使旧渲染失效，避免主线程仍在处理保存时旧结果抢先发布。 */
        fun invalidate(widgetId: Int) { revisions[widgetId] = UUID.randomUUID() }

        fun enqueue(context: Context, ids: IntArray, pending: BroadcastReceiver.PendingResult? = null) {
            val unique = ids.distinct()
            if (unique.isEmpty()) { pending?.finish(); return }
            val remaining = AtomicInteger(unique.size)
            fun finished() { if (remaining.decrementAndGet() == 0) pending?.finish() }
            val manager = WorkManager.getInstance(context.applicationContext)
            for (widgetId in unique) {
                val request = OneTimeWorkRequest.Builder(AppWidgetUpdateWorker::class.java)
                    .setInputData(Data.Builder().putInt(KEY_ID, widgetId).build()).build()
                revisions[widgetId] = request.id
                try {
                    val persisted = manager.enqueueUniqueWork("app-widget-$widgetId", ExistingWorkPolicy.REPLACE, request).result
                    // 广播只等待工作持久化，不在广播时限内完成所有小组件的媒体解码。
                    persisted.addListener({
                        try { persisted.get() } catch (error: Exception) { Log.w(TAG, "小组件刷新入队失败", error) }
                        finally { finished() }
                    }, direct)
                } catch (error: Exception) {
                    Log.w(TAG, "小组件刷新入队失败", error)
                    finished()
                }
            }
        }

        fun cancel(context: Context, id: Int) {
            invalidate(id)
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork("app-widget-$id")
        }
    }
}
