package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * C2 把 `FableSolGlOptics.build` 的九层循环改为按层并行：每层持一份 scratch 与一段
 * 静态划分的顶点段，构完后按层序压实回一段连续缓冲。
 *
 * 层内顶点顺序、层间顺序都没有变，所以这不是浮点重排——并行与串行的顶点缓冲必须
 * **逐元素 `floatToRawIntBits` 相等**，层区间元数据与各项统计位也必须一一相等。
 * 这条回归同时是"层任务之间没有共享可变写"的实测门禁：真出现竞态，重复轮次里
 * 一定会撞出不等。
 */
class FableSolGlOpticsParallelParityTest {

    @Test
    fun 并行与串行的顶点缓冲逐元素相同且层元数据一致() {
        // 没有常驻 worker 时并行路径会退化成串行，这条对比就变成空转；
        // 与 FableSolRowParallelTest 一致地显式跳过，而不是假绿。
        org.junit.Assume.assumeTrue(FableSolRowParallel.parallelWorkerCountForTest > 0)
        for (scenario in Scenario.values()) {
            val serial = runScenario(scenario, parallel = false)
            val parallel = runScenario(scenario, parallel = true)

            assertEquals("场景 $scenario 的 floatCount 不同", serial.floatCount, parallel.floatCount)
            assertTrue("场景 $scenario 没有产生任何顶点", serial.floatCount > 0)
            for (index in 0 until serial.floatCount) {
                val expected = java.lang.Float.floatToRawIntBits(serial.vertices[index])
                val actual = java.lang.Float.floatToRawIntBits(parallel.vertices[index])
                if (expected != actual) {
                    throw AssertionError(
                        "场景 $scenario 顶点缓冲第 $index 个 float 不同：" +
                            "串行=${serial.vertices[index]} 并行=${parallel.vertices[index]}"
                    )
                }
            }
            assertArrayEquals(
                "场景 $scenario 的 layerFirstVertex 不同",
                serial.layerFirstVertex, parallel.layerFirstVertex
            )
            assertArrayEquals(
                "场景 $scenario 的 layerVertexCount 不同",
                serial.layerVertexCount, parallel.layerVertexCount
            )
            assertArrayEquals(
                "场景 $scenario 的 backShade 顶点数不同",
                serial.backShadeCount, parallel.backShadeCount
            )
            assertArrayEquals(
                "场景 $scenario 的 interfaceShoulder 顶点数不同",
                serial.interfaceShoulderCount, parallel.interfaceShoulderCount
            )
            assertArrayEquals(
                "场景 $scenario 的 bodyLight 顶点数不同",
                serial.bodyLightCount, parallel.bodyLightCount
            )
            assertArrayEquals(
                "场景 $scenario 的 glint 顶点数不同",
                serial.glintCount, parallel.glintCount
            )
            assertArrayEquals(
                "场景 $scenario 的每层轨迹数不同",
                serial.glintTrackCount, parallel.glintTrackCount
            )
            assertEquals(
                "场景 $scenario 的本帧出生数不同",
                serial.glitterBirths, parallel.glitterBirths
            )
        }
    }

    /** 默认色板（闪点/体光全关）下并行同样不得改变输出，这是真机上真正跑的配置。 */
    @Test
    fun 默认配置下并行仍产出与串行相同的波背暗带几何() {
        val serial = runScenario(Scenario.DEFAULT_PALETTE, parallel = false)
        val parallel = runScenario(Scenario.DEFAULT_PALETTE, parallel = true)

        assertTrue(serial.floatCount > 0)
        assertEquals(serial.floatCount, parallel.floatCount)
        for (index in 0 until serial.floatCount) {
            assertEquals(
                java.lang.Float.floatToRawIntBits(serial.vertices[index]).toLong(),
                java.lang.Float.floatToRawIntBits(parallel.vertices[index]).toLong()
            )
        }
        // 默认色板下闪点与体光整段不进入，暗带是唯一几何来源。
        assertTrue(serial.glintCount.all { it == 0 })
        assertTrue(serial.bodyLightCount.all { it == 0 })
        assertTrue(serial.backShadeCount.any { it > 0 })
    }

