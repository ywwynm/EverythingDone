package com.ywwynm.everythingdone.views.particledismiss

import android.content.Context
import android.content.SharedPreferences
import com.ywwynm.everythingdone.BuildConfig
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 粒子动画的四项全局设置：**粒子大小**、**流动精细度**、**颜色精度**与**性能自动调节**。
 *
 * 四项都作用于全部粒子路径（弹窗、页内对话框、创建页、左滑卡片），存在自己的
 * SharedPreferences 文件里。粒子大小存的是**档位下标**（档位以后要调整时，旧值不会变成一个
 * 不在档位表里的数）；流动精细度存的是**取值本身**（它是 [FLOW_MIN]–[FLOW_MAX] 的连续整数，
 * 没有档位表）。
 *
 * 与 [ParticlePerfTuning] 的关系：那个对象是进程里唯一的读点，本对象只负责
 * 「从设置读出来、推给它」。debug 包的 A/B 配置文件写过同名键时以文件为准
 * （见 [ParticlePerfTuning.applyUserTuning]）。
 */
internal object ParticleTuning {

    const val PREFS_NAME = "particle_tuning"

    /**
     * 粒子大小的档位键。
     *
     * 键名与更早那一版（九档梯度）不同是**有意的**：两版的下标含义完全不同，沿用同一个键
     * 会把旧下标读成新梯度上的另一个档位。旧键的值就此作废，等同于「未设置」，
     * 也就是回到默认行为。
     */
    const val KEY_CELL_INDEX = "particle_size_dp_index"

    /**
     * 流动精细度的键。
     *
     * 键名与上一版（六档下标 `jacobi_iterations_index`）不同同样是**有意的**：这一版存的是
     * 取值本身（0–100），沿用同一个键会把「第 2 档」读成「2」。迁移见 [migrateLegacyFlow]。
     */
    const val KEY_FLOW_DETAIL = "flow_detail"

    /** 颜色精度：true = 标准（8 位定点），false = 高精度（16 位浮点，默认）。 */
    const val KEY_COLOR_STANDARD = "color_precision_standard"

    /** 性能自动调节：连续掉帧时自动降低粒子大小与流动精细度。默认关。 */
    const val KEY_AUTO_TUNE = "auto_performance_tuning"

    /** 粒子样式：`flow`（流体，原有实现）或 `drift`（飘散，缺省）。见 [ParticleStyle]。 */
    const val KEY_STYLE = "particle_style"

    /**
     * 飘散样式的「粒子数量」档位下标，倍率乘在按面积换算、截到 1～5 万之后的粒子数上。
     *
     * 键名与上一版（`drift_particle_amount`，0.5×～1.5× 的十一档）不同是**有意的**：两版的下标含义不同，
     * 沿用同一个键会把旧的「1.2×」读成新梯度上的 0.9×。迁移见 [migrateLegacyDriftAmount]。
     */
    const val KEY_DRIFT_AMOUNT = "drift_amount_index"

    /** 飘散样式的「粒子大小」档位下标（倍率乘在颗粒半宽上，1× 即源码大小）。 */
    const val KEY_DRIFT_SIZE = "drift_size_index"

    /** 仅调试包：钉住某段参考样式的名字，或 `random`（一律随机生成）。见 [ParticleDriftStyles.forcedForTests]。 */
    const val KEY_DEBUG_FORCE_DRIFT_STYLE = "debug_drift_force_style"

    /** 仅调试包：飘散零拷贝时把导入的纹理与样本逐字节核对一遍（慢）。见 [ParticleDriftHardwareImport.verifyForTests]。 */
    const val KEY_DEBUG_VERIFY_ZERO_COPY = "debug_drift_verify_zero_copy"

    /** 「粒子数量」的档位（倍率）：0.2×～1.6×，每档 0.1（用户 2026-10-06 定的范围）。 */
    val DRIFT_AMOUNT_LEVELS = floatArrayOf(
        .2f, .3f, .4f, .5f, .6f, .7f, .8f, .9f, 1f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f, 1.6f)
    const val DEFAULT_DRIFT_AMOUNT_INDEX = 8

