package com.ywwynm.everythingdone.views.particledismiss

import android.opengl.GLES30
import android.opengl.GLES31
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * EGL 线程内保存和倒序恢复原状态；程序由渲染器预编译并统一释放。
 *
 * 第二批起历史缓冲本身来自 [ParticleMicroflakeRenderer.HistoryPool]（常驻、只增不减，D4）；
 * 范围表仍随会话建立与销毁，它只有 `count × 16` 字节。
 */
internal class ParticleReverseHistory(
    private val program: Int,
    val plan: ParticleReversePlan,
    private val pool: ParticleMicroflakeRenderer.HistoryPool,
    private val uniforms: MutableMap<String, Int>
) : Closeable {
    private var rangeBuffer = 0
    private var historyBuffer = 0
    private var countLocation = 0
    private var frameLocation = 0
    private var restoreLocation = 0
    private var fractionLocation = 0

    /** 历史缓冲那一次 glBufferData 的实际耗时（毫秒），供性能记录使用；命中池时为 0。 */
    var allocMs = 0.0
        private set

    init {
        try {
            val limit = IntArray(1)
            GLES30.glGetIntegerv(GLES31.GL_MAX_SHADER_STORAGE_BLOCK_SIZE, limit, 0)
            check(plan.bytes <= limit[0]) { "逆向轨迹缓存超过设备单缓冲上限：${plan.bytes}/${limit[0]}" }
            val id = IntArray(1)
            GLES30.glGenBuffers(1, id, 0)
            rangeBuffer = id[0]
            val ranges = ByteBuffer.allocateDirect(plan.ranges.size * 4).order(ByteOrder.nativeOrder())
            ranges.asIntBuffer().put(plan.ranges)
            GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, rangeBuffer)
            GLES30.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, ranges.capacity(), ranges, GLES30.GL_STATIC_DRAW)
            val allocStarted = System.nanoTime()
            historyBuffer = pool.acquire(plan.bytes)
            allocMs = (System.nanoTime() - allocStarted) / 1e6
            countLocation = location("count")
            frameLocation = location("frame")
            restoreLocation = location("restore")
            fractionLocation = location("fraction")
            applyUniforms()
            check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "无法分配逆向轨迹缓存" }
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    private fun location(name: String) =
        uniforms.getOrPut("$program:$name") { GLES30.glGetUniformLocation(program, name) }

    /** 共享程序被别的会话改过之后要把 count 写回来。 */
    fun applyUniforms() {
        GLES30.glUseProgram(program)
        GLES30.glUniform1i(countLocation, plan.count)
    }

    /** 只绑定范围表与历史缓冲；积分着色器自己写历史时用（压力更新会占掉绑定 4/5/6）。 */
    fun bindTargets() {
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 4, rangeBuffer)
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 5, historyBuffer)
    }

    /** 调用方先绑定原材料和当前状态，缓存操作只改变绑定 4、5。 */
    fun transfer(frame: Int, restore: Boolean, fraction: Float = 0f) {
        bindTargets()
        GLES30.glUseProgram(program)
        GLES30.glUniform1i(countLocation, plan.count)
        GLES30.glUniform1i(frameLocation, frame)
        GLES30.glUniform1i(restoreLocation, if (restore) 1 else 0)
        GLES30.glUniform1f(fractionLocation, fraction)
        GLES31.glDispatchCompute((plan.count + 255) / 256, 1, 1)
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
    }

    override fun close() {
        if (rangeBuffer != 0) {
            GLES30.glDeleteBuffers(1, intArrayOf(rangeBuffer), 0)
            rangeBuffer = 0
        }
        if (historyBuffer != 0) {
            pool.release(historyBuffer)
            historyBuffer = 0
        }
    }
}
