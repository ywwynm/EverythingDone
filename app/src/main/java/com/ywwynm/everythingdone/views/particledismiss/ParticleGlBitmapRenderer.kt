package com.ywwynm.everythingdone.views.particledismiss

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.Build

/** GL 线程持有像素和姿态；主线程只可提前标记关闭，阻止迟到的上传再次显示。 */
internal class ParticleGlBitmapLayer(val name: String, val order: Int, val foreground: Boolean,
                                    val underlay: Boolean = false) {
    var copyHardwareToOwned = false
    var hardwareImages: ParticleGlHardwareImages? = null
    var imported: ParticleGlHardwareImages.Image? = null
    @Volatile var closed = false
    @Volatile var group: ParticleGlBitmapGroup? = null
    var texture = 0
    var eglImage = 0L
    var width = 0
    var height = 0
    var x = 0f
    var y = 0f
    var alpha = 1f
    var visible = false
    var replacedBy: ParticleLayerSession? = null
    var replacementCaptured = false
    var uploadedFrames = 0
    var releaseSource: (() -> Unit)? = null

    fun drawThisFrame(replacementDrawn: Boolean): Boolean {
        if (replacementDrawn) visible = false
        return visible && !closed && texture != 0
    }
}

/** 同一 ImageReader 的缓冲会循环使用；导入对象复用，Image 租约仍逐帧等待 GPU 归还。 */
internal class ParticleGlHardwareImages {
    inner class Image(val texture: Int, val eglImage: Long) {
        var references = 0
        fun release() { check(references > 0); references--; if (closed) trimUnused() }
    }
    private val images = mutableMapOf<Long, Image>()
    private var closed = false

    @androidx.annotation.RequiresApi(31)
    fun acquire(buffer: android.hardware.HardwareBuffer): Image? {
        check(!closed)
        images[buffer.id]?.let {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, it.texture)
            ParticleHardwareTexture.rebind(it.eglImage)
            check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "页面硬件图像重绑失败" }
            it.references++
            return it
        }
        val texture = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        val handle = ParticleHardwareTexture.bind(ParticleGl.display.nativeHandle, buffer)
        if (handle == 0L || GLES30.glGetError() != GLES30.GL_NO_ERROR) {
            GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
            if (handle != 0L) ParticleHardwareTexture.destroy(ParticleGl.display.nativeHandle, handle)
            return null
        }
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return Image(texture, handle).also { it.references = 1; images[buffer.id] = it }
    }

    fun trimUnused() {
        val iterator = images.iterator()
        while (iterator.hasNext()) {
            val image = iterator.next().value
            if (image.references != 0) continue
            GLES30.glDeleteTextures(1, intArrayOf(image.texture), 0)
            ParticleHardwareTexture.destroy(ParticleGl.display.nativeHandle, image.eglImage)
            iterator.remove()
        }
    }

    fun close() { closed = true; trimUnused() }
}

/** 整幅诊断画面的资源一起退出；成员的异常关闭同样只能撤下整组。 */
internal class ParticleGlBitmapGroup(val layers: List<ParticleGlBitmapLayer>, val beforeClose: () -> Unit = {}) {
    @Volatile var closing = false
    init { require(layers.isNotEmpty()); layers.forEach { check(it.group == null); it.group = this } }
}

/** 将预乘 alpha 的 Android 位图直接叠入共享 SurfaceView，不建立额外合成器图层。 */
internal class ParticleGlBitmapRenderer(assets: AssetManager) {
    private val program: Int
    private val vao: Int
    private val viewport: Int
    private val rectangle: Int
    private val opacity: Int

