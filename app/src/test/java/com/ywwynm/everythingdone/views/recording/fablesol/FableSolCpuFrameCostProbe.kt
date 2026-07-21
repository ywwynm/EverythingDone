package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 无设备的 CPU 侧每帧成本探针。
 *
 * 只测纯 Kotlin 的渲染热路径阶段（物理子步、连续水面采样、银泽坡度滤波），
 * 不涉及 GLES，因此可在 JVM 单测里运行。绝对值不代表手机耗时（桌面 x86 大核
 * 远快于手机，且没有 ART 的 debuggable 运行时税），**只用于优化前后的相对对照**。
 *
 * 默认跳过，不拖慢 `:app:testDebugUnitTest`。运行方式：
 * ```
 * gradlew.bat :app:testDebugUnitTest --tests "*FableSolCpuFrameCostProbe*" -Dfablesol.perf=1
 * ```
 * 需要把系统属性透传给测试 JVM 时，在 `app/build.gradle` 的 `testOptions.unitTests.all`
 * 里加 `systemProperty`，或直接用 `-Dfablesol.perf=1` 配合 Gradle 的 forkEvery 传递。
 */
class FableSolCpuFrameCostProbe {

    @Test
    fun measureCpuFrameStages() {
        assumeTrue(System.getProperty(PERF_PROPERTY) != null)

        val params = FableSolParams()
        params.setForTest("demo_mode", 1.0)
        val sim = FableSolSimulation(params)
        sim.setContainerWidthDp(280.0)

        val columns = 196
        val rows = FableSolContinuousSurface.Z_ROWS
        val sheen = FloatArray(rows * columns)
        val sheenScratch = FloatArray(rows * columns)
        for (i in sheen.indices) sheen[i] = ((i % 37) - 18) * 0.031f

        // 预热：让 JIT 编译热循环并让物理进入稳态（波包、Hero、边界剖面都已生成）。
        repeat(WARMUP_FRAMES) {
            sim.update(FRAME_DT)
            sim.surface2d.sample(sim)
            FableSolSheenSlopeFilter.smooth(sheen, sheenScratch, rows, columns)
        }

        val updateNs = LongArray(MEASURE_FRAMES)
        val sampleNs = LongArray(MEASURE_FRAMES)
        val sheenNs = LongArray(MEASURE_FRAMES)
        for (frame in 0 until MEASURE_FRAMES) {
            var mark = System.nanoTime()
            sim.update(FRAME_DT)
            updateNs[frame] = System.nanoTime() - mark

            mark = System.nanoTime()
            sim.surface2d.sample(sim)
            sampleNs[frame] = System.nanoTime() - mark

            mark = System.nanoTime()
            FableSolSheenSlopeFilter.smooth(sheen, sheenScratch, rows, columns)
            sheenNs[frame] = System.nanoTime() - mark
        }

        report("physics  sim.update", updateNs)
        report("build    surface2d.sample", sampleNs)
        report("build    sheenSlopeFilter", sheenNs)
        val total = LongArray(MEASURE_FRAMES) { updateNs[it] + sampleNs[it] + sheenNs[it] }
        report("合计（三阶段）", total)

        measureSampleSubStages(sim)
        measureRowParallelBarrier()
        measureOpticsAndSheenWriteback(sim, params, columns, rows, sheen, sheenScratch)
    }

