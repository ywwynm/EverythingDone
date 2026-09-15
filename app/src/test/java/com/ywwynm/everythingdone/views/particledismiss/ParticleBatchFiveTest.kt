package com.ywwynm.everythingdone.views.particledismiss

import java.io.File
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第五批：G0 对照的「基线 GPU 配方」只作用于调用线程。
 *
 * 第四批在 OPPO 上出现过「整帧只有 1 个像素不同」，判为并发下的偶发。第五批的共享状态审查
 * 找到的**唯一一处真实泄漏**就是这里：`withBaselineGpu` 改的是进程级开关，而 G0 对照跑在
 * 自己的线程上、与常驻 GL 线程上的提前预算同时进行——对照一进入基线段，生产路径读到的
 * 就是另一套配方。像素差异的成因另有其事（见 batch5-results），但这一处必须隔离。
 */
class ParticleBatchFiveTest {

    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "shared/particle-dismiss/rules.properties").isFile }

    private fun main(name: String) =
        File(root, "app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/$name").readText()

    @Test fun `基线配方只改调用线程看到的取值`() {
        val before = Triple(ParticlePerfTuning.skipBeforeFirstBirth,
            ParticlePerfTuning.jacobiLargeTile, ParticlePerfTuning.historyInIntegration)
        var insideOther: Triple<Boolean, Boolean, Boolean>? = null
        val observed = CountDownLatch(1)
        ParticlePerfTuning.withBaselineGpu {
            assertFalse("基线段里本线程必须读到关掉的取值",
                ParticlePerfTuning.skipBeforeFirstBirth || ParticlePerfTuning.jacobiLargeTile ||
                    ParticlePerfTuning.historyInIntegration)
            Thread({
                insideOther = Triple(ParticlePerfTuning.skipBeforeFirstBirth,
                    ParticlePerfTuning.jacobiLargeTile, ParticlePerfTuning.historyInIntegration)
                observed.countDown()
            }, "ParticleTuningProbe").start()
            observed.await()
        }
        assertEquals("同一时刻别的线程必须读到原来的取值", before, insideOther)
        assertEquals("退出基线段后本线程要还原", before,
            Triple(ParticlePerfTuning.skipBeforeFirstBirth, ParticlePerfTuning.jacobiLargeTile,
                ParticlePerfTuning.historyInIntegration))
    }

    @Test fun `基线配方可以嵌套，内层退出不得提前还原`() {
        ParticlePerfTuning.withBaselineGpu {
            ParticlePerfTuning.withBaselineGpu { }
            assertFalse("内层退出之后外层仍然在基线段",
                ParticlePerfTuning.skipBeforeFirstBirth)
        }
        assertTrue("全部退出后恢复默认", ParticlePerfTuning.skipBeforeFirstBirth)
    }

    @Test fun `三项 GPU 开关不再是可写的进程级字段`() {
        val tuning = main("ParticlePerfTuning.kt")
        for (name in listOf("jacobiLargeTile", "historyInIntegration", "skipBeforeFirstBirth")) {
            assertTrue("$name 必须经 baselineGpu 读出来",
                "val $name: Boolean get() = !baselineGpu && ${name}Setting" in tuning)
            assertFalse("$name 不得再是可以被别的线程改掉的 var",
                "@Volatile var $name " in tuning)
        }
        assertTrue("基线段必须是线程局部的", "ThreadLocal<Boolean>" in tuning)
    }

    // ---- 追加项：创建页预测素材持久化 ----

    private fun record(key: String, at: Long, bytes: Long) =
        ParticlePrewarmRetention.Record(key, "$key.webp", at, bytes)

    @Test fun `同一个键只留最新的那一张`() {
        val old = record("A", 100, 1000)
        val incoming = record("A", 200, 2000)
        val kept = ParticlePrewarmRetention.retain(listOf(old, record("B", 150, 1000)), incoming)
        assertEquals(listOf("A", "B"), kept.map { it.key })
        assertEquals(200L, kept.first { it.key == "A" }.savedAt)
        assertEquals(2, kept.size)
    }

    @Test fun `配置数超过上限时淘汰最旧的那一份`() {
        val existing = listOf(record("B", 400, 10), record("C", 300, 10),
            record("D", 200, 10), record("E", 100, 10))
        val kept = ParticlePrewarmRetention.retain(existing, record("A", 500, 10))
        assertEquals("保留 4 份，最旧的 E 被淘汰", listOf("A", "B", "C", "D"), kept.map { it.key })
    }

    @Test fun `总大小超过上限时同样从最旧的那一端淘汰`() {
        val mb = 1024L * 1024
        val existing = listOf(record("B", 300, 3 * mb), record("C", 200, 3 * mb),
            record("D", 100, 3 * mb))
        val kept = ParticlePrewarmRetention.retain(existing, record("A", 400, 3 * mb),
            maxEntries = 4, maxBytes = 8 * mb)
        assertEquals("8 MB 只装得下两份", listOf("A", "B"), kept.map { it.key })
    }

    @Test fun `单张就超过上限时不收它，但旧的不受牵连`() {
        val mb = 1024L * 1024
        val existing = listOf(record("B", 300, 2 * mb))
        val kept = ParticlePrewarmRetention.retain(existing, record("A", 400, 9 * mb),
            maxEntries = 4, maxBytes = 8 * mb)
        assertEquals(listOf("B"), kept.map { it.key })
    }

    @Test fun `没有新条目时只做一次收缩`() {
        val kept = ParticlePrewarmRetention.retain(
            listOf(record("A", 300, 10), record("B", 200, 10), record("C", 100, 10)),
            null, maxEntries = 2, maxBytes = ParticlePrewarmRetention.MAX_BYTES)
        assertEquals(listOf("A", "B"), kept.map { it.key })
    }

    @Test fun `磁盘上的键不含新记事背景色，其余十五项一项不少`() {
        val key = ParticleCreationKey("bg=%23ff0000|folder=root|night=32", "folder=root|night=32")
        assertFalse("磁盘键里不得出现背景色", "bg=" in key.persistent)
        val prewarm = main("ParticleCreationPrewarm.kt")
        val fingerprint = prewarm.substringAfter("fun fingerprint(").substringBefore("private fun refreshRate")
        assertTrue("背景色必须单独拼在完整键上",
            """ParticleCreationKey("bg=${'$'}colour|${'$'}rest", rest)""" in fingerprint)
        for (item in listOf("folder=", "night=", "loc=", "font=", "dpi=", "win=", "ori=",
            "multi=", "bars=", "ime=", "mode=", "scale=", "hz=", "fab=", "recipe=")) {
            assertTrue("磁盘键缺少 $item", item in fingerprint.substringAfter("val rest = buildString"))
        }
    }

    @Test fun `落盘在后台线程、读回按键匹配、内存告警不动磁盘`() {
        val prewarm = main("ParticleCreationPrewarm.kt")
        val remember = prewarm.substringAfter("fun remember(spec: ParticleDismissSpec")
            .substringBefore("fun take(")
        assertTrue("真实首屏捕获之后要落盘", "ParticlePrewarmStore.remember(" in remember)
        assertTrue("落盘用的是去掉背景色的那一份键", "key.persistent" in remember)
        assertTrue("有了真实首屏就放掉从磁盘读回来的那一份", "restored = null" in remember)
        val trim = prewarm.substringAfter("fun trimMemory()").substringBefore("// ---- 缓存键")
        assertTrue("内存告警要放掉读回来的那一份", "restored = null" in trim)
        assertFalse("但绝不能删磁盘缓存", "ParticlePrewarmStore" in trim)
        val restore = prewarm.substringAfter("private fun restoredFor(").substringBefore("// ---- 首页侧入口")
        assertTrue("键不匹配就重新去盘上找", "if (it.key == key) return it.material" in restore)
        assertTrue("找不到就返回 null，走现有路径", "?: run { restored = null; return null }" in restore)

        val store = main("ParticlePrewarmStore.kt")
        assertTrue("压缩必须无损", "WEBP_LOSSLESS" in store && "CompressFormat.PNG" in store)
        assertTrue("只写应用私有目录", "context.filesDir" in store)
        assertFalse("不得写任何外部目录", "getExternalFilesDir" in store)
        assertTrue("写盘在自己的线程上", "newSingleThreadExecutor" in store)
        assertTrue("失败只记日志", "onFailure { note(" in store)
    }
}
