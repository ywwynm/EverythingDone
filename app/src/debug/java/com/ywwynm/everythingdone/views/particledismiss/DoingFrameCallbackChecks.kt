package com.ywwynm.everythingdone.views.particledismiss

import android.app.Activity
import android.app.Instrumentation
import android.view.Choreographer
import android.view.ViewGroup
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.views.DoingRecyclerView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 在真实 UI 线程验证帧投递的取消、重复、重入及生命周期约定；不读取或修改记事。 */
internal object DoingFrameCallbackChecks {
    fun verify(instrumentation: Instrumentation, activity: Activity) {
        val list = activity.findViewById<DoingRecyclerView>(R.id.rv_thing_doing)
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun await(latch: CountDownLatch) = check(latch.await(3, TimeUnit.SECONDS)) { "帧回调未完成" }
        fun nextFrame() {
            val latch = CountDownLatch(1)
            main { Choreographer.getInstance().postFrameCallback { latch.countDown() } }
            await(latch)
        }
        val cancelled = AtomicInteger()
        val action = Runnable { cancelled.incrementAndGet() }
        main {
            repeat(3) { list.postOnAnimation(action) }
            check(list.removeCallbacks(action))
            // View 的投递／取消也允许由非 UI 线程调用。
            Thread {
                list.postOnAnimation(action)
                list.removeCallbacks(action)
            }.apply { start(); join() }
        }
        nextFrame()
        check(cancelled.get() == 0) { "已取消的帧回调仍然执行" }
        val duplicateCount = AtomicInteger()
        val duplicateLatch = CountDownLatch(2)
        val duplicate = Runnable { duplicateCount.incrementAndGet(); duplicateLatch.countDown() }
        main { repeat(2) { list.postOnAnimation(duplicate) } }
        await(duplicateLatch)
        nextFrame()
        check(duplicateCount.get() == 2) { "重复投递未按次数执行" }
        val reentrant = CountDownLatch(3)
        val repeatAction = object : Runnable {
            override fun run() {
                reentrant.countDown()
                if (reentrant.count > 0) list.postOnAnimation(this)
            }
        }
        main { list.postOnAnimation(repeatAction) }
        await(reentrant)
        val preAttach = CountDownLatch(1)
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        lateinit var temporary: DoingRecyclerView
        main {
            temporary = DoingRecyclerView(activity)
            temporary.postOnAnimation { preAttach.countDown() }
            root.addView(temporary, ViewGroup.LayoutParams(1, 1))
        }
        try {
            await(preAttach)
            main {
                temporary.postOnAnimation(action)
                root.removeView(temporary)
            }
            nextFrame()
            check(cancelled.get() == 0) { "卸载后仍执行旧回调" }
        } finally {
            main { if (temporary.parent === root) root.removeView(temporary) }
        }
    }
}
