package com.ywwynm.everythingdone.spatial

import android.content.Context
import android.os.Build
import androidx.annotation.Keep

/**
 * 高通 NPU（Hexagon HTP）可用性判定。
 *
 * 判定只回答一件事：**这台设备该不该下载 QNN 运行组件、下载哪一份**。
 * 是否真的接管了节点由 [SpatialQnnRuntimeStore] 加载后的实际运行结果决定，
 * 判定为可用不等于一定能跑——D217 的教训是"四条证据全绿也可能一个节点都没落到 QNN"。
 *
 * ## 为什么需要 dsp_arch
 *
 * ORT 的 QNN EP **不会**自己推断 `soc_model` / `htp_arch`（默认传 UNKNOWN/NONE），
 * QNN 库自己会探测出 SoC 并去加载 `libQnnHtpV<arch>Stub.so`。所以 EP 侧不需要我们告知，
 * 但**分发侧需要**：每个 dsp_arch 的 Skel 约 10–19 MB（D215），只能按机下发一份。
 *
 * ## 为什么表可以由 catalog 覆盖
 *
 * 新 SoC 上市不该逼用户升级 App。内置表只作兜底，catalog 里的
 * [SpatialModelCatalog.qnnDeviceProfiles] 优先。查不到就**不启用**（fail-closed），
 * 而不是猜一个最接近的——猜错会下载一份根本加载不了的 Skel。
 *
 * catalog 表由 [SpatialCatalogClient.fetchOrCached] 在**验签通过之后**快照进
 * SharedPreferences（[saveCatalogProfiles]），[resolveDspArch] 同步读快照。不直接读
 * catalog 文件：判定在设置页每次刷新都要跑，扛不住磁盘 I/O 与验签。
 *
 * ## 判定必须全进程一致
 *
 * dsp_arch 决定了下载哪一份 Skel、取哪一份 context binary，也是
 * [SpatialRuntimeStore] 判断已装组件是否过期的依据。若有的调用点带 catalog 表、
 * 有的不带，同一台设备会得到两个答案，表现为"装好了却判定没装"。所以设备态的
 * [resolveDspArch] **只有带 Context 这一个形式**，不提供无参重载。
 */
object SpatialQnnSupport {

    @Keep
    data class DeviceProfile(
        val socModel: String,
        val dspArch: String
    )

    /** QNN 只提供 arm64-v8a 的库。 */
    const val REQUIRED_ABI = "arm64-v8a"

    /**
     * `<uses-native-library>` 是 API 31 引入的；没有它就拿不到 vendor 的
     * `libcdsprpc.so`，HTP 必然起不来（D217）。
     */
    const val MINIMUM_SDK = Build.VERSION_CODES.S

    /**
     * 内置兜底表。**没把握的一律不写**——查不到就不启用 NPU，而不是猜一个最接近的。
     *
     * 标 `AI Hub` 的条目取自 Qualcomm AI Hub 的设备属性（`hexagon:` 与 `chipset:`），
     * 是官方权威数据而非二手资料；这张表可以随时用下面这段重新生成：
     *
     * ```python
     * import qai_hub as hub
     * for d in hub.get_devices():
     *     print(d.name, [a for a in d.attributes if a.startswith(('chipset:', 'hexagon:'))])
     * ```
     *
     * 不登记 v65/v66（sdm845/sm8150/sm7250 等）：QNN runtime 只提供 V68 及以上的 Skel，
     * 这些芯片没有可用的 HTP 后端，[DSP_ARCH_REGEX] 也会挡掉。
     */
    private val BUILT_IN_PROFILES = listOf(
        DeviceProfile("SM7325", "v68"), // 骁龙 778G —— AI Hub
        DeviceProfile("SM8350", "v68"), // 骁龙 888 —— AI Hub
        DeviceProfile("SM8450", "v69"), // 8 Gen 1 —— AI Hub
        DeviceProfile("SM8475", "v69"), // 8+ Gen 1（8 Gen 1 超频版，AI Hub 无条目）
        DeviceProfile("SM7750", "v73"), // 骁龙 7 Gen 4 —— AI Hub
        DeviceProfile("SM8550", "v73"), // 8 Gen 2 —— AI Hub + R5CW20BLNKL 真机实测（D217）
        DeviceProfile("SM8635", "v73"), // 8s Gen 3（AI Hub 无条目）
        DeviceProfile("SM8650", "v75"), // 8 Gen 3 —— AI Hub
        DeviceProfile("SM8750", "v79"), // 8 Elite —— AI Hub
        DeviceProfile("SM8845P", "v81"), // 8 Gen 5 —— OPD2515 真机实证，见下
        DeviceProfile("SM8850", "v81")  // 8 Elite Gen 5 —— AI Hub
    )

