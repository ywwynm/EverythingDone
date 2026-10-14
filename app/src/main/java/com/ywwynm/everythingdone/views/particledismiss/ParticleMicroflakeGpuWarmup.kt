package com.ywwynm.everythingdone.views.particledismiss

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.GLES30

/** 弹窗显示期间预热驱动的首用编译；不创建可见 Surface，也不保存用户画面。 */
internal object ParticleMicroflakeGpuWarmup {
    fun run(assets: AssetManager, materials: ParticleMicroflakeModel.Materials,
            shareContext: EGLContext = EGL14.EGL_NO_CONTEXT) {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return
        var context = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        var bitmap: Bitmap? = null
        try {
            val configs = arrayOfNulls<EGLConfig>(1); val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, 0x40, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_NONE
            ),0,configs,0,1,count,0) && count[0]>0)
            context = EGL14.eglCreateContext(display,configs[0],shareContext,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,3,EGL14.EGL_NONE),0)
            check(context != EGL14.EGL_NO_CONTEXT)
            surface = EGL14.eglCreatePbufferSurface(display,configs[0],
                intArrayOf(EGL14.EGL_WIDTH,64,EGL14.EGL_HEIGHT,64,EGL14.EGL_NONE),0)
            check(surface != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display,surface,surface,context))
            // 预热先于任何可见动画，驱动上限在这里就能记进日志。
            ParticleMicroflakeRenderer.logCapabilities()
            val resources = ParticleMicroflakeRenderer.sharedResources(assets)
            bitmap = Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).apply { eraseColor(-1) }
            val input = ParticleMicroflakeRenderer.Input(240f,320f,240f,320f,0f,0f,65f,bitmap,
                materials,resources.guide,resources.rules,confidence=resources.confidence)
            // 纹理、FBO 和会话资源仍为临时对象；共享上下文中的程序可交给正式渲染器继续使用。
            ParticleMicroflakeRenderer.Shared(assets).use { shared ->
                shared.ensure(64, 64)
                ParticleMicroflakeRenderer(assets, shared, input).use { renderer ->
                    renderer.prepare(); renderer.prepareReversePipeline(); renderer.draw(0f); GLES30.glFinish()
                }
                if (shareContext != EGL14.EGL_NO_CONTEXT) {
                    val programs = shared.compiled.toMap()
                    shared.programs.removeAll(programs.values.toSet())
                    shared.compiled.clear()
                    ParticleGl.acceptWarmPrograms(assets, programs)
                }
            }
        } finally {
            bitmap?.recycle()
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
            if(surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,surface)
            if(context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display,context)
            // display 与应用其他渲染器共用，不调用 eglTerminate。
            EGL14.eglReleaseThread()
        }
    }
}