    /**
     * 「粒子大小」的档位（倍率）：0.5×～2.0×，每档 0.1，与流体的粒子大小一样是十六档。
     * 源码的颗粒半径是定值（工作面 3.5 ± 0.5 像素），没有按面积自动计算的环节，所以缺省就是源码大小 1.0×。
     */
    val DRIFT_SIZE_LEVELS = floatArrayOf(
        .5f, .6f, .7f, .8f, .9f, 1f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f, 1.6f, 1.7f, 1.8f, 1.9f, 2f)
    const val DEFAULT_DRIFT_SIZE_INDEX = 5

    /** 上一版「粒子数量」的键与它的十一档梯度，只用于迁移。 */
    private const val LEGACY_KEY_DRIFT_AMOUNT = "drift_particle_amount"
    private val LEGACY_DRIFT_AMOUNT_LEVELS = floatArrayOf(.5f, .6f, .7f, .8f, .9f, 1f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f)

    /** 更早那一版的粒子大小键与它的九档梯度，只用于迁移。 */
    private const val LEGACY_KEY_CELL_INDEX = "microflake_cell_dp_index"
    private val LEGACY_CELL_DP_LEVELS = floatArrayOf(.9f, 1f, 1.2f, 1.6f, 2f, 2.4f, 2.7f, 3f, 3.2f)

    /** 上一版的流动精细度键与它的六档梯度，只用于迁移。 */
    private const val LEGACY_KEY_FLOW_INDEX = "jacobi_iterations_index"
    private val LEGACY_FLOW_LEVELS = intArrayOf(20, 30, 40, 60, 80, 100)

    /**
     * 粒子大小档位（dp），十六档。
     *
     * **未设置时不往模型里写这条下限**（[cellDpFloor] 返回 0）：材料在
     * 「720 逻辑像素 = 384 dp」的归一空间里建，一个 dp 是 [ParticleMicroflakeModel.DP_UNITS]
     * 个单位，而规则文件里自带的下限 `cell=1.65` 个单位 = **0.88 dp**，这个值在本梯度上
     * 没有正好对应的档。所以默认行为是「沿用规则里的下限」，滑杆停在最接近的一档
     * （[DEFAULT_CELL_INDEX]，即 0.84 dp）并标注「默认」；用户一旦拖动，就按选中的 dp
     * 显式生效。这样默认值下的画面与加入本设置之前**逐位相同**。
     */
    val CELL_DP_LEVELS = floatArrayOf(
        .64f, .72f, .84f, .96f, 1.08f, 1.29f, 1.5f, 1.6f,
        1.8f, 2f, 2.1f, 2.4f, 2.56f, 2.7f, 3f, 3.2f
    )

    /**
     * 流动精细度的取值范围：0–100 的连续整数，直接就是压力投影的迭代数。
     *
     * **0 表示完全不做流动求解**：整段压力更新都不跑，修正场保持构造时清空的零场
     * （见 `ParticleMicroflakeRenderer` 里 `skipPressure` 那一行）。
     */
    const val FLOW_MIN = 0
    const val FLOW_MAX = 100
    const val DEFAULT_FLOW = FLOW_MAX

    /**
     * 性能自动调节一次降多少流动精细度。取 20 是沿用上一版梯度相邻两档之差
     * （80 → 100），第四批实测「粒子大小与流动精细度各调一档」正是按这个幅度量的。
     */
    const val FLOW_EASE_STEP = 20

    /** 规则文件里自带的格子下限（归一空间的单位数）；[DEFAULT_CELL_INDEX] 由它推出来。 */
    const val RULES_CELL_FLOOR_UNITS = 1.65

    /**
     * 未设置时滑杆停的那一档：梯度上最接近规则自带下限（0.88 dp）的档位。
     * 由取值算出来而不是写死，改梯度时不会与实际行为脱节。
     */
    val DEFAULT_CELL_INDEX: Int = run {
        val rulesDp = (RULES_CELL_FLOOR_UNITS / ParticleMicroflakeModel.DP_UNITS).toFloat()
        CELL_DP_LEVELS.indices.minByOrNull { abs(CELL_DP_LEVELS[it] - rulesDp) } ?: 0
    }

    fun preferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 用户是否显式选过粒子大小；未选过时走规则自带的下限。 */
    fun isCellExplicit(context: Context): Boolean =
        preferences(context).contains(KEY_CELL_INDEX)

