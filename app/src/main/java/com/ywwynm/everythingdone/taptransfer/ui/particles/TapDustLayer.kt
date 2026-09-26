package com.ywwynm.everythingdone.taptransfer.ui.particles

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.TextureView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 接收状态下屏幕四周的光尘带、带底下的柔光与天线处的扩散圈，在 GPU 上画（第三版）。
 *
 * 光尘有两三万片，逐片求值（流场噪声、圆角矩形上取点、翻转、调色板）在 CPU 上每帧要十几到四十毫秒（2026-09-25
 * 三星实测），而且等待被碰可能持续几分钟。这里把 [TapDustBand] 的逐片常数一次性传给 GPU，顶点着色器按同一套
 * 闭式公式按时间求值；柔光与扩散圈照模拟器的片元着色器画。CPU 端的 [TapDustBand] 只留给光尘接手用（交接的颗粒
 * 要出现在那一片光尘当时的位置），两边公式逐项相同。
 *
 * 一个透明的 `TextureView` 叠在罩层之上、按钮之下，与模拟器的层次一致；自带一条 GL 线程。主线程每帧给一份
 * [Frame]（[render]），GL 线程取最新的一份画出来。用 GPU 画也避开了 Flyme 的强制深色（它会改画笔与渐变的颜色）。
 */