    init {
        val shaders = mutableListOf<Int>()
        var linked = 0
        try {
            for ((type, name) in listOf(GLES30.GL_VERTEX_SHADER to "presentation-quad.vert",
                    GLES30.GL_FRAGMENT_SHADER to "presentation-quad.frag")) {
                val shader = GLES30.glCreateShader(type).also { shaders += it }
                GLES30.glShaderSource(shader, assets.open("particle-dismiss/$name").bufferedReader().use { it.readText() })
                GLES30.glCompileShader(shader)
                val status = IntArray(1)
                GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
                check(status[0] != 0) { "$name：${GLES30.glGetShaderInfoLog(shader)}" }
            }
            linked = GLES30.glCreateProgram()
            shaders.forEach { GLES30.glAttachShader(linked, it) }
            GLES30.glLinkProgram(linked)
            val status = IntArray(1)
            GLES30.glGetProgramiv(linked, GLES30.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { GLES30.glGetProgramInfoLog(linked) }
            program = linked
        } catch (failure: Exception) {
            if (linked != 0) GLES30.glDeleteProgram(linked)
            throw failure
        } finally { shaders.forEach { GLES30.glDeleteShader(it) } }
        vao = IntArray(1).also { GLES30.glGenVertexArrays(1, it, 0) }[0]
        viewport = GLES30.glGetUniformLocation(program, "viewportSize")
        rectangle = GLES30.glGetUniformLocation(program, "rectangle")
        opacity = GLES30.glGetUniformLocation(program, "opacity")
        GLES30.glUseProgram(program)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "sourceImage"), 0)
    }

    fun upload(layer: ParticleGlBitmapLayer, bitmap: Bitmap) {
        clearErrors()
        if (Build.VERSION.SDK_INT >= 31 && bitmap.config == Bitmap.Config.HARDWARE) {
            layer.hardwareImages?.let { images ->
                release(layer)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                val imported = bitmap.hardwareBuffer.use { images.acquire(it) }
                if (imported != null) {
                    layer.imported = imported
                    layer.texture = imported.texture; layer.eglImage = imported.eglImage
                    layer.width = bitmap.width; layer.height = bitmap.height
                    return
                }
            }
            // 持续整页更新复用已分配的双缓冲纹理。每帧释放再分配整页 RGBA 存储会让
            // 驱动等待前次绘制，三星长卡片配对中上传任务可阻塞 8–13ms。
            if (layer.copyHardwareToOwned && layer.texture != 0 && layer.eglImage == 0L &&
                layer.width == bitmap.width && layer.height == bitmap.height &&
                runCatching { copyHardwareIntoOwned(layer, bitmap) }.isSuccess) return
            release(layer)
            layer.texture = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, layer.texture)
            layer.eglImage = bitmap.hardwareBuffer.use { ParticleHardwareTexture.bind(ParticleGl.display.nativeHandle, it) }
            if (layer.eglImage != 0L && GLES30.glGetError() == GLES30.GL_NO_ERROR) {
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                layer.width = bitmap.width; layer.height = bitmap.height
                if (layer.copyHardwareToOwned && runCatching { copyImportedTexture(layer) }.isFailure) {
                    // 部分驱动不允许将导入图像挂到读取 FBO；仍使用相同的独立纹理所有权。
                    release(layer)
                    val pixels = checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false))
                    try { upload(layer, pixels) } finally { pixels.recycle() }
                }
                return
            }
            release(layer)
            val pixels = checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false))
            try { upload(layer, pixels) } finally { pixels.recycle() }
            return
        }
        if (layer.eglImage != 0L) release(layer)
        if (layer.texture == 0) layer.texture = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, layer.texture)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        if (layer.width == bitmap.width && layer.height == bitmap.height) GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, bitmap)
        else GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        layer.width = bitmap.width; layer.height = bitmap.height
        check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "GL 前景纹理上传失败" }
    }

    /** 持续页面直接交接 ImageReader 缓冲，不创建会参与 HWUI 同步的中间 Bitmap。 */
    @androidx.annotation.RequiresApi(31)
    fun upload(layer: ParticleGlBitmapLayer, buffer: android.hardware.HardwareBuffer) {
        clearErrors()
        release(layer)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        val imported = checkNotNull(layer.hardwareImages).acquire(buffer)
        if (imported != null) {
            layer.imported = imported
            layer.texture = imported.texture; layer.eglImage = imported.eglImage
            layer.width = buffer.width; layer.height = buffer.height
        } else {
            // 不支持直接导入的驱动继续使用原有 Bitmap／像素回退。
            val bitmap = checkNotNull(Bitmap.wrapHardwareBuffer(buffer, null))
            try { upload(layer, bitmap) } finally { bitmap.recycle() }
        }
    }

    private fun copyHardwareIntoOwned(layer: ParticleGlBitmapLayer, bitmap: Bitmap) {
        val imported = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
        val framebuffer = IntArray(1).also { GLES30.glGenFramebuffers(1, it, 0) }[0]
        var image = 0L
        try {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, imported)
            image = bitmap.hardwareBuffer.use { ParticleHardwareTexture.bind(ParticleGl.display.nativeHandle, it) }
            check(image != 0L)
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, framebuffer)
            GLES30.glFramebufferTexture2D(GLES30.GL_READ_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, imported, 0)
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_READ_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, layer.texture)
            GLES30.glCopyTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, 0, 0, layer.width, layer.height)
            check(GLES30.glGetError() == GLES30.GL_NO_ERROR)
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
            GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            GLES30.glDeleteTextures(1, intArrayOf(imported), 0)
            if (image != 0L) ParticleHardwareTexture.destroy(ParticleGl.display.nativeHandle, image)
        }
    }

    /** 硬件缓冲只参与一次 GPU 拷贝，播放时不再反复跨 HWUI／EGL 生产者采样。 */
    private fun copyImportedTexture(layer: ParticleGlBitmapLayer) {
        val imported = layer.texture
        val image = layer.eglImage
        val texture = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
        val framebuffer = IntArray(1).also { GLES30.glGenFramebuffers(1, it, 0) }[0]
        var copied = false
        try {
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, framebuffer)
            GLES30.glFramebufferTexture2D(GLES30.GL_READ_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, imported, 0)
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_READ_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            // 本路径只接收自有 RGBA_8888 ImageReader；导入未指定 EGL 色彩空间转换。
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, layer.width, layer.height)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glCopyTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, 0, 0, layer.width, layer.height)
            check(GLES30.glGetError() == GLES30.GL_NO_ERROR)
            copied = true
            layer.texture = texture; layer.eglImage = 0L
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
            GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            if (copied) {
                GLES30.glDeleteTextures(1, intArrayOf(imported), 0)
                ParticleHardwareTexture.destroy(ParticleGl.display.nativeHandle, image)
            } else GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
        }
    }

    fun draw(layers: List<ParticleGlBitmapLayer>, width: Int, height: Int, emptyTarget: Boolean = false) {
        if (layers.isEmpty()) return
        clearErrors()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glColorMask(true, true, true, true)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glUseProgram(program)
        GLES30.glBindVertexArray(vao)
        GLES30.glUniform2f(viewport, width.toFloat(), height.toFloat())
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        for ((index, layer) in layers.withIndex()) {
            // 第一层的目标全透明，直接写入与 src-over 完全一致，省去整页目标颜色的混合读取。
            if (emptyTarget && index == 0) GLES30.glDisable(GLES30.GL_BLEND) else GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, layer.texture)
            GLES30.glUniform4f(rectangle, layer.x, layer.y, layer.width.toFloat(), layer.height.toFloat())
            GLES30.glUniform1f(opacity, layer.alpha)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBindVertexArray(0)
        check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "GL 前景合成失败" }
    }

    fun release(layer: ParticleGlBitmapLayer) {
        val releaseSource = layer.releaseSource
        layer.releaseSource = null
        if (releaseSource != null) ParticleGlImageLease.releaseAfterReading(releaseSource,
            if (layer.hardwareImages != null) 1 else 4)
        val imported = layer.imported
        layer.imported = null
        if (imported != null) imported.release()
        else {
            if (layer.texture != 0) GLES30.glDeleteTextures(1, intArrayOf(layer.texture), 0)
            if (layer.eglImage != 0L) ParticleHardwareTexture.destroy(ParticleGl.display.nativeHandle, layer.eglImage)
        }
        layer.eglImage = 0L
        layer.texture = 0
        layer.width = 0; layer.height = 0
    }

    fun close() {
        GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        GLES30.glDeleteProgram(program)
    }

    private fun clearErrors() {
        var remaining = 16
        while (remaining-- > 0 && GLES30.glGetError() != GLES30.GL_NO_ERROR) Unit
    }
}
