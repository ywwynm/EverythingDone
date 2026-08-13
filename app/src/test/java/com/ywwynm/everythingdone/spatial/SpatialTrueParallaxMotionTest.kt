package com.ywwynm.everythingdone.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 真透视位移基必须与网页端逐条对齐。判据不是"看起来合理"，而是**与网页端那条公式
 * 逐像素相等**：
 *
 *     u' = u + fx·tx·(1/Z0 − 1/Z)
 *
 * 用户 2026-08-12 反馈端上"像直接对图片做 warp"，实测那份基交叉项不为零、两个主项也不
 * 同源（D204）。这里把那两条立成硬断言，以后再有人换求解器也不会悄悄退回去。
 */
class SpatialTrueParallaxMotionTest {

    private val width = 60
    private val height = 80
    private val fx = 634.7f
    private val fy = 634.7f

    /** 前中后三层，跨度足够大，能把交叉项/不同源的问题暴露出来。 */
    private fun layeredDepth(): FloatArray = FloatArray(width * height) { i ->
        when (i % width) {
            in 0 until 20 -> 1.2f
            in 20 until 40 -> 2.5f
            else -> 8.0f
        }
    }

    @Test
    fun 位移与网页端公式逐像素相等() {
        val depth = layeredDepth()
        val r = SpatialTrueParallaxMotion.build(depth, width, height, fx, fy)
        val baseline = 0.045f
        val z0 = r.pivotDepth
        for (i in intArrayOf(0, 25, 55, 1000, width * height - 1)) {
            val expectedNormX = fx * baseline * (1f / z0 - 1f / depth[i]) / width
            val d = r.basis.displacement(i, viewpointX = 1f, viewpointY = 0f, amplitude = baseline)
            assertEquals("像素 $i 的水平位移与网页端公式不符", expectedNormX, d.x, 1e-6f)
            assertEquals("水平视点不应产生竖直位移", 0f, d.y, 1e-9f)
        }
    }

    @Test
    fun 交叉项恒为零() {
        val r = SpatialTrueParallaxMotion.build(layeredDepth(), width, height, fx, fy)
        assertTrue("horizontalY 必须恒为 0", r.basis.horizontalY.all { it == 0f })
        assertTrue("verticalX 必须恒为 0", r.basis.verticalX.all { it == 0f })
    }

    @Test
    fun 两个主项同源() {
        // horizontalX/(fx/width) 与 verticalY/(fy/height) 必须是同一个标量场
        val r = SpatialTrueParallaxMotion.build(layeredDepth(), width, height, fx, fy)
        for (i in r.basis.horizontalX.indices) {
            val a = r.basis.horizontalX[i] / (fx / width)
            val b = r.basis.verticalY[i] / (fy / height)
            assertEquals("像素 $i 的两个主项不同源", a, b, 1e-6f)
        }
    }

    @Test
    fun 支点处位移为零() {
        val depth = layeredDepth()
        val r = SpatialTrueParallaxMotion.build(depth, width, height, fx, fy)
        val at = depth.indices.first { abs(depth[it] - r.pivotDepth) < 1e-6f }
        val d = r.basis.displacement(at, 1f, 1f, 0.045f)
        assertEquals(0f, d.x, 1e-7f)
        assertEquals(0f, d.y, 1e-7f)
    }

    @Test
    fun 支点取主体深度中位数() {
        val depth = layeredDepth()
        // 只把最近那一层标成主体
        val subject = BooleanArray(depth.size) { depth[it] < 1.5f }
        val r = SpatialTrueParallaxMotion.build(depth, width, height, fx, fy, subject)
        assertEquals(1.2f, r.pivotDepth, 1e-4f)
        // 没有主体时退回全图中位数
        val r2 = SpatialTrueParallaxMotion.build(depth, width, height, fx, fy, null)
        assertEquals(2.5f, r2.pivotDepth, 1e-4f)
    }

    @Test
    fun 相对视差是可核对的物理量() {
        val depth = layeredDepth()
        val subject = BooleanArray(depth.size) { depth[it] < 1.5f }
        val r = SpatialTrueParallaxMotion.build(depth, width, height, fx, fy, subject, 0.045f)
        // 支点 1.2 m、最远 8.0 m，4.5cm 基线下：fx·b·(1/1.2 − 1/8.0)
        val expected = fx * 0.045f * (1f / 1.2f - 1f / 8.0f)
        assertEquals(expected, r.relativeParallaxPixels, 0.5f)
    }

    @Test
    fun 目标视差换算回基线是自洽的() {
        val depth = layeredDepth()
        val r = SpatialTrueParallaxMotion.build(depth, width, height, fx, fy)
        val target = 43.1f            // 网页端 00 场景在 4.5cm 下的目标视差
        val longEdge = maxOf(width, height)
        val baseline = SpatialTrueParallaxMotion.baselineForTargetDisparity(r, target, longEdge)
        // 用换算出的基线再算一遍相对视差，应当正好等于目标（按长边归一）
        val got = r.relativeParallaxPixels / 0.045f * baseline
        assertEquals(target * longEdge / 720f, got, 0.01f)
    }

