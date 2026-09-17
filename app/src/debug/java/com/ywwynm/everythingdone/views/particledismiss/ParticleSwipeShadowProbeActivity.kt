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
import android.widget.TextView
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
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val run = intent.getStringExtra("run_id") ?: "latest"
        require(run.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        output = File(getExternalFilesDir(null), "swipe-shadow-probe/$run").apply { mkdirs() }
        swipe = ParticleSwipeAnimator(this)
        list = StablerRecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ParticleSwipeShadowProbeActivity)
            setBackgroundColor(Color.rgb(244, 244, 244))
            setPadding(dp(48), dp(120), dp(48), dp(48))
            clipToPadding = false
        }
        returning = ThingSwipeReturn(this, list)
        list.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = 1
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val card = InterceptTouchCardView(parent.context).apply {
                    val requestedWidth = intent.getIntExtra("card_width_dp", 0)
                    layoutParams = RecyclerView.LayoutParams(
                        if (requestedWidth > 0) dp(requestedWidth) else ViewGroup.LayoutParams.MATCH_PARENT, dp(180))
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
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
        }
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder) = false
            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) {
                val done = {
                    completions++
                    list.adapter!!.notifyItemChanged(0)
                    returning.start(0, particles = true)
                }
                if (!swipe.finishSwipe(holder.itemView, list.width, done)) done()
            }
            override fun onChildDraw(c: Canvas, rv: RecyclerView, holder: RecyclerView.ViewHolder,
                                     dx: Float, dy: Float, state: Int, active: Boolean) {
                if (dx > 0f) swipe.reset(holder.itemView)
                val particles = dx <= 0f && swipe.draw(holder.itemView, dx)
                super.onChildDraw(c, rv, holder, if (particles || dx > 0f) 0f else dx, dy, state, active)
                if (!particles) holder.itemView.alpha = 1f
            }
            override fun clearView(rv: RecyclerView, holder: RecyclerView.ViewHolder) {
                swipe.reset(holder.itemView)
                super.clearView(rv, holder)
                holder.itemView.alpha = 1f
            }
        }).attachToRecyclerView(list)
        setContentView(list)
        list.post {
            startedAt = SystemClock.uptimeMillis()
            val card = list.findViewHolderForAdapterPosition(0)!!.itemView
            val at = IntArray(2).also { card.getLocationOnScreen(it) }
            val parents = JSONArray()
            var ancestor: View? = card
            while (ancestor != null) {
                parents.put(JSONObject().put("class", ancestor.javaClass.simpleName).put("z", ancestor.z)
                    .put("alpha", ancestor.alpha).put("scale", ancestor.scaleX))
                ancestor = ancestor.parent as? View
            }
            File(output, "ready.json").writeText(JSONObject().put("x", at[0]).put("y", at[1])
                .put("width", card.width).put("height", card.height).put("parents", parents)
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
            inputs.put(JSONArray(listOf(SystemClock.uptimeMillis() - startedAt,
                event.actionMasked, event.rawX, event.rawY)))
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val card = list.findViewHolderForAdapterPosition(0)?.itemView
                if (card != null) swipe.warm(card, event)
                finishAt = Long.MAX_VALUE
            } else swipe.recordTouch(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                finishAt = SystemClock.uptimeMillis() + 3000L
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun sample() {
        if (stopped || isDestroyed) return
        if (SystemClock.uptimeMillis() >= finishAt) {
            stopped = true
            File(output, "result.json").writeText(JSONObject().put("frames", frames).put("inputs", inputs)
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
        if (!copying && card != null && card.width > dp(160)) {
            val at = IntArray(2).also { list.getLocationInWindow(it) }
            val x = at[0] + card.left + card.width / 2
            val y = at[1] + card.top
            // 卡片粒子使用 TextureView，Window PixelCopy 同时包含它与真实卡片，检查合成后的阴影。
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

    override fun onDestroy() {
        stopped = true
        capturedFrames.forEach { it.second.recycle() }
        capturedFrames.clear()
        if (::swipe.isInitialized) swipe.reset()
        if (::returning.isInitialized) returning.cancel()
        super.onDestroy()
    }
}
