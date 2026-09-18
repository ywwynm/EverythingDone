package com.ywwynm.everythingdone.views.particledismiss

import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.fragments.AlertDialogFragment
import com.ywwynm.everythingdone.utils.ThingAnimationPreferences
import com.ywwynm.everythingdone.views.InterceptTouchCardView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 虚构列表用生产退场控制器；不访问 DAO，不修改用户偏好。由真实按钮及确认弹窗触发。 */
class ThingListRemovalProbeActivity : AppCompatActivity() {
    private lateinit var list: RecyclerView
    private lateinit var animator: ThingListRemovalAnimator
    private lateinit var output: File
    private var ids = (1L..18L).map { if (it % 4L == 0L) -it else it }
    private var frozen: List<Long>? = null
    private var started = 0L
    private var reflow = 0L
    private val samples = JSONArray()
    private var finished = false
    private var normalChrome = emptyList<View>()
    private var contextualChrome: View? = null
    private val mode get() = intent.getStringExtra("case") ?: "mixed"
    private val visibleIds get() = frozen ?: ids
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ParticleCardSurfaceHandoff.forceTextureForTests = intent.getBooleanExtra("texture", false)
        val run = intent.getStringExtra("run_id") ?: "latest"
        require(run.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        output = File(getExternalFilesDir(null), "list-removal-probe/$run").apply { mkdirs() }
        list = RecyclerView(this).apply {
            id = R.id.rv_things
            layoutManager = StaggeredGridLayoutManager(2, RecyclerView.VERTICAL)
            setPadding(dp(16), dp(104), dp(16), dp(24))
            clipToPadding = false
            setBackgroundColor(Color.rgb(244, 244, 244))
            adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = visibleIds.size
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                    val card = InterceptTouchCardView(parent.context).apply {
                        layoutParams = StaggeredGridLayoutManager.LayoutParams(-1, -2).apply {
                            setMargins(dp(6), dp(6), dp(6), dp(6))
                        }
                        radius = dp(12).toFloat()
                        cardElevation = resources.getDimension(R.dimen.thing_card_normal_elevation)
                        addView(TextView(context).apply { setPadding(dp(16), dp(20), dp(16), dp(20)); textSize = 17f })
                    }
                    return object : RecyclerView.ViewHolder(card) {}
                }
                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                    val id = visibleIds[position]
                    val card = holder.itemView as InterceptTouchCardView
                    card.visibility = View.VISIBLE; card.alpha = 1f; card.translationX = 0f
                    card.setTag(R.id.tag_thing_card_bound_stable_id, id)
                    card.setCardBackgroundColor(if (id < 0) 0xff93bde1.toInt() else 0xffffca83.toInt())
                    (card.getChildAt(0) as TextView).apply {
                        setTextColor(Color.DKGRAY)
                        text = (if (id < 0) "虚构文件夹 $id" else "虚构记事 $id") +
                            if (id == -4L) "\n保留匹配内容" else "\n" + "测试内容\n".repeat((position % 3) + 1)
                    }
                }
            }
        }
        animator = ThingListRemovalAnimator(this, list)
        val root = FrameLayout(this)
        root.addView(list)
        if (intent.getBooleanExtra("exit_chrome", false)) {
            normalChrome = ParticleChromeProbe.addNormal(root, "普通顶栏", 96)
            normalChrome.forEach { it.visibility = View.INVISIBLE }
        }
        contextualChrome = ParticleChromeProbe.add(root, "验证 $mode：点击执行", 96).apply {
            findViewById<View>(R.id.contextual_toolbar).apply {
                contentDescription = "执行虚构批量操作"
                setOnClickListener { confirm() }
            }
        }
        setContentView(root)
        list.post {
            if (mode == "partial") list.scrollBy(0, dp(156))
            File(output, "ready.json").writeText(JSONObject().put("case", mode)
                .put("style", ThingAnimationPreferences.swipe(this))
                .put("texture", ParticleCardSurfaceHandoff.forceTextureForTests)
                .put("cellDp", ParticlePerfTuning.microflakeCellDpNow).toString())
        }
    }

    private fun confirm() {
        if (animator.isRunning || finished) return
        val dialog = AlertDialogFragment()
        dialog.setTitle("仅操作虚构列表")
        dialog.setContent("不会读取或修改任何现有记事。")
        dialog.setConfirmText("确定")
        dialog.setConfirmListener(object : AlertDialogFragment.ConfirmListener {
            override fun onConfirm() {
                started = SystemClock.uptimeMillis()
                val remove = when (mode) {
                    "single" -> setOf(1L)
                    "offscreen" -> setOf(18L)
                    "all" -> ids.toSet()
                    "partial" -> setOf(1L, 3L, -8L, 18L)
                    else -> setOf(1L, 3L, -8L, 18L)
                }
                animator.start(before = { frozen = ids.toList(); frozen!! },
                    change = { ids = ids.filterNot { it in remove }; ids },
                    publish = { diff ->
                        reflow = SystemClock.uptimeMillis() - started
                        frozen = null
                        diff.dispatchUpdatesTo(list.adapter!!)
                        if (normalChrome.isNotEmpty()) {
                            normalChrome.forEach { it.visibility = View.VISIBLE }
                            contextualChrome?.apply {
                                animation = android.view.animation.AnimationUtils.loadAnimation(
                                    this@ThingListRemovalProbeActivity, R.anim.contextual_toolbar_hide)
                                animation.start()
                                visibility = View.INVISIBLE
                            }
                        }
                    })
                sample()
            }
        })
        dialog.show(supportFragmentManager, "probe-confirm")
    }

    private fun sample() {
        if (finished) return
        val children = JSONArray()
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            children.put(JSONObject().put("id", child.getTag(R.id.tag_thing_card_bound_stable_id))
                .put("top", child.y).put("alpha", child.alpha).put("visible", child.visibility))
        }
        samples.put(JSONObject().put("at", SystemClock.uptimeMillis() - started)
            .put("layers", ParticleDismissOverlay.activeIn(this).size).put("cards", children)
            .put("chrome", JSONArray((normalChrome + listOfNotNull(contextualChrome)).map {
                JSONObject().put("id", it.id).put("alpha", it.alpha).put("transitionAlpha", it.transitionAlpha)
            })))
        if (!animator.isRunning && reflow > 0 && SystemClock.uptimeMillis() - started > reflow + 500) {
            finished = true
            File(output, "result.json").writeText(JSONObject().put("case", mode).put("reflowMs", reflow)
                .put("remaining", JSONArray(ids)).put("samples", samples)
                .put("suppressed", list.isLayoutSuppressed).toString())
        } else list.postOnAnimation { sample() }
    }

    override fun onPause() { animator.finish(); super.onPause() }
    override fun onDestroy() {
        ParticleCardSurfaceHandoff.forceTextureForTests = false
        super.onDestroy()
    }
}
