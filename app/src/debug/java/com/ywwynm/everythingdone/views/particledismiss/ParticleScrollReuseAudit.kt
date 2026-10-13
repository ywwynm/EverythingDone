package com.ywwynm.everythingdone.views.particledismiss

import android.app.UiAutomation
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.ThingsActivity
import com.ywwynm.everythingdone.managers.ModeManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/** 真正按下、停留、滚动后的画面审计。仅供隔离包使用，不通过重绑或 invalidate 掩盖异常。 */
internal object ParticleScrollReuseAudit {
    fun run(activity: ThingsActivity, list: RecyclerView, mode: ModeManager, args: Bundle,
            output: File, main: (() -> Unit) -> Unit, automation: UiAutomation) {
        check(activity.packageName == "com.ywwynm.everythingdone.batchvalidation")
        val events = java.util.Collections.synchronizedList(mutableListOf<JSONObject>())
        val frameTimes = java.util.Collections.synchronizedList(mutableListOf<Double>())
        val metricThread = android.os.HandlerThread("ScrollAuditMetrics").apply { start() }
        val frameListener = android.view.Window.OnFrameMetricsAvailableListener { _, metric, _ ->
            frameTimes.add(metric.getMetric(android.view.FrameMetrics.TOTAL_DURATION) / 1e6)
        }
        main {
            ParticlePresentationDiagnostics.bind(activity, object : ParticlePresentationDiagnostics.Run {
                override val options = ParticlePresentationPolicy.debugOptions(false)
                override fun event(name: String, values: Map<String, Any?>) {
                    events.add(JSONObject(values).put("name", name))
                }
                override fun claimImage(name: String) = false
                override fun image(name: String, producer: () -> Bitmap?) = Unit
            })
            activity.window.addOnFrameMetricsAvailableListener(frameListener, android.os.Handler(metricThread.looper))
        }
        val observations = JSONArray()
        val failures = JSONArray()
        var gestureDetails = JSONObject()
        val density = activity.resources.displayMetrics.density
        fun screenshot(label: String): Bitmap = checkNotNull(automation.takeScreenshot()).also {
            File(output, "$label.png").outputStream().use { stream -> it.compress(Bitmap.CompressFormat.PNG, 100, stream) }
        }
        fun inspect(index: Int, save: Boolean = false) {
            val rows = JSONArray()
            main {
                for (i in 0 until list.childCount) {
                    val v = list.getChildAt(i)
                    val id = v.getTag(R.id.tag_thing_card_bound_stable_id) as? Long ?: continue
                    if (id !in 8_000_001L..8_000_032L) continue
                    val rect = Rect(); if (!v.getGlobalVisibleRect(rect)) continue
                    val at = IntArray(2); v.getLocationOnScreen(at)
                    val outline=android.graphics.Outline().apply { alpha=1f; v.outlineProvider?.getOutline(v,this) }
                    val outlineRect=Rect(); outline.getRect(outlineRect)
                    rows.put(JSONObject().put("id", id).put("view", System.identityHashCode(v))
                        .put("rect", JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom)))
                        .put("x", at[0]).put("y", at[1]).put("width", v.width).put("height", v.height)
                        .put("alpha", v.alpha).put("transitionAlpha", v.transitionAlpha)
                        .put("elevation", v.elevation).put("translationZ", v.translationZ)
                        .put("scaleX", v.scaleX).put("scaleY", v.scaleY)
                        .put("visibility", v.visibility).put("pressed", v.isPressed)
                        .put("layer", v.layerType).put("hasTransientState", v.hasTransientState())
                        .put("backgroundClass",v.background?.javaClass?.simpleName).put("backgroundAlpha",v.background?.alpha)
                        .put("backgroundBounds",v.background?.bounds?.toShortString())
                        .put("outline",outlineRect.toShortString()).put("outlineRadius",outline.radius).put("outlineAlpha",outline.alpha))
                }
            }
            val bitmap = checkNotNull(automation.takeScreenshot())
            val step = JSONObject().put("index", index).put("cards", rows)
                .put("gesture", gestureDetails)
            main { step.put("overlays",ParticleDismissOverlay.activeIn(activity).size) }
            var failed = false
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val r = row.getJSONArray("rect")
                val left = maxOf(r.getInt(0) + (16*density).toInt(), 0)
                val right = minOf(r.getInt(2) - (16*density).toInt(), bitmap.width)
                val top = maxOf(r.getInt(1) + (12*density).toInt(), (104*density).toInt())
                val bottom = minOf(r.getInt(3) - (12*density).toInt(), bitmap.height - (112*density).toInt())
                if (right-left<24*density || bottom-top<10*density) continue
                val id=row.getLong("id")
                val expected=intArrayOf(0xff2c82a9.toInt(),0xffd06b38.toInt(),0xff64904b.toInt())[((id-8_000_000)%3).toInt()]
                var count=0; var matches=0
                for (y in top until bottom step 4) for (x in left until right step 4) {
                    val c=bitmap.getPixel(x,y); count++
                    if (abs(Color.red(c)-Color.red(expected))<=5 && abs(Color.green(c)-Color.green(expected))<=5 &&
                        abs(Color.blue(c)-Color.blue(expected))<=5) matches++
                }
                val fraction=matches.toDouble()/count.coerceAtLeast(1)
                row.put("backgroundMatch",fraction)
                if (fraction<.3) { failed=true; failures.put(JSONObject().put("step",index).put("card",row)) }
            }
            observations.put(step)
            if (failed || save) File(output,"screen-$index.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            if(failed) main {
                File(output,"failed-nodes.json").writeText(SelectionCardAppearanceAudit.snapshot(list).toString())
            }
            bitmap.recycle()
            File(output,"scroll-audit.json").writeText(JSONObject().put("samples",observations).put("failures",failures).toString())
        }
        main { (list.layoutManager as StaggeredGridLayoutManager).scrollToPositionWithOffset(11,0) }
        SystemClock.sleep(550)
        inspect(-1, true)
        check(failures.length()==0) { "正常参考像素不正确" }
        File(output,"ready.json").writeText("{}")
        if (args.getString("wait_start")=="true") {
            val deadline=SystemClock.uptimeMillis()+10000
            while (!File(output,"start").exists() && SystemClock.uptimeMillis()<deadline) SystemClock.sleep(20)
            check(File(output,"start").exists())
        }
        val rounds=args.getString("rounds")?.toInt() ?: 24
        val horizontal = args.getString("gesture") == "horizontal"
        var direction=-1
        for (index in 0 until rounds) {
            var x=0f; var y=0f; var distance=0f
            main {
                list.stopScroll()
                if (!list.canScrollVertically(-direction)) direction=-direction
                val cards=(0 until list.childCount).map { list.getChildAt(it) }.filter {
                    val id=it.getTag(R.id.tag_thing_card_bound_stable_id) as? Long
                    id in 8_000_001L..8_000_032L && it.height>24*density && it.bottom>list.height*.4f && it.top<list.height*.85f
                }
                val v=cards.getOrNull(index%cards.size) ?: error("无可用手势卡片")
                val at=IntArray(2); v.getLocationOnScreen(at)
                x=at[0]+v.width*.53f
                y=(at[1]+v.height*.4f).coerceIn(230*density,list.height-150*density)
                distance=if (horizontal) v.width * (if (index % 2 == 0) -.16f else .16f) else if(direction<0) minOf(list.height*.48f,y-140*density) else minOf(list.height*.48f,list.height-110*density-y)
            }
            val down=SystemClock.uptimeMillis()
            main {
                val actual=(0 until list.childCount).map { list.getChildAt(it) }.lastOrNull {
                    val bounds=Rect(); it.getGlobalVisibleRect(bounds) && bounds.contains(x.toInt(),y.toInt())
                }
                gestureDetails=JSONObject().put("downX",x).put("downY",y).put("direction",direction)
                    .put("pressedId",actual?.getTag(R.id.tag_thing_card_bound_stable_id))
                    .put("pressMs",args.getString("press_ms")?.toLong() ?: longArrayOf(0,90,210,350,430,560)[index%6])
            }
            fun touch(action:Int,xx:Float,yy:Float) {
                val e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,xx,yy,0).apply { source=InputDevice.SOURCE_TOUCHSCREEN }
                check(automation.injectInputEvent(e,true)); e.recycle()
            }
            touch(MotionEvent.ACTION_DOWN,x,y)
            SystemClock.sleep(args.getString("press_ms")?.toLong() ?: longArrayOf(0,90,210,350,430,560)[index%6])
            for (j in 1..20) {
                touch(MotionEvent.ACTION_MOVE,
                    x + if (horizontal) distance*j/20f else if(index%4==0) -density*j*.3f else 0f,
                    y + if (horizontal) 0f else direction*distance*j/20f)
                SystemClock.sleep(12)
            }
            touch(MotionEvent.ACTION_UP, x + if (horizontal) distance else 0f,
                y + if (horizontal) 0f else direction*distance)
            SystemClock.sleep(if (horizontal) 1400 else 350)
            main {
                list.stopScroll()
                if (mode.getCurrentMode()!=ModeManager.NORMAL) mode.backNormalMode(0)
            }
            SystemClock.sleep(420)
            inspect(index,index==rounds-1)
            if (failures.length()>0) break
            if (horizontal) main { list.scrollBy(0, (if (index % 4 < 2) 1 else -1) * list.height / 4) }
        }
        screenshot("final").recycle()
        if (horizontal) check(events.any { it.optString("name") == "swipe_pixels_visible" }) { "没有实际呈现粒子" }
        main {
            activity.window.removeOnFrameMetricsAvailableListener(frameListener)
            ParticlePresentationDiagnostics.unbind(activity)
        }
        metricThread.quitSafely()
        File(output,"capture-events.json").writeText(JSONArray(events.toList()).toString())
        File(output,"frame-times.json").writeText(JSONArray(frameTimes.toList()).toString())
        check(failures.length()==0) { "滚动后实际卡片像素缺失：${failures.length()}" }
    }

}
