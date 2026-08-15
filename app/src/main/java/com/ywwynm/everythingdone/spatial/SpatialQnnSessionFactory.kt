package com.ywwynm.everythingdone.spatial

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File

/**
 * QNN HTP session 的唯一入口：判定、编译产物复用、失败回落，都收在这里。
 *
 * 调用方只需要问一句"这个模型能不能用 NPU 建 session"，拿到 null 就按原来的 CPU/XNNPACK
 * 路径走。**任何一步失败都返回 null 而不是抛异常**——NPU 是加速手段，不是功能前提，
 * 它坏掉时用户应该只是慢一点，而不是生成不出来。
 *
 * **这条契约不止管建 session。** 失败可以晚到第一次 `run()`（D276：OPD2515 上 Big-LaMa
 * 建 session 正常、执行时 CDSP 被十秒看门狗打死），那种异常此前会一路穿到界面，把整条
 * 生成链路打死。引擎侧必须把"建 session + 跑"整段包在 [withExecuteFallback] 里。
 *
 * ## 两条必须遵守的事实（D217 实测）
 *
 * 1. **不要设 `ADSP_LIBRARY_PATH`。** ORT 的 QNN EP 自己会按 `backend_path` 设定；
 *    手动设会让它跳过自己的设定并导致 HTP 起不来。
 * 2. **复用 context 时直接加载 `_ctx.onnx`，且不能再设 `ep.context_enable`。**
 *
 * ## 判定为可用 ≠ 真的接管了
 *
 * QNN EP 在拿不下某些节点时会把它们留给 CPU，甚至一个都不接管而整图退回——而且是静默的。
 * 因此 [Outcome.usedQnn] 只表示"session 是以 QNN EP 建起来的"，真实的节点划分要靠
 * profiling 才能知道（D217 里四条绿证据下仍是 4540 个节点全在 CPU）。
 */
object SpatialQnnSessionFactory {

    data class Request(
        val modelFile: File,
        val modelId: String,
        val modelVersion: String,
        val modelSha256: String,
        /** 形状档标识；固定形状模型直接用尺寸，如 `312x312`。 */
        val shapeTag: String
    )

    data class Outcome(
        val session: OrtSession,
        val usedQnn: Boolean,
        /** 本次是否发生了 HTP 图编译（首次会很慢：实测 20–51 秒）。 */
        val compiled: Boolean
    )

    /**
     * 设备与运行组件是否具备 QNN 条件。不做任何 IO 之外的重活，可在 UI 线程调用。
     */
    fun isAvailable(context: Context): Boolean = resolveEnvironment(context) != null

    /**
     * 每次尝试建 session 之后回报**实际**走了哪条路（true = 真的在 NPU 上跑）。
     *
     * 界面据此如实标注某一步是否用了 NPU。**不能靠"应该会用"去推断**：条件不满足或
     * 建 session 失败时都会静默回落 CPU，推断出来的标注会说谎
     * （2026-08-14 用户要求"需要写上，这样我才知道真的是在用 NPU 加速"）。
     */
    @Volatile
    var sessionListener: ((modelId: String, usedQnn: Boolean) -> Unit)? = null

    private data class QnnEnvironment(
        val backendPath: File,
        val dspArch: String,
        val runtimePackageVersion: String
    )

    /**
     * 一次"建 session + 跑"的作用域。**只记这一个模型**：一次生成里会依次跑分割、边界、
     * 补全，把它们混在一个作用域里，任何一个失败都会把另外两个也拉黑。
     */
    private class Attempt(val modelId: String) {
        var key: SpatialQnnContextStore.Key? = null

        /**
         * 重跑这一遍必须走 CPU。
         *
         * **不能靠"拉黑已落盘"来达到这个效果**：连续 [SpatialQnnExecutionBlocklist.FAILURE_THRESHOLD]
         * 次才拉黑，第一次失败时黑名单还没生效，照着原样重跑会再走一次 QNN、再失败一次，
         * 用户白等两轮还是拿不到结果。
         */
        var forceCpu = false
    }

    private val attempt = ThreadLocal<Attempt?>()

