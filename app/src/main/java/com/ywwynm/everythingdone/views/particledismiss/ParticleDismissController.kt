package com.ywwynm.everythingdone.views.particledismiss

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.PixelCopy
import android.view.Choreographer
import android.view.ViewTreeObserver
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import com.ywwynm.everythingdone.R
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Dialog 粒子消散动画的入口与编排。
 *
 * 设计（见 docs/features/dialog-particle-dismiss/）：不延迟真实 dismiss——在
 * dismiss 发起的瞬间同步抓取 Dialog DecorView 快照，把动画层挂到宿主 Activity 的
 * DecorView 上（粒子要飘出 Dialog window 边界，不能放在 Dialog 自己的 window 里），
 * 随后真实 dismiss 照常执行。动画是纯装饰层，不参与任何生命周期。
 */
internal object ParticleDismissController {

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 尝试为 [dialog] 启动粒子消散动画。
     *
     * 返回 true 表示动画层已接管：调用方应把窗口退出动画置空并**推迟**真实
     * dismiss——[onOverlayShown] 会在动画层实际上屏后（约 1–2 帧，附 100ms
     * 兜底）在主线程回调恰好一次，调用方在其中清除自身 dim 并执行真实 dismiss。
     * 时序保证画面上每一刻都恰有一层等浓度的暗：接力 dim 先在 Dialog window
     * 底下就位（被挡、无视觉变化）→ 调用方清 dim（底层接力暗层顶上，亮度
     * 连续）→ window 移除（已无 dim 可淡出）——任何一步跨帧都不闪。
     *
     * 返回 false 表示环境不满足，调用方保持系统默认退出行为（不会回调）。
     *
     * [touchInWindow] 是最近一次触摸在 Dialog window 内的坐标，作为波前扩散起点；
     * back 键等无触点路径传 null，起点取面板中下部（按钮所在区域）。
     */
    fun start(dialog: Dialog, touchInWindow: PointF?, onOverlayShown: Runnable): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        if (!ValueAnimator.areAnimatorsEnabled()) return false

        val window = dialog.window ?: return false
        val decor = window.decorView
        if (!decor.isAttachedToWindow || decor.width <= 0 || decor.height <= 0) return false

        val activity = activityFrom(dialog.context) ?: return false
        if (activity.isFinishing || activity.isDestroyed || activity.isChangingConfigurations) {
            return false
        }
        val hostDecor = activity.window?.decorView as? ViewGroup ?: return false
        if (!hostDecor.isAttachedToWindow) return false

        // PixelCopy 抓的是 window surface 的合成结果，SurfaceView 有独立
        // surface 不在其中（快照上是空洞），此类 Dialog（音频播放/录制等）
        // 仍跳过。
        if (containsLiveSurface(decor)) return false

