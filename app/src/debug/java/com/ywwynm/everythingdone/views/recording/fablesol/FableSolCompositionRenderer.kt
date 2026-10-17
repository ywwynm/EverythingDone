package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.res.AssetManager
import android.opengl.GLES30
import android.view.Surface
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 固定诊断图的唯一 GL 实现，SurfaceView 与直接 SurfaceControl 共用。只编入 debug。 */
internal class FableSolCompositionRenderer(
    assets: AssetManager,
    surface: Surface,
    preferPq: Boolean,
    private val cornerRadiusPx: Float,
    private val event: (String, JSONObject) -> Unit
) : AutoCloseable {
    private var egl: FableSolEglSession? = null
    private var program: FableSolGlProgram? = null
    private var texture = 0
    @Volatile var presentedSquareCorners: Boolean? = null
        private set
    val output: FableSolScreenOutput get() = checkNotNull(egl).output
    val diagnostic: String get() = checkNotNull(egl).diagnostic

    init {
        try {
            egl = FableSolEglSession(surface, preferPq, preferPq)
            program = FableSolGlProgram(assets, "fablesol/glsl/fullscreen.vert", "fablesol/glsl/present.frag")
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            texture = ids[0]
            event("egl", JSONObject().put("verifiedOutput", output.name)
                .put("diagnostic", diagnostic).put("glRenderer", GLES30.glGetString(GLES30.GL_RENDERER))
                .put("glVersion", GLES30.glGetString(GLES30.GL_VERSION)))
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    fun draw(width: Int, height: Int, comparison: JSONObject, square: Boolean) {
        val radius = if (square) 0f else cornerRadiusPx
        val textureWidth = 256
        val textureHeight = maxOf(64, (textureWidth.toFloat() * height / width).toInt())
        val data = ByteBuffer.allocateDirect(textureWidth * textureHeight * 16).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (y in 0 until textureHeight) for (x in 0 until textureWidth) {
            val u = (x + 0.5f) / textureWidth
            val v = (y + 0.5f) / textureHeight
            val whiteCircle = (u - 0.75f) * (u - 0.75f) +
                (v - 0.25f) * (v - 0.25f) * height * height / (width.toFloat() * width) < 0.075f * 0.075f
            val highlight = u > 0.45f && u < 0.55f && v > 0.65f && v < 0.75f
            val rgb = when {
                highlight -> floatArrayOf(4f, 4f, 4f)
                v > 0.5f || whiteCircle -> floatArrayOf(1f, 1f, 1f)
                else -> floatArrayOf(0.55f + 0.25f * u, 0.04f + 0.08f * u, 0.15f + 0.5f * u)
            }
            data.put(rgb).put(1f)
        }
        data.flip()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, textureWidth, textureHeight,
            0, GLES30.GL_RGBA, GLES30.GL_FLOAT, data)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        val shader = checkNotNull(program)
        shader.use()
        GLES30.glUniform1i(shader.uniform("uScene"), 0)
        GLES30.glUniform3f(shader.uniform("uBackdropColor"), 1f, 1f, 1f)
        GLES30.glUniform1f(shader.uniform("uPresentationAlpha"), 1f)
        GLES30.glUniform2f(shader.uniform("uViewportPx"), width.toFloat(), height.toFloat())
        GLES30.glUniform1f(shader.uniform("uCornerRadiusPx"), radius)
        GLES30.glUniform1f(shader.uniform("uCornerRadiusBottomDeltaPx"), 0f)
        val pq = egl!!.output == FableSolScreenOutput.PQ
        GLES30.glUniform1i(shader.uniform("uSceneLinear"), 1)
        GLES30.glUniform1i(shader.uniform("uOutputPq"), if (pq) 1 else 0)
        GLES30.glUniform1i(shader.uniform("uOutputSdr"), if (pq) 0 else 1)
        GLES30.glUniform1i(shader.uniform("uPqRgba8"), if (pq) 1 else 0)
        GLES30.glUniform1f(shader.uniform("uHdrHeadroom"), 4f)
        repeat(2) {
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            if (it == 0) {
                val samples = JSONObject()
                listOf("pq_white" to floatArrayOf(0.75f, 0.25f),
                    "backdrop_white" to floatArrayOf(0.5f, 0.85f),
                    "highlight_4x" to floatArrayOf(0.5f, 0.7f),
                    "corner" to floatArrayOf(0f, 0f)).forEach { (name, xy) ->
                    val pixel = ByteBuffer.allocateDirect(4)
                    GLES30.glReadPixels((width * xy[0]).toInt(), (height * xy[1]).toInt(), 1, 1,
                        GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixel)
                    samples.put(name, JSONArray((0..3).map { index -> pixel.get(index).toInt() and 255 }))
                }
                val error = GLES30.glGetError()
                event("gl_readback", JSONObject()
                    .put("comparison", comparison).put("shaderCornerRadiusPx", radius)
                    .put("rgba8", samples).put("error", error).put("contentReferenceWhiteNits", 203))
                check(error == GLES30.GL_NO_ERROR) { "GL error $error" }
            }
            check(egl!!.swapBuffers(System.nanoTime())) { "swapBuffers failed" }
        }
        presentedSquareCorners = square
    }

    override fun close() {
        presentedSquareCorners = null
        try {
            program?.release()
            program = null
            if (texture != 0) GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
            texture = 0
        } finally {
            egl?.release()
            egl = null
        }
    }
}
