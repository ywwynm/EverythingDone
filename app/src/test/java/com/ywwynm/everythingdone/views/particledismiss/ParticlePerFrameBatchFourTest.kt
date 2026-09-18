package com.ywwynm.everythingdone.views.particledismiss

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逐帧成本专项第四批（2026-09-17）的契约：C7 的根因修复，以及把微片尺寸与迭代数
 * 交给用户的那条链路。
 *
 * 根因：**Maleoon 910 上对 SSBO 里向量分量做原子操作不按数组下标寻址**，
 * `atomicAdd(accumulation[k].x, …)` 的十万次累积全部落进第 0 格，那台设备上整条压力投影
 * 因此恒为空——所以迭代数改了画面逐位不变。真机数据见
 * `docs/features/particle-animation-performance/per-frame-batch4-2026-09-17.md`。
 */
class ParticlePerFrameBatchFourTest {

    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "shared/particle-dismiss/rules.properties").isFile }

    private fun text(path: String) = File(root, path).readText()

    private fun main(name: String) =
        text("app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/$name")

    private val computeShaders: List<Pair<String, String>>
        get() = listOf("shared/particle-dismiss", "app/src/main/assets/particle-playback")
            .flatMap { folder ->
                (File(root, folder).listFiles() ?: emptyArray())
                    .filter { it.isFile && it.extension == "comp" }
                    .map { "$folder/${it.name}" to it.readText() }
            }

    // ---- C7 的根因：原子操作的目标必须是标量 ----

    @Test fun `着色器里不得对向量分量做原子操作`() {
        // 判据写成「取出每一处 atomic 调用的第一个实参，看它是不是以分量选择收尾」，
        // 而不是搜某个具体写法：换个变量名、多一层下标都必须同样被挡住。
        val call = Regex("""atomic(?:Add|Min|Max|And|Or|Xor|Exchange|CompSwap)\(([^,)]*)""")
        val offenders = ArrayList<String>()
        for ((name, source) in computeShaders) {
            for (match in call.findAll(source)) {
                val target = match.groupValues[1].trim()
                if (Regex("""\.[xyzw]$""").containsMatchIn(target)) offenders += "$name: $target"
            }
        }
        assertTrue("Maleoon 910 上这种写法不按下标寻址，累积会全部落进第 0 格：$offenders",
            offenders.isEmpty())
    }

    @Test fun `累积缓冲按标量数组声明，下标自己乘四`() {
        val splat = text("shared/particle-dismiss/peel-splat.comp")
        assertTrue("累积缓冲要声明成标量数组", "buffer D{int accumulation[];}" in splat)
        assertTrue("三个分量按 k / k+1 / k+2 寻址",
            "atomicAdd(accumulation[k]," in splat &&
                "atomicAdd(accumulation[k+1]," in splat &&
                "atomicAdd(accumulation[k+2]," in splat)
        assertTrue("格号要先乘四才是标量下标", "int k=(cell.y*grid_shape.x+cell.x)*4;" in splat)
        assertTrue("读它的模糊着色器仍按 ivec4 取，两种声明是同一批字节",
            "buffer A{ivec4 accumulation[];}" in text("shared/particle-dismiss/peel-blur.comp"))
    }

    @Test fun `间接分发的计数是标量成员，表头字节数不变`() {
        for ((name, source) in computeShaders) {
            if ("binding=7" !in source) continue
            assertFalse("$name 的表头还留着 uvec4 workgroups", "uvec4 workgroups" in source)
            assertTrue("$name 的表头缺少标量计数", "group_count" in source)
        }
        val tiles = text("app/src/main/assets/particle-playback/pressure-tiles.comp")
        assertTrue("复位要把四个标量都写上",
            "group_count=0u;group_y=1u;group_z=1u;group_pad=0u;" in tiles)
        assertTrue("Kotlin 侧的表头字节数不随声明形式改变",
            "WORK_HEADER_BYTES = 64" in main("ParticleMicroflakeRenderer.kt"))
    }

    // ---- 两项设置 ----
    //
    // 档位梯度、默认值与文案在第五批（界面产品化）整体改过，那一批的契约见
    // ParticlePerFrameBatchFiveUiTest；这里只留本批立下的、之后没有变的两条。

    // 本批把迭代数做成六档下标，第六批改成 0–100 的连续取值，契约转由
    // ParticlePerFrameBatchSixUiTest 守着；这里只留「默认仍是 100」这一条。
    @Test fun `流动精细度的默认值仍是 100`() {
        assertEquals(100, ParticleTuning.DEFAULT_FLOW)
    }

    @Test fun `两项设置都进创建页预算的指纹，且取的是稳定值`() {
        val prewarm = main("ParticleCreationPrewarm.kt")
        val recipe = prewarm.substringAfter("private fun recipe(): String").substringBefore("\n    }")
        assertTrue("微片尺寸要进指纹", "microflakeCellDp" in recipe)
        assertTrue("迭代数要进指纹", "jacobiIterations" in recipe)
        assertFalse("指纹里不能放带线程局部覆盖的瞬时读点", "microflakeCellDpNow" in recipe)
        assertFalse("指纹里不能放带线程局部覆盖的瞬时读点", "jacobiIterationsNow" in recipe)
    }

    @Test fun `设置值由进程启动时推给唯一读点，debug 的对照文件仍然优先`() {
        assertTrue("App 启动时要推一次",
            "ParticleTuning.apply(this)" in text("app/src/main/java/com/ywwynm/everythingdone/App.kt"))
        val tuning = main("ParticlePerfTuning.kt")
        val apply = tuning.substringAfter("fun applyUserTuning").substringBefore("\n    }")
        assertTrue("对照文件写过就不许被用户设置盖掉",
            "cellDpFromFile" in apply && "jacobiFromFile" in apply)
        assertTrue("两个标记由 attach 按键是否存在决定",
            "cellDpFromFile = config.has(\"microflakeCellDp\")" in tuning &&
                "jacobiFromFile = config.has(\"jacobiIterations\")" in tuning)
    }

    // ---- 设置页 ----

    @Test fun `设置页的左滑预览与首页用同一条代码路径`() {
        val page = text("app/src/main/java/com/ywwynm/everythingdone/activities/ParticleTuningActivity.kt")
        val home = text("app/src/main/java/com/ywwynm/everythingdone/activities/ThingsActivity.kt")
        // `finishSwipe` 不在这张表里：第六批起预览页的滑动永远判不成完成，松手一律沿原轨迹
        // 倒放回来（见 ParticlePerFrameBatchSixUiTest）。按下到松手之前的每一步仍然同源。
        for (call in listOf("swipeAnimator.warm(", "swipeAnimator.recordTouch(",
            "swipeAnimator.draw(", "swipeAnimator.reset(")) {
            val method = call.substringAfter('.')
            assertTrue("设置页缺少 $method", call in page)
            assertTrue("首页缺少 $method，两边就不是同一条路径了",
                "mSwipeParticleAnimator.$method" in home)
        }
        assertTrue("首页仍然靠 finishSwipe 把完成那一段播完",
            "mSwipeParticleAnimator.finishSwipe(" in home)
        // 第 8 批起示例卡两个方向都能滑，所以是 START or END；首页也是这两位。
        assertTrue("预览的手势由 ItemTouchHelper 驱动，与列表一致",
            "ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.START or ItemTouchHelper.END)"
                in page)
        assertTrue("对话框预览走页内内容那条路径", "ParticlePanelAnimator" in page)
    }

    @Test fun `入口齐全`() {
        assertTrue("设置页要有入口行",
            "rl_particle_tuning_as_bt" in text("app/src/main/res/layout/activity_settings.xml"))
        assertTrue("设置页要能打开它", "ParticleTuningActivity::class.java" in
            text("app/src/main/java/com/ywwynm/everythingdone/activities/SettingsActivity.kt"))
        assertTrue("清单要声明", ".activities.ParticleTuningActivity" in
            text("app/src/main/AndroidManifest.xml"))
    }

    @Test fun `帧统计只在设置页开着时采集`() {
        ParticleFrameStats.onPresented(1L)
        assertEquals("没 begin 过就不该留下任何样本", null, ParticleFrameStats.end())
        ParticleFrameStats.begin()
        var at = 1_000_000_000L
        repeat(10) { ParticleFrameStats.onPresented(at); at += 16_666_667L }
        val summary = ParticleFrameStats.end()!!
        assertEquals(10, summary.frames)
        assertEquals("首帧到末帧九拍，应有十帧", 10, summary.expected)
        assertEquals(1f, summary.ratio, 1e-3f)
        ParticleFrameStats.onPresented(at)
        assertEquals("收尾之后再来的帧不该被记", null, ParticleFrameStats.end())
    }

    private fun assertArrayEquals(expected: IntArray, actual: IntArray) {
        assertEquals(expected.toList(), actual.toList())
    }
}