    @Test
    fun 主体掩码按阈值最近邻重采样到网格() {
        // 左半 alpha=1、右半 alpha=0，外加一列 0.5 附近的过渡值：过渡值不得并入主体，
        // 否则支点会被一圈背景像素拉偏（网页端同阈值 0.5）
        val aw = 8
        val ah = 4
        val alpha = FloatArray(aw * ah) { i ->
            when (i % aw) {
                in 0 until 3 -> 1f
                3 -> 0.5f          // 恰在阈值上：> 0.5 为假，不算主体
                4 -> 0.51f
                else -> 0f
            }
        }
        val mask = SpatialTrueParallaxMotion.subjectMaskFrom(alpha, aw, ah, aw, ah)
        for (y in 0 until ah) {
            for (x in 0 until aw) {
                val expected = x < 3 || x == 4
                assertEquals("($x,$y)", expected, mask[y * aw + x])
            }
        }
        // 缩放到不同网格尺寸时仍保持左主体右背景的分布
        val scaled = SpatialTrueParallaxMotion.subjectMaskFrom(alpha, aw, ah, 4, 2)
        assertTrue("缩放后左侧仍是主体", scaled[0] && scaled[4])
        assertTrue("缩放后右侧仍是背景", !scaled[3] && !scaled[7])
    }

    @Test
    fun 支点用主体掩码时不再是全图中位数() {
        val depth = layeredDepth()
        // 只有最近那层是主体：支点应当是 1.2，而不是全图中位 2.5
        val alpha = FloatArray(depth.size) { if (depth[it] < 1.5f) 1f else 0f }
        val subject = SpatialTrueParallaxMotion.subjectMaskFrom(
            alpha, width, height, width, height
        )
        val withSubject = SpatialTrueParallaxMotion.build(
            depth, width, height, fx, fy, subject
        )
        val without = SpatialTrueParallaxMotion.build(depth, width, height, fx, fy, null)
        assertEquals(1.2f, withSubject.pivotDepth, 1e-4f)
        assertEquals(2.5f, without.pivotDepth, 1e-4f)
        // 主体支点下标量场应当**没有**正负各半的对称性——那是全图中位数的特征
        val positive = withSubject.basis.horizontalX.count { it > 0f }
        assertTrue(
            "主体支点下位移不应正负各半（那说明支点仍是全图中位数）",
            positive.toFloat() / withSubject.basis.horizontalX.size > 0.6f
        )
    }

    /**
     * 复刻 SURFEL_VERTEX_SHADER 的那一行：
     * `targetUv = aSourceUv − uParallaxMotion * aMotionScalar * uScalarToUv`，
     * 其中 `uScalarToUv = (1/(W·P), 1/(H·P))`、`uParallaxMotion = 视点 · 幅度`。
     */
    private fun shaderDisplacement(
        scalar: Float, viewpoint: Float, amplitude: Float, edge: Int, parallax: Float
    ): Float = -(viewpoint * amplitude) * scalar / (edge * parallax)

    @Test
    fun surfel标量经_shader_公式还原出与基相同的位移() {
        // 实际出像素的是 surfel 路径，标量只有一个；这条断言保证它与四分量基等价。
        val depth = layeredDepth()
        val r = SpatialTrueParallaxMotion.build(depth, width, height, fx, fy)
        val p = 0.16f
        val scalars = SpatialTrueParallaxMotion.surfelScalars(r, p)
        val b = 0.045f
        for (i in intArrayOf(0, 25, 55, 1000, width * height - 1)) {
            val fromBasis = r.basis.displacement(i, viewpointX = 1f, viewpointY = 0f, amplitude = b)
            val fromShader = shaderDisplacement(scalars[i], 1f, b, width, p)
            assertEquals("像素 $i 的水平位移：shader 口径与基不符", fromBasis.x, fromShader, 1e-7f)
            val fromBasisV = r.basis.displacement(i, viewpointX = 0f, viewpointY = 1f, amplitude = b)
            val fromShaderV = shaderDisplacement(scalars[i], 1f, b, height, p)
            assertEquals("像素 $i 的竖直位移：shader 口径与基不符", fromBasisV.y, fromShaderV, 1e-7f)
        }
    }

    @Test
    fun 近景标量为正_与_V13_的符号约定一致() {
        // shader 里是减号：近景标量必须为正，才会向视点反方向移动。
        // s = 1/Z0 − 1/Z 在近景为负，surfelScalars 的负号就是为了抵消它。
        val depth = layeredDepth()
        val subject = BooleanArray(depth.size) { depth[it] > 7f }   // 支点放在最远层
        val r = SpatialTrueParallaxMotion.build(depth, width, height, fx, fy, subject)
        val scalars = SpatialTrueParallaxMotion.surfelScalars(r, 0.16f)
        val near = depth.indices.first { depth[it] < 1.5f }
        val far = depth.indices.first { depth[it] > 7f }
        assertTrue("近景标量必须为正（V13 约定），实测 ${scalars[near]}", scalars[near] > 0f)
        assertEquals("支点处标量必须为零", 0f, scalars[far], 1e-6f)
    }

