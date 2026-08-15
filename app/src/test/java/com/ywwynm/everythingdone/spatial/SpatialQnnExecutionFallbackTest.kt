package com.ywwynm.everythingdone.spatial

import ai.onnxruntime.OrtException
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QNN **执行期**失败回落 CPU 的契约（D276）。
 *
 * 起因：OPD2515 上 Big-LaMa 的 v81 预编译 context 建 session 正常，第一次 `QnnGraph_execute`
 * 超过 10 秒被 CDSP 看门狗打死，异常一路穿到界面，那台平板一张空间照片都生成不出来。
 * `SpatialQnnSessionFactory` 的"失败就回落"此前只覆盖建 session 那一段。
 */
class SpatialQnnExecutionFallbackTest {

    private fun key(
        modelId: String = "big_lama_places2_512",
        modelVersion: String = "1.0.0",
        modelSha256: String = "a".repeat(64),
        shapeTag: String = "512x512",
        dspArch: String = "v81",
        runtimePackageVersion: String = "1.28.0-qnn-r2"
    ) = SpatialQnnContextStore.Key(
        modelId = modelId,
        modelVersion = modelVersion,
        modelSha256 = modelSha256,
        shapeTag = shapeTag,
        dspArch = dspArch,
        runtimePackageVersion = runtimePackageVersion
    )

    // ---- 什么样的失败才值得改走 CPU ----

    @Test
    fun `ORT 的失败才回落`() {
        assertTrue(SpatialQnnSessionFactory.shouldFallBackToCpu(OrtException("QNN graph execute error")))
    }

    @Test
    fun `包在自己上下文里的 ORT 失败也回落`() {
        val wrapped = IllegalStateException("补全失败", OrtException("QNN graph execute error"))
        assertTrue(SpatialQnnSessionFactory.shouldFallBackToCpu(wrapped))
    }

    @Test
    fun `取消不回落`() {
        // 取消是 check(!cancelled) 抛的 IllegalStateException。重跑一遍还是取消，
        // 回落只会让用户多等一倍时间，还会把一次正常取消误记成"这台机 NPU 不能用"。
        assertFalse(SpatialQnnSessionFactory.shouldFallBackToCpu(IllegalStateException("任务已取消")))
    }

    @Test
    fun `契约校验失败不回落`() {
        assertFalse(SpatialQnnSessionFactory.shouldFallBackToCpu(IllegalStateException("补全模型输出形状不符")))
        assertFalse(SpatialQnnSessionFactory.shouldFallBackToCpu(OutOfMemoryError("bitmap")))
    }

    // ---- 拉黑的指纹作废条件 ----

    @Test
    fun `指纹的每一维变了都当没有结论`() {
        val base = SpatialQnnExecutionBlocklist.fingerprint(key())
        // 换了运行组件、换了产物档位、换了模型字节，结论都不该继续沿用——它是跟着
        // 那一份模型与那一份组件得出的（与 D273 探测结论同一口径）。
        assertNotEquals(base, SpatialQnnExecutionBlocklist.fingerprint(key(dspArch = "v79")))
        assertNotEquals(
            base,
            SpatialQnnExecutionBlocklist.fingerprint(key(runtimePackageVersion = "1.28.0-qnn-r3"))
        )
        assertNotEquals(base, SpatialQnnExecutionBlocklist.fingerprint(key(modelVersion = "1.0.1")))
        assertNotEquals(
            base,
            SpatialQnnExecutionBlocklist.fingerprint(key(modelSha256 = "b".repeat(64)))
        )
        assertNotEquals(base, SpatialQnnExecutionBlocklist.fingerprint(key(shapeTag = "256x256")))
        assertNotEquals(base, SpatialQnnExecutionBlocklist.fingerprint(key(modelId = "migan")))
    }

    @Test
    fun `同一份组合的指纹稳定`() {
        assertEquals(
            SpatialQnnExecutionBlocklist.fingerprint(key()),
            SpatialQnnExecutionBlocklist.fingerprint(key())
        )
    }

