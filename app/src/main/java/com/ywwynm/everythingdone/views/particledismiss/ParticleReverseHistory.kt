package com.ywwynm.everythingdone.views.particledismiss

import android.opengl.GLES30
import android.opengl.GLES31
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** EGL 线程内保存和倒序恢复原状态；程序由渲染器预编译并统一释放。 */
internal class ParticleReverseHistory(private val program: Int, val plan: ParticleReversePlan) : Closeable {
    private val buffers = IntArray(2)
    private var frameLocation = 0
    private var restoreLocation = 0

    init {
        try {
            val limit = IntArray(1)
            GLES30.glGetIntegerv(GLES31.GL_MAX_SHADER_STORAGE_BLOCK_SIZE, limit, 0)
            check(plan.bytes <= limit[0]) { "逆向轨迹缓存超过设备单缓冲上限：${plan.bytes}/${limit[0]}" }
            GLES30.glGenBuffers(2, buffers, 0)
            val ranges = ByteBuffer.allocateDirect(plan.ranges.size * 4).order(ByteOrder.nativeOrder())
            ranges.asIntBuffer().put(plan.ranges)
            GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffers[0])
            GLES30.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, ranges.capacity(), ranges, GLES30.GL_STATIC_DRAW)
            GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffers[1])
            GLES30.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, plan.bytes, null, GLES30.GL_DYNAMIC_DRAW)
            GLES30.glUseProgram(program)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "count"), plan.count)
            frameLocation = GLES30.glGetUniformLocation(program, "frame")
            restoreLocation = GLES30.glGetUniformLocation(program, "restore")
            check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "无法分配逆向轨迹缓存" }
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    /** 调用方先绑定原材料和当前状态，缓存操作只改变绑定 4、5。 */
    fun transfer(frame: Int, restore: Boolean) {
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 4, buffers[0])
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 5, buffers[1])
        GLES30.glUseProgram(program)
        GLES30.glUniform1i(frameLocation, frame)
        GLES30.glUniform1i(restoreLocation, if (restore) 1 else 0)
        GLES31.glDispatchCompute((plan.count + 255) / 256, 1, 1)
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
    }

    override fun close() {
        GLES30.glDeleteBuffers(buffers.size, buffers, 0)
        buffers.fill(0)
    }
}
