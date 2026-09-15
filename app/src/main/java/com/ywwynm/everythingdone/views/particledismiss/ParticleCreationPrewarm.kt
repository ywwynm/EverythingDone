package com.ywwynm.everythingdone.views.particledismiss

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.res.AssetManager
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.AnimatorDurationScale
import com.ywwynm.everythingdone.utils.ThingAnimationPreferences
import java.lang.ref.WeakReference
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * 「提前算好的创建页轨迹」的缓存键。
 *
 * 只要 [environment] 里任何一项与预算那一刻不同，这份预算就作废。键**不要求像素相等**——
 * 预测快照本来就是上一次真实创建页的首屏（背景色必然不同），命中之后末帧仍由真实贴图
 * 按格子拼回，逐像素等于真实页面；预测偏差只影响途中每一片的权重。
 *
 * 键里各项的来源见 `docs/features/particle-animation-performance/batch4-results-*.md` 第 2 节。
 */
internal data class ParticleCreationKey(
    val environment: String,
    /**
     * 去掉**新记事背景色**之后的那一份（15 项）。磁盘上的预测素材按它匹配：背景色每次新建
     * 都重掷，存一张带着某个背景色的图去要求它相等没有意义；背景偏差只影响途中每一片的
     * 出生、寿命与权重，末帧仍由当次真实快照按格子拼回（见 [ParticlePrewarmStore]）。
     */
    val persistent: String = environment
)

/** 已经算完、等着被下一次新建取走的轨迹。除 [key] 外全部只能在 GL 线程上碰。 */
internal class ParticlePreparedAppearance(
    val key: ParticleCreationKey,
    val renderer: ParticleMicroflakeRenderer,
    val samples: Int,
    val startFrame: Int,
    val playbackSeconds: Double,
    val aliveCounts: IntArray?,
    val particleCount: Int,
    val seed: Int,
    val sharedWidth: Int,
    val sharedHeight: Int,
    val snapshotWidth: Int,
    val snapshotHeight: Int,
    val precomputeMs: Double
)

/**
 * 第四批 ②：首页空闲时为**下一次**新建记事预先建材并预算轨迹。
 *
 * ## 轨迹与贴图分离
 *
 * `material.vert` 的第 0 遍（表面）把每个格子按 `uv = source / card` 采样前景纹理、并画在
 * `world = offset + source` 上——采样位置与屏幕位置是同一个恒等仿射映射，所以**倒放末帧
 * （t = 0，全部格子 surface = 1）就是前景纹理的一次逐像素拷贝，与格子分辨率无关**。
 * 于是可以：用**预测快照**建材、预算轨迹，点新建之后只把**真实快照**换上去当贴图。
 * 末帧逐像素等于真实页面，途中每一片的出生、寿命、复制片数按预测快照来。
 *
 * ## 预测快照用的是「上一次真实创建页的首屏」
 *
 * 首选方案是把创建模式的视图初始化拆成可作用于离屏 View 树的函数、用 `HardwareRenderer`
 * 离屏渲染一张同尺寸的创建页。调研（2026-09-15）判定**改造过大**，具体阻碍：
 *
 * 1. 创建页首帧的几何 100% 由 decorView 的 inset 回调链决定（正文区 `marginTop =
 *    56dp + statusBarTopInset`、根布局 `paddingBottom = max(导航栏, 输入法)`），离屏树没有
 *    ViewRootImpl，这条链根本不会触发，只能手工注入一份复刻的 `WindowInsets`——而其中的
 *    输入法高度在预测那一刻无从得知；
 * 2. `App.updateNewThingColor()`（重掷下一次的随机背景）就写在 `initMembers` 正中间，离屏
 *    跑一遍会把下一次的颜色提前掷掉，缓存永远命中不了；同一段里还有全局计数器、两次
 *    `registerReceiver`、一次可能写偏好并弹窗的版本升级检查；
 * 3. actionbar 上那三个图标走的是 `AppCompatDelegate`（`setSupportActionBar` + `menuInflater`），
 *    与 Activity 绑死，离屏要另写一套；
 * 4. `DetailActivity` 5840 行、创建路径触碰约 50 个类级字段，抽出来等于重写这个 Activity。
 *
 * 退而求其次：**把上一次真实创建页的首屏像素留在进程里**当预测素材（首次新建没有素材，
 * 直接走原路径）。代价是背景色与真实页面不同，只影响途中权重；收益是零额外渲染、零副作用。
 *
 * ## 生命周期
 *
 * - 首页 `onResume` 之后、没有触摸、没有粒子会话在跑、静置 [ParticlePerfTuning.creationPrewarmIdleMs]
 *   毫秒才开工；每推进一小段就让出 GL 线程，触摸或有动画开播就暂停，稍后继续。
 * - `onTrimMemory` / `onLowMemory` / 退后台（UI 不可见）全部释放。
 */