    // ---- 连续多少次才下结论（与 D273 自探同一口径）----

    @Test
    fun `一次失败不下结论`() {
        // CDSP 是全机共享的，别的进程占着资源导致的一次失败，不足以证明这台机跑不了这个
        // 模型。D273 给自探定的就是这条规矩，两处必须一致（2026-08-15 用户裁定）。
        assertTrue("阈值必须大于 1，否则偶发失败会变成永久判决", SpatialQnnExecutionBlocklist.FAILURE_THRESHOLD > 1)
        assertFalse(SpatialQnnExecutionBlocklist.settled(1))
        assertTrue(SpatialQnnExecutionBlocklist.settled(SpatialQnnExecutionBlocklist.FAILURE_THRESHOLD))
    }

    @Test
    fun `连续失败才累加`() {
        val print = SpatialQnnExecutionBlocklist.fingerprint(key())
        assertEquals(1, SpatialQnnExecutionBlocklist.nextFailureCount(null, 0, print))
        assertEquals(2, SpatialQnnExecutionBlocklist.nextFailureCount(print, 1, print))
    }

    @Test
    fun `换了模型或组件就从头数`() {
        // 旧结论是对另一份东西得出的，累加过去等于拿旧账算新账：一次对旧组件的失败加上
        // 一次对新组件的失败，会把新组件直接判死。
        val old = SpatialQnnExecutionBlocklist.fingerprint(key(runtimePackageVersion = "1.28.0-qnn-r2"))
        val fresh = SpatialQnnExecutionBlocklist.fingerprint(key(runtimePackageVersion = "1.28.0-qnn-r3"))
        assertEquals(1, SpatialQnnExecutionBlocklist.nextFailureCount(old, 1, fresh))
    }

    // ---- 设置页读取侧的新鲜度 ----

    @Test
    fun `界面判据按架构与组件版本区分`() {
        // 读取侧必须自己校验新鲜度：写入侧的作废条件够不着已经收口的那条路，
        // 旧结论会永远清不掉、行永远置灰（D275 补在探测那边踩过一模一样的坑）。
        val base = SpatialQnnExecutionBlocklist.environment("v81", "1.28.0-qnn-r2")
        assertEquals(base, SpatialQnnExecutionBlocklist.environment("v81", "1.28.0-qnn-r2"))
        assertNotEquals(base, SpatialQnnExecutionBlocklist.environment("v79", "1.28.0-qnn-r2"))
        assertNotEquals(base, SpatialQnnExecutionBlocklist.environment("v81", "1.28.0-qnn-r3"))
        assertNotEquals(base, SpatialQnnExecutionBlocklist.environment(SpatialQnnSupport.ALL_ARCH, "1.28.0-qnn-r2"))
    }

    // ---- 阈值没到时，回落这一遍必须真的走 CPU ----

    /**
     * 第一次失败时黑名单还没生效，重跑若照着原样再走一次 `createSession`，会再次落到 QNN、
     * 再失败一次——用户白等两轮还是拿不到结果。因此 `withExecuteFallback` 必须在重跑前把
     * 本次作用域钉死在 CPU 上，`createSession` 也必须真的看这个标志。
     *
     * 判据按源码，先剥注释：两处的说明文字里都会提到这个名字。
     */
    @Test
    fun `回落重跑前把本次作用域钉在 CPU 上`() {
        val source = stripComments(
            mainKotlinSources()["com/ywwynm/everythingdone/spatial/SpatialQnnSessionFactory.kt"]
                ?: error("找不到 SpatialQnnSessionFactory.kt")
        )
        assertTrue(
            "withExecuteFallback 没有置 forceCpu：阈值没到时重跑会再走一次 QNN",
            source.contains("scope.forceCpu = true")
        )
        assertTrue(
            "createSession 没有读 forceCpu：置了也没用",
            source.contains("forceCpu == true")
        )
    }

    // ---- 三个引擎都必须接上 ----

