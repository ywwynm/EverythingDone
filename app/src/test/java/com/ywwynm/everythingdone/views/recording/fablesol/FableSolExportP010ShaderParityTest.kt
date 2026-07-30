package com.ywwynm.everythingdone.views.recording.fablesol

import java.io.File
import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两个 P010 着色器与 [FableSolExportP010Math] 的**逐项对照**。
 *
 * 着色器在 JVM 上跑不了，而它与 Kotlin 侧是同一套数学的两份实现——只要有一份被"顺手整理"
 * 改动，产物就会静默变成另一种颜色。这里把两边必须一致的东西钉死：抽头表、传递函数常量、
 * 闭环的四条门禁、量化方式，以及三趟 GPU 的先后顺序。
 */
class FableSolExportP010ShaderParityTest {

    private val math = FableSolExportP010Math

    // ---- 色度趟 ----

    @Test
    fun chromaShaderUsesThePhaseAwareTapsInsteadOfABoxAverage() {
        val chroma = shader("p010_chroma.frag")

        // 旧的 2×2 box average 必须彻底消失：它的色度中心落在 (0.5, 0.5)，等价于 H.273
        // Type 1，而 BT.2020/BT.2100 规定 Type 2（D154）。
        assertFalse(chroma.contains("sum * 0.25"))
        assertFalse(chroma.contains("const vec3 K_LUMA"))

        val centred = math.downsampleTaps(0.5)
        assertEquals(listOf(-1, 0, 1, 2), centred.map { it.first })
        assertEquals(listOf(0.125, 0.375, 0.375, 0.125), centred.map { it.second })
        assertTrue(chroma.contains("taps.offsets = ivec4(-1, 0, 1, 2)"))
        assertTrue(chroma.contains("taps.weights = vec4(0.125, 0.375, 0.375, 0.125)"))

        val coSited = math.downsampleTaps(0.0)
        assertEquals(listOf(-1, 0, 1), coSited.map { it.first })
        assertEquals(listOf(0.125, 0.75, 0.125), coSited.map { it.second })
        assertTrue(chroma.contains("taps.offsets = ivec4(centre - 1, centre, centre + 1, 0)"))
        assertTrue(chroma.contains("taps.weights = vec4(0.125, 0.75, 0.125, 0.0)"))
        // 奇数行共点（Type 4/5）时滤波中心整体挪一格。
        assertTrue(chroma.contains("int centre = phase >= 0.75 ? 1 : 0"))
        // 边界一致延拓：夹住下标，不折返，也不把首末样本挪到另一个采样位置。
        assertTrue(chroma.contains("int cx = clamp(x, 0, uWidth - 1)"))
    }

    @Test
    fun chromaShaderTakesTheMatrixFromUniformsAndQuantisesWithBlueNoise() {
        val chroma = shader("p010_chroma.frag")

        assertTrue(chroma.contains("float luma = dot(rgb, uLumaWeights)"))
        assertTrue(chroma.contains("float cb = (rgb.b - luma) / uChromaScale.x"))
        assertTrue(chroma.contains("float cr = (rgb.r - luma) / uChromaScale.y"))
        // 量化：floor(value + threshold)，再钳到本次信号范围（D157 第 6 条）。
        assertTrue(chroma.contains("clamp(floor(value + threshold), codeRange.x, codeRange.y)"))
        // Cb 与 Cr 各钳到**自己**的区间（D140）：两个色度分量的设备安全区间未必一样宽，
        // 合并成一条会让较窄的那个在边缘像素上越界。
        assertTrue(chroma.contains("uniform vec4 uChromaCodeRange"))
        assertTrue(chroma.contains("uChromaCodeRange.xy"))
        assertTrue(chroma.contains("uChromaCodeRange.zw"))
        assertTrue(chroma.contains("if (!uNoiseEnabled) return 0.5;"))
        assertTrue(chroma.contains("(float(texelFetch(uNoise, p, 0).r) + 0.5) / NOISE_LEVELS"))
        assertEquals(
            FableSolExportBlueNoise.SIZE,
            constantOf(chroma, "const int NOISE_SIZE").toInt()
        )
        assertEquals(
            FableSolExportBlueNoise.LEVELS.toDouble(),
            constantOf(chroma, "const float NOISE_LEVELS"),
            0.0
        )
        // Cb 与 Cr 各用一个固定且互不相同的相位偏移，避免两个量化器形成规则相关性。
        assertTrue(chroma.contains("noiseThreshold(dst, uNoisePhaseCb)"))
        assertTrue(chroma.contains("noiseThreshold(dst, uNoisePhaseCr)"))
    }

    // ---- 亮度趟 ----

