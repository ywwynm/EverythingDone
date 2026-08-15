package com.ywwynm.everythingdone.spatial

import android.content.Context
import android.os.Build
import androidx.annotation.Keep
import com.ywwynm.everythingdone.BuildConfig
import java.io.File

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
        DeviceProfile("SM8850", "v81")  // 8 Elite Gen 5 —— AI Hub
    )

    /*
     * **`SM8845P`（8 Gen 5，OPPO Pad Mini / OPD2515）是故意不登记的**（2026-08-15，D271）。
     *
     * 取证结论仍然成立、并且记在这里备查：该机 `/odm/lib64/aiframe/` 与
     * `/odm/lib/rfsa/adsp/` 下 OPPO 自己的 AI 框架装的是 `libQnnHtpV81Skel.so` /
     * `libQnnHtpV81Stub.so`，全盘只有 V81 一个版本；硅本身是 v85
     *（QNN 日志 `Setting libnative architecture to v85 (requested arch is v81)`）。
     * 型号串带 P 后缀，查表是全等匹配，写成 `SM8845` 匹配不上。
     *
     * 之所以拿掉：**这台设备是全 arch 兜底路径唯一的活体验证基准。** 登记着它就永远
     * 走"查得到 arch"那条老路，而 D267 想让新骁龙开箱即用的兜底路径直到 D271 才真正
     * 打通——在它身上跑通，等于证明了"没登记的新骁龙也能用"。要恢复省流量的单份包
     * （14.55 MB）随时可以把这一条加回来或走 catalog 覆盖表，不必发版。
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
     * [SpatialQnnArchProbe] 连续失败后写下的标记：组件齐全却建不起 session。
     *
     * **与 [TOO_OLD_ARCH_MARK] 分开是有意的**，尽管两者都意味着不可用。那一个下载前就能
     * 判出来（表或厂商库），这一个只有装完组件才知道——界面处置因此不同，见 [npuVerdict]。
     * 它也**不允许出现在 catalog 覆盖表里**（[isValidProfileArch] 不收）：这是设备本地的
     * 实测结论，不是可以下发的知识。
     */
    const val PROBE_FAILED_MARK = "probe-failed"

    /**
     * 已经出过货的 HTP 架构档位，兜底值。**catalog 的 `qnnRuntimes` 覆盖它**
     * （[saveServedArchs]），所以补发一档新架构不需要用户升级 App。
     *
     * 内置值是本版发布时运行组件的实际覆盖面。不从 [BUILT_IN_PROFILES] 推导——
     * 那张表答的是"这台设备该用哪一档"，这里答的是"哪几档我们真的编出来了"，
     * 两者恰好错开的那部分正是 D270 的故障：8+ Gen 1（`SM8475` → v69）在判定表里
     * 查得到，可我们从来没编过 v69 的包。
     */
    private val BUILT_IN_SERVED_ARCHS = setOf("v73", "v75", "v79", "v81")

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
     * 设置页那一行该怎么显示。**三档，分档标准是"确定不可用时，组件还在不在磁盘上"**
     *（2026-08-15 两轮裁定：D274 定下"下载后才知道的不可用要置灰可删"；同日审查发现
     * 按结论来源分档在两个方向上都会错位——表判 HIDDEN 但组件已装时 192 MB 没有删除
     * 入口，自探 PROBE_FAILED 但组件已删时文案还写着"可删除"——改按组件在位分档，
     * 对任何来源的结论都成立）：
     *
     * | 判定 | 界面 |
     * |---|---|
     * | [NpuVerdict.HIDDEN] | **整行隐藏**——非骁龙、或确定不可用且磁盘上没有 QNN 组件。用户永远用不上也没东西可清理，看见只会困惑 |
     * | [NpuVerdict.UNUSABLE_INSTALLED] | **置灰并说明**——确定不可用但组件还装着（不论结论来自表、厂商库还是自探）。用户得看得见才删得掉 |
     * | [NpuVerdict.USABLE] | 正常显示（能不能用还要再过 catalog 条件） |
     */
    enum class NpuVerdict { USABLE, HIDDEN, UNUSABLE_INSTALLED }

    fun npuVerdict(context: Context): NpuVerdict = npuVerdict(
        candidate = isSnapdragonNpuCandidate(),
        unusable = isKnownTooOldSoc(context) || isArchUnserved(context),
        qnnVariantInstalled = SpatialRuntimeStore.isVariantInstalled(context, qnn = true)
    )

    /** 与设备状态无关的纯函数形式，便于单测。 */
    fun npuVerdict(
        candidate: Boolean,
        unusable: Boolean,
        qnnVariantInstalled: Boolean
    ): NpuVerdict {
        if (!candidate) return NpuVerdict.HIDDEN
        if (!unusable) return NpuVerdict.USABLE
        return if (qnnVariantInstalled) NpuVerdict.UNUSABLE_INSTALLED else NpuVerdict.HIDDEN
    }

    /**
     * 本机的硅**确定**低于我们出过的所有档吗。硅档查不到时返回 false（放行）——
     * 那是新芯片的兜底路径，由全 arch 包接住。
     *
     * 这条判据补的是 D267 的一个不对称：把白名单换成黑名单时，理由是"比我们打包的
     * 硬件更新的芯片也能工作"（OPD2515 实测 v85 的硅用 V81 Skel）。那句话只对**高于**
     * 已发布最高档的芯片成立，对**低于最低档**的不成立——8+ Gen 1 是 v69，我们最低
     * 只编到 v73，没有任何一档能落到它上面。
     */
    fun isArchUnserved(context: Context): Boolean =
        isArchUnserved(hardwareArch(context), servedArchs(context))

    /**
     * 与设备状态无关的纯函数形式，便于单测。
     *
     * "确定不可用" = 硅档已知，且已发布的档里**没有一档落得到它上面**。注意与
     * [resolveDspArch] 是同一个条件的两面：那边取不到能用的档，这边就为 true。
     * 硅档未知（null）或没有已发布档位信息（空集合）时一律放行——一次拉取失败不能
     * 把所有设备判死。
     */
    fun isArchUnserved(hardwareArch: String?, servedArchs: Set<String>): Boolean {
        if (hardwareArch == null) return false
        // 设备自证扫到的全是 v65/v66，或 catalog 覆盖表明确标了不支持。
        if (hardwareArch == TOO_OLD_ARCH_MARK) return true
        // 组件齐全却连着两次建不起 session（D273）。同样是"确定不可用"，只是**界面处置
        // 不同**——见 [npuVerdict]，这一档要置灰而不是隐藏。
        if (hardwareArch == PROBE_FAILED_MARK) return true
        if (!isValidDspArch(hardwareArch)) return false
        if (servedArchs.isEmpty()) return false
        return resolveDspArch(hardwareArch, servedArchs) == null
    }

    /**
     * 门控：本机**有没有可能**跑 QNN。等于"是 Android 12+ 的 arm64 高通设备，
     * 不是确定跑不了的老架构，且它该用的那一档我们真的发布过"。
     * **不要求查得到 dsp_arch**——查不到时由全 arch 包兜底。
     */
    fun isNpuPossible(context: Context): Boolean =
        isSnapdragonNpuCandidate() &&
            !isKnownTooOldSoc(context) &&
            !isArchUnserved(context)

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
     * 本机**硅的** HTP 档。三个来源，依次是 catalog 覆盖表、内置表、设备自证。
     *
     * 设备自证（[probeVendorArch]）是 2026-08-15 补的第三条，也是最硬的一条：厂商在
     * `/vendor` `/odm` 下装的 `libQnnHtpV<N>Skel.so` 就是这颗硅能跑的档，比任何表都准，
     * 而且新芯片出厂当天就有。当初给 SM8845P 定档用的正是这个证据，只是那时是人工去看的。
     *
     * 与 [resolveDspArch] 的分工是这次的关键：**本函数答"硅是哪一档"，那个答"该给它用
     * 我们的哪一份"**。混作一件事的后果刚在 OPD2515 上现了形——把它从判定表里拿掉之后，
     * 一台明明装着 V81 Skel、我们也编了 v81 产物的设备，Big-LaMa（NPU 版）却整行消失了。
     */
    fun hardwareArch(context: Context): String? {
        if (Build.VERSION.SDK_INT < MINIMUM_SDK) return null
        if (!currentAbiSupported()) return null
        val socModel = currentSocModel() ?: return null
        // Debug 旁路：强制前两条来源失效，只留 QNN 自探那一路。有 vendor 库的机器
        // （OPD2515）本来永远走不到自探，这是唯一能在真机上验证它的办法——而且那台机
        // 的正确答案由 vendor 库独立给出（v81），正好当交叉校验的基准。
        if (debugForceProbePath(context)) return probedArch(context)
        return hardwareArch(socModel, catalogProfiles(context))
            ?: probeVendorArch()
            // 第三条：让 QNN 自己挑一次再从 /proc/self/maps 读回来（[SpatialQnnArchProbe]）。
            // 排在最后不是因为它最不准——恰恰相反，它是 QNN 亲自做的选择——而是因为它最贵：
            // 要先装好运行组件、还要建一次 session。前两条能答就不必走到这里。
            ?: probedArch(context)
    }

    /** 查表部分的纯函数形式，便于单测。设备自证那一路要真机文件系统，不在此列。 */
    fun hardwareArch(
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
     * 本机该用**我们发布的哪一份**：已发布档位里不超过硅档的最高一档。
     *
     * 这正是 QNN 自己的选法——D267 在 OPD2515 上实测，四份 Skel 同在时它挑了 V81，
     * 而硅是 v85（`Setting libnative architecture to v85 (requested arch is v81)`）。
     * 判定跟着它走，两边才不会打架：拼出来的 `libQnnHtpV<arch>Skel.so` 一定在包里，
     * 按这一档去取的预编译产物也一定是能加载的那一份。
     *
     * 返回 null 有两种情形，调用方要分清（用 [isArchUnserved] 区分）：
     * - **硅档未知**——走全 arch 包兜底，预编译产物没法取（不知道该取哪一份）；
     * - **硅档低于我们出过的所有档**（8+ Gen 1 的 v69）——整个 NPU 不可用。
     */
    fun resolveDspArch(context: Context): String? =
        resolveDspArch(hardwareArch(context), servedArchs(context))

    /** 与设备状态无关的纯函数形式，便于单测。 */
    fun resolveDspArch(hardwareArch: String?, servedArchs: Set<String>): String? {
        val hardware = archLevel(hardwareArch ?: return null) ?: return null
        return servedArchs
            .mapNotNull { arch -> archLevel(arch)?.takeIf { it <= hardware }?.let { it to arch } }
            .maxByOrNull { it.first }
            ?.second
    }

    /**
     * 扫厂商分区里的 QNN Skel/Stub，取最高的一档。装了多档时取最高——那是这颗硅的上限。
     *
     * 结果按进程缓存：判定在设置页每次刷新都要跑，而硬件不会变。列目录本身不受 linker
     * namespace 限制（那管的是 dlopen），只受文件权限，`/vendor` `/odm` 的库目录对普通
     * 应用可读。取不到就返回 null，照旧退回全 arch 包。
     */
    private fun probeVendorArch(): String? {
        probedVendorArch?.let { return it.value }
        val highest = VENDOR_LIBRARY_DIRECTORIES
            .asSequence()
            .flatMap { runCatching { File(it).list()?.asSequence() }.getOrNull() ?: emptySequence() }
            .mapNotNull { VENDOR_LIBRARY_REGEX.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull()
        val probed = when {
            highest == null -> null
            // 装的全是 v65/v66 那一代——QNN runtime 根本没有它们的后端。按"确定不支持"
            // 处理，而不是当作没探到：没探到会退回全 arch 包，让用户白下 63 MB。
            highest < MIN_PROBED_ARCH_LEVEL -> TOO_OLD_ARCH_MARK
            highest > MAX_PROBED_ARCH_LEVEL -> null
            else -> "v$highest".takeIf(::isValidDspArch)
        }
        probedVendorArch = Probed(probed)
        return probed
    }

    private class Probed(val value: String?)

    @Volatile
    private var probedVendorArch: Probed? = null

    private fun archLevel(value: String): Int? =
        value.takeIf(::isValidDspArch)?.drop(1)?.toIntOrNull()

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

    /**
     * 把 catalog 里**真的发布了单份运行组件**的那些档快照下来。只在验签通过后调用。
     *
     * 不新开一个 catalog 字段、而是从 `qnnRuntimes` 现推：多一个字段就多一处会忘记同步的
     * 地方——补发一档包却没改那个字段的话，判定会把新架构继续判成不可用，而这正是本条
     * 判据要防的故障。传 null 或空表示没问到，此时[servedArchs] 回退内置集合。
     */
    fun saveServedArchs(context: Context, archs: List<String>?) {
        val encoded = archs.orEmpty()
            .filter(::isValidDspArch)
            .distinct()
            .joinToString(ENTRY_SEPARATOR)
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVED_ARCHS, encoded)
            .apply()
    }

    /**
     * 读回快照；没有快照就用内置集合兜底。与 [catalogProfiles] 一样**读的时候重新校验**。
     *
     * 空集合的含义是"无从判断"，[isArchUnserved] 对它一律放行——绝不能让一次拉取失败
     * 把所有设备的 NPU 判成不可用。
     */
    fun servedArchs(context: Context): Set<String> {
        val encoded = context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SERVED_ARCHS, null)
        if (encoded.isNullOrEmpty()) return BUILT_IN_SERVED_ARCHS
        val parsed = encoded.split(ENTRY_SEPARATOR).filter(::isValidDspArch).toSet()
        return parsed.ifEmpty { BUILT_IN_SERVED_ARCHS }
    }

    /** catalog 未提供时使用的兜底集合。 */
    fun builtInServedArchs(): Set<String> = BUILT_IN_SERVED_ARCHS

    /**
     * [SpatialQnnArchProbe] 的结论：某一档、[PROBE_FAILED_MARK]（组件齐全却起不来，
     * 确定用不了），或 null（还没结论）。
     *
     * 与另外两条来源同一份快照，读的时候一样重新校验——真实档位那一支会拼进
     * `libQnnHtpV<arch>Skel.so`，而 [PROBE_FAILED_MARK] 永远形不成文件名
     *（[isValidProfileArch] 收两者，[isValidDspArch] 只收前者）。
     *
     * **失败结论在读取侧还要过一道新鲜度**：它是跟着某一份运行组件得出的，App 升级换了
     * [SpatialRuntimeStore.QNN_PACKAGE_VERSION] 之后必须自动失效——写入侧的作废逻辑在
     * [SpatialQnnArchProbe.probeIfNeeded] 里，而 PROBE_FAILED 设备的总开关已被收口关掉，
     * 那条路第一行就返回，永远走不到作废；只靠写入侧的话，行会一直隐藏，设备没有任何
     * 入口用新组件重试（2026-08-15 审查发现）。真实档位是硬件事实，不受此限。
     */
    fun probedArch(context: Context): String? {
        val preferences = context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val value = preferences.getString(KEY_PROBED_ARCH, null) ?: return null
        if (value == PROBE_FAILED_MARK) {
            val probedWith = preferences.getString(KEY_PROBE_RUNTIME_VERSION, null)
            return value.takeIf { probedWith == SpatialRuntimeStore.QNN_PACKAGE_VERSION }
        }
        return value.takeIf(::isValidDspArch)
    }

    /**
     * 只在 debug 构建存在，release 恒为 false。用**标记文件**而不是 SharedPreferences：
     * 开关只需 `run-as <pkg> touch no_backup/spatial-photo/.debug-force-probe`，
     * 不必跟 sed 改 XML 的引号较劲。
     */
    private fun debugForceProbePath(context: Context): Boolean =
        BuildConfig.DEBUG &&
            File(context.applicationContext.noBackupFilesDir, DEBUG_FORCE_PROBE_MARKER).exists()

    fun saveProbedArch(context: Context, arch: String?) {
        val valid = arch?.takeIf { isValidDspArch(it) || it == PROBE_FAILED_MARK }
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .apply { if (valid == null) remove(KEY_PROBED_ARCH) else putString(KEY_PROBED_ARCH, valid) }
            .apply()
    }

    private const val QUALCOMM_SOC_MANUFACTURER = "QTI"
    private val DSP_ARCH_REGEX = Regex("v(6[89]|7[0-9]|8[0-9])")

    /**
     * 厂商装 QNN 库的地方。OPD2515 上 `libQnnHtpV81Skel.so` 在 `/odm/lib/rfsa/adsp/`、
     * `libQnnHtpV81Stub.so` 在 `/vendor/lib64/` 与 `/odm/lib64/aiframe/`——各家路径不一，
     * 全扫一遍，取到哪个算哪个。
     */
    private val VENDOR_LIBRARY_DIRECTORIES = listOf(
        "/vendor/lib64",
        "/vendor/lib64/rfsa/adsp",
        "/vendor/lib/rfsa/adsp",
        "/odm/lib64",
        "/odm/lib64/aiframe",
        "/odm/lib/rfsa/adsp"
    )

    /**
     * 只认 Skel/Stub 这两种。同目录下还有 `libQnnHtpV81.so`、
     * `libQnnHtpV81CalculatorStub.so` 之类，它们不是架构标识，不能算进来。
     */
    private val VENDOR_LIBRARY_REGEX = Regex("""^libQnnHtpV(\d{2})(?:Skel|Stub)\.so$""")

    /** 探测结果仍要落在白名单范围内，厂商目录里的任意文件名不能直接变成路径片段。 */
    private const val MIN_PROBED_ARCH_LEVEL = 68
    private const val MAX_PROBED_ARCH_LEVEL = 89
    private val SOC_MODEL_REGEX = Regex("[A-Za-z0-9_-]+")
    private const val MAX_SOC_MODEL_LENGTH = 32
    private const val PREFERENCES_NAME = "spatial_qnn_profiles"
    private const val KEY_PROFILES = "catalog_profiles"
    private const val KEY_SERVED_ARCHS = "catalog_served_archs"
    private const val KEY_PROBED_ARCH = "probed_arch"

    /** [SpatialQnnArchProbe] 写、这里读（失败结论的新鲜度校验），必须共用同一个键名。 */
    const val KEY_PROBE_RUNTIME_VERSION = "probe_runtime_version"
    private const val DEBUG_FORCE_PROBE_MARKER = "spatial-photo/.debug-force-probe"
    private const val ENTRY_SEPARATOR = ";"
    private const val FIELD_SEPARATOR = "="
}
