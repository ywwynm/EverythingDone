package com.ywwynm.everythingdone.views.particledismiss

import java.io.File
import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第三批：左滑快拖不阻塞首帧（3.1 修复）、创建页出现的起点帧与缩短后的播放映射（2c）。
 *
 * 2b 只改材料输入（方向、触点距离与强度），由 `ParticleFlowGeometry` 既有的测试覆盖几何，
 * 这里只守住「真实触点落在控件内时距离为 0、强度为 0」这条会直接影响观感的边界。
 */
class ParticleBatchThreeTest {

    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "shared/particle-dismiss/rules.properties").isFile }

    private val rules get() = ParticleMicroflakeRules.read(
        File(root, "shared/particle-dismiss/rules.properties").inputStream(),
        File(root, "shared/particle-dismiss/common-release.f32").inputStream())

    // ---- 3.1：识别左滑时历史不足，先画备好的最高一档，绝不阻塞 ----

    @Test fun `追赶相位不会超过已经备好的档位`() {
        for (prepared in 0..12) {
            for (tenths in 0..200) {
                val wanted = tenths / 10f
                val position = ParticleSession.presentedPosition(wanted, prepared, creep = true)
                assertTrue("不得超过已备档位", position <= prepared + 1e-6f)
                assertTrue("不得超过手指位置", position <= wanted + 1e-6f)
                // drawGestureFrame 会用到 ceil(position) 那一档；必须已经备好。
                assertTrue("画这一相位需要的档位必须已经备好",
                    ceil(position).toInt() <= maxOf(prepared, 0))
            }
        }
    }

    @Test fun `备够之后完全跟手，关掉开关则退回阻塞补档的老行为`() {
        assertEquals(4.5f, ParticleSession.presentedPosition(4.5f, 8, creep = true), 0f)
        assertEquals(8f, ParticleSession.presentedPosition(8f, 8, creep = true), 0f)
        // 手上一档都没有时画第 0 档（完整卡片），而不是等。
        assertEquals(0f, ParticleSession.presentedPosition(17.3f, 0, creep = true), 0f)
        assertEquals(17.3f, ParticleSession.presentedPosition(17.3f, 0, creep = false), 0f)
    }

    @Test fun `追赶是单调的，手指停住时画面仍能逐拍逼近手指`() {
        val wanted = 21.6f
        var previous = -1f
        for (prepared in 0..22) {
            val position = ParticleSession.presentedPosition(wanted, prepared, creep = true)
            assertTrue("追赶不得回退", position >= previous)
            previous = position
        }
        assertEquals(wanted, previous, 0f)
    }

    @Test fun `回拖与跨原点仍按手指走，不受已备档位影响`() {
        // 回拖时手指要的相位一定不超过之前到过的最大相位，历史只增不减，所以不再截断。
        assertEquals(3f, ParticleSession.presentedPosition(3f, 36, creep = true), 0f)
        assertEquals(0f, ParticleSession.presentedPosition(0f, 36, creep = true), 0f)
    }

    @Test fun `出帧路径不得再同步补档`() {
        val source = File(root,
            "app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/ParticleSession.kt").readText()
        val body = source.substringAfter("private fun drawPlayFrame")
            .substringBefore("private fun drawHoldFrame")
        val blocking = body.substringAfter("if (!ParticlePerfTuning.gestureCreepToFinger) {")
            .substringBefore("}")
        assertTrue("阻塞补档只允许留在关掉开关的分支里", "stepReverse" in blocking)
        assertEquals("出帧路径只剩那一处补档", 1, Regex("stepReverse").findAll(body).count())
    }

    // ---- 2c：起点帧选择 ----

    @Test fun `存活片数与逐帧暴力统计一致且用开区间`() {
        val values = FloatArray(40 * 12)
        var seed = 7L
        fun next(): Float {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            return ((seed ushr 40) / 16777216.0).toFloat()
        }
        for (i in 0 until 40) {
            values[i * 12 + 2] = next() * .8f
            values[i * 12 + 6] = .11f + next() * .4f
        }
        // 再放两片精确落在采样边界上，验证严格不等号。
        values[2] = 6 / 36f; values[6] = 12 / 36f
        for (samples in listOf(12, 36, 60)) {
            val counts = ParticleReversePlan.aliveCounts(values, samples)
            assertEquals(samples + 1, counts.size)
            for (frame in 0..samples) {
                val t = frame / samples.toFloat()
                var expected = 0
                for (i in 0 until 40) {
                    val birth = values[i * 12 + 2]
                    val death = birth + values[i * 12 + 6]
                    if (t > birth && t < death) expected++
                }
                assertEquals("samples=$samples frame=$frame", expected, counts[frame])
            }
        }
    }

    @Test fun `起点取最晚一个达标的采样，不达标时完整倒放`() {
        val counts = intArrayOf(0, 10, 50, 80, 60, 20, 4, 1, 0)
        assertEquals(5, ParticleReversePlan.startFrameOf(counts, 100, .10f))
        assertEquals(6, ParticleReversePlan.startFrameOf(counts, 100, .04f))
        assertEquals(7, ParticleReversePlan.startFrameOf(counts, 100, .01f))
        // 门槛为 0 或从来没达标过：不跳过任何一帧。
        assertEquals(8, ParticleReversePlan.startFrameOf(counts, 100, 0f))
        assertEquals(8, ParticleReversePlan.startFrameOf(counts, 100, .9f))
        assertEquals(8, ParticleReversePlan.startFrameOf(counts, 0, .05f))
        // 永远不会退化成 0：起点至少要留一帧可播。
        assertTrue(ParticleReversePlan.startFrameOf(intArrayOf(0, 0, 0), 100, .5f) >= 1)
    }

    @Test fun `真实材料上只跳掉几乎没有粒子的尾段`() {
        val currentRules = rules
        val pixelWidth = 210
        val pixelHeight = 315
        val pixels = IntArray(pixelWidth * pixelHeight) { i ->
            if ((i / pixelWidth) in 40..48) 0xff202020.toInt() else 0xff3f7ad0.toInt()
        }
        for (seed in listOf(11L, 909602L)) {
            val materials = ParticleMicroflakeModel.build(896f, 1344f, pixels, pixelWidth, pixelHeight,
                294f, seed, currentRules)
            val samples = ParticleReversePlan.samplesFor(ParticleReversePlan.APPEARANCE_SECONDS, 60f)
            val counts = ParticleReversePlan.aliveCounts(materials.values, samples)
            val start = ParticleReversePlan.startFrameOf(counts, materials.count)
            assertTrue("必须真的跳掉了尾段", start < samples)
            assertTrue("不能跳掉一半以上的轨迹", start > samples * 3 / 4)
            // 被跳掉的每一帧都在门槛之下；起点自己在门槛之上。
            for (frame in start + 1..samples) {
                assertTrue("第 $frame 帧不该被跳掉",
                    counts[frame] < materials.count * ParticleReversePlan.APPEARANCE_ALIVE_RATIO)
            }
            assertTrue(counts[start] >= materials.count * ParticleReversePlan.APPEARANCE_ALIVE_RATIO)
        }
    }

    // ---- 2c：缩短后的播放映射 ----

    @Test fun `缩短播放之后逐帧仍是原序列去掉尾段，没有任何一帧被重采样`() {
        val plan = ParticleReversePlan(FloatArray(0), 36)
        for (start in listOf(36, 34, 33, 30, 18)) {
            val playbackSeconds = ParticleReversePlan.APPEARANCE_SECONDS.toDouble() * start / plan.samples
            val expected = (0..start).map { start - it }
            val actual = (0..start).map { beat ->
                val progress = (beat / 60.0 / playbackSeconds).toFloat().coerceIn(0f, 1f)
                plan.frame(progress, start)
            }
            assertEquals("start=$start", expected, actual)
        }
    }

    @Test fun `起点更早只是不播尾段，采样的时间值一点没变`() {
        val values = FloatArray(12).apply { this[2] = .2f; this[6] = .5f }
        val plan = ParticleReversePlan(values, 36)
        // time() 仍按总采样数归一，所以保留下来的每一帧与不跳过时是同一个时刻、同一份历史。
        for (frame in 0..36) assertEquals(frame / 36f, plan.time(frame), 0f)
        assertEquals(33, plan.frame(0f, 33))
        assertEquals(0, plan.frame(1f, 33))
        assertEquals(36, plan.frame(0f))
    }

    @Test fun `跳过尾段的开关按路径分档`() {
        // 第三批只有创建页启用；第四批起弹窗、页内面板与新卡片也启用，但各走各的开关，
        // 好单独回退某一条路径。逐项断言见 ParticleBatchFourTest。
        val controller = File(root,
            "app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/ParticleDismissController.kt").readText()
        assertTrue("弹窗那一档默认值必须是 dialogAppearanceSkipEmptyTail",
            "skipEmptyTail: Boolean = ParticlePerfTuning.dialogAppearanceSkipEmptyTail" in controller)
        val transition = File(root,
            "app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/ThingCreationTransition.kt").readText()
        assertTrue("创建页必须显式用 creationAppearanceSkipEmptyTail",
            "skipEmptyTail = ParticlePerfTuning.creationAppearanceSkipEmptyTail" in transition)
        val session = File(root,
            "app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/ParticleSession.kt").readText()
        assertTrue("会话只看 spec 的结论", "val skip = spec.skipEmptyTail" in session)
    }

    // ---- 2b：真实触点 ----

    @Test fun `控件内的真实触点给出零距离与最近的强度`() {
        val w = 1680f
        val h = 2520f
        // 新建按钮大致在右下角内侧。
        val x = w - 120f
        val y = h - 240f
        assertEquals(0f, ParticleFlowGeometry.touchGap(x, y, w, h), 0f)
        assertEquals(0f, ParticleFlowGeometry.touchStrength(x, y, w, h, 0f, 0f, w, h), 0f)
        // 而原来的虚拟远触点（1.1 倍对角线）在控件之外，距离明显大于 0。
        val reach = 1.1f * kotlin.math.hypot(w, h)
        val angle = Math.toRadians(-315.0)
        val far = ParticleFlowGeometry.touchGap(
            (w / 2 + kotlin.math.cos(angle) * reach).toFloat(),
            (h / 2 + kotlin.math.sin(angle) * reach).toFloat(), w, h)
        assertTrue("虚拟远触点的距离应当远大于真实触点", far > 1f)
    }

    // ---- 3b：方向 / 距离 / 强度三项可以分开采用 ----

    @Test fun `触点档位逐项叠加，方向在前、强度在最后`() {
        val controller = File(root,
            "app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/ParticleDismissController.kt").readText()
        val body = controller.substringAfter("val mode = ParticlePerfTuning.creationAppearanceTouch")
            .substringBefore("val overlay = ParticleDismissOverlay(")
        assertTrue("方向从 TOUCH_DIRECTION 起用真实触点",
            "mode >= ParticlePerfTuning.TOUCH_DIRECTION" in body)
        assertTrue("触点距离要到 TOUCH_DIRECTION_GAP 才用真实值",
            "mode >= ParticlePerfTuning.TOUCH_DIRECTION_GAP" in body)
        assertTrue("触点强度要到 TOUCH_FULL 才用真实值",
            "mode >= ParticlePerfTuning.TOUCH_FULL" in body)
        // 档位必须是单调的：低档不能比高档多用一项输入。
        assertTrue(ParticlePerfTuning.TOUCH_OFF < ParticlePerfTuning.TOUCH_DIRECTION)
        assertTrue(ParticlePerfTuning.TOUCH_DIRECTION < ParticlePerfTuning.TOUCH_DIRECTION_GAP)
        assertTrue(ParticlePerfTuning.TOUCH_DIRECTION_GAP < ParticlePerfTuning.TOUCH_FULL)
    }

    @Test fun `门槛提高只会跳掉更多帧，高到没有一帧达标时整段不跳`() {
        val counts = intArrayOf(0, 10, 50, 90, 120, 90, 45, 18, 4, 0)
        val count = 200
        val samples = counts.size - 1
        var previous = samples
        for (ratio in listOf(.0f, .02f, .05f, .10f, .15f, .30f, .59f)) {
            val start = ParticleReversePlan.startFrameOf(counts, count, ratio)
            assertTrue("ratio=$ratio 不得比更低的门槛留下更多帧", start <= previous)
            assertTrue(start >= 1)
            previous = start
        }
        // 门槛高过峰值时没有任何一帧达标：这时**完整倒放**（而不是把整段都跳掉）。
        // 0.6 恰好卡在峰值上，float 的 200×0.6f 比 120 大一点点，正好落进这一分支。
        assertEquals(samples, ParticleReversePlan.startFrameOf(counts, count, .60f))
        assertEquals(samples, ParticleReversePlan.startFrameOf(counts, count, .90f))
    }

    @Test fun `真实触点的方向就是内容中心指向按钮的方向`() {
        val w = 1680f
        val h = 2520f
        val x = w - 120f
        val y = h - 240f
        // fromSpec 用的就是这个式子：dy 向下为正，direction 以 y 向上为正。
        val direction = Math.toDegrees(kotlin.math.atan2(-(y - h / 2), x - w / 2).toDouble())
        assertTrue("按钮在右下，方向应落在下方扇区（225°–315°）",
            direction < 0 && direction > -90)
        assertFalse("不应再等于固定的 315°", kotlin.math.abs(direction + 45.0) < 1.0)
    }
}
