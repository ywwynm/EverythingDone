package com.ywwynm.everythingdone.spatial

import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.Surface
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.PI

class SpatialPhotoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs), SensorEventListener {

    private val spatialRenderer = SpatialPhotoRenderer()
    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    private var tiltEnabled = false
    private var sensorRegistered = false
    private var recenterSensor = true
    private var centerPitch = 0f
    private var centerRoll = 0f
    private var sensorX = 0f
    private var sensorY = 0f
    private var lastSensorTimestampNanos = 0L
    private var manualBaseX = 0f
    private var manualBaseY = 0f
    private var currentX = 0f
    private var currentY = 0f
    private var touching = false
    private var sceneBitmap: Bitmap? = null
    private var sceneLdiLite: SpatialLdiLiteData? = null
    private var mpiBuilding = false
    private var mpiGeneration = 0
    private var downX = 0f
    private var downY = 0f
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchMoved = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        setEGLContextClientVersion(2)
        // 优先申请 4x MSAA：断边处的网格三角形边缘在部分视点下呈像素锯齿，多重采样
        // 直接平滑多边形边缘；设备不支持时回退到普通配置。
        setEGLConfigChooser(MsaaConfigChooser())
        preserveEGLContextOnPause = true
        setRenderer(spatialRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        isClickable = true
        isFocusable = true
    }

    fun setScene(
        bitmap: Bitmap,
        depth: SpatialDepthData,
        strength: Float,
        ldiLite: SpatialLdiLiteData? = null,
        renderMode: SpatialRenderMode = SpatialRenderMode.resolve(
            value = null,
            hasLdiLite = ldiLite != null
        )
    ) {
        resetViewpoint()
        sceneBitmap = bitmap
        sceneLdiLite = ldiLite
        mpiGeneration++
        spatialRenderer.setMpiPlanes(null)
        spatialRenderer.setScene(bitmap, depth, strength, ldiLite, renderMode)
        if (renderMode == SpatialRenderMode.MPI) ensureMpiPlanes()
        requestRender()
    }

    fun setStrength(value: Float) {
        spatialRenderer.setStrength(value)
        requestRender()
    }

    fun setRenderMode(mode: SpatialRenderMode) {
        spatialRenderer.setRenderMode(mode)
        if (mode == SpatialRenderMode.MPI) ensureMpiPlanes()
        requestRender()
    }

    /** MPI 平面按场景惰性构建；构建期间先以双层显示（renderer 内兜底）。 */
    private fun ensureMpiPlanes() {
        val bitmap = sceneBitmap ?: return
        val ldiLite = sceneLdiLite ?: return
        if (mpiBuilding) return
        mpiBuilding = true
        val generation = mpiGeneration
        Thread({
            val planes = runCatching {
                SpatialMpiBuilder.build(bitmap, ldiLite)
            }.getOrNull()
            post {
                mpiBuilding = false
                if (planes != null && generation == mpiGeneration) {
                    spatialRenderer.setMpiPlanes(planes)
                    requestRender()
                }
            }
        }, "SpatialMpiBuild").start()
    }

    fun activate(useDeviceTilt: Boolean) {
        tiltEnabled = useDeviceTilt
        recenterSensor = true
        lastSensorTimestampNanos = 0L
        if (useDeviceTilt && rotationSensor != null && !sensorRegistered) {
            sensorRegistered = sensorManager.registerListener(
                this,
                rotationSensor,
                SensorManager.SENSOR_DELAY_GAME
            )
        }
        onResume()
        requestRender()
    }

    fun deactivate() {
        if (sensorRegistered) {
            sensorManager.unregisterListener(this)
            sensorRegistered = false
        }
        onPause()
    }

    fun releaseRenderer() {
        if (sensorRegistered) {
            sensorManager.unregisterListener(this)
            sensorRegistered = false
        }
        queueEvent { spatialRenderer.release() }
    }