    fun cellIndex(context: Context): Int = preferences(context)
        .getInt(KEY_CELL_INDEX, DEFAULT_CELL_INDEX).coerceIn(0, CELL_DP_LEVELS.lastIndex)

    fun flowDetail(context: Context): Int = preferences(context)
        .getInt(KEY_FLOW_DETAIL, DEFAULT_FLOW).coerceIn(FLOW_MIN, FLOW_MAX)

    /** 颜色精度是不是「标准」（8 位定点）；默认 false，即高精度的 16 位浮点。 */
    fun isColorStandard(context: Context): Boolean =
        preferences(context).getBoolean(KEY_COLOR_STANDARD, false)

    /** 性能自动调节是否打开；默认关。 */
    fun isAutoTuneOn(context: Context): Boolean =
        preferences(context).getBoolean(KEY_AUTO_TUNE, false)

    fun style(context: Context): ParticleStyle =
        ParticleStyle.of(preferences(context).getString(KEY_STYLE, null))

    fun setStyle(context: Context, style: ParticleStyle) {
        preferences(context).edit().putString(KEY_STYLE, style.key).apply()
        apply(context)
    }

    fun driftAmountIndex(context: Context): Int = preferences(context)
        .getInt(KEY_DRIFT_AMOUNT, DEFAULT_DRIFT_AMOUNT_INDEX).coerceIn(0, DRIFT_AMOUNT_LEVELS.lastIndex)

    fun setDriftAmountIndex(context: Context, index: Int) {
        preferences(context).edit()
            .putInt(KEY_DRIFT_AMOUNT, index.coerceIn(0, DRIFT_AMOUNT_LEVELS.lastIndex)).apply()
        apply(context)
    }

    /** 「粒子数量」档位的文案，例如 0.2× / 1.0× / 1.6×。 */
    fun driftAmountLabel(index: Int): String =
        String.format("%.1f×", DRIFT_AMOUNT_LEVELS[index.coerceIn(0, DRIFT_AMOUNT_LEVELS.lastIndex)])

    fun driftSizeIndex(context: Context): Int = preferences(context)
        .getInt(KEY_DRIFT_SIZE, DEFAULT_DRIFT_SIZE_INDEX).coerceIn(0, DRIFT_SIZE_LEVELS.lastIndex)

    fun setDriftSizeIndex(context: Context, index: Int) {
        preferences(context).edit()
            .putInt(KEY_DRIFT_SIZE, index.coerceIn(0, DRIFT_SIZE_LEVELS.lastIndex)).apply()
        apply(context)
    }

    /** 「粒子大小」档位的文案，例如 0.5× / 1.0× / 2.0×。 */
    fun driftSizeLabel(index: Int): String =
        String.format("%.1f×", DRIFT_SIZE_LEVELS[index.coerceIn(0, DRIFT_SIZE_LEVELS.lastIndex)])

    fun setCellIndex(context: Context, index: Int) {
        markUserChanged(context)
        preferences(context).edit()
            .putInt(KEY_CELL_INDEX, index.coerceIn(0, CELL_DP_LEVELS.lastIndex)).apply()
        apply(context)
    }

    fun setFlowDetail(context: Context, value: Int) {
        markUserChanged(context)
        preferences(context).edit()
            .putInt(KEY_FLOW_DETAIL, value.coerceIn(FLOW_MIN, FLOW_MAX)).apply()
        apply(context)
    }

    fun setColorStandard(context: Context, standard: Boolean) {
        preferences(context).edit().putBoolean(KEY_COLOR_STANDARD, standard).apply()
        apply(context)
    }

    fun setAutoTune(context: Context, on: Boolean) {
        preferences(context).edit().putBoolean(KEY_AUTO_TUNE, on).apply()
        apply(context)
    }

    /** 恢复默认：全部设置项退回「未设置」（粒子样式回到缺省的飘散），也就是各项的默认行为。 */
    fun restoreDefaults(context: Context) {
        preferences(context).edit()
            .remove(KEY_CELL_INDEX)
            .remove(KEY_FLOW_DETAIL)
            .remove(KEY_COLOR_STANDARD)
            .remove(KEY_AUTO_TUNE)
            .remove(KEY_STYLE)
            .remove(KEY_DRIFT_AMOUNT)
            .remove(KEY_DRIFT_SIZE)
            .remove(LEGACY_KEY_DRIFT_AMOUNT)
            .remove(KEY_AUTO_STEPS)
            .remove(KEY_AUTO_ROUNDS)
            .remove(KEY_USER_CHANGED)
            .apply()
        apply(context)
    }

