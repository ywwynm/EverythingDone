package com.ywwynm.everythingdone.spatial

import android.content.Context

/**
 * 「这台机上这个模型的 QNN 图**执行期**失败过」的落盘记录。
 *
 * [SpatialQnnSessionFactory] 原本只在**建 session** 失败时回落 CPU。但失败可以晚得多：
 * OPD2515 上 Big-LaMa 的 v81 预编译 context 建 session 正常，第一次 `QnnGraph_execute`
 * 超过 10 秒，CDSP 用户 PD 被看门狗打死，ORT 才报出
 * `QNN graph execute error. Error code: 1003`（D276）。异常一路穿到界面，
 * 结果是那台平板一张空间照片都生成不出来——而 NPU 本该只是加速手段。
 *
 * 记住结论是必须的：不记的话每次生成都要重新白等一次 session 创建加十秒看门狗。
 *
 * ## 连续 [FAILURE_THRESHOLD] 次才下结论
 *
 * 与 D273 的自探同一口径：**偶发的失败不该变成永久判决**。CDSP 是全机共享的（OPD2515 上
 * OPPO 自己的 AI 框架就在用），别的进程占着资源导致的一次失败，不足以证明这台机跑不了
 * 这个模型。跑通一次就把连续计数清零——"连续"才有意义。
 *
 * 代价是这类机器要白等两轮才收敛，这是用户裁定接受的（2026-08-15）。
 *
 * ## 作废条件
 *
 * 记的是 [SpatialQnnContextStore.Key] 的完整指纹（模型 id/版本/字节/形状档/HTP 架构/
 * 运行组件版本），任何一维变了就当没有结论、重新试一次。**这与 D273 探测结论的作废
 * 口径一致**：结论是跟着那一份模型与那一份运行组件得出的。
 *
 * 指纹管的是**运行层**的作废。设置页读取侧（[isUnusable]）只有（架构，组件版本）两维——
 * 拿不到模型版本、字节与形状档，模型字节换新后若不显式清除，那一行会保持置灰直到
 * 下一次生成在 QNN 上跑通。因此分割/边界/补全模型仓库与预编译产物仓库的
 * `installVerified` 和 `delete` 都显式调用 [clear]：字节换了或产物没了，结论都不该留着。
 * 有源码契约测试钉住这四处。
 */
object SpatialQnnExecutionBlocklist {

    /** 连续失败到几次才认定这台机跑不了。低于此值只回落当次，不留永久结论。 */
    const val FAILURE_THRESHOLD = 2

    /** 这一份模型 + 这一份运行组件，在本机已被判定执行期不可用吗。 */
    fun isBlocked(context: Context, key: SpatialQnnContextStore.Key): Boolean =
        settled(failures(context, key.modelId)) &&
            preferences(context).getString(fingerprintKey(key.modelId), null) == fingerprint(key)

    /**
     * 记一次执行期失败。
     *
     * @return 这一次之后是否已达阈值（true = 此后不再尝试 NPU）。
     */
    fun recordFailure(context: Context, key: SpatialQnnContextStore.Key): Boolean {
        val print = fingerprint(key)
        val next = nextFailureCount(
            previousFingerprint = preferences(context).getString(fingerprintKey(key.modelId), null),
            previousCount = failures(context, key.modelId),
            fingerprint = print
        )
        preferences(context).edit()
            .putString(fingerprintKey(key.modelId), print)
            .putString(environmentKey(key.modelId), environment(key.dspArch, key.runtimePackageVersion))
            .putInt(failuresKey(key.modelId), next)
            .apply()
        return settled(next)
    }

    /**
     * QNN 上跑通了一次。**必须调**：不清零的话，两次相隔很远的偶发失败会累加成永久判决，
     * "连续"就名存实亡了。
     */
    fun recordSuccess(context: Context, modelId: String) {
        if (preferences(context).contains(failuresKey(modelId))) clear(context, modelId)
    }

    /** 换了预编译产物、删了模型时调用：结论跟着产物走，产物没了结论也不该留着。 */
    fun clear(context: Context, modelId: String) {
        preferences(context).edit()
            .remove(fingerprintKey(modelId))
            .remove(environmentKey(modelId))
            .remove(failuresKey(modelId))
            .apply()
    }

    fun clearAll(context: Context) {
        preferences(context).edit().clear().apply()
    }

    /**
     * 供设置页用：这个模型的 NPU 版在本机是否已判定跑不起来。
     *
     * **读取侧必须自己校验新鲜度**（D275 补的教训：写入侧的作废条件够不着已经收口的那条
     * 路，旧结论会永远清不掉）。设置页拿不到 `shapeTag`（那是引擎内部的事），因此这里比
     * 的是单独存下来的 `(dspArch, 运行组件版本)`——这两维正是"换一份组件就该重试"的含义
     * 所在；模型维（升版、换字节、删除）由四个模型仓库安装/删除点的 [clear] 兜住。
     */
    fun isUnusable(
        context: Context,
        modelId: String,
        dspArch: String?,
        runtimePackageVersion: String?
    ): Boolean {
        if (dspArch == null || runtimePackageVersion == null) return false
        if (!settled(failures(context, modelId))) return false
        return preferences(context).getString(environmentKey(modelId), null) ==
            environment(dspArch, runtimePackageVersion)
    }

    internal fun failures(context: Context, modelId: String): Int =
        preferences(context).getInt(failuresKey(modelId), 0)

    /**
     * 这一次失败之后连续计数是多少。**指纹对不上就从头数**：此前那几次是对另一份模型或
     * 另一份运行组件的判断，累加过去等于拿旧账算新账。
     */
    internal fun nextFailureCount(
        previousFingerprint: String?,
        previousCount: Int,
        fingerprint: String
    ): Int = if (previousFingerprint == fingerprint) previousCount + 1 else 1

    /** 连续计数到这个数就不再尝试 NPU。 */
    internal fun settled(failures: Int): Boolean = failures >= FAILURE_THRESHOLD

    /**
     * 直接复用 context 缓存的目录名做指纹：它本来就是"对特定模型字节、特定 HTP 架构、
     * 特定运行组件"这层含义的既有编码，两处各写一份必然会漂移。
     */
    internal fun fingerprint(key: SpatialQnnContextStore.Key): String =
        SpatialQnnContextStore.directoryName(key)

    internal fun environment(dspArch: String, runtimePackageVersion: String): String =
        "$dspArch|$runtimePackageVersion"

    private fun fingerprintKey(modelId: String) = KEY_PREFIX + modelId
    private fun environmentKey(modelId: String) = ENVIRONMENT_PREFIX + modelId
    private fun failuresKey(modelId: String) = FAILURES_PREFIX + modelId

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private const val PREFERENCES_NAME = "spatial_qnn_execution"
    private const val KEY_PREFIX = "execute_failed_"
    private const val ENVIRONMENT_PREFIX = "execute_env_"
    private const val FAILURES_PREFIX = "execute_failures_"
}