    fun hasTiltSensor(): Boolean = rotationSensor != null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                touching = true
                downX = event.x
                downY = event.y
                touchStartX = currentX
                touchStartY = currentY
                touchMoved = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!touching) return false
                if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) {
                    touchMoved = true
                }
                val x = touchStartX + (event.x - downX) / width.coerceAtLeast(1) * TOUCH_GAIN
                val y = touchStartY - (event.y - downY) / height.coerceAtLeast(1) * TOUCH_GAIN
                applyViewpoint(x, y)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!touching) return false
                touching = false
                manualBaseX = currentX
                manualBaseY = currentY
                sensorX = 0f
                sensorY = 0f
                recenterSensor = true
                lastSensorTimestampNanos = 0L
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP && !touchMoved) performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!tiltEnabled || touching || event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR) return
        val rotationMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        val adjusted = FloatArray(9)
        remapForDisplay(rotationMatrix, adjusted)
        val orientation = FloatArray(3)
        SensorManager.getOrientation(adjusted, orientation)
        val pitch = orientation[1]
        val roll = orientation[2]
        if (recenterSensor) {
            centerPitch = pitch
            centerRoll = roll
            sensorX = 0f
            sensorY = 0f
            recenterSensor = false
            lastSensorTimestampNanos = event.timestamp
            applyViewpoint(manualBaseX, manualBaseY)
            return
        }

        // P1（design-2026-08-03）：满量程 0.16→0.5 rad（≈28°），tanh 软饱和替代硬钳。
        // 0.16 时手持约 9° 即打满，之后运动戛然而止、对角先后撞轴形成折角（用户
        // 反馈的"跳变/硬截断"主力）；tanh 小角近似线性、大角渐进 ±1，无饱和拐点。
        val targetX = kotlin.math.tanh(
            wrapAngle(roll - centerRoll) / SENSOR_FULL_SCALE_RADIANS
        )
        val targetY = kotlin.math.tanh(
            -wrapAngle(pitch - centerPitch) / SENSOR_FULL_SCALE_RADIANS
        )
        val filterAlpha = SpatialSensorSmoothing.alpha(
            event.timestamp - lastSensorTimestampNanos
        )
        lastSensorTimestampNanos = event.timestamp
        sensorX += (targetX - sensorX) * filterAlpha
        sensorY += (targetY - sensorY) * filterAlpha
        applyViewpoint(manualBaseX + sensorX, manualBaseY + sensorY)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun resetViewpoint() {
        manualBaseX = 0f
        manualBaseY = 0f
        sensorX = 0f
        sensorY = 0f
        currentX = 0f
        currentY = 0f
        recenterSensor = true
        lastSensorTimestampNanos = 0L
        spatialRenderer.setViewpoint(0f, 0f)
    }

    private fun applyViewpoint(x: Float, y: Float) {
        // P1：视点限制在单位圆内而非方形域。方形域的对角偏移可达轴向的 sqrt(2) 倍，
        // 方向手感不一致，还迫使取景边距按对角最坏情况放大静止裁切。
        var clampedX = x.coerceIn(-1f, 1f)
        var clampedY = y.coerceIn(-1f, 1f)
        val radius = kotlin.math.hypot(clampedX, clampedY)
        if (radius > 1f) {
            clampedX /= radius
            clampedY /= radius
        }
        currentX = clampedX
        currentY = clampedY
        spatialRenderer.setViewpoint(currentX, currentY)
        requestRender()
    }

    @Suppress("DEPRECATION")
    private fun remapForDisplay(input: FloatArray, output: FloatArray) {
        val rotation = if (android.os.Build.VERSION.SDK_INT >= 30) {
            context.display?.rotation ?: Surface.ROTATION_0
        } else {
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .defaultDisplay.rotation
        }
        val axes = when (rotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(input, axes.first, axes.second, output)
    }

    private fun wrapAngle(value: Float): Float {
        var result = value
        val full = (2.0 * PI).toFloat()
        while (result > PI) result -= full
        while (result < -PI) result += full
        return result
    }

    companion object {
        private const val TOUCH_GAIN = 2.6f
        private const val SENSOR_FULL_SCALE_RADIANS = 0.5f
    }

    /** 4x MSAA 优先、逐级回退的 EGL 配置选择器。 */
    private class MsaaConfigChooser : EGLConfigChooser {
        override fun chooseConfig(
            egl: javax.microedition.khronos.egl.EGL10,
            display: javax.microedition.khronos.egl.EGLDisplay
        ): javax.microedition.khronos.egl.EGLConfig {
            val attempts = listOf(4, 2, 0)
            for (samples in attempts) {
                val spec = buildList {
                    addAll(
                        listOf(
                            javax.microedition.khronos.egl.EGL10.EGL_RED_SIZE, 8,
                            javax.microedition.khronos.egl.EGL10.EGL_GREEN_SIZE, 8,
                            javax.microedition.khronos.egl.EGL10.EGL_BLUE_SIZE, 8,
                            javax.microedition.khronos.egl.EGL10.EGL_ALPHA_SIZE, 8,
                            javax.microedition.khronos.egl.EGL10.EGL_DEPTH_SIZE, 16,
                            javax.microedition.khronos.egl.EGL10.EGL_RENDERABLE_TYPE,
                            EGL_OPENGL_ES2_BIT
                        )
                    )
                    if (samples > 0) {
                        addAll(
                            listOf(
                                javax.microedition.khronos.egl.EGL10.EGL_SAMPLE_BUFFERS, 1,
                                javax.microedition.khronos.egl.EGL10.EGL_SAMPLES, samples
                            )
                        )
                    }
                    add(javax.microedition.khronos.egl.EGL10.EGL_NONE)
                }.toIntArray()
                val count = IntArray(1)
                if (!egl.eglChooseConfig(display, spec, null, 0, count) || count[0] <= 0) {
                    continue
                }
                val configs = arrayOfNulls<javax.microedition.khronos.egl.EGLConfig>(count[0])
                if (egl.eglChooseConfig(display, spec, configs, count[0], count)) {
                    configs[0]?.let { return it }
                }
            }
            error("没有可用的 EGL 配置")
        }

        private companion object {
            const val EGL_OPENGL_ES2_BIT = 4
        }
    }
}