internal object ParticleCreationPrewarm {

    // ---- 主线程状态 ----

    private val main = Handler(Looper.getMainLooper())
    private var lastInteractionAt = 0L
    private var scheduled = false
    private var homeActivity: WeakReference<Activity>? = null
    private var pendingKey: ParticleCreationKey? = null

    /** 上一次真实创建页首屏（预测素材）。写在材料线程，读在材料线程/主线程。 */
    private class Source(
        val pixels: IntArray, val width: Int, val height: Int,
        val originX: Float, val originY: Float,
        val virtualTouchX: Float, val virtualTouchY: Float,
        val touchGap: Float?, val touchStrength: Float?
    )

    @Volatile private var source: Source? = null

    /** 本次预测素材是从哪儿来的：`memory`（上一次真实创建页）/ `disk`（磁盘缓存）/ `none`。 */
    @Volatile private var sourceKind = "none"

    /** 落盘与读回都需要一个 Context；应用级引用挂在静态对象上不构成泄漏。 */
    @Volatile private var appContext: Context? = null

    /** 从磁盘读回来的那一份，连同它的键——键一变就必须重新去盘上找，不能拿旧版式顶着用。 */
    private class Restored(val key: String, val material: Source)

    @Volatile private var restored: Restored? = null

    /**
     * 按 [key]（去掉背景色的那一份）取磁盘缓存。命中过的留在内存里省掉重复解码；
     * 键不匹配就重新找一次，找不到返回 null——**绝不拿另一套版式的图当预测素材**。
     */
    private fun restoredFor(context: Context, key: String): Source? {
        restored?.let { if (it.key == key) return it.material }
        val stored = ParticlePrewarmStore.load(context, key) ?: run { restored = null; return null }
        val material = Source(stored.pixels, stored.width, stored.height,
            stored.originX, stored.originY, stored.virtualTouchX, stored.virtualTouchY,
            stored.touchGap, stored.touchStrength)
        restored = Restored(key, material)
        return material
    }

    // ---- 跨线程状态 ----

    private val lock = Any()
    private var ready: ParticlePreparedAppearance? = null
    @Volatile private var working = false

    /** 观察器读的诊断：命中与否、未命中原因、预算耗时。 */
    @Volatile private var lastOutcome = "none"
    @Volatile private var lastDetail = ""
    @Volatile private var lastPrecomputeMs = 0.0
    @Volatile private var lastTakeMs = 0.0

    // ---- 首页侧入口 ----

    /** 首页可见（`onResume`）。[keyProvider] 给出「如果现在点新建，键会是什么」。 */
    fun onHomeVisible(activity: Activity, keyProvider: () -> ParticleCreationKey?) {
        if (!ParticlePerfTuning.creationPrewarm) return
        homeActivity = WeakReference(activity)
        appContext = activity.applicationContext
        this.keyProvider = keyProvider
        touched()
    }

    private var keyProvider: (() -> ParticleCreationKey?)? = null

    /**
     * 首页不可见：**不释放**算好的轨迹与预测素材——紧接着的那一下往往就是打开创建页；
     * 只把首页的引用放掉（[keyProvider] 是个捕获了 Activity 的 lambda，挂在静态对象上就是泄漏）。
     */
    fun onHomeHidden() {
        main.removeCallbacks(schedule)
        main.removeCallbacks(resume)
        scheduled = false
        homeActivity = null
        keyProvider = null
    }

    /** 任何触摸/按键都推迟开工，并暂停正在跑的预算。 */
    fun touched() {
        if (!ParticlePerfTuning.creationPrewarm) return
        lastInteractionAt = SystemClock.uptimeMillis()
        if (scheduled) main.removeCallbacks(schedule)
        scheduled = true
        main.postDelayed(schedule, ParticlePerfTuning.creationPrewarmIdleMs)
    }