    @Test
    fun lumaShaderReconstructsChromaWithTheMirrorPhase() {
        val luma = shader("p010_luma.frag")

        val evenCoSited = math.upsampleTaps(0, 0.0)
        assertEquals(listOf(0 to 1.0), evenCoSited)
        val oddCoSited = math.upsampleTaps(1, 0.0)
        assertEquals(listOf(0 to 0.5, 1 to 0.5), oddCoSited)
        assertTrue(luma.contains("taps.offsets = even ? ivec2(0, 0) : ivec2(0, 1)"))
        assertTrue(luma.contains("taps.weights = even ? vec2(1.0, 0.0) : vec2(0.5, 0.5)"))

        val evenCentred = math.upsampleTaps(0, 0.5)
        assertEquals(listOf(-1 to 0.25, 0 to 0.75), evenCentred)
        val oddCentred = math.upsampleTaps(1, 0.5)
        assertEquals(listOf(0 to 0.75, 1 to 0.25), oddCentred)
        assertTrue(luma.contains("taps.weights = even ? vec2(0.25, 0.75) : vec2(0.75, 0.25)"))

        val evenOdd = math.upsampleTaps(0, 1.0)
        assertEquals(listOf(-1 to 0.5, 0 to 0.5), evenOdd)
        assertTrue(luma.contains("taps.offsets = even ? ivec2(-1, 0) : ivec2(0, 0)"))

        // 读的是上一趟**真正写出去的**码值，不是理想色度（D157 第 4 条）。
        assertTrue(luma.contains("texelFetch(uChroma, ivec2(cx, cy), 0)"))
        assertTrue(luma.contains("vec2(floor(cbWord / 64.0), floor(crWord / 64.0))"))
    }

    @Test
    fun lumaShaderTransferConstantsMatchTheKotlinCore() {
        val luma = shader("p010_luma.frag")

        assertEquals(2610.0 / 16384.0, constantOf(luma, "const float PQ_M1"), 0.0)
        assertEquals(2523.0 / 4096.0 * 128.0, constantOf(luma, "const float PQ_M2"), 0.0)
        assertEquals(3424.0 / 4096.0, constantOf(luma, "const float PQ_C1"), 0.0)
        assertEquals(2413.0 / 4096.0 * 32.0, constantOf(luma, "const float PQ_C2"), 0.0)
        assertEquals(2392.0 / 4096.0 * 32.0, constantOf(luma, "const float PQ_C3"), 0.0)

        val hlgA = 0.17883277
        assertEquals(hlgA, constantOf(luma, "const float HLG_A"), 0.0)
        assertEquals(1.0 - 4.0 * hlgA, constantOf(luma, "const float HLG_B"), 1e-8)
        assertEquals(0.5 - hlgA * ln(4.0 * hlgA), constantOf(luma, "const float HLG_C"), 1e-8)
        assertEquals(2.4, constantOf(luma, "const float BT1886_GAMMA"), 0.0)

        assertEquals(64.0, constantOf(luma, "const float LUMA_MIN_CODE"), 0.0)
        assertEquals(
            FableSolExportP010Math.LUMA_MIN_CODE,
            constantOf(luma, "const float LUMA_MIN_CODE"),
            0.0
        )
        assertEquals(
            FableSolExportP010Math.LUMA_RANGE,
            constantOf(luma, "const float LUMA_RANGE"),
            0.0
        )
        assertEquals(
            FableSolExportP010Math.CHROMA_MID_CODE,
            constantOf(luma, "const float CHROMA_MID_CODE"),
            0.0
        )
        assertEquals(
            FableSolExportP010Math.CHROMA_RANGE,
            constantOf(luma, "const float CHROMA_RANGE"),
            0.0
        )

        // 分支号与 Kotlin 侧的 shaderCode 一一对应。
        assertEquals(0, FableSolExportP010Math.Transfer.BT1886.shaderCode)
        assertEquals(1, FableSolExportP010Math.Transfer.PQ.shaderCode)
        assertEquals(2, FableSolExportP010Math.Transfer.HLG.shaderCode)
        assertTrue(luma.contains("if (uTransfer == 1) {"))
        assertTrue(luma.contains("if (uTransfer == 2) {"))
    }

