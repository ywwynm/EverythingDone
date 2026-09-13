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
import kotlin.math.sin

/**
 * Dialog 粒子消散动画的入口与编排。
 *
 * 使用 PixelCopy 异步取得 Dialog 快照，等待 Activity 上的动画层与首帧均就绪
 * 后释放真实窗口。粒子可越过原窗口边界，背景暗层沿同一逻辑时钟淡出。
 */
internal object ParticleDismissController {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val warmupStarted = java.util.concurrent.atomic.AtomicBoolean()

    /** 首个可见界面或弹窗展示后异步预热数值代码与驱动首用编译，不保存用户快照。 */
    fun warmDismissModel(context: Context) {
        if (!ValueAnimator.areAnimatorsEnabled() || !warmupStarted.compareAndSet(false, true)) return
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            runCatching {
                val rules = ParticleMicroflakeRenderer.sharedResources(context.applicationContext.assets).rules
                val pixels = IntArray(256 * 256) { -1 }
                ParticleMicroflakeModel.build(240f, 320f, pixels, 256, 256, 65f, 0L, rules)
                val material = ParticleMicroflakeModel.build(240f, 320f, pixels, 256, 256, 65f, 1L, rules)
                ParticleMicroflakeGpuWarmup.run(context.applicationContext.assets, material)
            }.onFailure { android.util.Log.w("ParticleMicroflake", "材料预热未完成，关闭时正常构建", it) }
        }, "ParticleModelWarmup").start()
    }

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
     * [touchInWindow] 表示直接触发本次关闭的触点，返回分发可传入左上虚拟触点；其他
     * 异步完成和代码关闭传 null，统一使用右上虚拟触点。
     */
    fun start(
        dialog: Dialog,
        touchInWindow: PointF?,
        onAnimationStarted: Runnable,
        onOverlayShown: Runnable,
        useDefaultTouchDistance: Boolean = false
    ): Boolean {
        val requestedAtNanos = System.nanoTime()
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
                        touchInWindow, onAnimationStarted, onOverlayShown, requestedAtNanos, useDefaultTouchDistance
                    )
                } else {
                    // 抓图失败：放行真实 dismiss（本次无粒子动画）
                    onAnimationStarted.run()
                    onOverlayShown.run()
                }
            }, mainHandler)
        } catch (_: Throwable) {
            return false
        }
        mainHandler.postDelayed({
            if (!copyHandled) {
                copyHandled = true
                onAnimationStarted.run()
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
        onAnimationStarted: Runnable,
        onOverlayShown: Runnable,
        requestedAtNanos: Long,
        useDefaultTouchDistance: Boolean
    ) {
        val dialogLocation = IntArray(2)
        val hostLocation = IntArray(2)
        decor.getLocationOnScreen(dialogLocation)
        hostDecor.getLocationOnScreen(hostLocation)
        val originX = dialogLocation[0] - hostLocation[0]
        val originY = dialogLocation[1] - hostLocation[1]
        val visibleScreen = android.graphics.Rect()
        hostDecor.getWindowVisibleDisplayFrame(visibleScreen)

        // 消散主方向严格朝直接因果触点。真实方向不设置宽阈值：只在向量
        // 数值上无法归一化时回退。所有无触点关闭统一从正上向右偏 30°，
        // 即右上但仍以上行为主。
        val baseAngle = if (touchInWindow != null) {
            val dx = touchInWindow.x - decor.width / 2f
            val dy = touchInWindow.y - decor.height / 2f
            if (hypot(dx, dy) > DIRECTION_EPSILON_PX) {
                atan2(dy, dx)
            } else {
                DEFAULT_DISMISS_ANGLE_RAD
            }
        } else {
            DEFAULT_DISMISS_ANGLE_RAD
        }
        val tiltRadians = baseAngle
        val reach = VIRTUAL_TOUCH_FACTOR *
            hypot(snapshot.width.toFloat(), snapshot.height.toFloat())
        val centerX = originX + snapshot.width / 2f
        val centerY = originY + snapshot.height / 2f
        val virtualTouchX = centerX + cos(tiltRadians) * reach
        val virtualTouchY = centerY + sin(tiltRadians) * reach
        val spec = ParticleDismissSpec(
            snapshot = snapshot,
            originXPx = originX.toFloat(),
            originYPx = originY.toFloat(),
            virtualTouchXPx = virtualTouchX,
            virtualTouchYPx = virtualTouchY,
            durationScale = animatorDurationScale(dialog.context),
            hashSeed = (Math.random() * Int.MAX_VALUE).toInt(),
            requestedAtNanos = requestedAtNanos,
            touchGap = if (useDefaultTouchDistance || touchInWindow == null) null else
                ParticleFlowGeometry.touchGap(touchInWindow.x, touchInWindow.y, snapshot.width.toFloat(), snapshot.height.toFloat()),
            touchStrength = if (useDefaultTouchDistance || touchInWindow == null) null else
                ParticleFlowGeometry.touchStrength(touchInWindow.x, touchInWindow.y, snapshot.width.toFloat(), snapshot.height.toFloat(),
                    (visibleScreen.left-dialogLocation[0]).toFloat(), (visibleScreen.top-dialogLocation[1]).toFloat(),
                    (visibleScreen.right-dialogLocation[0]).toFloat(), (visibleScreen.bottom-dialogLocation[1]).toFloat())
        )
        val overlay = ParticleDismissOverlay(
            activity = activity,
            spec = spec,
            onAnimationStarted = onAnimationStarted
        )
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

    /** ViewPropertyAnimator 会自行乘系统缩放；这里只提供逻辑时长，避免重复缩放。 */
    fun dismissAnimatorDurationMs(): Long =
        (ParticleDismissRenderer.TOTAL_DURATION * 1000).toLong()

    /**
     * 为 [dialog] 倒序播放当前共同消散模型：保持完整一秒轨迹、每次随机材料。
     * 先完成首次绘制准备再抓取最终配色，末帧恢复完整原图和真实面板。
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
        val requestedAtNanos = System.nanoTime()
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
        var captureQueued = false
        var layoutRetries = 0
        var preparationListener: ViewTreeObserver.OnPreDrawListener? = null
        fun removePreparationListener() {
            preparationListener?.let { listener ->
                if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnPreDrawListener(listener)
            }
            preparationListener = null
        }
        val restore = Runnable {
            if (!handled) {
                handled = true
                removePreparationListener()
                decor.alpha = prevAlpha
            }
        }
        preparationListener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (handled || captureQueued) return true
                captureQueued = true
                // 子 TextView 的渐变也在 pre-draw 中按最终文字布局初始化。
                // 当前监听器可能先于子树合并进来的监听器执行；此时同步抓图
                // 会把临时纯色固化到整个出现动画。排到本次遍历结束后，先让
                // 全部配色与布局准备回调完成，不添加固定毫秒等待。
                decor.post capture@{
                    captureQueued = false
                    if (handled) return@capture
                    if (!dialog.isShowing || !decor.isAttachedToWindow ||
                        decor.width <= 0 || decor.height <= 0 ||
                        containsLiveSurface(decor) || !hostDecor.isAttachedToWindow
                    ) {
                        restore.run()
                        return@capture
                    }
                    // 限高、分隔线和间距可能在 post 中继续请求布局。此时尺寸和
                    // 窗口居中位置仍是中间结果；只对这类弹窗等待下一次真正布局。
                    if (hasPendingLayout(decor) || decor.parent?.isLayoutRequested == true) {
                        layoutRetries++
                        return@capture
                    }
                    removePreparationListener()
                    val captureAtNanos = System.nanoTime()
                    val snapshot = captureSnapshot(decor, dialog.context)
                    if (com.ywwynm.everythingdone.BuildConfig.DEBUG) {
                        android.util.Log.i(ParticleMicroflakeRenderer.TAG,
                            "出现捕获 layoutMs=${(captureAtNanos-requestedAtNanos)/1e6} captureMs=${(System.nanoTime()-captureAtNanos)/1e6} retries=$layoutRetries")
                    }
                    if (snapshot == null) {
                        restore.run()
                        return@capture
                    }
                    handled = true
                    decor.removeCallbacks(restore)

                    val dialogLocation = IntArray(2)
                    val hostLocation = IntArray(2)
                    decor.getLocationOnScreen(dialogLocation)
                    hostDecor.getLocationOnScreen(hostLocation)
                    val originX = dialogLocation[0] - hostLocation[0]
                    val originY = dialogLocation[1] - hostLocation[1]

                    // 出现没有关闭触点：倒放下方扇区内的共同消散轨迹。
                    // 每次使用新种子，其余尺寸、材质、距离规则与消散一致。
                    val angle = Math.toRadians(-ParticleAppearanceDirection.degrees(Math.random()).toDouble()).toFloat()
                    val reach = VIRTUAL_TOUCH_FACTOR *
                        hypot(snapshot.width.toFloat(), snapshot.height.toFloat())
                    val spec = ParticleDismissSpec(
                        snapshot = snapshot,
                        originXPx = originX.toFloat(),
                        originYPx = originY.toFloat(),
                        virtualTouchXPx = originX + snapshot.width / 2f + cos(angle) * reach,
                        virtualTouchYPx = originY + snapshot.height / 2f + sin(angle) * reach,
                        durationScale = animatorDurationScale(dialog.context),
                        hashSeed = (Math.random() * Int.MAX_VALUE).toInt(),
                        reverse = true,
                        playbackDurationS = ParticleReversePlan.APPEARANCE_SECONDS
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
                }
                return true
            }
        }
        decor.viewTreeObserver.addOnPreDrawListener(preparationListener)
        // 兜底：preDraw 异常缺席时恢复显示，Dialog 不能一直不可见
        decor.postDelayed(restore, CONDENSE_RESTORE_TIMEOUT_MS)
        return true
    }

    private fun hasPendingLayout(view: View): Boolean {
        // GONE 子树可能保留 requestLayout 标记，却不参加本轮布局。
        if (view.visibility == View.GONE) return false
        if (view.isLayoutRequested) return true
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            if (hasPendingLayout(view.getChildAt(i))) return true
        }
        return false
    }

    /**
     * 凝聚场景的快照：面板在准备阶段 alpha=0，不能从 window surface 复制
     * 可见内容。首次绘制准备完成后通过离屏硬件绘制获取最终配色及裁剪；
     * View.draw 不合成根 View 自身的 alpha，子节点按当前失效状态重新录制。
     * 失败回退软件 draw（子 View 的 outline 裁剪不保留）。
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

    /** overlay 上屏探测的兜底时限：超时也放行真实 dismiss，Dialog 不能关不掉。 */
    private const val OVERLAY_SHOWN_TIMEOUT_MS = 100L

    /** PixelCopy 回调的兜底时限：部分设备回调可能不来，超时放行真实 dismiss。 */
    private const val PIXEL_COPY_TIMEOUT_MS = 500L

    /** 凝聚流程的兜底时限：preDraw 缺席也要恢复面板显示。 */
    private const val CONDENSE_RESTORE_TIMEOUT_MS = 150L

    /** 虚拟远触点距离 = 因子 × 快照对角线：越小方向汇聚感越强。 */
    private const val VIRTUAL_TOUCH_FACTOR = 1.1f

    /** 无触点或真实触点恰在中心时：从正上向右偏 30°。 */
    private val DEFAULT_DISMISS_ANGLE_RAD = Math.toRadians(-60.0).toFloat()

    /** 只防止零向量归一化，不形成用户可感知的中心回退区域。 */
    private const val DIRECTION_EPSILON_PX = 0.001f
}
