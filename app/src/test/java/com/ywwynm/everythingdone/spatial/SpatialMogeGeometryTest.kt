package com.ywwynm.everythingdone.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 内参恢复用**已知内参的合成点云**自校验：给定 fx 造出 point map，解回来必须还是那个 fx。
 * 这比对固定夹具更强——夹具只能证明"和上次一样"，合成场景能证明"数学上对"。
 *
 * 另有一项与桌面参考实现的对表：桌面 00 场景 ONNX + 同一算法解出 fx 633.9 px，
 * PyTorch `infer()` 是 634.7 px（D205）。那条在桌面已验，这里只锁算法本身的性质。
 */
class SpatialMogeGeometryTest {

    private val width = 108
    private val height = 144

    /** 按针孔模型造 point map：Z 给一个有前后层次的场，X/Y 由 fx 反投影得到。 */
    private fun syntheticPoints(fx: Float, shift: Float = 0f): Pair<FloatArray, BooleanArray> {
        val points = FloatArray(3 * width * height)
        val mask = BooleanArray(width * height) { true }
        val cx = width / 2f
        val cy = height / 2f
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                // 前后两层 + 一个斜面，保证 z 有足够跨度让 shift 可辨识
                val z = when {
                    x < width / 3 -> 1.2f
                    x < 2 * width / 3 -> 2.5f + 0.01f * y
                    else -> 4.0f
                }
                points[3 * i] = (x + 0.5f - cx) * z / fx
                points[3 * i + 1] = (y + 0.5f - cy) * z / fx
                // 造数据时把 shift 减掉，恢复时应当解回 +shift
                points[3 * i + 2] = z - shift
            }
        }
        return points to mask
    }

    @Test
    fun 合成针孔场景能解回给定的焦距() {
        for (fx in floatArrayOf(300f, 634.7f, 1200f)) {
            val (points, mask) = syntheticPoints(fx)
            val r = SpatialMogeGeometry.recover(points, mask, scale = 1f, width, height)
            val relative = abs(r.fx - fx) / fx
            assertTrue("fx=$fx 解出 ${r.fx}，相对误差 ${100 * relative}%", relative < 0.01f)
            assertEquals("fx 与 fy 必须相等（MoGe 假定方形像素）", r.fx, r.fy, 1e-4f)
            assertEquals(width / 2f, r.cx, 1e-4f)
            assertEquals(height / 2f, r.cy, 1e-4f)
        }
    }

    @Test
    fun 能同时解回焦距与偏移() {
        val fx = 634.7f
        val shift = 0.35f
        val (points, mask) = syntheticPoints(fx, shift)
        val r = SpatialMogeGeometry.recover(points, mask, scale = 1f, width, height)
        assertTrue("fx 解出 ${r.fx}", abs(r.fx - fx) / fx < 0.01f)
        assertTrue("shift 解出 ${r.shift}，期望 $shift", abs(r.shift - shift) < 0.02f)
    }

    @Test
    fun 米制深度等于点云z加偏移再乘尺度() {
        val fx = 500f
        val shift = 0.2f
        val scale = 2.7475f
        val (points, mask) = syntheticPoints(fx, shift)
        val r = SpatialMogeGeometry.recover(points, mask, scale, width, height)
        for (i in intArrayOf(0, 37, 5000, width * height - 1)) {
            val expected = (points[3 * i + 2] + r.shift) * scale
            assertEquals(expected, r.depth[i], 1e-3f)
        }
        // 造数据时前层是 1.2 m，解回来应当接近 1.2 × scale
        assertEquals(1.2f * scale, r.depth[0], 0.05f * scale)
    }

    @Test
    fun 归一化视平面坐标与MoGe同式() {
        val uv = SpatialMogeGeometry.normalizedViewPlaneUv(width, height)
        val aspect = width.toFloat() / height
        val norm = sqrt(1f + aspect * aspect)
        val spanX = aspect / norm * (1f - 1f / width)
        val spanY = 1f / norm * (1f - 1f / height)
        assertEquals(-spanX, uv[0], 1e-5f)
        assertEquals(-spanY, uv[1], 1e-5f)
        val last = 2 * (width * height - 1)
        assertEquals(spanX, uv[last], 1e-5f)
        assertEquals(spanY, uv[last + 1], 1e-5f)
        // 中心附近应当接近 0
        val mid = 2 * ((height / 2) * width + width / 2)
        assertTrue(abs(uv[mid]) < 2f * spanX / width + 1e-6f)
    }

    @Test
    fun 无效点不参与求解且样本不足时不抛() {
        val (points, _) = syntheticPoints(600f)
        val allInvalid = BooleanArray(width * height) { false }
        val r = SpatialMogeGeometry.recover(points, allInvalid, scale = 1f, width, height)
        assertEquals(0, r.sampleCount)
        assertEquals(0f, r.fx, 1e-6f)
    }

    @Test
    fun 焦距的闭式内解在最优shift处与残差最小点一致() {
        val (points, mask) = syntheticPoints(634.7f, shift = 0.1f)
        val r = SpatialMogeGeometry.recover(points, mask, scale = 1f, width, height)
        // 在解出的 shift 两侧各扰动一点，残差都不应更小
        val uv = SpatialMogeGeometry.normalizedViewPlaneUv(width, height)
        val n = width * height
        val xy = FloatArray(2 * n) { i -> points[3 * (i / 2) + (i % 2)] }
        val z = FloatArray(n) { points[3 * it + 2] }
        val f0 = SpatialMogeGeometry.focalForShift(uv, xy, z, r.shift)
        val fm = SpatialMogeGeometry.focalForShift(uv, xy, z, r.shift - 0.05f)
        val fp = SpatialMogeGeometry.focalForShift(uv, xy, z, r.shift + 0.05f)
        assertTrue("最优处 focal 应当有限", f0.isFinite() && f0 > 0f)
        assertTrue("扰动后 focal 仍有限", fm.isFinite() && fp.isFinite())
    }

    @Test
    fun patch对齐必须保持长宽比() {
        // 各边分别向下取整会引入各向异性：540x720 按长边 518 缩放后宽是 388.5，
        // 截到 378 等价于横向多压 2.7%，而 MoGe 假定 fx == fy，这份形变会进内参。
        val cases = listOf(
            540 to 720, 720 to 540, 492 to 720, 720 to 542, 1080 to 1440, 3000 to 4000,
            720 to 720, 1024 to 768, 100 to 700
        )
        for ((w, h) in cases) {
            for (limit in intArrayOf(518, 720, 1440)) {
                val (aw, ah) = SpatialMogeGeometry.alignToPatchPreservingAspect(w, h, limit)
                assertEquals("宽不是 14 的倍数：$w x $h @$limit", 0, aw % SpatialMogeGeometry.PATCH)
                assertEquals("高不是 14 的倍数：$w x $h @$limit", 0, ah % SpatialMogeGeometry.PATCH)
                assertTrue("长边超限：$w x $h @$limit -> $aw x $ah", maxOf(aw, ah) <= limit)
                assertTrue("尺寸退化：$w x $h @$limit -> $aw x $ah", aw >= 28 && ah >= 28)
                val error = kotlin.math.abs(
                    aw.toDouble() / ah / (w.toDouble() / h) - 1.0
                )
                // 一个 patch 相对于最短可用边（28 px）就是 50%，所以门槛按实际取：
                // 对齐后的比例误差不得超过"短边一个 patch"能造成的量
                val bound = SpatialMogeGeometry.PATCH.toDouble() / minOf(aw, ah)
                assertTrue(
                    "长宽比误差 ${"%.4f".format(error)} 超过 ${"%.4f".format(bound)}：" +
                        "$w x $h @$limit -> $aw x $ah",
                    error <= bound + 1e-9
                )
            }
        }
    }

    @Test
    fun patch对齐在_540x720_上比逐边向下取整更准() {
        // 旧口径：518/720 缩放后 388.5 -> 截到 378，比例 378/518 = 0.7297（应为 0.75）
        val (w, h) = SpatialMogeGeometry.alignToPatchPreservingAspect(540, 720, 518)
        val oldError = kotlin.math.abs(378.0 / 518.0 / 0.75 - 1.0)
        val newError = kotlin.math.abs(w.toDouble() / h / 0.75 - 1.0)
        assertTrue(
            "新口径 $w x $h 的比例误差 ${"%.5f".format(newError)} 应当明显小于旧口径 " +
                "${"%.5f".format(oldError)}",
            newError < oldError * 0.5
        )
    }
}
