package com.ywwynm.everythingdone.views.recording.fablesol

import android.opengl.EGL14
import android.opengl.GLES30
import android.hardware.HardwareBuffer
import com.ywwynm.everythingdone.views.particledismiss.ParticleHardwareTexture

/** 只在 GL 队列访问。跨 HDR/SDR EGL 重建保留 HWUI 缓冲，在新上下文重新导入。 */
internal class FableSolForegroundTexture {
    @Volatile var performance: FableSolCompositionPerformance? = null
    var frame: FableSolForegroundFrame? = null
        private set
    private data class Imported(val texture: Int, val image: Long, val display: Long)
    private val textureIds = IntArray(1)
    private val imports = FableSolForegroundImportCache<HardwareBuffer, Imported>(
        create = ::createImport, rebind = ::rebindImport, destroy = ::deleteImport,
        valid = { !it.isClosed })
    private var imported: FableSolForegroundImportCache.Entry<HardwareBuffer, Imported>? = null
    private data class Retired(val imported: FableSolForegroundImportCache.Entry<HardwareBuffer, Imported>,
                               val frame: FableSolForegroundFrame, val sinceNs: Long)
    private val retirement = FableSolGpuRetirement(object : FableSolGpuRetirement.Backend {
        override fun insertFence(): Long {
            val start = if (performance != null) System.nanoTime() else 0L
            val fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
            if (fence == 0L) GLES30.glGetError() // 已由同步回退处理，不污染下一次纹理导入检查。
            performance?.sample(FableSolPerfMetric.FOREGROUND_RETIRE_CREATE, System.nanoTime() - start)
            return fence
        }
        override fun flush() = GLES30.glFlush()
        override fun poll(fence: Long): FableSolGpuRetirement.Status {
            return when (GLES30.glClientWaitSync(fence, 0, 0L)) {
                GLES30.GL_ALREADY_SIGNALED, GLES30.GL_CONDITION_SATISFIED -> {
                    performance?.count(FableSolPerfCount.FOREGROUND_RETIRE_SIGNALED)
                    FableSolGpuRetirement.Status.SIGNALED
                }
                GLES30.GL_TIMEOUT_EXPIRED -> {
                    performance?.count(FableSolPerfCount.FOREGROUND_RETIRE_PENDING)
                    FableSolGpuRetirement.Status.PENDING
                }
                else -> {
                    GLES30.glGetError()
                    FableSolGpuRetirement.Status.FAILED
                }
            }
        }
        override fun deleteFence(fence: Long) = GLES30.glDeleteSync(fence)
        override fun finish() = waitForGpu()
        override fun fallback(reason: String) {
            performance?.count(FableSolPerfCount.FOREGROUND_RETIRE_FALLBACK)
            performance?.event("foreground_retirement_fallback", reason)
        }
        override fun lastUseFenceReused() {
            performance?.count(FableSolPerfCount.FOREGROUND_RETIRE_LAST_USE)
        }
    }, release = { old: Retired ->
        imports.release(old.imported)
        performance?.sample(FableSolPerfMetric.FOREGROUND_RETIRE_LATENCY, System.nanoTime() - old.sinceNs)
        old.frame.close()
        reportImportState()
    })
    val hasRetiredBuffers: Boolean get() = retirement.pendingCount != 0

    fun update(next: FableSolForegroundFrame) {
        val old = frame
        val oldImport = imported
        frame = next
        imported = null
        if (old != null && oldImport != null) {
            val retired = Retired(oldImport, old,
                if (performance != null) System.nanoTime() else 0L)
            retirement.retire(retired)
            performance?.count(FableSolPerfCount.FOREGROUND_RETIRED)
            // 上次最终合成的标记可能已经完成，不必等本轮海浪结束才归还捕获池。
            pollRetiredBuffers()
        } else old?.close() // 未导入、未被 GPU 使用的帧无需等待。
        reportRetirementState()
    }

    fun pollRetiredBuffers() {
        if (!hasRetiredBuffers) return
        val start = if (performance != null) System.nanoTime() else 0L
        retirement.poll()
        performance?.sample(FableSolPerfMetric.FOREGROUND_RETIRE_POLL, System.nanoTime() - start)
        reportRetirementState()
    }

