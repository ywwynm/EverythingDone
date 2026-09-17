package com.ywwynm.everythingdone.views.particledismiss

import com.ywwynm.everythingdone.utils.AnimatorDurationScale
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第二批新增逻辑的 JVM 验证：时间基准、呈现判定、手势预读、快照单拷贝的字节一致性。
 *
 * 这些测试全部不碰 GL——真机那一轮只跑一次合并验证，所以能在本地定死的都定死在这里。
 */
class ParticleBatchTwoTest {

    // ---- A 时间基准统一 ----

    @Test fun `时间基准只做下限保护，不再做任何反向换算`() {
        assertEquals(1f, AnimatorDurationScale.sanitize(1f), 0f)
        assertEquals(5f, AnimatorDurationScale.sanitize(5f), 0f)
        assertEquals(.5f, AnimatorDurationScale.sanitize(.5f), 0f)
        // 系统把动画整体关掉时缩放是 0；它会被当除数用，给一个下限。
        assertEquals(AnimatorDurationScale.MINIMUM, AnimatorDurationScale.sanitize(0f), 0f)
        assertEquals(AnimatorDurationScale.MINIMUM, AnimatorDurationScale.sanitize(-3f), 0f)
        assertEquals(AnimatorDurationScale.MINIMUM, AnimatorDurationScale.sanitize(Float.NaN), 0f)
    }

    @Test fun `涟漪结算超时按涟漪真实寿命乘唯一基准`() {
        // 标称 60 + 300，余量 240。
        assertEquals(600L, ParticleTouchFeedback.settleTimeoutMs(1f))
        assertEquals(2040L, ParticleTouchFeedback.settleTimeoutMs(5f))
        assertEquals(276L, ParticleTouchFeedback.settleTimeoutMs(.1f))
    }

    @Test fun `自绘涟漪与暗层交给 ValueAnimator 的是标称时长`() {
        val ripple = readProjectFile("app/src/main/java/com/ywwynm/everythingdone/views/GradientRippleDrawable.kt").readText()
        assertTrue("涟漪不得再做反向换算", "private fun scaled(nominalMs: Long): Long = nominalMs" in ripple)
        assertFalse("compensate 已随时间基准统一删除", "AnimatorDurationScale.compensate" in ripple)
        val dim = readProjectFile("app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/DialogDimLayer.kt").readText()
        assertTrue("暗层不得再做反向换算", "private fun scaled(nominalMs: Long): Long = nominalMs" in dim)
        val scale = readProjectFile("app/src/main/java/com/ywwynm/everythingdone/utils/AnimatorDurationScale.kt").readText()
        assertTrue("API 33+ 必须走公开的 getDurationScale", "ValueAnimator.getDurationScale()" in scale)
        assertTrue("必须跟随系统设置的变化", "registerDurationScaleChangeListener" in scale)
        assertTrue("以下要回退 Settings.Global", "ANIMATOR_DURATION_SCALE" in scale)
        assertFalse("不得再有反射校准", "getDeclaredMethod(\"getDurationScale\")" in scale)
    }

    // ---- 呈现判定（首帧交接口径） ----

    private val interval = ParticleClock.INTERVAL_NANOS

    @Test fun `有帧时间戳时 PENDING 必须继续等且保序`() {
        assertFalse(ParticleWindowSurface.shouldRelease(0L, ParticleFrameTimestamps.PENDING))
        assertFalse(ParticleWindowSurface.shouldRelease(interval * 3, ParticleFrameTimestamps.PENDING))
        // 拿到真实呈现时刻、或驱动明确说这一项无效，都放行。
        assertTrue(ParticleWindowSurface.shouldRelease(0L, 123_456_789L))
        assertTrue(ParticleWindowSurface.shouldRelease(0L, ParticleFrameTimestamps.INVALID))
        assertTrue(ParticleWindowSurface.shouldRelease(0L, ParticleFrameTimestamps.FAILED))
    }

    @Test fun `没有帧时间戳时按缓冲入队加下一次 vsync 的保守判定`() {
        assertFalse(ParticleWindowSurface.shouldRelease(0L, null))
        assertFalse(ParticleWindowSurface.shouldRelease(interval - 1, null))
        assertTrue(ParticleWindowSurface.shouldRelease(interval, null))
    }