        // 消散快照走 PixelCopy：窗口合成结果逐像素拷贝，clipToOutline / 阴影
        // 等硬件特性完全保真。（软件 draw 不执行子 View 的 outline 裁剪，圆形
        // 按钮变方形；HardwareRenderer 离屏重渲染在三星实测返回空图。）
        // PixelCopy 异步（约 1–2 帧回调），真实 dismiss 本就延迟到 overlay
        // 上屏，这里只是同一条延迟链上多等一步。
        val snapshot = Bitmap.createBitmap(
            decor.width, decor.height, Bitmap.Config.ARGB_8888
        )
        val locationInWindow = IntArray(2)
        decor.getLocationInWindow(locationInWindow)
        val sourceRect = Rect(
            locationInWindow[0], locationInWindow[1],
            locationInWindow[0] + decor.width, locationInWindow[1] + decor.height
        )
        // PixelCopy 回调必须有超时兜底：部分设备上回调可能不来（三星实测），
        // 没有兜底 Dialog 会永远关不掉
        var copyHandled = false
        try {
            PixelCopy.request(window, sourceRect, snapshot, { result ->
                if (copyHandled) return@request
                copyHandled = true
                if (result == PixelCopy.SUCCESS &&
                    !activity.isFinishing && !activity.isDestroyed &&
                    hostDecor.isAttachedToWindow && decor.isAttachedToWindow
                ) {
                    applyRoundedCornerMask(snapshot, dialog.context)
                    attachDismissOverlay(
                        dialog, activity, hostDecor, decor, snapshot,
                        touchInWindow, onOverlayShown
                    )
                } else {
                    // 抓图失败：放行真实 dismiss（本次无粒子动画）
                    onOverlayShown.run()
                }
            }, mainHandler)
        } catch (_: Throwable) {
            return false
        }
        mainHandler.postDelayed({
            if (!copyHandled) {
                copyHandled = true
                onOverlayShown.run()
            }
        }, PIXEL_COPY_TIMEOUT_MS)
        return true
    }

    private fun attachDismissOverlay(
        dialog: Dialog,
        activity: Activity,
        hostDecor: ViewGroup,
        decor: View,
        snapshot: Bitmap,
        touchInWindow: PointF?,
        onOverlayShown: Runnable
    ) {
        val dialogLocation = IntArray(2)
        val hostLocation = IntArray(2)
        decor.getLocationOnScreen(dialogLocation)
        hostDecor.getLocationOnScreen(hostLocation)
        val originX = dialogLocation[0] - hostLocation[0]
        val originY = dialogLocation[1] - hostLocation[1]

        val density = decor.resources.displayMetrics.density
        val cellPx = adaptiveCellPx(snapshot, density)

        // 波前起点允许落在快照之外：点击 Dialog 外部时，波前从真实触点方向的
        // 边缘先咬入（限幅只防极端值）
        val waveOriginUv = if (touchInWindow != null) {
            PointF(
                (touchInWindow.x / decor.width).coerceIn(-0.5f, 1.5f),
                (touchInWindow.y / decor.height).coerceIn(-0.5f, 1.5f)
            )
        } else {
            PointF(0.5f, 0.78f)
        }

        // 消散主方向严格朝触点；触点贴近中心或无触点（返回键/代码关闭）
        // 时默认向上。受控随机由 Renderer 内的起点位置、帷幔弧度和横摆
        // 承担，不再随机旋转主方向，否则“点下方就向下、点左侧就向左”的
        // 因果关系会被稀释。
        val baseAngle = if (touchInWindow != null) {
            val dx = touchInWindow.x - decor.width / 2f
            val dy = touchInWindow.y - decor.height / 2f
            if (hypot(dx, dy) >= MIN_TOUCH_CENTER_DISTANCE_DP * density) {
                atan2(dy, dx)
            } else {
                (-Math.PI / 2).toFloat()
            }
        } else {
            (-Math.PI / 2).toFloat()
        }
        val tiltRadians = baseAngle
        val reach = VIRTUAL_TOUCH_FACTOR *
            hypot(snapshot.width.toFloat(), snapshot.height.toFloat())
        val centerX = originX + snapshot.width / 2f
        val centerY = originY + snapshot.height / 2f
        val spec = ParticleDismissSpec(
            snapshot = snapshot,
            originXPx = originX.toFloat(),
            originYPx = originY.toFloat(),
            cellPx = cellPx,
            driftPx = DRIFT_DP * density,
            noiseScalePx = NOISE_SCALE_DP * density,
            flarePx = FLARE_DP * density,
            pinchMaxPx = PINCH_MAX_DP * density,
            virtualTouchXPx = centerX + cos(tiltRadians) * reach,
            virtualTouchYPx = centerY + sin(tiltRadians) * reach,
            waveOriginUv = waveOriginUv,
            spreadTime = ParticleDismissRenderer.SPREAD_TIME,
            delayJitter = ParticleDismissRenderer.DELAY_JITTER,
            waveWarp = ParticleDismissRenderer.WAVE_WARP,
            durationScale = animatorDurationScale(dialog.context),
            noiseSeedX = (Math.random() * 1024.0).toFloat(),
            noiseSeedY = (Math.random() * 1024.0).toFloat(),
            hashSeed = (Math.random() * Int.MAX_VALUE).toInt(),
            panelColor = dominantColor(snapshot)
        )
        val overlay = ParticleDismissOverlay(activity = activity, spec = spec)
        hostDecor.addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        // 等 overlay（含快照）实际上屏后再通知调用方执行真实 dismiss：overlay
        // 与 Dialog 分属两个 window、渲染管线不同步，同帧提交也可能错开一帧
        // 生效——先就位、后揭开才能保证画面连续。
        runAfterShown(overlay, OVERLAY_SHOWN_TIMEOUT_MS, onOverlayShown)
    }

    /**
     * 在 [view] 所在 window 的下一帧实际提交合成后运行 [action]（恰好一次）：
     * 首个 preDraw 表示本帧将绘制，Choreographer 的下一个回调时该帧已提交。
     * [timeoutMs] 兜底保证 action 必然执行（跨窗口衔接不能卡死流程）。
     */
    private fun runAfterShown(view: View, timeoutMs: Long, action: Runnable) {
        var fired = false
        val fire = Runnable {
            if (!fired) {
                fired = true
                action.run()
            }
        }
        view.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    view.viewTreeObserver.removeOnPreDrawListener(this)
                    Choreographer.getInstance().postFrameCallback { fire.run() }
                    return true
                }
            }
        )
        // 兜底必须挂全局 Handler：View.postDelayed 在 view 已 detach 时进入
        // RunQueue 等待 re-attach、永不执行——window 被移除后 preDraw 与
        // view 级兜底同时失效，动画层永久残留（2026-08-26"Dialog 凝固"根因）
        mainHandler.postDelayed(fire, timeoutMs)
    }

    /** 粒子消散动画（以及等长的 dim 淡出）的实际时长。 */
    fun dismissAnimationDurationMs(context: Context): Long =
        (ParticleDismissRenderer.TOTAL_DURATION * animatorDurationScale(context) * 1000).toLong()

    /**
     * 为 [dialog] 播放凝聚出现动画（约 320ms）：面板布局完成但首帧显示前抓
     * 快照并隐藏面板，粒子以"缕缕烟云"随机顺序凝实（波前权重极低、区域噪声
     * 与逐粒子抖动主导——不从四周向中心收拢、无中心空洞）、静止层逐格显现，
     * 末帧为完整原图后恢复真实面板显示——切换无缝。
     *
     * 返回 true 表示已接管（面板已置透明，动画结束或任何失败路径都会恢复，
     * 附兜底）。[onOverlayCreated] 在动画层创建时回调，调用方应持有引用并在
     * dismiss 拦截时调用其 release()——否则凝聚未完成就 dismiss 时，残留的
     * 动画层会以末帧形态永久盖在界面上（"Dialog 凝固"，2026-08-26 检查更新
     * 场景定位）。
     */
    fun startCondense(
        dialog: Dialog,
        onOverlayCreated: (ParticleDismissOverlay) -> Unit
    ): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        if (!ValueAnimator.areAnimatorsEnabled()) return false
        val window = dialog.window ?: return false
        val decor = window.decorView
        val activity = activityFrom(dialog.context) ?: return false
        if (activity.isFinishing || activity.isDestroyed || activity.isChangingConfigurations) {
            return false
        }
        val hostDecor = activity.window?.decorView as? ViewGroup ?: return false
        if (!hostDecor.isAttachedToWindow) return false

        // 隐藏首帧用 alpha 而不是 INVISIBLE：window 级可见性切换意味着窗口
        // 从未显示过，WMS 把窗口 enter 动画（如底部面板 190ms 的 100%p 滑入）
        // 挂起到凝聚结束恢复可见的瞬间才播——app 侧的 preDraw/Choreographer
        // 只能确认帧提交、感知不到 WMS 侧的窗口动画，动画层撤走时面板可能
        // 仍在屏外，表现为"凝聚结束闪一下"（2026-08-26 改记事颜色底部面板
        // 定位；置零窗口动画防不住——子类 onStart 在 super 之后重设即覆盖）。
        // alpha=0 下窗口全程正常显示：enter 动画在透明期内照常播完，恢复
        // 只是一帧普通属性重绘，上屏确认因此可靠。快照不受影响：View.draw
        // 渲染的是内容，自身 alpha 由父级/RenderNode 合成时才应用。
        val prevAlpha = decor.alpha
        decor.alpha = 0f
        var handled = false
        val restore = Runnable {
            if (!handled) {
                handled = true
                decor.alpha = prevAlpha
            }
        }
        decor.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    decor.viewTreeObserver.removeOnPreDrawListener(this)
                    if (handled) return true
                    if (decor.width <= 0 || decor.height <= 0 ||
                        containsLiveSurface(decor) || !hostDecor.isAttachedToWindow
                    ) {
                        restore.run()
                        return true
                    }
                    val snapshot = captureSnapshot(decor, dialog.context)
                    if (snapshot == null) {
                        restore.run()
                        return true
                    }
                    handled = true

                    val dialogLocation = IntArray(2)
                    val hostLocation = IntArray(2)
                    decor.getLocationOnScreen(dialogLocation)
                    hostDecor.getLocationOnScreen(hostLocation)
                    val originX = dialogLocation[0] - hostLocation[0]
                    val originY = dialogLocation[1] - hostLocation[1]

                    val density = decor.resources.displayMetrics.density
                    // 凝聚无触点：波前起点取面板中心（权重已极低）、主方向向上
                    // 带随机倾斜、位移减半（就近轻盈飘入）
                    val tiltRadians = Math.toRadians(
                        -90.0 + (Math.random() * 2.0 - 1.0) * DRIFT_TILT_DEG
                    ).toFloat()
                    val reach = VIRTUAL_TOUCH_FACTOR *
                        hypot(snapshot.width.toFloat(), snapshot.height.toFloat())
                    val spec = ParticleDismissSpec(
                        snapshot = snapshot,
                        originXPx = originX.toFloat(),
                        originYPx = originY.toFloat(),
                        cellPx = adaptiveCellPx(snapshot, density),
                        driftPx = DRIFT_DP * density * CONDENSE_DRIFT_FACTOR,
                        noiseScalePx = NOISE_SCALE_DP * density,
                        flarePx = FLARE_DP * density,
                        pinchMaxPx = PINCH_MAX_DP * density,
                        virtualTouchXPx = originX + snapshot.width / 2f +
                            cos(tiltRadians) * reach,
                        virtualTouchYPx = originY + snapshot.height / 2f +
                            sin(tiltRadians) * reach,
                        waveOriginUv = PointF(0.5f, 0.5f),
                        spreadTime = CONDENSE_SPREAD_TIME,
                        delayJitter = CONDENSE_DELAY_JITTER,
                        waveWarp = CONDENSE_WAVE_WARP,
                        durationScale = animatorDurationScale(dialog.context),
                        noiseSeedX = (Math.random() * 1024.0).toFloat(),
                        noiseSeedY = (Math.random() * 1024.0).toFloat(),
                        hashSeed = (Math.random() * Int.MAX_VALUE).toInt(),
                        panelColor = dominantColor(snapshot),
                        condenseFromT = CONDENSE_FROM_T,
                        condenseDurationS = CONDENSE_DURATION_S
                    )
                    // 先显后撤：凝聚末帧与真实面板逐像素相同，恢复面板可见后
                    // 等它确实上屏才移除动画层——跨窗口切换的任何一帧都无缝
                    var overlayRef: ParticleDismissOverlay? = null
                    val overlay = ParticleDismissOverlay(
                        activity = activity,
                        spec = spec,
                        onDone = Runnable {
                            decor.alpha = prevAlpha
                            runAfterShown(decor, OVERLAY_SHOWN_TIMEOUT_MS) {
                                overlayRef?.release()
                            }
                        }
                    )
                    overlayRef = overlay
                    onOverlayCreated(overlay)
                    hostDecor.addView(
                        overlay,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    return true
                }
            }
        )
        // 兜底：preDraw 异常缺席时恢复显示，Dialog 不能一直不可见
        decor.postDelayed(restore, CONDENSE_RESTORE_TIMEOUT_MS)
        return true
    }

    private fun adaptiveCellPx(snapshot: Bitmap, density: Float): Float {
        var cellPx = max(2f, CELL_DP * density)
        val estimated = (snapshot.width / cellPx) * (snapshot.height / cellPx)
        if (estimated > ParticleDismissRenderer.MAX_PARTICLES) {
            cellPx = sqrt(
                snapshot.width.toFloat() * snapshot.height /
                    ParticleDismissRenderer.MAX_PARTICLES
            )
        }
        return cellPx
    }

    /**
     * 面板本体色 = 快照缩略图的众数色（每通道 16 级量化直方图峰值桶的平均），
     * 作为内容色权重的参照。不依赖主题假设：自定义背景与暗色模式自动适配，
     * 32×32 缩略统计为微秒级。
     */
    private fun dominantColor(snapshot: Bitmap): Int {
        val scaled = Bitmap.createScaledBitmap(snapshot, 32, 32, false)
        val pixels = IntArray(32 * 32)
        scaled.getPixels(pixels, 0, 32, 0, 0, 32, 32)
        if (scaled !== snapshot) scaled.recycle()
        val counts = HashMap<Int, IntArray>()
        for (pixel in pixels) {
            if (pixel ushr 24 < 0x80) continue
            val bucket = ((pixel shr 20) and 0xF00) or
                ((pixel shr 12) and 0xF0) or ((pixel shr 4) and 0xF)
            val entry = counts.getOrPut(bucket) { IntArray(4) }
            entry[0]++
            entry[1] += (pixel shr 16) and 0xFF
            entry[2] += (pixel shr 8) and 0xFF
            entry[3] += pixel and 0xFF
        }
        val best = counts.values.maxByOrNull { it[0] } ?: return 0xFFFFFFFF.toInt()
        return (0xFF shl 24) or ((best[1] / best[0]) shl 16) or
            ((best[2] / best[0]) shl 8) or (best[3] / best[0])
    }

    /**
     * 凝聚场景的快照：面板首帧前 window surface 尚无内容，PixelCopy 返回
     * ERROR_SOURCE_NO_DATA，离屏硬件渲染是唯一保真路径（clipToOutline 等由
     * HWUI 应用；且首帧前的 View 走"全新录制"路径，不存在可见窗口场景下
     * "引用既有 displayList"的陈旧问题——2026-08-26 调研结论）。失败回退
     * 软件 draw（仅丢子 View 的 outline 裁剪，凝实末帧短暂可见）。
     */
    private fun captureSnapshot(decor: View, context: Context): Bitmap? {
        return try {
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                captureViaHardwareRenderer(decor) ?: captureViaSoftwareDraw(decor)
            } else {
                captureViaSoftwareDraw(decor)
            }
            applyRoundedCornerMask(bitmap, context)
            bitmap
        } catch (_: Throwable) {
            null
        }
    }

    private fun captureViaSoftwareDraw(decor: View): Bitmap {
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        return bitmap
    }

    /**
     * 加固版离屏硬件渲染（按 2026-08-26 调研清单）：检查 syncAndDraw 返回码
     * （失败静默、不产帧，空图首要嫌疑）、acquireNextImage、API 33+ 等 GPU
     * 完成栅栏（不等则 GPU 繁忙时读到未写完的缓冲）、光源（不设则 elevation
     * 阴影不渲染）、setOpaque(false)、官方释放顺序。
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun captureViaHardwareRenderer(decor: View): Bitmap? {
        val width = decor.width
        val height = decor.height
        var imageReader: ImageReader? = null
        var renderer: HardwareRenderer? = null
        val node = RenderNode("particleCondenseSnapshot")
        return try {
            imageReader = ImageReader.newInstance(
                width, height, PixelFormat.RGBA_8888, 3,
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
            )
            renderer = HardwareRenderer()
            renderer.setName("particleCondenseSnapshot")
            renderer.isOpaque = false
            renderer.setSurface(imageReader.surface)
            node.setPosition(0, 0, width, height)
            val recordingCanvas = node.beginRecording(width, height)
            decor.draw(recordingCanvas)
            node.endRecording()
            renderer.setContentRoot(node)
            val metrics = decor.resources.displayMetrics
            val locationOnScreen = IntArray(2)
            decor.getLocationOnScreen(locationOnScreen)
            renderer.setLightSourceGeometry(
                metrics.widthPixels / 2f - locationOnScreen[0],
                -locationOnScreen[1].toFloat(),
                600f * metrics.density,
                800f * metrics.density
            )
            renderer.setLightSourceAlpha(0.039f, 0.19f)
            renderer.start()
            val sync = renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            if (sync != HardwareRenderer.SYNC_OK &&
                sync != HardwareRenderer.SYNC_REDRAW_REQUESTED
            ) {
                return null
            }
            val image = imageReader.acquireNextImage() ?: return null
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    image.fence.await(java.time.Duration.ofMillis(3000))
                }
                val hardwareBuffer = image.hardwareBuffer ?: return null
                val hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, null)
                hardwareBuffer.close()
                // isMutable 必须为 true：后续圆角遮罩要在其上建 Canvas（2026-08-26
                // 探针定位：false 时抛 Immutable bitmap，凝聚整体失效）
                val software = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, true)
                hardwareBitmap?.recycle()
                software
            } finally {
                image.close()
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { node.discardDisplayList() }
            runCatching { renderer?.destroy() }
            runCatching { imageReader?.close() }
        }
    }

    /**
     * 软件 draw 不应用 clipToOutline，内容若溢出面板圆角会在快照四角露出直角，
     * 在 CPU 侧按面板同款圆角半径裁掉。
     */
    private fun applyRoundedCornerMask(bitmap: Bitmap, context: Context) {
        val radius = context.resources.getDimension(R.dimen.app_chrome_dialog_popup_corner_radius)
        if (radius <= 0f) return
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawRoundRect(
            0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), radius, radius, paint
        )
    }

    private fun containsLiveSurface(view: View): Boolean {
        if (view is SurfaceView || view is TextureView) return true
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (containsLiveSurface(view.getChildAt(i))) return true
            }
        }
        return false
    }

    private fun animatorDurationScale(context: Context): Float =
        Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ).coerceAtLeast(0.1f)

    private fun activityFrom(context: Context): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    /** 粒子网格步长；粒子总数超上限时自适应放大。第二十五轮细密化
     * （1.5 -> 1.1，配合尺寸曲线收缩共约 -34%）：薄纱而非沙粒。 */
    private const val CELL_DP = 1.1f

    /** 粒子漂移总距离的基准（逐粒子再乘随机系数与流场调制）。 */
    private const val DRIFT_DP = 210f

    /** 烟缕流场的空间尺度：约为中等 Dialog 宽度的 1/3，缕成团不碎。 */
    private const val NOISE_SCALE_DP = 120f

    /** 外扩羽流幅度：部分区段的边缘粒子向外推出的距离。 */
    private const val FLARE_DP = 90f

    /** 收拢位移绝对上限：宽 Dialog 边缘不被一口气拉向中轴。 */
    private const val PINCH_MAX_DP = 240f

    /** overlay 上屏探测的兜底时限：超时也放行真实 dismiss，Dialog 不能关不掉。 */
    private const val OVERLAY_SHOWN_TIMEOUT_MS = 100L

    /** PixelCopy 回调的兜底时限：部分设备回调可能不来，超时放行真实 dismiss。 */
    private const val PIXEL_COPY_TIMEOUT_MS = 500L

    /** 凝聚起点（逻辑秒）：起点为稀薄散云，凝实从"近乎无"开始浮现。 */
    private const val CONDENSE_FROM_T = 0.55f

    /** 凝聚动画时长（逻辑秒）：与普通入场动画量级相当，不拖交互节奏。 */
    private const val CONDENSE_DURATION_S = 0.32f

    /** 凝聚的波前扫过时长：极小值 = 弱化中心性，无"从四周收向中心"的空洞。 */
    private const val CONDENSE_SPREAD_TIME = 0.06f

    /** 凝聚的逐粒子激活抖动：加大 = 凝实顺序随机化。 */
    private const val CONDENSE_DELAY_JITTER = 0.20f

    /** 凝聚的区域噪声扭曲：加大 = 斑块状"缕缕"凝实。 */
    private const val CONDENSE_WAVE_WARP = 0.30f

    /** 凝聚的位移缩放：减半 = 粒子就近轻盈飘入。 */
    private const val CONDENSE_DRIFT_FACTOR = 0.55f

    /** 凝聚流程的兜底时限：preDraw 缺席也要恢复面板显示。 */
    private const val CONDENSE_RESTORE_TIMEOUT_MS = 150L

    /** 虚拟远触点距离 = 因子 × 快照对角线：越小方向汇聚感越强。 */
    private const val VIRTUAL_TOUCH_FACTOR = 1.1f

    /** 主飘散方向的随机倾斜幅度（度）。 */
    private const val DRIFT_TILT_DEG = 18.0

    /** 触点距快照中心近于此值时方向不稳定，退回默认向上。 */
    private const val MIN_TOUCH_CENTER_DISTANCE_DP = 40f
}