    @Test
    fun fx与fy不等时两轴误差对半分() {
        // patch-14 对齐会让 meshFx/meshFy 差 2.8%；单标量只能取几何平均，
        // 两轴各承担一半。这条把"一半"钉住，避免以后有人改成只对齐某一轴。
        val fxA = 600f
        val fyA = fxA * 1.028f
        val r = SpatialTrueParallaxMotion.build(layeredDepth(), width, height, fxA, fyA)
        val p = 0.16f
        val scalars = SpatialTrueParallaxMotion.surfelScalars(r, p)
        val b = 0.045f
        // 取一个**不在支点上**的像素：支点处位移为零，相对误差会算成 NaN
        val i = 5
        val hx = r.basis.displacement(i, 1f, 0f, b).x
        val vy = r.basis.displacement(i, 0f, 1f, b).y
        val sx = shaderDisplacement(scalars[i], 1f, b, width, p)
        val sy = shaderDisplacement(scalars[i], 1f, b, height, p)
        val errX = abs(sx / hx - 1f)
        val errY = abs(sy / vy - 1f)
        // 几何平均下两轴误差是 √r−1 与 1−1/√r，一阶相等、二阶差一点（1.390% vs
        // 1.371%）。判据只要求"对半分"，不是逐位相等。
        assertEquals("两轴误差应当对半分", errX, errY, 5e-4f)
        assertTrue("单轴误差应当只有总差 2.8% 的一半，实测 ${errX * 100}%", errX < 0.015f)
    }

    @Test
    fun 无效深度退回支点而不是炸开() {
        val depth = layeredDepth()
        depth[0] = Float.NaN
        depth[1] = 0f
        depth[2] = Float.POSITIVE_INFINITY
        val r = SpatialTrueParallaxMotion.build(depth, width, height, fx, fy)
        assertTrue(r.basis.horizontalX.all { it.isFinite() })
        // 无效像素按支点处理 ⇒ 位移为 0
        assertEquals(0f, r.basis.horizontalX[0], 1e-7f)
        assertEquals(0f, r.basis.horizontalX[1], 1e-7f)
    }

    @Test
    fun 取景内缩必须盖住任意方向的最大位移() {
        val depth = layeredDepth()
        val r = SpatialTrueParallaxMotion.build(depth, width, height, fx, fy)
        val b = 0.045f
        val margin = SpatialTrueParallaxMotion.coverMarginFraction(r, b, samplingGuard = 0f)
        // 逐像素、逐方向暴力核一遍：任何视点下的归一化位移都不得超出内缩
        var worst = 0f
        for (deg in 0 until 360 step 15) {
            val a = deg * Math.PI.toFloat() / 180f
            val vx = kotlin.math.cos(a)
            val vy = kotlin.math.sin(a)
            for (i in r.basis.horizontalX.indices) {
                val d = r.basis.displacement(i, vx, vy, b)
                val reach = kotlin.math.sqrt(d.x * d.x + d.y * d.y)
                if (reach > worst) worst = reach
            }
        }
        // 盖住实测最大位移；**除非已经撞到上限**——那时再放大就把画面裁没了，
        // 代价是极端视点下边缘会取到钳位的边界色，这是既有策略的取舍。
        val capped = margin >= 0.20f - 1e-6f
        assertTrue(
            "内缩 $margin 既没盖住实测最大位移 $worst，也没有封顶",
            capped || margin >= worst - 1e-6f
        )
        // 也不能过度保守：柯西–施瓦茨的上界与实际最大值之比不应离谱
        assertTrue(
            "内缩 $margin 相对实测 $worst 过度保守",
            capped || margin <= worst * 1.5f + 1e-6f
        )
    }

    @Test
    fun 取景内缩有下限也有上限() {
        // 全平面深度 => 位移恒为零，但仍需保留采样保护，不能返回 0
        val flat = FloatArray(width * height) { 2.0f }
        val r = SpatialTrueParallaxMotion.build(flat, width, height, fx, fy)
        val m = SpatialTrueParallaxMotion.coverMarginFraction(r, 0.045f)
        assertTrue("平面场的内缩应当等于采样保护，实测 $m", m in 0.011f..0.013f)

        // 极端深度跨度不得让内缩突破上限（否则画面被裁得只剩中间一小块）
        val wild = FloatArray(width * height) { if (it % 2 == 0) 0.06f else 40f }
        val r2 = SpatialTrueParallaxMotion.build(wild, width, height, fx, fy)
        val m2 = SpatialTrueParallaxMotion.coverMarginFraction(r2, 0.045f, maximum = 0.20f)
        assertEquals(0.20f, m2, 1e-6f)
    }
}
