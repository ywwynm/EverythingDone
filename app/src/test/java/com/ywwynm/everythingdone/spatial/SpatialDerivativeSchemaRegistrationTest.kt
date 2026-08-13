package com.ywwynm.everythingdone.spatial

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 新增一档 renderer/schema 时，`SpatialDerivativeStore` 里有**五处**必须同步：写入侧的
 * `saveSchemaFor`、校验侧的 `validLdiManifest`、登记侧的 `isLdiSchema` 与
 * `isCurrentGeneration`，以及 `manifestMatchesSource` 第一行读的
 * `SUPPORTED_SCHEMA_VERSIONS`。
 *
 * 2026-08-13 真机上 VNEXT14 就漏了后两处：manifest 写得完全正确，`loadManifest` 却因为
 * `isLdiSchema(16)` 为 false 直接判死，而 `load` 整个包在 `runCatching{}.getOrNull()` 里，
 * 异常与判死一起被吞成 null——端上只看到"无法回读刚保存的空间派生"，没有任何指向。
 *
 * 这里按源码断言登记完整。判据不搜 API 名（注释里出现同名字符串会误判），而是**逐个
 * 点名**：取当前 renderer 对应的那个 schema 常量，要求它出现在 `isLdiSchema` 的函数体内。
 */
class SpatialDerivativeSchemaRegistrationTest {

    private val storeSource by lazy {
        val file = File("app/src/main/java/com/ywwynm/everythingdone/spatial/SpatialDerivativeStore.kt")
        val fallback = File("../app/src/main/java/com/ywwynm/everythingdone/spatial/SpatialDerivativeStore.kt")
        (if (file.isFile) file else fallback).readText(Charsets.UTF_8)
    }

    /** 取 `saveSchemaFor` 里 renderer → schema 常量的映射。 */
    private fun schemaConstantFor(renderer: SpatialLdiRenderer): String {
        val pattern = Regex(
            """SpatialLdiRenderer\.${renderer.name}\s*->\s*\n?\s*(\w+_SCHEMA_VERSION)"""
        )
        val match = pattern.find(storeSource)
        requireNotNull(match) { "saveSchemaFor 里找不到 ${renderer.name} 的 schema 常量" }
        return match.groupValues[1]
    }

    private fun bodyOf(functionName: String): String {
        val at = storeSource.indexOf("fun $functionName(")
        require(at >= 0) { "源码里找不到 $functionName" }
        // 这两个函数都是单表达式体，到下一个声明为止
        val rest = storeSource.substring(at)
        val end = Regex("""\n\s*(private |internal |public )?(fun|val|const val|/\*\*)""")
            .find(rest, 1)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    /** 最新一代 = 枚举里最后声明的那个。写死代号的话每换一代都要改测试。 */
    private val current get() = SpatialLdiRenderer.entries.last()

    @Test
    fun 当前档的_schema_必须登记进_isLdiSchema() {
        val constant = schemaConstantFor(current)
        assertTrue(
            "$constant 未登记进 isLdiSchema——loadManifest 会静默判死，端上只报『无法回读』",
            bodyOf("isLdiSchema").contains(constant)
        )
    }

    @Test
    fun 每一档已发布的_LDI_renderer_都登记在案() {
        val body = bodyOf("isLdiSchema")
        val missing = SpatialLdiRenderer.entries
            .filter { storeSource.contains("SpatialLdiRenderer.${it.name} ->") }
            .filterNot { body.contains(schemaConstantFor(it)) }
        assertTrue("以下 renderer 的 schema 未登记进 isLdiSchema：$missing", missing.isEmpty())
    }

    @Test
    fun isCurrentGeneration_指向当前档而不是上一档() {
        val body = bodyOf("isCurrentGeneration")
        assertTrue(
            "isCurrentGeneration 仍指向旧 renderer（应为 ${current.name}）——" +
                "新派生会被判过期，每次进空间模式重算",
            body.contains(current.name)
        )
    }

    @Test
    fun 当前档的_schema_必须登记进_SUPPORTED_SCHEMA_VERSIONS() {
        // 第五处。2026-08-13 上 vNext15 时就漏了这一处：manifest 写得完全正确、四处
        // 登记也都补了，`manifestMatchesSource` 第一行仍直接判死，症状与漏 isLdiSchema
        // 时一模一样（"无法回读刚保存的空间派生"）。
        val at = storeSource.indexOf("SUPPORTED_SCHEMA_VERSIONS =")
        require(at >= 0) { "源码里找不到 SUPPORTED_SCHEMA_VERSIONS" }
        val body = storeSource.substring(at, storeSource.indexOf(")", at))
        val constant = schemaConstantFor(current)
        assertTrue(
            "$constant 未登记进 SUPPORTED_SCHEMA_VERSIONS——manifestMatchesSource 会静默判死",
            body.contains(constant)
        )
    }

    @Test
    fun 每一档已发布的_schema_都要在_SUPPORTED_SCHEMA_VERSIONS_里() {
        val at = storeSource.indexOf("SUPPORTED_SCHEMA_VERSIONS =")
        val body = storeSource.substring(at, storeSource.indexOf(")", at))
        val missing = SpatialLdiRenderer.entries
            .filter { storeSource.contains("SpatialLdiRenderer.${it.name} ->") }
            .filterNot { body.contains(schemaConstantFor(it)) }
        assertTrue("以下 schema 未登记进 SUPPORTED_SCHEMA_VERSIONS：$missing", missing.isEmpty())
    }
}
