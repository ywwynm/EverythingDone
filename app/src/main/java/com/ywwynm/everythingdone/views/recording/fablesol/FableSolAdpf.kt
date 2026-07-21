package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import androidx.annotation.RequiresApi

/**
 * ADPF 性能提示会话：把 GL 线程与 [FableSolRowParallel] 常驻 worker 绑进同一个
 * hint session，目标时长为 120Hz 一帧的 CPU 预算，逐帧上报实际 work 时长。
 *
 * 真机 HUD 已确认 work 尖峰是乘性的（各段 p95/p50 同为约 1.8~2 倍，来自 DVFS
 * 降频与调度抖动，而不是 GC 类加性事件）；hint session 让调速器知道帧截止时间，
 * 从源头压 p95，减少「漏帧 → 系统内容检测降档到 60」的触发源。
 *
 * 所有方法只能在 GL 线程调用（tid 绑定依赖 [Process.myTid]）。不支持的设备或
 * 运行时安全退化为空操作；会话在 detach 时释放，重新 attach 后按新 GL 线程重建。
 * session 字段声明为 Any?，与 [FableSolGlRenderThread] 的 VsyncProbe 同一模式，
 * 避免低版本设备在类加载期解析 API 31 类型。
 */
internal class FableSolAdpf(context: Context) {

    private val appContext = context.applicationContext
    private var session: Any? = null
    private var creationFailed = false

    /** 渲染帧开始前调用：worker tid 注册齐后建会话；已建则为空操作。 */
    fun onFrameStart() {
        if (Build.VERSION.SDK_INT >= 31) ensureSession31()
    }

    /** 渲染帧收尾后上报本帧实际 CPU work 时长。 */
    fun reportWork(workNs: Long) {
        if (workNs <= 0L) return
        if (Build.VERSION.SDK_INT >= 31) report31(workNs)
    }

    /** GL 线程销毁前释放；下一次 attach 会按新线程 id 重建。 */
    fun release() {
        if (Build.VERSION.SDK_INT >= 31) release31()
    }

    @RequiresApi(31)
    private fun ensureSession31() {
        if (creationFailed || session != null) return
        // 等全部常驻 worker 完成 tid 注册再建会话（通常前几帧内就绪），避免只绑到
        // GL 线程；期间每帧的这次检查只是一个数组快照，可忽略。
        val workerTids = FableSolRowParallel.workerThreadIds()
        if (workerTids.size < FableSolRowParallel.activeWorkerCount) return
        try {
            val manager = appContext.getSystemService(PerformanceHintManager::class.java)
            if (manager == null) {
                creationFailed = true
                return
            }
            val tids = IntArray(workerTids.size + 1)
            tids[0] = Process.myTid()
            workerTids.copyInto(tids, 1)
            val created = manager.createHintSession(tids, TARGET_WORK_NANOS)
            if (created == null) {
                creationFailed = true
            } else {
                session = created
            }
        } catch (_: Throwable) {
            creationFailed = true
        }
    }

    @RequiresApi(31)
    private fun report31(workNs: Long) {
        val current = session as? PerformanceHintManager.Session ?: return
        try {
            current.reportActualWorkDuration(workNs)
        } catch (_: Throwable) {
            // 系统侧会话失效（如电源服务重启）；放弃本会话，不再逐帧重试。
            session = null
            creationFailed = true
        }
    }

    @RequiresApi(31)
    private fun release31() {
        val current = session as? PerformanceHintManager.Session
        session = null
        creationFailed = false
        try {
            current?.close()
        } catch (_: Throwable) {
            // 释放路径上的异常无害，忽略。
        }
    }

    private companion object {
        /** 120Hz 一帧的 CPU work 预算；派发被降到 60 时也维持该目标，帮助回升。 */
        const val TARGET_WORK_NANOS = 8_333_333L
    }
}
