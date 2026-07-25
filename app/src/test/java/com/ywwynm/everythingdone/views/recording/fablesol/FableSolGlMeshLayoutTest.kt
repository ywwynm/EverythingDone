package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FableSolGlMeshLayoutTest {

    @Test
    fun `water vertex keeps crest pinch and adds an independent sheen slope`() {
        // 分量 8：到本层上轮廓的法向像素距离（D220 引入、D221 由 |∇depth01| 改为
        // 真实距离——除行列式的换算在层带倒转处会爆成亮块）。
        assertEquals(9, FableSolGlMeshLayout.COMPONENTS_PER_VERTEX)
        assertEquals(6, FableSolGlMeshLayout.SHEEN_SLOPE_X_OFFSET)
        assertEquals(7, FableSolGlMeshLayout.SHEEN_SLOPE_Z_OFFSET)
        assertEquals(8, FableSolGlMeshLayout.RIM_DISTANCE_OFFSET)
    }

    @Test
    fun `continuous mesh covers all eight depth groups`() {
        val columns = 120
        val indices = FableSolGlMeshLayout.buildIndices(columns)

        assertEquals(
            FableSolGlMeshLayout.indicesPerGroup(columns) * FableSolGlMeshLayout.GROUP_COUNT,
            indices.size
        )
        val maxVertex = indices.maxOf { it.toInt() and 0xffff }
        assertTrue(maxVertex < FableSolGlMeshLayout.vertexCount(columns))
        assertEquals(FableSolContinuousSurface.Z_ROWS * columns - 1, maxVertex)
    }

    @Test
    fun `each group begins at its far anchor and ends at its near anchor`() {
        val columns = 5
        val indices = FableSolGlMeshLayout.buildIndices(columns)
        val perGroup = FableSolGlMeshLayout.indicesPerGroup(columns)

        // 第一组是最远区间：第一只三角形连接最后一行与其近侧相邻行。
        assertEquals((FableSolContinuousSurface.Z_ROWS - 1) * columns,
            indices[0].toInt() and 0xffff)
        assertEquals((FableSolContinuousSurface.Z_ROWS - 2) * columns,
            indices[1].toInt() and 0xffff)
        // 最后一组是 layer 1，全部索引仍落在第一个产品层区间。
        val lastGroup = indices.copyOfRange(perGroup * 7, indices.size)
        assertTrue(lastGroup.all {
            (it.toInt() and 0xffff) <
                (FableSolContinuousSurface.ROWS_PER_LAYER + 1) * columns
        })
    }

    @Test
    fun `uint16 indices cover the maximum renderer column count and reject overflow`() {
        val maximumRendererColumns = FableSolSpec.N_POINTS
        val maximumVertex = FableSolGlMeshLayout.vertexCount(maximumRendererColumns) - 1

        assertTrue(maximumVertex <= 0xffff)
        FableSolGlMeshLayout.buildIndices(maximumRendererColumns)

        val overflowingColumns = 0x10000 / FableSolContinuousSurface.Z_ROWS + 1
        try {
            FableSolGlMeshLayout.buildIndices(overflowingColumns)
            throw AssertionError("应拒绝超出 GL_UNSIGNED_SHORT 范围的网格")
        } catch (_: IllegalArgumentException) {
            // 预期结果。
        }
    }

    @Test
    fun `index buffer is uploaded again after GL resources are recreated`() {
        val state = FableSolGlIndexBufferState()

        assertTrue(state.requiresUpload(120))
        state.onUploaded(120)
        assertTrue(!state.requiresUpload(120))

        state.onGlResourcesReleased()

        assertTrue(state.requiresUpload(120))
        assertEquals(0, state.indexCountPerGroup)
    }

    @Test
    fun `rim distance measures the normal gap to this band's own contour`() {
        // D221：银丝消费的是真实法向距离。合成一张几何：轮廓水平、行间距固定，
        // 于是法向距离退化为纯 y 差，答案可以手算。
        val columns = 4
        val rows = FableSolContinuousSurface.Z_ROWS
        val rowsPerLayer = FableSolContinuousSurface.ROWS_PER_LAYER
        val stride = FableSolGlMeshLayout.COMPONENTS_PER_VERTEX
        val data = FloatArray(rows * columns * stride)
        // y 随 row 递减（row 越大越远、屏幕越靠上），x 等距。
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val base = (row * columns + column) * stride
                data[base] = column * 10f
                data[base + 1] = 500f - row * 2f
            }
        }

        FableSolGlMeshLayout.writeRimContourDistance(data, rows, columns)

        fun value(row: Int, column: Int) =
            data[(row * columns + column) * stride + FableSolGlMeshLayout.RIM_DISTANCE_OFFSET]

        // 带 1 覆盖 row 0..12，上轮廓是 row 12：row r 的距离 = (12 − r) × 2px。
        assertEquals(24f, value(0, 1), 1e-3f)
        assertEquals(2f, value(11, 1), 1e-3f)
        // row 12 存的是"到上方那个锚行 row 24"的距离 = 本带（带 2）带高 24px；
        // 绘制带 1 时它是上轮廓，由 water.vert 按 uStartLayer 归零。
        assertEquals(24f, value(12, 1), 1e-3f)
        // 带 2 内部同理量到 row 24。
        assertEquals(22f, value(13, 1), 1e-3f)
        // 最远锚行没有更上方的锚行，恒 0。
        assertEquals(0f, value(rows - 1, 1), 0f)
        // 每一行都落在 [0, 带高] 内，不出现负值或爆炸值。
        for (row in 0 until rows - 1) {
            val d = value(row, 2)
            assertTrue("row=$row d=$d", d >= -1e-3f && d <= rowsPerLayer * 2f + 1e-3f)
        }
    }

    @Test
    fun `rim distance stays finite when a layer band is inverted`() {
        // 层带倒转（近层轮廓跑到远层轮廓上方）是波形起伏下的真实几何。旧的
        // |∇depth01| 换算要除雅可比行列式，此时 det→0、梯度爆到约 1e7，银丝会
        // 铺满整列变成亮块；真实距离场只做减法，倒转只是让距离变负。
        val columns = 4
        val rows = FableSolContinuousSurface.Z_ROWS
        val stride = FableSolGlMeshLayout.COMPONENTS_PER_VERTEX
        val data = FloatArray(rows * columns * stride)
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val base = (row * columns + column) * stride
                data[base] = column * 10f
                // 第 1 列整体抬高 40px，制造与相邻列反向的层带
                data[base + 1] = 500f - row * 2f + if (column == 1) 40f else 0f
            }
        }

        FableSolGlMeshLayout.writeRimContourDistance(data, rows, columns)

        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val d = data[(row * columns + column) * stride +
                    FableSolGlMeshLayout.RIM_DISTANCE_OFFSET]
                assertTrue("row=$row col=$column d=$d", d.isFinite())
                assertTrue("row=$row col=$column d=$d", kotlin.math.abs(d) < 1000f)
            }
        }
    }

    @Test
    fun `optical blending preserves opaque framebuffer alpha`() {
        assertEquals(1.0, FableSolGlOpticalBlendPolicy.resultingAlpha(1.0), 0.0)
    }

}