    // ---- 性能自动调节：按实测帧率自动降一档（默认关，规则见 [ParticleAutoDowngrade]）----

    private const val KEY_AUTO_STEPS = "auto_eased_steps"
    private const val KEY_AUTO_ROUNDS = "auto_poor_rounds"
    private const val KEY_USER_CHANGED = "user_changed_after_auto"

    /** 已经自动调低过几档；界面用它决定要不要显示那条提示。 */
    fun autoEasedSteps(context: Context): Int = preferences(context).getInt(KEY_AUTO_STEPS, 0)

    /** 用户自己动过设置之后就不再自动调整；由 [setCellIndex] / [setFlowDetail] 记下。 */
    private fun markUserChanged(context: Context) {
        if (autoEasedSteps(context) <= 0) return
        preferences(context).edit().putBoolean(KEY_USER_CHANGED, true).apply()
    }

    /**
     * 一轮动画收尾时把「呈现帧数 / 应有帧数」交给规则判定；判定说要调就写设置。
     * 返回 true 表示这一次真的改了档位（界面据此刷新）。
     */
    fun onRoundMeasured(context: Context, ratio: Float): Boolean {
        if (!ParticlePerfTuning.autoDowngrade) return false
        val preferences = preferences(context)
        val decision = ParticleAutoDowngrade.decide(ParticleAutoDowngrade.State(
            ratio, preferences.getInt(KEY_AUTO_ROUNDS, 0), cellIndex(context), flowDetail(context),
            isCellExplicit(context), autoEasedSteps(context),
            preferences.getBoolean(KEY_USER_CHANGED, false)))
        val editor = preferences.edit().putInt(KEY_AUTO_ROUNDS, decision.poorRounds)
        if (decision.downgrade) {
            editor.putInt(KEY_CELL_INDEX, decision.cellIndex)
                .putInt(KEY_FLOW_DETAIL, decision.flowDetail)
                .putInt(KEY_AUTO_STEPS, autoEasedSteps(context) + decision.appliedDelta)
        }
        editor.apply()
        if (decision.downgrade) apply(context)
        return decision.downgrade
    }

    /** 模型读到的 dp 下限；[explicit] 为假时写 0，即「不加下限、沿用规则里的取值」。 */
    fun cellDpFloor(index: Int, explicit: Boolean): Float =
        if (!explicit) 0f else CELL_DP_LEVELS[index.coerceIn(0, CELL_DP_LEVELS.lastIndex)]

    fun cellDpValue(index: Int): Float = CELL_DP_LEVELS[index.coerceIn(0, CELL_DP_LEVELS.lastIndex)]

    /**
     * 档位的 dp 文案：整十分位写一位小数（1.5 / 2.0 / 3.2），其余写两位（0.64 / 1.08 / 2.56）。
     * 不用通用的浮点格式化，是为了让梯度上每一档显示成它被定义时的样子。
     */
    fun cellDpLabel(index: Int): String {
        val value = cellDpValue(index)
        val hundredths = (value * 100f).roundToInt()
        return if (hundredths % 10 == 0) String.format("%.1f", value)
        else String.format("%.2f", value)
    }

    /**
     * 把更早那一版九档梯度里**用户显式选过**的那一档搬到新梯度上最接近的一档。
     *
     * 那一版的最低一档本来就是「不写下限」，与「未设置」等价，所以那一档只把旧键删掉，
     * 默认行为分毫不变。旧键删掉即为迁移完成，不会跑第二次。
     */
    private fun migrateLegacyCellIndex(preferences: SharedPreferences, editor: SharedPreferences.Editor) {
        if (!preferences.contains(LEGACY_KEY_CELL_INDEX)) return
        val legacy = preferences.getInt(LEGACY_KEY_CELL_INDEX, 0)
        editor.remove(LEGACY_KEY_CELL_INDEX)
        if (legacy in 1 until LEGACY_CELL_DP_LEVELS.size && !preferences.contains(KEY_CELL_INDEX)) {
            val dp = LEGACY_CELL_DP_LEVELS[legacy]
            val nearest = CELL_DP_LEVELS.indices.minByOrNull { abs(CELL_DP_LEVELS[it] - dp) }
            if (nearest != null) editor.putInt(KEY_CELL_INDEX, nearest)
        }
    }

