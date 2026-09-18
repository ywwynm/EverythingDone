package com.ywwynm.everythingdone.views.particledismiss

import java.io.File
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逐帧成本专项第一批（2026-09-17）的契约。
 *
 * 四项改动全部是「同一件事换一级去算」：A1 把逐顶点数学下沉成每片每帧一次、A2 把只依赖
 * 方向的量提成 uniform、A3 把流场与置信度并成一张纹理、C10 让压力更新只跑活动区域。
 * 能在 JVM 上守住的只有「形状」层面的东西——真正的收益与逐位一致性由离屏 A/B 与
 * `ParticleTrajectoryCheck` 在真机上给。
 */
class ParticlePerFrameBatchOneTest {

    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "shared/particle-dismiss/rules.properties").isFile }

    /**
     * 读源码一律**归一化换行**：本仓库的 `core.autocrlf` 是 true，新检出的工作区是 CRLF，
     * 而 `readText()` 不做归一化——按 `"…\nvoid main(){"` 这种跨行锚点切片的判据会在
     * 干净检出上直接找不到分隔符（2026-09-18 实际踩到）。
     */
    private fun read(path: String) = File(root, path).readText().replace("\r\n", "\n")

    private fun main(name: String) =
        read("app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/$name")

    private fun shader(name: String) = read("shared/particle-dismiss/$name")

    private fun playback(name: String) = read("app/src/main/assets/particle-playback/$name")

    // ---- 逐项开关：A/B 两端都要读回来核对，而且只影响调用线程 ----

    @Test fun `逐项开关按位生效且只作用于调用线程`() {
        assertTrue("默认两项全开",
            ParticlePerfTuning.drawRecords && ParticlePerfTuning.pressureActiveRegion)
        ParticlePerfTuning.withPerFrame(0) {
            assertFalse("整批关掉时本线程一项也不该读到开",
                ParticlePerfTuning.drawRecords || ParticlePerfTuning.pressureActiveRegion)
        }
        ParticlePerfTuning.withPerFrame(ParticlePerfTuning.PER_FRAME_ACTIVE_REGION) {
            assertTrue("单开 C10", ParticlePerfTuning.pressureActiveRegion)
            assertFalse("单开 C10 时 A1 必须是关的", ParticlePerfTuning.drawRecords)
        }
        ParticlePerfTuning.withPerFrame(ParticlePerfTuning.PER_FRAME_ALL) {
            assertTrue("整批打开", ParticlePerfTuning.drawRecords && ParticlePerfTuning.pressureActiveRegion)
        }
        var otherThread: Boolean? = null
        val done = CountDownLatch(1)
        ParticlePerfTuning.withPerFrame(0) {
            Thread({ otherThread = ParticlePerfTuning.drawRecords; done.countDown() }).start()
            done.await()
        }
        assertEquals("别的线程（常驻 GL 线程上的提前预算）不该被对照改到", true, otherThread)
        assertTrue("退出作用域要恢复", ParticlePerfTuning.drawRecords)
    }

    @Test fun `第一二批的基线配方连本批一起按下去`() {
        ParticlePerfTuning.withBaselineGpu {
            assertFalse("基线段里本批两项也必须是关的",
                ParticlePerfTuning.drawRecords || ParticlePerfTuning.pressureActiveRegion)
        }
    }

    @Test fun `本批两项都进了提前预算的配方`() {
        val recipe = main("ParticleCreationPrewarm.kt")
            .substringAfter("private fun recipe()").substringBefore("// ---- 诊断")
        for (switch in listOf("drawRecords", "pressureActiveRegion")) {
            assertTrue("配方里缺少 $switch", switch in recipe)
        }
    }

    // ---- A1：顶点着色器只做二维仿射展开 ----

    @Test fun `绘制记录路径的顶点着色器不再有任何昂贵数学`() {
        val source = shader("material.vert")
        // 取的是**绘制记录那一份 main**。不能按「最后一个 #ifdef DRAW_RECORDS 到第一个 #else」
        // 切：第二阶段在 main 里加了别的条件编译，第一个 #else 会落在它身上，判据就只看到半段。
        val records = source.substringAfter("#ifdef DRAW_RECORDS\nvoid main(){")
            .substringBefore("#else\nvoid main(){")
        assertTrue("记录路径要读预乘好的矩阵", "mat2 expand=mat2(record_xform" in records)
        assertTrue("展开只是原点加矩阵乘角点", "record_place.xy+expand*(c+j)" in records)
        for (call in listOf("sin(", "cos(", "exp(", "log(", "pow(", "atan(", "smoothstep(", "sqrt(")) {
            assertFalse("记录路径的顶点着色器里不该再出现 $call", call in records)
        }
        // 位置只依赖前两个属性——Adreno 的分箱遍与 Mali 的 IDVS 都只会读这 32 字节。
        val position = records.substringAfter("mat2 expand").substringBefore("gl_Position=")
        assertFalse("位置链不得碰外观属性", "record_shade" in position || "record_mask" in position)
    }

    @Test fun `绘制记录由 compute 每片每帧写一次，两遍绘制共用同一份`() {
        val record = shader("draw-record.comp")
        assertTrue("写的是四个 vec4", "records[o+3u]" in record && "records[o]=xform" in record)
        // 工作组大小第二阶段起走宏（M1 的 Mali 变体），默认值仍是 256。
        assertTrue("按片一次", "layout(local_size_x=LINEAR_GROUP) in;" in record)
        assertTrue("宏的默认值仍是 256", "#define LINEAR_GROUP 256" in record)
        val renderer = main("ParticleMicroflakeRenderer.kt")
        val write = renderer.substringAfter("private fun writeDrawRecords(").substringBefore("private fun render(")
        assertTrue("读顶点属性前要下顶点属性屏障",
            "GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT" in write)
        val render = renderer.substringAfter("private fun render(t: Float").substringBefore("// ---- 逆向")
        assertTrue("记录必须在两遍绘制之前写好一次", "writeDrawRecords(" in render)
        assertEquals("两遍绘制只写一次记录", 1, Regex("writeDrawRecords\\(").findAll(render).count())
    }

    @Test fun `按 trailing 丢整片的判据搬到逐片一级之后，片段着色器里不得再留一份`() {
        val frag = shader("material.frag")
        val guarded = frag.substringAfterLast("#ifndef DRAW_RECORDS").substringBefore("#endif")
        assertTrue("原判据仍留给不走记录的那条路径",
            "fract(random_out.y*13.731+random_out.z*17.371)" in guarded)
        val record = shader("draw-record.comp")
        assertTrue("记录路径里按同一条判据决定整片画不画",
            "fract(m.random.y*13.731+m.random.z*17.371)>=.64*trailing" in record)
    }

    // ---- C10：活动区域 ----

    @Test fun `模糊、tile 收集与投影都能只跑活动区域，且不引入间接分发`() {
        for (name in listOf("peel-blur.comp", "peel-project.comp")) {
            val source = shader(name)
            assertTrue("$name 缺少活动区域分支", "#ifdef ACTIVE_REGION" in source)
            assertTrue("$name 的早退判定要同时管上下界",
                "any(lessThan(q,region.xy))||any(greaterThan(q,region.zw))" in source)
        }
        assertTrue("tile 收集缺少活动区域分支", "#ifdef ACTIVE_REGION" in playback("pressure-tiles.comp"))
        val splat = shader("peel-splat.comp")
        assertTrue("包围盒由原子 min/max 给出",
            "atomicMin(box_x0" in splat && "atomicMax(box_x1" in splat)
        assertTrue("清零仍是整网格：它只写 16 字节/格，实测落在噪声里",
            Regex("""if\(pass==0\)\{if\(i<uint\(grid_shape\.x\*grid_shape\.y\)\)""").containsMatchIn(splat))
        // 间接分发要配 GL_COMMAND_BARRIER_BIT，Adreno 上那道屏障比省下的格子还贵。
        val renderer = main("ParticleMicroflakeRenderer.kt")
        val peel = renderer.substringAfter("private inner class PeelPressure").substringBefore("private fun program(")
        assertFalse("压力更新里不得出现间接分发", "glDispatchComputeIndirect(WORK" in peel)
        assertEquals("只剩 Jacobi 那一处按活动 tile 间接分发", 1,
            Regex("glDispatchComputeIndirect").findAll(peel).count())
    }

    @Test fun `活动区域按上一帧与本帧的并集外扩，并对齐到工作组`() {
        val region = shader("peel-region.comp")
        assertTrue("区域要把上一帧包围盒并进来", "min(box.xy,previous.xy)" in region)
        assertTrue("再外扩一个 tile（11 抽头模糊的半径 5 格不到一个 tile）",
            "lo-1" in region && "hi+1" in region)
        assertTrue("包围盒按 tile 记，区域天然对齐到工作组", "region=ivec4(lo*16," in region)
        val splat = shader("peel-splat.comp")
        assertTrue("包围盒按 tile 坐标记，压住原子竞争", "grid_shape-1)/16" in splat)
        assertTrue("已经覆盖就不发原子", "if(clo.x<box_x0)atomicMin(box_x0,clo.x);" in splat)
        assertTrue("每轮都要把包围盒复位，否则下一轮的原子从旧值开始",
            region.substringAfterLast("previous=box;").contains("box_x0=EMPTY_LO"))
        val tiles = playback("pressure-tiles.comp")
        assertTrue("tile 号仍是工作组自己的坐标", "tiles[slot]=ivec2(gl_WorkGroupID.xy);" in tiles)
    }

    @Test fun `工作缓冲的表头四个着色器写法一致`() {
        val sources = listOf(playback("pressure-tiles.comp"), playback("pressure-tiles-large.comp"),
            playback("pressure-batched.comp"), playback("pressure-batched-large.comp"))
        for (source in sources) {
            val block = source.substringAfter("binding=7").substringBefore("};")
            // 第四批把 `uvec4 workgroups` 换成四个标量 uint（Maleoon 上向量分量的原子
            // 不按下标寻址），字节布局不变，表头仍是 64 字节。
            for (field in listOf("group_count", "region", "previous", "box_x0", "tiles[]")) {
                assertTrue("表头缺少 $field", field in block)
            }
        }
        assertTrue("Kotlin 侧的表头字节数要与 std430 对齐结果一致",
            "WORK_HEADER_BYTES = 64" in main("ParticleMicroflakeRenderer.kt"))
    }

    @Test fun `压力三个程序与 tile 收集都带上同一套宏`() {
        val renderer = main("ParticleMicroflakeRenderer.kt")
        val peel = renderer.substringAfter("private inner class PeelPressure").substringBefore("private fun program(")
        for (line in listOf("""program("peel-splat.comp", defines = regionDefines())""",
            """program("peel-blur.comp", defines = regionDefines())""",
            """program("peel-project.comp", defines = regionDefines())""",
            """program("particle-playback/pressure-tiles.comp", defines = regionDefines())""")) {
            assertTrue("缓存键必须带宏，否则拿到的是另一份没有区域分支的程序：$line", line in peel)
        }
    }

    // ---- 创建页预算的 hz 键缺陷 ----

    @Test fun `采样计划按粒子层自己投的帧率算，键里不再有瞬时刷新率`() {
        assertEquals(60f, ParticleWindowSurface.VOTED_FRAME_RATE, 0f)
        assertEquals("投票的帧率下采样数是 36", 36,
            ParticleReversePlan.samplesFor(ParticleReversePlan.APPEARANCE_SECONDS,
                ParticleWindowSurface.VOTED_FRAME_RATE))
        val prewarm = main("ParticleCreationPrewarm.kt")
        val fingerprint = prewarm.substringAfter("fun fingerprint(").substringBefore("private fun recipe()")
        assertFalse("键里不得再出现刷新率这一项", "|hz=" in fingerprint)
        assertFalse("也不得再读面板的瞬时刷新率", "display?.refreshRate" in prewarm)
        val overlay = main("ParticleDismissOverlay.kt")
        assertTrue("会话拿到的必须是投票的那个常量",
            "refreshRate = ParticleWindowSurface.VOTED_FRAME_RATE" in overlay)
        assertTrue("预算那一侧也按同一个常量算采样数",
            "samplesFor(duration, ParticleWindowSurface.VOTED_FRAME_RATE)" in prewarm)
    }
}
