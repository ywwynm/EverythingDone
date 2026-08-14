package com.ywwynm.everythingdone.spatial

import ai.onnxruntime.OrtEnvironment
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

    private fun resolveEnvironment(context: Context): QnnEnvironment? {
        // 总开关关着就当没有 QNN——各引擎照常走 CPU，与开关引入前完全一致。
        if (!SpatialPreferences.qnnEnabled(context)) return null
        val dspArch = SpatialQnnSupport.resolveDspArch() ?: return null
        if (!SpatialRuntimeStore.isVariantInstalled(context, qnn = true)) return null
        val directory = runCatching {
            SpatialRuntimeStore.nativeLibraryDirectory(context)
        }.getOrNull() ?: return null
        val backend = File(directory, QNN_HTP_BACKEND)
        if (!backend.isFile) return null
        // Skel 与 Stub 必须是本机 dsp_arch 的那一份；缺了就说明下发的包与设备不匹配，
        // 此时**不能**让 QNN 去试——它会在 device 创建阶段失败并留下一堆误导性日志。
        if (!File(directory, SpatialQnnSupport.skelLibraryName(dspArch)).isFile) return null
        if (!File(directory, SpatialQnnSupport.stubLibraryName(dspArch)).isFile) return null
        return QnnEnvironment(
            backendPath = backend,
            dspArch = dspArch,
            runtimePackageVersion = SpatialRuntimeStore.installedPackageVersion(context)
                ?: return null
        )
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
        // 逐模型开关：总开关之上再筛一层，用户可以只给收益大的模型开。
        if (!SpatialPreferences.qnnEnabledFor(context, request.modelId)) {
            return report(request.modelId, null)
        }
        val qnn = resolveEnvironment(context) ?: return report(request.modelId, null)
        val key = SpatialQnnContextStore.Key(
            modelId = request.modelId,
            modelVersion = request.modelVersion,
            modelSha256 = request.modelSha256,
            shapeTag = request.shapeTag,
            dspArch = qnn.dspArch,
            runtimePackageVersion = qnn.runtimePackageVersion
        )

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
                    request.modelId,
                    Outcome(session = session, usedQnn = true, compiled = false)
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
                return report(request.modelId, null)
            }
            return report(
                request.modelId,
                Outcome(session = session, usedQnn = true, compiled = false)
            )
        }

        if (!allowOnDeviceCompile) return report(request.modelId, null)
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
            report(request.modelId, Outcome(session = session, usedQnn = true, compiled = true))
        }.getOrElse { error ->
            Log.w(TAG, "QNN session 创建失败，回落 CPU：${request.modelId}", error)
            runCatching { SpatialQnnContextStore.invalidate(context, request.modelId) }
            report(request.modelId, null)
        }
    }

    /** 统一的回报点：所有 return 路径都经过这里，漏一条就会让界面标注失真。 */
    private fun report(modelId: String, outcome: Outcome?): Outcome? {
        runCatching { sessionListener?.invoke(modelId, outcome?.usedQnn == true) }
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
}