    /**
     * 接漏一个引擎的后果就是那一步再次把整条链路打死。这类"下发/工厂都做完了、就差引擎
     * 没调"的漏接在本功能上真实发生过一次（D254：补全引擎从来没调过工厂，产物下下来躺
     * 在设备上）。
     *
     * 调用者名单从主源码**扫出来**而不是点名：点名清单防不住新增的调用文件——写新引擎
     * 的人不会收到"来这里登记"的任何提示（2026-08-16 复审）。
     *
     * 判据按源码，且**先剥掉注释**：文档里必然会提到这两个 API 名，字面搜索会被注释判活。
     */
    @Test
    fun `每个调用工厂的文件都包了执行期回落`() {
        val callers = mainKotlinSources()
            .mapValues { (_, source) -> stripComments(source) }
            .filterValues { it.contains("SpatialQnnSessionFactory.createSession(") }
        for ((path, source) in callers) {
            assertTrue(
                "$path 调了 QNN 工厂却没包 withExecuteFallback：执行期失败会打死整条生成链路",
                source.contains("SpatialQnnSessionFactory.withExecuteFallback(")
            )
        }
        // 扫描本身也要有效：已知的三个引擎必须被扫成调用者，否则是扫描路径或判据失效。
        for (known in listOf(
            "SpatialInpaintingEngine.kt",
            "SpatialSegmentationEngine.kt",
            "SpatialBoundaryRefinementEngine.kt"
        )) {
            assertTrue(
                "没扫到已知调用者 $known，检查扫描路径与判据；实际扫到：${callers.keys}",
                callers.keys.any { it.endsWith(known) }
            )
        }
    }

    // ---- 模型字节的每个安装点与删除点都要清结论 ----

    /**
     * 运行层靠六维指纹自动作废旧结论；设置页读取侧只有（架构，组件版本）两维，模型换新
     * 后不显式清除的话，那一行会保持置灰直到下一次生成在 QNN 上跑通（自愈但有窗口）。
     * 删除侧同理：产物没了还留着结论，Big-LaMa 行会显示「可删除」却收起下载键，删完
     * 既下不回来、也删无可删。
     *
     * 名单点名当前会产生 QNN 执行结论的四个仓库，各要求至少两处 `clear(`（安装与删除）。
     * 新增会用 QNN 的模型类别时把它的仓库加进来（上面的扫描测试会先逼新引擎包
     * withExecuteFallback，顺着看会找到这条）。
     */
    @Test
    fun `模型安装与删除点都清执行期结论`() {
        val sources = mainKotlinSources()
        for (name in listOf(
            "SpatialSegmentationModelStore.kt",
            "SpatialBoundaryRefinementModelStore.kt",
            "SpatialInpaintingModelStore.kt",
            "SpatialQnnPrecompiledStore.kt"
        )) {
            val source = stripComments(
                sources["com/ywwynm/everythingdone/spatial/$name"] ?: error("找不到 $name")
            )
            val calls = Regex("""SpatialQnnExecutionBlocklist\.clear\(""").findAll(source).count()
            assertTrue(
                "$name 的 SpatialQnnExecutionBlocklist.clear 调用只有 $calls 处：" +
                    "安装与删除两处都要清执行期结论",
                calls >= 2
            )
        }
    }

    /** 主源码全部 Kotlin 文件（键为相对路径）。测试工作目录可能是仓库根或 app 模块目录，两处都试。 */
    private fun mainKotlinSources(): Map<String, String> {
        val relative = "app/src/main/java"
        val root = listOf(File(relative), File("../$relative")).firstOrNull(File::isDirectory)
            ?: error("找不到主源码目录：$relative（工作目录 ${File(".").absolutePath}）")
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associate {
                it.relativeTo(root).invariantSeparatorsPath to it.readText(Charsets.UTF_8)
            }
    }

    private fun stripComments(source: String): String = source
        .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        .replace(Regex("""(?m)//.*$"""), " ")
}