    /**
     * 把 `createSession` 与其后的 `run()` 整段包起来：这一段里真的用上了 QNN、且抛出的是
     * ORT 自己的失败时，**记下结论并整段用 CPU 重跑一遍**。
     *
     * 重试的粒度是**整个 block**，不是单次 `run()`——Big-LaMa 是分块循环，失败可能发生在
     * 第三块上，而那时 session 已经废了（CDSP 的用户 PD 被打死，句柄全部失效），只能连
     * session 一起重建。block 因此必须是可重入的：读输入、建缓冲区、建 session 都在里面。
     *
     * 只对 [OrtException] 重试。取消（`任务已取消`）与契约校验都是 IllegalStateException，
     * 那些重跑一遍还是一样的结果，重试只会让用户多等一倍。
     *
     * 结论要连续 [SpatialQnnExecutionBlocklist.FAILURE_THRESHOLD] 次失败才落盘，但**回落
     * 是每次都做的**——本次生成必须出结果，与"要不要永久改走 CPU"是两个问题。
     */
    fun <T> withExecuteFallback(context: Context, modelId: String, block: () -> T): T {
        // 已经在外层作用域里（例如 selfTest 套着 runModel）就不再嵌套，否则内层记完结论、
        // 外层还会再重跑一次，用户白等两遍。
        if (attempt.get() != null) return block()
        val scope = Attempt(modelId)
        attempt.set(scope)
        try {
            return try {
                val result = block()
                // 跑通了就把连续计数清零，否则两次相隔很远的偶发失败会累加成永久判决。
                scope.key?.let {
                    runCatching { SpatialQnnExecutionBlocklist.recordSuccess(context, modelId) }
                }
                result
            } catch (error: Throwable) {
                val key = scope.key
                if (key == null || !shouldFallBackToCpu(error)) throw error
                val settled = runCatching {
                    SpatialQnnExecutionBlocklist.recordFailure(context, key)
                }.getOrDefault(false)
                Log.w(
                    TAG,
                    if (settled) "QNN 执行失败，本机此后改走 CPU：$modelId"
                    else "QNN 执行失败，本次改走 CPU；再失败一次才下结论：$modelId",
                    error
                )
                // 阈值没到时黑名单还不生效，必须显式把这一遍钉在 CPU 上。
                scope.forceCpu = true
                block()
            }
        } finally {
            attempt.remove()
        }
    }

