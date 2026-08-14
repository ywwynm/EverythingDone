package com.ywwynm.everythingdone.spatial

import java.util.Locale

/**
 * 端侧推理的分段计时。
 *
 * 默认关闭：未 [start] 时 [measure] 直接执行 block，不取时间、不加锁、不分配，
 * 因此可以留在产品路径上。只做观测，不改变任何推理行为。
 *
 * 分段粒度按「NPU 化之后会怎么变」来定，而不是按代码结构：
 *
 * - `*.session` 是 `createSession`。CPU 上它是权重加载与图优化；换到 QNN HTP 之后
 *   同一段会变成图编译（首次）或 context binary 加载（之后）。Big-LaMa 现在这一段
 *   就要 5.3 秒（D203），是判断 NPU 收益的关键分母。
 * - `*.run` 是 `session.run`，即 AI Hub 那些延迟数字对应的那一段。
 * - `*.prepare` / `*.post` 是 CPU 侧的像素搬运与后处理，**NPU 不会改善这两段**，
 *   它们决定了加速之后的地板。
 *
 * 分块推理（Big-LaMa）会对同一 stage 记多条，[format] 按 stage 聚合并给出条数。
 */
object SpatialInferenceTrace {

    data class Sample(
        val stage: String,
        val nanos: Long,
        val failed: Boolean
    )

    @Volatile
    private var recording = false

    private val samples = ArrayList<Sample>()

    /** 清空既有样本并开始记录。重复调用等价于重新开始。 */
    fun start() {
        synchronized(samples) {
            samples.clear()
            recording = true
        }
    }

    /** 停止记录并取回样本快照。 */
    fun stop(): List<Sample> {
        synchronized(samples) {
            recording = false
            return ArrayList(samples)
        }
    }

    fun isRecording(): Boolean = recording

    inline fun <T> measure(stage: String, block: () -> T): T {
        if (!isRecording()) return block()
        val startedAt = System.nanoTime()
        var failed = true
        try {
            val result = block()
            failed = false
            return result
        } finally {
            record(stage, System.nanoTime() - startedAt, failed)
        }
    }

    /** 供 [measure] 的 inline 体调用；不要直接使用。 */
    fun record(stage: String, nanos: Long, failed: Boolean) {
        synchronized(samples) {
            if (!recording) return
            samples.add(Sample(stage, nanos, failed))
        }
    }

    /**
     * 聚合成按总耗时降序的表。一行一个 stage：
     * `stage 次数 总毫秒 单次毫秒 占比%`，末行给合计。
     */
    fun format(samples: List<Sample>): String {
        if (samples.isEmpty()) return "trace empty"
        val order = LinkedHashMap<String, MutableList<Sample>>()
        for (sample in samples) {
            order.getOrPut(sample.stage) { ArrayList() }.add(sample)
        }
        val total = samples.sumOf { it.nanos }
        val rows = order.entries
            .map { (stage, group) -> stage to group }
            .sortedByDescending { (_, group) -> group.sumOf { it.nanos } }
        val builder = StringBuilder()
        for ((stage, group) in rows) {
            val stageNanos = group.sumOf { it.nanos }
            val failures = group.count { it.failed }
            builder.append(
                String.format(
                    Locale.US,
                    "  %-28s n=%-3d %8.1f ms  %8.1f ms/次  %5.1f%%%s\n",
                    stage,
                    group.size,
                    stageNanos / 1e6,
                    stageNanos / 1e6 / group.size,
                    stageNanos * 100.0 / total,
                    if (failures > 0) "  失败 $failures" else ""
                )
            )
        }
        builder.append(
            String.format(Locale.US, "  %-28s %12.1f ms\n", "合计（仅计时段）", total / 1e6)
        )
        return builder.toString()
    }

    // stage 名集中在这里，避免各引擎里散落字面量导致聚合不上。
    const val DEPTH_PREPARE = "depth.prepare"
    const val DEPTH_SESSION = "depth.session"
    const val DEPTH_RUN = "depth.run"
    const val DEPTH_POST = "depth.post"

    const val MATTING_PREPARE = "matting.prepare"
    const val MATTING_SESSION = "matting.session"
    const val MATTING_RUN = "matting.run"
    const val MATTING_POST = "matting.post"

    const val SEGMENTATION_PREPARE = "segmentation.prepare"
    const val SEGMENTATION_SESSION = "segmentation.session"
    const val SEGMENTATION_RUN = "segmentation.run"
    const val SEGMENTATION_POST = "segmentation.post"

    const val BOUNDARY_PREPARE = "boundary.prepare"
    const val BOUNDARY_SESSION = "boundary.session"
    const val BOUNDARY_RUN_ENCODER = "boundary.run.encoder"
    const val BOUNDARY_RUN_PROMPT = "boundary.run.prompt"
    const val BOUNDARY_RUN_DECODER = "boundary.run.decoder"
    const val BOUNDARY_POST = "boundary.post"

    const val INPAINT_PREPARE = "inpaint.prepare"
    const val INPAINT_SESSION = "inpaint.session"
    const val INPAINT_RUN = "inpaint.run"
    const val INPAINT_POST = "inpaint.post"

    /** 几何/合成等纯 CPU 段：NPU 不改善，用来标出加速之后的地板。 */
    const val GEOMETRY_BUILD = "geometry.build"
    const val DERIVATIVE_SAVE = "derivative.save"

    // 派生落盘的细分。2026-08-13 首次全链路计时发现 save 占 74.6 秒里的 30.2 秒，
    // 比补全推理还多；拆到每个写入函数才能定位。
    const val SAVE_DEPTH_VALUES = "save.depth_values"
    const val SAVE_COMPRESSED_BYTES = "save.compressed_bytes"
    const val SAVE_SURFACE_CHARTS = "save.surface_charts"
    const val SAVE_DEPTH_SURFELS = "save.depth_surfels"
    const val SAVE_MOTION_BASIS = "save.motion_basis"
    const val SAVE_CONNECTIVITY = "save.connectivity"
    const val SAVE_BACKGROUND_PNG = "save.background_png"
    const val SAVE_SHA256 = "save.sha256"

    // QNN 的 session 创建要与 CPU 分开看：cached 是加载 context binary（实测 0.25–0.4 s），
    // compile 是端上 HTP 图编译（实测 20–51 s，一次性）。
    const val QNN_SESSION_CACHED = "qnn.session.cached"
    const val QNN_SESSION_COMPILE = "qnn.session.compile"
}
