package com.ywwynm.everythingdone.views.particledismiss

import java.io.File
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 消散着色器的语法编译与模型契约。
 *
 * 契约来自 2026-08-30 对参考动画的逐帧复测，见
 * `docs/features/dialog-particle-dismiss/research-2026-08-30-reference-remeasure.md`。
 * 这里逐项点名允许出现的用法，不做宽泛的关键字搜索，避免被注释里的解释性
 * 文字误判。
 */
class ParticleDismissShaderCompileTest {

    @Test
    fun `GLES3粒子消散着色器必须通过真实语法编译`() {
        val validator = resolveValidator()
        assumeTrue("本机没有 glslangValidator，跳过外部着色器编译", validator?.isFile == true)
        val rendererSource = rendererSource()
        val sharedField = extractShader(rendererSource, "DISMISS_FIELD_GLSL")
        val shaders = listOf(
            Triple("DISMISS_VERTEX_SHADER", "vert", true),
            Triple("DISMISS_FRAGMENT_SHADER", "frag", false),
            Triple("STILL_VERTEX_SHADER", "vert", false),
            Triple("DISMISS_STILL_FRAGMENT_SHADER", "frag", true)
        )
        for ((name, stage, usesSharedField) in shaders) {
            var shader = extractShader(rendererSource, name)
            if (usesSharedField) {
                shader = shader.replace("${'$'}DISMISS_FIELD_GLSL", sharedField)
            }
            check("${'$'}DISMISS_FIELD_GLSL" !in shader) {
                "$name 仍包含未展开的共享 Shader 占位符"
            }
            val temporary = File.createTempFile("particle-dismiss-$name-", ".$stage")
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

    @Test
    fun `释放场必须是无纹理的低频核场且只由种子与方向决定`() {
        val field = extractShader(rendererSource(), "DISMISS_FIELD_GLSL")

        assertFalse("释放场不得再依赖任何采样器", "sampler" in field)
        assertFalse("释放场不得读取 PBD 轨迹", "uClothFrames" in field)
        assertFalse("释放场不得读取预解算释放纹理", "uReleaseProfile" in field)
        assertTrue("缺少早释放核构造", "void curtainNucleus(" in field)
        assertTrue(
            "早释放核必须沿卡片轮廓定位，否则宽 Dialog 上的核会掉到卡外",
            "float rim = min(" in field
        )
        assertTrue(
            "顺风角必须作为**第二个起爆点**参与，用 min 与斜坡合并，不能从斜坡里" +
                "减掉一个鼓包。减法必然在鼓包外沿留下一圈局部极大——那里提前量已经" +
                "没了、而斜坡仍判它为最晚（它在顺风端），屏幕上是一道始终不化的" +
                "白钩，读起来像「从一个洞开始」而不是「角在化掉」；释放场探针上能" +
                "直接看到那圈比周围更晚的环。两个单调波前取 min 不可能有局部极大",
            "phase = min(phase, cornerArrival);" in field
        )
        assertTrue(
            "角波前的核必须覆盖整个圆角区域：对话框是圆角的，矩形的角点落在可见" +
                "卡面之外，只锚在那一个点上时卡面只剩沿圆角外缘的一道细月牙",
            "max(cornerRadius - CURTAIN_CORNER_CORE, 0.0)" in field &&
                "float cornerRadius = length(q - downstreamCorner);" in field
        )
        assertTrue(
            "角核内不能是严格的平顶：平顶那一整片相位完全相等，会在同一刻整块" +
                "释放，屏幕上是角上凭空缺掉一块，而不是从角点烧开",
            "CURTAIN_CORNER_SEED" in field &&
                "min(cornerRadius, CURTAIN_CORNER_CORE)" in field
        )
        assertTrue(
            "释放时刻必须有起步偏置：叠层在真对话框窗口之上接管，第 0 帧必须与" +
                "原窗口逐像素相同。起火角的相位恰好是 0，没有偏置时它在第 0 帧" +
                "就已经缺了一块，交接处会看到一次跳变",
            "CURTAIN_RELEASE_ONSET" in field &&
                "+ (1.0 - CURTAIN_RELEASE_ONSET)" in field
        )
        assertTrue(
            "平顶的到角距离必须先被噪声扰动：否则平顶边界是一段干净圆弧，" +
                "弧与卡面的交界处出现尖角，看上去是被切了一刀而不是被侵蚀",
            "* (1.0 + CURTAIN_CORNER_WOBBLE * (cornerWobble - 0.5))" in field
        )
        assertFalse(
            "角内不得再抑制分形。当初的目标「保住角块同时释放、作为稠密团被吹走」" +
                "本身是错的：同龄同速的一整片材料在屏幕上就是一坨凸起，没有被风" +
                "吹起的轻盈感",
            "CURTAIN_CORNER_COHERENCE" in stripComments(field)
        )
        assertTrue(
            "角块内部的释放时刻必须错开，否则整片同龄同速地离开",
            "(1.0 + CURTAIN_CORNER_STAGGER * (cornerStagger - 0.5))" in field
        )
        assertTrue(
            "扇区不得整体镜像，否则顺风核会翻到别的边上",
            "vec2 perpendicular = vec2(-direction.y, direction.x);" in field
        )
        assertTrue("必须遍历全部早释放核", "for (int i = 0; i < CURTAIN_NUCLEUS_COUNT; i++)" in field)
        assertTrue(
            "提前量必须受本地相位限制，保证相位不小于 0",
            "min(CURTAIN_NUCLEUS_ADVANCE * advance, low * 0.985)" in field
        )
        assertTrue("缺少多尺度分形项", "float curtainFbm(" in field)
        assertFalse(
            "不得再有「中心钉」mix(low, 1.0, pow(centreness, 16.0))。它把卡片正中" +
                "一小块的相位硬顶到上界，用途只是让场的最大值落在中心；代价是正中" +
                "那一小块成了最后才消失的完整表面，在白色对话框上就是一个边缘锐利" +
                "的白圆。而且它连归一化都没做到——实测 maxPhase 只有 0.928，因为" +
                "探针取的是 cell 中心、取不到 q=0 那一点",
            "pow(centreness, 16.0)" in stripComments(field)
        )
        assertTrue(
            "中心度必须是椭圆型的（min(toEdge.x, toEdge.y) 的等值线是矩形，在宽卡上" +
                "整条水平中线取值都是 1），并且必须在被 sway 扭过的坐标上算——否则" +
                "它是一个规则的椭圆圆顶，最后残留的那块就是一个规则椭圆，在白色" +
                "对话框上很扎眼",
            "length((q + direction * sway) / max(corner, vec2(1.0e-4)))" in field
        )
        assertFalse(
            "中心度不得再用两条边距的较小值构造，它在两条对角线上还有折痕，" +
                "侵蚀边界会沿折痕吸附成直边并在端点形成尖角",
            "min(toEdge.x, toEdge.y)" in stripComments(field)
        )
        assertTrue(
            "前沿的形状必须来自对前沿**位置**的扰动，而不是给释放场加二维噪声。" +
                "加二维噪声时场里任何地方都可能出现低值坑，远离前沿的整块区域会" +
                "提前释放，屏幕上是与移动方向毫无关系的独立粒子化区；把幅度调大" +
                "到能匹配参考的方差配比时，场还会退化成两级台地，前沿上出现尖锐" +
                "的凸起与下凹",
            "float sway = (curtainFbm(" in field &&
                "(alongAxis + sway + extent + CURTAIN_SWAY_ROOM)" in field
        )
        assertTrue(
            "形状与纹理必须**全部**作用在前沿的位置上，不得有加性的相位项。加性项" +
                "会在场里造出局部极小——屏幕上是与周围完全不连通的孤立暗区，形状和" +
                "位置看不出任何规律；它同时让前沿在推进方向上不再单值，曲线一会上" +
                "一会下、凹凸不平。位置扰动受同一条不折叠约束，因此前沿永远是一条" +
                "单值的光滑曲线，孤岛在构造上不可能出现，团块由前沿的局部快慢自然" +
                "形成",
            "* CURTAIN_SWAY_FINE" in field && "* CURTAIN_SWAY_GRAIN;" in field
        )
        assertFalse(
            "释放相位里不得再出现加性的细尺度项",
            "fineRoom" in stripComments(field)
        )
        assertTrue(
            "起伏沿飞行轴的变化率必须远小于斜坡自身的斜率，场才沿飞行方向严格" +
                "单调，每条等值线才是横风向上的单值曲线——这是「永远连通、不出现" +
                "脱离前沿的独立区域」的充分条件",
            "alongAxis * CURTAIN_SWAY_SCALE * CURTAIN_SWAY_DRIFT" in field
        )
        assertTrue(
            "逐格抖动的幅度必须硬性不超过本格到相位两端的余量。渐隐式的收敛" +
                "（smoothstep(0, amount*0.5, room)）只是「靠近端点时变小」，" +
                "并不保证不越界：amount=0.36、base=0.09 时仍会被推到 -0.09，" +
                "clamp 之后就是 0——开场第一帧全卡各处同时冒粒子",
            "float amp = min(ditherAmount, room * 0.85);" in field &&
                "CURTAIN_DITHER_BASE + (1.0 - CURTAIN_DITHER_BASE) * base" in field
        )
        assertTrue(
            "交接带的宽度必须沿前沿起伏。只随相位变化时整条前沿处处等宽，屏幕上" +
                "是一条又粗又匀的实心带，没有轻重疏密",
            "float widthVar = mix(CURTAIN_DITHER_LOW, CURTAIN_DITHER_HIGH," in field &&
                "CURTAIN_DITHER * widthVar" in field &&
                "float widthRoll = curtainValueNoise(" in field
        )
        assertTrue(
            "斜坡量程必须给起伏留出余量，否则上游角一带的 ramp 会越过 1 被 clamp " +
                "压平成一整片同刻释放的格子——屏幕上是最后一块整片一次性消失",
            "(alongAxis + sway + extent + CURTAIN_SWAY_ROOM)" in field &&
                "2.0 * max(extent + CURTAIN_SWAY_ROOM, 1.0e-4)" in field
        )
        assertTrue(
            "斜坡必须做直方图均衡：线性斜坡投影到卡片上是三角分布，中间那一档的" +
                "格子扎堆，「已消耗面积对时间」的曲线必然是 S 形——开场慢、中段猛冲。" +
                "同内容对照实测参考在 n=0.10/0.20/0.30/0.40/0.50 的消耗量是 " +
                "0.066/0.186/0.267/0.366/0.548，未均衡时我们是 0.025/0.074/0.163/" +
                "0.356/0.663。单纯改速率指数补不了——快慢能调，形状调不了",
            "1.0 - 2.0 * (1.0 - ramp) * (1.0 - ramp);" in field
        )
        assertTrue(
            "表面消耗速率不是常数：参考的已释放面积对时间明显是凸的，必须用一次" +
                "单调重映射补上，等值线与拓扑不受影响",
            "pow(clamp(phase, 0.0, 1.0), CURTAIN_RATE_EXPONENT)" in field
        )
        assertTrue("缺少逐 cell 抖动", "float curtainCellDither(" in field)
        assertTrue(
            "静止层与粒子层必须共用同一个逐 cell 释放时刻",
            "float curtainReleaseTime(" in field
        )
        assertFalse("释放场不得恢复单向扫描项", "uSweepTime" in field)
        assertFalse("释放场不得恢复弓形", "uArcBow" in field)
        assertFalse("释放场不得恢复边缘距离侵蚀", "edgeDepth" in field)
        assertFalse("释放场不得恢复表层纤维预脱落", "curtainEdgeSheddingData" in field)
    }

    @Test
    fun `释放场常量必须锚定在复测数值上`() {
        val field = extractShader(rendererSource(), "DISMISS_FIELD_GLSL")

        assertEquals(0.64f, shaderFloatConstant(field, "CURTAIN_RELEASE_END"), 1.0e-6f)
        assertEquals(0.24f, shaderFloatConstant(field, "CURTAIN_DITHER"), 1.0e-6f)
        assertEquals(0.45f, shaderFloatConstant(field, "CURTAIN_DITHER_LOW"), 1.0e-6f)
        assertEquals(1.50f, shaderFloatConstant(field, "CURTAIN_DITHER_HIGH"), 1.0e-6f)
        assertEquals(1.6f, shaderFloatConstant(field, "CURTAIN_DITHER_SCALE"), 1.0e-6f)
        assertEquals(0.55f, shaderFloatConstant(field, "CURTAIN_DITHER_BASE"), 1.0e-6f)
        assertEquals(0.70f, shaderFloatConstant(field, "CURTAIN_NUCLEUS_ADVANCE"), 1.0e-6f)
        assertEquals(0.60f, shaderFloatConstant(field, "CURTAIN_RATE_EXPONENT"), 1.0e-6f)
        assertEquals(0.95f, shaderFloatConstant(field, "CURTAIN_SWAY_BIG"), 1.0e-6f)
        assertEquals(0.22f, shaderFloatConstant(field, "CURTAIN_SWAY_MID"), 1.0e-6f)
        assertEquals(1.70f, shaderFloatConstant(field, "CURTAIN_SWAY_SCALE"), 1.0e-6f)
        assertEquals(0.34f, shaderFloatConstant(field, "CURTAIN_SWAY_ROOM"), 1.0e-6f)
        assertEquals(0.16f, shaderFloatConstant(field, "CURTAIN_SWAY_DRIFT"), 1.0e-6f)
        assertEquals(0.26f, shaderFloatConstant(field, "CURTAIN_CENTRE_LIFT"), 1.0e-6f)
        assertEquals(1.30f, shaderFloatConstant(field, "CURTAIN_FBM_SCALE"), 1.0e-6f)
        assertEquals(0.20f, shaderFloatConstant(field, "CURTAIN_CORNER_REACH"), 1.0e-6f)
        assertEquals(0.55f, shaderFloatConstant(field, "CURTAIN_CORNER_SLOPE"), 1.0e-6f)
        assertEquals(0.20f, shaderFloatConstant(field, "CURTAIN_CORNER_CORE"), 1.0e-6f)
        assertEquals(0.80f, shaderFloatConstant(field, "CURTAIN_CORNER_STAGGER"), 1.0e-6f)
    }

    @Test
    fun `静止层必须逐格硬切而不是alpha渐隐`() {
        val still = extractShader(rendererSource(), "DISMISS_STILL_FRAGMENT_SHADER")

        assertTrue("完整表面必须整格丢弃", "discard;" in still)
        assertTrue(
            "丢弃判据必须直接比较本 cell 的释放时刻",
            "float releaseAt = curtainReleaseTime(cell);" in still &&
                "ivec2 cell = curtainCellOf(vUv);" in still &&
                "uTime >= releaseAt" in still
        )
        assertTrue(
            "未释放区域的唯一亮度调制必须来自静止层网格的真实法向",
            "outColor = vec4(color.rgb * vShade * color.a, color.a);" in still
        )
        assertFalse(
            "静止层不得再做释放前的亮度压暗。用不含抖动的平滑时刻算，压暗量在" +
                "空间上连续，白色对话框上是一片糊在白底上的灰影；用本格自己的" +
                "时刻算，白底上是一片灰阶椒盐噪点。参考里根本没有亮度压暗——" +
                "那里「变暗」的观感全部来自已经走掉的散格露出深色背景",
            "CURTAIN_PREDARK" in stripComments(still)
        )
        assertFalse("静止层不得再做 alpha 渐隐", "intact" in still)
        assertFalse("静止层不得再打软孔", "softPores" in still)
        assertFalse("静止层不得再打细孔", "finePores" in still)
        assertFalse("静止层不得再做相位滤波", "phaseStep" in still)
        assertFalse("静止层不得再做局部模糊或后处理", "surfaceNoise" in still)
    }

    @Test
    fun `粒子运动必须是单方向递增风加固定随机踢`() {
        val vertex = extractShader(rendererSource(), "DISMISS_VERTEX_SHADER")
        // 湍流势在共享段里，顶点着色器里只有占位符，要单独取。
        val field = extractShader(rendererSource(), "DISMISS_FIELD_GLSL")
        val stillVertex = extractShader(rendererSource(), "DISMISS_STILL_VERTEX_SHADER")

        assertTrue("缺少共同风的位移积分", "float curtainWindIntegral(" in vertex)
        assertTrue(
            "晚出生的粒子必须直接进入当前风速，不从零起步",
            "curtainWindIntegral(uTime, windMaxPx)" in vertex &&
                "- curtainWindIntegral(birth, windMaxPx)" in vertex
        )
        assertTrue("缺少逐粒子速度倍率", "float speedScale = 1.0 + CURTAIN_SPEED_SPREAD" in vertex)
        assertTrue("缺少固定随机踢", "vec2 kick = (" in vertex)
        assertTrue("随机踢必须按年龄累积，不随时间演化", "+ kick * (age - lag)" in vertex)
        assertTrue(
            "必须使用一阶阻力松弛：参考同一帧内老粒子比新粒子快约 1.6 倍",
            "float relax = 1.0 - exp(-age / tau);" in vertex
        )
        assertTrue(
            "阻力必须按 Stokes 数逐粒子不同：响应时间正比于直径的平方。小颗粒紧跟" +
                "气流、被涡旋卷着走，大颗粒滞后、走得更直。所有粒子共用同一个 tau " +
                "和同一个湍流耦合强度时，响应完全一致——那正是「机械、不轻盈」的" +
                "来源；烟尘的轻盈感来自细尘缠绕与粗粒穿行两层同时存在",
            "float stokes = sizeVar * sizeVar;" in vertex &&
                "float tau = max(CURTAIN_DRAG_TAU * stokes, 1.0e-4);" in vertex
        )
        assertTrue(
            "湍流耦合必须按 1/(1+St) 随粒径下降",
            "age / (1.0 + stokes)" in vertex
        )
        assertTrue(
            "湍流势必须沿飞行轴拉长：各向同性的势给出圆形的 churn，拉长之后涡是" +
                "细长的，粒子被卷成缕而不是搅成团——这是烟雾里涡丝那一层的廉价近似",
            "vec2 q = vec2(p.x * CURTAIN_SWIRL_STRETCH, p.y);" in field
        )
        assertEquals(0.42f, shaderFloatConstant(field, "CURTAIN_SWIRL_STRETCH"), 1.0e-6f)
        assertFalse(
            "不得再有正比于离轴距离的横向发散项。它当初的依据「参考沿风/横风伸展比" +
                "由 1.35 降到 0.62，说明横向增长快于纵向」是错的：分开量两个分量后，" +
                "参考的横向 sd 是 0.273 -> 0.261（斜率 -0.036），沿轴 sd 是 " +
                "0.322 -> 0.166。比值下降是纵向在收缩，粒群整体被搬走而不从中心炸开。" +
                "该项曾使膨胀/平移之比达到 0.24（参考 -0.04），并把粒子云的前沿抹平",
            "lateral * CURTAIN_SPREAD_RATE" in stripComments(vertex)
        )
        assertTrue(
            "非均匀运动必须是空间相干的。此前唯一的非均匀项是逐粒子随机踢，那是" +
                "空间上不相关的白噪声——相邻两颗粒子的偏移毫无关系，所以只能像沙，" +
                "不可能像纱。人眼判定「这是一块布」靠的正是相邻粒子一起动。" +
                "curl noise 对标量势取旋度，二维下恒为无散度，粒子不会在某处堆积" +
                "或稀释，只有平滑的涡旋（Bridson, SIGGRAPH 2007）",
            "vec2 swirl = curtainSwirl(material, birth + age * 0.5" in vertex
        )
        assertTrue(
            "纱面必须有真实的高度场：柔性薄片在均匀流中的颤振本征形态是「从前缘" +
                "出发、向后缘传播且幅度递增的行波」。它是时变的，静态的折叠位移" +
                "只能给出固定条纹，给不出「飘」",
            "float height = CURTAIN_WAVE_AMPLITUDE * envelope * sin(wavePhase)" in vertex
        )
        assertTrue(
            "必须把 z 真的算出来再做透视投影：透视同时给出视差、前缩造成的密度" +
                "起伏与粒径随深度的变化，三者一起出现眼睛才判定这是有厚度的纱。" +
                "密度起伏因此是投影雅可比的必然结果，不需要单独的折叠压缩位移项",
            "vec2 posPx = centrePx + (flatPx - centrePx) * perspective;" in vertex
        )
        assertTrue(
            "粒径必须随透视变化，否则近处的粒子不会变大，视差就不成立",
            "* sizeVar * perspective," in vertex
        )
        assertTrue(
            "风必须作用在面元上，而不是对所有粒子一视同仁地平移。curtainWind 只是" +
                "一条标量速度曲线乘上固定方向，对每颗粒子完全相同；若 height 又只" +
                "进 perspective 与 normal、从不进入位置，纱面的形状就纯粹是装饰——" +
                "它改变东西看起来怎样，不改变任何东西往哪走。风与形状零耦合，" +
                "怎么调都不像布",
            "float facing = dot(normalize(windVec), trueNormal);" in vertex
        )
        assertTrue(
            "法向力必须同时进入高度与位置：z 分量把已经鼓起的地方推得更鼓（颤振" +
                "失稳的一步显式迭代），xy 分量真正搬运材料。凸起、折叠、翻滚是" +
                "同一个反馈的三种表现，不是三种要分别捏出来的效果",
            "height += pressure.z / max(shortSide, 1.0);" in vertex &&
                "+ pressure.xy;" in vertex
        )
        assertTrue(
            "受力必须用真实的几何法向，明暗才用夸张过的那个。高度以短边为单位、" +
                "材料坐标以半对角线为单位，真实坡度要做这个换算；" +
                "CURTAIN_NORMAL_GAIN 是为了让明暗带够强调出来的夸张系数，拿它当" +
                "物理法向会把力的方向整个带偏——法向变成几乎躺在平面内，压力于是" +
                "几乎不进 z，鼓不起来",
            "vec3 trueNormal = normalize(vec3(-heightGrad * toSlope, 1.0));" in vertex
        )
        assertTrue(
            "风必须有离屏分量：纱面初始几乎正对观察者，若风完全躺在屏幕平面内，" +
                "(U·n) 恒等于 0，一点力都吃不到，也就永远鼓不起来",
            "vec3 windVec = vec3(direction * windSpeed, -CURTAIN_WIND_TILT * windSpeed);" in vertex
        )
        assertEquals(0.75f, shaderFloatConstant(vertex, "CURTAIN_WIND_TILT"), 1.0e-6f)
        assertEquals(0.16f, shaderFloatConstant(vertex, "CURTAIN_PRESS"), 1.0e-6f)
        assertEquals(4.5f, shaderFloatConstant(vertex, "CURTAIN_WAVE_NUMBER"), 1.0e-6f)
        // 不撕裂的判据：透视对材料坐标的梯度必须小于 1。相机 3.0 倍短边、波数 4.5
        // 时约 0.3；相机 1.1、波数 7.0 时是 2.27，粒子云会被生生撕出一条断层。
        assertTrue(
            "相机不能太近、波长不能太短，否则相邻材料点的透视因子差得太多，薄片被撕裂",
            shaderFloatConstant(field, "CURTAIN_CAMERA_DIST")
                / shaderFloatConstant(vertex, "CURTAIN_WAVE_NUMBER") > 0.55f
        )
        assertTrue(
            "明暗只能是很轻的起伏，且必须用真实几何法向：纱的折叠是轻微的，不存在" +
                "被转过去的背光面。此前那套强明暗（下限 0.18、法向增益 7.8）是为了" +
                "把「云内部局部亮度的变异系数」凑到参考的 0.24-0.31，而那个目标测错" +
                "了——参考**源图**的局部亮度变异系数是 0.537，远高于云内部的 " +
                "0.24-0.31，说明那些「明暗带」是照片本身的明暗被粒子带着走，不是" +
                "光照。我们的对话框源图只有 0.102，这个指标本就追不得",
            "float shade = curtainShade(trueNormal);" in vertex &&
                "clamp(dot(normal, normalize(CURTAIN_LIGHT)), 0.0, 1.0)" in field
        )
        assertTrue(
            "静止层必须是可形变的网格，不能再是一张平的四边形——「前沿附近变暗」" +
                "必须由真实几何造成，否则就是往白底上糊一层灰",
            "vec2 curtainGridUv(int id)" in stillVertex &&
                "centrePx + (flatPx - centrePx) * perspective" in stillVertex &&
                "CURTAIN_LIFT * curtainLift(lead)" in stillVertex
        )
        assertTrue(
            "静止层的明暗必须取观察方向的朗伯项（法向的 z 分量），不能用固定的" +
                "斜向光。斜向光下卷起的法向朝哪边完全取决于本次扫描方向：附件" +
                "对话框是 242°，卷起后法向恰好转向光源、算出来比平面还亮，在白卡" +
                "上被截到白，一点形体都看不到；换个方向的对话框又会突然变暗",
            "vShade = mix(CURTAIN_STILL_SHADE_MIN, 1.0, clamp(normal.z, 0.0, 1.0));"
                in stillVertex
        )
        assertTrue(
            "倾斜量不得写成 lift / LIFT_LEAD：那样「变暗有多深」与「变暗铺多宽」" +
                "被同一个常数绑死，想把暗带铺宽就必然同时把它变浅。而暗带必须比" +
                "抖动的侵蚀区宽得多——抖动会把前沿之前一大片格子提前打成散孔，" +
                "暗带若比它窄，整条带子落在的正是那片已被打穿的地方，屏幕上一点" +
                "变暗都看不到（洋红底单画静止层可直接看到卡面全白）",
            "float slope = -CURTAIN_LIFT_TILT * curtainLift(lead)" in stillVertex
        )
        assertTrue(
            "卷起必须有时间包络：叠层在真窗口之上接管，第 0 帧必须逐像素相同，" +
                "而起火角的相位恰好是 0，不加包络时它在 t=0 就已是满幅卷起",
            "smoothstep(0.0, CURTAIN_LIFT_RAMP, uTime)" in stillVertex
        )
        assertFalse(
            "不得再有为了凑明暗强度而夸张的法向增益",
            "CURTAIN_NORMAL_GAIN" in stripComments(vertex)
        )
        assertFalse(
            "静态的折叠压缩位移必须已经删除：它不随时间演化，只能给出固定的条纹；" +
                "密度起伏改由高度场的透视投影给出",
            "CURTAIN_FOLD_AMPLITUDE" in stripComments(vertex)
        )
        assertEquals(0.10f, shaderFloatConstant(vertex, "CURTAIN_SWIRL_AMPLITUDE"), 1.0e-6f)
        assertEquals(0.075f, shaderFloatConstant(vertex, "CURTAIN_WAVE_AMPLITUDE"), 1.0e-6f)
        assertEquals(3.0f, shaderFloatConstant(field, "CURTAIN_CAMERA_DIST"), 1.0e-6f)
        assertEquals(1.14f, shaderFloatConstant(field, "CURTAIN_SHADE_MAX"), 1.0e-6f)
        // 亮度下限必须让背光的粒子仍明显亮于背景，否则整条背光带在深色 UI 上
        // 凭空消失，看上去像几何断层。白色粒子经 boost 后约 212·f，背景约 40，
        // 要两倍背景则 f >= 0.38。
        assertTrue(
            "背光面的亮度下限过低会让整条带消失，看上去像断层",
            shaderFloatConstant(field, "CURTAIN_SHADE_MIN") >= 0.38f
        )

        assertFalse("消散不得恢复旋涡场", "uSwirl" in vertex)
        assertFalse("消散不得恢复随时间演化的流场", "uFlowEvolve" in vertex)
        assertFalse("消散不得恢复漏斗收拢", "uPinch" in vertex)
        assertFalse("消散不得恢复逐粒子湍流抖动", "uTurbPx" in vertex)
        assertFalse("消散不得恢复弓形揭开", "uArcBow" in vertex)
        assertFalse("消散不得恢复单向扫描项", "uSweepTime" in vertex)
        assertFalse("消散不得恢复 PBD 薄面", "clothSurface" in vertex)
        assertFalse("消散不得恢复前后层伪深度", "vPseudoDepth" in vertex)
    }

    @Test
    fun `寿命分布必须复现实测的占位率衰减律`() {
        val vertex = extractShader(rendererSource(), "DISMISS_VERTEX_SHADER")

        // life = LIFE_MAX * (1 - sqrt(u)) 的生存函数恰为 (1 - age/LIFE_MAX)^2，
        // 与参考实测的占位率逐点吻合；换成别的分布就复现不出浓淡对比。
        val lifeFormula = vertex
            .substringAfter("float life = ")
            .substringBefore(";")
            .replace(Regex("\\s+"), " ")
        assertEquals(
            "缺少与实测衰减律等价的寿命分布",
            "lifeMax * (1.0 - sqrt(curtainRandom(id, 0x9e3779b9u)))",
            lifeFormula
        )
        assertTrue("寿命必须钳制在动画结束前", "life = min(life, 1.0 - birth);" in vertex)
        assertEquals(0.70f, shaderFloatConstant(vertex, "CURTAIN_LIFE_MAX"), 1.0e-6f)
        assertEquals(0.055f, shaderFloatConstant(vertex, "CURTAIN_DRAG_TAU"), 1.0e-6f)
        assertEquals(0.88f, shaderFloatConstant(vertex, "CURTAIN_LIFE_TAIL"), 1.0e-6f)
        assertEquals(0.06f, shaderFloatConstant(vertex, "CURTAIN_TAIL_SHARE"), 1.0e-6f)
        assertTrue(
            "少量尾粒子必须取更长寿命，对应参考末段的稀疏尘埃",
            "step(1.0 - CURTAIN_TAIL_SHARE, curtainRandom(id, 0xa136aaadu))" in vertex
        )
        assertEquals(0.20f, shaderFloatConstant(vertex, "CURTAIN_WIND_KNEE"), 1.0e-6f)
        assertEquals(0.07f, shaderFloatConstant(vertex, "CURTAIN_KICK_SD"), 1.0e-6f)
        assertEquals(0.40f, shaderFloatConstant(vertex, "CURTAIN_WIND_RATIO"), 1.0e-6f)
        assertFalse("不得再叠加全局收尾包络", "globalEnvelope" in vertex)
    }

    @Test
    fun `粒径必须恒定且浓淡只由存活数量产生`() {
        val vertex = extractShader(rendererSource(), "DISMISS_VERTEX_SHADER")
        val pointSizeFormula = vertex
            .substringAfter("gl_PointSize = clamp(")
            .substringBefore(");")

        assertTrue("粒径必须以网格步长为基准", "uCellPx * CURTAIN_POINT_RATIO" in pointSizeFormula)
        assertTrue(
            "同一批粒子必须有大有小：参考稀疏区的等效直径 P90/P10 约 2.7，而全部" +
                "粒子同径时屏幕上密密麻麻一片、没有层次",
            "sizeVar" in pointSizeFormula
        )
        // 「粒径不得随年龄变化」这条契约已被前沿的实际观感推翻，见下。
        assertFalse("粒径仍不得随寿命变化", "life" in pointSizeFormula)
        assertFalse("粒径不得被折叠或锋线放大", "front" in pointSizeFormula)
        assertTrue(
            "新生粒子必须更大。此前立过「粒径不得随年龄变化，浓淡只由存活数量产生」" +
                "的契约，被前沿的实际观感推翻了：一个刚释放的格子若没被自己的粒子" +
                "盖住就露出暗背景，前沿于是是一片粗黑的洞，而参考那里是细密明亮的" +
                "尘——同内容放大对照可以直接看到。只放大新生的那一批，全局浓淡不变",
            "sizeVar *= mix(CURTAIN_FRESH_SIZE, 1.0, smoothstep(0.0, 0.18, age));" in vertex
        )
        assertEquals(1.28f, shaderFloatConstant(vertex, "CURTAIN_FRESH_SIZE"), 1.0e-6f)
        assertEquals(1.02f, shaderFloatConstant(vertex, "CURTAIN_POINT_RATIO"), 1.0e-6f)
        assertEquals(0.62f, shaderFloatConstant(vertex, "CURTAIN_SIZE_MIN"), 1.0e-6f)
        assertEquals(1.35f, shaderFloatConstant(vertex, "CURTAIN_SIZE_MAX"), 1.0e-6f)

        val renderer = rendererSource()
        val replicas = Regex("private const val DISMISS_REPLICAS = (\\d+)").find(renderer)
        assertNotNull("找不到消散副本数", replicas)
        assertEquals(
            "参考是每 cell 一个粒子；叠副本会把颗粒糊成雾",
            1,
            checkNotNull(replicas).groupValues[1].toInt()
        )
        val columns = Regex("private const val DISMISS_TARGET_COLUMNS = ([0-9]+)").find(renderer)
        assertNotNull("找不到消散网格列数", columns)
        assertEquals(
            "网格列数必须与桌面 canonical 一致；桌面从这里读取，不得各自维护。" +
                "512 列时稀疏区的粒子数是参考的 3-4 倍，真机上密密麻麻一片",
            256,
            checkNotNull(columns).groupValues[1].toInt()
        )
    }

    @Test
    fun `渲染器不得再预解算薄面或释放纹理`() {
        val renderer = rendererSource()

        assertFalse("PBD 薄面必须已经删除", "ParticleDismissClothModel" in renderer)
        assertFalse("预解算释放场必须已经删除", "ParticleDismissReleaseField" in renderer)
        assertFalse("不得再上传薄面轨迹纹理", "uClothFrames" in renderer)
        assertFalse("不得再上传释放场纹理", "uReleaseProfile" in renderer)
    }

    private fun rendererSource(): String = readProjectFile(
        "app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/" +
            "ParticleDismissRenderer.kt"
    ).readText(Charsets.UTF_8)

    private fun extractShader(source: String, name: String): String {
        val marker = "private val $name = \"\"\""
        val start = source.indexOf(marker)
        check(start >= 0) { "找不到 Shader：$name" }
        val bodyStart = start + marker.length
        val end = source.indexOf("\"\"\".trimIndent()", bodyStart)
        check(end >= 0) { "Shader 未正确闭合：$name" }
        return source.substring(bodyStart, end).trimIndent()
    }

    /**
     * 去掉 GLSL 行注释后再做「不得出现」类断言。解释某个构造为什么被废弃的
     * 注释里必然写着那个构造本身，字面搜索会把正确的代码判成失败。
     */
    private fun stripComments(source: String): String =
        source.lineSequence().joinToString("\n") { it.substringBefore("//") }

    private fun shaderFloatConstant(source: String, name: String): Float {
        val match = Regex("const float $name = ([0-9.]+);").find(source)
        assertNotNull("找不到 Shader 常量：$name", match)
        return checkNotNull(match).groupValues[1].toFloat()
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
