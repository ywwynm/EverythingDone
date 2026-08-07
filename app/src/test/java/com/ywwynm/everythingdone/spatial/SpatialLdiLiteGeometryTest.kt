package com.ywwynm.everythingdone.spatial

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialLdiLiteGeometryTest {

    @Test
    fun depthStepBecomesCutAndReceivesFarBackgroundBand() {
        val width = 16
        val height = 8
        val colors = IntArray(width * height) { index ->
            if (index % width < width / 2) 0xffeeeeee.toInt() else 0xff222222.toInt()
        }
        val depth = FloatArray(width * height) { index ->
            if (index % width < width / 2) 0.85f else 0.20f
        }
        val result = SpatialLdiLiteGeometryBuilder.build(
            colorPixels = colors,
            width = width,
            height = height,
            sourceDepth = spatialDepth(width, height, depth)
        )

        for (y in 0 until height) {
            assertTrue(result.cutRight[y * (width - 1) + width / 2 - 1])
        }
        assertTrue(result.hiddenBackgroundMask.any { it })
        val hiddenNearSide = (0 until height).flatMap { y ->
            (0 until width / 2).map { x -> y * width + x }
        }.filter { result.hiddenBackgroundMask[it] }
        assertTrue(hiddenNearSide.isNotEmpty())
        assertTrue(hiddenNearSide.all {
            result.backgroundDepth[it] < result.surfaceDepth[it]
        })
    }

    @Test
    fun smoothSurfaceRemainsConnectedAndWithinLocalMotionBudget() {
        val width = 36
        val height = 24
        val colors = IntArray(width * height) { 0xff777777.toInt() }
        val depth = FloatArray(width * height) { index ->
            val x = index % width
            0.3f + x.toFloat() / (width - 1) * 0.4f
        }
        val result = SpatialLdiLiteGeometryBuilder.build(
            colors,
            width,
            height,
            spatialDepth(width, height, depth)
        )

        assertFalse(result.cutRight.any { it })
        assertFalse(result.cutDown.any { it })
        val amplitude = SpatialRenderDepthStabilizer.MAX_PARALLAX_AMPLITUDE
        for (y in 0 until height) {
            for (x in 0 until width - 1) {
                val left = result.surfaceDepth[y * width + x]
                val right = result.surfaceDepth[y * width + x + 1]
                val localScale = 1f - amplitude * width * (right - left)
                assertTrue(
                    "局部尺度越界：$localScale",
                    localScale in 0.819f..1.181f
                )
            }
        }
    }

    @Test
    fun connectedEdgesDoNotCrossDeclaredCuts() {
        val width = 12
        val height = 9
        val colors = IntArray(width * height) { index ->
            val x = index % width
            if (x < 5) 0xffff0000.toInt() else 0xff0000ff.toInt()
        }
        val depth = FloatArray(width * height) { index ->
            if (index % width < 5) 0.9f else 0.1f
        }
        val result = SpatialLdiLiteGeometryBuilder.build(
            colors,
            width,
            height,
            spatialDepth(width, height, depth)
        )

        for (y in 0 until height - 1) {
            for (x in 0 until width - 1) {
                val topRightTriangleExists =
                    !result.cutRight[y * (width - 1) + x] &&
                        !result.cutDown[y * width + x + 1]
                val bottomLeftTriangleExists =
                    !result.cutDown[y * width + x] &&
                        !result.cutRight[(y + 1) * (width - 1) + x]
                if (x == 4) {
                    assertFalse(topRightTriangleExists)
                    assertFalse(bottomLeftTriangleExists)
                }
            }
        }
    }

    @Test
    fun foregroundRimNextToCutKeepsItsSourceConnectivityAndReceivesHiddenBackground() {
        val width = 12
        val height = 9
        val colors = IntArray(width * height) { index ->
            if (index % width < 5) 0xffff0000.toInt() else 0xff0000ff.toInt()
        }
        val depth = FloatArray(width * height) { index ->
            if (index % width < 5) 0.9f else 0.1f
        }

        val result = SpatialLdiLiteGeometryBuilder.build(
            colors, width, height, spatialDepth(width, height, depth)
        )

        for (y in 0 until height) {
            // 只有真实台阶（x=4/5）断开；近侧 x=4 仍与同一表面的 x=3、上下格连通。
            assertFalse(result.cutRight[y * (width - 1) + 3])
            assertTrue(result.cutRight[y * (width - 1) + 4])
            if (y > 0) assertFalse(result.cutDown[(y - 1) * width + 4])
            if (y < height - 1) assertFalse(result.cutDown[y * width + 4])
            // 隐藏背景层仍在近侧下方扩展，前景移开后可承接显露区。
            assertTrue(result.hiddenBackgroundMask[y * width + 4])
        }
    }

    @Test
    fun lowContrastSmoothedDepthEdgeStillCutsAndFillsBackground() {
        val width = 64
        val height = 48
        // 颜色完全均匀：RGB 门失效，引导滤波把台阶摊成逐格差低于断边阈值的宽过渡带。
        val colors = IntArray(width * height) { 0xff808080.toInt() }
        val depth = FloatArray(width * height) { index ->
            if (index % width < width / 2) 0.65f else 0.35f
        }

        val result = SpatialLdiLiteGeometryBuilder.build(
            colors, width, height, spatialDepth(width, height, depth)
        )

        // 深度边缘吸附后，中缝附近必须出现断边，而不是被磨成连通宽坡。
        val y = height / 2
        var cutCount = 0
        for (x in width / 2 - 8 until width / 2 + 8) {
            if (result.cutRight[y * (width - 1) + x]) cutCount++
        }
        assertTrue("低对比度深度边未被截断", cutCount >= 1)
        // 断边远侧存在背景扩展与隐藏背景标记，显露区域可被补图覆盖。
        assertTrue(result.hiddenBackgroundMask.any { it })
    }

    @Test
    fun metricRatioKeepsOcclusionAndHealsInteriorCrease() {
        val width = 24
        val height = 8
        // 三段：主体近段（逆深度 1.05）、主体远段（0.95，褶皱：比值 1.11 < 1.2）、
        // 背景（0.4，遮挡：0.95/0.4 = 2.4）。归一化视差同位置给出 ≥0.05 的步进，
        // 旧判据会把褶皱也切开。
        val colors = IntArray(width * height) { 0xff888888.toInt() }
        fun segment(x: Int): Int = when {
            x < 8 -> 0
            x < 16 -> 1
            else -> 2
        }
        val normalized = floatArrayOf(0.90f, 0.80f, 0.15f)
        val inverse = floatArrayOf(1.05f, 0.95f, 0.40f)
        val depth = FloatArray(width * height) { normalized[segment(it % width)] }
        val metric = FloatArray(width * height) { inverse[segment(it % width)] }

        val result = SpatialLdiLiteGeometryBuilder.build(
            colorPixels = colors,
            width = width,
            height = height,
            sourceDepth = spatialDepth(width, height, depth, metric)
        )

        val middleRow = height / 2
        // 褶皱边（x=7/8）被愈合，遮挡边（x=15/16）保留。
        assertFalse(result.cutRight[middleRow * (width - 1) + 7])
        assertTrue(result.cutRight[middleRow * (width - 1) + 15])
    }

    @Test
    fun metricRatioTreatsInfinityAsOcclusion() {
        val width = 16
        val height = 8
        val colors = IntArray(width * height) { 0xff888888.toInt() }
        val depth = FloatArray(width * height) { if (it % width < 8) 0.9f else 0.1f }
        val metric = FloatArray(width * height) { if (it % width < 8) 1.2f else 0f }

        val result = SpatialLdiLiteGeometryBuilder.build(
            colorPixels = colors,
            width = width,
            height = height,
            sourceDepth = spatialDepth(width, height, depth, metric)
        )
        assertTrue(result.cutRight[(height / 2) * (width - 1) + 7])
    }

    @Test
    fun metricOcclusionInsideSameOwnershipGroupIsHealed() {
        val width = 16
        val height = 8
        val colors = IntArray(width * height) { 0xff888888.toInt() }
        val depth = FloatArray(width * height) { if (it % width < 8) 0.9f else 0.15f }
        val metric = FloatArray(width * height) { if (it % width < 8) 1.2f else 0.4f }
        fun buildWith(groups: ByteArray?) = SpatialLdiLiteGeometryBuilder.build(
            colorPixels = colors,
            width = width,
            height = height,
            sourceDepth = spatialDepth(width, height, depth, metric),
            ownershipGroups = groups
        )

        // 同一非 0 组：装配体内部禁断，比值再大也连续。
        val sameGroup = buildWith(ByteArray(width * height) { 1 })
        assertFalse(sameGroup.cutRight[(height / 2) * (width - 1) + 7])
        // 跨组（实例对场景）：交还深度比判据，遮挡保留。
        val crossGroup = buildWith(
            ByteArray(width * height) { if (it % width < 8) 1 else 0 }
        )
        assertTrue(crossGroup.cutRight[(height / 2) * (width - 1) + 7])
    }

    @Test
    fun groupGuidedRefinementPullsHaloBackToItsSide() {
        val width = 20
        val height = 8
        val colors = IntArray(width * height) { 0xff888888.toInt() }
        // mask 边界在 x=9/10；深度边错位到 x=7/8：x=8..9 是"挂着背景深度的实例格"
        //（halo）。修正后这两列应回归实例侧水平，断边落在组边界。
        val groups = ByteArray(width * height) { if (it % width < 10) 1 else 0 }
        val depth = FloatArray(width * height) { if (it % width < 8) 0.9f else 0.15f }
        val metric = FloatArray(width * height) { if (it % width < 8) 1.2f else 0.4f }

        val result = SpatialLdiLiteGeometryBuilder.build(
            colorPixels = colors,
            width = width,
            height = height,
            sourceDepth = spatialDepth(width, height, depth, metric),
            ownershipGroups = groups
        )

        val middleRow = height / 2
        // halo 格深度已回归实例侧（显著高于背景 0.15 水平）。
        assertTrue(result.surfaceDepth[middleRow * width + 8] > 0.6f)
        assertTrue(result.surfaceDepth[middleRow * width + 9] > 0.6f)
        // 断边只在组边界（x=9/10），不再出现在错位的旧深度边（x=7/8）。
        assertFalse(result.cutRight[middleRow * (width - 1) + 7])
        assertTrue(result.cutRight[middleRow * (width - 1) + 9])
    }

    @Test
    fun interiorSpikeInsideInstanceIsDenoised() {
        val width = 16
        val height = 10
        val colors = IntArray(width * height) { 0xff888888.toInt() }
        val groups = ByteArray(width * height) { 1 }
        val depth = FloatArray(width * height) { 0.8f }
        val metric = FloatArray(width * height) { 1.0f }
        val spike = (height / 2) * width + width / 2
        depth[spike] = 0.4f
        metric[spike] = 0.5f

        val result = SpatialLdiLiteGeometryBuilder.build(
            colorPixels = colors,
            width = width,
            height = height,
            sourceDepth = spatialDepth(width, height, depth, metric),
            ownershipGroups = groups
        )

        // 同组 3×3 中位抑噪：孤立尖噪回归本组水平，不留断边。
        assertTrue(kotlin.math.abs(result.surfaceDepth[spike] - 0.8f) < 0.05f)
        assertFalse(result.cutRight.any { it })
        assertFalse(result.cutDown.any { it })
    }

    @Test
    fun instanceResidualsAreSoftLimitedWhileSceneKeepsFullSweep() {
        val width = 80
        val height = 6
        val colors = IntArray(width * height) { 0xff888888.toInt() }
        // 左段：实例组 1，内部大跨度斜坡 0.2→1.0（残差 ±0.4）；右段：场景组 0，
        // 60 格长坡 0.1→0.7（宽于 D47 吸附带上限，保留完整扫掠）。比值场让分界
        // 成为遮挡断边。
        val groups = ByteArray(width * height) { if (it % width < 20) 1 else 0 }
        val depth = FloatArray(width * height) { index ->
            val x = index % width
            if (x < 20) 0.2f + 0.8f * x / 19f else 0.1f + 0.6f * (x - 20) / 59f
        }
        val metric = FloatArray(width * height) { index ->
            if (index % width < 20) 1.0f else 0.3f
        }

        val result = SpatialLdiLiteGeometryBuilder.build(
            colorPixels = colors,
            width = width,
            height = height,
            // sharpEdges = true：走 DA3 同款路径（跳过引导滤波）；本测试的均匀颜色
            // 会让引导滤波退化成箱型模糊，把长坡端点磨短（真实照片不会）。
            sourceDepth = spatialDepth(width, height, depth, metric, sharpEdges = true),
            ownershipGroups = groups
        )

        val middleRow = height / 2
        fun span(range: IntRange): Float {
            var minimum = 1f
            var maximum = 0f
            for (x in range) {
                val value = result.surfaceDepth[middleRow * width + x]
                if (value < minimum) minimum = value
                if (value > maximum) maximum = value
            }
            return maximum - minimum
        }
        // 实例内 0.8 跨度被软限幅（≤0.40 且保留层次 ≥0.18）。
        assertTrue(span(0 until 20) <= 0.40f)
        // 下限只证"未被压平到贴片"；D47 吸附会把窄坡折成台阶，随后限幅+正则化的
        // 综合跨度约 0.16。
        assertTrue(span(0 until 20) >= 0.12f)
        // 场景组不限幅：0.6 跨度基本保留（正则化仅微调）。
        assertTrue(span(20 until width) >= 0.5f)
    }

    private fun spatialDepth(
        width: Int,
        height: Int,
        values: FloatArray,
        rawInverseDepth: FloatArray? = null,
        sharpEdges: Boolean = false
    ): SpatialDepthData = SpatialDepthData(
        width = width,
        height = height,
        values = values,
        robustRange = 1f,
        strongEdgeRatio = 0f,
        defaultStrength = 0.72f,
        sharpEdges = sharpEdges,
        rawInverseDepth = rawInverseDepth
    )
}