    private val schedule = Runnable {
        scheduled = false
        beginIfIdle()
    }

    private fun beginIfIdle() {
        if (!ParticlePerfTuning.creationPrewarm) return
        val activity = homeActivity?.get() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val decor = activity.window?.decorView as? ViewGroup ?: return
        if (!decor.isAttachedToWindow || decor.width <= 0 || decor.height <= 0) return
        if (SystemClock.uptimeMillis() - lastInteractionAt < ParticlePerfTuning.creationPrewarmIdleMs) {
            touched(); return
        }
        if (ParticleGpuWork.busy || ParticleDismissOverlay.activeIn(activity).isNotEmpty()) {
            touched(); return
        }
        if (ThingAnimationPreferences.creation(activity) != ThingAnimationPreferences.PARTICLE) return
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val key = keyProvider?.invoke() ?: return
        synchronized(lock) {
            if (working) return
            ready?.let { if (it.key == key) return else discard(it) }
            ready = null
            working = true
        }
        pendingKey = key
        val density = activity.resources.displayMetrics.density
        val context = activity.applicationContext
        val assets = context.assets
        val width = decor.width
        val height = decor.height
        val seed = (Math.random() * Int.MAX_VALUE).toInt()
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            // 第五批：内存里没有上一次真实创建页时，按键去磁盘缓存里找一张。解码放在这条
            // 后台线程上（首页静置 500 ms 之后才会走到这里），不挡首页首帧。
            var kind = "memory"
            val material = source ?: restoredFor(context, key.persistent)?.also { kind = "disk" }
            if (material == null) {
                synchronized(lock) { working = false }
                sourceKind = "none"
                record("skip", "还没有真实创建页快照可作预测素材")
                return@Thread
            }
            sourceKind = kind
            val built = runCatching {
                ParticleMicroflakeRenderer.fromPixels(assets, 0, 0, density,
                    material.pixels, material.width, material.height,
                    material.originX, material.originY,
                    material.virtualTouchX, material.virtualTouchY, seed,
                    material.touchGap, material.touchStrength)
            }.getOrElse {
                Log.w(ParticleMicroflakeRenderer.TAG, "预测建材失败", it)
                synchronized(lock) { working = false }
                record("error", "预测建材失败：${it.javaClass.simpleName}")
                return@Thread
            }
            val upload = ParticleMicroflakePreparation.packColors(material.pixels)
            val pixels = ParticleMicroflakePreparation.Pixels(material.width, material.height, upload, null)
            ParticleGl.post { precompute(assets, key, seed, built, pixels, width, height, density) }
        }, "ParticleCreationPrewarm").start()
    }

    // ---- GL 线程：分步预算，随时可以停下 ----

    private class Job(
        val key: ParticleCreationKey, val seed: Int,
        val renderer: ParticleMicroflakeRenderer,
        val samples: Int, val start: Int, val counts: IntArray?,
        val sharedWidth: Int, val sharedHeight: Int,
        val snapshotWidth: Int, val snapshotHeight: Int,
        val durationScale: Float, val startedAt: Long
    )

    private var job: Job? = null

    private fun precompute(assets: AssetManager, key: ParticleCreationKey, seed: Int,
                           input: ParticleMicroflakeRenderer.Input,
                           pixels: ParticleMicroflakePreparation.Pixels,
                           width: Int, height: Int, density: Float) {
        // 绝不 allowResize：预算是后台工作，不能把正在播的会话的累积纹理换掉。共享资源已经
        // 建好时就按它现有的尺寸算，真实动画那一刻再由 ParticleSession 核对一次。
        val shared = ParticleGl.ensureShared(assets, width, height, allowResize = false) ?: run {
            abandon("共享资源不可用"); return
        }
        val scale = (density / 1.875f).coerceAtLeast(.5f)
        val framed = input.copy(frameWidth = shared.width / scale, frameHeight = shared.height / scale)
        val created = runCatching {
            ParticleMicroflakeRenderer(assets, shared).also {
                it.preparePipeline(ParticleMicroflakeRenderer.sharedResources(assets))
                it.prepareReversePipeline()
                it.prepareForeground(pixels)
                it.prepare(framed)
            }
        }.getOrElse {
            Log.w(ParticleMicroflakeRenderer.TAG, "预算管线建立失败", it)
            abandon("预算管线建立失败：${it.javaClass.simpleName}")
            return
        }
        val durationScale = AnimatorDurationScale.scale().coerceAtLeast(.1f)
        val duration = ParticleReversePlan.APPEARANCE_SECONDS * durationScale
        val samples = ParticleReversePlan.samplesFor(duration, 60f)
        val counts = ParticleReversePlan.aliveCounts(created.materialValues, samples)
        val start = if (ParticlePerfTuning.creationAppearanceSkipEmptyTail)
            ParticleReversePlan.startFrameOf(counts, created.materialValues.size / 12,
                ParticlePerfTuning.appearanceAliveRatio) else samples
        runCatching { created.beginReverse(samples, start, precomputeProbe = false, throttled = true) }
            .getOrElse {
                runCatching { created.close() }
                abandon("预算起步失败：${it.javaClass.simpleName}")
                return
            }
        job = Job(key, seed, created, samples, start, if (BuildConfig.DEBUG) counts else null,
            shared.width, shared.height, pixels.width, pixels.height, durationScale, System.nanoTime())
        ParticleGl.post { step() }
    }

    private fun step() {
        val current = job ?: return
        if (!ParticlePerfTuning.creationPrewarm) { cancelJob("开关已关"); return }
        // 有触摸/有动画在播就停一拍，稍后接着算：历史只增不减，续算完全等价。
        if (ParticleGpuWork.busy ||
            SystemClock.uptimeMillis() - lastInteractionAt < ParticlePerfTuning.creationPrewarmIdleMs) {
            main.post { retryLater() }
            return
        }
        val done = runCatching {
            val deadline = System.nanoTime() + BUDGET_NANOS
            while (current.renderer.reverseIncomplete && System.nanoTime() < deadline) {
                if (!current.renderer.stepReverse { false }) return@runCatching true
            }
            !current.renderer.reverseIncomplete
        }.getOrElse {
            Log.w(ParticleMicroflakeRenderer.TAG, "预算失败", it)
            cancelJob("预算失败：${it.javaClass.simpleName}")
            return
        }
        if (!done) { ParticleGl.post { step() }; return }
        runCatching { current.renderer.endReverse() }
        val elapsed = (System.nanoTime() - current.startedAt) / 1e6
        job = null
        val prepared = ParticlePreparedAppearance(current.key, current.renderer, current.samples,
            current.start, ParticleReversePlan.APPEARANCE_SECONDS.toDouble() *
                current.durationScale * current.start / current.samples,
            current.counts, current.renderer.particleCount, current.seed,
            current.sharedWidth, current.sharedHeight, current.snapshotWidth, current.snapshotHeight,
            elapsed)
        synchronized(lock) {
            working = false
            ready?.let { discard(it) }
            ready = prepared
        }
        lastPrecomputeMs = elapsed
        if (BuildConfig.DEBUG) Log.i(ParticleMicroflakeRenderer.TAG,
            "创建页轨迹已提前算好 elapsedMs=$elapsed samples=${current.samples} start=${current.start} " +
                "count=${prepared.particleCount}")
    }

    /** 被触摸打断：回到主线程重新排队，而不是在 GL 线程上空转。 */
    private fun retryLater() {
        if (job == null) return
        main.removeCallbacks(resume)
        main.postDelayed(resume, ParticlePerfTuning.creationPrewarmIdleMs)
    }

    private val resume = Runnable { ParticleGl.post { step() } }

    private fun abandon(reason: String) {
        synchronized(lock) { working = false }
        record("error", reason)
    }

    private fun cancelJob(reason: String) {
        job?.let { runCatching { it.renderer.close() } }
        job = null
        abandon(reason)
    }

    /** 只能在 GL 线程调用。 */
    private fun discard(prepared: ParticlePreparedAppearance) {
        ParticleGl.post { runCatching { prepared.renderer.close() } }
    }

    // ---- 创建页侧入口 ----

    /**
     * 记下这一次真实创建页的首屏，作为**下一次**的预测素材。在材料线程调用（那里本来就
     * 要把快照解成 ARGB，这里只是多留一个引用，不复制像素）。
     */
    fun remember(spec: ParticleDismissSpec, pixels: IntArray?) {
        if (!ParticlePerfTuning.creationPrewarm || !spec.creationPage || pixels == null) return
        val bitmap = spec.snapshot
        if (pixels.size != bitmap.width * bitmap.height) return
        source = Source(pixels, bitmap.width, bitmap.height, spec.originXPx, spec.originYPx,
            spec.virtualTouchXPx, spec.virtualTouchYPx, spec.touchGap, spec.touchStrength)
        // 有了这一进程里的真实首屏，从磁盘读回来的那一份就没用了（它只是冷启动的替身），
        // 17 MB 不必再挂着。
        restored = null
        // 第五批：同一屏排进后台线程无损落盘，下一次**冷启动**的第一次新建就能命中。
        // 这条调用立即返回，压缩与写盘都在 ParticlePrewarmStore 自己的线程上；失败只记日志。
        val key = spec.prewarmKey ?: return
        val context = appContext ?: return
        ParticlePrewarmStore.remember(context, key.persistent,
            ParticlePrewarmStore.Material(pixels, bitmap.width, bitmap.height,
                spec.originXPx, spec.originYPx, spec.virtualTouchXPx, spec.virtualTouchYPx,
                spec.touchGap, spec.touchStrength))
    }

    /**
     * 主线程：这一次新建能不能用上提前算好的轨迹。
     *
     * 命中要求键相同**且**快照尺寸、原点与预算时完全一致；共享资源尺寸的最后一道校验在
     * GL 线程上做（[ParticleSession] 收养失败时回退到原路径）。
     */
    fun take(key: ParticleCreationKey?, snapshotWidth: Int, snapshotHeight: Int,
             originX: Float, originY: Float): ParticlePreparedAppearance? {
        val started = System.nanoTime()
        if (!ParticlePerfTuning.creationPrewarm) { record("miss", "预算开关已关"); return null }
        var busy = false
        val prepared = synchronized(lock) {
            busy = working
            val candidate = ready
            ready = null
            candidate
        }
        lastTakeMs = (System.nanoTime() - started) / 1e6
        if (prepared == null) {
            record("miss", if (busy) "预算还没算完" else
                (lastDetail.takeIf { lastOutcome == "skip" || lastOutcome == "error" } ?: "没有预算好的轨迹"))
            return null
        }
        val material = source
        val reason = ParticleCreationMatch.reject(prepared.key, key,
            prepared.snapshotWidth, prepared.snapshotHeight, snapshotWidth, snapshotHeight,
            material?.originX, material?.originY, originX, originY)
        if (reason != null) {
            discard(prepared)
            record("miss", reason)
            return null
        }
        record("hit", "预算耗时 ${"%.1f".format(prepared.precomputeMs)} ms")
        return prepared
    }

    /** 收养失败或没人接手时回退；轨迹已经没用了，直接丢掉（GL 对象只能在 GL 线程释放）。 */
    fun rejected(prepared: ParticlePreparedAppearance, reason: String) {
        record("reject", reason)
        discard(prepared)
    }

    // ---- 释放 ----

    /** 内存告警与退后台（UI 不可见）：预算好的轨迹与预测素材全部放掉。 */
    fun trimMemory() {
        main.removeCallbacks(schedule)
        main.removeCallbacks(resume)
        scheduled = false
        source = null
        // 只清内存态：磁盘缓存原样留着，下一次空闲时按键重新读回来（第五批）。
        restored = null
        sourceKind = "none"
        val stale = synchronized(lock) { val value = ready; ready = null; value }
        // 没有任何 GL 侧的东西要放时不要 post——那会把常驻 GL 线程白起起来。
        if (stale == null && !working) { record("trim", "内存告警或退后台，无可释放"); return }
        ParticleGl.post {
            stale?.let { runCatching { it.renderer.close() } }
            job?.let { runCatching { it.renderer.close() } }
            job = null
            synchronized(lock) { working = false }
        }
        record("trim", "内存告警或退后台，已释放")
    }

    // ---- 缓存键 ----

    /**
     * 「如果现在点新建按钮，创建页首帧会长什么样」的指纹。
     *
     * 逐项对应 2026-09-15 的创建页首帧输入调研（见结果文档第 2 节的表）。文件夹那一项传
     * [folderKey]（列表投影自己的键，含整条祖先 id 链 + 私密鉴权态，全在内存里）——**文件夹
     * 的名字与配色不进键**：那要逐级查数据库，不能压在点击那一刻的主线程上。改名/改色只让
     * 预测素材稍微过时（影响途中每一片的权重），末帧仍由真实贴图拼回，下一次真实新建自动纠正。
     */
    fun fingerprint(activity: Activity, touchX: Float, touchY: Float,
                    folderKey: String, background: ThingBackground?): ParticleCreationKey? {
        val decor = activity.window?.decorView ?: return null
        if (decor.width <= 0 || decor.height <= 0) return null
        val configuration = activity.resources.configuration
        val insets = ViewCompat.getRootWindowInsets(decor)
        val bars = insets?.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ime = insets?.getInsets(WindowInsetsCompat.Type.ime())
        // 第五批：新记事背景色单独拎出来。磁盘上的预测素材按**其余 15 项**匹配——
        // 背景色每次新建都重掷，拿它去要求相等等于永远不命中，而它只影响途中权重。
        val rest = buildString {
            append("folder=").append(folderKey)
            append("|night=").append(configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
            append("|loc=").append(configuration.locales.toLanguageTags())
            append("|font=").append((configuration.fontScale * 100).roundToInt())
            append("|dpi=").append(configuration.densityDpi)
            append("|win=").append(decor.width).append('x').append(decor.height)
            append("|ori=").append(configuration.orientation)
            append("|multi=").append(activity.isInMultiWindowMode)
            append("|bars=").append(bars?.top ?: -1).append(',').append(bars?.bottom ?: -1)
            append("|ime=").append(ime?.bottom ?: -1)
            append("|mode=").append(ThingAnimationPreferences.creation(activity))
            append("|scale=").append((AnimatorDurationScale.scale(activity) * 100).roundToInt())
            append("|hz=").append(refreshRate(decor).roundToInt())
            append("|fab=").append(touchX.roundToInt()).append(',').append(touchY.roundToInt())
            append("|recipe=").append(recipe())
        }
        val colour = background?.toJson() ?: "null"
        return ParticleCreationKey("bg=$colour|$rest", rest)
    }

    private fun refreshRate(decor: View): Float =
        (if (Build.VERSION.SDK_INT >= 30) decor.display?.refreshRate else null) ?: 60f

    /** 影响轨迹的全部开关；任何一项被 A/B 改掉，预算好的轨迹就不能用了。 */
    private fun recipe(): String = buildString {
        append(ParticlePerfTuning.creationAppearanceTouch)
        append(',').append(ParticlePerfTuning.creationAppearanceSkipEmptyTail)
        append(',').append(ParticlePerfTuning.appearanceAliveRatio)
        append(',').append(ParticlePerfTuning.skipBeforeFirstBirth)
        append(',').append(ParticlePerfTuning.jacobiLargeTile)
        append(',').append(ParticlePerfTuning.jacobiLargeBatch)
        append(',').append(ParticlePerfTuning.historyInIntegration)
    }

    // ---- 诊断 ----

    private fun record(outcome: String, detail: String) {
        lastOutcome = outcome
        lastDetail = detail
        if (BuildConfig.DEBUG) Log.i(ParticleMicroflakeRenderer.TAG,
            "创建页预算 $outcome：$detail")
    }

    fun diagnostics(): JSONObject = JSONObject()
        .put("outcome", lastOutcome)
        .put("detail", lastDetail)
        .put("precomputeMs", lastPrecomputeMs)
        .put("takeMs", lastTakeMs)
        .put("hasSource", source != null)
        .put("enabled", ParticlePerfTuning.creationPrewarm)
        // 第五批：这一次的预测素材是内存里的上一屏、磁盘缓存，还是根本没有。
        .put("sourceKind", sourceKind)
        .put("disk", ParticlePrewarmStore.diagnostics())

    /** 一次 pump 里连续预算的墙钟预算；与 [ParticleGl] 的准备预算同量级。 */
    private const val BUDGET_NANOS = 8_000_000L
}