    /*
     * `SM8845P` 的来源与其余条目不同，单独记一笔（2026-08-15）：
     *
     * AI Hub 至今没有 8 Gen 5 的条目，公开资料也无一致口径，所以此前按 fail-closed 留空，
     * 后果是 OPD2515（OPPO Pad Mini）上整块 NPU 设置不可见。本轮直接向设备取证——OPPO 自己
     * 的 AI 框架在 `/odm/lib64/aiframe/` 与 `/odm/lib/rfsa/adsp/` 下装的是
     * `libQnnHtpV81Skel.so` / `libQnnHtpV81Stub.so`，且全盘只有 V81 这一个版本，
     * 故 dsp_arch = v81。这是设备自带的 QNN 库，比任何二手资料都硬。
     *
     * 型号串是 **`SM8845P`**（带 P 后缀），不是资料里写的 `SM8845`；查表是全等匹配，
     * 登记成 `SM8845` 匹配不上。不登记裸 `SM8845`：手上没有那样上报的设备，
     * 按 fail-closed 原则不猜。真遇到了走 catalog 补，不必发版。
     */

    /**
     * 确定跑不了的老架构。**这是白名单换成黑名单之后唯一还按型号串判断的地方。**
     *
     * QNN runtime 只提供 V68 及以上的 Skel，v65/v66 那一档没有可用后端。名单取自
     * Qualcomm AI Hub 的 `hexagon:` 属性（`hub.get_devices()`），是**封闭集合**——
     * 高通不会再出新的 v65/v66 芯片，所以它不像白名单那样会腐化。
     *
     * ## 为什么这里漏判是安全的
     *
     * 白名单漏判一颗新芯片 = 功能永远不可见（D266 的故障）；黑名单漏判一颗老芯片 =
     * 用户白下一次组件，QNN 起不来后回落 CPU。**极性反过来之后，"不认识"的默认
     * 从"禁止"变成了"放行"**，而新芯片恰恰是最需要 NPU 的那批。
     */
    private val KNOWN_TOO_OLD_SOC_MODELS = setOf(
        "SDM670",   // v65
        "SDM845",   // v65
        "SM6150",   // v66，AI Hub 记为 sm6150-ac
        "SM7250",   // v66
        "SM8150",   // v66
        "SM8250"    // v66，AI Hub 记为 sm8250-ab
    )

    /**
     * catalog 可以补充黑名单——万一有 v65/v66 的型号串我们没登记，不必发版才能挡住。
     * 与 [DeviceProfile] 走同一份快照，用 [TOO_OLD_ARCH_MARK] 作 dspArch 标记。
     */
    const val TOO_OLD_ARCH_MARK = "unsupported"

    /**
     * 全 arch 包的 dspArch 取值。这样的包里带着**每一档**的 Skel+Stub，
     * 由 QNN 自己按探测到的 SoC 挑，因此不需要事先知道本机 arch。
     *
     * 实测（OPD2515，2026-08-15）：四份 Skel/Stub 同时在库目录时，QNN 只 dlopen 了
     * `libQnnHtpV81Stub.so`，日志为 `min_arch = 81`、`soc_type = SM8845`。而且这颗芯片
     * 的硅本身是 **v85**（`Setting libnative architecture to v85 (requested arch is v81)`），
     * 说明 QNN 取的是"不超过硬件档的最高可用 Skel"——**比硬件更新的芯片也能工作**，
     * 这正是判定表可以退居可选优化的依据。
     */
    const val ALL_ARCH = "all"