    @Test fun `一直 PENDING 的帧不能把交接卡死`() {
        assertTrue(ParticleWindowSurface.shouldRelease(
            ParticleWindowSurface.PRESENT_TIMEOUT_NANOS + 1, ParticleFrameTimestamps.PENDING))
    }

    // ---- 3.1 左滑预读 ----

    /**
     * 具体档数由逐帧成本专项第二批按实测成本重定（见 `ParticlePerFrameBatchTwoTest`），
     * 这里只守住不随批次变的那两条：至少备一档、越快备得越多。
     */
    @Test fun `按速度决定预读档数`() {
        assertEquals(1, ParticleSession.gestureLookahead(0f))
        val speeds = listOf(0f, .5f, 1f, 1.01f, 2f, 4f, 9f)
        for ((slow, fast) in speeds.zipWithNext()) {
            assertTrue("$fast 档/拍不该比 $slow 备得少",
                ParticleSession.gestureLookahead(fast) >= ParticleSession.gestureLookahead(slow))
        }
    }

    @Test fun `预读目标只增不减且不超过总档数`() {
        // 当前已备 4 档，画面需要第 6 档，慢拖预读 1 档。
        assertEquals(7, ParticleSession.gestureGoal(current = 4, need = 6, lookahead = 1, samples = 60))
        // 已经备得更远时不回退。
        assertEquals(20, ParticleSession.gestureGoal(current = 20, need = 6, lookahead = 2, samples = 60))
        // 不越过总档数。
        assertEquals(60, ParticleSession.gestureGoal(current = 58, need = 60, lookahead = 2, samples = 60))
    }

    @Test fun `按下阶段的预备档数覆盖识别左滑时的常见位移`() {
        // 触发阈值约 24 dp，卡片宽度 360 dp 量级 → 进度约 0.067 → 档位约 4。
        val samples = ParticleReversePlan.MAX_SAMPLES
        val position = (24f / 360f) * samples
        assertTrue("预备 ${ParticleSession.GESTURE_PREROLL} 档要能覆盖 $position",
            kotlin.math.ceil(position).toInt() <= ParticleSession.GESTURE_PREROLL)
        // 停住时每拍多备的档数由逐帧成本专项第二批提到 6（预读不再阻塞 CPU），下限留在这里。
        assertTrue(ParticleSession.GESTURE_IDLE_LOOKAHEAD >= 2)
    }

    // ---- 3.2 快照单拷贝 ----

    @Test fun `copyPixelsToBuffer 的字节与 packColors 的结果逐字节相同`() {
        val random = Random(9018)
        for (count in listOf(1, 7, 4096)) {
            val argb = IntArray(count) { random.nextInt() }
            // Bitmap.copyPixelsToBuffer 对 ARGB_8888 给出的内存字节就是 R,G,B,A。
            val expected = ByteArray(count * 4)
            for (i in 0 until count) {
                val c = argb[i]
                expected[i * 4] = ((c ushr 16) and 255).toByte()      // R
                expected[i * 4 + 1] = ((c ushr 8) and 255).toByte()   // G
                expected[i * 4 + 2] = (c and 255).toByte()            // B
                expected[i * 4 + 3] = ((c ushr 24) and 255).toByte()  // A
            }
            val packed = ParticleMicroflakePreparation.packColors(argb)
            val actual = ByteArray(packed.remaining())
            packed.duplicate().get(actual)
            assertArrayEquals("count=$count", expected, actual)
        }
    }

    @Test fun `从上传字节反解出的 ARGB 与 getPixels 的结果一致`() {
        val random = Random(4712)
        for (count in listOf(1, 7, 4096)) {
            val argb = IntArray(count) { random.nextInt() }
            val rgba = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (c in argb) {
                rgba.put(((c ushr 16) and 255).toByte()); rgba.put(((c ushr 8) and 255).toByte())
                rgba.put((c and 255).toByte()); rgba.put(((c ushr 24) and 255).toByte())
            }
            rgba.rewind()
            assertArrayEquals("count=$count", argb, ParticleMicroflakePreparation.unpackColors(rgba, count))
            // 再打包回去必须还是同一批字节：材料模型与纹理上传读的是同一份数据。
            val repacked = ParticleMicroflakePreparation.packColors(
                ParticleMicroflakePreparation.unpackColors(rgba, count))
            val a = ByteArray(count * 4); rgba.duplicate().get(a)
            val b = ByteArray(count * 4); repacked.duplicate().get(b)
            assertArrayEquals(a, b)
        }
    }

