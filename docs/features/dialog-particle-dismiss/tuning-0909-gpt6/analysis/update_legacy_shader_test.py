from pathlib import Path
root=next(p for p in Path(__file__).resolve().parents if (p/'gradlew.bat').is_file())
path=root/'app/src/test/java/com/ywwynm/everythingdone/views/particledismiss/ParticleDismissShaderCompileTest.kt'
path.write_text(r'''package com.ywwynm.everythingdone.views.particledismiss

import java.io.File
import java.security.MessageDigest
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 本轮只替换消失路径；出现路径保留并继续做实际语法编译。 */
class ParticleDismissShaderCompileTest {
    private val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "shared/particle-dismiss").isDirectory }
    private val source get() = File(root,
        "app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/ParticleDismissRenderer.kt").readText()

    private fun shader(name: String): String = Regex("private val $name = \"\"\"(.*?)\"\"\"\\.trimIndent\\(\\)",
        RegexOption.DOT_MATCHES_ALL).find(source)!!.groupValues[1].trimIndent()

    @Test fun `四段出现着色器保持本轮开始时的内容`() {
        for ((name, expected) in preserved) {
            val canonical = shader(name).trim().lines().joinToString("\n") { it.trim() }
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            assertEquals(name, expected, digest)
            assertFalse("不得残留模板插值", '$' in shader(name))
        }
    }

    @Test fun `出现路径通过实际GLES语法编译`() {
        val properties = Properties().apply { File(root, "local.properties").takeIf(File::isFile)?.inputStream()?.use(::load) }
        val sdk = System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_HOME") ?: properties.getProperty("sdk.dir")
        val validator = sdk?.let { File(it, "emulator/lib64/vulkan/glslangValidator.exe") }
        assumeTrue("本机没有外部 GLSL 编译器", validator?.isFile == true)
        for (name in preserved.keys) {
            val stage = if ("VERTEX" in name) "vert" else "frag"
            val file = File.createTempFile("particle-condense-", ".$stage")
            try {
                file.writeText(shader(name))
                val process = ProcessBuilder(validator!!.path, file.path).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                assertEquals("$name\n$output", 0, process.waitFor())
            } finally { file.delete() }
        }
    }

    companion object {
        private val preserved = linkedMapOf(
            "VERTEX_SHADER" to "3ead90621fcdcd20efa9cd95bca9d2a43d4013a752af27a1e42446ddd88cfde1",
            "FRAGMENT_SHADER" to "64fdebd3d25bc0312f8806f11a8fc3711343d05b8ace01b37759e8a1570d2d09",
            "STILL_VERTEX_SHADER" to "b05e03e9572a116a2f75645c07ac45e95b5581516dc6a8d8f9b75f66dd74d095",
            "STILL_FRAGMENT_SHADER" to "cc65a2e6c5220c5c60e50db9e9bb5c38a2c323ed445c69a5d42c7bf26cf73426"
        )
    }
}
''',encoding='utf-8')