    fun markSampled() {
        if (imported == null || frame == null) return
        val start = if (performance != null) System.nanoTime() else 0L
        retirement.markCurrentUse()
        performance?.count(FableSolPerfCount.FOREGROUND_USE_FENCE)
        performance?.sample(FableSolPerfMetric.FOREGROUND_USE_FENCE, System.nanoTime() - start)
    }

    private fun reportRetirementState() {
        performance?.retirementState(retirement.pendingCount, retirement.asynchronous)
    }

    private fun reportImportState() {
        performance?.importCacheState(imports.size, imports.activeCount, imports.peakSize, imports.reuseEnabled)
    }

    fun texture(): Int {
        imported?.let { return it.value.texture }
        val current = frame ?: return 0
        val perf = performance
        val start = if (perf != null) System.nanoTime() else 0L
        // 捕获端已等待 HWUI fence；同一缓冲只有在本端最后使用 fence 完成并归还后才会重新交来。
        val entry = imports.acquire(current.buffer)
        imported = entry
        perf?.sample(FableSolPerfMetric.FOREGROUND_ACQUIRE, System.nanoTime() - start)
        reportImportState()
        return entry.value.texture
    }

    private fun createImport(buffer: HardwareBuffer): Imported {
        val perf = performance
        val start = if (perf != null) System.nanoTime() else 0L
        GLES30.glGenTextures(1, textureIds, 0)
        val texture = textureIds[0]
        val display = EGL14.eglGetCurrentDisplay().nativeHandle
        var image = 0L
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        try {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            image = ParticleHardwareTexture.bind(display, buffer)
            check(image != 0L) { "foreground_hardware_import_failed" }
            check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "foreground_hardware_texture_failed" }
            perf?.count(FableSolPerfCount.FOREGROUND_CACHE_CREATED)
            return Imported(texture, image, display)
        } catch (failure: Throwable) {
            // 未进入缓存的半成品也由当前上下文清理，然后沿用原失败处理。
            textureIds[0] = texture
            GLES30.glDeleteTextures(1, textureIds, 0)
            ParticleHardwareTexture.destroy(display, image)
            throw failure
        } finally {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            perf?.sample(FableSolPerfMetric.FOREGROUND_IMPORT, System.nanoTime() - start)
        }
    }

    private fun rebindImport(value: Imported): Boolean {
        val perf = performance
        val start = if (perf != null) System.nanoTime() else 0L
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, value.texture)
        try {
            ParticleHardwareTexture.rebind(value.image)
            val error = GLES30.glGetError()
            if (error != GLES30.GL_NO_ERROR) {
                perf?.count(FableSolPerfCount.FOREGROUND_CACHE_REBIND_FAILED)
                perf?.event("foreground_rebind_failed", "glError=$error fallback=fresh_import_until_context_change")
                return false
            }
            perf?.count(FableSolPerfCount.FOREGROUND_CACHE_HIT)
            return true
        } finally {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            perf?.sample(FableSolPerfMetric.FOREGROUND_REBIND, System.nanoTime() - start)
        }
    }

    fun releaseContext() {
        if (imported != null || hasRetiredBuffers) {
            // EGL 切换／销毁仍要完整收尾；当前帧保留给新上下文，旧帧全部安全归还。
            performance?.count(FableSolPerfCount.FOREGROUND_CONTEXT_FINISH)
            waitForGpu()
            retirement.drainAfterFinish()
        }
        imported?.let { imports.release(it) }
        imported = null
        imports.clearAfterFinish()
        retirement.resetForNewContext()
        reportRetirementState()
        reportImportState()
    }

    private fun waitForGpu() {
        val perf = performance
        val start = if (perf != null) System.nanoTime() else 0L
        GLES30.glFinish()
        val elapsed = if (perf != null) System.nanoTime() - start else 0L
        perf?.sample(FableSolPerfMetric.FOREGROUND_FINISH, elapsed)
        perf?.slow("foreground_finish", frame?.sequence ?: 0L, elapsed)
    }

    private fun deleteImport(value: Imported) {
        val perf = performance
        val start = if (perf != null) System.nanoTime() else 0L
        textureIds[0] = value.texture
        GLES30.glDeleteTextures(1, textureIds, 0)
        ParticleHardwareTexture.destroy(value.display, value.image)
        perf?.count(FableSolPerfCount.FOREGROUND_CACHE_DESTROYED)
        perf?.sample(FableSolPerfMetric.FOREGROUND_DELETE, System.nanoTime() - start)
    }

    fun close() { releaseContext(); frame?.close(); frame = null }
}
