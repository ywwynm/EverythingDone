package com.ywwynm.everythingdone.views.particledismiss

import com.ywwynm.everythingdone.BuildConfig

/**
 * 节拍级取证：把「一拍从 vsync 到上屏」这条链上的每一步都记下来。
 *
 * 第二批之后左滑仍不跟手，而滞后曲线（`ParticleSession.gestureTimings`）已经证明**画面画的
 * 就是手指要的相位**，剩下的问题只能出在「什么时候出这一帧、这一帧什么时候上屏」。既有产物
 * 只有「真的提交了的那些帧」，看不到**没提交的那些拍**：节拍到底跳没跳、pump 有没有赶上、
 * `wantsFrame` 为什么判否、栅栏等了多久、SurfaceFlinger 有没有把这一张丢掉。这份取证把这
 * 五件事都补上。
 *
 * release 包里所有记录点都被 [BuildConfig.DEBUG] 短路；debug 包里也要 [enabled] 为真才记，
 * 由观察器在 `begin` 时按配置打开、`end` 时关掉，平时零开销。
 *
 * 行的第一位是类别，其余按类别解释（单位一律纳秒，时间基准是 `System.nanoTime`）：
 *
 * | 类别 | 含义 | 其余各位 |
 * | --- | --- | --- |
 * | [TICK] | 节拍线程收到一次 vsync | 1 vsync 时刻, 2 回调实际执行时刻, 3 本拍目标（0 = 这次不出拍）, 4 预期呈现时刻, 5 截止时刻, 6 上一次 vsync 到这次的间隔 |
 * | [PUMP] | GL 线程跑一次 pump | 1 进入, 2 beatTarget, 3 consumedTarget, 4 是否出拍, 5 准备循环用时, 6 退出 |
 * | [COMPOSITE] | 一次合成 | 1 目标呈现时刻, 2 进入, 3 等栅栏用时, 4 画完, 5 swap 返回, 6 帧号, 7 画了几层 |
 * | [PRESENT] | 一帧的呈现判定 | 1 目标呈现时刻, 2 帧号, 3 驱动报的呈现时刻（负数是 PENDING/INVALID/FAILED）, 4 判定时刻, 5 从提交到现在 |
 * | [SKIP] | 出拍了但这一层没画 | 1 目标呈现时刻, 2 阶段序号, 3 原因码, 4 手指要的相位（位）, 5 这一拍能画的相位（位）, 6 上一拍画出的相位（位）, 7 可画档位 |
 */
internal object ParticleBeatTrace {

    const val TICK = 0L
    const val PUMP = 1L
    const val COMPOSITE = 2L
    const val PRESENT = 3L
    const val SKIP = 4L
    /** 整页捕获：开始、记录开始、记录完成、同步完成、HardwareRenderer 状态。 */
    const val PAGE_CAPTURE = 5L
    /** 飘散内容分析：入队、开工、取样完成、分析完成、线程 CPU 纳秒。 */
    const val CONTENT_ANALYSIS = 6L
    /** 驱动绘制完成：帧号、目标、提交、绘制完成、呈现；负值表示不支持或未发生。 */
    const val RENDER_COMPLETE = 7L
    /** 整页消费：捕获开始、图像到达、栅栏完成、主线程接收、上传完成、旧图像归还后放行。 */
    const val PAGE_IMAGE = 8L
    /** 页面遍历请求：时刻、是否仍在等待上一张图像。 */
    const val PAGE_REQUEST = 9L
    /** 流体准备的同步阶段：阶段序号、进入、退出。仅记录超过半毫秒的步骤。 */
    const val FLOW_STAGE = 10L
    /** 流体初始化：细项（0 程序、1 合并流场 CPU、2 合并流场上传）、开始、结束。 */
    const val FLOW_INITIALIZE = 11L
    /** 流体 CPU 建材：开始、像素拷贝完成、解包完成、模型完成、边界／历史完成。 */
    const val FLOW_MATERIAL = 12L
    /** 建材模型耗时：完成时刻、分类、释放场（含分类）、填充，单位纳秒。 */
    const val FLOW_MATERIAL_STATS = 13L
    /** 页面图像获取细分：通知、acquire 完成、fence 完成、包装完成。 */
    const val PAGE_IMAGE_ACQUIRE = 14L
    /** 页面上传细分：入队、开始执行、导入完成、回收 Bitmap 完成。 */
    const val PAGE_IMAGE_UPLOAD = 15L
    /** 页面提交时钟：捕获开始、默认动画时钟、实际单调时钟。 */
    const val PAGE_FRAME_TIME = 16L

    /** [SKIP] 的原因码。 */
    const val SKIP_CANCELLED = 0L
    const val SKIP_INVISIBLE = 1L
    const val SKIP_STAGE = 2L
    const val SKIP_NO_RENDERER = 3L
    const val SKIP_SAME_POSITION = 4L

    /** 一轮取证最多留多少行；超过就丢最早的（左滑一格约 1500 行）。 */
    private const val CAPACITY = 48_000

    @Volatile var enabled = false
        private set
    /** 仅 debug 探针订阅实际滚动容器绘制；区分原窗口遍历与独立整页记录。 */
    @Volatile var doingDrawObserver: ((androidx.recyclerview.widget.RecyclerView) -> Unit)? = null

    private val rows = ArrayDeque<LongArray>(1024)

    fun start() {
        if (!BuildConfig.DEBUG) return
        synchronized(rows) { rows.clear() }
        enabled = true
    }

    fun stop() { enabled = false; doingDrawObserver = null }

    fun record(vararg values: Long) {
        if (!BuildConfig.DEBUG || !enabled) return
        synchronized(rows) {
            rows.addLast(values)
            while (rows.size > CAPACITY) rows.removeFirst()
        }
    }

    fun snapshot(): List<LongArray> = synchronized(rows) { rows.toList() }
}
