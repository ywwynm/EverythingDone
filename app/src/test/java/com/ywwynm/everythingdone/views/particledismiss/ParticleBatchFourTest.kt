package com.ywwynm.everythingdone.views.particledismiss

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第四批：①弹窗出现也跳过尾段空白、②创建页轨迹提前预算的缓存键与命中判定、
 * ④本地材料库的工作线程跟随调用线程优先级。
 *
 * ③（三星涟漪）是采集 + 无外观改动的修正，没有可以在 JVM 上断言的纯逻辑，见结果文档。
 */
class ParticleBatchFourTest {

    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "shared/particle-dismiss/rules.properties").isFile }

    private fun source(relative: String) = File(root, relative).readText()

    private fun main(name: String) =
        source("app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/$name")

    // ---- ①：弹窗、页内面板、新卡片的出现也跳过尾段空白 ----

    @Test fun `出现路径默认启用跳过尾段，创建页与弹窗各有开关`() {
        val controller = main("ParticleDismissController.kt")
        assertTrue("appearContent 的默认值必须是弹窗那一档开关",
            "skipEmptyTail: Boolean = ParticlePerfTuning.dialogAppearanceSkipEmptyTail" in controller)
        val transition = main("ThingCreationTransition.kt")
        assertTrue("创建页必须显式用自己那一档开关",
            "skipEmptyTail = ParticlePerfTuning.creationAppearanceSkipEmptyTail" in transition)
        assertTrue("两档默认都开",
            ParticlePerfTuning.dialogAppearanceSkipEmptyTail &&
                ParticlePerfTuning.creationAppearanceSkipEmptyTail)
    }

    @Test fun `会话只看 spec 的结论，开关已经在上一层解析完`() {
        val session = main("ParticleSession.kt")
        val body = session.substringAfter("private fun beginPrecompute()").substringBefore("private fun precomputeStep")
        assertTrue("会话直接用 spec.skipEmptyTail", "val skip = spec.skipEmptyTail" in body)
        assertFalse("不得在会话里再与创建页那一档开关相与",
            "ParticlePerfTuning.creationAppearanceSkipEmptyTail" in body)
        assertTrue("门槛是两条路径共用的那一个",
            "ParticlePerfTuning.appearanceAliveRatio" in body)
    }

    @Test fun `弹窗与创建页共用同一个门槛，起点判据一字不差`() {
        assertEquals(ParticleReversePlan.APPEARANCE_ALIVE_RATIO, ParticlePerfTuning.appearanceAliveRatio, 0f)
        // 同一条存活曲线上，起点只由门槛决定，与是哪一条出现路径无关。
        val counts = intArrayOf(0, 8, 40, 96, 130, 104, 60, 26, 9, 2, 0)
        val total = 200
        assertEquals(ParticleReversePlan.startFrameOf(counts, total, .10f),
            ParticleReversePlan.startFrameOf(counts, total, .10f))
        // 10% = 20 片：最晚仍 ≥20 的是第 7 个采样（26 片），第 8 个只有 9 片。
        assertEquals(7, ParticleReversePlan.startFrameOf(counts, total, .10f))
        // 关掉（门槛 0）时整段照播。
        assertEquals(counts.size - 1, ParticleReversePlan.startFrameOf(counts, total, 0f))
    }

    @Test fun `跳掉的尾段只是开头那几帧，保留段的时间值一点没变`() {
        val samples = 36
        val values = FloatArray(12 * 4)
        for (i in 0 until 4) {
            values[i * 12 + 2] = .05f + i * .08f   // birth
            values[i * 12 + 6] = .45f              // life
        }
        val plan = ParticleReversePlan(values, samples)
        val start = ParticleReversePlan.appearanceStartFrame(values, samples, .10f)
        assertTrue("起点必须落在总采样数之内", start in 1..samples)
        for (frame in 0..start) assertEquals(frame / samples.toFloat(), plan.time(frame), 0f)
        assertEquals(start, plan.frame(0f, start))
        assertEquals(0, plan.frame(1f, start))
    }

    // ---- ②：缓存键与命中判定 ----

    @Test fun `缓存键相同才可能命中，不要求像素相等`() {
        val key = ParticleCreationKey("bg=A|folder=0|win=1280x2856")
        assertNull("同一份环境、同一个尺寸与原点：命中",
            ParticleCreationMatch.reject(key, key, 1280, 2856, 1280, 2856, 0f, 0f, 0f, 0f))
    }

    @Test fun `环境里任何一项变化都会未命中，并指出是哪一项`() {
        val before = ParticleCreationKey("bg=A|folder=0|win=1280x2856|night=16")
        val after = ParticleCreationKey("bg=B|folder=0|win=1280x2856|night=32")
        val reason = ParticleCreationMatch.reject(before, after, 1280, 2856, 1280, 2856, 0f, 0f, 0f, 0f)
        assertNotNull(reason)
        assertTrue("原因里要点名背景", reason!!.contains("bg"))
        assertTrue("原因里要点名深浅色", reason.contains("night"))
        assertFalse("没变的项不该出现", reason.contains("folder"))
    }

    @Test fun `尺寸或原点对不上一律未命中`() {
        val key = ParticleCreationKey("bg=A")
        assertNotNull("旋转后快照尺寸变了",
            ParticleCreationMatch.reject(key, key, 1280, 2856, 2856, 1280, 0f, 0f, 0f, 0f))
        assertNotNull("原点变了（系统栏/多窗口）",
            ParticleCreationMatch.reject(key, key, 1280, 2856, 1280, 2856, 0f, 0f, 0f, 12f))
        assertNotNull("本次没带键",
            ParticleCreationMatch.reject(key, null, 1280, 2856, 1280, 2856, 0f, 0f, 0f, 0f))
    }

    @Test fun `缓存键覆盖调研列出的全部首帧输入`() {
        val prewarm = main("ParticleCreationPrewarm.kt")
        val fingerprint = prewarm.substringAfter("fun fingerprint(").substringBefore("private fun refreshRate")
        for (item in listOf("bg=", "folder=", "night=", "loc=", "font=", "dpi=", "win=", "ori=",
            "multi=", "bars=", "ime=", "mode=", "scale=", "hz=", "recipe=")) {
            assertTrue("缓存键缺少 $item", item in fingerprint)
        }
        val recipe = prewarm.substringAfter("private fun recipe()").substringBefore("// ---- 诊断")
        for (switch in listOf("creationAppearanceTouch", "creationAppearanceSkipEmptyTail",
            "appearanceAliveRatio", "skipBeforeFirstBirth", "jacobiLargeTile", "historyInIntegration")) {
            assertTrue("配方里缺少 $switch", switch in recipe)
        }
    }

    @Test fun `收养失败必须退回原路径，绝不让动画失败`() {
        val session = main("ParticleSession.kt")
        val adopt = session.substringAfter("private fun adopt(").substringBefore("private fun beginPrecompute")
        assertTrue("共享资源尺寸要核", "sharedWidth" in adopt && "sharedHeight" in adopt)
        assertTrue("快照尺寸要核", "snapshotWidth" in adopt && "snapshotHeight" in adopt)
        assertTrue("退回时要把那份预算还回去", "ParticleCreationPrewarm.rejected" in adopt)
        assertTrue("退回时要补建材", "preparation?.requireModel()" in adopt)
        assertTrue("退回时返回 false，由调用方照常建渲染器", "return false" in adopt)
    }

    @Test fun `命中时种子沿用预算那一次，否则对照工具分不清两条路径`() {
        val controller = main("ParticleDismissController.kt")
        assertTrue("hashSeed 必须优先取预算的种子",
            "hashSeed = prepared?.seed ?:" in controller)
    }

    @Test fun `预掷的背景消费后必然重掷，两条记事绝不会同色`() {
        val app = source("app/src/main/java/com/ywwynm/everythingdone/App.kt")
        assertTrue("创建页只标记消费", "fun consumeNewThingBackground()" in app)
        assertTrue("重掷之后要清掉消费标记",
            app.substringAfter("newThingColor      = representative").substringBefore("}")
                .contains("newThingBackgroundConsumed = false"))
        val ensure = app.substringAfter("fun ensureNewThingBackground()").substringBefore("}")
        assertTrue("已消费或从未掷过都要补掷",
            "newThingBackgroundConsumed" in ensure && "newThingBackground == null" in ensure)
        // 每一个读预掷值的地方都必须先 ensure 一次。
        for (reader in listOf(
            "app/src/main/java/com/ywwynm/everythingdone/activities/ShortcutActivity.kt",
            "app/src/main/java/com/ywwynm/everythingdone/receivers/DailyCreateTodoReceiver.kt",
            "app/src/main/java/com/ywwynm/everythingdone/utils/SystemNotificationUtil.kt",
            "app/src/main/java/com/ywwynm/everythingdone/activities/DetailActivity.kt")) {
            assertTrue("$reader 读预掷值之前没有 ensure",
                "App.ensureNewThingBackground()" in source(reader))
        }
        val detail = source("app/src/main/java/com/ywwynm/everythingdone/activities/DetailActivity.kt")
        assertFalse("创建页不再当场重掷", "App.updateNewThingColor()" in detail)
    }

    @Test fun `预算只在真正空闲时跑，触摸与动画都会让它停下`() {
        val prewarm = main("ParticleCreationPrewarm.kt")
        val idle = prewarm.substringAfter("private fun beginIfIdle()").substringBefore("private class Job")
        assertTrue("要看有没有粒子在播", "ParticleGpuWork.busy" in idle)
        assertTrue("要看有没有动画层挂着", "ParticleDismissOverlay.activeIn" in idle)
        assertTrue("要看静置够了没有", "lastInteractionAt" in idle)
        assertTrue("只在粒子档位下预算", "ThingAnimationPreferences.PARTICLE" in idle)
        val step = prewarm.substringAfter("private fun step()").substringBefore("private fun retryLater")
        assertTrue("每一小段之后都要让出 GL 线程", "ParticleGl.post { step() }" in step)
        assertTrue("被打断时保留进度、稍后续算", "retryLater" in step)
        // 预算绝不能一次性把整段 compute 排进驱动队列。
        assertTrue("预算要节流", "throttled = true" in prewarm)
    }

    @Test fun `退后台与内存告警都要释放预算与预测素材`() {
        val app = source("app/src/main/java/com/ywwynm/everythingdone/App.kt")
        assertTrue("onTrimMemory 要释放", app.substringAfter("override fun onTrimMemory")
            .substringBefore("override fun onLowMemory").contains("ParticleCreationPrewarm.trimMemory()"))
        assertTrue("onLowMemory 要释放", app.substringAfter("override fun onLowMemory")
            .substringBefore("override fun onCreate").contains("ParticleCreationPrewarm.trimMemory()"))
        val prewarm = main("ParticleCreationPrewarm.kt")
        val trim = prewarm.substringAfter("fun trimMemory()").substringBefore("// ---- 缓存键")
        assertTrue("预测素材要放掉", "source = null" in trim)
        assertTrue("算好的轨迹要放掉", "ready = null" in trim)
        assertTrue("在算的那一份也要放掉", "job = null" in trim)
    }

    @Test fun `换贴图不改材料，末帧仍由真实贴图按格子拼回`() {
        val renderer = main("ParticleMicroflakeRenderer.kt")
        val replace = renderer.substringAfter("fun replaceForeground(")
            .substringBefore("val cardSize")
        assertTrue("必须已经有前景纹理", "check(foregroundTexture != 0)" in replace)
        assertTrue("只是往同一个纹理对象上重新上传", "uploadForeground(pixels)" in replace)
        assertFalse("不得动任何材料缓冲", "uploadBuffer" in replace)
        // 末帧逐像素相同这一条由设备端的 prewarmFinalFrameEqual 守住（离屏跑不了 GL）。
        val check = source("app/src/debug/java/com/ywwynm/everythingdone/views/particledismiss/ParticleTrajectoryCheck.kt")
        assertTrue("设备端要有『预测图与真实图相同 ⇒ 逐位相同』这条",
            "prewarmSameSnapshotPassed" in check)
        assertTrue("设备端要有『预测图不同 ⇒ 末帧仍等于真实页面』这条",
            "prewarmFinalFrameEqual" in check)
    }

    // ---- ④：本地材料库的工作线程跟随调用线程优先级 ----

    @Test fun `工作线程按调用线程的 nice 派发，让出语义原样保留`() {
        val native = source("app/src/main/cpp/particle_material.cpp")
        val parallel = native.substringAfter("template<class F> void parallel(int count,F function)")
            .substringBefore("template<class T> T clamp")
        assertTrue("派发前读调用线程的 nice", "currentPriority()" in parallel)
        assertTrue("每一段开工前把自己设成同一个 nice",
            "setpriority(PRIO_PROCESS,0,priority)" in parallel)
        assertFalse("不得在并行段里写死 nice 0", "setpriority(PRIO_PROCESS,0,0)" in parallel)
        // getpriority 的 −1 是合法值，必须靠 errno 区分失败。
        val current = native.substringAfter("int currentPriority()").substringBefore("class MaterialWorkers")
        assertTrue("必须先清 errno", "errno=0" in current)
        assertTrue("必须按 errno 判定失败", "errno==0" in current)
        // 1.7 的「有反馈或播放时让出」仍由**调用线程**决定，本地库不再自己判一次。
        val preparation = main("ParticleMicroflakePreparation.kt")
        assertTrue("调用线程仍按 1.7 决定优先级",
            "ParticlePerfTuning.defaultPriorityPreparation && ParticleGpuWork.busy" in preparation)
        assertFalse("本地库里不该出现 1.7 的判定", "ParticleGpuWork" in native)
    }

    @Test fun `两个 nice 能读回来对照`() {
        val native = source("app/src/main/cpp/particle_material.cpp")
        assertTrue("要能读回工作线程这一轮用的 nice", "nativeLanePriority" in native)
        assertTrue("要能读回调用线程自己的 nice", "nativeThreadPriority" in native)
        val probe = source(
            "app/src/debug/java/com/ywwynm/everythingdone/views/particledismiss/ParticleMicroflakeProbeReceiver.kt")
        assertTrue("设备端要断言两者相等", "工作线程没有跟随调用线程优先级" in probe)
        assertTrue("要在两种调用线程优先级下各跑一次",
            "THREAD_PRIORITY_DISPLAY" in probe && "THREAD_PRIORITY_DEFAULT" in probe)
    }

    // ---- ③：采集与候选修正都要能一键回退 ----

    @Test fun `涟漪期间给宿主投高帧率票，动画结束撤回`() {
        assertTrue("默认开", ParticlePerfTuning.rippleHighFrameRate)
        val ripple = source("app/src/main/java/com/ywwynm/everythingdone/views/GradientRippleDrawable.kt")
        val vote = ripple.substringAfter("private fun voteFrameRate(").substringBefore("private fun enter()")
        assertTrue("API 35 以下没有这套机制", "SDK_INT < 35" in vote)
        assertTrue("要能一键回退", "ParticlePerfTuning.rippleHighFrameRate" in vote)
        assertTrue("投的是高帧率", "REQUESTED_FRAME_RATE_CATEGORY_HIGH" in vote)
        assertTrue("撤回的是框架默认", "REQUESTED_FRAME_RATE_CATEGORY_DEFAULT" in vote)
        assertTrue("按下时投票", ripple.substringAfter("private fun enter()")
            .substringBefore("private fun exit()").contains("voteFrameRate(true)"))
        // 三条收尾路径（淡出播完、框架 jumpToCurrentState）都要撤票，否则票留在 View 上。
        assertEquals(2, Regex("voteFrameRate\\(false\\)").findAll(ripple).count())
    }

    @Test fun `采集分段与让路都默认关闭`() {
        assertFalse("atrace 分段不该留在常态里", ParticlePerfTuning.atrace)
        assertFalse("让路会让首帧更晚，默认不开", ParticlePerfTuning.yieldPrecomputeDuringFeedback)
        val renderer = main("ParticleMicroflakeRenderer.kt")
        assertTrue("让路只在涟漪可见时发生", "ParticleTouchFeedback.feedbackActive" in renderer)
        assertTrue("让路只作用于预计算（batchPressure）",
            renderer.substringAfter("val yielding = batchPressure").substringBefore("\n")
                .contains("yieldPrecomputeDuringFeedback"))
    }
}
