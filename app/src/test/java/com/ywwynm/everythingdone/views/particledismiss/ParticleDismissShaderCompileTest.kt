package com.ywwynm.everythingdone.views.particledismiss

import java.io.File
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 验证当前共同模型及逆向缓存实际使用的 GLES 着色器。 */
class ParticleDismissShaderCompileTest {

    /** 需要外部宏才能编译的着色器：直接丢给编译器会缺少 tile 尺寸常量。 */
    private val parametrised = mapOf(
        "pressure-batched-large.comp" to listOf("#define BAND 8\n", "#define BAND 12\n", "#define BAND 16\n")
    )

    @Test fun `共同模型和逆向播放通过实际GLES语法编译`() {
        val root = generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .first { File(it, "shared/particle-dismiss").isDirectory }
        val properties = Properties().apply { File(root, "local.properties").takeIf(File::isFile)?.inputStream()?.use(::load) }
        val sdk = System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_HOME") ?: properties.getProperty("sdk.dir")
        val validator = sdk?.let { File(it, "emulator/lib64/vulkan/glslangValidator.exe") }
        assumeTrue("本机没有外部 GLSL 编译器", validator?.isFile == true)
        for (dir in listOf("shared/particle-dismiss", "app/src/main/assets/particle-playback")) {
            for (file in File(root, dir).listFiles()!!.filter { it.extension in listOf("comp", "vert", "frag") }) {
                val variants = parametrised[file.name]
                if (variants == null) {
                    val process = ProcessBuilder(validator!!.path, file.path).redirectErrorStream(true).start()
                    val output = process.inputStream.bufferedReader().readText()
                    assertEquals("${file.name}\n$output", 0, process.waitFor())
                } else for (defines in variants) {
                    compile(validator!!, "${file.name} [${defines.trim()}]",
                        ParticleReverseShader.withDefines(file.readText(), defines))
                }
            }
        }
        val step = File(root, "shared/particle-dismiss/step.comp").readText()
        compile(validator!!, "逆向准备积分器", ParticleReverseShader.integration(step))
        compile(validator, "逆向准备积分器（历史合并）", ParticleReverseShader.integration(step, withHistory = true))
    }

    private fun compile(validator: File, label: String, source: String) {
        val file = File.createTempFile("particle-shader-", ".comp")
        try {
            file.writeText(source)
            val process = ProcessBuilder(validator.path, file.path).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals("$label\n$output", 0, process.waitFor())
        } finally { file.delete() }
    }
}
