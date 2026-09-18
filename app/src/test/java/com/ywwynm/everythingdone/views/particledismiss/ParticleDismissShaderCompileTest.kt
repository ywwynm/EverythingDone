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
        // 第二阶段：每一项都是一组编译期宏，变体在设备上才会被编译到。本机先各编一遍，
        // 免得一个拼写错误要等装到真机上才发现（Maleoon 的链接检查最严，但 Adreno 的宽容
        // 会掩盖问题，两边都不该是第一道关）。
        for ((file, defines) in PHASE_TWO_VARIANTS) {
            val source = File(root, file)
            for (macros in defines) {
                compile(validator, "$file [" + macros.replace("\n", " ").trim() + "]",
                    ParticleReverseShader.withDefines(source.readText(), macros),
                    "." + source.extension)
            }
        }
    }

    private val MATERIAL_VARIANTS = listOf(
        "#define DRAW_RECORDS 1\n",
        "#define DRAW_RECORDS 1\n#define MEDIUMP_VARYING 1\n",
        "#define DRAW_RECORDS 1\n#define FLAKE_CONSTANTS 1\n",
        "#define DRAW_RECORDS 1\n#define FLAKE_CONSTANTS 1\n#define SINGLE_INSTANCE 1\n",
        "#define SRGB_TARGET 1\n",
        "#define DRAW_RECORDS 1\n#define MEDIUMP_VARYING 1\n#define FLAKE_CONSTANTS 1\n" +
            "#define SINGLE_INSTANCE 1\n#define SRGB_TARGET 1\n")

    /**
     * 第二阶段的编译期宏组合。**每个文件都要覆盖「单开」与「全开」两种**：
     * 单开抓拼写，全开抓两项宏互相冲突（例如 varying 精度与逐片量下沉同时改）。
     */
    private val PHASE_TWO_VARIANTS = listOf(
        "shared/particle-dismiss/step.comp" to listOf(
            "#define LINEAR_GROUP 64\n",
            "#define MEDIUMP_SAMPLER 1\n",
            "#define MERGED_FIELD 1\n",
            "#define PEEL_FIELD_HALF 1\n",
            "#define MEDIUMP_ALU 1\n",
            "#define HARDWARE_TRILINEAR 1\n#define MERGED_FIELD 1\n",
            "#define PROBE_SINGLE_CELL 1\n",
            "#define LINEAR_GROUP 64\n#define MEDIUMP_SAMPLER 1\n#define MERGED_FIELD 1\n" +
                "#define PEEL_FIELD_HALF 1\n#define MEDIUMP_ALU 1\n#define HARDWARE_TRILINEAR 1\n"),
        "shared/particle-dismiss/peel-splat.comp" to listOf(
            "#define LINEAR_GROUP 64\n",
            "#define NEAREST_SPLAT 1\n",
            "#define ACTIVE_REGION 1\n#define CLEAR_ACTIVE_REGION 1\n",
            "#define ACTIVE_REGION 1\n#define CLEAR_ACTIVE_REGION 1\n#define NEAREST_SPLAT 1\n" +
                "#define LINEAR_GROUP 64\n"),
        "shared/particle-dismiss/peel-blur.comp" to listOf(
            "#define PLANE_GROUP 8\n", "#define MEDIUMP_ALU 1\n",
            "#define ACTIVE_REGION 1\n#define PLANE_GROUP 8\n#define MEDIUMP_ALU 1\n"),
        "shared/particle-dismiss/peel-project.comp" to listOf(
            "#define PLANE_GROUP 8\n", "#define PEEL_FIELD_HALF 1\n",
            "#define ACTIVE_REGION 1\n#define PLANE_GROUP 8\n#define PEEL_FIELD_HALF 1\n"),
        "shared/particle-dismiss/draw-record.comp" to listOf(
            "#define LINEAR_GROUP 64\n", "#define FLAKE_CONSTANTS 1\n",
            "#define FLAKE_CONSTANTS 1\n#define SINGLE_INSTANCE 1\n#define LINEAR_GROUP 64\n"),
        "app/src/main/assets/particle-playback/pressure-batched.comp" to listOf(
            "#define REGISTER_BANDS 1\n"),
        "app/src/main/assets/particle-playback/history.comp" to listOf(
            "#define LINEAR_GROUP 64\n"),
        // 材料着色器的变体：DRAW_RECORDS 是现行生产路径，无宏那一份是基线路径。
        "shared/particle-dismiss/material.vert" to MATERIAL_VARIANTS,
        "shared/particle-dismiss/material.frag" to MATERIAL_VARIANTS)

    /** [suffix] 决定 glslangValidator 按哪一个阶段编译，必须与原文件一致。 */
    private fun compile(validator: File, label: String, source: String, suffix: String = ".comp") {
        val file = File.createTempFile("particle-shader-", suffix)
        try {
            file.writeText(source)
            val process = ProcessBuilder(validator.path, file.path).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals("$label\n$output", 0, process.waitFor())
        } finally { file.delete() }
    }
}