    /** 闭环的四条门禁一条都不能少，否则它会从"修正"变成"随手改亮度"。 */
    @Test
    fun lumaShaderKeepsAllFourClosedLoopGates() {
        val luma = shader("p010_luma.frag")
        val body = luma.substring(
            luma.indexOf("float correctLuma("),
            luma.indexOf("float noiseThreshold(")
        )

        assertTrue(body.contains("if (!(slope > SLOPE_EPSILON)) return originalLuma;"))
        assertTrue(body.contains("clamp(step, -uMaxLumaCorrection, uMaxLumaCorrection)"))
        assertTrue(body.contains("if (originalLegal && !isLegal(candidateRgb)) return originalLuma;"))
        assertTrue(
            body.contains("abs(candidateLuminance - target) < abs(originalLuminance - target)")
        )
        assertTrue(body.contains("isnan(step) || isinf(step)"))
        // 修正量的上界来自版本化常量，不是着色器里随手写的一个数。
        assertTrue(luma.contains("uniform float uMaxLumaCorrection"))
        assertEquals(24.0, FableSolExportP010Math.MAX_LUMA_CORRECTION_CODES, 0.0)
    }

    // ---- 三趟 GPU 的顺序与接线 ----

    @Test
    fun bridgeRunsChromaBeforeLumaAndFeedsTheQuantisedChromaBack() {
        val bridge = exportFile("FableSolExportP010Bridge.kt")

        val chromaPass = bridge.indexOf("drawInto(chromaTarget, chromaProgram)")
        val lumaPass = bridge.indexOf("drawInto(lumaTarget, lumaProgram)")
        assertTrue(chromaPass > 0)
        assertTrue("亮度趟必须在色度趟之后", lumaPass > chromaPass)
        assertTrue(bridge.contains("GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, chromaTarget.textureId)"))
        assertTrue(bridge.contains("""program.uniform("uChroma"), 1"""))
        // 统计只为 HDR10+ 存在：其余 10-bit 档位既不建线性附件、也不跑逐帧归约与回读。
        assertTrue(bridge.contains("private val statsBackend = if (collectStats) {"))
        assertTrue(bridge.contains("private val linearTarget = if (collectStats) {"))
    }

    /**
     * 三趟读的必须是**同一个**呈现中间面。
     *
     * 统计与编码输入来自不同精度的画面，是最难看出来的一类错误：元数据说的峰值与码流里的
     * 像素对不上，播放端按错的峰值还原，而两边各自都"没报错"（D153 第 3 条）。
     */
    @Test
    fun conversionAndStatisticsReadTheSamePresentationTarget() {
        val bridge = exportFile("FableSolExportP010Bridge.kt")
        val present = shader("export_present.frag")

        assertTrue(bridge.contains("GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, presentTarget.textureId)"))
        // 两趟转换共用同一条 drawInto，uSource 恒为 0 号单元。
        assertTrue(bridge.contains("""GLES30.glUniform1i(program.uniform("uSource"), 0)"""))
        assertEquals(1, countOf(bridge, """program.uniform("uSource")"""))
        // HDR10+ 的统计读的是**同一次呈现**的第二个附件（线性 BT.2020），不是再画一遍：
        // 两遍之间任何一个 uniform 不同，统计描述的就不是编码进去的那张画面（D102、D103）。
        assertTrue(present.contains("layout(location = 0) out vec4 fragColor;"))
        assertTrue(present.contains("layout(location = 1) out vec4 fragLinear;"))
        assertTrue(
            present.contains("fragLinear = vec4(max(bt709ToBt2020(color), vec3(0.0)), 1.0);")
        )
        assertTrue(bridge.contains("GLES30.GL_COLOR_ATTACHMENT1"))
        // 统计读的是抖动前的样本：蓝噪声只在 P010 码值量化那一步出现（D157 第 5 条）。
        val linearIndex = present.indexOf("fragLinear =")
        val ditherIndex = present.indexOf("blueNoiseQuantize(encoded")
        assertTrue(ditherIndex in 1 until linearIndex)
    }

    /** 三条资源后备都必须是"降级继续"，不是"报失败"（D153/D157 的同格式后备）。 */
    @Test
    fun resourceFallbacksDegradeInPlaceInsteadOfFailingTheExport() {
        val bridge = exportFile("FableSolExportP010Bridge.kt")
        val luma = shader("p010_luma.frag")
        val chroma = shader("p010_chroma.frag")
        val encoder = exportFile("FableSolExportEncoder.kt")

        // 蓝噪声资源不可用：占位纹理 + uNoiseEnabled=false，阈值固定 0.5，即普通四舍五入。
        // 启用判据必须是"64×64 表本身上传成功"，不能看占位纹理是否建出来——否则 upload()
        // 失败、占位成功的组合会以 uNoiseEnabled=true 对 1×1 纹理越界 texelFetch。
        assertTrue(bridge.contains("?: FableSolExportBlueNoise.uploadNeutral()"))
        assertTrue(bridge.contains("private val noiseEnabled = blueNoiseTextureId != null"))
        assertTrue(luma.contains("if (!uNoiseEnabled) return 0.5;"))
        assertTrue(chroma.contains("if (!uNoiseEnabled) return 0.5;"))

        // P010 排布与我们要写的半平面交错不符：判本候选失败，交由阶梯退到同格式 Surface，
        // 而不是照写一帧花屏。
        assertTrue(encoder.contains("P010 chroma is not semi-planar"))
        assertTrue(encoder.contains("inputLayout.withPlaneRowStrides("))
        // 缓冲不够大同样是候选失败，不能截断写入。
        assertTrue(bridge.contains("check(destination.capacity() >= layout.requiredBytes)"))
    }