@SuppressLint("ViewConstructor")
internal class TapDustLayer(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {

    /** 一帧的输入：在主线程取好，GL 线程只读。长度一律是舞台像素。 */
    class Frame(
        /** 舞台时钟（秒）。 */
        val t: Float,
        val spread: Float,
        val diffuseT: Float,
        /** 整条带的不透明度（出现、退场）；没被接手的光尘向外退去的像素与不透明度倍数。 */
        val bandAlpha: Float,
        val retreat: Float,
        val retreatAlpha: Float,
        /** 倒放走到哪儿：被接手的光尘过了各自的交接位置就不再画。 */
        val tau: Float,
        val palette: IntArray,
        /** 调色板转过的圈数（光尘与柔光共用）。 */
        val turn: Float,
        /** 柔光：中线离屏幕边缘的像素、高斯宽度、峰值不透明度（0 = 不画）。 */
        val glowCenter: Float,
        val glowSigma: Float,
        val glowPeak: Float,
        /** 扩散圈：圆心与逐圈的（半径, 不透明度），颜色 ARGB。 */
        val antennaX: Float,
        val antennaY: Float,
        val rings: FloatArray,
        val ringTint: Int
    ) {
        /** 这一帧什么都不画（带、柔光、扩散圈都看不见）。 */
        val empty: Boolean
            get() = bandAlpha <= .003f && glowPeak <= .002f && (0 until rings.size / 2).none { rings[it * 2 + 1] > .003f }
    }

    private val thread = HandlerThread("TapDustGl", Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
    private val gl = Handler(thread.looper)
    private val latest = AtomicReference<Frame?>(null)
    private val drawPending = AtomicBoolean(false)
    private val drawTask = Runnable { drawPending.set(false); draw() }
    private var released = false

    // ---- 以下只在 GL 线程上用
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var flakeProgram = 0
    private var glowProgram = 0
    private var ringProgram = 0
    private var vao = 0
    private var staticVbo = 0
    private var dynamicVbo = 0
    private var uploadedCount = 0
    private var pendingBand: TapDustBand? = null
    private var pendingHide: FloatArray? = null
    private var hideDirty = false
    private var band: TapDustBand? = null
    private var blankShown = true
    private val uniforms = HashMap<String, Int>()
    private val paletteScratch = FloatArray(MAX_PALETTE * 3)

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    /** 换一圈光尘（进入接收、旋转屏幕之后）：逐片常数重新上传。 */
    fun setBand(next: TapDustBand) {
        gl.post {
            pendingBand = next
            pendingHide = null
            hideDirty = true
        }
    }

    /** 光尘接手：被接手的那几片各自隐去时的倒放位置（其余正无穷）；为 null 时都不隐去。 */
    fun setHideTau(hideTau: FloatArray?) {
        gl.post {
            pendingHide = hideTau
            hideDirty = true
        }
    }

    /** 主线程每帧调用：记下最新的一份，GL 线程空下来就画。 */
    fun render(frame: Frame) {
        if (released) return
        latest.set(frame)
        if (drawPending.compareAndSet(false, true)) gl.post(drawTask)
    }

    fun release() {
        if (released) return
        released = true
        gl.post {
            destroySurface()
            destroyContext()
            thread.quitSafely()
        }
    }

    // ---------------------------------------------------------------- TextureView

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        gl.post {
            surfaceWidth = width
            surfaceHeight = height
            if (!ensureContext()) return@post
            destroySurface()
            surface = EGL14.eglCreateWindowSurface(display, config, texture, intArrayOf(EGL14.EGL_NONE), 0)
            if (surface == EGL14.EGL_NO_SURFACE) {
                Log.w(TAG, "光尘层建不起窗口表面：0x${Integer.toHexString(EGL14.eglGetError())}")
                return@post
            }
            blankShown = false
            draw()
        }
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        gl.post {
            surfaceWidth = width
            surfaceHeight = height
        }
    }

    /** 返回之后不能再往这个表面上画：同步等 GL 线程撤掉表面。 */
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        if (released || !thread.isAlive) return true
        val done = CountDownLatch(1)
        gl.post {
            destroySurface()
            done.countDown()
        }
        done.await(500, TimeUnit.MILLISECONDS)
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}

    // ---------------------------------------------------------------- GL 线程

    private fun ensureContext(): Boolean {
        if (context != EGL14.EGL_NO_CONTEXT) return true
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return false
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val found = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, found, 0) || found[0] == 0) return false
        config = configs[0]
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        return context != EGL14.EGL_NO_CONTEXT
    }

    private fun destroySurface() {
        if (surface == EGL14.EGL_NO_SURFACE) return
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        surface = EGL14.EGL_NO_SURFACE
    }

    private fun destroyContext() {
        if (context == EGL14.EGL_NO_CONTEXT) return
        EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
        context = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
        flakeProgram = 0
        glowProgram = 0
        ringProgram = 0
        vao = 0
        uploadedCount = 0
    }

    private fun draw() {
        if (surface == EGL14.EGL_NO_SURFACE || surfaceWidth <= 0 || surfaceHeight <= 0) return
        val frame = latest.get() ?: return
        // 看不见的时候只清一次，之后不再交换缓冲（省电）
        if (frame.empty && blankShown) return
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return
        if (flakeProgram == 0 && !buildPrograms()) return
        uploadIfNeeded()
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if (!frame.empty) {
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            val palette = packPalette(frame.palette)
            if (frame.glowPeak > .002f) drawGlow(frame, palette)
            if (frame.bandAlpha > .003f && uploadedCount > 0) drawFlakes(frame, palette)
            drawRings(frame)
            GLES30.glDisable(GLES30.GL_BLEND)
        }
        EGL14.eglSwapBuffers(display, surface)
        blankShown = frame.empty
    }

    private fun uploadIfNeeded() {
        pendingBand?.let { next ->
            band = next
            pendingBand = null
            if (vao == 0) {
                val ids = IntArray(1)
                GLES30.glGenVertexArrays(1, ids, 0)
                vao = ids[0]
                val buffers = IntArray(2)
                GLES30.glGenBuffers(2, buffers, 0)
                staticVbo = buffers[0]
                dynamicVbo = buffers[1]
                GLES30.glBindVertexArray(vao)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, staticVbo)
                for (k in 0..2) {
                    GLES30.glEnableVertexAttribArray(k)
                    GLES30.glVertexAttribPointer(k, 4, GLES30.GL_FLOAT, false, STATIC_FLOATS * 4, k * 16)
                    GLES30.glVertexAttribDivisor(k, 1)
                }
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, dynamicVbo)
                GLES30.glEnableVertexAttribArray(3)
                GLES30.glVertexAttribPointer(3, 4, GLES30.GL_FLOAT, false, DYNAMIC_FLOATS * 4, 0)
                GLES30.glVertexAttribDivisor(3, 1)
                GLES30.glBindVertexArray(0)
            }
            // 流场噪声的排列表：与 CPU 端（光尘接手）同一个种子、同一张表
            GLES30.glUseProgram(flakeProgram)
            GLES30.glUniform4iv(u(flakeProgram, "perm"), 128, FlowNoise(next.noiseSeed).permutation(), 0)
            val data = next.packStatic()
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, staticVbo)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, floats(data), GLES30.GL_STATIC_DRAW)
            uploadedCount = next.count
            hideDirty = true
        }
        if (hideDirty) {
            val current = band ?: return
            val data = current.packDynamic(pendingHide)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, dynamicVbo)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, floats(data), GLES30.GL_DYNAMIC_DRAW)
            hideDirty = false
        }
    }

    private fun drawFlakes(frame: Frame, palette: Int) {
        val band = band ?: return
        val p = flakeProgram
        GLES30.glUseProgram(p)
        GLES30.glUniform2f(u(p, "screen"), surfaceWidth.toFloat(), surfaceHeight.toFloat())
        GLES30.glUniform1f(u(p, "t"), frame.t)
        GLES30.glUniform1f(u(p, "spread"), frame.spread)
        GLES30.glUniform1f(u(p, "diffuse"), frame.diffuseT)
        GLES30.glUniform1f(u(p, "band_alpha"), frame.bandAlpha)
        GLES30.glUniform1f(u(p, "retreat"), frame.retreat)
        GLES30.glUniform1f(u(p, "retreat_alpha"), frame.retreatAlpha)
        GLES30.glUniform1f(u(p, "tau"), frame.tau)
        GLES30.glUniform1f(u(p, "turn"), frame.turn)
        GLES30.glUniform1f(u(p, "inset"), band.inset)
        GLES30.glUniform1f(u(p, "perimeter"), band.perimeterPx)
        GLES30.glUniform1f(u(p, "corner"), band.corner)
        GLES30.glUniform1f(u(p, "rounding"), TapDustBand.ROUNDING)
        GLES30.glUniform4f(u(p, "band"), band.bandPx, band.spreadPx, band.rest, band.speedPx)
        GLES30.glUniform2f(u(p, "wander"), band.wanderPx, 1f / (90f * band.density))
        GLES30.glUniform3fv(u(p, "palette"), MAX_PALETTE, paletteScratch, 0)
        GLES30.glUniform1i(u(p, "palette_count"), palette)
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, uploadedCount)
        GLES30.glBindVertexArray(0)
    }

    private fun drawGlow(frame: Frame, palette: Int) {
        val band = band ?: return
        val p = glowProgram
        GLES30.glUseProgram(p)
        GLES30.glUniform2f(u(p, "screen"), surfaceWidth.toFloat(), surfaceHeight.toFloat())
        GLES30.glUniform3fv(u(p, "palette"), MAX_PALETTE, paletteScratch, 0)
        GLES30.glUniform1i(u(p, "palette_count"), palette)
        GLES30.glUniform1f(u(p, "rotation"), frame.turn)
        GLES30.glUniform1f(u(p, "corner"), band.corner)
        GLES30.glUniform1f(u(p, "center_inset"), frame.glowCenter)
        GLES30.glUniform1f(u(p, "sigma"), frame.glowSigma.coerceAtLeast(1f))
        GLES30.glUniform1f(u(p, "peak"), frame.glowPeak)
        GLES30.glUniform1f(u(p, "rounding"), TapDustBand.ROUNDING)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    private fun drawRings(frame: Frame) {
        val rings = frame.rings
        if (rings.isEmpty()) return
        val p = ringProgram
        GLES30.glUseProgram(p)
        GLES30.glUniform2f(u(p, "screen"), surfaceWidth.toFloat(), surfaceHeight.toFloat())
        GLES30.glUniform2f(u(p, "center"), frame.antennaX, frame.antennaY)
        val tint = frame.ringTint
        GLES30.glUniform3f(u(p, "tint"), ((tint shr 16) and 0xFF) / 255f, ((tint shr 8) and 0xFF) / 255f,
            (tint and 0xFF) / 255f)
        for (k in 0 until rings.size / 2) {
            val radius = rings[k * 2]
            val alpha = rings[k * 2 + 1]
            if (alpha <= .003f || radius <= .5f) continue
            GLES30.glUniform1f(u(p, "radius"), radius)
            GLES30.glUniform1f(u(p, "alpha"), alpha)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }
    }

    /** 调色板写进 [paletteScratch]（0–1），返回色数。 */
    private fun packPalette(palette: IntArray): Int {
        val n = palette.size.coerceIn(1, MAX_PALETTE)
        for (k in 0 until n) {
            val c = palette[k]
            paletteScratch[k * 3] = ((c shr 16) and 0xFF) / 255f
            paletteScratch[k * 3 + 1] = ((c shr 8) and 0xFF) / 255f
            paletteScratch[k * 3 + 2] = (c and 0xFF) / 255f
        }
        return n
    }

    private fun u(program: Int, name: String): Int =
        uniforms.getOrPut("$program/$name") { GLES30.glGetUniformLocation(program, name) }

    private fun buildPrograms(): Boolean {
        uniforms.clear()
        flakeProgram = link(FLAKE_VS, FLAKE_FS)
        glowProgram = link(FULL_VS, GLOW_FS)
        ringProgram = link(RING_VS, RING_FS)
        if (flakeProgram == 0 || glowProgram == 0 || ringProgram == 0) {
            flakeProgram = 0
            return false
        }
        return true
    }

    private fun link(vertex: String, fragment: String): Int {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertex)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragment)
        if (vs == 0 || fs == 0) return 0
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.w(TAG, "光尘层着色器链接失败：${GLES30.glGetProgramInfoLog(program)}")
            GLES30.glDeleteProgram(program)
            return 0
        }
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.w(TAG, "光尘层着色器编译失败：${GLES30.glGetShaderInfoLog(shader)}")
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    companion object {
        private const val TAG = "TapDust"
        private const val MAX_PALETTE = 12

        /** 逐片常数（每片 12 个 float）与会变的那一份（每片 4 个 float），见 [TapDustBand.packStatic]。 */
        const val STATIC_FLOATS = 12
        const val DYNAMIC_FLOATS = 4

        private fun floats(data: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(data)
                position(0)
            }

        /**
         * 光尘的一片：与 [TapDustBand.Evaluator] 逐项相同的闭式求值（出场、跨带相位、沿边流动、流场漂移、
         * 圆角矩形上取点、翻转、转角、调色板），与弹窗微片同一形状。看不见的片直接丢到裁剪范围外。
         */
        internal const val FLAKE_VS = """#version 300 es
precision highp float;
precision highp int;
layout(location=0) in vec4 a0;
layout(location=1) in vec4 a1;
layout(location=2) in vec4 a2;
layout(location=3) in vec4 a3;
uniform vec2 screen;
uniform float t,spread,diffuse,band_alpha,retreat,retreat_alpha,tau,turn;
uniform float inset,perimeter,corner,rounding;
uniform vec4 band;
uniform vec2 wander;
uniform vec3 palette[12];
uniform int palette_count;
uniform ivec4 perm[128];
out vec2 local;
out vec3 v_color;
out float v_alpha;
out float v_glint;
const vec2 corners[4]=vec2[4](vec2(-.5,-.5),vec2(.5,-.5),vec2(-.5,.5),vec2(.5,.5));
int perm_at(int i){ivec4 v=perm[i>>2];int k=i&3;return k==0?v.x:(k==1?v.y:(k==2?v.z:v.w));}
vec2 gradient(int g){float a=(float(g)+.5)*0.39269908;return vec2(cos(a),sin(a));}
float fade(float x){return x*x*x*(x*(x*6.-15.)+10.);}
float fade_slope(float x){return 30.*x*x*(x*(x-2.)+1.);}
vec2 noise_derivative(vec2 p){
    vec2 f0=floor(p);
    int ix=int(f0.x)&255;
    int iy=int(f0.y)&255;
    vec2 f=p-f0;
    vec2 g00=gradient(perm_at(perm_at(ix)+iy)%16);
    vec2 g10=gradient(perm_at(perm_at(ix+1)+iy)%16);
    vec2 g01=gradient(perm_at(perm_at(ix)+iy+1)%16);
    vec2 g11=gradient(perm_at(perm_at(ix+1)+iy+1)%16);
    float a=dot(g00,f);
    float b=dot(g10,vec2(f.x-1.,f.y));
    float c=dot(g01,vec2(f.x,f.y-1.));
    float d=dot(g11,f-1.);
    float u=fade(f.x),v=fade(f.y),du=fade_slope(f.x),dv=fade_slope(f.y);
    float k=a-b-c+d;
    vec2 kk=g00-g10-g01+g11;
    return vec2(g00.x+u*(g10.x-g00.x)+v*(g01.x-g00.x)+u*v*kk.x+du*((b-a)+v*k),
                g00.y+u*(g10.y-g00.y)+v*(g01.y-g00.y)+u*v*kk.y+dv*((c-a)+u*k));
}
vec2 curl(vec2 p,float time){
    vec2 d1=noise_derivative(vec2(p.x+time*.31,p.y-time*.23));
    vec2 d2=noise_derivative(vec2(p.x*2.03-time*.19+17.1,p.y*2.03+time*.27+3.7));
    vec2 d=d1+d2*(.5*2.03);
    return vec2(d.y,-d.x);
}
vec2 corner_point(vec2 c,float start,float along,float rad){
    float a=start+(rad>0.?along/rad:0.);
    return c+vec2(cos(a),sin(a))*rad;
}
vec2 inset_round_rect(float frac,float depth){
    float f=fract(frac);
    float l=depth,tp=depth,r=screen.x-depth,b=screen.y-depth;
    float rad=max(corner-depth,0.)+rounding*depth;
    rad=max(min(rad,min(r-l,b-tp)*.5),0.);
    float sw=max(r-l-2.*rad,0.);
    float sh=max(b-tp-2.*rad,0.);
    float arc=1.5707963*rad;
    float d=f*(2.*sw+2.*sh+4.*arc);
    if(d<=sw)return vec2(l+rad+d,tp);
    d-=sw;
    if(d<=arc)return corner_point(vec2(r-rad,tp+rad),-1.5707963,d,rad);
    d-=arc;
    if(d<=sh)return vec2(r,tp+rad+d);
    d-=sh;
    if(d<=arc)return corner_point(vec2(r-rad,b-rad),0.,d,rad);
    d-=arc;
    if(d<=sw)return vec2(r-rad-d,b);
    d-=sw;
    if(d<=arc)return corner_point(vec2(l+rad,b-rad),1.5707963,d,rad);
    d-=arc;
    if(d<=sh)return vec2(l,b-rad-d);
    d-=sh;
    return corner_point(vec2(l+rad,tp+rad),3.1415927,d,rad);
}
void main(){
    // a0：周长初相、跨带初相、沿边速度倍数、向内频率
    // a1：尺寸（像素）、初始转角、转速、翻转角速度
    // a2：翻转初相、自身不透明度、出场次序、取色
    // a3：漂移种子 u、v、明暗、隐去时的倒放位置（没被接手的是一个很大的数）
    float present=band.z+(1.-band.z)*min(spread,1.);
    float edge_fade=clamp((present-a2.z)/.08,0.,1.);
    float q=fract(a0.y+a0.w*max(diffuse,0.));
    float back=pow(clamp((q-.3)/.7,0.,1.),1.3);
    float inner=(1.-back)*sqrt(clamp(q/.04,0.,1.));
    bool kept=a3.w<1e8;
    float alpha=band_alpha*a2.y*inner*edge_fade;
    if(!kept)alpha*=retreat_alpha;
    if(alpha<=.004||(kept&&tau>=a3.w)){gl_Position=vec4(2.,2.,2.,1.);return;}
    float reach=band.x+band.y*spread;
    float frac=a0.x+a0.z*band.w*t/perimeter;
    vec2 c=curl(vec2(frac*perimeter*wander.y+a3.x,q*3.+a3.y),t*.45);
    float depth=inset+reach*pow(q,1.6)+c.y*wander.x-(kept?0.:retreat);
    frac+=c.x*wander.x*.6/perimeter;
    vec2 pos=inset_round_rect(frac,depth);
    float facing=abs(cos(a2.x+a1.w*t));
    float squash=.35+.65*facing;
    float f2=facing*facing;float f4=f2*f2;
    float angle=a1.y+a1.z*t;
    vec2 corner_v=corners[gl_VertexID];
    vec2 qv=vec2(corner_v.x*squash,corner_v.y)*a1.x;
    float ca=cos(angle),sa=sin(angle);
    vec2 p=pos+vec2(ca*qv.x-sa*qv.y,sa*qv.x+ca*qv.y);
    gl_Position=vec4(p.x/screen.x*2.-1.,1.-p.y/screen.y*2.,0.,1.);
    // 按绕屏幕中心的方位取调色板（随时间转动），每片只取相邻两色之一，逐片略有明暗
    float position=atan(pos.y-screen.y*.5,pos.x-screen.x*.5)/6.2831853-turn;
    float fp=fract(position)*float(palette_count);
    int k=clamp(int(floor(fp)),0,palette_count-1);
    int pick=a2.w<fp-float(k)?(k+1)%palette_count:k;
    v_color=clamp(palette[pick]*a3.z,0.,1.);
    v_alpha=alpha;
    v_glint=f4*f4*f4;
    local=corner_v+.5;
}
"""

        internal const val FLAKE_FS = """#version 300 es
precision mediump float;
in vec2 local;
in vec3 v_color;
in float v_alpha;
in float v_glint;
out vec4 frag;
// material.frag 的老化微片：径向截断成圆角，正对时出现白色闪光
void main(){
    vec2 q=local-.5;
    float radial=length(q*vec2(.92,.90));
    float cut=1.-smoothstep(.35,.58,radial);
    float a=v_alpha*cut;
    if(a<.002)discard;
    vec3 col=min(v_color*(1.+.30*v_glint)+vec3(.20*v_glint),vec3(1.));
    frag=vec4(col*a,a);
}
"""

        /** 盖满整层的一个三角形；p 是舞台像素（y 向下）。 */
        internal const val FULL_VS = """#version 300 es
precision highp float;
uniform vec2 screen;
out vec2 p;
void main(){
    vec2 v=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));
    p=v*screen;
    gl_Position=vec4(v.x*2.-1.,1.-v.y*2.,0.,1.);
}
"""

        /** 光尘带底下的柔光：以带的中线为轴的高斯晕，颜色是绕屏幕中心转动的调色板（与模拟器相同）。 */
        internal const val GLOW_FS = """#version 300 es
precision highp float;
uniform vec2 screen;
uniform vec3 palette[12];
uniform int palette_count;
uniform float rotation,corner,center_inset,sigma,peak,rounding;
in vec2 p;
out vec4 frag;
float sd_round(vec2 p,vec4 r,float rad){
    vec2 c=(r.xy+r.zw)*.5;vec2 h=(r.zw-r.xy)*.5;
    rad=min(rad,min(h.x,h.y));
    vec2 q=abs(p-c)-h+rad;
    return length(max(q,0.))+min(max(q.x,q.y),0.)-rad;
}
void main(){
    float r=max(corner-center_inset,0.)+rounding*center_inset;
    float sd=sd_round(p,vec4(center_inset,center_inset,screen.x-center_inset,screen.y-center_inset),r);
    if(abs(sd)>4.*sigma)discard;
    vec2 c=screen*.5;
    float turn=atan(p.y-c.y,p.x-c.x)/6.2831853;
    if(turn<0.)turn+=1.;
    float pos=fract(turn-rotation);
    float f=pos*float(palette_count);
    int k=min(int(f),palette_count-1);
    vec3 col=mix(palette[k],palette[(k+1)%palette_count],f-float(k));
    float a=peak*exp(-.5*sd*sd/(sigma*sigma));
    frag=vec4(col*a,a);
}
"""

        internal const val RING_VS = """#version 300 es
precision highp float;
uniform vec2 screen;
uniform vec2 center;
uniform float radius;
out vec2 p;
const vec2 corners[4]=vec2[4](vec2(-1.,-1.),vec2(1.,-1.),vec2(-1.,1.),vec2(1.,1.));
void main(){
    p=center+corners[gl_VertexID]*radius;
    gl_Position=vec4(p.x/screen.x*2.-1.,1.-p.y/screen.y*2.,0.,1.);
}
"""

        /** 扩散圈：与发送那张精灵图相同的细环（256 像素里环心半径 124.5、半宽 2.2），按半径缩放。 */
        internal const val RING_FS = """#version 300 es
precision highp float;
uniform vec2 center;
uniform float radius,alpha;
uniform vec3 tint;
in vec2 p;
out vec4 frag;
void main(){
    float scale=256./(2.*radius);
    vec2 s=(p-(center-radius))*scale;
    float d=abs(length(s-127.5)-124.5);
    float a=floor((1.-clamp(d-2.2+1.,0.,1.))*255.+.5)/255.*alpha;
    frag=vec4(tint*a,a);
}
"""
    }
}
