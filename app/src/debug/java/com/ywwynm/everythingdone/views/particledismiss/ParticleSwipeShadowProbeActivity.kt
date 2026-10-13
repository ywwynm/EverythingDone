package com.ywwynm.everythingdone.views.particledismiss

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.FrameMetrics
import android.view.Window
import android.widget.TextView
import android.widget.FrameLayout
import android.content.res.ColorStateList
import com.ywwynm.everythingdone.views.FloatingActionButton
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.views.InterceptTouchCardView
import com.ywwynm.everythingdone.views.StablerRecyclerView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 虚构列表复用生产手势和回位管线；不访问记事 DAO、不修改任何偏好。 */
class ParticleSwipeShadowProbeActivity : AppCompatActivity() {
    private var presentationReport: ParticlePresentationReports.Report? = null
    private var metricsAttached = false
    private companion object {
        var probeOwner: ParticleSwipeShadowProbeActivity? = null
    }
    private lateinit var list: RecyclerView
    private lateinit var swipe: ParticleSwipeAnimator
    private lateinit var returning: ThingSwipeReturn
    private lateinit var output: File
    private val main = Handler(Looper.getMainLooper())
    private val frames = JSONArray()
    private val inputs = JSONArray()
    private val capturedFrames = mutableListOf<Pair<Long, Bitmap>>()
    private var finishAt = Long.MAX_VALUE
    private var startedAt = 0L
    private var copying = false
    private var stopped = false
    private var completions = 0
    private val windowFrames = JSONArray()
    private val particleFrames = JSONArray()
    private val observed = mutableSetOf<ParticleDismissOverlay>()
    private var pixelSampling = true
    private var fab: FloatingActionButton? = null
    private val fabFrames = JSONArray()
    private var fabMotionStarted = false
    private var fabClicks = 0
    private var delegatedFabClicks = 0
    private var gestureEnded = false
    private val metricsListener = Window.OnFrameMetricsAvailableListener { _, frame, dropped ->
        val report = presentationReport
        if (report != null) report.event("window_frame", mapOf("intendedVsync" to frame.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP),
            "durationNanos" to frame.getMetric(FrameMetrics.TOTAL_DURATION), "droppedCallbacks" to dropped,
            "meaning" to "window_only_not_particle_surface"))
        else windowFrames.put(JSONArray(listOf(frame.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP),
            frame.getMetric(FrameMetrics.TOTAL_DURATION), frame.getMetric(FrameMetrics.GPU_DURATION),
            frame.getMetric(FrameMetrics.DEADLINE), dropped.toLong())))
    }
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        presentationReport = ParticlePresentationReports.bind(this)
        if (intent.hasExtra(ParticlePresentationReports.EXTRA_RUN) &&
            (presentationReport == null || android.os.Build.VERSION.SDK_INT < 34)) { finish(); return }
        probeOwner = this
        ParticleCardSurfaceHandoff.forceTextureForTests = presentationReport == null && intent.getBooleanExtra("force_texture", false)
        ParticleCardSurfaceHandoff.seedForTests = 42017
        ParticleForegroundSurface.renderedFramesForTests = 0
        pixelSampling = !intent.getBooleanExtra("performance_only", false)
        window.addOnFrameMetricsAvailableListener(metricsListener, main)
        metricsAttached = true
        val run = intent.getStringExtra("run_id") ?: "latest"
        require(run.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        output = File(getExternalFilesDir(null), "swipe-shadow-probe/$run").apply { mkdirs() }
        val allowRight = intent.getBooleanExtra("allow_right_swipe", false)
        swipe = ParticleSwipeAnimator(this, allowRightSwipe = allowRight)
        if (intent.getBooleanExtra("check_release_origin", false)) Thread {
            val result = runCatching {
                check(ParticleMaterialNative.enabled)
                val rules = ParticleMicroflakeRenderer.sharedResources(assets).rules
                val variation = ParticleMicroflakeVariation.fromSeed(42017L, rules)
                var error = 0f
                for (direction in listOf(0f, 180f)) for (point in listOf(null,
                    ParticleReleaseOrigin(.25f, .25f), ParticleReleaseOrigin(.75f, .75f),
                    ParticleReleaseOrigin(.98f, .02f))) {
                    fun field() = ParticleMicroflakeModel.releaseField(72, 48, direction, rules,
                        360f, 180f, variation, releaseOrigin = point)
                    val native = field()
                    val reference = ParticleMaterialNative.reference { field() }
                    error = maxOf(error, native.indices.maxOf { kotlin.math.abs(native[it] - reference[it]) })
                }
                check(error < 2e-6f) { "JNI 与 Kotlin 释放场不一致：$error" }
                JSONObject().put("cases", 8).put("maxError", error)
            }.getOrElse { JSONObject().put("error", it.toString()) }
            File(output, "origin-check.json").writeText(result.toString())
        }.start()
        list = StablerRecyclerView(this).apply {
            id = R.id.rv_things
            layoutManager = LinearLayoutManager(this@ParticleSwipeShadowProbeActivity)
            setBackgroundColor(Color.rgb(244, 244, 244))
            setPadding(dp(48), dp(120), dp(48), dp(48))
            clipToPadding = false
        }
        returning = ThingSwipeReturn(this, list, alwaysParticles = presentationReport != null)
        list.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = intent.getIntExtra("card_count", 1).coerceIn(1, 3)
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val card = InterceptTouchCardView(parent.context).apply {
                    val requestedWidth = intent.getIntExtra("card_width_dp", 0)
                    layoutParams = RecyclerView.LayoutParams(
                        if (requestedWidth > 0) dp(requestedWidth) else ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(intent.getIntExtra("card_height_dp", 180).coerceIn(120, 540))).apply {
                            if (itemCount > 1) bottomMargin = dp(24)
                        }
                    radius = dp(12).toFloat()
                    cardElevation = resources.getDimension(R.dimen.thing_card_normal_elevation)
                    setCardBackgroundColor(Color.rgb(243, 189, 112))
                    isClickable = true
                    contentDescription = "虚构阴影测试卡片"
                    addView(TextView(context).apply {
                        text = "虚构阴影测试卡片\n左滑后仍留在列表\n不读取或修改现有记事"
                        textSize = 18f
                        setTextColor(Color.DKGRAY)
                        setPadding(dp(24), dp(24), dp(24), dp(24))
                    })
                }
                return object : RecyclerView.ViewHolder(card) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                holder.itemView.contentDescription = if (position == 0) "虚构阴影测试卡片" else "背景阴影测试卡片 $position"
                ((holder.itemView as ViewGroup).getChildAt(0) as TextView).text = if (position == 0)
                    "虚构阴影测试卡片\n左滑后仍留在列表\n不读取或修改现有记事" else "保持静止的背景卡片 $position\n观察动画首尾的阴影强度"
            }
        }
        val swipeFlags = ItemTouchHelper.LEFT or if (allowRight) ItemTouchHelper.RIGHT else 0
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, swipeFlags) {
            override fun onSelectedChanged(holder: RecyclerView.ViewHolder?, state: Int) {
                super.onSelectedChanged(holder, state)
                presentationReport?.event("swipe_selected", mapOf("state" to state))
                if (holder != null && state == ItemTouchHelper.ACTION_STATE_SWIPE)
                    swipe.onSwipeSelected(holder.itemView)
            }
            override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder) = false
            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) {
                val done = {
                    completions++
                    list.adapter!!.notifyItemChanged(0)
                    returning.start(0, particles = true, fromRight = direction == ItemTouchHelper.RIGHT)
                }
                if (!swipe.finishSwipe(holder.itemView, list.width, done)) done()
            }
            override fun onChildDraw(c: Canvas, rv: RecyclerView, holder: RecyclerView.ViewHolder,
                                     dx: Float, dy: Float, state: Int, active: Boolean) {
                if (dx > 0f && !allowRight) swipe.reset(holder.itemView)
                val particles = (allowRight || dx <= 0f) && swipe.draw(holder.itemView, dx)
                super.onChildDraw(c, rv, holder, if (particles || dx > 0f) 0f else dx, dy, state, active)
                if (!particles) holder.itemView.alpha = 1f
            }
            override fun clearView(rv: RecyclerView, holder: RecyclerView.ViewHolder) {
                swipe.reset(holder.itemView)
                super.clearView(rv, holder)
                holder.itemView.alpha = 1f
            }
        }).attachToRecyclerView(list)
        val root = FrameLayout(this)
        root.addView(list, FrameLayout.LayoutParams(-1, -1))
        if (intent.getBooleanExtra("dynamic_background", false)) {
            root.addView(ParticleDynamicBackdropProbe(this), FrameLayout.LayoutParams(-1, dp(64),
                android.view.Gravity.BOTTOM).apply { bottomMargin = dp(112) })
        }
        if (intent.getBooleanExtra("with_chrome", false)) {
            if (intent.getBooleanExtra("normal_chrome", false))
                ParticleChromeProbe.addNormal(root, "虚构顶栏，保留真实渐变阴影", if (presentationReport != null) 80 else 144)
            else ParticleChromeProbe.add(root, "虚构顶栏，保留真实渐变阴影", 144)
        }
        if (intent.getBooleanExtra("with_fab", false)) {
            fab = FloatingActionButton(this).apply {
                id = R.id.fab_create
                backgroundTintList = ColorStateList.valueOf(Color.rgb(34, 102, 204))
                imageTintList = ColorStateList.valueOf(Color.WHITE)
                setImageResource(R.drawable.vec_ic_create_thing)
                compatElevation = dp(6).toFloat()
                contentDescription = "虚构 FAB，不创建记事"
                setOnClickListener {
                    fabClicks++
                    if (transitionAlpha == 0f) delegatedFabClicks++
                }
            }.also { root.addView(it, FrameLayout.LayoutParams(dp(56), dp(56))) }
        }
        presentationReport?.let { report ->
            list.setPadding(dp(32), dp(180), dp(32), dp(100))
            val header = layoutInflater.inflate(R.layout.include_activity_header_things, root, false)
            header.findViewById<TextView>(R.id.tv_header_title).text = "虚构首页标题"
            header.findViewById<TextView>(R.id.tv_header_subtitle).text = "观察标题、图标和 FAB"
            root.addView(header)
            root.findViewById<androidx.appcompat.widget.Toolbar>(R.id.actionbar)?.apply {
                title = "前景交接对照"
                setNavigationIcon(android.R.drawable.ic_menu_sort_by_size)
                setNavigationOnClickListener { android.widget.Toast.makeText(context, "示例菜单仍可响应", 0).show() }
                menu.add("示例搜索").setIcon(android.R.drawable.ic_menu_search).apply {
                    setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
                    setOnMenuItemClickListener { report.event("toolbar_clicked"); true }
                }
            }
            root.addView(android.widget.Button(this).apply {
                text = "${report.label}\n返回检查页记录结果"
                contentDescription = "${report.id} ${report.label}"
                setOnClickListener { finish() }
            }, FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM))
            report.event("card_fixture_ready", mapOf("api" to android.os.Build.VERSION.SDK_INT,
                "includes" to "actionbar,rl_header,actionbar_shadow,view_status_bar,fab_create"))
        }
        setContentView(root)
        list.post {
            startedAt = SystemClock.uptimeMillis()
            val card = list.findViewHolderForAdapterPosition(0)?.itemView ?: return@post
            val at = IntArray(2).also { card.getLocationOnScreen(it) }
            val rootAt = IntArray(2)
            if (presentationReport != null) root.getLocationOnScreen(rootAt)
            fab?.let {
                it.x = (at[0] - rootAt[0] + card.width - dp(40)).toFloat()
                it.y = (at[1] - rootAt[1] + card.height - dp(40)).toFloat()
            }
            if (presentationReport != null) {
                presentationReport?.event("card_layout", mapOf("x" to at[0], "y" to at[1],
                    "width" to card.width, "height" to card.height,
                    "backend" to if (ParticleCardSurfaceHandoff.supported()) "SurfaceView" else "TextureView"))
                return@post
            }
            val parents = JSONArray()
            var ancestor: View? = card
            while (ancestor != null) {
                parents.put(JSONObject().put("class", ancestor.javaClass.simpleName).put("z", ancestor.z)
                    .put("alpha", ancestor.alpha).put("scale", ancestor.scaleX))
                ancestor = ancestor.parent as? View
            }
            File(output, "ready.json").writeText(JSONObject().put("x", at[0]).put("y", at[1])
                .put("width", card.width).put("height", card.height).put("parents", parents)
                .put("density", resources.displayMetrics.density)
                .put("backend", if (ParticleCardSurfaceHandoff.supported()) "surface" else "texture")
                .put("fab", fab?.let { JSONObject().put("x", it.x).put("y", it.y)
                    .put("width", dp(56)).put("height", dp(56)) })
                .put("rotation", ParticleWindowLighting.rotation(card)).toString())
            val shadow = ParticleCardSnapshot.of(card)
            if (shadow != null) ParticleHardwareSnapshot.capture(card, shadow.bounds, shadow) { bitmap ->
                if (bitmap != null) Thread {
                    File(output, "snapshot.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                    File(output, "snapshot.json").writeText(JSONObject()
                        .put("bounds", JSONArray(listOf(shadow.bounds.left, shadow.bounds.top, shadow.bounds.right, shadow.bounds.bottom)))
                        .put("light", JSONArray(listOf(shadow.lightX, shadow.lightY, shadow.lightZ, shadow.lightRadius,
                            shadow.ambientAlpha, shadow.spotAlpha))).toString())
                }.start()
            }
            sample()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (::swipe.isInitialized && ::list.isInitialized) {
            if (presentationReport == null) inputs.put(JSONArray(listOf(SystemClock.uptimeMillis() - startedAt,
                event.actionMasked, event.rawX, event.rawY)))
            else if (event.actionMasked != MotionEvent.ACTION_MOVE) presentationReport?.event("input",
                mapOf("action" to event.actionMasked, "x" to event.rawX, "y" to event.rawY))
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                gestureEnded = false
                val card = list.findViewHolderForAdapterPosition(0)?.itemView
                val overFab = fab?.let {
                    val bounds = Rect()
                    it.getGlobalVisibleRect(bounds) && bounds.contains(event.rawX.toInt(), event.rawY.toInt())
                } ?: false
                val cardBounds = Rect()
                val overCard = presentationReport == null || (card?.getGlobalVisibleRect(cardBounds) == true &&
                    cardBounds.contains(event.rawX.toInt(), event.rawY.toInt()))
                if (card != null && !overFab && overCard) swipe.warm(card, event)
                finishAt = Long.MAX_VALUE
                if (fab != null && !fabMotionStarted && intent.getBooleanExtra("fab_motion", false)) {
                    fabMotionStarted = true
                    main.postDelayed({
                        fab?.animate()?.translationYBy(-dp(64).toFloat())?.scaleX(.7f)?.scaleY(.7f)?.alpha(.65f)
                            ?.setDuration(450)?.withEndAction {
                                fab?.animate()?.translationYBy(dp(64).toFloat())?.scaleX(1f)?.scaleY(1f)?.alpha(1f)
                                    ?.setDuration(450)?.start()
                            }?.start()
                    }, 650)
                }
            } else swipe.recordTouch(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                gestureEnded = true
                finishAt = SystemClock.uptimeMillis() + 3000L
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun sample() {
        if (stopped || isDestroyed) return
        if (gestureEnded && fab?.transitionAlpha == 0f && intent.getBooleanExtra("exit_after_up", false)) {
            File(output, "exit.json").writeText(JSONObject().put("fabDelegated", true)
                .put("activeOverlays", ParticleDismissOverlay.activeIn(this).size).toString())
            stopped = true
            finish()
            return
        }
        fab?.let {
            fabFrames.put(JSONArray(listOf(SystemClock.uptimeMillis() - startedAt, it.x.toDouble(),
                it.y.toDouble(), it.scaleX.toDouble(), it.alpha.toDouble(), it.transitionAlpha.toDouble())))
        }
        ParticleDismissOverlay.activeIn(this).forEach { overlay ->
            if (observed.add(overlay)) overlay.afterRelease(Runnable {
                val field = ParticleDismissOverlay::class.java.getDeclaredField("session").apply { isAccessible = true }
                val session = field.get(overlay) as? ParticleSession
                if (session != null) {
                    val spec = session.spec
                    val decor = IntArray(2).also { window.decorView.getLocationOnScreen(it) }
                    fun rows(list: MutableList<LongArray>) = synchronized(list) { JSONArray(list.map { JSONArray(it.toList()) }) }
                    particleFrames.put(JSONObject().put("submitted", rows(session.submittedTimings))
                        .put("presented", rows(session.presentedTimings)).put("draws", rows(session.drawTimings))
                        .put("origin", spec.releaseOrigin?.let { JSONArray(listOf(it.x, it.y)) })
                        .put("snapshot", JSONArray(listOf(spec.originXPx + decor[0], spec.originYPx + decor[1],
                            spec.snapshot.width.toFloat(), spec.snapshot.height.toFloat())))
                        .put("flightDx", spec.virtualTouchXPx - spec.originXPx - spec.snapshot.width / 2f)
                        .put("startup", JSONArray(session.startupTimings.toList())))
                }
            })
        }
        if (SystemClock.uptimeMillis() >= finishAt) {
            stopped = true
            File(output, "result.json").writeText(JSONObject().put("frames", frames).put("inputs", inputs)
                .put("windowFrames", windowFrames).put("particleFrames", particleFrames)
                .put("fabFrames", fabFrames).put("fabRenderFrames", ParticleForegroundSurface.renderedFramesForTests)
                .put("fabClicks", fabClicks).put("delegatedFabClicks", delegatedFabClicks)
                .put("startedAtNanos", startedAt * 1000000L)
                .put("completions", completions).toString())
            val samples = capturedFrames.toList()
            capturedFrames.clear()
            Thread {
                val directory = File(output, "frames").apply { mkdirs() }
                for ((at, bitmap) in samples) {
                    File(directory, "$at.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }.start()
            return
        }
        val card = list.findViewHolderForAdapterPosition(0)?.itemView
        if (card != null && !pixelSampling) {
            frames.put(JSONObject().put("at", SystemClock.uptimeMillis() - startedAt)
                .put("alpha", card.alpha).put("tx", card.translationX).put("z", card.z)
                .put("contentHidden", card.alpha == 0f))
        }
        if (pixelSampling && !copying && card != null && card.width > dp(160)) {
            val at = IntArray(2).also { list.getLocationInWindow(it) }
            val x = at[0] + card.left + card.width / 2
            val y = at[1] + card.top
            // Window PixelCopy 不包含独立 SurfaceView。Surface 路径的最终合成验证使用系统录屏。
            val rect = Rect(x - dp(72), y - dp(6), x + dp(72), y + dp(18))
            val image = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
            val frame = JSONObject().put("at", SystemClock.uptimeMillis() - startedAt)
                .put("alpha", card.alpha).put("elevation", card.elevation).put("z", card.z)
                .put("tx", card.translationX).put("view", System.identityHashCode(card))
                .put("shadowColorAlpha", Color.alpha(card.outlineAmbientShadowColor))
                .put("contentHidden", card.alpha == 0f)
            copying = true
            PixelCopy.request(window, rect, image, { status ->
                if (status == PixelCopy.SUCCESS) {
                    val pixels = IntArray(image.width * image.height)
                    image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
                    val shadowPixels = image.width * dp(5)
                    frame.put("darkness", (0 until shadowPixels).sumOf { 244 - Color.red(pixels[it]) }.toDouble() / shadowPixels)
                    val interior = pixels.takeLast(image.width * dp(4))
                    frame.put("interiorDelta", interior.maxOf { kotlin.math.abs(244 - Color.blue(it)) })
                    frames.put(frame)
                    if (!stopped && inputs.length() > 0 && capturedFrames.size < 600) capturedFrames += frame.getLong("at") to image
                    else image.recycle()
                }
                else image.recycle()
                copying = false
            }, main)
        }
        list.postOnAnimation { sample() }
    }

    override fun onStop() {
        if (presentationReport != null) {
            if (::swipe.isInitialized) swipe.reset()
            if (::returning.isInitialized) returning.cancel()
            ParticleDismissOverlay.activeIn(this).toList().forEach { it.release() }
            presentationReport?.event("activity_stopped"); presentationReport?.save()
        }
        super.onStop()
    }

    override fun onDestroy() {
        stopped = true
        if (metricsAttached) window.removeOnFrameMetricsAvailableListener(metricsListener)
        if (probeOwner === this) {
            probeOwner = null
            ParticleCardSurfaceHandoff.forceTextureForTests = false
            ParticleCardSurfaceHandoff.seedForTests = null
        }
        capturedFrames.forEach { it.second.recycle() }
        capturedFrames.clear()
        if (::swipe.isInitialized) swipe.reset()
        if (::returning.isInitialized) returning.cancel()
        super.onDestroy()
        presentationReport?.event("activity_destroyed"); presentationReport?.save()
        ParticlePresentationDiagnostics.unbind(this)
    }
}