    /** catalog 未提供覆盖表时使用的兜底集合。 */
    fun builtInProfiles(): List<DeviceProfile> = BUILT_IN_PROFILES

    /**
     * 本机是不是**确定**跑不了 QNN 的老架构。查不到一律返回 false（放行）。
     */
    fun isKnownTooOldSoc(context: Context): Boolean {
        val socModel = currentSocModel() ?: return false
        if (catalogProfiles(context).any {
                it.socModel.equals(socModel, ignoreCase = true) &&
                    it.dspArch == TOO_OLD_ARCH_MARK
            }
        ) {
            return true
        }
        // 型号串常带后缀（SM8845P、sm8250-ab），老芯片按前缀匹配即可——
        // 这里判"确定不支持"，前缀命中说明就是那一代硅。
        val upper = socModel.uppercase()
        return KNOWN_TOO_OLD_SOC_MODELS.any { upper.startsWith(it) }
    }

    /**
     * 门控：本机**有没有可能**跑 QNN。等于"是 Android 12+ 的 arm64 高通设备，
     * 且不是确定跑不了的老架构"。**不再要求查得到 dsp_arch**——查不到时由全 arch 包兜底。
     */
    fun isNpuPossible(context: Context): Boolean =
        isSnapdragonNpuCandidate() && !isKnownTooOldSoc(context)

    fun currentAbiSupported(): Boolean =
        Build.SUPPORTED_ABIS.firstOrNull() == REQUIRED_ABI

    fun currentSocModel(): String? {
        if (Build.VERSION.SDK_INT < MINIMUM_SDK) return null
        val manufacturer = Build.SOC_MANUFACTURER
        val model = Build.SOC_MODEL
        if (!manufacturer.equals(QUALCOMM_SOC_MANUFACTURER, ignoreCase = true)) return null
        if (model.isBlank() || model == Build.UNKNOWN) return null
        return model
    }

    /**
     * 本机**有没有可能**带骁龙 NPU——即"是 Android 12+ 的 arm64 高通设备"。
     *
     * 与 [resolveDspArch] 的区别是设置页要分三档显示，而不是两档：
     *
     * | 判定 | 界面 |
     * |---|---|
     * | 本函数为 false（联发科/Exynos/RK 等，或非 arm64，或 API < 31） | **整行隐藏**——这类机器上"骁龙 NPU 加速"不是一个有意义的概念 |
     * | 本函数为 true 但 [resolveDspArch] 为 null | **显示并置灰**，写明不支持 |
     * | 两者皆成立 | 正常可用 |
     *
     * 中间那一档此前也走隐藏，后果是 `spatial_qnn_unsupported` 这条文案**永远渲染不出来**，
     * 一颗没登记的骁龙（OPD2515 的 SM8845P）在界面上与"不支持 NPU"毫无区别，
     * 只能靠用户肉眼发现来报（2026-08-15）。
     */
    fun isSnapdragonNpuCandidate(): Boolean =
        currentAbiSupported() && currentSocModel() != null

    /**
     * 解析本机 dsp_arch。catalog 表优先于内置表；两边都查不到返回 null（不启用 QNN）。
     */
    fun resolveDspArch(context: Context): String? {
        if (Build.VERSION.SDK_INT < MINIMUM_SDK) return null
        if (!currentAbiSupported()) return null
        val socModel = currentSocModel() ?: return null
        return resolveDspArch(socModel, catalogProfiles(context))
    }

    /** 与设备状态无关的纯函数形式，便于单测。 */
    fun resolveDspArch(
        socModel: String,
        catalogProfiles: List<DeviceProfile>?
    ): String? {
        val fromCatalog = catalogProfiles
            ?.firstOrNull { it.socModel.equals(socModel, ignoreCase = true) }
            ?.dspArch
            ?.takeIf(::isValidDspArch)
        if (fromCatalog != null) return fromCatalog
        return BUILT_IN_PROFILES
            .firstOrNull { it.socModel.equals(socModel, ignoreCase = true) }
            ?.dspArch
    }