    /**
     * 值不值得改用 CPU 重跑一遍。**只认 ORT 自己抛的失败**：取消（`任务已取消`）与各处
     * 契约校验都是 IllegalStateException，重跑一遍结果一样，只会让用户白等一倍时间。
     *
     * 顺着 `cause` 链找而不是只看最外层：引擎会把 ORT 的异常包在自己的上下文里。
     */
    internal fun shouldFallBackToCpu(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any { it is OrtException }

    private fun resolveEnvironment(context: Context): QnnEnvironment? {
        // 总开关关着就当没有 QNN——各引擎照常走 CPU，与开关引入前完全一致。
        if (!SpatialPreferences.qnnEnabled(context)) return null
        // 与设置页同一道判定：本机该用的那一档我们出过货吗（D270）。
        if (!SpatialQnnSupport.isNpuPossible(context)) return null
        if (!SpatialRuntimeStore.isVariantInstalled(context, qnn = true)) return null
        val directory = runCatching {
            SpatialRuntimeStore.nativeLibraryDirectory(context)
        }.getOrNull() ?: return null
        val backend = File(directory, QNN_HTP_BACKEND)
        if (!backend.isFile) return null
        val dspArch = SpatialQnnSupport.resolveDspArch(context)
        if (dspArch != null) {
            // 查得到架构：Skel 与 Stub 必须是本机那一份。缺了说明下发的包与设备不匹配，
            // 此时**不能**让 QNN 去试——它会在 device 创建阶段失败并留下一堆误导性日志。
            if (!File(directory, SpatialQnnSupport.skelLibraryName(dspArch)).isFile) return null
            if (!File(directory, SpatialQnnSupport.stubLibraryName(dspArch)).isFile) return null
        } else if (!hasAnySkelStubPair(directory)) {
            // 查不到架构：装的是全 arch 包，**由 QNN 自己按探测到的 SoC 挑**
            //（D267 实测：四份 Skel/Stub 同在时它只 dlopen 了 libQnnHtpV81Stub.so。
            // 原记录还说"且硅是 v85，取的是不超过硬件档的最高可用 Skel"，**该结论已由
            // D276 收回**：那台机全盘只有 V81 的库，v85 出自 prepare 库的一行日志，
            // 是过度解读。因此"更新的芯片也能工作"这一条目前没有实测支撑，靠的是
            // D273 的自探兜底——挑不到 Skel 时探测会失败并如实收口）。我们只确认至少凑得出
            // 一对，挑哪一档不该由我们猜。此前这里硬性要求查得到架构，等于把全 arch 包
            // 这条路在运行层堵死（D271）。
            return null
        }
        return QnnEnvironment(
            backendPath = backend,
            // 架构未知时用 ALL_ARCH 作 context 缓存键的一部分：真正用了哪一档由 QNN 决定，
            // 我们事先不知道。缓存是本机本地的，同一台设备上这个取值不会变；将来登记了它
            // 的架构，键会从 "all" 变成 "v<N>"，缓存作废重编一次而已。
            dspArch = dspArch ?: SpatialQnnSupport.ALL_ARCH,
            runtimePackageVersion = SpatialRuntimeStore.installedPackageVersion(context)
                ?: return null
        )
    }

    /** 全 arch 包里带哪几档由 catalog 决定，这里只确认目录里至少凑得出一对 Skel+Stub。 */
    private fun hasAnySkelStubPair(directory: File): Boolean {
        val names = directory.list()?.toSet() ?: return false
        return names.any {
            SKEL_LIBRARY_REGEX.matches(it) && it.replace("Skel.so", "Stub.so") in names
        }
    }

    /**
     * 尝试用 QNN HTP 建 session。返回 null 表示调用方应回落到既有 CPU/XNNPACK 路径。
     *
     * @param configure 供调用方设置与 EP 无关的通用选项（优化级别、线程数等）。
     */
    fun createSession(
        context: Context,
        environment: OrtEnvironment,
        request: Request,
        /**
         * 首次为该模型编译计算图之前触发（20–50 秒）。复用已缓存的 context 时**不会**触发。
         * 给调用方一个机会把界面文案换成"正在为 NPU 编译"，否则用户会盯着一条与当前
         * 工作无关的旧文案等半分钟（2026-08-14 用户指出）。
         */
        onCompileStart: () -> Unit = {},
        /**
         * 允许在没有现成 context 时**于设备上编译**。
         *
         * 对图很大的模型必须传 false：Big-LaMa 在端上编译会被 LMK 杀掉，AI Hub 上即使
         * 降到 optimization level 1 也要一小时以上，端上没有可能（D250/D253）。
         * 这类模型只在 catalog 下发了本机 dsp_arch 的预编译产物时才走 NPU。
         */
        allowOnDeviceCompile: Boolean = true,
        configure: (OrtSession.SessionOptions) -> Unit
    ): Outcome? {
        // 本次作用域已经因执行期失败回落过了，这一遍钉死在 CPU 上（见 [Attempt.forceCpu]）。
        if (attempt.get()?.takeIf { it.modelId == request.modelId }?.forceCpu == true) {
            return report(context, request.modelId, null)
        }
        // 逐模型开关：总开关之上再筛一层，用户可以只给收益大的模型开。
        if (!SpatialPreferences.qnnEnabledFor(context, request.modelId)) {
            return report(context, request.modelId, null)
        }
        val qnn = resolveEnvironment(context) ?: return report(context, request.modelId, null)
        val key = SpatialQnnContextStore.Key(
            modelId = request.modelId,
            modelVersion = request.modelVersion,
            modelSha256 = request.modelSha256,
            shapeTag = request.shapeTag,
            dspArch = qnn.dspArch,
            runtimePackageVersion = qnn.runtimePackageVersion
        )
        // 这一份模型在本机执行期失败过就别再试了（D276）。指纹任何一维变了都当没有结论。
        if (SpatialQnnExecutionBlocklist.isBlocked(context, key)) {
            return report(context, request.modelId, null)
        }

        // 下发的预编译产物优先于端上现编：Big-LaMa 这种图在设备上根本编不出来
        // （会被 LMK 杀掉），而 AI Hub 编好的直接建 session 即可（D252）。
        val precompiled = runCatching {
            SpatialQnnPrecompiledStore.contextModel(
                context, request.modelId, request.modelVersion, qnn.dspArch
            )
        }.getOrNull()
        if (precompiled != null) {
            val session = runCatching {
                SpatialInferenceTrace.measure(SpatialInferenceTrace.QNN_SESSION_CACHED) {
                    buildSession(environment, qnn, precompiled.absolutePath, null, configure)
                }
            }.getOrElse { error ->
                // 产物与本机运行组件不配（换过 QAIRT、或文件损坏）就放弃这一路，
                // 让下面的现编/CPU 兜底，不要每次都在这里失败。
                Log.w(TAG, "预编译 QNN context 不可用，回落：${request.modelId}", error)
                null
            }
            if (session != null) {
                return report(
                    context, request.modelId,
                    Outcome(session = session, usedQnn = true, compiled = false), key
                )
            }
        }

        val ready = runCatching { SpatialQnnContextStore.readyContextModel(context, key) }
            .getOrNull()
        if (ready != null) {
            val session = runCatching {
                SpatialInferenceTrace.measure(SpatialInferenceTrace.QNN_SESSION_CACHED) {
                    buildSession(environment, qnn, ready.absolutePath, null, configure)
                }
            }.getOrElse { error ->
                // 产物坏了（换了系统、库被清理）就丢掉重编，不要每次都在这里失败。
                Log.w(TAG, "QNN context 复用失败，作废后回落：${request.modelId}", error)
                runCatching { SpatialQnnContextStore.invalidate(context, request.modelId) }
                return report(context, request.modelId, null)
            }
            return report(
                context, request.modelId,
                Outcome(session = session, usedQnn = true, compiled = false), key
            )
        }

        if (!allowOnDeviceCompile) return report(context, request.modelId, null)
        return runCatching {
            onCompileStart()
            val target = SpatialQnnContextStore.prepareForCompile(context, key)
            val session = SpatialInferenceTrace.measure(SpatialInferenceTrace.QNN_SESSION_COMPILE) {
                buildSession(
                    environment, qnn, request.modelFile.absolutePath, target.absolutePath, configure
                )
            }
            // commit 失败（没产出 .bin）不影响这次推理，只是下次还要再编译一遍。
            runCatching { SpatialQnnContextStore.commit(context, key) }
                .onFailure { Log.w(TAG, "QNN context 未能登记：${request.modelId}", it) }
            report(
                context, request.modelId,
                Outcome(session = session, usedQnn = true, compiled = true), key
            )
        }.getOrElse { error ->
            Log.w(TAG, "QNN session 创建失败，回落 CPU：${request.modelId}", error)
            runCatching { SpatialQnnContextStore.invalidate(context, request.modelId) }
            report(context, request.modelId, null)
        }
    }

    /**
     * 统一的回报点：所有 return 路径都经过这里，漏一条就会让界面标注失真。
     *
     * 同时把"这次真的用上了 QNN，用的是哪一份"记进当前 [withExecuteFallback] 作用域——
     * 执行期失败要拉黑的是这一份，不能只按 modelId 记（换了组件版本就该重新试）。
     */
    private fun report(
        context: Context,
        modelId: String,
        outcome: Outcome?,
        key: SpatialQnnContextStore.Key? = null
    ): Outcome? {
        if (outcome?.usedQnn == true && key != null) {
            attempt.get()?.takeIf { it.modelId == modelId }?.key = key
        }
        runCatching { sessionListener?.invoke(modelId, outcome?.usedQnn == true) }
        // 真的用上 QNN 了，说明它已经 dlopen 了自己挑中的那份 Stub——顺手读一下
        // /proc/self/maps 就知道本机是哪一档，比专门建一次探测 session 更省。
        if (outcome?.usedQnn == true) {
            runCatching { SpatialQnnArchProbe.recordFromLoadedLibraries(context) }
        }
        return outcome
    }

    private fun buildSession(
        environment: OrtEnvironment,
        qnn: QnnEnvironment,
        modelPath: String,
        contextOutputPath: String?,
        configure: (OrtSession.SessionOptions) -> Unit
    ): OrtSession = OrtSession.SessionOptions().use { options ->
        configure(options)
        if (contextOutputPath != null) {
            options.addConfigEntry("ep.context_enable", "1")
            // embed_mode=0：context binary 单独成 .bin。嵌进 onnx 会让那个文件涨到
            // 几十 MB，且 ORT 读取时要整体载入。
            options.addConfigEntry("ep.context_embed_mode", "0")
            options.addConfigEntry("ep.context_file_path", contextOutputPath)
        }
        options.addQnn(
            mapOf(
                "backend_path" to qnn.backendPath.absolutePath,
                // 让 fp32 模型直接以 fp16 在 HTP 上跑，不需要量化（D217）。
                "enable_htp_fp16_precision" to "1",
                "htp_performance_mode" to "burst",
                // 0 = 编译最快。产物会被缓存复用，没必要为更激进的图优化多等。
                "htp_graph_finalization_optimization_mode" to "0"
            )
        )
        environment.createSession(modelPath, options)
    }

    private const val TAG = "SpatialQnn"
    private const val QNN_HTP_BACKEND = "libQnnHtp.so"
    private val SKEL_LIBRARY_REGEX = Regex("""libQnnHtpV\d+Skel\.so""")
}
