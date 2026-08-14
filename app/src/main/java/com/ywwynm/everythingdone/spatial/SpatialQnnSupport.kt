package com.ywwynm.everythingdone.spatial

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
        DeviceProfile("SM8850", "v81")  // 8 Elite Gen 5 —— AI Hub
    )

    /** catalog 未提供覆盖表时使用的兜底集合。 */
    fun builtInProfiles(): List<DeviceProfile> = BUILT_IN_PROFILES

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
     * 解析本机 dsp_arch。catalog 表优先于内置表；两边都查不到返回 null（不启用 QNN）。
     */
    fun resolveDspArch(catalogProfiles: List<DeviceProfile>? = null): String? {
        if (Build.VERSION.SDK_INT < MINIMUM_SDK) return null
        if (!currentAbiSupported()) return null
        val socModel = currentSocModel() ?: return null
        return resolveDspArch(socModel, catalogProfiles)
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

    fun skelLibraryName(dspArch: String): String {
        check(isValidDspArch(dspArch)) { "非法 dsp_arch：$dspArch" }
        return "libQnnHtp${dspArch.uppercase()}Skel.so"
    }

    fun stubLibraryName(dspArch: String): String {
        check(isValidDspArch(dspArch)) { "非法 dsp_arch：$dspArch" }
        return "libQnnHtp${dspArch.uppercase()}Stub.so"
    }

    private const val QUALCOMM_SOC_MANUFACTURER = "QTI"
    private val DSP_ARCH_REGEX = Regex("v(6[89]|7[0-9]|8[0-9])")
}