    private enum class Scenario { DEFAULT_PALETTE, GLINTS_ON, GLINTS_AND_BODY_LIGHT }

    private class Snapshot(
        val floatCount: Int,
        val vertices: FloatArray,
        val layerFirstVertex: IntArray,
        val layerVertexCount: IntArray,
        val backShadeCount: IntArray,
        val interfaceShoulderCount: IntArray,
        val bodyLightCount: IntArray,
        val glintCount: IntArray,
        val glintTrackCount: IntArray,
        val glitterBirths: Int
    )

    /**
     * 两路各自从同一组确定性初值起步跑同样多帧。`FableSolSimulation` 与
     * `FableSolGlOptics` 都是自带种子的确定性对象，因此两次运行的输入序列完全相同，
     * 差异只可能来自层任务的调度方式。
     */
    private fun runScenario(scenario: Scenario, parallel: Boolean): Snapshot {
        val params = FableSolParams()
        if (scenario != Scenario.DEFAULT_PALETTE) {
            params.setForTest("glint_capacity_gain", 1.0)
        }
        if (scenario == Scenario.GLINTS_AND_BODY_LIGHT) {
            params.setForTest("body_light_strength", 0.6)
        }
        val sim = FableSolSimulation(params)
        val optics = FableSolGlOptics(DENSITY)
        optics.parallelLayerBuildEnabled = parallel
        val water = syntheticWater(COLUMNS)
        val start = Array(FableSolSpec.N_LAYERS) { intArrayOf(52, 108, 180) }
        val end = Array(FableSolSpec.N_LAYERS) { intArrayOf(92, 150, 208) }
        // 界面肩权重在 D135 色板下恒为零；这里显式给非零值，把那两条带也拉进对比。
        val interfaceWeights = FloatArray(FableSolSpec.N_LAYERS) { boundary ->
            if (boundary == 0) 0f else 0.85f
        }
        sim.sparkle01 = 1.0
        for (layer in 0..5) sim.layers[layer].roughness01 = 0.38

        var floatCount = 0
        repeat(FRAMES) {
            sim.update(1.0 / 60.0)
            floatCount = optics.build(
                sim,
                params,
                COLUMNS,
                water,
                start,
                end,
                HORIZON,
                interfaceWeightStart = interfaceWeights,
                interfaceWeightStop1 = interfaceWeights,
                interfaceWeightStop2 = interfaceWeights,
                interfaceWeightEnd = interfaceWeights
            )
        }
        return Snapshot(
            floatCount = floatCount,
            vertices = optics.vertices.copyOf(floatCount),
            layerFirstVertex = optics.layerFirstVertex.copyOf(),
            layerVertexCount = optics.layerVertexCount.copyOf(),
            backShadeCount = optics.backShadeVertexCountForTest.copyOf(),
            interfaceShoulderCount = optics.interfaceShoulderVertexCountForTest.copyOf(),
            bodyLightCount = optics.bodyLightVertexCountForTest.copyOf(),
            glintCount = optics.glintVertexCountForTest.copyOf(),
            glintTrackCount = IntArray(FableSolSpec.N_LAYERS) {
                optics.glintTrackCountForTest(it)
            },
            glitterBirths = optics.glitterBirthsForTest
        )
    }

    private fun syntheticWater(columns: Int, spacingPx: Double = 3.0): FloatArray {
        val rows = FableSolContinuousSurface.Z_ROWS
        val data = FloatArray(rows * columns * FableSolGlMeshLayout.COMPONENTS_PER_VERTEX)
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val offset = (row * columns + column) * FableSolGlMeshLayout.COMPONENTS_PER_VERTEX
                data[offset] = (column * spacingPx).toFloat()
                data[offset + 1] = (
                    120.0 + 6.0 * row + 9.0 * sin(column * 0.21 + row * 0.13)
                    ).toFloat()
                data[offset + 2] = (0.06 * sin(column * 0.37 + row * 0.05)).toFloat()
                data[offset + 3] = (0.04 * sin(column * 0.19 - row * 0.11)).toFloat()
            }
        }
        return data
    }

    private companion object {
        const val COLUMNS = 120
        const val DENSITY = 2.5
        const val FRAMES = 90
        val HORIZON = intArrayOf(140, 176, 214)
    }
}
