package com.ywwynm.everythingdone.views.particledismiss

import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.DoingActivity
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.services.DoingService

/** 使用真实 DoingActivity 的布局、适配器、输入和滑动回调；仅替换服务与完成动作。 */
class ParticleDoingProbeActivity : DoingActivity() {
    private var report: ParticlePresentationReports.Report? = null
    private val probeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var probeTime = 25 * 60000L
    private var probeTicks = 0
    private var ticking = false
    private var countdownListener: DoingService.DoingListener? = null
    private var previousDigits: IntArray? = null
    private inner class ProbeService : DoingService() {
        val binder = object : DoingBinder() {
            override fun getThing() = field("mThing").get(this@ParticleDoingProbeActivity) as Thing
            override fun getTimeInMillis() = 25 * 60000L
            override fun getLeftTime() = probeTime
            override fun setCountdownListener(listener: DoingListener?) { countdownListener = listener }
            override fun startCountdown(resume: Boolean) {
                ticking = true; previousDigits = null
                probeHandler.postDelayed(tick, 1000L)
            }
            override fun canAdd5Min() = true
            override fun add5Min() {
                probeTime += 5 * 60000L
                countdownListener?.onAdd5Min(probeTime)
                report?.event("doing_probe_add_five_minutes", mapOf("timeMillis" to probeTime))
            }
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed || !ticking) return
            val before = probeTime
            probeTime -= 1000L
            if (countdownListener == null)
                findViewById<com.github.adnansm.timelytextview.TimelyClockView>(R.id.clock_time_doing).setTimeMillis(probeTime, true)
            else {
                val s = probeTime / 1000L
                val digits = intArrayOf((s / 36000).toInt(), (s / 3600 % 10).toInt(),
                    (s / 600 % 6).toInt(), (s / 60 % 10).toInt(), (s / 10 % 6).toInt(), (s % 10).toInt())
                countdownListener?.onLeftTimeChanged(previousDigits ?: IntArray(6) { -1 }, digits, before, probeTime)
                previousDigits = digits
            }
            report?.event("doing_probe_tick", mapOf("timeMillis" to probeTime, "ticks" to ++probeTicks))
            probeHandler.postDelayed(this, 1000L)
        }
    }
    private fun field(name: String) = DoingActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun call(name: String) = DoingActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(this)

    override fun beforeInit() {
        // 不连接 DoingService，不设置正在做事的 ID，不触发计时或数据库写入。
        field("mServiceUnbind").setBoolean(this, true)
        val receiver = field("mReceiver").get(this) as BroadcastReceiver
        ContextCompat.registerReceiver(this, receiver, IntentFilter(BROADCAST_ACTION_JUST_FINISH),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        report = ParticlePresentationReports.find(intent.getStringExtra(ParticlePresentationReports.EXTRA_RUN))
        report?.let { ParticlePresentationDiagnostics.bind(this, it) }
    }

    override fun initUI() {
        super.initUI()
        val thing = Thing(-9842017L, Thing.NOTE, 0xfff3bd70.toInt(), 0L).apply {
            title = "Doing 页面虚构卡片"
            content = "左右滑动、快速松手或回拉\n复用真实页面的透明壁纸、卡片浓度和输入流程\n不启动计时，不修改任何已有记事"
            if (intent.getBooleanExtra("doing_long_card", false)) {
                title = "Doing 长记事滚动对照"
                content = (1..90).joinToString("\n") { line ->
                    "第 %02d 行：长记事滚动与粒子对照，检查内容、裁剪和恢复。".format(line)
                }
            }
        }
        val existingId = intent.getLongExtra("existing_thing_id", -1L)
        val displayed = if (existingId >= 0L) checkNotNull(
            com.ywwynm.everythingdone.database.ThingDAO.getInstance(this)?.getThingById(existingId)) else thing
        field("mThing").set(this, displayed)
        call("initRecyclerView")
        call("applyClockThingBackground")
        findViewById<RecyclerView>(R.id.rv_thing_doing).alpha = .84f
        findViewById<RecyclerView>(R.id.rv_thing_doing).addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                report?.event("doing_probe_scroll", mapOf("state" to newState,
                    "offset" to recyclerView.computeVerticalScrollOffset(),
                    "range" to recyclerView.computeVerticalScrollRange(),
                    "extent" to recyclerView.computeVerticalScrollExtent()))
            }
        })
        findViewById<View>(R.id.tv_swipe_to_finish_doing).alpha = 1f
        findViewById<com.github.adnansm.timelytextview.TimelyClockView>(R.id.clock_time_doing).apply {
            alpha = 1f; setTimeMillis(25 * 60000L, false)
        }
        listOf(R.id.fl_add_5_min, R.id.fl_strict_mode, R.id.fl_cancel_doing).forEach {
            findViewById<View>(it).apply { scaleX = 1f; scaleY = 1f }
        }
        listOf(R.id.fab_add_5_min, R.id.fab_strict_mode, R.id.fab_cancel_doing).forEach {
            findViewById<View>(it).setOnClickListener { finish() }
        }
        if (intent.getBooleanExtra("dynamic_background", false)) {
            val density = resources.displayMetrics.density
            findViewById<FrameLayout>(android.R.id.content).addView(ParticleDynamicBackdropProbe(this),
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, (24 * density).toInt(), Gravity.TOP).apply {
                    topMargin = (32 * density).toInt()
                    leftMargin = (16 * density).toInt(); rightMargin = leftMargin
                })
        }
        window.decorView.post {
            if (intent.getBooleanExtra("doing_demo_clock", false)) command("tick")
            report?.event("doing_probe_ready", mapOf("activity" to "DoingActivity",
                "serviceBound" to false, "databaseWrites" to false, "containerAlpha" to .84f,
                "existingThingReadOnly" to (existingId >= 0L)))
        }
    }

    override fun useSwipeParticle() = true

    override fun finish() {
        report?.event("doing_probe_finish_requested", mapOf("alreadyFinishing" to isFinishing))
        super.finish()
    }

    override fun onPause() {
        report?.event("doing_probe_paused", mapOf("finishing" to isFinishing))
        super.onPause()
    }

    override fun onStop() {
        report?.event("doing_probe_stopped", mapOf("finishing" to isFinishing))
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        report?.event("doing_probe_window_focus", mapOf("focus" to hasFocus))
    }

    override fun useParticlePageContent() = if (intent.hasExtra("doing_gl_scene"))
        intent.getBooleanExtra("doing_gl_scene", false) else super.useParticlePageContent()

    override fun onDestroy() {
        probeHandler.removeCallbacksAndMessages(null)
        field("mServiceUnbind").setBoolean(this, true)
        report?.event("doing_probe_destroying")
        super.onDestroy()
        report?.event("doing_probe_destroyed")
        report?.save()
    }

    /** 在恢复动画真正结束后采样，不能只验证 clearView 曾经写过正确 alpha。 */
    internal fun captureFinalState() {
        val list = findViewById<RecyclerView>(R.id.rv_thing_doing)
        val card = list.getChildAt(0)
        report?.event("doing_probe_final_state", mapOf("containerAlpha" to list.alpha,
            "cardAlpha" to card?.alpha, "cardTranslationX" to card?.translationX,
            "cardAttached" to card?.isAttachedToWindow, "scrollOffset" to list.computeVerticalScrollOffset(),
            "scrollRange" to list.computeVerticalScrollRange(), "scrollExtent" to list.computeVerticalScrollExtent(),
            "clockTicks" to probeTicks,
            "expectedRestingAlpha" to .84f, "meaning" to "view_properties_not_screen_verified"))
        captureMediaState()
    }

    private fun captureMediaState() {
        report?.event("doing_probe_media", mapOf("state" to inspectMedia().toString()))
    }

    internal fun inspectMedia() = ParticleMediaLoadProbe(window.decorView).inspect().apply {
        val thing = field("mThing").get(this@ParticleDoingProbeActivity) as? Thing
        val source = com.ywwynm.everythingdone.helpers.ThingCardMediaHelper.resolveEffectiveMediaSource(thing)
        put("sourceExtension", source?.pathName?.substringAfterLast('.', ""))
        put("coverAutoplay", com.ywwynm.everythingdone.FrequentSettings.getBoolean(
            com.ywwynm.everythingdone.Def.Meta.KEY_AUTOPLAY_COVER_DYNAMIC, true))
    }

    /** 使用真实计时控件与 ValueAnimator；仅时间源替换为虚构计时，不触碰服务或记事。 */
    internal fun command(action: String) {
        val clock = findViewById<com.github.adnansm.timelytextview.TimelyClockView>(R.id.clock_time_doing)
        when (action) {
            "media" -> captureMediaState()
            "tick" -> { ticking = true; probeHandler.removeCallbacks(tick); tick.run() }
            "stop" -> { ticking = false; probeHandler.removeCallbacks(tick); clock.setTimeMillis(25 * 60000L, false) }
            "outline" -> clock.setRenderMode(false)
            "fill" -> clock.setRenderMode(true)
            "infinite" -> { ticking = false; probeHandler.removeCallbacks(tick); clock.setInfinite(true)
                clock.animate().alpha(.75f).setDuration(1800L).withEndAction { clock.animate().alpha(1f).setDuration(1800L).start() }.start() }
            "dialog" -> onClick(findViewById(R.id.fab_cancel_doing))
            "scroll_top" -> findViewById<RecyclerView>(R.id.rv_thing_doing).scrollBy(0, -Int.MAX_VALUE)
            "resume" -> (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).appTasks
                .firstOrNull { it.taskInfo.taskId == taskId }?.moveToFront()
            "resize" -> {
                val content = findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                val nextHeight = content.height - (80 * resources.displayMetrics.density).toInt()
                content.layoutParams = content.layoutParams.apply { height = nextHeight }
            }
            "business" -> {
                ticking = false; probeHandler.removeCallbacks(tick)
                field("mDoingBinder").set(this, ProbeService().binder)
                field("mServiceUnbind").setBoolean(this, false)
                listOf(R.id.clock_time_doing, R.id.tv_swipe_to_finish_doing, R.id.rv_thing_doing)
                    .forEach { findViewById<View>(it).alpha = 0f }
                listOf(R.id.fl_add_5_min, R.id.fl_strict_mode, R.id.fl_cancel_doing).forEach {
                    findViewById<View>(it).apply { scaleX = 0f; scaleY = 0f }
                }
                listOf(R.id.fab_add_5_min, R.id.fab_strict_mode, R.id.fab_cancel_doing).forEach {
                    findViewById<View>(it).setOnClickListener(::onClick)
                }
                // 真实的布局更新、入场、计时监听、+5m、严格模式与取消弹窗；服务边界使用内存替身。
                call("initAfterBindService")
            }
            "strict_state" -> report?.event("doing_probe_strict_state", mapOf("enabled" to
                (field("mDoingBinder").get(this) as? DoingService.DoingBinder)?.isInStrictMode()))
        }
        report?.event("doing_probe_command", mapOf("action" to action))
    }

    override fun finishThingBySwipe() {
        report?.event("doing_probe_swipe_finished")
        findViewById<RecyclerView>(R.id.rv_thing_doing).adapter?.notifyItemChanged(0)
    }
}