    @Test fun `材料线程只从位图取一次像素`() {
        val source = readProjectFile(
            "app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/ParticleMicroflakePreparation.kt"
        ).readText()
        val task = source.substringAfter("private val task = FutureTask {").substringBefore("}.also { executor.execute(it) }")
        assertTrue("主路径必须是 copyPixelsToBuffer", "copyPixels(bitmap)" in task)
        // getPixels 只在行距异常的回退分支里出现一次。
        assertEquals(1, Regex("getPixels\\(").findAll(task).count())
    }

    // ---- 结构性约束 ----

    @Test fun `粒子叠加层不再使用 TextureView，也不再投窗口刷新率`() {
        val overlay = readProjectFile(
            "app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/ParticleDismissOverlay.kt").readText()
        // 只点名真实用法，不做字面搜索——解释性注释里提到旧实现是正常的。
        assertFalse("动画层不得再自带 TextureView", "TextureView(" in overlay)
        assertFalse("动画层不得再自带 TextureView", "import android.view.TextureView" in overlay)
        val controller = readProjectFile(
            "app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/ParticleDismissController.kt").readText()
        assertFalse("窗口刷新率投票已删除", "ParticleWindowFrameRate" in controller)
        assertFalse("不得再改窗口的 preferredRefreshRate", "preferredRefreshRate" in controller)
        val window = readProjectFile(
            "app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/ParticleWindowSurface.kt").readText()
        assertTrue("粒子 Surface 必须投一次无缝 60 Hz", "CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS" in window)
        assertTrue("表面必须置顶且半透明", "setZOrderOnTop(true)" in window)
        assertTrue("表面必须置顶且半透明", "PixelFormat.TRANSLUCENT" in window)
    }

    @Test fun `播放路径不再每帧排空管线`() {
        val window = readProjectFile(
            "app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/ParticleWindowSurface.kt").readText()
        assertFalse("每帧 glFinish 已由栅栏取代", "glFinish(" in window)
        assertTrue("必须用栅栏限制在途帧数", "glFenceSync" in window)
        assertTrue("下一拍开画前等上一帧的栅栏", "glClientWaitSync" in window)
        val renderer = readProjectFile(
            "app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/ParticleMicroflakeRenderer.kt").readText()
        // 渲染器里只剩两处 glFinish：读回状态（诊断）与开着探针时的预计算墙钟口径。
        assertEquals(2, Regex("GLES30\\.glFinish\\(\\)").findAll(renderer).count())
    }

    @Test fun `共享上下文里换会话必须整套重设 uniform`() {
        val renderer = readProjectFile(
            "app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/ParticleMicroflakeRenderer.kt").readText()
        assertTrue("必须有按会话切换的 uniform 重设", "private fun activate()" in renderer)
        assertTrue("uniform 重设必须覆盖压力网格", "peelPressure.applyUniforms()" in renderer)
        assertTrue("uniform 重设必须覆盖历史程序的 count", "reverseHistory?.applyUniforms()" in renderer)
        for (entry in listOf("advance", "render", "drawReverseFrame", "drawGestureFrame")) {
            val body = renderer.substringAfter("fun $entry(").substringAfter("{")
            assertTrue("$entry 入口必须先 activate", body.trimStart().startsWith("activate()"))
        }
    }

    @Test fun `合成路径把默认帧缓冲的清空交给宿主窗口`() {
        val renderer = readProjectFile(
            "app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/ParticleMicroflakeRenderer.kt").readText()
        // 单会话路径（离屏对照、预热）自己清；合成路径由窗口每拍清一次。
        assertTrue("clearTarget 必须可控", "clearTarget: Boolean = true" in renderer)
        assertTrue("叠画必须开预乘 alpha 混合", "GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA" in renderer)
        val session = readProjectFile(
            "app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/ParticleSession.kt").readText()
        assertFalse("会话画进共享 Surface 时不得自己清空",
            Regex("clearTarget = true").containsMatchIn(session))
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