    /**
     * `buildFrame()` 后半段里同样跑在 GL 线程、且与 GLES 无关的两块：
     * 几何光学顶点流构建，以及银泽坡度写回 19012 个顶点的交织循环。
     */
    private fun measureOpticsAndSheenWriteback(
        sim: FableSolSimulation,
        params: FableSolParams,
        columns: Int,
        rows: Int,
        sheenSlopeX: FloatArray,
        sheenSlopeZ: FloatArray
    ) {
        val optics = FableSolGlOptics(2.75)
        val water = FloatArray(rows * columns * FableSolGlMeshLayout.COMPONENTS_PER_VERTEX)
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val offset = (row * columns + column) * FableSolGlMeshLayout.COMPONENTS_PER_VERTEX
                water[offset] = ((column - (columns - 1) / 2.0) * 3.0).toFloat()
                water[offset + 1] = (row * 4.0 + 16.0 * Math.sin(column * 0.05 + row * 0.11)).toFloat()
            }
        }
        val start = Array(FableSolSpec.N_LAYERS) { intArrayOf(48, 102, 176) }
        val end = Array(FableSolSpec.N_LAYERS) { intArrayOf(88, 146, 205) }
        val horizon = intArrayOf(218, 205, 226)
        val sourceIndex = IntArray(columns) { it.coerceAtMost(FableSolSpec.N_POINTS - 2) }
        val sourceFraction = DoubleArray(columns)

        val opticsNs = LongArray(MEASURE_FRAMES)
        val writebackNs = LongArray(MEASURE_FRAMES)
        repeat(WARMUP_FRAMES / 4) {
            optics.build(sim, params, columns, water, start, end, horizon, sourceIndex, sourceFraction)
            sheenWriteback(water, sheenSlopeX, sheenSlopeZ, rows * columns)
        }
        for (frame in 0 until MEASURE_FRAMES) {
            var mark = System.nanoTime()
            optics.build(sim, params, columns, water, start, end, horizon, sourceIndex, sourceFraction)
            opticsNs[frame] = System.nanoTime() - mark

            mark = System.nanoTime()
            sheenWriteback(water, sheenSlopeX, sheenSlopeZ, rows * columns)
            writebackNs[frame] = System.nanoTime() - mark
        }
        report("build    optics.build", opticsNs)
        report("build    sheen 写回 19012 顶点", writebackNs)
    }

    private fun sheenWriteback(
        vertexData: FloatArray,
        sheenSlopeX: FloatArray,
        sheenSlopeZ: FloatArray,
        vertexCount: Int
    ) {
        for (vertex in 0 until vertexCount) {
            val offset = vertex * FableSolGlMeshLayout.COMPONENTS_PER_VERTEX
            vertexData[offset + FableSolGlMeshLayout.SHEEN_SLOPE_X_OFFSET] = sheenSlopeX[vertex]
            vertexData[offset + FableSolGlMeshLayout.SHEEN_SLOPE_Z_OFFSET] = sheenSlopeZ[vertex]
        }
    }

    /**
     * 单独量 [FableSolRowParallel] 每次 `run()` 的固定开销：空 body 的整轮耗时
     * 就是发布 + 唤醒 3 个 worker + 汇合的纯同步成本。再用同一份 fairing 工作量
     * 对照串行执行，判断按行并行在这个粒度上是否划算。
     */
    private fun measureRowParallelBarrier() {
        val rows = FableSolContinuousSurface.Z_ROWS
        val points = FableSolSpec.N_POINTS
        val source = Array(rows) { r -> DoubleArray(points) { x -> 0.3 * ((r * 7 + x) % 11) - 1.5 } }
        val values = Array(rows) { DoubleArray(points) }
        val derivatives = Array(rows) { DoubleArray(points) }
        val emptyBody = FableSolRowBody { _, _ -> }

        val barrierNs = LongArray(MEASURE_FRAMES)
        val parallelNs = LongArray(MEASURE_FRAMES)
        val serialNs = LongArray(MEASURE_FRAMES)

        repeat(WARMUP_FRAMES / 4) {
            FableSolRowParallel.run(rows, emptyBody)
            fairAllRows(rows, source, values, derivatives)
            fairSerial(rows, source, values, derivatives)
        }
        for (frame in 0 until MEASURE_FRAMES) {
            var mark = System.nanoTime()
            FableSolRowParallel.run(rows, emptyBody)
            barrierNs[frame] = System.nanoTime() - mark

            mark = System.nanoTime()
            fairAllRows(rows, source, values, derivatives)
            parallelNs[frame] = System.nanoTime() - mark

            mark = System.nanoTime()
            fairSerial(rows, source, values, derivatives)
            serialNs[frame] = System.nanoTime() - mark
        }

        println("[FableSolCpuFrameCost] worker 数=${FableSolRowParallel.parallelWorkerCountForTest}" +
            "，可用核=${Runtime.getRuntime().availableProcessors()}")
        report("  ‖ RowParallel 空 body（纯屏障）", barrierNs)
        report("  ‖ fairing×1 并行", parallelNs)
        report("  ‖ fairing×1 串行", serialNs)
    }

    private fun fairSerial(
        rows: Int,
        source: Array<DoubleArray>,
        values: Array<DoubleArray>,
        derivatives: Array<DoubleArray>
    ) {
        for (r in 0 until rows) {
            FableSolCubicResampler.fairCubicBsplineRow(
                source[r], values[r], derivatives[r], FableSolSpec.DX_DP
            )
        }
    }

    /**
     * 把 `sample()` 的四个阶段单独复算一遍以定位内部占比。
     * compose 与 fairing/slopeZ 用公开入口在同规模数据上重放；方向场累加阶段
     * 的耗时由 `sample() − 其余三段` 反推。
     */
    private fun measureSampleSubStages(sim: FableSolSimulation) {
        val rows = FableSolContinuousSurface.Z_ROWS
        val points = FableSolSpec.N_POINTS
        val directional = Array(rows) { r -> DoubleArray(points) { x -> 0.7 * ((r + x) % 13) - 4.0 } }
        val values = Array(rows) { DoubleArray(points) }
        val derivatives = Array(rows) { DoubleArray(points) }
        val world = Array(rows) { r -> DoubleArray(points) { x -> 0.3 * ((r * 7 + x) % 11) - 1.5 } }
        val slopeZ = Array(rows) { DoubleArray(points) }
        val depthStep = 1.35

        val composeNs = LongArray(MEASURE_FRAMES)
        val fairNs = LongArray(MEASURE_FRAMES)
        val slopeNs = LongArray(MEASURE_FRAMES)

        repeat(WARMUP_FRAMES / 4) {
            sim.surface2d.composeLayerField(sim.heights, directional)
            fairAllRows(rows, world, values, derivatives)
            gradientZ(rows, points, world, slopeZ, depthStep)
        }
        for (frame in 0 until MEASURE_FRAMES) {
            var mark = System.nanoTime()
            sim.surface2d.composeLayerField(sim.heights, directional)
            composeNs[frame] = System.nanoTime() - mark

            mark = System.nanoTime()
            // sample() 里 fairing 三路（worldEta / orbitX / orbitZ）共用一次 run。
            FableSolRowParallel.run(rows) { start, end ->
                for (r in start until end) {
                    FableSolCubicResampler.fairCubicBsplineRow(
                        world[r], values[r], derivatives[r], FableSolSpec.DX_DP
                    )
                    FableSolCubicResampler.fairCubicBsplineRow(
                        world[r], values[r], derivatives[r], FableSolSpec.DX_DP
                    )
                    FableSolCubicResampler.fairCubicBsplineRow(
                        world[r], values[r], derivatives[r], FableSolSpec.DX_DP
                    )
                }
            }
            fairNs[frame] = System.nanoTime() - mark

            mark = System.nanoTime()
            gradientZ(rows, points, world, slopeZ, depthStep)
            slopeNs[frame] = System.nanoTime() - mark
        }

        report("  └ composeLayerField", composeNs)
        report("  └ C2 fairing ×3", fairNs)
        report("  └ slopeZ 梯度", slopeNs)
    }

    private fun fairAllRows(
        rows: Int,
        source: Array<DoubleArray>,
        values: Array<DoubleArray>,
        derivatives: Array<DoubleArray>
    ) {
        FableSolRowParallel.run(rows) { start, end ->
            for (r in start until end) {
                FableSolCubicResampler.fairCubicBsplineRow(
                    source[r], values[r], derivatives[r], FableSolSpec.DX_DP
                )
            }
        }
    }

    private fun gradientZ(
        rows: Int,
        points: Int,
        world: Array<DoubleArray>,
        slopeZ: Array<DoubleArray>,
        depthStep: Double
    ) {
        FableSolRowParallel.run(rows) { start, end ->
            for (r in start until end) {
                val row = slopeZ[r]
                for (x in 0 until points) {
                    row[x] = when (r) {
                        0 -> (-3.0 * world[0][x] + 4.0 * world[1][x] - world[2][x]) / (2.0 * depthStep)
                        rows - 1 -> (3.0 * world[r][x] - 4.0 * world[r - 1][x] +
                            world[r - 2][x]) / (2.0 * depthStep)
                        else -> (world[r + 1][x] - world[r - 1][x]) / (2.0 * depthStep)
                    }
                }
            }
        }
    }

    private fun report(label: String, samples: LongArray) {
        val sorted = samples.clone().also { it.sort() }
        val p50 = sorted[sorted.size / 2] / 1000.0
        val p95 = sorted[(sorted.size * 95) / 100] / 1000.0
        val p99 = sorted[(sorted.size * 99) / 100] / 1000.0
        var sum = 0L
        for (value in samples) sum += value
        val mean = sum / samples.size / 1000.0
        println(
            "[FableSolCpuFrameCost] %-28s mean=%8.1fµs p50=%8.1fµs p95=%8.1fµs p99=%8.1fµs"
                .format(label, mean, p50, p95, p99)
        )
    }

    private companion object {
        const val PERF_PROPERTY = "fablesol.perf"
        const val FRAME_DT = 1.0 / 120.0
        const val WARMUP_FRAMES = 1200
        const val MEASURE_FRAMES = 1200
    }
}