    /**
     * dsp_arch 会拼进下载下来的库文件名（`libQnnHtpV73Skel.so`），必须按白名单校验，
     * 不能让 catalog 里的任意字符串进入路径。
     */
    fun isValidDspArch(value: String): Boolean =
        DSP_ARCH_REGEX.matches(value)

    /**
     * 覆盖表里允许出现的 dspArch 取值：真实架构，或"确定不支持"标记。
     *
     * 与 [isValidDspArch] **必须分开**：后者的取值会拼进 `libQnnHtpV<arch>Skel.so`，
     * 放宽了就等于放宽路径。[TOO_OLD_ARCH_MARK] 只用于查表判定，永远不会形成文件名——
     * [resolveDspArch] 对 catalog 值仍按 [isValidDspArch] 过滤。
     */
    fun isValidProfileArch(value: String): Boolean =
        isValidDspArch(value) || value == TOO_OLD_ARCH_MARK

    fun skelLibraryName(dspArch: String): String {
        check(isValidDspArch(dspArch)) { "非法 dsp_arch：$dspArch" }
        return "libQnnHtp${dspArch.uppercase()}Skel.so"
    }

    fun stubLibraryName(dspArch: String): String {
        check(isValidDspArch(dspArch)) { "非法 dsp_arch：$dspArch" }
        return "libQnnHtp${dspArch.uppercase()}Stub.so"
    }

    /**
     * SoC 型号的合法字符集。型号本身不拼进路径（拼路径的是 dsp_arch），但它会被写进
     * SharedPreferences 的分隔串里，必须挡住分隔符本身。真实取值形如 `SM8550`、`SM8845P`。
     */
    fun isValidSocModel(value: String): Boolean =
        value.length <= MAX_SOC_MODEL_LENGTH && SOC_MODEL_REGEX.matches(value)

    /**
     * 把 catalog 里的覆盖表快照下来。只在 catalog **验签通过**后调用。
     *
     * 传 null 或空表示这份 catalog 没带覆盖表，此时**清空快照**而不是保留旧的：
     * 撤下一条错误的映射必须能立即生效，否则发错了就只能等 App 升级。
     */
    fun saveCatalogProfiles(context: Context, profiles: List<DeviceProfile>?) {
        val encoded = profiles.orEmpty()
            .filter { isValidSocModel(it.socModel) && isValidProfileArch(it.dspArch) }
            .joinToString(ENTRY_SEPARATOR) { "${it.socModel}$FIELD_SEPARATOR${it.dspArch}" }
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROFILES, encoded)
            .apply()
    }

    /**
     * 读回快照。**读的时候重新校验一遍**：SharedPreferences 在已 root 的设备上可写，
     * 而这里解析出来的 dsp_arch 会拼进库文件名。
     */
    fun catalogProfiles(context: Context): List<DeviceProfile> {
        val encoded = context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PROFILES, null)
        if (encoded.isNullOrEmpty()) return emptyList()
        return encoded.split(ENTRY_SEPARATOR).mapNotNull { entry ->
            val fields = entry.split(FIELD_SEPARATOR)
            if (fields.size != 2) return@mapNotNull null
            val socModel = fields[0]
            val dspArch = fields[1]
            if (!isValidSocModel(socModel) || !isValidProfileArch(dspArch)) return@mapNotNull null
            DeviceProfile(socModel, dspArch)
        }
    }

    private const val QUALCOMM_SOC_MANUFACTURER = "QTI"
    private val DSP_ARCH_REGEX = Regex("v(6[89]|7[0-9]|8[0-9])")
    private val SOC_MODEL_REGEX = Regex("[A-Za-z0-9_-]+")
    private const val MAX_SOC_MODEL_LENGTH = 32
    private const val PREFERENCES_NAME = "spatial_qnn_profiles"
    private const val KEY_PROFILES = "catalog_profiles"
    private const val ENTRY_SEPARATOR = ";"
    private const val FIELD_SEPARATOR = "="
}
