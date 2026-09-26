package com.ywwynm.everythingdone.taptransfer.ui.particles

import java.io.File
import java.util.Properties
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 接收光尘层（GPU）的三组着色器按 GLES 3.00 编译并链接，免得语法或阶段间的接口问题要到真机上才发现。 */
class TapDustLayerShaderCompileTest {

    @Test fun `光尘、柔光与扩散圈的着色器能编译并链接`() {
        val root = generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .first { File(it, "shared/particle-dismiss").isDirectory }
        val properties = Properties().apply { File(root, "local.properties").takeIf(File::isFile)?.inputStream()?.use(::load) }
        val sdk = System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_HOME") ?: properties.getProperty("sdk.dir")
        val validator = sdk?.let { File(it, "emulator/lib64/vulkan/glslangValidator.exe") }
        assumeTrue("本机没有外部 GLSL 编译器", validator?.isFile == true)
        link(validator!!, "光尘", TapDustLayer.FLAKE_VS, TapDustLayer.FLAKE_FS)
        link(validator, "柔光", TapDustLayer.FULL_VS, TapDustLayer.GLOW_FS)
        link(validator, "扩散圈", TapDustLayer.RING_VS, TapDustLayer.RING_FS)
    }

    private fun link(validator: File, label: String, vertex: String, fragment: String) {
        val dir = createTempDirectory("tap-dust-shader-").toFile()
        try {
            val vs = File(dir, "a.vert").apply { writeText(vertex) }
            val fs = File(dir, "a.frag").apply { writeText(fragment) }
            val process = ProcessBuilder(validator.path, "-l", vs.path, fs.path).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals("$label\n$output", 0, process.waitFor())
        } finally {
            dir.deleteRecursively()
        }
    }
}