    @Test
    fun presentTargetPrefersHalfFloatAndProvesItWithFramebufferCompleteness() {
        val target = exportFile("FableSolExportPresentTarget.kt")

        assertTrue(target.contains("attempt(widthPx, heightPx, GLES30.GL_RGBA16F, highPrecision = true)"))
        assertTrue(target.contains("attempt(widthPx, heightPx, GLES30.GL_RGB10_A2, highPrecision = false)"))
        // 只看 GLES 版本或扩展字符串不算数（D153）：必须真的建出附件并检查 completeness。
        assertTrue(target.contains("GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)"))
        assertTrue(target.contains("status != GLES30.GL_FRAMEBUFFER_COMPLETE"))
        assertFalse(target.contains("glGetString"))
    }

    /** 输入通路是同一档位下的子候选，不能被提成另一种格式（D158 第 5 条）。 */
    @Test
    fun inputPathIsASubCandidateOfTheSameTier() {
        val encoder = exportFile("FableSolExportEncoder.kt")

        assertTrue(encoder.contains("entry.eightBit -> listOf(FableSolExportInputPath.SURFACE)"))
        assertTrue(encoder.contains("p010Required && listsP010 -> listOf(FableSolExportInputPath.APP_P010)"))
        assertTrue(encoder.contains("p010Required -> emptyList()"))
        // 排序键的最后一项才是输入通路：它因此永远不会把某个编码器的 Surface 提到另一个
        // 编码器的 P010 前面。
        val comparator = encoder.substring(
            encoder.indexOf("return collected.sortedWith("),
            encoder.indexOf(").map { it.second }")
        )
        assertTrue(
            comparator.indexOf("tier.usesAppP010") >
                comparator.indexOf("tier.family.ordinal")
        )
        assertTrue(
            comparator.indexOf("tier.usesAppP010") > comparator.indexOf("tier.softwareOnly")
        )
        assertEquals(54, FableSolExportTier.COLOR_FORMAT_YUV_P010)
    }

    /** 导出关键路径上不得触发能力探测：相位取不到就走 Type 0 兼容语义（D154 第 3 条）。 */
    @Test
    fun exportResolvesChromaSitingFromCacheWithoutProbing() {
        val exporter = exportFile("FableSolVideoExporter.kt")

        assertTrue(exporter.contains("FableSolHdrExportCapability.cachedMatrix(context).chromaSiting("))
        assertFalse(exporter.contains("FableSolHdrExportCapability.matrix("))
        assertTrue(
            exporter.contains("FableSolExportP010Math.ChromaSiting.COMPATIBLE_DEFAULT")
        )
        assertEquals(
            FableSolExportP010Math.ChromaSiting.TYPE_0,
            FableSolExportP010Math.ChromaSiting.COMPATIBLE_DEFAULT
        )
        assertEquals(
            FableSolExportP010Math.ChromaSiting.TYPE_2,
            FableSolExportP010Math.ChromaSiting.PREFERRED
        )
        // HDR10+ 的 SEI 门禁只对 HDR10+ 成立；别的 10-bit 档位同样走 P010，不该被它挡住。
        assertTrue(exporter.contains("check(!hdr10Plus || encoder.hdr10PlusSeiSeen)"))
        assertTrue(exporter.contains("val byteBuffer = tier.usesAppP010"))
    }

    private fun countOf(source: String, needle: String): Int =
        source.split(needle).size - 1

    private fun constantOf(source: String, declaration: String): Double {
        val index = source.indexOf(declaration)
        assertTrue("找不到 $declaration", index >= 0)
        val value = source.substring(index + declaration.length)
            .substringAfter('=')
            .substringBefore(';')
            .trim()
        return value.toDouble()
    }

    private fun shader(name: String): String = projectFile("shared/fablesol/glsl/$name")

    private fun exportFile(name: String): String = projectFile(
        "app/src/main/java/com/ywwynm/everythingdone/views/recording/fablesol/$name"
    )

    private fun projectFile(relativePath: String): String {
        var directory = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) {
                return candidate.readText(Charsets.UTF_8).replace("\r\n", "\n")
            }
            directory = directory.parentFile ?: return@repeat
        }
        error("找不到 $relativePath")
    }
}