    /**
     * 把上一版六档梯度里的流动精细度**按取值**搬过来：第 k 档存的是下标，新键存的是迭代数。
     * 最高一档（100）本来就是默认值，搬过来仍是 100，与「未设置」等价，所以只删旧键。
     */
    private fun migrateLegacyFlow(preferences: SharedPreferences, editor: SharedPreferences.Editor) {
        if (!preferences.contains(LEGACY_KEY_FLOW_INDEX)) return
        val legacy = preferences.getInt(LEGACY_KEY_FLOW_INDEX, LEGACY_FLOW_LEVELS.lastIndex)
        editor.remove(LEGACY_KEY_FLOW_INDEX)
        if (legacy in LEGACY_FLOW_LEVELS.indices && legacy != LEGACY_FLOW_LEVELS.lastIndex &&
            !preferences.contains(KEY_FLOW_DETAIL)) {
            editor.putInt(KEY_FLOW_DETAIL, LEGACY_FLOW_LEVELS[legacy])
        }
    }

    /**
     * 把上一版「粒子数量」（0.5×～1.5×）里选过的倍率按取值搬到新梯度（0.2×～1.6×）上：新梯度包含旧梯度的每一档，
     * 倍率不变。旧值是 1.0× 时与「未设置」等价，只删旧键。
     */
    private fun migrateLegacyDriftAmount(preferences: SharedPreferences, editor: SharedPreferences.Editor) {
        if (!preferences.contains(LEGACY_KEY_DRIFT_AMOUNT)) return
        val legacy = preferences.getInt(LEGACY_KEY_DRIFT_AMOUNT, 5)
        editor.remove(LEGACY_KEY_DRIFT_AMOUNT)
        if (legacy in LEGACY_DRIFT_AMOUNT_LEVELS.indices && !preferences.contains(KEY_DRIFT_AMOUNT)) {
            val amount = LEGACY_DRIFT_AMOUNT_LEVELS[legacy]
            val nearest = DRIFT_AMOUNT_LEVELS.indices.minByOrNull { abs(DRIFT_AMOUNT_LEVELS[it] - amount) }
            if (nearest != null && nearest != DEFAULT_DRIFT_AMOUNT_INDEX) editor.putInt(KEY_DRIFT_AMOUNT, nearest)
        }
    }

    /** 进程启动时与每次改动之后各调一次。 */
    fun apply(context: Context) {
        val preferences = preferences(context)
        if (preferences.contains(LEGACY_KEY_CELL_INDEX) || preferences.contains(LEGACY_KEY_FLOW_INDEX) ||
            preferences.contains(LEGACY_KEY_DRIFT_AMOUNT)) {
            val editor = preferences.edit()
            migrateLegacyCellIndex(preferences, editor)
            migrateLegacyFlow(preferences, editor)
            migrateLegacyDriftAmount(preferences, editor)
            // `apply()` 对内存里的那份映射是同步生效的，紧接着的读取拿到的就是迁移后的值；
            // 磁盘写入异步进行，不占主线程。
            editor.apply()
        }
        ParticleStyle.current = style(context)
        ParticleStyle.driftAmount = DRIFT_AMOUNT_LEVELS[driftAmountIndex(context)]
        ParticleStyle.driftSize = DRIFT_SIZE_LEVELS[driftSizeIndex(context)]
        // 仅调试包：验收时把某段参考样式钉住（键由 adb 经 run-as 写入，正式包不读）。
        ParticleDriftStyles.forcedForTests =
            if (BuildConfig.DEBUG) preferences.getString(KEY_DEBUG_FORCE_DRIFT_STYLE, null) else null
        ParticleDriftHardwareImport.verifyForTests =
            BuildConfig.DEBUG && preferences.getBoolean(KEY_DEBUG_VERIFY_ZERO_COPY, false)
        ParticlePerfTuning.applyUserTuning(
            cellDpFloor(cellIndex(context), isCellExplicit(context)),
            flowDetail(context),
            isColorStandard(context),
            isAutoTuneOn(context)
        )
    }
}
