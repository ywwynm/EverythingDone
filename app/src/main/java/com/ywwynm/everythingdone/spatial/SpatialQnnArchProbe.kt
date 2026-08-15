package com.ywwynm.everythingdone.spatial

import android.content.Context
import android.util.Log
import ai.onnxruntime.OrtSession
import java.io.File

/**
 * 让 QNN 自己说出本机是哪一档 HTP，**或者说出它根本起不来**。
 *
 * ## 为什么需要它
 *
 * [SpatialQnnSupport.hardwareArch] 原本只有两条路：判定表、以及扫厂商在 `/vendor` `/odm`
 * 下装的 Skel/Stub。两条都可能落空——新骁龙没登记，而厂商不一定装 QNN 库（Z Fold4 六个
 * 目录一个都没有）。落空的后果不是整个 NPU 不可用（运行组件有全 arch 包兜底，端上编译的
 * 模型照常跑），而是**按架构分片下发的预编译产物取不到**：Big-LaMa（NPU 版）整行消失。
 *
 * ## 原理：问 QNN，而不是猜硬件
 *
 * QNN 建 session 时会按它自己探测到的 SoC `dlopen` 对应的 `libQnnHtpV<N>Stub.so`
 *（D267 实测：四份 Skel/Stub 同在时它只加载了 V81，日志 `min_arch = 81`）。四份 Stub 都在
 * 磁盘上，**只有被 dlopen 的那份会出现在 `/proc/self/maps` 里**。Skel 跑在 DSP 上，不会
 * 映射进本进程，所以只找 Stub。
 *
 * 探测图是 assets 里 138 字节的一个 Add（[PROBE_ASSET]）。**不在乎有没有节点真的落到
 * NPU 上**：要的只是 backend 初始化 + device 创建那一步，那一步就已经 dlopen 了 Stub
 *（D217 记过"四条证据全绿也可能一个节点都没落到 QNN"，正说明这是两件事）。
 *
 * ## 失败也是结论（D273）
 *
 * 全 arch 包里四档 Skel 齐全、QNN 却建不起 session，**这台机就确定落在我们出过的最低档
 * 以下**——硅只要不低于最低档，QNN 必定挑得到一份。这条不依赖任何判定表，正好补上
 * "表里没有 + 厂商没装 so"那类设备（用户举的 v66 例子）：它们此前会白装 63 MB 组件、
 * 界面显示"已启用"而实际全程 CPU。
 *
 * 两道防误判：**要连续 [MAX_PROBE_FAILURES] 次失败才下结论**（偶发的内存不足、驱动异常
 * 不该变成永久判决），且结论**与运行组件版本绑定**——换一份组件就重探，因为结论本来就是
 * 跟着那一份组件得出的。
 */
object SpatialQnnArchProbe {

    /**
     * 装完运行组件、或进设置页时调用。已有结论就不再探测——硬件不会变，而建 session 有
     * 几十毫秒到数秒的代价。
     *
     * @return 探到的档位、[SpatialQnnSupport.TOO_OLD_ARCH_MARK]（确定用不了），
     *         或 null（这次没得出结论，下次还会再试）。
     */
    @Synchronized
    fun probeIfNeeded(context: Context): String? {
        // **总开关必须是开的。** 不是出于礼貌，是物理限制：QNN 版与 CPU 版的
        // libonnxruntime.so 是两份不同的库，同一进程只能加载一份，而开关决定加载哪一份
        // （[SpatialRuntimeStore.requiredPackageVersion]）。开关关着时进程里的 ORT 没有
        // QNN EP，`addQnn` 直接抛异常；`nativeLibraryDirectory` 拿到的也是 CPU 版目录，
        // 里面一个 Skel 都没有。2026-08-15 在 Z Fold4 上实测踩到：探测静默地什么也没做。
        if (!SpatialPreferences.qnnEnabled(context)) return null
        val runtimeVersion = SpatialRuntimeStore.installedPackageVersion(context) ?: return null
        // 换过运行组件就把上一轮的结论作废：那是跟着上一份组件得出的。
        if (probedRuntimeVersion(context) != runtimeVersion) clearState(context)
        // 已有确定结论就到此为止——结论持久化、只探一次，与下面的进程态无关。
        cachedArch(context)?.let { return it }
        // **进程里已经加载了另一份变体时不能探，也绝不能计失败。** 开关翻转不换进程里的
        // 库（同一进程只能加载一份 libonnxruntime.so），用户切到 NPU 前只要跑过一次 CPU
        // 推理（下载模型的自测就会），此后本进程里 ensureLoaded 会因 marker 不一致直接抛
        // "请重新启动 App"——那是进程状态的回答，不是硬件的回答。此前没有这道门，
        // "装完组件探一次 + 进一次设置页再探一次"恰好凑满两次失败，把一台健康的新骁龙
        // 永久判成 PROBE_FAILED（2026-08-15 审查发现）。等重启后进程干净了再探。
        val loaded = SpatialRuntimeStore.loadedPackageVersion()
        if (loaded != null && loaded != runtimeVersion) return null
        // 判定表或厂商库已经给出答案时不必再探：三条来源里这一条最贵。
        if (SpatialQnnSupport.hardwareArch(context) != null) return null
        if (!SpatialRuntimeStore.isVariantInstalled(context, qnn = true)) return null

        val directory = runCatching {
            SpatialRuntimeStore.nativeLibraryDirectory(context)
        }.getOrNull() ?: return null
        // 包里一对 Skel/Stub 都没有的话，失败该归因于包不完整而不是硬件太老，不计入。
        if (!hasAnySkelStubPair(directory)) return null

        val probed = runCatching { probe(context, directory) }.getOrElse { error ->
            Log.w(TAG, "QNN 架构探测失败", error)
            null
        }
        if (probed != null) {
            save(context, probed, runtimeVersion, failures = 0)
            return probed
        }
        val failures = failureCount(context) + 1
        if (failures < MAX_PROBE_FAILURES) {
            save(context, arch = null, runtimeVersion = runtimeVersion, failures = failures)
            return null
        }
        // 四档 Skel 齐全却连一次 session 都建不起来——这台机确定用不了我们发布的任何一档。
        // 写 PROBE_FAILED_MARK 而不是 TOO_OLD_ARCH_MARK：两者都是"不可用"，但**这一档是
        // 装完组件才知道的**，界面要置灰而不是隐藏——那 192 MB 还在磁盘上，用户得看得见
        // 才删得掉（D274 用户裁定）。
        Log.w(TAG, "QNN 连续 $failures 次起不来，判定本机不支持")
        save(context, SpatialQnnSupport.PROBE_FAILED_MARK, runtimeVersion, failures)
        return SpatialQnnSupport.PROBE_FAILED_MARK
    }

