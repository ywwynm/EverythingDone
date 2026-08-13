package com.ywwynm.everythingdone.spatial

import java.io.File
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

class SpatialDepthSurfelShaderCompileTest {

    @Test
    fun `GLES2微表面着色器必须通过真实语法编译`() {
        val validator = resolveValidator()
        assumeTrue("本机没有 glslangValidator，跳过外部着色器编译", validator?.isFile == true)
        val rendererSource = readProjectFile(
            "app/src/main/java/com/ywwynm/everythingdone/spatial/SpatialPhotoRenderer.kt"
        ).readText(Charsets.UTF_8)
        val shaders = listOf(
            "SURFEL_VERTEX_SHADER" to "vert",
            "SURFEL_FRAGMENT_SHADER" to "frag",
            "SURFEL_COMPOSITE_FRAGMENT_SHADER" to "frag"
        )
        for ((name, stage) in shaders) {
            val marker = "private const val $name = \"\"\""
            check(marker in rendererSource) { "找不到着色器：$name" }
            val shader = rendererSource.substringAfter(marker).substringBefore("\"\"\"")
            val temporary = File.createTempFile("spatial-surfel-$name-", ".$stage")
            try {
                temporary.writeText(shader, Charsets.UTF_8)
                val process = ProcessBuilder(validator!!.absolutePath, temporary.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val exitCode = process.waitFor()
                assertEquals("$name 编译失败：\n$output", 0, exitCode)
            } finally {
                temporary.delete()
            }
        }
    }

    private fun resolveValidator(): File? {
        val environmentSdk = sequenceOf(
            System.getenv("ANDROID_SDK_ROOT"),
            System.getenv("ANDROID_HOME")
        ).filterNotNull().map(::File).firstOrNull(File::isDirectory)
        val localSdk = runCatching {
            val properties = Properties()
            readProjectFile("local.properties").inputStream().use(properties::load)
            properties.getProperty("sdk.dir")?.let(::File)
        }.getOrNull()
        val sdk = environmentSdk ?: localSdk ?: return null
        return listOf(
            File(sdk, "emulator/lib64/vulkan/glslangValidator.exe"),
            File(sdk, "emulator/lib64/vulkan/glslangValidator")
        ).firstOrNull(File::isFile)
    }

    private fun readProjectFile(relativePath: String): File {
        var directory = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(7) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate
            directory = directory.parentFile ?: return@repeat
        }
        error("找不到项目文件：$relativePath")
    }
}
