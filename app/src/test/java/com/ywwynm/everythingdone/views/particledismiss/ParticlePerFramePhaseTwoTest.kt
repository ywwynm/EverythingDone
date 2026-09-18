package com.ywwynm.everythingdone.views.particledismiss

import java.io.File
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逐帧成本专项第二阶段（2026-09-18）的契约。
 *
 * 能在 JVM 上守住的只有形状层面的东西：开关的作用域、屏障位与它的依赖是否对得上、
 * 两套工作组变体与 dispatch 除数是否一致、自动降档的规则。真正的收益与逐位一致性
 * 由离屏 A/B（`ParticleSegmentBench.runPhase2`）与 `ParticleTrajectoryCheck` 在真机上给。
 *
 * 判据一律**切出声明体再断言性质**，不按注释、不按 API 名搜索——注释能把禁令判死，
 * 而「某个名字出现过」在合法重构之后就失效（`source-contract-tests-vs-comments`）。
 */
class ParticlePerFramePhaseTwoTest {

    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "shared/particle-dismiss/rules.properties").isFile }

    /**
     * 读源码一律**归一化换行**：本仓库的 `core.autocrlf` 是 true，新检出的工作区是 CRLF，
     * 而 `readText()` 不做归一化——按缩进切声明体、按 `^…$` 多行匹配的判据都会在干净检出上失效。
     */
    private fun read(path: String) = File(root, path).readText().replace("\r\n", "\n")

    private fun main(name: String) =
        read("app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/$name")

    private fun shader(name: String) = read("shared/particle-dismiss/$name")

    private fun playback(name: String) = read("app/src/main/assets/particle-playback/$name")

    /**
     * 从 [text] 里 [marker] 那一处起切出一段声明体。
     *
     * 两种形态都要支持：花括号体按配平切；**表达式体**（`fun f() = …`，本文件里的屏障、
     * 宏拼接、步长都是这一种）按缩进切——一直取到下一行缩进回到声明这一层为止。
     * 只按花括号找会一路吃到下一个函数里，判据就成了「隔壁那段里有没有」。
     */
    private fun body(text: String, marker: String): String {
        val at = text.indexOf(marker)
        assertTrue("找不到 $marker", at >= 0)
        val lineStart = text.lastIndexOf('\n', at) + 1
        val indent = text.substring(lineStart).takeWhile { it == ' ' }.length
        val lineEnd = text.indexOf('\n', at).let { if (it < 0) text.length else it }
        val brace = text.indexOf('{', at)
        val equals = text.indexOf('=', at)
        val expression = equals in 0 until lineEnd && (brace < 0 || equals < brace)
        if (!expression) {
            assertTrue("$marker 之后没有花括号", brace >= 0)
            var depth = 0
            var i = brace
            while (i < text.length) {
                if (text[i] == '{') depth++
                if (text[i] == '}') { depth--; if (depth == 0) return text.substring(brace, i + 1) }
                i++
            }
            throw AssertionError("$marker 的花括号没有配平")
        }
        var i = lineEnd + 1
        while (i < text.length) {
            val end = text.indexOf('\n', i).let { if (it < 0) text.length else it }
            val line = text.substring(i, end)
            if (line.isNotBlank() && line.takeWhile { it == ' ' }.length <= indent) {
                return text.substring(at, i)
            }
            i = end + 1
        }
        return text.substring(at)
    }

    // ---- 开关：两个方向都要能切，而且只作用于调用线程 ----

    @Test fun `第二阶段开关只作用于调用线程并且两端都读回来`() {
        val bit = ParticlePerfTuning.P2_NARROW_BARRIERS
        val before = ParticlePerfTuning.phase2(bit)
        ParticlePerfTuning.withPhase2(on = bit) {
            assertTrue("强制开必须读得到开", ParticlePerfTuning.phase2(bit))
            var other: Boolean? = null
            val done = CountDownLatch(1)
            Thread({ other = ParticlePerfTuning.phase2(bit); done.countDown() }).start()
            done.await()
            assertEquals("别的线程不该被对照改到", before, other)
        }
        ParticlePerfTuning.withPhase2(off = bit) {
            assertFalse("强制关必须读得到关", ParticlePerfTuning.phase2(bit))
        }
        assertEquals("退出作用域要恢复", before, ParticlePerfTuning.phase2(bit))
    }

    @Test fun `基线段里第二阶段的每一位都必须是关的`() {
        ParticlePerfTuning.withBaselineGpu {
            for ((name, bit) in ParticlePerfTuning.PHASE2_KEYS) {
                assertFalse("基线段读到 $name 是开的", ParticlePerfTuning.phase2(bit))
            }
        }
    }

    @Test fun `每一位都有自己的 JSON 键且互不重复`() {
        val keys = ParticlePerfTuning.PHASE2_KEYS
        assertEquals("键名有重复", keys.size, keys.map { it.first }.toSet().size)
        assertEquals("位有重复", keys.size, keys.map { it.second }.toSet().size)
        for ((name, bit) in keys) {
            assertEquals("$name 不是单独一位", 1, Integer.bitCount(bit))
        }
    }

    // ---- U1：屏障位与它的依赖一致 ----

    @Test fun `压力更新之间的屏障只要求缓冲可见性`() {
        val source = main("ParticleMicroflakeRenderer.kt")
        val narrow = body(source, "private fun barrier()")
        assertTrue("窄屏障必须带 SSBO 那一位", "GL_SHADER_STORAGE_BARRIER_BIT" in narrow)
        assertFalse("窄屏障不该要求图像可见性", "GL_SHADER_IMAGE_ACCESS_BARRIER_BIT" in narrow)
        assertFalse("窄屏障不该要求纹理可见性", "GL_TEXTURE_FETCH_BARRIER_BIT" in narrow)
        assertTrue("窄屏障要由开关决定，关掉时回到三位全开",
            "P2_NARROW_BARRIERS" in narrow && "fullBarrier()" in narrow)
        val full = body(source, "private fun fullBarrier()")
        for (bit in listOf("GL_SHADER_STORAGE_BARRIER_BIT", "GL_SHADER_IMAGE_ACCESS_BARRIER_BIT",
            "GL_TEXTURE_FETCH_BARRIER_BIT")) {
            assertTrue("三位全开缺 $bit", bit in full)
        }
    }

    @Test fun `写图像再被采样的那一步必须三位全开`() {
        val resolve = body(main("ParticleMicroflakeRenderer.kt"), "private fun resolveField()")
        assertTrue("resolveField 先 imageStore 再被 step.comp 采样", "glBindImageTexture" in resolve)
        assertTrue("这一步必须用三位全开的屏障", "fullBarrier()" in resolve)
    }

    @Test fun `基线绘制路径也要发顶点属性屏障`() {
        val records = body(main("ParticleMicroflakeRenderer.kt"), "private fun writeDrawRecords(")
        // 关掉绘制记录时 material.vert 直接把 compute 写过的状态缓冲当实例化顶点属性读；
        // 两条分支都必须发这一位，否则基线路径的画面本身不可靠（所有 G0/G1 对照以它为参考）。
        val occurrences = Regex("GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT").findAll(records).count()
        assertEquals("绘制记录开与关两条分支各要发一次", 2, occurrences)
    }

    // ---- M1：两套工作组变体与 dispatch 除数一致 ----

    @Test fun `工作组变体与 dispatch 除数一致`() {
        val source = main("ParticleMicroflakeRenderer.kt")
        val defines = body(source, "private fun groupDefines()")
        assertTrue("宏必须同时给一维与二维两个值",
            "LINEAR_GROUP 64" in defines && "PLANE_GROUP 8" in defines)
        val linear = body(source, "private fun linearGroups(")
        assertTrue("一维除数必须取 linearGroup，不能写死 256", "linearGroup" in linear)
        assertFalse("一维除数里不该出现写死的 256", "256" in linear)
        // 着色器侧：用到宏的那几个核都要有 fallback，且 layout 必须引用宏而不是字面量。
        for (name in listOf("step.comp", "peel-splat.comp", "draw-record.comp")) {
            val text = shader(name)
            assertTrue("$name 缺 LINEAR_GROUP 的默认值", "#define LINEAR_GROUP 256" in text)
            assertTrue("$name 的 local_size 没有走宏", "local_size_x=LINEAR_GROUP" in text)
        }
        for (name in listOf("peel-blur.comp", "peel-project.comp")) {
            val text = shader(name)
            assertTrue("$name 缺 PLANE_GROUP 的默认值", "#define PLANE_GROUP 16" in text)
            assertTrue("$name 的 local_size 没有走宏",
                "local_size_x=PLANE_GROUP,local_size_y=PLANE_GROUP" in text)
        }
        assertTrue("history.comp 也要跟着一维变体走",
            "local_size_x=LINEAR_GROUP" in playback("history.comp"))
        // tile 收集与批处理 Jacobi 的 16×16 与活动区域的 16 格对齐绑死，**不许跟着变**。
        for (name in listOf("pressure-tiles.comp", "pressure-batched.comp")) {
            val text = playback(name)
            assertTrue("$name 的工作组必须保持 16×16",
                "local_size_x=16, local_size_y=16" in text || "local_size_x=16,local_size_y=16" in text)
        }
    }

    @Test fun `逆向历史的 dispatch 除数跟着工作组走`() {
        val transfer = body(main("ParticleReverseHistory.kt"), "fun transfer(")
        assertTrue("除数必须取 groupSize", "groupSize" in transfer)
        assertFalse("不该再有写死的 255 / 256", "255) / 256" in transfer)
    }

    // ---- U6：散度与占据度移出 shared 之后仍是同样的数、同样的顺序 ----

    @Test fun `批处理 Jacobi 的寄存器变体与 shared 变体逐格对应`() {
        val text = playback("pressure-batched.comp")
        assertTrue("要有可切换的宏", "#ifdef REGISTER_BANDS" in text)
        // 两条路径的槽位映射必须是同一个：shared 版是 k = lane, lane+256, …；
        // 寄存器版是 k = lane + c*256。写错一处就会拿错格子的散度。
        assertTrue("shared 版的步长", "for(int k=lane;k<1024;k+=256)" in text)
        assertTrue("寄存器版的槽位", "int k=lane+c*256;" in text)
        assertTrue("寄存器版每线程的格数必须是编译期常量", "#define CELLS 4" in text)
    }

    // ---- M4：改了「累积缓冲每帧整网格清零」这条不变式就要同步那条注释 ----

    @Test fun `只清活动区域时区域的不变式要写清楚`() {
        val splat = shader("peel-splat.comp")
        assertTrue("清零分支要能按区域早退", "CLEAR_ACTIVE_REGION" in splat)
        // 早退必须同时要求 ACTIVE_REGION：区域是从绑定 7 的工作缓冲里读的。
        assertTrue("早退要同时要求 ACTIVE_REGION",
            "#if defined(ACTIVE_REGION) && defined(CLEAR_ACTIVE_REGION)" in splat)
        val region = shader("peel-region.comp")
        assertTrue("peel-region 的不变式注释必须提到累积缓冲这条例外已经改了",
            "CLEAR_ACTIVE_REGION" in region)
    }

    // ---- U4 / U10a：绘制记录变宽之后，属性槽位与步长必须一致 ----

    @Test fun `绘制记录的槽位数与顶点属性一致`() {
        val source = main("ParticleMicroflakeRenderer.kt")
        val stride = body(source, "private val recordStride get()")
        // U4 本身**不许**把记录变宽：回放路径每帧要多写多读这一份，实测宽了 25% 就把出现
        // 动画拖慢 8%（2026-09-18 在华为上的第一轮对照）。只有 U10a 才多一段。
        assertTrue("只有 U10a 才用宽的那一档",
            "RECORD_STRIDE_WIDE" in stride && "useSingleInstanceBuffer" in stride)
        assertFalse("U4 不该改变记录步长", "useFlakeConstants" in stride)
        val bind = body(source, "private fun bindInstanceAttributes(")
        assertTrue("属性个数必须由步长推出来，不能写死", "recordStride / 16" in bind)
        // U10a 建立在 U4 之上：随机量在 U4 之后片元一个分量都不用了。
        val single = body(source, "private val useSingleInstanceBuffer get()")
        assertTrue("U10a 必须以 U4 为前提", "useFlakeConstants" in single)
        val record = shader("draw-record.comp")
        assertTrue("记录里要把 replica 与 alive 合成一个标志位",
            "float flags=(alive?1.:0.)+(replica>0?2.:0.);" in record)
        // 注释里也会出现这个名字，所以只数真正的调用（后面紧跟左括号的那些）。
        assertEquals("radial 与 loosen/age 都要打包成半精度", 2,
            Regex("packHalf2x16\\(").findAll(record).count())
        val vert = shader("material.vert")
        assertTrue("U10a 打开时第五段在 location 4",
            "layout(location=4) in vec4 record_extra;" in vert)
        assertTrue("U10a 关掉时复制片的采样中心仍走原来那一路",
            "layout(location=4) in vec4 material_src;" in vert)
    }

    @Test fun `跨阶段的 varying 精度两侧一致`() {
        // Maleoon 910 对跨阶段精度不一致直接拒绝链接，两个文件必须同时改。
        for (name in listOf("material.vert", "material.frag")) {
            val text = shader(name)
            val vout = Regex("^#define VOUT( mediump)?\\s*$", RegexOption.MULTILINE)
                .findAll(text).map { it.groupValues[1].trim() }.toList()
            assertEquals("$name 的 VOUT 宏要给出 mediump 与空两档", listOf("mediump", ""), vout)
            assertTrue("$name 的 uv 必须留 highp", "highp vec2 uv;" in text)
        }
        val vert = shader("material.vert")
        val frag = shader("material.frag")
        val declared = Regex("(?:flat )?(?:out|in) VOUT ([^;]+);")
        val outs = declared.findAll(vert).map { it.groupValues[1].trim() }.toList()
        val ins = declared.findAll(frag).map { it.groupValues[1].trim() }.toList()
        assertEquals("两侧的 varying 声明必须逐条对应", outs, ins)
    }

    // ---- U9：自动降档的规则 ----

    @Test fun `自动降档要连续两轮掉帧才动手`() {
        fun state(ratio: Float, rounds: Int) = ParticleAutoDowngrade.State(
            ratio, rounds, ParticleTuning.DEFAULT_CELL_INDEX, ParticleTuning.DEFAULT_FLOW,
            cellExplicit = false, appliedSteps = 0, userChanged = false)
        val first = ParticleAutoDowngrade.decide(state(.19f, 0))
        assertFalse("第一轮不该降", first.downgrade)
        assertEquals(1, first.poorRounds)
        val second = ParticleAutoDowngrade.decide(state(.19f, first.poorRounds))
        assertTrue("连续第二轮才降", second.downgrade)
        assertEquals("粒子大小往粗里走一档",
            ParticleTuning.DEFAULT_CELL_INDEX + 1, second.cellIndex)
        assertEquals("流动精细度往省里走一档",
            ParticleTuning.DEFAULT_FLOW - ParticleTuning.FLOW_EASE_STEP, second.flowDetail)
    }

    @Test fun `一轮不掉帧就把连续计数清零`() {
        val good = ParticleAutoDowngrade.decide(ParticleAutoDowngrade.State(
            1.01f, 1, 3, 60, cellExplicit = true, appliedSteps = 0, userChanged = false))
        assertEquals(0, good.poorRounds)
        assertFalse(good.downgrade)
    }

    @Test fun `没采到的那一轮既不算好也不算坏`() {
        val none = ParticleAutoDowngrade.decide(ParticleAutoDowngrade.State(
            -1f, 1, 3, 60, cellExplicit = true, appliedSteps = 0, userChanged = false))
        assertEquals("连续计数不动", 1, none.poorRounds)
        assertFalse(none.downgrade)
    }

    @Test fun `只降一档而且用户改过之后不再自动降`() {
        val again = ParticleAutoDowngrade.decide(ParticleAutoDowngrade.State(
            .2f, 1, 3, 40, cellExplicit = true, appliedSteps = 1, userChanged = false))
        assertFalse("已经降过一档就不再降", again.downgrade)
        val changed = ParticleAutoDowngrade.decide(ParticleAutoDowngrade.State(
            .2f, 1, 3, 40, cellExplicit = true, appliedSteps = 0, userChanged = true))
        assertFalse("用户自己改过之后不再自动降", changed.downgrade)
    }

    @Test fun `两项都到头就不再写设置`() {
        val bottom = ParticleAutoDowngrade.decide(ParticleAutoDowngrade.State(
            .2f, 1, ParticleTuning.CELL_DP_LEVELS.lastIndex, ParticleTuning.FLOW_MIN,
            cellExplicit = true, appliedSteps = 0, userChanged = false))
        assertFalse("最省的一档上不再降", bottom.downgrade)
        assertEquals("连续计数保留", 2, bottom.poorRounds)
    }

    @Test fun `自动降档默认关闭`() {
        assertFalse("设置页里的「性能自动调节」默认关", ParticlePerfTuning.autoDowngrade)
    }

    // ---- 采纳清单：默认配置就是发布配置 ----

    @Test fun `V 级三项与归因探针默认关`() {
        for (bit in listOf(ParticlePerfTuning.P2_SRGB8_ACCUMULATION,
            ParticlePerfTuning.P2_NEAREST_SPLAT, ParticlePerfTuning.P2_HARDWARE_TRILINEAR,
            ParticlePerfTuning.P2_PROBE_SINGLE_CELL)) {
            assertFalse("改画面的项与探针不许默认打开", ParticlePerfTuning.phase2(bit))
        }
    }

    @Test fun `被否决的项一个都不许默认打开`() {
        // 两台都量不出收益、或者只在一台上有收益而另一台变慢的，按本专项的纪律不收。
        for (bit in listOf(ParticlePerfTuning.P2_MERGED_FIELD, ParticlePerfTuning.P2_MEDIUMP_SAMPLER,
            ParticlePerfTuning.P2_PEEL_FIELD_HALF, ParticlePerfTuning.P2_MEDIUMP_VARYING,
            ParticlePerfTuning.P2_SINGLE_INSTANCE_BUFFER, ParticlePerfTuning.P2_MEDIUMP_ALU,
            ParticlePerfTuning.P2_REGION_PLAIN_JACOBI, ParticlePerfTuning.P2_PLAIN_JACOBI_ON_MALI)) {
            assertFalse("否决项不许留在默认配置里", ParticlePerfTuning.phase2(bit))
        }
    }

    @Test fun `采纳的五项默认打开`() {
        for (bit in listOf(ParticlePerfTuning.P2_NARROW_BARRIERS, ParticlePerfTuning.P2_SHARED_HALF,
            ParticlePerfTuning.P2_MALI_LOCAL_SIZE, ParticlePerfTuning.P2_CLEAR_ACTIVE_REGION,
            ParticlePerfTuning.P2_FLAKE_CONSTANTS)) {
            assertTrue("采纳项必须是发布配置", ParticlePerfTuning.phase2(bit))
        }
    }
}