    /**
     * 已经建起过 QNN session 的地方顺手调一次——读一个 `/proc` 文件，几乎零成本，
     * 而且此刻 Stub 一定已经在 maps 里了，比专门建一次探测 session 更省。
     */
    fun recordFromLoadedLibraries(context: Context) {
        if (cachedArch(context) != null) return
        if (SpatialQnnSupport.hardwareArch(context) != null) return
        val runtimeVersion = SpatialRuntimeStore.installedPackageVersion(context) ?: return
        readLoadedStubArch()?.let { save(context, it, runtimeVersion, failures = 0) }
    }

    fun cachedArch(context: Context): String? = SpatialQnnSupport.probedArch(context)

    private fun probe(context: Context, directory: File): String? {
        val backend = File(directory, QNN_HTP_BACKEND)
        if (!backend.isFile) return null
        val model = context.assets.open(PROBE_ASSET).use { it.readBytes() }
        val environment = SpatialOrtRuntime.environment(context)
        OrtSession.SessionOptions().use { options ->
            options.addQnn(
                mapOf(
                    "backend_path" to backend.absolutePath,
                    // 与产品路径同参：探测的是"这台机上 QNN 会挑哪一档"，
                    // 参数不同有可能走进不同的初始化分支。
                    "enable_htp_fp16_precision" to "1",
                    "htp_graph_finalization_optimization_mode" to "0"
                )
            )
            environment.createSession(model, options).close()
        }
        return readLoadedStubArch()
    }

    /** 与 [SpatialQnnSessionFactory] 同一条判据：包里至少凑得出一对 Skel+Stub。 */
    private fun hasAnySkelStubPair(directory: File): Boolean {
        val names = directory.list()?.toSet() ?: return false
        return names.any {
            SKEL_REGEX.matches(it) && it.replace("Skel.so", "Stub.so") in names
        }
    }

    private fun readLoadedStubArch(): String? = runCatching {
        File("/proc/self/maps").useLines { lines ->
            lines.mapNotNull { line ->
                STUB_REGEX.find(line)?.groupValues?.get(1)?.toIntOrNull()
            }.maxOrNull()
        }?.let { "v$it" }?.takeIf(SpatialQnnSupport::isValidDspArch)
    }.getOrNull()

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun probedRuntimeVersion(context: Context): String? =
        preferences(context).getString(KEY_PROBE_RUNTIME_VERSION, null)

    private fun failureCount(context: Context): Int =
        preferences(context).getInt(KEY_PROBE_FAILURES, 0)

    private fun save(
        context: Context,
        arch: String?,
        runtimeVersion: String,
        failures: Int
    ) {
        SpatialQnnSupport.saveProbedArch(context, arch)
        preferences(context).edit()
            .putString(KEY_PROBE_RUNTIME_VERSION, runtimeVersion)
            .putInt(KEY_PROBE_FAILURES, failures)
            .apply()
    }

    private fun clearState(context: Context) {
        SpatialQnnSupport.saveProbedArch(context, null)
        preferences(context).edit()
            .remove(KEY_PROBE_RUNTIME_VERSION)
            .remove(KEY_PROBE_FAILURES)
            .apply()
    }

    private val STUB_REGEX = Regex("""libQnnHtpV(\d{2})Stub\.so""")
    private val SKEL_REGEX = Regex("""^libQnnHtpV\d{2}Skel\.so$""")
    private const val PROBE_ASSET = "spatial/qnn_arch_probe.onnx"
    private const val QNN_HTP_BACKEND = "libQnnHtp.so"
    private const val PREFERENCES_NAME = "spatial_qnn_profiles"
    private const val KEY_PROBE_RUNTIME_VERSION = SpatialQnnSupport.KEY_PROBE_RUNTIME_VERSION
    private const val KEY_PROBE_FAILURES = "probe_failures"

    /** 偶发失败（内存不足、驱动异常）不该变成永久判决，连续两次才下结论。 */
    private const val MAX_PROBE_FAILURES = 2
    private const val TAG = "SpatialQnn"
}
