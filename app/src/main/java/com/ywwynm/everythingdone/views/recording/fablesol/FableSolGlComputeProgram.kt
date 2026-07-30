package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.res.AssetManager
import android.opengl.GLES30
import android.opengl.GLES31

/**
 * GLES 3.1 compute program 封装。
 *
 * 与 [FableSolGlProgram] 分开：compute shader 不能与顶点/片元着色器链进同一个 program，
 * 而且它只在 3.1 及以上可用——[create] 因此返回可空值，调用方据此选择兼容后端，而不是让
 * 整条通路崩在链接失败上。
 */
internal class FableSolGlComputeProgram private constructor(val id: Int) {

    private val uniforms = HashMap<String, Int>()

    fun use() {
        GLES30.glUseProgram(id)
    }

    fun uniform(name: String): Int = uniforms.getOrPut(name) {
        GLES30.glGetUniformLocation(id, name).also { location ->
            check(location >= 0) { "Missing GLSL uniform $name" }
        }
    }

    fun release() {
        GLES30.glDeleteProgram(id)
    }

    companion object {

        /** @return null 表示本机没有可用的 compute 通路；调用方退到 GLES 3.0 兼容后端。 */
        fun create(assets: AssetManager, computeAsset: String): FableSolGlComputeProgram? {
            val source = try {
                assets.open(computeAsset).bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (ignored: Throwable) {
                return null
            }
            val shader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
            if (shader == 0) return null
            GLES31.glShaderSource(shader, source)
            GLES31.glCompileShader(shader)
            val status = IntArray(1)
            GLES31.glGetShaderiv(shader, GLES31.GL_COMPILE_STATUS, status, 0)
            if (status[0] != GLES31.GL_TRUE) {
                GLES31.glDeleteShader(shader)
                return null
            }
            val program = GLES31.glCreateProgram()
            if (program == 0) {
                GLES31.glDeleteShader(shader)
                return null
            }
            GLES31.glAttachShader(program, shader)
            GLES31.glLinkProgram(program)
            GLES31.glGetProgramiv(program, GLES31.GL_LINK_STATUS, status, 0)
            GLES31.glDeleteShader(shader)
            if (status[0] != GLES31.GL_TRUE) {
                GLES31.glDeleteProgram(program)
                return null
            }
            return FableSolGlComputeProgram(program)
        }

        /**
         * 本机 GL 上下文**实际**取得的版本是否达到 3.1。
         *
         * 不看 `EGL_CONTEXT_CLIENT_VERSION`：那里请求的是 3，驱动通常返回它支持的最高 3.x，
         * 但"通常"不是保证。D104 的门控必须落在真实版本上，所以直接查 `GL_VERSION`。
         */
        fun supportsCompute(): Boolean {
            val version = GLES30.glGetString(GLES30.GL_VERSION) ?: return false
            val match = Regex("OpenGL ES (\\d+)\\.(\\d+)").find(version) ?: return false
            val major = match.groupValues[1].toIntOrNull() ?: return false
            val minor = match.groupValues[2].toIntOrNull() ?: return false
            return major > 3 || (major == 3 && minor >= 1)
        }
    }
}
