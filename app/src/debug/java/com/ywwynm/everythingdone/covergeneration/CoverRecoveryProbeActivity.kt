package com.ywwynm.everythingdone.covergeneration

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.widget.FrameLayout
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.FrequentSettings
import com.ywwynm.everythingdone.adapters.BaseThingsAdapter
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.helpers.VideoCoverPreviewManager
import com.ywwynm.everythingdone.managers.ModeManager
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingCardAppearance
import com.ywwynm.everythingdone.services.VideoCoverPreviewWorker
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File

/** 仅隔离包可运行。由设备脚本驱动长后台、进程回收及系统调度中断，持续记录正式卡片状态。 */
class CoverRecoveryProbeActivity : Activity() {
    private lateinit var events: File
    private lateinit var holder: BaseThingsAdapter.BaseThingViewHolder
    private lateinit var adapter: BaseThingsAdapter
    private var subscription: Closeable? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastUi = ""
    private var lastWorks = ""
    private var scenarioName = ""
    private val workLive by lazy { WorkManager.getInstance(this)
        .getWorkInfosByTagLiveData(VideoCoverPreviewWorker.TAG) }
    private val observer = Observer<List<WorkInfo>> { infos ->
        val data = JSONArray()
        infos.forEach { work -> data.put(JSONObject().put("id", work.id)
            .put("state", work.state).put("attempt", work.runAttemptCount)
            .put("progress", work.progress.toString())) }
        if (lastWorks != data.toString()) {
            lastWorks = data.toString()
            event("works", data)
        }
    }
    private val sample = object : Runnable {
        override fun run() {
            val state = holder.coverGeneration?.state
            val image = holder.ivImageAttachment?.drawable
            val data = JSONObject().put("phase", state?.phase).put("percent", state?.percent)
                .put("text", holder.tvImageCount?.text).put("gif", image is GifDrawable)
                .put("playing", (image as? GifDrawable)?.isRunning)
            if (lastUi != data.toString()) {
                lastUi = data.toString()
                event("ui", data)
            }
            handler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(packageName == "com.ywwynm.everythingdone.covervalidation")
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val scenario = intent.getStringExtra("scenario") ?: "baseline"
        scenarioName = scenario
        check(scenario.matches(Regex("[a-zA-Z0-9_-]+")))
        events = File(getExternalFilesDir(null), "recovery-$scenario.jsonl")
        val source = File(getExternalFilesDir(null), "complex-4k.mp4")
        check(source.isFile)
        val frameMs = intent.getLongExtra("frame", 12000L)
        FrequentSettings.put(Def.Meta.KEY_AUTOPLAY_COVER_DYNAMIC, true)
        val model = Thing(990001L, Thing.NOTE, 0xffeedfa7.toInt(), 1L).apply {
            title = "大视频封面与后台恢复"
            content = "4K 视频；记录实际生成进度、任务重启和卡片恢复。"
            attachment = AttachmentHelper.SIGNAL + AttachmentHelper.VIDEO + source.path
            thingCardAppearance = thingCardAppearance.copy(sources = mapOf(
                (AttachmentHelper.VIDEO.toString() + source.path) to ThingCardAppearance.SourceAppearance(videoFrameMs = frameMs)),
                spanMode = Thing.THING_CARD_SPAN_FULL)
        }
        val content = FrameLayout(this).apply { setPadding(24, 100, 24, 24) }
        setContentView(content)
        adapter = object : BaseThingsAdapter(applicationContext) {
            override fun getCurrentMode() = ModeManager.NORMAL
            override fun getThings(): List<Thing> = listOf(model)
            override fun isFullSpanThingCard(thing: Thing) = true
        }.apply { setFullSpanCardWidth(resources.displayMetrics.widthPixels - 48) }
        holder = adapter.onCreateViewHolder(content, 0)
        adapter.onBindViewHolder(holder, 0)
        content.addView(holder.itemView)
        event("create", JSONObject().put("frame", frameMs).put("sourceBytes", source.length()))
        workLive.observeForever(observer)
        subscription = VideoCoverPreviewManager.observePreview(this, source.path, frameMs,
            intent.getBooleanExtra("retry", false), { state ->
                event("state", JSONObject().put("phase", state.phase).put("percent", state.percent))
            }, { file -> event("ready", JSONObject().put("file", file.name).put("bytes", file.length())) })
        handler.post(sample)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getStringExtra("command") == "cancel") {
            event("cancel-request")
            WorkManager.getInstance(this).cancelAllWorkByTag(VideoCoverPreviewWorker.TAG)
            return
        }
        setIntent(intent)
        if (intent.getStringExtra("scenario") != scenarioName || intent.getBooleanExtra("retry", false)) recreate()
    }

    private fun event(type: String, data: Any? = null) {
        if (::events.isInitialized) events.appendText(JSONObject().put("time", SystemClock.elapsedRealtime())
            .put("pid", Process.myPid()).put("event", type).put("data", data).toString() + "\n")
    }

    override fun onResume() { super.onResume(); event("resume") }
    override fun onStop() { event("stop"); super.onStop() }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        subscription?.close()
        workLive.removeObserver(observer)
        if (::holder.isInitialized) adapter.onViewRecycled(holder)
        event("destroy")
        super.onDestroy()
    }
}
