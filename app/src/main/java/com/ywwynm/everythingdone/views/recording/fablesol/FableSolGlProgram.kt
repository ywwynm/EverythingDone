package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.res.AssetManager
import android.opengl.GLES30

/** GLSL asset 编译/链接封装；所有调用只发生在 GL 线程。 */
internal class FableSolGlProgram(
    assets: AssetManager,
    vertexAsset: String,
    fragmentAsset: String
) {

    val id: Int
    private val uniforms = HashMap<String, Int>()

    init {
        val vertex = compile(GLES30.GL_VERTEX_SHADER, assets.readText(vertexAsset), vertexAsset)
        val fragment = compile(GLES30.GL_FRAGMENT_SHADER, assets.readText(fragmentAsset), fragmentAsset)
        id = GLES30.glCreateProgram()
        check(id != 0) { "glCreateProgram failed" }
        GLES30.glAttachShader(id, vertex)
        GLES30.glAttachShader(id, fragment)
        GLES30.glLinkProgram(id)
        val status = IntArray(1)
        GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, status, 0)
        val log = GLES30.glGetProgramInfoLog(id)
        GLES30.glDeleteShader(vertex)
        GLES30.glDeleteShader(fragment)
        check(status[0] == GLES30.GL_TRUE) { "GLSL link failed: $log" }
    }

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

    private fun compile(type: Int, source: String, label: String): Int {
        val shader = GLES30.glCreateShader(type)
        check(shader != 0) { "glCreateShader failed for $label" }
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        val log = GLES30.glGetShaderInfoLog(shader)
        if (status[0] != GLES30.GL_TRUE) GLES30.glDeleteShader(shader)
        check(status[0] == GLES30.GL_TRUE) { "GLSL compile failed for $label: $log" }
        return shader
    }

    private fun AssetManager.readText(path: String): String =
        open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
}
