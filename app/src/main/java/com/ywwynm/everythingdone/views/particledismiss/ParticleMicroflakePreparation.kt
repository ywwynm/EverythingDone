package com.ywwynm.everythingdone.views.particledismiss

import android.content.res.AssetManager
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask

/** 快照就绪后立即建材，与叠加层布局和 EGL 建链重叠；不在主线程等待。 */
internal class ParticleMicroflakePreparation(
    assets: AssetManager,
    private val density: Float,
    spec: ParticleDismissSpec
) {
    private val started = System.nanoTime()
    @Volatile var buildMs = 0.0
        private set
    private val task = FutureTask {
        // 这段计算直接阻塞用户可见首帧，使用显示任务优先级，而非普通后台任务。
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
        ParticleMicroflakeRenderer.fromSpec(assets, 0, 0, density, spec).also {
            buildMs = (System.nanoTime() - started) / 1e6
        }
    }.also { executor.execute(it) }

    val isFinished get() = task.isDone

    fun await(width: Int, height: Int): ParticleMicroflakeRenderer.Input {
        val scale = (density / 1.875f).coerceAtLeast(.5f)
        return task.get().copy(frameWidth = width / scale, frameHeight = height / scale)
    }

    companion object {
        // 快速连续关闭也限制并发。任务只保留本次快照，不保留 Activity 或 View。
        private val executor = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "ParticleMaterial").apply { isDaemon = true }
        }
    }
}
